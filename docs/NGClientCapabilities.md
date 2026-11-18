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

Prospective ordinary native media ownership is separately optional via
`NATIVE_PLAYBACK_OWNERSHIP_V1`. Its exact actual-Socket Java API, pre-OPENURL
publication, immutable server-selected resource, Stop/generation fences,
same-resource retirement/reissue and conditional secure native-cookie boundary
are canonical in [NativePlaybackOwnershipV1.md](NativePlaybackOwnershipV1.md).
It changes neither normal playback selection nor legacy defaults and is not a
Party capability or an HTTP credential by itself.

Party Mode is a separately negotiated, session-authorized, opt-in extension, not implied
by the NG version. Its exact capability tokens, `PARTY_V1` transport, server
enablement/admission prerequisites, schemas and executable integration examples are
canonical in [PartyModeV1.md](PartyModeV1.md). Existing no-login LAN admission is
supported with transport-bound session tokens; no admin pairing is needed.
Legacy/unnegotiated clients are ineligible; normal playback defaults are unchanged.
Party contract release v0.10 uses the stable `PARTY_V1` wire namespace and the
renderer's ordinary SageTV Watch/source path. It requires
`PARTY_CONTRACT_REV_010`, `PARTY_JOIN_CODE_V1`, `PARTY_INVITATION_V1`,
`PARTY_MONOTONIC_V1`, `PARTY_SCHEDULED_V1`,
`PARTY_HEARTBEAT_V1`, `PARTY_PLAYER_ADAPTER_V1`,
`PARTY_OPEN_BINDING_V1` and
`PARTY_PRESENTED_FRAME_V1`, `PARTY_ARMED_REVEAL_V1`, plus
`PARTY_LOCAL_STOP_V1`. Even selected connected
invitees enter the host's
three-digit code on their existing admitted session. No join-token bypass or
deep link is offered. The player adapter prepares the canonical `MediaFile`
through normal Watch, remains paused until a future command/correction, executes
generation-fenced monotonic deadlines, holds disruptive reposition frames
behind an ARMED/reveal barrier, reports the strongest supported
generation-correlated frame and active-surface cover-removal evidence for
activation, and sends later observed media time through HEARTBEAT. This evidence
may be compositor submission/selection plus cover completion or the specified
PWA rendering opportunity; it is not proof of physical panel scanout. Party
Mode does not select a renderer surface, issue media admission
handles, or expose GPU/transcoder internals.
Optional `PARTY_GROWING_MEDIA_V1` is required for active Sage recording/timeshift
parties. It means the adapter consumes the dynamic `availableEndMs` /
`playableEndMs` window, preserves the configured delayed live edge, and remains
correct through recording segment rollover and completion. It does not authorize
raw live/network streams or permit treating the current available end as final.
Optional `PARTY_PUSH_EPOCH_ORIGIN_V1` is required whenever Party selects direct
push. The client accepts media command 42 after each FLUSH and before new-epoch
PUSHBUFFER bytes, installs its same-sample recording/media timestamp mapping,
and ACKs only after prior-epoch packets, frames and replies are unreachable.
PWA bridges instead bind each preparation to the preallocated
`playbackSessionId` carried by `MEDIA_OPEN_BINDING_ACK`; the HTTP binding and
session headers must match so concurrent sessions on one bridge cannot consume
one another's opens.
Pre-release clients and servers are lockstep: HELLO/AVAILABLE negotiate
`contractRevision=10`, and CREATE/JOIN/RESUME revalidate that token-bound
revision. Sharing the generic PARTY_V1 property name does not make older clients
eligible for a v0.10 room.
Existing NG playback-surface and native ownership capabilities remain separate,
reusable playback features and are not Party protocol messages.

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
| `DISPLAY_RESOLUTION` | actual video/UI surface or activity-window pixel dimensions (`Surface`/`WindowMetrics`), not `Display.Mode` | the render target/framebuffer currently used by the app |
| `DISPLAY_SINK_RESOLUTION` | active output timing mode for the display hosting the active video surface (`Display.Mode.getPhysicalWidth/Height`); Tizen uses `webapis.productinfo.getRealResolution()` | the currently selected sink timing, e.g. `3840x2160`; it is not proof of the panel's native resolution and must follow external-display moves |
| `DISPLAY_REFRESH_RATES` | `Display.getSupportedModes()` | csv of supported refresh rates (24, 25, 30, 50, 60) |
| `DISPLAY_HDR_TYPES` | `Display.getHdrCapabilities()` | csv: `HDR10,HDR10+,DOLBY_VISION,HLG` |
| `DISPLAY_MAX_LUMINANCE` | same | nits — for tone mapping |
| `DISPLAY_WIDE_COLOR` | `Configuration.isScreenWideColorGamut()` | `true` for BT.2020-capable sinks |
| `LOCAL_ENHANCEMENT` | user policy + client upscaler capability | `pref=auto\|always\|ai_only\|never;cap=none\|standard\|high_quality\|ai;status=active\|available\|none`. The client declares its own capability; do not send a device or product model |

