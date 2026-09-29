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
  public void testEnhancementTierDoesNotCreateDirectEnhanceDelivery()
  {
    assertEquals(MiniPlayer.buildEffDeliveryToken("pull", null,
        sage.enhance.EnhancementTier.ENHANCE_2160P), "pull:direct",
        "A raw direct pull has no transcoder and must not advertise enhancement");
  }

  @Test
  public void testEnhancementNeverResurrectsANullToken()
  {
    assertNull(MiniPlayer.buildEffDeliveryToken(null, null,
        sage.enhance.EnhancementTier.ENHANCE_2160P),
        "An absent delivery must stay absent even with a tier set");
  }

  // -------- NG output-codec resolution (resolveNgOutputCodecNames) --------
  //
  // The server owns the browserhd ffmpeg profiles, so it states the codecs the
  // client will ACTUALLY receive on the wire. The source-based hint names the
  // pre-transcode codec (wrong for a browserhd TRANSCODE), which is what made
  // the PWA probe/stall on startup. This one resolver is the single decision
  // behind the native STREAMINFO channel (it rewrites the wire ContainerFormat).
  // The browser/CMAF path reads codecs from the fMP4 init.mp4 (#EXT-X-MAP), so
  // it needs no server side-channel; the old ng_fmt/ng_out hints are retired.
  // Pure String in/out -> no MediaFormat instantiation (which needs full Sage
  // init and is unavailable in this unit harness).

  private static final String V = "video/avc";       // H.264
  private static final String AAC_M = "audio/mp4a-latm";
  private static final String AC3_M = "audio/ac3";
  private static final String HEVC_M = "video/hevc";

  @Test
  public void testResolveOutputBrowserhdTranscodeIsH264Aac()
  {
    // The exact failing case: MPEG2/AC3 source, browserhd transcodes to H.264/AAC.
    String[] n = MiniPlayer.resolveNgOutputCodecNames("browserhd", null,
        sage.media.format.MediaFormat.MPEG2_VIDEO, sage.media.format.MediaFormat.AC3, "AAC");
    assertEquals(n[0], sage.media.format.MediaFormat.H264);
    assertEquals(n[1], "AAC");
  }

  @Test
  public void testResolveOutputDefaultsToAacFloor()
  {
    // No surface-resolved target audio codec -> browserhd's AAC floor.
    String[] n = MiniPlayer.resolveNgOutputCodecNames("browserhd", null,
        sage.media.format.MediaFormat.MPEG2_VIDEO, sage.media.format.MediaFormat.AC3, null);
    assertEquals(n[0], sage.media.format.MediaFormat.H264);
    assertEquals(n[1], sage.media.format.MediaFormat.AAC);
  }

  @Test
  public void testResolveOutputCopyvKeepsSourceVideoTranscodesAudio()
  {
    // copyv stream-copies the (already H.264) source video, transcodes audio to AAC.
    String[] n = MiniPlayer.resolveNgOutputCodecNames("browserhd_copyv", null,
        sage.media.format.MediaFormat.H264, sage.media.format.MediaFormat.AC3, "AAC");
    assertEquals(n[0], sage.media.format.MediaFormat.H264);
    assertEquals(n[1], "AAC");
  }

  @Test
  public void testResolveOutputRemuxCopiesBothStreams()
  {
    // remux stream-copies both -> report the source codecs (H.264 + AC3 here).
    String[] n = MiniPlayer.resolveNgOutputCodecNames("browserhd_remux", null,
        sage.media.format.MediaFormat.H264, sage.media.format.MediaFormat.AC3, null);
    assertEquals(n[0], sage.media.format.MediaFormat.H264);
    assertEquals(n[1], sage.media.format.MediaFormat.AC3);
  }

  @Test
  public void testResolveOutputEnhanceIsHevc()
  {
    String[] n = MiniPlayer.resolveNgOutputCodecNames("browserhd", sage.enhance.EnhancementTier.ENHANCE_2160P,
        sage.media.format.MediaFormat.MPEG2_VIDEO, sage.media.format.MediaFormat.AC3, "AAC");
    assertEquals(n[0], sage.media.format.MediaFormat.HEVC);
    assertEquals(n[1], "AAC");
  }

  @Test
  public void testResolveOutputNonBrowserhdModeYieldsNull()
  {
    assertNull(MiniPlayer.resolveNgOutputCodecNames("mpeg2tsremux", null,
        sage.media.format.MediaFormat.H264, sage.media.format.MediaFormat.AAC, "AAC"),
        "Only the browserhd/fMP4 family carries an output-codec decision");
    assertNull(MiniPlayer.resolveNgOutputCodecNames(null, null, null, null, null));
  }

  @Test
  public void testResolveOutputUnmappableNameIsAuthoritative()
  {
    // A copied source video whose name toMimeType cannot map (e.g. VP9) is still
    // authoritative for STREAMINFO -- the raw codec NAME is returned as-is (the
    // old MIME projection returned null here; STREAMINFO carries the name).
    String[] n = MiniPlayer.resolveNgOutputCodecNames("browserhd_copyv", null,
        "VP9", sage.media.format.MediaFormat.AAC, null);
    assertEquals(n[0], "VP9");
    assertEquals(n[1], sage.media.format.MediaFormat.AAC);
  }

  @Test
  public void testResolveNgOutputCodecNamesFeedsStreamInfo()
  {
    // STREAMINFO consumes the raw codec NAMES (not MIMEs) to rewrite the wire
    // ContainerFormat -- the sole serializer of this decision now.
    String[] n = MiniPlayer.resolveNgOutputCodecNames("browserhd", null,
        sage.media.format.MediaFormat.MPEG2_VIDEO, sage.media.format.MediaFormat.AC3, "AAC");
    assertEquals(n[0], sage.media.format.MediaFormat.H264);
    assertEquals(n[1], "AAC");
    // Non-browserhd -> no override anywhere.
    assertNull(MiniPlayer.resolveNgOutputCodecNames("mpeg2tsremux", null,
        sage.media.format.MediaFormat.H264, sage.media.format.MediaFormat.AAC, "AAC"));
  }

  @Test
  public void testDescribeBrowserhdWireFormatNullSourceIsNull()
  {
    // Null source / null output codecs are pure no-ops (no ContainerFormat
    // instantiation -- which needs full Sage init, unavailable in this harness).
    assertNull(MiniPlayer.describeBrowserhdWireFormat0(null, "H.264", "AAC"));
  }

  // -------- NG output-container resolution (resolveNgOutputContainerName) --------

  @Test
  public void testResolveContainerTsRemuxIsTs()
  {
    // A PS recording remuxed to MPEG2-TS delivers TS on the wire; STREAMINFO must
    // report the delivery container, not the source's MPEG2-PS.
    assertEquals(MiniPlayer.resolveNgOutputContainerName("mpeg2tsremux"),
        sage.media.format.MediaFormat.MPEG2_TS);
  }

  @Test
  public void testResolveContainerPsRemuxIsPs()
  {
    assertEquals(MiniPlayer.resolveNgOutputContainerName("mpeg2psremux"),
        sage.media.format.MediaFormat.MPEG2_PS);
  }

  @Test
  public void testResolveContainerBrowserhdFamilyIsFmp4()
  {
    // Every browserhd-family mode delivers an fMP4 (QuickTime/MP4) container.
    assertEquals(MiniPlayer.resolveNgOutputContainerName("browserhd"),
        sage.media.format.MediaFormat.QUICKTIME);
    assertEquals(MiniPlayer.resolveNgOutputContainerName("browserhd_copyv"),
        sage.media.format.MediaFormat.QUICKTIME);
    assertEquals(MiniPlayer.resolveNgOutputContainerName("browserhd_remux"),
        sage.media.format.MediaFormat.QUICKTIME);
  }

  @Test
  public void testResolveContainerDirectPlayKeepsSource()
  {
    // Direct play / bare pull: the wire IS the source container, so no override.
    assertNull(MiniPlayer.resolveNgOutputContainerName(null));
    assertNull(MiniPlayer.resolveNgOutputContainerName(""));
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

  // ---- chooseRemuxPushMode: REMUX target must not be hardcoded to PS ----------

  /**
   * A TS-only client (dropped PS, as the Android NG client now does) that hits a
   * codec-clean REMUX verdict must be rewrapped into MPEG2-TS, never PS. The old
   * hardcoded {@code mpeg2psremux} both ignored this and, worse, forced HEVC into
   * MPEG2-PS which freezes the client.
   */
  @Test
  public void testChooseRemux_tsOnlyClientGetsTs()
  {
    assertEquals(MiniPlayer.chooseRemuxPushMode(true, false, null, false), "mpeg2tsremux",
        "TS-capable / PS-incapable client must get an MPEG2-TS remux, not PS");
  }

  /** A legacy PS-capable client (no TS, no enhance, no fixed format) is unchanged. */
  @Test
  public void testChooseRemux_legacyPsClientUnchanged()
  {
    assertEquals(MiniPlayer.chooseRemuxPushMode(false, true, null, false), "mpeg2psremux",
        "Legacy PS-only client keeps the classic PS remux (no regression)");
  }

  /**
   * The client's explicit FIXED_PUSH_REMUX_FORMAT is honored verbatim when set —
   * the client named the wire container it wants.
   */
  @Test
  public void testChooseRemux_honorsClientFixedRemuxFormat()
  {
    String fixed = "container=mpegts;videocodec=COPY;audiocodec=COPY";
    assertEquals(MiniPlayer.chooseRemuxPushMode(true, true, fixed, false), fixed,
        "A client-declared FIXED_PUSH_REMUX_FORMAT must be honored over the PS default");
  }

  /**
   * An active GPU-enhance tier can only ride a copy-family MPEG2-TS remux; even
   * when the client would accept PS (or named a fixed format), enhancement forces
   * mpeg2tsremux so the enhancement pass has a copy-family transcoder to rewrite.
   */
  @Test
  public void testChooseRemux_enhanceForcesTsRemux()
  {
    assertEquals(MiniPlayer.chooseRemuxPushMode(true, true,
        "container=mpegts;videocodec=COPY;audiocodec=COPY", true), "mpeg2tsremux",
        "Active enhance tier + TS push must resolve to mpeg2tsremux (enhance-capable)");
    assertEquals(MiniPlayer.chooseRemuxPushMode(true, false, null, true), "mpeg2tsremux",
        "Active enhance tier on a TS-only client -> mpeg2tsremux");
  }

  /**
   * Enhance requested but the client has NO MPEG2-TS push: enhancement cannot be
   * delivered as a TS remux, so fall through to the normal container choice rather
   * than fabricating a TS push the client can't receive.
   */
  @Test
  public void testChooseRemux_enhanceWithoutTsFallsThrough()
  {
    assertEquals(MiniPlayer.chooseRemuxPushMode(false, true, null, true), "mpeg2psremux",
        "Enhance active but no TS push -> normal PS choice (no unreceivable TS push)");
  }

  /** Null mcsr degrades to the safe PS default. */
  @Test
  public void testChooseRemux_nullClientDefaultsToPs()
  {
    assertEquals(MiniPlayer.chooseRemuxPushMode(null, null, false), "mpeg2psremux",
        "Null renderer -> safe MPEG2-PS default");
  }

  // ---- high-fps MPEG-2 copy-remux -> H.264 re-encode ---------------------------

  /**
   * A 720p59.94 MPEG-2 source that would otherwise be copied through as a
   * copy-family remux must be re-encoded to H.264-in-TS when the client can
   * decode H.264 push — the copy path tears once the client buffer drains.
   */
  @Test
  public void testPreferH264_highFpsMpeg2CopyReencodes()
  {
    assertTrue(MiniPlayer.preferH264ReencodeOverMpeg2Copy("MPEG2-Video", 59.94, true, false),
        "720p59.94 MPEG-2 with H.264-capable client must re-encode to H.264");
    assertEquals(MiniPlayer.maybeReencodeHighFpsMpeg2Remux(
        "mpeg2tsremux", "MPEG2-Video", 59.94, true, false), "dynamich264",
        "High-fps MPEG-2 copy-family TS remux must become dynamich264");
    assertEquals(MiniPlayer.maybeReencodeHighFpsMpeg2Remux(
        "mpeg2psremux", "MPEG2-Video", 60.0, true, false), "dynamich264",
        "High-fps MPEG-2 copy-family PS remux must become dynamich264");
  }

  /** Ordinary 29.97 MPEG-2 stays a plain copy remux (no needless re-encode). */
  @Test
  public void testPreferH264_normalFpsMpeg2StaysCopy()
  {
    assertFalse(MiniPlayer.preferH264ReencodeOverMpeg2Copy("MPEG2-Video", 29.97, true, false),
        "29.97 MPEG-2 is within the client's MPEG-2 decode budget — keep the copy remux");
    assertEquals(MiniPlayer.maybeReencodeHighFpsMpeg2Remux(
        "mpeg2tsremux", "MPEG2-Video", 29.97, true, false), "mpeg2tsremux",
        "Standard-fps MPEG-2 remux is left untouched");
  }

  /** Enhancement active must keep the copy-family remux (enhance rides it). */
  @Test
  public void testPreferH264_enhanceKeepsCopyRemux()
  {
    assertFalse(MiniPlayer.preferH264ReencodeOverMpeg2Copy("MPEG2-Video", 59.94, true, true),
        "Active enhance tier must never be diverted off the copy-family remux");
    assertEquals(MiniPlayer.maybeReencodeHighFpsMpeg2Remux(
        "mpeg2tsremux", "MPEG2-Video", 59.94, true, true), "mpeg2tsremux",
        "Enhance-active high-fps remux stays mpeg2tsremux");
  }

  /** No H.264 push decoder -> nothing to gain, keep the copy remux. */
  @Test
  public void testPreferH264_noH264ClientStaysCopy()
  {
    assertFalse(MiniPlayer.preferH264ReencodeOverMpeg2Copy("MPEG2-Video", 59.94, false, false),
        "Without an H.264 push decoder the copy remux is the only option");
  }

  /** Non-MPEG-2 high-fps sources (e.g. H.264) are not diverted. */
  @Test
  public void testPreferH264_nonMpeg2Untouched()
  {
    assertFalse(MiniPlayer.preferH264ReencodeOverMpeg2Copy("H.264", 59.94, true, false),
        "An H.264 source is already the strong codec — no re-encode");
    assertEquals(MiniPlayer.maybeReencodeHighFpsMpeg2Remux(
        "mpeg2tsremux", "H.264", 59.94, true, false), "mpeg2tsremux",
        "Non-MPEG-2 remux mode is passed through unchanged");
  }

  /** Unknown (0) fps must not trigger a re-encode (fail-safe to copy). */
  @Test
  public void testPreferH264_unknownFpsStaysCopy()
  {
    assertFalse(MiniPlayer.preferH264ReencodeOverMpeg2Copy("MPEG2-Video", 0, true, false),
        "Unknown fps must not be treated as high-fps");
  }

  /** A custom container= remux mode is never a copy-family mode -> untouched. */
  @Test
  public void testMaybeReencode_customContainerModeUntouched()
  {
    String custom = "container=mpegts;videocodec=COPY;audiocodec=ac3";
    assertEquals(MiniPlayer.maybeReencodeHighFpsMpeg2Remux(
        custom, "MPEG2-Video", 59.94, true, false), custom,
        "A custom container= mode is not a copy-family remux and must pass through");
  }
}
