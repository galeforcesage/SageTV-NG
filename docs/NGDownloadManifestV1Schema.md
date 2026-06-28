# NG Download Manifest v1 Schema (Canonical)

Status: Canonical V1 design for first production client/server implementation.

Owner: SageTV Server + NG Download Client teams

## 1. Contract Policy

- This is the first production NG download-manifest contract.
- Use exactly `manifest_version = 1`.
- There are no legacy aliases and no backward-compatibility branches in V1.
- The server must emit one canonical shape.
- The client must parse that canonical shape.
- Optional fields are presence-driven: omit unavailable values instead of sending `null`, empty strings, or placeholder zeros.
- Unknown keys inside `metadata` and `assets` are allowed so the server can add non-breaking descriptive data later.

### Why No Backward Compatibility In V1

This contract is being finalized before broad production release. Carrying old draft shapes forward would make both sides harder to reason about forever: multiple title locations, multiple artwork locations, duplicate video-file metadata, and role-specific people buckets. V1 should ship clean.

The running client work proved the useful behaviors: flexible metadata rendering, dynamic credit grouping, sidecar fetches, and full-manifest replacement. This document keeps those battle-tested behaviors but makes the wire shape cleaner and single-source.

## 2. Design Goals

- One canonical shape for all recordings.
- Keep durable identity/display fields in `core`, not scattered across top-level and `metadata`.
- Keep flexible details in `metadata`; unknown keys render generically.
- Keep mutable playback state in `playback`, not mixed into descriptive metadata.
- Represent people as canonical role rows in `credits`, not hard-coded protocol buckets such as `cast`, `director`, or `correspondents`.
- Represent presentation imagery as `assets.images`, because the client splits and downloads artwork as companion metadata assets.
- Represent other downloadable or functional sidecars under `assets` as well.
- Keep transfer/session fields outside the manifest object.
- Use deterministic ordering for stable diffs, cache hashes, tests, and server diagnostics.

## 3. Top-Level Shape

```json
{
  "manifest_version": 1,
  "recording_id": "12345",
  "generated_at": "2026-05-29T22:16:43Z",
  "core": { },
  "metadata": { },
  "playback": { },
  "credits": [ ],
  "assets": { }
}
```

### Required Top-Level Fields

- `manifest_version` number
  - Must be exactly `1`.
- `recording_id` string
  - SageTV server recording/media-file id as a string for cross-platform parser safety.
- `generated_at` string
  - RFC3339 UTC timestamp for the manifest generation time.
- `core` object
- `metadata` object
- `playback` object
- `credits` array
- `assets` object

## 4. `core` Object

`core` contains stable identity and first-paint display data. These fields should not be duplicated in `metadata` unless there is a separate, display-specific reason.

### Required Fields

- `title` string
- `description` string
  - Can be empty only if truly unknown.
- `runtime_ms` number
  - Use `0` only if truly unknown.

### Optional Fields

Omit when unknown.

- `subtitle` string
- `show_title` string
- `season_number` number
- `episode_number` number
- `original_air_date` string (`YYYY-MM-DD`)
- `first_run` boolean
- `channel` string
- `station` string
- `network` string
- `station_id` number
- `show_id` string
- `recording_start_ms` number
- `recording_end_ms` number
- `recording_start_utc` string
- `recording_end_utc` string

## 5. `metadata` Object

`metadata` is a flexible, presence-driven details bag for rendering and filtering. It should contain fields that are descriptive but not part of the manifest's core identity.

### Rules

- Keys appear only when the value exists.
- Unknown keys are allowed.
- Clients render unknown keys generically under "More Details".
- Values should be primitive (`string`, `number`, `boolean`) or arrays of strings.
- Avoid duplicating values already present in `core`, except for current first-client display fields listed below.
- Do not put playback state in `metadata`; use `playback`.

### Recommended Keys

- `rated` string
- `parental_rating` string
- `year` string
- `language` string
- `categories` string[]
- `expanded_ratings` string[]
- `audio_format_summary` string
- `format` string
- `file_properties` string or object
- `recording_files` string[]
- `recording_file_size` number
- `hdtv` boolean
- `surround` boolean
- `cc` boolean
- `subtitles_available` boolean
- `favorite` boolean
- `manual` boolean
- `epg_scheduled` boolean

