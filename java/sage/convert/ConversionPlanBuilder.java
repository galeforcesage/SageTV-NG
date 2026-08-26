/*
 * Copyright 2015 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sage.convert;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Resolves a client-agnostic {@link ConversionRequest} plus a {@link SourceMedia}
 * snapshot and the available {@link ConversionEngineCaps} into a single concrete
 * {@link ConversionPlan}: one ffmpeg command (raw-cmdline metadata form), an
 * operations list, an output summary, and a size estimate.
 *
 * <p>This is the client-agnostic transcode engine's decision layer. It centralizes
 * choices the static preset files currently hard-code — including H.264 level
 * selection, which is computed from the resolved height and frame rate so a
 * high-fps 1080p output is never emitted at a level NVENC will reject (Level 4.0
 * caps 1080p at 30&nbsp;fps; 1080p60 requires Level 4.2).
 *
 * <p>Scope note: this first cut resolves stream-copy, H.264 and HEVC re-encodes
 * with Lanczos or no scaling, {@code -fpsmax} frame-rate capping, and audio
 * copy/AAC/AC-3/E-AC-3. AV1, AI upscaling, explicit HDR preserve/tone-map, and
 * subtitle burn-in/include-all are resolved by later engine layers and are
 * rejected here with a clear message rather than emitting an unverified command.
 */
public final class ConversionPlanBuilder
{
  // Rough bits-per-pixel-per-frame constants used only for size ESTIMATES
  // (not for the encode itself). Tuned so a 1280x720p30 H.264 output lands
  // near the ~3.0-3.5 Mbps the phone presets target.
  private static final double BPP_H264 = 0.108;
  private static final double BPP_HEVC = 0.065;
  private static final double BPP_AV1  = 0.050;

  private ConversionPlanBuilder() { }

