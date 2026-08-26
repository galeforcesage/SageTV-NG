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
 * The resolved encoder/filter capabilities the {@link ConversionPlanBuilder}
 * builds against. The caller (Ministry glue) populates this from
 * {@link sage.HwEncoder} for the concrete ffmpeg binary and GPU in play; the
 * builder itself performs no hardware probing, which keeps it deterministic and
 * unit-testable.
 */
public final class ConversionEngineCaps
{
  private final String videoEncoderName;
  private final boolean nvenc;
  private final String scalerFilter;
  private final boolean scalerSupportsLanczos;
  private final boolean supportsFpsMax;
  private final String deinterlacer;

  private ConversionEngineCaps(Builder b)
  {
    this.videoEncoderName = b.videoEncoderName;
    this.nvenc = b.nvenc;
    this.scalerFilter = b.scalerFilter;
    this.scalerSupportsLanczos = b.scalerSupportsLanczos;
    this.supportsFpsMax = b.supportsFpsMax;
    this.deinterlacer = b.deinterlacer;
  }

  /** The resolved video encoder name (e.g. {@code h264_nvenc}, {@code libx265}); may be {@code null} for stream-copy. */
  public String getVideoEncoderName() { return videoEncoderName; }

  /** Whether the chosen video encoder is NVENC (drives CUDA hwaccel + {@code -cq}/{@code -preset pN}). */
  public boolean isNvenc() { return nvenc; }

  /** The scale filter name to use ({@code scale_npp}, {@code scale_cuda}, or {@code scale}); may be {@code null} if none. */
  public String getScalerFilter() { return scalerFilter; }

  /** Whether {@link #getScalerFilter()} can do Lanczos (vs bilinear only). */
  public boolean scalerSupportsLanczos() { return scalerSupportsLanczos; }

  /** Whether the ffmpeg binary supports the {@code -fpsmax} output option. */
  public boolean supportsFpsMax() { return supportsFpsMax; }

  /**
   * The deinterlace filter to use on the GPU/CUDA filter path (e.g.
   * {@code yadif_cuda}, {@code bwdif_cuda}); the software path always uses the
   * plain {@code yadif}. Defaults to {@code yadif_cuda}.
   */
  public String getDeinterlacer() { return deinterlacer; }

  public boolean isGpuScaler()
  {
    return scalerFilter != null
        && (scalerFilter.equals("scale_npp") || scalerFilter.equals("scale_cuda"));
  }

  public static Builder builder()
  {
    return new Builder();
  }

  public static final class Builder
  {
    private String videoEncoderName;
    private boolean nvenc;
    private String scalerFilter = "scale";
    private boolean scalerSupportsLanczos = true;
    private boolean supportsFpsMax = true;
    private String deinterlacer = "yadif_cuda";

    public Builder videoEncoderName(String v) { this.videoEncoderName = v; return this; }
    public Builder nvenc(boolean v) { this.nvenc = v; return this; }
    public Builder scalerFilter(String v) { this.scalerFilter = v; return this; }
    public Builder scalerSupportsLanczos(boolean v) { this.scalerSupportsLanczos = v; return this; }
    public Builder supportsFpsMax(boolean v) { this.supportsFpsMax = v; return this; }
    public Builder deinterlacer(String v) { this.deinterlacer = v; return this; }

    public ConversionEngineCaps build() { return new ConversionEngineCaps(this); }
  }
}
