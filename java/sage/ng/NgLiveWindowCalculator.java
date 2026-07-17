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
 * Lightweight live-window calculator for NG delta generation.
 * <p>
 * This service holds the mutable state needed to compute and suppress
 * live-window deltas for one playback session. It is designed to be called
 * cheaply from existing playback code paths without any heavy I/O.
 * <p>
 * Thread safety: this class is NOT thread-safe. The caller must synchronize
 * externally (e.g., under MiniPlayer's decoderLock) or confine access to a
 * single thread (the push thread).
 * <p>
 * Usage:
 * <pre>
 *   NgLiveWindowCalculator calc = new NgLiveWindowCalculator(sessionId, mediaFileId, airingId);
 *   calc.open(recordingStartEpochMs);
 *
 *   // On each push cycle or wall-clock tick:
 *   calc.updateServerState(serverMediaTimeMs, fileSizeBytes, System.currentTimeMillis());
 *   NgPlaybackContextDelta delta = calc.computeDeltaIfChanged();
 *   if (delta != null) { // deliver to client }
 *
 *   // On seek:
 *   calc.notifySeek(newMediaTimeMs);
 *
 *   // On close:
 *   NgPlaybackContextDelta closeDelta = calc.close();
 * </pre>
 */
public final class NgLiveWindowCalculator
{
  // --- Suppression thresholds ---
  /** Minimum change in safeSeekEndMs to emit a delta */
  private static final long SEEK_END_TOLERANCE_MS = 1000;
  /** Minimum file growth (bytes) to emit a delta */
  private static final long GROWTH_BYTES_TOLERANCE = 65536; // 64KB
  /** Minimum wall-clock interval between emitted deltas (ms) */
  private static final long MIN_EMIT_INTERVAL_MS = 1000;

  // --- Conservative safety margin ---
  /** How far back from the true file end we advertise as safe to seek */
  private static final long SAFE_SEEK_TRAILING_MARGIN_MS = 5000;

  // --- Session identity (immutable after construction) ---
  private final String sessionId;
  private final long mediaFileId;
  private final long airingId;

  // --- Mutable state ---
  private int streamEpoch;
  private boolean isOpen;
  private long recordingStartEpochMs;

  // Latest server-provided values
  private long serverMediaTimeMs;
  private long fileSizeBytes;
  private long lastSizeRefreshMs;
  private long knownDurationMs;

  // Last emitted update (for suppression)
  private NgLiveWindowUpdate lastEmitted;
  private long lastEmitTimeMs;
  private String pendingReason;

  public NgLiveWindowCalculator(String sessionId, long mediaFileId, long airingId)
  {
    this.sessionId = (sessionId != null) ? sessionId : "";
    this.mediaFileId = mediaFileId;
    this.airingId = airingId;
    this.streamEpoch = 0;
    this.isOpen = false;
  }

  /**
   * Open/reset the calculator for a new playback stream.
   * Call when media is loaded and playback begins.
   *
   * @param recordingStartEpochMs epoch ms when recording started (0 if unknown)
   */
  public void open(long recordingStartEpochMs)
  {
    this.streamEpoch++;
    this.isOpen = true;
    this.recordingStartEpochMs = Math.max(0, recordingStartEpochMs);
    this.serverMediaTimeMs = 0;
    this.fileSizeBytes = 0;
    this.lastSizeRefreshMs = 0;
    this.knownDurationMs = 0;
    this.lastEmitted = null;
    this.lastEmitTimeMs = 0;
    this.pendingReason = NgDeltaReason.INITIAL;
  }

  /**
   * Update server state cheaply. Call from the push loop, wall-clock timer, or
   * anywhere the server already has these values handy.
   *
   * @param serverMediaTimeMs current server media time in ms
   * @param fileSizeBytes current file size (0 if unknown or not cheap to get)
   * @param nowMs System.currentTimeMillis() at time of call
   */
  public void updateServerState(long serverMediaTimeMs, long fileSizeBytes, long nowMs)
  {
    this.serverMediaTimeMs = Math.max(0, serverMediaTimeMs);
    if (fileSizeBytes > 0)
    {
      this.fileSizeBytes = fileSizeBytes;
      this.lastSizeRefreshMs = nowMs;
    }
  }

  /**
   * Set the known duration if the server has it (for recordings that haven't finished).
   */
  public void setKnownDuration(long durationMs)
  {
    this.knownDurationMs = Math.max(0, durationMs);
  }

  /**
   * Notify that a seek was processed. Forces next delta emission with reason=seek.
   */
  public void notifySeek(long newMediaTimeMs)
  {
    this.serverMediaTimeMs = Math.max(0, newMediaTimeMs);
    this.pendingReason = NgDeltaReason.SEEK;
  }

  /**
   * Notify that a flush was processed. Forces next delta emission with reason=flush.
   */
  public void notifyFlush()
  {
    this.pendingReason = NgDeltaReason.FLUSH;
  }

  /**
   * Compute a delta if the live window has materially changed since the last emission.
   * Returns null if no meaningful change occurred (suppressed).
   *
   * @param nowMs System.currentTimeMillis() at time of call
   * @return a delta to deliver to the client, or null if suppressed
   */
  public NgPlaybackContextDelta computeDeltaIfChanged(long nowMs)
  {
    if (!isOpen) return null;

    // Rate-limit: don't emit faster than MIN_EMIT_INTERVAL_MS unless forced
    boolean forced = (pendingReason != null &&
        !NgDeltaReason.WALL_CLOCK.equals(pendingReason));
    if (!forced && lastEmitTimeMs > 0 && (nowMs - lastEmitTimeMs) < MIN_EMIT_INTERVAL_MS)
      return null;

    NgLiveWindowUpdate update = computeCurrentWindow();

    // Determine reason
    String reason;
    if (pendingReason != null)
    {
      reason = pendingReason;
      pendingReason = null;
    }
    else if (lastEmitted == null)
    {
      reason = NgDeltaReason.INITIAL;
    }
    else if (fileSizeBytes > 0 && lastEmitted.getGrowthBytes() > 0 &&
        (fileSizeBytes - lastEmitted.getGrowthBytes()) >= GROWTH_BYTES_TOLERANCE)
    {
      reason = NgDeltaReason.FILE_GROWTH;
    }
    else
    {
      reason = NgDeltaReason.WALL_CLOCK;
    }

    // Suppression check (unless forced by seek/flush/initial/epoch)
    if (!isForceReason(reason))
    {
      if (!update.isMateriallyDifferent(lastEmitted, SEEK_END_TOLERANCE_MS, GROWTH_BYTES_TOLERANCE))
        return null;
    }

    // Emit
    lastEmitted = update;
    lastEmitTimeMs = nowMs;

    return new NgPlaybackContextDelta(
        sessionId, mediaFileId, airingId,
        streamEpoch, reason, serverMediaTimeMs, update
    );
  }

  /**
   * Close the session and emit a final session_close delta.
   * After this, no more deltas will be emitted until {@link #open} is called again.
   */
  public NgPlaybackContextDelta close()
  {
    if (!isOpen) return null;
    isOpen = false;

    NgLiveWindowUpdate finalUpdate = new NgLiveWindowUpdate(
        false, recordingStartEpochMs, 0, 0, 0, fileSizeBytes, lastSizeRefreshMs
    );

    lastEmitted = null;
    return new NgPlaybackContextDelta(
        sessionId, mediaFileId, airingId,
        streamEpoch, NgDeltaReason.SESSION_CLOSE, serverMediaTimeMs, finalUpdate
    );
  }

  /**
   * Force an epoch change (e.g., channel change within same session).
   * Resets internal state and next delta will have reason=epoch_change.
   */
  public void notifyEpochChange(long newMediaFileId, long newAiringId, long recordingStartEpochMs)
  {
    this.streamEpoch++;
    this.recordingStartEpochMs = Math.max(0, recordingStartEpochMs);
    this.serverMediaTimeMs = 0;
    this.fileSizeBytes = 0;
    this.lastSizeRefreshMs = 0;
    this.knownDurationMs = 0;
    this.lastEmitted = null;
    this.pendingReason = NgDeltaReason.EPOCH_CHANGE;
  }

  /**
   * @return true if the calculator is in an open (active) state
   */
  public boolean isOpen() { return isOpen; }

  /**
   * @return current stream epoch (increments on open and epoch changes)
   */
  public int getStreamEpoch() { return streamEpoch; }

  // --- Private helpers ---

  private NgLiveWindowUpdate computeCurrentWindow()
  {
    long safeSeekStartMs = 0;

    // safeSeekEndMs: the furthest point we're confident the server can serve
    // Conservative: use serverMediaTimeMs minus a trailing margin
    long safeSeekEndMs;
    if (serverMediaTimeMs > SAFE_SEEK_TRAILING_MARGIN_MS)
      safeSeekEndMs = serverMediaTimeMs - SAFE_SEEK_TRAILING_MARGIN_MS;
    else if (serverMediaTimeMs > 0)
      safeSeekEndMs = serverMediaTimeMs;
    else if (knownDurationMs > 0)
      safeSeekEndMs = knownDurationMs;
    else
      safeSeekEndMs = 0;

    // playableEndMs: the actual media extent (slightly ahead of safe seek end)
    // This is what the player could reach if it was already buffered
    long playableEndMs;
    if (serverMediaTimeMs > 0)
      playableEndMs = serverMediaTimeMs;
    else if (knownDurationMs > 0)
      playableEndMs = knownDurationMs;
    else
      playableEndMs = safeSeekEndMs;

    return new NgLiveWindowUpdate(
        true,
        recordingStartEpochMs,
        safeSeekStartMs,
        safeSeekEndMs,
        playableEndMs,
        fileSizeBytes,
        lastSizeRefreshMs
    );
  }

  private static boolean isForceReason(String reason)
  {
    return NgDeltaReason.INITIAL.equals(reason) ||
        NgDeltaReason.SEEK.equals(reason) ||
        NgDeltaReason.FLUSH.equals(reason) ||
        NgDeltaReason.EPOCH_CHANGE.equals(reason) ||
        NgDeltaReason.SESSION_CLOSE.equals(reason);
  }
}
