# Change Log

## Next
* `build/deploy_jar_ng.sh` no longer destroys the server it deploys to. It ran
  `rm -f JARs/*.jar` before copying the staged set, which is only safe if
  staging is a superset of the live directory — and it isn't: the live server
  carries ~88 jars (Jetty, sagex-api, phoenix, plugins) where a clean gradle
  build emits ~26, so the script silently removed the web UI and every plugin.
  It now overlays by default and enumerates the difference first; pruning is
  still available behind `PRUNE_JARS=1`, and refuses to run when staging
  doesn't cover the live set. It also SIGTERMed the server without asking what
  was running, so a routine deploy could kill an in-progress recording or drop
  every viewer mid-stream; there is now a hard idle gate (no ffmpeg processes,
  no recording-shaped writes in the last two minutes) with an explicit
  `ALLOW_BUSY=1` override. Two checks were reporting the opposite of the truth:
  the `jdeps --missing-deps` preflight swallowed its own failure and printed a
  confident all-clear on a classpath it had never managed to read, and the
  post-deploy port check used `ss`, which returns an empty table inside this
  container and so warned "ports not listening" on every healthy deploy. Both
  now report unverified-vs-clean honestly, with the port check on `/dev/tcp`.
  The container name is now taken from `$CONTAINER` and the script aborts with a
  clear message when the named container doesn't exist, rather than defaulting to
  a name that happens to suit one deployment.
* Removed the `SUPPORTS_4K` capability field. It was never part of the client
  contract — the Android client sends no such property, so the query always came
  back empty and every branch reading it was unreachable. The real contract
  answers the two questions on two separate channels, and the distinction
  matters: *"can I decode 4K?"* is hardware fact, reported per codec in
  `EXO_VIDEO_CODECS`/`IJK_VIDEO_CODECS` and sent unconditionally; *"should you
  upscale, and up to what?"* is the user's Auto/Always/Never setting, carried
  entirely by whether and when the client populates `DISPLAY_SINK_RESOLUTION`.
  Never sends an empty sink, Always sends the honest physical panel
  unconditionally, Auto sends it only when the client judges itself eligible —
  and the value is always the true panel, never a fabricated 4K. That split is
  better than the field it replaces, because it keeps a user-facing toggle from
  being able to talk the server past a real decoder limit. Auto and Always are
  deliberately indistinguishable on the wire, so the server applies one rule: a
  sink that arrives at all is a request to upscale, up to that size. The
  docked-phone case that motivated the invented field turns out to need no
  server support at all — Android reports the *television's* geometry when a
  phone drives one, so it already resolves client-side. The dry-run log now
  records `sinkKind=none|builtin|external` in place of `uhd=`.
* Enhancement now answers to the **network gate**, and this is not a live-TV
  concern: the same delivery machinery carries recorded files, and
  `PlaybackDecisionEngine` already forces a transcode-down when a *recording's*
  bitrate exceeds the measured link. Enhancement raises bitrate, so leaving it
  outside that budget meant spending headroom the ranking had already
  accounted for — the advisor ran after the bandwidth-aware decision and
  nothing re-checked it. It now receives the same `sourceBitrateKbps` and
  `availableBwKbps` that ranking used, projects a per-tier bitrate via
  `GpuEnhancePipeline.suggestBitrateKbps()`, and requires it to fit
  `link × safety factor`. The check sits inside the tier ladder beside the
  decode gate, so a constrained link degrades 2160p → 1440p → 1080p rather than
  refusing outright. Deinterlace is exempt, because it emits roughly the stream
  the client was already being sent and the existing rate machinery has already
  sized it. An unmeasured link (`0`) imposes no cap — "not measured" is not
  "measured as zero", the same abstention rule as the sink, and NG direct-play
  deliberately skips the probe. Inherits `playback/bandwidth_safety_factor`
  (0.85) so a tuned link isn't tuned twice;
  `playback/gpu_enhance/bandwidth_safety_factor` overrides it for enhancement
  alone. New verdict `INSUFFICIENT_BANDWIDTH`; the log gains `srcKbps=` and
  `linkKbps=<measured>/<after safety>`. Mid-stream degradation remains open.
