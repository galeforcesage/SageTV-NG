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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import sage.HwEncoder;
import sage.Sage;

/**
 * Derives this host's per-tier concurrency ceiling <b>by measurement</b>.
 *
 * <p>This exists to answer "can this box run one enhanced session, or two?"
 * honestly. The tempting answer — a table mapping GPU model to a session count —
 * is wrong, and wrong in a way that is worth writing down so it doesn't get
 * reintroduced:
 *
 * <ul>
 *   <li>The driver's concurrent-session cap is <b>not</b> the binding constraint.
 *       Every GeForce from Turing through Blackwell reports max 12 concurrent
 *       NVENC sessions; the old 2/3-session consumer cap is gone. We will never
 *       approach 12 with 4K live sessions.</li>
 *   <li>NVENC <i>engine count</i> does not track card tier. The entire Ampere
 *       30-series desktop line, up to the 3090 Ti, has a single NVENC — the same
 *       count as an RTX 2060. Among Blackwell, the 5060 also has one; only the
 *       5070 Ti / 5080 have two, and only the 5090 has three.</li>
 *   <li>So the real ceiling moves with things a model name doesn't capture:
 *       tier (2160p vs 1440p), source frame rate (60fps sports costs roughly
 *       double 30fps news), deinterlacer choice, and how much capacity Invariant 0
 *       has already reserved for recording.</li>
 * </ul>
 *
 * <p>Hardcoding a model-to-count table would be the same "device myth" this
 * feature rejects for clients; the same discipline applies to servers. Instead a
 * short synthetic encode is run per tier, its achieved throughput is measured, and
 * the ceiling is derived from how much real-time headroom that leaves.
 *
 * <p>Per Invariant 1 the probe is a short-lived subprocess whose <i>results</i>
 * are cached as plain numbers. It holds no context, and it refuses to run while
 * anything is recording.
 *
 * <p>Property knobs:
 * <pre>
 *   playback/gpu_enhance/calibrated/&lt;tier&gt;_fps       cached achieved fps
 *   playback/gpu_enhance/calibrated/&lt;tier&gt;_vram_mb   cached VRAM cost
 *   playback/gpu_enhance/calibration_seconds          synthetic clip length, default 5
 *   playback/gpu_enhance/calibration_headroom         safety divisor, default 1.3
 * </pre>
 */
public final class CapacityCalibrator
{
  private static final String PROP_PREFIX      = "playback/gpu_enhance/calibrated/";
  private static final String PROP_SECONDS     = "playback/gpu_enhance/calibration_seconds";
  private static final String PROP_HEADROOM    = "playback/gpu_enhance/calibration_headroom";
  private static final String PROP_FFMPEG      = "multimedia/hwaccel/probe_ffmpeg";
  private static final String DEFAULT_FFMPEG   = "/opt/sagetv/server/ffmpeg";

  private static final int    DEFAULT_SECONDS  = 5;
  private static final String DEFAULT_HEADROOM = "1.3";

  /**
   * Conservative per-tier VRAM estimates used before calibration has run. These
   * are starting points that calibration replaces with measured values — they are
   * intentionally pessimistic so an uncalibrated host under-admits rather than
   * over-admits.
   */
  private static final Map<EnhancementTier, Long> DEFAULT_VRAM = buildDefaultVram();

  private static Map<EnhancementTier, Long> buildDefaultVram()
  {
    Map<EnhancementTier, Long> m = new EnumMap<EnhancementTier, Long>(EnhancementTier.class);
    m.put(EnhancementTier.NONE, 0L);
    m.put(EnhancementTier.DEINTERLACE_ONLY, 350L);
    m.put(EnhancementTier.ENHANCE_1080P, 500L);
    m.put(EnhancementTier.ENHANCE_1440P, 700L);
    m.put(EnhancementTier.ENHANCE_2160P, 1100L);
    return m;
  }

  /** Result of measuring one tier. */
  public static final class TierCapacity
  {
    private final EnhancementTier tier;
    private final double achievedFps;
    private final long vramMB;
    private final boolean measured;

    TierCapacity(EnhancementTier tier, double achievedFps, long vramMB, boolean measured)
    {
      this.tier = tier;
      this.achievedFps = achievedFps;
      this.vramMB = vramMB;
      this.measured = measured;
    }

    public EnhancementTier getTier() { return tier; }
    public double getAchievedFps() { return achievedFps; }
    public long getVramMB() { return vramMB; }
    /** False when these are defaults rather than measurements. */
    public boolean isMeasured() { return measured; }

    @Override
    public String toString()
    {
      return "TierCapacity[" + tier.token() + " fps=" + String.format("%.1f", achievedFps)
          + " vram=" + vramMB + "MB " + (measured ? "measured" : "default") + "]";
    }
  }

  private static final CapacityCalibrator instance = new CapacityCalibrator();

  public static CapacityCalibrator getInstance() { return instance; }

  private final Map<EnhancementTier, TierCapacity> results =
      new EnumMap<EnhancementTier, TierCapacity>(EnhancementTier.class);
  private volatile boolean running = false;

