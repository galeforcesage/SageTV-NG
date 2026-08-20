package sage.enhance;

import org.testng.annotations.Test;

import static org.testng.Assert.*;

/**
 * Tests for {@link GpuSnapshot}, whose job is to make "we don't know" distinct
 * from "there's plenty free". Confusing the two would wrongly admit sessions.
 */
public class GpuSnapshotTest
{
  @Test
  public void testUnavailableIsNotKnown()
  {
    assertFalse(GpuSnapshot.UNAVAILABLE.isKnown());
  }

  @Test
  public void testKnownSnapshotReportsFreeMemory()
  {
    GpuSnapshot s = new GpuSnapshot(0, 40, 30, 10, 2048, 8192, 1000L);
    assertTrue(s.isKnown());
    assertEquals(s.getFreeMemMB(), 6144L);
  }

  /**
   * The governor budgets against measured free VRAM precisely so another tenant
   * on a shared GPU shrinks our budget automatically.
   */
  @Test
  public void testFreeMemoryShrinksAsOtherTenantsAllocate()
  {
    GpuSnapshot quiet = new GpuSnapshot(0, 5, 0, 0, 500, 8192, 1000L);
    GpuSnapshot busy  = new GpuSnapshot(0, 5, 0, 0, 7000, 8192, 1000L);
    assertTrue(busy.getFreeMemMB() < quiet.getFreeMemMB());
    assertEquals(busy.getFreeMemMB(), 1192L);
  }

  @Test
  public void testFreeMemoryNeverGoesNegative()
  {
    GpuSnapshot s = new GpuSnapshot(0, 5, 0, 0, 9000, 8192, 1000L);
    assertEquals(s.getFreeMemMB(), 0L);
  }

  @Test
  public void testUnknownMemoryReportsUnknownNotZero()
  {
    GpuSnapshot s = new GpuSnapshot(0, 40, 30, 10, GpuSnapshot.UNKNOWN, 8192, 1000L);
    assertEquals(s.getFreeMemMB(), GpuSnapshot.UNKNOWN);
  }

  // ---- Video engine pressure ---------------------------------------------

  @Test
  public void testPressureTakesTheWorseOfEncodeAndDecode()
  {
    GpuSnapshot s = new GpuSnapshot(0, 20, 75, 30, 1000, 8192, 1000L);
    assertEquals(s.getVideoEnginePressurePct(), 75);
  }

  @Test
  public void testDecoderPressureCountsToo()
  {
    // NVDEC contention is real on the ATSC3 path, so decode load must not be
    // ignored just because the encoder looks idle.
    GpuSnapshot s = new GpuSnapshot(0, 20, 10, 90, 1000, 8192, 1000L);
    assertEquals(s.getVideoEnginePressurePct(), 90);
  }

  /**
   * Some consumer cards/drivers report "[N/A]" for the per-engine counters.
   * That must fall back to overall GPU utilization rather than reading as 0%,
   * which would look like idle headroom.
   */
  @Test
  public void testMissingEngineCountersFallBackToGpuUtil()
  {
    GpuSnapshot s = new GpuSnapshot(0, 65, GpuSnapshot.UNKNOWN, GpuSnapshot.UNKNOWN,
        1000, 8192, 1000L);
    assertEquals(s.getVideoEnginePressurePct(), 65);
  }

  @Test
  public void testZeroSampleTimeIsNotKnown()
  {
    GpuSnapshot s = new GpuSnapshot(0, 40, 30, 10, 2048, 8192, 0L);
    assertFalse(s.isKnown(), "a snapshot with no sample time must not be trusted");
  }
}