* An absent `DISPLAY_SINK_RESOLUTION` is now read as an **abstention rather than
  a refusal**, and the server decides. The previous reading came from the Android
  client expressing its "Never" setting by clearing the sink, which conflated
  what a client *sends* with what it *wants*: an empty value is equally what
  "I can't measure my panel", "my own eligibility heuristic said no" and "I
  predate this spec" look like. Treating all four as a veto let the least
  informative thing a client can do decide the outcome, and handed the decision
  to the least informed party in the exchange. Now the panel clamp is simply
  skipped and the tier falls through to rules that are each independently
  fail-closed — the per-codec decode ceilings above all, which the client must
  have affirmatively declared. Nothing is fabricated, so a client that declared
  nothing still gets nothing, and every legacy client lands exactly where it did
  before, protected by the decode gate rather than by the sink check. What is
  lost without a sink is only the panel ceiling, so the worst case is bandwidth
  spent upscaling for a smaller panel, never an unplayable stream. The log
  reports `sinkKind=inferred` for this case, and
  `playback/gpu_enhance/unknown_sink=refuse` restores the old behaviour. Note
  the consequence for clients: **withholding the sink no longer avoids
  enhancement, it only removes the ceiling** — the honest value is now in the
  client's own interest, and a client that genuinely wants less should say so via
  `QUALITY_HINT=savings`, which is an existing preference field that neither side
  currently wires up.
* Enhancement can now be restricted by device form factor. A GPU session spent
  upscaling for a 14.6" tablet held at arm's length buys far less than the same
  session spent on a 65" panel across the room, and both draw on the same
  encoder — but how much less depends on the room, so
  `playback/gpu_enhance/upscale_form_factors` ships empty (every device
  eligible) and the dry-run log now records `DEVICE_FORM_FACTOR` so the question
  can be settled from real traffic rather than a guess baked into the default.
  `DEVICE_FORM_FACTOR` was already collected but had never been consulted.
  Excluded devices still receive deinterlacing, which is cheap and helps a
  handheld as much as a television, and a client that never reported a form
  factor is never excluded — this is a judgement about where the GPU is best
  spent, not a safety gate.
* Server Video Enhancement now accepts a client's **per-codec** decode ceilings
  as proof it can play an enhanced stream, not just the per-surface
  `MAX_OUTPUT_*` fields. Android `MediaCodec` and the browser's
  `MediaCapabilities` both report decoder limits per codec rather than per
  "surface", so the per-surface form asked client teams for a shape their
  platform does not have. `maxW`/`maxH`/`maxFps`/`maxBitrate` are now parsed
  from the `EXO_`/`IJK_VIDEO_CONSTRAINTS` rows, and from `*_VIDEO_CODECS` too
  when that property carries attributes, so whichever channel a client
  populates is read. A codec qualifies only when it declares `decoder=hw` and a
  geometry ceiling at or above the target: software decode cannot sustain 4K in
  real time, and an unstated decoder type is refused rather than assumed. The
  two sources are OR'd, since they describe the same decoder, and the gate
  consults the player that will actually decode this stream rather than the
  client's default. Fixed while adding this: the same gate treated an
  unidentifiable playback surface as permission to proceed, so a client that
  had declared nothing at all could be handed 4K; with no surface and no codec
  ceilings the answer is now no. Enhancement targets are also clamped to the
  reported panel in **both** dimensions, so a 2960x1848 tablet is offered a
  1440p upscale that fits rather than a 2160p stream it would only spend power
  downscaling. Deinterlacing remains exempt from the decode gate — it emits the
  geometry the client was already decoding.
