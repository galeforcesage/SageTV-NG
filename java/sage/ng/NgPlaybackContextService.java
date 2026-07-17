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
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Public service API for the NG Playback Context subsystem.
 * <p>
 * This is the single entry point for server plugins, HTTP endpoints, PWA bridge
 * code, or any future transport layer that needs to:
 * <ul>
 *   <li>Query current playback context for a session</li>
 *   <li>Query context as pre-serialized JSON (no parsing/building cost for callers)</li>
 *   <li>List active sessions</li>
 *   <li>Subscribe/unsubscribe for live-window delta updates</li>
 *   <li>Retrieve diagnostics</li>
 * </ul>
 * <p>
 * <b>Thread safety:</b> All methods are safe to call from any thread.
 * <p>
 * <b>Transport agnostic:</b> This class does not implement HTTP, WebSocket, SSE,
 * or any wire protocol. Transport layers are listeners that receive callbacks and
 * push data in their own format.
 * <p>
 * <b>Usage example for a future HTTP endpoint:</b>
 * <pre>{@code
 *   NgPlaybackContextService svc = NgPlaybackContextService.getInstance();
 *
 *   // GET /ng/context/{sessionKey}
 *   String json = svc.getContextJson(sessionKey);
 *   if (json == null) return 404;
 *   return 200, json;
 *
 *   // Subscribe to deltas (e.g., for SSE push)
 *   svc.addListener(myTransportListener);
 * }</pre>
 * <p>
 * <b>Usage example for a session-specific subscription:</b>
 * <pre>{@code
 *   svc.addSessionListener(sessionKey, myPerSessionListener);
 *   // listener receives only deltas for that session
 *   svc.removeSessionListener(sessionKey, myPerSessionListener);
 * }</pre>
 */
public final class NgPlaybackContextService
{
  private static final NgPlaybackContextService INSTANCE = new NgPlaybackContextService();

  private final NgPlaybackContextProvider provider;

  private NgPlaybackContextService()
  {
    this.provider = NgPlaybackContextWiring.getGlobalProvider();
  }

  /**
   * Returns the singleton service instance.
   */
  public static NgPlaybackContextService getInstance()
  {
    return INSTANCE;
  }

  // =========================================================================
  // Query API
  // =========================================================================

  /**
   * Get the current playback context object for a session.
   *
   * @param sessionKey internal session key (from NgPlaybackContextWiring)
   * @return the context, or null if the session does not exist or is closed
   */
  public NgPlaybackContext getContext(String sessionKey)
  {
    return provider.getCurrentContext(sessionKey);
  }

  /**
   * Get the current playback context as a JSON string.
   * Returns null if the session does not exist.
   * The JSON is freshly serialized on each call.
   *
   * @param sessionKey internal session key
   * @return JSON string, or null if no active session
   */
  public String getContextJson(String sessionKey)
  {
    NgPlaybackContext ctx = provider.getCurrentContext(sessionKey);
    if (ctx == null) return null;
    return NgPlaybackContextSerializer.toJson(ctx);
  }

  /**
   * Check whether a session exists and has context available.
   *
   * @param sessionKey internal session key
   * @return true if the session is active and context is available
   */
  public boolean hasSession(String sessionKey)
  {
    return provider.hasSession(sessionKey);
  }

  /**
   * Get the set of all currently active session keys.
   * This is a snapshot — sessions may open/close after this call returns.
   *
   * @return unmodifiable set of active session keys (never null, may be empty)
   */
  public Set<String> getActiveSessionKeys()
  {
    return provider.getActiveSessionKeys();
  }

  /**
   * Get the number of active playback sessions.
   */
  public int getActiveSessionCount()
  {
    return provider.getActiveSessionCount();
  }

  // =========================================================================
  // Client-name-based Query API (for PWA bridge/proxy resolution)
  // =========================================================================

