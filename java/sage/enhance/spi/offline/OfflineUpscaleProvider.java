/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package sage.enhance.spi.offline;

import java.util.List;

/**
 * A pluggable <b>offline / batch</b> upscaler backend for the {@code Ministry}
 * chained-job path.
 *
 * <p>This is the sibling of the live {@code sage.enhance.spi.ScaleProvider}, for
 * the other place SageTV-NG upscales: the offline pass that shells out to an
 * external worker to produce an upscaled lossless intermediate before the normal
 * transcode. The stock plugin API is control-plane only and cannot inject a
 * native upscaler here either; this seam lets a separately installed plugin
 * supply the worker without any of its (possibly EULA'd) code entering the
 * public repo.
 *
 * <p>A provider's whole job is to describe two commands as argv lists: an
 * optional device <i>probe</i>, and the actual <i>upscale</i> invocation for one
 * request. The core ({@code Ministry}) owns everything else — genre routing, the
 * one-per-JVM probe cache and retry backoff, intermediate-file management, the
 * phase-2 scale-filter strip, and recording protection (offline concurrency is
 * already dropped to 1 while a tuner records). A provider cannot reach any of
 * those.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link #buildProbeCommand} returns an argv the core runs (with a drain +
 *       timeout harness) to decide device availability, or {@code null}/empty to
 *       declare "no device probe needed" (treated as available). It must be
 *       cheap and side-effect free beyond the probe subprocess itself.</li>
 *   <li>{@link #buildUpscaleCommand} returns the argv for exactly one job and
 *       must not mutate shared state.</li>
 *   <li>Any exception thrown from either method is treated as a provider failure
 *       and the core falls back to the built-in provider's command.</li>
 * </ul>
 */
public interface OfflineUpscaleProvider
{
  /** Stable, unique identifier, e.g. {@code realesrgan-ncnn}. */
  String id();

  /** Whether this backend consumes a scarce inference resource. Informational
   *  today (offline concurrency is governed by the recording guard, not a
   *  separate budget), but declared so tooling can reason about it. */
  boolean isSpecialized();

  /**
   * The argv for a cheap device-availability probe, or {@code null}/empty when
   * this backend needs no probe. The core runs it with output draining and a
   * timeout, caches a success for the life of the JVM, and re-probes a failure
   * after a backoff. Exit code 0 means available.
   */
  List<String> buildProbeCommand();

  /** The argv that upscales {@code request.getInput()} into
   *  {@code request.getOutput()} at the requested target size. */
  List<String> buildUpscaleCommand(OfflineUpscaleRequest request);
}
