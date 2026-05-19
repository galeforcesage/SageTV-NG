/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */
package sage.captions;

import sage.MediaFile;
import sage.Sage;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Single caption-extraction job. Spawns ffmpeg with the lavfi `subcc` filter to
 * pull EIA-608/CEA-708 captions out of the recording's video stream and writes
 * a `.srt` sidecar next to the source file. Writes to a `.tmp` first then
 * atomically renames so a half-written sidecar never confuses the subtitle
 * loader.
 */
class CaptionExtractionJob implements Runnable
{
  private final MediaFile mf;
  private final File recFile;
  private final File sidecar;
  private final Runnable onComplete;

  private volatile Process proc;
  private volatile boolean cancelled;

  CaptionExtractionJob(MediaFile mf, File recFile, File sidecar, Runnable onComplete)
  {
    this.mf = mf;
    this.recFile = recFile;
    this.sidecar = sidecar;
    this.onComplete = onComplete;
  }

  void cancel()
  {
    cancelled = true;
    Process p = proc;
    if (p != null) p.destroy();
  }

  @Override
  public void run()
  {
    File tmp = new File(sidecar.getAbsolutePath() + ".tmp");
    try
    {
      if (tmp.exists()) tmp.delete();

      // Default to the bundled SageTV-patched ffmpeg (the same binary the
      // transcoder, format parser, thumbnail extractor, etc. resolve through
      // FFMPEGTranscoder.getTranscoderPath()). Users can still override with
      // caption_extraction/ffmpeg_path. The args we send here are all standard
      // libav flags, so any modern ffmpeg works -- but defaulting to the
      // bundled binary keeps every native subprocess in SageTV using the
      // same, known-good build instead of whatever happens to be on PATH.
      String ffmpeg = Sage.get("caption_extraction/ffmpeg_path",
          sage.FFMPEGTranscoder.getTranscoderPath());
      int extractSec = Sage.getInt("caption_extraction/extract_seconds", 0);

      // The lavfi `movie=PATH[out0+subcc]` filter exposes a captions subtitle
      // stream synthesized from line-21 / SEI user data in the video track. We
      // map that synthesized stream to the SRT muxer.
      //
      // ffmpeg is picky about characters in the lavfi filter graph; escape
      // backslashes, single quotes, colons, and commas in the path.
      String escapedPath = escapeForLavfi(recFile.getAbsolutePath());
      String filterInput = "movie=" + escapedPath + "[out0+subcc]";

      List<String> cmd = new ArrayList<>();
      cmd.add(ffmpeg);
      cmd.add("-hide_banner");
      cmd.add("-loglevel");
      cmd.add("error");
      cmd.add("-y");
      cmd.add("-f");
      cmd.add("lavfi");
      cmd.add("-i");
      cmd.add(filterInput);
      cmd.add("-map");
      cmd.add("0:1");
      cmd.add("-c:s");
      cmd.add("srt");
      // Explicit muxer so the `.tmp` suffix doesn't break format inference.
      cmd.add("-f");
      cmd.add("srt");
      if (extractSec > 0)
      {
        cmd.add("-t");
        cmd.add(Integer.toString(extractSec));
      }
      cmd.add(tmp.getAbsolutePath());

      if (Sage.DBG) System.out.println("CaptionExtractionJob: launching " + String.join(" ", cmd));

      ProcessBuilder pb = new ProcessBuilder(cmd);
      pb.redirectErrorStream(true);
      proc = pb.start();

      // Drain stderr/stdout so the process doesn't block.
      StringBuilder errBuf = new StringBuilder();
      try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8)))
      {
        String line;
        while ((line = r.readLine()) != null)
        {
          if (errBuf.length() < 4096) errBuf.append(line).append('\n');
        }
      }

      int rc = proc.waitFor();
      if (cancelled)
      {
        if (Sage.DBG) System.out.println("CaptionExtractionJob: cancelled for " + recFile);
        tmp.delete();
        return;
      }
      if (rc != 0)
      {
        if (Sage.DBG) System.out.println("CaptionExtractionJob: ffmpeg exit=" + rc + " for " + recFile + "\n" + errBuf);
        tmp.delete();
        return;
      }

      // ffmpeg writes the file even when there are zero cues; treat empty/very
      // small output as "no captions present".
      if (!tmp.isFile() || tmp.length() < 8)
      {
        if (Sage.DBG) System.out.println("CaptionExtractionJob: no captions found in " + recFile);
        tmp.delete();
        return;
      }

      // ffmpeg's `subcc` synthesizer emits ASS-style positioning ({\an7}),
      // <font face="Monospace">...</font> wrappers, and \h hard-spaces.
      // SageTV's SRTSubtitleHandler renders most of that as literal text, so
      // strip it down to plain text + a few simple tags it knows about.
      cleanSrtFile(tmp);
      if (tmp.length() < 8)
      {
        if (Sage.DBG) System.out.println("CaptionExtractionJob: nothing left after cleanup for " + recFile);
        tmp.delete();
        return;
      }

      Files.move(tmp.toPath(), sidecar.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      if (Sage.DBG) System.out.println("CaptionExtractionJob: wrote sidecar " + sidecar + " (" + sidecar.length() + " bytes)");
    }
    catch (IOException | InterruptedException e)
    {
      if (Sage.DBG) System.out.println("CaptionExtractionJob: error processing " + recFile + ": " + e);
      try { tmp.delete(); } catch (Throwable ignore) {}
    }
    finally
    {
      try { if (onComplete != null) onComplete.run(); } catch (Throwable ignore) {}
    }
  }

  /**
   * Escape a filesystem path for use inside an ffmpeg lavfi filter expression
   * (specifically the value of `movie=`). Per ffmpeg docs the special chars
   * inside a filter argument are `\ ' : ,` — wrap result so colons and
   * commas in directory names don't truncate the argument.
   */
  static String escapeForLavfi(String path)
  {
    StringBuilder sb = new StringBuilder(path.length() + 16);
    for (int i = 0; i < path.length(); i++)
    {
      char c = path.charAt(i);
      if (c == '\\' || c == '\'' || c == ':' || c == ',' || c == '[' || c == ']' || c == ';')
        sb.append('\\');
      sb.append(c);
    }
    return sb.toString();
  }

  /**
   * Rewrite an SRT in place stripping ASS-style positioning ({\an?}),
   * \h hard-spaces, font-face wrappers, and any other {..} or unsupported
   * tags emitted by ffmpeg's lavfi subcc synthesizer. Italic tags (<i></i>)
   * are preserved because SageTV's SRT handler understands them.
   */
  static void cleanSrtFile(File f) throws IOException
  {
    java.util.List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
    StringBuilder out = new StringBuilder((int) Math.min(f.length() + 64, Integer.MAX_VALUE));
    for (String line : lines)
    {
      String s = cleanSrtLine(line);
      out.append(s).append('\n');
    }
    Files.write(f.toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
  }

  static String cleanSrtLine(String s)
  {
    if (s == null || s.isEmpty()) return s == null ? "" : s;
    // Strip {\anX}, {\posX,Y}, and any other {\...} ASS override blocks.
    s = s.replaceAll("\\{\\\\[^}]*\\}", "");
    // Strip <font ...> ... </font> wrappers (keep inner text).
    s = s.replaceAll("(?i)</?font[^>]*>", "");
    // \h is the ASS hard-space; render as a real space.
    s = s.replace("\\h", " ");
    // Collapse runs of spaces created by stripping.
    s = s.replaceAll("  +", " ");
    return s;
  }
}
