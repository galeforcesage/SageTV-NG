package sage.enhance;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import sage.TestUtils;

/**
 * Behavior of the optional {@link ExternalAdmissionAuthority} seam on
 * {@link GpuGovernor}: the {@link ExternalAdmissionAuthority.Decision} contract,
 * and the governor forwarding release / heartbeat / reclamation to an installed
 * authority while remaining byte-identical to the stock build when none is set.
 *
 * <p>The full {@code requestAdmission} arbitration path needs a real CUDA/NVENC
 * probe ({@link sage.HwEncoder#gpuEnhanceSupported()}) and so is exercised live,
 * not here; these cover the accounting seam that a broker adapter plugs into.
 */
public class GpuGovernorExternalAuthorityTest
{
  /** Records every lifecycle call the governor forwards, for assertions. */
  private static final class RecordingAuthority implements ExternalAdmissionAuthority
  {
    final List<String> released = new ArrayList<String>();
    final AtomicInteger renews = new AtomicInteger();
    volatile String reclaimedId;
    volatile ExternalAdmissionAuthority.Decision nextDecision;
    volatile boolean throwOnAdmit;

    public String name() { return "recording-authority"; }

    public Decision admit(String sessionId, EnhancementTier locallyGranted,
                          int sourceHeight, long estBitrateKbps, boolean offline)
    {
      if (throwOnAdmit) throw new RuntimeException("boom");
      return nextDecision;
    }

    public void renew(String sessionId) { renews.incrementAndGet(); }
    public void release(String sessionId) { released.add(sessionId); }
    public boolean isReclaimed(String sessionId)
    { return reclaimedId != null && reclaimedId.equals(sessionId); }
  }

  private GpuGovernor gov;

