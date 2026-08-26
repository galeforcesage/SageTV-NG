package sage.convert;

import org.testng.annotations.Test;

import static org.testng.Assert.*;

/**
 * Unit tests for the client-agnostic conversion plan builder (Layer A).
 * Pure logic — no ffmpeg, no GPU, no server.
 */
public class ConversionPlanBuilderTest
{
  private static ConversionEngineCaps nvenc(String enc)
  {
    return ConversionEngineCaps.builder()
        .videoEncoderName(enc).nvenc(true)
        .scalerFilter("scale_npp").scalerSupportsLanczos(true).supportsFpsMax(true)
        .build();
  }

  private static ConversionEngineCaps software(String enc)
  {
    return ConversionEngineCaps.builder()
        .videoEncoderName(enc).nvenc(false)
        .scalerFilter("scale").scalerSupportsLanczos(true).supportsFpsMax(true)
        .build();
  }

  private static SourceMedia source(int w, int h, double fps, int ch)
  {
    return SourceMedia.builder().width(w).height(h).fps(fps).audioChannels(ch)
        .videoCodec("MPEG2-VIDEO").audioCodec("AC3").containerMuxer("mpegts")
        .durationMillis(3_600_000L).build();
  }

  private static SourceMedia interlacedSource(int w, int h, double fps, int ch)
  {
    return SourceMedia.builder().width(w).height(h).fps(fps).audioChannels(ch)
        .interlaced(true)
        .videoCodec("MPEG2-VIDEO").audioCodec("AC3").containerMuxer("mpegts")
        .durationMillis(3_600_000L).build();
  }

  private static SourceMedia hdrSource(int w, int h, double fps, int ch)
  {
    return SourceMedia.builder().width(w).height(h).fps(fps).audioChannels(ch)
        .hdr(true).colorspace("bt2020nc")
        .videoCodec("HEVC").audioCodec("EAC3").containerMuxer("matroska")
        .durationMillis(3_600_000L).build();
  }

  @Test
  public void testPhoneStdReproducesPresetShape()
  {
    ConversionRequest req = ConversionRequest.builder()
        .container(ContainerChoice.MP4)
        .videoCodec(VideoCodecChoice.H264)
        .targetSize(1280, 720).scaling(ScalingChoice.LANCZOS)
        .frameRate(FrameRateChoice.CAP_30)
        .audioCodec(AudioCodecChoice.AAC).audioBitrateKbps(160).audioLayout(AudioLayoutChoice.STEREO)
        .qualityCq(23).build();
    ConversionPlan plan = ConversionPlanBuilder.build(req, source(1920, 1080, 59.94, 2), nvenc("h264_nvenc"));

    String args = plan.getVideoArgs();
    assertTrue(args.contains("-vf scale_npp=1280:720:interp_algo=lanczos"), args);
    assertTrue(args.contains("-fpsmax 30000/1001"), args);
    assertTrue(args.contains("-c:v h264_nvenc"), args);
    assertTrue(args.contains("-profile:v high"), args);
    assertTrue(args.contains("-c:a aac -b:a 160k -ac 2"), args);
    assertTrue(args.contains("-movflags +faststart"), args);
    assertEquals(plan.getGlobalArgs(), "-hwaccel cuda -hwaccel_output_format cuda");
    assertEquals(plan.getMuxer(), "mp4");
    assertFalse(plan.isVideoStreamCopy());
  }

  @Test
  public void testH264LevelBumpsTo42For1080p60()
  {
    // The core bug the VSR team hit: 1080p60 at Level 4.0 is rejected by NVENC.
    ConversionRequest req = ConversionRequest.builder()
        .videoCodec(VideoCodecChoice.H264).build();
    ConversionPlan plan = ConversionPlanBuilder.build(req, source(1920, 1080, 59.94, 2), nvenc("h264_nvenc"));
    assertTrue(plan.getVideoArgs().contains("-level 4.2"), plan.getVideoArgs());
  }

