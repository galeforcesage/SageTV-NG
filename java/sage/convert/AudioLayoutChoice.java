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
 * Output audio channel layout. {@link #SURROUND_51} is only honoured when the
 * source actually has 6+ channels; otherwise the builder falls back to keeping
 * the source layout (never upmixes).
 */
public enum AudioLayoutChoice
{
  /** Keep the source channel layout. */
  KEEP,
  /** Downmix to 2.0 stereo. */
  STEREO,
  /** Keep/produce 5.1 surround (requires a 6-channel source). */
  SURROUND_51;

  /** Explicit {@code -ac} channel count for this layout, or 0 to omit -ac. */
  public int channels()
  {
    switch (this)
    {
      case STEREO:      return 2;
      case SURROUND_51: return 6;
      case KEEP:
      default:          return 0;
    }
  }
}
