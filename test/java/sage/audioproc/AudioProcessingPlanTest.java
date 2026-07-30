package sage.audioproc;

import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class AudioProcessingPlanTest
{
  @Test
  public void testNoneFactory()
  {
    AudioProcessingPlan plan = AudioProcessingPlan.none("feature flag off", "hash1");
    assertEquals(plan.getResolvedLocation(), AudioProcessingLocation.NONE);
    assertEquals(plan.getReason(), "feature flag off");
    assertEquals(plan.getSettingsHash(), "hash1");
    assertNull(plan.getFilterGraph());
    assertNull(plan.getFilterGraphHash());
    assertFalse(plan.isClientMustDisableDsp());
  }

  @Test
  public void testFilterGraphHashComputedWhenGraphPresent()
  {
    AudioProcessingPlan plan = AudioProcessingPlan.builder()
        .resolvedLocation(AudioProcessingLocation.SERVER)
        .filterGraph("volume=2.0dB")
        .build();
    assertNotNull(plan.getFilterGraphHash());
    assertEquals(plan.getFilterGraphHash().length(), 16);
  }

  @Test
  public void testFilterGraphHashDeterministic()
  {
    AudioProcessingPlan a = AudioProcessingPlan.builder().filterGraph("volume=2.0dB").build();
    AudioProcessingPlan b = AudioProcessingPlan.builder().filterGraph("volume=2.0dB").build();
    assertEquals(a.getFilterGraphHash(), b.getFilterGraphHash());
  }

  @Test
  public void testNullFilterGraphYieldsNullHash()
  {
    AudioProcessingPlan plan = AudioProcessingPlan.builder().resolvedLocation(AudioProcessingLocation.NONE).build();
    assertNull(plan.getFilterGraph());
    assertNull(plan.getFilterGraphHash());
  }

  @Test
  public void testTargetAudioCodecEchoesExistingSelectionOnly()
  {
    // The plan must never invent its own target codec choice; it can only
    // carry through whatever value the caller supplies (which must come
    // from the existing audio-selection logic).
    AudioProcessingPlan plan = AudioProcessingPlan.builder().targetAudioCodec("eac3").build();
    assertEquals(plan.getTargetAudioCodec(), "eac3");
  }

  @Test
  public void testServerWillApplyDspIsDerivedFromResolvedLocation()
  {
    AudioProcessingPlan server = AudioProcessingPlan.builder().resolvedLocation(AudioProcessingLocation.SERVER).build();
    AudioProcessingPlan none = AudioProcessingPlan.builder().resolvedLocation(AudioProcessingLocation.NONE).build();
    AudioProcessingPlan client = AudioProcessingPlan.builder().resolvedLocation(AudioProcessingLocation.CLIENT).build();
    assertTrue(server.isServerWillApplyDsp());
    assertFalse(none.isServerWillApplyDsp());
    assertFalse(client.isServerWillApplyDsp());
  }

  @Test
  public void testPlanIdAndPlaybackSessionIdAndDiagnosticsRoundTrip()
  {
    AudioProcessingPlan plan = AudioProcessingPlan.builder()
        .planId("plan-123")
        .playbackSessionId("session-456")
        .settingsVersionAccepted(7L)
        .putDiagnostic("filterGraphHash", "abc123")
        .putDiagnostic("doubleProcessingPrevented", false)
        .build();
    assertEquals(plan.getPlanId(), "plan-123");
    assertEquals(plan.getPlaybackSessionId(), "session-456");
    assertEquals(plan.getSettingsVersionAccepted(), Long.valueOf(7L));
    assertEquals(plan.getDiagnostics().get("filterGraphHash"), "abc123");
    assertEquals(plan.getDiagnostics().get("doubleProcessingPrevented"), Boolean.FALSE);
  }

  @Test
  public void testDiagnosticsDefaultsToEmptyNotNull()
  {
    AudioProcessingPlan plan = AudioProcessingPlan.none("no state", null);
    assertNotNull(plan.getDiagnostics());
    assertTrue(plan.getDiagnostics().isEmpty());
  }

  @Test
  public void testSettingsHashAcceptedIsAliasOfSettingsHash()
  {
    AudioProcessingPlan plan = AudioProcessingPlan.builder().settingsHash("hash1").build();
    assertEquals(plan.getSettingsHashAccepted(), plan.getSettingsHash());
    assertEquals(plan.getSettingsHashAccepted(), "hash1");
  }
}
