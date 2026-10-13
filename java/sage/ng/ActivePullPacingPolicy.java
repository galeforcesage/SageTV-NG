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

import java.util.List;

/**
 * Media-time pacing policy for active Protocol 2.1 pull transcodes.
 *
 * <p>The existing transcode ring and MediaServer READ flow control continue to
 * regulate bytes. This policy limits how far FFmpeg may advance through a
 * growing source while still allowing a bounded startup reservoir and bounded
 * catch-up after downstream backpressure clears.
 */
public final class ActivePullPacingPolicy
{
  public static final double DEFAULT_READ_RATE = 1.0;
  public static final double DEFAULT_INITIAL_BURST_SECONDS = 6.0;
  public static final double DEFAULT_CATCHUP_RATE = 1.5;

  private static final double MIN_READ_RATE = 0.5;
  private static final double MAX_READ_RATE = 2.0;
  private static final double MIN_INITIAL_BURST_SECONDS = 0.0;
  private static final double MAX_INITIAL_BURST_SECONDS = 30.0;
  private static final double MAX_CATCHUP_RATE = 4.0;
  private static final ActivePullPacingPolicy DISABLED =
      new ActivePullPacingPolicy(false, 0.0, 0.0, 0.0);

  private final boolean enabled;
  private final double readRate;
  private final double initialBurstSeconds;
  private final double catchupRate;

  private ActivePullPacingPolicy(boolean enabled, double readRate,
      double initialBurstSeconds, double catchupRate)
  {
    this.enabled = enabled;
    this.readRate = readRate;
    this.initialBurstSeconds = initialBurstSeconds;
    this.catchupRate = catchupRate;
  }

  public static ActivePullPacingPolicy resolve(boolean propertyEnabled,
      boolean activeSource, boolean bufferedPull, boolean protocol21Setup,
      boolean segmentedOutput, String readRateValue, String initialBurstValue,
      String catchupRateValue)
  {
    if (!propertyEnabled || !activeSource || !bufferedPull || !protocol21Setup
        || segmentedOutput)
      return DISABLED;

    double readRate = clamp(parse(readRateValue, DEFAULT_READ_RATE),
        MIN_READ_RATE, MAX_READ_RATE);
    double initialBurst = clamp(parse(initialBurstValue, DEFAULT_INITIAL_BURST_SECONDS),
        MIN_INITIAL_BURST_SECONDS, MAX_INITIAL_BURST_SECONDS);
    double catchupRate = clamp(parse(catchupRateValue, DEFAULT_CATCHUP_RATE),
        readRate, MAX_CATCHUP_RATE);
    return new ActivePullPacingPolicy(true, readRate, initialBurst, catchupRate);
  }

  private static double parse(String value, double fallback)
  {
    if (value == null) return fallback;
    try
    {
      double parsed = Double.parseDouble(value.trim());
      return Double.isFinite(parsed) ? parsed : fallback;
    }
    catch (NumberFormatException e)
    {
      return fallback;
    }
  }

  private static double clamp(double value, double min, double max)
  {
    return Math.max(min, Math.min(max, value));
  }

  public boolean isEnabled()
  {
    return enabled;
  }

  public double getReadRate()
  {
    return readRate;
  }

  public double getInitialBurstSeconds()
  {
    return initialBurstSeconds;
  }

  public double getCatchupRate()
  {
    return catchupRate;
  }

  public void appendInputArguments(List<String> arguments)
  {
    if (!enabled) return;
    arguments.add("-readrate");
    arguments.add(format(readRate));
    arguments.add("-readrate_initial_burst");
    arguments.add(format(initialBurstSeconds));
    arguments.add("-readrate_catchup");
    arguments.add(format(catchupRate));
  }

  /**
   * Upper media-time envelope used by deterministic policy tests and
   * diagnostics. FFmpeg owns the actual timestamp clock.
   */
  public double maxInputMediaSeconds(double elapsedSeconds, boolean recovering)
  {
    if (!enabled) return Double.POSITIVE_INFINITY;
    return initialBurstSeconds + Math.max(0.0, elapsedSeconds)
        * (recovering ? catchupRate : readRate);
  }

  private static String format(double value)
  {
    if (value == Math.rint(value))
      return Long.toString((long) value);
    return Double.toString(value);
  }

  @Override
  public String toString()
  {
    return enabled
        ? "readrate=" + format(readRate) + " burst=" + format(initialBurstSeconds)
            + "s catchup=" + format(catchupRate)
        : "disabled";
  }
}
