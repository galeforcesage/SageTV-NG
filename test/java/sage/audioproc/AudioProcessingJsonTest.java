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
    assertTrue(json.contains("filterGraph"));
    assertTrue(json.contains("clientMustDisableDsp"));
    assertTrue(json.contains("48000"));
  }
}
