/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package sage.enhance;

import java.util.ArrayList;
import java.util.List;

import sage.HwEncoder;
import sage.Sage;

/**
 * Builds the ffmpeg tokens for a full-GPU enhancement pipeline.
 *
 * <p>One builder, used by both live delivery paths (push and pull-xcode) and by
 * the capacity probe, so there is exactly one place where the CUDA command shape
 * is decided. The offline {@code presets/transcoder/upscale_2160.properties}
 * preset already proves this shape works end to end; this class is the live
 * equivalent of it.
 *
 * <p>The output is three groups, because ffmpeg cares deeply about where each
 * lands relative to {@code -i}:
 * <ul>
 *   <li>{@link #buildGlobalArgs global} — must precede the input, and is what
 *       makes the pipeline GPU-<i>resident</i>. The existing live path emits
 *       {@code -hwaccel cuda} with no {@code -hwaccel_output_format}, so decoded
 *       frames round-trip through system RAM; adding the output format is what
 *       keeps them in VRAM for the whole chain.</li>
 *   <li>{@link #buildFilterChain filter} — the deinterlace and scale stages.</li>
 *   <li>{@link #buildEncoderArgs encoder} — HEVC NVENC and its rate control.</li>
 * </ul>
 *
 * <p>Correctness notes that are easy to get wrong:
 * <ul>
 *   <li>The deinterlacer is invoked as {@code yadif_cuda=0:-1:1} — mode 0 (one
 *       frame per frame), auto parity, and <b>deint=1 meaning "interlaced frames
 *       only"</b>. That last flag is what lets 720p60 pass through untouched and
 *       stops mixed-cadence channels from being mangled.</li>
 *   <li>The scaler is an abstraction on purpose. {@code scale_cuda} is preferred
 *       when its {@code interp_algo} (Lanczos) option is present — it is the
 *       actively-maintained native CUDA kernel — falling back to {@code scale_npp}
 *       (which needs {@code --enable-libnpp}, deprecated by NVIDIA on CUDA past
 *       12.8) only when {@code scale_cuda} is bilinear-only, so quality is never
 *       traded for the newer filter. Never assume a specific one is present.</li>
 *   <li>Audio is deliberately <b>not</b> handled here. A blind {@code -c:a copy}
 *       would break Tizen (which wants AC3/EAC3) and MSE (which wants AAC); audio
 *       stays with the existing per-surface target-codec machinery.</li>
 * </ul>
 */
public final class GpuEnhancePipeline
{
  private static final String PROP_NVENC_PRESET = "playback/gpu_enhance/nvenc_preset";
  private static final String PROP_NVENC_TUNE   = "playback/gpu_enhance/nvenc_tune";
  private static final String PROP_PREFER_BWDIF = "playback/gpu_enhance/prefer_bwdif";
  private static final String PROP_GPU_INDEX    = "playback/gpu_enhance/gpu_index";
  private static final String PROP_MAX_BITRATE  = "playback/gpu_enhance/max_bitrate_kbps";
  // Quality knobs. AQ is on by default: it redistributes bits within a frame
  // (grass, crowds, jersey numbers on sports) at no extra latency. Lookahead and
  // multipass default OFF because both add encode latency, which we keep out of
  // the live/trickplay path unless explicitly opted in.
  private static final String PROP_SPATIAL_AQ   = "playback/gpu_enhance/spatial_aq";
  private static final String PROP_TEMPORAL_AQ  = "playback/gpu_enhance/temporal_aq";
  private static final String PROP_AQ_STRENGTH  = "playback/gpu_enhance/aq_strength";
  private static final String PROP_RC_LOOKAHEAD = "playback/gpu_enhance/rc_lookahead";
  private static final String PROP_MULTIPASS    = "playback/gpu_enhance/multipass";

  private static final String DEFAULT_PRESET = "p4";
  private static final String DEFAULT_TUNE   = "hq";

  private GpuEnhancePipeline() { }

