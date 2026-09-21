package sage.client;

import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.*;

/**
 * Unit tests for {@link PlaybackDecisionEngine#promoteForServerEqIfRequested}
 * -- the server-side Audio Equalizer (v1) extension that forces an
 * audio-only transcode (video stays copy) when a client explicitly requests
 * server EQ on content that would otherwise DIRECT_PLAY/REMUX with no
 * audio-encode stage for the {@code -af} filtergraph to attach to.
 *
 * <p>These tests exercise the promotion logic in isolation against
 * hand-built {@link PlaybackDecisionEngine.SurfaceDecision} lists, without
 * needing a real {@link PlaybackSurfaceSet} or ffprobe/ffmpeg.
 */
public class PlaybackDecisionEngineTest
{
  private static PlaybackSurface surface(String id, int priority)
  {
    return new PlaybackSurface(id, "route", priority,
        java.util.Arrays.asList("pull-xcode"),
        java.util.Arrays.asList("H264"),
        java.util.Arrays.asList("AAC"),
        java.util.Arrays.asList("MP4"));
  }

  private static PlaybackDecisionEngine.SurfaceDecision decisionOf(
      String surfaceId, int priority, PlaybackDecisionEngine.Decision decision, String xcodeMode)
  {
    PlaybackDecisionEngine.PlaybackDecision pd = new PlaybackDecisionEngine.PlaybackDecision(
        decision, "test", "MP4", "H264", "AAC");
    return new PlaybackDecisionEngine.SurfaceDecision(surface(surfaceId, priority), pd, "pull-xcode", null, xcodeMode);
  }

  // -----------------------------------------------------------------------
  // Test Case (a): promotion happens when winner=DIRECT_PLAY and a ranked
  // AUDIO_TRANSCODE/browserhd_copyv candidate exists, EQ requested.
  // -----------------------------------------------------------------------
  @Test
  public void testPromotesAudioTranscodeCandidate_WhenEqRequested()
  {
    PlaybackDecisionEngine.SurfaceDecision directPlayWinner =
        decisionOf("pwa_native", 10, PlaybackDecisionEngine.Decision.DIRECT_PLAY, null);
    PlaybackDecisionEngine.SurfaceDecision audioTranscodeCandidate =
        decisionOf("browserhd", 5, PlaybackDecisionEngine.Decision.AUDIO_TRANSCODE, "browserhd_copyv");

    List<PlaybackDecisionEngine.SurfaceDecision> ranked = new ArrayList<>();
    ranked.add(directPlayWinner); // ranked.get(0) -- cheapest tier wins pre-promotion
    ranked.add(audioTranscodeCandidate);

    PlaybackDecisionEngine.SurfaceDecision result = PlaybackDecisionEngine.promoteForServerEqIfRequested(
        ranked, directPlayWinner, true);

    assertSame(result, audioTranscodeCandidate,
        "Should promote the ranked AUDIO_TRANSCODE/browserhd_copyv candidate over the cheaper DIRECT_PLAY winner");
  }

  // -----------------------------------------------------------------------
  // Test Case (b): no promotion when EQ not requested (net-neutral).
  // -----------------------------------------------------------------------
  @Test
  public void testNoPromotion_WhenEqNotRequested()
  {
    PlaybackDecisionEngine.SurfaceDecision directPlayWinner =
        decisionOf("pwa_native", 10, PlaybackDecisionEngine.Decision.DIRECT_PLAY, null);
    PlaybackDecisionEngine.SurfaceDecision audioTranscodeCandidate =
        decisionOf("browserhd", 5, PlaybackDecisionEngine.Decision.AUDIO_TRANSCODE, "browserhd_copyv");

    List<PlaybackDecisionEngine.SurfaceDecision> ranked = new ArrayList<>();
    ranked.add(directPlayWinner);
    ranked.add(audioTranscodeCandidate);

    PlaybackDecisionEngine.SurfaceDecision result = PlaybackDecisionEngine.promoteForServerEqIfRequested(
        ranked, directPlayWinner, false);

    assertSame(result, directPlayWinner,
        "Sessions that did not request server EQ must be completely unaffected (net-neutral)");
  }

  // -----------------------------------------------------------------------
  // Test Case (c): no promotion when no video-copy-compatible candidate
  // exists (the MPEG2 boundary case -- only DIRECT_PLAY/TRANSCODE entries,
  // no AUDIO_TRANSCODE/browserhd_copyv anywhere in ranked).
  // -----------------------------------------------------------------------
  @Test
  public void testNoPromotion_WhenNoVideoCopyCompatibleCandidateExists()
  {
    PlaybackDecisionEngine.SurfaceDecision directPlayWinner =
        decisionOf("pwa_native", 10, PlaybackDecisionEngine.Decision.DIRECT_PLAY, null);
    // Simulates e.g. MPEG2 video: the browser surface's own natural decision
    // is a full TRANSCODE (video re-encode), not AUDIO_TRANSCODE, because no
    // browser/MSE surface can copy MPEG2 video into fragmented MP4.
    PlaybackDecisionEngine.SurfaceDecision fullTranscodeCandidate =
        decisionOf("browserhd", 5, PlaybackDecisionEngine.Decision.TRANSCODE, "browserhd");

    List<PlaybackDecisionEngine.SurfaceDecision> ranked = new ArrayList<>();
    ranked.add(directPlayWinner);
    ranked.add(fullTranscodeCandidate);

    PlaybackDecisionEngine.SurfaceDecision result = PlaybackDecisionEngine.promoteForServerEqIfRequested(
        ranked, directPlayWinner, true);

    assertSame(result, directPlayWinner,
        "With no AUDIO_TRANSCODE/browserhd_copyv candidate anywhere in ranked (e.g. MPEG2 video), "
            + "EQ must fall back to the existing winner unchanged");
  }

