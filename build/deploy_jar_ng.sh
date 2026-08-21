#!/bin/bash
#
# deploy_jar_ng.sh - update a running state-managed sagetv-ng container to a
# freshly built SageTV-NG release (Sage.jar + JARs/ runtime classpath).
#
# WHY THIS DEPLOYS JARs/ TOO (hard-won lesson):
#   The container runs java with `-cp Sage.jar:JARs/*`. A Sage.jar-only
#   hot-swap is UNSAFE: whenever build.gradle dependencies change (e.g.
#   jcifs 1.1.6 -> jcifs-ng 2.1.37, or new bcprov/jogl/AppleJavaExtensions),
#   the new Sage.jar references classes that the frozen JARs/ dir does not
#   contain. sage.Catbert's init-time reflective scan of sage.api.* then
#   throws ExceptionInInitializerError (NoClassDefFoundError), Catbert never
#   initializes, and the server boots but never binds its ports. Always ship
#   Sage.jar and JARs/ together, and gate on `jdeps --missing-deps`.
#
# WHY THIS NO LONGER WIPES JARs/ (second hard-won lesson):
#   This script used to `rm -f JARs/*.jar` before copying the staged set. That
#   is only safe if staging is a superset of the live dir, and it is not: the
#   live server carries ~88 jars (Jetty, sagex-api, phoenix, plugins) while a
#   clean gradle build emits ~26. Running it destroyed the web UI and every
#   plugin. The default is now an OVERLAY: staged jars replace their live
#   namesakes and everything else is left alone. Pruning is still available
#   via PRUNE_JARS=1, but only after a coverage check proves staging covers
#   every live jar.
#
# USAGE:
#   1. Build artifacts from the repo root:
#          ./gradlew sageJar copyRuntimeJars
#      then assemble the authoritative JARs/ set (see build/copyserverfiles.sh):
#          third_party/{Oracle,Apache,Lucene}/*.jar + buildoutput/runtime-jars/*.jar
#      into a staging dir, e.g. buildoutput/serverrelease/{Sage.jar,JARs/}.
#   2. Copy that staging dir to the docker host and run this script THERE:
#          STAGE=/path/to/serverrelease ./deploy_jar_ng.sh
#
# CONFIG (env overridable):
#   CONTAINER   target container name           (default: sagetv-ng; deployments
#               whose container is named differently must set this explicitly)
#   STAGE       staging dir with Sage.jar+JARs/ (default: ./serverrelease)
#   SHA         commit stamp for DEPLOYED_COMMIT (default: git rev-parse --short)
#   SKIP_JDEPS  set to 1 to skip the preflight   (NOT recommended)
#   PRUNE_JARS  set to 1 to delete live jars absent from staging (guarded)
#   PORTS       ports to verify after restart    (default: 8080 31099 42024)
set -euo pipefail

CONTAINER="${CONTAINER:-sagetv-ng}"
STAGE="${STAGE:-./serverrelease}"
SRV=/opt/sagetv/server
SHA="${SHA:-$(git rev-parse --short HEAD 2>/dev/null || echo unknown)}"
TS="$(date +%Y%m%d-%H%M%S)"
PORTS="${PORTS:-8080 31099 42024}"

