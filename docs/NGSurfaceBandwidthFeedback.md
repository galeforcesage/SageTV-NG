# NG Surface Bandwidth Feedback & WAN Cold-Start Seed

**Audience:** PWA client / bridge team **and** NG Android client team
**Status:** Server-side **implemented and live**. Additive, fail-open, awaiting
client adoption. A client that changes nothing keeps working exactly as today.

## TL;DR

Two additive, independent extensions for the managed-transcode WAN path
(`pull-xcode:browserhd` over `/msproxy`):

1. A new **per-surface attribute** that declares whether a surface can feed live
   delivered-goodput back to the server for a managed transcode:

   ```
   PLAYBACK_SURFACE_<id>_BANDWIDTH_FEEDBACK = xcode_adjust | none      (default: none)
   ```

2. A new **optional `XCODE_SETUP` seed** that lets the client/bridge hand the
   server a conservative initial link estimate so a WAN transcode does not launch
   at the full resolution anchor and die before the first live measurement:

   ```
   XCODE_SETUP browserhd;bw=<kbps>;ss=<ms>;...        (bw optional, ';'-delimited)
   ```

Neither is a route selector. `BANDWIDTH_FEEDBACK` is a **ranking tie-breaker +
telemetry hint**; `;bw=` only conditions the **startup bitrate**. Live
adaptation itself continues to run over `XCODE_ADJUST` exactly as before.

Two further **server-side** behaviors (no new client wire tokens) round out the
WAN path and are covered in §3–§4:

3. **Native HLS routing** — an honestly-advertised `DELIVERY_MODES=…,hls` surface
   (Safari/WebKit) is delivered native CMAF HLS for conditioned content instead of
   being pulled into the `/msproxy` MSE bridge.
4. **Server-side `XCODE_ADJUST` policy** — the bridge reports raw goodput; the
   server owns reserve + dead-band + fast-down/slow-up + ceiling.

> ### ⚠️ Name disambiguation — read this first
> This is **NOT** the `BANDWIDTH_FEEDBACK_V1` capability from
> `NGBandwidthAdjustmentClientHandoff.md`. Those are two different mechanisms:
>
> | | `BANDWIDTH_FEEDBACK_V1` capability | `PLAYBACK_SURFACE_<id>_BANDWIDTH_FEEDBACK` (this doc) |
> |---|---|---|
> | What | A device signal: client answers `GetProperty SAGETV_NG_BANDWIDTH_FEEDBACK_V1` with `kbps=…;seq=…` | A per-surface routing/telemetry hint: `xcode_adjust` or `none` |
> | Advertised via | `SAGETV_NG_CAPABILITIES=BANDWIDTH_FEEDBACK_V1` | `PLAYBACK_SURFACE_<id>_*` property set |
> | Used for | Startup + live push-path bitrate estimation | Tie-breaking equivalent surfaces on the `pull-xcode` WAN route |
>
> A client may implement either, both, or neither. They do not interact.

## Why this exists

The NG **surface** engine (`PLAYBACK_SURFACES` + `PLAYBACK_SURFACE_<id>_*`) is the
path that decides playback for surface-advertising clients, and `pull-xcode:<mode>`
maps 1:1 to the bridge's `/msproxy?mode=<mode>`. On that path the bridge measures
downstream goodput and drives live adaptation by sending `XCODE_ADJUST <kbps>` on
the same MediaServer control connection.

Two gaps remained:

- **No cold-start estimate.** An *unmetered* WAN `browserhd` session
  (`linkKbps==0`, because the server has no LAN-style link budget for a bridge
  connection) launched at the full resolution anchor (e.g. ~14 Mbps for 1080p
  high-motion). Over a cold VPN that overran and the client could die before the
  first ~5 s goodput sample produced an `XCODE_ADJUST`.
