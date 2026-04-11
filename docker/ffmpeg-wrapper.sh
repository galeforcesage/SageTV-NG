#!/bin/bash
# FFmpeg compatibility wrapper for SageTV
#
# SageTV's FFMPEGTranscoder.java and FormatParser.java pass custom flags
# that only exist in SageTV's patched FFmpeg build. When using stock FFmpeg
# (e.g., BtbN n7.1.3), these cause the transcoder to fail silently.
#
# This wrapper strips the incompatible flags before forwarding to the
# real FFmpeg binary.
#
# Flags filtered:
#   -dumpmetadata   SageTV-custom: format detection fails without it
#   -stdinctrl      SageTV-custom: transcoder exits immediately
#   -activefile     SageTV-custom: transcoder exits
#   -brokendts      SageTV-custom: transcoder exits
#   -deinterlace    Removed in FFmpeg 4.x+: transcoder exits
#   -v 2            Numeric verbosity: stock FFmpeg maps "2" = "warning",
#                   suppressing Input #0 line needed for format parsing;
#                   replaced with "info"

args=()
skip_next=false
for arg in "$@"; do
    if $skip_next; then
        skip_next=false
        args+=("info")
        continue
    fi
    case "$arg" in
        -dumpmetadata|-stdinctrl|-activefile|-brokendts|-deinterlace)
            ;;
        -v)
            args+=("$arg")
            skip_next=true
            ;;
        *)
            args+=("$arg")
            ;;
    esac
done

exec /usr/local/bin/ffmpeg.real "${args[@]}"