  @Test
  public void testH264Level40For1080p30()
  {
    ConversionRequest req = ConversionRequest.builder()
        .videoCodec(VideoCodecChoice.H264).build();
    ConversionPlan plan = ConversionPlanBuilder.build(req, source(1920, 1080, 29.97, 2), nvenc("h264_nvenc"));
    assertTrue(plan.getVideoArgs().contains("-level 4.0"), plan.getVideoArgs());
  }

  @Test
  public void testH264LevelDirectMapping()
  {
    assertEquals(ConversionPlanBuilder.h264Level(1920, 1080, 60), "4.2");
    assertEquals(ConversionPlanBuilder.h264Level(1920, 1080, 30), "4.0");
    assertEquals(ConversionPlanBuilder.h264Level(1280, 720, 60), "3.2");
    assertEquals(ConversionPlanBuilder.h264Level(720, 480, 30), "3.0");
    assertEquals(ConversionPlanBuilder.h264Level(3840, 2160, 30), "5.1");
  }

  @Test
  public void testHevcEmitsHvc1TagNoLevel()
  {
    ConversionRequest req = ConversionRequest.builder().videoCodec(VideoCodecChoice.HEVC).build();
    ConversionPlan plan = ConversionPlanBuilder.build(req, source(1920, 1080, 29.97, 6), nvenc("hevc_nvenc"));
    assertTrue(plan.getVideoArgs().contains("-c:v hevc_nvenc"), plan.getVideoArgs());
    assertTrue(plan.getVideoArgs().contains("-tag:v hvc1"), plan.getVideoArgs());
    assertFalse(plan.getVideoArgs().contains("-level"), plan.getVideoArgs());
  }

  @Test
  public void testExactBackupStreamCopies()
  {
    ConversionRequest req = ConversionRequest.builder()
        .purpose(ConversionPurpose.EXACT_BACKUP)
        .container(ContainerChoice.MKV)
        .videoCodec(VideoCodecChoice.COPY).audioCodec(AudioCodecChoice.COPY)
        .scaling(ScalingChoice.NONE).frameRate(FrameRateChoice.KEEP)
        .build();
    ConversionPlan plan = ConversionPlanBuilder.build(req, source(1920, 1080, 29.97, 6), nvenc("h264_nvenc"));
    assertTrue(plan.isVideoStreamCopy());
    assertTrue(plan.getVideoArgs().contains("-c:v copy"), plan.getVideoArgs());
    assertTrue(plan.getVideoArgs().contains("-c:a copy"), plan.getVideoArgs());
    assertEquals(plan.getGlobalArgs(), "");
    assertEquals(plan.getMuxer(), "matroska");
  }

  @Test
  public void testAudioCopyRetains51()
  {
    ConversionRequest req = ConversionRequest.builder()
        .videoCodec(VideoCodecChoice.HEVC).audioCodec(AudioCodecChoice.COPY).build();
    ConversionPlan plan = ConversionPlanBuilder.build(req, source(1920, 1080, 29.97, 6), nvenc("hevc_nvenc"));
    assertTrue(plan.getVideoArgs().contains("-c:a copy"), plan.getVideoArgs());
  }

  @Test
  public void testNeverUpmixToSurround()
  {
    ConversionRequest req = ConversionRequest.builder()
        .videoCodec(VideoCodecChoice.H264)
        .audioCodec(AudioCodecChoice.AC3).audioLayout(AudioLayoutChoice.SURROUND_51)
        .build();
    // Stereo (2ch) source: must NOT force -ac 6.
    ConversionPlan plan = ConversionPlanBuilder.build(req, source(1280, 720, 29.97, 2), nvenc("h264_nvenc"));
    assertFalse(plan.getVideoArgs().contains("-ac 6"), plan.getVideoArgs());
  }

