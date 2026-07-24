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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.testng.Assert.*;

/**
 * Verifies that {@link Atsc3StppExtractor}'s incremental pass logic
 * ({@code processWindowRaw} + {@code reconcile}) reproduces exactly what a
 * single full {@link Atsc3StppExtractor#extract}-style pass would, when the
 * same raw cues are fed to it in overlapping windows the way a live tail
 * re-scan does.
 *
 * <p>These tests exercise the pure logic (no ffmpeg / no broadcast stream):
 * they build synthetic char-by-char roll-up TTML cues, then drive them through
 * the incremental state machine in windows and compare against the full
 * reference coalescing.
 */
public class Atsc3StppExtractorIncrementalTest
{
  private static final double EPS = 1e-6;

  /** Builds a raw (un-normalized) STPP-style cue with an absolute epoch begin. */
  private static CaptionEvent raw(double begin, double end, String text)
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
   * Synthesizes a broadcast-like sequence: several sentences, each rolled up
   * word by word (the "typewriter" pattern coalesce collapses), on an absolute
   * wall-clock-like epoch (starts at 40000s).
   */
  private static List<CaptionEvent> syntheticRawCues()
  {
    List<CaptionEvent> out = new ArrayList<>();
    double t = 40000.0;
    String[] sentences = {
        "Good evening everyone",
        "The weather today is sunny",
        "Traffic is light on the highway",
        "Back to you in the studio",
        "And now the sports report",
    };
    for (String s : sentences)
    {
      String[] words = s.split(" ");
      StringBuilder acc = new StringBuilder();
      for (String w : words)
      {
        if (acc.length() > 0) acc.append(' ');
        acc.append(w);
        out.add(raw(t, t + 0.5, acc.toString()));
        t += 0.5;
      }
      t += 1.0; // gap before next sentence
    }
    return out;
  }

  /** Reference: the full-file result — coalesce everything after normalizing to first begin. */
  private static List<CaptionEvent> fullReference(List<CaptionEvent> rawAll)
  {
    List<CaptionEvent> copy = new ArrayList<>(rawAll);
    Atsc3StppExtractor.normalizeTimestamps(copy);
    java.util.Collections.sort(copy);
    return CaptionEvent.coalesce(copy);
  }

  @Test
  public void incrementalUnionEqualsFullRescan()
  {
    List<CaptionEvent> rawAll = syntheticRawCues();
    List<CaptionEvent> reference = fullReference(rawAll);

    Atsc3StppExtractor.StppIncrementalState state = new Atsc3StppExtractor.StppIncrementalState();
    List<CaptionEvent> collectedFinal = new ArrayList<>();
    Set<String> seenKeys = new HashSet<>();

    double minBegin = rawAll.get(0).getBeginSeconds();
    double maxBegin = rawAll.get(rawAll.size() - 1).getBeginSeconds();
    double step = 2.0, overlap = 4.0;

    for (double edge = minBegin + step; edge <= maxBegin + 10.0; edge += step)
    {
      double resumeAbs = minBegin + Math.max(0.0, state.resumeSeconds);
      double lo = Math.max(minBegin, resumeAbs - overlap);
      List<CaptionEvent> window = new ArrayList<>();
      for (CaptionEvent e : rawAll)
        if (e.getBeginSeconds() >= lo && e.getBeginSeconds() <= edge) window.add(e);

      Atsc3StppExtractor.IncrementalResult r = Atsc3StppExtractor.processWindowRaw(window, state);
      for (CaptionEvent f : r.newlyFinalized)
      {
        String key = f.getService() + "|" + Math.round(f.getBeginSeconds() * 1000) + "|" + f.getText();
        assertTrue(seenKeys.add(key), "duplicate finalized cue emitted: " + f);
        collectedFinal.add(f);
      }
    }

    // Flush: one full pass so the last still-open sentence is knowable as a tail.
    Atsc3StppExtractor.IncrementalResult last = Atsc3StppExtractor.processWindowRaw(rawAll, state);
    List<CaptionEvent> tail = new ArrayList<>(last.provisionalTail);

    // Every finalized reference cue except possibly the last (still-open) one must
    // have been emitted incrementally with identical timing + text.
    for (int i = 0; i < reference.size() - 1; i++)
    {
      CaptionEvent ref = reference.get(i);
      CaptionEvent match = find(collectedFinal, ref);
      assertNotNull(match, "reference cue never finalized incrementally: " + ref);
      assertEquals(match.getBeginSeconds(), ref.getBeginSeconds(), EPS, "begin mismatch for " + ref);
      assertEquals(match.getEndSeconds(), ref.getEndSeconds(), EPS, "end mismatch for " + ref);
      assertEquals(match.getText(), ref.getText());
    }

    CaptionEvent lastRef = reference.get(reference.size() - 1);
    boolean present = find(collectedFinal, lastRef) != null || find(tail, lastRef) != null;
    assertTrue(present, "final reference cue neither finalized nor provisional: " + lastRef);
  }

