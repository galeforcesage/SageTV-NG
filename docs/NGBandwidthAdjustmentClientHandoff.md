# NG Bandwidth Adjustment: Client Handoff

Audience: NG client team

Purpose: Bandwidth-adjustment-only contract for NG clients. This document is platform-agnostic and applies to any NG client implementation.

## 1. Scope

This document covers only NG client to server bandwidth feedback used for startup and live adaptive bitrate decisions.

It does not redefine:

- download flow
- offline manifest behavior
- playback-state sync
- unrelated capability negotiation

## 2. V1 Rules

- This is V1.
- Automatic tuning between client and server is allowed.
- End-user tuning is not allowed.
- Do not add user-visible toggles for this feature in V1.
- If behavior changes before release, server and client change together under the same V1 contract.
- Do not split to a V2 contract before release.

## 3. What The Client Must Implement

1. Required
- Advertise `BANDWIDTH_FEEDBACK_V1` in `SAGETV_NG_CAPABILITIES`.
- Implement `GetProperty SAGETV_NG_BANDWIDTH_FEEDBACK_V1`.
- Return at minimum `kbps=<value>;seq=<counter>`.

2. Recommended (same V1 contract)
- Include device and network health signals when available (`thermal`, `cpu`, `memory`, `power_mode`, `net_type`, etc.).
- Keep updates fresh enough for startup and live adaptation.

## 4. Negotiation Contract

### 4.1 Capability Token

Advertise this token in `SAGETV_NG_CAPABILITIES`:

- `BANDWIDTH_FEEDBACK_V1`

<Remove> Alias token `BW_FEEDBACK_V1`.

### 4.2 Server Capability Advertisement

Server advertises:

- `SAGETV_NG_SERVER=1`
- `SAGETV_NG_SERVER_CAPS=BANDWIDTH_FEEDBACK_V1`

### 4.3 Static Device Capability Properties (One-Time)

Set these once during connection setup. Do not include them in every bandwidth sample.

- `SAGETV_NG_DISPLAY_HZ` (integer, example: 60, 90, 120)
- `SAGETV_NG_DEVICE_TYPE` (enum: `phone`, `tablet`, `tv`, `watch`, `auto`, `other`)
- `SAGETV_NG_VIDEO_DECODER` (enum: `hardware`, `software`, `limited`)
- `SAGETV_NG_DISPLAY_RES` (string, example: `1920x1080`)
- `SAGETV_NG_HDR_SUPPORT` (enum: `none`, `hdr10`, `hdr10plus`, `dolby_vision`, `hlg`)

## 5. Property Contract

### 5.1 Property Name

When the server requests a sample, it calls:

- `GetProperty SAGETV_NG_BANDWIDTH_FEEDBACK_V1`

Client returns a string payload.

### 5.2 Allowed Payload Shapes

1. Plain integer kbps

```text
18400
```

2. Key/value payload (semicolon-delimited)

```text
kbps=18400;seq=42
```

```text
kbps=18400;seq=42;buffer_ms=18000;rebuffer_count_30s=0;thermal=nominal;net_type=wifi;battery=85;power_mode=normal
```

3. JSON payload

```text
{"kbps":18400,"seq":42,"buffer_ms":18000,"rebuffer_count_30s":0,"thermal":"nominal","net_type":"wifi","battery":85,"power_mode":"normal"}
```

### 5.3 Bandwidth Keys

Required:

- `kbps` (estimated sustainable receive bandwidth, kilobits per second)
- `seq` (monotonic sample counter)

<Remove> `bw_kbps`, `bandwidth_kbps`, `est_kbps`.

<Remove> `sequence`.

### 5.4 QoE Keys (Optional)

- `buffer_ms` (integer)
- `rebuffer_count_30s` (integer)
- `stall_count_60s` (integer)
- `dropped_frames_30s` (integer)
- `decoder_latency_ms` (integer)
- `packet_loss_pct` (float, 0-100)
- `rtt_ms` (integer)

### 5.5 Network Condition Keys (Optional)

- `net_type` (enum: `wifi`, `wifi_5g`, `wifi_6e`, `cellular`, `cellular_5g`, `cellular_4g`, `cellular_3g`, `ethernet`, `vpn`, `unknown`)
- `wifi_rssi` (integer, dBm)
- `wifi_freq_mhz` (integer)
- `wifi_link_speed_mbps` (integer)
- `cellular_signal_dbm` (integer, dBm)
- `net_metered` (boolean)

### 5.6 Device Health Keys (Optional)

Thermal:

- `thermal` (enum: `nominal`, `fair`, `serious`, `critical`)
- `thermal_trend` (enum: `stable`, `rising`, `falling`)
- `skin_temp_c` (float)

CPU and memory:

- `cpu` (enum: `nominal`, `fair`, `serious`, `critical`)
- `memory` (enum: `nominal`, `fair`, `serious`, `critical`)

Battery and power:

