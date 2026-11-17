# Party Mode v0.10: coordinator and player-adapter contract

Status: pre-release contract building toward v1.00. The stable wire property and
capability namespace remains `PARTY_V1`; the document/package release increments
from v0.01 in 0.01 increments until physical client acceptance declares v1.00. The failed
recovery-heavy Party contract is removed in place. Android, PWA and Tizen clients
must implement this document together; there is no compatibility mode for the
removed contract.

Pre-release revisions are lockstep and explicitly negotiated. v0.10 clients
advertise `PARTY_CONTRACT_REV_010` and send `contractRevision=10` in HELLO. The
server returns the same revision in AVAILABLE and binds it to the session token.
CREATE, JOIN and RESUME revalidate that binding, so an older client cannot enter
a v0.10 room despite sharing the stable `PARTY_V1` property namespace.

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

For server-owned MiniClient push playback, a directive that repositions enters
the existing `VideoFrame` job queue as one `TIME_SET`, followed by the target
`PLAY` or `PAUSE` state. State-only PLAY and PAUSE queue only the state change.
Pull/MSE/AVPlay/Media3 adapters apply the same ownership rule locally against
their ordinary source.

Party Mode never introduces a second source resolver or a parallel media loader.

Reposition ownership is exact and singular:

- server-owned push uses one `TIME_SET`;
- pull/local adapters perform one local seek;
- PLAY, PAUSE and REVEAL change state only and never add another seek;
- REVEAL releases the frame prepared by the preceding barrier.

The adapter must temporarily suppress audio before disruptive barrier
preparation so old-position audio cannot leak behind the visual cover. Party
suppression is an overlay, not a saved user setting:

- retain the latest user mute and volume intent independently throughout the
  barrier;
- effective output remains suppressed while the Party overlay is active;
- at successful REVEAL, remove only the Party audio overlay and recompute output
  from the latest user intent;
- cancellation, failure or timeout before REVEAL must not uncover a staged
  target while the old canonical timeline remains authoritative. Keep the
  staged generation paused and covered, or stop it before uncovering. Reconcile
  through explicit PREPARE/READY, ordinary Watch, Stop, LEAVE or END;
- LEAVE, END, local Stop or source replacement must stop or replace the staged
  Party generation before removing the visual cover, then remove only Party
  audio suppression.

Never restore a stale mute/volume snapshot or unmute a user-muted player. This
is a required barrier invariant, not a diagnosis of an earlier silence report.

### Direct-push epoch/origin extension

Party direct push requires `PARTY_PUSH_EPOCH_ORIGIN_V1`. After every FLUSH or
initial push open, the server parses a sample from the new stream epoch and
sends media command 42 before sending any PUSHBUFFER bytes from that epoch. The
command carries the monotonically increasing stream epoch plus one truthful
same-sample pair: recording-relative landed position and raw media timestamp.
The client installs that mapping atomically and returns ACK `1` before accepting
new-epoch bytes.

