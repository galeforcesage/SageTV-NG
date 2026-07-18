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

import java.util.UUID;

/**
 * Thin adapter wiring NgPlaybackContextProvider into MiniPlayer lifecycle.
 * <p>
 * This class isolates the NG context integration from MiniPlayer internals.
 * MiniPlayer holds one instance of this class and calls its lifecycle methods
 * at the documented call sites. All calls are exception-safe — errors are
 * caught and logged without ever propagating into playback code.
 * <p>
 * <b>I/O guarantee:</b> This class performs zero filesystem, network, or media
 * scanning. File size is supplied by the caller from already-known server state.
 * <p>
 * <b>Thread safety:</b> All methods are safe to call from any thread. The
 * underlying provider handles synchronization.
 * <p>
 * <b>Integration call sites in MiniPlayer:</b>
 * <ul>
 *   <li>{@code load()} — after currState = LOADED_STATE: call {@link #onPlaybackOpen}</li>
 *   <li>{@code seek()} — after seek completes: call {@link #onSeek}</li>
 *   <li>{@code free()} — at start of free(): call {@link #onPlaybackClose}</li>
 *   <li>Push loop — after numPushedBuffers++: call {@link #onPushLoopTick}</li>
 *   <li>{@code inactiveFile()} — call {@link #onInactiveFile}</li>
 * </ul>
 */
public final class NgPlaybackContextWiring
{
  /** Minimum interval between push-loop updates (ms). Avoids per-buffer overhead. */
  private static final long PUSH_LOOP_UPDATE_INTERVAL_MS = 2000;

  /** Minimum interval between pull-mode metadata updates (ms). */
  private static final long PULL_MODE_UPDATE_INTERVAL_MS = 3000;

  /** Minimum interval between file-size polls (ms). Rate-limits any external size supplier. */
  private static final long FILE_SIZE_REFRESH_INTERVAL_MS = 3000;

  private final NgPlaybackContextProvider provider;
  private volatile String sessionKey;
  private volatile String sessionId;
  private volatile boolean active;
  private long lastPushUpdateMs;
  private long lastPullUpdateMs;
  private long lastFileSizeRefreshMs;
  private long cachedFileSize;
  private long openGeneration;

  // --- Global singleton provider ---
  private static final NgPlaybackContextProvider GLOBAL_PROVIDER = new NgPlaybackContextProvider();

  /**
   * Returns the global singleton provider instance.
   * MiniPlayer uses this to construct its per-instance wiring.
   * Transport layers (HTTP, socket) also use this to register as listeners.
   */
  public static NgPlaybackContextProvider getGlobalProvider()
  {
    return GLOBAL_PROVIDER;
  }

  /**
   * Size supplier interface. Implementations may call File.length() internally
   * but MUST be rate-limited — this wiring will not call more often than
   * FILE_SIZE_REFRESH_INTERVAL_MS.
   */
  public interface FileSizeSupplier
  {
    /**
     * Return the current known file size in bytes, or 0 if unknown.
     * Implementations SHOULD return a cached value or already-known value.
     */
    long getFileSize();
  }

  private FileSizeSupplier fileSizeSupplier;

  /**
   * Create a wiring instance backed by the given provider.
   * @param provider the singleton NgPlaybackContextProvider
   */
  public NgPlaybackContextWiring(NgPlaybackContextProvider provider)
  {
    if (provider == null) throw new IllegalArgumentException("provider must not be null");
    this.provider = provider;
  }

