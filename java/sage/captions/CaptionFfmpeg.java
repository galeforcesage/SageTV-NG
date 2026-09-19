/*
 * Copyright 2015 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sage.captions;

import sage.Sage;

/**
 * Resolves which ffmpeg binary the caption paths should run.
 *
 * <p>SageTV-NG deliberately uses a single ffmpeg for playback and transcoding —
 * the core build at {@code /opt/sagetv/server/ffmpeg}. That build is configured
 * {@code --disable-devices}, because playback and transcoding only ever need file
 * and network I/O, and disabling devices keeps v4l2/alsa/x11grab out of a server
 * binary. The side effect is that it has no {@code lavfi} input device.
 *
 * <p>That matters for exactly one thing: the embedded 608/708 caption fallback,
 * whose entire input is a lavfi filter graph
 * ({@code movie=<file>[out0+subcc]}). Two separate call sites implement that
 * fallback — {@link CaptionExtractionJob} and {@code sage.FFMPEGTranscoder} — so
 * the resolution lives here rather than being written twice and drifting apart.
 *
 * <p>The fallback binary only reads caption bytes out of the video track; it never
 * decodes audio, so using a stock distribution ffmpeg for it does not reintroduce
 * the AC-4 problems the core build exists to solve. Installing {@code ccextractor}
 * is the preferred fix and bypasses this path entirely.
 */
public final class CaptionFfmpeg
{
  private CaptionFfmpeg() {}

  private static volatile boolean warnedFfmpegOverride = false;
  private static volatile boolean warnedLavfiFallback = false;

  /** Cache of ffmpeg binary path -&gt; whether it exposes the lavfi input device. */
  private static final java.util.Map<String, Boolean> lavfiSupport =
      new java.util.concurrent.ConcurrentHashMap<String, Boolean>();

  /**
   * The ffmpeg binary for caption work: the core build by default.
   *
   * <p>{@code caption_extraction/ffmpeg_path} remains available as an override for
   * unusual installs. A bare name such as {@code "ffmpeg"} resolves through
   * {@code PATH} rather than to the core binary, so an override is logged once to
   * make that visible.
   */
  public static String resolve()
  {
    String core = sage.FFMPEGTranscoder.getTranscoderPath();
    String ffmpeg = Sage.get("caption_extraction/ffmpeg_path", core);
    if (ffmpeg == null || ffmpeg.length() == 0) return core;
    if (!warnedFfmpegOverride && !ffmpeg.equals(core))
    {
      warnedFfmpegOverride = true;
      System.out.println("CaptionFfmpeg: caption_extraction/ffmpeg_path=" + ffmpeg +
          " overrides the core ffmpeg (" + core + ")" +
          (ffmpeg.indexOf('/') < 0 && ffmpeg.indexOf('\\') < 0
              ? "; it has no path separator so it is resolved via PATH."
              : "."));
    }
    return ffmpeg;
  }

  /** Does {@code bin} provide the {@code lavfi} input device? */
  public static boolean supportsLavfi(String bin)
  {
    Boolean cached = lavfiSupport.get(bin);
    if (cached != null) return cached.booleanValue();
    boolean ok = false;
    try
    {
      Process p = new ProcessBuilder(bin, "-hide_banner", "-loglevel", "error", "-demuxers")
          .redirectErrorStream(true).start();
      java.io.BufferedReader r = new java.io.BufferedReader(
          new java.io.InputStreamReader(p.getInputStream()));
      String line;
      while ((line = r.readLine()) != null)
      {
        // Entries look like: " D  lavfi           Libavfilter virtual input device"
        if (line.matches("\\s*[D ][E ]\\s+lavfi\\s+.*")) { ok = true; }
      }
      r.close();
      p.waitFor();
    }
    catch (Exception e) { ok = false; }
    lavfiSupport.put(bin, Boolean.valueOf(ok));
    return ok;
  }

  /**
   * An ffmpeg that can actually run the {@code subcc} fallback.
   *
   * <p>Prefers {@link #resolve()}; falls back to a plain {@code ffmpeg} from
   * {@code PATH} when the core build has no lavfi device.
   *
   * @return a usable binary, or {@code null} if none supports lavfi.
   */
  public static String resolveLavfi()
  {
    String preferred = resolve();
    if (supportsLavfi(preferred)) return preferred;
    if (supportsLavfi("ffmpeg"))
    {
      if (!warnedLavfiFallback)
      {
        warnedLavfiFallback = true;
        System.out.println("CaptionFfmpeg: " + preferred + " has no lavfi input device" +
            " (SageTV's ffmpeg is built --disable-devices), so the subcc caption fallback will use" +
            " 'ffmpeg' from PATH. This affects caption extraction only; playback and transcoding" +
            " still use " + preferred + ". Installing ccextractor avoids this path entirely.");
      }
      return "ffmpeg";
    }
    return null;
  }
}
