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
 * The minimal facts about a produced media file needed to validate it, obtained
 * by probing the finished output (ffprobe: does the container open, what is its
 * duration, how many video/audio streams, how large is it on disk).
 *
 * <p>This is a plain value object so the validation decision
 * ({@link DerivativeValidator}) is pure and unit-testable without invoking
 * ffprobe or touching the filesystem. The caller (the job/validation phase)
 * populates it from a real probe.
 */
public final class ProbeResult
{
  private final boolean containerOpened;
  private final long durationMillis;
  private final int videoStreams;
  private final int audioStreams;
  private final long byteSize;

  public ProbeResult(boolean containerOpened, long durationMillis,
      int videoStreams, int audioStreams, long byteSize)
  {
    this.containerOpened = containerOpened;
    this.durationMillis = durationMillis;
    this.videoStreams = videoStreams;
    this.audioStreams = audioStreams;
    this.byteSize = byteSize;
  }

  /** Whether ffprobe could open/parse the container at all. */
  public boolean isContainerOpened() { return containerOpened; }
  /** Probed duration in milliseconds (0 if unknown). */
  public long getDurationMillis() { return durationMillis; }
  public int getVideoStreams() { return videoStreams; }
  public int getAudioStreams() { return audioStreams; }
  /** File size on disk in bytes. */
  public long getByteSize() { return byteSize; }
}
