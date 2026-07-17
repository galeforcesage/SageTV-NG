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

import java.util.Arrays;
import java.util.Collections;

/**
 * Unit tests for NgPlaybackContextSerializer.
 * Run with: javac java/sage/ng/*.java && java -ea -cp java sage.ng.NgPlaybackContextSerializerTest
 */
public class NgPlaybackContextSerializerTest
{
  private static int passed = 0;
  private static int failed = 0;

  public static void main(String[] args)
  {
    testSerializeRecording();
    testSerializeLive();
    testEmptyListsSerialization();
    testInvalidValuesClamped();
    testUnknownModeAndContainer();
    testStringEscaping();
    testPtsSamplesSerialization();
    testSkipSegmentsSerialization();

    System.out.println("\n=== Results: " + passed + " passed, " + failed + " failed ===");
    if (failed > 0)
    {
      System.exit(1);
    }
  }

  private static void testSerializeRecording()
  {
    NgPlaybackContext ctx = new NgPlaybackContext(
        "550e8400-e29b-41d4-a716-446655440000",
        123456, 789,
        "recording", "ts",
        3612000, 1500000,
        NgLiveContext.EMPTY,
        NgSeekPolicy.DEFAULT,
        NgIndexContext.EMPTY,
        NgSkipContext.EMPTY,
        NgFlowPolicy.DEFAULT
    );

    String json = NgPlaybackContextSerializer.toJson(ctx);

    assertContains(json, "\"version\":1", "recording: version");
    assertContains(json, "\"sessionId\":\"550e8400-e29b-41d4-a716-446655440000\"", "recording: sessionId");
    assertContains(json, "\"mediaFileId\":123456", "recording: mediaFileId");
    assertContains(json, "\"airingId\":789", "recording: airingId");
    assertContains(json, "\"mode\":\"recording\"", "recording: mode");
    assertContains(json, "\"container\":\"ts\"", "recording: container");
    assertContains(json, "\"durationMs\":3612000", "recording: durationMs");
    assertContains(json, "\"serverMediaTimeMs\":1500000", "recording: serverMediaTimeMs");
    assertContains(json, "\"isLive\":false", "recording: live.isLive");
    assertContains(json, "\"requiresServerSeek\":true", "recording: seek.requiresServerSeek");
    assertContains(json, "\"preferredPrebufferBytes\":262144", "recording: flow.preferredPrebufferBytes");
    assertValidJson(json, "recording: valid JSON structure");
  }

  private static void testSerializeLive()
  {
    NgLiveContext live = new NgLiveContext(true, 1000, 500, 180000, 185000, 52428800, 1689000000000L);

    NgPlaybackContext ctx = new NgPlaybackContext(
        "live-session-uuid",
        99999, 456,
        "live", "ts",
        0, 60000,
        live,
        new NgSeekPolicy(10000, 500, 2000, true, true),
        NgIndexContext.EMPTY,
        NgSkipContext.EMPTY,
        NgFlowPolicy.DEFAULT
    );

    String json = NgPlaybackContextSerializer.toJson(ctx);

    assertContains(json, "\"isLive\":true", "live: isLive");
    assertContains(json, "\"recordingStartMs\":1000", "live: recordingStartMs");
    assertContains(json, "\"safeSeekStartMs\":500", "live: safeSeekStartMs");
    assertContains(json, "\"safeSeekEndMs\":180000", "live: safeSeekEndMs");
    assertContains(json, "\"playableEndMs\":185000", "live: playableEndMs");
    assertContains(json, "\"growthBytes\":52428800", "live: growthBytes");
    assertContains(json, "\"clientMayPredictOsd\":true", "live: clientMayPredictOsd");
    assertContains(json, "\"mode\":\"live\"", "live: mode");
  }

