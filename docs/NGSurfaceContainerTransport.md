# NG Surface Per-Container Transport Constraints

**Audience:** NG Android client team **and** PWA client team
**Status:** Server-side **implemented and live**. Additive, fail-open, awaiting
client adoption. A client that changes nothing keeps working exactly as today.

## TL;DR

A playback surface can now declare, per container, which **transports**
(`push` / `pull`) that container is playable over — directly on the existing
`PLAYBACK_SURFACE_<id>_CONTAINERS` property, using the same
`TOKEN;key=value` attribute grammar already used by `VIDEO_CODECS`
(`H264;scan=progressive`).

```
PLAYBACK_SURFACE_android_ijk_CONTAINERS=MPEG2-PS;push=true;pull=false,MP4,MATROSKA
```

This lets a surface say **"MPEG2-PS is a push-only container for me"**: the
client can play a program stream when it is **pushed** (libavformat demux), but
**cannot** direct-play a raw program stream from a **pull** (`stv://`) — a pull
`OPEN` of the `.mpg` returns `NON_MEDIA`. Before this field the surface model had
only a flat container list and a flat delivery-mode list, with no way to link the
two, so the server picked the cheapest transport (pull) for a direct-play and
stranded the client on a container it could only receive by push.

## Why this exists

The NG **surface** engine (`PLAYBACK_SURFACES` + `PLAYBACK_SURFACE_<id>_*`) is the
path that actually decides playback for surface-advertising clients. It is a
**separate channel** from the legacy per-player `IJK_CONTAINER_CONSTRAINTS`
push/pull rows — the server does **not** consult the legacy rows for a client that
advertises surfaces. So a surface that listed `MPEG2-PS` in its flat `CONTAINERS`
set was treated as "playable on any transport this surface offers," and because
the ExoPlayer/IJK surfaces advertise `DELIVERY_MODES=push,pull`, the server's
cheapest-first rule chose **pull** for a direct-play — the one transport a program
stream can't be direct-played over.

`MPEG2-PS;push=true;pull=false` is the surface-native equivalent of the legacy
`IJK_CONTAINER_CONSTRAINTS` row. It carries the same intent into the channel the
server actually uses for your client.

## Wire format

`PLAYBACK_SURFACE_<id>_CONTAINERS` is a comma-separated list of container tokens.
Each token MAY carry semicolon-separated transport attributes:

```
CONTAINER[;push=<bool>][;pull=<bool>]
```

- `CONTAINER` — a canonical container name (`MPEG2-PS`, `MPEG2-TS`, `MP4`,
  `MATROSKA`, `AVI`, `MOV`, `FLV`, `WEBM`). Legacy short forms are accepted and
  canonicalized (`MPG`/`MPEG` → `MPEG2-PS`, `TS` → `MPEG2-TS`, `MKV` → `MATROSKA`,
  `QUICKTIME` → `MP4`). **`MPEG1-PS` is accepted as an alias of `MPEG2-PS`** — the
  program-stream container/demuxer is identical, and the MPEG-1-vs-2 distinction
  is carried by the video codec (`MPEG1-VIDEO` vs `MPEG2-VIDEO`), not the
  container. So `MPEG1-PS;push=true;pull=false` folds onto the `MPEG2-PS` entry
  with those transport flags; do **not** rely on a separate `MPEG1-PS` container
  identity server-side.
- `push=<bool>` / `pull=<bool>` — `true` or `false`. An attribute that is
  **present but the other is omitted defaults the omitted one to `true`**
  (`MPEG2-PS;pull=false` means `push=true, pull=false`).

### Fail-open rules (this is why nothing breaks)

- A **bare** container (`MP4`) carries **no** transport constraint and is reachable
  on **either** transport — identical to today. Every current client is unaffected.
- An unrecognized attribute token is ignored with a server-side `WARN`; the
  container itself is still honored.
