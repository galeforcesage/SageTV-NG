package sage.media;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import sage.Sage;
import sage.TestUtils;

import static org.testng.Assert.*;

/**
 * Tests for {@link BitratePolicy}, the single shared per-session bitrate model.
 */
public class BitratePolicyTest
{
  @BeforeMethod
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    // Clear every tunable so tests see documented defaults.
    Sage.remove("playback/bandwidth_safety_factor");
    Sage.remove("playback/bitrate/high_motion_fps");
    Sage.remove("playback/bitrate/audio_reserve_kbps");
    Sage.remove("playback/bitrate/max_kbps");
    Sage.remove("playback/bitrate/min_kbps");
    Sage.remove("playback/bitrate/maxrate_factor");
    Sage.remove("playback/bitrate/bufsize_factor");
    Sage.remove("playback/bitrate/hevc_factor");
    Sage.remove("playback/bitrate/av1_factor");
    Sage.remove("playback/bitrate/mpeg2_factor");
    Sage.remove("playback/bitrate/mpeg4_factor");
    Sage.remove("playback/bitrate/ladder/2160/high");
    Sage.remove("playback/bitrate/ladder/1080/medium");
  }

  // ---- ladder reproduces the enhance cells exactly ------------------------

  @Test
  public void ladderMatchesEnhanceTierNumbers()
  {
    assertEquals(BitratePolicy.ladderKbps(2160, BitratePolicy.Motion.HIGH),   40000);
    assertEquals(BitratePolicy.ladderKbps(2160, BitratePolicy.Motion.MEDIUM), 28000);
    assertEquals(BitratePolicy.ladderKbps(2160, BitratePolicy.Motion.LOW),    20000);
    assertEquals(BitratePolicy.ladderKbps(1440, BitratePolicy.Motion.HIGH),   24000);
    assertEquals(BitratePolicy.ladderKbps(1440, BitratePolicy.Motion.MEDIUM), 17000);
    assertEquals(BitratePolicy.ladderKbps(1440, BitratePolicy.Motion.LOW),    13000);
    assertEquals(BitratePolicy.ladderKbps(1080, BitratePolicy.Motion.HIGH),   14000);
    assertEquals(BitratePolicy.ladderKbps(1080, BitratePolicy.Motion.MEDIUM), 10000);
    assertEquals(BitratePolicy.ladderKbps(1080, BitratePolicy.Motion.LOW),     7000);
  }

  @Test
  public void ladderTierBoundariesRoundToNearestTier()
  {
    // 1088/1072 (common padded 1080) still land in the 1080 tier.
    assertEquals(BitratePolicy.ladderKbps(1088, BitratePolicy.Motion.MEDIUM), 10000);
    // 720 lands in the 720 tier, not 1080.
    assertEquals(BitratePolicy.ladderKbps(720, BitratePolicy.Motion.MEDIUM), 6000);
    // SD.
    assertEquals(BitratePolicy.ladderKbps(480, BitratePolicy.Motion.MEDIUM), 1800);
  }

  @Test
  public void ladderCellIsOverridable()
  {
    Sage.putInt("playback/bitrate/ladder/2160/high", 55000);
    assertEquals(BitratePolicy.ladderKbps(2160, BitratePolicy.Motion.HIGH), 55000);
  }

  // ---- motion / fps mapping ----------------------------------------------

  @Test
  public void highFrameRateFoldsIntoHighMotion()
  {
    assertEquals(BitratePolicy.motionForFps(60), BitratePolicy.Motion.HIGH);
    assertEquals(BitratePolicy.motionForFps(59.94), BitratePolicy.Motion.HIGH);
    assertEquals(BitratePolicy.motionForFps(30), BitratePolicy.Motion.MEDIUM);
    assertEquals(BitratePolicy.motionForFps(24), BitratePolicy.Motion.MEDIUM);
  }

  @Test
  public void computeUsesHigherOfGenreAndFpsMotion()
  {
    // Genre LOW but 60fps -> HIGH motion anchor at 1080p (14000), unmetered.
    BitratePolicy.Plan p = BitratePolicy.compute(
        1920, 1080, 60, "h264", BitratePolicy.Motion.LOW, 0, 0);
    assertEquals(p.targetKbps, 14000);
  }

  // ---- codec factor -------------------------------------------------------

  @Test
  public void codecFactorScalesAnchor()
  {
    assertEquals(BitratePolicy.codecFactor("h264_nvenc"), 1.0, 0.0001);
    assertEquals(BitratePolicy.codecFactor("hevc"), 0.55, 0.0001);
    assertEquals(BitratePolicy.codecFactor("hevc_nvenc"), 0.55, 0.0001);
    assertEquals(BitratePolicy.codecFactor("av1_nvenc"), 0.50, 0.0001);
    assertEquals(BitratePolicy.codecFactor("mpeg2video"), 1.6, 0.0001);
  }

  @Test
  public void hevc4kIsRoughlyHalfH264()
  {
    // 2160p medium H.264 = 28000; HEVC = 28000 * 0.55 = 15400.
    BitratePolicy.Plan p = BitratePolicy.compute(
        3840, 2160, 30, "hevc_nvenc", BitratePolicy.Motion.MEDIUM, 0, 0);
    assertEquals(p.targetKbps, 15400);
  }

  // ---- link / ceiling / admin clamps -------------------------------------

  @Test
  public void unmeteredLinkAppliesNoClamp()
  {
    // linkKbps=0 -> pure quality anchor (2160p high = 40000).
    BitratePolicy.Plan p = BitratePolicy.compute(
        3840, 2160, 60, "h264", BitratePolicy.Motion.HIGH, 0, 0);
    assertEquals(p.targetKbps, 40000);
  }

  @Test
  public void meteredLinkClampsBelowAnchor()
  {
    // 1080p medium anchor = 10000; link 8000 * 0.85 = 6800 - 160 audio = 6640.
    BitratePolicy.Plan p = BitratePolicy.compute(
        1920, 1080, 30, "h264", BitratePolicy.Motion.MEDIUM, 8000, 0);
    assertEquals(p.targetKbps, 6640);
  }

  @Test
  public void ampleLinkDoesNotRaiseAnchor()
  {
    // 1080p medium anchor = 10000; link 100000 is far above -> anchor wins.
    BitratePolicy.Plan p = BitratePolicy.compute(
        1920, 1080, 30, "h264", BitratePolicy.Motion.MEDIUM, 100000, 0);
    assertEquals(p.targetKbps, 10000);
  }

  @Test
  public void clientDecoderCeilingClamps()
  {
    // 2160p high anchor = 40000; client ceiling 20000 wins.
    BitratePolicy.Plan p = BitratePolicy.compute(
        3840, 2160, 60, "h264", BitratePolicy.Motion.HIGH, 0, 20000);
    assertEquals(p.targetKbps, 20000);
  }

  @Test
  public void adminMaxClamps()
  {
    Sage.putLong("playback/bitrate/max_kbps", 12000);
    BitratePolicy.Plan p = BitratePolicy.compute(
        3840, 2160, 60, "h264", BitratePolicy.Motion.HIGH, 0, 0);
    assertEquals(p.targetKbps, 12000);
  }

  @Test
  public void smallestClampWins()
  {
    // anchor 40000, link 30000*0.85-160=25340, ceiling 18000, adminMax 22000 -> 18000.
    Sage.putLong("playback/bitrate/max_kbps", 22000);
    BitratePolicy.Plan p = BitratePolicy.compute(
        3840, 2160, 60, "h264", BitratePolicy.Motion.HIGH, 30000, 18000);
    assertEquals(p.targetKbps, 18000);
  }

  @Test
  public void neverGoesBelowFloor()
  {
    // A pathologically small link floors at 500 kbps by default.
    BitratePolicy.Plan p = BitratePolicy.compute(
        640, 360, 30, "h264", BitratePolicy.Motion.LOW, 300, 0);
    assertEquals(p.targetKbps, 500);
  }

  // ---- maxrate / bufsize derivation --------------------------------------

  @Test
  public void maxrateAndBufsizeFollowTarget()
  {
    // 1080p medium unmetered = 10000; maxrate 1.4x = 14000; bufsize 2x = 28000.
    BitratePolicy.Plan p = BitratePolicy.compute(
        1920, 1080, 30, "h264", BitratePolicy.Motion.MEDIUM, 0, 0);
    assertEquals(p.targetKbps, 10000);
    assertEquals(p.maxrateKbps, 14000);
    assertEquals(p.bufsizeKbps, 28000);
  }
}
