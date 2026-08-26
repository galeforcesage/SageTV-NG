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
package sage.convert.guided;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import sage.convert.AudioCodecChoice;
import sage.convert.AudioLayoutChoice;
import sage.convert.ContainerChoice;
import sage.convert.ConversionEngineCaps;
import sage.convert.ConversionPlan;
import sage.convert.ConversionPlanBuilder;
import sage.convert.ConversionPurpose;
import sage.convert.ConversionRequest;
import sage.convert.DynamicRangeChoice;
import sage.convert.FrameRateChoice;
import sage.convert.ScalingChoice;
import sage.convert.SourceMedia;
import sage.convert.SubtitleChoice;
import sage.convert.VideoCodecChoice;

/**
 * Turns the guided front-door answers ({@link GuidedInputs}) into a single
 * scored {@link Recommendation}: a fully-resolved {@link ConversionRequest}, the
 * built {@link ConversionPlan} (reusing {@link ConversionPlanBuilder} so the
 * exact ffmpeg command and size estimate are identical to a hand-built request),
 * a plain-language rationale, and a list of {@link Conflict}s.
 *
 * <p>The recommendation is always device-<em>compatible</em> by construction; it
 * never emits an unsupported codec/container just because a goal asked for it.
 * Only manual {@link GuidedInputs.Overrides} can push the output into an
 * {@link Conflict.Severity#UNVERIFIED} or {@link Conflict.Severity#INCOMPATIBLE}
 * state — and even then the recommender reports rather than silently rewrites, in
 * keeping with the workflow's "warn, don't unnecessarily prohibit" rule.
 */
public final class GuidedRecommender
{
  private GuidedRecommender() { }

  /**
   * Convenience overload that resolves host encoder/filter caps to match the
   * codec the recommendation itself chooses. Runs the (pure, cheap) resolution
   * once to learn the codec, then again against caps for that codec so the built
   * plan carries the correct encoder. Used by the live Catbert draft API; unit
   * tests use {@link #recommend(GuidedInputs, ConversionEngineCaps)} with fixed
   * caps for determinism.
   */
  public static Recommendation recommend(GuidedInputs in)
  {
    if (in == null) throw new IllegalArgumentException("inputs are null");
    ConversionEngineCaps probe =
        sage.convert.ConversionCapsResolver.resolve(VideoCodecChoice.H264, false);
    Recommendation first = recommend(in, probe);
    VideoCodecChoice chosen = first.getRequest().getVideoCodec();
    if (chosen == VideoCodecChoice.H264 || chosen == VideoCodecChoice.COPY)
      return first;
    ConversionEngineCaps caps = sage.convert.ConversionCapsResolver.resolve(chosen, false);
    return recommend(in, caps);
  }

