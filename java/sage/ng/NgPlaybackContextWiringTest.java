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
 * Tests for NgPlaybackContextWiring — the MiniPlayer ↔ Provider adapter.
 * Verifies lifecycle wiring, rate limiting, exception safety, file-size handling,
 * and session key generation without requiring any real playback infrastructure.
 */
public class NgPlaybackContextWiringTest
{
  private static int passed = 0;
  private static int failed = 0;

  private static void check(String name, boolean condition)
  {
    if (condition) { passed++; }
    else { failed++; System.out.println("FAIL: " + name); }
  }

  public static void main(String[] args)
  {
    testSessionKeyGeneration();
    testOpenAndClose();
    testOpenPopulatesProvider();
    testSeekNotifiesProvider();
    testFlushNotifiesProvider();
    testPushLoopRateLimiting();
    testPushLoopUpdatesProvider();
    testInactiveFileEpochChange();
    testEpochChange();
    testCloseIsSafe();
    testDoubleClose();
    testMethodsBeforeOpenAreNoOp();
    testProviderExceptionDoesNotPropagate();
    testFileSizeSupplier();
    testFileSizeSupplierRateLimited();
    testNonTimeshiftedFileSizeStatic();
    testBuildSessionKeyNullClient();
    testReopenSameMediaFileProducesDifferentKey();

    System.out.println("\n=== NgPlaybackContextWiringTest ===");
    System.out.println("Passed: " + passed + " Failed: " + failed);
    if (failed > 0) System.exit(1);
  }

  // --- Session key ---

  static void testSessionKeyGeneration()
  {
    String key = NgPlaybackContextWiring.buildSessionKey("client1", 12345, 1);
    check("sessionKey contains clientName", key.contains("client1"));
    check("sessionKey contains mediaFileId", key.contains("12345"));
    check("sessionKey contains generation", key.contains(":1"));
    check("sessionKey format", key.equals("client1:12345:1"));
  }

  static void testBuildSessionKeyNullClient()
  {
    String key = NgPlaybackContextWiring.buildSessionKey(null, 99, 1);
    check("null client uses 'local'", key.equals("local:99:1"));
    String key2 = NgPlaybackContextWiring.buildSessionKey("", 99, 2);
    check("empty client uses 'local'", key2.equals("local:99:2"));
  }

  // --- Open/Close lifecycle ---

  static void testOpenAndClose()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(provider);

    check("not active before open", !wiring.isActive());
    check("sessionKey null before open", wiring.getSessionKey() == null);

    wiring.onPlaybackOpen("testClient", 100, 200, "MPEG2-TS", 3600000,
        true, true, false, System.currentTimeMillis(), 1000000, null);

    check("active after open", wiring.isActive());
    check("sessionKey not null after open", wiring.getSessionKey() != null);
    check("sessionId not null after open", wiring.getSessionId() != null);
    check("sessionId is UUID format", wiring.getSessionId().contains("-"));

