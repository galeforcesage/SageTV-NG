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
 * Opaque handle to a pre-warmed provider resource.
 *
 * <p>An external-process {@link ScaleProvider} may need significant startup time
 * (model load, shader compile, GPU context init) before it can process frames.
 * Paid at play-start, that cost is a multi-second black screen. The advisory
 * phase ({@code EnhancementAdvisor}) evaluates providers seconds before playback
 * begins, which is a natural window to pre-warm one. The core holds this opaque
 * handle between the advisory phase and pipeline build, then passes it back to
 * {@link ScaleProvider#plan(ScaleRequest, WarmContext)}. The core never inspects
 * it; implementations are entirely provider-specific (a running worker with a
 * loaded model, a compiled shader pipeline, an inference-server connection, a
 * pre-allocated GPU buffer pool, ...).
 *
 * <p>Lifecycle contract:
 * <ul>
 *   <li><b>Optional.</b> Providers that start quickly return {@code null} from
 *       {@link ScaleProvider#warmup(ScaleRequest)}; the core cold-starts as
 *       before.</li>
 *   <li><b>Bounded.</b> If the context is not consumed within {@link #ttlMillis()}
 *       the core calls {@link #close()} and discards it.</li>
 *   <li><b>Single-owner.</b> A context is consumed at most once (by one playback
 *       session) or expired. It is never shared across concurrent sessions.</li>
 *   <li><b>Safe cancellation.</b> The core may {@link #close()} at any time (user
 *       navigated away, a different provider was selected). {@code close()} must
 *       release resources synchronously and be idempotent.</li>
 * </ul>
 *
 * <p>Thread safety: a single instance is touched by one core thread at a time
 * (the warmup thread, then the pipeline-build thread). Implementations need not
 * be thread-safe.
 *
 * <p>Extends {@link AutoCloseable} so the core can release resources with a
 * try-with-resources / reaper without a provider-specific call. {@link #close()}
 * is declared to throw nothing so callers need no checked-exception handling.
 */
public interface WarmContext extends AutoCloseable
{
  /**
   * Time-to-live in milliseconds. If the context is not consumed by
   * {@link ScaleProvider#plan(ScaleRequest, WarmContext)} within this duration,
   * the core calls {@link #close()} and discards it.
   *
   * <p>Guideline: the maximum reasonable delay between the advisory offer and
   * play-start for the use case. 30&ndash;60 seconds covers most UI navigation.
   *
   * @return TTL in ms; must be positive. A non-positive value is treated by the
   *         core as "already expired" and the context is closed immediately.
   */
  long ttlMillis();

  /**
   * Returns {@code true} if the warm resource is still usable. The core checks
   * this before passing the context to {@code plan()}. A context may become
   * invalid if the underlying process crashed, a connection dropped, or hardware
   * was reclaimed.
   *
   * @return true if the warm resource is still ready
   */
  boolean isValid();

  /**
   * Release all resources (kill process, free GPU memory, close connection).
   * Called by the core when the context expires unused or is cancelled. Must be
   * idempotent &mdash; safe to call multiple times, or after {@code plan()} has
   * already consumed the context.
   */
  @Override
  void close();
}
