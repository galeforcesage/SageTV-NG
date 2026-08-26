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
package sage.convert;

/**
 * Pure validation of a produced derivative against expectations. A derivative
 * only becomes "ready offline" after passing this check, so that a truncated,
 * corrupt, or wrong-duration output is never advertised to clients.
 *
 * <p>Checks, in order (first failure wins):
 * <ol>
 *   <li>the container opened/parsed;</li>
 *   <li>at least one video stream (when a video output was expected);</li>
 *   <li>at least one audio stream (when an audio output was expected);</li>
 *   <li>the probed duration is within tolerance of the expected duration —
 *       this is the truncation guard;</li>
 *   <li>the file is non-empty.</li>
 * </ol>
 *
 * <p>The duration tolerance defaults to the larger of a fixed floor and a
 * fraction of the expected duration, so both very short and very long content
 * get a sensible window.
 */
public final class DerivativeValidator
{
  /** Minimum absolute duration slack (ms) regardless of length. */
  static final long MIN_TOLERANCE_MS = 2000L;
  /** Fractional duration slack (of expected duration). */
  static final double TOLERANCE_FRACTION = 0.02; // 2%

  private DerivativeValidator() { }

  public static ValidationResult validate(ProbeResult probe, long expectedDurationMillis,
      boolean expectVideo, boolean expectAudio)
  {
    if (probe == null)
      return ValidationResult.fail("no probe result");
    if (!probe.isContainerOpened())
      return ValidationResult.fail("output container could not be opened");
    if (expectVideo && probe.getVideoStreams() < 1)
      return ValidationResult.fail("expected a video stream but none found");
    if (expectAudio && probe.getAudioStreams() < 1)
      return ValidationResult.fail("expected an audio stream but none found");
    if (probe.getByteSize() <= 0L)
      return ValidationResult.fail("output file is empty");

    if (expectedDurationMillis > 0L)
    {
      long tol = tolerance(expectedDurationMillis);
      long delta = Math.abs(probe.getDurationMillis() - expectedDurationMillis);
      if (delta > tol)
        return ValidationResult.fail("duration mismatch: expected ~"
            + expectedDurationMillis + "ms, got " + probe.getDurationMillis()
            + "ms (tolerance " + tol + "ms) — possible truncation");
    }
    return ValidationResult.pass();
  }

  static long tolerance(long expectedDurationMillis)
  {
    long frac = (long) (expectedDurationMillis * TOLERANCE_FRACTION);
    return Math.max(MIN_TOLERANCE_MS, frac);
  }
}
