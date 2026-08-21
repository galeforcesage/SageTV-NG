# NG Client Capability Spec

**Audience:** NG Android client team
**Goal:** zero user-facing streaming toggles. The client reports what the
hardware can do, the server reports what the source is and what the link
can carry, and the server picks the best `{transport, container, video
codec, video bitrate, audio codec, audio bitrate, remux vs transcode}` for
every tune. The server downshifts mid-stream when telemetry shows trouble.

This replaces the six legacy client toggles documented in
[ClientSettings.md](ClientSettings.md). When the server sees the
properties in this spec, it ignores all six legacy toggles for that client.

## Versioning

Bump `SAGETV_NG_VERSION` to `2.0.0` when this spec is implemented.
The server uses this as a single gate — if it sees `2.0.0+` it expects
every property below to be answerable.

## 1. Device identity (one‑shot at connect)

| GetProperty | Source | Required | Notes |
|---|---|---|---|
| `DEVICE_MANUFACTURER` | `Build.MANUFACTURER` | yes | logging + bug‑report routing |
| `DEVICE_MODEL` | `Build.MODEL` | yes | logging + known‑quirk list |
| `DEVICE_SDK_INT` | `Build.VERSION.SDK_INT` | yes | gates API availability on the server |
| `DEVICE_FORM_FACTOR` | `UiModeManager.getCurrentModeType()` | yes | `TV` / `TABLET` / `PHONE` / `CAR` / `WATCH` |
| `PLAYER_ENGINE` | constant string | yes | e.g. `"ExoPlayer 2.19.1"` |
| `PLAYER_EXTENSIONS` | csv | yes | which ExoPlayer extensions are loaded: `ffmpeg,av1,opus,…` |

## 2. Display capability (one‑shot)

| GetProperty | Source | Notes |
|---|---|---|
| `DISPLAY_RESOLUTION` | `Display.getMode().getPhysicalWidth/Height` | the UI/render surface. On Android this reports the framebuffer, so a Shield rendering 1080p on a 4K TV answers `1920x1080` here — do **not** treat it as the panel |
| `DISPLAY_SINK_RESOLUTION` | `Display.getMode().getPhysicalWidth/Height` (Android), `webapis.productinfo.getRealResolution()` (Tizen) | the **physical panel**, e.g. `3840x2160`. Required for server video enhancement; see [NGServerVideoEnhancement.md](NGServerVideoEnhancement.md) §2.1 |
| `DISPLAY_REFRESH_RATES` | `Display.getSupportedModes()` | csv of supported refresh rates (24, 25, 30, 50, 60) |
| `DISPLAY_HDR_TYPES` | `Display.getHdrCapabilities()` | csv: `HDR10,HDR10+,DOLBY_VISION,HLG` |
| `DISPLAY_MAX_LUMINANCE` | same | nits — for tone mapping |
| `DISPLAY_WIDE_COLOR` | `Configuration.isScreenWideColorGamut()` | `true` for BT.2020-capable sinks |
| `LOCAL_ENHANCEMENT` | client's own upscaler state | `pref=auto\|local\|server;status=active\|available\|none`. Report `status=active` when the device is already upscaling, so the server doesn't duplicate it |

> **There is no `SUPPORTS_4K` field.** A client's user-facing
> Auto / Always / Never setting for server upscaling is carried entirely by
> *whether and when* it populates `DISPLAY_SINK_RESOLUTION` — Never sends `""`,
> Always sends the honest physical panel unconditionally, Auto sends it only
> when the client judges itself eligible. The value is always the true panel and
> never a fabricated 4K. See
> [NGServerVideoEnhancement.md](NGServerVideoEnhancement.md) §2.9.

> **Server video enhancement (GPU upscale to 4K)** additionally requires the
> per-surface output limits `PLAYBACK_SURFACE_<id>_MAX_OUTPUT_WIDTH`,
> `_MAX_OUTPUT_HEIGHT` and `_MAX_FPS`. Every one of these fields fails closed: a
> client that omits them parses fine and simply never receives an enhanced stream,
> with no error. The full contract, including the accepted value ranges and a table
> for diagnosing refusals, is in
> [NGServerVideoEnhancement.md](NGServerVideoEnhancement.md).

## 3. Video decode matrix (one‑shot)

