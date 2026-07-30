package sage.audioproc;

import org.testng.annotations.Test;

import java.util.Arrays;

import static org.testng.Assert.*;

public class AudioFilterGraphBuilderTest
{
  private static final AudioFilterCapabilities FULL_CAPS = AudioFilterCapabilities.builder()
      .ffmpegPath("/opt/sagetv/server/ffmpeg")
      .probeSucceeded(true)
      .equalizerAvailable(true)
      .anequalizerAvailable(true)
      .volumeAvailable(true)
      .aresampleAvailable(true)
      .acompressorAvailable(true)
      .loudnormAvailable(true)
      .dynaudnormAvailable(true)
      .alimiterAvailable(true)
      .build();

  @Test
  public void testNoopSettingsProduceNoGraph()
  {
    AudioFilterGraphBuilder.Result result = AudioFilterGraphBuilder.build(AudioProcessingSettings.DISABLED, FULL_CAPS);
    assertFalse(result.isBuildable());
    assertNull(result.getFilterGraph());
  }

  @Test
  public void testNullCapsWhenSettingsNonNoopIsNotBuildable()
  {
    AudioProcessingSettings settings = AudioProcessingSettings.builder().preampDb(3.0).build();
    AudioFilterGraphBuilder.Result result = AudioFilterGraphBuilder.build(settings, null);
    assertFalse(result.isBuildable());
  }

  @Test
  public void testPreampOnlyBuildsVolumeAndLimiter()
  {
    AudioProcessingSettings settings = AudioProcessingSettings.builder().preampDb(3.0).build();
    AudioFilterGraphBuilder.Result result = AudioFilterGraphBuilder.build(settings, FULL_CAPS);
    assertTrue(result.isBuildable());
    assertEquals(result.getFilterGraph(), "volume=3.00dB,alimiter");
  }

  @Test
  public void testEqBandsBuildEqualizerChainInFrequencyOrder()
  {
    AudioProcessingSettings settings = AudioProcessingSettings.builder()
        .eqEnabled(true)
        .addBand(new EqualizerBand(4000, 3.0))
        .addBand(new EqualizerBand(125, -2.0))
        .build();
    AudioFilterGraphBuilder.Result result = AudioFilterGraphBuilder.build(settings, FULL_CAPS);
    assertTrue(result.isBuildable());
    String graph = result.getFilterGraph();
    assertTrue(graph.indexOf("f=125.00") < graph.indexOf("f=4000.00"), "bands must appear in ascending frequency order: " + graph);
    assertTrue(graph.endsWith("alimiter"));
  }

  @Test
  public void testFullChainOrderIsPreampThenEqThenNightModeThenLimiter()
  {
    AudioProcessingSettings settings = AudioProcessingSettings.builder()
        .preampDb(2.0)
        .eqEnabled(true)
        .addBand(new EqualizerBand(1000, 4.0))
        .nightMode(new NightModeSettings(NightModeMode.DYNAMIC_RANGE_COMPRESSION, NightModeIntensity.MEDIUM))
        .build();
    AudioFilterGraphBuilder.Result result = AudioFilterGraphBuilder.build(settings, FULL_CAPS);
    assertTrue(result.isBuildable());
    String graph = result.getFilterGraph();
    int volumeIdx = graph.indexOf("volume=");
    int eqIdx = graph.indexOf("equalizer=");
    int compressorIdx = graph.indexOf("acompressor=");
    int limiterIdx = graph.indexOf("alimiter");
    assertTrue(volumeIdx < eqIdx);
    assertTrue(eqIdx < compressorIdx);
    assertTrue(compressorIdx < limiterIdx);
  }

  @Test
  public void testPlatformNightModeIsAdvisoryOnlyAndBuildsNoFilter()
  {
    AudioProcessingSettings settings = AudioProcessingSettings.builder()
        .nightMode(new NightModeSettings(NightModeMode.PLATFORM_NIGHT_MODE, NightModeIntensity.HIGH))
        .build();
    AudioFilterGraphBuilder.Result result = AudioFilterGraphBuilder.build(settings, FULL_CAPS);
    assertFalse(result.isBuildable());
    assertTrue(result.isNightModeAdvisoryOnly());
    assertNull(result.getFilterGraph());
  }

