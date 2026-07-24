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
import java.util.Set;
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
 *   caption_extraction/ffmpeg_path           - ffmpeg binary (default: bundled
 *                                              FFMPEGTranscoder.getTranscoderPath(),
 *                                              i.e. /opt/sagetv/server/ffmpeg)
 *   caption_extraction/post_recording_delay_ms - pause after stop before run (default 5000)
 *   caption_extraction/extract_seconds       - cap extraction at N seconds (0 = whole file)
 *   caption_extraction/live_interval_ms      - tail re-extract period while recording is in
 *                                              progress (default 10000)
 *   caption_extraction/live_min_file_bytes   - skip live extract until source has at least N
 *                                              bytes on disk (default 524288)
 */
public class CaptionExtractionManager
{
  private static CaptionExtractionManager instance;

  private final ExecutorService threadPool;
  private final Map<Integer, CaptionExtractionJob> activeJobs = new ConcurrentHashMap<>();
  // MediaFile id -> live tail-extract loop for an in-progress recording.
  private final Map<Integer, LiveExtractor> liveExtractors = new ConcurrentHashMap<>();
  // MediaFile ids that had on-demand (partial) extraction during playback
  // and need a full extraction once playback stops.
  private final Set<Integer> pendingFullExtraction = ConcurrentHashMap.newKeySet();

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

  /**
   * Called from playback paths (e.g. VideoFrame.setCCState) when the user
   * enables CC on a recording that has no sidecar yet. Behaviour:
   *   - completed recording: one-shot job that overwrites any stale sidecar.
   *   - in-progress recording: starts (or attaches to) a tail-extract loop
   *     that re-runs ffmpeg every caption_extraction/live_interval_ms (default
   *     10s) until the recording stops, then does a final full pass.
   * The supplied onSidecarReady callback fires on the worker thread every
   * time a fresh sidecar is written (initial creation for one-shot, after
   * each successful tail pass for live), letting the caller reload its
   * SubtitleHandler. Returns false if extraction can't run (disabled,
   * missing file, etc.).
   */
  public boolean ensureExtractionRunning(MediaFile mf, Runnable onSidecarReady)
  {
    if (!isEnabled()) return false;
    if (mf == null) return false;
    File recFile = mf.getFile(0);
    if (recFile == null || !recFile.exists()) return false;

    if (mf.isRecording())
    {
      final int id = mf.getID();
      LiveExtractor existing = liveExtractors.get(id);
      if (existing != null)
      {
        existing.addListener(onSidecarReady);
        if (Sage.DBG) System.out.println("CaptionExtractionManager: attached listener to live extractor for MF " + id);
        return true;
      }
      LiveExtractor le = new LiveExtractor(mf, recFile);
      le.addListener(onSidecarReady);
      LiveExtractor prev = liveExtractors.putIfAbsent(id, le);
      if (prev != null)
      {
        // Race: someone else beat us. Attach to the winner.
        prev.addListener(onSidecarReady);
        return true;
      }
      Thread t = new Thread(le, "CaptionExtract-Live-" + id);
      t.setDaemon(true);
      t.setPriority(Thread.MIN_PRIORITY);
      t.start();
      if (Sage.DBG) System.out.println("CaptionExtractionManager: started live extractor for MF " + id + " (" + recFile.getName() + ")");
      return true;
    }

    // Completed recording: one-shot, force=true, onDemand=true to limit I/O during playback.
    // Mark for deferred full extraction when playback stops.
    pendingFullExtraction.add(mf.getID());
    submitJob(mf, recFile, true, onSidecarReady, true);
    return true;
  }

  /** Stop any live extractor for this MediaFile (no-op if not running). */
  public void stopLiveExtraction(MediaFile mf)
  {
    if (mf == null) return;
    LiveExtractor le = liveExtractors.remove(mf.getID());
    if (le != null) le.stop();
  }

  /**
   * Called when playback stops. If this file had an on-demand (partial)
   * extraction during playback, queue a full extraction now that the disk
   * is free. The full extraction overwrites the partial sidecar with
   * complete captions (all tracks, full duration).
   */
  public void onPlaybackStopped(MediaFile mf)
  {
    if (mf == null) return;
    final int id = mf.getID();
    if (!pendingFullExtraction.remove(id)) return;
    if (!isEnabled()) return;

    File recFile = mf.getFile(0);
    if (recFile == null || !recFile.exists()) return;

    int delayMs = Sage.getInt("caption_extraction/post_playback_delay_ms", 10000);
    if (Sage.DBG) System.out.println("CaptionExtractionManager: scheduling full extraction for MF " + id + " in " + delayMs + "ms (playback stopped)");
    Pooler.execute(() -> {
      try { Thread.sleep(delayMs); } catch (InterruptedException e) { return; }
      // force=true to overwrite the partial sidecar, onDemand=false for full extraction
      submitJob(mf, recFile, true, null, false);
    });
  }

  // ── Internal ──

  private void submitJob(MediaFile mf, File recFile, boolean force)
  {
    submitJob(mf, recFile, force, null, false);
  }

  private void submitJob(MediaFile mf, File recFile, boolean force, Runnable extraCallback)
  {
    submitJob(mf, recFile, force, extraCallback, false);
  }

