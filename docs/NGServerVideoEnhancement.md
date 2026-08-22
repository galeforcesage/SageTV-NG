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

**Normative: this field is a measurement, not a decision.** A client MUST report
the true physical panel whenever it knows it. Empty means **"I don't know"** — it
never means "I don't want this". A client MUST NOT withhold, zero, or shrink this
value to express a preference, and MUST NOT inflate it to request a larger
stream; the reported number is the honest panel, and the server treats it as a
ceiling, never as a target.

**Withholding it does not refuse anything — it abstains.** An empty sink hands
the decision to the server, which then settles it from the client's declared
decode ceilings, the source, the admin ceiling, GPU capacity and the network
(§2.9.1). So a client that clears this field to avoid enhancement does not avoid
enhancement; it only removes the one input that would have capped the result at
its own panel size. The honest value is in the client's own interest.

That restriction is what makes the field usable at all. A measurement that is
sometimes a refusal cannot be read as either: an empty sink would then mean
"unknown" *or* "opted out" *or* "the client's own policy said no", three
conditions the server must handle differently and cannot tell apart. Preference
belongs in a field of its own — see §2.9, which documents the current
contradiction of this rule rather than a resolution to it.

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

**Note — `pref` has no value meaning "nobody".** The three values answer *which
side* should enhance, and every one of them presumes that enhancement is wanted:
`auto` defers, `local` claims it, `server` requests it. There is no way to say
"neither of you — leave the stream alone." This matters because it is the field
that would most naturally carry a user's opt-out: it is already sent
unconditionally, on every connection, and already has the shape of a preference
rather than a measurement. It is recorded here as an observation about the
existing contract, **not as a proposal** — and note that for the *bandwidth* axis
§2.5's `QUALITY_HINT=savings` already says most of it. Whether anything more is
needed, and where it would live, is an open question owned by the client teams.
See §2.9.1.

### 2.5 `QUALITY_HINT`

```
QUALITY_HINT  ->  "auto"    (or "quality" | "savings")
```

Already specced in [NGClientCapabilities.md](NGClientCapabilities.md) §8 but never
queried. Wire it up: `savings` pins the bitrate ladder to its floor, `quality`
allows the ceiling.

**This is the existing home for "spend less on me".** Of the reasons a client
might want to decline enhancement, the ones the server genuinely cannot see are
metered or capped data, thermal headroom and battery — and all of them are
fundamentally a statement about *cost*, which is what `savings` already says. It
is a preference field, not a measurement, and it is sent unconditionally. A
client that wants less should say so here rather than by withholding §2.1, which
does not mean that and (see §2.9.1) does not achieve it.

Wiring this up is therefore worth more than it looks: it is the only intent
channel in the contract that already exists, and it is currently ignored by both
sides.

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

### 2.9 The user's Auto / Always / Never override rides on the sink

Clients expose a user-facing setting for server upscaling, because the detection
behind §2.1 is not always right — most obviously on a phone in dock mode. There
is **no separate flag for this.** The override is expressed entirely through
`DISPLAY_SINK_RESOLUTION`, and the reported value is always the true physical
panel — never a fabricated 4K.

