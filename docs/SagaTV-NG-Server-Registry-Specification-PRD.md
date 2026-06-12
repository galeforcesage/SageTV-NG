# SagaTV-NG Server Registry Specification (PRD)

## 1. Purpose

Provide a **user-owned, decentralized discovery and registration mechanism** for SagaTV-NG servers, enabling clients to locate and connect to servers without:

- manual IP management
- dynamic DNS
- central cloud dependency

---

## 2. Design Principles

### Core Principles

- **Decentralized:** No global service required
- **User-controlled:** Registry lives in user storage (e.g. OneDrive)
- **Multi-endpoint aware:** Servers expose LAN / VPN / WAN addresses
- **Eventually consistent:** Accept sync delays
- **Resilient:** Clients try multiple strategies

---

## 3. High-Level Architecture

```text
          ┌──────────────────────────┐
          │   OneDrive / Storage     │
          │   discovery.json         │
          └────────────┬─────────────┘
                       │
        ┌──────────────┼──────────────┐
        │              │              │
 ┌────────────┐ ┌────────────┐ ┌────────────┐
 │ Server A   │ │ Server B   │ │ Server C   │
 │ updates    │ │ updates    │ │ updates    │
 └─────┬──────┘ └─────┬──────┘ └─────┬──────┘
       │              │              │
       └─────── Clients fetch + resolve ───────► Connect
```

## 4. Data Model (discovery.json)

### File Location

/Apps/SagaTV-NG/discovery.json

### JSON Schema (v1)

```json
{
  "version": 1,
  "updated": 1717971293,
  "servers": [
    {
      "id": "uuid-v4",
      "name": "Basement DVR",
      "owner": "optional-user-id",
      "lastSeen": 1717971200,
      "expires": 1717971800,

      "capabilities": {
        "dvr": true,
        "liveTV": true,
        "transcode": true
      },

      "endpoints": [
        {
          "type": "lan",
          "url": "http://192.168.1.50:31099",
          "priority": 100
        },
        {
          "type": "vpn",
          "url": "http://10.0.0.5:31099",
          "priority": 200
        },
        {
          "type": "wan",
          "url": "https://myserver.example.com",
          "priority": 300
        }
      ],

      "metadata": {
        "location": "home",
        "notes": "optional"
      }
    }
  ]
}
```

---

## 5. Server Behavior

### 5.1 On Startup

Server must:

1. Load existing `discovery.json`
2. Locate its entry by `id`

If not exists -> create new entry

3. Populate/update `lastSeen`
4. Populate/update `expires`
5. Populate/update `endpoints`
6. Write file back

---

### 5.2 On IP / Network Change

Server SHOULD update when:

- VPN address changes
- WAN IP / DNS changes
- LAN IP changes

---

### 5.3 Endpoint Detection

Server MUST attempt to detect:

#### LAN Endpoint

```text
http://:31099
```

#### VPN Endpoint

- Example:

```text
http://10.x.x.x:31099
```

(Any private routed VPN subnet - WireGuard, Tailscale, OpenVPN, etc.)

#### WAN (optional)

- user-provided hostname or external address

---

### 5.4 Write Strategy (Conflict Avoidance)

```text
READ -> MODIFY -> WRITE
```

- Preserve other server entries
- Avoid overwriting unrelated entries
- Retry on conflict

---

### 5.5 Expiration

Each server sets:

```text
expires = lastSeen + TTL
```

Recommended:

```text
TTL = 5-15 minutes
```

---

## 6. Client Behavior

### 6.1 Registry Fetch

Client loads:

- `discovery.json`
- caches locally

---

### 6.2 Server Filtering

Client MUST:

- Ignore entries where:

```text
now > expires
```

- Sort remaining by:

`lastSeen DESC`

---

### 6.3 Connection Resolution Algorithm

For each server:

```text
for endpoint in endpoints sorted by priority:
    if reachable:
        connect
        break
```

---

### 6.4 Network-Aware Ranking (recommended)

Override priority dynamically:

Condition | Prefer endpoint
--- | ---
Same subnet | LAN
VPN active | VPN
Otherwise | WAN

---

### 6.5 Fallback Behavior

If registry fails:

1. Use cached registry
2. Attempt direct/manual endpoints
3. Attempt LAN discovery (UPnP/broadcast)

---

## 7. Bootstrap Mechanism (Critical)

### 7.1 Share Link Bootstrap (Primary)

User provides:

```text
https://onedrive/.../discovery.json
```

Client:

- stores link
- uses as registry source

---

### 7.2 QR Code (Optional UX Enhancement)

```text
sagetv-ng://bootstrap?registry=
```

---

### 7.3 Manual Entry (Fallback)

User can input:

- server IP
- or registry URL

---

## 8. Security Model

### 8.1 Assumptions

- Registry is user-controlled
- No global trust model

---

### 8.2 Minimal Security

- Registry is **read-only for clients**
- Write access restricted to servers

---

### 8.3 Optional Future Enhancements

- Signed server entries
- Per-user encryption
- Token-based registry access

---

## 9. Failure Modes

Scenario | Behavior
--- | ---
OneDrive unavailable | Use cache
Server entry stale | Skip
IP changed not synced | Try other endpoints
Multiple servers collide | Last write wins

---

## 10. Discovery Stack (Final Architecture)

```text
1. Direct known endpoints (VPN / DNS / manual)
2. Registry (OneDrive discovery.json)
3. LAN discovery (UPnP) - optional fallback
```

---

# Final Note

This version makes the model:

- Vendor-neutral (VPN = any private routed network)
- Compatible with your Tailnet usage (without naming it)
- Clear for future contributors
