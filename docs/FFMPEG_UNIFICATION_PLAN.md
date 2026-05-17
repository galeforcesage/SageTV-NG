# FFmpeg Unification Plan — single SageTV-patched AC-4-capable binary

**Status:** approved, not yet executed.
**Owner:** appmod/java-upgrade-20260328165139.
**Replaces:** the dual-binary arrangement of
`docker/build-modern-ffmpeg.sh` (6.1.1 + SageTV patches, no AC-4) +
`docker/build-ac4-ffmpeg.sh` (AC-4 fork, no SageTV patches) +
`docker/ffmpeg-wrapper.sh` (strips SageTV flags when stock ffmpeg
gets aliased into `/opt/sagetv/server/ffmpeg`).

---

## Problem

Container `sagetv-mine` currently ships `/opt/sagetv/server/ffmpeg`
as the legacy 2010 SageTV-patched binary (git-bbb07a0, built May 25
2021). Modern callers reference `/usr/local/bin/ffprobe-ac4` and
`/usr/local/bin/ffmpeg-ac4` (pliu6/elliotclee AC-4 fork) which are
NOT installed in this container. Result:

- `FormatParser.getFFMPEGFormatInfo()` works on legacy ATSC1 but
  cannot probe HEVC + AC-4 ATSC3 recordings.
- `HDHomeRunCaptureDevice.probeAtsc3FileWithFFMPEG()` is a silent
  no-op (binary missing).
- `AC4TranscodeJob` cannot run for legacy push miniclients.
- `HwEncoder` cannot probe NVENC capabilities.

## Goal

ONE ffmpeg binary at `/opt/sagetv/server/ffmpeg` that has:

1. All four SageTV custom CLI flags re-implemented in C:
   - `-stdinctrl` — runtime control channel (incl. `rateadjust` for
     slow-link bandwidth adaptation)
   - `-activefile` — live-DVR follow mode
   - `-dumpmetadata` — emits `META:KEY=VAL` lines used by FormatParser
   - `-brokendts` — tolerates broken MPEG-TS DTS
2. AC-4 decoder (pliu6 / elliotclee fork patches)
3. NVENC (h264/hevc), libx264, libx265, libfdk_aac, libxvid,
   libfreetype
4. Sibling `ffprobe` at `/opt/sagetv/server/ffprobe` with same
   capabilities.

All Java callers reference the single binary. `ffmpeg-wrapper.sh`,
`build-modern-ffmpeg.sh`, `build-ac4-ffmpeg.sh`,
`/usr/local/bin/ffmpeg-ac4`, `/usr/local/bin/ffprobe-ac4` go away.

---

## Phase A — `docker/build-sagetv-ffmpeg.sh`

Single build script. Inputs:

- `elliotclee/FFmpeg` (AC-4 fork) — **pin to specific commit**, NOT
  `master`. Initial pin: HEAD as of plan approval (≈ `1dc7ff58`
  on 2026-05-13). Recorded in `FFMPEG_COMMIT=` near top of script.
- `nv-codec-headers` tag `n12.1.14.0` (driver 530+, per existing
  build-ac4-ffmpeg.sh comment).

Build steps:

1. Clone fork at pinned commit.
2. Verify AC-4 decoder is present (`grep ff_ac4_decoder
   libavcodec/allcodecs.c` and `ls libavcodec/ac4dec.c`).
3. Apply four SageTV patches as separate `.patch` files committed
   under `docker/sagetv-ffmpeg-patches/`:
   - `0001-add-stdinctrl-flag.patch`
   - `0002-add-activefile-flag.patch` (maps to `-follow 1` /
     `AVFMT_FLAG_NOBUFFER` equivalent on modern API)
   - `0003-add-dumpmetadata-flag.patch`
   - `0004-add-brokendts-flag.patch` (maps to `-fflags +igndts`)

   Existing `build-modern-ffmpeg.sh` already forward-ports these to
   FFmpeg 6.1.1 via inline `sed`/heredoc; first task is to extract
   those into clean `.patch` files and rebase onto the elliotclee
   fork's tree (FFmpeg 7.x).
4. `configure`:
   ```
   --prefix=/install
   --disable-doc --disable-htmlpages --disable-manpages
   --disable-podpages --disable-txtpages
   --enable-gpl --enable-nonfree
   --enable-libx264 --enable-libx265
   --enable-libfdk-aac --enable-libxvid --enable-libfreetype
   --enable-nvenc --enable-ffnvcodec
   ```
