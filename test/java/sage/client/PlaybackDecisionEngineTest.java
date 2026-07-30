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
}
