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

/**
 * The structured, client-agnostic description of a desired offline conversion —
 * the output of the guided workflow's menus and the input to
 * {@link ConversionPlanBuilder}. Immutable; built via {@link Builder}.
 *
 * <p>Resolution is expressed as an explicit target width/height (0,0 means
 * "keep source"). Quality is expressed either as a constant-quality ladder value
 * ({@link #getQualityCq()}) or, when {@link #getMaxOutputBytes()} &gt; 0, as a
 * target file size the builder converts into an average bitrate.
 */
public final class ConversionRequest
{
  private final ConversionPurpose purpose;
  private final ContainerChoice container;
  private final VideoCodecChoice videoCodec;
  private final int targetWidth;
  private final int targetHeight;
  private final ScalingChoice scaling;
  private final DynamicRangeChoice dynamicRange;
  private final FrameRateChoice frameRate;
  private final AudioLayoutChoice audioLayout;
  private final AudioCodecChoice audioCodec;
  private final int audioBitrateKbps;
  private final SubtitleChoice subtitles;
  private final int qualityCq;
  private final long maxOutputBytes;
  private final boolean faststart;
  private final boolean keepChapters;

  private ConversionRequest(Builder b)
  {
    this.purpose = b.purpose;
    this.container = b.container;
    this.videoCodec = b.videoCodec;
    this.targetWidth = b.targetWidth;
    this.targetHeight = b.targetHeight;
    this.scaling = b.scaling;
    this.dynamicRange = b.dynamicRange;
    this.frameRate = b.frameRate;
    this.audioLayout = b.audioLayout;
    this.audioCodec = b.audioCodec;
    this.audioBitrateKbps = b.audioBitrateKbps;
    this.subtitles = b.subtitles;
    this.qualityCq = b.qualityCq;
    this.maxOutputBytes = b.maxOutputBytes;
    this.faststart = b.faststart;
    this.keepChapters = b.keepChapters;
  }

  public ConversionPurpose getPurpose() { return purpose; }
  public ContainerChoice getContainer() { return container; }
  public VideoCodecChoice getVideoCodec() { return videoCodec; }
  public int getTargetWidth() { return targetWidth; }
  public int getTargetHeight() { return targetHeight; }
  public ScalingChoice getScaling() { return scaling; }
  public DynamicRangeChoice getDynamicRange() { return dynamicRange; }
  public FrameRateChoice getFrameRate() { return frameRate; }
  public AudioLayoutChoice getAudioLayout() { return audioLayout; }
  public AudioCodecChoice getAudioCodec() { return audioCodec; }
  public int getAudioBitrateKbps() { return audioBitrateKbps; }
  public SubtitleChoice getSubtitles() { return subtitles; }
  public int getQualityCq() { return qualityCq; }
  public long getMaxOutputBytes() { return maxOutputBytes; }
  public boolean isFaststart() { return faststart; }
  public boolean isKeepChapters() { return keepChapters; }

  public boolean hasExplicitTargetSize()
  {
    return targetWidth > 0 && targetHeight > 0;
  }

  public static Builder builder()
  {
    return new Builder();
  }

  public static final class Builder
  {
    private ConversionPurpose purpose = ConversionPurpose.CUSTOM;
    private ContainerChoice container = ContainerChoice.MP4;
    private VideoCodecChoice videoCodec = VideoCodecChoice.H264;
    private int targetWidth = 0;
    private int targetHeight = 0;
    private ScalingChoice scaling = ScalingChoice.NONE;
    private DynamicRangeChoice dynamicRange = DynamicRangeChoice.AUTO;
    private FrameRateChoice frameRate = FrameRateChoice.KEEP;
    private AudioLayoutChoice audioLayout = AudioLayoutChoice.KEEP;
    private AudioCodecChoice audioCodec = AudioCodecChoice.AAC;
    private int audioBitrateKbps = 160;
    private SubtitleChoice subtitles = SubtitleChoice.COPY;
    private int qualityCq = 23;
    private long maxOutputBytes = 0;
    private boolean faststart = true;
    private boolean keepChapters = true;

    public Builder purpose(ConversionPurpose v) { this.purpose = v == null ? ConversionPurpose.CUSTOM : v; return this; }
    public Builder container(ContainerChoice v) { this.container = v == null ? ContainerChoice.MP4 : v; return this; }
    public Builder videoCodec(VideoCodecChoice v) { this.videoCodec = v == null ? VideoCodecChoice.H264 : v; return this; }
    public Builder targetSize(int w, int h) { this.targetWidth = w; this.targetHeight = h; return this; }
    public Builder scaling(ScalingChoice v) { this.scaling = v == null ? ScalingChoice.NONE : v; return this; }
    public Builder dynamicRange(DynamicRangeChoice v) { this.dynamicRange = v == null ? DynamicRangeChoice.AUTO : v; return this; }
    public Builder frameRate(FrameRateChoice v) { this.frameRate = v == null ? FrameRateChoice.KEEP : v; return this; }
    public Builder audioLayout(AudioLayoutChoice v) { this.audioLayout = v == null ? AudioLayoutChoice.KEEP : v; return this; }
    public Builder audioCodec(AudioCodecChoice v) { this.audioCodec = v == null ? AudioCodecChoice.AAC : v; return this; }
    public Builder audioBitrateKbps(int v) { this.audioBitrateKbps = v; return this; }
    public Builder subtitles(SubtitleChoice v) { this.subtitles = v == null ? SubtitleChoice.COPY : v; return this; }
    public Builder qualityCq(int v) { this.qualityCq = v; return this; }
    public Builder maxOutputBytes(long v) { this.maxOutputBytes = v; return this; }
    public Builder faststart(boolean v) { this.faststart = v; return this; }
    public Builder keepChapters(boolean v) { this.keepChapters = v; return this; }

    public ConversionRequest build() { return new ConversionRequest(this); }
  }
}
