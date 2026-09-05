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

import org.testng.annotations.Test;

/**
 * Tests for {@link SeekWindow#applyClamp(long, long, long)} -- the pure clamp
 * arithmetic behind the pull-xcode {@code -ss} guard. The state-selection and
 * ffmpeg probe are exercised live; this pins the math that decides whether a
 * requested seek is pulled back below a completed file's demuxable end.
 *
 * <p>The motivating field case: a resume position of {@code 266838} ms (a
 * wall-clock watch span) against a file whose real {@code Duration} is
 * {@code 265882} ms must be pulled back below the end, leaving a keyframe tail
 * margin, instead of seeking past EOF and producing an empty fMP4.
 */
public class SeekWindowTest
{
  @Test
  public void pastEnd_isClampedBelowEndByTailMargin()
  {
    // 266838 requested, real end 265882, 1500 margin -> 264382
    assertEquals(SeekWindow.applyClamp(266838L, 265882L, 1500L), 264382L,
        "a resume past the demuxable end clamps to end - tailMargin");
  }

  @Test
  public void withinContent_passesThroughUnchanged()
  {
    assertEquals(SeekWindow.applyClamp(100000L, 265882L, 1500L), 100000L,
        "a normal in-content seek is not moved");
  }

  @Test
  public void nearButBeforeEnd_pulledBackToKeyframeMargin()
  {
    // 265000 is before the 265882 end but inside the tail margin; a copy-path
    // input seek there could land past the last usable keyframe, so it is pulled
    // back to the same end - margin ceiling.
    assertEquals(SeekWindow.applyClamp(265000L, 265882L, 1500L), 264382L,
        "a seek inside the tail margin is pulled back to the keyframe-safe ceiling");
  }

  @Test
  public void unknownEnd_isNoOp()
  {
    assertEquals(SeekWindow.applyClamp(266838L, 0L, 1500L), 266838L,
        "an unknown (0) playable end must not clamp -- worst case equals today");
    assertEquals(SeekWindow.applyClamp(266838L, -1L, 1500L), 266838L,
        "a negative playable end must not clamp");
  }

  @Test
  public void zeroOrNegativeRequest_passesThrough()
  {
    assertEquals(SeekWindow.applyClamp(0L, 265882L, 1500L), 0L,
        "a from-start (0) request is untouched");
    assertEquals(SeekWindow.applyClamp(-5L, 265882L, 1500L), -5L,
        "a negative request is untouched");
  }

  @Test
  public void marginLargerThanEnd_floorsAtZero()
  {
    assertEquals(SeekWindow.applyClamp(266838L, 1000L, 1500L), 0L,
        "when the tail margin exceeds the end, the ceiling floors at 0, never negative");
  }

  @Test
  public void zeroTailMargin_clampsExactlyToEnd()
  {
    assertEquals(SeekWindow.applyClamp(266838L, 265882L, 0L), 265882L,
        "a zero tail margin clamps to the exact demuxable end");
  }

  // --- clampToWindow: the shared runtime-path clamp (VideoFrame skip + MiniPlayer push) ---

  @Test
  public void window_forwardPastEdge_saturatesAtEdgeMinusGuard()
  {
    // VideoFrame case (file-epoch units): target beyond the live edge is pulled
    // back to edge - guard, not past it.
    assertEquals(SeekWindow.clampToWindow(1_000_500L, 1_000_000L, 1_000_400L, 4000L),
        1_000_000L, "forward skip past the live edge saturates at max(floor, edge-guard)");
  }

  @Test
  public void window_backwardBelowFloor_saturatesAtFloor()
  {
    assertEquals(SeekWindow.clampToWindow(999_000L, 1_000_000L, 1_050_000L, 4000L),
        1_000_000L, "a backward skip below the window start saturates at the floor");
  }

  @Test
  public void window_withinWindow_passesThrough()
  {
    assertEquals(SeekWindow.clampToWindow(1_020_000L, 1_000_000L, 1_050_000L, 4000L),
        1_020_000L, "an in-window target is not moved");
  }

  @Test
  public void window_unknownEnd_isNoOp()
  {
    assertEquals(SeekWindow.clampToWindow(1_020_000L, 1_000_000L, 0L, 4000L),
        1_020_000L, "an unknown (<=0) end must not clamp -- matches each site's no-op-on-unknown");
    assertEquals(SeekWindow.clampToWindow(1_020_000L, 1_000_000L, -1L, 4000L),
        1_020_000L, "a negative end must not clamp");
  }

