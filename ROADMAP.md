# SageTV-mine Roadmap

Living document of post-modernization work, ordered by priority within
each track. Items move to a `## Done` section once shipped.

---

## ATSC 3.0 / EPG track

### Phase 1 — ATSC1 subchannel EPG via idle-tuner PSIP/EIT scan  *(real EPG win)*

**Why this and not "ATSC3 ESG over ROUTE."** SD already covers ATSC3
main channels because 109.x is a virtual remap of the same logical
service as 9.x (and SD has 9.x). The actual gap is ATSC1 **sub**channels
— 9.2 (MeTV), 9.3 (Heroes & Icons), 26.4, 32.5, etc. — which often have
no SD listings. Those carry standard PSIP/EIT in the broadcast already;
nothing exotic, just nobody is reading it.

**Scope.**

- New `Atsc1EITScanner` daemon: when the tuner has been idle for
  > N seconds (property `epg/eit_scan_idle_threshold`, default 120s)
  and at least one channel in the lineup has < 6 hours of EPG ahead,
  tune that channel for ~20–30 s and harvest:
  - PMT (already parsed)
  - **MGT** (PID 0x1FFB, table_id 0xC7) — locates EIT-0..3 PIDs
  - **EIT-0..3** — current + next ~12 hours of programs
  - **ETT-0..3** — long-form descriptions
- Translate to SageTV `Show` + `Airing` rows tagged with
  `dataSource = "OTA"` so SD never overwrites them and a future re-scan
  can deduplicate cleanly.
- Stop scanning when (a) all subchannels covered for at least 6 hours,
  (b) a recording / live-tune request arrives (immediately yield),
  or (c) per-channel scan budget exceeded.
- Persist a "last scanned" timestamp per channel so we round-robin and
  don't repeatedly re-scan the same one.

**Why HDHomeRun makes this easy.**

- Lineup.json gives every subchannel up-front (no service discovery
  guesswork).
- HTTP-pull tune is non-blocking from the host's perspective.
- The same TS bytes go through the existing native MPEG parser — MGT /
  EIT / ETT parsing is in `native/ax/Native2.0/NativeCore/ATSCPSIParser.c`
  (`TSEPGParser.c` already handles EIT structure).

**Single-tuner safety.**

- Strict yield-on-tune: if the Seeker requests a tune for any reason
  (live TV, recording, even a manual channel switch) the scanner must
  release the tuner within ≤ 1 s.
- Per-channel scan budget (~30 s) and global scan budget (~5 min/hour)
  to bound impact on PVR responsiveness.

**Files likely touched.** New `java/sage/epg/ota/Atsc1EITScanner.java`,
plus integration hooks in `java/sage/Seeker.java` (tuner-state callbacks)
and `java/sage/EPG.java` (data merge with priority: USER > SD > OTA >
INFERRED). Native-side `TSEPGParser` may need a lightweight standalone
JNI entry point to parse a captured EIT slice without spinning up the
full TSFilter capture graph.

### Phase 4 — True ATSC3 ESG over ROUTE  *(long-term, optional)*

Only if the FLEX 4K firmware ever exposes the LLS/ESG ROUTE flow
separately, OR we add libatsc3 as an out-of-process helper. Until then,
Phases 0–3 cover real-world need without touching the broadcast stack.

---

## Native parser track

- **Rebuild + deploy `libMPEGParser.so`.** Source already has HEVC +
  AC-4 stream-type patches (commit `6c09aae3`); deployed binary is the
  May 2021 stock build. Rebuild gives ATSC 1-style instant codec ID for
  recorded ATSC3 files (FormatParser uses MPEGParser too). Low risk.
- **Native HEVC SPS parser.** Drop the `ffprobe-ac4` dependency for
  resolution / fps / aspect-ratio. ~300 LOC C in
  `native/ax/Native2.0/NativeCore/ESAnalyzer.c`.
- **Native AC-4 TOC parser.** Same idea for sample rate / channel
  layout / language present in the AC-4 TOC.

## FFmpeg track

- ~~**Unify on a single SageTV-patched, AC-4-capable FFmpeg binary.**~~
  ✅ done — see `Done` section. Unified binary deployed at
  `/opt/sagetv/server/ffmpeg` with all four SageTV custom flags,
  AC-4 decode, NVENC, libx265, libfdk-aac. Old scripts
  (`build-modern-ffmpeg.sh`, `build-ac4-ffmpeg.sh`,
  `ffmpeg-wrapper.sh`) deleted. Design doc:
  [docs/FFMPEG_UNIFICATION_PLAN.md](docs/FFMPEG_UNIFICATION_PLAN.md).
- ~~**6.x → 7.x CLI audit.**~~ ✅ done — see `Done` section. Full
  inventory of every direct ffmpeg invocation in `FFMPEGTranscoder`,
  `HwEncoder`, `AC4TranscodeJob`, `HTTPLSServer`, `MediaFile`,
  `Ministry`, `FormatParser`, `CaptionExtractionJob`. Final
  remaining legacy-x264 option soup inside the `MPEG4*-H.264 MKV`
  preset strings (`-coder`, `-flags +loop`, `-partitions`,
  `-me_method`, `-subq`, `-flags2`, `-wpredp`, etc.) is intentionally
  left for the larger *Offline transcode preset modernization
  (Ministry)* rewrite below.
