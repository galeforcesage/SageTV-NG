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
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

/**
 * The {@link WarmupBudget} contract the VSR plugin links against: the two default
 * constants, the {@code (acquire, ready)} constructor order, and a countdown that
 * starts at the ready allowance and never goes negative.
 */
public class WarmupBudgetTest
{
  @Test
  public void defaultConstantsMatchTheWarmupTunables()
  {
    assertEquals(WarmupBudget.DEFAULT_ACQUIRE_MILLIS, 8000L, "warmup_acquire_timeout_ms");
    assertEquals(WarmupBudget.DEFAULT_READY_MILLIS, 12000L, "warmup_ready_timeout_ms");
  }

  @Test
  public void constructorPreservesAcquireThenReadyOrder()
  {
    WarmupBudget b = new WarmupBudget(8000L, 12000L);
    assertEquals(b.acquireTimeoutMillis(), 8000L, "first arg is the acquire allowance");
    assertEquals(b.readyTimeoutMillis(), 12000L, "second arg is the ready allowance");
  }

  @Test
  public void remainingCountsDownAgainstReadyAndIsNeverNegative()
  {
    WarmupBudget b = new WarmupBudget(8000L, 12000L);
    long remaining = b.remainingMillis();
    assertTrue(remaining > 0L && remaining <= 12000L,
        "fresh budget has (0, ready] ms left, got " + remaining);
  }

  @Test
  public void negativeInputsAreClampedToZero()
  {
    WarmupBudget b = new WarmupBudget(-1L, -1L);
    assertEquals(b.readyTimeoutMillis(), 0L, "negative ready clamps to zero");
    assertEquals(b.remainingMillis(), 0L, "a zero budget is immediately spent");
    assertTrue(b.isExpired(), "zero budget is expired");
  }
}
