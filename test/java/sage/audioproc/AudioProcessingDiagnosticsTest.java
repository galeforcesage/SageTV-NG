package sage.audioproc;

import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;

import static org.testng.Assert.*;

public class AudioProcessingDiagnosticsTest
{
  private static AudioProcessingClientState clientState(ClientKind kind, AudioProcessingSettings settings, boolean clientDspActive)
  {
    AudioProcessingCapabilities caps = AudioProcessingCapabilities.builder()
        .clientKind(kind)
        .supportsEqualizerUi(true)
        .supportsDspActiveReporting(true)
        .supportsSettingsVersionSync(true)
        .supportedLocations(java.util.Collections.singletonList(AudioProcessingLocation.SERVER))
        .build();
    return new AudioProcessingClientState("client-1", caps, settings, clientDspActive, 12345L);
  }

  @Test
  public void testNonePlanEventReportsErrorCodeAsReason()
  {
    AudioProcessingPlan plan = AudioProcessingPlan.none("audioproc/enable_server_eq feature flag is disabled", "hashXYZ");
    Map<String, Object> event = AudioProcessingDiagnostics.buildEvent(null, null, plan);
    assertEquals(event.get("location"), "NONE");
    assertEquals(event.get("errorCode"), "audioproc/enable_server_eq feature flag is disabled");
    assertEquals(event.get("serverDspActive"), Boolean.FALSE);
    assertEquals(event.get("settingsHash"), "hashXYZ");
  }

  @Test
  public void testServerPlanEventReportsNullErrorCodeAndServerDspActive()
  {
    AudioProcessingSettings settings = AudioProcessingSettings.builder()
        .location(AudioProcessingLocation.SERVER)
        .eqEnabled(true)
        .settingsVersion(7L)
        .build();
    AudioProcessingClientState state = clientState(ClientKind.PWA_BROWSER, settings, false);
    AudioProcessingPlan plan = AudioProcessingPlan.builder()
        .resolvedLocation(AudioProcessingLocation.SERVER)
        .filterGraph("volume=2.0dB")
        .targetAudioCodec("eac3")
        .sourceAudioCodec("ac3")
        .clientMustDisableDsp(true)
        .build();
    Map<String, Object> event = AudioProcessingDiagnostics.buildEvent(state, null, plan);
    assertEquals(event.get("location"), "SERVER");
    assertNull(event.get("errorCode"));
    assertEquals(event.get("serverDspActive"), Boolean.TRUE);
    assertEquals(event.get("clientKind"), "PWA_BROWSER");
    assertEquals(event.get("settingsVersion"), 7L);
    assertEquals(event.get("targetAudioCodec"), "eac3");
    assertEquals(event.get("sourceAudioCodec"), "ac3");
    assertNotNull(event.get("filterGraphHash"));
  }

  @Test
  public void testDoubleProcessingPreventedFlaggedWhenClientDspActiveForcesNone()
  {
    AudioProcessingSettings settings = AudioProcessingSettings.builder()
        .location(AudioProcessingLocation.SERVER)
        .eqEnabled(true)
        .build();
    AudioProcessingClientState state = clientState(ClientKind.PWA_BROWSER, settings, true);
    AudioProcessingPlan plan = AudioProcessingPlan.none(
        "client reports its own DSP already active; refusing to double-process an ambiguous/stale request", null);
    Map<String, Object> event = AudioProcessingDiagnostics.buildEvent(state, null, plan);
    assertEquals(event.get("doubleProcessingPrevented"), Boolean.TRUE);
    assertEquals(event.get("clientDspActive"), Boolean.TRUE);
  }

