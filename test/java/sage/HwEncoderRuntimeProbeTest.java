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
   * out again. Measured indirectly â€” the second call is dramatically faster than
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

  /**
   * The probe must never depend on the lavfi input device.
   *
   * SageTV ships a custom ffmpeg built without libavdevice, so `-f lavfi`
   * fails with "Unknown input format: 'lavfi'". A lavfi-based probe reported
   * "GPU enhancement unsupported" on a host whose GPU pipeline was verified
   * working (real 1080i -> 3840x2160 HEVC at 59.94fps on an RTX 5080),
   * silently disabling the feature. Confirmed on that host: `ffmpeg -devices`
   * printed an empty list.
   */
  @Test
  public void testProbeDoesNotUseLavfi()
  {
    java.util.List<String> cmd = HwEncoder.buildRuntimeProbeCommand("/opt/sagetv/server/ffmpeg", "scale_npp", "hevc_nvenc");
    for (String a : cmd)
      assertFalse(a.contains("lavfi"), "probe must not use the lavfi input device, found: " + a);
    assertTrue(cmd.contains("rawvideo"), "probe should synthesize input via rawvideo, got: " + cmd);
    assertTrue(cmd.contains("-"), "rawvideo input should be read from stdin");
  }

  /** The probe must exercise a real scale and a real NVENC open. */
  @Test
  public void testProbeExercisesScalerAndEncoder()
  {
    java.util.List<String> cmd = HwEncoder.buildRuntimeProbeCommand("ffmpeg", "scale_cuda", "hevc_nvenc");
    String joined = String.join(" ", cmd);
    assertTrue(joined.contains("hwupload_cuda,scale_cuda="), "probe must upload then scale on the GPU: " + joined);
    assertTrue(joined.contains("-c:v hevc_nvenc"), "probe must actually open the NVENC encoder: " + joined);
    assertTrue(joined.contains("-init_hw_device cuda=cu:0"), "probe must initialize a CUDA device: " + joined);

    // The scale target must differ from the source, or the scaler is a no-op
    // and the probe would pass on a host whose scaler is broken.
    assertFalse(joined.contains("scale_cuda=320:180"), "probe must scale to a different size than the source");
  }

  /** Frame payload must match the declared geometry, or ffmpeg desyncs. */
  @Test
  public void testProbeFrameSizeMatchesGeometry()
  {
    java.util.List<String> cmd = HwEncoder.buildRuntimeProbeCommand("ffmpeg", "scale_npp", "hevc_nvenc");
    int i = cmd.indexOf("-s");
    assertTrue(i >= 0 && i + 1 < cmd.size(), "probe should declare a frame size");
    String[] wh = cmd.get(i + 1).split("x");
    int expected = Integer.parseInt(wh[0]) * Integer.parseInt(wh[1]) * 3 / 2;
    assertEquals(HwEncoder.probeFrameBytes(), expected,
        "yuv420p frame payload must match the -s geometry the probe declares");
  }
}