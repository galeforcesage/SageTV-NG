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
import sage.client.ClientConstraints;
import sage.client.PlaybackSurface;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * The per-codec decode gate and the sink clamp -- the "Client Plan & Contract
 * Delta" (§1.1-§1.4).
 *
 * <p>The theme of every test here is that the server never guesses on the
 * client's behalf. A codec the client didn't describe, a decoder it flagged
 * software, or a panel dimension it never declared all mean "no", because the
 * cost of guessing wrong is an unplayable stream in someone's living room.
 */
public class EnhancementCodecGateTest
{
  private static final List<String> DELIVERY = Arrays.asList("pull-xcode");
  private static final List<String> VCODECS  = Arrays.asList("HEVC", "H264");
  private static final List<String> ACODECS  = Arrays.asList("EAC3", "AAC");
  private static final List<String> CONT     = Arrays.asList("MP4");

  private static PlaybackSurface surface(int maxW, int maxH, int maxFps)
  {
    return new PlaybackSurface("s1", "route", 0, DELIVERY, VCODECS, ACODECS, CONT,
        "all", "client", null, maxW, maxH, maxFps);
  }

  private static PlaybackSurface undeclaredSurface()
  {
    return new PlaybackSurface("s1", "route", 0, DELIVERY, VCODECS, ACODECS, CONT);
  }

  private static ClientConstraints codecs(String videoRows)
  {
    return ClientConstraints.parse("exoplayer", videoRows, "", "");
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
    Sage.remove(EnhancementAdvisor.PROP_FORM_FACTORS);
  }

  private EnhancementAdvisor.Advice advise(int sw, int sh, int sinkW, int sinkH,
      PlaybackSurface s, ClientConstraints c)
  {
    return EnhancementAdvisor.advise(sw, sh, false, 60, sinkW, sinkH, s, c,
        "auto", "none", true);
  }

  // ---------- §1.4 clamp the target to the sink, in BOTH dimensions ----------

  /**
   * The case that prompted this: a Galaxy Tab S8 Ultra. Its panel is 2960x1848,
   * so 2160p would be manufactured and then immediately thrown away by the
   * client's own downscale -- paying full 4K encode cost to deliver a 1848-line
   * picture. The honest answer is the largest tier that FITS, which is 1440p.
   */
  @Test
  public void testTabletPanelClampsToOneFourFortyNotTwoOneSixty()
  {
    assertEquals(EnhancementTier.clampToSink(EnhancementTier.ENHANCE_2160P, 2960, 1848),
        EnhancementTier.ENHANCE_1440P,
        "a 2960x1848 tablet panel must cap at 1440p, never 2160p");
  }

  /**
   * Height alone is not enough. A portrait-ish or pillarboxed sink can be tall
   * enough for the tier while being too narrow for it, and sending 2560 columns
   * to a 1920-column panel is the same waste in the other axis.
   */
  @Test
  public void testNarrowSinkClampsOnWidth()
  {
    assertEquals(EnhancementTier.clampToSink(EnhancementTier.ENHANCE_2160P, 1920, 2160),
        EnhancementTier.ENHANCE_1080P,
        "a 1920-wide sink cannot take 1440p's 2560 columns");
  }

  @Test
  public void testExact4kSinkKeeps2160p()
  {
    assertEquals(EnhancementTier.clampToSink(EnhancementTier.ENHANCE_2160P, 3840, 2160),
        EnhancementTier.ENHANCE_2160P, "an exact 4K sink keeps 2160p");
  }

  /** End-to-end: the tablet gets 1440p out of the advisor, not just the clamp. */
  @Test
  public void testTabletEndToEndOffers1440p()
  {
    EnhancementAdvisor.Advice a = advise(1920, 1080, 2960, 1848,
        surface(3840, 2160, 60), null);
    assertEquals(a.getTier(), EnhancementTier.ENHANCE_1440P,
        "1080p source on a 2960x1848 panel should be offered 1440p");
    assertEquals(a.getVerdict(), EnhancementAdvisor.Verdict.OFFERED, "verdict");
  }

  // ---------- §1.1 per-codec decode ceilings gate the upscale ----------

  @Test
  public void testCodecCeilingAlonePermitsUpscale()
  {
    ClientConstraints c = codecs("HEVC;decoder=hw;maxW=3840;maxH=2160;maxFps=60");
    EnhancementAdvisor.Advice a = advise(1920, 1080, 3840, 2160, undeclaredSurface(), c);
    assertEquals(a.getTier(), EnhancementTier.ENHANCE_2160P,
        "a codec row declaring 4K hw decode is sufficient on its own");
  }

