package sage.audioproc;

import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class AudioProcessingJsonTest
{
  @Test
  public void testParseSettingsFullPayload()
  {
    String json = "{"
        + "\"location\":\"SERVER\","
        + "\"eqEnabled\":true,"
        + "\"preampDb\":2.5,"
        + "\"bands\":[{\"frequencyHz\":1000,\"gainDb\":3.0},{\"frequencyHz\":125,\"gainDb\":-2.0}],"
        + "\"nightMode\":{\"mode\":\"LOUDNESS_LEVELING\",\"intensity\":\"HIGH\"},"
        + "\"clientSettingsVersion\":7"
        + "}";
    AudioProcessingSettings settings = AudioProcessingJson.parseSettings(json);
    assertEquals(settings.getLocation(), AudioProcessingLocation.SERVER);
    assertTrue(settings.isEqEnabled());
    assertEquals(settings.getPreampDb(), 2.5);
    assertEquals(settings.getBands().size(), 2);
    assertEquals(settings.getNightMode().getMode(), NightModeMode.LOUDNESS_LEVELING);
    assertEquals(settings.getNightMode().getIntensity(), NightModeIntensity.HIGH);
    assertEquals(settings.getClientSettingsVersion(), 7L);
  }

  @Test
  public void testParseSettingsIgnoresUnknownFields()
  {
    // Simulates a client-local night-schedule field the server has no model for.
    String json = "{\"location\":\"SERVER\",\"eqEnabled\":true,\"nightScheduleStart\":\"22:00\",\"someFutureField\":{\"nested\":true}}";
    AudioProcessingSettings settings = AudioProcessingJson.parseSettings(json);
    assertEquals(settings.getLocation(), AudioProcessingLocation.SERVER);
    assertTrue(settings.isEqEnabled());
  }

  @Test
  public void testParseSettingsMalformedJsonFailsSafeToDisabled()
  {
    AudioProcessingSettings settings = AudioProcessingJson.parseSettings("{not valid json");
    assertEquals(settings, AudioProcessingSettings.DISABLED);
  }

  @Test
  public void testParseSettingsNullOrEmptyFailsSafeToDisabled()
  {
    assertEquals(AudioProcessingJson.parseSettings(null), AudioProcessingSettings.DISABLED);
    assertEquals(AudioProcessingJson.parseSettings(""), AudioProcessingSettings.DISABLED);
    assertEquals(AudioProcessingJson.parseSettings("[]"), AudioProcessingSettings.DISABLED);
  }

  @Test
  public void testParseSettingsUnknownEnumValueFailsSafe()
  {
    String json = "{\"location\":\"BOGUS_LOCATION\",\"nightMode\":{\"mode\":\"BOGUS_MODE\",\"intensity\":\"BOGUS_LEVEL\"}}";
    AudioProcessingSettings settings = AudioProcessingJson.parseSettings(json);
    assertEquals(settings.getLocation(), AudioProcessingLocation.NONE);
    assertEquals(settings.getNightMode().getMode(), NightModeMode.OFF);
    assertEquals(settings.getNightMode().getIntensity(), NightModeIntensity.LOW);
  }

  @Test
  public void testParseCapabilitiesFullPayload()
  {
    String json = "{\"clientSideEqSupported\":true,\"serverEqPlanSupported\":true,\"platformNightModeAvailable\":false,\"maxEqBands\":10}";
    AudioProcessingCapabilities caps = AudioProcessingJson.parseCapabilities(json);
    assertTrue(caps.isClientSideEqSupported());
    assertTrue(caps.isServerEqPlanSupported());
    assertFalse(caps.isPlatformNightModeAvailable());
    assertEquals(caps.getMaxEqBands(), 10);
  }

  @Test
  public void testParseCapabilitiesMalformedFailsSafeToNone()
  {
    AudioProcessingCapabilities caps = AudioProcessingJson.parseCapabilities("not json at all");
    assertSame(caps, AudioProcessingCapabilities.NONE);
  }

  @Test
  public void testParseDspActiveVariants()
  {
    assertTrue(AudioProcessingJson.parseDspActive("true"));
    assertTrue(AudioProcessingJson.parseDspActive("TRUE"));
    assertTrue(AudioProcessingJson.parseDspActive("1"));
    assertFalse(AudioProcessingJson.parseDspActive("false"));
    assertFalse(AudioProcessingJson.parseDspActive("0"));
    assertFalse(AudioProcessingJson.parseDspActive(null));
    assertFalse(AudioProcessingJson.parseDspActive("garbage"));
  }

  @Test
  public void testToJsonRoundTripsResolvedFields()
  {
    AudioProcessingPlan plan = AudioProcessingPlan.builder()
        .resolvedLocation(AudioProcessingLocation.SERVER)
        .reason("client requested server EQ")
        .filterGraph("equalizer=f=1000:t=q:w=1.0:g=3.0,alimiter")
        .settingsHash("abc123")
        .clientMustDisableDsp(true)
        .sourceAudioCodec("ac3")
        .targetAudioCodec("aac")
        .sampleRate(48000)
        .channelLayout("stereo")
        .build();
    String json = AudioProcessingJson.toJson(plan);
    assertTrue(json.contains("\"location\":\"SERVER\""));
    assertTrue(json.contains("ffmpegFilterGraph"));
    assertTrue(json.contains("clientMustDisableDsp"));
    assertTrue(json.contains("48000"));
  }

  @Test
  public void testParseSettingsAcceptsCanonicalFieldNames()
  {
    String json = "{"
        + "\"schemaVersion\":1,"
        + "\"location\":\"SERVER\","
        + "\"enabled\":true,"
        + "\"preampDb\":2.5,"
        + "\"presetId\":\"movie-night\","
        + "\"settingsVersion\":9,"
        + "\"updatedAtEpochMs\":1700000000000,"
        + "\"bands\":[{\"id\":\"1000\",\"frequencyHz\":1000,\"gainDb\":3.0,\"q\":0.7,\"enabled\":true}],"
        + "\"nightMode\":{\"enabled\":true,\"effectiveNow\":true,\"mode\":\"LOUDNESS_LEVELING\",\"intensity\":\"HIGH\",\"controllability\":\"APP_SCOPED\"}"
        + "}";
    AudioProcessingSettings settings = AudioProcessingJson.parseSettings(json);
    assertEquals(settings.getLocation(), AudioProcessingLocation.SERVER);
    assertTrue(settings.isEqEnabled());
    assertEquals(settings.getPreampDb(), 2.5);
    assertEquals(settings.getPresetId(), "movie-night");
    assertEquals(settings.getSettingsVersion(), 9L);
    assertEquals(settings.getUpdatedAtEpochMs(), 1700000000000L);
    assertEquals(settings.getBands().size(), 1);
    assertEquals(settings.getBands().get(0).getQ(), 0.7);
    assertTrue(settings.getNightMode().isEnabled());
    assertTrue(settings.getNightMode().isEffectiveNow());
    assertEquals(settings.getNightMode().getControllability(), NightModeControllability.APP_SCOPED);
  }

  @Test
  public void testParseSettingsAcceptsLegacyPwaClientProcessingFalseAsServer()
  {
    // Live PWA client currently sends clientProcessing (bool) instead of an explicit location.
    String json = "{\"clientProcessing\":false,\"enabled\":true,\"presetName\":\"flat\"}";
    AudioProcessingSettings settings = AudioProcessingJson.parseSettings(json);
    assertEquals(settings.getLocation(), AudioProcessingLocation.SERVER);
    assertEquals(settings.getPresetId(), "flat");
  }

  @Test
  public void testParseSettingsAcceptsLegacyPwaClientProcessingTrueAsClient()
  {
    String json = "{\"clientProcessing\":true,\"enabled\":true}";
    AudioProcessingSettings settings = AudioProcessingJson.parseSettings(json);
    assertEquals(settings.getLocation(), AudioProcessingLocation.CLIENT);
  }

  @Test
  public void testParseSettingsExplicitLocationTakesPrecedenceOverClientProcessing()
  {
    String json = "{\"location\":\"SERVER\",\"clientProcessing\":true}";
    AudioProcessingSettings settings = AudioProcessingJson.parseSettings(json);
    assertEquals(settings.getLocation(), AudioProcessingLocation.SERVER);
  }

  @Test
  public void testParseSettingsIgnoresClientLocalNightScheduleFields()
  {
    String json = "{\"location\":\"SERVER\",\"enabled\":true,"
        + "\"nightMode\":{\"mode\":\"LOUDNESS_LEVELING\",\"intensity\":\"LOW\"},"
        + "\"nightStartTime\":\"22:00\",\"nightEndTime\":\"06:00\"}";
    AudioProcessingSettings settings = AudioProcessingJson.parseSettings(json);
    assertEquals(settings.getNightMode().getMode(), NightModeMode.LOUDNESS_LEVELING);
  }

  @Test
  public void testParseCapabilitiesAcceptsCanonicalFieldNames()
  {
    String json = "{"
        + "\"clientKind\":\"PWA_BROWSER\","
        + "\"supportsEqualizerUi\":true,"
        + "\"supportsClientDsp\":true,"
        + "\"supportsDspActiveReporting\":true,"
        + "\"supportsSettingsVersionSync\":true,"
        + "\"supportedLocations\":[\"CLIENT\",\"SERVER\"],"
        + "\"supportedBandCount\":10,"
        + "\"gainRangeDb\":{\"min\":-12,\"max\":12},"
        + "\"supportsBiquad\":true,"
        + "\"supportsNightMode\":true,"
        + "\"localPersistence\":\"localStorage\""
        + "}";
    AudioProcessingCapabilities caps = AudioProcessingJson.parseCapabilities(json);
    assertEquals(caps.getClientKind(), ClientKind.PWA_BROWSER);
    assertTrue(caps.isSupportsEqualizerUi());
    assertTrue(caps.isSupportsClientDsp());
    assertTrue(caps.isServerEqPlanSupported()); // derived from supportedLocations containing SERVER
    assertEquals(caps.getSupportedBandCount(), 10);
    assertEquals(caps.getGainRangeDb().min, -12.0);
    assertEquals(caps.getGainRangeDb().max, 12.0);
    assertTrue(caps.isSupportsBiquad());
    assertTrue(caps.isSupportsNightMode());
    assertEquals(caps.getLocalPersistence(), LocalPersistence.localStorage);
  }

  @Test
  public void testParseCapabilitiesLegacyClientKindForcesNoneLocation()
  {
    String json = "{\"clientKind\":\"LEGACY\",\"supportedLocations\":[\"CLIENT\",\"SERVER\"],\"supportsClientDsp\":true}";
    AudioProcessingCapabilities caps = AudioProcessingJson.parseCapabilities(json);
    assertEquals(caps.getClientKind(), ClientKind.LEGACY);
    assertFalse(caps.isSupportsClientDsp());
    assertFalse(caps.isServerEqPlanSupported());
    assertEquals(caps.getSupportedLocations().size(), 1);
    assertTrue(caps.getSupportedLocations().contains(AudioProcessingLocation.NONE));
  }

  @Test
  public void testParseStateFullPayload()
  {
    String json = "{\"playbackSessionId\":\"sess-1\",\"dspActive\":true,\"activeLocation\":\"CLIENT\","
        + "\"appliedSettingsVersion\":3,\"appliedSettingsHash\":\"h1\",\"engineName\":\"WebAudioBiquad\","
        + "\"planId\":\"plan-1\",\"errorCode\":null}";
    AudioProcessingState state = AudioProcessingJson.parseState(json);
    assertEquals(state.getPlaybackSessionId(), "sess-1");
    assertTrue(state.isDspActive());
    assertEquals(state.getActiveLocation(), AudioProcessingLocation.CLIENT);
    assertEquals(state.getAppliedSettingsVersion(), Long.valueOf(3L));
    assertEquals(state.getAppliedSettingsHash(), "h1");
    assertEquals(state.getEngineName(), AudioProcessingEngineName.WebAudioBiquad);
    assertEquals(state.getPlanId(), "plan-1");
    assertNull(state.getErrorCode());
  }

  @Test
  public void testParseStateMalformedFailsSafe()
  {
    AudioProcessingState state = AudioProcessingJson.parseState("not json");
    assertFalse(state.isDspActive());
    assertEquals(state.getActiveLocation(), AudioProcessingLocation.NONE);
  }

  @Test
  public void testToJsonEmitsCanonicalFieldNamesAndDiagnostics()
  {
    AudioProcessingPlan plan = AudioProcessingPlan.builder()
        .planId("plan-1")
        .playbackSessionId("sess-1")
        .resolvedLocation(AudioProcessingLocation.SERVER)
        .filterGraph("volume=2.0dB,alimiter")
        .putDiagnostic("sourceAudioCodec", "ac3")
        .build();
    String json = AudioProcessingJson.toJson(plan);
    assertTrue(json.contains("\"planId\":\"plan-1\""));
    assertTrue(json.contains("\"playbackSessionId\":\"sess-1\""));
    assertTrue(json.contains("\"serverWillApplyDsp\":true"));
    assertTrue(json.contains("\"schemaVersion\":1"));
    assertTrue(json.contains("diagnostics"));
  }
}
