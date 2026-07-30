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
package sage.audioproc;

import java.util.Locale;

/**
 * Night-mode / dynamic-range-suppression strategy. Only
 * {@link #DYNAMIC_RANGE_COMPRESSION} and {@link #LOUDNESS_LEVELING} are
 * server-executable (see {@link #isServerExecutable()}); {@link
 * #PLATFORM_NIGHT_MODE} means "let the device/OS/vendor handle it" and can
 * never be built into a server-side ffmpeg filtergraph -- the builder must
 * record it as advisory only and resolve to {@link AudioProcessingLocation#NONE}
 * for that function rather than faking an equivalent server filter.
 */
public enum NightModeMode
{
  OFF,
  DYNAMIC_RANGE_COMPRESSION,
  LOUDNESS_LEVELING,
  PLATFORM_NIGHT_MODE;

  /** {@code true} for the two modes the server can build an ffmpeg filter for. */
  public boolean isServerExecutable()
  {
    return this == DYNAMIC_RANGE_COMPRESSION || this == LOUDNESS_LEVELING;
  }

  /** Unknown, empty, or {@code null} wire values map to {@link #OFF}. */
  public static NightModeMode fromWire(String s)
  {
    if (s == null)
      return OFF;
    String trimmed = s.trim();
    if (trimmed.length() == 0)
      return OFF;
    try
    {
      return NightModeMode.valueOf(trimmed.toUpperCase(Locale.ROOT));
    }
    catch (IllegalArgumentException e)
    {
      return OFF;
    }
  }
}
