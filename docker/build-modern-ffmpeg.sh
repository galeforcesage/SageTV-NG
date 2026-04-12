#!/bin/bash
# Build modern FFmpeg 6.1.1 with SageTV custom patches forward-ported.
#
# SageTV's patched FFmpeg (git-0960662, ~2010) had these custom flags:
#   -stdinctrl    : stdin command channel (inactivefile, videorateadapt)
#   -activefile   : input file is still being written (live DVR)
#   -dumpmetadata : dump all metadata as META:key=value to stderr
#   -brokendts    : ignore broken DTS timestamps in MPEG-TS
#
# Forward-port strategy:
#   -stdinctrl    : patched into read_key() in fftools/ffmpeg.c (same approach)
#   -activefile   : uses FFmpeg's native -follow 1 protocol option +
#                   adds -activefile flag that sets follow=1 automatically
#   -dumpmetadata : patched into fftools/ffmpeg_demux.c after stream info
#   -brokendts    : adds flag, maps to -fflags +igndts (native FFmpeg)
#
# Copyright (C) Frey Technologies / SageTV — patches originally by
#   Jeffrey Kardatzke (narflex). Forward-ported for FFmpeg 6.1.1.
#
set -e

FFMPEG_VERSION="6.1.1"
FFMPEG_URL="https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz"
BUILD_DIR="/tmp/ffmpeg-build"

echo "=== Downloading FFmpeg ${FFMPEG_VERSION} ==="
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

if [ ! -f "ffmpeg-${FFMPEG_VERSION}.tar.xz" ]; then
    wget -q "$FFMPEG_URL"
fi
rm -rf "ffmpeg-${FFMPEG_VERSION}"
tar xf "ffmpeg-${FFMPEG_VERSION}.tar.xz"
cd "ffmpeg-${FFMPEG_VERSION}"

echo "=== Applying SageTV patches ==="

# =====================================================================
# PATCH 1: Add -stdinctrl, -activefile, -dumpmetadata, -brokendts flags
# File: fftools/ffmpeg.c
# =====================================================================

# 1a. Add global variables after the existing includes/globals
# Find the line with "static volatile int received_nb_signals" and add after it
sed -i '/^static volatile int received_nb_signals/a\
\
/* SageTV custom flags - forward-ported from SageTV patched FFmpeg */\
int sagetv_stdin_ctrl = 0;\
int sagetv_active_file = 0;\
int sagetv_dump_metadata = 0;\
int sagetv_broken_dts = 0;\
\
/* stdin command buffer for -stdinctrl */\
#define SAGETV_CMD_BUF_SIZE 64\
static int sagetv_cmd_buf_pos = 0;\
static char sagetv_cmd_buf[SAGETV_CMD_BUF_SIZE];' fftools/ffmpeg.c

# 1b. Patch read_key() to handle stdin commands when -stdinctrl is active
# Replace the existing read_key function
cat > /tmp/sagetv_read_key.py << 'PYEOF'
import re, sys

with open(sys.argv[1], 'r') as f:
    content = f.read()

