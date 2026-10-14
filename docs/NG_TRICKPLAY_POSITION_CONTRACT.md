# NG Trick-Play & Position Reporting Contract — Client Implementation Guide

> **STATUS: DRAFT (pending Phase 0 discovery).** The wire mechanics, canonical
> definitions, and invariants below are stable and safe to architect against now.
> The exact numeric **trust criteria** the server uses to enable the in-place fast
> path (Section 6) will be finalized once Phase 0 telemetry has measured real client
> behavior on live streams. This notice will be removed when the contract is locked.

## Overview

Paused push seeks use the receiving client's existing units: desktop and NG
receivers get milliseconds relative to the stream's first PTS, while hardware
extenders retain their raw 90 kHz PTS convention. The server converts the aligned
I-frame timestamp at this boundary, including PTS rollover. Sending raw PTS to a
millisecond receiver can falsely advance the timeline to the end and mark a
partially viewed recording watched.

Direct push playback resets its cached position on a full seek and checks
reported positions against the new seek floor and the parser's sent-data
boundary before using them for display or relative skips. Out-of-window reports
are logged and retain the last accepted position. Pending relative skips remember
the clamped recording boundary, not an unreachable negative or past-end request.
The direct-push sent boundary is sampled from parsed PTS **after** each payload
transfer, independently of bitrate/DTS statistics. MPEG-PS can omit DTS and end a
payload on an audio packet; neither condition means that no media was sent.

Pending-target coalescing and recording/live-edge clamps default on only for
sessions accepted by the existing NG capability handshake, using
`videoframe/ng_seek_target_coalesce`, `videoframe/ng_live_skip_edge_clamp`,
and `miniplayer/ng_push_seek_dvr_clamp`. Explicit false values disable them.
Unsupported NG playback-rate requests return 1.0 without seeking.
`miniplayer/ng_push_inplace_seek` defaults off. Legacy sessions retain their
existing unprefixed settings, defaults, and rate-to-skip behavior; local players
and hardware clients without the NG handshake do not inherit the new defaults.

These server changes do not implement a client-rendered clock or an epoch
carrier in position replies. A server-local seek counter cannot identify the
epoch of an untagged client report. Pull-transcode clients still own reopening
their media connection at the requested target and discarding old demux state.

Active, growing recordings served through the parameterized Protocol 2.1
pull-xcode path are producer-paced by media time. FFmpeg normally reads at 1x,
may establish a bounded startup reservoir, and may use a bounded catch-up rate
after downstream backpressure clears. The requested `ss` offset remains the
generation's playback origin; pacing never forces playback to the live edge.
The existing transcode ring, MediaServer READ/SIZE protocol, and HTTP socket
backpressure continue to regulate bytes independently.

Completed recordings remain unpaced for fast startup and seeking. HLS/CMAF
segment production, push/direct playback, and legacy unparameterized
`XCODE_SETUP` sessions retain their existing behavior. The policy is controlled
by `media_server/pull_xcode_active_readrate_enabled`,
`media_server/pull_xcode_active_readrate`,
`media_server/pull_xcode_active_initial_burst_seconds`, and
`media_server/pull_xcode_active_catchup_rate`.

For NG enhanced push, the inferred display clock is bounded by parsed PTS from
successfully transferred payloads, not the DTS-only bitrate counter. Before any
payload timestamp is available it stays at the seek target. This prevents a
stalled pipeline from inventing playback progress; it does not prove which frame
the client has rendered. Enhancement watchdogs are cancelled and joined before
intentional seek/stop teardown, so a superseded worker cannot trigger crash
cooldown or act on the next pipeline.
NG reported-position clocks do not extrapolate a repeated client timestamp from
wall time. A frozen renderer report stays frozen; legacy interpolation remains
unchanged.

Completed CMAF/HLS recordings expose their full playlist from media sequence zero,
even when LL-HLS is enabled. Sliding windows, delta skips, blocking reloads and
low-latency part/control tags apply only while the source segment is recording.
Part duration remains consistent with the transcoder in both modes. A VOD resume
target must not be excluded by a live-edge window.

