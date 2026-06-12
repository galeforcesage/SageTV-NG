# NG Bandwidth Adjustment: Client Handoff

Audience: NG client team

Purpose: This is the bandwidth-adjustment-only contract for the NG client. Share this when the client team is already aligned on the other docs and only needs the adaptive bandwidth feedback piece.

## 1. Scope

This document covers only the NG client to server bandwidth feedback path used to improve startup and live adaptive bitrate decisions.

It does not redefine:

- download flow
- offline manifest behavior
- playback-state sync
- generic capability negotiation outside bandwidth feedback

## 2. V1 Rules

- This is still V1.
- Automatic tuning between client and server is allowed.
- End-user tuning is not allowed.
- Do not add a settings toggle, developer preference, or user-visible option for bandwidth adjustment behavior in V1.
- If behavior changes before release, server and client must change together under the same V1 contract.
- Do not introduce a V2 split for this before release.

## 3. What The Client Must Implement

V1 is already active in the running server:

1. **Required (server is polling now)**
   - Advertise `BANDWIDTH_FEEDBACK_V1` in `SAGETV_NG_CAPABILITIES` (server checks this)
   - Implement `GetProperty SAGETV_NG_BANDWIDTH_FEEDBACK_V1` response with at least `kbps=<value>;seq=<counter>`
   - The server calls `pollNgBandwidthFeedbackKbps()` at startup and `pollNgBandwidthFeedbackPayload()` during live playback

2. **Recommended (future-ready, same V1 contract)**
   - Include network/device-health keys (`net_type`, `thermal`, `power_mode`, `battery`, etc.) when available
   - These optimize server decisions but are optional in V1

## 4. Negotiation Contract

### 4.1 Capability Token

The client must advertise this capability token in `SAGETV_NG_CAPABILITIES`:

- `BANDWIDTH_FEEDBACK_V1`

<remove reason="consolidating to single canonical token">

The alias `BW_FEEDBACK_V1` is removed. Use `BANDWIDTH_FEEDBACK_V1` exclusively.

</remove>

### 4.2 Server Capability Advertisement

The server advertises (active in deployed code):

- `SAGETV_NG_SERVER=1` ✓ confirmed running
- `SAGETV_NG_SERVER_CAPS=BANDWIDTH_FEEDBACK_V1` (check logs for this; may be gated by diagnostics setting)

<add reason="static device capabilities moved here from per-sample feedback payload — report once at connect time, not every sample">

### 4.3 Static Device Capability Properties

The client should set these properties once during connection setup (alongside `SAGETV_NG_CAPABILITIES`). These are device-level facts that do not change during a session:

- `SAGETV_NG_DISPLAY_HZ` (integer) — display refresh rate (60, 90, 120)
- `SAGETV_NG_DEVICE_TYPE` (enum: `phone`, `tablet`, `tv`, `watch`, `auto`, `other`)
- `SAGETV_NG_VIDEO_DECODER` (enum: `hardware`, `software`, `limited`)
- `SAGETV_NG_DISPLAY_RES` (string, e.g. `1920x1080`) — native display resolution
- `SAGETV_NG_HDR_SUPPORT` (enum: `none`, `hdr10`, `hdr10plus`, `dolby_vision`, `hlg`)

These inform the server's initial transcode profile selection without polluting the per-sample bandwidth feedback payload.

</add>

## 5. Property Contract

### 5.1 Property Name

When the server wants a feedback sample, it calls:

- `GetProperty SAGETV_NG_BANDWIDTH_FEEDBACK_V1`

The client must return a string payload. This single property carries bandwidth metrics plus device health signals all together.

**Implementation status (ACTIVE in deployed server):**

- Server DOES poll this property at startup and during playback
- Polling is gated by: `miniplayer/ng_bandwidth_feedback_enabled` (default: true)
- Method call: `pollNgBandwidthFeedbackKbps()` at startup, `pollNgBandwidthFeedbackPayload()` during playback
- **Clients must implement this now** — the server is actively polling

### 5.2 Allowed Payload Shapes

The server accepts either:

1. Plain integer kbps

```text
18400
```

2. Key/value payload (semicolon-delimited or JSON)

```text
kbps=18400;seq=42
```

