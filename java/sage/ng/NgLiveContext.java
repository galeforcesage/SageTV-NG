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
 * Live/timeshift window state for an active recording or live stream.
 * For non-live content, use {@link #EMPTY}.
 */
public final class NgLiveContext
{
  public static final NgLiveContext EMPTY = new NgLiveContext(false, 0, 0, 0, 0, 0, 0);

  private final boolean isLive;
  private final long recordingStartMs;
  private final long safeSeekStartMs;
  private final long safeSeekEndMs;
  private final long playableEndMs;
  private final long growthBytes;
  private final long lastSizeRefreshMs;

  public NgLiveContext(boolean isLive, long recordingStartMs,
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

  @Override
  public String toString()
  {
    return "NgLiveContext{isLive=" + isLive + ", safeSeek=[" + safeSeekStartMs +
        "," + safeSeekEndMs + "], growthBytes=" + growthBytes + '}';
  }
}
