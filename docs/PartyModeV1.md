# Party Mode v1: coordinator and player-adapter contract

Status: replacement pre-release contract. The failed recovery-heavy Party
contract is removed in place. Android, PWA and Tizen clients must implement this
document together; there is no compatibility mode for the removed contract.

Canonical sources:

- server protocol: `java/sage/party/`
- executable examples: `docs/party/v1/examples.cjs`
- golden envelopes: `docs/party/v1/golden.json`
- synthetic clients: `test/java/sage/party/PartyServiceTest.java`

## 1. Architecture

Party Mode has four boundaries:

1. **Party coordinator** — membership, host authority, join codes and one
   authoritative timeline.
2. **Clock model** — server/client monotonic offset, RTT and uncertainty.
3. **Player adapter** — prepare ordinary playback, observe media time and execute
   a future play/pause/seek command.
4. **Media delivery** — existing SageTV source lookup, Watch, transcoding,
   direct-play and per-client unicast connections.

The coordinator does not select a playback surface, issue media-producer
credentials, reserve GPU bitrate, own a transcoder, count buffer quorum or
interpret platform decoder internals.

### Existing SageTV playback is authoritative

The server resolves `mediaFileId` with the existing Wizard `MediaFile` lookup.
`PREPARE` calls the existing `VideoFrame.watch(...)` path through
`preparePartyMedia(...)`. That preserves ordinary file accessibility, parental
controls, tuner/resource checks, recording segments, playback-surface selection,
direct play and transcoder selection.

For server-owned MiniClient push playback, a scheduled directive enters the
existing `VideoFrame` job queue as `TIME_SET` followed by `PLAY` or `PAUSE`.
Pull/MSE/AVPlay/Media3 adapters execute the same command locally against their
ordinary source.

Party Mode never introduces a second source resolver or a parallel media loader.

### Independent unicast

Every participant retains its own media connection and player. A slow participant
cannot backpressure another participant. Direct-file reads may share the OS page
cache, and existing delivery infrastructure may reuse transcoders or fragments,
but no Party socket or player is shared.

Shared representation production is a delivery-layer optimization and does not
change this protocol.

### 1.1 Party topology and terminology

A Party has up to five participants:

- one **Party originator**, which creates the Party and invites others;
- one to four **invitees**, each with an independent connection, clock model,
  preparation state, player backend, network path and latency model.

This is not a host/guest pair and invitees are not assumed to be equivalent.

Three identities are distinct:

| Term | Meaning |
| --- | --- |
| Party originator | Participant that created the Party |
| current authority holder | `hostParticipantId`; initially the originator, but may transfer after Stop/disconnect |
| action origin | `originParticipantId` on a COMMAND; participant that initiated that specific shared action |

An invitee may originate an ordinary shared action when eligible. Host-only
operations still use the current authority holder.

One PartyService supports at most 8 simultaneous independent Parties. With the
five-participant room limit, the protocol ceiling is 40 logical participants.
This is a coordination limit, not a guarantee that the server can concurrently
decode, transcode, enhance or deliver 16 media sessions. Existing tuner,
transcoder, GPU, bandwidth and playback admission policies still apply.

## 2. Enablement and negotiation

Set `party/enabled=true` and restart the SageTV JVM. Party Mode remains disabled
by default until physical client acceptance passes.

An eligible renderer advertises all of:

```text
PARTY_V1
PARTY_JOIN_CODE_V1
PARTY_INVITATION_V1
PARTY_MONOTONIC_V1
PARTY_SCHEDULED_V1
PARTY_HEARTBEAT_V1
PARTY_PLAYER_ADAPTER_V1
PARTY_PRESENTED_FRAME_V1
PARTY_ARMED_REVEAL_V1
PARTY_LOCAL_STOP_V1
```

Growing recordings additionally require:

```text
PARTY_GROWING_MEDIA_V1
```

These tokens assert real adapter behavior. Do not advertise them from a client
that cannot prepare ordinary playback, use a monotonic clock, execute a future
deadline, report the first screen-presented frame and subsequent observed media
time, prepare a decoded frame without revealing it, release that frame at a
future reveal deadline, and intercept local Stop.

Authorization remains bound to the existing admitted MiniClient/NG session.
Party Mode does not change Placeshifter login, LAN trust, parental control or
security-profile behavior.

## 3. Transport and common envelope

Use the existing MiniClient SetProperty transport. Property name is
case-sensitive:

```text
PARTY_V1
```

Property payload:

```text
u16 nameByteLength
u16 valueByteLength
ASCII property name
UTF-8 JSON value
```