* HEVC recordings no longer lose their resolution. A 4K ATSC 3.0 recording was
  stored as `Video[HEVC progressive id=0100] 0 kbps` — codec and PID but no
  resolution, frame rate, aspect ratio or bitrate — while `ffprobe` on the same
  file reported `hevc Main 3840x2160`. Two defects had to line up. The internal
  MPEG parser reads the PMT for codec and PID but only derives geometry from
  sequence headers it knows, and it does not walk the HEVC SPS; since
  `getFileFormat()` returns the internal result as soon as it has any stream,
  ffmpeg was never consulted. It now backfills missing video geometry from the
  external probe, filling only fields still unset so the native parse wins where
  it spoke (`format_detect_ffmpeg_geometry_fallback`, default on). That alone
  fixed nothing, though: modern ffmpeg folds the aspect ratio into the
  resolution field as `1920x1080 [SAR 1:1 DAR 16:9]`, where this parser expected
  them separate, so `parseSeparatedInts` threw and width, height *and* display
  aspect ratio were dropped without a word — for every codec, not just HEVC.
  Existing recordings heal themselves too: a stored format carrying a video
  stream with no resolution is repaired in place on first access, filling only
  the unset fields so PIDs and audio tracks are preserved. That repair runs at
  most once per file per server lifetime and never against an in-progress
  recording, live stream or downloading file, so it cannot compete with capture
  for disk (`format_detect_repair_stored_geometry`, default on).
* Server Video Enhancement, phase 0 (foundations only — no playback behavior
  change): `sage.enhance` package with the tier vocabulary and 720-line source
  floor, a `nvidia-smi`-backed `GpuMonitor`, the `GpuGovernor` admission
  cascade (recording veto → free VRAM → video-engine pressure → disk-write
  budget → calibrated concurrency), a `CapacityCalibrator` that measures the
  per-host concurrency ceiling instead of hardcoding a GPU-model table, the
  shared `GpuEnhancePipeline` ffmpeg token builder, and outcome telemetry with
  a demote-fast/promote-slow feedback loop. Nothing is wired into the live
  playback paths yet; the feature is off by default.
* `HwEncoder` gained a cached ffmpeg filter probe (`yadif_cuda`, `bwdif_cuda`,
  `scale_npp`, `scale_cuda`) so the CUDA scaler and deinterlacer are detected
  rather than assumed, plus a *functional* enhancement probe that runs a
  fractional-second encode instead of trusting `ffmpeg -encoders`. The listing
  probe alone is not sufficient: an ffmpeg built against a newer NVENC SDK than
  the installed driver supports still advertises `hevc_nvenc` and then fails at
  encoder open ("Driver does not support the required nvenc API version"),
  which would have admitted enhanced sessions on a host where every one of them
  dies at stream start (`multimedia/hwaccel/enhance_runtime_probe`, default on).
* That functional probe originally synthesized its source with `-f lavfi -i
  color=...`, which turned out to be unusable on the hosts it matters most for:
  SageTV's bundled ffmpeg is built `--disable-devices`, so it has no
  libavdevice, `ffmpeg -devices` prints an empty list, and `-f lavfi` fails with
  "Unknown input format: 'lavfi'". Because the probe is deliberately
  fail-closed, that meant enhancement would have been silently disabled forever
  on a working host, explained only by a debug line. Measured on the dev server
  (RTX 5080, driver 595.84): the old probe exited 234 while the real pipeline
  ran a recording through `yadif_cuda` + `scale_npp` + `hevc_nvenc` and produced
  genuine 3840x2160 HEVC at 59.94fps. The probe now feeds raw yuv420p frames on
  ffmpeg's stdin, which needs no input device and behaves identically on every
  build and platform; both the `scale_npp` and `scale_cuda` variants now pass on
  that host.
