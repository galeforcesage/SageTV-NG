/*
 * Copyright 2015-2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sage.media;

import sage.Sage;

/**
 * Single, shared per-session video-bitrate policy for every server-side
 * re-encode path (browserhd/PWA pull, H.264 push, GPU enhance, audioonly /
 * dynamic, and the HLS variant ladder).
 *
 * <p>Historically each transcode path invented its own {@code -b:v} logic with
 * unrelated caps (push 12&nbsp;Mbps, XCODE_ADJUST 8&nbsp;Mbps, browserhd a flat
 * 8&nbsp;Mbps, enhance a 40&nbsp;Mbps tier ladder, HLS an 864&nbsp;kbps ceiling).
 * The result was that a 4K browserhd stream was starved at 8&nbsp;Mbps while a
 * 4K enhance stream got 40&nbsp;Mbps on the same GPU and client. This class
 * replaces those scattered constants with one function so all playback shares a
 * single resolution- and bandwidth-aware policy.
 *
 * <p><b>What this computes:</b> the <i>launch-time</i> encoder rate control
 * ({@code -b:v} / {@code -maxrate} / {@code -bufsize}). That is the stock-ffmpeg
 * mechanism and works on any binary. It is deliberately separate from SageTV's
 * custom {@code videorateadapt} live-trim (which needs the patched ffmpeg): this
 * policy sets the starting target and the ceiling, and the live layer, where
 * present, moves within that envelope.
 *
 * <p><b>Direct play is never rate-capped.</b> This policy is only ever invoked
 * on paths that are already re-encoding video. Direct-play and video-copy remux
 * carry the source bytes verbatim; bandwidth for those paths is a <i>gate</i>
 * (handled in {@link sage.client.PlaybackDecisionEngine}: a source that exceeds
 * the client's budget is bumped out of direct-play into transcode), never a
 * target applied here.
 *
 * <p><b>Model.</b> A quality anchor is chosen from the output resolution
 * (height tier) and temporal complexity (motion, into which a high frame rate
 * folds), scaled by a codec-efficiency factor, then clamped by the smallest of
 * the per-client link budget, the client's declared decoder ceiling, and the
 * admin maximum. The resolution ladder's H.264 cells are calibrated to
 * reproduce the existing GPU-enhance tier bitrates exactly (2160p 40/28/20,
 * 1440p 24/17/13, 1080p 14/10/7 Mbps for high/medium/low motion), extended
 * downward to 720p and below.
 */
public final class BitratePolicy
{
  private BitratePolicy() {}

  /** Property prefix for all policy tunables. */
  private static final String PFX = "playback/bitrate/";

  /** Temporal-complexity class. A high frame rate folds up into this. */
  public enum Motion { LOW, MEDIUM, HIGH }

  /** Immutable result: launch-time encoder rate control, in kbps. */
  public static final class Plan
  {
    public final int targetKbps;
    public final int maxrateKbps;
    public final int bufsizeKbps;

    public Plan(int targetKbps, int maxrateKbps, int bufsizeKbps)
    {
      this.targetKbps  = targetKbps;
      this.maxrateKbps = maxrateKbps;
      this.bufsizeKbps = bufsizeKbps;
    }

    @Override public String toString()
    {
      return "BitratePolicy.Plan[b:v=" + targetKbps + "k maxrate=" + maxrateKbps
          + "k bufsize=" + bufsizeKbps + "k]";
    }
  }

  /**
   * Map a frame rate to the temporal-complexity floor it implies. 50/60&nbsp;fps
   * sports/action carry more motion at the same perceived quality, so a high
   * frame rate is never treated as below {@link Motion#HIGH}.
   *
   * @param fps frames per second; &le;0 is treated as unknown (MEDIUM)
   * @return {@link Motion#HIGH} when {@code fps} exceeds the threshold
   *         (default 45, tunable via {@code playback/bitrate/high_motion_fps}),
   *         otherwise {@link Motion#MEDIUM}
   */
  public static Motion motionForFps(double fps)
  {
    double thresh = Sage.getFloat(PFX + "high_motion_fps", 45f);
    return (fps > thresh) ? Motion.HIGH : Motion.MEDIUM;
  }

  /**
   * The medium/high/low-motion H.264 quality anchor for an output height, in
   * kbps, before any codec factor or clamp. Cells for the 1080/1440/2160 tiers
   * match the GPU-enhance ladder exactly. Each cell is overridable via
   * {@code playback/bitrate/ladder/<tier>/<motion>}.
   *
   * @param outHeight output frame height in pixels
   * @param motion    temporal-complexity class (null =&gt; MEDIUM)
   * @return the anchor bitrate in kbps
   */
  public static int ladderKbps(int outHeight, Motion motion)
  {
    if (motion == null) motion = Motion.MEDIUM;
    final String tier;
    final int hi, med, lo;
    if (outHeight >= 1801)      { tier = "2160"; hi = 40000; med = 28000; lo = 20000; }
    else if (outHeight >= 1261) { tier = "1440"; hi = 24000; med = 17000; lo = 13000; }
    else if (outHeight >= 901)  { tier = "1080"; hi = 14000; med = 10000; lo =  7000; }
    else if (outHeight >= 649)  { tier = "720";  hi =  9000; med =  6000; lo =  4500; }
    else if (outHeight >= 529)  { tier = "576";  hi =  5000; med =  3500; lo =  2500; }
    else                        { tier = "360";  hi =  2500; med =  1800; lo =  1200; }

    int base;
    switch (motion)
    {
      case HIGH: base = hi;  break;
      case LOW:  base = lo;  break;
      default:   base = med; break;
    }
    int override = Sage.getInt(PFX + "ladder/" + tier + "/"
        + motion.name().toLowerCase(java.util.Locale.ROOT), 0);
    return (override > 0) ? override : base;
  }

