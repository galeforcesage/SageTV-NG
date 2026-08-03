package sage.client;

import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import static org.testng.Assert.*;

/**
 * Parser + decision-integration tests for {@link ClientConstraints}.
 *
 * Uses the example strings from the schema v2 spec:
 *   EXO_VIDEO_CONSTRAINTS = MPEG2-VIDEO;scan=progressive;interlaced=false;decoder=hw,H.264;scan=interlaced+progressive;interlaced=true;decoder=hw
 *   EXO_AUDIO_CONSTRAINTS = AC3;decode=true;passthrough=true,AAC;decode=true;passthrough=false
 *   EXO_CONTAINER_CONSTRAINTS = MPEG2-TS;push=true;pull=false,MATROSKA;push=false;pull=true
 */
public class ClientConstraintsTest
{
  private static final String EXAMPLE_VIDEO =
      "MPEG2-VIDEO;scan=progressive;interlaced=false;decoder=hw,"
    + "H.264;scan=interlaced+progressive;interlaced=true;decoder=hw";
  private static final String EXAMPLE_AUDIO =
      "AC3;decode=true;passthrough=true,AAC;decode=true;passthrough=false";
  private static final String EXAMPLE_CONTAINER =
      "MPEG2-TS;push=true;pull=false,MATROSKA;push=false;pull=true";

  private static ClientProfile profile(String id, Collection<String> containers,
      Collection<String> video, Collection<String> audio, String autoRemux)
  {
    return new ClientProfile(id, id, true, containers, video, audio,
        /*allowHevc=*/true, autoRemux,
        /*maxW=*/3840, /*maxH=*/2160, /*allowOverrides=*/false);
  }

  // -------------------- Parser tests --------------------

  @Test
  public void parse_videoRows_extractsAllAttributes()
  {
    ClientConstraints c = ClientConstraints.parse("exoplayer", EXAMPLE_VIDEO, null, null);

    ClientConstraints.VideoConstraint mpeg2 = c.getVideo("MPEG2-VIDEO");
    assertNotNull(mpeg2);
    assertEquals(mpeg2.scan, ClientConstraints.Scan.PROGRESSIVE);
    assertEquals(mpeg2.interlaced, ClientConstraints.Tri.FALSE);
    assertEquals(mpeg2.decoder, ClientConstraints.Decoder.HW);

    ClientConstraints.VideoConstraint h264 = c.getVideo("H.264");
    assertNotNull(h264);
    assertEquals(h264.scan, ClientConstraints.Scan.INTERLACED_AND_PROGRESSIVE);
    assertEquals(h264.interlaced, ClientConstraints.Tri.TRUE);
    assertEquals(h264.decoder, ClientConstraints.Decoder.HW);
  }

  @Test
  public void parse_videoLookup_caseAndDialectInsensitive()
  {
    ClientConstraints c = ClientConstraints.parse("exoplayer", EXAMPLE_VIDEO, null, null);
    assertSame(c.getVideo("H.264"), c.getVideo("H264"));
    assertSame(c.getVideo("h.264"), c.getVideo("H.264"));
    assertSame(c.getVideo("MPEG2-Video"), c.getVideo("MPEG2-VIDEO"));
    assertSame(c.getVideo("mpeg2video"), c.getVideo("MPEG2-VIDEO"));
  }

  @Test
  public void parse_audioRows_extractsAllAttributes()
  {
    ClientConstraints c = ClientConstraints.parse("exoplayer", null, EXAMPLE_AUDIO, null);

    ClientConstraints.AudioConstraint ac3 = c.getAudio("AC3");
    assertNotNull(ac3);
    assertEquals(ac3.decode, ClientConstraints.Tri.TRUE);
    assertEquals(ac3.passthrough, ClientConstraints.Tri.TRUE);

    ClientConstraints.AudioConstraint aac = c.getAudio("AAC");
    assertNotNull(aac);
    assertEquals(aac.decode, ClientConstraints.Tri.TRUE);
    assertEquals(aac.passthrough, ClientConstraints.Tri.FALSE);
  }

  @Test
  public void parse_containerRows_extractsAllAttributes()
  {
    ClientConstraints c = ClientConstraints.parse("exoplayer", null, null, EXAMPLE_CONTAINER);

    ClientConstraints.ContainerConstraint ts = c.getContainer("MPEG2-TS");
    assertNotNull(ts);
    assertEquals(ts.push, ClientConstraints.Tri.TRUE);
    assertEquals(ts.pull, ClientConstraints.Tri.FALSE);

    ClientConstraints.ContainerConstraint mkv = c.getContainer("MATROSKA");
    assertNotNull(mkv);
    assertEquals(mkv.push, ClientConstraints.Tri.FALSE);
    assertEquals(mkv.pull, ClientConstraints.Tri.TRUE);
  }

