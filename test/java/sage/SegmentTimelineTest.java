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
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

/**
 * Tests for {@link SegmentTimeline} — the whole-recording virtual timeline over
 * a multi-segment recording. These pin the same numbers the client-side mirror
 * (segmentTimeline.client.ts / .test.ts) asserts, so server and PWA/Tizen agree
 * on every content&harr;wall-clock&harr;(segment,file-relative) mapping. A
 * divergence here is a cross-boundary FF/REW/Skip/CC/comskip bug.
 *
 * <p>Shapes come from real recordings on the deployment: the Mom glitch-split
 * (31s + 934s, adjacent), the Reindeer 3-segment case (20/21/30s), and the
 * classic outage example (15min + 14min gap + 31min).
 */
public class SegmentTimelineTest
{
  private static final long T = 1_700_000_000_000L; // arbitrary epoch anchor

  /** Build a timeline from [startEpochMs, durationMs] pairs. */
  private static SegmentTimeline of(long[][] startDurPairs)
  {
    int n = startDurPairs.length;
    java.io.File[] files = new java.io.File[n];
    long[] start = new long[n];
    long[] end = new long[n];
    long[] size = new long[n];
    for (int i = 0; i < n; i++)
    {
      files[i] = new java.io.File("seg" + i + ".mpg");
      start[i] = startDurPairs[i][0];
      end[i] = startDurPairs[i][0] + startDurPairs[i][1];
      size[i] = 0;
    }
    return new SegmentTimeline(files, start, end, size);
  }

  // ---- single segment (common case, no regression) --------------------------

  @Test
  public void singleSegment_isNotMultiAndMaps1to1()
  {
    SegmentTimeline st = of(new long[][] { { T, 1_800_000 } });
    assertEquals(st.segmentCount(), 1, "single: count");
    assertFalse(st.isMultiSegment(), "single: not multi");
    assertFalse(st.hasGaps(), "single: no gaps");
    assertEquals(st.totalContentMs(), 1_800_000L, "single: total content");
    assertEquals(st.contentToSegment(600_000), 0, "single: seg lookup");
    assertEquals(st.contentToFileRelativeMs(600_000), 600_000L, "single: file-relative 1:1");
  }

  // ---- Mom 31s + 934s glitch-split (adjacent, no gap) -----------------------

  @Test
  public void momSplit_secondSegmentIsReachable()
  {
    SegmentTimeline st = of(new long[][] { { T, 31_000 }, { T + 31_000, 934_000 } });
    assertEquals(st.segmentCount(), 2, "mom: count");
    assertEquals(st.totalContentMs(), 965_000L, "mom: total content");
    assertEquals(st.contentBaseMs(1), 31_000L, "mom: seg1 content base == seg0 duration");
    assertEquals(st.gapBeforeMs(1), 0L, "mom: adjacent split has no gap");
    assertFalse(st.hasGaps(), "mom: no gaps");

    // The "second segment never plays" boundary: a content target at/after seg0
    // EOF must land in seg1, not fall off the end of seg0.
    assertEquals(st.contentToSegment(31_000), 1, "mom: boundary -> seg1");
    assertEquals(st.contentToFileRelativeMs(31_000), 0L, "mom: boundary -> seg1 @ 0");
    assertEquals(st.contentToSegment(40_000), 1, "mom: 40s -> seg1");
    assertEquals(st.contentToFileRelativeMs(40_000), 9_000L, "mom: 40s -> seg1 @ 9s");
    assertEquals(st.contentToSegment(30_999), 0, "mom: last ms of seg0 -> seg0");
  }

  // ---- Reindeer 20 / 21 / 30 s (multi-boundary) -----------------------------

  @Test
  public void reindeer_cumulativeBasesAndMapping()
  {
    SegmentTimeline st = of(new long[][] {
        { T, 20_000 }, { T + 20_000, 21_000 }, { T + 41_000, 30_000 } });
    assertEquals(st.contentBaseMs(0), 0L, "reindeer: base0");
    assertEquals(st.contentBaseMs(1), 20_000L, "reindeer: base1");
    assertEquals(st.contentBaseMs(2), 41_000L, "reindeer: base2");
    assertEquals(st.totalContentMs(), 71_000L, "reindeer: total");

    assertEquals(st.contentToSegment(20_000), 1, "reindeer: 20s -> seg1");
    assertEquals(st.contentToFileRelativeMs(30_000), 10_000L, "reindeer: 30s -> seg1 @ 10s");
    assertEquals(st.contentToSegment(41_000), 2, "reindeer: 41s -> seg2");
  }

  @Test
  public void reindeer_clampsSegmentButNotFileRelativeOverflow()
  {
    SegmentTimeline st = of(new long[][] {
        { T, 20_000 }, { T + 20_000, 21_000 }, { T + 41_000, 30_000 } });
    // Segment index clamps to the last segment; file-relative is NOT upper-clamped
    // (SeekWindow owns the final per-file clamp at OPEN, a separate authority).
    assertEquals(st.contentToSegment(999_999), 2, "reindeer: past-end -> last segment");
    assertEquals(st.contentToFileRelativeMs(999_999), 999_999L - 41_000L,
        "reindeer: past-end file-relative is raw overflow, not clamped");
    // Negative clamps to seg0 @ 0.
    assertEquals(st.contentToSegment(-5_000), 0, "reindeer: negative -> seg0");
    assertEquals(st.contentToFileRelativeMs(-5_000), 0L, "reindeer: negative -> 0");
  }