  @Test
  public void epochAnchorStaysFrozen()
  {
    List<CaptionEvent> rawAll = syntheticRawCues();
    Atsc3StppExtractor.StppIncrementalState state = new Atsc3StppExtractor.StppIncrementalState();

    // First window: only the first sentence — sets the epoch anchor.
    List<CaptionEvent> w1 = new ArrayList<>();
    double firstBegin = rawAll.get(0).getBeginSeconds();
    for (CaptionEvent e : rawAll)
      if (e.getBeginSeconds() < firstBegin + 3.0) w1.add(e);
    Atsc3StppExtractor.processWindowRaw(w1, state);
    double frozen = state.epochOffsetSeconds;
    assertEquals(frozen, firstBegin, EPS, "epoch anchor should equal the first cue begin");

    // A much later window must NOT move the anchor (its own min begin is larger).
    List<CaptionEvent> wLate = new ArrayList<>();
    double lateBegin = rawAll.get(rawAll.size() - 1).getBeginSeconds();
    for (CaptionEvent e : rawAll)
      if (e.getBeginSeconds() > lateBegin - 3.0) wLate.add(e);
    Atsc3StppExtractor.processWindowRaw(wLate, state);
    assertEquals(state.epochOffsetSeconds, frozen, EPS, "epoch anchor must never be recomputed");

    assertFalse(state.finalizedCues.isEmpty());
    assertEquals(state.finalizedCues.get(0).getBeginSeconds(), 0.0, EPS,
        "first finalized cue must be normalized to zero");
  }

  @Test
  public void provisionalTailIsRevisedNotDuplicated()
  {
    Atsc3StppExtractor.StppIncrementalState state = new Atsc3StppExtractor.StppIncrementalState();

    // Window 1: a sentence mid-roll-up ("Hello" then "Hello there").
    List<CaptionEvent> w1 = new ArrayList<>();
    w1.add(raw(100.0, 100.5, "Hello"));
    w1.add(raw(100.5, 101.0, "Hello there"));
    Atsc3StppExtractor.IncrementalResult r1 = Atsc3StppExtractor.processWindowRaw(w1, state);
    assertTrue(r1.newlyFinalized.isEmpty(), "an open sentence must not finalize yet");
    assertEquals(r1.provisionalTail.size(), 1);
    assertEquals(r1.provisionalTail.get(0).getText(), "Hello there");

    // Window 2 (overlapping): the sentence keeps rolling, then a NEW sentence starts.
    List<CaptionEvent> w2 = new ArrayList<>();
    w2.add(raw(100.0, 100.5, "Hello"));
    w2.add(raw(100.5, 101.0, "Hello there"));
    w2.add(raw(101.0, 101.5, "Hello there world"));
    w2.add(raw(103.0, 103.5, "Next sentence"));
    Atsc3StppExtractor.IncrementalResult r2 = Atsc3StppExtractor.processWindowRaw(w2, state);

    assertEquals(r2.newlyFinalized.size(), 1, "exactly one sentence should finalize");
    assertEquals(r2.newlyFinalized.get(0).getText(), "Hello there world");
    assertEquals(state.finalizedCues.size(), 1, "no duplicate of the revised sentence");
    assertEquals(r2.provisionalTail.get(0).getText(), "Next sentence");
  }

