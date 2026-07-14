# NG Download Client: Engineering Handoff

Audience: NG client team

Purpose: This is the implementation handoff for the client team. It turns the PRD into an engineering plan with exact behaviors, payload handling, state transitions, and coding guidance.

Versioning rule for this release:

- Treat this as one fixed V1 contract.
- Do not add end-user settings, advanced toggles, debug switches, or user-visible policy knobs for download negotiation, metadata fetch policy, refresh cadence, queue semantics, credential security, or offline sync behavior.
- Automatic behavior changes driven by server responses, negotiated protocol state, or built-in client logic are allowed under V1 as long as they are not user-tunable and do not create a separate V2 contract.
- If we change behavior before release, server and client must change together under the same V1 contract.
- Do not introduce a V2 split, alternate protocol mode, or client-side compatibility branch before the release date.

Primary source docs:
- [NGDownloadClientPRD.md](c:/Users/ted/SageTV-NG/docs/NGDownloadClientPRD.md)
- [NGDownloadManifestV1Schema.md](c:/Users/ted/SageTV-NG/docs/NGDownloadManifestV1Schema.md)
- [OFFLINE_PLAYBACK_SYNC_SERVER_CONTRACT.md](c:/Users/ted/SageTV-NG/docs/OFFLINE_PLAYBACK_SYNC_SERVER_CONTRACT.md)

## 1. What The Client Team Needs To Build

You need to build six things:

1. A strict manifest parser and typed domain model for manifest v1.
2. A unified download negotiation orchestrator for both initial and refresh flows.
3. A Download Manager state model and UI that handles queued, active, paused, completed, and failed items.
4. Two-step metadata handling so inline metadata paints immediately and the full manifest replaces it when fetched.
5. Download credential exchange security with HTTPS plus pinning semantics.
6. Offline playback-state reconciliation for completed downloads only.

If you implement those six pieces correctly, the rest is mostly UI wiring and test coverage.

## 2. Non-Negotiable Rules

These are release-gate rules, not suggestions:

- Reject manifest data when `manifest_version != 1`.
- Never show raw transport URLs as normal UI.
- Do not expose user-tunable settings for this flow under V1.
- Use one orchestrator for all download operations.
- Never run HTTP refresh and command-channel refresh in parallel for the same logical attempt.
- Always rotate to the newest `session_token` returned by ACK.
- Parse inline `offline` immediately.
- If `offline_metadata_url` or `offline_metadata_path` exists, support full-manifest fetch.
- Replace full sections from fetched manifest. Do not sticky-merge fields.
- Require HTTPS for download credential exchange after pairing.
- Fail closed on pin mismatch or HTTPS downgrade for credential exchange.
- Reconcile offline playback state only for completed downloads.

## 2A. V1 Freeze Policy

This needs to be explicit for implementation and review:

- The client may have internal constants for timeouts, cooldowns, retry windows, and background scheduling.
- Those constants are implementation details, not end-user preferences.
- Do not add a settings-screen entry, developer menu toggle, or hidden preference for these behaviors in V1.
- Automatic tuning between client and server is allowed when it stays inside the agreed V1 wire contract and is not exposed as an end-user control.
- If an implementation constant needs to change before release, coordinate the change with the server side and keep the wire contract labeled V1.
- Any true productized tunability or protocol-version branch waits until after release.

## 3. Recommended Client Architecture

Use these modules.

### 3.1 Data / Model Layer

Implement typed models for:

- `TransferSessionAck`
- `TransferSessionError`
- `TransferStatus`
- `OfflineManifestV1`
- `OfflineManifestCore`
- `OfflineManifestMetadata`
- `OfflineManifestCredit`
- `OfflineManifestArtwork`
- `OfflineManifestAsset`
- `OfflinePlaybackSyncRequest`
- `OfflinePlaybackSyncResponse`

Keep raw unknown JSON fields in an `extras` or `unknownFields` map for diagnostics.

### 3.2 Download Orchestrator Layer

Implement one coordinator, for example `DownloadNegotiationOrchestrator`, that owns:

- request context creation
- correlation ID reuse
- lane selection
- timeout handling
- retry with jitter
- token rotation
- two-step metadata follow-up
- telemetry emission

Do not spread refresh logic across UI screens, background workers, and command handlers independently.

### 3.3 Persistence Layer

Persist:

- latest `session_token`
- `queue_item_id`
- `session_state`
- `download_username`
- `download_password`
- `offline` inline snapshot
- fetched full manifest snapshot
- per-server trust pin
- completed-download playback state (`watched`, `resume_position_ms`)

Credential replacement must be atomic when a refresh ACK rotates values.

### 3.4 UI Layer

