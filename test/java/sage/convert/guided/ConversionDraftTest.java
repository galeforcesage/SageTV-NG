package sage.convert.guided;

import org.testng.annotations.Test;

import static org.testng.Assert.*;

import sage.convert.ConversionEngineCaps;
import sage.convert.SourceMedia;

/**
 * Lifecycle + report-formatting tests for the stateful {@link ConversionDraft}
 * and the {@link GuidedReports} strings the STV wizard renders. These pin the
 * server-facing behaviour (loose token tolerance, override channel, and that the
 * reports never leak engine internals) without needing a running server.
 */
public class ConversionDraftTest
{
  private static ConversionEngineCaps nvenc()
  {
    return ConversionEngineCaps.builder()
        .videoEncoderName("hevc_nvenc").nvenc(true)
        .scalerFilter("scale_npp").scalerSupportsLanczos(true).supportsFpsMax(true)
        .deinterlacer("yadif_cuda")
        .build();
  }

  private static SourceMedia broadcast1080i()
  {
    return SourceMedia.builder().width(1920).height(1080).fps(29.97).audioChannels(6)
        .interlaced(true).videoCodec("MPEG2-VIDEO").audioCodec("AC3").containerMuxer("mpegts")
        .durationMillis(3_600_000L).build();
  }

  @Test
  public void draft_appliesTokensAndResolves()
  {
    ConversionDraft d = new ConversionDraft(1, broadcast1080i(), 3_600_000L);
    d.setGoal("PHONE_OFFLINE", true);
    d.setTransfer("LIMITED_WAN");
    d.setDevice("phone");
    d.setPriority("SMALLER");

    GuidedInputs in = d.toInputs();
    assertTrue(in.has(CreationGoal.PHONE_OFFLINE), "goal recorded");
    assertEquals(in.getTransfer(), TransferClass.LIMITED_WAN, "transfer recorded");
    assertEquals(in.getPriority(), QualityPriority.SMALLER, "priority recorded");

    Recommendation rec = GuidedRecommender.recommend(in, nvenc());
    assertTrue(rec.isBuildable(), "phone/cellular draft builds");
  }

  @Test
  public void draft_ignoresUnknownTokens()
  {
    ConversionDraft d = new ConversionDraft(2, broadcast1080i(), 3_600_000L);
    d.setGoal("NOT_A_GOAL", true);
    d.setTransfer("FURTHER_THAN_MARS");
    d.setDevice("microwave");
    d.setPriority("WHATEVER");
    d.setPreference("teleport", true);

    GuidedInputs in = d.toInputs();
    assertTrue(in.getGoals().isEmpty(), "unknown goal ignored");
    assertEquals(in.getTransfer(), TransferClass.UNRESTRICTED, "unknown transfer left at default");
    assertEquals(in.getPriority(), QualityPriority.BALANCED, "unknown priority left at default");
  }

  @Test
  public void draft_overrideWinsThenClears()
  {
    ConversionDraft d = new ConversionDraft(3, broadcast1080i(), 3_600_000L);
    d.setGoal("PHONE_OFFLINE", true);
    d.setDevice("phone");

    d.setOverride("videocodec", "HEVC");
    assertTrue(d.hasOverrides(), "override registered");
    assertEquals(d.toInputs().getOverrides().videoCodec, sage.convert.VideoCodecChoice.HEVC, "override captured");

    d.setOverride("videocodec", "auto");
    assertNull(d.toInputs().getOverrides().videoCodec, "\"auto\" clears the override");
    assertFalse(d.hasOverrides(), "no overrides after clear");
  }

  @Test
  public void reports_areUserFacingAndLeakNoInternals()
  {
    ConversionDraft d = new ConversionDraft(4, broadcast1080i(), 3_600_000L);
    d.setGoal("REDUCE_STORAGE", true);
    d.setPriority("SMALLER");
    Recommendation rec = GuidedRecommender.recommend(d.toInputs(), nvenc());

    String recReport = GuidedReports.recommendationReport(rec, d.getDurationMillis());
    assertTrue(recReport.startsWith("Recommended:"), "recommendation report has headline");
    assertFalse(recReport.contains("nvenc"), "no encoder token leaks");
    assertFalse(recReport.contains("scale_npp"), "no filter token leaks");

    String[] groups = GuidedReports.groupSummaries(rec);
    assertTrue(groups.length >= 4, "customize screen has group lines");

    String review = GuidedReports.reviewReport(rec, d.getSource(), d.getDurationMillis());
    assertTrue(review.contains("Source:") && review.contains("Output:"), "review shows both sides");
  }

  @Test
  public void reports_conflictLinesTagBlockingCombo()
  {
    ConversionDraft d = new ConversionDraft(5, broadcast1080i(), 3_600_000L);
    d.setGoal("EXACT_BACKUP", true);
    d.setGoal("IMPROVE_UPSCALE", true);
    Recommendation rec = GuidedRecommender.recommend(d.toInputs(), nvenc());

    String[] conflicts = GuidedReports.conflictLines(rec);
    assertTrue(conflicts.length > 0, "the exact+upscale combo surfaces a conflict");
    boolean blocking = false;
    for (String c : conflicts) if (c.startsWith("[Blocking]")) blocking = true;
    assertTrue(blocking, "the combo is tagged as blocking");
  }
}
