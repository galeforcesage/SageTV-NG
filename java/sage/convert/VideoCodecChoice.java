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
 * Target video codec. {@link #COPY} stream-copies the source video (no
 * re-encode, byte-preserving); the others re-encode via the best available
 * hardware or software encoder as resolved by the caller.
 */
public enum VideoCodecChoice
{
  /** Stream-copy the source video track unchanged. */
  COPY,
  H264,
  HEVC,
  AV1;

  /** Normalized codec family token used to resolve an encoder name. */
  public String codecToken()
  {
    switch (this)
    {
      case H264: return "h264";
      case HEVC: return "hevc";
      case AV1:  return "av1";
      case COPY:
      default:   return "copy";
    }
  }

  public boolean isCopy()
  {
    return this == COPY;
  }
}
