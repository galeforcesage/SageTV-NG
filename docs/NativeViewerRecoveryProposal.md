# Native viewer recovery: server boundary proposal

Status: **design only, pending approval**. This document records the server
assessment for the bridge-owned consolidated recovery proposal. It adds no
implemented endpoint, Java API, credential, storage or identity behavior.
The implemented ordinary ownership contract remains
[NativePlaybackOwnershipV1.md](NativePlaybackOwnershipV1.md).

## Goal and identity limit

Preserve one cooperative viewer's unresolved native-resource retirement
obligation across page reload or complete SessionManager recreation, without
blocking independent viewers. A viewer here is a cooperative top-level tab or
standalone application instance, not a person, account, IP address or permanent
device identifier.

Existing Sage admission cannot identify this continuity. Its verified account
or server-admitted LAN realm can be shared by independent viewers. The current
native context and renderer handle are ephemeral; neither is a reconnect
credential. Actual graphics socket ownership still proves only the current
transport association.

Missing, cleared or deliberately omitted recovery storage is indistinguishable
from a genuinely new independent viewer under these constraints. No optional
proof can guarantee recovery for that case. An explicit fresh-viewer choice
can explain the uncertainty but does not technically prove that an orphaned
producer is absent. Do not claim universal reconnect isolation.

## Proposed authority and bootstrap

The bridge would mint a private, cryptographically random recovery proof and a
server-assigned lineage after a successful ordinary context claim on the exact
owning secure gfx connection. The lineage maps only to the bridge's exact
resource record, actual terminal future and retirement obligation.

Before creating the first owned native producer, the client must durably save
the proof in its chosen storage and acknowledge that write on the same secure
gfx connection. If storage or acknowledgment fails, the owned flow must report
the failure rather than creating an untracked producer or an unowned fallback.
No proof belongs in a URL, log, diagnostic, ambient cookie or advertised client
identifier.

A recreated SessionManager would present the private proof using a bounded
secure control exchange, never a secret URL. Successful recovery transfers
**only a denial obligation**: the recovering viewer remains blocked until its
old resource's real terminal future completes. It does not adopt the producer,
select a file/mode, restore a revoked context, authorize Stop/retirement, or
grant Party membership.

Pending recovery can attach to the bridge's actual new secure socket without
claiming that Sage has authenticated a person or admitted that renderer.
Independent normal Sage admission and a fresh exact-socket ordinary claim or
Party admission remain mandatory before any subsequent media authorization.
The recovery proof itself cannot establish those facts.

## Persistence scope and independent viewers

The bounded first option is per-tab `sessionStorage`, with unavailable storage
treated explicitly. It supports cooperative same-tab reload, not a guarantee of
browser shutdown, standalone application restart or device restart recovery.
Storage survival in Tizen and Safari standalone applications requires device
validation. An installed-viewer persistence alternative is a separate design
decision, not an implied implementation task.

Do not use shared `localStorage`, an ambient browser cookie or an arbitrary
stable client ID as per-viewer identity. Duplicated tabs may copy session storage;
a copied proof denotes the same lineage and can produce an explicit recovery
conflict, not a proven independent viewer. The proposal must define conflict
handling without closing another owner's producer.

Existing same-SessionManager recreation can retain its in-memory obligation
object independently of this proposed persistent recovery. A global denial
barrier is excluded: one viewer's unresolved teardown must not disable unrelated
viewers' native or Party registration.

## Repeated rotation and crash recovery

The proposed bridge journal stores domain-separated cryptographic proof hashes,
lineage versions and rotation transaction state, not plaintext proof secrets.
Accepting a client-generated CSPRNG successor is credential rotation only: it
must be authorized by a valid predecessor for a bridge-created lineage, never
by an unauthenticated caller-selected identity.

1. The client atomically saves the predecessor secret, successor secret,
   rotation nonce and pending state before sending the predecessor proof,
   successor hash and nonce on secure control.
2. The bridge serializes a compare-and-swap on the lineage/version and durably
   commits the successor hash and identical denial fence before acknowledging.
   Concurrent differing successors conflict; they do not create parallel owners.
3. Identical nonce/hash retries return the same transaction result. A consumed
   predecessor permits only that bounded identical status replay, not another
   rotation or ownership transfer.
4. The client durably records the committed successor before discarding the
   predecessor. On another reload, the successor can recover committed state;
   if the original request never committed, the client retries the identical
   pending predecessor transaction.

Same-physical-socket replay alone is insufficient: a reload can lose the response
or acknowledgment and replace that socket. Recovery must handle that crash
window without treating a copied proof as an independent viewer. Old-owner
liveness, conflicting live claimants, journal retention and exact exchange
bounds remain decisions in the consolidated proposal.

Proof expiry removes proof usability; it never declares an unresolved resource
retired. Further rotation while fenced must preserve the same obligation.

## Epochs, restart and actual resource facts

Server epoch change invalidates old authorization, not the bridge's retirement
obligation. A recovery proof cannot revive an old ordinary OPEN intent. Any
future resource requires fresh ordinary Watch/claim after retirement.

The bridge must retain the exact old resource and terminal future independently
of server context revocation. A bridge restart can destroy the in-memory future;
a durable journal must then retain an **unknown/unresolved** state for that
lineage until independently verified exact teardown evidence is available.
Current Sage APIs do not supply a retrospective terminal receipt. Process
restart, timeout, token expiry, Stop/DEINIT acknowledgment or CLOSE OK alone
cannot manufacture one. Other viewers remain unaffected.

Without sufficient durable journal or client storage, report the recovery
limitation explicitly. An explicit fresh independent viewer does not resolve
the old unknown resource fact or demonstrate same-viewer continuity.

## Minimum server API impact and approval boundary

**Recommended: no Sage API change** for this denial-only design. The bridge owns
the recovery proof, journal and retained resource obligation; existing independent
server media authorization remains authoritative.

If a later approved requirement insists on backend admission attestation before
even a pending recovery attachment, the smallest candidate would be a boolean
actual-socket admission check. It would require exactly one current admitted,
initialized NG renderer with the ownership capability and the reversed live
TCP pair. It would return no account, realm or stable viewer identity, confer
no media/retirement rights, and require a separately bounded admission registry.
That check is **not implemented or approved**, and is unnecessary for transferring
only a denial obligation.

Implementation approval must follow review of the consolidated storage,
rotation, conflict, retention, restart and UX proposal. This design does not
authorize recovery code, server configuration, deployment, restart, TLS bypass,
new login/admin requirements or hardware changes.