Minimum screens/features:

- Recording detail page
- Download Manager
- Persistent active-download notification
- Explicit re-pair UX on pin mismatch

The Download Manager should be the canonical home for queued, active, paused, completed, and failed download state.

## 4. Manifest Parser Requirements

### 4.1 Validation

The parser must:

- require `manifest_version`
- reject anything other than `1`
- tolerate unknown keys
- preserve unknown keys for logging
- never crash on sparse manifests

### 4.2 Section Behavior

Treat these sections as replaceable snapshots:

- `core`
- `metadata`
- `credits`
- `artwork`
- `assets`

When the full manifest is fetched successfully, replace each section wholesale.

### 4.3 Artwork Selection

Use this exact priority:

1. `thumbnail`
2. `poster`
3. `fanart`
4. placeholder

Do not block rendering on artwork availability.

## 5. Unified Download Negotiation Flow

Implement exactly one orchestrator for both `initial` and `refresh`.

### 5.1 Request Context

For every logical operation build:

- `operation`: `initial` or `refresh`
- `correlationId`: UUID reused across retries and fallback for that operation
- `clientId`
- `recordingId`
- `sessionToken` when available
- `bytesTransferred`
- `reason`, recommended: `forced_fresh_manifest` for metadata refresh

### 5.2 Lane Ordering

Use this order:

1. Tokened HTTP refresh when token exists
2. Generic HTTP refresh without token
3. Generic HTTP refresh with token in body when appropriate
4. Command-channel refresh only if HTTP did not return ACK and the command channel is healthy

Command channel is optional fast-lane/fallback. It is not the primary dependency for standalone correctness.

This ordering is fixed for V1 and must not be exposed as a user preference.

### 5.3 Command Channel Refresh

Opcode: `228 DOWNLOAD_REFRESH_REQUEST`

Payload JSON:

```json
{
  "sessionToken": "<current_session_token>",
  "mediaFileID": "12163322",
  "bytesTransferred": 1598486013,
  "correlationId": "05336179-4a9c-4429-a91f-fa307ba20e13",
  "clientId": "46:51:42:48:55:43",
  "reason": "forced_fresh_manifest"
}
```

Expected async response on command channel:

- `TRANSFER_SESSION_ACK`
- or `TRANSFER_SESSION_ERROR`

Recommended fallback timeout before trying another lane: 2 seconds.

### 5.4 HTTP Refresh Endpoints

Preferred tokened refresh:

- `POST /api/transfers/{session_token}/refresh`

Generic refresh:

- `POST /api/transfers/refresh`

Request body:

```json
{
  "session_token": "<current_session_token_optional>",
  "recording_id": "12163322",
  "mediaFileID": "12163322",
  "bytes_transferred": 1598486013,
  "correlation_id": "05336179-4a9c-4429-a91f-fa307ba20e13",
  "reason": "forced_fresh_manifest",
  "ng_client_id": "46:51:42:48:55:43",
  "clientId": "46:51:42:48:55:43"
}
```

Client should send POST-first even if compatibility GET exists server-side.

## 6. Required ACK Parsing

Parse and persist all of these from `TRANSFER_SESSION_ACK`:

- `type`
- `queue_item_id`
- `session_token`
- `recording_id`
- `session_state`
- `download_url`
- `download_path`
- `download_username`
- `download_password`
- `offline_metadata_url`
- `offline_metadata_path`
- `offline_inline_level`
- `offline`
- `expires_in_seconds`

Rules:

- Do not log raw credentials.
- On every successful ACK, replace stored token immediately.
- On every successful ACK, replace stored download credentials atomically.

## 7. Two-Step Metadata Delivery

This is mandatory.

### 7.1 First Paint

As soon as ACK arrives:

1. Parse `offline`.
2. Render title, subtitle, summary, and available inline image data immediately.
3. Do not wait for full-manifest fetch to render the screen.

### 7.2 Full Manifest Fetch

If either exists:

- `offline_metadata_url`
- `offline_metadata_path`

then fetch the full manifest.

Behavior:

- if `offline_inline_level == core`, fetch immediately
- if `offline_inline_level == full`, fetch in background

### 7.3 Replacement Semantics

When valid full manifest arrives:

- validate `manifest_version == 1`
- replace `core`
- replace `metadata`
- replace `credits`
- replace `artwork`
- replace `assets`
- refresh UI without navigation reset

Do not keep stale inline fields when the fetched manifest has the updated section.

### 7.4 Failure Handling

If fetch fails:

- keep displaying inline metadata
- retry in background with bounded backoff
- do not fail the download creation flow

## 8. Download Manager State Model

Implement these item states:

