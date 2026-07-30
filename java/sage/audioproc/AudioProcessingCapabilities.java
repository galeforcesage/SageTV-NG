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
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

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
 * 0}/empty) so a client that never sends this message -- or a legacy/STV
 * client -- is treated as fully incapable, which forces the resolver to
 * {@link AudioProcessingLocation#NONE}.
 *
 * <p><b>Hard LEGACY rule:</b> whenever {@link #getClientKind()} is {@link
 * ClientKind#LEGACY}, the constructor unconditionally forces {@link
 * #getSupportedLocations()} to {@code {NONE}} and both {@code
 * supportsClientDsp}/{@code supportsEqualizerUi} to {@code false},
 * regardless of whatever the builder was given -- a legacy SageTV/STV
 * client can never be offered CLIENT or SERVER audio processing.
 */
public final class AudioProcessingCapabilities
{
  /** Safe default: a client that has reported nothing can do nothing. */
  public static final AudioProcessingCapabilities NONE = new Builder().build();

  /** {@code min}/{@code max} dB bounds a client's local EQ UI allows, per canonical {@code gainRangeDb}. */
  public static final class GainRangeDb
  {
    public final double min;
    public final double max;

    public GainRangeDb(double min, double max)
    {
      this.min = min;
      this.max = max;
    }

    @Override
    public String toString()
    {
      return "[" + min + "," + max + "]";
    }
  }

  private final ClientKind clientKind;
  private final boolean clientSideEqSupported;
  private final boolean serverEqPlanSupported;
  private final boolean platformNightModeAvailable;
  private final int maxEqBands;
  private final boolean supportsEqualizerUi;
  private final boolean supportsDspActiveReporting;
  private final boolean supportsSettingsVersionSync;
  private final Set<AudioProcessingLocation> supportedLocations;
  private final GainRangeDb gainRangeDb;
  private final boolean supportsBiquad;
  private final boolean supportsAndroidEqualizer;
  private final boolean supportsNightMode;
  private final boolean supportsRemoteFocusNav;
  private final LocalPersistence localPersistence;

  private AudioProcessingCapabilities(Builder b)
  {
    boolean legacy = b.clientKind == ClientKind.LEGACY;
    this.clientKind = b.clientKind;
    this.clientSideEqSupported = !legacy && b.clientSideEqSupported;
    this.serverEqPlanSupported = !legacy && b.serverEqPlanSupported;
    this.platformNightModeAvailable = !legacy && b.platformNightModeAvailable;
    this.maxEqBands = Math.max(0, b.maxEqBands);
    this.supportsEqualizerUi = !legacy && b.supportsEqualizerUi;
    this.supportsDspActiveReporting = !legacy && b.supportsDspActiveReporting;
    this.supportsSettingsVersionSync = !legacy && b.supportsSettingsVersionSync;
    if (legacy)
    {
      this.supportedLocations = Collections.unmodifiableSet(EnumSet.of(AudioProcessingLocation.NONE));
    }
    else
    {
      Set<AudioProcessingLocation> locs = b.supportedLocations.isEmpty()
          ? EnumSet.of(AudioProcessingLocation.NONE)
          : EnumSet.copyOf(b.supportedLocations);
      this.supportedLocations = Collections.unmodifiableSet(locs);
    }
    this.gainRangeDb = b.gainRangeDb;
    this.supportsBiquad = !legacy && b.supportsBiquad;
    this.supportsAndroidEqualizer = !legacy && b.supportsAndroidEqualizer;
    this.supportsNightMode = !legacy && b.supportsNightMode;
    this.supportsRemoteFocusNav = !legacy && b.supportsRemoteFocusNav;
    this.localPersistence = b.localPersistence == null ? LocalPersistence.none : b.localPersistence;
  }

  /** The connecting client's platform/runtime; {@link ClientKind#LEGACY} forces every capability off. */
  public ClientKind getClientKind()
  {
    return clientKind;
  }

  /** {@code true} if the client itself can run a local EQ/DSP chain. Canonical alias: {@link #isSupportsClientDsp()}. */
  public boolean isClientSideEqSupported()
  {
    return clientSideEqSupported;
  }

  /** Canonical-named alias for {@link #isClientSideEqSupported()} (wire field {@code supportsClientDsp}). */
  public boolean isSupportsClientDsp()
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

  /** Maximum EQ bands the client can render locally; 0 if unknown/unsupported. Canonical alias: {@link #getSupportedBandCount()}. */
  public int getMaxEqBands()
  {
    return maxEqBands;
  }

  /** Canonical-named alias for {@link #getMaxEqBands()} (wire field {@code supportedBandCount}). */
  public int getSupportedBandCount()
  {
    return maxEqBands;
  }

  /** {@code true} if the client can present an EQ adjustment UI to the user at all. */
  public boolean isSupportsEqualizerUi()
  {
    return supportsEqualizerUi;
  }

  /** {@code true} if the client can send {@code AUDIO_PROCESSING_DSP_ACTIVE} updates. */
  public boolean isSupportsDspActiveReporting()
  {
    return supportsDspActiveReporting;
  }

  /** {@code true} if the client understands {@code AUDIO_PROCESSING_SETTINGS_VERSION_ACK}. */
  public boolean isSupportsSettingsVersionSync()
  {
    return supportsSettingsVersionSync;
  }

  /** The set of {@link AudioProcessingLocation} values this client can operate under; never empty (at least {@code NONE}). */
  public Set<AudioProcessingLocation> getSupportedLocations()
  {
    return supportedLocations;
  }

  /** The client's local EQ UI gain bounds, or {@code null} if not reported. */
  public GainRangeDb getGainRangeDb()
  {
    return gainRangeDb;
  }

  /** {@code true} if the client's local EQ engine is a Web Audio API biquad chain. */
  public boolean isSupportsBiquad()
  {
    return supportsBiquad;
  }

  /** {@code true} if the client's local EQ engine is Android's {@code AudioEffect} equalizer. */
  public boolean isSupportsAndroidEqualizer()
  {
    return supportsAndroidEqualizer;
  }

  /** {@code true} if the client supports any night-mode function locally. */
  public boolean isSupportsNightMode()
  {
    return supportsNightMode;
  }

  /** {@code true} if the client's EQ UI can be driven by a remote-control D-pad (10-foot UI consideration). */
  public boolean isSupportsRemoteFocusNav()
  {
    return supportsRemoteFocusNav;
  }

  /** Where the client persists its local settings across restarts; informational only. */
  public LocalPersistence getLocalPersistence()
  {
    return localPersistence;
  }

  @Override
  public String toString()
  {
    return "AudioProcessingCapabilities[clientKind=" + clientKind + ", clientSideEq=" + clientSideEqSupported
        + ", serverEqPlan=" + serverEqPlanSupported + ", platformNightMode=" + platformNightModeAvailable
        + ", maxEqBands=" + maxEqBands + ", supportedLocations=" + supportedLocations
        + ", localPersistence=" + localPersistence + "]";
  }

  public static Builder builder()
  {
    return new Builder();
  }

  public static final class Builder
  {
    private ClientKind clientKind = ClientKind.UNKNOWN;
    private boolean clientSideEqSupported = false;
    private boolean serverEqPlanSupported = false;
    private boolean platformNightModeAvailable = false;
    private int maxEqBands = 0;
    private boolean supportsEqualizerUi = false;
    private boolean supportsDspActiveReporting = false;
    private boolean supportsSettingsVersionSync = false;
    private final List<AudioProcessingLocation> supportedLocations = new ArrayList<AudioProcessingLocation>();
    private GainRangeDb gainRangeDb;
    private boolean supportsBiquad = false;
    private boolean supportsAndroidEqualizer = false;
    private boolean supportsNightMode = false;
    private boolean supportsRemoteFocusNav = false;
    private LocalPersistence localPersistence = LocalPersistence.none;

    public Builder clientKind(ClientKind v)
    {
      this.clientKind = v == null ? ClientKind.UNKNOWN : v;
      return this;
    }

    public Builder clientSideEqSupported(boolean v)
    {
      this.clientSideEqSupported = v;
      return this;
    }

    /** Canonical-named alias for {@link #clientSideEqSupported(boolean)}. */
    public Builder supportsClientDsp(boolean v)
    {
      return clientSideEqSupported(v);
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

    /** Canonical-named alias for {@link #maxEqBands(int)}. */
    public Builder supportedBandCount(int v)
    {
      return maxEqBands(v);
    }

    public Builder supportsEqualizerUi(boolean v)
    {
      this.supportsEqualizerUi = v;
      return this;
    }

    public Builder supportsDspActiveReporting(boolean v)
    {
      this.supportsDspActiveReporting = v;
      return this;
    }

    public Builder supportsSettingsVersionSync(boolean v)
    {
      this.supportsSettingsVersionSync = v;
      return this;
    }

    public Builder supportedLocations(List<AudioProcessingLocation> v)
    {
      this.supportedLocations.clear();
      if (v != null)
        this.supportedLocations.addAll(v);
      return this;
    }

    public Builder addSupportedLocation(AudioProcessingLocation v)
    {
      if (v != null)
        this.supportedLocations.add(v);
      return this;
    }

    public Builder gainRangeDb(GainRangeDb v)
    {
      this.gainRangeDb = v;
      return this;
    }

    public Builder supportsBiquad(boolean v)
    {
      this.supportsBiquad = v;
      return this;
    }

    public Builder supportsAndroidEqualizer(boolean v)
    {
      this.supportsAndroidEqualizer = v;
      return this;
    }

    public Builder supportsNightMode(boolean v)
    {
      this.supportsNightMode = v;
      return this;
    }

    public Builder supportsRemoteFocusNav(boolean v)
    {
      this.supportsRemoteFocusNav = v;
      return this;
    }

    public Builder localPersistence(LocalPersistence v)
    {
      this.localPersistence = v == null ? LocalPersistence.none : v;
      return this;
    }

    public AudioProcessingCapabilities build()
    {
      return new AudioProcessingCapabilities(this);
    }
  }
}
