# Party Mode v1: canonical NG server contract

Status: implemented server-side, **disabled by default**, client integration and physical
acceptance outstanding. This file, `java/sage/party/`, and
`docs/party/v1/golden.json` are the canonical contract, not the conceptual message
names in PRD v0.9. The executable transport examples are
`docs/party/v1/examples.cjs`; the synthetic PWA/Android scenarios are
`test/java/sage/party/PartyServiceTest.java`. No Party REST endpoint, public port,
bridge authority, client player implementation, deployment, or public discovery is added.

## Enablement, negotiation and self-service session authorization

Set `party/enabled=true` and restart the server process for staged integration.
Defaults: `party/max_budget_kbps=100000`, `party/max_transcodes=1`,
`party/live_edge_delay_ms=3000` (minimum 1500). These are
aggregate **Party** preparation/playback budgets, not a machine-wide CPU/encoder
governor. Configure measured headroom before enabling. At most 16 parties exist;
each has five slots, including preparing, locally stopped and grace-retained slots.
Parties expire six hours after CREATE, including all playing, paused, buffering
and recovery time. Each unresolved member join/preparation attempt expires after
15 seconds; successful PREPARATION_RESULT completion cancels that member deadline
and does not affect the Party or existing ready members.

In the existing `SAGETV_NG_CAPABILITIES` reply, advertise **all** these exact tokens:

```
PARTY_V1,PARTY_JOIN_CODE_V1,PARTY_MONOTONIC_V1,PARTY_POSITION_V1,PARTY_SCHEDULED_V1,PARTY_SEEK_V1,PARTY_LOCAL_STOP_V1
```

Optional: `PARTY_RATE_ADJUST_V1`, `PARTY_START_POSITION_V1`, `PARTY_PREPARE_V1`,
`PARTY_SURFACE_V1` (requires `PARTY_PREPARE_V1`), `PARTY_GROWING_MEDIA_V1`.
`PARTY_START_POSITION_V1` enables server-sampled Current Position creation;
`PARTY_PREPARE_V1` enables joined-media preparation with paused asynchronous Watch.
`PARTY_GROWING_MEDIA_V1` attests that the adapter accepts dynamic media-window
fields, never treats the current available end as final while `mediaGrowing:true`,
and can prepare/report/seek across ordinary Sage recording segment rollover.
`PARTY_JOIN_CODE_V1` is mandatory: clients predating mandatory code entry are
ineligible, even if they implement the earlier six Party guarantees.
Existing NG version and surface negotiation
remain required for normal NG playback. These new tokens attest respectively to
schema v1, mandatory three-digit guest confirmation, a monotonic clock, observed position reports, scheduled play/pause,
seek-with-landing reports, and **local Stop interception plus explicit rejoin**.
Do not advertise until the adapter implements every guarantee. No property is
silently inferred from an NG boolean, client type, IP, VPN label or client ID.

**Explicit user-directed adjustment to PRD v0.9:** LAN users share playback without
login, pairing or admin intervention. Authority is bound to the **existing
server-admitted MiniClient session**, not independent human authentication.
The server records the actual successful admission branch where its existing
`miniclient/authenticate_local_connections=false` policy accepts that connection.
It does not accept a client-asserted LAN flag, NG ID, subnet or VPN label as proof.
No global authentication default is changed.

Sessions admitted under the existing LAN trust policy receive a short-lived,
random, exact-renderer-bound session token after full NG guarantee negotiation.
This token limits authorization to that session; **minting it does not independently
authenticate a person or strengthen the server's existing LAN trust boundary**.
Server-issued three-digit codes provide self-service guest confirmation on other
already-admitted NG sessions, with no administrator approval.

Where existing server admission requires login, the same full NG capability set
and a **verified Placeshifter principal bound to the exact renderer/session** are
required instead. `SecureLogin.xml` makes an additive
`VerifyPartyPlaceshifterLogin(Username, EncryptedPassword)` server Java call before
disabling encryption. It independently verifies enabled existing user records,
STV and the existing signed-BigInteger MD5 digest representation; it does not change
the legacy login decision or introduce a password store. Cached login is eligible
only after the existing cached secret hash passes and an exact, unique enabled
user-to-client cached mapping is found. Partial substring or ambiguous mappings
cannot authorize Party Mode.

Default no-login LAN connections are eligible after normal server admission and
full Party negotiation. If the operator enables existing local authentication,
that existing login requirement still applies; Party Mode never bypasses it.
A custom secure-login STV must call the additive verification hook after
successful credential validation. Encryption being off is never evidence of login.
The existing Sage security profile and parental controls remain authoritative.

The server sends `AVAILABLE` only after authorized admission and complete negotiation.
`HELLO` retrieves/refreshes it. During the final MiniClient property negotiation, the
server may retain one syntactically valid `HELLO` on that same renderer and process it
only after normal admission and all required capabilities complete. This grants no
token, participant, media or other authority before admission; an exact retry is
coalesced and a different additional early `HELLO` is rejected. Legacy,
unnegotiated and all other not-yet-admitted requests are rejected server-side with
`NOT_AVAILABLE`, including CREATE/JOIN/ACTION, not just hidden in UI.
No Party offer is added to the legacy `SAGETV_NG_SERVER_CAPS` value.

## Transport and envelope

Use the existing server-admitted MiniClient UI connection. Property name **`PARTY_V1`**
is case-sensitive, type/opcode **1** (SET_PROPERTY), in both directions. The PWA
bridge relays bytes; it does not create participants, tokens, clocks or commands.

Property payload, big endian:

```
u16 nameByteLength | u16 valueByteLength | ASCII "PARTY_V1" | UTF-8 JSON
```

Client-to-server uses the existing **16-byte event/reply header**:
`u8 type=1, u24 payloadLength, u32 timestamp, u32 id, u32 padding`.
Those transport timestamp/id fields have no Party identity or ordering meaning.
Server-to-client uses the existing **4-byte command header**:
`u8 type=1, u24 payloadLength`, then the same property payload.
Clients must return the normal MiniClient integer SetProperty acknowledgment,
even for Party notifications. That acknowledgment is not a Party execution ACK.
Do not put a 16-byte client header on server commands.

Every JSON value is an object with `schemaVersion:1` and string `type`.
Maximum JSON length is **8192 UTF-8 bytes**; request nesting depth is at most 4
(nested JOINED snapshots, including outcome diagnostics, can reach depth 5). Property length must
match exactly. Identifiers/enums match `[A-Za-z0-9_.:-]{1,128}` (the final hyphen is
literal). Numeric input is strict JSON numeric, finite safe integral milliseconds
unless stated otherwise; no numeric-string or boolean-string coercion.
Unknown extra fields are ignored in v1; unknown types fail explicitly.

All requests require `requestId` and positive `requestSeq`. All except HELLO also
require `serverEpoch` and `sessionToken` from AVAILABLE. Membership requests require `partyId`, except
HELLO, LIST_CLIENTS, CREATE and manual code-only JOIN. No request accepts an asserted principal/participant
identity as authority. `participantId` in RESUME is only a lookup key, additionally
requiring the same server-authorized account/LAN realm and the private recovery token.

Successful replies echo `requestId` and add `serverMonoMs`. Events omit requestId.
ERROR echoes it when a valid envelope was parsed. Commands reach the origin as
an event **and** as the action reply; deduplicate by commandSeq, not requestId.

## Identity, invites, tokens and ordering

`serverEpoch`, party IDs, participant IDs, session handles and tokens are
server-generated 192-bit random URL-safe strings. Physical socket IDs and
`SAGETV_NG_CLIENT_ID` are not participant identities. LIST_CLIENTS returns only
currently server-authorized, compatible renderer handles; they are invitation addresses,
not stable identities. No account names or file paths are exposed.

