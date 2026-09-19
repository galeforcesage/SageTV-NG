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

import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import sage.Sage;
import sage.TestUtils;
import sage.enhance.EnhancementTier;

/**
 * The pre-warm stash: gated off by default, round-trips a context on a hit,
 * enforces single-owner, and closes contexts it cannot hand out.
 */
public class ScaleWarmupCacheTest
{
  private static final String PROP_ENABLED = "playback/gpu_enhance/scale/warmup_enabled";
  private static final String PROP_WAIT = "playback/gpu_enhance/scale/warmup_consume_wait_ms";

  private ScaleWarmupCache cache;

  @BeforeMethod
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    cache = ScaleWarmupCache.getInstance();
    cache.resetForTest();
    // Block briefly on consume so the async warmup future resolves deterministically.
    Sage.put(PROP_WAIT, "3000");
  }

  @AfterMethod
  public void tearDown()
  {
    cache.resetForTest();
    Sage.remove(PROP_ENABLED);
    Sage.remove(PROP_WAIT);
  }

  private static ScaleRequest req()
  {
    return new ScaleRequest(EnhancementTier.ENHANCE_2160P, 3840, 2160, 1280, 720,
        false, "scale_cuda", ScaleRequest.Purpose.LIVE);
  }

  private static final class RecordingWarm implements WarmContext
  {
    volatile boolean closed;
    final boolean valid;
    RecordingWarm(boolean valid) { this.valid = valid; }
    public long ttlMillis() { return 45_000L; }
    public boolean isValid() { return valid && !closed; }
    public void close() { closed = true; }
  }

  /** A provider whose warmup() hands back a supplied context and counts calls. */
  private static final class WarmingProvider implements ScaleProvider
  {
    final RecordingWarm ctx;
    final AtomicInteger warmups = new AtomicInteger();
    WarmingProvider(RecordingWarm ctx) { this.ctx = ctx; }
    public String id() { return "warming"; }
    public ScaleProviderCapabilities capabilities() { return null; }
    public ScaleProviderAvailability probe(ScaleRequest r) { return null; }
    public ScaleExecutionPlan plan(ScaleRequest r)
    { return new ScaleExecutionPlan(ExecutionForm.FFMPEG_FILTER, "x", "cold"); }
    public WarmContext warmup(ScaleRequest r) { warmups.incrementAndGet(); return ctx; }
  }

  @Test
  public void disabledIsInert()
  {
    // enabled unset -> default false
    RecordingWarm w = new RecordingWarm(true);
    WarmingProvider p = new WarmingProvider(w);
    cache.requestWarmup(p.id(), p, req());
    assertNull(cache.consume(p.id(), req()), "consume is null when disabled");
    assertTrue(p.warmups.get() == 0, "warmup() is never called when disabled");
  }

  @Test
  public void hitRoundTripsAndIsSingleOwner()
  {
    Sage.put(PROP_ENABLED, "true");
    RecordingWarm w = new RecordingWarm(true);
    WarmingProvider p = new WarmingProvider(w);
    cache.requestWarmup(p.id(), p, req());
    WarmContext got = cache.consume(p.id(), req());
    assertSame(got, w, "consume returns the warmed context");
    assertNull(cache.consume(p.id(), req()), "single-owner: a second consume misses");
  }

  @Test
  public void nullWarmupIsAMiss()
  {
    Sage.put(PROP_ENABLED, "true");
    WarmingProvider p = new WarmingProvider(null);
    cache.requestWarmup(p.id(), p, req());
    assertNull(cache.consume(p.id(), req()), "a provider that warms to null misses");
  }

  @Test
  public void invalidContextIsClosedAndNotHandedOut()
  {
    Sage.put(PROP_ENABLED, "true");
    RecordingWarm w = new RecordingWarm(false);   // reports invalid
    WarmingProvider p = new WarmingProvider(w);
    cache.requestWarmup(p.id(), p, req());
    assertNull(cache.consume(p.id(), req()), "an invalid context is not handed out");
    assertTrue(w.closed, "an invalid context is closed rather than leaked");
  }

  @Test
  public void geometryMismatchMisses()
  {
    Sage.put(PROP_ENABLED, "true");
    RecordingWarm w = new RecordingWarm(true);
    WarmingProvider p = new WarmingProvider(w);
    cache.requestWarmup(p.id(), p, req());
    ScaleRequest otherTier = new ScaleRequest(EnhancementTier.ENHANCE_1080P, 1920, 1080,
        1280, 720, false, "scale_cuda", ScaleRequest.Purpose.LIVE);
    assertNull(cache.consume(p.id(), otherTier), "a different tier/target does not match");
    // original still retrievable
    assertSame(cache.consume(p.id(), req()), w, "matching request still hits");
  }
}
