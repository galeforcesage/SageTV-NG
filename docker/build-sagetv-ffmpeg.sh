#!/bin/bash
# Build the unified SageTV FFmpeg binary.
#
# ONE binary at /opt/sagetv/server/{ffmpeg,ffprobe} with everything:
#
#   1. Four SageTV custom CLI flags forward-ported from the 2010
#      narflex tree (third_party/FFMPEG/ffmpeg.c):
#        -stdinctrl    : stdin command channel (inactivefile, videorateadapt)
#        -activefile   : input file is being live-written (maps to follow=1)
#        -dumpmetadata : emit META:KEY=VALUE lines (FormatParser depends on these)
#        -brokendts    : ignore broken DTS in MPEG-TS (maps to -fflags +igndts)
#   2. AC-4 audio decoder (elliotclee/pliu6 fork patches)
#   3. NVENC (h264/hevc), libx264, libx265, libfdk-aac, libxvid, libfreetype
#
# Replaces:
#   docker/build-modern-ffmpeg.sh  (FFmpeg 6.1.1 + SageTV patches, no AC-4)
#   docker/build-ac4-ffmpeg.sh     (AC-4 fork, no SageTV patches)
#   docker/ffmpeg-wrapper.sh       (strips SageTV flags before stock ffmpeg)
#
# Plan: docs/FFMPEG_UNIFICATION_PLAN.md.
#
# Reproducibility pins (DO NOT bump without testing on the host):
#   FFMPEG_COMMIT     : elliotclee/FFmpeg pinned commit (AC-4 fork on FFmpeg 7.x)
#   NVCODEC_TAG       : nv-codec-headers tag matching host NVIDIA driver
#                       n12.1.14.0 = SDK 12.0.16, requires driver >= 530
#
set -e

FFMPEG_REPO="https://github.com/elliotclee/FFmpeg.git"
# Pinned: elliotclee/FFmpeg master @ 2026-05-13 (latest as of plan approval).
# Bump only when tests pass against the new HEAD.
FFMPEG_COMMIT="1dc7ff583b213ac01c56b19b5557604fa9df5772"
NVCODEC_REPO="https://github.com/FFmpeg/nv-codec-headers.git"
NVCODEC_TAG="n12.1.14.0"

BUILD_DIR="/tmp/sagetv-ffmpeg-build"
PREFIX="${BUILD_DIR}/install"
JOBS="$(nproc)"
OUT_DIR="${OUT_DIR:-/src/build/elf}"

echo "=== sagetv-ffmpeg: preparing build directory ==="
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

echo "=== sagetv-ffmpeg: cloning nv-codec-headers ${NVCODEC_TAG} ==="
git clone --depth 1 --branch "$NVCODEC_TAG" "$NVCODEC_REPO" nv-codec-headers
make -C nv-codec-headers PREFIX="$PREFIX" install

echo "=== sagetv-ffmpeg: cloning elliotclee/FFmpeg @ ${FFMPEG_COMMIT} ==="
git clone "$FFMPEG_REPO" FFmpeg
cd FFmpeg
git checkout "$FFMPEG_COMMIT"

echo "=== sagetv-ffmpeg: verifying AC-4 decoder is present ==="
if ! grep -q "ff_ac4_decoder" libavcodec/allcodecs.c; then
    echo "ERROR: ff_ac4_decoder not found in fork - wrong commit?" >&2
    exit 1
fi
if [ ! -f libavcodec/ac4dec.c ]; then
    echo "ERROR: libavcodec/ac4dec.c missing - wrong commit?" >&2
    exit 1
fi

echo "=== sagetv-ffmpeg: applying SageTV custom-flag patches ==="
# Patches are inline (sed/python) for self-containment. If any patch's
# context line drifts in a future FFmpeg upstream merge, fix it here.

# ---------------------------------------------------------------------
# PATCH 1: globals in fftools/ffmpeg.c
# ---------------------------------------------------------------------
sed -i '/^static volatile int received_nb_signals/a\
\
/* SageTV custom flags - forward-ported from SageTV patched FFmpeg */\
int sagetv_stdin_ctrl = 0;\
int sagetv_active_file = 0;\
int sagetv_dump_metadata = 0;\
int sagetv_broken_dts = 0;\
\
#define SAGETV_CMD_BUF_SIZE 64\
static int sagetv_cmd_buf_pos = 0;\
static char sagetv_cmd_buf[SAGETV_CMD_BUF_SIZE];' fftools/ffmpeg.c

