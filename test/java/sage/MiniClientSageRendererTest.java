package sage;

import java.util.HashMap;
import java.util.Map;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class MiniClientSageRendererTest
{
  @Test
  public void androidNativeSurfaceIdsAreRecognized()
  {
    assertTrue(MiniClientSageRenderer.isAndroidNativeSurfaceId("android_media3"));
    assertTrue(MiniClientSageRenderer.isAndroidNativeSurfaceId("ANDROID_IJK"));
    assertFalse(MiniClientSageRenderer.isAndroidNativeSurfaceId("pwa_avplay"));
    assertFalse(MiniClientSageRenderer.isAndroidNativeSurfaceId(null));
  }

  @Test
  public void clientTypeDoesNotImplyLinkQuality()
  {
    assertEquals(MiniClientSageRenderer.resolveMiniClientReplyTimeoutMs(
        false, true, 60000L, 10000L), 60000L);
    assertEquals(MiniClientSageRenderer.resolveMiniClientReplyTimeoutMs(
        false, false, 60000L, 10000L), 60000L);
  }

  @Test
  public void callingThreadDoesNotShortenReplyTimeout()
  {
    assertEquals(MiniClientSageRenderer.resolveMiniClientReplyTimeoutMs(
        true, false, 60000L, 30000L), 60000L);
    assertEquals(MiniClientSageRenderer.resolveMiniClientReplyTimeoutMs(
        true, true, 60000L, 30000L), 60000L);
  }

  @Test
  public void replyTimeoutHasAOneSecondSafetyFloor()
  {
    assertEquals(MiniClientSageRenderer.resolveMiniClientReplyTimeoutMs(
        false, true, 500L, 0L), 1000L);
    assertEquals(MiniClientSageRenderer.resolveMiniClientReplyTimeoutMs(
        false, false, -1L, 10000L), 1000L);
  }

  @Test
  public void staleCleanupCannotRemoveReplacementSocket()
  {
    Map sockets = new HashMap();
    Map times = new HashMap();
    Object oldSocket = new Object();
    Object newSocket = new Object();

    MiniClientSageRenderer.replaceMappedSocket(sockets, times,
        "client", oldSocket, 10L);
    MiniClientSageRenderer.replaceMappedSocket(sockets, times,
        "client", newSocket, 20L);

    assertFalse(MiniClientSageRenderer.removeMappedSocketIfSame(
        sockets, times, "client", oldSocket));
    assertEquals(sockets.get("client"), newSocket);
    assertEquals(times.get("client"), Long.valueOf(20L));
  }

  @Test
  public void ownerCanRemoveItsMappedSocket()
  {
    Map sockets = new HashMap();
    Map times = new HashMap();
    Object socket = new Object();

    MiniClientSageRenderer.replaceMappedSocket(sockets, times,
        "client", socket, 10L);

    assertTrue(MiniClientSageRenderer.removeMappedSocketIfSame(
        sockets, times, "client", socket));
    assertFalse(sockets.containsKey("client"));
    assertFalse(times.containsKey("client"));
  }

  @Test
  public void healthyPlayerSocketRequiresAnExpectedReconnectToBeReplaced()
  {
    assertFalse(MiniClientSageRenderer.shouldReplacePlayerSocket(true, false),
        "A delayed duplicate must not evict a healthy mapped player channel");
    assertTrue(MiniClientSageRenderer.shouldReplacePlayerSocket(true, true),
        "A server-requested media reconnect may replace the prior channel");
    assertTrue(MiniClientSageRenderer.shouldReplacePlayerSocket(false, false),
        "A missing or unusable channel can always be replaced");
  }

  @Test
  public void duplicateReconnectCannotReplaceTheAcceptedHealthyTransport()
  {
    assertTrue(MiniClientSageRenderer.shouldAcceptReconnectSocket(false, true),
        "The first proactive reconnect must be accepted");
    assertFalse(MiniClientSageRenderer.shouldAcceptReconnectSocket(true, true),
        "A delayed duplicate must not replace the newly attached transport");
    assertTrue(MiniClientSageRenderer.shouldAcceptReconnectSocket(true, false),
        "A failed reconnect transport may be replaced");
  }

  @Test
  public void intentionalUiShutdownDoesNotEnterReconnectRecovery()
  {
    assertFalse(MiniClientSageRenderer.shouldAttemptReconnect(
        1L, false, true, true));
  }

  @Test
  public void unexpectedHealthyTransportFailureAllowsReconnectRecovery()
  {
    assertTrue(MiniClientSageRenderer.shouldAttemptReconnect(
        1L, false, true, false));
  }

  @Test
  public void transportFailuresHaveStableDiagnosticReasons()
  {
    assertEquals(MiniClientSageRenderer.transportClosureReason(
        new java.io.EOFException()), "peer_eof");
    assertEquals(MiniClientSageRenderer.transportClosureReason(
        new java.net.SocketTimeoutException("absolute timeout")), "absolute_timeout");
    assertEquals(MiniClientSageRenderer.transportClosureReason(
        new java.net.SocketTimeoutException("idle")), "idle_timeout");
    assertEquals(MiniClientSageRenderer.transportClosureReason(
        new java.net.SocketException("reset")), "peer_reset");
    assertEquals(MiniClientSageRenderer.transportClosureReason(
        new java.io.IOException("bad frame")), "protocol_error");
  }

  @Test
  public void zeroLengthReplyDecodesAsEmptyString() throws Exception
  {
    Class<?> replyType =
        Class.forName("sage.MiniClientSageRenderer$ReplyPacket");
    Constructor<?> constructor =
        replyType.getDeclaredConstructor(int.class, long.class, int.class, byte[].class);
    constructor.setAccessible(true);
    Object reply = constructor.newInstance(0, 0L, 0, null);
    Method getDataAsString = replyType.getDeclaredMethod("getDataAsString");
    getDataAsString.setAccessible(true);

    assertEquals(getDataAsString.invoke(reply), "",
        "A valid zero-length GET_PROPERTY reply is an empty value, not a missing reply");
  }
}
