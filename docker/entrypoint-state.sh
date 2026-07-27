#!/bin/bash
#
# entrypoint-state.sh - SageTV container entrypoint (state-managed).
#
# PID layout:
#     PID 1: tini  (--init)
#     PID 2: this script (entrypoint-state.sh)
#     PID N: java  sage.Sage ... (child of entrypoint-state, NOT PID 1)
#
# Java runs as a normal child so /opt/sagetv/server/{startsage,stopsage}
# can SIGTERM it and let this script respawn it in-place WITHOUT
# restarting the container.
#
# Responsibilities (folded in from sagesupervisor + state-arch design):
#   1. Acquire a per-container flock so two builds can't share state.
#   2. Stage Sage.properties + Wiz.bin from $STATE_DIR into
#      /opt/sagetv/server/ (rm-then-cp so JVM can rename-replace later).
#   3. Spawn / respawn Sage (.sage-run flag controls it; sage.pid tracks).
#   4. Periodically snapshot live Sage.properties + Wiz.bin into
#      $STATE_DIR/.snapshots/ (keep N most recent per build).
#   5. On SIGTERM/SIGINT (from `docker stop`):
#        - tell supervisor loop to stop (rm .sage-run)
#        - SIGTERM Sage, wait grace period, SIGKILL if needed
#        - mirror live state back into $STATE_DIR
#        - release flock
#
# Standalone mode: if STATE_DIR or CONTAINER_NAME is unset/empty, this
# script logs 'standalone mode (no state management)' and simply exec's
# the java command directly, so the public image is usable for a plain
# `docker run` without any state volumes. Lock/stage/snapshot/mirror
# logic engages ONLY when BOTH env vars are set.
#
# Required env (state-managed mode):
#     CONTAINER_NAME    e.g. sagetv-ng  (used in lock holder + snapshot suffix)
#     STATE_DIR         e.g. /opt/sagetv/state/ng
#
# Optional env:
#     SNAPSHOT_INTERVAL  seconds between snapshots          (default 60)
#     SNAPSHOT_KEEP      number of snapshots to retain      (default 10)
#     SAGE_TERM_GRACE    seconds to wait for sage to exit   (default 90)
#     RESPAWN_BACKOFF    seconds before respawn after crash (default 3)
#
# Usage (from container CMD):
#     /usr/local/bin/entrypoint-state.sh \
#         java -Djava.awt.headless=true ... -cp Sage.jar:JARs/* \
#              sage.Sage 0 0 x "sagetv Sage.properties"
#
# NOTE the LAST arg must be the single string "sagetv Sage.properties"
# (with embedded space). Sage splits on first space; passing two array
# elements causes IllegalArgumentException at Sage.java:986.
#
set -u

# ---------- config ----------
SAGE_DIR=/opt/sagetv/server
RUNFILE=$SAGE_DIR/.sage-run
PIDFILE=$SAGE_DIR/sage.pid

# ---------- standalone fallback ----------
# When state management is not configured (no STATE_DIR / CONTAINER_NAME),
# run the supplied command directly so a plain `docker run` of the public
# image works with no mounted state. This is the PII-free default.
if [ -z "${STATE_DIR:-}" ] || [ -z "${CONTAINER_NAME:-}" ]; then
  printf '[%s] [entrypoint-state] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" \
    "standalone mode (no state management)"
  [ "$#" -ge 1 ] || {
    printf '[%s] [entrypoint-state] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" \
      "FATAL: no command supplied"
    exit 1
  }
  cd "$SAGE_DIR" 2>/dev/null || true
  exec "$@"
fi

: "${SNAPSHOT_INTERVAL:=60}"
: "${SNAPSHOT_KEEP:=10}"
: "${SAGE_TERM_GRACE:=90}"
: "${RESPAWN_BACKOFF:=3}"
: "${SECURITY_UPDATE_ENABLED:=true}"
: "${SECURITY_UPDATE_INTERVAL:=604800}"
: "${SECURITY_UPDATE_ON_START:=true}"
: "${SECURITY_DIR:=/opt/sagetv/security}"
: "${APPARMOR_DIR:=$SECURITY_DIR/apparmor}"
: "${RISK_DIR:=$SECURITY_DIR/risk}"
: "${APPARMOR_PROFILE_URL:=}"
: "${RISK_PROFILE_URL:=}"