Client-to-server retains the existing 16-byte event header. Server-to-client
retains the existing four-byte command header.

Every JSON value is an object containing:

```json
{
  "schemaVersion": 1,
  "type": "HELLO"
}
```

Maximum JSON length is 8192 UTF-8 bytes. Identifiers and enum strings match
`[A-Za-z0-9_.:-]{1,128}`.

Every request contains:

```json
{
  "requestId": "unique-request-id",
  "requestSeq": 1
}
```

Every request except `HELLO` also contains the latest `serverEpoch` and
`sessionToken` from `AVAILABLE`. Membership requests contain `partyId`, except
code-only `JOIN`.

Successful replies echo `requestId` and include `serverMonoMs`. Events omit
`requestId`. Exact request retries return the cached reply; reusing a request ID
with different content fails.

### 3.1 Message rule

The server sends a message only when it performs one of these functions:

- replies to a client request;
- completes an accepted asynchronous operation;
- directs playback;
- changes control authority;
- invalidates the Party or a pending operation.

Invitation and participant-state messages are functional, not decorative:

- `CLIENTS` supplies the host's eligible invitation targets;
- `INVITED` opens the invitee's join-code flow for one identified Party/media;
- `PARTICIPANT_STATE` reports an actual status/prepared-state transition so the
  host can determine cohort readiness.

Participant-state events are deduplicated. They are not emitted for unchanged
heartbeats. Unsolicited join-code reminders are removed; the code is returned by
CREATE or an explicit host request.

### 3.2 Required, conditional, optional and forbidden fields

Do not interpret "not present in every message" as "optional." Fields have four
categories:

| Category | Meaning |
| --- | --- |
| required | Must be present every time that message is sent |
| conditionally required | Must be present when the stated mode/action/stage applies |
| optional/defaulted | May be omitted and has the documented default |
| forbidden otherwise | Must not be sent outside the stated condition |

| Field | Requirement |
| --- | --- |
| `requestId`, `requestSeq` | Required on every client request |
| `serverEpoch`, `sessionToken` | Required on every request except HELLO |
| `partyId` | Required on Party-member requests; forbidden on code-only manual JOIN |
| JOIN `partyId` + `mediaFileId` | Conditionally required together when JOIN follows INVITED |
| CREATE `mediaFileId` | Required unless the server can resolve the renderer's currently loaded media |
| CREATE `startMode` | Optional/defaulted to `BEGINNING` |
| ACTION `targetPositionMs` | Required for JUMP; forbidden for actions that do not use an explicit target |
| ACK `success` | Required for EXECUTED, ARMED and PRESENTED |
| ACK `sampleMonoMs`, `positionMs`, `state` | Required for ARMED and PRESENTED; forbidden for plain EXECUTED |
| directive `phase`, `barrier`, `visibleAtServerMonoMs` | Required together for barrier PREPARE; absent on single-phase directives |
| `predictedPresentationLatencyMs`, `presentationUncertaintyMs` | Required diagnostic scheduling inputs on barrier PREPARE |
| READY `bufferAheadMs` | Optional/defaulted to zero |
| HEARTBEAT `bufferAheadMs` | Optional/defaulted to zero |

If a condition is true, its conditionally required fields are mandatory.

### 3.3 Required ordered sequences

### 3.2 Required ordered sequences

#### New participant activation

These steps are ordered. Do not start a later step before the prior step
completes successfully.

| Order | Client | Server result | Required before next step |
| --- | --- | --- | --- |
| 1 | `HELLO` | `AVAILABLE` | Save `serverEpoch` and `sessionToken` |
| 2 | Host: `LIST_CLIENTS` then `INVITE`; invitee: receive `INVITED` | `CLIENTS`, `ACK`, then `INVITED` | Required when using the invitation UI; manual code entry skips this order |
| 3 | `CREATE` or `JOIN` | `CREATED` or `JOINED` | Save Party and participant identifiers |
| 4 | five `CLOCK` / `CLOCK_REPLY` exchanges | `CLOCK_MODEL` | `confident=true` |
| 5 | `PREPARE` | `PREPARE_ACCEPTED` | Wait; this is not loaded yet |
| 6 | none | `PREPARATION_RESULT` | Must be `status=LOADED` |
| 7 | `READY` | initial `CORRECTION` | Store its exact directive identifiers |
| 8 | execute correction | optional `ACK/EXECUTED` reply | Dispatch alone does not activate |
| 9 | first visible target frame | `ACK/PRESENTED` reply | Participant becomes active |
| 10 | sparse `HEARTBEAT` | `HEARTBEAT_ACK` | Normal telemetry begins |

