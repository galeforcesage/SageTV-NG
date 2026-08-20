# NG Server Video Enhancement — Client Protocol Contract

**Audience:** NG client teams (Android/Shield, Samsung Tizen, PWA)
**Status:** contract for the server-side Adaptive Server GPU Video Enhancement feature
**Related:** [NGClientCapabilities.md](NGClientCapabilities.md),
[NGBandwidthAdjustmentClientHandoff.md](NGBandwidthAdjustmentClientHandoff.md)

The server can deinterlace and upscale a live stream on its GPU before sending
it, so a 1080i broadcast can arrive at a 4K TV already deinterlaced and scaled
rather than leaving that work to the panel. This document is the complete list of
what a client must tell the server, and what the server will tell the client
back.

**Nothing here is mandatory.** Every field is optional, and every missing field
pushes the decision toward "don't enhance". A client that implements none of this
behaves exactly as it does today.

---

## 1. Why the server can't already do this

The server currently cannot tell a Shield on a 4K TV apart from a phone.

`DISPLAY_RESOLUTION` is queried today, but on Android it returns the **UI
framebuffer** size, which is routinely 1920x1080 on a Shield that is connected to
a 4K panel — the launcher renders at 1080p and the display scales. The server
stores it only as an advisory hint for that reason. `DISPLAY_REFRESH_RATES`,
`DISPLAY_HDR_TYPES` and `DEVICE_MODEL` are specced in
[NGClientCapabilities.md](NGClientCapabilities.md) but are never actually queried.

So the single most valuable thing a client can implement here is
§2.1: **the physical sink resolution**.

---

## 2. What the client reports

These are added to the NG-only capability round, guarded by the existing NG
session gate, so legacy clients never see them. Answer with an empty string if a
value is genuinely unknown — do **not** guess, because a wrong "yes" produces a
stream the device can't play, while a "don't know" just means no enhancement.

### 2.1 `DISPLAY_SINK_RESOLUTION` — the decisive one

```
DISPLAY_SINK_RESOLUTION  ->  "3840x2160"
```

The **physical resolution of the display the user is actually looking at**, not
the surface, window, or UI framebuffer size.

| Platform | Where to get it |
|---|---|
| Android / Shield | `Display.getMode().getPhysicalWidth()` / `getPhysicalHeight()` |
| Tizen | `webapis.productinfo.getRealResolution()` (falls back to panel model info) |
| PWA / desktop | `screen.width * devicePixelRatio` — report empty if uncertain |

A Shield rendering its UI at 1080p on a 4K TV **must report `3840x2160`**. This is
the field the entire feature turns on: without it the server has no honest reason
to build a 4K stream for anybody.

Accepted range is **640–7680 wide by 480–4320 high**. Anything outside it, or any
value that doesn't parse as `<int>x<int>`, is discarded and treated as *undeclared*
rather than clamped — a bogus sink size is the one input that could talk the server
into building a stream nothing can play. The separator may be `x` or `X`;
surrounding whitespace is tolerated.

### 2.2 `DISPLAY_REFRESH_RATES`

```
DISPLAY_REFRESH_RATES  ->  "60,59.94,50,24"
```

CSV of refresh rates the sink supports. Used to avoid cadence mismatch, and
because 60fps at 4K costs materially more GPU than 30fps — the server will
sometimes choose a lower tier for 60fps content on a constrained host.

### 2.3 `DISPLAY_HDR_TYPES`

```
DISPLAY_HDR_TYPES  ->  "HDR10,HLG"      (or "none")
```

Recorded now, acted on later. HDR passthrough and tone mapping are explicitly
future work; reporting this today simply means no protocol change is needed when
that lands.

### 2.4 `LOCAL_ENHANCEMENT` — the client's honest self-assessment

```
LOCAL_ENHANCEMENT  ->  "pref=auto;status=active"
```

