package sage.enhance;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import sage.Sage;
import sage.TestUtils;

/**
 * Lifecycle tests for {@link GpuGovernor}: releasing a session returns the box
 * to its idle baseline (Invariant 1: zero idle GPU footprint), and the
 * {@link GpuGovernor#reapStale()} backstop drops leaked reservations while a
 * heart-beating session is preserved.
 *
 * <p>These exercise bookkeeping only (no GPU, no ffmpeg) — which is exactly the
 * accounting that shrinks capacity for everyone if a reservation ever leaks.
 */
public class GpuGovernorTest
{
  private static final String PROP_STALE_MS    = "playback/gpu_enhance/session_stale_ms";
  private static final String PROP_REAP_INT_MS = "playback/gpu_enhance/reap_interval_ms";

  private GpuGovernor gov;

  @BeforeMethod
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    // Park the lazy reaper daemon so each test drives reapStale() deterministically.
    Sage.put(PROP_REAP_INT_MS, "600000");
    gov = GpuGovernor.getInstance();
    drainSessions();
  }

  @AfterMethod
  public void tearDown()
  {
    drainSessions();
    Sage.remove(PROP_STALE_MS);
    Sage.remove(PROP_REAP_INT_MS);
  }

  private void drainSessions()
  {
    for (String id : gov.activeSessionIds())
      gov.release(id);
  }

  private GpuGovernor.Session live(String id, EnhancementTier tier, long kbps)
  {
    return new GpuGovernor.Session(id, tier, 0, kbps, false);
  }

  @Test
  public void testReleaseReturnsToIdleBaseline()
  {
    assertTrue(gov.isIdle(), "governor must start idle");

    gov.trackSession(live("s-1", EnhancementTier.ENHANCE_2160P, 30000));
    assertFalse(gov.isIdle());
    assertEquals(gov.countLiveSessions(), 1);
    assertEquals(gov.countSessions(), 1);
    assertEquals(gov.totalBitrateKbps(), 30000L);
    assertEquals(gov.tierOf("s-1"), EnhancementTier.ENHANCE_2160P);

    gov.release("s-1");

    // Back to a clean baseline: no sessions, no reserved bitrate, tier NONE.
    assertTrue(gov.isIdle(), "release must return the box to idle");
    assertEquals(gov.countLiveSessions(), 0);
    assertEquals(gov.countSessions(), 0);
    assertEquals(gov.totalBitrateKbps(), 0L);
    assertEquals(gov.tierOf("s-1"), EnhancementTier.NONE);
  }

  @Test
  public void testReleaseIsIdempotent()
  {
    gov.trackSession(live("s-idem", EnhancementTier.ENHANCE_1080P, 8000));
    gov.release("s-idem");
    gov.release("s-idem"); // second release must be a harmless no-op
    gov.release(null);     // null must not throw
    assertTrue(gov.isIdle());
  }

  @Test
  public void testReapStaleRemovesLeakedSession() throws Exception
  {
    Sage.put(PROP_STALE_MS, "1"); // anything older than 1ms is stale
    gov.trackSession(live("s-leak", EnhancementTier.ENHANCE_1440P, 15000));
    assertFalse(gov.isIdle());

    Thread.sleep(20);
    int reaped = gov.reapStale();

    assertEquals(reaped, 1, "the leaked reservation must be reaped");
    assertTrue(gov.isIdle(), "capacity must be reclaimed to baseline");
    assertEquals(gov.totalBitrateKbps(), 0L);
  }

  @Test
  public void testHeartbeatPreservesLiveSession() throws Exception
  {
    Sage.put(PROP_STALE_MS, "40");
    gov.trackSession(live("s-alive", EnhancementTier.ENHANCE_2160P, 30000));

    // Keep it fresh across several stale windows, as the ffmpeg progress loop does.
    for (int i = 0; i < 4; i++)
    {
      Thread.sleep(20);
      gov.heartbeat("s-alive");
      assertEquals(gov.reapStale(), 0, "a heart-beating session must never be reaped");
    }
    assertFalse(gov.isIdle());
    assertEquals(gov.countLiveSessions(), 1);
  }

  @Test
  public void testReapStaleDisabledWhenIntervalNonPositive() throws Exception
  {
    Sage.put(PROP_STALE_MS, "0"); // 0 disables staleness reaping entirely
    gov.trackSession(live("s-keep", EnhancementTier.ENHANCE_1080P, 8000));
    Thread.sleep(20);
    assertEquals(gov.reapStale(), 0, "stale<=0 must disable reaping");
    assertFalse(gov.isIdle());
  }

  @Test
  public void testOfflineSessionsNotCountedLive()
  {
    gov.trackSession(new GpuGovernor.Session("s-batch", EnhancementTier.ENHANCE_2160P, 0, 20000, true));
    assertEquals(gov.countLiveSessions(), 0, "offline batch jobs are not live sessions");
    assertEquals(gov.countOfflineSessions(), 1);
    assertEquals(gov.countSessions(), 1);
    gov.release("s-batch");
    assertTrue(gov.isIdle());
  }
}
