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

import java.util.Vector;

import sage.Airing;
import sage.CaptureDevice;
import sage.MMC;
import sage.Sage;
import sage.Scheduler;

/**
 * Invariant 0: <b>recordings are never affected.</b>
 *
 * <p>This outranks every other goal in this feature. A viewer's picture glitching
 * is recoverable; a damaged or dropped recording is permanent. Enhancement is
 * therefore a strictly opportunistic consumer of leftover capacity, and this class
 * is the thing that decides how much "leftover" there is.
 *
 * <p>Two properties of the design matter more than the arithmetic:
 *
 * <ol>
 *   <li><b>Recording is a veto, not a priority tier.</b> It cannot be outvoted by
 *       a user's "Maximum Quality" preference, by the admin "Force" posture, or by
 *       abundant GPU headroom. Those settings push past the <i>benefit</i> gate
 *       only; none of them reaches this class.</li>
 *   <li><b>Admission is schedule-aware, not just state-aware.</b> Consulting only
 *       {@link CaptureDevice#isRecording()} would happily start a 2160p session
 *       ninety seconds before four tuners fire. The lookahead window is the whole
 *       point: if a tier cannot survive the imminent load, it is never granted.</li>
 * </ol>
 *
 * <p>This extends the posture the repo already established — {@code Ministry}
 * drops offline transcode concurrency to 1 while any capture device is recording,
 * and can SIGSTOP running jobs under {@code transcoder/pause_during_recording}.
 *
 * <p>Property knobs:
 * <pre>
 *   playback/gpu_enhance/recording_protection   "protect" (default) | "balanced"
 *   playback/gpu_enhance/schedule_lookahead_ms  default 300000 (5 min)
 * </pre>
 */
public final class RecordingGuard
{
  private static final String PROP_PROTECTION = "playback/gpu_enhance/recording_protection";
  private static final String PROP_LOOKAHEAD  = "playback/gpu_enhance/schedule_lookahead_ms";

  private static final long DEFAULT_LOOKAHEAD = 5L * 60L * 1000L;

  /** How much capacity recording is allowed to take away from enhancement. */
  public enum Posture
  {
    /**
     * Default. While any capture is active or imminent, no <i>new</i> enhanced
     * session is admitted at all. The safest posture and the right default for a
     * DVR: enhancement is a luxury, capture is the product.
     */
    PROTECT,
    /**
     * Enhancement is capped rather than forbidden while recording: existing
     * sessions continue and new ones may be admitted, but only at
     * {@link EnhancementTier#DEINTERLACE_ONLY}, which costs a small fraction of
     * an upscale tier.
     */
    BALANCED;

    public static Posture fromToken(String t)
    {
      if (t != null && t.trim().equalsIgnoreCase("balanced")) return BALANCED;
      return PROTECT;
    }
  }

  /** Immutable view of current and imminent capture load. */
  public static final class CaptureLoad
  {
    private final int activeRecordings;
    private final int imminentRecordings;
    private final long msUntilNext;

    CaptureLoad(int active, int imminent, long msUntilNext)
    {
      this.activeRecordings = active;
      this.imminentRecordings = imminent;
      this.msUntilNext = msUntilNext;
    }

    public int getActiveRecordings() { return activeRecordings; }
    public int getImminentRecordings() { return imminentRecordings; }

    /** Milliseconds until the next scheduled recording starts, or -1 if none. */
    public long getMsUntilNext() { return msUntilNext; }

    /** True if any tuner is recording right now. */
    public boolean isRecordingNow() { return activeRecordings > 0; }

    /** True if any tuner is recording now or will start within the lookahead. */
    public boolean isBusyOrImminent() { return activeRecordings > 0 || imminentRecordings > 0; }

    /** Total tuners to reserve capacity for. */
    public int getReservedTuners() { return activeRecordings + imminentRecordings; }

    @Override
    public String toString()
    {
      return "CaptureLoad[active=" + activeRecordings + " imminent=" + imminentRecordings
          + " nextIn=" + msUntilNext + "ms]";
    }
  }

  private static final RecordingGuard instance = new RecordingGuard();