`RESUME` replaces order 2 after a transport loss, but orders 3 through 8 are
still mandatory.

#### Single-phase shared transition

PLAY and any SOFT correction use this order:

1. Client sends `ACTION`, or server emits participant-specific `CORRECTION`.
2. Server returns/sends participant-specific `COMMAND` or `CORRECTION`.
3. Client executes at `executeAtServerMonoMs`.
4. Client may send `ACK/EXECUTED`.
5. Client waits for the first screen-visible post-command frame.
6. Client sends `ACK/PRESENTED`.
7. Only after PRESENTED may normal drift heartbeats resume.

PAUSE may finish at EXECUTED only when the directive does not require a new
visible frame.

#### Barrier transition

JUMP, FF, REW, FF2, REW2, COMSKIP and HARD correction use this exact order:

1. Server sends `COMMAND`/`CORRECTION` with `phase=PREPARE`, `barrier=true`,
   participant-specific `executeAtServerMonoMs`, and tentative
   `visibleAtServerMonoMs`.
2. Client preserves the old visible frame.
3. At `executeAtServerMonoMs`, client repositions behind the cover and pauses on
   the decoded target frame.
4. Client sends `ACK/ARMED` with the exact directive identifiers, normalized
   sample time, target position, and `state=PAUSED`.
5. Server queues the correlated `ACK` response to that ARMED request. ARMED is
   functional request/response traffic and must never be treated as removable
   positive chatter.
6. Only after the correlated response is queued does the server evaluate the
   frozen ready cohort. No participant may reveal early.
7. Server sends `REVEAL` with one common execution deadline.
8. At the REVEAL deadline, client removes the cover and changes play/pause state
   without another seek.
9. Client sends `ACK/PRESENTED` for the first screen-visible frame.
10. Normal heartbeats resume.

EXECUTED cannot replace ARMED. ARMED cannot replace PRESENTED. PRESENTED before
ARMED is invalid for a barrier directive.

#### Recoverable clock failure

1. Retain exactly the latest user action.
2. Run CLOCK exchanges until `CLOCK_MODEL.confident=true`.
3. Fetch `SNAPSHOT`.
4. Send the retained ACTION once with the snapshot's current `stateVersion`.

Do not execute the action locally before the accepted COMMAND arrives.

### 3.4 Situation-valid messages

| Message | Valid only when | Invalid or ignored when |
| --- | --- | --- |
| `CREATE` | Eligible connection not already joined | Already joined |
| `LIST_CLIENTS` | Eligible host-side connection preparing invitation UI | Ineligible connection |
| `INVITE` | Current host and a handle returned by current CLIENTS | Non-host or unavailable handle |
| `JOIN` | Valid unexpired join code; not already joined | Wrong/expired code or existing membership |
| `RESUME` | Same principal, disconnected participant, within grace, valid recovery token | Grace expired or participant still connected |
| `CLOCK_REPLY` | Matching outstanding probe | Probe unknown/expired |
| `PREPARE` | Joined, no preparation already pending | Another preparation is pending |
| `READY` | Preparation loaded and clock confident | Not loaded or clock uncertain |
| `ACTION` | Current `stateVersion`, requester eligible, no transition pending | Stale generation, clock uncertain, activating, buffering, or stale playing heartbeat |
| `ACK/EXECUTED` | Exact currently pending directive | Wrong directive/generation |
| `ACK/ARMED` | Barrier directive after target frame is decoded and held paused | Single-phase directive, duplicate ARMED, wrong state/position, or early sample |
| `ACK/PRESENTED` | First visible frame for the exact pending directive | Before ARMED on a barrier, wrong state, stale sample, or wrong directive |
| `HEARTBEAT` | Prepared participant; telemetry at a fresh normalized sample | Not prepared or stale/non-increasing sample |
| `JOIN_CODE` / `ROTATE_CODE` | Current host only | Non-host |
| `COMSKIP` | Current host only and marker end exists | Non-host or no usable marker |
| `LEAVE` | Current member | Non-member |
| `END` | Current host only | Non-host |

### 3.5 Coupled items

The following values and messages must be treated as one unit:

