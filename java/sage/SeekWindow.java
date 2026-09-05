/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sage;

/**
 * Shared, client-agnostic authority for "how far into a physical media file may
 * a seek land." Every seek path -- the PWA/MSE pull-xcode {@code -ss}, the push
 * DVR clamp, native trick-play, HLS resume -- should ultimately resolve its
 * target through this class so they stop each carrying a private, subtly
 * different notion of "the end."
 *
 * <h2>Why this exists</h2>
 * A client that resumes "where I left off" derives its position from a
 * <em>wall-clock</em> watch span (e.g. {@code WatchEnd - WatchStart}). On a
 * recording that has just <em>ended</em>, that value is structurally ~1 s longer
 * than the last demuxable frame, because the recorder's own bookkeeping
 * ({@link MediaFile#getEnd(int)} returns {@code Sage.time()} while recording,
 * and even a finished segment's {@code [start,end]} pair is wall-clock, not
 * media-demuxable time). Handing that raw position to ffmpeg as {@code -ss}
 * seeks past the end of the file: ffmpeg emits a valid-looking but empty fMP4
 * (just the moov header, no media), which a pull client cannot distinguish from
 * success -- so it retries forever ("stuck at Loading...").
 *
 * <h2>The authority is per-physical-file, and state-derived</h2>
 * A single airing can be several physical files with gaps between them (a 60-min
 * show captured as 5-6 segments after power/signal loss). The whole-recording
 * duration is therefore meaningless for any one segment, and
 * {@code getEnd(last) - getStart(0)} spans the gaps. So the only correct
 * authority is the <em>actual demuxable extent of the specific file being
 * opened</em>. This class always operates on one physical file and picks the
 * authority by that file's state:
 *
 * <ul>
 *   <li><b>Growing / actively recording</b> ({@link MMC#isRecording(java.io.File)}):
 *       no clamp. The file is still being written; follow-mode serves data as it
 *       arrives, and a wall-clock "edge" would only ever be a no-op that is also
 *       structurally too long.</li>
 *   <li><b>Pure live stream</b> ({@link MediaFile#isAnyLiveStream()} with no
 *       recording): no clamp, and never probe -- there is no fixed duration to
 *       probe and the source never ends.</li>
 *   <li><b>Completed / static</b> (finished recording, imported, or a converted
 *       derivative): clamp to the file's probed demuxable end, less a small tail
 *       margin so a {@code -c:v copy} input-seek lands on a real keyframe with
 *       frames after it rather than at the very last (possibly partial) GOP.</li>
 * </ul>
 *
 * When the authority is unknown (probe unavailable, duration not parseable) this
 * class is a <b>no-op</b> -- it returns the requested position unchanged, so the
 * worst case is exactly today's behavior. It can only ever be equal-or-better.
 *
 * <p>Delivery mode (stream-copy vs full re-encode) does <em>not</em> change the
 * authority: {@code -ss} is applied to the source input in both cases, so the
 * clamp is identical. The tail margin matters most for the copy path (its input
 * seek snaps to the preceding keyframe), and is harmless for re-encode.
 */
public final class SeekWindow
{
  private SeekWindow() {}

  /** Master switch; set {@code ffmpeg/seek_clamp_enabled=false} to disable live. */
  private static final String PROP_ENABLED = "ffmpeg/seek_clamp_enabled";
  /**
   * Tail margin (ms) kept below a completed file's demuxable end so a copy-path
   * input seek lands on a keyframe with content after it. Tunable via
   * {@code ffmpeg/seek_clamp_tail_margin_ms}.
   */
  private static final String PROP_TAIL_MARGIN_MS = "ffmpeg/seek_clamp_tail_margin_ms";
  private static final long DEFAULT_TAIL_MARGIN_MS = 1500L;

  // Probe cache: completed files don't change, so a duration keyed by
  // path+size+mtime is valid until the file is rewritten. Keeps the synchronous
  // ffmpeg probe off the hot path for repeated seeks into the same file.
  private static final java.util.concurrent.ConcurrentHashMap<String, long[]> DURATION_CACHE =
      new java.util.concurrent.ConcurrentHashMap<String, long[]>();

