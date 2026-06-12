# Offline Playback Sync Server Contract

Status: Draft for implementation

Owner: NG Server + NG Client teams

## 1) Endpoint

- Method: `POST`
- Path: `/api/offline/playback-state-sync`

Headers (when available):
- `X-Transfer-Token`
- `X-Correlation-ID`
- `x-ng-client-id`

## 2) Request Payload

```json
{
  "schema_version": 1,
  "reason": "capability_negotiated|pre_metadata_refresh|local_progress_saved|watched_toggled|start_at_beginning",
  "updated_at_ms": 1717000000000,
  "session_token": "optional",
  "ng_client_id": "optional",
  "recordings": [
    {
      "media_file_id": "12163322",
      "resume_position_ms": 1598486,
      "watched": false
    }
  ]
}
```

Rules:
- `recordings[]` must include completed local downloads only.
- Client must not send newly requested, queued, preparing, downloading, paused, or failed items.
- `schema_version` must be `1`.

## 3) Merge Semantics

For each recording id present on both sides:
- `watched`: OR-style merge (`server || client`).
- `resume_position_ms`: max-style merge (`max(server, client)`).

Authoritative convergence target:
- Both sides converge to merged values after sync.
- Client applies returned server values only when they win by the same rules.

## 4) Response Payload

```json
{
  "type": "OFFLINE_PLAYBACK_SYNC_ACK",
  "schema_version": 1,
  "applied_count": 1,
  "recordings": [
    {
      "media_file_id": "12163322",
      "watched": true,
      "resume_position_ms": 1823000
    }
  ]
}
```

Behavior:
- `recordings[]` returns post-merge authoritative server view for each accepted item.
- Unknown recording ids may be omitted or returned with per-item error metadata.

## 5) Errors

Machine-readable error format:

```json
{
  "type": "TRANSFER_ERROR",
  "error_code": "PLAYBACK_SYNC_INVALID_REQUEST",
  "message": "Invalid playback sync payload.",
  "retriable": false
}
```

Compatibility behavior (client-side requirement):
- If endpoint returns `404`, `405`, or `501`, client treats sync as unsupported and continues normal download/playback behavior.

## 6) Idempotency and Ordering

- Server should treat repeated requests with same values as idempotent.
- Last-write bias is not used; merge semantics always apply (OR/max).
- Requests may arrive out of order; merge semantics must still produce monotonic progress.

## 7) Security and Privacy

- Endpoint follows existing transfer/auth model used by offline transfer control paths.
- Avoid logging raw payloads at info level.
- Treat playback position and watched state as user activity data.

## 8) Server Team Implementation Checklist

- [ ] Implement `POST /api/offline/playback-state-sync` and require `schema_version = 1`.
- [ ] Accept `recordings[]` entries shaped as `{ media_file_id, resume_position_ms, watched }`.
- [ ] Validate payload types and return machine-readable `TRANSFER_ERROR` for malformed requests.
- [ ] Apply merge semantics exactly: `watched = server || client`; `resume_position_ms = max(server, client)`.
- [ ] Return authoritative merged results per recording in `OFFLINE_PLAYBACK_SYNC_ACK.recordings[]`.
- [ ] Make repeated requests idempotent and safe under retries.
- [ ] Ensure out-of-order arrivals still converge correctly under OR/max semantics.
- [ ] Preserve per-item tolerance for unknown or missing server recording ids (omit or return item-level error metadata).
- [ ] Keep auth/headers aligned with offline transfer control conventions (`X-Transfer-Token`, `X-Correlation-ID`, `x-ng-client-id`).
- [ ] Add structured logging and counters: request count, applied count, validation failures, auth failures.
- [ ] Avoid logging raw playback payload values at info level.
- [ ] Validate compatibility behavior expectations with client teams (`404/405/501` treated as unsupported on client side).

### 8.1 Minimum Acceptance Tests

- [ ] Newer client resume beats older server resume.
- [ ] Older client resume does not overwrite newer server resume.
- [ ] Either-side watched=true converges both sides to watched=true.
- [ ] Batched mixed recordings merge independently and return per-recording authoritative values.
- [ ] Duplicate request replay produces stable response and no regression in stored values.
- [ ] Auth failure path returns deterministic machine-readable error.
