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
 *   <li>The scaler is an abstraction on purpose. {@code scale_npp} is preferred
 *       for its Lanczos kernel, but it needs {@code --enable-libnpp}, which
 *       NVIDIA advises against on CUDA past 12.8; {@code scale_cuda} is the
 *       fallback. Never assume a specific one is present.</li>
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

    return new EnhancementPlan(tier, deint != null, deint, scaler,
        tier.getTargetWidth(), tier.getTargetHeight(), rate, cap, "built");
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
      sb.append(plan.getScaler()).append('=')
        .append(plan.getTargetWidth()).append(':').append(plan.getTargetHeight());
      if ("scale_npp".equals(plan.getScaler())) sb.append(":interp_algo=lanczos");
    }
    return (sb.length() == 0) ? null : sb.toString();
  }

  /**
   * Encoder args for the enhanced output.
   *
   * <p>{@code -bf 0} and a short GOP are not arbitrary: they match what the
   * existing push and HLS branches already do, keeping live latency and trickplay
   * behavior consistent rather than making enhanced streams behave differently
   * from every other live stream.
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
   */
  public static long suggestBitrateKbps(EnhancementTier tier, int fps, long sourceBitrateKbps)
  {
    boolean highMotion = (fps >= 50);
    long base;
    switch (tier)
    {
      case ENHANCE_2160P: base = highMotion ? 40000L : 25000L; break;
      case ENHANCE_1440P: base = highMotion ? 24000L : 16000L; break;
      case ENHANCE_1080P: base = highMotion ? 14000L : 9000L;  break;
      case DEINTERLACE_ONLY:
        // Not rescaling, so the source's own bitrate is the best anchor we have.
        base = (sourceBitrateKbps > 0) ? Math.max(6000L, sourceBitrateKbps) : 8000L;
        break;
      default: return 0L;
    }
    long cap = Sage.getLong(PROP_MAX_BITRATE, 0L);
    if (cap > 0 && base > cap) base = cap;
    return base;
  }
}
