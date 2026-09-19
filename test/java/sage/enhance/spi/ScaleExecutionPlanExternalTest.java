/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package sage.enhance.spi;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.testng.annotations.Test;

/**
 * The {@link ExecutionForm#EXTERNAL_PROCESS} renderability gate: a complete
 * external-process plan is renderable and exposes its worker argv/geometry; an
 * incomplete one is rejected so the registry falls back to the built-in scaler.
 */
public class ScaleExecutionPlanExternalTest
{
  private static final List<String> ARGV =
      Arrays.asList("worker", "--mode", "stream", "--width", "1920");

  @Test
  public void completeExternalPlanIsRenderable()
  {
    ScaleExecutionPlan p = new ScaleExecutionPlan(
        ExecutionForm.EXTERNAL_PROCESS, ARGV, 1920, 1080, "rgb24", "VSR");
    assertTrue(p.rendersExternalProcess(), "complete external plan renders");
    assertTrue(p.isRenderable(), "renderable via external path");
    assertTrue(p.isRenderablePhase0(), "deprecated alias still delegates");
    assertFalse(p.rendersFilterFragment(), "external plan is not a filter fragment");
    assertNull(p.getFfmpegFilter(), "no filter fragment on an external plan");
    assertEquals(p.getExternalArgv(), ARGV, "argv round-trips");
    assertEquals(p.getOutputWidth(), 1920, "output width");
    assertEquals(p.getOutputHeight(), 1080, "output height");
    assertEquals(p.getPipePixelFormat(), "rgb24", "pipe pixel format");
  }

  @Test
  public void nullArgvIsNotRenderable()
  {
    ScaleExecutionPlan p = new ScaleExecutionPlan(
        ExecutionForm.EXTERNAL_PROCESS, (List<String>) null, 1920, 1080, "rgb24", "VSR");
    assertFalse(p.rendersExternalProcess(), "no argv => not renderable");
    assertFalse(p.isRenderable(), "falls back");
  }

  @Test
  public void zeroGeometryIsNotRenderable()
  {
    ScaleExecutionPlan p = new ScaleExecutionPlan(
        ExecutionForm.EXTERNAL_PROCESS, ARGV, 0, 0, "rgb24", "VSR");
    assertFalse(p.rendersExternalProcess(), "no geometry => not renderable");
  }

  @Test
  public void pixelFormatDefaultsToRgb24()
  {
    ScaleExecutionPlan p = new ScaleExecutionPlan(
        ExecutionForm.EXTERNAL_PROCESS, ARGV, 1920, 1080, null, "VSR");
    assertEquals(p.getPipePixelFormat(), "rgb24", "null pixel format defaults to rgb24");
  }

  @Test(expectedExceptions = UnsupportedOperationException.class)
  public void externalArgvIsImmutable()
  {
    ScaleExecutionPlan p = new ScaleExecutionPlan(
        ExecutionForm.EXTERNAL_PROCESS, ARGV, 1920, 1080, "rgb24", "VSR");
    p.getExternalArgv().add("mutate");
  }

  @Test
  public void filterFormStillRenders()
  {
    ScaleExecutionPlan p = new ScaleExecutionPlan(
        ExecutionForm.FFMPEG_FILTER, "scale_cuda=1920:1080", "CUDA");
    assertTrue(p.rendersFilterFragment(), "filter fragment renders");
    assertTrue(p.isRenderable(), "renderable");
    assertFalse(p.rendersExternalProcess(), "not an external plan");
    assertNull(p.getExternalArgv(), "no argv on a filter plan");
  }
}