  @Test
  public void parse_missingRowReturnsNull()
  {
    ClientConstraints c = ClientConstraints.parse("exoplayer", EXAMPLE_VIDEO, EXAMPLE_AUDIO, EXAMPLE_CONTAINER);
    assertNull(c.getVideo("AV1"));
    assertNull(c.getAudio("OPUS"));
    assertNull(c.getContainer("MP4"));
  }

  @Test
  public void parse_missingAttributeIsUnknown()
  {
    ClientConstraints c = ClientConstraints.parse("exoplayer",
        "H.264;interlaced=true", null, null);
    ClientConstraints.VideoConstraint h264 = c.getVideo("H.264");
    assertNotNull(h264);
    assertEquals(h264.interlaced, ClientConstraints.Tri.TRUE);
    assertEquals(h264.scan, ClientConstraints.Scan.UNKNOWN);
    assertEquals(h264.decoder, ClientConstraints.Decoder.UNKNOWN);
  }

  @Test
  public void parse_emptyAndNullInputsYieldEmpty()
  {
    assertTrue(ClientConstraints.parse("", null, null, null).isEmpty());
    assertTrue(ClientConstraints.parse("exoplayer", "", "", "").isEmpty());
    assertTrue(ClientConstraints.parse("exoplayer", "   ", null, null).isEmpty());
  }

  @Test
  public void parse_h265AliasFoldsToHevc()
  {
    ClientConstraints c = ClientConstraints.parse("exoplayer",
        "HEVC;interlaced=false;decoder=hw", null, null);
    assertNotNull(c.getVideo("H265"));
    assertSame(c.getVideo("H265"), c.getVideo("HEVC"));
  }

  // -------------------- Engine-integration tests --------------------
  // Critical interlaced gate: ExoPlayer + interlaced MPEG-2 = black video.

  @Test
  public void engine_interlacedSource_clientRowFalse_downgradesToTranscode()
  {
    ClientProfile profile = profile("test_modern",
        Arrays.asList("MPEG2-PS", "MPEG2-TS"),
        Arrays.asList("MPEG2-VIDEO", "H.264"),
        Arrays.asList("AC3", "AAC"),
        ClientProfile.AUTO_REMUX_ON_FAILURE);

    ClientConstraints fold = ClientConstraints.parse("exoplayer",
        "MPEG2-VIDEO;scan=progressive;interlaced=false;decoder=hw,"
      + "H.264;scan=interlaced+progressive;interlaced=true;decoder=hw",
        "AC3;decode=true;passthrough=true",
        "MPEG2-PS;push=true;pull=true");

    PlaybackDecisionEngine.PlaybackDecision d = PlaybackDecisionEngine.evaluate(
        profile, "MPEG2-PS", "MPEG2-Video", "AC3", 1920, 1080,
        false, 0, 0, fold, /*sourceInterlaced=*/true, /*isPushTransport=*/true);

    assertEquals(d.decision, PlaybackDecisionEngine.Decision.TRANSCODE,
        "Interlaced MPEG-2 source + client row interlaced=false must NOT direct-play");
  }

  @Test
  public void engine_progressiveSource_clientRowFalse_stillDirectPlays()
  {
    ClientProfile profile = profile("test_modern",
        Collections.singletonList("MPEG2-PS"),
        Collections.singletonList("MPEG2-VIDEO"),
        Collections.singletonList("AC3"),
        ClientProfile.AUTO_REMUX_ON_FAILURE);

    ClientConstraints fold = ClientConstraints.parse("exoplayer",
        "MPEG2-VIDEO;scan=progressive;interlaced=false", "AC3;decode=true", null);

    PlaybackDecisionEngine.PlaybackDecision d = PlaybackDecisionEngine.evaluate(
        profile, "MPEG2-PS", "MPEG2-Video", "AC3", 1280, 720,
        false, 0, 0, fold, /*sourceInterlaced=*/false, /*isPushTransport=*/true);

    assertEquals(d.decision, PlaybackDecisionEngine.Decision.DIRECT_PLAY);
  }

