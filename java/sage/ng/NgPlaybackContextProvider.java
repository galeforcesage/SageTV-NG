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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central server-side provider for NG playback context and live-window deltas.
 * <p>
 * Manages per-session state (context + calculator) and dispatches delta updates
 * to registered {@link NgPlaybackContextListener}s. This is the single integration
 * point for future transport layers (HTTP, socket, PWA bridge) — they subscribe
 * as listeners and receive deltas without touching playback internals.
 * <p>
 * <b>Thread safety:</b> All public methods are safe to call from any thread.
 * Internal per-session state is synchronized per session key. Listener dispatch
 * happens outside any lock to avoid deadlocks with playback code.
 * <p>
 * <b>I/O guarantee:</b> This class performs zero filesystem, network, or media
 * scanning operations. File size and server media time are injected by the caller
 * (typically the push loop or a rate-limited size supplier).
 * <p>
 * <b>Future call sites (not wired in this phase):</b>
 * <ul>
 *   <li>{@code MiniPlayer.load()} → {@code provider.openSession(...)}</li>
 *   <li>{@code MiniPlayer} push loop → {@code provider.updateSessionState(...)}</li>
 *   <li>{@code MiniPlayer.seek()} → {@code provider.notifySeek(...)}</li>
 *   <li>{@code MiniPlayer.free()} → {@code provider.closeSession(...)}</li>
 * </ul>
 */
public final class NgPlaybackContextProvider
{
  /**
   * Per-session state holder. Package-private for testability.
   */
  static final class SessionState
  {
    final String sessionKey;
    final String clientName;
    final NgLiveWindowCalculator calculator;
    final NgPlaybackContextBuilder.PlaybackSnapshot snapshot;
    volatile NgPlaybackContext lastContext;

    SessionState(String sessionKey, String clientName, String sessionId, long mediaFileId, long airingId)
    {
      this.sessionKey = sessionKey;
      this.clientName = clientName;
      this.calculator = new NgLiveWindowCalculator(sessionId, mediaFileId, airingId);
      this.snapshot = new NgPlaybackContextBuilder.PlaybackSnapshot();
      this.snapshot.sessionId = sessionId;
      this.snapshot.mediaFileId = mediaFileId;
      this.snapshot.airingId = airingId;
    }
  }

  private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, String> sessionIdIndex = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, String> clientNameIndex = new ConcurrentHashMap<>();
  private final CopyOnWriteArrayList<NgPlaybackContextListener> listeners = new CopyOnWriteArrayList<>();

  /**
   * Register a listener for delta updates. Duplicate registrations are safe
   * (the same instance will only be added once).
   */
  public void addListener(NgPlaybackContextListener listener)
  {
    if (listener != null && !listeners.contains(listener))
    {
      listeners.add(listener);
    }
  }

  /**
   * Remove a previously registered listener.
   */
  public void removeListener(NgPlaybackContextListener listener)
  {
    if (listener != null)
    {
      listeners.remove(listener);
    }
  }

  /**
   * Open a new playback session or reset an existing one.
   * Builds an initial {@link NgPlaybackContext} and prepares the live-window
   * calculator if the media is live/timeshifted.
   *
   * @param sessionKey          opaque key identifying this session (e.g., client name + socket)
   * @param clientName          client identity (e.g., uiMgr.getLocalUIClientName())
   * @param sessionId           UUID for the NG context
   * @param mediaFileId         SageTV MediaFile ID (-1 if unknown)
   * @param airingId            SageTV Airing ID (-1 if unknown)
   * @param containerFormat     container string (e.g., "MPEG2-TS")
   * @param durationMs          known duration (0 if live/unknown)
   * @param timeshifted         true if file is actively being written
   * @param isLiveStream        true if this is live TV
   * @param serverSideTranscoding true if server is transcoding
   * @param recordingStartEpochMs epoch ms when recording started (0 if unknown)
   */
  public void openSession(String sessionKey, String clientName, String sessionId,
      long mediaFileId, long airingId, String containerFormat, long durationMs,
      boolean timeshifted, boolean isLiveStream, boolean serverSideTranscoding,
      long recordingStartEpochMs)
  {
    if (sessionKey == null) return;

    SessionState state = new SessionState(sessionKey, clientName, sessionId, mediaFileId, airingId);
    state.snapshot.containerFormat = containerFormat;
    state.snapshot.durationMs = durationMs;
    state.snapshot.timeshifted = timeshifted;
    state.snapshot.isLiveStream = isLiveStream;
    state.snapshot.serverSideTranscoding = serverSideTranscoding;
    state.snapshot.recordingStartEpochMs = recordingStartEpochMs;

    // Open the live-window calculator (always, even for non-live — it's a no-op if not used)
    state.calculator.open(recordingStartEpochMs);
    state.snapshot.streamEpoch = state.calculator.getStreamEpoch();

    // Build initial context
    state.lastContext = NgPlaybackContextBuilder.build(state.snapshot);

    sessions.put(sessionKey, state);
    if (sessionId != null)
    {
      sessionIdIndex.put(sessionId, sessionKey);
    }
    if (clientName != null && clientName.length() > 0)
    {
      clientNameIndex.put(clientName, sessionKey);
    }
  }

