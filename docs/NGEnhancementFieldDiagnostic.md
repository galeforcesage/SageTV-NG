# NG Enhancement — Field Diagnostic, 2026-08-21

Findings from live Shield + PWA sessions against a server running the fixed
enhancement hook. This is a punch list, split by who owns each item. It
supplements `NGServerVideoEnhancement.md`; it does not change the contract.

The headline: **the server now reaches a correct verdict, and reached
`tier=enhance_2160p verdict=OFFERED` for the first time.** Nothing upscaled,
because two deliberate gates still hold (see S1). Everything below is what
stands between that verdict and a picture.

---

## 1. What actually happened

Five client connections were observed. Grouping them by whether the NG
handshake completed is the single most useful cut of the data:

| Time  | `SAGETV_NG_VERSION` | Codec constraints | Sink reported | Enhancement verdict |
|-------|---------------------|-------------------|---------------|---------------------|
| 09:32 | `1.0.1`             | full              | `3840x2160`   | *(not evaluated — server bug, now fixed)* |
| 13:26 | **empty**           | `null`            | *(never asked)* | `SURFACE_CANNOT_DECODE` |
| 13:32 | **empty**           | `null`            | *(never asked)* | `SURFACE_CANNOT_DECODE` |
| 13:33 | `2.0.0` (PWA)       | n/a               | `0x0`         | `SURFACE_CANNOT_DECODE` |
| 13:41 | `1.0.1`             | full              | **`0x0`**     | **`OFFERED` / `enhance_2160p`** |

Two distinct Android faults are visible here, and they are easy to confuse:

* **13:26 and 13:32** — the app did not identify itself as an NG client at all.
* **13:41 vs 09:32** — the app *did* identify as NG both times, advertised
  `DISPLAY_SINK_V1` both times, and reported a real 4K panel only once.

Note also that the one connection that knew the panel (09:32) is the one where
the server never asked the question, and the connection that got the offer
(13:41) did not know the panel. No single session has yet produced a
fully-informed 4K decision.

---

## 2. Android client — A1 through A6

### A1. The NG handshake silently does not happen on some connections — **blocker**

On the 13:26 and 13:32 connections *every* NG property returned `null`,
including the app's own identity:

```
SAGETV_NG_VERSION=            SAGETV_NG_CAPABILITIES=null parsed=[]
CAP_SCHEMA_VERSION=null       CAP_PROFILE_ID=null       CAP_OVERRIDES=null
EXO_VIDEO_CODECS=null         EXO_VIDEO_CONSTRAINTS=null
```

while legacy properties on the *same* connection answered correctly
(`OPENURL_INIT=TRUE`, `GFX_SUBTITLES=TRUE`, `ZLIB_COMM=TRUE`,
`VIDEO_ADVANCED_ASPECT=Source`, and a fully populated `OFFLINE_CACHE_CONTENTS`).

So the socket is healthy and the property responder is registered — it simply
has no NG entries in its table on that connection. Because
`SAGETV_NG_VERSION` is empty, the server correctly concludes this is not an NG
session and **skips the entire NG capability round**, which is why those
sessions have no sink, no constraints, and no `enhancement caps` log line at
all. The server is behaving correctly here; there is nothing to fix on that
side.

This is the highest-priority item because it makes every other capability
non-deterministic. Right now whether a user gets enhancement depends on which
connection they happened to draw, which will be reported as a server bug and
is not one.

Most likely causes, in order: the NG property table is populated by an
initializer that races the server's capability burst; or a reconnect /
process-restore path builds a reduced property map; or a second connection
(UI vs media) is served by a different responder.

**Ask:** make NG identity synchronous and unconditional before the socket
accepts property queries. If the NG table cannot be ready in time, it is far
better to delay the reply than to answer `null` — a `null` here is
indistinguishable from "I am a legacy client", and the server must treat it as
such.

### A2. `SAGETV_NG_CLIENT_ID` is always empty — **easy, high diagnostic value**

Empty on *every* Android connection, including the two successful NG
handshakes. The PWA sends `PWA-<id>` correctly. Consequence: every server-side
decision logs `client=-`, so decisions cannot be correlated back to a device.
This is why the first pass of this investigation took as long as it did.

### A3. `DISPLAY_SINK_RESOLUTION` is inconsistent between good sessions — **blocker for correct 4K**

