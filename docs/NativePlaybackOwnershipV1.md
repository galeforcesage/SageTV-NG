# Prospective ordinary native playback ownership v1

Status: **implemented server source**, optional NG-only same-JVM integration.
Bridge/browser cookie source belongs to the PWA repository, not this server.
No new HTTP endpoint, listener, cookie store, TLS setting, deployment or device
validation is supplied here. Ordinary native/AVPlay selection and legacy
defaults are unchanged. This extension does not offer Party Mode to legacy
clients or relax [PartyModeV1.md](PartyModeV1.md) negotiation/readiness.

## Negotiation and trust boundary

An admitted NG client may include `NATIVE_PLAYBACK_OWNERSHIP_V1` in its existing
`SAGETV_NG_CAPABILITIES` reply. It is separate from all `PARTY_*` guarantees and
does not require Party enablement/membership. Do not advertise until the bridge
and adapter implement this lifecycle and have passed their secure-origin gates.
Ordinary clients omitting it are unaffected; their existing native producers
remain **unassociated and unclaimable** for a subsequent Party transition.

Authorization requires the existing actual admitted renderer: existing LAN
admission policy or verified account admission, not caller identity or a claimed
NG boolean. Shared bridge Basic authentication, HTTP Origin, IP, clientName,
MAC, URL session/resource ID and a MiniPlayer DEINIT/Stop acknowledgment are not
exact renderer/producer ownership. Server media authorization/Watch/parental
checks and actual selected delivery remain authoritative.

The public API requires the **same server JVM and shared Sage classloader** as
the bridge. It matches the actual bridge-owned live upstream graphics Socket to
one current accepted renderer SocketChannel by the reversed full local/remote
TCP pair. Missing, ambiguous, closed, half-closed, replaced, remote/NAT/unmatched
mapping fails closed for this ownership path; ordinary unowned playback is
unchanged. No control encryption/compression parsing, private reflection, caller
endpoint fields or IP-only lookup is permitted.

User-approved native credential designs are conditional on **trusted HTTPS and
secure control**, without downgrade or TLS/certificate bypass:

* Tizen AVPlay uses an explicit per-player `COOKIE` streaming property after
  `open` reaches IDLE and before `prepareAsync`, reapplied on every authorized
  open. Generic native custom headers are not assumed. Manual native Cookie
  headers do not inherit browser HttpOnly/Path/SameSite enforcement.
* Safari may use ambient cookies on a **dedicated trusted same-origin HTTPS
  SageTV origin**. The user explicitly accepts normal same-origin/browser-profile
  shared media-read/use authority, **not per-tab cryptographic isolation**.
  Bootstrap is a fixed same-origin POST with a private one-use proof delivered
  over the owning secure gfx connection; no secret URL. The proposed bridge
  cookie is HttpOnly, Secure, SameSite=Strict, host-only (no Domain), and scoped
  by per-context name/path. These attributes do not prevent another trusted
  same-origin tab/script from requesting the known context's resource or winning
  its first request. Path and nonsecret selectors route; they do not authenticate.

Cookie implementation lives in the bridge. The server sees the bridge's actual
upstream TCP socket, **not** the browser's WSS/HTTPS certificate: this API cannot
attest to browser-facing TLS. The bridge must enforce trusted HTTPS on bootstrap
and media, actual secure originating gfx, same-origin rules and device validation.
No cookie/secret may appear in URLs, logs, analytics, redirects or exception
details. The cookie grants only read/use of one already-authorized immutable
resource; it never grants Stop, retirement, arbitrary file/mode selection or Party
commands. Lifecycle control remains on the exact owning gfx connection.

## Exact public Java API

Canonical source: `java/sage/NativePlaybackOwnershipBridge.java`.

```java
public static NativePlaybackOwnershipBridge.Context claim(java.net.Socket upstreamControlSocket);
public static boolean isCurrent(String contextId, java.net.Socket upstreamControlSocket);
public static void release(String contextId, java.net.Socket upstreamControlSocket);
public static NativePlaybackOwnershipBridge.Context reissue(
    String previousContextId, java.net.Socket upstreamControlSocket);
```

`claim` and `reissue` return a public immutable record with these exact accessors:

