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

import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.*;

/**
 * Verifies {@link CaptionEvent#coalesce}'s overlap-clamp and minimum-dwell-time
 * logic, in particular the Issue B fix: a too-short cue with no forward slack
 * (the common back-to-back-dialogue case) must still be floored to
 * {@link CaptionEvent#MIN_CUE_DURATION_SECONDS} by reclaiming slack from
 * *before* the cue, instead of silently staying under-floor.
 */
public class CaptionEventTest
{
  private static final double EPS = 1e-6;

  private static CaptionEvent cue(double begin, double end, String text)
  {
    return CaptionEvent.builder()
        .language("eng")
        .service("ENG")
        .beginSeconds(begin)
        .endSeconds(end)
        .text(text)
        .build();
  }

  /**
   * Two back-to-back cues with zero forward gap (the previous behavior's
   * blind spot): the first cue's natural duration is only 0.3s and there is
   * no room after it (the next cue starts immediately), but there IS room
   * before it (a gap since media start). The fix must pull the first cue's
   * begin earlier to reach the 1.0s floor, rather than leaving it at 0.3s.
   */
  @Test
  public void backToBackShortCueBorrowsTimeFromBefore()
  {
    List<CaptionEvent> input = new ArrayList<>();
    input.add(cue(5.0, 5.3, "Quick line"));
    input.add(cue(5.3, 7.0, "Following line"));

    List<CaptionEvent> out = CaptionEvent.coalesce(input);
    assertEquals(out.size(), 2);

    CaptionEvent first = out.get(0);
    CaptionEvent second = out.get(1);

    assertEquals(first.getEndSeconds(), 5.3, EPS, "end must not overlap the next cue's begin");
    assertTrue(first.getEndSeconds() - first.getBeginSeconds() >= CaptionEvent.MIN_CUE_DURATION_SECONDS - EPS,
        "duration should reach the floor via a borrowed earlier begin: was " +
            (first.getEndSeconds() - first.getBeginSeconds()));
    assertEquals(first.getBeginSeconds(), 4.3, EPS, "begin should be pulled back exactly to the 1.0s floor");
    assertEquals(second.getText(), "Following line");
    assertEquals(second.getBeginSeconds(), 5.3, EPS, "second cue's begin must be unaffected");
  }

  /**
   * Chained short cues: each cue borrows from before it, but must never
   * overlap backward into the previous (already-adjusted) cue's end.
   */
  @Test
  public void chainedShortCuesNeverOverlapBackward()
  {
    List<CaptionEvent> input = new ArrayList<>();
    input.add(cue(0.2, 0.4, "One"));
    input.add(cue(0.4, 0.6, "Two"));
    input.add(cue(0.6, 0.8, "Three"));

    List<CaptionEvent> out = CaptionEvent.coalesce(input);
    assertEquals(out.size(), 3);

    for (int i = 1; i < out.size(); i++)
    {
      CaptionEvent prev = out.get(i - 1);
      CaptionEvent curr = out.get(i);
      assertTrue(curr.getBeginSeconds() >= prev.getEndSeconds() - EPS,
          "cue " + i + " must not overlap the previous cue's end: prevEnd=" + prev.getEndSeconds() +
              " currBegin=" + curr.getBeginSeconds());
    }
    // The very first cue can only borrow down to 0 (media start).
    assertEquals(out.get(0).getBeginSeconds(), 0.0, EPS);
  }

  /** Sanity: when ample forward gap exists, the original forward-extension path still applies. */
  @Test
  public void shortCueWithForwardSlackExtendsForward()
  {
    List<CaptionEvent> input = new ArrayList<>();
    input.add(cue(10.0, 10.2, "Short"));
    input.add(cue(20.0, 21.0, "Later"));

    List<CaptionEvent> out = CaptionEvent.coalesce(input);
    CaptionEvent first = out.get(0);
    assertEquals(first.getBeginSeconds(), 10.0, EPS, "begin should be untouched when forward slack suffices");
    assertEquals(first.getEndSeconds(), 11.0, EPS, "end should extend forward to the floor");
  }

  /** No slack on either side (truly continuous dialogue): duration stays under-floor, but never inverted. */
  @Test
  public void noSlackEitherSideKeepsShortDurationWithoutInversion()
  {
    List<CaptionEvent> input = new ArrayList<>();
    input.add(cue(0.0, 0.1, "A"));
    input.add(cue(0.1, 0.2, "B"));
    input.add(cue(0.2, 5.0, "C"));

    List<CaptionEvent> out = CaptionEvent.coalesce(input);
    assertEquals(out.size(), 3);
    for (CaptionEvent e : out)
    {
      assertTrue(e.getEndSeconds() >= e.getBeginSeconds(), "end must never precede begin: " + e);
    }
    // "B" has no room before (bounded by "A"'s adjusted end) or after (bounded by "C"'s begin).
    CaptionEvent b = out.get(1);
    assertEquals(b.getBeginSeconds(), 0.1, EPS);
    assertEquals(b.getEndSeconds(), 0.2, EPS);
  }

