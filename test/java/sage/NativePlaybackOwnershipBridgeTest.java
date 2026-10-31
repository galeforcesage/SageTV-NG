package sage;

import java.io.File;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

/** Prospective ordinary resource ownership without native HTTP or control-frame parsing. */
public class NativePlaybackOwnershipBridgeTest
{
  @BeforeClass public void initializeServerStatics() throws Throwable { TestUtils.initializeSageTVForTesting(); }

  private static void field(Object target, Class<?> type, String name, Object value) throws Exception
  {
    Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
  }

  private static MiniClientSageRenderer renderer(PartyMediaAdmissionBridgeTest.Pair pair) throws Exception
  {
    MiniClientSageRenderer renderer = mock(MiniClientSageRenderer.class, CALLS_REAL_METHODS);
    field(renderer, MiniClientSageRenderer.class, "partySurfaceLock", new Object());
    field(renderer, MiniClientSageRenderer.class, "clientSocket", pair.accepted);
    doReturn("admitted").when(renderer).getPartyPrincipal();
    doReturn(true).when(renderer).isNgCapableSession();
    doReturn(true).when(renderer).hasClientCapability(NativePlaybackOwnershipBridge.CAPABILITY);
    doReturn("pwa_native").when(renderer).getCurrentSurfaceId();
    doReturn("pull-xcode").when(renderer).getCurrentSurfaceDeliveryMode();
    return renderer;
  }

  private static boolean publish(NativePlaybackOwnershipBridge.Registry registry, Object player,
      MiniClientSageRenderer renderer, AtomicBoolean current)
  {
    return registry.publish(player, renderer, 42, 1, new File("selected-segment.ts").getAbsolutePath(),
        "pwa_native", "pull-xcode", "mpeg2tsremux", 7, 0, 900000, current::get);
  }

  @Test public void actualOpenBoundaryPublishesBeforeClientReplyAndFailureRevokes() throws Exception
  {
    try (PartyMediaAdmissionBridgeTest.Pair pair = new PartyMediaAdmissionBridgeTest.Pair())
    {
      MiniClientSageRenderer renderer = renderer(pair);
      MiniPlayer player = spy(new MiniPlayer());
      field(player, MiniPlayer.class, "mcsr", renderer);
      field(player, MiniPlayer.class, "partyXcodeMode", "mpeg2tsremux");
      VideoFrame frame = mock(VideoFrame.class); MediaFile media = mock(MediaFile.class);
      File selected = new File("selected-segment.ts");
      doReturn(player).when(frame).getPartyPlayer(); doReturn(media).when(frame).getCurrFile();
      doReturn(42).when(media).getID(); doReturn(2).when(media).getNumSegments();
      doReturn(new File("other-segment.ts")).when(media).getFile(0);
      doReturn(selected).when(media).getFile(1); doReturn(900000L).when(media).getDuration(1);
      AtomicBoolean success = new AtomicBoolean(true);
      final NativePlaybackOwnershipBridge.Context[] claimed = new NativePlaybackOwnershipBridge.Context[1];
      doAnswer(call -> {
        claimed[0] = NativePlaybackOwnershipBridge.claim(pair.bridge);
        assertTrue(player.getState() == MediaPlayer.NO_STATE || player.getState() == MediaPlayer.STOPPED_STATE,
            "Claim must not wait for LOADED or OPENURL reply");
        assertEquals(claimed[0].resourcePath(), selected.getAbsolutePath());
        assertEquals(claimed[0].segmentIndex(), 1);
        assertEquals(claimed[0].mode(), "pull-xcode");
        assertEquals(claimed[0].xcodeMode(), "mpeg2tsremux");
        assertEquals(claimed[0].durationMs(), 900000);
        return success.get();
      }).when(player).openURL0(anyString());
      try
      {
        long intent = player.invalidateNativeOwnership();
        assertTrue(player.openOrdinaryURL0("normal-original-url", media, selected, frame, intent));
        assertTrue(NativePlaybackOwnershipBridge.isCurrent(claimed[0].contextId(), pair.bridge));
        assertThrows(IllegalStateException.class, () -> NativePlaybackOwnershipBridge.claim(pair.bridge));
        long generation = player.getPartyPlaybackGeneration();
        player.stop();
        assertEquals(player.getPartyPlaybackGeneration(), generation, "Stop has a separate ownership fence");
        assertFalse(NativePlaybackOwnershipBridge.isCurrent(claimed[0].contextId(), pair.bridge));
        assertThrows(IllegalStateException.class, () -> NativePlaybackOwnershipBridge.reissue(claimed[0].contextId(), pair.bridge));
        intent = player.invalidateNativeOwnership(); success.set(false);
        assertFalse(player.openOrdinaryURL0("normal-original-url", media, selected, frame, intent));
        assertFalse(NativePlaybackOwnershipBridge.isCurrent(claimed[0].contextId(), pair.bridge));
        intent = player.invalidateNativeOwnership(); player.stop();
        assertFalse(player.publishNativeOpenIntent(media, selected, frame, intent), "A late load cannot publish after Stop");
        doReturn(true).when(frame).isPartyProgressOwned();
        assertFalse(player.publishNativeOpenIntent(media, selected, frame, player.invalidateNativeOwnership()));
      }
      finally { player.invalidateNativeOwnership(); }
    }
  }