| Key | Values | Meaning |
|---|---|---|
| `pref` | `auto` \| `local` \| `server` | what the user (or the client's default) prefers |
| `status` | `active` \| `available` \| `none` | is the device's own upscaler actually running |

This deliberately replaces the more tempting design of an `AI_CAPABLE` boolean or
an `UPSCALER_RATING` number. Those invite the server to keep a table of which
devices are "good", which is a device myth that goes stale the moment a firmware
update ships. Instead the client states what it is doing, the server states what
it can do, and the outcome telemetry settles which one actually looked better.

Guidance:

- A Shield with AI upscaling enabled should report `status=active`. The server
  will then prefer to leave the stream alone when direct play or remux is viable,
  because a local upscale of a pristine source usually beats a server upscale of a
  re-encoded one.
- A Tizen TV, or any client with no upscaler worth the name, should report
  `status=none`, which biases toward server enhancement.
- If the user explicitly picked a side, report it in `pref` and the server will
  honor it.

### 2.5 `QUALITY_HINT`

```
QUALITY_HINT  ->  "auto"    (or "quality" | "savings")
```

Already specced in [NGClientCapabilities.md](NGClientCapabilities.md) §8 but never
queried. Wire it up: `savings` pins the bitrate ladder to its floor, `quality`
allows the ceiling.

### 2.6 Per-surface output limits

Added as additional positional fields on the existing playback-surface
declaration, following the same additive pattern used for track access:

```
PLAYBACK_SURFACE_<id>_MAX_OUTPUT_WIDTH   ->  "3840"
PLAYBACK_SURFACE_<id>_MAX_OUTPUT_HEIGHT  ->  "2160"
PLAYBACK_SURFACE_<id>_MAX_FPS            ->  "60"
```

A surface must **prove** it can decode the output before the server will send it.
Declaring HEVC support is not sufficient on its own: plenty of decoders advertise
HEVC and top out at 1080p. Report the real `MediaCodec` /
`MediaCapabilities.decodingInfo()` limits for the codec the surface will use.

Exact gate semantics, because "partly declared" is the case client teams get wrong:

- **Width and height must both be > 0.** Declaring one without the other counts as
  undeclared, and an undeclared surface is never enhanced. Absent, empty,
  non-numeric and negative values all become 0.
- **`MAX_FPS` is optional.** Leaving it out does not block enhancement — geometry
  is the limit that actually breaks decoders in practice. When it *is* declared,
  a proposed output frame rate above it disqualifies the surface.
- These are positional fields **9, 10 and 11** on the existing per-surface reply,
  after the 2.1.0006 track-access fields. A client that stops at index 8 still
  parses cleanly; it just never gets enhancement.

---

## 3. What the server sends back

No new channel and no new message type. The existing effective-delivery token
gains one extra suffix, in the `;k=v` shape the format already carries for audio:

```
pull-xcode:<mode>:enhance;tier=2160p
```

Concretely, a surface whose delivery is `pull-xcode` with xcode mode `mp4h264`
receives `pull-xcode:mp4h264` today and `pull-xcode:mp4h264:enhance;tier=2160p`
when enhancement is active. `push` and `hls` bases are unchanged apart from the
same suffix; a bare `pull` base normalizes to `pull:direct` first.

| Pair | Values |
|---|---|
| `enhance` | present only when enhancement is active |
| `tier` | `deint` \| `1080p` \| `1440p` \| `2160p` |

Nothing else is added to this token by the enhancement path — in particular the
server does **not** append `vcodec`, `acodec` or `ac` here. Codec and channel
information continues to arrive by the routes it always did.

**Required client behavior: none.** Clients already map this token onto their
request. The only requirement is the one that was always there — tolerate
unrecognized `;k=v` pairs, and an extra `:`-separated segment, rather than failing
to parse the token. When no tier is active the token is byte-identical to what the
server sent before enhancement existed, so a client can ship §2 support and observe
no change until an admin clears both switches.

Clients that want to show something in a stream-info overlay can surface `tier`,
but no client is required to display or act on it.

---

## 4. Rules the server follows

Worth knowing, because they explain why enhancement sometimes doesn't happen:

0. **It is off, and then it is still off.** Two switches guard this:
   `playback/gpu_enhance/enabled` (default false) and
   `playback/gpu_enhance/dry_run` (default **true**). Turning the feature on
   only makes the server *log* what it would have done. Enhancement re-encodes
   nothing until an admin also clears dry-run. Clients can therefore implement
   §2 well before any server starts using it.

1. **Recordings always win.** If a tuner is recording — or is scheduled to start
   within the lookahead window — enhancement is refused or capped, regardless of
   GPU headroom or any user preference. No client-side setting can override this.
2. **Source floor: 720 lines.** Nothing below 720 lines of **height** is ever
   upscaled live. Note this is height, not width: DVD/SD at 720x480 does *not*
   qualify, because its 720 is the width. SD material is the domain of the offline
   AI upscale path. Interlaced SD can still receive `tier=deint`, since
   deinterlacing is not upscaling.
3. **Sink must be meaningfully bigger than source.** Roughly 1.5x source height.
   A 1080i source on a 1080p panel gets `tier=deint`, not an upscale.
4. **The GPU is shared.** The server budgets against *currently free* VRAM, so
   another application on the same GPU simply results in fewer or lower tiers. No
   enhancement resources are held while nothing is playing.
5. **Tier is fixed for the life of a stream.** Mid-stream adaptation changes
   bitrate only, using the existing rate-adjustment path, so there is no
   re-buffer. A new tier is chosen at the next channel or stream change. The one
   exception is recording distress, where enhancement may be torn down mid-stream
   to protect the capture.
6. **Outcomes feed back.** Sustained rebuffering reported through
   `BANDWIDTH_FEEDBACK_V1` causes the *next* stream for that client to start a
   tier lower. Keeping that feedback accurate is the most useful thing a client
   can do after §2.1.

### 4.1 Diagnosing "why am I not getting enhanced?"

Every decision is logged with a verdict. These are the ones a client controls:

| Verdict | What the client did |
|---|---|
| `offered` | enhancement was granted |
| `client did not report a sink resolution` | `DISPLAY_SINK_RESOLUTION` empty, unparseable, or outside 640×480–7680×4320 |
| `client surface cannot decode the enhanced output` | surface declared no `MAX_OUTPUT_WIDTH`+`MAX_OUTPUT_HEIGHT`, or the target exceeds them / `MAX_FPS` |
| `client's own upscaler is active and preferred` | `LOCAL_ENHANCEMENT` reported `status=active` |
| `client explicitly prefers local enhancement` | `LOCAL_ENHANCEMENT` reported `pref=local` |
| `sink is not meaningfully larger than the source` | sink height below ~1.5× source height — expected on a 1080p panel |
| `source below the 720-line floor and not interlaced` | source material, not a client fault |
| `source geometry unknown` | server could not determine source size |
| `feature disabled` / `ffmpeg/GPU cannot run the pipeline` | server-side, nothing the client can change |

The first four are the ones worth checking before reporting a bug: three of them
mean the client declined, and one means it never declared enough for the server to
say yes.

---

## 5. Implementation priority

If a client team implements only part of this, do it in this order:

1. **`DISPLAY_SINK_RESOLUTION`** — without it, nothing else matters; the server
   cannot justify enhancement for any client.
2. **Per-surface `MAX_OUTPUT_*` / `MAX_FPS`** — prevents sending a 4K stream to a
   decoder that will fail on it.
3. **`LOCAL_ENHANCEMENT`** — stops the server from duplicating work a Shield is
   already doing better.
4. **`DISPLAY_REFRESH_RATES`**, **`QUALITY_HINT`** — refinement.
5. **`DISPLAY_HDR_TYPES`** — forward-looking only.

Steps 1 and 2 alone are enough for the feature to work correctly end to end.

---

## 6. Server-side status

Implemented and merged (behavior-neutral until both switches are cleared):

- All §2 fields are queried in the NG capability round and parsed fail-closed.
- Per-surface indices 9–11 carry `MAX_OUTPUT_WIDTH`, `MAX_OUTPUT_HEIGHT`,
  `MAX_FPS`. Missing or unparseable values become 0 = undeclared = no upscale.
- The benefit gate (`EnhancementAdvisor`) and the capacity gate (`GpuGovernor`)
  are separate services, deliberately: "a spare NVENC session exists" is not a
  reason to re-encode a stream that already looked fine.
- The §3 token suffix is emitted.

Not yet wired: the enhancement pipeline is not attached to the push and
pull-xcode transcode branches, so the server currently logs its decisions and
sends today's stream. Client work in §2 is safe to start now — it is read by the
server immediately and simply improves the quality of what gets logged.