  @Test
  public void testCodecCeilingBelowTargetStepsDown()
  {
    ClientConstraints c = codecs("HEVC;decoder=hw;maxW=2560;maxH=1440;maxFps=60");
    EnhancementAdvisor.Advice a = advise(1920, 1080, 3840, 2160, undeclaredSurface(), c);
    assertEquals(a.getTier(), EnhancementTier.ENHANCE_1440P,
        "a 1440-capable decoder must step down from 2160p, not be refused outright");
  }

  // ---------- §1.3 decoder=sw is a hard block ----------

  @Test
  public void testSoftwareDecoderIsBlockedEvenAt4k()
  {
    ClientConstraints c = codecs("HEVC;decoder=sw;maxW=3840;maxH=2160;maxFps=60");
    EnhancementAdvisor.Advice a = advise(1920, 1080, 3840, 2160, undeclaredSurface(), c);
    assertEquals(a.getTier(), EnhancementTier.NONE,
        "software decode must never be handed 4K, whatever geometry it claims");
    assertEquals(a.getVerdict(), EnhancementAdvisor.Verdict.SURFACE_CANNOT_DECODE, "verdict");
  }

  @Test
  public void testHardwareRowWinsWhenASoftwareRowAlsoExists()
  {
    ClientConstraints c = codecs(
        "H264;decoder=sw;maxW=3840;maxH=2160,HEVC;decoder=hw;maxW=3840;maxH=2160");
    assertEquals(c.pickDecodableCodec(3840, 2160, 60), "HEVC",
        "the eligible set must exclude the sw row and pick the hw one");
  }

  // ---------- fail-closed: silence is not consent ----------

  @Test
  public void testUndeclaredEverythingRefusesUpscale()
  {
    EnhancementAdvisor.Advice a = advise(1920, 1080, 3840, 2160, undeclaredSurface(), null);
    assertEquals(a.getTier(), EnhancementTier.NONE,
        "no surface limits and no codec limits must refuse the upscale");
  }

  /**
   * A null surface used to sail straight through the gate, so a client that had
   * declared nothing at all could be sent 4K. Listing a codec says nothing about
   * the resolution its decoder was built for.
   */
  @Test
  public void testNullSurfaceDoesNotBypassTheGate()
  {
    EnhancementAdvisor.Advice a = advise(1920, 1080, 3840, 2160, null, null);
    assertEquals(a.getTier(), EnhancementTier.NONE,
        "a null surface must not be treated as permission");
  }

  @Test
  public void testCodecRowWithoutGeometryIsNotEligible()
  {
    ClientConstraints c = codecs("HEVC;decoder=hw");
    assertFalse(c.canDecodeAny(3840, 2160, 60),
        "a codec row with no maxW/maxH declares nothing about resolution");
    assertFalse(c.hasAnyDeclaredOutputLimits(), "no declared limits");
  }

  @Test
  public void testUnparseableGeometryIsTreatedAsUndeclared()
  {
    ClientConstraints c = codecs("HEVC;decoder=hw;maxW=wide;maxH=2160");
    assertFalse(c.canDecodeAny(3840, 2160, 60),
        "an unparseable ceiling must never be guessed at");
  }

  // ---------- frame-rate ceiling ----------

  @Test
  public void testDeclaredFpsCeilingIsEnforced()
  {
    ClientConstraints c = codecs("HEVC;decoder=hw;maxW=3840;maxH=2160;maxFps=30");
    assertFalse(c.canDecodeAny(3840, 2160, 60), "60fps exceeds a declared 30fps ceiling");
    assertTrue(c.canDecodeAny(3840, 2160, 30), "30fps fits");
  }

  @Test
  public void testAbsentFpsCeilingIsTolerated()
  {
    ClientConstraints c = codecs("HEVC;decoder=hw;maxW=3840;maxH=2160");
    assertTrue(c.canDecodeAny(3840, 2160, 60),
        "geometry is the ceiling that breaks decoders; an absent fps limit is not fatal");
  }

  // ---------- either source suffices ----------

  @Test
  public void testSurfaceAlonePermitsUpscaleWithNoCodecRows()
  {
    EnhancementAdvisor.Advice a = advise(1920, 1080, 3840, 2160, surface(3840, 2160, 60), null);
    assertEquals(a.getTier(), EnhancementTier.ENHANCE_2160P,
        "surface limits remain sufficient on their own");
  }