`recording_files` and `recording_file_size` are also present in `assets.video`. They are intentionally allowed in `metadata` for the first production client because the current detail UI renders file facts from metadata. `assets.video` remains the canonical media-asset location; these metadata keys are display mirrors.

### Client Rendering Order

Clients should render known fields first, then render remaining metadata keys alphabetically as "More Details".

Preferred known-order fields:

1. `categories`
2. `original_air_date` from `core`
3. `rated`
4. `season_number` from `core`
5. `episode_number` from `core`
6. `show_id` from `core`
7. `channel` / `station` / `network` from `core`
8. `audio_format_summary`
9. `first_run` from `core`
10. `runtime_ms` from `core`

## 6. `playback` Object

`playback` contains the server's last known playback state at manifest generation time. It gives the client an initial watched/resume state before any later active synchronization.

### Fields

- `resume_position_ms` number
  - Last known watched position in milliseconds.
  - Use `0` when there is no resume position.
- `watched` boolean
  - `true` when the server considers the recording watched.
  - `false` when known to be unwatched.

### Rules

- `playback` is required, but individual fields are presence-driven if the server truly cannot determine them.
- Do not duplicate `resume_position_ms` or `watched` in `metadata`.
- The active watched/resume synchronization API remains outside the manifest. The manifest provides the initial snapshot only; later changes flow through playback-state sync.

## 7. `credits` Array

`credits` is the authoritative people model. One row represents one person-role association.

### Each Entry

- `person_id` string
  - Stable person id when available, e.g. `P1234`.
- `person_name` string
  - Required for display.
- `role_code` number
  - Sage role byte from `Show` when available.
- `role_name` string
  - Human-readable role name; required for grouping.
- `image_url` string optional

### Rules

- One row per person-role association.
- Do not send role-specific top-level fields such as `cast`, `director`, `writer`, `anchor`, or `correspondents`.
- Correspondent, anchor, host, director, writer, actor, guest, and any future role appear naturally through `role_code` and `role_name`.
- Clients group credits by `role_name` at render time.
- Within each role group, clients sort names alphabetically for stable display.

## 8. `assets.images` Array

`assets.images` contains presentation images used by library rows, detail pages, hero images, and people avatars. This is intentionally under `assets` because the running client treats artwork as companion metadata: it parses the manifest, extracts `assets.images` into `artwork.json`, and downloads those image files into the recording's companion directory.

### Each Entry

- `kind` string
  - Allowed: `thumbnail`, `poster`, `fanart`, `banner`, `person`, `other`.
- `url` string
- `subject_id` string optional
  - Omit for show-level artwork.
  - Use `person_id` for person artwork.
- `mime_type` string optional
- `width` number optional
- `height` number optional

### Rules

- Omit artwork entries that are not found.
- Prefer at least one `thumbnail` or `poster` for a polished offline library experience.
- Preferred preview image order is `thumbnail`, then `poster`, then `fanart`, then first available image.
- Person artwork should use `kind = person` with `subject_id = person_id`.
- Do not emit a top-level `artwork` array in V1; use `assets.images` as the one canonical artwork location.

### Local File Mapping

When the client fetches companion images, it may store them using this stable mapping:

- `thumbnail` -> `thumbnail.jpg`
- `poster` -> `poster.jpg`
- `fanart` -> `fanart.jpg`
- `banner` -> `banner.jpg`
- `person` -> `cast/<subject_id>.jpg`

## 9. `assets` Object

`assets` contains downloadable media facts and companion metadata sidecars, including artwork images. The client persists the sub-blocks from this object into companion files (`artwork.json`, `captions.json`, `comskip.json`, `transcript.json`) and fetches referenced URLs.

### Required Fields

- `video` object
  - `recording_files` string[]
  - `recording_file_size` number
- `images` array
  - Canonical artwork/image descriptors as defined in section 8.

### Optional Fields

- `captions` array of objects
- `comskip` object or array
- `transcript` object or array

### `assets.video`

```json
{
  "recording_files": ["Holey.Moley.S02E06.ts"],
  "recording_file_size": 4123456789
}
```

Additional video fields are allowed when useful, for example:

