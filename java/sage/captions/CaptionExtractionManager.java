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
import sage.MediaPlayer;
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
 *   caption_extraction/live_buffer_max_cues  - safety-ceiling backstop on the in-memory live
 *                                              caption list per stream, drop-oldest beyond it
 *                                              (default 10000)
 *   caption_extraction/srt_flush_interval_ms - Piece C v2: for an in-progress KEEPER recording
 *                                              only, minimum time between periodic sidecar
 *                                              flushes while live extraction is pushing
 *                                              captions in-memory every cycle (default 30000).
 *                                              Never applies to ephemeral live buffers, which
 *                                              are never flushed until/unless promoted.
 *   caption_extraction/srt_flush_cue_count   - companion to the above: also flush once at
 *                                              least this many new cues have accumulated since
 *                                              the last flush, whichever comes first (default 50)
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
  // Piece C v2: MediaFile ids for which the in-memory live push
  // (VideoFrame.postCaptionEvents) currently owns caption display for every
  // viewer of that recording. While a file's id is in this set,
  // VideoFrame.reloadExternalSubHandlerAndApplyCC() is a no-op for it, so
  // the normal sidecar-reload path never fights the push (single source of
  // truth). Int-keyed (mf.getID()), not keyed by MediaFile object, so this
  // set can never itself pin a MediaFile in memory. Added/removed every
  // LiveExtractor cycle based on live viewer state -- never left stale.
  private final Set<Integer> liveCaptionPushActive = ConcurrentHashMap.newKeySet();

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

  /**
   * Piece C v2 ephemeral-vs-keeper classification. {@code true} means this
   * MediaFile is still a temporary, likely-to-be-discarded live/timeshift
   * buffer: safe to render captions purely in-memory and never write an SRT
   * sidecar. {@code false} means it's a real (or now-promoted) recording
   * that should get the exact same on-disk sidecar behavior as today.
   *
   * <p>{@code MediaFile.generalType} (what {@code isLiveBufferedStream()}
   * checks) never changes away from {@code MEDIAFILE_LIVE_BUFFERED_STREAM}
   * even after a live buffer is promoted to a keeper (user hits record, or
   * it converts into a scheduled recording) -- so that alone can't detect
   * promotion. The actual signal SageTV flips at promotion time is
   * {@code acquisitionTech} (see every {@code setAcquisitionTech(...)} call
   * site): it starts at {@code ACQUISITION_WATCH_BUFFER} while still just a
   * live buffer, and moves to {@code ACQUISITION_MANUAL} /
   * {@code ACQUISITION_FAVORITE} / {@code ACQUISITION_INTELLIGENT} once it's
   * a real recording. There is no dedicated "buffer became permanent" event
   * anywhere in {@code PluginEventManager} to hook, so this is deliberately
   * polled once per {@link LiveExtractor} cycle rather than pushed.
   *
   * <p>Only {@code MEDIAFILE_LIVE_BUFFERED_STREAM} files are ever
   * classified ephemeral; a plain completed/scheduled recording (the
   * overwhelmingly common case) is never ephemeral, matching "keeper
   * behavior unchanged".
   */
  static boolean isEphemeral(MediaFile mf)
  {
    if (mf == null) return false;
    return mf.isLiveBufferedStream() && mf.getAcquistionTech() == MediaFile.ACQUISITION_WATCH_BUFFER;
  }

  /**
   * Piece C v2 single-owner gate. Returns true while the in-memory live
   * caption push ({@code VideoFrame.postCaptionEvents}) owns display for
   * every current viewer of this MediaFile -- see {@link LiveExtractor}.
   * While true, {@code VideoFrame.reloadExternalSubHandlerAndApplyCC} must
   * treat itself as a no-op for this file so there is never more than one
   * active caption source. Backed by an int-keyed set (mf.getID()), so it
   * can never itself pin a MediaFile in memory.
   */
  public boolean isLiveCaptionPushActive(MediaFile mf)
  {
    return mf != null && liveCaptionPushActive.contains(mf.getID());
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
    // Pure live streams (no seekable backing file, e.g. raw passthrough) --
    // never anything to extract from. Unchanged from before.
    if (mf.isLiveStream()) return;
    // Live-buffered (timeshift) streams: MediaFile.generalType never changes
    // away from MEDIAFILE_LIVE_BUFFERED_STREAM even after the buffer is
    // promoted to a keeper (user hit record / it converted to a scheduled
    // recording) -- isAnyLiveStream() alone can't distinguish "still an
    // ephemeral live buffer" from "was a live buffer, now a real recording".
    // Only skip here while still ephemeral; once promoted, fall through to
    // the normal one-shot extraction path below like any other recording.
    // (Piece C v2's LiveExtractor also does its own promotion catch-up
    // write the moment it detects the flip, so this is a second/backup
    // durability net, not the only path -- see LiveExtractor.runOnce().)
    if (mf.isLiveBufferedStream() && isEphemeral(mf)) return;

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
   * Tail-extract loop for an in-progress recording (live-buffer or normal
   * scheduled/manual recording that's still writing). Wakes every
   * caption_extraction/live_interval_ms (default 10s) and re-derives the
   * current caption set from the growing source file.
   *
   * <p>Piece C v2: this class is now also the single owner of the whole
   * live-caption delivery + persistence-policy seam for a MediaFile:
   * <ul>
   *   <li>Exactly one {@code LiveExtractor} (and one ffmpeg cycle) exists
   *       per MediaFile regardless of viewer count -- already guaranteed by
   *       {@link #ensureExtractionRunning}'s {@code liveExtractors}
   *       putIfAbsent/addListener logic (unchanged).</li>
   *   <li>Each cycle, the current set of CC-enabled viewers of this exact
   *       file ({@link sage.VideoFrame#getVFsUsingMediaFile}, filtered to
   *       {@code getCCState() != CC_DISABLED}) is polled fresh -- no extra
   *       hooks needed, since both of those already reflect reality
   *       immediately (see class javadoc references below).</li>
   *   <li>Per-viewer delivery high-water-marks ({@link #perViewerHwm}) make
   *       sure a viewer that enables CC late still gets every earlier cue
   *       (backfill), while an already-caught-up viewer only gets the new
   *       delta -- fixing the "per-MediaFile HWM misses late joiners" bug.
   *       The map is pruned to exactly the current eligible set every
   *       cycle, so it can never grow past the current viewer count.</li>
   *   <li>SRT persistence is fully decoupled from both extraction and
   *       display: never written for an ephemeral live buffer (see
   *       {@link CaptionExtractionManager#isEphemeral}); written on a rare
   *       cadence (every {@code srt_flush_interval_ms} or
   *       {@code srt_flush_cue_count} new cues, whichever first) for an
   *       in-progress keeper; and a fresh full catch-up write fires exactly
   *       once at the ephemeral-&gt;keeper promotion instant.</li>
   *   <li>Zero-consumer teardown: the moment no eligible viewer remains,
   *       this extractor flushes once (if a keeper had unpersisted cues)
   *       and stops itself -- see {@link #runOnce}.</li>
   * </ul>
   * Exits (and does one final full pass) once the MediaFile reports
   * {@code !isRecording()} in the ordinary case, same as before.
   */
  private class LiveExtractor implements Runnable
  {
    private final MediaFile mf;
    private final File recFile;
    private final java.util.List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean stopped;

    // Piece C v2 per-viewer delivery high-water-mark: for each currently
    // eligible VideoFrame, the end time (seconds) of the last CaptionEvent
    // already pushed to it. Read/written only from this extractor's own
    // thread (single-threaded access -- runOnce() is never invoked
    // concurrently with itself), so no external synchronization is needed.
    // Pruned to the live eligible-viewer set every cycle in runOnce()
    // (perViewerHwm.keySet().retainAll(eligible)), so under normal
    // operation it never holds an entry for a VideoFrame that's since
    // closed/changed files/disabled CC. WeakHashMap is a belt-and-suspenders
    // backstop on top of that explicit pruning: even if a VideoFrame were
    // somehow dropped without going through a pruning cycle (e.g. this
    // LiveExtractor itself gets torn down first), a VideoFrame key can
    // still be GC'd and never pins this map's memory.
    private final java.util.Map<sage.VideoFrame, Double> perViewerHwm = new java.util.WeakHashMap<>();

    // Most recent full coalesced event list (already capped to
    // live_buffer_max_cues), kept only for the zero-consumer durability
    // flush in runOnce(). Not a separately-growing accumulation: today's
    // extraction is a full rescan every cycle, so this is simply replaced
    // (not appended to) each time -- see runOnce()'s doc for how this seam
    // stays valid once the incremental extractor is wired in later.
    private volatile java.util.List<CaptionEvent> lastEvents = new java.util.ArrayList<>();
    private boolean wasEphemeral = true;
    private long lastSrtFlushMs;
    private int cuesAtLastFlush;

    // Incremental-extraction integration: one persistent cursor/accumulator
    // for this extractor's entire life, so each cycle's ffmpeg pass costs
    // roughly what's new since the last pass instead of rescanning the whole
    // recording. Created with this LiveExtractor (first-viewer/CC-enable)
    // and freed simply by falling out of scope on teardown (this instance is
    // discarded when perViewerHwm/lastEvents are, and liveExtractors.remove
    // in run()'s finally drops the last reference) -- no separate
    // create/free bookkeeping needed. STPP-only; the 608/708 fallback path
    // never touches this and stays on full-rescan regardless.
    private final Atsc3StppExtractor.StppIncrementalState stppState =
        new Atsc3StppExtractor.StppIncrementalState();

    // Set once at the top of run(); used only to size the sleep between
    // cycles.
    private volatile long intervalMs = 10000L;

    // Lag-by-one tail-release tracking (see pushDeltaToViewers): identity
    // key of the tail cue as of the last cycle it was seen. Single-threaded
    // field -- runOnce() (hence pushDeltaToViewers()) is only ever invoked
    // from this extractor's own run() loop, never concurrently.
    private String pendingTailKey;

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
      intervalMs = Math.max(2000L, Sage.getLong("caption_extraction/live_interval_ms", 10000L));
      long minBytes = Math.max(0L, Sage.getLong("caption_extraction/live_min_file_bytes", 524288L));
      final int id = mf.getID();
      try
      {
        // Run the very first cycle immediately (subject only to the
        // min-file-bytes gate) instead of sleeping a full interval first.
        // Live-buffer MediaFiles for some capture methods (e.g. ATSC3
        // HTTP-pull's provisional-format-refinement/subfile churn) can be
        // torn down and replaced with a fresh MediaFile id within a single
        // interval window; sleeping first meant a fast-churning MediaFile
        // could have its LiveExtractor start and stop without runOnce()
        // ever executing even once, so the STPP in-memory push never got a
        // chance to run at all. Subsequent cycles still wait the full
        // interval as before.
        while (!stopped && mf.isRecording())
        {
          if (recFile.exists() && recFile.length() >= minBytes)
            runOnce(false);
          if (stopped) return;
          try { Thread.sleep(intervalMs); } catch (InterruptedException e) { return; }
        }
        // Recording finished while we were running: do one final,
        // forced-write pass so a keeper's on-disk SRT is authoritative and
        // byte-identical to today's (pre-v2) behavior at recording-stop --
        // it must NOT wait for the next periodic flush-cadence trigger,
        // since this is the last cycle this LiveExtractor will ever run.
        // Skipped if runOnce() already stopped us (zero-consumer teardown,
        // which already did its own forced durability flush) or if an
        // explicit stopLiveExtraction() call set `stopped`.
        if (!stopped && recFile.exists())
        {
          // Brief delay to let the writer flush.
          long finalDelay = Math.max(0L, Sage.getLong("caption_extraction/post_recording_delay_ms", 5000L));
          try { Thread.sleep(finalDelay); } catch (InterruptedException ignore) {}
          runOnce(true);
        }
      }
      finally
      {
        liveExtractors.remove(id, this);
        liveCaptionPushActive.remove(id);
        if (Sage.DBG) System.out.println("CaptionExtractionManager: live extractor finished for MF " + id);
      }
    }

    /**
     * Run a synchronous extract pass on the current file state, push
     * per-viewer deltas, and apply the decoupled SRT-flush policy. See the
     * class javadoc for the full picture; this is the method where each
     * piece is actually wired together every cycle.
     *
     * @param forceFlush true only for the single final pass run right
     *                    after the recording has stopped (see run()):
     *                    bypasses the periodic flush-interval/cue-count
     *                    cadence so a keeper's last write is authoritative
     *                    or -- if a keeper never got its rare cadence
     *                    trigger before recording-stop, its final on-disk
     *                    SRT would otherwise be missing the last cycle's
     *                    cues. Never overrides the ephemeral no-write rule.
     */
    private void runOnce(boolean forceFlush)
    {
      // Piece C v2 Gap 1/2: poll the ground-truth eligible-viewer set fresh
      // every cycle. getVFsUsingMediaFile() already only returns
      // VideoFrames whose currFile == mf and whose player is actually
      // active; getCCState() reflects a CC-disable the instant
      // VideoFrame.setCCState() writes it. No new close/disable hooks are
      // needed on the VideoFrame side for this to be correct and current.
      java.util.ArrayList vfsRaw = sage.VideoFrame.getVFsUsingMediaFile(mf);
      java.util.List<sage.VideoFrame> eligible = new java.util.ArrayList<>(vfsRaw.size());
      for (Object o : vfsRaw)
      {
        sage.VideoFrame vf = (sage.VideoFrame) o;
        if (vf.getCCState() != MediaPlayer.CC_DISABLED) eligible.add(vf);
      }

      boolean ephemeralNow = isEphemeral(mf);

      if (eligible.isEmpty())
      {
        // Gap 2: zero-consumer teardown. Free every resource tied to this
        // MediaFile's id: the perViewerHwm map is discarded with `this`
        // (about to fall out of scope), the liveExtractors map entry is
        // removed by run()'s finally block, and the single-owner push gate
        // is cleared right here.
        if (!ephemeralNow && !lastEvents.isEmpty())
        {
          // Durability net: a keeper recording had push-only cues that
          // hadn't hit the rare flush cadence yet. Write them now instead
          // of waiting for the next viewer to reopen the file (which would
          // just force a fresh extraction anyway -- captions are always
          // re-derivable from the recording, so nothing is ever truly at
          // risk of being lost, but this keeps the sidecar current).
          try
          {
            new SrtCaptionWriter().writeGrouped(lastEvents, sidecarFor(recFile));
            mf.checkForSubtitles();
          }
          catch (Throwable ignore) {}
        }
        liveCaptionPushActive.remove(mf.getID());
        stop();
        return;
      }

      // Shrink the per-viewer map to exactly today's eligible set.
      perViewerHwm.keySet().retainAll(eligible);

      File sidecar = sidecarFor(recFile);
      final java.util.List<CaptionEvent>[] holder = new java.util.List[1];
      CaptionExtractionJob job = new CaptionExtractionJob(mf, recFile, sidecar, () -> {});
      // Buffer-fill is extraction-method-agnostic: the job internally
      // reconstructs "current authoritative full list" whether it's doing a
      // full rescan or an incremental pass against stppState (see
      // CaptionExtractionJob.runAtsc3StppIfPresent()) -- everything
      // downstream here (capping, per-viewer delta math, flush cadence)
      // already treats `events` as that full list and needed no changes for
      // this swap.
      job.setLiveEventSink(events -> holder[0] = events);
      // This LiveExtractor -- not the job -- owns persistence timing.
      job.setPersistSidecar(false);
      // Config escape hatch: default on, but flippable back to the original
      // full-rescan-every-cycle behavior instantly (no code change, no
      // restart of anything else) if the incremental path ever needs to be
      // ruled out during the live smoke test.
      if (Sage.getBoolean("caption_extraction/stpp_incremental", true))
        job.setIncrementalState(stppState);
      job.run();

      java.util.List<CaptionEvent> events = holder[0];
      if (events == null)
      {
        // This cycle produced no in-memory STPP push (either it took the
        // legacy 608/708 ffmpeg/subcc fallback, which writes its own sidecar
        // and expects VideoFrame's normal reload to pick it up, or it's an
        // STPP file with no cues extracted yet). Either way, nothing is
        // being pushed to viewers right now, so the single-owner gate must
        // not be held for this mf -- clear it so
        // VideoFrame.reloadExternalSubHandlerAndApplyCC() is free to run its
        // normal sidecar-reload path instead of silently no-op'ing.
        liveCaptionPushActive.remove(mf.getID());
        return;
      }

      // Genuine STPP cues were delivered via the in-memory push this cycle:
      // claim single-owner status so VideoFrame's legacy reload backs off
      // and doesn't install a competing sidecar-backed handler that would
      // fight (or silently lose to) this push's own append cadence.
      liveCaptionPushActive.add(mf.getID());

      // Safety-ceiling backstop (drop-oldest) against pathological 24/7 live
      // sessions, independent of the lifecycle teardown above.
      int maxCues = Math.max(100, Sage.getInt("caption_extraction/live_buffer_max_cues", 10000));
      if (events.size() > maxCues)
        events = events.subList(events.size() - maxCues, events.size());
      lastEvents = events;

      // Promotion detection: ephemeral -> keeper transition since last cycle.
      boolean promoted = wasEphemeral && !ephemeralNow;
      wasEphemeral = ephemeralNow;

      pushDeltaToViewers(events, eligible);

      if (promoted)
      {
        // Gap 4: persist using *this* cycle's already-fresh full-rescan
        // `events` -- equivalent to "a fresh full extract() right now" (not
        // a separately-tracked, possibly-stale accumulation), since
        // Atsc3StppExtractor.extract() always does a complete rescan today.
        // This makes the on-disk SRT byte-identical to today's keeper
        // output at the moment of promotion.
        try
        {
          new SrtCaptionWriter().writeGrouped(events, sidecar);
          lastSrtFlushMs = System.currentTimeMillis();
          cuesAtLastFlush = events.size();
          mf.checkForSubtitles();
          fireListeners();
        }
        catch (Throwable ignore) {}
      }
      else if (!ephemeralNow)
      {
        long flushIntervalMs = Math.max(1000L, Sage.getLong("caption_extraction/srt_flush_interval_ms", 30000L));
        int flushCueCount = Math.max(1, Sage.getInt("caption_extraction/srt_flush_cue_count", 50));
        long now = System.currentTimeMillis();
        boolean cadenceHit = now - lastSrtFlushMs >= flushIntervalMs || (events.size() - cuesAtLastFlush) >= flushCueCount;
        if (forceFlush || cadenceHit)
        {
          try
          {
            new SrtCaptionWriter().writeGrouped(events, sidecar);
            lastSrtFlushMs = now;
            cuesAtLastFlush = events.size();
            mf.checkForSubtitles();
            fireListeners();
          }
          catch (Throwable ignore) {}
        }
      }
      // else: still ephemeral -- push-only, never write, even if forceFlush
      // (a never-promoted live buffer's final pass writes nothing, exactly
      // as the ephemeral-suppression policy requires).
    }

    /**
     * Push each eligible viewer only the primary-track cues it hasn't
     * already received (Gap 1 fix): a newly-attached/late-CC-enabled
     * VideoFrame starts at HWM 0 and gets the full backfill of everything
     * extracted so far; an already-caught-up viewer only gets new cues.
     * <p>
     * Lags the single latest (tail) cue by one, since either extraction mode
     * may still revise it on the very next pass: a full rescan can extend a
     * still-open sentence's text/end time, and the incremental path's
     * `provisionalTail` is explicitly still-open by definition. The tail is
     * released once a SUBSEQUENT extraction cycle has actually completed and
     * reproduced the exact same (begin, end, text) for it -- i.e. that
     * cycle's fresh pass over the source had the opportunity to revise the
     * cue and didn't, so it cannot be revised any further. This is a cursor
     * on extraction progress, not a wall-clock timer: it needs no mapping
     * between media time and real time (which would be fragile for a live
     * stream's anchor), and it naturally scales with however often runOnce()
     * actually executes. Without this, a stream that only ever produces a
     * single coalesced cue (or goes quiet after one) would withhold that cue
     * forever: safeCount would stay 0 and postCaptionEvents would never fire
     * for it, exactly producing "showed once [via some other path], then
     * never again".
     */
    private void pushDeltaToViewers(java.util.List<CaptionEvent> events, java.util.List<sage.VideoFrame> eligible)
    {
      int safeCount = events.size() - 1; // lag-by-one
      if (!events.isEmpty())
      {
        CaptionEvent tail = events.get(events.size() - 1);
        String key = tail.getBeginSeconds() + "|" + tail.getEndSeconds() + "|" + tail.getText();
        if (key.equals(pendingTailKey))
        {
          // A prior cycle already saw this exact tail and a subsequent
          // extraction pass (this one) has now completed without revising
          // it -- it cannot change further, safe to release even though
          // it's the only/last cue.
          safeCount = events.size();
        }
        else
        {
          pendingTailKey = key;
        }
      }
      if (safeCount <= 0) return;

      for (sage.VideoFrame vf : eligible)
      {
        Double hwmBoxed = perViewerHwm.get(vf);
        double hwm = hwmBoxed == null ? 0.0 : hwmBoxed;
        java.util.List<CaptionEvent> delta = new java.util.ArrayList<>();
        double newHwm = hwm;
        for (int i = 0; i < safeCount; i++)
        {
          CaptionEvent e = events.get(i);
          if (!SrtCaptionWriter.isPrimaryService(e.getService())) continue;
          if (e.getBeginSeconds() < hwm) continue; // already delivered to this viewer
          delta.add(e);
          if (e.getEndSeconds() > newHwm) newHwm = e.getEndSeconds();
        }
        if (!delta.isEmpty())
        {
          try { vf.postCaptionEvents(delta); } catch (Throwable ignore) {}
          perViewerHwm.put(vf, newHwm);
        }
      }
    }
  }
}
