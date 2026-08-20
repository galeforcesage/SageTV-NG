package sage.enhance;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import sage.Sage;
import sage.TestUtils;

import static org.testng.Assert.*;

/**
 * Tests for the outcome feedback loop — the mechanism that lets the system stay
 * stable on hardware nobody tested, without hardcoded device assumptions.
 */
public class EnhancementTelemetryTest
{
  private static final String PROP_DEMOTE  = "playback/gpu_enhance/demote_after";
  private static final String PROP_PROMOTE = "playback/gpu_enhance/promote_after";

  @BeforeMethod
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.remove(PROP_DEMOTE);
    Sage.remove(PROP_PROMOTE);
    EnhancementTelemetry.getInstance().clear();
  }

  private static void session(String id, EnhancementTier tier,
                              EnhancementTelemetry.Outcome outcome)
  {
    EnhancementTelemetry t = EnhancementTelemetry.getInstance();
    t.recordDecision(id, "prof1", "surf1", 1920, 1080, true, 60,
        tier, tier, "admitted", "gpu ok", 25000);
    t.recordOutcome(id, outcome, "");
  }

  // ---- Recording ----------------------------------------------------------

  @Test
  public void testDecisionIsRecordedAndRetrievable()
  {
    EnhancementTelemetry t = EnhancementTelemetry.getInstance();
    t.recordDecision("s1", "prof1", "surf1", 1920, 1080, true, 60,
        EnhancementTier.ENHANCE_2160P, EnhancementTier.ENHANCE_1440P,
        "stepped down: VRAM", "gpu 60%", 20000);
    assertEquals(t.recentRecords().size(), 1);
    EnhancementTelemetry.Record r = t.recentRecords().get(0);
    assertEquals(r.getGrantedTier(), EnhancementTier.ENHANCE_1440P);
    assertEquals(r.getDesiredTier(), EnhancementTier.ENHANCE_2160P);
    assertEquals(r.getOutcome(), EnhancementTelemetry.Outcome.ACTIVE);
  }

  /** Grants are logged too, not just denials — otherwise the log can't explain success. */
  @Test
  public void testLogLineExplainsGrantsAsWellAsDenials()
  {
    EnhancementTelemetry t = EnhancementTelemetry.getInstance();
    t.recordDecision("s1", "prof1", "surf1", 1280, 720, false, 60,
        EnhancementTier.ENHANCE_2160P, EnhancementTier.ENHANCE_2160P, "admitted", "gpu 20%", 40000);
    String line = t.recentRecords().get(0).toLogLine();
    assertTrue(line.contains("want=enhance_2160p"), line);
    assertTrue(line.contains("got=enhance_2160p"), line);
    assertTrue(line.contains("admitted"), line);
    assertTrue(line.contains("720p") || line.contains("1280x720p"), line);
  }

  @Test
  public void testOutcomeIsRecorded()
  {
    session("s1", EnhancementTier.ENHANCE_2160P, EnhancementTelemetry.Outcome.CLEAN);
    assertEquals(EnhancementTelemetry.getInstance().recentRecords().get(0).getOutcome(),
        EnhancementTelemetry.Outcome.CLEAN);
  }

  // ---- Outcome classification --------------------------------------------

  @Test
  public void testBadOutcomeClassification()
  {
    assertTrue(EnhancementTelemetry.Outcome.REBUFFERED.isBad());
    assertTrue(EnhancementTelemetry.Outcome.DOWNGRADED.isBad());
    assertTrue(EnhancementTelemetry.Outcome.FAILED.isBad());
    assertFalse(EnhancementTelemetry.Outcome.CLEAN.isBad());
    assertFalse(EnhancementTelemetry.Outcome.ACTIVE.isBad());
    // Stopped by policy, not by inadequacy — must not count against the client.
    assertFalse(EnhancementTelemetry.Outcome.YIELDED_TO_RECORDING.isBad());
  }

  // ---- Demotion -----------------------------------------------------------

  @Test
  public void testRepeatedBadOutcomesDemoteTheBucket()
  {
    Sage.put(PROP_DEMOTE, "2");
    EnhancementTelemetry t = EnhancementTelemetry.getInstance();
    session("s1", EnhancementTier.ENHANCE_2160P, EnhancementTelemetry.Outcome.REBUFFERED);
    session("s2", EnhancementTier.ENHANCE_2160P, EnhancementTelemetry.Outcome.REBUFFERED);
    assertEquals(t.applyBiasCeiling("prof1", "surf1", EnhancementTier.ENHANCE_2160P),
        EnhancementTier.ENHANCE_1440P);
  }

  @Test
  public void testASingleBadOutcomeDoesNotDemoteWhenThresholdIsTwo()
  {
    Sage.put(PROP_DEMOTE, "2");
    session("s1", EnhancementTier.ENHANCE_2160P, EnhancementTelemetry.Outcome.REBUFFERED);
    assertEquals(EnhancementTelemetry.getInstance()
        .applyBiasCeiling("prof1", "surf1", EnhancementTier.ENHANCE_2160P),
        EnhancementTier.ENHANCE_2160P);
  }

  @Test
  public void testCleanRunResetsTheBadStreak()
  {
    Sage.put(PROP_DEMOTE, "2");
    session("s1", EnhancementTier.ENHANCE_2160P, EnhancementTelemetry.Outcome.REBUFFERED);
    session("s2", EnhancementTier.ENHANCE_2160P, EnhancementTelemetry.Outcome.CLEAN);
    session("s3", EnhancementTier.ENHANCE_2160P, EnhancementTelemetry.Outcome.REBUFFERED);
    assertEquals(EnhancementTelemetry.getInstance()
        .applyBiasCeiling("prof1", "surf1", EnhancementTier.ENHANCE_2160P),
        EnhancementTier.ENHANCE_2160P, "a clean run in between must reset the streak");
  }

  /**
   * A session torn down to protect a recording was working fine; holding that
   * against the client's hardware would be wrong.
   */
  @Test
  public void testYieldingToRecordingNeverDemotes()
  {
    Sage.put(PROP_DEMOTE, "1");
    session("s1", EnhancementTier.ENHANCE_2160P,
        EnhancementTelemetry.Outcome.YIELDED_TO_RECORDING);
    session("s2", EnhancementTier.ENHANCE_2160P,
        EnhancementTelemetry.Outcome.YIELDED_TO_RECORDING);
    assertEquals(EnhancementTelemetry.getInstance()
        .applyBiasCeiling("prof1", "surf1", EnhancementTier.ENHANCE_2160P),
        EnhancementTier.ENHANCE_2160P);
  }

  // ---- Bias application ---------------------------------------------------

  @Test
  public void testUnknownBucketIsUnrestricted()
  {
    assertEquals(EnhancementTelemetry.getInstance()
        .applyBiasCeiling("brand-new", "surfX", EnhancementTier.ENHANCE_2160P),
        EnhancementTier.ENHANCE_2160P);
  }

  @Test
  public void testBiasNeverRaisesARequestedTier()
  {
    Sage.put(PROP_DEMOTE, "1");
    session("s1", EnhancementTier.ENHANCE_1080P, EnhancementTelemetry.Outcome.FAILED);
    // Bucket ceiling is now below 1080p, but asking for deinterlace-only must
    // not be inflated upward.
    EnhancementTier got = EnhancementTelemetry.getInstance()
        .applyBiasCeiling("prof1", "surf1", EnhancementTier.DEINTERLACE_ONLY);
    assertTrue(got.getRank() <= EnhancementTier.DEINTERLACE_ONLY.getRank());
  }

  @Test
  public void testInactiveTierStaysInactive()
  {
    assertEquals(EnhancementTelemetry.getInstance()
        .applyBiasCeiling("prof1", "surf1", EnhancementTier.NONE), EnhancementTier.NONE);
    assertEquals(EnhancementTelemetry.getInstance()
        .applyBiasCeiling("prof1", "surf1", null), EnhancementTier.NONE);
  }

  // ---- Bucket keys --------------------------------------------------------

  @Test
  public void testBucketKeySeparatesProfileSurfaceAndTier()
  {
    String a = EnhancementTelemetry.bucketKey("p", "s", EnhancementTier.ENHANCE_2160P);
    String b = EnhancementTelemetry.bucketKey("p", "s", EnhancementTier.ENHANCE_1080P);
    String c = EnhancementTelemetry.bucketKey("p", "other", EnhancementTier.ENHANCE_2160P);
    assertNotEquals(a, b);
    assertNotEquals(a, c);
  }

  @Test
  public void testBucketKeyToleratesNulls()
  {
    assertNotNull(EnhancementTelemetry.bucketKey(null, null, null));
  }

  // ---- Ring bounding ------------------------------------------------------

  @Test
  public void testRingIsBounded()
  {
    Sage.put("playback/gpu_enhance/telemetry_ring_size", "10");
    EnhancementTelemetry t = EnhancementTelemetry.getInstance();
    for (int i = 0; i < 50; i++)
      session("s" + i, EnhancementTier.ENHANCE_1080P, EnhancementTelemetry.Outcome.CLEAN);
    assertTrue(t.recentRecords().size() <= 10,
        "ring must stay bounded, was " + t.recentRecords().size());
    Sage.remove("playback/gpu_enhance/telemetry_ring_size");
  }
}
