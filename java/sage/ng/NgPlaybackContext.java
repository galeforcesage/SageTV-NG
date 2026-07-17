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
 * Top-level immutable NG Playback Context.
 * Carries all advisory metadata for an NG-capable client's playback session.
 * <p>
 * All sub-objects are guaranteed non-null — use static EMPTY/DEFAULT instances
 * for absent data.
 */
public final class NgPlaybackContext
{
  public static final int CURRENT_VERSION = 1;

  private final int version;
  private final String sessionId;
  private final long mediaFileId;
  private final long airingId;
  private final String mode;
  private final String container;
  private final long durationMs;
  private final long serverMediaTimeMs;
  private final NgLiveContext live;
  private final NgSeekPolicy seek;
  private final NgIndexContext index;
  private final NgSkipContext skip;
  private final NgFlowPolicy flow;

  public NgPlaybackContext(String sessionId, long mediaFileId, long airingId,
      String mode, String container, long durationMs, long serverMediaTimeMs,
      NgLiveContext live, NgSeekPolicy seek, NgIndexContext index,
      NgSkipContext skip, NgFlowPolicy flow)
  {
    this.version = CURRENT_VERSION;
    this.sessionId = (sessionId != null) ? sessionId : "";
    this.mediaFileId = mediaFileId;
    this.airingId = airingId;
    this.mode = (mode != null) ? mode : "unknown";
    this.container = (container != null) ? container : "unknown";
    this.durationMs = Math.max(0, durationMs);
    this.serverMediaTimeMs = Math.max(0, serverMediaTimeMs);
    this.live = (live != null) ? live : NgLiveContext.EMPTY;
    this.seek = (seek != null) ? seek : NgSeekPolicy.DEFAULT;
    this.index = (index != null) ? index : NgIndexContext.EMPTY;
    this.skip = (skip != null) ? skip : NgSkipContext.EMPTY;
    this.flow = (flow != null) ? flow : NgFlowPolicy.DEFAULT;
  }

  public int getVersion() { return version; }
  public String getSessionId() { return sessionId; }
  public long getMediaFileId() { return mediaFileId; }
  public long getAiringId() { return airingId; }
  public String getMode() { return mode; }
  public String getContainer() { return container; }
  public long getDurationMs() { return durationMs; }
  public long getServerMediaTimeMs() { return serverMediaTimeMs; }
  public NgLiveContext getLive() { return live; }
  public NgSeekPolicy getSeek() { return seek; }
  public NgIndexContext getIndex() { return index; }
  public NgSkipContext getSkip() { return skip; }
  public NgFlowPolicy getFlow() { return flow; }

  @Override
  public String toString()
  {
    return "NgPlaybackContext{v=" + version + ", session=" + sessionId +
        ", mediaFile=" + mediaFileId + ", mode=" + mode +
        ", container=" + container + ", durationMs=" + durationMs + '}';
  }
}