  private static void testEmptyListsSerialization()
  {
    NgPlaybackContext ctx = new NgPlaybackContext(
        "empty-test", 1, 1, "recording", "mp4",
        1000, 0, null, null, null, null, null
    );

    String json = NgPlaybackContextSerializer.toJson(ctx);

    assertContains(json, "\"ptsSamples\":[]", "empty: ptsSamples empty array");
    assertContains(json, "\"commercials\":[]", "empty: commercials empty array");
    assertContains(json, "\"chapters\":[]", "empty: chapters empty array");
    assertContains(json, "\"bookmarks\":[]", "empty: bookmarks empty array");
    assertValidJson(json, "empty: valid JSON");
  }

  private static void testInvalidValuesClamped()
  {
    // Negative duration should clamp to 0
    NgPlaybackContext ctx = new NgPlaybackContext(
        "clamp-test", 1, 1, "recording", "ts",
        -5000, -100, null, null, null, null, null
    );
    assertEqual(ctx.getDurationMs(), 0, "clamped: negative duration to 0");
    assertEqual(ctx.getServerMediaTimeMs(), 0, "clamped: negative serverMediaTimeMs to 0");

    // Inverted seek bounds
    NgLiveContext live = new NgLiveContext(true, 0, 5000, 2000, 0, -100, 0);
    assertEqual(live.getSafeSeekEndMs(), 5000, "clamped: safeSeekEnd to safeSeekStart");
    assertEqual(live.getGrowthBytes(), 0, "clamped: negative growthBytes to 0");

    // Inverted skip segment
    NgSkipSegment seg = new NgSkipSegment(5000, 2000, null, -10);
    assertEqual(seg.getEndMs(), 5000, "clamped: segment endMs to startMs");
    assertEqual(seg.getPrerollMs(), 0, "clamped: negative prerollMs to 0");
    assertEqual(seg.getType(), "unknown", "clamped: null type to unknown");

    // Flow policy - high < low
    NgFlowPolicy flow = new NgFlowPolicy(100, 500, 200);
    assertTrue(flow.getHighWatermarkBytes() >= flow.getLowWatermarkBytes(),
        "clamped: highWatermark >= lowWatermark");

    // Serialize clamped context — must produce valid JSON
    String json = NgPlaybackContextSerializer.toJson(ctx);
    assertContains(json, "\"durationMs\":0", "clamped serialized: durationMs is 0");
    assertValidJson(json, "clamped: valid JSON after clamping");
  }

  private static void testUnknownModeAndContainer()
  {
    NgPlaybackContext ctx = new NgPlaybackContext(
        "unk-test", 1, 1, null, null,
        1000, 0, null, null, null, null, null
    );

    String json = NgPlaybackContextSerializer.toJson(ctx);
    assertContains(json, "\"mode\":\"unknown\"", "unknown: null mode becomes unknown");
    assertContains(json, "\"container\":\"unknown\"", "unknown: null container becomes unknown");

    // Exotic but valid mode/container pass through as-is
    NgPlaybackContext ctx2 = new NgPlaybackContext(
        "exotic-test", 1, 1, "timeshift", "mkv",
        1000, 0, null, null, null, null, null
    );
    String json2 = NgPlaybackContextSerializer.toJson(ctx2);
    assertContains(json2, "\"mode\":\"timeshift\"", "exotic: timeshift mode");
    assertContains(json2, "\"container\":\"mkv\"", "exotic: mkv container");
  }

  private static void testStringEscaping()
  {
    NgPlaybackContext ctx = new NgPlaybackContext(
        "session-with-\"quotes\"", 1, 1, "recording", "ts",
        1000, 0, null, null, null, null, null
    );

    String json = NgPlaybackContextSerializer.toJson(ctx);
    assertContains(json, "\\\"quotes\\\"", "escaping: quotes escaped");
    assertNotContains(json, "\"quotes\"\"", "escaping: no unescaped quotes");
  }