  @Test
  public void engine_interlacedSource_clientRowTrue_directPlays()
  {
    ClientProfile profile = profile("test_shield",
        Collections.singletonList("MPEG2-PS"),
        Collections.singletonList("MPEG2-VIDEO"),
        Collections.singletonList("AC3"),
        ClientProfile.AUTO_REMUX_ON_FAILURE);

    ClientConstraints shield = ClientConstraints.parse("exoplayer",
        "MPEG2-VIDEO;scan=interlaced+progressive;interlaced=true;decoder=hw",
        "AC3;decode=true;passthrough=true",
        "MPEG2-PS;push=true;pull=true");

    PlaybackDecisionEngine.PlaybackDecision d = PlaybackDecisionEngine.evaluate(
        profile, "MPEG2-PS", "MPEG2-Video", "AC3", 1920, 1080,
        false, 0, 0, shield, /*sourceInterlaced=*/true, /*isPushTransport=*/true);

    assertEquals(d.decision, PlaybackDecisionEngine.Decision.DIRECT_PLAY,
        "Shield-like client (interlaced=true) must direct-play interlaced source");
  }

  @Test
  public void engine_legacyClient_nullConstraints_preservesExistingBehavior()
  {
    ClientProfile profile = profile("test_legacy",
        Collections.singletonList("MPEG2-PS"),
        Collections.singletonList("MPEG2-VIDEO"),
        Collections.singletonList("AC3"),
        ClientProfile.AUTO_REMUX_ON_FAILURE);

    PlaybackDecisionEngine.PlaybackDecision d = PlaybackDecisionEngine.evaluate(
        profile, "MPEG2-PS", "MPEG2-Video", "AC3", 1920, 1080,
        false, 0, 0, null, /*sourceInterlaced=*/true, /*isPushTransport=*/true);

    assertEquals(d.decision, PlaybackDecisionEngine.Decision.DIRECT_PLAY,
        "Legacy client (null constraints) keeps pre-schema-v2 direct-play behavior");
  }

  @Test
  public void engine_constraintsPresentButRowMissing_rejectsAsUnsupported()
  {
    // Schema v2 semantic: a populated constraint set is the player's COMPLETE
    // capability declaration. ExoPlayer omits MPEG2-VIDEO from its set because
    // it physically cannot decode MPEG-2. A missing row in a non-empty set
    // therefore means "not supported by this player" and must NOT direct-play.
    ClientProfile profile = profile("test_partial",
        Collections.singletonList("MPEG2-PS"),
        Arrays.asList("MPEG2-VIDEO", "H.264"),
        Collections.singletonList("AC3"),
        ClientProfile.AUTO_REMUX_ON_FAILURE);

    ClientConstraints partial = ClientConstraints.parse("exoplayer",
        "H.264;interlaced=true",  // no MPEG2-VIDEO row -> ExoPlayer can't play it
        "AC3;decode=true",
        "MPEG2-PS;push=true;pull=true");

    PlaybackDecisionEngine.PlaybackDecision d = PlaybackDecisionEngine.evaluate(
        profile, "MPEG2-PS", "MPEG2-Video", "AC3", 1920, 1080,
        false, 0, 0, partial, /*sourceInterlaced=*/true, /*isPushTransport=*/true);

    assertEquals(d.decision, PlaybackDecisionEngine.Decision.TRANSCODE,
        "Codec missing from a populated video constraint set must be treated as unsupported");
  }

  @Test
  public void engine_videoConstraintsEmpty_othersPopulated_preservesLegacy()
  {
    // If the video set is null/empty but audio is populated, the engine must
    // still fall back to the legacy profile codec list for video (per spec
    // rule "missing constraint set = UNKNOWN").
    ClientProfile profile = profile("test_partial",
        Collections.singletonList("MPEG2-PS"),
        Collections.singletonList("MPEG2-VIDEO"),
        Collections.singletonList("AC3"),
        ClientProfile.AUTO_REMUX_ON_FAILURE);

    ClientConstraints partial = ClientConstraints.parse("exoplayer",
        null,                  // video set absent -> UNKNOWN
        "AC3;decode=true", null);

    PlaybackDecisionEngine.PlaybackDecision d = PlaybackDecisionEngine.evaluate(
        profile, "MPEG2-PS", "MPEG2-Video", "AC3", 1920, 1080,
        false, 0, 0, partial, /*sourceInterlaced=*/false, /*isPushTransport=*/true);

    assertEquals(d.decision, PlaybackDecisionEngine.Decision.DIRECT_PLAY,
        "Empty video constraint set must fall back to legacy profile codec list");
  }

