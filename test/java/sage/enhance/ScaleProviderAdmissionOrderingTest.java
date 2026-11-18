package sage.enhance;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.mockito.MockedStatic;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import sage.HwEncoder;
import sage.Sage;
import sage.TestUtils;
import sage.enhance.spi.ExecutionForm;
import sage.enhance.spi.FrameCadence;
import sage.enhance.spi.ScaleExecutionPlan;
import sage.enhance.spi.ScaleGovernor;
import sage.enhance.spi.ScaleProfileKey;
import sage.enhance.spi.ScaleProvider;
import sage.enhance.spi.ScaleProviderAssessment;
import sage.enhance.spi.ScaleProviderAvailability;
import sage.enhance.spi.ScaleProviderCapabilities;
import sage.enhance.spi.ScaleProviderRegistry;
import sage.enhance.spi.ScaleRequest;
import sage.enhance.spi.ScaleSelection;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.testng.Assert.*;

/** Offer, broker admission, and final planning must preserve a one-use grant. */
public class ScaleProviderAdmissionOrderingTest
{
  private final ScaleProviderRegistry registry = ScaleProviderRegistry.getInstance();
  private final GpuGovernor governor = GpuGovernor.getInstance();

  @BeforeMethod
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    governor.setExternalAuthority(null);
    for (String id : governor.activeSessionIds()) governor.release(id);
    registry.resetForTest();
    ScaleGovernor.getInstance().resetForTest();
    Sage.put("playback/gpu_enhance/enabled", "true");
    Sage.put("playback/gpu_enhance/scale/transport_pixel_format", "rgb24");
  }

  @AfterMethod
  public void tearDown()
  {
    for (String id : governor.activeSessionIds()) governor.release(id);
    governor.setExternalAuthority(null);
    registry.resetForTest();
    ScaleGovernor.getInstance().resetForTest();
    Sage.remove("playback/gpu_enhance/enabled");
    Sage.remove("playback/gpu_enhance/scale/transport_pixel_format");
  }

  private static ScaleRequest request(ScaleRequest.Purpose purpose)
  {
    return new ScaleRequest(EnhancementTier.ENHANCE_2160P, 3840, 2160, 1920, 1080,
        false, "scale_npp", purpose, new FrameCadence(30, 1), new FrameCadence(30, 1),
        "yuv420p", 8, "rgb24", 8, "", "", "", "");
  }

  /** Mimics VSR: planning consumes a thread-local grant and otherwise fails closed. */
  private static final class GrantedProvider implements ScaleProvider, ExternalAdmissionAuthority
  {
    final ThreadLocal<Boolean> grant = new ThreadLocal<Boolean>();
    final List<String> events = new ArrayList<String>();
    boolean specialized = true;
    boolean selfAdmitted = true;
    boolean measured = true;
    boolean admissible = true;
    boolean matching = true;
    boolean fresh = true;
    boolean allow = true;
    int planCalls;
    int admitCalls;

    public String id() { return "nvidia-vsr"; }
    public String name() { return "test-broker"; }
    public ScaleProviderCapabilities capabilities()
    { return new ScaleProviderCapabilities(id(), specialized, true, 1); }
    public boolean managesWarmupAdmission() { return selfAdmitted; }
    public ScaleProviderAvailability probe(ScaleRequest r)
    { return ScaleProviderAvailability.available(); }

    public ScaleProviderAssessment assess(ScaleRequest r)
    {
      if (!measured) return ScaleProviderAssessment.fromAvailability(r, probe(r));
      ScaleProfileKey profile = new ScaleProfileKey(matching ? 1920 : 1280, 1080,
          3840, 2160, false, new FrameCadence(30, 1), new FrameCadence(30, 1),
          "yuv420p", 8, "rgb24", 8, "vsr", "cuda", "gpu", "driver");
      Duration age = fresh ? Duration.ZERO : Duration.ofDays(1);
      return new ScaleProviderAssessment(profile, admissible, "exact capacity",
          80.0, 36.0, 80.0 / 36.0, Instant.now().minus(age), age,
          Duration.ofSeconds(10), 800L);
    }

    public ScaleExecutionPlan plan(ScaleRequest r)
    {
      planCalls++;
      boolean granted = Boolean.TRUE.equals(grant.get());
      events.add(granted ? "plan:granted" : "plan:denied");
      grant.remove();
      if (!granted) return null;
      assertEquals(ScaleGovernor.getInstance().activeCount(), 1);
      assertEquals(r.getModelIdentity(), "vsr");
      return new ScaleExecutionPlan(ExecutionForm.EXTERNAL_PROCESS,
          Arrays.asList("test-vsr-worker", "--broker-granted"), 3840, 2160,
          "rgb24", "NVIDIA VSR test");
    }

    public Decision admit(String id, EnhancementTier tier, int height, long bitrate,
        boolean offline)
    {
      admitCalls++;
      events.add("admit");
      assertEquals(planCalls, 0, "planning must not precede broker admission");
      if (!allow) return Decision.deny("broker denied placement");
      grant.set(true);
      return Decision.grant(tier);
    }

    public void renew(String id) { }
    public void release(String id) { grant.remove(); }
    public boolean isReclaimed(String id) { return false; }
  }

  @DataProvider
  public Object[][] decisions()
  {
    return new Object[][] { { true }, { false } };
  }

  @Test(dataProvider = "decisions")
  public void offerThenAdmissionThenFinalPlan(boolean allowed)
  {
    GrantedProvider provider = new GrantedProvider();
    provider.allow = allowed;
    registry.register(provider);
    governor.setExternalAuthority(provider);
    ScaleRequest probe = request(ScaleRequest.Purpose.PROBE);

    assertTrue(registry.selectedProviderCanUpscale(probe));
    assertTrue(registry.selectedSpecializedProviderCanUpscale(probe));
    assertEquals(provider.planCalls, 0);
    assertEquals(provider.admitCalls, 0);
    assertNull(provider.grant.get());
    assertEquals(ScaleGovernor.getInstance().activeCount(), 0);

    RecordingGuard guard = mock(RecordingGuard.class);
    RecordingGuard.CaptureLoad load = new RecordingGuard.CaptureLoad(0, 0, -1L);
    when(guard.sampleLoad()).thenReturn(load);
    when(guard.applyVeto(EnhancementTier.ENHANCE_2160P, load))
        .thenReturn(EnhancementTier.ENHANCE_2160P);
    GpuMonitor monitor = mock(GpuMonitor.class);
    when(monitor.getSnapshot(anyInt())).thenReturn(GpuSnapshot.UNAVAILABLE);

    try (MockedStatic<HwEncoder> hardware = mockStatic(HwEncoder.class);
         MockedStatic<RecordingGuard> recording = mockStatic(RecordingGuard.class);
         MockedStatic<GpuMonitor> telemetry = mockStatic(GpuMonitor.class))
    {
      hardware.when(HwEncoder::gpuEnhanceSupported).thenReturn(true);
      hardware.when(HwEncoder::cudaScaler).thenReturn("scale_npp");
      recording.when(RecordingGuard::getInstance).thenReturn(guard);
      telemetry.when(GpuMonitor::getInstance).thenReturn(monitor);

      GpuGovernor.Admission admission = governor.requestAdmission(
          "ordering", EnhancementTier.ENHANCE_2160P, 1080, 30000L);
      assertEquals(provider.admitCalls, 1, "the real governor must ask the broker");
      assertEquals(admission.isGranted(), allowed);
      assertEquals(admission.isExternalGrant(), allowed);
      assertTrue(registry.selectedSpecializedProviderCanUpscale(probe));
      assertEquals(provider.planCalls, 0, "repeated offers must not consume the grant");
      assertEquals(Boolean.TRUE.equals(provider.grant.get()), allowed);

      EnhancementPlan plan = GpuEnhancePipeline.buildPlan(admission.getTier(),
          false, 1920, 1080, 30000L, false, true, 30.0, "yuv420p", 8);
      try
      {
        assertEquals(plan.isActive(), allowed);
        if (allowed)
        {
          assertTrue(plan.getScaleExec().isRenderable());
          assertEquals(plan.getScaleExec().getImplementationLabel(), "NVIDIA VSR test");
          assertEquals(provider.events, Arrays.asList("admit", "plan:granted"));
          assertNull(provider.grant.get(), "only final planning consumes the grant");
        }
        else
        {
          assertEquals(provider.planCalls, 0);
          ScaleSelection denied = registry.selectSpecialized(request(ScaleRequest.Purpose.LIVE));
          assertTrue(denied.fellBackToBuiltin());
          assertFalse(denied.getExecutionPlan().isRenderable(),
              "AI-only denial must never fall through to CUDA-Lanczos");
          assertEquals(ScaleGovernor.getInstance().activeCount(), 0);
          assertTrue(governor.isIdle());
        }
      }
      finally
      {
        if (plan.getScaleLease() != null) plan.getScaleLease().close();
      }
    }
  }

  @DataProvider
  public Object[][] legacyGates()
  {
    return new Object[][] {
        { false, true, true, true },
        { true, false, true, true },
        { true, true, false, true },
        { true, true, true, false }
    };
  }

  @Test(dataProvider = "legacyGates")
  public void otherProvidersStillRequireRenderablePlan(boolean authority,
      boolean specialized, boolean selfAdmitted, boolean measured)
  {
    GrantedProvider provider = new GrantedProvider();
    provider.specialized = specialized;
    provider.selfAdmitted = selfAdmitted;
    provider.measured = measured;
    registry.register(provider);
    Sage.put("playback/gpu_enhance/scale_cuda_lanczos_provider", "off");
    try
    {
      if (authority) governor.setExternalAuthority(provider);
      assertFalse(registry.selectedProviderCanUpscale(request(ScaleRequest.Purpose.PROBE)));
      assertEquals(provider.planCalls, 1);
      assertEquals(provider.admitCalls, 0);
    }
    finally
    {
      Sage.remove("playback/gpu_enhance/scale_cuda_lanczos_provider");
    }
  }

  @DataProvider
  public Object[][] unusableAssessments()
  {
    return new Object[][] { { false, true, true }, { true, false, true },
        { true, true, false } };
  }

  @Test(dataProvider = "unusableAssessments")
  public void assessmentStillMustBeAdmissibleExactAndFresh(boolean admissible,
      boolean matching, boolean fresh)
  {
    GrantedProvider provider = new GrantedProvider();
    provider.admissible = admissible;
    provider.matching = matching;
    provider.fresh = fresh;
    registry.register(provider);
    governor.setExternalAuthority(provider);

    assertFalse(registry.selectedSpecializedProviderCanUpscale(
        request(ScaleRequest.Purpose.PROBE)));
    assertEquals(provider.planCalls, 0);
    assertEquals(provider.admitCalls, 0);
    assertEquals(ScaleGovernor.getInstance().activeCount(), 0);
  }
}
