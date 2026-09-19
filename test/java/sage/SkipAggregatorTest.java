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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;
import sage.commercial.EdlWriter;
import sage.commercial.SkipAggregator;
import sage.commercial.SkipMatrix;

/**
 * Tests for {@link SkipAggregator} — merging per-physical-file comskip marks into
 * one whole-recording skip list in content time. The decisive property is that a
 * segment's file-relative marks are shifted by that segment's
 * {@link SegmentTimeline#contentBaseMs(int)} so the client can skip across segment
 * boundaries on a single continuous clock. Single-segment recordings (content base
 * 0) must pass through unchanged — this is also the first path that surfaces
 * comskip to NG clients at all.
 */
public class SkipAggregatorTest
{
  private static final long T = 1_700_000_000_000L; // arbitrary epoch anchor

  /** Build a timeline from per-segment durations (adjacent, no gaps). */
  private static SegmentTimeline timeline(long... durationsMs)
  {
    int n = durationsMs.length;
    java.io.File[] files = new java.io.File[n];
    long[] start = new long[n];
    long[] end = new long[n];
    long[] size = new long[n];
    long cursor = T;
    for (int i = 0; i < n; i++)
    {
      files[i] = new java.io.File("seg" + i + ".mpg");
      start[i] = cursor;
      end[i] = cursor + durationsMs[i];
      size[i] = 0;
      cursor = end[i];
    }
    return new SegmentTimeline(files, start, end, size);
  }

  /** Build a file-relative SkipMatrix from [startSec, endSec, action] triples. */
  private static SkipMatrix matrix(double[][] marks)
  {
    ArrayList<EdlWriter.Segment> segs = new ArrayList<>();
    for (double[] m : marks)
      segs.add(new EdlWriter.Segment(m[0], m[1], (int) m[2]));
    return SkipMatrix.fromEdlSegments(segs);
  }

  // ---- single segment: pass-through (base 0) --------------------------------

  @Test
  public void singleSegment_passesThroughUnchanged()
  {
    SegmentTimeline tl = timeline(1_800_000);
    SkipMatrix seg0 = matrix(new double[][] { { 60, 120, 0 }, { 600, 660, 0 } });

    List<long[]> out = SkipAggregator.aggregate(tl, i -> seg0);

    assertEquals(out.size(), 2, "single: count");
    assertEquals(out.get(0), new long[] { 60_000, 120_000, 0 }, "single: first mark unshifted");
    assertEquals(out.get(1), new long[] { 600_000, 660_000, 0 }, "single: second mark unshifted");
  }

  // ---- multi-segment: marks shifted by contentBase ---------------------------

  @Test
  public void multiSegment_shiftsMarksByContentBase()
  {
    // Reindeer-style 3-segment recording: 20s + 21s + 30s, adjacent.
    // contentBase = 0, 20000, 41000.
    SegmentTimeline tl = timeline(20_000, 21_000, 30_000);
    SkipMatrix[] per = new SkipMatrix[] {
        matrix(new double[][] { { 5, 10, 0 } }),   // seg0 commercial -> [5000,10000]
        matrix(new double[][] { { 2, 7, 0 } }),    // seg1 commercial -> [22000,27000]
        matrix(new double[][] { { 1, 3, 1 } })     // seg2 promo      -> [42000,44000] kind1
    };

    List<long[]> out = SkipAggregator.aggregate(tl, i -> per[i]);

    assertEquals(out.size(), 3, "multi: count");
    assertEquals(out.get(0), new long[] { 5_000, 10_000, 0 }, "multi: seg0 mark");
    assertEquals(out.get(1), new long[] { 22_000, 27_000, 0 }, "multi: seg1 mark shifted by 20000");
    assertEquals(out.get(2), new long[] { 42_000, 44_000, 1 }, "multi: seg2 promo shifted by 41000");
  }

  @Test
  public void multiSegment_outputSortedByContentStart()
  {
    SegmentTimeline tl = timeline(20_000, 21_000, 30_000);
    // Two marks per segment, out of global order pre-merge.
    SkipMatrix[] per = new SkipMatrix[] {
        matrix(new double[][] { { 15, 18, 0 } }),
        matrix(new double[][] { { 1, 4, 0 }, { 10, 13, 0 } }),
        matrix(new double[][] { { 2, 5, 0 } })
    };

    List<long[]> out = SkipAggregator.aggregate(tl, i -> per[i]);

    assertEquals(out.size(), 4, "sorted: count");
    long prev = -1;
    for (long[] row : out)
    {
      assertTrue(row[0] >= prev, "sorted: non-decreasing start");
      prev = row[0];
    }
    assertEquals(out.get(0)[0], 15_000L, "sorted: seg0 first");
    assertEquals(out.get(1)[0], 21_000L, "sorted: seg1 mark @1s -> 21000");
    assertEquals(out.get(2)[0], 30_000L, "sorted: seg1 mark @10s -> 30000");
    assertEquals(out.get(3)[0], 43_000L, "sorted: seg2 mark @2s -> 43000");
  }

  // ---- missing / empty segments ---------------------------------------------

  @Test
  public void growingLastSegment_withNoEdl_isSkipped()
  {
    // seg2 has no .edl yet (in-progress recording) -> empty matrix.
    SegmentTimeline tl = timeline(20_000, 21_000, 30_000);
    SkipMatrix[] per = new SkipMatrix[] {
        matrix(new double[][] { { 5, 10, 0 } }),
        matrix(new double[][] { { 2, 7, 0 } }),
        SkipMatrix.empty()
    };

    List<long[]> out = SkipAggregator.aggregate(tl, i -> per[i]);

    assertEquals(out.size(), 2, "growing: only completed segments contribute");
    assertEquals(out.get(1), new long[] { 22_000, 27_000, 0 }, "growing: seg1 still shifted");
  }

  @Test
  public void perSegmentFailure_doesNotSinkWholeRecording()
  {
    SegmentTimeline tl = timeline(20_000, 21_000);
    List<long[]> out = SkipAggregator.aggregate(tl, i -> {
      if (i == 0) throw new RuntimeException("unreadable sidecar");
      return matrix(new double[][] { { 2, 7, 0 } });
    });

    assertEquals(out.size(), 1, "failure: surviving segment still contributes");
    assertEquals(out.get(0), new long[] { 22_000, 27_000, 0 }, "failure: seg1 shifted");
  }

  @Test
  public void noMarksAnywhere_yieldsEmpty()
  {
    SegmentTimeline tl = timeline(20_000, 21_000);
    List<long[]> out = SkipAggregator.aggregate(tl, i -> SkipMatrix.empty());
    assertTrue(out.isEmpty(), "empty: no marks -> empty list");
  }

  @Test
  public void nullTimeline_yieldsEmptyNotNull()
  {
    List<long[]> out = SkipAggregator.aggregate(null);
    assertTrue(out != null && out.isEmpty(), "null timeline -> empty, non-null");
  }
}