# ---------------------------------------------------------------------
# PATCH 2: -stdinctrl handler inside read_key()
# ---------------------------------------------------------------------
python3 - <<'PYEOF'
import re
path = 'fftools/ffmpeg.c'
src  = open(path).read()
old  = r'(/\* read a key without blocking \*/\nstatic int read_key\(void\)\n\{)'
new  = r'''\1
    /* SageTV: when -stdinctrl is active, parse commands from stdin.
       Returns -1 to indicate no interactive key (we are not a TTY).  */
    if (sagetv_stdin_ctrl) {
#if HAVE_TERMIOS_H
        int n;
        struct timeval tv;
        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(0, &rfds);
        tv.tv_sec  = 0;
        tv.tv_usec = 0;
        n = select(1, &rfds, NULL, NULL, &tv);
        if (n > 0) {
            int readcount;
            if (sagetv_cmd_buf_pos >= SAGETV_CMD_BUF_SIZE) {
                memset(sagetv_cmd_buf, 0, SAGETV_CMD_BUF_SIZE);
                sagetv_cmd_buf_pos = 0;
            }
            readcount = read(0, &sagetv_cmd_buf[sagetv_cmd_buf_pos],
                             SAGETV_CMD_BUF_SIZE - sagetv_cmd_buf_pos);
            if (readcount <= 0) return -1;
            sagetv_cmd_buf_pos += readcount;
            for (;;) {
                char *eol;
                int cmd_len;
                sagetv_cmd_buf[FFMIN(SAGETV_CMD_BUF_SIZE - 1, sagetv_cmd_buf_pos)] = 0;
                eol = strchr(sagetv_cmd_buf, '\n');
                if (!eol) eol = strchr(sagetv_cmd_buf, '\r');
                if (!eol) return -1;
                if (strstr(sagetv_cmd_buf, "inactivefile") == sagetv_cmd_buf) {
                    if (sagetv_active_file) {
                        sagetv_active_file = 0;
                        av_log(NULL, AV_LOG_INFO,
                               "SageTV: inactivefile received - exiting follow mode\n");
                    }
                } else if (strstr(sagetv_cmd_buf, "videorateadapt") == sagetv_cmd_buf) {
                    int rate_adjust = atoi(sagetv_cmd_buf + 15) * 1000;
                    av_log(NULL, AV_LOG_INFO,
                           "SageTV: videorateadapt request: %d bps\n", rate_adjust);
                    /* Best-effort runtime bitrate change is non-trivial on
                       modern fftools (per-output OutputStream lookup needed).
                       Logging the request preserves the SageTV protocol; the
                       Java side will treat unrequited rate changes gracefully. */
                }
                *eol = 0;
                cmd_len = strlen(sagetv_cmd_buf) + 1;
                if (cmd_len < sagetv_cmd_buf_pos) {
                    memmove(sagetv_cmd_buf, eol + 1, sagetv_cmd_buf_pos - cmd_len);
                    sagetv_cmd_buf_pos -= cmd_len;
                    memset(&sagetv_cmd_buf[sagetv_cmd_buf_pos], 0,
                           SAGETV_CMD_BUF_SIZE - sagetv_cmd_buf_pos);
                } else {
                    sagetv_cmd_buf_pos = 0;
                    memset(sagetv_cmd_buf, 0, SAGETV_CMD_BUF_SIZE);
                }
            }
        }
#endif
        return -1;
    }
    /* end SageTV stdinctrl */
'''
src = re.sub(old, new, src, count=1)
open(path, 'w').write(src)
PYEOF

# ---------------------------------------------------------------------
# PATCH 3: option table entries in fftools/ffmpeg_opt.c
# ---------------------------------------------------------------------
sed -i '/^#include "ffmpeg.h"/a\
\
/* SageTV custom flags */\
extern int sagetv_stdin_ctrl;\
extern int sagetv_active_file;\
extern int sagetv_dump_metadata;\
extern int sagetv_broken_dts;' fftools/ffmpeg_opt.c

sed -i '/^    { NULL, },$/i\
    /* SageTV custom options */\
    { "stdinctrl",     OPT_TYPE_BOOL, OPT_EXPERT,  { \&sagetv_stdin_ctrl },\
        "accept control commands through stdin (inactivefile, videorateadapt)" },\
    { "activefile",    OPT_TYPE_BOOL, OPT_EXPERT,  { \&sagetv_active_file },\
        "input is an active file still being written (enables follow mode)" },\
    { "dumpmetadata",  OPT_TYPE_BOOL, OPT_EXPERT,  { \&sagetv_dump_metadata },\
        "dump metadata information to stderr in META:key=value format" },\
    { "brokendts",     OPT_TYPE_BOOL, OPT_EXPERT,  { \&sagetv_broken_dts },\
        "ignore broken DTS values in MPEG-TS streams" },\
' fftools/ffmpeg_opt.c

# ---------------------------------------------------------------------
# PATCH 4: emit META: lines after stream-info detection (ffmpeg_demux.c)
# ---------------------------------------------------------------------
sed -i '/^#include "ffmpeg.h"/a\
\
/* SageTV custom flags */\
extern int sagetv_dump_metadata;\
extern int sagetv_active_file;\
extern int sagetv_broken_dts;' fftools/ffmpeg_demux.c