  /**
   * Build a plan for a granted tier, resolving the concrete filter names that
   * this ffmpeg binary actually has. Returns {@link EnhancementPlan#NONE} when
   * the tier can't be built, which is the fail-closed path.
   *
   * @param sourceInterlaced whether the source is flagged interlaced; drives
   *        whether a deinterlace stage is added at all
   * @param sourceHeight used to enforce the sub-720-line source floor
   */
  public static EnhancementPlan buildPlan(EnhancementTier tier, boolean sourceInterlaced,
                                          int sourceHeight, long bitrateKbps)
  {
    if (tier == null || !tier.isActive())
      return EnhancementPlan.NONE;

    // Source floor. Upscaling tiers require >= 720 LINES; the check is on height,
    // so 720x480 SD is correctly excluded.
    if (tier.isUpscaling() && sourceHeight < EnhancementTier.SOURCE_HEIGHT_FLOOR)
    {
      return new EnhancementPlan(EnhancementTier.NONE, false, null, null, 0, 0, 0, 0,
          "source height " + sourceHeight + " below floor "
          + EnhancementTier.SOURCE_HEIGHT_FLOOR);
    }

    boolean preferBwdif = Sage.getBoolean(PROP_PREFER_BWDIF, false);
    String deint = sourceInterlaced ? HwEncoder.cudaDeinterlacer(preferBwdif) : null;
    if (sourceInterlaced && deint == null)
      return new EnhancementPlan(EnhancementTier.NONE, false, null, null, 0, 0, 0, 0,
          "no CUDA deinterlacer available");

    String scaler = null;
    if (tier.isUpscaling())
    {
      scaler = HwEncoder.cudaScaler();
      if (scaler == null)
        return new EnhancementPlan(EnhancementTier.NONE, false, null, null, 0, 0, 0, 0,
            "no CUDA scaler available");
    }

    // A deinterlace-only tier with a progressive source is a no-op; don't burn a
    // GPU session to do nothing.
    if (!tier.isUpscaling() && deint == null)
      return new EnhancementPlan(EnhancementTier.NONE, false, null, null, 0, 0, 0, 0,
          "progressive source, nothing to deinterlace");

    long cap = Sage.getLong(PROP_MAX_BITRATE, 0L);
    long rate = bitrateKbps;
    if (cap > 0 && rate > cap) rate = cap;

    // Provider seam: resolve and capture the scale stage now, at plan time, so a
    // later registry change cannot alter this session's rendered chain. The
    // built-in provider reproduces the current scale fragment exactly and takes
    // no specialized permit, keeping behavior byte-identical when no external
    // provider is selected. A deinterlace-only tier still runs through selection
    // but contributes no scale fragment.
    sage.enhance.spi.ScaleExecutionPlan scaleExec = null;
    sage.enhance.spi.ScaleGovernor.Lease scaleLease = null;
    {
      sage.enhance.spi.ScaleRequest req = new sage.enhance.spi.ScaleRequest(
          tier, tier.getTargetWidth(), tier.getTargetHeight(), sourceHeight,
          sourceInterlaced, scaler, sage.enhance.spi.ScaleRequest.Purpose.LIVE);
      sage.enhance.spi.ScaleSelection sel =
          sage.enhance.spi.ScaleProviderRegistry.getInstance().select(req);
      if (sel != null)
      {
        scaleExec = sel.getExecutionPlan();
        scaleLease = sel.getLease();
      }
    }

    return new EnhancementPlan(tier, deint != null, deint, scaler,
        tier.getTargetWidth(), tier.getTargetHeight(), rate, cap, "built",
        scaleExec, scaleLease);
  }

  /**
   * Global args that must appear <b>before</b> {@code -i}.
   *
   * <p>{@code -hwaccel_output_format cuda} is the load-bearing token: without it
   * ffmpeg copies every decoded frame back to system memory, which defeats the
   * whole point and costs more than it saves.
   */
  public static List<String> buildGlobalArgs(EnhancementPlan plan)
  {
    List<String> out = new ArrayList<String>();
    if (plan == null || !plan.isActive()) return out;
    out.add("-hwaccel"); out.add("cuda");
    out.add("-hwaccel_output_format"); out.add("cuda");
    int idx = Sage.getInt(PROP_GPU_INDEX, -1);
    if (idx >= 0) { out.add("-hwaccel_device"); out.add(String.valueOf(idx)); }
    return out;
  }