  // -----------------------------------------------------------------------
  // Test Case (d): no-op when winner already offers an audio-encode stage.
  // -----------------------------------------------------------------------
  @Test
  public void testNoPromotion_WhenWinnerAlreadyHasAudioEncodeStage()
  {
    PlaybackDecisionEngine.SurfaceDecision winnerAlreadyAudioTranscode =
        decisionOf("browserhd", 5, PlaybackDecisionEngine.Decision.AUDIO_TRANSCODE, "browserhd_copyv");

    List<PlaybackDecisionEngine.SurfaceDecision> ranked = new ArrayList<>();
    ranked.add(winnerAlreadyAudioTranscode);

    PlaybackDecisionEngine.SurfaceDecision result = PlaybackDecisionEngine.promoteForServerEqIfRequested(
        ranked, winnerAlreadyAudioTranscode, true);

    assertSame(result, winnerAlreadyAudioTranscode,
        "Winner that already has an active audio-encode stage should be returned unchanged");
  }

  @Test
  public void testNoPromotion_WhenWinnerAlreadyBrowserhdXcodeMode()
  {
    // A full TRANSCODE decision that already xcodes via "browserhd" (video
    // re-encode + audio re-encode) already has an active audio stage too --
    // no promotion needed even though the Decision enum value isn't
    // AUDIO_TRANSCODE itself.
    PlaybackDecisionEngine.SurfaceDecision winner =
        decisionOf("browserhd", 5, PlaybackDecisionEngine.Decision.TRANSCODE, "browserhd");

    List<PlaybackDecisionEngine.SurfaceDecision> ranked = new ArrayList<>();
    ranked.add(winner);

    PlaybackDecisionEngine.SurfaceDecision result = PlaybackDecisionEngine.promoteForServerEqIfRequested(
        ranked, winner, true);

    assertSame(result, winner,
        "Winner already using the browserhd xcodeMode (full re-encode, audio stage present) should be unchanged");
  }

  // -----------------------------------------------------------------------
  // Edge cases: null winner / null ranked must not throw.
  // -----------------------------------------------------------------------
  @Test
  public void testNullWinner_ReturnsNull()
  {
    List<PlaybackDecisionEngine.SurfaceDecision> ranked = new ArrayList<>();
    PlaybackDecisionEngine.SurfaceDecision result =
        PlaybackDecisionEngine.promoteForServerEqIfRequested(ranked, null, true);
    assertNull(result);
  }

  @Test
  public void testNullRanked_DoesNotThrow_ReturnsWinner()
  {
    PlaybackDecisionEngine.SurfaceDecision directPlayWinner =
        decisionOf("pwa_native", 10, PlaybackDecisionEngine.Decision.DIRECT_PLAY, null);
    PlaybackDecisionEngine.SurfaceDecision result =
        PlaybackDecisionEngine.promoteForServerEqIfRequested(null, directPlayWinner, true);
    assertSame(result, directPlayWinner);
  }

  // -----------------------------------------------------------------------
  // Test Case (e): a REMUX/browserhd_remux candidate (codecs already match,
  // audio would otherwise copy) gets rerouted to AUDIO_TRANSCODE/
  // browserhd_copyv when EQ requested and no better AUDIO_TRANSCODE
  // candidate exists -- forcing the audio stage from copy to a real encode
  // of the SAME codec so -af has something to attach to.
  // -----------------------------------------------------------------------
  @Test
  public void testPromotesRemuxCandidate_ForcingAudioFlip_WhenNoAudioTranscodeCandidateExists()
  {
    PlaybackDecisionEngine.SurfaceDecision directPlayWinner =
        decisionOf("pwa_native", 10, PlaybackDecisionEngine.Decision.DIRECT_PLAY, null);
    PlaybackDecisionEngine.SurfaceDecision remuxCandidate =
        decisionOf("browserhd", 5, PlaybackDecisionEngine.Decision.REMUX, "browserhd_remux");

    List<PlaybackDecisionEngine.SurfaceDecision> ranked = new ArrayList<>();
    ranked.add(directPlayWinner);
    ranked.add(remuxCandidate);

    PlaybackDecisionEngine.SurfaceDecision result = PlaybackDecisionEngine.promoteForServerEqIfRequested(
        ranked, directPlayWinner, true);

    assertNotSame(result, directPlayWinner, "Should not stay on the cheaper DIRECT_PLAY winner");
    assertNotSame(result, remuxCandidate, "Should be a NEW relabeled decision, not the raw REMUX candidate itself");
    assertEquals(result.decision.decision, PlaybackDecisionEngine.Decision.AUDIO_TRANSCODE,
        "REMUX should be relabeled AUDIO_TRANSCODE so the audio stage becomes a real encode");
    assertEquals(result.chosenXcodeMode, "browserhd_copyv",
        "Should reuse the existing browserhd_copyv xcodeMode/ffmpeg template -- no new mode invented");
    assertSame(result.surface, remuxCandidate.surface, "Surface identity must be preserved");
    assertEquals(result.decision.targetVideoCodec, remuxCandidate.decision.targetVideoCodec,
        "Video target codec must be carried over unchanged (still a copy)");
    assertEquals(result.decision.targetAudioCodec, remuxCandidate.decision.targetAudioCodec,
        "Audio target codec must be carried over unchanged -- never invented by this method");
  }

