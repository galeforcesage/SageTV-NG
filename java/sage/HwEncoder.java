/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package sage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Hardware video encoder abstraction. Lets the rest of the code request
 * "encode H.264" or "encode HEVC" without naming a specific vendor
 * (nvenc / vaapi / qsv / amf / videotoolbox).
 *
 * Detection probes a given {@code ffmpeg} binary's {@code -encoders} output
 * once per JVM and caches the result. The selected encoder is decided by the
 * preference order in {@code multimedia/hwaccel/preferred} (default
 * {@code nvenc,vaapi,qsv,amf,videotoolbox,none}); first encoder in the list
 * that is both available AND supports the requested target codec wins.
 *
 * Property knobs:
 *   multimedia/hwaccel/preferred       comma list (default above)
 *   multimedia/hwaccel/vaapi_device    /dev/dri/renderD128
 *   multimedia/hwaccel/probe_ffmpeg    /usr/local/bin/ffmpeg-ac4
 *   multimedia/hwaccel/enhance_runtime_probe  true (functionally verify NVENC)
 *
 * NOTE: This class does NOT shell out at class-load time — the probe runs
 * lazily on first call to {@link #detect(String)} or {@link #pick(String)}.
 * This keeps Sage startup unaffected when no transcode is ever requested.
 */
public final class HwEncoder
{
  public enum Kind
  {
    /** NVIDIA NVENC (Pascal+ for HEVC, Maxwell+ for H.264). */
    NVENC,
    /** Linux VAAPI (covers AMD AMDGPU/RADV and Intel iHD/i965). */
    VAAPI,
    /** Intel Quick Sync Video. */
    QSV,
    /** AMD AMF (Windows-only as a practical matter). */
    AMF,
    /** Apple VideoToolbox (macOS only). */
    VIDEOTOOLBOX,
    /** Software fallback sentinel — not a HW encoder. */
    NONE;

    /** Lowercase token used in property values (preferred list, etc). */
    public String token() { return name().toLowerCase(Locale.ROOT); }

    public static Kind fromToken(String t)
    {
      if (t == null) return null;
      String s = t.trim().toLowerCase(Locale.ROOT);
      if (s.length() == 0) return null;
      try { return Kind.valueOf(s.toUpperCase(Locale.ROOT)); }
      catch (IllegalArgumentException ex) { return null; }
    }
  }

  private static final String PROP_PREFERRED      = "multimedia/hwaccel/preferred";
  private static final String DEFAULT_PREFERRED   = "nvenc,vaapi,qsv,amf,videotoolbox,none";
  private static final String PROP_VAAPI_DEVICE   = "multimedia/hwaccel/vaapi_device";
  private static final String DEFAULT_VAAPI_DEV   = "/dev/dri/renderD128";
  private static final String PROP_PROBE_FFMPEG   = "multimedia/hwaccel/probe_ffmpeg";
  private static final String DEFAULT_PROBE_FF    = "/opt/sagetv/server/ffmpeg";
  private static final String PROP_ENHANCE_RUNTIME_PROBE =
      "multimedia/hwaccel/enhance_runtime_probe";
  /** Cap on the functional probe so a wedged ffmpeg can't stall the caller. */
  private static final int PROBE_TIMEOUT_SECS = 20;
  // Synthetic probe source geometry. Small enough to be instant, large enough
  // that the scaler and NVENC both do real work.
  private static final int PROBE_W = 320;
  private static final int PROBE_H = 180;
  private static final int PROBE_FRAMES = 6;

  /** Cache: ffmpeg binary path -> Set of available encoder kinds (excluding NONE). */
  private static final Map<String, Set<Kind>> probeCache = new ConcurrentHashMap<String, Set<Kind>>();

  /** Cache: ffmpeg binary path -> Set of available filter names. */
  private static final Map<String, Set<String>> filterCache = new ConcurrentHashMap<String, Set<String>>();

  /** Cache for the functional (actually-run-it) enhancement probe, per binary. */
  private static final Map<String, Boolean> runtimeCache = new ConcurrentHashMap<String, Boolean>();

  /**
   * Cache: ffmpeg binary path -> whether its {@code scale_cuda} filter exposes
   * the {@code interp_algo} option (i.e. can do Lanczos, not just bilinear).
   * Older ffmpeg builds shipped a bilinear-only {@code scale_cuda}; modern ones
   * (roughly ffmpeg 6.0+) added algorithm selection, which is what lets us prefer
   * the actively-maintained native CUDA scaler over the deprecated NPP one
   * without a quality regression.
   */
  private static final Map<String, Boolean> scaleCudaInterpCache = new ConcurrentHashMap<String, Boolean>();

  /**
   * Binaries we have already explained the "enhancement unavailable" verdict
   * for, so the reason is stated once rather than on every admission check.
   */
  private static final Set<String> unsupportedReasonLogged =
      java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

  /**
   * Filter names the GPU enhancement pipeline cares about. Probing is limited
   * to this list so the cache stays small and the intent stays obvious.
   *
   * <p>{@code scale_npp} is NOT guaranteed present: it requires an ffmpeg built
   * with {@code --enable-libnpp}, and NVIDIA advises against libnpp on CUDA
   * releases after 12.8. {@code scale_cuda} is the fallback. Callers must treat
   * the scaler as an abstraction and never assume a specific one exists.
   */
  private static final String[] INTERESTING_FILTERS = {
    "yadif_cuda", "bwdif_cuda", "scale_npp", "scale_cuda", "yadif", "bwdif",
  };

  /** Encoder name table: Kind -> (codec -> ffmpeg encoder name). */
  private static final Map<Kind, Map<String, String>> ENC_NAMES = buildEncNames();

  private static Map<Kind, Map<String, String>> buildEncNames()
  {
    Map<Kind, Map<String, String>> m = new EnumMap<Kind, Map<String, String>>(Kind.class);
    Map<String, String> nv = new HashMap<String, String>();
    nv.put("h264", "h264_nvenc"); nv.put("hevc", "hevc_nvenc");
    m.put(Kind.NVENC, nv);
    Map<String, String> va = new HashMap<String, String>();
    va.put("h264", "h264_vaapi"); va.put("hevc", "hevc_vaapi");
    m.put(Kind.VAAPI, va);
    Map<String, String> qs = new HashMap<String, String>();
    qs.put("h264", "h264_qsv");   qs.put("hevc", "hevc_qsv");
    m.put(Kind.QSV, qs);
    Map<String, String> af = new HashMap<String, String>();
    af.put("h264", "h264_amf");   af.put("hevc", "hevc_amf");
    m.put(Kind.AMF, af);
    Map<String, String> vt = new HashMap<String, String>();
    vt.put("h264", "h264_videotoolbox"); vt.put("hevc", "hevc_videotoolbox");
    m.put(Kind.VIDEOTOOLBOX, vt);
    Map<String, String> sw = new HashMap<String, String>();
    sw.put("h264", "libx264"); sw.put("hevc", "libx265");
    m.put(Kind.NONE, sw);
    return Collections.unmodifiableMap(m);
  }

  private HwEncoder() { }

  /** Normalize a codec hint to {@code "h264"} or {@code "hevc"} (lowercase). */
  public static String normalizeCodec(String codec)
  {
    if (codec == null) return "h264";
    String c = codec.trim().toLowerCase(Locale.ROOT);
    if (c.length() == 0) return "h264";
    if (c.equals("h.264") || c.equals("avc")) return "h264";
    if (c.equals("h.265") || c.equals("h265")) return "hevc";
    if (c.equals("hevc_nvenc") || c.equals("hevc_vaapi") || c.equals("hevc_qsv")
        || c.equals("hevc_amf") || c.equals("hevc_videotoolbox") || c.equals("libx265"))
      return "hevc";
    if (c.startsWith("h264") || c.startsWith("libx264")) return "h264";
    if (c.startsWith("hevc")) return "hevc";
    return c;
  }

  /** Encoder name for a kind+codec, or null if unsupported. */
  public static String encoderName(Kind k, String codec)
  {
    if (k == null) return null;
    Map<String, String> m = ENC_NAMES.get(k);
    if (m == null) return null;
    return m.get(normalizeCodec(codec));
  }

  /**
   * Probe a specific {@code ffmpeg} binary for available HW encoder backends.
   * Result is cached per binary path. Returns an empty set if probe fails.
   */
  public static Set<Kind> detect(String ffmpegBin)
  {
    String key = (ffmpegBin == null || ffmpegBin.length() == 0)
        ? Sage.get(PROP_PROBE_FFMPEG, DEFAULT_PROBE_FF) : ffmpegBin;
    Set<Kind> cached = probeCache.get(key);
    if (cached != null) return cached;
    synchronized (HwEncoder.class)
    {
      cached = probeCache.get(key);
      if (cached != null) return cached;
      Set<Kind> found = EnumSet.noneOf(Kind.class);
      try
      {
        Process p = new ProcessBuilder(key, "-hide_banner", "-encoders")
            .redirectErrorStream(true).start();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        try
        {
          String line;
          while ((line = r.readLine()) != null)
          {
            // Lines look like "  V..... h264_nvenc          NVIDIA NVENC H.264 ..."
            // Match by encoder-name substring in any column.
            if (line.contains("h264_nvenc") || line.contains("hevc_nvenc")) found.add(Kind.NVENC);
            else if (line.contains("h264_vaapi") || line.contains("hevc_vaapi")) found.add(Kind.VAAPI);
            else if (line.contains("h264_qsv")   || line.contains("hevc_qsv"))   found.add(Kind.QSV);
            else if (line.contains("h264_amf")   || line.contains("hevc_amf"))   found.add(Kind.AMF);
            else if (line.contains("h264_videotoolbox") || line.contains("hevc_videotoolbox"))
              found.add(Kind.VIDEOTOOLBOX);
          }
        }
        finally { try { r.close(); } catch (IOException ie) {} }
        try { while (p.getInputStream().read() >= 0); } catch (IOException ie) {}
        try { p.waitFor(); } catch (InterruptedException ie) { p.destroy(); }
      }
      catch (Throwable t)
      {
        if (Sage.DBG) System.out.println("HwEncoder: probe of " + key + " failed: " + t);
      }
      Set<Kind> immut = Collections.unmodifiableSet(found);
      probeCache.put(key, immut);
      if (Sage.DBG)
        System.out.println("HwEncoder: probe " + key + " -> " + immut);
      return immut;
    }
  }

  /**
   * Probe a specific {@code ffmpeg} binary for the availability of the filters
   * in {@link #INTERESTING_FILTERS}. Result is cached per binary path, exactly
   * like {@link #detect(String)}. Returns an empty set if the probe fails,
   * which reads as "no GPU filters" and disables enhancement — fail closed.
   */
  public static Set<String> detectFilters(String ffmpegBin)
  {
    String key = (ffmpegBin == null || ffmpegBin.length() == 0)
        ? Sage.get(PROP_PROBE_FFMPEG, DEFAULT_PROBE_FF) : ffmpegBin;
    Set<String> cached = filterCache.get(key);
    if (cached != null) return cached;
    synchronized (HwEncoder.class)
    {
      cached = filterCache.get(key);
      if (cached != null) return cached;
      Set<String> found = new HashSet<String>();
      try
      {
        Process p = new ProcessBuilder(key, "-hide_banner", "-filters")
            .redirectErrorStream(true).start();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        try
        {
          String line;
          while ((line = r.readLine()) != null)
          {
            // Lines look like " ... yadif_cuda        V->V       Deinterlace CUDA frames"
            // Match on a whitespace-delimited token so "yadif" never matches
            // inside "yadif_cuda".
            for (String f : INTERESTING_FILTERS)
            {
              if (found.contains(f)) continue;
              if (containsToken(line, f)) found.add(f);
            }
          }
        }
        finally { try { r.close(); } catch (IOException ie) {} }
        try { while (p.getInputStream().read() >= 0); } catch (IOException ie) {}
        try { p.waitFor(); } catch (InterruptedException ie) { p.destroy(); }
      }
      catch (Throwable t)
      {
        if (Sage.DBG) System.out.println("HwEncoder: filter probe of " + key + " failed: " + t);
      }
      Set<String> immut = Collections.unmodifiableSet(found);
      filterCache.put(key, immut);
      if (Sage.DBG)
        System.out.println("HwEncoder: filter probe " + key + " -> " + immut);
      return immut;
    }
  }

  /** True if {@code line} contains {@code tok} as a whitespace-delimited token. */
  private static boolean containsToken(String line, String tok)
  {
    int from = 0;
    while (true)
    {
      int i = line.indexOf(tok, from);
      if (i < 0) return false;
      int end = i + tok.length();
      boolean leftOk  = (i == 0) || Character.isWhitespace(line.charAt(i - 1));
      boolean rightOk = (end >= line.length()) || Character.isWhitespace(line.charAt(end));
      if (leftOk && rightOk) return true;
      from = i + 1;
    }
  }

  /** Convenience: probe the default ffmpeg binary for filter availability. */
  public static Set<String> detectFilters()
  {
    return detectFilters(Sage.get(PROP_PROBE_FFMPEG, DEFAULT_PROBE_FF));
  }

  /** True if the default ffmpeg binary exposes the named filter. */
  public static boolean hasFilter(String name)
  {
    return name != null && detectFilters().contains(name);
  }

  /**
   * Pick the CUDA scaler filter name. Prefers {@code scale_cuda} when this
   * ffmpeg's {@code scale_cuda} supports Lanczos ({@code interp_algo}): it is the
   * actively-maintained native CUDA kernel, whereas {@code scale_npp} depends on
   * the NPP library NVIDIA is deprecating on CUDA past 12.8 (ffmpeg prints a
   * "libnpp based filters are deprecated" warning for it). When {@code scale_cuda}
   * is bilinear-only on this build, {@code scale_npp} is preferred instead so we
   * never trade Lanczos quality for the newer filter; bare {@code scale_cuda} is
   * the last resort. Returns null when neither exists, which removes every upscale
   * tier from the ladder.
   *
   * <p>An operator can pin the choice with {@code playback/gpu_enhance/scaler}
   * ({@code scale_cuda} / {@code scale_npp}); an unavailable pin is ignored.
   */
  public static String cudaScaler()
  {
    Set<String> f = detectFilters();
    String pin = Sage.get("playback/gpu_enhance/scaler", "").trim();
    if (pin.length() > 0 && f.contains(pin)) return pin;

    boolean npp = f.contains("scale_npp");
    boolean cuda = f.contains("scale_cuda");
    if (cuda && scaleCudaSupportsInterpAlgo()) return "scale_cuda";
    if (npp) return "scale_npp";
    if (cuda) return "scale_cuda";
    return null;
  }

  /** True if this scaler renders Lanczos on the default binary (so a
   *  {@code :interp_algo=lanczos} suffix is valid). {@code scale_npp} always does;
   *  {@code scale_cuda} only on builds whose filter exposes {@code interp_algo}. */
  public static boolean scalerSupportsLanczos(String scaler)
  {
    if ("scale_npp".equals(scaler)) return true;
    if ("scale_cuda".equals(scaler)) return scaleCudaSupportsInterpAlgo();
    return false;
  }

  /** True if the default ffmpeg's {@code scale_cuda} exposes {@code interp_algo}. */
  public static boolean scaleCudaSupportsInterpAlgo()
  {
    return scaleCudaSupportsInterpAlgo(Sage.get(PROP_PROBE_FFMPEG, DEFAULT_PROBE_FF));
  }

  /**
   * Probe {@code ffmpeg -h filter=scale_cuda} for the {@code interp_algo} option,
   * cached per binary like {@link #detectFilters(String)}. Fail-closed: a failed
   * probe reads as "no Lanczos", so we keep {@code scale_npp}'s known-good quality.
   */
  public static boolean scaleCudaSupportsInterpAlgo(String ffmpegBin)
  {
    // Operator/test override: force the capability without probing. "auto"
    // (default) probes the binary. Lets a deployment pin behavior and makes the
    // decision unit-testable without a real ffmpeg present.
    String ov = Sage.get("playback/gpu_enhance/scale_cuda_lanczos", "auto").trim().toLowerCase(Locale.ROOT);
    if (ov.equals("true") || ov.equals("1") || ov.equals("yes") || ov.equals("on")) return true;
    if (ov.equals("false") || ov.equals("0") || ov.equals("no") || ov.equals("off")) return false;

    String key = (ffmpegBin == null || ffmpegBin.length() == 0)
        ? Sage.get(PROP_PROBE_FFMPEG, DEFAULT_PROBE_FF) : ffmpegBin;
    Boolean cached = scaleCudaInterpCache.get(key);
    if (cached != null) return cached.booleanValue();
    boolean found = false;
    try
    {
      Process p = new ProcessBuilder(key, "-hide_banner", "-h", "filter=scale_cuda")
          .redirectErrorStream(true).start();
      BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
      try
      {
        String line;
        while ((line = r.readLine()) != null)
        {
          if (line.indexOf("interp_algo") >= 0) { found = true; break; }
        }
      }
      finally { try { r.close(); } catch (IOException ie) {} }
      try { while (p.getInputStream().read() >= 0); } catch (IOException ie) {}
      try { p.waitFor(); } catch (InterruptedException ie) { p.destroy(); }
    }
    catch (Throwable t)
    {
      if (Sage.DBG) System.out.println("HwEncoder: scale_cuda interp_algo probe of "
          + key + " failed: " + t);
    }
    scaleCudaInterpCache.put(key, Boolean.valueOf(found));
    if (Sage.DBG)
      System.out.println("HwEncoder: scale_cuda interp_algo on " + key + " -> " + found);
    return found;
  }

  /**
   * Pick the CUDA deinterlacer. {@code bwdif_cuda} is higher quality and is
   * used only when explicitly requested via {@code preferBwdif}; otherwise
   * {@code yadif_cuda} is the default. Returns null when neither exists.
   */
  public static String cudaDeinterlacer(boolean preferBwdif)
  {
    Set<String> f = detectFilters();
    if (preferBwdif && f.contains("bwdif_cuda")) return "bwdif_cuda";
    if (f.contains("yadif_cuda")) return "yadif_cuda";
    if (f.contains("bwdif_cuda")) return "bwdif_cuda";
    return null;
  }

  /**
   * Functionally verify the full-GPU enhancement pipeline by actually running a
   * tiny encode, rather than trusting what {@code -encoders} advertises.
   *
   * This exists because the listing probes lie. An ffmpeg build compiled against
   * a newer NVENC SDK than the installed driver supports will happily list
   * {@code hevc_nvenc} in {@code -encoders} and then fail at
   * {@code avcodec_open2} with "Driver does not support the required nvenc API
   * version" — observed with ffmpeg 8.1.2 (NVENC API 13.1) on driver 577.13
   * (API 13.0). Without this check, enhancement would be admitted on such a host
   * and then every enhanced session would die at stream start, which is exactly
   * the runtime surprise the design forbids.
   *
   * The probe encodes a fraction of a second of synthetic color through a real
   * CUDA device and the real scaler, so it also catches a missing CUDA device,
   * a broken driver/library install, and a scaler that lists but can't
   * initialize. Result is cached per binary; a failure fails closed.
   *
   * Set {@code multimedia/hwaccel/enhance_runtime_probe=false} to skip it and
   * trust the listing probes instead (not recommended).
   */
  /**
   * Build the functional GPU-enhancement probe command.
   *
   * The synthetic source is raw frames on stdin rather than the lavfi input
   * device. SageTV ships a custom ffmpeg built without libavdevice: its
   * `-devices` list is empty and `-f lavfi` fails with "Unknown input format:
   * 'lavfi'". A lavfi-based probe therefore reports "no GPU support" on
   * exactly the hosts where the pipeline actually works, silently disabling
   * enhancement. rawvideo over a pipe requires no input device and behaves
   * identically across builds and platforms.
   *
   * Package-private so the shape of the command can be asserted in tests
   * without needing a real ffmpeg or a GPU.
   */
  static List<String> buildRuntimeProbeCommand(String bin, String scaler, String hevc)
  {
    List<String> cmd = new ArrayList<String>();
    cmd.add(bin);
    cmd.add("-hide_banner");
    cmd.add("-loglevel"); cmd.add("error");
    cmd.add("-init_hw_device"); cmd.add("cuda=cu:0");
    cmd.add("-filter_hw_device"); cmd.add("cu");
    cmd.add("-f"); cmd.add("rawvideo");
    cmd.add("-pix_fmt"); cmd.add("yuv420p");
    cmd.add("-s"); cmd.add(PROBE_W + "x" + PROBE_H);
    cmd.add("-r"); cmd.add("30");
    cmd.add("-i"); cmd.add("-");
    cmd.add("-vf"); cmd.add("hwupload_cuda," + scaler + "=" + (PROBE_W * 2) + ":" + (PROBE_H * 2));
    cmd.add("-c:v"); cmd.add(hevc);
    cmd.add("-f"); cmd.add("null");
    cmd.add("-");
    return cmd;
  }

  /** Bytes of yuv420p payload the probe feeder writes per frame. */
  static int probeFrameBytes() { return PROBE_W * PROBE_H * 3 / 2; }

  public static boolean gpuEnhanceRuntimeOk(String ffmpegBin)
  {    String key = (ffmpegBin == null || ffmpegBin.length() == 0)
        ? Sage.get(PROP_PROBE_FFMPEG, DEFAULT_PROBE_FF) : ffmpegBin;
    if (!Sage.getBoolean(PROP_ENHANCE_RUNTIME_PROBE, true)) return true;
    Boolean cached = runtimeCache.get(key);
    if (cached != null) return cached.booleanValue();
    synchronized (HwEncoder.class)
    {
      cached = runtimeCache.get(key);
      if (cached != null) return cached.booleanValue();

      boolean ok = false;
      String scaler = cudaScaler();
      String hevc = encoderName(Kind.NVENC, "hevc");
      if (scaler == null || hevc == null)
      {
        if (Sage.DBG)
          System.out.println("HwEncoder: GPU enhance runtime probe skipped for " + key
              + " -- " + (scaler == null ? "no CUDA scaler" : "no NVENC HEVC encoder"));
        runtimeCache.put(key, Boolean.FALSE);
        return false;
      }
      Process p = null;
      try
      {
        List<String> cmd = buildRuntimeProbeCommand(key, scaler, hevc);
        p = new ProcessBuilder(cmd).redirectErrorStream(true).start();

        // Feed the frames on a separate thread: writing inline would deadlock
        // against our own draining of the merged stdout/stderr stream.
        final Process fp = p;
        Thread feeder = new Thread(new Runnable()
        {
          public void run()
          {
            java.io.OutputStream os = fp.getOutputStream();
            try
            {
              byte[] frame = new byte[PROBE_W * PROBE_H * 3 / 2];
              for (int i = 0; i < PROBE_FRAMES; i++) os.write(frame);
              os.flush();
            }
            catch (IOException ioe) { /* ffmpeg exited early; exit code tells us */ }
            finally { try { os.close(); } catch (IOException ioe) {} }
          }
        }, "HwEncoderProbeFeeder");
        feeder.setDaemon(true);
        feeder.start();

        StringBuilder err = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        try
        {
          String line;
          while ((line = r.readLine()) != null)
            if (err.length() < 2000) err.append(line).append('\n');
        }
        finally { try { r.close(); } catch (IOException ie) {} }

        if (p.waitFor(PROBE_TIMEOUT_SECS, TimeUnit.SECONDS))
          ok = (p.exitValue() == 0);
        else
          p.destroyForcibly();

        if (!ok && Sage.DBG)
          System.out.println("HwEncoder: GPU enhance runtime probe FAILED for " + key
              + " -- enhancement disabled on this host. ffmpeg said:\n" + err);
      }
      catch (Throwable t)
      {
        if (Sage.DBG)
          System.out.println("HwEncoder: GPU enhance runtime probe of " + key + " errored: " + t);
      }
      finally
      {
        if (p != null && p.isAlive()) p.destroyForcibly();
      }

      runtimeCache.put(key, Boolean.valueOf(ok));
      if (Sage.DBG && ok)
        System.out.println("HwEncoder: GPU enhance runtime probe OK for " + key
            + " (scaler=" + scaler + ", encoder=" + hevc + ")");
      return ok;
    }
  }

  /**
   * True if this ffmpeg binary can run the full-GPU enhancement pipeline:
   * a CUDA deinterlacer, a CUDA scaler, and an NVENC HEVC encoder that actually
   * opens. Any missing element disables enhancement entirely rather than
   * producing a broken ffmpeg invocation at stream time.
   */
  public static boolean gpuEnhanceSupported()
  {
    String bin = Sage.get(PROP_PROBE_FFMPEG, DEFAULT_PROBE_FF);
    String missing = null;
    if (cudaScaler() == null)
      missing = "no CUDA scaler (need scale_npp or scale_cuda)";
    else if (cudaDeinterlacer(false) == null)
      missing = "no CUDA deinterlacer (need yadif_cuda or bwdif_cuda)";
    else if (!detect(bin).contains(Kind.NVENC))
      missing = "ffmpeg reports no NVENC support";
    else if (encoderName(Kind.NVENC, "hevc") == null)
      missing = "no NVENC HEVC encoder";

    if (missing != null)
    {
      // Say why, once per binary. A silent false here is indistinguishable
      // from "enhancement was never asked for", which is how a broken probe
      // can disable the whole feature indefinitely without anyone noticing.
      if (unsupportedReasonLogged.add(bin))
        System.out.println("HwEncoder: GPU enhancement unavailable for " + bin + " -- " + missing);
      return false;
    }
    return gpuEnhanceRuntimeOk(bin);
  }

  /** Test hook: forget cached probe results so a probe can be re-run. */
  static void clearProbeCaches()
  {
    probeCache.clear();
    filterCache.clear();
    runtimeCache.clear();
    scaleCudaInterpCache.clear();
    unsupportedReasonLogged.clear();
  }

  /**
   * Choose the best available HW encoder for a target codec, honoring
   * {@code multimedia/hwaccel/preferred} order. Returns {@link Kind#NONE} if
   * no HW encoder is available for that codec — caller should fall back to
   * software (libx264/libx265) or pass-through.
   */
  public static Kind pick(String targetCodec)
  {
    return pick(targetCodec, Sage.get(PROP_PROBE_FFMPEG, DEFAULT_PROBE_FF));
  }

  public static Kind pick(String targetCodec, String ffmpegBin)
  {
    String codec = normalizeCodec(targetCodec);
    Set<Kind> avail = detect(ffmpegBin);
    String pref = Sage.get(PROP_PREFERRED, DEFAULT_PREFERRED);
    String[] toks = pref.split("\\s*,\\s*");
    for (String tok : toks)
    {
      Kind k = Kind.fromToken(tok);
      if (k == null) continue;
      if (k == Kind.NONE) return Kind.NONE; // explicit software request
      if (avail.contains(k) && encoderName(k, codec) != null) return k;
    }
    return Kind.NONE;
  }

  /** True if any HW encoder for {@code targetCodec} is available. */
  public static boolean availableFor(String targetCodec)
  {
    Kind k = pick(targetCodec);
    return k != null && k != Kind.NONE;
  }

  /** True if any HW encoder for any codec is available (probe-once view). */
  public static boolean anyAvailable()
  {
    return availableFor("h264") || availableFor("hevc");
  }

  /**
   * Translate a generic preset hint (NVENC-style {@code p1..p7} or one of
   * {@code fast|medium|slow|quality|speed|balanced}) into an encoder-specific
   * preset string. Returns null if the encoder ignores preset (vaapi).
   */
  public static String preset(Kind k, String hint)
  {
    if (k == null || hint == null || hint.length() == 0) return null;
    String h = hint.trim().toLowerCase(Locale.ROOT);
    switch (k)
    {
      case NVENC:
        // Pass-through if already a p-preset; map common words.
        if (h.matches("p[1-7]")) return h;
        if (h.equals("fast") || h.equals("speed")) return "p2";
        if (h.equals("medium") || h.equals("balanced")) return "p4";
        if (h.equals("slow") || h.equals("quality")) return "p6";
        return "p4";
      case QSV:
        if (h.equals("fast") || h.equals("speed") || h.matches("p[1-2]")) return "veryfast";
        if (h.equals("slow") || h.equals("quality") || h.matches("p[6-7]")) return "slower";
        return "medium";
      case AMF:
        if (h.equals("fast") || h.equals("speed") || h.matches("p[1-2]")) return "speed";
        if (h.equals("slow") || h.equals("quality") || h.matches("p[6-7]")) return "quality";
        return "balanced";
      case VIDEOTOOLBOX:
        // VideoToolbox doesn't expose presets via -preset; ignore.
        return null;
      case VAAPI:
        // VAAPI uses -compression_level (1-7, lower = faster). Convert.
        if (h.matches("p[1-7]")) return String.valueOf(8 - Integer.parseInt(h.substring(1)));
        if (h.equals("fast") || h.equals("speed")) return "1";
        if (h.equals("slow") || h.equals("quality")) return "7";
        return "4";
      case NONE:
        // libx264/libx265 -preset
        if (h.matches("p[1-2]")) return "veryfast";
        if (h.matches("p[3-4]")) return "medium";
        if (h.matches("p[5-7]")) return "slow";
        return h;
      default:
        return null;
    }
  }

  /** Preset CLI flag for the encoder ({@code -preset} or {@code -compression_level}). */
  public static String presetFlag(Kind k)
  {
    return (k == Kind.VAAPI) ? "-compression_level" : "-preset";
  }

  /**
   * Build the global ffmpeg arg list that must precede {@code -i} (e.g.
   * VAAPI device init). May be empty.
   */
  public static List<String> globalArgs(Kind k)
  {
    List<String> out = new ArrayList<String>();
    if (k == Kind.VAAPI)
    {
      String dev = Sage.get(PROP_VAAPI_DEVICE, DEFAULT_VAAPI_DEV);
      out.add("-vaapi_device"); out.add(dev);
    }
    return out;
  }

  /**
   * Build the {@code -vf} filter-graph string appropriate for the encoder's
   * required pixel format upload. {@code basePixfmt} is the SW pixel format
   * the source should be converted to first (typically {@code yuv420p}).
   * Includes upload step for VAAPI / QSV.
   */
  public static String videoFilter(Kind k, String basePixfmt, String extraFilters)
  {
    if (basePixfmt == null || basePixfmt.length() == 0) basePixfmt = "yuv420p";
    StringBuilder sb = new StringBuilder();
    if (extraFilters != null && extraFilters.length() > 0)
    {
      sb.append(extraFilters);
      if (!extraFilters.endsWith(",")) sb.append(',');
    }
    if (k == Kind.VAAPI)
    {
      sb.append("format=nv12|vaapi,hwupload");
    }
    else if (k == Kind.QSV)
    {
      sb.append("format=nv12,hwupload=extra_hw_frames=8");
    }
    else
    {
      // NVENC / AMF / VideoToolbox / SW all happy with plain SW frames.
      sb.append("format=").append(basePixfmt);
    }
    return sb.toString();
  }
}