- **No surface-level way to say "I can feed goodput back."** The server had no
  declared signal to prefer a feedback-capable surface when two otherwise-equal
  surfaces competed on the WAN transcode route, nor to flag a missing feedback
  beacon as diagnosable rather than silent.

## 1. Surface attribute — `BANDWIDTH_FEEDBACK`

### Wire format

```
PLAYBACK_SURFACE_<id>_BANDWIDTH_FEEDBACK = xcode_adjust | none
```

- `xcode_adjust` — this surface's transport carries a control channel the
  client/bridge uses to send `XCODE_ADJUST <kbps>` (a `pull-xcode` browserhd
  session over `/msproxy`). **Only Chromium/Firefox MSE surfaces set this.**
- `none` (default) — no reliable delivered-goodput signal (Safari WebKit native
  HLS exposes none to JS), no encoder in the loop (direct-play), or adaptation
  handled elsewhere (the server-side push estimator). This is the safe default
  and how every silent/legacy surface is treated.
- Missing or unrecognized value → `none`, with a server-side `WARN`. A bad hint
  never fabricates an `XCODE_ADJUST` loop.

### Server behavior — tie-breaker only

The surface ranking order is unchanged except for one appended discriminator:

1. decision tier
2. client-declared `PRIORITY`
3. server CPU cost proxy
4. **`BANDWIDTH_FEEDBACK` — on the `pull-xcode` WAN route only, prefer `xcode_adjust`**
5. deterministic id order

Step 4 fires **only** among surfaces already tied on tier/priority/cost, and
**only** when the chosen delivery mode is `pull-xcode`. It never reorders across
tier, priority, or cost; it never moves Safari off native HLS or Tizen off
direct/AVPlay; and it never selects an incompatible surface merely because it
supports feedback.

### A new GetProperty round-trip

Unlike the per-container transport attributes (which ride inside the existing
`CONTAINERS` reply), `BANDWIDTH_FEEDBACK` is fetched with its own
`GetProperty PLAYBACK_SURFACE_<id>_BANDWIDTH_FEEDBACK` during surface discovery.
The server also began fetching the sibling `PLAYBACK_SURFACE_<id>_AUDIO_MAX_CHANNELS`
in the same round to keep the reply positions aligned. A client that does not
recognize either property returns empty and is treated as the default
(`none` / undeclared) — no failure.

## 2. `XCODE_SETUP` seed — `;bw=<kbps>`

### Wire format

```
XCODE_SETUP <mode>;bw=<kbps>          (all params optional, ';'-delimited,
                                       same grammar as ;acodec= ;ac= ;ss= ;sink=)
```

`bw` is the client's/bridge's **seeded initial link estimate** in kbps. It is used
**only** to pick a conservative cold-start bitrate.

- Parsed as a positive integer; a non-numeric or absent value is ignored and
  **never rejects `XCODE_SETUP`**.
- When absent, the server falls back to the `media_server/wan_coldstart_kbps`
  property (**default `5000`**).

### Server behavior — startup bitrate only

For an **unmetered WAN/bridge pull-xcode `browserhd`** session (`linkKbps==0`),
gated by `media_server/wan_coldstart_cap` (**default on**), the server:

1. Computes the normal resolution-anchored bitrate plan.
2. Computes a second plan at the seed (`bw`, else `wan_coldstart_kbps`).
3. Replaces **only the launch video target** with the lower seeded target, while
   **keeping the plan's high `maxrate`/`bufsize` ceiling** so live `XCODE_ADJUST`
   can ramp straight back up to the policy envelope.

The startup log changes from, e.g.:

```
browserhd BitratePolicy cap: ... linkKbps=0 -> Plan[target=14000k maxrate=... bufsize=...]
```

to:

```
browserhd WAN cold-start: launch b:v 14000k -> 3500k (seed=5000kbps); ceiling kept maxrate=... for XCODE_ADJUST ramp
```