- `Queued`
- `Starting`
- `Downloading`
- `Paused`
- `Completed`
- `Failed`

Each item should carry at least:

- `queue_item_id`
- `recording_id`
- `session_token`
- `session_state`
- `bytes_transferred`
- `total_bytes`
- `reason` or `status_message`
- `download_speed`
- `eta`

Required item actions:

- `Pause`
- `Resume`
- `Cancel`
- `Retry`

Required global actions if backend supports them:

- `Pause all`
- `Resume all`

## 9. UI Routing Rules

These are important because the wrong routing creates bad UX quickly.

- `Download now`: stay on detail with success banner or route directly to Download Manager with the item focused.
- `Queue recording download`: show banner like `Added to queue (#N)` and route or deep-link into Download Manager state.
- `session_state = queued`: show queued state, not a raw URL screen.
- `session_state = transferring`: open or update active progress UI.
- Back from Download Manager must return to the previous app screen in one step.
- Do not add success screens that require an extra back press.

## 10. Error Handling Contract

Transfer errors are machine-readable. Example:

```json
{
  "type": "TRANSFER_ERROR",
  "error_code": "TRANSFER_TOKEN_INVALID",
  "message": "Transfer token is invalid or expired.",
  "retriable": true
}
```

Client actions:

- `409` with queued or paused state: expected wait-state, not failure
- `401` with `retriable=true`: try valid fallback path
- `401` with `retriable=false`: stop and surface integration or ownership error
- `404 TRANSFER_SESSION_NOT_FOUND` with `retriable=true`: try generic refresh using `recording_id`
- `400` malformed request: fail fast and log as client bug
- `429`: back off, do not lane-hop immediately
- `5xx` or timeout: retry with backoff up to max attempts

## 11. Storm Prevention Guardrails

These are mandatory.

### 11.1 Single-Flight

Maintain a per-recording single-flight lock keyed by `(recordingId, operation)`.

If a refresh is already running:

- do not start another network call
- mark one pending trigger
- run at most one follow-up attempt after current attempt completes

### 11.2 Lane Mutex

For one `correlationId`:

- only one lane may be active at a time
- no HTTP plus command parallel refresh for the same attempt

### 11.3 Cooldown

After successful ACK:

- suppress duplicate refresh for the same recording for about `1200 ms`
- allow at most one deferred intent during cooldown

### 11.4 Retry Policy

Retry only retriable outcomes.

Recommended jittered backoff:

- attempt 1: 400 to 700 ms
- attempt 2: 900 to 1400 ms
- attempt 3: 1800 to 2800 ms

Maximum attempts per logical refresh: 3.

### 11.5 Correlation Discipline

- one `correlationId` per logical operation
- reuse it across retries and fallback
- new ID only for new intent after cooldown

### 11.6 Token Hygiene

- rotate token on every ACK
- invalidate prior token immediately
- discard stale responses that arrive after a newer token is already committed

## 12. Download Credential Security

This applies specifically to download credential exchange.

### 12.1 Transport Rules

- require HTTPS for credential exchange endpoints
- do not fall back to plaintext HTTP after pairing
- block on pin mismatch

These security behaviors are mandatory in V1 and must not be bypassable by user setting.

### 12.2 Trust Model

Use TOFU plus persistent pinning:

- first successful secure pair stores a per-server identity pin
- pin survives restart and upgrade
- all later credential exchange verifies HTTPS plus pin match

### 12.3 Identity Change

If pin changes unexpectedly:

- block credential exchange
- show explicit re-pair-required UX
- do not silently trust new identity

### 12.4 Rotation

Transparent auto-accept only when rotation is cryptographically linked to current trust.

Otherwise require explicit re-pair.

### 12.5 Logging

Never log:

- `download_username`
- `download_password`
- raw token values

## 13. Offline Playback-State Reconciliation

This is required for offline-capable clients.

### 13.1 When To Sync

Run playback-state sync:

- after NG connection and capability negotiation completes
- before user-initiated single-recording metadata refresh
- after local playback position save
- after watched toggle
- after user chooses Start at Beginning

### 13.2 What To Include

Include only completed local downloads.

Do not include:

- new
- queued
- preparing
- downloading
- paused
- failed

### 13.3 Merge Rules

- watched: OR-style, if either side is true then authoritative result is true
- resume position: max-style, larger `resume_position_ms` wins

Client applies returned server state only if it wins locally by those same rules.

### 13.4 Endpoint

Use:

- `POST /api/offline/playback-state-sync`

Headers should follow existing offline transfer conventions.

## 14. Telemetry To Implement

Per refresh operation emit:

