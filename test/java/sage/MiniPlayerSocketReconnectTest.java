package sage;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

/**
 * Tests for {@link MiniPlayer#acquirePlayerSocketChannel(MiniPlayer.PlayerSocketProvider)},
 * the bounded player-socket-channel self-heal used by {@code initDriver0()}.
 * <p>
 * Root-caused live incident: a client-side decode failure on one file (e.g. an
 * unplayable DIRECT_PLAY decision for an MPEG2-PS/AC3 file the PWA client
 * couldn't actually decode) tore down the shared player-socket-channel. With
 * no self-heal, every subsequent playback attempt in the SAME session --
 * including an entirely unrelated, perfectly playable HEVC file -- failed
 * immediately because {@code initDriver0()} saw a null channel and gave up.
 * These tests exercise the bounded retry policy in isolation via a fake
 * {@link MiniPlayer.PlayerSocketProvider}, without any live client/socket.
 */
public class MiniPlayerSocketReconnectTest
{
  private static final String ATTEMPTS_PROP = "miniplayer/player_socket_reconnect_attempts";
  private static final String BACKOFF_PROP = "miniplayer/player_socket_reconnect_backoff_ms";

  private ServerSocketChannel serverChannel;
  private SocketChannel acceptedChannel;

  @AfterMethod
  public void cleanup() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.remove(ATTEMPTS_PROP);
    Sage.remove(BACKOFF_PROP);
    if (acceptedChannel != null) try { acceptedChannel.close(); } catch (Exception e) {}
    if (serverChannel != null) try { serverChannel.close(); } catch (Exception e) {}
  }

  /**
   * A real, connected loopback channel -- used to stand in for "the client
   * successfully (re)established its player-socket-channel" without touching
   * any live SageTV client.
   */
  private SocketChannel openUsableChannel() throws Exception
  {
    serverChannel = ServerSocketChannel.open();
    serverChannel.bind(new InetSocketAddress("127.0.0.1", 0));
    SocketChannel client = SocketChannel.open(
        new InetSocketAddress("127.0.0.1", serverChannel.socket().getLocalPort()));
    acceptedChannel = serverChannel.accept();
    return client;
  }

  /** Fake provider recording calls; queues up channels to hand back in order. */
  private static final class FakeProvider implements MiniPlayer.PlayerSocketProvider
  {
    private final java.util.List<SocketChannel> channelsToReturn = new java.util.ArrayList<SocketChannel>();
    private int getChannelCalls = 0;
    private int reconnectCalls = 0;

    void enqueue(SocketChannel sc) { channelsToReturn.add(sc); }

    public SocketChannel getChannel()
    {
      getChannelCalls++;
      if (channelsToReturn.isEmpty())
        return null;
      return channelsToReturn.remove(0);
    }

    public void requestReconnect()
    {
      reconnectCalls++;
    }
  }

  @Test
  public void testNullProviderReturnsNullSafely() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    assertNull(MiniPlayer.acquirePlayerSocketChannel(null));
  }

  /**
   * (a) A null channel triggers a reconnect request: with the default policy
   * (2 attempts) and a provider that never has a channel, exactly one
   * reconnect request must be made (between attempt 1 and attempt 2) before
   * the bounded retries are exhausted.
   */
  @Test
  public void testDeadChannelTriggersReconnectRequest() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.put(BACKOFF_PROP, "0"); // don't slow the test down
    FakeProvider provider = new FakeProvider();
    // No channels enqueued -> getChannel() always returns null.

    SocketChannel result = MiniPlayer.acquirePlayerSocketChannel(provider);

    assertNull(result, "Exhausted retries must return null, not hang or throw");
    assertEquals(provider.getChannelCalls, 2, "Default policy is 2 attempts");
    assertEquals(provider.reconnectCalls, 1, "Reconnect must be requested exactly once between the 2 attempts");
  }

  /**
   * (b) After a simulated reconnect, the NEXT acquisition attempt succeeds --
   * proving the self-heal actually restores a usable channel rather than
   * papering over the null. This mirrors the live scenario: first attempt
   * dead (from the earlier MPEG failure), reconnect requested, second
   * attempt (e.g. for the HEVC file) gets a fresh working channel.
   */
  @Test
  public void testSucceedsAfterSimulatedReconnect() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.put(BACKOFF_PROP, "0");
    FakeProvider provider = new FakeProvider();
    provider.enqueue(null); // first attempt: still dead
    SocketChannel usable = openUsableChannel();
    provider.enqueue(usable); // second attempt: reconnect succeeded

    SocketChannel result = MiniPlayer.acquirePlayerSocketChannel(provider);

    assertSame(result, usable, "Must return the channel obtained after the reconnect");
    assertEquals(provider.reconnectCalls, 1, "Exactly one reconnect request should precede the successful retry");
  }

  /**
   * A channel that is present but not actually usable (open yet never
   * connected -- standing in for a stale/dead pooled entry) must be treated
   * the same as null: discarded, and retried rather than handed back to the
   * caller as if it were good.
   */
  @Test
  public void testDeadButPresentChannelIsTreatedAsUnusableAndRetried() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.put(BACKOFF_PROP, "0");
    FakeProvider provider = new FakeProvider();
    SocketChannel deadButPresent = SocketChannel.open(); // open() but never connect()ed
    assertTrue(deadButPresent.isOpen());
    assertFalse(deadButPresent.isConnected());
    provider.enqueue(deadButPresent);
    SocketChannel usable = openUsableChannel();
    provider.enqueue(usable);

    SocketChannel result = MiniPlayer.acquirePlayerSocketChannel(provider);

    assertSame(result, usable);
    assertFalse(deadButPresent.isOpen(), "The unusable channel should have been closed, not leaked");
  }

  /**
   * (c) Bounded retries: exhausting all attempts must fail cleanly and
   * quickly -- no hang, no infinite loop, no busy-spin. Verified by asserting
   * on the exact call counts (not just wall-clock time) so the test itself
   * stays fast and deterministic.
   */
  @Test
  public void testExhaustedRetriesFailCleanlyWithBoundedAttempts() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.put(ATTEMPTS_PROP, "3");
    Sage.put(BACKOFF_PROP, "0");
    FakeProvider provider = new FakeProvider();

    long start = System.currentTimeMillis();
    SocketChannel result = MiniPlayer.acquirePlayerSocketChannel(provider);
    long elapsedMs = System.currentTimeMillis() - start;

    assertNull(result);
    assertEquals(provider.getChannelCalls, 3, "Must stop at exactly the configured attempt count");
    assertEquals(provider.reconnectCalls, 2, "Reconnect requested between attempts, never after the last one");
    assertTrue(elapsedMs < 5000, "Bounded retry with zero backoff must not hang (took " + elapsedMs + "ms)");
  }

  /**
   * Setting the attempts property to 1 fully restores the pre-fix behavior:
   * give up immediately on the first miss, with no reconnect request at all.
   * This is the live kill-switch for reverting to the old behavior without a
   * rebuild.
   */
  @Test
  public void testSingleAttemptPropertyDisablesRetryEntirely() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.put(ATTEMPTS_PROP, "1");
    FakeProvider provider = new FakeProvider();

    SocketChannel result = MiniPlayer.acquirePlayerSocketChannel(provider);

    assertNull(result);
    assertEquals(provider.getChannelCalls, 1);
    assertEquals(provider.reconnectCalls, 0, "No reconnect should be requested when only 1 attempt is configured");
  }
}
