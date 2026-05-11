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
import sage.Pooler;
import sage.Sage;
import sage.Wizard;
import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Singleton service that extracts EIA-608 / CEA-708 closed captions from
 * recordings into a sidecar `.srt` file. Once written, the existing SageTV
 * external-subtitle pipeline (FormatParser.updateExternalSubs +
 * SubtitleHandler.createSubtitleHandler) renders them transparently on every
 * client (DShow, MiniClient, HD200/HD300 extender, PlaceShifter).
 *
 * Extraction is performed by ffmpeg's lavfi `subcc` filter, which handles both
 * legacy line-21 EIA-608 (MPEG-2 SD) and modern CEA-708 in H.264/H.265 SEI
 * (digital ATSC).
 *
 * Sidecar naming: `<recording-basename>.srt`. If a `.srt` already exists (user-
 * supplied or previously extracted), we leave it alone unless `force` is set.
 *
 * Config keys (Sage.properties):
 *   caption_extraction/enabled               - master switch (default true)
 *   caption_extraction/run_on_recording_stop - auto-extract at recording end (default true)
 *   caption_extraction/max_concurrent_jobs   - thread pool size (default 1)
 *   caption_extraction/ffmpeg_path           - ffmpeg binary (default "ffmpeg")
 *   caption_extraction/post_recording_delay_ms - pause after stop before run (default 5000)
 *   caption_extraction/extract_seconds       - cap extraction at N seconds (0 = whole file)
 */
public class CaptionExtractionManager
{
  private static CaptionExtractionManager instance;

  private final ExecutorService threadPool;
  private final Map<Integer, CaptionExtractionJob> activeJobs = new ConcurrentHashMap<>();

  private CaptionExtractionManager()
  {
    int maxJobs = Sage.getInt("caption_extraction/max_concurrent_jobs", 1);
    threadPool = Executors.newFixedThreadPool(maxJobs, r -> {
      Thread t = new Thread(r, "CaptionExtract-Worker");
      t.setDaemon(true);
      t.setPriority(Thread.MIN_PRIORITY);
      return t;
    });
    if (Sage.DBG) System.out.println("CaptionExtractionManager initialized, maxConcurrent=" + maxJobs);
  }

  public static synchronized CaptionExtractionManager getInstance()
  {
    if (instance == null)
      instance = new CaptionExtractionManager();
    return instance;
  }

  public boolean isEnabled()
  {
    return Sage.getBoolean("caption_extraction/enabled", true);
  }

  // ── Recording lifecycle hooks (called from Seeker) ──

  /**
   * Called when a recording stops. Schedules caption extraction after a brief
   * delay to let the file fully finalize on disk.
   */
  public void onRecordingStopped(MediaFile mf)
  {
    if (!isEnabled()) return;
    if (!Sage.getBoolean("caption_extraction/run_on_recording_stop", true)) return;
    if (mf == null) return;
    if (mf.isAnyLiveStream()) return;

    final int id = mf.getID();
    if (activeJobs.containsKey(id)) return;

    File recFile = mf.getFile(0);
    if (recFile == null) return;

    File sidecar = sidecarFor(recFile);
    if (sidecar.exists() && sidecar.length() > 0)
    {
      if (Sage.DBG) System.out.println("CaptionExtractionManager: sidecar already present, skipping " + sidecar);
      return;
    }

    int delayMs = Sage.getInt("caption_extraction/post_recording_delay_ms", 5000);
    if (Sage.DBG) System.out.println("CaptionExtractionManager: scheduling extraction for MF " + id + " in " + delayMs + "ms");
    Pooler.execute(() -> {
      try { Thread.sleep(delayMs); } catch (InterruptedException e) { return; }
      submitJob(mf, recFile, false);
    });
  }

  // ── Public API for STV / CaptionsAPI ──

  /**
   * Manually extract captions from a media file. If a sidecar exists, it is
   * overwritten.
   */
  public void runNow(MediaFile mf)
  {
    if (mf == null) return;
    File recFile = mf.getFile(0);
    if (recFile == null || !recFile.exists()) return;
    submitJob(mf, recFile, true);
  }

  /**
   * Returns true if a non-empty caption sidecar exists for this media file.
   */
  public boolean hasCaptions(MediaFile mf)
  {
    if (mf == null) return false;
    File recFile = mf.getFile(0);
    if (recFile == null) return false;
    File s = sidecarFor(recFile);
    return s.isFile() && s.length() > 0;
  }

  /**
   * Delete the caption sidecar.
   */
  public boolean clearCaptions(MediaFile mf)
  {
    if (mf == null) return false;
    File recFile = mf.getFile(0);
    if (recFile == null) return false;
    File s = sidecarFor(recFile);
    if (s.isFile()) return s.delete();
    return false;
  }

  /**
   * Scan all TV recordings and queue extraction for any without a sidecar.
   * Returns the number of jobs queued.
   */
  public int backfillAll(boolean force)
  {
    int queued = 0;
    Wizard wiz = Wizard.getInstance();
    if (wiz == null) return 0;
    MediaFile[] all = wiz.getFiles();
    if (all == null) return 0;
    for (MediaFile mf : all)
    {
      if (mf == null) continue;
      if (!mf.isTV()) continue;
      if (mf.isRecording()) continue;
      File recFile = mf.getFile(0);
      if (recFile == null || !recFile.exists()) continue;
      if (!force && hasCaptions(mf)) continue;
      submitJob(mf, recFile, force);
      queued++;
    }
    if (Sage.DBG) System.out.println("CaptionExtractionManager: backfill queued " + queued + " jobs (force=" + force + ")");
    return queued;
  }

  // ── Internal ──

  private void submitJob(MediaFile mf, File recFile, boolean force)
  {
    if (mf == null || recFile == null) return;
    final int id = mf.getID();
    if (activeJobs.containsKey(id))
    {
      if (Sage.DBG) System.out.println("CaptionExtractionManager: job already active for MF " + id);
      return;
    }
    File sidecar = sidecarFor(recFile);
    if (!force && sidecar.isFile() && sidecar.length() > 0)
    {
      if (Sage.DBG) System.out.println("CaptionExtractionManager: sidecar exists, skipping (use force=true to re-extract): " + sidecar);
      return;
    }
    CaptionExtractionJob job = new CaptionExtractionJob(mf, recFile, sidecar, () -> {
      activeJobs.remove(id);
      // Re-scan external subtitles so the player picks up the new sidecar without
      // a metadata refresh round-trip.
      try
      {
        mf.checkForSubtitles();
      }
      catch (Throwable t)
      {
        if (Sage.DBG) System.out.println("CaptionExtractionManager: post-extraction sub re-scan failed: " + t);
      }
    });
    activeJobs.put(id, job);
    threadPool.submit(job);
  }

  /** Returns the canonical sidecar path: `<recording>.srt` next to the source file. */
  public static File sidecarFor(File recFile)
  {
    if (recFile == null) return null;
    String p = recFile.getAbsolutePath();
    int dot = p.lastIndexOf('.');
    String base = (dot > 0) ? p.substring(0, dot) : p;
    return new File(base + ".srt");
  }
}
