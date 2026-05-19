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

- **Unify on a single SageTV-patched, AC-4-capable FFmpeg binary.**
  Replace the current 2010 SageTV-patched ffmpeg + missing-from-this-
  container ffmpeg-ac4 + wrapper-script stack with one binary at
  `/opt/sagetv/server/ffmpeg` that has all four SageTV custom flags
  (`-stdinctrl`, `-activefile`, `-dumpmetadata`, `-brokendts`), AC-4
  decode, NVENC, libx265, libfdk-aac. Full design in
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

## Playback track

- **Per-airing audio language UI selector.** Server-side language-aware
  audio mapping is done (see Done section: `AC4TranscodeJob` honors
  `default_audio_language` / `hdhr/ac4_transcode_audio_lang`). Still
  needed: a click-through UI in the STV that lets the user override
  the language per show without changing the global default.
- **CTA-708 captions in HEVC SEI.** Accessibility win; needs SEI
  extraction or pass-through to client.

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
- **SBBI UPnP → JUPnP.** SBBI is 2005-era and has known IPv6 /
  multi-NIC bugs. JUPnP is maintained, OpenHAB-backed. Fixes DLNA on
  multi-homed servers (relevant to dual-subnet host setups).
  *Plugin compat:* internal; no plugin exposes SBBI types.
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

## Done

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