5. `make -j$(nproc)`.
6. Self-verify:
   - `ffmpeg -version` shows commit + AC-4 tag
   - `ffmpeg -h full 2>&1 | grep -E 'stdinctrl|activefile|dumpmetadata|brokendts'`
     finds all four
   - `ffmpeg -decoders | grep '^ A....D ac4 '`
   - `ffmpeg -encoders | grep h264_nvenc`
7. `strip` and install to `/src/build/elf/sagetv-ffmpeg`,
   `/src/build/elf/sagetv-ffprobe`.

## Phase B — Dockerfile cleanup

- Remove the `RUN bash /src/docker/build-modern-ffmpeg.sh` line.
- Remove the `RUN bash /src/docker/build-ac4-ffmpeg.sh` block.
- Add `RUN bash /src/docker/build-sagetv-ffmpeg.sh` in their place.
- Remove the `ffmpeg-wrapper` install block
  (`COPY docker/ffmpeg-wrapper.sh /usr/local/bin/ffmpeg-wrapper`,
  the `mv /usr/bin/ffmpeg /usr/local/bin/ffmpeg.real` step, the
  `ln -sf /usr/local/bin/ffmpeg /opt/sagetv/server/ffmpeg`).
- Remove the `/usr/local/bin/ffmpeg-ac4` + `ffprobe-ac4` install
  block.
- Install new binary as real file:
  `install -m 755 /tmp/builder-elf/sagetv-ffmpeg
  /opt/sagetv/server/ffmpeg` (same for ffprobe).
- Keep `apt-get install ffmpeg` (provides `/usr/bin/ffmpeg` for
  comskip / unrelated tools).
- Once Phase E verifies, in a follow-up commit delete the now-unused
  scripts from `docker/`.

## Phase C — Java repoint (single binary for everything)

| File | Line(s) | Change |
|---|---|---|
| `java/sage/FFMPEGTranscoder.java` | 774–787 | Delete `miniplayer/transcode_ffmpeg_ac4` override block. Always use `getTranscoderPath()`. |
| `java/sage/FFMPEGTranscoder.java` | 966–971 | Restore unconditional `-stdinctrl`. Remove the "skipping (upstream ffmpeg-ac4 has no SageTV patch)" guard. |
| `java/sage/HDHomeRunCaptureDevice.java` | 1012 | `Sage.get("hdhr/atsc3_ffprobe_path", "/opt/sagetv/server/ffprobe")` |
| `java/sage/hdhr/AC4TranscodeJob.java` | 64 | `DEFAULT_FFMPEG = "/opt/sagetv/server/ffmpeg"` |
| `java/sage/HwEncoder.java` | 81 | `DEFAULT_PROBE_FF = "/opt/sagetv/server/ffmpeg"` |

## Phase D — FormatParser modern-output compatibility

Even with `-dumpmetadata` re-implemented, modern ffmpeg's
human-readable stream lines differ from 2010 ffmpeg. Update
`java/sage/media/format/FormatParser.java`:

- Line 830 `ffmpegStreamPat` — currently `Stream \#0\.(\d*).*`,
  matches only old `Stream #0.0:` syntax. New pattern must match
  BOTH old and new:
  `Stream \#0[\.:](\d+)(?:\[0x([0-9a-fA-F]+)\])?(.*)`
  (group 1 = stream index, group 2 = optional PID, group 3 = rest).
- Line 1503 `extractStreamPESIDFromFFMPEGStreamInfo()` — reuse the
  PID capture from the new pattern instead of a separate
  `\[0x...\]` scan.
- The `-v 2` numeric verbosity arg in `getFFMPEGFormatInfo()` is
  honored by our patched build; verify post-build.

## Phase E — Build & deploy (multi-step, image preserved)

Per user preference: rebuild image under a new tag, do NOT replace
running container.