  @BeforeMethod
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    gov = GpuGovernor.getInstance();
    gov.setExternalAuthority(null);
    for (String id : gov.activeSessionIds()) gov.release(id);
  }

  @AfterMethod
  public void tearDown()
  {
    for (String id : gov.activeSessionIds()) gov.release(id);
    // Critical: the governor is a singleton — never leak an authority to other tests.
    gov.setExternalAuthority(null);
  }

  private GpuGovernor.Session live(String id, EnhancementTier tier, long kbps)
  {
    return new GpuGovernor.Session(id, tier, 0, kbps, false);
  }

  // ---- Decision contract --------------------------------------------------

  @Test
  public void testGrantDecision()
  {
    ExternalAdmissionAuthority.Decision d =
        ExternalAdmissionAuthority.Decision.grant(EnhancementTier.ENHANCE_1440P);
    assertTrue(d.isGranted());
    assertFalse(d.isDeny());
    assertFalse(d.isDeferToLocal());
    assertEquals(d.getTier(), EnhancementTier.ENHANCE_1440P);
  }

  @Test
  public void testGrantOfInactiveTierCollapsesToDeny()
  {
    // A grant must name real work; granting NONE is a denial, not a silent pass.
    ExternalAdmissionAuthority.Decision d =
        ExternalAdmissionAuthority.Decision.grant(EnhancementTier.NONE);
    assertFalse(d.isGranted());
    assertTrue(d.isDeny());
    assertEquals(d.getTier(), EnhancementTier.NONE);
  }

  @Test
  public void testDenyAndDeferAreDistinct()
  {
    ExternalAdmissionAuthority.Decision deny =
        ExternalAdmissionAuthority.Decision.deny("higher-priority tenant");
    assertTrue(deny.isDeny());
    assertFalse(deny.isDeferToLocal());
    assertFalse(deny.isGranted());

    ExternalAdmissionAuthority.Decision defer =
        ExternalAdmissionAuthority.Decision.deferToLocal("unreachable");
    assertTrue(defer.isDeferToLocal());
    assertFalse(defer.isDeny());
    assertFalse(defer.isGranted());
  }

  @Test
  public void testAdmissionPolicyRequiresExplicitExternalGrant()
  {
    GpuGovernor.Admission grant = new GpuGovernor.Admission(
        "grant", EnhancementTier.ENHANCE_2160P, 0, "test", true, true, false);
    assertTrue(grant.isExternalAuthorityPresent());
    assertTrue(grant.isExternalGrant());
    assertFalse(grant.isExternalDeny());
    assertTrue(grant.permitsSpecializedScale());

    GpuGovernor.Admission deny = new GpuGovernor.Admission(
        "deny", EnhancementTier.ENHANCE_2160P, 0, "test", true, false, true);
    assertTrue(deny.isGranted(), "denial retains a tier for deterministic fallback");
    assertFalse(deny.isExternalGrant(), "fallback admission is not a broker grant");
    assertTrue(deny.isExternalDeny());
    assertFalse(deny.permitsSpecializedScale());

    GpuGovernor.Admission defer = new GpuGovernor.Admission(
        "defer", EnhancementTier.ENHANCE_2160P, 0, "test", true, false, false);
    assertFalse(defer.isExternalGrant());
    assertFalse(defer.permitsSpecializedScale(),
        "fail-open local admission must not become unmanaged neural VSR");

    GpuGovernor.Admission legacy = new GpuGovernor.Admission(
        "legacy", EnhancementTier.ENHANCE_2160P, 0, "test");
    assertFalse(legacy.isExternalAuthorityPresent());
    assertTrue(legacy.permitsSpecializedScale(), "no-authority behavior remains unchanged");
  }

  // ---- Governor forwarding ------------------------------------------------

  @Test
  public void testInstallAndClearAuthority()
  {
    assertNull(gov.getExternalAuthority(), "stock build installs no authority");
    RecordingAuthority auth = new RecordingAuthority();
    gov.setExternalAuthority(auth);
    assertSame(gov.getExternalAuthority(), auth);
    gov.setExternalAuthority(null);
    assertNull(gov.getExternalAuthority());
  }

  @Test
  public void testReleaseForwardsToAuthority()
  {
    RecordingAuthority auth = new RecordingAuthority();
    gov.setExternalAuthority(auth);
    gov.trackSession(live("s-fwd", EnhancementTier.ENHANCE_2160P, 30000));

    gov.release("s-fwd");

    assertEquals(auth.released, java.util.Collections.singletonList("s-fwd"));
    assertTrue(gov.isIdle());
  }

  @Test
  public void testReleaseForwardsEvenWhenSessionUnknown()
  {
    // Idempotent teardown may release twice; the arbiter must still hear both so
    // it never holds a phantom lease after the local session is already gone.
    RecordingAuthority auth = new RecordingAuthority();
    gov.setExternalAuthority(auth);
    gov.trackSession(live("s-twice", EnhancementTier.ENHANCE_1080P, 8000));

    gov.release("s-twice");
    gov.release("s-twice");

    assertEquals(auth.released.size(), 2);
  }

  @Test
  public void testHeartbeatForwardsRenewOnlyForLiveSession()
  {
    RecordingAuthority auth = new RecordingAuthority();
    gov.setExternalAuthority(auth);

    gov.heartbeat("ghost");            // no such session -> no renew
    assertEquals(auth.renews.get(), 0);

    gov.trackSession(live("s-hb", EnhancementTier.ENHANCE_2160P, 30000));
    gov.heartbeat("s-hb");
    gov.heartbeat("s-hb");
    assertEquals(auth.renews.get(), 2, "each heartbeat of a live session renews the lease");
  }

  @Test
  public void testFallbackSessionDoesNotRenewDeniedLease()
  {
    RecordingAuthority auth = new RecordingAuthority();
    gov.setExternalAuthority(auth);
    gov.trackSession(new GpuGovernor.Session(
        "s-fallback", EnhancementTier.ENHANCE_2160P, 0, 30000, false, false));

    gov.heartbeat("s-fallback");

    assertEquals(auth.renews.get(), 0,
        "a deterministic fallback must not masquerade as a granted broker lease");
  }

  @Test
  public void testIsReclaimedReflectsAuthority()
  {
    assertFalse(gov.isReclaimed("x"), "no authority -> never reclaimed");

    RecordingAuthority auth = new RecordingAuthority();
    gov.setExternalAuthority(auth);
    assertFalse(gov.isReclaimed("s-rc"));
    auth.reclaimedId = "s-rc";
    assertTrue(gov.isReclaimed("s-rc"));
    assertFalse(gov.isReclaimed("other"));
  }

  @Test
  public void testForwardingSwallowsAuthorityExceptions()
  {
    // release/heartbeat/isReclaimed run on hot teardown/progress paths; a throwing
    // adapter must never propagate out of them.
    ExternalAdmissionAuthority throwing = new ExternalAdmissionAuthority()
    {
      public String name() { return "throwing"; }
      public Decision admit(String s, EnhancementTier t, int h, long b, boolean o)
      { return Decision.deferToLocal("n/a"); }
      public void renew(String s) { throw new RuntimeException("renew boom"); }
      public void release(String s) { throw new RuntimeException("release boom"); }
      public boolean isReclaimed(String s) { throw new RuntimeException("reclaim boom"); }
    };
    gov.setExternalAuthority(throwing);
    gov.trackSession(live("s-throw", EnhancementTier.ENHANCE_2160P, 30000));

    gov.heartbeat("s-throw");           // must not throw
    assertFalse(gov.isReclaimed("s-throw")); // swallowed -> false
    gov.release("s-throw");             // must not throw
    assertTrue(gov.isIdle());
  }
}