Under `pref=auto`, a specialized/AI server provider outranks `cap=high_quality`.
If specialized scaling is unavailable, `high_quality` and `ai` clients receive
the source resolution unchanged. `standard` and `none` clients may receive the
standard server scaler.

> **There is no `SUPPORTS_4K` field.** A client's user-facing
> Auto / Always / AI Only / Never is carried by `LOCAL_ENHANCEMENT.pref`.
> `DISPLAY_SINK_RESOLUTION` always remains the honest active sink timing and is
> never withheld or fabricated to express policy. It must not be described as
> the TV's native panel resolution. See
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
  {"codec":"H264","hw":true,"modes":[
    {"profile":"High","level":"5.1","bitDepth":8,
     "maxW":3840,"maxH":2160,"maxFps":60,"maxKbps":80000}
  ]},
  {"codec":"HEVC","hw":true,"modes":[
    {"profile":"Main","level":"5.1","bitDepth":8,
     "maxW":3840,"maxH":2160,"maxFps":60,"maxKbps":120000,"hdr":[]},
    {"profile":"Main10","level":"5.1","bitDepth":10,
     "maxW":3840,"maxH":2160,"maxFps":60,"maxKbps":120000,
     "hdr":["HDR10","DOLBY_VISION"]}
  ]},
  {"codec":"AV1","hw":false,"modes":[
    {"profile":"Main","level":"4.0","bitDepth":8,
     "maxW":1920,"maxH":1080,"maxFps":30,"maxKbps":30000}
  ]},
  {"codec":"VP9","hw":true,"modes":[
    {"profile":"Profile0","level":"5.1","bitDepth":8,
     "maxW":3840,"maxH":2160,"maxFps":60,"maxKbps":80000}
  ]},
  {"codec":"MPEG2","hw":false,"modes":[]}
]
```

Build it from `MediaCodecList.REGULAR_CODECS`. On API 29+, mark `hw=true` only
when `codecInfo.isHardwareAccelerated()` returns true. Android 21-28 has no such
API; use the platform's explicit software-only classification/name rules when
available, otherwise report `hw=false` rather than guessing hardware support.
The server uses this to decide raw push vs transcode, and to pick the transcode
target codec.

Each `modes[]` entry is one explicitly verified
`{codec, profile, level, bitDepth, maxW, maxH, maxFps, maxKbps, hdr[]}` tuple.
The arrays are alternatives; fields from different entries must never be
combined as a Cartesian product. Android must validate every advertised mode
with the codec capabilities and
`VideoCapabilities.areSizeAndRateSupported(...)`. If only separate maxima are
known, reduce the mode until the complete combination is proven or omit it.
An empty `modes` array means the codec is reported by the OS but no safe
delivery tuple has been validated; the server cannot select it.

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
- `passthrough[]` — direct-playback queries do not independently probe an
  arbitrary inactive route. Report passthrough only for the currently routed
  output. On API 33+, call `AudioManager.getDirectPlaybackSupport(format,
  attrs)` and include a codec only when the returned bitmask contains
  `DIRECT_PLAYBACK_BITSTREAM_SUPPORTED`. Offload-only support is not evidence
  of encoded passthrough.
- `decode[]` — ExoPlayer renderer's `supportsFormat()` for SW/HW decode
  to PCM on this route. AAC/MP3/OPUS/FLAC are almost universal.
- `max_channels` — maximum reported by the active `AudioDeviceInfo`; use `0`
  when unknown. Never assume eight channels merely because the type is HDMI or
  HDMI ARC.

On API 29-32, `AudioTrack.isDirectPlaybackSupported(format, attrs)` is
route-uncertain; combine it with the currently routed output and fail closed
when attribution is ambiguous. Android 21-28 has no equivalent direct-playback
query: report passthrough as unknown/empty unless the production player proves
the codec on the active route. Do not claim passthrough solely from
`AudioDeviceInfo.getEncodings()` or another inactive HDMI device. Inactive
routes may be listed for identity, but their passthrough arrays remain empty.

**Route observation:** `AudioDeviceCallback` is one invalidation signal, not
proof that every active-route switch was observed. Re-query the production
player's currently routed device at tune/PREPARE, resume, audio-focus changes,
player route callbacks, every device callback, and periodically with playback
statistics. Emit the notification only after the active route ID actually
changes.

**Push event:** when the active routed device changes, emit
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
  (bytes received / wall time) over the last 5 s. This is the paced delivery
  rate, not available link capacity; never use it alone as a bandwidth ceiling.
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
- `auto` — pick the best quality supported by an independent capacity estimate
  and current playback health.
- `quality` — bias upward only when an unpaced capacity estimate leaves
  headroom; never infer headroom from paced receive throughput.
- `savings` — bias downward, prefer H.264 over HEVC when both are available to
  reduce client decode power, and never push 4K over a cellular route.

The hint is the **only** user-visible control. Everything else is
inferred from the capability properties above.

## 9. Server decision pipeline

```
on tune(source):
  caps   = {video_matrix, audio_matrix(active_route), display, extractors}
  budget = {
    capacity_kbps = unpaced_capacity_estimate() ?: null,
    delivered_kbps = ewma(NET_STATS.throughput_kbps),
    rtt_ms   = ewma(NET_STATS.rtt_ms)            ?: 30,
    metered  = NET_METERED,
    health   = {buffer trend, retransmits, dropped frames, underruns}
  }
  ceiling_kbps = budget.capacity_kbps == null
      ? null
      : budget.capacity_kbps * quality_headroom_factor(QUALITY_HINT)
  stable_kbps = last_representation_with_healthy_buffer() ?: null
  selection_ceiling_kbps = ceiling_kbps
      ?: stable_kbps
      ?: conservative_initial_ceiling(route, budget.metered)

  # Paced delivered_kbps proves only that the current representation arrived.
  # It may support a downshift together with collapsing buffer/retx evidence,
  # but it must never authorize an upward bitrate change.

  # Video. Low RTT does not prove bandwidth.
  if exact_tuple_supported(source.video.codec, source.video.profile,
                           source.video.level, source.video.bit_depth,
                           source.video.width, source.video.height,
                           source.video.frame_rate)
     and source.video.kbps <= selection_ceiling_kbps:
    push raw video
  else:
    emitted_hevc_profile = Main10 if source.hdr else Main
    emitted_hevc_bit_depth = 10 if source.hdr else 8
    if exact_tuple_supported(HEVC, emitted_hevc_profile, selected_hevc_level,
                             emitted_hevc_bit_depth, target_width, target_height,
                             target_fps)
       and not budget.metered:
      transcode HEVC using emitted_hevc_profile, selected_hevc_level and
                emitted_hevc_bit_depth at
                min(source.kbps, selection_ceiling_kbps)
    elif exact_tuple_supported(H264, selected_h264_profile, selected_h264_level,
                               8, target_width, target_height, target_fps):
      transcode H264 using that proven profile/level at
                min(source.kbps, selection_ceiling_kbps)
    else:
      reject video delivery

  # Audio
  active = caps.audio_matrix.active_route
  if source.audio in active.passthrough:
    push raw audio
  elif source.audio in active.decode:
    push raw audio (client decodes)
  else:
    target_audio = first supported active-route decode/passthrough target from
                   [EAC3, AC3, AAC, PCM]
    if target_audio exists:
      transcode to target_audio within active.max_channels
    else:
      reject audio delivery

  # Container: prefer remux over full transcode whenever the selected
  # representation already fits and the client accepts the source codecs.
  needs_remux = (source.container not in extractors) and
                (source.video in caps.video_matrix) and
                (source.audio_codec_ok)
  if needs_remux and TS in extractors:         container = MPEG2-TS
  elif needs_remux and MKV in extractors:      container = MKV
  elif needs_remux and MP4 in extractors:      container = MP4
  elif needs_remux:                            reject container delivery
  elif TUNNELED_PLAYBACK and TS in extractors: container = MPEG2-TS
  elif source.container in extractors:         container = source.container
  elif MKV in extractors:                      container = MKV
  elif MP4 in extractors:                      container = MP4
  else:                                        reject container delivery

  # Transport: protocol and mode support must both be true. low_rtt is only a
  # latency preference; it does not prove bandwidth.
  low_rtt = budget.rtt_ms <= 20
  push_capable = SAGE_PUSH in protocols and
                 (push in transport_modes or dynamic in transport_modes)
  pull_capable = pull in transport_modes and
                 (HTTP in protocols or HTTPS in protocols)
  hls_capable = HLS in protocols and
                (pull in transport_modes or dynamic in transport_modes) and
                (HTTP in protocols or HTTPS in protocols)

  if party_prepare:
    # Apply Party constraints before ordinary Watch chooses a route.
    # Direct push is eligible only with the command-42 epoch/origin extension.
    if not negotiated_party_push_epoch_origin:
      push_capable = false
    require preparation_binding_transport_metadata
    if browser_client:
      # Party PWA media requests must be fetch-controlled so the binding header
      # can be attached and validated. Browser-managed/native HLS is ineligible.
      pull_capable = pull_capable and
                     route_can_attach_party_binding_header(pull)
      hls_capable = hls_capable and
                    route_can_attach_party_binding_header(HLS)

  if low_rtt and push_capable:
    push, remux=on-demand   # LAN: keep server CPU at zero whenever we can
  elif budget.rtt_ms > 80 and push_capable:
    push, remux=always      # avoid transcoder warm-up jitter on slow links
  elif hls_capable and budget.metered:
    HLS                     # adaptive bitrate handles cellular variability
  elif pull_capable:
    pull
  elif push_capable:
    push
  else:
    reject delivery         # never fall back to an unsupported transport

