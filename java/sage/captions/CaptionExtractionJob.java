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
 * Single caption-extraction job. Pulls EIA-608 / CEA-708 captions from the
 * recording's video stream and writes language-tagged sidecars next to the
 * source file:
 * <ul>
 *   <li>{@code <base>.srt} + {@code <base>.vtt} — primary track (CEA-708
 *       service 1 if present, else 608 CC1). This is the file SageTV's
 *       legacy {@code FormatParser.updateExternalSubs} short-circuits on so
 *       it always shows up as the default subtitle stream.</li>
 *   <li>{@code <base>.spa.srt} + {@code <base>.spa.vtt} — 608 CC2 (Spanish
 *       SAP track on most US broadcasters). Skipped when empty.</li>
 * </ul>
 *
 * <h3>Extractor selection</h3>
 * Prefers {@code ccextractor} (much faster than ffmpeg's lavfi {@code subcc}
 * filter, decodes CEA-708 DTVCC services directly, handles H.265 SEI). Falls
 * back to {@code ffmpeg -f lavfi -i movie=...[out0+subcc]} when ccextractor
 * is not on {@code PATH} (CC1 only in that mode, no VTT, no Spanish).
 *
 * <h3>Atomic writes</h3>
 * Each output is written to {@code <name>.tmp} first then atomically renamed
 * so a half-written sidecar never confuses the subtitle loader.
 */
class CaptionExtractionJob implements Runnable
{
  private final MediaFile mf;
  private final File recFile;
  private final File sidecar;
  private final Runnable onComplete;
  private final boolean onDemand;

  private volatile Process proc;
  private volatile boolean cancelled;

  // Piece C v2 (live push + ephemeral write-suppression): optional hook
  // fired, STPP path only, with the freshly-coalesced CaptionEvents right
  // after each extraction pass — before writeGrouped() persists them — so a
  // caller (CaptionExtractionManager's live-tail loop) can push them
  // straight into active VideoFrames every cycle, not just before a sidecar
  // first exists. Never invoked for the 608/708 path. Unset (null) by
  // default; a no-op then.
  private volatile java.util.function.Consumer<List<CaptionEvent>> liveEventSink;

  // Incremental-extraction integration: when set (STPP path only), the job
  // calls Atsc3StppExtractor.extractIncremental(state) instead of the full
  // extract(File, String) rescan, then reconstructs the same "complete
  // authoritative list" shape every downstream consumer (liveEventSink,
  // writeGrouped) expects by concatenating state.finalizedSnapshot() with
  // the pass's provisional tail. Cost is proportional to newly-arrived data
  // (a bounded ffmpeg -ss window), not total file length, while every line
  // below this class stays unaware of the swap. Null (default) keeps the
  // original full-rescan behavior -- the escape hatch used by every caller
  // except CaptionExtractionManager.LiveExtractor's STPP branch.
  private volatile Atsc3StppExtractor.StppIncrementalState incrementalState;

  // Piece C v2: when false, the STPP path skips its own writeGrouped() call
  // entirely (the caller — CaptionExtractionManager.LiveExtractor — owns
  // persistence timing instead: never for ephemeral live buffers, on a rare
  // decoupled cadence for in-progress keepers). Defaults to true so every
  // other caller (one-shot completed-recording jobs, backfill, on-demand)
  // keeps writing the sidecar exactly as before. Never affects the 608/708
  // fallback path, which is out of scope for this revision and always
  // writes its own SRT directly as it always has.
  private volatile boolean persistSidecar = true;

  CaptionExtractionJob(MediaFile mf, File recFile, File sidecar, Runnable onComplete)
  {
    this(mf, recFile, sidecar, onComplete, false);
  }

  CaptionExtractionJob(MediaFile mf, File recFile, File sidecar, Runnable onComplete, boolean onDemand)
  {
    this.mf = mf;
    this.recFile = recFile;
    this.sidecar = sidecar;
    this.onComplete = onComplete;
    this.onDemand = onDemand;
  }