| Coupled items | Invariant |
| --- | --- |
| `serverEpoch` + `sessionToken` | Send both on every request after HELLO |
| `requestId` + complete request body | Exact retry must reuse both; never reuse an ID for different content |
| `stateVersion` + `commandSeq` + `directiveId` | Echo all three exactly in every directive ACK |
| `CLOCK_PROBE.probeId` + `c1` + `c2` | One CLOCK_REPLY answers one outstanding probe |
| `PREPARE_ACCEPTED.preparationId` + `PREPARATION_RESULT.preparationId` | Accepted preparation is incomplete until its matching result |
| `CLIENTS.sessionHandles[]` + `INVITE.sessionHandle` | INVITE must use a currently returned eligible handle |
| `INVITED.partyId` + `INVITED.mediaFileId` + JOIN | Invited JOIN echoes both identifiers together with the entered code |
| PREPARE-phase directive + `ACK/ARMED` + `REVEAL` + `ACK/PRESENTED` | One barrier lifecycle; no stage may be skipped or borrowed from another directive |
| `executeAtServerMonoMs` + current clock model | Convert with the model that is confident for that deadline |
| `sampleMonoMs` + `positionMs` + `state` | One observation of the same rendered frame |
| `HOST_CHANGED.hostParticipantId` + host-only operations | Authority changes immediately when this event arrives |

### 3.6 Independent items

These do not wait on or settle playback directives:

- CLOCK maintenance while joined, including while paused;
- `SNAPSHOT` retrieval;
- local captions, audio-track selection, subtitle styling, and volume;
- sparse HEARTBEAT telemetry after activation;
- join-code rotation, except that only the host may request it.

Independent does not mean optional: CLOCK maintenance is required to preserve
future ACTION eligibility.

### 3.7 Functional asynchronous server events

Only these unsolicited messages remain:

| Event | Functional reason |
| --- | --- |
| `PREPARATION_RESULT` | Completes accepted asynchronous PREPARE |
| `INVITED` | Starts the selected invitee's join-code flow |
| `PARTICIPANT_STATE` | Reports an actual readiness/status transition |
| `COMMAND` | Directs a non-origin participant's shared transition |
| `CORRECTION` | Directs participant-specific synchronization work |
| `REVEAL` | Releases an armed barrier at the common deadline |
| `HOST_CHANGED` | Changes authority for host-only operations |
| `ENDED` | Invalidates the Party |
| `ERROR` | Fails an asynchronous operation or pending directive |

`CLIENTS` is a request reply, not an event. Unsolicited `JOIN_CODE` messages are
removed.

## 4. Availability

Request:

```json
{
  "schemaVersion": 1,
  "type": "HELLO",
  "requestId": "hello-1",
  "requestSeq": 1
}
```

Reply:

```json
{
  "schemaVersion": 1,
  "type": "AVAILABLE",
  "contract": "PARTY_V1_COORDINATOR",
  "serverEpoch": "opaque",
  "sessionHandle": "opaque",
  "sessionToken": "opaque",
  "sessionTokenExpiresAtServerMonoMs": 1900000,
  "maxParticipants": 5,
  "joinCodeDigits": 3,
  "reconnectGraceMs": 90000,
  "playbackPath": "SAGETV_WATCH",
  "sourceResolution": "WIZARD_MEDIAFILE"
}
```

`LIST_CLIENTS` returns currently eligible renderer `sessionHandles`. The
invitation flow is required when the Party UI offers device selection:

1. host requests LIST_CLIENTS;
2. host sends INVITE with one returned `sessionHandle`;
3. selected client receives INVITED with `partyId`, `mediaFileId` and
   `joinCodeRequired=true`;
4. invitee enters the host's three-digit code;
5. invited JOIN includes the entered code plus the INVITED `partyId` and
   `mediaFileId`.

Manual code-only JOIN is a separate valid flow and omits those two identifiers.

## 5. Party creation, joining and reconnect

### CREATE

```json
{
  "type": "CREATE",
  "mediaFileId": 42,
  "startMode": "BEGINNING"
}
```

`mediaFileId` may be omitted only when the server can identify the renderer's
currently loaded media. `startMode` is `BEGINNING` or `CURRENT_POSITION`.
Arbitrary client-supplied start positions are rejected.

CREATE, JOIN and RESUME authorize Party preparation only. They do not transfer
ordinary playback controls or watched-progress ownership to Party Mode.
Ownership transfers only after PREPARE has completed with LOADED and READY has
been accepted. Until then, ordinary playback on that renderer remains
independent.

`CREATED` returns:

- `partyId`
- creator `participantId`
- private `recoveryToken`
- three-digit `joinCode`
- code expiry
- `prepareVia:"SAGETV_WATCH"`
- canonical `snapshot`

### JOIN

```json
{
  "type": "JOIN",
  "joinCode": "042"
}
```

A join code confirms the room but never replaces normal session authorization.
When JOIN follows INVITED, `partyId` and `mediaFileId` are conditionally
required together and must match the invitation. Manual code-only JOIN omits
both.

