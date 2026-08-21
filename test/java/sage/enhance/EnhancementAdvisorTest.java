/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package sage.enhance;

import java.util.Arrays;
import java.util.List;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import sage.Sage;
import sage.TestUtils;
import sage.client.PlaybackSurface;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** Benefit-gate tests for {@link EnhancementAdvisor}. */
public class EnhancementAdvisorTest
{
  private static final List<String> DELIVERY = Arrays.asList("pull-xcode");
  private static final List<String> VCODECS  = Arrays.asList("HEVC", "H264");
  private static final List<String> ACODECS  = Arrays.asList("EAC3", "AAC");
  private static final List<String> CONT     = Arrays.asList("MP4");

  /** A surface that declared it can decode up to the given geometry. */
  private static PlaybackSurface surface(int maxW, int maxH, int maxFps)
  {
    return new PlaybackSurface("s1", "route", 0, DELIVERY, VCODECS, ACODECS, CONT,
        "all", "client", null, maxW, maxH, maxFps);
  }

  /** A surface that never declared output limits (the legacy shape). */
  private static PlaybackSurface undeclaredSurface()
  {
    return new PlaybackSurface("s1", "route", 0, DELIVERY, VCODECS, ACODECS, CONT);
  }

  @BeforeMethod
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.put(EnhancementAdvisor.PROP_ENABLED, "true");
  }

  @AfterMethod
  public void tearDown()
  {
    Sage.remove(EnhancementAdvisor.PROP_ENABLED);
    Sage.remove(EnhancementAdvisor.PROP_MAX_HEIGHT);
    Sage.remove(EnhancementAdvisor.PROP_MIN_GAIN_TENTHS);
    Sage.remove(EnhancementAdvisor.PROP_OVERRIDE_LOCAL);
    Sage.remove(EnhancementAdvisor.PROP_UNKNOWN_SINK);
    Sage.remove(EnhancementAdvisor.PROP_BW_SAFETY);
    Sage.remove("playback/bandwidth_safety_factor");
  }

  private EnhancementAdvisor.Advice advise(int sw, int sh, boolean inter, int fps,
      int sinkW, int sinkH, PlaybackSurface s, String pref, String status)
  {
    return EnhancementAdvisor.advise(sw, sh, inter, fps, sinkW, sinkH, s, pref, status, true);
  }

  private EnhancementAdvisor.Advice adviseBw(int sw, int sh, boolean inter, int fps,
      int sinkW, int sinkH, PlaybackSurface s, long srcKbps, long linkKbps)
  {
    return EnhancementAdvisor.advise(sw, sh, inter, fps, sinkW, sinkH, s, null, null,
        "auto", "none", true, srcKbps, linkKbps);
  }

  // ---------- the network gate (rule 6a) ----------
  //
  // Not a live-TV concern: the same delivery path carries recorded files, and
  // PlaybackDecisionEngine already forces a transcode-down when a RECORDING's
  // bitrate exceeds the measured link. Enhancement raises bitrate, so it has to
  // answer the same question or it spends headroom that was already budgeted.

  @Test
  public void testAmpleBandwidthAllowsTheFullTier()
  {
    EnhancementAdvisor.Advice a =
        adviseBw(1920, 1080, false, 30, 3840, 2160, surface(3840, 2160, 60), 8000, 100000);
    assertEquals(a.getTier(), EnhancementTier.ENHANCE_2160P);
    assertEquals(a.getVerdict(), EnhancementAdvisor.Verdict.OFFERED, "verdict");
  }

  /**
   * A constrained link DEGRADES rather than refuses. 2160p@30 asks 25000kbps
   * and 20000 * 0.85 = 17000 won't carry it, but 1440p asks 16000 and fits.
   * A smaller enhancement is nearly always better than none.
   */
  @Test
  public void testConstrainedLinkDowngradesRatherThanRefusing()
  {
    EnhancementAdvisor.Advice a =
        adviseBw(1920, 1080, false, 30, 3840, 2160, surface(3840, 2160, 60), 8000, 20000);
    assertEquals(a.getTier(), EnhancementTier.ENHANCE_1440P,
        "should step down the ladder, not refuse outright");
  }

  @Test
  public void testLinkTooSmallForAnyUpscaleIsRefused()
  {
    EnhancementAdvisor.Advice a =
        adviseBw(1920, 1080, false, 30, 3840, 2160, surface(3840, 2160, 60), 8000, 5000);
    assertEquals(a.getTier(), EnhancementTier.NONE);
    assertEquals(a.getVerdict(), EnhancementAdvisor.Verdict.INSUFFICIENT_BANDWIDTH,
        "the reason must say bandwidth, not blame the decoder");
  }

  /**
   * Deinterlacing is exempt: it emits roughly the stream the client was already
   * being sent, so the existing delivery-side rate machinery has already sized
   * it. Only upscaling adds bits nothing budgeted for.
   */
  @Test
  public void testDeinterlaceSurvivesALinkTooSmallToUpscale()
  {
    EnhancementAdvisor.Advice a =
        adviseBw(1920, 1080, true, 30, 3840, 2160, surface(3840, 2160, 60), 8000, 5000);
    assertEquals(a.getTier(), EnhancementTier.DEINTERLACE_ONLY,
        "a slow link must not strip the cheapest win");
  }

  /**
   * An unmeasured link is an abstention, exactly as an absent sink is. A probe
   * that was skipped is not evidence of a slow network.
   */
  @Test
  public void testUnknownBandwidthImposesNoCap()
  {
    EnhancementAdvisor.Advice a =
        adviseBw(1920, 1080, false, 30, 3840, 2160, surface(3840, 2160, 60), 0, 0);
    assertEquals(a.getTier(), EnhancementTier.ENHANCE_2160P,
        "0 means 'not measured', never 'measured as zero'");
  }

  @Test
  public void testBandwidthSafetyFactorInheritsTheGlobalSetting()
  {
    Sage.put("playback/bandwidth_safety_factor", "0.5");
    assertEquals(EnhancementAdvisor.bandwidthSafetyFactor(), 0.5f,
        "enhancement must not need the link tuned twice");
    // 30000 * 0.5 = 15000, which no longer carries 1440p's 16000.
    EnhancementAdvisor.Advice a =
        adviseBw(1920, 1080, false, 30, 3840, 2160, surface(3840, 2160, 60), 8000, 30000);
    assertEquals(a.getTier(), EnhancementTier.ENHANCE_1080P);
    Sage.remove("playback/bandwidth_safety_factor");
  }

  @Test
  public void testEnhancementSpecificSafetyFactorOverridesTheGlobal()
  {
    Sage.put("playback/bandwidth_safety_factor", "0.5");
    Sage.put(EnhancementAdvisor.PROP_BW_SAFETY, "0.9");
    assertEquals(EnhancementAdvisor.bandwidthSafetyFactor(), 0.9f);
    Sage.remove("playback/bandwidth_safety_factor");
  }

  // ---------- master switch ----------

  @Test
  public void testDisabledByDefault()
  {
    Sage.remove(EnhancementAdvisor.PROP_ENABLED);
    assertFalse(EnhancementAdvisor.isEnabled(),
        "Enhancement must be off until an admin turns it on");
    EnhancementAdvisor.Advice a =
        advise(1920, 1080, true, 30, 3840, 2160, surface(3840, 2160, 60), "auto", "none");
    assertEquals(a.getTier(), EnhancementTier.NONE);
    assertEquals(a.getVerdict(), EnhancementAdvisor.Verdict.DISABLED);
  }

  @Test
  public void testNoGpuSupportBlocksEverything()
  {
    EnhancementAdvisor.Advice a = EnhancementAdvisor.advise(1920, 1080, true, 30,
        3840, 2160, surface(3840, 2160, 60), "auto", "none", false);
    assertEquals(a.getTier(), EnhancementTier.NONE);
    assertEquals(a.getVerdict(), EnhancementAdvisor.Verdict.NO_GPU_SUPPORT);
  }

  // ---------- the happy paths ----------

  @Test
  public void test1080iTo4kIsOffered()
  {
    EnhancementAdvisor.Advice a =
        advise(1920, 1080, true, 30, 3840, 2160, surface(3840, 2160, 60), "auto", "none");
    assertEquals(a.getTier(), EnhancementTier.ENHANCE_2160P);
    assertTrue(a.isOffered());
  }

  @Test
  public void test720p60To4kIsOffered()
  {
    EnhancementAdvisor.Advice a =
        advise(1280, 720, false, 60, 3840, 2160, surface(3840, 2160, 60), "auto", "none");
    assertEquals(a.getTier(), EnhancementTier.ENHANCE_2160P);
  }

  // ---------- the source floor: HEIGHT, not width ----------

  @Test
  public void testDvdSdIsNotUpscaledBecause720IsItsWidth()
  {
    // 720x480 -- the classic trap. Its 720 is the WIDTH; height is 480, below
    // the floor, so no upscale may be offered.
    EnhancementAdvisor.Advice a =
        advise(720, 480, false, 30, 3840, 2160, surface(3840, 2160, 60), "auto", "none");
    assertEquals(a.getTier(), EnhancementTier.NONE,
        "720x480 must not upscale -- 720 is the width, height is 480");
    assertEquals(a.getVerdict(), EnhancementAdvisor.Verdict.SOURCE_BELOW_FLOOR);
  }

  @Test
  public void testInterlacedSdStillGetsDeinterlaceOnly()
  {
    // The user's explicit carve-out: deinterlacing isn't upscaling, and it's a
    // cheap real win on SD interlaced material.
    EnhancementAdvisor.Advice a =
        advise(720, 480, true, 30, 3840, 2160, surface(3840, 2160, 60), "auto", "none");
    assertEquals(a.getTier(), EnhancementTier.DEINTERLACE_ONLY,
        "SD interlaced must still be allowed to deinterlace");
  }

  @Test
  public void testOtherSubFloorGeometriesExcluded()
  {
    int[][] sub = { {704, 480}, {640, 480}, {720, 576}, {352, 240} };
    for (int[] wh : sub)
    {
      EnhancementAdvisor.Advice a =
          advise(wh[0], wh[1], false, 30, 3840, 2160, surface(3840, 2160, 60), "auto", "none");
      assertEquals(a.getTier(), EnhancementTier.NONE,
          wh[0] + "x" + wh[1] + " is below the floor and must not upscale");
    }
  }

  @Test
  public void testExactlyAtFloorQualifies()
  {
    EnhancementAdvisor.Advice a =
        advise(1280, 720, false, 30, 3840, 2160, surface(3840, 2160, 60), "auto", "none");
    assertTrue(a.getTier().isUpscaling(), "720 lines is exactly at the floor and qualifies");
  }

  // ---------- benefit gate ----------

  @Test
  public void test1080iOn1080pPanelGetsDeinterlaceNotUpscale()
  {
    EnhancementAdvisor.Advice a =
        advise(1920, 1080, true, 30, 1920, 1080, surface(1920, 1080, 60), "auto", "none");
    assertEquals(a.getTier(), EnhancementTier.DEINTERLACE_ONLY,
        "Same-size sink means there is nothing to upscale");
    assertEquals(a.getVerdict(), EnhancementAdvisor.Verdict.NO_VISIBLE_GAIN);
  }

  @Test
  public void testProgressive1080pOn1080pPanelGetsNothing()
  {
    EnhancementAdvisor.Advice a =
        advise(1920, 1080, false, 60, 1920, 1080, surface(1920, 1080, 60), "auto", "none");
    assertEquals(a.getTier(), EnhancementTier.NONE,
        "Nothing to deinterlace and nothing to gain in size");
  }

  /**
   * An absent sink is an abstention, not a refusal. The client stated no
   * opinion about its display but DID state a 4K decode ceiling, so the server
   * decides from the evidence it was given rather than handing the decision to
   * silence.
   */
  @Test
  public void testUnknownSinkIsAnAbstentionNotARefusal()
  {
    EnhancementAdvisor.Advice a =
        advise(1920, 1080, false, 30, 0, 0, surface(3840, 2160, 60), "auto", "none");
    assertEquals(a.getTier(), EnhancementTier.ENHANCE_2160P,
        "No sink means 'you decide', and the client proved it can decode 4K");
    assertEquals(a.getVerdict(), EnhancementAdvisor.Verdict.OFFERED, "verdict");
  }

  /**
   * The admin escape hatch: an installation that wants silence read as "no"
   * gets the older behaviour back with one property.
   */
  @Test
  public void testUnknownSinkCanBeConfiguredToRefuse()
  {
    Sage.put(EnhancementAdvisor.PROP_UNKNOWN_SINK, "refuse");
    EnhancementAdvisor.Advice a =
        advise(1920, 1080, false, 30, 0, 0, surface(3840, 2160, 60), "auto", "none");
    assertEquals(a.getTier(), EnhancementTier.NONE);
    assertEquals(a.getVerdict(), EnhancementAdvisor.Verdict.UNKNOWN_SINK, "verdict");
  }

  /**
   * The safety property that makes inference safe to default on: inferring
   * from declared decode ceilings gives a client that declared NOTHING exactly
   * nothing. Every legacy client lands where it always did, via the decode
   * gate rather than the sink check.
   */
  @Test
  public void testUnknownSinkWithNoDeclaredCeilingStillGetsNoUpscale()
  {
    EnhancementAdvisor.Advice a =
        advise(1920, 1080, false, 30, 0, 0, undeclaredSurface(), "auto", "none");
    assertEquals(a.getTier(), EnhancementTier.NONE,
        "Inference reads what the client declared; a legacy client declared nothing");
  }

  @Test
  public void testUnknownSinkStillAllowsDeinterlace()
  {
    EnhancementAdvisor.Advice a =
        advise(1920, 1080, true, 30, 0, 0, undeclaredSurface(), "auto", "none");
    assertEquals(a.getTier(), EnhancementTier.DEINTERLACE_ONLY,
        "Deinterlacing doesn't change frame size, so an unknown sink doesn't block it");
  }

  @Test
  public void testUnknownSourceHeightIsRefused()
  {
    EnhancementAdvisor.Advice a =
        advise(0, 0, false, 0, 3840, 2160, surface(3840, 2160, 60), "auto", "none");
    assertEquals(a.getVerdict(), EnhancementAdvisor.Verdict.UNKNOWN_SOURCE);
  }

  @Test
  public void testMinGainThresholdIsConfigurable()
  {
    // 1080 -> 1440 is only a 1.33x gain, below the 1.5x default.
    EnhancementAdvisor.Advice a =
        advise(1920, 1080, false, 30, 2560, 1440, surface(3840, 2160, 60), "auto", "none");
    assertEquals(a.getTier(), EnhancementTier.NONE, "1.33x is under the default 1.5x bar");

    Sage.put(EnhancementAdvisor.PROP_MIN_GAIN_TENTHS, "13");
    EnhancementAdvisor.Advice b =
        advise(1920, 1080, false, 30, 2560, 1440, surface(3840, 2160, 60), "auto", "none");
    assertEquals(b.getTier(), EnhancementTier.ENHANCE_1440P, "1.3x bar admits the 1440p tier");
  }

  // ---------- client-side upscaler deference ----------

  @Test
  public void testActiveLocalUpscalerWins()
  {
    EnhancementAdvisor.Advice a =
        advise(1920, 1080, true, 30, 3840, 2160, surface(3840, 2160, 60), "auto", "active");
    assertEquals(a.getTier(), EnhancementTier.NONE);
    assertEquals(a.getVerdict(), EnhancementAdvisor.Verdict.CLIENT_UPSCALES_LOCALLY,
        "Never spend a GPU session fighting a Shield's own upscaler");
  }

  @Test
  public void testExplicitLocalPreferenceWins()
  {
    EnhancementAdvisor.Advice a =
        advise(1920, 1080, true, 30, 3840, 2160, surface(3840, 2160, 60), "local", "none");
    assertEquals(a.getVerdict(), EnhancementAdvisor.Verdict.CLIENT_PREFERS_LOCAL);
  }

  @Test
  public void testExplicitServerPreferenceBeatsActiveLocalUpscaler()
  {
    EnhancementAdvisor.Advice a =
        advise(1920, 1080, true, 30, 3840, 2160, surface(3840, 2160, 60), "server", "active");
    assertEquals(a.getTier(), EnhancementTier.ENHANCE_2160P,
        "An explicit server preference overrides the local upscaler deference");
  }

  @Test
  public void testAdminCanOverrideLocalDeference()
  {
    Sage.put(EnhancementAdvisor.PROP_OVERRIDE_LOCAL, "true");
    EnhancementAdvisor.Advice a =
        advise(1920, 1080, true, 30, 3840, 2160, surface(3840, 2160, 60), "auto", "active");
    assertEquals(a.getTier(), EnhancementTier.ENHANCE_2160P);
  }

  @Test
  public void testAvailableButInactiveLocalUpscalerDoesNotBlock()
  {
    EnhancementAdvisor.Advice a =
        advise(1920, 1080, true, 30, 3840, 2160, surface(3840, 2160, 60), "auto", "available");
    assertEquals(a.getTier(), EnhancementTier.ENHANCE_2160P,
        "Only an ACTIVE local upscaler defers; merely available does not");
  }

  // ---------- surface decode gate ----------

  @Test
  public void testSurfaceLimitDowngradesRatherThanRefusing()
  {
    // Sink is 4K but the decoder tops out at 1080p: must fall back, not send 4K.
    EnhancementAdvisor.Advice a =
        advise(1280, 720, false, 30, 3840, 2160, surface(1920, 1080, 60), "auto", "none");
    assertEquals(a.getTier(), EnhancementTier.ENHANCE_1080P,
        "Must downgrade to what the decoder proved it can handle");
  }

  @Test
  public void testUndeclaredSurfaceLimitsBlockUpscale()
  {
    EnhancementAdvisor.Advice a =
        advise(1920, 1080, false, 30, 3840, 2160, undeclaredSurface(), "auto", "none");
    assertEquals(a.getTier(), EnhancementTier.NONE);
    assertEquals(a.getVerdict(), EnhancementAdvisor.Verdict.SURFACE_CANNOT_DECODE,
        "A surface that never declared limits must not receive an upscaled stream");
  }

  @Test
  public void testSurfaceFpsLimitDowngrades()
  {
    // 4K60 decoder limit is 30fps -- the 60fps source can't be sent at 2160p.
    PlaybackSurface s = surface(3840, 2160, 30);
    EnhancementAdvisor.Advice a =
        advise(1280, 720, false, 60, 3840, 2160, s, "auto", "none");
    assertEquals(a.getTier(), EnhancementTier.NONE,
        "Every upscale tier exceeds the 30fps decoder limit for 60fps content");
  }

  @Test
  public void testNullSurfaceIsRefusedNotTrusted()
  {
    // This used to return ENHANCE_2160P on the theory that an unidentifiable
    // surface should "defer" rather than invent a limit. That was backwards:
    // deferring here means shipping 4K to a client that has proved nothing,
    // which is the one outcome the gate exists to prevent. With no surface AND
    // no per-codec ceilings there is no evidence, and no evidence means no.
    EnhancementAdvisor.Advice a =
        advise(1920, 1080, false, 30, 3840, 2160, null, "auto", "none");
    assertEquals(a.getTier(), EnhancementTier.NONE,
        "an unidentifiable surface is not permission to upscale");
  }

  // ---------- admin ceiling ----------

  @Test
  public void testAdminMaxHeightClampsTier()
  {
    Sage.put(EnhancementAdvisor.PROP_MAX_HEIGHT, "1440");
    EnhancementAdvisor.Advice a =
        advise(1280, 720, false, 30, 3840, 2160, surface(3840, 2160, 60), "auto", "none");
    assertEquals(a.getTier(), EnhancementTier.ENHANCE_1440P,
        "The admin ceiling must cap the tier below the sink size");
  }

  @Test
  public void testAdminCeilingBelowSourceFallsBackToDeinterlace()
  {
    Sage.put(EnhancementAdvisor.PROP_MAX_HEIGHT, "1080");
    EnhancementAdvisor.Advice a =
        advise(1920, 1080, true, 30, 3840, 2160, surface(3840, 2160, 60), "auto", "none");
    assertEquals(a.getTier(), EnhancementTier.DEINTERLACE_ONLY,
        "A ceiling at the source height leaves nothing to gain but the deinterlace");
  }

  // ---------- cheap pre-check ----------

  @Test
  public void testIsCandidateSource()
  {
    assertTrue(EnhancementAdvisor.isCandidateSource(1080, true));
    assertTrue(EnhancementAdvisor.isCandidateSource(720, false));
    assertTrue(EnhancementAdvisor.isCandidateSource(480, true), "SD interlaced can deinterlace");
    assertFalse(EnhancementAdvisor.isCandidateSource(480, false), "SD progressive has nothing to do");
    assertFalse(EnhancementAdvisor.isCandidateSource(0, true), "Unknown height is never a candidate");
  }
}
