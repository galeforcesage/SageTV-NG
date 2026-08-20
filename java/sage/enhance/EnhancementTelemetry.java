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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sage.Sage;

/**
 * One structured record per enhancement decision, and the feedback loop built on
 * top of them.
 *
 * <p>This is the component that removes most of the risk from the feature. The
 * alternative to measuring outcomes is a table of device and GPU assumptions that
 * is wrong somewhere and silently degrades playback for whoever owns the hardware
 * nobody tested. Instead: record what was decided and what happened, and let
 * repeated bad outcomes for a {@code {profile, surface, tier}} bucket lower that
 * bucket's starting tier automatically, with repeated clean outcomes letting it
 * climb back.
 *
 * <p>Records are kept in a bounded in-memory ring for the admin view; the
 * bias table is what actually feeds back into decisions and is the only part that
 * outlives the ring.
 *
 * <p>Property knobs:
 * <pre>
 *   playback/gpu_enhance/telemetry_ring_size   default 200
 *   playback/gpu_enhance/demote_after          consecutive bad outcomes, default 2
 *   playback/gpu_enhance/promote_after         consecutive clean outcomes, default 10
 * </pre>
 */
public final class EnhancementTelemetry
{
  private static final String PROP_RING     = "playback/gpu_enhance/telemetry_ring_size";
  private static final String PROP_DEMOTE   = "playback/gpu_enhance/demote_after";
  private static final String PROP_PROMOTE  = "playback/gpu_enhance/promote_after";

  private static final int DEFAULT_RING    = 200;
  private static final int DEFAULT_DEMOTE  = 2;
  private static final int DEFAULT_PROMOTE = 10;

  /** How a session ended. */
  public enum Outcome
  {
    /** Still running. */
    ACTIVE,
    /** Ran to completion with no rebuffering or downgrade. */
    CLEAN,
    /** Client reported sustained rebuffering. */
    REBUFFERED,
    /** Bitrate was cut mid-stream by the watchdog. */
    DOWNGRADED,
    /** Torn down to protect a recording. */
    YIELDED_TO_RECORDING,
    /** Encoder or pipeline error. */
    FAILED;

    /** True if this outcome should count against the bucket. */
    public boolean isBad()
    {
      return this == REBUFFERED || this == DOWNGRADED || this == FAILED;
    }
  }

  /** One decision record. Mutable only in its outcome fields. */
  public static final class Record
  {
    final String sessionId;
    final String bucketKey;
    final long decidedAt;
    final int sourceWidth;
    final int sourceHeight;
    final boolean sourceInterlaced;
    final int sourceFps;
    final EnhancementTier desiredTier;
    final EnhancementTier grantedTier;
    final String admissionReason;
    final String gpuSnapshot;
    final long bitrateKbps;

    volatile Outcome outcome = Outcome.ACTIVE;
    volatile long endedAt = 0L;
    volatile String outcomeDetail = "";

    Record(String sessionId, String bucketKey, int sw, int sh, boolean interlaced, int fps,
           EnhancementTier desired, EnhancementTier granted, String reason,
           String gpuSnapshot, long bitrateKbps)
    {
      this.sessionId = sessionId;
      this.bucketKey = bucketKey;
      this.decidedAt = Sage.time();
      this.sourceWidth = sw;
      this.sourceHeight = sh;
      this.sourceInterlaced = interlaced;
      this.sourceFps = fps;
      this.desiredTier = desired;
      this.grantedTier = granted;
      this.admissionReason = reason;
      this.gpuSnapshot = gpuSnapshot;
      this.bitrateKbps = bitrateKbps;
    }

    public String getSessionId() { return sessionId; }
    public String getBucketKey() { return bucketKey; }
    public EnhancementTier getGrantedTier() { return grantedTier; }
    public EnhancementTier getDesiredTier() { return desiredTier; }
    public Outcome getOutcome() { return outcome; }
    public String getAdmissionReason() { return admissionReason; }
    public long getDecidedAt() { return decidedAt; }

    /** Single-line form for the log and the admin table. */
    public String toLogLine()
    {
      StringBuilder sb = new StringBuilder();
      sb.append("session=").append(sessionId)
        .append(" bucket=").append(bucketKey)
        .append(" src=").append(sourceWidth).append('x').append(sourceHeight)
        .append(sourceInterlaced ? "i" : "p").append('@').append(sourceFps)
        .append(" want=").append(desiredTier.token())
        .append(" got=").append(grantedTier.token())
        .append(" kbps=").append(bitrateKbps)
        .append(" outcome=").append(outcome)
        .append(" why=\"").append(admissionReason).append('"');
      if (gpuSnapshot != null && gpuSnapshot.length() > 0)
        sb.append(" gpu=\"").append(gpuSnapshot).append('"');
      if (outcomeDetail != null && outcomeDetail.length() > 0)
        sb.append(" detail=\"").append(outcomeDetail).append('"');
      if (endedAt > 0) sb.append(" durMs=").append(endedAt - decidedAt);
      return sb.toString();
    }

    @Override
    public String toString() { return "Record[" + toLogLine() + "]"; }
  }

  /** Rolling verdict for one {@code {profile, surface, tier}} bucket. */
  static final class Bias
  {
    int consecutiveBad;
    int consecutiveClean;
    /** Highest tier this bucket is currently trusted with; null = no opinion. */
    EnhancementTier ceiling;
  }

  private static final EnhancementTelemetry instance = new EnhancementTelemetry();

  public static EnhancementTelemetry getInstance() { return instance; }

  private final LinkedHashMap<String, Record> ring = new LinkedHashMap<String, Record>();
  private final Map<String, Bias> bias = new LinkedHashMap<String, Bias>();

