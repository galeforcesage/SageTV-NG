#!/bin/bash
# Build a SECOND, independent FFmpeg binary with AC-4 audio support.
#
# Source: elliotclee/FFmpeg fork (Linux mirror of pliu6's FFmpeg-AC-4 patches,
# which add a pure-C AC-4 decoder by Paul B Mahol). This is the same source
# MCEBuddy and Channels DVR use under the hood; nothing in stock FFmpeg
# decodes Dolby AC-4 because the Dolby reference decoder is closed-source.
#
# The resulting binary is installed at /usr/local/bin/ffmpeg-ac4 and is kept
# STRICTLY SEPARATE from:
#   - /opt/sagetv/server/ffmpeg  (SageTV-patched 6.1.1: -stdinctrl, etc.)
#   - /usr/bin/ffmpeg            (stock Ubuntu 6.1.1)
#
# Only new code paths that explicitly need AC-4 (HDHomeRun FLEX 4K ATSC 3.0
# transcoding) invoke ffmpeg-ac4 by absolute path. SageTV's existing
# transcoder is NOT redirected.
#
# CRITICAL pin (verified on host RTX 2060 + driver 535/595, Ubuntu 24.04):
#   nv-codec-headers tag n12.1.14.0  (Video Codec SDK 12.0.16, driver 530+)
#   DO NOT use master   -> targets SDK 13.0, requires driver 570+
#                          NVENC init fails with -22 Invalid argument
#                          on older drivers. Re-pin only after host driver
#                          is upgraded past 570.
#
set -e

FFMPEG_REPO="https://github.com/elliotclee/FFmpeg.git"
FFMPEG_BRANCH="master"
NVCODEC_REPO="https://github.com/FFmpeg/nv-codec-headers.git"
NVCODEC_TAG="n12.1.14.0"

BUILD_DIR="/tmp/ffmpeg-ac4-build"
PREFIX="${BUILD_DIR}/install"
JOBS="$(nproc)"

echo "=== ffmpeg-ac4: preparing build directory ==="
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

echo "=== ffmpeg-ac4: cloning nv-codec-headers ${NVCODEC_TAG} ==="
git clone --depth 1 --branch "$NVCODEC_TAG" "$NVCODEC_REPO" nv-codec-headers
make -C nv-codec-headers PREFIX="$PREFIX" install

echo "=== ffmpeg-ac4: cloning elliotclee/FFmpeg (pliu6 AC-4 fork) ==="
git clone --depth 1 --branch "$FFMPEG_BRANCH" "$FFMPEG_REPO" FFmpeg
cd FFmpeg

echo "=== ffmpeg-ac4: verifying AC-4 decoder is present in fork ==="
if ! grep -q "ff_ac4_decoder" libavcodec/allcodecs.c; then
    echo "ERROR: ff_ac4_decoder not found in fork - wrong branch?" >&2
    exit 1
fi
if [ ! -f libavcodec/ac4dec.c ]; then
    echo "ERROR: libavcodec/ac4dec.c missing - wrong branch?" >&2
    exit 1
fi

echo "=== ffmpeg-ac4: configure ==="
PKG_CONFIG_PATH="${PREFIX}/lib/pkgconfig" \
./configure \
    --prefix="$PREFIX" \
    --disable-doc \
    --disable-htmlpages \
    --disable-manpages \
    --disable-podpages \
    --disable-txtpages \
    --enable-gpl \
    --enable-nonfree \
    --enable-libx264 \
    --enable-libx265 \
    --enable-libfdk-aac \
    --enable-libfreetype \
    --enable-nvenc \
    --enable-ffnvcodec \
    --extra-cflags="-I${PREFIX}/include" \
    --extra-ldflags="-L${PREFIX}/lib"

echo "=== ffmpeg-ac4: make -j${JOBS} ==="
make -j"$JOBS"

echo "=== ffmpeg-ac4: install to /src/build/elf/ ==="
mkdir -p /src/build/elf
cp ffmpeg  /src/build/elf/ffmpeg-ac4
cp ffprobe /src/build/elf/ffprobe-ac4
strip /src/build/elf/ffmpeg-ac4 /src/build/elf/ffprobe-ac4 2>/dev/null || true

echo "=== ffmpeg-ac4: verifying built binary ==="
/src/build/elf/ffmpeg-ac4 -hide_banner -decoders 2>&1 | grep -q '^ A....D ac4 ' \
    || { echo "ERROR: ac4 decoder missing from built binary" >&2; exit 1; }
/src/build/elf/ffmpeg-ac4 -hide_banner -encoders 2>&1 | grep -q 'h264_nvenc' \
    || { echo "ERROR: h264_nvenc encoder missing from built binary" >&2; exit 1; }

echo "=== ffmpeg-ac4: BUILD OK ==="
ls -la /src/build/elf/ffmpeg-ac4 /src/build/elf/ffprobe-ac4
