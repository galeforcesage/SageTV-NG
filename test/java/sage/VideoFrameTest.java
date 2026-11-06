package sage;

import org.testng.annotations.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;

/**
 * VideoFrame itself is a large, UIManager/MediaPlayer-lifecycle-coupled
 * singleton-style class with no existing test harness, so this intentionally
 * covers only the pure, extracted decision logic for the live-playback
 * caption/subtitle delay-compensation fix
 * ({@link VideoFrame#computeLiveCaptionDelayCompensationMs}) rather than
 * exercising VideoFrame end-to-end.
 */
public class VideoFrameTest
{
  @Test
  public void testExplicitStartFromBeginningIgnoresSavedResume()
  {
    MediaFile mediaFile = mock(MediaFile.class);
    Airing airing = mock(Airing.class);
    when(mediaFile.getContentAiring()).thenReturn(airing);
    when(airing.getStartTime()).thenReturn(1200000L);

    assertEquals(VideoFrame.selectInitialWatchTarget(mediaFile, false, true), 1200000L,
        "Explicit restart must use the airing start even if teardown writes a resume concurrently");
    assertEquals(VideoFrame.selectInitialWatchTarget(mediaFile, true, false), 1200000L,
        "Playlist start-from-beginning keeps the same target selection");
  }

  @Test
  public void testPartyPreparationUsesCanonicalTargetInsteadOfSavedResume()
  {
    MediaFile mediaFile = mock(MediaFile.class);
    when(mediaFile.getRecordTime()).thenReturn(100000L);
    when(mediaFile.getRecordEnd()).thenReturn(900000L);
    VideoFrame.PartyPreparation preparation =
        new VideoFrame.PartyPreparation(mediaFile, "prepare", () -> true, 12345L);

    assertEquals(VideoFrame.selectInitialWatchTarget(mediaFile, false, false, preparation), 112345L,
        "Party preparation must stage at the canonical relative position");
  }

  @Test
  public void testNgLoadFailureDoesNotAutoTuneLiveTv()
  {
    assertEquals(VideoFrame.suppressDefaultWatchAfterFailure(true, true), true,
        "A failed recording load must not trigger a tuner after a timed holdoff expires");
    assertEquals(VideoFrame.suppressDefaultWatchAfterFailure(true, false), false,
        "An explicit new watch or successful load clears the failure state");
    assertEquals(VideoFrame.suppressDefaultWatchAfterFailure(false, true), false,
        "Legacy default preview behavior remains unchanged");
    assertEquals(VideoFrame.suppressDefaultWatchAfterFailure(false, false), false);
  }

  @Test
  public void testPartyBitrateFallsBackToRecordedBytesForGrowingMedia()
  {
    MediaFile mediaFile = mock(MediaFile.class);
    sage.media.format.ContainerFormat format = mock(sage.media.format.ContainerFormat.class);
    when(mediaFile.getFileFormat()).thenReturn(format);
    when(format.getBitrate()).thenReturn(0);
    when(mediaFile.getNumSegments()).thenReturn(2);
    when(mediaFile.getDuration(0)).thenReturn(1200000L);
    when(mediaFile.getDuration(1)).thenReturn(1800000L);
    when(mediaFile.getSize()).thenReturn(3000000000L);

    assertEquals(VideoFrame.partySourceBitrateKbps(mediaFile), 8000,
        "Growing recordings with incomplete format metadata must still be Party-ready");
  }

  @Test
  public void testPartyBitratePrefersParsedFormatMetadata()
  {
    MediaFile mediaFile = mock(MediaFile.class);
    sage.media.format.ContainerFormat format = mock(sage.media.format.ContainerFormat.class);
    when(mediaFile.getFileFormat()).thenReturn(format);
    when(format.getBitrate()).thenReturn(17500000);

    assertEquals(VideoFrame.partySourceBitrateKbps(mediaFile), 17500,
        "Parsed format bitrate remains authoritative when available");
  }

  @Test
  public void testRepeatedSkipsUsePendingTargetWhileSettling()
  {
    VideoFrame.PendingSeekTarget pending = new VideoFrame.PendingSeekTarget();
    long reported = 600000L;
    long now = 1000L;

    long ff1 = reported + 30000L;
    pending.record(ff1, now, 0L, 1920000L);
    long ff2 = pending.selectBase(reported, now + 100L, 8000L, 2500L, 0L, 1920000L) + 30000L;
    pending.record(ff2, now + 100L, 0L, 1920000L);
    long rew1 = pending.selectBase(reported, now + 200L, 8000L, 2500L, 0L, 1920000L) - 30000L;
    pending.record(rew1, now + 200L, 0L, 1920000L);
    long rew2 = pending.selectBase(reported, now + 300L, 8000L, 2500L, 0L, 1920000L) - 30000L;

    assertEquals(ff1, 630000L, "FF starts from the rendered position");
    assertEquals(ff2, 660000L, "FF #2 starts from FF #1's intended target");
    assertEquals(rew1, 630000L, "direction change starts from FF #2's intended target");
    assertEquals(rew2, 600000L, "REW #2 starts from REW #1's intended target");

    pending.record(750000L, now + 400L, 0L, 1920000L);
    pending.clear();
    assertEquals(pending.selectBase(610000L, now + 500L, 8000L, 2500L, 0L, 1920000L),
        610000L, "a rate-mode change clears the old relative-seek target");
  }