An NG recording load failure leaves the preview empty instead of automatically
tuning live TV when the timed user-watch holdoff expires. The failure state is
cleared by an explicit new watch request or successful load. Legacy preview
auto-launch behavior is unchanged.

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
| `SEEK_EPOCH_V1` | Reserved for a future versioned wire extension; do not advertise yet | Section 4 |
| `DVR_WINDOW_V1` | Accurate scrubber/live-edge UI; out-of-range clamping | Section 5 |

`TRICKPLAY_POSITION_V1` is the single most important one — it is the root-cause fix.
`DVR_WINDOW_V1` is UI polish plus a safety clamp and can follow later.

The server checks each implemented capability independently. Clients must not advertise
`SEEK_EPOCH_V1` until a versioned carrier has been specified and implemented on both ends.
Until then the server uses the known seek target, its delivered-media window, and pending
relative-target coalescing; those protections do not claim to identify a report's epoch.

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

### 4.1 Current wire status
No epoch field is present in the existing 5-byte detailed `MEDIACMD_GETMEDIATIME`
response or 9-byte detailed `MEDIACMD_PUSHBUFFER` response. The server rejects positions
outside the known post-seek delivered-media window and coalesces rapid relative seeks from
the last intended target. It cannot prove that an in-window report belongs to the current
epoch. `SEEK_EPOCH_V1` is reserved; advertising it does not currently change framing and
must not be used to imply that reports are tagged.

A future epoch carrier must use a separately negotiated, versioned response extension;
it must not append bytes to the legacy detailed responses based only on the current token.

### 4.2 Future versioned extension: client MUST
1. On epoch change: **discard** any queued data and in-flight position samples from the
   prior epoch.
2. **Reset** the demuxer/renderer at the boundary (for MPEG-TS: re-initialize on the next
   PAT/PMT; expect a clean RAP/keyframe at the start of the new epoch).
3. **Tag** every subsequent position report with the epoch it is rendering, and MUST NOT
   report a position stamped with a superseded epoch.
4. Resume reporting per Section 3 in the new client-local base.

### 4.3 Future result
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
  original direction bugs. (In **pull-xcode** mode the server still owns the relative-skip base
  and sends the resolved absolute target via `MEDIACMD_SEEK`; the client's obligation is to
  **re-open the transcode at that target** — see Section 6.2.)
- **Absolute seeks** (scrubber / seek-bar / jump-to-time): carry an explicit target time;
  handled as an absolute reposition, clamped to the DVR window (Section 5).

### 6.1 Fast path eligibility (informative)
When a forward absolute/relative target lands inside data already transcoded and buffered
by the client, the server can satisfy it with an **in-place** client seek
(`MEDIACMD_SEEK`) — no transcoder restart, near-instant — **iff**:
- the client advertises `PUSH_BUFFER_SEEKING` **and** `TRICKPLAY_POSITION_V1`, and
- a future versioned epoch extension is negotiated, so the server can prove the client's
  position belongs to the current reposition.

Otherwise the server performs a full reprime (correct, but ~2–5 s). In-place push seeking
remains disabled by default until that proof exists.

### 6.2 Pull-xcode (server-transcoded pull) seeking — client MUST re-open with `ss=` (NORMATIVE)

This section applies to every `pull-xcode:<mode>` delivery (`browserhd`, `browserhd_remux`,
`browserhd_copyv`, `mpeg2tsremux`, …) — i.e. whenever the **server transcodes** and the
client **pulls** the result. This is the primary PWA/browser (MSE) path.

**Transport reality.** The transcoded result is served over the MediaServer pull protocol
(`/msproxy`) as a **forward-growing byte stream**: the client polls `SIZE` (available/total
bytes) and issues `READ <byteOffset> <len>` against the transcoder's virtual output. **There
is no in-stream time-seek in this transport.** The server can reposition the transcode *only*
by tearing down the running ffmpeg and starting a new one at a new `-ss`, and that is
triggered **exclusively** by a fresh control line:

```
XCODE_SETUP <mode>;ss=<targetMs>
```