  // -----------------------------------------------------------------------
  // An AUDIO_TRANSCODE/browserhd_copyv candidate is preferred over a REMUX
  // candidate when both exist -- audio is already re-encoding there, so no
  // relabeling/flip is needed.
  // -----------------------------------------------------------------------
  @Test
  public void testPrefersAudioTranscodeCandidate_OverRemuxCandidate_WhenBothExist()
  {
    PlaybackDecisionEngine.SurfaceDecision directPlayWinner =
        decisionOf("pwa_native", 10, PlaybackDecisionEngine.Decision.DIRECT_PLAY, null);
    PlaybackDecisionEngine.SurfaceDecision audioTranscodeCandidate =
        decisionOf("browserhd_a", 5, PlaybackDecisionEngine.Decision.AUDIO_TRANSCODE, "browserhd_copyv");
    PlaybackDecisionEngine.SurfaceDecision remuxCandidate =
        decisionOf("browserhd_b", 6, PlaybackDecisionEngine.Decision.REMUX, "browserhd_remux");

    List<PlaybackDecisionEngine.SurfaceDecision> ranked = new ArrayList<>();
    ranked.add(directPlayWinner);
    ranked.add(audioTranscodeCandidate);
    ranked.add(remuxCandidate);

    PlaybackDecisionEngine.SurfaceDecision result = PlaybackDecisionEngine.promoteForServerEqIfRequested(
        ranked, directPlayWinner, true);

    assertSame(result, audioTranscodeCandidate,
        "An already-audio-transcoding candidate should win over a REMUX-flip candidate");
  }

  // -----------------------------------------------------------------------
  // REMUX candidates on the TV/AVPlay-family mpeg2tsremux xcodeMode are
  // intentionally OUT of v1 scope (matches browserhd/browserhd_copyv-only
  // scope already established) -- must NOT be promoted/rerouted.
  // -----------------------------------------------------------------------
  @Test
  public void testNoPromotion_ForMpeg2TsRemuxCandidate_OutOfScope()
  {
    PlaybackDecisionEngine.SurfaceDecision directPlayWinner =
        decisionOf("pwa_native", 10, PlaybackDecisionEngine.Decision.DIRECT_PLAY, null);
    PlaybackDecisionEngine.SurfaceDecision tvRemuxCandidate =
        decisionOf("tv_avplay", 5, PlaybackDecisionEngine.Decision.REMUX, "mpeg2tsremux");

    List<PlaybackDecisionEngine.SurfaceDecision> ranked = new ArrayList<>();
    ranked.add(directPlayWinner);
    ranked.add(tvRemuxCandidate);

    PlaybackDecisionEngine.SurfaceDecision result = PlaybackDecisionEngine.promoteForServerEqIfRequested(
        ranked, directPlayWinner, true);

    assertSame(result, directPlayWinner,
        "mpeg2tsremux (TV/AVPlay family) is out of v1 EQ scope and must not be rerouted");
  }

  // -----------------------------------------------------------------------
  // ranked is pre-sorted: the FIRST matching AUDIO_TRANSCODE/browserhd_copyv
  // candidate should win when multiple exist.
  // -----------------------------------------------------------------------
  @Test
  public void testPicksFirstMatchingCandidate_WhenMultipleExist()
  {
    PlaybackDecisionEngine.SurfaceDecision directPlayWinner =
        decisionOf("pwa_native", 10, PlaybackDecisionEngine.Decision.DIRECT_PLAY, null);
    PlaybackDecisionEngine.SurfaceDecision bestAudioTranscodeCandidate =
        decisionOf("browserhd_best", 5, PlaybackDecisionEngine.Decision.AUDIO_TRANSCODE, "browserhd_copyv");
    PlaybackDecisionEngine.SurfaceDecision secondAudioTranscodeCandidate =
        decisionOf("browserhd_second", 6, PlaybackDecisionEngine.Decision.AUDIO_TRANSCODE, "browserhd_copyv");

    List<PlaybackDecisionEngine.SurfaceDecision> ranked = new ArrayList<>();
    ranked.add(directPlayWinner);
    ranked.add(bestAudioTranscodeCandidate);
    ranked.add(secondAudioTranscodeCandidate);

    PlaybackDecisionEngine.SurfaceDecision result = PlaybackDecisionEngine.promoteForServerEqIfRequested(
        ranked, directPlayWinner, true);

    assertSame(result, bestAudioTranscodeCandidate,
        "Should pick the first (best-ranked) matching candidate, not just any match");
  }

  // =======================================================================
  // Item 8: per-profile bandwidth_safety_factor cascade
  // (playback/profile/<id>/bandwidth_safety_factor -> playback/bandwidth_safety_factor
  //  -> 0.85f constant; out-of-(0,1] values rejected to 0.85f)
  // =======================================================================
  private static final String GLOBAL_BSF = "playback/bandwidth_safety_factor";
  private static String profileBsfKey(String id) { return "playback/profile/" + id + "/bandwidth_safety_factor"; }

  @Test
  public void item8_perProfileOverrideWinsOverGlobal() throws Throwable
  {
    sage.TestUtils.initializeSageTVForTesting();
    sage.Sage.putFloat(GLOBAL_BSF, 0.70f);
    sage.Sage.putFloat(profileBsfKey("prof_a"), 0.50f);
    try
    {
      assertEquals(PlaybackDecisionEngine.resolveBandwidthSafetyFactor("prof_a"), 0.50f, 0.0001f,
          "Per-profile override must win over the global safety factor");
    }
    finally
    {
      sage.Sage.remove(profileBsfKey("prof_a"));
      sage.Sage.remove(GLOBAL_BSF);
    }
  }

  @Test
  public void item8_absentProfileOverrideFallsBackToGlobal() throws Throwable
  {
    sage.TestUtils.initializeSageTVForTesting();
    sage.Sage.putFloat(GLOBAL_BSF, 0.70f);
    sage.Sage.remove(profileBsfKey("prof_missing"));
    try
    {
      assertEquals(PlaybackDecisionEngine.resolveBandwidthSafetyFactor("prof_missing"), 0.70f, 0.0001f,
          "Absent per-profile key must fall back to the global safety factor");
    }
    finally
    {
      sage.Sage.remove(GLOBAL_BSF);
    }
  }

