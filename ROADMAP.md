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

## Playback track

- **Multi-language AC-4 track switching.** Re-probe already detects
  `eng` + `spa` AC-4 streams; miniplayer transcode pipeline currently
  hardcodes `-map 0:a:0`. Add per-airing audio track preference + UI
  selector.
- **CTA-708 captions in HEVC SEI.** Accessibility win; needs SEI
  extraction or pass-through to client.

---

## Done

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