  /**
   * Get the opaque client-facing sessionId for the currently active session
   * of the given client. Returns null if no active session exists.
   * <p>
   * This is the primary entry point for the PWA bridge to discover the
   * sessionId without ever seeing the internal sessionKey.
   *
   * @param clientName the MiniClient identity (uiMgr.getLocalUIClientName())
   * @return opaque sessionId UUID, or null
   */
  public String getCurrentSessionIdForClientName(String clientName)
  {
    return provider.getSessionIdByClientName(clientName);
  }

  /**
   * Get the current playback context object for the given client's active session.
   *
   * @param clientName the MiniClient identity
   * @return the context, or null if no active session
   */
  public NgPlaybackContext getCurrentContextForClientName(String clientName)
  {
    return provider.getContextByClientName(clientName);
  }

  /**
   * Get the current playback context as JSON for the given client's active session.
   * The returned JSON does NOT contain internal sessionKey.
   *
   * @param clientName the MiniClient identity
   * @return JSON string, or null if no active session
   */
  public String getCurrentContextJsonForClientName(String clientName)
  {
    NgPlaybackContext ctx = provider.getContextByClientName(clientName);
    if (ctx == null) return null;
    return NgPlaybackContextSerializer.toJson(ctx);
  }

  /**
   * Check whether the given client identity has an active playback session.
   *
   * @param clientName the MiniClient identity
   * @return true if the client has an active session with context available
   */
  public boolean hasActiveSessionForClientName(String clientName)
  {
    return provider.hasClientName(clientName);
  }

  // =========================================================================
  // SessionId-based Query API (client-facing, transport-safe)
  // =========================================================================

  /**
   * Get the current playback context by client-facing sessionId (UUID).
   * This is the preferred API for HTTP/WebSocket/SSE endpoints.
   *
   * @param sessionId the opaque UUID from NgPlaybackContext.getSessionId()
   * @return the context, or null if unknown/closed
   */
  public NgPlaybackContext getContextBySessionId(String sessionId)
  {
    return provider.getContextBySessionId(sessionId);
  }

  /**
   * Get the current playback context as JSON by client-facing sessionId.
   * The returned JSON does NOT contain internal sessionKey.
   *
   * @param sessionId the opaque UUID
   * @return JSON string, or null if unknown/closed
   */
  public String getContextJsonBySessionId(String sessionId)
  {
    NgPlaybackContext ctx = provider.getContextBySessionId(sessionId);
    if (ctx == null) return null;
    return NgPlaybackContextSerializer.toJson(ctx);
  }

  /**
   * Check whether a session with the given client-facing sessionId exists.
   *
   * @param sessionId the opaque UUID
   * @return true if active
   */
  public boolean hasSessionId(String sessionId)
  {
    return provider.hasSessionId(sessionId);
  }

  /**
   * Register a per-session listener by client-facing sessionId.
   * The listener receives deltas only for the session matching this UUID.
   * Safe to call with unknown sessionId (listener is registered and will fire
   * if a session with that ID appears later via the same provider).
   *
   * @param sessionId the opaque UUID
   * @param listener  the listener (null is ignored)
   */
  public void addSessionIdListener(String sessionId, NgPlaybackContextListener listener)
  {
    if (sessionId == null || listener == null) return;
    provider.addListener(new SessionIdFilterListener(sessionId, listener, provider));
  }

  /**
   * Remove a per-session listener registered by sessionId.
   *
   * @param sessionId the opaque UUID
   * @param listener  the original listener instance
   */
  public void removeSessionIdListener(String sessionId, NgPlaybackContextListener listener)
  {
    if (sessionId == null || listener == null) return;
    provider.removeListener(new SessionIdFilterListener(sessionId, listener, provider));
  }

  /**
   * Get a snapshot of all active sessions with their current contexts.
   * Useful for dashboard/diagnostics or an "all sessions" REST endpoint.
   *
   * @return unmodifiable list of session info objects (never null)
   */
  public List<SessionInfo> getAllSessions()
  {
    Set<String> keys = provider.getActiveSessionKeys();
    if (keys.isEmpty()) return Collections.emptyList();

    List<SessionInfo> result = new ArrayList<>(keys.size());
    for (String key : keys)
    {
      NgPlaybackContext ctx = provider.getCurrentContext(key);
      if (ctx != null)
      {
        result.add(new SessionInfo(key, ctx));
      }
    }
    return Collections.unmodifiableList(result);
  }

