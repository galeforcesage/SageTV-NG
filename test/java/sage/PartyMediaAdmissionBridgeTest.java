package sage;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

/** Real TCP-pair ownership tests without interpreting encrypted/compressed control bytes. */
public class PartyMediaAdmissionBridgeTest
{
  @BeforeClass public void initializeServerStatics() throws Throwable { TestUtils.initializeSageTVForTesting(); }

  static final class Pair implements AutoCloseable
  {
    final Socket bridge;
    final SocketChannel accepted;
    Pair() throws Exception
    {
      try (ServerSocketChannel listener = ServerSocketChannel.open())
      {
        listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        bridge = new Socket();
        bridge.connect(listener.getLocalAddress());
        accepted = listener.accept();
      }
    }
    @Override public void close() throws Exception { bridge.close(); accepted.close(); }
  }

  private static void channel(MiniClientSageRenderer renderer, SocketChannel value) throws Exception
  {
    java.lang.reflect.Field field = MiniClientSageRenderer.class.getDeclaredField("clientSocket");
    field.setAccessible(true); field.set(renderer, value);
  }

  private static PartyMediaAdmissionBridge.Context context(String id)
  { return new PartyMediaAdmissionBridge.Context(id, "epoch", "renderer", 42, "pull-xcode", 7, 1, 160000, "request", 4); }

  @Test public void actualPairClaimsOnceAndStillNeedsItsOwnBoundSuccessfulProducer() throws Exception
  {
    Object owner = new Object(); AtomicBoolean current = new AtomicBoolean(true);
    List<String> revoked = new ArrayList<>();
    try (Pair pair = new Pair(); Pair other = new Pair())
    {
      MiniClientSageRenderer renderer = mock(MiniClientSageRenderer.class, CALLS_REAL_METHODS);
      channel(renderer, pair.accepted);
      String handle = NgPartyMediaAdmission.INSTANCE.issue(owner, 42, "pull-xcode", null, current::get, revoked::add);
      NgPartyMediaAdmission.INSTANCE.describe(owner, handle, context("proof"), socket -> renderer.ownsPartyControlSocket(pair.accepted, socket));
      assertThrows(IllegalStateException.class, () -> PartyMediaAdmissionBridge.claim(handle, other.bridge));
      assertThrows(IllegalStateException.class, () -> PartyMediaAdmissionBridge.claim(handle, new Socket()));
      assertFalse(PartyMediaAdmissionBridge.isCurrent("proof", pair.bridge), "An unclaimed context is not bridge-owned");
      assertEquals(PartyMediaAdmissionBridge.claim(handle, pair.bridge), context("proof"));
      assertThrows(IllegalStateException.class, () -> PartyMediaAdmissionBridge.claim(handle, pair.bridge));
      assertTrue(PartyMediaAdmissionBridge.isCurrent("proof", pair.bridge));
      assertFalse(PartyMediaAdmissionBridge.isCurrent("proof", other.bridge));
      assertFalse(NgPartyMediaAdmission.INSTANCE.confirmed(owner), "Claim is not OPEN or actual media output");
      Object producer = new Object(); AtomicBoolean output = new AtomicBoolean(false);
      assertTrue(NgPartyMediaAdmission.INSTANCE.bind(handle, 42, "pull-xcode", null, producer, output::get));
      assertFalse(NgPartyMediaAdmission.INSTANCE.confirmed(owner));
      output.set(true); assertTrue(NgPartyMediaAdmission.INSTANCE.confirmed(owner));
      assertFalse(NgPartyMediaAdmission.INSTANCE.bind(handle, 42, "pull-xcode", null, new Object(), () -> true));
      PartyMediaAdmissionBridge.release("proof", other.bridge);
      assertTrue(PartyMediaAdmissionBridge.isCurrent("proof", pair.bridge));
      PartyMediaAdmissionBridge.release("proof", pair.bridge);
      assertEquals(revoked, List.of("BRIDGE_RELEASED"));
      assertFalse(PartyMediaAdmissionBridge.isCurrent("proof", pair.bridge));
      assertThrows(IllegalStateException.class, () -> PartyMediaAdmissionBridge.claim(handle, pair.bridge));
      assertFalse(NgPartyMediaAdmission.INSTANCE.bind(handle, 42, "pull-xcode", null, producer, () -> true));
    }
    finally { NgPartyMediaAdmission.INSTANCE.revoke(owner); }
  }