```text
kbps=18400;seq=42;buffer_ms=18000;rebuffer_count_30s=0;thermal=nominal;net_type=wifi;wifi_rssi=-55;battery=85;battery_state=discharging;power_mode=normal
```

```text
{"kbps":18400,"seq":42,"buffer_ms":18000,"rebuffer_count_30s":0,"thermal":"nominal","net_type":"wifi","wifi_rssi":-55,"battery":85,"battery_state":"discharging","power_mode":"normal"}
```

### 5.3 Bandwidth Keys

Required:

- `kbps` — estimated available bandwidth in kilobits per second

<remove reason="server consolidating on canonical key only">

The following aliases are removed. Use `kbps` exclusively:

- `bw_kbps`
- `bandwidth_kbps`
- `est_kbps`

</remove>

Sequence tracking:

- `seq` — monotonically increasing integer per sample update (required)

<remove reason="server consolidating on canonical key only">

The following alias is removed. Use `seq` exclusively:

- `sequence`

</remove>

### 5.4 QoE Guardrail Keys (Optional, Future-Ready)

Network and buffer health:

- `rebuffer_count_30s` (integer) — rebuffer events in the last 30 seconds
- `buffer_ms` (integer) — current player buffer depth in milliseconds
- `packet_loss_pct` (float 0–100) — measured packet loss percentage
- `rtt_ms` (integer) — round-trip latency to server in milliseconds

<add reason="server uses these for proactive bitrate decisions before rebuffer occurs">

Playback stall indicators:

- `stall_count_60s` (integer) — total playback stalls in the last 60 seconds (broader window than rebuffer_count_30s)
- `dropped_frames_30s` (integer) — video frames dropped by the decoder in the last 30 seconds
- `decoder_latency_ms` (integer) — average decode-to-render latency in milliseconds (helps detect decoder overload)

</add>

### 5.5 Network Condition Keys (Optional, Future-Ready)

<add reason="server strategy now adjusts bitrate and transcode profile based on connection type and signal quality">

Connection type and quality:

- `net_type` (enum: `wifi`, `wifi_5g`, `wifi_6e`, `cellular`, `cellular_5g`, `cellular_4g`, `cellular_3g`, `ethernet`, `vpn`, `unknown`)
  - Server uses this to set floor/ceiling bandwidth assumptions
  - `wifi` is the generic fallback; prefer specific generation when available
  - `cellular_3g` triggers aggressive bitrate capping regardless of reported kbps
  - `vpn` signals potential latency/overhead — server applies additional safety margin
  - `ethernet` signals no wireless variability — server trusts kbps more directly

- `wifi_rssi` (integer, dBm, e.g. -55) — WiFi received signal strength
  - Only meaningful when `net_type` is `wifi*`
  - Server interprets: > -50 excellent, -50 to -65 good, -65 to -75 fair, < -75 poor
  - Poor signal triggers preemptive bitrate reduction even if kbps is currently high

- `wifi_freq_mhz` (integer, e.g. 5180) — WiFi channel frequency
  - Helps server distinguish 2.4 GHz (congestion-prone) vs 5 GHz vs 6 GHz

- `wifi_link_speed_mbps` (integer) — WiFi link speed reported by OS
  - Upper bound on throughput; server uses as ceiling for kbps credibility check

- `cellular_signal_dbm` (integer, dBm) — cellular signal strength
  - Only meaningful when `net_type` is `cellular*`

- `net_metered` (boolean) — whether the connection is metered/capped
  - When `true`, server may prefer lower bitrate to conserve data usage

</add>

### 5.6 Device Health Keys (Optional, Future-Ready)

Thermal state:

- `thermal` (enum: `nominal`, `fair`, `serious`, `critical`)
  - `nominal`: Device well within thermal envelope, no constraints
  - `fair`: Approaching thermal limits but still healthy
  - `serious`: High thermal load — server will reduce bitrate to lower decode cost
  - `critical`: Thermal throttling active or imminent — server will drop to minimum viable bitrate

<add reason="overheating is the #1 cause of client-initiated playback failure on mobile; server needs granular signal">

