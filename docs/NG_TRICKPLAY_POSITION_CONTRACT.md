# NG Trick-Play & Position Reporting Contract — Client Implementation Guide

> **STATUS: DRAFT (pending Phase 0 discovery).** The wire mechanics, canonical
> definitions, and invariants below are stable and safe to architect against now.
> The exact numeric **trust criteria** the server uses to enable the in-place fast
> path (Section 6) will be finalized once Phase 0 telemetry has measured real client
> behavior on live streams. This notice will be removed when the contract is locked.

## Overview

This contract specifies how NG clients report **playback position** and participate in
**trick-play** (fast-forward, rewind, jump, scrub) so that the SageTV-NG server can
reposition **correctly** (right direction and distance, every time) and **quickly** (no
transcoder restart when avoidable).

It exists because, in push mode with server-side transcoding, the server owns positioning
for relative skips but must know where the client is *actually displaying*. Today the one
position value on the wire (`clientReportedMediaTime`) is under-specified, and some clients
report it in ways that make the server mis-compute the skip base (backward skips landing
forward, one bad skip poisoning the session). This contract nails down what that value
**must** mean, adds opt-in epoch and DVR-window semantics, and does so **without changing
any existing framing or public API** — every addition is capability-gated and additive.

**Backwards compatibility is absolute.** A client that advertises none of the capabilities
in Section 1 behaves exactly as it does today; the server falls back to a server-side
inferred position model. Placeshifter, HD200/HD300, and existing miniclients are
unaffected.

---

## Table of Contents
1. Capability Advertisement
2. Canonical Position (the core definition)
3. Position Reporting on the Existing Wire (`TRICKPLAY_POSITION_V1`)
4. Seek-Epoch Contract (`SEEK_EPOCH_V1`)
5. DVR / Live-Edge Window (`DVR_WINDOW_V1`)
6. Trick-Play Intent Semantics (relative vs absolute)
7. Per-Client Implementation Notes
8. Canonical Names
9. Legacy Contract (non-negotiable)
10. Testing & Verification

---

## 1. Capability Advertisement

Three **independent, opt-in** capabilities. A client MAY advertise any subset. They are
negotiated by the same mechanism as `STREAMINFO` / `DETAILED_BUFFER_STATS` /
`PUSH_BUFFER_SEEKING` (property/capability handshake during NG session setup).

| Capability | Unlocks | Requires |
|---|---|---|
| `TRICKPLAY_POSITION_V1` | Correct relative-skip base; eligibility for the fast path | Section 2, 3 |
| `SEEK_EPOCH_V1` | Structural elimination of stale-position bugs; exact fast-path gating | Section 4 |
| `DVR_WINDOW_V1` | Accurate scrubber/live-edge UI; out-of-range clamping | Section 5 |

`TRICKPLAY_POSITION_V1` is the single most important one — it is the root-cause fix.
`SEEK_EPOCH_V1` is strongly recommended alongside it. `DVR_WINDOW_V1` is UI polish plus a
safety clamp and can follow later.

The server checks each capability independently. Advertising `TRICKPLAY_POSITION_V1`
without `SEEK_EPOCH_V1` is valid (the server uses its inferred epoch guard); advertising
both is the intended target and is what makes the fast path safe.

---

## 2. Canonical Position (the core definition)

> **Canonical position is the media-time of the video frame currently visible ON SCREEN,
> expressed in the stream's media timeline.**

Read that literally. It is **not**:
- the client's wall clock,
- the read-ahead / download position,
- the demuxer/parser head,
- the tail of the decode queue,
- the last byte received.

It is the presentation time of the frame the user is looking at *right now*, sampled at
report time. If playback is paused on a frame at 00:42, the canonical position is 42000 ms
and stays there until playback resumes.

### Timeline / units
- The server sends, in every detailed push reply trailer, its **mux time** =
  `currParserTimestamp − timestampOffset` (a 32-bit int, milliseconds, **client-local**:
  i.e. relative to the start of the current transcode prime, where the first frame after a
  (re)prime is time 0).