  @Test public void replacedOrClosedSocketAndGenerationChangesCannotReviveProof() throws Exception
  {
    for (String invalidation : List.of("replace", "close", "generation"))
    {
      Object owner = new Object(); AtomicBoolean current = new AtomicBoolean(true);
      try (Pair pair = new Pair(); Pair other = new Pair())
      {
        MiniClientSageRenderer renderer = mock(MiniClientSageRenderer.class, CALLS_REAL_METHODS);
        channel(renderer, pair.accepted);
        String handle = NgPartyMediaAdmission.INSTANCE.issue(owner, 42, "pull", null, current::get);
        NgPartyMediaAdmission.INSTANCE.describe(owner, handle, context("proof-" + invalidation),
            socket -> renderer.ownsPartyControlSocket(pair.accepted, socket));
        PartyMediaAdmissionBridge.Context context = PartyMediaAdmissionBridge.claim(handle, pair.bridge);
        if ("replace".equals(invalidation)) channel(renderer, other.accepted);
        else if ("close".equals(invalidation)) pair.bridge.close();
        else current.set(false);
        assertFalse(PartyMediaAdmissionBridge.isCurrent(context.contextId(), pair.bridge));
        current.set(true); channel(renderer, pair.accepted);
        assertFalse(PartyMediaAdmissionBridge.isCurrent(context.contextId(), pair.bridge));
        assertThrows(IllegalStateException.class, () -> PartyMediaAdmissionBridge.claim(handle, pair.bridge));
      }
      finally { NgPartyMediaAdmission.INSTANCE.revoke(owner); }
    }
  }

  @Test public void concurrentClaimAndRetirementNeverReviveOwnership() throws Exception
  {
    java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(2);
    try
    {
      for (int attempt = 0; attempt < 18; attempt++)
      {
        NgPartyMediaAdmission registry = new NgPartyMediaAdmission(() -> 100000);
        Object owner = new Object(); AtomicBoolean current = new AtomicBoolean(true);
        int retirement = attempt % 3;
        try (Pair pair = new Pair())
        {
          String handle = registry.issue(owner, 42, "pull-xcode", null, current::get);
          registry.describe(owner, handle, context("race"), socket -> socket == pair.bridge);
          java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
          java.util.concurrent.Future<PartyMediaAdmissionBridge.Context> claim = workers.submit(() -> {
            start.await(); return registry.claim(handle, pair.bridge);
          });
          java.util.concurrent.Future<?> retire = workers.submit(() -> {
            start.await();
            if (retirement == 0) registry.revoke(owner);
            else if (retirement == 1) current.set(false);
            else pair.bridge.close();
            return null;
          });
          start.countDown();
          claim.get(5, java.util.concurrent.TimeUnit.SECONDS);
          retire.get(5, java.util.concurrent.TimeUnit.SECONDS);
          assertFalse(registry.bridgeCurrent("race", pair.bridge));
          current.set(true);
          assertNull(registry.claim(handle, pair.bridge), "An invalidated generation/registration cannot revive");
          assertFalse(registry.confirmed(owner));
        }
      }
    }
    finally { workers.shutdownNow(); }
  }

  @Test public void expiredUnusedProofAndReplacedGrantCannotBeClaimed() throws Exception
  {
    java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(100000);
    NgPartyMediaAdmission registry = new NgPartyMediaAdmission(now::get);
    Object owner = new Object(); List<String> revoked = new ArrayList<>();
    try (Pair pair = new Pair())
    {
      String old = registry.issue(owner, 42, "pull", null, () -> true, revoked::add);
      registry.describe(owner, old, context("old"), socket -> socket == pair.bridge);
      now.addAndGet(60000);
      assertNull(registry.claim(old, pair.bridge));
      assertEquals(revoked, List.of("EXPIRED"));
      String next = registry.issue(owner, 42, "pull", null, () -> true, revoked::add);
      registry.describe(owner, next, context("new"), socket -> socket == pair.bridge);
      registry.issue(owner, 42, "pull", null, () -> true);
      assertNull(registry.claim(next, pair.bridge));
      assertEquals(revoked, List.of("EXPIRED", "REPLACED"));
    }
  }

  @Test public void boundProducerSurvivesStartupTtlButNotSurfaceRestoration() throws Exception
  {
    java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(100000);
    NgPartyMediaAdmission registry = new NgPartyMediaAdmission(now::get);
    Object owner = new Object(); List<String> revoked = new ArrayList<>();
    try (Pair pair = new Pair())
    {
      MiniClientSageRenderer renderer = mock(MiniClientSageRenderer.class, CALLS_REAL_METHODS);
      java.lang.reflect.Field lock = MiniClientSageRenderer.class.getDeclaredField("partySurfaceLock");
      lock.setAccessible(true); lock.set(renderer, new Object());
      channel(renderer, pair.accepted);
      sage.client.PlaybackSurfaceSet selected = sage.client.PlaybackSurfaceSet.build("mse",
          id -> new String[] {"mse", "1", "pull-xcode", "H264", "AAC", "MP4"});
      renderer.selectPartySurface(selected, 0);
      String handle = registry.issue(owner, 42, "pull-xcode", null,
          () -> renderer.getPartyPathGeneration() == 1, revoked::add);
      registry.describe(owner, handle, context("active"), socket -> renderer.ownsPartyControlSocket(pair.accepted, socket));
      assertNotNull(registry.claim(handle, pair.bridge));
      assertTrue(registry.bind(handle, 42, "pull-xcode", null, new Object(), () -> true));
      now.addAndGet(120000);
      assertTrue(registry.bridgeCurrent("active", pair.bridge), "An active bound producer outlives startup TTL");
      assertTrue(registry.confirmed(owner));
      renderer.restorePartySurface();
      assertFalse(registry.bridgeCurrent("active", pair.bridge));
      assertEquals(revoked, List.of("GENERATION_CHANGED"));
      assertFalse(registry.confirmed(owner));
    }
  }
}