i.e. by the client **re-opening the transcode stream** at the new position. Repositioning the
transcoder underneath an existing pull is *not* possible without corrupting the byte/READ
contract (a restart resets the virtual output size to ~0, so the client's next `READ` at its
current high offset would fall past EOF and desync). The re-open is the mechanism, by design.

**Client requirement (MUST).** On **every** seek — relative (FF / REW / Jump) or absolute
(scrubber / jump-to-time) — the server delivers the resolved **absolute** target time to the
client as `MEDIACMD_SEEK <targetMs>` (source-timeline ms; the server has already computed the
relative-skip base per Sections 2 and 6). In a `pull-xcode` session the client MUST react by
**re-opening the transcode stream at that target**:

1. Open a new `/msproxy` request whose `XCODE_SETUP` carries `;ss=<targetMs>`.
2. Treat the response as a **fresh stream**: reset the MSE `SourceBuffer` / demuxer and append
   the new init exactly as at initial open (new fragmented-MP4 `empty_moov`, or for MP2T a
   clean PAT/PMT + PCR-before-payload + RAP at the head — same discipline as an epoch change,
   Section 7.2).

The client MUST NOT try to satisfy the seek by continuing to read the *current* byte stream,
and MUST NOT compute byte offsets itself — the byte↔time map of a live in-progress transcode
is not known to the client, and reading forward produces **no reposition** (the transcoder
keeps emitting from its original start).

**Observed failure this codifies.** In a server-transcode (`browserhd`) live session the
client issued no re-open on seek: the server logged the correct `MEDIACMD_SEEK` target for
each FF/REW/Jump, but **zero** `XCODE_SETUP;ss=` re-opens followed, so the single transcoder
streamed straight ahead and **nothing moved on screen**. The initial resume-seek worked only
because the *first* open already carried `ss=`. FF/REW/Jump/scrub must each behave like that
first open.

**Server side is already complete (informative).** `MediaServer` parses `;ss=` and, on each
such `XCODE_SETUP`, stops the previous transcoder and starts a new `FFMPEGTranscoder` with
`setTranscodeStartSeekTime(ss)` (emitting `-ss` ahead of `-i`). No server or framing change is
required for correct pull-xcode trick-play — only that the client re-opens with `ss=`. The
trigger may be either a brand-new `/msproxy` request **or** a fresh `XCODE_SETUP <mode>;ss=`
line sent on the **existing** MediaServer connection (the command loop handles repeated
`XCODE_SETUP`, tearing down and restarting the transcoder each time); in the same-connection
case the client resets its `READ` offset to 0 for the new stream and re-inits MSE as above.

**Reconciliation with the "server never restarts on seek" diagnostic (IMPORTANT).** A bridge
report may observe that `seekPull0()` / `MEDIACMD_SEEK` is logged N times yet the transcoder
never restarts (`virtXcodedBytes` climbs monotonically, one continuous `/msproxy` body). That
is expected and is **not** a server regression, because the client uses **two independent
server connections**:

- **control** (`/media`, the MiniClient socket): carries `OPENURL`, `MEDIACMD_SEEK`, etc. This
  is where `seekPull0(targetMs)` is delivered — to `MiniPlayer`, *not* to the transcoder.
- **media** (`/msproxy`, the MediaServer socket): carries `XCODE_SETUP` / `SIZE` / `READ` and
  owns the `FFMPEGTranscoder`.

`MediaServer.Connection` is per-socket and holds **no client identity** (only a file path), so
there is deliberately **no server-side path** from a control-channel `MEDIACMD_SEEK` to the
`/msproxy` transcoder — the server cannot know which pull connection (if any, and there may be
several for the same file) a given control seek belongs to. Therefore the transcode restart
**must** be initiated on the `/msproxy` connection by the client sending `XCODE_SETUP;ss=`. The
proof that the server-side restart works is the initial open: it carried `ss=` and positioned
correctly. Every subsequent FF/REW/Jump/scrub must do the same thing the first open did.

