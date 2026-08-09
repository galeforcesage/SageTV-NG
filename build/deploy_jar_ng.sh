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
#   CONTAINER   target container name           (default: sagetv-ng)
#   STAGE       staging dir with Sage.jar+JARs/ (default: ./serverrelease)
#   SHA         commit stamp for DEPLOYED_COMMIT (default: git rev-parse --short)
#   SKIP_JDEPS  set to 1 to skip the preflight   (NOT recommended)
set -euo pipefail

CONTAINER="${CONTAINER:-sagetv-ng}"
STAGE="${STAGE:-./serverrelease}"
SRV=/opt/sagetv/server
SHA="${SHA:-$(git rev-parse --short HEAD 2>/dev/null || echo unknown)}"
TS="$(date +%Y%m%d-%H%M%S)"

log() { printf '\n=== %s ===\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# -- 0. sanity -------------------------------------------------------------
[ -f "$STAGE/Sage.jar" ] || die "missing $STAGE/Sage.jar"
[ -d "$STAGE/JARs" ]     || die "missing $STAGE/JARs/ (must ship the runtime classpath, not just Sage.jar)"
ls "$STAGE"/JARs/*.jar >/dev/null 2>&1 || die "no jars in $STAGE/JARs/"
docker inspect "$CONTAINER" >/dev/null 2>&1 || die "container '$CONTAINER' not found"

# -- 1. preflight: no unresolved app classes on the NEW classpath ----------
# Catches the jcifs-ng-style dependency drift BEFORE we touch the server.
if [ "${SKIP_JDEPS:-0}" != "1" ] && command -v jdeps >/dev/null 2>&1; then
  log "preflight: jdeps --missing-deps"
  missing="$(jdeps --multi-release 21 -cp "$STAGE/JARs/*" --missing-deps "$STAGE/Sage.jar" 2>/dev/null \
              | grep -vE '^$|^Sage\.jar' || true)"
  if [ -n "$missing" ]; then
    echo "$missing" | head -40
    die "unresolved classes on new classpath (JARs/ is incomplete for this Sage.jar) - refusing to deploy"
  fi
  echo "jdeps: no missing dependencies"
else
  echo "WARN: skipping jdeps preflight (jdeps not found or SKIP_JDEPS=1)"
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

# -- 6. swap in new Sage.jar + JARs/ ---------------------------------------
log "installing new Sage.jar + JARs/"
docker exec "$CONTAINER" bash -c "
  set -e
  rm -f '$SRV'/JARs/*.jar
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
for _ in $(seq 1 90); do
  if docker exec "$CONTAINER" bash -c 'ss -lnt 2>/dev/null | grep -q ":7818 "'; then echo "7818 listening"; break; fi
  sleep 1
done

log "verify"
docker exec "$CONTAINER" bash -c 'ss -lnt 2>/dev/null | grep -E ":(7818|31099|42024) "' || echo "WARN: sage ports not listening yet"
cat_err="$(docker exec "$CONTAINER" bash -c "tail -n 400 '$SRV/sagetv_0.txt' 2>/dev/null | grep -cE 'Could not initialize class sage.Catbert|ExceptionInInitializer'" || echo '?')"
if [ "$cat_err" != "0" ]; then
  echo "!!! Catbert/init errors detected ($cat_err). Rollback with:"
  echo "    docker exec $CONTAINER bash -c \"rm -f $SRV/JARs/*.jar; cp -a $SRV/JARs.bak-$TS/*.jar $SRV/JARs/; cp -a $SRV/Sage.jar.bak-$TS $SRV/Sage.jar\""
  echo "    then SIGTERM the supervised java to respawn."
  die "deploy produced Catbert init errors"
fi
echo "Catbert init errors: $cat_err"
log "deploy_jar_ng.sh done (backups: Sage.jar.bak-$TS, JARs.bak-$TS)"
