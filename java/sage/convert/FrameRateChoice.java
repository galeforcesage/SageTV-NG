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
package sage.convert;

/**
 * Output frame-rate policy. Implemented with ffmpeg's {@code -fpsmax}, which
 * only ever reduces the frame rate (a 59.94 source is decimated to ~29.97 for
 * {@link #CAP_30}) and never duplicates frames, so lower-rate sources pass
 * through untouched. This is source-fps-aware with no runtime probing.
 */
public enum FrameRateChoice
{
  /** Keep the source frame rate. */
  KEEP,
  /** Cap at ~29.97 fps (NTSC 30000/1001). */
  CAP_30,
  /** Cap at 24 fps (24000/1001). */
  CAP_24,
  /** Allow up to 60 fps (no cap; identical wire behaviour to KEEP here). */
  ALLOW_60;

  /**
   * The {@code -fpsmax} rational token for this policy, or {@code null} when no
   * {@code -fpsmax} argument should be emitted.
   */
  public String fpsMaxToken()
  {
    switch (this)
    {
      case CAP_30: return "30000/1001";
      case CAP_24: return "24000/1001";
      case KEEP:
      case ALLOW_60:
      default:     return null;
    }
  }

  /** Nominal fps this policy caps to (for size estimation); 0 means "keep". */
  public double cappedFps()
  {
    switch (this)
    {
      case CAP_30: return 30000.0 / 1001.0;
      case CAP_24: return 24000.0 / 1001.0;
      case KEEP:
      case ALLOW_60:
      default:     return 0.0;
    }
  }
}
