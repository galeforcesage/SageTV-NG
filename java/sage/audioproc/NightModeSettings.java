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
 * plus a {@link NightModeIntensity} strength. Immutable; {@code null}
 * constructor arguments are normalized to the safe defaults ({@link
 * NightModeMode#OFF}, {@link NightModeIntensity#LOW}).
 */
public final class NightModeSettings
{
  /** Convenience constant for "night mode disabled". */
  public static final NightModeSettings OFF = new NightModeSettings(NightModeMode.OFF, NightModeIntensity.LOW);

  private final NightModeMode mode;
  private final NightModeIntensity intensity;

  public NightModeSettings(NightModeMode mode, NightModeIntensity intensity)
  {
    this.mode = mode == null ? NightModeMode.OFF : mode;
    this.intensity = intensity == null ? NightModeIntensity.LOW : intensity;
  }

  public NightModeMode getMode()
  {
    return mode;
  }

  public NightModeIntensity getIntensity()
  {
    return intensity;
  }

  public boolean isOff()
  {
    return mode == NightModeMode.OFF;
  }

  @Override
  public boolean equals(Object o)
  {
    if (this == o) return true;
    if (!(o instanceof NightModeSettings)) return false;
    NightModeSettings other = (NightModeSettings) o;
    return mode == other.mode && intensity == other.intensity;
  }

  @Override
  public int hashCode()
  {
    return 31 * mode.hashCode() + intensity.hashCode();
  }

  @Override
  public String toString()
  {
    return "NightModeSettings[mode=" + mode + ", intensity=" + intensity + "]";
  }
}