  private void submitJob(MediaFile mf, File recFile, boolean force, Runnable extraCallback, boolean onDemand)
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
      if (extraCallback != null)
      {
        try { extraCallback.run(); } catch (Throwable ignore) {}
      }
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
      if (extraCallback != null)
      {
        try { extraCallback.run(); } catch (Throwable ignore) {}
      }
    }, onDemand);
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

  /**
   * Tail-extract loop for an in-progress recording: wakes every
   * caption_extraction/live_interval_ms (default 10s), runs a forced one-shot
   * extract on the growing source file, fires listeners after each successful
   * pass so VideoFrame can reload its SubtitleHandler. Exits and does one
   * final full pass once the MediaFile reports !isRecording().
   */
  private class LiveExtractor implements Runnable
  {
    private final MediaFile mf;
    private final File recFile;
    private final java.util.List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean stopped;

    LiveExtractor(MediaFile mf, File recFile)
    {
      this.mf = mf;
      this.recFile = recFile;
    }

    void addListener(Runnable r)
    {
      if (r != null) listeners.add(r);
    }

    void stop()
    {
      stopped = true;
    }

    private void fireListeners()
    {
      for (Runnable r : listeners)
      {
        try { r.run(); } catch (Throwable ignore) {}
      }
    }

    @Override
    public void run()
    {
      long intervalMs = Math.max(2000L, Sage.getLong("caption_extraction/live_interval_ms", 10000L));
      long minBytes = Math.max(0L, Sage.getLong("caption_extraction/live_min_file_bytes", 524288L));
      final int id = mf.getID();
      try
      {
        while (!stopped && mf.isRecording())
        {
          try { Thread.sleep(intervalMs); } catch (InterruptedException e) { return; }
          if (stopped) return;
          if (!recFile.exists() || recFile.length() < minBytes) continue;
          runOnce();
        }
        // Recording finished while we were running: do one final clean pass.
        if (!stopped && recFile.exists())
        {
          // Brief delay to let the writer flush.
          long finalDelay = Math.max(0L, Sage.getLong("caption_extraction/post_recording_delay_ms", 5000L));
          try { Thread.sleep(finalDelay); } catch (InterruptedException ignore) {}
          runOnce();
        }
      }
      finally
      {
        liveExtractors.remove(id, this);
        if (Sage.DBG) System.out.println("CaptionExtractionManager: live extractor finished for MF " + id);
      }
    }

    /** Run a synchronous extract pass on the current file state and fire listeners on success. */
    private void runOnce()
    {
      File sidecar = sidecarFor(recFile);
      File tmpScratch = new File(sidecar.getAbsolutePath() + ".live.tmp");
      // Use a one-off job that writes directly to the real sidecar path (force=true).
      // We deliberately do NOT route through submitJob() to avoid contending with
      // the activeJobs map (which a recording-stop hook might also touch).
      final boolean[] ok = { false };
      CaptionExtractionJob job = new CaptionExtractionJob(mf, recFile, sidecar, () -> {
        if (sidecar.isFile() && sidecar.length() > 8) ok[0] = true;
      });

      // Piece C v1 (bootstrap-gap push): while no usable sidecar exists yet
      // for this recording, wire the STPP-only live-event hook so any active
      // VideoFrame playing this in-progress recording gets captions in
      // memory before the first sidecar/SubtitleHandler shows up. Once a
      // real sidecar exists, the existing reload-and-replace mechanism
      // (fireListeners() below, already wired to
      // VideoFrame.reloadExternalSubHandlerAndApplyCC) is what actually
      // drives display from then on, so we stop bothering to wire this.
      // VideoFrame.postCaptionEvents() is itself idempotent (no-ops once its
      // subHandler is non-null), so this is safe even if called every cycle.
      if (!(sidecar.isFile() && sidecar.length() > 8))
      {
        job.setLiveEventSink(events -> pushBootstrapCaptionEvents(mf, events));
      }

      job.run();
      if (tmpScratch.exists()) tmpScratch.delete();
      if (ok[0])
      {
        try { mf.checkForSubtitles(); } catch (Throwable ignore) {}
        fireListeners();
      }
    }
  }

  /**
   * Piece C v1 helper: filters a raw coalesced CaptionEvent list down to the
   * primary/default service only (the track a viewer would see by default;
   * see {@link SrtCaptionWriter#isPrimaryService(String)}) and drops the
   * final cue, which may still be revised (extended text/end time) on the
   * next live extraction pass — pushing it now risks displaying a cue that
   * then silently changes underneath the viewer. The remainder, if any, is
   * pushed to every {@link sage.VideoFrame} currently playing {@code mf} via
   * the additive {@code VideoFrame.postCaptionEvents} hook.
   */
  private static void pushBootstrapCaptionEvents(MediaFile mf, java.util.List<CaptionEvent> events)
  {
    if (events == null || events.size() < 2) return; // nothing safe to push yet (lag-by-one)

    java.util.List<CaptionEvent> primary = new java.util.ArrayList<>();
    for (int i = 0; i < events.size() - 1; i++)
    {
      CaptionEvent e = events.get(i);
      if (SrtCaptionWriter.isPrimaryService(e.getService()))
        primary.add(e);
    }
    if (primary.isEmpty()) return;

    java.util.ArrayList vfs = sage.VideoFrame.getVFsUsingMediaFile(mf);
    for (int i = 0; i < vfs.size(); i++)
    {
      try
      {
        ((sage.VideoFrame) vfs.get(i)).postCaptionEvents(primary);
      }
      catch (Throwable ignore) {}
    }
  }
}
