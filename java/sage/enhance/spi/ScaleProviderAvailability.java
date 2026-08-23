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
 * Immutable result of a provider availability probe.
 *
 * <p>A probe must be cheap and side-effect free: it answers "could you handle
 * this request right now?" without initializing an expensive model or session.
 * When a provider reports unavailable the registry falls back to the built-in
 * scaler, so an absent private runtime is never fatal to playback.
 */
public final class ScaleProviderAvailability
{
  private static final ScaleProviderAvailability AVAILABLE =
      new ScaleProviderAvailability(true, "available");

  private final boolean available;
  private final String detail;

  private ScaleProviderAvailability(boolean available, String detail)
  {
    this.available = available;
    this.detail = (detail == null) ? "" : detail;
  }

  public static ScaleProviderAvailability available() { return AVAILABLE; }

  public static ScaleProviderAvailability unavailable(String detail)
  {
    return new ScaleProviderAvailability(false, detail);
  }

  public boolean isAvailable() { return available; }
  public String getDetail() { return detail; }

  @Override
  public String toString()
  {
    return "ScaleProviderAvailability[" + (available ? "available" : "unavailable")
        + ": " + detail + "]";
  }
}
