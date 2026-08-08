# SageTV-NG Completed Work

Shipped post-modernization features, moved out of [ROADMAP.md](ROADMAP.md) once
done. Each entry records **what shipped**, **how it was deployed**, and **how it
works** (key files / properties / commits) so the behavior is discoverable
without re-reading the roadmap history.

---

## FFmpeg / encoding

### Unified SageTV-patched, AC-4-capable FFmpeg binary

- **What shipped.** A single patched FFmpeg build replaces the previous soup of
  three build/wrapper scripts.
- **How deployed.** Built into the container image and deployed at
  `/opt/sagetv/server/ffmpeg`. The old `build-modern-ffmpeg.sh`,
  `build-ac4-ffmpeg.sh`, and `ffmpeg-wrapper.sh` were deleted. Build recipe:
  `docker/build-sagetv-ffmpeg.sh`.
- **How it works.** One binary carries all four SageTV custom flags plus AC-4
  decode, NVENC, libx265, and libfdk-aac, so every code path
  (`FFMPEGTranscoder`, `HwEncoder`, `AC4TranscodeJob`, `HTTPLSServer`,
  `Ministry`, `FormatParser`, caption extraction) invokes the same executable
  with consistent capabilities. Design doc:
  [docs/FFMPEG_UNIFICATION_PLAN.md](docs/FFMPEG_UNIFICATION_PLAN.md).

### FFmpeg 6.x → 7.x CLI audit

- **What shipped.** A complete inventory of every direct ffmpeg invocation in
  the server, checked for 7.x CLI compatibility.
- **How it works.** Covered `FFMPEGTranscoder`, `HwEncoder`, `AC4TranscodeJob`,
  `HTTPLSServer`, `MediaFile`, `Ministry`, `FormatParser`, and
  `CaptionExtractionJob`. The only deliberately deferred surface is the legacy
  x264 option soup inside the `MPEG4*-H.264 MKV` preset strings (`-coder`,
  `-flags +loop`, `-partitions`, `-me_method`, `-subq`, `-flags2`, `-wpredp`,
  …), left for the still-open *Offline transcode preset modernization
  (Ministry)* rewrite.

### Live-tune NVENC wiring (HLS live transcode)

- **What shipped.** The live on-the-fly HLS transcode path now uses NVENC when
  available instead of always encoding on the CPU.
- **How it works.** The `httplsMode` branch of `FFMPEGTranscoder` previously
  hardcoded `-vcodec libx264` with a legacy x264 option soup and never
  consulted `HwEncoder`. It now selects the encoder via `HwEncoder.pick("h264")`
  (`FFMPEGTranscoder.java:~1891`): `h264_nvenc` with NVENC-appropriate rate
  control (`-preset`, `-rc:v vbr`, `-g`, `-profile:v high`) when an NVIDIA GPU +
  nvenc-capable ffmpeg are present, otherwise the original `libx264` block
  unchanged so no-GPU hosts keep working. The dead `LiveTranscodeProfile.hwAccel`
  read gap was also identified.
- **Knob.** `multimedia/hwaccel/nvenc/live_preset` (default `p4`).

### MiniPlayer push-mode transcode GPU offload

- **What shipped.** The MiniPlayer push path replaces legacy ~1 Mbps
  `mpeg4`/DVD output with modern H.264.
- **How it works.** The "Modern H.264 MPEG-TS push" path
  (`FFMPEGTranscoder.java:2077+`, `dynamich264` transcode mode) encodes with
  `h264_nvenc` when available (or `libx264` fallback), is bandwidth-adaptive,
  and is wired through `HwEncoder.pick()`. 10-bit sources are downconverted to
  8-bit to satisfy both encoders.

### Audio-only transcode when only the audio codec mismatches

- **What shipped.** When the client can decode the source video but not the
  audio, only the audio is re-encoded — the video is stream-copied.
- **How it works.** The `audioonly` transcode mode (`FFMPEGTranscoder.java:937+`)
  passes video through with `-vcodec copy` and re-encodes audio down a ladder
  (eac3 → ac3 → aac → mp2), e.g. AC-4 → EAC3 with HEVC copied. An optional video
  re-encode integrates `HwEncoder.pick()`.
- **Knobs.** `miniplayer/audioonly_video_codec` (default `h264_nvenc`),
  `miniplayer/audioonly_audio_bitrate`, `miniplayer/audioonly_hwenc_preset`
  (default `p4`), `miniplayer/audioonly_hwenc_params`.

---

## GPU offload / no-GPU degradation

### No-GPU / GPU-absent graceful degradation (fully closed)

All three sub-points complete; hosts with no NVENC now get a working (software
or opt-in) transcode instead of a hard failure.