  /**
   * The {@code -vf} chain, or null when there is nothing to filter.
   * Frames are already in VRAM courtesy of the global args, so every stage here
   * is a CUDA filter and no upload/download appears in the chain.
   */
  public static String buildFilterChain(EnhancementPlan plan)
  {
    if (plan == null || !plan.isActive()) return null;
    StringBuilder sb = new StringBuilder();
    if (plan.isDeinterlace() && plan.getDeinterlacer() != null)
    {
      // mode=0 (frame per frame), parity=-1 (auto), deint=1 (interlaced frames only).
      sb.append(plan.getDeinterlacer()).append("=0:-1:1");
    }
    if (plan.isScaling())
    {
      if (sb.length() > 0) sb.append(',');
      // Prefer the provider-captured scale stage; fall back to the legacy render
      // for directly-constructed plans (calibration) so their tokens are
      // byte-identical to pre-seam behavior.
      sage.enhance.spi.ScaleExecutionPlan exec = plan.getScaleExec();
      if (exec != null && exec.rendersFilterFragment())
      {
        sb.append(exec.getFfmpegFilter());
      }
      else
      {
        sb.append(plan.getScaler()).append('=')
          .append(plan.getTargetWidth()).append(':').append(plan.getTargetHeight());
        if (HwEncoder.scalerSupportsLanczos(plan.getScaler())) sb.append(":interp_algo=lanczos");
      }
    }
    return (sb.length() == 0) ? null : sb.toString();
  }

  /**
   * Encoder args for the enhanced output.
   *
   * <p>{@code -bf 0} and a short GOP are not arbitrary: they match what the
   * existing push and HLS branches already do, keeping live latency and trickplay
   * behavior consistent rather than making enhanced streams behave differently
   * from every other live stream. Adaptive quantization (spatial + temporal) is
   * added by default because it improves quality-per-bit on high-detail,
   * high-motion content (sports) at no latency cost; lookahead and multipass are
   * opt-in only, since both add encode latency to the live/trickplay path.
   */
  public static List<String> buildEncoderArgs(EnhancementPlan plan, int fps)
  {
    List<String> out = new ArrayList<String>();
    if (plan == null || !plan.isActive()) return out;

    String enc = HwEncoder.encoderName(HwEncoder.Kind.NVENC, "hevc");
    if (enc == null) return out;

    if (fps <= 0) fps = 30;
    long rate = plan.getBitrateKbps();
    if (rate <= 0) rate = 20000L;
    long maxrate = (plan.getBitrateCapKbps() > 0)
        ? Math.max(rate, plan.getBitrateCapKbps()) : (rate * 3L / 2L);

    out.add("-c:v"); out.add(enc);
    out.add("-preset"); out.add(Sage.get(PROP_NVENC_PRESET, DEFAULT_PRESET));
    out.add("-tune"); out.add(Sage.get(PROP_NVENC_TUNE, DEFAULT_TUNE));
    out.add("-rc"); out.add("vbr");
    out.add("-b:v"); out.add(rate + "k");
    out.add("-maxrate"); out.add(maxrate + "k");
    out.add("-bufsize"); out.add((maxrate * 2L) + "k");
    out.add("-bf"); out.add("0");
    out.add("-g"); out.add(String.valueOf(fps * 2));

    // Adaptive quantization: default on. Redistributes bits toward high-detail
    // regions (grass, crowds) without adding encode latency, so it stays in the
    // live/trickplay path safely.
    if (Sage.getBoolean(PROP_SPATIAL_AQ, true))
    {
      out.add("-spatial_aq"); out.add("1");
      int aqStrength = Sage.getInt(PROP_AQ_STRENGTH, 0);
      if (aqStrength >= 1 && aqStrength <= 15)
      {
        out.add("-aq-strength"); out.add(String.valueOf(aqStrength));
      }
    }
    if (Sage.getBoolean(PROP_TEMPORAL_AQ, true))
    {
      out.add("-temporal_aq"); out.add("1");
    }
    // Lookahead and multipass both add latency, so they are opt-in only. When a
    // deployment has spare NVENC headroom and no trickplay concern, they buy
    // extra quality-per-bit on high-motion content.
    int lookahead = Sage.getInt(PROP_RC_LOOKAHEAD, 0);
    if (lookahead > 0)
    {
      out.add("-rc-lookahead"); out.add(String.valueOf(lookahead));
    }
    String multipass = Sage.get(PROP_MULTIPASS, "").trim();
    if (multipass.length() > 0 && !"0".equals(multipass) && !"none".equalsIgnoreCase(multipass)
        && !"disabled".equalsIgnoreCase(multipass))
    {
      out.add("-multipass"); out.add(multipass);
    }

    out.add("-fps_mode"); out.add("passthrough");
    out.add("-tag:v"); out.add("hvc1");
    return out;
  }

