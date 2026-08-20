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
 * An immutable sample of one GPU's load, as reported by {@code nvidia-smi}.
 *
 * <p>Every field may be {@link #UNKNOWN}. A snapshot that {@link #isKnown()
 * isn't known} must be treated as "no headroom information", which fails closed
 * to a conservative session-count cap — never to "allow everything".
 */
public final class GpuSnapshot
{
  public static final int UNKNOWN = -1;

  /** Snapshot used when {@code nvidia-smi} is missing, fails, or is disabled. */
  public static final GpuSnapshot UNAVAILABLE =
      new GpuSnapshot(UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, 0L);

  private final int index;
  private final int gpuUtilPct;
  private final int encoderUtilPct;
  private final int decoderUtilPct;
  private final long memUsedMB;
  private final long memTotalMB;
  private final long sampledAt;

  public GpuSnapshot(int index, int gpuUtilPct, int encoderUtilPct, int decoderUtilPct,
                     long memUsedMB, long memTotalMB, long sampledAt)
  {
    this.index = index;
    this.gpuUtilPct = gpuUtilPct;
    this.encoderUtilPct = encoderUtilPct;
    this.decoderUtilPct = decoderUtilPct;
    this.memUsedMB = memUsedMB;
    this.memTotalMB = memTotalMB;
    this.sampledAt = sampledAt;
  }

  public int getIndex() { return index; }
  public int getGpuUtilPct() { return gpuUtilPct; }
  public int getEncoderUtilPct() { return encoderUtilPct; }
  public int getDecoderUtilPct() { return decoderUtilPct; }
  public long getMemUsedMB() { return memUsedMB; }
  public long getMemTotalMB() { return memTotalMB; }
  public long getSampledAt() { return sampledAt; }

  /** True when this sample carries usable utilization data. */
  public boolean isKnown() { return sampledAt > 0 && gpuUtilPct >= 0; }

  /**
   * Currently free VRAM in MB, or {@link #UNKNOWN}.
   *
   * <p>Per Invariant 1 the governor budgets against this rather than against the
   * card's nameplate total, so another tenant's consumption automatically shrinks
   * SageTV's budget instead of causing an allocation failure mid-stream.
   */
  public long getFreeMemMB()
  {
    if (memTotalMB < 0 || memUsedMB < 0) return UNKNOWN;
    long free = memTotalMB - memUsedMB;
    return (free < 0) ? 0 : free;
  }

  /**
   * Combined video-engine pressure: the worst of the encoder and decoder
   * utilization figures, which is what actually gates another live session.
   * Falls back to overall GPU utilization when the per-engine counters are
   * unavailable (some driver/card combinations do not report them).
   */
  public int getVideoEnginePressurePct()
  {
    int worst = Math.max(encoderUtilPct, decoderUtilPct);
    if (worst < 0) return gpuUtilPct;
    return Math.max(worst, 0);
  }

  @Override
  public String toString()
  {
    if (!isKnown()) return "GpuSnapshot[unavailable]";
    return "GpuSnapshot[idx=" + index + " gpu=" + gpuUtilPct + "% enc=" + encoderUtilPct
        + "% dec=" + decoderUtilPct + "% mem=" + memUsedMB + "/" + memTotalMB + "MB free="
        + getFreeMemMB() + "MB]";
  }
}
