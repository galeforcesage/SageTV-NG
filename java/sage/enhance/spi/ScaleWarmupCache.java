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
import java.util.concurrent.ScheduledExecutorService;
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

  private static final ScaleWarmupCache INSTANCE = new ScaleWarmupCache();

  public static ScaleWarmupCache getInstance() { return INSTANCE; }

  private final ConcurrentHashMap<String, Entry> stash =
      new ConcurrentHashMap<String, Entry>();
  private volatile java.util.concurrent.ExecutorService warmExec;
  private volatile ScheduledExecutorService reaper;

  private ScaleWarmupCache() {}

  /** Master switch. When false the whole mechanism is inert. */
  public boolean isEnabled() { return Sage.getBoolean(PROP_ENABLED, false); }

  private long capMillis()
  {
    return Math.max(1000L, Sage.getLong(PROP_MAX_TTL_SECONDS, 60L) * 1000L);
  }

  /**
   * Stash key. Deliberately keyed on the OUTPUT geometry + tier + source height
   * (both the advisory offer and the live plan know these), not source width, so
   * a small source-width discrepancy does not miss the warm worker; the provider
   * re-verifies exact dimensions in {@link ScaleProvider#plan(ScaleRequest,
   * WarmContext)} and cold-falls-back on any true mismatch.
   */
  static String keyFor(String providerId, ScaleRequest req)
  {
    return providerId + "|" + req.getTier().token() + "|"
        + req.getTargetWidth() + "x" + req.getTargetHeight()
        + "|src=" + req.getSourceHeight();
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
    CompletableFuture<WarmContext> cf = CompletableFuture.supplyAsync(
        new java.util.function.Supplier<WarmContext>()
        {
          public WarmContext get()
          {
            try { return p.warmup(r); }
            catch (Throwable t)
            {
              if (Sage.DBG) System.out.println("SCALE_WARMUP warmup threw for "
                  + key + ": " + t);
              return null;
            }
          }
        }, warmExec);

    Entry e = new Entry(key, cf);
    Entry prev = stash.putIfAbsent(key, e);
    if (prev != null)
    {
      // Lost a race; make sure our orphan is closed when it resolves.
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
      closeWhenDone(e.future);
      return null;
    }
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
          e.future.cancel(true);
          closeWhenDone(e.future);
        }
        continue;
      }
      WarmContext c = e.future.getNow(null);
      if (c == null) { it.remove(); continue; }   // warmup returned null / failed
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
    final long createdAt = System.currentTimeMillis();

    Entry(String key, CompletableFuture<WarmContext> future)
    {
      this.key = key;
      this.future = future;
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
      e.future.cancel(true);
      closeWhenDone(e.future);
    }
  }
}