* Live transcode teardown now escalates to SIGKILL. `stopTranscode()` sent a
  single `destroy()` (SIGTERM), never waited for the child to die, and — worse —
  unregistered it from the shutdown reaper *before* signalling, then dropped the
  JVM's last handle to it. A child that ignores SIGTERM therefore survived as an
  orphan that was both invisible to the shutdown hook and unkillable from Java.
  This was not theoretical: the dev server had two such processes, one alive
  2d16h holding 421 MiB of VRAM and a file handle to a deleted recording. One
  exited on a manual SIGTERM (nothing had ever signalled it again); the other
  required SIGKILL. Reaping both returned 879 MiB of VRAM. Teardown now sends
  SIGTERM to the child and its descendants, waits
  `media_server/transcode_kill_grace_ms` (default 1500), escalates to
  `destroyForcibly()`, confirms death, and unregisters only once confirmed.
* Windows transcoder priority parity: `xcode_reduce_process_priority` has always
  defaulted to true, but the `nice`/`ionice` wrap that implements it is
  POSIX-only, so Windows hosts were silently running transcodes at normal
  priority against in-progress recordings. New `ProcessPriority` helper applies
  the equivalent by PID after the child starts (`xcode_windows_priority_class`,
  default `BelowNormal`).
* Added [docs/NGServerVideoEnhancement.md](docs/NGServerVideoEnhancement.md) —
  the client protocol contract for server-side enhancement, including
  `DISPLAY_SINK_RESOLUTION`, which is what finally lets the server tell a Shield
  on a 4K TV apart from a 1080p device.
* Server Video Enhancement, phase 1 (decision logic, observe-only): the NG
  capability round now queries `DISPLAY_SINK_RESOLUTION`, `DISPLAY_REFRESH_RATES`,
  `DISPLAY_HDR_TYPES`, `LOCAL_ENHANCEMENT` and `QUALITY_HINT`; `PlaybackSurface`
  gained the output-limit dimension (`MAX_OUTPUT_WIDTH/_HEIGHT/_MAX_FPS`,
  optional array indices 9-11) so a surface must prove it can decode 4K rather
  than merely listing HEVC. New `EnhancementAdvisor` decides whether enhancement
  would actually help — it defers to a client whose own upscaler is active,
  requires a 1.5x size gain before re-encoding, enforces the 720-line source
  floor on HEIGHT (so 720x480 DVD is excluded, since its 720 is the width), and
  downgrades rather than refusing when a decoder ceiling is lower than the panel.
  The decision is wired into the per-tune surface path as a post-rank treatment,
  next to the existing server-EQ promotion, and gated behind a second switch:
  `playback/gpu_enhance/dry_run` defaults true, so enabling the feature logs
  decisions against real traffic without re-encoding anything.
* `CAP_EFFECTIVE_DELIVERY` gained an optional enhancement suffix
  (`pull-xcode:dynamich264:enhance;tier=2160p`). Purely additive — with no active
  tier the token is byte-identical to before.
* Added [FORK_INVENTORY.md](FORK_INVENTORY.md) — reference catalogue
  of capabilities the fork adds vs upstream `google/sagetv` (NG
  protocol, recording-copy transfer queue, BW-aware playback,
  client profiles, captions, comskip, AI upscale, transcoder
  safety, ATSC1 EIT, HDHR HTTP-pull, SMB2/3, JUPnP, SLF4J bridge,
  TZ parser, MiniPlayer hardening, STV UI symbols).

## Version 10.0.16 (2026-04-28)
* Version bump to 10.0.16 (fork versioning: galeforcesage/SageTV-NG)
* Java 21 compatibility (finalize suppression, module access)
* Commercial detection system with profile-based INI resolution
* Disk I/O Phase 1: sidecar file infrastructure, skip matrix, crypto vectorization
* Properties save debounce, MediaServer zero-copy on 64-bit Linux
* Sidecar cleanup on recording deletion (fixes orphaned .edl/.vprj/.csv)

## Version 9.2.16 (2025-11-20)
* Fix for lockouts with SD EPG images bring requested with wrong agent and missing token
* Further fixes to send 14 character program ids
* Add SD healthcheck and add system alert if user is blocked