  /**
   * The two sources are OR'd, not AND'd. They are independent reports of the
   * same underlying decoder, and a client that answered one of them fully
   * should not be penalised for leaving the other blank.
   */
  @Test
  public void testCodecRowsRescueASurfaceThatDeclaredTooLittle()
  {
    ClientConstraints c = codecs("HEVC;decoder=hw;maxW=3840;maxH=2160;maxFps=60");
    EnhancementAdvisor.Advice a = advise(1920, 1080, 3840, 2160, surface(1920, 1080, 60), c);
    assertEquals(a.getTier(), EnhancementTier.ENHANCE_2160P,
        "a 4K-capable codec row should permit 4K even when the surface understated");
  }

  // ---------- deinterlace stays exempt ----------

  @Test
  public void testDeinterlaceIsNotSubjectToTheDecodeGate()
  {
    EnhancementAdvisor.Advice a = EnhancementAdvisor.advise(1920, 1080, true, 30,
        1920, 1080, undeclaredSurface(), null, "auto", "none", true);
    assertEquals(a.getTier(), EnhancementTier.DEINTERLACE_ONLY,
        "deinterlace emits the geometry the client was already decoding");
  }

  // ---------- form-factor eligibility (opt-in) ----------

  private EnhancementAdvisor.Advice adviseForm(String formFactor, boolean interlaced)
  {
    return adviseForm(formFactor, interlaced, 3840, 2160);
  }

  /**
   * Handheld cases must pass a handheld-sized sink: a device reporting a 4K sink
   * is read as driving an external display and is deliberately not excluded.
   */
  private EnhancementAdvisor.Advice adviseForm(String formFactor, boolean interlaced,
      int sinkW, int sinkH)
  {
    return EnhancementAdvisor.advise(1920, 1080, interlaced, 30, sinkW, sinkH,
        surface(3840, 2160, 60), null, formFactor, "auto", "none", true);
  }

  /** Ships open: nobody is excluded until an admin looks at their own traffic. */
  @Test
  public void testFormFactorGateIsOpenByDefault()
  {
    assertEquals(adviseForm("TABLET", false).getTier(), EnhancementTier.ENHANCE_2160P,
        "with no configured list, every form factor stays eligible");
  }

  @Test
  public void testNarrowedListExcludesOtherFormFactors()
  {
    Sage.put(EnhancementAdvisor.PROP_FORM_FACTORS, "tv");
    EnhancementAdvisor.Advice a = adviseForm("TABLET", false, 2960, 1848);
    assertEquals(a.getTier(), EnhancementTier.NONE, "tablet excluded when the list is tv-only");
    assertEquals(a.getVerdict(), EnhancementAdvisor.Verdict.FORM_FACTOR_EXCLUDED, "verdict");
  }

  @Test
  public void testNarrowedListStillAdmitsListedFormFactors()
  {
    Sage.put(EnhancementAdvisor.PROP_FORM_FACTORS, "tv,desktop");
    assertEquals(adviseForm("TV", false).getTier(), EnhancementTier.ENHANCE_2160P,
        "a listed form factor is unaffected");
    assertEquals(adviseForm("desktop", false).getTier(), EnhancementTier.ENHANCE_2160P,
        "matching is case-insensitive and reads every CSV entry");
  }

  /**
   * The exclusion is about where an upscale is worth spending a GPU session, not
   * about safety, so it must not take deinterlacing away from a handheld.
   */
  @Test
  public void testExcludedFormFactorStillGetsDeinterlace()
  {
    Sage.put(EnhancementAdvisor.PROP_FORM_FACTORS, "tv");
    assertEquals(adviseForm("PHONE", true, 2400, 1080).getTier(), EnhancementTier.DEINTERLACE_ONLY,
        "deinterlace is cheap and still worth it on a handheld");
  }

  /** A client predating the field must not be silently dropped. */
  @Test
  public void testUndeclaredFormFactorIsNotExcluded()
  {
    Sage.put(EnhancementAdvisor.PROP_FORM_FACTORS, "tv");
    assertEquals(adviseForm(null, false).getTier(), EnhancementTier.ENHANCE_2160P,
        "an undeclared form factor is not grounds for exclusion");
    assertTrue(EnhancementAdvisor.isFormFactorEligible("  ", 1920, 1080, null),
        "blank counts as undeclared");
  }

  // ---------- SUPPORTS_4K: the user's answer beats every inference ----------

  private EnhancementAdvisor.Advice adviseUhd(String formFactor, int sinkW, int sinkH,
      PlaybackSurface s, ClientConstraints c, Boolean supports4k)
  {
    return EnhancementAdvisor.advise(1920, 1080, false, 60, sinkW, sinkH, s, c,
        formFactor, supports4k, "auto", "none", true);
  }