  /** See {@link #liveEventSink}. */
  void setLiveEventSink(java.util.function.Consumer<List<CaptionEvent>> sink)
  {
    this.liveEventSink = sink;
  }

  /** See {@link #persistSidecar}. */
  void setPersistSidecar(boolean persist)
  {
    this.persistSidecar = persist;
  }

  /** See {@link #incrementalState}. */
  void setIncrementalState(Atsc3StppExtractor.StppIncrementalState state)
  {
    this.incrementalState = state;
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
    try
    {
      if (runAtsc3StppIfPresent())
      {
        return;
      }

      // NOTE: the 608/708 passes below still shell ccextractor/ffmpeg the
      // same way they always have (including their sibling .vtt output,
      // which is untouched legacy ccextractor-native output, not routed
      // through CaptionEvent — SRT is the only sidecar format
      // CaptionEvent-based writers ever produce). What *is* new: once each
      // pass's SRT sidecar is written, convergeThroughCaptionEvent() parses
      // it straight back into CaptionEvent[] and re-serializes it via
      // SrtCaptionWriter, so the on-disk SRT for every caption source (ATSC1
      // 608/708 and ATSC3 STPP alike) is canonically CaptionEvent's own
      // serialization, not a source-specific format. This is a deliberate
      // round-trip no-op on cue content — see convergeThroughCaptionEvent().
      String ccextractor = Sage.get("caption_extraction/ccextractor_path", "ccextractor");
      boolean useCce = Sage.getBoolean("caption_extraction/use_ccextractor", true) && which(ccextractor);
      if (useCce)
      {
        runCcextractor(ccextractor);
      }
      else
      {
        runFfmpegFallback();
      }
    }
    finally
    {
      try { if (onComplete != null) onComplete.run(); } catch (Throwable ignore) {}
    }
  }

  // ── ATSC 3.0 STPP/IMSC1 path ────────────────────────────────────────────

  /**
   * ATSC 3.0 recordings carry captions as a clean STPP/IMSC1 (TTML) data
   * stream instead of 608/708 user data, so caption handling here is
   * source-driven rather than codec-driven: if such a stream is present we
   * extract it exclusively (via {@link Atsc3StppExtractor}) and skip the
   * ccextractor/ffmpeg 608/708 passes entirely, since ATSC3 broadcasts carry
   * STPP <em>instead of</em> 608/708, not in addition to it.
   *
   * @return true if an STPP stream was detected and handled (regardless of
   *         whether any cues were actually written), false if no STPP
   *         stream was found and the caller should fall back to the legacy
   *         608/708 extraction path.
   */
  private boolean runAtsc3StppIfPresent()
  {
    String ffmpeg = Sage.get("caption_extraction/ffmpeg_path", sage.FFMPEGTranscoder.getTranscoderPath());
    Atsc3StppExtractor.StppStream stream = Atsc3StppExtractor.detectStppStream(recFile, ffmpeg);
    if (stream == null) return false;

    if (Sage.DBG) System.out.println("CaptionExtractionJob: ATSC3 STPP stream detected (index=" +
        stream.streamIndex + ", lang=" + stream.language + ") for " + recFile + "; using TTML pipeline");

    List<CaptionEvent> events;
    Atsc3StppExtractor.StppIncrementalState st = incrementalState;
    if (st != null)
    {
      // Incremental path: cost proportional to newly-arrived data. Every
      // downstream consumer of `events` (liveEventSink below, writeGrouped
      // if persistSidecar) expects the complete authoritative list, so
      // reconstruct it from the state's finalized cues plus the current
      // pass's still-open provisional tail rather than passing along just
      // the newly-finalized delta.
      Atsc3StppExtractor.IncrementalResult inc = Atsc3StppExtractor.extractIncremental(recFile, ffmpeg, st);
      List<CaptionEvent> full = new ArrayList<>(st.finalizedSnapshot());
      if (inc.provisionalTail != null) full.addAll(inc.provisionalTail);
      events = full;
    }
    else
    {
      events = Atsc3StppExtractor.extract(recFile, ffmpeg);
    }
    if (events.isEmpty())
    {
      if (Sage.DBG) System.out.println("CaptionExtractionJob: STPP stream present but no cues extracted for " + recFile);
      return true;
    }

    // Piece C v2: fire the live-push hook (if wired) with the raw coalesced
    // events *before* persisting them, every cycle (not just before a
    // sidecar first exists) — the caller decides which subset (e.g.
    // primary-service-only, lag-by-one-cue) is safe to display in-memory,
    // and whether to persist at all. This never affects the sidecar write
    // below.
    java.util.function.Consumer<List<CaptionEvent>> sink = liveEventSink;
    if (sink != null)
    {
      try { sink.accept(events); } catch (Throwable ignore) {}
    }

    if (!persistSidecar)
    {
      // Ephemeral live buffer, or the caller (LiveExtractor) is managing its
      // own decoupled flush cadence and this cycle isn't a flush cycle:
      // skip the write entirely. The liveEventSink above already delivered
      // the fresh events for in-memory display.
      if (Sage.DBG) System.out.println("CaptionExtractionJob: persistSidecar=false, skipping sidecar write for " + sidecar);
      return true;
    }

    try
    {
      new SrtCaptionWriter().writeGrouped(events, sidecar);
      if (Sage.DBG) System.out.println("CaptionExtractionJob: wrote " + sidecar + " (" + events.size() +
          " cues, source=ATSC3_STPP)");
    }
    catch (IOException e)
    {
      if (Sage.DBG) System.out.println("CaptionExtractionJob: failed to write STPP sidecar " + sidecar + ": " + e);
    }
    return true;
  }