## Version 9.2.15 (2025-09-16)
* Fix for lockouts with SD EPG including better handling when run as service and using clients
* Ensure SD is sent 14 character program IDs

## Version 9.2.14 (2025-06-16)
* Fix daily notification of new version.  Should only notify once when new version is available

## Version 9.2.13 (2025-05-23)
* More fixes for placeshifter on windows (added consolewin registry optional logging to match client)

## Version 9.2.12 (2025-05-18)
* Fix for placeshifter on windows (also added optional logging)

## Version 9.2.11 (2025-05-15)
* SD EPG changes to correct non-syncronized method causing use of old tokens

## Version 9.2.10 (2025-04-04)
* SD EPG changes to correct Error 6000, 4009 and some other login issues with SD

## Version 9.2.9 (2025-03-11)
* Updated gradle script so that project could build in Netbeans
* Updated the FFMPEGTranscoder to fallback to frame count instead of time to calculate progress
* Allow IR blasters that support it to xmit non-numeric Tune strings (eg 42-1).
* SD EPG changes to correct image retrieval without token and other API corrections/updates
* SD EPG added debug_sd_support property to enable extra debug info when contacting SD support
* SD EPG added sdepg_core/bypassCelebrityImages to allow users in the future to bypass reteiving Celebrity images from SD if causing issues
* SD EPG added sdepg_core/bypassProgramImages to allow users in the future to bypass retrieving Program images from SD if causing issues
* SD EPG added sdepg_core/bypassEPGUpdates to allow users in the future to bypass retrieving EPG from SD if causing issues
* SD EPG added wizard/scheduled_maintenance and wizard/scheduled_maintenance_offset to allow users to set the hour that the daily maintenance will run
* SD EPG added code to support SD now passing back the current token along with its expiration
* SD EPG fix for send SD empty program lists as well as malformed endpoint for metadata/program
* SD EPG fix enpoint call for metadata/programs to use 14 character programID rather than shortended to 10
* Added seeker/duration_for_watchdog property to handle long running watchdog process for larger libraries (defaults to 60000)
* Windows installer build notes updated for location of missing files needed for the build
* Added ability to notify user if a new version is available on Github (defaults to enabled but can be disabled)

## Version 9.2.8 (2022-01-05)
* Update to build process to support Linux build on Ubuntu 18.04 and JDK 11
* removed Travis process as no longer used for builds

## Version 9.2.7 (2022-01-04)
* Update to build process to support build on JDK 11 while supporting Java 8 dockers to run SageTV
* Added DirecTVTuner DLL for http tuning (Windows)

## Version 9.2.6 (2021-09-13)
* Updated weather in STV to use OpenWeatherMap
* Added option to FFMPEGTranscoder to allow for a setting to copy video or audio
* Added a new option to the Miniclient for fixed remux profile.  This is used when the audio/video codec are supported, but the container is not
* Added some additional constraints on -aspect switch in FFMPEGTranscoder to make sure an invalid aspect ratio is not passed to the transcoder

## Version 9.2.5 (2021-05-24)
* Fixed 32-bit installer incorrectly removing uu_irsage.dll which broke USB-UIRT (Windows)
* Visual Studio launcher project cleanup (Windows)
* Added Detailed Setup -> Customize option to disable display of thumbnails/artwork for shows.
* Added Detailed Setup -> Customize option to disable display of channel logos.

## Version 9.2.4 (2021-04-16)
* Fixed database clearing of non-manual Wasted objects that were over a year old (comment indicated it happened, but was never implemented before)
* Fixed crash on extenders when loading 4K images that use diffused textures

## Version 9.2.3 (not released)
* Added ability to use fixed push format when transcoding is required, but not low bandwidth
* Added 720, 1080 and SOURCE (use video source resolution) options to FFMPEGTranscoder for fixed transcoding
* Added SOURCE option to FPS that calculates GOP automatically and uses FPS of source videos
* Added an option for Audio Channels.  Does not allow a value greater than source audio
* Fix for conversion to MKV. Removed Format substitution of "MATROSKA" -> "MATROSKA,WEBM"

