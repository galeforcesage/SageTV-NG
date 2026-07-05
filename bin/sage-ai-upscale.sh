#!/bin/bash
# sage-ai-upscale.sh — phase 1 of SageTV's automatic AI upscale chained job.
#
# Reads a source video, AI-upscales the video stream with
# realesrgan-ncnn-vulkan in frame chunks, and writes a single intermediate
# MKV (lossless H.264 video at the requested target WxH + audio copied from
# the source). Phase 2 of the SageTV chained job then re-encodes that
# intermediate with the user's preset (HEVC/AAC/etc), but with -vf scale=
# stripped since the upscale already happened here.
#
# Usage:
#   sage-ai-upscale.sh \
#       --input  /path/to/source.ts \
#       --output /path/to/intermediate.mkv \
#       --width  2560 --height 1440 \
#       [--model realesr-general-x4v3] \
#       [--chunk-frames 500] \
#       [--realesrgan /usr/local/bin/realesrgan-ncnn-vulkan] \
#       [--ffmpeg /usr/bin/ffmpeg] \
#       [--workdir /tmp/sage-ai-upscale.$$]
#
#   sage-ai-upscale.sh --probe [--realesrgan BIN] [--model M] [--ffmpeg BIN]
#       Vulkan availability check: upscales a trivial test frame and exits 0
#       only if a Vulkan device initializes and produces output; exits 7
#       otherwise. SageTV uses this to decide whether to engage the AI-upscale
#       chained job or fall back to a plain (Lanczos) transcode. In probe mode
#       --input/--output/--width/--height are ignored.
#
# Emits ffmpeg-style progress lines on stderr ("frame=NNN ... speed=...") so
# SageTV's stderr scraper can show progress for the upscale phase too.
#
# Exit codes:
#   0   success
#   2   bad args
#   3   ffprobe failed on source
#   4   no upscaler binary
#   5   chunk extraction / upscale / encode failed
#   6   final concat / mux failed
#   7   --probe: no usable Vulkan device

set -u
set -o pipefail

INPUT=""
OUTPUT=""
WIDTH=0
HEIGHT=0
MODEL="${SAGE_AI_UPSCALE_MODEL:-realesr-general-x4v3}"
CHUNK_FRAMES="${SAGE_AI_UPSCALE_CHUNK_FRAMES:-500}"
# Default to the bind-mounted realesrgan location used by our deploy
# (run_mine.sh mounts the host realesrgan dir at /opt/realesrgan:ro). SageTV
# normally passes the path explicitly via transcoder/ai_upscale_binary, but a
# sane default keeps `--probe` and manual invocation working out of the box.
REALESRGAN_BIN="${SAGE_AI_UPSCALE_BINARY:-/opt/realesrgan/realesrgan-ncnn-vulkan}"
FFMPEG_BIN="${SAGE_FFMPEG:-/usr/bin/ffmpeg}"
FFPROBE_BIN="${SAGE_FFPROBE:-/usr/bin/ffprobe}"
WORKDIR=""
PROBE=0

while [ $# -gt 0 ]; do
    case "$1" in
        --input)        INPUT="$2"; shift 2;;
        --output)       OUTPUT="$2"; shift 2;;
        --width)        WIDTH="$2"; shift 2;;
        --height)       HEIGHT="$2"; shift 2;;
        --model)        MODEL="$2"; shift 2;;
        --chunk-frames) CHUNK_FRAMES="$2"; shift 2;;
        --realesrgan)   REALESRGAN_BIN="$2"; shift 2;;
        --ffmpeg)       FFMPEG_BIN="$2"; shift 2;;
        --ffprobe)      FFPROBE_BIN="$2"; shift 2;;
        --workdir)      WORKDIR="$2"; shift 2;;
        --probe)        PROBE=1; shift;;
        *) echo "sage-ai-upscale: unknown arg '$1'" >&2; exit 2;;
    esac
done

