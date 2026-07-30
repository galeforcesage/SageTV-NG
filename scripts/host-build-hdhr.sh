#!/bin/bash
# host-build-hdhr.sh — runs on a Docker host and stages/builds HDHR sources.
# Stages source into the container, builds, deploys.
set -euo pipefail
CONT=${SAGETV_CONTAINER:-sagetv}
CONTAINER_USER=${SAGETV_CONTAINER_USER:-sagetv}

echo "=== check tarball ==="
ls -la /tmp/hdhrbuild.tar.gz /tmp/build-hdhr-lib.sh

echo "=== copy into container ==="
docker cp /tmp/hdhrbuild.tar.gz "${CONT}:/tmp/hdhrbuild.tar.gz"
docker cp /tmp/build-hdhr-lib.sh "${CONT}:/tmp/build-hdhr-lib.sh"

echo "=== extract ==="
docker exec --user "$CONTAINER_USER" "$CONT" bash -c '
set -e
rm -rf /tmp/hdhrbuild
mkdir /tmp/hdhrbuild
tar -xzf /tmp/hdhrbuild.tar.gz -C /tmp/hdhrbuild
chmod +x /tmp/build-hdhr-lib.sh
echo "--- contents ---"
ls /tmp/hdhrbuild
ls /tmp/hdhrbuild/native
ls /tmp/hdhrbuild/native/so/HDHomeRun2.0 | head
echo "--- patch markers ---"
grep -c VIDEO_RTP_MAX_PACKET_SIZE /tmp/hdhrbuild/third_party/SiliconDust/libhdhomerun/hdhomerun_video.h
grep -c hdhomerun_video_parse_packet_header /tmp/hdhrbuild/third_party/SiliconDust/libhdhomerun/hdhomerun_video.c
'

echo "=== check supporting source dirs (NativeCore / Channel-2) ==="
docker exec --user "$CONTAINER_USER" "$CONT" bash -c '
ls /tmp/hdhrbuild/native/lib/NativeCore/ 2>/dev/null | head || echo "(NativeCore source MISSING from tar)"
ls /tmp/hdhrbuild/native/ax/Channel-2/ 2>/dev/null | head || echo "(Channel-2 source MISSING from tar)"
'

echo "=== run build ==="
docker exec --user "$CONTAINER_USER" "$CONT" bash /tmp/build-hdhr-lib.sh
