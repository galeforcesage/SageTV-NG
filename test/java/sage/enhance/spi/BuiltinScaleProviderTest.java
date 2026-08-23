package sage.enhance.spi;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import sage.TestUtils;
import sage.enhance.EnhancementTier;

import static org.testng.Assert.*;

/**
 * The built-in provider must reproduce the pre-seam scale fragment exactly.
 */
public class BuiltinScaleProviderTest
{
  private final BuiltinScaleProvider p = new BuiltinScaleProvider();

  @BeforeMethod
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
  }

  private static ScaleRequest upscale(String hint)
  {
    return new ScaleRequest(EnhancementTier.ENHANCE_2160P, 3840, 2160, 1080, false,
        hint, ScaleRequest.Purpose.LIVE);
  }

  @Test
  public void nppFragmentIsLegacyLanczos()
  {
    ScaleExecutionPlan ex = p.plan(upscale("scale_npp"));
    assertEquals(ex.getFfmpegFilter(), "scale_npp=3840:2160:interp_algo=lanczos",
        "NPP fragment must match the legacy token exactly");
    assertEquals(ex.getForm(), ExecutionForm.BUILTIN);
    assertEquals(ex.getImplementationLabel(), "NPP/Lanczos");
    assertTrue(ex.rendersFilterFragment());
  }

  @Test
  public void cudaFragmentHasNoLanczosFlag()
  {
    ScaleExecutionPlan ex = p.plan(upscale("scale_cuda"));
    assertEquals(ex.getFfmpegFilter(), "scale_cuda=3840:2160",
        "scale_cuda fragment must match the legacy token exactly");
    assertEquals(ex.getImplementationLabel(), "CUDA");
  }

  @Test
  public void notSpecialized()
  {
    assertFalse(p.capabilities().isSpecialized(),
        "the built-in scaler must never take a specialized permit");
    assertEquals(p.id(), "builtin-lanczos");
  }

  @Test
  public void unavailableWhenNoScalerForUpscale()
  {
    assertFalse(p.probe(upscale(null)).isAvailable(),
        "no CUDA scaler means the built-in cannot upscale");
  }

  @Test
  public void deinterlaceOnlyNeedsNoScaler()
  {
    ScaleRequest deintOnly = new ScaleRequest(EnhancementTier.DEINTERLACE_ONLY,
        0, 0, 1080, true, null, ScaleRequest.Purpose.LIVE);
    assertTrue(p.probe(deintOnly).isAvailable(),
        "a deinterlace-only request needs no scaler");
    assertNull(p.plan(deintOnly).getFfmpegFilter(),
        "a deinterlace-only request contributes no scale fragment");
  }
}