## Version 9.2.2 (2020-05-16)
* Fixed MpegDeMux that crashed some MPEG2 playback (Windows)
* Change service launcher (Windows) to support local JRE
* Change watch ignore times from constants to properties
* Tidy up warnings in VS2015 for data type conversions (Windows)
* Fix for Hauppauge 885 tuners with Alt TS Capture Devices (Windows)
* Change maximum number of BDA tuners from 2 to 4 (HVR-5525 has 3 BDA tuners)
* Fix DirecTVSerialControl
* Fix 64-bit service launcher (Windows)
* Set default 1G heap for 64-bit (Windows)
* Updates for OPTUS D1 transponder changes to DVB-S2
* Fix: Schedules Direct EPG grabber failed to finish updating some satellite-based lineups
* Added Forced as a property to SubpictureFormat
* Added the ability to auto select forced subtitle track based on the default audio language
* Added the ability to use a Plugin for format detection of media files instead of built in ffmpeg.
* Added 2160p as a Pretty resolution to VideoFormat
* Added HEVC as a supported media format

## Version 9.2.1 (2019-03-23)
* 64-bit AVI playback and music fixes (Windows)
* Change: Allowlist LAV Audio and Video Decoders (Windows)
* Change: SageTV7 STV system information will indicate 32/64 bit
* Fix: Include Win10 in 'VISTA_OS' detection (Windows)
* Change: New installation properties default video/dvd_video renderer is 'EVR'
* Change: New installation properties default video/audio decoder are auto-detected
* New: Add EXEMultiTunerPlugin, HCWIRBlaster & USB-UUIRT VS projects (Windows)
* 64-bit code and VS project updates (Windows)
* Enabled MSYS2/MinGW compile for FFMPEG-based projects (Windows)
* Fix: IR interface hangs trying to send non-numeric (eg: 42-1-1) command.
* Removed dependency on SDK6.1 (Windows)
* Fix: Sage-x64 hang due to CableCARD tuners (Windows)
* Fix: Add support for HVR-4400 and other 885 variants (Windows)

## Version 9.1.10 (2018-10-13)
* removed old bytes properties for episodeName and desc to resolve potential crashes

## Version 9.1.9 (2018-05-18)
* Byte based seeking support for MPEG files
* update libhdhomerun to 20170930
* Fixed bug where file modification time can get set incorrectly

## Version 9.1.8 (2017-11-13) - windows only
* HD-PVR2 video capture device: add ability to select multiple audio inputs (Windows)
* HD PVR 60 video capture device: new device support (Windows)

## Version 9.1.7 (2017-09-24)
* Fix: add support for 2nd tuner of Hauppauge WinTV-dualHD usb tuner stick (Windows).
* Changes in the STV set 2017081201 for the next SageTV release v9.1.7.0:
    * malore menus: Removed random misc adjectives after show titles; only display misc textafter the title if it is a star rating.
	* Removed Zap2it logo from System Information.
	* EPG Lineup configuration: Changed help text above option buttons, put Schedules Direct option at top of list, old built-in EPG option renamed as plugin option and moved down.
	* Fixed Music by Artist filtering issue resulting in 0 songs per artist after entering 2nd and subsequent chars.
	* Disabled access to YouTube, Google videos, and channels.com.
	* Detailed Setup -> General: reworded the Sync System Clock option.
	* Detailed Setup -> Advanced: removed Debug Logging enable/disable option because it is always enabled now.
	* Configuration Wizard playback testing/configuration menu uses the "Default" decoder settings instead of SageTV MPEG decoders.
	* Detailed Setup -> Customize: renamed extra option to mark channels in guide with non-Zap2it channel IDs to refer to non-Tribune IDs.
	* Changed Zap2it text to Tribune elsewhere in the STV, since the EPG data fo the old built-in and new SD EPG data both ultimately come from Tribune.  