  private EnhancementTelemetry() { }

  /**
   * Build the bucket key that outcomes are aggregated under. Deliberately made of
   * things that describe the <i>situation</i> (client profile, chosen surface,
   * tier) rather than a device model string, so the loop learns behavior instead
   * of encoding a device myth.
   */
  public static String bucketKey(String profileId, String surfaceId, EnhancementTier tier)
  {
    return (profileId == null ? "?" : profileId) + "|"
         + (surfaceId == null ? "?" : surfaceId) + "|"
         + (tier == null ? "none" : tier.token());
  }

  /** Record a decision. Returns the record so the caller can close it out later. */
  public synchronized Record recordDecision(String sessionId, String profileId, String surfaceId,
                                            int sourceWidth, int sourceHeight, boolean interlaced,
                                            int fps, EnhancementTier desired, EnhancementTier granted,
                                            String reason, String gpuSnapshot, long bitrateKbps)
  {
    Record r = new Record(sessionId, bucketKey(profileId, surfaceId, granted),
        sourceWidth, sourceHeight, interlaced, fps, desired, granted, reason,
        gpuSnapshot, bitrateKbps);
    ring.put(sessionId, r);
    trim();
    if (Sage.DBG) System.out.println("EnhancementTelemetry: decision " + r.toLogLine());
    return r;
  }

  /** Close out a session and feed the result back into the bias table. */
  public synchronized void recordOutcome(String sessionId, Outcome outcome, String detail)
  {
    Record r = ring.get(sessionId);
    if (r == null) return;
    r.outcome = (outcome == null) ? Outcome.FAILED : outcome;
    r.outcomeDetail = (detail == null) ? "" : detail;
    r.endedAt = Sage.time();
    applyBias(r);
    if (Sage.DBG) System.out.println("EnhancementTelemetry: outcome " + r.toLogLine());
  }

  /**
   * Update the bucket's rolling verdict.
   *
   * <p>Demotion is fast and promotion is slow, on purpose. A viewer who sees
   * stuttering twice should stop seeing it immediately; a bucket that has been
   * demoted should have to prove itself over many clean sessions before it climbs
   * back, or the system will oscillate between tiers on marginal hardware.
   *
   * <p>{@link Outcome#YIELDED_TO_RECORDING} is explicitly <b>not</b> counted as a
   * bad outcome: the session was working fine and was stopped by policy, so
   * holding it against the client's hardware would be wrong.
   */
  private void applyBias(Record r)
  {
    if (r.outcome == Outcome.YIELDED_TO_RECORDING || r.outcome == Outcome.ACTIVE) return;
    Bias b = bias.get(r.bucketKey);
    if (b == null) { b = new Bias(); bias.put(r.bucketKey, b); }

    if (r.outcome.isBad())
    {
      b.consecutiveClean = 0;
      b.consecutiveBad++;
      if (b.consecutiveBad >= Sage.getInt(PROP_DEMOTE, DEFAULT_DEMOTE))
      {
        EnhancementTier from = (b.ceiling == null) ? r.grantedTier : b.ceiling;
        b.ceiling = from.downgrade();
        b.consecutiveBad = 0;
        if (Sage.DBG) System.out.println("EnhancementTelemetry: demoting bucket "
            + r.bucketKey + " to " + b.ceiling.token());
      }
    }
    else
    {
      b.consecutiveBad = 0;
      b.consecutiveClean++;
      if (b.ceiling != null && b.consecutiveClean >= Sage.getInt(PROP_PROMOTE, DEFAULT_PROMOTE))
      {
        b.consecutiveClean = 0;
        // Climb back one step by clearing the ceiling only when it has returned
        // to the top; otherwise raise it a single notch.
        b.ceiling = raise(b.ceiling);
        if (Sage.DBG) System.out.println("EnhancementTelemetry: promoting bucket "
            + r.bucketKey + " to " + (b.ceiling == null ? "unrestricted" : b.ceiling.token()));
      }
    }
  }

  private static EnhancementTier raise(EnhancementTier t)
  {
    switch (t)
    {
      case NONE: return EnhancementTier.DEINTERLACE_ONLY;
      case DEINTERLACE_ONLY: return EnhancementTier.ENHANCE_1080P;
      case ENHANCE_1080P: return EnhancementTier.ENHANCE_1440P;
      case ENHANCE_1440P: return EnhancementTier.ENHANCE_2160P;
      default: return null; // unrestricted
    }
  }

  /**
   * Clamp a desired tier by what this bucket has earned. Returns {@code desired}
   * unchanged when the bucket has no adverse history.
   */
  public synchronized EnhancementTier applyBiasCeiling(String profileId, String surfaceId,
                                                       EnhancementTier desired)
  {
    if (desired == null || !desired.isActive()) return EnhancementTier.NONE;
    Bias b = bias.get(bucketKey(profileId, surfaceId, desired));
    if (b == null || b.ceiling == null) return desired;
    return (desired.getRank() > b.ceiling.getRank()) ? b.ceiling : desired;
  }

  /** Most recent records, newest last. For the admin dashboard. */
  public synchronized List<Record> recentRecords()
  {
    return Collections.unmodifiableList(new ArrayList<Record>(ring.values()));
  }

  /** Drop all history. Exposed for the admin "reset" action and for tests. */
  public synchronized void clear()
  {
    ring.clear();
    bias.clear();
  }

  private void trim()
  {
    int max = Math.max(10, Sage.getInt(PROP_RING, DEFAULT_RING));
    while (ring.size() > max)
    {
      String oldest = ring.keySet().iterator().next();
      ring.remove(oldest);
    }
  }
}
