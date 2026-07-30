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

import java.util.Locale;

/**
 * A single 10-band graphic-EQ band: a center frequency (Hz) and a gain (dB),
 * matching the legacy {@code af_equalizer.c} 10-band layout (31.25/62.5/125/
 * 250/500/1000/2000/4000/8000/16000 Hz). Gain is always clamped to
 * [{@value #MIN_GAIN_DB}, {@value #MAX_GAIN_DB}] dB at construction so an
 * out-of-range or hostile client value can never reach the ffmpeg
 * filtergraph builder.
 *
 * <p>Immutable; two bands are {@link #equals(Object)} when their frequency
 * and (already-clamped) gain match exactly.
 */
public final class EqualizerBand implements Comparable<EqualizerBand>
{
  /** The 10 standard band-center frequencies (Hz), in ascending order. */
  public static final double[] STANDARD_FREQUENCIES_HZ = {
      31.25, 62.5, 125, 250, 500, 1000, 2000, 4000, 8000, 16000
  };

  public static final double MIN_GAIN_DB = -12.0;
  public static final double MAX_GAIN_DB = 12.0;

  /** Default filter Q (bandwidth) when a client omits it; matches a typical 10-band graphic-EQ response. */
  public static final double DEFAULT_Q = 1.0;

  private final String id;
  private final double frequencyHz;
  private final double gainDb;
  private final double q;
  private final boolean enabled;

  /** Convenience constructor: derives {@code id} from frequency, default Q, enabled. */
  public EqualizerBand(double frequencyHz, double gainDb)
  {
    this(null, frequencyHz, gainDb, DEFAULT_Q, true);
  }

  /**
   * Full constructor mirroring the canonical wire model {@code
   * EqualizerBand: { id, frequencyHz, gainDb, q?, enabled }}.
   *
   * @param id the band identifier (e.g. {@code "31.25"}); derived from
   *     {@code frequencyHz} when {@code null}/empty
   * @param q filter Q (bandwidth); non-positive/NaN falls back to {@link #DEFAULT_Q}
   */
  public EqualizerBand(String id, double frequencyHz, double gainDb, double q, boolean enabled)
  {
    this.frequencyHz = frequencyHz;
    this.id = (id == null || id.length() == 0) ? deriveId(frequencyHz) : id;
    this.gainDb = clampGainDb(gainDb);
    this.q = (Double.isNaN(q) || q <= 0.0) ? DEFAULT_Q : q;
    this.enabled = enabled;
  }

  /** Clamps a raw gain value to [{@link #MIN_GAIN_DB}, {@link #MAX_GAIN_DB}]; NaN clamps to 0.0. */
  public static double clampGainDb(double gainDb)
  {
    if (Double.isNaN(gainDb))
      return 0.0;
    return Math.max(MIN_GAIN_DB, Math.min(MAX_GAIN_DB, gainDb));
  }

  /** Derives a canonical id string (e.g. {@code "31.25"}, {@code "1000"}) from a frequency. */
  private static String deriveId(double frequencyHz)
  {
    if (frequencyHz == Math.rint(frequencyHz) && !Double.isInfinite(frequencyHz))
      return Long.toString((long) frequencyHz);
    return String.valueOf(frequencyHz);
  }

  public String getId()
  {
    return id;
  }

  public double getFrequencyHz()
  {
    return frequencyHz;
  }

  /** Always within [{@link #MIN_GAIN_DB}, {@link #MAX_GAIN_DB}]. */
  public double getGainDb()
  {
    return gainDb;
  }

  /** Filter Q (bandwidth); always positive, defaults to {@link #DEFAULT_Q}. */
  public double getQ()
  {
    return q;
  }

  /** {@code false} means this band should be omitted from the filtergraph entirely (per-band mute). */
  public boolean isEnabled()
  {
    return enabled;
  }

  /** {@code true} when this band contributes nothing (0 dB gain) -- omitted from the filtergraph even if enabled. */
  public boolean isFlat()
  {
    return gainDb == 0.0;
  }

  @Override
  public int compareTo(EqualizerBand o)
  {
    return Double.compare(frequencyHz, o.frequencyHz);
  }

  /** Equality is based on frequency + gain only (the two values that determine filter behavior). */
  @Override
  public boolean equals(Object o)
  {
    if (this == o) return true;
    if (!(o instanceof EqualizerBand)) return false;
    EqualizerBand other = (EqualizerBand) o;
    return Double.compare(frequencyHz, other.frequencyHz) == 0
        && Double.compare(gainDb, other.gainDb) == 0;
  }

  @Override
  public int hashCode()
  {
    long f = Double.doubleToLongBits(frequencyHz);
    long g = Double.doubleToLongBits(gainDb);
    int result = (int) (f ^ (f >>> 32));
    result = 31 * result + (int) (g ^ (g >>> 32));
    return result;
  }

  @Override
  public String toString()
  {
    return String.format(Locale.ROOT, "EqualizerBand[id=%s, f=%.2fHz, g=%.2fdB, q=%.2f, enabled=%s]",
        id, frequencyHz, gainDb, q, enabled);
  }
}