  /**
   * Called when playback opens (after MiniPlayer.load() → currState = LOADED_STATE).
   * Generates a session UUID and opens the provider session.
   *
   * @param clientName       client identifier for session key (e.g. uiMgr.getLocalUIClientName())
   * @param mediaFileId      SageTV MediaFile ID
   * @param airingId         Airing ID, or -1 if unavailable
   * @param containerFormat  container string (e.g. "MPEG2-TS"), null if unknown
   * @param durationMs       known duration, 0 if live/unknown
   * @param timeshifted      true if file is actively being written
   * @param isLiveStream     true if live TV
   * @param serverSideTranscoding true if transcoding is active
   * @param recordingStartEpochMs epoch ms when recording started, 0 if unknown
   * @param initialFileSize  initial file size in bytes, 0 if unknown
   * @param sizeSupplier     optional file-size supplier for periodic refresh, null if not needed
   */
  public void onPlaybackOpen(String clientName, long mediaFileId, long airingId,
      String containerFormat, long durationMs, boolean timeshifted, boolean isLiveStream,
      boolean serverSideTranscoding, long recordingStartEpochMs,
      long initialFileSize, FileSizeSupplier sizeSupplier)
  {
    try
    {
      this.openGeneration++;
      this.sessionId = UUID.randomUUID().toString();
      this.sessionKey = buildSessionKey(clientName, mediaFileId, openGeneration);
      this.fileSizeSupplier = sizeSupplier;
      this.cachedFileSize = initialFileSize;
      this.lastPushUpdateMs = 0;
      this.lastPullUpdateMs = 0;
      this.lastFileSizeRefreshMs = System.currentTimeMillis();
      this.active = true;

      provider.openSession(sessionKey, clientName, sessionId, mediaFileId, airingId,
          containerFormat, durationMs, timeshifted, isLiveStream,
          serverSideTranscoding, recordingStartEpochMs);
    }
    catch (Exception e)
    {
      System.err.println("NgPlaybackContextWiring.onPlaybackOpen: " + e);
      active = false;
    }
  }

  /**
   * Called in the push loop after each buffer push (at numPushedBuffers++).
   * Rate-limited to PUSH_LOOP_UPDATE_INTERVAL_MS to avoid per-buffer overhead.
   *
   * @param serverMediaTimeMs current server media time from the parser/source
   * @param knownFileSize     already-cached file size from MiniPlayer (e.g. finalLength), 0 if unknown
   * @param timeshifted       true if file is still active
   */
  public void onPushLoopTick(long serverMediaTimeMs, long knownFileSize, boolean timeshifted)
  {
    if (!active) return;
    try
    {
      long nowMs = System.currentTimeMillis();
      if (nowMs - lastPushUpdateMs < PUSH_LOOP_UPDATE_INTERVAL_MS) return;
      lastPushUpdateMs = nowMs;

      long fileSize = resolveFileSize(knownFileSize, timeshifted, nowMs);

      provider.updateSessionState(sessionKey, serverMediaTimeMs, fileSize, nowMs);
      provider.computeDeltaIfChanged(sessionKey, nowMs);
    }
    catch (Exception e)
    {
      // Never propagate into push loop
      if (Boolean.getBoolean("sage.ng.debug"))
        System.err.println("NgPlaybackContextWiring.onPushLoopTick: " + e);
    }
  }

  /**
   * Called during pull-mode playback when the server reports or queries
   * the current media time. Rate-limited to PULL_MODE_UPDATE_INTERVAL_MS
   * so it adds negligible overhead to the getMediaTimeMillis() path.
   * <p>
   * This is the pull-mode equivalent of {@link #onPushLoopTick}: it feeds
   * the NG context with the latest server-known media time and file size
   * so that live-window values are populated for pull-mode clients (PWA).
   *
   * @param serverMediaTimeMs current server/client media time in ms
   * @param knownFileSize     already-cached file size from MiniPlayer (e.g. finalLength), 0 if unknown
   * @param timeshifted       true if file is still active (live/recording)
   */
  public void onPullModeTick(long serverMediaTimeMs, long knownFileSize, boolean timeshifted)
  {
    if (!active) return;
    try
    {
      long nowMs = System.currentTimeMillis();
      if (nowMs - lastPullUpdateMs < PULL_MODE_UPDATE_INTERVAL_MS) return;
      lastPullUpdateMs = nowMs;

      long fileSize = resolveFileSize(knownFileSize, timeshifted, nowMs);

      provider.updateSessionState(sessionKey, serverMediaTimeMs, fileSize, nowMs);
      provider.computeDeltaIfChanged(sessionKey, nowMs);
    }
    catch (Exception e)
    {
      // Never propagate into media time query path
      if (Boolean.getBoolean("sage.ng.debug"))
        System.err.println("NgPlaybackContextWiring.onPullModeTick: " + e);
    }
  }