Recommended initial video target is ~70–80% of the seed after audio + protocol
margin (a 5 Mbps seed starts ~3.5–4 Mbps video, not 14 Mbps). Metered sessions
(`linkKbps>0`) and every non-`browserhd` path (LAN push, direct play) are
untouched.

> **Cold-start coverage (as deployed).** The `;bw=` setup seed is what covers
> cold start until the first ~5 s goodput sample arrives. The bridge does **not**
> currently emit an immediate seeded `XCODE_ADJUST`; an immediate seeded adjust
> is an optional future optimization, not a requirement.

## 3. Native HLS routing for Safari/WebKit — `DELIVERY_MODES=…,hls`

A surface may advertise the canonical delivery mode `hls` (already in
`CANONICAL_DELIVERY_MODES`). A client advertises it **only** when it genuinely
consumes server-produced CMAF HLS natively — today that is **Apple
WebKit/Safari**, gated client-side to real native-HLS support. Blink, Gecko and
Tizen do **not** advertise `hls` and are unchanged.

### Server behavior — native HLS wins over the MSE bridge

For a **non-direct** (conditioned: REMUX / AUDIO_TRANSCODE / TRANSCODE) decision,
the server now delivers **native `hls` first** when the surface declares it,
ahead of the `pull-xcode` MSE bridge. This is deliberate: Safari's MSE is
unreliable, so an honestly-advertised native-HLS surface must **not** be pulled
into `/msproxy`. Two coordinated changes make this robust:

1. **Within a surface** (`pickDeliveryModeForDecision`): non-direct preference is
   now `hls → pull-xcode → push`. A surface without `hls` (Blink/Gecko/Tizen)
   falls straight through to the unchanged `pull-xcode`-first order.
2. **Across surfaces** (`deliveryModeCpuRank`): native `hls` now ranks **above**
   the MSE bridge modes (`pull` `0` < `hls` `1` < `pull-xcode` `2` < `push` `3`),
   so if a client ever co-advertises both a native-HLS surface and a `pull-xcode`
   surface at equal tier/priority, the native-HLS surface wins the tie instead of
   being undercut by the cheaper-CPU bridge mode.

**Direct play is untouched:** a stream Safari can direct-play still routes over
native `pull` (the direct-play preference stays `pull → push → hls`); advertising
`hls` never forces segmentation onto direct-playable content.

## 4. Server-side `XCODE_ADJUST` bitrate policy

Per the "client owns facts, server owns policy" contract, the pull bridge reports
**raw measured delivered goodput** every ~5 s and applies **no** ramp policy of
its own. **All** smoothing/hysteresis/ramp/ceiling policy is server-side, gated by
`media_server/xcode_adjust_server_policy` (**default on**). On each sample the
server derives the applied video target from the raw goodput:

1. **Reserve margin** — candidate = `goodput × videoFraction`
   (`xcode_adjust_video_reserve_pct`, default **80%**), reserving audio +
   container/protocol overhead (same principle as the `;bw=` cold-start).
2. **Dead-band** — a candidate within ±`xcode_adjust_deadband_pct` (default **5%**)
   of the current applied target is ignored (no oscillation churn).
3. **Fast-down** — a candidate below current is applied **immediately** (protect
   against stalls the instant capacity falls).
4. **Slow-up** — a candidate above current rises by at most
   `xcode_adjust_up_step_pct` (default **+15%**) of current per tick, never
   jumping straight to the candidate.
5. **Clamp** — bounded to `[xcode_adjust_min_kbps, session policy ceiling]`; the
   handler returns the **actual applied** bitrate.

Setting `xcode_adjust_server_policy=false` restores the legacy path (clamp the raw
value to the ceiling and apply it verbatim, no smoothing). The policy math is a
pure function (`FFMPEGTranscoder.computeGoodputAdjustedVideoKbps`) with unit
coverage.

## 5. Corrupt-packet / timestamp-discontinuity resilience (fragmented-MP4 pulls)

