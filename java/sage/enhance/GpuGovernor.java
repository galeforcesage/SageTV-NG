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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import sage.HwEncoder;
import sage.Sage;

/**
 * Admission control for GPU-enhanced live sessions.
 *
 * <p>The governor is the single place that answers "may this session run, and at
 * what tier?". It is consulted <i>after</i> the benefit gate has already decided a
 * human would see the difference, so its job is purely about capacity.
 *
 * <p>Admission is a cascade, and the order is load-bearing:
 *
 * <ol>
 *   <li><b>Recording veto</b> ({@link RecordingGuard}) — unconditional, first,
 *       and not overridable by any user or admin setting.</li>
 *   <li><b>Feature/plumbing availability</b> — a missing CUDA scaler, deinterlacer
 *       or NVENC HEVC encoder removes every tier rather than producing a broken
 *       ffmpeg invocation at stream time.</li>
 *   <li><b>Free VRAM</b> — measured, not nameplate, so other tenants on the shared
 *       GPU shrink our budget automatically (Invariant 1).</li>
 *   <li><b>Video-engine pressure</b> — soft cap admits, hard cap denies.</li>
 *   <li><b>Disk write budget</b> — a 2160p session writes roughly 5-6x what
 *       today's live transcode does, onto the same arrays recordings are writing
 *       to continuously. This is the least obvious contention vector and is
 *       admitted as its own first-class resource, not folded into "GPU".</li>
 *   <li><b>Calibrated concurrency ceiling</b> — a measured number from
 *       {@link CapacityCalibrator}, never a hardcoded per-GPU-model table.</li>
 * </ol>
 *
 * <p>Every denial steps the tier down the ladder and retries, so a box that can't
 * afford 2160p still gets 1440p or a cheap deinterlace rather than nothing.
 *
 * <p>Sessions must be {@link #release(String) released}. Release is what returns
 * VRAM to the rest of the machine, so it is driven from the transcoder's teardown
 * paths and backstopped by {@link #reapStale()}.
 */
public final class GpuGovernor
{
  private static final String PROP_ENABLED        = "playback/gpu_enhance/enabled";
  private static final String PROP_SOFT_CAP       = "playback/gpu_enhance/soft_cap_pct";
  private static final String PROP_HARD_CAP       = "playback/gpu_enhance/hard_cap_pct";
  private static final String PROP_MAX_SESSIONS   = "playback/gpu_enhance/max_sessions";
  private static final String PROP_FALLBACK_MAX   = "playback/gpu_enhance/max_sessions_when_blind";
  private static final String PROP_DISK_BUDGET    = "playback/gpu_enhance/disk_write_budget_kbps";
  private static final String PROP_VRAM_RESERVE   = "playback/gpu_enhance/vram_reserve_mb";
  private static final String PROP_GPU_INDEX      = "playback/gpu_enhance/gpu_index";
  private static final String PROP_STALE_MS       = "playback/gpu_enhance/session_stale_ms";
  private static final String PROP_REAP_INTERVAL_MS = "playback/gpu_enhance/reap_interval_ms";

  private static final int  DEFAULT_SOFT_CAP     = 70;
  private static final int  DEFAULT_HARD_CAP     = 85;
  /** {@code 0} means "Auto (calibrated)" — the shipped default. */
  private static final int  DEFAULT_MAX_SESSIONS = 0;
  /**
   * Cap used when we have no GPU telemetry at all. Deliberately 1: blind means
   * conservative, never "allow everything".
   */
  private static final int  DEFAULT_BLIND_MAX    = 1;
  private static final long DEFAULT_DISK_BUDGET  = 120000L; // ~120 Mbps aggregate
  private static final long DEFAULT_VRAM_RESERVE = 512L;
  private static final long DEFAULT_STALE_MS     = 5L * 60L * 1000L;
  private static final long DEFAULT_REAP_INTERVAL_MS = 30L * 1000L;

  /** Verdict of an admission request. */
  public static final class Admission
  {
    private final EnhancementTier granted;
    private final String reason;
    private final int gpuIndex;
    private final String sessionId;

    Admission(String sessionId, EnhancementTier granted, int gpuIndex, String reason)
    {
      this.sessionId = sessionId;
      this.granted = granted;
      this.gpuIndex = gpuIndex;
      this.reason = reason;
    }

    /** True when some enhancement was granted. */
    public boolean isGranted() { return granted != null && granted.isActive(); }
    public EnhancementTier getTier() { return granted == null ? EnhancementTier.NONE : granted; }
    public int getGpuIndex() { return gpuIndex; }
    /** Always populated, including on success — this is the telemetry record. */
    public String getReason() { return reason; }
    public String getSessionId() { return sessionId; }

    @Override
    public String toString()
    {
      return "Admission[" + (isGranted() ? getTier().token() : "denied") + " gpu=" + gpuIndex
          + " reason=" + reason + "]";
    }
  }

