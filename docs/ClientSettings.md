# Legacy Android Miniclient Settings — Recommended Configurations

These recommendations target the **legacy SageTV Android miniclient** (and other
"classic" placeshifter clients that expose the same six user-facing toggles).
They apply to the current server (no per-client `Sage.properties` edits
required) by leveraging the announce strings the client already sends from
its preferences screen.

If a row in the table doesn't match the toggle name in your client app build
exactly, use the column header — those map to the wire-level meaning the
server reacts to.

## The six client toggles

| # | Client UI setting | Allowed values | What it changes on the wire |
|---|---|---|---|
| 1 | **Transcoding preference** | when needed / always | Whether `FIXED_PUSH_MEDIA_FORMAT` is sent |
| 2 | **Container format** (push) | MKV / DVD (MPEG-PS) | `FIXED_PUSH_MEDIA_FORMAT.container` |
| 3 | **Audio codec** (push) | AC3 / MP2 | `FIXED_PUSH_MEDIA_FORMAT.audiocodec` |
| 4 | **Remuxing sequence** | when needed / always / off | Whether `FIXED_PUSH_REMUX_FORMAT` is sent + an always-flag |
| 5 | **Remuxing container** | MKV / MPEG-PS / MPEG-TS | `FIXED_PUSH_REMUX_FORMAT.container` |
| 6 | **ExoPlayer FFmpeg audio extension** | off / use if needed / preferred | Set of audio codecs the client claims (AC3/EAC3/DTS/TrueHD) |

## Decision rationale

- **HEVC decode**: every device listed below has hardware HEVC; we want the
  remux path to succeed for HEVC sources so we avoid burning CPU on transcode.
- **H.264 decode**: universal.
- **MPEG-2 decode**: not hardware on any modern Android — always transcoded by
  the server.
- **AC3 / EAC3 decode**:
  - Native HW: NVIDIA Shield (all variants), most Samsung Galaxy S- and
    Z-series, Galaxy Tab S-series.
  - Via ExoPlayer FFmpeg audio extension: everything else (works fine, costs
    a small amount of CPU on the client).
- **MPEG-TS vs MKV remux container**: MPEG-TS plays slightly better on cheap
  Android TV sticks (Chromecast / Onn 4K) because their decoder pipelines are
  TS-first. MKV is otherwise the safe default.
- **MP2**: only choose this if AC3 fails — it is universally decodable but
  stereo only and lower quality.

## Recommended settings by device

### Android TV (set-top)

| Device tier | Examples | 1 Transcode | 2 Push container | 3 Push audio | 4 Remux | 5 Remux container | 6 ExoFFmpeg audio |
|---|---|---|---|---|---|---|---|
| **Premium** | NVIDIA Shield, Shield Pro | when needed | MKV | AC3 | always | MPEG-TS | off |
| **Budget** | Chromecast w/ Google TV, Onn 4K | when needed | MKV | AC3 | when needed | MPEG-TS | use if needed |

Shields have full HW AC3/EAC3 + Dolby passthrough — no need for the FFmpeg
audio extension. Cheap sticks lack some audio HW; the extension covers them.
Both benefit from MPEG-TS remux because their MediaCodec stack handles TS
ingest more smoothly than MKV.

### Premium phones / foldables

| Device tier | Examples | 1 | 2 | 3 | 4 | 5 | 6 |
|---|---|---|---|---|---|---|---|
| **Premium phones** | Galaxy S Ultra, Pixel Pro | when needed | MKV | AC3 | when needed | MKV | use if needed |
| **Foldables — flagship** | Galaxy Z Fold | when needed | MKV | AC3 | when needed | MKV | use if needed |
| **Foldables — clamshell** | Galaxy Z Flip | when needed | MKV | AC3 | when needed | MKV | use if needed |

These devices have HW AC3 in most regions but firmware varies; leaving the
FFmpeg audio extension on **use if needed** keeps a fallback without forcing
software decode.

### Mid-range phones

| Device tier | Examples | 1 | 2 | 3 | 4 | 5 | 6 |
|---|---|---|---|---|---|---|---|
| **Mid** | Galaxy A3x/A5x, Pixel A-series | when needed | MKV | AC3 | when needed | MKV | use if needed |

Same as premium — these chipsets handle HEVC + AC3 fine. Stick with MKV
unless you observe stutter, in which case switch remux container to MPEG-TS.

