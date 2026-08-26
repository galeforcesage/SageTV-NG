package sage.convert.guided;

import org.testng.annotations.Test;

import static org.testng.Assert.*;

import sage.convert.AudioCodecChoice;
import sage.convert.AudioLayoutChoice;
import sage.convert.ContainerChoice;
import sage.convert.ConversionEngineCaps;
import sage.convert.DynamicRangeChoice;
import sage.convert.FrameRateChoice;
import sage.convert.ScalingChoice;
import sage.convert.SourceMedia;
import sage.convert.VideoCodecChoice;

/**
 * Behaviour tests for the guided recommendation engine (Layer C, front-door
 * logic). Each test reproduces one of the worked examples from the UI design:
 * the recommender must resolve intent+transfer+device+priority into a concrete,
 * device-compatible {@link sage.convert.ConversionRequest}, surface conflicts
 * instead of silently prohibiting, and let manual overrides win.
 */
public class GuidedRecommenderTest
{
  private static ConversionEngineCaps nvenc()
  {
    return ConversionEngineCaps.builder()
        .videoEncoderName("hevc_nvenc").nvenc(true)
        .scalerFilter("scale_npp").scalerSupportsLanczos(true).supportsFpsMax(true)
        .deinterlacer("yadif_cuda")
        .build();
  }

  /** 1080i 29.97 MPEG-2 broadcast, 5.1 AC-3 — the tablet worked example source. */
  private static SourceMedia broadcast1080i()
  {
    return SourceMedia.builder().width(1920).height(1080).fps(29.97).audioChannels(6)
        .interlaced(true).videoCodec("MPEG2-VIDEO").audioCodec("AC3").containerMuxer("mpegts")
        .durationMillis(3_600_000L).build();
  }

  /** 720p59.94 H.264 sports recording. */
  private static SourceMedia sportsHd()
  {
    return SourceMedia.builder().width(1280).height(720).fps(59.94).audioChannels(6)
        .videoCodec("H264").audioCodec("AC3").containerMuxer("mpegts")
        .durationMillis(3_600_000L).build();
  }

  /** 4K HDR (BT.2020/PQ) HEVC source, 5.1 E-AC-3. */
  private static SourceMedia uhdHdr()
  {
    return SourceMedia.builder().width(3840).height(2160).fps(23.976).audioChannels(6)
        .hdr(true).colorspace("bt2020nc").videoCodec("HEVC").audioCodec("EAC3")
        .containerMuxer("matroska").durationMillis(3_600_000L).build();
  }

  @Test
  public void tabletCellularBalanced_downscalesToCompatible720p()
  {
    GuidedInputs in = GuidedInputs.builder(broadcast1080i())
        .goal(CreationGoal.TABLET_OFFLINE)
        .transfer(TransferClass.LIMITED_WAN)
        .device(DeviceProfile.tablet())
        .priority(QualityPriority.BALANCED)
        .build();

    Recommendation rec = GuidedRecommender.recommend(in, nvenc());

    assertTrue(rec.isBuildable(), "should build");
    assertFalse(rec.hasBlockingConflict(), "no blocking conflict");
    assertEquals(rec.getRequest().getVideoCodec(), VideoCodecChoice.H264, "compat codec");
    assertEquals(rec.getRequest().getContainer(), ContainerChoice.MP4, "compat container");
    assertEquals(rec.getRequest().getTargetWidth(), 1280, "downscaled width");
    assertEquals(rec.getRequest().getTargetHeight(), 720, "downscaled height");
    assertEquals(rec.getRequest().getScaling(), ScalingChoice.LANCZOS, "conventional downscale");
    assertEquals(rec.getRequest().getAudioCodec(), AudioCodecChoice.AAC, "compat audio");
    assertEquals(rec.getRequest().getAudioLayout(), AudioLayoutChoice.STEREO, "stereo downmix");
    assertTrue(rec.getEstimatedBytes() > 0, "has a size estimate");
  }

  @Test
  public void highFps_capsAndWarns_unlessSmoothMotionKept()
  {
    // 720p59.94 to a tablet on cellular, balanced: caps to 30 and warns.
    GuidedInputs capped = GuidedInputs.builder(sportsHd())
        .goal(CreationGoal.TABLET_OFFLINE)
        .transfer(TransferClass.LIMITED_WAN)
        .device(DeviceProfile.tablet())
        .priority(QualityPriority.BALANCED)
        .build();
    Recommendation r1 = GuidedRecommender.recommend(capped, nvenc());
    assertEquals(r1.getRequest().getFrameRate(), FrameRateChoice.CAP_30, "high fps capped");
    assertTrue(hasMessage(r1, "less smooth"), "smooth-motion warning present");
    assertFalse(r1.hasBlockingConflict(), "warning is not blocking");

    // Same, but ask to preserve smooth motion: keep 59.94, no warning.
    GuidedInputs smooth = GuidedInputs.builder(sportsHd())
        .goal(CreationGoal.TABLET_OFFLINE)
        .transfer(TransferClass.LIMITED_WAN)
        .device(DeviceProfile.tablet())
        .priority(QualityPriority.BALANCED)
        .preserveSmoothMotion(true)
        .build();
    Recommendation r2 = GuidedRecommender.recommend(smooth, nvenc());
    assertEquals(r2.getRequest().getFrameRate(), FrameRateChoice.KEEP, "smooth motion kept");
    assertFalse(hasMessage(r2, "less smooth"), "no smooth-motion warning");
  }