  /** A live enhanced session holding capacity. */
  static final class Session
  {
    final String id;
    final EnhancementTier tier;
    final int gpuIndex;
    final long startedAt;
    volatile long estBitrateKbps;
    volatile long lastHeartbeat;
    volatile boolean offline;

    Session(String id, EnhancementTier tier, int gpuIndex, long estBitrateKbps, boolean offline)
    {
      this.id = id;
      this.tier = tier;
      this.gpuIndex = gpuIndex;
      this.estBitrateKbps = estBitrateKbps;
      this.offline = offline;
      this.startedAt = Sage.time();
      this.lastHeartbeat = this.startedAt;
    }
  }

  private static final GpuGovernor instance = new GpuGovernor();

  public static GpuGovernor getInstance() { return instance; }

  private final Map<String, Session> sessions = new ConcurrentHashMap<String, Session>();
  private final AtomicLong denials = new AtomicLong();
  private final AtomicLong grants = new AtomicLong();
  private final AtomicBoolean reaperRunning = new AtomicBoolean(false);

  private GpuGovernor() { }

  /** Master switch. Defaults off; enhancement is opt-in. */
  public boolean isEnabled() { return Sage.getBoolean(PROP_ENABLED, false); }

  /**
   * Request admission for {@code desired}, stepping down the ladder until
   * something fits. Returns an {@link Admission} whose tier is
   * {@link EnhancementTier#NONE} when nothing could be granted.
   *
   * <p>On success the session is registered and holds capacity until
   * {@link #release(String)}.
   */
  public Admission requestAdmission(String sessionId, EnhancementTier desired,
                                    int sourceHeight, long estBitrateKbps)
  {
    return requestAdmission(sessionId, desired, sourceHeight, estBitrateKbps, false);
  }

  /**
   * @param offline true for {@code Ministry} batch jobs, which register with the
   *        same governor so a batch conversion can never collectively
   *        oversubscribe the box alongside live sessions and a recording.
   */
  public Admission requestAdmission(String sessionId, EnhancementTier desired,
                                    int sourceHeight, long estBitrateKbps, boolean offline)
  {
    if (sessionId == null || sessionId.length() == 0)
      return deny(sessionId, "no session id");
    if (!isEnabled())
      return deny(sessionId, "enhancement disabled");
    if (desired == null || !desired.isActive())
      return deny(sessionId, "no tier requested");

    // (1) Recording veto — unconditional, and deliberately first.
    RecordingGuard guard = RecordingGuard.getInstance();
    RecordingGuard.CaptureLoad load = guard.sampleLoad();
    EnhancementTier tier = guard.applyVeto(desired, load);
    if (!tier.isActive())
      return deny(sessionId, "recording veto: " + load);

    // (2) Plumbing. Absent filters/encoder means no tier is buildable.
    if (!HwEncoder.gpuEnhanceSupported())
      return deny(sessionId, "ffmpeg lacks CUDA scaler/deinterlacer or hevc_nvenc");

    // Source floor: never upscale sub-720-line material on the live path.
    while (tier.isActive() && !tier.isLegalForSourceHeight(sourceHeight))
      tier = tier.downgrade();
    if (!tier.isActive())
      return deny(sessionId, "source height " + sourceHeight + " below floor "
          + EnhancementTier.SOURCE_HEIGHT_FLOOR);

    int gpuIndex = Sage.getInt(PROP_GPU_INDEX, 0);
    GpuSnapshot snap = GpuMonitor.getInstance().getSnapshot(gpuIndex);

    // (3-6) Walk the ladder until a tier fits every remaining budget.
    List<String> whyNot = new ArrayList<String>();
    while (tier.isActive())
    {
      String failure = checkBudgets(tier, snap, load, estBitrateKbps, offline);
      if (failure == null)
      {
        Session s = new Session(sessionId, tier, gpuIndex,
            effectiveBitrateKbps(tier, estBitrateKbps), offline);
        trackSession(s);
        grants.incrementAndGet();
        String reason = (tier == desired)
            ? "admitted " + tier.token()
            : "admitted " + tier.token() + " (stepped down from " + desired.token() + ": "
              + join(whyNot) + ")";
        if (Sage.DBG) System.out.println("GpuGovernor: " + sessionId + " " + reason
            + " [" + snap + ", " + load + "]");
        return new Admission(sessionId, tier, gpuIndex, reason);
      }
      whyNot.add(tier.token() + ": " + failure);
      tier = tier.downgrade();
    }
    return deny(sessionId, "no tier fits (" + join(whyNot) + ")");
  }