- `correlation_id`
- `recording_id`
- `lane_selected`
- `lane_fallback_count`
- `attempt_count`
- `result_type`
- `error_code`
- `retriable`
- `cooldown_suppressed_count`
- `coalesced_trigger_count`

Also emit:

- `manifest_parse_success`
- `manifest_parse_failure`
- `metadata_key_count`
- `credits_count`
- `artwork_count`
- `tls_required_enforced`
- `pin_state`
- `pin_mismatch_block_count`
- `https_downgrade_block_count`

## 15. Suggested Implementation Order

Build in this order.

1. Domain models and parsers.
2. Unified orchestrator with HTTP-first negotiation.
3. ACK persistence and token rotation.
4. Two-step metadata replacement pipeline.
5. Download Manager state store and UI.
6. Security pinning and re-pair flow.
7. Offline playback-state sync.
8. Storm-prevention and telemetry hardening.
9. Fixture, UI, and integration tests.

This order avoids building UI on top of unstable state semantics.

It also keeps the V1 contract centralized so any pre-release change is made once, in sync with server behavior, instead of being scattered across optional client knobs.

## 16. Suggested Test Matrix

Minimum tests to ship:

### 16.1 Parser Tests

- accepts valid v1 manifest
- rejects wrong manifest version
- preserves unknown metadata keys
- handles sparse manifests without exception
- groups credits dynamically by `role_name`

### 16.2 Two-Step Metadata Tests

- inline-only ACK renders first paint
- inline core plus fetched full manifest replaces sections correctly
- fetch failure keeps inline state intact
- artwork updates after fetched full manifest
- no navigation reset on metadata replacement

### 16.3 Negotiation Tests

- tokened HTTP refresh success
- generic HTTP fallback success
- command fallback only when HTTP does not produce ACK
- no parallel lane execution per correlation ID
- token rotation applied on every ACK
- stale ACK ignored after newer token commit

### 16.4 Queue And UX Tests

- queued state shows queue position
- active state shows progress
- completed state exposes play or open actions
- failed state exposes primary recovery action
- no raw URL page shown
- no multi-back success flow

### 16.5 Security Tests

- first pair stores pin
- unchanged pin proceeds silently
- pin mismatch blocks credential exchange
- HTTPS downgrade blocked after pairing
- linked rotation auto-accepts
- untrusted rotation requires re-pair

### 16.6 Offline Sync Tests

- bulk sync runs after NG capability negotiation
- sync runs before single-recording refresh
- only completed downloads included
- watched merge is OR-style
- resume merge is max-style
- unsupported server responses `404/405/501` do not break normal flow

## 17. Pseudocode Skeleton

Use this as the client-team baseline.

```ts
async function refreshRecording(ctx: RefreshContext): Promise<void> {
  const key = `${ctx.recordingId}:refresh`;
  if (singleFlight.has(key)) {
    singleFlight.get(key)!.pending = true;
    return;
  }

  const op = {
    correlationId: uuid(),
    pending: false,
    attempts: 0,
  };
  singleFlight.set(key, op);

  try {
    do {
      op.pending = false;
      if (isInCooldown(ctx.recordingId)) {
        return;
      }

      const result = await runUnifiedNegotiation(ctx, op.correlationId);

      if (result.type === "ACK") {
        persistAck(result);
        rotateToken(result.session_token);
        await applyInlineOfflineFirstPaint(result.offline);
        await maybeFetchFullManifest(result);
        applyCooldown(ctx.recordingId, 1200);
        return;
      }

      if (!isRetriable(result) || op.attempts >= 3) {
        handleTerminalResult(result);
        return;
      }

      op.attempts += 1;
      await sleep(backoffWithJitter(op.attempts));
    } while (op.pending);
  } finally {
    singleFlight.delete(key);
  }
}
```

## 18. Release Gate Summary

The client is ready only when all of these are true:

- manifest v1 parser is strict and safe
- unified orchestrator is the only negotiation path
- two-step metadata replacement works
- queue and active states route correctly
- token rotation is correct
- storm guardrails are enforced
- credential exchange security is enforced
- offline playback-state reconciliation works for completed downloads only
- fixture and UI tests cover sparse and rich real-world cases

## 19. Bottom Line For The Client Team

If you want the shortest accurate summary:

- Treat the download flow as a state machine, not a one-shot URL fetch.
- Treat metadata as two-phase: inline now, full manifest after fetch.
- Treat security as transport-plus-pinning, not optional crypto.
- Treat refresh as a single-flight orchestrated operation with token rotation.
- Treat offline playback sync as a completed-download reconciliation job, not part of active transfer state.
- Treat all of the above as fixed V1 behavior with no end-user tunables before release.

That is the contract the client should code to.
