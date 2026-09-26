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
 *   multimedia/hwaccel/probe_ffmpeg    /opt/sagetv/server/ffmpeg
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

  /** Cache: ffmpeg binary path -> Set of available output-format (muxer) names. */
  private static final Map<String, Set<String>> muxerCache = new ConcurrentHashMap<String, Set<String>>();

  /**
   * Cache: {@code "bin|container|codec"} -> whether a real, functional
   * stream-copy of that codec into that container verified end-to-end. This is
   * the authoritative container-copy signal; the {@code -codecs}/{@code -muxers}
   * listings are NOT (an ffmpeg build lists an {@code ac4} decoder and the
   * mpegts/mp4/matroska muxers yet cannot signal AC-4 in any of them — proven
   * empirically), which is exactly why this must actually run the copy.
   */
  private static final Map<String, Boolean> streamCopyCache = new ConcurrentHashMap<String, Boolean>();

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
    nv.put("h264", "h264_nvenc"); nv.put("hevc", "hevc_nvenc"); nv.put("av1", "av1_nvenc");
    m.put(Kind.NVENC, nv);
    Map<String, String> va = new HashMap<String, String>();
    va.put("h264", "h264_vaapi"); va.put("hevc", "hevc_vaapi"); va.put("av1", "av1_vaapi");
    m.put(Kind.VAAPI, va);
    Map<String, String> qs = new HashMap<String, String>();
    qs.put("h264", "h264_qsv");   qs.put("hevc", "hevc_qsv");   qs.put("av1", "av1_qsv");
    m.put(Kind.QSV, qs);
    Map<String, String> af = new HashMap<String, String>();
    af.put("h264", "h264_amf");   af.put("hevc", "hevc_amf");   af.put("av1", "av1_amf");
    m.put(Kind.AMF, af);
    Map<String, String> vt = new HashMap<String, String>();
    vt.put("h264", "h264_videotoolbox"); vt.put("hevc", "hevc_videotoolbox");
    m.put(Kind.VIDEOTOOLBOX, vt);
    Map<String, String> sw = new HashMap<String, String>();
    sw.put("h264", "libx264"); sw.put("hevc", "libx265"); sw.put("av1", "libsvtav1");
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
    if (c.equals("av1") || c.startsWith("av1") || c.equals("libsvtav1") || c.equals("libaom-av1"))
      return "av1";
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

  // ===========================================================================
  // ffmpeg output-format (muxer) listing + functional container stream-copy
  // self-test. Ported from the StreamingSourcePlugin's FfmpegCapabilities/
  // FfmpegCapabilityProbe, but with the plugin's core fallacy corrected: it
  // treated "has decoder for codec X" + "has muxer for container Y" as proof
  // that X can be stream-copied into Y. It cannot — the muxer must also have a
  // codec tag/signaling for X. So the LISTING is kept only as a cheap pre-gate,
  // and the authoritative answer comes from actually running the copy.
  // ===========================================================================

  /**
   * Probe a specific ffmpeg binary's output formats ("muxers") via
   * {@code -hide_banner -formats}, cached per binary path exactly like
   * {@link #detectFilters(String)}. A {@code -formats} name token may be a
   * comma-separated alias list (e.g. {@code "mov,mp4,m4a"}); each alias is
   * recorded lowercase. Returns an empty set on probe failure (reads as
   * "muxer absent" — fail closed).
   */
  public static Set<String> detectMuxers(String ffmpegBin)
  {
    String key = (ffmpegBin == null || ffmpegBin.length() == 0)
        ? Sage.get(PROP_PROBE_FFMPEG, DEFAULT_PROBE_FF) : ffmpegBin;
    Set<String> cached = muxerCache.get(key);
    if (cached != null) return cached;
    synchronized (HwEncoder.class)
    {
      cached = muxerCache.get(key);
      if (cached != null) return cached;
      java.util.ArrayList<String> lines = new java.util.ArrayList<String>();
      try
      {
        Process p = new ProcessBuilder(key, "-hide_banner", "-formats")
            .redirectErrorStream(true).start();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        try { String line; while ((line = r.readLine()) != null) lines.add(line); }
        finally { try { r.close(); } catch (IOException ie) {} }
        try { p.waitFor(); } catch (InterruptedException ie) { p.destroy(); }
      }
      catch (Throwable t)
      {
        if (Sage.DBG) System.out.println("HwEncoder: muxer probe of " + key + " failed: " + t);
      }
      Set<String> immut = Collections.unmodifiableSet(parseMuxerNames(lines));
      muxerCache.put(key, immut);
      if (Sage.DBG)
        System.out.println("HwEncoder: muxer probe " + key + " -> " + immut.size() + " formats");
      return immut;
    }
  }

  /**
   * Parse {@code ffmpeg -formats} output into the set of output-format (muxer)
   * names. Rows look like {@code " DE mpegts   MPEG-TS (MPEG-2 Transport ...)"};
   * the leading token is 1-2 chars of {@code D}/{@code E}. Only rows advertising
   * {@code E} (a muxer) are recorded — the plugin's parser wrongly accepted
   * demux-only ({@code D}) rows as muxers. A comma-separated name token is split
   * into each alias. Package-visible so tests can feed canned output.
   */
  static Set<String> parseMuxerNames(List<String> lines)
  {
    Set<String> found = new HashSet<String>();
    if (lines == null) return found;
    for (String raw : lines)
    {
      if (raw == null) continue;
      String line = raw.trim();
      if (line.length() == 0) continue;
      int sp = line.indexOf(' ');
      if (sp <= 0) continue;
      String flags = line.substring(0, sp);
      if (flags.length() > 2) continue;
      boolean okFlags = true, hasE = false;
      for (int i = 0; i < flags.length(); i++)
      {
        char c = flags.charAt(i);
        if (c != 'D' && c != 'E') { okFlags = false; break; }
        if (c == 'E') hasE = true;
      }
      if (!okFlags || !hasE) continue;
      String rest = line.substring(sp).trim();
      if (rest.length() == 0) continue;
      int nameEnd = rest.indexOf(' ');
      String namesToken = nameEnd < 0 ? rest : rest.substring(0, nameEnd);
      for (String name : namesToken.split(","))
      {
        String n = name.trim();
        if (n.length() > 0) found.add(n.toLowerCase(Locale.ROOT));
      }
    }
    return found;
  }

  /** True if the given ffmpeg binary advertises the named output format (muxer). */
  public static boolean hasMuxer(String ffmpegBin, String muxer)
  {
    return muxer != null
        && detectMuxers(ffmpegBin).contains(muxer.trim().toLowerCase(Locale.ROOT));
  }

  /** Minimum plausible size (bytes) of a ~1s real container-copy output. A
   *  header-only stub (e.g. matroska's ~293-byte EBML head written before an
   *  AC-4 mux failure) falls well below this. */
  private static final long STREAMCOPY_MIN_OUTPUT_BYTES = 4096L;
  /** Cap on the container-copy self-test so a wedged ffmpeg can't stall setup. */
  private static final int STREAMCOPY_PROBE_TIMEOUT_SECS = 20;

  /** ffmpeg stderr fragments (lowercase) that unambiguously mean "this muxer
   *  cannot carry the codec". Captured empirically across mp4/matroska/raw. */
  private static final String[] STREAMCOPY_TAG_FAILURES = {
    "could not find tag for codec",
    "no wav codec tag found",
    "could not write header",
    "codec not currently supported in container",
    "output file is empty",
  };

  /**
   * Functionally verify that {@code ffmpegBin} can stream-copy the selected
   * track of {@code source} into {@code container} and that the result reads
   * back as {@code expectedCodecName}. Runs a bounded ({@code -t 1}) real copy
   * to a temp file, then an {@code ffprobe} readback, and caches the verdict per
   * {@code binary|container|codec}. Fail-closed: any error, timeout, missing
   * muxer, tag-failure signature, empty/short output, or codec mismatch yields
   * {@code false}.
   *
   * <p>This is the container-copy authority (see {@link #streamCopyCache}). It
   * needs a real {@code source} because ffmpeg has no AC-4 encoder to synthesize
   * a probe stream; the first real AC-4 play seeds the cache, exactly like
   * {@link #gpuEnhanceRuntimeOk(String)} seeds on first use.
   */
  public static boolean streamCopyIntoContainerOk(String ffmpegBin, java.io.File source,
      String mapSelector, String container, String expectedCodecName)
  {
    return streamCopyIntoContainerOk(ffmpegBin, source, mapSelector, container,
        expectedCodecName, null, null);
  }

  /**
   * Variant of {@link #streamCopyIntoContainerOk(String, java.io.File, String,
   * String, String)} that appends {@code extraOutputArgs} to the mux command
   * (e.g. {@code -movflags +frag_keyframe+empty_moov+default_base_moof} for a
   * fragmented-MP4/CMAF probe) and keys the cache with {@code variantLabel} so a
   * plain-MP4 verdict and a fragmented-MP4 verdict are cached independently.
   */
  public static boolean streamCopyIntoContainerOk(String ffmpegBin, java.io.File source,
      String mapSelector, String container, String expectedCodecName,
      List<String> extraOutputArgs, String variantLabel)
  {
    String bin = (ffmpegBin == null || ffmpegBin.length() == 0)
        ? Sage.get(PROP_PROBE_FFMPEG, DEFAULT_PROBE_FF) : ffmpegBin;
    if (source == null || !source.isFile() || container == null || container.length() == 0)
      return false;
    String lcContainer = container.trim().toLowerCase(Locale.ROOT);
    String cacheKey = bin + "|" + lcContainer
        + (variantLabel == null ? "" : "/" + variantLabel) + "|"
        + (expectedCodecName == null ? "" : expectedCodecName.trim().toLowerCase(Locale.ROOT));
    Boolean cached = streamCopyCache.get(cacheKey);
    if (cached != null) return cached.booleanValue();
    synchronized (HwEncoder.class)
    {
      cached = streamCopyCache.get(cacheKey);
      if (cached != null) return cached.booleanValue();

      boolean ok = false;
      java.io.File tmp = null;
      try
      {
        // Cheap pre-gate: if the muxer isn't even present, skip the real copy.
        if (!detectMuxers(bin).contains(lcContainer))
        {
          if (Sage.DBG)
            System.out.println("HwEncoder: stream-copy probe skipped — no '" + lcContainer
                + "' muxer on " + bin);
          streamCopyCache.put(cacheKey, Boolean.FALSE);
          return false;
        }
        tmp = java.io.File.createTempFile("sage-streamcopyprobe-", ".tmp");
        List<String> cmd = buildStreamCopyProbeCommand(bin, source.getPath(), mapSelector,
            lcContainer, tmp.getPath(), extraOutputArgs);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        try
        {
          String line;
          while ((line = r.readLine()) != null)
            if (out.length() < 4000) out.append(line).append('\n');
        }
        finally { try { r.close(); } catch (IOException ie) {} }
        int exit = -1;
        if (p.waitFor(STREAMCOPY_PROBE_TIMEOUT_SECS, TimeUnit.SECONDS)) exit = p.exitValue();
        else p.destroyForcibly();
        long sz = tmp.length();
        String readback = null;
        if (exit == 0 && sz >= STREAMCOPY_MIN_OUTPUT_BYTES)
          readback = probeFirstAudioCodec(deriveFfprobePath(bin), tmp.getPath());
        ok = classifyStreamCopyProbe(exit, out.toString(), sz, readback, expectedCodecName);
        if (Sage.DBG)
          System.out.println("HwEncoder: stream-copy probe " + expectedCodecName + " -> " + lcContainer
              + (variantLabel == null ? "" : "/" + variantLabel)
              + " on " + bin + " = " + (ok ? "OK" : "NO")
              + " (exit=" + exit + " size=" + sz + " readback=" + readback + ")");
      }
      catch (Throwable t)
      {
        if (Sage.DBG) System.out.println("HwEncoder: stream-copy probe errored: " + t);
        ok = false;
      }
      finally
      {
        if (tmp != null) { try { tmp.delete(); } catch (Throwable t) { /* best effort */ } }
      }
      streamCopyCache.put(cacheKey, Boolean.valueOf(ok));
      return ok;
    }
  }

  // --- Explicit AC-4 capability map (per the three-layer design's Layer 3) ----
  // These name each container/variant independently rather than collapsing them,
  // because "ffmpeg knows AV_CODEC_ID_AC4" (a decoder) says NOTHING about whether
  // any given muxer can SIGNAL AC-4. Each answer is an end-to-end verified,
  // cached stream-copy self-test of the real source's AC-4 track. All are
  // fail-closed and require a real AC-4 source (ffmpeg has no AC-4 encoder to
  // synthesize a probe stream, so these seed on first real AC-4 play).

  /** Can ffmpeg copy this source's AC-4 track into a raw {@code .ac4} elementary stream? */
  public static boolean canMuxRawAc4(String ffmpegBin, java.io.File source)
  {
    return streamCopyIntoContainerOk(ffmpegBin, source, "0:a:0", "ac4", "ac4");
  }

  /** Can ffmpeg mux this source's AC-4 track into a plain (non-fragmented) MP4 (ISOBMFF {@code ac-4} sample entry)? */
  public static boolean canMuxAc4IntoMp4(String ffmpegBin, java.io.File source)
  {
    return streamCopyIntoContainerOk(ffmpegBin, source, "0:a:0", "mp4", "ac4");
  }

  /** Can ffmpeg mux this source's AC-4 track into a fragmented MP4 / CMAF segment (the PWA delivery target)? */
  public static boolean canMuxAc4IntoFragmentedMp4(String ffmpegBin, java.io.File source)
  {
    return streamCopyIntoContainerOk(ffmpegBin, source, "0:a:0", "mp4", "ac4",
        java.util.Arrays.asList("-movflags", "+frag_keyframe+empty_moov+default_base_moof"), "frag");
  }

  /** Can ffmpeg remux this source's AC-4 track into MPEG-TS with correct AC-4 signaling (verified by readback, not just a non-error exit)? */
  public static boolean canMuxAc4IntoMpegTs(String ffmpegBin, java.io.File source)
  {
    return streamCopyIntoContainerOk(ffmpegBin, source, "0:a:0", "mpegts", "ac4");
  }

  /** Can ffmpeg mux this source's AC-4 track into Matroska ({@code A_AC4} track mapping)? */
  public static boolean canMuxAc4IntoMatroska(String ffmpegBin, java.io.File source)
  {
    return streamCopyIntoContainerOk(ffmpegBin, source, "0:a:0", "matroska", "ac4");
  }

  /** Convenience: can this ffmpeg stream-copy the first AC-4 audio track of
   *  {@code source} into {@code container} (verified end-to-end)? Accepts the
   *  ffmpeg output-format token ({@code matroska}/{@code mp4}/{@code mpegts}). */
  public static boolean ac4StreamCopyOk(String container, String ffmpegBin, java.io.File source)
  {
    return streamCopyIntoContainerOk(ffmpegBin, source, "0:a:0", container, "ac4");
  }

  /**
   * Build the container stream-copy self-test command. Package-private so the
   * command shape is unit-assertable without spawning ffmpeg. {@code extraOutputArgs}
   * (may be null) are inserted before {@code -f} so container-shaping flags like
   * {@code -movflags} apply to the output.
   */
  static List<String> buildStreamCopyProbeCommand(String bin, String source,
      String mapSelector, String container, String tmpOut, List<String> extraOutputArgs)
  {
    List<String> cmd = new ArrayList<String>();
    cmd.add(bin);
    cmd.add("-hide_banner");
    cmd.add("-loglevel"); cmd.add("error");
    cmd.add("-y");
    cmd.add("-i"); cmd.add(source);
    cmd.add("-map"); cmd.add(mapSelector);
    cmd.add("-c"); cmd.add("copy");
    cmd.add("-t"); cmd.add("1");
    if (extraOutputArgs != null)
      cmd.addAll(extraOutputArgs);
    cmd.add("-f"); cmd.add(container);
    cmd.add(tmpOut);
    return cmd;
  }

  /** Back-compat overload (no extra output args). */
  static List<String> buildStreamCopyProbeCommand(String bin, String source,
      String mapSelector, String container, String tmpOut)
  {
    return buildStreamCopyProbeCommand(bin, source, mapSelector, container, tmpOut, null);
  }

  /**
   * Classify a container stream-copy self-test from its observable results.
   * Package-private + pure so the empirically-captured failure signatures are
   * unit-tested without a real ffmpeg. Returns {@code true} only when the mux
   * exited cleanly, wrote a plausibly-sized file with no tag-failure signature,
   * AND the readback codec equals {@code expectedCodec}.
   */
  static boolean classifyStreamCopyProbe(int muxExit, String muxOutput, long outputBytes,
      String readbackCodec, String expectedCodec)
  {
    if (muxOutput != null)
    {
      String lc = muxOutput.toLowerCase(Locale.ROOT);
      for (String sig : STREAMCOPY_TAG_FAILURES)
        if (lc.contains(sig)) return false;
    }
    if (muxExit != 0) return false;
    if (outputBytes < STREAMCOPY_MIN_OUTPUT_BYTES) return false;
    if (readbackCodec == null) return false;
    String rb = readbackCodec.trim();
    if (rb.length() == 0) return false;
    if (expectedCodec == null) return false;
    return rb.equalsIgnoreCase(expectedCodec.trim());
  }

  /**
   * Derive the sibling {@code ffprobe} path from an {@code ffmpeg} path
   * (e.g. {@code /opt/sagetv/server/ffmpeg} -> {@code /opt/sagetv/server/ffprobe}).
   * Package-private for testing.
   */
  static String deriveFfprobePath(String ffmpegBin)
  {
    if (ffmpegBin == null || ffmpegBin.length() == 0) return "ffprobe";
    java.io.File f = new java.io.File(ffmpegBin);
    String name = f.getName();
    int at = name.toLowerCase(Locale.ROOT).indexOf("ffmpeg");
    String probeName = (at >= 0)
        ? name.substring(0, at) + "ffprobe" + name.substring(at + "ffmpeg".length())
        : "ffprobe";
    java.io.File parent = f.getParentFile();
    return (parent != null) ? new java.io.File(parent, probeName).getPath() : probeName;
  }

  /** Run ffprobe for the first audio track's codec_name; null on any failure. */
  private static String probeFirstAudioCodec(String ffprobeBin, String file)
  {
    Process p = null;
    try
    {
      List<String> cmd = new ArrayList<String>();
      cmd.add(ffprobeBin); cmd.add("-hide_banner"); cmd.add("-v"); cmd.add("error");
      cmd.add("-select_streams"); cmd.add("a:0");
      cmd.add("-show_entries"); cmd.add("stream=codec_name");
      cmd.add("-of"); cmd.add("default=nokey=1:noprint_wrappers=1");
      cmd.add(file);
      p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
      BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
      String first = null;
      try
      {
        String line;
        while ((line = r.readLine()) != null)
        {
          String t = line.trim();
          if (first == null && t.length() > 0) first = t;
        }
      }
      finally { try { r.close(); } catch (IOException ie) {} }
      p.waitFor(10, TimeUnit.SECONDS);
      return first;
    }
    catch (Throwable t) { return null; }
    finally { if (p != null && p.isAlive()) p.destroyForcibly(); }
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
    // Runtime NVENC backoff: after a live encoder-init failure, report the whole
    // GPU-enhance pipeline unavailable for the cooldown so a tune falls back to
    // the plain software/copy path instead of re-attempting a GPU that just failed.
    if (!nvencHealthy()) return false;
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

  /** Property: ms to avoid NVENC after a runtime encoder-init failure (self-healing). */
  private static final String PROP_NVENC_COOLDOWN_MS =
      "multimedia/hwaccel/nvenc/runtime_failure_cooldown_ms";
  private static final long DEFAULT_NVENC_COOLDOWN_MS = 60000L;
  /** When &gt; now, NVENC is treated as unavailable (runtime-failure backoff window). */
  private static volatile long nvencUnhealthyUntil = 0L;

  /**
   * Record a runtime NVENC failure: an ffmpeg child that selected an NVENC
   * encoder died at/near launch with an encoder-init error (e.g. {@code -22
   * Invalid argument}, {@code OpenEncodeSessionEx failed}, {@code Cannot load
   * nvcuda}). For a cooldown window {@link #pick} stops returning NVENC and
   * {@link #gpuEnhanceSupported} reports unsupported, so every new/relaunched
   * transcode falls back to the software {@code libx264} path every stock SageTV
   * server uses — rather than black-screening on a GPU that momentarily cannot be
   * accessed. Self-heals when the window elapses, so a transient fault (driver
   * reload, encode-engine exhaustion, a lost admission race) never disables the
   * GPU until a JVM restart.
   */
  public static void noteNvencFailure()
  {
    long cd = Sage.getLong(PROP_NVENC_COOLDOWN_MS, DEFAULT_NVENC_COOLDOWN_MS);
    if (cd <= 0) return;
    nvencUnhealthyUntil = Sage.time() + cd;
    System.out.println("HwEncoder: NVENC runtime failure noted; routing (re)launches to "
        + "software libx264 for " + cd + "ms");
  }

  /** False while a recent runtime NVENC failure keeps us in the software-fallback window. */
  public static boolean nvencHealthy()
  {
    long until = nvencUnhealthyUntil;
    return until == 0L || Sage.time() >= until;
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
      // Runtime NVENC backoff: after a live encoder-init failure, skip NVENC (try
      // the next preference, ultimately software) until the cooldown elapses.
      if (k == Kind.NVENC && !nvencHealthy()) continue;
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
