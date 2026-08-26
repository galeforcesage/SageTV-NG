package sage.convert;

import org.testng.annotations.Test;

import static org.testng.Assert.*;

/**
 * Human-reviewable showcase of the offline conversion engine (Layer A).
 *
 * <p>This is a documentation/acceptance harness, not a unit test of a single
 * method: for each canonical {@link ConversionPurpose} it builds a plan against a
 * representative source and prints the fully-resolved ffmpeg argument spec, so a
 * reviewer can eyeball exactly what the engine would run — without deploying to
 * the server. It still asserts the load-bearing invariants for each purpose, so
 * it fails loudly if the emitted command ever regresses.
 *
 * <p>Run just this class:
 * {@code .\gradlew test --tests sage.convert.ConversionPlanShowcaseTest --console=plain -q}
 * The resolved commands are written to stdout (visible with {@code -i} or in the
 * test's {@code standardOutput} in the HTML/XML report).
 */
public class ConversionPlanShowcaseTest
{
  private static ConversionEngineCaps nvenc(String enc)
  {
    return ConversionEngineCaps.builder()
        .videoEncoderName(enc).nvenc(true)
        .scalerFilter("scale_npp").scalerSupportsLanczos(true).supportsFpsMax(true)
        .deinterlacer("yadif_cuda")
        .build();
  }

  private static ConversionEngineCaps software(String enc)
  {
    return ConversionEngineCaps.builder()
        .videoEncoderName(enc).nvenc(false)
        .scalerFilter("scale").scalerSupportsLanczos(true).supportsFpsMax(true)
        .deinterlacer("yadif")
        .build();
  }

  /** A typical 720p59.94 NFL recording (the VSR team's real source). */
  private static SourceMedia sportsHd()
  {
    return SourceMedia.builder().width(1280).height(720).fps(59.94).audioChannels(6)
        .videoCodec("H264").audioCodec("AC3").containerMuxer("mpegts")
        .durationMillis(3_600_000L).build();
  }

  /** A 1080i broadcast (interlaced) recording. */
  private static SourceMedia broadcast1080i()
  {
    return SourceMedia.builder().width(1920).height(1080).fps(29.97).audioChannels(6)
        .interlaced(true)
        .videoCodec("MPEG2-VIDEO").audioCodec("AC3").containerMuxer("mpegts")
        .durationMillis(3_600_000L).build();
  }

  /** A 4K HDR (BT.2020/PQ) HEVC source. */
  private static SourceMedia uhdHdr()
  {
    return SourceMedia.builder().width(3840).height(2160).fps(23.976).audioChannels(6)
        .hdr(true).colorspace("bt2020nc")
        .videoCodec("HEVC").audioCodec("EAC3").containerMuxer("matroska")
        .durationMillis(3_600_000L).build();
  }

  private static void dump(String title, ConversionPlan plan)
  {
    System.out.println();
    System.out.println("==================================================================");
    System.out.println("PURPOSE: " + title);
    System.out.println("------------------------------------------------------------------");
    System.out.println("summary : " + plan.getSummary());
    System.out.println("muxer   : " + plan.getMuxer());
    System.out.println("global  : " + plan.getGlobalArgs());
    System.out.println("video   : " + plan.getVideoArgs());
    System.out.println("spec    : " + plan.getFormatSpec());
    System.out.println("aiPhase : upscale=" + plan.isAiEnhancement()
        + " deinterlaceBeforeAi=" + plan.isDeinterlaceBeforeAi());
    System.out.println("ops     :");
    for (String op : plan.getOperations())
      System.out.println("            - " + op);
  }

  @Test
  public void showcase_UsbTv_1080p60_from720p60()
  {
    // "Play on my USB-stick TV": upscale 720p60 sports to 1080p60 H.264.
    // Load-bearing: this is the exact case that used to fail — 1080p60 must be
    // H.264 Level 4.2, not 4.0, or NVENC rejects it.
    ConversionRequest req = ConversionRequest.builder()
        .purpose(ConversionPurpose.USB_TV)
        .container(ContainerChoice.MP4)
        .videoCodec(VideoCodecChoice.H264)
        .targetSize(1920, 1080).scaling(ScalingChoice.LANCZOS)
        .frameRate(FrameRateChoice.ALLOW_60)
        .audioCodec(AudioCodecChoice.AAC).audioBitrateKbps(192).audioLayout(AudioLayoutChoice.STEREO)
        .qualityCq(21).build();
    ConversionPlan plan = ConversionPlanBuilder.build(req, sportsHd(), nvenc("h264_nvenc"));
    dump("USB_TV — 720p60 -> 1080p60 H.264 (Level 4.2 fix)", plan);

    assertTrue(plan.getVideoArgs().contains("-level 4.2"), plan.getVideoArgs());
    assertTrue(plan.getVideoArgs().contains("scale_npp=1920:1080:interp_algo=lanczos"), plan.getVideoArgs());
    assertTrue(plan.getVideoArgs().contains("-c:v h264_nvenc"), plan.getVideoArgs());
  }

