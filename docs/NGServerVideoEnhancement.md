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

### 2.7 Per-codec decode ceilings — the portable alternative to §2.6

**You do not have to implement §2.6.** Android `MediaCodec` and the browser's
`MediaCapabilities.decodingInfo()` both report decoder limits **per codec**, not
per "surface", so the per-codec channel is the one that exists on every platform.
The server treats it as fully equivalent evidence.

Put the ceilings on the video constraint rows for the player that will decode —
`EXO_VIDEO_CODECS` / `EXO_VIDEO_CONSTRAINTS` for the ExoPlayer path,
`IJK_VIDEO_CODECS` / `IJK_VIDEO_CONSTRAINTS` for IJKPlayer:

```
HEVC;scan=progressive;decoder=hw;maxW=3840;maxH=2160;maxFps=60;profiles=main:5.1,
H264;scan=progressive;decoder=hw;maxW=1920;maxH=1080;maxFps=60
```

The server reads whichever of the two properties you populate: a `*_VIDEO_CODECS`
value containing `;` is parsed as constraint rows, and a bare comma-separated codec
list is still treated as a plain codec list exactly as before. If a codec appears in
both properties, the `*_VIDEO_CONSTRAINTS` row wins.

Gate semantics, mirroring §2.6:

- A codec is eligible iff `maxW >= target_w`, `maxH >= target_h`, **and
  `decoder=hw`**. Enhancement is permitted when **at least one** codec is eligible,
  and the server picks the output codec from that eligible set (HEVC preferred).
- **`decoder=sw` is a hard block**, whatever geometry it claims. Software decode
  cannot sustain 4K in real time. `decoder` values other than `hw` — including an
  absent one — are refused for the same reason: an ambiguous answer about whose CPU
  is about to be spent is not a yes.
- Missing, empty or unparseable `maxW`/`maxH` counts as undeclared, and undeclared
  is never enhanced. `maxFps` is optional and enforced only when declared.
- **Attribute keys are case-insensitive** (`maxW`, `maxw` and `MAXW` are the same
  key), so you do not have to match the casing used above.

**§2.6 and §2.7 are OR'd, not AND'd.** Either one proving the geometry is enough;
they are two reports of the same underlying decoder. But if *neither* is declared,
the upscale is refused — listing a codec says nothing about the resolution its
decoder was built for.

### 2.8 The target is capped by your panel

The server never builds a picture larger than the sink you reported in §2.1, in
**either** dimension. It picks the largest tier that fits and stops there.