  /**
   * Check every capacity budget for one tier. Returns null when it fits, or a
   * short human-readable reason why it doesn't.
   */
  private String checkBudgets(EnhancementTier tier, GpuSnapshot snap,
                              RecordingGuard.CaptureLoad load, long estBitrateKbps,
                              boolean offline)
  {
    // Concurrency ceiling: explicit admin cap wins if set, else calibrated,
    // else the blind fallback.
    int liveCount = countLiveSessions();
    int adminMax = Sage.getInt(PROP_MAX_SESSIONS, DEFAULT_MAX_SESSIONS);
    int ceiling;
    if (adminMax > 0)
    {
      ceiling = adminMax;
    }
    else if (snap.isKnown())
    {
      ceiling = CapacityCalibrator.getInstance().concurrencyCeiling(tier);
    }
    else
    {
      ceiling = Math.max(1, Sage.getInt(PROP_FALLBACK_MAX, DEFAULT_BLIND_MAX));
    }
    // The recording reserve is subtracted from the calibrated budget, so the
    // "1 or 2 concurrent sessions?" answer emerges from measurement plus current
    // load rather than being hand-set per GPU model.
    if (load != null) ceiling -= load.getReservedTuners();
    if (!offline && liveCount >= Math.max(0, ceiling))
      return "concurrency ceiling " + ceiling + " (active " + liveCount + ")";

    // Disk write budget. Counted for every session, live or offline, because the
    // array doesn't care which process is filling it.
    long budget = Sage.getLong(PROP_DISK_BUDGET, DEFAULT_DISK_BUDGET);
    long inFlight = totalBitrateKbps();
    long want = effectiveBitrateKbps(tier, estBitrateKbps);
    if (budget > 0 && (inFlight + want) > budget)
      return "disk write budget " + budget + "kbps (in flight " + inFlight + " + " + want + ")";

    if (!snap.isKnown())
    {
      // No GPU telemetry: the concurrency ceiling above is the only guard, and it
      // is already the conservative blind value. Nothing further to check.
      return null;
    }

    // Free VRAM, measured. Reserve keeps a downgrade step of headroom and leaves
    // room for other tenants.
    long freeMB = snap.getFreeMemMB();
    if (freeMB >= 0)
    {
      long reserve = Sage.getLong(PROP_VRAM_RESERVE, DEFAULT_VRAM_RESERVE);
      long needMB = CapacityCalibrator.getInstance().vramCostMB(tier);
      if ((freeMB - reserve) < needMB)
        return "free VRAM " + freeMB + "MB - reserve " + reserve + "MB < " + needMB + "MB";
    }

    // Video-engine pressure. Hard cap denies outright; soft cap denies only new
    // admissions, which is what this call is.
    int pressure = snap.getVideoEnginePressurePct();
    if (pressure >= 0)
    {
      int hard = Sage.getInt(PROP_HARD_CAP, DEFAULT_HARD_CAP);
      int soft = Sage.getInt(PROP_SOFT_CAP, DEFAULT_SOFT_CAP);
      if (pressure >= hard) return "video engine at " + pressure + "% (hard cap " + hard + "%)";
      if (pressure >= soft) return "video engine at " + pressure + "% (soft cap " + soft + "%)";
    }
    return null;
  }

  /** Release a session's capacity. Safe to call more than once. */
  public void release(String sessionId)
  {
    if (sessionId == null) return;
    Session s = sessions.remove(sessionId);
    if (s != null)
    {
      // Invalidate so the next admission sees the freed VRAM immediately rather
      // than through a stale 2-second cache.
      GpuMonitor.getInstance().invalidate();
      if (Sage.DBG) System.out.println("GpuGovernor: released " + sessionId
          + " (" + s.tier.token() + "), " + sessions.size() + " remain");
    }
  }

  /** Note that a session is still alive, for {@link #reapStale()}. */
  public void heartbeat(String sessionId)
  {
    Session s = sessions.get(sessionId);
    if (s != null) s.lastHeartbeat = Sage.time();
  }

  /** Update a running session's measured output bitrate. */
  public void updateBitrate(String sessionId, long kbps)
  {
    Session s = sessions.get(sessionId);
    if (s != null && kbps > 0) s.estBitrateKbps = kbps;
  }

  /**
   * Register a granted session and make sure the idle-reaper backstop is
   * running. Package-private so the admission path and tests share one seam.
   */
  void trackSession(Session s)
  {
    if (s == null) return;
    sessions.put(s.id, s);
    startReaperIfNeeded();
  }