  @Test
  public void showcase_OfflineDevice_phone_720p_interlaced_downTo30()
  {
    // "Fit on my phone": 1080i broadcast -> 720p H.264, deinterlaced, capped to 30fps.
    // Load-bearing: deinterlace must precede scale in the single -vf chain.
    ConversionRequest req = ConversionRequest.builder()
        .purpose(ConversionPurpose.OFFLINE_DEVICE)
        .container(ContainerChoice.MP4)
        .videoCodec(VideoCodecChoice.H264)
        .targetSize(1280, 720).scaling(ScalingChoice.LANCZOS)
        .frameRate(FrameRateChoice.CAP_30)
        .audioCodec(AudioCodecChoice.AAC).audioBitrateKbps(128).audioLayout(AudioLayoutChoice.STEREO)
        .qualityCq(23).build();
    ConversionPlan plan = ConversionPlanBuilder.build(req, broadcast1080i(), nvenc("h264_nvenc"));
    dump("OFFLINE_DEVICE — 1080i -> 720p30 H.264 (deint before scale)", plan);

    String vf = plan.getVideoArgs();
    int deint = vf.indexOf("yadif_cuda");
    int scale = vf.indexOf("scale_npp");
    assertTrue(deint >= 0 && scale >= 0 && deint < scale, "deinterlace must precede scale: " + vf);
    assertTrue(vf.contains("-fpsmax 30000/1001"), vf);
  }

  @Test
  public void showcase_Travel_dataBudget_capped()
  {
    // "Only 2 GB of hotel wifi": a hard output-size budget drives rate control.
    ConversionRequest req = ConversionRequest.builder()
        .purpose(ConversionPurpose.TRAVEL)
        .container(ContainerChoice.MP4)
        .videoCodec(VideoCodecChoice.H264)
        .targetSize(1280, 720).scaling(ScalingChoice.LANCZOS)
        .frameRate(FrameRateChoice.CAP_30)
        .audioCodec(AudioCodecChoice.AAC).audioBitrateKbps(96).audioLayout(AudioLayoutChoice.STEREO)
        .maxOutputBytes(2L * 1024 * 1024 * 1024).build();
    ConversionPlan plan = ConversionPlanBuilder.build(req, sportsHd(), nvenc("h264_nvenc"));
    dump("TRAVEL — 720p30 H.264, 2 GB data budget", plan);

    assertTrue(plan.getVideoArgs().contains("-b:v") || plan.getVideoArgs().contains("-maxrate"),
        "budget must set a bitrate ceiling: " + plan.getVideoArgs());
  }

  @Test
  public void showcase_EnhancedFavorite_AI_720p60_to_4k()
  {
    // "Enhance my favorite": AI super-resolution 720p -> 4K HEVC. The upscale/deint
    // are a chained pre-phase (Real-ESRGAN/VSR); the encode keeps the scale target
    // token so the chained job can detect it (it is stripped for the phase-2 encode).
    ConversionRequest req = ConversionRequest.builder()
        .purpose(ConversionPurpose.ENHANCED_FAVORITE)
        .container(ContainerChoice.MKV)
        .videoCodec(VideoCodecChoice.HEVC)
        .targetSize(3840, 2160).scaling(ScalingChoice.AI)
        .frameRate(FrameRateChoice.KEEP)
        .audioCodec(AudioCodecChoice.EAC3).audioLayout(AudioLayoutChoice.SURROUND_51)
        .qualityCq(20).build();
    ConversionPlan plan = ConversionPlanBuilder.build(req, sportsHd(), nvenc("hevc_nvenc"));
    dump("ENHANCED_FAVORITE — AI 720p -> 4K HEVC (chained pre-phase)", plan);

    assertTrue(plan.isAiEnhancement());
    assertTrue(plan.getVideoArgs().contains("3840:2160"),
        "AI: scale target retained so the chained upscaler engages: " + plan.getVideoArgs());
    assertEquals(plan.getMuxer(), "matroska");
  }

  @Test
  public void showcase_Archive_HDR_preserved_HEVC()
  {
    // "Archive in full quality": keep 4K HDR, HEVC main10, BT.2020/PQ preserved.
    ConversionRequest req = ConversionRequest.builder()
        .purpose(ConversionPurpose.ARCHIVE)
        .container(ContainerChoice.MKV)
        .videoCodec(VideoCodecChoice.HEVC)
        .dynamicRange(DynamicRangeChoice.PRESERVE_HDR10)
        .frameRate(FrameRateChoice.KEEP)
        .audioCodec(AudioCodecChoice.COPY).audioLayout(AudioLayoutChoice.KEEP)
        .qualityCq(18).build();
    ConversionPlan plan = ConversionPlanBuilder.build(req, uhdHdr(), software("libx265"));
    dump("ARCHIVE — 4K HDR HEVC preserved (main10 / BT.2020 / PQ)", plan);

    String vf = plan.getVideoArgs();
    assertTrue(vf.contains("main10"), vf);
    assertTrue(vf.contains("bt2020"), vf);
    assertTrue(vf.contains("smpte2084"), vf);
  }

  @Test
  public void showcase_ExactBackup_streamCopy()
  {
    // "Just copy it losslessly into MKV": stream copy, no re-encode.
    ConversionRequest req = ConversionRequest.builder()
        .purpose(ConversionPurpose.EXACT_BACKUP)
        .container(ContainerChoice.MKV)
        .videoCodec(VideoCodecChoice.COPY)
        .audioCodec(AudioCodecChoice.COPY).audioLayout(AudioLayoutChoice.KEEP)
        .frameRate(FrameRateChoice.KEEP).build();
    ConversionPlan plan = ConversionPlanBuilder.build(req, uhdHdr(), nvenc("hevc_nvenc"));
    dump("EXACT_BACKUP — lossless stream copy into MKV", plan);

    assertTrue(plan.isVideoStreamCopy());
    assertTrue(plan.getVideoArgs().contains("-c:v copy"), plan.getVideoArgs());
    assertEquals(plan.getMuxer(), "matroska");
  }
}