- `container` string
- `video_codec` string
- `audio_codec` string
- `duration_ms` number
- `checksum` string

### `assets.captions`

Each caption entry should include:

- `kind` string, e.g. `cc`, `subtitle`, `forced`
- `format` string, e.g. `srt`, `vtt`
- `language` string, e.g. `eng`
- `url` string for server-hosted sidecar fetches
- `local_file_name` string optional when the transfer mode already supplies a local sidecar name

### `assets.comskip` and `assets.transcript`

These may be a single object or an array of objects. Clients recursively collect `url` fields from these nodes for sidecar fetching.

Common fields:

- `format` string
- `language` string optional
- `url` string for server-hosted sidecar fetches
- `local_file_name` string optional when the transfer mode already supplies a local sidecar name

## 10. Transfer Envelope Fields

These fields are outside the manifest object and are carried in transfer-session payloads such as `TRANSFER_SESSION_ACK`, `TRANSFER_STATUS`, and control ACKs.

- `offline_metadata_url` string recommended
  - Absolute URL for the full canonical v1 manifest for this session.
- `offline_metadata_path` string recommended
  - Relative path equivalent of `offline_metadata_url`.
- `offline_inline_level` string ACK only
  - `full`: inline `offline` is a full v1 manifest.
  - `core`: inline `offline` is reduced first-paint data and the full manifest must be fetched urgently.

### Client Rules

- Treat inline `offline` as first-paint data.
- If `offline_metadata_url` or `offline_metadata_path` is present, fetch the full manifest.
- A fetched full manifest with `manifest_version = 1` replaces the entire local offline snapshot.
- If fetch fails, continue rendering from inline `offline` without blocking the download.
- For `offline_inline_level = core`, full-manifest fetch is urgent because credits/artwork/sidecars may be absent from inline data.
- For `offline_inline_level = full`, background fetch is still allowed for consistency and refresh.

## 11. Serialization Rules

- UTF-8 JSON.
- Deterministic top-level key order:
  1. `manifest_version`
  2. `recording_id`
  3. `generated_at`
  4. `core`
  5. `metadata`
  6. `playback`
  7. `credits`
  8. `assets`
- Deterministic array ordering:
  - `credits`: stable source order, tie-break by `person_name`, then `role_code`.
  - `assets.images`: `thumbnail`, `poster`, `fanart`, `banner`, `person`, `other`; then lexical URL.
- Omit nulls.
- Omit empty strings for optional fields.
- Omit optional empty arrays/objects unless the field is required by this contract.

## 12. Example Manifest

```json
{
  "manifest_version": 1,
  "recording_id": "98765",
  "generated_at": "2026-05-29T22:16:43Z",
  "core": {
    "title": "Holey Moley",
    "show_title": "Holey Moley",
    "subtitle": "Episode 6",
    "description": "Mini-golf competition show.",
    "runtime_ms": 2580000,
    "season_number": 2,
    "episode_number": 6,
    "original_air_date": "2020-07-16",
    "first_run": false,
    "channel": "7",
    "station": "ABC",
    "network": "ABC",
    "station_id": 12345,
    "show_id": "EP012345670006"
  },
  "metadata": {
    "rated": "TV-PG",
    "parental_rating": "TVPG",
    "categories": ["Game Show", "Competition"],
    "audio_format_summary": "AAC 2.0",
    "hdtv": true,
    "surround": false,
    "cc": true,
    "subtitles_available": true,
    "favorite": false,
    "manual": false,
    "epg_scheduled": true,
    "recording_files": ["Holey.Moley.S02E06.ts"],
    "recording_file_size": 4123456789
  },
  "playback": {
    "resume_position_ms": 0,
    "watched": false
  },
  "credits": [
    {
      "person_id": "P1001",
      "person_name": "Rob Riggle",
      "role_code": 15,
      "role_name": "Host",
      "image_url": "https://example.invalid/person/1001.jpg"
    },
    {
      "person_id": "P1002",
      "person_name": "Jeannie Mai",
      "role_code": 23,
      "role_name": "Correspondent",
      "image_url": "https://example.invalid/person/1002.jpg"
    }
  ],
  "assets": {
    "video": {
      "recording_files": ["Holey.Moley.S02E06.ts"],
      "recording_file_size": 4123456789
    },
    "images": [
      {
        "kind": "thumbnail",
        "url": "https://example.invalid/thumb.jpg",
        "mime_type": "image/jpeg"
      },
      {
        "kind": "poster",
        "url": "https://example.invalid/poster.jpg",
        "mime_type": "image/jpeg"
      },
      {
        "kind": "person",
        "subject_id": "P1001",
        "url": "https://example.invalid/person/1001.jpg",
        "mime_type": "image/jpeg"
      }
    ],
    "captions": [
      {
        "kind": "cc",
        "format": "srt",
        "language": "eng",
        "url": "https://example.invalid/sidecars/Holey.Moley.S02E06.cc.srt",
        "local_file_name": "Holey.Moley.S02E06.cc.srt"
      }
    ],
    "comskip": {
      "format": "edl",
      "url": "https://example.invalid/sidecars/Holey.Moley.S02E06.edl",
      "local_file_name": "Holey.Moley.S02E06.edl"
    },
    "transcript": {
      "format": "vtt",
      "language": "eng",
      "url": "https://example.invalid/sidecars/Holey.Moley.S02E06.transcript.vtt",
      "local_file_name": "Holey.Moley.S02E06.transcript.vtt"
    }
  }
}
```