  @Test
  public void testFpsMaxOmittedWhenSourceBelowCap()
  {
    ConversionRequest req = ConversionRequest.builder()
        .videoCodec(VideoCodecChoice.H264).frameRate(FrameRateChoice.CAP_30).build();
    ConversionPlan plan = ConversionPlanBuilder.build(req, source(1280, 720, 23.976, 2), nvenc("h264_nvenc"));
    // -fpsmax below source is a harmless no-op, but the op note must not claim a reduction.
    for (String op : plan.getOperations())
      assertFalse(op.startsWith("Reduce frame rate"), op);
    assertEquals(plan.getTargetFps(), 23.976, 0.01);
  }

  @Test
  public void testSizeBudgetSetsBitrateNotCq()
  {
    ConversionRequest req = ConversionRequest.builder()
        .videoCodec(VideoCodecChoice.H264)
        .maxOutputBytes(700L * 1024 * 1024) // ~700MB for a 1h source
        .audioCodec(AudioCodecChoice.AAC).audioBitrateKbps(128)
        .build();
    ConversionPlan plan = ConversionPlanBuilder.build(req, source(1280, 720, 29.97, 2), nvenc("h264_nvenc"));
    assertTrue(plan.getVideoArgs().contains("-b:v"), plan.getVideoArgs());
    assertTrue(plan.getVideoArgs().contains("-maxrate"), plan.getVideoArgs());
    assertFalse(plan.getVideoArgs().contains("-cq:v"), plan.getVideoArgs());
    // Estimate should land within a sane band of the requested budget.
    long est = plan.estimateBytes(3_600_000L);
    assertTrue(est > 500L * 1024 * 1024 && est < 800L * 1024 * 1024, "est=" + est);
  }

  @Test
  public void testSoftwareFallbackUsesCrfAndPlainScale()
  {
    ConversionRequest req = ConversionRequest.builder()
        .videoCodec(VideoCodecChoice.H264)
        .targetSize(1280, 720).scaling(ScalingChoice.LANCZOS)
        .qualityCq(22).build();
    ConversionPlan plan = ConversionPlanBuilder.build(req, source(1920, 1080, 29.97, 2), software("libx264"));
    assertTrue(plan.getVideoArgs().contains("-vf scale=1280:720:flags=lanczos"), plan.getVideoArgs());
    assertTrue(plan.getVideoArgs().contains("-c:v libx264"), plan.getVideoArgs());
    assertTrue(plan.getVideoArgs().contains("-crf 22"), plan.getVideoArgs());
    assertEquals(plan.getGlobalArgs(), "");
  }

  @Test
  public void testFormatSpecRoundTripsThroughEscaping()
  {
    ConversionRequest req = ConversionRequest.builder()
        .videoCodec(VideoCodecChoice.H264).targetSize(1280, 720).scaling(ScalingChoice.LANCZOS)
        .frameRate(FrameRateChoice.CAP_30).build();
    ConversionPlan plan = ConversionPlanBuilder.build(req, source(1920, 1080, 59.94, 2), nvenc("h264_nvenc"));
    String spec = plan.getFormatSpec();
    // Same wire form the static presets materialize into transcoder/formats/*.
    String expected = "f=mp4;"
        + "MRawCmdlineGlobal=" + SpecEscaping.escape(plan.getGlobalArgs()) + ";"
        + "MRawCmdline=" + SpecEscaping.escape(plan.getVideoArgs()) + ";";
    assertEquals(spec, expected);
    // The escaping must be reversible (ContainerFormat.buildFormatFromString uses
    // identical rules), so the decoded args equal what we put in.
    assertEquals(SpecEscaping.unescape(SpecEscaping.escape(plan.getVideoArgs())), plan.getVideoArgs());
  }

