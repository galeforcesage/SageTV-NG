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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import sage.Sage;

/**
 * Admission budget for <b>specialized</b> scale providers only.
 *
 * <p>This is a separate resource from ordinary NVENC/general GPU admission
 * (which {@code sage.enhance.GpuGovernor} already governs) and from recording
 * protection ({@code sage.enhance.RecordingGuard}). Specialized scaling — for
 * example AI super-resolution — is inference-bound, so it gets its own small,
 * independently configurable ceiling. The built-in scaler is not specialized and
 * never acquires a permit here, which is why the current path is unaffected.
 *
 * <p>Two hard rules:
 * <ul>
 *   <li>A permit is a {@link Lease} captured by the playback session and released
 *       <b>exactly once</b> — {@link Lease#close()} is idempotent.</li>
 *   <li>This governor may deny, but it never weakens recording protection or the
 *       general GPU admission that the caller checks first.</li>
 * </ul>
 */
public final class ScaleGovernor
{
  /** Default specialized-session ceiling. A conservative project default, not a
   *  vendor-documented limit; override with the property below. */
  public static final int DEFAULT_MAX_SPECIALIZED = 1;

  private static final String PROP_MAX_SPECIALIZED =
      "playback/gpu_enhance/scale/max_specialized_sessions";

  private static final ScaleGovernor INSTANCE = new ScaleGovernor();

  private final AtomicInteger active = new AtomicInteger(0);

  private ScaleGovernor() { }

  public static ScaleGovernor getInstance() { return INSTANCE; }

  private static int configuredMax()
  {
    int m = Sage.getInt(PROP_MAX_SPECIALIZED, DEFAULT_MAX_SPECIALIZED);
    return (m < 0) ? 0 : m;
  }

  /**
   * Try to take one specialized permit. Returns a live {@link Lease} on success,
   * or {@code null} when the budget is exhausted (the caller must then fall back
   * to the built-in scaler). Probes never retain a permit: a {@code PROBE}
   * request is granted a no-op lease that holds no capacity.
   */
  public Lease acquire(String providerId, ScaleRequest request)
  {
    if (request != null && request.isProbe())
      return Lease.noop(providerId);

    int max = configuredMax();
    while (true)
    {
      int cur = active.get();
      if (cur >= max) return null;
      if (active.compareAndSet(cur, cur + 1))
        return new Lease(this, providerId);
    }
  }

  /** Number of specialized permits currently held. Test/diagnostic visibility. */
  public int activeCount() { return active.get(); }

  /** Test hook: drop all counted permits. Never call from production paths. */
  public void resetForTest() { active.set(0); }

  private void releaseOne() { active.updateAndGet(n -> n > 0 ? n - 1 : 0); }

  /**
   * A once-only specialized permit. {@link #close()} is idempotent, so releasing
   * it from multiple lifecycle unwinds (startup failure, transcoder exit, client
   * disconnect) never double-frees the budget.
   */
  public static final class Lease implements AutoCloseable
  {
    private final ScaleGovernor owner;   // null for a no-op probe lease
    private final String providerId;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private Lease(ScaleGovernor owner, String providerId)
    {
      this.owner = owner;
      this.providerId = providerId;
    }

    static Lease noop(String providerId) { return new Lease(null, providerId); }

    public String getProviderId() { return providerId; }

    /** True when this lease holds no counted capacity (probe lease). */
    public boolean isNoop() { return owner == null; }

    @Override
    public void close()
    {
      if (closed.compareAndSet(false, true) && owner != null)
        owner.releaseOne();
    }
  }
}
