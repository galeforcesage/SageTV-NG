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
 * Tests for NgContextHttpHandler — the read-only HTTP transport layer.
 */
public class NgContextHttpHandlerTest
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
    testCanHandle();
    testValidContextLookup();
    testValidContextResponseShape();
    testUnknownSessionId();
    testNullPath();
    testEmptySessionId();
    testInvalidPrefix();
    testTrailingSlash();
    testEventsSubpathDeferred();
    testUnknownSubpath();
    testResponseDoesNotContainInternalKey();
    testResponseContainsSessionId();
    testResponseContainsStreamEpoch();
    testUnavailableResponseShape();
    testBuildDeltaEventJson();
    testBuildSessionClosedEventJson();
    testNoSessionEnumerationEndpoint();
    testMultipleSessions();

    System.out.println("\n=== NgContextHttpHandlerTest ===");
    System.out.println("Passed: " + passed + " Failed: " + failed);
    if (failed > 0) System.exit(1);
  }

  // --- canHandle ---

  static void testCanHandle()
  {
    check("canHandle: valid path", NgContextHttpHandler.canHandle("/ng/playback-context/abc"));
    check("canHandle: prefix only", NgContextHttpHandler.canHandle("/ng/playback-context/"));
    check("canHandle: wrong prefix", !NgContextHttpHandler.canHandle("/api/other"));
    check("canHandle: null", !NgContextHttpHandler.canHandle(null));
  }

  // --- Valid context lookup ---

  static void testValidContextLookup()
  {
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());
    wiring.onPlaybackOpen("httpTest", 30001, 30002, "MPEG2-TS", 3600000,
        false, false, false, 0, 5000000, null);

    String sessionId = wiring.getSessionId();
    NgContextHttpHandler.Response resp = NgContextHttpHandler.handleGet("/ng/playback-context/" + sessionId);

    check("validLookup: status 200", resp.statusCode == 200);
    check("validLookup: content-type json", "application/json".equals(resp.contentType));
    check("validLookup: body not null", resp.body != null);
    check("validLookup: body starts with {", resp.body.startsWith("{"));

    wiring.onPlaybackClose();
  }

  static void testValidContextResponseShape()
  {
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());
    wiring.onPlaybackOpen("httpShape", 31001, 31002, "MP4", 1800000,
        false, false, false, 0, 3000000, null);

    String sessionId = wiring.getSessionId();
    NgContextHttpHandler.Response resp = NgContextHttpHandler.handleGet("/ng/playback-context/" + sessionId);

    String body = resp.body;
    check("shape: contains type field", body.contains("\"type\":\"NG_PLAYBACK_CONTEXT\""));
    check("shape: contains sessionId field", body.contains("\"sessionId\":\"" + sessionId + "\""));
    check("shape: contains context object", body.contains("\"context\":{"));
    check("shape: context has version", body.contains("\"version\":1"));
    check("shape: context has mediaFileId", body.contains("\"mediaFileId\":31001"));
    check("shape: context has container", body.contains("\"container\":\"mp4\""));

    wiring.onPlaybackClose();
  }

  // --- Unknown session ---

  static void testUnknownSessionId()
  {
    NgContextHttpHandler.Response resp = NgContextHttpHandler.handleGet("/ng/playback-context/nonexistent-uuid-1234");

    check("unknown: status 404", resp.statusCode == 404);
    check("unknown: content-type json", "application/json".equals(resp.contentType));
    check("unknown: type is UNAVAILABLE", resp.body.contains("\"type\":\"NG_PLAYBACK_CONTEXT_UNAVAILABLE\""));
    check("unknown: reason is unknown_session", resp.body.contains("\"reason\":\"unknown_session\""));
    check("unknown: contains sessionId", resp.body.contains("nonexistent-uuid-1234"));
  }

  // --- Edge cases ---

  static void testNullPath()
  {
    NgContextHttpHandler.Response resp = NgContextHttpHandler.handleGet(null);
    check("null path: status 404", resp.statusCode == 404);
  }

  static void testEmptySessionId()
  {
    NgContextHttpHandler.Response resp = NgContextHttpHandler.handleGet("/ng/playback-context/");
    check("empty sessionId: status 404", resp.statusCode == 404);
    check("empty sessionId: reason", resp.body.contains("missing_session_id"));
  }

  static void testInvalidPrefix()
  {
    NgContextHttpHandler.Response resp = NgContextHttpHandler.handleGet("/api/something");
    check("invalid prefix: status 404", resp.statusCode == 404);
  }

  static void testTrailingSlash()
  {
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());
    wiring.onPlaybackOpen("trailSlash", 32001, 32002, "MPEG2-TS", 0,
        false, false, false, 0, 1000000, null);

    String sessionId = wiring.getSessionId();
    NgContextHttpHandler.Response resp = NgContextHttpHandler.handleGet("/ng/playback-context/" + sessionId + "/");

    check("trailingSlash: status 200", resp.statusCode == 200);
    check("trailingSlash: valid response", resp.body.contains("NG_PLAYBACK_CONTEXT"));

    wiring.onPlaybackClose();
  }

  // --- SSE deferred ---

  static void testEventsSubpathDeferred()
  {
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());
    wiring.onPlaybackOpen("eventsTest", 33001, 33002, "MPEG2-TS", 0,
        false, false, false, 0, 1000000, null);

    String sessionId = wiring.getSessionId();
    NgContextHttpHandler.Response resp = NgContextHttpHandler.handleGet("/ng/playback-context/" + sessionId + "/events");

    check("events: status 501", resp.statusCode == 501);
    check("events: reason streaming_not_implemented", resp.body.contains("streaming_not_implemented"));

    wiring.onPlaybackClose();
  }

  static void testUnknownSubpath()
  {
    NgContextHttpHandler.Response resp = NgContextHttpHandler.handleGet("/ng/playback-context/someid/unknown");
    check("unknownSubpath: status 404", resp.statusCode == 404);
  }

  // --- Security: no internal key exposure ---

  static void testResponseDoesNotContainInternalKey()
  {
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());
    wiring.onPlaybackOpen("noKeyTest", 34001, 34002, "MPEG2-TS", 3600000,
        false, false, false, 0, 5000000, null);

    String sessionId = wiring.getSessionId();
    String internalKey = wiring.getSessionKey();
    NgContextHttpHandler.Response resp = NgContextHttpHandler.handleGet("/ng/playback-context/" + sessionId);

    check("noInternalKey: body does not contain internal key", !resp.body.contains(internalKey));
    check("noInternalKey: body does not contain sessionKey field", !resp.body.contains("\"sessionKey\""));

    wiring.onPlaybackClose();
  }

  static void testResponseContainsSessionId()
  {
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());
    wiring.onPlaybackOpen("sidInResp", 35001, 35002, "MPEG2-TS", 0,
        false, false, false, 0, 1000000, null);

    String sessionId = wiring.getSessionId();
    NgContextHttpHandler.Response resp = NgContextHttpHandler.handleGet("/ng/playback-context/" + sessionId);

    // sessionId should appear in both the wrapper and the context body
    check("sessionIdInResp: in wrapper", resp.body.contains("\"sessionId\":\"" + sessionId + "\""));

    wiring.onPlaybackClose();
  }

  static void testResponseContainsStreamEpoch()
  {
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());
    wiring.onPlaybackOpen("epochTest", 36001, 36002, "MPEG2-TS", 0,
        true, true, false, 0, 1000000, null);

    String sessionId = wiring.getSessionId();
    NgContextHttpHandler.Response resp = NgContextHttpHandler.handleGet("/ng/playback-context/" + sessionId);

    // The context should contain the live section (since timeshifted+isLive)
    check("streamEpoch: live section present", resp.body.contains("\"live\""));

    wiring.onPlaybackClose();
  }

  // --- Unavailable response shape ---

  static void testUnavailableResponseShape()
  {
    NgContextHttpHandler.Response resp = NgContextHttpHandler.handleGet("/ng/playback-context/test-unavail-id");

    check("unavailShape: has type", resp.body.contains("\"type\":\"NG_PLAYBACK_CONTEXT_UNAVAILABLE\""));
    check("unavailShape: has sessionId", resp.body.contains("\"sessionId\":\"test-unavail-id\""));
    check("unavailShape: has reason", resp.body.contains("\"reason\":\"unknown_session\""));
    check("unavailShape: valid JSON braces", resp.body.startsWith("{") && resp.body.endsWith("}"));
  }

  // --- Helper JSON builders ---

  static void testBuildDeltaEventJson()
  {
    String deltaJson = "{\"version\":1,\"sessionId\":\"abc\",\"streamEpoch\":1}";
    String result = NgContextHttpHandler.buildDeltaEventJson("abc", deltaJson);

    check("deltaEvent: has type", result.contains("\"type\":\"NG_PLAYBACK_CONTEXT_DELTA\""));
    check("deltaEvent: has sessionId", result.contains("\"sessionId\":\"abc\""));
    check("deltaEvent: has delta object", result.contains("\"delta\":{"));
    check("deltaEvent: valid JSON", result.startsWith("{") && result.endsWith("}"));
  }

  static void testBuildSessionClosedEventJson()
  {
    String result = NgContextHttpHandler.buildSessionClosedEventJson("xyz-uuid");

    check("closedEvent: has type UNAVAILABLE", result.contains("NG_PLAYBACK_CONTEXT_UNAVAILABLE"));
    check("closedEvent: has sessionId", result.contains("xyz-uuid"));
    check("closedEvent: reason is session_closed", result.contains("\"reason\":\"session_closed\""));
  }

  // --- No enumeration endpoint ---

  static void testNoSessionEnumerationEndpoint()
  {
    // Verify that /ng/playback-context/ with no sessionId does NOT list sessions
    NgContextHttpHandler.Response resp = NgContextHttpHandler.handleGet("/ng/playback-context/");
    check("noEnum: does not return 200", resp.statusCode != 200);
    check("noEnum: does not contain array", !resp.body.startsWith("["));
  }

  // --- Multiple sessions ---

  static void testMultipleSessions()
  {
    NgPlaybackContextWiring w1 = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());
    NgPlaybackContextWiring w2 = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());

    w1.onPlaybackOpen("multiA", 37001, 37002, "MPEG2-TS", 3600000, false, false, false, 0, 5000000, null);
    w2.onPlaybackOpen("multiB", 37003, 37004, "MP4", 900000, false, false, false, 0, 2000000, null);

    NgContextHttpHandler.Response resp1 = NgContextHttpHandler.handleGet("/ng/playback-context/" + w1.getSessionId());
    NgContextHttpHandler.Response resp2 = NgContextHttpHandler.handleGet("/ng/playback-context/" + w2.getSessionId());

    check("multi: session1 status 200", resp1.statusCode == 200);
    check("multi: session2 status 200", resp2.statusCode == 200);
    check("multi: session1 has its mediaFileId", resp1.body.contains("37001"));
    check("multi: session2 has its mediaFileId", resp2.body.contains("37003"));
    check("multi: session1 does not have session2 data", !resp1.body.contains("37003"));
    check("multi: session2 does not have session1 data", !resp2.body.contains("37001"));

    w1.onPlaybackClose();
    w2.onPlaybackClose();

    // After close, both return 404
    NgContextHttpHandler.Response resp1after = NgContextHttpHandler.handleGet("/ng/playback-context/" + w1.getSessionId());
    check("multi: session1 404 after close", resp1after.statusCode == 404);
  }
}
