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
    /**
     * Video codec + container are supported; only the audio codec needs
     * re-encoding. Introduced by Protocol v2.1 (Playback Surface model).
     * Legacy V1/V2 paths never return this value -- they emit {@link #TRANSCODE}
     * with {@code targetVideo == sourceVideo} for the same scenario. Existing
     * downstream code that only distinguishes DIRECT_PLAY from "everything
     * else" continues to work; consumers that want to take the cheap
     * audio-only pipeline (see ROADMAP.md "Audio-only transcode when only
     * the audio codec mismatches") should treat AUDIO_TRANSCODE explicitly.
     */
    AUDIO_TRANSCODE,
    TRANSCODE
  }

  /**
   * The client's ACTUAL reported support for the source container / video /
   * audio, computed by the caller from the client's coarse capability lists
   * (VIDEO_CODECS / AUDIO_CODECS / PULL_AV_CONTAINERS / PUSH_AV_CONTAINERS via
   * {@code MiniClientSageRenderer.isSupported*}). Used by the engine as the
   * legacy-side honor signal so the static profile can only restrict, never
   * grant, a capability the client did not report (upstream google/SageTV
   * conjunctive model). {@code true} for a dimension means "client reports it
   * can handle this"; a missing/empty codec should be passed as {@code true}
   * (nothing to gate).
   */
  public static final class ClientReportedCaps
  {
    public final boolean container;
    public final boolean video;
    public final boolean audio;
    public ClientReportedCaps(boolean container, boolean video, boolean audio)
    {
      this.container = container;
      this.video = video;
      this.audio = audio;
    }
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
    /**
     * When non-null, indicates the caller should ask the client to switch
     * to this player BEFORE starting this stream (via
     * {@code sendSetProperty("CAP_EFFECTIVE_PLAYER", preferredPlayer)}).
     * Set by {@link #evaluateWithPlayerSwitch} when the client's default
     * player cannot handle the source but the alternate player can.
     * Always {@code null} on results from the plain {@code evaluate} methods.
     */
    public final String preferredPlayer;

    public PlaybackDecision(Decision decision, String reason,
        String targetContainer, String targetVideoCodec, String targetAudioCodec)
    {
      this(decision, reason, targetContainer, targetVideoCodec, targetAudioCodec, 0, null);
    }

    public PlaybackDecision(Decision decision, String reason,
        String targetContainer, String targetVideoCodec, String targetAudioCodec,
        int targetBitrateKbps)
    {
      this(decision, reason, targetContainer, targetVideoCodec, targetAudioCodec, targetBitrateKbps, null);
    }

    public PlaybackDecision(Decision decision, String reason,
        String targetContainer, String targetVideoCodec, String targetAudioCodec,
        int targetBitrateKbps, String preferredPlayer)
    {
      this.decision = decision;
      this.reason = reason;
      this.targetContainer = targetContainer;
      this.targetVideoCodec = targetVideoCodec;
      this.targetAudioCodec = targetAudioCodec;
      this.targetBitrateKbps = targetBitrateKbps;
      this.preferredPlayer = preferredPlayer;
    }

    /** Copy this decision but attach a {@code preferredPlayer} hint. */
    public PlaybackDecision withPreferredPlayer(String player)
    {
      return new PlaybackDecision(decision, reason, targetContainer,
          targetVideoCodec, targetAudioCodec, targetBitrateKbps, player);
    }

    @Override
    public String toString()
    {
      return "PlaybackDecision[" + decision + " reason=" + reason +
          " container=" + targetContainer + " video=" + targetVideoCodec +
          " audio=" + targetAudioCodec
          + (targetBitrateKbps > 0 ? " targetBitrateKbps=" + targetBitrateKbps : "")
          + (preferredPlayer != null ? " preferredPlayer=" + preferredPlayer : "")
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
    return evaluate(profile, mediaContainer, mediaVideoCodec, mediaAudioCodec,
        mediaWidth, mediaHeight, isHDx00Extender,
        sourceBitrateKbps, availableBandwidthKbps,
        null, false, true);
  }

  /**
   * Schema v2 capability-constraints-aware overload.
   *
   * <p>Adds per-client constraint enforcement on top of the codec/container set
   * policy:
   * <ul>
   *   <li><b>Video interlaced gate.</b> If the source is interlaced and the
   *       client's row for the source video codec has {@code interlaced=false}
   *       (i.e. the client cannot decode interlaced content for this codec, as
   *       is the case for ExoPlayer MPEG-2 on Galaxy phones/tablets/Fold), the
   *       video is treated as unsupported and the decision is downgraded to
   *       {@link Decision#TRANSCODE} (deinterlace + re-encode).</li>
   *   <li><b>Container transport gate.</b> If the chosen transport is push and
   *       the row for the source container has {@code push=false} (or pull and
   *       {@code pull=false}), the container is treated as unsupported, which
   *       forces {@link Decision#REMUX} (or {@link Decision#TRANSCODE} when
   *       remux is disabled).</li>
   *   <li><b>Audio decode gate.</b> If the row for the source audio codec has
   *       {@code decode=false}, audio is treated as unsupported and the
   *       transcoder picks a fallback audio target.</li>
   * </ul>
   *
   * <p>All gates are <b>skipped</b> when:
   * <ul>
   *   <li>{@code constraints} is null (client did not negotiate schema v2), OR</li>
   *   <li>{@code constraints} has no row for the relevant codec/container
   *       (treated as {@link ClientConstraints.Tri#UNKNOWN} per the spec —
   *       preserves legacy behavior).</li>
   * </ul>
   *
   * @param constraints per-client schema-v2 capability set, or {@code null} for legacy clients
   * @param sourceInterlaced whether the source video stream is interlaced
   *                         (from {@code VideoFormat.isInterlaced()}); ignored when {@code constraints} is null
   * @param isPushTransport true when the renderer is using push mode (the common case for miniclients);
   *                        false for pull/HLS-style fetching
   */
  public static PlaybackDecision evaluate(ClientProfile profile,
      String mediaContainer, String mediaVideoCodec, String mediaAudioCodec,
      int mediaWidth, int mediaHeight, boolean isHDx00Extender,
      int sourceBitrateKbps, int availableBandwidthKbps,
      ClientConstraints constraints, boolean sourceInterlaced, boolean isPushTransport)
  {
    return evaluate(profile, mediaContainer, mediaVideoCodec, mediaAudioCodec,
        mediaWidth, mediaHeight, isHDx00Extender, sourceBitrateKbps, availableBandwidthKbps,
        constraints, sourceInterlaced, isPushTransport, null);
  }

  /**
   * Full evaluator with the LEGACY client-report intersection.
   *
   * <p>{@code clientCaps} carries the client's ACTUAL reported support for the
   * source container / video / audio (from the coarse {@code VIDEO_CODECS} /
   * {@code AUDIO_CODECS} / {@code PULL_AV_CONTAINERS} / {@code PUSH_AV_CONTAINERS}
   * lists via {@code MiniClientSageRenderer.isSupported*}). This is the
   * upstream google/SageTV honor model: the server never direct-plays a
   * container/codec the client did not report it can handle.
   *
   * <p>The static profile is demoted to a GUARD RAIL: a codec/container is
   * "OK" only when BOTH the profile allows it AND the client reported it
   * ({@code profileAllows && clientReports}). The profile can therefore only
   * RESTRICT, never GRANT a capability the client did not claim. When
   * {@code clientCaps} is null (callers that haven't been updated), behavior is
   * unchanged (profile-only), preserving backward compatibility.
   */
  public static PlaybackDecision evaluate(ClientProfile profile,
      String mediaContainer, String mediaVideoCodec, String mediaAudioCodec,
      int mediaWidth, int mediaHeight, boolean isHDx00Extender,
      int sourceBitrateKbps, int availableBandwidthKbps,
      ClientConstraints constraints, boolean sourceInterlaced, boolean isPushTransport,
      ClientReportedCaps clientCaps)
  {
    if (profile == null)
      return new PlaybackDecision(Decision.DIRECT_PLAY, "No profile (legacy path)", null, null, null);

    // Guard-rail intersection: profile allowance AND the client's actual
    // reported support. clientCaps==null (unupdated caller) => profile-only.
    boolean containerOK = profile.isContainerAllowed(mediaContainer)
        && (clientCaps == null || clientCaps.container);
    boolean videoOK = profile.isVideoCodecAllowed(mediaVideoCodec)
        && (clientCaps == null || clientCaps.video);
    boolean audioOK = profile.isAudioCodecAllowed(mediaAudioCodec)
        && (clientCaps == null || clientCaps.audio);

    // --- Schema v2 capability-constraints gates ---
    // Applied only when the client negotiated schema v2.
    //
    // Semantics: a populated *_CONSTRAINTS set is the player's COMPLETE
    // declaration of supported codecs/containers. Any codec/container not in
    // the set is treated as unsupported by that player. (ExoPlayer, for
    // example, omits MPEG2-VIDEO from EXO_VIDEO_CONSTRAINTS because it cannot
    // decode MPEG-2 at all; the legacy profile codec list happens to allow
    // it, but the player physically can't play it.)
    //
    // An empty/missing constraint set (e.g. audio set null while video set
    // populated, or both null for a legacy client) is treated as UNKNOWN
    // and preserves the legacy profile-based behavior for that dimension.
    String constraintRejectReason = null;
    if (constraints != null && !constraints.isEmpty())
    {
      // Video gate: codec must have a row, AND if the row says interlaced=false
      // but the source is interlaced, reject.
      ClientConstraints.VideoConstraint vrow = constraints.getVideo(mediaVideoCodec);
      if (constraints.hasAnyVideo() && videoOK)
      {
        if (vrow == null)
        {
          videoOK = false;
          constraintRejectReason = "video " + mediaVideoCodec
              + " not in client " + constraints.getPlayer() + " supported codecs";
        }
        else if (sourceInterlaced && vrow.interlaced == ClientConstraints.Tri.FALSE)
        {
          videoOK = false;
          constraintRejectReason = "interlaced source + client " + mediaVideoCodec
              + " row interlaced=false (player=" + constraints.getPlayer() + ")";
        }
      }

      // Container transport gate (push vs pull).
      ClientConstraints.ContainerConstraint crow = constraints.getContainer(mediaContainer);
      if (constraints.hasAnyContainer() && containerOK)
      {
        if (crow == null)
        {
          containerOK = false;
          String add = "container " + mediaContainer
              + " not in client " + constraints.getPlayer() + " supported containers";
          constraintRejectReason = (constraintRejectReason == null)
              ? add : constraintRejectReason + "; " + add;
        }
        else
        {
          ClientConstraints.Tri transportOK = isPushTransport ? crow.push : crow.pull;
          if (transportOK == ClientConstraints.Tri.FALSE)
          {
            containerOK = false;
            String t = isPushTransport ? "push" : "pull";
            String add = "container " + mediaContainer + " row " + t + "=false";
            constraintRejectReason = (constraintRejectReason == null)
                ? add : constraintRejectReason + "; " + add;
          }
        }
      }

      // Audio gate.
      ClientConstraints.AudioConstraint arow = constraints.getAudio(mediaAudioCodec);
      if (constraints.hasAnyAudio() && audioOK)
      {
        if (arow == null)
        {
          audioOK = false;
          String add = "audio " + mediaAudioCodec
              + " not in client " + constraints.getPlayer() + " supported codecs";
          constraintRejectReason = (constraintRejectReason == null)
              ? add : constraintRejectReason + "; " + add;
        }
        else if (arow.decode == ClientConstraints.Tri.FALSE)
        {
          audioOK = false;
          String add = "audio " + mediaAudioCodec + " row decode=false";
          constraintRejectReason = (constraintRejectReason == null)
              ? add : constraintRejectReason + "; " + add;
        }
      }
    }

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
          " profile=" + profile.getProfileId()
          + (constraints != null && !constraints.isEmpty()
              ? " constraintsPlayer=" + constraints.getPlayer()
                + " scan=" + (sourceInterlaced ? "interlaced" : "progressive")
                + " transport=" + (isPushTransport ? "push" : "pull")
                + (constraintRejectReason != null ? " constraintReject=[" + constraintRejectReason + "]" : "")
              : ""));

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
    // highest-quality target codecs the profile + source + CLIENT permit.
    // Passing constraints intersects the profile's allowed set with the
    // client's actual reported decoders so we never target a codec this
    // specific client cannot play (e.g. EAC3 to a generic Chrome PWA).
    String targetContainer = selectBestContainer(profile, null);
    String targetVideo = (videoOK && resolutionOK)
        ? mediaVideoCodec
        : selectBestVideoCodec(profile, mediaVideoCodec, constraints);
    String targetAudio = audioOK
        ? mediaAudioCodec
        : selectBestAudioCodec(profile, mediaAudioCodec, constraints);

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
   *
   * When {@code constraints} is non-null and declares video codecs, each
   * candidate must ALSO be in the client's actual reported set — so we never
   * pick a target the specific client cannot decode (e.g. a static profile
   * lists HEVC but this player's decoder matrix omits it). Legacy clients
   * (null/empty constraints) keep the profile-only behavior unchanged.
   */
  private static String selectBestVideoCodec(ClientProfile profile, String sourceCodec,
      ClientConstraints constraints)
  {
    if (sourceCodec != null && profile.isVideoCodecAllowed(sourceCodec)
        && videoSupportedByClient(constraints, sourceCodec))
      return sourceCodec;
    if (profile.isAllowHevc() && profile.getVideoCodecs().contains("HEVC")
        && videoSupportedByClient(constraints, "HEVC"))
      return "HEVC";
    if (profile.getVideoCodecs().contains("H.264") && videoSupportedByClient(constraints, "H.264"))
      return "H.264";
    if (profile.getVideoCodecs().contains("H264") && videoSupportedByClient(constraints, "H264"))
      return "H264";
    // Fall back to the first profile codec the client can actually decode.
    for (String c : profile.getVideoCodecs())
      if (videoSupportedByClient(constraints, c))
        return c;
    return profile.getVideoCodecs().iterator().next();
  }

  private static String selectBestVideoCodec(ClientProfile profile, String sourceCodec)
  {
    return selectBestVideoCodec(profile, sourceCodec, null);
  }

  /** Backward-compat overload (no source hint). */
  @SuppressWarnings("unused")
  private static String selectBestVideoCodec(ClientProfile profile)
  {
    return selectBestVideoCodec(profile, null, null);
  }

  // True when the client's schema-v2 constraints allow this video codec, OR
  // when constraints are absent/empty (legacy client → defer to the profile,
  // preserving the pre-schema-v2 behavior exactly).
  private static boolean videoSupportedByClient(ClientConstraints constraints, String codec)
  {
    if (constraints == null || constraints.isEmpty() || !constraints.hasAnyVideo())
      return true;
    return constraints.getVideo(codec) != null;
  }

  /**
   * Choose a target audio codec for transcode that maximizes quality:
   * (1) source codec pass-through if profile allows,
   * (2) EAC3 / EC-3 (multi-channel surround, broadly supported on modern AVRs),
   * (3) AC3 (5.1 surround, near-universal),
   * (4) AAC (stereo / lower quality, last resort).
   *
   * When {@code constraints} is non-null and declares audio codecs, each
   * candidate must ALSO be in the client's actual reported set — so a browser
   * that decodes only AAC (e.g. generic Chrome/Edge PWA) never gets an
   * undecodable EAC3 stream, while a Safari/Tizen/Android-Dolby client that
   * genuinely reports EAC3 still gets the higher-quality surround target.
   * Legacy clients (null/empty constraints) keep the profile-only behavior.
   */
  private static String selectBestAudioCodec(ClientProfile profile, String sourceCodec,
      ClientConstraints constraints)
  {
    if (sourceCodec != null && profile.isAudioCodecAllowed(sourceCodec)
        && audioSupportedByClient(constraints, sourceCodec))
      return sourceCodec;
    if (profile.getAudioCodecs().contains("EAC3") && audioSupportedByClient(constraints, "EAC3"))
      return "EAC3";
    if (profile.getAudioCodecs().contains("EC-3") && audioSupportedByClient(constraints, "EC-3"))
      return "EC-3";
    if (profile.getAudioCodecs().contains("AC3") && audioSupportedByClient(constraints, "AC3"))
      return "AC3";
    if (profile.getAudioCodecs().contains("AAC") && audioSupportedByClient(constraints, "AAC"))
      return "AAC";
    // Fall back to the first profile codec the client can actually decode.
    for (String c : profile.getAudioCodecs())
      if (audioSupportedByClient(constraints, c))
        return c;
    return profile.getAudioCodecs().iterator().next();
  }

  private static String selectBestAudioCodec(ClientProfile profile, String sourceCodec)
  {
    return selectBestAudioCodec(profile, sourceCodec, null);
  }

  /** Backward-compat overload (no source hint). */
  @SuppressWarnings("unused")
  private static String selectBestAudioCodec(ClientProfile profile)
  {
    return selectBestAudioCodec(profile, null, null);
  }

  // True when the client's schema-v2 constraints allow this audio codec, OR
  // when constraints are absent/empty (legacy client → defer to the profile).
  private static boolean audioSupportedByClient(ClientConstraints constraints, String codec)
  {
    if (constraints == null || constraints.isEmpty() || !constraints.hasAnyAudio())
      return true;
    return constraints.getAudio(codec) != null;
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

  /**
   * Evaluate playback against the client's default player first; if that
   * would require transcode/remux for a schema-v2 capability-constraints
   * reason, retry against the alternate player. If the alternate would
   * direct-play the source, return the alternate's decision tagged with
   * {@code preferredPlayer = altPlayer} so the caller can send
   * {@code CAP_EFFECTIVE_PLAYER=altPlayer} to the client before starting
   * the stream.
   *
   * If the alternate cannot direct-play either, the primary's decision is
   * returned unchanged (no player switch).
   *
   * Falls back to a plain {@link #evaluate} call when either constraints
   * argument is {@code null}/empty, so the wrapper is safe for legacy
   * clients and for clients that only declared one player.
   *
   * @param defaultPlayer the client's current default player tag (e.g.
   *                      {@code "exoplayer"}); used only to label log output
   *                      and short-circuit when no switch is possible
   * @param altPlayer     the alternate player tag (e.g. {@code "ijkplayer"});
   *                      the value sent in {@code CAP_EFFECTIVE_PLAYER} if a
   *                      switch is recommended
   * @param primary       constraints for {@code defaultPlayer}
   * @param alternate     constraints for {@code altPlayer}
   */
  public static PlaybackDecision evaluateWithPlayerSwitch(ClientProfile profile,
      String mediaContainer, String mediaVideoCodec, String mediaAudioCodec,
      int mediaWidth, int mediaHeight, boolean isHDx00Extender,
      int sourceBitrateKbps, int availableBandwidthKbps,
      String defaultPlayer, String altPlayer,
      ClientConstraints primary, ClientConstraints alternate,
      boolean sourceInterlaced, boolean isPushTransport)
  {
    return evaluateWithPlayerSwitch(profile, mediaContainer, mediaVideoCodec, mediaAudioCodec,
        mediaWidth, mediaHeight, isHDx00Extender, sourceBitrateKbps, availableBandwidthKbps,
        defaultPlayer, altPlayer, primary, alternate, sourceInterlaced, isPushTransport, null);
  }

  /** Player-switch evaluator with the legacy client-report intersection. */
  public static PlaybackDecision evaluateWithPlayerSwitch(ClientProfile profile,
      String mediaContainer, String mediaVideoCodec, String mediaAudioCodec,
      int mediaWidth, int mediaHeight, boolean isHDx00Extender,
      int sourceBitrateKbps, int availableBandwidthKbps,
      String defaultPlayer, String altPlayer,
      ClientConstraints primary, ClientConstraints alternate,
      boolean sourceInterlaced, boolean isPushTransport,
      ClientReportedCaps clientCaps)
  {
    PlaybackDecision primaryResult = evaluate(profile, mediaContainer, mediaVideoCodec,
        mediaAudioCodec, mediaWidth, mediaHeight, isHDx00Extender,
        sourceBitrateKbps, availableBandwidthKbps,
        primary, sourceInterlaced, isPushTransport, clientCaps);

    // No switch possible / needed.
    if (primaryResult.decision == Decision.DIRECT_PLAY) return primaryResult;
    if (alternate == null || alternate.isEmpty()) return primaryResult;
    if (altPlayer == null || altPlayer.length() == 0) return primaryResult;
    if (altPlayer.equalsIgnoreCase(defaultPlayer)) return primaryResult;

    PlaybackDecision altResult = evaluate(profile, mediaContainer, mediaVideoCodec,
        mediaAudioCodec, mediaWidth, mediaHeight, isHDx00Extender,
        sourceBitrateKbps, availableBandwidthKbps,
        alternate, sourceInterlaced, isPushTransport, clientCaps);

    if (altResult.decision == Decision.DIRECT_PLAY)
    {
      if (sage.Sage.DBG) System.out.println("PlaybackDecisionEngine: switching player from "
          + defaultPlayer + " to " + altPlayer + " (primary=" + primaryResult.decision
          + " reason=" + primaryResult.reason + ")");
      return altResult.withPreferredPlayer(altPlayer);
    }

    return primaryResult;
  }

  // ==========================================================================
  // Playback Surface Capability Model (Protocol v2.1) — Phase 2
  //
  // Surface-aware evaluation path. Called from MiniPlayer when the client
  // advertised PLAYBACK_SURFACES and miniplayer/use_playback_surfaces=true;
  // otherwise the legacy V1/V2 evaluate() / evaluateWithPlayerSwitch() path
  // runs unchanged. See ROADMAP.md "Playback Surface capability model
  // (Protocol 2.1)" for design + phasing.
  //
  // Contract:
  //   - Evaluate each surface INDEPENDENTLY. Capabilities from separate
  //     surfaces MUST NEVER be merged.
  //   - Skip surfaces whose DELIVERY_MODES does not intersect what the
  //     server can actually serve (see SERVER_SERVABLE_DELIVERY_MODES).
  //   - Rank by (a) decision tier, (b) client-declared PRIORITY, (c) server
  //     CPU cost proxy, (d) deterministic id order.
  //   - Surface path does NOT consult ClientProfile — the surface IS the
  //     honest capability report. Profile stays as a hard-cap policy layer
  //     for a separate future extension.
  // ==========================================================================

  /**
   * Delivery modes the SageTV server can actually serve today. A surface
   * whose {@code DELIVERY_MODES} does not intersect this set is skipped
   * with a WARN when {@link #evaluateSurfaces} runs, since the server has
   * no way to deliver bytes to it. Order matches routing preference:
   * {@code pull} (cheapest), {@code push} (adaptive), {@code hls}
   * (segmented for iOS/PWA). Extend when DASH/WebRTC servers ship.
   */
  public static final java.util.Set<String> SERVER_SERVABLE_DELIVERY_MODES =
      java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<String>(
          java.util.Arrays.asList("pull", "pull-xcode", "push", "hls")));

  /**
   * Pairs a {@link PlaybackSurface} with the {@link PlaybackDecision} the
   * engine reached for THAT surface against a given source. Returned in
   * ranked order by {@link #evaluateSurfaces}; the first element is the
   * winning surface + decision.
   */
  public static final class SurfaceDecision
  {
    public final PlaybackSurface surface;
    public final PlaybackDecision decision;
    /**
     * Delivery mode the server picked for this surface (from the intersection
     * of surface's declared modes with {@link #SERVER_SERVABLE_DELIVERY_MODES},
     * respecting server preference order). Populated even for non-winning
     * results so the caller can inspect the runner-up path.
     */
    public final String chosenDeliveryMode;
    /**
     * The audio stream chosen by {@link #selectBestAudioStream}, or null when
     * the source has a single audio stream or the flat-string evaluator was
     * used. Carries the orderIndex needed for the ffmpeg {@code -map} and
     * whether the surface can natively decode the stream. Protocol 2.1.0003.
     */
    public final AudioStreamChoice audioStreamChoice;

    /**
     * For a {@code pull-xcode} delivery mode, the concrete server-native
     * XCODE_SETUP mode name the client/bridge must request (e.g. {@code
     * browserhd}, {@code browserhd_copyv}, {@code browserhd_remux},
     * {@code mpeg2tsremux}). null for pull/push/hls (no server transcode
     * mode to name). Emitted as {@code CAP_EFFECTIVE_DELIVERY=pull-xcode:<mode>}.
     */
    public final String chosenXcodeMode;

    public SurfaceDecision(PlaybackSurface surface, PlaybackDecision decision,
        String chosenDeliveryMode)
    {
      this(surface, decision, chosenDeliveryMode, null, null);
    }

    public SurfaceDecision(PlaybackSurface surface, PlaybackDecision decision,
        String chosenDeliveryMode, AudioStreamChoice audioStreamChoice)
    {
      this(surface, decision, chosenDeliveryMode, audioStreamChoice, null);
    }

    public SurfaceDecision(PlaybackSurface surface, PlaybackDecision decision,
        String chosenDeliveryMode, AudioStreamChoice audioStreamChoice, String chosenXcodeMode)
    {
      this.surface = surface;
      this.decision = decision;
      this.chosenDeliveryMode = chosenDeliveryMode;
      this.audioStreamChoice = audioStreamChoice;
      this.chosenXcodeMode = chosenXcodeMode;
    }

    @Override
    public String toString()
    {
      return "SurfaceDecision[surface=" + (surface == null ? "?" : surface.getId())
          + " priority=" + (surface == null ? 0 : surface.getPriority())
          + " delivery=" + chosenDeliveryMode
          + (chosenXcodeMode != null ? ":" + chosenXcodeMode : "")
          + " decision=" + decision + "]";
    }
  }

  /**
   * Result of multi-audio-stream selection against a surface. Carries the
   * chosen audio stream and whether it is natively decodable by the surface.
   */
  public static final class AudioStreamChoice
  {
    /** The chosen audio format. Never null when valid. */
    public final sage.media.format.AudioFormat audioFormat;
    /** True when the surface can natively decode this stream (no transcode). */
    public final boolean nativelyDecodable;

    public AudioStreamChoice(sage.media.format.AudioFormat af, boolean nativelyDecodable)
    {
      this.audioFormat = af;
      this.nativelyDecodable = nativelyDecodable;
    }

    @Override
    public String toString()
    {
      return "AudioStreamChoice[" + (audioFormat == null ? "null" : audioFormat.getFormatName()
          + " ch=" + audioFormat.getChannels()
          + " " + (audioFormat.getLanguage() == null ? "" : audioFormat.getLanguage()))
          + " native=" + nativelyDecodable + "]";
    }
  }

  /**
   * Select the best audio stream from a multi-audio-stream source for a given
   * surface. Implements the Protocol 2.1.0002 rule:
   *
   * <ol>
   *   <li>Filter to the server's preferred language (ISO 639-2/3 three-letter
   *       code, e.g. "eng"). If no streams match, use all streams (don't
   *       break playback over a language miss).</li>
   *   <li>Sort by quality: most channels descending, then highest bitrate
   *       descending.</li>
   *   <li>Walk from highest quality to lowest: the first stream the surface
   *       can natively decode → return it ({@code nativelyDecodable=true}).
   *       This avoids a transcode even if a higher-quality stream exists
   *       that the surface can't decode — "a native lower-quality stream
   *       beats a transcoded higher-quality stream."</li>
   *   <li>If NO stream is natively decodable → return the highest-quality
   *       one ({@code nativelyDecodable=false}). The caller will set the
   *       decision to {@code AUDIO_TRANSCODE}.</li>
   * </ol>
   *
   * <p>Language is derived from the server's configured locale
   * ({@code Sage.userLocale}). Audio stream language tags use ISO 639-2/3
   * (three-letter, e.g. "eng") while the locale uses ISO 639-1 (two-letter,
   * e.g. "en"). The comparison handles both forms.
   *
   * @param surface the winning surface to check codec support against.
   * @param cf the source container format with multiple audio streams.
   * @return the chosen audio stream + whether it's native, or {@code null}
   *         when no audio streams exist at all.
   */
  public static AudioStreamChoice selectBestAudioStream(
      PlaybackSurface surface, sage.media.format.ContainerFormat cf)
  {
    return selectBestAudioStream(surface, cf, null);
  }

  /**
   * 2.1.0007 / 2.1.0006 overload of the surface audio selector.
   *
   * <p>2.1.0007 — {@code clientLang} (CLIENT_AUDIO_LANGUAGE, ISO 639-1/2) is
   * honored ahead of the server locale. Language preference chain:
   * client language → server locale → all streams (never break playback over
   * a language miss).
   *
   * <p>2.1.0006 — a stream counts as {@code nativelyDecodable} only when the
   * surface can BOTH decode the codec AND actually reach that track in the
   * source container (see {@link PlaybackSurface#canAccessAudioTrack}). This
   * prevents the "one playable audio track means all tracks are safe"
   * assumption: e.g. an MPEG2-PS with AC3 5.1 in a later substream that the
   * client's demuxer can't reach must NOT be reported as direct-playable just
   * because the codec is decodable.
   */
  public static AudioStreamChoice selectBestAudioStream(
      PlaybackSurface surface, sage.media.format.ContainerFormat cf, String clientLang)
  {
    if (cf == null) return null;
    sage.media.format.AudioFormat[] allAudio = cf.getAudioFormats(false);
    if (allAudio == null || allAudio.length == 0) return null;
    final String container = cf.getFormatName();
    // Lowest orderIndex among audio streams = the container's "first" audio
    // track. Demuxer reachability rules (e.g. MPEG2-PS first_substream_only)
    // are keyed on this.
    int minAudioIndex = Integer.MAX_VALUE;
    for (sage.media.format.AudioFormat af : allAudio)
      if (af.getOrderIndex() < minAudioIndex) minAudioIndex = af.getOrderIndex();

    if (allAudio.length == 1)
    {
      boolean first = allAudio[0].getOrderIndex() == minAudioIndex;
      boolean ok = surface != null
          && surface.supportsAudioCodec(allAudio[0].getFormatName())
          && surface.canAccessAudioTrack(container, first);
      return new AudioStreamChoice(allAudio[0], ok);
    }

    // Determine server language preference (2-letter or 3-letter).
    String serverLang2 = "";
    String serverLang3 = "";
    if (sage.Sage.userLocale != null)
    {
      serverLang2 = sage.Sage.userLocale.getLanguage(); // "en"
      serverLang3 = sage.Sage.userLocale.getISO3Language(); // "eng"
    }
    String cl = (clientLang == null) ? "" : clientLang.trim();

    // Filter to preferred language. Client language wins; if it matches none,
    // fall back to server language; if that matches none either, use all.
    java.util.List<sage.media.format.AudioFormat> clientMatched =
        new java.util.ArrayList<sage.media.format.AudioFormat>();
    java.util.List<sage.media.format.AudioFormat> serverMatched =
        new java.util.ArrayList<sage.media.format.AudioFormat>();
    for (sage.media.format.AudioFormat af : allAudio)
    {
      String lang = af.getLanguage();
      if (lang == null || lang.length() == 0) continue;
      if (cl.length() > 0 && lang.equalsIgnoreCase(cl))
        clientMatched.add(af);
      if (lang.equalsIgnoreCase(serverLang2) || lang.equalsIgnoreCase(serverLang3))
        serverMatched.add(af);
    }
    java.util.List<sage.media.format.AudioFormat> candidates;
    String langPref;
    if (!clientMatched.isEmpty())
    {
      candidates = clientMatched;
      langPref = "client:" + cl;
    }
    else if (!serverMatched.isEmpty())
    {
      candidates = serverMatched;
      langPref = "server:" + (serverLang3.length() > 0 ? serverLang3 : serverLang2);
    }
    else
    {
      candidates = new java.util.ArrayList<sage.media.format.AudioFormat>();
      for (sage.media.format.AudioFormat af : allAudio) candidates.add(af);
      langPref = "none";
    }

    // Sort by quality: most channels desc, then highest bitrate desc.
    java.util.Collections.sort(candidates, new java.util.Comparator<sage.media.format.AudioFormat>() {
      @Override
      public int compare(sage.media.format.AudioFormat a, sage.media.format.AudioFormat b)
      {
        int ch = Integer.compare(b.getChannels(), a.getChannels());
        if (ch != 0) return ch;
        return Integer.compare(b.getBitrate(), a.getBitrate());
      }
    });

    if (sage.Sage.DBG)
    {
      StringBuilder sb = new StringBuilder("PlaybackDecisionEngine.selectBestAudioStream: candidates=[");
      for (int i = 0; i < candidates.size(); i++)
      {
        if (i > 0) sb.append(", ");
        sage.media.format.AudioFormat af = candidates.get(i);
        sb.append(af.getFormatName()).append(" ch=").append(af.getChannels())
          .append(" br=").append(af.getBitrate())
          .append(" lang=").append(af.getLanguage());
      }
      sb.append("] clientLang=").append(cl.length() > 0 ? cl : "(none)")
        .append(" serverLang=").append(serverLang3.length() > 0 ? serverLang3 : serverLang2)
        .append(" langPref=").append(langPref)
        .append(" container=").append(container);
      System.out.println(sb.toString());
    }

    // Walk highest-quality first: prefer native decode over transcode. A
    // stream is only "native" when the surface can decode the codec AND reach
    // the track in this container (2.1.0006 track-access gate). A native
    // lower-quality accessible stream beats a transcoded higher-quality one.
    for (sage.media.format.AudioFormat af : candidates)
    {
      boolean first = af.getOrderIndex() == minAudioIndex;
      if (surface != null
          && surface.supportsAudioCodec(af.getFormatName())
          && surface.canAccessAudioTrack(container, first))
      {
        if (sage.Sage.DBG) System.out.println("PlaybackDecisionEngine.selectBestAudioStream: "
            + "native match: " + af.getFormatName() + " ch=" + af.getChannels()
            + " lang=" + af.getLanguage() + " firstTrack=" + first);
        return new AudioStreamChoice(af, true);
      }
    }

    // No natively-decodable + accessible stream — return the highest quality
    // for transcode.
    sage.media.format.AudioFormat best = candidates.get(0);
    if (sage.Sage.DBG) System.out.println("PlaybackDecisionEngine.selectBestAudioStream: "
        + "no native+accessible match, will transcode highest quality: " + best.getFormatName()
        + " ch=" + best.getChannels() + " lang=" + best.getLanguage());
    return new AudioStreamChoice(best, false);
  }

  /**
   * Legacy V1 (9.2.16) overload of the multi-audio-stream selector. Uses the
   * client's coarse V1 {@code AUDIO_CODECS} set (a flat comma-separated list
   * of codec names like "AC3,AAC,MP3") instead of a per-surface codec list.
   * Applies the same rule as the surface variant:
   * <ol>
   *   <li>Filter to server language.</li>
   *   <li>Sort by quality (channels desc, bitrate desc).</li>
   *   <li>First natively-decodable stream wins (native = codec appears in
   *       the V1 set).</li>
   *   <li>If none decodable, return highest quality for transcode.</li>
   * </ol>
   *
   * <p>This ensures Legacy 9.2.16 clients also get the best language-matched,
   * quality-sorted audio stream instead of the legacy "lowest orderIndex"
   * behavior from {@code ContainerFormat.getAudioFormat()}.
   *
   * @param v1AudioCodecs the client's V1-reported audio codec set (may be
   *   null/empty if unknown — in which case every stream is treated as
   *   non-decodable and the highest quality is returned for transcode).
   * @param cf the source container format with multiple audio streams.
   * @return the chosen audio stream + whether it's native, or {@code null}
   *         when no audio streams exist at all.
   */
  @SuppressWarnings({"rawtypes"})
  public static AudioStreamChoice selectBestAudioStreamLegacy(
      java.util.Set v1AudioCodecs, sage.media.format.ContainerFormat cf)
  {
    return selectBestAudioStreamLegacy(v1AudioCodecs, cf, null);
  }

  /**
   * 2.1.0007 overload of the Legacy audio selector: honors a client-advertised
   * preferred audio language (CLIENT_AUDIO_LANGUAGE) ahead of the server
   * locale. Language preference chain: client language → server locale → all
   * streams. No track-access gate applies on the Legacy path (Legacy clients
   * advertise no surface and therefore no container-access rules).
   */
  @SuppressWarnings({"rawtypes"})
  public static AudioStreamChoice selectBestAudioStreamLegacy(
      java.util.Set v1AudioCodecs, sage.media.format.ContainerFormat cf, String clientLang)
  {
    if (cf == null) return null;
    sage.media.format.AudioFormat[] allAudio = cf.getAudioFormats(false);
    if (allAudio == null || allAudio.length == 0) return null;
    if (allAudio.length == 1)
    {
      boolean ok = v1CodecSetContains(v1AudioCodecs, allAudio[0].getFormatName());
      return new AudioStreamChoice(allAudio[0], ok);
    }

    // Determine server language preference (2-letter or 3-letter).
    String serverLang2 = "";
    String serverLang3 = "";
    if (sage.Sage.userLocale != null)
    {
      serverLang2 = sage.Sage.userLocale.getLanguage();
      serverLang3 = sage.Sage.userLocale.getISO3Language();
    }
    String cl = (clientLang == null) ? "" : clientLang.trim();

    // Filter to preferred language. Client language wins; if it matches none,
    // fall back to server language; if that matches none either, use all.
    java.util.List<sage.media.format.AudioFormat> clientMatched =
        new java.util.ArrayList<sage.media.format.AudioFormat>();
    java.util.List<sage.media.format.AudioFormat> serverMatched =
        new java.util.ArrayList<sage.media.format.AudioFormat>();
    for (sage.media.format.AudioFormat af : allAudio)
    {
      String lang = af.getLanguage();
      if (lang == null || lang.length() == 0) continue;
      if (cl.length() > 0 && lang.equalsIgnoreCase(cl))
        clientMatched.add(af);
      if (lang.equalsIgnoreCase(serverLang2) || lang.equalsIgnoreCase(serverLang3))
        serverMatched.add(af);
    }
    java.util.List<sage.media.format.AudioFormat> candidates;
    String langPref;
    if (!clientMatched.isEmpty())
    {
      candidates = clientMatched;
      langPref = "client:" + cl;
    }
    else if (!serverMatched.isEmpty())
    {
      candidates = serverMatched;
      langPref = "server:" + (serverLang3.length() > 0 ? serverLang3 : serverLang2);
    }
    else
    {
      candidates = new java.util.ArrayList<sage.media.format.AudioFormat>();
      for (sage.media.format.AudioFormat af : allAudio) candidates.add(af);
      langPref = "none";
    }

    // Sort by quality: most channels desc, then highest bitrate desc.
    java.util.Collections.sort(candidates, new java.util.Comparator<sage.media.format.AudioFormat>() {
      @Override
      public int compare(sage.media.format.AudioFormat a, sage.media.format.AudioFormat b)
      {
        int ch = Integer.compare(b.getChannels(), a.getChannels());
        if (ch != 0) return ch;
        return Integer.compare(b.getBitrate(), a.getBitrate());
      }
    });

    if (sage.Sage.DBG)
    {
      StringBuilder sb = new StringBuilder("PlaybackDecisionEngine.selectBestAudioStreamLegacy: candidates=[");
      for (int i = 0; i < candidates.size(); i++)
      {
        if (i > 0) sb.append(", ");
        sage.media.format.AudioFormat af = candidates.get(i);
        sb.append(af.getFormatName()).append(" ch=").append(af.getChannels())
          .append(" br=").append(af.getBitrate())
          .append(" lang=").append(af.getLanguage());
      }
      sb.append("] clientLang=").append(cl.length() > 0 ? cl : "(none)")
        .append(" serverLang=").append(serverLang3.length() > 0 ? serverLang3 : serverLang2)
        .append(" langPref=").append(langPref)
        .append(" v1AudioCodecs=").append(v1AudioCodecs);
      System.out.println(sb.toString());
    }

    // Walk highest-quality first: prefer native decode over transcode.
    for (sage.media.format.AudioFormat af : candidates)
    {
      if (v1CodecSetContains(v1AudioCodecs, af.getFormatName()))
      {
        if (sage.Sage.DBG) System.out.println("PlaybackDecisionEngine.selectBestAudioStreamLegacy: "
            + "native match: " + af.getFormatName() + " ch=" + af.getChannels()
            + " lang=" + af.getLanguage());
        return new AudioStreamChoice(af, true);
      }
    }

    // No native decode available — return the highest quality for transcode.
    sage.media.format.AudioFormat best = candidates.get(0);
    if (sage.Sage.DBG) System.out.println("PlaybackDecisionEngine.selectBestAudioStreamLegacy: "
        + "no native match, will transcode highest quality: " + best.getFormatName()
        + " ch=" + best.getChannels() + " lang=" + best.getLanguage());
    return new AudioStreamChoice(best, false);
  }

  /** Case- and alias-tolerant codec membership check against a V1 raw set. */
  @SuppressWarnings({"rawtypes"})
  private static boolean v1CodecSetContains(java.util.Set codecs, String formatName)
  {
    if (codecs == null || codecs.isEmpty() || formatName == null) return false;
    String canon = PlaybackSurfaceSet.canonicalAudioCodec(formatName);
    for (Object o : codecs)
    {
      if (o != null && canon.equals(PlaybackSurfaceSet.canonicalAudioCodec(o.toString())))
        return true;
    }
    return false;
  }

  /**
   * Evaluate a single {@link PlaybackSurface} against a source. Returns
   * a {@link PlaybackDecision} whose {@code decision} is one of DIRECT_PLAY,
   * REMUX, AUDIO_TRANSCODE, or TRANSCODE per the Protocol v2.1 case list.
   * Bandwidth budget is applied identically to the legacy evaluator. The
   * surface IS the honest capability report -- no profile, no constraints,
   * no ClientReportedCaps intersection.
   */
  public static PlaybackDecision evaluateForSurface(PlaybackSurface surface,
      String mediaContainer, String mediaVideoCodec, String mediaAudioCodec,
      int mediaWidth, int mediaHeight,
      int sourceBitrateKbps, int availableBandwidthKbps,
      boolean sourceInterlaced)
  {
    if (surface == null)
      return new PlaybackDecision(Decision.DIRECT_PLAY, "No surface (legacy path)", null, null, null);

    boolean containerOK = surface.supportsContainer(mediaContainer);
    boolean videoOK = (mediaVideoCodec == null || mediaVideoCodec.length() == 0)
        || surface.supportsVideoCodec(mediaVideoCodec);
    boolean audioOK = (mediaAudioCodec == null || mediaAudioCodec.length() == 0)
        || surface.supportsAudioCodec(mediaAudioCodec);

    // Bandwidth budget — identical to legacy path.
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
        targetBitrateKbps = budgetKbps;
      }
    }

    if (sage.Sage.DBG)
      System.out.println("PlaybackDecisionEngine.surface[" + surface.getId() + "]: "
          + "container=" + mediaContainer + "(" + containerOK + ") "
          + "video=" + mediaVideoCodec + "(" + videoOK + ") "
          + "audio=" + mediaAudioCodec + "(" + audioOK + ") "
          + "sourceKbps=" + sourceBitrateKbps + " availableKbps=" + availableBandwidthKbps
          + " bandwidthOK=" + bandwidthOK);

    // Case 1: Direct Play
    if (containerOK && videoOK && audioOK && bandwidthOK)
      return new PlaybackDecision(Decision.DIRECT_PLAY,
          "surface " + surface.getId() + " covers container+video+audio",
          mediaContainer, mediaVideoCodec, mediaAudioCodec);

    // Case 2: Remux — codecs OK, container wrong (bandwidth still must fit)
    if (videoOK && audioOK && bandwidthOK && !containerOK)
    {
      String tgtContainer = selectBestContainerForSurface(surface);
      return new PlaybackDecision(Decision.REMUX,
          "surface " + surface.getId() + " lacks container " + mediaContainer
          + ", remuxing to " + tgtContainer,
          tgtContainer, mediaVideoCodec, mediaAudioCodec);
    }

    // Case 3: Audio Transcode — video codec OK, audio codec not.
    // Container can be remuxed as part of the audio-transcode job.
    if (videoOK && !audioOK)
    {
      String tgtContainer = containerOK ? mediaContainer : selectBestContainerForSurface(surface);
      String tgtAudio = selectBestAudioCodecForSurface(surface, mediaAudioCodec);
      if (targetBitrateKbps == 0) targetBitrateKbps = 0;
      return new PlaybackDecision(Decision.AUDIO_TRANSCODE,
          "surface " + surface.getId() + " lacks audio " + mediaAudioCodec
          + ", transcoding audio to " + tgtAudio + " (video copy)",
          tgtContainer, mediaVideoCodec, tgtAudio, targetBitrateKbps);
    }

    // Case 4: Full Transcode — video codec not supported (or bandwidth exceeded).
    String tgtContainer = selectBestContainerForSurface(surface);
    String tgtVideo = videoOK ? mediaVideoCodec : selectBestVideoCodecForSurface(surface, mediaVideoCodec);
    String tgtAudio = audioOK ? mediaAudioCodec : selectBestAudioCodecForSurface(surface, mediaAudioCodec);
    String reason;
    if (!videoOK)         reason = "surface " + surface.getId() + " lacks video " + mediaVideoCodec;
    else if (!bandwidthOK) reason = "source " + sourceBitrateKbps + " kbps exceeds available "
        + availableBandwidthKbps + " kbps (target " + targetBitrateKbps + " kbps)";
    else                  reason = "full transcode fallback (surface " + surface.getId() + ")";
    return new PlaybackDecision(Decision.TRANSCODE, reason,
        tgtContainer, tgtVideo, tgtAudio, targetBitrateKbps);
  }

  /**
   * Rank every surface in {@code surfaces} against the source and return the
   * results sorted best-first. Surfaces whose {@code DELIVERY_MODES} does
   * not intersect {@link #SERVER_SERVABLE_DELIVERY_MODES} are dropped with a
   * WARN (server has no way to deliver to them). Returns an empty list only
   * when the input is empty or every surface was undeliverable.
   *
   * <p>Ranking order:
   * <ol>
   *   <li>Decision tier: DIRECT_PLAY &gt; REMUX &gt; AUDIO_TRANSCODE &gt; TRANSCODE</li>
   *   <li>Highest client-declared PRIORITY wins</li>
   *   <li>Server CPU cost proxy: {@code hls} &gt; {@code push} &gt; {@code pull}
   *       (pull is cheapest — no server-side muxing)</li>
   *   <li>Deterministic tiebreak: alphabetical surface id</li>
   * </ol>
   *
   * <p>The winner (index 0) is what {@link sage.MiniPlayer} adopts and
   * emits via {@code CAP_EFFECTIVE_SURFACE}. Runners-up are returned for
   * logging / future auto-fallback logic.
   */
  public static java.util.List<SurfaceDecision> evaluateSurfaces(
      PlaybackSurfaceSet surfaces,
      String mediaContainer, String mediaVideoCodec, String mediaAudioCodec,
      int mediaWidth, int mediaHeight,
      int sourceBitrateKbps, int availableBandwidthKbps,
      boolean sourceInterlaced)
  {
    return evaluateSurfaces(surfaces, mediaContainer, mediaVideoCodec, mediaAudioCodec,
        mediaWidth, mediaHeight, sourceBitrateKbps, availableBandwidthKbps, sourceInterlaced, null);
  }

  /**
   * Multi-audio-stream-aware overload. When {@code cf} is non-null and has
   * multiple audio streams, uses {@link #selectBestAudioStream} to pick the
   * best language-matched, quality-sorted, natively-decodable stream for
   * each surface before evaluating. Falls back to the flat
   * {@code mediaAudioCodec} string when {@code cf} is null or has ≤1 stream.
   */
  public static java.util.List<SurfaceDecision> evaluateSurfaces(
      PlaybackSurfaceSet surfaces,
      String mediaContainer, String mediaVideoCodec, String mediaAudioCodec,
      int mediaWidth, int mediaHeight,
      int sourceBitrateKbps, int availableBandwidthKbps,
      boolean sourceInterlaced,
      sage.media.format.ContainerFormat cf)
  {
    return evaluateSurfaces(surfaces, mediaContainer, mediaVideoCodec, mediaAudioCodec,
        mediaWidth, mediaHeight, sourceBitrateKbps, availableBandwidthKbps, sourceInterlaced,
        cf, null);
  }

  /**
   * 2.1.0007 overload: threads the client-advertised preferred audio language
   * (CLIENT_AUDIO_LANGUAGE) into {@link #selectBestAudioStream} so multi-audio
   * sources are matched to the client's language first, then the server
   * locale. {@code clientLang} may be null/empty (all legacy sessions and NG
   * clients that omit it) in which case the server locale is used as before.
   */
  public static java.util.List<SurfaceDecision> evaluateSurfaces(
      PlaybackSurfaceSet surfaces,
      String mediaContainer, String mediaVideoCodec, String mediaAudioCodec,
      int mediaWidth, int mediaHeight,
      int sourceBitrateKbps, int availableBandwidthKbps,
      boolean sourceInterlaced,
      sage.media.format.ContainerFormat cf,
      String clientLang)
  {
    if (surfaces == null || surfaces.isEmpty())
      return java.util.Collections.<SurfaceDecision>emptyList();

    boolean multiAudio = (cf != null && cf.getAudioFormats(false) != null
        && cf.getAudioFormats(false).length > 1);

    java.util.List<SurfaceDecision> results = new java.util.ArrayList<SurfaceDecision>();
    for (PlaybackSurface s : surfaces.asMap().values())
    {
      if (!surfaceHasAnyServableMode(s))
      {
        if (sage.Sage.DBG) System.out.println("PlaybackDecisionEngine.evaluateSurfaces: "
            + "skipping surface '" + s.getId() + "' — declared DELIVERY_MODES=" + s.getDeliveryModes()
            + " does not intersect server-servable " + SERVER_SERVABLE_DELIVERY_MODES);
        continue;
      }

      PlaybackDecision d;
      AudioStreamChoice asc = null;
      if (multiAudio)
      {
        // Multi-audio: pick the best stream for THIS surface using language +
        // quality + native-decode-over-transcode preference, with the
        // 2.1.0006 track-access gate applied inside selectBestAudioStream.
        asc = selectBestAudioStream(s, cf, clientLang);
        String chosenAudioCodec = (asc != null) ? asc.audioFormat.getFormatName() : mediaAudioCodec;
        boolean audioOK = (asc != null) ? asc.nativelyDecodable : false;
        d = evaluateForSurfaceWithAudioChoice(s, mediaContainer, mediaVideoCodec,
            chosenAudioCodec, audioOK, mediaWidth, mediaHeight,
            sourceBitrateKbps, availableBandwidthKbps, sourceInterlaced,
            asc);
      }
      else
      {
        d = evaluateForSurface(s, mediaContainer, mediaVideoCodec, mediaAudioCodec,
            mediaWidth, mediaHeight, sourceBitrateKbps, availableBandwidthKbps, sourceInterlaced);
      }

      String mode = pickDeliveryModeForDecision(s, d.decision);
      if (mode == null)
      {
        if (sage.Sage.DBG) System.out.println("PlaybackDecisionEngine.evaluateSurfaces: "
            + "surface '" + s.getId() + "' has no servable delivery mode for decision " + d.decision
            + " (declared=" + s.getDeliveryModes() + "); dropping");
        continue;
      }
      // For a pull-xcode delivery, resolve the concrete server-native XCODE_SETUP
      // mode the bridge will request (surface-family + decision aware).
      String xcodeMode = "pull-xcode".equals(mode) ? xcodeModeForDecision(s, d.decision) : null;
      results.add(new SurfaceDecision(s, d, mode, asc, xcodeMode));
    }
    java.util.Collections.sort(results, SURFACE_DECISION_COMPARATOR);
    return results;
  }

  /**
   * Internal evaluator that uses a pre-chosen audio stream from
   * {@link #selectBestAudioStream}. Callers already know whether the
   * audio is natively decodable, so the check is pre-resolved.
   */
  private static PlaybackDecision evaluateForSurfaceWithAudioChoice(
      PlaybackSurface surface,
      String mediaContainer, String mediaVideoCodec, String chosenAudioCodec,
      boolean audioNativelyDecodable,
      int mediaWidth, int mediaHeight,
      int sourceBitrateKbps, int availableBandwidthKbps,
      boolean sourceInterlaced,
      AudioStreamChoice asc)
  {
    if (surface == null)
      return new PlaybackDecision(Decision.DIRECT_PLAY, "No surface (legacy path)", null, null, null);

    boolean containerOK = surface.supportsContainer(mediaContainer);
    boolean videoOK = (mediaVideoCodec == null || mediaVideoCodec.length() == 0)
        || surface.supportsVideoCodec(mediaVideoCodec);
    boolean audioOK = audioNativelyDecodable;

    boolean bandwidthOK = true;
    int targetBitrateKbps = 0;
    if (sourceBitrateKbps > 0 && availableBandwidthKbps > 0)
    {
      float safety = sage.Sage.getFloat("playback/bandwidth_safety_factor", 0.85f);
      if (safety <= 0f || safety > 1f) safety = 0.85f;
      int budgetKbps = (int) (availableBandwidthKbps * safety);
      if (sourceBitrateKbps > budgetKbps) { bandwidthOK = false; targetBitrateKbps = budgetKbps; }
    }

    String ascDesc = (asc != null && asc.audioFormat != null)
        ? asc.audioFormat.getFormatName() + " ch=" + asc.audioFormat.getChannels()
          + " lang=" + asc.audioFormat.getLanguage() + " native=" + asc.nativelyDecodable
        : "?";
    if (sage.Sage.DBG)
      System.out.println("PlaybackDecisionEngine.surface[" + surface.getId() + "]: "
          + "container=" + mediaContainer + "(" + containerOK + ") "
          + "video=" + mediaVideoCodec + "(" + videoOK + ") "
          + "audio=" + chosenAudioCodec + "(" + audioOK + ") "
          + "audioStream=[" + ascDesc + "] "
          + "sourceKbps=" + sourceBitrateKbps + " availableKbps=" + availableBandwidthKbps
          + " bandwidthOK=" + bandwidthOK);

    if (containerOK && videoOK && audioOK && bandwidthOK)
      return new PlaybackDecision(Decision.DIRECT_PLAY,
          "surface " + surface.getId() + " covers container+video+audio (stream: " + ascDesc + ")",
          mediaContainer, mediaVideoCodec, chosenAudioCodec);

    if (videoOK && audioOK && bandwidthOK && !containerOK)
    {
      String tgtContainer = selectBestContainerForSurface(surface);
      return new PlaybackDecision(Decision.REMUX,
          "surface " + surface.getId() + " lacks container " + mediaContainer + ", remux to " + tgtContainer
          + " (audio stream: " + ascDesc + ")",
          tgtContainer, mediaVideoCodec, chosenAudioCodec);
    }

    if (videoOK && !audioOK)
    {
      String tgtContainer = containerOK ? mediaContainer : selectBestContainerForSurface(surface);
      String tgtAudio = selectBestAudioCodecForSurface(surface, chosenAudioCodec);
      return new PlaybackDecision(Decision.AUDIO_TRANSCODE,
          "surface " + surface.getId() + " cannot natively decode audio " + chosenAudioCodec
          + " (stream: " + ascDesc + "), transcode to " + tgtAudio + " (video copy)",
          tgtContainer, mediaVideoCodec, tgtAudio, targetBitrateKbps);
    }

    String tgtContainer = selectBestContainerForSurface(surface);
    String tgtVideo = videoOK ? mediaVideoCodec : selectBestVideoCodecForSurface(surface, mediaVideoCodec);
    String tgtAudio = audioOK ? chosenAudioCodec : selectBestAudioCodecForSurface(surface, chosenAudioCodec);
    String reason;
    if (!videoOK)         reason = "surface " + surface.getId() + " lacks video " + mediaVideoCodec;
    else if (!bandwidthOK) reason = "source " + sourceBitrateKbps + " kbps exceeds available " + availableBandwidthKbps + " kbps";
    else                  reason = "full transcode fallback (surface " + surface.getId() + ")";
    return new PlaybackDecision(Decision.TRANSCODE, reason,
        tgtContainer, tgtVideo, tgtAudio, targetBitrateKbps);
  }

  /**
   * Returns true when {@code surface}'s declared delivery modes intersect
   * {@link #SERVER_SERVABLE_DELIVERY_MODES}. Cheap pre-filter used by
   * {@link #evaluateSurfaces} to skip surfaces the server has no way to
   * feed bytes to (e.g. a hypothetical webrtc-only surface today).
   */
  private static boolean surfaceHasAnyServableMode(PlaybackSurface s)
  {
    if (s == null) return false;
    java.util.List<String> declared = s.getDeliveryModes();
    if (declared == null || declared.isEmpty()) return false;
    for (String m : declared) if (SERVER_SERVABLE_DELIVERY_MODES.contains(m)) return true;
    return false;
  }

  /**
   * Select the delivery mode the server should use for this surface, GIVEN
   * the decision the engine just reached. This is the critical piece that
   * routes REMUX / AUDIO_TRANSCODE / TRANSCODE decisions away from pull
   * mode (which would serve the raw file and defeat the transform) toward
   * push / hls (which the server can feed transformed bytes into).
   *
   * <p>Rules:
   * <ul>
   *   <li>{@code DIRECT_PLAY}: any declared mode works. Prefer {@code pull}
   *       (cheapest — no server-side muxing), then {@code push}, then
   *       {@code hls}.</li>
   *   <li>{@code REMUX} / {@code AUDIO_TRANSCODE} / {@code TRANSCODE}: pull
   *       is unusable (raw file bypasses the transform pipeline). Prefer
   *       {@code push} (adaptive), then {@code hls} (segmented). Returns
   *       {@code null} if the surface declares only pull — that
   *       configuration can't fulfill a non-direct decision and the surface
   *       is dropped from the ranking.</li>
   * </ul>
   * Returns {@code null} to signal "no viable mode for this decision"; the
   * caller drops the surface with a WARN.
   */
  private static String pickDeliveryModeForDecision(PlaybackSurface s, Decision d)
  {
    if (s == null) return null;
    java.util.List<String> declared = s.getDeliveryModes();
    if (declared == null || declared.isEmpty()) return null;
    if (d == Decision.DIRECT_PLAY)
    {
      // Any servable mode; cheapest first.
      if (declared.contains("pull")) return "pull";
      if (declared.contains("push")) return "push";
      if (declared.contains("hls"))  return "hls";
      return null;
    }
    // Non-DIRECT: server must feed transformed bytes; raw pull cannot, but
    // pull-xcode (a pull of a SERVER-TRANSCODED stream) can and is preferred --
    // it rides the single control/HTTP port via the bridge's /msproxy and lets
    // the PWA stop sniffing. Fall back to push (adaptive) then hls (segmented).
    if (declared.contains("pull-xcode")) return "pull-xcode";
    if (declared.contains("push")) return "push";
    if (declared.contains("hls"))  return "hls";
    return null;
  }

  /**
   * Map an engine {@link Decision} to the concrete server-native XCODE_SETUP
   * mode a {@code pull-xcode} surface must request. Browser/MSE surfaces get
   * fragmented-MP4 modes; native/AVPlay (TV) surfaces get MPEG-TS modes:
   * <pre>
   *                     browser (fMP4)     TV/AVPlay (TS)
   *   REMUX             browserhd_remux    mpeg2tsremux
   *   AUDIO_TRANSCODE   browserhd_copyv    audioonly
   *   TRANSCODE         browserhd          dynamich264
   * </pre>
   */
  private static String xcodeModeForDecision(PlaybackSurface s, Decision d)
  {
    boolean fmp4 = surfaceWantsFmp4(s);
    if (d == Decision.REMUX)           return fmp4 ? "browserhd_remux" : "mpeg2tsremux";
    if (d == Decision.AUDIO_TRANSCODE) return fmp4 ? "browserhd_copyv" : "audioonly";
    return fmp4 ? "browserhd" : "dynamich264"; // FULL_TRANSCODE (and any future tier)
  }

  /**
   * True when the surface consumes fragmented MP4 (browser MSE), false when it
   * takes MPEG-TS (native/AVPlay TV). Route wins ({@code mse} vs
   * {@code native}/{@code avplay}); otherwise infer from container caps;
   * default to fMP4 when ambiguous (the browser is the primary pull-xcode user).
   */
  private static boolean surfaceWantsFmp4(PlaybackSurface s)
  {
    if (s == null) return true;
    String route = s.getRoute();
    if (route != null)
    {
      String r = route.toLowerCase(java.util.Locale.ROOT);
      if (r.contains("mse")) return true;
      if (r.contains("native") || r.contains("avplay")) return false;
    }
    boolean mp4 = s.supportsContainer("MP4");
    boolean ts = s.supportsContainer("MPEG2-TS");
    if (mp4 && !ts) return true;
    if (ts && !mp4) return false;
    return true;
  }

  private static int decisionTierRank(Decision d)
  {
    if (d == Decision.DIRECT_PLAY)     return 0;
    if (d == Decision.REMUX)           return 1;
    if (d == Decision.AUDIO_TRANSCODE) return 2;
    return 3; // TRANSCODE
  }

  private static int deliveryModeCpuRank(String mode)
  {
    if ("pull".equals(mode)) return 0;
    if ("pull-xcode".equals(mode)) return 1;
    if ("push".equals(mode)) return 2;
    if ("hls".equals(mode))  return 3;
    return 4;
  }

  private static final java.util.Comparator<SurfaceDecision> SURFACE_DECISION_COMPARATOR =
      new java.util.Comparator<SurfaceDecision>() {
        @Override
        public int compare(SurfaceDecision a, SurfaceDecision b) {
          int at = decisionTierRank(a.decision.decision);
          int bt = decisionTierRank(b.decision.decision);
          if (at != bt) return Integer.compare(at, bt);
          // Higher PRIORITY wins (so negate).
          int ap = a.surface == null ? 0 : a.surface.getPriority();
          int bp = b.surface == null ? 0 : b.surface.getPriority();
          if (ap != bp) return Integer.compare(bp, ap);
          // Cheaper delivery wins.
          int ac = deliveryModeCpuRank(a.chosenDeliveryMode);
          int bc = deliveryModeCpuRank(b.chosenDeliveryMode);
          if (ac != bc) return Integer.compare(ac, bc);
          // Deterministic tiebreak.
          String aid = a.surface == null ? "" : a.surface.getId();
          String bid = b.surface == null ? "" : b.surface.getId();
          return aid.compareTo(bid);
        }
      };

  // ---- surface-scoped target-codec pickers (independent of ClientProfile) ----

  private static String selectBestContainerForSurface(PlaybackSurface s)
  {
    if (s == null) return "MP4";
    java.util.List<String> c = s.getContainers();
    if (c.isEmpty()) return "MP4";
    // Prefer MP4 (universal), then MPEG2-TS (HLS-friendly), then whatever is available.
    if (c.contains("MP4")) return "MP4";
    if (c.contains("MPEG2-TS")) return "MPEG2-TS";
    return c.iterator().next();
  }

  private static String selectBestVideoCodecForSurface(PlaybackSurface s, String sourceCodec)
  {
    if (s == null) return sourceCodec == null ? "H264" : sourceCodec;
    java.util.List<String> v = s.getVideoCodecs();
    if (v.isEmpty()) return sourceCodec == null ? "H264" : sourceCodec;
    if (sourceCodec != null && v.contains(sourceCodec)) return sourceCodec;
    // Prefer HEVC when the surface supports it (better quality/bitrate), else H264.
    if (v.contains("HEVC")) return "HEVC";
    if (v.contains("H264")) return "H264";
    return v.iterator().next();
  }

  private static String selectBestAudioCodecForSurface(PlaybackSurface s, String sourceCodec)
  {
    if (s == null) return sourceCodec == null ? "AAC" : sourceCodec;
    java.util.List<String> a = s.getAudioCodecs();
    if (a.isEmpty()) return sourceCodec == null ? "AAC" : sourceCodec;
    if (sourceCodec != null && a.contains(sourceCodec)) return sourceCodec;
    // Prefer EAC3 for surround, then AC3, then AAC (universal).
    if (a.contains("EAC3")) return "EAC3";
    if (a.contains("AC3"))  return "AC3";
    if (a.contains("AAC"))  return "AAC";
    return a.iterator().next();
  }
}