LOCK_DIR=$STATE_DIR/.locks
SNAP_DIR=$STATE_DIR/.snapshots
LOCK_FILE=$LOCK_DIR/sagetv.lock
LOCK_FD=9

# Files mirrored both ways between $STATE_DIR and $SAGE_DIR.
#  STATE_FILES   = mirrored in at start, mirrored out on shutdown,
#                  AND snapshotted every $SNAPSHOT_INTERVAL.
#
# Per-install identity files (SageTVLocator keys, sdauth, sdmd5*,
# filetracker.properties, SageTVPlugins{,V9}.xml, clients/*) are NOT
# staged here and are NOT baked into the image: this image is produced
# by a single reproducible `docker build`, and those per-install identity
# files are provided at RUNTIME via mounted state, never baked into the
# image.
STATE_FILES=( Sage.properties Wiz.bin )

JPID=""
SNAP_PID=""
SEC_PID=""

# ---------- logging ----------
log() {
  printf '[%s] [entrypoint-state] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}
die() { log "FATAL: $*"; exit 1; }

# ---------- lock ----------
acquire_lock() {
  mkdir -p "$LOCK_DIR" "$SNAP_DIR" || die "cannot create state subdirs"
  exec {LOCK_FD}>"$LOCK_FILE" || die "cannot open lock file $LOCK_FILE"
  if ! flock -n "$LOCK_FD"; then
    holder="$(cat "$LOCK_FILE" 2>/dev/null || true)"
    die "state lock held by another container: '${holder:-unknown}' (file: $LOCK_FILE)"
  fi
  : > "$LOCK_FILE"
  printf '%s pid=%d host=%s started=%s\n' \
    "$CONTAINER_NAME" "$$" "$(hostname)" "$(date -Iseconds)" >"$LOCK_FILE"
  log "acquired state lock for $CONTAINER_NAME -> $LOCK_FILE"
}

release_lock() {
  # Closing FD releases flock; clear file content so it's obvious nobody holds it.
  : > "$LOCK_FILE" 2>/dev/null || true
  eval "exec ${LOCK_FD}>&-" 2>/dev/null || true
  log "released state lock"
}

# ---------- staging ----------
# _stage_one IN  <src> <dst>   copy src -> dst (rm -rf dst first)
# _stage_one OUT <src> <dst>   atomic-ish copy src -> dst via .tmp + rename
# Handles both regular files and directories (e.g. clients/).
_stage_one() {
  local mode=$1 src=$2 dst=$3
  if [ ! -e "$src" ]; then
    log "WARN: $src missing; skipping ($mode)"
    return 0
  fi
  case $mode in
    IN)
      rm -rf "$dst" || { log "ERROR rm $dst"; return 1; }
      cp -a "$src" "$dst" || { log "ERROR cp $src -> $dst"; return 1; }
      ;;
    OUT)
      local tmp=$dst.$$.tmp
      rm -rf "$tmp" 2>/dev/null
      if cp -a "$src" "$tmp" && rm -rf "$dst" && mv -f "$tmp" "$dst"; then
        :
      else
        log "ERROR mirroring $src -> $dst"
        rm -rf "$tmp" 2>/dev/null
        return 1
      fi
      ;;
    *) log "BUG: _stage_one mode=$mode"; return 2 ;;
  esac
  if [ -f "$dst" ]; then
    log "$mode $(basename "$src") ($(stat -c%s "$dst") bytes)"
  else
    log "$mode $(basename "$src") (dir)"
  fi
}

stage_in() {
  log "staging state in from $STATE_DIR"
  for f in "${STATE_FILES[@]}"; do
    _stage_one IN "$STATE_DIR/$f" "$SAGE_DIR/$f" || die "stage_in failed on $f"
  done
}

mirror_out() {
  log "mirroring live state -> $STATE_DIR"
  for f in "${STATE_FILES[@]}"; do
    _stage_one OUT "$SAGE_DIR/$f" "$STATE_DIR/$f" || true
  done
}

