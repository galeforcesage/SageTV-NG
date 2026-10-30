package sage;

import java.net.Socket;

/**
 * Same-JVM ownership verification for a thin bridge's existing renderer control socket.
 * The bridge must pass its actual connected upstream socket, never HTTP-supplied endpoints.
 * This API neither opens media nor reserves resources; PARTY_BIND and actual output remain required.
 */
public final class PartyMediaAdmissionBridge
{
  private PartyMediaAdmissionBridge() {}

  /**
   * Immutable server-built context; identifiers are ephemeral, not device or human identities.
   *
   * @param contextId random admission-context identifier
   * @param serverEpoch current server monotonic-clock epoch
   * @param sessionHandle opaque owning renderer session handle
   * @param mediaFileId canonical joined media identifier
   * @param mode actual server-selected delivery mode
   * @param playbackGeneration exact loaded MiniPlayer generation
   * @param pathGeneration renderer-only playback-surface generation
   * @param expiresAtServerMonoMs unused admission startup deadline, not an active producer deadline
   * @param requestId original MEDIA_ADMISSION request identifier
   * @param requestSeq original MEDIA_ADMISSION request sequence
   */
  public record Context(String contextId, String serverEpoch, String sessionHandle, int mediaFileId,
      String mode, long playbackGeneration, long pathGeneration, long expiresAtServerMonoMs,
      String requestId, long requestSeq) {}

  /**
   * Consumes one bridge-ownership claim for an existing admission handle.
   * @param handle opaque server admission handle received on the owning control session
   * @param upstreamControlSocket bridge-owned live upstream graphics socket for that session
   * @return the immutable server-owned context
   * @throws IllegalStateException if ownership is unsupported, stale, already claimed or mismatched
   */
  public static Context claim(String handle, Socket upstreamControlSocket)
  {
    Context result = NgPartyMediaAdmission.INSTANCE.claim(handle, upstreamControlSocket);
    if (result == null) throw new IllegalStateException("PARTY_ASSOCIATION_DENIED");
    return result;
  }

  /**
   * Checks actual ownership and media/path generation before using a claimed coordinator.
   * @param contextId server-generated admission context identifier
   * @param upstreamControlSocket the same socket object passed to the successful claim
   * @return whether the claimed context is still current
   */
  public static boolean isCurrent(String contextId, Socket upstreamControlSocket)
  { return NgPartyMediaAdmission.INSTANCE.bridgeCurrent(contextId, upstreamControlSocket); }

  /**
   * Revokes this bridge's claimed context on retirement; it cannot be claimed again.
   * @param contextId server-generated admission context identifier
   * @param upstreamControlSocket the same socket object passed to the successful claim
   */
  public static void release(String contextId, Socket upstreamControlSocket)
  { NgPartyMediaAdmission.INSTANCE.releaseBridge(contextId, upstreamControlSocket); }
}