The client discards all old demux packets, decoded frames, rendered frames and
pending position replies before acknowledging FLUSH. It reports the raw media
timestamp of the rendered frame; the server maps it to recording time with the
announced pair. Neither side derives the origin from the old position or the
requested seek target. Missing capability, malformed payload, timeout or any
ACK other than `1` makes direct push Party-ineligible. Legacy and non-Party push
sessions remain byte-for-byte unchanged.

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
PARTY_CONTRACT_REV_010
PARTY_JOIN_CODE_V1
PARTY_INVITATION_V1
PARTY_MONOTONIC_V1
PARTY_SCHEDULED_V1
PARTY_HEARTBEAT_V1
PARTY_PLAYER_ADAPTER_V1
PARTY_OPEN_BINDING_V1
PARTY_PRESENTED_FRAME_V1
PARTY_ARMED_REVEAL_V1
PARTY_LOCAL_STOP_V1
```

An eligible direct-push route additionally requires:

```text
PARTY_PUSH_EPOCH_ORIGIN_V1
```

Growing recordings additionally require:

```text
PARTY_GROWING_MEDIA_V1
```

These tokens assert real adapter behavior. Do not advertise them from a client
that cannot prepare ordinary playback, use a monotonic clock, execute a future
deadline, report the required generation-correlated output evidence and
subsequent observed media time, prepare a decoded frame without revealing it,
release that frame at a future reveal deadline, and intercept local Stop.

The revision token is required in addition to the generic behavior tokens.
HELLO from a missing, lower or higher unsupported revision fails
`CONTRACT_REVISION_UNSUPPORTED`; the server must not issue a Party session token
to that transport. Revision is rechecked on CREATE, JOIN, RESUME and token
renewal. Mixed pre-release room membership is forbidden.

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
  "type": "HELLO",
  "contractRevision": 10
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

`HELLO` is also the session-token renewal operation. It is accepted without the
current token, including after token expiry, but still requires a new
`requestId` and a strictly newer `requestSeq` on the same transport. A joined
client must serialize renewal with its other requests:

1. when its server-time estimate enters the final 60 seconds before
   `sessionTokenExpiresAtServerMonoMs`, stop issuing non-HELLO requests;
2. send one fresh `HELLO`;
3. atomically replace `serverEpoch`, `sessionToken`, and token expiry from the
   correlated `AVAILABLE`;
4. resume other requests only after that replacement.

The old session token becomes invalid immediately when a new one is issued.
Renewal on the same admitted transport preserves Party membership, participant
identity, prepared state, clock model and pending directive state. It does not
replay or settle any operation. If the client is joined, `AVAILABLE` also
returns the unchanged principal-bound `recoveryToken` with its separately
extended expiry. A recovery token is not a session token and is used only by
`RESUME` after transport loss. On a new transport, run HELLO and then RESUME
with the retained participant identity and recovery token.

A non-HELLO request after token expiry fails `SESSION_EXPIRED`. A request using
the superseded token fails `SESSION_DENIED`. If more than 60 seconds remain,
HELLO may return the current token rather than rotate it.

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
- `MEDIA_OPEN_BINDING` installs the one-shot Party identity before production
  Watch opens media;
- `PARTICIPANT_STATE` reports an actual status/prepared-state transition so the
  host can determine cohort readiness.
- `HOLD_CONFIRM` requests fresh proof that an accepted ARMED frame is still
  held before REVEAL;
- `CANCEL` fences an exact unsettled directive before replacement.

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
| HELLO `contractRevision` | Required; v0.10 sends `10` |
| `serverEpoch`, `sessionToken` | Required on every request except HELLO |
| `partyId` | Required on Party-member requests; forbidden on code-only manual JOIN |
| JOIN `partyId` + `mediaFileId` | Conditionally required together when JOIN follows INVITED |
| CREATE `mediaFileId` | Required unless the server can resolve the renderer's currently loaded media |
| CREATE `startMode` | Optional/defaulted to `BEGINNING` |
| ACTION `targetPositionMs` | Required for JUMP; forbidden for actions that do not use an explicit target |
| ACK `success` | Required for EXECUTED, ARMED, HELD and PRESENTED |
| ACK `sampleMonoMs`, `positionMs`, `state`, `sampleUncertaintyMs` | Required for successful ARMED, HELD and PRESENTED; forbidden for plain EXECUTED |
| covered PLAYING PRESENTED `frameMonoMs`, `coverRemovedMonoMs` | Required; `frameMonoMs >= coverRemovedMonoMs`, `sampleMonoMs=frameMonoMs`, and `positionMs` belongs to that exposed frame |
| failed ACK `failureCode` | Required when `success=false`; frame fields are optional and must be omitted when no truthful evidence exists |
| ARMED response `accepted` | Required; `true` only when this observation has admitted the participant to the frozen barrier cohort |
| HOLD_CONFIRM `confirmByServerMonoMs` | Required; client must return successful HELD before this deadline |
| playing HARD `recoveryAttemptId`, `recoveryDeadlineServerMonoMs` | Required and retained unchanged across every rebase replacement |
| directive `phase`, `barrier`, `visibleAtServerMonoMs` | Required together for barrier PREPARE; absent on single-phase directives |
| `predictedPresentationLatencyMs`, `presentationUncertaintyMs` | Required diagnostic scheduling inputs on barrier PREPARE |
| `preparationBinding` | Required on PREPARE_ACCEPTED and PREPARATION_RESULT and on the corresponding adapter media-open event |
| PREPARE_ACCEPTED `openReused` | Required; true skips MEDIA_OPEN_BINDING and requires fresh `MEDIA_OPENED reused=true` confirmation |
| MEDIA_OPEN_BINDING `preparationId`, `preparationBinding` | Required; server sends before starting a new production media open |
| PWA MEDIA_OPEN_BINDING_ACK `playbackSessionId` | Required for fetch-controlled HTTP/MSE/HLS; opaque bridge-allocated ID copied exactly to `X-Playback-Session-Id` on every request for that production open |
| MEDIA_OPENED `preparationId`, `preparationBinding`, `localOpenGeneration`, `surfaceGeneration`, `reused` | Required authenticated client request; server ACK gates READY |
| CANCEL `stateVersion`, `commandSeq`, `directiveId`, `reason` | Required together; identify the exact unsettled directive being cancelled |
| READY `bufferAheadMs` | Optional/defaulted to zero |
| HEARTBEAT `bufferAheadMs` | Optional/defaulted to zero |

If a condition is true, its conditionally required fields are mandatory.

#### v0.10 frozen barrier lifecycle schemas

These shapes are complete for the protocol-freeze messages; implementations
must reject missing required fields and unknown enum values rather than infer
state from arrival order:

| Message | Required fields | Success/result |
| --- | --- | --- |
| `MEDIA_OPEN_BINDING` event | `partyId`, `preparationId`, `preparationBinding` | Client sends authenticated `MEDIA_OPEN_BINDING_ACK` with the same fields and, for PWA HTTP routes, required `playbackSessionId`; server ACKs before route-specific binding installation |
| `MEDIACMD_PARTY_OPEN_BINDING` command 41 | `v=1`, `partyId`, `preparationId`, `preparationBinding`, `mediaFileId`, `openOrdinal` | 32-bit ACK `1`; every other value fails PREPARE, and ACK `1` is an explicit READY prerequisite |
| `ACK stage=ARMED` request | common authenticated request fields, directive tuple, `success`, and on success `sampleMonoMs`, `sampleUncertaintyMs`, `positionMs`, `state=PAUSED` | Response echoes request/directive/stage and required `accepted` boolean |
| `HOLD_CONFIRM` event | directive tuple, `confirmByServerMonoMs` | Client answers with a new authenticated `ACK stage=HELD` |
| `ACK stage=HELD` request | same successful observation fields as ARMED, proving the same local open/surface/frame identity | Response echoes request/directive/stage and `accepted`; only accepted HELD participates in REVEAL |
| `REVEAL` event | directive tuple, `executeAtServerMonoMs`, `targetPositionMs`, `targetState`, `targetRate` | Client schedules exactly this reveal or sends negative PRESENTED; it never replies with another event type |
| `ACK stage=PRESENTED` request | common authenticated request fields, directive tuple, `success`; successful evidence fields follow target-state rules above | Response settles only this participant; negative ACK marks it LATE |
| `CANCEL` event | directive tuple, `reason` | No ACK request; client fences callbacks immediately and ignores every later message for that directive except an exact duplicate CANCEL |

`directive tuple` means `partyId`, `stateVersion`, `commandSeq`, and
participant-specific `directiveId`. Error responses include `requestId` when
failing a request and the authoritative directive/generation when known.

### 3.3 Required ordered sequences

#### New participant activation

These steps are ordered. Do not start a later step before the prior step
completes successfully.

| Order | Client | Server result | Required before next step |
| --- | --- | --- | --- |
| 1 | `HELLO` | `AVAILABLE` | Save `serverEpoch` and `sessionToken` |
| 2 | Host: `LIST_CLIENTS` then `INVITE`; invitee: receive `INVITED` | `CLIENTS`, `ACK`, then `INVITED` | Required when using the invitation UI; manual code entry skips this order |
| 3 | `CREATE` or `JOIN` | `CREATED` or `JOINED` | Save Party and participant identifiers |
| 4A | five `CLOCK` / `CLOCK_REPLY` exchanges | `CLOCK_MODEL` | `confident=true` |
| 4B | `PREPARE` | `PREPARE_ACCEPTED`; for a new Android open, MEDIA_OPEN_BINDING -> MEDIA_OPEN_BINDING_ACK -> command 41/ACK `1` -> production open -> MEDIA_OPENED/ACK; matching PREPARATION_RESULT may race MEDIA_OPENED | Must be `status=LOADED`; every binding must match and command-41 ACK `1` is required before READY |
| 5 | wait for both 4A and 4B | confident clock plus loaded preparation | These two branches may run in parallel; neither alone permits READY |
| 6 | `READY` | participant-specific initial `CORRECTION`; `kind=NONE` when PREPARE is already paused at the canonical target | Store its exact directive identifiers |
| 7 | execute correction semantics | optional `ACK/EXECUTED` reply | NONE preserves the prepared frame; dispatch alone does not activate |
| 8 | first visible target frame | `ACK/PRESENTED` reply | Settles initial activation |
| 9 | sparse `HEARTBEAT` | `HEARTBEAT_ACK` | Normal telemetry begins |

`RESUME` replaces CREATE/JOIN after a transport loss. Fresh CLOCK calibration
and PREPARE may again run in parallel, but READY remains gated on both.

#### Single-phase visible transition

PLAY and participant-specific NONE correction use this order. NONE never seeks:
when PREPARE left a cover in place, it schedules uncovering that already-held
frame at the directive deadline and then proves the cover-removal evidence
boundary. When no cover exists, it verifies the same frame/surface generation.
State-only PAUSE uses the related settled-hold flow in the next subsection.

1. Client sends `ACTION`, or server emits participant-specific `CORRECTION`.
2. Server returns/sends participant-specific `COMMAND` or `CORRECTION`.
3. Client executes at `executeAtServerMonoMs`.
4. Client may send `ACK/EXECUTED`.
5. For PLAY, client waits for the platform-defined output evidence for the
   first qualifying PLAYING frame. For NONE, client remains PAUSED and proves
   only paused held-frame cover removal; it does not seek or wait for a new
   player-frame callback.
6. Client sends `ACK/PRESENTED`.
7. Only after PRESENTED may normal drift heartbeats resume.

#### State-only PAUSE

An ordinary ACTION with `cause=PAUSE` is state-only:

1. execute pause at `executeAtServerMonoMs`;
2. preserve the current source, generation and media position;
3. do not seek, flush, recreate the source, or require a new frame callback;
4. send `ACK/EXECUTED`;
5. wait for the backend's pause-completion/state callback, then verify the same
   last rendered frame/PTS remains held and media position is stable across two
   observations separated by at least `max(50 ms, 2 * frameDurationMs)`;
6. send `ACK/PRESENTED` with a fresh settle observation, the actual held frame
   position and bounded `sampleUncertaintyMs`.

This PRESENTED proves settled paused output, not a newly rendered frame. A
barrier whose target state is PAUSED is different: repositioning still requires
ARMED, REVEAL and PRESENTED after cover removal.

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
5. Server delivers the correlated `ACK` response to that ARMED request as its
   own PARTY_V1 property update. It includes `accepted=true` only when the
   observation admits this participant to the frozen barrier cohort;
   `accepted=false` means the request was understood but the participant
   remains ACTIVATING and must send a newer observation. ARMED is functional
   request/response traffic and must never be treated as removable positive
   chatter.
6. Only after that property update is acknowledged does the server evaluate
   the frozen ready cohort. No participant may reveal early.
7. After all initial ARMED admissions, server sends participant-specific
   `HOLD_CONFIRM` updates. Each client proves the exact same target remains held
   with `ACK stage=HELD`; the correlated ACK response must carry
   `accepted=true`.
8. Server delivers `REVEAL` as a distinct, subsequent PARTY_V1 property update
   with the original participant directive identifiers and one common execution
   deadline. It must not be coalesced with ARMED/HELD ACKs. After the final HELD
   response is admitted, choose a fresh deadline no earlier than:

   ```text
   now + max(300 ms,
             max armed participant(2 * p95RttMs + clockUncertaintyMs))
   ```

   The earlier tentative `visibleAtServerMonoMs` may be retained only when it is
   later than this bound.
9. At the REVEAL deadline, client removes the cover and changes play/pause state
   without another seek. The server does not schedule resume until REVEAL has
   been delivered.
10. Client sends `ACK/PRESENTED` using the platform-defined output evidence
   boundary.
11. Normal heartbeats resume.

EXECUTED cannot replace ARMED. ARMED cannot replace HELD. HELD cannot replace
PRESENTED. HELD before accepted ARMED and PRESENTED before accepted HELD are
invalid for a barrier directive.

Cancellation before REVEAL is an ordered `CANCEL` update carrying the exact
`stateVersion`, `commandSeq`, `directiveId`, and a reason. The server delivers
it before any replacement directive. It never removes the cover over a
successfully staged target. The participant fences and abandons work for that
directive, remains paused and held, or is stopped before uncovering, until the
replacement PREPARE/READY, ordinary Watch, Stop, LEAVE or END reconciles it. It
must not expose that target while SNAPSHOT still projects the old canonical
timeline. A stale REVEAL received after CANCEL is rejected.

```json
{
  "type": "CANCEL",
  "partyId": "opaque",
  "stateVersion": 3,
  "commandSeq": 3,
  "directiveId": "opaque-per-participant",
  "reason": "REBASE_REQUIRED"
}
```

Reasons are `COHORT_REMOVED`, `REBASE_REQUIRED`, `SHARED_TRANSITION`,
`DEADLINE_EXHAUSTED`, `ACTIVATION_TIMEOUT`, `CLIENT_FAILED`, or `PARTY_ENDED`.
CANCEL is not a seek,
uncover, or rollback instruction; it fences the old directive and preserves the
safe held/stopped state until a later authoritative operation.

#### Recoverable clock failure

1. Retain exactly the latest user action.
2. Run CLOCK exchanges until `CLOCK_MODEL.confident=true`.
3. Fetch `SNAPSHOT`.
4. Send the retained ACTION once with the snapshot's current `stateVersion`.

Do not execute the action locally before the accepted COMMAND arrives.

#### User action while a transition is pending

The server does not queue hidden shared actions. ACTION received while another
transition is unsettled fails `TRANSITION_PENDING`. The client must not execute
it locally and must not retry the same stale request. It may retain exactly the
latest user intent, replacing any earlier retained intent. After the current
directive settles or fails:

1. fetch a fresh correlated `SNAPSHOT`;
2. discard the retained intent if it is no longer meaningful;
3. otherwise send it once as a new ACTION with a new request ID/sequence and
   the snapshot's current `stateVersion`.

LEAVE, host END, CLOCK maintenance, and SNAPSHOT are independent of this
latest-intent rule.

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
| `ACK/ARMED` | Barrier directive after the client has paused for the target; an inaccurate observation may be followed by a newer correlated observation | Single-phase directive, a new ARMED after the stage was already accepted, wrong state, or early sample; an exact same-request retry returns its cached response |
| `ACK/PRESENTED` | First visible frame for the exact pending directive | Before ARMED on a barrier, wrong state, stale sample, or wrong directive |
| `HEARTBEAT` | Prepared participant; telemetry at a fresh normalized sample, or an exact repeat of the admitted PAUSED frame | Not prepared, stale/non-increasing PLAYING sample, or mismatched PAUSED repeat |
| `JOIN_CODE` / `ROTATE_CODE` | Current host only | Non-host |
| `COMSKIP` | Current host only and marker end exists | Non-host or no usable marker |
| `LEAVE` | Current member | Non-member |
| `END` | Current host only | Non-host |

### 3.5 Coupled items

The following values and messages must be treated as one unit:

| Coupled items | Invariant |
| --- | --- |
| `serverEpoch` + `sessionToken` + token expiry | Replace all three atomically from a correlated AVAILABLE; never race renewal with other requests |
| `requestId` + complete request body | Exact retry must reuse both; never reuse an ID for different content |
| `stateVersion` + `commandSeq` + `directiveId` | Echo all three exactly in every directive ACK |
| `CLOCK_PROBE.probeId` + `c1` + `c2` | One CLOCK_REPLY answers one outstanding probe |
| `PREPARE_ACCEPTED.preparationId` + `PREPARATION_RESULT.preparationId` | Accepted preparation is incomplete until its matching result |
| `CLIENTS.sessionHandles[]` + `INVITE.sessionHandle` | INVITE must use a currently returned eligible handle |
| `INVITED.partyId` + `INVITED.mediaFileId` + JOIN | Invited JOIN echoes both identifiers together with the entered code |
| PREPARE-phase directive + ARMED request + correlated ARMED ACK response + distinct `REVEAL` + `ACK/PRESENTED` | One barrier lifecycle; no stage may be skipped, coalesced or borrowed from another directive |
| `executeAtServerMonoMs` + current clock model | Convert with the model that is confident for that deadline |
| `sampleMonoMs` + `positionMs` + `state` | One observation: same visible frame for ARMED/PRESENTED/PLAYING; BUFFERING uses fresh transition-observation time plus the last visible frame position |
| `HOST_CHANGED.hostParticipantId` + host-only operations | Authority changes immediately when this event arrives |

### 3.6 Independent items

These do not wait on or settle playback directives:

- CLOCK maintenance while joined, including while paused;
- HELLO token renewal, provided other requests are paused until the replacement token is installed;
- `SNAPSHOT` retrieval;
- local captions, audio-track selection, subtitle styling, and volume;
- sparse HEARTBEAT telemetry after activation;
- join-code rotation, except that only the host may request it.

Independent does not mean optional: CLOCK maintenance is required to preserve
future ACTION eligibility.

### 3.7 Safe parallel work

Parallel work is allowed only when it has separate completion identity and a
later explicit join point:

| Parallel work | Required isolation | Join point |
| --- | --- | --- |
| CLOCK bootstrap and PREPARE after CREATE/JOIN/RESUME | probes use `probeId`; preparation uses `preparationId` | READY waits for confident CLOCK_MODEL and matching LOADED result |
| preparation for different participants | one preparation ID and generation per participant | each participant independently becomes READY |
| barrier preparation for a frozen cohort | one directive ID per participant; no participant may borrow another's ACK | server emits REVEAL only after every remaining participant's HELD confirmation is accepted |
| CLOCK maintenance during stable playback or pause | clock replies cannot settle directives | next ACTION checks the resulting confidence |
| local captions/audio/subtitle styling/volume | must not recreate the source or change rendered media time | no Party join point unless source recreation forces PREPARE |
| independent participant media delivery | separate player/connection/backpressure | common Party deadline coordinates only visible effect |

Within one participant, PREPARE callbacks, ACK stages, heartbeat samples, and
directive effects are serialized by their identifiers and generation. Parallel
threads may perform work, but completion must re-enter the participant state
machine and pass stale-generation checks before producing an effect.

### 3.8 Functional asynchronous server events

Only these unsolicited messages remain:

| Event | Functional reason |
| --- | --- |
| `MEDIA_OPEN_BINDING` | Installs one exact one-shot preparation binding before a new production media open |
| `PREPARATION_RESULT` | Completes accepted asynchronous PREPARE |
| `INVITED` | Starts the selected invitee's join-code flow |
| `PARTICIPANT_STATE` | Reports an actual readiness/status transition |
| `COMMAND` | Directs a non-origin participant's shared transition |
| `CORRECTION` | Directs participant-specific synchronization work |
| `HOLD_CONFIRM` | Requests fresh proof that an ARMED frame is still held before REVEAL |
| `REVEAL` | Releases an armed barrier at the common deadline |
| `CANCEL` | Fences one exact unsettled directive before replacement or reconciliation |
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
  "requestSeq": 1,
  "contractRevision": 10
}
```

