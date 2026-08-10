# NG STREAMINFO Protocol — Client Implementation Guide

## Overview

`MEDIACMD_STREAMINFO` (command ID `40`) is a **pre-stream metadata announcement**
sent by the SageTV-NG server to NG-capable clients **before** `MEDIACMD_OPENURL`.
It eliminates client-side stream probing by providing full codec, resolution,
audio track, and timing metadata upfront.

**Backwards compatibility**: This command is ONLY sent to clients that advertise
the `STREAMINFO` capability during NG session negotiation. Legacy clients never
receive it and are completely unaffected.

---

## Protocol Flow

### Before (Legacy / Non-STREAMINFO NG Clients)

```
Server                          Client
  |                               |
  |--- MEDIACMD_OPENURL (16) ---->|  (URL + format hint string)
  |<-- int reply (success) -------|
  |                               |  Client probes stream: sniffs container,
  |                               |  detects codecs, counts audio tracks,
  |                               |  measures resolution... (100-500ms)
  |                               |
  |--- MEDIACMD_PUSHBUFFER ------>|  Bytes start flowing
  |                               |  Client finally starts decoding
```

### After (NG Client with STREAMINFO Capability)

```
Server                          Client
  |                               |
  |--- MEDIACMD_STREAMINFO (40) ->|  Full JSON metadata
  |<-- int ACK bitmask -----------|  Client pre-configures pipeline
  |                               |  (0 probe time, decoders ready)
  |                               |
  |--- MEDIACMD_OPENURL (16) ---->|  URL (stream IS ready now)
  |<-- int reply (success) -------|  Client connects immediately
  |                               |
  |--- MEDIACMD_PUSHBUFFER ------>|  Bytes flow, decoding starts instantly
```

**Key timing improvement**: The server sends STREAMINFO, then openURL.
By the time openURL arrives, the client's decoder pipeline is already
configured. The client can start decoding the first frame immediately
upon receiving push data — no probe delay.

---

## Capability Advertisement

To receive STREAMINFO, the client must include `"STREAMINFO"` in its
NG capabilities set during session negotiation:

```
capabilities: [..., "STREAMINFO", ...]
```

The server checks `mcsr.hasClientCapability("STREAMINFO")` before
sending the command. If the capability is absent, the legacy path
(openURL with `ng_fmt=` hint in the URL) is used — no behavior change.

---

## Wire Format

```
┌─────────────────────────────────────────────────────┐
│ 4 bytes: (MEDIACMD_STREAMINFO << 24) | payload_len  │
│ 4 bytes: string_length (including null terminator)   │
│ N bytes: UTF-8 JSON payload                         │
│ 1 byte:  0x00 (null terminator)                     │
└─────────────────────────────────────────────────────┘
```

Same framing as `MEDIACMD_OPENURL` — a length-prefixed null-terminated
UTF-8 string. The payload is a JSON object.

---

## JSON Payload Schema

```json
{
  "v": 1,
  "container": "MPEG2-TS",
  "duration_ms": 3600000,
  "live": false,
  "bitrate": 18000000,
  "video": [
    {
      "codec": "H.264",
      "width": 1920,
      "height": 1080,
      "fps": 29.97,
      "fps_num": 30000,
      "fps_den": 1001,
      "ar_num": 16,
      "ar_den": 9,
      "interlaced": true,
      "id": "1011",
      "mime": "video/avc"
    }
  ],
  "audio": [
    {
      "codec": "AC3",
      "channels": 6,
      "sample_rate": 48000,
      "bits_per_sample": 16,
      "bitrate": 384000,
      "language": "eng",
      "primary": true,
      "id": "bd-80",
      "mime": "audio/ac3"
    },
    {
      "codec": "AC3",
      "channels": 2,
      "sample_rate": 48000,
      "language": "spa",
      "primary": false,
      "id": "bd-81",
      "mime": "audio/ac3"
    }
  ],
  "subtitle": [
    {
      "codec": "DVB Subtitle",
      "language": "eng",
      "id": "20-01"
    }
  ]
}
```