  /**
   * Bitrate ladder: what a tier should target for this content.
   *
   * <p>Frame rate is the strongest available proxy for motion on live OTA — 60fps
   * is overwhelmingly sports, 24/30fps is drama and news — so it drives the
   * choice. The result is still clamped by the admin cap and by measured client
   * throughput before it reaches the encoder.
   *
   * <p>This frame-rate overload is retained for the advisor's bandwidth-envelope
   * check; the genre-aware {@link #suggestBitrateKbps(EnhancementTier,
   * EnhancementProfile.MotionClass, long)} overload is what the encoder uses.
   */
  public static long suggestBitrateKbps(EnhancementTier tier, int fps, long sourceBitrateKbps)
  {
    return suggestBitrateKbps(tier,
        (fps >= 50) ? EnhancementProfile.MotionClass.HIGH
                    : EnhancementProfile.MotionClass.MEDIUM,
        sourceBitrateKbps);
  }

  /**
   * Genre-aware bitrate ladder. The {@link EnhancementProfile.MotionClass} lets
   * sports/nature claim more bits while news/talk claim fewer, at the same
   * perceived quality. Each (tier, motion) cell is overridable with the property
   * {@code playback/gpu_enhance/bitrate/<tier>/<motion>} so a deployment can tie
   * bitrate directly to genre without a rebuild. Clamped by the admin cap.
   */
  public static long suggestBitrateKbps(EnhancementTier tier,
      EnhancementProfile.MotionClass motion, long sourceBitrateKbps)
  {
    if (motion == null) motion = EnhancementProfile.MotionClass.MEDIUM;
    long base;
    switch (tier)
    {
      case ENHANCE_2160P: base = pick(motion, 40000L, 28000L, 20000L); break;
      case ENHANCE_1440P: base = pick(motion, 24000L, 17000L, 13000L); break;
      case ENHANCE_1080P: base = pick(motion, 14000L, 10000L,  7000L); break;
      case DEINTERLACE_ONLY:
        // Not rescaling, so the source's own bitrate is the best anchor we have.
        base = (sourceBitrateKbps > 0) ? Math.max(6000L, sourceBitrateKbps) : 8000L;
        break;
      default: return 0L;
    }
    long override = Sage.getLong(bitrateKey(tier, motion), 0L);
    if (override > 0) base = override;
    long cap = Sage.getLong(PROP_MAX_BITRATE, 0L);
    if (cap > 0 && base > cap) base = cap;
    return base;
  }

  private static long pick(EnhancementProfile.MotionClass m, long hi, long med, long lo)
  {
    switch (m)
    {
      case HIGH: return hi;
      case LOW:  return lo;
      default:   return med;
    }
  }

  private static String bitrateKey(EnhancementTier tier, EnhancementProfile.MotionClass motion)
  {
    return "playback/gpu_enhance/bitrate/"
        + tier.name().toLowerCase(java.util.Locale.ROOT) + "/"
        + motion.name().toLowerCase(java.util.Locale.ROOT);
  }

