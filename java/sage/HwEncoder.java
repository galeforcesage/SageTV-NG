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

  /** Cache: ffmpeg binary path -> Set of available encoder kinds (excluding NONE). */
  private static final Map<String, Set<Kind>> probeCache = new ConcurrentHashMap<String, Set<Kind>>();

  /** Cache: ffmpeg binary path -> Set of available filter names. */
  private static final Map<String, Set<String>> filterCache = new ConcurrentHashMap<String, Set<String>>();

  /** Cache for the functional (actually-run-it) enhancement probe, per binary. */
  private static final Map<String, Boolean> runtimeCache = new ConcurrentHashMap<String, Boolean>();

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
   * Pick the CUDA scaler filter name, preferring {@code scale_npp} (Lanczos
   * capable, matching the existing offline {@code upscale_2160} preset) and
   * falling back to {@code scale_cuda}. Returns null when neither exists, which
   * removes every upscale tier from the ladder.
   */
  public static String cudaScaler()
  {
    Set<String> f = detectFilters();
    if (f.contains("scale_npp")) return "scale_npp";
    if (f.contains("scale_cuda")) return "scale_cuda";
    return null;
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
  public static boolean gpuEnhanceRuntimeOk(String ffmpegBin)
  {
    String key = (ffmpegBin == null || ffmpegBin.length() == 0)
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
        runtimeCache.put(key, Boolean.FALSE);
        return false;
      }
      Process p = null;
      try
      {
        List<String> cmd = new ArrayList<String>();
        cmd.add(key);
        cmd.add("-hide_banner");
        cmd.add("-loglevel"); cmd.add("error");
        cmd.add("-init_hw_device"); cmd.add("cuda=cu:0");
        cmd.add("-filter_hw_device"); cmd.add("cu");
        cmd.add("-f"); cmd.add("lavfi");
        cmd.add("-i"); cmd.add("color=c=black:s=640x360:r=30:d=0.2");
        cmd.add("-vf"); cmd.add("hwupload_cuda," + scaler + "=1280:720");
        cmd.add("-c:v"); cmd.add(hevc);
        cmd.add("-f"); cmd.add("null");
        cmd.add("-");
        p = new ProcessBuilder(cmd).redirectErrorStream(true).start();

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
    if (cudaScaler() == null) return false;
    if (cudaDeinterlacer(false) == null) return false;
    if (!detect(Sage.get(PROP_PROBE_FFMPEG, DEFAULT_PROBE_FF)).contains(Kind.NVENC)) return false;
    if (encoderName(Kind.NVENC, "hevc") == null) return false;
    return gpuEnhanceRuntimeOk(Sage.get(PROP_PROBE_FFMPEG, DEFAULT_PROBE_FF));
  }

  /** Test hook: forget cached probe results so a probe can be re-run. */
  static void clearProbeCaches()
  {
    probeCache.clear();
    filterCache.clear();
    runtimeCache.clear();
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