  @Test
  public void testAv1NvencEncodesWithoutLevel()
  {
    ConversionRequest req = ConversionRequest.builder().videoCodec(VideoCodecChoice.AV1).build();
    ConversionPlan plan = ConversionPlanBuilder.build(req, source(1920, 1080, 30, 2), nvenc("av1_nvenc"));
    String args = plan.getVideoArgs();
    assertTrue(args.contains("-c:v av1_nvenc"), args);
    assertFalse(args.contains("-level"), args);
    assertFalse(args.contains("-profile:v"), args);
  }

  @Test
  public void testAv1SoftwareUsesNumericPreset()
  {
    ConversionRequest req = ConversionRequest.builder().videoCodec(VideoCodecChoice.AV1).build();
    ConversionPlan plan = ConversionPlanBuilder.build(req, source(1920, 1080, 30, 2), software("libsvtav1"));
    String args = plan.getVideoArgs();
    assertTrue(args.contains("-c:v libsvtav1"), args);
    assertTrue(args.contains("-preset 8"), args);
    assertFalse(args.contains("-preset medium"), args);
  }

  @Test
  public void testDeinterlaceBeforeScaleGpu()
  {
    ConversionRequest req = ConversionRequest.builder()
        .videoCodec(VideoCodecChoice.HEVC).targetSize(3840, 2160).scaling(ScalingChoice.LANCZOS).build();
    ConversionPlan plan = ConversionPlanBuilder.build(req, interlacedSource(1920, 1080, 29.97, 6), nvenc("hevc_nvenc"));
    String args = plan.getVideoArgs();
    assertTrue(args.contains("-vf yadif_cuda,scale_npp=3840:2160:interp_algo=lanczos"), args);
  }

  @Test
  public void testDeinterlaceSoftwareUsesPlainYadif()
  {
    ConversionRequest req = ConversionRequest.builder().videoCodec(VideoCodecChoice.H264).build();
    ConversionPlan plan = ConversionPlanBuilder.build(req, interlacedSource(1920, 1080, 29.97, 2), software("libx264"));
    String args = plan.getVideoArgs();
    assertTrue(args.contains("-vf yadif"), args);
    assertFalse(args.contains("yadif_cuda"), args);
  }

