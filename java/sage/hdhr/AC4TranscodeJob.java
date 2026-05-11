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
 *                                 auto = on iff h264_nvenc encoder is available
 *   hdhr/ac4_transcode_ffmpeg     default /usr/local/bin/ffmpeg-ac4
 *   hdhr/ac4_transcode_vcodec     default h264_nvenc
 *   hdhr/ac4_transcode_preset     default p4
 *   hdhr/ac4_transcode_vbitrate   default 8M
 *   hdhr/ac4_transcode_acodec     default ac3
 *   hdhr/ac4_transcode_abitrate   default 384k
 *   hdhr/ac4_transcode_scale      default 1920:1080
 */
public class AC4TranscodeJob implements Runnable
{
  public static final String FFMPEG_BIN_PROP = "hdhr/ac4_transcode_ffmpeg";
  public static final String DEFAULT_FFMPEG  = "/usr/local/bin/ffmpeg-ac4";
  public static final String ENABLED_PROP    = "hdhr/ac4_transcode_enabled";

  // Cached result of the one-time NVENC probe.
  private static volatile Boolean nvencAvailable;
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
    String vcodec   = Sage.get("hdhr/ac4_transcode_vcodec",   "h264_nvenc");
    String preset   = Sage.get("hdhr/ac4_transcode_preset",   "p4");
    String vbitrate = Sage.get("hdhr/ac4_transcode_vbitrate", "8M");
    String acodec   = Sage.get("hdhr/ac4_transcode_acodec",   "ac3");
    String abitrate = Sage.get("hdhr/ac4_transcode_abitrate", "384k");
    String scale    = Sage.get("hdhr/ac4_transcode_scale",    "1920:1080");

    List<String> cmd = new ArrayList<String>();
    cmd.add(ff);
    cmd.add("-hide_banner");
    cmd.add("-nostdin");
    cmd.add("-loglevel"); cmd.add("error");
    cmd.add("-y");
    cmd.add("-i"); cmd.add(src);
    cmd.add("-map"); cmd.add("0:v:0");
    cmd.add("-map"); cmd.add("0:a:0");
    cmd.add("-vf"); cmd.add("scale=" + scale + ":flags=bicubic,format=yuv420p");
    cmd.add("-c:v"); cmd.add(vcodec);
    cmd.add("-preset"); cmd.add(preset);
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
   *   "auto" - on iff h264_nvenc is present in ffmpeg-ac4's encoder list
   *            (default; logs a clear WARN once when auto-disabled).
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
        v = hasNvenc();
        if (!v && Sage.DBG)
        {
          System.out.println("AC4TranscodeJob: AUTO-DISABLED — no h264_nvenc "
              + "encoder found in " + Sage.get(FFMPEG_BIN_PROP, DEFAULT_FFMPEG)
              + ". ATSC 3.0 recording still works; modern clients play HEVC+AC-4 "
              + "natively. To force software transcode for legacy clients, set "
              + ENABLED_PROP + "=on (CPU-bound, may not be real-time).");
        }
      }
      enabledCached = Boolean.valueOf(v);
      return v;
    }
  }

  /**
   * Probe ffmpeg-ac4 once for the h264_nvenc encoder. Result is cached for
   * the lifetime of the JVM (re-probe on config change requires restart).
   */
  public static boolean hasNvenc()
  {
    Boolean c = nvencAvailable;
    if (c != null) return c.booleanValue();
    synchronized (AC4TranscodeJob.class)
    {
      if (nvencAvailable != null) return nvencAvailable.booleanValue();
      boolean found = false;
      String ff = Sage.get(FFMPEG_BIN_PROP, DEFAULT_FFMPEG);
      try
      {
        Process p = new ProcessBuilder(ff, "-hide_banner", "-encoders")
            .redirectErrorStream(true).start();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        try
        {
          String line;
          while ((line = r.readLine()) != null)
          {
            if (line.contains("h264_nvenc")) { found = true; break; }
          }
        }
        finally { try { r.close(); } catch (IOException ie) {} }
        // Drain remainder so the process can exit cleanly.
        try { while (p.getInputStream().read() >= 0); } catch (IOException ie) {}
        try { p.waitFor(); } catch (InterruptedException ie) { p.destroy(); }
      }
      catch (Throwable t)
      {
        if (Sage.DBG) System.out.println("AC4TranscodeJob: NVENC probe failed: " + t);
      }
      nvencAvailable = Boolean.valueOf(found);
      if (Sage.DBG) System.out.println("AC4TranscodeJob: h264_nvenc "
          + (found ? "available" : "NOT available"));
      return found;
    }
  }
}
