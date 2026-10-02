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
#   3. NVENC (h264/hevc) + NVDEC + libnpp (scale_npp/scale_cuda full-GPU pipeline)
#   4. VAAPI (h264_vaapi/hevc_vaapi + scale_vaapi for AMD/Intel Linux)
#   5. libx264, libx265, libfdk-aac, libxvid, libfreetype (software fallback)
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
                eol = strchr(sagetv_cmd_buf, '\\n');
                if (!eol) eol = strchr(sagetv_cmd_buf, '\\r');
                if (!eol) return -1;
                if (strstr(sagetv_cmd_buf, "inactivefile") == sagetv_cmd_buf) {
                    if (sagetv_active_file) {
                        sagetv_active_file = 0;
                        av_log(NULL, AV_LOG_INFO,
                               "SageTV: inactivefile received - exiting follow mode\\n");
                    }
                } else if (strstr(sagetv_cmd_buf, "videorateadapt") == sagetv_cmd_buf) {
                    int rate_adjust = atoi(sagetv_cmd_buf + 15) * 1000;
                    int fi, si, applied = 0;
                    av_log(NULL, AV_LOG_INFO,
                           "SageTV: videorateadapt request: %d bps\\n", rate_adjust);
                    /* Apply the delta live to every video output encoder. nvenc.c
                       reconfig_encoder() re-reads enc_ctx->bit_rate each frame and
                       calls nvEncReconfigureEncoder (forceIDR, no SPS/dimension
                       change) so the CMAF/MSE init.mp4 stays valid. libx264 self-
                       reconfigures the same way. Cross-thread int writes to the
                       encoder ctx are aligned and benign (matches the legacy
                       SageTV FFmpeg fork's videorateadapt behaviour). */
                    for (fi = 0; fi < nb_output_files; fi++) {
                        OutputFile *of = output_files[fi];
                        if (!of) continue;
                        for (si = 0; si < of->nb_streams; si++) {
                            OutputStream *ost = of->streams[si];
                            AVCodecContext *e;
                            int64_t nb;
                            if (!ost || !ost->enc) continue;
                            e = ost->enc->enc_ctx;
                            if (!e || e->codec_type != AVMEDIA_TYPE_VIDEO) continue;
                            nb = (int64_t)e->bit_rate + rate_adjust;
                            if (nb < 100000) nb = 100000;
                            e->bit_rate = nb;
                            e->rc_max_rate = nb;
                            applied++;
                            av_log(NULL, AV_LOG_INFO,
                                   "SageTV: videorateadapt applied out %d:%d -> %lld bps\\n",
                                   fi, si, (long long)e->bit_rate);
                        }
                    }
                    if (!applied)
                        av_log(NULL, AV_LOG_WARNING,
                               "SageTV: videorateadapt: no video output stream found\\n");
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

# ---------------------------------------------------------------------
# PATCH 5: Fix scale_npp configure dep resolution
# ---------------------------------------------------------------------
# The elliotclee fork lists individual NPP sub-libraries (libnppig,
# libnppicc, etc.) as separate filter dependencies, but check_lib only
# enables the umbrella "libnpp" feature. Replace the individual lib deps
# with just "libnpp" so the dependency check passes when libnpp is found.
echo "=== sagetv-ffmpeg: patching scale_npp filter deps ==="
sed -i 's/scale_npp_filter_deps="ffnvcodec libnppig libnppicc libnppicc libnppc libnppidei libnppif"/scale_npp_filter_deps="ffnvcodec libnpp"/' configure
sed -i 's/scale2ref_npp_filter_deps="ffnvcodec libnppig libnppicc libnppicc libnppc libnppidei libnppif"/scale2ref_npp_filter_deps="ffnvcodec libnpp"/' configure
sed -i 's/sharpen_npp_filter_deps="ffnvcodec libnppig libnppicc libnppicc libnppc libnppidei libnppif"/sharpen_npp_filter_deps="ffnvcodec libnpp"/' configure

# ---------------------------------------------------------------------
# PATCH 8: AC-4 decoder frame-loss fixes (libavcodec/ac4dec.c)
# ---------------------------------------------------------------------
# Numbered 8 to stay aligned with the deployment build recipe, which also
# carries PATCH 6 (stv:// protocol) and PATCH 7 (MOV active_file). Those two
# are not yet mirrored into this script.
#
# The elliotclee AC-4 fork discards whole 32ms frames on two conditions that do
# not actually invalidate the PCM it has already decoded. On ATSC 3.0 5.1
# content this cost ~2s of real dialogue per 29min episode and surfaced as A/V
# desync in the browser MSE client.
#
# 8a: "substream audio data overread" was fatal. audio_data() has already
#     decoded the samples before this post-hoc byte-accounting check runs, so
#     returning AVERROR_INVALIDDATA throws away good audio over a 1-9 byte
#     bookkeeping mismatch. librempeg downgraded this to a warning upstream;
#     match that. (~52 frames per episode)
#
# 8b: "invalid aspx num env" was fatal. A-SPX is the high-frequency extension
#     and is parsed AFTER the core spectral data (five_channel_data() and
#     friends), so the core waveform is already decoded when this fires. Keep
#     the frame and rebuild the high band from the previous frame's A-SPX
#     envelope (standard SBR-style concealment). (~8 frames per episode)
#
# Verified on a 1758s ATSC 3.0 AC-4 5.1 capture: decoded audio went from
# 1684.128s -> 1686.048s against a 1686.112s clean stereo control, and that
# stereo control's PCM stayed bit-identical (no collateral change). The 2
# residual frames are end-of-recording truncation and are not recoverable.
echo "=== sagetv-ffmpeg: AC-4 frame-loss fixes ==="

python3 << 'PATCH8EOF'
import sys

F = 'libavcodec/ac4dec.c'
src = open(F, encoding='utf-8', errors='surrogateescape').read()

def sub_once(old, new, label):
    global src
    if new in src:
        print('  skip %s (already applied)' % label); return
    n = src.count(old)
    if n != 1:
        print('  FAIL %s: found %d occurrences' % (label, n)); sys.exit(1)
    src = src.replace(old, new, 1)
    print('  ok   %s' % label)

# --- 8a: overread is a warning, not a dropped frame -------------------------
sub_once(r'''    if (consumed > audio_size) {
        av_log(s->avctx, AV_LOG_ERROR, "substream audio data overread: %d\n", consumed - audio_size);
        return AVERROR_INVALIDDATA;
    }''',
r'''    if (consumed > audio_size) {
        /* SageTV/upstream-align: librempeg downgraded this post-hoc byte-accounting
           check from fatal to a warning. audio_data() has already decoded the
           samples; discarding the frame loses ~33ms of real audio. */
        av_log(s->avctx, AV_LOG_WARNING, "substream audio data overread: %d\n", consumed - audio_size);
    }''', '8a overread -> warning')

# --- 8b: conceal corrupt A-SPX framing instead of dropping the frame --------
sub_once(r'''    PresentationInfo   pinfo[8];
    SubstreamGroupInfo ssgroup[8];
    Substream          substream;
} AC4DecodeContext;''',
r'''    PresentationInfo   pinfo[8];
    SubstreamGroupInfo ssgroup[8];
    Substream          substream;

    /* SageTV: set when A-SPX framing for the current frame is corrupt. The core
       waveform is fully decoded before A-SPX is parsed, so the frame is kept and
       the high band is concealed from the previous frame's envelope rather than
       discarding ~33ms of real audio. */
    int                aspx_conceal;
} AC4DecodeContext;''', '8b ctx field')

sub_once(r'''        if (ssch->aspx_num_env > 4) {
            av_log(s->avctx, AV_LOG_ERROR, "invalid aspx num env in FIXFIX: %d\n", ssch->aspx_num_env);
            return AVERROR_INVALIDDATA;
        }''',
r'''        if (ssch->aspx_num_env > 4) {
            av_log(s->avctx, AV_LOG_WARNING, "invalid aspx num env in FIXFIX: %d (concealing)\n", ssch->aspx_num_env);
            ssch->aspx_num_env = ssch->aspx_num_env_prev;
            s->aspx_conceal = 1;
            return AVERROR_INVALIDDATA;
        }''', '8b FIXFIX conceal')

sub_once(r'''        if (ssch->aspx_num_env > 5) {
            av_log(s->avctx, AV_LOG_ERROR, "invalid aspx num env: %d (class %d)\n", ssch->aspx_num_env, ssch->aspx_int_class);
            return AVERROR_INVALIDDATA;
        }''',
r'''        if (ssch->aspx_num_env > 5) {
            av_log(s->avctx, AV_LOG_WARNING, "invalid aspx num env: %d (class %d) (concealing)\n", ssch->aspx_num_env, ssch->aspx_int_class);
            ssch->aspx_num_env = ssch->aspx_num_env_prev;
            s->aspx_conceal = 1;
            return AVERROR_INVALIDDATA;
        }''', '8b VARVAR conceal')

sub_once(r'''    ret = audio_data(s, ssinfo->channel_mode, ssinfo->iframe[0]);
    if (ret < 0)
        return ret;''',
r'''    ret = audio_data(s, ssinfo->channel_mode, ssinfo->iframe[0]);
    if (ret < 0) {
        int target, cur;

        if (!s->aspx_conceal)
            return ret;
        /* SageTV: A-SPX framing was corrupt, but the core waveform for this frame
           was already decoded (five_channel_data() and friends run before
           aspx_data_*). Keep the frame; the high band is rebuilt from the
           previous frame's A-SPX envelope, which is still held in the channel
           state. The bitstream position is unreliable from here, so realign to
           the declared end of the audio block rather than running the
           byte-accounting checks over garbage. */
        target = (offset + audio_size) * 8;
        cur = get_bits_count(gb);
        if (target > cur)
            skip_bits_long(gb, target - cur);
        metadata(s, ssinfo, s->iframe_global);
        align_get_bits(gb);
        return 0;
    }''', '8b conceal interception')

sub_once(r'''    if ((ret = init_get_bits8(gb, avpkt->data, avpkt->size)) < 0)
        return ret;
    av_log(s->avctx, AV_LOG_DEBUG, "packet_size: %d\n", avpkt->size);''',
r'''    if ((ret = init_get_bits8(gb, avpkt->data, avpkt->size)) < 0)
        return ret;
    s->aspx_conceal = 0;
    av_log(s->avctx, AV_LOG_DEBUG, "packet_size: %d\n", avpkt->size);''', '8b per-frame reset')

open(F, 'w', encoding='utf-8', errors='surrogateescape').write(src)
PATCH8EOF
if [ $? -ne 0 ]; then echo "ERROR: PATCH 8 (ac4dec.c) failed to apply" >&2; exit 1; fi

# ---------------------------------------------------------------------
# PATCH 9: AC-4 resampling mode (libavcodec/ac4dec.c)
# ---------------------------------------------------------------------
# AC-4 frame-rate-locked streams (ATSC 3.0) signal a resampling ratio so the
# fixed-size MDCT frame maps to the correct number of 48kHz output samples. For
# a 29.97fps-locked stream frame_rate_index=3 selects frame_len_base=1536 with
# resampling_ratio 25025/24000, i.e. each 1536-sample frame actually represents
# 1536*25025/24000 = 1601.6 samples of real time.
#
# The fork computes s->resampling_ratio (ac4_toc) but NEVER applies it -- the
# apply step at the end of ac4_decode_frame is commented out -- so it emits the
# raw 1536 samples labelled 48000Hz. Every frame is then 4.27% too short; over a
# programme the decoded audio runs ~4% shorter than the video, and the
# downstream aresample=async drift corrector pads the growing deficit with
# ~102ms of silence every ~4.6s -- audible as regular sub-second audio breaks on
# ATSC 3.0 (AC-4) playback. A hardware AC-4 decoder (e.g. nVidia Shield) applies
# the resampling and plays clean, which is why only the server transcode path
# exhibits the breaks.
#
# Fix: report the effective (lower) sample rate for resampling-mode frames so the
# already-decoded samples carry their true duration. libavcodec may not depend on
# libswresample (layering), so we do not resample in-decoder; instead the 48kHz
# resample SageTV already performs before the audio encoder (-ar 48000 + the
# aresample filter in FFMPEGTranscoder.buildAudioResampleFilter) restores the
# nominal 48kHz grid exactly where a valid encoder rate is required. The E-AC-3
# encoder asserts a standard sample rate, so this effective rate must never reach
# an encoder un-resampled; every SageTV AC-4 re-encode path resamples to 48000.
#
# Verified on an ATSC 3.0 AC-4 5.1 capture (frame_rate_index 3): raw decode of a
# 180s slice went 172.4s -> 179.7s (full real-time length), and the production
# aformat=stereo,aresample=async=1000,-ar 48000 chain went from ~38 injected
# ~102ms silence gaps to 0.
echo "=== sagetv-ffmpeg: AC-4 resampling-mode fix ==="

python3 << 'PATCH9EOF'
import sys

F = 'libavcodec/ac4dec.c'
src = open(F, encoding='utf-8', errors='surrogateescape').read()

def sub_once(old, new, label):
    global src
    if new in src:
        print('  skip %s (already applied)' % label); return
    n = src.count(old)
    if n != 1:
        print('  FAIL %s: found %d occurrences' % (label, n)); sys.exit(1)
    src = src.replace(old, new, 1)
    print('  ok   %s' % label)

sub_once(r'''#include "libavutil/opt.h"''',
r'''#include "libavutil/opt.h"
#include "libavutil/mathematics.h"''', '9 include mathematics.h')

sub_once(r'''    frame->nb_samples = s->frame_len_base;''',
r'''    /* SageTV PATCH 9: apply AC-4 resampling mode. For frame-rate-locked streams
       (e.g. ATSC 3.0 29.97fps: frame_rate_index 3 -> frame_len_base 1536,
       resampling_ratio 25025/24000) each decoded frame of frame_len_base PCM
       samples represents frame_len_base*resampling_ratio samples of real time.
       The fork set s->resampling_ratio but never applied it, so output ran
       1/ratio short (1536 vs 1601.6 @ 29.97fps = 4.27% too few samples/frame);
       the downstream async resampler then padded the cumulative deficit with
       periodic silence, heard as sub-second audio breaks. Keep the decoded
       samples but report the effective (lower) sample rate so each frame carries
       its true duration; the 48kHz resample SageTV already performs before the
       encoder restores the nominal grid. */
    if (s->resampling_ratio.num != s->resampling_ratio.den)
        avctx->sample_rate = av_rescale_rnd(avctx->sample_rate,
                                            s->resampling_ratio.den,
                                            s->resampling_ratio.num,
                                            AV_ROUND_NEAR_INF);
    frame->nb_samples = s->frame_len_base;''', '9 apply resampling_ratio as effective rate')

open(F, 'w', encoding='utf-8', errors='surrogateescape').write(src)
PATCH9EOF
if [ $? -ne 0 ]; then echo "ERROR: PATCH 9 (ac4dec.c) failed to apply" >&2; exit 1; fi

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
    --enable-cuda-nvcc \
    --enable-libnpp \
    --enable-vaapi \
    --disable-devices \
    `# lavfi is the one input "device" SageTV needs: the caption extraction` \
    `# fallback feeds ffmpeg a "movie=...[out0+subcc]" filter graph, which is` \
    `# only reachable through -f lavfi. Everything else (v4l2, alsa, x11grab)` \
    `# stays off. Without this the core binary cannot extract 608/708 captions` \
    `# and the job has to shell out to a distribution ffmpeg instead.` \
    --enable-indev=lavfi \
    --disable-bzlib \
    --extra-cflags="-I${PREFIX}/include -I/usr/local/cuda/include -I/usr/include" \
    --extra-ldflags="-L${PREFIX}/lib -L/usr/local/cuda/lib64 -L/usr/lib/x86_64-linux-gnu" \
    --extra-libs="-lnppig -lnppicc -lnppidei -lnppif -lnppc"

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
"${OUT_DIR}/sagetv-ffmpeg" -hide_banner -filters 2>&1 | grep -q 'scale_npp' \
    || echo "WARN: scale_npp filter not available (libnpp may be missing at runtime)"
"${OUT_DIR}/sagetv-ffmpeg" -hide_banner -filters 2>&1 | grep -q 'scale_vaapi' \
    || echo "WARN: scale_vaapi filter not available (VAAPI libs may be missing at runtime)"
"${OUT_DIR}/sagetv-ffmpeg" -hide_banner -encoders 2>&1 | grep -q 'h264_vaapi' \
    || echo "WARN: h264_vaapi encoder not available (VAAPI libs may be missing at runtime)"
"${OUT_DIR}/sagetv-ffprobe" -hide_banner -version >/dev/null \
    || { echo "ERROR: ffprobe build broken" >&2; exit 1; }

echo "=== sagetv-ffmpeg: BUILD OK ==="
ls -la "${OUT_DIR}/sagetv-ffmpeg" "${OUT_DIR}/sagetv-ffprobe"
