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
 * Night-mode (dynamic-range suppression) selection: a {@link NightModeMode}
 * plus a {@link NightModeIntensity} strength, matching the canonical wire
 * model {@code NightModeSettings: { enabled, effectiveNow, mode, intensity,
 * controllability }}. Immutable; {@code null} constructor arguments are
 * normalized to the safe defaults ({@link NightModeMode#OFF}, {@link
 * NightModeIntensity#LOW}, {@link NightModeControllability#APP_SCOPED}).
 */
public final class NightModeSettings
{
  /** Convenience constant for "night mode disabled". */
  public static final NightModeSettings OFF = new NightModeSettings(NightModeMode.OFF, NightModeIntensity.LOW);

  private final NightModeMode mode;
  private final NightModeIntensity intensity;
  private final boolean enabled;
  private final boolean effectiveNow;
  private final NightModeControllability controllability;

  /** Convenience constructor: {@code enabled} defaults to {@code mode != OFF}; {@code effectiveNow} defaults to false. */
  public NightModeSettings(NightModeMode mode, NightModeIntensity intensity)
  {
    this(mode, intensity, mode != null && mode != NightModeMode.OFF, false, NightModeControllability.APP_SCOPED);
  }

  public NightModeSettings(NightModeMode mode, NightModeIntensity intensity, boolean enabled,
      boolean effectiveNow, NightModeControllability controllability)
  {
    this.mode = mode == null ? NightModeMode.OFF : mode;
    this.intensity = intensity == null ? NightModeIntensity.LOW : intensity;
    this.enabled = enabled;
    this.effectiveNow = effectiveNow;
    this.controllability = controllability == null ? NightModeControllability.APP_SCOPED : controllability;
  }

  public NightModeMode getMode()
  {
    return mode;
  }

  public NightModeIntensity getIntensity()
  {
    return intensity;
  }

  /** The client's master night-mode switch; {@code false} means off regardless of {@link #getMode()}. */
  public boolean isEnabled()
  {
    return enabled;
  }

  /** {@code true} when the client reports night mode is currently in effect (e.g. within its schedule window). */
  public boolean isEffectiveNow()
  {
    return effectiveNow;
  }

  /** Who/what actually controls a {@link NightModeMode#PLATFORM_NIGHT_MODE} request on the client; diagnostics-only. */
  public NightModeControllability getControllability()
  {
    return controllability;
  }

  /** {@code true} when there is nothing for any DSP stage to do: disabled or mode is OFF. */
  public boolean isOff()
  {
    return !enabled || mode == NightModeMode.OFF;
  }

  @Override
  public boolean equals(Object o)
  {
    if (this == o) return true;
    if (!(o instanceof NightModeSettings)) return false;
    NightModeSettings other = (NightModeSettings) o;
    return mode == other.mode && intensity == other.intensity && enabled == other.enabled;
  }

  @Override
  public int hashCode()
  {
    int result = 31 * mode.hashCode() + intensity.hashCode();
    return 31 * result + (enabled ? 1 : 0);
  }

  @Override
  public String toString()
  {
    return "NightModeSettings[mode=" + mode + ", intensity=" + intensity + ", enabled=" + enabled
        + ", effectiveNow=" + effectiveNow + ", controllability=" + controllability + "]";
  }
}