- `thermal_trend` (enum: `stable`, `rising`, `falling`)
  - `rising` + `serious` triggers earlier server intervention than `serious` alone
  - `falling` + `fair` tells server recovery is in progress, hold current bitrate

- `skin_temp_c` (float) — device skin/case temperature in Celsius when available (Android 13+ ThermalService)
  - Provides absolute reference vs the enum-only `thermal` field

</add>

CPU state:

- `cpu` (enum: `nominal`, `fair`, `serious`, `critical`)
  - `nominal`: CPU headroom available
  - `fair`: CPU moderately busy but handling playback
  - `serious`: CPU at significant load, server may reduce bitrate
  - `critical`: CPU severely constrained, server will reduce to minimum

Memory and system pressure:

- `memory` (enum: `nominal`, `fair`, `serious`, `critical`)
  - `nominal`: Abundant free memory
  - `fair`: Reasonable memory available
  - `serious`: Low free memory, potential for swap/slowdown
  - `critical`: Memory pressure severe, risk of app termination by OS

Battery and power state:

- `battery` (integer 0–100) — percentage charge remaining
- `battery_state` (enum: `charging`, `discharging`, `full`, `unknown`)

<add reason="server needs to know low-power/battery-saver mode to avoid pushing high-bitrate streams that the OS will throttle anyway">

- `power_mode` (enum: `normal`, `battery_saver`, `ultra_saver`, `performance`, `unknown`)
  - `normal`: No power restrictions
  - `battery_saver`: OS battery saver active — CPU/GPU frequency capped, background restricted
    - Server will reduce target bitrate by configurable factor (default 0.6x)
    - Server will avoid triggering transcode profile changes that increase decode complexity
  - `ultra_saver`: Aggressive power saving — server drops to audio-only or minimum video
  - `performance`: Device in performance mode (gaming phones, etc.) — server can push higher bitrate
  - `unknown`: Client cannot determine power mode

- `battery_temp_c` (float) — battery temperature in Celsius
  - Supplements `thermal` — some devices report battery temp but not skin temp
  - Server uses max(skin_temp, battery_temp) logic for thermal decisions

- `charging_wattage` (integer) — current charging wattage when `battery_state=charging`
  - Low wattage (< 5W) + `discharging` behavior means net drain — server treats as `battery_saver`

</add>

<remove reason="these are static device capabilities, moved to one-time capability advertisement in Section 4">

The following keys are removed from the per-sample feedback payload. Report them once during capability negotiation instead:

- `display_hz`
- `device_type`
- `video_decoder`

</remove>

### 5.7 Preferred Client Payload for V1

Minimal (backward compatible):

```text
kbps=18400;seq=42
```

Recommended with QoE + network:

```text
kbps=18400;seq=42;buffer_ms=18000;rebuffer_count_30s=0;net_type=wifi;wifi_rssi=-52
```

Recommended full payload (most useful for server decisions):

```text
kbps=18400;seq=42;buffer_ms=18000;rebuffer_count_30s=0;stall_count_60s=0;dropped_frames_30s=2;net_type=wifi_5g;wifi_rssi=-48;wifi_link_speed_mbps=866;thermal=nominal;thermal_trend=stable;cpu=nominal;memory=fair;battery=85;battery_state=discharging;power_mode=normal
```

Full JSON format alternative:

```json
{
  "kbps": 18400,
  "seq": 42,
  "buffer_ms": 18000,
  "rebuffer_count_30s": 0,
  "stall_count_60s": 0,
  "dropped_frames_30s": 2,
  "decoder_latency_ms": 8,
  "net_type": "wifi_5g",
  "wifi_rssi": -48,
  "wifi_freq_mhz": 5180,
  "wifi_link_speed_mbps": 866,
  "thermal": "nominal",
  "thermal_trend": "stable",
  "skin_temp_c": 34.5,
  "cpu": "nominal",
  "memory": "fair",
  "battery": 85,
  "battery_state": "discharging",
  "power_mode": "normal",
  "battery_temp_c": 31.2,
  "charging_wattage": 0,
  "packet_loss_pct": 0.1,
  "rtt_ms": 12,
  "net_metered": false
}
```

The server handles any subset of these fields. Include only what your client can measure reliably. Keys that cannot be measured should be omitted entirely (do not send default/zero values for unknown fields).

