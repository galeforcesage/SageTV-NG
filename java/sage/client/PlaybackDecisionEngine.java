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
    /**
     * Suggested target bitrate in Kbps for the transcoder when the decision is
     * REMUX or TRANSCODE. {@code 0} means "no hint" (use existing dynamic
     * adjustment / profile defaults).
     */
    public final int targetBitrateKbps;

    public PlaybackDecision(Decision decision, String reason,
        String targetContainer, String targetVideoCodec, String targetAudioCodec)
    {
      this(decision, reason, targetContainer, targetVideoCodec, targetAudioCodec, 0);
    }

    public PlaybackDecision(Decision decision, String reason,
        String targetContainer, String targetVideoCodec, String targetAudioCodec,
        int targetBitrateKbps)
    {
      this.decision = decision;
      this.reason = reason;
      this.targetContainer = targetContainer;
      this.targetVideoCodec = targetVideoCodec;
      this.targetAudioCodec = targetAudioCodec;
      this.targetBitrateKbps = targetBitrateKbps;
    }

    @Override
    public String toString()
    {
      return "PlaybackDecision[" + decision + " reason=" + reason +
          " container=" + targetContainer + " video=" + targetVideoCodec +
          " audio=" + targetAudioCodec
          + (targetBitrateKbps > 0 ? " targetBitrateKbps=" + targetBitrateKbps : "")
          + "]";
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
    return evaluate(profile, mediaContainer, mediaVideoCodec, mediaAudioCodec,
        mediaWidth, mediaHeight, isHDx00Extender, 0, 0);
  }

  /**
   * Quality- and bandwidth-aware variant.
   *
   * <p>In addition to the codec / container / resolution checks of the legacy
   * 7-arg overload, this form considers:
   * <ul>
   *   <li><b>Source bitrate vs. available bandwidth.</b> If the codec/container
   *       set would normally allow {@code DIRECT_PLAY} but the measured client
   *       bandwidth is below the source bitrate (with a safety factor, default
   *       0.85; tunable via {@code playback/bandwidth_safety_factor}), the
   *       decision is downgraded to {@link Decision#TRANSCODE} with a target
   *       bitrate clamped to {@code BW * safety} and capped by the profile's
   *       {@code liveTranscode.max_bitrate_kbps}.</li>
   *   <li><b>Source-aware target codec selection.</b> When transcode IS chosen,
   *       prefer source codec pass-through (highest fidelity) when the profile
   *       allows it, then HEVC over H.264 (better quality at given bitrate),
   *       then H.264. For audio prefer source pass-through, then EAC3 / AC3
   *       (multi-channel), then AAC (stereo).</li>
   * </ul>
   *
   * @param sourceBitrateKbps source media bitrate in Kbps, or {@code 0} if unknown
   * @param availableBandwidthKbps measured push-mode bandwidth to the client in
   *                                Kbps, or {@code 0} if unknown / unmetered
   */
  public static PlaybackDecision evaluate(ClientProfile profile,
      String mediaContainer, String mediaVideoCodec, String mediaAudioCodec,
      int mediaWidth, int mediaHeight, boolean isHDx00Extender,
      int sourceBitrateKbps, int availableBandwidthKbps)
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
          " sourceKbps=" + sourceBitrateKbps + " availableKbps=" + availableBandwidthKbps +
          " profile=" + profile.getProfileId());

    // Bandwidth budget check: if source bitrate is known and exceeds the
    // measured client bandwidth (with safety factor), we MUST transcode down
    // even if codecs would otherwise allow direct play. Skip the check if
    // either value is unknown (== 0) so behavior matches the old 7-arg form
    // for callers that haven't been updated.
    boolean bandwidthOK = true;
    int targetBitrateKbps = 0;
    if (sourceBitrateKbps > 0 && availableBandwidthKbps > 0)
    {
      float safety = sage.Sage.getFloat("playback/bandwidth_safety_factor", 0.85f);
      if (safety <= 0f || safety > 1f) safety = 0.85f;
      int budgetKbps = (int) (availableBandwidthKbps * safety);
      if (sourceBitrateKbps > budgetKbps)
      {
        bandwidthOK = false;
        targetBitrateKbps = clampToProfileCeiling(profile, budgetKbps);
      }
    }

    // HDx00 special risk rule: even if format looks playable, treat risky patterns
    // (e.g., certain TS+H.264 combos) as needing remux/transcode sooner
    if (isHDx00Extender && containerOK && videoOK && audioOK && resolutionOK && bandwidthOK)
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

    // 1. Direct play -- everything compatible AND bandwidth fits
    if (containerOK && videoOK && audioOK && resolutionOK && bandwidthOK)
    {
      return new PlaybackDecision(Decision.DIRECT_PLAY,
          "All formats compatible", mediaContainer, mediaVideoCodec, mediaAudioCodec);
    }

    // 2. Remux -- codecs OK, resolution+bw OK, but container mismatch
    if (videoOK && audioOK && resolutionOK && bandwidthOK
        && !containerOK && profile.isAutoRemuxEnabled())
    {
      String targetContainer = selectBestContainer(profile, mediaContainer);
      return new PlaybackDecision(Decision.REMUX,
          "Codecs compatible, container " + mediaContainer + " not allowed, remuxing to " + targetContainer,
          targetContainer, mediaVideoCodec, mediaAudioCodec);
    }

    // 3. Transcode -- codec, resolution, or bandwidth incompatible. Pick the
    // highest-quality target codecs the profile + source permit.
    String targetContainer = selectBestContainer(profile, null);
    String targetVideo = (videoOK && resolutionOK)
        ? mediaVideoCodec
        : selectBestVideoCodec(profile, mediaVideoCodec);
    String targetAudio = audioOK
        ? mediaAudioCodec
        : selectBestAudioCodec(profile, mediaAudioCodec);

    String reason;
    if (!videoOK)        reason = "Video codec " + mediaVideoCodec + " not supported";
    else if (!audioOK)   reason = "Audio codec " + mediaAudioCodec + " not supported";
    else if (!resolutionOK) reason = "Resolution " + mediaWidth + "x" + mediaHeight + " exceeds limits";
    else if (!bandwidthOK) reason = "Source " + sourceBitrateKbps + " kbps exceeds available "
        + availableBandwidthKbps + " kbps (target " + targetBitrateKbps + " kbps)";
    else                 reason = "Container " + mediaContainer + " not supported and remux disabled";

    if (targetBitrateKbps == 0)
      targetBitrateKbps = profileCeiling(profile);

    return new PlaybackDecision(Decision.TRANSCODE, reason,
        targetContainer, targetVideo, targetAudio, targetBitrateKbps);
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

  /**
   * Choose a target video codec for transcode that maximizes quality:
   * (1) source codec pass-through if profile allows (no re-encode needed),
   * (2) HEVC if profile allows it (better quality at the same bitrate),
   * (3) H.264 (universal fallback),
   * (4) first allowed codec.
   */
  private static String selectBestVideoCodec(ClientProfile profile, String sourceCodec)
  {
    if (sourceCodec != null && profile.isVideoCodecAllowed(sourceCodec))
      return sourceCodec;
    if (profile.isAllowHevc() && profile.getVideoCodecs().contains("HEVC"))
      return "HEVC";
    if (profile.getVideoCodecs().contains("H.264"))
      return "H.264";
    if (profile.getVideoCodecs().contains("H264"))
      return "H264";
    return profile.getVideoCodecs().iterator().next();
  }

  /** Backward-compat overload (no source hint). */
  @SuppressWarnings("unused")
  private static String selectBestVideoCodec(ClientProfile profile)
  {
    return selectBestVideoCodec(profile, null);
  }

  /**
   * Choose a target audio codec for transcode that maximizes quality:
   * (1) source codec pass-through if profile allows,
   * (2) EAC3 / EC-3 (multi-channel surround, broadly supported on modern AVRs),
   * (3) AC3 (5.1 surround, near-universal),
   * (4) AAC (stereo / lower quality, last resort).
   */
  private static String selectBestAudioCodec(ClientProfile profile, String sourceCodec)
  {
    if (sourceCodec != null && profile.isAudioCodecAllowed(sourceCodec))
      return sourceCodec;
    if (profile.getAudioCodecs().contains("EAC3"))
      return "EAC3";
    if (profile.getAudioCodecs().contains("EC-3"))
      return "EC-3";
    if (profile.getAudioCodecs().contains("AC3"))
      return "AC3";
    if (profile.getAudioCodecs().contains("AAC"))
      return "AAC";
    return profile.getAudioCodecs().iterator().next();
  }

  /** Backward-compat overload (no source hint). */
  @SuppressWarnings("unused")
  private static String selectBestAudioCodec(ClientProfile profile)
  {
    return selectBestAudioCodec(profile, null);
  }

  /** Profile's live-transcode max bitrate ceiling, or {@code 0} if unset. */
  private static int profileCeiling(ClientProfile profile)
  {
    LiveTranscodeProfile lt = profile.getLiveTranscode();
    return (lt != null) ? lt.getMaxBitrateKbps() : 0;
  }

  /** Clamp a budget bitrate to the profile's live-transcode ceiling. */
  private static int clampToProfileCeiling(ClientProfile profile, int budgetKbps)
  {
    int ceiling = profileCeiling(profile);
    if (ceiling > 0 && budgetKbps > ceiling) return ceiling;
    return budgetKbps;
  }
}
