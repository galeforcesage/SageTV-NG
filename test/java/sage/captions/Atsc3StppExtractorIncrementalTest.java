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

  // ── Issue B: overlap seek must never re-enter an already-finalized cue ──

  /**
   * Reproduces the real-world "long already-finalized cue re-derived as a
   * truncated duplicate" bug: a long char-by-char roll-up cue (e.g. a music
   * note marker held up for several seconds) finalizes once the next
   * sentence's tail appears. Its own duration exceeds the fixed overlap
   * look-back margin, so naively seeking to {@code resumeSeconds - overlap}
   * (the pre-fix formula) lands back inside that cue's raw time span. If a
   * later incremental window is built from that seek point, it misses the
   * long cue's *first* raw fragment (which lies before the seek point) and
   * only sees its later fragment(s) — coalesce() then derives a truncated,
   * differently-timed duplicate of an already-emitted cue.
   *
   * <p>This test drives {@code processWindowRaw}/{@code reconcile} directly
   * (the seek computation itself lives in {@code extractIncremental}, which
   * needs ffmpeg) and asserts two things: (1) after the long cue finalizes,
   * {@code state.resumeFloorSeconds} is advanced to (at least) that cue's
   * end, so the same seek formula {@code extractIncremental} uses —
   * {@code max(resumeFloorSeconds, resumeSeconds - overlap)} — can never
   * land before it; and (2) driving a subsequent window built from that
   * fixed seek point (rather than the old, unclamped one) never produces a
   * duplicate/truncated re-finalization of the long cue.
   */
  @Test
  public void overlapSeekNeverReenterAlreadyFinalizedCue()
  {
    double overlap = 4.0; // matches Atsc3StppExtractor's default stpp_window_overlap_seconds

    // A long roll-up cue: two raw fragments 8s apart (a real "note" marker
    // held up across several packets), well past the fixed overlap margin.
    // Values are absolute/raw TTML times (the epoch anchor, frozen from this
    // window's minimum begin, is subtracted back out below to interpret
    // state.resumeSeconds/resumeFloorSeconds, which live in normalized
    // media-relative seconds -- the same domain ffmpeg's -ss expects).
    CaptionEvent longRaw1 = raw(100.0, 100.5, "note");
    CaptionEvent longRaw2 = raw(108.0, 110.0, "note note"); // extends "note" -> single coalesced cue
    // Next sentence's first fragment -- makes longRaw2 finalize as the closed cue.
    CaptionEvent nextRaw = raw(111.0, 111.5, "Hello");

    Atsc3StppExtractor.StppIncrementalState state = new Atsc3StppExtractor.StppIncrementalState();
    Atsc3StppExtractor.IncrementalResult r1 = Atsc3StppExtractor.processWindowRaw(
        new ArrayList<>(List.of(longRaw1, longRaw2, nextRaw)), state);

    assertEquals(r1.newlyFinalized.size(), 1, "the long roll-up cue should finalize once the next sentence starts");
    CaptionEvent longFinalized = r1.newlyFinalized.get(0);
    assertEquals(longFinalized.getText(), "note note");
    double epoch = state.epochOffsetSeconds; // frozen anchor == longRaw1.begin == 100.0
    assertEquals(longFinalized.getBeginSeconds(), 100.0 - epoch, EPS,
        "finalized cue must keep its TRUE (normalized) begin -- the first fragment");
    assertEquals(longFinalized.getEndSeconds(), 110.0 - epoch, EPS);

    // The floor must have advanced to (at least) the long cue's normalized end.
    double longCueNormalizedEnd = 110.0 - epoch;
    assertTrue(state.resumeFloorSeconds >= longCueNormalizedEnd - EPS,
        "resumeFloorSeconds must advance to the finalized cue's end: was " + state.resumeFloorSeconds);

    // Reproduce the seek formula extractIncremental() uses (normalized seconds).
    double seekSeconds = Math.max(state.resumeFloorSeconds, state.resumeSeconds - overlap);
    seekSeconds = Math.max(0.0, seekSeconds);

    // Sanity: WITHOUT the floor, the naive formula would land inside the gap
    // between the long cue's two raw fragments -- proving this scenario
    // really does reproduce the bug: a window seeked from there would miss
    // the long cue's *first* fragment (normalized begin 0.0) while still
    // capturing its second fragment (normalized begin 8.0).
    double naiveSeek = Math.max(0.0, state.resumeSeconds - overlap);
    assertTrue(naiveSeek > 0.0 && naiveSeek < (108.0 - epoch),
        "test setup sanity: naive seek should land after the long cue's first fragment " +
            "but before its second, was " + naiveSeek);

    // The floor-respecting seek must NOT land inside the long cue's span.
    assertTrue(seekSeconds >= longCueNormalizedEnd - EPS,
        "clamped seek must not re-enter the already-finalized long cue: was " + seekSeconds);

    // Drive a follow-up window built from the fixed seek point. Raw TTML docs
    // are keyed by absolute time, so convert the normalized seek back to the
    // raw domain (adding the frozen epoch back) before filtering -- exactly
    // mirroring how a real ffmpeg -ss (media-relative) determines which raw
    // TTML packets fall inside the re-extracted window.
    double rawSeek = seekSeconds + epoch;
    List<CaptionEvent> window2 = new ArrayList<>();
    for (CaptionEvent e : List.of(longRaw1, longRaw2, nextRaw))
      if (e.getBeginSeconds() >= rawSeek) window2.add(e);
    // Confirms the fixed seek correctly omits BOTH of the long cue's raw
    // fragments (already fully finalized), leaving only the next sentence.
    assertEquals(window2.size(), 1);
    assertEquals(window2.get(0).getText(), "Hello");

    Atsc3StppExtractor.IncrementalResult r2 = Atsc3StppExtractor.processWindowRaw(window2, state);

    assertTrue(r2.newlyFinalized.isEmpty(), "no cue should re-finalize from the follow-up window: " + r2.newlyFinalized);
    assertEquals(state.finalizedCues.size(), 1, "the long cue must not be duplicated");
    assertEquals(state.finalizedCues.get(0).getBeginSeconds(), 100.0 - epoch, EPS,
        "the single finalized cue must retain its TRUE begin, not a truncated one");
  }

  /**
   * Defense-in-depth: {@link Atsc3StppExtractor#isFuzzyDuplicate} (accessed here indirectly via
   * {@code processWindowRaw}/{@code reconcile}) must catch a truncated duplicate even in a
   * scenario the root-cause {@code resumeFloorSeconds} fix wasn't designed for -- e.g. a
   * hypothetical future seek/window edge case that re-derives an already-finalized cue with a
   * begin time that happens to fall outside {@link Atsc3StppExtractor#keyOf}'s exact-match
   * tolerance (a different millisecond-rounded begin defeats that exact key) but still lands
   * within a few seconds of the original cue's true span. This is simulated directly (bypassing
   * the normal seek-floor path entirely) by feeding a second window whose coalesced cue shares
   * the first window's exact text but a slightly different begin/end.
   */
  @Test
  public void fuzzyDedupeCatchesTruncatedDuplicateEvadingExactKey()
  {
    // Window 1: finalizes "Hello there" (begin=10.0, end=12.0 raw == normalized, since this
    // window's first raw begin becomes the frozen epoch anchor).
    CaptionEvent raw1a = raw(10.0, 10.5, "Hello");
    CaptionEvent raw1b = raw(10.5, 12.0, "Hello there");
    CaptionEvent raw1Next = raw(13.0, 13.5, "Bye"); // closes "Hello there" as non-tail

    Atsc3StppExtractor.StppIncrementalState state = new Atsc3StppExtractor.StppIncrementalState();
    Atsc3StppExtractor.IncrementalResult r1 = Atsc3StppExtractor.processWindowRaw(
        new ArrayList<>(List.of(raw1a, raw1b, raw1Next)), state);

    assertEquals(r1.newlyFinalized.size(), 1);
    assertEquals(r1.newlyFinalized.get(0).getText(), "Hello there");
    assertEquals(state.finalizedCues.size(), 1);

    // Window 2 (simulating an unanticipated edge case, NOT a normal seeked re-scan): a
    // near-duplicate of "Hello there" with a begin ~0.3s later -- close enough to be the same
    // real cue, but far enough to produce a DIFFERENT keyOf() (different rounded begin-ms), so
    // the exact-match dedupe alone would NOT catch it. A different following sentence closes it.
    CaptionEvent raw2a = raw(10.3, 10.8, "Hello");
    CaptionEvent raw2b = raw(10.8, 12.3, "Hello there");
    CaptionEvent raw2Next = raw(14.0, 14.5, "Goodnight");

    Atsc3StppExtractor.IncrementalResult r2 = Atsc3StppExtractor.processWindowRaw(
        new ArrayList<>(List.of(raw2a, raw2b, raw2Next)), state);

    assertTrue(r2.newlyFinalized.isEmpty(),
        "fuzzy dedupe should suppress the near-duplicate 'Hello there' cue: " + r2.newlyFinalized);
    assertEquals(state.finalizedCues.size(), 1,
        "no second 'Hello there' cue should be finalized");
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
