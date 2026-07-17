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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for NgPlaybackContextBuilder.
 * Run with: javac java/sage/ng/*.java && java -ea -cp java sage.ng.NgPlaybackContextBuilderTest
 */
public class NgPlaybackContextBuilderTest
{
  private static int passed = 0;
  private static int failed = 0;

  public static void main(String[] args)
  {
    testBuildRecordingContext();
    testBuildLiveContext();
    testBuildTimeshiftContext();
    testBuildUnknownDefaults();
    testDeriveMode();
    testNormalizeContainer();
    testBuildSeekPolicyNormal();
    testBuildSeekPolicyTranscoding();
    testBuildSkipContextEmpty();
    testBuildSkipContextWithSegments();
    testBuildSkipContextMixedKinds();
    testBuildLiveContextNonLive();
    testBuildLiveContextWithDuration();
    testBuildLiveContextWithServerTime();
    testNullSnapshotSafe();
    testSessionIdGenerated();
    testNegativeValuesClamped();

    System.out.println("\n=== NgPlaybackContextBuilderTest: " + passed + " passed, " + failed + " failed ===");
    if (failed > 0) System.exit(1);
  }

  private static void testBuildRecordingContext()
  {
    NgPlaybackContextBuilder.PlaybackSnapshot snap = new NgPlaybackContextBuilder.PlaybackSnapshot();
    snap.mediaFileId = 12345;
    snap.airingId = 678;
    snap.sessionId = "test-session-1";
    snap.containerFormat = "MPEG2-TS";
    snap.durationMs = 3600000;
    snap.serverMediaTimeMs = 1800000;
    snap.timeshifted = false;
    snap.isLiveStream = false;

    NgPlaybackContext ctx = NgPlaybackContextBuilder.build(snap);

    assertEqual(ctx.getVersion(), 1, "recording: version");
    assertEqualStr(ctx.getSessionId(), "test-session-1", "recording: sessionId");
    assertEqual(ctx.getMediaFileId(), 12345, "recording: mediaFileId");
    assertEqual(ctx.getAiringId(), 678, "recording: airingId");
    assertEqualStr(ctx.getMode(), "recording", "recording: mode");
    assertEqualStr(ctx.getContainer(), "ts", "recording: container");
    assertEqual(ctx.getDurationMs(), 3600000, "recording: durationMs");
    assertEqual(ctx.getServerMediaTimeMs(), 1800000, "recording: serverMediaTimeMs");
    assertTrue(!ctx.getLive().isLive(), "recording: not live");
  }

  private static void testBuildLiveContext()
  {
    NgPlaybackContextBuilder.PlaybackSnapshot snap = new NgPlaybackContextBuilder.PlaybackSnapshot();
    snap.mediaFileId = 99999;
    snap.airingId = 555;
    snap.sessionId = "live-session";
    snap.containerFormat = "MPEG2-TS";
    snap.durationMs = 0;
    snap.serverMediaTimeMs = 45000;
    snap.timeshifted = true;
    snap.isLiveStream = true;
    snap.fileSizeBytes = 52428800;
    snap.fileSizeRefreshTimeMs = 1689000000000L;
    snap.recordingStartEpochMs = 1689000000000L - 45000;

    NgPlaybackContext ctx = NgPlaybackContextBuilder.build(snap);

    assertEqualStr(ctx.getMode(), "live", "live: mode");
    assertTrue(ctx.getLive().isLive(), "live: isLive=true");
    assertEqual(ctx.getLive().getGrowthBytes(), 52428800, "live: growthBytes");
    assertEqual(ctx.getLive().getSafeSeekEndMs(), 45000, "live: safeSeekEnd from serverMediaTime");
    assertEqual(ctx.getLive().getSafeSeekStartMs(), 0, "live: safeSeekStart=0");
    assertEqual(ctx.getLive().getLastSizeRefreshMs(), 1689000000000L, "live: lastSizeRefreshMs");
  }

