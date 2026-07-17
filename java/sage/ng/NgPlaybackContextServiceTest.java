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
import java.util.Set;

/**
 * Tests for NgPlaybackContextService — the public API facade.
 */
public class NgPlaybackContextServiceTest
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
    testSingleton();
    testGetContextNoSession();
    testGetContextJsonNoSession();
    testHasSessionEmpty();
    testGetActiveSessionKeysEmpty();
    testGetActiveSessionCount();
    testFullLifecycle();
    testGetContextJson();
    testGetAllSessionsEmpty();
    testGetAllSessionsWithData();
    testGetAllSessionsJson();
    testGlobalListener();
    testSessionFilterListener();
    testSessionFilterListenerRemoval();
    testSessionFilterListenerIgnoresOther();
    testDiagnosticsEmpty();
    testDiagnosticsWithSessions();
    testListenerExceptionSafety();
    testGetContextBySessionId();
    testGetContextJsonBySessionId();
    testHasSessionId();
    testSessionIdListenerReceivesDeltas();
    testSessionIdListenerIgnoresOtherSession();
    testSessionIdListenerRemoval();
    testSessionIdListenerExceptionSafety();
    testCloseRemovesSessionIdLookup();
    testReopenNoStaleSessionIdMapping();
    testAllSessionsJsonNoInternalKey();

    System.out.println("\n=== NgPlaybackContextServiceTest ===");
    System.out.println("Passed: " + passed + " Failed: " + failed);
    if (failed > 0) System.exit(1);
  }

  // --- Singleton ---

  static void testSingleton()
  {
    NgPlaybackContextService svc1 = NgPlaybackContextService.getInstance();
    NgPlaybackContextService svc2 = NgPlaybackContextService.getInstance();
    check("singleton: same instance", svc1 == svc2);
    check("singleton: not null", svc1 != null);
  }

  // --- Query API with no sessions ---

  static void testGetContextNoSession()
  {
    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    check("no session: getContext null", svc.getContext("nonexistent") == null);
    check("no session: null key safe", svc.getContext(null) == null);
  }

  static void testGetContextJsonNoSession()
  {
    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    check("no session: getContextJson null", svc.getContextJson("nonexistent") == null);
  }

  static void testHasSessionEmpty()
  {
    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    check("no session: hasSession=false", !svc.hasSession("nonexistent"));
    check("no session: null key safe", !svc.hasSession(null));
  }

  static void testGetActiveSessionKeysEmpty()
  {
    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    // May or may not be empty depending on test ordering, just verify it returns non-null
    Set<String> keys = svc.getActiveSessionKeys();
    check("activeKeys: not null", keys != null);
  }

  static void testGetActiveSessionCount()
  {
    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    // Should be >= 0
    check("activeCount: non-negative", svc.getActiveSessionCount() >= 0);
  }

  // --- Full lifecycle through wiring ---

  static void testFullLifecycle()
  {
    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());

    wiring.onPlaybackOpen("svcTest", 9001, 9002, "MPEG2-TS", 7200000,
        false, false, false, 0, 5000000, null);

    String key = wiring.getSessionKey();
    check("lifecycle: hasSession=true", svc.hasSession(key));
    check("lifecycle: getContext not null", svc.getContext(key) != null);
    check("lifecycle: activeKeys contains key", svc.getActiveSessionKeys().contains(key));

    wiring.onPlaybackClose();
    check("lifecycle: hasSession=false after close", !svc.hasSession(key));
    check("lifecycle: getContext null after close", svc.getContext(key) == null);
  }

  // --- JSON serialization ---

  static void testGetContextJson()
  {
    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());

    wiring.onPlaybackOpen("jsonTest", 8001, 8002, "MP4", 1800000,
        false, false, false, 0, 3000000, null);

    String key = wiring.getSessionKey();
    String json = svc.getContextJson(key);
    check("json: not null", json != null);
    check("json: starts with {", json.startsWith("{"));
    check("json: contains version", json.contains("\"version\""));
    check("json: contains mediaFileId", json.contains("\"mediaFileId\":8001"));
    check("json: contains container", json.contains("\"container\":\"mp4\""));
    check("json: contains durationMs", json.contains("\"durationMs\":1800000"));

    wiring.onPlaybackClose();
  }

  // --- getAllSessions ---

  static void testGetAllSessionsEmpty()
  {
    // Use a fresh provider to guarantee empty
    // Since we use the global singleton, just check structure
    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    List<NgPlaybackContextService.SessionInfo> all = svc.getAllSessions();
    check("allSessions: not null", all != null);
  }

  static void testGetAllSessionsWithData()
  {
    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    NgPlaybackContextWiring w1 = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());
    NgPlaybackContextWiring w2 = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());

    w1.onPlaybackOpen("allTest1", 7001, 7002, "MPEG2-TS", 3600000,
        false, false, false, 0, 1000000, null);
    w2.onPlaybackOpen("allTest2", 7003, 7004, "MP4", 600000,
        false, false, false, 0, 2000000, null);

    List<NgPlaybackContextService.SessionInfo> all = svc.getAllSessions();
    check("allSessions: at least 2", all.size() >= 2);

    boolean found1 = false, found2 = false;
    for (NgPlaybackContextService.SessionInfo info : all)
    {
      if (info.getSessionKey().equals(w1.getSessionKey())) found1 = true;
      if (info.getSessionKey().equals(w2.getSessionKey())) found2 = true;
    }
    check("allSessions: contains session 1", found1);
    check("allSessions: contains session 2", found2);

    w1.onPlaybackClose();
    w2.onPlaybackClose();
  }

  static void testGetAllSessionsJson()
  {
    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());

    wiring.onPlaybackOpen("jsonAllTest", 6001, 6002, "MPEG2-PS", 900000,
        false, false, false, 0, 500000, null);

    String json = svc.getAllSessionsJson();
    check("allJson: starts with [", json.startsWith("["));
    check("allJson: ends with ]", json.endsWith("]"));
    check("allJson: contains sessionId key", json.contains("\"sessionId\""));
    check("allJson: contains mediaFileId", json.contains("6001"));
    check("allJson: does NOT contain sessionKey", !json.contains("\"sessionKey\""));

    wiring.onPlaybackClose();
  }

  // --- Listener API ---

  static void testGlobalListener()
  {
    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    RecordingListener listener = new RecordingListener();
    svc.addListener(listener);

    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());
    wiring.onPlaybackOpen("listenerTest", 5001, 5002, "MPEG2-TS", 0,
        true, true, false, 0, 1000000, null);

    // Force a seek to produce a delta
    String key = wiring.getSessionKey();
    NgPlaybackContextWiring.getGlobalProvider().updateSessionState(key, 30000, 2000000, System.currentTimeMillis());
    NgPlaybackContextWiring.getGlobalProvider().computeDeltaIfChanged(key, System.currentTimeMillis());
    listener.deltas.clear();

    wiring.onSeek(60000);
    check("globalListener: received delta", listener.deltas.size() > 0);

    svc.removeListener(listener);
    listener.deltas.clear();
    wiring.onSeek(90000);
    check("globalListener: no delta after removal", listener.deltas.size() == 0);

    wiring.onPlaybackClose();
  }

  static void testSessionFilterListener()
  {
    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    RecordingListener listener = new RecordingListener();

    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());
    wiring.onPlaybackOpen("filterTest", 4001, 4002, "MPEG2-TS", 0,
        true, true, false, 0, 1000000, null);

    String key = wiring.getSessionKey();
    svc.addSessionListener(key, listener);

    // Seed and force seek
    NgPlaybackContextWiring.getGlobalProvider().updateSessionState(key, 30000, 2000000, System.currentTimeMillis());
    NgPlaybackContextWiring.getGlobalProvider().computeDeltaIfChanged(key, System.currentTimeMillis());
    listener.deltas.clear();

    wiring.onSeek(60000);
    check("sessionFilter: received delta for target session", listener.deltas.size() > 0);

    svc.removeSessionListener(key, listener);
    wiring.onPlaybackClose();
  }

  static void testSessionFilterListenerRemoval()
  {
    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    RecordingListener listener = new RecordingListener();

    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());
    wiring.onPlaybackOpen("filterRm", 3001, 3002, "MPEG2-TS", 0,
        true, true, false, 0, 1000000, null);

    String key = wiring.getSessionKey();
    svc.addSessionListener(key, listener);
    svc.removeSessionListener(key, listener);

    NgPlaybackContextWiring.getGlobalProvider().updateSessionState(key, 30000, 2000000, System.currentTimeMillis());
    NgPlaybackContextWiring.getGlobalProvider().computeDeltaIfChanged(key, System.currentTimeMillis());
    listener.deltas.clear();

    wiring.onSeek(60000);
    check("sessionFilterRemoval: no delta after removal", listener.deltas.size() == 0);

    wiring.onPlaybackClose();
  }

  static void testSessionFilterListenerIgnoresOther()
  {
    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    RecordingListener listener = new RecordingListener();

    NgPlaybackContextWiring wiring1 = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());
    NgPlaybackContextWiring wiring2 = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());

    wiring1.onPlaybackOpen("filterA", 2001, 2002, "MPEG2-TS", 0,
        true, true, false, 0, 1000000, null);
    wiring2.onPlaybackOpen("filterB", 2003, 2004, "MPEG2-TS", 0,
        true, true, false, 0, 1000000, null);

    // Subscribe only to wiring1's session
    String key1 = wiring1.getSessionKey();
    svc.addSessionListener(key1, listener);

    // Seed wiring2 and seek — listener should NOT fire
    String key2 = wiring2.getSessionKey();
    NgPlaybackContextWiring.getGlobalProvider().updateSessionState(key2, 30000, 2000000, System.currentTimeMillis());
    NgPlaybackContextWiring.getGlobalProvider().computeDeltaIfChanged(key2, System.currentTimeMillis());
    listener.deltas.clear();

    wiring2.onSeek(60000);
    check("sessionFilterIgnore: no delta from other session", listener.deltas.size() == 0);

    svc.removeSessionListener(key1, listener);
    wiring1.onPlaybackClose();
    wiring2.onPlaybackClose();
  }

  // --- Diagnostics ---

  static void testDiagnosticsEmpty()
  {
    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    String diag = svc.getDiagnostics();
    check("diagnostics: not null", diag != null);
    check("diagnostics: contains class name", diag.contains("NgPlaybackContextService"));
    check("diagnostics: contains count", diag.contains("active session"));
  }

  static void testDiagnosticsWithSessions()
  {
    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());

    wiring.onPlaybackOpen("diagTest", 1001, 1002, "MPEG2-TS", 3600000,
        false, false, false, 0, 5000000, null);

    String diag = svc.getDiagnostics();
    check("diagWithSession: contains mediaFileId", diag.contains("1001"));
    check("diagWithSession: contains mode", diag.contains("mode="));
    check("diagWithSession: contains container", diag.contains("container="));

    wiring.onPlaybackClose();
  }

  // --- Exception safety ---

  static void testListenerExceptionSafety()
  {
    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    NgPlaybackContextListener broken = new NgPlaybackContextListener() {
      public void onDelta(String sk, NgPlaybackContextDelta d) { throw new RuntimeException("boom"); }
      public void onSessionClosed(String sk, NgPlaybackContextDelta d) { throw new RuntimeException("boom"); }
    };
    svc.addListener(broken);

    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());
    wiring.onPlaybackOpen("exTest", 999, 888, "MPEG2-TS", 0,
        true, true, false, 0, 1000000, null);

    // Should not throw
    wiring.onSeek(5000);
    check("exSafety: seek did not throw with broken listener", true);

    wiring.onPlaybackClose();
    check("exSafety: close did not throw with broken listener", true);

    svc.removeListener(broken);
  }

  // =========================================================================
  // SessionId-based API tests
  // =========================================================================

  static void testGetContextBySessionId()
  {
    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());

    wiring.onPlaybackOpen("sidTest", 11001, 11002, "MPEG2-TS", 3600000,
        false, false, false, 0, 5000000, null);

    String sessionId = wiring.getSessionId();
    String key = wiring.getSessionKey();

    // Same context via both paths
    NgPlaybackContext byKey = svc.getContext(key);
    NgPlaybackContext byId = svc.getContextBySessionId(sessionId);
    check("bySessionId: not null", byId != null);
    check("bySessionId: same as byKey", byKey == byId);
    check("bySessionId: unknown returns null", svc.getContextBySessionId("nonexistent-uuid") == null);
    check("bySessionId: null returns null", svc.getContextBySessionId(null) == null);

    wiring.onPlaybackClose();
  }

  static void testGetContextJsonBySessionId()
  {
    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());

    wiring.onPlaybackOpen("sidJsonTest", 12001, 12002, "MP4", 1800000,
        false, false, false, 0, 3000000, null);

    String sessionId = wiring.getSessionId();
    String json = svc.getContextJsonBySessionId(sessionId);
    check("jsonBySessionId: not null", json != null);
    check("jsonBySessionId: valid JSON start", json.startsWith("{"));
    check("jsonBySessionId: contains mediaFileId", json.contains("12001"));
    check("jsonBySessionId: contains sessionId", json.contains(sessionId));
    check("jsonBySessionId: does NOT contain internal key", !json.contains(wiring.getSessionKey()));
    check("jsonBySessionId: unknown returns null", svc.getContextJsonBySessionId("nope") == null);

    wiring.onPlaybackClose();
  }

  static void testHasSessionId()
  {
    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());

    wiring.onPlaybackOpen("hasIdTest", 13001, 13002, "MPEG2-TS", 0,
        true, true, false, 0, 1000000, null);

    String sessionId = wiring.getSessionId();
    check("hasSessionId: true when open", svc.hasSessionId(sessionId));
    check("hasSessionId: false for unknown", !svc.hasSessionId("bogus-uuid"));
    check("hasSessionId: false for null", !svc.hasSessionId(null));

    wiring.onPlaybackClose();
    check("hasSessionId: false after close", !svc.hasSessionId(sessionId));
  }

  static void testSessionIdListenerReceivesDeltas()
  {
    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    RecordingListener listener = new RecordingListener();

    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());
    wiring.onPlaybackOpen("sidListen", 14001, 14002, "MPEG2-TS", 0,
        true, true, false, 0, 1000000, null);

    String sessionId = wiring.getSessionId();
    String key = wiring.getSessionKey();
    svc.addSessionIdListener(sessionId, listener);

    // Seed and seek to produce delta
    NgPlaybackContextWiring.getGlobalProvider().updateSessionState(key, 30000, 2000000, System.currentTimeMillis());
    NgPlaybackContextWiring.getGlobalProvider().computeDeltaIfChanged(key, System.currentTimeMillis());
    listener.deltas.clear();

    wiring.onSeek(60000);
    check("sidListener: received delta", listener.deltas.size() > 0);
    check("sidListener: delta has correct sessionId", sessionId.equals(listener.deltas.get(0).getSessionId()));

    svc.removeSessionIdListener(sessionId, listener);
    wiring.onPlaybackClose();
  }

  static void testSessionIdListenerIgnoresOtherSession()
  {
    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    RecordingListener listener = new RecordingListener();

    NgPlaybackContextWiring wiring1 = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());
    NgPlaybackContextWiring wiring2 = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());

    wiring1.onPlaybackOpen("sidIgnA", 15001, 15002, "MPEG2-TS", 0, true, true, false, 0, 1000000, null);
    wiring2.onPlaybackOpen("sidIgnB", 15003, 15004, "MPEG2-TS", 0, true, true, false, 0, 1000000, null);

    // Subscribe to session 1 only
    svc.addSessionIdListener(wiring1.getSessionId(), listener);

    // Produce delta on session 2
    String key2 = wiring2.getSessionKey();
    NgPlaybackContextWiring.getGlobalProvider().updateSessionState(key2, 30000, 2000000, System.currentTimeMillis());
    NgPlaybackContextWiring.getGlobalProvider().computeDeltaIfChanged(key2, System.currentTimeMillis());
    listener.deltas.clear();

    wiring2.onSeek(60000);
    check("sidIgnore: no delta from other session", listener.deltas.size() == 0);

    svc.removeSessionIdListener(wiring1.getSessionId(), listener);
    wiring1.onPlaybackClose();
    wiring2.onPlaybackClose();
  }

  static void testSessionIdListenerRemoval()
  {
    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    RecordingListener listener = new RecordingListener();

    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());
    wiring.onPlaybackOpen("sidRm", 16001, 16002, "MPEG2-TS", 0, true, true, false, 0, 1000000, null);

    String sessionId = wiring.getSessionId();
    String key = wiring.getSessionKey();
    svc.addSessionIdListener(sessionId, listener);
    svc.removeSessionIdListener(sessionId, listener);

    NgPlaybackContextWiring.getGlobalProvider().updateSessionState(key, 30000, 2000000, System.currentTimeMillis());
    NgPlaybackContextWiring.getGlobalProvider().computeDeltaIfChanged(key, System.currentTimeMillis());
    listener.deltas.clear();

    wiring.onSeek(60000);
    check("sidRemoval: no delta after removal", listener.deltas.size() == 0);

    wiring.onPlaybackClose();
  }

  static void testSessionIdListenerExceptionSafety()
  {
    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    NgPlaybackContextListener broken = new NgPlaybackContextListener() {
      public void onDelta(String sk, NgPlaybackContextDelta d) { throw new RuntimeException("sid boom"); }
      public void onSessionClosed(String sk, NgPlaybackContextDelta d) { throw new RuntimeException("sid boom"); }
    };

    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());
    wiring.onPlaybackOpen("sidExc", 17001, 17002, "MPEG2-TS", 0, true, true, false, 0, 1000000, null);

    svc.addSessionIdListener(wiring.getSessionId(), broken);
    // Should not throw
    wiring.onSeek(5000);
    check("sidExSafety: seek did not throw", true);

    wiring.onPlaybackClose();
    check("sidExSafety: close did not throw", true);

    svc.removeSessionIdListener(wiring.getSessionId(), broken);
  }

  static void testCloseRemovesSessionIdLookup()
  {
    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());

    wiring.onPlaybackOpen("sidClose", 18001, 18002, "MPEG2-TS", 0, true, true, false, 0, 1000000, null);

    String sessionId = wiring.getSessionId();
    check("sidClose: exists before close", svc.hasSessionId(sessionId));

    wiring.onPlaybackClose();
    check("sidClose: removed after close", !svc.hasSessionId(sessionId));
    check("sidClose: getContext null after close", svc.getContextBySessionId(sessionId) == null);
  }

  static void testReopenNoStaleSessionIdMapping()
  {
    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());

    // First open
    wiring.onPlaybackOpen("staleTest", 19001, 19002, "MPEG2-TS", 0, true, true, false, 0, 1000000, null);
    String sid1 = wiring.getSessionId();
    check("stale: first sessionId exists", svc.hasSessionId(sid1));

    wiring.onPlaybackClose();
    check("stale: first sessionId gone", !svc.hasSessionId(sid1));

    // Reopen same client/media
    wiring.onPlaybackOpen("staleTest", 19001, 19002, "MPEG2-TS", 0, true, true, false, 0, 1000000, null);
    String sid2 = wiring.getSessionId();
    check("stale: second sessionId different", !sid1.equals(sid2));
    check("stale: second sessionId exists", svc.hasSessionId(sid2));
    check("stale: old sessionId NOT resurrected", !svc.hasSessionId(sid1));

    // Context via new sessionId works
    NgPlaybackContext ctx = svc.getContextBySessionId(sid2);
    check("stale: context via new sessionId", ctx != null);
    check("stale: correct mediaFileId", ctx.getMediaFileId() == 19001);

    wiring.onPlaybackClose();
  }

  static void testAllSessionsJsonNoInternalKey()
  {
    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());

    wiring.onPlaybackOpen("noKeyJson", 20001, 20002, "MPEG2-TS", 0, false, false, false, 0, 5000000, null);

    String json = svc.getAllSessionsJson();
    String internalKey = wiring.getSessionKey();
    check("noKeyJson: does NOT contain internal sessionKey value", !json.contains(internalKey));
    check("noKeyJson: does NOT contain sessionKey field name", !json.contains("\"sessionKey\""));
    check("noKeyJson: contains sessionId", json.contains(wiring.getSessionId()));

    wiring.onPlaybackClose();
  }

  // --- Helper ---

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
