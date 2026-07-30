#!/bin/bash
# host-deploy-hdhr.sh - run on docker host. Deploys the patched .so.
set -euo pipefail
CONT=${SAGETV_CONTAINER:-sagetv}

echo "=== copy .so out of build dir, deploy to /opt/sagetv/server ==="
docker exec --user root "$CONT" bash -c '
set -e
cd /opt/sagetv/server
if [ ! -f libHDHomeRunCapture.so.preflex4k ]; then
    cp -p libHDHomeRunCapture.so libHDHomeRunCapture.so.preflex4k
fi
cp /tmp/hdhrbuild/native/so/HDHomeRun2.0/libHDHomeRunCapture.so libHDHomeRunCapture.so.new
ls -la libHDHomeRunCapture.so libHDHomeRunCapture.so.new libHDHomeRunCapture.so.preflex4k
echo "--- md5 ---"
md5sum libHDHomeRunCapture.so libHDHomeRunCapture.so.new libHDHomeRunCapture.so.preflex4k
echo "--- swap in (atomic mv) ---"
chown sagetv:sagetv libHDHomeRunCapture.so.new || true
mv libHDHomeRunCapture.so.new libHDHomeRunCapture.so
ls -la libHDHomeRunCapture.so

echo "--- enable HDHOMERUN_VIDEO_DEBUG in startsage if not already ---"
if ! grep -q HDHOMERUN_VIDEO_DEBUG startsage; then
    # Insert after the shebang line.
    sed -i "2i export HDHOMERUN_VIDEO_DEBUG=1" startsage
fi
head -5 startsage
'

echo ""
echo "=== stopsage / startsage (in-container, NO docker stop/start) ==="
docker exec --user sagetv "$CONT" bash -c '
echo "--- stopsage ---"
/opt/sagetv/server/stopsage
sleep 4
echo "--- old sage processes? ---"
ps -ef | grep -i java | grep -v grep | head
echo "--- startsage ---"
/opt/sagetv/server/startsage
sleep 6
echo "--- new sage processes ---"
ps -ef | grep -i java | grep -v grep | head
echo "--- HDHOMERUN_VIDEO_DEBUG in env ---"
PID=$(pgrep -f "java.*Sage" | head -1)
if [ -n "$PID" ]; then
    tr "\0" "\n" < /proc/$PID/environ | grep -E "HDHOMERUN|PATH" || echo "(none found)"
fi
echo "--- recent log tail ---"
tail -30 /opt/sagetv/server/sagetv_0.txt
'

echo ""
echo "=== DONE — now trigger a channel scan on Tuner 3 from the SageTV UI ==="
echo "Then check for diagnostic packet sizes:"
echo "  docker exec ${CONT} grep HDHR_VIDEO /opt/sagetv/server/sagetv_0.txt | head -50"
echo "  docker exec ${CONT} grep HDHR_VIDEO /var/log/syslog 2>/dev/null | head -50"