  public static Recommendation recommend(GuidedInputs in, ConversionEngineCaps caps)
  {
    if (in == null) throw new IllegalArgumentException("inputs are null");
    if (caps == null) throw new IllegalArgumentException("caps are null");

    final SourceMedia src = in.getSource();
    final DeviceProfile dev = in.getDevice();
    final List<String> why = new ArrayList<String>();

    // ---- Fold goals + priority + preferences into intent flags --------------
    final boolean exactBackup = in.has(CreationGoal.EXACT_BACKUP);
    final boolean wantUpscale = in.has(CreationGoal.IMPROVE_UPSCALE);
    final boolean favorite    = in.has(CreationGoal.REUSABLE_FAVORITE);
    final boolean reduceStorage = in.has(CreationGoal.REDUCE_STORAGE);
    final boolean preserveResFps = in.has(CreationGoal.PRESERVE_RES_FPS)
        || in.getPriority() == QualityPriority.PRESERVE_SOURCE;
    final boolean preserveSurround = in.has(CreationGoal.PRESERVE_SURROUND) || in.isPreserveSurround();
    final boolean preserveHdr = in.has(CreationGoal.PRESERVE_HDR) || in.isPreserveHdr();
    final boolean preferCompat = in.has(CreationGoal.PREFER_COMPAT)
        || in.getPriority() == QualityPriority.MAX_COMPAT;
    final boolean preferSmallest = in.has(CreationGoal.PREFER_SMALLEST)
        || in.getPriority() == QualityPriority.SMALLER;
    final boolean smoothMotion = in.isPreserveSmoothMotion() || preserveResFps;
    final boolean keepSubs = in.isKeepSubtitles() || in.has(CreationGoal.INCLUDE_SUBTITLES);
    final boolean fastest = in.getPriority() == QualityPriority.FASTEST;
    final int pressure = in.getTransfer().pressure();

    final boolean usbTv = in.has(CreationGoal.USB_TV_PLAYBACK);
    final boolean phone = in.has(CreationGoal.PHONE_OFFLINE);
    final boolean tablet = in.has(CreationGoal.TABLET_OFFLINE);
    final boolean wanSmall = in.has(CreationGoal.WAN_SMALLER);
    final boolean portable = usbTv || phone || tablet || wanSmall;

    // ---- 1. Resolution & scaling -------------------------------------------
    int tw = 0, th = 0;                 // 0,0 = keep source
    ScalingChoice scaling = ScalingChoice.NONE;
    if (exactBackup || preserveResFps)
    {
      scaling = ScalingChoice.NONE;
      why.add("Keeping the source resolution and frame rate");
    }
    else if (wantUpscale)
    {
      int[] up = upscaleTarget(src, dev, favorite);
      if (up != null && (up[0] > src.getWidth() || up[1] > src.getHeight()))
      {
        tw = up[0]; th = up[1];
        scaling = fastest ? ScalingChoice.LANCZOS : ScalingChoice.AI;
        why.add((scaling == ScalingChoice.AI ? "AI-enhance" : "Upscale") + " to " + tw + "x" + th
            + (favorite ? " (enhanced favorite)" : ""));
      }
    }
    else if (portable)
    {
      int ceil = usbTv ? 1080 : (pressure >= 3 || wanSmall || phone ? 720 : 1080);
      int[] down = downscaleTarget(src, ceil);
      if (down != null)
      {
        tw = down[0]; th = down[1];
        scaling = ScalingChoice.LANCZOS;
        why.add("Downscale to " + tw + "x" + th + " for the portable target");
      }
    }

    // Never exceed the target display.
    if (tw > 0 && th > 0 && dev.exceedsDisplay(tw, th) && dev.getMaxWidth() > 0)
    {
      tw = dev.getMaxWidth();
      th = dev.getMaxHeight();
      why.add("Capped to the device display (" + tw + "x" + th + ")");
    }

    // ---- 2. Video codec -----------------------------------------------------
    VideoCodecChoice videoCodec;
    boolean canCopy = (fastest || in.isAvoidReencode()) && scaling == ScalingChoice.NONE;
    VideoCodecChoice srcChoice = srcVideoChoice(src);
    if (exactBackup)
    {
      videoCodec = VideoCodecChoice.COPY;
    }
    else if (canCopy && srcChoice != null && supportsVideo(dev, srcChoice) && !preserveHdr)
    {
      videoCodec = VideoCodecChoice.COPY;
      why.add("Copying the source video unchanged (no re-encode)");
    }
    else if (preferCompat)
    {
      videoCodec = VideoCodecChoice.H264;
      why.add("H.264 chosen for maximum compatibility");
    }
    else if (preserveHdr)
    {
      videoCodec = VideoCodecChoice.HEVC;
      why.add("HEVC chosen so HDR can be preserved");
    }
    else if (preferSmallest)
    {
      if (supportsVideo(dev, VideoCodecChoice.AV1))
      {
        videoCodec = VideoCodecChoice.AV1;
        why.add("AV1 chosen for the smallest practical file");
      }
      else
      {
        videoCodec = VideoCodecChoice.HEVC;
        why.add("HEVC chosen for a small file");
      }
    }
    else if (portable)
    {
      videoCodec = VideoCodecChoice.H264;
      why.add("H.264 chosen for broad device compatibility");
    }
    else if (reduceStorage || favorite || in.getPriority() == QualityPriority.BEST_PICTURE)
    {
      videoCodec = VideoCodecChoice.HEVC;
      why.add("HEVC chosen to save space at the same quality");
    }
    else
    {
      videoCodec = VideoCodecChoice.H264;
    }
    // Keep the recommendation device-compatible: fall back to a supported codec.
    if (videoCodec != VideoCodecChoice.COPY && !supportsVideo(dev, videoCodec))
    {
      VideoCodecChoice fb = firstSupportedVideo(dev);
      if (fb != null && fb != videoCodec)
      {
        why.add(videoCodec + " not listed for " + dev.getName() + "; using " + fb);
        videoCodec = fb;
      }
    }

    // ---- 3. Container -------------------------------------------------------
    ContainerChoice container;
    boolean wantMkv = exactBackup || preserveHdr || reduceStorage || favorite
        || videoCodec == VideoCodecChoice.AV1;
    if (wantMkv && supportsContainer(dev, ContainerChoice.MKV))
      container = ContainerChoice.MKV;
    else
      container = ContainerChoice.MP4;

    // ---- 4. Frame rate ------------------------------------------------------
    FrameRateChoice frameRate;
    if (videoCodec == VideoCodecChoice.COPY || exactBackup || smoothMotion || preserveResFps)
      frameRate = FrameRateChoice.KEEP;
    else if ((portable && (pressure >= 2 || preferSmallest)) || preferSmallest)
      frameRate = FrameRateChoice.CAP_30;
    else
      frameRate = FrameRateChoice.KEEP;

    // ---- 5. Dynamic range ---------------------------------------------------
    DynamicRangeChoice dynamicRange;
    if (exactBackup || videoCodec == VideoCodecChoice.COPY)
    {
      dynamicRange = DynamicRangeChoice.KEEP;
    }
    else if (src.isHdr() && preserveHdr)
    {
      dynamicRange = DynamicRangeChoice.PRESERVE_HDR10;
      if (videoCodec == VideoCodecChoice.H264)
      {
        videoCodec = VideoCodecChoice.HEVC;   // H.264 cannot carry HDR10 here
        why.add("Switched to HEVC to preserve HDR10");
      }
    }
    else if (src.isHdr() && portable && !dev.supportsHdr())
    {
      dynamicRange = DynamicRangeChoice.TONEMAP_SDR;
      why.add("Tone-mapping HDR to SDR for the selected player");
    }
    else
    {
      dynamicRange = DynamicRangeChoice.AUTO;
    }

    // ---- 6. Audio -----------------------------------------------------------
    AudioCodecChoice audioCodec;
    AudioLayoutChoice audioLayout;
    if (exactBackup || preserveSurround)
    {
      audioCodec = AudioCodecChoice.COPY;
      audioLayout = AudioLayoutChoice.KEEP;
      if (preserveSurround && !exactBackup) why.add("Keeping the original surround audio");
    }
    else if (portable || preferCompat || preferSmallest)
    {
      audioCodec = AudioCodecChoice.AAC;
      audioLayout = AudioLayoutChoice.STEREO;
    }
    else
    {
      audioCodec = AudioCodecChoice.COPY;
      audioLayout = AudioLayoutChoice.KEEP;
    }
    if (audioCodec != AudioCodecChoice.COPY && !supportsAudio(dev, audioCodec))
      audioCodec = AudioCodecChoice.AAC;
    int audioBitrate = audioBitrateFor(pressure);

    // ---- 7. Subtitles -------------------------------------------------------
    SubtitleChoice subtitles;
    if ((phone || wanSmall || in.getTransfer() == TransferClass.LIMITED_WAN) && !keepSubs)
      subtitles = SubtitleChoice.NONE;
    else
      subtitles = SubtitleChoice.COPY;

    // ---- 8. Quality / size target ------------------------------------------
    int cq = baseCq(in.getPriority());
    if (pressure >= 2) cq += (pressure - 1);
    if (reduceStorage) cq += 2;
    if (cq < 16) cq = 16;
    if (cq > 30) cq = 30;
    long maxBytes = 0L;
    if (in.getTransfer() == TransferClass.CUSTOM && in.getCustomBudgetBytes() > 0
        && videoCodec != VideoCodecChoice.COPY)
    {
      maxBytes = in.getCustomBudgetBytes();
      why.add("Targeting the custom size budget");
    }

    // ---- Apply manual overrides (Customize step) ----------------------------
    GuidedInputs.Overrides ov = in.getOverrides();
    if (ov != null && !ov.isEmpty())
    {
      if (ov.container != null) container = ov.container;
      if (ov.videoCodec != null) videoCodec = ov.videoCodec;
      if (ov.scaling != null) scaling = ov.scaling;
      if (ov.width != null) tw = ov.width.intValue();
      if (ov.height != null) th = ov.height.intValue();
      if (ov.frameRate != null) frameRate = ov.frameRate;
      if (ov.audioLayout != null) audioLayout = ov.audioLayout;
      if (ov.audioCodec != null) audioCodec = ov.audioCodec;
      if (ov.audioBitrateKbps != null) audioBitrate = ov.audioBitrateKbps.intValue();
      if (ov.dynamicRange != null) dynamicRange = ov.dynamicRange;
      if (ov.subtitles != null) subtitles = ov.subtitles;
      if (ov.qualityCq != null) cq = ov.qualityCq.intValue();
      why.add("Applied manual capability overrides");
    }

    // ---- Build the resolved request ----------------------------------------
    ConversionRequest.Builder rb = ConversionRequest.builder()
        .purpose(derivePurpose(in))
        .container(container)
        .videoCodec(videoCodec)
        .scaling(scaling)
        .frameRate(frameRate)
        .dynamicRange(dynamicRange)
        .audioLayout(audioLayout)
        .audioCodec(audioCodec)
        .audioBitrateKbps(audioBitrate)
        .subtitles(subtitles)
        .qualityCq(cq)
        .maxOutputBytes(maxBytes);
    if (tw > 0 && th > 0) rb.targetSize(tw, th);
    ConversionRequest req = rb.build();

    // ---- Conflict detection -------------------------------------------------
    List<Conflict> conflicts = detectConflicts(in, req, src, dev);

    // ---- Build the plan (reuse the engine) ---------------------------------
    ConversionPlan plan = null;
    long estBytes = 0L;
    try
    {
      plan = ConversionPlanBuilder.build(req, src, caps);
      estBytes = plan.estimateBytes(src.getDurationMillis());
    }
    catch (RuntimeException e)
    {
      if (!hasBlocking(conflicts))
        conflicts.add(new Conflict(Conflict.Severity.INCOMPATIBLE,
            e.getMessage() == null ? "This combination is not supported." : e.getMessage(),
            null));
    }

    return new Recommendation(req, plan, why, conflicts, estBytes);
  }

