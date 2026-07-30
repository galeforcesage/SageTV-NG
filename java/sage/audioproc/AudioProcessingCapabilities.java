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

/**
 * What a client can do for audio DSP, as reported by an {@code
 * AUDIO_PROCESSING_CAPABILITIES} message. This governs whether the server
 * may ever propose {@link AudioProcessingLocation#SERVER} for a client (it
 * must understand the resulting {@code AUDIO_PROCESSING_PLAN} message and
 * agree to stop running its own DSP), and whether a {@link
 * NightModeMode#PLATFORM_NIGHT_MODE} request is even meaningful for that
 * device.
 *
 * <p>All fields default to the safe/conservative value ({@code false}/{@code
 * 0}) so a client that never sends this message -- or a legacy/STV client --
 * is treated as fully incapable, which forces the resolver to {@link
 * AudioProcessingLocation#NONE}.
 */
public final class AudioProcessingCapabilities
{
  /** Safe default: a client that has reported nothing can do nothing. */
  public static final AudioProcessingCapabilities NONE = new Builder().build();

  private final boolean clientSideEqSupported;
  private final boolean serverEqPlanSupported;
  private final boolean platformNightModeAvailable;
  private final int maxEqBands;

  private AudioProcessingCapabilities(Builder b)
  {
    this.clientSideEqSupported = b.clientSideEqSupported;
    this.serverEqPlanSupported = b.serverEqPlanSupported;
    this.platformNightModeAvailable = b.platformNightModeAvailable;
    this.maxEqBands = Math.max(0, b.maxEqBands);
  }

  /** {@code true} if the client itself can run a local EQ/DSP chain. */
  public boolean isClientSideEqSupported()
  {
    return clientSideEqSupported;
  }

  /**
   * {@code true} if the client understands {@code AUDIO_PROCESSING_PLAN} /
   * {@code SETTINGS_VERSION_ACK} messages and can act on {@link
   * AudioProcessingLocation#SERVER} being chosen (i.e. stop any local DSP
   * when {@code clientMustDisableDsp} is set).
   */
  public boolean isServerEqPlanSupported()
  {
    return serverEqPlanSupported;
  }

  /** {@code true} if the device/OS/vendor exposes a platform night-mode API. */
  public boolean isPlatformNightModeAvailable()
  {
    return platformNightModeAvailable;
  }

  /** Maximum EQ bands the client can render locally; 0 if unknown/unsupported. */
  public int getMaxEqBands()
  {
    return maxEqBands;
  }

  @Override
  public String toString()
  {
    return "AudioProcessingCapabilities[clientSideEq=" + clientSideEqSupported
        + ", serverEqPlan=" + serverEqPlanSupported + ", platformNightMode=" + platformNightModeAvailable
        + ", maxEqBands=" + maxEqBands + "]";
  }

  public static Builder builder()
  {
    return new Builder();
  }

  public static final class Builder
  {
    private boolean clientSideEqSupported = false;
    private boolean serverEqPlanSupported = false;
    private boolean platformNightModeAvailable = false;
    private int maxEqBands = 0;

    public Builder clientSideEqSupported(boolean v)
    {
      this.clientSideEqSupported = v;
      return this;
    }

    public Builder serverEqPlanSupported(boolean v)
    {
      this.serverEqPlanSupported = v;
      return this;
    }

    public Builder platformNightModeAvailable(boolean v)
    {
      this.platformNightModeAvailable = v;
      return this;
    }

    public Builder maxEqBands(int v)
    {
      this.maxEqBands = v;
      return this;
    }

    public AudioProcessingCapabilities build()
    {
      return new AudioProcessingCapabilities(this);
    }
  }
}