# Find read_key function and replace it
old_read_key = r'(\/\* read a key without blocking \*\/\nstatic int read_key\(void\)\n\{)'
new_read_key = r'''\1
    /* SageTV: when -stdinctrl is active, parse commands from stdin */
    if (sagetv_stdin_ctrl) {
#if HAVE_TERMIOS_H
        int n;
        struct timeval tv;
        fd_set rfds;

        FD_ZERO(&rfds);
        FD_SET(0, &rfds);
        tv.tv_sec = 0;
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
            while (1) {
                char *eol;
                int cmd_len;
                sagetv_cmd_buf[FFMIN(SAGETV_CMD_BUF_SIZE - 1, sagetv_cmd_buf_pos)] = 0;
                eol = strchr(sagetv_cmd_buf, '\\n');
                if (!eol) eol = strchr(sagetv_cmd_buf, '\\r');
                if (!eol) return -1;
                if (strstr(sagetv_cmd_buf, "inactivefile") == sagetv_cmd_buf) {
                    if (sagetv_active_file) {
                        sagetv_active_file = 0;
                        av_log(NULL, AV_LOG_INFO, "SageTV: Inactive file message processed\\n");
                    }
                } else if (strstr(sagetv_cmd_buf, "videorateadapt") == sagetv_cmd_buf) {
                    int rate_adjust = atoi(sagetv_cmd_buf + 15) * 1000;
                    av_log(NULL, AV_LOG_INFO, "SageTV: Video rate adjust %d\\n", rate_adjust);
                    /* Note: in modern FFmpeg, output stream codec params are
                       not easily accessible here. Rate adaptation would need
                       to be implemented via the filtergraph or bitrate filter.
                       For now we log the request. */
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
#elif HAVE_KBHIT && HAVE_PEEKNAMEDPIPE && HAVE_GETSTDHANDLE
        HANDLE mystdin = GetStdHandle(STD_INPUT_HANDLE);
        DWORD retval;
        if (PeekNamedPipe(mystdin, NULL, 0, NULL, &retval, NULL) && retval) {
            if (sagetv_cmd_buf_pos >= SAGETV_CMD_BUF_SIZE) {
                memset(sagetv_cmd_buf, 0, SAGETV_CMD_BUF_SIZE);
                sagetv_cmd_buf_pos = 0;
            }
            if (!ReadFile(mystdin, &sagetv_cmd_buf[sagetv_cmd_buf_pos],
                         SAGETV_CMD_BUF_SIZE - sagetv_cmd_buf_pos, &retval, NULL))
                return -1;
            sagetv_cmd_buf_pos += retval;
            while (1) {
                char *eol;
                int cmd_len;
                sagetv_cmd_buf[FFMIN(SAGETV_CMD_BUF_SIZE - 1, sagetv_cmd_buf_pos)] = 0;
                eol = strchr(sagetv_cmd_buf, '\\n');
                if (!eol) return -1;
                if (strstr(sagetv_cmd_buf, "inactivefile") == sagetv_cmd_buf) {
                    if (sagetv_active_file) {
                        sagetv_active_file = 0;
                        av_log(NULL, AV_LOG_INFO, "SageTV: Inactive file message processed\\n");
                    }
                } else if (strstr(sagetv_cmd_buf, "videorateadapt") == sagetv_cmd_buf) {
                    int rate_adjust = atoi(sagetv_cmd_buf + 15) * 1000;
                    av_log(NULL, AV_LOG_INFO, "SageTV: Video rate adjust %d\\n", rate_adjust);
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

content = re.sub(old_read_key, new_read_key, content, count=1)

with open(sys.argv[1], 'w') as f:
    f.write(content)
PYEOF

python3 /tmp/sagetv_read_key.py fftools/ffmpeg.c

# =====================================================================
# PATCH 2: Add option definitions to fftools/ffmpeg_opt.c
# =====================================================================

# Add extern declarations at the top of ffmpeg_opt.c
sed -i '/^#include "ffmpeg.h"/a\
\
/* SageTV custom flags */\
extern int sagetv_stdin_ctrl;\
extern int sagetv_active_file;\
extern int sagetv_dump_metadata;\
extern int sagetv_broken_dts;' fftools/ffmpeg_opt.c

# Add options before the final { NULL, } terminator
sed -i '/^    { NULL, },$/i\
    /* SageTV custom options - forward-ported from SageTV patched FFmpeg */\
    { "stdinctrl",     OPT_BOOL | OPT_EXPERT,  { \&sagetv_stdin_ctrl },\
        "accept control commands through stdin (inactivefile, videorateadapt)" },\
    { "activefile",    OPT_BOOL | OPT_EXPERT,  { \&sagetv_active_file },\
        "input is an active file still being written (enables follow mode)" },\
    { "dumpmetadata",  OPT_BOOL | OPT_EXPERT,  { \&sagetv_dump_metadata },\
        "dump metadata information to stderr in META:key=value format" },\
    { "brokendts",     OPT_BOOL | OPT_EXPERT,  { \&sagetv_broken_dts },\
        "ignore broken DTS values in MPEG-TS streams" },\
' fftools/ffmpeg_opt.c

# =====================================================================
# PATCH 3: Add -dumpmetadata output after stream info detection
# File: fftools/ffmpeg_demux.c
# =====================================================================

# Add extern declaration
sed -i '/^#include "ffmpeg.h"/a\
extern int sagetv_dump_metadata;' fftools/ffmpeg_demux.c

# After avformat_find_stream_info, add metadata dump
# Find the line after "ret = avformat_find_stream_info" success check
sed -i '/ret = avformat_find_stream_info/,/^[[:space:]]*}/ {
    /if.*ret.*<.*0/,/^[[:space:]]*}/ {
        /^[[:space:]]*}/a\
\
    /* SageTV: dump metadata if -dumpmetadata was specified */\
    if (sagetv_dump_metadata) {\
        const AVDictionaryEntry *tag = NULL;\
        /* Container-level metadata */\
        while ((tag = av_dict_iterate(ic->metadata, tag)))\
            av_log(NULL, AV_LOG_INFO, "META:%s=%s\\n", tag->key, tag->value);\
        /* Per-stream metadata */\
        for (int si = 0; si < (int)ic->nb_streams; si++) {\
            tag = NULL;\
            while ((tag = av_dict_iterate(ic->streams[si]->metadata, tag)))\
                av_log(NULL, AV_LOG_INFO, "META:%s=%s\\n", tag->key, tag->value);\
        }\
    }
    }
}' fftools/ffmpeg_demux.c

# =====================================================================
# PATCH 4: Handle -activefile by setting protocol follow=1 option
# Also handle -brokendts by adding igndts format flag
# File: fftools/ffmpeg_demux.c
# =====================================================================

# Add extern declarations for activefile and brokendts
sed -i '/^extern int sagetv_dump_metadata;/a\
extern int sagetv_active_file;\
extern int sagetv_broken_dts;' fftools/ffmpeg_demux.c

# Before avformat_open_input, add protocol options for follow mode
# and format flags for brokendts
sed -i '/err = avformat_open_input/i\
    /* SageTV: if -activefile, set follow=1 on the file protocol */\
    if (sagetv_active_file) {\
        av_dict_set(\&o->g->format_opts, "follow", "1", 0);\
        av_log(NULL, AV_LOG_INFO, "SageTV: Active file mode enabled (follow=1)\\n");\
    }\
    /* SageTV: if -brokendts, add igndts flag */\
    if (sagetv_broken_dts) {\
        av_dict_set(\&o->g->format_opts, "fflags", "+igndts", AV_DICT_APPEND);\
        av_log(NULL, AV_LOG_INFO, "SageTV: Broken DTS mode enabled (igndts)\\n");\
    }' fftools/ffmpeg_demux.c

echo "=== Patches applied ==="

# =====================================================================
# Configure and build
# =====================================================================
echo "=== Configuring FFmpeg ${FFMPEG_VERSION} ==="

./configure \
    --disable-doc \
    --disable-ffplay \
    --disable-ffprobe \
    --enable-gpl \
    --enable-nonfree \
    --enable-pthreads \
    --enable-libx264 \
    --enable-libx265 \
    --enable-libxvid \
    --enable-libmp3lame \
    --enable-libfdk-aac \
    --disable-devices \
    --disable-bzlib \
    --prefix=/usr/local

echo "=== Building FFmpeg ${FFMPEG_VERSION} ==="
make -j$(nproc)

echo "=== Build complete ==="

# Verify the custom flags are present
echo "=== Verifying SageTV flags ==="
./ffmpeg -h 2>&1 | grep -E 'stdinctrl|activefile|dumpmetadata|brokendts' || echo "WARN: Custom flags not found in help output"
./ffmpeg -version 2>&1 | head -3

echo "=== Done ==="
