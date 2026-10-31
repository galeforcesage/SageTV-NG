package sage;

import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Correlates a prospective ordinary NG media open with its bridge-owned graphics socket.
 * This same-JVM API does not authenticate HTTP, open media, or prove producer retirement.
 */
public final class NativePlaybackOwnershipBridge
{
  static final Registry REGISTRY = new Registry(() -> System.nanoTime() / 1000000);
  static final String CAPABILITY = "NATIVE_PLAYBACK_OWNERSHIP_V1";

  private NativePlaybackOwnershipBridge() {}

  /**
   * Immutable server-selected ordinary resource context, private to the trusted bridge.
   *
   * @param contextId random one-open context identifier
   * @param serverEpoch process-local monotonic epoch identifier
   * @param rendererHandle ephemeral opaque renderer handle
   * @param mediaFileId actual selected MediaFile identifier
   * @param segmentIndex actual selected zero-based segment
   * @param resourcePath actual selected segment's absolute path, never browser authority or logging data
   * @param surface actual selected NG surface
   * @param mode actual selected delivery, {@code pull} or {@code pull-xcode}
   * @param xcodeMode exact server-selected XCODE_SETUP mode, null for direct pull
   * @param playbackGeneration exact MiniPlayer load generation
   * @param pathGeneration exact renderer surface generation
   * @param expiresAtServerMonoMs unclaimed startup deadline in the server monotonic domain
   * @param durationMs selected segment duration and upper bound for ordinary transport positioning
   * @param ownershipGeneration same-resource delivery generation, initially zero
   */
  public record Context(String contextId, String serverEpoch, String rendererHandle,
      int mediaFileId, int segmentIndex, String resourcePath, String surface, String mode,
      String xcodeMode, long playbackGeneration, long pathGeneration,
      long expiresAtServerMonoMs, long durationMs, long ownershipGeneration) {}

  /**
   * Consumes the single bridge claim for an authorized, prospective ordinary OPEN intent.
   *
   * @param upstreamControlSocket actual live graphics socket owned by this bridge
   * @return server-selected resource context, not a loaded-player or HTTP admission receipt
   * @throws IllegalStateException if no unique current eligible intent matches the actual socket
   */
  public static Context claim(Socket upstreamControlSocket)
  {
    Context context = REGISTRY.claim(upstreamControlSocket);
    if (context == null) throw new IllegalStateException("NATIVE_ASSOCIATION_DENIED");
    return context;
  }

  /**
   * Checks a claimed context's actual control ownership and current playback lifecycle.
   *
   * @param contextId server-generated context identifier
   * @param upstreamControlSocket the identical socket object used for claim
   * @return whether authorization remains current, never whether a producer has terminated
   */
  public static boolean isCurrent(String contextId, Socket upstreamControlSocket)
  { return REGISTRY.isCurrent(contextId, upstreamControlSocket); }

  /**
   * Consumes the latest same-resource context after the bridge has completed actual producer retirement.
   * This method checks authorization, not the bridge's teardown; the bridge must await its real terminal future.
   *
   * @param previousContextId latest released context, not previously reissued
   * @param upstreamControlSocket the identical bridge-owned graphics socket
   * @return a fresh context for the unchanged server resource and next delivery generation
   * @throws IllegalStateException if the original intent is no longer current or the context was consumed
   */
  public static Context reissue(String previousContextId, Socket upstreamControlSocket)
  {
    Context context = REGISTRY.reissue(previousContextId, upstreamControlSocket);
    if (context == null) throw new IllegalStateException("NATIVE_ASSOCIATION_DENIED");
    return context;
  }

  /**
   * Retires this context's authorization, without asserting delivery-resource teardown.
   * A bounded tombstone permits explicit same-resource reissue while the original OPEN intent remains current.
   *
   * @param contextId server-generated context identifier
   * @param upstreamControlSocket the identical socket object used for claim
   */
  public static void release(String contextId, Socket upstreamControlSocket)
  { REGISTRY.release(contextId, upstreamControlSocket); }

  static final class Registry
  {
    private final LongSupplier time;
    private final SecureRandom random = new SecureRandom();
    private final String epoch = opaque();
    private final Map<Object, Entry> entries = new IdentityHashMap<>();

    private static final class Entry
    {
      final MiniClientSageRenderer renderer;
      final SocketChannel control;
      final BooleanSupplier current;
      Context context;
      Socket claimedSocket;
      boolean retired;
      long retiredUntil;