  /**
   * Clamp a <em>file-relative</em> requested seek position (ms into the given
   * physical file) to a value ffmpeg can actually honor.
   *
   * @param mf           the owning MediaFile (may be {@code null} -- state is
   *                     then derived from {@code physicalFile} alone)
   * @param physicalFile the exact file that will be opened and {@code -ss}'d
   * @param requestedFileMs requested offset in ms, relative to that file
   * @return the clamped offset (>= 0), or {@code requestedFileMs} unchanged when
   *         no clamp applies or the authority is unknown
   */
  public static long clampFileRelativeMs(MediaFile mf, java.io.File physicalFile, long requestedFileMs)
  {
    if (requestedFileMs <= 0)
      return requestedFileMs;
    if (!Sage.getBoolean(PROP_ENABLED, true))
      return requestedFileMs;

    try
    {
      // --- Growing / actively-recording segment: no clamp (follow-mode serves it). ---
      if (physicalFile != null && MMC.getInstance().isRecording(physicalFile))
        return requestedFileMs;
      if (mf != null)
      {
        try { if (mf.isRecording(physicalFile)) return requestedFileMs; }
        catch (Throwable ignore) {}
        // --- Pure live stream: no fixed duration, never probe. ---
        if (mf.isAnyLiveStream() && !mf.isCompleteRecording())
          return requestedFileMs;
      }

      // --- Completed / static: clamp to the probed demuxable end of THIS file. ---
      long playableEndMs = resolvePlayableEndMs(mf, physicalFile);
      if (playableEndMs <= 0)
        return requestedFileMs; // unknown -> no-op

      long tailMargin = Math.max(0L, Sage.getLong(PROP_TAIL_MARGIN_MS, DEFAULT_TAIL_MARGIN_MS));
      return applyClamp(requestedFileMs, playableEndMs, tailMargin);
    }
    catch (Throwable t)
    {
      if (Sage.DBG) System.out.println("SeekWindow: clamp skipped (exception): " + t);
      return requestedFileMs;
    }
  }

  /**
   * Pure clamp arithmetic, separated for testability. Returns the smaller of the
   * request and {@code (playableEndMs - tailMarginMs)} (floored at 0). A
   * non-positive {@code playableEndMs} means "unknown" and the request passes
   * through unchanged.
   */
  static long applyClamp(long requestedMs, long playableEndMs, long tailMarginMs)
  {
    if (requestedMs <= 0)
      return requestedMs;
    if (playableEndMs <= 0)
      return requestedMs;
    long ceiling = Math.max(0L, playableEndMs - Math.max(0L, tailMarginMs));
    return Math.min(requestedMs, ceiling);
  }

  /**
   * Shared clamp arithmetic for the <em>runtime</em> seek paths (native
   * trick-play skip in {@link VideoFrame}, push DVR clamp in {@link MiniPlayer}).
   * Confines the {@code min/max}/floor/ceiling/margin discipline to one place so
   * those callers stop each hand-rolling a subtly different expression that can
   * drift apart.
   *
   * <p><b>Unit-agnostic:</b> {@code requestedMs}, {@code floorMs} and
   * {@code playableEndMs} must all be in the <em>same</em> unit chosen by the
   * caller (file-epoch ms for {@code VideoFrame}, media-relative ms for
   * {@code MiniPlayer}); the helper only does arithmetic and never assumes a
   * particular epoch.
   *
   * <p>This is deliberately distinct from {@link #applyClamp}: it adds a
   * caller-supplied {@code floorMs} (so a backward skip saturates at the window
   * start rather than 0) while preserving the same "unknown end &rarr; upper
   * bound not applied" contract. When {@code playableEndMs <= 0} the value passes
   * through unchanged, exactly matching each site's current no-op-on-unknown
   * behavior.
   *
   * @param requestedMs   requested target (caller's unit)
   * @param floorMs       lowest legal target in the same unit; a backward move
   *                      saturates here (negative values are treated as 0)
   * @param playableEndMs furthest legal end in the same unit, or {@code <= 0}
   *                      for "unknown" (upper bound not applied)
   * @param tailMarginMs  margin kept below the end (negative treated as 0)
   * @return the clamped target
   */
  static long clampToWindow(long requestedMs, long floorMs, long playableEndMs, long tailMarginMs)
  {
    long floor = Math.max(0L, floorMs);
    if (playableEndMs <= 0)
      return requestedMs; // unknown end -> preserve today's pass-through
    long ceiling = Math.max(floor, playableEndMs - Math.max(0L, tailMarginMs));
    return Math.min(Math.max(requestedMs, floor), ceiling);
  }

