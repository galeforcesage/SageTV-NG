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
package sage.ng;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Builds an {@link NgPlaybackContext} from server-side playback state.
 * <p>
 * This builder accepts a {@link PlaybackSnapshot} which captures the relevant
 * state from MiniPlayer/MediaFile/SkipMatrix at a point in time. The snapshot
 * approach decouples the NG model from MiniPlayer's internal fields (which are
 * protected/private) and allows the wiring phase to populate it however is
 * convenient.
 * <p>
 * Usage:
 * <pre>
 *   PlaybackSnapshot snap = new PlaybackSnapshot();
 *   snap.mediaFileId = currMF.getID();
 *   snap.timeshifted = timeshifted;
 *   // ... populate other fields ...
 *   NgPlaybackContext ctx = NgPlaybackContextBuilder.build(snap);
 * </pre>
 */
public final class NgPlaybackContextBuilder
{
  private NgPlaybackContextBuilder() {}

  /**
   * A mutable snapshot of server-side playback state.
   * All fields have safe defaults so partial population is fine.
   * Populate only what is cheaply available; the builder handles unknowns.
   */
  public static final class PlaybackSnapshot
  {
    // --- Identity ---
    /** SageTV MediaFile ID, or -1 if unavailable */
    public long mediaFileId = -1;
    /** SageTV Airing ID, or -1 if unavailable */
    public long airingId = -1;
    /** Session UUID (caller should generate or reuse one per playback session) */
    public String sessionId = null;

    // --- Media info ---
    /** Container format string from ContainerFormat (e.g. "MPEG2-TS", "MPEG2-PS", "MP4", "Matroska") */
    public String containerFormat = null;
    /** Duration in milliseconds, 0 if unknown or live */
    public long durationMs = 0;

    // --- Playback state ---
    /** True if the file is currently being recorded/written (timeshifted flag) */
    public boolean timeshifted = false;
    /** True if this is a live TV stream (not just a recording in progress) */
    public boolean isLiveStream = false;
    /** Current server media time in milliseconds */
    public long serverMediaTimeMs = 0;
    /** Stream epoch — increments on media file change/channel switch within a session */
    public int streamEpoch = 1;

    // --- Live window (only meaningful when timeshifted=true) ---
    /** File length in bytes (for active files, current on-disk size) */
    public long fileSizeBytes = 0;
    /** Timestamp (System.currentTimeMillis) when fileSizeBytes was last read */
    public long fileSizeRefreshTimeMs = 0;
    /** Recording start timestamp (epoch ms), 0 if unknown */
    public long recordingStartEpochMs = 0;

    // --- Skip data (from SkipMatrix if loaded) ---
    /** Commercial segments: pairs of [startMs, endMs, kind] */
    public List<long[]> skipSegments = null;

    // --- Server capabilities ---
    /** True if server-side transcoding is active */
    public boolean serverSideTranscoding = false;
    /** True if push mode is active (server pushes buffers to client) */
    public boolean pushMode = false;
    /** True if byte-based seeking is in use (no PTS map) */
    public boolean byteBasedSeeking = false;
  }

  /**
   * Build a best-effort NgPlaybackContext from the given snapshot.
   * All unknown values are safely defaulted.
   */
  public static NgPlaybackContext build(PlaybackSnapshot snap)
  {
    if (snap == null)
    {
      snap = new PlaybackSnapshot();
    }

    String sessionId = (snap.sessionId != null) ? snap.sessionId : UUID.randomUUID().toString();
    String mode = deriveMode(snap);
    String container = normalizeContainer(snap.containerFormat);
    long durationMs = Math.max(0, snap.durationMs);
    long serverMediaTimeMs = Math.max(0, snap.serverMediaTimeMs);

    NgLiveContext live = buildLiveContext(snap, durationMs);
    NgSeekPolicy seek = buildSeekPolicy(snap);
    NgIndexContext index = NgIndexContext.EMPTY; // Phase 2: no keyframe scanning
    NgSkipContext skip = buildSkipContext(snap);
    NgFlowPolicy flow = NgFlowPolicy.DEFAULT;

    return new NgPlaybackContext(
        sessionId, snap.mediaFileId, snap.airingId,
        mode, container, durationMs, serverMediaTimeMs,
        snap.streamEpoch,
        live, seek, index, skip, flow
    );
  }

  /**
   * Derive the playback mode from snapshot state.
   */
  static String deriveMode(PlaybackSnapshot snap)
  {
    if (snap.isLiveStream)
      return "live";
    if (snap.timeshifted)
      return "timeshift";
    if (snap.mediaFileId > 0)
      return "recording";
    return "unknown";
  }