### RESUME

```json
{
  "type": "RESUME",
  "partyId": "opaque",
  "participantId": "opaque",
  "recoveryToken": "opaque"
}
```

Resume is available for 90000 ms after connection loss and requires the same
authorized principal/LAN realm. It restores participant identity but always
requires fresh clock calibration and media preparation.

Paused Parties remain durable only while at least one participant is genuinely
active. A connected transport must produce an authenticated Party request at
least every 30 seconds; the required paused CLOCK maintenance satisfies this.
When no participant is active, the server starts the 90000 ms reconnect grace
from the last genuine activity or transport disconnect. A stale transport
reference does not keep the Party alive.

After grace expires, the participant recovery token and, when abandoned, the
Party are terminally tombstoned. RESUME returns `RESUME_EXPIRED` when only that
participant recovery grant expired, or `PARTY_EXPIRED` when the entire Party
ended. It must not recreate membership or reopen media. Explicit LEAVE
immediately tombstones that participant; LEAVE by the final participant ends
and tombstones the Party immediately.

### Host exit

Local Stop or host disconnect:

1. schedules a canonical Pause when the room was playing;
2. preserves the Party and join code;
3. transfers host authority to the earliest connected non-stopped participant;
4. sends `HOST_CHANGED` because host-only operation authority changed.

## 6. Authoritative timeline

Snapshot fields:

```json
{
  "stateVersion": 3,
  "commandSeq": 3,
  "mediaFileId": 42,
  "state": "PLAYING",
  "anchorPositionMs": 120000,
  "anchorServerMonoMs": 500000,
  "rate": 1,
  "durationMs": 3600000,
  "mediaGrowing": false,
  "availableEndMs": 3600000,
  "playableEndMs": 3600000
}
```

Projected media time at server monotonic time `t`:

```text
PAUSED  -> anchorPositionMs
PLAYING -> anchorPositionMs + max(0, t - anchorServerMonoMs)
```

Clamp the result to `0..playableEndMs`.

When `pendingCommand` exists and its deadline has passed, project from that
command's target position/state/deadline instead.

`stateVersion` is the playback generation. Ignore commands, heartbeats and
corrections for older generations.

`PARTICIPANT_STATE` is emitted only when a participant's server-authoritative
`status` or `prepared` value changes:

```json
{
  "type": "PARTICIPANT_STATE",
  "partyId": "opaque",
  "participantId": "opaque",
  "status": "READY",
  "prepared": true,
  "clockConfident": true,
  "readyForPlayback": true
}
```

It is coupled to readiness/cohort UI, not directive settlement. The server still
decides command eligibility internally. Repeated heartbeats that leave both
values unchanged produce no event. `prepared=true` means the media path is
loaded; it does not alone mean the participant may receive PLAY.
`readyForPlayback=true` is the authoritative combined readiness result for the
current timeline state, including preparation, participant status, clock
confidence and heartbeat freshness where active playback requires it.

## 7. Clock calibration

Client requests `CLOCK`. Server returns:

```json
{
  "type": "CLOCK_PROBE",
  "probeId": "opaque",
  "s0": 500000
}
```

Client records receive/send monotonic timestamps `c1` and `c2`, then sends:

```json
{
  "type": "CLOCK_REPLY",
  "partyId": "opaque",
  "probeId": "opaque",
  "c1": 499900,
  "c2": 499901
}
```

`CLOCK_MODEL` returns:

- `confident`
- `serverMinusClientMs`
- `uncertaintyMs`
- `p95RttMs`

Bootstrap with at least five successful exchanges. Use `performance.now()` on
PWA, `SystemClock.elapsedRealtime()` on Android and the platform monotonic clock
on Tizen. Never use wall time for Party deadlines.

Clock confidence expires when fewer than two accepted samples remain in the
server's 60-second rolling window. While joined to a Party, including while
paused, send one maintenance exchange every 20 seconds so at least two fresh
samples remain available.

Before sending a user ACTION, the client must verify that its latest
`CLOCK_MODEL` is still confident. If it is not, retain exactly the latest user
intent, calibrate until `confident=true`, fetch the current snapshot, and submit
that ACTION once. If the server races this check and returns
`CLOCK_UNCERTAIN`, perform the same recalibrate-and-resubmit sequence.
`CLOCK_UNCERTAIN` is a recoverable scheduling barrier: it must not put either
participant into terminal ERROR, leave the Party, or execute the action
speculatively.

Convert a server deadline to client monotonic time:

```text
clientDeadline = executeAtServerMonoMs - serverMinusClientMs
```