- The client's canonical position **MUST use the same client-local base**: 0 at the first
  displayed frame after the current (re)prime, counting up in milliseconds.
- The server reconstructs absolute source time as `timestampOffset + reportedPosition`.

This is exactly the base the existing field already uses — the contract does **not** move
the field or change its units. It only pins down its *meaning* so the server can trust it.

---

## 3. Position Reporting on the Existing Wire (`TRICKPLAY_POSITION_V1`)

### 3.1 Transport (no framing change)

Position is reported on the **existing** detailed-push reply, which the client already
sends after a push buffer when `DETAILED_BUFFER_STATS` is active:

```
Server → Client (push trailer, unchanged):
  int16  estimated channel bandwidth (kbps)
  int16  estimated stream bitrate (kbps)
  int16  target/current transcode bitrate (kbps)
  int32  server mux time (ms, client-local = currParserTimestamp - timestampOffset)

Client → Server (reply, unchanged framing):
  int32  freeSpace
  int32  clientReportedMediaTime   <-- THIS is the canonical position (Section 2)
  int8   clientReportedPlayState
```

`TRICKPLAY_POSITION_V1` changes **nothing about the bytes** — it is a promise about what
`clientReportedMediaTime` and `clientReportedPlayState` *contain*.

### 3.2 Invariants (MUST)

1. **On-screen, not buffered.** `clientReportedMediaTime` is the visible frame's time
   (Section 2), sampled within one report interval of "now".
2. **Advances only during PLAY.** While PLAY, it increases at ~1× real time. While PAUSED
   or BUFFERING it is frozen at the last displayed frame. It MUST NOT free-run off the wall
   clock during a stall.
3. **Monotonic within an epoch.** It never goes backward except across a server-initiated
   reposition (see Section 4). No jitter/regressions from out-of-order sampling.
4. **Reset on flush.** After the client honors a server `MEDIACMD_FLUSH` (22) or a
   reposition, it reports from the **new** content (starting near 0 in the new client-local
   base), never a value from the discarded timeline.
5. **Play-state accuracy.** `clientReportedPlayState` reflects the true renderer state
   (playing / paused / buffering / ended) so the server can tell a frozen position (paused)
   from a stalled one (buffering).

### 3.3 Why this is the fix