**User-directed invitation revision:** CREATE returns `joinCode`, a string of
exactly three ASCII digits, including leading zeros (`"000"` through `"999"`).
It is a confirmation challenge, **not standalone authentication**. The creator
is already a member; every guest, including a selected connected invitee, must
enter the code on its existing admitted, fully negotiated control session.
There are no URLs, deep links, new-session joins or usable `joinToken` routes.
The obsolete `ROTATE_TOKEN` operation is not implemented.

Codes are uniformly cryptographically sampled from currently available values,
unique among all live codes on this server. They expire after **120000 ms**,
are reusable by several guests up to the five-slot cap, and are never included
in INVITED, JOINED, rosters or snapshots. INVITED contains `partyId`,
`mediaFileId`, `joinCodeRequired:true` only; it is a notification, not admission.
Host-only JOIN_CODE retrieves the current code; ROTATE_CODE replaces it
immediately, with a **10000 ms** per-party rotation cooldown. Expired codes are
not automatically renewed. Removed/rotated codes remain unavailable for another
120000 ms; expired codes are quarantined until 120000 ms after their expiry.
END, last-member removal and restart revoke them. A new host may retrieve/rotate
the code; cached old replies never make old codes valid again.

For either PWA or future Android sidecars, show the code/countdown only to the
current host using CREATED's private reply or **JOIN_CODE on sidecar open/reopen**.
JOIN_CODE neither rotates the code nor changes the timeline/versions; it also
works after successful host RESUME. Retain the private reply only in host session
memory and replace its displayed expiry with the latest retrieval. An expired
code is shown as expired and requires explicit ROTATE_CODE, never an automatic
side effect of opening the UI. Guest sidecars show code entry, including when
INVITED; opening them cannot retrieve the host's code. No code is recoverable
through unauthenticated routes, a global playback context or roster snapshots.

JOIN with `joinCode` looks up the party in the server-local live-code namespace.
If an invited `partyId` is supplied it must match; optional `mediaFileId` must
also match. All such validation attempts, successful or not, are bounded by
**three per renderer per rolling 60000 ms and six globally per rolling 60000 ms**.
Global history survives renderer disconnect/replacement. A rate rejection returns
JOIN_RATE_LIMITED and numeric `retryAfterMs`; do not retry before that interval,
then use a **new requestId/sequence**. Exact cached retries neither validate again
nor consume another attempt. Codes never bypass normal session tokens, eligibility,
media/permissions or per-viewer admission. Code issuance (CREATE/ROTATE_CODE) is
also globally bounded to **12 per rolling 60000 ms** against namespace churn;
CODE_ISSUE_RATE_LIMITED returns `retryAfterMs`. Retrieval does not issue a code.

**Trust/abuse limit:** 1000 possibilities are inherently guessable by already
admitted peers. The global limiter bounds reconnect/Sybil guessing, not human
identity; more simultaneous live parties increase chance of guessing some party.
Quarantined codes reduce the available space. Malicious admitted peers can also
consume the global allowance and temporarily deny legitimate joins/creation.
These are explicit limits of the requested no-admin three-digit confirmation,
not strong invite secrecy. Keep normal server admission restricted to trusted
users; stronger resistance would require an additional trust/confirmation decision,
not silently more digits or admin approval. Never log codes/tokens, put them in
URLs/referrers/analytics, or expose them through device discovery. There is no
public party enumeration.

AVAILABLE includes `sessionToken` valid for **1800000 ms** and
`sessionTokenExpiresAtServerMonoMs`. Refresh with HELLO before expiry (recommended
every five minutes); HELLO rotates the token when less than 60000 ms remain.
Replace the old token immediately and serialize refresh with outgoing requests.
Old tokens are rejected after rotation, including cached non-HELLO request replay.
Tokens cannot authorize another renderer,
even on the same LAN/account. Do not retry an old cached HELLO to refresh; use a new
requestId/sequence. Every non-HELLO request must carry the current sessionToken.

JOINED/CREATED privately return a recoveryToken bound to that participant and
server-authorized account/LAN realm, with `recoveryTokenExpiresAtServerMonoMs`.
It expires after **1800000 ms**, rotates on successful JOINED/REJOIN/RESUME,
and is extended by a fresh HELLO while joined; that HELLO includes the private
recovery token and its updated expiry. Retain in session memory, not shared invites.
LAN realm alone cannot reclaim a slot: the private participant recovery token is
always required. RESUME accepts it
only for an offline slot during **15000 ms** grace. Live slots cannot be stolen,
even with the same account. Reconnect invalidates clock calibration and preparation.
Renderer disconnect/replacement invalidates its admission authorization and session
token; establish a fresh fully admitted MiniClient session, HELLO, then RESUME using
the retained participant recovery token. LAN re-admission needs no login when normal
server policy permits it; an authenticated account must reauthenticate normally.
The old MiniClient transparent socket reconnect alone does not reauthorize Party Mode.

Requests are limited to **30 per renderer per second**. Use increasing requestSeq
over the renderer lifetime, including clock/report requests. Retried requests
reuse the exact original serialized envelope/requestId/sequence. Last 128 results
(including errors) are cached; matching retries return the original result.
Changed reuse yields REQUEST_ID_REUSED. Older sequences outside the cache yield
STALE_REQUEST, never re-execute an evicted action. Out-of-order new requests are
rejected rather than reordered. A new admitted renderer starts a new sequence
domain. The service bounds input/output queues to 64 per renderer and disconnects
Party participation on overflow/I/O failure; it never broadcasts another viewer's
media bytes or makes one slow output block other viewers.

`stateVersion` and `commandSeq` start at 0, increase together once per canonical
timeline transition, and are scoped to a party/epoch. Roster/policy/clock/Stop
changes do **not** increment them. ACTION, READY, POSITION and CORRECTION_RESULT
require the exact current stateVersion. Stale input gets STALE_STATE; request a
SNAPSHOT with a new request ID/sequence, cancel superseded deadlines/corrections,
and re-enter readiness if needed. SNAPSHOT replaces missing history, not a request
to replay commands 1..commandSeq. Apply a command sequence at most once.
ACK instead references the version/sequence of its retained command, not the
latest timeline: a delayed execution report remains valid after a newer action.

One timeline transition may be pending. Additional actions get
TRANSITION_PENDING until its execution boundary; held FF/REW should queue/coalesce
locally and submit the next ordered action only afterward. No smooth/reverse rate
scan is advertised in v1: held/smooth inputs use repeated configured skips.

## Exact request/reply schemas

Below, fields are **in addition to** the common request envelope. All listed
fields are required unless marked optional. `V` means exact current stateVersion;
positions are relative to the beginning of the common media, not epoch PTS.