The failures above are about *bandwidth*; this section is about *media integrity*
on the same WAN pull paths. Off-air DVR content routinely carries a corrupt
MPEG-2/AC-3 packet or a timestamp discontinuity from a brief signal dropout,
typically surfacing right after a mid-recording seek. Two symptoms were observed
live and are both server-side, not bandwidth or routing:

- **Native CMAF stall** — a source discontinuity stalls the AAC encoder's input,
  so a fragment/segment never finalizes (`CMAF part N not available; abort`,
  `DEMUXER_ERROR_COULD_NOT_PARSE`).
- **`browserhd` invalid sample** — a corrupt packet decodes into a broken frame
  that the H.264 re-encode passes on to the browser, which the MSE stack refuses
  (`CHUNK_DEMUXER_ERROR_APPEND_FAILED` / "Failed to prepare video sample for
  decode"), tearing the connection down mid-fragment.

Two additive, live-tunable server hardenings (no client wire changes):

1. **Demuxer error-resilience on the `browserhd` pull path.** The
   `-err_detect ignore_err -fflags +discardcorrupt` input flags — long proven on
   the native CMAF/HLS path — now also apply to the browser/PWA MSE fragmented-MP4
   pull (`browserhd`, `-f mp4` streamed to stdout), which previously received
   none. Corrupt packets are dropped at the demuxer so they never decode into a
   broken frame. Gated by `httpls/input_error_resilience` (**default on**).
2. **Audio-continuity floor across a discontinuity.** For the fragmented-MP4
   **re-encode** pulls (native CMAF + `browserhd`), the audio resampler's async
   value is floored to a working value (default **1000**) so a *timestamp
   discontinuity* — whose packets are valid and therefore not dropped by
   `+discardcorrupt` — is absorbed by `aresample` (adds/drops samples to keep the
   AAC track contiguous). The muxer then always has interleavable audio and the
   fragment finalizes instead of wedging. `async=1` (the legacy fixed-rate
   placeshifter default) only corrected the initial offset, not an ongoing gap.
   Mirrors the existing video-copy floor; only raises a sub-floor value, and a
   higher operator-configured async is respected. Gated by
   `ffmpeg/browserpull_aresample_async_floor_enabled` (**default on**).

IDR-led fragments after a seek are already guaranteed on these paths: the
`browserhd`/CMAF re-encode starts a fresh encoder (first output frame is an IDR),
and CMAF re-anchors the muxer clock with `-output_ts_offset` across a
seek-relaunch (see §"Server-side references").

## Per-client guidance

| Client | `BANDWIDTH_FEEDBACK` | `;bw=` | Notes |
|---|---|---|---|
| **Chromium / Firefox (PWA MSE)** | `xcode_adjust` | send a seed (FF may omit — then `wan_coldstart_kbps` applies) | The one path all of this targets: `pull-xcode:browserhd` over `/msproxy`, live `XCODE_ADJUST`. |
| **Safari (PWA)** | `none` | n/a | Advertises native `hls` in `DELIVERY_MODES` (gated to WebKit/iOS native-HLS support). The server routes it to **native CMAF HLS** for conditioned content and native `pull` for direct-play — never into the MSE bridge (see §3). WebKit exposes no reliable JS byte stats, so feedback stays `none`. |
| **Tizen** | `none` | n/a | **Almost always LAN-attached**, direct/AVPlay, no re-encode — LAN goodput is not the bottleneck, so `none` is correct and penalty-free. The tie-breaker only fires on `pull-xcode`, so a Tizen `none` is never demoted. For a Tizen `xcode:*` mode the client applies its own 50 Mbps cold-start ceiling (still advertising feedback `none`). |
| **Android NG (ExoPlayer)** | `none` | n/a | Server-side **push** transcode already self-adapts via the classic placeshifter estimator (`dynamicVideoRateAdjust`). No `pull-xcode`/browser-goodput transport → this feature is irrelevant; no changes required. |
| **Legacy / classic extender** | *(absent → none)* | *(absent)* | Advertises no surfaces → legacy V1/V2 negotiation runs unchanged. |

Any LAN-attached surface (including a wired Chromium box) correctly reports
`none`: without a constrained WAN link there is nothing for the tie-breaker to
prefer, and the cold-start cap only engages on an unmetered bridge pull-xcode
session.

## Current adoption status & known gaps (2026-09-20)

The **core deployed bandwidth loop** matches this contract:

- Chromium/Firefox MSE advertise `BANDWIDTH_FEEDBACK=xcode_adjust`; Safari, iOS
  and Tizen advertise `none`.
- Chromium sends `bw=<navigator.connection.downlink>`; Firefox omits `bw` when the
  Network Information API is unavailable (server then uses `wan_coldstart_kbps`).
- The bridge forwards the seed as `XCODE_SETUP …;bw=`, measures delivered bytes
  every ~5 s, and sends `XCODE_ADJUST` on the **same** MediaServer connection.
- Tizen direct/remux stays unmetered (no `bw`).

Divergences a client team should not assume away:

- **Adaptation strategy is server-side, not in the bridge.** The bridge reports
  **raw measured goodput**; the server owns the applied bitrate. Reserve margin,
  dead-band, fast-down/slow-up smoothing and hysteresis, and the ceiling clamp are
  now **implemented server-side** (§4), gated by
  `media_server/xcode_adjust_server_policy` (default on). The bridge does **no**
  ramp policy of its own.
- **No immediate seeded `XCODE_ADJUST`.** The `;bw=` seed alone covers cold start
  until the first ~5 s sample.
- **Safari/iOS advertise native `hls`.** The server honors it and routes Safari to
  native CMAF HLS for conditioned content (§3); it is not pulled into `/msproxy`.
- **Tizen `xcode:*` cold start** uses a client-side 50 Mbps ceiling (feedback still
  `none`).

Resolved since the first draft:

- **`CAP_EFFECTIVE_SURFACE` preserved through `/msproxy`.** The PWA previously
  cleared the server-selected effective surface immediately before `/msproxy`
  playback began; the client team fixed this — the selected surface is now
  captured before per-request state is cleared and passed intact into
  `loadMsProxy()`.

## Compatibility & rollout

- **Additive:** clients that send neither token get today's exact behavior. The
  same "silent client / legacy client gets safe defaults" pattern used across NG.
- **Fail-open everywhere:** unknown `BANDWIDTH_FEEDBACK` → `none` (WARN);
  malformed/absent `;bw=` → `wan_coldstart_kbps` default; neither rejects the
  client or the transcode.
- **Scoped:** the cold-start cap applies only to unmetered `browserhd`
  pull-xcode sessions; the tie-breaker only to the `pull-xcode` route. Legacy
  push clients keep the push estimator and are untouched.
- **Verify:** the parsed surface is logged at decision time
  (`PlaybackSurface[... bwFeedback=xcode_adjust ...]`); the cold-start decision
  logs the `browserhd WAN cold-start: launch b:v ...` line above.

### Acceptance (Chromium)

```
PLAYBACK_SURFACE_pwa_mse_BANDWIDTH_FEEDBACK = xcode_adjust
XCODE_SETUP browserhd;bw=<nonzero>
browserhd WAN cold-start: launch b:v ...k -> ...k (seed=<same>kbps)
XCODE_ADJUST <measured>  ->  <applied>
```

Firefox may omit `bw` (log shows the configured `wan_coldstart_kbps` instead of
`linkKbps=0`). Safari reports `none` and stays on native HLS; Tizen reports
`none` and retains direct/AVPlay.

## Configuration properties

| Property | Default | Meaning |
|---|---|---|
| `media_server/wan_coldstart_cap` | `true` | Master enable for the unmetered `browserhd` cold-start cap. |
| `media_server/wan_coldstart_kbps` | `5000` | Fallback cold-start link estimate (kbps) when `;bw=` is absent. |
| `media_server/xcode_adjust_server_policy` | `true` | Master enable for the server-side `XCODE_ADJUST` policy (§4). Off = legacy clamp-and-apply. |
| `media_server/xcode_adjust_video_reserve_pct` | `80` | Reserve margin: applied video candidate = goodput × this %. |
| `media_server/xcode_adjust_up_step_pct` | `15` | Slow-up: max increase per 5 s tick, as a % of the current target. |
| `media_server/xcode_adjust_deadband_pct` | `5` | Dead-band: candidate within ±this % of current is ignored. |
| `media_server/xcode_adjust_min_kbps` | `300` | Floor for the applied video bitrate. |
| `media_server/xcode_adjust_max_kbps` | `8000` | Fallback ceiling; raised to the session policy ceiling when present. |
| `httpls/input_error_resilience` | `true` | Drop corrupt source packets at the demuxer (`-err_detect ignore_err -fflags +discardcorrupt`) on the CMAF **and** `browserhd` fragmented-MP4 pulls (§5). |
| `ffmpeg/browserpull_aresample_async_floor_enabled` | `true` | Master enable for the audio-continuity async floor on the fragmented-MP4 re-encode pulls (§5). |
| `ffmpeg/browserpull_aresample_async_floor` | `1000` | Floor value for `aresample=async=N` on those paths; only raises a sub-floor value. |

## Server-side references (galeforcesage/SageTV-NG)

- `java/sage/client/PlaybackSurfaceSet.java` — `CANONICAL_BANDWIDTH_FEEDBACK`,
  `canonicalBandwidthFeedback` (parse `_BANDWIDTH_FEEDBACK`, default `none`).
- `java/sage/client/PlaybackSurface.java` — `getBandwidthFeedback()`.
- `java/sage/client/PlaybackDecisionEngine.java` — `surfaceFeedbackRank`,
  `SURFACE_DECISION_COMPARATOR` (the `pull-xcode` tie-breaker); `deliveryModeCpuRank`
  (native `hls` above the MSE bridge modes) and `pickDeliveryModeForDecision`
  (non-direct `hls`-first) for Safari native-HLS routing (§3).
- `java/sage/MiniClientSageRenderer.java` — surface discovery fetch of
  `_BANDWIDTH_FEEDBACK` (and `_AUDIO_MAX_CHANNELS`).
- `java/sage/MediaServer.java` — `XCODE_SETUP` `;bw=` parse → `setSeededLinkKbps`;
  `XCODE_ADJUST` handler → `applyMeasuredGoodputKbps` under the server-policy gate (§4).
- `java/sage/FFMPEGTranscoder.java` — `setSeededLinkKbps`, the WAN cold-start
  block in `applyBrowserHdRateCap`, and the `XCODE_ADJUST` policy
  (`computeGoodputAdjustedVideoKbps` pure function + `applyMeasuredGoodputKbps`).
  Media-integrity hardening (§5): the input error-resilience block (now gated for
  the `browserhd` fragmented-MP4 pull, not just `httplsMode`), and
  `resolveAudioResampleAsync` / `isBrowserFragmentedReencodePull` (the
  discontinuity async floor). Seek IDR/timeline anchoring: `-output_ts_offset` on
  the CMAF seek-relaunch.
- `java/sage/media/BitratePolicy.java` — `compute` / `Plan` (the bitrate anchor).
- `test/java/sage/client/PlaybackSurfaceSetTest.java`,
  `test/java/sage/client/PlaybackDecisionEngineTest.java` — parse + ranking coverage
  (including Safari `hls` routing); `test/java/sage/FFMPEGTranscoderTest.java` —
  `XCODE_ADJUST` policy coverage.