## 8. Preparation and readiness

### PREPARE

```json
{
  "type": "PREPARE",
  "partyId": "opaque"
}
```

The server:

1. resolves the canonical `MediaFile` and returns `PREPARE_ACCEPTED`;
2. calls ordinary SageTV Watch asynchronously;
3. seeks to the current canonical position;
4. emits `PREPARATION_RESULT status=LOADED` as soon as the requested media has a
   stable playback generation and its push/transcode delivery path is established;
5. on successful load, stores the current menu as `StartPlaybackMenu` and enters
   the standard `MediaPlayer OSD`, matching ordinary Watch Now presentation;
6. waits for client `READY` before beginning canonical Party activation.

`PREPARATION_RESULT.status` is `LOADED` or `FAILED`. A loaded result includes
`playbackGeneration` and diagnostic `delivery` metadata. The metadata describes
the normal playback route; it is not a second source API.

`LOADED` is a server-pipeline result, not a client decoder-readiness result. The
server must not wait for client `READY` before emitting it: the client cannot
send `READY` until it receives the matching `LOADED`. A growing recording may
still be completing ordinary Watch bookkeeping after OPENURL; once the exact
media, generation, and delivery pipeline are established, that bookkeeping does
not delay `LOADED`. The client remains responsible for holding presentation
behind its preparation cover until the canonical correction/reveal lifecycle.

PREPARE is idempotent for media already loaded on the current valid generation.
The server reuses the active preparation identifier and reports `LOADED` without
starting another Watch lifecycle.

The 15-second preparation timeout is a no-progress ceiling, not a client
readiness wait. Failure is returned as `PREPARATION_RESULT status=FAILED` with a
nonzero `watchCode` and a specific `code`: `WATCH_REJECTED`,
`TRANSCODE_START_FAILED`, or `NO_MEDIA_PROGRESS`. Timeout and asynchronous Watch
completion are fenced by `preparationId`; a stale callback cannot fail or
complete a newer preparation.

### READY

After preparation and confident clock calibration:

```json
{
  "type": "READY",
  "partyId": "opaque",
  "stateVersion": 0,
  "bufferAheadMs": 0
}
```

Zero buffer is valid while paused. The server verifies that the ordinary player
is actually loaded and returns a participant-specific `CORRECTION` for initial
alignment.

The client executes the correction but remains `ACTIVATING` while the player
flushes, decodes and presents the target. It acknowledges the first
post-correction frame with the `PRESENTED` ACK described below. Heartbeats sent
while activation is pending are telemetry only and never trigger another
correction.

`READY` is transition-scoped, not a polling message. If a client repeats it
after becoming ready, active or late, the server returns an idempotent response
without scheduling another correction. A current repeated `READY` also restores
that prepared participant to ready after transient post-correction buffering:

```json
{
  "type": "READY_ACK",
  "partyId": "opaque",
  "stateVersion": 0,
  "commandSeq": 0
}
```

## 9. Shared controls and scheduled commands

Client request:

```json
{
  "type": "ACTION",
  "partyId": "opaque",
  "stateVersion": 2,
  "action": "PLAY"
}
```

Supported actions:

```text
PLAY
PAUSE
JUMP
FF
REW
FF2
REW2
COMSKIP
```

`JUMP` additionally supplies `targetPositionMs`. `COMSKIP` is host-only and
uses the existing server-owned commercial markers. FF/REW deltas use existing
SageTV skip settings.

Server event/reply:

```json
{
  "type": "COMMAND",
  "partyId": "opaque",
  "stateVersion": 3,
  "commandSeq": 3,
  "directiveId": "opaque-per-participant",
  "cause": "PLAY",
  "targetPositionMs": 120000,
  "targetState": "PLAYING",
  "targetRate": 1,
  "executeAtServerMonoMs": 500750,
  "originParticipantId": "opaque"
}
```

The server freezes the ready recipient cohort at dispatch. Every recipient gets
a distinct `directiveId`; acknowledgements must echo that exact value. A
late/buffering
participant never delays ready participants and does not receive PLAY/JUMP until
it catches up. PAUSE reaches every connected prepared participant, including a
buffering participant.

While the canonical timeline is paused, an expired playback heartbeat alone
does not make a connected, prepared, clock-confident participant ineligible to
request or receive PLAY. The ACTION proves requester liveness; clock confidence
continues to protect the shared execution deadline. During active playback,
stale heartbeats still mark that participant late and exclude it from PLAY/JUMP
until it reports current playback state again.

