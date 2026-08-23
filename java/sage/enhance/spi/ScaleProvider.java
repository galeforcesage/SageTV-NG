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
  /** Stable, unique identifier, e.g. {@code builtin-lanczos}. */
  String id();

  /** Static self-description, including whether admission is specialized. */
  ScaleProviderCapabilities capabilities();

  /** Cheap, side-effect-free check that this request can be handled now. */
  ScaleProviderAvailability probe(ScaleRequest request);

  /** Produce the immutable scale stage for this request. */
  ScaleExecutionPlan plan(ScaleRequest request);
}
