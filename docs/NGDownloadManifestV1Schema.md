# NG Download Manifest v1 Schema (Canonical)

Status: Approved Draft for First Client

Owner: SageTV Server + NG Download Client teams

Contract policy:
- This is the first production contract.
- Use exactly `manifest_version = 1`.
- No legacy aliases.
- No backward-compat branches.
- Presence-driven payload: optional fields are omitted when unavailable.

## 1) Design Goals

- One canonical shape for all recordings.
- Flexible metadata for content variability (movies, sports, news, episodic).
- Canonical credits as role rows (no hard-coded role buckets in protocol).
- Canonical artwork as assets with kind and subject mapping.
- Deterministic ordering for stable diffs and test fixtures.

## 2) Top-Level Shape

```json
{
  "manifest_version": 1,
  "recording_id": "12345",
  "generated_at": "2026-05-29T22:16:43Z",
  "core": { },
  "metadata": { },
  "credits": [ ],
  "artwork": [ ],
  "assets": { }
}
```

## 3) Field Definitions

### 3.1 Required Top-Level Fields

- `manifest_version` number
  - Must be `1`.
- `recording_id` string
  - Server recording id as string for cross-platform parser safety.
- `generated_at` string
  - RFC3339 UTC timestamp.
- `core` object
- `metadata` object
- `credits` array
- `artwork` array
- `assets` object

### 3.2 `core` Object

Required fields:
- `title` string
- `description` string (can be empty if truly unknown)
- `runtime_ms` number (0 if unknown)

Optional fields (omit when unknown):
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

### 3.3 `metadata` Object (Flexible, Presence-Driven)

Rules:
- Keys appear only when value exists.
- Unknown keys are allowed; clients must render unknown keys generically.
- Values should be primitive (`string|number|boolean`) or arrays of strings.

Recommended keys:
- `rated` string
- `parental_rating` string
- `year` string
- `language` string
- `categories` string[]
- `expanded_ratings` string[]
- `audio_format_summary` string
- `hdtv` boolean
- `surround` boolean
- `cc` boolean
- `subtitles_available` boolean
- `favorite` boolean
- `manual` boolean
- `epg_scheduled` boolean
- `watched` boolean
- `resume_position_ms` number

### 3.4 `credits` Array (Canonical People Data)

Each entry:
- `person_id` string
  - Example: `P1234`.
- `person_name` string
- `role_code` number
  - Matches Sage role byte from `Show`.
- `role_name` string
  - Human-readable role name.
- `image_url` string (optional)

Rules:
- One row per person-role association.
- No role-specific top-level fields in protocol.
- Correspondent appears naturally via role code/name when present.

### 3.5 `artwork` Array (Canonical Image Assets)

Each entry:
- `kind` string
  - Allowed: `poster`, `fanart`, `banner`, `thumbnail`, `person`, `other`.
- `url` string
- `subject_id` string (optional)
  - Null/omitted for show-level images; person id for person artwork.
- `mime_type` string (optional)
- `width` number (optional)
- `height` number (optional)

Rules:
- Omit assets not found.
- `thumbnail` is preferred UI preview target when present.
- Person artwork should use `kind = person` with `subject_id = person_id`.

### 3.6 `assets` Object

Required fields:
- `video` object
  - `recording_files` string[]
  - `recording_file_size` number

Optional fields:
- `captions` array of objects
  - Each with `kind`, `format`, `language`, and either `url` or `local_file_name` (depending on transfer mode).
- `comskip` object
- `transcript` object

### 3.7 Transfer Envelope Fields (ACK/Status/Control)

These fields are outside the manifest object and are carried in transfer session
payloads such as `TRANSFER_SESSION_ACK`, `TRANSFER_STATUS`, and control ACKs.

- `offline_metadata_url` string (recommended)
  - Absolute URL for the full canonical v1 manifest for this session.
- `offline_metadata_path` string (recommended)
  - Relative path equivalent of `offline_metadata_url`.
- `offline_inline_level` string (ACK only)
  - `full`: inline `offline` is full v1 manifest.
  - `core`: inline `offline` is reduced payload for command-size safety.

Client rules:
- Treat `offline_metadata_url`/`offline_metadata_path` as the authoritative source
  for full metadata/artwork.
- Treat inline `offline` as immediate preview data for first paint.
- If both are present, fetch and merge/replace with fetched full manifest.
- If fetch fails, continue rendering from inline `offline` without blocking download.

## 4) Serialization Rules

- UTF-8 JSON.
- Deterministic key order:
  1. `manifest_version`
  2. `recording_id`
  3. `generated_at`
  4. `core`
  5. `metadata`
  6. `credits`
  7. `artwork`
  8. `assets`
- Deterministic array ordering:
  - `credits`: stable source order, tie-break by `person_name`, then `role_code`.
  - `artwork`: `thumbnail`, `poster`, `fanart`, `banner`, `person`, `other`; then lexical URL.
- Omit nulls.
- Omit empty strings for optional fields.

## 5) Example Manifest (v1)

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
    "watched": false,
    "resume_position_ms": 0,
    "favorite": false,
    "manual": false,
    "epg_scheduled": true
  },
  "credits": [
    {
      "person_id": "P1001",
      "person_name": "Rob Riggle",
      "role_code": 15,
      "role_name": "Host",
      "image_url": "https://.../person/1001.jpg"
    },
    {
      "person_id": "P1002",
      "person_name": "Jeannie Mai",
      "role_code": 23,
      "role_name": "Correspondent",
      "image_url": "https://.../person/1002.jpg"
    }
  ],
  "artwork": [
    {
      "kind": "thumbnail",
      "url": "https://.../thumb.jpg",
      "mime_type": "image/jpeg"
    },
    {
      "kind": "poster",
      "url": "https://.../poster.jpg",
      "mime_type": "image/jpeg"
    },
    {
      "kind": "person",
      "subject_id": "P1001",
      "url": "https://.../person/1001.jpg",
      "mime_type": "image/jpeg"
    }
  ],
  "assets": {
    "video": {
      "recording_files": ["Holey.Moley.S02E06.ts"],
      "recording_file_size": 4123456789
    },
    "captions": [
      {
        "kind": "cc",
        "format": "srt",
        "language": "eng",
        "local_file_name": "Holey.Moley.S02E06.cc.srt"
      }
    ]
  }
}
```

## 6) Client Requirements

- Treat `credits` and `metadata` as authoritative.
- Do not require any specific role to exist.
- Group credits by `role_name` at render time.
- Preferred preview image order: `thumbnail`, then `poster`, then first available.
- Render unknown metadata keys under "More Details".
- For transfer session flows, always fetch full manifest from
  `offline_metadata_url` (or `offline_metadata_path`) when supplied.

## 7) Non-Goals

- No role-specific fixed protocol fields (`cast`, `director`, `correspondents`) at top-level.
- No compatibility aliases.
- No mixed schema modes.

## 8) Acceptance Criteria

- All manifests validate against this schema contract.
- Sparse content still renders without errors.
- Correspondent appears when source data contains that role.
- Empty optional keys are omitted.
- Deterministic JSON output across runs for unchanged source data.
