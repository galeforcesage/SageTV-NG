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
 * Target audio codec. {@link #COPY} stream-copies the source audio (the only
 * choice that losslessly retains an original 5.1 track).
 */
public enum AudioCodecChoice
{
  /** Stream-copy the source audio track unchanged. */
  COPY,
  AAC,
  AC3,
  EAC3;

  /** ffmpeg encoder name, or {@code null} for {@link #COPY}. */
  public String encoderName()
  {
    switch (this)
    {
      case AAC:  return "aac";
      case AC3:  return "ac3";
      case EAC3: return "eac3";
      case COPY:
      default:   return null;
    }
  }

  public boolean isCopy()
  {
    return this == COPY;
  }

  /** Whether this codec can carry a 5.1 (6-channel) layout. */
  public boolean supportsSurround()
  {
    return this == AC3 || this == EAC3 || this == AAC || this == COPY;
  }
}