  private static void testBuildTimeshiftContext()
  {
    NgPlaybackContextBuilder.PlaybackSnapshot snap = new NgPlaybackContextBuilder.PlaybackSnapshot();
    snap.mediaFileId = 77777;
    snap.timeshifted = true;
    snap.isLiveStream = false;
    snap.durationMs = 0;
    snap.serverMediaTimeMs = 120000;

    NgPlaybackContext ctx = NgPlaybackContextBuilder.build(snap);

    assertEqualStr(ctx.getMode(), "timeshift", "timeshift: mode");
    assertTrue(ctx.getLive().isLive(), "timeshift: live.isLive=true (actively recording)");
    assertEqual(ctx.getLive().getSafeSeekEndMs(), 120000, "timeshift: safeSeekEnd");
  }

  private static void testBuildUnknownDefaults()
  {
    NgPlaybackContextBuilder.PlaybackSnapshot snap = new NgPlaybackContextBuilder.PlaybackSnapshot();
    // Leave everything at defaults

    NgPlaybackContext ctx = NgPlaybackContextBuilder.build(snap);

    assertEqual(ctx.getMediaFileId(), -1, "defaults: mediaFileId=-1");
    assertEqual(ctx.getAiringId(), -1, "defaults: airingId=-1");
    assertEqualStr(ctx.getMode(), "unknown", "defaults: mode=unknown");
    assertEqualStr(ctx.getContainer(), "unknown", "defaults: container=unknown");
    assertEqual(ctx.getDurationMs(), 0, "defaults: durationMs=0");
    assertEqual(ctx.getServerMediaTimeMs(), 0, "defaults: serverMediaTimeMs=0");
    assertTrue(!ctx.getLive().isLive(), "defaults: not live");
    assertTrue(ctx.getSkip().getCommercials().isEmpty(), "defaults: no commercials");
    assertTrue(ctx.getIndex() == NgIndexContext.EMPTY, "defaults: empty index");
    assertTrue(ctx.getFlow() == NgFlowPolicy.DEFAULT, "defaults: default flow");
  }

  private static void testDeriveMode()
  {
    NgPlaybackContextBuilder.PlaybackSnapshot snap = new NgPlaybackContextBuilder.PlaybackSnapshot();

    snap.isLiveStream = true; snap.timeshifted = true;
    assertEqualStr(NgPlaybackContextBuilder.deriveMode(snap), "live", "deriveMode: live takes priority");

    snap.isLiveStream = false; snap.timeshifted = true;
    assertEqualStr(NgPlaybackContextBuilder.deriveMode(snap), "timeshift", "deriveMode: timeshift");

    snap.isLiveStream = false; snap.timeshifted = false; snap.mediaFileId = 100;
    assertEqualStr(NgPlaybackContextBuilder.deriveMode(snap), "recording", "deriveMode: recording");

    snap.mediaFileId = -1;
    assertEqualStr(NgPlaybackContextBuilder.deriveMode(snap), "unknown", "deriveMode: unknown");
  }

  private static void testNormalizeContainer()
  {
    assertEqualStr(NgPlaybackContextBuilder.normalizeContainer("MPEG2-TS"), "ts", "container: MPEG2-TS");
    assertEqualStr(NgPlaybackContextBuilder.normalizeContainer("MPEG2-PS"), "ps", "container: MPEG2-PS");
    assertEqualStr(NgPlaybackContextBuilder.normalizeContainer("MPEG2_TS"), "ts", "container: MPEG2_TS");
    assertEqualStr(NgPlaybackContextBuilder.normalizeContainer("MP4"), "mp4", "container: MP4");
    assertEqualStr(NgPlaybackContextBuilder.normalizeContainer("Matroska"), "mkv", "container: Matroska");
    assertEqualStr(NgPlaybackContextBuilder.normalizeContainer("AVI"), "avi", "container: AVI");
    assertEqualStr(NgPlaybackContextBuilder.normalizeContainer(null), "unknown", "container: null");
    assertEqualStr(NgPlaybackContextBuilder.normalizeContainer(""), "unknown", "container: empty");
    assertEqualStr(NgPlaybackContextBuilder.normalizeContainer("SomeWeirdFormat"), "someweirdformat", "container: unknown passthrough");
  }