## 6. Freshness and Staleness

Status: these rules are the V1 target behavior once server polling/parser lands. They are not fully active in current server code yet.

### 6.1 Client Update Cadence

- The client must update its internal estimate at least once per second during active playback
- The `seq` field must increment on every update so the server can detect stale/duplicate samples
- If the client has no new measurement, it should still return the last sample (same `seq`) — the server uses the timestamp delta to detect staleness

### 6.2 Server Staleness Rules

- A sample is **fresh** if `seq` has changed since the last poll, or the sample age is < 5 seconds
- A sample is **stale** if `seq` is unchanged for > 10 seconds
- A **stale** sample causes the server to fall back to its own push-based estimation
- The server will not penalize the client for stale samples — it simply ignores them

## 7. Error and Edge Cases

### 7.1 Empty or Missing Response

- If the client returns an empty string, the server treats it as "no feedback available"
- This is not an error — the server falls back to legacy estimation

### 7.2 Malformed Payload

- The server ignores keys it does not recognize
- The server ignores values that fail type validation (e.g. non-integer for `kbps`)
- A malformed payload does not break the session — the server logs a warning and skips the sample

### 7.3 Zero or Negative kbps

- `kbps=0` is treated as "client has no estimate" — same as empty response
- Negative values are ignored

### 7.4 Network Transitions

- When the client detects a network change (WiFi → cellular, SSID change, etc.):
  - Reset `seq` to 0
  - Report the new `net_type` immediately
  - The kbps estimate from the old network is stale — report `kbps=0` until a new measurement is available
  - The server will detect the `seq` reset and discard its EWMA history for this client

## 8. Server Behavior (Informational)

This section explicitly describes **current active implementation** in the running server.

### 8.0 Current Implementation Snapshot (ACTIVE)

- ✓ Server sets `SAGETV_NG_SERVER=1` on every client connection
- ✓ Server calls `pollNgBandwidthFeedbackKbps(true)` at startup (after client handshake)
  - If `miniplayer/ng_bandwidth_feedback_enabled=true` (default) AND client advertises `BANDWIDTH_FEEDBACK_V1`
  - Server expects return value > 0 (in kbps)
  - Uses this to initialize bitrate budget and skip synthetic push-probe if available
- ✓ Server calls `pollNgBandwidthFeedbackPayload()` during playback (polling cadence configurable)
  - Parses kbps, seq, buffer_ms, rebuffer_count_30s, thermal, power_mode, net_type, etc.
  - Uses parsed values to drive EWMA smoothing and adaptive bitrate decisions
- Device-health fields (thermal, power_mode, memory, net_type, etc.) are **partially consumed**:
  - `thermal=critical` → immediate bitrate reduction
  - `power_mode=battery_saver` → apply budget reduction multiplier
  - `net_type=cellular_3g` → cap bitrate at 1500 kbps
  - Others logged for diagnostics but may not drive decisions yet

### 8.1 Startup

At playback start:

- If NG bandwidth feedback is available and fresh, the server uses it to set the initial transcode bitrate
- If the feedback is fresh enough (< 5 seconds old), the server skips the synthetic startup push probe entirely
- If feedback is missing or stale, the server falls back to the existing push-probe estimation

### 8.2 Live Adaptation (Planned V1 Behavior)

During playback:

- The server polls the property at a configurable interval (default 2 seconds)
- The sample feeds into the server's EWMA smoother with hysteresis to avoid rapid oscillation
- The server applies asymmetric rate adjustment: fast ramp-down (immediate on `serious`/`critical` signals), slow ramp-up (requires sustained improvement over multiple samples)
- Device health signals (`thermal`, `power_mode`, `net_type`) act as multipliers on the bandwidth budget — e.g. `battery_saver` reduces the effective budget by 40% regardless of reported kbps

### 8.3 Server Decision Priority (Planned V1 Behavior)

When multiple signals conflict, the server uses this priority order:

