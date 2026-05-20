# Offline transcode preset catalogue

This directory ships the baseline set of "Transcode To..." presets consumed by
`sage.Ministry.loadPresets()`. At server startup Ministry walks two paths in
precedence order:

1. `${STATE_DIR}/transcoder/presets/*.properties` — deploy / user overrides
   (e.g. `/opt/sagetv/state/mine/transcoder/presets/` in the state-managed
   container layout). Files here take precedence over baseline by `name`.
2. `<install-root>/presets/transcoder/*.properties` — this directory, shipped
   in the source tree and copied into the container image by the Dockerfile.

Each `.properties` file describes one preset:

```
name=PHONE_STD                 # display name, becomes the
                               # transcoder/formats/<name> Sage.properties key
container=mp4                  # output muxer / file extension hint
global=-hwaccel cuda -hwaccel_output_format cuda
args=-vf scale_npp=1280:720:interp_algo=lanczos -c:v %V264% -preset p5 \
     -rc:v vbr -cq:v 23 -b:v 3500k -maxrate 4500k -bufsize 9000k \
     -c:a aac -b:a 160k -ac 2 -movflags +faststart
```

Tokens substituted at load time:

| Token    | Resolved via                                                  |
|----------|---------------------------------------------------------------|
| `%V264%` | `HwEncoder.encoderName(HwEncoder.pick("h264"), "h264")` — yields `h264_nvenc` when NVENC is probed, else `libx264`. |
| `%V265%` | `HwEncoder.encoderName(HwEncoder.pick("hevc"), "hevc")` — yields `hevc_nvenc` when NVENC is probed, else `libx265`. |

Materialized form (stored in Sage.properties, parsed by
`ContainerFormat.buildFormatFromString` and consumed by `FFMPEGTranscoder` raw
cmdline mode introduced in Item 6):

```
f=mp4;MRawCmdlineGlobal=<global>;MRawCmdline=<args>;
```

Values are escaped via `MediaFormat.escapeString` so embedded `=` and `;`
round-trip correctly through the format-string parser.

## Catalogue

Screen-class targets (H.264 unless noted):

| Preset                  | Resolution  | Codec | cq | Bitrate target |
|-------------------------|-------------|-------|----|----------------|
| `PHONE_LOW`             | 854x480     | H.264 | 28 | 1500k          |
| `PHONE_STD`             | 1280x720    | H.264 | 23 | 3500k          |
| `PHONE_HIGH_1080`       | 1920x1080   | H.264 | 21 | 6000k          |
| `TABLET_10_1080`        | 1920x1080   | H.264 | 22 | 5000k          |
| `TABLET_12_1440`        | 2560x1440   | HEVC  | 24 | 8000k          |
| `TV_1080_COMPAT`        | 1920x1080   | H.264 | 20 | 8000k          |
| `TV_4K_HEVC`            | 3840x2160   | HEVC  | 22 | 16000k         |
| `ARCHIVE_HEVC_MKV`      | (source)    | HEVC  | 18 | 12000k         |
| `DVD_LEGACY_MPEG2`      | 720x480     | MPEG2 | —  | 6000k (sw)     |
| `UPSCALE_1440_FROM_1080`| 2560x1440   | HEVC  | 22 | 10000k         |
| `UPSCALE_2160_FROM_1080`| 3840x2160   | HEVC  | 22 | 18000k         |

Notes:

* All NVENC-backed presets use `-preset p5` (slow / quality balance).
* Upscale presets use `scale_npp` with `interp_algo=lanczos` for GPU-side
  resampling; for higher quality try `super` or external SR filters.
* `DVD_LEGACY_MPEG2` is software-only (NVENC has no MPEG-2 encoder).
* `ARCHIVE_HEVC_MKV` deliberately omits a scaler so source resolution is
  preserved.
