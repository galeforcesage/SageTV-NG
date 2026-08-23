package sage.enhance.spi;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import sage.Sage;
import sage.TestUtils;
import sage.enhance.EnhancementPlan;
import sage.enhance.EnhancementTier;
import sage.enhance.GpuEnhancePipeline;

import static org.testng.Assert.*;

/**
 * The provider seam must not change what the pipeline renders when the built-in
 * scaler is selected, must render the captured plan (not re-resolve the
 * registry), and must never let a provider drop the mandatory deinterlacer.
 */
public class ScaleSeamRenderTest
{
  private static final String PROP_PROVIDER = "playback/gpu_enhance/scale_provider";

  @BeforeMethod
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.remove(PROP_PROVIDER);
    ScaleProviderRegistry.getInstance().resetForTest();
    ScaleGovernor.getInstance().resetForTest();
  }

  @AfterMethod
  public void tearDown()
  {
    ScaleProviderRegistry.getInstance().resetForTest();
    ScaleGovernor.getInstance().resetForTest();
    Sage.remove(PROP_PROVIDER);
  }

  /** A legacy plan as directly constructed before the seam existed. */
  private static EnhancementPlan legacy(EnhancementTier tier, boolean deint, String scaler)
  {
    return new EnhancementPlan(tier, deint, deint ? "yadif_cuda" : null, scaler,
        tier.getTargetWidth(), tier.getTargetHeight(), 25000, 0, "legacy");
  }

  /** The same plan, but carrying the built-in provider's captured scale stage. */
  private static EnhancementPlan captured(EnhancementTier tier, boolean deint, String scaler)
  {
    ScaleExecutionPlan ex = new BuiltinScaleProvider().plan(new ScaleRequest(
        tier, tier.getTargetWidth(), tier.getTargetHeight(),
        deint ? tier.getTargetHeight() : 1080, deint, scaler, ScaleRequest.Purpose.LIVE));
    return new EnhancementPlan(tier, deint, deint ? "yadif_cuda" : null, scaler,
        tier.getTargetWidth(), tier.getTargetHeight(), 25000, 0, "captured", ex, null);
  }

  @Test
  public void builtinCaptureRendersIdenticalTo720pProgressive()
  {
    // 720p progressive → 2160p, no deinterlace.
    EnhancementPlan leg = legacy(EnhancementTier.ENHANCE_2160P, false, "scale_npp");
    EnhancementPlan cap = captured(EnhancementTier.ENHANCE_2160P, false, "scale_npp");
    assertEquals(GpuEnhancePipeline.buildFilterChain(cap),
        GpuEnhancePipeline.buildFilterChain(leg),
        "720p progressive chain must be byte-identical with the seam");
    assertEquals(GpuEnhancePipeline.buildFilterChain(cap),
        "scale_npp=3840:2160:interp_algo=lanczos");
  }

  @Test
  public void builtinCaptureRendersIdenticalTo1080iInterlaced()
  {
    // 1080i → 2160p, with deinterlace: the deint stage must precede the scaler.
    EnhancementPlan leg = legacy(EnhancementTier.ENHANCE_2160P, true, "scale_npp");
    EnhancementPlan cap = captured(EnhancementTier.ENHANCE_2160P, true, "scale_npp");
    assertEquals(GpuEnhancePipeline.buildFilterChain(cap),
        GpuEnhancePipeline.buildFilterChain(leg),
        "1080i chain must be byte-identical with the seam");
    assertEquals(GpuEnhancePipeline.buildFilterChain(cap),
        "yadif_cuda=0:-1:1,scale_npp=3840:2160:interp_algo=lanczos");
  }

  @Test
  public void builtinCaptureRendersIdenticalTo1080pProgressiveCuda()
  {
    // 1080p progressive → 2160p on a build with only scale_cuda.
    EnhancementPlan leg = legacy(EnhancementTier.ENHANCE_2160P, false, "scale_cuda");
    EnhancementPlan cap = captured(EnhancementTier.ENHANCE_2160P, false, "scale_cuda");
    assertEquals(GpuEnhancePipeline.buildFilterChain(cap),
        GpuEnhancePipeline.buildFilterChain(leg),
        "scale_cuda chain must be byte-identical with the seam");
    assertEquals(GpuEnhancePipeline.buildFilterChain(cap), "scale_cuda=3840:2160");
  }

  @Test
  public void nullExecUsesLegacyRenderPath()
  {
    // A directly-constructed plan (as the calibrator builds) has no captured exec
    // and must still render the legacy fragment.
    EnhancementPlan leg = legacy(EnhancementTier.ENHANCE_2160P, false, "scale_npp");
    assertNull(leg.getScaleExec());
    assertEquals(GpuEnhancePipeline.buildFilterChain(leg),
        "scale_npp=3840:2160:interp_algo=lanczos");
  }

  @Test
  public void capturedPlanIsImmuneToLaterRegistryChange()
  {
    // Capture a selection now...
    ScaleSelection sel = ScaleProviderRegistry.getInstance().select(new ScaleRequest(
        EnhancementTier.ENHANCE_2160P, 3840, 2160, 720, false, "scale_npp",
        ScaleRequest.Purpose.LIVE));
    EnhancementPlan cap = new EnhancementPlan(EnhancementTier.ENHANCE_2160P, false, null,
        "scale_npp", 3840, 2160, 25000, 0, "captured", sel.getExecutionPlan(), sel.getLease());
    String before = GpuEnhancePipeline.buildFilterChain(cap);

    // ...then a provider is registered and made the default afterwards.
    ScaleProvider fake = new ScaleProvider() {
      public String id() { return "nvidia-vsr"; }
      public ScaleProviderCapabilities capabilities()
      { return new ScaleProviderCapabilities("nvidia-vsr", true, true, 1); }
      public ScaleProviderAvailability probe(ScaleRequest r) { return ScaleProviderAvailability.available(); }
      public ScaleExecutionPlan plan(ScaleRequest r)
      { return new ScaleExecutionPlan(ExecutionForm.FFMPEG_FILTER, "fakevsr=3840:2160", "Fake"); }
    };
    ScaleProviderRegistration r = ScaleProviderRegistry.getInstance().register(fake);
    Sage.put(PROP_PROVIDER, "nvidia-vsr");

    assertEquals(GpuEnhancePipeline.buildFilterChain(cap), before,
        "a captured plan must not hot-swap when the registry changes mid-session");
    r.close();
  }

  @Test
  public void providerCannotSuppressMandatoryDeinterlace()
  {
    // Even a plan carrying a foreign scale fragment renders the deinterlacer the
    // core decided on: the provider owns only the scale stage.
    ScaleExecutionPlan foreign =
        new ScaleExecutionPlan(ExecutionForm.FFMPEG_FILTER, "fakevsr=3840:2160", "Fake");
    EnhancementPlan cap = new EnhancementPlan(EnhancementTier.ENHANCE_2160P, true,
        "yadif_cuda", "scale_npp", 3840, 2160, 25000, 0, "captured", foreign, null);
    String chain = GpuEnhancePipeline.buildFilterChain(cap);
    assertTrue(chain.startsWith("yadif_cuda=0:-1:1,"),
        "the mandatory deinterlacer must still lead the chain");
    assertTrue(chain.endsWith("fakevsr=3840:2160"),
        "the provider's scale fragment occupies only the scale stage");
  }
}
