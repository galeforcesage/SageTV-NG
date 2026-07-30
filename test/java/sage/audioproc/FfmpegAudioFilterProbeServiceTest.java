package sage.audioproc;

import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.testng.Assert.*;

public class FfmpegAudioFilterProbeServiceTest
{
  // A representative excerpt of real `ffmpeg -filters` output, including the
  // header lines that must be ignored and both audio and non-audio-of-interest
  // filters to make sure only the filters we care about are recorded.
  private static final List<String> SAMPLE_OUTPUT = Arrays.asList(
      "Filters:",
      "  T.. = Timeline support",
      "  .S. = Slice threading",
      "  ..C = Command support",
      "  A = Audio input/output",
      "  V = Video input/output",
      "  N = Dynamic number and/or type of input/output",
      "  | = Source or sink filter",
      " ... aconvert            A->A       Convert the input audio to sample_fmt:channel_layout:sample_rate.",
      " T.C acompressor         A->A       Audio compressor.",
      " ..C aecho               A->A       Add echoing to the audio.",
      " T.. aresample           A->A       Resample audio data.",
      " T.C equalizer           A->A       Apply two-pole peaking equalization (EQ) filter.",
      " ..C alimiter            A->A       Audio lookahead limiter.",
      " T.C volume              A->A       Change input volume.",
      " T.C loudnorm            A->A       EBU R128 loudness normalization",
      " ..C dynaudnorm          A->A       Dynamic Audio Normalizer.",
      " ..C anequalizer         A->A       Apply high-order audio parametric multi band equalizer.",
      " ... scale               V->V       Scale the input video size and/or content.");

  @Test
  public void testParseFilterNamesFindsAllExpectedFilters()
  {
    Set<String> found = FfmpegAudioFilterProbeService.parseFilterNames(SAMPLE_OUTPUT);
    assertTrue(found.contains("equalizer"));
    assertTrue(found.contains("anequalizer"));
    assertTrue(found.contains("volume"));
    assertTrue(found.contains("aresample"));
    assertTrue(found.contains("acompressor"));
    assertTrue(found.contains("loudnorm"));
    assertTrue(found.contains("dynaudnorm"));
    assertTrue(found.contains("alimiter"));
  }

  @Test
  public void testParseFilterNamesIgnoresUninterestingFilters()
  {
    Set<String> found = FfmpegAudioFilterProbeService.parseFilterNames(SAMPLE_OUTPUT);
    assertFalse(found.contains("aconvert"));
    assertFalse(found.contains("aecho"));
    assertFalse(found.contains("scale"));
  }

  @Test
  public void testParseFilterNamesHandlesEmptyOrNullInput()
  {
    assertTrue(FfmpegAudioFilterProbeService.parseFilterNames(null).isEmpty());
    assertTrue(FfmpegAudioFilterProbeService.parseFilterNames(Arrays.<String>asList()).isEmpty());
  }

  @Test
  public void testBuildFromFilterLinesFullSupport()
  {
    AudioFilterCapabilities caps = FfmpegAudioFilterProbeService.buildFromFilterLines("/opt/sagetv/server/ffmpeg", SAMPLE_OUTPUT);
    assertTrue(caps.isProbeSucceeded());
    assertTrue(caps.supportsEqChain());
    assertTrue(caps.supportsNightMode(NightModeMode.DYNAMIC_RANGE_COMPRESSION));
    assertTrue(caps.supportsNightMode(NightModeMode.LOUDNESS_LEVELING));
    assertFalse(caps.supportsNightMode(NightModeMode.PLATFORM_NIGHT_MODE));
    assertFalse(caps.supportsNightMode(NightModeMode.OFF));
  }

  @Test
  public void testBuildFromFilterLinesMissingAlimiterFailsEqChain()
  {
    List<String> withoutLimiter = Arrays.asList(
        " T.C equalizer           A->A       Apply EQ.",
        " T.C volume              A->A       Change input volume.");
    AudioFilterCapabilities caps = FfmpegAudioFilterProbeService.buildFromFilterLines("/opt/sagetv/server/ffmpeg", withoutLimiter);
    assertTrue(caps.isEqualizerAvailable());
    assertTrue(caps.isVolumeAvailable());
    assertFalse(caps.isAlimiterAvailable());
    assertFalse(caps.supportsEqChain());
  }

  @Test
  public void testUnavailableForMissingBinary()
  {
    FfmpegAudioFilterProbeService.clearCache();
    AudioFilterCapabilities caps = FfmpegAudioFilterProbeService.probe("/definitely/does/not/exist/ffmpeg-binary-xyz");
    assertFalse(caps.isProbeSucceeded());
    assertFalse(caps.supportsEqChain());
    assertFalse(caps.supportsNightMode(NightModeMode.DYNAMIC_RANGE_COMPRESSION));
  }

  @Test
  public void testProbeCachesResultForSamePathAndMtime()
  {
    FfmpegAudioFilterProbeService.clearCache();
    AudioFilterCapabilities first = FfmpegAudioFilterProbeService.probe("/definitely/does/not/exist/ffmpeg-binary-xyz");
    AudioFilterCapabilities second = FfmpegAudioFilterProbeService.probe("/definitely/does/not/exist/ffmpeg-binary-xyz");
    assertSame(first, second);
  }

  @Test
  public void testNullOrBlankPathIsUnavailable()
  {
    assertFalse(FfmpegAudioFilterProbeService.probe(null).isProbeSucceeded());
    assertFalse(FfmpegAudioFilterProbeService.probe("").isProbeSucceeded());
    assertFalse(FfmpegAudioFilterProbeService.probe("   ").isProbeSucceeded());
  }
}
