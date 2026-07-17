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
 * A single skip/chapter/bookmark segment with time range and optional preroll.
 */
public final class NgSkipSegment
{
  private final long startMs;
  private final long endMs;
  private final String type;
  private final long prerollMs;

  public NgSkipSegment(long startMs, long endMs, String type, long prerollMs)
  {
    this.startMs = Math.max(0, startMs);
    long clampedEnd = Math.max(0, endMs);
    this.endMs = Math.max(this.startMs, clampedEnd);
    this.type = (type != null) ? type : "unknown";
    this.prerollMs = Math.max(0, prerollMs);
  }

  public long getStartMs() { return startMs; }
  public long getEndMs() { return endMs; }
  public String getType() { return type; }
  public long getPrerollMs() { return prerollMs; }

  @Override
  public String toString()
  {
    return "NgSkipSegment{startMs=" + startMs + ", endMs=" + endMs +
        ", type='" + type + "', prerollMs=" + prerollMs + '}';
  }
}
