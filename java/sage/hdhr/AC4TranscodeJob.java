/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package sage.hdhr;

import sage.Sage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Legacy-client transcode for ATSC 3.0 recordings (HEVC + AC-4) into a
 * miniclient-playable MPEG-TS (H.264 + AC-3).
 *
 * Strict isolation:
 *   - Always invokes /usr/local/bin/ffmpeg-ac4 by ABSOLUTE PATH.
 *   - Never touches the SageTV-patched ffmpeg or the stock /usr/bin/ffmpeg.
 *   - Sage's existing FFMPEGTranscoder is NOT modified; this is a parallel
 *     code path used only when the source MediaFile is HEVC and the consuming
 *     client is a legacy renderer (HD300 etc.).
 *
 * Single command shape (validated 5/10 on /tmp/hevc-sample.ts at 6.1x rt):
 *   ffmpeg-ac4 -i &lt;src&gt;
 *              -map 0:v:0 -map 0:a:0
 *              -vf scale=1920:1080:flags=bicubic,format=yuv420p
 *              -c:v h264_nvenc -preset p4 -b:v 8M
 *              -c:a ac3 -b:a 384k
 *              -f mpegts &lt;dst&gt;
 *
 * The -vf must include scale= (plain -vf format=yuv420p returned ENOSYS on
 * the Turing 8-bit NVENC path).
 *
 * Sage.properties knobs:
 *   hdhr/ac4_transcode_enabled    default "auto"  (auto|on|off)
 *                                 auto = on iff ANY HW H.264 encoder is
 *                                 available (nvenc/vaapi/qsv/amf/videotoolbox)
 *   hdhr/ac4_transcode_ffmpeg     default /usr/local/bin/ffmpeg-ac4
 *   hdhr/ac4_transcode_vcodec     default "auto" (HwEncoder picks); accepts
 *                                 explicit names like h264_nvenc, h264_vaapi
 *   hdhr/ac4_transcode_preset     default p4 (portable hint, mapped per encoder)
 *   hdhr/ac4_transcode_vbitrate   default 8M
 *   hdhr/ac4_transcode_acodec     default ac3
 *   hdhr/ac4_transcode_abitrate   default 384k
 *   hdhr/ac4_transcode_scale      default 1920:1080
 *   hdhr/ac4_transcode_audio_lang default "" (auto from default_audio_language).
 *                                 ISO-639-2 3-letter code (e.g. "eng", "spa").
 *                                 Selects the first audio stream whose ISO_639
 *                                 language descriptor matches; falls back to
 *                                 stream 0 if not found or probe fails.
 */
public class AC4TranscodeJob implements Runnable
{
  public static final String FFMPEG_BIN_PROP = "hdhr/ac4_transcode_ffmpeg";
  public static final String DEFAULT_FFMPEG  = "/opt/sagetv/server/ffmpeg";
  public static final String ENABLED_PROP    = "hdhr/ac4_transcode_enabled";

  // Cached result of the one-time isEnabled() decision (logged once).
  private static volatile Boolean enabledCached;

  private final String src;
  private final String dst;
  private volatile Process proc;
  private volatile int     exitCode = -1;
  private volatile String  lastErrorLine;

  public AC4TranscodeJob(String srcFile, String dstFile)
  {
    if (srcFile == null || dstFile == null)
      throw new IllegalArgumentException("src/dst required");
    this.src = srcFile;
    this.dst = dstFile;
  }

  public void requestStop()
  {
    Process p = proc;
    if (p != null) p.destroy();
  }

  public int getExitCode()        { return exitCode; }
  public String getLastErrorLine(){ return lastErrorLine; }