| Request type | Fields | Successful reply |
|---|---|---|
| HELLO | no party/epoch needed | AVAILABLE |
| LIST_CLIENTS | no party needed | CLIENTS |
| CREATE | optional `mediaFileId` positive int32; optional `startMode:"BEGINNING"\|"CURRENT_POSITION"` | CREATED |
| JOIN | `joinCode:string`; optional `partyId, mediaFileId` | JOINED |
| RESUME | `partyId, participantId, recoveryToken` | JOINED |
| SNAPSHOT | `partyId` | SNAPSHOT |
| INVITE | `partyId, sessionHandle` | ACK; target receives INVITED |
| JOIN_CODE | `partyId` | JOIN_CODE; host only |
| ROTATE_CODE | `partyId` | JOIN_CODE; host only |
| SELECT_SURFACE | `partyId, surfaceId:string`; requires PARTY_SURFACE_V1 and PARTY_PREPARE_V1 | SURFACE_SELECTION_ACCEPTED; later SURFACE_SELECTION_RESULT |
| RESTORE_SURFACE | `partyId`; requires PARTY_SURFACE_V1 | SURFACE_RESTORED |
| PREPARE | `partyId, intent:"JOIN_AND_PREPARE", confirmReplace:true`; requires optional PARTY_PREPARE_V1; `selectionId` required iff a Party surface is selected | PREPARE_ACCEPTED; later PREPARATION_RESULT |
| POLICY | `partyId, hostOnly:boolean, pauseOnBuffer:boolean` | ACK; roster receives SNAPSHOT |
| CLOCK | `partyId` | CLOCK_PROBE |
| CLOCK_REPLY | `partyId, probeId, c1, c2` | CLOCK_MODEL |
| MEDIA_ADMISSION | `partyId`; only preparing/recovering/buffering members, after normal Watch loads the same media | MEDIA_ADMISSION_HANDLE |
| READY | `partyId, stateVersion:V, prepared:boolean, activated:boolean, bufferAheadMs:0..3600000, precisionMs:0..1000, rateAdjust:boolean` | CORRECTION with delivery |
| ACTION | `partyId, stateVersion:V, action` | COMMAND |
| ACK | `partyId, stateVersion, commandSeq, stage:"RECEIVED"\|"EXECUTED"`; EXECUTED also requires `success:boolean, sampleMonoMs, positionMs, state` | ACK with commandSeq/stage; roster gets COMMAND_OUTCOME |
| POSITION | `partyId, stateVersion:V, sampleMonoMs, positionMs, bufferAheadMs:0..3600000, state` | ACK with skewMs/uncertaintyMs; optional targeted CORRECTION event |
| STATUS | `partyId, status` | ACK; roster gets PARTICIPANT_STATE |
| CORRECTION_RESULT | `partyId, stateVersion:V, correctionId, success:boolean, sampleMonoMs, positionMs` | ACK; roster gets PARTICIPANT_STATE |
| REJOIN | `partyId` | JOINED; fresh prepare/clock barrier |
| LEAVE | `partyId` | LEFT; others get PARTICIPANT_STATE with status LEFT |
| END | `partyId` | ENDED; roster gets ENDED |

### Beginning/Current Position and explicit Join & Prepare

CREATE may omit `mediaFileId` when starting from the renderer's current ordinary
playback. The server then derives the exact loaded MediaFile from that admitted
renderer and requires its player to remain PLAYING or PAUSED on the same playback
generation. This is the native push path: clients must not require the optional
same-JVM `NATIVE_PLAYBACK_OWNERSHIP_V1` context or infer an ID from a path. An
explicit `mediaFileId` remains supported for existing clients and server-authorized
selection flows.

CREATE omitting `startMode` preserves **BEGINNING**, anchor 0. CURRENT_POSITION
requires negotiated `PARTY_START_POSITION_V1` and AVAILABLE containing that mode
in `supportedStartModes`. The server samples only the invoking renderer's actual
loaded MiniPlayer and requested MediaFile ID: it must have a positive currently
available duration and be PLAYING **or PAUSED**. Exact player, MediaFile and
playback generation must still match after the read; otherwise CREATE fails
CURRENT_POSITION_UNAVAILABLE with no party/membership side effects. File-epoch
media time is converted to relative milliseconds with checked subtraction and
clamped to `[0,playableEndMs]`. Client `positionMs` or `startPositionMs` is rejected,
not treated as a seek. The result always starts **PAUSED**, version/sequence 0,
with authoritative `startMode`/`startPositionMs` and matching snapshot anchor.
An exact cached CREATE retry cannot resample a later position.

Future PWA UI may pause ordinary playback when opening Start Party **before**
CREATE; that paused same-generation position is supported. Server CREATE itself
does not fabricate a normal Watch or resume point. Beginning and Current Position
affect only initial creation; late join corrects to the party's current timeline.
No general Watch-by-ID, arbitrary initial position or Party SEEK is exposed.

`prepareVia:"PARTY_PREPARE"` and AVAILABLE `prepareSupported:true` occur only for
negotiated `PARTY_PREPARE_V1`; otherwise `prepareVia:"NORMAL_NG_WATCH"` means the
client must use its existing renderer-scoped normal Watch path. The current PWA
has no such outbound Watch-by-ID API. Global "last-active" playback context and
read-only context lookup are **not** per-viewer Watch authority.

JOIN and INVITED never replace playback. After code-validated JOIN, explicit user
**Join & Prepare**, including confirmation before replacing any existing playback,
permits PREPARE `{partyId,intent:"JOIN_AND_PREPARE",confirmReplace:true}`. The
operation takes no media ID or position: the server resolves only the joined
party's canonical media on the exact authorized renderer. Only preparing,
recovering or buffering members may use it; locally stopped members need REJOIN
first. Repeated identical requests are idempotent; another request while pending
gets PREPARATION_PENDING.

The adapter must install a local **hold/autoplay-suppression before sending
PREPARE**, not wait for the accepted reply: ordinary NG media setup and
PREPARATION_RESULT can race the reply. Match events by `preparationId` and
canonical media, and ignore stale/cancelled IDs. PREPARE_ACCEPTED is not a
successful media load or permission grant. The server runs the ordinary
UIManager/VideoFrame Watch path asynchronously, retaining its parental prompt,
Seeker permission/resource checks, file verification and media-player selection.
It tags WATCH/LOAD jobs with revocable operation authority, forces full switching
for a new media load (no auto-playing fast switch), and pauses the resulting
player. Already-loaded same media uses normal Watch's redundant-file shortcut,
pauses it and retains its exact generation without forced reload, **unless a
Party surface override is selected**: that requires a full paused reload even
for identical media, so the new route actually takes effect.

PREPARATION_RESULT `LOADED,watchCode:0,playbackGeneration` is emitted only after
the requested media is loaded and paused on that generation. `FAILED` has
`code:"PREPARATION_FAILED"` plus the ordinary integer Watch code: notably
`-2` missing files, `-3` parental rejection, `-7` Seeker error, `-8` cancelled,
`-13` asynchronous load failure/timeout, `-19` unsupported player,
`-21` recording resource pressure, `-22` permission denied or
`-23` external-client resource denial. The authoritative full Watch-code constants
are in `VideoFrame.java`; no error is converted into successful preparation.
Ordinary protected-media metadata/Party eligibility is not parental approval:
the actual Watch still performs the normal check before loading.

There is a 15000 ms unresolved join/preparation lease (including the parental
dialog); expiration removes only that member with PREPARATION_EXPIRED. The
ordinary Watch load completion directly resolves PREPARATION_RESULT and cancels
the deadline; waiting for READY afterward does not retain the preparation timer.
Stop, leave/end,
disconnect/replacement, rejoin or normal Watch revoke outstanding jobs/results.
Already-running native media I/O may finish, but remains paused and cannot
produce a valid stale completion or Party admission for a removed member.
An already-opened staged player stays paused; cancellation is not a synthetic
Stop, rollback to the replaced video or authority to resume independent playback.
After leave/end, ordinary controls are released; a normal Watch clears the stage.

`LOADED` is **not READY or producer admission**. The future client associates
subsequent context/media setup with its exact renderer/generation, obtains
MEDIA_ADMISSION after the load, and relays its handle through its own bridge
producer as documented below. READY and MEDIA_ADMISSION reject while preparation
is pending. After actual admission, buffer/activation observations and fresh
clock calibration, READY returns the canonical correction. Keep staged playback
nonplaying until that correction/deadline; do not execute intervening shared
commands as an unready participant. Initial correction uses the selected
Beginning/Current anchor, not inherited normal resume; late join uses the current
party timeline and may then play. Preparation alone changes no shared
anchor/version/sequence and never loads or alters another viewer's media path.

### Same-session Party surface selection and restoration

`PARTY_SURFACE_V1` adds only a per-renderer Party override, not a generic
client-driven codec switch. AVAILABLE `surfaceSelectionSupported:true` requires
both that token and `PARTY_PREPARE_V1`. After CREATE/code-validated JOIN, explicit
Join & Prepare confirmation, and pausing existing playback, send SELECT_SURFACE.
The member must be PREPARING, RECOVERING or BUFFERING; its renderer must be idle
or paused, with no pending ordinary Watch. Neither SELECT nor receipt of INVITED
opens media. Install the local playback hold before PREPARE as above.

`surfaceId` matches `[A-Za-z0-9_]{1,64}`. Server queries the **same renderer**
using normal GET_PROPERTY, first `PLAYBACK_SURFACES`, then these existing fields
under `PLAYBACK_SURFACE_<surfaceId>_`:

