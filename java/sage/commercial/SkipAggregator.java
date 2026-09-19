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
package sage.commercial;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntFunction;
import sage.SegmentTimeline;

/**
 * Merges the per-physical-file commercial-skip data of a multi-segment recording
 * into a single whole-recording skip list expressed in <b>content time</b>.
 * <p>
 * Each SageTV airing can be recorded across several physical segment files (power
 * loss, file-size rollover, signal glitch). Comskip runs per physical file and its
 * marks ({@link SkipMatrix}) are <b>file-relative</b> — segment 2's first commercial
 * at 30&nbsp;s means 30&nbsp;s into <i>that file</i>, not 30&nbsp;s into the recording.
 * <p>
 * NG/PWA playback delivers the recording as one continuous stream whose clock is
 * <b>content time</b> (gaps collapsed): the cumulative sum of the preceding segment
 * durations. This aggregator converts each per-file mark into that whole-recording
 * content clock by adding {@link SegmentTimeline#contentBaseMs(int)} of its owning
 * segment, so a single skip list spans the entire recording and the client can
 * skip across segment boundaries without knowing they exist.
 * <p>
 * Output rows are {@code long[]{startMs, endMs, kind}} in content time, sorted by
 * {@code startMs}, matching the shape consumed by
 * {@code NgPlaybackContextBuilder.buildSkipContext}. {@code kind} carries the
 * {@link SkipMatrix} constants ({@code 0}=commercial, {@code 1}=promo,
 * {@code 2}=chapter).
 * <p>
 * The result is correct for the single-segment case as well: with one segment the
 * content base is 0, so content time equals file time and the marks pass through
 * unchanged. This is the first path that surfaces comskip data to NG clients at all.
 */
public final class SkipAggregator
{
  private SkipAggregator() {}

  /**
   * Aggregates per-segment skip data into whole-recording content-time rows using
   * the segment files carried by the timeline.
   *
   * @param timeline the whole-recording virtual timeline (may be null)
   * @return sorted content-time rows {@code {startMs, endMs, kind}}; never null, may be empty
   */
  public static List<long[]> aggregate(SegmentTimeline timeline)
  {
    if (timeline == null) return new ArrayList<>();
    return aggregate(timeline, seg -> {
      java.io.File f = timeline.file(seg);
      return (f != null) ? SkipMatrix.load(f) : SkipMatrix.empty();
    });
  }

  /**
   * Aggregates per-segment skip data into whole-recording content-time rows.
   * <p>
   * Pure/testable core: the caller supplies a function that yields the
   * {@link SkipMatrix} for a given segment index (file-relative marks), decoupling
   * the offset arithmetic from file I/O.
   *
   * @param timeline   the whole-recording virtual timeline (may be null)
   * @param perSegment resolves the file-relative {@link SkipMatrix} for a segment index
   * @return sorted content-time rows {@code {startMs, endMs, kind}}; never null, may be empty
   */
  public static List<long[]> aggregate(SegmentTimeline timeline, IntFunction<SkipMatrix> perSegment)
  {
    List<long[]> out = new ArrayList<>();
    if (timeline == null || perSegment == null) return out;

    int count = timeline.segmentCount();
    for (int seg = 0; seg < count; seg++)
    {
      SkipMatrix matrix;
      try
      {
        matrix = perSegment.apply(seg);
      }
      catch (Throwable t)
      {
        // A missing/unreadable segment sidecar must not sink the whole recording's
        // skip data; the growing last segment of an in-progress recording commonly
        // has no .edl yet. Skip just this segment.
        continue;
      }
      if (matrix == null) continue;

      long base = timeline.contentBaseMs(seg);
      int rows = matrix.getSegmentCount();
      for (int i = 0; i < rows; i++)
      {
        long startMs = base + matrix.getSegmentStartMs(i);
        long endMs = base + matrix.getSegmentEndMs(i);
        if (endMs <= startMs) continue; // drop degenerate/empty marks
        out.add(new long[] { startMs, endMs, matrix.getSegmentKind(i) });
      }
    }

    out.sort(Comparator.comparingLong(a -> a[0]));
    return out;
  }
}
