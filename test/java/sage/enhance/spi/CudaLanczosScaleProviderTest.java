package sage.enhance.spi;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import sage.Sage;
import sage.TestUtils;
import sage.enhance.EnhancementTier;

import static org.testng.Assert.*;

/**
 * The always-present deterministic CUDA-Lanczos live upscaler: fragment shape,
 * hint handling, and the availability switches.
 */
public class CudaLanczosScaleProviderTest
{
  private static final String PROP_ENABLED =
      "playback/gpu_enhance/scale_cuda_lanczos_provider";
  private static final String PROP_INTERP = "playback/gpu_enhance/scale_cuda_lanczos";

  private final CudaLanczosScaleProvider provider = new CudaLanczosScaleProvider();

  @BeforeMethod
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.remove(PROP_ENABLED);
    Sage.remove(PROP_INTERP);
  }

  @AfterMethod
  public void tearDown()
  {
    Sage.remove(PROP_ENABLED);
    Sage.remove(PROP_INTERP);
  }

  private static ScaleRequest upscale(String hint)
  {
    return new ScaleRequest(EnhancementTier.ENHANCE_2160P, 3840, 2160, 1080, false,
        hint, ScaleRequest.Purpose.LIVE);
  }

  @Test
  public void capabilitiesAreNonSpecializedUpscaler()
  {
    ScaleProviderCapabilities caps = provider.capabilities();
    assertEquals(caps.getProviderId(), CudaLanczosScaleProvider.ID);
    assertTrue(caps.supportsUpscale(), "the CUDA-Lanczos provider upscales");
    assertFalse(caps.isSpecialized(),
        "it uses ordinary NVENC capacity, not the specialized AI budget, so it takes no permit");
  }

  @Test
  public void availableForUpscaleWhenScalerHinted()
  {
    assertTrue(provider.probe(upscale("scale_npp")).isAvailable());
  }

  @Test
  public void unavailableForDeinterlaceOnly()
  {
    ScaleRequest deint = new ScaleRequest(EnhancementTier.DEINTERLACE_ONLY, 1920, 1080,
        1080, true, "scale_npp", ScaleRequest.Purpose.LIVE);
    assertFalse(provider.probe(deint).isAvailable(),
        "deinterlace-only is the core's job, not this provider's scale stage");
  }

  @Test
  public void unavailableWhenDisabled()
  {
    Sage.put(PROP_ENABLED, "off");
    assertFalse(provider.probe(upscale("scale_npp")).isAvailable());
  }

  @Test
  public void nppPlanCarriesLanczos()
  {
    ScaleExecutionPlan plan = provider.plan(upscale("scale_npp"));
    assertEquals(plan.getForm(), ExecutionForm.FFMPEG_FILTER);
    assertEquals(plan.getFfmpegFilter(), "scale_npp=3840:2160:interp_algo=lanczos");
    assertTrue(plan.isRenderable());
    assertEquals(plan.getImplementationLabel(), "NPP/Lanczos");
  }

  @Test
  public void cudaPlanAddsLanczosWhenInterpForcedOn()
  {
    Sage.put(PROP_INTERP, "true");   // force scale_cuda interp_algo support in-test
    ScaleExecutionPlan plan = provider.plan(upscale("scale_cuda"));
    assertEquals(plan.getFfmpegFilter(), "scale_cuda=3840:2160:interp_algo=lanczos");
    assertEquals(plan.getImplementationLabel(), "CUDA/Lanczos");
  }

  @Test
  public void cudaPlanOmitsLanczosWhenInterpForcedOff()
  {
    Sage.put(PROP_INTERP, "false");
    ScaleExecutionPlan plan = provider.plan(upscale("scale_cuda"));
    assertEquals(plan.getFfmpegFilter(), "scale_cuda=3840:2160",
        "without interp_algo support the fragment must not claim Lanczos");
    assertTrue(plan.isRenderable());
  }
}