```
ROUTE, PRIORITY, DELIVERY_MODES, VIDEO_CODECS, AUDIO_CODECS, CONTAINERS,
AUDIO_TRACK_ACCESS, AUDIO_TRACK_SELECTION_MODE, AUDIO_CONTAINER_RULES,
MAX_OUTPUT_WIDTH, MAX_OUTPUT_HEIGHT, MAX_FPS, AUDIO_MAX_CHANNELS, BANDWIDTH_FEEDBACK
```

PLAYBACK_SURFACES is limited to 32 entries; each reply is at most 4096 characters.
Empty optional values are allowed; missing replies, unsupported IDs, an empty
canonical route/codec/container/delivery set or an invalid ID fail explicitly.
The query has a total 10-second reply deadline. An unanswered query closes the
unresponsive control transport using existing receiver teardown: continuing with
misaligned FIFO property replies would be unsafe. Ordinary queries/timeouts are
unchanged. The client must respond with genuinely probed support (including
platform/codec constraints already negotiated under NG policy), not arbitrary
codecs copied from this example. There is no client-supplied codec JSON in SELECT.

The asynchronous result matches `selectionId`, not event arrival order. Server
rechecks membership, permissions/policy budget, exact player/media/Watch
generation and renderer path generation before installing the queried set.
Installation retains the ordinary cached set unchanged as the prior-config
snapshot; only this renderer sees the single selected surface. Normal handshake
refresh cannot broaden that override. Query, selection and restore do not change
the Party anchor/stateVersion/commandSeq or another viewer's path.

For a truthful `pwa_party_mse` surface reporting `ROUTE=mse`, `CONTAINERS=MP4`,
`DELIVERY_MODES=pull-xcode` and genuinely supported codecs, **selected Party
evaluation** remuxes otherwise direct-compatible media with `browserhd_remux`.
It does not drop the surface as an unservable raw DIRECT_PLAY. Existing codec,
bandwidth, interlace and resource policy still selects `browserhd_copyv` for
audio conversion or `browserhd` for full conversion where needed. These are
existing server fragmented-MP4 XCODE_SETUP modes, not raw progressive MP4 or
MPEG-TS. Ordinary surfaces/evaluations are unchanged. A mixed `pull,pull-xcode`
surface does not promise conditioned output; use an honestly queried distinct
MP4-only/MSE/pull-xcode-only surface when the adapter requires it. This is not a
permission to advertise unsupported codecs or feed native TS to MSE.

SELECT's `preparation:Delivery` is a plan. Echo its `selectionId` in PREPARE;
PREPARE_ACCEPTED and PREPARATION_RESULT carry that selection and path generation.
LOADED additionally carries actual loaded `delivery`, including `route`,
canonical `container` and `xcodeMode` when known. For the selected conditioned
MSE path, missing/non-MP4/browserhd-family output fails preparation rather than
silently switching to raw/TS. Match preparationId, selectionId, path generation
and loaded playbackGeneration, and confirm actual NG stream metadata before
starting the adapter. A selected path cannot issue MEDIA_ADMISSION or READY
until its selected paused preparation succeeds. Output admission is still separate.

RESTORE_SURFACE explicitly cancels pending selection/preparation, invalidates the
local readiness barrier and old admission, restores the untouched ordinary set,
and returns the current pathGeneration. Restore also happens on local Stop,
REJOIN/RESUME, leave/end, disconnect/replacement and normal Watch. The renderer's
monotonic pathGeneration increments on installing/removing an override (no-op
restore does not increment); it is ephemeral and scoped to that renderer, not a
human/device or Party identity. Re-query/re-select/re-prepare after restoration
when another Party route is needed. A stale query/result cannot reinstall a
cancelled override. RESTORE is configuration restoration, **not** reopening the
old video, resuming it or transcoding a currently loaded stream in place. A
staged player stays paused; normal Watch selects from ordinary defaults again.

ACTION enum: `PLAY, PAUSE, FF, REW, FF2, REW2`. STOP, arbitrary SEEK and raw
keycodes are invalid. FF/REW/secondary skip values are read once from the initiating
UI's existing `videoframe/ff_time`, `rew_time`, `ff_time2`, `rew_time2` preferences
(defaults +10000/-10000/+150000/-150000 ms). The server resolves from the predicted
party position at execution, clamps to `[0,playableEndMs]`, and preserves paused state.
The existing server VideoFrame play/pause/toggle/skip/smooth-scan paths are
intercepted for members; native arbitrary timeJump is explicitly rejected.
Adapters apply the resolved result locally, never rebroadcast keys or calculate skips.

On `mode:"push"`, the server also schedules the existing viewer-specific VideoFrame
seek/play/pause at the canonical deadline. The push adapter follows that normal
MiniPlayer media-control path and observes/reports actual landing; it must not send
a second Sage seek/action back to the server. On pull/pull-xcode/HLS, the adapter
executes the player transition locally. Superseding commands, local Stop,
leave/end and disconnect cancel pending server push transitions. A scheduled push
transition is bound to the loaded MediaFile ID and cannot seek a newly opened item.
Optional local soft rate adjustment is only advertised on paths that actually
support it; there is no server-side shared reverse-rate implementation.

POSITION state enum: `PLAYING, PAUSED, BUFFERING, SEEKING`. Send immediate STATUS
BUFFERING when entering underrun, and READY after buffer restoration for a hard
catch-up barrier. Do not rely on a POSITION label alone to change buffering policy.
STATUS input enum: `LOCALLY_STOPPED, BUFFERING, RECOVERING`; RECOVERING invalidates
clock confidence (use for background/network change). Reports use normalized
state in place of separate seeking/buffering boolean flags; playback trim rate is
commanded by CORRECTION, not accepted as a timeline override in position input.

Host-only requests: INVITE, JOIN_CODE, ROTATE_CODE, POLICY, END. ACTION is allowed to all
healthy participants by default; hostOnly restricts it. Stop/leave/rejoin never
require controller authority. Host loss transfers to the earliest still-connected
join-order member immediately under everyone-control. Host-only locks for the
disconnected host's grace period, then transfers deterministically.

## Outbound schemas

All fields below are required except those explicitly optional. Standard reply
metadata above applies to replies; identical event payloads lack requestId.