  @Test
  public void item8_globalAbsentFallsBackToConstant() throws Throwable
  {
    sage.TestUtils.initializeSageTVForTesting();
    sage.Sage.remove(GLOBAL_BSF);
    sage.Sage.remove(profileBsfKey("prof_x"));
    // No profile, no global -> the hard-coded 0.85f constant.
    assertEquals(PlaybackDecisionEngine.resolveBandwidthSafetyFactor(null), 0.85f, 0.0001f,
        "With neither key set, the cascade must resolve to the 0.85f constant");
    assertEquals(PlaybackDecisionEngine.resolveBandwidthSafetyFactor("prof_x"), 0.85f, 0.0001f,
        "Absent global + absent per-profile must still be 0.85f");
  }

  @Test
  public void item8_outOfRangeValuesRejectedToConstant() throws Throwable
  {
    sage.TestUtils.initializeSageTVForTesting();
    try
    {
      sage.Sage.putFloat(GLOBAL_BSF, 1.5f); // > 1 rejected
      assertEquals(PlaybackDecisionEngine.resolveBandwidthSafetyFactor(null), 0.85f, 0.0001f,
          "A safety factor > 1 must be rejected to 0.85f");
      sage.Sage.putFloat(GLOBAL_BSF, 0.0f); // <= 0 rejected
      assertEquals(PlaybackDecisionEngine.resolveBandwidthSafetyFactor(null), 0.85f, 0.0001f,
          "A safety factor of 0 must be rejected to 0.85f");
      sage.Sage.putFloat(profileBsfKey("prof_bad"), -0.2f);
      assertEquals(PlaybackDecisionEngine.resolveBandwidthSafetyFactor("prof_bad"), 0.85f, 0.0001f,
          "A negative per-profile safety factor must be rejected to 0.85f");
    }
    finally
    {
      sage.Sage.remove(GLOBAL_BSF);
      sage.Sage.remove(profileBsfKey("prof_bad"));
    }
  }

  // =======================================================================
  // P1: safeBaselineTranscodeDecision -- fail-closed baseline for NG sessions
  // =======================================================================
  @Test
  public void p1_safeBaselineDefaultsToH264AacFmp4Transcode()
  {
    PlaybackDecisionEngine.PlaybackDecision d =
        PlaybackDecisionEngine.safeBaselineTranscodeDecision(null, null, null);
    assertEquals(d.decision, PlaybackDecisionEngine.Decision.TRANSCODE,
        "Fail-closed baseline must be a TRANSCODE, never DIRECT_PLAY");
    assertEquals(d.targetContainer, "FMP4");
    assertEquals(d.targetVideoCodec, "H264");
    assertEquals(d.targetAudioCodec, "AAC");
  }

  @Test
  public void p1_safeBaselineHonorsOverrides()
  {
    PlaybackDecisionEngine.PlaybackDecision d =
        PlaybackDecisionEngine.safeBaselineTranscodeDecision("MP4", "HEVC", "AC3");
    assertEquals(d.decision, PlaybackDecisionEngine.Decision.TRANSCODE);
    assertEquals(d.targetContainer, "MP4");
    assertEquals(d.targetVideoCodec, "HEVC");
    assertEquals(d.targetAudioCodec, "AC3");
  }

  @Test
  public void p1_safeBaselineNeverDirectPlay_evenWithEmptyStrings()
  {
    PlaybackDecisionEngine.PlaybackDecision d =
        PlaybackDecisionEngine.safeBaselineTranscodeDecision("", "", "");
    assertEquals(d.decision, PlaybackDecisionEngine.Decision.TRANSCODE);
    assertEquals(d.targetContainer, "FMP4");
    assertEquals(d.targetVideoCodec, "H264");
    assertEquals(d.targetAudioCodec, "AAC");
  }

  // =======================================================================
  // Item 3 (surface path): evaluateForSurface passthrough overload
  // =======================================================================
  private static PlaybackSurface aacOnlySurface()
  {
    // Supports MP4 + H264 + AAC only (NOT DTS).
    return new PlaybackSurface("pwa_native", "route", 10,
        java.util.Arrays.asList("pull-xcode"),
        java.util.Arrays.asList("H264"),
        java.util.Arrays.asList("AAC"),
        java.util.Arrays.asList("MP4"));
  }

  @Test
  public void item3_surfacePassthroughSupported_directPlays() throws Throwable
  {
    sage.TestUtils.initializeSageTVForTesting();
    sage.Sage.put("playback/honor_audio_passthrough", "true");
    // Source audio DTS is NOT in the surface's decode list, but the caller
    // reports the client can passthrough it -> audio treated OK -> DIRECT_PLAY.
    PlaybackDecisionEngine.PlaybackDecision d = PlaybackDecisionEngine.evaluateForSurface(
        aacOnlySurface(), "MP4", "H264", "DTS", 1920, 1080, 0, 0,
        /*sourceInterlaced=*/false, /*audioPassthroughSupported=*/true);
    assertEquals(d.decision, PlaybackDecisionEngine.Decision.DIRECT_PLAY,
        "Surface lacking DTS decode but with passthrough supported must DIRECT_PLAY (audio copy)");
  }

