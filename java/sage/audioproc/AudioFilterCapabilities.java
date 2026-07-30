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
 * Which audio filters the configured ffmpeg/transcoder binary actually
 * supports, as reported by {@code ffmpeg -filters}. The audio-EQ resolver
 * (Phase 5) consults this before ever proposing {@link
 * AudioProcessingLocation#SERVER} -- if the binary can't build the required
 * filtergraph, the resolver must fail safe to {@code NONE} rather than
 * emitting an {@code -af} value ffmpeg will reject at runtime.
 */
public final class AudioFilterCapabilities
{
  /** Safe default when the binary could not be probed at all (missing/unreadable/exec failure). */
  public static AudioFilterCapabilities unavailable(String ffmpegPath)
  {
    return new Builder().ffmpegPath(ffmpegPath).probeSucceeded(false).build();
  }

  private final String ffmpegPath;
  private final boolean probeSucceeded;
  private final boolean equalizerAvailable;
  private final boolean anequalizerAvailable;
  private final boolean volumeAvailable;
  private final boolean aresampleAvailable;
  private final boolean acompressorAvailable;
  private final boolean loudnormAvailable;
  private final boolean dynaudnormAvailable;
  private final boolean alimiterAvailable;
  private final long probedAtMillis;

  private AudioFilterCapabilities(Builder b)
  {
    this.ffmpegPath = b.ffmpegPath;
    this.probeSucceeded = b.probeSucceeded;
    this.equalizerAvailable = b.equalizerAvailable;
    this.anequalizerAvailable = b.anequalizerAvailable;
    this.volumeAvailable = b.volumeAvailable;
    this.aresampleAvailable = b.aresampleAvailable;
    this.acompressorAvailable = b.acompressorAvailable;
    this.loudnormAvailable = b.loudnormAvailable;
    this.dynaudnormAvailable = b.dynaudnormAvailable;
    this.alimiterAvailable = b.alimiterAvailable;
    this.probedAtMillis = b.probedAtMillis;
  }

  public String getFfmpegPath()
  {
    return ffmpegPath;
  }

  /** {@code false} if the {@code ffmpeg -filters} probe itself could not run (binary missing, exec error, etc). */
  public boolean isProbeSucceeded()
  {
    return probeSucceeded;
  }

  public boolean isEqualizerAvailable()
  {
    return equalizerAvailable;
  }

  public boolean isAnequalizerAvailable()
  {
    return anequalizerAvailable;
  }

  public boolean isVolumeAvailable()
  {
    return volumeAvailable;
  }

  public boolean isAresampleAvailable()
  {
    return aresampleAvailable;
  }

  public boolean isAcompressorAvailable()
  {
    return acompressorAvailable;
  }

  public boolean isLoudnormAvailable()
  {
    return loudnormAvailable;
  }

  public boolean isDynaudnormAvailable()
  {
    return dynaudnormAvailable;
  }

  public boolean isAlimiterAvailable()
  {
    return alimiterAvailable;
  }

  public long getProbedAtMillis()
  {
    return probedAtMillis;
  }

  /** {@code true} when every filter the v1 EQ/preamp/final-limiter chain needs is present. */
  public boolean supportsEqChain()
  {
    return probeSucceeded && equalizerAvailable && volumeAvailable && alimiterAvailable;
  }

  /**
   * {@code true} if this binary can build a server-executable filter for
   * the given night mode. {@link NightModeMode#OFF} and {@link
   * NightModeMode#PLATFORM_NIGHT_MODE} are never server-executable
   * regardless of probe results -- see {@link NightModeMode#isServerExecutable()}.
   */
  public boolean supportsNightMode(NightModeMode mode)
  {
    if (mode == null || !mode.isServerExecutable())
      return false;
    if (!probeSucceeded)
      return false;
    switch (mode)
    {
      case DYNAMIC_RANGE_COMPRESSION:
        return acompressorAvailable;
      case LOUDNESS_LEVELING:
        return loudnormAvailable || dynaudnormAvailable;
      default:
        return false;
    }
  }

  @Override
  public String toString()
  {
    return "AudioFilterCapabilities[ffmpegPath=" + ffmpegPath + ", probeSucceeded=" + probeSucceeded
        + ", equalizer=" + equalizerAvailable + ", anequalizer=" + anequalizerAvailable
        + ", volume=" + volumeAvailable + ", aresample=" + aresampleAvailable
        + ", acompressor=" + acompressorAvailable + ", loudnorm=" + loudnormAvailable
        + ", dynaudnorm=" + dynaudnormAvailable + ", alimiter=" + alimiterAvailable + "]";
  }

  public static Builder builder()
  {
    return new Builder();
  }

  public static final class Builder
  {
    private String ffmpegPath;
    private boolean probeSucceeded = false;
    private boolean equalizerAvailable = false;
    private boolean anequalizerAvailable = false;
    private boolean volumeAvailable = false;
    private boolean aresampleAvailable = false;
    private boolean acompressorAvailable = false;
    private boolean loudnormAvailable = false;
    private boolean dynaudnormAvailable = false;
    private boolean alimiterAvailable = false;
    private long probedAtMillis = 0L;

    public Builder ffmpegPath(String v) { this.ffmpegPath = v; return this; }
    public Builder probeSucceeded(boolean v) { this.probeSucceeded = v; return this; }
    public Builder equalizerAvailable(boolean v) { this.equalizerAvailable = v; return this; }
    public Builder anequalizerAvailable(boolean v) { this.anequalizerAvailable = v; return this; }
    public Builder volumeAvailable(boolean v) { this.volumeAvailable = v; return this; }
    public Builder aresampleAvailable(boolean v) { this.aresampleAvailable = v; return this; }
    public Builder acompressorAvailable(boolean v) { this.acompressorAvailable = v; return this; }
    public Builder loudnormAvailable(boolean v) { this.loudnormAvailable = v; return this; }
    public Builder dynaudnormAvailable(boolean v) { this.dynaudnormAvailable = v; return this; }
    public Builder alimiterAvailable(boolean v) { this.alimiterAvailable = v; return this; }
    public Builder probedAtMillis(long v) { this.probedAtMillis = v; return this; }

    public AudioFilterCapabilities build()
    {
      return new AudioFilterCapabilities(this);
    }
  }
}
