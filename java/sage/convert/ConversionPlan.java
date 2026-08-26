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

import java.util.Collections;
import java.util.List;

/**
 * The fully-resolved conversion produced by {@link ConversionPlanBuilder}: the
 * concrete ffmpeg command pieces, a human-readable operations list and output
 * summary for the guided workflow's Review step, and a size estimate.
 *
 * <p>{@link #getFormatSpec()} is the raw-cmdline metadata string
 * ({@code f=<muxer>;MRawCmdlineGlobal=<global>;MRawCmdline=<args>;}) consumed by
 * {@code Ministry}/{@code FFMPEGTranscoder} — identical in shape to what the
 * static preset files materialize into {@code transcoder/formats/*}.
 */
public final class ConversionPlan
{
  private final String muxer;
  private final String globalArgs;
  private final String videoArgs;
  private final String formatSpec;
  private final List<String> operations;
  private final String summary;
  private final int targetWidth;
  private final int targetHeight;
  private final double targetFps;
  private final boolean videoStreamCopy;
  private final int estimatedVideoKbps;
  private final int estimatedAudioKbps;
  private final boolean aiEnhancement;
  private final boolean deinterlaceBeforeAi;

  ConversionPlan(String muxer, String globalArgs, String videoArgs, String formatSpec,
      List<String> operations, String summary, int targetWidth, int targetHeight,
      double targetFps, boolean videoStreamCopy, int estimatedVideoKbps, int estimatedAudioKbps)
  {
    this(muxer, globalArgs, videoArgs, formatSpec, operations, summary, targetWidth, targetHeight,
        targetFps, videoStreamCopy, estimatedVideoKbps, estimatedAudioKbps, false, false);
  }

  ConversionPlan(String muxer, String globalArgs, String videoArgs, String formatSpec,
      List<String> operations, String summary, int targetWidth, int targetHeight,
      double targetFps, boolean videoStreamCopy, int estimatedVideoKbps, int estimatedAudioKbps,
      boolean aiEnhancement, boolean deinterlaceBeforeAi)
  {
    this.muxer = muxer;
    this.globalArgs = globalArgs;
    this.videoArgs = videoArgs;
    this.formatSpec = formatSpec;
    this.operations = Collections.unmodifiableList(operations);
    this.summary = summary;
    this.targetWidth = targetWidth;
    this.targetHeight = targetHeight;
    this.targetFps = targetFps;
    this.videoStreamCopy = videoStreamCopy;
    this.estimatedVideoKbps = estimatedVideoKbps;
    this.estimatedAudioKbps = estimatedAudioKbps;
    this.aiEnhancement = aiEnhancement;
    this.deinterlaceBeforeAi = deinterlaceBeforeAi;
  }

  /** ffmpeg muxer/container name (e.g. {@code mp4}, {@code matroska}). */
  public String getMuxer() { return muxer; }

  /** Global (pre-input) ffmpeg args, e.g. {@code -hwaccel cuda ...}; may be empty. */
  public String getGlobalArgs() { return globalArgs; }

  /** Per-output ffmpeg args (filters, encoders, bitrates, muxer flags). */
  public String getVideoArgs() { return videoArgs; }

  /** The {@code f=...;MRawCmdlineGlobal=...;MRawCmdline=...;} spec for {@code transcoder/formats/*}. */
  public String getFormatSpec() { return formatSpec; }

  /** Ordered, human-readable operations for the Review step. */
  public List<String> getOperations() { return operations; }

  /** One-line resolved-output summary (container/codec/res/fps/audio). */
  public String getSummary() { return summary; }

  public int getTargetWidth() { return targetWidth; }
  public int getTargetHeight() { return targetHeight; }
  public double getTargetFps() { return targetFps; }
  public boolean isVideoStreamCopy() { return videoStreamCopy; }
  public int getEstimatedVideoKbps() { return estimatedVideoKbps; }
  public int getEstimatedAudioKbps() { return estimatedAudioKbps; }
  public int getEstimatedTotalKbps() { return estimatedVideoKbps + estimatedAudioKbps; }

  /**
   * True when this plan requires the offline AI-enhancement (upscale) pre-phase.
   * The orchestrator ({@code Ministry}) runs the AI provider on the original
   * decoded frames first (see {@link #isDeinterlaceBeforeAi()}), then executes
   * this plan's single ffmpeg command as the "encode once" final step. When this
   * is set the plan's {@code -vf} chain deliberately carries no scale filter,
   * because the AI phase has already produced target-resolution frames.
   */
  public boolean isAiEnhancement() { return aiEnhancement; }

  /**
   * True when the AI pre-phase must deinterlace the source before enhancement
   * (interlaced source). Deinterlace-before-AI is a hard ordering guarantee: AI
   * is never fed interlaced or Lanczos-upscaled frames.
   */
  public boolean isDeinterlaceBeforeAi() { return deinterlaceBeforeAi; }

  /**
   * Estimated output size in bytes for a given content duration. This is an
   * estimate: VBR and AI variance mean the actual size can differ, so callers
   * that enforce a hard budget should keep a safety margin.
   */
  public long estimateBytes(long durationMillis)
  {
    if (durationMillis <= 0) return 0L;
    double seconds = durationMillis / 1000.0;
    double totalBits = (double) getEstimatedTotalKbps() * 1000.0 * seconds;
    return (long) (totalBits / 8.0);
  }

  @Override
  public String toString()
  {
    return "ConversionPlan[" + summary + "; ~" + getEstimatedTotalKbps() + " kbps]";
  }
}