```
GetProperty VIDEO_DECODER_MATRIX
```
returns one JSON array enumerating every video decoder the OS reports:

```json
[
  {"codec":"H264","hw":true,"maxW":3840,"maxH":2160,"maxFps":60,
   "maxKbps":80000,"profiles":["High","High10"]},
  {"codec":"HEVC","hw":true,"maxW":3840,"maxH":2160,"maxFps":60,
   "maxKbps":120000,"profiles":["Main","Main10"],
   "hdr":["HDR10","DOLBY_VISION"]},
  {"codec":"AV1","hw":false,"maxW":1920,"maxH":1080,"maxFps":30},
  {"codec":"VP9","hw":true,"maxW":3840,"maxH":2160,"maxFps":60},
  {"codec":"MPEG2","hw":false}
]
```

Build it from `MediaCodecList.REGULAR_CODECS`. Mark `hw=true` when
`codecInfo.isHardwareAccelerated()`. The server uses this to decide raw
push vs transcode, and to pick the transcode target codec.

## 4. Audio decode + passthrough matrix

This is the most important property in the spec. Audio capability is
**per output route** and changes when the user plugs in headphones or
turns on an AVR.

```
GetProperty AUDIO_ROUTE_MATRIX
```
returns:

```json
{
  "active_route":"HDMI_ARC",
  "routes":[
    {"id":"HDMI_ARC","type":"HDMI_ARC",
     "passthrough":["AC3","EAC3","AC4","DTSHD","TRUEHD"],
     "decode":["AAC","MP3","OPUS","FLAC","PCM"],
     "max_channels":8},
    {"id":"SPEAKER","type":"BUILTIN_SPEAKER",
     "passthrough":[],
     "decode":["AAC","MP3","AC3","EAC3","OPUS","FLAC","PCM"],
     "max_channels":2},
    {"id":"BT_A2DP","type":"BLUETOOTH_A2DP",
     "passthrough":[],
     "decode":["AAC","SBC","LDAC","APTX"],
     "max_channels":2}
  ]
}
```

How to compute each field:
- `passthrough[]` — call `AudioTrack.getDirectPlaybackSupport(format,
  attrs)` (API 33+) for every encoding (AC3, EAC3, AC4, DTS, DTSHD,
  TRUEHD) against this route. Include codecs returning
  `DIRECT_PLAYBACK_SUPPORTED`.
- `decode[]` — ExoPlayer renderer's `supportsFormat()` for SW/HW decode
  to PCM on this route. AAC/MP3/OPUS/FLAC are almost universal.
- `max_channels` — `AudioDeviceInfo.getChannelCounts()` max, or 8 for
  HDMI_ARC, 2 for speakers/BT.

On API 32 and lower, fall back to a per-encoding `AudioManager.isAudioFormatSupported()` probe.

**Push event:** when `AudioDeviceCallback` fires (route change), emit
async:
```
NOTIFY AUDIO_ROUTE_CHANGED  {new_active_route_id}
```
The server immediately re-runs its audio decision and may switch codecs
mid-stream.

## 5. Transport / streaming capability (one‑shot)

| GetProperty | Notes |
|---|---|
| `TRANSPORT_MODES` | csv: `push,pull,dynamic` |
| `EXTRACTORS_SUPPORTED` | csv of ExoPlayer extractors: `MP4,MKV,TS,FLV,FRAGMENTED_MP4,OGG,FLAC,WAV` |
| `STREAMING_PROTOCOLS` | csv: `HTTP,HTTPS,HLS,DASH,RTSP,SAGE_PUSH` |
| `TUNNELED_PLAYBACK` | `true` when `MediaCodec.PARAMETER_KEY_TUNNEL_PEER` works — server prefers TS container on tunneled paths |
| `DRM_SCHEMES` | csv: `WIDEVINE_L1,WIDEVINE_L3,PLAYREADY` — future-proofing |

## 6. Network telemetry

### Initial one‑shot

