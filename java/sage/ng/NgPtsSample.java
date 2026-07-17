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
 * A single PTS/byte-offset sample point from the server's index.
 * Used by NG clients to map between time and byte position for
 * client-assisted seeking.
 */
public final class NgPtsSample
{
  private final long timeMs;
  private final long byteOffset;
  private final boolean keyframe;

  public NgPtsSample(long timeMs, long byteOffset, boolean keyframe)
  {
    this.timeMs = Math.max(0, timeMs);
    this.byteOffset = Math.max(0, byteOffset);
    this.keyframe = keyframe;
  }

  public long getTimeMs() { return timeMs; }
  public long getByteOffset() { return byteOffset; }
  public boolean isKeyframe() { return keyframe; }

  @Override
  public String toString()
  {
    return "NgPtsSample{timeMs=" + timeMs + ", byteOffset=" + byteOffset +
        ", keyframe=" + keyframe + '}';
  }
}
