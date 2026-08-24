package sage.enhance;

import java.util.List;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import sage.Sage;
import sage.TestUtils;

import static org.testng.Assert.*;

/**
 * Tests for the ffmpeg token builder.
 *
 * <p>The filter-chain and encoder-arg builders are pure functions of an
 * {@link EnhancementPlan}, so they are tested directly with hand-built plans
 * rather than through {@link GpuEnhancePipeline#buildPlan}, which necessarily
 * shells out to probe the real ffmpeg binary.
 */
public class GpuEnhancePipelineTest
{
  private static final String PROP_MAX_BITRATE = "playback/gpu_enhance/max_bitrate_kbps";
  private static final String PROP_GPU_INDEX   = "playback/gpu_enhance/gpu_index";
  private static final String PROP_SPATIAL_AQ   = "playback/gpu_enhance/spatial_aq";
  private static final String PROP_TEMPORAL_AQ  = "playback/gpu_enhance/temporal_aq";
  private static final String PROP_AQ_STRENGTH  = "playback/gpu_enhance/aq_strength";
  private static final String PROP_RC_LOOKAHEAD = "playback/gpu_enhance/rc_lookahead";
  private static final String PROP_MULTIPASS    = "playback/gpu_enhance/multipass";

  @BeforeMethod
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.remove(PROP_MAX_BITRATE);
    Sage.remove(PROP_GPU_INDEX);
    Sage.remove(PROP_SPATIAL_AQ);
    Sage.remove(PROP_TEMPORAL_AQ);
    Sage.remove(PROP_AQ_STRENGTH);
    Sage.remove(PROP_RC_LOOKAHEAD);
    Sage.remove(PROP_MULTIPASS);
    Sage.remove("playback/gpu_enhance/bitrate/enhance_2160p/high");
    Sage.remove("playback/gpu_enhance/bitrate/enhance_2160p/low");
  }

  private static EnhancementPlan plan(EnhancementTier tier, boolean deint, String scaler)
  {
    return new EnhancementPlan(tier, deint, deint ? "yadif_cuda" : null, scaler,
        tier.getTargetWidth(), tier.getTargetHeight(), 25000, 0, "test");
  }

  // ---- Global args --------------------------------------------------------

  /**
   * {@code -hwaccel_output_format cuda} is the load-bearing token. Without it
   * ffmpeg copies decoded frames back to system RAM, which is exactly the gap in
   * the current live path.
   */
  @Test
  public void testGlobalArgsKeepFramesInVram()
  {
    List<String> g = GpuEnhancePipeline.buildGlobalArgs(
        plan(EnhancementTier.ENHANCE_2160P, true, "scale_npp"));
    assertTrue(g.contains("-hwaccel"));
    assertTrue(g.contains("cuda"));
    int i = g.indexOf("-hwaccel_output_format");
    assertTrue(i >= 0, "must request a CUDA output format: " + g);
    assertEquals(g.get(i + 1), "cuda");
  }

  @Test
  public void testGlobalArgsAreEmptyForInactivePlan()
  {
    assertTrue(GpuEnhancePipeline.buildGlobalArgs(EnhancementPlan.NONE).isEmpty());
    assertTrue(GpuEnhancePipeline.buildGlobalArgs(null).isEmpty());
  }

  @Test
  public void testGpuIndexIsOmittedUnlessConfigured()
  {
    List<String> g = GpuEnhancePipeline.buildGlobalArgs(
        plan(EnhancementTier.ENHANCE_1080P, false, null));
    assertFalse(g.contains("-hwaccel_device"));

    Sage.put(PROP_GPU_INDEX, "1");
    g = GpuEnhancePipeline.buildGlobalArgs(plan(EnhancementTier.ENHANCE_1080P, false, null));
    int i = g.indexOf("-hwaccel_device");
    assertTrue(i >= 0);
    assertEquals(g.get(i + 1), "1");
  }

  // ---- Filter chain -------------------------------------------------------

  /**
   * The {@code deint=1} flag (third parameter) restricts deinterlacing to frames
   * actually flagged interlaced. Without it, 720p60 would be mangled and
   * mixed-cadence channels would break.
   */
  @Test
  public void testDeinterlacerOnlyTouchesInterlacedFrames()
  {
    String chain = GpuEnhancePipeline.buildFilterChain(
        plan(EnhancementTier.DEINTERLACE_ONLY, true, null));
    assertEquals(chain, "yadif_cuda=0:-1:1");
  }

  @Test
  public void testScaleNppUsesLanczos()
  {
    String chain = GpuEnhancePipeline.buildFilterChain(
        plan(EnhancementTier.ENHANCE_2160P, false, "scale_npp"));
    assertEquals(chain, "scale_npp=3840:2160:interp_algo=lanczos");
  }

  /** {@code scale_cuda} gets Lanczos too on builds that expose {@code interp_algo}
   *  (gated by the capability probe / override), so switching off the deprecated
   *  NPP filter costs no quality. */
  @Test
  public void testScaleCudaUsesLanczosWhenSupported()
  {
    Sage.putBoolean("playback/gpu_enhance/scale_cuda_lanczos", true);
    try
    {
      String chain = GpuEnhancePipeline.buildFilterChain(
          plan(EnhancementTier.ENHANCE_2160P, false, "scale_cuda"));
      assertEquals(chain, "scale_cuda=3840:2160:interp_algo=lanczos");
    }
    finally { Sage.remove("playback/gpu_enhance/scale_cuda_lanczos"); }
  }

  /** A bilinear-only {@code scale_cuda} (no interp_algo) must not be handed a
   *  Lanczos option ffmpeg would reject. */
  @Test
  public void testScaleCudaOmitsInterpAlgoWhenUnsupported()
  {
    Sage.putBoolean("playback/gpu_enhance/scale_cuda_lanczos", false);
    try
    {
      String chain = GpuEnhancePipeline.buildFilterChain(
          plan(EnhancementTier.ENHANCE_2160P, false, "scale_cuda"));
      assertEquals(chain, "scale_cuda=3840:2160");
    }
    finally { Sage.remove("playback/gpu_enhance/scale_cuda_lanczos"); }
  }

  @Test
  public void testDeinterlaceComesBeforeScale()
  {
    String chain = GpuEnhancePipeline.buildFilterChain(
        plan(EnhancementTier.ENHANCE_2160P, true, "scale_npp"));
    assertEquals(chain, "yadif_cuda=0:-1:1,scale_npp=3840:2160:interp_algo=lanczos");
    assertTrue(chain.indexOf("yadif_cuda") < chain.indexOf("scale_npp"),
        "deinterlacing must happen before scaling");
  }

  /** No upload/download hops: frames are already resident in VRAM. */
  @Test
  public void testChainHasNoHostRoundTrip()
  {
    String chain = GpuEnhancePipeline.buildFilterChain(
        plan(EnhancementTier.ENHANCE_2160P, true, "scale_npp"));
    assertFalse(chain.contains("hwdownload"), chain);
    assertFalse(chain.contains("hwupload"), chain);
  }

  @Test
  public void testInactivePlanHasNoFilterChain()
  {
    assertNull(GpuEnhancePipeline.buildFilterChain(EnhancementPlan.NONE));
    assertNull(GpuEnhancePipeline.buildFilterChain(null));
  }

  // ---- Encoder args -------------------------------------------------------

  @Test
  public void testEncoderArgsTargetHevcNvencWithLiveFriendlyGop()
  {
    List<String> e = GpuEnhancePipeline.buildEncoderArgs(
        plan(EnhancementTier.ENHANCE_2160P, true, "scale_npp"), 60);
    assertEquals(e.get(e.indexOf("-c:v") + 1), "hevc_nvenc");
    // No B-frames and a 2-second GOP, matching the existing push/HLS branches.
    assertEquals(e.get(e.indexOf("-bf") + 1), "0");
    assertEquals(e.get(e.indexOf("-g") + 1), "120");
    assertEquals(e.get(e.indexOf("-tag:v") + 1), "hvc1");
  }

  /**
   * Adaptive quantization is on by default (spatial + temporal), improving
   * high-detail/high-motion quality-per-bit at no latency cost.
   */
  @Test
  public void testAdaptiveQuantizationOnByDefault()
  {
    List<String> e = GpuEnhancePipeline.buildEncoderArgs(
        plan(EnhancementTier.ENHANCE_2160P, false, "scale_npp"), 60);
    assertTrue(e.contains("-spatial_aq"), e.toString());
    assertEquals(e.get(e.indexOf("-spatial_aq") + 1), "1");
    assertTrue(e.contains("-temporal_aq"), e.toString());
    assertEquals(e.get(e.indexOf("-temporal_aq") + 1), "1");
    // No aq-strength unless explicitly configured.
    assertFalse(e.contains("-aq-strength"), e.toString());
  }

  @Test
  public void testAdaptiveQuantizationCanBeDisabled()
  {
    Sage.putBoolean(PROP_SPATIAL_AQ, false);
    Sage.putBoolean(PROP_TEMPORAL_AQ, false);
    List<String> e = GpuEnhancePipeline.buildEncoderArgs(
        plan(EnhancementTier.ENHANCE_2160P, false, "scale_npp"), 60);
    assertFalse(e.contains("-spatial_aq"), e.toString());
    assertFalse(e.contains("-temporal_aq"), e.toString());
  }

  @Test
  public void testAqStrengthAppliedWhenInRange()
  {
    Sage.putInt(PROP_AQ_STRENGTH, 8);
    List<String> e = GpuEnhancePipeline.buildEncoderArgs(
        plan(EnhancementTier.ENHANCE_2160P, false, "scale_npp"), 60);
    assertEquals(e.get(e.indexOf("-aq-strength") + 1), "8");
  }

  /**
   * Lookahead and multipass add latency, so they must be absent unless a
   * deployment explicitly opts in — the live/trickplay path stays low-latency.
   */
  @Test
  public void testLookaheadAndMultipassOffByDefault()
  {
    List<String> e = GpuEnhancePipeline.buildEncoderArgs(
        plan(EnhancementTier.ENHANCE_2160P, false, "scale_npp"), 60);
    assertFalse(e.contains("-rc-lookahead"), e.toString());
    assertFalse(e.contains("-multipass"), e.toString());
  }

  @Test
  public void testLookaheadAndMultipassOptIn()
  {
    Sage.putInt(PROP_RC_LOOKAHEAD, 20);
    Sage.put(PROP_MULTIPASS, "fullres");
    List<String> e = GpuEnhancePipeline.buildEncoderArgs(
        plan(EnhancementTier.ENHANCE_2160P, false, "scale_npp"), 60);
    assertEquals(e.get(e.indexOf("-rc-lookahead") + 1), "20");
    assertEquals(e.get(e.indexOf("-multipass") + 1), "fullres");
  }

  @Test
  public void testMultipassSentinelValuesAreIgnored()
  {
    Sage.put(PROP_MULTIPASS, "none");
    List<String> e = GpuEnhancePipeline.buildEncoderArgs(
        plan(EnhancementTier.ENHANCE_2160P, false, "scale_npp"), 60);
    assertFalse(e.contains("-multipass"), e.toString());
  }

  @Test
  public void testGopTracksFrameRate()
  {
    List<String> e = GpuEnhancePipeline.buildEncoderArgs(
        plan(EnhancementTier.ENHANCE_2160P, false, "scale_npp"), 30);
    assertEquals(e.get(e.indexOf("-g") + 1), "60");
  }

  @Test
  public void testEncoderArgsCarryBitrateEnvelope()
  {
    List<String> e = GpuEnhancePipeline.buildEncoderArgs(
        plan(EnhancementTier.ENHANCE_2160P, false, "scale_npp"), 60);
    assertEquals(e.get(e.indexOf("-b:v") + 1), "25000k");
    assertTrue(e.contains("-maxrate"));
    assertTrue(e.contains("-bufsize"));
  }

  /** Audio is handled by the existing per-surface machinery, never here. */
  @Test
  public void testEncoderArgsNeverTouchAudio()
  {
    List<String> e = GpuEnhancePipeline.buildEncoderArgs(
        plan(EnhancementTier.ENHANCE_2160P, true, "scale_npp"), 60);
    for (String a : e)
      assertFalse(a.startsWith("-c:a"), "pipeline must not decide audio codecs: " + e);
  }

  @Test
  public void testInactivePlanProducesNoEncoderArgs()
  {
    assertTrue(GpuEnhancePipeline.buildEncoderArgs(EnhancementPlan.NONE, 60).isEmpty());
    assertTrue(GpuEnhancePipeline.buildEncoderArgs(null, 60).isEmpty());
  }

  // ---- Source floor in buildPlan -----------------------------------------

  /**
   * The floor is enforced before any ffmpeg probing, so this is testable without
   * a real binary — and 480 must be rejected even though 720x480's width is 720.
   */
  @Test
  public void testBuildPlanRejectsSubFloorSourcesForUpscaleTiers()
  {
    EnhancementPlan p = GpuEnhancePipeline.buildPlan(
        EnhancementTier.ENHANCE_2160P, true, 480, 25000);
    assertFalse(p.isActive());
    assertTrue(p.getReason().contains("below floor"), p.getReason());
  }

  @Test
  public void testBuildPlanRejectsNullAndNoneTier()
  {
    assertFalse(GpuEnhancePipeline.buildPlan(null, true, 1080, 25000).isActive());
    assertFalse(GpuEnhancePipeline.buildPlan(EnhancementTier.NONE, true, 1080, 25000).isActive());
  }

  // ---- Bitrate ladder -----------------------------------------------------

  @Test
  public void testHighMotionGetsMoreBitrate()
  {
    long sports = GpuEnhancePipeline.suggestBitrateKbps(EnhancementTier.ENHANCE_2160P, 60, 0);
    long news   = GpuEnhancePipeline.suggestBitrateKbps(EnhancementTier.ENHANCE_2160P, 30, 0);
    assertTrue(sports > news, "60fps content must be given more bitrate than 30fps");
  }

  @Test
  public void testBitrateFallsWithTier()
  {
    long uhd = GpuEnhancePipeline.suggestBitrateKbps(EnhancementTier.ENHANCE_2160P, 60, 0);
    long qhd = GpuEnhancePipeline.suggestBitrateKbps(EnhancementTier.ENHANCE_1440P, 60, 0);
    long hd  = GpuEnhancePipeline.suggestBitrateKbps(EnhancementTier.ENHANCE_1080P, 60, 0);
    assertTrue(uhd > qhd);
    assertTrue(qhd > hd);
  }

  @Test
  public void testAdminCapClampsTheLadder()
  {
    Sage.put(PROP_MAX_BITRATE, "18000");
    assertEquals(GpuEnhancePipeline.suggestBitrateKbps(EnhancementTier.ENHANCE_2160P, 60, 0),
        18000L);
  }

  @Test
  public void testDeinterlaceOnlyAnchorsToSourceBitrate()
  {
    // Not rescaling, so the source's own bitrate is the best anchor available.
    assertEquals(GpuEnhancePipeline.suggestBitrateKbps(
        EnhancementTier.DEINTERLACE_ONLY, 30, 15000), 15000L);
  }

  @Test
  public void testNoneTierGetsNoBitrate()
  {
    assertEquals(GpuEnhancePipeline.suggestBitrateKbps(EnhancementTier.NONE, 60, 0), 0L);
  }

  @Test
  public void testMotionClassLadderIsMonotonic()
  {
    long high = GpuEnhancePipeline.suggestBitrateKbps(
        EnhancementTier.ENHANCE_2160P, EnhancementProfile.MotionClass.HIGH, 0);
    long med  = GpuEnhancePipeline.suggestBitrateKbps(
        EnhancementTier.ENHANCE_2160P, EnhancementProfile.MotionClass.MEDIUM, 0);
    long low  = GpuEnhancePipeline.suggestBitrateKbps(
        EnhancementTier.ENHANCE_2160P, EnhancementProfile.MotionClass.LOW, 0);
    assertTrue(high > med, "high motion must exceed medium");
    assertTrue(med > low, "medium motion must exceed low");
  }

  @Test
  public void testNewsTalkGetsFewerBitsThanSportsAtSameTier()
  {
    long sports = GpuEnhancePipeline.suggestBitrateKbps(
        EnhancementTier.ENHANCE_2160P, EnhancementProfile.SPORTS.motionClass(), 0);
    long talk = GpuEnhancePipeline.suggestBitrateKbps(
        EnhancementTier.ENHANCE_2160P, EnhancementProfile.NEWS_TALK.motionClass(), 0);
    assertTrue(sports > talk, "sports must be given more bits than news/talk");
  }

  @Test
  public void testPerGenreBitrateOverride()
  {
    // playback/gpu_enhance/bitrate/<tier>/<motion> lets a deployment tie bitrate
    // to genre without a rebuild.
    Sage.put("playback/gpu_enhance/bitrate/enhance_2160p/high", "55000");
    assertEquals(GpuEnhancePipeline.suggestBitrateKbps(
        EnhancementTier.ENHANCE_2160P, EnhancementProfile.MotionClass.HIGH, 0), 55000L);
    // A different motion cell is unaffected by the high override.
    assertNotEquals(GpuEnhancePipeline.suggestBitrateKbps(
        EnhancementTier.ENHANCE_2160P, EnhancementProfile.MotionClass.LOW, 0), 55000L);
    Sage.remove("playback/gpu_enhance/bitrate/enhance_2160p/high");
  }

  @Test
  public void testOverrideStillClampedByAdminCap()
  {
    Sage.put("playback/gpu_enhance/bitrate/enhance_2160p/high", "55000");
    Sage.put(PROP_MAX_BITRATE, "30000");
    assertEquals(GpuEnhancePipeline.suggestBitrateKbps(
        EnhancementTier.ENHANCE_2160P, EnhancementProfile.MotionClass.HIGH, 0), 30000L);
    Sage.remove("playback/gpu_enhance/bitrate/enhance_2160p/high");
  }

  @Test
  public void testFpsOverloadDelegatesToMotionClass()
  {
    // 60fps => HIGH, 30fps => MEDIUM, matching the motion-class ladder directly.
    assertEquals(
        GpuEnhancePipeline.suggestBitrateKbps(EnhancementTier.ENHANCE_1080P, 60, 0),
        GpuEnhancePipeline.suggestBitrateKbps(
            EnhancementTier.ENHANCE_1080P, EnhancementProfile.MotionClass.HIGH, 0));
    assertEquals(
        GpuEnhancePipeline.suggestBitrateKbps(EnhancementTier.ENHANCE_1080P, 30, 0),
        GpuEnhancePipeline.suggestBitrateKbps(
            EnhancementTier.ENHANCE_1080P, EnhancementProfile.MotionClass.MEDIUM, 0));
  }

  // ---- Argv rewrite -------------------------------------------------------

  /** The exact mpeg2tsremux playback command, as assembled by FFMPEGTranscoder. */
  private static java.util.List<String> mpeg2tsremuxArgv()
  {
    return new java.util.ArrayList<String>(java.util.Arrays.asList(
        "ffmpeg", "-v", "info", "-y", "-hwaccel", "cuda", "-threads", "2",
        "-i", "/media/rec.mpg",
        "-f", "mpegts", "-muxdelay", "0", "-muxpreload", "0",
        "-c:v", "copy", "-c:a", "copy", "-copyts", "-"));
  }

  @Test
  public void testRewriteInsertsHwaccelOutputFormatBeforeInput()
  {
    java.util.List<String> argv = mpeg2tsremuxArgv();
    assertTrue(GpuEnhancePipeline.rewriteArgv(argv, plan(EnhancementTier.ENHANCE_2160P, false, "scale_cuda"), 60));
    int of = argv.indexOf("-hwaccel_output_format");
    int i = argv.indexOf("-i");
    assertTrue(of >= 0 && of < i, "output format must be a global, before -i: " + argv);
    assertEquals(argv.get(of + 1), "cuda");
    // The pre-existing -hwaccel cuda must not be duplicated.
    int first = argv.indexOf("-hwaccel");
    int last = argv.lastIndexOf("-hwaccel");
    assertEquals(first, last, "-hwaccel must not be duplicated: " + argv);
  }

  @Test
  public void testRewriteReplacesCopyWithEncoderAndFilter()
  {
    java.util.List<String> argv = mpeg2tsremuxArgv();
    assertTrue(GpuEnhancePipeline.rewriteArgv(argv, plan(EnhancementTier.ENHANCE_2160P, false, "scale_cuda"), 60));
    // No stray "-c:v copy" remains; the video codec is now hevc_nvenc.
    int vc = argv.indexOf("-c:v");
    assertEquals(argv.get(vc + 1), "hevc_nvenc", argv.toString());
    assertFalse(argv.contains("copy") && argv.indexOf("copy") < argv.indexOf("-c:a"),
        "no video copy should remain: " + argv);
    // The scale filter is present and after -i.
    int vf = argv.indexOf("-vf");
    assertTrue(vf > argv.indexOf("-i"), "filter must be in the output section: " + argv);
    assertEquals(argv.get(vf + 1), "scale_cuda=3840:2160");
  }

  /** Audio copy must be left exactly as it was. */
  @Test
  public void testRewriteNeverTouchesAudio()
  {
    java.util.List<String> argv = mpeg2tsremuxArgv();
    GpuEnhancePipeline.rewriteArgv(argv, plan(EnhancementTier.ENHANCE_1080P, false, "scale_cuda"), 60);
    int ca = argv.indexOf("-c:a");
    assertTrue(ca >= 0, "audio codec flag must survive: " + argv);
    assertEquals(argv.get(ca + 1), "copy");
  }

  @Test
  public void testRewriteIsNoOpForInactivePlan()
  {
    java.util.List<String> argv = mpeg2tsremuxArgv();
    java.util.List<String> before = new java.util.ArrayList<String>(argv);
    assertFalse(GpuEnhancePipeline.rewriteArgv(argv, EnhancementPlan.NONE, 60));
    assertEquals(argv, before, "inactive plan must leave the command byte-identical");
  }

  /** A command whose video is already being encoded (not copy) must be left alone. */
  @Test
  public void testRewriteRefusesNonCopyVideo()
  {
    java.util.List<String> argv = new java.util.ArrayList<String>(java.util.Arrays.asList(
        "ffmpeg", "-i", "/media/rec.mpg", "-c:v", "libx264", "-c:a", "copy", "-"));
    java.util.List<String> before = new java.util.ArrayList<String>(argv);
    assertFalse(GpuEnhancePipeline.rewriteArgv(argv, plan(EnhancementTier.ENHANCE_2160P, false, "scale_cuda"), 60));
    assertEquals(argv, before);
  }

  @Test
  public void testRewriteRefusesArgvWithNoInput()
  {
    java.util.List<String> argv = new java.util.ArrayList<String>(java.util.Arrays.asList(
        "ffmpeg", "-c:v", "copy", "-c:a", "copy", "-"));
    assertFalse(GpuEnhancePipeline.rewriteArgv(argv, plan(EnhancementTier.ENHANCE_2160P, false, "scale_cuda"), 60));
  }

  /** The encoder args re-supply -tag:v; a pre-existing one must not be left to conflict. */
  @Test
  public void testRewriteStripsPreexistingTagV()
  {
    java.util.List<String> argv = new java.util.ArrayList<String>(java.util.Arrays.asList(
        "ffmpeg", "-hwaccel", "cuda", "-i", "/media/rec.mpg",
        "-f", "mp4", "-c:v", "copy", "-tag:v", "hvc1", "-c:a", "copy", "-"));
    assertTrue(GpuEnhancePipeline.rewriteArgv(argv, plan(EnhancementTier.ENHANCE_1440P, false, "scale_cuda"), 60));
    // Exactly one -tag:v (the encoder's), not two.
    int count = 0;
    for (String s : argv) if ("-tag:v".equals(s)) count++;
    assertEquals(count, 1, "must not leave a duplicate -tag:v: " + argv);
  }

  /** The exact browserhd (PWA/MSE) re-encode command shape: H.264 nvenc, CPU
   *  format filter, no explicit bitrate, AAC stereo audio. */
  private static java.util.List<String> browserhdArgv()
  {
    return new java.util.ArrayList<String>(java.util.Arrays.asList(
        "ffmpeg", "-v", "info", "-y", "-hwaccel", "cuda", "-threads", "2",
        "-i", "/media/rec.mpg",
        "-f", "mp4", "-movflags", "+frag_keyframe+empty_moov+default_base_moof",
        "-vf", "format=yuv420p",
        "-c:v", "h264_nvenc", "-preset", "p4", "-profile:v", "high",
        "-g", "60", "-forced-idr", "1",
        "-acodec", "aac", "-ac", "2", "-ar", "48000", "-b:a", "128k", "-"));
  }

  /** The re-encode path keeps the browser's H.264 codec (MSE can't decode HEVC)
   *  and swaps the CPU format filter for the CUDA scale chain. */
  @Test
  public void testReencodeKeepsH264AndSwapsFilter()
  {
    java.util.List<String> argv = browserhdArgv();
    assertTrue(GpuEnhancePipeline.rewriteReencodeArgv(
        argv, plan(EnhancementTier.ENHANCE_2160P, false, "scale_cuda"), 60));
    int vc = argv.indexOf("-c:v");
    assertEquals(argv.get(vc + 1), "h264_nvenc", "must NOT switch to HEVC: " + argv);
    assertFalse(argv.contains("hevc_nvenc"), "no HEVC on the browser path: " + argv);
    assertFalse(argv.contains("hvc1"), "no hvc1 tag on the browser path: " + argv);
    int vf = argv.indexOf("-vf");
    assertTrue(vf > argv.indexOf("-i"), "filter stays in the output section: " + argv);
    assertEquals(argv.get(vf + 1), "scale_cuda=3840:2160", argv.toString());
    assertFalse(argv.contains("format=yuv420p"), "CPU format filter must be replaced: " + argv);
  }

  /** Decode is made GPU-resident: -hwaccel_output_format cuda before -i, no dup -hwaccel. */
  @Test
  public void testReencodeInsertsHwaccelOutputFormat()
  {
    java.util.List<String> argv = browserhdArgv();
    assertTrue(GpuEnhancePipeline.rewriteReencodeArgv(
        argv, plan(EnhancementTier.ENHANCE_1080P, false, "scale_cuda"), 60));
    int of = argv.indexOf("-hwaccel_output_format");
    int i = argv.indexOf("-i");
    assertTrue(of >= 0 && of < i, "output format must precede -i: " + argv);
    assertEquals(argv.indexOf("-hwaccel"), argv.lastIndexOf("-hwaccel"),
        "-hwaccel must not be duplicated: " + argv);
  }

  /** The browser command carries no explicit bitrate; enhancement adds VBR control. */
  @Test
  public void testReencodeAddsBitrateControl()
  {
    java.util.List<String> argv = browserhdArgv();
    assertTrue(GpuEnhancePipeline.rewriteReencodeArgv(
        argv, plan(EnhancementTier.ENHANCE_2160P, false, "scale_cuda"), 60));
    int bv = argv.indexOf("-b:v");
    assertTrue(bv > argv.indexOf("-i"), "bitrate must be set on output: " + argv);
    assertEquals(argv.get(bv + 1), "25000k", argv.toString());
    assertTrue(argv.contains("-maxrate") && argv.contains("-bufsize"), argv.toString());
  }

  /** Audio must be left exactly as negotiated. */
  @Test
  public void testReencodeNeverTouchesAudio()
  {
    java.util.List<String> argv = browserhdArgv();
    GpuEnhancePipeline.rewriteReencodeArgv(
        argv, plan(EnhancementTier.ENHANCE_1080P, false, "scale_cuda"), 60);
    int ca = argv.indexOf("-acodec");
    assertEquals(argv.get(ca + 1), "aac", argv.toString());
    int ba = argv.indexOf("-b:a");
    assertEquals(argv.get(ba + 1), "128k", argv.toString());
  }

  /** Only NVENC output can consume CUDA frames; a software/other encoder is refused. */
  @Test
  public void testReencodeRefusesNonNvencEncoder()
  {
    java.util.List<String> argv = new java.util.ArrayList<String>(java.util.Arrays.asList(
        "ffmpeg", "-i", "/media/rec.mpg", "-vf", "format=yuv420p",
        "-c:v", "libx264", "-acodec", "aac", "-"));
    java.util.List<String> before = new java.util.ArrayList<String>(argv);
    assertFalse(GpuEnhancePipeline.rewriteReencodeArgv(
        argv, plan(EnhancementTier.ENHANCE_2160P, false, "scale_cuda"), 60));
    assertEquals(argv, before, "non-nvenc encoder must be left byte-identical");
  }

  /** An inactive plan is a no-op on the re-encode path too. */
  @Test
  public void testReencodeIsNoOpForInactivePlan()
  {
    java.util.List<String> argv = browserhdArgv();
    java.util.List<String> before = new java.util.ArrayList<String>(argv);
    assertFalse(GpuEnhancePipeline.rewriteReencodeArgv(argv, EnhancementPlan.NONE, 60));
    assertEquals(argv, before, "inactive plan must leave the command byte-identical");
  }
}
