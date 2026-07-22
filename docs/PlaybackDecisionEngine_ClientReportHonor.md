# PlaybackDecisionEngine — Client-Report Honor Model

_Architecture reference. Introduced 2026-07-06._

## Overview

`PlaybackDecisionEngine.evaluate()` decides the playback strategy for a media
row: **DIRECT_PLAY**, **REMUX**, or **TRANSCODE**.

The static `ClientProfile` (from `ClientProfileManager`) is a **guard rail
only**: it can *restrict* but never *grant* a capability. The real honor signal
is the client's **actual reported capabilities**, intersected with the profile.
This matches upstream google/SageTV's conjunctive model — the source container
**and** video codec **and** audio codec must all be reported by the client for
direct play.

## `ClientReportedCaps`

A small holder in `PlaybackDecisionEngine`:

```java
public static final class ClientReportedCaps
{
  public final boolean container;
  public final boolean video;
  public final boolean audio;
}
```

- Passed into the new `evaluate(...)` / `evaluateWithPlayerSwitch(...)`
  overloads.
- Intersection rule (per dimension):

  ```
  containerOK = profile.isContainerAllowed(x) && (caps == null || caps.container);
  videoOK     = profile.isVideoAllowed(x)     && (caps == null || caps.video);
  audioOK     = profile.isAudioAllowed(x)     && (caps == null || caps.audio);
  ```

- The pre-existing signatures remain and delegate with `null` caps for backward
  compatibility.

## Caller: `MiniPlayer`

`MiniPlayer` computes `ClientReportedCaps` from the client's coarse capability
lists before calling the engine:

- **Container** — follows the chosen transport:
  - `isPushTransport ? mcsr.isSupportedPushContainerFormat(x)
    : mcsr.isSupportedPullContainerFormat(x)`
  - Push special case: an `MPEG2-TS` source is acceptable when
    `enable_internal_push_remuxer` (default `true`) **and** the client supports
    `MPEG2-PS` push — i.e. the internal TS→PS remuxer can bridge it.
- **Video** — `null`/empty codec, or `mcsr.isSupportedVideoCodec(mediaVideo)`.
- **Audio** — `null`/empty codec, or `mcsr.isSupportedAudioCodec(mediaAudio)`.

The decision log line includes
`clientReports[container=.. video=.. audio=..]` for diagnostics.

## NG vs. Legacy Clients

- **NG clients** (`ngVersion ≥ 1.0`, `isNgClient=true`): the client's
  self-report **wins on conflict** with the profile. If the client reports a
  codec (e.g. AC-4) that the profile excludes, the client wins and the dimension
  is treated as supported. This is safe because NG clients provide accurate,
  fine-grained capability reports.

  ```
  containerOK = caps.container || profile.isContainerAllowed(x)
  videoOK     = caps.video     || profile.isVideoCodecAllowed(x)
  audioOK     = caps.audio     || profile.isAudioCodecAllowed(x)
  ```

- **Legacy clients** (`isNgClient=false`): the conjunctive (AND) intersection
  — both the profile AND the client must agree for direct play. The profile can
  only restrict, never grant. This guards against legacy clients that may
  over-report.

  ```
  containerOK = profile.isContainerAllowed(x) && (caps == null || caps.container)
  videoOK     = profile.isVideoCodecAllowed(x) && (caps == null || caps.video)
  audioOK     = profile.isAudioCodecAllowed(x) && (caps == null || caps.audio)
  ```

- `MiniPlayer` passes `ngSession` (derived from the NG handshake) as the
  `isNgClient` flag to `evaluateWithPlayerSwitch()`.
- A **transport-force safety net** remains in `MiniPlayer`: for a legacy client
  with a non-`DIRECT_PLAY` decision and `clientDoesPull` set, `clientDoesPull`
  is cleared to force push mode. NG clients are excluded from this fallback.

## `desktop_default` Profile

`desktop_default` uses a **broad, upstream-faithful** container/codec list
(`MP4`, `MKV`, `MATROSKA`, `MPEG2-TS`, `MPEG2-PS`, `QUICKTIME`, `FLASHVIDEO`,
etc.) and is documented as a guard rail — not a hard gate.

**Do not narrow this profile to "fix" a client freeze.** Rely on the
client-report intersection instead. If a client *over-reports* a container it
cannot actually demux (for example, advertising Quicktime in
`PULL_AV_CONTAINERS`), add a **targeted client-quirk override** rather than
crippling the shared profile.

## Windows Client MP4 Freeze — Context

- **Original cause:** the REMUX verdict was advisory only; the actual transport
  used `clientDoesPull`, derived from the static `PULL_AV_CONTAINERS` list,
  which advertised Quicktime.
- **Now:** if the Windows client does **not** report Quicktime pull,
  `containerOK` is `false` → **REMUX** → freeze fixed. If it **does**
  over-report, the client would `DIRECT_PLAY` and could re-freeze — which
  indicates a client over-report to be handled with a client quirk, not a
  profile change.