Reply:

```json
{
  "schemaVersion": 1,
  "type": "AVAILABLE",
  "contract": "PARTY_V1_COORDINATOR",
  "contractRevision": 10,
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

Session tokens live for 30 minutes. HELLO rotates a missing token or one with
less than 60 seconds remaining; otherwise it may return the existing token.
See the serialized renewal sequence in section 3. Joined clients receive their
existing recovery token and a separately extended recovery expiry in AVAILABLE.

The server issues AVAILABLE only when the HELLO revision and advertised
`PARTY_CONTRACT_REV_010` token both match. The admitted revision is bound to the
token and participant. CREATE, JOIN and RESUME fail
`CONTRACT_REVISION_UNSUPPORTED` if the transport no longer presents the same
revision guarantee.

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
If omitted-media resolution cannot identify and validate the renderer's current
MediaFile, CREATE fails with `CURRENT_MEDIA_UNAVAILABLE`. An explicitly supplied
but unavailable `mediaFileId` continues to fail with `MEDIA_UNAVAILABLE`.

CREATE, JOIN and RESUME authorize Party preparation only and do not mutate the
renderer. PREPARE is the explicit transition from ordinary playback into the
Party preparation plane: after `PREPARE_ACCEPTED`, the existing Watch path may
replace the renderer's source, seek it to the canonical target and hold it
paused. Ordinary playback is no longer independent on that renderer, but Party
timeline/control and watched-progress ownership do not transfer until READY is
accepted; activation completes only with PRESENTED.

If PREPARE fails or is cancelled before READY, cancel its tagged asynchronous
work and report the matching `PREPARATION_RESULT`. Never auto-play or silently
restore a guessed prior position. A source already loaded by that preparation
remains held paused until an explicit retry, normal Watch, Stop, LEAVE or END
chooses the next state.

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

The three-digit code is protected as a confirmation code, not treated as an
authentication secret:

- the transport must already have an authorized account or trusted-LAN
  principal before any JOIN attempt is processed;
- every JOIN attempt, successful or failed, counts against both limits;
- one admitted transport may make 3 attempts per rolling 60 seconds;
- one authenticated account principal may make 6 attempts across replacement
  transports per rolling 60 seconds; the shared trusted-LAN realm is excluded
  from this principal bucket because independent LAN clients share that realm;
- one three-digit join code may receive 6 attempts across principals and
  transports per rolling 60 seconds;
- the server retains a 30-attempt rolling-60-second emergency ceiling across all
  transports, principals and codes;
- excess attempts fail `JOIN_RATE_LIMITED` with `retryAfterMs`;
- CREATE/code issuance is limited to 12 codes per rolling 60 seconds and fails
  `CODE_ISSUE_RATE_LIMITED` with `retryAfterMs`;
- one Party may rotate its code at most once per 10 seconds; earlier rotation
  fails `CODE_ROTATION_COOLDOWN` with `retryAfterMs`;
- retired or expired codes remain unavailable for reuse for 120 seconds;
- the ordinary per-transport request ceiling remains 30 requests per second and
  fails `RATE_LIMITED`.

Clients must display `retryAfterMs` as a temporary wait, not repeatedly retry in
the background. Successful JOIN does not exempt an attempt from the limits.

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
  "playableEndMs": 3600000,
  "liveEdgeDelayMs": 0
}
```

`durationMs`, `mediaGrowing`, `availableEndMs`, `playableEndMs`, and
`liveEdgeDelayMs` are one ordered media-window tuple from the same SNAPSHOT.
Clients replace the entire tuple atomically and never combine fields from
different snapshots. While `mediaGrowing=true`, every participant requests
SNAPSHOT at least every two seconds during active playback and before validating
or issuing a seek. The server refreshes the window before replying. A temporary
end at `availableEndMs` is BUFFERING; drained EOS is valid only after
`mediaGrowing=false` and all accepted bytes/frames have been consumed.

Projected media time at server monotonic time `t`:

```text
PAUSED  -> anchorPositionMs
PLAYING -> anchorPositionMs + max(0, t - anchorServerMonoMs)
```

Clamp the result to `0..playableEndMs`.

When `pendingCommand` exists and its deadline has passed, project from that
command's target position/state/deadline instead.

Barrier PREPARE is never a canonical pending command. While the frozen cohort is
preparing, SNAPSHOT contains `barrierPending=true` and `barrierCommandSeq`, keeps
the previous authoritative anchor/state, and omits `pendingCommand`. Neither a
participant-specific PREPARE `executeAtServerMonoMs` nor its tentative
`visibleAtServerMonoMs` may advance projection. REVEAL commits one deadline only
after every remaining participant's HELD confirmation is accepted; subsequent
SNAPSHOT may expose a
`pendingCommand` with that exact REVEAL deadline. If every cohort member fails
or times out before REVEAL, the pending transition is cancelled and projection
continues from the previous anchor/state.