  @Test
  public void item3_surfacePassthroughUnsupported_audioTranscodes() throws Throwable
  {
    sage.TestUtils.initializeSageTVForTesting();
    sage.Sage.put("playback/honor_audio_passthrough", "true");
    // Same source, but passthrough NOT supported -> audio codec mismatch ->
    // AUDIO_TRANSCODE (video codec is fine, only audio needs re-encode).
    PlaybackDecisionEngine.PlaybackDecision d = PlaybackDecisionEngine.evaluateForSurface(
        aacOnlySurface(), "MP4", "H264", "DTS", 1920, 1080, 0, 0,
        /*sourceInterlaced=*/false, /*audioPassthroughSupported=*/false);
    assertEquals(d.decision, PlaybackDecisionEngine.Decision.AUDIO_TRANSCODE,
        "Surface lacking DTS with no passthrough must AUDIO_TRANSCODE (existing behavior)");
  }

  @Test
  public void item3_surfacePassthroughGateOff_audioTranscodes() throws Throwable
  {
    sage.TestUtils.initializeSageTVForTesting();
    sage.Sage.put("playback/honor_audio_passthrough", "false");
    try
    {
      // Even with passthrough "supported", the gate OFF must ignore it.
      PlaybackDecisionEngine.PlaybackDecision d = PlaybackDecisionEngine.evaluateForSurface(
          aacOnlySurface(), "MP4", "H264", "DTS", 1920, 1080, 0, 0,
          /*sourceInterlaced=*/false, /*audioPassthroughSupported=*/true);
      assertEquals(d.decision, PlaybackDecisionEngine.Decision.AUDIO_TRANSCODE,
          "With honor_audio_passthrough=false the surface path must ignore passthrough");
    }
    finally
    {
      sage.Sage.put("playback/honor_audio_passthrough", "true");
    }
  }

  @Test
  public void item3_surfaceBaseOverloadDefaultsToNoPassthrough() throws Throwable
  {
    sage.TestUtils.initializeSageTVForTesting();
    sage.Sage.put("playback/honor_audio_passthrough", "true");
    // The pre-Item-3 9-arg overload must behave as passthrough=false.
    PlaybackDecisionEngine.PlaybackDecision d = PlaybackDecisionEngine.evaluateForSurface(
        aacOnlySurface(), "MP4", "H264", "DTS", 1920, 1080, 0, 0,
        /*sourceInterlaced=*/false);
    assertEquals(d.decision, PlaybackDecisionEngine.Decision.AUDIO_TRANSCODE,
        "Base 9-arg overload must preserve pre-Item-3 behavior (no passthrough)");
  }

  // =======================================================================
  // GPU-enhance pull upgrade: enhanceCopyFamilyXcodeMode must resolve the
  // surface-correct copy-family REMUX mode so a DIRECT_PLAY source can be
  // rerouted into an enhanceable transcode (mirror of xcodeModeForDecision's
  // REMUX row). Browser/MSE (fMP4) -> browserhd_remux; TV/AVPlay (TS) ->
  // mpeg2tsremux. Both are modern copy-family modes the enhancement pass
  // recognises, so the rewrite applies for either surface family.
  // =======================================================================
  private static PlaybackSurface surfaceWith(String route, String... containers)
  {
    return new PlaybackSurface("s", route, 10,
        java.util.Arrays.asList("pull-xcode"),
        java.util.Arrays.asList("H264", "HEVC"),
        java.util.Arrays.asList("AAC"),
        java.util.Arrays.asList(containers));
  }

  @Test
  public void enhanceCopyFamilyXcodeMode_fmp4Surface_browserhdRemux()
  {
    // MP4 container declared, no TS -> fMP4 family -> browserhd_remux.
    assertEquals(PlaybackDecisionEngine.enhanceCopyFamilyXcodeMode(surfaceWith("mse", "MP4")),
        "browserhd_remux",
        "A browser/fMP4 surface must enhance over the browserhd_remux copy-family mode");
  }

  @Test
  public void enhanceCopyFamilyXcodeMode_tsSurface_mpeg2tsremux()
  {
    // MPEG2-TS container declared, no MP4 -> TS family -> mpeg2tsremux.
    assertEquals(PlaybackDecisionEngine.enhanceCopyFamilyXcodeMode(surfaceWith("avplay", "MPEG2-TS")),
        "mpeg2tsremux",
        "A TV/AVPlay TS surface must enhance over the mpeg2tsremux copy-family mode");
  }

  @Test
  public void enhanceCopyFamilyXcodeMode_routeDecidesWhenContainersAmbiguous()
  {
    // Both containers declared -> route heuristic decides. native/avplay -> TS.
    assertEquals(PlaybackDecisionEngine.enhanceCopyFamilyXcodeMode(
            surfaceWith("native", "MP4", "MPEG2-TS")),
        "mpeg2tsremux",
        "A native/AVPlay route must resolve to the TS copy-family mode when containers are ambiguous");
    // mse route -> fMP4.
    assertEquals(PlaybackDecisionEngine.enhanceCopyFamilyXcodeMode(
            surfaceWith("mse", "MP4", "MPEG2-TS")),
        "browserhd_remux",
        "An MSE route must resolve to the fMP4 copy-family mode when containers are ambiguous");
  }

  @Test
  public void enhanceCopyFamilyXcodeMode_defaultsToFmp4WhenUnknown()
  {
    // No container, unknown route -> default to fMP4 (browser is the primary user).
    assertEquals(PlaybackDecisionEngine.enhanceCopyFamilyXcodeMode(surfaceWith("unknown")),
        "browserhd_remux",
        "An ambiguous surface must default to the fMP4 copy-family mode");
  }

  // =======================================================================
  // Interlaced-capability gate (capability-driven, Option A): a video-copy
  // decision to a surface whose client-declared row says interlaced=false is
  // escalated to a full TRANSCODE so the server deinterlaces. Only an explicit
  // interlaced=false fires it; UNKNOWN/absent and null constraints are no-ops.
  // =======================================================================
  private static PlaybackDecisionEngine.PlaybackDecision copyDecision(
      PlaybackDecisionEngine.Decision d)
  {
    // Video-copy tier: target video codec is the (copied) source codec.
    return new PlaybackDecisionEngine.PlaybackDecision(d, "test", "MP4", "H264", "AAC");
  }