  @Test
  public void testPendingSkipRemembersClampedLanding()
  {
    assertEquals(VideoFrame.clampPendingSkipTarget(-12637L, 0L, 1920625L), 0L);
    assertEquals(VideoFrame.clampPendingSkipTarget(2000000L, 0L, 1920625L), 1920625L);
    assertEquals(VideoFrame.clampPendingSkipTarget(24762L, 0L, 1920625L), 24762L);
    assertEquals(VideoFrame.clampPendingSkipTarget(99000L, 100000L, 200000L), 100000L);
  }
  @Test
  public void testLiveCaptionDelayCompensationOnlyAppliesToLiveCapPlayerWhenEnabled() throws Throwable
  {
    // Disabled via property (kill-switch): no compensation regardless of player capability.
    assertEquals(VideoFrame.computeLiveCaptionDelayCompensationMs(false, true, 1500), 0L);

    // Non-live-playback (mp.getMediaTimeMillis() already reflects real client-fed position,
    // so no wall-clock bias exists to compensate for): no compensation.
    assertEquals(VideoFrame.computeLiveCaptionDelayCompensationMs(true, false, 1500), 0L);

    // Live-playback (LIVE_CAP): the full encode-to-playback delay is applied so captions
    // stop running ahead of the actual displayed video position.
    assertEquals(VideoFrame.computeLiveCaptionDelayCompensationMs(true, true, 1500), 1500L);

    // Never returns a negative compensation even if the delay itself were negative
    // (shouldn't happen in practice, but the lookup time must never be pushed forward).
    assertEquals(VideoFrame.computeLiveCaptionDelayCompensationMs(true, true, -200), 0L);

    // Zero delay (e.g. local encoder playback with no configured lag) is a legitimate no-op.
    assertEquals(VideoFrame.computeLiveCaptionDelayCompensationMs(true, true, 0), 0L);
  }

  @Test
  public void testLiveSeamPermittedOnlyBridgesLiveTunesWhenEnabled() throws Throwable
  {
    // The EPG-boundary seam must bridge ONLY for a live/unscheduled tune
    // (liveControl==true) and ONLY when the per-path feature/capability is on.

    // Live tune + feature on -> bridge past the show boundary into the next airing.
    assertEquals(VideoFrame.liveSeamPermitted(true, true), true);

    // Scheduled-recording playback (liveControl==false): never bridge, even with
    // the feature enabled. A 60-min recording watched from the start ends at its
    // own 60-min end, not carried into the next airing.
    assertEquals(VideoFrame.liveSeamPermitted(true, false), false);

    // Feature/capability off (kill-switch, or a pull client that did not send
    // x-cmaf-seam): today's behavior even for a live tune -> no bridge.
    assertEquals(VideoFrame.liveSeamPermitted(false, true), false);

    // Neither condition holds: no bridge.
    assertEquals(VideoFrame.liveSeamPermitted(false, false), false);
  }

  @Test
  public void testAndroidTranscodeEosWaitsForBufferedPlaybackClock()
  {
    assertEquals(VideoFrame.shouldDeferPrematurePushEos(true, true, 38721L, 3000L), true,
        "Producer EOF must not dismiss the player while Android still has buffered media");
    assertEquals(VideoFrame.shouldDeferPrematurePushEos(true, true, 1500L, 3000L), false,
        "Near-end timestamp differences retain normal EOS handling");
    assertEquals(VideoFrame.shouldDeferPrematurePushEos(true, false, 38721L, 3000L), false,
        "Legacy and non-transcoded playback retain historical EOS behavior");
    assertEquals(VideoFrame.shouldDeferPrematurePushEos(false, true, 38721L, 3000L), false);
  }

  @Test
  public void testFinalCommercialSkipIsLatchedNearRecordingEnd()
  {
    assertEquals(VideoFrame.isTerminalCommercialSkip(3537330L, 3540000L, 5000L), true,
        "A final skip landing in the last partial CMAF part must not repeat forever");
    assertEquals(VideoFrame.isTerminalCommercialSkip(3500000L, 3540000L, 5000L), false);
    assertEquals(VideoFrame.isTerminalCommercialSkip(3537330L, 0L, 5000L), false);
  }
}
