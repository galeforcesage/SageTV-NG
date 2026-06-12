# PRD: NG Download Client (Manifest v1)

Status: Draft for implementation kickoff

Product: SageTV NG Download Client

Server contract: NG Download Manifest v1 (canonical only)

Primary reference: docs/NGDownloadManifestV1Schema.md

**⚠️  INTEGRATION ALERT: Two-Step Metadata Delivery for Full Fidelity**

Server sends transfer ACKs in two forms to respect miniclient command-channel size limits:
- **Full inline manifest:** if ACK payload fits, includes complete `core`, `metadata`, `credits`, `artwork`, `assets`.
- **Core inline manifest:** if ACK is too large, includes only `core` + `metadata` with **empty** `credits[]` and `artwork[]` arrays, plus a URL pointer to fetch the full manifest.

**Thumbnails and full metadata are in the server-side full manifest.** Client must:
1. Parse inline `offline` immediately for first-paint title/episode.
2. Check for `offline_metadata_url` or `offline_metadata_path`.
3. Fetch and parse the full manifest from that endpoint over HTTP (no size limits).
4. Merge/replace `core`, `metadata`, `credits`, `artwork`, `assets` sections.

**This is required.** Without the fetch, client will show no thumbnails/artwork and sparse metadata when ACK falls back to core mode.