  @Test
  public void engine_playerSwitch_exoCannotMpeg2Interlaced_switchesToIjk()
  {
    // Magnum scenario: source is interlaced MPEG2-Video in MPEG2-PS.
    // Default player (exo) does NOT list MPEG2-VIDEO; alt player (ijk) does
    // with interlaced=true. Engine must recommend a player switch instead
    // of a transcode.
    ClientProfile profile = profile("android_galaxy",
        Collections.singletonList("MPEG2-PS"),
        Collections.singletonList("MPEG2-VIDEO"),
        Collections.singletonList("AC3"),
        ClientProfile.AUTO_REMUX_ON_FAILURE);

    // Exo has H.264/HEVC but not MPEG2-VIDEO.
    ClientConstraints exo = ClientConstraints.parse("exoplayer",
        "H.264;scan=interlaced+progressive;interlaced=true;decoder=hw",
        "AC3;decode=true",
        "MPEG2-PS;push=true;pull=true");
    // Ijk has MPEG2-VIDEO with interlaced=true.
    ClientConstraints ijk = ClientConstraints.parse("ijkplayer",
        "MPEG2-VIDEO;scan=interlaced+progressive;interlaced=true;decoder=sw_or_hw,"
            + "H.264;scan=interlaced+progressive;interlaced=true;decoder=sw_or_hw",
        "AC3;decode=true",
        "MPEG2-PS;push=true;pull=true");

    PlaybackDecisionEngine.PlaybackDecision d =
        PlaybackDecisionEngine.evaluateWithPlayerSwitch(
            profile, "MPEG2-PS", "MPEG2-Video", "AC3",
            1920, 1080, false, 0, 0,
            "exoplayer", "ijkplayer", exo, ijk,
            /*sourceInterlaced=*/true, /*isPushTransport=*/true);

    assertEquals(d.decision, PlaybackDecisionEngine.Decision.DIRECT_PLAY,
        "Engine should pick alt player path and direct-play");
    assertEquals(d.preferredPlayer, "ijkplayer",
        "Engine must tag the decision with the alternate player tag");
  }

  @Test
  public void engine_playerSwitch_altAlsoCannot_keepsPrimaryDecision()
  {
    // Neither player can handle the source -> no switch, primary stands.
    ClientProfile profile = profile("android_galaxy",
        Collections.singletonList("MPEG2-PS"),
        Collections.singletonList("MPEG2-VIDEO"),
        Collections.singletonList("AC3"),
        ClientProfile.AUTO_REMUX_ON_FAILURE);

    // Exo lacks MPEG2-VIDEO entirely.
    ClientConstraints exo = ClientConstraints.parse("exoplayer",
        "H.264;scan=progressive;interlaced=false;decoder=hw",
        "AC3;decode=true",
        "MPEG2-PS;push=true;pull=true");
    // Ijk has MPEG2-VIDEO but only progressive (interlaced=false).
    ClientConstraints ijk = ClientConstraints.parse("ijkplayer",
        "MPEG2-VIDEO;scan=progressive;interlaced=false;decoder=sw",
        "AC3;decode=true",
        "MPEG2-PS;push=true;pull=true");

    PlaybackDecisionEngine.PlaybackDecision d =
        PlaybackDecisionEngine.evaluateWithPlayerSwitch(
            profile, "MPEG2-PS", "MPEG2-Video", "AC3",
            1920, 1080, false, 0, 0,
            "exoplayer", "ijkplayer", exo, ijk,
            /*sourceInterlaced=*/true, /*isPushTransport=*/true);

    assertNotEquals(d.decision, PlaybackDecisionEngine.Decision.DIRECT_PLAY,
        "Neither player can handle interlaced MPEG2 here, must NOT direct-play");
    assertNull(d.preferredPlayer,
        "No player switch should be recommended when alt also fails");
  }

  @Test
  public void engine_playerSwitch_primaryAlreadyDirectPlay_noSwitch()
  {
    // Primary can play it directly -> engine must NOT recommend a switch.
    ClientProfile profile = profile("android_galaxy",
        Collections.singletonList("MPEG2-PS"),
        Collections.singletonList("H.264"),
        Collections.singletonList("AC3"),
        ClientProfile.AUTO_REMUX_ON_FAILURE);

    ClientConstraints exo = ClientConstraints.parse("exoplayer",
        "H.264;scan=interlaced+progressive;interlaced=true;decoder=hw",
        "AC3;decode=true",
        "MPEG2-PS;push=true;pull=true");
    ClientConstraints ijk = ClientConstraints.parse("ijkplayer",
        "H.264;scan=interlaced+progressive;interlaced=true;decoder=sw",
        "AC3;decode=true",
        "MPEG2-PS;push=true;pull=true");

    PlaybackDecisionEngine.PlaybackDecision d =
        PlaybackDecisionEngine.evaluateWithPlayerSwitch(
            profile, "MPEG2-PS", "H.264", "AC3",
            1920, 1080, false, 0, 0,
            "exoplayer", "ijkplayer", exo, ijk,
            /*sourceInterlaced=*/false, /*isPushTransport=*/true);

    assertEquals(d.decision, PlaybackDecisionEngine.Decision.DIRECT_PLAY);
    assertNull(d.preferredPlayer, "No switch when default already direct-plays");
  }