`stateVersion` is the playback generation. Ignore commands, heartbeats and
corrections for older generations.

`PARTICIPANT_STATE` is emitted only when any member of the complete
server-authoritative readiness tuple changes:

```text
(status, prepared, clockConfident, readyForPlayback)
```

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
decides command eligibility internally. Repeated activity that leaves all four
values unchanged produces no event. `prepared=true` means the media path is
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

Before starting Watch, the server derives a Party-route constraint from the
client's negotiated transport, container, decoder and epoch/origin
capabilities. Ordinary Watch remains the production source path, but its route
selector must exclude Party-ineligible routes. Direct push is eligible only when
`PARTY_PUSH_EPOCH_ORIGIN_V1` is negotiated; without it the selector uses another
eligible route or PREPARE fails without opening media.

The server:

1. resolves the canonical `MediaFile`, creates opaque `preparationBinding`, and
   returns it with `PREPARE_ACCEPTED`;
2. starts ordinary SageTV Watch asynchronously as a held provisional
   preparation under that route constraint; it may establish eligible push,
   pull or transcode delivery behind the preparation cover but cannot activate
   Party playback;
3. seeks to the current canonical position and holds it paused;
4. after the actual route is established, checks route-specific Party
   eligibility. A direct-push route that lacks the negotiated epoch/origin
   extension fails preparation rather than reaching READY;
5. emits `PREPARATION_RESULT status=LOADED` with the same
   `preparationBinding` only after the requested media has a stable playback
   generation and an eligible delivery path;
6. on successful load, stores the current menu as `StartPlaybackMenu` and enters
   the standard `MediaPlayer OSD`, matching ordinary Watch Now presentation;
7. waits for client `READY` before beginning canonical Party activation.

`PREPARATION_RESULT.status` is `LOADED` or `FAILED`. A loaded result includes
`playbackGeneration`, `preparationBinding`, and diagnostic `delivery` metadata.
The metadata describes the normal playback route; it is not a second source API.

The binding handshake is explicit and ordered:

1. `PREPARE_ACCEPTED` supplies `preparationId`, `preparationBinding`, and
   `openReused=false`.
2. Before ordinary Watch can issue OPENURL/push-open/local-open, the server sends
   `MEDIA_OPEN_BINDING` with the same Party/preparation identifiers.
3. The client installs that binding as the one-shot tag for the next production
   media open and sends `MEDIA_OPEN_BINDING_ACK` as a normal authenticated
   request. A PWA bridge also includes its preallocated opaque
   `playbackSessionId`; Android/local routes omit it. The server replies ACK and
   only then starts Watch.
4. The route's existing open metadata carries
   `partyPreparationBinding=<opaque>` to the adapter. The adapter atomically
   records `(preparationBinding, localOpenGeneration, surfaceGeneration)` when
   that open begins.
5. The client sends authenticated `MEDIA_OPENED` with those identifiers and
   generations. The server returns ACK.
6. `PREPARATION_RESULT` may race `MEDIA_OPENED`; READY waits for accepted
   PREPARE, the Party binding ACK, command-41 ACK `1` when that carrier applies,
   acknowledged MEDIA_OPENED, and LOADED result, all with the identical
   binding.

`PARTY_OPEN_BINDING_V1` is a required capability and selects fail-closed
route-specific carriers:

| Eligible route | Binding carrier and ordering |
| --- | --- |
| Android MiniClient push, pull or dynamic | New `MEDIACMD_PARTY_OPEN_BINDING` command ID 41 on the exact media-command socket. It is sent after the Party `MEDIA_OPEN_BINDING_ACK`, before STREAMINFO and before the next OPENURL. Payload uses the normal length-prefixed null-terminated UTF-8 JSON command framing. The client must return 32-bit integer `1`; `0`, timeout, parse failure or unsupported command fails PREPARE. ACK `1` is required before OPENURL and before READY. |
| PWA fetch-controlled HTTP/MSE/HLS pull | `MEDIA_OPEN_BINDING_ACK` first names the bridge's preallocated opaque `playbackSessionId`. The first manifest/media request for the new source carries both `X-Sage-Party-Preparation-Binding: <base64url binding>` and the identical `X-Playback-Session-Id`. The server validates both before returning media bytes. A binding cannot be consumed or reused by another session sharing the bridge; missing/mismatch/cross-session reuse returns HTTP 409 and fails PREPARE. Redirects must preserve same-origin validation and both headers. Browser-managed/native HLS that cannot attach them is Party-ineligible and must not advertise `PARTY_OPEN_BINDING_V1`. |
| In-process local adapter | A typed `open(media, preparationBinding)` call and typed open callback carry the exact binding; no string or "latest open" lookup is permitted. |

The PWA fetch-controlled route is an additive playback surface with the exact
identity and values below:

| Property | Required value |
| --- | --- |
| surface ID | `pwa_party_cmaf` |
| `ROUTE` | `mse` |
| `DELIVERY_MODES` | `party-cmaf` |
| `CONTAINERS` | `MP4` |
| codecs and limits | The same actually probed MSE codec lists and limits as `pwa_mse`; empty capability data makes the surface unavailable. |
| `CAP_EFFECTIVE_SURFACE` | `pwa_party_cmaf` |
| `CAP_EFFECTIVE_DELIVERY` | `party-cmaf` |

`party-cmaf` is server-servable only while Party conditioning is active.
Ordinary playback selection ignores a surface whose only delivery mode is
`party-cmaf`. PREPARE selects and commits exactly this surface before ordinary
Watch begins. MiniPlayer then uses the finalized-segment fMP4/CMAF transport and
emits the existing
`iosstream_<clientMac>_<mediaFileId>_<segment>_<bandwidth>_fmp4.m3u8` OPENURL.
The bridge maps only the exact surface/delivery pair to its fetch-controlled
`/cmaf/iosstream_...` loader. A missing or mismatched pair, a non-CMAF OPENURL,
or unavailable binding fails closed; it must not fall back to native HLS,
`pwa_native`, `pwa_mse`, `/msproxy`, or ordinary pull.

#### PWA shared-bridge playback-session binding addendum

`playbackSessionId` is the exact Party JSON field. `X-Playback-Session-Id` is
the exact HTTP carrier. They are one opaque identifier in two encodings of the
same value; comparison is case-sensitive byte-for-byte UTF-8. The identifier is
allocated by the PWA bridge before `MEDIA_OPEN_BINDING_ACK`, is non-empty, is at
most 256 UTF-8 bytes, and is unique among that bridge's concurrently active or
unsettled playback opens. It is not a Party ID, participant ID, HTTP credential,
media ID, preparation ID, or preparation binding.

For a PWA fetch-controlled route, the authenticated request is:

```json
{
  "schemaVersion": 1,
  "type": "MEDIA_OPEN_BINDING_ACK",
  "requestId": "opaque",
  "requestSeq": 11,
  "serverEpoch": "opaque",
  "sessionToken": "opaque",
  "partyId": "opaque",
  "preparationId": "opaque",
  "preparationBinding": "base64url-128-bit",
  "playbackSessionId": "bridge-opaque"
}
```

The server admits and stores the immutable tuple:

```text
(renderer session, partyId, preparationId, preparationBinding,
 mediaFileId, playbackSessionId)
```

The ACK means that exact tuple was installed before Watch may start:

```json
{
  "schemaVersion": 1,
  "type": "ACK",
  "requestId": "opaque",
  "serverMonoMs": 100120,
  "preparationId": "opaque",
  "preparationBinding": "base64url-128-bit",
  "playbackSessionId": "bridge-opaque"
}
```

The first production manifest, initialization, segment, or media request then
carries both headers:

```http
X-Sage-Party-Preparation-Binding: <base64url-128-bit>
X-Playback-Session-Id: <bridge-opaque>
```

The HTTP server resolves the renderer from the existing authenticated bridge
connection and accepts the request only if all tuple members match the stored
binding. The first accepted request consumes the one-shot open binding for that
`playbackSessionId`; subsequent requests for the same established production
open may reuse the same pair. The same `preparationBinding` with another
`X-Playback-Session-Id`, or the same playback-session ID with another live
binding, is rejected. A replacement open for one playback session retires only
that session's prior binding and cannot disturb another session on the bridge.

Missing either header, mismatch, cross-session reuse, duplicate live ownership,
cancelled/stale preparation, or an HTTP request arriving before the Party ACK is
accepted returns HTTP 409 before manifest or media bytes. The preparation fails
closed and remains covered. Redirects and retries preserve both headers.
Browser-managed/native HLS is ineligible because it cannot guarantee this
carrier. Android command-41 and in-process routes omit `playbackSessionId`.

For Android direct push, command 42 follows the successful FLUSH boundary (or
initial push setup) and precedes the first PUSHBUFFER command of the new epoch:

```text
command = 42
payload length = 28
int32 version = 1
int64 streamEpoch
int64 landedRecordingPositionMs
int64 mediaTimestampMs
response = int32 1
```

`landedRecordingPositionMs` and `mediaTimestampMs` describe the same parsed
sample. The client treats an identical repeated epoch/pair as idempotent, rejects
a lower epoch or conflicting pair, installs the mapping before ACK, and does not
emit a position sample until it renders the new epoch. Timeout, parse failure,
unsupported command or an ACK other than `1` fails the covered preparation; no
new-epoch bytes may be exposed.

