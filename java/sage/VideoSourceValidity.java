/*
 * Copyright 2015 The SageTV Authors. All Rights Reserved.
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
package sage;

public final class VideoSourceValidity
{
  private VideoSourceValidity()
  {
  }

  public static final String CLEANUP_ENABLED = "mmc/video_source_cleanup/enabled";
  public static final String SCAN_INTERVAL_MS = "mmc/video_source_cleanup/scan_interval_ms";
  public static final String STALE_AFTER_MS = "mmc/video_source_cleanup/stale_after_ms";
  public static final String PRUNE_AFTER_MS = "mmc/video_source_cleanup/prune_after_ms";

  public static final String STATE_STALE = "stale";
  public static final String STATE_EXPIRED = "expired";

  public static final String REASON_MISSING_EPG_DATA_SOURCE = "missing_epg_data_source";

  private static final long DEFAULT_SCAN_INTERVAL_MS = Sage.MILLIS_PER_DAY;
  private static final long DEFAULT_STALE_AFTER_MS = Sage.MILLIS_PER_DAY;
  private static final long DEFAULT_PRUNE_AFTER_MS = 14L * Sage.MILLIS_PER_DAY;

  public static boolean isCleanupEnabled()
  {
    return Sage.getBoolean(CLEANUP_ENABLED, false);
  }

  public static long getScanIntervalMs()
  {
    return Math.max(Sage.MILLIS_PER_MIN, Sage.getLong(SCAN_INTERVAL_MS, DEFAULT_SCAN_INTERVAL_MS));
  }

  public static long getStaleAfterMs()
  {
    return Math.max(0L, Sage.getLong(STALE_AFTER_MS, DEFAULT_STALE_AFTER_MS));
  }

  public static long getPruneAfterMs()
  {
    return Math.max(getStaleAfterMs(), Sage.getLong(PRUNE_AFTER_MS, DEFAULT_PRUNE_AFTER_MS));
  }

  public static String resolveState(long firstInvalidAt, long now)
  {
    if (firstInvalidAt <= 0)
      throw new IllegalArgumentException("firstInvalidAt must be positive");
    return now - firstInvalidAt >= getStaleAfterMs() ? STATE_EXPIRED : STATE_STALE;
  }

  public static boolean shouldPrune(long firstInvalidAt, long now)
  {
    return isCleanupEnabled() && firstInvalidAt > 0 && now - firstInvalidAt >= getPruneAfterMs();
  }
}
