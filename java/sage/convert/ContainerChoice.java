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
 * Target output container. Decoupled from the video codec in the guided UI:
 * changing the container never re-encodes and never shrinks the media.
 */
public enum ContainerChoice
{
  /** Keep the source container where possible (falls back to MP4). */
  KEEP,
  MP4,
  MKV;

  /** ffmpeg muxer name for this container, given the source muxer for KEEP. */
  public String muxerName(String sourceMuxer)
  {
    switch (this)
    {
      case MP4: return "mp4";
      case MKV: return "matroska";
      case KEEP:
      default:
        if (sourceMuxer != null && sourceMuxer.length() > 0) return sourceMuxer;
        return "mp4";
    }
  }

  /** File extension for this container, given the source extension for KEEP. */
  public String extension(String sourceExt)
  {
    switch (this)
    {
      case MP4: return "mp4";
      case MKV: return "mkv";
      case KEEP:
      default:
        if (sourceExt != null && sourceExt.length() > 0) return sourceExt;
        return "mp4";
    }
  }
}
