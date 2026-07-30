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
import java.util.List;
import java.util.Locale;

/**
 * The audio DSP settings a client has requested for its current session, as
 * reported by an {@code AUDIO_PROCESSING_SETTINGS_STATE} message.
 *
 * <p>This is a pure value snapshot of what the client asked for -- it is
 * never persisted server-side (no database/property writes; see the
 * client-state registry, which holds it only in memory for the lifetime of
 * the connection). Any client-local-only fields (e.g. a night-mode
 * schedule/calendar) are intentionally not modeled here at all: unknown
 * incoming JSON fields are ignored by construction, satisfying "ignore
 * client-local night-schedule fields" without needing special-case code.
 *
 * <p>All numeric gains are clamped at construction time via {@link
 * EqualizerBand} and {@link #clampPreampDb(double)} so a hostile or buggy
 * client can never push an out-of-range value into the filtergraph builder.
 */
public final class AudioProcessingSettings
{
  public static final double MIN_PREAMP_DB = -12.0;
  public static final double MAX_PREAMP_DB = 12.0;

  /** Convenience constant: DSP fully disabled, no bands, no night mode. */
  public static final AudioProcessingSettings DISABLED =
      new Builder().location(AudioProcessingLocation.NONE).eqEnabled(false).build();

  /** Fixed canonical wire schema version for this model; always 1 in this protocol generation. */
  public static final int SCHEMA_VERSION = 1;

  private final AudioProcessingLocation location;
  private final boolean eqEnabled;
  private final List<EqualizerBand> bands;
  private final double preampDb;
  private final NightModeSettings nightMode;
  private final long clientSettingsVersion;
  private final String presetId;
  private final long updatedAtEpochMs;

  private AudioProcessingSettings(Builder b)
  {
    this.location = b.location;
    this.eqEnabled = b.eqEnabled;
    List<EqualizerBand> sorted = new ArrayList<EqualizerBand>(b.bands);
    Collections.sort(sorted);
    this.bands = Collections.unmodifiableList(sorted);
    this.preampDb = clampPreampDb(b.preampDb);
    this.nightMode = b.nightMode == null ? NightModeSettings.OFF : b.nightMode;
    this.clientSettingsVersion = b.clientSettingsVersion;
    this.presetId = b.presetId;
    this.updatedAtEpochMs = b.updatedAtEpochMs;
  }

  /** Always {@link #SCHEMA_VERSION} (1) for this protocol generation. */
  public int getSchemaVersion()
  {
    return SCHEMA_VERSION;
  }

  /** Client-chosen preset identifier, if any; the legacy PWA field {@code presetName} is accepted as an alias on intake. */
  public String getPresetId()
  {
    return presetId;
  }

  /** Client-reported wallclock epoch millis of the last local edit; 0 if not supplied. Server never derives/recomputes this. */
  public long getUpdatedAtEpochMs()
  {
    return updatedAtEpochMs;
  }

  public static double clampPreampDb(double preampDb)
  {
    if (Double.isNaN(preampDb))
      return 0.0;
    return Math.max(MIN_PREAMP_DB, Math.min(MAX_PREAMP_DB, preampDb));
  }

  public AudioProcessingLocation getLocation()
  {
    return location;
  }

  public boolean isEqEnabled()
  {
    return eqEnabled;
  }

  /** Bands sorted ascending by frequency; unmodifiable, never {@code null}. */
  public List<EqualizerBand> getBands()
  {
    return bands;
  }

  public double getPreampDb()
  {
    return preampDb;
  }

  public NightModeSettings getNightMode()
  {
    return nightMode;
  }

  public long getClientSettingsVersion()
  {
    return clientSettingsVersion;
  }

  /** Canonical alias for {@link #getClientSettingsVersion()} (wire field {@code settingsVersion}). */
  public long getSettingsVersion()
  {
    return clientSettingsVersion;
  }