- `battery` (integer 0-100)
- `battery_state` (enum: `charging`, `discharging`, `full`, `unknown`)
- `power_mode` (enum: `normal`, `battery_saver`, `ultra_saver`, `performance`, `unknown`)
- `battery_temp_c` (float)
- `charging_wattage` (integer)

<Remove> Per-sample keys for static capabilities: `display_hz`, `device_type`, `video_decoder`.

### 5.7 Preferred V1 Payloads

Minimum:

```text
kbps=18400;seq=42
```

Recommended:

```text
kbps=18400;seq=42;buffer_ms=18000;rebuffer_count_30s=0;net_type=wifi;thermal=nominal;cpu=fair;memory=fair;battery=85;power_mode=normal
```

JSON example:

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

Rules:

- Include only fields that are reliably measurable on the client platform.
- Omit unknown fields entirely.
- Do not send placeholder defaults for unknown values.

## 6. Freshness and Staleness

### 6.1 Client Cadence

- Update estimate at least every second during active playback.
- Increment `seq` whenever a new sample is published.
- If no new sample exists, return last sample (same `seq`).

### 6.2 Staleness Expectations

- Fresh sample: recently updated and changing `seq`.
- Stale sample: unchanged for a sustained interval.
- On stale/invalid data, server may fall back to internal estimation.

## 7. Error and Edge Cases

### 7.1 Empty or Missing Response

- Empty response means feedback unavailable.
- Server should fall back safely.

### 7.2 Malformed Payload

- Unknown keys are ignored.
- Type-invalid values are ignored.
- Session should continue; sample is skipped.

### 7.3 Zero or Negative kbps

- `kbps=0` means no current estimate.
- Negative values are invalid and ignored.

### 7.4 Network Transitions

On network change (for example Wi-Fi to cellular):

- Reset `seq` to 0.
- Report new `net_type` immediately.
- Set `kbps=0` until new measurement is credible.

## 8. No End-User Tunables

- no quality slider for this feature
- no bandwidth mode switch
- no hidden release preference for user control
- no user-exposed polling cadence control

Automatic behavior is allowed:

- route-aware estimation
- player-aware estimation
- network-aware estimation
- server-negotiated adaptation

## 9. Recommended Client Implementation Pattern

```ts
interface BandwidthFeedbackSample {
  kbps: number;
  seq: number;
  updatedAtMs: number;
  bufferMs?: number;
  rebufferCount30s?: number;
  thermal?: "nominal" | "fair" | "serious" | "critical";
  cpu?: "nominal" | "fair" | "serious" | "critical";
  memory?: "nominal" | "fair" | "serious" | "critical";
  powerMode?: "normal" | "battery_saver" | "ultra_saver" | "performance" | "unknown";
  battery?: number;
  netType?: string;
}

let latestSample: BandwidthFeedbackSample = { kbps: 0, seq: 0, updatedAtMs: 0 };

function onGetProperty(name: string): string {
  if (name !== "SAGETV_NG_BANDWIDTH_FEEDBACK_V1") return "";
  if (latestSample.kbps <= 0) return "";

  const parts = [`kbps=${latestSample.kbps}`, `seq=${latestSample.seq}`];
  if (latestSample.bufferMs !== undefined) parts.push(`buffer_ms=${latestSample.bufferMs}`);
  if (latestSample.rebufferCount30s !== undefined) parts.push(`rebuffer_count_30s=${latestSample.rebufferCount30s}`);
  if (latestSample.thermal) parts.push(`thermal=${latestSample.thermal}`);
  if (latestSample.cpu) parts.push(`cpu=${latestSample.cpu}`);
  if (latestSample.memory) parts.push(`memory=${latestSample.memory}`);
  if (latestSample.powerMode) parts.push(`power_mode=${latestSample.powerMode}`);
  if (latestSample.battery !== undefined) parts.push(`battery=${latestSample.battery}`);
  if (latestSample.netType) parts.push(`net_type=${latestSample.netType}`);

  return parts.join(";");
}
```

Notes:

- Keep reply path fast.
- Avoid heavy allocations in hot polling paths.
- Use platform-native APIs where available, but keep emitted payload schema consistent across all NG clients.

## 10. Release Checklist For Client Team

- [ ] Advertise `BANDWIDTH_FEEDBACK_V1`.
- [ ] Implement `GetProperty SAGETV_NG_BANDWIDTH_FEEDBACK_V1`.
- [ ] Return at least `kbps` and `seq`.
- [ ] Keep sampling automatic and non-user-tunable.
- [ ] Increment `seq` on updates.
- [ ] Add optional QoE/network/device-health fields where reliable.
- [ ] Validate startup and live polling behavior.
- [ ] Validate network transition behavior (`seq` reset, `kbps=0` until re-measured).

## 11. What To Share With The Client Team

For bandwidth-adjustment contract only:

- `docs/NGBandwidthAdjustmentClientHandoff.md`

For broader NG capability context:

- `docs/NGClientCapabilities.md`