### Field Reference

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `v` | int | yes | Protocol version (always `1` for now) |
| `container` | string | yes | Wire container format name (SageTV canonical names: `MPEG2-TS`, `MPEG2-PS`, `MATROSKA`, `MP4`, `AVI`, `FLV`) |
| `duration_ms` | long | no | Total duration in milliseconds. Absent for live/timeshifted streams. |
| `live` | bool | yes | `true` if this is a live or timeshifted stream (duration unknown, no seek-to-end) |
| `bitrate` | int | no | Overall bitrate in bits/sec |
| `video[]` | array | yes | Video track list (usually 1 entry) |
| `video[].codec` | string | yes | SageTV codec name (see Codec Names below) |
| `video[].width` | int | no | Horizontal resolution in pixels |
| `video[].height` | int | no | Vertical resolution in pixels |
| `video[].fps` | float | no | Frames per second (may be approximate) |
| `video[].fps_num` | int | no | Frame rate numerator (exact; prefer over `fps`) |
| `video[].fps_den` | int | no | Frame rate denominator (exact) |
| `video[].ar_num` | int | no | Display aspect ratio numerator |
| `video[].ar_den` | int | no | Display aspect ratio denominator |
| `video[].interlaced` | bool | yes | `true` if interlaced content |
| `video[].id` | string | no | Stream/PID identifier (hex, e.g. `"1011"` for PID 0x1011) |
| `video[].mime` | string | no | MIME type for decoder selection (e.g. `"video/avc"`, `"video/hevc"`) |
| `audio[]` | array | yes | Audio track list (may have multiple languages) |
| `audio[].codec` | string | yes | SageTV codec name |
| `audio[].channels` | int | no | Channel count (2=stereo, 6=5.1, 8=7.1) |
| `audio[].sample_rate` | int | no | Sample rate in Hz |
| `audio[].bits_per_sample` | int | no | Bit depth |
| `audio[].bitrate` | int | no | Audio bitrate in bits/sec |
| `audio[].language` | string | no | ISO 639 language code (e.g. `"eng"`, `"spa"`) |
| `audio[].primary` | bool | yes | `true` for the default/primary audio track |
| `audio[].id` | string | no | Stream ID (e.g. `"bd-80"` for private stream 1, substream 0x80) |
| `audio[].mime` | string | no | MIME type (e.g. `"audio/ac3"`, `"audio/eac3"`, `"audio/mp4a-latm"`) |
| `subtitle[]` | array | yes | Subtitle track list (may be empty `[]`) |
| `subtitle[].codec` | string | yes | Format name |
| `subtitle[].language` | string | no | ISO 639 language code |
| `subtitle[].id` | string | no | Stream ID |

### SageTV Codec Names → MIME Mapping

| SageTV Name | MIME Type | Notes |
|-------------|-----------|-------|
| `H.264` | `video/avc` | |
| `HEVC` | `video/hevc` | |
| `MPEG2-Video` | `video/mpeg2` | |
| `MPEG4-Video` | `video/mp4v-es` | |
| `AV1` | `video/av01` | |
| `AC3` | `audio/ac3` | Dolby Digital |
| `EAC3` | `audio/eac3` | Dolby Digital Plus |
| `AC4` | `audio/ac4` | Dolby AC-4 |
| `AAC` | `audio/mp4a-latm` | |
| `MP2` | `audio/mpeg-L2` | |
| `MP3` | `audio/mpeg` | |
| `DTS` | `audio/vnd.dts` | |
| `FLAC` | `audio/flac` | |
| `PCM` | `audio/raw` | |

---

## Client ACK Response

The client replies to `MEDIACMD_STREAMINFO` with a single 32-bit integer
(same framing as all MEDIACMD replies). This integer is a **bitmask**:

| Bit | Value | Meaning |
|-----|-------|---------|
| 0 | `0x01` | Client parsed the JSON payload successfully |
| 1 | `0x02` | Client pre-configured video decoder (no probe needed) |
| 2 | `0x04` | Client pre-configured audio decoder(s) (no probe needed) |
| 3 | `0x08` | Client requests that server delay openURL until stream-ready (advisory) |

### Recommended client ACK behavior:

```
int ack = 0;
if (parseSuccess)    ack |= 0x01;  // Always set if JSON parsed
if (videoConfigured) ack |= 0x02;  // Set after MediaFormat/codec init
if (audioConfigured) ack |= 0x04;  // Set after audio renderer init
if (wantWaitReady)   ack |= 0x08;  // Optional: ask server to hold openURL
reply(ack);
```

**The ACK is sent IMMEDIATELY after parsing** — the server is waiting
synchronously for this reply before proceeding to openURL. Keep
processing time minimal (parse JSON + configure decoders, no I/O).

---

## Client Implementation Recommendations

### 1. Pre-Configure the Demuxer

Use `container` to select the correct demuxer/extractor **before** the
stream arrives:

