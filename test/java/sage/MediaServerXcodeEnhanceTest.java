package sage;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

import sage.enhance.EnhancementTier;

/**
 * Round-trip contract tests for the GPU-enhancement wire format between
 * {@link MiniPlayer#buildEffDeliveryToken} (the emitter) and
 * {@link MediaServer#parseXcodeEnhanceRequest} (the transcode-socket parser).
 *
 * <p>These exist because the two ends were once wired to different shapes: the
 * emitter published {@code <mode>:enhance;tier=<wire>} while the parser looked
 * for a {@code ;enhance=<tier>} pair. The mismatch meant the enhancement request
 * was never honored AND the literal {@code mpeg2tsremux:enhance} was handed to
 * the transcoder, which did not recognize it and fell through to a 352x240 SD
 * DVD profile. Each test drives the emitter's real output through the pull
 * bridge's prefix-strip and asserts the parser recovers the base mode + tier.
 */
public class MediaServerXcodeEnhanceTest
{
  /**
   * Simulate the pull bridge: {@code CAP_EFFECTIVE_DELIVERY=pull-xcode:<rest>}
   * is mapped 1:1 to {@code /msproxy?mode=<rest>}, so the transcode socket
   * receives everything after the {@code pull-xcode:} prefix verbatim.
   */
  private static String bridgeForward(String effDeliveryToken)
  {
    final String prefix = "pull-xcode:";
    assertTrue(effDeliveryToken.startsWith(prefix),
        "This helper only models the pull-xcode bridge; got: " + effDeliveryToken);
    return effDeliveryToken.substring(prefix.length());
  }

  @Test
  public void enhance2160pRoundTripsFromEmitterToParser()
  {
    String token = MiniPlayer.buildEffDeliveryToken(
        "pull-xcode", "mpeg2tsremux", EnhancementTier.ENHANCE_2160P);
    assertEquals(token, "pull-xcode:mpeg2tsremux:enhance;tier=2160p",
        "Emitter shape changed; parser test must be updated in lockstep");

    MediaServer.XcodeEnhanceRequest req =
        MediaServer.parseXcodeEnhanceRequest(bridgeForward(token));

    assertEquals(req.baseMode, "mpeg2tsremux",
        "Base mode must have the :enhance marker stripped so it is recognized as copy-family");
    assertTrue(req.enhanceRequested, "The :enhance marker must set enhanceRequested");
    assertEquals(req.tierToken, "2160p", "Tier token must be recovered from ;tier=");
    assertEquals(EnhancementTier.fromToken(req.tierToken), EnhancementTier.ENHANCE_2160P,
        "Recovered tier token must resolve back to the emitted tier");
  }

  @Test
  public void deintTierRoundTrips()
  {
    String token = MiniPlayer.buildEffDeliveryToken(
        "pull-xcode", "dynamich264", EnhancementTier.DEINTERLACE_ONLY);
    MediaServer.XcodeEnhanceRequest req =
        MediaServer.parseXcodeEnhanceRequest(bridgeForward(token));
    assertEquals(req.baseMode, "dynamich264");
    assertTrue(req.enhanceRequested);
    assertEquals(req.tierToken, "deint");
    assertEquals(EnhancementTier.fromToken(req.tierToken), EnhancementTier.DEINTERLACE_ONLY);
  }

  @Test
  public void plainModeWithoutEnhancementIsUnchanged()
  {
    MediaServer.XcodeEnhanceRequest req =
        MediaServer.parseXcodeEnhanceRequest("mpeg2tsremux");
    assertEquals(req.baseMode, "mpeg2tsremux",
        "A mode with no marker must pass through byte-for-byte");
    assertFalse(req.enhanceRequested);
    assertNull(req.tierToken);
  }

  @Test
  public void enhancementCoexistsWithAudioParams()
  {
    // The real wire may carry audio hints alongside the enhancement marker.
    MediaServer.XcodeEnhanceRequest req = MediaServer.parseXcodeEnhanceRequest(
        "mpeg2tsremux:enhance;tier=2160p;acodec=aac;ac=2;ss=1500");
    assertEquals(req.baseMode, "mpeg2tsremux");
    assertTrue(req.enhanceRequested);
    assertEquals(req.tierToken, "2160p");
  }

  @Test
  public void markerWithoutTierIsRequestedButUntargeted()
  {
    MediaServer.XcodeEnhanceRequest req =
        MediaServer.parseXcodeEnhanceRequest("mpeg2tsremux:enhance");
    assertEquals(req.baseMode, "mpeg2tsremux");
    assertTrue(req.enhanceRequested);
    assertNull(req.tierToken,
        "No ;tier= means no tier; the caller must not honor an untargeted request");
  }

  @Test
  public void baseModeNeverRetainsEnhanceToken()
  {
    // The exact regression: the transcoder must never see ":enhance" in the mode.
    for (String mode : new String[] {"mpeg2tsremux", "dynamich264", "browserhd_copyv"})
    {
      MediaServer.XcodeEnhanceRequest req =
          MediaServer.parseXcodeEnhanceRequest(mode + ":enhance;tier=2160p");
      assertFalse(req.baseMode.contains("enhance"),
          "baseMode must not leak the enhance marker to setTranscodeFormat: " + req.baseMode);
      assertEquals(req.baseMode, mode);
    }
  }

  @Test
  public void nullArgIsSafe()
  {
    MediaServer.XcodeEnhanceRequest req = MediaServer.parseXcodeEnhanceRequest(null);
    assertNull(req.baseMode);
    assertFalse(req.enhanceRequested);
    assertNull(req.tierToken);
  }
}
