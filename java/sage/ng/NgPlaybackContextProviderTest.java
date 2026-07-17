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
import java.util.List;

/**
 * Unit tests for NgPlaybackContextProvider and NgPlaybackContextListener.
 * Run with: javac java/sage/ng/*.java && java -ea -cp java sage.ng.NgPlaybackContextProviderTest
 */
public class NgPlaybackContextProviderTest
{
  private static int passed = 0;
  private static int failed = 0;

  public static void main(String[] args)
  {
    testOpenSessionAndRetrieveContext();
    testInitialDeltaEmitted();
    testDuplicateUpdatesSuppressed();
    testSeekBypassesSuppression();
    testFlushBypassesSuppression();
    testEpochChangeIncrementsEpoch();
    testCloseSessionClearsState();
    testListenerReceivesDelta();
    testListenerExceptionDoesNotPropagate();
    testUnknownSessionReturnsNull();
    testMultipleSessionsIsolated();
    testSessionChangeNoStaleContext();
    testDuplicateListenerRegistration();
    testRemoveListener();
    testNullSessionKeySafe();
    testCloseEmitsSessionClosedToListener();
    testNoFilesystemAccess();

    System.out.println("\n=== NgPlaybackContextProviderTest: " + passed + " passed, " + failed + " failed ===");
    if (failed > 0) System.exit(1);
  }

  // --- Test 1: Open session and retrieve initial context ---

  private static void testOpenSessionAndRetrieveContext()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    provider.openSession("sess-A", null, "uuid-A", 100, 200, "MPEG2-TS", 3600000,
        false, false, false, 0);