  /**
   * Single authority for the <em>live/timeshift</em> seek window advertised to
   * NG clients. Both {@code sage.ng.NgLiveWindowCalculator} (per-tick delta) and
   * {@code sage.ng.NgPlaybackContextBuilder} (initial snapshot) resolve the
   * client-visible {@code {safeSeekStart, safeSeekEnd, playableEnd}} triple
   * through here, so the two can no longer drift apart (they previously disagreed
   * on whether known-duration or the served edge wins, and on margin underflow).
   *
   * <p><b>Policy (file-relative ms):</b>
   * <ul>
   *   <li>{@code playableEnd = max(knownDurationMs, serverMediaTimeMs)} -- the
   *       furthest reachable point: the greater of what the server has actually
   *       served (the growing edge) and any known total extent. Per the caller
   *       contract {@code knownDurationMs} is {@code 0} for a live/unknown source,
   *       so this is the served edge for a growing stream and the true duration
   *       for a known-length file. It never advertises beyond both.</li>
   *   <li>{@code safeSeekEnd = playableEnd > trailingMargin ?
   *       playableEnd - trailingMargin : playableEnd} -- hold back a margin from
   *       the edge, but do not underflow near the very start of a stream (a
   *       2-3 s-in stream stays seekable to its edge rather than snapping to 0).</li>
   *   <li>{@code safeSeekStart = 0}.</li>
   * </ul>
   *
   * @param serverMediaTimeMs furthest media time the server has served (>=0)
   * @param knownDurationMs   known total media duration, or {@code <=0}/unknown
   * @param trailingMarginMs  margin held back from the edge (negative treated 0)
   * @return {@code {safeSeekStartMs, safeSeekEndMs, playableEndMs}}, each &gt;= 0
   */
  public static long[] resolveLiveWindowMs(long serverMediaTimeMs, long knownDurationMs,
      long trailingMarginMs)
  {
    long edge = Math.max(0L, serverMediaTimeMs);
    long dur = Math.max(0L, knownDurationMs);
    long margin = Math.max(0L, trailingMarginMs);
    long playableEndMs = Math.max(edge, dur);
    long safeSeekStartMs = 0L;
    long safeSeekEndMs = (playableEndMs > margin) ? (playableEndMs - margin) : playableEndMs;
    if (safeSeekEndMs < safeSeekStartMs)
      safeSeekEndMs = safeSeekStartMs;
    return new long[] { safeSeekStartMs, safeSeekEndMs, playableEndMs };
  }

  /**
   * Best-known demuxable end (ms) of the specific physical file. Prefers a
   * parsed duration ONLY for a single-segment recording (where the
   * whole-recording format duration equals this file's); otherwise probes the
   * physical file directly, because a multi-segment/gapped recording's
   * whole-recording duration does not describe any one segment.
   */
  private static long resolvePlayableEndMs(MediaFile mf, java.io.File physicalFile)
  {
    // Rung 1: parsed whole-recording duration, but only when it unambiguously
    // describes this single physical file.
    if (mf != null)
    {
      try
      {
        if (mf.getNumSegments() == 1)
        {
          sage.media.format.ContainerFormat cf = mf.getFileFormat();
          if (cf != null && cf.getDuration() > 0)
            return cf.getDuration();
        }
      }
      catch (Throwable ignore) {}
    }

    // Rung 2: probe the physical file (cached by path+size+mtime).
    if (physicalFile != null)
      return probeDurationMsCached(physicalFile);

    return 0L;
  }

  private static long probeDurationMsCached(java.io.File f)
  {
    try
    {
      long size = f.length();
      long mtime = f.lastModified();
      String key = f.getPath();
      long[] cached = DURATION_CACHE.get(key);
      if (cached != null && cached[0] == size && cached[1] == mtime)
        return cached[2];

      long dur = 0L;
      sage.media.format.ContainerFormat cf =
          sage.media.format.FormatParser.getFFMPEGFileFormat(f.getPath());
      if (cf != null && cf.getDuration() > 0)
        dur = cf.getDuration();

      if (dur > 0)
        DURATION_CACHE.put(key, new long[] { size, mtime, dur });
      return dur;
    }
    catch (Throwable t)
    {
      if (Sage.DBG) System.out.println("SeekWindow: probe failed for " + f + ": " + t);
      return 0L;
    }
  }
}