# ── Vulkan probe mode ───────────────────────────────────────────────
# Exits 0 iff realesrgan-ncnn-vulkan can initialize a Vulkan device and
# upscale a trivial test frame; exits 7 otherwise. Consulted by SageTV to
# decide whether to engage the AI-upscale chained job. Only the upscaler
# binary / model (and optionally ffmpeg for test-frame generation) matter here.
if [ "$PROBE" -eq 1 ]; then
    if [ ! -x "$REALESRGAN_BIN" ]; then
        echo "sage-ai-upscale: [probe] upscaler binary not executable: $REALESRGAN_BIN" >&2
        exit 7
    fi
    if [ ! -x "$FFMPEG_BIN" ]; then
        FFMPEG_BIN="$(command -v ffmpeg || true)"
    fi
    PROBE_DIR="$(mktemp -d -t sage-ai-probe.XXXXXX)"
    trap 'rm -rf "$PROBE_DIR"' EXIT
    PROBE_IN="$PROBE_DIR/in"
    PROBE_OUT="$PROBE_DIR/out"
    mkdir -p "$PROBE_IN" "$PROBE_OUT"
    # Prefer ffmpeg to synthesize a 16x16 test frame; fall back to a hand
    # written 1x1 PNG so the probe still exercises Vulkan without ffmpeg.
    if [ -n "$FFMPEG_BIN" ] && [ -x "$FFMPEG_BIN" ]; then
        "$FFMPEG_BIN" -hide_banner -loglevel error -y -f lavfi \
            -i color=c=black:s=16x16 -frames:v 1 "$PROBE_IN/f_00000001.png" \
            >/dev/null 2>&1 || true
    fi
    if [ ! -s "$PROBE_IN/f_00000001.png" ]; then
        printf '%s' \
'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M8AAAMBAQDJ/pLvAAAAAElFTkSuQmCC' \
            | base64 -d > "$PROBE_IN/f_00000001.png" 2>/dev/null || true
    fi
    if [ ! -s "$PROBE_IN/f_00000001.png" ]; then
        echo "sage-ai-upscale: [probe] could not create a test frame" >&2
        exit 7
    fi
    if "$REALESRGAN_BIN" -i "$PROBE_IN" -o "$PROBE_OUT" -n "$MODEL" -s 4 -f png \
            >"$PROBE_DIR/probe.err" 2>&1 \
            && ls "$PROBE_OUT"/*.png >/dev/null 2>&1; then
        echo "sage-ai-upscale: [probe] Vulkan device OK ($REALESRGAN_BIN)" >&2
        exit 0
    fi
    echo "sage-ai-upscale: [probe] no usable Vulkan device for $REALESRGAN_BIN" >&2
    tail -3 "$PROBE_DIR/probe.err" >&2 2>/dev/null || true
    exit 7
fi

if [ -z "$INPUT" ] || [ -z "$OUTPUT" ] || [ "$WIDTH" -le 0 ] || [ "$HEIGHT" -le 0 ]; then
    echo "sage-ai-upscale: --input, --output, --width, --height are required" >&2
    exit 2
fi
if [ ! -f "$INPUT" ]; then
    echo "sage-ai-upscale: input not found: $INPUT" >&2
    exit 2
fi
if [ ! -x "$REALESRGAN_BIN" ]; then
    echo "sage-ai-upscale: realesrgan-ncnn-vulkan binary not executable: $REALESRGAN_BIN" >&2
    exit 4
fi
if [ ! -x "$FFMPEG_BIN" ]; then
    # Fall back to whatever is on PATH
    FFMPEG_BIN="$(command -v ffmpeg || true)"
    if [ -z "$FFMPEG_BIN" ]; then
        echo "sage-ai-upscale: ffmpeg not found on PATH" >&2
        exit 4
    fi
fi
if [ ! -x "$FFPROBE_BIN" ]; then
    FFPROBE_BIN="$(command -v ffprobe || true)"
    if [ -z "$FFPROBE_BIN" ]; then
        echo "sage-ai-upscale: ffprobe not found on PATH" >&2
        exit 4
    fi
fi

if [ -z "$WORKDIR" ]; then
    WORKDIR="$(mktemp -d -t sage-ai-upscale.XXXXXX)"
else
    mkdir -p "$WORKDIR"
fi
trap 'rm -rf "$WORKDIR"' EXIT

echo "sage-ai-upscale: input=$INPUT output=$OUTPUT target=${WIDTH}x${HEIGHT} model=$MODEL chunk=$CHUNK_FRAMES workdir=$WORKDIR" >&2

# ── Probe source ──────────────────────────────────────────────────────────
FPS_RAT=$("$FFPROBE_BIN" -v error -select_streams v:0 \
    -show_entries stream=r_frame_rate -of default=nokey=1:noprint_wrappers=1 \
    "$INPUT") || { echo "sage-ai-upscale: ffprobe failed" >&2; exit 3; }
# Convert "30000/1001" → numeric for the encoder; keep ratio for accuracy.
FPS_NUM="${FPS_RAT%/*}"
FPS_DEN="${FPS_RAT#*/}"
[ -z "$FPS_DEN" ] && FPS_DEN=1
FPS_FLOAT=$(awk "BEGIN { printf \"%.6f\", $FPS_NUM / $FPS_DEN }")