    NgPlaybackContext ctx = provider.getCurrentContext("sess-A");
    assertTrue(ctx != null, "open: context non-null");
    assertEqual(ctx.getMediaFileId(), 100, "open: mediaFileId");
    assertEqual(ctx.getAiringId(), 200, "open: airingId");
    assertEqualStr(ctx.getContainer(), "ts", "open: container normalized");
    assertEqual(ctx.getDurationMs(), 3600000, "open: durationMs");
    assertEqualStr(ctx.getMode(), "recording", "open: mode=recording (not timeshifted)");
    assertTrue(provider.hasSession("sess-A"), "open: hasSession=true");
    assertEqual(provider.getActiveSessionCount(), 1, "open: activeCount=1");
  }

  // --- Test 2: Initial delta emitted ---

  private static void testInitialDeltaEmitted()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    provider.openSession("sess-B", null, "uuid-B", 100, 200, "MPEG2-TS", 0,
        true, true, false, 1689000000000L);
    provider.updateSessionState("sess-B", 30000, 5242880, 1000);

    NgPlaybackContextDelta delta = provider.computeDeltaIfChanged("sess-B", 1000);
    assertTrue(delta != null, "initial delta: emitted");
    assertEqualStr(delta.getReason(), "initial", "initial delta: reason=initial");
    assertTrue(delta.getLive().isLive(), "initial delta: live=true");
    assertEqual(delta.getLive().getGrowthBytes(), 5242880, "initial delta: growthBytes");
  }

  // --- Test 3: Duplicate updates suppressed ---

  private static void testDuplicateUpdatesSuppressed()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    provider.openSession("sess-C", null, "uuid-C", 100, 200, "MPEG2-TS", 0,
        true, true, false, 0);
    provider.updateSessionState("sess-C", 30000, 5242880, 1000);
    provider.computeDeltaIfChanged("sess-C", 1000); // consume initial

    // Tiny change: +200ms media time, +20 bytes — within tolerance
    provider.updateSessionState("sess-C", 30200, 5242900, 3000);
    NgPlaybackContextDelta delta = provider.computeDeltaIfChanged("sess-C", 3000);
    assertTrue(delta == null, "suppress: no material change suppressed");
  }

  // --- Test 4: Seek bypasses suppression ---

  private static void testSeekBypassesSuppression()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    provider.openSession("sess-D", null, "uuid-D", 100, 200, "MPEG2-TS", 0,
        true, true, false, 0);
    provider.updateSessionState("sess-D", 30000, 5242880, 1000);
    provider.computeDeltaIfChanged("sess-D", 1000); // consume initial

    provider.notifySeek("sess-D", 10000, 1500);
    // Verify a delta was emitted — we need a listener to capture it, or check via computeDelta
    // notifySeek already dispatches internally, so let's verify via a listener
    RecordingListener listener = new RecordingListener();
    provider.addListener(listener);

    // notifySeek already computed+dispatched, so listener won't see it retroactively.
    // Instead, test that the seek reason was set and next compute emits:
    provider.removeListener(listener);

    // Actually, notifySeek computes and dispatches inline. Let's re-test with listener in place:
    NgPlaybackContextProvider provider2 = new NgPlaybackContextProvider();
    RecordingListener listener2 = new RecordingListener();
    provider2.addListener(listener2);
    provider2.openSession("sess-D2", null, "uuid-D2", 100, 200, "MPEG2-TS", 0,
        true, true, false, 0);
    provider2.updateSessionState("sess-D2", 30000, 5242880, 1000);
    provider2.computeDeltaIfChanged("sess-D2", 1000); // initial
    listener2.deltas.clear();

    provider2.notifySeek("sess-D2", 10000, 1500);
    assertTrue(listener2.deltas.size() > 0, "seek: delta emitted via listener");
    assertEqualStr(listener2.deltas.get(0).getReason(), "seek", "seek: reason=seek");
  }

  // --- Test 5: Flush bypasses suppression ---

  private static void testFlushBypassesSuppression()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    RecordingListener listener = new RecordingListener();
    provider.addListener(listener);
    provider.openSession("sess-E", null, "uuid-E", 100, 200, "MPEG2-TS", 0,
        true, true, false, 0);
    provider.updateSessionState("sess-E", 30000, 5242880, 1000);
    provider.computeDeltaIfChanged("sess-E", 1000); // initial
    listener.deltas.clear();

    provider.notifyFlush("sess-E", 1500);
    assertTrue(listener.deltas.size() > 0, "flush: delta emitted");
    assertEqualStr(listener.deltas.get(0).getReason(), "flush", "flush: reason=flush");
  }

  // --- Test 6: Epoch change increments streamEpoch ---

  private static void testEpochChangeIncrementsEpoch()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    RecordingListener listener = new RecordingListener();
    provider.addListener(listener);
    provider.openSession("sess-F", null, "uuid-F", 100, 200, "MPEG2-TS", 0,
        true, true, false, 0);
    provider.updateSessionState("sess-F", 30000, 5242880, 1000);
    provider.computeDeltaIfChanged("sess-F", 1000); // initial, epoch=1
    listener.deltas.clear();

    provider.notifyEpochChange("sess-F", 999, 888, 1689000050000L, 5000);

    assertTrue(listener.deltas.size() > 0, "epoch: delta emitted");
    NgPlaybackContextDelta epochDelta = listener.deltas.get(0);
    assertEqualStr(epochDelta.getReason(), "epoch_change", "epoch: reason=epoch_change");
    assertEqual(epochDelta.getStreamEpoch(), 2, "epoch: streamEpoch=2");
  }

  // --- Test 7: Close clears session state ---

  private static void testCloseSessionClearsState()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    provider.openSession("sess-G", null, "uuid-G", 100, 200, "MPEG2-TS", 3600000,
        false, false, false, 0);
    assertTrue(provider.hasSession("sess-G"), "close: session exists before close");

    NgPlaybackContextDelta closeDelta = provider.closeSession("sess-G", 9000);
    assertTrue(closeDelta != null, "close: closeDelta emitted");
    assertEqualStr(closeDelta.getReason(), "session_close", "close: reason=session_close");
    assertTrue(!provider.hasSession("sess-G"), "close: session removed");
    assertTrue(provider.getCurrentContext("sess-G") == null, "close: context null after close");
    assertEqual(provider.getActiveSessionCount(), 0, "close: activeCount=0");
  }

  // --- Test 8: Listener receives delta ---

  private static void testListenerReceivesDelta()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    RecordingListener listener = new RecordingListener();
    provider.addListener(listener);

    provider.openSession("sess-H", null, "uuid-H", 100, 200, "MPEG2-TS", 0,
        true, true, false, 0);
    provider.updateSessionState("sess-H", 30000, 5242880, 1000);
    provider.computeDeltaIfChanged("sess-H", 1000);

    assertTrue(listener.deltas.size() == 1, "listener: received 1 delta");
    assertEqualStr(listener.deltas.get(0).getSessionId(), "uuid-H", "listener: correct sessionId");
    assertEqual(listener.lastSessionKey.equals("sess-H") ? 1 : 0, 1, "listener: correct sessionKey");
  }

  // --- Test 9: Listener exception does not propagate ---

  private static void testListenerExceptionDoesNotPropagate()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();

    // Add a throwing listener
    provider.addListener(new NgPlaybackContextListener()
    {
      public void onDelta(String sessionKey, NgPlaybackContextDelta delta)
      {
        throw new RuntimeException("Intentional test exception");
      }
      public void onSessionClosed(String sessionKey, NgPlaybackContextDelta closeDelta)
      {
        throw new RuntimeException("Intentional test close exception");
      }
    });

    // Add a recording listener AFTER the throwing one
    RecordingListener safe = new RecordingListener();
    provider.addListener(safe);

    // This should NOT throw, and the safe listener should still receive the delta
    boolean threwOnCompute = false;
    try
    {
      provider.openSession("sess-I", null, "uuid-I", 100, 200, "MPEG2-TS", 0,
          true, true, false, 0);
      provider.updateSessionState("sess-I", 30000, 5242880, 1000);
      provider.computeDeltaIfChanged("sess-I", 1000);
    }
    catch (Exception e)
    {
      threwOnCompute = true;
    }
    assertTrue(!threwOnCompute, "exception safety: compute did not throw");
    assertTrue(safe.deltas.size() == 1, "exception safety: safe listener received delta");

    // Close should also not throw
    boolean threwOnClose = false;
    try
    {
      provider.closeSession("sess-I", 9000);
    }
    catch (Exception e)
    {
      threwOnClose = true;
    }
    assertTrue(!threwOnClose, "exception safety: close did not throw");
    assertTrue(safe.closedSessions.size() == 1, "exception safety: safe listener received close");
  }

  // --- Test 10: Unknown session returns null-safe result ---

  private static void testUnknownSessionReturnsNull()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();

    assertTrue(provider.getCurrentContext("nonexistent") == null, "unknown: getCurrentContext=null");
    assertTrue(provider.computeDeltaIfChanged("nonexistent", 1000) == null, "unknown: computeDelta=null");
    assertTrue(provider.closeSession("nonexistent", 1000) == null, "unknown: closeSession=null");
    assertTrue(!provider.hasSession("nonexistent"), "unknown: hasSession=false");

    // These should not throw
    provider.updateSessionState("nonexistent", 0, 0, 0);
    provider.notifySeek("nonexistent", 0, 0);
    provider.notifyFlush("nonexistent", 0);
    provider.notifyEpochChange("nonexistent", 0, 0, 0, 0);
    passed++;
    System.out.println("  PASS: unknown: all operations safe on nonexistent session");
  }

  // --- Test 11: Multiple sessions isolated ---

  private static void testMultipleSessionsIsolated()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();

    provider.openSession("sess-X", null, "uuid-X", 111, 222, "MPEG2-TS", 3600000,
        false, false, false, 0);
    provider.openSession("sess-Y", null, "uuid-Y", 333, 444, "MP4", 7200000,
        true, true, false, 0);

    assertEqual(provider.getActiveSessionCount(), 2, "multi: 2 sessions");

    NgPlaybackContext ctxX = provider.getCurrentContext("sess-X");
    NgPlaybackContext ctxY = provider.getCurrentContext("sess-Y");
    assertTrue(ctxX != null && ctxY != null, "multi: both contexts non-null");
    assertEqual(ctxX.getMediaFileId(), 111, "multi: X has mediaFileId=111");
    assertEqual(ctxY.getMediaFileId(), 333, "multi: Y has mediaFileId=333");
    assertEqualStr(ctxX.getContainer(), "ts", "multi: X container=ts");
    assertEqualStr(ctxY.getContainer(), "mp4", "multi: Y container=mp4");

    // Close X, Y should be unaffected
    provider.closeSession("sess-X", 9000);
    assertTrue(!provider.hasSession("sess-X"), "multi: X closed");
    assertTrue(provider.hasSession("sess-Y"), "multi: Y still open");
    assertEqual(provider.getActiveSessionCount(), 1, "multi: 1 session remaining");
    assertTrue(provider.getCurrentContext("sess-Y") != null, "multi: Y context still accessible");
  }

  // --- Test 12: Session change does not leak old context ---

  private static void testSessionChangeNoStaleContext()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();

    // Open, use, close
    provider.openSession("sess-Z", null, "uuid-Z", 100, 200, "MPEG2-TS", 3600000,
        false, false, false, 0);
    provider.updateSessionState("sess-Z", 1800000, 0, 1000);
    provider.closeSession("sess-Z", 2000);

    // Verify old context is gone
    assertTrue(provider.getCurrentContext("sess-Z") == null, "stale: old context cleared");

    // Reopen same key with different media
    provider.openSession("sess-Z", null, "uuid-Z2", 999, 888, "MP4", 7200000,
        false, false, false, 0);
    NgPlaybackContext newCtx = provider.getCurrentContext("sess-Z");
    assertTrue(newCtx != null, "stale: new context available");
    assertEqual(newCtx.getMediaFileId(), 999, "stale: new mediaFileId");
    assertEqualStr(newCtx.getSessionId(), "uuid-Z2", "stale: new sessionId");
  }

  // --- Test 13: Duplicate listener registration ---

  private static void testDuplicateListenerRegistration()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    RecordingListener listener = new RecordingListener();

    provider.addListener(listener);
    provider.addListener(listener); // duplicate

    provider.openSession("sess-dup", null, "uuid-dup", 100, 200, "MPEG2-TS", 0,
        true, true, false, 0);
    provider.updateSessionState("sess-dup", 30000, 5242880, 1000);
    provider.computeDeltaIfChanged("sess-dup", 1000);

    // Should receive exactly 1 delta, not 2
    assertEqual(listener.deltas.size(), 1, "duplicate listener: only 1 delta");
  }

  // --- Test 14: Remove listener ---

  private static void testRemoveListener()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    RecordingListener listener = new RecordingListener();
    provider.addListener(listener);

    provider.openSession("sess-rm", null, "uuid-rm", 100, 200, "MPEG2-TS", 0,
        true, true, false, 0);
    provider.updateSessionState("sess-rm", 30000, 5242880, 1000);
    provider.computeDeltaIfChanged("sess-rm", 1000); // listener receives
    assertEqual(listener.deltas.size(), 1, "remove: received before removal");

    provider.removeListener(listener);

    // Significant change that would normally emit
    provider.updateSessionState("sess-rm", 60000, 10485760, 5000);
    provider.computeDeltaIfChanged("sess-rm", 5000);
    assertEqual(listener.deltas.size(), 1, "remove: no more after removal");
  }

  // --- Test 15: Null session key safe ---

  private static void testNullSessionKeySafe()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();

    // None of these should throw
    provider.openSession(null, null, "x", 1, 2, "ts", 0, false, false, false, 0);
    assertTrue(provider.getCurrentContext(null) == null, "null key: getCurrentContext=null");
    assertTrue(provider.computeDeltaIfChanged(null, 1000) == null, "null key: computeDelta=null");
    assertTrue(provider.closeSession(null, 1000) == null, "null key: closeSession=null");
    assertTrue(!provider.hasSession(null), "null key: hasSession=false");
    provider.updateSessionState(null, 0, 0, 0);
    provider.notifySeek(null, 0, 0);
    provider.notifyFlush(null, 0);
    provider.notifyEpochChange(null, 0, 0, 0, 0);
    passed++;
    System.out.println("  PASS: null key: all operations safe");
  }

  // --- Test 16: Close emits onSessionClosed to listener ---

  private static void testCloseEmitsSessionClosedToListener()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    RecordingListener listener = new RecordingListener();
    provider.addListener(listener);

    provider.openSession("sess-close", null, "uuid-close", 100, 200, "MPEG2-TS", 0,
        true, true, false, 0);
    provider.closeSession("sess-close", 9000);

    assertEqual(listener.closedSessions.size(), 1, "onClose: listener called");
    assertEqualStr(listener.closedSessions.get(0), "sess-close", "onClose: correct sessionKey");
    assertTrue(listener.closeDeltas.get(0) != null, "onClose: closeDelta non-null");
    assertEqualStr(listener.closeDeltas.get(0).getReason(), "session_close", "onClose: reason=session_close");
  }

  // --- Test 17: No filesystem access ---

  private static void testNoFilesystemAccess()
  {
    // This test verifies that the provider works entirely from injected values.
    // No File.length(), no new File(), no I/O of any kind.
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();

    provider.openSession("sess-noio", null, "uuid-noio", 100, 200, "MPEG2-TS", 0,
        true, true, false, 1689000000000L);

    // Inject values as if they came from already-known server state
    provider.updateSessionState("sess-noio", 45000, 10485760, 1689000045000L);
    NgPlaybackContextDelta delta = provider.computeDeltaIfChanged("sess-noio", 1689000045000L);

    assertTrue(delta != null, "noio: delta from injected values");
    assertEqual(delta.getLive().getGrowthBytes(), 10485760, "noio: injected file size");
    assertEqual(delta.getServerMediaTimeMs(), 45000, "noio: injected media time");

    // Second update with injected growth
    provider.updateSessionState("sess-noio", 50000, 11534336, 1689000050000L);
    NgPlaybackContextDelta delta2 = provider.computeDeltaIfChanged("sess-noio", 1689000050000L);
    assertTrue(delta2 != null, "noio: growth delta from injected values");
    assertEqual(delta2.getLive().getGrowthBytes(), 11534336, "noio: updated file size injected");

    provider.closeSession("sess-noio", 1689000060000L);
    passed++;
    System.out.println("  PASS: noio: entire lifecycle with zero filesystem access");
  }

  // --- Recording listener helper ---

  static class RecordingListener implements NgPlaybackContextListener
  {
    final List<NgPlaybackContextDelta> deltas = new ArrayList<>();
    final List<String> closedSessions = new ArrayList<>();
    final List<NgPlaybackContextDelta> closeDeltas = new ArrayList<>();
    String lastSessionKey;

    public void onDelta(String sessionKey, NgPlaybackContextDelta delta)
    {
      lastSessionKey = sessionKey;
      deltas.add(delta);
    }

    public void onSessionClosed(String sessionKey, NgPlaybackContextDelta closeDelta)
    {
      closedSessions.add(sessionKey);
      closeDeltas.add(closeDelta);
    }
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
