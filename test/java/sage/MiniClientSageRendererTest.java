package sage;

import java.util.HashMap;
import java.util.Map;

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
}