  @Override
  public void run()
  {
    String ff       = Sage.get(FFMPEG_BIN_PROP, DEFAULT_FFMPEG);
    String vcodec   = Sage.get("hdhr/ac4_transcode_vcodec",   "auto");
    String presetIn = Sage.get("hdhr/ac4_transcode_preset",   "p4");
    String vbitrate = Sage.get("hdhr/ac4_transcode_vbitrate", "8M");
    String acodec   = Sage.get("hdhr/ac4_transcode_acodec",   "ac3");
    String abitrate = Sage.get("hdhr/ac4_transcode_abitrate", "384k");
    String scale    = Sage.get("hdhr/ac4_transcode_scale",    "1920:1080");

    // Resolve generic "auto" -> concrete encoder via HwEncoder.
    sage.HwEncoder.Kind hwKind;
    String resolvedEncoder;
    if ("auto".equalsIgnoreCase(vcodec))
    {
      hwKind = sage.HwEncoder.pick("h264", ff);
      resolvedEncoder = sage.HwEncoder.encoderName(hwKind, "h264");
      if (resolvedEncoder == null) resolvedEncoder = "libx264";
    }
    else
    {
      // Explicit encoder name; figure out which kind it belongs to so we
      // can translate the portable preset hint correctly.
      hwKind = encoderToKind(vcodec);
      resolvedEncoder = vcodec;
    }
    String preset = sage.HwEncoder.preset(hwKind, presetIn);
    String presetFlag = sage.HwEncoder.presetFlag(hwKind);

    List<String> cmd = new ArrayList<String>();
    cmd.add(ff);
    cmd.add("-hide_banner");
    cmd.add("-nostdin");
    cmd.add("-loglevel"); cmd.add("error");
    cmd.add("-y");
    // HW-specific global args (e.g. VAAPI device init) must precede -i.
    for (String g : sage.HwEncoder.globalArgs(hwKind)) cmd.add(g);
    cmd.add("-i"); cmd.add(src);
    cmd.add("-map"); cmd.add("0:v:0");
    // Pick audio stream by language preference (e.g. NextGen broadcasts often
    // carry primary=spa + secondary=eng AC-4 tracks). Falls back to 0:a:0.
    String audioMap = resolveAudioMap(ff, src);
    cmd.add("-map"); cmd.add(audioMap);
    String hwFilter = sage.HwEncoder.videoFilter(hwKind, "yuv420p", "scale=" + scale + ":flags=bicubic");
    cmd.add("-vf"); cmd.add(hwFilter);
    cmd.add("-c:v"); cmd.add(resolvedEncoder);
    if (preset != null && preset.length() > 0)
    {
      cmd.add(presetFlag); cmd.add(preset);
    }
    cmd.add("-b:v"); cmd.add(vbitrate);
    cmd.add("-c:a"); cmd.add(acodec);
    cmd.add("-b:a"); cmd.add(abitrate);
    cmd.add("-f"); cmd.add("mpegts");
    cmd.add(dst);

    if (Sage.DBG) System.out.println("AC4TranscodeJob exec " + cmd);

    Process p;
    try
    {
      p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
    }
    catch (IOException e)
    {
      lastErrorLine = "spawn failed: " + e.getMessage();
      if (Sage.DBG) System.out.println("AC4TranscodeJob: " + lastErrorLine);
      exitCode = -2;
      return;
    }
    proc = p;

    final Process fp = p;
    Thread pump = new Thread(new Runnable() {
      public void run()
      {
        InputStream es = fp.getErrorStream();
        BufferedReader r = new BufferedReader(new InputStreamReader(es));
        try
        {
          String line;
          while ((line = r.readLine()) != null)
          {
            lastErrorLine = line;
            if (Sage.DBG) System.out.println("ffmpeg-ac4: " + line);
          }
        }
        catch (IOException ignore) { }
        finally { try { r.close(); } catch (IOException ie) {} }
      }
    }, "AC4TranscodeJob-stderr");
    pump.setDaemon(true);
    pump.start();

    try { exitCode = p.waitFor(); }
    catch (InterruptedException ie) { p.destroy(); exitCode = -3; }
    proc = null;
  }