  /**
   * Rewrite an already-assembled ffmpeg argv <b>in place</b> to apply an
   * enhancement plan to the video stream of a copy-family remux command.
   *
   * <p>This is the single place the live command shape is edited, deliberately
   * kept as a pure list transform so it can be unit-tested without a running
   * transcoder. It performs exactly three edits and touches nothing else:
   * <ol>
   *   <li>Makes the decode GPU-resident: ensures {@code -hwaccel cuda} and
   *       {@code -hwaccel_output_format cuda} appear <b>before</b> {@code -i}, so
   *       the CUDA scaler/deinterlacer receive VRAM frames.</li>
   *   <li>Inserts the {@code -vf} deinterlace/scale chain in the output section.</li>
   *   <li>Replaces the video codec {@code -c:v copy} (or {@code -vcodec copy})
   *       with the NVENC HEVC encoder args, stripping any pre-existing
   *       {@code -tag:v} / {@code -fps_mode} the encoder args re-supply.</li>
   * </ol>
   *
   * <p>Audio is never touched: no {@code -c:a}, {@code -acodec}, {@code -b:a} or
   * audio filter is inspected or moved. If the argv is not the expected
   * copy-family shape (no {@code -i}, or the video codec is not {@code copy}),
   * the method makes no change and returns false — the fail-closed direction, so
   * a surprising command is left byte-identical rather than half-rewritten.
   *
   * @return true if the argv was rewritten, false if it was left untouched.
   */
  public static boolean rewriteArgv(java.util.List<String> argv, EnhancementPlan plan, int fps)
  {
    if (argv == null || plan == null || !plan.isActive()) return false;

    int iIdx = argv.indexOf("-i");
    if (iIdx < 0) return false;

    // Only rewrite a genuine copy-family video stream. Find the video codec
    // token in the OUTPUT section (after -i) and require it to be "copy".
    int vci = indexOfVideoCodec(argv, iIdx + 1);
    if (vci < 0 || vci + 1 >= argv.size()) return false;
    if (!"copy".equalsIgnoreCase(argv.get(vci + 1))) return false;

    // (1) GPU-resident decode: ensure the two global tokens precede -i.
    ensureGpuGlobals(argv, iIdx);

    // Indices may have shifted; re-anchor on -i and the video codec token.
    iIdx = argv.indexOf("-i");
    // Strip encoder-supplied duplicates from the output section only.
    stripPairAfter(argv, iIdx, "-tag:v");
    stripPairAfter(argv, iIdx, "-fps_mode");
    iIdx = argv.indexOf("-i");
    vci = indexOfVideoCodec(argv, iIdx + 1);
    if (vci < 0 || vci + 1 >= argv.size() || !"copy".equalsIgnoreCase(argv.get(vci + 1)))
      return false;

    // (3) Remove "-c:v","copy".
    argv.remove(vci);
    argv.remove(vci);

    // (2)+(3) Build the replacement: optional -vf chain, then encoder args.
    java.util.List<String> repl = new java.util.ArrayList<String>();
    String vf = buildFilterChain(plan);
    if (vf != null) { repl.add("-vf"); repl.add(vf); }
    repl.addAll(buildEncoderArgs(plan, fps));
    if (repl.isEmpty()) return false; // no encoder available: leave copy in place
    argv.addAll(vci, repl);
    return true;
  }