  @Test
  public void finalizedStateRespectsSafetyCeiling()
  {
    Atsc3StppExtractor.StppIncrementalState state = new Atsc3StppExtractor.StppIncrementalState();

    // One window with more distinct sentences than the ceiling. Each is a single
    // non-extending cue, so coalesce keeps them separate; all but the last close.
    int n = Atsc3StppExtractor.MAX_FINALIZED_CUES + 50;
    List<CaptionEvent> window = new ArrayList<>(n);
    for (int i = 0; i < n; i++)
    {
      double t = 100.0 + i * 2.0;
      window.add(raw(t, t + 0.5, "sentence number " + i));
    }

    Atsc3StppExtractor.processWindowRaw(window, state);

    // Retained finalized cues must be capped, and dedupe keys must track them.
    assertEquals(state.finalizedCues.size(), Atsc3StppExtractor.MAX_FINALIZED_CUES,
        "finalized cues must be capped at the safety ceiling");
    assertEquals(state.emittedKeys.size(), state.finalizedCues.size(),
        "dedupe keys must stay in lockstep with retained cues");

    // Oldest cues were evicted; the newest retained ones are still present.
    assertEquals(state.finalizedCues.get(0).getText(), "sentence number 49",
        "oldest cues should have been dropped first");
    CaptionEvent lastRetained = state.finalizedCues.get(state.finalizedCues.size() - 1);
    assertEquals(lastRetained.getText(), "sentence number " + (n - 2),
        "the most recent finalized cue should be retained");
  }

  // ── Media-time-0 anchor calibration ──────────────────────────────────

  /**
   * Silent-open case: the recording opens with several seconds of no captions,
   * so the first cue-bearing packet is not packet 0. The calibrated anchor must
   * land the first cue at its true media time N (from container PTS), NOT at 0.
   */
  @Test
  public void calibratedAnchorLandsFirstCueAtMediaTimeN()
  {
    // Video starts at container time 100.0; STPP packets every 2s from there.
    double videoStart = 100.0;
    double[] pts = new double[16];
    for (int i = 0; i < pts.length; i++) pts[i] = videoStart + i * 2.0;

    // First caption doesn't appear until packet 3 → media time N = 6.0s.
    int firstDoc = 3;
    double N = pts[firstDoc] - videoStart; // 6.0
    double firstBegin = 50000.0;            // absolute broadcast wall-clock
    // Second cue-bearing doc one packet later, TTML clock advancing 1:1.
    int secondDoc = 4;
    double secondBegin = firstBegin + (pts[secondDoc] - pts[firstDoc]); // +2.0

    Double offset = Atsc3StppExtractor.calibrateOffset(videoStart, pts, firstDoc, firstBegin, secondDoc, secondBegin);
    assertNotNull(offset, "calibration should succeed for a clean 1:1 pairing");

    // Drive one window through processWindowRaw with the calibrated offset and
    // assert the first finalized cue sits at N, not 0.
    Atsc3StppExtractor.StppIncrementalState state = new Atsc3StppExtractor.StppIncrementalState();
    List<CaptionEvent> window = new ArrayList<>();
    window.add(raw(firstBegin, firstBegin + 0.5, "first sentence"));
    window.add(raw(secondBegin, secondBegin + 0.5, "second sentence"));
    window.add(raw(secondBegin + 2.0, secondBegin + 2.5, "third sentence")); // closes the second

    Atsc3StppExtractor.processWindowRaw(window, state, offset);
    List<CaptionEvent> finalized = state.finalizedSnapshot();
    assertFalse(finalized.isEmpty(), "expected at least one finalized cue");
    assertEquals(finalized.get(0).getBeginSeconds(), N, EPS,
        "calibrated anchor must place the first cue at its true media time, not 0");
  }