  public static ConversionPlan build(ConversionRequest req, SourceMedia src, ConversionEngineCaps caps)
  {
    if (req == null) throw new IllegalArgumentException("request is null");
    if (src == null) throw new IllegalArgumentException("source is null");
    if (caps == null) throw new IllegalArgumentException("caps is null");

    rejectUnsupported(req);

    List<String> ops = new ArrayList<String>();
    final boolean videoCopy = req.getVideoCodec().isCopy();

    // ---- Resolved geometry & frame rate -------------------------------------
    int outW = src.getWidth();
    int outH = src.getHeight();
    boolean doScale = false;
    boolean aiEnhance = false;
    if (req.getScaling() == ScalingChoice.LANCZOS && req.hasExplicitTargetSize()
        && (req.getTargetWidth() != src.getWidth() || req.getTargetHeight() != src.getHeight()))
    {
      outW = req.getTargetWidth();
      outH = req.getTargetHeight();
      doScale = true;
    }
    else if (req.getScaling() == ScalingChoice.AI)
    {
      // AI enhancement is an upscale-only offline pre-phase. It requires an
      // explicit larger target; equal/smaller targets are a configuration error
      // (use None or Lanczos to downscale). AI is never used to downscale.
      if (!req.hasExplicitTargetSize())
        throw new IllegalArgumentException("AI enhancement requires an explicit target resolution");
      if (req.getTargetWidth() <= src.getWidth() && req.getTargetHeight() <= src.getHeight())
        throw new IllegalArgumentException("AI enhancement is upscale-only: target must exceed the source resolution");
      outW = req.getTargetWidth();
      outH = req.getTargetHeight();
      aiEnhance = true;
    }
    final boolean deinterlaceBeforeAi = aiEnhance && src.isInterlaced();

    double srcFps = src.getFps() > 0 ? src.getFps() : 30.0;
    double cap = req.getFrameRate().cappedFps();
    // -fpsmax only ever reduces: below-cap sources pass through untouched, so
    // the estimate fps is min(source, cap).
    boolean fpsReduced = cap > 0 && srcFps > cap + 0.01;
    double outFps = fpsReduced ? cap : srcFps;
    String fpsMax = req.getFrameRate().fpsMaxToken();

    // ---- Command assembly ---------------------------------------------------
    StringBuilder global = new StringBuilder();
    List<String> args = new ArrayList<String>();

    if (videoCopy)
    {
      args.add("-c:v"); args.add("copy");
      ops.add("Copy video (no re-encode)");
    }
    else
    {
      String enc = caps.getVideoEncoderName();
      if (enc == null || enc.length() == 0)
        throw new IllegalArgumentException("no video encoder resolved for " + req.getVideoCodec());

      // HDR tone-mapping runs as a software filter graph, so it forces the CPU
      // filter path (frames land in system memory); the encoder may still be
      // NVENC, which accepts system frames. Every other job can use the GPU
      // filter path when NVENC + a CUDA scaler are available.
      boolean tonemap = req.getDynamicRange() == DynamicRangeChoice.TONEMAP_SDR && src.isHdr();
      boolean useGpuFilters = caps.isNvenc() && caps.isGpuScaler() && !tonemap;

      if (useGpuFilters)
        global.append("-hwaccel cuda -hwaccel_output_format cuda");

      // The AI upscale runs as a 2-phase chained job inside FFMPEGTranscodeJob:
      // it discovers the target resolution by parsing THIS -vf scale token
      // (Ministry.parseTargetHeightFromPresetArgs -> shouldAutoAiUpscale), runs the
      // offline provider to produce target-resolution frames in phase 1, then
      // strips the scale filter for the phase-2 encode (stripScaleFilterForPhase2).
      // The scale token must therefore be PRESENT here for AI to engage at all;
      // emitting nothing silently degrades to a plain source-resolution encode.
      // Emit scale only (no deinterlace in the chain: the provider consumes the
      // original frames, and a leftover deinterlace token would wrongly run in
      // phase 2 against already-progressive, already-upscaled frames). If the
      // provider is unavailable or genre routing declines, this same scale token
      // drives a plain Lanczos upscale to the target instead of doing nothing.
      String vf;
      if (aiEnhance)
      {
        vf = scaleFilter(caps, useGpuFilters, outW, outH);
        if (deinterlaceBeforeAi)
          ops.add("Deinterlace before AI enhancement");
        ops.add("AI-enhance to " + outW + "x" + outH + " (offline provider)");
      }
      else
      {
        vf = buildFilterChain(req, src, caps, outW, outH, doScale, useGpuFilters, tonemap, ops);
      }
      if (vf != null)
      {
        args.add("-vf"); args.add(vf);
      }

      if (fpsMax != null)
      {
        args.add("-fpsmax"); args.add(fpsMax);
        if (fpsReduced)
          ops.add("Reduce frame rate to ~" + fmtFps(cap)
              + " fps (source " + fmtFps(srcFps) + ")");
      }

      args.add("-c:v"); args.add(enc);
      appendVideoRateControl(req, src, caps, outW, outH, outFps, args);
      appendProfileLevelAndHdr(req, src, outW, outH, outFps, useGpuFilters, args, ops);
      ops.add("Encode video as " + codecLabel(req.getVideoCodec())
          + (caps.isNvenc() ? " (GPU/NVENC)" : " (software)"));
    }

    appendAudio(req, src, args, ops);

    String muxer = req.getContainer().muxerName(src.getContainerMuxer());
    appendSubtitles(req, muxer, args, ops);

    if ("mp4".equals(muxer) && req.isFaststart() && !videoCopy)
    {
      args.add("-movflags"); args.add("+faststart");
    }
    ops.add("Package as " + containerLabel(req.getContainer(), muxer));

    String argStr = join(args);
    String globalStr = global.toString().trim();
    String spec = buildSpec(muxer, globalStr, argStr);

    int vKbps = estimateVideoKbps(req, src, outW, outH, outFps, videoCopy);
    int aKbps = estimateAudioKbps(req, src);
    String summary = buildSummary(req, muxer, outW, outH, outFps, videoCopy, src);

    return new ConversionPlan(muxer, globalStr, argStr, spec, ops, summary,
        outW, outH, outFps, videoCopy, vKbps, aKbps, aiEnhance, deinterlaceBeforeAi);
  }

  // -------------------------------------------------------------------------