  @Test
  public void interlacedGate_directPlay_interlacedFalse_escalatesToTranscode()
  {
    ClientConstraints c = ClientConstraints.parse("pwa_mse", "H264;interlaced=false", null, null);
    PlaybackDecisionEngine.PlaybackDecision out =
        PlaybackDecisionEngine.escalateForInterlacedIfDeclaredUndecodable(
            copyDecision(PlaybackDecisionEngine.Decision.DIRECT_PLAY),
            surfaceWith("mse", "MP4"), c, "H264", /*sourceInterlaced=*/true);
    assertEquals(out.decision, PlaybackDecisionEngine.Decision.TRANSCODE,
        "Interlaced source + client H264 interlaced=false must escalate a video-copy DIRECT_PLAY to full TRANSCODE");
  }

  @Test
  public void interlacedGate_remux_interlacedFalse_escalatesToTranscode()
  {
    ClientConstraints c = ClientConstraints.parse("pwa_mse", "H264;interlaced=false", null, null);
    PlaybackDecisionEngine.PlaybackDecision out =
        PlaybackDecisionEngine.escalateForInterlacedIfDeclaredUndecodable(
            copyDecision(PlaybackDecisionEngine.Decision.REMUX),
            surfaceWith("mse", "MP4"), c, "H264", true);
    assertEquals(out.decision, PlaybackDecisionEngine.Decision.TRANSCODE,
        "A REMUX (video-copy) decision must also escalate when the client declares interlaced=false");
  }

  @Test
  public void interlacedGate_unknownDeclaration_leavesDecisionUnchanged()
  {
    // Row present but interlaced attribute absent -> Tri.UNKNOWN -> permissive (Option A).
    ClientConstraints c = ClientConstraints.parse("android_media3", "H264;decoder=hw", null, null);
    PlaybackDecisionEngine.PlaybackDecision in = copyDecision(PlaybackDecisionEngine.Decision.DIRECT_PLAY);
    PlaybackDecisionEngine.PlaybackDecision out =
        PlaybackDecisionEngine.escalateForInterlacedIfDeclaredUndecodable(
            in, surfaceWith("native", "MP4"), c, "H264", true);
    assertEquals(out.decision, PlaybackDecisionEngine.Decision.DIRECT_PLAY,
        "An UNKNOWN/absent interlaced declaration must not change the decision (permissive default)");
  }

  @Test
  public void interlacedGate_nullConstraints_isNoOp()
  {
    PlaybackDecisionEngine.PlaybackDecision in = copyDecision(PlaybackDecisionEngine.Decision.DIRECT_PLAY);
    PlaybackDecisionEngine.PlaybackDecision out =
        PlaybackDecisionEngine.escalateForInterlacedIfDeclaredUndecodable(
            in, surfaceWith("mse", "MP4"), null, "H264", true);
    assertEquals(out.decision, PlaybackDecisionEngine.Decision.DIRECT_PLAY,
        "Null constraints (legacy client) must be a complete no-op on the surface interlaced gate");
  }

  @Test
  public void interlacedGate_progressiveSource_isNoOp()
  {
    ClientConstraints c = ClientConstraints.parse("pwa_mse", "H264;interlaced=false", null, null);
    PlaybackDecisionEngine.PlaybackDecision in = copyDecision(PlaybackDecisionEngine.Decision.DIRECT_PLAY);
    PlaybackDecisionEngine.PlaybackDecision out =
        PlaybackDecisionEngine.escalateForInterlacedIfDeclaredUndecodable(
            in, surfaceWith("mse", "MP4"), c, "H264", /*sourceInterlaced=*/false);
    assertEquals(out.decision, PlaybackDecisionEngine.Decision.DIRECT_PLAY,
        "A progressive source must never be escalated regardless of the interlaced declaration");
  }

  @Test
  public void interlacedGate_alreadyTranscode_isUnchanged()
  {
    ClientConstraints c = ClientConstraints.parse("pwa_mse", "H264;interlaced=false", null, null);
    PlaybackDecisionEngine.PlaybackDecision in = copyDecision(PlaybackDecisionEngine.Decision.TRANSCODE);
    PlaybackDecisionEngine.PlaybackDecision out =
        PlaybackDecisionEngine.escalateForInterlacedIfDeclaredUndecodable(
            in, surfaceWith("mse", "MP4"), c, "H264", true);
    assertSame(out, in,
        "A decision that is already a full TRANSCODE must be returned unchanged (no double work)");
  }

  // A surface that declares interlaced=false on its own VIDEO_CODECS channel
  // (H264;scan=progressive;interlaced=false) with H264 + AAC + MP4 decodable.
  private static PlaybackSurface surfaceProgressiveOnly()
  {
    java.util.Set<String> progOnly = new java.util.HashSet<String>();
    progOnly.add("H264");
    return new PlaybackSurface("pwa_mse", "mse", 10,
        java.util.Arrays.asList("pull-xcode"),
        java.util.Arrays.asList("H264"),
        java.util.Arrays.asList("AAC"),
        java.util.Arrays.asList("MP4"),
        null, null, null, 0, 0, 0, progOnly);
  }

  @Test
  public void interlacedGate_surfaceDeclaresProgressive_nullConstraints_escalates()
  {
    // The real browser case: a PWA/MSE client declares interlaced=false on the
    // per-surface VIDEO_CODECS channel and has NO client-level ClientConstraints
    // (those are Android EXO/IJK only). The gate must still fire off the surface.
    PlaybackDecisionEngine.PlaybackDecision out =
        PlaybackDecisionEngine.escalateForInterlacedIfDeclaredUndecodable(
            copyDecision(PlaybackDecisionEngine.Decision.DIRECT_PLAY),
            surfaceProgressiveOnly(), /*constraints=*/null, "H264", /*sourceInterlaced=*/true);
    assertEquals(out.decision, PlaybackDecisionEngine.Decision.TRANSCODE,
        "A surface-declared interlaced=false must escalate a video-copy decision even with null client constraints");
  }