  // -------------------------------------------------------------------------
  //  Conflict detection
  // -------------------------------------------------------------------------

  private static List<Conflict> detectConflicts(GuidedInputs in, ConversionRequest req,
      SourceMedia src, DeviceProfile dev)
  {
    List<Conflict> out = new ArrayList<Conflict>();
    boolean copy = req.getVideoCodec().isCopy();

    // Goal-level: an exact backup and an enhance/re-encode goal can't be one file.
    if (in.has(CreationGoal.EXACT_BACKUP)
        && (in.has(CreationGoal.IMPROVE_UPSCALE) || in.has(CreationGoal.REDUCE_STORAGE)))
    {
      out.add(new Conflict(Conflict.Severity.INCOMPATIBLE,
          "An exact original backup can't also be enhanced or re-encoded in the same file.",
          Arrays.asList("Create two files (exact + enhanced)",
              "Keep the exact original only", "Enhance instead of an exact backup")));
    }

    // Exact backup cannot re-encode or resize.
    if (in.has(CreationGoal.EXACT_BACKUP)
        && (!copy || req.getScaling() != ScalingChoice.NONE))
    {
      out.add(new Conflict(Conflict.Severity.INCOMPATIBLE,
          "Exact original backup cannot include video resizing or re-encoding.",
          Arrays.asList("Preserve the exact original",
              "Create a separate enhanced copy as an additional output")));
    }

    // AI/Lanczos with stream copy is impossible.
    if (req.getScaling() != ScalingChoice.NONE && copy)
    {
      out.add(new Conflict(Conflict.Severity.INCOMPATIBLE,
          "Video resize (" + req.getScaling() + ") cannot be combined with an exact video copy.",
          Arrays.asList("Re-encode the video", "Keep the exact original only")));
    }

    // HDR10 needs HEVC/AV1.
    if (req.getDynamicRange() == DynamicRangeChoice.PRESERVE_HDR10
        && (req.getVideoCodec() == VideoCodecChoice.H264 || copy))
    {
      out.add(new Conflict(Conflict.Severity.INCOMPATIBLE,
          "Preserving HDR10 requires an HEVC or AV1 re-encode.",
          Arrays.asList("Use HEVC", "Convert HDR to SDR")));
    }

    // AI upscale must actually enlarge.
    if (req.getScaling() == ScalingChoice.AI
        && !(req.getTargetWidth() > src.getWidth() || req.getTargetHeight() > src.getHeight()))
    {
      out.add(new Conflict(Conflict.Severity.INCOMPATIBLE,
          "AI upscale requires a target larger than the source.",
          Arrays.asList("Choose a larger resolution", "Use conventional scaling")));
    }

    // Device compatibility — advisory (UNVERIFIED), never silently changed.
    if (!dev.isUnknown())
    {
      if (!copy && !supportsVideo(dev, req.getVideoCodec()))
        out.add(new Conflict(Conflict.Severity.UNVERIFIED,
            req.getVideoCodec() + " is supported by SageTV-NG but not reported by " + dev.getName() + ".",
            Arrays.asList("Keep " + req.getVideoCodec(), "Use a device-supported codec")));
      if (!supportsContainer(dev, req.getContainer()))
        out.add(new Conflict(Conflict.Severity.UNVERIFIED,
            req.getContainer() + " container is not reported by " + dev.getName() + ".",
            Arrays.asList("Keep " + req.getContainer(), "Change container")));
      if (!req.getAudioCodec().isCopy() && !supportsAudio(dev, req.getAudioCodec()))
        out.add(new Conflict(Conflict.Severity.UNVERIFIED,
            req.getAudioCodec() + " audio is not reported by " + dev.getName() + ".", null));
      if (req.getTargetWidth() > 0 && dev.exceedsDisplay(req.getTargetWidth(), req.getTargetHeight()))
        out.add(new Conflict(Conflict.Severity.UNVERIFIED,
            "Output resolution exceeds the device display.", null));
      if (src.isHdr() && req.getDynamicRange() == DynamicRangeChoice.PRESERVE_HDR10 && !dev.supportsHdr())
        out.add(new Conflict(Conflict.Severity.UNVERIFIED,
            "HDR is preserved but " + dev.getName() + " does not report HDR support.", null));
    }

    // Smooth-motion warning when capping a high-fps source.
    if (req.getFrameRate() == FrameRateChoice.CAP_30 && src.getFps() > 50.0)
      out.add(new Conflict(Conflict.Severity.UNVERIFIED,
          "Reducing " + fmt(src.getFps()) + " fps to ~29.97 saves space but may make fast motion less smooth.",
          Arrays.asList("Keep the source frame rate", "Accept the smaller file")));

    // Surround requested on a stereo source.
    if (req.getAudioLayout() == AudioLayoutChoice.SURROUND_51 && !src.hasSurroundAudio())
      out.add(new Conflict(Conflict.Severity.COMPATIBLE,
          "Source has no surround audio; the original layout is kept (never upmixed).", null));

    return out;
  }

