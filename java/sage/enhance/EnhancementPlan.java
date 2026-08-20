/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package sage.enhance;

/**
 * The immutable unit of work for one enhanced session: what treatment was
 * granted, at what size, within what bitrate envelope, and why.
 *
 * <p>Enhancement targets are carried <b>here</b> rather than by relaxing
 * {@code FFMPEGTranscoder}'s existing {@code if (th > sh) th = sh; // never
 * upscale beyond source} guard. That guard protects the scale-<i>down</i>
 * behavior of {@code LiveTranscodeProfile.scaleWidth/Height}, which is a
 * different feature with different callers; loosening it would let every
 * unrelated transcode path start upscaling by accident. Keeping the targets on
 * the plan means the enhanced path opts in explicitly and nothing else changes.
 *
 * <p>{@link #reason} is always populated, including on success, because the
 * telemetry record is only useful if it explains grants as well as denials.
 */
public final class EnhancementPlan
{
  /** Shared no-op plan. Reference-comparable, and safe to hand anywhere. */
  public static final EnhancementPlan NONE =
      new EnhancementPlan(EnhancementTier.NONE, false, null, null, 0, 0, 0, 0, "not enhanced");

  private final EnhancementTier tier;
  private final boolean deinterlace;
  private final String deinterlacer;
  private final String scaler;
  private final int targetWidth;
  private final int targetHeight;
  private final long bitrateKbps;
  private final long bitrateCapKbps;
  private final String reason;

  public EnhancementPlan(EnhancementTier tier, boolean deinterlace, String deinterlacer,
                         String scaler, int targetWidth, int targetHeight,
                         long bitrateKbps, long bitrateCapKbps, String reason)
  {
    this.tier = (tier == null) ? EnhancementTier.NONE : tier;
    this.deinterlace = deinterlace;
    this.deinterlacer = deinterlacer;
    this.scaler = scaler;
    this.targetWidth = targetWidth;
    this.targetHeight = targetHeight;
    this.bitrateKbps = bitrateKbps;
    this.bitrateCapKbps = bitrateCapKbps;
    this.reason = (reason == null) ? "" : reason;
  }

  public EnhancementTier getTier() { return tier; }
  public boolean isDeinterlace() { return deinterlace; }
  /** ffmpeg filter name, e.g. {@code yadif_cuda}. Null when not deinterlacing. */
  public String getDeinterlacer() { return deinterlacer; }
  /** ffmpeg filter name, e.g. {@code scale_npp}. Null when not scaling. */
  public String getScaler() { return scaler; }
  public int getTargetWidth() { return targetWidth; }
  public int getTargetHeight() { return targetHeight; }
  public long getBitrateKbps() { return bitrateKbps; }
  public long getBitrateCapKbps() { return bitrateCapKbps; }
  public String getReason() { return reason; }

  /** True when this plan actually asks for GPU work. */
  public boolean isActive() { return tier.isActive(); }

  /** True when this plan changes the frame size. */
  public boolean isScaling() { return targetHeight > 0 && scaler != null; }

  /** Copy of this plan with a new bitrate, for mid-stream rate adaptation. */
  public EnhancementPlan withBitrate(long kbps)
  {
    return new EnhancementPlan(tier, deinterlace, deinterlacer, scaler, targetWidth,
        targetHeight, kbps, bitrateCapKbps, reason);
  }

  @Override
  public String toString()
  {
    if (!isActive()) return "EnhancementPlan[none: " + reason + "]";
    StringBuilder sb = new StringBuilder("EnhancementPlan[").append(tier.token());
    if (deinterlace) sb.append(" deint=").append(deinterlacer);
    if (isScaling()) sb.append(' ').append(scaler).append('=')
        .append(targetWidth).append('x').append(targetHeight);
    sb.append(" ").append(bitrateKbps).append("kbps");
    if (bitrateCapKbps > 0) sb.append(" cap=").append(bitrateCapKbps);
    return sb.append(" (").append(reason).append(")]").toString();
  }
}