  @Test public void realOpenUrlWireWaitDoesNotBlockProspectiveClaim() throws Exception
  {
    java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    try (PartyMediaAdmissionBridgeTest.Pair gfx = new PartyMediaAdmissionBridgeTest.Pair();
        PartyMediaAdmissionBridgeTest.Pair mediaSocket = new PartyMediaAdmissionBridgeTest.Pair())
    {
      MiniClientSageRenderer renderer = renderer(gfx);
      MiniPlayer player = new MiniPlayer();
      field(player, MiniPlayer.class, "mcsr", renderer);
      field(player, MiniPlayer.class, "partyXcodeMode", "mpeg2tsremux");
      field(player, MiniPlayer.class, "clientSocket", mediaSocket.accepted);
      VideoFrame frame = mock(VideoFrame.class); MediaFile media = mock(MediaFile.class);
      File selected = new File("selected-segment.ts");
      doReturn(player).when(frame).getPartyPlayer(); doReturn(media).when(frame).getCurrFile();
      doReturn(42).when(media).getID(); doReturn(1).when(media).getNumSegments();
      doReturn(selected).when(media).getFile(0); doReturn(900000L).when(media).getDuration(0);
      mediaSocket.bridge.setSoTimeout(5000);
      try
      {
        long intent = player.invalidateNativeOwnership();
        java.util.concurrent.Future<Boolean> opened = worker.submit(
            () -> player.openOrdinaryURL0("normal-original-url", media, selected, frame, intent));
        java.io.DataInputStream input = new java.io.DataInputStream(mediaSocket.bridge.getInputStream());
        int header = input.readInt();
        assertEquals(header >>> 24, MiniPlayer.MEDIACMD_OPENURL);
        input.readFully(new byte[header & 0xFFFFFF]);
        assertFalse(opened.isDone(), "Server is waiting for the ordinary client OPENURL reply");
        NativePlaybackOwnershipBridge.Context context = NativePlaybackOwnershipBridge.claim(gfx.bridge);
        assertEquals(context.resourcePath(), selected.getAbsolutePath());
        assertTrue(NativePlaybackOwnershipBridge.isCurrent(context.contextId(), gfx.bridge));
        new java.io.DataOutputStream(mediaSocket.bridge.getOutputStream()).writeInt(1);
        assertTrue(opened.get(5, java.util.concurrent.TimeUnit.SECONDS));
        player.invalidateNativeOwnership();
        assertFalse(NativePlaybackOwnershipBridge.isCurrent(context.contextId(), gfx.bridge));
      }
      finally { player.invalidateNativeOwnership(); }
    }
    finally { worker.shutdownNow(); }
  }

  @Test public void exactSocketOneUseReissueAndRetirementDoNotAdoptOrChangeResources() throws Exception
  {
    AtomicLong now = new AtomicLong(100000);
    NativePlaybackOwnershipBridge.Registry registry = new NativePlaybackOwnershipBridge.Registry(now::get);
    AtomicBoolean current = new AtomicBoolean(true); Object player = new Object();
    try (PartyMediaAdmissionBridgeTest.Pair pair = new PartyMediaAdmissionBridgeTest.Pair();
        PartyMediaAdmissionBridgeTest.Pair other = new PartyMediaAdmissionBridgeTest.Pair())
    {
      MiniClientSageRenderer renderer = renderer(pair);
      assertNull(registry.claim(pair.bridge), "An existing unassociated producer has no intent");
      assertTrue(publish(registry, player, renderer, current));
      assertNull(registry.claim(other.bridge), "Same IP with different ports is not the renderer");
      NativePlaybackOwnershipBridge.Context first = registry.claim(pair.bridge);
      assertNotNull(first); assertNull(registry.claim(pair.bridge));
      assertEquals(first.ownershipGeneration(), 0);
      assertNull(registry.reissue(first.contextId(), pair.bridge), "Revoke before resource retirement/reissue");
      registry.release(first.contextId(), other.bridge);
      assertTrue(registry.isCurrent(first.contextId(), pair.bridge));
      registry.release(first.contextId(), pair.bridge);
      assertFalse(registry.isCurrent(first.contextId(), pair.bridge));
      assertNull(registry.reissue(first.contextId(), other.bridge));
      NativePlaybackOwnershipBridge.Context next = registry.reissue(first.contextId(), pair.bridge);
      assertNotNull(next); assertNotEquals(next.contextId(), first.contextId());
      assertEquals(next.ownershipGeneration(), 1);
      assertEquals(next.resourcePath(), first.resourcePath());
      assertEquals(next.mediaFileId(), first.mediaFileId());
      assertEquals(next.playbackGeneration(), first.playbackGeneration());
      assertEquals(next.xcodeMode(), first.xcodeMode());
      assertNull(registry.reissue(first.contextId(), pair.bridge), "Consumed context cannot replay");
      now.addAndGet(120000);
      assertTrue(registry.isCurrent(next.contextId(), pair.bridge), "Established ownership is not a startup lease");
      current.set(false); assertFalse(registry.isCurrent(next.contextId(), pair.bridge));
      current.set(true); assertNull(registry.reissue(next.contextId(), pair.bridge));
      assertNull(registry.claim(pair.bridge), "A stale intent never revives");
    }
  }