  /**
   * {@code true} when there is nothing for any DSP stage (server or client)
   * to actually do: EQ disabled with no bands, no preamp, and night mode off.
   */
  public boolean isEffectivelyNoop()
  {
    return !eqEnabled && bands.isEmpty() && preampDb == 0.0 && nightMode.isOff();
  }

  /**
   * A deterministic content fingerprint of these settings (fixed field
   * order/number formatting), stable across JVM runs and process restarts.
   * Two logically-equal settings snapshots always produce the same hash.
   */
  public String computeSettingsHash()
  {
    StringBuilder sb = new StringBuilder();
    sb.append("loc=").append(location).append(';');
    sb.append("eq=").append(eqEnabled).append(';');
    sb.append("preamp=").append(String.format(Locale.ROOT, "%.2f", preampDb)).append(';');
    sb.append("bands=[");
    for (EqualizerBand band : bands)
    {
      sb.append(String.format(Locale.ROOT, "%.2f:%.2f,", band.getFrequencyHz(), band.getGainDb()));
    }
    sb.append("];");
    sb.append("night=").append(nightMode.getMode()).append(':').append(nightMode.getIntensity());
    return AudioProcessingHashing.sha256Hex16(sb.toString());
  }

  @Override
  public String toString()
  {
    return "AudioProcessingSettings[location=" + location + ", eqEnabled=" + eqEnabled
        + ", bands=" + bands.size() + ", preampDb=" + preampDb + ", nightMode=" + nightMode
        + ", clientSettingsVersion=" + clientSettingsVersion + ", presetId=" + presetId
        + ", updatedAtEpochMs=" + updatedAtEpochMs + "]";
  }

  public static Builder builder()
  {
    return new Builder();
  }

  public static final class Builder
  {
    private AudioProcessingLocation location = AudioProcessingLocation.NONE;
    private boolean eqEnabled = false;
    private final List<EqualizerBand> bands = new ArrayList<EqualizerBand>();
    private double preampDb = 0.0;
    private NightModeSettings nightMode = NightModeSettings.OFF;
    private long clientSettingsVersion = 0L;
    private String presetId;
    private long updatedAtEpochMs = 0L;

    public Builder location(AudioProcessingLocation location)
    {
      this.location = location == null ? AudioProcessingLocation.NONE : location;
      return this;
    }

    public Builder eqEnabled(boolean eqEnabled)
    {
      this.eqEnabled = eqEnabled;
      return this;
    }

    public Builder addBand(EqualizerBand band)
    {
      if (band != null)
        this.bands.add(band);
      return this;
    }

    public Builder bands(List<EqualizerBand> bands)
    {
      this.bands.clear();
      if (bands != null)
        this.bands.addAll(bands);
      return this;
    }

    public Builder preampDb(double preampDb)
    {
      this.preampDb = preampDb;
      return this;
    }

    public Builder nightMode(NightModeSettings nightMode)
    {
      this.nightMode = nightMode;
      return this;
    }

    public Builder clientSettingsVersion(long clientSettingsVersion)
    {
      this.clientSettingsVersion = clientSettingsVersion;
      return this;
    }

    /** Canonical alias for {@link #clientSettingsVersion(long)} (wire field {@code settingsVersion}). */
    public Builder settingsVersion(long settingsVersion)
    {
      return clientSettingsVersion(settingsVersion);
    }

    public Builder presetId(String presetId)
    {
      this.presetId = presetId;
      return this;
    }

    /** Alias for {@link #presetId(String)} matching the live PWA client's current field name. */
    public Builder presetName(String presetName)
    {
      return presetId(presetName);
    }

    public Builder updatedAtEpochMs(long updatedAtEpochMs)
    {
      this.updatedAtEpochMs = updatedAtEpochMs;
      return this;
    }

    public AudioProcessingSettings build()
    {
      return new AudioProcessingSettings(this);
    }
  }
}
