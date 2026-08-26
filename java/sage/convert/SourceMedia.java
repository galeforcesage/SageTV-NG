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
 * An immutable snapshot of the source media properties the conversion builder
 * needs. Deliberately decoupled from {@link sage.media.format.ContainerFormat}
 * so the builder can be unit-tested without constructing a full parsed format,
 * and so a future ffprobe-backed inspector can populate the same fields.
 */
public final class SourceMedia
{
  private final int width;
  private final int height;
  private final double fps;
  private final boolean interlaced;
  private final boolean hdr;
  private final String colorspace;
  private final String videoCodec;
  private final int audioChannels;
  private final String audioCodec;
  private final long durationMillis;
  private final String containerMuxer;

  private SourceMedia(Builder b)
  {
    this.width = b.width;
    this.height = b.height;
    this.fps = b.fps;
    this.interlaced = b.interlaced;
    this.hdr = b.hdr;
    this.colorspace = b.colorspace;
    this.videoCodec = b.videoCodec;
    this.audioChannels = b.audioChannels;
    this.audioCodec = b.audioCodec;
    this.durationMillis = b.durationMillis;
    this.containerMuxer = b.containerMuxer;
  }

  public int getWidth() { return width; }
  public int getHeight() { return height; }
  public double getFps() { return fps; }
  public boolean isInterlaced() { return interlaced; }
  public boolean isHdr() { return hdr; }
  public String getColorspace() { return colorspace; }
  public String getVideoCodec() { return videoCodec; }
  public int getAudioChannels() { return audioChannels; }
  public String getAudioCodec() { return audioCodec; }
  public long getDurationMillis() { return durationMillis; }
  public String getContainerMuxer() { return containerMuxer; }

  public boolean hasSurroundAudio() { return audioChannels >= 6; }

  /**
   * Build a {@link SourceMedia} from a parsed {@link sage.media.format.ContainerFormat}.
   * HDR is inferred conservatively from the primary video colorspace string
   * (a BT.2020 colorspace); the caller may override via the builder when a
   * transfer-characteristic probe is available.
   */
  public static SourceMedia from(sage.media.format.ContainerFormat cf)
  {
    Builder b = builder();
    if (cf == null) return b.build();
    b.containerMuxer(cf.getFormatName());
    b.durationMillis(cf.getDuration());
    sage.media.format.VideoFormat vf = cf.getVideoFormat();
    if (vf != null)
    {
      b.width(vf.getWidth());
      b.height(vf.getHeight());
      b.fps(vf.getFps());
      b.interlaced(vf.isInterlaced());
      b.colorspace(vf.getColorspace());
      b.videoCodec(vf.getFormatName());
      String cs = vf.getColorspace();
      if (cs != null && cs.toLowerCase(java.util.Locale.ROOT).indexOf("2020") != -1)
        b.hdr(true);
    }
    sage.media.format.AudioFormat af = cf.getAudioFormat();
    if (af != null)
    {
      b.audioChannels(af.getChannels());
      b.audioCodec(af.getFormatName());
    }
    return b.build();
  }

  public static Builder builder()
  {
    return new Builder();
  }

  public static final class Builder
  {
    private int width;
    private int height;
    private double fps;
    private boolean interlaced;
    private boolean hdr;
    private String colorspace;
    private String videoCodec;
    private int audioChannels;
    private String audioCodec;
    private long durationMillis;
    private String containerMuxer;

    public Builder width(int v) { this.width = v; return this; }
    public Builder height(int v) { this.height = v; return this; }
    public Builder fps(double v) { this.fps = v; return this; }
    public Builder interlaced(boolean v) { this.interlaced = v; return this; }
    public Builder hdr(boolean v) { this.hdr = v; return this; }
    public Builder colorspace(String v) { this.colorspace = v; return this; }
    public Builder videoCodec(String v) { this.videoCodec = v; return this; }
    public Builder audioChannels(int v) { this.audioChannels = v; return this; }
    public Builder audioCodec(String v) { this.audioCodec = v; return this; }
    public Builder durationMillis(long v) { this.durationMillis = v; return this; }
    public Builder containerMuxer(String v) { this.containerMuxer = v; return this; }

    public SourceMedia build() { return new SourceMedia(this); }
  }
}
