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

import java.util.List;

/**
 * Serializes {@link NgPlaybackContext} to compact JSON.
 * Uses manual StringBuilder — no external JSON library required.
 */
public final class NgPlaybackContextSerializer
{
  private NgPlaybackContextSerializer() {}

  /**
   * Serialize a full context to compact JSON.
   */
  public static String toJson(NgPlaybackContext ctx)
  {
    StringBuilder sb = new StringBuilder(512);
    sb.append('{');
    appendKey(sb, "version"); sb.append(ctx.getVersion());
    sb.append(','); appendKey(sb, "sessionId"); appendString(sb, ctx.getSessionId());
    sb.append(','); appendKey(sb, "mediaFileId"); sb.append(ctx.getMediaFileId());
    sb.append(','); appendKey(sb, "airingId"); sb.append(ctx.getAiringId());
    sb.append(','); appendKey(sb, "mode"); appendString(sb, ctx.getMode());
    sb.append(','); appendKey(sb, "container"); appendString(sb, ctx.getContainer());
    sb.append(','); appendKey(sb, "durationMs"); sb.append(ctx.getDurationMs());
    sb.append(','); appendKey(sb, "serverMediaTimeMs"); sb.append(ctx.getServerMediaTimeMs());
    sb.append(','); appendKey(sb, "streamEpoch"); sb.append(ctx.getStreamEpoch());
    sb.append(','); appendKey(sb, "live"); appendLive(sb, ctx.getLive());
    sb.append(','); appendKey(sb, "seek"); appendSeek(sb, ctx.getSeek());
    sb.append(','); appendKey(sb, "index"); appendIndex(sb, ctx.getIndex());
    sb.append(','); appendKey(sb, "skip"); appendSkip(sb, ctx.getSkip());
    sb.append(','); appendKey(sb, "flow"); appendFlow(sb, ctx.getFlow());
    sb.append('}');
    return sb.toString();
  }

  private static void appendLive(StringBuilder sb, NgLiveContext live)
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

  private static void appendSeek(StringBuilder sb, NgSeekPolicy seek)
  {
    sb.append('{');
    appendKey(sb, "preferredGranularityMs"); sb.append(seek.getPreferredGranularityMs());
    sb.append(','); appendKey(sb, "minSeekIntervalMs"); sb.append(seek.getMinSeekIntervalMs());
    sb.append(','); appendKey(sb, "maxClientCoalesceMs"); sb.append(seek.getMaxClientCoalesceMs());
    sb.append(','); appendKey(sb, "requiresServerSeek"); sb.append(seek.isRequiresServerSeek());
    sb.append(','); appendKey(sb, "clientMayPredictOsd"); sb.append(seek.isClientMayPredictOsd());
    sb.append('}');
  }

  private static void appendIndex(StringBuilder sb, NgIndexContext index)
  {
    sb.append('{');
    appendKey(sb, "hasKeyframeIndex"); sb.append(index.hasKeyframeIndex());
    sb.append(','); appendKey(sb, "hasPtsByteMap"); sb.append(index.hasPtsByteMap());
    sb.append(','); appendKey(sb, "ptsSamples"); appendPtsSamples(sb, index.getPtsSamples());
    sb.append('}');
  }

  private static void appendPtsSamples(StringBuilder sb, List<NgPtsSample> samples)
  {
    sb.append('[');
    for (int i = 0; i < samples.size(); i++)
    {
      if (i > 0) sb.append(',');
      NgPtsSample s = samples.get(i);
      sb.append('{');
      appendKey(sb, "timeMs"); sb.append(s.getTimeMs());
      sb.append(','); appendKey(sb, "byteOffset"); sb.append(s.getByteOffset());
      sb.append(','); appendKey(sb, "keyframe"); sb.append(s.isKeyframe());
      sb.append('}');
    }
    sb.append(']');
  }

  private static void appendSkip(StringBuilder sb, NgSkipContext skip)
  {
    sb.append('{');
    appendKey(sb, "commercials"); appendSegmentList(sb, skip.getCommercials());
    sb.append(','); appendKey(sb, "chapters"); appendSegmentList(sb, skip.getChapters());
    sb.append(','); appendKey(sb, "bookmarks"); appendSegmentList(sb, skip.getBookmarks());
    sb.append('}');
  }

  private static void appendSegmentList(StringBuilder sb, List<NgSkipSegment> segments)
  {
    sb.append('[');
    for (int i = 0; i < segments.size(); i++)
    {
      if (i > 0) sb.append(',');
      NgSkipSegment seg = segments.get(i);
      sb.append('{');
      appendKey(sb, "startMs"); sb.append(seg.getStartMs());
      sb.append(','); appendKey(sb, "endMs"); sb.append(seg.getEndMs());
      sb.append(','); appendKey(sb, "type"); appendString(sb, seg.getType());
      sb.append(','); appendKey(sb, "prerollMs"); sb.append(seg.getPrerollMs());
      sb.append('}');
    }
    sb.append(']');
  }

  private static void appendFlow(StringBuilder sb, NgFlowPolicy flow)
  {
    sb.append('{');
    appendKey(sb, "preferredPrebufferBytes"); sb.append(flow.getPreferredPrebufferBytes());
    sb.append(','); appendKey(sb, "lowWatermarkBytes"); sb.append(flow.getLowWatermarkBytes());
    sb.append(','); appendKey(sb, "highWatermarkBytes"); sb.append(flow.getHighWatermarkBytes());
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
          {
            sb.append("\\u");
            sb.append(String.format("%04x", (int) c));
          }
          else
          {
            sb.append(c);
          }
      }
    }
    sb.append('"');
  }
}
