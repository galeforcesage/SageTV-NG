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
 * Read-only HTTP request handler for NG Playback Context endpoints.
 * <p>
 * This class is a stateless request router — it parses a request path, calls
 * {@link NgPlaybackContextService}, and returns a structured HTTP response.
 * It is intentionally framework-agnostic: the caller provides the request path
 * and receives a response object containing status, content-type, and body.
 * <p>
 * <b>Supported endpoints:</b>
 * <ul>
 *   <li>{@code GET /ng/playback-context/{sessionId}} — returns full context or unavailable</li>
 * </ul>
 * <p>
 * <b>Security notes:</b>
 * <ul>
 *   <li>Internal sessionKey is NEVER included in responses</li>
 *   <li>No session enumeration endpoint for normal clients</li>
 *   <li>SessionId acts as an opaque capability identifier</li>
 *   <li>These endpoints should be LAN/internal only until auth is added</li>
 * </ul>
 * <p>
 * <b>SSE/WebSocket streaming:</b> Deferred — the current repo does not have
 * a safe SSE or persistent-connection framework. A future phase will add
 * event streaming once HTTPLSServer or an equivalent is extended.
 * <p>
 * <b>Integration with HTTPLSServer:</b>
 * <pre>{@code
 *   // In HTTPLSServer.handleRequest() or equivalent routing:
 *   if (requestPath.startsWith("/ng/")) {
 *     NgContextHttpHandler.Response resp = NgContextHttpHandler.handleGet(requestPath);
 *     writeHttpResponse(channel, resp.statusCode, resp.contentType, resp.body);
 *     return;
 *   }
 * }</pre>
 */
public final class NgContextHttpHandler
{
  /** Prefix for all NG context endpoints. */
  public static final String PATH_PREFIX = "/ng/playback-context/";

  private NgContextHttpHandler() {}

  /**
   * Immutable HTTP response value.
   */
  public static final class Response
  {
    public final int statusCode;
    public final String contentType;
    public final String body;

    Response(int statusCode, String contentType, String body)
    {
      this.statusCode = statusCode;
      this.contentType = contentType;
      this.body = body;
    }
  }

  /**
   * Handle a GET request to an NG context endpoint.
   * <p>
   * Supported paths:
   * <ul>
   *   <li>{@code /ng/playback-context/{sessionId}} — full context lookup by sessionId</li>
   *   <li>{@code /ng/playback-context/current?clientName={name}} — lookup by client identity</li>
   * </ul>
   *
   * @param requestPath the HTTP request path (e.g. "/ng/playback-context/abc-123-uuid")
   * @return the response to send to the client, never null
   */
  public static Response handleGet(String requestPath)
  {
    return handleGet(requestPath, null);
  }

  /**
   * Handle a GET request with optional query string.
   *
   * @param requestPath the HTTP request path (without query string)
   * @param queryString the query string (portion after '?'), or null
   * @return the response to send to the client, never null
   */
  public static Response handleGet(String requestPath, String queryString)
  {
    if (requestPath == null || !requestPath.startsWith(PATH_PREFIX))
    {
      return notFound("invalid_path");
    }

    // Extract remainder from path: /ng/playback-context/{remainder}
    String remainder = requestPath.substring(PATH_PREFIX.length());

    // Handle inline query string (some callers may pass path?query as one string)
    if (queryString == null)
    {
      int qIdx = remainder.indexOf('?');
      if (qIdx >= 0)
      {
        queryString = remainder.substring(qIdx + 1);
        remainder = remainder.substring(0, qIdx);
      }
    }

    // Strip trailing slash if present
    if (remainder.endsWith("/"))
    {
      remainder = remainder.substring(0, remainder.length() - 1);
    }

    // Special route: /ng/playback-context/current?clientName=...
    if ("current".equals(remainder))
    {
      return handleCurrentClientLookup(queryString);
    }

    // Check for sub-path (e.g., /events)
    int slashIdx = remainder.indexOf('/');
    if (slashIdx >= 0)
    {
      String sessionId = remainder.substring(0, slashIdx);
      String subPath = remainder.substring(slashIdx + 1);

      if ("events".equals(subPath))
      {
        // SSE streaming endpoint — deferred
        return new Response(501, "application/json",
            buildUnavailableJson(sessionId, "streaming_not_implemented"));
      }
      // Unknown sub-path
      return notFound("invalid_path");
    }

    // Simple context lookup: /ng/playback-context/{sessionId}
    String sessionId = remainder;
    if (sessionId.isEmpty())
    {
      return notFound("missing_session_id");
    }

    return handleContextLookup(sessionId);
  }

  /**
   * Check whether a request path is handled by this handler.
   * Useful for routing in HTTPLSServer or similar.
   */
  public static boolean canHandle(String requestPath)
  {
    return requestPath != null && requestPath.startsWith(PATH_PREFIX);
  }

  // --- Internal ---