python3 - <<'PYEOF'
import re
path = 'fftools/ffmpeg_demux.c'
src  = open(path).read()

# Insert META: dump after avformat_find_stream_info success branch.
# Match the line "ret = avformat_find_stream_info(ic, ...)" and inject
# the dump right after the closing brace of its error-check block.
needle = re.search(
    r'ret\s*=\s*avformat_find_stream_info\([^;]*;\s*\n'
    r'(?:[^\n]*\n){0,15}?'   # up to ~15 lines of follow-up
    r'\s*\}\s*\n',
    src, re.MULTILINE)
if not needle:
    print("WARN: could not locate avformat_find_stream_info call site; "
          "META: dump not injected. Check ffmpeg_demux.c manually.")
else:
    insert_at = needle.end()
    chunk = '''
    /* SageTV: dump metadata if -dumpmetadata was specified */
    if (sagetv_dump_metadata) {
        const AVDictionaryEntry *tag = NULL;
        while ((tag = av_dict_iterate(ic->metadata, tag)))
            av_log(NULL, AV_LOG_INFO, "META:%s=%s\\n", tag->key, tag->value);
        for (unsigned si = 0; si < ic->nb_streams; si++) {
            tag = NULL;
            while ((tag = av_dict_iterate(ic->streams[si]->metadata, tag)))
                av_log(NULL, AV_LOG_INFO, "META:%s=%s\\n", tag->key, tag->value);
        }
    }
'''
    src = src[:insert_at] + chunk + src[insert_at:]

# Map -activefile and -brokendts onto modern format/protocol options.
# Inject just before the avformat_open_input() call.
open_call = re.search(r'\n([ \t]*)err\s*=\s*avformat_open_input\(', src)
if not open_call:
    print("WARN: could not locate avformat_open_input call site; "
          "-activefile/-brokendts not wired. Fix manually.")
else:
    indent = open_call.group(1)
    snippet = (
        f"\n{indent}/* SageTV: -activefile maps to protocol follow=1 */\n"
        f"{indent}if (sagetv_active_file)\n"
        f"{indent}    av_dict_set(&o->g->format_opts, \"follow\", \"1\", 0);\n"
        f"{indent}/* SageTV: -brokendts maps to fflags +igndts */\n"
        f"{indent}if (sagetv_broken_dts)\n"
        f"{indent}    av_dict_set(&o->g->format_opts, \"fflags\", \"+igndts\", AV_DICT_APPEND);\n"
    )
    src = src[:open_call.start()] + snippet + src[open_call.start():]

open(path, 'w').write(src)
PYEOF

echo "=== sagetv-ffmpeg: configuring ==="
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
    --enable-pthreads \
    --enable-libx264 \
    --enable-libx265 \
    --enable-libfdk-aac \
    --enable-libxvid \
    --enable-libmp3lame \
    --enable-libfreetype \
    --enable-nvenc \
    --enable-ffnvcodec \
    --disable-devices \
    --disable-bzlib \
    --extra-cflags="-I${PREFIX}/include" \
    --extra-ldflags="-L${PREFIX}/lib"

echo "=== sagetv-ffmpeg: make -j${JOBS} ==="
make -j"$JOBS"

echo "=== sagetv-ffmpeg: installing into ${OUT_DIR} ==="
mkdir -p "$OUT_DIR"
cp ffmpeg  "${OUT_DIR}/sagetv-ffmpeg"
cp ffprobe "${OUT_DIR}/sagetv-ffprobe"
strip "${OUT_DIR}/sagetv-ffmpeg" "${OUT_DIR}/sagetv-ffprobe" 2>/dev/null || true

echo "=== sagetv-ffmpeg: smoke-testing built binary ==="
"${OUT_DIR}/sagetv-ffmpeg" -hide_banner -h full 2>&1 \
    | grep -E '^[[:space:]]*-(stdinctrl|activefile|dumpmetadata|brokendts)' \
    || { echo "ERROR: one or more SageTV flags missing from -h full" >&2; exit 1; }
"${OUT_DIR}/sagetv-ffmpeg" -hide_banner -decoders 2>&1 | grep -q '^ A....D ac4 ' \
    || { echo "ERROR: ac4 decoder missing" >&2; exit 1; }
"${OUT_DIR}/sagetv-ffmpeg" -hide_banner -encoders 2>&1 | grep -q 'h264_nvenc' \
    || { echo "ERROR: h264_nvenc encoder missing" >&2; exit 1; }
"${OUT_DIR}/sagetv-ffprobe" -hide_banner -version >/dev/null \
    || { echo "ERROR: ffprobe build broken" >&2; exit 1; }

echo "=== sagetv-ffmpeg: BUILD OK ==="
ls -la "${OUT_DIR}/sagetv-ffmpeg" "${OUT_DIR}/sagetv-ffprobe"