* Fix: resolved 'Grey-scale channel logos are green and half-width' for Windows releases (was fixed for linux in 9.0.8.423 and newer)

## Version 9.1.6 (2017-08-10)
* Fix: Various fixes and cleanup on Linux Firewire and DVB.
* Fix: Added support for all 4 tuners on the Hauppauge WinTV-quadHD tuner in Windows.
* New: Add Schedules Direct lineup by ID.
* Change: Removed ZZZ from Schedules Direct Regions because it doesn't do anything.
* Fix: VOB and MP4 subtitles locking methods were not being called.
* Fix: Fixes to HDHomeRun (and probably others) ATSC Scanning returning blank and garbled channels.
* Fix: Reduced Schedules Direct person image import threads to 4 (including the execution thread) and added logging for when new threads are created for during the process.
* Change: Removed unhelpful alias to original person log entries.
* Fix: Fixed issue with Schedules Direct forcing a full airing re-import on stations that do not have a No Data airing.
* Change: Lowered the priority of the Schedules Direct person image import threads.
* Fix: Removed use of G1GC in Windows due to possible memory leak issues.

## Version 9.1.5 (2017-06-19)
* Fix: Carny throws a null pointer exception if a show has a null title.

## Version 9.1.4 (2017-06-11)
* Fix: Schedules Direct deleted lineups were not removed from accounts correctly.
* Fix: When checking for existing lineups and a deleted lineup exists, a null pointer exception was thrown.
* Change: The SRT subtitle monitoring thread now uses Pooler.
* Fix: Index out of bounds exception while getting recommendations from Schedules Direct.

## Version 9.1.3 (2017-05-30)
* Fix: A missing space in an if test causes the Linux start script to fail.

## Version 9.1.2 (2017-05-30)
* Fix: Changed awk parsing to use sed to clean up the Java version check.
* Fix: API methods GetFavoriteAirings() and GetPotentialFavoriteAirings() were returning all airings for keyword favorites.
* New: Increased possible range for scheduling lookahead to 21 days. The default is still 14 days.
* Fix: Removed check in Scheduler that was preventing a future airing beyond lookahead from being considered to resolve a conflict.
* Fix: Fixed Carny not being marked prepped on startup when no agents exist.

## Version 9.1.1 (2017-05-22)
* Fix: Fixed a problem with awk parsing in Ubuntu

## Version 9.1.0 (2017-05-22)
* Fix: Transcoder crashing on Linux with signal 11.
* New: Added new API method to get enabled and disabled favorites.
    * public Airing[] GetPotentialFavoriteAirings(Favorite Favorite);
* Fix: Aliases without a non-alias would cause an NPE when searching.
* Fix: Schedules Direct aliasing logic was applied backwards.
* New: Carny is now multi-threaded and highly optimized.
* New: Schedules Direct movie length is now imported.
* New: Schedules Direct alternative channel logos can now be used by changing the property sdepg_core/use_alternate_logos=false to true.
    * This can also be changed in the UI via Setup > Detailed Setup > Customize > Use Alternative Schedules Direct Channel Logos.
* New: Enabled G1GC String deduplication for Java versions 8 and 9.

## Version 9.0.14 (2017-03-18)
* New: Added new API methods for in progress sports tracking using Schedules Direct.
  * public boolean IsSDEPGServiceAvailable();
  * public boolean[] IsSDEPGInProgressSport(String[] ExternalIDs);
  * public int[] GetSDEPGInProgressSportStatus(String[] ExternalIDs);
* New: Added editorials based on recommendations from Schedules Direct.
* Fix: Radio stations in Schedules Direct guide data now retain their prepended zeros in the guide data.
* Fix: Teams from Schedules Direct were being skipped because they do not have a person ID.

