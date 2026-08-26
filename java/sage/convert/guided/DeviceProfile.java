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

import java.util.EnumSet;

import sage.convert.AudioCodecChoice;
import sage.convert.ContainerChoice;
import sage.convert.VideoCodecChoice;

/**
 * What will play the file (Menu 3). A capability profile — decode codecs,
 * container support, audio support, display resolution and HDR — used as a
 * <em>recommendation input and a compatibility filter, never an absolute
 * prohibition</em>. When the resolved output uses a capability this profile does
 * not report, the {@link GuidedRecommender} does not silently change it: it
 * lowers the compatibility state to {@link Conflict.Severity#UNVERIFIED} and lets
 * the user keep or change the choice.
 *
 * <p>{@link #unknownDevice()} is the conservative default (H.264 / MP4 / AAC,
 * 1080p, SDR, stereo). {@link #unrestricted()} models "no device / stays on the
 * server" and permits everything.
 */
public final class DeviceProfile
{
  private final String name;
  private final EnumSet<ContainerChoice> containers;
  private final EnumSet<VideoCodecChoice> videoCodecs;
  private final EnumSet<AudioCodecChoice> audioCodecs;
  private final int maxWidth;   // 0 = unlimited
  private final int maxHeight;  // 0 = unlimited
  private final boolean hdr;
  private final boolean surround;
  private final boolean unknown; // true = "detect / not verified" — treat filters as advisory only

  private DeviceProfile(String name, EnumSet<ContainerChoice> containers,
      EnumSet<VideoCodecChoice> videoCodecs, EnumSet<AudioCodecChoice> audioCodecs,
      int maxWidth, int maxHeight, boolean hdr, boolean surround, boolean unknown)
  {
    this.name = name;
    this.containers = containers;
    this.videoCodecs = videoCodecs;
    this.audioCodecs = audioCodecs;
    this.maxWidth = maxWidth;
    this.maxHeight = maxHeight;
    this.hdr = hdr;
    this.surround = surround;
    this.unknown = unknown;
  }

  public String getName() { return name; }
  public int getMaxWidth() { return maxWidth; }
  public int getMaxHeight() { return maxHeight; }
  public boolean supportsHdr() { return hdr; }
  public boolean supportsSurround() { return surround; }

  /** True for "detect / unknown" profiles whose capability filters are advisory only. */
  public boolean isUnknown() { return unknown; }

  public boolean supportsContainer(ContainerChoice c)
  {
    return c == ContainerChoice.KEEP || containers.contains(c);
  }

  public boolean supportsVideo(VideoCodecChoice c)
  {
    return c == VideoCodecChoice.COPY || videoCodecs.contains(c);
  }

  public boolean supportsAudio(AudioCodecChoice c)
  {
    return c == AudioCodecChoice.COPY || audioCodecs.contains(c);
  }

  /** True when this profile constrains resolution and (w,h) exceeds it. */
  public boolean exceedsDisplay(int w, int h)
  {
    return (maxWidth > 0 && w > maxWidth) || (maxHeight > 0 && h > maxHeight);
  }

  // ---- Built-in profiles ---------------------------------------------------

  /** No device / stays on the server: everything permitted, no resolution cap. */
  public static DeviceProfile unrestricted()
  {
    return new DeviceProfile("No device (server / local storage)",
        EnumSet.of(ContainerChoice.MP4, ContainerChoice.MKV),
        EnumSet.of(VideoCodecChoice.H264, VideoCodecChoice.HEVC, VideoCodecChoice.AV1),
        EnumSet.of(AudioCodecChoice.AAC, AudioCodecChoice.AC3, AudioCodecChoice.EAC3),
        0, 0, true, true, false);
  }

  /** Conservative default when the target device is unknown. */
  public static DeviceProfile unknownDevice()
  {
    return new DeviceProfile("Unknown player",
        EnumSet.of(ContainerChoice.MP4),
        EnumSet.of(VideoCodecChoice.H264),
        EnumSet.of(AudioCodecChoice.AAC, AudioCodecChoice.AC3),
        1920, 1080, false, false, true);
  }

  /** A general phone: H.264/HEVC in MP4, AAC, up to 1080p, SDR, stereo. */
  public static DeviceProfile phone()
  {
    return new DeviceProfile("General phone",
        EnumSet.of(ContainerChoice.MP4),
        EnumSet.of(VideoCodecChoice.H264, VideoCodecChoice.HEVC),
        EnumSet.of(AudioCodecChoice.AAC),
        1920, 1080, false, false, false);
  }

  /** A general tablet: like a phone but a larger panel. */
  public static DeviceProfile tablet()
  {
    return new DeviceProfile("General tablet",
        EnumSet.of(ContainerChoice.MP4),
        EnumSet.of(VideoCodecChoice.H264, VideoCodecChoice.HEVC),
        EnumSet.of(AudioCodecChoice.AAC),
        2560, 1440, false, false, false);
  }

  /** A general computer: MP4/MKV, H.264/HEVC/AV1, full audio, up to 4K, HDR, surround. */
  public static DeviceProfile computer()
  {
    return new DeviceProfile("General computer",
        EnumSet.of(ContainerChoice.MP4, ContainerChoice.MKV),
        EnumSet.of(VideoCodecChoice.H264, VideoCodecChoice.HEVC, VideoCodecChoice.AV1),
        EnumSet.of(AudioCodecChoice.AAC, AudioCodecChoice.AC3, AudioCodecChoice.EAC3),
        3840, 2160, true, true, false);
  }

  /** A modern 4K television: everything, 4K, HDR, surround. */
  public static DeviceProfile modern4kTv()
  {
    return new DeviceProfile("Modern 4K television",
        EnumSet.of(ContainerChoice.MP4, ContainerChoice.MKV),
        EnumSet.of(VideoCodecChoice.H264, VideoCodecChoice.HEVC, VideoCodecChoice.AV1),
        EnumSet.of(AudioCodecChoice.AAC, AudioCodecChoice.AC3, AudioCodecChoice.EAC3),
        3840, 2160, true, true, false);
  }

  /** A fully custom profile. */
  public static DeviceProfile custom(String name, EnumSet<ContainerChoice> containers,
      EnumSet<VideoCodecChoice> videoCodecs, EnumSet<AudioCodecChoice> audioCodecs,
      int maxWidth, int maxHeight, boolean hdr, boolean surround)
  {
    return new DeviceProfile(name == null ? "Custom device" : name,
        containers == null ? EnumSet.of(ContainerChoice.MP4) : containers,
        videoCodecs == null ? EnumSet.of(VideoCodecChoice.H264) : videoCodecs,
        audioCodecs == null ? EnumSet.of(AudioCodecChoice.AAC) : audioCodecs,
        maxWidth, maxHeight, hdr, surround, false);
  }
}
