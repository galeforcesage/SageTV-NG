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
 * Immutable live-window update payload — the subset of context that changes
 * frequently during live/active-file playback.
 * <p>
 * This is the "inner" content of a delta; see {@link NgPlaybackContextDelta}
 * for the full delta envelope.
 */
public final class NgLiveWindowUpdate
{
  private final boolean isLive;
  private final long recordingStartMs;
  private final long safeSeekStartMs;
  private final long safeSeekEndMs;
  private final long playableEndMs;
  private final long growthBytes;
  private final long lastSizeRefreshMs;

  public NgLiveWindowUpdate(boolean isLive, long recordingStartMs,
      long safeSeekStartMs, long safeSeekEndMs, long playableEndMs,
      long growthBytes, long lastSizeRefreshMs)
  {
    this.isLive = isLive;
    this.recordingStartMs = Math.max(0, recordingStartMs);
    this.safeSeekStartMs = Math.max(0, safeSeekStartMs);
    long clampedEnd = Math.max(0, safeSeekEndMs);
    this.safeSeekEndMs = Math.max(this.safeSeekStartMs, clampedEnd);
    this.playableEndMs = Math.max(0, playableEndMs);
    this.growthBytes = Math.max(0, growthBytes);
    this.lastSizeRefreshMs = Math.max(0, lastSizeRefreshMs);
  }

  public boolean isLive() { return isLive; }
  public long getRecordingStartMs() { return recordingStartMs; }
  public long getSafeSeekStartMs() { return safeSeekStartMs; }
  public long getSafeSeekEndMs() { return safeSeekEndMs; }
  public long getPlayableEndMs() { return playableEndMs; }
  public long getGrowthBytes() { return growthBytes; }
  public long getLastSizeRefreshMs() { return lastSizeRefreshMs; }

  /**
   * Check if this update is materially different from a previous one.
   * Suppresses no-op deltas when nothing meaningful changed.
   *
   * @param previous the last emitted update (null means no previous)
   * @param seekEndToleranceMs ignore safeSeekEnd changes smaller than this
   * @param growthBytesTolerance ignore growthBytes changes smaller than this
   * @return true if this update differs enough from previous to warrant emission
   */
  public boolean isMateriallyDifferent(NgLiveWindowUpdate previous,
      long seekEndToleranceMs, long growthBytesTolerance)
  {
    if (previous == null) return true;
    if (this.isLive != previous.isLive) return true;
    if (this.safeSeekStartMs != previous.safeSeekStartMs) return true;
    if (Math.abs(this.safeSeekEndMs - previous.safeSeekEndMs) >= seekEndToleranceMs) return true;
    if (Math.abs(this.playableEndMs - previous.playableEndMs) >= seekEndToleranceMs) return true;
    if (Math.abs(this.growthBytes - previous.growthBytes) >= growthBytesTolerance) return true;
    return false;
  }

  @Override
  public String toString()
  {
    return "NgLiveWindowUpdate{isLive=" + isLive +
        ", safeSeek=[" + safeSeekStartMs + "," + safeSeekEndMs + "]" +
        ", playableEnd=" + playableEndMs +
        ", growthBytes=" + growthBytes + '}';
  }
}
