package sage;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

/**
 * Tests for {@link MiniPlayer#legacyH264PushProfileApplies(boolean, boolean)},
 * the legacy-client H.264-push capability profile that lets the classic
 * (non-NG) desktop Placeshifter -- which advertises H.264 video decode but
 * never advertises MPEG2-TS push (a protocol limitation, not a real
 * capability gap) -- route onto the modern {@code dynamich264} transcode
 * path instead of the legacy mpeg4 ladder.
 */
public class MiniPlayerTest
{
  private static final String OVERRIDE_PROP = "miniplayer/legacy_h264_push_override";

  @AfterMethod
  public void resetProperty() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.put(OVERRIDE_PROP, "false");
  }

  /**
   * Classic desktop Placeshifter fingerprint: H.264 advertised, no
   * MPEG2-TS push, has a MOUSE (not a hardware extender), no NG handshake.
   * The override now DEFAULTS OFF (root-caused: the classic Placeshifter's
   * push receiver is a bespoke PS-only path that cannot demux MPEG2-TS --
   * see legacyH264PushProfileApplies() javadoc), so with no property set
   * these clients must stay on the legacy mpeg4 ladder.
   */
  @Test
  public void testDefaultsOffForLegacyDesktopPlaceshifter() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.remove(OVERRIDE_PROP); // simulate a fresh install: property truly unset
    boolean ngSession = false;
    boolean mediaExtender = false; // has MOUSE -> not an extender
    assertFalse(MiniPlayer.legacyH264PushProfileApplies(ngSession, mediaExtender),
        "Legacy desktop Placeshifter must NOT get the H.264-push override by default "
            + "(client's push receiver can't demux MPEG2-TS -- stall root-caused this session)");
  }

  /**
   * Explicit opt-in: setting miniplayer/legacy_h264_push_override=true still
   * routes a qualifying legacy desktop Placeshifter onto H.264-push, for a
   * future client build verified to handle TS push, or manual testing.
   */
  @Test
  public void testExplicitOptInAppliesForLegacyDesktopPlaceshifter() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.put(OVERRIDE_PROP, "true");
    boolean ngSession = false;
    boolean mediaExtender = false; // has MOUSE -> not an extender
    assertTrue(MiniPlayer.legacyH264PushProfileApplies(ngSession, mediaExtender),
        "Explicit opt-in (override=true) should still grant the H.264-push profile "
            + "to a legacy desktop Placeshifter (non-NG, non-extender)");
  }

  /**
   * HD100/HD200 hardware extender fingerprint: no MOUSE -> isMediaExtender()
   * true. The profile must NOT apply -- these clients stay on the legacy
   * mpeg4 path (with its LAN-aware ceiling) unchanged.
   */
  @Test
  public void testExcludesMediaExtender() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.put(OVERRIDE_PROP, "true"); // even opted in, extenders must be excluded
    boolean ngSession = false;
    boolean mediaExtender = true; // HD100/HD200-style extender
    assertFalse(MiniPlayer.legacyH264PushProfileApplies(ngSession, mediaExtender),
        "Hardware media extenders (HD100/HD200) must never get the legacy H.264-push override");
  }

  /**
   * NG client (PWA/Android) fingerprint: isNgCapableSession() true. NG
   * clients already self-advertise their real capabilities correctly, so
   * the profile must NOT apply and must leave their advertised capabilities
   * completely untouched.
   */
  @Test
  public void testExcludesNgSession() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.put(OVERRIDE_PROP, "true"); // even opted in, NG sessions must be excluded
    boolean ngSession = true;
    boolean mediaExtender = false;
    assertFalse(MiniPlayer.legacyH264PushProfileApplies(ngSession, mediaExtender),
        "NG-capable sessions must never be touched by the legacy H.264-push override");
  }

  /**
   * Kill-switch: setting miniplayer/legacy_h264_push_override=false must
   * disable the override live, without a rebuild, even for an otherwise
   * qualifying legacy desktop Placeshifter.
   */
  @Test
  public void testKillSwitchDisablesOverride() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.put(OVERRIDE_PROP, "false");
    boolean ngSession = false;
    boolean mediaExtender = false;
    assertFalse(MiniPlayer.legacyH264PushProfileApplies(ngSession, mediaExtender),
        "Kill-switch=false must disable the override even for a qualifying legacy client");
  }
}
