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

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import sage.Sage;
import sage.TestUtils;
import sage.client.ClientConstraints;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Regression tests for the client that has no {@code PLAYBACK_SURFACES} at all.
 *
 * <p>These exist because of a defect that shipped: the enhancement decision was
 * evaluated inside the playback-surface negotiation branch, so a client that
 * never advertises surfaces never reached it. That is not a refusal -- it is
 * worse. A refusal is a decision, is logged, and is auditable. This produced
 * <em>nothing</em>: a 4K TV asked for a picture, the server had every input it
 * needed to answer, and no verdict was ever recorded either way.
 *
 * <p>What made it expensive is that the clients it silently excluded were the
 * primary targets. PLAYBACK_SURFACES is an NG addition that non-PWA clients
 * "typically don't implement at all" -- which is to say the Android/Shield
 * client on the 4K television, exactly the device the feature was built for.
 * The tests below therefore assert the property the code failed to have: the
 * absence of a surface changes what the server <em>knows</em>, never whether it
 * bothers to decide.
 */
public class EnhancementSurfacelessClientTest
{
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
    Sage.remove(EnhancementDryRun.PROP_DRY_RUN);
  }

  /**
   * The exact live configuration that produced no log line at all: a Shield on
   * a 4K panel, 1080i source, no surfaces, but per-codec ceilings proving it
   * can decode 4K HEVC. With no surface to consult, the codec rows are the
   * capability channel -- and they are sufficient on their own.
   */
  @Test
  public void testSurfacelessClientStillGetsAnUpscaleDecision()
  {
    EnhancementAdvisor.Advice a = EnhancementAdvisor.advise(
        1920, 1080, true, 30, 3840, 2160,
        null, // no PLAYBACK_SURFACES -- the whole point
        codecs("HEVC;decoder=hw;maxW=3840;maxH=2160;maxFps=60"),
        "auto", "none", true);

    assertEquals(a.getTier(), EnhancementTier.ENHANCE_2160P,
        "a surface-less 4K client with declared 4K HEVC decode must still be offered 2160p");
  }

  /**
   * The failure mode this class guards is silence, so assert on the reason
   * string too: whatever the answer, a surface-less session must produce an
   * explained verdict rather than falling off the end of the decision path.
   */
  @Test
  public void testSurfacelessRefusalIsStillAnExplainedDecision()
  {
    EnhancementAdvisor.Advice a = EnhancementAdvisor.advise(
        1920, 1080, true, 30, 3840, 2160,
        null,
        codecs("HEVC;decoder=hw;maxW=1920;maxH=1080;maxFps=60"), // can't decode 4K
        "auto", "none", true);

    assertTrue(a.getTier() != EnhancementTier.ENHANCE_2160P,
        "a client that declared only 1080p decode must not be sent 2160p");
    assertTrue(a.getVerdict() != null && a.getVerdict().getDescription().length() > 0,
        "a surface-less session must produce a stated reason, never an unrecorded skip");
  }

  /**
   * Absent surface and absent codec rows means the client declared no decode
   * ceiling anywhere. That is unknown, and unknown fails closed for upscaling.
   * Deinterlacing is exempt by design -- it emits the geometry the client was
   * already decoding, so no new ceiling is being asserted.
   */
  @Test
  public void testNoDeclaredCeilingAnywhereFailsClosedForUpscale()
  {
    EnhancementAdvisor.Advice a = EnhancementAdvisor.advise(
        1920, 1080, true, 30, 3840, 2160,
        null, null,
        "auto", "none", true);

    assertTrue(a.getTier() == EnhancementTier.NONE || a.getTier() == EnhancementTier.DEINTERLACE_ONLY,
        "with no declared decode ceiling from any channel, upscaling must not be offered; got " + a.getTier());
  }

  /**
   * Deinterlace-only must survive the surface-less path. It is the cheapest and
   * most broadly applicable win, it asserts no new decode ceiling, and it is
   * precisely what a legacy-path 1080i client should still be able to receive.
   */
  @Test
  public void testDeinterlaceOnlySurvivesWithoutASurface()
  {
    // Sink no bigger than the source, so no upscale is warranted, but the
    // source is interlaced and a GPU deinterlace is still a genuine gain.
    EnhancementAdvisor.Advice a = EnhancementAdvisor.advise(
        1920, 1080, true, 30, 1920, 1080,
        null, null,
        "auto", "none", true);

    assertEquals(a.getTier(), EnhancementTier.DEINTERLACE_ONLY,
        "an interlaced source on a matching sink must still get GPU deinterlace with no surface declared");
  }
}