# Use nb_frames if container reports it; otherwise estimate from duration.
TOTAL_FRAMES=$("$FFPROBE_BIN" -v error -select_streams v:0 \
    -show_entries stream=nb_frames -of default=nokey=1:noprint_wrappers=1 \
    "$INPUT" 2>/dev/null || true)
if [ -z "$TOTAL_FRAMES" ] || [ "$TOTAL_FRAMES" = "N/A" ] || [ "$TOTAL_FRAMES" = "0" ]; then
    DURATION=$("$FFPROBE_BIN" -v error -show_entries format=duration \
        -of default=nokey=1:noprint_wrappers=1 "$INPUT" 2>/dev/null || echo 0)
    TOTAL_FRAMES=$(awk "BEGIN { printf \"%d\", $DURATION * $FPS_FLOAT }")
fi
[ "$TOTAL_FRAMES" -le 0 ] && TOTAL_FRAMES=1

echo "sage-ai-upscale: fps=$FPS_RAT (~$FPS_FLOAT) totalFrames~=$TOTAL_FRAMES" >&2

# ── Extract audio once (copy if possible, else encode to AAC for muxability)
AUDIO_FILE="$WORKDIR/audio.mka"
if ! "$FFMPEG_BIN" -hide_banner -loglevel error -y -i "$INPUT" \
        -vn -sn -dn -map 0:a:0? -c:a copy "$AUDIO_FILE" 2>"$WORKDIR/audio_copy.err"; then
    echo "sage-ai-upscale: audio copy failed, transcoding to AAC" >&2
    "$FFMPEG_BIN" -hide_banner -loglevel error -y -i "$INPUT" \
        -vn -sn -dn -map 0:a:0? -c:a aac -b:a 192k -ac 2 "$AUDIO_FILE" \
        || { echo "sage-ai-upscale: audio extraction failed" >&2; exit 5; }
fi
# If source has no audio at all, AUDIO_FILE may be a 0-byte stub; flag it.
HAS_AUDIO=1
if [ ! -s "$AUDIO_FILE" ]; then
    HAS_AUDIO=0
    rm -f "$AUDIO_FILE"
fi

# ── Per-chunk loop ────────────────────────────────────────────────────────
CONCAT="$WORKDIR/concat.txt"
: > "$CONCAT"

