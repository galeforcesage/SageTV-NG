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
 * Where audio DSP (equalizer/preamp/night-mode) is applied for a given
 * playback session.
 * <ul>
 *   <li>{@link #CLIENT} - the client applies its own local DSP; the server
 *       does not touch the audio stage at all.</li>
 *   <li>{@link #SERVER} - the client asked the server to apply DSP via an
 *       {@code -af} filtergraph on the audio encode.</li>
 *   <li>{@link #NONE} - no audio DSP anywhere (feature off, client disabled
 *       EQ, legacy/STV client, or the wire value was missing/unrecognized).</li>
 * </ul>
 * {@link #fromWire(String)} is the only place unknown/legacy values are
 * interpreted; it always fails safe to {@link #NONE} rather than throwing,
 * since this value arrives as free-form text from the client protocol.
 */
public enum AudioProcessingLocation
{
  CLIENT,
  SERVER,
  NONE;

  /**
   * Parses a wire value (as received in an {@code AUDIO_PROCESSING_*}
   * client message). Unknown, empty, or {@code null} values map to
   * {@link #NONE} -- never throws.
   */
  public static AudioProcessingLocation fromWire(String s)
  {
    if (s == null)
      return NONE;
    String trimmed = s.trim();
    if (trimmed.length() == 0)
      return NONE;
    try
    {
      return AudioProcessingLocation.valueOf(trimmed.toUpperCase(Locale.ROOT));
    }
    catch (IllegalArgumentException e)
    {
      return NONE;
    }
  }
}