| Reported sink | Tier offered for a 1080p source | Why |
|---|---|---|
| `3840x2160` | 2160p | fits exactly |
| `2960x1848` (e.g. a 14.6" tablet) | **1440p** | 2160 lines don't fit in 1848 |
| `1920x2160` | 1080p | tall enough for 1440p, but only 1920 columns wide |
| `1920x1080` | none (deinterlace only, if interlaced) | no size gain to be had |

So a client that honestly reports a non-4K panel is not refused — it is served the
largest enhancement that panel can actually show, rather than a 4K stream it would
only spend power downscaling.

---

### 2.9 `SUPPORTS_4K` — the user's override, and it wins

Everything above is inference: EDID sensing, panel geometry, decoder capability
tables. All three are wrong often enough that clients now expose a user-facing
**4K support** setting. Report it verbatim.

| Value | Meaning |
|---|---|
| `auto` (or the field omitted) | Use the server's inference — §2.1 sink, §2.6/§2.7 ceilings, form factor |
| `yes` | This device can play 4K. Serve it. |
| `no` | Do not send 4K here. |

Accepted spellings for `yes`: `yes`, `true`, `1`, `on`, `supported`. For `no`:
`no`, `false`, `0`, `off`, `unsupported`. Anything else, including `auto`, reads
as "not answered" and falls back to inference. A client that never implements the
field is byte-for-byte a client answering `auto`.

**`yes` is authoritative, deliberately.** It:

- raises the effective sink to at least `3840x2160`, so a mis-sensed HDMI
  connection can't cap the target at the handset's own panel size;
- bypasses the form-factor restriction of §4 rule 3 entirely — **a phone that
  reports 4K support is served 4K**, even when an admin has narrowed upscaling to
  televisions;
- satisfies the §2.6/§2.7 decode gate on its own.

That last one is the sharp edge and it is intentional. The declared codec ceilings
come from the same auto-detection the user is overriding, so honouring them here
would make the override useless in precisely the situation it was built for — a
phone in desktop mode driving a television, where the handset's panel and the
decoder tables both describe the wrong screen. The decision is logged as
`uhd=forced` so that if the override is wrong, the unplayable stream has an
obvious cause and a one-setting fix.

**`no` is a ceiling, not a refusal.** It caps the target at 1440p rather than
disabling enhancement, because a user who knows 4K doesn't work on their setup
usually still wants the deinterlace and the upscale that does.

**What `yes` does *not* override.** It is a statement about the screen, so it only
overrules screen-shaped inferences. It cannot exceed the server's
`playback/gpu_enhance/max_height` ceiling, and it has no effect on the recording
veto, GPU admission, or the §A.1 source floor — none of which are about what the
client can display.

---

## 3. What the server sends back

No new channel and no new message type. The existing effective-delivery token
gains one extra suffix, in the `;k=v` shape the format already carries for audio:

```
pull-xcode:<mode>:enhance;tier=2160p
```

Concretely, a surface whose delivery is `pull-xcode` with xcode mode `dynamich264`
receives `pull-xcode:dynamich264` today and
`pull-xcode:dynamich264:enhance;tier=2160p` when enhancement is active. `push` and
`hls` bases are unchanged apart from the same suffix; a bare `pull` base normalizes
to `pull:direct` first.

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
4. **The target never exceeds the panel.** See §2.8 — the server picks the
   largest tier that fits the reported sink in both dimensions.
5. **Admins may restrict upscaling by form factor.**
   `playback/gpu_enhance/upscale_form_factors` is a CSV of eligible
   `DEVICE_FORM_FACTOR` values and is **empty by default**, meaning every device
   is eligible. An admin who decides a handheld is not worth an encoder session
   can set it to `tv`. Excluded devices still receive `tier=deint`, and a client
   that never reported a form factor is never excluded by it. A device reporting
   `SUPPORTS_4K=yes`, or sensed to be driving an external display, is exempt —
   see §2.9.
6. **The user's `SUPPORTS_4K` answer outranks the server's inference.** `yes`
   raises the sink to 4K, bypasses rule 5, and satisfies the decode gate; `no`
   caps the target at 1440p. Neither affects rules 1, 2 or 7 — those are not
   questions about the screen. See §2.9.
7. **The GPU is shared.** The server budgets against *currently free* VRAM, so
   another application on the same GPU simply results in fewer or lower tiers. No
   enhancement resources are held while nothing is playing.
8. **Tier is fixed for the life of a stream.** Mid-stream adaptation changes
   bitrate only, using the existing rate-adjustment path, so there is no
   re-buffer. A new tier is chosen at the next channel or stream change. The one
   exception is recording distress, where enhancement may be torn down mid-stream
   to protect the capture.
9. **Outcomes feed back.** Sustained rebuffering reported through
   `BANDWIDTH_FEEDBACK_V1` causes the *next* stream for that client to start a
   tier lower. Keeping that feedback accurate is the most useful thing a client
   can do after §2.1.

### 4.1 Diagnosing "why am I not getting enhanced?"

Every decision is logged with a verdict. These are the ones a client controls:

| Verdict | What the client did |
|---|---|
| `offered` | enhancement was granted |
| `client did not report a sink resolution` | `DISPLAY_SINK_RESOLUTION` empty, unparseable, or outside 640×480–7680×4320 |
| `client cannot decode the enhanced output (no surface or codec proved it)` | neither §2.6 surface limits nor §2.7 codec ceilings permitted the target — declared nothing, target exceeds the declared ceiling, or every eligible codec was `decoder=sw` |
| `client's own upscaler is active and preferred` | `LOCAL_ENHANCEMENT` reported `status=active` |
| `client explicitly prefers local enhancement` | `LOCAL_ENHANCEMENT` reported `pref=local` |
| `sink is not meaningfully larger than the source` | sink height below ~1.5× source height — expected on a 1080p panel |
| `device form factor is not in the upscale-eligible set` | this server's admin restricted upscaling to certain `DEVICE_FORM_FACTOR` values, and the client neither reported `SUPPORTS_4K=yes` nor a sink large enough to be an external display; not a client fault, and deinterlace is still offered |
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
2. **A decode ceiling — either §2.7 per-codec `maxW`/`maxH`/`decoder` (preferred,
   and portable across Android and the web) or §2.6 per-surface `MAX_OUTPUT_*`** —
   prevents sending a 4K stream to a decoder that will fail on it. Implement
   whichever one your platform already exposes; you do not need both.
3. **`LOCAL_ENHANCEMENT`** — stops the server from duplicating work a Shield is
   already doing better.
4. **`SUPPORTS_4K`** — cheap to implement (you already have the setting) and the
   only way a user can rescue a device the server mis-senses. Worth doing early
   on any platform with an HDMI-out or dock mode.
5. **`DISPLAY_REFRESH_RATES`**, **`QUALITY_HINT`** — refinement.
6. **`DISPLAY_HDR_TYPES`** — forward-looking only.

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

**A phase interlock enforces that.** Because the tier travels to the client in the
§3 token, going live before the pipeline can apply it would make the server
advertise an enhancement it never performed. So until the pipeline is wired,
clearing `playback/gpu_enhance/dry_run` is *not* sufficient: dry-run stays on, and
the server logs

```
GPU_ENHANCE INTERLOCK playback/gpu_enhance/dry_run is false, but the enhancement
pipeline is not wired to the transcode branches yet, so dry-run stays on.
```

once, so the setting is never silently ignored. `enhance;tier=` therefore cannot
appear on the wire yet, and any client seeing it is talking to a newer server.

### What a client team can test today

Everything in §2, which is the part that is easy to get wrong:

1. Set `playback/gpu_enhance/enabled=true` on the server (leave dry-run alone).
2. Tune from the client.
3. Read the decision:

```
GPU_ENHANCE DRYRUN client=<id> media=<mode> src=1920x1080i@30 sink=3840x2160
  surface=<id> surfaceMax=3840x2160@60 local=auto/none
  -> tier=enhance_2160p verdict=OFFERED (offered)
```

That single line confirms the whole client contract: `sink=` proves
`DISPLAY_SINK_RESOLUTION` arrived and parsed, `surfaceMax=` proves the per-surface
limits arrived, `local=` proves `LOCAL_ENHANCEMENT` arrived, and `verdict=` says
whether the server would have accepted. `sink=0x0` or `surfaceMax=0x0@0` means the
field never made it — see the §4.1 table.

What cannot be tested yet is the picture itself: no stream is re-encoded until the
pipeline is wired.
