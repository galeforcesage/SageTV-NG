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
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.testng.annotations.Test;

import sage.enhance.EnhancementTier;

/**
 * The warmup SPI surface: the two {@link ScaleProvider} default methods preserve
 * cold-start behavior (a provider that overrides nothing warms to {@code null}
 * and its {@code plan(req, warm)} closes the context then cold-plans), and a
 * warm {@link ScaleExecutionPlan} carries the running worker while still
 * rendering as an external-process plan.
 */
public class ScaleProviderWarmupTest
{
  private static final List<String> ARGV =
      Arrays.asList("worker", "--mode", "stream", "--width", "3840");

  private static ScaleRequest req()
  {
    return new ScaleRequest(EnhancementTier.ENHANCE_2160P, 3840, 2160, 1280, 720,
        false, "scale_cuda", ScaleRequest.Purpose.LIVE);
  }

  /** A minimal provider that overrides only the cold plan(); warmup/plan(req,warm)
   *  come from the interface defaults. */
  private static final class ColdOnlyProvider implements ScaleProvider
  {
    final ScaleExecutionPlan cold = new ScaleExecutionPlan(
        ExecutionForm.EXTERNAL_PROCESS, ARGV, 3840, 2160, "rgb24", "test");
    public String id() { return "cold-only"; }
    public ScaleProviderCapabilities capabilities() { return null; }
    public ScaleProviderAvailability probe(ScaleRequest r) { return null; }
    public ScaleExecutionPlan plan(ScaleRequest r) { return cold; }
  }

  /** A WarmContext that records whether close() ran. */
  private static final class RecordingWarm implements WarmContext
  {
    boolean closed;
    final boolean valid;
    RecordingWarm(boolean valid) { this.valid = valid; }
    public long ttlMillis() { return 45_000L; }
    public boolean isValid() { return valid; }
    public void close() { closed = true; }
  }

  @Test
  public void defaultWarmupReturnsNull()
  {
    assertNull(new ColdOnlyProvider().warmup(req()), "default warmup is a no-op");
  }

  @Test
  public void defaultWarmPlanClosesContextAndColdPlans()
  {
    ColdOnlyProvider p = new ColdOnlyProvider();
    RecordingWarm warm = new RecordingWarm(true);
    ScaleExecutionPlan out = p.plan(req(), warm);
    assertSame(out, p.cold, "default plan(req,warm) delegates to the cold plan");
    assertTrue(warm.closed, "default plan(req,warm) closes the unused warm context");
    assertNull(out.getWarmProcess(), "cold plan carries no warm process");
  }

  @Test
  public void defaultWarmPlanToleratesNullContext()
  {
    ColdOnlyProvider p = new ColdOnlyProvider();
    assertSame(p.plan(req(), null), p.cold, "null warm context still cold-plans");
  }

  @Test
  public void warmProcessPlanStillRendersExternal()
  {
    Process fake = new java.lang.Process()
    {
      public java.io.OutputStream getOutputStream() { return null; }
      public java.io.InputStream getInputStream() { return null; }
      public java.io.InputStream getErrorStream() { return null; }
      public int waitFor() { return 0; }
      public int exitValue() { return 0; }
      public void destroy() {}
    };
    ScaleExecutionPlan p = ScaleExecutionPlan.externalWithWarmProcess(
        ARGV, 3840, 2160, "rgb24", "VSR", fake);
    assertTrue(p.rendersExternalProcess(), "warm external plan still renders");
    assertTrue(p.isRenderable(), "renderable");
    assertSame(p.getWarmProcess(), fake, "warm process round-trips");
    assertEquals(p.getExternalArgv(), ARGV, "argv retained for diagnostics");
    assertEquals(p.getOutputWidth(), 3840, "output width");
    assertEquals(p.getOutputHeight(), 2160, "output height");
  }

  @Test
  public void coldExternalPlanHasNoWarmProcess()
  {
    ScaleExecutionPlan p = new ScaleExecutionPlan(
        ExecutionForm.EXTERNAL_PROCESS, ARGV, 3840, 2160, "rgb24", "VSR");
    assertNull(p.getWarmProcess(), "cold plan has no warm process");
    assertTrue(p.rendersExternalProcess(), "still renders external");
    assertFalse(p.rendersFilterFragment(), "not a filter plan");
  }
}
