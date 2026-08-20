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

/**
 * The dry-run gate is the last thing standing between a decision and a
 * re-encode, so it gets its own tests.
 */
public class EnhancementDryRunTest
{
  private static PlaybackSurface surface()
  {
    List<String> d = Arrays.asList("pull-xcode");
    return new PlaybackSurface("s1", "r", 0, d,
        Arrays.asList("HEVC"), Arrays.asList("EAC3"), Arrays.asList("MP4"),
        "all", "client", null, 3840, 2160, 60);
  }

  @BeforeMethod
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
  }

  @AfterMethod
  public void tearDown()
  {
    Sage.remove(EnhancementAdvisor.PROP_ENABLED);
    Sage.remove(EnhancementDryRun.PROP_DRY_RUN);
  }

  private EnhancementTier eval()
  {
    return EnhancementDryRun.evaluateAndLog("client1", "test", 1920, 1080, true, 30,
        3840, 2160, surface(), "auto", "none", true);
  }

  @Test
  public void testDryRunIsTheDefaultEvenWhenEnabled()
  {
    Sage.put(EnhancementAdvisor.PROP_ENABLED, "true");
    assertTrue(EnhancementDryRun.isDryRun(),
        "Enabling the feature must NOT be enough to start re-encoding");
    assertFalse(EnhancementDryRun.isLive());
    assertEquals(eval(), EnhancementTier.NONE,
        "Dry-run must return NONE no matter what the advisor decided");
  }

  @Test
  public void testBothSwitchesRequiredToGoLive()
  {
    Sage.put(EnhancementAdvisor.PROP_ENABLED, "true");
    Sage.put(EnhancementDryRun.PROP_DRY_RUN, "false");
    if (EnhancementDryRun.PIPELINE_WIRED)
    {
      assertTrue(EnhancementDryRun.isLive());
      assertEquals(eval(), EnhancementTier.ENHANCE_2160P);
    }
    else
    {
      // Written to survive the flip: once the pipeline is wired this branch
      // stops running and the assertions above take over.
      assertFalse(EnhancementDryRun.isLive(),
          "The phase interlock must outrank both switches until the pipeline is wired");
      assertEquals(eval(), EnhancementTier.NONE);
    }
  }

  /**
   * The tier is advertised to the client in CAP_EFFECTIVE_DELIVERY, so going
   * live before the pipeline can actually apply it would make the server
   * announce an enhancement it never performed.
   */
  @Test
  public void testInterlockOutranksBothSwitchesUntilPipelineIsWired()
  {
    if (EnhancementDryRun.PIPELINE_WIRED) return; // nothing left to guard

    Sage.put(EnhancementAdvisor.PROP_ENABLED, "true");
    Sage.put(EnhancementDryRun.PROP_DRY_RUN, "false");
    assertTrue(EnhancementDryRun.isDryRun(),
        "Clearing the property must not defeat the interlock");
    assertFalse(EnhancementDryRun.isLive());
    assertEquals(eval(), EnhancementTier.NONE,
        "No tier may reach the delivery token while the pipeline is unwired");
  }

  @Test
  public void testClearingDryRunAloneDoesNothing()
  {
    Sage.put(EnhancementDryRun.PROP_DRY_RUN, "false");
    assertFalse(EnhancementDryRun.isLive(),
        "Clearing dry-run without enabling the feature must stay off");
    assertEquals(eval(), EnhancementTier.NONE);
  }

  @Test
  public void testDisabledFeatureSkipsEvaluationEntirely()
  {
    assertEquals(eval(), EnhancementTier.NONE);
    assertFalse(EnhancementDryRun.isLive());
  }
}