  @Test
  public void reindeer_fileRelativeToContentRoundTrips()
  {
    SegmentTimeline st = of(new long[][] {
        { T, 20_000 }, { T + 20_000, 21_000 }, { T + 41_000, 30_000 } });
    assertEquals(st.fileRelativeToContentMs(2, 10_000), 51_000L, "reindeer: seg2+10s -> content 51s");
    assertEquals(st.contentToSegment(51_000), 2, "reindeer: content 51s -> seg2");
    assertEquals(st.contentToFileRelativeMs(51_000), 10_000L, "reindeer: content 51s -> seg2 @ 10s");
  }

  // ---- 15min + 14min OUTAGE + 31min (gapped) --------------------------------

  private static final long GAP_MS = 14 * 60_000L;
  private static final long SEG1_START = T + 15 * 60_000L + GAP_MS;

  private static SegmentTimeline gapped()
  {
    return of(new long[][] { { T, 15 * 60_000L }, { SEG1_START, 31 * 60_000L } });
  }

  @Test
  public void gapped_contentTimelineCollapsesTheGap()
  {
    SegmentTimeline st = gapped();
    assertEquals(st.contentBaseMs(1), 900_000L, "gapped: seg1 content base == 15min (gap collapsed)");
    assertEquals(st.totalContentMs(), 46 * 60_000L, "gapped: total content == 15+31 min");
  }

  @Test
  public void gapped_exposesRealWallClockGap()
  {
    SegmentTimeline st = gapped();
    assertEquals(st.gapBeforeMs(1), 840_000L, "gapped: 14min gap before seg1");
    assertTrue(st.hasGaps(), "gapped: hasGaps");
  }

  @Test
  public void gapped_skipTargetAfterBoundaryOpensSeg1AtRightOffset()
  {
    SegmentTimeline st = gapped();
    // Skip to content 16:00 -> seg1, 1min into the file.
    assertEquals(st.contentToSegment(16 * 60_000L), 1, "gapped: 16min content -> seg1");
    assertEquals(st.contentToFileRelativeMs(16 * 60_000L), 60_000L, "gapped: 16min -> seg1 @ 1min");
  }

  // ---- server-only epoch mapping (toRelativeMediaTime backbone) --------------

  @Test
  public void segmentForEpochMs_mapsEpochToSegment()
  {
    SegmentTimeline st = gapped();
    assertEquals(st.segmentForEpochMs(T + 100_000), 0, "epoch in seg0 -> 0");
    assertEquals(st.segmentForEpochMs(SEG1_START + 50_000), 1, "epoch in seg1 -> 1");
  }

  @Test
  public void segmentForEpochMs_growingTailIsNotClamped()
  {
    SegmentTimeline st = gapped();
    // An epoch well past the (open-time frozen) end of the last segment must
    // still resolve to the last segment, so the live tail keeps reporting a
    // monotonically increasing content position instead of pinning at the
    // snapshot duration.
    long farPastFrozenEnd = SEG1_START + 90 * 60_000L;
    assertEquals(st.segmentForEpochMs(farPastFrozenEnd), 1, "epoch past frozen end -> last segment");
    // The content-time conversion the wiring performs: base + (epoch - segStart).
    long content = st.contentBaseMs(1) + (farPastFrozenEnd - st.startEpochMs(1));
    assertEquals(content, 900_000L + 90 * 60_000L, "live-tail content grows past frozen duration");
  }

  @Test
  public void gappedConversion_doesNotOverflowScrubberLikeNaiveSubtract()
  {
    SegmentTimeline st = gapped();
    // A position 5 minutes into seg1. Raw epoch = SEG1_START + 5min.
    long epoch = SEG1_START + 5 * 60_000L;
    // Naive "subtract recordingStartEpochMs (== seg0 start)" is gap-INCLUSIVE:
    long naive = epoch - T; // = 15min + 14min gap + 5min = 34min
    assertEquals(naive, 34 * 60_000L, "naive subtract is gap-inclusive (34min)");
    // Correct content-time conversion collapses the gap -> 20min, which fits
    // inside the gap-collapsed duration (46min) so the scrubber does not overflow.
    int seg = st.segmentForEpochMs(epoch);
    long content = st.contentBaseMs(seg) + (epoch - st.startEpochMs(seg));
    assertEquals(content, 20 * 60_000L, "gap-collapsed content == 20min");
    assertTrue(content <= st.totalContentMs(), "content fits within duration (no overflow)");
  }

  // ---- degenerate ------------------------------------------------------------

  @Test
  public void empty_isSafe()
  {
    SegmentTimeline st = new SegmentTimeline(new java.io.File[0], new long[0], new long[0], new long[0]);
    assertEquals(st.segmentCount(), 0, "empty: count 0");
    assertEquals(st.totalContentMs(), 0L, "empty: total 0");
    assertEquals(st.contentToSegment(1000), -1, "empty: seg lookup -1");
    assertEquals(st.segmentForEpochMs(1000), -1, "empty: epoch lookup -1");
  }

  @Test
  public void fromMediaFile_nullIsNull()
  {
    assertNull(SegmentTimeline.fromMediaFile(null), "fromMediaFile(null) -> null");
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void mismatchedArrayLengths_throw()
  {
    new SegmentTimeline(new java.io.File[2], new long[1], new long[2], new long[2]);
  }
}