  /**
   * Get all active sessions as a JSON array string.
   * Each element contains the full context (which includes sessionId).
   * Internal sessionKey is NOT included in this output.
   *
   * @return JSON array string (never null; "[]" if no sessions)
   */
  public String getAllSessionsJson()
  {
    Set<String> keys = provider.getActiveSessionKeys();
    if (keys.isEmpty()) return "[]";

    StringBuilder sb = new StringBuilder(512);
    sb.append('[');
    boolean first = true;
    for (String key : keys)
    {
      NgPlaybackContext ctx = provider.getCurrentContext(key);
      if (ctx != null)
      {
        if (!first) sb.append(',');
        first = false;
        sb.append(NgPlaybackContextSerializer.toJson(ctx));
      }
    }
    sb.append(']');
    return sb.toString();
  }

  // =========================================================================
  // Subscription API
  // =========================================================================

  /**
   * Register a global listener that receives deltas for ALL sessions.
   * Suitable for broadcast transports (e.g., a single SSE stream for all sessions).
   * Duplicate registrations are safe.
   *
   * @param listener the listener to register (null is ignored)
   */
  public void addListener(NgPlaybackContextListener listener)
  {
    provider.addListener(listener);
  }

  /**
   * Remove a previously registered global listener.
   *
   * @param listener the listener to remove (null is ignored)
   */
  public void removeListener(NgPlaybackContextListener listener)
  {
    provider.removeListener(listener);
  }

  /**
   * Register a per-session listener that receives deltas only for a specific session.
   * Useful for per-client subscriptions (e.g., one WebSocket per session).
   * If the session doesn't exist yet, the listener is registered and will receive
   * deltas once the session opens (if a matching key is used).
   *
   * @param sessionKey the session to subscribe to
   * @param listener   the listener (null is ignored)
   */
  public void addSessionListener(String sessionKey, NgPlaybackContextListener listener)
  {
    if (sessionKey == null || listener == null) return;
    provider.addListener(new SessionFilterListener(sessionKey, listener));
  }

  /**
   * Remove a per-session listener. Matches by the same sessionKey + listener pair.
   *
   * @param sessionKey the session the listener was registered for
   * @param listener   the original listener instance
   */
  public void removeSessionListener(String sessionKey, NgPlaybackContextListener listener)
  {
    if (sessionKey == null || listener == null) return;
    provider.removeListener(new SessionFilterListener(sessionKey, listener));
  }

  // =========================================================================
  // Diagnostics
  // =========================================================================

  /**
   * Return a diagnostic summary string suitable for logging or admin endpoints.
   */
  public String getDiagnostics()
  {
    Set<String> keys = provider.getActiveSessionKeys();
    StringBuilder sb = new StringBuilder();
    sb.append("NgPlaybackContextService: ").append(keys.size()).append(" active session(s)");
    for (String key : keys)
    {
      NgPlaybackContext ctx = provider.getCurrentContext(key);
      sb.append("\n  [").append(key).append("] ");
      if (ctx != null)
      {
        sb.append("mediaFileId=").append(ctx.getMediaFileId());
        sb.append(" mode=").append(ctx.getMode());
        sb.append(" container=").append(ctx.getContainer());
        sb.append(" durationMs=").append(ctx.getDurationMs());
      }
      else
      {
        sb.append("(context unavailable)");
      }
    }
    return sb.toString();
  }

  // =========================================================================
  // Supporting types
  // =========================================================================

  /**
   * Snapshot of a session's key and current context.
   * The internal sessionKey is available via {@link #getSessionKey()} for internal use,
   * but {@link #toJson()} does NOT include it — only the client-facing sessionId.
   */
  public static final class SessionInfo
  {
    private final String sessionKey;
    private final NgPlaybackContext context;