| Type | Additional fields |
|---|---|
| AVAILABLE | `serverEpoch, sessionHandle, sessionToken, sessionTokenExpiresAtServerMonoMs, maxParticipants:5, supportedStartModes:string[], prepareSupported:boolean, surfaceSelectionSupported:boolean, growingMediaSupported:boolean, joinCodeDigits:3, joinCodeLifetimeMs:120000, joinAttemptWindowMs:60000, joinAttemptsPerSession:3, joinAttemptsGlobal:6`; while joined additionally `recoveryToken, recoveryTokenExpiresAtServerMonoMs` |
| CLIENTS | `sessionHandles:string[]` |
| CREATED | JOINED fields plus `joinCode, expiresAtServerMonoMs, startMode, startPositionMs` |
| JOINED | `partyId, participantId, recoveryToken, recoveryTokenExpiresAtServerMonoMs, reconnectGraceMs:15000, prepareVia:"PARTY_PREPARE"\|"NORMAL_NG_WATCH", snapshot`, optional `preparation:Delivery` |
| INVITED | `partyId, mediaFileId, joinCodeRequired:true` |
| JOIN_CODE | `partyId, joinCode:string, expiresAtServerMonoMs` |
| SURFACE_SELECTION_ACCEPTED | `partyId, selectionId` |
| SURFACE_SELECTION_RESULT | `partyId, selectionId, status:"SELECTED"\|"FAILED"`; SELECTED has `pathGeneration, requiresPrepare:true, preparation:Delivery`; FAILED has `code` |
| SURFACE_RESTORED | `partyId, pathGeneration` |
| PREPARE_ACCEPTED | `partyId, mediaFileId, preparationId, holdPlayback:true`; selected path additionally `selectionId, pathGeneration` |
| PREPARATION_RESULT | `partyId, mediaFileId, preparationId, status:"LOADED"\|"FAILED", watchCode:integer`; LOADED has `playbackGeneration`; FAILED has `code:"PREPARATION_FAILED"`; selected path additionally `selectionId, pathGeneration`, and LOADED `delivery:Delivery` |
| CLOCK_PROBE | `probeId, s0` |
| CLOCK_MODEL | `serverMinusClientMs:number, uncertaintyMs:number, p95RttMs, confident:boolean` |
| MEDIA_ADMISSION_HANDLE | `handle:string, contextId, serverEpoch, sessionHandle, partyId, requestSeq, playbackGeneration, pathGeneration, expiresAtServerMonoMs, expiresInMs:60000, mediaFileId, mode:"pull"\|"pull-xcode"\|"hls"`; credential only to requesting renderer, never roster |
| MEDIA_ADMISSION_REVOKED | `contextId, reason`; targeted owning renderer only, no handle |
| COMMAND | `partyId, commandSeq, stateVersion, cause:ACTION, targetPositionMs, targetState, targetRate:1, executeAtServerMonoMs, originParticipantId` |
| CORRECTION | `partyId, participantId, stateVersion, commandSeq, correctionId, kind:"SOFT"\|"HARD", targetPositionMs, targetState, targetRate:number, durationMs, executeAtServerMonoMs`; READY reply additionally has `delivery:Delivery` |
| PARTICIPANT_STATE | `partyId, participantId, status` |
| BUFFER_RECOMMENDATION | `partyId, stateVersion, commandSeq, policy:"KEEP_MOVING", recommendation:"PAUSE_OR_REDUCE_QUALITY", bufferingCount, activeCount, hostParticipantId:string|null, serverMonoMs` |
| COMMAND_OUTCOME | schema below |
| SNAPSHOT | schema below |
| ACK | no fields by default; command ACK reply adds `commandSeq, stage`; POSITION reply adds `skewMs, uncertaintyMs` |
| LEFT | no fields |
| ENDED | END reply has no additional fields; event has `partyId, reason:"HOST_ENDED"\|"EXPIRED"\|"SERVER_SHUTDOWN"` |
| ERROR | `code`, optional requestId; JOIN_RATE_LIMITED, CODE_ROTATION_COOLDOWN and CODE_ISSUE_RATE_LIMITED additionally have `retryAfterMs` |

Delivery: `{surface:string, mode:string, videoCodec:string|null,
audioCodec:string|null, budgetKbps:positive integer, transcode:boolean,
route?:string, xcodeMode?:string, container?:string}`.
Mode is the existing selected NG delivery (`push`, `pull`, `pull-xcode`, `hls`,
etc.), **not** a new Party transport. Codec fields are the playback core's target
selection, not a guarantee of decoder-observed output when COPY/unspecified.
Do not infer codecs from Party fields; existing STREAMINFO/NG stream metadata
remains authoritative. Preparation is provisional, READY's delivery is revalidated
against the actual loaded MediaFile, selected surface and confirmed downstream
producer. `budgetKbps` is **only a Party admission estimate**, not a machine-wide
resource reservation or evidence that the actual encoder/file path started.
`route` is the queried surface route; `container` uses canonical NG spelling
(`MP4`, not internal `Quicktime`). `xcodeMode` is the precise existing server
XCODE_SETUP mode, e.g. `browserhd_remux`; omitted when none/unknown. SELECT
describes the candidate; selected PREPARATION_RESULT and READY describe the
loaded path. None replaces stream metadata or proof of actual media output.

SNAPSHOT:

```
{
  schemaVersion:1, type:"SNAPSHOT",
  partyId, serverEpoch, mediaFileId, durationMs,
  mediaGrowing:boolean, availableEndMs, playableEndMs, liveEdgeDelayMs,
  stateVersion, commandSeq, anchorPositionMs, anchorServerMonoMs,
  state:"PLAYING"|"PAUSED", rate:1, serverMonoMs,
  hostParticipantId:string|null, hostOnly:boolean, pauseOnBuffer:boolean,
  pendingCommand?:COMMAND,
  latestCommandOutcome?:COMMAND_OUTCOME,
  roster:[{
    participantId, status, clockConfident:boolean, p95RttMs,
    uncertaintyMs:number, ackCommandSeq, delivery?:Delivery
  }]
}
```

Roster statuses: `PREPARING, READY, PLAYING, PAUSED, BUFFERING, RECOVERING,
LOCALLY_STOPPED, DISCONNECTED, DEGRADED`; LEFT is a removal event, not retained.
No display name/client kind or network-address label is synthesized. Client UI can
label its own known devices; classify link quality from clock/buffer measurements.

For completed media, `durationMs`, `availableEndMs` and `playableEndMs` are equal
and `liveEdgeDelayMs` is zero. For an in-progress recording, `durationMs` remains
the backward-compatible current available extent, `availableEndMs` increases as
the recording grows, and `playableEndMs=availableEndMs-liveEdgeDelayMs` (floored
at zero). Clients must clamp Party targets to `playableEndMs`, not interpret the
current `durationMs` as EOS. POSITION ACKs repeat these four media-window fields.
When recording completes, the next snapshot/ACK sets `mediaGrowing:false`,
`liveEdgeDelayMs:0`, and the final fixed end becomes fully playable. This media
window update does not increment timeline version/sequence.

The current anchor remains authoritative **before** pendingCommand's deadline.
At/after it, use the pending target as a new anchor and its targetState.
Predicted media position is anchorPosition plus elapsed monotonic time only while
PLAYING, clamped to duration. A paused snapshot is never extrapolated. A snapshot
received after a pending deadline must project from that deadline, not its receipt.
A late join gets a targeted catch-up CORRECTION; existing participants are not
reanchored or rewound.

COMMAND_OUTCOME:

```
{
  schemaVersion:1, type:"COMMAND_OUTCOME", partyId, stateVersion, commandSeq,
  executeAtServerMonoMs, reportDeadlineServerMonoMs, serverMonoMs,
  outcome:"PENDING"|"COMPLETE"|"TIMED_OUT"|"SUPERSEDED"|"EVICTED",
  expectedCount, receivedCount, executedCount, successCount, failedCount, missingCount,
  executionSpreadMs?:integer, spreadUncertaintyMs?:number,
  participants:[{
    participantId, receivedAtServerMonoMs?:integer,
    executedAtServerMonoMs?:integer, positionMs?:integer,
    state?:"PLAYING"|"PAUSED"|"BUFFERING"|"SEEKING",
    success?:boolean, skewMs?:integer, uncertaintyMs?:number
  }]
}
```

The recipient set is frozen at dispatch, at most five; late joiners cannot ACK old
commands. RECEIVED records server receipt time (not claimed network latency).
EXECUTED's `sampleMonoMs` is the client's **actual execution/landing observation
time**, translated using its confident clock. It implicitly records receipt if
necessary. Sample age/future checks match POSITION; before-deadline reports fail.
The server evaluates success as client success **and** target state match **and**
absolute landing skew within that member's deadband. For a playing target, skew
is measured against the command's own projected position at the execution time,
even when another command has since changed the current timeline.

`executionSpreadMs` is max minus min translated execution times, included only
after at least two reports. `spreadUncertaintyMs` is twice the greatest reporting
clock uncertainty. These are client-observed diagnostics, **not rendered-frame
proof**. Failed landings still contribute execution timing; missing reports do
not become invented successes or zero spread. `missingCount=expectedCount-executedCount`;
`failedCount=executedCount-successCount`. COMPLETE means all recipients reported,
not necessarily all succeeded.

At most 16 command records per party survive. Each closes 10000 ms after its
execution deadline; missing/disconnected/stopped recipients remain explicitly
missing at TIMED_OUT. A host-policy pause that supersedes a pending play closes
it as SUPERSEDED. Eviction of an unfinished record emits EVICTED. Closed records
reject further reports; exact request retries still return the cached response.
Reports for evicted commands fail COMMAND_GONE. Snapshots carry only the latest
outcome (not unbounded history); clients may retain their own diagnostics history.