**Related server aid (position regression).** The same report may note relative skips walking
backward during a rapid burst (e.g. four FFs to 70s, then the next FF lands at 53s) because the
server based the skip on the client's still-catching-up reported position. The server already
mitigates this with seek-target *coalescing* (it accumulates from the last **issued** target
instead of the lagging reported position while the transcode settles). This is server-side and
needs no client change; it becomes fully effective once the reposition above actually happens,
since today the correct targets are computed but never acted on.

**How the other server-transcode clients already do this (precedent).** Every existing client
that plays a *server-transcoded* stream reaches the transcoder on seek in one of two ways, and
in **both** the party that **owns the transcode connection drives the seek** — the server never
reprimes a transcoder it cannot reach from the control channel:

| Client / delivery | Who owns the transcode source | What happens on seek |
| --- | --- | --- |
| **Push + server transcode** — native MiniClient (HD200/HD300), Placeshifter, Android push | **`MiniPlayer`** (creates `mpegSrc = FastMpeg2Reader` / `Mpeg2Transcoder` on its *own* MediaServer connection) | `MiniPlayer.seek()` → `mpegSrc.seek()` → `inxs.seekToTime()` reprimes the `FFMPEGTranscoder` **server-side** (`FastMpeg2Reader.java`). Client is simply pushed the new bytes. |
| **HLS pull** — iOS / `httpls` | **The HLS server** (per session) | Client **re-requests** a new playlist/segment; the server calls `xcode.transcoder.seekToTime(...)` per request (`HTTPLSServer.java`). Re-request → reprime. |
| **PWA `pull-xcode:browserhd`** — bridge/MSE | **The bridge** (its own `/msproxy` connection + `XCODE_SETUP`) | `MiniPlayer` owns *nothing* to reprime (`pushMode=false`, no `mpegSrc`/`tcSrc`); it can only emit `MEDIACMD_SEEK` on the control channel. The bridge holds a **single continuous** pull and **never re-requests** → nothing reprimes. |

So the PWA pull path is a **third model that matches neither precedent**. The behavior the
bridge is asking for ("server restarts the transcode on the control seek") is exactly what the
**push** clients get — but only because there the server *owns* the transcode source. A pull
client that privately owns the `/msproxy` transcode cannot get push semantics; its correct
analog is the **HLS pattern it is not yet following**: on `MEDIACMD_SEEK`, **re-issue the
request** with `XCODE_SETUP;ss=<targetMs>` (a fresh `/msproxy` open, or a new `XCODE_SETUP` on
the existing connection). That is the identical "client re-requests → server seeks its
transcoder" contract iOS already satisfies — **not a new server capability**, just the pull
client adopting the same discipline every other server-transcode client uses.

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
- **Pull-xcode seek (server transcodes):** on `MEDIACMD_SEEK <targetMs>`, re-open the
  transcode stream with `;ss=<targetMs>` and reset the `SourceBuffer` as on epoch change —
  do **not** keep reading the current stream. This is mandatory for FF/REW/Jump/scrub to move
  the picture; see Section 6.2 for the full contract and the failure it prevents.

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
  behavior**. NG-scoped default policies do not alter legacy property defaults. Existing
  player-specific position and seek behavior remains in force, and nothing on the wire
  changes for these clients.
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
   works (no flush-to-0 cascade). Once a versioned epoch extension exists, also confirm
   stale-epoch reports are dropped.
5. **DVR window:** for an in-progress recording, confirm forward skips saturate at the live
   edge and the scrubber reflects the sliding window.
6. **Future fast path:** with `PUSH_BUFFER_SEEKING` + `TRICKPLAY_POSITION_V1` plus a
   versioned epoch extension, confirm forward in-buffer targets land sub-second (branch
   `in-place(seekPull0)`), while backward / out-of-buffer targets take the reprime branch.

---

## Version Evolution

`*_V1` capabilities allow later revisions without breaking existing clients. Future
versions may add: explicit RAP-policy negotiation (previous/nearest/next/exact), speed-
intent for smooth FF/REW preview, and I-frame-playlist trickplay for pull/HLS clients
(server-side, backed by the keyframe index). Clients should ignore unknown fields and
negotiate only the versions they implement.