  @Test
  public void interlacedGate_surfaceDeclaresProgressive_remux_escalates()
  {
    PlaybackDecisionEngine.PlaybackDecision out =
        PlaybackDecisionEngine.escalateForInterlacedIfDeclaredUndecodable(
            copyDecision(PlaybackDecisionEngine.Decision.REMUX),
            surfaceProgressiveOnly(), null, "H264", true);
    assertEquals(out.decision, PlaybackDecisionEngine.Decision.TRANSCODE,
        "A REMUX to a progressive-only surface must escalate to full TRANSCODE");
  }

  @Test
  public void interlacedGate_surfaceProgressive_differentCodec_isNoOp()
  {
    // Surface declares only H264 as progressive-only; an interlaced HEVC source
    // is a different codec -> no declaration -> no escalation.
    PlaybackDecisionEngine.PlaybackDecision in = copyDecision(PlaybackDecisionEngine.Decision.DIRECT_PLAY);
    PlaybackDecisionEngine.PlaybackDecision out =
        PlaybackDecisionEngine.escalateForInterlacedIfDeclaredUndecodable(
            in, surfaceProgressiveOnly(), null, "HEVC", true);
    assertEquals(out.decision, PlaybackDecisionEngine.Decision.DIRECT_PLAY,
        "The interlaced gate is per-codec: a codec the surface did not flag must be left unchanged");
  }

  // -----------------------------------------------------------------------
  // Per-container transport dimension (Protocol 2.1): a surface can declare a
  // push-only container (MPEG2-PS;push=true;pull=false) so the server does not
  // route a program stream over the pull transport the client cannot
  // direct-play (a raw stv:// .mpg pull OPEN returns NON_MEDIA).
  // -----------------------------------------------------------------------
  private static PlaybackSurfaceSet buildSurfaceSet(final String id, final String delivery,
      final String video, final String audio, final String containers)
  {
    return PlaybackSurfaceSet.build(id, new java.util.function.Function<String, String[]>()
    {
      public String[] apply(String sid)
      {
        // [ROUTE, PRIORITY, DELIVERY_MODES, VIDEO_CODECS, AUDIO_CODECS, CONTAINERS]
        return new String[] { "native", "100", delivery, video, audio, containers };
      }
    });
  }

  private static String chosenDeliveryFor(PlaybackSurfaceSet set, String container,
      String video, String audio)
  {
    java.util.List<PlaybackDecisionEngine.SurfaceDecision> ranked =
        PlaybackDecisionEngine.evaluateSurfaces(set, container, video, audio,
            1920, 1080, 0, 0, /*sourceInterlaced=*/false, null, null, null);
    assertFalse(ranked.isEmpty(), "expected at least one servable surface");
    return ranked.get(0).chosenDeliveryMode;
  }

  @Test
  public void pushOnlyContainer_directPlay_routesOverPushNotPull()
  {
    // android_ijk-style surface: offers BOTH push and pull, but declares
    // MPEG2-PS as push-only. A PS source must DIRECT_PLAY over push.
    PlaybackSurfaceSet set = buildSurfaceSet("android_ijk", "push,pull",
        "MPEG2-VIDEO,H264,HEVC", "AC3,AAC",
        "MPEG2-PS;push=true;pull=false,MP4,MATROSKA");
    assertEquals(chosenDeliveryFor(set, "MPEG2-PS", "MPEG2-VIDEO", "AC3"), "push",
        "A push-only MPEG2-PS container must be delivered over push, not the cheaper pull");
  }

  @Test
  public void unrestrictedContainer_directPlay_keepsPullFirst()
  {
    // Control: a bare MPEG2-PS (no transport attribute) is fail-open, so the
    // legacy cheapest-first pull>push order is preserved.
    PlaybackSurfaceSet set = buildSurfaceSet("android_ijk", "push,pull",
        "MPEG2-VIDEO,H264,HEVC", "AC3,AAC",
        "MPEG2-PS,MP4,MATROSKA");
    assertEquals(chosenDeliveryFor(set, "MPEG2-PS", "MPEG2-VIDEO", "AC3"), "pull",
        "An unrestricted container must keep the legacy cheapest-first (pull) routing");
  }

  @Test
  public void pushOnlyContainer_otherContainersUnaffected()
  {
    // The PS transport restriction must not leak onto MP4 on the same surface.
    PlaybackSurfaceSet set = buildSurfaceSet("android_ijk", "push,pull",
        "MPEG2-VIDEO,H264,HEVC", "AC3,AAC",
        "MPEG2-PS;push=true;pull=false,MP4,MATROSKA");
    assertEquals(chosenDeliveryFor(set, "MP4", "H264", "AAC"), "pull",
        "A container without a transport attribute must stay pull-first even when a sibling container is push-only");
  }

  @Test
  public void pushOnlyContainer_pushOnlyQueriesTrueOnSurface()
  {
    PlaybackSurfaceSet set = buildSurfaceSet("android_ijk", "push,pull",
        "MPEG2-VIDEO,H264,HEVC", "AC3,AAC",
        "MPEG2-PS;push=true;pull=false,MP4");
    PlaybackSurface s = set.get("android_ijk");
    assertTrue(s.isContainerPushOnly("MPEG2-PS"), "MPEG2-PS declared push-only");
    assertFalse(s.containerAllowsTransport("MPEG2-PS", /*push=*/false), "PS pull must be disallowed");
    assertTrue(s.containerAllowsTransport("MPEG2-PS", /*push=*/true), "PS push must be allowed");
    assertTrue(s.containerAllowsTransport("MP4", /*push=*/false), "MP4 (unrestricted) is fail-open on pull");
    assertFalse(s.isContainerPushOnly("MP4"), "MP4 is not push-only");
  }