  /**
   * Normalize container format strings from SageTV's ContainerFormat naming
   * to the NG contract's short identifiers.
   */
  static String normalizeContainer(String containerFormat)
  {
    if (containerFormat == null || containerFormat.isEmpty())
      return "unknown";

    String upper = containerFormat.toUpperCase();

    if (upper.contains("MPEG2-TS") || upper.contains("MPEG2_TS") || upper.equals("TS"))
      return "ts";
    if (upper.contains("MPEG2-PS") || upper.contains("MPEG2_PS") || upper.equals("PS"))
      return "ps";
    if (upper.contains("MP4") || upper.contains("MPEG4") || upper.contains("M4V"))
      return "mp4";
    if (upper.contains("MATROSKA") || upper.contains("MKV"))
      return "mkv";
    if (upper.contains("AVI"))
      return "avi";
    if (upper.contains("FLV"))
      return "flv";

    // Pass through unknown formats in lowercase
    return containerFormat.toLowerCase();
  }

  /** Conservative safety margin for live-edge seek targeting (ms) */
  private static final long LIVE_EDGE_SAFETY_MARGIN_MS = 5000;

  /**
   * Build the live context sub-object.
   */
  static NgLiveContext buildLiveContext(PlaybackSnapshot snap, long durationMs)
  {
    if (!snap.timeshifted && !snap.isLiveStream)
      return NgLiveContext.EMPTY;

    long safeSeekStartMs = 0;

    // playableEndMs: the furthest media-relative point the server can serve
    long playableEndMs;
    if (durationMs > 0)
      playableEndMs = durationMs;
    else if (snap.serverMediaTimeMs > 0)
      playableEndMs = snap.serverMediaTimeMs;
    else
      playableEndMs = 0;

    // safeSeekEndMs: conservatively behind playableEndMs by the safety margin
    long safeSeekEndMs = Math.max(safeSeekStartMs, playableEndMs - LIVE_EDGE_SAFETY_MARGIN_MS);

    return new NgLiveContext(
        true,
        snap.recordingStartEpochMs,
        safeSeekStartMs,
        safeSeekEndMs,
        playableEndMs,
        snap.fileSizeBytes,
        snap.fileSizeRefreshTimeMs
    );
  }

  /**
   * Build the seek policy based on current server state.
   * Conservative defaults: all seeks go through server.
   */
  static NgSeekPolicy buildSeekPolicy(PlaybackSnapshot snap)
  {
    // If server is transcoding, seeks are expensive — use larger granularity
    long granularity = snap.serverSideTranscoding ? 10000 : 5000;

    // Client may predict OSD for non-transcoded content
    boolean mayPredictOsd = !snap.serverSideTranscoding;

    return new NgSeekPolicy(
        granularity,       // preferredGranularityMs
        250,               // minSeekIntervalMs
        1500,              // maxClientCoalesceMs
        true,              // requiresServerSeek (always true in Phase 2)
        mayPredictOsd      // clientMayPredictOsd
    );
  }

  /**
   * Build skip context from SkipMatrix segment data if available.
   * Expects segments as long[]{startMs, endMs, kind}.
   */
  static NgSkipContext buildSkipContext(PlaybackSnapshot snap)
  {
    if (snap.skipSegments == null || snap.skipSegments.isEmpty())
      return NgSkipContext.EMPTY;

    List<NgSkipSegment> commercials = new ArrayList<>();
    List<NgSkipSegment> chapters = new ArrayList<>();
    List<NgSkipSegment> bookmarks = new ArrayList<>();

    for (long[] seg : snap.skipSegments)
    {
      if (seg == null || seg.length < 3) continue;
      long startMs = seg[0];
      long endMs = seg[1];
      int kind = (int) seg[2];

      NgSkipSegment ngSeg;
      switch (kind)
      {
        case 0: // KIND_COMMERCIAL
          ngSeg = new NgSkipSegment(startMs, endMs, "commercial", 0);
          commercials.add(ngSeg);
          break;
        case 1: // KIND_PROMO
          ngSeg = new NgSkipSegment(startMs, endMs, "promo", 0);
          commercials.add(ngSeg); // promos go in commercials list too
          break;
        case 2: // KIND_CHAPTER
          ngSeg = new NgSkipSegment(startMs, endMs, "chapter", 0);
          chapters.add(ngSeg);
          break;
        default:
          ngSeg = new NgSkipSegment(startMs, endMs, "unknown", 0);
          bookmarks.add(ngSeg);
          break;
      }
    }

    if (commercials.isEmpty() && chapters.isEmpty() && bookmarks.isEmpty())
      return NgSkipContext.EMPTY;

    return new NgSkipContext(commercials, chapters, bookmarks);
  }
}
