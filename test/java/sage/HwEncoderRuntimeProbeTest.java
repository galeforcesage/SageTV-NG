/*
 * Copyright 2026 The SageTV Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sage;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Tests for the functional GPU-enhancement probe.
 *
 * The point of this probe is that {@code ffmpeg -encoders} lies: a build linked
 * against a newer NVENC SDK than the installed driver supports still lists
 * {@code hevc_nvenc}, then fails to open it at stream time. These tests pin the
 * fail-closed behavior and the opt-out knob.
 */
public class HwEncoderRuntimeProbeTest
{
  private static final String PROP_PROBE_FFMPEG = "multimedia/hwaccel/probe_ffmpeg";
  private static final String PROP_RUNTIME      = "multimedia/hwaccel/enhance_runtime_probe";

  @BeforeMethod
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    HwEncoder.clearProbeCaches();
  }

  @AfterMethod
  public void tearDown()
  {
    Sage.remove(PROP_PROBE_FFMPEG);
    Sage.remove(PROP_RUNTIME);
    HwEncoder.clearProbeCaches();
  }

  /** A binary that doesn't exist must fail closed, not throw. */
  @Test
  public void testMissingBinaryFailsClosed()
  {
    Sage.put(PROP_PROBE_FFMPEG, "definitely-not-a-real-ffmpeg-binary-xyz");
    assertFalse(HwEncoder.gpuEnhanceRuntimeOk("definitely-not-a-real-ffmpeg-binary-xyz"),
        "A nonexistent ffmpeg must not report the enhancement pipeline as usable");
    assertFalse(HwEncoder.gpuEnhanceSupported(),
        "gpuEnhanceSupported must be false when the binary is missing");
  }

  /** The opt-out knob short-circuits the probe without running anything. */
  @Test
  public void testProbeCanBeDisabled()
  {
    Sage.put(PROP_RUNTIME, "false");
    assertTrue(HwEncoder.gpuEnhanceRuntimeOk("definitely-not-a-real-ffmpeg-binary-xyz"),
        "With the runtime probe disabled the check must defer to the listing probes");
  }

  /**
   * The result must be cached: a second call on the same binary must not shell
   * out again. Measured indirectly — the second call is dramatically faster than
   * a process spawn, and must agree with the first.
   */
  @Test
  public void testResultIsCached()
  {
    String bin = "definitely-not-a-real-ffmpeg-binary-xyz";
    boolean first = HwEncoder.gpuEnhanceRuntimeOk(bin);
    long t0 = System.nanoTime();
    boolean second = HwEncoder.gpuEnhanceRuntimeOk(bin);
    long elapsedMicros = (System.nanoTime() - t0) / 1000L;
    assertEquals(second, first, "Cached probe result must agree with the first result");
    assertTrue(elapsedMicros < 50000L,
        "Second call should hit the cache, took " + elapsedMicros + "us");
  }

  /** Null/empty binary falls back to the configured property, still fail-closed. */
  @Test
  public void testNullBinaryUsesPropertyAndFailsClosed()
  {
    Sage.put(PROP_PROBE_FFMPEG, "definitely-not-a-real-ffmpeg-binary-xyz");
    assertFalse(HwEncoder.gpuEnhanceRuntimeOk(null),
        "Null binary must resolve via the property and still fail closed");
    assertFalse(HwEncoder.gpuEnhanceRuntimeOk(""),
        "Empty binary must resolve via the property and still fail closed");
  }

  /**
   * Against whatever ffmpeg is actually on this host, the probe must return a
   * definite answer without throwing, and must never claim support when the
   * listing probes already say the parts are missing.
   */
  @Test
  public void testAgainstRealHostBinaryIsConsistent()
  {
    Sage.put(PROP_PROBE_FFMPEG, "ffmpeg");
    boolean listingSaysScaler = HwEncoder.cudaScaler() != null;
    boolean listingSaysDeint  = HwEncoder.cudaDeinterlacer(false) != null;
    boolean supported = HwEncoder.gpuEnhanceSupported();

    if (!listingSaysScaler || !listingSaysDeint)
      assertFalse(supported,
          "Enhancement cannot be supported when a required filter is absent");

    // The functional probe is allowed to be stricter than the listing probes,
    // never more permissive.
    if (supported)
      assertTrue(HwEncoder.gpuEnhanceRuntimeOk("ffmpeg"),
          "gpuEnhanceSupported must imply the functional probe passed");
  }
}