  // ── ccextractor path (preferred) ───────────────────────────────────────

  private void runCcextractor(String ccextractor)
  {
    String basePath = stripExt(sidecar.getAbsolutePath());
    File srtPrimary = new File(basePath + ".srt");
    File vttPrimary = new File(basePath + ".vtt");
    File srtSpa    = new File(basePath + ".spa.srt");
    File vttSpa    = new File(basePath + ".spa.vtt");

    String inFmt = ccextractorInputFlag(recFile.getName());
    int extractSec = Sage.getInt("caption_extraction/extract_seconds", 0);
    // On-demand (during playback): single CC1 pass, limited to 5 minutes
    // to avoid disk I/O contention that causes video freezes.
    if (onDemand)
    {
      int onDemandSec = Sage.getInt("caption_extraction/ondemand_seconds", 300);
      if (extractSec <= 0 || extractSec > onDemandSec) extractSec = onDemandSec;
      if (Sage.DBG) System.out.println("CaptionExtractionJob: on-demand mode, CC1 only, limit=" + extractSec + "s");
    }

    // ── Pass 1: 608 CC1 (always attempted) ─────────────────────────────
    File cc1Tmp = new File(basePath + ".cc1.srt");
    boolean cc1Ok = runCce(ccextractor, cc1Tmp.getAbsolutePath(), "srt", inFmt, null, extractSec);

    File svcOut = null;
    boolean svcOk = false;
    if (!onDemand)
    {
      // Brief pause between passes so playback I/O can breathe.
      if (!cancelled) throttlePause();

      // ── Pass 2: 708 service 1 (preferred when present) ─────────────────
      // ccextractor with -svc N renames the -o argument by inserting
      // ".pN.svcNN" before the extension. The N varies by input format
      // (.p0 for .mpg, .p1 for .ts), so we search for it dynamically.
      File svcArg = new File(basePath + ".svc01.srt");
      svcOk = runCce(ccextractor, svcArg.getAbsolutePath(), "srt", inFmt, "svc1", extractSec);
      svcOut = findPhantom(basePath, "svc01", "srt");
    }

    // Pick best primary
    File winner = null;
    String winnerKind = null;
    if (svcOk && svcOut != null && svcOut.length() > 8) { winner = svcOut; winnerKind = "708svc1"; }
    else if (cc1Ok && cc1Tmp.isFile() && cc1Tmp.length() > 8) { winner = cc1Tmp; winnerKind = "608cc1"; }

    if (winner != null)
    {
      try
      {
        cleanSrtFile(winner);
        if (winner.length() > 8)
        {
          Files.move(winner.toPath(), srtPrimary.toPath(),
              StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
          // Sibling VTT — skip in on-demand mode to minimize I/O
          if (!onDemand)
          {
            File vttArg = new File(basePath + ".vtt-tmp.vtt");
            runCce(ccextractor, vttArg.getAbsolutePath(), "webvtt", inFmt,
                "708svc1".equals(winnerKind) ? "svc1" : null, extractSec);
            // ccextractor may produce .vtt-tmp.pN.svcNN.vtt or plain .vtt-tmp.vtt
            File vttResult = findPhantom(basePath, "vtt-tmp", "vtt");
            if (vttResult == null && vttArg.isFile()) vttResult = vttArg;
            if (vttResult != null && vttResult.length() > 8)
            {
              Files.move(vttResult.toPath(), vttPrimary.toPath(),
                  StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
          }
          if (Sage.DBG) System.out.println("CaptionExtractionJob: wrote " + srtPrimary +
              " (" + srtPrimary.length() + " bytes, source=" + winnerKind + ")");
          convergeThroughCaptionEvent(srtPrimary, "eng",
              "708svc1".equals(winnerKind) ? CaptionEvent.SERVICE_708_SVC1 : CaptionEvent.SERVICE_CC1);
        }
      }
      catch (IOException e)
      {
        if (Sage.DBG) System.out.println("CaptionExtractionJob: primary rename failed: " + e);
      }
    }
    // cleanup intermediates (incl. phantom .pN.svcNN.srt that ccextractor
    // 0.94 produces as a side-effect when 708 captions are present in the
    // source, regardless of -svc flag. Pattern varies: .p0.svc01 or .p1.svc01).
    cc1Tmp.delete();
    if (!onDemand)
    {
      new File(basePath + ".svc01.srt").delete();
      if (svcOut != null) svcOut.delete();
    }
    deletePhantoms(basePath, "cc1");
    deletePhantoms(basePath, "svc01");
    deletePhantoms(basePath, "vtt-tmp");
    new File(basePath + ".vtt-tmp.vtt").delete();

    if (!onDemand && !cancelled)
    {
      throttlePause();

      // ── Pass 3: 608 CC2 (Spanish SAP) — skip silently when empty ──────────
      File cc2Tmp = new File(basePath + ".cc2-tmp.srt");
      boolean cc2Ok = runCce(ccextractor, cc2Tmp.getAbsolutePath(), "srt", inFmt, "cc2", extractSec);
      if (cc2Ok && cc2Tmp.isFile() && cc2Tmp.length() > 8)
      {
        try
        {
          cleanSrtFile(cc2Tmp);
          if (cc2Tmp.length() > 8)
          {
            Files.move(cc2Tmp.toPath(), srtSpa.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            File cc2vTmp = new File(basePath + ".cc2-vtt.vtt");
            runCce(ccextractor, cc2vTmp.getAbsolutePath(), "webvtt", inFmt, "cc2", extractSec);
            if (cc2vTmp.isFile() && cc2vTmp.length() > 8)
            {
              Files.move(cc2vTmp.toPath(), vttSpa.toPath(),
                  StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            if (Sage.DBG) System.out.println("CaptionExtractionJob: wrote CC2 sidecar " + srtSpa +
                " (" + srtSpa.length() + " bytes)");
            convergeThroughCaptionEvent(srtSpa, "spa", CaptionEvent.SERVICE_CC2);
          }
        }
        catch (IOException e)
        {
          if (Sage.DBG) System.out.println("CaptionExtractionJob: cc2 rename failed: " + e);
        }
      }
      cc2Tmp.delete();
      deletePhantoms(basePath, "cc2-tmp");
      new File(basePath + ".cc2-vtt.vtt").delete();
      deletePhantoms(basePath, "cc2-vtt");
    }
  }

  /**
   * Run one ccextractor invocation. Output file is exactly {@code outPath}.
   * For -svc passes ccextractor appends {@code .p0.svc01} before the extension
   * (so caller should pre-account for that when inspecting outputs).
   * Returns true on exit code 0.
   */
  private boolean runCce(String ccextractor, String outPath, String outFmt,
      String inFmt, String channel, int seconds)
  {
    List<String> cmd = new ArrayList<>();
    // Wrap in nocache so ccextractor's sequential reads don't evict page cache
    // pages that the player and recorder are actively using. Then ionice (idle
    // class) + nice (lowest CPU priority) so extraction never starves playback.
    if (which("nocache"))
    {
      cmd.add("nocache");
    }
    if (which("ionice"))
    {
      cmd.add("ionice");
      cmd.add("-c");
      cmd.add("3");
      cmd.add("--");
      cmd.add("nice");
      cmd.add("-n");
      cmd.add("19");
    }
    cmd.add(ccextractor);
    if (inFmt != null) cmd.add(inFmt);
    cmd.add("-out=" + outFmt);
    if ("cc2".equals(channel)) cmd.add("-cc2");
    else if ("svc1".equals(channel)) { cmd.add("-svc"); cmd.add("1"); }
    if (seconds > 0)
    {
      cmd.add("-endat");
      cmd.add(secondsToHms(seconds));
    }
    cmd.add(recFile.getAbsolutePath());
    cmd.add("-o");
    cmd.add(outPath);

    if (Sage.DBG) System.out.println("CaptionExtractionJob: " + String.join(" ", cmd));
    StringBuilder err = new StringBuilder();
    try
    {
      ProcessBuilder pb = new ProcessBuilder(cmd);
      pb.redirectErrorStream(true);
      proc = pb.start();
      try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8)))
      {
        String line;
        while ((line = r.readLine()) != null)
          if (err.length() < 4096) err.append(line).append('\n');
      }
      int rc = proc.waitFor();
      if (cancelled) return false;
      // rc=2 means "user-defined limits reached" (we passed -endat) — the
      // file is fully written, just truncated to the requested duration.
      if (rc != 0 && rc != 2)
      {
        if (Sage.DBG) System.out.println("CaptionExtractionJob: ccextractor exit=" + rc + " for channel=" + channel + "\n" + err);
        return false;
      }
      return true;
    }
    catch (IOException | InterruptedException e)
    {
      if (Sage.DBG) System.out.println("CaptionExtractionJob: ccextractor error: " + e);
      return false;
    }
  }

  private static String secondsToHms(int s)
  {
    int h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
    return String.format("%02d:%02d:%02d", h, m, sec);
  }

  /**
   * Brief sleep between ccextractor passes to give the disk I/O scheduler
   * breathing room when a playback transcoder is running concurrently.
   * Default 3 seconds, tunable via caption_extraction/inter_pass_delay_ms.
   */
  private void throttlePause()
  {
    int ms = Sage.getInt("caption_extraction/inter_pass_delay_ms", 3000);
    if (ms <= 0) return;
    try { Thread.sleep(ms); } catch (InterruptedException e) { cancelled = true; }
  }

  /**
   * Delete phantom files produced by ccextractor when it auto-detects a 708
   * service. The phantom pattern is {@code <basePath>.<prefix>.pN.svcNN.<ext>}
   * where N varies. We scan the parent directory for matches.
   */
  private static void deletePhantoms(String basePath, String prefix)
  {
    File base = new File(basePath);
    File dir = base.getParentFile();
    if (dir == null || !dir.isDirectory()) return;
    String namePrefix = base.getName() + "." + prefix + ".";
    File[] phantoms = dir.listFiles((d, name) ->
        name.startsWith(namePrefix) && (name.endsWith(".srt") || name.endsWith(".vtt")));
    if (phantoms != null)
    {
      for (File f : phantoms) f.delete();
    }
  }

  /**
   * Find the phantom file produced by ccextractor for a given prefix and
   * extension. Returns the first matching {@code <basePath>.<prefix>.pN.svcNN.<ext>}
   * or null if none found.
   */
  private static File findPhantom(String basePath, String prefix, String ext)
  {
    File base = new File(basePath);
    File dir = base.getParentFile();
    if (dir == null || !dir.isDirectory()) return null;
    String namePrefix = base.getName() + "." + prefix + ".";
    String suffix = "." + ext;
    File[] matches = dir.listFiles((d, name) ->
        name.startsWith(namePrefix) && name.endsWith(suffix));
    if (matches != null && matches.length > 0) return matches[0];
    return null;
  }

  private static String ccextractorInputFlag(String fname)
  {
    String n = fname.toLowerCase();
    if (n.endsWith(".ts") || n.endsWith(".m2ts")) return "-ts";
    if (n.endsWith(".mpg") || n.endsWith(".mpeg") || n.endsWith(".vob")) return "-ps";
    if (n.endsWith(".mp4") || n.endsWith(".m4v") || n.endsWith(".mov")) return "-mp4";
    if (n.endsWith(".mkv") || n.endsWith(".webm")) return "-mkv";
    if (n.endsWith(".wtv")) return "-wtv";
    return null; // let ccextractor autodetect
  }

  private static String stripExt(String path)
  {
    int dot = path.lastIndexOf('.');
    return (dot > 0) ? path.substring(0, dot) : path;
  }

  private static boolean which(String binary)
  {
    if (binary == null || binary.isEmpty()) return false;
    // absolute path → check directly
    File f = new File(binary);
    if (f.isAbsolute()) return f.canExecute();
    // PATH lookup
    String path = System.getenv("PATH");
    if (path == null) return false;
    for (String dir : path.split(File.pathSeparator))
    {
      File c = new File(dir, binary);
      if (c.canExecute()) return true;
    }
    return false;
  }

  // ── ffmpeg fallback (CC1 only, no VTT, kept for environments without ccextractor) ──

  private void runFfmpegFallback()
  {
    File tmp = new File(sidecar.getAbsolutePath() + ".tmp");
    try
    {
      if (tmp.exists()) tmp.delete();

      String ffmpeg = Sage.get("caption_extraction/ffmpeg_path",
          sage.FFMPEGTranscoder.getTranscoderPath());
      int extractSec = Sage.getInt("caption_extraction/extract_seconds", 0);

      // The lavfi `movie=PATH[out0+subcc]` filter exposes a captions subtitle
      // stream synthesized from line-21 / SEI user data in the video track. We
      // map that synthesized stream to the SRT muxer.
      //
      // ffmpeg is picky about characters in the lavfi filter graph; escape
      // backslashes, single quotes, colons, and commas in the path.
      //
      // seek_point/output_ts_offset: HDHomeRun-tuned recordings (live 608/708
      // path only — this is the ffmpeg fallback, not ccextractor) start with a
      // few seconds of corrupt/incomplete tuner-startup frames that otherwise
      // make ffmpeg's subcc decode abort after ~5s and produce a near-empty
      // (~70 byte) SRT. Seeking the movie filter past that startup window
      // avoids the corrupt frames; output_ts_offset then shifts the resulting
      // cue timestamps back by the same amount so they still line up with
      // true media-relative time (0 = start of the recording), not the
      // post-seek decode position. Both default to 8s, tunable/disable-able
      // (0) via caption_extraction/ffmpeg_seek_seconds.
      String escapedPath = escapeForLavfi(recFile.getAbsolutePath());
      int seekSeconds = Sage.getInt("caption_extraction/ffmpeg_seek_seconds", 8);
      String filterInput = "movie=" + escapedPath +
          (seekSeconds > 0 ? ":seek_point=" + seekSeconds : "") + "[out0+subcc]";

      List<String> cmd = new ArrayList<>();
      // Wrap in ionice (idle class) + nice (lowest CPU priority) so extraction
      // never starves an active playback transcoder for disk or CPU time.
      if (which("ionice"))
      {
        cmd.add("ionice");
        cmd.add("-c");
        cmd.add("3");
        cmd.add("--");
        cmd.add("nice");
        cmd.add("-n");
        cmd.add("19");
      }
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
      if (seekSeconds > 0)
      {
        cmd.add("-output_ts_offset");
        cmd.add(Integer.toString(seekSeconds));
      }
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
      convergeThroughCaptionEvent(sidecar, "eng", CaptionEvent.SERVICE_CC1);
    }
    catch (IOException | InterruptedException e)
    {
      if (Sage.DBG) System.out.println("CaptionExtractionJob: error processing " + recFile + ": " + e);
      try { tmp.delete(); } catch (Throwable ignore) {}
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
   * Parses an already-written SRT sidecar back into {@link CaptionEvent}s
   * (tagged with {@code language}/{@code service}, since plain SRT carries
   * neither) and re-serializes it via {@link SrtCaptionWriter}, in place.
   *
   * <p>This is a deliberate round-trip no-op on cue content: its only
   * purpose is convergence — making {@link CaptionEvent} the canonical
   * in-memory/on-disk representation for <em>every</em> caption source
   * (ATSC1 608/708 here, ATSC3 STPP in {@link #runAtsc3StppIfPresent()}),
   * not just the ones that happen to produce it natively. The ccextractor
   * invocation, its 3 interdependent passes, and its sibling .vtt output
   * are completely unaffected — this only touches the already-finalized
   * SRT file after it's in place.
   *
   * <p>Failures here are non-fatal: the ccextractor-produced SRT is left
   * exactly as-is (it's already a valid, complete sidecar) and only a debug
   * line is logged, so a parsing edge case can never regress the working
   * 608/708 extraction.
   */
  private static void convergeThroughCaptionEvent(File srtFile, String language, String service)
  {
    try
    {
      List<CaptionEvent> events = SrtCaptionReader.read(srtFile, language, service);
      if (!events.isEmpty())
      {
        java.util.Collections.sort(events);
        new SrtCaptionWriter().write(events, srtFile);
      }
    }
    catch (IOException e)
    {
      if (Sage.DBG) System.out.println("CaptionExtractionJob: SRT->CaptionEvent round-trip failed for " +
          srtFile + ", leaving ccextractor output as-is: " + e);
    }
  }

  /**
   * Rewrite an SRT in place stripping ASS-style positioning ({\an?}),
   * \h hard-spaces, font-face wrappers, and any other {..} or unsupported
   * tags emitted by ffmpeg's lavfi subcc synthesizer. Italic tags (<i></i>)
   * are preserved because SageTV's SRT handler understands them.
   */
  static void cleanSrtFile(File f) throws IOException
  {
    // Use ISO_8859_1 to avoid MalformedInputException from ccextractor's
    // occasional non-UTF-8 bytes (e.g. raw CEA-708 data leaking through).
    // ISO_8859_1 is lossless for all byte values; the regex-based cleanSrtLine
    // strips any stray high-byte sequences anyway.
    java.util.List<String> lines = Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.ISO_8859_1);
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
    // Trim trailing whitespace — ccextractor pads cue lines out to ~32 cols.
    return rtrim(s);
  }

  private static String rtrim(String s)
  {
    int n = s.length();
    while (n > 0 && Character.isWhitespace(s.charAt(n - 1))) n--;
    return (n == s.length()) ? s : s.substring(0, n);
  }
}