  private CapacityCalibrator() { loadCached(); }

  /** True while a calibration pass is in flight. */
  public boolean isRunning() { return running; }

  /** Measured or default capacity for a tier. Never null. */
  public synchronized TierCapacity capacityOf(EnhancementTier tier)
  {
    if (tier == null) tier = EnhancementTier.NONE;
    TierCapacity c = results.get(tier);
    if (c != null) return c;
    return new TierCapacity(tier, 0.0, defaultVram(tier), false);
  }

  /** Per-session VRAM cost in MB — measured if calibrated, else conservative default. */
  public long vramCostMB(EnhancementTier tier)
  {
    TierCapacity c = capacityOf(tier);
    long v = c.getVramMB();
    return (v > 0) ? v : defaultVram(tier);
  }

  private static long defaultVram(EnhancementTier tier)
  {
    Long v = DEFAULT_VRAM.get(tier == null ? EnhancementTier.NONE : tier);
    return (v == null) ? 800L : v.longValue();
  }

  /**
   * How many concurrent sessions of this tier the host can sustain.
   *
   * <p>This is throughput-derived, not count-derived: the session count is an
   * <i>output</i> of the model, not an input. If the calibration measured N times
   * real-time throughput, the host can sustain roughly N concurrent sessions,
   * divided by a safety headroom factor.
   *
   * <p>Returns 1 when uncalibrated — the conservative assumption, and the same
   * answer the offline path already reaches via {@code Ministry}'s drop-to-one
   * rule while recording.
   */
  public int concurrencyCeiling(EnhancementTier tier)
  {
    if (tier == null || !tier.isActive()) return 0;
    TierCapacity c = capacityOf(tier);
    if (!c.isMeasured() || c.getAchievedFps() <= 0) return 1;

    // Real-time for live TV means keeping up with a 60fps source in the worst case.
    double realtimeFps = 60.0;
    double headroom = parseDouble(Sage.get(PROP_HEADROOM, DEFAULT_HEADROOM), 1.3);
    if (headroom < 1.0) headroom = 1.0;
    int ceiling = (int) Math.floor(c.getAchievedFps() / (realtimeFps * headroom));
    return Math.max(1, ceiling);
  }

  /**
   * Run a calibration pass across every buildable tier.
   *
   * <p>Refuses to run while anything is recording or is about to — a calibration
   * that competes with capture would both corrupt its own measurement and violate
   * Invariant 0. Returns the list of results, empty if it declined to run.
   */
  public synchronized List<TierCapacity> calibrate()
  {
    List<TierCapacity> out = new ArrayList<TierCapacity>();
    if (running) return out;
    if (!HwEncoder.gpuEnhanceSupported())
    {
      if (Sage.DBG) System.out.println("CapacityCalibrator: ffmpeg lacks the GPU enhance "
          + "pipeline; skipping calibration");
      return out;
    }
    RecordingGuard.CaptureLoad load = RecordingGuard.getInstance().sampleLoad();
    if (load.isBusyOrImminent())
    {
      if (Sage.DBG) System.out.println("CapacityCalibrator: declining to calibrate, "
          + load + " (Invariant 0)");
      return out;
    }
    if (!GpuGovernor.getInstance().isIdle())
    {
      if (Sage.DBG) System.out.println("CapacityCalibrator: declining to calibrate while "
          + "enhanced sessions are active");
      return out;
    }

    running = true;
    try
    {
      EnhancementTier[] tiers = {
        EnhancementTier.DEINTERLACE_ONLY, EnhancementTier.ENHANCE_1080P,
        EnhancementTier.ENHANCE_1440P, EnhancementTier.ENHANCE_2160P,
      };
      for (EnhancementTier t : tiers)
      {
        TierCapacity c = measureTier(t);
        if (c != null)
        {
          results.put(t, c);
          persist(c);
          out.add(c);
          if (Sage.DBG) System.out.println("CapacityCalibrator: " + c
              + " -> ceiling " + concurrencyCeiling(t));
        }
      }
    }
    finally
    {
      running = false;
      GpuMonitor.getInstance().invalidate();
    }
    return out;
  }

