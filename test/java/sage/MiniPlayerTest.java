package sage;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
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

  /**
   * Tests for {@link MiniPlayer#buildEffDeliveryToken(String, String)}, the
   * {@code CAP_EFFECTIVE_DELIVERY} wire-string builder (Protocol 2.1).
   * DIRECT_PLAY's bare "pull" must become "pull:direct" (so the bridge
   * routes to /msproxy?mode=direct instead of /rawmedia), while
   * pull-xcode/push/hls and the null/empty no-emission case are unchanged.
   */
  @Test
  public void testDirectPlayPullEmitsPullDirect()
  {
    assertEquals(MiniPlayer.buildEffDeliveryToken("pull", null), "pull:direct",
        "DIRECT_PLAY's bare pull delivery must be labeled pull:direct so the bridge "
            + "routes to /msproxy?mode=direct (proper seeking) instead of /rawmedia");
  }

  @Test
  public void testDirectPlayPullEmitsPullDirectWithEmptyXcodeMode()
  {
    assertEquals(MiniPlayer.buildEffDeliveryToken("pull", ""), "pull:direct",
        "An empty (non-null) xcode mode must be treated the same as null for the bare-pull case");
  }

  @Test
  public void testPullXcodeModeUnchanged()
  {
    assertEquals(MiniPlayer.buildEffDeliveryToken("pull-xcode", "mpeg2tsremux"), "pull-xcode:mpeg2tsremux",
        "pull-xcode delivery must still emit pull-xcode:<mode> unchanged");
    assertEquals(MiniPlayer.buildEffDeliveryToken("pull-xcode", "browserhd_remux"), "pull-xcode:browserhd_remux");
    assertEquals(MiniPlayer.buildEffDeliveryToken("pull-xcode", "browserhd_copyv"), "pull-xcode:browserhd_copyv");
    assertEquals(MiniPlayer.buildEffDeliveryToken("pull-xcode", "audioonly"), "pull-xcode:audioonly");
    assertEquals(MiniPlayer.buildEffDeliveryToken("pull-xcode", "browserhd"), "pull-xcode:browserhd");
    assertEquals(MiniPlayer.buildEffDeliveryToken("pull-xcode", "dynamich264"), "pull-xcode:dynamich264");
  }

  @Test
  public void testPushDeliveryUnchanged()
  {
    assertEquals(MiniPlayer.buildEffDeliveryToken("push", null), "push",
        "push delivery (no xcode mode) must be emitted bare, unchanged");
  }

  @Test
  public void testHlsDeliveryUnchanged()
  {
    assertEquals(MiniPlayer.buildEffDeliveryToken("hls", null), "hls",
        "hls delivery (no xcode mode) must be emitted bare, unchanged");
  }

  @Test
  public void testNullOrEmptyDeliveryYieldsNull()
  {
    assertNull(MiniPlayer.buildEffDeliveryToken(null, null),
        "No chosen surface delivery (legacy/empty-surface path) must yield null -- caller skips emission");
    assertNull(MiniPlayer.buildEffDeliveryToken("", null),
        "Empty chosen surface delivery must yield null -- caller skips emission");
  }

  // -------- server video enhancement suffix (additive) --------

  @Test
  public void testNoEnhancementTierLeavesTokenByteIdentical()
  {
    // The whole backward-compatibility claim rests on this: until enhancement
    // actually runs, no client can observe any change to the token.
    assertEquals(MiniPlayer.buildEffDeliveryToken("pull-xcode", "dynamich264", null),
        MiniPlayer.buildEffDeliveryToken("pull-xcode", "dynamich264"));
    assertEquals(MiniPlayer.buildEffDeliveryToken("pull", null, sage.enhance.EnhancementTier.NONE),
        "pull:direct");
    assertEquals(MiniPlayer.buildEffDeliveryToken("push", null, sage.enhance.EnhancementTier.NONE),
        "push");
  }

  @Test
  public void testEnhancementTierAppendsSuffix()
  {
    assertEquals(MiniPlayer.buildEffDeliveryToken("pull-xcode", "dynamich264",
        sage.enhance.EnhancementTier.ENHANCE_2160P),
        "pull-xcode:dynamich264:enhance;tier=2160p");
    assertEquals(MiniPlayer.buildEffDeliveryToken("push", null,
        sage.enhance.EnhancementTier.ENHANCE_1080P),
        "push:enhance;tier=1080p");
    assertEquals(MiniPlayer.buildEffDeliveryToken("pull-xcode", "mpeg2tsremux",
        sage.enhance.EnhancementTier.DEINTERLACE_ONLY),
        "pull-xcode:mpeg2tsremux:enhance;tier=deint");
  }

  @Test
  public void testEnhancementNeverResurrectsANullToken()
  {
    assertNull(MiniPlayer.buildEffDeliveryToken(null, null,
        sage.enhance.EnhancementTier.ENHANCE_2160P),
        "An absent delivery must stay absent even with a tier set");
  }

  // =======================================================================
  // Item 2: audioRelativeIndexOf -- 0-based position of the chosen audio
  // stream among the source's audio streams (for -map 0:a:<rel>).
  // =======================================================================
  private static sage.media.format.AudioFormat audio(int orderIndex)
  {
    sage.media.format.AudioFormat af = new sage.media.format.AudioFormat();
    af.setOrderIndex(orderIndex);
    return af;
  }

  @Test
  public void testAudioRelativeIndexOf_multiAudioByIdentity()
  {
    sage.media.format.AudioFormat a0 = audio(1); // stream 1 (0 is video)
    sage.media.format.AudioFormat a1 = audio(2);
    sage.media.format.AudioFormat a2 = audio(3);
    sage.media.format.VideoFormat v = new sage.media.format.VideoFormat();
    v.setOrderIndex(0);
    sage.media.format.ContainerFormat cf = new sage.media.format.ContainerFormat();
    cf.setStreamFormats(new sage.media.format.BitstreamFormat[] { v, a0, a1, a2 });

    assertEquals(MiniPlayer.audioRelativeIndexOf(cf, a0), 0,
        "First audio stream must be audio-relative index 0 (not its absolute orderIndex)");
    assertEquals(MiniPlayer.audioRelativeIndexOf(cf, a1), 1);
    assertEquals(MiniPlayer.audioRelativeIndexOf(cf, a2), 2);
  }

  @Test
  public void testAudioRelativeIndexOf_matchesByOrderIndexWhenNotSameInstance()
  {
    sage.media.format.AudioFormat a0 = audio(1);
    sage.media.format.AudioFormat a1 = audio(2);
    sage.media.format.ContainerFormat cf = new sage.media.format.ContainerFormat();
    cf.setStreamFormats(new sage.media.format.BitstreamFormat[] { a0, a1 });

    // A different object with the same orderIndex must still resolve.
    assertEquals(MiniPlayer.audioRelativeIndexOf(cf, audio(2)), 1,
        "Should fall back to matching by absolute orderIndex when not the same instance");
  }

  @Test
  public void testAudioRelativeIndexOf_nullsAndMissingReturnMinusOne()
  {
    assertEquals(MiniPlayer.audioRelativeIndexOf(null, audio(1)), -1);
    assertEquals(MiniPlayer.audioRelativeIndexOf(new sage.media.format.ContainerFormat(), null), -1);

    sage.media.format.ContainerFormat cf = new sage.media.format.ContainerFormat();
    cf.setStreamFormats(new sage.media.format.BitstreamFormat[] { audio(1) });
    assertEquals(MiniPlayer.audioRelativeIndexOf(cf, audio(9)), -1,
        "An audio stream not present in the container must return -1 (caller keeps all audio)");
  }
}
