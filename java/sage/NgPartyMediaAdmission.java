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
    Object producer;
    BooleanSupplier ready;
    BooleanSupplier failed = () -> false;
    Grant(int media, String mode, Object context, BooleanSupplier current, long expires)
    { this.media = media; this.mode = mode; this.context = context; this.current = current; this.expires = expires; }
  }

  NgPartyMediaAdmission(LongSupplier time) { this.time = time; }

  synchronized String issue(Object owner, int media, String mode, Object context, BooleanSupplier current)
  {
    revoke(owner);
    prune();
    if (grants.size() >= 80) throw new PartyProtocol.Fault("RESOURCE_DENIED");
    if (!current.getAsBoolean()) throw new PartyProtocol.Fault("MEDIA_PATH_NOT_READY");
    byte[] bytes = new byte[24];
    random.nextBytes(bytes);
    String handle = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    grants.put(handle, new Grant(media, mode, context, current, time.getAsLong() + 60000));
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

  synchronized void revoke(Object owner)
  {
    String handle = owners.remove(owner);
    if (handle != null) grants.remove(handle);
  }

  synchronized void closed(Object producer)
  {
    grants.entrySet().removeIf(entry -> entry.getValue().producer == producer);
    owners.values().removeIf(handle -> !grants.containsKey(handle));
  }

  private void prune()
  {
    long now = time.getAsLong();
    grants.entrySet().removeIf(entry -> !entry.getValue().current.getAsBoolean()
        || entry.getValue().failed.getAsBoolean()
        || entry.getValue().producer == null && entry.getValue().expires <= now);
    owners.values().removeIf(handle -> !grants.containsKey(handle));
  }

  static boolean transcodeReady(TranscodeEngine engine)
  {
    return engine != null && engine.getVirtualTranscodeSize() > 0
        && (engine.isTranscodeDone() ? engine instanceof FFMPEGTranscoder
            && ((FFMPEGTranscoder)engine).didTranscodeCompleteOK() : engine.isTranscoding());
  }
}
