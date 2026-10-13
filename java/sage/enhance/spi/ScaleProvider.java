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
 * A pluggable scaler backend.
 *
 * <p>This is the public, vendor-neutral data-plane <i>selection</i> seam that the
 * stock plugin API cannot provide on its own: the core exposes the seam, a
 * provider (built-in, or one registered at runtime by a separately installed
 * plugin) fills it. A provider describes itself, answers a cheap availability
 * probe, and produces an immutable {@link ScaleExecutionPlan} for the scale
 * stage. It has no authority over deinterlacing, bitrate, recording protection,
 * or client admission — those stay with the core.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link #probe} must be cheap and side-effect free, and must not
 *       initialize an expensive runtime or model.</li>
 *   <li>{@link #plan} returns the scale stage for exactly one request; it must
 *       not mutate shared state.</li>
 *   <li>Any exception thrown from {@code probe} or {@code plan} is treated as
 *       "unavailable" and the core falls back to the built-in scaler.</li>
 * </ul>
 */
public interface ScaleProvider
{
  /** Stable, unique identifier, e.g. {@code builtin-passthrough}. */
  String id();

  /** Static self-description, including whether admission is specialized. */
  ScaleProviderCapabilities capabilities();

  /** Cheap, side-effect-free check that this request can be handled now. */
  ScaleProviderAvailability probe(ScaleRequest request);

  /**
   * Typed profile-specific availability and capacity assessment.
   * Existing providers remain compatible through the unmeasured probe adapter.
   */
  default ScaleProviderAssessment assess(ScaleRequest request)
  {
    return ScaleProviderAssessment.fromAvailability(request, probe(request));
  }

  /** Produce the immutable scale stage for this request. */
  ScaleExecutionPlan plan(ScaleRequest request);

  /**
   * Pre-warm this provider for an anticipated playback session.
   *
   * <p>Called by the core during the advisory phase, before play-start, on a
   * background thread. A provider with an expensive cold start (model load,
   * shader compile, process spawn) should begin that work here and return a
   * {@link WarmContext} the core holds and passes back to
   * {@link #plan(ScaleRequest, WarmContext)} at pipeline-build time. This method
   * may block for the duration of the warmup; it should return only once the
   * provider is ready to process frames, or throw / return {@code null} if
   * warmup fails.
   *
   * <p>Unlike {@link #probe}, this method is expressly permitted to acquire
   * runtime resources (spawn the worker, load the model). Anything it throws is
   * treated by the core as "warmup failed" &mdash; the context is discarded and
   * the session cold-starts through {@link #plan(ScaleRequest)} as usual.
   *
   * <p>Default: supplies a default {@link WarmupBudget} ({@link
   * WarmupBudget#DEFAULT_ACQUIRE_MILLIS} / {@link WarmupBudget#DEFAULT_READY_MILLIS})
   * and delegates to {@link #warmup(ScaleRequest, WarmupBudget)}, which is the
   * primitive a warmup-capable provider overrides. Providers that start quickly
   * (&lt; 500&nbsp;ms), and every filter-form provider, should not override either.
   *
   * @param request the anticipated scale request. The actual request at play
   *        time may differ slightly (e.g. aspect-ratio correction), but the
   *        dimensions will be the same or very close.
   * @return a warm context, or {@code null} if warmup is not needed or not
   *         possible for this request
   */
  default WarmContext warmup(ScaleRequest request)
  {
    return warmup(request,
        new WarmupBudget(WarmupBudget.DEFAULT_ACQUIRE_MILLIS,
            WarmupBudget.DEFAULT_READY_MILLIS));
  }

  /**
   * Pre-warm this provider under an explicit time {@link WarmupBudget}.
   *
   * <p>This is the warmup <b>primitive</b>: {@link #warmup(ScaleRequest)} calls it
   * with a default budget, so a warmup-capable provider need only override this
   * one. The core supplies the deadline it is prepared to wait rather than letting
   * the provider pick its own. A provider whose warmup must bound an admission
   * round-trip (acquire a GPU lease / broker grant, then bring a worker to ready)
   * should honour {@link WarmupBudget#remainingMillis()} and abandon the attempt
   * when it hits zero.
   *
   * <p>Default: returns {@code null} (no warmup).
   *
   * @param request the anticipated scale request
   * @param budget  the time budget for this warm attempt; never {@code null} when
   *        the core calls this
   * @return a warm context, or {@code null} if warmup is not needed or not
   *         possible within the budget
   */
  default WarmContext warmup(ScaleRequest request, WarmupBudget budget)
  {
    return null;
  }

  /**
   * Whether this provider owns its own warmup-time admission.
   *
   * <p>A provider that returns {@code true} acquires whatever cross-tenant
   * admission it needs (e.g. a GPU lease / broker grant) itself, inside
   * {@link #warmup(ScaleRequest, WarmupBudget)}, and will only produce a
   * renderable plan once that admission is held. The core must not additionally
   * gate such a provider through its local enhancement admission &mdash; doing so
   * would double-count the resource. A provider that returns {@code false} (the
   * default) is admitted by the core's ordinary governor path.
   *
   * @return {@code true} if the provider self-admits during warmup; default
   *         {@code false}
   */
  default boolean managesWarmupAdmission()
  {
    return false;
  }

  /**
   * Build an execution plan using a pre-warmed context, produced by a prior
   * {@link #warmup(ScaleRequest)} call and still valid.
   *
   * <p>The provider should incorporate the warm resource into its plan. For an
   * {@link ExecutionForm#EXTERNAL_PROCESS} provider this typically means
   * returning a plan whose worker handle
   * ({@link ScaleExecutionPlan#getWarmProcess()}) points at the already-running
   * worker so the core skips the spawn. If the provider cannot use the warm
   * context (dimensions changed, context invalid), it should {@code warm.close()},
   * fall back to {@link #plan(ScaleRequest)}, and let the core cold-start.
   *
   * <p>Default: closes {@code warm} and delegates to the cold
   * {@link #plan(ScaleRequest)}, so a provider that overrides only
   * {@link #warmup} still behaves correctly.
   *
   * @param request the actual scale request (may differ slightly from the one
   *        passed to {@link #warmup})
   * @param warm    the pre-warmed context; never {@code null} when the core calls
   *        this. Ownership transfers to the provider: it must be closed by the
   *        provider (on the fallback path) or consumed into the returned plan.
   * @return the execution plan
   */
  default ScaleExecutionPlan plan(ScaleRequest request, WarmContext warm)
  {
    if (warm != null) { try { warm.close(); } catch (Throwable ignore) {} }
    return plan(request);
  }
}