- A transport attribute on a non-canonical container name is ignored with a `WARN`.
- Constraints apply **only to `DIRECT_PLAY`** (raw source container handed to the
  client). `REMUX` / `AUDIO_TRANSCODE` / `TRANSCODE` feed a **server-produced**
  container, whose transport the source-container constraint does not govern, so
  those decisions are unchanged.

## Server behavior

For a `DIRECT_PLAY` of container `C` on surface `S`:

| `C` declared as | Surface offers | Server picks |
|---|---|---|
| push-only (`push=true;pull=false`) | `push,pull` | **push** |
| push-only | `pull` only | *(surface dropped — no servable transport)* |
| pull-only (`push=false;pull=true`) | `push,pull` | **pull** |
| unrestricted (bare) | `push,pull` | **pull** (cheapest-first, unchanged) |

The cheapest-first order (`pull` → `push` → `hls`) is preserved for every
container that does not explicitly restrict a transport.

## What each client team should do

### Android (ExoPlayer / IJK surfaces)

For the surface routed to **IJKPlayer**, declare program-stream containers as
push-only so the server direct-plays them over push → IJK:

```
PLAYBACK_SURFACE_android_ijk_CONTAINERS=MPEG2-PS;push=true;pull=false,MP4,MATROSKA,MPEG2-TS
```

This is the surface-channel encoding of your existing
`IJK_CONTAINER_CONSTRAINTS: MPEG2-PS;push=true;pull=false`. (If you also want to
name MPEG-1 program streams explicitly you may send
`MPEG1-PS;push=true;pull=false` — the server folds it onto the same `MPEG2-PS`
entry, so listing both is redundant, not additive.) Keep declaring `MPEG2-PS`
**out** of the ExoPlayer surface's `CONTAINERS` (Exo's `PsExtractor`
is unreliable) — the two surfaces are evaluated independently, so the server will
route PS to IJK.

> **Important:** the surface's `CONTAINERS` list is what the NG engine reads —
> **not** `IJK_CONTAINER_CONSTRAINTS`. If `MPEG2-PS` is absent from
> `PLAYBACK_SURFACE_android_ijk_CONTAINERS`, the server cannot direct-play PS on
> that surface at all (it will REMUX/TRANSCODE), regardless of what the legacy row
> says. Add PS to the surface `CONTAINERS`, with `push=true;pull=false`.

### PWA

Only declare a transport attribute on a container your surface genuinely cannot
direct-play over one transport. If `pwa_native` / `pwa_mse` can pull every
container it lists (the common case), **change nothing** — bare container names
remain correct and fail-open. Use `push=…;pull=…` only to encode a real
restriction (e.g. a container you can MSE-append over a push feed but cannot fetch
as a seekable byte range).

## Compatibility & rollout

- **Additive:** older clients that send bare container lists are unchanged.
- **No new round-trip:** the attributes ride inside the existing
  `PLAYBACK_SURFACE_<id>_CONTAINERS` reply. No new `GetProperty` is added.
- **Fail-open everywhere:** malformed or partial input degrades to "unrestricted,"
  never to a hard failure.
- **Verify:** the server logs the parsed surface at decision time
  (`PlaybackSurface[... containerTransports=[MPEG2-PS;push=true;pull=false] ...]`)
  and, when a direct-play has no servable transport, logs
  `surface '<id>' DIRECT_PLAY of <C> has no servable transport (container push=… pull=…, surface delivery=…)`.

## Server-side references (galeforcesage/SageTV-NG)

- `java/sage/client/PlaybackSurfaceSet.java` — `stripContainerAttributes`,
  `parseContainerTransports` (parses the `;push=;pull=` grammar).
- `java/sage/client/PlaybackSurface.java` — `containerAllowsTransport`,
  `isContainerPushOnly`, `isContainerPullOnly`.
- `java/sage/client/PlaybackDecisionEngine.java` — `pickDeliveryModeForDecision`
  (honors the per-container transport for `DIRECT_PLAY`).
- `test/java/sage/client/PlaybackDecisionEngineTest.java` — `pushOnlyContainer_*`,
  `unrestrictedContainer_*` (routing coverage).