  // -------------------------------------------------------------------------
  //  Helpers
  // -------------------------------------------------------------------------

  private static boolean hasBlocking(List<Conflict> cs)
  {
    for (Conflict c : cs) if (c.isBlocking()) return true;
    return false;
  }

  /** Preferred upscale target for an enhance request, capped to the device display. */
  private static int[] upscaleTarget(SourceMedia src, DeviceProfile dev, boolean favorite)
  {
    int w, h;
    if (favorite || (dev.getMaxHeight() == 0 || dev.getMaxHeight() >= 2160)) { w = 3840; h = 2160; }
    else if (dev.getMaxHeight() >= 1440) { w = 2560; h = 1440; }
    else { w = 1920; h = 1080; }
    if (dev.getMaxWidth() > 0 && w > dev.getMaxWidth()) { w = dev.getMaxWidth(); h = dev.getMaxHeight(); }
    if (w <= src.getWidth() && h <= src.getHeight()) return null;
    return new int[] { w, h };
  }

  /** 16:9 downscale target for a height ceiling, or null when the source is already smaller. */
  private static int[] downscaleTarget(SourceMedia src, int ceilingHeight)
  {
    if (src.getHeight() <= ceilingHeight) return null;
    int h = ceilingHeight;
    int w = (int) Math.round((double) src.getWidth() * h / src.getHeight());
    if ((w & 1) == 1) w++;   // keep even for yuv420
    return new int[] { w, h };
  }