## Clocks, scheduling, reports and adapter obligations

The server domain is milliseconds from this process's monotonic origin
(`System.nanoTime`), with opaque serverEpoch reset on service/process restart.
Client timestamps are nonnegative milliseconds from one continuous client
monotonic domain (PWA performance clock; Android elapsed realtime). Rebase a signed
nanoTime source if necessary. Never send Date.now/UTC or reset the client domain
without REJOIN/recalibration.

For each CLOCK, the server records s0 at processing and returns an opaque
single-use probeId. Record client receive c1 and send c2, then CLOCK_REPLY. s3 is
server receipt/processing (`serverMonoMs` in the reply). Server calculates
`delay=(s3-s0)-(c2-c1)`, and server-minus-client offset
`((s0-c1)+(s3-c2))/2`. Impossible/negative/over-2000ms delay or over-5000ms exchange
is rejected. At most eight outstanding probes and twenty retained samples exist.
Calibrate with **at least five** rapid exchanges before READY. Repeated probe replies
are rejected except exact request retry cached responses.

Use the five lowest-delay samples in the last 15000 ms. Confidence requires five
fresh samples and uncertainty <=150 ms (half-delay plus offset dispersion).
This bounds, rather than magically removes, unknown asymmetry. p95 uses the whole
fresh rolling window, including spikes. Refresh every 5 seconds and on foreground,
network changes or clock reset. An uncertain/stale client is not a timeline reference.
Client deadline = `executeAtServerMonoMs - serverMinusClientMs`.

Lead = `min(3000,max(750,3*slowestHealthyP95RttMs+250))`.
No IP/subnet/VPN assumptions. CLOCK_MODEL contains measured values; propagate
uncertainty to diagnostics/UI. No NTP-quality guarantee exists under unknown
asymmetric transport delay or browser timer throttling.

Prepare via **existing normal NG Watch(MediaFile ID)** and per-client surface
negotiation, media authorization, parental checks, OPENURL/XCODE_SETUP and existing
stream metadata. CREATED/JOINED is authorization to prepare, not authorization to
start unscheduled playback. Obtain activation, hold the adapter paused, buffer at
least 1500 ms (or all remaining completed media when less remains), complete clock
calibration, then READY. Do not send ordinary server
Pause while preparing: in-party play controls are intercepted as shared actions.
The server preflights the existing PlaybackDecisionEngine for that viewer, leases
Party bitrate/transcode budget before returning JOINED, and rechecks the actually
loaded same MediaFile/surface/player state **and actual producer admission** on READY. No raw file paths/media URLs
are placed in Party messages. Media must be seekable Sage-managed video, non-DVD,
non-BluRay, available and have a positive current duration. Completed recordings
and active in-progress/timeshift recordings, including ordinary segment rollover,
are supported. Every participant in a growing party must advertise
`PARTY_GROWING_MEDIA_V1`; older clients fail `GROWING_MEDIA_UNSUPPORTED`.
Raw `MediaFile.isAnyLiveStream()` sources remain excluded because they do not
provide the stable shared recording identity/window used by this contract.

Growing playback intentionally trails the writer by `party/live_edge_delay_ms`
(default 3000 ms, minimum 1500 ms), leaving enough contiguous media for the
unchanged READY buffer floor. CURRENT_POSITION creation is clamped to that safe
edge when the host is closer to live. The canonical playing position and safe
edge then advance together, preserving the same delay; pause leaves the canonical
position fixed while the recording continues growing. FF cannot seek beyond the
current safe edge. Segment rollover stays the same MediaFile timeline; producer
generation/close fences still force only the affected viewer through normal
RECOVERING and fresh media admission. Recording completion converts the party
to fixed-duration behavior without requiring rejoin.

Readiness requires prepared+activated flags, minimum buffer, confident clock,
actual server media-path check and server-confirmed admission. Preparation metadata never authorizes
the client to override the normal delivery decision. An admission denial requires
the client to abandon/close its own failed prepared path and LEAVE or retry after
quality changes; other participants are untouched. Party-only budget leases are conservative
(at least twice source/selected bitrate and no shrinking below preflight budget);
normal downstream GPU admission remains authoritative at actual stream setup.
Locally stopped releases Party budget; reconnect grace retains it; leave,
expiry and shutdown release it. Normal media-session cleanup remains in the normal
playback/HTTP lifecycle, not in a shared Party media connection.

### Actual producer admission and future thin-bridge routing

This is implemented server-side on the **existing** media listeners. No additional
public API or listener is used. It is not inferred from IP, a UI client name, a
path, `prepared:true`, a selected transcode mode or an estimated bitrate.

For pull, pull-xcode and HLS: after normal NG Watch has loaded the same media on
the renderer, request MEDIA_ADMISSION on its Party control connection. The server
issues a random 192-bit, 32-character URL-safe handle bound to that exact renderer,
VideoFrame, loaded MiniPlayer generation, MediaFile and delivery mode. It is not
a participant ID and never appears in roster/snapshots/logs. Unused handles expire
after 60000 ms; another MEDIA_ADMISSION replaces the previous handle. Only
preparing/recovering/buffering members may request one.

#### Same-JVM opaque ownership proof for a transparent bridge

For **prospective ordinary native** playback before entering Party, see the
separate [NativePlaybackOwnershipV1.md](NativePlaybackOwnershipV1.md).
That optional exact-Socket API can establish ownership before the native OPENURL
reply; it cannot adopt pre-existing unassociated producers, replace actual
teardown proof, or bypass this Party admission protocol. Native cookie transport
is bridge-owned and conditional on trusted HTTPS/secure control and device
validation; Safari's approved same-origin shared media-use boundary is not
per-tab cryptographic identity.

The bridge cannot authenticate server approvals by scanning relayed bytes:
normal control encryption/compression remains unchanged. The implemented Java
boundary is `java/sage/PartyMediaAdmissionBridge.java`, loaded from the server's
Sage classes in the **same JVM and shared Sage classloader**:

```java
public static PartyMediaAdmissionBridge.Context claim(String handle, java.net.Socket upstreamControlSocket);
public static boolean isCurrent(String contextId, java.net.Socket upstreamControlSocket);
public static void release(String contextId, java.net.Socket upstreamControlSocket);
```

`claim` returns the public immutable record with these exact Java accessor types:

```
String contextId(), serverEpoch(), sessionHandle(), mode(), requestId()
int mediaFileId()
long playbackGeneration(), pathGeneration(), expiresAtServerMonoMs(), requestSeq()
```

Pass the bridge's **actual owned, connected, nonclosed, non-half-closed upstream
graphics Socket object**, not HTTP/client fields or an endpoint reconstructed
from them. Server checks the exact currently accepted renderer SocketChannel
object and the reversed **full local/remote TCP endpoint pair**. A different
socket on the same IP, a replaced channel, an unconnected/closed socket, stale
generation or already-claimed handle is denied. The context is populated only
by server-owned MEDIA_ADMISSION_HANDLE state, including that reply's requestId
and requestSeq; client JSON cannot substitute context fields. The context/handle
is valid for one viewer, player/path generation, canonical media and mode.
`sessionHandle` stays an opaque ephemeral renderer handle, not a person/device ID.

Claim atomically consumes only the bridge-registration right, **not** the
downstream producer binding: it does not open media or make READY succeed.
Continue with exact successful OPEN, PARTY_BIND on that producer and actual
output. Never adopt an existing unproven producer. Retire only a producer already
proven owned by the previous immutable context, wait for its real CLOSE, then
OPEN the new one. Coordinator keys and lifecycle must derive from the immutable
verified context, never URL session identity, path, IP, clientName or "last client".

