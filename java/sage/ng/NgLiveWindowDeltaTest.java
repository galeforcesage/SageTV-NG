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

/**
 * Unit tests for NgLiveWindowCalculator, NgPlaybackContextDelta, and serializer.
 * Run with: javac java/sage/ng/*.java && java -ea -cp java sage.ng.NgLiveWindowDeltaTest
 */
public class NgLiveWindowDeltaTest
{
  private static int passed = 0;
  private static int failed = 0;

  public static void main(String[] args)
  {
    // Delta model tests
    testDeltaConstruction();
    testDeltaReasonConstants();
    testLiveWindowUpdateClamping();
    testMaterialDifferenceDetection();

    // Calculator lifecycle tests
    testCalculatorInitialDelta();
    testCalculatorSuppressesNoop();
    testCalculatorEmitsOnGrowth();
    testCalculatorEmitsOnSeek();
    testCalculatorEmitsOnFlush();
    testCalculatorRateLimiting();
    testCalculatorEpochChange();
    testCalculatorClose();
    testCalculatorClosedNoEmit();
    testCalculatorSafeSeekTrailingMargin();
    testCalculatorPlayableEnd();

    // Serializer tests
    testDeltaSerializerBasic();
    testDeltaSerializerFieldNames();
    testDeltaSerializerValidJson();

    System.out.println("\n=== NgLiveWindowDeltaTest: " + passed + " passed, " + failed + " failed ===");
    if (failed > 0) System.exit(1);
  }

  // --- Delta model tests ---

  private static void testDeltaConstruction()
  {
    NgLiveWindowUpdate live = new NgLiveWindowUpdate(true, 1000, 0, 50000, 55000, 1048576, 1689000000000L);
    NgPlaybackContextDelta delta = new NgPlaybackContextDelta(
        "sess-1", 123, 456, 1, NgDeltaReason.INITIAL, 50000, live
    );

    assertEqual(delta.getVersion(), 1, "delta ctor: version");
    assertEqualStr(delta.getSessionId(), "sess-1", "delta ctor: sessionId");
    assertEqual(delta.getMediaFileId(), 123, "delta ctor: mediaFileId");
    assertEqual(delta.getAiringId(), 456, "delta ctor: airingId");
    assertEqual(delta.getStreamEpoch(), 1, "delta ctor: streamEpoch");
    assertEqualStr(delta.getType(), "NG_LIVE_WINDOW_UPDATE", "delta ctor: type");
    assertEqualStr(delta.getReason(), "initial", "delta ctor: reason");
    assertEqual(delta.getServerMediaTimeMs(), 50000, "delta ctor: serverMediaTimeMs");
    assertTrue(delta.getLive().isLive(), "delta ctor: live.isLive");
  }

  private static void testDeltaReasonConstants()
  {
    assertEqualStr(NgDeltaReason.INITIAL, "initial", "reason: initial");
    assertEqualStr(NgDeltaReason.WALL_CLOCK, "wall_clock", "reason: wall_clock");
    assertEqualStr(NgDeltaReason.FILE_GROWTH, "file_growth", "reason: file_growth");
    assertEqualStr(NgDeltaReason.SEEK, "seek", "reason: seek");
    assertEqualStr(NgDeltaReason.FLUSH, "flush", "reason: flush");
    assertEqualStr(NgDeltaReason.EPOCH_CHANGE, "epoch_change", "reason: epoch_change");
    assertEqualStr(NgDeltaReason.SESSION_CLOSE, "session_close", "reason: session_close");
  }

  private static void testLiveWindowUpdateClamping()
  {
    // Inverted seek bounds
    NgLiveWindowUpdate update = new NgLiveWindowUpdate(true, 0, 5000, 2000, 0, -100, 0);
    assertEqual(update.getSafeSeekEndMs(), 5000, "update clamp: safeSeekEnd >= safeSeekStart");
    assertEqual(update.getGrowthBytes(), 0, "update clamp: negative growthBytes to 0");
  }

  private static void testMaterialDifferenceDetection()
  {
    NgLiveWindowUpdate a = new NgLiveWindowUpdate(true, 0, 0, 50000, 55000, 1000000, 100);
    NgLiveWindowUpdate b = new NgLiveWindowUpdate(true, 0, 0, 50500, 55500, 1010000, 200);
    NgLiveWindowUpdate c = new NgLiveWindowUpdate(true, 0, 0, 52000, 57000, 1100000, 300);
    NgLiveWindowUpdate d = new NgLiveWindowUpdate(false, 0, 0, 50000, 55000, 1000000, 100);

    // b is within tolerance of a (500ms seekEnd, 10KB growth)
    assertTrue(!b.isMateriallyDifferent(a, 1000, 65536), "material: within tolerance");
    // c exceeds tolerance (2000ms seekEnd, 100KB growth)
    assertTrue(c.isMateriallyDifferent(a, 1000, 65536), "material: exceeds tolerance");
    // d differs in isLive
    assertTrue(d.isMateriallyDifferent(a, 1000, 65536), "material: isLive changed");
    // null previous always material
    assertTrue(a.isMateriallyDifferent(null, 1000, 65536), "material: null previous");
  }

