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
 * One physical segment of a multi-segment recording, expressed on the
 * whole-recording virtual timeline (see {@link sage.SegmentTimeline}).
 * <p>
 * A single SageTV airing can span several physical files after a power loss,
 * signal drop, or file-size rollover. The NG client navigates a continuous
 * virtual timeline and addresses individual segments by {@link #getIndex()};
 * it never receives absolute server filesystem paths.
 * <p>
 * Two time bases travel with each segment and must not be mixed:
 * <ul>
 *   <li>{@link #getContentBaseMs()} — content time (gaps collapsed): the offset
 *       at which continuous playback reaches this segment. Commercial-skip marks
 *       and caption cues (both per-file, file-relative) map to whole-recording
 *       content time by adding this value.</li>
 *   <li>{@link #getStartTimeUtcMs()} / {@link #getGapBeforeMs()} — wall-clock
 *       (gaps preserved): for the scrubber/OSD so the viewer sees the real jump
 *       across an outage.</li>
 * </ul>
 */
public final class NgSegment
{
  private final int index;
  private final long startTimeUtcMs;
  private final long durationMs;
  private final long contentBaseMs;
  private final long gapBeforeMs;
  private final long fileSizeBytes;

  public NgSegment(int index, long startTimeUtcMs, long durationMs,
      long contentBaseMs, long gapBeforeMs, long fileSizeBytes)
  {
    this.index = Math.max(0, index);
    this.startTimeUtcMs = Math.max(0, startTimeUtcMs);
    this.durationMs = Math.max(0, durationMs);
    this.contentBaseMs = Math.max(0, contentBaseMs);
    this.gapBeforeMs = Math.max(0, gapBeforeMs);
    this.fileSizeBytes = Math.max(0, fileSizeBytes);
  }

  /** 0-based physical segment index within the recording. */
  public int getIndex() { return index; }

  /** Wall-clock start of this segment (epoch ms) = MediaFile.getStart(i). */
  public long getStartTimeUtcMs() { return startTimeUtcMs; }

  /** Content duration of this segment (ms) = getEnd(i) - getStart(i). */
  public long getDurationMs() { return durationMs; }

  /** Content-time offset (ms) where this segment begins, gaps collapsed. */
  public long getContentBaseMs() { return contentBaseMs; }

  /** Real un-recorded wall-clock gap (ms) immediately before this segment; 0 for index 0. */
  public long getGapBeforeMs() { return gapBeforeMs; }

  /** Physical file size (bytes) at snapshot time, or 0 if unknown. */
  public long getFileSizeBytes() { return fileSizeBytes; }

  @Override
  public String toString()
  {
    return "NgSegment{index=" + index + ", startUtc=" + startTimeUtcMs +
        ", durationMs=" + durationMs + ", contentBaseMs=" + contentBaseMs +
        ", gapBeforeMs=" + gapBeforeMs + '}';
  }
}
