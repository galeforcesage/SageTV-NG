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
 * A time budget handed to a {@link ScaleProvider} for a single pre-warm attempt.
 *
 * <p>Warming an external-process provider (model load, GPU context init, a broker
 * admission round-trip) must be bounded: the advisory phase pre-warms seconds
 * before play-start, so a provider that cannot get ready in time should give up
 * and let the core cold-start or fall back rather than stall playback. This
 * object carries that deadline so the provider does not have to invent its own.
 *
 * <p>Two phases are expressed as two durations, both measured from the moment the
 * budget is constructed:
 * <ul>
 *   <li><b>acquire</b> &mdash; the soft allowance for obtaining whatever admission
 *       or resource the warm needs (e.g. a broker grant / GPU lease).</li>
 *   <li><b>ready</b> &mdash; the hard overall allowance by which the warm resource
 *       must be fully ready. This is the deadline {@link #remainingMillis()}
 *       counts down against; it should be &ge; the acquire allowance.</li>
 * </ul>
 *
 * <p>{@link #remainingMillis()} returns the wall-clock time left before the ready
 * deadline, measured on a monotonic clock so it is immune to system-clock steps.
 * A provider typically wraps it in a {@link java.time.Duration} and passes it to
 * its warm routine; when it reaches zero the provider must abandon the attempt.
 *
 * <p>Instances are immutable apart from the ticking clock and are cheap to create;
 * a fresh budget is constructed per warm attempt. Thread-safe.
 */
public final class WarmupBudget
{
  /**
   * Default soft acquire allowance in milliseconds, used when a caller does not
   * supply its own. Mirrors the {@code warmup_acquire_timeout_ms} tunable.
   */
  public static final long DEFAULT_ACQUIRE_MILLIS = 8000L;

  /**
   * Default hard ready allowance in milliseconds, used when a caller does not
   * supply its own. Mirrors the {@code warmup_ready_timeout_ms} tunable. This is
   * the deadline {@link #remainingMillis()} counts down against.
   */
  public static final long DEFAULT_READY_MILLIS = 12000L;

  private final long acquireTimeoutMillis;
  private final long readyTimeoutMillis;
  private final long startNanos;

  /**
   * Starts a budget whose countdown begins immediately.
   *
   * @param acquireTimeoutMillis soft allowance, in ms, for acquiring admission or
   *                             resources; clamped to {@code >= 0}
   * @param readyTimeoutMillis   hard overall allowance, in ms, by which the warm
   *                             resource must be ready; this is the deadline
   *                             {@link #remainingMillis()} counts down against,
   *                             clamped to {@code >= 0}
   */
  public WarmupBudget(long acquireTimeoutMillis, long readyTimeoutMillis)
  {
    this.acquireTimeoutMillis = Math.max(0L, acquireTimeoutMillis);
    this.readyTimeoutMillis = Math.max(0L, readyTimeoutMillis);
    this.startNanos = System.nanoTime();
  }

  /**
   * The soft acquire allowance in milliseconds, as supplied at construction.
   *
   * @return acquire allowance in ms ({@code >= 0})
   */
  public long acquireTimeoutMillis()
  {
    return acquireTimeoutMillis;
  }

  /**
   * The hard ready allowance in milliseconds, as supplied at construction. This is
   * the full width of the window {@link #remainingMillis()} counts down from.
   *
   * @return ready allowance in ms ({@code >= 0})
   */
  public long readyTimeoutMillis()
  {
    return readyTimeoutMillis;
  }

  /**
   * Time remaining, in milliseconds, before the ready deadline elapses.
   *
   * <p>Computed on a monotonic clock as {@code readyTimeoutMillis - elapsed}, never
   * negative. Immediately after construction this is approximately
   * {@link #readyTimeoutMillis()}; it decreases as the warm proceeds and reaches
   * {@code 0} once the deadline passes, at which point the provider must stop.
   *
   * @return non-negative milliseconds left in the budget
   */
  public long remainingMillis()
  {
    long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
    long remaining = readyTimeoutMillis - elapsedMillis;
    return (remaining > 0L) ? remaining : 0L;
  }

  /**
   * Returns {@code true} once {@link #remainingMillis()} has reached zero.
   *
   * @return true if the ready deadline has passed
   */
  public boolean isExpired()
  {
    return remainingMillis() <= 0L;
  }

  @Override
  public String toString()
  {
    return "WarmupBudget[acquire=" + acquireTimeoutMillis + "ms, ready="
        + readyTimeoutMillis + "ms, remaining=" + remainingMillis() + "ms]";
  }
}
