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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import sage.Sage;

/**
 * The core-side pre-warm stash for {@link ScaleProvider#warmup(ScaleRequest)}.
 *
 * <p>An expensive external provider is pre-warmed during the advisory phase
 * (seconds before play-start) and the resulting {@link WarmContext} is held here
 * until the pipeline is built, so the live session can wire an already-running
 * worker instead of paying a cold start on the play path. The whole mechanism is
 * gated by {@code playback/gpu_enhance/scale/warmup_enabled} (default off): when
 * disabled, {@link #requestWarmup} and {@link #consume} are no-ops and playback
 * is byte-identical to the no-warmup path.
 *
 * <p>Design (see {@code docs/ScalerProviderSPI.md} §5.1):
 * <ul>
 *   <li><b>Non-blocking.</b> {@link #requestWarmup} only submits the (possibly
 *       long) {@code warmup()} to a dedicated single-thread executor and returns;
 *       it never blocks the advisory thread or the offer.</li>
 *   <li><b>Single-owner.</b> {@link #consume} atomically removes the entry, so a
 *       context is handed to exactly one session; the reaper closes whatever is
 *       left unconsumed.</li>
 *   <li><b>Bounded.</b> A reaper closes contexts older than
 *       {@code min(ctx.ttlMillis(), warmup_max_ttl_seconds)} or that report
 *       {@link WarmContext#isValid()}==false, so a warm worker cannot leak.</li>
 *   <li><b>No GPU permit.</b> Warmup holds no {@link ScaleGovernor} permit; it is
 *       best-effort and must never block a recording or a real transcode. The
 *       permit is acquired at consume time in {@link ScaleProviderRegistry#select}
 *       exactly as on the cold path.</li>
 * </ul>
 */
public final class ScaleWarmupCache
{
  private static final String PROP_ENABLED =
      "playback/gpu_enhance/scale/warmup_enabled";
  private static final String PROP_MAX_TTL_SECONDS =
      "playback/gpu_enhance/scale/warmup_max_ttl_seconds";
  private static final String PROP_CONSUME_WAIT_MS =
      "playback/gpu_enhance/scale/warmup_consume_wait_ms";
  private static final String PROP_ACQUIRE_TIMEOUT_MS =
      "playback/gpu_enhance/scale/warmup_acquire_timeout_ms";
  private static final String PROP_READY_TIMEOUT_MS =
      "playback/gpu_enhance/scale/warmup_ready_timeout_ms";
  private static final long HARD_MAX_READY_MILLIS = 20_000L;

  private static final ScaleWarmupCache INSTANCE = new ScaleWarmupCache();

  public static ScaleWarmupCache getInstance() { return INSTANCE; }

  private final ConcurrentHashMap<String, Entry> stash =
      new ConcurrentHashMap<String, Entry>();
  private volatile java.util.concurrent.ExecutorService warmExec;
  private volatile ScheduledExecutorService reaper;

  private ScaleWarmupCache() {}

  /** Master switch. When false the whole mechanism is inert. */
  public boolean isEnabled() { return Sage.getBoolean(PROP_ENABLED, false); }

  WarmupBudget budget()
  {
    long ready = Sage.getLong(PROP_READY_TIMEOUT_MS, WarmupBudget.DEFAULT_READY_MILLIS);
    ready = Math.max(0L, Math.min(ready, HARD_MAX_READY_MILLIS));
    long acquire = Sage.getLong(PROP_ACQUIRE_TIMEOUT_MS, WarmupBudget.DEFAULT_ACQUIRE_MILLIS);
    acquire = Math.max(0L, Math.min(acquire, ready));
    return new WarmupBudget(acquire, ready);
  }

  private long capMillis()
  {
    return Math.max(1000L, Sage.getLong(PROP_MAX_TTL_SECONDS, 60L) * 1000L);
  }

  /** Stash key. Warm contexts are reusable only for an exact profile identity. */
  static String keyFor(String providerId, ScaleRequest req)
  {
    return providerId + "|" + req.getTier().token() + "|" + req.getProfileKey();
  }

  /**
   * Begin warming {@code provider} for {@code req} on a background thread, if
   * enabled and not already warming/warm for the same job. Returns immediately.
   */
  public void requestWarmup(String providerId, ScaleProvider provider, ScaleRequest req)
  {
    if (!isEnabled() || provider == null || req == null
        || providerId == null || providerId.isEmpty())
      return;
    final String key = keyFor(providerId, req);
    if (stash.containsKey(key)) return;   // already warming/warm for this exact job
    ensureStarted();

    final ScaleProvider p = provider;
    final ScaleRequest r = req;
    final WarmupBudget budget = budget();
    final CompletableFuture<WarmContext> cf = new CompletableFuture<WarmContext>();
    final Future<?> workerTask = warmExec.submit(
        new Runnable()
        {
          public void run()
          {
            WarmContext ctx = null;
            try { ctx = warmupWithBudget(p, r, budget); }
            catch (Throwable t)
            {
              if (Sage.DBG) System.out.println("SCALE_WARMUP warmup threw for "
                  + key + ": " + t);
            }
            if (budget.isExpired() || !cf.complete(ctx))
              closeQuietly(ctx);
          }
        });
    final ScheduledFuture<?> timeoutTask = reaper.schedule(new Runnable()
    {
      public void run()
      {
        if (cf.complete(null))
          workerTask.cancel(true);
      }
    }, budget.readyTimeoutMillis(), TimeUnit.MILLISECONDS);

    Entry e = new Entry(key, cf, workerTask, timeoutTask);
    Entry prev = stash.putIfAbsent(key, e);
    if (prev != null)
    {
      // Lost a race; make sure our orphan is closed when it resolves.
      e.cancelWork();
      closeWhenDone(cf);
      return;
    }
    if (Sage.DBG) System.out.println("SCALE_WARMUP requested " + key);
  }