The bridge may expose its own connection-local registration ACK and short-lived
request-header-only HTTP capability for that verified context; those are bridge
implementation surfaces, **not server Party operations or new server endpoints**.
No cookies, URL credentials, parser bypass, private-field reflection or new
authentication store/listener is introduced here. Native/headerless `src` cannot
use a request-header-only route by assumption: use a genuinely supported
header-capable MSE path or report unsupported association.

`claim` throws `IllegalStateException("PARTY_ASSOCIATION_DENIED")` without paths,
credentials or endpoint details. Missing API, another JVM/classloader,
remote/NAT/proxied topology without the exact live pair, or an ambiguous/missing
association must fail closed for **Party only**. Do not reconnect or weaken the
match to IP/client metadata; ordinary remote/native playback is unchanged.
Reusing a differently constructed Socket object never passes `isCurrent`/release,
even with copied endpoint values.

Use the **same Socket object** for `isCurrent` and `release`. Check before
HTTP admission, coordinator acquisition, producer binding and readiness, and
poll at least every 250 ms while a Party coordinator is retained; close only
that owned producer when false. FIN/disconnect is observed by the existing
transport lifecycle, not guessed from IP or a JSON notification. There is no
public callback registration and no server callback executes bridge-owned work
under the registry lock. Registry revocations enqueue targeted renderer events
through its bounded output worker; bridge polling is authoritative because it
does not parse encrypted control events. Release on retirement unregisters and
permanently revokes that context; duplicate release is harmless and a wrong
socket cannot release another viewer's grant.

At most 80 admission contexts exist. The unused deadline is 60000 ms in the
current serverEpoch's monotonic domain; it is **not** a UTC timestamp or an active
producer expiry. A bound current producer survives that startup TTL, while
generation, surface/Watch, disconnect, Stop/leave/end and producer failure/close
still revoke it. Maintenance and claim/bind/confirmation/currentness checks prune
stale contexts, and a rejected/revoked context never revives. A duplicate cached
MEDIA_ADMISSION reply cannot restore an expired or consumed claim; use a new
ordered request for a new grant only after retiring the old owned producer.

Targeted `MEDIA_ADMISSION_REVOKED{contextId,reason}` contains no handle or code.
Reasons are `REPLACED`, `RELEASED`, `PRODUCER_CLOSED`, `GENERATION_CHANGED`,
`PRODUCER_FAILED`, `EXPIRED`, `CONTROL_CLOSED`, `BRIDGE_RELEASED`. The notification
is best-effort on a live owning stream; shutdown/disconnect may prevent delivery.
Cancel the local context immediately, but do not depend on receiving it for
bridge retirement or treat it as authority to operate another renderer.

For existing MediaServer direct/pull-xcode:

```
[normal XCODE_SETUP ... if needed, and normal OPEN/OPENW ... -> OK]
PARTY_BIND <32-character handle>\r\n
OK\r\n
```

This new optional ASCII command is sent **after successful OPEN**, on that exact
media producer connection. Invalid/stale/wrong-media/wrong-mode/reused-producer
bindings reply `PARTY_DENIED\r\n`; the handle is redacted in debug logging.
Normal OPEN/XCODE_SETUP/SIZE/READ bytes and existing media authorization are
unchanged when the command is absent. A direct producer must have an open server
file channel. A transcode producer must have successfully produced bytes and
be running or completed successfully; an allocated encoder alone is insufficient.
Binding may succeed before output exists; READY remains fail-closed until output.
OPEN failure never creates a confirmed grant.

For existing `/iosstream_...` HLS requests, send HTTP header
`X-SageTV-Party-Admission: <handle>` on the media requests. This header is
redacted by the HTTP logger; never put the handle in a URL/query. Binding requires
the cached HLS producer's exact VideoFrame context, MediaFile and a unique
producer. A nonempty media segment must have been produced and the encoder must
be running/completed successfully; a playlist or initialization segment alone is
not evidence of admission. Normal requests without the header are unchanged.

**Future PWA/bridge work, not implemented in the PWA repository:** the PWA must
forward MEDIA_ADMISSION's handle as `X-SageTV-Party-Admission` on its existing
per-client bridge media request. The bridge must use its already-associated
renderer/control session and per-playback-generation coordinator, then relay
PARTY_BIND after successful upstream OPEN on **that exact coordinator producer**,
before reporting preparation/readiness success. For HLS it forwards the header
to the existing server HTTP path. This is a new optional input contract for the
future bridge, not a claim that today's bridge accepts/relays it.
The future bridge must redact this header and the PARTY_BIND command from raw
request/debug/error logs; never log the handle or use it as a human/device label.
Coordinator ownership must include the actual client/control session and playback
generation/media/mode; never reuse a producer across Party viewers based on a
matching file, IP or global URL. A handle cannot authorize a second producer or
allow one producer to confirm two viewers. If upstream OPEN or PARTY_BIND fails,
close that failed producer, discard the handle, report the error to that client,
and do not send READY. Do not retry on a shared fallback producer.

Load/fast-load/free (even of the same media), renderer replacement/disconnect,
local Stop, explicit rejoin/leave/end and failed/closed producer revoke admission.
A bound live producer may outlive the unused-handle TTL. Failed proof cannot revive;
obtain a fresh handle for the current generation. READY before confirmation fails
MEDIA_ADMISSION_UNCONFIRMED and changes no other member or timeline. Maintenance
detects loss after readiness within its one-second tick, cancels only that viewer's
pending server work and marks it RECOVERING with the same error.
Server-owned push checks its actual loaded source/produced transcode output directly,
so no PARTY_BIND is needed. Remote-pusher paths without local admission evidence
remain fail-closed; they are not proven by a selected `push` label.

Apply CORRECTION at the deadline, report actual landing with CORRECTION_RESULT,
then send POSITION every 1 s while playing/buffering and 5 s paused, plus immediate
seek/state/underrun reports. Samples are compared to the canonical timeline at
their **translated sample time**, not receipt time. Samples older than 3000 ms or
in the future beyond clock uncertainty fail; out-of-order position samples fail.
ACK a COMMAND with `stage:"RECEIVED"` and that command's version/sequence. After
actual execution/landing, ACK again with `stage:"EXECUTED"` and its observed
success, position, state and monotonic sample time; do not treat receipt as execution.

Deadband is max(adapter precision,125 ms), or max(precision,250 ms) when measured
p95 RTT >40 ms. Beyond it and <=750 ms, SOFT uses 0.98/1.02 rate for <=5000 ms
only if optional capability AND READY rateAdjust are true and both states PLAYING;
restore rate=1 at duration expiry. Otherwise HARD seeks to the projected position
at the deadline and applies target play/pause state. Neither correction changes
party version/seq. Verify real landing; soft partial improvement is progress, not
immediate failure. Three failed/time-out corrections within 30000 ms mark only
that participant DEGRADED. No result within 12000 ms counts as failure, leaving
transport and processing allowance beyond the client's 8000 ms landing timeout.
Rejoin is required after degradation. Reports/clock timeout mark RECOVERING, not synchronized.

Default buffering policy leaves others moving. STATUS BUFFERING with host-enabled
pauseOnBuffer issues an explicit scheduled shared PAUSE, including superseding a
pending PLAY; this is a canonical command with a new version/sequence. Under
KEEP_MOVING, a strict majority of connected admitted active members buffering
emits BUFFER_RECOMMENDATION immediately (or within 3000 ms under flapping), with
counts and a recommendation to pause/reduce quality. It is advisory, never a
hidden pause/Stop; timeline versions do not change. The denominator includes
READY/PLAYING/PAUSED/BUFFERING members, not stopped/preparing/disconnected/degraded
members. At most one notice per majority episode, no more than one per 2000 ms;
the one-second maintenance tick handles a rate-limited renewed episode.
Policy changes are applied to existing buffering too. Buffer restoration uses
READY and targeted catch-up; it never rewinds the whole party or generates Stop.

