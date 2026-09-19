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
 * Whole-recording virtual timeline over a multi-segment {@link MediaFile}.
 *
 * <p>This is the shared authority for "the recording is one logical program made
 * of N physical segment files" -- the classic SageTV model (a single airing can
 * be several {@code .mpg}/{@code .ts} files with real gaps between them after a
 * power/signal loss, a file-size rollover, a recorder restart, etc.). It is the
 * whole-recording counterpart to {@link SeekWindow}, which owns the
 * per-<em>physical</em>-file clamp. Every facet that must span segments -- the
 * NG segment manifest the web player navigates, commercial-skip aggregation, and
 * caption merging -- maps through this one class so they cannot drift apart.
 *
 * <h2>Two timelines</h2>
 * A gapped recording has two distinct notions of time, and mixing them is the
 * classic source of bugs:
 * <ul>
 *   <li><b>content time</b> -- continuous, gaps <em>collapsed</em>. This is what
 *       continuous playback delivers: segment i's playable content plays
 *       immediately after segment i-1's, with the outage removed. Segment i
 *       begins at content offset {@code contentBaseMs(i) = Sum of durationMs(0..i-1)}.
 *       Commercial-skip marks and caption cues (both derived per physical file,
 *       file-relative) map into content time by adding {@code contentBaseMs(i)}.</li>
 *   <li><b>wall-clock time</b> -- {@link MediaFile#getStart(int)} epoch ms, gaps
 *       <em>preserved</em>. This is what the scrubber/OSD shows so the viewer
 *       sees the real program position (14:59 &rarr; jump &rarr; 29:00). The
 *       gap before segment i is {@code getStart(i) - getEnd(i-1)}.</li>
 * </ul>
 *
 * <p>All arithmetic is pure and null-safe; the class never touches ffmpeg or IO.
 * The {@link #fromMediaFile(MediaFile)} factory snapshots a {@code MediaFile}'s
 * segment list at a point in time (a growing last segment contributes its
 * current {@code getEnd}, which {@code MediaFile} already reports as
 * {@code Sage.time()} while recording).
 */
public final class SegmentTimeline
{
  private final java.io.File[] files;
  private final long[] startEpochMs;   // wall-clock start of each segment
  private final long[] endEpochMs;     // wall-clock end (Sage.time() for a growing segment)
  private final long[] sizeBytes;      // physical file size at snapshot time
  private final long[] contentBaseMs;  // content-time offset where each segment begins
  private final long totalContentMs;

  /**
   * Pure constructor (testable without a MediaFile). Arrays must be the same
   * length; a per-segment content duration is {@code max(0, end - start)}.
   *
   * @param files        per-segment physical file (may contain nulls)
   * @param startEpochMs per-segment wall-clock start (epoch ms)
   * @param endEpochMs   per-segment wall-clock end (epoch ms; the growing last
   *                     segment should pass the current time)
   * @param sizeBytes    per-segment physical file size (bytes), or 0 if unknown
   */
  public SegmentTimeline(java.io.File[] files, long[] startEpochMs, long[] endEpochMs, long[] sizeBytes)
  {
    int n = (files != null) ? files.length : 0;
    if (startEpochMs == null || endEpochMs == null || sizeBytes == null
        || startEpochMs.length != n || endEpochMs.length != n || sizeBytes.length != n)
      throw new IllegalArgumentException("SegmentTimeline: array lengths must match files.length=" + n);

    this.files = (files != null) ? files.clone() : new java.io.File[0];
    this.startEpochMs = (startEpochMs != null) ? startEpochMs.clone() : new long[0];
    this.endEpochMs = (endEpochMs != null) ? endEpochMs.clone() : new long[0];
    this.sizeBytes = (sizeBytes != null) ? sizeBytes.clone() : new long[0];
    this.contentBaseMs = new long[n];

    long acc = 0;
    for (int i = 0; i < n; i++)
    {
      contentBaseMs[i] = acc;
      acc += durationMs(i);
    }
    this.totalContentMs = acc;
  }

  /**
   * Snapshot a MediaFile's segment list. Returns {@code null} when the media file
   * is null or has no segments.
   */
  public static SegmentTimeline fromMediaFile(MediaFile mf)
  {
    if (mf == null) return null;
    int n = mf.getNumSegments();
    if (n <= 0) return null;
    java.io.File[] f = new java.io.File[n];
    long[] s = new long[n];
    long[] e = new long[n];
    long[] sz = new long[n];
    for (int i = 0; i < n; i++)
    {
      f[i] = mf.getFile(i);
      s[i] = mf.getStart(i);
      e[i] = mf.getEnd(i); // MediaFile substitutes Sage.time() for a growing segment
      sz[i] = (f[i] != null && f[i].isFile()) ? f[i].length() : 0L;
    }
    return new SegmentTimeline(f, s, e, sz);
  }

  /** Number of physical segments. */
  public int segmentCount() { return files.length; }

  /** True when this recording spans more than one physical file. */
  public boolean isMultiSegment() { return files.length > 1; }

  public java.io.File file(int seg) { return inRange(seg) ? files[seg] : null; }

  public long startEpochMs(int seg) { return inRange(seg) ? startEpochMs[seg] : 0L; }

  public long endEpochMs(int seg) { return inRange(seg) ? endEpochMs[seg] : 0L; }

  public long sizeBytes(int seg) { return inRange(seg) ? sizeBytes[seg] : 0L; }

  /** Content duration of a segment (ms): {@code max(0, end - start)}. */
  public long durationMs(int seg)
  {
    if (!inRange(seg)) return 0L;
    long d = endEpochMs[seg] - startEpochMs[seg];
    return (d > 0) ? d : 0L;
  }

  /** Content-time offset (ms) at which the given segment begins. */
  public long contentBaseMs(int seg) { return inRange(seg) ? contentBaseMs[seg] : 0L; }

  /** Total content duration (ms) across all segments, gaps collapsed. */
  public long totalContentMs() { return totalContentMs; }

  /**
   * Wall-clock gap (ms) immediately before the given segment -- the real
   * un-recorded time between the previous segment's end and this segment's start
   * (a power outage, a signal drop). {@code 0} for segment 0 and for any
   * non-positive/overlapping computation.
   */
  public long gapBeforeMs(int seg)
  {
    if (!inRange(seg) || seg == 0) return 0L;
    long g = startEpochMs[seg] - endEpochMs[seg - 1];
    return (g > 0) ? g : 0L;
  }

  /** True if any inter-segment wall-clock gap exists. */
  public boolean hasGaps()
  {
    for (int i = 1; i < files.length; i++)
      if (gapBeforeMs(i) > 0) return true;
    return false;
  }

  /**
   * Map a content-time position (ms) to the segment index that contains it.
   * Clamps: negative &rarr; 0, at/after the end &rarr; the last segment.
   */
  public int contentToSegment(long contentMs)
  {
    int n = files.length;
    if (n == 0) return -1;
    if (contentMs <= 0) return 0;
    for (int i = 0; i < n; i++)
    {
      long base = contentBaseMs[i];
      long end = base + durationMs(i);
      if (contentMs < end) return i;
    }
    return n - 1;
  }

  /**
   * Map a content-time position (ms) to a file-relative offset (ms) within the
   * segment returned by {@link #contentToSegment(long)}.
   */
  public long contentToFileRelativeMs(long contentMs)
  {
    int seg = contentToSegment(contentMs);
    if (seg < 0) return 0L;
    long rel = contentMs - contentBaseMs[seg];
    return (rel > 0) ? rel : 0L;
  }

  /**
   * Map a file-relative offset (ms) within a given segment to whole-recording
   * content time (ms).
   */
  public long fileRelativeToContentMs(int seg, long fileRelativeMs)
  {
    if (!inRange(seg)) return 0L;
    long rel = (fileRelativeMs > 0) ? fileRelativeMs : 0L;
    return contentBaseMs[seg] + rel;
  }

  /**
   * Server-side epoch mapping: the segment index whose wall-clock start is the
   * greatest {@code startEpochMs <= epochMs}. Unlike {@link #contentToSegment}
   * this does NOT clamp to a segment's (possibly frozen) end, so a growing last
   * segment keeps reporting a monotonically increasing position on the live
   * tail. Assumes segments are chronological. Clamps to {@code [0, count-1]};
   * returns -1 when empty.
   *
   * <p>This is a server-only helper (raw SageTV media time is epoch/PTS-based);
   * the client never sees epoch media time — the server sends content time — so
   * it has no counterpart in the client mirror.
   */
  public int segmentForEpochMs(long epochMs)
  {
    int n = files.length;
    if (n == 0) return -1;
    int seg = 0;
    for (int i = 0; i < n; i++)
    {
      if (startEpochMs[i] <= epochMs) seg = i;
      else break;
    }
    return seg;
  }

  private boolean inRange(int seg) { return seg >= 0 && seg < files.length; }

  @Override
  public String toString()
  {
    StringBuilder sb = new StringBuilder("SegmentTimeline[").append(files.length)
        .append(" seg, contentMs=").append(totalContentMs);
    if (hasGaps()) sb.append(", gapped");
    return sb.append(']').toString();
  }
}