| GetProperty | Source |
|---|---|
| `NET_LINK_TYPE` | `ConnectivityManager.getActiveNetwork()` transport: `WIFI` / `ETHERNET` / `CELLULAR` |
| `NET_LINK_KBPS_DOWN` | `NetworkCapabilities.getLinkDownstreamBandwidthKbps()` |
| `NET_LINK_KBPS_UP` | same upstream |
| `NET_METERED` | `ConnectivityManager.isActiveNetworkMetered()` |
| `NET_WIFI_RSSI` | `WifiInfo.getRssi()` when on WIFI |
| `NET_WIFI_FREQ` | `WifiInfo.getFrequency()` MHz when on WIFI |

### Recurring (every 5 s during playback)

Push async event:
```
NOTIFY NET_STATS  {"rtt_ms":42, "throughput_kbps":18400, "retx_pct":0.4, "route":"WIFI"}
```
- `rtt_ms` — ping opcode on UI channel; smoothed.
- `throughput_kbps` — measured by client over the push socket
  (bytes received / wall time) over the last 5 s.
- `retx_pct` — TCP retransmits if discoverable; `-1` if unavailable.

## 7. Playback telemetry (every 2 s during playback)

Push async event:
```
NOTIFY PLAYBACK_STATS  {
  "buffer_ms":3200,
  "dropped_frames":2, "rendered_frames":240,
  "audio_underruns":0,
  "decoder_video":"OMX.qcom.video.decoder.hevc(HW)",
  "decoder_audio":"c2.android.eac3.decoder(SW)",
  "surface_kbps":9500,
  "render_mode":"TUNNELED"
}
```

Sources:
- `buffer_ms` — `Player.getTotalBufferedDuration()`.
- `dropped_frames` / `rendered_frames` — `VideoSize`/`AnalyticsListener.onDroppedVideoFrames`.
- `audio_underruns` — `AudioSink` event counter (ExoPlayer 2.18+).
- `decoder_*` — `MediaCodecInfo.getName()` for active decoders.
- `surface_kbps` — incoming bitrate measured at the demuxer.
- `render_mode` — `TUNNELED` / `NORMAL` / `SW_FALLBACK`.

This is the feedback loop that lets the server downshift on real evidence
(dropped frames, underruns, buffer collapse) instead of waiting for the
user to complain.

## 8. Quality preference hint (optional, user-facing)

Single user control on the NG client settings screen:

> **Quality preference**: `Auto` (default) / `Prefer quality` / `Prefer data savings`

When set to anything other than `Auto`, the client reports:

```
GetProperty QUALITY_HINT
```
returns one of: `auto`, `quality`, `savings`.

Server interpretation:
- `auto` — pick best quality the link sustainably carries
  (default budget = `0.7 × throughput_kbps`).
- `quality` — bias upward (budget = `0.85 × throughput_kbps`, accept
  slightly more dropped-frame risk; never downshift on the first
  underrun, only on sustained ones).
- `savings` — bias downward (budget = `0.4 × throughput_kbps`, prefer
  H.264 over HEVC when both available to reduce client decode power,
  never push 4K over a cellular route).

The hint is the **only** user-visible control. Everything else is
inferred from the capability properties above.

## 9. Server decision pipeline