  /**
   * Lazily start a daemon that periodically drops leaked reservations
   * ({@link #reapStale()}) and then <b>exits the moment the box is idle</b>, so a
   * server with no enhanced sessions runs no enhancement thread at all
   * (Invariant 1: zero idle footprint). It is re-armed on the next admission.
   *
   * <p>This is the backstop for the primary teardown ({@code stopTranscode() ->
   * release()}): if any session-end path fails to release — an exception between
   * admission and teardown, or a self-exited child whose client never
   * disconnects — the reservation would otherwise silently shrink capacity until
   * a JVM restart. The reaper keys off {@link Session#lastHeartbeat}, which the
   * transcoder refreshes from live ffmpeg progress, so a genuinely running
   * session is never reaped while its child is alive.
   */
  private void startReaperIfNeeded()
  {
    if (sessions.isEmpty()) return;
    if (!reaperRunning.compareAndSet(false, true)) return;
    Thread t = new Thread("GpuGovernor-Reaper")
    {
      public void run()
      {
        try
        {
          while (!sessions.isEmpty())
          {
            long interval = Sage.getLong(PROP_REAP_INTERVAL_MS, DEFAULT_REAP_INTERVAL_MS);
            if (interval <= 0) interval = DEFAULT_REAP_INTERVAL_MS;
            try { Thread.sleep(interval); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            reapStale();
          }
        }
        finally
        {
          reaperRunning.set(false);
          // A session admitted between the loop's isEmpty() check and clearing
          // the flag must not be left with no reaper; re-arm if so.
          if (!sessions.isEmpty()) startReaperIfNeeded();
        }
      }
    };
    t.setDaemon(true);
    t.start();
  }

  /**
   * Drop bookkeeping for sessions that stopped heart-beating.
   *
   * <p>This is a <i>safety net</i>, not the primary teardown mechanism: leaking a
   * reservation would silently shrink capacity for everyone until a restart,
   * which is exactly the failure the phantom-transcode reaper exists to catch on
   * the process side.
   */
  public int reapStale()
  {
    long stale = Sage.getLong(PROP_STALE_MS, DEFAULT_STALE_MS);
    if (stale <= 0) return 0;
    long now = Sage.time();
    int reaped = 0;
    for (Session s : new ArrayList<Session>(sessions.values()))
    {
      if ((now - s.lastHeartbeat) > stale)
      {
        sessions.remove(s.id);
        reaped++;
        if (Sage.DBG) System.out.println("GpuGovernor: reaped stale session " + s.id
            + " (no heartbeat for " + (now - s.lastHeartbeat) + "ms)");
      }
    }
    if (reaped > 0) GpuMonitor.getInstance().invalidate();
    return reaped;
  }

  /** Number of registered live (non-offline) enhanced sessions. */
  public int countLiveSessions()
  {
    int n = 0;
    for (Session s : sessions.values()) if (!s.offline) n++;
    return n;
  }

  /** Number of registered offline ({@code Ministry}) sessions. */
  public int countOfflineSessions()
  {
    int n = 0;
    for (Session s : sessions.values()) if (s.offline) n++;
    return n;
  }

  /** Total registered sessions, live and offline. */
  public int countSessions() { return sessions.size(); }

  /** Total registered output bitrate across all enhanced sessions. */
  public long totalBitrateKbps()
  {
    long t = 0;
    for (Session s : sessions.values()) t += Math.max(0, s.estBitrateKbps);
    return t;
  }

  /** Tier currently granted to a session, or {@link EnhancementTier#NONE}. */
  public EnhancementTier tierOf(String sessionId)
  {
    Session s = (sessionId == null) ? null : sessions.get(sessionId);
    return (s == null) ? EnhancementTier.NONE : s.tier;
  }

  /** True if zero enhanced sessions are registered — the idle-footprint check. */
  public boolean isIdle() { return sessions.isEmpty(); }

  public long getGrantCount() { return grants.get(); }
  public long getDenialCount() { return denials.get(); }

  /** Snapshot of active session ids, for the admin dashboard. */
  public List<String> activeSessionIds()
  {
    return Collections.unmodifiableList(new ArrayList<String>(sessions.keySet()));
  }

  /**
   * Bitrate a tier is expected to produce, used for the disk budget. Falls back
   * to the tier's nominal ladder value when the caller has no estimate.
   */
  private static long effectiveBitrateKbps(EnhancementTier tier, long estBitrateKbps)
  {
    if (estBitrateKbps > 0) return estBitrateKbps;
    switch (tier)
    {
      case ENHANCE_2160P: return 35000L;
      case ENHANCE_1440P: return 20000L;
      case ENHANCE_1080P: return 12000L;
      case DEINTERLACE_ONLY: return 8000L;
      default: return 0L;
    }
  }

  private Admission deny(String sessionId, String reason)
  {
    denials.incrementAndGet();
    if (Sage.DBG) System.out.println("GpuGovernor: denied " + sessionId + ": " + reason);
    return new Admission(sessionId, EnhancementTier.NONE, -1, reason);
  }

  private static String join(List<String> parts)
  {
    if (parts == null || parts.isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < parts.size(); i++)
    {
      if (i > 0) sb.append("; ");
      sb.append(parts.get(i));
    }
    return sb.toString();
  }
}