See section [5.8](#58-two-step-metadata-delivery-required) for detailed implementation pattern and pseudo-code.

## 1) Problem Statement

Recorded content has variable metadata completeness by source. The client must render recording details robustly without relying on hard-coded fields that may not exist for every program.

## 2) Objectives

- Build first client against one canonical manifest shape.
- Render metadata dynamically and safely for sparse and rich recordings.
- Avoid protocol churn by using canonical `credits[]` and `metadata{}`.
- Ensure predictable UX fallback when images or fields are absent.
- Provide transport security for download credential exchange that is transparent in normal use (silent when healthy, explicit only on identity change).

## 3) Non-Objectives

- Supporting previous manifest formats.
- Server-side UI-specific grouping in protocol.
- Role-specific hard-coded protocol fields.

## 4) Personas

- End user browsing downloaded recordings on mobile/TV.
- QA engineer validating metadata consistency across content types.
- Integration developer building client parser and detail screen.

## 5) Functional Requirements

### 5.1 Manifest Parsing

- Parse required top-level fields.
- Reject manifest if `manifest_version != 1`.
- Tolerate unknown optional fields in `metadata` and `assets`.
- Preserve unknown keys for logging and diagnostics.

### 5.2 Detail Screen Rendering

- Header:
  - title (required)
  - subtitle when present
  - runtime when > 0
- Hero image:
  - choose first available: thumbnail, poster, fanart
- Metadata panel:
  - render preferred known keys first
  - render remaining metadata keys alphabetically under “More Details”
- Credits panel:
  - group `credits[]` by role_name
  - sort names alphabetically in each role group
  - show person image when available
- Assets panel:
  - show captions/comskip/transcript availability

### 5.3 Missing Data Behavior

- Never crash on absent optional fields.
- Hide empty sections entirely.
- If no artwork exists, show neutral placeholder.
- If no credits exist, hide credits section.

### 5.4 Search/Filter (Phase 1)

- Search by title and subtitle.
- Filter by category if `metadata.categories` exists.

### 5.5 Download Action Semantics

- `Download now` means: create session and attempt immediate transfer start.
- `Queue recording download` means: create session without forcing immediate content fetch UI.
- If server marks session `queued`, client must show queue position and route user to Download Manager state, not a raw URL screen.
- If server marks session `transferring`, client must open or update the in-app download progress view.
- Client must not present transport URLs as a standalone screen in normal UX.

### 5.6 Queue Manager Behavior

- Add a dedicated Download Manager screen with tabs or filters for `Queued`, `Active`, `Paused`, `Completed`, `Failed`.
- Show queue metadata per item: `queue_item_id`, state, bytes transferred, total bytes, reason/status.
- `Start queue` behavior: queue is auto-run. Client polls queue/status and starts fetching content when item transitions to `transferring`.
- Expose item actions: `Pause`, `Resume`, `Cancel`, `Retry`.
- Expose global actions: `Pause all`, `Resume all` if backend APIs are available.
- If a queued item receives HTTP 409 with queued state, treat this as expected waiting state, not failure.

### 5.7 Download Confirmation and Notices

- Use a non-blocking confirmation banner after each action:
  - `Download started`
  - `Added to queue (#N)`
  - `Paused`
  - `Canceled`
- Add persistent notification while active download is running.
- Completion notification should deep-link to local file detail or downloads list.
- Error notices must include actionable wording (`Retry`, `Resume`, `Check network`) and preserve queue context.

### 5.8 Two-Step Metadata Delivery (Required)

- Client must support two-step manifest delivery for transfer session payloads:
  1. Parse inline `offline` block immediately for first paint.
  2. If `offline_metadata_url` or `offline_metadata_path` exists, fetch full manifest from server endpoint.
- Treat fetched manifest as authoritative when it is valid `manifest_version = 1`.
- Use `offline_inline_level` to classify inline payload:
  - `full`: inline payload may already be complete; background fetch is still allowed.
  - `core`: inline payload is intentionally reduced; client should fetch full manifest immediately.
- Metadata fetch failure must not fail download creation flow:
  - Keep showing inline metadata.
  - Retry in background with bounded backoff.
- Do not show transport URLs directly in UI.
- Merge policy:
  - Replace sections (`core`, `metadata`, `credits`, `artwork`, `assets`) from fetched manifest as a full snapshot.
  - Do not perform field-by-field sticky merges that can preserve stale values.

**Implementation Pattern for Thumbnail/Artwork Display:**

```
// On receiving transfer ACK:
let inlineManifest = parseJSON(ack.offline);
displayMetadata(inlineManifest);  // First paint: show title, episode, poster

// Check for full manifest fetch:
if (ack.offline_metadata_url || ack.offline_metadata_path) {
  if (ack.offline_inline_level === "core") {
    // Urgently fetch full manifest (inline is intentionally reduced)
    fetchFullManifestNow(ack.offline_metadata_url);
  } else if (ack.offline_inline_level === "full") {
    // Background fetch is optional (inline may be complete)
    fetchFullManifestInBackground(ack.offline_metadata_url);
  }
}

// When full manifest arrives:
async function onFullManifestReceived(fullManifest) {
  if (fullManifest.manifest_version !== 1) {
    logWarning("Invalid manifest version from server");
    return;  // Keep using inline
  }
  // **Replace entire sections** with fetched data:
  currentMetadata.core = fullManifest.core;
  currentMetadata.metadata = fullManifest.metadata;
  currentMetadata.credits = fullManifest.credits;
  currentMetadata.artwork = fullManifest.artwork;  // <-- Thumbnails come here
  currentMetadata.assets = fullManifest.assets;
  updateUI(currentMetadata);  // Refresh artwork/artwork display
}

// When displaying artwork:
function selectHeroImage(artwork) {
  // Prefer this order: thumbnail, poster, fanart, person, other
  for (let kind of ["thumbnail", "poster", "fanart"]) {
    let image = artwork.find(a => a.kind === kind);
    if (image && image.url) return image.url;
  }
  return null;  // Use placeholder
}
```

### 5.9 Offline Playback State Reconciliation (Required for Offline-Capable Client)

- Offline-capable clients must reconcile completed local recordings with the connected NG server for:
  - `watched`
  - `resume_position_ms`
- Reconciliation triggers:
  - after NG client/server connection and capability negotiation completes
  - before a user-initiated single-recording metadata refresh
  - after local offline playback position is persisted
  - after the user toggles offline Watched state
  - after the user explicitly chooses Start at Beginning
- Bulk reconciliation must include only completed local recordings. New, queued, preparing, downloading, paused, and failed downloads must be skipped.
- Merge policy:
  - Watched is OR-style: if either side is watched, authoritative watched becomes `true`.
  - Resume position is max-style: the higher playback position wins.
  - Returned server state is applied locally only when it wins under the same rules.
- Server-side implementation details are defined in `OFFLINE_PLAYBACK_SYNC_SERVER_CONTRACT.md`.

## 6) UX Requirements

- Time-to-first-render <= 500 ms for already-loaded manifest.
- Consistent section order:
  1. Header
  2. Hero image
  3. Core info
  4. Metadata
  5. Credits
  6. Assets
- Use concise labels for metadata keys; unknown keys prettified from snake_case.

### 6.1 Navigation and Back-Stack Rules (Required)

- No raw URL page in primary flow.
- No dead-end `OK` acknowledgment screens for successful download/queue actions.
- `Download now` from recording detail:
  - Stay on detail and show banner, or
  - Navigate directly to Download Manager with selected item focused.
- Back behavior from Download Manager always returns to previous app screen in one step.
- Action dialogs must use explicit `Close` and support system Back without double-back loops.

### 6.2 Download UX States

- `Queued`: show position and reason (for example waiting for active slot).
- `Starting`: transient state while content endpoint is negotiated.
- `Downloading`: show progress, speed, and ETA when available.
- `Paused`: show resume affordance.
- `Completed`: show open/play/delete affordances.
- `Failed`: show reason and primary recovery action.

## 7) Analytics and Logging

- Log parse failures with reason and manifest snippet hash.
- Log missing recommended fields (thumbnail/rated) as warnings, not errors.
- Emit counters:
  - manifest_parse_success
  - manifest_parse_failure
  - metadata_key_count
  - credits_count
  - artwork_count

## 8) Quality and Test Plan

### 8.1 Unit Tests

- Parser accepts valid v1 manifest.
- Parser rejects wrong manifest version.
- Unknown metadata keys are preserved.
- Credits grouping handles correspondent and any other role dynamically.
- Two-step parser prefers fetched full manifest over inline core payload when both exist.
- If fetch fails, parser/model falls back to inline payload without exception.

### 8.2 UI Tests

- Sparse manifest renders with no crashes.
- Rich manifest renders all sections.
- No-artwork manifest falls back to placeholder.
- No-credits manifest hides credits section.
- Download actions never show raw URL page.
- Download actions never require multi-back to dismiss success state.
- Queue flow shows `Added to queue (#N)` and item appears in Download Manager.
- Queued item receiving HTTP 409 remains in waiting UI and eventually transitions to active when available.
- Inline-core then fetched-full transition updates episode title/description/artwork without navigation reset.
- Identity-change security event shows explicit re-pair-required UX and blocks download credential exchange until user confirms re-pair.

### 8.4 Security and Pairing Tests

- Initial pairing with no stored pin over HTTPS stores server identity pin and proceeds without extra prompts.
- Subsequent connections with unchanged identity proceed silently and do not prompt.
- Pinned identity mismatch fails closed for download credential exchange operations and surfaces re-pair workflow.
- Plain HTTP download credential exchange endpoint usage is rejected once server is paired.
- Pin rotation using server-signed next key succeeds without user interaction.
- Rotation without trusted signature requires explicit re-pair confirmation.
- Event-channel crypto remains optional defense-in-depth and is not accepted as a replacement for HTTPS transport trust.

### 8.3 Golden Fixtures

- Movie sparse metadata fixture.
- Episodic fixture with season/episode.
- News/sports fixture including Correspondent/Anchor roles.
- Problem fixture equivalent to “Holey Moley” scenario.

## 9) Delivery Plan

### Milestone 0: Keep Download Encryption In Scope (Day 0)

- Keep the download credential exchange encryption/pinning code in place for the current release path.
- Track the remaining server-side support work as a follow-up roadmap item rather than rolling the client code back.
- Align the server contract and rotation policy so the current download security implementation can remain active and be fully supported.
- Document any server-side changes required to preserve or complete the download credential exchange key/pin model.

### Milestone 0A: Download Credential Exchange Encryption Contract (Day 0-1)

- Define the end-to-end username/password exchange for downloads as a paired client/server security contract.
- Server side: issue or refresh `download_username` and `download_password` in `TRANSFER_SESSION_ACK` over HTTPS-only download credential exchange endpoints.
- Server side: support the agreed pin target, rotation model, and re-pair behavior so the client can keep the trust decision stable across refreshes.
- Client side: parse the credential fields from ACK, store them only in encrypted local vault storage, and replace them atomically on refresh.
- Client side: require HTTPS for the download credential exchange endpoints, pin the server identity on first successful pair, and fail closed on plaintext after pairing.
- Client side: verify the pinned identity on every credential exchange, support explicit re-pair on mismatch, and treat rotation as transparent only when it matches the trusted pin/rotation model.
- Client side: never log raw download usernames/passwords and keep protocol event-channel crypto as defense-in-depth rather than the trust anchor for credential exchange.

### Milestone 1: Contract Lock (Day 0-1)

- Finalize schema doc.
- Align server/client on required/optional fields.
- Freeze `manifest_version = 1` semantics.

### Milestone 2: Client Parser + Models (Day 1-3)

- Implement parser and strict validation.
- Add typed domain models.
- Add parse metrics and error taxonomy.

### Milestone 3: Detail Screen Rendering (Day 3-6)

- Build presence-driven detail screen.
- Implement credits grouping by role_name.
- Implement artwork fallback logic.

### Milestone 4: QA + Fixture Validation (Day 6-8)

- Execute fixture and regression tests.
- Validate behavior against live manifest captures.
- Fix UX bugs and edge-case parsing issues.

### Milestone 5: Release Readiness (Day 8-9)

- Performance check.
- Crash-free validation pass.
- Ship decision review.

### Milestone 5A: Build/Test Stability Follow-up (Post-Release)

- Investigate and fix `sage.client.ClientProfileTest > testAutoDetect_AndroidMiniClient` (`AssertionError` at `ClientProfileTest.java:478`).
- Remove temporary `-x test` build workaround once the test is stable.
- Validate full `sageJar` build with tests enabled in CI/local release workflow.

## 10) Risks and Mitigations

- Risk: Highly variable upstream metadata completeness.
  - Mitigation: Presence-driven rendering and section suppression.
- Risk: New metadata keys introduced by server.
  - Mitigation: Unknown key support and generic rendering.
- Risk: Inconsistent image availability.
  - Mitigation: Deterministic image fallback and placeholder policy.

## 11) Done Criteria

- Client fully supports schema in docs/NGDownloadManifestV1Schema.md.
- All required tests pass.
- Live validation with multiple real recordings passes.
- No compatibility paths or temporary alias handling in client code.

## 12) Implementation Checklist for Client Coders

- [ ] Add manifest domain models
- [ ] Implement parser with strict required-field checks
- [ ] Implement metadata rendering order + unknown key rendering
- [ ] Implement credits grouping and sorting
- [ ] Implement artwork fallback chain
- [ ] Add fixtures and golden tests
- [ ] Add parse/quality telemetry
- [ ] Validate with real manifests from server
- [ ] **[REQUIRED] Parse inline `offline` immediately and display title/episode/summary from `core`**
- [ ] **[REQUIRED] Check transfer ACK for `offline_metadata_url`**
- [ ] **[REQUIRED] Fetch full manifest via HTTP GET to `offline_metadata_url` or `offline_metadata_path`**
- [ ] **[REQUIRED] On fetch success, replace entire `core`, `metadata`, `credits`, `artwork`, `assets` sections from fetched manifest**
- [ ] **[REQUIRED] Implement artwork selection: prefer `thumbnail`, then `poster`, then `fanart` from `artwork[]` array**
- [ ] Add retry/backoff and fallback behavior for metadata fetch failures (keep showing inline data)
- [ ] Test inline-only vs. inline+fetch scenarios with golden fixtures
- [ ] Verify thumbnails/artwork appear when fetch completes
- [ ] **[REQUIRED] Require HTTPS for download credential exchange endpoints**
- [ ] **[REQUIRED] Enforce no plaintext fallback after pairing is established**
- [ ] **[REQUIRED] Persist per-server identity pin long-term (TOFU model on first successful pair)**
- [ ] **[REQUIRED] Keep protocol event-channel crypto as defense-in-depth only, not primary trust anchor**
- [ ] **[REQUIRED] Implement explicit re-pair workflow when pinned identity changes**
- [ ] **[REQUIRED] Support transparent certificate/key rotation using signed-next-key or overlap pin set**
- [ ] **[REQUIRED] Reconcile completed offline recordings' `watched` and `resume_position_ms` after NG connect/capability negotiation**
- [ ] **[REQUIRED] Reconcile all completed offline recordings before single-recording metadata refresh**
- [ ] **[REQUIRED] Skip new/incomplete downloads during offline playback-state reconciliation**
- [ ] **[REQUIRED] Apply watched OR-style and resume max-position merge rules**

## 13) Exact Client Negotiation Contract (Unified Flow)

This section is normative and intended for direct implementation by client teams.

### 13.1 Unified Flow Rule

- Use one orchestrator for **all** download operations (`initial` and `refresh`).
- Select only one active negotiation lane per attempt:
  - `http` first for refresh (standalone-safe behavior)
  - `command_channel` fallback when HTTP did not return ACK and channel is healthy
- Command channel is an optional fast lane/fallback in connected mode; it must not be required for successful standalone refresh.
- Never run command and HTTP refresh in parallel for the same `correlationId`.

### 13.2 Canonical Request Context

- `operation`: `initial` or `refresh`
- `correlationId`: UUID string (stable across retries for same logical attempt)
- `clientId`: stable client identifier
- `recordingId`: numeric recording/media id
- `sessionToken`: optional for initial, expected for refresh
- `bytesTransferred`: long offset for resume/refresh
- `reason`: recommended `forced_fresh_manifest` for metadata-triggered refresh

### 13.3 Command Channel Refresh (Connected Mode)

Send opcode 228 `DOWNLOAD_REFRESH_REQUEST` payload JSON:

```json
{
  "sessionToken": "<current_session_token>",
  "mediaFileID": "<recording_id_as_string>",
  "bytesTransferred": 1598486013,
  "correlationId": "05336179-4a9c-4429-a91f-fa307ba20e13",
  "clientId": "46:51:42:48:55:43",
  "reason": "forced_fresh_manifest"
}
```

Expected response comes asynchronously on command channel as `CMD_DOWNLOAD_REQUEST` payload:
- `TRANSFER_SESSION_ACK`
- or `TRANSFER_SESSION_ERROR`

If no response within bounded timeout (recommended 2 seconds), fall back to HTTP flow below.
If no response within the client timeout window (current implementation: 45 seconds), treat command refresh as failed and continue fallback policy.

Server compatibility note:
- Canonical client payload should use the fields shown above.
- Current server parser is tolerant and also accepts common aliases for recording/session fields (for backward compatibility), but clients should not depend on alias behavior for new releases.

### 13.4 HTTP Refresh Endpoints (Standalone or Fallback)

- Tokened refresh (preferred when token exists):
  - `POST /api/transfers/{session_token}/refresh`
- Generic refresh (fallback/create):
  - `POST /api/transfers/refresh`

HTTP method note:
- Client contract remains POST-first.
- Current server implementation also accepts GET on refresh endpoints for compatibility paths.
- Client teams should continue sending POST for deterministic behavior and payload consistency.

HTTP body schema (snake_case):

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

Notes:
- Current client may send generic refresh body without `session_token` first, then retry with `session_token` when available.
- Server should tolerate both `recording_id` and `mediaFileID` aliases.

Standalone server support requirement (mandatory):
- Server must implement the HTTP refresh contract above so refresh succeeds without command channel availability.
- Server must return canonical `TRANSFER_SESSION_ACK` or `TRANSFER_ERROR` payloads for these endpoints.
- Returning transport-only placeholders without refresh ACK semantics is non-compliant.

### 13.5 Required ACK Fields

Client must parse and persist these fields from `TRANSFER_SESSION_ACK`:

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
- `offline_inline_level` (`full` or `core`)
- `offline`
- `expires_in_seconds`

Credential field semantics:
- `download_username` and `download_password` are transfer-scoped credentials used only for download transport authentication.
- Client must treat these values as sensitive and avoid logging raw values.
- Refresh ACK may rotate either or both values; client must replace previously cached credential values atomically with the latest ACK.

### 13.6 Token Rotation Rule

- On every successful ACK, replace local token with returned `session_token`.
- Treat prior token as stale immediately.

### 13.7 Two-Step Metadata Rule (Mandatory)

1. Parse and render inline `offline` immediately for first paint.
2. If `offline_metadata_url` or `offline_metadata_path` exists:
   - if `offline_inline_level == core`, fetch full manifest immediately
   - if `offline_inline_level == full`, fetch in background
3. On successful full-manifest fetch, replace full sections (not sticky merge):
   - `core`
   - `metadata`
   - `credits`
   - `artwork`
   - `assets`

Artwork selection priority:
- `thumbnail` -> `poster` -> `fanart` -> placeholder

### 13.7A Offline Playback State Reconciliation (Offline-Capable Clients)

Offline-capable clients must reconcile local playback state with the connected NG server using the control-plane endpoint defined in `OFFLINE_PLAYBACK_SYNC_SERVER_CONTRACT.md`.

Client trigger requirements:
- On NG connection/capability negotiation completion, bulk-sync all completed local recordings.
- On user-initiated metadata refresh for one recording, bulk-sync all completed local recordings before fetching the refreshed manifest.
- On local playback-position save, watched toggle, or Start at Beginning, sync the affected completed recording. The implementation may send this as a one-recording batch to the same bulk endpoint.

Filtering rule:
- Include only completed downloads in playback-state sync payloads.
- Do not include newly requested, queued, preparing, downloading, paused, or failed downloads. A newly requested recording receives authoritative server state through its normal transfer ACK/full-manifest flow instead.

Bulk endpoint:
- `POST /api/offline/playback-state-sync`
- Headers follow existing offline transfer conventions: `X-Transfer-Token`, `X-Correlation-ID`, and `x-ng-client-id` when available.
- Payload contains `schema_version`, `reason`, `updated_at_ms`, optional session/client context, and a `recordings[]` array of `{ media_file_id, resume_position_ms, watched }` entries.

Merge rule:
- Watched is OR-style: if either side has `watched=true`, both sides should converge to `true`.
- Resume position is max-style: the higher `resume_position_ms` wins.
- The client must apply returned server state only when it wins locally: apply `watched=true`; apply `resume_position_ms` only if it is greater than the local value.

Compatibility behavior:
- Until the server implements this endpoint, clients must treat `404`, `405`, and `501` as unsupported and continue normal playback/download behavior.

### 13.8 Error Semantics and Client Actions

Transfer errors are machine-readable JSON:

```json
{
  "type": "TRANSFER_ERROR",
  "error_code": "TRANSFER_TOKEN_INVALID",
  "message": "Transfer token is invalid or expired.",
  "retriable": true
}
```

Client actions:

- `409` with transfer status for queued/paused: expected waiting state, not failure.
- `401 TRANSFER_TOKEN_INVALID` with `retriable=true`: fallback to next lane (or generic refresh when appropriate).
- `401` with `retriable=false` (for example token/path mismatch or client mismatch): do not retry immediately; treat as request-shape or ownership error and surface integration/auth diagnostics.
- `404 TRANSFER_SESSION_NOT_FOUND` with `retriable=true`: try generic refresh using `recording_id`.
- `400` malformed/shape errors: treat as client integration bug; fail fast and log.

### 13.9 Step-by-Step Unified Sequence

1. Build request context (Section 13.2).
2. Attempt tokened HTTP refresh when token exists.
3. If tokened HTTP fails, attempt generic HTTP refresh without token.
4. If generic HTTP fails and token exists, retry generic HTTP refresh with token in body.
5. If HTTP path does not return ACK and command channel is healthy, attempt command refresh.
6. Normalize result to internal union: `ACK | STATUS | ERROR`.
7. Apply token/session updates (including token rotation).
8. Apply two-step metadata update.
9. Route UI by `session_state`:
   - `queued` -> Download Manager queued state
   - `transferring` -> active progress
   - `paused` -> paused state
10. Record telemetry (`lane`, status, `error_code`, `retriable`, token prefix, correlation id).

### 13.10 Compatibility Checklist (Release Gate)

- [ ] Single orchestrator handles initial + refresh flows.
- [ ] Only one lane active per attempt (no parallel duplicate refresh).
- [ ] Uses HTTP snake_case schema exactly.
- [ ] Parses and persists all required ACK fields.
- [ ] Rotates to latest `session_token` every ACK.
- [ ] Implements two-step metadata replacement semantics.
- [ ] Implements offline playback-state reconciliation for completed downloads only.
- [ ] Syncs all completed local recordings after NG connect/capability negotiation.
- [ ] Syncs all completed local recordings before single-recording metadata refresh.
- [ ] Applies watched OR-style and resume max-position merge rules.
- [ ] Treats `409 queued/paused` as wait-state.
- [ ] Implements machine-readable error handling using `error_code` + `retriable`.
- [ ] Preserves `correlationId` across retries for one logical attempt.
- [ ] Handles both retriable and non-retriable `401` outcomes correctly.
- [ ] Server-side standalone HTTP refresh contract is implemented and validated (works when command channel is unavailable).
- [ ] Command channel remains optional fast lane/fallback and is not required for standalone refresh success.
- [ ] Section 13.11 storm-prevention guardrails are implemented client-side and pass release-gate validation.

### 13.10A Alignment Snapshot (2026-06-05)

This subsection records doc-to-code alignment decisions for current branch behavior.

1. Decision: keep Section 13.11 storm-prevention guardrails as **mandatory release requirements**.
2. Decision: refresh lane ordering in this PRD is now HTTP-first to match standalone requirement and current client flow.
3. Decision: keep command-channel refresh as fallback capability, not primary requirement for standalone refresh.
4. Gap: strict single-flight trigger coalescing (`pending=true` replay) is only partially implemented in current code.
5. Gap: jittered backoff windows in Section 13.11.D are not fully implemented as specified.
6. Gap: full storm telemetry field set in Section 13.11.I is not fully implemented yet.
7. Action: do not relax 13.11 requirements; track remaining gaps as implementation work before release gate closure.
8. Decision: standalone HTTP refresh contract support on server is a mandatory dependency for release gate closure.
9. Decision: command channel is retained as optional fast lane/fallback only.

### 13.11 Storm-Prevention Guardrails (Mandatory)

This section is normative and required for any client release that performs `initial` and `refresh` operations.

#### A) Single-Flight per Recording (Hard Requirement)

- Maintain a per-recording single-flight lock keyed by `(recordingId, operation)`.
- While one refresh attempt is active, do not start another refresh attempt for the same key.
- New triggers during an active attempt must coalesce into one pending trigger, not a new network call.

#### B) Lane Mutex (Hard Requirement)

- For a given `correlationId`, only one lane is allowed at a time.
- If command lane is active, HTTP lane is blocked until command timeout/failure.
- If HTTP lane is active, command lane is blocked until HTTP timeout/failure.
- Never send command and HTTP refresh in parallel for the same logical attempt.

Server behavior note:
- Server enforces best-effort per-lane protections (HTTP refresh throttle/backpressure and command refresh coalescing/rate limiting).
- Server does not currently provide a strict global cross-lane mutex keyed by `correlationId`.
- Therefore, lane mutex remains a mandatory client-orchestrator responsibility.

#### C) Cooldown Windows (Hard Requirement)

- After any successful ACK, apply a local cooldown before another refresh for the same recording:
  - Recommended: `min_refresh_interval_ms = 1200`.
- During cooldown, queue one deferred refresh intent if needed; drop additional duplicate intents.

#### D) Retry Policy with Jitter (Hard Requirement)

- Retry only for retriable outcomes (`timeout`, `5xx`, `429`, transfer error with `retriable=true`).
- Exponential backoff with jitter:
  - attempt 1: 400-700 ms
  - attempt 2: 900-1400 ms
  - attempt 3: 1800-2800 ms
  - max attempts per logical refresh: 3
- Do not spin immediate retries on `401/404/409` unless contract says fallback is valid.

#### E) Correlation Discipline (Hard Requirement)

- Generate one `correlationId` per logical refresh operation.
- Reuse the same `correlationId` across lane fallback and retries for that operation.
- Start a new `correlationId` only for a new user/system intent after cooldown.

#### F) Token Hygiene (Hard Requirement)

- Rotate token on every successful ACK.
- Invalidate prior token immediately.
- If response arrives with stale token while newer token is already committed, discard stale response.

#### G) Trigger Coalescing Rules (Hard Requirement)

- Coalesce trigger sources (`metadata_needed`, `resume`, `network_recovered`, `ui_resume`) into one pending operation per recording.
- Recommended behavior:
  - if active attempt exists: set `pending=true`
  - when active attempt completes: if `pending=true` and outside cooldown, run exactly one more attempt

#### H) Server Backpressure Compliance (Hard Requirement)

- On HTTP `429` or `TRANSFER_REFRESH_THROTTLED`, respect backoff policy and do not lane-hop immediately.
- Treat these as expected overload control signals, not fatal errors.

#### I) Required Telemetry

Emit per refresh operation:
- `correlation_id`
- `recording_id`
- `lane_selected`
- `lane_fallback_count`
- `attempt_count`
- `result_type` (`ACK|STATUS|ERROR|TIMEOUT`)
- `error_code` (if any)
- `retriable` (if any)
- `cooldown_suppressed_count`
- `coalesced_trigger_count`

#### J) Reference Client Pseudocode

```javascript
async function refreshRecording(ctx) {
  const key = `${ctx.recordingId}:refresh`;
  if (singleFlight.has(key)) {
    singleFlight.get(key).pending = true;
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
      if (isInCooldown(ctx.recordingId)) break;

      const result = await runUnifiedNegotiation(ctx, op.correlationId); // lane mutex enforced inside
      if (result.type === "ACK") {
        commitToken(result.session_token);
        applyCooldown(ctx.recordingId, 1200);
        await applyTwoStepMetadata(result);
        break;
      }

      if (!isRetriable(result) || op.attempts >= 3) break;
      op.attempts += 1;
      await sleep(backoffWithJitter(op.attempts));
    } while (op.pending);
  } finally {
    singleFlight.delete(key);
  }
}
```

### 13.12 Download Credential Exchange Security and Pairing (Mandatory)

This subsection is currently roadmap material for the follow-up server/client alignment work; it documents the planned client/server download credential exchange contract and is not a client rollback directive.

Scope:
- Applies only to download flow credential exchange where download-specific username/password are created, refreshed, or transmitted.
- Does not impose new transport requirements on unrelated control-plane endpoints outside this credential exchange scope.

#### A) HTTPS Requirement for Download Credential Exchange Endpoints

- All download credential exchange endpoints must use HTTPS transport.
- This includes initial transfer credential issuance and any refresh/rotation endpoint that returns or accepts download-specific username/password material.
- Plain HTTP is non-compliant for these credential exchange endpoints.

Download credential exchange contract:
- Credential issuance/refresh response must carry `download_username` and `download_password` in `TRANSFER_SESSION_ACK`.
- Credential material must be transfer-scoped and replaceable on refresh (no long-lived static credential assumption).

#### B) No Plaintext Fallback After Pairing

- Once a server has been paired and pinned, the client must not fall back to plaintext HTTP for download credential exchange operations.
- On HTTPS failure, fail closed and surface recoverable diagnostics instead of silently downgrading.

#### C) Trust Model: TOFU with Persistent Pinning

- First successful secure pairing uses trust-on-first-use (TOFU).
- Client stores a per-server identity pin (certificate public key hash or cert fingerprint).
- Stored pin is long-lived and must survive app restarts and upgrades.

#### D) Runtime Verification

- For every subsequent download credential exchange operation, verify both:
  - valid HTTPS session
  - pinned server identity match
- If pin verification fails, block download credential exchange immediately.

#### E) Identity Change Handling (Explicit Re-Pair)

- On pin mismatch, client must enter re-pair-required state.
- UX must be explicit and actionable:
  - explain that server identity changed
  - block download credential exchange until user confirms re-pair
  - provide clear cancel/back path without hidden bypass
- Re-pair creates a new trusted pin only after explicit user/admin confirmation.

#### F) Rotation Policy (Transparent Normal Operation)

- Client should support transparent key/cert rotation via one of:
  - overlap trust set (old+new pin during bounded rotation window), or
  - next-key signed by currently trusted key/cert chain.
- Rotation that cannot be cryptographically linked to current trust requires explicit re-pair.

#### G) Defense-in-Depth Rule

- Existing protocol event-channel encryption remains enabled where supported.
- Protocol crypto is secondary defense-in-depth and must not be treated as the primary trust anchor.
- Transport trust for download credential exchange is HTTPS plus pin verification.

#### H) Observability Requirements

Emit security telemetry at minimum:
- `tls_required_enforced`
- `pin_state` (`unpaired|paired|mismatch|repair_pending`)
- `pin_mismatch_block_count`
- `https_downgrade_block_count`
- `rotation_auto_accept_count`
- `rotation_repair_required_count`

### 13.13 Artwork Sidecar Reliability Contract and Triage

This section covers failures when loading thumbnails/posters/person images from transfer sidecar routes.

#### A) Canonical Artwork Sidecar Endpoint

- `GET /api/transfers/{session_token}/offline/artwork/{index}`
- Used after ACK + offline manifest parsing, when `artwork[]` entries resolve to transfer sidecar paths.

#### B) Client Behavior Requirements

- Treat artwork as non-blocking decoration: metadata and download state must remain usable if image loads fail.
- On image failure, render placeholder immediately and retry in background with bounded backoff.
- Respect explicit server statuses:
  - `401/404`: treat as non-retriable for that URL/token; refresh session/manifest path before retrying.
  - `429`: back off and retry later; do not lane-hop immediately.
  - `5xx`/timeout/connect error: retriable with bounded attempts and jitter.
- Correlate artwork attempts with transfer operation telemetry using `correlation_id` when available.

#### C) Server-Side Checks (Send to Server Team)

1. Endpoint latency instrumentation for artwork path
- Add per-request timings for `/api/transfers/{token}/offline/artwork/{index}`:
  - request start
  - first-byte timestamp (TTFB)
  - completion timestamp
  - status code
  - bytes written
  - exception (if any)

2. Token/session validation path health
- Confirm sidecar routes validate the same transfer session/token contract as refresh/content routes.
- Ensure failures are fast and explicit (401/404 JSON for transfer endpoints), not silent hangs.

3. Control-plane thread and lock contention
- Check HTTP worker saturation and queue depth around artwork failures.
- Inspect contention around transfer/session maps and any synchronized blocks.

4. Artwork source IO timing
- Add step timings for:
  - artwork metadata resolve
  - source open (local/remote)
  - stream start
  - stream finish

5. Listener lifecycle correlation (port 31099)
- Correlate client timeout/connect exceptions with server process/listener events:
  - process restart windows
  - listener bind/accept failures
  - socket-level errors

6. Backpressure/error semantics
- Prefer explicit deterministic responses:
  - `429` for throttle/backpressure
  - `5xx` for transient internal failure
  - `401/404` for auth/session mismatch or missing assets

#### D) Minimal Server-Team Ask Snippet

1. "Instrument `/api/transfers/{token}/offline/artwork/{index}` with start/TTFB/end, status, bytes, exception."
2. "Confirm sidecar token/session validation returns fast explicit failure and never blocks/hangs."
3. "Check worker-pool saturation and transfer/session lock contention during artwork fetch spikes."
4. "Correlate failures with connect/timeout windows on port 31099."