  @Test
  public void testPlatformNightModeAdvisoryDoesNotBlockOtherStages()
  {
    AudioProcessingSettings settings = AudioProcessingSettings.builder()
        .preampDb(1.5)
        .nightMode(new NightModeSettings(NightModeMode.PLATFORM_NIGHT_MODE, NightModeIntensity.HIGH))
        .build();
    AudioFilterGraphBuilder.Result result = AudioFilterGraphBuilder.build(settings, FULL_CAPS);
    assertTrue(result.isBuildable());
    assertTrue(result.isNightModeAdvisoryOnly());
    assertFalse(result.getFilterGraph().contains("compressor"));
    assertFalse(result.getFilterGraph().contains("loudnorm"));
  }

  @Test
  public void testEqRejectedWhenEqualizerFilterUnavailable()
  {
    AudioFilterCapabilities noEq = AudioFilterCapabilities.builder()
        .probeSucceeded(true).volumeAvailable(true).alimiterAvailable(true).equalizerAvailable(false).build();
    AudioProcessingSettings settings = AudioProcessingSettings.builder()
        .eqEnabled(true).addBand(new EqualizerBand(1000, 3.0)).build();
    AudioFilterGraphBuilder.Result result = AudioFilterGraphBuilder.build(settings, noEq);
    assertFalse(result.isBuildable());
    assertTrue(result.getReason().contains("equalizer"));
  }

  @Test
  public void testMissingAlimiterFailsEvenWhenOtherFiltersAvailable()
  {
    AudioFilterCapabilities noLimiter = AudioFilterCapabilities.builder()
        .probeSucceeded(true).volumeAvailable(true).equalizerAvailable(true).alimiterAvailable(false).build();
    AudioProcessingSettings settings = AudioProcessingSettings.builder().preampDb(2.0).build();
    AudioFilterGraphBuilder.Result result = AudioFilterGraphBuilder.build(settings, noLimiter);
    assertFalse(result.isBuildable());
    assertTrue(result.getReason().contains("alimiter"));
  }

  @Test
  public void testDeterministicHashForEquivalentSettings()
  {
    AudioProcessingSettings a = AudioProcessingSettings.builder()
        .eqEnabled(true).addBand(new EqualizerBand(1000, 3.0)).build();
    AudioProcessingSettings b = AudioProcessingSettings.builder()
        .eqEnabled(true).addBand(new EqualizerBand(1000, 3.0)).build();
    AudioFilterGraphBuilder.Result r1 = AudioFilterGraphBuilder.build(a, FULL_CAPS);
    AudioFilterGraphBuilder.Result r2 = AudioFilterGraphBuilder.build(b, FULL_CAPS);
    assertEquals(r1.getFilterGraph(), r2.getFilterGraph());
    assertEquals(r1.getFilterGraphHash(), r2.getFilterGraphHash());
  }

  @Test
  public void testLoudnessLevelingIntensityAffectsGraph()
  {
    AudioProcessingSettings low = AudioProcessingSettings.builder()
        .nightMode(new NightModeSettings(NightModeMode.LOUDNESS_LEVELING, NightModeIntensity.LOW)).build();
    AudioProcessingSettings high = AudioProcessingSettings.builder()
        .nightMode(new NightModeSettings(NightModeMode.LOUDNESS_LEVELING, NightModeIntensity.HIGH)).build();
    AudioFilterGraphBuilder.Result lowResult = AudioFilterGraphBuilder.build(low, FULL_CAPS);
    AudioFilterGraphBuilder.Result highResult = AudioFilterGraphBuilder.build(high, FULL_CAPS);
    assertNotEquals(lowResult.getFilterGraph(), highResult.getFilterGraph());
  }

  @Test
  public void testNightModeRejectedWhenFilterUnavailable()
  {
    AudioFilterCapabilities noCompressor = AudioFilterCapabilities.builder()
        .probeSucceeded(true).acompressorAvailable(false).build();
    AudioProcessingSettings settings = AudioProcessingSettings.builder()
        .nightMode(new NightModeSettings(NightModeMode.DYNAMIC_RANGE_COMPRESSION, NightModeIntensity.LOW))
        .build();
    AudioFilterGraphBuilder.Result result = AudioFilterGraphBuilder.build(settings, noCompressor);
    assertFalse(result.isBuildable());
  }
}