- **Plugin-installed FFmpeg libraries audit.** Some plugins in
  [OpenSageTV/sagetv-plugin-repo](https://github.com/OpenSageTV/sagetv-plugin-repo)
  ship their own old FFmpeg .so/.jar (Phoenix media utilities, BMT
  thumbnail extraction, etc.). Inventory which plugins do this, what
  versions they bundle, and whether they can share the unified server
  binary or need their own modernization. Not blocking the server-side
  unification.

### Offline transcode preset modernization (`Ministry`)

Replace the legacy 2008-era offline transcode profile catalogue in
[java/sage/Ministry.java](java/sage/Ministry.java) — `Razr-*` (already
dead), `PSP-*`, `iPod-*`, `iPhone-*`, `AppleTV-*`, `MPEG4 HDTV-*`,
`MPEG4-*`, `DVD-*` — with a screen-class-driven catalogue tuned for
the RTX 2060 (Turing NVENC: H.264 + HEVC, no AV1) on the host. **No
live-tune path; offline `Ministry`/`Transcode To...` queue only.**

**New screen-tier presets** (h264_nvenc / hevc_nvenc, NVDEC+NVENC,
MP4 + `+faststart`, AAC stereo by default):

| Preset | Target | Codec | Resolution | VBR cq/bitrate |
|---|---|---|---|---|
| `PHONE_LOW` | small screens / low bandwidth | h264_nvenc | 640x360 | cq 23, 1.2-1.5 Mbps |
| `PHONE_STD` | typical phones | h264_nvenc | 1280x720 | cq 23, 3.5-4.5 Mbps |
| `PHONE_HIGH_1080` | phones on Wi-Fi / local | hevc_nvenc | 1920x1080 | cq 24, 6-7.5 Mbps |
| `TABLET_10_1080` | 10" tablets | hevc_nvenc | 1920x1080 | cq 23, 8-10 Mbps |
| `TABLET_12_1440` | 12-13" tablets (sharp UI/text) | hevc_nvenc | 2560x1440 | cq 23, 12-14 Mbps |
| `TV_1080_COMPAT` | TVs/streamers, broad compat | h264_nvenc | 1920x1080 | cq 21, 10-12 Mbps |
| `TV_4K_HEVC` | 4K TVs / Shield / ATV 4K | hevc_nvenc | 3840x2160 | cq 24, 18-24 Mbps |
| `ARCHIVE_HEVC_MKV` | archival (replaces `MPEG4 HDTV-*` / `MPEG4-*`) | hevc_nvenc | source (cap 2160p) | cq 22, audio copy |
| `DVD_LEGACY_MPEG2` | DVD-Video authoring (NTSC/PAL) | mpeg2video | 720x480 / 720x576 | 8 Mbps, AC3 192k |

**Optional offline upscale** for 1080 masters destined for large
screens (separate preset; produces a new file, never the live path):

- `UPSCALE_1440_FROM_1080` — basic GPU scale (`scale_npp=2560:1440:interp_algo=lanczos`) + hevc_nvenc (cq 23, 12-14 Mbps), audio copy, MKV.
- `UPSCALE_2160_FROM_1080` — basic GPU scale (`scale_npp=3840:2160:interp_algo=lanczos`) + hevc_nvenc (cq 24, 18-24 Mbps), audio copy, MKV.
- AI-upscale variant: external tool runs first (Real-ESRGAN /
  Topaz / etc.) producing 1440p/2160p intermediate; second stage
  encodes with the same hevc_nvenc settings. Kept as a separate
  job by design — the AI step is the slow part.

**Canonical command shape** (PHONE_STD example):

```
ffmpeg -hwaccel cuda -hwaccel_output_format cuda -i "IN" \
  -vf "scale_npp=1280:720:interp_algo=lanczos" \
  -c:v h264_nvenc -preset p5 -rc:v vbr -cq:v 23 \
  -b:v 3500k -maxrate 4500k -bufsize 9000k \
  -c:a aac -b:a 160k -ac 2 \
  -movflags +faststart "OUT.mp4"
```

HEVC-in-MP4 presets should also accept an optional `-tag:v hvc1` for
iOS/Apple-player compatibility.

**Implementation notes.**
- Storage: drop the hardcoded `PREDEFINED_TRANSCODER_FORMATS` table
  and load from `transcoder/presets/*.properties` so users / plugins
  can add their own without recompiling.
- Translate the property-style preset into the existing
  `MCompressionDetails=...;[bf=vid;...][bf=aud;...]` form that
  `FFMPEGTranscoder` already consumes (or, better, plumb a new
  raw-cmdline path so we stop converting `cq`/`hwaccel` back through
  the legacy `bf=`/`f=`/`br=` token grammar).
- STV menu: regenerate the "Transcode To..." menu items from the
  preset catalogue at runtime; remove the dead Razr/PSP/iPod/iPhone
  menu entries.
- Migration: any saved jobs (`transcoder/jobs/*`) referencing
  retired names map to the closest new preset, with a one-time log
  message naming the substitution.
- Hardware probe: detect NVENC availability via
  `HwEncoder.pick("h264")` / `HwEncoder.pick("hevc")` and fall back
  to `libx264` / `libx265` software encoders when the host has no
  NVENC. (RTX 2060 = Turing: H.264 + HEVC; no AV1 encode.)

### Vendor-agnostic preset coverage  *(follow-up to Ministry mod.)*

The modernized catalogue currently ships one preset per format that
assumes an NVIDIA host (`-hwaccel cuda` + `hevc_nvenc`/`h264_nvenc`).
**Option A — explicit `_NV` / `_SW` suffix per format.** Add a parallel
CPU-only variant of each screen-tier preset (`<NAME>_SW`,
`libx264`/`libx265`, no `-hwaccel`) so users on AMD / Intel / no-GPU
hosts get a working path without recompiling ffmpeg. DVD_LEGACY_MPEG2
stays single (no NVENC mpeg2). Adds 10 preset files; `Ministry`
sort-order migration bumps to a v2 fingerprint. No code/UI changes
beyond `PRESET_SORT_ORDER`.

**Option C — true two-axis Format × Quality menu**  *(deferred)*. Split
the single "Transcode To..." dropdown into two: **Format** (what you
get: TV_1080_COMPAT, UPSCALE_1440, etc.) × **Quality** (how it's done:
Nvidia / AMD-VAAPI / Intel-QSV / Agnostic-CPU). Requires real STV menu
work plus a Ministry resolver that builds the cartesian product from
a preset spec like `args_nv=` / `args_sw=` / `args_vaapi=`. Cleaner
mental model than Option A once we have >1 GPU vendor in the mix.

### Non-NVIDIA hardware encoder support — VAAPI / QSV / AMF  *(Option B; recommended next step after sub-point (1))*

**Where this fits.** Sub-point (1) of the *No-GPU graceful degradation*
item (below) only takes the NVENC fast-path **or** drops to CPU
(`libx264`/`libx265`) — see `Ministry.pickPresetEncoder()`, which
deliberately collapses any non-NVENC `HwEncoder.Kind` to `NONE` because
the screen-tier presets are CUDA-shaped and can't be mechanically
rewritten into a VAAPI/QSV filter graph. So an AMD or Intel box today
gets a **working** transcode, but a **software** one — it leaves the
iGPU/dGPU idle. This item closes that gap: give AMD (VAAPI/AMF) and
Intel (QSV/VAAPI) hosts a native hardware path instead of CPU.

`HwEncoder` already probes and ranks `nvenc,vaapi,qsv,amf,videotoolbox,none`
per JVM, so **encoder *selection* is solved** — the missing piece is
authoring vendor-shaped ffmpeg command graphs and wiring `buildPresetSpec`
to emit the one matching the detected backend.

**Why it can't be a token swap.** Unlike NVENC (where `%V264%` →
`h264_nvenc` and CUDA `-hwaccel` strips cleanly), each backend needs a
*structurally different* command:

| Backend | Typical host | Init | Decode hwaccel | Scale filter | Encoders |
|---|---|---|---|---|---|
| **VAAPI** | Intel iGPU / AMD (Linux, `/dev/dri/renderD128`) | `-init_hw_device vaapi=va:/dev/dri/renderD128 -filter_hw_device va` | `-hwaccel vaapi -hwaccel_output_format vaapi` | `scale_vaapi=w:h` (or `hwupload` after CPU scale) | `h264_vaapi`, `hevc_vaapi` |
| **QSV** | Intel (iGPU, modern) | `-init_hw_device qsv=hw -filter_hw_device hw` | `-hwaccel qsv -hwaccel_output_format qsv` | `scale_qsv=w:h` | `h264_qsv`, `hevc_qsv` |
| **AMF** | AMD (Windows; Linux support weak) | n/a (no hwaccel decode) | CPU/DXVA decode | CPU `scale` then encode | `h264_amf`, `hevc_amf` |
| **VideoToolbox** | macOS (not our container) | n/a | `-hwaccel videotoolbox` | CPU `scale` | `h264_videotoolbox`, `hevc_videotoolbox` |

Note the rate-control knobs also differ: NVENC `-rc vbr -cq:v N`;
VAAPI `-rc_mode VBR -qp N` (or `-b:v`/`-maxrate`); QSV `-global_quality N
-look_ahead 1`; AMF `-rc vbr_peak -qp_i/-qp_p`. A single arg string
can't cover them.