The command-41 JSON schema is:

```json
{
  "v": 1,
  "partyId": "opaque",
  "preparationId": "opaque",
  "preparationBinding": "base64url-128-bit",
  "mediaFileId": 42,
  "openOrdinal": 1
}
```

The binding is 128 random bits encoded as unpadded base64url and is scoped to
one admitted Party transport, preparation and open ordinal. It is not a bearer
credential and must not be logged. `openOrdinal` is the monotonically increasing
count of every production OPENURL written on that media-command socket,
including non-Party opens. Client and server both maintain the next expected
ordinal. Command 41 is accepted only when its ordinal equals that next OPENURL;
the immediately following OPENURL advances and consumes that ordinal exactly
once.

The server serializes every command-41/STREAMINFO/OPENURL exchange per socket
under one media-open queue/lock from command-41 write through OPENURL reply. No
other OPENURL, STOP, DEINIT or binding command may interleave. A competing open
is queued until the exchange completes, or explicitly cancels the pending
preparation before any of its bytes are written; it can never consume the
binding. The client likewise processes that socket serially. Ordinal mismatch,
unexpected command ordering, STOP, DEINIT, another OPENURL, a different binding,
Party CANCEL/END/LEAVE, transport loss, timeout, or source replacement before
consumption invalidates the binding and fails PREPARE with
`PREPARATION_BINDING_MISMATCH`. STREAMINFO remains codec metadata only and is
never the binding carrier.

No untagged "next open" heuristic is allowed. A stale binding is cleared on
failure, cancellation, timeout, source replacement, transport loss, or a newer
PREPARE. A route that cannot carry the binding in its production open metadata
is Party-ineligible.

Capability negotiation establishes that the client has at least one eligible
Party path; PREPARE applies those constraints to the real Watch route selection
and then verifies the established route. If the constrained route still proves
ineligible, return `PREPARATION_RESULT status=FAILED` with nonzero `watchCode`
and `code=UNSUPPORTED_PARTY_DELIVERY`, leave the provisional source
paused/covered or stopped, and do not accept READY.

`PREPARATION_RESULT` and `PREPARE_ACCEPTED` are independently delivered
functional updates and may be observed in either order. The client buffers a
result by `preparationId` until the matching acceptance arrives. Neither message
alone permits READY.

`LOADED` is a server-pipeline result, not a client decoder-readiness result. The
server must not wait for client `READY` before emitting it: the client cannot
send `READY` until it receives the matching `LOADED`. A growing recording may
still be completing ordinary Watch bookkeeping after OPENURL; once the exact
media, generation, and delivery pipeline are established, that bookkeeping does
not delay `LOADED`. The client remains responsible for holding presentation
behind its preparation cover until the canonical correction/reveal lifecycle.

PREPARE is idempotent for media already loaded on the current valid generation.
The server reuses the active preparation identifier/binding and returns
`PREPARE_ACCEPTED openReused=true`; it does not send MEDIA_OPEN_BINDING or start
another Watch lifecycle. The client sends `MEDIA_OPENED reused=true` as a fresh
confirmation of the still-current stored binding/open/surface generations, and
the server acknowledges it before returning/replaying `LOADED`. If that stored
open no longer matches, the client rejects reuse and both sides start a new
PREPARE/binding handshake.

The 15-second preparation timeout is a no-progress ceiling, not a client
readiness wait. Failure is returned as `PREPARATION_RESULT status=FAILED` with a
nonzero `watchCode` and a specific `code`: `WATCH_REJECTED`,
`TRANSCODE_START_FAILED`, `UNSUPPORTED_PARTY_DELIVERY`, or
`NO_MEDIA_PROGRESS`. Timeout and asynchronous Watch completion are fenced by
`preparationId`; a stale callback cannot fail or complete a newer preparation.

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
is actually loaded on the same stable generation returned by the matching
`PREPARATION_RESULT`. `playbackGeneration` is an opaque server generation; it is
not numerically comparable to an Android/PWA local counter. When all three preparation events are correlated, the client binds
`(preparationId, preparationBinding, serverPlaybackGeneration,
localOpenGeneration, surfaceGeneration)` to the held source. Immediately before
READY, the preparation binding, local open and surface generations must still
match; the server independently verifies its generation and binding. Any
replacement on either side restarts PREPARE. No READY wire field is introduced.
PREPARE has already positioned and rendered a paused target;
when the same stable player generation remains paused within the visible-drift
deadband, READY returns the normal participant-specific `CORRECTION` lifecycle
with `kind=NONE`, `targetState=PAUSED`, and the prepared target position. NONE
does not seek, flush or reprime either player path. At its scheduled deadline it
removes the preparation cover over the already-held frame using the
platform-specific cover-removal evidence boundary, then settles with
PRESENTED. It must not assume PREPARE left that frame uncovered.

If PREPARE is not paused at the canonical target, READY returns the existing
participant-specific `kind=HARD` correction. The client remains `ACTIVATING`
while the player flushes, decodes and presents the target, then acknowledges the
first post-correction frame with PRESENTED. Heartbeats sent while activation is
pending are telemetry only and never trigger another correction.

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

PAUSE never takes its canonical anchor from the first participant response. At
dispatch, the server projects the existing authoritative timeline to the PAUSE
deadline and places that deterministic position in every participant command.
That position becomes the paused canonical anchor at the deadline. A participant
whose settled held frame is outside the **67 ms visible-drift deadband** is
marked LATE;
its PRESENTED cannot move the shared anchor. A buffering participant's old held
frame is therefore telemetry, not an eligible anchor. It must receive a
participant-specific paused HARD correction before it can become ready again.
Subsequent state-only PLAY includes only participants aligned to this paused
anchor, so it cannot preserve a known offset.

The deadband is the absolute recording-timeline difference between the
participant's truthful held-frame `positionMs` and the deterministic command
target. It is fixed at 67 ms, matching the existing PartyService precedent; it
is not widened by network RTT or used as a substitute for ACK timestamp
uncertainty.

For three or four invitees, recipient readiness and scheduling remain
per-participant. One invitee's clock, network latency, preparation, buffering,
presentation estimator, correction cooldown or directive failure never becomes
another invitee's state. Shared COMMAND generation and the final REVEAL deadline
are cohort-coupled; preparation deadlines and acknowledgements are
participant-specific.

Single-phase PLAY/PAUSE/NONE command lead is bounded to 300..7500 ms. Barrier
PREPARE may take longer: its tentative visible estimate is diagnostic and does
not cap cohort preparation. Every barrier has one 20000 ms overall activation
deadline measured from the original directive dispatch, including all hold
confirmation and participant-specific HARD replacement directives.

The original ARMED event proves when the target frame first became ready; it
does not have to remain younger than five seconds while slower participants
prepare. After the final initial ARMED admission, the server sends one
`HOLD_CONFIRM` update to each accepted cohort member. The client verifies that
the exact same frame identity/position remains held on the same
open/surface/directive generations and replies with `ACK stage=HELD`, a fresh
sample and bounded uncertainty. HELD does not seek, decode, reveal, or reset the
overall activation deadline.

A final REVEAL deadline must satisfy:

```text
presentationReserveMs =
  clamp(max(1000 ms,
            p95PostRevealEvidenceMs + p95RttMs + clockUncertaintyMs),
        1000 ms, 5000 ms)

deadline >= finalHeldAdmissionTime + requiredDeliveryLead
deadline <= oldestAcceptedHeldSampleTime + 5000 ms
deadline + presentationReserveMs <= directiveDispatchTime + 20000 ms
```

If no deadline satisfies the intersection, the server must not reveal. It
removes an unarmed/unconfirmed participant from a shared fixed-target cohort
when the remaining cohort can still proceed, or sends correlated CANCEL. The
activation timeout never authorizes a late REVEAL. This permits observed
multi-participant preparations whose ARMED reports are nine seconds apart while
still requiring a fresh held-frame confirmation immediately before scheduling
visibility.

