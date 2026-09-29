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

/**
 * Provider-neutral timing budget for preparing a warm resource.
 *
 * <p>The complete acquire-and-ready operation may not exceed the total budget.
 * Providers may use less time, but must not extend either phase past its budget.
 */
public final class WarmupBudget
{
  public static final long DEFAULT_ACQUIRE_MILLIS = 8000L;
  public static final long DEFAULT_READY_MILLIS = 12000L;
  public static final long HARD_MAX_TOTAL_MILLIS = 20000L;

  private final long startedAtMillis;
  private final long acquireMillis;
  private final long readyMillis;

  public WarmupBudget(long acquireMillis, long readyMillis)
  {
    this.startedAtMillis = System.currentTimeMillis();
    long acquire = Math.max(0L, Math.min(acquireMillis, HARD_MAX_TOTAL_MILLIS));
    long ready = Math.max(0L,
        Math.min(readyMillis, HARD_MAX_TOTAL_MILLIS - acquire));
    this.acquireMillis = acquire;
    this.readyMillis = ready;
  }

  public long getAcquireMillis() { return acquireMillis; }
  public long getReadyMillis() { return readyMillis; }
  public long getTotalMillis() { return acquireMillis + readyMillis; }
  public long getDeadlineMillis() { return startedAtMillis + getTotalMillis(); }
  public long remainingMillis()
  {
    return Math.max(0L, getDeadlineMillis() - System.currentTimeMillis());
  }
}