  /**
   * Returns the current {@link NgPlaybackContext} for a session, or null if
   * the session doesn't exist or has been closed.
   */
  public NgPlaybackContext getCurrentContext(String sessionKey)
  {
    if (sessionKey == null) return null;
    SessionState state = sessions.get(sessionKey);
    return (state != null) ? state.lastContext : null;
  }

  /**
   * Update the session's live state from already-known server values.
   * <p>
   * <b>No I/O:</b> The caller must provide these values from existing server
   * state. Do not call {@code File.length()} or any filesystem API here.
   * Use a rate-limited size supplier if fresh file size is needed.
   *
   * @param sessionKey        the session to update
   * @param serverMediaTimeMs current server media time in ms
   * @param fileSizeBytes     current known file size (0 if unknown or not cheap)
   * @param nowMs             System.currentTimeMillis() at time of call
   */
  public void updateSessionState(String sessionKey, long serverMediaTimeMs,
      long fileSizeBytes, long nowMs)
  {
    if (sessionKey == null) return;
    SessionState state = sessions.get(sessionKey);
    if (state == null) return;

    synchronized (state)
    {
      state.snapshot.serverMediaTimeMs = serverMediaTimeMs;
      if (fileSizeBytes > 0)
      {
        state.snapshot.fileSizeBytes = fileSizeBytes;
        state.snapshot.fileSizeRefreshTimeMs = nowMs;
      }
      state.calculator.updateServerState(serverMediaTimeMs, fileSizeBytes, nowMs);
      state.snapshot.streamEpoch = state.calculator.getStreamEpoch();
      // Rebuild immutable context so getCurrentContext() returns fresh values
      state.lastContext = NgPlaybackContextBuilder.build(state.snapshot);
    }
  }

  /**
   * Compute a live-window delta if the session's state has materially changed.
   * Returns null if suppressed (no material change, rate-limited, or session unknown).
   * <p>
   * If a non-null delta is produced, it is dispatched to all registered listeners.
   *
   * @param sessionKey the session to check
   * @param nowMs      System.currentTimeMillis()
   * @return the delta, or null if suppressed/unknown
   */
  public NgPlaybackContextDelta computeDeltaIfChanged(String sessionKey, long nowMs)
  {
    if (sessionKey == null) return null;
    SessionState state = sessions.get(sessionKey);
    if (state == null) return null;

    NgPlaybackContextDelta delta;
    synchronized (state)
    {
      delta = state.calculator.computeDeltaIfChanged(nowMs);
    }

    if (delta != null)
    {
      dispatchDelta(sessionKey, delta);
    }
    return delta;
  }

  /**
   * Notify that a seek was processed. Forces the next delta emission with reason=seek.
   */
  public void notifySeek(String sessionKey, long newMediaTimeMs, long nowMs)
  {
    if (sessionKey == null) return;
    SessionState state = sessions.get(sessionKey);
    if (state == null) return;

    NgPlaybackContextDelta delta;
    synchronized (state)
    {
      state.snapshot.serverMediaTimeMs = newMediaTimeMs;
      state.calculator.notifySeek(newMediaTimeMs);
      state.lastContext = NgPlaybackContextBuilder.build(state.snapshot);
      delta = state.calculator.computeDeltaIfChanged(nowMs);
    }

    if (delta != null)
    {
      dispatchDelta(sessionKey, delta);
    }
  }

  /**
   * Notify that a flush was processed. Forces the next delta emission with reason=flush.
   */
  public void notifyFlush(String sessionKey, long nowMs)
  {
    if (sessionKey == null) return;
    SessionState state = sessions.get(sessionKey);
    if (state == null) return;

    NgPlaybackContextDelta delta;
    synchronized (state)
    {
      state.calculator.notifyFlush();
      state.lastContext = NgPlaybackContextBuilder.build(state.snapshot);
      delta = state.calculator.computeDeltaIfChanged(nowMs);
    }

    if (delta != null)
    {
      dispatchDelta(sessionKey, delta);
    }
  }

  /**
   * Notify that the stream epoch changed (e.g., channel change, new file loaded
   * within the same session). Increments the stream epoch and forces an
   * epoch_change delta.
   */
  public void notifyEpochChange(String sessionKey, long newMediaFileId,
      long newAiringId, long recordingStartEpochMs, long nowMs)
  {
    if (sessionKey == null) return;
    SessionState state = sessions.get(sessionKey);
    if (state == null) return;

    NgPlaybackContextDelta delta;
    synchronized (state)
    {
      state.snapshot.mediaFileId = newMediaFileId;
      state.snapshot.airingId = newAiringId;
      state.snapshot.recordingStartEpochMs = recordingStartEpochMs;
      state.snapshot.serverMediaTimeMs = 0;
      state.snapshot.fileSizeBytes = 0;
      state.calculator.notifyEpochChange(newMediaFileId, newAiringId, recordingStartEpochMs);
      state.snapshot.streamEpoch = state.calculator.getStreamEpoch();
      state.lastContext = NgPlaybackContextBuilder.build(state.snapshot);
      delta = state.calculator.computeDeltaIfChanged(nowMs);
    }

    if (delta != null)
    {
      dispatchDelta(sessionKey, delta);
    }
  }

