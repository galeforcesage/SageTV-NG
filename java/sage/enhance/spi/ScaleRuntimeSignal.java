/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package sage.enhance.spi;

/** Typed health/control signal emitted by an indexed external scaler. */
public final class ScaleRuntimeSignal
{
  public enum Kind { HEALTHY, DEGRADE, FATAL }

  private final Kind kind;
  private final String reason;
  private final long lastCompletedFrameIndex;
  private final long lastCompletedPts;

  private ScaleRuntimeSignal(Kind kind, String reason, long frameIndex, long pts)
  {
    this.kind = kind;
    this.reason = reason == null ? "" : reason;
    this.lastCompletedFrameIndex = frameIndex;
    this.lastCompletedPts = pts;
  }

  public static ScaleRuntimeSignal healthy()
  { return new ScaleRuntimeSignal(Kind.HEALTHY, "", -1L, Long.MIN_VALUE); }

  public static ScaleRuntimeSignal degrade(String reason, long frameIndex, long pts)
  { return new ScaleRuntimeSignal(Kind.DEGRADE, reason, frameIndex, pts); }

  public static ScaleRuntimeSignal fatal(String reason)
  { return new ScaleRuntimeSignal(Kind.FATAL, reason, -1L, Long.MIN_VALUE); }

  public Kind getKind() { return kind; }
  public String getReason() { return reason; }
  public long getLastCompletedFrameIndex() { return lastCompletedFrameIndex; }
  public long getLastCompletedPts() { return lastCompletedPts; }

  /**
   * Parse the versioned line protocol:
   * {@code SCALE_HEALTH|1|HEALTHY},
   * {@code SCALE_HEALTH|1|DEGRADE|frameIndex|pts|reason}, or
   * {@code SCALE_HEALTH|1|FATAL|reason}.
   */
  public static ScaleRuntimeSignal parse(String line)
  {
    if (line == null) return null;
    String[] parts = line.trim().split("\\|", 7);
    if (parts.length < 3 || !"SCALE_HEALTH".equals(parts[0]) || !"1".equals(parts[1]))
      return null;
    if ("HEALTHY".equals(parts[2]) && parts.length == 3) return healthy();
    if ("FATAL".equals(parts[2]) && parts.length >= 4) return fatal(parts[3]);
    if ("DEGRADE".equals(parts[2]) && parts.length >= 6)
    {
      try
      {
        long index = Long.parseLong(parts[3]);
        long pts = Long.parseLong(parts[4]);
        if (index < -1L) return null;
        return degrade(parts[5], index, pts);
      }
      catch (NumberFormatException e)
      {
        return null;
      }
    }
    return null;
  }
}