**Stop interception**: call adapter.localStop only, cancel local pending deadlines,
send STATUS LOCALLY_STOPPED, show "Party continues on other devices". Do not send
ACTION STOP, a normal Sage STOP event, or END. This status changes no timeline
anchor/version/seq and produces no remote COMMAND. Stopped clients cannot become
ready or control by sending other statuses: REJOIN is explicit and requires fresh
clock/preparation. END is separate, host-only and should require client confirmation.
ENDED invalidates Party authority; it is **not** an instruction to call player.stop.
Server shutdown/restart never synthesizes Stop.

## Errors and recovery

Every ERROR has schemaVersion/type/code and requestId when parseable. Codes:

```
NOT_AVAILABLE SESSION_EXPIRED SESSION_DENIED INVALID_REQUEST UNKNOWN_TYPE RATE_LIMITED REQUEST_ID_REUSED
JOIN_RATE_LIMITED JOIN_CODE_EXPIRED JOIN_CODE_UNAVAILABLE CODE_ROTATION_COOLDOWN CODE_ISSUE_RATE_LIMITED
START_MODE_UNSUPPORTED CURRENT_POSITION_UNAVAILABLE PREPARE_UNSUPPORTED PREPARATION_PENDING PREPARATION_BUSY
SURFACE_UNSUPPORTED SURFACE_UNAVAILABLE SURFACE_QUERY_FAILED SURFACE_SELECTION_PENDING
SURFACE_ALREADY_SELECTED SURFACE_SWITCH_UNSAFE SURFACE_CHANGED SURFACE_PREPARATION_REQUIRED
STALE_REQUEST SERVER_EPOCH_CHANGED ALREADY_JOINED SERVER_CAPACITY MEDIA_UNAVAILABLE
MEDIA_MISMATCH MEDIA_PATH_NOT_READY MEDIA_ADMISSION_UNCONFIRMED PARTY_GONE JOIN_DENIED PARTY_FULL REJOIN_DENIED
NOT_MEMBER HOST_REQUIRED CLIENT_UNAVAILABLE CLOCK_SAMPLE_INVALID NOT_READY
REJOIN_REQUIRED RESOURCE_DENIED STALE_STATE INVALID_ACTION TRANSITION_PENDING
CLOCK_UNCERTAIN STALE_SAMPLE STALE_CORRECTION CORRECTION_PENDING PREPARATION_EXPIRED
COMMAND_GONE NOT_COMMAND_RECIPIENT OUTCOME_CLOSED OUTCOME_ALREADY_REPORTED EXECUTION_PENDING
```

Surface the specific error, not success-shaped fallback. STALE_STATE:
fetch snapshot; TRANSITION_PENDING: wait for deadline/snapshot; CLOCK_UNCERTAIN:
recalibrate; RESOURCE_DENIED: reduce own quality or leave; PARTY_FULL: no sixth
slot; REJOIN_REQUIRED: explicit barrier; PARTY_GONE/SERVER_EPOCH_CHANGED:
discard old authority/tokens/deadlines and fall back to independent playback.
SESSION_EXPIRED: HELLO on the same admitted connection, then send a new request
with the refreshed token; SESSION_DENIED: discard mismatched credentials and HELLO.
MEDIA_ADMISSION_UNCONFIRMED: wait for actual output or fix/rebind only your
current-generation producer; do not override it with client readiness claims.
NOT_AVAILABLE: hide Party UI; ordinary playback continues. Never execute rejected
actions speculatively. Snapshots can supersede an in-flight correction; discard
old-version corrections, fetch new state and rejoin rather than executing stale work.

## Progress ownership, diagnostics and known acceptance limits

VideoFrame's normal periodic and close/EOS watched writes are suppressed during
membership. POSITION does not write progress. Explicit LEAVE/END of an admitted
participant commits canonical relative progress through the existing
BigBrother/permission policy, even after local Stop. This repository's watched
history is shared Airing state, **not newly invented per-account/per-profile
storage**. Unprepared participants and shutdown/grace expiry do not synthesize
progress commits. The old media instance is marked logged when Party ownership
releases, preventing a later player close from overwriting the canonical commit.
Open normal playback anew when returning to ordinary progress ownership.

Structured `PartyMode` logs contain only server-generated party/participant IDs,
version, seq, monotonic timing, command/correction/status/outcome codes. No raw
envelopes, tokens, passwords, user names, media URLs or paths are logged. Roster
snapshots include link confidence and effective delivery for diagnostics.

Known limitations, not claims of completed PRD acceptance:

* PWA/Android adapters/UI are not implemented here. Physical mixed-client,
  activation, AVPlay/HTML5/Android lifecycle behavior and 30-minute p95 skew/action
  convergence targets remain unproven. Synthetic tests are not a physical matrix.
* Generic Party budgets bound Party concurrency/estimated egress only. There is
  no existing machine-wide software-transcode CPU reservation or generic egress
  governor to reuse. Normal GPU resource admission still happens in the existing
  stream pipeline; provisional surface ranking may be revised there. Do not
  promise five transcoded streams based on a five-slot roster or these unit tests.
* Decoder seek precision, physical encoder pressure and
  normal media cleanup need end-to-end hardware validation. READY requires an
  actual loaded server player and client observations but does not independently
  measure browser activation or rendered frames.
* Selected-client invite handles have no server-synthesized friendly-device labels.
  Majority-buffer recommendations and bounded execution-spread aggregation are
  implemented; rendered-frame skew metrics are not independently measured.
* Today's PWA/Android clients and PWA bridge do not yet implement the new media
  admission echo/command or execution-report protocol. They cannot pass Party
  readiness by sending flags alone. This is required future client/relay integration,
  not an unimplemented server resource grant.
* The opaque bridge API requires a shared server JVM/Sage classloader and an
  exact live control-socket endpoint pair. Separate-JVM/NAT/unmatched association
  has no ownership fallback. Prospective native ownership has the separate
  conditional secure-cookie/teardown contract above; unassociated native streams
  remain unclaimable. Actual native cookie/device behavior and platform MSE
  probing/codec/contiguous-buffer support remain client acceptance work.
* Transparent socket-only reconnect does not reauthorize Party Mode; a fresh
  server-admitted NG session plus recovery token is required. LAN needs no login
  under existing LAN admission policy. Raw live/network streams without a stable
  shared Sage recording identity remain unsupported.
  Smooth FF/REW uses ordered discrete skips; arbitrary seek/shared scan rates are
  not part of this v1 contract.

## Reproducible verification and concrete examples

Run from the repository root on Windows:

```powershell
.\gradlew.bat test --tests sage.party.PartyServiceTest --tests sage.party.PartyJoinCodesTest --tests sage.NgPartyTransportIntegrationTest --tests sage.NgPartyMediaAdmissionTest --tests sage.VideoFrameTest --tests sage.MiniPlayerTest --tests sage.MiniPlayerSocketReconnectTest --tests sage.client.PlaybackDecisionEngineTest --tests sage.client.PlaybackSurfaceSetTest --tests sage.MediaServerXcodeEnhanceTest --tests sage.HTTPLSServerTest
node --test .\docs\party\v1\examples.cjs
```

`golden.json` contains exact envelopes, framing bytes and deterministic field
examples. `examples.cjs` demonstrates both PWA (`performance.now`) and Android
(`elapsedRealtime`) monotonic deadline translation without implementing either
client. `PartyServiceTest.Client` is a runnable synthetic client for full
create/join/clock/ready/action/report/rejoin flows through the real framing adapter.
`NgPartyTransportIntegrationTest` exercises the actual renderer inbound dispatcher,
per-renderer adapter, service and outbound SetProperty for create/join/leave,
including cross-renderer token/auth/capability rejection, server-owned push
scheduling/cancellation, renderer authorization invalidation, and unconsumed legacy
property regression. Its media/environment boundaries are mocked, not a physical
stream startup test.
`NgPartyMediaAdmissionTest` exercises the actual MediaServer binding and HLS
producer-confirmation callback with deterministic producer boundaries, including
expiry/replacement/generation/close/failure and per-viewer isolation. These are not
physical encoder/HTTP soak acceptance results.