  /**
   * Measure one tier with a short synthetic encode.
   *
   * <p>Uses ffmpeg's {@code testsrc2} generator rather than a real recording so
   * calibration never touches the media library, never reads from an array a
   * tuner is writing to, and produces a repeatable number. Output goes to
   * {@code -f null} so nothing is written to disk.
   */
  private TierCapacity measureTier(EnhancementTier tier)
  {
    String scaler = HwEncoder.cudaScaler();
    String deint = HwEncoder.cudaDeinterlacer(false);
    if (scaler == null || deint == null) return null;

    int seconds = Math.max(1, Sage.getInt(PROP_SECONDS, DEFAULT_SECONDS));
    String bin = Sage.get(PROP_FFMPEG, DEFAULT_FFMPEG);

    List<String> cmd = new ArrayList<String>();
    cmd.add(bin);
    cmd.add("-hide_banner");
    cmd.add("-nostats");
    // Synthetic 1080p60 source, the realistic worst case for live OTA.
    cmd.add("-f"); cmd.add("lavfi");
    cmd.add("-i"); cmd.add("testsrc2=size=1920x1080:rate=60:duration=" + seconds);
    cmd.add("-vf"); cmd.add(buildProbeFilter(tier, scaler));
    cmd.add("-c:v"); cmd.add("hevc_nvenc");
    cmd.add("-preset"); cmd.add("p4");
    cmd.add("-f"); cmd.add("null");
    cmd.add("-");

    long startedAt = Sage.time();
    long peakVram = 0L;
    int frames = 0;
    Process p = null;
    try
    {
      p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
      BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
      try
      {
        String line;
        while ((line = r.readLine()) != null)
        {
          int f = parseFrameCount(line);
          if (f > frames) frames = f;
          long used = sampleOwnVram();
          if (used > peakVram) peakVram = used;
        }
      }
      finally { try { r.close(); } catch (IOException ie) {} }
      if (!p.waitFor(seconds * 10L + 30000L, TimeUnit.MILLISECONDS))
      {
        p.destroyForcibly();
        return null;
      }
      if (p.exitValue() != 0) return null;
    }
    catch (Throwable t)
    {
      if (Sage.DBG) System.out.println("CapacityCalibrator: probe for " + tier.token()
          + " failed: " + t);
      return null;
    }
    finally
    {
      if (p != null && p.isAlive()) p.destroyForcibly();
    }

    long elapsed = Math.max(1L, Sage.time() - startedAt);
    // Fall back to the nominal frame count when ffmpeg's progress lines weren't
    // parseable; the wall-clock measurement is still meaningful.
    if (frames <= 0) frames = seconds * 60;
    double fps = (frames * 1000.0) / elapsed;
    long vram = (peakVram > 0) ? peakVram : defaultVram(tier);
    return new TierCapacity(tier, fps, vram, true);
  }

  /** Filter chain matching what the real pipeline would build for this tier. */
  private static String buildProbeFilter(EnhancementTier tier, String scaler)
  {
    // testsrc2 is progressive, so the probe uses a plain upload plus the real
    // scaler rather than the deinterlacer, which would correctly no-op on
    // unflagged frames and make tiers incomparable. Scaling is the dominant cost
    // and is what we are actually measuring.
    StringBuilder sb = new StringBuilder("format=nv12,hwupload_cuda");
    if (tier.isUpscaling())
    {
      EnhancementPlan probe = new EnhancementPlan(tier, false, null, scaler,
          tier.getTargetWidth(), tier.getTargetHeight(), 0, 0, "calibration");
      String chain = GpuEnhancePipeline.buildFilterChain(probe);
      if (chain != null) sb.append(',').append(chain);
    }
    return sb.toString();
  }

  /** Current total GPU memory in use, as a stand-in for this probe's cost. */
  private static long sampleOwnVram()
  {
    GpuSnapshot s = GpuMonitor.getInstance().getSnapshot(Sage.getInt("playback/gpu_enhance/gpu_index", 0));
    return s.isKnown() ? s.getMemUsedMB() : 0L;
  }

  /** Pull the frame counter out of an ffmpeg progress line. */
  private static int parseFrameCount(String line)
  {
    if (line == null) return -1;
    int i = line.indexOf("frame=");
    if (i < 0) return -1;
    int j = i + "frame=".length();
    while (j < line.length() && line.charAt(j) == ' ') j++;
    int k = j;
    while (k < line.length() && Character.isDigit(line.charAt(k))) k++;
    if (k == j) return -1;
    try { return Integer.parseInt(line.substring(j, k)); }
    catch (Throwable t) { return -1; }
  }

  private void persist(TierCapacity c)
  {
    try
    {
      Sage.put(PROP_PREFIX + c.getTier().token() + "_fps", String.valueOf(c.getAchievedFps()));
      Sage.put(PROP_PREFIX + c.getTier().token() + "_vram_mb", String.valueOf(c.getVramMB()));
    }
    catch (Throwable t) { /* persistence is a convenience, not a requirement */ }
  }

  /** Reload previously measured values so a restart doesn't force a re-probe. */
  private void loadCached()
  {
    for (EnhancementTier t : EnhancementTier.values())
    {
      if (!t.isActive()) continue;
      double fps = parseDouble(Sage.get(PROP_PREFIX + t.token() + "_fps", ""), -1);
      long vram = Sage.getLong(PROP_PREFIX + t.token() + "_vram_mb", -1L);
      if (fps > 0 && vram > 0)
        results.put(t, new TierCapacity(t, fps, vram, true));
    }
  }

  /** Discard measurements so the next {@link #calibrate()} starts clean. */
  public synchronized void clear()
  {
    results.clear();
  }

  private static double parseDouble(String v, double dflt)
  {
    if (v == null || v.trim().length() == 0) return dflt;
    try { return Double.parseDouble(v.trim()); }
    catch (Throwable t) { return dflt; }
  }
}
