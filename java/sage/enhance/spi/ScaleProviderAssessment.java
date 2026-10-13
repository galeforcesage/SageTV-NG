/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package sage.enhance.spi;

import java.time.Duration;
import java.time.Instant;

/** Typed, profile-specific availability and measured-capacity assessment. */
public final class ScaleProviderAssessment
{
  private final ScaleProfileKey profile;
  private final boolean admissible;
  private final String reason;
  private final double sustainableFps, requiredFps, headroomRatio;
  private final Instant benchmarkTimestamp;
  private final Duration benchmarkAge, sampleDuration;
  private final long sampleFrameCount;
  private final boolean measured;

  public ScaleProviderAssessment(ScaleProfileKey profile, boolean admissible, String reason,
      double sustainableFps, double requiredFps, double headroomRatio,
      Instant benchmarkTimestamp, Duration benchmarkAge, Duration sampleDuration,
      long sampleFrameCount)
  {
    this(profile, admissible, reason, sustainableFps, requiredFps, headroomRatio,
        benchmarkTimestamp, benchmarkAge, sampleDuration, sampleFrameCount, true);
  }

  private ScaleProviderAssessment(ScaleProfileKey profile, boolean admissible, String reason,
      double sustainableFps, double requiredFps, double headroomRatio,
      Instant benchmarkTimestamp, Duration benchmarkAge, Duration sampleDuration,
      long sampleFrameCount, boolean measured)
  {
    this.profile = profile;
    this.admissible = admissible;
    this.reason = reason == null ? "" : reason;
    this.sustainableFps = sustainableFps;
    this.requiredFps = requiredFps;
    this.headroomRatio = headroomRatio;
    this.benchmarkTimestamp = benchmarkTimestamp;
    this.benchmarkAge = benchmarkAge;
    this.sampleDuration = sampleDuration;
    this.sampleFrameCount = sampleFrameCount;
    this.measured = measured;
  }

  public static ScaleProviderAssessment fromAvailability(ScaleRequest request,
      ScaleProviderAvailability availability)
  {
    boolean ok = availability != null && availability.isAvailable();
    return new ScaleProviderAssessment(ScaleProfileKey.fromRequest(request), ok,
        availability == null ? "null availability" : availability.getDetail(),
        0.0, request == null ? 0.0 : request.getRequiredOutputFps(), 0.0,
        null, null, null, 0L, false);
  }

  public ScaleProfileKey getProfile() { return profile; }
  public boolean isAdmissible() { return admissible; }
  public String getReason() { return reason; }
  public double getSustainableFps() { return sustainableFps; }
  public double getRequiredFps() { return requiredFps; }
  public double getHeadroomRatio() { return headroomRatio; }
  public Instant getBenchmarkTimestamp() { return benchmarkTimestamp; }
  public Duration getBenchmarkAge() { return benchmarkAge; }
  public Duration getSampleDuration() { return sampleDuration; }
  public long getSampleFrameCount() { return sampleFrameCount; }
  public boolean isMeasured() { return measured; }

  /** Reject malformed measured claims even if a provider marks them admissible. */
  public boolean isUsable()
  {
    if (!admissible) return false;
    if (!measured) return true;
    return profile != null && profile.isComplete()
        && sustainableFps > 0.0 && requiredFps > 0.0
        && headroomRatio > 0.0 && sustainableFps >= requiredFps
        && benchmarkTimestamp != null && benchmarkAge != null
        && !benchmarkAge.isNegative() && sampleDuration != null
        && !sampleDuration.isZero() && !sampleDuration.isNegative()
        && sampleFrameCount > 0L;
  }

  public boolean isUsableFor(ScaleRequest request)
  {
    return isUsable() && (!measured || profile.matchesRequest(request));
  }

  @Override
  public String toString()
  {
    return "ScaleProviderAssessment[admissible=" + admissible + " usable=" + isUsable()
        + " measured=" + measured + " sustainableFps=" + sustainableFps
        + " requiredFps=" + requiredFps + " headroom=" + headroomRatio
        + " age=" + benchmarkAge + " profile=" + profile + " reason=" + reason + "]";
  }
}