  /**
   * Rewrite an already-assembled <b>re-encode</b> ffmpeg argv <b>in place</b> to
   * apply an enhancement plan while <b>keeping the base mode's negotiated video
   * codec</b>. This is the browser/PWA counterpart to {@link #rewriteArgv}: when
   * the source cannot be stream-copied to fMP4 (e.g. MPEG-2 DVR content), the
   * server already re-encodes to H.264 for the browser ({@code browserhd}), so
   * the copy-family rewrite does not apply. Here we enhance that H.264 output in
   * place rather than switching it to the HEVC {@link #buildEncoderArgs} emits —
   * browser MSE generally cannot decode HEVC.
   *
   * <p>The CUDA scaler/deinterlacer only exist behind {@code -hwaccel cuda} feeding
   * an {@code *_nvenc} encoder, so this requires an NVENC video codec in the output
   * section. Any other encoder (libx264/qsv/amf/vaapi) or a missing filter chain
   * leaves the argv byte-identical and returns false — the fail-closed direction.
   *
   * <p>Exactly three edits, touching no audio token:
   * <ol>
   *   <li>Makes decode GPU-resident ({@code -hwaccel cuda -hwaccel_output_format
   *       cuda} before {@code -i}).</li>
   *   <li>Replaces the base CPU pixel-format filter ({@code -vf format=yuv420p})
   *       with the CUDA deinterlace/scale chain from {@link #buildFilterChain} —
   *       which routes through the {@code ScaleProvider} SPI, so a registered VSR
   *       provider that renders a filter fragment is used here too. A CPU
   *       {@code format=} filter is incompatible with {@code -hwaccel_output_format
   *       cuda}, so replacing it is mandatory, not cosmetic.</li>
   *   <li>Adds VBR rate control at the enhanced bitrate after the existing codec,
   *       without disturbing {@code -c:v}/{@code -preset}/{@code -profile}/{@code -g}/
   *       {@code -forced-idr} or any audio argument.</li>
   * </ol>
   *
   * @return true if the argv was rewritten, false if it was left untouched.
   */
  public static boolean rewriteReencodeArgv(java.util.List<String> argv, EnhancementPlan plan, int fps)
  {
    if (argv == null || plan == null || !plan.isActive()) return false;

    int iIdx = argv.indexOf("-i");
    if (iIdx < 0) return false;

    // Require an NVENC video codec in the output section: the CUDA scaler feeds
    // nvenc directly; any other encoder can't consume VRAM frames.
    int vci = indexOfVideoCodec(argv, iIdx + 1);
    if (vci < 0 || vci + 1 >= argv.size()) return false;
    String enc = argv.get(vci + 1);
    if (enc == null || enc.toLowerCase().indexOf("nvenc") < 0) return false;

    // Nothing to scale/deinterlace => leave the stream exactly as negotiated.
    String enhanceVf = buildFilterChain(plan);
    if (enhanceVf == null) return false;

    // (1) GPU-resident decode.
    ensureGpuGlobals(argv, iIdx);

    // (2) Replace the base CPU -vf (or insert one if absent). Re-anchor first.
    iIdx = argv.indexOf("-i");
    int vfIdx = indexOfAfter(argv, iIdx, "-vf");
    if (vfIdx >= 0 && vfIdx + 1 < argv.size())
    {
      argv.set(vfIdx + 1, enhanceVf);
    }
    else
    {
      int ins = Math.min(iIdx + 2, argv.size()); // after "-i <file>"
      argv.add(ins, enhanceVf);
      argv.add(ins, "-vf");
    }

    // (3) VBR rate control after the codec token, only for flags not already set.
    iIdx = argv.indexOf("-i");
    vci = indexOfVideoCodec(argv, iIdx + 1);
    java.util.List<String> rc = buildReencodeRateControlArgs(plan, argv, iIdx);
    if (!rc.isEmpty() && vci >= 0) argv.addAll(vci + 2, rc);
    return true;
  }