`3840x2160` at 09:32 and `0x0` at 13:41, both with `DISPLAY_SINK_V1`
advertised and the NG handshake complete. `DISPLAY_REFRESH_RATES` and
`DISPLAY_HDR_TYPES` degraded identically (full lists, then empty).

Per the contract, an absent sink means *unknown*, and the server is entitled to
proceed on policy. It did: `sinkKind=inferred`, and it offered 2160p from the
admin ceiling. That is the specified behaviour, but it means **the 4K decision
rested on policy rather than on knowledge of the panel**. On a 1080p TV the
same code path would manufacture 4K that the panel then discards.

Suspected cause: querying the display before the presentation surface is
attached (`Display.getMode()` on the wrong `Display`, or before the activity
is resumed). §2.1 requires the true physical panel whenever it is known.

### A4. `DEVICE_FORM_FACTOR` is always empty

Empty on every connection observed today; earlier sessions were seen flapping
`TV -> TABLET -> TV`. Currently harmless — an empty form factor is treated as
"no opinion" and does not exclude anything — but it makes the two admin
form-factor knobs unreachable, and a flapping value would make decisions
unstable across reconnects.

### A5. `CLIENT_PLATFORM`, `PLAYER_ENGINE`, `DISPLAY_RESOLUTION` are never sent

Empty/`null` on every connection including good NG ones. Not currently blocking
enhancement. Please either implement them or say they are out of scope so they
can be removed from the expected set.

### A6. `PLAYBACK_SURFACES` is answered `null`, not empty — **informational**

Confirmed directly now that the server logs the raw reply:

```
MiniClient PLAYBACK_SURFACES=<null: client did not answer the query> parsedIds=[]
```

This is **fine** and needs no work — the client does not advertise a surfaces
capability, and the server has been fixed to evaluate enhancement on the legacy
path regardless. Recorded only so nobody re-diagnoses it. If surfaces are ever
implemented, note the PWA's mistake in P1 below.

---

## 3. Server — S1 through S4

### S1. The decision is not wired to ffmpeg — **the blocker for any visible change**

`verdict=OFFERED` is a decision, not an action. Two independent gates hold:
`playback/gpu_enhance/dry_run=true`, and the `PIPELINE_WIRED=false` interlock
which keeps dry-run in force even if the first is cleared. Nothing reaches the
GPU, and the delivery token is byte-identical to an unenhanced session.

Remaining work: inject the scale/deinterlace filter chain and
`-hwaccel_output_format cuda` through the argv-rewrite chain, leaving audio
untouched. Until this lands, no client-side fix can produce a picture change.

### S2. Recording integrity gate must land before the interlock is cleared

Invariant 0: recordings are never affected. This needs to be an enforced,
tested gate rather than a property of the current code, before anything can go
live.

### S3. Log the reason NG detection failed

Today a non-NG session is diagnosable only by recognising a wall of `null`s.
One explicit line — NG detection failed, client reported no `SAGETV_NG_VERSION`,
treating as legacy — would have identified A1 immediately.

### S4. Fall back to the connection id when the client sends no NG client id

Independent of A2: the server should not log `client=-` when it has a perfectly
good connection identifier available. Diagnosis should not depend on the client
getting its own identity right.

---

## 4. PWA — P1

`PLAYBACK_SURFACES=pwa_native,pwa_mse` parsed correctly, with sensible codec
and container lists. Both surfaces were nevertheless refused, because both
declared `maxOutput=0x0 maxFps=0`.

A surface that declares no output ceiling cannot be proven able to decode
enhanced output, and the server fails closed rather than guessing. Populate
`PLAYBACK_SURFACE_<id>_MAX_OUTPUT_WIDTH` / `_MAX_OUTPUT_HEIGHT` / `_MAX_FPS`
(§2.7) and this surface becomes eligible. The PWA also reported `sink=0x0`
(see A3).

---

## 5. Suggested order

1. **A1** — until NG identity is reliable, every other result is a coin flip.
2. **S1** — nothing is visible on screen until the pipeline is wired.
3. **A3** — required before a 4K decision is evidence-based rather than policy-based.
4. **A2 / S3 / S4** — cheap, and they make everything after this faster to diagnose.
5. **S2** — must precede clearing the interlock.
6. **A4, A5, P1** — as scheduling allows.