The server must deliver the distinct REVEAL property update to every remaining
cohort member before the common deadline. A participant whose delivery fails
before that deadline is removed and receives correlated CANCEL; if the reduced
cohort can no longer proceed, the whole directive is cancelled. Once the common
deadline passes, the canonical transition is committed and is never rolled
back because one participant misses PRESENTED. Such a participant is marked
LATE and enters participant-only recovery. A client must never uncover a
CANCELLED, superseded or late-delivered directive. Failure after a valid reveal
does not authorize replaying that old reveal.

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
`visibleAtServerMonoMs` is only a tentative scheduling estimate. Neither field
commits or projects the canonical transition. The client
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
  "sampleUncertaintyMs": 24,
  "positionMs": 120000,
  "state": "PAUSED"
}
```

The server holds the canonical pending deadline outside projectable time while
any member of the frozen ready cohort remains unarmed. A pre-REVEAL SNAPSHOT
sets `barrierPending=true`, omits `pendingCommand`, and continues to project the
old anchor/state even after the tentative visible time. Once the remaining
cohort is armed, the server requests a fresh hold confirmation:

```json
{
  "type": "HOLD_CONFIRM",
  "partyId": "opaque",
  "stateVersion": 3,
  "commandSeq": 3,
  "directiveId": "opaque-per-participant",
  "confirmByServerMonoMs": 500500
}
```

The client answers with a new request ID and `stage=HELD`, reporting the same
held frame position/identity and current observation time. HELD is accepted only
when the directive, preparation binding, local open generation, surface
generation, paused state and held frame all still match ARMED. An accepted HELD
is a confirmation, not a second ARMED stage or a new preparation sample.

After all remaining cohort HELD responses are accepted, the server commits the
canonical pending command to the REVEAL deadline and every member receives that
same deadline:

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
For PLAYING barriers, PRESENTED requires both a frame-correlated output event
and active-surface cover-removal completion for the same directive and surface
generation. A frame event that precedes cover removal is discarded for
PRESENTED. After cover removal completes, the client reports the first
frame-correlated output event at or after that boundary. The ACK reports
`coverRemovedMonoMs`, `frameMonoMs >= coverRemovedMonoMs`, and
`sampleMonoMs=frameMonoMs`; `positionMs` belongs to that exposed frame.
For PAUSED, PRESENTED reports the supported
cover-removal evidence boundary exposing the already-held target frame; no new
player frame is required. A participant
that fails or times out is marked late and removed from the barrier cohort; it
cannot hold the remaining armed participants forever. If the cohort becomes
empty before REVEAL, the server cancels the transition and the old canonical
anchor/state remain authoritative. Ordinary PLAY and NONE corrections remain
single-phase. Ordinary PAUSE is state-only but settles only after its held frame
and paused state are confirmed with PRESENTED. `CORRECTION kind=NONE` is valid
only for an already-aligned PAUSED target hidden behind the preparation cover:
it removes that cover at the deadline and reports PAUSED PRESENTED from
cover-removal evidence. It does not start playback or wait for a post-cover
playing-frame callback.

A participant-specific HARD correction against an already-playing canonical
timeline is not a fixed-target shared seek. The server maintains bounded
per-route p95 estimates for preparation, HELD confirmation, REVEAL delivery and
post-REVEAL evidence. For each attempt it chooses:

```text
candidateReveal =
  now + p95PrepareMs + p95HeldMs + requiredDeliveryLead +
        presentationReserveMs
targetPosition = projectCanonical(candidateReveal)
```

Both values must fit before `recoveryDeadlineServerMonoMs`. ARMED and HELD must
prove that exact target remains held. Immediately before committing REVEAL, the
server projects the current canonical timeline to the final candidate deadline.
If that position differs from the held target by more than 67 ms, it sends
correlated CANCEL and issues a replacement HARD correction with a new directive
ID and freshly predicted future target. The replacement retains the original
`recoveryAttemptId` and `recoveryDeadlineServerMonoMs`; retries never reset the
overall 20000 ms budget.

Any accepted shared PLAY, PAUSE, JUMP or rate-changing action increments the
shared command generation and fences every in-flight participant recovery.
The server sends correlated `CANCEL reason=SHARED_TRANSITION`; all old recovery
callbacks and REVEALs are stale. After the shared action settles, the late
participant may start a new recovery against the new authoritative timeline
within a new recovery attempt. Recovery never changes the shared canonical
anchor/state/command sequence, never adds healthy participants to its barrier,
and never blocks their ACTIONs. On expiry the participant remains LATE. Never
reveal an obsolete catch-up frame and then assume rate 1.0 will close the
permanent offset.

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

`EXECUTED` means the player operation was issued. For PLAY, PAUSE and NONE it
does not mean activation has completed; each remains unsettled until its
required PRESENTED evidence. Barrier operations require ARMED, REVEAL and then
PRESENTED:

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
  "sampleUncertaintyMs": 28,
  "positionMs": 120270,
  "state": "PLAYING"
}
```

The canonical target/deadline mapping is established deterministically from the
authoritative timeline and command, never selected by the first participant
response. Valid PRESENTED acknowledgements verify each participant against that
mapping. Other recipients settle independently. No later ACTION may overlap a
transition that has not settled or cancelled.

### 9.1 What PRESENTED means

PRESENTED is the strongest supported frame-correlated output evidence for the
backend. Depending on the platform, that boundary is compositor submission,
compositor selection, a native transaction commit, or the explicitly weaker PWA
post-removal rendering opportunity below. It is never a claim about physical
panel scanout. Decoder readiness, buffer availability, a completed seek, a
scheduled release time, or a dispatched player operation are insufficient.
`sampleMonoMs`, `positionMs`, and `state` must describe the same
frame/directive generation.

For a covered PLAYING barrier, frame evidence alone is insufficient: the client
must also prove that cover removal completed on the active surface generation.
The ACK carries both monotonic boundaries and the logical PRESENTED event is
their later value. This proves only the strongest platform-observable visibility
opportunity, not viewer-visible photons.

If REVEAL is received after its local deadline by more than the clock model's
uncertainty, after a correlated CANCEL, after the activation ceiling, or after
the HELD confirmation can no longer satisfy the 5000 ms admission age, the
client must not uncover. It sends `ACK/PRESENTED success=false` with
`failureCode=MISSED_REVEAL_DEADLINE` or `CANCELLED_DIRECTIVE`, remains held, and
waits for reconciliation. The server marks it LATE and sends/delivers CANCEL
before any replacement.

- **Android playing output:** correlate the frame's media timestamp and player,
  source and surface generations with an event that occurs after that frame is
  released/submitted to the active output surface. Media3
  `onVideoFrameAboutToBeRendered` may identify the candidate timestamp but is
  pre-render evidence; `onRenderedFirstFrame` alone is not guaranteed for every
  PLAY/REVEAL and does not identify the frame. A client must instrument an
  appropriate renderer/output callback or not advertise
  `PARTY_PRESENTED_FRAME_V1` for that backend. Callback arrival time is not the
  frame time: use a correlated backend event timestamp converted into the Party
  monotonic domain. Delayed or batched delivery is allowed only when each event
  retains its own timestamp and the conversion/delivery uncertainty has a
  measured finite bound. Timestamp-free or unbounded observations are
  ineligible.

  The evidence and visual hold must apply to the active video output surface,
  including an external display. An Activity-window cover on the phone does not
  cover video routed to another display or a separately composed surface.
  Every observation carries the current surface generation locally; a surface
  move/recreation invalidates ARMED/PRESENTED evidence and requires fresh
  preparation. `PixelCopy` is optional diagnostic verification, not
  presentation evidence; it requires API 24+, may fail for protected surfaces,
  and failure makes a PixelCopy-dependent backend ineligible.

  A timestamp-free Android `SurfaceControl.Transaction` committed callback
  (Java API 33+) confirms that commit occurred sometime after transaction
  submission and no later than callback arrival; it does not timestamp the
  commit or physical exposure. Use callback arrival as the conservative
  `coverRemovedMonoMs` boundary and include at least
  `callbackArrivalMonoMs - transactionSubmitMonoMs` plus measured scheduler
  jitter in `sampleUncertaintyMs`. A timestamped native transaction callback
  may use its correlated event timestamp with measured conversion uncertainty.
  Neither form is scanout evidence.
- **PWA covered PLAYING output:** remove the cover in the first foreground rAF
  and use the second rAF as `coverRemovedMonoMs`, then accept only the first
  `requestVideoFrameCallback` whose `metadata.presentationTime` is at or after
  that boundary for the same video/MSE/directive generation. Report that value
  as both `frameMonoMs` and `sampleMonoMs`, and report its `mediaTime` as
  `positionMs`. Discard any rVFC selected before cover removal. Include both rAF
  delay and rVFC callback/conversion delay in `sampleUncertaintyMs`. This proves
  only a browser rendering opportunity followed by compositor frame selection,
  not physical scanout. `seeked`, `playing`,
  `timeupdate`, `canplay`, `SourceBuffer.updateend`, and MSE append completion
  are insufficient.
- **PWA paused barrier reveal:** no standard browser API confirms compositor
  commit or physical exposure of an unchanged paused video frame. The supported
  evidence is a foreground post-removal rendering opportunity:
  1. ARMED must already have correlated the held target through
     `requestVideoFrameCallback.mediaTime`, and retained the video element, MSE
     generation and target position;
  2. require `document.visibilityState === "visible"`;
  3. in one `requestAnimationFrame` callback, remove the Party cover and schedule
     a second `requestAnimationFrame`;
  4. in that second callback, verify the same video/MSE generation remains
     paused at the held target and computed cover state no longer visually
     occludes it, then report PRESENTED using `performance.now()`.

  This double-rAF sequence proves only that the foreground browser had a
  rendering opportunity after cover removal; browsers may coalesce or skip
  paint/composition and it does not prove scanout. A native wrapper may instead
  use a stronger compositor transaction callback. If the browser lacks
  `requestVideoFrameCallback`, foreground `requestAnimationFrame`, or client
  ownership of a verifiable cover, it must not advertise
  `PARTY_PRESENTED_FRAME_V1`. If visibility changes or the second callback does
  not arrive before timeout, fail the directive rather than acknowledge.
  `sampleUncertaintyMs` includes both rAF intervals and scheduling delay, but
  does not convert this event into a bounded compositor-commit claim. Double-rAF
  observations validate logical exposure only; they cannot satisfy the external
  `measuredScreenSpreadMs` acceptance metric.
- **Native paused barrier reveal:** ARMED records the held target's media
  position, source/player/surface generation and underlying frame identity.
  REVEAL removes only an active-output-surface cover; a native
  compositor/transaction callback may prove that removal was committed before
  sending PAUSED PRESENTED. A View overlay on a different display or above only
  the Activity window is insufficient.
- **NONE correction:** the prepared paused frame may still be behind the
  PREPARE cover. At the deadline, remove that cover using the platform-specific
  evidence boundary without seeking. If it was already uncovered, verify the
  same surface, generation and target. Then send PRESENTED with a fresh
  observation.