### Budget phones

| Device tier | Examples | 1 | 2 | 3 | 4 | 5 | 6 |
|---|---|---|---|---|---|---|---|
| **Budget** | Galaxy A1x, Moto G | when needed | MKV | AC3 | when needed | MKV | **preferred** |

Some budget chips lack AC3 entirely. Setting the FFmpeg audio extension to
**preferred** forces ExoPlayer to use it for AC3/EAC3 even when claimed
"native". Only fall back to **MP2** (toggle 3) if AC3 still doesn't play
after switching the extension to preferred.

### Tablets

| Device tier | Examples | 1 | 2 | 3 | 4 | 5 | 6 |
|---|---|---|---|---|---|---|---|
| **Premium** | Galaxy Tab S-series | when needed | MKV | AC3 | always | MKV | off |
| **Mid** | Galaxy Tab S9 FE, Tab A-series | when needed | MKV | AC3 | when needed | MKV | use if needed |

Premium tabs (Tab S) get the same "always-remux" treatment as Shield because
their decoders are robust enough to handle direct-play; this preserves source
quality and saves server CPU.

## Quick troubleshooting

| Symptom | Try changing |
|---|---|
| Audio plays, video missing | Push container MKV → MPEG-PS, OR audio AC3 → MP2 (only as last resort) |
| Choppy HEVC playback | Remux sequence → **always**, container → MPEG-TS |
| No audio with AC3 source | ExoFFmpeg audio extension → **preferred** |
| Server CPU pegged at 100% | Transcoding preference → **when needed**, ensure remux is on |
| HEVC source forces transcode anyway | Remux sequence → **always**, container MKV |

## Why these settings keep `Sage.properties` clean

Each of the six toggles is sent in the client's announce, so the server's
`ClientProfileManager` / `PlaybackDecisionEngine` paths can act on them
without any persistent per-MAC override. The only entries you should ever
need in `Sage.properties` for a miniclient are the auto-managed auth slots —
no `miniclient/profile/<MAC>=…` lines are required when the client is
configured per the recommendations above.

## How the server adapts to your link

The six toggles above tell the server **what your device can play**.
The server then picks the best quality your **link** can carry, in real
time, without any further user input.

### Bandwidth — automatic bitrate clamp

While a stream is running the server measures the actual push throughput
to your client (rolling average, smoothed). When you've picked
**Transcoding preference = when needed** the FFmpeg target bitrate is
adjusted up or down every ~500 ms to stay just below the measured link
capacity — you never see the "buffering" spinner caused by a bitrate
ceiling set too high for the current Wi‑Fi.

When you've picked **Transcoding preference = always** (i.e. you sent a
`FIXED_PUSH_MEDIA_FORMAT` profile), the server still clamps the
transcoder bitrate **down** to fit the measured link if needed — but it
will never push the bitrate **above** the fixed profile's target. So the
profile remains the ceiling, the link remains the floor. (Controlled by
`transcoder/adapt_fixed_to_bw=true`, default on.)

### Latency — LAN gets raw, WAN gets remux

The server also tracks round-trip time on the UI channel. Two rules:

| RTT band | What changes | Why |
|---|---|---|
| **< 10 ms (LAN / wired / strong Wi‑Fi)** | Avoid transcoding whenever possible — push raw if the codec is in your set, even when your "Transcoding preference" says "always". Use container-only remux for anything that needs fixing. | A fat local link can carry the source bitrate directly. Skipping FFmpeg saves server CPU and preserves source quality. |
| **> 80 ms (cellular / VPN / weak Wi‑Fi)** | Force "remuxing sequence = always" regardless of your toggle. | Avoids the FFmpeg warm-up jitter that high-latency links can't hide. |

These are server-side overrides — you don't need to change anything in
the client when you move between Wi‑Fi and cellular. The server detects
the change from the next ping and adapts.

### When you'd flip a toggle yourself

The six toggles are still the right answer when:

- You're chasing a specific compatibility bug (e.g. AC3 doesn't decode
  on a particular cheap TV box → set audio = MP2).
- You want to *guarantee* a particular container for testing.
- You're on an exotic link (sat / 4G hotspot in a moving car) where the
  server's BW estimate is too unstable and you'd rather lock to a known
  conservative profile.

For everything else, follow the device-class table above once and let
the server handle the rest.
