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
 * A normalized description of a wanted output, used to match an existing
 * derivative for reuse. Two derivatives are interchangeable for reuse when they
 * share the same container, video codec, geometry, rounded frame rate and HDR
 * flag — the visible, deliverable characteristics. Bitrate/quality is
 * intentionally excluded: re-running solely for a slightly different quality
 * target is rarely worth another full encode.
 */
public final class DerivativeSpec
{
  private final String containerMuxer;
  private final String videoCodec;
  private final int width;
  private final int height;
  private final int fpsRounded;
  private final boolean hdr;

  public DerivativeSpec(String containerMuxer, String videoCodec, int width, int height,
      double fps, boolean hdr)
  {
    this.containerMuxer = norm(containerMuxer);
    this.videoCodec = norm(videoCodec);
    this.width = width;
    this.height = height;
    this.fpsRounded = (int) Math.round(fps);
    this.hdr = hdr;
  }

  /** Build the spec a plan would produce for a given source (deliverable identity). */
  public static DerivativeSpec of(ConversionPlan plan, boolean hdr)
  {
    return new DerivativeSpec(plan.getMuxer(), planVideoCodec(plan), plan.getTargetWidth(),
        plan.getTargetHeight(), plan.getTargetFps(), hdr);
  }

  private static String planVideoCodec(ConversionPlan plan)
  {
    String a = plan.getVideoArgs();
    if (a == null) return "";
    if (a.contains("av1")) return "av1";
    if (a.contains("hevc") || a.contains("265")) return "hevc";
    if (a.contains("264")) return "h264";
    if (plan.isVideoStreamCopy()) return "copy";
    return "";
  }

  private static String norm(String s)
  {
    return s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT);
  }

  public boolean matches(DerivativeRecord d)
  {
    if (d == null) return false;
    return width == d.getWidth()
        && height == d.getHeight()
        && fpsRounded == (int) Math.round(d.getFps())
        && hdr == d.isHdr()
        && norm(d.getContainerMuxer()).equals(containerMuxer)
        && codecEquiv(norm(d.getVideoCodec()), videoCodec);
  }

  private static boolean codecEquiv(String a, String b)
  {
    return canonCodec(a).equals(canonCodec(b));
  }

  private static String canonCodec(String c)
  {
    if (c == null) return "";
    if (c.contains("av1")) return "av1";
    if (c.contains("hevc") || c.contains("265")) return "hevc";
    if (c.contains("264") || c.contains("avc")) return "h264";
    return c;
  }

  public String getContainerMuxer() { return containerMuxer; }
  public String getVideoCodec() { return videoCodec; }
  public int getWidth() { return width; }
  public int getHeight() { return height; }
  public int getFpsRounded() { return fpsRounded; }
  public boolean isHdr() { return hdr; }
}