  private static void testPtsSamplesSerialization()
  {
    NgIndexContext index = new NgIndexContext(true, true, Arrays.asList(
        new NgPtsSample(0, 0, true),
        new NgPtsSample(5000, 188000, true),
        new NgPtsSample(10000, 376000, false)
    ));

    NgPlaybackContext ctx = new NgPlaybackContext(
        "pts-test", 1, 1, "recording", "ts",
        30000, 0, null, null, index, null, null
    );

    String json = NgPlaybackContextSerializer.toJson(ctx);
    assertContains(json, "\"hasKeyframeIndex\":true", "pts: hasKeyframeIndex");
    assertContains(json, "\"hasPtsByteMap\":true", "pts: hasPtsByteMap");
    assertContains(json, "\"timeMs\":5000", "pts: sample timeMs");
    assertContains(json, "\"byteOffset\":188000", "pts: sample byteOffset");
    assertContains(json, "\"keyframe\":false", "pts: non-keyframe sample");
  }

  private static void testSkipSegmentsSerialization()
  {
    NgSkipContext skip = new NgSkipContext(
        Arrays.asList(
            new NgSkipSegment(60000, 120000, "commercial", 2000),
            new NgSkipSegment(300000, 360000, "commercial", 2000)
        ),
        Arrays.asList(
            new NgSkipSegment(0, 60000, "chapter", 0),
            new NgSkipSegment(60000, 300000, "chapter", 0)
        ),
        Collections.emptyList()
    );

    NgPlaybackContext ctx = new NgPlaybackContext(
        "skip-test", 1, 1, "recording", "ts",
        600000, 0, null, null, null, skip, null
    );

    String json = NgPlaybackContextSerializer.toJson(ctx);
    assertContains(json, "\"startMs\":60000", "skip: commercial startMs");
    assertContains(json, "\"endMs\":120000", "skip: commercial endMs");
    assertContains(json, "\"type\":\"commercial\"", "skip: commercial type");
    assertContains(json, "\"prerollMs\":2000", "skip: commercial prerollMs");
    assertContains(json, "\"type\":\"chapter\"", "skip: chapter type");
    assertContains(json, "\"bookmarks\":[]", "skip: empty bookmarks");
  }

  // --- Assertion helpers ---

  private static void assertContains(String json, String expected, String label)
  {
    if (json.contains(expected))
    {
      passed++;
      System.out.println("  PASS: " + label);
    }
    else
    {
      failed++;
      System.out.println("  FAIL: " + label);
      System.out.println("    Expected to contain: " + expected);
      System.out.println("    Actual: " + json.substring(0, Math.min(200, json.length())) + "...");
    }
  }

  private static void assertNotContains(String json, String unexpected, String label)
  {
    if (!json.contains(unexpected))
    {
      passed++;
      System.out.println("  PASS: " + label);
    }
    else
    {
      failed++;
      System.out.println("  FAIL: " + label);
      System.out.println("    Expected NOT to contain: " + unexpected);
    }
  }

  private static void assertEqual(long actual, long expected, String label)
  {
    if (actual == expected)
    {
      passed++;
      System.out.println("  PASS: " + label);
    }
    else
    {
      failed++;
      System.out.println("  FAIL: " + label + " (expected=" + expected + ", actual=" + actual + ")");
    }
  }

  private static void assertEqual(String actual, String expected, String label)
  {
    if (expected.equals(actual))
    {
      passed++;
      System.out.println("  PASS: " + label);
    }
    else
    {
      failed++;
      System.out.println("  FAIL: " + label + " (expected=" + expected + ", actual=" + actual + ")");
    }
  }

  private static void assertTrue(boolean condition, String label)
  {
    if (condition)
    {
      passed++;
      System.out.println("  PASS: " + label);
    }
    else
    {
      failed++;
      System.out.println("  FAIL: " + label);
    }
  }

  private static void assertValidJson(String json, String label)
  {
    // Basic structural validation: balanced braces/brackets, starts with {, ends with }
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

    if (valid)
    {
      passed++;
      System.out.println("  PASS: " + label);
    }
    else
    {
      failed++;
      System.out.println("  FAIL: " + label + " (unbalanced JSON structure)");
      System.out.println("    JSON: " + json.substring(0, Math.min(200, json.length())));
    }
  }
}