## 13. Client Requirements

- Reject manifest if `manifest_version` is missing or not `1`.
- Require `core.title` for display.
- Treat `core`, `metadata`, `playback`, `credits`, and `assets` as the complete canonical snapshot.
- Apply initial watched/resume state from `playback`, then use the playback-state sync API for later changes.
- Treat fetched full manifests as authoritative replacement snapshots.
- Group credits by `role_name` dynamically.
- Do not require any specific role to exist.
- Hide empty UI sections.
- Prefer preview image order: `thumbnail`, then `poster`, then `fanart`, then first available.
- Render unknown metadata keys under "More Details".
- Fetch sidecar assets referenced by `assets.images`, `assets.captions`, `assets.comskip`, and `assets.transcript` when companion storage is available.
- Log parse failures as `manifest_parse_failure` with a reason.
- Log missing recommended fields such as `thumbnail` and `rated` as warnings, not fatal errors.

## 14. Server Requirements

- Emit the canonical V1 shape only.
- Do not emit top-level `title`, `subtitle`, `runtime_ms`, or `description`; use `core`.
- Do not emit top-level `artwork`; use `assets.images`.
- Do not emit watched/resume state in `metadata`; use `playback`.
- Do not emit credit aliases such as `name` or `role`; use `person_name` and `role_name`.
- Do not emit person-artwork aliases such as `person_id` inside `assets.images`; use `subject_id`.
- Emit `recording_files` and `recording_file_size` in `assets.video`.
- Also mirror `recording_files` and `recording_file_size` into `metadata` for the first production client so current detail screens can render file facts without a client parser update.
- Continue watched/resume updates through the playback-state sync API; the manifest `playback` object is the initial snapshot at generation time.
- Include `offline_metadata_url` or `offline_metadata_path` in transfer ACKs whenever inline metadata may be incomplete.
- For inline `offline_inline_level = core`, include enough `core` data for first paint and make the full manifest endpoint immediately fetchable.

## 15. Non-Goals

- No role-specific fixed protocol fields (`cast`, `director`, `correspondents`) at top level.
- No compatibility aliases.
- No mixed schema modes.
- No client-side interpretation of missing fields as empty strings unless explicitly allowed.
- No transfer/session fields inside the manifest object.
- No active watched/resume synchronization inside the manifest object.

## 16. Acceptance Criteria

- Server output validates against this schema contract.
- Sparse content renders without errors.
- `core.title` appears for every manifest.
- `metadata` unknown keys render under "More Details".
- Credits group by `role_name`, including correspondent/anchor/host roles when present.
- Artwork preview chooses `thumbnail`, then `poster`, then `fanart`, then first available.
- Initial watched and resume state apply from `playback`.
- Companion sidecars referenced by `assets` can be fetched without blocking the primary download.
- Empty optional keys are omitted.
- Deterministic JSON output is stable across runs for unchanged source data.