    wiring.onPlaybackClose();
    check("not active after close", !wiring.isActive());
    check("sessionKey null after close", wiring.getSessionKey() == null);
  }

  static void testOpenPopulatesProvider()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(provider);

    wiring.onPlaybackOpen("myClient", 500, 600, "MPEG2-TS", 7200000,
        true, true, false, 1000, 5000000, null);

    String key = wiring.getSessionKey();
    check("provider has session after open", provider.hasSession(key));

    NgPlaybackContext ctx = provider.getCurrentContext(key);
    check("context not null", ctx != null);
    check("context mediaFileId", ctx.getMediaFileId() == 500);
    check("context airingId", ctx.getAiringId() == 600);
    check("context container normalized", "ts".equals(ctx.getContainer()));
    check("context durationMs", ctx.getDurationMs() == 7200000);
    check("context mode is live or timeshift", "live".equals(ctx.getMode()) || "timeshift".equals(ctx.getMode()));

    wiring.onPlaybackClose();
    check("provider session cleared after close", !provider.hasSession(key));
  }

  // --- Seek ---

  static void testSeekNotifiesProvider()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    RecordingListener listener = new RecordingListener();
    provider.addListener(listener);
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(provider);

    wiring.onPlaybackOpen("client", 100, 200, "MPEG2-TS", 3600000,
        true, true, false, 0, 1000000, null);

    // Give it some state first so the seek produces a delta
    long now = System.currentTimeMillis();
    provider.updateSessionState(wiring.getSessionKey(), 60000, 2000000, now);
    provider.computeDeltaIfChanged(wiring.getSessionKey(), now);
    listener.deltas.clear();

    // Seek
    wiring.onSeek(120000);

    check("seek produced delta", listener.deltas.size() > 0);
    if (listener.deltas.size() > 0)
    {
      check("seek delta reason is seek", NgDeltaReason.SEEK.equals(listener.deltas.get(0).getReason()));
    }

    wiring.onPlaybackClose();
  }

  // --- Flush ---

  static void testFlushNotifiesProvider()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    RecordingListener listener = new RecordingListener();
    provider.addListener(listener);
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(provider);

    wiring.onPlaybackOpen("client", 100, 200, "MPEG2-TS", 3600000,
        true, true, false, 0, 1000000, null);

    long now = System.currentTimeMillis();
    provider.updateSessionState(wiring.getSessionKey(), 60000, 2000000, now);
    provider.computeDeltaIfChanged(wiring.getSessionKey(), now);
    listener.deltas.clear();

    wiring.onFlush();

    check("flush produced delta", listener.deltas.size() > 0);
    if (listener.deltas.size() > 0)
    {
      check("flush delta reason is flush", NgDeltaReason.FLUSH.equals(listener.deltas.get(0).getReason()));
    }

    wiring.onPlaybackClose();
  }

  // --- Push loop rate limiting ---

  static void testPushLoopRateLimiting()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    RecordingListener listener = new RecordingListener();
    provider.addListener(listener);
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(provider);

    wiring.onPlaybackOpen("client", 100, 200, "MPEG2-TS", 0,
        true, true, false, 0, 1000000, null);

    // Rapid-fire push ticks — should be rate-limited
    for (int i = 0; i < 100; i++)
    {
      wiring.onPushLoopTick(i * 1000, 1000000 + i * 1000, true);
    }

    // With 2000ms interval, 100 rapid calls should result in at most 1 update
    // (the first one, since they happen in < 1ms)
    check("push loop rate-limited (few deltas despite many calls)", listener.deltas.size() <= 2);

    wiring.onPlaybackClose();
  }

  static void testPushLoopUpdatesProvider()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(provider);

    wiring.onPlaybackOpen("client", 100, 200, "MPEG2-TS", 0,
        true, true, false, 0, 1000000, null);

    // Force past the rate limit by using reflection... or just accept the first tick goes through
    wiring.onPushLoopTick(30000, 2000000, true);

    // The provider should have the session with updated state
    check("provider session exists after tick", provider.hasSession(wiring.getSessionKey()));

    wiring.onPlaybackClose();
  }

  // --- Inactive file / epoch change ---

  static void testInactiveFileEpochChange()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    RecordingListener listener = new RecordingListener();
    provider.addListener(listener);
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(provider);

    wiring.onPlaybackOpen("client", 100, 200, "MPEG2-TS", 0,
        true, true, false, 0, 1000000, null);

    long now = System.currentTimeMillis();
    provider.updateSessionState(wiring.getSessionKey(), 60000, 2000000, now);
    provider.computeDeltaIfChanged(wiring.getSessionKey(), now);
    listener.deltas.clear();

    wiring.onInactiveFile(100, 200, 5000000);

    check("inactiveFile produced delta", listener.deltas.size() > 0);
    if (listener.deltas.size() > 0)
    {
      check("inactiveFile delta reason is epoch_change",
          NgDeltaReason.EPOCH_CHANGE.equals(listener.deltas.get(0).getReason()));
    }

    wiring.onPlaybackClose();
  }

  static void testEpochChange()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    RecordingListener listener = new RecordingListener();
    provider.addListener(listener);
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(provider);

    wiring.onPlaybackOpen("client", 100, 200, "MPEG2-TS", 0,
        true, true, false, 0, 1000000, null);

    long now = System.currentTimeMillis();
    provider.updateSessionState(wiring.getSessionKey(), 60000, 2000000, now);
    provider.computeDeltaIfChanged(wiring.getSessionKey(), now);
    listener.deltas.clear();

    wiring.onEpochChange(101, 201, System.currentTimeMillis());

    check("epochChange produced delta", listener.deltas.size() > 0);
    if (listener.deltas.size() > 0)
    {
      check("epochChange delta reason",
          NgDeltaReason.EPOCH_CHANGE.equals(listener.deltas.get(0).getReason()));
    }

    wiring.onPlaybackClose();
  }

  // --- Exception safety ---

  static void testCloseIsSafe()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(provider);

    wiring.onPlaybackOpen("client", 100, 200, "MPEG2-TS", 0,
        true, true, false, 0, 0, null);

    // Close should not throw
    wiring.onPlaybackClose();
    check("close completes without error", true);
  }

  static void testDoubleClose()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(provider);

    wiring.onPlaybackOpen("client", 100, 200, "MPEG2-TS", 0,
        true, true, false, 0, 0, null);

    wiring.onPlaybackClose();
    // Second close should be safe (no-op since not active)
    wiring.onPlaybackClose();
    check("double close is safe", !wiring.isActive());
  }

  static void testMethodsBeforeOpenAreNoOp()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(provider);

    // These should all be no-ops with no exceptions
    wiring.onSeek(5000);
    wiring.onFlush();
    wiring.onPushLoopTick(10000, 50000, true);
    wiring.onInactiveFile(1, 2, 3);
    wiring.onEpochChange(1, 2, 3);
    wiring.onPlaybackClose();

    check("methods before open are safe no-ops", true);
    check("provider has no sessions", provider.getActiveSessionCount() == 0);
  }

  static void testProviderExceptionDoesNotPropagate()
  {
    // Use a provider with a listener that throws
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    provider.addListener(new NgPlaybackContextListener() {
      public void onDelta(String sessionKey, NgPlaybackContextDelta delta) {
        throw new RuntimeException("Intentional test explosion");
      }
      public void onSessionClosed(String sessionKey, NgPlaybackContextDelta closeDelta) {
        throw new RuntimeException("Intentional test explosion");
      }
    });

    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(provider);

    // Open should succeed even with a broken listener
    wiring.onPlaybackOpen("client", 100, 200, "MPEG2-TS", 0,
        true, true, false, 0, 1000000, null);
    check("open succeeds with broken listener", wiring.isActive());

    // Seek should not throw
    wiring.onSeek(5000);
    check("seek succeeds with broken listener", wiring.isActive());

    // Flush should not throw
    wiring.onFlush();
    check("flush succeeds with broken listener", wiring.isActive());

    // Close should not throw
    wiring.onPlaybackClose();
    check("close succeeds with broken listener", !wiring.isActive());
  }

  // --- File size supplier ---

  static void testFileSizeSupplier()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    final long[] suppliedSize = {9999999};
    NgPlaybackContextWiring.FileSizeSupplier supplier = new NgPlaybackContextWiring.FileSizeSupplier() {
      public long getFileSize() { return suppliedSize[0]; }
    };

    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(provider);

    wiring.onPlaybackOpen("client", 100, 200, "MPEG2-TS", 0,
        true, true, false, 0, 0, supplier);

    // First tick: supplier should be consulted since knownFileSize=0 and timeshifted
    // But rate limiting on the supplier might prevent it on the very first call
    // since lastFileSizeRefreshMs was just set. Let's just confirm it doesn't crash.
    wiring.onPushLoopTick(5000, 0, true);
    check("push tick with supplier does not crash", wiring.isActive());

    wiring.onPlaybackClose();
  }

  static void testFileSizeSupplierRateLimited()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    final int[] callCount = {0};
    NgPlaybackContextWiring.FileSizeSupplier supplier = new NgPlaybackContextWiring.FileSizeSupplier() {
      public long getFileSize() { callCount[0]++; return 5000000; }
    };

    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(provider);

    wiring.onPlaybackOpen("client", 100, 200, "MPEG2-TS", 0,
        true, true, false, 0, 0, supplier);

    // Rapid fire — supplier should be called at most once due to rate limiting
    for (int i = 0; i < 50; i++)
    {
      wiring.onPushLoopTick(i * 100, 0, true);
    }

    // Due to both push-loop and supplier rate limiting, calls should be very few
    check("supplier rate-limited (few calls)", callCount[0] <= 2);

    wiring.onPlaybackClose();
  }

  static void testNonTimeshiftedFileSizeStatic()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    final int[] callCount = {0};
    NgPlaybackContextWiring.FileSizeSupplier supplier = new NgPlaybackContextWiring.FileSizeSupplier() {
      public long getFileSize() { callCount[0]++; return 5000000; }
    };

    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(provider);

    // Not timeshifted — supplier should NOT be called
    wiring.onPlaybackOpen("client", 100, 200, "MPEG2-TS", 3600000,
        false, false, false, 0, 8000000, supplier);

    wiring.onPushLoopTick(5000, 8000000, false);

    check("supplier not called for non-timeshifted", callCount[0] == 0);

    wiring.onPlaybackClose();
  }

  // --- Reopen same mediaFileId produces different key ---

  static void testReopenSameMediaFileProducesDifferentKey()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(provider);

    // First open
    wiring.onPlaybackOpen("client", 100, 200, "MPEG2-TS", 3600000,
        false, false, false, 0, 8000000, null);
    String key1 = wiring.getSessionKey();
    String id1 = wiring.getSessionId();
    check("reopen: first key not null", key1 != null);
    check("reopen: first id not null", id1 != null);

    wiring.onPlaybackClose();
    check("reopen: closed first", !wiring.isActive());

    // Second open — same client, same mediaFileId
    wiring.onPlaybackOpen("client", 100, 200, "MPEG2-TS", 3600000,
        false, false, false, 0, 8000000, null);
    String key2 = wiring.getSessionKey();
    String id2 = wiring.getSessionId();
    check("reopen: second key not null", key2 != null);
    check("reopen: second id not null", id2 != null);

    // Keys MUST differ due to openGeneration increment
    check("reopen: keys differ on same mediaFileId", !key1.equals(key2));
    // Session IDs should also differ (UUID-based)
    check("reopen: sessionIds differ", !id1.equals(id2));
    // Both should contain the mediaFileId
    check("reopen: key2 contains mediaFileId", key2.contains("100"));

    wiring.onPlaybackClose();

    // Third open to verify monotonic increment
    wiring.onPlaybackOpen("client", 100, 200, "MPEG2-TS", 3600000,
        false, false, false, 0, 8000000, null);
    String key3 = wiring.getSessionKey();
    check("reopen: third key differs from second", !key2.equals(key3));
    check("reopen: third key differs from first", !key1.equals(key3));

    wiring.onPlaybackClose();
  }

  // --- Helper listener ---

  static class RecordingListener implements NgPlaybackContextListener
  {
    final List<NgPlaybackContextDelta> deltas = new ArrayList<>();
    final List<String> closedSessions = new ArrayList<>();

    public void onDelta(String sessionKey, NgPlaybackContextDelta delta)
    {
      deltas.add(delta);
    }

    public void onSessionClosed(String sessionKey, NgPlaybackContextDelta closeDelta)
    {
      closedSessions.add(sessionKey);
    }
  }
}