  /**
   * True if AC-4 transcode is permitted. Honors hdhr/ac4_transcode_enabled:
   *   "on"   - always on
   *   "off"  - always off
   *   "auto" - on iff any HW H.264 encoder is available (default; logs a
   *            clear WARN once when auto-disabled).
   */
  public static boolean isEnabled()
  {
    Boolean c = enabledCached;
    if (c != null) return c.booleanValue();
    synchronized (AC4TranscodeJob.class)
    {
      if (enabledCached != null) return enabledCached.booleanValue();
      String mode = Sage.get(ENABLED_PROP, "auto").trim().toLowerCase();
      boolean v;
      if ("on".equals(mode) || "true".equals(mode) || "1".equals(mode))
      {
        v = true;
      }
      else if ("off".equals(mode) || "false".equals(mode) || "0".equals(mode))
      {
        v = false;
      }
      else // "auto" or anything else
      {
        String ff = Sage.get(FFMPEG_BIN_PROP, DEFAULT_FFMPEG);
        sage.HwEncoder.Kind k = sage.HwEncoder.pick("h264", ff);
        v = (k != null && k != sage.HwEncoder.Kind.NONE);
        if (!v && Sage.DBG)
        {
          System.out.println("AC4TranscodeJob: AUTO-DISABLED — no HW H.264 "
              + "encoder (nvenc/vaapi/qsv/amf/videotoolbox) found in " + ff
              + ". ATSC 3.0 recording still works; modern clients play HEVC+AC-4 "
              + "natively. To force software transcode for legacy clients, set "
              + ENABLED_PROP + "=on (CPU-bound, may not be real-time).");
        }
        else if (Sage.DBG)
        {
          System.out.println("AC4TranscodeJob: AUTO-ENABLED — using " + k
              + " (" + sage.HwEncoder.encoderName(k, "h264") + ")");
        }
      }
      enabledCached = Boolean.valueOf(v);
      return v;
    }
  }

  /**
   * @deprecated Use {@code sage.HwEncoder.availableFor("h264")} for the
   * generic check, or {@code sage.HwEncoder.pick("h264", ff) == NVENC}
   * specifically. Kept as a thin alias so any caller (or test) still compiles.
   */
  @Deprecated
  public static boolean hasNvenc()
  {
    String ff = Sage.get(FFMPEG_BIN_PROP, DEFAULT_FFMPEG);
    return sage.HwEncoder.detect(ff).contains(sage.HwEncoder.Kind.NVENC);
  }