  // -------------------- Item 3: honor audio passthrough --------------------
  // Legacy evaluate() gate: a codec the client cannot DECODE (decode=false)
  // but CAN bitstream/passthrough (passthrough=true) must stay audio-OK, so
  // the decision is DIRECT_PLAY (audio copy), NOT a transcode that would
  // strip the lossless bitstream. Gated by playback/honor_audio_passthrough.

  private static ClientProfile dtsProfile()
  {
    return profile("android_avr",
        Collections.singletonList("MPEG2-PS"),
        Collections.singletonList("H.264"),
        Arrays.asList("DTS", "AAC"),
        ClientProfile.AUTO_REMUX_ON_FAILURE);
  }

  @Test
  public void engine_audioDtsDecodeFalsePassthroughTrue_directPlays() throws Throwable
  {
    sage.TestUtils.initializeSageTVForTesting();
    sage.Sage.put("playback/honor_audio_passthrough", "true");

    ClientConstraints avr = ClientConstraints.parse("exoplayer",
        "H.264;scan=progressive;interlaced=false;decoder=hw",
        "DTS;decode=false;passthrough=true",
        "MPEG2-PS;push=true;pull=true");

    PlaybackDecisionEngine.PlaybackDecision d = PlaybackDecisionEngine.evaluate(
        dtsProfile(), "MPEG2-PS", "H.264", "DTS", 1920, 1080,
        false, 0, 0, avr, /*sourceInterlaced=*/false, /*isPushTransport=*/true);

    assertEquals(d.decision, PlaybackDecisionEngine.Decision.DIRECT_PLAY,
        "DTS decode=false but passthrough=true must DIRECT_PLAY (audio bitstreamed), "
            + "not transcode");
    assertEquals(d.targetAudioCodec, "DTS",
        "Passthrough must keep the original DTS audio, not re-target it");
  }

  @Test
  public void engine_audioDtsDecodeFalsePassthroughFalse_transcodes() throws Throwable
  {
    sage.TestUtils.initializeSageTVForTesting();
    sage.Sage.put("playback/honor_audio_passthrough", "true");

    ClientConstraints avr = ClientConstraints.parse("exoplayer",
        "H.264;scan=progressive;interlaced=false;decoder=hw",
        "DTS;decode=false;passthrough=false,AAC;decode=true;passthrough=false",
        "MPEG2-PS;push=true;pull=true");

    PlaybackDecisionEngine.PlaybackDecision d = PlaybackDecisionEngine.evaluate(
        dtsProfile(), "MPEG2-PS", "H.264", "DTS", 1920, 1080,
        false, 0, 0, avr, /*sourceInterlaced=*/false, /*isPushTransport=*/true);

    assertEquals(d.decision, PlaybackDecisionEngine.Decision.TRANSCODE,
        "DTS decode=false passthrough=false must transcode the audio (existing behavior), "
            + "NOT direct-play");
  }

  @Test
  public void engine_audioPassthroughGateOff_ignoresPassthroughTri() throws Throwable
  {
    sage.TestUtils.initializeSageTVForTesting();
    // Safe rollback: with the gate OFF, passthrough=true is ignored and the
    // legacy decode-only reject stands -> transcode.
    sage.Sage.put("playback/honor_audio_passthrough", "false");
    try
    {
      ClientConstraints avr = ClientConstraints.parse("exoplayer",
          "H.264;scan=progressive;interlaced=false;decoder=hw",
          "DTS;decode=false;passthrough=true",
          "MPEG2-PS;push=true;pull=true");

      PlaybackDecisionEngine.PlaybackDecision d = PlaybackDecisionEngine.evaluate(
          dtsProfile(), "MPEG2-PS", "H.264", "DTS", 1920, 1080,
          false, 0, 0, avr, /*sourceInterlaced=*/false, /*isPushTransport=*/true);

      assertEquals(d.decision, PlaybackDecisionEngine.Decision.TRANSCODE,
          "With honor_audio_passthrough=false the passthrough Tri must be ignored (transcode)");
    }
    finally
    {
      sage.Sage.put("playback/honor_audio_passthrough", "true");
    }
  }
}