| Client setting | `DISPLAY_SINK_RESOLUTION` sent | Result |
|---|---|---|
| **Never** (off) | `""` | **Does not produce an opt-out.** An empty sink is an abstention (§2.9.1), so the server decides from the client's declared decode ceilings. |
| **Auto** | physical `WxH`, but only when the client judges itself eligible (TV-class, external/HDMI display, or an internal panel over 12"); otherwise `""` | Phone/small tablet → no upscale. Shield on a 4K TV → `3840x2160`. |
| **Always** (on) | honest physical `WxH`, unconditionally — a 6" phone sends `2400x1080` | Forces the invitation; server still clamps to `min(tier, sink)`. |

> **Status of this table: DESCRIPTIVE, NOT NORMATIVE.** It records how the Android
> client currently behaves, as reported by that client's team. It is not a
> specification, nothing was written against it, and no other client is obliged to
> match it. It is also **in direct conflict with the normative rule in §2.1**,
> which forbids using the sink to express a preference. That conflict is the
> subject of §2.9.1 and is not resolved in this document.

Two consequences the server honours **given the behaviour above**:

**Auto and Always are indistinguishable on the wire.** Both simply produce a
sink. So the server cannot apply different policy to them: *a sink that arrives
at all is a request to upscale, up to that size.* Under Auto the client has
already applied its own eligibility test, so second-guessing it server-side would
override a decision made with better information.

**The setting can never talk past the decode gate.** The per-codec rows of §2.7
report real MediaCodec limits and are sent unconditionally, unaffected by the
setting. A decode refusal is therefore always a hardware fact, never a
preference, and nothing the user can toggle should be able to override it.

**The docked-phone case needs no special server logic.** When a phone drives a
television, `Display.getMode()` reports the *television's* geometry, so the
client sends `3840x2160` and the normal path serves 4K. The form-factor rule
(§4 rule 5) additionally treats any sink too large to be a built-in handheld
panel as an external display, so `DEVICE_FORM_FACTOR=PHONE` does not refuse it.

**"Always" on a genuine handset is honoured, not inflated.** A 6" phone sending
`2400x1080` gets a 1080p-class upscale from a 720p source — the best its panel
can show. The server never invents a 4K target for a screen that can't display
one.

### 2.9.1 The sink field is overloaded, and that is the defect (OPEN)

`DISPLAY_SINK_RESOLUTION` is currently asked to carry two incompatible things:

| Section | What it says the field is |
|---|---|
| §2.1 | the physical panel — a **measurement**. Empty means *uncertain*. |
| §2.9 | the carrier for Auto/Always/Never — empty means *opted out*, or *the client's own eligibility test said no*. A **policy decision**. |

A field cannot be both a fact and an intent. Three distinct conditions —
"unknown", "user opted out", "client-side policy declined" — are pushed through
one value slot, so they arrive identical and the server cannot act on the
difference. **This is the root defect; the "Never vs. legacy" ambiguity below is
a symptom of it, not a separate problem.**

Concretely, an empty sink today means any of:

| Client | Sends | Means |
|---|---|---|
| implements this spec, user chose **Never** | `""` | "don't upscale for me" |
| implements this spec, user chose **Auto**, judged itself ineligible | `""` | "my own policy says don't bother" |
| genuinely does not know its panel size | `""` | "I can't measure this" |
| predates this spec entirely | `""` (absent) | "I've never heard of this question" |

**An absent value is an abstention, and the server decides.** This is the
governing rule, and it settles most of what follows. A field the client never
sent carries no opinion, so reading it as a refusal lets *silence* — the least
informative thing a client can do — veto a decision, and hands that decision to
the least informed party in the exchange. The server therefore does what is best
for perceived picture quality within its own capacity, using what the client
**did** state.

That is not a licence to guess. Nothing is fabricated: with no sink the server
skips the panel clamp and settles the tier from rules that are each independently
fail-closed — the §2.7 decode ceilings (which the client must have *affirmatively*
declared), the 720-line source floor, the admin ceiling, GPU admission, and the
network. A client that declared nothing therefore still receives nothing, which
is why this is safe to have on by default: **legacy clients are protected by the
decode gate, not by the sink check.** What is lost without a sink is only the
panel clamp, so the worst case is bandwidth spent upscaling for a smaller panel —
never an unplayable stream.

**Network overrides everything above it — as a design rule; see §4 rule 6a for
what is actually wired.** Measured throughput is not a preference to be weighed
against the others; a stream that cannot cross the link is not a better picture,
whatever the client asked for or the server decided. This is intended to apply
equally to `Always`, to an admin `Force`, and to any inference made here. It does
not constrain anything yet, because the pipeline re-encodes nothing yet.

Rows 1, 3 and 4 of the table therefore all resolve identically — *decide for me* —
and the server does not need to tell them apart, because it would act the same
way if it could. Row 2 is the one that costs something: there the client wanted a
sensible decision and pre-empted it, discarding a measurement the server needed
using a heuristic the server owns (item 4). That is the only case where the
overload makes the server reach a **worse** answer than it would with full
information.

**Why no existing field can disambiguate them.** The obvious idea is to read the
capability token — a client implementing the sink field advertises
`DISPLAY_SINK_V1` regardless of the user's setting, so its presence alongside an
empty sink looks like it should mean "deliberate opt-out". It fails twice:

1. **The collapse happens at the client, not the server.** Rows 1 and 2 above are
   already identical before the bytes leave. No server-side reading of any field
   can recover a distinction the client never encoded.
2. **The token tracks a different switch.** `DISPLAY_SINK_V1` is gated on the
   master `cap_display_sink_v1` toggle, not on Auto/Always/Never, so disabling
   that toggle produces a legacy-looking wire from a spec-aware client.

#### What has to be specified to close this

1. **§2.1 becomes unconditional and normative.** Always report the true physical
   panel when it is known; empty means *unknown*, never *unwanted*. This is now
   written into §2.1 and is the rule §2.9's table conflicts with.
2. **If a client genuinely needs to refuse, that needs a slot — but check that
   it does.** Because silence is now an abstention, the "Never" toggle as
   described in §2.9 no longer does anything: an empty sink invites the server
   to decide rather than telling it to stop. **That is a live defect in the
   client as shipped**, and it is worth settling whether the setting should
   exist at all before designing a wire field for it.
   - The server never needs to be told "no" in order to behave well. It needs
     to be told the *truth* — items 1 and 4 — after which it declines on the
     merits wherever declining is right. Refusal is only necessary for reasons
     the server genuinely cannot see.
   - Those reasons are real but narrow: metered or capped data, thermal
     headroom, battery. Note that the bandwidth axis is **already specced** —
     §2.5's `QUALITY_HINT=savings` is an existing preference field, sent
     unconditionally, and it is not queried today. Wiring up a field that
     already exists is a smaller and better-founded change than inventing one.
   - What `savings` cannot say is "do not re-encode my stream at all". Whether
     that is a requirement, and where it would ride, **is the client teams'
     decision. This document does not pick, and proposes no field** — an
     invented field no client sends is worse than an acknowledged gap, which is
     what the removal of `SUPPORTS_4K` cost us.
3. **Each setting must state its obligations across every relevant field**, not
   just the sink. "Auto" is currently defined only by what it does to one value;
   a usable specification says what a client asserting Auto guarantees about the
   sink, the decode ceilings, `LOCAL_ENHANCEMENT`, and the capability tokens
   together. Until that exists there is nothing for a client to comply with and
   nothing a server can validate.
4. **Client-side eligibility policy must be declared advisory, or moved to the
   server.** §2.9's Auto rule has the client decide whether a screen is worth
   enhancing ("TV-class, external/HDMI, or an internal panel over 12 inches").
   That is a server judgement, and the server already owns it via
   `playback/gpu_enhance/upscale_form_factors` and
   `playback/gpu_enhance/builtin_panel_max`. When the client pre-filters, the
   server never sees the sink it would have judged and **both admin settings
   become unreachable**. It also means every client team reimplements the
   12-inch heuristic independently, and they will drift.

**Implemented behaviour, as of this revision.** An empty sink is treated as an
abstention: the panel clamp is skipped and the tier is settled by the decode
ceilings, source floor, admin ceiling, GPU admission and network. The log line
reports `sinkKind=inferred` for this case, distinguishing it from a real panel
report (`builtin`/`external`). An installation that wants the older
read-silence-as-no behaviour sets
`playback/gpu_enhance/unknown_sink=refuse`, which restores
`verdict=UNKNOWN_SINK` and logs `sinkKind=none`.

Note this does **not** resolve items 2–4. The four meanings of an empty sink are
still indistinguishable; the server has simply stopped pretending that treating
them all as refusals was a decision rather than an accident.

#### Why deinterlace is offered regardless of the sink

The **deinterlace floor applies whatever the sink says**, including when it says
nothing: an interlaced source on a client with no declared panel is still offered
`tier=deint`. Deinterlacing is not upscaling — it removes comb artifacts that are
a defect at any resolution, costs a fraction of an upscale, and never changes the
frame size, so none of the sink-based reasoning above bears on it. It is also the
one tier that needs no decode ceiling, because it emits exactly the geometry the
client was already decoding.

Suppressing it on an empty sink would silently strip deinterlacing from **every
legacy client**, a regression inflicted on clients that never asked for anything.

Whether a client should be able to refuse even this is a policy question for the
client teams, and a weak one: deinterlacing has been a server-side encode
decision since long before this feature existed (`shouldAutoAddYadif()`), no
client has ever had a say in it, and the user-facing setting is described as
controlling *upscaling*. If the answer turns out to be "a client must be able to
refuse everything", that cannot be expressed until item 2 is resolved.

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

### 3.1 A refusal is invisible — `GPU_ENHANCE_REPORT_V1` (OPEN)

The token above reports only the **positive** case. When the tier is `NONE` — for
any reason, including a refusal the client could have fixed — the token is
byte-for-byte what a server without this feature would send. That property is
deliberate and is what makes §3 safe to ship, but it has a cost: **a client
cannot distinguish "the server declined, and here's why" from "this server has
no enhancement feature at all."**

This is not theoretical. On 2026-08-20 a PWA client produced all three of these
in the space of sixteen minutes, and received an **identical**
`pull-xcode:browserhd;acodec=aac;ac=6` token every time:

| Server's actual decision | What the client could observe |
|---|---|
| `tier=none verdict=SURFACE_CANNOT_DECODE` (client declared no decode ceiling) | nothing |
| `tier=enhance_1080p verdict=OFFERED` | nothing |
| `tier=deinterlace_only verdict=NO_VISIBLE_GAIN` | nothing |

The first row is the painful one: the client was one capability field away from
being served, and had no way to know. That diagnosis existed only in the server
log, which a client developer generally cannot read.

At least one client already advertises a `GPU_ENHANCE_REPORT_V1` capability in
`SAGETV_NG_CAPABILITIES`, evidently anticipating exactly this. **The server
currently implements nothing for it** — the string does not appear anywhere in
the server source.

**Why nothing already in this contract covers it.** Three candidate answers
exist, and all three fail:

1. **Infer it from the token's absence.** A client knows what it declared, so it
   could in principle treat "I declared a sink and a decode ceiling, yet got no
   `:enhance` suffix" as a refusal. This is wrong in a way that matters: the
   server runs a **dry-run mode** in which the advisor reaches a full positive
   decision and then deliberately returns `NONE` so the wire stays byte-identical
   (`EnhancementDryRun.java:155`). During dry-run — the current default, and the
   state all three measurements above were taken in — a client inferring from
   token absence would conclude it was misconfigured when the server had in fact
   decided in its favour. The inference is not merely incomplete; it is
   confidently wrong at exactly the moment a client developer would be relying
   on it.
2. **Read §4.1.** The diagnosis table is prose for a human reading a server log.
   It is not reachable by a running client and cannot drive a settings screen.
3. **Widen the §3 token to report refusals.** This would break the property that
   makes §3 safe: today a server with no tier active is byte-identical to a
   server without the feature, which is what lets clients ship §2 support and
   observe nothing until an admin opts in. Emitting refusal detail on the
   delivery token would change the wire for every client on every non-enhanced
   stream, including clients that never asked.

So the gap is real and cannot be closed with what is already specified.
**This document deliberately does not specify the replacement.** A field invented
server-side and never implemented by any client is worse than an acknowledged
gap — that is precisely what happened with `SUPPORTS_4K`, which was designed,
built, tested, deployed and then removed without a single client ever sending it.
`GPU_ENHANCE_REPORT_V1` is a name a client team has already chosen, so the shape
belongs to them.

What the server team needs in order to implement it:

1. the **property name** and the **value grammar** the client is prepared to read;
2. whether it carries only the verdict, or also the inputs the verdict was
   computed from (the server has `verdict`, `tier`, `sink`, `sinkKind`,
   `surfaceMax`, `codecMax` available at decision time and can expose any subset);
3. whether it is a `GetProperty` the server answers on demand, or a value pushed
   alongside the delivery token at OPENURL time;
4. what it should report while the server is in dry-run — the honest decision, or
   an explicit "evaluated only" state, since these are genuinely different
   situations and conflating them would reintroduce failure mode 1 above.

Until that is agreed, the §4.1 table plus the server log remain the only
diagnosis route.

---

## 3.2 What the client echoes back — `XCODE_SETUP <mode>:enhance;tier=<tier>`

The token in §3 is an *advertisement*; it does nothing on its own. The transcode
that actually runs is set up on the separate transcode socket, and the tier only
takes effect if the client **echoes it back** there.

When a client acts on a `pull-xcode:<mode>:enhance;tier=<t>` delivery token, the
pull bridge maps it 1:1 to `/msproxy?mode=<mode>:enhance;tier=<t>` and the mode
part is forwarded **verbatim** to the transcode socket. So the server receives
its usual `XCODE_SETUP <mode>;k=v;k=v…` with the enhancement carried exactly as
it appeared on the delivery token — the `:enhance` marker on the mode plus the
`;tier=<t>` pair, in the same `;k=v` shape already used for `acodec`, `ac`, `ss`:

```
XCODE_SETUP <mode>:enhance;tier=<tier>
```

- The `:enhance` suffix on the mode is the marker; the server strips it to recover
  the real copy-family `<mode>` (e.g. `mpeg2tsremux`) and treats the base mode
  exactly as it would without enhancement. A server that receives `<mode>:enhance`
  but does **not** strip the marker will not recognize the mode and will fall
  through to its default SD transcode — that is the failure mode this contract
  prevents.
- `<tier>` (on the `;tier=` pair) is the value taken verbatim from the delivery
  token: `deint`, `1080p`, `1440p`, or `2160p`. (`2160p` is the common case.)
- The marker + pair are **optional and additive**. A client that never sends them
  gets exactly today's behavior — the server enhances nothing it was not asked to.
- Sending them is only a **request**. The server re-checks every gate at transcode
  start — the dry-run interlock, that the mode is a copy-family *playback* mode
  (never an in-progress recording), and `GpuGovernor` admission (recording veto +
  live GPU capacity) — and silently runs the unenhanced command if any gate says
  no. A client therefore never has to reason about GPU load; it just relays the
  tier the server already offered.

This is deliberately the mirror of the audio-EQ precedent (`;afeq=` / `;afeqcodec=`):
the server resolves a decision, advertises it on the delivery token, and the client
reflects the relevant pieces back on `XCODE_SETUP` so the transcode socket — which
has no other view of the per-tune decision — can reconstruct it.

This is deliberately the mirror of the audio-EQ precedent (`;afeq=` / `;afeqcodec=`):
the server resolves a decision, advertises it on the delivery token, and the client
reflects the relevant pieces back on `XCODE_SETUP` so the transcode socket — which
has no other view of the per-tune decision — can reconstruct it.

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
   A 1080i source on a 1080p panel gets `tier=deint`, not an upscale. Only
   applied when a sink was actually reported; with none, rule 4's target-must-
   exceed-source check does the equivalent job.
4. **The target never exceeds the panel.** See §2.8 — the server picks the
   largest tier that fits the reported sink in both dimensions.
5. **Admins may restrict upscaling by form factor.**
   `playback/gpu_enhance/upscale_form_factors` is a CSV of eligible
   `DEVICE_FORM_FACTOR` values and is **empty by default**, meaning every device
   is eligible. An admin who decides a handheld is not worth an encoder session
   can set it to `tv`. Excluded devices still receive `tier=deint`, and a client
   that never reported a form factor is never excluded by it. A device whose
   sink is too large to be a built-in handheld panel is treated as driving an
   external display and is exempt — that is how a docked phone reaches 4K
   despite `DEVICE_FORM_FACTOR=PHONE`. See §2.9.
6. **A sink that arrives is a ceiling; a sink that doesn't is an abstention.**
   A present sink is both the invitation and the upper bound, and Auto and
   Always are indistinguishable on the wire by design. An **absent** sink is not
   a refusal: it says "no opinion", and the server decides from what the client
   did declare — the decode ceilings above all — subject to every other rule
   here. Nothing is fabricated, so a client that declared nothing gets nothing.
   Set `playback/gpu_enhance/unknown_sink=refuse` to read silence as "no"
   instead. See §2.9.1.
6a. **Network overrides all of it — and this is not a live-TV concern.** A stream
   that cannot cross the link is not a better picture, so bandwidth caps apply
   to `Always`, to an admin `Force`, and to any decision made in the absence of
   a sink.

   The adaptation machinery already exists and is **general to delivery, not
   specific to live**. Playing back a *recording* uses it too:
   `PlaybackDecisionEngine.decide(...)` takes `sourceBitrateKbps` and
   `availableBandwidthKbps`, applies `playback/bandwidth_safety_factor`
   (default 0.85, overridable per profile), and will refuse direct play and
   force a transcode-down when a recording's own bitrate exceeds the measured
   link. Alongside it: `setEstimatedBandwidth()` →
   `selectDynamicVideoBitrateKbps()` in the transcoder, `XCODE_ADJUST` on the
   pull proxy, the HTTPLS ladder rebuild, and `clampPolicy()` on recording copy
   transfers.

   **Enhancement now participates in it.** The advisor is handed the *same*
   `sourceBitrateKbps` and `availableBwKbps` the bandwidth-aware ranking above
   already used, so an enhanced stream is measured against the budget that
   sized the stream instead of spending headroom nothing accounted for. The
   projected bitrate per tier comes from `GpuEnhancePipeline.suggestBitrateKbps()`
   and must fit `link × safety factor`.

   Three properties of the gate are deliberate:
   - **It degrades, it does not veto.** The check sits inside the tier ladder
     next to the decode gate, so a constrained link steps 2160p → 1440p →
     1080p and only then to nothing. A smaller enhancement beats none.
   - **Deinterlace is exempt.** It emits roughly the stream the client was
     already being sent, so the existing rate machinery has already sized it.
     Only upscaling adds bits nothing budgeted for.
   - **An unmeasured link imposes no cap.** `0` means "not measured", never
     "measured as zero" — the same abstention rule as §2.9.1. A bandwidth probe
     that was skipped (NG direct-play skips it deliberately) is not evidence of
     a slow network.

   Inherits `playback/bandwidth_safety_factor` so a tuned link doesn't have to
   be tuned twice; `playback/gpu_enhance/bandwidth_safety_factor` overrides it
   for enhancement alone. Refusals log `verdict=INSUFFICIENT_BANDWIDTH`, and
   every decision logs `srcKbps=` and `linkKbps=<measured>/<after safety>`.

   Still open: the gate is evaluated at *decision* time only. Mid-stream
   degradation is `runtime-adapt`, and the case that will exercise it first is
   a high-bitrate recording played to a client whose link drops — not live TV.
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
| `client reported no sink and policy is to refuse rather than infer` | `DISPLAY_SINK_RESOLUTION` empty, unparseable, or outside 640×480–7680×4320 — **and** this server sets `playback/gpu_enhance/unknown_sink=refuse`. On a default server this verdict never appears: an absent sink is an abstention and the decision falls through to the decode gate instead (`sinkKind=inferred`) |
| `client cannot decode the enhanced output (no surface or codec proved it)` | neither §2.6 surface limits nor §2.7 codec ceilings permitted the target — declared nothing, target exceeds the declared ceiling, or every eligible codec was `decoder=sw` |
| `measured link cannot carry the enhanced stream` | every upscale tier projected above `linkKbps × safety factor`; check `linkKbps=` in the log. Not a client fault, and deinterlace is still offered. Applies to recordings as much as to live |
| `client's own upscaler is active and preferred` | `LOCAL_ENHANCEMENT` reported `status=active` || `client explicitly prefers local enhancement` | `LOCAL_ENHANCEMENT` reported `pref=local` |
| `sink is not meaningfully larger than the source` | sink height below ~1.5× source height — expected on a 1080p panel |
| `device form factor is not in the upscale-eligible set` | this server's admin restricted upscaling to certain `DEVICE_FORM_FACTOR` values, and the client's sink was not large enough to read as an external display; not a client fault, and deinterlace is still offered |
| `source below the 720-line floor and not interlaced` | source material, not a client fault |
| `source geometry unknown` | server could not determine source size |
| `feature disabled` / `ffmpeg/GPU cannot run the pipeline` | server-side, nothing the client can change |

The first four are the ones worth checking before reporting a bug: three of them
mean the client declined, and one means it never declared enough for the server to
say yes.

Note that on a default server, **omitting `DISPLAY_SINK_RESOLUTION` is not a way
to avoid enhancement** — it only removes the panel ceiling. The verdict a
sink-less client actually gets is decided by its decode ceilings, so a client
seeing an unexpected `offered` should look at §2.7, not §2.1.

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
4. **The Auto / Always / Never user setting.** **This item changed — see §7.**
   Do *not* implement "Never" by withholding `DISPLAY_SINK_RESOLUTION`. As of the
   server change described in §2.9.1, an absent sink is an abstention, so
   withholding no longer refuses anything; it only discards the ceiling that
   would have capped the target. Report the true panel under all three settings
   (§2.1) and express the user's cost preference through `QUALITY_HINT` (§2.5).
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
  sinkKind=external form=TV surface=<id> surfaceMax=3840x2160@60
  codecMax=<per-codec ceilings> srcKbps=8000 linkKbps=45000/38250
  local=auto/none -> tier=enhance_2160p verdict=OFFERED (offered)
```

That single line confirms the whole client contract: `sink=` proves
`DISPLAY_SINK_RESOLUTION` arrived and parsed, `surfaceMax=` proves the per-surface
limits arrived, `codecMax=` proves the §2.7 decode ceilings arrived, `local=`
proves `LOCAL_ENHANCEMENT` arrived, and `verdict=` says whether the server would
have accepted. `sink=0x0` or `surfaceMax=0x0@0` means the field never made it —
see the §4.1 table.

Three fields on that line are newer than the first round of client integration:

- **`sinkKind=`** — `external`, `builtin`, `inferred`, or `none`. `inferred` is
  the case where no sink arrived and the server fell back to your declared decode
  ceilings (§2.9.1). If you believe you are sending a sink and see `inferred`,
  the field is not arriving.
- **`srcKbps=` / `linkKbps=`** — the source bitrate and the measured link, the
  second shown as `measured/after-safety-factor`. `linkKbps=0` means the link was
  never measured, which imposes no cap (§4 rule 6a). NG direct-play clients
  deliberately skip the probe, so `0` is expected and correct there.
- **`verdict=INSUFFICIENT_BANDWIDTH`** — the enhanced stream did not fit the
  measured link at any tier. This blames the network, not your decoder.

What cannot be tested yet is the picture itself: no stream is re-encoded until the
pipeline is wired.

---

## 7. Revision log — what changed after the first client integration round

Everything in this section postdates the capability dumps taken from the live
Android MiniClient, PWA and Tizen clients. **A client team that implemented
against the earlier version of this document should read only this section**; the
rest of the document has already been rewritten to match, so §2 and §4 no longer
show what changed.

Four of these five items are corrections to *this document*, not to client code.
Three of them exist because the earlier text was wrong, and the live traffic is
what proved it.

### 7.1 `SUPPORTS_4K` never existed — remove it if you send it

**Removed. No client action required unless you implemented it.**

An earlier draft described a `SUPPORTS_4K` boolean. No client has ever sent it,
and the server no longer looks for it. It was specified from an assumption rather
than from a capability dump, and the dumps showed it absent everywhere. Use the
§2.7 per-codec decode ceilings, which real clients do send.

### 7.2 The user's 4K override now beats the server's inference

**Behaviour change, favourable to clients.**

Where a client reports a sink the server would otherwise have judged too small or
the wrong form factor, an explicit user setting is honoured rather than
second-guessed. The rationale is unchanged from §2.9: under Auto the client has
already applied an eligibility test using better information than the server has.

### 7.3 An absent `DISPLAY_SINK_RESOLUTION` is an ABSTENTION, not a refusal

**This is the one that breaks a shipped client behaviour. Read §2.1 and §2.9.1.**

The earlier document told client teams to implement the user's "Never" setting by
sending an empty `DISPLAY_SINK_RESOLUTION`. The server was written to match, and
treated an empty sink as a full opt-out.

That was wrong, and the reasoning is worth stating because it governs every other
optional field in this contract:

> A value a client never sent carries **no opinion**. Reading absence as refusal
> lets silence — the least informative thing a client can do — veto a decision,
> and hands that decision to the least informed party. Absence means *don't
> care*: the server should do what is best for perceived quality and server
> capacity.

The field is a **measurement** (§2.1), and a measurement cannot carry a refusal.
Empty now means *unknown*, never *unwanted*.

What this means for you, concretely:

| | Before | Now |
|---|---|---|
| Sink absent | no enhancement at all | server infers from your §2.7 decode ceilings; **the panel clamp is skipped** |
| Effect of withholding | refuses enhancement | only discards the ceiling that would have capped the target |
| "Never" toggle as shipped | worked | **non-functional** |

Withholding the sink no longer avoids enhancement — it removes the *ceiling*. That
is strictly worse for the user than sending the honest value, so the honest value
is now in your own interest.

This is not a hole in the safety story. The decode gate (§2.7) requires a client
to have **affirmatively** declared a ceiling before any upscale is offered, so a
legacy client that declares nothing still gets nothing. Legacy clients were always
protected by the decode gate, not by the sink check. What is lost without a sink
is only the panel clamp, so the worst case is wasted bandwidth, never an
unplayable stream.

Server operators can restore the old behaviour with
`playback/gpu_enhance/unknown_sink=refuse`. That switch exists as an escape hatch;
it is not the recommended configuration, and it defaults to `infer`.

**Where the intent should live instead.** §2.5 `QUALITY_HINT` already carries a
preference, is already sent unconditionally, and is currently queried by nobody.
The reasons a client legitimately cannot be overruled on — metered data, thermal
headroom, battery — are all statements about *cost*, which is exactly what
`QUALITY_HINT=savings` means. Wiring an existing field beats inventing one. The
client teams own that decision; the server is not asking to be told, and will not
require a new field.

### 7.4 Enhancement now answers to the measured link (§4 rule 6a)

**New gate. May change which tier you are offered; will not surprise you.**

Enhancement is now evaluated against the same source bitrate and measured
bandwidth the delivery ranking already used — deliberately the same numbers, so
enhancement is checked against the budget that sized the stream rather than
forming a second opinion. This applies to **recording playback and transfers as
well as live TV**; the bandwidth machinery was never live-specific.

Three properties matter to a client:

1. **It degrades, it does not veto.** The check runs inside the tier ladder, so a
   tight link steps 2160p to 1440p to 1080p before giving up.
2. **Deinterlace is exempt.** It emits roughly the stream you were already being
   sent, which the existing rate machinery already sized. A slow link must not
   strip the cheapest quality win.
3. **An unmeasured link imposes no cap** — the same abstention rule as §7.3.
   `linkKbps=0` means *not measured*, never *measured as zero*. NG direct-play
   deliberately skips the probe, so refusing on a skipped probe would disable
   enhancement for precisely the clients best able to use it.

New verdict `INSUFFICIENT_BANDWIDTH` (§4.1).

### 7.5 Still open, still owned by the client teams

Unchanged from earlier drafts, and still blocked on client input rather than on
server work:

- **`GPU_ENHANCE_REPORT_V1`** (§3.1) — advertised by at least one client,
  implemented by nothing on the server. The wire format is the client team's to
  define; the server will not guess it.
- **`DEVICE_FORM_FACTOR` instability** — one client reported `TV`, then `TABLET`,
  then `TV` across three consecutive capability rounds on the same physical
  device. This feeds an admin-facing eligibility knob, so the flapping is a live
  correctness risk. Whether the value is user-settable is still unanswered.