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
    Sage.put(OVERRIDE_PROP, "true");
  }

  /**
   * Classic desktop Placeshifter fingerprint: H.264 advertised, no
   * MPEG2-TS push, has a MOUSE (not a hardware extender), no NG handshake.
   * The profile must apply, flipping h264PushOK true.
   */
  @Test
  public void testAppliesForLegacyDesktopPlaceshifter() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    boolean ngSession = false;
    boolean mediaExtender = false; // has MOUSE -> not an extender
    assertTrue(MiniPlayer.legacyH264PushProfileApplies(ngSession, mediaExtender),
        "Legacy desktop Placeshifter (non-NG, non-extender) should get the H.264-push override");
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
