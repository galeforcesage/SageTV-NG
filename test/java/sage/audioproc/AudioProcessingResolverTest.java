package sage.audioproc;

import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class AudioProcessingResolverTest
{
  private static final AudioFilterCapabilities FULL_CAPS = AudioFilterCapabilities.builder()
      .probeSucceeded(true).equalizerAvailable(true).volumeAvailable(true).alimiterAvailable(true)
      .acompressorAvailable(true).loudnormAvailable(true).build();

  private static AudioProcessingClientState serverRequestState(boolean serverEqPlanSupported, boolean clientDspActive)
  {
    AudioProcessingSettings settings = AudioProcessingSettings.builder()
        .location(AudioProcessingLocation.SERVER)
        .eqEnabled(true)
        .addBand(new EqualizerBand(1000, 4.0))
        .build();
    AudioProcessingCapabilities caps = AudioProcessingCapabilities.builder()
        .serverEqPlanSupported(serverEqPlanSupported)
        .build();
    return new AudioProcessingClientState("client1", caps, settings, clientDspActive, System.currentTimeMillis());
  }

  @Test
  public void testNullClientStateResolvesToNone()
  {
    AudioProcessingPlan plan = AudioProcessingResolver.resolve(null, FULL_CAPS, "ac3", "aac", 48000, "stereo");
    assertEquals(plan.getResolvedLocation(), AudioProcessingLocation.NONE);
  }

  @Test
  public void testClientLocationNotServerResolvesToNone()
  {
    AudioProcessingSettings settings = AudioProcessingSettings.builder()
        .location(AudioProcessingLocation.CLIENT).eqEnabled(true).build();
    AudioProcessingClientState state = new AudioProcessingClientState("client1", AudioProcessingCapabilities.builder()
        .serverEqPlanSupported(true).build(), settings, false, System.currentTimeMillis());
    AudioProcessingPlan plan = AudioProcessingResolver.resolve(state, FULL_CAPS, "ac3", "aac", 48000, "stereo");
    assertEquals(plan.getResolvedLocation(), AudioProcessingLocation.NONE);
  }

  @Test
  public void testLegacyClientNeverTriggersServerLocation()
  {
    // Legacy/STV clients never send AUDIO_PROCESSING_* messages at all, so
    // their state is simply absent -- must resolve to NONE, never SERVER.
    AudioProcessingPlan plan = AudioProcessingResolver.resolve(null, FULL_CAPS, "ac3", "aac", 48000, "stereo");
    assertEquals(plan.getResolvedLocation(), AudioProcessingLocation.NONE);
  }

  @Test
  public void testEqNotEnabledResolvesToNoneEvenWithServerLocation()
  {
    AudioProcessingSettings settings = AudioProcessingSettings.builder()
        .location(AudioProcessingLocation.SERVER).eqEnabled(false).build();
    AudioProcessingClientState state = new AudioProcessingClientState("client1", AudioProcessingCapabilities.builder()
        .serverEqPlanSupported(true).build(), settings, false, System.currentTimeMillis());
    AudioProcessingPlan plan = AudioProcessingResolver.resolve(state, FULL_CAPS, "ac3", "aac", 48000, "stereo");
    assertEquals(plan.getResolvedLocation(), AudioProcessingLocation.NONE);
  }

  @Test
  public void testClientCapabilitiesDoNotSupportServerPlanResolvesToNone()
  {
    AudioProcessingClientState state = serverRequestState(false, false);
    AudioProcessingPlan plan = AudioProcessingResolver.resolve(state, FULL_CAPS, "ac3", "aac", 48000, "stereo");
    assertEquals(plan.getResolvedLocation(), AudioProcessingLocation.NONE);
  }

  @Test
  public void testDoubleProcessingPreventionWhenClientDspAlreadyActive()
  {
    AudioProcessingClientState state = serverRequestState(true, true);
    AudioProcessingPlan plan = AudioProcessingResolver.resolve(state, FULL_CAPS, "ac3", "aac", 48000, "stereo");
    assertEquals(plan.getResolvedLocation(), AudioProcessingLocation.NONE);
    assertTrue(plan.getReason().toLowerCase().contains("double"));
    assertFalse(plan.isClientMustDisableDsp());
  }

  @Test
  public void testUnbuildableFiltergraphResolvesToNone()
  {
    AudioProcessingClientState state = serverRequestState(true, false);
    AudioFilterCapabilities noEq = AudioFilterCapabilities.builder().probeSucceeded(true).build(); // no filters available
    AudioProcessingPlan plan = AudioProcessingResolver.resolve(state, noEq, "ac3", "aac", 48000, "stereo");
    assertEquals(plan.getResolvedLocation(), AudioProcessingLocation.NONE);
    assertTrue(plan.getReason().contains("filtergraph"));
  }

  @Test
  public void testHappyPathResolvesToServerWithClientMustDisableDsp()
  {
    AudioProcessingClientState state = serverRequestState(true, false);
    AudioProcessingPlan plan = AudioProcessingResolver.resolve(state, FULL_CAPS, "ac3", "aac", 48000, "stereo");
    assertEquals(plan.getResolvedLocation(), AudioProcessingLocation.SERVER);
    assertTrue(plan.isClientMustDisableDsp());
    assertNotNull(plan.getFilterGraph());
    assertNotNull(plan.getFilterGraphHash());
    // targetAudioCodec/sourceAudioCodec must be pure pass-through, never invented.
    assertEquals(plan.getSourceAudioCodec(), "ac3");
    assertEquals(plan.getTargetAudioCodec(), "aac");
    assertEquals(plan.getSampleRate(), 48000);
    assertEquals(plan.getChannelLayout(), "stereo");
  }

  @Test
  public void testLegacyClientKindAlwaysResolvesToNoneEvenIfCapabilitiesClaimOtherwise()
  {
    // LEGACY clientKind on AudioProcessingCapabilities already forces serverEqPlanSupported=false
    // and supportedLocations={NONE}, but the resolver must also independently refuse LEGACY.
    AudioProcessingSettings settings = AudioProcessingSettings.builder()
        .location(AudioProcessingLocation.SERVER).eqEnabled(true).addBand(new EqualizerBand(1000, 4.0)).build();
    AudioProcessingCapabilities legacyCaps = AudioProcessingCapabilities.builder()
        .clientKind(ClientKind.LEGACY).serverEqPlanSupported(true).build();
    AudioProcessingClientState state = new AudioProcessingClientState("client1", legacyCaps, settings, false, 0L);
    AudioProcessingPlan plan = AudioProcessingResolver.resolve(state, FULL_CAPS, "ac3", "aac", 48000, "stereo");
    assertEquals(plan.getResolvedLocation(), AudioProcessingLocation.NONE);
    assertTrue(plan.getReason().toLowerCase().contains("legacy"));
  }

  @Test
  public void testSettingsHashPresentEvenWhenResolvingToNone()
  {
    AudioProcessingSettings settings = AudioProcessingSettings.builder()
        .location(AudioProcessingLocation.NONE).build();
    AudioProcessingClientState state = new AudioProcessingClientState("client1", AudioProcessingCapabilities.NONE, settings, false, 0L);
    AudioProcessingPlan plan = AudioProcessingResolver.resolve(state, FULL_CAPS, null, null, 0, null);
    assertEquals(plan.getResolvedLocation(), AudioProcessingLocation.NONE);
    assertNotNull(plan.getSettingsHash());
  }
}