  /**
   * Codec efficiency relative to H.264. HEVC/AV1 reach the same quality at a
   * fraction of H.264's bitrate; MPEG-2 needs more. Tunable per codec.
   *
   * @param videoCodec ffmpeg/SageTV codec name (e.g. {@code h264_nvenc},
   *                   {@code hevc}, {@code av1}); null =&gt; 1.0
   * @return a multiplier applied to the H.264 quality anchor
   */
  public static double codecFactor(String videoCodec)
  {
    if (videoCodec == null) return 1.0;
    String c = videoCodec.toLowerCase(java.util.Locale.ROOT);
    if (c.contains("hevc") || c.contains("h265") || c.contains("265"))
      return Sage.getFloat(PFX + "hevc_factor", 0.55f);
    if (c.contains("av1"))
      return Sage.getFloat(PFX + "av1_factor", 0.50f);
    if (c.contains("mpeg2") || c.contains("mpeg-2"))
      return Sage.getFloat(PFX + "mpeg2_factor", 1.6f);
    if (c.contains("mpeg4") || c.contains("xvid") || c.contains("divx"))
      return Sage.getFloat(PFX + "mpeg4_factor", 1.3f);
    return 1.0; // h264/avc and anything else
  }

  /**
   * Compute the launch-time encoder rate control for one re-encode session.
   *
   * @param outW              output frame width in pixels (unused today but part
   *                          of the contract for future bpp models; may be 0)
   * @param outH              output frame height in pixels; drives the ladder
   * @param fps               output frame rate; folds into the motion floor
   * @param videoCodec        target video codec (for the efficiency factor)
   * @param genreMotion       EPG/genre-derived motion class; null =&gt; MEDIUM.
   *                          The effective motion is the higher of this and the
   *                          frame-rate-implied class, so nothing double-counts.
   * @param linkKbps          per-client link budget in kbps; &le;0 means
   *                          unmetered/LAN (no link clamp)
   * @param clientCeilingKbps client-declared decoder bitrate ceiling in kbps;
   *                          &le;0 means undeclared (no ceiling clamp)
   * @return the {@link Plan} (target/maxrate/bufsize), never null
   */
  public static Plan compute(int outW, int outH, double fps, String videoCodec,
      Motion genreMotion, int linkKbps, int clientCeilingKbps)
  {
    if (genreMotion == null) genreMotion = Motion.MEDIUM;
    // Effective motion is the higher of the genre and the frame-rate floor.
    Motion fpsMotion = motionForFps(fps);
    Motion motion = (fpsMotion.ordinal() > genreMotion.ordinal()) ? fpsMotion : genreMotion;

    int anchor = ladderKbps(outH, motion);
    double cf = codecFactor(videoCodec);
    long target = Math.round(anchor * cf);

    // --- Clamp by the per-client link budget (metered clients only) ---
    if (linkKbps > 0)
    {
      float safety = Sage.getFloat("playback/bandwidth_safety_factor", 0.85f);
      int audioReserve = Sage.getInt(PFX + "audio_reserve_kbps", 160);
      long budget = (long) Math.floor(linkKbps * safety) - audioReserve;
      if (budget < target) target = budget;
    }

    // --- Clamp by the client's declared decoder ceiling ---
    if (clientCeilingKbps > 0 && clientCeilingKbps < target)
      target = clientCeilingKbps;

    // --- Clamp by the admin maximum ---
    long adminMax = Sage.getLong(PFX + "max_kbps", 0L);
    if (adminMax > 0 && adminMax < target) target = adminMax;

    // --- Floor so we never emit an unplayably small stream ---
    int floor = Sage.getInt(PFX + "min_kbps", 500);
    if (target < floor) target = floor;

    int targetKbps = (int) Math.min(Integer.MAX_VALUE, target);

    float maxrateFactor = Sage.getFloat(PFX + "maxrate_factor", 1.4f);
    float bufsizeFactor = Sage.getFloat(PFX + "bufsize_factor", 2.0f);
    int maxrateKbps = (int) Math.min(Integer.MAX_VALUE, Math.round(targetKbps * (double) maxrateFactor));
    if (maxrateKbps < targetKbps) maxrateKbps = targetKbps;
    int bufsizeKbps = (int) Math.min(Integer.MAX_VALUE, Math.round(maxrateKbps * (double) bufsizeFactor));

    return new Plan(targetKbps, maxrateKbps, bufsizeKbps);
  }
}