  @Test
  public void testPlatformNightModeNeverReportedActiveEvenWhenServerDspActive()
  {
    NightModeSettings platformNightMode = new NightModeSettings(NightModeMode.PLATFORM_NIGHT_MODE,
        NightModeIntensity.HIGH, true, true, NightModeControllability.OS_SCOPED);
    AudioProcessingSettings settings = AudioProcessingSettings.builder()
        .location(AudioProcessingLocation.SERVER)
        .eqEnabled(true)
        .nightMode(platformNightMode)
        .build();
    AudioProcessingClientState state = clientState(ClientKind.ANDROID_MINICLIENT, settings, false);
    AudioProcessingPlan plan = AudioProcessingPlan.builder()
        .resolvedLocation(AudioProcessingLocation.SERVER)
        .filterGraph("volume=1.0dB")
        .build();
    Map<String, Object> event = AudioProcessingDiagnostics.buildEvent(state, null, plan);
    assertEquals(event.get("nightModeActive"), Boolean.FALSE);
    assertEquals(event.get("nightModeEngine"), "PlatformNightMode");
    assertEquals(event.get("nightModeMode"), "PLATFORM_NIGHT_MODE");
    assertEquals(event.get("nightModeIntensitySemantics"), "SUPPRESSION_STRENGTH");
    assertEquals(event.get("nightModeScheduleClientLocal"), Boolean.TRUE);
  }

  @Test
  public void testServerExecutableNightModeReportsFFmpegEngineWhenServerDspActive()
  {
    NightModeSettings drc = new NightModeSettings(NightModeMode.DYNAMIC_RANGE_COMPRESSION,
        NightModeIntensity.MEDIUM, true, true, NightModeControllability.APP_SCOPED);
    AudioProcessingSettings settings = AudioProcessingSettings.builder()
        .location(AudioProcessingLocation.SERVER)
        .eqEnabled(true)
        .nightMode(drc)
        .build();
    AudioProcessingClientState state = clientState(ClientKind.PWA_TIZEN, settings, false);
    AudioProcessingPlan plan = AudioProcessingPlan.builder()
        .resolvedLocation(AudioProcessingLocation.SERVER)
        .filterGraph("acompressor=threshold=0.1")
        .build();
    Map<String, Object> event = AudioProcessingDiagnostics.buildEvent(state, null, plan);
    assertEquals(event.get("nightModeActive"), Boolean.TRUE);
    assertEquals(event.get("nightModeEngine"), "FFmpegNightMode");
  }

  @Test
  public void testFfmpegFiltersAvailableReflectsProbeResult()
  {
    AudioFilterCapabilities fullSupport = AudioFilterCapabilities.builder()
        .probeSucceeded(true).equalizerAvailable(true).volumeAvailable(true).alimiterAvailable(true).build();
    AudioProcessingPlan plan = AudioProcessingPlan.none("flag off", null);
    Map<String, Object> event = AudioProcessingDiagnostics.buildEvent(null, fullSupport, plan);
    assertEquals(event.get("ffmpegFiltersAvailable"), Boolean.TRUE);

    AudioFilterCapabilities noSupport = AudioFilterCapabilities.unavailable("/no/ffmpeg");
    event = AudioProcessingDiagnostics.buildEvent(null, noSupport, plan);
    assertEquals(event.get("ffmpegFiltersAvailable"), Boolean.FALSE);
  }

  @Test
  public void testNullPlanDegradesToNoneRatherThanThrowing()
  {
    Map<String, Object> event = AudioProcessingDiagnostics.buildEvent(null, null, null);
    assertEquals(event.get("location"), "NONE");
    assertNotNull(event.get("errorCode"));
  }

  @Test
  public void testFormatForLogProducesDeterministicOrderedLine()
  {
    AudioProcessingPlan plan = AudioProcessingPlan.none("reason", "hash1");
    Map<String, Object> event = AudioProcessingDiagnostics.buildEvent(null, null, plan);
    String line = AudioProcessingDiagnostics.formatForLog(event);
    assertTrue(line.startsWith("AudioProcessing diagnostic: {playbackSessionId="));
    assertTrue(line.contains("location=NONE"));
  }
}
