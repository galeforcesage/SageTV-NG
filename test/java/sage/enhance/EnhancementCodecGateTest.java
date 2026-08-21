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
    assertTrue(EnhancementAdvisor.isFormFactorEligible("  ", 1920, 1080),
        "blank counts as undeclared");
  }

  // ---------- the real client override: it rides on the sink ----------
  //
  // The Android client does NOT send a separate 4K flag. Its user-facing
  // Auto / Always / Never setting is expressed entirely through
  // DISPLAY_SINK_RESOLUTION, and the value is always the true physical panel --
  // never a fabricated 4K:
  //
  //   Never  -> ""            (full opt-out)
  //   Auto   -> physical WxH, but only when the client deems itself eligible
  //             (TV-class, external/HDMI, or an internal panel over 12")
  //   Always -> physical WxH, unconditionally
  //
  // Two consequences the server must respect. First, "Auto" and "Always" are
  // INDISTINGUISHABLE on the wire, so the server cannot apply different policy
  // to them -- a sink that arrives at all is a request to upscale up to that
  // size. Second, the per-codec rows are sent unconditionally and are unaffected
  // by the setting, so a decode refusal is always a hardware fact.

  private EnhancementAdvisor.Advice adviseSink(String formFactor, int sinkW, int sinkH,
      PlaybackSurface s)
  {
    return EnhancementAdvisor.advise(1280, 720, false, 60, sinkW, sinkH, s, null,
        formFactor, "auto", "none", true);
  }

  /** "Never" arrives as an empty sink, and must refuse rather than guess. */
  @Test
  public void testEmptySinkIsAFullOptOut()
  {
    EnhancementAdvisor.Advice a = adviseSink("TV", 0, 0, surface(3840, 2160, 60));
    assertEquals(a.getTier(), EnhancementTier.NONE, "no sink means no upscale");
    assertEquals(a.getVerdict(), EnhancementAdvisor.Verdict.UNKNOWN_SINK, "verdict");
  }

  /**
   * The docked-phone case, which is what the whole override exists for. The
   * client reports the TELEVISION's geometry, not the handset's, so the server
   * needs no special knowledge -- and the sink is large enough to read as an
   * external display, so a tv-only admin policy doesn't refuse it either.
   */
  @Test
  public void testDockedPhoneReportsTheTelevisionAndIsServed()
  {
    Sage.put(EnhancementAdvisor.PROP_FORM_FACTORS, "tv");
    EnhancementAdvisor.Advice a = adviseSink("PHONE", 3840, 2160, surface(3840, 2160, 60));
    assertEquals(a.getTier(), EnhancementTier.ENHANCE_2160P,
        "a phone driving a 4K TV reports 3840x2160 and must be served");
  }

  /**
   * "Always" on a handset sends the handset's own panel. The user opted in, so
   * they get the upscale their panel can actually show -- 1080p-class from a
   * 720p source -- and emphatically not a fabricated 4K.
   */
  @Test
  public void testAlwaysOnAPhoneUpscalesToThePhonePanelOnly()
  {
    EnhancementAdvisor.Advice a = adviseSink("PHONE", 2400, 1080, surface(3840, 2160, 60));
    assertEquals(a.getTier(), EnhancementTier.ENHANCE_1080P,
        "the reported panel is the ceiling; never invent 4K for a 6-inch screen");
  }

  /** The tablet's own panel is not mistaken for an external display. */
  @Test
  public void testBuiltInPanelsAreNotInferredExternal()
  {
    assertFalse(EnhancementAdvisor.isSinkExternal(2960, 1848),
        "the largest shipping tablet panel is still a built-in panel");
    assertFalse(EnhancementAdvisor.isSinkExternal(2400, 1080), "phone panel");
    assertTrue(EnhancementAdvisor.isSinkExternal(3840, 2160),
        "4K is beyond any built-in handheld panel");
  }

  /**
   * The setting only ever moves the sink -- it can never talk the server past
   * the decode gate, because the per-codec rows report real MediaCodec limits
   * and are sent regardless of the setting.
   */
  @Test
  public void testOptingInCannotOverrideARealDecodeCeiling()
  {
    ClientConstraints c = codecs("HEVC;decoder=hw;maxW=1920;maxH=1080;maxFps=60");
    EnhancementAdvisor.Advice a = EnhancementAdvisor.advise(1280, 720, false, 60,
        3840, 2160, undeclaredSurface(), c, "TV", "auto", "none", true);
    assertEquals(a.getTier(), EnhancementTier.ENHANCE_1080P,
        "a 4K sink cannot beat a 1080p decoder; the tier walks down to what plays");
  }
}
