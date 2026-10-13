package sage.enhance.spi;

import java.time.Duration;
import java.time.Instant;

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
  private static final String PROP_CUDA_LANCZOS =
      "playback/gpu_enhance/scale_cuda_lanczos_provider";
  private static final String PROP_ASSESSMENT_AGE =
      "playback/gpu_enhance/scale/assessment_max_age_seconds";

  private ScaleProviderRegistry reg;

  @BeforeMethod
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.remove(PROP_PROVIDER);
    Sage.remove(PROP_MAX);
    Sage.remove(PROP_CUDA_LANCZOS);
    Sage.remove(PROP_ASSESSMENT_AGE);
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
    Sage.remove(PROP_CUDA_LANCZOS);
    Sage.remove(PROP_ASSESSMENT_AGE);
  }

  private static ScaleRequest live()
  {
    return new ScaleRequest(EnhancementTier.ENHANCE_2160P, 3840, 2160, 1080, false,
        "scale_npp", ScaleRequest.Purpose.LIVE);
  }

  private static ScaleRequest measuredLive()
  {
    return new ScaleRequest(EnhancementTier.ENHANCE_2160P, 3840, 2160, 1920, 1080,
        true, "scale_npp", ScaleRequest.Purpose.LIVE,
        new FrameCadence(30000, 1001), new FrameCadence(60000, 1001),
        "yuv420p", 8, "rgb24", 8, "", "", "", "");
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

  @Test
  public void inadmissibleMeasuredProfileFallsThroughToCudaLanczos()
  {
    ScaleProvider slow = new FakeSpecialized("nvidia-vsr")
    {
      public ScaleProviderAssessment assess(ScaleRequest request)
      {
        ScaleProfileKey profile = new ScaleProfileKey(1920, 1080, 3840, 2160, true,
            new FrameCadence(30000, 1001), new FrameCadence(60000, 1001),
            "yuv420p", 8, "rgb24", 8, "vsr", "cuda", "gpu", "driver");
        return new ScaleProviderAssessment(profile, false, "below measured threshold",
            59.6, 71.928, 0.829, Instant.now(), Duration.ZERO,
            Duration.ofSeconds(10), 596);
      }

      @Test
      public void expiredMeasuredProfileFallsThroughToCudaLanczos()
      {
        Sage.putLong(PROP_ASSESSMENT_AGE, 60L);
        ScaleProvider expired = new FakeSpecialized("nvidia-vsr")
        {
          public ScaleProviderAssessment assess(ScaleRequest request)
          {
            ScaleProfileKey profile = new ScaleProfileKey(1920, 1080, 3840, 2160, true,
                new FrameCadence(30000, 1001), new FrameCadence(60000, 1001),
                "yuv420p", 8, "rgb24", 8, "vsr", "cuda", "gpu", "driver");
            return new ScaleProviderAssessment(profile, true, "stale",
                80.0, 71.928, 1.112, Instant.now().minusSeconds(120),
                Duration.ofSeconds(120), Duration.ofSeconds(10), 800);
          }

          @Test
          public void exactAdmissibleMeasuredProfileSelectsSpecializedProvider()
          {
            ScaleProvider measured = new FakeSpecialized("nvidia-vsr")
            {
              public ScaleProviderAssessment assess(ScaleRequest request)
              {
                ScaleProfileKey profile = new ScaleProfileKey(1920, 1080, 3840, 2160, true,
                    new FrameCadence(30000, 1001), new FrameCadence(60000, 1001),
                    "yuv420p", 8, "rgb24", 8, "vsr", "cuda", "gpu", "driver");
                return new ScaleProviderAssessment(profile, true, "exact benchmark",
                    80.0, 71.928, 1.112, Instant.now(), Duration.ZERO,
                    Duration.ofSeconds(10), 800);
              }
            };
            ScaleProviderRegistration r = reg.register(measured);
            ScaleSelection sel = reg.select(measuredLive());
            assertEquals(sel.getProviderId(), "nvidia-vsr");
            assertEquals(sel.getExecutionPlan().getFfmpegFilter(), "fakevsr=3840:2160");
            sel.getLease().close();
            r.close();
          }
        };
        ScaleProviderRegistration r = reg.register(expired);
        ScaleSelection sel = reg.select(measuredLive());
        assertEquals(sel.getProviderId(), CudaLanczosScaleProvider.ID);
        r.close();
      }
    };
    ScaleProviderRegistration r = reg.register(slow);
    ScaleSelection sel = reg.select(live());
    assertEquals(sel.getProviderId(), CudaLanczosScaleProvider.ID);
    assertEquals(ScaleGovernor.getInstance().activeCount(), 0);
    r.close();
  }

  // ---- Deterministic (one-rung-down) selection ---------------------------

  @Test
  public void selectDeterministicSkipsSpecializedAndUsesCudaLanczos()
  {
    // The VSR->Lanczos fallback: when a specialized worker misses its load budget
    // the transcoder rebuilds the scale stage with selectDeterministic, which must
    // bypass the (registered, available) AI upscaler and land on CUDA-Lanczos so
    // the session degrades ONE rung instead of dropping to source.
    ScaleProviderRegistration r = reg.register(new FakeSpecialized("nvidia-vsr"));
    ScaleSelection sel = reg.selectDeterministic(live());
    assertEquals(sel.getProviderId(), CudaLanczosScaleProvider.ID,
        "deterministic selection must skip the specialized upscaler");
    assertFalse(sel.fellBackToBuiltin(), "CUDA-Lanczos is a real rung, not the source fallback");
    assertEquals(sel.getExecutionPlan().getFfmpegFilter(),
        "scale_npp=3840:2160:interp_algo=lanczos");
    r.close();
  }

  @Test
  public void selectDeterministicHoldsNoSpecializedPermit()
  {
    // The deterministic rung takes no ScaleGovernor permit (Lanczos is an in-argv
    // filter, not a worker), so it must never touch specialized capacity even with
    // an eligible specialized provider registered.
    ScaleProviderRegistration r = reg.register(new FakeSpecialized("nvidia-vsr"));
    ScaleSelection sel = reg.selectDeterministic(live());
    assertNull(sel.getLease(), "CUDA-Lanczos holds no specialized permit");
    assertEquals(ScaleGovernor.getInstance().activeCount(), 0,
        "deterministic selection must not consume specialized capacity");
    r.close();
  }

  @Test
  public void selectPrefersSpecializedButDeterministicDoesNot()
  {
    // Same request, same registry state: the ordinary path picks the AI upscaler,
    // the deterministic path picks Lanczos. This is exactly the one-rung gap the
    // fallback command relies on.
    ScaleProviderRegistration r = reg.register(new FakeSpecialized("nvidia-vsr"));
    assertEquals(reg.select(live()).getProviderId(), "nvidia-vsr");
    ScaleGovernor.getInstance().resetForTest();
    assertEquals(reg.selectDeterministic(live()).getProviderId(),
        CudaLanczosScaleProvider.ID);
    r.close();
  }

  @Test
  public void selectDeterministicDeliversSourceWhenNoScaler()
  {
    // With CUDA-Lanczos disabled the deterministic chain is empty, so the fallback
    // resolves to the source-delivering built-in — a specialized provider is still
    // ignored, so a VSR miss with no Lanczos correctly ends at plain remux.
    Sage.put(PROP_CUDA_LANCZOS, "off");
    ScaleProviderRegistration r = reg.register(new FakeSpecialized("nvidia-vsr"));
    ScaleSelection sel = reg.selectDeterministic(live());
    assertEquals(sel.getProviderId(), BuiltinScaleProvider.ID);
    assertTrue(sel.fellBackToBuiltin());
    assertEquals(ScaleGovernor.getInstance().activeCount(), 0);
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