  @Test public void missingNegotiationAdmissionExpiryAmbiguityAndReplacedSocketFailClosed() throws Exception
  {
    AtomicLong now = new AtomicLong(100000);
    NativePlaybackOwnershipBridge.Registry registry = new NativePlaybackOwnershipBridge.Registry(now::get);
    AtomicBoolean current = new AtomicBoolean(true); Object player = new Object();
    try (PartyMediaAdmissionBridgeTest.Pair pair = new PartyMediaAdmissionBridgeTest.Pair();
        PartyMediaAdmissionBridgeTest.Pair other = new PartyMediaAdmissionBridgeTest.Pair())
    {
      MiniClientSageRenderer renderer = renderer(pair);
      doReturn(false).when(renderer).hasClientCapability(NativePlaybackOwnershipBridge.CAPABILITY);
      assertFalse(publish(registry, player, renderer, current));
      doReturn(true).when(renderer).hasClientCapability(NativePlaybackOwnershipBridge.CAPABILITY);
      doReturn(null).when(renderer).getPartyPrincipal();
      assertFalse(publish(registry, player, renderer, current));
      doReturn("admitted").when(renderer).getPartyPrincipal();
      doReturn(false).when(renderer).isNgCapableSession();
      assertFalse(publish(registry, player, renderer, current));
      doReturn(true).when(renderer).isNgCapableSession();
      assertTrue(publish(registry, player, renderer, current));
      now.addAndGet(60000); assertNull(registry.claim(pair.bridge));
      assertTrue(publish(registry, player, renderer, current));
      Object ambiguous = new Object(); assertTrue(publish(registry, ambiguous, renderer, current));
      assertNull(registry.claim(pair.bridge));
      registry.revokePlayer(ambiguous);
      NativePlaybackOwnershipBridge.Context context = registry.claim(pair.bridge);
      assertNotNull(context);
      field(renderer, MiniClientSageRenderer.class, "clientSocket", other.accepted);
      assertFalse(registry.isCurrent(context.contextId(), pair.bridge));
      field(renderer, MiniClientSageRenderer.class, "clientSocket", pair.accepted);
      assertNull(registry.reissue(context.contextId(), pair.bridge));
      assertTrue(publish(registry, player, renderer, current));
      context = registry.claim(pair.bridge);
      registry.revokeRenderer(renderer);
      assertFalse(registry.isCurrent(context.contextId(), pair.bridge));
      assertNull(registry.reissue(context.contextId(), pair.bridge));
      assertTrue(publish(registry, player, renderer, current));
      context = registry.claim(pair.bridge);
      registry.release(context.contextId(), pair.bridge);
      now.addAndGet(60000);
      assertNull(registry.reissue(context.contextId(), pair.bridge), "Retirement tombstones are bounded");
      assertTrue(publish(registry, player, renderer, current));
      context = registry.claim(pair.bridge); pair.bridge.close();
      assertFalse(registry.isCurrent(context.contextId(), pair.bridge));
      assertNull(registry.reissue(context.contextId(), pair.bridge));
    }
  }