| Type | Accessors |
|---|---|
| String | `contextId()`, `serverEpoch()`, `rendererHandle()`, `resourcePath()`, `surface()`, `mode()`, `xcodeMode()` |
| int | `mediaFileId()`, `segmentIndex()` |
| long | `playbackGeneration()`, `pathGeneration()`, `expiresAtServerMonoMs()`, `durationMs()`, `ownershipGeneration()` |

`resourcePath` is the actual server-selected segment File's absolute path,
resolved from the ordinary load argument and that MediaFile's segment list.
It is **private bridge metadata**, not a client input or browser response field.
`segmentIndex` is zero-based. `durationMs` is the selected segment's positive
duration, not an entire multi-segment timeline or file-epoch PTS. Ordinary native
initial seek folding/transport positioning may choose a finite segment-relative
offset in `[0,durationMs]`; the producer's selected offset becomes immutable for
request replay. This is existing ordinary same-resource positioning, not
permission to change Party's timeline or select another file.

`mode` is the actual server-selected `pull` or `pull-xcode`. The bridge maps
`pull` to its direct resource and `pull-xcode` to its xcode resource with the
**exact** `xcodeMode`; it may not override these from HTTP/browser parameters.
`xcodeMode` is null for direct pull. Unknown/no selected xcode mode, missing
surface/path, nonpositive segment duration, push/HLS and non-NG/unadmitted
renderers do not publish eligible intents in this version. Never replace a
failed owned path with an unowned/second producer.

All identifiers are ephemeral random 192-bit URL-safe strings, not permanent or
personal IDs. `serverEpoch` resets with this registry/process/classloader and
is separate from Party's service epoch; do not compare the two. The time domain
is server `System.nanoTime()/1000000`, not UTC or the Party service's rebased
clock. Do not compare it to browser Date.now or performance.now without an
appropriate mapping; bridge startup checks use the server API deadline/domain.
`rendererHandle` is opaque context metadata, not a stable lookup/authorization
key. `playbackGeneration` identifies this exact MiniPlayer load;
`pathGeneration` fences its renderer's surface configuration;
`ownershipGeneration` starts at 0 and increases only for explicit same-resource
reissue, without changing media/player/Party timeline/configuration.

Denied claim/reissue throws exactly
`IllegalStateException("NATIVE_ASSOCIATION_DENIED")`; no path, credential or
endpoint details. `isCurrent` returns false for absent/mismatched/revoked
contexts; wrong-socket/duplicate release is harmless. It is not a network API
and adds no new gfx SET_PROPERTY, JSON request/event or MediaServer command.

## Publication and lifecycle

The server publishes one prospective ordinary OPEN intent **after normal Watch,
media/segment and selected NG playback-path decisions, before OPENURL bytes are
sent and before waiting for its client reply**. Native prepare/HTTP therefore
can claim while the player is still NO_STATE. Requiring LOADED here would
deadlock native startup; a successful claim is **not** a load-success, producer
output or Party READY receipt. Exact matching is to the loaded-or-loading
VideoFrame's selected MiniPlayer/MediaFile and original ordinary load fence.
Publication does not open a second media producer.

The bridge calls `claim` with its actual owned upstream gfx Socket when handling
that ordinary OPEN intent, before allowing the future native HTTP producer.
It must not claim based on discovery or adopt an already-existing ordinary
producer. Only one claim is consumed for that intent, even on the same socket.
The same Java Socket object is required by all subsequent calls. Copying
endpoint fields/constructing another Socket does not transfer ownership.

At most 80 current intents/tombstones are retained. An unclaimed intent expires
60000 ms after publication; rejected/expired contexts do not revive. Claimed
ownership may remain valid past this **startup** deadline for an already
established current resource. The bridge must separately bound credential and
producer startup to 60 seconds; claiming alone is not permission to delay
startup indefinitely. Expired cookie/HTTP retries cannot automatically OPEN a
new producer. Server checks and bridge polling are lifecycle checks, not cookie
renewal or a fabricated HTTP completion receipt.