# ---------- HDHR debug log redirect ----------
# Today's libHDHomeRunCapture.so (hvdiag-instrumented) writes to a
# hard-coded /tmp/hdhrvid.log inside the container. Redirect to a
# host-visible log file via symlink so we can tail it from the host.
setup_hdhr_log() {
  if [ -n "${HDHRVID_LOG:-}" ]; then
    mkdir -p "$(dirname "$HDHRVID_LOG")" 2>/dev/null || true
    : > "$HDHRVID_LOG" 2>/dev/null || true
    rm -f /tmp/hdhrvid.log
    ln -sf "$HDHRVID_LOG" /tmp/hdhrvid.log
    log "HDHR diag log: /tmp/hdhrvid.log -> $HDHRVID_LOG"
  fi
}

# ---------- snapshots ----------
snapshot_once() {
  local ts
  ts=$(date '+%Y%m%d-%H%M%S')
  for f in "${STATE_FILES[@]}"; do
    src=$SAGE_DIR/$f
    [ -f "$src" ] || continue
    cp -p "$src" "$SNAP_DIR/${f}.${ts}.${CONTAINER_NAME}" 2>/dev/null || true
  done
  # Retention: keep $SNAPSHOT_KEEP per-file per-container.
  for f in "${STATE_FILES[@]}"; do
    # shellcheck disable=SC2012
    ls -1t "$SNAP_DIR/${f}."*".${CONTAINER_NAME}" 2>/dev/null \
      | tail -n +"$((SNAPSHOT_KEEP + 1))" \
      | xargs -r rm -f
  done
}

snapshot_loop() {
  # Detached loop; receives SIGTERM from on_term -> exits cleanly.
  trap 'exit 0' TERM INT
  while true; do
    sleep "$SNAPSHOT_INTERVAL" &
    wait $! 2>/dev/null || true
    snapshot_once
  done
}

# ---------- security profile updates (in-container only) ----------
security_update_once() {
  log "security update: starting"
  mkdir -p "$APPARMOR_DIR" "$RISK_DIR" 2>/dev/null || true

  if [ -n "$APPARMOR_PROFILE_URL" ]; then
    if command -v curl >/dev/null 2>&1; then
      curl -fsSL "$APPARMOR_PROFILE_URL" -o "$APPARMOR_DIR/sagetv-ng.profile" || log "WARN: failed AppArmor profile download"
    elif command -v wget >/dev/null 2>&1; then
      wget -qO "$APPARMOR_DIR/sagetv-ng.profile" "$APPARMOR_PROFILE_URL" || log "WARN: failed AppArmor profile download"
    else
      log "WARN: curl/wget unavailable; skipping AppArmor profile download"
    fi
  fi

  if [ -n "$RISK_PROFILE_URL" ]; then
    if command -v curl >/dev/null 2>&1; then
      curl -fsSL "$RISK_PROFILE_URL" -o "$RISK_DIR/plugin-risk-profile.json" || log "WARN: failed risk profile download"
    elif command -v wget >/dev/null 2>&1; then
      wget -qO "$RISK_DIR/plugin-risk-profile.json" "$RISK_PROFILE_URL" || log "WARN: failed risk profile download"
    else
      log "WARN: curl/wget unavailable; skipping risk profile download"
    fi
  fi

  if command -v freshclam >/dev/null 2>&1; then
    freshclam || log "WARN: freshclam update failed"
  else
    log "security update: freshclam unavailable"
  fi

  if command -v grype >/dev/null 2>&1; then
    grype db update || log "WARN: grype db update failed"
  else
    log "security update: grype unavailable"
  fi

  log "security update: completed"
}

security_loop() {
  trap 'exit 0' TERM INT

  if [ "$SECURITY_UPDATE_ON_START" = "true" ]; then
    security_update_once
  fi

  while true; do
    sleep "$SECURITY_UPDATE_INTERVAL" &
    wait $! 2>/dev/null || true
    security_update_once
  done
}