  /**
   * The headline case: a phone in desktop mode driving a television. Form
   * factor still reads PHONE and the admin has restricted upscaling to TVs, but
   * the user turned 4K on, so it gets served.
   */
  @Test
  public void testPhoneWith4kOnIsServed4kDespiteTvOnlyPolicy()
  {
    Sage.put(EnhancementAdvisor.PROP_FORM_FACTORS, "tv");
    EnhancementAdvisor.Advice a = adviseUhd("PHONE", 3840, 2160,
        surface(3840, 2160, 60), null, Boolean.TRUE);
    assertEquals(a.getTier(), EnhancementTier.ENHANCE_2160P,
        "a phone that reports 4K support must be served 4K");
    assertEquals(a.getVerdict(), EnhancementAdvisor.Verdict.OFFERED, "verdict");
  }

  /**
   * Same phone, but HDMI sensing reported the handset's own panel. The user's
   * override is the only correct signal in the room, so it raises the sink too
   * -- otherwise the clamp would quietly cap a 4K television at phone size.
   */
  @Test
  public void testForced4kRaisesAMisSensedSink()
  {
    Sage.put(EnhancementAdvisor.PROP_FORM_FACTORS, "tv");
    EnhancementAdvisor.Advice a = adviseUhd("PHONE", 1080, 2340,
        surface(3840, 2160, 60), null, Boolean.TRUE);
    assertEquals(a.getTier(), EnhancementTier.ENHANCE_2160P,
        "a mis-sensed sink must not cap a user who says 4K works");
  }

  /**
   * And it satisfies the decode gate. The declared ceilings come from the same
   * auto-detection the user is overriding, so refusing here would make the
   * override useless in exactly the case it was built for.
   */
  @Test
  public void testForced4kSatisfiesTheDecodeGate()
  {
    EnhancementAdvisor.Advice a = adviseUhd("PHONE", 3840, 2160,
        undeclaredSurface(), null, Boolean.TRUE);
    assertEquals(a.getTier(), EnhancementTier.ENHANCE_2160P,
        "an explicit yes stands in for the declaration the client never made");
  }

  /** Without the override, that same phone is excluded by a tv-only policy. */
  @Test
  public void testPhoneOnAutoIsStillExcludedByTvOnlyPolicy()
  {
    Sage.put(EnhancementAdvisor.PROP_FORM_FACTORS, "tv");
    EnhancementAdvisor.Advice a = adviseUhd("PHONE", 2400, 1080,
        surface(3840, 2160, 60), null, null);
    assertEquals(a.getTier(), EnhancementTier.NONE,
        "auto plus a handset-sized sink stays excluded");
    assertEquals(a.getVerdict(), EnhancementAdvisor.Verdict.FORM_FACTOR_EXCLUDED, "verdict");
  }

  /**
   * A phone on auto whose sink is too big to be its own panel is inferred to be
   * driving something external, so clients that never implement SUPPORTS_4K
   * still work.
   */
  @Test
  public void testPhoneOnAutoWithA4kSinkIsInferredExternal()
  {
    Sage.put(EnhancementAdvisor.PROP_FORM_FACTORS, "tv");
    EnhancementAdvisor.Advice a = adviseUhd("PHONE", 3840, 2160,
        surface(3840, 2160, 60), null, null);
    assertEquals(a.getTier(), EnhancementTier.ENHANCE_2160P,
        "a 3840x2160 sink is not a phone panel");
  }

  /** The tablet's own 2960x1848 panel must NOT be inferred external. */
  @Test
  public void testTabletOwnPanelIsNotInferredExternal()
  {
    assertFalse(EnhancementAdvisor.isSinkExternal(2960, 1848, null),
        "the largest shipping tablet panel is still a built-in panel");
    assertTrue(EnhancementAdvisor.isSinkExternal(3840, 2160, null),
        "4K is beyond any built-in handheld panel");
  }

  /** An explicit no caps below 4K but still allows a useful upscale. */
  @Test
  public void testSupports4kNoCapsAt1440p()
  {
    EnhancementAdvisor.Advice a = adviseUhd("TV", 3840, 2160,
        surface(3840, 2160, 60), null, Boolean.FALSE);
    assertEquals(a.getTier(), EnhancementTier.ENHANCE_1440P,
        "an explicit no is a ceiling, not a refusal");
  }

  /** The override is about capability, not about overruling safety limits. */
  @Test
  public void testForced4kStillRespectsTheAdminCeiling()
  {
    Sage.put(EnhancementAdvisor.PROP_MAX_HEIGHT, "1440");
    EnhancementAdvisor.Advice a = adviseUhd("PHONE", 3840, 2160,
        surface(3840, 2160, 60), null, Boolean.TRUE);
    assertEquals(a.getTier(), EnhancementTier.ENHANCE_1440P,
        "a client override cannot exceed what the server admin allows");
  }
}
