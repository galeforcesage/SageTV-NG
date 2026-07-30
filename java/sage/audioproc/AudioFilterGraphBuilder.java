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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the deterministic ffmpeg {@code -af} filtergraph for a client's
 * requested {@link AudioProcessingSettings}, given what the configured
 * ffmpeg binary can actually do ({@link AudioFilterCapabilities}).
 *
 * <p>Filter chain order is fixed: preamp -&gt; 10-band EQ -&gt; night-mode
 * compression/leveling -&gt; a final clip-safety {@code alimiter}. The
 * limiter is unconditionally appended whenever any gain-affecting stage is
 * present, so preamp boosts, EQ boosts, and compression makeup gain can
 * never clip the output. {@link NightModeMode#PLATFORM_NIGHT_MODE} is never
 * built into the graph -- it is device/OS/vendor-executed only, so it is
 * recorded as advisory and contributes no filter stage (see {@link
 * Result#isNightModeAdvisoryOnly()}).
 *
 * <p>This class has no knowledge of, and must never be given, any video or
 * audio-codec/bitrate decision -- it only ever produces an {@code -af}
 * value string for whatever audio encode the existing pipeline already
 * selected.
 */
public final class AudioFilterGraphBuilder
{
  /** Fixed EQ band Q (bandwidth), matching a typical 10-band graphic-EQ response. */
  static final double EQ_BAND_Q = 1.0;

  private AudioFilterGraphBuilder()
  {
  }

  /**
   * Builds the filtergraph for {@code settings} against {@code caps}. Never
   * throws; every failure mode (missing filter support, no-op settings,
   * advisory-only night mode) is reported via {@link Result}.
   */
  public static Result build(AudioProcessingSettings settings, AudioFilterCapabilities caps)
  {
    AudioProcessingSettings effective = settings == null ? AudioProcessingSettings.DISABLED : settings;

    if (effective.isEffectivelyNoop())
      return Result.noop("settings are a no-op: EQ disabled, no preamp, night mode off", false);

    if (caps == null || !caps.isProbeSucceeded())
      return Result.notBuildable("ffmpeg audio filter capabilities unavailable (probe failed)");

    List<String> stages = new ArrayList<String>();
    boolean nightModeAdvisoryOnly = false;

    if (effective.getPreampDb() != 0.0)
    {
      if (!caps.isVolumeAvailable())
        return Result.notBuildable("volume filter unavailable for preamp stage");
      stages.add(String.format(Locale.ROOT, "volume=%.2fdB", effective.getPreampDb()));
    }

    if (effective.isEqEnabled() && !effective.getBands().isEmpty())
    {
      List<EqualizerBand> activeBands = new ArrayList<EqualizerBand>();
      for (EqualizerBand band : effective.getBands())
      {
        // Per canonical rule: omit disabled or flat (0 dB) bands from the filtergraph entirely.
        if (band.isEnabled() && !band.isFlat())
          activeBands.add(band);
      }
      if (!activeBands.isEmpty())
      {
        if (!caps.isEqualizerAvailable())
          return Result.notBuildable("equalizer filter unavailable for EQ bands");
        for (EqualizerBand band : activeBands)
        {
          stages.add(String.format(Locale.ROOT, "equalizer=f=%.2f:t=q:w=%.2f:g=%.2f",
              band.getFrequencyHz(), band.getQ(), band.getGainDb()));
        }
      }
    }

    NightModeSettings nightMode = effective.getNightMode();
    if (!nightMode.isOff())
    {
      NightModeMode mode = nightMode.getMode();
      if (mode == NightModeMode.PLATFORM_NIGHT_MODE)
      {
        nightModeAdvisoryOnly = true;
      }
      else if (mode.isServerExecutable())
      {
        if (!caps.supportsNightMode(mode))
          return Result.notBuildable("night mode filter unavailable for " + mode);
        stages.add(buildNightModeFilter(mode, nightMode.getIntensity()));
      }
    }

    if (stages.isEmpty())
    {
      String reason = nightModeAdvisoryOnly
          ? "only PLATFORM_NIGHT_MODE requested, which is not server-executable"
          : "no server-executable DSP stages requested";
      return Result.noop(reason, nightModeAdvisoryOnly);
    }

    if (!caps.isAlimiterAvailable())
      return Result.notBuildable("alimiter filter unavailable for the mandatory clip-safety stage");
    stages.add("alimiter");

    StringBuilder graph = new StringBuilder();
    for (int i = 0; i < stages.size(); i++)
    {
      if (i > 0)
        graph.append(',');
      graph.append(stages.get(i));
    }
    return Result.buildable(graph.toString(), nightModeAdvisoryOnly);
  }

  private static String buildNightModeFilter(NightModeMode mode, NightModeIntensity intensity)
  {
    switch (mode)
    {
      case DYNAMIC_RANGE_COMPRESSION:
        return buildAcompressor(intensity);
      case LOUDNESS_LEVELING:
        return buildLoudnorm(intensity);
      default:
        // Unreachable: callers only invoke this for server-executable modes.
        throw new IllegalArgumentException("Not server-executable: " + mode);
    }
  }

  /**
   * threshold/ratio/attack/release/makeup presets by suppression strength.
   * Higher intensity = lower threshold + higher ratio = more aggressive
   * loud/quiet leveling.
   */
  private static String buildAcompressor(NightModeIntensity intensity)
  {
    switch (intensity)
    {
      case HIGH:
        return "acompressor=threshold=-16dB:ratio=8:attack=10:release=200:makeup=6dB";
      case MEDIUM:
        return "acompressor=threshold=-20dB:ratio=4:attack=20:release=250:makeup=4dB";
      case LOW:
      default:
        return "acompressor=threshold=-24dB:ratio=2:attack=20:release=250:makeup=2dB";
    }
  }

  /**
   * EBU R128 integrated-loudness (I) / loudness-range (LRA) / true-peak (TP)
   * presets by suppression strength. Higher intensity = louder target floor
   * + narrower allowed loudness range = more aggressive leveling.
   */
  private static String buildLoudnorm(NightModeIntensity intensity)
  {
    switch (intensity)
    {
      case HIGH:
        return "loudnorm=I=-16:LRA=7:TP=-1.0";
      case MEDIUM:
        return "loudnorm=I=-18:LRA=11:TP=-1.5";
      case LOW:
      default:
        return "loudnorm=I=-20:LRA=15:TP=-2.0";
    }
  }

  /** Outcome of a {@link #build(AudioProcessingSettings, AudioFilterCapabilities)} call. */
  public static final class Result
  {
    private final boolean buildable;
    private final String filterGraph;
    private final String filterGraphHash;
    private final String reason;
    private final boolean nightModeAdvisoryOnly;

    private Result(boolean buildable, String filterGraph, String reason, boolean nightModeAdvisoryOnly)
    {
      this.buildable = buildable;
      this.filterGraph = filterGraph;
      this.filterGraphHash = filterGraph == null ? null : AudioProcessingHashing.sha256Hex16(filterGraph);
      this.reason = reason;
      this.nightModeAdvisoryOnly = nightModeAdvisoryOnly;
    }

    static Result buildable(String filterGraph, boolean nightModeAdvisoryOnly)
    {
      return new Result(true, filterGraph, "buildable", nightModeAdvisoryOnly);
    }

    static Result noop(String reason, boolean nightModeAdvisoryOnly)
    {
      return new Result(false, null, reason, nightModeAdvisoryOnly);
    }

    static Result notBuildable(String reason)
    {
      return new Result(false, null, reason, false);
    }

    /** {@code true} only when {@link #getFilterGraph()} is non-null and ready to use as {@code -af}. */
    public boolean isBuildable()
    {
      return buildable;
    }

    /** The complete {@code -af} value; {@code null} unless {@link #isBuildable()}. */
    public String getFilterGraph()
    {
      return filterGraph;
    }

    /** Hash of {@link #getFilterGraph()}; {@code null} unless {@link #isBuildable()}. */
    public String getFilterGraphHash()
    {
      return filterGraphHash;
    }

    /** Diagnostic explaining the outcome (always non-null). */
    public String getReason()
    {
      return reason;
    }

    /**
     * {@code true} when the caller requested {@link NightModeMode#PLATFORM_NIGHT_MODE},
     * which was recorded as advisory only and contributed no filter stage
     * (this can be {@code true} even when {@link #isBuildable()} is {@code
     * true}, if e.g. EQ bands were also requested alongside it).
     */
    public boolean isNightModeAdvisoryOnly()
    {
      return nightModeAdvisoryOnly;
    }

    @Override
    public String toString()
    {
      return "Result[buildable=" + buildable + ", filterGraph=" + filterGraph
          + ", reason=" + reason + ", nightModeAdvisoryOnly=" + nightModeAdvisoryOnly + "]";
    }
  }
}