  /**
   * Called after seek completes in MiniPlayer.seek().
   * @param seekTimeMs the target seek time
   */
  public void onSeek(long seekTimeMs)
  {
    if (!active) return;
    try
    {
      long nowMs = System.currentTimeMillis();
      provider.notifySeek(sessionKey, seekTimeMs, nowMs);
    }
    catch (Exception e)
    {
      System.err.println("NgPlaybackContextWiring.onSeek: " + e);
    }
  }

  /**
   * Called when the push decoder is flushed (flushPush0 at seek/rate-change boundaries).
   */
  public void onFlush()
  {
    if (!active) return;
    try
    {
      long nowMs = System.currentTimeMillis();
      provider.notifyFlush(sessionKey, nowMs);
    }
    catch (Exception e)
    {
      System.err.println("NgPlaybackContextWiring.onFlush: " + e);
    }
  }

  /**
   * Called when the file transitions from active/live to inactive (recording stops).
   * This is an epoch change — the live window is no longer growing.
   */
  public void onInactiveFile(long mediaFileId, long airingId, long finalFileSize)
  {
    if (!active) return;
    try
    {
      this.cachedFileSize = finalFileSize;
      long nowMs = System.currentTimeMillis();
      // Epoch change signals the live window is now frozen
      provider.notifyEpochChange(sessionKey, mediaFileId, airingId, 0, nowMs);
    }
    catch (Exception e)
    {
      System.err.println("NgPlaybackContextWiring.onInactiveFile: " + e);
    }
  }

  /**
   * Called when a media file/channel change happens within the same client session.
   */
  public void onEpochChange(long newMediaFileId, long newAiringId, long recordingStartEpochMs)
  {
    if (!active) return;
    try
    {
      long nowMs = System.currentTimeMillis();
      provider.notifyEpochChange(sessionKey, newMediaFileId, newAiringId, recordingStartEpochMs, nowMs);
    }
    catch (Exception e)
    {
      System.err.println("NgPlaybackContextWiring.onEpochChange: " + e);
    }
  }

  /**
   * Called at the start of MiniPlayer.free(). Closes the provider session.
   */
  public void onPlaybackClose()
  {
    if (!active) return;
    try
    {
      active = false;
      long nowMs = System.currentTimeMillis();
      provider.closeSession(sessionKey, nowMs);
    }
    catch (Exception e)
    {
      System.err.println("NgPlaybackContextWiring.onPlaybackClose: " + e);
    }
    finally
    {
      sessionKey = null;
      sessionId = null;
      fileSizeSupplier = null;
    }
  }

  /**
   * Returns the current session key, or null if not active.
   */
  public String getSessionKey()
  {
    return sessionKey;
  }

  /**
   * Returns true if this wiring has an active provider session.
   */
  public boolean isActive()
  {
    return active;
  }

  /**
   * Returns the session UUID generated at playback open.
   */
  public String getSessionId()
  {
    return sessionId;
  }

  // --- Internal helpers ---

  /**
   * Build a stable session key from client name, media file ID, and open generation.
   * The generation value ensures reopening the same file produces a distinct key,
   * preventing stale provider state or listener subscriptions from leaking.
   */
  static String buildSessionKey(String clientName, long mediaFileId, long generation)
  {
    String name = (clientName != null && clientName.length() > 0) ? clientName : "local";
    return name + ":" + mediaFileId + ":" + generation;
  }

  /**
   * Resolve the file size to use for this update cycle.
   * Prefers the already-known value passed in; falls back to rate-limited supplier.
   */
  private long resolveFileSize(long knownFileSize, boolean timeshifted, long nowMs)
  {
    // For non-active files, the size doesn't change
    if (!timeshifted) return knownFileSize > 0 ? knownFileSize : cachedFileSize;

    // If caller supplied a known size, use it
    if (knownFileSize > 0)
    {
      cachedFileSize = knownFileSize;
      return knownFileSize;
    }

    // Fall back to rate-limited supplier
    if (fileSizeSupplier != null && (nowMs - lastFileSizeRefreshMs >= FILE_SIZE_REFRESH_INTERVAL_MS))
    {
      lastFileSizeRefreshMs = nowMs;
      long freshSize = fileSizeSupplier.getFileSize();
      if (freshSize > 0) cachedFileSize = freshSize;
    }
    return cachedFileSize;
  }
}
