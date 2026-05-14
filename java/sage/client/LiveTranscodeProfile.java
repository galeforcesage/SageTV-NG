/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */
package sage.client;

/**
 * Per-profile live-transcode shaping for ATSC 3.0 (HEVC + AC-4) channel
 * coexistence. Read by the LiveTV variant selector and by FFMPEGTranscoder
 * when a source codec is not in the profile's allow-list.
 *
 * <p>Numbers are tier defaults, intentionally conservative; a future
 * auto-bandwidth pipeline (planned for ffmpeg-atsc3) will treat
 * {@code videoBitrateKbps} as a CEILING (-maxrate) rather than a fixed
 * target. Code that consumes these fields should already use
 * {@code -maxrate $videoBitrateKbps -bufsize $((2*videoBitrateKbps))} so the
 * future transition is a no-op.</p>
 *
 * <p>Hardware encoder selection is generic via {@code sage.HwEncoder}: the
 * {@code hwAccel} field is one of {@code auto|nvenc|vaapi|qsv|amf|videotoolbox|none}
 * (default {@code auto}). The {@code hwencPreset} is a portable preset hint
 * (NVENC-style {@code p1..p7}, or {@code fast|medium|slow|quality|speed|balanced})
 * which {@code HwEncoder.preset()} translates per-encoder.</p>
 */
public class LiveTranscodeProfile
{
  /** When true, prefer the ATSC 3.0 variant for LiveTV (if one exists). */
  private final boolean preferAtsc3;
  /** Target video codec when transcode is required (HEVC|H264). HEVC means
   *  pass-through when source is HEVC. */
  private final String videoCodec;
  /** Target audio codec when transcode is required (AC4|EAC3|AC3|AAC). */
  private final String audioCodec;
  /** Overall stream cap in kbps (0 = unlimited). */
  private final int maxBitrateKbps;
  /** Video bitrate ceiling in kbps. */
  private final int videoBitrateKbps;
  /** Audio bitrate target in kbps. */
  private final int audioBitrateKbps;
  /** Generic HW-encoder preset hint (p1..p7 or fast/medium/slow). Translated
   *  per encoder by {@code HwEncoder.preset()}. */
  private final String hwencPreset;
  /** HW-accel selection: auto|nvenc|vaapi|qsv|amf|videotoolbox|none. */
  private final String hwAccel;
  /** Scale-down output width (0 = source). */
  private final int scaleWidth;
  /** Scale-down output height (0 = source). */
  private final int scaleHeight;

  /** Full constructor. */
  public LiveTranscodeProfile(boolean preferAtsc3, String videoCodec, String audioCodec,
      int maxBitrateKbps, int videoBitrateKbps, int audioBitrateKbps,
      String hwencPreset, String hwAccel, int scaleWidth, int scaleHeight)
  {
    this.preferAtsc3      = preferAtsc3;
    this.videoCodec       = videoCodec       == null ? "H264"  : videoCodec.toUpperCase();
    this.audioCodec       = audioCodec       == null ? "AC3"   : audioCodec.toUpperCase();
    this.maxBitrateKbps   = maxBitrateKbps;
    this.videoBitrateKbps = videoBitrateKbps;
    this.audioBitrateKbps = audioBitrateKbps <= 0 ? 384 : audioBitrateKbps;
    this.hwencPreset      = hwencPreset      == null ? "p4"    : hwencPreset;
    this.hwAccel          = hwAccel          == null ? "auto"  : hwAccel.toLowerCase();
    this.scaleWidth       = scaleWidth;
    this.scaleHeight      = scaleHeight;
  }

  /** Backward-compat constructor (no hwAccel; defaults to "auto"). */
  public LiveTranscodeProfile(boolean preferAtsc3, String videoCodec, String audioCodec,
      int maxBitrateKbps, int videoBitrateKbps, int audioBitrateKbps,
      String hwencPreset, int scaleWidth, int scaleHeight)
  {
    this(preferAtsc3, videoCodec, audioCodec, maxBitrateKbps, videoBitrateKbps,
         audioBitrateKbps, hwencPreset, "auto", scaleWidth, scaleHeight);
  }

  public boolean isPreferAtsc3()     { return preferAtsc3; }
  public String  getVideoCodec()     { return videoCodec; }
  public String  getAudioCodec()     { return audioCodec; }
  public int     getMaxBitrateKbps() { return maxBitrateKbps; }
  public int     getVideoBitrateKbps() { return videoBitrateKbps; }
  public int     getAudioBitrateKbps() { return audioBitrateKbps; }
  public String  getHwencPreset()    { return hwencPreset; }
  public String  getHwAccel()        { return hwAccel; }
  public int     getScaleWidth()     { return scaleWidth; }
  public int     getScaleHeight()    { return scaleHeight; }

  /** @deprecated Use {@link #getHwencPreset()}. Kept for source compat. */
  @Deprecated public String getNvencPreset() { return hwencPreset; }

  /** Default safe fallback used when a profile omits {@code liveTranscode}. */
  public static LiveTranscodeProfile safeDefault()
  {
    // H.264 + AC-3 @ 8 Mbps source-resolution -- matches desktop_default tier.
    return new LiveTranscodeProfile(false, "H264", "AC3", 8000, 7616, 384, "p4", "auto", 0, 0);
  }

  @Override public String toString()
  {
    return "LiveTranscode{preferAtsc3=" + preferAtsc3
        + " v=" + videoCodec + "@" + videoBitrateKbps + "k"
        + " a=" + audioCodec + "@" + audioBitrateKbps + "k"
        + " max=" + maxBitrateKbps + "k preset=" + hwencPreset + " hw=" + hwAccel
        + " scale=" + scaleWidth + "x" + scaleHeight + "}";
  }
}