  /**
   * A long cue's dwell floor scales with its text length via the reading-speed
   * (characters-per-second) calculation, not just the flat 1.0s minimum -- a dense
   * line of dialogue needs more time on screen than a 3-word one.
   */
  @Test
  public void longCueGetsReadingSpeedFloorNotJustFlatMinimum()
  {
    // 64 characters / 16 cps = 4.0s -- well above the flat 1.0s floor.
    String longText = "This is a fairly long line of dialogue that needs more time!!!!";
    assertTrue(longText.length() > 60, "test setup sanity: text should be long enough to trigger the CPS floor");

    List<CaptionEvent> input = new ArrayList<>();
    input.add(cue(10.0, 10.5, longText));   // natural duration only 0.5s
    input.add(cue(30.0, 31.0, "Later"));    // ample forward slack available

    List<CaptionEvent> out = CaptionEvent.coalesce(input);
    CaptionEvent first = out.get(0);
    double expectedFloor = longText.length() / CaptionEvent.MIN_READING_CPS;
    assertTrue(expectedFloor > CaptionEvent.MIN_CUE_DURATION_SECONDS,
        "test setup sanity: reading-speed floor should exceed the flat floor for this text");
    assertEquals(first.getBeginSeconds(), 10.0, EPS, "begin should be untouched when forward slack suffices");
    assertEquals(first.getEndSeconds(), 10.0 + expectedFloor, EPS,
        "end should extend to the reading-speed floor, not just the flat 1.0s minimum");
  }

  /** Short cues (the common case) are unaffected: the reading-speed floor never exceeds the flat one. */
  @Test
  public void shortCueReadingSpeedFloorMatchesFlatFloor()
  {
    assertEquals(CaptionEvent.readingSpeedFloorSeconds("Hi"), CaptionEvent.MIN_CUE_DURATION_SECONDS, EPS);
    assertEquals(CaptionEvent.readingSpeedFloorSeconds(""), CaptionEvent.MIN_CUE_DURATION_SECONDS, EPS);
    assertEquals(CaptionEvent.readingSpeedFloorSeconds(null), CaptionEvent.MIN_CUE_DURATION_SECONDS, EPS);
  }

  // ── Hold-until-next (gap filling) ────────────────────────────────────────

  /**
   * With hold-until-next enabled, a cue whose natural end leaves a blank gap
   * before the next cue is extended forward to fill that gap (up to the next
   * cue's begin), so the caption stays on screen until the next one appears
   * instead of clearing early. The legacy overload leaves the gap.
   */
  @Test
  public void holdUntilNextFillsGapToNextCue()
  {
    List<CaptionEvent> input = new ArrayList<>();
    input.add(cue(10.0, 11.0, "First line"));   // natural end 11.0, next at 15.0 -> 4s gap
    input.add(cue(15.0, 16.0, "Second line"));

    // Legacy behavior: gap preserved, end stays at the reading floor (11.0).
    List<CaptionEvent> legacy = CaptionEvent.coalesce(input);
    assertEquals(legacy.get(0).getEndSeconds(), 11.0, EPS, "legacy overload must not fill the gap");

    // Hold enabled with an 8s cap: end extends to the next cue's begin (15.0).
    List<CaptionEvent> held = CaptionEvent.coalesce(input, 8.0);
    assertEquals(held.get(0).getBeginSeconds(), 10.0, EPS, "begin is untouched");
    assertEquals(held.get(0).getEndSeconds(), 15.0, EPS, "end should hold until the next cue begins");
    assertEquals(held.get(1).getBeginSeconds(), 15.0, EPS);
  }

  /**
   * The hold is capped: when the gap to the next cue exceeds the max-hold, the
   * cue clears after max-hold rather than lingering the entire silence.
   */
  @Test
  public void holdUntilNextIsCappedByMaxHold()
  {
    List<CaptionEvent> input = new ArrayList<>();
    input.add(cue(10.0, 11.0, "Talks then long silence"));
    input.add(cue(40.0, 41.0, "Much later"));   // 29s gap

    List<CaptionEvent> held = CaptionEvent.coalesce(input, 8.0);
    assertEquals(held.get(0).getEndSeconds(), 18.0, EPS,
        "end should be capped at begin + maxHold (10.0 + 8.0), not stretched to the next cue");
  }

  /** Hold never overlaps the next cue: the end is still capped at the next begin. */
  @Test
  public void holdUntilNextNeverOverlaps()
  {
    List<CaptionEvent> input = new ArrayList<>();
    input.add(cue(10.0, 12.0, "First"));
    input.add(cue(11.0, 13.0, "Overlapping next"));   // starts before first's natural end

    List<CaptionEvent> held = CaptionEvent.coalesce(input, 8.0);
    assertTrue(held.get(0).getEndSeconds() <= held.get(1).getBeginSeconds() + EPS,
        "held cue must not overlap the next cue's begin");
  }

  /** The last cue on a track (no following cue) is unaffected by hold-until-next. */
  @Test
  public void holdUntilNextLeavesLastCueUntouched()
  {
    List<CaptionEvent> input = new ArrayList<>();
    input.add(cue(10.0, 11.0, "First"));
    input.add(cue(15.0, 16.0, "Last line"));

    List<CaptionEvent> held = CaptionEvent.coalesce(input, 8.0);
    CaptionEvent last = held.get(held.size() - 1);
    assertEquals(last.getEndSeconds(), 16.0, EPS, "last cue keeps its natural (floored) end");
  }

  /** A non-positive cap disables hold entirely (identical to the legacy overload). */
  @Test
  public void holdUntilNextDisabledWhenCapNonPositive()
  {
    List<CaptionEvent> input = new ArrayList<>();
    input.add(cue(10.0, 11.0, "First line"));
    input.add(cue(15.0, 16.0, "Second line"));

    List<CaptionEvent> zero = CaptionEvent.coalesce(input, 0.0);
    assertEquals(zero.get(0).getEndSeconds(), 11.0, EPS, "cap <= 0 must preserve the gap");
  }
}