  private static Response handleCurrentClientLookup(String queryString)
  {
    String clientName = getQueryParam(queryString, "clientName");
    if (clientName == null || clientName.length() == 0)
    {
      return new Response(400, "application/json",
          buildUnavailableJson("", "bad_request"));
    }

    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
    String sessionId = svc.getCurrentSessionIdForClientName(clientName);
    if (sessionId == null)
    {
      return new Response(404, "application/json",
          buildUnavailableJson("", "no_active_session"));
    }

    String contextJson = svc.getContextJsonBySessionId(sessionId);
    if (contextJson == null)
    {
      return new Response(404, "application/json",
          buildUnavailableJson(sessionId, "session_closed"));
    }

    return new Response(200, "application/json",
        buildContextResponseJson(sessionId, contextJson));
  }

  private static Response handleContextLookup(String sessionId)
  {
    NgPlaybackContextService svc = NgPlaybackContextService.getInstance();

    if (!svc.hasSessionId(sessionId))
    {
      return new Response(404, "application/json",
          buildUnavailableJson(sessionId, "unknown_session"));
    }

    String contextJson = svc.getContextJsonBySessionId(sessionId);
    if (contextJson == null)
    {
      // Race: session closed between hasSessionId and getContextJson
      return new Response(404, "application/json",
          buildUnavailableJson(sessionId, "session_closed"));
    }

    return new Response(200, "application/json",
        buildContextResponseJson(sessionId, contextJson));
  }

  private static Response notFound(String reason)
  {
    return new Response(404, "application/json",
        buildUnavailableJson("", reason));
  }

  /**
   * Build the success response JSON wrapper.
   * Shape:
   * <pre>
   * {
   *   "type": "NG_PLAYBACK_CONTEXT",
   *   "sessionId": "...",
   *   "context": { ... }
   * }
   * </pre>
   */
  static String buildContextResponseJson(String sessionId, String contextJson)
  {
    StringBuilder sb = new StringBuilder(contextJson.length() + 80);
    sb.append("{\"type\":\"NG_PLAYBACK_CONTEXT\",\"sessionId\":\"");
    appendEscaped(sb, sessionId);
    sb.append("\",\"context\":");
    sb.append(contextJson);
    sb.append('}');
    return sb.toString();
  }

  /**
   * Build the unavailable response JSON.
   * Shape:
   * <pre>
   * {
   *   "type": "NG_PLAYBACK_CONTEXT_UNAVAILABLE",
   *   "sessionId": "...",
   *   "reason": "..."
   * }
   * </pre>
   */
  static String buildUnavailableJson(String sessionId, String reason)
  {
    StringBuilder sb = new StringBuilder(128);
    sb.append("{\"type\":\"NG_PLAYBACK_CONTEXT_UNAVAILABLE\",\"sessionId\":\"");
    appendEscaped(sb, sessionId);
    sb.append("\",\"reason\":\"");
    appendEscaped(sb, reason);
    sb.append("\"}");
    return sb.toString();
  }

  /**
   * Build the delta event JSON for future SSE streaming.
   * Shape:
   * <pre>
   * {
   *   "type": "NG_PLAYBACK_CONTEXT_DELTA",
   *   "sessionId": "...",
   *   "delta": { ... }
   * }
   * </pre>
   */
  public static String buildDeltaEventJson(String sessionId, String deltaJson)
  {
    StringBuilder sb = new StringBuilder(deltaJson.length() + 80);
    sb.append("{\"type\":\"NG_PLAYBACK_CONTEXT_DELTA\",\"sessionId\":\"");
    appendEscaped(sb, sessionId);
    sb.append("\",\"delta\":");
    sb.append(deltaJson);
    sb.append('}');
    return sb.toString();
  }

  /**
   * Build the session-closed event JSON for future SSE streaming.
   */
  public static String buildSessionClosedEventJson(String sessionId)
  {
    return buildUnavailableJson(sessionId, "session_closed");
  }

  private static void appendEscaped(StringBuilder sb, String value)
  {
    if (value == null) return;
    for (int i = 0; i < value.length(); i++)
    {
      char c = value.charAt(i);
      switch (c)
      {
        case '"': sb.append("\\\""); break;
        case '\\': sb.append("\\\\"); break;
        case '\n': sb.append("\\n"); break;
        case '\r': sb.append("\\r"); break;
        case '\t': sb.append("\\t"); break;
        default: sb.append(c);
      }
    }
  }

  /**
   * Extract a named parameter from a simple query string (key=value&key2=value2).
   * Returns null if not found or queryString is null/empty.
   * Does not perform URL decoding (client names are expected to be simple ASCII).
   */
  static String getQueryParam(String queryString, String name)
  {
    if (queryString == null || queryString.length() == 0 || name == null) return null;
    String prefix = name + "=";
    int idx = queryString.indexOf(prefix);
    if (idx < 0) return null;
    int start = idx + prefix.length();
    int end = queryString.indexOf('&', start);
    return (end >= 0) ? queryString.substring(start, end) : queryString.substring(start);
  }
}
