# Fork Inventory — SageTV-mine vs upstream `google/sagetv`

Reference catalogue of capabilities that exist in this fork but not in
upstream SageTV. Generated 2026-06-17 against `BUILD_VERSION = 1167`
(`Version 10.0.16`). Companion to:

- [CHANGELOG.md](CHANGELOG.md) — chronological per-version notes.
- [ROADMAP.md](ROADMAP.md) — planned and in-flight work.

This file is grouped by capability domain and lists, for each entry,
the primary class/file, the user-facing API or knob, and a one-line
description. It is intentionally read-only documentation — when an
entry is added/removed, refresh this file rather than editing it
incidentally.

---

## 1. NG client protocol (server → modern miniclients)

A "next-gen" miniclient session that advertises capabilities and
negotiates download/playback behaviour with the server.

- **NG self-declaration** — `MiniClientSageRenderer` sends
  `SAGETV_NG_SERVER=1` to every miniclient on connect, then negotiates
  `SAGETV_NG_VERSION` and `SAGETV_NG_CAPABILITIES`. State is held as
  volatile snapshots so reconnect churn cannot blank an active
  capability set.
- **Capability gates** (`java/sage/api/Global.java`):
  - `IsNgClientSession()` — true when current UI is NG-capable.
  - `HasClientCapability(name)` — checks advertised capability.
    Treats NG-capable sessions as `DOWNLOAD`-capable fallback;
    accepts `DOWNLOAD_REFRESH` as positive evidence.
  - `GetMiniclientNgVersion()` — protocol version string.
- **Server capability set**: `DOWNLOAD`, `DOWNLOAD_REFRESH`,
  `OFFLINE_METADATA`, `OFFLINE_ARTWORK`, `OFFLINE_CAPTIONS`,
  `OFFLINE_COMSKIP`, `OFFLINE_TRANSCRIPT`, `OFFLINE_GUIDE`,
  `GUIDE_SNAPSHOT`, `OFFLINE_SCHEDULED`, `SCHEDULED_SNAPSHOT`,
  `OFFLINE_FAVORITES`, `FAVORITES_SNAPSHOT`.

## 2. Recording-copy transfer (offline-download queue)

End-to-end download contract — server stages files for clients to
pull over HTTP, with a managed queue and per-session policy.

- **Core managers**:
  - `java/sage/client/NgClientRecordingCopyTransferManager.java` —
    session and queue lifecycle, BW clamps, state machine.
  - `java/sage/client/NgClientDownloadTokenManager.java` —
    short-lived per-download tokens.
  - `java/sage/client/NgClientOfflineCompanionBuilder.java` —
    packs sidecar metadata (artwork, captions, skip markers) into
    ACK payloads.
- **Session states**: `REQUESTED`, `QUEUED`, `TRANSFERRING`,
  `PAUSED_BY_CLIENT`, `PAUSED_BY_SERVER`, `COMPLETED`, `CANCELED`,
  `EXPIRED`, `ERROR`.
- **Reason codes**: `CLAMPED_BY_PER_CLIENT_MAX`,
  `CLAMPED_BY_GLOBAL_MAX`, `BACKGROUND_DEFAULT_CAP`,
  `ACTIVE_RECORDING_BACKOFF`, `STORAGE_IO_PRESSURE`,
  `TOO_MANY_ACTIVE_CLIENTS`, `SESSION_EXPIRED`, `TOKEN_INVALID`,
  `RECORDING_STILL_GROWING`, `DISPATCH_FAILED`, `URL_REFRESHED`.
- **STV-callable APIs (~25)** in `Global.java`:
  - Session: `CreateRecordingCopyTransferSession`,
    `GetRecordingCopyTransferStatus(token)`,
    `Pause/Resume/CancelRecordingCopyTransfer(token)`.
  - Per-MediaFile (no token wrangling):
    `GetLatestRecordingCopyTransfer{Status,State}ForMediaFile`,
    `Pause/Resume/CancelLatestRecordingCopyTransferForMediaFile`,
    `GetRecordingCopyTransferUiModeForMediaFile(s)`.
  - Bulk: `Pause/Resume/CancelRecordingCopyTransfersForMediaFiles`.
  - Queue:
    `GetRecordingCopyTransferQueue{Snapshot,SummaryText,ItemCount,ItemLabel,ItemProgressText,ItemId,ItemDetailText}`,
    `Pause/CancelRecordingCopyTransferQueueItem(ById)`.
  - Server:
    `Pause/Resume/ClearAll…`, `GetRecordingCopyTransferCapsAck`.
  - Push to client:
    `SendDownloadCommandToClient(MediaFile [, Mode])`,
    `SendDownloadCommandsToClient(MediaFiles, Mode)`.

