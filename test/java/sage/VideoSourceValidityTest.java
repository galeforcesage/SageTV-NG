package sage;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class VideoSourceValidityTest
{
  private void clearProps()
  {
    Sage.remove(VideoSourceValidity.CLEANUP_ENABLED);
    Sage.remove(VideoSourceValidity.SCAN_INTERVAL_MS);
    Sage.remove(VideoSourceValidity.STALE_AFTER_MS);
    Sage.remove(VideoSourceValidity.PRUNE_AFTER_MS);
  }

  @BeforeMethod
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    clearProps();
  }

  @AfterMethod
  public void tearDown()
  {
    clearProps();
  }

  @Test
  public void testDefaultsStayConservative()
  {
    assertFalse(VideoSourceValidity.isCleanupEnabled(),
        "Automatic pruning must stay disabled by default");
    assertTrue(VideoSourceValidity.getScanIntervalMs() >= Sage.MILLIS_PER_MIN);
    assertTrue(VideoSourceValidity.getStaleAfterMs() >= 0L);
    assertTrue(VideoSourceValidity.getPruneAfterMs() >= VideoSourceValidity.getStaleAfterMs());
  }

  @Test
  public void testResolveStateTransitionsFromStaleToExpired()
  {
    Sage.putLong(VideoSourceValidity.STALE_AFTER_MS, 5000L);
    long firstInvalidAt = 1000L;

    assertEquals(VideoSourceValidity.resolveState(firstInvalidAt, 5999L), VideoSourceValidity.STATE_STALE);
    assertEquals(VideoSourceValidity.resolveState(firstInvalidAt, 6000L), VideoSourceValidity.STATE_EXPIRED);
  }

  @Test
  public void testPruneRequiresExplicitEnableFlag()
  {
    Sage.putLong(VideoSourceValidity.STALE_AFTER_MS, 1000L);
    Sage.putLong(VideoSourceValidity.PRUNE_AFTER_MS, 5000L);
    long firstInvalidAt = 1000L;

    assertFalse(VideoSourceValidity.shouldPrune(firstInvalidAt, 7000L),
        "Past-TTL invalid sources must not prune unless the feature is explicitly enabled");

    Sage.putBoolean(VideoSourceValidity.CLEANUP_ENABLED, true);
    assertTrue(VideoSourceValidity.shouldPrune(firstInvalidAt, 7000L));
  }

  @Test
  public void testPruneThresholdNeverDropsBelowStaleThreshold()
  {
    Sage.putLong(VideoSourceValidity.STALE_AFTER_MS, 9000L);
    Sage.putLong(VideoSourceValidity.PRUNE_AFTER_MS, 2000L);

    assertEquals(VideoSourceValidity.getPruneAfterMs(), 9000L,
        "Prune threshold must never undercut stale/expire threshold");
  }
}