1. `thermal=critical` or `cpu=critical` → force minimum bitrate (overrides everything)
2. `power_mode=ultra_saver` → audio-only or minimum video
3. `rebuffer_count_30s > 0` or `stall_count_60s > 0` → immediate ramp-down
4. `thermal=serious` or `thermal_trend=rising` → preemptive ramp-down
5. `power_mode=battery_saver` → apply 0.6x budget multiplier
6. `net_type=cellular_3g` → cap at 1500 kbps regardless of reported kbps
7. `wifi_rssi < -75` → apply 0.7x credibility multiplier to reported kbps
8. Normal EWMA-smoothed kbps → standard bitrate selection

## 9. No End-User Tunables

This is explicit because it affects client implementation:

- no quality slider for this feature
- no bandwidth mode switch
- no hidden debug preference intended for release builds
- no manual override for feedback polling cadence exposed to users

Automatic behavior is allowed:

- route-aware estimation
- player-aware estimation
- network-aware estimation
- server-negotiated adaptation

But none of that should be surfaced as a user-facing knob in V1.

## 10. Recommended Client Implementation Pattern

```ts
interface DeviceHealth {
  thermal: "nominal" | "fair" | "serious" | "critical";
  thermalTrend?: "stable" | "rising" | "falling";
  cpu: "nominal" | "fair" | "serious" | "critical";
  memory: "nominal" | "fair" | "serious" | "critical";
  battery: number;          // 0-100
  batteryState: "charging" | "discharging" | "full" | "unknown";
  powerMode: "normal" | "battery_saver" | "ultra_saver" | "performance" | "unknown";
}

interface NetworkInfo {
  netType: string;           // "wifi" | "wifi_5g" | "cellular" | "ethernet" | etc.
  wifiRssi?: number;         // dBm, e.g. -55
  wifiFreqMhz?: number;      // e.g. 5180
  wifiLinkSpeedMbps?: number; // e.g. 866
  cellularSignalDbm?: number; // dBm
  netMetered?: boolean;
}

interface BandwidthFeedbackSample {
  kbps: number;
  seq: number;
  updatedAtMs: number;
  bufferMs?: number;
  rebufferCount30s?: number;
  stallCount60s?: number;
  droppedFrames30s?: number;
  health?: DeviceHealth;
  network?: NetworkInfo;
}

let latestSample: BandwidthFeedbackSample = {
  kbps: 0,
  seq: 0,
  updatedAtMs: 0,
};

function updateBandwidthEstimate(bytesReceived: number, windowMs: number) {
  if (windowMs <= 0) return;

  const instantKbps = Math.max(1, Math.floor((bytesReceived * 8) / windowMs));
  const smoothedKbps = smoothEstimate(instantKbps);

  if (Math.abs(smoothedKbps - latestSample.kbps) >= 100) {
    latestSample = {
      ...latestSample,
      kbps: smoothedKbps,
      seq: latestSample.seq + 1,
      updatedAtMs: Date.now(),
    };
  }
}

// Call when OS reports network type change (WiFi ↔ cellular, SSID change, etc.)
function onNetworkChanged(newNetwork: NetworkInfo) {
  latestSample = {
    ...latestSample,
    kbps: 0,       // invalidate old estimate
    seq: 0,        // signal reset to server
    updatedAtMs: Date.now(),
    network: newNetwork,
  };
}

function onGetProperty(name: string): string {
  if (name !== "SAGETV_NG_BANDWIDTH_FEEDBACK_V1") {
    return "";
  }

  if (latestSample.kbps <= 0) {
    // Still report device health even without BW estimate
    const parts: string[] = ["kbps=0", `seq=${latestSample.seq}`];
    appendHealthFields(parts, latestSample);
    appendNetworkFields(parts, latestSample);
    return parts.join(";");
  }

  const parts: string[] = [
    `kbps=${latestSample.kbps}`,
    `seq=${latestSample.seq}`,
  ];

  if (latestSample.bufferMs !== undefined)
    parts.push(`buffer_ms=${latestSample.bufferMs}`);
  if (latestSample.rebufferCount30s !== undefined)
    parts.push(`rebuffer_count_30s=${latestSample.rebufferCount30s}`);
  if (latestSample.stallCount60s !== undefined)
    parts.push(`stall_count_60s=${latestSample.stallCount60s}`);
  if (latestSample.droppedFrames30s !== undefined)
    parts.push(`dropped_frames_30s=${latestSample.droppedFrames30s}`);

  appendHealthFields(parts, latestSample);
  appendNetworkFields(parts, latestSample);

  return parts.join(";");
}

function appendHealthFields(parts: string[], s: BandwidthFeedbackSample) {
  if (!s.health) return;
  parts.push(`thermal=${s.health.thermal}`);
  if (s.health.thermalTrend) parts.push(`thermal_trend=${s.health.thermalTrend}`);
  parts.push(`cpu=${s.health.cpu}`);
  parts.push(`memory=${s.health.memory}`);
  parts.push(`battery=${s.health.battery}`);
  parts.push(`battery_state=${s.health.batteryState}`);
  parts.push(`power_mode=${s.health.powerMode}`);
}

function appendNetworkFields(parts: string[], s: BandwidthFeedbackSample) {
  if (!s.network) return;
  parts.push(`net_type=${s.network.netType}`);
  if (s.network.wifiRssi !== undefined) parts.push(`wifi_rssi=${s.network.wifiRssi}`);
  if (s.network.wifiLinkSpeedMbps !== undefined)
    parts.push(`wifi_link_speed_mbps=${s.network.wifiLinkSpeedMbps}`);
  if (s.network.cellularSignalDbm !== undefined)
    parts.push(`cellular_signal_dbm=${s.network.cellularSignalDbm}`);
  if (s.network.netMetered !== undefined)
    parts.push(`net_metered=${s.network.netMetered}`);
}
```