For a **relative** skip (e.g. "REW 10s"), the server computes `target = canonicalPosition −
10000`. If the reported position is the read-ahead head (which leads the screen by the
client's whole buffer, measured 11–31 s in the field), a −10 s rewind nets *forward*. If it
is frozen/stale, the base is wrong by minutes. Pinning it to the on-screen frame makes the
arithmetic correct. Until a client implements this, the server uses its own inferred
estimate (Section 9) — usable, but the client's true value is better and is what unlocks
the fast path.

---

## 4. Seek-Epoch Contract (`SEEK_EPOCH_V1`)

An **epoch** is a monotonically increasing integer identifying a contiguous run of stream
content. Every server-initiated reposition (reprime, flush, or in-place jump) starts a new
epoch. The epoch contract lets the server match each position report to the exact content
it describes, so a report sampled *before* a reposition can never be applied *after* it —
which is precisely the "backward skip goes forward" / "session poisoned, everything flushes
to 0:00" failure class.

### 4.1 Server → Client
On each reposition the server communicates the new current epoch id. (Carried additively
alongside the existing `MEDIACMD_SEEK` (29) / `MEDIACMD_FLUSH` (22) for clients that
advertise `SEEK_EPOCH_V1`; the exact carrier is finalized in the locked spec — clients
should treat "a reposition occurred" and "here is the new epoch id" as the two facts to
consume.)

### 4.2 Client MUST
1. On epoch change: **discard** any queued data and in-flight position samples from the
   prior epoch.
2. **Reset** the demuxer/renderer at the boundary (for MPEG-TS: re-initialize on the next
   PAT/PMT; expect a clean RAP/keyframe at the start of the new epoch).
3. **Tag** every subsequent position report with the epoch it is rendering, and MUST NOT
   report a position stamped with a superseded epoch.
4. Resume reporting per Section 3 in the new client-local base.

### 4.3 Result
The server accepts a client position only when it matches the current epoch, eliminating
the "catch-up guessing" the inferred guard must otherwise do — and making the in-place fast
path (Section 6) safe to enable.

---

## 5. DVR / Live-Edge Window (`DVR_WINDOW_V1`)

The server advertises the currently seekable window so the client's scrubber and
jump-to-live behave correctly and never request an out-of-range target.

Window fields (pushed on change, additively; JSON in the STREAMINFO style):

| Field | Type | Meaning |
|---|---|---|
| `seekableStartMs` | long | Earliest seekable source time (0 for completed; retained start for live) |
| `seekableEndMs` | long | Latest seekable source time |
| `liveEdgeMs` | long | Current live edge (live/in-progress only) |
| `atLive` | bool | Whether playback is currently at the live edge |
| `durationMs` | long? | Present and fixed for completed recordings; absent/growing for live |

### Client MUST
- Clamp scrubber and seek intents to `[seekableStartMs, seekableEndMs]`.
- Render the live edge; treat `atLive` for a "Jump to Live" affordance.
- For in-progress/live content, expect the window to slide; forward skips saturate at
  `liveEdgeMs` rather than overshooting into nonexistent content.

Clients without this capability rely on server-side clamping (Section 9) and STREAMINFO's
`live` / `duration_ms` as today.

---

## 6. Trick-Play Intent Semantics (relative vs absolute)

The command vocabulary is the **existing** `MEDIACMD_*` set — nothing is renumbered.

- **Relative skips** (FF, REW, Jump — the IR skip codes): in push mode the client sends the
  **intent only**; the **server owns positioning** and computes the target from the
  canonical on-screen base (Section 2). Clients **MUST NOT** self-compute relative targets
  in push mode — client-side relative math against a mis-based clock is what produced the
  original direction bugs. (In pull mode the client already owns seeking; unchanged.)
- **Absolute seeks** (scrubber / seek-bar / jump-to-time): carry an explicit target time;
  handled as an absolute reposition, clamped to the DVR window (Section 5).

### 6.1 Fast path eligibility (informative)
When a forward absolute/relative target lands inside data already transcoded and buffered
by the client, the server can satisfy it with an **in-place** client seek
(`MEDIACMD_SEEK`) — no transcoder restart, near-instant — **iff**:
- the client advertises `PUSH_BUFFER_SEEKING` **and** `TRICKPLAY_POSITION_V1`, and
- (recommended) `SEEK_EPOCH_V1`, so the server can trust the client's position across the
  jump.

Otherwise the server performs a full reprime (correct, but ~2–5 s). Meeting this contract
is what moves an NG client from "always reprime" to "in-place when possible." The precise
trust threshold is finalized after Phase 0.

---

## 7. Per-Client Implementation Notes

### 7.1 Android (media3/ExoPlayer, and the ijkplayer path)
- Derive canonical position from the **renderer/output** clock (the frame being presented),
  not from the sample queue or `SampleStream` read position. In media3 terms this is the
  rendered position, not the buffered position.
- Freeze the reported value while the player is `STATE_BUFFERING` or paused; report the true
  state in `clientReportedPlayState`.
- On epoch change: perform the equivalent of a seek/flush and decoder reconfigure; for TS,
  re-init on the next PAT/PMT.

### 7.2 PWA / Web (MSE + hls.js or native)
- Canonical position = `video.currentTime` mapped to the stream's client-local base (the
  `<video>` element's currentTime *is* the on-screen presentation time — exactly what this
  contract wants).
