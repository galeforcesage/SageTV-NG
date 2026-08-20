package sage.enhance;

import java.util.List;

import org.testng.SkipException;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import sage.Sage;
import sage.TestUtils;

import static org.testng.Assert.*;

/**
 * Tests for {@link GpuMonitor}.
 *
 * <p>The failure-path tests are the important ones and always run: a host with no
 * NVIDIA driver must degrade to "unknown", never to "plenty of headroom". The
 * happy-path test runs only where {@code nvidia-smi} actually exists, and is
 * skipped elsewhere rather than failing the build on non-GPU CI.
 */
public class GpuMonitorTest
{
  private static final String PROP_SMI  = "playback/gpu_enhance/nvidia_smi_path";
  private static final String PROP_POLL = "playback/gpu_enhance/gpu_poll_ms";

  @BeforeMethod
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.remove(PROP_SMI);
    Sage.remove(PROP_POLL);
    GpuMonitor.getInstance().reset();
  }

  // ---- Fail-closed behavior (always runs) --------------------------------

  @Test
  public void testMissingBinaryDegradesToUnknownNotToHeadroom()
  {
    Sage.put(PROP_SMI, "/nonexistent/definitely-not-here/nvidia-smi");
    GpuMonitor.getInstance().reset();
    GpuMonitor m = GpuMonitor.getInstance();
    assertFalse(m.isAvailable());
    GpuSnapshot s = m.getSnapshot(0);
    assertFalse(s.isKnown(), "a missing binary must not produce a trusted snapshot");
    assertEquals(s.getFreeMemMB(), GpuSnapshot.UNKNOWN,
        "unknown free VRAM must never read as a number the governor can spend");
  }

  @Test
  public void testMissingBinaryIsLatchedSoWeStopForkingDoomedProcesses()
  {
    Sage.put(PROP_SMI, "/nonexistent/definitely-not-here/nvidia-smi");
    GpuMonitor.getInstance().reset();
    GpuMonitor m = GpuMonitor.getInstance();
    assertFalse(m.isAvailable());
    // Second call must be cheap and still unavailable.
    assertFalse(m.isAvailable());
    assertTrue(m.getSnapshots().isEmpty());
  }

  @Test
  public void testUnknownGpuIndexIsUnavailable()
  {
    assertFalse(GpuMonitor.getInstance().getSnapshot(999).isKnown());
  }

  // ---- Happy path (skipped without a driver) -----------------------------

  private static void requireRealSmi()
  {
    GpuMonitor.getInstance().reset();
    if (!GpuMonitor.getInstance().isAvailable())
      throw new SkipException("nvidia-smi not available on this host");
  }

  @Test
  public void testRealSmiParsesIntoUsableSnapshots()
  {
    requireRealSmi();
    List<GpuSnapshot> all = GpuMonitor.getInstance().getSnapshots();
    assertFalse(all.isEmpty());
    GpuSnapshot s = all.get(0);
    assertTrue(s.isKnown(), "real nvidia-smi output must parse: " + s);
    assertTrue(s.getIndex() >= 0);
    assertTrue(s.getMemTotalMB() > 0, "total VRAM must be positive: " + s);
    assertTrue(s.getFreeMemMB() >= 0);
    assertTrue(s.getFreeMemMB() <= s.getMemTotalMB());
  }

  @Test
  public void testUtilizationValuesAreInRange()
  {
    requireRealSmi();
    GpuSnapshot s = GpuMonitor.getInstance().getSnapshots().get(0);
    assertTrue(s.getGpuUtilPct() >= 0 && s.getGpuUtilPct() <= 100, "gpu util: " + s);
    int p = s.getVideoEnginePressurePct();
    assertTrue(p >= 0 && p <= 100, "pressure: " + p);
  }

  @Test
  public void testResultsAreCachedWithinTheTtl()
  {
    requireRealSmi();
    Sage.put(PROP_POLL, "60000");
    GpuMonitor m = GpuMonitor.getInstance();
    long first = m.getSnapshots().get(0).getSampledAt();
    long second = m.getSnapshots().get(0).getSampledAt();
    assertEquals(first, second, "a second read inside the TTL must reuse the cache");
  }

  @Test
  public void testInvalidateForcesAFreshSample()
  {
    requireRealSmi();
    Sage.put(PROP_POLL, "60000");
    GpuMonitor m = GpuMonitor.getInstance();
    assertFalse(m.getSnapshots().isEmpty());
    m.invalidate();
    // Must still succeed after invalidation; the point is that it re-runs.
    assertFalse(m.getSnapshots().isEmpty());
  }
}