# ---------- shutdown ----------
on_term() {
  # Block re-entry: a second SIGTERM during shutdown would otherwise
  # double-release the lock or trample mirror_out mid-rename.
  trap '' TERM INT
  log "received termination signal; beginning orderly shutdown"
  # 1) Tell supervisor loop not to respawn after sage exits.
  rm -f "$RUNFILE"

  # 2) Stop the snapshot loop FIRST so it doesn't race the mirror_out.
  if [ -n "$SNAP_PID" ] && kill -0 "$SNAP_PID" 2>/dev/null; then
    kill -TERM "$SNAP_PID" 2>/dev/null || true
    wait "$SNAP_PID" 2>/dev/null || true
  fi

  if [ -n "$SEC_PID" ] && kill -0 "$SEC_PID" 2>/dev/null; then
    kill -TERM "$SEC_PID" 2>/dev/null || true
    wait "$SEC_PID" 2>/dev/null || true
  fi

  # 3) SIGTERM sage; wait up to SAGE_TERM_GRACE; SIGKILL if stubborn.
  if [ -n "$JPID" ] && kill -0 "$JPID" 2>/dev/null; then
    log "forwarding SIGTERM to sage pid=$JPID (grace ${SAGE_TERM_GRACE}s)"
    kill -TERM "$JPID" 2>/dev/null || true
    for _i in $(seq 1 "$SAGE_TERM_GRACE"); do
      kill -0 "$JPID" 2>/dev/null || break
      sleep 1
    done
    if kill -0 "$JPID" 2>/dev/null; then
      log "sage did not exit in ${SAGE_TERM_GRACE}s; SIGKILL"
      kill -KILL "$JPID" 2>/dev/null || true
      sleep 1
    fi
  fi
  rm -f "$PIDFILE"

  # 4) Sage has now (hopefully) flushed Wiz.bin / Sage.properties to disk;
  #    mirror them out to STATE_DIR.
  mirror_out

  # 5) Final snapshot tagged "shutdown" for forensics.
  local ts
  ts=$(date '+%Y%m%d-%H%M%S')
  for f in "${STATE_FILES[@]}"; do
    src=$STATE_DIR/$f
    [ -f "$src" ] || continue
    cp -p "$src" "$SNAP_DIR/${f}.shutdown.${ts}.${CONTAINER_NAME}" 2>/dev/null || true
  done

  release_lock
  log "shutdown complete"
  exit 0
}
trap on_term TERM INT

# ---------- main ----------
[ "$#" -ge 1 ] || die "no command supplied; expected: $0 java <opts> sage.Sage ..."

cd "$SAGE_DIR" || die "cannot cd $SAGE_DIR"

acquire_lock
stage_in
setup_hdhr_log

# Start snapshot loop in background.
snapshot_loop &
SNAP_PID=$!
log "snapshot loop pid=$SNAP_PID interval=${SNAPSHOT_INTERVAL}s keep=${SNAPSHOT_KEEP}"

if [ "$SECURITY_UPDATE_ENABLED" = "true" ]; then
  security_loop &
  SEC_PID=$!
  log "security loop pid=$SEC_PID interval=${SECURITY_UPDATE_INTERVAL}s"
else
  log "security loop disabled"
fi

# Default: spawn sage on entrypoint startup.
touch "$RUNFILE"
log "supervisor up; java cmd: $*"

# Supervisor loop (folded from sagesupervisor).
while true; do
  if [ ! -f "$RUNFILE" ]; then
    # Idle (after stopsage). Background sleep so traps fire promptly.
    sleep 2 &
    wait $! 2>/dev/null || true
    continue
  fi

  log "starting sage"
  "$@" &
  JPID=$!
  echo "$JPID" >"$PIDFILE"
  log "sage spawned pid=$JPID"

  # `wait` is interruptible by traps; retry until child is reaped.
  while kill -0 "$JPID" 2>/dev/null; do
    wait "$JPID" 2>/dev/null || true
  done
  ec=$?
  rm -f "$PIDFILE"
  log "sage exited (status=$ec)"
  JPID=""

  if [ -f "$RUNFILE" ]; then
    log "RUNFILE present; auto-restarting in ${RESPAWN_BACKOFF}s"
    sleep "$RESPAWN_BACKOFF" &
    wait $! 2>/dev/null || true
  else
    log "RUNFILE absent (intentional stop); idling until startsage"
  fi
done
