package sage.enhance;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import sage.Sage;
import sage.TestUtils;

import static org.testng.Assert.*;

/**
 * Tests for the recording veto — Invariant 0 of the enhancement feature.
 *
 * <p>These exercise {@link RecordingGuard#applyVeto} against synthetic capture
 * load rather than a live {@code MMC}, so the policy is testable without tuners.
 */
public class RecordingGuardTest
{
  private static final String PROP_PROTECTION = "playback/gpu_enhance/recording_protection";

  @BeforeMethod
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.remove(PROP_PROTECTION);
  }

  private static RecordingGuard.CaptureLoad idle()
  {
    return new RecordingGuard.CaptureLoad(0, 0, -1L);
  }

  private static RecordingGuard.CaptureLoad recordingNow()
  {
    return new RecordingGuard.CaptureLoad(1, 0, -1L);
  }

  /** Nothing recording now, but a tuner fires in 90 seconds. */
  private static RecordingGuard.CaptureLoad imminent()
  {
    return new RecordingGuard.CaptureLoad(0, 1, 90000L);
  }

  // ---- Idle: no veto ------------------------------------------------------

  @Test
  public void testIdleGrantsTheRequestedTier()
  {
    RecordingGuard g = RecordingGuard.getInstance();
    assertEquals(g.applyVeto(EnhancementTier.ENHANCE_2160P, idle()),
        EnhancementTier.ENHANCE_2160P);
  }

  // ---- Protect posture (default) -----------------------------------------

  @Test
  public void testProtectPostureIsTheDefault()
  {
    assertEquals(RecordingGuard.getInstance().getPosture(), RecordingGuard.Posture.PROTECT);
  }

  @Test
  public void testProtectForbidsEnhancementWhileRecording()
  {
    Sage.put(PROP_PROTECTION, "protect");
    RecordingGuard g = RecordingGuard.getInstance();
    assertEquals(g.applyVeto(EnhancementTier.ENHANCE_2160P, recordingNow()),
        EnhancementTier.NONE);
    // Even the cheapest tier is refused under the protective posture.
    assertEquals(g.applyVeto(EnhancementTier.DEINTERLACE_ONLY, recordingNow()),
        EnhancementTier.NONE);
  }

  /**
   * The schedule lookahead is the whole point of being schedule-aware: starting
   * a 2160p session ninety seconds before a tuner fires is the exact failure the
   * veto exists to prevent.
   */
  @Test
  public void testProtectAlsoVetoesOnImminentRecordings()
  {
    Sage.put(PROP_PROTECTION, "protect");
    assertEquals(RecordingGuard.getInstance().applyVeto(EnhancementTier.ENHANCE_2160P, imminent()),
        EnhancementTier.NONE);
  }

  // ---- Balanced posture ---------------------------------------------------

  @Test
  public void testBalancedCapsRatherThanForbids()
  {
    Sage.put(PROP_PROTECTION, "balanced");
    RecordingGuard g = RecordingGuard.getInstance();
    assertEquals(g.applyVeto(EnhancementTier.ENHANCE_2160P, recordingNow()),
        EnhancementTier.DEINTERLACE_ONLY);
    assertEquals(g.applyVeto(EnhancementTier.ENHANCE_1080P, recordingNow()),
        EnhancementTier.DEINTERLACE_ONLY);
  }

  @Test
  public void testBalancedLeavesAlreadyCheapTiersAlone()
  {
    Sage.put(PROP_PROTECTION, "balanced");
    assertEquals(RecordingGuard.getInstance()
        .applyVeto(EnhancementTier.DEINTERLACE_ONLY, recordingNow()),
        EnhancementTier.DEINTERLACE_ONLY);
  }

  // ---- Fail-closed behavior ----------------------------------------------

  @Test
  public void testUnknownPostureFallsBackToProtect()
  {
    Sage.put(PROP_PROTECTION, "wide-open-please");
    assertEquals(RecordingGuard.getInstance().getPosture(), RecordingGuard.Posture.PROTECT);
  }

  @Test
  public void testNullInputsYieldNoEnhancement()
  {
    RecordingGuard g = RecordingGuard.getInstance();
    assertEquals(g.applyVeto(null, idle()), EnhancementTier.NONE);
    assertEquals(g.applyVeto(EnhancementTier.ENHANCE_2160P, null), EnhancementTier.NONE);
    assertEquals(g.applyVeto(EnhancementTier.NONE, idle()), EnhancementTier.NONE);
  }

  // ---- CaptureLoad semantics ---------------------------------------------

  @Test
  public void testCaptureLoadReservesBothActiveAndImminentTuners()
  {
    RecordingGuard.CaptureLoad load = new RecordingGuard.CaptureLoad(2, 3, 45000L);
    assertEquals(load.getReservedTuners(), 5);
    assertTrue(load.isRecordingNow());
    assertTrue(load.isBusyOrImminent());
    assertEquals(load.getMsUntilNext(), 45000L);
  }

  @Test
  public void testImminentOnlyLoadIsBusyButNotRecordingNow()
  {
    RecordingGuard.CaptureLoad load = imminent();
    assertFalse(load.isRecordingNow());
    assertTrue(load.isBusyOrImminent(), "an imminent recording must still count as busy");
  }

  @Test
  public void testIdleLoadIsNeitherBusyNorRecording()
  {
    assertFalse(idle().isBusyOrImminent());
    assertFalse(idle().isRecordingNow());
    assertEquals(idle().getReservedTuners(), 0);
  }

  // ---- Explanation --------------------------------------------------------

  @Test
  public void testExplainVetoIsNullWhenNothingChanged()
  {
    assertNull(RecordingGuard.getInstance().explainVeto(
        EnhancementTier.ENHANCE_2160P, EnhancementTier.ENHANCE_2160P, idle()));
  }

  @Test
  public void testExplainVetoDescribesTheDowngrade()
  {
    String why = RecordingGuard.getInstance().explainVeto(
        EnhancementTier.ENHANCE_2160P, EnhancementTier.NONE, recordingNow());
    assertNotNull(why);
    assertTrue(why.contains("recording veto"), why);
    assertTrue(why.contains("enhance_2160p"), why);
  }
}
