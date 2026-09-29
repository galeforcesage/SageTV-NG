package sage.enhance.spi;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import sage.Sage;
import sage.TestUtils;
import sage.enhance.EnhancementTier;
import sage.enhance.ExternalAdmissionAuthority;
import sage.enhance.GpuGovernor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.*;

/**
 * Registry selection, fallback, admission, and lifecycle-safety tests.
 */
public class ScaleProviderRegistryTest
{
  private static final String PROP_PROVIDER = "playback/gpu_enhance/scale_provider";
  private static final String PROP_MAX =
      "playback/gpu_enhance/scale/max_specialized_sessions";
  private static final String PROP_CUDA_LANCZOS =
      "playback/gpu_enhance/scale_cuda_lanczos_provider";

  private ScaleProviderRegistry reg;

  @BeforeMethod
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.remove(PROP_PROVIDER);
    Sage.remove(PROP_MAX);
    Sage.remove(PROP_CUDA_LANCZOS);
    reg = ScaleProviderRegistry.getInstance();
    reg.resetForTest();
    ScaleGovernor.getInstance().resetForTest();
    GpuGovernor.getInstance().setExternalAuthority(null);
    ScaleWarmupCache.getInstance().resetForTest();
  }

  @AfterMethod
  public void tearDown()
  {
    reg.resetForTest();
    ScaleGovernor.getInstance().resetForTest();
    GpuGovernor.getInstance().setExternalAuthority(null);
    ScaleWarmupCache.getInstance().resetForTest();
    Sage.remove(PROP_PROVIDER);
    Sage.remove(PROP_MAX);
    Sage.remove(PROP_CUDA_LANCZOS);
  }

  private static ScaleRequest live()
  {
    return new ScaleRequest(EnhancementTier.ENHANCE_2160P, 3840, 2160, 1080, false,
        "scale_npp", ScaleRequest.Purpose.LIVE);
  }

  private static ExternalAdmissionAuthority authority()
  {
    return new ExternalAdmissionAuthority()
    {
      public String name() { return "test"; }
      public Decision admit(String id, EnhancementTier desired, int gpu, long kbps,
                            boolean offline)
      { return Decision.grant(desired); }
      public void renew(String id) {}
      public void release(String id) {}
      public boolean isReclaimed(String id) { return false; }
    };
  }

  // ---- Fakes --------------------------------------------------------------

  /** A specialized provider that returns a renderable fragment. */
  private static class FakeSpecialized implements ScaleProvider
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
  public void defaultUsesCudaLanczosWhenScalerAvailable()
  {
    // No plugin provider registered; the request advertises a CUDA scaler (as the
    // live plan path does via HwEncoder.cudaScaler()). The always-present
    // CUDA-Lanczos fallback renders it, so the deployment upscales in real time
    // rather than delivering source.
    ScaleSelection sel = reg.select(live());
    assertEquals(sel.getProviderId(), CudaLanczosScaleProvider.ID);
    assertNull(sel.getLease(), "CUDA-Lanczos is not specialized, so it holds no permit");
    assertFalse(sel.fellBackToBuiltin(), "choosing CUDA-Lanczos is a real selection, not a fallback");
    assertEquals(sel.getExecutionPlan().getFfmpegFilter(),
        "scale_npp=3840:2160:interp_algo=lanczos");
    assertTrue(sel.getExecutionPlan().isRenderable());
  }

  @Test
  public void deliversSourceWhenNoUpscalerUsable()
  {
    // CUDA-Lanczos disabled and nothing registered: the chain is empty, so the
    // built-in passthrough delivers the source and the client scales.
    Sage.put(PROP_CUDA_LANCZOS, "off");
    ScaleSelection sel = reg.select(live());
    assertEquals(sel.getProviderId(), BuiltinScaleProvider.ID);
    assertNull(sel.getLease(), "built-in path holds no permit");
    assertTrue(sel.fellBackToBuiltin(), "an exhausted chain resolves to the source-delivering built-in");
    assertFalse(sel.getExecutionPlan().isRenderable(),
        "an upscale via the passthrough is non-renderable, so the pipeline skips it");
  }

  @Test
  public void deinterlaceOnlyUsesBuiltin()
  {
    // A non-upscaling request goes straight to the built-in; the chain governs
    // the scale stage only and never spawns an upscaler to do nothing.
    ScaleRequest deint = new ScaleRequest(EnhancementTier.DEINTERLACE_ONLY, 1920, 1080,
        1080, true, "scale_npp", ScaleRequest.Purpose.LIVE);
    ScaleSelection sel = reg.select(deint);
    assertEquals(sel.getProviderId(), BuiltinScaleProvider.ID);
    assertFalse(sel.fellBackToBuiltin(), "the built-in is the correct choice for a deinterlace-only request");
  }

  @Test
  public void selectedProviderCanUpscaleReflectsChain()
  {
    // With CUDA-Lanczos disabled and nothing registered, no server upscaling.
    Sage.put(PROP_CUDA_LANCZOS, "off");
    assertFalse(reg.selectedProviderCanUpscale());
    // A registered specialized upscaler flips it on regardless of CUDA-Lanczos.
    ScaleProviderRegistration r = reg.register(new FakeSpecialized("nvidia-vsr"));
    assertTrue(reg.selectedProviderCanUpscale());
    r.close();
    assertFalse(reg.selectedProviderCanUpscale(),
        "after the specialized provider unregisters and CUDA-Lanczos is off, upscaling is unavailable");
  }

  @Test
  public void unknownPreferenceHeadIsIgnored()
  {
    // An unknown soft-preference head neither pins nor breaks selection; the
    // chain still resolves to the CUDA-Lanczos fallback.
    Sage.put(PROP_PROVIDER, "does-not-exist");
    ScaleSelection sel = reg.select(live());
    assertEquals(sel.getProviderId(), CudaLanczosScaleProvider.ID);
    assertFalse(sel.fellBackToBuiltin());
  }

  @Test
  public void specializedProviderIsSelectedAndHoldsPermit()
  {
    ScaleProviderRegistration r = reg.register(new FakeSpecialized("nvidia-vsr"));
    ScaleSelection sel = reg.select(live());
    assertEquals(sel.getProviderId(), "nvidia-vsr",
        "a registered specialized upscaler is the head of the chain");
    assertEquals(sel.getExecutionPlan().getFfmpegFilter(), "fakevsr=3840:2160");
    assertNotNull(sel.getLease(), "a specialized provider holds a permit");
    assertEquals(ScaleGovernor.getInstance().activeCount(), 1);
    sel.getLease().close();
    assertEquals(ScaleGovernor.getInstance().activeCount(), 0);
    r.close();
  }

  @Test
  public void explicitExternalGrantAllowsSpecializedProvider()
  {
    ScaleProviderRegistration r = reg.register(new FakeSpecialized("nvidia-vsr"));
    ScaleSelection sel = reg.select(live(), true);
    assertEquals(sel.getProviderId(), "nvidia-vsr");
    assertNotNull(sel.getLease());
    sel.getLease().close();
    r.close();
  }

  @Test
  public void externalDenySkipsSpecializedProviderAndUsesLanczos()
  {
    final AtomicInteger probes = new AtomicInteger();
    ScaleProvider vsr = new ScaleProvider() {
      public String id() { return "nvidia-vsr"; }
      public ScaleProviderCapabilities capabilities()
      { return new ScaleProviderCapabilities(id(), true, true, 1); }
      public ScaleProviderAvailability probe(ScaleRequest request)
      { probes.incrementAndGet(); return ScaleProviderAvailability.available(); }
      public ScaleExecutionPlan plan(ScaleRequest request)
      { return new ScaleExecutionPlan(ExecutionForm.FFMPEG_FILTER, "vsr=1", "VSR"); }
    };
    ScaleProviderRegistration r = reg.register(vsr);

    ScaleSelection sel = reg.select(live(), false);

    assertEquals(sel.getProviderId(), CudaLanczosScaleProvider.ID);
    assertEquals(probes.get(), 0, "denial must skip even the neural provider probe");
    assertNull(sel.getLease());
    r.close();
  }

  @Test
  public void externalDenyWithNoLanczosDeliversSource()
  {
    Sage.put(PROP_CUDA_LANCZOS, "off");
    ScaleProviderRegistration r = reg.register(new FakeSpecialized("nvidia-vsr"));

    ScaleSelection sel = reg.select(live(), false);

    assertEquals(sel.getProviderId(), BuiltinScaleProvider.ID);
    assertFalse(sel.getExecutionPlan().isRenderable());
    assertEquals(ScaleGovernor.getInstance().activeCount(), 0);
    r.close();
  }

  @Test
  public void authorityBlocksWarmupUnlessProviderOwnsWarmAdmission()
      throws Exception
  {
    final AtomicInteger warmups = new AtomicInteger();
    ScaleProvider unmanaged = new FakeSpecialized("unmanaged")
    {
      public WarmContext warmup(ScaleRequest request, WarmupBudget budget)
      {
        warmups.incrementAndGet();
        return null;
      }
    };
    ScaleProviderRegistration registration = reg.register(unmanaged);
    GpuGovernor.getInstance().setExternalAuthority(authority());

    reg.warmupSelectedProvider(live());
    Thread.sleep(50L);

    assertEquals(warmups.get(), 0);
    registration.close();
  }

  @Test
  public void selfGovernedProviderMayWarmUnderAuthority()
      throws Exception
  {
    final java.util.concurrent.CountDownLatch warmed =
        new java.util.concurrent.CountDownLatch(1);
    ScaleProvider managed = new FakeSpecialized("managed")
    {
      public boolean managesWarmupAdmission() { return true; }
      public WarmContext warmup(ScaleRequest request, WarmupBudget budget)
      {
        warmed.countDown();
        return null;
      }
    };
    ScaleProviderRegistration registration = reg.register(managed);
    GpuGovernor.getInstance().setExternalAuthority(authority());

    reg.warmupSelectedProvider(live());

    assertTrue(warmed.await(1, java.util.concurrent.TimeUnit.SECONDS));
    registration.close();
  }

  @Test
  public void budgetExhaustedFallsThroughToCudaLanczos()
  {
    Sage.putInt(PROP_MAX, 1);
    ScaleProviderRegistration r = reg.register(new FakeSpecialized("nvidia-vsr"));
    ScaleSelection first = reg.select(live());
    assertEquals(first.getProviderId(), "nvidia-vsr");
    ScaleSelection second = reg.select(live());
    assertEquals(second.getProviderId(), CudaLanczosScaleProvider.ID,
        "with the specialized budget exhausted, selection falls through to CUDA-Lanczos, not source");
    assertFalse(second.fellBackToBuiltin());
    assertNull(second.getLease(), "the CUDA-Lanczos fallback holds no specialized permit");
    assertEquals(ScaleGovernor.getInstance().activeCount(), 1,
        "only the first, granted session holds capacity");
    first.getLease().close();
    r.close();
  }

  @Test
  public void probeThrowFallsThroughAndLeaksNoPermit()
  {
    ScaleProviderRegistration r = reg.register(throwingProbe("nvidia-vsr"));
    ScaleSelection sel = reg.select(live());
    assertEquals(sel.getProviderId(), CudaLanczosScaleProvider.ID,
        "a specialized provider that throws falls through to CUDA-Lanczos");
    assertFalse(sel.fellBackToBuiltin());
    assertEquals(ScaleGovernor.getInstance().activeCount(), 0,
        "a provider that throws must not leak a permit");
    r.close();
  }

  @Test
  public void unrenderablePlanFallsThroughAndReleasesPermit()
  {
    ScaleProviderRegistration r = reg.register(unrenderable("nvidia-vsr"));
    ScaleSelection sel = reg.select(live());
    assertEquals(sel.getProviderId(), CudaLanczosScaleProvider.ID,
        "a not-yet-renderable specialized plan falls through to CUDA-Lanczos");
    assertEquals(ScaleGovernor.getInstance().activeCount(), 0,
        "the acquired permit must be released on fall-through");
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

  @Test(expectedExceptions = IllegalStateException.class)
  public void cannotShadowCudaLanczosId()
  {
    reg.register(new FakeSpecialized(CudaLanczosScaleProvider.ID));
  }

  @Test
  public void unregisterPreventsNewUse()
  {
    ScaleProviderRegistration r = reg.register(new FakeSpecialized("nvidia-vsr"));
    assertEquals(reg.select(live()).getProviderId(), "nvidia-vsr");
    r.close();
    assertFalse(reg.isRegistered("nvidia-vsr"));
    assertEquals(reg.select(live()).getProviderId(), CudaLanczosScaleProvider.ID,
        "after unregister, new selections fall through to CUDA-Lanczos");
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