Check `isCurrent` before credential issuance, HTTP admission, coordinator/resource
acquisition and readiness, and at least every 250 ms while retaining an owned
resource. Actual source load/fastLoad/free, ordinary Stop, new ordinary Watch,
surface transition, changed player/media/mode, control disconnect/replacement,
admission/NG-negotiation loss, explicit release and process restart revoke the
appropriate context. Stop has an explicit ordinary ownership fence: it need not
increment Party's playback generation to revoke ordinary native authority.
Late load callbacks cannot publish after a superseding Stop/load fence. Failed
OPENURL or a failed load revokes prospective ownership; no success fallback.

Bridge resource state must retain the exact actual coordinator or direct/static
producer socket and its terminal future/tombstone **independently** of this
authorization registry. `release` or false `isCurrent` means revoked authority,
**never actual teardown**. Revocation must deny new cookie attaches first, then
retire only the resource already proven owned; terminal completion is recorded
only after its real upstream teardown/finally. A bare MediaServer CLOSE OK or
MiniPlayer STOP/DEINIT reply is insufficient. New Party OPEN must wait for that
owned old resource's actual terminal completion. Unknown/unassociated or failed
termination remains fail-closed, never global Stop or a grace-time guess.

Both normal transcoded coordinators **and direct/static native range sockets**
need this actual resource/terminal ownership. Repeated range/attach requests may
use only the same immutable resource/producer under the authorized bridge policy.
The cookie/selector must not create another producer, change seek/file/mode,
retire another resource, or serve as per-tab identity.

## Same-resource ordinary seeks and authorized reopen

Ordinary AVPlay local restart-seek does not necessarily send a new Sage OPENURL.
It must not reuse a consumed claim or regress into competing unowned playback:

1. On the exact owning gfx connection, revoke the resource cookie/attach
   authorization and call `release(previousContextId,sameSocket)`.
2. Retain the bridge's actual resource identity and wait for its real terminal
   future. Unknown/failing teardown cannot authorize replacement.
3. Call `reissue(previousContextId,sameSocket)` only after completion, within
   60000 ms of release. Server requires the **original** ordinary intent still
   current and that latest context explicitly released, then consumes the
   tombstone and returns a fresh context/60-second startup deadline with
   `ownershipGeneration+1`. The returned context is already claimed by that
   same bridge, so do not call `claim` again.
4. Bind the new authorized same-resource offset and create its one future
   producer. No caller path/media/mode is accepted by reissue. Old context IDs
   cannot replay or affect the new context.

Duplicate release does not extend the tombstone lifetime. Stop/new Watch/load,
surface/control/admission change invalidates reissue entirely. Tombstones are
bounded by the same 80-entry limit and 60-second release window. Reissue proves
only continuing authorization; the server cannot verify the bridge's terminal
future and accepts no caller-supplied "teardown complete" boolean.

For ongoing Safari range requests to the same **existing** resource, bridge
cookie renewal must be serialized/authorized on exact live gfx with repeated
server `isCurrent` checks and a private one-use fixed-POST proof; renewal is not
reissue and cannot create/replace a producer. AVPlay's COOKIE setter is IDLE-only:
do not assume hot cookie refresh works on an active request. Reopen/reseek must
use the explicit retirement/reissue flow. Real native request/range/retry/expiry
behavior remains a device acceptance requirement, not a server unit-test result.

## Fixtures, tests and acceptance

`docs/party/v1/golden.json` contains a `nativeOwnershipContext` API-value example,
not a gfx/HTTP wire envelope. `docs/party/v1/examples.cjs` demonstrates fixed
resource/reissue checks. `test/java/sage/NativePlaybackOwnershipBridgeTest.java`
exercises real paired loopback sockets, actual OPENURL write/wait before claim,
one-use claim/reissue, startup/tombstone expiry, mismatched/replaced/closed
sockets, negotiation/admission, generation/Stop fencing and stale-proof denial.
Normal playback and Party regression tests remain separate requirements.

Source verification does not prove browser-facing TLS, Safari/Tizen cookie
propagation, first native network-request ordering, native range/retry/cookie
clearing behavior, long-session renewal, hardware playback, physical sync or
deployed status. User approval covers source implementation of the stated
secure designs; it does not authorize changing server configuration, bypassing
TLS, deployment, restart or treating those acceptance checks as already passed.