  private static void rejectUnsupported(ConversionRequest req)
  {
    if (req.getSubtitles() == SubtitleChoice.ALL || req.getSubtitles() == SubtitleChoice.BURN_IN)
      throw new IllegalArgumentException("subtitle include-all / burn-in is resolved by the job/UI layer, not the plan builder");
    if (req.getDynamicRange() == DynamicRangeChoice.PRESERVE_HDR10
        && (req.getVideoCodec() == VideoCodecChoice.H264 || req.getVideoCodec().isCopy()))
      throw new IllegalArgumentException("HDR10 preservation requires an HEVC or AV1 re-encode (H.264/copy cannot carry HDR10 here)");
    if (req.getVideoCodec().isCopy())
    {
      if (req.getScaling() != ScalingChoice.NONE)
        throw new IllegalArgumentException("cannot rescale when the video codec is COPY");
      if (req.getFrameRate().fpsMaxToken() != null)
        throw new IllegalArgumentException("cannot change frame rate when the video codec is COPY");
      if (req.getDynamicRange() == DynamicRangeChoice.TONEMAP_SDR)
        throw new IllegalArgumentException("cannot tone-map when the video codec is COPY");
    }
  }

  /**
   * Compose the {@code -vf} filter chain in the correct order:
   * deinterlace &rarr; scale/tone-map. Deinterlace always precedes scaling so a
   * 1080i source is woven to progressive before it is resized. Returns
   * {@code null} when no filter is needed.
   */
  private static String buildFilterChain(ConversionRequest req, SourceMedia src,
      ConversionEngineCaps caps, int outW, int outH, boolean doScale,
      boolean useGpuFilters, boolean tonemap, List<String> ops)
  {
    List<String> chain = new ArrayList<String>();

    if (src.isInterlaced())
    {
      String deint = useGpuFilters ? caps.getDeinterlacer() : "yadif";
      if (deint == null || deint.length() == 0) deint = "yadif";
      chain.add(deint);
      ops.add("Deinterlace (" + deint + ")");
    }

    if (tonemap)
    {
      // HDR10 (PQ, BT.2020) -> SDR (BT.709). Software zscale/tonemap chain; if a
      // resize is also requested, fold it into the same software scale.
      if (doScale)
      {
        chain.add("scale=" + outW + ":" + outH + ":flags=lanczos");
        ops.add((outW * outH < src.getWidth() * src.getHeight() ? "Downscale to " : "Upscale to ")
            + outW + "x" + outH + " (Lanczos)");
      }
      chain.add("zscale=t=linear:npl=100");
      chain.add("tonemap=hable");
      chain.add("zscale=t=bt709:m=bt709:p=bt709:r=tv");
      chain.add("format=yuv420p");
      ops.add("Tone-map HDR to SDR (BT.709)");
    }
    else if (doScale)
    {
      chain.add(scaleFilter(caps, useGpuFilters, outW, outH));
      ops.add((outW * outH < src.getWidth() * src.getHeight() ? "Downscale to " : "Upscale to ")
          + outW + "x" + outH + " (Lanczos)");
    }

    if (chain.isEmpty()) return null;
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < chain.size(); i++)
    {
      if (i > 0) sb.append(',');
      sb.append(chain.get(i));
    }
    return sb.toString();
  }

  private static String scaleFilter(ConversionEngineCaps caps, boolean useGpuFilters, int w, int h)
  {
    String scaler = useGpuFilters ? caps.getScalerFilter() : "scale";
    if (scaler == null || scaler.length() == 0) scaler = "scale";
    StringBuilder sb = new StringBuilder();
    sb.append(scaler).append('=').append(w).append(':').append(h);
    if (caps.scalerSupportsLanczos())
    {
      if (scaler.equals("scale")) sb.append(":flags=lanczos");
      else sb.append(":interp_algo=lanczos");
    }
    return sb.toString();
  }

  private static void appendVideoRateControl(ConversionRequest req, SourceMedia src,
      ConversionEngineCaps caps, int w, int h, double fps, List<String> args)
  {
    String swPreset = softwarePreset(caps.getVideoEncoderName());
    if (req.getMaxOutputBytes() > 0 && src.getDurationMillis() > 0)
    {
      int totalKbps = (int) ((req.getMaxOutputBytes() * 8.0) / 1000.0 / (src.getDurationMillis() / 1000.0));
      int vKbps = Math.max(200, totalKbps - estimateAudioKbps(req, src));
      if (caps.isNvenc()) { args.add("-preset"); args.add("p5"); args.add("-rc:v"); args.add("vbr"); }
      else { args.add("-preset"); args.add(swPreset); }
      args.add("-b:v"); args.add(vKbps + "k");
      args.add("-maxrate"); args.add(((int) (vKbps * 1.45)) + "k");
      args.add("-bufsize"); args.add((vKbps * 2) + "k");
    }
    else
    {
      if (caps.isNvenc())
      {
        args.add("-preset"); args.add("p5");
        args.add("-rc:v"); args.add("vbr");
        args.add("-cq:v"); args.add(Integer.toString(req.getQualityCq()));
        args.add("-b:v"); args.add("0");
      }
      else
      {
        args.add("-preset"); args.add(swPreset);
        args.add("-crf"); args.add(Integer.toString(req.getQualityCq()));
      }
    }
  }

  /** Software encoder preset token — numeric for SVT-AV1, named for x264/x265. */
  private static String softwarePreset(String enc)
  {
    if (enc != null && enc.toLowerCase(Locale.ROOT).indexOf("svtav1") != -1) return "8";
    return "medium";
  }

  private static void appendProfileLevelAndHdr(ConversionRequest req, SourceMedia src,
      int w, int h, double fps, boolean useGpuFilters, List<String> args, List<String> ops)
  {
    boolean preserveHdr = req.getDynamicRange() == DynamicRangeChoice.PRESERVE_HDR10;

    if (req.getVideoCodec() == VideoCodecChoice.H264)
    {
      args.add("-profile:v"); args.add("high");
      args.add("-level"); args.add(h264Level(w, h, fps));
    }
    else if (req.getVideoCodec() == VideoCodecChoice.HEVC)
    {
      args.add("-tag:v"); args.add("hvc1");
      if (preserveHdr) { args.add("-profile:v"); args.add("main10"); }
    }
    // AV1: no -level / -profile needed; 10-bit is selected via pixel format below.

    if (preserveHdr)
    {
      // 10-bit pixel format only on the software filter path (on the CUDA path
      // the hardware frame format already carries 10-bit and forcing -pix_fmt
      // would conflict).
      if (!useGpuFilters) { args.add("-pix_fmt"); args.add("p010le"); }
      args.add("-colorspace"); args.add("bt2020nc");
      args.add("-color_primaries"); args.add("bt2020");
      args.add("-color_trc"); args.add("smpte2084");
      ops.add("Preserve HDR10 (BT.2020 / PQ, 10-bit)");
    }
  }

  /**
   * Minimal H.264 level adequate for the resolved height and frame rate. Baked
   * in so a high-fps 1080p output is never emitted at Level 4.0/4.1 (which cap
   * 1080p at 30&nbsp;fps and would be rejected by NVENC as "Invalid Level").
   */
  static String h264Level(int w, int h, double fps)
  {
    boolean high = fps > 30.5;
    if (h <= 480) return high ? "3.1" : "3.0";
    if (h <= 576) return "3.1";
    if (h <= 720) return high ? "3.2" : "3.1";
    if (h <= 1080) return high ? "4.2" : "4.0";
    if (h <= 1440) return "5.0";
    return "5.1";
  }

  private static void appendAudio(ConversionRequest req, SourceMedia src, List<String> args, List<String> ops)
  {
    if (req.getAudioCodec().isCopy())
    {
      args.add("-c:a"); args.add("copy");
      ops.add("Copy audio (" + (src.getAudioChannels() >= 6 ? "5.1 preserved" : "unchanged") + ")");
      return;
    }
    args.add("-c:a"); args.add(req.getAudioCodec().encoderName());
    args.add("-b:a"); args.add(req.getAudioBitrateKbps() + "k");

    AudioLayoutChoice layout = req.getAudioLayout();
    int ch = layout.channels();
    if (layout == AudioLayoutChoice.SURROUND_51 && !src.hasSurroundAudio())
    {
      // Never upmix: a stereo (or mono) source can't become 5.1.
      ch = 0;
      ops.add("Encode audio as " + req.getAudioCodec()
          + " " + req.getAudioBitrateKbps() + "k (source has no surround; keeping layout)");
    }
    else
    {
      if (ch > 0) { args.add("-ac"); args.add(Integer.toString(ch)); }
      ops.add("Encode audio as " + req.getAudioCodec() + " " + req.getAudioBitrateKbps() + "k"
          + (ch == 2 ? " stereo" : ch == 6 ? " 5.1" : ""));
    }
  }

  private static void appendSubtitles(ConversionRequest req, String muxer, List<String> args, List<String> ops)
  {
    if (req.getSubtitles() == SubtitleChoice.NONE)
    {
      args.add("-sn");
      ops.add("Drop subtitles");
    }
    else if (req.getSubtitles() == SubtitleChoice.COPY)
    {
      // Container-aware text-subtitle carriage: MP4 needs mov_text; MKV/others
      // copy through. Map any subtitle streams present without failing when none
      // exist.
      args.add("-c:s"); args.add("mp4".equals(muxer) ? "mov_text" : "copy");
      ops.add("Keep subtitles");
    }
    // include-all / burn-in / CC migration are resolved by a later layer.
  }

  private static String buildSpec(String muxer, String global, String args)
  {
    StringBuilder sb = new StringBuilder();
    sb.append("f=").append(SpecEscaping.escape(muxer)).append(';');
    if (global != null && global.length() > 0)
      sb.append("MRawCmdlineGlobal=").append(SpecEscaping.escape(global)).append(';');
    sb.append("MRawCmdline=").append(SpecEscaping.escape(args)).append(';');
    return sb.toString();
  }

  private static int estimateVideoKbps(ConversionRequest req, SourceMedia src,
      int w, int h, double fps, boolean copy)
  {
    double bpp;
    switch (req.getVideoCodec())
    {
      case HEVC: bpp = BPP_HEVC; break;
      case AV1:  bpp = BPP_AV1; break;
      default:   bpp = BPP_H264; break;
    }
    if (copy)
    {
      // Unknown source bitrate: estimate from source geometry at H.264 efficiency.
      double sfps = src.getFps() > 0 ? src.getFps() : 30.0;
      return (int) (src.getWidth() * src.getHeight() * sfps * BPP_H264 / 1000.0);
    }
    if (req.getMaxOutputBytes() > 0 && src.getDurationMillis() > 0)
    {
      int totalKbps = (int) ((req.getMaxOutputBytes() * 8.0) / 1000.0 / (src.getDurationMillis() / 1000.0));
      return Math.max(200, totalKbps - estimateAudioKbps(req, src));
    }
    double f = fps > 0 ? fps : 30.0;
    return (int) (w * h * f * bpp / 1000.0);
  }

  private static int estimateAudioKbps(ConversionRequest req, SourceMedia src)
  {
    if (req.getAudioCodec().isCopy())
      return src.getAudioChannels() >= 6 ? 448 : 192;
    return req.getAudioBitrateKbps() > 0 ? req.getAudioBitrateKbps() : 160;
  }

  private static String buildSummary(ConversionRequest req, String muxer, int w, int h,
      double fps, boolean copy, SourceMedia src)
  {
    StringBuilder sb = new StringBuilder();
    sb.append(containerLabel(req.getContainer(), muxer)).append(" / ");
    sb.append(copy ? "video copy" : codecLabel(req.getVideoCodec()));
    sb.append(" ").append(w).append("x").append(h);
    sb.append(" @").append(fmtFps(fps)).append("fps / ");
    if (req.getAudioCodec().isCopy())
      sb.append("audio copy");
    else
      sb.append(req.getAudioCodec()).append(" ").append(req.getAudioBitrateKbps()).append("k");
    return sb.toString();
  }

  private static String codecLabel(VideoCodecChoice c)
  {
    switch (c)
    {
      case H264: return "H.264";
      case HEVC: return "HEVC";
      case AV1:  return "AV1";
      default:   return "copy";
    }
  }

  private static String containerLabel(ContainerChoice c, String muxer)
  {
    if ("matroska".equals(muxer)) return "MKV";
    if ("mp4".equals(muxer)) return "MP4";
    return muxer;
  }

  private static String fmtFps(double fps)
  {
    if (fps <= 0) return "?";
    if (Math.abs(fps - Math.rint(fps)) < 0.05) return Integer.toString((int) Math.rint(fps));
    return String.format(Locale.ROOT, "%.2f", fps);
  }

  private static String join(List<String> parts)
  {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < parts.size(); i++)
    {
      if (i > 0) sb.append(' ');
      sb.append(parts.get(i));
    }
    return sb.toString();
  }
}