```
on tune(source):
  caps   = {video_matrix, audio_matrix(active_route), display, extractors}
  budget = {
    bw_kbps  = ewma(NET_STATS.throughput_kbps)  ?: NET_LINK_KBPS_DOWN,
    rtt_ms   = ewma(NET_STATS.rtt_ms)            ?: 30,
    metered  = NET_METERED,
    factor   = (QUALITY_HINT == "quality") ? 0.85 :
               (QUALITY_HINT == "savings") ? 0.40 : 0.70
  }
  ceiling_kbps = budget.bw_kbps * budget.factor
  low_latency  = (budget.rtt_ms < 10)   # LAN / wired / strong WiFi

  # Video
  # Low-latency (LAN) rule: avoid transcode whenever possible.
  # On a fat local link we'd rather push raw (or remux container only)
  # than spin up FFmpeg — saves server CPU, preserves source quality,
  # and the link can carry the higher bitrate anyway.
  if source.video in caps.video_matrix
     and (low_latency or source.video.kbps <= ceiling_kbps):
    push raw video    # no ceiling check on LAN
  elif source.video in caps.video_matrix and source.video.kbps > ceiling_kbps
       and budget.metered == false and budget.bw_kbps > source.video.kbps:
    # Codec is OK, bitrate exceeds soft ceiling but link can carry it →
    # still push raw on non-metered links; only transcode when the link
    # genuinely cannot sustain the source.
    push raw video
  elif HEVC in caps.video_matrix and not budget.metered:
    transcode HEVC at min(source.kbps, ceiling_kbps),
              Main10 if source.hdr else Main
  else:
    transcode H264 high@4.1 at min(source.kbps, ceiling_kbps)

  # Audio
  active = caps.audio_matrix.active_route
  if source.audio in active.passthrough:
    push raw audio
  elif source.audio in active.decode:
    push raw audio (client decodes)
  else:
    transcode → EAC3 if HDMI route, AAC otherwise, 384 kbps

  # Container
  # Low-latency rule continues here: when we DO need to change something,
  # prefer a container-only remux over a full transcode whenever the
  # client accepts the source codecs.
  needs_remux = (source.container not in extractors) and
                (source.video in caps.video_matrix) and
                (source.audio_codec_ok)
  if needs_remux:                              action = REMUX
  elif TUNNELED_PLAYBACK and TS in extractors: container = MPEG2-TS
  elif MKV in extractors:                      container = MKV
  else:                                        container = MP4

  # Transport
  if low_latency and SAGE_PUSH in transport_modes:
    push, remux=on-demand   # LAN: keep server CPU at zero whenever we can
  elif budget.rtt_ms > 80 and SAGE_PUSH in transport_modes:
    push, remux=always      # avoid transcoder warm-up jitter on slow links
  elif HLS in protocols and budget.metered:
    HLS                     # adaptive bitrate handles cellular variability
  else:
    push

on NOTIFY PLAYBACK_STATS:
  if dropped/rendered > 0.02
     OR audio_underruns > 0
     OR buffer_ms < 1500:
    downshift one rung (HEVC→H264, or video_kbps × 0.75)

on NOTIFY AUDIO_ROUTE_CHANGED:
  re-run audio decision against new active route

on NOTIFY NET_STATS with throughput drop > 30% sustained 10 s:
  downshift one rung
```

## 10. Implementation checklist for the NG client team

- [ ] Bump `SAGETV_NG_VERSION` to `2.0.0`
- [ ] 6 device-identity `GetProperty` handlers
- [ ] 5 display `GetProperty` handlers
- [ ] `DISPLAY_SINK_RESOLUTION` — the **physical panel**, distinct from
      `DISPLAY_RESOLUTION`; without it the server can never offer 4K enhancement
- [ ] `LOCAL_ENHANCEMENT` (`pref=…;status=…`)
- [ ] Wire the user's Auto / Always / Never upscale setting to *whether* you send
      `DISPLAY_SINK_RESOLUTION` (Never ⇒ `""`, Always ⇒ always the honest panel).
      There is no separate field for this — see §2.9 of the enhancement doc
- [ ] Per-surface `MAX_OUTPUT_WIDTH` / `MAX_OUTPUT_HEIGHT` (both, or neither
      counts) and optional `MAX_FPS`
- [ ] `VIDEO_DECODER_MATRIX` JSON builder
- [ ] `AUDIO_ROUTE_MATRIX` JSON builder (API 33+ path + fallback)
- [ ] `AudioDeviceCallback` listener → `NOTIFY AUDIO_ROUTE_CHANGED`
- [ ] Transport / extractor / protocol getters
- [ ] Initial network-state getters
- [ ] 5 s `NET_STATS` push thread
- [ ] 2 s `PLAYBACK_STATS` push thread
- [ ] (Optional) `QUALITY_HINT` setting screen + getter
- [ ] Remove the 6 legacy streaming toggles from the settings UI when
      `SAGETV_NG_VERSION >= 2.0.0` is honored by the server

## 11. What this does to the user experience

With every property in this spec wired up:

- New user installs the NG client → first tune at best quality the link
  carries, automatic codec/container/audio-route selection.
- User plugs in headphones mid-show → audio switches from passthrough
  AC3 to client-decoded AAC inside one buffer-flush.
- User moves to weaker WiFi → server downshifts to fit measured BW
  before the user sees a stutter.
- User on cellular → server picks H.264 SD, 384k AAC, MP4 container
  automatically; toggling `QUALITY_HINT=savings` halves the ceiling.

No streaming preference screen is needed beyond the optional
`QUALITY_HINT`.