  /**
   * Choose the ffmpeg -map argument for the audio track matching the user's
   * preferred language. Probes the source with ffprobe (assumed to live next
   * to ff at &lt;dir&gt;/ffprobe, else /usr/bin/ffprobe, else /usr/local/bin/ffprobe).
   * Returns a string like "0:a:1" (per-type index) or "0:a:0" on any failure.
   */
  static String resolveAudioMap(String ff, String src)
  {
    String prefIso = Sage.get("hdhr/ac4_transcode_audio_lang", "").trim().toLowerCase();
    if (prefIso.length() == 0)
      prefIso = mapDefaultAudioLangToIso(Sage.get("default_audio_language", "English"));
    if (prefIso == null || prefIso.length() == 0)
      return "0:a:0";

    String[] probeCandidates = new String[] {
      siblingFfprobe(ff),
      "/usr/bin/ffprobe",
      "/usr/local/bin/ffprobe"
    };
    String probe = null;
    for (String c : probeCandidates)
    {
      if (c != null && new java.io.File(c).canExecute()) { probe = c; break; }
    }
    if (probe == null)
    {
      if (Sage.DBG) System.out.println("AC4TranscodeJob: no ffprobe available; using 0:a:0");
      return "0:a:0";
    }

    try
    {
      // One line per audio stream (in -map index order), containing just the
      // ISO-639 language tag (or empty if untagged).
      ProcessBuilder pb = new ProcessBuilder(probe,
        "-v", "error",
        "-select_streams", "a",
        "-show_entries", "stream_tags=language",
        "-of", "default=nw=1:nk=1",
        src);
      pb.redirectErrorStream(true);
      Process p = pb.start();
      java.io.BufferedReader r = new java.io.BufferedReader(
          new java.io.InputStreamReader(p.getInputStream()));
      java.util.List<String> langs = new java.util.ArrayList<String>();
      String line;
      while ((line = r.readLine()) != null)
      {
        langs.add(line.trim().toLowerCase());
      }
      try { p.waitFor(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
      if (langs.isEmpty()) return "0:a:0";
      for (int i = 0; i < langs.size(); i++)
      {
        if (prefIso.equals(langs.get(i)))
        {
          if (Sage.DBG) System.out.println("AC4TranscodeJob: selected audio track " + i + " (lang=" + prefIso + ") of " + langs);
          return "0:a:" + i;
        }
      }
      if (Sage.DBG) System.out.println("AC4TranscodeJob: no audio track matches lang=" + prefIso + " in " + langs + "; falling back to 0:a:0");
      return "0:a:0";
    }
    catch (java.io.IOException e)
    {
      if (Sage.DBG) System.out.println("AC4TranscodeJob: ffprobe failed: " + e + "; using 0:a:0");
      return "0:a:0";
    }
  }

  private static String siblingFfprobe(String ff)
  {
    if (ff == null) return null;
    int slash = ff.lastIndexOf('/');
    String dir = slash >= 0 ? ff.substring(0, slash) : ".";
    return dir + "/ffprobe";
  }

  /**
   * Translate a SageTV default_audio_language full name (e.g. "English") to
   * the first ISO-639-2 3-letter code Sage maps it to. Mirrors VideoFrame's
   * MediaLangInfo table without triggering VideoFrame's DShow static init.
   */
  static String mapDefaultAudioLangToIso(String fullName)
  {
    if (fullName == null) return "eng";
    String src = Sage.get("media_language_options",
      "en;English;eng|ar;Arabic;ara|bg;Bulgarian;bul|zh;Chinese;chi,zho|cs;Czech;ces,cze|da;Danish;dan|nl;Dutch;nld,dut,nla|fi;Finnish;fin|fr;French;fra,fre|de;German;deu,ger|el;Greek;gre,ell|he;Hebrew;heb|hu;Hungarian;hun|it;Italian;ita|ja;Japanese;jpn|ko;Korean;kor|no;Norwegian;nor|pl;Polish;pol|pt;Portugese;por|ru;Russian;rus|sl;Slovenian;slv|es;Spanish;esl,spa|sv;Swedish;sve,swe|to;Tonga;ton,tog|tr;Turkish;tur");
    java.util.StringTokenizer t = new java.util.StringTokenizer(src, "|");
    while (t.hasMoreTokens())
    {
      String tok = t.nextToken();
      int s1 = tok.indexOf(';');
      int s2 = tok.lastIndexOf(';');
      if (s1 < 0 || s2 <= s1) continue;
      String full = tok.substring(s1 + 1, s2);
      if (fullName.equalsIgnoreCase(full))
      {
        String three = tok.substring(s2 + 1);
        int comma = three.indexOf(',');
        return (comma >= 0 ? three.substring(0, comma) : three).trim().toLowerCase();
      }
    }
    return "eng";
  }

  /** Map an explicit ffmpeg encoder name to its HwEncoder.Kind. */
  private static sage.HwEncoder.Kind encoderToKind(String enc)
  {
    if (enc == null) return sage.HwEncoder.Kind.NONE;
    String e = enc.toLowerCase();
    if (e.endsWith("_nvenc"))        return sage.HwEncoder.Kind.NVENC;
    if (e.endsWith("_vaapi"))        return sage.HwEncoder.Kind.VAAPI;
    if (e.endsWith("_qsv"))          return sage.HwEncoder.Kind.QSV;
    if (e.endsWith("_amf"))          return sage.HwEncoder.Kind.AMF;
    if (e.endsWith("_videotoolbox")) return sage.HwEncoder.Kind.VIDEOTOOLBOX;
    return sage.HwEncoder.Kind.NONE;
  }
}
