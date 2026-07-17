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
 * Serializes {@link NgPlaybackContextDelta} to compact JSON.
 * Uses manual StringBuilder — no external JSON library required.
 */
public final class NgPlaybackContextDeltaSerializer
{
  private NgPlaybackContextDeltaSerializer() {}

  /**
   * Serialize a delta to compact JSON.
   */
  public static String toJson(NgPlaybackContextDelta delta)
  {
    StringBuilder sb = new StringBuilder(256);
    sb.append('{');
    appendKey(sb, "version"); sb.append(delta.getVersion());
    sb.append(','); appendKey(sb, "sessionId"); appendString(sb, delta.getSessionId());
    sb.append(','); appendKey(sb, "mediaFileId"); sb.append(delta.getMediaFileId());
    sb.append(','); appendKey(sb, "airingId"); sb.append(delta.getAiringId());
    sb.append(','); appendKey(sb, "streamEpoch"); sb.append(delta.getStreamEpoch());
    sb.append(','); appendKey(sb, "type"); appendString(sb, delta.getType());
    sb.append(','); appendKey(sb, "reason"); appendString(sb, delta.getReason());
    sb.append(','); appendKey(sb, "serverMediaTimeMs"); sb.append(delta.getServerMediaTimeMs());
    sb.append(','); appendKey(sb, "live"); appendLiveWindow(sb, delta.getLive());
    sb.append('}');
    return sb.toString();
  }

  private static void appendLiveWindow(StringBuilder sb, NgLiveWindowUpdate live)
  {
    sb.append('{');
    appendKey(sb, "isLive"); sb.append(live.isLive());
    sb.append(','); appendKey(sb, "recordingStartMs"); sb.append(live.getRecordingStartMs());
    sb.append(','); appendKey(sb, "safeSeekStartMs"); sb.append(live.getSafeSeekStartMs());
    sb.append(','); appendKey(sb, "safeSeekEndMs"); sb.append(live.getSafeSeekEndMs());
    sb.append(','); appendKey(sb, "playableEndMs"); sb.append(live.getPlayableEndMs());
    sb.append(','); appendKey(sb, "growthBytes"); sb.append(live.getGrowthBytes());
    sb.append(','); appendKey(sb, "lastSizeRefreshMs"); sb.append(live.getLastSizeRefreshMs());
    sb.append('}');
  }

  private static void appendKey(StringBuilder sb, String key)
  {
    sb.append('"').append(key).append("\":");
  }

  private static void appendString(StringBuilder sb, String value)
  {
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
        default:
          if (c < 0x20)
            sb.append(String.format("\\u%04x", (int) c));
          else
            sb.append(c);
      }
    }
    sb.append('"');
  }
}