For three or four invitees, recipient readiness and scheduling remain
per-participant. One invitee's clock, network latency, preparation, buffering,
presentation estimator, correction cooldown or directive failure never becomes
another invitee's state. Shared COMMAND generation and the final REVEAL deadline
are cohort-coupled; preparation deadlines and acknowledgements are
participant-specific.

Command lead is bounded to 300..7500 ms. It covers both the worst ready
participant p95 RTT and, once learned, that participant's robust predicted
preparation-plus-reveal latency and uncertainty.

JUMP, FF, REW, FF2, REW2, COMSKIP and HARD corrections use a two-phase visible
barrier. Their participant-specific COMMAND/CORRECTION includes:

```json
{
  "phase": "PREPARE",
  "barrier": true,
  "executeAtServerMonoMs": 497000,
  "visibleAtServerMonoMs": 500750,
  "predictedPresentationLatencyMs": 3450,
  "presentationUncertaintyMs": 300
}
```

`executeAtServerMonoMs` is the participant-specific preparation start.
`visibleAtServerMonoMs` is the tentative common visible deadline. The client
must preserve the currently visible frame, reposition the player behind that
cover, pause on the target frame, and report ARMED only after that frame is
decoded and ready to reveal:

```json
{
  "type": "ACK",
  "partyId": "opaque",
  "stateVersion": 3,
  "commandSeq": 3,
  "directiveId": "opaque-per-participant",
  "stage": "ARMED",
  "success": true,
  "sampleMonoMs": 500120,
  "positionMs": 120000,
  "state": "PAUSED"
}
```

The server does not advance the canonical timeline while any member of the
frozen ready cohort remains unarmed. Once the cohort is armed, every member
receives the same visible deadline:

```json
{
  "type": "REVEAL",
  "directiveId": "opaque-per-participant",
  "stateVersion": 3,
  "commandSeq": 3,
  "executeAtServerMonoMs": 500750,
  "targetPositionMs": 120000,
  "targetState": "PLAYING",
  "targetRate": 1
}
```

At that deadline the client removes the cover and enters the target state.
PRESENTED then reports the first screen-visible post-REVEAL frame. A participant
that fails or times out is marked late; it cannot hold an armed cohort forever.
Ordinary PLAY, PAUSE and SOFT frame corrections remain single-phase.

The client may report receipt and dispatch-time execution:

```json
{
  "type": "ACK",
  "partyId": "opaque",
  "stateVersion": 3,
  "commandSeq": 3,
  "directiveId": "opaque-per-participant",
  "stage": "EXECUTED",
  "success": true
}
```

`EXECUTED` means the player operation was issued. It does not mean activation
has completed. PLAY and SOFT corrections remain unsettled until the first
post-directive frame is visible. Barrier operations require ARMED, REVEAL and
then PRESENTED:

```json
{
  "type": "ACK",
  "partyId": "opaque",
  "stateVersion": 3,
  "commandSeq": 3,
  "directiveId": "opaque-per-participant",
  "stage": "PRESENTED",
  "success": true,
  "sampleMonoMs": 501020,
  "positionMs": 120270,
  "state": "PLAYING"
}
```

The first valid `PRESENTED` acknowledgement for a shared transition establishes
the canonical mapping between server monotonic time and the frame visible on
screen. Other recipients settle independently against that mapping. No later
ACTION may overlap a transition that has not established this mapping. PAUSE
may settle at `EXECUTED` because it does not require a new frame.

A failed directive marks only that participant late. A stale or mismatched
`directiveId` is rejected and cannot settle a newer seek.

ACK is a request and always receives either `ACK` or `ERROR`; it is not a
one-way notification. `commandSeq=0` is valid for the initial alignment
correction. The only valid stages are `EXECUTED`, `ARMED` and `PRESENTED`; the
removed `RECEIVED` stage is invalid.

For a matching pending directive, an invalid `PRESENTED` fails that directive
immediately, marks the participant late and returns the precise error. It is not
left activating until timeout. Validation errors include:

- `STALE_STATE` — `stateVersion` is not the pending/current generation. The
  ERROR includes request type, Party ID, supplied state version/command
  sequence, and authoritative state version/command sequence;
- `STALE_DIRECTIVE` — `directiveId` or `commandSeq` does not identify the
  participant's pending directive;
- `CLOCK_UNCERTAIN` — no trustworthy monotonic conversion is available;
- `STALE_SAMPLE` — normalized `sampleMonoMs` is more than five seconds old,
  exceeds the larger of advertised clock uncertainty or 50 ms into the future,
  or is not newer than the participant's previous accepted sample;
