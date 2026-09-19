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
import java.util.Collections;
import java.util.List;

/**
 * The segment manifest for the current recording — the whole-recording virtual
 * timeline the NG client navigates.
 * <p>
 * For a single-file recording this carries one item (or {@link #EMPTY} when no
 * segment info is available), so the common case is unchanged. For a
 * multi-segment recording it exposes every physical segment with its content and
 * wall-clock offsets, letting the client cross boundaries (FF/REW/Skip), map
 * aggregated comskip/caption marks, and render the OSD without any absolute
 * server path.
 *
 * @see sage.SegmentTimeline
 */
public final class NgSegmentContext
{
  public static final NgSegmentContext EMPTY =
      new NgSegmentContext(Collections.emptyList(), 0, false);

  private final List<NgSegment> items;
  private final long totalContentMs;
  private final boolean hasGaps;

  public NgSegmentContext(List<NgSegment> items, long totalContentMs, boolean hasGaps)
  {
    this.items = (items != null)
        ? Collections.unmodifiableList(new ArrayList<>(items))
        : Collections.emptyList();
    this.totalContentMs = Math.max(0, totalContentMs);
    this.hasGaps = hasGaps;
  }

  /**
   * Build a manifest from a {@link sage.SegmentTimeline} snapshot. Returns
   * {@link #EMPTY} when the timeline is null or empty.
   */
  public static NgSegmentContext fromTimeline(sage.SegmentTimeline timeline)
  {
    if (timeline == null || timeline.segmentCount() == 0)
      return EMPTY;

    int n = timeline.segmentCount();
    List<NgSegment> items = new ArrayList<>(n);
    for (int i = 0; i < n; i++)
    {
      items.add(new NgSegment(
          i,
          timeline.startEpochMs(i),
          timeline.durationMs(i),
          timeline.contentBaseMs(i),
          timeline.gapBeforeMs(i),
          timeline.sizeBytes(i)));
    }
    return new NgSegmentContext(items, timeline.totalContentMs(), timeline.hasGaps());
  }

  public List<NgSegment> getItems() { return items; }
  public int getCount() { return items.size(); }
  public long getTotalContentMs() { return totalContentMs; }
  public boolean hasGaps() { return hasGaps; }
  public boolean isMultiSegment() { return items.size() > 1; }

  @Override
  public String toString()
  {
    return "NgSegmentContext{count=" + items.size() +
        ", totalContentMs=" + totalContentMs + (hasGaps ? ", gapped" : "") + '}';
  }
}
