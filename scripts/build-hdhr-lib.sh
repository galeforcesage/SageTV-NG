#!/bin/bash
# build-hdhr-lib.sh
# Run INSIDE the SageTV container (see host-build-hdhr.sh's SAGETV_CONTAINER) as user sagetv.
# Builds libHDHomeRunCapture.so from /tmp/hdhrbuild (a tarball of the SageTV
# source tree's third_party/SiliconDust/libhdhomerun + native/ directories).
# Output: /tmp/hdhrbuild/native/so/HDHomeRun2.0/libHDHomeRunCapture.so

set -euo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:${PATH:-}

cd /tmp/hdhrbuild

# Confirm patched constant is in place — fail fast if scp missed it.
grep -q VIDEO_RTP_MAX_PACKET_SIZE third_party/SiliconDust/libhdhomerun/hdhomerun_video.h \
    || { echo "ERROR: hdhomerun_video.h is not patched"; exit 1; }
grep -q hdhomerun_video_parse_packet_header third_party/SiliconDust/libhdhomerun/hdhomerun_video.c \
    || { echo "ERROR: hdhomerun_video.c is not patched"; exit 1; }

cd native/so/HDHomeRun2.0

# JDK_HOME for jni.h. Container has openjdk-21 at /usr/lib/jvm/...
if [ -z "${JDK_HOME:-}" ]; then
    for cand in /usr/lib/jvm/temurin-21-jdk-amd64 /usr/lib/jvm/java-21-openjdk-amd64 /usr/lib/jvm/default-java; do
        if [ -f "$cand/include/jni.h" ]; then
            export JDK_HOME="$cand"
            break
        fi
    done
fi
if [ ! -f "${JDK_HOME:-}/include/jni.h" ]; then
    JDK_HOME=$(find /usr/lib/jvm -maxdepth 3 -name jni.h 2>/dev/null | head -1 | xargs -I{} dirname {} | xargs -I{} dirname {})
    export JDK_HOME
fi
echo "JDK_HOME=$JDK_HOME"
test -f "$JDK_HOME/include/jni.h" || { echo "ERROR: jni.h not found"; exit 1; }

# The Makefile's `dep_make` target builds libNativeCore.so + libchannel.a from
# subprojects. Try that first; if it fails, copy the existing prebuilt copies
# from the deployed tree as a fallback.
echo "=== dep_make ==="
if ! make dep_make 2>&1 | tail -40; then
    echo "WARN: dep_make failed -- using prebuilt libNativeCore.so + libchannel.a"
    cp /opt/sagetv/server/libNativeCore.so .
    # libchannel.a not deployed; attempt to extract from existing .so via objcopy?
    # Cannot. Bail with a clear message.
    if [ ! -f libchannel.a ]; then
        echo "ERROR: libchannel.a not available; cannot link without it."
        exit 2
    fi
fi

# Build the bundled libhdhomerun static lib + wrapper objects + final .so.
echo "=== make libHDHomeRunCapture.so ==="
make libHDHomeRunCapture.so 2>&1 | tail -60

ls -la libHDHomeRunCapture.so
file libHDHomeRunCapture.so
echo "=== md5 ==="
md5sum libHDHomeRunCapture.so
echo "=== nm grep parse_packet_header ==="
nm libHDHomeRunCapture.so 2>/dev/null | grep -E "parse_packet_header|debug_size" || echo "(symbols stripped or static-inlined)"