```
# 1. Build new image
docker build -t sagetv-mine:ffmpeg-unified C:\Users\<...>\SageTV-mine

# 2. Smoke-test the new ffmpeg inside the image
docker run --rm sagetv-mine:ffmpeg-unified \
  /opt/sagetv/server/ffmpeg -h full 2>&1 | \
  grep -E 'stdinctrl|activefile|dumpmetadata|brokendts'
docker run --rm sagetv-mine:ffmpeg-unified \
  /opt/sagetv/server/ffmpeg -decoders 2>&1 | grep ac4
docker run --rm sagetv-mine:ffmpeg-unified \
  /opt/sagetv/server/ffmpeg -encoders 2>&1 | grep h264_nvenc

# 3. Extract just the binaries from the new image
docker create --name ffmpeg-extract sagetv-mine:ffmpeg-unified
docker cp ffmpeg-extract:/opt/sagetv/server/ffmpeg  .\ffmpeg.new
docker cp ffmpeg-extract:/opt/sagetv/server/ffprobe .\ffprobe.new
docker rm ffmpeg-extract

# 4. Backup in-container binaries and deploy new ones
docker exec sagetv-mine cp /opt/sagetv/server/ffmpeg  /opt/sagetv/server/ffmpeg.preac4
# (ffprobe doesn't yet exist in the running container; skip backup)
docker cp .\ffmpeg.new  sagetv-mine:/opt/sagetv/server/ffmpeg
docker cp .\ffprobe.new sagetv-mine:/opt/sagetv/server/ffprobe
docker exec sagetv-mine chmod 755 /opt/sagetv/server/ffmpeg /opt/sagetv/server/ffprobe

# 5. Build & deploy Sage.jar with Phase C/D Java repoints
# (standard sageJar / scp / docker cp / docker restart flow)
```

Rollback during testing: `docker exec sagetv-mine mv
/opt/sagetv/server/ffmpeg.preac4 /opt/sagetv/server/ffmpeg && docker
restart sagetv-mine`.

## Phase F — Cutover

User decision: no 24h burn-in required (dev system). After Phase E
smoke test passes for 109.1 playback + at least one legacy ATSC1
recording, delete `ffmpeg.preac4` from the live container and move
on. The next time the container is recreated from any image, it
picks up `sagetv-mine:ffmpeg-unified` automatically.

---

## Cross-cutting work item: 6.1.1 → 7.x CLI differences

Modern FFmpeg has dropped / renamed several options the SageTV code
or scripts still emit. Catalog and fix all of them in
`FFMPEGTranscoder.java`, `MPlayerTranscoder.java`, `HwEncoder.java`,
`AC4TranscodeJob.java`, `HttpLiveStreamer.java`, and any STV-side
profile that builds command lines.

Known starting list (extend during Phase A research):

| Old (6.x or earlier) | New (7.x) | Note |
|---|---|---|
| `-deinterlace` | `-vf yadif` | already done in commit `d0451217` |
| `-v 2` | `-v info` | numeric levels deprecated; SageTV patched build still accepts them but stock 7.x does not |
| `-vol <n>` | `-af volume=<n>dB` | |
| `-async <n>` | `-af aresample=async=<n>` | |
| `-vsync N` | `-fps_mode <vfr\|cfr\|passthrough\|drop>` | |
| `-sameq` | (removed long ago) | already absent |
| `-ab / -ar / -ac` global | per-stream `-b:a / -ar / -ac` | already converted in most places — verify all |
| `-newaudio / -newvideo / -newsubtitle` | `-map 0:a / 0:v / 0:s` | |
| `-pre` profile shortcuts | `-preset <name>` | |
| `-bsf` | `-bsf:v` / `-bsf:a` | |
| `-tune zerolatency` (legacy syntax) | per-encoder option, unchanged | |

Audit method: `grep -nE '"-[a-z]' java/sage/*Transcoder*.java
java/sage/Hw*.java java/sage/hdhr/*.java` and compare each flag
against `ffmpeg -h full` on the new build. Anything that prints
`Unrecognized option` is a bug to fix in Java.

---

## Out of scope this round (deferred to ROADMAP)

- **Plugin-installed FFmpeg libraries.** Some plugins in
  OpenSageTV/sagetv-plugin-repo ship their own old FFmpeg .so/.jar
  for thumbnail extraction or transcoding (e.g. Phoenix media
  utilities). They need an audit + compatibility plan but not in
  this round. Added to ROADMAP under "Longer-term modernization".
- AC-4 encoding (decode-only for now; transcode path produces AC-3
  or AAC).
- Removing `mplayer` (separate path, still used by some Placeshifter
  profiles).
