# NG Live EPG-Boundary Seam Continuity

**Audience:** PWA client team **and** NG Android client team
**Status:** Server-side **implemented and live**. Additive, capability-gated,
fail-safe. A client that changes nothing keeps working exactly as today —
legacy clients are never affected.

## TL;DR

When you watch **live TV** continuously and the guide's scheduled show ends and
the next airing begins on the **same physical channel/tuner**, SageTV rolls the
capture into a new `MediaFile` per EPG airing. Historically that boundary tore
playback down:

* **Push clients** (native MiniPlayer): the server dropped the transcoded/remuxed
  session to null-mode passthrough, the client saw an "encoding change", forced a
  FULL SWITCH, and hit the 15s player-socket deadline → `sage.PlaybackException`.
* **Pull clients** (CMAF/HLS): the variant playlist emitted `#EXT-X-ENDLIST` at
  the end of the first airing's `MediaFile` and playback simply stopped.

The server now **bridges** the seam so continuous live playback does not falter.

## The one rule: live tune only, never a scheduled recording

The seam bridges **only when the viewing is a live/unscheduled tune**
(`VideoFrame.isLiveControl() == true`). It never bridges playback of a scheduled
recording.

| Viewing mode | Bridged? | Result |
| --- | --- | --- |
| Live tune, paused / timeshifted, crosses the show boundary | **Yes** | Playback continues into the next airing |
| A scheduled recording played from the start | **No** | Playback ends at the recording's own end |
| Server cannot resolve the viewing mode | **No** (fail-safe) | Ends as today |

Example: a 60-minute scheduled recording watched from the beginning ends at its
60-minute mark. A non-recorded live tune that is paused continues past the show's
EPG end into the next airing.

This distinction is enforced **server-side** in both transport paths. Clients do
not signal it and cannot override it.

## Transport paths

### Push (native MiniPlayer, legacy + Android-on-LAN) — no client change

Fully server-side. On a genuine same-tuner/same-station/contiguous seam the
server now rebuilds the exact pipeline it resolved for the prior airing
(transcode mode, enhancement tier, sidecar channel cap, resolved AC-4 audio
codec, or the TS→PS remux path) and fast-switches into the successor airing
instead of tearing the player down. **Legacy and native clients need no change.**

The server still independently requires the same-tuner/same-station/contiguous
seam predicate (`areMediaFileFormatsSwitchable`), so this can only ever engage on
a real boundary of the same live capture.

### Pull (CMAF/HLS: PWA, Android-on-metered) — capability-gated

The fMP4 variant playlist bridges the boundary using **standard HLS tags**:

```
#EXTM3U
#EXT-X-VERSION:7
#EXT-X-MAP:URI="…/<mfId>_…_init.mp4"
#EXTINF:5.0,
…/<mfId>_…_part0.m4s
…                       (parts of the first airing)
#EXT-X-DISCONTINUITY
#EXT-X-MAP:URI="…/<successorMfId>_…_init.mp4"
#EXTINF:5.0,
…/<successorMfId>_…_part0.m4s
…                       (parts of the next airing, under its OWN mediafile URL)
```

Each successor airing's parts are served under **its own `MediaFile` URL**, with
its own per-file CMAF transcoder and its own `init.mp4`. There is no cross-file
transcoder surgery.

#### Capability gate

The bridge is emitted **only** when the request carries the header:

```
x-cmaf-seam: 1
```

Without it (every legacy/unknown client), the playlist emits today's
`#EXT-X-ENDLIST` at the first airing's end — **zero behavior change**. So the
bridge is strictly opt-in per request.

(Operators may flip a server default, `httpls/fmp4_seam_default`, to enable it for
all pull clients, but the header is the supported per-client contract.)

#### What a client must do at the discontinuity

The standard HLS tags (`#EXT-X-DISCONTINUITY` + a new `#EXT-X-MAP`) are handled
natively by compliant HLS players (ExoPlayer, AVPlayer). Only a hand-rolled MSE
pipeline (the PWA) needs to react explicitly:

1. On the new `#EXT-X-MAP`, fetch the successor `init.mp4` and re-initialize the
   `SourceBuffer` for the new fragment run — call `SourceBuffer.changeType()`
   before appending the successor init, then append it, then the successor parts.
2. Treat the `#EXT-X-DISCONTINUITY` as a timeline reset for the appended parts
   (each successor airing starts its own media timeline / part numbering at 0).
3. Keep polling the variant playlist across the boundary. If the current airing
   has just ended but the successor `MediaFile` has not yet materialized, the
   server holds the playlist open (no `#EXT-X-ENDLIST`) for a bounded grace
   window (`httpls/fmp4_seam_grace_ms`, default 8000 ms) rather than signalling
   end-of-stream. Do not treat a momentarily part-less tail as EOS; keep polling
   until either the successor appears or `#EXT-X-ENDLIST` is finally emitted.

The bridge follows the successor chain across multiple back-to-back airings,
bounded by `httpls/fmp4_seam_max_links` (default 32).

## Server configuration reference

| Property | Default | Meaning |
| --- | --- | --- |
| `miniplayer/seam_fastswitch_transcoded` | `true` | Enable push-path server-side seam continuation |
| `httpls/fmp4_seam_default` | `false` | Emit the CMAF bridge for all pull clients even without the header |
| `httpls/fmp4_seam_grace_ms` | `8000` | How long to hold a pull playlist open while awaiting the successor `MediaFile` |
| `httpls/fmp4_seam_max_links` | `32` | Max successor airings bridged in one chain |

## Compatibility summary

* Legacy native clients: fixed transparently, no change, no new tags.
* Legacy HLS/pull clients: never send `x-cmaf-seam`, so they see exactly today's
  `#EXT-X-ENDLIST` behavior.
* PWA: send `x-cmaf-seam: 1` and handle the discontinuity re-init as above.
* Android NG: uses push on LAN (already fixed server-side) and the CMAF bridge on
  metered pull (send `x-cmaf-seam: 1`).
* Scheduled-recording playback is never bridged, on any transport.