log() { printf '\n=== %s ===\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# -- 0. sanity -------------------------------------------------------------
[ -f "$STAGE/Sage.jar" ] || die "missing $STAGE/Sage.jar"
[ -d "$STAGE/JARs" ]     || die "missing $STAGE/JARs/ (must ship the runtime classpath, not just Sage.jar)"
ls "$STAGE"/JARs/*.jar >/dev/null 2>&1 || die "no jars in $STAGE/JARs/"
docker inspect "$CONTAINER" >/dev/null 2>&1 || die "container '$CONTAINER' not found (set CONTAINER=<name> if yours is named differently)"

# -- 1. preflight: no unresolved app classes on the NEW classpath ----------
# Catches the jcifs-ng-style dependency drift BEFORE we touch the server.
#
# This check was silently useless for a long time: jdeps aborts with
# "ClassFileError: unexpected tag" on both the new jar and the running one, and
# the old `2>/dev/null || true` swallowed both the error and the exit status,
# leaving $missing empty and printing a confident "no missing dependencies".
# A preflight that cannot fail is worse than no preflight, so a jdeps that
# refuses to run is now reported as UNVERIFIED rather than as a pass.
if [ "${SKIP_JDEPS:-0}" != "1" ] && command -v jdeps >/dev/null 2>&1; then
  log "preflight: jdeps --missing-deps"
  jdeps_err="$(mktemp)"
  if missing="$(jdeps --multi-release 21 -cp "$STAGE/JARs/*" --missing-deps "$STAGE/Sage.jar" 2>"$jdeps_err")"; then
    missing="$(printf '%s\n' "$missing" | grep -vE '^$|^Sage\.jar' || true)"
    if [ -n "$missing" ]; then
      printf '%s\n' "$missing" | head -40
      rm -f "$jdeps_err"
      die "unresolved classes on new classpath (JARs/ is incomplete for this Sage.jar) - refusing to deploy"
    fi
    echo "jdeps: no missing dependencies"
  else
    echo "WARN: jdeps could not analyze this classpath -- classpath is UNVERIFIED, not clean:"
    head -5 "$jdeps_err" >&2 || true
    echo "WARN: if the server fails to bind its ports after this deploy, suspect dependency drift first."
  fi
  rm -f "$jdeps_err"
else
  echo "WARN: skipping jdeps preflight (jdeps not found or SKIP_JDEPS=1) - classpath UNVERIFIED"
fi

# -- 2. show pre-state -----------------------------------------------------
log "pre-state ($CONTAINER)"
docker exec "$CONTAINER" cat "$SRV/DEPLOYED_COMMIT" 2>/dev/null || echo "(no stamp)"
docker exec "$CONTAINER" md5sum "$SRV/Sage.jar" || true

# -- 3. stage artifacts into the container ---------------------------------
log "staging artifacts into container"
docker cp "$STAGE/Sage.jar" "$CONTAINER":/tmp/Sage.jar.ng-new
docker exec "$CONTAINER" rm -rf /tmp/JARs.ng-new
docker cp "$STAGE/JARs" "$CONTAINER":/tmp/JARs.ng-new

# -- 4. backup current jar + JARs dir --------------------------------------
log "backup current Sage.jar + JARs/ -> *.bak-$TS"
docker exec "$CONTAINER" bash -c "cp -a '$SRV/Sage.jar' '$SRV/Sage.jar.bak-$TS' && cp -a '$SRV/JARs' '$SRV/JARs.bak-$TS'"

# -- 4b. refuse to restart on top of live work -----------------------------
# The step below SIGTERMs the server. Doing that while a tuner is recording
# corrupts the capture, and doing it during playback drops every viewer
# mid-stream. Neither is recoverable by retrying the deploy, so this is a hard
# gate rather than a warning. ALLOW_BUSY=1 overrides it deliberately.
#
# NOTE: `grep -c` exits 1 when it matches nothing, which under `set -e` would
# abort the script at exactly the "everything is idle" moment. Hence `|| true`
# on every counting command here.
log "safety: recordings and live transcodes"
n_ffmpeg="$(docker exec "$CONTAINER" bash -c "ps -eo args | grep -c '[f]fmpeg' || true" | tr -d '\r')"
recent="$(docker exec "$CONTAINER" bash -c "find / -xdev -maxdepth 4 \( -name '*.ts' -o -name '*.mpg' \) -mmin -2 2>/dev/null | head -5 || true" | tr -d '\r')"
n_recent="$(printf '%s\n' "$recent" | grep -c . || true)"
echo "ffmpeg processes: ${n_ffmpeg:-?}   recording-shaped files written in last 2min: $n_recent"

if [ "${ALLOW_BUSY:-0}" != "1" ]; then
  [ "${n_ffmpeg:-1}" = "0" ] || die "$n_ffmpeg ffmpeg process(es) running -- a restart would kill live playback/transcode mid-stream. Wait, or set ALLOW_BUSY=1 if you are certain."
  if [ "$n_recent" != "0" ]; then
    printf '%s\n' "$recent"
    die "files were written in the last 2 minutes -- a tuner may be recording. Refusing to restart. Set ALLOW_BUSY=1 to override."
  fi
  echo "safety: idle, safe to restart"
else
  echo "WARN: ALLOW_BUSY=1 -- restarting regardless of active recordings or playback"
fi

# -- 5. stop the supervised java (entrypoint respawns it in-place) ---------
# The state-managed entrypoint runs java as its child and respawns it when it
# exits (while the .sage-run RUNFILE exists). SIGTERM that child directly -
# do NOT use ./startsage (it spawns a second, unsupervised instance).
log "stopping supervised sage"
OLDPID="$(docker exec "$CONTAINER" bash -c 'ps -o pid,ppid -C java | awk "\$2==7{print \$1}"' || true)"
[ -n "$OLDPID" ] || OLDPID="$(docker exec "$CONTAINER" bash -c 'pgrep -f sage.Sage | head -1' || true)"
[ -n "$OLDPID" ] && docker exec "$CONTAINER" kill -TERM "$OLDPID" || echo "(no running java found)"
for _ in $(seq 1 60); do
  docker exec "$CONTAINER" bash -c "kill -0 ${OLDPID:-0} 2>/dev/null" || { echo "sage stopped"; break; }
  sleep 1
done

# -- 5b. coverage check: what would pruning actually delete? ---------------
# The live JARs/ dir carries far more than a gradle build produces (Jetty,
# sagex-api, phoenix, plugins). Enumerate the difference BEFORE touching it, so
# the destructive path can never run on an incomplete staging dir.
log "JARs/ coverage"
live_jars="$(docker exec "$CONTAINER" bash -c "cd '$SRV/JARs' && ls -1 *.jar 2>/dev/null" | tr -d '\r' | sort)"
stage_jars="$(cd "$STAGE/JARs" && ls -1 *.jar 2>/dev/null | sort)"
orphans="$(comm -23 <(printf '%s\n' "$live_jars") <(printf '%s\n' "$stage_jars") || true)"
n_live=$(printf '%s\n' "$live_jars" | grep -c . || true)
n_stage=$(printf '%s\n' "$stage_jars" | grep -c . || true)
n_orph=$(printf '%s\n' "$orphans" | grep -c . || true)
echo "live=$n_live staged=$n_stage live-only=$n_orph"

if [ "${PRUNE_JARS:-0}" = "1" ]; then
  MODE=prune
  if [ "$n_orph" != "0" ]; then
    printf '%s\n' "$orphans" | head -40
    die "PRUNE_JARS=1 would delete $n_orph live jar(s) that staging does not provide (Jetty/sagex-api/phoenix/plugins live here). Refusing. Deploy as an overlay instead, or complete the staging dir."
  fi
  echo "prune: staging covers every live jar, safe to replace wholesale"
else
  MODE=overlay
  [ "$n_orph" = "0" ] || echo "overlay: leaving $n_orph live-only jar(s) in place (set PRUNE_JARS=1 to remove, guarded)"
fi

# -- 6. swap in new Sage.jar + JARs/ ---------------------------------------
log "installing new Sage.jar + JARs/ (mode: $MODE)"
docker exec -e DO_PRUNE="${PRUNE_JARS:-0}" "$CONTAINER" bash -c "
  set -e
  if [ \"\$DO_PRUNE\" = '1' ]; then rm -f '$SRV'/JARs/*.jar; fi
  cp -a /tmp/JARs.ng-new/*.jar '$SRV'/JARs/
  cp -a /tmp/Sage.jar.ng-new  '$SRV'/Sage.jar
  chown -R sagetv:sagetv '$SRV'/JARs '$SRV'/Sage.jar
"
echo "$SHA" | docker exec -i "$CONTAINER" bash -c "cat > '$SRV/DEPLOYED_COMMIT'"
docker exec "$CONTAINER" md5sum "$SRV/Sage.jar"
echo "DEPLOYED_COMMIT: $(docker exec "$CONTAINER" cat "$SRV/DEPLOYED_COMMIT")"

# -- 7. wait for respawn + port bind, then verify a clean boot -------------
log "waiting for respawn + startup"
for _ in $(seq 1 40); do
  NEWPID="$(docker exec "$CONTAINER" bash -c 'ps -o pid,ppid -C java 2>/dev/null | awk "\$2==7{print \$1}"' || true)"
  [ -n "$NEWPID" ] && [ "$NEWPID" != "${OLDPID:-}" ] && { echo "new java pid=$NEWPID"; break; }
  sleep 1
done
# Port checks use bash's /dev/tcp, not `ss`. `ss` is present in this image but
# silently returns an empty table for the container's own sockets, so the old
# check reported "ports not listening" on a perfectly healthy deploy -- a false
# alarm that trains you to ignore the one line that would catch a real failure.
port_up() { docker exec "$CONTAINER" bash -c "(echo > /dev/tcp/127.0.0.1/$1) >/dev/null 2>&1"; }

FIRST_PORT="${PORTS%% *}"
for _ in $(seq 1 90); do
  if port_up "$FIRST_PORT"; then echo "$FIRST_PORT listening"; break; fi
  sleep 1
done

log "verify"
ports_down=0
for p in $PORTS; do
  if port_up "$p"; then echo "port $p UP"; else echo "port $p DOWN"; ports_down=1; fi
done
[ "$ports_down" = "0" ] || echo "WARN: sage ports not listening yet"
cat_err="$(docker exec "$CONTAINER" bash -c "tail -n 400 '$SRV/sagetv_0.txt' 2>/dev/null | grep -cE 'Could not initialize class sage.Catbert|ExceptionInInitializer'" || echo '?')"
if [ "$cat_err" != "0" ]; then
  echo "!!! Catbert/init errors detected ($cat_err). Rollback with:"
  echo "    docker exec $CONTAINER bash -c \"cp -a $SRV/JARs.bak-$TS/*.jar $SRV/JARs/; cp -a $SRV/Sage.jar.bak-$TS $SRV/Sage.jar\""
  echo "    then SIGTERM the supervised java to respawn."
  die "deploy produced Catbert init errors"
fi
echo "Catbert init errors: $cat_err"
log "deploy_jar_ng.sh done (backups: Sage.jar.bak-$TS, JARs.bak-$TS)"