  /**
   * Fallback case: when no calibrated offset is available (null), the anchor
   * must reproduce today's min-begin behaviour exactly — first cue at 0.
   */
  @Test
  public void nullCalibrationReproducesMinBeginBehaviour()
  {
    double firstBegin = 50000.0;
    Atsc3StppExtractor.StppIncrementalState state = new Atsc3StppExtractor.StppIncrementalState();
    List<CaptionEvent> window = new ArrayList<>();
    window.add(raw(firstBegin, firstBegin + 0.5, "first sentence"));
    window.add(raw(firstBegin + 2.0, firstBegin + 2.5, "second sentence"));
    window.add(raw(firstBegin + 4.0, firstBegin + 4.5, "third sentence"));

    Atsc3StppExtractor.processWindowRaw(window, state, null);
    List<CaptionEvent> finalized = state.finalizedSnapshot();
    assertFalse(finalized.isEmpty());
    assertEquals(finalized.get(0).getBeginSeconds(), 0.0, EPS,
        "min-begin fallback must place the earliest cue at 0 (historical behaviour)");
    assertEquals(state.epochOffsetSeconds, firstBegin, EPS,
        "fallback offset must equal the minimum raw begin");
  }

  /** Guard: a first cue-bearing doc beyond the probed packet window → reject (null). */
  @Test
  public void calibrationRejectsIndexBeyondProbedWindow()
  {
    double[] pts = new double[12];
    for (int i = 0; i < pts.length; i++) pts[i] = i * 2.0;
    // First cue-bearing doc index 20 is past the 12-packet probe window.
    Double offset = Atsc3StppExtractor.calibrateOffset(0.0, pts, 20, 1000.0, null, null);
    assertNull(offset, "out-of-window doc index must fall back to min-begin");
  }

  /** Guard: unavailable PTS (NaN) at the first cue-bearing doc → reject (null). */
  @Test
  public void calibrationRejectsNaNPts()
  {
    double[] pts = { 0.0, 2.0, Double.NaN, 6.0 };
    Double offset = Atsc3StppExtractor.calibrateOffset(0.0, pts, 2, 1000.0, null, null);
    assertNull(offset, "NaN PTS at the anchor packet must fall back to min-begin");
  }

  /** Guard: TTML clock not advancing 1:1 with container PTS → reject (null). */
  @Test
  public void calibrationRejectsNonUnitySlope()
  {
    double[] pts = new double[16];
    for (int i = 0; i < pts.length; i++) pts[i] = i * 2.0;
    // Second cue's begin advances 4s while PTS advances 2s → slope 2.0 (skew).
    Double offset = Atsc3StppExtractor.calibrateOffset(0.0, pts, 0, 1000.0, 1, 1004.0);
    assertNull(offset, "clock/PTS skew must invalidate the doc-packet pairing");
  }

  /** Guard: implausibly large implied media time → reject (null). */
  @Test
  public void calibrationRejectsImplausibleMediaTime()
  {
    double[] pts = { 0.0, 2.0, 4.0 };
    // videoStart far in the future vs pts ⇒ media time hugely negative → reject;
    // and the positive-but-absurd direction:
    double[] ptsBig = { 100000.0, 100002.0 };
    assertNull(Atsc3StppExtractor.calibrateOffset(0.0, ptsBig, 0, 1000.0, null, null),
        "absurdly large media time must be rejected");
    assertNull(Atsc3StppExtractor.calibrateOffset(50.0, pts, 0, 1000.0, null, null),
        "strongly negative media time must be rejected");
  }

  private static CaptionEvent find(List<CaptionEvent> list, CaptionEvent ref)
  {
    for (CaptionEvent e : list)
    {
      if (e.getText().equals(ref.getText()) &&
          Math.abs(e.getBeginSeconds() - ref.getBeginSeconds()) < 1e-3)
        return e;
    }
    return null;
  }
}