  /**
   * Take the warm context for this provider+request if one is ready. Removes the
   * entry (single-owner). Returns {@code null} — the cold path — when disabled,
   * absent, still warming (a fast play beat the warmup), failed, or invalid.
   */
  public WarmContext consume(String providerId, ScaleRequest req)
  {
    if (!isEnabled() || providerId == null || req == null) return null;
    final String key = keyFor(providerId, req);
    Entry e = stash.remove(key);
    if (e == null) return null;

    long waitMs = Math.max(0L, Sage.getLong(PROP_CONSUME_WAIT_MS, 0L));
    WarmContext ctx = null;
    try
    {
      if (waitMs > 0)
        ctx = e.future.get(waitMs, TimeUnit.MILLISECONDS);
      else if (e.future.isDone())
        ctx = e.future.getNow(null);
    }
    catch (Throwable t)
    {
      if (Sage.DBG) System.out.println("SCALE_WARMUP consume: not ready for "
          + key + " (" + t + ") -> cold start");
      ctx = null;
    }

    if (ctx == null)
    {
      // Still warming or failed: cold-start now, and make sure a late-arriving
      // context is closed rather than leaked (we already own the removed entry).
      e.cancelWork();
      closeWhenDone(e.future);
      return null;
    }
    e.cancelTimeout();
    if (!isValidQuietly(ctx))
    {
      closeQuietly(ctx);
      return null;
    }
    if (Sage.DBG) System.out.println("SCALE_WARMUP consume: hit for " + key);
    return ctx;
  }

  private synchronized void ensureStarted()
  {
    if (warmExec != null) return;
    ThreadFactory tf = new ThreadFactory()
    {
      public Thread newThread(Runnable r)
      {
        Thread t = new Thread(r, "scale-warmup");
        t.setDaemon(true);
        return t;
      }
    };
    warmExec = Executors.newSingleThreadExecutor(tf);

    ThreadFactory rtf = new ThreadFactory()
    {
      public Thread newThread(Runnable r)
      {
        Thread t = new Thread(r, "scale-warmup-reaper");
        t.setDaemon(true);
        return t;
      }
    };
    reaper = Executors.newSingleThreadScheduledExecutor(rtf);
    reaper.scheduleWithFixedDelay(new Runnable()
    {
      public void run() { try { reap(); } catch (Throwable ignore) {} }
    }, 5L, 5L, TimeUnit.SECONDS);
  }

  private void reap()
  {
    long now = System.currentTimeMillis();
    long cap = capMillis();
    for (java.util.Iterator<Entry> it = stash.values().iterator(); it.hasNext();)
    {
      Entry e = it.next();
      long age = now - e.createdAt;
      if (!e.future.isDone())
      {
        if (age > cap)   // warmup hung well past any usable window
        {
          it.remove();
          e.cancelWork();
          closeWhenDone(e.future);
        }
        continue;
      }
      WarmContext c = e.future.getNow(null);
      if (c == null) { it.remove(); continue; }   // warmup returned null / failed
      e.cancelTimeout();
      long ttl = Math.min(Math.max(0L, c.ttlMillis()), cap);
      if (age > ttl || !isValidQuietly(c))
      {
        it.remove();
        closeQuietly(c);
      }
    }
  }

  private static boolean isValidQuietly(WarmContext c)
  {
    try { return c != null && c.isValid(); }
    catch (Throwable t) { return false; }
  }

  private static WarmContext warmupWithBudget(
      ScaleProvider provider, ScaleRequest request, WarmupBudget budget)
  {
    try
    {
      // Preserve compatibility with providers compiled against the original
      // one-argument warmup hook while preferring the budget-aware primitive.
      java.lang.reflect.Method m = provider.getClass().getMethod(
          "warmup", ScaleRequest.class, WarmupBudget.class);
      if (m.getDeclaringClass() != ScaleProvider.class)
        return provider.warmup(request, budget);
    }
    catch (NoSuchMethodException e)
    {
      // Fall through to the legacy hook.
    }
    return provider.warmup(request);
  }

  private static void closeQuietly(WarmContext c)
  {
    if (c == null) return;
    try { c.close(); } catch (Throwable ignore) {}
  }

  private static void closeWhenDone(CompletableFuture<WarmContext> cf)
  {
    if (cf == null) return;
    cf.whenComplete(new java.util.function.BiConsumer<WarmContext, Throwable>()
    {
      public void accept(WarmContext c, Throwable t) { closeQuietly(c); }
    });
  }

  private static final class Entry
  {
    final String key;
    final CompletableFuture<WarmContext> future;
    final Future<?> workerTask;
    final ScheduledFuture<?> timeoutTask;
    final long createdAt = System.currentTimeMillis();

    Entry(String key, CompletableFuture<WarmContext> future,
          Future<?> workerTask, ScheduledFuture<?> timeoutTask)
    {
      this.key = key;
      this.future = future;
      this.workerTask = workerTask;
      this.timeoutTask = timeoutTask;
    }

    void cancelTimeout()
    {
      if (timeoutTask != null) timeoutTask.cancel(false);
    }

    void cancelWork()
    {
      cancelTimeout();
      if (workerTask != null) workerTask.cancel(true);
      future.complete(null);
    }
  }

  // ---- Test support -------------------------------------------------------

  /** Close every stashed context and clear the stash. Test-only. */
  public void resetForTest()
  {
    for (java.util.Iterator<Entry> it = stash.values().iterator(); it.hasNext();)
    {
      Entry e = it.next();
      it.remove();
      e.cancelWork();
      closeWhenDone(e.future);
    }
  }
}