on NOTIFY PLAYBACK_STATS:
  if dropped/rendered > 0.02
     OR audio_underruns > 0
     OR buffer_ms < 1500:
    candidate = next lower representation
                (HEVC→H264, or video_kbps × 0.75)
    require exact_tuple_supported(candidate.codec, candidate.profile,
                                  candidate.level, candidate.bit_depth,
                                  candidate.width, candidate.height,
                                  candidate.frame_rate)
    require candidate.container in extractors
    require candidate.audio tuple supported on active route
    if every actual emitted tuple is valid:
      switch to candidate
    else:
      try a lower fully valid rung or reject the adaptation

on NOTIFY AUDIO_ROUTE_CHANGED:
  re-run audio decision against new active route

on NOTIFY NET_STATS with paced throughput drop:
  do not downshift from throughput alone
  downshift only when corroborated by collapsing buffer, retransmits,
  dropped frames or underruns
```

## 10. Implementation checklist for the NG client team

- [ ] Bump `SAGETV_NG_VERSION` to `2.0.0`
- [ ] 6 device-identity `GetProperty` handlers
- [ ] 5 display `GetProperty` handlers
- [ ] `DISPLAY_SINK_RESOLUTION` — the active sink timing, distinct from
      `DISPLAY_RESOLUTION`; it follows the active video display and does not
      claim the panel's native resolution
- [ ] `LOCAL_ENHANCEMENT`
      (`pref=…;cap=none|standard|high_quality|ai;status=…`)
- [ ] Wire the user's Auto / Always / AI Only / Never setting to `pref`; always
      report the honest active `DISPLAY_SINK_RESOLUTION`
- [ ] Required per-surface `MAX_OUTPUT_WIDTH` / `MAX_OUTPUT_HEIGHT` /
      `MAX_FPS` as one jointly supported tuple, not independent maxima
- [ ] `VIDEO_DECODER_MATRIX` JSON builder
- [ ] `AUDIO_ROUTE_MATRIX` JSON builder (API 33+ path + fallback)
- [ ] Active-route revalidation at tune/PREPARE, resume, focus/player route
      events, `AudioDeviceCallback`, and periodic playback stats; notify only on
      an observed routed-device change
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