## 3. Bandwidth-aware playback decision

Decides DIRECT_PLAY / REMUX / TRANSCODE per-stream using the client
profile and a real-time bandwidth measurement.

- `java/sage/client/PlaybackDecisionEngine.java` —
  `evaluate(profile, container, vCodec, aCodec, w, h, isHDx00,
  sourceKbps, availableKbps)` returns Decision + reason + target
  codec/container/bitrate hints + optional `preferredPlayer`.
- **BW telemetry** (`MiniClientSageRenderer`): `ACTIVE_BW_WINDOW_MS`
  active-window sampler (5×short / 20×long average, 50 kbps buffer)
  replaces the previous instantaneous estimate.
- **Bug fix vs upstream**: `MiniPlayer.java` source bitrate is now
  divided by 1000 — upstream passed bits/sec where kbps was expected,
  causing every otherwise-direct-playable file to be transcoded.
- **Knob**: `playback/bandwidth_safety_factor` (default `0.85`).

## 3b. Unified bandwidth model across direct-play AND transcoding

A single bandwidth model drives both the per-stream playback decision
(DIRECT_PLAY / REMUX / TRANSCODE) **and** the runtime transcode-bitrate
clamp. There is no parallel BW path for transcoding — both consult the
same value from `MiniClientSageRenderer.getEstimatedBandwidth()`.

- **Single decision authority** —
  `PlaybackDecisionEngine.evaluate(profile, container, vCodec, aCodec,
  w, h, isHDx00, sourceKbps, availableKbps, ...)` returns a
  `PlaybackDecision` whose `targetBitrateKbps` is the *same* number used
  to make the direct-play / transcode call (`budgetKbps =
  availableBwKbps × safety`, then clamped to
  `LiveTranscodeProfile.maxBitrateKbps`).
- **Unit-correctness fix** — `MiniPlayer.java` L994 divides
  `cf.getBitrate()` by 1000 (bits/sec → kbps). Without this, a 2 Mbps
  source was reported as `2129000 kbps` and *always* lost the BW
  check — every direct-playable file was being transcoded.
- **LAN vs WAN detection** —
  `MiniClientSageRenderer` does subnet-mask comparison of local vs
  remote IP at connect time (4-octet mask AND, ~L3808). Result is
  exposed via `isLocalConnection()` and `isLoopbackConnection()`. The
  `force_nonlocal_connection` pref overrides to false for testing.
- **LAN sentinel = 50 Mbps** — when the renderer's BW estimator
  returns 0 (no usable sample, common for non-low-bandwidth extenders
  on a LAN), `MiniPlayer.java` L951 sets `uiBandwidthEstimate =
  50_000_000` (50 Mbps) as "unmetered LAN" sentinel.
- **49 Mbps cutoff at engine boundary** — `MiniPlayer.java` L999
  treats `uiBandwidthEstimate ≥ 49_000_000` as the sentinel and passes
  `availableBwKbps = 0` (skip the BW gate, like the legacy 7-arg
  overload). Below 49 Mbps the value is a real measurement and the
  engine enforces the budget.
- **WAN clamp** — for non-LAN clients with measured BW between 2 Mbps
  (`miniplayer/min_bandwidth_for_no_transcode`, default) and 10 Mbps,
  `MiniPlayer.java` L941 forces the estimate down to
  `min_bandwidth_for_no_transcode − 1000` so a transcode is chosen
  rather than a fragile push. Gated by `miniplayer/wan_prevent_push`
  (default true).