  private static void testBuildSeekPolicyNormal()
  {
    NgPlaybackContextBuilder.PlaybackSnapshot snap = new NgPlaybackContextBuilder.PlaybackSnapshot();
    snap.serverSideTranscoding = false;

    NgSeekPolicy policy = NgPlaybackContextBuilder.buildSeekPolicy(snap);

    assertEqual(policy.getPreferredGranularityMs(), 5000, "seek normal: granularity=5000");
    assertEqual(policy.getMinSeekIntervalMs(), 250, "seek normal: minInterval=250");
    assertEqual(policy.getMaxClientCoalesceMs(), 1500, "seek normal: coalesce=1500");
    assertTrue(policy.isRequiresServerSeek(), "seek normal: requiresServerSeek=true");
    assertTrue(policy.isClientMayPredictOsd(), "seek normal: mayPredictOsd=true");
  }

  private static void testBuildSeekPolicyTranscoding()
  {
    NgPlaybackContextBuilder.PlaybackSnapshot snap = new NgPlaybackContextBuilder.PlaybackSnapshot();
    snap.serverSideTranscoding = true;

    NgSeekPolicy policy = NgPlaybackContextBuilder.buildSeekPolicy(snap);

    assertEqual(policy.getPreferredGranularityMs(), 10000, "seek transcode: granularity=10000");
    assertTrue(!policy.isClientMayPredictOsd(), "seek transcode: mayPredictOsd=false");
    assertTrue(policy.isRequiresServerSeek(), "seek transcode: requiresServerSeek=true");
  }

  private static void testBuildSkipContextEmpty()
  {
    NgPlaybackContextBuilder.PlaybackSnapshot snap = new NgPlaybackContextBuilder.PlaybackSnapshot();
    snap.skipSegments = null;

    NgSkipContext skip = NgPlaybackContextBuilder.buildSkipContext(snap);
    assertTrue(skip == NgSkipContext.EMPTY, "skip empty: null segments returns EMPTY");

    snap.skipSegments = new ArrayList<>();
    skip = NgPlaybackContextBuilder.buildSkipContext(snap);
    assertTrue(skip == NgSkipContext.EMPTY, "skip empty: empty list returns EMPTY");
  }

  private static void testBuildSkipContextWithSegments()
  {
    NgPlaybackContextBuilder.PlaybackSnapshot snap = new NgPlaybackContextBuilder.PlaybackSnapshot();
    snap.skipSegments = new ArrayList<>();
    snap.skipSegments.add(new long[]{60000, 120000, 0}); // commercial
    snap.skipSegments.add(new long[]{300000, 360000, 0}); // commercial

    NgSkipContext skip = NgPlaybackContextBuilder.buildSkipContext(snap);

    assertEqual(skip.getCommercials().size(), 2, "skip segments: 2 commercials");
    assertEqual(skip.getCommercials().get(0).getStartMs(), 60000, "skip segments: first start");
    assertEqual(skip.getCommercials().get(0).getEndMs(), 120000, "skip segments: first end");
    assertEqualStr(skip.getCommercials().get(0).getType(), "commercial", "skip segments: type");
    assertTrue(skip.getChapters().isEmpty(), "skip segments: no chapters");
    assertTrue(skip.getBookmarks().isEmpty(), "skip segments: no bookmarks");
  }

