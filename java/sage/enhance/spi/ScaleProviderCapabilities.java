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
 * Immutable, static description of what a {@link ScaleProvider} is and costs.
 *
 * <p>{@link #isSpecialized()} is the load-bearing flag: a specialized provider
 * consumes a scarce resource (for example AI-inference capacity) that is
 * distinct from ordinary NVENC session capacity, so the registry admits it
 * through the separate {@link ScaleGovernor} budget. The built-in scaler is
 * <b>not</b> specialized and therefore never takes a permit — which is what
 * keeps the current path byte-for-byte unchanged.
 */
public final class ScaleProviderCapabilities
{
  private final String providerId;
  private final boolean specialized;
  private final boolean supportsUpscale;
  private final int nominalMaxConcurrent;

  public ScaleProviderCapabilities(String providerId, boolean specialized,
                                   boolean supportsUpscale, int nominalMaxConcurrent)
  {
    this.providerId = providerId;
    this.specialized = specialized;
    this.supportsUpscale = supportsUpscale;
    this.nominalMaxConcurrent = nominalMaxConcurrent;
  }

  public String getProviderId() { return providerId; }

  /** True when admission must go through the specialized {@link ScaleGovernor}. */
  public boolean isSpecialized() { return specialized; }

  public boolean supportsUpscale() { return supportsUpscale; }

  /** A provider-declared soft ceiling, informational in Phase 0. */
  public int getNominalMaxConcurrent() { return nominalMaxConcurrent; }

  @Override
  public String toString()
  {
    return "ScaleProviderCapabilities[" + providerId
        + (specialized ? " specialized" : " builtin")
        + " upscale=" + supportsUpscale
        + " max=" + nominalMaxConcurrent + "]";
  }
}