  private static VideoCodecChoice srcVideoChoice(SourceMedia s)
  {
    String c = s.getVideoCodec();
    if (c == null) return null;
    c = c.toLowerCase(Locale.ROOT);
    if (c.indexOf("hevc") != -1 || c.indexOf("h265") != -1 || c.indexOf("265") != -1) return VideoCodecChoice.HEVC;
    if (c.indexOf("av1") != -1) return VideoCodecChoice.AV1;
    if (c.indexOf("264") != -1 || c.indexOf("avc") != -1) return VideoCodecChoice.H264;
    return null;   // MPEG-2 etc. must be re-encoded
  }

  private static boolean supportsVideo(DeviceProfile d, VideoCodecChoice c) { return d.supportsVideo(c); }
  private static boolean supportsContainer(DeviceProfile d, ContainerChoice c) { return d.supportsContainer(c); }
  private static boolean supportsAudio(DeviceProfile d, AudioCodecChoice c) { return d.supportsAudio(c); }

  private static VideoCodecChoice firstSupportedVideo(DeviceProfile d)
  {
    if (d.supportsVideo(VideoCodecChoice.H264)) return VideoCodecChoice.H264;
    if (d.supportsVideo(VideoCodecChoice.HEVC)) return VideoCodecChoice.HEVC;
    if (d.supportsVideo(VideoCodecChoice.AV1)) return VideoCodecChoice.AV1;
    return VideoCodecChoice.H264;
  }