1. **Per-vendor preset blocks (`6a799164`).** All 10 GPU presets carry an
   `args_nv=`/`global_nv=` block (NVENC params) and an `args_sw=` block
   (libx264/libx265 with `-preset medium`/`slow` + `-crf` instead of NVENC
   `-rc:v vbr`/`-cq:v`). `Ministry.buildPresetSpec()` selects the block via
   `pickVendorBlock()` → `"nv"` for NVENC, `"sw"` otherwise, then emits the
   `f=;MRawCmdlineGlobal=;MRawCmdline=` spec (no `FFMPEGTranscoder` change).
   `stripCudaHwaccel()` removes CUDA flags for the software path. The DVD preset
   is software-only and unchanged. Future vendors add `args_vaapi=`/`args_qsv=`
   and extend `pickVendorBlock()`.
2. **AI-upscale Vulkan device gate.** `Ministry.shouldAutoAiUpscale()` calls
   `aiUpscaleDeviceAvailable()`, a one-per-JVM cached probe that shells out to
   `sage-ai-upscale.sh --probe` (upscales a trivial 16×16 test frame, exit 0 iff
   a Vulkan device initializes). With no Vulkan GPU the rule declines and the
   transcode falls back to the preset's own Lanczos scale instead of failing
   mid-job. Lazy (fires on first qualifying SD→HD job).
   Knobs: `transcoder/ai_upscale_require_vulkan` (default true),
   `transcoder/ai_upscale_probe_timeout_secs` (default 60).
3. **Container GPU reservation is opt-in.** Base
   [docker-compose.yml](docker-compose.yml) stays CPU-only and runs anywhere;
   NVIDIA (NVENC + Vulkan `graphics` cap + Real-ESRGAN bind) layers via the
   opt-in [docker-compose.gpu.yml](docker-compose.gpu.yml):
   `docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d`.
   Startup logs the detected encoder tier once.

---

## Playback

### NG-first decision ordering in `MiniPlayer.load()`

- **What shipped (`819da971`).** The NG (next-gen player) ruling is hoisted to
  the top of `MiniPlayer.load()` so all downstream path decisions inherit it.
- **How it works.** Gates #1–#8 are wired in order, each with an individual
  kill-switch property `miniplayer/ng_override_N` for safe rollback of any
  single gate.

### Comskip end-to-end (code audit)

- **What shipped (`8d94cb23`).** Full code audit confirming the commercial-skip
  pipeline is intact end to end, plus zero-config auto-detection.
- **How it works.** Four stages verified:
  1. `Seeker` → `CommercialDetectionManager.onRecordingStarted/Stopped` →
     `submitJob` → `CommercialDetectionJob.runComskip()` builds the correct
     command.
  2. `EdlWriter.readEdl()` parses standard EDL →
     `SkipMatrix.fromEdlSegments()` builds a binary-search structure.
  3. `VideoFrame.checkCommercialSkip()` monitors position each cycle with
     boundary-aware wake and auto-seeks past commercials.
  4. REW preroll uses `SkipMatrix.getCommercialStart()` to land 15s before a
     break.
- **Auto-detect.** If `commercial_detection/enabled` has never been set,
  `isEnabled()` checks whether the comskip binary exists at the configured path
  — no manual `Sage.properties` edit needed.
- **Remaining (tracked in ROADMAP).** Runtime verification on a real recording
  (confirm `.edl` appears and auto-skip fires during playback).

---

## Infrastructure

### SBBI UPnP → JUPnP migration

- **What shipped.** `PlaceshifterNATManager` fully ported off the abandoned SBBI
  UPnP library to actively-maintained JUPnP.
- **How deployed.** Depends on `org.jupnp:org.jupnp:3.0.3` +
  `org.jupnp.support:3.0.3` (Maven). The vendored
  `third_party/UPnPLib/sbbi-upnplib-1.0.3.jar` was removed and
  `build/copyserverfiles.sh` no longer copies it into `serverrelease/JARs/`.
- **How it works.** All behavior and `Sage.properties` keys are preserved;
  the migration fixes long-standing IPv6 / multi-NIC port-mapping bugs on
  multi-homed servers.

---

## STV optimization

### Phase 1 — Widget deduplication (shipped)

- **What shipped.** A build-time deduplicator that removes redundant widget
  definitions from the STV UI file.
- **How it works.** Implemented in `docs/STV_Cleanup/stv_deduplicator.py` and
  run against `stvs/SageTV7/SageTV7.xml`, removing 6,715 redundant definitions.
  Results: `docs/STV_Cleanup/PHASE1_RESULTS.md`. (Phase 2 build-time layer is
  also done but its runtime layer is still open — see ROADMAP.)