  // --- Calculator lifecycle tests ---

  private static void testCalculatorInitialDelta()
  {
    NgLiveWindowCalculator calc = new NgLiveWindowCalculator("sess-1", 100, 200);
    calc.open(1689000000000L);
    calc.updateServerState(30000, 5242880, 1689000030000L);

    NgPlaybackContextDelta delta = calc.computeDeltaIfChanged(1689000030000L);

    assertTrue(delta != null, "initial: delta emitted");
    assertEqualStr(delta.getReason(), "initial", "initial: reason=initial");
    assertEqual(delta.getStreamEpoch(), 1, "initial: epoch=1");
    assertEqualStr(delta.getSessionId(), "sess-1", "initial: sessionId");
    assertEqual(delta.getMediaFileId(), 100, "initial: mediaFileId");
    assertTrue(delta.getLive().isLive(), "initial: live=true");
    assertEqual(delta.getLive().getGrowthBytes(), 5242880, "initial: growthBytes");
  }

  private static void testCalculatorSuppressesNoop()
  {
    NgLiveWindowCalculator calc = new NgLiveWindowCalculator("sess-1", 100, 200);
    calc.open(0);
    calc.updateServerState(30000, 5242880, 1000);

    // First: emits (initial)
    NgPlaybackContextDelta d1 = calc.computeDeltaIfChanged(1000);
    assertTrue(d1 != null, "suppress: first emits");

    // Same state, enough time passed: suppressed (no material change)
    calc.updateServerState(30200, 5242900, 3000); // +200ms, +20 bytes
    NgPlaybackContextDelta d2 = calc.computeDeltaIfChanged(3000);
    assertTrue(d2 == null, "suppress: no material change suppressed");
  }

  private static void testCalculatorEmitsOnGrowth()
  {
    NgLiveWindowCalculator calc = new NgLiveWindowCalculator("sess-1", 100, 200);
    calc.open(0);
    calc.updateServerState(30000, 1000000, 1000);
    calc.computeDeltaIfChanged(1000); // consume initial

    // Significant growth: +100KB file, +2s media time
    calc.updateServerState(32000, 1100000, 3000);
    NgPlaybackContextDelta delta = calc.computeDeltaIfChanged(3000);
    assertTrue(delta != null, "growth: delta emitted");
    assertEqualStr(delta.getReason(), "file_growth", "growth: reason=file_growth");
  }

  private static void testCalculatorEmitsOnSeek()
  {
    NgLiveWindowCalculator calc = new NgLiveWindowCalculator("sess-1", 100, 200);
    calc.open(0);
    calc.updateServerState(30000, 5242880, 1000);
    calc.computeDeltaIfChanged(1000); // consume initial

    calc.notifySeek(10000);
    NgPlaybackContextDelta delta = calc.computeDeltaIfChanged(1500); // within rate limit, but forced
    assertTrue(delta != null, "seek: delta emitted despite rate limit");
    assertEqualStr(delta.getReason(), "seek", "seek: reason=seek");
    assertEqual(delta.getServerMediaTimeMs(), 10000, "seek: serverMediaTimeMs updated");
  }

  private static void testCalculatorEmitsOnFlush()
  {
    NgLiveWindowCalculator calc = new NgLiveWindowCalculator("sess-1", 100, 200);
    calc.open(0);
    calc.updateServerState(30000, 5242880, 1000);
    calc.computeDeltaIfChanged(1000); // consume initial

    calc.notifyFlush();
    NgPlaybackContextDelta delta = calc.computeDeltaIfChanged(1500);
    assertTrue(delta != null, "flush: delta emitted");
    assertEqualStr(delta.getReason(), "flush", "flush: reason=flush");
  }

  private static void testCalculatorRateLimiting()
  {
    NgLiveWindowCalculator calc = new NgLiveWindowCalculator("sess-1", 100, 200);
    calc.open(0);
    calc.updateServerState(30000, 1000000, 1000);
    calc.computeDeltaIfChanged(1000); // consume initial

    // Update with significant change but too soon (only 500ms later)
    calc.updateServerState(35000, 1200000, 1500);
    NgPlaybackContextDelta delta = calc.computeDeltaIfChanged(1500);
    assertTrue(delta == null, "rate limit: suppressed within 1s");

    // After 1s+ passes: emits
    NgPlaybackContextDelta delta2 = calc.computeDeltaIfChanged(2100);
    assertTrue(delta2 != null, "rate limit: emits after 1s");
  }

