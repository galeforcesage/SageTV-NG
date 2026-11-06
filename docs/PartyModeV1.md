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

## 2. Enablement and negotiation

Set `party/enabled=true` and restart the SageTV JVM. Party Mode remains disabled
by default until physical client acceptance passes.

An eligible renderer advertises all of:

```text
PARTY_V1
PARTY_JOIN_CODE_V1
PARTY_MONOTONIC_V1
PARTY_SCHEDULED_V1
PARTY_HEARTBEAT_V1
PARTY_PLAYER_ADAPTER_V1
PARTY_LOCAL_STOP_V1
```

Growing recordings additionally require:

```text
PARTY_GROWING_MEDIA_V1
```

These tokens assert real adapter behavior. Do not advertise them from a client
that cannot prepare ordinary playback, use a monotonic clock, execute a future
deadline, report observed media time and intercept local Stop.

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

`LIST_CLIENTS` returns currently eligible renderer session handles for optional
invitation UI. Handles are not participant identities.

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

An invitation may additionally supply the expected `partyId` and `mediaFileId`;
they must match. A join code confirms the room but never replaces normal session
authorization.

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

There is no tombstone `REJOIN`. After reconnect grace expires, join again with
the room code as a new participant.

### Host exit

Local Stop or host disconnect:

1. schedules a canonical Pause when the room was playing;
2. preserves the Party and join code;
3. transfers host authority to the earliest connected non-stopped participant;
4. re-sends `JOIN_CODE` to a still-connected stopping host.

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
4. verifies the expected playback generation and holds that player paused;
5. on successful load, stores the current menu as `StartPlaybackMenu` and enters
   the standard `MediaPlayer OSD`, matching ordinary Watch Now presentation;
6. emits `PREPARATION_RESULT`.

`PREPARATION_RESULT.status` is `LOADED` or `FAILED`. A loaded result includes
`playbackGeneration` and diagnostic `delivery` metadata. The metadata describes
the normal playback route; it is not a second source API.

Presentation occurs only for a verified `LOADED` preparation. A failed,
cancelled or superseded preparation does not navigate. Entering the standard
playback UI does not release the paused hold; only a canonical correction or
scheduled command may begin playback.

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

The client executes the correction and begins `HEARTBEAT` reporting. Corrections
are one-way; subsequent heartbeats report the observed player state.

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
SEEK
FF
REW
FF2
REW2
COMSKIP
```

`SEEK` additionally supplies `targetPositionMs`. `COMSKIP` is host-only and uses
the existing server-owned commercial markers. FF/REW deltas use existing SageTV
skip settings.

Server event/reply:

```json
{
  "type": "COMMAND",
  "partyId": "opaque",
  "stateVersion": 3,
  "commandSeq": 3,
  "cause": "PLAY",
  "targetPositionMs": 120000,
  "targetState": "PLAYING",
  "targetRate": 1,
  "executeAtServerMonoMs": 500750,
  "originParticipantId": "opaque"
}
```

The server freezes the ready recipient cohort at dispatch. A late/buffering
participant never delays ready participants and does not receive PLAY/SEEK until
it catches up. PAUSE reaches every connected prepared participant, including a
buffering participant.

While the canonical timeline is paused, an expired playback heartbeat alone
does not make a connected, prepared, clock-confident participant ineligible to
request or receive PLAY. The ACTION proves requester liveness; clock confidence
continues to protect the shared execution deadline. During active playback,
stale heartbeats still mark that participant late and exclude it from PLAY/SEEK
until it reports current playback state again.

Command lead is bounded to 300..2500 ms from the worst ready participant p95 RTT.

Client may report receipt or execution:

```json
{
  "type": "ACK",
  "partyId": "opaque",
  "stateVersion": 3,
  "commandSeq": 3,
  "stage": "EXECUTED",
  "success": true
}
```

ACK diagnostics do not block later room control. A failed execution marks only
that participant late.

## 10. Heartbeats and correction

Send every second while playing or converging and every five seconds while
stably paused:

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

Correction policy:

| Condition | Result |
| --- | --- |
| absolute drift below 100 ms | none |
| 100–750 ms for three consecutive heartbeats | one hard correction |
| above 750 ms or wrong play/pause state | immediate hard correction |
| BUFFERING or ERROR | mark participant late; do not correct |

Hard corrections are rate-limited to one per participant per ten seconds.

`CORRECTION` contains the same target position/state/rate/deadline fields as a
command plus a reason. It never changes the canonical timeline generation.

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

The participant must PREPARE and READY again before receiving PLAY/SEEK.

## 12. Leave, end and expiry

- `LEAVE` removes only the requesting participant and commits its watched/resume
  position through existing SageTV policy.
- host-only `END` ends the room.
- Parties expire six hours after creation.
- A Party with no remaining members is removed immediately.

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
10. captions/audio/subtitle changes remain local.
