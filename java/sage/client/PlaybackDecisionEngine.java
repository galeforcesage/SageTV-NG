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
package sage.client;

/**
 * Policy-driven playback decision engine.
 * Given a ClientProfile (effective capabilities) and media info,
 * determines the playback strategy: direct play, remux, or transcode.
 */
public class PlaybackDecisionEngine
{
  /** Decision result types */
  public enum Decision
  {
    DIRECT_PLAY,
    REMUX,
    TRANSCODE
  }

  /**
   * Result of a playback decision, including the chosen strategy
   * and the reason for the choice.
   */
  public static class PlaybackDecision
  {
    public final Decision decision;
    public final String reason;
    public final String targetContainer;
    public final String targetVideoCodec;
    public final String targetAudioCodec;

    public PlaybackDecision(Decision decision, String reason,
        String targetContainer, String targetVideoCodec, String targetAudioCodec)
    {
      this.decision = decision;
      this.reason = reason;
      this.targetContainer = targetContainer;
      this.targetVideoCodec = targetVideoCodec;
      this.targetAudioCodec = targetAudioCodec;
    }

    @Override
    public String toString()
    {
      return "PlaybackDecision[" + decision + " reason=" + reason +
          " container=" + targetContainer + " video=" + targetVideoCodec +
          " audio=" + targetAudioCodec + "]";
    }
  }

  /**
   * Evaluate the playback decision for a given profile and media.
   *
   * Attempt chain:
   * 1. Direct play — if all codecs and container are profile-compatible
   * 2. Remux — if codecs are OK but container needs conversion (and auto_remux is not disabled)
   * 3. Transcode — if codec or format is not compatible
   *
   * @param profile the effective client profile
   * @param mediaContainer the source container format (e.g., "MPEG2-TS", "MKV")
   * @param mediaVideoCodec the source video codec (e.g., "H264", "MPEG2-VIDEO")
   * @param mediaAudioCodec the source audio codec (e.g., "AC3", "AAC")
   * @param mediaWidth source video width
   * @param mediaHeight source video height
   * @param isHDx00Extender true if the client is an HDx00 (triggers risk-averse behavior)
   * @return the playback decision
   */
  public static PlaybackDecision evaluate(ClientProfile profile,
      String mediaContainer, String mediaVideoCodec, String mediaAudioCodec,
      int mediaWidth, int mediaHeight, boolean isHDx00Extender)
  {
    if (profile == null)
      return new PlaybackDecision(Decision.DIRECT_PLAY, "No profile (legacy path)", null, null, null);

    boolean containerOK = profile.isContainerAllowed(mediaContainer);
    boolean videoOK = profile.isVideoCodecAllowed(mediaVideoCodec);
    boolean audioOK = profile.isAudioCodecAllowed(mediaAudioCodec);

    // Check resolution limits
    boolean resolutionOK = true;
    if (profile.getMaxVideoWidth() > 0 && mediaWidth > profile.getMaxVideoWidth())
      resolutionOK = false;
    if (profile.getMaxVideoHeight() > 0 && mediaHeight > profile.getMaxVideoHeight())
      resolutionOK = false;

    if (sage.Sage.DBG)
      System.out.println("PlaybackDecisionEngine: container=" + mediaContainer + "(" + containerOK + ")" +
          " video=" + mediaVideoCodec + "(" + videoOK + ")" +
          " audio=" + mediaAudioCodec + "(" + audioOK + ")" +
          " resolution=" + mediaWidth + "x" + mediaHeight + "(" + resolutionOK + ")" +
          " profile=" + profile.getProfileId());

    // HDx00 special risk rule: even if format looks playable, treat risky patterns
    // (e.g., certain TS+H.264 combos) as needing remux/transcode sooner
    if (isHDx00Extender && containerOK && videoOK && audioOK && resolutionOK)
    {
      if (isRiskyForHDx00(mediaContainer, mediaVideoCodec))
      {
        if (profile.isAutoRemuxEnabled())
        {
          String targetContainer = selectBestContainer(profile, mediaContainer);
          return new PlaybackDecision(Decision.REMUX,
              "HDx00 risk rule: " + mediaContainer + "+" + mediaVideoCodec + " is risky, preferring remux",
              targetContainer, mediaVideoCodec, mediaAudioCodec);
        }
      }
    }

    // 1. Direct play — everything compatible
    if (containerOK && videoOK && audioOK && resolutionOK)
    {
      return new PlaybackDecision(Decision.DIRECT_PLAY,
          "All formats compatible", mediaContainer, mediaVideoCodec, mediaAudioCodec);
    }

    // 2. Remux — codecs OK but container mismatch
    if (videoOK && audioOK && resolutionOK && !containerOK && profile.isAutoRemuxEnabled())
    {
      String targetContainer = selectBestContainer(profile, mediaContainer);
      return new PlaybackDecision(Decision.REMUX,
          "Codecs compatible, container " + mediaContainer + " not allowed, remuxing to " + targetContainer,
          targetContainer, mediaVideoCodec, mediaAudioCodec);
    }

    // 3. Transcode — codec or resolution incompatible
    String targetContainer = selectBestContainer(profile, null);
    String targetVideo = videoOK && resolutionOK ? mediaVideoCodec : selectBestVideoCodec(profile);
    String targetAudio = audioOK ? mediaAudioCodec : selectBestAudioCodec(profile);

    String reason;
    if (!videoOK) reason = "Video codec " + mediaVideoCodec + " not supported";
    else if (!audioOK) reason = "Audio codec " + mediaAudioCodec + " not supported";
    else if (!resolutionOK) reason = "Resolution " + mediaWidth + "x" + mediaHeight + " exceeds limits";
    else reason = "Container " + mediaContainer + " not supported and remux disabled";

    return new PlaybackDecision(Decision.TRANSCODE, reason,
        targetContainer, targetVideo, targetAudio);
  }

  /**
   * Check if a format combination is known to be risky for HDx00 extenders.
   */
  private static boolean isRiskyForHDx00(String container, String videoCodec)
  {
    if (container == null || videoCodec == null) return false;
    String c = container.toUpperCase();
    String v = videoCodec.toUpperCase();
    // TS + H.264 can cause HDx00 hangs in some cases
    if (("MPEG2-TS".equals(c) || "TS".equals(c)) && ("H264".equals(v) || "H.264".equals(v)))
      return true;
    return false;
  }

  private static String selectBestContainer(ClientProfile profile, String avoid)
  {
    for (String c : profile.getContainers())
    {
      if (avoid == null || !c.equalsIgnoreCase(avoid))
        return c;
    }
    // If all containers match the one to avoid, just use the first
    return profile.getContainers().iterator().next();
  }

  private static String selectBestVideoCodec(ClientProfile profile)
  {
    // Prefer H264 as it's the most universally compatible
    if (profile.getVideoCodecs().contains("H264"))
      return "H264";
    return profile.getVideoCodecs().iterator().next();
  }

  private static String selectBestAudioCodec(ClientProfile profile)
  {
    // Prefer AAC as it's the most universally compatible
    if (profile.getAudioCodecs().contains("AAC"))
      return "AAC";
    return profile.getAudioCodecs().iterator().next();
  }
}
