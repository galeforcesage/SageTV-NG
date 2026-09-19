package sage.enhance.spi;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import sage.TestUtils;
import sage.enhance.EnhancementTier;

import static org.testng.Assert.*;

/**
 * The built-in provider is a passthrough: it never renders a scale stage, so the
 * open-source playback path does not upscale. Server-side upscaling is an opt-in
 * capability of a separately installed provider. (Offline/batch Lanczos is a
 * different subsystem and is unaffected.)
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
  public void upscaleRequestRendersNoFragment()
  {
    ScaleExecutionPlan ex = p.plan(upscale("scale_npp"));
    assertNull(ex.getFfmpegFilter(),
        "the built-in passthrough must not emit any scale fragment for upscaling");
    assertEquals(ex.getForm(), ExecutionForm.BUILTIN);
    assertFalse(ex.rendersFilterFragment());
    assertFalse(ex.isRenderable(),
        "an upscale that reaches the passthrough is non-renderable, so the core skips it");
  }

  @Test
  public void cudaUpscaleAlsoRendersNoFragment()
  {
    ScaleExecutionPlan ex = p.plan(upscale("scale_cuda"));
    assertNull(ex.getFfmpegFilter());
    assertEquals(ex.getImplementationLabel(), "passthrough");
  }

  @Test
  public void notSpecialized()
  {
    assertFalse(p.capabilities().isSpecialized(),
        "the built-in scaler must never take a specialized permit");
    assertFalse(p.capabilities().supportsUpscale(),
        "the built-in passthrough must declare it cannot upscale");
    assertEquals(p.id(), "builtin-passthrough");
  }

  @Test
  public void availableForUpscaleButRendersNothing()
  {
    // The passthrough is always "available" -- it simply contributes no scale
    // stage. Availability without a renderable plan is what makes the core fall
    // through to the plain stream.
    assertTrue(p.probe(upscale(null)).isAvailable());
    assertFalse(p.plan(upscale(null)).isRenderable());
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