- `INVALID_PRESENTATION` — ARMED is not paused near the target frame, or the
  final visible state is not `PLAYING`/`PAUSED` and equal to the directive
  target state;
- `EARLY_PRESENTATION` — the reported visible frame predates the scheduled
  execution window.

`ACTIVATION_EXPIRED` is reserved for a valid directive for which no settling
`PRESENTED` arrives within 20 seconds. It must not be used to hide a rejected
PRESENTED ACK.

## 10. Heartbeats and correction

During activation or buffering, heartbeats may report state but are excluded
from drift decisions. After `PRESENTED`, send sparse heartbeats about every two
seconds while playing and every five seconds while stably paused:

```json
{
  "type": "HEARTBEAT",
  "partyId": "opaque",
  "stateVersion": 3,
  "sampleMonoMs": 499900,
  "positionMs": 119900,
  "bufferAheadMs": 2000,
  "state": "PLAYING"
}
```

Allowed states:

```text
PLAYING
PAUSED
BUFFERING
STOPPED
ERROR
```

`positionMs` is observed player media time, not downloaded bytes or transport
position. `BUFFERING` is telemetry and affects only that participant.

`HEARTBEAT_ACK` includes:

- current `stateVersion` and `commandSeq`
- `desiredState`
- projected `desiredPositionMs`
- observed `skewMs`
- `activationPending`

Correction policy:

| Condition | Result |
| --- | --- |
| activation pending | telemetry only; no drift decision |
| BUFFERING | remember starvation; no drift decision |
| absolute drift at or below 67 ms | ignore as roughly one or two frames |
| 67–750 ms for three consecutive post-activation heartbeats | one `SOFT` hold/skip correction |
| above 750 ms or wrong play/pause state | one `HARD` reposition |
| visible drift after starvation | one `HARD` recovery reposition |
| ERROR | mark participant late |

Corrections are rate-limited to one per participant per ten seconds. A new
correction is forbidden while that participant's previous directive is still
awaiting `PRESENTED`, preventing seek/flush/re-evaluate loops.

`CORRECTION` contains the same participant-specific `directiveId`,
position/state/rate/deadline fields as a command plus a reason and `kind`.
`SOFT` means hold or skip rendered frames without seeking. `HARD` means
reposition the player through ARMED/REVEAL. Both settle with `PRESENTED`;
neither changes the canonical timeline generation.

For each participant the server keeps two bounded nine-sample rolling models:
preparation start to normalized ARMED time, and REVEAL deadline to normalized
PRESENTED time. Three samples are required in each model before compensation is
trusted. The center is the median; uncertainty is derived from median absolute
deviation; impossible samples are discarded; total compensation is clamped to
five seconds. Barrier wait time is therefore never learned as player latency.
ACK arrival time is never used. SNAPSHOT roster entries expose confidence,
predicted total presentation latency and uncertainty for diagnostics.

## 11. Local controls

Captions, audio-track selection, subtitle styling and volume remain local.

Local Stop is not a shared Stop command. Report it as:

```json
{
  "type": "HEARTBEAT",
  "state": "STOPPED",
  "positionMs": 120000,
  "bufferAheadMs": 0
}
```

The participant must PREPARE and READY again before receiving PLAY/JUMP.

## 12. Leave, end and expiry

- `LEAVE` removes only the requesting participant and commits its watched/resume
  position through existing SageTV policy.
- LEAVE by the final participant ends the Party immediately.
- host-only `END` ends the room.
- Parties expire six hours after creation.
- A Party with no remaining members is removed immediately.
- A Party with no genuinely active participant expires after reconnect grace,
  even if a stale transport object still appears connected.

Server shutdown clears ephemeral Party state without stopping ordinary playback.

## 13. Client adapter acceptance

Before advertising the required capabilities, each platform must prove:

1. ordinary media preparation uses its production player/source path;
2. preparation remains paused until correction or command execution;
3. server monotonic deadlines are converted correctly;
4. stale generations are ignored;
5. observed media time is trustworthy;
6. local Stop is intercepted and reported;
7. one buffering participant does not pause or delay another;
8. PAUSE works while a participant reports buffering;
9. reconnect requires fresh preparation and clock calibration;
10. paused sessions maintain clock confidence without user interaction;
11. `CLOCK_UNCERTAIN` recalibrates and retries the latest user ACTION once;
12. disruptive reposition preserves the old visible frame until REVEAL;
13. ARMED is sent only after the target frame is decoded and held paused;
14. REVEAL releases at the server-monotonic deadline and PRESENTED reports the
    first actually visible frame;
15. captions/audio/subtitle changes remain local.
