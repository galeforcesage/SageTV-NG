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
}