**Design — per-vendor arg blocks (aligns with Option C's `args_<vendor>=`).**
Extend each screen-tier `presets/transcoder/*.properties` with optional
keyed blocks; keep `%V264%`/`%V265%` only inside the NV block:

```
name=TV_1080_COMPAT
container=mp4
# NVIDIA (current behaviour; %V264% resolves via HwEncoder)
global_nv=-hwaccel cuda -hwaccel_output_format cuda
args_nv=-i ${INPUT} -map 0:v:0 -map 0:a:0? -vf scale=1920:1080:flags=lanczos \
    -c:v %V264% -preset p5 -rc vbr -cq:v 21 -b:v 10M -maxrate 12M \
    -c:a aac -b:a 192k -ac 2 -movflags +faststart ${OUTPUT}
# VAAPI (Intel/AMD Linux)
global_vaapi=-init_hw_device vaapi=va:${VAAPI_DEVICE} -filter_hw_device va \
    -hwaccel vaapi -hwaccel_output_format vaapi
args_vaapi=-i ${INPUT} -map 0:v:0 -map 0:a:0? \
    -vf scale_vaapi=1920:1080 -c:v h264_vaapi -rc_mode VBR -qp 22 -b:v 10M \
    -c:a aac -b:a 192k -ac 2 -movflags +faststart ${OUTPUT}
# QSV (Intel)
global_qsv=-init_hw_device qsv=hw -filter_hw_device hw \
    -hwaccel qsv -hwaccel_output_format qsv
args_qsv=-i ${INPUT} -map 0:v:0 -map 0:a:0? \
    -vf scale_qsv=1920:1080 -c:v h264_qsv -global_quality 22 -look_ahead 1 \
    -c:a aac -b:a 192k -ac 2 -movflags +faststart ${OUTPUT}
# CPU fallback (Option A _SW, always present as the floor)
args_sw=-i ${INPUT} -map 0:v:0 -map 0:a:0? -vf scale=1920:1080:flags=lanczos \
    -c:v libx264 -preset medium -crf 21 \
    -c:a aac -b:a 192k -ac 2 -movflags +faststart ${OUTPUT}
```

`${VAAPI_DEVICE}` defaults to `/dev/dri/renderD128`, overridable via
`transcoder/vaapi_device=`. AMF omitted from the container recipe (weak
Linux support) but the schema slot (`args_amf=`) exists for Windows/bare-
metal users.

**Engine changes** (`java/sage/Ministry.java`, ~150–250 LOC):

1. `pickPresetEncoder(codec)` — stop collapsing non-NVENC to `NONE`.
   Return the real `HwEncoder.Kind`. Add `presetBlockFor(Kind)` →
   `"nv"|"vaapi"|"qsv"|"amf"|"sw"`.
2. `buildPresetSpec()` — pick `args_<block>` / `global_<block>` for the
   detected backend, falling back through `sw` if that vendor's block is
   absent. Resolve `%V264%`/`%V265%` only in the NV block; substitute
   `${VAAPI_DEVICE}` etc. Emit the same `f=;MRawCmdlineGlobal=;MRawCmdline=`
   spec — **no `FFMPEGTranscoder` change needed** (the raw-cmdline path
   already round-trips arbitrary flags).
3. Back-compat — a preset that only has the legacy single `args=`/`global=`
   keys keeps working exactly as today (treated as `args_nv` when a
   `%V…%` token is present, else vendor-neutral).

**Container / device requirements.**

- VAAPI/QSV need a DRI render node: bind `/dev/dri` (already mounted by
  `run_mine.sh`) and add the container user to the `render`/`video`
  groups. Verify inside the container with `vainfo` (add `vainfo` +
  `intel-media-va-driver-non-free` **only** in the opt-in Intel image
  layer; do **not** bloat the base).
- ffmpeg build: the unified binary already has `--enable-vaapi`; confirm
  and add `--enable-libmfx`/`--enable-libvpl` for QSV in a follow-up
  build task (cross-reference the `--enable-libnpp` build item above).
  If QSV libs aren't compiled in, `HwEncoder.pick` should not rank QSV —
  verify the probe reflects the actual build.
- These are **Linux** accel paths; keep them behind the same opt-in GPU
  compose layering as NVENC (base compose stays CPU-only).

**Validation.** On an Intel iGPU host and an AMD host: run
`TV_1080_COMPAT` and `TV_4K_HEVC`, confirm (a) the correct encoder is
selected (`Ministry` tier log), (b) `ffmpeg` actually offloads (GPU busy
via `intel_gpu_top` / `radeontop`, CPU stays low), (c) output plays and
matches the NVENC output at similar bitrate. Regression: NVIDIA host and
no-GPU host must behave exactly as before.

**Out of scope for v1.** AV1 encode on any backend; full-GPU zero-copy
VAAPI decode→scale→encode chains (start with CPU-scale + `hwupload` for
correctness, optimize later); AMF on Linux; per-vendor quality tuning
beyond a sane VBR default.

**Estimated effort.** 2–4 days (schema + `buildPresetSpec` vendor
routing + one Intel and one AMD validation pass + container render-node
plumbing). Depends on access to non-NVIDIA test hardware.

### FFmpeg build with `--enable-libnpp` / `--enable-cuda-nvcc`

The bundled SageTV ffmpeg is currently built with `--enable-nvenc
--enable-ffnvcodec` only \(see `ffmpeg -buildconf`\), so `scale_npp`
and `scale_cuda` filters are missing and all `_NV` presets must run
"decode-GPU → scale-CPU → encode-GPU". Rebuild with `--enable-libnpp`
and `--enable-cuda-nvcc` to enable true full-GPU pipelines; then swap
`scale=W:H:flags=lanczos` → `scale_npp=W:H:interp_algo=lanczos` (and
restore `-hwaccel_output_format cuda`) in the `_NV` presets.

### AI upscale via pipeline presets  *(scope; deferred until SD/720p volume warrants)*

**Motivation.** Current `UPSCALE_1440` / `UPSCALE_2160` use Lanczos.
For 1080p→1440p of HD OTA recordings the perceptual delta vs AI is
small. AI upscale shines for **480p SD** and **720p cable/streaming**
sources where classical filters can't invent the missing detail.
Defer until there is enough such content to justify the GPU-hours.

**Backend choice.** **Real-ESRGAN-ncnn-vulkan** (Tencent ncnn + Vulkan):

- Single static binary, no Python / TF / Torch runtime.
- Vulkan path works inside the existing container; needs
  `NVIDIA_DRIVER_CAPABILITIES=compute,video,utility,graphics` (current
  value lacks `graphics`; one-line `run_mine.sh` change).
- Models worth shipping:
  - `realesr-general-x4v3` — balanced, decompression-noise removal,
    **best default for OTA TV**.
  - `realesr-animevideov3` — fast, animation/cartoons.
  - `realesrgan-x4plus` — slow, photo-realistic, max quality.
- Expected throughput on RTX 2060, 1080→4K, default tile size:
  ~1–3 fps (≈ 0.05× realtime for a 24fps source). A 60-min show
  takes ~3–6 GPU-hours. Acceptable only as overnight batch.
- Alternative if any content is anime: **Anime4K** as mpv GLSL shaders
  on the MiniClient — real-time at playback, zero offline cost. That
  is a separate (playback-track) item, not a Ministry preset.

**Constraint that drives the change.** Ministry presets today are
single-ffmpeg-invocation. AI upscale fundamentally needs a pipeline:

```
ffmpeg (decode + optional denoise, write raw frames to fifo/stdout)
  │
  ▼
realesrgan-ncnn-vulkan (upscale frame-by-frame, stdin → stdout)
  │
  ▼
ffmpeg (read raw frames from fifo/stdin, encode HEVC NVENC, mux audio
        from the original via -i original.mpg -map 1:a -c:a aac)
```

That is two ffmpeg processes + the upscaler, glued by a fifo or a
shell `|`. `FFMPEGTranscoder` cannot model this with its current
`MRawCmdline` single-exec form.

**Scope of the engine change** (compact; ~300–500 LOC):

1. `presets/transcoder/*.properties` schema extension. New optional
   keys; presence of `pipeline=` switches Ministry to pipeline mode:
   ```
   name=AI_UPSCALE_1440_GENERAL
   displayName=TV \u2013 AI Enhanced HD (1440p)
   pipeline=ai_upscale          # selector for which wrapper recipe
   ai_model=realesr-general-x4v3
   ai_scale=2
   ai_tile=256                  # 0 = auto; smaller = less VRAM, slower
   container=mp4
   # decode-stage ffmpeg args (input → raw frames on stdout)
   pipeline_decode=-hwaccel cuda -i ${INPUT} -map 0:v:0 \
       -vf scale=1920:1080:flags=lanczos,format=rgb24 \
       -f rawvideo -
   # encode-stage ffmpeg args (raw frames on stdin → final file)
   pipeline_encode=-f rawvideo -pix_fmt rgb24 -s 3840x2160 -r ${FPS} -i - \
       -i ${INPUT} -map 0:v -map 1:a:0? -c:v %V265% -preset p6 \
       -rc:v vbr -cq:v 22 -b:v 12000k -maxrate 18000k \
       -c:a aac -b:a 192k -ac 2 -movflags +faststart ${OUTPUT}
   ```
2. `Ministry.buildPresetSpec()` — when `pipeline=` is present, emit a
   new spec form (e.g. `MPipelineKind=ai_upscale;MPipelineSpec=…`)
   instead of `MRawCmdline=…`. Carry the extra keys verbatim so the
   transcoder can read them back.
3. `FFMPEGTranscoder.startTranscode()` — branch on the new pipeline
   marker. New helper `startPipelineTranscode()`:
   - Probe source resolution + fps via `FFMPEGMediaInfo`
   (already available) to fill `${FPS}` and pick `${OUTPUT_W}x${H}`.
   - Build three ProcessBuilder commands.
   - Wire them via `Pipe` / `ProcessBuilder.Redirect.PIPE`; or `bash
     -c "ffmpeg … | realesrgan … | ffmpeg …"` to keep it simple
     (acceptable since the executable path is sage-controlled).
   - Track the **encode** process for exitValue / job completion
     (it's the last stage; when it exits 0 the file is final).
   - SIGTERM all three on cancel; `Process.destroyForcibly()` after
     `xcode_pipeline_term_grace_ms` (default 5000).
4. `TranscodeJob` — no schema change required (the spec string already
   round-trips arbitrary contents). Progress parsing: the **encode**
   stage's stderr drives the % gauge exactly like single-exec presets
   once `xcode_ffmpeg_loglevel=info` is in effect.
5. Resource gating — same as today (serial via Ministry's `converting`
   list). Pipeline presets should additionally honor a new opt-in
   `transcoder/pipeline_max_concurrent=1` (hardcoded ceiling at 1
   until we add the recording/playback contention gate listed in the
   "Stability / data" backlog below).
6. Container fix — `run_mine.sh`: add `graphics` to
   `NVIDIA_DRIVER_CAPABILITIES`; mount `realesrgan-ncnn-vulkan`
   binary + `models/` dir into `/opt/sagetv/server/ai/`. Document the
   one-line `Sage.properties` knob `transcoder/ai_upscaler_bin=`.

**Out of scope for v1:**

- Multi-pipeline parallelism (one AI job at a time).
- Custom model upload UI (drop files in `/opt/sagetv/server/ai/models/`).
- RIFE frame interpolation (separate roadmap item if ever wanted).
- TensorRT-optimized models — Real-ESRGAN-TRT is faster but needs a
  GPU-specific compiled engine; not worth the build complexity until
  someone actually wants it.
- NVIDIA RTX VSR — **does not apply server-side.** VSR is a *display-time*
  upscaler in the NVIDIA display/decode pipeline (browser video, NVIDIA
  app), applied at the point of presentation on the machine with the
  screen. A headless SageTV-NG server has no display output, so VSR has
  nothing to hook even on RTX 30/40/50 hardware. It could only ever help
  on a *client* that both has an RTX GPU and plays video through a
  VSR-enabled surface — which is outside SageTV-NG's control. Not a
  server feature at any GPU generation.

**Validation.** Convert one known 480p SD recording with
`AI_UPSCALE_1080_GENERAL` and one known 1080p OTA with
`AI_UPSCALE_1440_GENERAL`. Compare against the Lanczos baseline at
identical bitrate. Decision gate: ship only if the SD→HD case is
**unambiguously** better and the HD→QHD case is **at least equal**
(no waxy faces, no ringing on text/scoreboards). If HD→QHD is
controversial, keep AI presets SD-only.

**Estimated effort.** 3–5 days end-to-end (engine + preset schema +
two shipped presets + container/runtime plumbing + validation).

### GPU offload review (2026-07)

Follow-up items from a GPU-offload audit. Several sibling ideas were
already tracked above (libnpp/cuda-nvcc rebuild, AI-upscale container
bind + `graphics` capability, vendor-agnostic `_SW` presets, HLS/DASH,
RTX VSR) — the items below are the ones not previously captured.

- **No-GPU / GPU-absent graceful degradation (cross-cutting, required).**
  `HwEncoder` already probes the ffmpeg binary once per JVM and falls
  back through `nvenc,vaapi,qsv,amf,videotoolbox,none` to
  `libx264`/`libx265`, so encoder *selection* is already vendor-safe.
  The gap is that the modernized `Ministry` presets hardcode
  `h264_nvenc`/`hevc_nvenc` and `-hwaccel cuda` in the command string and
  bypass `HwEncoder`. On a host with no NVENC those presets fail outright.
  Work: (1) route preset codec + `-hwaccel` selection through
  `HwEncoder.pick()` at job-build time so `_NV` presets auto-rewrite to
  software (`libx264`/`libx265`, drop `-hwaccel cuda`) when no GPU is
  detected — or land the vendor-agnostic `_SW` catalogue (Option A above)
  and auto-select; ~~(2) gate the AI-upscale path on a successful Vulkan
  device probe and skip (log + fall back to Lanczos or straight encode)
  when absent;~~ ✅ **done** — `Ministry.shouldAutoAiUpscale` now calls
  `aiUpscaleDeviceAvailable()`, a one-per-JVM cached probe that shells out
  to `sage-ai-upscale.sh --probe` (upscales a trivial 16×16 test frame,
  exit 0 iff a Vulkan device initializes). When no Vulkan GPU is present
  the rule declines and the transcode falls back to the preset's own
  Lanczos scale instead of failing mid-job. Lazy (fires on first
  qualifying SD→HD job, never per-playback), knobs
  `transcoder/ai_upscale_require_vulkan` (default true) and
  `transcoder/ai_upscale_probe_timeout_secs` (default 60).
  ~~(3) make the container GPU reservation opt-in (base
  `docker-compose.yml` is already CPU-only; GPU is layered via override)
  and have startup log the detected encoder tier once.~~ ✅ **done** —
  base [docker-compose.yml](docker-compose.yml) stays CPU-only and runs
  anywhere; NVIDIA GPU (NVENC + Vulkan `graphics` cap + Real-ESRGAN bind)
  is layered via the opt-in [docker-compose.gpu.yml](docker-compose.gpu.yml)
  (`docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d`).
  Startup encoder-tier log already landed with the live-path NVENC wiring
  (`Ministry`). ~~**Remaining for this item: only sub-point (1)** — routing
  the offline `_NV` presets' codec/`-hwaccel` through `HwEncoder` (partly
  covered by `buildPresetSpec`/`stripCudaHwaccel`) or the `_SW` catalogue.~~
  ✅ **done** — per-vendor preset blocks landed (`6a799164`). All 10 GPU
  presets now carry `args_nv=`/`global_nv=` (NVENC params) and `args_sw=`
  (libx264/libx265 with correct `-preset medium`/`slow`, `-crf` instead of
  NVENC `-rc:v vbr`/`-cq:v`). `Ministry.buildPresetSpec()` selects the
  block via `pickVendorBlock()` → `"nv"` for NVENC, `"sw"` for everything
  else. Future vendors add `args_vaapi=`/`args_qsv=` blocks + extend
  `pickVendorBlock()`. DVD preset unchanged (software-only, no variants).
  Non-NVIDIA hosts now get a working transcode path. **All three sub-points
  complete; this item is closed.**

- **Comskip external GPU engine (speculative).** `CommercialDetectionJob`
  already supports `commercial_detection/engine=external` with a
  templated command (`external_recorded_args` /
  `external_live_args`, `{input}/{output}/{ini}` substitution), so an
  alternate comskip binary is pure configuration — no Java change. BUT:
  mainline comskip is not GPU-bound in a way a "comskip-cuda" drop-in
  solves, and no well-maintained CUDA comskip fork exists. The realistic
  GPU win is NVDEC-accelerated *decode* feeding comskip's analysis, which
  upstream comskip does not do. Treat as R&D, not a 2-hour swap. (Also
  verify the property key match: the job reads
  `commercial_detection/external_engine_path` while the manager
  get/set uses its own key — confirm before relying on the UI hook.)

- ~~**Live-tune NVENC audit (small).**~~ ✅ audit done + NVENC/software
  wiring landed. Finding confirmed: the live on-the-fly HLS transcode
  path built its command with a hardcoded `-vcodec libx264` (plus the
  legacy x264 option soup: `-coder`, `-partitions`, `-me_method`,
  `-subq`, `-direct-pred`, `-wpredp`, `-rc-lookahead`, `-trellis`, …)
  in `FFMPEGTranscoder`'s `httplsMode` block, and never consulted
  `HwEncoder`. `LiveTranscodeProfile.hwAccel` was parsed/stored but
  `PlaybackDecisionEngine` only ever read its `maxBitrateKbps`, so the
  `hw_accel` field was effectively dead and concurrent MiniClient live
  transcodes silently bottlenecked on CPU. **Fix:** the `httplsMode`
  encoder is now chosen via `HwEncoder.pick("h264")` — `h264_nvenc`
  with NVENC-appropriate rate control (`-preset`, `-rc:v vbr`, `-g`,
  `-profile:v high`) when an NVIDIA GPU + nvenc-capable ffmpeg is
  present, else the original `libx264` block unchanged (no-GPU hosts
  keep working). NVENC preset knob:
  `multimedia/hwaccel/nvenc/live_preset` (default `p4`).

- **AMD / Intel live transcode (VAAPI / QSV / AMF) — not yet wired.**
  The live/HLS path above engages **NVENC only**; on AMD/Intel hosts it
  falls back to software `libx264`. Full HW support on the live path is
  deferred because it needs work in three distinct areas:
  1. **ffmpeg binary.** The bundled SageTV ffmpeg is built
     `--enable-nvenc` only (verified via `-buildconf`; no `h264_vaapi`
     / `h264_qsv` / `h264_amf` encoders listed). Rebuild
     [docker/build-sagetv-ffmpeg.sh](docker/build-sagetv-ffmpeg.sh)
     with `--enable-vaapi` (AMD/Intel Linux), `--enable-libmfx` or
     `--enable-libvpl` (Intel QSV), and/or `--enable-amf` (AMD
     Windows/Linux Pro). Until then `HwEncoder.pick()` correctly
     returns `NONE` for those kinds, so the live path stays
     dormant/safe on those hosts.
  2. **Filter-graph / hwupload rework (the hard part).** The
     `httplsMode` block appends a **software** `-vf` chain built
     piecemeal (`yadif` deinterlace + aspect/scale added separately).
     VAAPI/QSV need frames uploaded to GPU surfaces via a single fused
     filtergraph (`format=nv12,hwupload` for VAAPI with `-vaapi_device
     /dev/dri/renderD128`; `hwupload=extra_hw_frames=N` for QSV), and
     ffmpeg allows only one `-vf`. The current code's multiple
     conditional `-vf` additions must be consolidated into one graph
     and interleaved correctly with the hwupload/format-conversion
     stages. `HwEncoder.globalArgs(k)` and `HwEncoder.videoFilter(k,
     …)` already produce the right fragments; integrating them into
     the legacy string-built command is what's outstanding.
  3. **Per-client override plumbing.** `LiveTranscodeProfile.hwAccel`
     (`auto|nvenc|vaapi|qsv|amf|videotoolbox|none`) is not currently
     passed into `FFMPEGTranscoder`; the live path uses the global
     `multimedia/hwaccel/preferred` order via `HwEncoder.pick()`. To
     let a specific client force VAAPI/QSV, thread the profile's
     `hwAccel` through `PlaybackDecisionEngine` → `HTTPLSServer` →
     `FFMPEGTranscoder` as a per-transcode override of the global pick.
  Blocked primarily on (1)+(2); (3) is a small follow-on. No AMD/Intel
  GPU is on the current host, so this is untestable until hardware +
  an appropriately-built ffmpeg are available.

- **MiniPlayer push-mode transcode GPU offload (high priority).**
  When a MiniClient's profile doesn't support the source codec (e.g.
  HEVC source + `desktop_default` profile without HEVC), `MiniPlayer`
  forces a push-mode transcode via `FFMPEGTranscoder`'s legacy format
  builder. This path produces `-vcodec mpeg4 -f dvd -s 1280x720` at
  1 Mbps — 2008-era quality, pure CPU, ignores `HwEncoder` entirely.
  This is the most common transcode trigger for legacy Windows and
  Android clients watching HEVC/ATSC3 content. Fix: wire
  `HwEncoder.pick()` into the MiniPlayer push-transcode codec
  selection so it uses `h264_nvenc` (or software `libx264`) with
  modern rate control instead of `mpeg4`/DVD. Output format should be
  MPEG-TS H.264 (universally playable by all MiniClients) at a
  bitrate appropriate to the client's reported bandwidth. The
  HTTPLSServer live-tune fix (`httplsMode` NVENC wiring) is a
  template for this — same pattern, different entry point.
  Effort: 4–8 hours.

- **NVDEC thumbnail generation (low value — likely rejected).** Proposed
  as a library-rescan speed-up, but thumbnails are single-frame
  seek+extract operations where per-file NVDEC context init typically
  *outweighs* the decode saving. Only worth it if profiling shows thumbnail
  generation is a real bottleneck on batch import; otherwise leave as CPU.

- **AI/ML commercial detection (R&D parking lot).** Replacing comskip's
  black-frame/silence/aspect heuristics with an ONNX/TensorRT ad-vs-content
  classifier is a research effort with no training-data pipeline today;
  the "much better on sports/news" payoff is unproven. Park until (a)
  comskip accuracy is measured to be the actual pain point and (b) a
  labeled dataset path exists. Would reuse the external-engine hook.

- **Face recognition / people tagging — out of scope.** A photo-library
  ML feature with little DVR/live-TV value and large scope/privacy
  surface. Not aligned with SageTV-NG's recording/streaming product
  focus; not planned.

## Playback track

- **Transcode-seek reuse (trickplay responsiveness — high value).**
  `FFMPEGTranscoder.seekToTime()` currently tears down and respawns
  ffmpeg on *every* seek (its own comment: "This will ALWAYS rebuild
  the transcoder" — `stopTranscode()` kills the process + joins consumer
  threads up to 2s each, then `startTranscode()` spawns a fresh ffmpeg
  with `-ss`). Because FF/REW are implemented as repeated `seek()` calls
  (`MiniPlayer.setRate` → relative skips), every trickplay tap pays a
  full ffmpeg cold-start + pipeline warmup + client buffer flush/refill.
  Pull and REMUX seeks avoid the encoder entirely (client-side seek or a
  native byte reposition), so only the TRANSCODE path is slow. Improve by
  one of: (a) keyframe-indexed restart that reuses the encoder session
  when the seek target is near the current position, (b) a small
  output-ring-buffer window so short FF/REW taps land within already-
  transcoded data (partly exists via `PUSH_BUFFER_SEEKING` /
  `DETAILED_BUFFER_STATS` client fast-path — verify it engages), or
  (c) an ffmpeg build/mode that seeks without full teardown. This is the
  single biggest trickplay pain on the transcode path. Substantial effort;
  measure seek latency before/after.

- **Audio-only transcode when only the audio codec mismatches.** In
  `PlaybackDecisionEngine.evaluate()` the REMUX branch only fires when
  BOTH video and audio codecs are supported and just the container is
  wrong. When the video codec is fine but the AUDIO codec is unsupported
  (e.g. AC-4/DTS on a client that lacks it), the decision falls through to
  the full TRANSCODE branch. It does set `targetVideo = mediaVideoCodec`
  (source codec unchanged), but the transcoder must then emit
  `-c:v copy` (stream-copy the video, transcode only the audio, then mux)
  rather than re-encoding the video. Verify `FFMPEGTranscoder` honors the
  copy opportunity when `targetVideo == sourceVideo`; if it re-encodes
  video unnecessarily, add an explicit audio-only path. Payoff: keeps
  trickplay fast (no video encode = no cold-restart cost) AND fixes the
  audio incompatibility. Cross-references the existing AC-4→EAC3
  audio-only path in the deployed jar (see userMemory sagetv-deploy notes).

- **Per-airing audio language UI selector.** Server-side language-aware
  audio mapping is done (see Done section: `AC4TranscodeJob` honors
  `default_audio_language` / `hdhr/ac4_transcode_audio_lang`). Still
  needed: a click-through UI in the STV that lets the user override
  the language per show without changing the global default.
- **CTA-708 captions in HEVC SEI.** Accessibility win; needs SEI
  extraction or pass-through to client.
- ~~**Comskip end-to-end verification.**~~ ✅ code audit complete
  (`8d94cb23`). All four stages verified intact: (1) `Seeker` →
  `CommercialDetectionManager.onRecordingStarted/Stopped` → `submitJob`
  → `CommercialDetectionJob.runComskip()` constructs correct command,
  (2) `EdlWriter.readEdl()` parses standard EDL → `SkipMatrix.fromEdlSegments()`
  builds binary-search struct, (3) `VideoFrame.checkCommercialSkip()` monitors
  position every cycle with boundary-aware wake, auto-seeks past commercials,
  (4) REW preroll (`~line 1845`) uses `SkipMatrix.getCommercialStart()` to land
  15s before break. Additionally, `isEnabled()` now auto-detects: if
  `commercial_detection/enabled` has never been set, checks whether the comskip
  binary exists at the configured path — no manual Sage.properties edit needed.
  **Remaining: runtime verification test** (enable comskip on a real recording,
  confirm `.edl` appears, confirm auto-skip fires during playback).
- **Container/host TZ-mismatch resilience.** Today Sage relies on the
  `time_zone=` property to call `TimeZone.setDefault()` (`Sage.java`
  ~line 1078). When that property is unset OR the container OS clock
  is in UTC while the property is local, the JVM default ends up UTC
  and any subprocess (ffmpeg, comskip) inherits the bad `TZ` env —
  log timestamps drift, EPG math gets edge-case wrong, and child
  process output (mux metadata, comskip log lines) gets stamped UTC.
  Harden in three places:
  1. `Sage.java` startup — if `time_zone` is unset, fall back to
     reading `/etc/timezone`, then `$TZ`, then JVM default; log the
     resolved zone once at INFO so misconfigs are visible.
  2. After `TimeZone.setDefault()`, also push the resolved zone into
     `System.setProperty("user.timezone", ...)` and re-export `TZ`
     in the env passed to `ProcessBuilder` for ffmpeg/comskip
     (`FFMPEGTranscoder`, `CommercialDetectionJob`) so children agree
     with the parent.
  3. Add a startup self-check that compares `ZoneId.systemDefault()`
     against `TimeZone.getDefault()` and warns if the OS clock offset
     (`new Date().getTime() % 86400000`) disagrees with wall-clock
     local time by more than a few seconds — catches host/container
     clock drift before it manifests as recording-time bugs.
  Goal: a container running in UTC with `time_zone=America/Chicago`
  should be fully self-consistent end-to-end without the operator
  also having to set `TZ` and `/etc/localtime`.

---

## Longer-term modernization track  *(backlog, not scheduled)*

Bigger architectural changes. Each is independently shippable; order
below is rough best-ROI-per-effort, not commitment.

### Cross-cutting constraint: OpenSageTV plugin-repo compatibility

Every item in this track MUST preserve compatibility with the existing
plugin ecosystem at
[OpenSageTV/sagetv-plugin-repo](https://github.com/OpenSageTV/sagetv-plugin-repo)
(Phoenix, BMT, sagex APIs, CMT, OpenDCT, Comskip launcher, nielm web
server, etc.). The stable surfaces plugins depend on:

| Surface | Stability rule |
|---|---|
| STV API (`Global.GetXxx()`, `Database.GetYyy()`, etc.) | Additive only — never remove or rename existing entries |
| `sage.Wizard` Java API | Byte-compatible signatures; storage backend swaps are internal |
| `SageTVPluginRegistry` / `SageTVPlugin` lifecycle | Untouched |
| Socket protocol on TCP/7818 | Frozen — new APIs are additive sidecars |
| MPEG-TS recording files (`.mpg`) | Default container stays MPEG-TS; new containers are opt-in per Channel or global property |
| HTTP endpoints on 8080 (sagex, MediaStreaming, mobile web) | Additive only |
| `sage.Sage` static logging facade | Kept as a thin shim over any new logger |

Per-item compatibility plan is called out inline below.

### Stability / data
- **Wizard flat-file DB → SQLite or embedded H2 option.** Replace
  `Wiz.bin` (single proprietary file, no transactions) with an ACID
  embedded DB behind the existing `Wizard` API. Wins: crash recovery,
  standard SQL tooling, incremental backups, ~10–100× faster lookups
  on large libraries.
  *Plugin compat:* keep every public `Wizard` method signature
  unchanged; backend chosen by `wizard/backend=flatfile|sqlite`
  (default `flatfile`). Plugins see no change.
- **`sage.Sage` logging → SLF4J + Logback.** Bridge `sage.Sage.DBG` /
  `printlnObject` onto SLF4J (Logback config already present in
  container). Wins: per-package log levels, built-in rotation, JSON
  output for log aggregators, dynamic level changes via JMX.
  *Plugin compat:* `sage.Sage` static methods kept as facade — any
  plugin that calls `sage.Sage.println(...)` keeps working.

### Modern dependencies
- **Investigate moving `third_party/` flat jars to Maven Central.**
  Today Javolution, JCIFS, GSON, Bouncy Castle, JmDNS, Apache Commons
  bits, etc. are vendored as pre-built jars under `third_party/` and
  merged into the fat `Sage.jar` at build time; upgrades and CVE
  tracking are manual. Investigation scope: per-jar audit (which have
  Maven coordinates at the pinned version; which carry local patches
  that would have to be reapplied or upstreamed; which are paired
  with native bits or JCE policy files); proof-of-concept Gradle
  `implementation` declarations for the safe ones (start with GSON,
  JmDNS, Commons); confirm Sage.jar fat-jar output stays byte-stable
  enough that OpenSageTV plugins reflecting on classpath contents
  don't break. Wins: CVE scanning via Gradle dependency-check,
  reproducible hashed artifacts, smaller repo, one-line version
  bumps, surfaced transitive-dep conflicts. Risks: classpath order
  shifts (mitigate by keeping fat-jar deploy artifact), version drift
  vs plugins compiled against exact vendored quirks (mitigate by
  pinning to current versions first, upgrading separately), offline
  build story (mitigate with a populated `gradle/offline-cache/`).
  *Plugin compat:* keep emitting the same fat `Sage.jar` so plugin
  classpath assumptions hold; thin-jar + `libs/` layout is a dev-only
  variant.
- **JCIFS 1.1.6 → jcifs-ng (SMB2/SMB3)**, *should upgrade eventually but
  not now*. SMB1 has been disabled by default on every Windows release
  since 2017 and on most modern NAS firmware; the bundled JCIFS 1.1.6
  speaks only SMB1, so any SageTV mount of a current Windows / Synology /
  QNAP / TrueNAS share fails until the admin re-enables SMB1. Modern
  `jcifs-ng` (`org.codelibs:jcifs:2.x`) supports SMB2/3 and is actively
  maintained, but it is an API rewrite — package moves from `jcifs.smb`
  to `jcifs.smb` v2 layout (or a `jcifs.smb1` shim), and `SmbFile`
  constructor signatures change. Worth a follow-up if SageTV is doing
  SMB to your shares; if you are not mounting any SMB shares from SageTV,
  just leave it.
  *Plugin compat:* a few plugins reference `jcifs.smb.SmbFile` directly;
  ship the legacy 1.1.6 jar alongside the new one (or via the `jcifs.smb1`
  compat package) so plugin classpaths still resolve.
- **GSON (sun.misc.Unsafe path) → Jackson.** Removes the
  `sun.misc.Unsafe` reflective warnings on Java 17+, futures JDK
  upgrades to 25 LTS+, ~2× JSON parse throughput on large EPG payloads.
  *Plugin compat:* keep GSON jar on classpath since sagex services
  serialize through it; only internal call sites move to Jackson.

### Recording / streaming
- **Native MP4/MKV recording containers.** Optional end-of-recording
  (or live segment) remux from MPEG-TS to MP4/MKV; codecs unchanged.
  Files become directly playable in VLC/Plex/Jellyfin/browsers without
  re-transcode; multi-lang audio + chapters preserved cleanly.
  *Plugin compat:* MPEG-TS remains the default. Opt-in via
  `recording/container_default=mpegts|mp4|mkv` (global) plus
  per-Channel override. Comskip/BMT keep seeing `.mpg` unless user
  opts a Channel in.
- **Transcoding pipeline cleanup: MPlayer → modern FFmpeg profiles.**
  Profile-driven FFmpeg command builder
  (`profile=tablet_720p_hevc` → args), HW-accel paths (NVENC/QSV/VAAPI).
  Wins: smaller bitrates at same quality, more simultaneous transcodes
  via GPU offload, survives distro upgrades that drop MPlayer.
  *Plugin compat:* keep `mplayer` external launcher available since
  a few plugins shell out to it; new pipeline is alongside, not
  replacing.
- **HLS/DASH streaming (replace `HTTPLSServer`).** Serve recordings +
  live TV as standard HLS playlists / DASH manifests with adaptive
  bitrate ladders. Wins: native playback in browsers / smart TVs /
  Chromecast / AirPlay without an app; ABR adapts to network.
  *Plugin compat:* new endpoint paths (`/hls/...`, `/dash/...`);
  `HTTPLSServer` legacy URLs remain.

### Capture devices
- **HDHomeRun IPv6 parity in OTA helper path.** `HdhrControl.pickReachableIp()`
  currently skips IPv6 addresses (`if (ip.indexOf(':') >= 0) continue`).
  Complete dual-stack support for interface selection and control-path
  targeting so IPv6-only or IPv6-preferred LANs work without IPv4 fallback.
- **HDHomeRun: pure-Java HTTP capture path (optional).** Today
  HDHomeRun tuning + capture goes through `libHDHomeRunCapture.so`
  (JNI → bundled `libhdhomerun` → RTP/UDP stream receive). The ATSC3
  path already proves an HTTP-pull alternative works end-to-end
  (`HttpPullCaptureJob` against `/auto/v<vchannel>` on port 5004),
  and the device's lineup is fetched as JSON over HTTP today. A
  pure-Java HTTP capture mode would let the same flow handle ATSC1
  too, retiring `libHDHomeRunCapture.so` / `HDHRDevice.cpp` and the
  `third_party/SiliconDust/libhdhomerun/` C tree. Wins: one fewer
  native build target (no more g++-13 / make wrangling inside the
  container — see `scripts/build-hdhr-lib.sh`), no more RTP packet-
  size firmware fragility (the FLEX 4K firmware 20260326 RTP issue
  fixed in `bce87984` would not have existed), trivial cross-platform
  story for any future non-Linux server, and HTTP semantics that play
  nicely with reverse proxies / VPNs. Opt-in via
  `hdhr/capture_transport=jni|http` (default `jni`) so the JNI path
  stays the verified default until the HTTP path has parity on tuner
  locking, signal-strength reporting, and Channels DVR style fallback.
  *Plugin compat:* `CaptureDevice` API unchanged; only the internal
  byte-source swaps.

### Integration
- **REST/gRPC API layer alongside socket protocol 7818.** Additive
  HTTP API exposing channels/recordings/EPG/scheduling. Existing
  binary protocol keeps working. Wins: Home Assistant / Node-RED /
  shell-script integrations in 10 lines instead of learning the binary
  framing; OpenAPI docs; browser-based test consoles.
  *Plugin compat:* port 7818 is frozen forever. The new API is a
  sidecar on a separate port; sagex API + Placeshifter + MiniClient
  keep using 7818 unchanged.

---

## Distributed architecture track  *(backlog, not scheduled)*

### Modular frontend / backend separation + multi-server aggregation

**Goal.** Decouple SageTV into independent frontend and backend
modules so a single "aggregation node" can present a unified UI / EPG
/ scheduling DB while delegating recording, EPG ingestion, and media
storage to one or more backend servers — possibly across sites
(e.g. Chicago + Madison over VPN).

**Why.** Today a SageTV install is a single monolith: one process
owns tuners, recordings, EPG, scheduling, UI, and all plugins. There
is no concept of "borrow my friend's tuner when mine is busy" or "I
have a recording farm in the basement and a UI box upstairs." A clean
frontend/backend split unlocks:

- Multi-site households / shared SageTV cooperatives
- Independent scaling of recording capacity vs UI clients
- Cleaner upgrade story (replace backend without touching the UI)
- Geographic EPG mixing (catch a Chicago show on a Madison tuner if
  the Chicago tuner is recording two others)

**Module decomposition.**

*Backend (per server) — modular service responsibilities:*
- Local recording scheduler + execution
- EPG ingestion (Schedules Direct, OTA EIT, ATSC3 ESG, future sources)
- Media storage + metadata management
- Media serving (streaming endpoints, file access)
- Backend-class plugin execution (recording rules, EPG augmentation,
  commercial detection, transcoding)

*Frontend (aggregation node) — primary responsibilities:*
- Aggregate multiple backends into one logical system
- Unified EPG, recording schedule UI, media browser/search
- Manage content-locality preference (prefer local backend; fall back
  to remote over VPN)
- Playback routing decisions

**Unified EPG layer.**
- Merge listings from multiple backends; deduplicate channels and
  programs when overlapping
- Maintain source attribution (which backend owns which tuner/channel)
- Cross-region listings (Chicago + Madison) become a single guide
- Cross-server scheduling: frontend selects optimal backend per
  recording (locality, tuner availability, rules); backends report
  status back; conflicts arbitrated by frontend

**Media flow modes.**
1. **Direct client → backend** (preferred): client streams from
   the chosen backend; frontend is control-plane + metadata only.
2. **Frontend proxy** (fallback): frontend relays stream to client
   when VPN routing or legacy-client compatibility requires it.
3. **Hybrid / smart routing:** client attempts direct; falls back
   to proxy on failure.

**Multi-site / VPN considerations.**
- Backends advertise location / latency hints
- Frontend prefers proximity; optional latency- and bandwidth-aware
  selection
- Stable identity per backend so reconnects after VPN flaps are clean

**Service APIs required.**
- *Backend service API:* EPG export, recording control, media catalog,
  stream endpoints, capability discovery (tuners, codecs, plugin
  capabilities — important for ATSC 3.0 / AC-4 capability mixing)
- *Frontend aggregation API:* unified EPG query, cross-server
  scheduling, global search

**Plugin model — split responsibility.**
Existing plugins are not cleanly separated, so this needs:
- *Capability tagging:* every plugin declares
  `frontend` / `backend` / `hybrid` in its `SageTVPlugin.xml`
- *Compatibility shim:* untagged legacy plugins default to `hybrid`
  and run on whichever node ends up doing the work, with a warning
  about potential duplication

**Known challenges / risks.**
- Plugin ecosystem fragmentation during the tagging transition
- Synchronization complexity across backends (clock skew, partition
  handling)
- Duplicate-media handling when the same recording exists on multiple
  servers
- UI responsiveness with distributed queries (need aggressive
  frontend caching of EPG slices)
- Legacy client compatibility, especially HD300 / extender protocol
  (these will likely always talk to a single backend directly via
  proxy mode)

**Sequenced sub-deliverables** (rough order, each independently
useful):
1. Backend service API definition (OpenAPI / proto) — builds on the
   "REST/gRPC sidecar" item above
2. Capability discovery endpoint on existing servers (no behavior
   change yet, just self-describe)
3. Plugin capability tagging spec + shim
4. Read-only frontend aggregator MVP — unified EPG view from N
   backends, no scheduling yet
5. Cross-server scheduling + recording arbitration
6. Media flow modes (direct → proxy fallback)
7. Latency-aware backend selection
8. Production multi-site deployment

---
## STV Optimization track

The STV (UI definition) optimization toolkit lives in `docs/STV_Cleanup/`. Phases 1–3 are largely shipped (script-driven; see `docs/STV_Cleanup/PHASE1_RESULTS.md`). Phase 4 is long-term scope.

### Phase 1 — Widget Deduplication  *(shipped)*

Estimated effort: 2–3 days  •  Risk: Low  •  Expected gain: 10–20% file size reduction

Implemented in `docs/STV_Cleanup/stv_deduplicator.py`. Already run against `stvs/SageTV7/SageTV7.xml` — removed 6,715 redundant definitions per `docs/STV_Cleanup/PHASE1_RESULTS.md`.

### Phase 2 — Expression Caching  *(in progress — build-time layer complete)*

Estimated effort: 1–2 weeks  •  Risk: Medium  •  Expected gain: 60–80% selection lag reduction

Phase 2 is a two-layer system. The **build-time layer** — implemented
in `docs/STV_Cleanup/stv_cache_patcher.py` (`apply_patches`,
`scan_screen`, `find_before_menu_load`) — identifies expensive Catbert
expressions repeated within a screen subtree and emits
`SetLocal`/`GetLocal` patch plans. This layer is complete and usable
today (`--report` for dry-run, `--apply` for output).

The **runtime layer** is a separate concern: STV XML hooks and Java
callbacks must invalidate or refresh cached locals when state changes
(focus move, selection change, setting change). This layer is not in the
Python patcher — it belongs in SageTV7.xml event Actions and optionally
in Java property-change listeners. PRD AC-2.5 (state-change correctness)
depends on this runtime layer being present.

Current status: **patch generation complete; runtime invalidation hooks
pending.** See `docs/STV_Cleanup/PHASE2_NOTES.md` for the full
architecture breakdown and AC mapping, and
`docs/STV_Cleanup/PHASE2_RUNTIME_DESIGN.md` for the runtime-layer
investigation plan.

### Phase 2 hygiene — Review and remove 31 dead `_c_*` SetLocal seeds in SageTV7.xml

Estimated effort: ~1 hour  •  Risk: Low–Medium  •  Expected gain: 31 fewer `GetProperty`/`GetCurrentMediaFile`/`GetElement` calls per menu load

Five `_c_<callsig>` cache slots are seeded by `SetLocal(...)` on menu load but have zero corresponding `GetLocal(...)` reads anywhere in the file:

| Cache slot | Seed sites |
|---|---|
| `_c_GetProperty_display_video_on_menus_XIf_Active` | 19 |
| `_c_GetCurrentMediaFile` | 8 |
| `_c_GetProperty_video_menu_style_XWindow` | 2 |
| `_c_GetCurrentPlaylist` | 1 |
| `_c_GetElement_PluginTypesList_0` | 1 (orphaned by the 2026-06-30 plugin menu fix) |

**Risk before removing:** any of these could be a read that got accidentally deleted during a past UI rework — removing the seed would then mask the real bug. For each slot, before deletion, grep upstream `google/sagetv` and our git history for the read sites to confirm they were intentionally retired (not lost). If the read was lost, restore it; otherwise drop the seed.

**Reward:** very small per-menu load latency reduction; less dead code in the STV.

### Phase 3 — Theme Chain Flattening

Estimated effort: 2–3 weeks  •  Risk: Medium  •  Expected gain: O(depth)→O(1) per property lookup at paint time

Pre-resolve theme inheritance chains and write final property values directly onto each theme widget, eliminating ancestor traversal at paint time. Implemented in `docs/STV_Cleanup/stv_theme_flattener.py` (`flatten`). Script is functional; awaiting integration into a full Phase 1 → 2 → 3 pipeline run.

### Phase 4 — Screen Isolation & Modularization  *(long-term)*

Estimated effort: 1–3 months  •  Risk: High  •  Expected gain: Catbert skips inactive screens (large paint-time reduction)

**Description.** Split the monolith `stvs/SageTV7/SageTV7.xml` into per-Sym-prefix module files and add `IsCurrentMenu()` guards to every screen subtree so Catbert can short-circuit evaluation of inactive screens. Compose modules back into a canonical STV at build/deploy time, with optional plugin injections via `.stvi` ImportSTV hooks. Implemented in `docs/STV_Cleanup/stv_modularizer.py` (`split` and `compose` subcommands).

**Sub-commands.**

1. **split** — Split the monolith into per-prefix module files:

       python docs/STV_Cleanup/stv_modularizer.py split stvs/SageTV7/SageTV7.xml ./modules/

   Optional flags: `--no-guards` (skip `IsCurrentMenu()` injection — debug only; defeats the optimization), `--dry-run` (report without writing).

2. **compose** — Merge modules back into a canonical STV with optional plugin injections:

       python docs/STV_Cleanup/stv_modularizer.py compose ./modules/ stvs/SageTV7/SageTV7_composed.xml --plugins ./plugins/ --hooks hooks.json --order BASE OPUS4 OPUS4A NFLX1 COMSKIP XHDFU

   The `--plugins`, `--hooks`, and `--order` flags are all optional (hooks defaults to `hooks.json` in the current directory). On PowerShell, run on one line — the multi-line `\` continuation style is bash/zsh only.

**Acceptance Criteria.**

- AC-4.1 All screens guarded — `grep -c 'IsCurrentMenu' output.xml` ≥ 619
- AC-4.2 Module files produced — `ls modules/*.stv` shows one file per Sym prefix
- AC-4.3 Compose round-trip — `compose(split(original)) == original` (modulo whitespace)
- AC-4.4 No ID conflicts — `compose` reports 0 duplicate IDs
- AC-4.5 Plugin hooks applied — Each `.stvi` ImportSTV block injects into its correct target
- AC-4.6 All 619 screens pass — Full regression test of every Menu screen passes

**Source:** `docs/SageTV_STV_Optimization_PRD.md`, Phase 4

---

## Done

- **SBBI UPnP → JUPnP migration complete.** `PlaceshifterNATManager`
  fully ported to `org.jupnp:org.jupnp:3.0.3` + `org.jupnp.support:3.0.3`
  (Maven dependencies); all behaviour and Sage.properties keys preserved.
  Vendored `third_party/UPnPLib/sbbi-upnplib-1.0.3.jar` removed and
  `build/copyserverfiles.sh` no longer copies it into
  `serverrelease/JARs/`. Fixes long-standing IPv6 / multi-NIC bugs on
  multi-homed servers.
- **Build system: Maven runtime deps now actually shipped to `JARs/`.**
  `Sage.jar` is a *thin* jar (project classes + resources only), so the
  Maven-resolved `org.jupnp`, `slf4j-api`, `logback-*`, JOGL/GlueGen, etc.
  must live as sidecar jars in `/opt/sagetv/server/JARs/` to satisfy the
  `Sage.jar:JARs/*` runtime classpath. Before this change `copyserverfiles.sh`
  only copied the four hand-vendored `third_party/*/*.jar` trees, so any
  Maven-only dep (including the new JUPnP migration) silently failed at
  runtime with `NoClassDefFoundError`. Added a Gradle `copyRuntimeJars`
  task (`build.gradle`) that exports `configurations.runtimeClasspath`
  to `buildoutput/runtime-jars/`, wired `sageJar.dependsOn copyRuntimeJars`,
  and extended `build/copyserverfiles.sh` to cp those jars into
  `serverrelease/JARs/` after the third_party trees. This also fixes the
  parallel SLF4J 2.x / Logback gap that was about to bite the logging
  modernization.
- **`commons-jxpath` 1.1 → 1.4.0.** Vendored
  `third_party/Apache/commons-jxpath-1.1.jar` (2008) replaced with the
  Apache-reactivated `commons-jxpath-1.4.0.jar` (2025-04 Maven Central
  release). Wire-compatible API; SageTV core does not call it directly
  but plugins (Phoenix etc.) link against it at runtime.
- **Dual-stack host:port handling hardening (IPv4 + IPv6 literals).**
  Added `java/sage/NetworkAddressUtils.java` and integrated it where
  host:port text is constructed/parsing is performed:
  `SageTVConnection` notifier/client identity formatting,
  `NetworkEncoderManager` discovery host formatting, and
  `miniclient/AppletConnection` endpoint parsing/formatting.
  This removes `host + ":" + port` ambiguity for IPv6 literals by
  using bracket-safe authority formatting (`[v6]:port`).

- **Unified SageTV-patched, AC-4-capable FFmpeg binary** —
  `docker/build-sagetv-ffmpeg.sh` builds a single binary from the
  [elliotclee/FFmpeg](https://github.com/elliotclee/FFmpeg) fork
  (commit `1dc7ff583b`) with inline SageTV patches for `-stdinctrl`,
  `-activefile`, `-dumpmetadata`, `-brokendts`, plus AC-4 decode,
  NVENC (h264/hevc/av1), libx264, libx265, libfdk-aac, libxvid,
  libfreetype. Dockerfile stage 1 runs the build script; stage 2
  installs the output at `/opt/sagetv/server/{ffmpeg,ffprobe}`.
  Obsolete scripts deleted: `build-modern-ffmpeg.sh`,
  `build-ac4-ffmpeg.sh`, `ffmpeg-wrapper.sh`. Full design in
  [docs/FFMPEG_UNIFICATION_PLAN.md](docs/FFMPEG_UNIFICATION_PLAN.md).
- **FFmpeg 6.x → 7.x CLI audit completed** — `4fa26838` (final
  cleanup) on top of `1515b199`, `3e13d648`, `d0451217`. Full sweep
  of every direct ffmpeg invocation in the Java tree:
  - `FFMPEGTranscoder`: `-ab` → `-b:a` migration in mode-string
    converter (`1515b199`); auto-deinterlace guards recognise both
    legacy `-deinterlace` and modern `yadif` filter (`3e13d648`);
    `-deinterlace` emission replaced with `-vf yadif` (`d0451217`).
  - `MediaFile.extractThumbnail`: `-vsync 0` → `-fps_mode passthrough`
    (`3e13d648`); `-deinterlace` → `-vf yadif` (`d0451217`).
  - `FormatParser.getFFMPEGFormatInfo`: libav-numeric `-v 2` → `-v
    info` so Input/Duration/Stream banner reaches the parser
    (`1515b199`).
  - `CaptionExtractionJob`: default `caption_extraction/ffmpeg_path`
    resolves via bundled `FFMPEGTranscoder.getTranscoderPath()` rather
    than relying on `PATH` (`1515b199`).
  - `Ministry` offline presets: iPhone/AppleTV `-async 50` →
    `-af aresample=async=50` and `-directpred 3` dropped (`1515b199`);
    HDTV/NTSC/PAL `MPEG4*-H.264 MKV` presets `-directpred 1` dropped
    (`4fa26838`).
  - `HwEncoder`, `AC4TranscodeJob`: already ffmpeg-7-clean (modern
    `-c:v`/`-c:a`/`-b:v`/`-b:a`/`-vf`/`-map`/`-preset`/
    `-compression_level`/`-vaapi_device`).
  - `HTTPLSServer`: orchestration only, delegates flag emission to
    `FFMPEGTranscoder`.
  - The remaining legacy x264 option soup inside the six
    `MPEG4*-H.264 MKV` mode strings (`-coder`, `-flags +loop`,
    `-partitions`, `-me_method`, `-subq`, `-flags2`, `-wpredp`, etc.)
    is intentionally left for the **Offline transcode preset
    modernization (Ministry)** rewrite tracked separately above.
- **Client capability spec + per-client settings docs** — `0adcd3bc`.
  New [docs/NGClientCapabilities.md](docs/NGClientCapabilities.md)
  defines the SageTV-NG client capability handshake; new
  [docs/ClientSettings.md](docs/ClientSettings.md) documents the
  per-client transcode profile property surface. ROADMAP gains the
  "Offline transcode preset modernization (Ministry)" subsection in
  the FFmpeg track.
- **HEVC+AC-4 push playback fix (Android miniclient)** — `42cba626`.
  `MiniPlayer` now skips the legacy `DVDStream(0, 0xbd80)` private-PES
  primer on non-PS wire containers (Matroska / fMP4), which was the
  source of the "spinning" hang on the Galaxy Tab S9 FE. Push hint
  synthesis from `prefTranscodeMode` strings; `ClientProfileManager`
  drops AC4/AC-4 entries from `android_modern` audio set so the server
  never offers AC-4 to push clients; `FFMPEGTranscoder` adds an AC-4
  source-audio override path and migrates `-ab` → `-b:a`.
- **FFmpeg CLI modernization in standalone subprocess callers** —
  `1515b199`. `FormatParser.getFFMPEGFormatInfo` switched from
  libav-numeric `-v 2` (= panic-level, suppressed banner) to `-v info`,
  restoring Input/Duration/Stream output that the parser needs.
  `Ministry` iPhone / AppleTV presets scrubbed: `-async 50` →
  `-af aresample=async=50`, removed `-directpred 3` (FFmpeg-7-incompat).
  `CaptionExtractionJob` default `caption_extraction/ffmpeg_path` now
  resolves via `FFMPEGTranscoder.getTranscoderPath()` (bundled binary)
  instead of relying on `PATH`.
- **ATSC3 ↔ ATSC1 mirror manager (dry-run by default)** — `b6182006`.
  New `sage.epg.atsc3.Atsc3MirrorManager` pairs ATSC3 stub channels
  (1xx.y) with their ATSC1 sibling (xx.y) via
  `EPG.setOverride`/`clearOverride`, so SD schedule data on the ATSC1
  station ID can be tuned through the ATSC3 vchan. Properties under
  `atsc3/`: `mirror_enabled`, `mirror_mode`
  (`atsc1_only`/`prefer_atsc1`/`prefer_atsc3`), `mirror_dry_run`,
  `mirror_pairs`, `mirror_drm_stations`, `mirror_refresh_interval_ms`.
  Auto-detect walks `Wizard.getChannels()` and pairs any 1xx.y with
  its xx.y sibling. Startup hooked from `SageTV.java` after
  `Atsc1EITScanner`.
- **AC4TranscodeJob language-aware audio track selection** — `50109844`.
  `ffprobe`-driven `-map 0:a:N` matching `default_audio_language` (full
  name) or new `hdhr/ac4_transcode_audio_lang` ISO override. Required
  for stations like WGBO 166.1 that broadcast Spanish primary + English
  secondary AC-4. End-to-end verified: spa+eng AC-4 input →
  `-map 0:a:1` → transcode TS with `TAG:language=eng`.
- **NG capability-protocol version channel
  (`SAGETV_NG_VERSION` / `GetMiniclientNgVersion()`)** — `10140011`.
  New GetProperty channel separate from `FIRMWARE_VERSION`, so NG
  miniclients route to `android_modern` based on NG version (1.0.1+)
  instead of accidentally satisfying the legacy `>= 1.15` gate via
  `FIRMWARE_VERSION="9.0.0"`. Back-compat: old `autoDetectProfile()`
  overloads kept, stock 9.x clients return empty NG version and fall
  back to existing apk-version gate. New `GetMiniclientNgVersion()`
  STV API exposed in `sage.api.Global`.
- "No data" placeholder Show uniqueness (Phase 0): `Wizard.addMediaFile`
  and `addMediaFileRecovered` now fork a unique Show + Airing pair
  (extID `NODATA::<stationID>::<recStart>`) for any recording made
  against the global noShow placeholder, so the recordings UI no longer
  collapses unrelated no-data recordings into one row and "Delete" no
  longer cascades across them. Original placeholder Airing is preserved.
- ATSC 3.0 / ATSC 1.0 sibling EPG aliasing (Phase 2) + callsign fallback
  (Phase 3): new `sage.epg.EpgFallbackResolver`,
  `Wizard.getAiringsWithFallback()`, hooked into
  `Database.GetAiringsOnChannelAtTime` and
  `GetAiringsOnViewableChannelsAtTime`. Properties:
  `epg/atsc3_alias_offset` (default 100),
  `epg/atsc3_alias_enabled` (default true),
  `epg/callsign_fallback_enabled` (default false / opt-in),
  `epg/callsign_strip_suffixes` (default `-NG,-DT,-HD,-LD,-CD,-TV`).
  Read-time only; never mutates the database; recording / conflict
  detection paths still use strict `getAirings()`.
- ATSC 3.0 channel scan (lineup.json + DRM detection) — `5ad71b14`,
  `c8e8b197`
- ATSC 3.0 HDHR HTTP-pull capture (HEVC + AC-4) — `a4a32ca8`
- ATSC 3.0 ffprobe-ac4 re-probe with in-place format enrichment
  (no playback yank for active VFs) — `788f70bc`
- HEVC + AC-4 push-miniclient transcode pipeline — see
  [sagetv-deploy CHANGELOG](https://github.com/galeforcesage/sagetv-deploy/blob/master/CHANGELOG.md)
- Java 21 / Lucene 4.10.4 / Docker multi-stage / G1GC tuning
- Samsung TV Plus IPTV plugin — `be92eeee`