- Freeze while `readyState` indicates stall/`waiting`; report buffering state accurately.
- On epoch change: `SourceBuffer.abort()` + `remove(...)` the old range, then append the new
  init segment. For MSE **MP2T** the epoch boundary MUST provide a clean init: PAT/PMT
  present, PCR before payload, valid PTS, and a RAP/keyframe at the start — otherwise MSE
  will stall. (This is the same discipline the remux path already follows for TS.)

### 7.3 Both
- Always honor `MEDIACMD_FLUSH` before emitting the next position report.
- Keep the report interval short enough that skip latency is dominated by repositioning, not
  by stale position sampling.

---

## 8. Canonical Names

Use the SageTV canonical names verbatim (clients normalize to these; the server compares
directly). No new vocabulary is introduced by this contract.

- **Video:** `MPEG1-VIDEO`, `MPEG2-VIDEO`, `MPEG4-VIDEO`, `H.264`, `HEVC`, `VP9`, `AV1`
- **Audio:** `MP2`, `MP3`, `AAC`, `HE-AAC`, `AC3`, `EAC3`, `AC4`, `DTS`, `TRUEHD`, `OPUS`,
  `FLAC`, `PCM`
- **Container:** `MPEG2-PS`, `MPEG2-TS`, `MP4`, `MATROSKA`, `AVI`, `MOV`, `FLV`, `WEBM`
- **Delivery:** `pull`, `push`, `hls`, `dash`, `webrtc`

(See `NG_STREAMINFO_PROTOCOL.md` for the codec→MIME mapping.)

---

## 9. Legacy Contract (non-negotiable)

- A client advertising **none** of Section 1's capabilities keeps the **exact current
  behavior**. The server computes position from an inferred server-side display model
  (`timestampOffset + wall-elapsed-since-reprime`, clamped by the parser timestamp), applies
  an inferred epoch guard, and clamps seeks to the recording window server-side. Nothing on
  the wire changes for these clients.
- Every capability is **independently** additive. Turning any of them on never alters the
  bytes seen by a client that didn't ask for it.
- Existing framing (`MEDIACMD_*`, the detailed-push trailer) is **byte-for-byte
  unchanged**. Placeshifter, HD200/HD300, and existing Android miniclients are unaffected.
- Live-TV steady playback never enters the seek path and is unaffected by any of this.

---

## 10. Testing & Verification

Server logs (grep `NG-SEEKDIAG`) show, per skip: the target, the base used, implied delta,
direction, which reposition branch fired, and the raw position fields. To validate a client
implementation of this contract:

1. **Position truth:** during steady PLAY, confirm the server's `displayBase` and the
   client's reported `crmt` (as `timestampOffset + crmt`) agree within a small margin, and
   both track the on-screen frame — not the buffered head.
2. **Pause freeze:** pause; confirm the reported position stops advancing and playstate
   reports paused.
3. **Relative correctness:** REW 10s from a known frame lands ~10 s earlier (not forward);
   FF 10s lands ~10 s later; repeat rapidly and confirm no cumulative drift.
4. **No poisoning:** issue a bad/edge skip, then a normal FF; confirm the normal FF still
   works (no flush-to-0 cascade). With `SEEK_EPOCH_V1`, confirm stale-epoch reports are
   dropped.
5. **DVR window:** for an in-progress recording, confirm forward skips saturate at the live
   edge and the scrubber reflects the sliding window.
6. **Fast path:** with `PUSH_BUFFER_SEEKING` + `TRICKPLAY_POSITION_V1` (+ `SEEK_EPOCH_V1`),
   confirm forward in-buffer targets land sub-second (branch `in-place(seekPull0)`), while
   backward / out-of-buffer targets take the reprime branch and remain correct.

---

## Version Evolution

`*_V1` capabilities allow later revisions without breaking existing clients. Future
versions may add: explicit RAP-policy negotiation (previous/nearest/next/exact), speed-
intent for smooth FF/REW preview, and I-frame-playlist trickplay for pull/HLS clients
(server-side, backed by the keyframe index). Clients should ignore unknown fields and
negotiate only the versions they implement.