  @Test
  public void mpeg1PsAlias_foldsOntoMpeg2PsWithTransportFlags()
  {
    // MPEG1-PS is the same PS container as MPEG2-PS; the client may spell it
    // either way. The transport flags must land on the canonical MPEG2-PS entry.
    PlaybackSurfaceSet set = buildSurfaceSet("android_ijk", "push,pull",
        "MPEG1-VIDEO,MPEG2-VIDEO,H264", "AC3,AAC",
        "MPEG1-PS;push=true;pull=false,MP4");
    PlaybackSurface s = set.get("android_ijk");
    assertTrue(s.supportsContainer("MPEG2-PS"),
        "MPEG1-PS must canonicalize to MPEG2-PS in the container set");
    assertTrue(s.isContainerPushOnly("MPEG2-PS"),
        "Transport flags on MPEG1-PS must apply to the canonical MPEG2-PS entry");
    assertEquals(chosenDeliveryFor(set, "MPEG2-PS", "MPEG1-VIDEO", "AC3"), "push",
        "A PS source (advertised as MPEG1-PS) must still route over push");
  }

  // -----------------------------------------------------------------------
  // Native HLS routing (Safari/WebKit): a surface that advertises native "hls"
  // must be delivered native HLS for a non-direct (conditioned) decision, not
  // pulled into the MSE pull-xcode bridge. Only Apple WebKit/Safari advertises
  // "hls" (gated client-side); Blink/Gecko/Tizen never do, so their routing is
  // unchanged.
  // -----------------------------------------------------------------------
  private static PlaybackSurfaceSet buildTwoSurfaceSet(
      final String idA, final String deliveryA,
      final String idB, final String deliveryB,
      final int priority, final String video, final String audio, final String containers)
  {
    return PlaybackSurfaceSet.build(idA + "," + idB,
        new java.util.function.Function<String, String[]>()
    {
      public String[] apply(String sid)
      {
        String delivery = sid.equals(idA) ? deliveryA : deliveryB;
        // [ROUTE, PRIORITY, DELIVERY_MODES, VIDEO_CODECS, AUDIO_CODECS, CONTAINERS]
        return new String[] { "native", Integer.toString(priority), delivery, video, audio, containers };
      }
    });
  }

  @Test
  public void hlsSurface_nonDirect_prefersNativeHlsOverPullXcode()
  {
    // Safari surface advertises pull,pull-xcode,hls. An HEVC source (surface
    // decodes only H264) forces a full TRANSCODE -> non-direct. The server must
    // deliver native "hls", NOT the cheaper-CPU "pull-xcode" MSE bridge.
    PlaybackSurfaceSet set = buildSurfaceSet("pwa_safari", "pull,pull-xcode,hls",
        "H264", "AAC", "MP4");
    assertEquals(chosenDeliveryFor(set, "MP4", "HEVC", "AAC"), "hls",
        "A surface declaring native hls must be delivered native HLS for a non-direct decision");
  }

  @Test
  public void hlsSurface_directPlay_staysNativePull()
  {
    // Direct-playable content on the same Safari surface must still use the raw
    // native "pull" -- advertising hls must NOT force segmented HLS onto a
    // stream the client can direct-play.
    PlaybackSurfaceSet set = buildSurfaceSet("pwa_safari", "pull,pull-xcode,hls",
        "H264", "AAC", "MP4");
    assertEquals(chosenDeliveryFor(set, "MP4", "H264", "AAC"), "pull",
        "Direct-playable content on an hls-capable surface must stay on native pull");
  }

  @Test
  public void nonHlsSurface_nonDirect_keepsPullXcode()
  {
    // Chromium/Firefox surface: pull,pull-xcode (no hls). A non-direct decision
    // must keep the unchanged pull-xcode MSE bridge routing.
    PlaybackSurfaceSet set = buildSurfaceSet("pwa_mse", "pull,pull-xcode",
        "H264", "AAC", "MP4");
    assertEquals(chosenDeliveryFor(set, "MP4", "HEVC", "AAC"), "pull-xcode",
        "A surface without hls must keep pull-xcode routing for a non-direct decision");
  }

  @Test
  public void hlsSurface_outranksPullXcodeSurface_crossSurface()
  {
    // Belt-and-suspenders: if Safari co-advertises a native hls surface AND an
    // MSE pull-xcode surface at equal priority, the native-HLS surface must win
    // the cross-surface tie (not be undercut by the cheaper-CPU pull-xcode).
    PlaybackSurfaceSet set = buildTwoSurfaceSet(
        "pwa_safari", "pull,hls",
        "pwa_mse",    "pull,pull-xcode",
        /*priority=*/10, "H264", "AAC", "MP4");
    java.util.List<PlaybackDecisionEngine.SurfaceDecision> ranked =
        PlaybackDecisionEngine.evaluateSurfaces(set, "MP4", "HEVC", "AAC",
            1920, 1080, 0, 0, /*sourceInterlaced=*/false, null, null, null);
    assertFalse(ranked.isEmpty(), "expected at least one servable surface");
    assertEquals(ranked.get(0).chosenDeliveryMode, "hls",
        "Native HLS must win the cross-surface tie over an equal-priority pull-xcode surface");
    assertEquals(ranked.get(0).surface.getId(), "pwa_safari",
        "The native-HLS Safari surface must be the selected winner");
  }
}
