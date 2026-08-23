package sage.enhance.spi;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import sage.Sage;
import sage.TestUtils;
import sage.enhance.EnhancementTier;

import static org.testng.Assert.*;

/**
 * Registry selection, fallback, admission, and lifecycle-safety tests.
 */
public class ScaleProviderRegistryTest
{
  private static final String PROP_PROVIDER = "playback/gpu_enhance/scale_provider";
  private static final String PROP_MAX =
      "playback/gpu_enhance/scale/max_specialized_sessions";

  private ScaleProviderRegistry reg;

  @BeforeMethod
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.remove(PROP_PROVIDER);
    Sage.remove(PROP_MAX);
    reg = ScaleProviderRegistry.getInstance();
    reg.resetForTest();
    ScaleGovernor.getInstance().resetForTest();
  }

  @AfterMethod
  public void tearDown()
  {
    reg.resetForTest();
    ScaleGovernor.getInstance().resetForTest();
    Sage.remove(PROP_PROVIDER);
    Sage.remove(PROP_MAX);
  }

  private static ScaleRequest live()
  {
    return new ScaleRequest(EnhancementTier.ENHANCE_2160P, 3840, 2160, 1080, false,
        "scale_npp", ScaleRequest.Purpose.LIVE);
  }

  // ---- Fakes --------------------------------------------------------------

  /** A specialized provider that returns a renderable fragment. */
  private static final class FakeSpecialized implements ScaleProvider
  {
    private final String id;
    FakeSpecialized(String id) { this.id = id; }
    public String id() { return id; }
    public ScaleProviderCapabilities capabilities()
    { return new ScaleProviderCapabilities(id, true, true, 1); }
    public ScaleProviderAvailability probe(ScaleRequest r)
    { return ScaleProviderAvailability.available(); }
    public ScaleExecutionPlan plan(ScaleRequest r)
    { return new ScaleExecutionPlan(ExecutionForm.FFMPEG_FILTER, "fakevsr=3840:2160", "Fake"); }
  }

  private static ScaleProvider throwingProbe(final String id)
  {
    return new ScaleProvider() {
      public String id() { return id; }
      public ScaleProviderCapabilities capabilities()
      { return new ScaleProviderCapabilities(id, true, true, 1); }
      public ScaleProviderAvailability probe(ScaleRequest r) { throw new RuntimeException("boom"); }
      public ScaleExecutionPlan plan(ScaleRequest r)
      { return new ScaleExecutionPlan(ExecutionForm.FFMPEG_FILTER, "x=1:1", "x"); }
    };
  }

  private static ScaleProvider unrenderable(final String id)
  {
    return new ScaleProvider() {
      public String id() { return id; }
      public ScaleProviderCapabilities capabilities()
      { return new ScaleProviderCapabilities(id, true, true, 1); }
      public ScaleProviderAvailability probe(ScaleRequest r) { return ScaleProviderAvailability.available(); }
      public ScaleExecutionPlan plan(ScaleRequest r)
      { return new ScaleExecutionPlan(ExecutionForm.EXTERNAL_PROCESS, null, "ext"); }
    };
  }

  // ---- Selection & fallback ----------------------------------------------

  @Test
  public void defaultSelectsBuiltin()
  {
    ScaleSelection sel = reg.select(live());
    assertEquals(sel.getProviderId(), BuiltinScaleProvider.ID);
    assertNull(sel.getLease(), "built-in path holds no permit");
    assertFalse(sel.fellBackToBuiltin(), "choosing the default built-in is not a fallback");
    assertEquals(sel.getExecutionPlan().getFfmpegFilter(),
        "scale_npp=3840:2160:interp_algo=lanczos");
  }

  @Test
  public void unknownIdFallsBackToBuiltin()
  {
    Sage.put(PROP_PROVIDER, "does-not-exist");
    ScaleSelection sel = reg.select(live());
    assertEquals(sel.getProviderId(), BuiltinScaleProvider.ID);
    assertTrue(sel.fellBackToBuiltin());
  }

  @Test
  public void specializedProviderIsSelectedAndHoldsPermit()
  {
    ScaleProviderRegistration r = reg.register(new FakeSpecialized("nvidia-vsr"));
    Sage.put(PROP_PROVIDER, "nvidia-vsr");
    ScaleSelection sel = reg.select(live());
    assertEquals(sel.getProviderId(), "nvidia-vsr");
    assertEquals(sel.getExecutionPlan().getFfmpegFilter(), "fakevsr=3840:2160");
    assertNotNull(sel.getLease(), "a specialized provider holds a permit");
    assertEquals(ScaleGovernor.getInstance().activeCount(), 1);
    sel.getLease().close();
    assertEquals(ScaleGovernor.getInstance().activeCount(), 0);
    r.close();
  }

  @Test
  public void budgetExhaustedFallsBackWithoutPermit()
  {
    Sage.putInt(PROP_MAX, 1);
    ScaleProviderRegistration r = reg.register(new FakeSpecialized("nvidia-vsr"));
    Sage.put(PROP_PROVIDER, "nvidia-vsr");
    ScaleSelection first = reg.select(live());
    assertEquals(first.getProviderId(), "nvidia-vsr");
    ScaleSelection second = reg.select(live());
    assertEquals(second.getProviderId(), BuiltinScaleProvider.ID,
        "with the budget exhausted the second request falls back");
    assertTrue(second.fellBackToBuiltin());
    assertNull(second.getLease(), "a fallback must not retain a specialized permit");
    assertEquals(ScaleGovernor.getInstance().activeCount(), 1,
        "only the first, granted session holds capacity");
    first.getLease().close();
    r.close();
  }

  @Test
  public void probeThrowFallsBackAndLeaksNoPermit()
  {
    ScaleProviderRegistration r = reg.register(throwingProbe("nvidia-vsr"));
    Sage.put(PROP_PROVIDER, "nvidia-vsr");
    ScaleSelection sel = reg.select(live());
    assertEquals(sel.getProviderId(), BuiltinScaleProvider.ID);
    assertTrue(sel.fellBackToBuiltin());
    assertEquals(ScaleGovernor.getInstance().activeCount(), 0,
        "a provider that throws must not leak a permit");
    r.close();
  }

  @Test
  public void unrenderablePlanFallsBackAndReleasesPermit()
  {
    ScaleProviderRegistration r = reg.register(unrenderable("nvidia-vsr"));
    Sage.put(PROP_PROVIDER, "nvidia-vsr");
    ScaleSelection sel = reg.select(live());
    assertEquals(sel.getProviderId(), BuiltinScaleProvider.ID,
        "a not-yet-renderable execution form falls back in Phase 0");
    assertEquals(ScaleGovernor.getInstance().activeCount(), 0,
        "the acquired permit must be released on fallback");
    r.close();
  }

  // ---- Registration lifecycle --------------------------------------------

  @Test(expectedExceptions = IllegalStateException.class)
  public void duplicateIdRejected()
  {
    reg.register(new FakeSpecialized("dup"));
    reg.register(new FakeSpecialized("dup"));
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void cannotShadowBuiltinId()
  {
    reg.register(new FakeSpecialized(BuiltinScaleProvider.ID));
  }

  @Test
  public void unregisterPreventsNewUse()
  {
    ScaleProviderRegistration r = reg.register(new FakeSpecialized("nvidia-vsr"));
    Sage.put(PROP_PROVIDER, "nvidia-vsr");
    assertEquals(reg.select(live()).getProviderId(), "nvidia-vsr");
    r.close();
    assertFalse(reg.isRegistered("nvidia-vsr"));
    assertEquals(reg.select(live()).getProviderId(), BuiltinScaleProvider.ID,
        "after unregister, new selections fall back to built-in");
  }

  @Test
  public void closeOnlyRemovesOwnInstance()
  {
    ScaleProviderRegistration first = reg.register(new FakeSpecialized("nvidia-vsr"));
    first.close();
    // A different instance claims the same id afterwards.
    ScaleProviderRegistration second = reg.register(new FakeSpecialized("nvidia-vsr"));
    // Closing the stale handle must NOT evict the live second registration.
    first.close();
    assertTrue(reg.isRegistered("nvidia-vsr"),
        "a stale registration handle must not remove a same-id replacement");
    second.close();
  }

  @Test
  public void concurrentRegisterAndSelectAreSafe() throws Exception
  {
    Thread[] ts = new Thread[8];
    final boolean[] ok = { true };
    for (int i = 0; i < ts.length; i++)
    {
      final int n = i;
      ts[i] = new Thread(() -> {
        try
        {
          for (int k = 0; k < 50; k++)
          {
            ScaleProviderRegistration rr = reg.register(new FakeSpecialized("p-" + n + "-" + k));
            reg.select(live());
            rr.close();
          }
        }
        catch (Throwable t) { ok[0] = false; }
      });
    }
    for (Thread t : ts) t.start();
    for (Thread t : ts) t.join();
    assertTrue(ok[0], "concurrent register/select/unregister must not throw");
  }
}