  @Test
  public void testPreserveHdr10HevcEmitsMain10AndColorTags()
  {
    ConversionRequest req = ConversionRequest.builder()
        .videoCodec(VideoCodecChoice.HEVC).dynamicRange(DynamicRangeChoice.PRESERVE_HDR10).build();
    ConversionPlan plan = ConversionPlanBuilder.build(req, hdrSource(3840, 2160, 24, 6), nvenc("hevc_nvenc"));
    String args = plan.getVideoArgs();
    assertTrue(args.contains("-profile:v main10"), args);
    assertTrue(args.contains("-color_trc smpte2084"), args);
    assertTrue(args.contains("-color_primaries bt2020"), args);
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void testRejectHdr10OnH264()
  {
    ConversionRequest req = ConversionRequest.builder()
        .videoCodec(VideoCodecChoice.H264).dynamicRange(DynamicRangeChoice.PRESERVE_HDR10).build();
    ConversionPlanBuilder.build(req, hdrSource(3840, 2160, 24, 6), nvenc("h264_nvenc"));
  }

  @Test
  public void testTonemapForcesSoftwareChain()
  {
    ConversionRequest req = ConversionRequest.builder()
        .videoCodec(VideoCodecChoice.HEVC).dynamicRange(DynamicRangeChoice.TONEMAP_SDR).build();
    ConversionPlan plan = ConversionPlanBuilder.build(req, hdrSource(3840, 2160, 24, 6), nvenc("hevc_nvenc"));
    String args = plan.getVideoArgs();
    assertTrue(args.contains("tonemap=hable"), args);
    assertTrue(args.contains("format=yuv420p"), args);
    // tone-map runs on the CPU filter path, so no CUDA hwaccel globals
    assertEquals(plan.getGlobalArgs(), "");
  }

  @Test
  public void testSubtitleCopyMp4UsesMovText()
  {
    ConversionRequest req = ConversionRequest.builder()
        .container(ContainerChoice.MP4).videoCodec(VideoCodecChoice.H264)
        .subtitles(SubtitleChoice.COPY).build();
    ConversionPlan plan = ConversionPlanBuilder.build(req, source(1920, 1080, 30, 2), nvenc("h264_nvenc"));
    assertTrue(plan.getVideoArgs().contains("-c:s mov_text"), plan.getVideoArgs());
  }

  @Test
  public void testSubtitleCopyMkvCopies()
  {
    ConversionRequest req = ConversionRequest.builder()
        .container(ContainerChoice.MKV).videoCodec(VideoCodecChoice.H264)
        .subtitles(SubtitleChoice.COPY).build();
    ConversionPlan plan = ConversionPlanBuilder.build(req, source(1920, 1080, 30, 2), nvenc("h264_nvenc"));
    assertTrue(plan.getVideoArgs().contains("-c:s copy"), plan.getVideoArgs());
  }

  @Test
  public void testAiEnhancementEmitsScaleTargetForChainedUpscaler()
  {
    ConversionRequest req = ConversionRequest.builder()
        .videoCodec(VideoCodecChoice.HEVC).targetSize(3840, 2160).scaling(ScalingChoice.AI).build();
    ConversionPlan plan = ConversionPlanBuilder.build(req, source(1280, 720, 59.94, 6), nvenc("hevc_nvenc"));
    assertTrue(plan.isAiEnhancement());
    assertFalse(plan.isDeinterlaceBeforeAi());
    assertEquals(plan.getTargetWidth(), 3840);
    assertEquals(plan.getTargetHeight(), 2160);
    String args = plan.getVideoArgs();
    // The chained AI upscaler detects its target by parsing the -vf scale token,
    // so it must be present (FFMPEGTranscodeJob strips it for the phase-2 encode).
    assertTrue(args.contains("-vf"), args);
    assertTrue(args.contains("3840:2160"), args);
    assertTrue(args.contains("-c:v hevc_nvenc"), args);
  }

  @Test
  public void testAiEnhancementDeinterlaceStaysOutOfEncodeCommand()
  {
    ConversionRequest req = ConversionRequest.builder()
        .videoCodec(VideoCodecChoice.HEVC).targetSize(3840, 2160).scaling(ScalingChoice.AI).build();
    ConversionPlan plan = ConversionPlanBuilder.build(req, interlacedSource(1920, 1080, 29.97, 6), nvenc("hevc_nvenc"));
    assertTrue(plan.isAiEnhancement());
    assertTrue(plan.isDeinterlaceBeforeAi());
    String args = plan.getVideoArgs();
    // deinterlace is a provider pre-phase, never in the final encode command...
    assertFalse(args.contains("yadif"), args);
    // ...but the scale target must remain so the chained upscaler can detect it.
    assertTrue(args.contains("3840:2160"), args);
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void testRejectAiDownscale()
  {
    ConversionRequest req = ConversionRequest.builder()
        .videoCodec(VideoCodecChoice.HEVC).targetSize(1280, 720).scaling(ScalingChoice.AI).build();
    ConversionPlanBuilder.build(req, source(1920, 1080, 30, 2), nvenc("hevc_nvenc"));
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void testRejectAiWithoutTarget()
  {
    ConversionRequest req = ConversionRequest.builder()
        .videoCodec(VideoCodecChoice.HEVC).scaling(ScalingChoice.AI).build();
    ConversionPlanBuilder.build(req, source(1280, 720, 30, 2), nvenc("hevc_nvenc"));
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void testRejectCopyWithRescale()
  {
    ConversionRequest req = ConversionRequest.builder()
        .videoCodec(VideoCodecChoice.COPY).targetSize(1280, 720).scaling(ScalingChoice.LANCZOS).build();
    ConversionPlanBuilder.build(req, source(1920, 1080, 30, 2), nvenc("h264_nvenc"));
  }
}