Notes:

- Keep this fast — no allocations on hot path if avoidable
- Returning device health when `kbps=0` is safe and future-ready, but current server code may ignore these fields until polling/parser work is merged
- Omit keys entirely when the value is unavailable (do not send defaults or zeros for unknown fields)
- Call `onNetworkChanged()` on connectivity broadcasts; this is future-ready for server-side sequence-aware smoothing logic
- Update `health` and `network` fields from OS callbacks independently of bandwidth measurement

## 11. Release Checklist For Client Team

### Core Protocol (Required NOW)
- [ ] Advertise `BANDWIDTH_FEEDBACK_V1` in `SAGETV_NG_CAPABILITIES`
- [ ] Implement `GetProperty SAGETV_NG_BANDWIDTH_FEEDBACK_V1` response
- [ ] Return payload: `kbps=<value>;seq=<counter>` (minimum viable format)
- [ ] Keep sampling automatic, not user-tunable
- [ ] Return stable `kbps` estimate; increment `seq` on every update
- [ ] Do not add a V2 fork before release

### Network Awareness (Recommended)
- [ ] Report `net_type` (wifi/cellular/ethernet)
- [ ] Report `wifi_rssi` when on WiFi
- [ ] Report `net_metered` when determinable
- [ ] Reset `seq` to 0 on network transitions (WiFi ↔ cellular, SSID change)

### Device Health (Recommended)
- [ ] Report `thermal` state from OS thermal API
- [ ] Report `thermal_trend` (rising/falling/stable) when available
- [ ] Report `power_mode` (normal/battery_saver/ultra_saver/performance)
- [ ] Report `battery` level and `battery_state`
- [ ] Report `cpu` and `memory` pressure levels

### QoE Signals (Optional)
- [ ] Report `buffer_ms` (player buffer depth)
- [ ] Report `rebuffer_count_30s` (rebuffer events)
- [ ] Report `dropped_frames_30s` when available from decoder API

### Validation
- [ ] Test that `GetProperty SAGETV_NG_BANDWIDTH_FEEDBACK_V1` is called at startup and during playback
- [ ] Verify server adjusts bitrate based on returned `kbps` value
- [ ] Test `seq` counter increment behavior
- [ ] Verify server reacts to `thermal=critical` by reducing bitrate
- [ ] Verify `power_mode=battery_saver` triggers lower bitrate selection
- [ ] Verify network transition (`seq=0` reset) causes server adaptation changes

## 12. What To Share With The Client Team

If they only need the bandwidth-adjustment contract, send:

- this file: `docs/NGBandwidthAdjustmentClientHandoff.md`

If they also need broader background for the rest of NG playback capability reporting, add:

- `docs/NGClientCapabilities.md`