Evidence uncertainty includes backend callback delay, clock uncertainty and the
platform's rendering opportunity/refresh delay. For PWA double-rAF evidence it
includes at least the interval between both callbacks plus callback scheduling
delay. Clients report the actual observation time rather than subtracting a
guess. Android reports the converted backend event time, not listener arrival,
and includes the measured conversion/callback uncertainty in its local
eligibility decision. The existing learned presentation model absorbs stable
finite delay and variability. If evidence cannot be correlated to the
directive and active-surface generations with bounded uncertainty, send
`success=false` or fail preparation.

ARMED, HELD and PRESENTED positions are actual frame media timestamps in the
recording timeline, never requested seek targets or extrapolated player-clock
positions. ARMED, HELD, PAUSED PRESENTED and NONE PRESENTED compare against the
fixed directive target. For a known frame duration `frameMs`, such a fixed
target observation is valid only when:

```text
abs(actualFramePositionMs - targetPositionMs)
  <= min(750 ms, max(50 ms, 2 * frameMs + timestampUncertaintyMs))
```

Unknown frame duration uses the 50 ms floor, not the 750 ms server safety
ceiling. If no qualifying frame can be held, fail the directive.

A PLAYING PRESENTED frame has advanced after REVEAL and must not be compared
with the original seek target. Normalize `frameMonoMs` into the server
monotonic domain, project the authoritative canonical timeline to that exact
instant, and compare `positionMs` with the projected position:

```text
expectedPositionMs = projectCanonical(normalizedFrameMonoMs)
abs(positionMs - expectedPositionMs)
  <= min(750 ms,
         max(67 ms,
             2 * frameMs + sampleUncertaintyMs + clockUncertaintyMs))
```

The frame, cover, timeline generation and projection must all belong to the
same accepted directive. A shared generation change makes the evidence stale
rather than changing the expected position.

When evidence cannot be produced, fail without fabricating it:

```json
{
  "type": "ACK",
  "partyId": "opaque",
  "stateVersion": 3,
  "commandSeq": 3,
  "directiveId": "opaque-per-participant",
  "stage": "PRESENTED",
  "success": false,
  "failureCode": "EVIDENCE_UNAVAILABLE"
}
```

For `success=false`, `failureCode` is required. `sampleMonoMs`, `positionMs`,
`state`, and `sampleUncertaintyMs` are optional and must be omitted when no
truthful correlated observation exists. A negative ACK immediately fails that
participant's directive; it never requires invented frame fields.

A failed directive marks only that participant late. A stale or mismatched
`directiveId` is rejected and cannot settle a newer seek.

ACK is a request and always receives either `ACK` or `ERROR`; it is not a
one-way notification. `commandSeq=0` is valid for the initial alignment
correction. The only valid stages are `EXECUTED`, `ARMED`, `HELD` and
`PRESENTED`; the
removed `RECEIVED` stage is invalid.

For a matching pending directive, an invalid `PRESENTED` fails that directive
immediately, marks the participant late and returns the precise error. It is not
left activating until timeout.

If `ACK/ARMED` is paused but its reported position still reflects the pre-seek
frame, the server returns the correlated ACK with `accepted=false` while keeping
the participant `ACTIVATING`; it does not silently acknowledge an ambiguous
stage. A later server-side position sample cannot prove that the client decoded
and held the target frame. The client must send a fresh, generation-correlated
ARMED observation after actual readiness. The accepting response carries
`accepted=true`, and only that observation may release the cohort. Wrong-state
and early ARMED reports remain errors, and the 20000 ms activation timeout
remains the no-progress ceiling.

ARMED retry identities are distinct:

- an exact retry reuses the same `requestId` and identical body and receives the
  cached response without reprocessing;
- a newer observation uses a new `requestId`, a higher `requestSeq`, the same
  directive identity, and a strictly newer sample while the stage remains
  unaccepted;
- after ARMED has been accepted, another new ARMED observation is an invalid
  duplicate stage; fresh hold proof uses HELD only after HOLD_CONFIRM.

HELD uses the same exact-request retry rule. A new HELD observation is accepted
only while its HOLD_CONFIRM is pending and before REVEAL/CANCEL. Failed or late
HELD removes that participant from a shared fixed-target cohort or leaves a
participant-specific recovery LATE.

Validation errors include:

- `STALE_STATE` — `stateVersion` is not the pending/current generation. The
  ERROR includes request type, Party ID, supplied state version/command
  sequence, and authoritative state version/command sequence;
- `STALE_DIRECTIVE` — `directiveId` or `commandSeq` does not identify the
  participant's pending directive;
- `CLOCK_UNCERTAIN` — no trustworthy monotonic conversion is available;
- `TRANSITION_PENDING` — another directive is unsettled; retain at most the
  latest user intent and send it as a new ACTION only after fresh SNAPSHOT;
- `MISSED_REVEAL_DEADLINE` — do not uncover; wait for correlated CANCEL and a
  replacement or explicit reconciliation;
- `CANCELLED_DIRECTIVE` — the directive was superseded or cancelled and no
  later REVEAL/PRESENTED may settle it;
- `POSITION_MISMATCH` — the truthful held/output frame lies outside the
  directive's allowed position tolerance. For state-only PAUSE this means more
  than 67 ms from the deterministic paused anchor; the participant becomes
  LATE and the shared anchor does not move;
- `PREPARATION_BINDING_MISMATCH` — the actual production open did not consume
  the exact pending one-shot binding, or a competing/cancelled open attempted
  to consume it; fail PREPARE without falling back to an untagged open;
- `SESSION_EXPIRED` — renew with HELLO before sending another authenticated
  request;
- `SESSION_DENIED` — the supplied token is not the transport's current token;
- `JOIN_RATE_LIMITED`, `CODE_ISSUE_RATE_LIMITED`,
  `CODE_ROTATION_COOLDOWN` — wait the returned `retryAfterMs`;
- `STALE_SAMPLE` — normalized `sampleMonoMs` is more than five seconds old,
  exceeds the larger of advertised clock uncertainty or 50 ms into the future,
  or is not newer than the participant's previous accepted sample. The narrow
  exception is an ACTIVE PAUSED heartbeat that repeats the exact admitted
  PAUSED frame's timestamp and position;
- `INVALID_PRESENTATION` — ARMED is not paused, or the final visible state is
  not `PLAYING`/`PAUSED` and equal to the directive target state;
- `EARLY_PRESENTATION` — the reported visible frame predates the scheduled
  execution window.

`ACTIVATION_EXPIRED` is reserved for a valid directive whose required terminal
stage does not arrive within 20 seconds: ARMED/PRESENTED for a barrier, or
PRESENTED for PLAY/PAUSE/NONE. It must not be used to hide a rejected
acknowledgement.

## 10. Heartbeats and correction

During activation, heartbeats may report state but cannot alter the directive
lifecycle. After `PRESENTED`, send sparse heartbeats about every two seconds
while playing and every five seconds while stably paused:

All canonical playback runs at rate 1.0. Once participants present the same
canonical position at the scheduled deadline, assume they remain synchronized
through healthy realtime playback. Ordinary PLAYING samples are liveness and
progress telemetry, not evidence that resynchronization is needed. Only an
explicit accepted participant signal such as BUFFERING, ERROR, STOPPED or a
failed directive may invalidate that assumption.

A stably paused renderer may keep reporting the original visible frame's
`sampleMonoMs` and `positionMs`. The server accepts that exact pair repeatedly
while the participant remains ACTIVE and no directive is pending. Reusing the
timestamp with a different position, state or admitted frame is stale. PLAYING,
ARMED, HELD and PRESENTED samples remain strictly fresh and increasing when
each stage is observed; only HELD freshness controls later REVEAL admission.

BUFFERING is an observation of a state transition, not a newly presented frame.
Its `sampleMonoMs` is the fresh client monotonic time when buffering was
observed; `positionMs` remains the last truthfully visible PLAYING frame and may
therefore predate that event. This exception does not authorize synthetic media
progress. The first recovered PLAYING sample must again be a fresh presented
frame observation. Ordinary PLAYING skew remains telemetry-only.

STOPPED follows the same transition-time rule. Its `sampleMonoMs` is the fresh
time when local Stop was observed, while `positionMs` is the last truthfully
visible frame and may be old after a long pause. Do not reuse the old frame's
timestamp as the Stop event timestamp.

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
position. Ordinary PLAYING heartbeats are liveness and progress telemetry only:
position skew never changes participant status or generates a correction.
An accepted BUFFERING heartbeat marks only that participant LATE and arms one
recovery comparison; every other participant continues unaffected.

`HEARTBEAT_ACK` includes:

- current `stateVersion` and `commandSeq`
- `desiredState`
- projected `desiredPositionMs`
- observed `skewMs`
- `activationPending`

Recovery policy:

| Condition | Result |
| --- | --- |
| activation pending | telemetry only; no drift decision |
| ordinary PLAYING heartbeat, regardless of skew | liveness/progress telemetry only; remain ACTIVE and send no correction |
| accepted BUFFERING | mark only that participant LATE and arm recovery comparison |
| first accepted PLAYING after BUFFERING, aligned within 67 ms at the same server-monotonic instant | return that participant directly to ACTIVE |
| first accepted PLAYING after BUFFERING, outside 67 ms or wrong canonical state | issue one participant-specific `HARD` correction |
| further PLAYING heartbeats without another BUFFERING | telemetry only; no additional correction |
| stale or out-of-generation BUFFERING/recovery sample | reject without changing recovery state |
| ERROR | mark participant late |

Each accepted BUFFERING episode can produce at most one recovery correction.
That recovery is consumed by its first accepted PLAYING sample whether aligned
or corrected. PLAY, PAUSE, JUMP, FF and REW retain their normal command and
barrier handling.

