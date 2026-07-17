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
 * Server-advisory buffer flow policy for NG clients.
 * Tells the client how to manage its playback buffer.
 */
public final class NgFlowPolicy
{
  public static final NgFlowPolicy DEFAULT = new NgFlowPolicy(262144, 131072, 4194304);

  private final int preferredPrebufferBytes;
  private final int lowWatermarkBytes;
  private final int highWatermarkBytes;

  public NgFlowPolicy(int preferredPrebufferBytes, int lowWatermarkBytes, int highWatermarkBytes)
  {
    this.preferredPrebufferBytes = Math.max(0, preferredPrebufferBytes);
    this.lowWatermarkBytes = Math.max(0, lowWatermarkBytes);
    this.highWatermarkBytes = Math.max(this.lowWatermarkBytes, Math.max(0, highWatermarkBytes));
  }

  public int getPreferredPrebufferBytes() { return preferredPrebufferBytes; }
  public int getLowWatermarkBytes() { return lowWatermarkBytes; }
  public int getHighWatermarkBytes() { return highWatermarkBytes; }

  @Override
  public String toString()
  {
    return "NgFlowPolicy{prebuffer=" + preferredPrebufferBytes +
        ", low=" + lowWatermarkBytes + ", high=" + highWatermarkBytes + '}';
  }
}