    SessionInfo(String sessionKey, NgPlaybackContext context)
    {
      this.sessionKey = sessionKey;
      this.context = context;
    }

    /** Internal session key — NOT for client exposure. */
    public String getSessionKey() { return sessionKey; }
    /** Client-facing session UUID. */
    public String getSessionId() { return context.getSessionId(); }
    public NgPlaybackContext getContext() { return context; }

    /**
     * Serialize this session info to JSON (client-safe).
     * Does NOT include internal sessionKey.
     */
    public String toJson()
    {
      return NgPlaybackContextSerializer.toJson(context);
    }
  }

  /**
   * Internal listener wrapper that filters deltas to a specific session key.
   * Uses sessionKey + delegate identity for equals/hashCode so that
   * removeSessionListener works correctly.
   */
  static final class SessionFilterListener implements NgPlaybackContextListener
  {
    private final String targetSessionKey;
    private final NgPlaybackContextListener delegate;

    SessionFilterListener(String targetSessionKey, NgPlaybackContextListener delegate)
    {
      this.targetSessionKey = targetSessionKey;
      this.delegate = delegate;
    }

    public void onDelta(String sessionKey, NgPlaybackContextDelta delta)
    {
      if (targetSessionKey.equals(sessionKey))
      {
        delegate.onDelta(sessionKey, delta);
      }
    }

    public void onSessionClosed(String sessionKey, NgPlaybackContextDelta closeDelta)
    {
      if (targetSessionKey.equals(sessionKey))
      {
        delegate.onSessionClosed(sessionKey, closeDelta);
      }
    }

    @Override
    public boolean equals(Object o)
    {
      if (this == o) return true;
      if (!(o instanceof SessionFilterListener)) return false;
      SessionFilterListener that = (SessionFilterListener) o;
      return targetSessionKey.equals(that.targetSessionKey) && delegate == that.delegate;
    }

    @Override
    public int hashCode()
    {
      return 31 * targetSessionKey.hashCode() + System.identityHashCode(delegate);
    }
  }

  /**
   * Listener wrapper that filters by client-facing sessionId (UUID).
   * Resolves sessionId → sessionKey at dispatch time via the provider's reverse index.
   */
  static final class SessionIdFilterListener implements NgPlaybackContextListener
  {
    private final String targetSessionId;
    private final NgPlaybackContextListener delegate;
    private final NgPlaybackContextProvider provider;

    SessionIdFilterListener(String targetSessionId, NgPlaybackContextListener delegate,
        NgPlaybackContextProvider provider)
    {
      this.targetSessionId = targetSessionId;
      this.delegate = delegate;
      this.provider = provider;
    }

    public void onDelta(String sessionKey, NgPlaybackContextDelta delta)
    {
      // Check if this sessionKey corresponds to our target sessionId
      if (targetSessionId.equals(delta.getSessionId()))
      {
        delegate.onDelta(sessionKey, delta);
      }
    }

    public void onSessionClosed(String sessionKey, NgPlaybackContextDelta closeDelta)
    {
      if (closeDelta != null && targetSessionId.equals(closeDelta.getSessionId()))
      {
        delegate.onSessionClosed(sessionKey, closeDelta);
      }
    }

    @Override
    public boolean equals(Object o)
    {
      if (this == o) return true;
      if (!(o instanceof SessionIdFilterListener)) return false;
      SessionIdFilterListener that = (SessionIdFilterListener) o;
      return targetSessionId.equals(that.targetSessionId) && delegate == that.delegate;
    }

    @Override
    public int hashCode()
    {
      return 31 * targetSessionId.hashCode() + System.identityHashCode(delegate);
    }
  }

  // =========================================================================
  // Internal helpers
  // =========================================================================

  private static void appendJsonString(StringBuilder sb, String value)
  {
    if (value == null) { sb.append("null"); return; }
    sb.append('"');
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
    sb.append('"');
  }
}