  private static void testBuildSkipContextMixedKinds()
  {
    NgPlaybackContextBuilder.PlaybackSnapshot snap = new NgPlaybackContextBuilder.PlaybackSnapshot();
    snap.skipSegments = new ArrayList<>();
    snap.skipSegments.add(new long[]{0, 60000, 2});      // chapter
    snap.skipSegments.add(new long[]{60000, 120000, 0}); // commercial
    snap.skipSegments.add(new long[]{120000, 180000, 1}); // promo
    snap.skipSegments.add(new long[]{180000, 240000, 99}); // unknown kind -> bookmark

    NgSkipContext skip = NgPlaybackContextBuilder.buildSkipContext(snap);

    assertEqual(skip.getChapters().size(), 1, "mixed: 1 chapter");
    assertEqual(skip.getCommercials().size(), 2, "mixed: 2 commercials (incl promo)");
    assertEqual(skip.getBookmarks().size(), 1, "mixed: 1 bookmark (unknown kind)");
    assertEqualStr(skip.getCommercials().get(1).getType(), "promo", "mixed: promo type");
    assertEqualStr(skip.getBookmarks().get(0).getType(), "unknown", "mixed: unknown type");
  }

  private static void testBuildLiveContextNonLive()
  {
    NgPlaybackContextBuilder.PlaybackSnapshot snap = new NgPlaybackContextBuilder.PlaybackSnapshot();
    snap.timeshifted = false;
    snap.isLiveStream = false;

    NgLiveContext live = NgPlaybackContextBuilder.buildLiveContext(snap, 3600000);
    assertTrue(live == NgLiveContext.EMPTY, "liveCtx non-live: returns EMPTY");
  }

  private static void testBuildLiveContextWithDuration()
  {
    NgPlaybackContextBuilder.PlaybackSnapshot snap = new NgPlaybackContextBuilder.PlaybackSnapshot();
    snap.timeshifted = true;
    snap.isLiveStream = false;
    snap.serverMediaTimeMs = 50000;

    NgLiveContext live = NgPlaybackContextBuilder.buildLiveContext(snap, 120000);
    assertTrue(live.isLive(), "liveCtx duration: isLive");
    assertEqual(live.getSafeSeekEndMs(), 120000, "liveCtx duration: uses durationMs");
  }

  private static void testBuildLiveContextWithServerTime()
  {
    NgPlaybackContextBuilder.PlaybackSnapshot snap = new NgPlaybackContextBuilder.PlaybackSnapshot();
    snap.timeshifted = true;
    snap.isLiveStream = true;
    snap.serverMediaTimeMs = 90000;

    // duration=0, so should fall back to serverMediaTimeMs
    NgLiveContext live = NgPlaybackContextBuilder.buildLiveContext(snap, 0);
    assertEqual(live.getSafeSeekEndMs(), 90000, "liveCtx serverTime: uses serverMediaTimeMs");
  }

  private static void testNullSnapshotSafe()
  {
    NgPlaybackContext ctx = NgPlaybackContextBuilder.build(null);
    assertTrue(ctx != null, "null snapshot: returns non-null context");
    assertEqualStr(ctx.getMode(), "unknown", "null snapshot: mode=unknown");
    assertTrue(ctx.getSessionId() != null && !ctx.getSessionId().isEmpty(), "null snapshot: sessionId generated");
  }

  private static void testSessionIdGenerated()
  {
    NgPlaybackContextBuilder.PlaybackSnapshot snap = new NgPlaybackContextBuilder.PlaybackSnapshot();
    snap.sessionId = null;

    NgPlaybackContext ctx = NgPlaybackContextBuilder.build(snap);
    assertTrue(ctx.getSessionId() != null, "sessionId: non-null");
    assertTrue(ctx.getSessionId().length() == 36, "sessionId: UUID format (36 chars)");
    assertTrue(ctx.getSessionId().contains("-"), "sessionId: contains dashes");
  }

  private static void testNegativeValuesClamped()
  {
    NgPlaybackContextBuilder.PlaybackSnapshot snap = new NgPlaybackContextBuilder.PlaybackSnapshot();
    snap.durationMs = -5000;
    snap.serverMediaTimeMs = -100;

    NgPlaybackContext ctx = NgPlaybackContextBuilder.build(snap);
    assertEqual(ctx.getDurationMs(), 0, "clamped: negative duration");
    assertEqual(ctx.getServerMediaTimeMs(), 0, "clamped: negative serverMediaTime");
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
}