  @Test
  public void archiveCombo_spaceSavingHevcMkv_noScaleNoFpsCut_keepsSurround()
  {
    GuidedInputs in = GuidedInputs.builder(uhdHdr())
        .goal(CreationGoal.REDUCE_STORAGE)
        .goal(CreationGoal.PRESERVE_RES_FPS)
        .goal(CreationGoal.PRESERVE_SURROUND)
        .transfer(TransferClass.UNRESTRICTED)
        .device(DeviceProfile.unrestricted())
        .priority(QualityPriority.BALANCED)
        .build();

    Recommendation rec = GuidedRecommender.recommend(in, nvenc());

    assertTrue(rec.isBuildable(), "should build");
    assertEquals(rec.getRequest().getVideoCodec(), VideoCodecChoice.HEVC, "space-saving codec");
    assertEquals(rec.getRequest().getContainer(), ContainerChoice.MKV, "archive container");
    assertEquals(rec.getRequest().getScaling(), ScalingChoice.NONE, "no scaling");
    assertEquals(rec.getRequest().getTargetWidth(), 0, "keep source resolution");
    assertEquals(rec.getRequest().getFrameRate(), FrameRateChoice.KEEP, "keep source fps");
    assertEquals(rec.getRequest().getAudioCodec(), AudioCodecChoice.COPY, "keep surround losslessly");
  }

  @Test
  public void exactBackupPlusAiUpscale_isIncompatible()
  {
    GuidedInputs in = GuidedInputs.builder(broadcast1080i())
        .goal(CreationGoal.EXACT_BACKUP)
        .goal(CreationGoal.IMPROVE_UPSCALE)
        .device(DeviceProfile.unrestricted())
        .build();

    Recommendation rec = GuidedRecommender.recommend(in, nvenc());

    assertTrue(rec.hasBlockingConflict(), "exact + enhance must be incompatible");
    assertTrue(hasMessage(rec, "exact original backup"), "explains the exact-backup conflict");
  }

  @Test
  public void preserveHdr_autoUpgradesH264ToHevc()
  {
    // Ask for HDR preservation but prioritize compatibility (which wants H.264).
    GuidedInputs in = GuidedInputs.builder(uhdHdr())
        .goal(CreationGoal.PRESERVE_HDR)
        .device(DeviceProfile.modern4kTv())
        .priority(QualityPriority.MAX_COMPAT)
        .build();

    Recommendation rec = GuidedRecommender.recommend(in, nvenc());

    assertTrue(rec.isBuildable(), "should build");
    assertFalse(rec.hasBlockingConflict(), "auto-upgrade avoids a blocking conflict");
    assertEquals(rec.getRequest().getVideoCodec(), VideoCodecChoice.HEVC, "upgraded for HDR");
    assertEquals(rec.getRequest().getDynamicRange(), DynamicRangeChoice.PRESERVE_HDR10, "HDR10 preserved");
  }

  @Test
  public void override_forcingH264OnHdr_isIncompatibleButReported()
  {
    GuidedInputs.Overrides ov = new GuidedInputs.Overrides();
    ov.videoCodec = VideoCodecChoice.H264;   // user forces H.264 on an HDR-preserve plan
    GuidedInputs in = GuidedInputs.builder(uhdHdr())
        .goal(CreationGoal.PRESERVE_HDR)
        .device(DeviceProfile.modern4kTv())
        .priority(QualityPriority.BEST_PICTURE)
        .overrides(ov)
        .build();

    Recommendation rec = GuidedRecommender.recommend(in, nvenc());

    assertEquals(rec.getRequest().getVideoCodec(), VideoCodecChoice.H264, "override wins");
    assertTrue(rec.hasBlockingConflict(), "H.264 + HDR10 is incompatible");
    assertFalse(rec.isBuildable(), "blocking conflict means no plan");
    assertTrue(hasMessage(rec, "HDR10"), "explains the HDR/codec conflict");
  }

  @Test
  public void override_unsupportedCodecForDevice_isUnverifiedNotBlocked()
  {
    // Phone profile doesn't report AV1; user forces AV1 → allowed but unverified.
    GuidedInputs.Overrides ov = new GuidedInputs.Overrides();
    ov.videoCodec = VideoCodecChoice.AV1;
    GuidedInputs in = GuidedInputs.builder(sportsHd())
        .goal(CreationGoal.PHONE_OFFLINE)
        .device(DeviceProfile.phone())
        .priority(QualityPriority.BALANCED)
        .overrides(ov)
        .build();

    Recommendation rec = GuidedRecommender.recommend(in, nvenc());

    assertEquals(rec.getRequest().getVideoCodec(), VideoCodecChoice.AV1, "override kept");
    assertFalse(rec.hasBlockingConflict(), "unverified is not blocking");
    assertEquals(rec.worstSeverity(), Conflict.Severity.UNVERIFIED, "flagged unverified");
  }

  private static boolean hasMessage(Recommendation rec, String needle)
  {
    for (Conflict c : rec.getConflicts())
      if (c.getMessage() != null && c.getMessage().toLowerCase().indexOf(needle.toLowerCase()) != -1)
        return true;
    return false;
  }
}