  private static void testCalculatorEpochChange()
  {
    NgLiveWindowCalculator calc = new NgLiveWindowCalculator("sess-1", 100, 200);
    calc.open(0);
    calc.updateServerState(30000, 5242880, 1000);
    calc.computeDeltaIfChanged(1000); // consume initial
    assertEqual(calc.getStreamEpoch(), 1, "epoch: starts at 1");

    calc.notifyEpochChange(999, 888, 1689000050000L);
    assertEqual(calc.getStreamEpoch(), 2, "epoch: increments to 2");

    calc.updateServerState(5000, 100000, 5000);
    NgPlaybackContextDelta delta = calc.computeDeltaIfChanged(5000);
    assertTrue(delta != null, "epoch: delta emitted");
    assertEqualStr(delta.getReason(), "epoch_change", "epoch: reason=epoch_change");
    assertEqual(delta.getStreamEpoch(), 2, "epoch: delta has epoch=2");
  }

  private static void testCalculatorClose()
  {
    NgLiveWindowCalculator calc = new NgLiveWindowCalculator("sess-1", 100, 200);
    calc.open(0);
    calc.updateServerState(30000, 5242880, 1000);
    calc.computeDeltaIfChanged(1000); // consume initial

    NgPlaybackContextDelta delta = calc.close();
    assertTrue(delta != null, "close: delta emitted");
    assertEqualStr(delta.getReason(), "session_close", "close: reason=session_close");
    assertTrue(!delta.getLive().isLive(), "close: live=false");
    assertTrue(!calc.isOpen(), "close: calculator not open");
  }

  private static void testCalculatorClosedNoEmit()
  {
    NgLiveWindowCalculator calc = new NgLiveWindowCalculator("sess-1", 100, 200);
    // Not opened
    NgPlaybackContextDelta delta = calc.computeDeltaIfChanged(1000);
    assertTrue(delta == null, "closed: no emission when not open");

    NgPlaybackContextDelta closeDelta = calc.close();
    assertTrue(closeDelta == null, "closed: close returns null when not open");
  }

  private static void testCalculatorSafeSeekTrailingMargin()
  {
    NgLiveWindowCalculator calc = new NgLiveWindowCalculator("sess-1", 100, 200);
    calc.open(0);
    // Server time is 30s — safe seek should be 25s (30s - 5s margin)
    calc.updateServerState(30000, 5242880, 1000);
    NgPlaybackContextDelta delta = calc.computeDeltaIfChanged(1000);

    assertTrue(delta != null, "margin: delta emitted");
    assertEqual(delta.getLive().getSafeSeekEndMs(), 25000, "margin: safeSeekEnd = serverTime - 5s");
    assertEqual(delta.getLive().getPlayableEndMs(), 30000, "margin: playableEnd = serverTime");
  }

  private static void testCalculatorPlayableEnd()
  {
    NgLiveWindowCalculator calc = new NgLiveWindowCalculator("sess-1", 100, 200);
    calc.open(0);
    // Server time only 3s (less than margin) — safeSeek should still be serverTime (no subtraction below 0)
    calc.updateServerState(3000, 100000, 1000);
    NgPlaybackContextDelta delta = calc.computeDeltaIfChanged(1000);

    assertTrue(delta != null, "playable: delta emitted");
    assertEqual(delta.getLive().getSafeSeekEndMs(), 3000, "playable: safeSeekEnd = serverTime (no margin underflow)");
    assertEqual(delta.getLive().getPlayableEndMs(), 3000, "playable: playableEnd = serverTime");
  }

  // --- Serializer tests ---

  private static void testDeltaSerializerBasic()
  {
    NgLiveWindowUpdate live = new NgLiveWindowUpdate(true, 1000, 0, 50000, 55000, 1048576, 1689000000000L);
    NgPlaybackContextDelta delta = new NgPlaybackContextDelta(
        "sess-1", 123, 456, 2, NgDeltaReason.FILE_GROWTH, 50000, live
    );

    String json = NgPlaybackContextDeltaSerializer.toJson(delta);
    assertContains(json, "\"version\":1", "serializer: version");
    assertContains(json, "\"sessionId\":\"sess-1\"", "serializer: sessionId");
    assertContains(json, "\"streamEpoch\":2", "serializer: streamEpoch");
    assertContains(json, "\"type\":\"NG_LIVE_WINDOW_UPDATE\"", "serializer: type");
    assertContains(json, "\"reason\":\"file_growth\"", "serializer: reason");
    assertContains(json, "\"serverMediaTimeMs\":50000", "serializer: serverMediaTimeMs");
    assertContains(json, "\"safeSeekEndMs\":50000", "serializer: live.safeSeekEndMs");
  }

