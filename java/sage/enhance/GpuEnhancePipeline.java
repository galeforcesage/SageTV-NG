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
    return buildPlan(tier, sourceInterlaced, 0, sourceHeight, bitrateKbps);
  }

  public static EnhancementPlan buildPlan(EnhancementTier tier, boolean sourceInterlaced,
                                          int sourceWidth, int sourceHeight, long bitrateKbps)
  {
    return buildPlan(tier, sourceInterlaced, sourceWidth, sourceHeight, bitrateKbps, true);
  }

  /**
   * Build a plan while enforcing whether specialized providers are authorized.
   * Passing false skips their probe, permit acquisition, plan, and worker startup.
   */
  public static EnhancementPlan buildPlan(EnhancementTier tier, boolean sourceInterlaced,
                                          int sourceWidth, int sourceHeight, long bitrateKbps,
                                          boolean allowSpecializedProviders)
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
    String scaleProviderId = null;
    {
      sage.enhance.spi.ScaleRequest req = new sage.enhance.spi.ScaleRequest(
          tier, tier.getTargetWidth(), tier.getTargetHeight(), sourceWidth, sourceHeight,
          sourceInterlaced, scaler, sage.enhance.spi.ScaleRequest.Purpose.LIVE);
      sage.enhance.spi.ScaleSelection sel =
          sage.enhance.spi.ScaleProviderRegistry.getInstance().select(
              req, allowSpecializedProviders);
      if (sel != null)
      {
        scaleExec = sel.getExecutionPlan();
        scaleLease = sel.getLease();
        scaleProviderId = sel.getProviderId();
      }
    }

    // Upscaling requires a provider that can actually render one. The built-in
    // passthrough does not upscale the playback path, so if the granted tier is
    // an upscale but the captured stage renders neither a filter fragment nor an
    // external-process worker, there is no server upscaler installed: collapse to
    // a no-op so the client receives the plain (un-enhanced) stream and scales
    // itself. The advisor normally suppresses the offer upstream via
    // ScaleProviderRegistry.selectedProviderCanUpscale(); this is the fail-closed
    // backstop at plan time. Deinterlace-only tiers never reach here.
    if (tier.isUpscaling())
    {
      boolean canUpscale = scaleExec != null
          && (scaleExec.rendersFilterFragment() || scaleExec.rendersExternalProcess());
      if (!canUpscale)
      {
        if (scaleLease != null) { try { scaleLease.close(); } catch (Throwable ignore) {} }
        return new EnhancementPlan(EnhancementTier.NONE, false, null, null, 0, 0, 0, 0,
            "no upscaling provider installed; built-in playback upscaling is disabled");
      }
    }

    return new EnhancementPlan(tier, deint != null, deint, scaler,
        tier.getTargetWidth(), tier.getTargetHeight(), rate, cap, "built",
        scaleExec, scaleLease, scaleProviderId);
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
      // Prefer the provider-captured scale stage. A selection-originated plan
      // (scaleExec != null) renders ONLY what the provider produced: a filter
      // fragment for filter-form providers, or nothing for the built-in
      // passthrough and for external-process providers (whose worker upscales
      // out-of-process, so the single-process fallback stays at source
      // resolution -- the core no longer emits a built-in Lanczos upscale on the
      // playback path). A directly-constructed plan (scaleExec == null, used by
      // calibration and offline-parity callers) keeps the legacy render.
      sage.enhance.spi.ScaleExecutionPlan exec = plan.getScaleExec();
      String frag = null;
      if (exec != null)
      {
        if (exec.rendersFilterFragment()) frag = exec.getFfmpegFilter();
      }
      else
      {
        StringBuilder s = new StringBuilder();
        s.append(plan.getScaler()).append('=')
         .append(plan.getTargetWidth()).append(':').append(plan.getTargetHeight());
        if (HwEncoder.scalerSupportsLanczos(plan.getScaler())) s.append(":interp_algo=lanczos");
        frag = s.toString();
      }
      if (frag != null && frag.length() > 0)
      {
        if (sb.length() > 0) sb.append(',');
        sb.append(frag);
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
      case ENHANCE_2160P:
      case ENHANCE_1440P:
      case ENHANCE_1080P:
        // Unified resolution ladder (sage.media.BitratePolicy). Its H.264 cells
        // are calibrated to reproduce these enhance tiers exactly, so the
        // numbers are unchanged here -- but browserhd, the H.264 push path and
        // the audioonly/dynamic paths now share the very same curve, so all
        // playback finally agrees on "resolution -> bitrate".
        base = sage.media.BitratePolicy.ladderKbps(tier.getTargetHeight(), mapMotion(motion));
        break;
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

  /** Map the genre motion class onto the shared {@link sage.media.BitratePolicy} enum. */
  private static sage.media.BitratePolicy.Motion mapMotion(EnhancementProfile.MotionClass m)
  {
    if (m == null) return sage.media.BitratePolicy.Motion.MEDIUM;
    switch (m)
    {
      case HIGH: return sage.media.BitratePolicy.Motion.HIGH;
      case LOW:  return sage.media.BitratePolicy.Motion.LOW;
      default:   return sage.media.BitratePolicy.Motion.MEDIUM;
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
   *       which routes through the {@code ScaleProvider} SPI, so a registered
   *       upscaling provider that renders a filter fragment is used here too. A CPU
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

    // (2) Collapse the base CPU -vf chain(s) to a single GPU chain. Re-anchor first.
    // The browserhd base command can carry MORE THAN ONE -vf for an interlaced
    // source: the base pixel-format filter ("-vf format=yuv420p") PLUS an
    // auto-added deinterlacer ("-vf yadif"). ffmpeg honours only the LAST -vf, so
    // replacing just the first would leave a trailing CPU "-vf yadif" that then
    // receives CUDA (VRAM) frames -- an unconvertible format the encode can never
    // start on, so no bytes are ever produced and the PWA/MSE client sits forever
    // on "Loading...". Remove EVERY existing -vf in the output section and install
    // exactly one CUDA-resident chain in the earliest -vf slot.
    iIdx = argv.indexOf("-i");
    int firstVf = -1;
    for (int i = argv.size() - 2; i > iIdx; i--)
    {
      if ("-vf".equals(argv.get(i)))
      {
        firstVf = i;
        argv.remove(i + 1);
        argv.remove(i);
      }
    }
    if (firstVf >= 0)
    {
      argv.add(firstVf, enhanceVf);
      argv.add(firstVf, "-vf");
    }
    else
    {
      int ins = Math.min(iIdx + 2, argv.size()); // after "-i <file>"
      argv.add(ins, enhanceVf);
      argv.add(ins, "-vf");
    }

    // (3) VBR rate control after the codec token. An active enhancement OWNS the
    // bitrate, so strip any rate-control the base browserhd profile already set
    // (its default cap) before installing the tier/genre estimate below --
    // otherwise addFlagIfAbsent would keep the profile's lower ceiling and
    // throttle the upscale.
    iIdx = argv.indexOf("-i");
    removeFlagPair(argv, iIdx, "-rc");
    removeFlagPair(argv, iIdx, "-b:v");
    removeFlagPair(argv, iIdx, "-maxrate");
    removeFlagPair(argv, iIdx, "-bufsize");
    vci = indexOfVideoCodec(argv, iIdx + 1);
    java.util.List<String> rc = buildReencodeRateControlArgs(plan, argv, iIdx);
    if (!rc.isEmpty() && vci >= 0) argv.addAll(vci + 2, rc);
    return true;
  }

  // ---- External-process (upscale worker) pipeline --------------------------

  /**
   * The two ffmpeg argv stages that bracket a provider-owned external worker.
   * The worker itself is spawned from {@link ScaleExecutionPlan#getExternalArgv()}
   * by the transcoder; this object only carries the decode (source → raw frames)
   * and encode (raw frames → delivery container) commands, connected by OS pipes:
   *
   * <pre>{@code  ffmpeg <decodeArgv> | <worker argv> | ffmpeg <encodeArgv> }</pre>
   */
  public static final class ExternalPipeline
  {
    private final java.util.List<String> decodeArgv;
    private final java.util.List<String> encodeArgv;
    private final String audioSidecarPath;
    private final java.util.List<String> audioArgv;
    private final boolean sourceControl;
    ExternalPipeline(java.util.List<String> d, java.util.List<String> e)
    { this(d, e, null, null, false); }
    ExternalPipeline(java.util.List<String> d, java.util.List<String> e, String audioSidecar,
        java.util.List<String> audioArgv)
    { this(d, e, audioSidecar, audioArgv, false); }
    ExternalPipeline(java.util.List<String> d, java.util.List<String> e, String audioSidecar,
        java.util.List<String> audioArgv, boolean sourceControl)
    { this.decodeArgv = d; this.encodeArgv = e; this.audioSidecarPath = audioSidecar; this.audioArgv = audioArgv;
      this.sourceControl = sourceControl; }
    /**
     * True when the source-reading stages ({@link #getDecodeArgv() decode} and,
     * when present, {@link #getAudioArgv() audio}) carry the SageTV {@code
     * -stdinctrl} control channel and are following the live file, so the caller
     * may write {@code inactivefile} to their stdin at a program boundary to stop
     * {@code -follow} -&gt; source EOF -&gt; pipeline EOS. False on the legacy split
     * where {@code -stdinctrl} was dropped (no way to signal boundary completion).
     */
    public boolean hasSourceControl() { return sourceControl; }
    /** ffmpeg that decodes the source and writes fixed-size raw frames to stdout. */
    public java.util.List<String> getDecodeArgv() { return decodeArgv; }
    /** ffmpeg that reads upscaled raw frames from stdin, muxes source audio, and
     *  writes the enhanced container to stdout. */
    public java.util.List<String> getEncodeArgv() { return encodeArgv; }
    /**
     * Filesystem path of the audio side-channel FIFO the {@linkplain
     * #getAudioArgv() audio process} writes and the encode stage reads, or
     * {@code null} when the pipeline re-opens the source for audio in the encode
     * (the legacy shape). When non-null the caller must create the named pipe
     * before spawning the stages, spawn {@link #getAudioArgv()} to fill it, and
     * unlink it on teardown.
     *
     * <p>The audio travels on a <em>separate, decoupled</em> ffmpeg process rather
     * than a second output of the video decode: a second decode output would have
     * to interleave audio with the raw video frames, but the raw video pipe is
     * back-pressured by the (slow) worker+encode, so the decode could never flush
     * an audio packet the encode was blocked waiting to probe -- a deadlock. The
     * standalone audio process reads the seekable source directly, unthrottled by
     * the video pipe, so it fills the FIFO freely. Both the video decode and this
     * audio process perform the identical accurate seek (see {@link SeekSplit}),
     * so a resumed play lands audio and video at exactly the same source time and
     * stays in lip-sync -- which the rawvideo pipe's PTS strip otherwise destroys.
     */
    public String getAudioSidecarPath() { return audioSidecarPath; }
    /**
     * Standalone ffmpeg that accurately seeks the source, copies its audio, and
     * writes {@code mpegts} to {@link #getAudioSidecarPath()}. Non-null exactly
     * when the sidecar path is non-null; spawn it alongside the decode/worker/
     * encode after creating the FIFO.
     */
    public java.util.List<String> getAudioArgv() { return audioArgv; }
  }

  /**
   * Locate the ffmpeg executable within a base command that may be wrapped in a
   * launcher prefix (e.g. {@code nice}, optionally {@code nice -n N}). Returns the
   * index of the first token naming ffmpeg, searching only up to {@code limit}
   * (the input marker): tokens before it are the launcher prefix and must be
   * preserved on every derived sub-stage rather than mistaken for the binary.
   * Returns -1 when no ffmpeg binary precedes the input.
   */
  private static int ffmpegBinIndex(java.util.List<String> argv, int limit)
  {
    int n = Math.min(limit, argv.size());
    for (int i = 0; i < n; i++)
    {
      String t = argv.get(i);
      if (t != null && t.toLowerCase().indexOf("ffmpeg") >= 0) return i;
    }
    return -1;
  }

  /**
   * Map a SageTV source video-format name (as reported by
   * {@code ContainerFormat.getPrimaryVideoFormat()}) to the matching NVDEC
   * {@code *_cuvid} decoder, or {@code null} when none applies. Selecting the
   * dedicated cuvid decoder explicitly is load-bearing: a bare
   * {@code -hwaccel cuda} hint silently falls back to the software decoder for
   * MPEG-2/H.264 on this build, which caps the decode stage well below realtime
   * and starves the worker. The cuvid decoders download to system memory (no
   * {@code -hwaccel_output_format}), exactly what the raw-frame pipe needs.
   */
  static String cuvidDecoderFor(String videoFormat)
  {
    if (videoFormat == null) return null;
    if (sage.media.format.MediaFormat.MPEG2_VIDEO.equals(videoFormat)) return "mpeg2_cuvid";
    if (sage.media.format.MediaFormat.H264.equals(videoFormat))        return "h264_cuvid";
    if (sage.media.format.MediaFormat.HEVC.equals(videoFormat))        return "hevc_cuvid";
    if (sage.media.format.MediaFormat.MPEG1_VIDEO.equals(videoFormat)) return "mpeg1_cuvid";
    if (sage.media.format.MediaFormat.MPEG4_VIDEO.equals(videoFormat)) return "mpeg4_cuvid";
    if (sage.media.format.MediaFormat.VC1.equals(videoFormat))         return "vc1_cuvid";
    return null;
  }

  /**
   * Colorspace-sanitizing {@code -vf} element for the cuvid decode path, or
   * {@code null} when none is needed.
   *
   * <p>The {@code *_cuvid} NVDEC decoders download NV12 frames whose color
   * metadata is left unspecified. This unified ffmpeg build's swscale is strict
   * and refuses the implicit NV12&rarr;RGB24 conversion the raw-frame pipe needs,
   * dying before the first frame with {@code Unsupported input ... csp:gbr
   * prim:reserved -> fmt:rgb24} (error -95). The decode process then exits in
   * ~20ms, the launcher sees {@code decodeAlive=false}, and the whole external
   * pipeline is abandoned in favor of a plain transcode &mdash; the "enhanced
   * play tears/jumps" symptom. Stamping a valid matrix with {@code setparams}
   * before the conversion fixes it (verified on the deploy host).
   *
   * <p>Only applies when an explicit cuvid decoder is in use (software decode
   * preserves the tags) and the pipe format is an RGB family (a YUV pipe needs
   * no swscale RGB conversion, so the bug never triggers). The external pipe is
   * upscale-only &mdash; source height is always at or above the 720-line floor
   * &mdash; so HD {@code bt709} is the correct matrix.
   */
  private static String cuvidColorspaceFixup(String cuvid, String pixFmt)
  {
    if (cuvid == null || pixFmt == null) return null;
    String pf = pixFmt.toLowerCase(java.util.Locale.ROOT);
    boolean rgbFamily = pf.startsWith("rgb") || pf.startsWith("bgr") || pf.startsWith("gbr");
    if (!rgbFamily) return null;
    return "setparams=colorspace=bt709:color_primaries=bt709:color_trc=bt709";
  }

  /**
   * Output-side pixel-format normalization for the worker&rarr;encode stage, or an
   * empty list when none is needed.
   *
   * <p>The VSR worker delivers full-range packed RGB ({@code rgb24}). Handed
   * straight to NVENC, that produces a <b>4:4:4 RGB</b> bitstream &mdash; HEVC
   * Range-Extensions ({@code gbrp}) or H.264 High-4:4:4-Predictive &mdash; which
   * consumer hardware decoders (Shield, Android media3/ExoPlayer, IJK, browser
   * MSE) can only *instantiate*: they support Main/Main10 8-bit 4:2:0 exclusively,
   * so they accept the stream, decode zero frames, and render a blank screen
   * ({@code reported=0 rendered=0}, "can not return buffer to native window").
   * Forcing {@code yuv420p} on the output inserts the RGB&rarr;YUV 4:2:0 conversion
   * NVENC needs, matching the byte format the pre-enhancement browserhd path
   * already shipped (its {@code -vf format=yuv420p}, dropped by the worker rewrite,
   * is re-established here on the output side).
   *
   * <p>Only fires for an RGB-family pipe; a YUV pipe is already decoder-friendly
   * and is left untouched.
   */
  private static java.util.List<String> pipeOutputPixelFormatArgs(String pipePixFmt)
  {
    java.util.List<String> out = new java.util.ArrayList<String>();
    if (pipePixFmt == null) return out;
    String pf = pipePixFmt.toLowerCase(java.util.Locale.ROOT);
    boolean rgbFamily = pf.startsWith("rgb") || pf.startsWith("bgr") || pf.startsWith("gbr");
    if (rgbFamily) { out.add("-pix_fmt"); out.add("yuv420p"); }
    return out;
  }

  /**
   * Format an exact frame-rate as an ffmpeg {@code -framerate}/{@code fps=} token,
   * preferring the broadcast rational over a rounded integer.
   *
   * <p>A literal {@code -framerate 30} against a 29.97 (30000/1001) source is a
   * 0.1% overspeed that accumulates ~1.8s of A/V drift across a 30-minute
   * program. The NTSC-family fractional rates (23.976, 29.97, 59.94) are emitted
   * as their exact rationals; genuine integer rates (24/25/30/50/60, PAL/film)
   * stay integers. An unrecognized fractional rate falls back to a milli-exact
   * {@code n/1000} rational rather than silently rounding.
   */
  static String frameRateToken(double fps)
  {
    if (fps <= 0.0) return "0";
    long nearest = Math.round(fps);
    if (Math.abs(fps - nearest) <= 0.01) return Long.toString(nearest); // true integer rate
    if (Math.abs(fps - 24000.0 / 1001.0) < 0.05) return "24000/1001";   // 23.976
    if (Math.abs(fps - 30000.0 / 1001.0) < 0.05) return "30000/1001";   // 29.97
    if (Math.abs(fps - 60000.0 / 1001.0) < 0.05) return "60000/1001";   // 59.94
    return Math.round(fps * 1000.0) + "/1000";
  }

  /**
   * The two halves of an <em>accurate</em> seek, split from a base input
   * section's single {@code -ss T}. A resumed enhanced play must land video and
   * audio on the exact same source instant, but the raw-frame worker pipe strips
   * the video PTS: the encode regenerates it from the pipe {@code -framerate}
   * starting at zero, so any offset between where the two streams actually seek
   * to becomes fixed lip-sync error. A bare (fast) {@code -ss T} lands the
   * {@code *_cuvid} decoder on the next decodable frame &mdash; up to ~1&ndash;3s
   * <em>after</em> T, varying with GOP position &mdash; while an audio copy lands
   * elsewhere, so the natural offset is neither zero nor constant.
   *
   * <p>The remedy is an accurate seek: a coarse <em>input</em> seek to
   * {@code T - preroll} (fast, keyframe-granular) followed by an accurate
   * <em>output</em> seek of {@code preroll} that decodes-and-discards up to
   * exactly T. Applied identically to the video output and the audio side-channel
   * output of one decode process, both streams start at exactly T and stay in
   * sync by construction, with no runtime probe and correct behaviour at
   * {@code T == 0} (the split collapses to a no-op).
   */
  static final class SeekSplit
  {
    /** Input section with {@code -ss} rewritten to the coarse pre-roll target. */
    final java.util.List<String> decodeInput;
    /** {@code ["-ss", "<preroll>"]} to prepend to each output, or empty. */
    final java.util.List<String> outputSeek;
    /** Absolute source seconds of the seek target T, or 0 when there is no
     *  meaningful seek (head). Used to drive the video's accurate {@code select}
     *  landing when {@link #outputSeek} is non-empty. */
    final double targetSeconds;
    SeekSplit(java.util.List<String> di, java.util.List<String> os)
    { this(di, os, 0.0); }
    SeekSplit(java.util.List<String> di, java.util.List<String> os, double targetSeconds)
    { this.decodeInput = di; this.outputSeek = os; this.targetSeconds = targetSeconds; }
  }

  /**
   * Split the base {@code inputSection} into a coarse input seek plus an accurate
   * output seek of at most {@code prerollSec} seconds. When the section carries
   * no {@code -ss}, or the seek is at/again the head, or {@code prerollSec <= 0},
   * the input is returned unchanged with an empty output seek (a no-op).
   */
  static SeekSplit accurateSeekSplit(java.util.List<String> inputSection, double prerollSec)
  {
    java.util.List<String> di = new java.util.ArrayList<String>(inputSection);
    java.util.List<String> os = new java.util.ArrayList<String>();
    if (prerollSec <= 0.0) return new SeekSplit(di, os);
    int ssIdx = -1;
    double t = 0.0;
    for (int i = 0; i + 1 < di.size(); i++)
    {
      if ("-ss".equals(di.get(i)))
      {
        ssIdx = i;
        try { t = Double.parseDouble(di.get(i + 1)); } catch (NumberFormatException e) { t = 0.0; }
        break;
      }
    }
    if (ssIdx < 0 || t <= 0.0) return new SeekSplit(di, os); // no meaningful seek
    double coarse = Math.max(0.0, t - prerollSec);
    double outSeek = t - coarse;
    if (outSeek <= 0.0) return new SeekSplit(di, os);
    di.set(ssIdx + 1, formatSeconds(coarse));
    os.add("-ss");
    os.add(formatSeconds(outSeek));
    return new SeekSplit(di, os, t);
  }

  /** Format a seconds value for an ffmpeg {@code -ss} token: millisecond
   *  precision, no trailing zeros, never scientific notation. */
  static String formatSeconds(double s)
  {
    if (s < 0.0) s = 0.0;
    long ms = Math.round(s * 1000.0);
    String str = String.format(java.util.Locale.ROOT, "%d.%03d", ms / 1000, ms % 1000);
    // trim trailing zeros then any dangling dot
    int end = str.length();
    while (end > 0 && str.charAt(end - 1) == '0') end--;
    if (end > 0 && str.charAt(end - 1) == '.') end--;
    return str.substring(0, end);
  }

  /**
   * Build the standalone audio side-channel process: an ffmpeg that performs the
   * <em>same</em> accurate seek as the video decode, copies the selected source
   * audio, and writes {@code mpegts} to the FIFO the encode reads. Kept a separate
   * process (not a second output of the video decode) so it is not throttled by
   * the back-pressured raw video pipe -- see {@link ExternalPipeline#getAudioSidecarPath()}.
   *
   * @param launchPrefix any launcher tokens (e.g. {@code nice}) shared with the
   *     other stages, preserved ahead of the ffmpeg binary
   * @param ffmpegBin the ffmpeg executable
   * @param split the accurate-seek split already computed for the video decode;
   *     its coarse input and per-output {@code -ss} are reused verbatim so audio
   *     and video land at the identical source time
   * @param audioMap the audio stream selector (e.g. {@code 0:a?})
   * @param audioSidecarPath the FIFO path to write mpegts audio to
   * @param sidecarAudio when {@code null} (or carrying no output options) the
   *     source audio is stream-copied ({@code -c:a copy}); otherwise the source
   *     audio is transcoded using the caller-supplied option lists. Copying is
   *     impossible for sources whose codec the {@code mpegts} muxer cannot carry
   *     (notably <b>AC-4</b> in the pinned ffmpeg fork): a {@code -c:a copy} there
   *     makes this process exit before it opens the FIFO for write, and the encode
   *     blocks forever opening the same FIFO for read (a hard pipeline deadlock).
   *     The caller ({@code FFMPEGTranscoder}) owns the AC-4 replacement codec,
   *     bitrate, {@code -copytb} and {@code aresample=async} drift-correction
   *     filter so the sidecar reproduces the exact audio timing of the
   *     non-enhanced path (see {@link SidecarAudio}).
   */
  static java.util.List<String> buildAudioSidecarArgv(java.util.List<String> launchPrefix,
      String ffmpegBin, SeekSplit split, String audioMap, String audioSidecarPath, boolean sourceCtrl,
      SidecarAudio sidecarAudio)
  {
    java.util.List<String> a = new java.util.ArrayList<String>();
    a.addAll(launchPrefix);
    a.add(ffmpegBin);
    a.add("-y");                              // overwrite the pre-created FIFO node
    // Source control channel: like the decode stage, this process follows the live
    // file, so it must accept 'inactivefile' to stop -follow at a program boundary
    // (otherwise the encode's audio FIFO input never EOFs and the pipeline can't
    // drain to EOS). Its stdin is otherwise unused.
    if (sourceCtrl) a.add("-stdinctrl");
    // Input-side options (e.g. -copytb 0 for AC-4 in MPEG-TS) must precede -i, so
    // they go ahead of the seek/input group.
    if (sidecarAudio != null && sidecarAudio.inputOpts != null)
      a.addAll(sidecarAudio.inputOpts);
    a.addAll(stripInputHwaccel(split.decodeInput));  // coarse input seek + source
    a.addAll(split.outputSeek);               // accurate output seek to exactly T
    a.add("-map"); a.add(audioMap);
    boolean transcode = sidecarAudio != null && sidecarAudio.outputOpts != null
        && !sidecarAudio.outputOpts.isEmpty();
    if (transcode)
      a.addAll(sidecarAudio.outputOpts);      // -c:a <codec> -b:a <n> -af aresample=async=...
    else
    {
      a.add("-c:a"); a.add("copy");           // default: preserve the source codec
    }
    a.add("-f"); a.add("mpegts");
    a.add(audioSidecarPath);
    return a;
  }

  /**
   * Audio-side-channel transcode options, resolved by {@code FFMPEGTranscoder}
   * (which owns the AC-4 codec/bitrate/resample policy) and threaded to
   * {@link #buildAudioSidecarArgv}. {@code inputOpts} are spliced <em>before</em>
   * the input {@code -i} (per-input options such as {@code -copytb 0});
   * {@code outputOpts} replace the default {@code -c:a copy} (e.g.
   * {@code -c:a eac3 -b:a 640k -af aformat=channel_layouts=stereo,aresample=async=1000}).
   * A {@code null} holder, or one whose {@code outputOpts} are empty, means "copy".
   */
  public static final class SidecarAudio
  {
    public final java.util.List<String> inputOpts;
    public final java.util.List<String> outputOpts;

    public SidecarAudio(java.util.List<String> inputOpts, java.util.List<String> outputOpts)
    {
      this.inputOpts = inputOpts;
      this.outputOpts = outputOpts;
    }
  }

  /**
   * Decode-stage {@code -vf} token list for the external raw-frame pipe. The
   * caller joins the returned tokens with commas; an empty list means no
   * {@code -vf} at all.
   *
   * <p><b>Interlaced source (GPU deinterlace).</b> A software {@code yadif} after
   * an {@code *_cuvid} decoder is a hard ffmpeg failure: cuvid hands it NV12, the
   * implicit NV12&rarr;planar swscale that {@code yadif} forces is rejected
   * ({@code csp:gbr prim:reserved}, error -95), and the decode emits <b>zero
   * frames</b> in ~20ms &mdash; the whole enhanced session then collapses to an
   * un-deinterlaced copy and the viewer gets combing ("tearing/jumping"). The fix
   * keeps frames VRAM-resident (the caller emits {@code -hwaccel_output_format
   * cuda}; see {@link #externalDecodeNeedsCudaFrames}), deinterlaces with the
   * CUDA filter in <b>bob</b> mode ({@code =1:-1:0}, one output frame per field =>
   * full 59.94fps of temporal detail from 1080i, what a decent TV does), then
   * {@code hwdownload,format=nv12} back to system memory, and only <i>then</i>
   * stamps the colorspace on those downloaded frames (swscale reads them next).
   *
   * <p><b>Progressive source.</b> The cuvid decoder has already downloaded NV12
   * to system memory (no {@code -hwaccel_output_format}), so the chain is just the
   * colorspace stamp before the implicit NV12&rarr;RGB swscale.
   *
   * @param csFix the cuvid colorspace {@code setparams} token, or null
   * @param decimateToken the {@code fps=} decimation target, or null for none
   */
  private static java.util.List<String> externalDecodeFilters(
      EnhancementPlan plan, String csFix, String decimateToken)
  {
    java.util.List<String> vf = new java.util.ArrayList<String>();
    if (plan.isDeinterlace())
    {
      String deint = plan.getDeinterlacer();
      if (deint == null) deint = "yadif_cuda";
      vf.add(deint + "=1:-1:0");   // bob (send_field): field-rate output
      vf.add("hwdownload");
      vf.add("format=nv12");
      if (csFix != null) vf.add(csFix);
    }
    else if (csFix != null)
    {
      vf.add(csFix);
    }
    if (decimateToken != null) vf.add("fps=" + decimateToken);
    return vf;
  }

  /**
   * Insert an accurate-landing {@code select} filter into an already-built decode
   * filter chain so the first delivered video frame is the one at exactly source
   * time {@code targetSeconds}.
   *
   * <p>Rationale: the {@code *_cuvid} decoders with CUDA-resident output
   * ({@code -hwaccel_output_format cuda}) honour a coarse <em>input</em>
   * {@code -ss} as a fast keyframe seek but skip the input-side accurate discard,
   * so a following output {@code -ss preroll} lands the first frame at
   * {@code keyframe + preroll} &mdash; up to a GOP early, varying with seek
   * position. The packet-accurate audio side-channel lands at exactly T, so the
   * mismatch shows as variable lip-sync drift that changes on every skip. Driving
   * the landing with {@code select='gte(t,T)'} instead performs the discard on the
   * decoded frames (under {@code -copyts}, whose preserved source PTS makes
   * {@code t} absolute), landing video at exactly T to match the audio.
   *
   * <p>The filter is inserted <em>before</em> any {@code fps=} decimation so it
   * sees the original decoded presentation timestamps.
   */
  private static void appendAccurateSelect(java.util.List<String> vf, double targetSeconds)
  {
    String sel = "select=gte(t\\," + formatSeconds(targetSeconds) + ")";
    for (int i = 0; i < vf.size(); i++)
      if (vf.get(i).startsWith("fps=")) { vf.add(i, sel); return; }
    vf.add(sel);
  }

  /**
   * Whether the external decode stage must request CUDA-resident decoder output
   * ({@code -hwaccel_output_format cuda}). True exactly when the plan deinterlaces:
   * the CUDA {@code yadif_cuda}/{@code bwdif_cuda} filter only exists behind
   * VRAM frames, which are then downloaded back for the worker pipe. Progressive
   * sources leave decoder output in system memory as before.
   */
  private static boolean externalDecodeNeedsCudaFrames(EnhancementPlan plan)
  {
    return plan != null && plan.isDeinterlace();
  }

  /**
   * Copy of an input section with every {@code -hwaccel}/{@code -hwaccel_output_format}/
   * {@code -hwaccel_device} option (and its value) removed. The decode stage owns
   * its own decode-accel spec, so any {@code -hwaccel} inherited from the base
   * command's input section must be dropped first — otherwise the two collide and
   * ffmpeg warns "only the last option used", masking the explicit cuvid decoder.
   */
  private static java.util.List<String> stripInputHwaccel(java.util.List<String> in)
  {
    java.util.List<String> out = new java.util.ArrayList<String>(in.size());
    for (int i = 0; i < in.size(); i++)
    {
      String t = in.get(i);
      if (("-hwaccel".equals(t) || "-hwaccel_output_format".equals(t)
            || "-hwaccel_device".equals(t)) && i + 1 < in.size())
      { i++; continue; }
      out.add(t);
    }
    return out;
  }

  /**
   * Build the decode/encode ffmpeg stages that bracket an
   * {@link ExecutionForm#EXTERNAL_PROCESS} worker, from the already-assembled
   * copy-family base command. Pure list transform (no processes launched), so the
   * argv shape is unit-testable.
   *
   * <p>The decode stage strips audio and any output-side muxing, deinterlaces on
   * the CPU (the worker consumes progressive frames over a pipe), and emits
   * headerless {@code rawvideo} at the <b>source</b> resolution. The encode stage
   * re-reads the same source (identical input flags, so any {@code -ss} seek keeps
   * audio aligned) purely for its audio, maps the upscaled video from the pipe,
   * applies the NVENC HEVC encoder, and writes to the base command's original
   * output target (stdout for the live push/pull path).
   *
   * @return the two stages, or {@code null} when the base command is not the
   *     expected copy-family shape or the plan carries no external worker — the
   *     caller then falls back to the single-process (Lanczos) command.
   */
  public static ExternalPipeline buildExternalPipeline(
      java.util.List<String> baseCopyArgv, EnhancementPlan plan, int fps)
  {
    return buildExternalPipeline(baseCopyArgv, plan, fps, null, 0);
  }

  /**
   * As {@link #buildExternalPipeline(java.util.List, EnhancementPlan, int)}, but
   * with the source video-format name (e.g. {@code MediaFormat.MPEG2_VIDEO}) so
   * the decode stage can select an explicit {@code *_cuvid} NVDEC decoder. Pass
   * {@code null} to keep the generic {@code -hwaccel cuda} hint — the caller is
   * expected to pass a codec only when the server actually has an NVIDIA GPU.
   */
  public static ExternalPipeline buildExternalPipeline(
      java.util.List<String> baseCopyArgv, EnhancementPlan plan, int fps, String srcVideoFormat)
  {
    return buildExternalPipeline(baseCopyArgv, plan, fps, srcVideoFormat, 0);
  }

  /**
   * As above, plus a raw-frame-rate cap. When {@code fpsCap > 0} and the source
   * runs faster, the decode stage decimates to the cap with an {@code fps=} filter
   * <b>before</b> the pipe, so the RGB24 raw stream (≈{@code W*H*3*fps} B/s) and
   * the worker's per-frame GPU cost are cut proportionally, and the encode stage
   * is tagged with the same effective rate so decode and encode never disagree
   * (the source of the "60fps of pulldown duplicates" waste). A native-24/30fps
   * source below the cap is passed through untouched. {@code fpsCap <= 0} disables
   * the cap.
   */
  public static ExternalPipeline buildExternalPipeline(
      java.util.List<String> baseCopyArgv, EnhancementPlan plan, int fps, String srcVideoFormat,
      int fpsCap)
  {
    return buildExternalPipeline(baseCopyArgv, plan, fps, srcVideoFormat, fpsCap, fps);
  }

  /**
   * As above, plus the <b>exact</b> source frame rate (e.g. {@code 29.97}) so the
   * encode {@code -framerate} is tagged with the true broadcast rational
   * ({@code 30000/1001}) instead of a rounded integer that drifts A/V. Pass
   * {@code exactFps <= 0} to fall back to the integer {@code fps}. When the plan
   * deinterlaces, the decode stage runs bob (field-rate) deinterlace, so the
   * effective pipe rate &mdash; and the encode {@code -framerate} &mdash; is
   * <b>doubled</b> to the field rate before the cap is applied.
   */
  public static ExternalPipeline buildExternalPipeline(
      java.util.List<String> baseCopyArgv, EnhancementPlan plan, int fps, String srcVideoFormat,
      int fpsCap, double exactFps)
  {
    return buildExternalPipeline(baseCopyArgv, plan, fps, srcVideoFormat, fpsCap, exactFps, null, 0.0);
  }

  /**
   * As above, plus an audio side-channel. When {@code audioSidecarPath} is
   * non-null, the source audio is copied out of the <em>same</em> decode process
   * (a second output writing {@code mpegts} to that FIFO path) instead of the
   * encode re-opening the file, and both the video and audio decode outputs use
   * an accurate seek (coarse input pre-roll of {@code seekPrerollSec} + accurate
   * output seek) so a resumed play keeps audio and video on one seek origin. See
   * {@link SeekSplit} and {@link ExternalPipeline#getAudioSidecarPath()}. Passing
   * {@code null}/{@code 0} preserves the legacy re-open-for-audio shape.
   */
  public static ExternalPipeline buildExternalPipeline(
      java.util.List<String> baseCopyArgv, EnhancementPlan plan, int fps, String srcVideoFormat,
      int fpsCap, double exactFps, String audioSidecarPath, double seekPrerollSec)
  {
    return buildExternalPipeline(baseCopyArgv, plan, fps, srcVideoFormat, fpsCap, exactFps,
        audioSidecarPath, seekPrerollSec, null);
  }

  /**
   * As above, plus an explicit {@code sidecarAudio}: when non-null the audio
   * side-channel transcodes the source audio (codec/bitrate/filter supplied by
   * the caller) instead of a blind {@code -c:a copy}. Required for AC-4 sources,
   * which the {@code mpegts} muxer cannot stream-copy in the pinned fork (a copy
   * there deadlocks the audio FIFO), and which need the same {@code -copytb 0} +
   * {@code aresample=async} timing correction the non-enhanced path applies.
   */
  public static ExternalPipeline buildExternalPipeline(
      java.util.List<String> baseCopyArgv, EnhancementPlan plan, int fps, String srcVideoFormat,
      int fpsCap, double exactFps, String audioSidecarPath, double seekPrerollSec,
      SidecarAudio sidecarAudio)
  {
    if (baseCopyArgv == null || baseCopyArgv.size() < 3 || plan == null || !plan.isActive())
      return null;
    sage.enhance.spi.ScaleExecutionPlan exec = plan.getScaleExec();
    if (exec == null || !exec.rendersExternalProcess()) return null;

    int iIdx = baseCopyArgv.indexOf("-i");
    if (iIdx <= 0 || iIdx + 1 >= baseCopyArgv.size()) return null;
    // The base command may carry a launcher prefix (e.g. "nice"); the real ffmpeg
    // executable is the first token naming ffmpeg. Everything before it is a
    // prefix preserved on both sub-stages, not the binary itself.
    int binIdx = ffmpegBinIndex(baseCopyArgv, iIdx);
    if (binIdx < 0) return null;
    java.util.List<String> launchPrefix =
        new java.util.ArrayList<String>(baseCopyArgv.subList(0, binIdx));
    String ffmpegBin = baseCopyArgv.get(binIdx);

    // Input section = everything from the first arg through "-i <source>". Carries
    // any -ss seek and input-format flags; reused verbatim by both stages. The
    // SageTV control flag -stdinctrl is dropped from this SHARED list because it
    // must never reach the encode stage (whose stdin is the raw-frame pipe). It is
    // re-added below to the source-reading stages (decode + audio sidecar) so the
    // 'inactivefile' boundary signal that stops -follow still has a channel; that
    // restores the program-boundary EOS -> live rollover for enhanced sessions.
    java.util.List<String> inputSection =
        new java.util.ArrayList<String>(baseCopyArgv.subList(binIdx + 1, iIdx + 2));
    boolean hadCtrl = inputSection.remove("-stdinctrl");
    boolean sourceCtrl = hadCtrl
        && sage.Sage.getBoolean("playback/gpu_enhance/external_active_file_control", true);

    // Output target = the base command's final token (stdout "-" on the live
    // path, or a file). Container from the output-section "-f", default mpegts.
    String outputTarget = baseCopyArgv.get(baseCopyArgv.size() - 1);
    String container = "mpegts";
    for (int i = iIdx + 2; i + 1 < baseCopyArgv.size(); i++)
    {
      if ("-f".equals(baseCopyArgv.get(i))) { container = baseCopyArgv.get(i + 1); break; }
    }

    String pixFmt = exec.getPipePixelFormat();
    if (pixFmt == null || pixFmt.isEmpty()) pixFmt = "rgb24";
    int outW = exec.getOutputWidth();
    int outH = exec.getOutputHeight();
    if (fps <= 0) fps = 30;
    // Effective pipeline rate. Bob deinterlace (mode 1) emits one frame per field,
    // so an interlaced source doubles to its field rate BEFORE any cap; the cap
    // then bounds pipe bandwidth and worker load. Decode and encode share this one
    // rate so they never disagree, and it is carried as the exact broadcast
    // rational (29.97 -> 30000/1001), not a drifting rounded integer.
    double srcRate = (exactFps > 0.0) ? exactFps : (double) fps;
    int fieldMult = plan.isDeinterlace() ? 2 : 1;
    double pipeRate = srcRate * fieldMult;
    boolean decimate = (fpsCap > 0 && pipeRate > fpsCap + 1e-6);
    double effRate = decimate ? (double) fpsCap : pipeRate;
    String frTok = frameRateToken(effRate);
    int effFpsInt = (int) Math.round(effRate);   // integer rate for GOP/keyframe math

    // ---- Decode stage: source -> raw frames on stdout ----
    java.util.List<String> decode = new java.util.ArrayList<String>();
    decode.addAll(launchPrefix);
    decode.add(ffmpegBin);
    // Decode accel: emit exactly one -hwaccel and, when the source codec is known,
    // an explicit *_cuvid NVDEC decoder so hardware decode actually engages -- a
    // bare "-hwaccel cuda" silently falls back to software mpeg2/h264 on this
    // build, capping the decode stage below realtime. Progressive frames land in
    // system memory for the worker's raw-frame pipe; an interlaced source must
    // instead deinterlace on the GPU (see externalDecodeFilters), so we request
    // CUDA-resident decoder output (-hwaccel_output_format cuda) and download
    // after the deinterlacer. Any -hwaccel inherited from the base input section
    // is stripped first to avoid a duplicate ("only the last option used").
    String cuvid = cuvidDecoderFor(srcVideoFormat);
    // Audio side-channel: when present, a SEPARATE ffmpeg process (built below)
    // copies the source audio to the FIFO after the identical accurate seek, and
    // the encode reads that FIFO. The decode itself stays video-only either way
    // -- routing audio as a second decode output deadlocks against the back-
    // pressured raw video pipe (see ExternalPipeline#getAudioSidecarPath). The
    // accurate seek lands the first video frame at exactly T via a select filter
    // (see appendAccurateSelect) so it matches the audio process; no-op at the head.
    boolean sidecar = (audioSidecarPath != null && !audioSidecarPath.isEmpty());
    SeekSplit seek = sidecar ? accurateSeekSplit(inputSection, seekPrerollSec) : null;
    boolean accurateSelect = seek != null && !seek.outputSeek.isEmpty();
    // -copyts preserves source PTS so the select filter's t is absolute; must
    // precede the input so it applies to the decode.
    if (accurateSelect) decode.add("-copyts");
    decode.add("-hwaccel"); decode.add("cuda");
    if (externalDecodeNeedsCudaFrames(plan)) { decode.add("-hwaccel_output_format"); decode.add("cuda"); }
    if (cuvid != null) { decode.add("-c:v"); decode.add(cuvid); }
    // Re-add the source control channel (stripped from the shared inputSection) so
    // the decode stage follows the live file AND can be told 'inactivefile' at a
    // program boundary. Its stdin is otherwise unused (frames leave on stdout), so
    // this is safe. FFMPEGTranscoder retains this process's stdin for the write.
    if (sourceCtrl) decode.add("-stdinctrl");
    decode.addAll(stripInputHwaccel(sidecar ? seek.decodeInput : inputSection));
    // Video filter chain, in pipe order. Interlaced: GPU bob deinterlace ->
    // hwdownload -> colorspace stamp; progressive: colorspace stamp only (cuvid
    // emits NV12 whose unspecified color metadata strict swscale rejects on the
    // RGB pipe -- see cuvidColorspaceFixup). Frame-rate decimation to the cap, if
    // any, runs last so only frames that will be encoded reach the pipe.
    String csFix = cuvidColorspaceFixup(cuvid, pixFmt);
    java.util.List<String> vf =
        externalDecodeFilters(plan, csFix, decimate ? frTok : null);
    if (accurateSelect) appendAccurateSelect(vf, seek.targetSeconds);
    if (sidecar)
    {
      decode.add("-map"); decode.add("0:v:0");    // video only; audio is the sidecar process
    }
    else
    {
      decode.add("-an");                          // audio handled by the encode stage
    }
    if (!vf.isEmpty()) { decode.add("-vf"); decode.add(String.join(",", vf)); }
    decode.add("-f"); decode.add("rawvideo");
    decode.add("-pix_fmt"); decode.add(pixFmt);
    decode.add("pipe:1");

    // Standalone audio process: accurate-seek copy of the source audio -> FIFO.
    java.util.List<String> audioArgv = sidecar
        ? buildAudioSidecarArgv(launchPrefix, ffmpegBin, seek, "0:a?", audioSidecarPath, sourceCtrl,
            sidecarAudio) : null;

    // ---- Encode stage: raw frames on stdin (+ source audio) -> container on stdout ----
    java.util.List<String> encode = new java.util.ArrayList<String>();
    encode.addAll(launchPrefix);
    encode.add(ffmpegBin);
    // Input 0: the upscaled raw frames from the worker.
    encode.add("-f"); encode.add("rawvideo");
    encode.add("-pix_fmt"); encode.add(pixFmt);
    encode.add("-video_size"); encode.add(outW + "x" + outH);
    encode.add("-framerate"); encode.add(frTok);
    encode.add("-i"); encode.add("pipe:0");
    // Input 1: source audio. From the side-channel FIFO (already accurately
    // seeked out of the decode) when present, else the original source re-opened
    // with the same input flags (legacy shape; the "same -ss" is NOT actually
    // aligned on a resume -- see ExternalPipeline#getAudioSidecarPath).
    if (sidecar) { encode.add("-f"); encode.add("mpegts"); encode.add("-i"); encode.add(audioSidecarPath); }
    else encode.addAll(inputSection);
    encode.add("-map"); encode.add("0:v:0");
    encode.add("-map"); encode.add("1:a?");
    encode.addAll(buildEncoderArgs(plan, effFpsInt));
    // Force 8-bit 4:2:0 Main-profile output: the worker feeds RGB frames, and
    // NVENC left to its own devices emits a 4:4:4 RGB HEVC (Rext) stream that HW
    // decoders instantiate but cannot render. See pipeOutputPixelFormatArgs.
    java.util.List<String> pixArgs = pipeOutputPixelFormatArgs(pixFmt);
    encode.addAll(pixArgs);
    if (!pixArgs.isEmpty()) { encode.add("-profile:v"); encode.add("main"); }
    encode.add("-c:a"); encode.add("copy");
    encode.add("-f"); encode.add(container);
    encode.add(outputTarget);

    if (buildEncoderArgs(plan, effFpsInt).isEmpty()) return null; // no NVENC encoder available
    return new ExternalPipeline(decode, encode, sidecar ? audioSidecarPath : null, audioArgv, sourceCtrl);
  }

  /**
   * Browser/PWA counterpart of {@link #buildExternalPipeline}: bracket an
   * {@link ExecutionForm#EXTERNAL_PROCESS} worker around the already-assembled
   * <b>re-encode</b> ({@code browserhd}) base command, so a specialized upscaler
   * (e.g. an NVIDIA VSR worker) can drive the browser MSE path too.
   *
   * <p>The critical difference from the copy-family builder is the encode stage:
   * it must NOT switch the output to the HEVC/mpegts {@link #buildEncoderArgs}
   * emits (browser MSE cannot decode HEVC). Instead it <b>reuses the base
   * command's own output section</b> — its {@code h264_nvenc} video codec, its
   * preset/profile/g/forced-idr, its AAC audio parameters and its fragmented-MP4
   * container flags ({@code -f mp4 -movflags +frag_keyframe+empty_moov+
   * default_base_moof}) — merely swapping the <i>source</i> of the video from the
   * decoded input to the worker's upscaled raw-frame pipe, and re-imposing the
   * plan's VBR envelope. The result is byte-compatible with what the browser
   * already plays, only upscaled.
   *
   * <p>Only the video filters ({@code -vf ...}, now redundant — the worker did the
   * scaling), any inherited rate control, and the base stream {@code -map}s are
   * dropped; every other output token is preserved verbatim. Requires an NVENC
   * video codec in the base output section (any other encoder is refused, the
   * fail-closed direction), and a plan carrying an external worker with positive
   * output geometry.
   *
   * @return the two stages, or {@code null} when the base command is not the
   *     expected browser re-encode shape or the plan carries no external worker —
   *     the caller then falls back to the single-process command.
   */
  public static ExternalPipeline buildExternalReencodePipeline(
      java.util.List<String> baseReencodeArgv, EnhancementPlan plan, int fps)
  {
    return buildExternalReencodePipeline(baseReencodeArgv, plan, fps, null, 0);
  }

  /**
   * As {@link #buildExternalReencodePipeline(java.util.List, EnhancementPlan, int)},
   * but with the source video-format name so the decode stage can select an
   * explicit {@code *_cuvid} NVDEC decoder. Pass {@code null} to keep the generic
   * {@code -hwaccel cuda} hint — the caller passes a codec only when the server
   * actually has an NVIDIA GPU.
   */
  public static ExternalPipeline buildExternalReencodePipeline(
      java.util.List<String> baseReencodeArgv, EnhancementPlan plan, int fps, String srcVideoFormat)
  {
    return buildExternalReencodePipeline(baseReencodeArgv, plan, fps, srcVideoFormat, 0);
  }

  /**
   * As above, plus the raw-frame-rate cap (see the copy-family overload): when the
   * source runs faster than {@code fpsCap} the decode stage decimates to it before
   * the pipe, and the encode input {@code -framerate} is tagged with the same
   * effective rate. {@code fpsCap <= 0} disables the cap.
   */
  public static ExternalPipeline buildExternalReencodePipeline(
      java.util.List<String> baseReencodeArgv, EnhancementPlan plan, int fps, String srcVideoFormat,
      int fpsCap)
  {
    return buildExternalReencodePipeline(baseReencodeArgv, plan, fps, srcVideoFormat, fpsCap, fps);
  }

  /**
   * As above, plus the <b>exact</b> source frame rate so the encode
   * {@code -framerate} carries the true broadcast rational (29.97 -> 30000/1001)
   * rather than a drifting rounded integer, and so bob (field-rate) deinterlace
   * doubles the effective rate for an interlaced source. Pass {@code exactFps <= 0}
   * to fall back to the integer {@code fps}.
   */
  public static ExternalPipeline buildExternalReencodePipeline(
      java.util.List<String> baseReencodeArgv, EnhancementPlan plan, int fps, String srcVideoFormat,
      int fpsCap, double exactFps)
  {
    return buildExternalReencodePipeline(
        baseReencodeArgv, plan, fps, srcVideoFormat, fpsCap, exactFps, null, 0.0);
  }

  /**
   * As above, plus the audio side-channel (see the copy-family overload): when
   * {@code audioSidecarPath} is non-null the source audio is copied out of the
   * same accurately-seeked decode process to that FIFO instead of the encode
   * re-opening the file, keeping a resumed play's audio and video on one seek
   * origin. Passing {@code null}/{@code 0} preserves the legacy shape.
   */
  public static ExternalPipeline buildExternalReencodePipeline(
      java.util.List<String> baseReencodeArgv, EnhancementPlan plan, int fps, String srcVideoFormat,
      int fpsCap, double exactFps, String audioSidecarPath, double seekPrerollSec)
  {
    return buildExternalReencodePipeline(baseReencodeArgv, plan, fps, srcVideoFormat, fpsCap,
        exactFps, audioSidecarPath, seekPrerollSec, null);
  }

  /**
   * As above, plus an explicit {@code sidecarAudio}: when non-null the audio
   * side-channel transcodes (codec/bitrate/filter supplied by the caller) instead
   * of {@code -c:a copy}. Required for AC-4 sources (unmuxable by copy in the
   * pinned fork's {@code mpegts}, and needing {@code aresample=async} timing).
   */
  public static ExternalPipeline buildExternalReencodePipeline(
      java.util.List<String> baseReencodeArgv, EnhancementPlan plan, int fps, String srcVideoFormat,
      int fpsCap, double exactFps, String audioSidecarPath, double seekPrerollSec,
      SidecarAudio sidecarAudio)
  {
    if (baseReencodeArgv == null || baseReencodeArgv.size() < 3
        || plan == null || !plan.isActive())
      return null;
    sage.enhance.spi.ScaleExecutionPlan exec = plan.getScaleExec();
    if (exec == null || !exec.rendersExternalProcess()) return null;

    int iIdx = baseReencodeArgv.indexOf("-i");
    if (iIdx <= 0 || iIdx + 1 >= baseReencodeArgv.size()) return null;
    // The base command may carry a launcher prefix (e.g. "nice"); the real ffmpeg
    // executable is the first token naming ffmpeg. Everything before it is a
    // prefix preserved on both sub-stages, not the binary itself.
    int binIdx = ffmpegBinIndex(baseReencodeArgv, iIdx);
    if (binIdx < 0) return null;
    java.util.List<String> launchPrefix =
        new java.util.ArrayList<String>(baseReencodeArgv.subList(0, binIdx));
    String ffmpegBin = baseReencodeArgv.get(binIdx);

    // Require an NVENC video codec in the base output section: the browser plays
    // H.264, and re-imposing HEVC here would break MSE. Any non-nvenc encoder is
    // refused (fail-closed), matching rewriteReencodeArgv.
    int vci = indexOfVideoCodec(baseReencodeArgv, iIdx + 1);
    if (vci < 0 || vci + 1 >= baseReencodeArgv.size()) return null;
    String enc = baseReencodeArgv.get(vci + 1);
    if (enc == null || enc.toLowerCase().indexOf("nvenc") < 0) return null;

    java.util.List<String> inputSection =
        new java.util.ArrayList<String>(baseReencodeArgv.subList(binIdx + 1, iIdx + 2));
    inputSection.remove("-stdinctrl");

    String outputTarget = baseReencodeArgv.get(baseReencodeArgv.size() - 1);

    String pixFmt = exec.getPipePixelFormat();
    if (pixFmt == null || pixFmt.isEmpty()) pixFmt = "rgb24";
    int outW = exec.getOutputWidth();
    int outH = exec.getOutputHeight();
    if (outW <= 0 || outH <= 0) return null;
    if (fps <= 0) fps = 30;
    // Bob deinterlace doubles an interlaced source to its field rate before any
    // cap; decode and encode share the one exact rate (see the copy-family builder).
    double srcRate = (exactFps > 0.0) ? exactFps : (double) fps;
    int fieldMult = plan.isDeinterlace() ? 2 : 1;
    double pipeRate = srcRate * fieldMult;
    boolean decimate = (fpsCap > 0 && pipeRate > fpsCap + 1e-6);
    double effRate = decimate ? (double) fpsCap : pipeRate;
    String frTok = frameRateToken(effRate);

    // ---- Decode stage: source -> raw frames on stdout (system memory) ----
    java.util.List<String> decode = new java.util.ArrayList<String>();
    decode.addAll(launchPrefix);
    decode.add(ffmpegBin);
    // Explicit *_cuvid NVDEC decode when the source codec is known (see the
    // copy-family builder); strip any inherited -hwaccel to avoid a duplicate. An
    // interlaced source deinterlaces on the GPU, so request CUDA-resident output.
    String cuvid = cuvidDecoderFor(srcVideoFormat);
    // Audio side-channel (see the copy-family builder): when present, a SEPARATE
    // ffmpeg process copies the source audio to the FIFO after the identical
    // accurate seek and the encode reads it; the decode stays video-only either
    // way (a second decode output deadlocks against the raw video pipe). The
    // accurate seek lands the first video frame at exactly T via a select filter
    // (see appendAccurateSelect) to match the audio.
    boolean sidecar = (audioSidecarPath != null && !audioSidecarPath.isEmpty());
    SeekSplit seek = sidecar ? accurateSeekSplit(inputSection, seekPrerollSec) : null;
    boolean accurateSelect = seek != null && !seek.outputSeek.isEmpty();
    if (accurateSelect) decode.add("-copyts");   // keep source PTS so select's t is absolute
    decode.add("-hwaccel"); decode.add("cuda");
    if (externalDecodeNeedsCudaFrames(plan)) { decode.add("-hwaccel_output_format"); decode.add("cuda"); }
    if (cuvid != null) { decode.add("-c:v"); decode.add(cuvid); }
    decode.addAll(stripInputHwaccel(sidecar ? seek.decodeInput : inputSection));
    // Interlaced: GPU bob deinterlace -> hwdownload -> colorspace stamp;
    // progressive: colorspace stamp only; then decimation to the cap. See
    // externalDecodeFilters / cuvidColorspaceFixup.
    String csFix = cuvidColorspaceFixup(cuvid, pixFmt);
    java.util.List<String> vf =
        externalDecodeFilters(plan, csFix, decimate ? frTok : null);
    if (accurateSelect) appendAccurateSelect(vf, seek.targetSeconds);
    if (sidecar)
    {
      decode.add("-map"); decode.add("0:v:0");
    }
    else
    {
      decode.add("-an");
    }
    if (!vf.isEmpty()) { decode.add("-vf"); decode.add(String.join(",", vf)); }
    decode.add("-f"); decode.add("rawvideo");
    decode.add("-pix_fmt"); decode.add(pixFmt);
    decode.add("pipe:1");

    // Standalone audio process: accurate-seek copy of the source audio -> FIFO.
    // Reencode/browser path keeps the legacy shape (no source control channel).
    java.util.List<String> audioArgv = sidecar
        ? buildAudioSidecarArgv(launchPrefix, ffmpegBin, seek, "0:a?", audioSidecarPath, false,
            sidecarAudio) : null;

    // ---- Walk the base OUTPUT section: gather source audio maps (retargeted to
    // input 1) and everything worth keeping, dropping -vf / -map / inherited rate
    // control / the final output target. ----
    int outStart = iIdx + 2;
    int outEnd = baseReencodeArgv.size() - 1; // exclusive of the output target
    java.util.List<String> keptOutputOpts = new java.util.ArrayList<String>();
    java.util.List<String> audioMaps = new java.util.ArrayList<String>();
    boolean sawExplicitMap = false;
    for (int i = outStart; i < outEnd; i++)
    {
      String tok = baseReencodeArgv.get(i);
      if ("-vf".equals(tok)) { i++; continue; }            // scaling now done by the worker
      if ("-rc".equals(tok) || "-b:v".equals(tok)
          || "-maxrate".equals(tok) || "-bufsize".equals(tok)) { i++; continue; }
      if ("-stdinctrl".equals(tok)) continue;
      if ("-map".equals(tok) && i + 1 < outEnd)
      {
        sawExplicitMap = true;
        String v = baseReencodeArgv.get(++i);
        // The base video map is superseded by the worker pipe (input 0); retarget
        // any other (audio/subtitle) map from the source, now input 1.
        if (v != null && v.startsWith("0:") && !v.startsWith("0:v") && !"0:0".equals(v))
          audioMaps.add("1:" + v.substring(2));
        continue;
      }
      keptOutputOpts.add(tok);
    }

    // ---- Encode stage: worker frames (input 0) + source audio (input 1) ----
    java.util.List<String> encode = new java.util.ArrayList<String>();
    encode.addAll(launchPrefix);
    encode.add(ffmpegBin);
    // Input 0: the upscaled raw frames from the worker.
    encode.add("-f"); encode.add("rawvideo");
    encode.add("-pix_fmt"); encode.add(pixFmt);
    encode.add("-video_size"); encode.add(outW + "x" + outH);
    encode.add("-framerate"); encode.add(frTok);
    encode.add("-i"); encode.add("pipe:0");
    // Input 1: source audio. From the side-channel FIFO (already accurately
    // seeked out of the decode) when present, else the original source re-opened
    // with the same input flags (legacy shape; not seek-aligned on a resume).
    if (sidecar) { encode.add("-f"); encode.add("mpegts"); encode.add("-i"); encode.add(audioSidecarPath); }
    else encode.addAll(inputSection);
    // Video from the worker pipe; audio from the source (preserving any explicit
    // track selection, else the first audio stream). The FIFO already carries the
    // selected source audio, so the sidecar path maps all of its tracks.
    encode.add("-map"); encode.add("0:v:0");
    if (!sidecar && sawExplicitMap && !audioMaps.isEmpty())
      for (String am : audioMaps) { encode.add("-map"); encode.add(am); }
    else
      { encode.add("-map"); encode.add("1:a?"); }
    // The base output section verbatim minus filters/maps/rate: keeps h264_nvenc,
    // preset/profile/g/forced-idr, AAC audio params and the fMP4 container flags.
    encode.addAll(keptOutputOpts);
    // Re-impose the enhancement's VBR envelope (the base rate, if any, was dropped).
    long rate = plan.getBitrateKbps();
    if (rate <= 0) rate = 20000L;
    long maxrate = (plan.getBitrateCapKbps() > 0)
        ? Math.max(rate, plan.getBitrateCapKbps()) : (rate * 3L / 2L);
    encode.add("-rc"); encode.add("vbr");
    encode.add("-b:v"); encode.add(rate + "k");
    encode.add("-maxrate"); encode.add(maxrate + "k");
    encode.add("-bufsize"); encode.add((maxrate * 2L) + "k");
    if (Sage.getBoolean(PROP_SPATIAL_AQ, true)) { encode.add("-spatial_aq"); encode.add("1"); }
    if (Sage.getBoolean(PROP_TEMPORAL_AQ, true)) { encode.add("-temporal_aq"); encode.add("1"); }
    // Force 8-bit 4:2:0 output (the base -vf format=yuv420p was dropped with the
    // other filters): the worker feeds RGB frames, and NVENC would otherwise emit
    // a 4:4:4 stream the browser MSE / HW decoders can't render. The base output
    // section's -profile:v (high) is retained and is 4:2:0-compatible.
    encode.addAll(pipeOutputPixelFormatArgs(pixFmt));
    encode.add(outputTarget);

    return new ExternalPipeline(decode, encode, sidecar ? audioSidecarPath : null, audioArgv);
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

  /**
   * Impose a {@link sage.media.BitratePolicy.Plan} as VBR rate control on any
   * plain (un-enhanced) re-encode argv — the browserhd/PWA pull path and the
   * other launch-time paths that funnel through the shared policy. Strips any
   * {@code -rc}/{@code -b:v}/{@code -maxrate}/{@code -bufsize} inherited from a
   * static profile string (so the per-session values win), then inserts the
   * plan's tokens right after the video codec. {@code nvenc} adds the explicit
   * {@code -rc vbr} NVENC needs before it will honour {@code -maxrate}.
   *
   * <p>This is the launch-time (stock-ffmpeg) layer only; the live
   * {@code videorateadapt} trim, where the patched binary is present, still
   * moves within the envelope this sets.
   *
   * @return the target kbps installed, or {@code -1} if the argv had no
   *         recognisable video codec token (left untouched)
   */
  public static int applyBitratePlan(java.util.List<String> argv,
      sage.media.BitratePolicy.Plan plan, boolean nvenc)
  {
    if (argv == null || plan == null) return -1;
    int iIdx = argv.indexOf("-i");
    removeFlagPair(argv, iIdx, "-rc");
    removeFlagPair(argv, iIdx, "-b:v");
    removeFlagPair(argv, iIdx, "-maxrate");
    removeFlagPair(argv, iIdx, "-bufsize");
    int vci = indexOfVideoCodec(argv, iIdx + 1);
    if (vci < 0) return -1;
    java.util.List<String> rc = new java.util.ArrayList<String>();
    if (nvenc) { rc.add("-rc"); rc.add("vbr"); }
    rc.add("-b:v");     rc.add(plan.targetKbps + "k");
    rc.add("-maxrate"); rc.add(plan.maxrateKbps + "k");
    rc.add("-bufsize"); rc.add(plan.bufsizeKbps + "k");
    argv.addAll(vci + 2, rc);
    return plan.targetKbps;
  }

  /** Remove every {@code flag value} pair occurring strictly after {@code afterIdx}
   *  from {@code argv} (in place). Used so an active enhancement can re-assert its
   *  own rate control over whatever the base profile already set. */
  private static void removeFlagPair(java.util.List<String> argv, int afterIdx, String flag)
  {
    for (int i = argv.size() - 1; i > Math.max(-1, afterIdx); i--)
    {
      if (flag.equals(argv.get(i)))
      {
        if (i + 1 < argv.size()) argv.remove(i + 1); // value
        argv.remove(i);                              // flag
      }
    }
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
