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

import sage.enhance.EnhancementTier;

/**
 * The immutable, provider-facing description of one scaling job.
 *
 * <p>It carries <b>only</b> what a provider needs to assess availability and
 * produce a scale stage: the granted tier, the target frame size, the source
 * height and scan type, and a hint of the built-in CUDA scaler this ffmpeg has.
 * It deliberately carries no bitrate, no client identity, and no admission
 * state, so a provider can never reach outside its contract and influence those
 * decisions.
 */
public final class ScaleRequest
{
  /** Why the request is being planned. Live requests may take a specialized
   *  permit; probes must never retain one. */
  public enum Purpose { LIVE, PROBE }

  private final EnhancementTier tier;
  private final int targetWidth;
  private final int targetHeight;
  private final int sourceHeight;
  private final boolean sourceInterlaced;
  private final String builtinScalerHint;
  private final Purpose purpose;

  public ScaleRequest(EnhancementTier tier, int targetWidth, int targetHeight,
                      int sourceHeight, boolean sourceInterlaced,
                      String builtinScalerHint, Purpose purpose)
  {
    this.tier = (tier == null) ? EnhancementTier.NONE : tier;
    this.targetWidth = targetWidth;
    this.targetHeight = targetHeight;
    this.sourceHeight = sourceHeight;
    this.sourceInterlaced = sourceInterlaced;
    this.builtinScalerHint = builtinScalerHint;
    this.purpose = (purpose == null) ? Purpose.LIVE : purpose;
  }

  public EnhancementTier getTier() { return tier; }
  public int getTargetWidth() { return targetWidth; }
  public int getTargetHeight() { return targetHeight; }
  public int getSourceHeight() { return sourceHeight; }
  public boolean isSourceInterlaced() { return sourceInterlaced; }

  /** The concrete CUDA scaler filter name this ffmpeg build offers
   *  ({@code scale_npp} or {@code scale_cuda}), or null if none. Providers may
   *  use it as a fallback hint; specialized providers are free to ignore it. */
  public String getBuiltinScalerHint() { return builtinScalerHint; }

  public Purpose getPurpose() { return purpose; }
  public boolean isProbe() { return purpose == Purpose.PROBE; }

  /** True when this request actually changes the frame size. */
  public boolean isUpscaling() { return tier != null && tier.isUpscaling(); }

  @Override
  public String toString()
  {
    return "ScaleRequest[" + tier.token() + " " + targetWidth + "x" + targetHeight
        + " src=" + sourceHeight + (sourceInterlaced ? "i" : "p")
        + " hint=" + builtinScalerHint + " " + purpose + "]";
  }
}