## Version 9.0.13 (2017-01-19)
* Fix: Schedules Direct was unable to distinguish between two lineups with the exact same name.
* Fix: Added handling for an unknown regular expression Schedules Direct was providing for the postal code for a few countries. The code also now skips the check if it does not recognize the regex formatting.
* Fix: Added better handling to Seeker when starting a recording and no directories are selectable for the desired encoder.
* Force debug logging to always be on.
* Fix: Watched calculation for movies with commercials is improved
* Fix: Prevent freezing between programs when playing back on Windows (matches V7 behavior, although not ideal, avoids freezing)
* New: Added more roles for Person objects.
* New: Schedules Direct Person images are now imported.
* New: Schedules Direct movie quality ratings are now a part of the bonus data.
* Fix: Schedules Direct movie images are now prioritized to use box art first.
* Fix: Schedules Direct now updates channels with No Data with previously saved hashes that happen to still be valid.
* Fix: Startup now explicitly adds lucene-core-3.6.0.jar before loading the JARs folder to address a common upgrade issue.

## Version 9.0.12 (2016-12-22)
* New: Schedules Direct now includes teams as people for favorite scheduling.
* New: SageTV server will no longer allow the server to go to sleep until video conversions are complete.
* New: Updated DVB-S & DVB-T frequencies for New Zealand
* New: Add STV support for enabling and disabling favorites
* Fix: Schedules Direct was not returning the saved country in some cases.
* Fix: Removed asterisks from password field when entering the password for Schedules Direct.
* Fix: Fixed so that Ministry will not allow sleep while converting.
* Fix: Allow mounting DVD iso images as non-root
* Linux Placeshifter: Added AC3 support

## Version 9.0.11 (2016-11-20)
* Fix: Enable streams with valid PAT packets and invalid PMT packets to be able to be detected by the built in remuxer.
* Fix: Linux tries a few more adapters when trying to get the primary server IP address.

## Version 9.0.9 (2016-10-10)
* Fix: GetSeriesID wasn't always returning a valid series ID
* New: Added logic to Schedules Direct program categories to ensure Movie is the first category for programs that start with MV
* Fix: Cleaned up the logic for determining when images from Schedules Direct should be in a Show or SeriesInfo object
* Fix: Clarified in logging when we can't process anything currently because Schedules Direct is offline
* Fix: Added random timeout when Schedules Direct token expires before getting a new token in case there are multiple SageTV servers using the same account

## Version 9.0.8.429 (2016-09-27)
* Fix: Fixed plugin bug that caused some upgraded plugins to be in a corrupted state
* Fix: Fixed bug in the EPG license detection logic

## Version 9.0.8 (2016-09-22)
* New: Added Schedules Direct EPG support as a core BETA feature

## Version 9.0.7 (2016-08-10)
* New: Added SageTVPluginsDev.d directory support (See [SageTVPluginsDev README](SageTVPluginsDev.md))
* New: Added direct JAR linking in SageTV Plugin Manifest (ie, no need to repackage library plugins as .zip files)


#### Notes about incrementing versions for developers:

* If you are the first to commit changes after a release, ensure that the following have been incremented beyond the last release:
    * MICRO_VERSION in sage/Version.java
* If you make any changes to stvs/SageTV7/SageTV7.xml, ensure that the following are updated in the STV:
    * AddGlobalContext( "STVversionText", "August 12, 2017" )
        * This should match the date of the commit.
    * AddGlobalContext( "ThisSTVSetVersionNum", "2017081201" )
        * This should match the date of the commit and if there was more than one commit the same day, the last two digits should be incremented.
        * The format is YYYYMMDDVV.
        * YYYY is the year.
        * MM if the month number.
        * DD is the day of the month.
        * VV is the commit version for this date. This resets to 01 if the date changes.
    * STVVersion [="9.1.7.0"]
        * This should start with MAJOR_VERSION.MINOR_VERSION.MICRO_VERSION in sage/Version.java
        * The last number should be incremented for each update of the STV for the MAJOR_VERSION.MINOR_VERSION.MICRO_VERSION SageTV release, starting with 0 for the first STV version of a new release.