  public static RecordingGuard getInstance() { return instance; }

  private RecordingGuard() { }

  public Posture getPosture()
  {
    return Posture.fromToken(Sage.get(PROP_PROTECTION, "protect"));
  }

  /**
   * Sample active and imminent capture load.
   *
   * <p>Every probe is individually defensive: a failure to read capture or
   * schedule state must never break admission, and must never be mistaken for
   * "nothing is recording". On any error we report a synthetic busy load so the
   * veto engages — the fail-closed direction.
   */
  public CaptureLoad sampleLoad()
  {
    int active = 0;
    int imminent = 0;
    long soonest = -1L;
    try
    {
      CaptureDevice[] cds = MMC.getInstance().getCaptureDevices();
      if (cds != null)
      {
        long now = Sage.time();
        long lookahead = Sage.getLong(PROP_LOOKAHEAD, DEFAULT_LOOKAHEAD);
        for (int i = 0; i < cds.length; i++)
        {
          CaptureDevice cd = cds[i];
          if (cd == null) continue;
          if (cd.isRecording()) { active++; continue; }

          // Not recording now — is it about to be?
          long startsIn = msUntilNextScheduled(cd, now);
          if (startsIn >= 0 && startsIn <= lookahead)
          {
            imminent++;
            if (soonest < 0 || startsIn < soonest) soonest = startsIn;
          }
        }
      }
    }
    catch (Throwable t)
    {
      if (Sage.DBG) System.out.println("RecordingGuard: capture probe failed (" + t
          + "); assuming recording is active");
      return new CaptureLoad(1, 0, -1L);
    }
    return new CaptureLoad(active, imminent, soonest);
  }

  /**
   * Milliseconds until this device's next scheduled recording, or -1 when the
   * schedule is empty or unreadable.
   */
  private static long msUntilNextScheduled(CaptureDevice cd, long now)
  {
    try
    {
      Vector<Airing> sched = Scheduler.getInstance().getSchedule(cd);
      if (sched == null || sched.isEmpty()) return -1L;
      long best = -1L;
      for (int i = 0; i < sched.size(); i++)
      {
        Airing a = sched.get(i);
        if (a == null) continue;
        long delta = a.getStartTime() - now;
        // Already started airings are covered by isRecording(); ignore the past.
        if (delta < 0) continue;
        if (best < 0 || delta < best) best = delta;
      }
      return best;
    }
    catch (Throwable t)
    {
      return -1L;
    }
  }

  /**
   * Apply the recording veto to a desired tier, returning the highest tier that
   * is still allowed. {@link EnhancementTier#NONE} means "do not enhance".
   *
   * <p>This is the governor's <i>first</i> check and is unconditional — there is
   * deliberately no {@code force} parameter, because there is no caller who is
   * allowed to skip it.
   */
  public EnhancementTier applyVeto(EnhancementTier desired, CaptureLoad load)
  {
    if (desired == null || !desired.isActive()) return EnhancementTier.NONE;
    if (load == null) return EnhancementTier.NONE;
    if (!load.isBusyOrImminent()) return desired;

    if (getPosture() == Posture.BALANCED)
    {
      // Capped, not forbidden: deinterlace-only is cheap enough to coexist.
      return (desired.getRank() <= EnhancementTier.DEINTERLACE_ONLY.getRank())
          ? desired : EnhancementTier.DEINTERLACE_ONLY;
    }
    return EnhancementTier.NONE;
  }

  /** Convenience: sample and veto in one call. */
  public EnhancementTier applyVeto(EnhancementTier desired)
  {
    return applyVeto(desired, sampleLoad());
  }

  /**
   * Human-readable explanation for a veto, for the telemetry record and the
   * admin dashboard. Returns null when nothing was vetoed.
   */
  public String explainVeto(EnhancementTier desired, EnhancementTier granted, CaptureLoad load)
  {
    if (desired == granted) return null;
    if (load == null) return "recording state unknown";
    return "recording veto (" + getPosture().name().toLowerCase(java.util.Locale.ROOT)
        + "): " + load + ", " + desired.token() + " -> " + granted.token();
  }
}