- **FIXED-profile escape hatch closed** — legacy clients announcing
  `FIXED_PUSH_MEDIA_FORMAT` used to bypass the BW clamp. With
  `transcoder/adapt_fixed_to_bw=true` (default), the profile becomes a
  *ceiling* and the link estimate a *floor*; the UP-branch is gated at
  `currentVideoBitrate < 1500` so a fixed profile cannot be ratcheted
  up — only clamped down toward the link
  ([MiniPlayer.java#L1199-L1212](java/sage/MiniPlayer.java#L1199-L1212)).
- **Telemetry windows** —
  `MiniPlayer`: `NUM_SAMPLES_BANDWIDTH_ESTIMATE=5` (short),
  `NUM_SAMPLES_AVERAGE_BANDWIDTH_ESTIMATE=20` (long),
  `BANDWIDTH_BUFFER_KBPS=50` (headroom),
  `MIN_DYNAMIC_VIDEO_BITRATE_KBPS=50` (floor).
  `MiniClientSageRenderer`: `ACTIVE_BW_WINDOW_MS=1000`,
  `ACTIVE_BW_HISTORY_SIZE=60` (60 s rolling peak history).
- **Transcoder live clamp** —
  `FFMPEGTranscoder.ingestLiveBandwidthHintKbps` applies EWMA
  (`0.7·smoothed + 0.3·measured`) and hysteresis (≥2 deficit windows
  OR ≥3 headroom windows) before mutating the in-flight target — one
  noisy sample cannot whipsaw the encode bitrate.
- **NG client capability** — `BANDWIDTH_FEEDBACK_V1` (advertised via
  `SAGETV_NG_SERVER_CAPS`); newer clients post their own measurements
  to `SAGETV_NG_BANDWIDTH_FEEDBACK_V1` and the values are folded back
  through the same `addDataToBandwidthCalc` path (no parallel pipe).
- **Cross-session persistence** —
  `MiniPlayer.persistSessionBandwidthFromTranscoder()` writes the
  smoothed link estimate back to the renderer at end-of-stream / on
  seek so the *next* play's direct-play decision starts from a warm
  sample.
- **Knobs (full set)**: `playback/bandwidth_safety_factor` (0.85),
  `transcoder/adapt_fixed_to_bw` (true),
  `miniplayer/min_bandwidth_for_no_transcode` (2_000_000 bps),
  `miniplayer/wan_prevent_push` (true),
  `force_nonlocal_connection` (false; test override).

## 4. Client profile system

Per-client capability descriptor that drives the decision engine.

- `java/sage/client/ClientProfileManager.java`,
  `ClientProfile.java`, `LiveTranscodeProfile.java`.
- **Built-in profiles**: `hd_legacy_strict`, `desktop_default`,
  `desktop_hevc_optin`, `android_modern`, `android_legacy`,
  `pwa_safe`.
- **Profile fields**: containers, video/audio codec sets, allowHevc,
  autoRemux mode, max W/H, allowClientOverrides, liveTranscode
  (max/min/target kbps + scaling), preferPullContainers,
  avoidPushContainers.
- **`extends` inheritance** infrastructure (allows derived profiles).
- **Knob**: `transcoder/adapt_fixed_to_bw` — keeps BW-aware clamp
  active even for legacy clients announcing `FIXED_PUSH_MEDIA_FORMAT`.

## 5. Caption sidecar extraction

Auto-extracts EIA-608 / CEA-708 captions to `<basename>.srt` next to
the recording.

- `java/sage/api/CaptionsAPI.java`:
  `IsCaptionExtractionEnabled`, `SetCaptionExtractionEnabled`,
  `ExtractCaptions(MediaFile)`, `HasCaptionSidecar`,
  `ClearCaptionSidecar`, `BackfillCaptions(force)`.
- `java/sage/captions/CaptionExtractionManager.java` — singleton
  service, ffmpeg lavfi `subcc` filter, recording-stop hook,
  on-demand backfill.
- **Knobs**: `caption_extraction/{enabled, max_concurrent_jobs,
  run_on_recording_stop, post_recording_delay_ms, ffmpeg_path,
  extract_seconds, live_interval_ms}`.

## 6. Commercial detection / auto-skip

Profile-based commercial detection with EDL output and runtime
auto-skip during playback.

- `java/sage/api/CommercialSkipAPI.java`:
  detection (`RunCommercialDetect`, `HasCommercialMarkers`,
  `ClearCommercialMarkers`); segment access
  (`GetCommercialSegments`, `…Times`, `…Start/End`); engine config
  (`Get/SetComskipPath`, `Get/SetComskipIniPath`,
  `GetCommercialDetectEngine`); auto-skip toggles
  (`Is/SetAutoSkipEnabled`, `Get/SetAutoSkipDelayMs`);
  `IsComskipActive`.
- `java/sage/commercial/` — `CommercialDetectionJob`,
  `CommercialDetectionManager` (thread pool + lifecycle hooks),
  `EdlWriter`, `SkipMatrix` (per-channel/category skip filtering).
- **Knobs**: `commercial_detection/{enabled,
  run_on_recording_start, max_concurrent_jobs, engine,
  comskip_path, comskip_ini, post_recording_delay_ms}`.

## 7. AI upscale transcode (Real-ESRGAN, 2-phase)

Upscales ≤720p sources to ≥1080p using `realesrgan-ncnn-vulkan`,
then encodes with the standard preset.

- `java/sage/Ministry.java` — `shouldAutoAiUpscale(srcH,tgtH)`,
  `spawnAiUpscaleProcess`, `stripScaleFilterForPhase2`, intermediate
  `.mkv` builder.
- `java/sage/FFMPEGTranscodeJob.java` — phase-1 / phase-2
  orchestration.
- **Wrapper**: `bin/sage-ai-upscale.sh`. Default model
  `realesr-general-x4v3`.
- **Knobs**: `transcoder/ai_upscale_{enabled,
  max_source_height, min_target_height, wrapper, binary, model,
  chunk_frames, intermediate_dir}`.
- **New presets**: `UPSCALE_1440`, `UPSCALE_2160` (and the wider
  modernized catalogue: `PHONE_LOW/STD/HIGH_1080`, `TABLET_10_1080`,
  `TABLET_12_1440`, `TV_1080_COMPAT`, `TV_4K_HEVC`,
  `ARCHIVE_HEVC_MKV`, `DVD_LEGACY_MPEG2`).

## 8. Transcoder safety / pacing

Co-existence between transcode jobs and live recordings.

- `Ministry` per-tick gating: detects `anyDeviceRecording`; if so,
  holds queue at max 1 and SIGSTOPs running ffmpeg, otherwise
  SIGCONT and allows `max_concurrent_when_idle`.
- New methods on `TranscodeJob` / `FFMPEGTranscodeJob`:
  `pauseForRecording`, `resumeForRecording`,
  `isPausedForRecording` (avoids signature clash with the existing
  `void pauseTranscode()`).
- **CPU/IO shaping**: `xcode_nice_level` (`nice -n N`),
  `xcode_ionice_class` (`ionice -c CLASS`), `xcode_ffmpeg_loglevel`
  (default `info`).
- **Preset display labels**: `transcoder/format_labels/<NAME>`
  subtree + `TranscodeAPI.GetTranscodeFormatDisplayName`.
- **Preset path**: `Ministry.loadPresets()` reads
  `${STATE_DIR}/transcoder/presets/*.properties` first, falls back
  to `<CWD>/presets/transcoder/`. Old `scale_npp` presets rewritten
  to CPU `scale=…:flags=lanczos`.

## 9. AC-4 audio handling

- `java/sage/hdhr/AC4TranscodeJob.java` and
  `FFMPEGTranscoder.setAc4SourceAudioCodec()` — auto audio-only
  transcode (`vcodec=copy`, `acodec=eac3`) for AC-4 sources where
  the client lacks an AC-4 decoder. The `android_modern` profile
  audio set no longer claims AC-4 (was causing socket spin-loops on
  Shield/Onn/Galaxy Tab).

## 9b. Unified SageTV-patched FFmpeg binary (HEVC + AC-4)

A single FFmpeg build replaces the previous trio of side-by-side
binaries (legacy SageTV ffmpeg, modern AC-4-only ffmpeg, wrapper
script). It carries SageTV's C-code patches *and* AC-4 decode *and*
modern codec/HW support, all in one binary at
`/opt/sagetv/server/{ffmpeg,ffprobe}`.

- **Source / build** — `docker/build-sagetv-ffmpeg.sh` builds from
  `elliotclee/FFmpeg` fork commit `1dc7ff583b` (see header line:
  `ffmpeg version N-124561-g1dc7ff583b`). Design doc:
  `docs/FFMPEG_UNIFICATION_PLAN.md`.
- **SageTV-only C-code flags** (preserved from legacy build,
  visible in `-h full`):
  - `-stdinctrl` — accept control commands through stdin
    (`inactivefile`, `videorateadapt`)
  - `-activefile` — input is an active file still being written
    (enables follow mode)
  - `-dumpmetadata` — dump metadata in `META:key=value` lines to stderr
  - `-brokendts` — ignore broken DTS values in MPEG-TS streams
- **Codec / HW support** —
  - HEVC: `libx265`, `hevc_nvenc`, `hevc_v4l2m2m`
  - NVENC: `h264_nvenc`, `hevc_nvenc`, `av1_nvenc`
  - External libs: `libx264`, `libx265`, `libxvid`, `libfdk_aac`,
    `libmp3lame`, `libfreetype`
  - AC-4 decode: patched into libavcodec via the elliotclee fork
- **Replaces**: `build-modern-ffmpeg.sh`, `build-ac4-ffmpeg.sh`,
  `ffmpeg-wrapper.sh`. Old `/usr/local/bin/ffmpeg-ac4` is gone.
- **FFmpeg 6.x → 7.x CLI audit completed** — commits `4fa26838`,
  `1515b199`, `3e13d648`, `d0451217`. Changes: `-ab` → `-b:a` for
  audio bitrate; `yadif` updated; `-v 2` → `-v info`;
  `-async 50` → `-af aresample=async=50`; dropped removed
  `-directpred`. Touches `FFMPEGTranscoder`,
  `MediaFile.extractThumbnail`, `FormatParser.getFFMPEGFormatInfo`,
  `CaptionExtractionJob`, Ministry presets, `HwEncoder`,
  `AC4TranscodeJob`, `HTTPLSServer`.
- **HEVC + AC-4 push playback fix** — commit `42cba626`. MiniPlayer
  skips the legacy `DVDStream(0, 0xbd80)` private-PES primer on
  non-PS containers (Matroska / fragmented MP4). Fixed Galaxy Tab
  S9 FE spinning hang. `ClientProfileManager` no longer claims AC-4
  in the `android_modern` profile (forces the AC4TranscodeJob path).
- **AC-4 language-aware audio transcode** — commit `50109844`.
  `AC4TranscodeJob` uses ffprobe to find the matching language
  stream and emits `-map 0:a:N`. Knob
  `hdhr/ac4_transcode_audio_lang` (ISO-639) overrides; falls back
  to `default_audio_language`.
- **ATSC 3.0 HEVC + AC-4 capture chain** — commits `5ad71b14`,
  `c8e8b197` (lineup.json scan + DRM detect), `a4a32ca8` (HDHR
  HTTP-pull capture path), `788f70bc` (ffprobe-ac4 re-probe),
  `b6182006` (mirror manager scaffold).
- **Native parser track (planned, NOT done)** —
  `libMPEGParser.so` HEVC + AC-4 stream-type patches in
  commit `6c09aae3`; the deployed binary lags. ROADMAP "Native
  parser track" lists rebuild + native HEVC SPS parser + native
  AC-4 TOC parser to drop the `ffprobe-ac4` dependency entirely.
- **Default lookup path** —
  `FFMPEGTranscoder.getTranscoderPath()` returns
  `/opt/sagetv/server/ffmpeg`, so Java callers get the unified
  binary even if `Sage.properties` overrides are stale or missing.

## 10. ATSC 1.0 OTA EPG scanner (idle-tuner)

Background PSIP/EIT scanner that fills in subchannel guide gaps that
Schedules Direct misses.

- `java/sage/epg/ota/`: `Atsc1EITScanner`, `OtaEpgIngestor`,
  `PsipTables`, `TsSectionAssembler`, `HdhrControl`, `MssDecoder`.
- **Two cadences**:
  - 4 h coverage scan — skip RFs already covered ≥6 h ahead by SD.
  - 10 min sports refresh — fires only when sports overrun could
    impact a scheduled recording (Case 1a sports IS the recording;
    Case 1b sports precedes recording within `followon_window`).
- **Safety gates**: idle-tuner check, 5 min recording-lookahead,
  dual-tuner opt-in, dedicated-device override, per-hour budget
  (default 10 min/hr).
- **Knobs**: `epg/ota_scan_{enabled, device_id, device_ip, tuner,
  tuners, allow_dual_tuner, device_dedicated,
  recording_lookahead_ms, interval_ms, sports_interval_ms,
  sports_pre_end_lead_ms, sports_followon_window_ms,
  per_rf_duration_ms, min_lookahead_ms,
  global_budget_ms_per_hour, skip_rf, debug_rfs}`.

## 11. ATSC 3.0 mirror (placeholder)

`java/sage/epg/atsc3/Atsc3MirrorManager.java` — scaffolding for
future ESG-over-ROUTE handling.

## 12. HDHomeRun enhancements

- `java/sage/hdhr/HttpPullCaptureJob.java` — HTTP pull capture path.
- `java/sage/hdhr/HDHomeRunDiscover.java`, `HDHomeRunLineup.java`,
  `ChannelVariantAttacher.java` — discovery and FLEX 4K variant
  handling.

## 13. SMB2 / SMB3 browse with auth

- Dependency `org.codelibs:jcifs:2.1.37` replacing legacy
  JCIFS 1.1.6.
- `java/sage/Seeker.java`, `java/sage/MetaImage.java` —
  `SMB_CACHE_TTL_MS`, `normalizeSmbPath`, auth-aware browsing.
- **Knobs**: `smbBrowseUser`, `smbBrowsePass`, `smbBrowseDomain`.

## 14. JUPnP UPnP stack

- Dependencies `org.jupnp:org.jupnp:3.0.3` +
  `org.jupnp:org.jupnp.support:3.0.3` replacing SBBI
  `sbbi-upnplib-1.0.3`.
- `java/sage/upnp/PlaceshifterNATManager.java` — port-mapping
  manager rewritten on JUPnP.

## 15. Logging modernization (opt-in)

- `java/sage/SageLogBridge.java` — `System.out`/`err` captured and
  routed through SLF4J / Logback.
- Dependencies: `slf4j-api:2.0.16`, `logback-classic:1.5.13`,
  `logback-core:1.5.13`. `installer/conf/logback.xml` bundled as
  classpath resource.
- **Gate**: `logging/use_slf4j=true` (default `false` — old
  behaviour preserved).
- Operational note (NOT in source): the deployed jar carries a
  bytecode patch to `JettyInstance.class` that disables Jetty's
  `DebugListener` ERROR-spam; see `tmp/` deploy notes.

## 16. Timezone handling

- `java/sage/TimeZoneParse.java` (+ test) — POSIX TZ-string parser
  (e.g. `EST5EDT,M3.2.0/2,M11.1.0/2`).
- `Sage.java` — `resolveConfiguredTimeZone` /
  `applyRuntimeTimeZone` flow reads the `time_zone` pref before any
  `TimeZone.setDefault()` call.

## 17. MiniPlayer / transport hardening

- BW estimator constants: `NUM_SAMPLES_BANDWIDTH_ESTIMATE=5`,
  `NUM_SAMPLES_AVERAGE_BANDWIDTH_ESTIMATE=20`,
  `MIN_DYNAMIC_VIDEO_BITRATE_KBPS=50`, `BANDWIDTH_BUFFER_KBPS=50`.
- `MiniClientSageRenderer.useReconnectSocket(...)` closes the prior
  `clientSocket` before swapping; `MiniUIClientReceiver.setNewStream(...)`
  closes the prior receiver channel.
- `MiniClientSageRenderer.connectionError()` immediately tears down
  `zout` + `clientSocket` to drain port-31099 `CLOSE_WAIT` buildup
  before reconnect.

## 18. STV (`SageTV7.xml`) UI additions

- **`NGDLQ-*`** (~215 symbols, max 1755) — Download Recording menu,
  Download Queue Management screen, per-MediaFile Pause / Resume /
  Cancel, Queue Snapshot screen with raw-JSON drill-down, transfer
  status JSON viewer.
- **`CAP-*`** (29 symbols) — Caption sidecar UI: enable toggle on
  the settings panel, Backfill button, Extract / Delete-Captions
  per-MediaFile actions.

## 19. Build / versioning infra

- `java/sage/Version.java` — `MAJOR=10  MINOR=0  MICRO=16`.
- `java/sage/SageConstants.java` — `BUILD_VERSION=1167` (the
  authoritative drift check; bumped on every java-touching commit).
- Gradle 8.8 / Java 21 (`--release 21`); `sageJar` task with
  `copyRuntimeJars` exporting Maven jars to `build/runtime-jars/`.
- Root docs: this file, `CHANGELOG.md`, `ROADMAP.md`,
  `SageTVPluginsDev.md`, `REPOSITORIES.md`, `BUILDING.md`.

---

## How to refresh this inventory

1. Add or remove the relevant section above.
2. Bump the date and `BUILD_VERSION` line at the top.
3. Cross-reference the change with `CHANGELOG.md` (chronological)
   and `ROADMAP.md` (planned work) so the three files stay
   consistent.
