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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import sage.Sage;

/**
 * Reads GPU load by shelling out to {@code nvidia-smi}, with a short result cache.
 *
 * <p><b>Why a subprocess and not a CUDA/NVML binding.</b> This is a deliberate
 * design constraint, not an expedient. Invariant 1 of this feature is that SageTV
 * is a guest on a shared GPU and must hold <i>zero</i> idle footprint: no CUDA
 * context, no VRAM, no NVENC/NVDEC session when nobody is watching. Linking NVML
 * or CUDA into the SageTV JVM would create a permanent context and a permanent
 * VRAM allocation in the server process itself, for the lifetime of the server.
 * A short-lived {@code nvidia-smi} child leaves nothing resident; only plain
 * numbers are cached.
 *
 * <p>Failure is always closed: if {@code nvidia-smi} is absent, times out, or
 * returns garbage, callers see {@link GpuSnapshot#UNAVAILABLE} and the governor
 * falls back to a conservative session-count cap rather than to "allow everything".
 *
 * <p>Property knobs:
 * <pre>
 *   playback/gpu_enhance/nvidia_smi_path   binary path (default "nvidia-smi" on PATH)
 *   playback/gpu_enhance/gpu_poll_ms       cache TTL, default 2000
 *   playback/gpu_enhance/probe_timeout_ms  subprocess timeout, default 4000
 * </pre>
 */
public final class GpuMonitor
{
  private static final String PROP_SMI_PATH     = "playback/gpu_enhance/nvidia_smi_path";
  private static final String PROP_POLL_MS      = "playback/gpu_enhance/gpu_poll_ms";
  private static final String PROP_TIMEOUT_MS   = "playback/gpu_enhance/probe_timeout_ms";

  private static final String DEFAULT_SMI_PATH  = "nvidia-smi";
  private static final long   DEFAULT_POLL_MS   = 2000L;
  private static final long   DEFAULT_TIMEOUT   = 4000L;

  private static final String QUERY_FIELDS =
      "index,utilization.gpu,utilization.encoder,utilization.decoder,memory.used,memory.total";

  private static final GpuMonitor instance = new GpuMonitor();

  public static GpuMonitor getInstance() { return instance; }

  private final Object lock = new Object();
  private List<GpuSnapshot> cached = Collections.emptyList();
  private long cachedAt = 0L;
  /** Set once the binary has proven missing, so we stop paying for the failure. */
  private volatile boolean smiUnusable = false;
  private volatile boolean loggedUnusable = false;

  private GpuMonitor() { }

  /** True if {@code nvidia-smi} has been found to work at least once. */
  public boolean isAvailable()
  {
    if (smiUnusable) return false;
    return !sample().isEmpty();
  }

  /**
   * Snapshot for a specific GPU index, or {@link GpuSnapshot#UNAVAILABLE} if that
   * index isn't present or {@code nvidia-smi} is unusable.
   */
  public GpuSnapshot getSnapshot(int gpuIndex)
  {
    for (GpuSnapshot s : sample())
      if (s.getIndex() == gpuIndex) return s;
    return GpuSnapshot.UNAVAILABLE;
  }

  /** Snapshots for every visible GPU. Empty when unavailable. */
  public List<GpuSnapshot> getSnapshots() { return sample(); }

  /** Discard the cache so the next read re-runs {@code nvidia-smi}. */
  public void invalidate()
  {
    synchronized (lock) { cachedAt = 0L; }
  }

  /**
   * Clear the "binary is missing" latch. Exposed for the admin
   * re-run-calibration action, so a host that gains a driver mid-life doesn't
   * need a server restart.
   */
  public void reset()
  {
    smiUnusable = false;
    loggedUnusable = false;
    invalidate();
  }

  private List<GpuSnapshot> sample()
  {
    long ttl = Sage.getLong(PROP_POLL_MS, DEFAULT_POLL_MS);
    long now = Sage.time();
    synchronized (lock)
    {
      if (cachedAt > 0 && (now - cachedAt) < ttl) return cached;
    }
    List<GpuSnapshot> fresh = smiUnusable ? Collections.<GpuSnapshot>emptyList() : runQuery();
    synchronized (lock)
    {
      cached = fresh;
      cachedAt = Sage.time();
      return cached;
    }
  }

  private List<GpuSnapshot> runQuery()
  {
    List<GpuSnapshot> out = new ArrayList<GpuSnapshot>();
    String bin = Sage.get(PROP_SMI_PATH, DEFAULT_SMI_PATH);
    Process p = null;
    try
    {
      p = new ProcessBuilder(bin,
              "--query-gpu=" + QUERY_FIELDS,
              "--format=csv,noheader,nounits")
          .redirectErrorStream(true).start();
      BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
      try
      {
        String line;
        long now = Sage.time();
        while ((line = r.readLine()) != null)
        {
          GpuSnapshot s = parseLine(line, now);
          if (s != null) out.add(s);
        }
      }
      finally { try { r.close(); } catch (IOException ie) {} }

      long timeout = Sage.getLong(PROP_TIMEOUT_MS, DEFAULT_TIMEOUT);
      if (!p.waitFor(timeout, TimeUnit.MILLISECONDS))
      {
        p.destroyForcibly();
        if (Sage.DBG) System.out.println("GpuMonitor: " + bin + " timed out after " + timeout + "ms");
        return Collections.emptyList();
      }
      if (p.exitValue() != 0)
      {
        if (Sage.DBG) System.out.println("GpuMonitor: " + bin + " exited " + p.exitValue());
        return Collections.emptyList();
      }
    }
    catch (Throwable t)
    {
      // Most commonly IOException "cannot run program" on a host with no NVIDIA
      // driver. Latch it so we don't fork a doomed process every two seconds.
      smiUnusable = true;
      if (!loggedUnusable)
      {
        loggedUnusable = true;
        if (Sage.DBG)
          System.out.println("GpuMonitor: " + bin + " unusable (" + t
              + "); GPU enhancement admission will fall back to a conservative session cap");
      }
      return Collections.emptyList();
    }
    finally
    {
      if (p != null && p.isAlive()) p.destroyForcibly();
    }
    return Collections.unmodifiableList(out);
  }

  /** Parse one {@code csv,noheader,nounits} row. Returns null if unparseable. */
  private static GpuSnapshot parseLine(String line, long now)
  {
    if (line == null) return null;
    String s = line.trim();
    if (s.length() == 0) return null;
    String[] f = s.split("\\s*,\\s*");
    if (f.length < 6) return null;
    int idx = parseIntSafe(f[0]);
    if (idx < 0) return null;
    return new GpuSnapshot(idx,
        parseIntSafe(f[1]), parseIntSafe(f[2]), parseIntSafe(f[3]),
        parseLongSafe(f[4]), parseLongSafe(f[5]), now);
  }

  /**
   * {@code nvidia-smi} reports "[N/A]" (and similar) for counters a given card or
   * driver doesn't expose — notably the encoder/decoder utilization figures on
   * some consumer parts. Those must read as UNKNOWN, not as zero, because zero
   * would look like idle headroom and wrongly admit a session.
   */
  private static int parseIntSafe(String v)
  {
    try { return Integer.parseInt(v.trim()); }
    catch (Throwable t) { return GpuSnapshot.UNKNOWN; }
  }

  private static long parseLongSafe(String v)
  {
    try { return Long.parseLong(v.trim()); }
    catch (Throwable t) { return GpuSnapshot.UNKNOWN; }
  }
}
