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

import sage.HwEncoder;

/**
 * Bridges the client-agnostic {@link ConversionPlanBuilder} to the running
 * host's real encoder/filter capabilities by resolving a
 * {@link ConversionEngineCaps} from {@link HwEncoder}. This is the seam between
 * the pure, unit-tested engine and the live ffmpeg probe; it is intentionally
 * separate so the builder stays testable without a server or an ffmpeg binary.
 *
 * <p>Resolution is fail-safe: when no hardware encoder is available for the
 * requested codec it falls back to the software encoder, and when no CUDA scaler
 * exists it reports a software scaler so the builder emits a CPU filter chain.
 */
public final class ConversionCapsResolver
{
  private ConversionCapsResolver() { }

  /** Map a {@link VideoCodecChoice} to the HwEncoder codec token. */
  static String codecToken(VideoCodecChoice c)
  {
    if (c == null) return "h264";
    switch (c)
    {
      case HEVC: return "hevc";
      case AV1:  return "av1";
      case H264: return "h264";
      default:   return "h264"; // COPY has no encoder; caller won't use it
    }
  }

  /**
   * Resolve caps for a given output codec against the default probe ffmpeg.
   * {@code preferBwdif} selects the higher-quality CUDA deinterlacer when present.
   */
  public static ConversionEngineCaps resolve(VideoCodecChoice codec, boolean preferBwdif)
  {
    ConversionEngineCaps.Builder b = ConversionEngineCaps.builder();

    if (codec == VideoCodecChoice.COPY)
    {
      // No encoder needed; still report a plausible scaler for completeness.
      return withScaler(b.videoEncoderName("copy").nvenc(false), preferBwdif).build();
    }

    String token = codecToken(codec);
    HwEncoder.Kind kind = HwEncoder.pick(token);
    String enc = (kind == null) ? null : HwEncoder.encoderName(kind, token);
    boolean nvenc = kind == HwEncoder.Kind.NVENC && enc != null;

    if (enc == null)
    {
      // Fall back to software encoding for this codec.
      enc = HwEncoder.encoderName(HwEncoder.Kind.NONE, token);
      nvenc = false;
    }

    b.videoEncoderName(enc).nvenc(nvenc);
    return withScaler(b, preferBwdif).build();
  }

  private static ConversionEngineCaps.Builder withScaler(ConversionEngineCaps.Builder b, boolean preferBwdif)
  {
    String scaler = HwEncoder.cudaScaler();
    if (scaler != null && scaler.length() > 0)
    {
      b.scalerFilter(scaler).scalerSupportsLanczos(HwEncoder.scalerSupportsLanczos(scaler));
    }
    else
    {
      b.scalerFilter("scale").scalerSupportsLanczos(true);
    }

    String deint = HwEncoder.cudaDeinterlacer(preferBwdif);
    b.deinterlacer(deint != null ? deint : "yadif");
    b.supportsFpsMax(true);
    return b;
  }
}