CHUNK_IDX=0
PROCESSED=0
while [ "$PROCESSED" -lt "$TOTAL_FRAMES" ]; do
    START_FRAME=$PROCESSED
    END_FRAME=$((PROCESSED + CHUNK_FRAMES - 1))
    IN_DIR="$WORKDIR/in_$CHUNK_IDX"
    OUT_DIR="$WORKDIR/out_$CHUNK_IDX"
    rm -rf "$IN_DIR" "$OUT_DIR"
    mkdir -p "$IN_DIR" "$OUT_DIR"

    # Extract this chunk's frames. -vsync 0 keeps original timing; select=
    # filter picks the right frame range.
    if ! "$FFMPEG_BIN" -hide_banner -loglevel error -y -i "$INPUT" \
            -map 0:v:0 \
            -vf "select=between(n\\,$START_FRAME\\,$END_FRAME),setpts=N/FRAME_RATE/TB" \
            -vsync 0 -f image2 \
            "$IN_DIR/f_%08d.png"; then
        echo "sage-ai-upscale: chunk $CHUNK_IDX extraction failed" >&2
        exit 5
    fi

    NUM_PNG=$(ls -1 "$IN_DIR"/f_*.png 2>/dev/null | wc -l)
    if [ "$NUM_PNG" -eq 0 ]; then
        # Past the end of the video; stop the loop.
        rm -rf "$IN_DIR" "$OUT_DIR"
        break
    fi

    # AI upscale — realesrgan-ncnn-vulkan native scale is 4x.
    if ! "$REALESRGAN_BIN" -i "$IN_DIR" -o "$OUT_DIR" \
            -n "$MODEL" -s 4 -f png 2>"$WORKDIR/realesrgan_$CHUNK_IDX.err"; then
        echo "sage-ai-upscale: realesrgan failed on chunk $CHUNK_IDX (see $WORKDIR/realesrgan_$CHUNK_IDX.err)" >&2
        tail -5 "$WORKDIR/realesrgan_$CHUNK_IDX.err" >&2 || true
        exit 5
    fi

    # Encode the upscaled chunk to lossless H.264 at the target WxH (downscale
    # from 4x with lanczos so we land exactly at user-requested 1440/2160/etc).
    CHUNK_MKV="$WORKDIR/chunk_$(printf "%05d" "$CHUNK_IDX").mkv"
    if ! "$FFMPEG_BIN" -hide_banner -loglevel error -y \
            -framerate "$FPS_RAT" \
            -i "$OUT_DIR/f_%08d.png" \
            -vf "scale=${WIDTH}:${HEIGHT}:flags=lanczos,format=yuv420p" \
            -c:v libx264 -preset ultrafast -qp 0 \
            -an -sn \
            "$CHUNK_MKV"; then
        echo "sage-ai-upscale: chunk $CHUNK_IDX encode failed" >&2
        exit 5
    fi

    echo "file '$CHUNK_MKV'" >> "$CONCAT"
    rm -rf "$IN_DIR" "$OUT_DIR"

    PROCESSED=$((PROCESSED + NUM_PNG))
    CHUNK_IDX=$((CHUNK_IDX + 1))

    # ffmpeg-style progress for Sage's stderr scraper.
    PCT=$((PROCESSED * 100 / TOTAL_FRAMES))
    echo "frame=${PROCESSED} fps=0.0 q=-0.0 size=N/A time=N/A bitrate=N/A speed=N/A progress=${PCT}%" >&2
done

if [ ! -s "$CONCAT" ]; then
    echo "sage-ai-upscale: no chunks produced; aborting" >&2
    exit 5
fi

# ── Concat video chunks ──────────────────────────────────────────────────
VIDEO_ONLY="$WORKDIR/video_only.mkv"
if ! "$FFMPEG_BIN" -hide_banner -loglevel error -y \
        -f concat -safe 0 -i "$CONCAT" -c copy -an "$VIDEO_ONLY"; then
    echo "sage-ai-upscale: chunk concat failed" >&2
    exit 6
fi

# ── Final mux (video + optional audio) ───────────────────────────────────
if [ "$HAS_AUDIO" -eq 1 ]; then
    if ! "$FFMPEG_BIN" -hide_banner -loglevel error -y \
            -i "$VIDEO_ONLY" -i "$AUDIO_FILE" \
            -map 0:v:0 -map 1:a:0 -c copy \
            "$OUTPUT"; then
        echo "sage-ai-upscale: final mux failed" >&2
        exit 6
    fi
else
    if ! "$FFMPEG_BIN" -hide_banner -loglevel error -y \
            -i "$VIDEO_ONLY" -map 0:v:0 -c copy "$OUTPUT"; then
        echo "sage-ai-upscale: final mux (video-only) failed" >&2
        exit 6
    fi
fi

echo "sage-ai-upscale: done — wrote $OUTPUT" >&2
exit 0