```kotlin
val extractor = when (streamInfo.container) {
    "MPEG2-TS" -> TsExtractor(...)
    "MPEG2-PS" -> PsExtractor(...)
    "MATROSKA" -> MatroskaExtractor(...)
    "MP4"      -> FragmentedMp4Extractor(...)
    else       -> DefaultExtractorsFactory().createExtractors()
}
```

This eliminates the ~50-100ms sniffing phase where ExoPlayer tries each
extractor's `sniff()` method sequentially.

### 2. Pre-Create MediaFormat Objects

Build `MediaFormat` instances from the video/audio track metadata
**before** openURL arrives:

```kotlin
val videoFormat = MediaFormat.createVideoFormat(
    streamInfo.video[0].mime,    // "video/avc"
    streamInfo.video[0].width,  // 1920
    streamInfo.video[0].height  // 1080
).apply {
    if (streamInfo.video[0].fps_num > 0) {
        setFloat(KEY_FRAME_RATE,
            streamInfo.video[0].fps_num.toFloat() / streamInfo.video[0].fps_den)
    }
}

val audioFormat = MediaFormat.createAudioFormat(
    streamInfo.audio[0].mime,         // "audio/ac3"
    streamInfo.audio[0].sample_rate,  // 48000
    streamInfo.audio[0].channels      // 6
)
```

### 3. Pre-Allocate Codec Instances

Once you have the MediaFormat, you can create and configure the
`MediaCodec` instance immediately:

```kotlin
val videoDecoder = MediaCodec.createDecoderByType(videoFormat.mime)
videoDecoder.configure(videoFormat, surface, null, 0)
videoDecoder.start()  // Ready to receive buffers as soon as stream arrives
```

This saves 50-200ms of codec allocation + configuration that normally
happens AFTER the first frame is parsed from the stream.

### 4. Handle `live` Flag

- If `live == true`: do NOT show duration/seek bar, enable timeshift
  buffer management, expect the stream to grow indefinitely.
- If `live == false` and `duration_ms` is present: show seek bar with
  known duration, allow seek-to-position.

### 5. Audio Track Selection

The `audio[]` array gives you all available tracks with language codes.
You can present track selection UI **before** playback starts, or
auto-select based on user language preference without waiting for the
demuxer to discover tracks.

### 6. Handle Unknown Commands Gracefully

If a client receives command ID 40 but does NOT understand it (e.g.
a client that advertises STREAMINFO but has a parsing bug), it should
reply with `0x00` (no bits set). The server will proceed normally with
openURL — the STREAMINFO is purely advisory and does not block playback.

---

## Race Condition Protection

### Problem (before STREAMINFO)

```
1. Server sends openURL              (client has no format info)
2. Client connects to stream socket  (guesses extractor)
3. Transcoder still initializing     (no bytes ready yet)
4. Client times out or mis-sniffs    (playback failure)
```

### Solution (with STREAMINFO)

```
1. Server initializes transcoder/remuxer     (happens FIRST)
2. Server sends STREAMINFO                    (client gets full metadata)
3. Client ACKs (pipeline configured)          (~5ms)
4. Server sends openURL                       (stream IS ready)
5. Client connects                            (correct extractor, decoders ready)
6. First bytes arrive                         (immediate decode, no probe)
```

The critical ordering guarantee: **openURL is never sent until the
transcoder/remuxer is initialized AND STREAMINFO has been ACK'd.**
This eliminates the race where the client connects before the server
is ready to push bytes.

### Bit 3 (WAIT_READY) Advisory

If the client sets bit 3 in the ACK, it's telling the server:
"I understand you might not be ready yet — please don't send openURL
until the stream source is fully initialized."

In the current implementation, the server ALWAYS initializes before
sending openURL (the reordering already happened). Bit 3 is reserved
for future use where the server might want to pipeline more aggressively.

---

## Version Evolution

The `"v": 1` field allows future payload extensions. Clients should:
- Ignore unknown fields (forward-compatible)
- Check `v` and handle unknown versions gracefully (parse what you can)
- Future versions may add: HDR metadata, DRM info, chapter markers,
  GOP interval, codec-specific init data (SPS/PPS for H.264)

---

## Testing

To verify STREAMINFO is working, check the server log for:

```
MiniPlayer: STREAMINFO sent (XXX bytes), client ACK=0x07 video audio
```

If you see `ACK=0x00`, the client received but couldn't parse the JSON.
If you don't see the log line at all, the client doesn't advertise
the `STREAMINFO` capability.