  /**
   * Close a playback session. Emits a session_close delta to listeners,
   * then removes all session state. No stale context remains accessible.
   *
   * @return the closing delta, or null if the session didn't exist
   */
  public NgPlaybackContextDelta closeSession(String sessionKey, long nowMs)
  {
    if (sessionKey == null) return null;
    SessionState state = sessions.remove(sessionKey);
    if (state == null) return null;

    // Remove sessionId from reverse index
    if (state.snapshot.sessionId != null)
    {
      sessionIdIndex.remove(state.snapshot.sessionId);
    }

    // Remove clientName from reverse index
    if (state.clientName != null && state.clientName.length() > 0)
    {
      clientNameIndex.remove(state.clientName, sessionKey);
    }

    NgPlaybackContextDelta closeDelta;
    synchronized (state)
    {
      closeDelta = state.calculator.close();
      state.lastContext = null;
    }

    // Dispatch to listeners — both onDelta (if non-null) and onSessionClosed
    dispatchSessionClosed(sessionKey, closeDelta);
    return closeDelta;
  }

  /**
   * Returns true if a session with the given key is currently open.
   */
  public boolean hasSession(String sessionKey)
  {
    return sessionKey != null && sessions.containsKey(sessionKey);
  }

  /**
   * Returns the number of active sessions. Useful for diagnostics.
   */
  public int getActiveSessionCount()
  {
    return sessions.size();
  }

  /**
   * Returns an unmodifiable snapshot of all active session keys.
   * The returned set is a copy — additions/removals after this call are not reflected.
   */
  public Set<String> getActiveSessionKeys()
  {
    return java.util.Collections.unmodifiableSet(
        new java.util.HashSet<>(sessions.keySet()));
  }

  /**
   * Resolve a client-facing sessionId (UUID) to the internal session key.
   * Returns null if the sessionId is unknown or the session has been closed.
   */
  public String resolveSessionId(String sessionId)
  {
    if (sessionId == null) return null;
    return sessionIdIndex.get(sessionId);
  }

  /**
   * Returns true if a session with the given client-facing sessionId exists.
   */
  public boolean hasSessionId(String sessionId)
  {
    return sessionId != null && sessionIdIndex.containsKey(sessionId);
  }

  /**
   * Returns the current context for a session identified by client-facing sessionId.
   * Returns null if unknown or closed.
   */
  public NgPlaybackContext getContextBySessionId(String sessionId)
  {
    String key = resolveSessionId(sessionId);
    return (key != null) ? getCurrentContext(key) : null;
  }

  // =========================================================================
  // Client name lookup (for PWA bridge/proxy)
  // =========================================================================

  /**
   * Resolve a client name to the internal session key of its current active session.
   * Returns null if no active session exists for that client.
   */
  public String resolveClientName(String clientName)
  {
    if (clientName == null || clientName.length() == 0) return null;
    String key = clientNameIndex.get(clientName);
    // Validate the key still maps to a live session
    if (key != null && sessions.containsKey(key)) return key;
    // Stale entry — remove it
    if (key != null) clientNameIndex.remove(clientName, key);
    return null;
  }

  /**
   * Returns true if a client with the given name has an active session.
   */
  public boolean hasClientName(String clientName)
  {
    return resolveClientName(clientName) != null;
  }

  /**
   * Returns the current context for the active session of the given client.
   * Returns null if unknown or closed.
   */
  public NgPlaybackContext getContextByClientName(String clientName)
  {
    String key = resolveClientName(clientName);
    return (key != null) ? getCurrentContext(key) : null;
  }

  /**
   * Returns the opaque client-facing sessionId for the current active session
   * of the given client. Returns null if no active session.
   */
  public String getSessionIdByClientName(String clientName)
  {
    String key = resolveClientName(clientName);
    if (key == null) return null;
    SessionState state = sessions.get(key);
    return (state != null) ? state.snapshot.sessionId : null;
  }

  // --- Listener dispatch (always outside locks) ---

  private void dispatchDelta(String sessionKey, NgPlaybackContextDelta delta)
  {
    for (NgPlaybackContextListener listener : listeners)
    {
      try
      {
        listener.onDelta(sessionKey, delta);
      }
      catch (Exception e)
      {
        // Log and continue — listener failures must never affect playback
        System.err.println("NgPlaybackContextProvider: listener threw on delta for " +
            sessionKey + ": " + e);
      }
    }
  }

  private void dispatchSessionClosed(String sessionKey, NgPlaybackContextDelta closeDelta)
  {
    for (NgPlaybackContextListener listener : listeners)
    {
      try
      {
        listener.onSessionClosed(sessionKey, closeDelta);
      }
      catch (Exception e)
      {
        System.err.println("NgPlaybackContextProvider: listener threw on close for " +
            sessionKey + ": " + e);
      }
    }
  }
}