  private static void testDeltaSerializerFieldNames()
  {
    NgLiveWindowUpdate live = new NgLiveWindowUpdate(true, 0, 0, 0, 0, 0, 0);
    NgPlaybackContextDelta delta = new NgPlaybackContextDelta(
        "x", 1, 2, 0, NgDeltaReason.INITIAL, 0, live
    );
    String json = NgPlaybackContextDeltaSerializer.toJson(delta);

    // All expected top-level keys present
    assertContains(json, "\"version\":", "fields: version key");
    assertContains(json, "\"sessionId\":", "fields: sessionId key");
    assertContains(json, "\"mediaFileId\":", "fields: mediaFileId key");
    assertContains(json, "\"airingId\":", "fields: airingId key");
    assertContains(json, "\"streamEpoch\":", "fields: streamEpoch key");
    assertContains(json, "\"type\":", "fields: type key");
    assertContains(json, "\"reason\":", "fields: reason key");
    assertContains(json, "\"serverMediaTimeMs\":", "fields: serverMediaTimeMs key");
    assertContains(json, "\"live\":", "fields: live key");

    // All expected live sub-keys present
    assertContains(json, "\"isLive\":", "fields: live.isLive key");
    assertContains(json, "\"recordingStartMs\":", "fields: live.recordingStartMs key");
    assertContains(json, "\"safeSeekStartMs\":", "fields: live.safeSeekStartMs key");
    assertContains(json, "\"safeSeekEndMs\":", "fields: live.safeSeekEndMs key");
    assertContains(json, "\"playableEndMs\":", "fields: live.playableEndMs key");
    assertContains(json, "\"growthBytes\":", "fields: live.growthBytes key");
    assertContains(json, "\"lastSizeRefreshMs\":", "fields: live.lastSizeRefreshMs key");
  }

  private static void testDeltaSerializerValidJson()
  {
    NgLiveWindowUpdate live = new NgLiveWindowUpdate(true, 500, 100, 99000, 100000, 999999, 1689000099000L);
    NgPlaybackContextDelta delta = new NgPlaybackContextDelta(
        "valid-json-test", 777, 888, 5, NgDeltaReason.WALL_CLOCK, 99000, live
    );
    String json = NgPlaybackContextDeltaSerializer.toJson(delta);
    assertValidJson(json, "serializer: produces valid JSON");
  }

  // --- Assertion helpers ---

  private static void assertEqual(long actual, long expected, String label)
  {
    if (actual == expected) { passed++; System.out.println("  PASS: " + label); }
    else { failed++; System.out.println("  FAIL: " + label + " (expected=" + expected + ", actual=" + actual + ")"); }
  }

  private static void assertEqual(int actual, int expected, String label)
  {
    assertEqual((long) actual, (long) expected, label);
  }

  private static void assertEqualStr(String actual, String expected, String label)
  {
    if (expected.equals(actual)) { passed++; System.out.println("  PASS: " + label); }
    else { failed++; System.out.println("  FAIL: " + label + " (expected=\"" + expected + "\", actual=\"" + actual + "\")"); }
  }

  private static void assertTrue(boolean condition, String label)
  {
    if (condition) { passed++; System.out.println("  PASS: " + label); }
    else { failed++; System.out.println("  FAIL: " + label); }
  }

  private static void assertContains(String json, String expected, String label)
  {
    if (json.contains(expected)) { passed++; System.out.println("  PASS: " + label); }
    else { failed++; System.out.println("  FAIL: " + label + "\n    Expected: " + expected + "\n    In: " + json.substring(0, Math.min(200, json.length()))); }
  }

  private static void assertValidJson(String json, String label)
  {
    boolean valid = json.startsWith("{") && json.endsWith("}");
    int braceCount = 0;
    int bracketCount = 0;
    boolean inString = false;
    boolean escaped = false;
    for (int i = 0; i < json.length() && valid; i++)
    {
      char c = json.charAt(i);
      if (escaped) { escaped = false; continue; }
      if (c == '\\' && inString) { escaped = true; continue; }
      if (c == '"') { inString = !inString; continue; }
      if (inString) continue;
      if (c == '{') braceCount++;
      else if (c == '}') braceCount--;
      else if (c == '[') bracketCount++;
      else if (c == ']') bracketCount--;
      if (braceCount < 0 || bracketCount < 0) valid = false;
    }
    valid = valid && braceCount == 0 && bracketCount == 0 && !inString;
    if (valid) { passed++; System.out.println("  PASS: " + label); }
    else { failed++; System.out.println("  FAIL: " + label + " (unbalanced)"); }
  }
}
