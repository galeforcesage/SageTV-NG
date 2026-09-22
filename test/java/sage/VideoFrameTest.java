package sage;

import org.testng.annotations.Test;

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
}