  private static int audioBitrateFor(int pressure)
  {
    switch (pressure)
    {
      case 3: return 96;
      case 2: return 128;
      case 1: return 160;
      default: return 192;
    }
  }

  private static int baseCq(QualityPriority p)
  {
    switch (p)
    {
      case BEST_PICTURE: return 20;
      case SMALLER: return 25;
      case FASTEST: return 23;
      case MAX_COMPAT: return 22;
      case PRESERVE_SOURCE: return 20;
      case CUSTOM: return 23;
      case BALANCED:
      default: return 22;
    }
  }

  private static ConversionPurpose derivePurpose(GuidedInputs in)
  {
    if (in.has(CreationGoal.EXACT_BACKUP)) return ConversionPurpose.EXACT_BACKUP;
    if (in.has(CreationGoal.IMPROVE_UPSCALE) || in.has(CreationGoal.REUSABLE_FAVORITE))
      return ConversionPurpose.ENHANCED_FAVORITE;
    if (in.has(CreationGoal.REDUCE_STORAGE)) return ConversionPurpose.ARCHIVE;
    if (in.has(CreationGoal.WAN_SMALLER)) return ConversionPurpose.TRAVEL;
    if (in.has(CreationGoal.PHONE_OFFLINE) || in.has(CreationGoal.TABLET_OFFLINE))
      return ConversionPurpose.OFFLINE_DEVICE;
    if (in.has(CreationGoal.USB_TV_PLAYBACK)) return ConversionPurpose.USB_TV;
    return ConversionPurpose.CUSTOM;
  }

  private static String fmt(double fps)
  {
    if (Math.abs(fps - Math.rint(fps)) < 0.05) return Integer.toString((int) Math.rint(fps));
    return String.format(Locale.ROOT, "%.2f", fps);
  }
}
