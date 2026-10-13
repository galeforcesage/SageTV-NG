package sage.enhance.spi;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.testng.annotations.Test;

import sage.enhance.EnhancementTier;

public class ScaleProfileAssessmentTest
{
  private static ScaleRequest request(FrameCadence source, FrameCadence target,
      String sourceFormat, String model)
  {
    return new ScaleRequest(EnhancementTier.ENHANCE_2160P, 3840, 2160,
        1920, 1080, true, "scale_npp", ScaleRequest.Purpose.LIVE,
        source, target, sourceFormat, sourceFormat.indexOf("10") >= 0 ? 10 : 8,
        "p010le", 10, "tensorrt", model, "GPU-1", "595.84");
  }

  @Test
  public void broadcastCadencesRemainDistinct()
  {
    assertEquals(FrameCadence.fromFps(59.94), new FrameCadence(60000, 1001));
    assertEquals(FrameCadence.fromFps(60.0), new FrameCadence(60, 1));
    assertNotEquals(FrameCadence.fromFps(59.94), FrameCadence.fromFps(60.0));
  }

  @Test
  public void everyProfileDimensionParticipatesInIdentity()
  {
    ScaleProfileKey base = request(new FrameCadence(30000, 1001),
        new FrameCadence(60000, 1001), "yuv420p10le", "vsr-a").getProfileKey();
    assertNotEquals(base, request(new FrameCadence(30, 1),
        new FrameCadence(60, 1), "yuv420p10le", "vsr-a").getProfileKey());
    assertNotEquals(base, request(new FrameCadence(30000, 1001),
        new FrameCadence(60000, 1001), "yuv420p", "vsr-a").getProfileKey());
    assertNotEquals(base, request(new FrameCadence(30000, 1001),
        new FrameCadence(60000, 1001), "yuv420p10le", "vsr-b").getProfileKey());
  }

  @Test
  public void measuredAssessmentRejectsReadinessOnlyAndBelowRate()
  {
    ScaleProfileKey key = request(new FrameCadence(30000, 1001),
        new FrameCadence(60000, 1001), "yuv420p10le", "vsr-a").getProfileKey();
    ScaleProviderAssessment good = new ScaleProviderAssessment(key, true, "measured",
        72.0, 71.928, 1.201, Instant.now(), Duration.ZERO,
        Duration.ofSeconds(10), 720);
    assertTrue(good.isUsable());
    assertTrue(good.isUsableFor(request(new FrameCadence(30000, 1001),
        new FrameCadence(60000, 1001), "yuv420p10le", "vsr-a")));
    assertFalse(good.isUsableFor(request(new FrameCadence(30, 1),
        new FrameCadence(60, 1), "yuv420p10le", "vsr-a")));
    assertFalse(new ScaleProviderAssessment(key, true, "slow", 59.6, 71.928, 0.995,
        Instant.now(), Duration.ZERO, Duration.ofSeconds(10), 596).isUsable());
    assertFalse(new ScaleProviderAssessment(key, true, "ready only", 0.0, 71.928, 0.0,
        Instant.now(), Duration.ZERO, Duration.ZERO, 0).isUsable());
  }

  @Test
  public void warmCacheKeyUsesExactProfile()
  {
    ScaleRequest a = request(new FrameCadence(30000, 1001),
        new FrameCadence(60000, 1001), "yuv420p10le", "vsr-a");
    ScaleRequest b = request(new FrameCadence(30, 1),
        new FrameCadence(60, 1), "yuv420p10le", "vsr-a");
    assertNotEquals(ScaleWarmupCache.keyFor("vsr", a), ScaleWarmupCache.keyFor("vsr", b));
  }
}