`CORRECTION` contains the same participant-specific `directiveId`,
position/state/rate/deadline fields as a command plus a reason and `kind`.
`NONE` preserves the already-rendered frame without player work. `HARD` means
reposition the player through ARMED/REVEAL. Both settle with `PRESENTED`; neither
changes the canonical timeline generation. The server does not emit speculative
rendered-frame drift corrections.

State-only `PLAY` and `PAUSE` directives preserve the renderer position already
established by initial alignment or the preceding canonical transition. The
server adapter changes only play/pause state; it must not issue a redundant seek.
JUMP, skip actions, unprepared initial alignment and HARD corrections continue
to reposition.

For each participant the server keeps two bounded nine-sample rolling models:
preparation start to normalized ARMED time, and REVEAL deadline to normalized
PRESENTED time. Three samples are required in each model before compensation is
trusted. The center is the median; uncertainty is derived from median absolute
deviation; impossible samples are discarded; total compensation is clamped to
five seconds. Barrier wait time is therefore never learned as player latency.
ACK arrival time is never used. SNAPSHOT roster entries expose confidence,
predicted total presentation latency and uncertainty for diagnostics.

### 10.1 Measurable synchronization bound

A common deadline coordinates intent; it does not erase output latency,
scheduler jitter or hardware-clock error. For every barrier, normalize each
accepted PLAYING PRESENTED event into server monotonic time. Each sample has:

```text
t[i] = normalized event time
p[i] = actual frame position
u[i] = clockUncertaintyMs + sampleUncertaintyMs
T = max(t[i])
q[i] = p[i] + (T - t[i])   # rate 1.0 projection to one common instant
```

Then compute separate timing and media-position metrics:

```text
revealTimingSpreadMs = max(t[i]) - min(t[i])
framePositionSpreadMs = max(q[i]) - min(q[i])
lower[i] = q[i] - u[i]
upper[i] = q[i] + u[i]
conservativeFrameSpreadMs = max(upper[i]) - min(lower[i])
```

The interval formula includes uncertainty from both endpoints; with two samples
at ±100 ms it adds 200 ms, not 100 ms. The rolling median/MAD presentation model
is a scheduling forecast, not a hard per-sample bound, and cannot replace
`sampleUncertaintyMs`.

These are protocol-observable logical-output metrics, not physical-screen
measurements. Physical v1.00 acceptance additionally records all participating
screens with one external timebase (for example a high-frame-rate camera or
photodiode rig showing a shared flash/frame marker) and computes
`measuredScreenSpreadMs` from the first actually visible target frame on each
device. Device clocks and compositor callbacks cannot establish that metric.

Across the representative real-client matrix, acceptance requires p95
`revealTimingSpreadMs <= 250`, p95 `framePositionSpreadMs <= 250`, p95
`conservativeFrameSpreadMs <= 500`, and p95
`measuredScreenSpreadMs <= 250`; no individual successful transition may exceed
750 ms frame-position or measured screen spread. A participant outside either
750 ms safety bound is late, not evidence that the cohort was synchronized.
Unlearned participants may operate during pre-release testing, but their
transitions do not establish the v1.00 acceptance claim until both latency
models are confident and the external screen-to-screen measurement passes.

At rate 1.0, ordinary PLAYING heartbeat skew remains telemetry only and never
causes a correction. The client must, however, monitor fresh frame-correlated
output samples. If its own output clock departs from monotonic 1:1 progression
by more than 250 ms for three consecutive two-second samples without an actual
buffering transition, it reports `HEARTBEAT state=ERROR`, becomes LATE, and
re-enters through PREPARE/READY. It must not mislabel clock drift as BUFFERING.

## 11. Local controls

Captions, audio-track selection, subtitle styling and volume remain local.

Local Stop is not a shared Stop command. Report it as:

```json
{
  "type": "HEARTBEAT",
  "state": "STOPPED",
  "sampleMonoMs": 700000,
  "positionMs": 120000,
  "bufferAheadMs": 0
}
```

`sampleMonoMs` is the fresh Stop transition observation; `positionMs` remains
the last visible frame. The participant must not manufacture media progress.
The participant must PREPARE and READY again before receiving PLAY/JUMP.

## 12. Leave, end and expiry

- Canonical Party progress is persisted through the existing
  `VideoFrame`/`BigBrother` watched and Resume policy. Accepted samples are
  ordered by `stateVersion`, `commandSeq`, then normalized sample freshness;
  media position is payload, not ordering, so a newer intentional rewind wins
  over an older higher position.
- This authoritative-cursor replacement is invoked only by an authenticated,
  capability-negotiated Party renderer. Ordinary NG playback and legacy clients
  retain the existing monotonic watched-position behavior.
- Progress is written at a bounded 30-second cadence. Stale or out-of-order
  samples cannot replace the accepted canonical sample.
- Any transport disconnect immediately writes the newest accepted canonical
  position and starts or restarts a 60-second hold. Normal writes are frozen
  during the hold. Ending the Party during that window retains the disconnect
  position; if the Party continues beyond it, cadence writes resume from newer
  canonical progress.
- `LEAVE` removes only the requesting participant and commits canonical
  watched/resume progress through existing SageTV policy when no disconnect hold
  is active.
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
14. REVEAL releases at the server-monotonic deadline; PLAYING PRESENTED is
    frame-correlated output evidence, while PAUSED PRESENTED uses the
    platform-defined cover-removal evidence boundary without another player
    callback;
15. state-only PAUSE does not seek or recreate the source, but settles with a
    fresh PRESENTED observation proving the paused state and held position;
16. token renewal serializes HELLO and atomically replaces the admitted token
    without resetting Party lifecycle state;
17. `TRANSITION_PENDING` retains at most the latest intent and resends it only
    after a fresh correlated SNAPSHOT;
18. join-code retry errors honor `retryAfterMs`;
19. growing-window snapshots replace all five window fields atomically and
    distinguish temporary end buffering from drained EOS;
20. push/local seek ownership executes exactly one reposition and REVEAL never
    seeks;
21. removing Party audio suppression restores the latest user mute/volume
    intent, including changes made while the barrier was pending;
22. PREPARE may provisionally establish delivery, but an ineligible route
    returns FAILED before READY and never exposes staged media;
23. direct-push preparation negotiates `PARTY_PUSH_EPOCH_ORIGIN_V1`, ACKs
    command 42 before receiving new-epoch bytes, and maps rendered media
    timestamps from the announced same-sample origin pair;
24. READY uses a bound tuple of preparation ID, preparation binding, opaque
    server playback generation, local open generation and surface generation;
    MEDIA_OPEN_BINDING is acknowledged before Watch opens media, MEDIA_OPENED is
    acknowledged afterward, reuse confirms the existing bound open, and all
    replacement/arrival races are fenced;
25. Android evidence uses actual frame timestamps and bounded converted backend
    event time on the active surface generation; callback arrival, PixelCopy
    failure, protected surfaces, and external-display mismatches fail closed;
26. cancelled pre-REVEAL staging remains covered/held or is stopped before
    uncovering;
27. each shared REVEAL gets fresh post-HELD delivery lead, including when the
    initial ARMED reports are many seconds apart; HELD proves the same frame and
    generations remain staged without resetting the overall activation budget;
28. logical tests separately satisfy reveal-timing, common-time frame-position
    and pairwise interval uncertainty bounds, while an external shared-timebase
    rig separately satisfies measured screen-to-screen timing; median/MAD is not
    used as a hard sample uncertainty;
29. failed ARMED/PRESENTED ACKs carry a failure code without invented frame
    fields;
30. sustained client-observed output-clock drift reports ERROR and re-enters
    preparation without turning ordinary PLAYING heartbeat skew into a server
    correction;
31. HELLO, token renewal, CREATE, JOIN and RESUME reject any client not
    negotiated at contract revision 10;
32. paused late join, rollover, resume, FF/REW, bounded input free space, and
    FLUSH-before-ACK pass on both Android player backends and representative
    phone/tablet/TV-class devices;
33. legacy servers and unnegotiated clients remain unchanged;
34. a buffering participant cannot anchor PAUSE; the canonical paused position
    is projected from the pre-command timeline, and misaligned participants
    become LATE before state-only PLAY;
35. covered PLAYING PRESENTED uses the first correlated frame at or after
    active-surface cover removal and compares it with canonical position
    projected to that frame timestamp; physical screen timing is measured
    externally;
36. ARMED responses explicitly distinguish accepted from not-yet-accepted
    observations;
37. late/cancelled REVEAL never uncovers staged media, and correlated CANCEL is
    delivered before any replacement;
38. a participant-specific playing HARD correction is rebased and reprepared
    when a delayed reveal would expose an obsolete catch-up frame; replacements
    retain one recovery attempt/deadline and never mutate or block the shared
    timeline;
39. route selection admits direct push only with its negotiated epoch/origin
    extension and otherwise selects another eligible route or fails PREPARE;
40. captions/audio/subtitle changes remain local;
41. command 41 and the PWA HTTP headers bind the actual production open
    fail-closed, including competing-open, shared-bridge cross-session, reuse
    and cancellation races;
42. final REVEAL scheduling reserves measured time for PRESENTED evidence and
    its ACK inside the activation budget;
43. shared actions fence in-flight participant recovery without waiting for it;
44. decoder capabilities and every adaptive rung use exact validated
    codec/profile/level/bit-depth/size/rate/container/audio tuples;
45. NONE removes a paused-frame cover without waiting for a PLAYING frame
    callback.
