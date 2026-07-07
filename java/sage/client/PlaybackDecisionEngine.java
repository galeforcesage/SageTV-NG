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
}
