package sage;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import sage.party.PartyProtocol;

/**
 * Correlates a normally admitted NG renderer's loaded playback generation with one
 * existing media producer. A credential is correlation authority, not a resource reservation.
 */
final class NgPartyMediaAdmission
{
  static final NgPartyMediaAdmission INSTANCE =
      new NgPartyMediaAdmission(() -> System.nanoTime() / 1000000);
  private final LongSupplier time;
  private final SecureRandom random = new SecureRandom();
  private final Map<String, Grant> grants = new HashMap<>();
  private final Map<Object, String> owners = new IdentityHashMap<>();

  private static final class Grant
  {
    final int media;
    final String mode;
    final Object context;
    final BooleanSupplier current;
    final long expires;
    final java.util.function.Consumer<String> revoked;
    PartyMediaAdmissionBridge.Context bridgeContext;
    java.util.function.Predicate<java.net.Socket> controlOwner;
    java.net.Socket bridgeSocket;
    Object producer;
    BooleanSupplier ready;
    BooleanSupplier failed = () -> false;
    Grant(int media, String mode, Object context, BooleanSupplier current, long expires,
        java.util.function.Consumer<String> revoked)
    { this.media = media; this.mode = mode; this.context = context; this.current = current; this.expires = expires; this.revoked = revoked; }
  }

  NgPartyMediaAdmission(LongSupplier time) { this.time = time; }

  synchronized String issue(Object owner, int media, String mode, Object context, BooleanSupplier current)
  { return issue(owner, media, mode, context, current, reason -> {}); }

  synchronized String issue(Object owner, int media, String mode, Object context, BooleanSupplier current,
      java.util.function.Consumer<String> revoked)
  {
    revoke(owner, "REPLACED");
    prune();
    if (grants.size() >= 80) throw new PartyProtocol.Fault("RESOURCE_DENIED");
    if (!current.getAsBoolean()) throw new PartyProtocol.Fault("MEDIA_PATH_NOT_READY");
    byte[] bytes = new byte[24];
    random.nextBytes(bytes);
    String handle = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    grants.put(handle, new Grant(media, mode, context, current, time.getAsLong() + 60000, revoked));
    owners.put(owner, handle);
    return handle;
  }

  synchronized boolean bind(String handle, int media, String mode, Object context,
      Object producer, BooleanSupplier ready)
  { return bind(handle, media, mode, context, producer, ready, () -> false); }

  synchronized boolean bind(String handle, int media, String mode, Object context,
      Object producer, BooleanSupplier ready, BooleanSupplier failed)
  {
    prune();
    Grant grant = grants.get(handle);
    if (grant == null || grant.media != media || !grant.mode.equals(mode)
        || context != null && grant.context != context
        || grant.producer != null && grant.producer != producer)
      return false;
    for (Grant other : grants.values())
      if (other != grant && other.producer == producer) return false;
    grant.producer = producer;
    grant.ready = ready;
    grant.failed = failed;
    return true;
  }

  synchronized boolean confirmed(Object owner)
  {
    prune();
    Grant grant = grants.get(owners.get(owner));
    return grant != null && grant.producer != null && grant.ready.getAsBoolean();
  }

  synchronized long expiresAt(Object owner, String handle)
  {
    prune();
    Grant grant = grants.get(handle);
    if (grant == null || !handle.equals(owners.get(owner))) throw new PartyProtocol.Fault("MEDIA_PATH_NOT_READY");
    return grant.expires;
  }

  synchronized void describe(Object owner, String handle, PartyMediaAdmissionBridge.Context context,
      java.util.function.Predicate<java.net.Socket> controlOwner)
  {
    prune();
    Grant grant = grants.get(handle);
    if (grant == null || !handle.equals(owners.get(owner)) || grant.bridgeContext != null)
      throw new PartyProtocol.Fault("MEDIA_PATH_NOT_READY");
    grant.bridgeContext = context; grant.controlOwner = controlOwner;
  }

  private static boolean live(java.net.Socket socket)
  { return socket != null && socket.isConnected() && !socket.isClosed() && !socket.isInputShutdown() && !socket.isOutputShutdown(); }

  synchronized PartyMediaAdmissionBridge.Context claim(String handle, java.net.Socket socket)
  {
    prune();
    Grant grant = grants.get(handle);
    if (grant == null || grant.bridgeContext == null || grant.bridgeSocket != null || !live(socket)
        || !grant.controlOwner.test(socket)) return null;
    grant.bridgeSocket = socket;
    return grant.bridgeContext;
  }

  synchronized boolean bridgeCurrent(String contextId, java.net.Socket socket)
  {
    prune();
    Grant grant = findBridge(contextId, socket);
    return grant != null && live(socket) && grant.controlOwner.test(socket);
  }

  private Grant findBridge(String contextId, java.net.Socket socket)
  {
    if (contextId == null || socket == null) return null;
    for (Grant grant : grants.values())
      if (grant.bridgeContext != null && contextId.equals(grant.bridgeContext.contextId()) && grant.bridgeSocket == socket)
        return grant;
    return null;
  }

  synchronized void releaseBridge(String contextId, java.net.Socket socket)
  {
    Grant grant = findBridge(contextId, socket);
    if (grant == null) return;
    grants.values().remove(grant);
    owners.values().removeIf(handle -> !grants.containsKey(handle));
    grant.revoked.accept("BRIDGE_RELEASED");
  }

  synchronized void revoke(Object owner)
  { revoke(owner, "RELEASED"); }

  private void revoke(Object owner, String reason)
  {
    String handle = owners.remove(owner);
    Grant grant = handle == null ? null : grants.remove(handle);
    if (grant != null) grant.revoked.accept(reason);
  }

  synchronized void closed(Object producer)
  {
    grants.entrySet().removeIf(entry -> {
      if (entry.getValue().producer != producer) return false;
      entry.getValue().revoked.accept("PRODUCER_CLOSED"); return true;
    });
    owners.values().removeIf(handle -> !grants.containsKey(handle));
  }

  private void prune()
  {
    long now = time.getAsLong();
    grants.entrySet().removeIf(entry -> {
      Grant grant = entry.getValue();
      String reason = !grant.current.getAsBoolean() ? "GENERATION_CHANGED"
          : grant.bridgeSocket != null && (!live(grant.bridgeSocket) || !grant.controlOwner.test(grant.bridgeSocket))
              ? "CONTROL_CLOSED"
          : grant.failed.getAsBoolean() ? "PRODUCER_FAILED"
          : grant.producer == null && grant.expires <= now ? "EXPIRED" : null;
      if (reason == null) return false;
      grant.revoked.accept(reason); return true;
    });
    owners.values().removeIf(handle -> !grants.containsKey(handle));
  }

  synchronized void maintenance() { prune(); }

  static boolean transcodeReady(TranscodeEngine engine)
  {
    return engine != null && engine.getVirtualTranscodeSize() > 0
        && (engine.isTranscodeDone() ? engine instanceof FFMPEGTranscoder
            && ((FFMPEGTranscoder)engine).didTranscodeCompleteOK() : engine.isTranscoding());
  }
}