      Entry(MiniClientSageRenderer renderer, SocketChannel control, BooleanSupplier current, Context context)
      { this.renderer = renderer; this.control = control; this.current = current; this.context = context; }
    }

    Registry(LongSupplier time) { this.time = time; }

    private String opaque()
    {
      byte[] bytes = new byte[24]; random.nextBytes(bytes);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    synchronized boolean publish(Object player, MiniClientSageRenderer renderer, int media, int segment,
        String path, String surface, String mode, String xcodeMode, long generation, long pathGeneration,
        long duration, BooleanSupplier current)
    {
      if (renderer.getPartyPrincipal() == null || !renderer.isNgCapableSession()
          || !renderer.hasClientCapability(CAPABILITY)
          || media <= 0 || segment < 0 || path == null || path.isEmpty() || surface == null || surface.isEmpty()
          || duration <= 0 || mode == null || !java.util.Set.of("pull", "pull-xcode").contains(mode)
          || "pull-xcode".equals(mode) && (xcodeMode == null || xcodeMode.isEmpty())
          || !current.getAsBoolean())
        return false;
      entries.remove(player);
      prune();
      if (entries.size() >= 80) return false;
      SocketChannel control = renderer.partyControlChannel();
      if (control == null || !control.isOpen() || !control.isConnected()) return false;
      String rendererHandle = null;
      for (Entry entry : entries.values())
        if (entry.renderer == renderer) { rendererHandle = entry.context.rendererHandle(); break; }
      Context context = new Context(opaque(), epoch, rendererHandle == null ? opaque() : rendererHandle,
          media, segment, path, surface, mode, xcodeMode, generation, pathGeneration,
          time.getAsLong() + 60000, duration, 0);
      entries.put(player, new Entry(renderer, control, current, context));
      return true;
    }

    synchronized Context claim(Socket socket)
    {
      prune();
      Entry match = null;
      for (Entry entry : entries.values())
        if (entry.renderer.ownsPartyControlSocket(entry.control, socket))
        {
          if (match != null) return null;
          match = entry;
        }
      if (match == null || match.claimedSocket != null || match.context.expiresAtServerMonoMs() <= time.getAsLong())
        return null;
      match.claimedSocket = socket;
      return match.context;
    }

    synchronized boolean isCurrent(String contextId, Socket socket)
    {
      prune();
      Entry entry = find(contextId, socket);
      return entry != null && !entry.retired;
    }

    synchronized Context reissue(String contextId, Socket socket)
    {
      prune();
      Entry entry = find(contextId, socket);
      if (entry == null || !entry.retired || entry.context.ownershipGeneration() == Long.MAX_VALUE) return null;
      Context old = entry.context;
      entry.context = new Context(opaque(), epoch, old.rendererHandle(), old.mediaFileId(), old.segmentIndex(),
          old.resourcePath(), old.surface(), old.mode(), old.xcodeMode(), old.playbackGeneration(),
          old.pathGeneration(), time.getAsLong() + 60000, old.durationMs(), old.ownershipGeneration() + 1);
      entry.retired = false;
      return entry.context;
    }

    synchronized void release(String contextId, Socket socket)
    {
      Entry match = find(contextId, socket);
      if (match != null && !match.retired)
      { match.retired = true; match.retiredUntil = time.getAsLong() + 60000; }
    }

    synchronized void revokePlayer(Object player) { entries.remove(player); }

    synchronized void revokeRenderer(MiniClientSageRenderer renderer)
    { entries.values().removeIf(entry -> entry.renderer == renderer); }

    private Entry find(String contextId, Socket socket)
    {
      if (contextId == null || socket == null) return null;
      for (Entry entry : entries.values())
        if (entry.claimedSocket == socket && contextId.equals(entry.context.contextId())) return entry;
      return null;
    }

    private void prune()
    {
      long now = time.getAsLong();
      entries.values().removeIf(entry -> !entry.current.getAsBoolean()
          || entry.renderer.getPartyPrincipal() == null || !entry.renderer.isNgCapableSession()
          || !entry.renderer.hasClientCapability(CAPABILITY)
          || entry.renderer.partyControlChannel() != entry.control || !entry.control.isOpen() || !entry.control.isConnected()
          || entry.retired && entry.retiredUntil <= now
          || entry.claimedSocket == null && entry.context.expiresAtServerMonoMs() <= now
          || entry.claimedSocket != null && !entry.renderer.ownsPartyControlSocket(entry.control, entry.claimedSocket));
    }
  }
}
