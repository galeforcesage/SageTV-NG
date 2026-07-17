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
 * Immutable delta envelope for NG live-window updates.
 * <p>
 * This is a lightweight, partial update that avoids retransmitting the full
 * {@link NgPlaybackContext}. Only the live window and server media time change
 * during active playback — skip/index/flow/seek-policy are stable after initial context.
 * <p>
 * JSON shape:
 * <pre>
 * {
 *   "version": 1,
 *   "sessionId": "uuid",
 *   "mediaFileId": 123456,
 *   "airingId": 789,
 *   "streamEpoch": 1,
 *   "type": "NG_LIVE_WINDOW_UPDATE",
 *   "reason": "wall_clock",
 *   "serverMediaTimeMs": 1840000,
 *   "live": { ... }
 * }
 * </pre>
 */
public final class NgPlaybackContextDelta
{
  public static final int CURRENT_VERSION = 1;
  public static final String TYPE_LIVE_WINDOW_UPDATE = "NG_LIVE_WINDOW_UPDATE";

  private final int version;
  private final String sessionId;
  private final long mediaFileId;
  private final long airingId;
  private final int streamEpoch;
  private final String type;
  private final String reason;
  private final long serverMediaTimeMs;
  private final NgLiveWindowUpdate live;

  public NgPlaybackContextDelta(String sessionId, long mediaFileId, long airingId,
      int streamEpoch, String reason, long serverMediaTimeMs, NgLiveWindowUpdate live)
  {
    this.version = CURRENT_VERSION;
    this.sessionId = (sessionId != null) ? sessionId : "";
    this.mediaFileId = mediaFileId;
    this.airingId = airingId;
    this.streamEpoch = Math.max(0, streamEpoch);
    this.type = TYPE_LIVE_WINDOW_UPDATE;
    this.reason = (reason != null) ? reason : NgDeltaReason.WALL_CLOCK;
    this.serverMediaTimeMs = Math.max(0, serverMediaTimeMs);
    this.live = (live != null) ? live : new NgLiveWindowUpdate(false, 0, 0, 0, 0, 0, 0);
  }

  public int getVersion() { return version; }
  public String getSessionId() { return sessionId; }
  public long getMediaFileId() { return mediaFileId; }
  public long getAiringId() { return airingId; }
  public int getStreamEpoch() { return streamEpoch; }
  public String getType() { return type; }
  public String getReason() { return reason; }
  public long getServerMediaTimeMs() { return serverMediaTimeMs; }
  public NgLiveWindowUpdate getLive() { return live; }

  @Override
  public String toString()
  {
    return "NgPlaybackContextDelta{session=" + sessionId +
        ", epoch=" + streamEpoch +
        ", reason=" + reason +
        ", serverTime=" + serverMediaTimeMs +
        ", live=" + live + '}';
  }
}