  /**
   * Rate-control tokens for the in-place re-encode path: VBR at the plan's
   * bitrate plus adaptive quantization, mirroring {@link #buildEncoderArgs} but
   * omitting anything the base {@code browserhd} command already supplies
   * ({@code -c:v}/{@code -preset}/{@code -g}). Each flag is staged only if absent
   * from the output section, so a variant that already sets a rate is not
   * duplicated.
   */
  private static java.util.List<String> buildReencodeRateControlArgs(
      EnhancementPlan plan, java.util.List<String> argv, int iIdx)
  {
    java.util.List<String> out = new java.util.ArrayList<String>();
    long rate = plan.getBitrateKbps();
    if (rate <= 0) rate = 20000L;
    long maxrate = (plan.getBitrateCapKbps() > 0)
        ? Math.max(rate, plan.getBitrateCapKbps()) : (rate * 3L / 2L);
    addFlagIfAbsent(out, argv, iIdx, "-rc", "vbr");
    addFlagIfAbsent(out, argv, iIdx, "-b:v", rate + "k");
    addFlagIfAbsent(out, argv, iIdx, "-maxrate", maxrate + "k");
    addFlagIfAbsent(out, argv, iIdx, "-bufsize", (maxrate * 2L) + "k");
    if (Sage.getBoolean(PROP_SPATIAL_AQ, true))
      addFlagIfAbsent(out, argv, iIdx, "-spatial_aq", "1");
    if (Sage.getBoolean(PROP_TEMPORAL_AQ, true))
      addFlagIfAbsent(out, argv, iIdx, "-temporal_aq", "1");
    return out;
  }

  /** Stage {@code flag value} into {@code out} only if {@code flag} is absent after {@code iIdx}. */
  private static void addFlagIfAbsent(java.util.List<String> out, java.util.List<String> argv,
      int iIdx, String flag, String value)
  {
    if (indexOfAfter(argv, iIdx, flag) < 0) { out.add(flag); out.add(value); }
  }

  /** First index of {@code flag} strictly after {@code afterIdx}. */
  private static int indexOfAfter(java.util.List<String> argv, int afterIdx, String flag)
  {
    for (int i = Math.max(0, afterIdx + 1); i < argv.size(); i++)
      if (flag.equals(argv.get(i))) return i;
    return -1;
  }

  /** Ensure {@code -hwaccel cuda -hwaccel_output_format cuda} appear before {@code iIdx}. */
  private static void ensureGpuGlobals(java.util.List<String> argv, int iIdx)
  {
    int hw = indexOfBefore(argv, iIdx, "-hwaccel");
    if (hw >= 0)
    {
      // -hwaccel already present (the decode-only path sets it). Only add the
      // output format if it is missing, so we don't duplicate -hwaccel.
      if (indexOfBefore(argv, iIdx, "-hwaccel_output_format") < 0)
      {
        int insAt = Math.min(hw + 2, iIdx);
        argv.add(insAt, "cuda");
        argv.add(insAt, "-hwaccel_output_format");
      }
      return;
    }
    java.util.List<String> globals = new java.util.ArrayList<String>();
    globals.add("-hwaccel"); globals.add("cuda");
    globals.add("-hwaccel_output_format"); globals.add("cuda");
    int idx = Sage.getInt(PROP_GPU_INDEX, -1);
    if (idx >= 0) { globals.add("-hwaccel_device"); globals.add(String.valueOf(idx)); }
    argv.addAll(iIdx, globals);
  }

  /** First index of {@code -c:v} or {@code -vcodec} at or after {@code from}. */
  private static int indexOfVideoCodec(java.util.List<String> argv, int from)
  {
    for (int i = Math.max(0, from); i < argv.size(); i++)
    {
      String s = argv.get(i);
      if ("-c:v".equals(s) || "-vcodec".equals(s)) return i;
    }
    return -1;
  }

  /** First index of {@code flag} strictly before {@code limit}. */
  private static int indexOfBefore(java.util.List<String> argv, int limit, String flag)
  {
    int lim = Math.min(limit, argv.size());
    for (int i = 0; i < lim; i++)
      if (flag.equals(argv.get(i))) return i;
    return -1;
  }

  /** Remove the first {@code flag <value>} pair occurring after {@code afterIdx}. */
  private static void stripPairAfter(java.util.List<String> argv, int afterIdx, String flag)
  {
    for (int i = Math.max(0, afterIdx + 1); i < argv.size(); i++)
    {
      if (flag.equals(argv.get(i)))
      {
        argv.remove(i);
        if (i < argv.size()) argv.remove(i); // its value
        return;
      }
    }
  }
}
