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
 * Tests for client-identity-based session lookup in NgPlaybackContextProvider,
 * NgPlaybackContextService, and NgContextHttpHandler.
 */
public class NgClientIdentityLookupTest
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
    testProviderClientNameLookup();
    testProviderClientNameCloseRemovesMapping();
    testProviderReopenUpdatesMappingToNewSessionId();
    testProviderUnknownClientNameReturnsNull();
    testProviderMultipleClientsDoNotCrossResolve();
    testProviderDuplicateClientOverwritesToLatest();
    testServiceGetCurrentSessionIdForClientName();
    testServiceGetCurrentContextForClientName();
    testServiceGetCurrentContextJsonForClientName();
    testServiceHasActiveSessionForClientName();
    testServiceUnknownClientReturnsNull();
    testServiceCloseRemovesClientMapping();
    testServiceReopenCreatesNewSessionId();
    testServiceJsonDoesNotContainInternalKey();
    testHttpCurrentEndpointSuccess();
    testHttpCurrentEndpointUnknownClient();
    testHttpCurrentEndpointMissingParam();
    testHttpCurrentEndpointInlineQueryString();
    testHttpCurrentEndpointWithSeparateQueryString();
    testHttpCurrentEndpointAfterClose();
    testQueryParamParsing();
    testExistingSessionIdApisStillWork();

    System.out.println("\n=== NgClientIdentityLookupTest ===");
    System.out.println("Passed: " + passed + " Failed: " + failed);
    if (failed > 0) System.exit(1);
  }

  // --- Provider-level tests ---

  static void testProviderClientNameLookup()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    provider.openSession("clientA:1000:1", "clientA", "uuid-1", 1000, 2000,
        "MPEG2-TS", 3600000, false, false, false, 0);

    check("provider: hasClientName true", provider.hasClientName("clientA"));
    check("provider: getSessionIdByClientName", "uuid-1".equals(provider.getSessionIdByClientName("clientA")));

    NgPlaybackContext ctx = provider.getContextByClientName("clientA");
    check("provider: context non-null", ctx != null);
    check("provider: correct mediaFileId", ctx.getMediaFileId() == 1000);

    provider.closeSession("clientA:1000:1", System.currentTimeMillis());
  }

  static void testProviderClientNameCloseRemovesMapping()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    provider.openSession("clientB:2000:1", "clientB", "uuid-2", 2000, 3000,
        "MP4", 0, false, false, false, 0);

    check("close: before close has client", provider.hasClientName("clientB"));
    provider.closeSession("clientB:2000:1", System.currentTimeMillis());
    check("close: after close no client", !provider.hasClientName("clientB"));
    check("close: sessionId null", provider.getSessionIdByClientName("clientB") == null);
  }

  static void testProviderReopenUpdatesMappingToNewSessionId()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    provider.openSession("clientC:3000:1", "clientC", "uuid-3a", 3000, 4000,
        "MPEG2-TS", 0, true, true, false, 0);

    String firstSessionId = provider.getSessionIdByClientName("clientC");
    check("reopen: first sessionId", "uuid-3a".equals(firstSessionId));

    // Close and reopen with different generation
    provider.closeSession("clientC:3000:1", System.currentTimeMillis());
    provider.openSession("clientC:3000:2", "clientC", "uuid-3b", 3000, 4000,
        "MPEG2-TS", 0, true, true, false, 0);

    String secondSessionId = provider.getSessionIdByClientName("clientC");
    check("reopen: second sessionId different", "uuid-3b".equals(secondSessionId));
    check("reopen: old sessionId gone", !provider.hasSessionId("uuid-3a"));

    provider.closeSession("clientC:3000:2", System.currentTimeMillis());
  }

  static void testProviderUnknownClientNameReturnsNull()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    check("unknown: hasClientName false", !provider.hasClientName("nobody"));
    check("unknown: sessionId null", provider.getSessionIdByClientName("nobody") == null);
    check("unknown: context null", provider.getContextByClientName("nobody") == null);
    check("unknown: null name", !provider.hasClientName(null));
    check("unknown: empty name", !provider.hasClientName(""));
  }

  static void testProviderMultipleClientsDoNotCrossResolve()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    provider.openSession("alpha:100:1", "alpha", "uuid-alpha", 100, 1,
        "MPEG2-TS", 0, false, false, false, 0);
    provider.openSession("beta:200:1", "beta", "uuid-beta", 200, 2,
        "MP4", 0, false, false, false, 0);

    check("multi: alpha resolves alpha", "uuid-alpha".equals(provider.getSessionIdByClientName("alpha")));
    check("multi: beta resolves beta", "uuid-beta".equals(provider.getSessionIdByClientName("beta")));
    check("multi: alpha context correct", provider.getContextByClientName("alpha").getMediaFileId() == 100);
    check("multi: beta context correct", provider.getContextByClientName("beta").getMediaFileId() == 200);

    provider.closeSession("alpha:100:1", System.currentTimeMillis());
    provider.closeSession("beta:200:1", System.currentTimeMillis());
  }

  static void testProviderDuplicateClientOverwritesToLatest()
  {
    NgPlaybackContextProvider provider = new NgPlaybackContextProvider();
    // Open first session for clientD
    provider.openSession("clientD:500:1", "clientD", "uuid-d1", 500, 1,
        "MPEG2-TS", 0, false, false, false, 0);
    // Open second session for same clientD (simulating reopen without close)
    provider.openSession("clientD:600:2", "clientD", "uuid-d2", 600, 2,
        "MP4", 0, false, false, false, 0);

    // Latest wins
    String resolved = provider.getSessionIdByClientName("clientD");
    check("dup: latest sessionId", "uuid-d2".equals(resolved));
    check("dup: latest mediaFileId", provider.getContextByClientName("clientD").getMediaFileId() == 600);

    provider.closeSession("clientD:500:1", System.currentTimeMillis());
    provider.closeSession("clientD:600:2", System.currentTimeMillis());
  }

  // --- Service-level tests ---

  static void testServiceGetCurrentSessionIdForClientName()
  {
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());
    wiring.onPlaybackOpen("svcClient1", 10001, 10002, "MPEG2-TS", 3600000,
        false, false, false, 0, 5000000, null);

    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    String sessionId = svc.getCurrentSessionIdForClientName("svcClient1");
    check("svc.sessionId: non-null", sessionId != null);
    check("svc.sessionId: matches wiring", sessionId.equals(wiring.getSessionId()));

    wiring.onPlaybackClose();
  }

  static void testServiceGetCurrentContextForClientName()
  {
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());
    wiring.onPlaybackOpen("svcClient2", 20001, 20002, "MP4", 900000,
        false, false, false, 0, 2000000, null);

    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    NgPlaybackContext ctx = svc.getCurrentContextForClientName("svcClient2");
    check("svc.context: non-null", ctx != null);
    check("svc.context: correct mediaFileId", ctx.getMediaFileId() == 20001);
    check("svc.context: correct container", "mp4".equals(ctx.getContainer()));

    wiring.onPlaybackClose();
  }

  static void testServiceGetCurrentContextJsonForClientName()
  {
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());
    wiring.onPlaybackOpen("svcClient3", 30001, 30002, "MPEG2-TS", 7200000,
        false, false, false, 0, 4000000, null);

    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    String json = svc.getCurrentContextJsonForClientName("svcClient3");
    check("svc.json: non-null", json != null);
    check("svc.json: valid json", json.startsWith("{") && json.endsWith("}"));
    check("svc.json: contains mediaFileId", json.contains("30001"));
    check("svc.json: does not contain internal key", !json.contains("svcClient3:30001:"));

    wiring.onPlaybackClose();
  }

  static void testServiceHasActiveSessionForClientName()
  {
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());
    wiring.onPlaybackOpen("svcClient4", 40001, 40002, "MPEG2-TS", 0,
        false, false, false, 0, 1000000, null);

    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    check("svc.has: true", svc.hasActiveSessionForClientName("svcClient4"));
    check("svc.has: false for unknown", !svc.hasActiveSessionForClientName("nobody"));

    wiring.onPlaybackClose();
  }

  static void testServiceUnknownClientReturnsNull()
  {
    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    check("svc.unknown: sessionId null", svc.getCurrentSessionIdForClientName("ghostClient") == null);
    check("svc.unknown: context null", svc.getCurrentContextForClientName("ghostClient") == null);
    check("svc.unknown: json null", svc.getCurrentContextJsonForClientName("ghostClient") == null);
    check("svc.unknown: has false", !svc.hasActiveSessionForClientName("ghostClient"));
    check("svc.unknown: null name", svc.getCurrentSessionIdForClientName(null) == null);
  }

  static void testServiceCloseRemovesClientMapping()
  {
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());
    wiring.onPlaybackOpen("svcClose", 50001, 50002, "MPEG2-TS", 0,
        false, false, false, 0, 1000000, null);

    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    check("svc.close: has before", svc.hasActiveSessionForClientName("svcClose"));
    wiring.onPlaybackClose();
    check("svc.close: has after", !svc.hasActiveSessionForClientName("svcClose"));
  }

  static void testServiceReopenCreatesNewSessionId()
  {
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());
    wiring.onPlaybackOpen("svcReopen", 60001, 60002, "MPEG2-TS", 0,
        false, false, false, 0, 1000000, null);

    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    String firstId = svc.getCurrentSessionIdForClientName("svcReopen");
    wiring.onPlaybackClose();

    // Reopen same client, same mediaFileId
    wiring.onPlaybackOpen("svcReopen", 60001, 60002, "MPEG2-TS", 0,
        false, false, false, 0, 1000000, null);
    String secondId = svc.getCurrentSessionIdForClientName("svcReopen");

    check("svc.reopen: different sessionId", !firstId.equals(secondId));
    check("svc.reopen: second is current", secondId.equals(wiring.getSessionId()));

    wiring.onPlaybackClose();
  }

  static void testServiceJsonDoesNotContainInternalKey()
  {
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());
    wiring.onPlaybackOpen("svcJson", 70001, 70002, "MPEG2-TS", 0,
        false, false, false, 0, 1000000, null);

    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    String json = svc.getCurrentContextJsonForClientName("svcJson");
    String internalKey = wiring.getSessionKey();

    check("svc.nokey: json has no internal key", !json.contains(internalKey));
    check("svc.nokey: json has no openGeneration exposed", !json.contains("svcJson:70001:"));

    wiring.onPlaybackClose();
  }

  // --- HTTP handler tests ---

  static void testHttpCurrentEndpointSuccess()
  {
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());
    wiring.onPlaybackOpen("httpClient1", 80001, 80002, "MPEG2-TS", 3600000,
        false, false, false, 0, 5000000, null);

    NgContextHttpHandler.Response resp = NgContextHttpHandler.handleGet(
        "/ng/playback-context/current?clientName=httpClient1");

    check("http.current: status 200", resp.statusCode == 200);
    check("http.current: type NG_PLAYBACK_CONTEXT", resp.body.contains("\"type\":\"NG_PLAYBACK_CONTEXT\""));
    check("http.current: has sessionId", resp.body.contains("\"sessionId\":\"" + wiring.getSessionId() + "\""));
    check("http.current: has context", resp.body.contains("\"context\":{"));
    check("http.current: has mediaFileId", resp.body.contains("80001"));
    check("http.current: no internal key", !resp.body.contains(wiring.getSessionKey()));

    wiring.onPlaybackClose();
  }

  static void testHttpCurrentEndpointUnknownClient()
  {
    NgContextHttpHandler.Response resp = NgContextHttpHandler.handleGet(
        "/ng/playback-context/current?clientName=unknownHttpClient");

    check("http.unknown: status 404", resp.statusCode == 404);
    check("http.unknown: unavailable type", resp.body.contains("NG_PLAYBACK_CONTEXT_UNAVAILABLE"));
    check("http.unknown: reason no_active_session", resp.body.contains("no_active_session"));
  }

  static void testHttpCurrentEndpointMissingParam()
  {
    NgContextHttpHandler.Response resp1 = NgContextHttpHandler.handleGet(
        "/ng/playback-context/current");
    check("http.missing: no query → 400", resp1.statusCode == 400);
    check("http.missing: reason bad_request", resp1.body.contains("bad_request"));

    NgContextHttpHandler.Response resp2 = NgContextHttpHandler.handleGet(
        "/ng/playback-context/current?otherParam=x");
    check("http.wrongparam: 400", resp2.statusCode == 400);
  }

  static void testHttpCurrentEndpointInlineQueryString()
  {
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());
    wiring.onPlaybackOpen("inlineQS", 81001, 81002, "MP4", 0,
        false, false, false, 0, 1000000, null);

    // Query string embedded in path (single-arg handleGet)
    NgContextHttpHandler.Response resp = NgContextHttpHandler.handleGet(
        "/ng/playback-context/current?clientName=inlineQS");
    check("http.inline: 200", resp.statusCode == 200);
    check("http.inline: correct mediaFileId", resp.body.contains("81001"));

    wiring.onPlaybackClose();
  }

  static void testHttpCurrentEndpointWithSeparateQueryString()
  {
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());
    wiring.onPlaybackOpen("separateQS", 82001, 82002, "MPEG2-TS", 0,
        false, false, false, 0, 1000000, null);

    // Two-arg handleGet (path, queryString)
    NgContextHttpHandler.Response resp = NgContextHttpHandler.handleGet(
        "/ng/playback-context/current", "clientName=separateQS");
    check("http.separate: 200", resp.statusCode == 200);
    check("http.separate: correct mediaFileId", resp.body.contains("82001"));

    wiring.onPlaybackClose();
  }

  static void testHttpCurrentEndpointAfterClose()
  {
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());
    wiring.onPlaybackOpen("closedHttp", 83001, 83002, "MPEG2-TS", 0,
        false, false, false, 0, 1000000, null);
    wiring.onPlaybackClose();

    NgContextHttpHandler.Response resp = NgContextHttpHandler.handleGet(
        "/ng/playback-context/current?clientName=closedHttp");
    check("http.closed: 404", resp.statusCode == 404);
    check("http.closed: no_active_session", resp.body.contains("no_active_session"));
  }

  // --- Query param utility ---

  static void testQueryParamParsing()
  {
    check("qp: simple", "bar".equals(NgContextHttpHandler.getQueryParam("foo=bar", "foo")));
    check("qp: multi", "val".equals(NgContextHttpHandler.getQueryParam("a=1&foo=val&b=2", "foo")));
    check("qp: first", "first".equals(NgContextHttpHandler.getQueryParam("x=first&y=second", "x")));
    check("qp: last", "second".equals(NgContextHttpHandler.getQueryParam("x=first&y=second", "y")));
    check("qp: null qs", NgContextHttpHandler.getQueryParam(null, "foo") == null);
    check("qp: empty qs", NgContextHttpHandler.getQueryParam("", "foo") == null);
    check("qp: missing key", NgContextHttpHandler.getQueryParam("a=1&b=2", "c") == null);
  }

  // --- Regression: existing sessionId APIs ---

  static void testExistingSessionIdApisStillWork()
  {
    NgPlaybackContextWiring wiring = new NgPlaybackContextWiring(NgPlaybackContextWiring.getGlobalProvider());
    wiring.onPlaybackOpen("regrClient", 90001, 90002, "MPEG2-TS", 0,
        false, false, false, 0, 1000000, null);

    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    String sessionId = wiring.getSessionId();

    check("regr: hasSessionId", svc.hasSessionId(sessionId));
    check("regr: getContextBySessionId", svc.getContextBySessionId(sessionId) != null);
    check("regr: getContextJsonBySessionId", svc.getContextJsonBySessionId(sessionId) != null);

    // HTTP sessionId lookup still works
    NgContextHttpHandler.Response resp = NgContextHttpHandler.handleGet(
        "/ng/playback-context/" + sessionId);
    check("regr: http 200", resp.statusCode == 200);

    wiring.onPlaybackClose();
    check("regr: after close hasSessionId false", !svc.hasSessionId(sessionId));
  }
}
