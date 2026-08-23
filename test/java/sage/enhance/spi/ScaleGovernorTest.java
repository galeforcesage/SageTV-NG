package sage.enhance.spi;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import sage.Sage;
import sage.TestUtils;
import sage.enhance.EnhancementTier;

import static org.testng.Assert.*;

/**
 * Unit tests for the specialized-scale admission budget.
 */
public class ScaleGovernorTest
{
  private static final String PROP_MAX =
      "playback/gpu_enhance/scale/max_specialized_sessions";

  @BeforeMethod
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.remove(PROP_MAX);
    ScaleGovernor.getInstance().resetForTest();
  }

  private static ScaleRequest live()
  {
    return new ScaleRequest(EnhancementTier.ENHANCE_2160P, 3840, 2160, 1080, false,
        "scale_npp", ScaleRequest.Purpose.LIVE);
  }

  private static ScaleRequest probe()
  {
    return new ScaleRequest(EnhancementTier.ENHANCE_2160P, 3840, 2160, 1080, false,
        "scale_npp", ScaleRequest.Purpose.PROBE);
  }

  @Test
  public void defaultCeilingIsOne()
  {
    ScaleGovernor g = ScaleGovernor.getInstance();
    ScaleGovernor.Lease a = g.acquire("p", live());
    assertNotNull(a, "first specialized permit should be granted");
    assertEquals(g.activeCount(), 1, "one permit held");
    ScaleGovernor.Lease b = g.acquire("p", live());
    assertNull(b, "second permit should be denied at default ceiling of 1");
  }

  @Test
  public void releaseIsIdempotent()
  {
    ScaleGovernor g = ScaleGovernor.getInstance();
    ScaleGovernor.Lease a = g.acquire("p", live());
    assertEquals(g.activeCount(), 1);
    a.close();
    assertEquals(g.activeCount(), 0, "permit released");
    a.close();
    a.close();
    assertEquals(g.activeCount(), 0, "double/triple close must not underflow the budget");
  }

  @Test
  public void configurableCeiling()
  {
    Sage.putInt(PROP_MAX, 2);
    ScaleGovernor g = ScaleGovernor.getInstance();
    assertNotNull(g.acquire("p", live()));
    assertNotNull(g.acquire("p", live()));
    assertNull(g.acquire("p", live()), "third denied at ceiling of 2");
    assertEquals(g.activeCount(), 2);
  }

  @Test
  public void probeLeaseHoldsNoCapacity()
  {
    ScaleGovernor g = ScaleGovernor.getInstance();
    ScaleGovernor.Lease p = g.acquire("p", probe());
    assertNotNull(p, "a probe is always granted a lease");
    assertTrue(p.isNoop(), "probe lease holds no counted capacity");
    assertEquals(g.activeCount(), 0, "probe must not consume the live budget");
    // A live permit is still available alongside a probe.
    assertNotNull(g.acquire("p", live()));
    assertEquals(g.activeCount(), 1);
    p.close();
    assertEquals(g.activeCount(), 1, "closing a probe lease changes nothing");
  }

  @Test
  public void zeroCeilingDeniesAll()
  {
    Sage.putInt(PROP_MAX, 0);
    ScaleGovernor g = ScaleGovernor.getInstance();
    assertNull(g.acquire("p", live()), "a ceiling of 0 denies every live permit");
  }
}