  @Test
  public void window_miniPlayerEquivalence_floorZeroNoMargin()
  {
    // MiniPlayer used max(0, min(seek, availEnd)); clampToWindow(seek,0,availEnd,0)
    // must be identical across the interesting cases.
    long availEnd = 115_000L;
    for (long seek : new long[] { -5L, 0L, 50_000L, 115_000L, 200_000L })
    {
      long legacy = Math.max(0L, Math.min(seek, availEnd));
      assertEquals(SeekWindow.clampToWindow(seek, 0L, availEnd, 0L), legacy,
          "clampToWindow must equal the prior MiniPlayer max(0,min(seek,availEnd)) for seek=" + seek);
    }
  }

  @Test
  public void window_videoFrameEquivalence_epochFloorAndGuard()
  {
    // VideoFrame used hi = max(floor, edge-guard); clamped = min(max(target,floor),hi).
    long floor = 1_000_000L, edge = 1_060_000L, guard = 4000L;
    for (long target : new long[] { 900_000L, 1_000_000L, 1_030_000L, 1_058_000L, 1_100_000L })
    {
      long hi = Math.max(floor, edge - guard);
      long legacy = Math.min(Math.max(target, floor), hi);
      assertEquals(SeekWindow.clampToWindow(target, floor, edge, guard), legacy,
          "clampToWindow must equal the prior VideoFrame expression for target=" + target);
    }
  }

  // --- resolveLiveWindowMs: the shared NG live/timeshift window authority ---

  @Test
  public void liveWindow_liveGrowing_edgeMinusMargin()
  {
    // duration unknown (0, per caller contract for live) -> edge governs.
    long[] w = SeekWindow.resolveLiveWindowMs(45000L, 0L, 5000L);
    assertEquals(w[0], 0L, "live: safeSeekStart=0");
    assertEquals(w[1], 40000L, "live: safeSeekEnd = edge - margin");
    assertEquals(w[2], 45000L, "live: playableEnd = edge");
  }

  @Test
  public void liveWindow_knownDuration_wins_whenLargerThanEdge()
  {
    // timeshift into a known-length file: duration > served edge -> duration governs.
    long[] w = SeekWindow.resolveLiveWindowMs(50000L, 120000L, 5000L);
    assertEquals(w[2], 120000L, "timeshift: playableEnd = max(duration, edge) = duration");
    assertEquals(w[1], 115000L, "timeshift: safeSeekEnd = duration - margin");
  }

  @Test
  public void liveWindow_servedEdge_wins_whenLargerThanDuration()
  {
    // served past the nominal duration (live overrun) -> the truthful served edge wins.
    long[] w = SeekWindow.resolveLiveWindowMs(130000L, 120000L, 5000L);
    assertEquals(w[2], 130000L, "overrun: playableEnd = max(duration, edge) = edge");
    assertEquals(w[1], 125000L, "overrun: safeSeekEnd = edge - margin");
  }

  @Test
  public void liveWindow_nearStart_noMarginUnderflow()
  {
    // 2-3 s into a stream (< margin): stay seekable to the edge, never snap to 0.
    long[] w = SeekWindow.resolveLiveWindowMs(3000L, 0L, 5000L);
    assertEquals(w[1], 3000L, "near-start: safeSeekEnd = edge (no underflow below the edge)");
    assertEquals(w[2], 3000L, "near-start: playableEnd = edge");
  }

  @Test
  public void liveWindow_bothZero_isZero()
  {
    long[] w = SeekWindow.resolveLiveWindowMs(0L, 0L, 5000L);
    assertEquals(w[1], 0L, "unknown: safeSeekEnd 0");
    assertEquals(w[2], 0L, "unknown: playableEnd 0");
  }

  @Test
  public void liveWindow_negativeInputs_clampedToZero()
  {
    long[] w = SeekWindow.resolveLiveWindowMs(-100L, -5000L, 5000L);
    assertEquals(w[1], 0L, "negative: safeSeekEnd 0");
    assertEquals(w[2], 0L, "negative: playableEnd 0");
  }
}