  @Test public void generationSurfaceAndAdmissionTransitionsCannotReviveOldContexts() throws Exception
  {
    try (PartyMediaAdmissionBridgeTest.Pair pair = new PartyMediaAdmissionBridgeTest.Pair())
    {
      MiniClientSageRenderer renderer = renderer(pair);
      MiniPlayer player = spy(new MiniPlayer());
      field(player, MiniPlayer.class, "mcsr", renderer);
      field(player, MiniPlayer.class, "partyXcodeMode", "mpeg2tsremux");
      VideoFrame frame = mock(VideoFrame.class); MediaFile media = mock(MediaFile.class);
      File selected = new File("selected-segment.ts");
      doReturn(player).when(frame).getPartyPlayer(); doReturn(media).when(frame).getCurrFile();
      doReturn(42).when(media).getID(); doReturn(1).when(media).getNumSegments();
      doReturn(selected).when(media).getFile(0); doReturn(900000L).when(media).getDuration(0);
      try
      {
        for (String change : java.util.List.of("generation", "surface", "admission", "load", "free"))
        {
          long generation = player.getPartyPlaybackGeneration();
          assertTrue(player.publishNativeOpenIntent(media, selected, frame, player.invalidateNativeOwnership()));
          NativePlaybackOwnershipBridge.Context context = NativePlaybackOwnershipBridge.claim(pair.bridge);
          if ("generation".equals(change)) field(player, MiniPlayer.class, "partyPlaybackGeneration", generation + 1);
          else if ("surface".equals(change))
            renderer.selectPartySurface(sage.client.PlaybackSurfaceSet.build("mse",
                id -> new String[] {"mse", "1", "pull-xcode", "H264", "AAC", "MP4"}), renderer.getPartyPathGeneration());
          else if ("admission".equals(change)) doReturn(null).when(renderer).getPartyPrincipal();
          else if ("free".equals(change)) player.free();
          else player.invalidateNativeOwnership();
          assertFalse(NativePlaybackOwnershipBridge.isCurrent(context.contextId(), pair.bridge), change);
          field(player, MiniPlayer.class, "partyPlaybackGeneration", generation);
          renderer.restorePartySurface();
          doReturn("admitted").when(renderer).getPartyPrincipal();
          assertThrows(IllegalStateException.class, () -> NativePlaybackOwnershipBridge.reissue(context.contextId(), pair.bridge));
        }
      }
      finally { player.invalidateNativeOwnership(); }
    }
  }

  @Test public void registryCapacityAndConcurrentReissueRemainBoundedAndSingleUse() throws Exception
  {
    NativePlaybackOwnershipBridge.Registry registry = new NativePlaybackOwnershipBridge.Registry(() -> 100000);
    AtomicBoolean current = new AtomicBoolean(true);
    java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(2);
    try (PartyMediaAdmissionBridgeTest.Pair pair = new PartyMediaAdmissionBridgeTest.Pair())
    {
      MiniClientSageRenderer renderer = renderer(pair);
      Object[] players = new Object[80];
      for (int i = 0; i < players.length; i++)
      { players[i] = new Object(); assertTrue(publish(registry, players[i], renderer, current)); }
      assertFalse(publish(registry, new Object(), renderer, current));
      for (int i = 1; i < players.length; i++) registry.revokePlayer(players[i]);
      NativePlaybackOwnershipBridge.Context context = registry.claim(pair.bridge);
      assertFalse(publish(registry, players[0], renderer, new AtomicBoolean(false)));
      assertTrue(registry.isCurrent(context.contextId(), pair.bridge), "Late stale publication cannot erase a newer intent");
      for (String invalidMode : new String[] {null, "", "push", "httpls"})
        assertFalse(registry.publish(new Object(), renderer, 42, 0, "selected-segment.ts", "native",
            invalidMode, null, 7, 0, 900000, () -> true));
      registry.release(context.contextId(), pair.bridge);
      java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.Callable<NativePlaybackOwnershipBridge.Context> reissue = () -> {
        start.await(); return registry.reissue(context.contextId(), pair.bridge);
      };
      java.util.concurrent.Future<NativePlaybackOwnershipBridge.Context> a = workers.submit(reissue);
      java.util.concurrent.Future<NativePlaybackOwnershipBridge.Context> b = workers.submit(reissue);
      start.countDown();
      NativePlaybackOwnershipBridge.Context first = a.get(5, java.util.concurrent.TimeUnit.SECONDS);
      NativePlaybackOwnershipBridge.Context second = b.get(5, java.util.concurrent.TimeUnit.SECONDS);
      assertTrue((first == null) != (second == null), "Only one concurrent reissue can consume the tombstone");
      NativePlaybackOwnershipBridge.Context next = first == null ? second : first;
      registry.release(context.contextId(), pair.bridge);
      assertTrue(registry.isCurrent(next.contextId(), pair.bridge), "Old release cannot revoke the newer generation");
      registry.revokeRenderer(renderer);
      assertNull(registry.reissue(next.contextId(), pair.bridge));
    }
    finally { workers.shutdownNow(); }
  }
}
