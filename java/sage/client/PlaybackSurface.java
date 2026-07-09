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

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A single concrete playback path exposed by a client -- one honest set of
 * capabilities that corresponds to ONE actual decode pipeline (e.g.
 * {@code android_media3}, {@code android_ijk}, {@code pwa_native},
 * {@code pwa_mse}, {@code windows_mediafoundation}). NOT a device, browser,
 * or OS -- those are properties of the client, not of a decode path.
 *
 * <p>The server evaluates each surface INDEPENDENTLY and ranks them at
 * decision time; capabilities from separate surfaces MUST NEVER be merged.
 * This is what fixes the class of bug where a device has two decoders with
 * different capability sets (Tizen native vs Tizen MSE; ExoPlayer vs
 * IJKPlayer on Android) and a single flat codec list picks the wrong path.
 *
 * <p>Introduced by the Playback Surface Capability Model, Protocol v2.1.
 * See ROADMAP.md "Playback Surface capability model (Protocol 2.1)" for the
 * full contract, canonical name reference, and phasing plan.
 *
 * <p>Protocol 2.1.0006 adds the TRACK-ACCESS dimension, orthogonal to decode
 * capability. Decode capability answers "can this surface decode AC3?";
 * track-access capability answers "can this surface parse/select the SPECIFIC
 * (e.g. 5.1) track it wants inside THIS container?". A surface may decode a
 * codec fine yet be unable to reach a non-first audio substream in MPEG2-PS.
 * The server must NEVER assume "one playable track means all tracks are
 * safe" -- see {@link #canAccessAudioTrack}.
 */
public final class PlaybackSurface
{
  private final String id;
  private final String route;
  private final int priority;
  private final List<String> deliveryModes;
  private final List<String> videoCodecs;
  private final List<String> audioCodecs;
  private final List<String> containers;
  // --- 2.1.0006 track-access dimension ---
  // audioTrackAccess: all | primary_only | default_only | none (canonical lowercase).
  //   Absent from the wire => conservative default "default_only" (NEVER "all").
  private final String audioTrackAccess;
  // audioTrackSelectionMode: client | server (canonical lowercase).
  //   client = the client demux picks the track from the raw stream.
  //   server = the server must preselect/emit only the chosen track.
  private final String audioTrackSelectionMode;
  // audioContainerRules: canonical-container -> list of rule tokens
  //   (e.g. MPEG2-PS -> [first_substream_only, order_sensitive]).
  private final Map<String, List<String>> audioContainerRules;

  /**
   * Backward-compatible constructor (pre-2.1.0006). Applies the conservative
   * track-access defaults: {@code audioTrackAccess="default_only"},
   * {@code audioTrackSelectionMode="client"}, no container rules.
   */
  public PlaybackSurface(String id, String route, int priority,
      List<String> deliveryModes, List<String> videoCodecs,
      List<String> audioCodecs, List<String> containers)
  {
    this(id, route, priority, deliveryModes, videoCodecs, audioCodecs, containers,
        null, null, null);
  }

  /**
   * Full constructor including the 2.1.0006 track-access dimension.
   *
   * @param audioTrackAccess null/empty => conservative default "default_only".
   * @param audioTrackSelectionMode null/empty => default "client".
   * @param audioContainerRules null => no container-specific rules.
   */
  public PlaybackSurface(String id, String route, int priority,
      List<String> deliveryModes, List<String> videoCodecs,
      List<String> audioCodecs, List<String> containers,
      String audioTrackAccess, String audioTrackSelectionMode,
      Map<String, List<String>> audioContainerRules)
  {
    if (id == null || id.length() == 0)
      throw new IllegalArgumentException("PlaybackSurface id must be non-empty");
    this.id = id;
    this.route = route == null ? "" : route;
    this.priority = priority;
    this.deliveryModes = deliveryModes == null
        ? Collections.<String>emptyList()
        : Collections.unmodifiableList(deliveryModes);
    this.videoCodecs = videoCodecs == null
        ? Collections.<String>emptyList()
        : Collections.unmodifiableList(videoCodecs);
    this.audioCodecs = audioCodecs == null
        ? Collections.<String>emptyList()
        : Collections.unmodifiableList(audioCodecs);
    this.containers = containers == null
        ? Collections.<String>emptyList()
        : Collections.unmodifiableList(containers);
    // Conservative default: NEVER assume "all" when the client didn't declare it.
    this.audioTrackAccess = (audioTrackAccess == null || audioTrackAccess.length() == 0)
        ? "default_only" : audioTrackAccess;
    this.audioTrackSelectionMode = (audioTrackSelectionMode == null || audioTrackSelectionMode.length() == 0)
        ? "client" : audioTrackSelectionMode;
    this.audioContainerRules = audioContainerRules == null
        ? Collections.<String, List<String>>emptyMap()
        : Collections.unmodifiableMap(audioContainerRules);
  }

  public String getId() { return id; }
  public String getRoute() { return route; }
  public int getPriority() { return priority; }
  public List<String> getDeliveryModes() { return deliveryModes; }
  public List<String> getVideoCodecs() { return videoCodecs; }
  public List<String> getAudioCodecs() { return audioCodecs; }
  public List<String> getContainers() { return containers; }
  public String getAudioTrackAccess() { return audioTrackAccess; }
  public String getAudioTrackSelectionMode() { return audioTrackSelectionMode; }
  public Map<String, List<String>> getAudioContainerRules() { return audioContainerRules; }

  /** Container rules for a specific container (canonicalized), or empty list. */
  public List<String> getContainerRules(String container)
  {
    if (container == null) return Collections.<String>emptyList();
    List<String> r = audioContainerRules.get(PlaybackSurfaceSet.canonicalContainer(container));
    return r == null ? Collections.<String>emptyList() : r;
  }

  public boolean supportsDeliveryMode(String mode)
  {
    return mode != null
        && deliveryModes.contains(PlaybackSurfaceSet.canonicalDeliveryMode(mode));
  }

  /**
   * Alias- and case-tolerant check against the surface's declared video
   * codec list. SageTV's FormatParser emits internal spellings (e.g.
   * {@code "H.264"}, {@code "MPEG2-Video"}) that don't literally match the
   * canonical v2.1 tokens ({@code "H264"}, {@code "MPEG2-VIDEO"}) even
   * though they refer to the same codec. Canonicalizing the query via
   * {@link PlaybackSurfaceSet#canonicalVideoCodec} before comparison fixes
   * the class of bug that made an MPG2 source appear un-decodable to the
   * ijk software surface (which does list MPEG2-VIDEO) purely because of
   * a case mismatch.
   */
  public boolean supportsVideoCodec(String codec)
  {
    return codec != null
        && videoCodecs.contains(PlaybackSurfaceSet.canonicalVideoCodec(codec));
  }

  /** Alias- and case-tolerant check against the surface's audio codec list. */
  public boolean supportsAudioCodec(String codec)
  {
    return codec != null
        && audioCodecs.contains(PlaybackSurfaceSet.canonicalAudioCodec(codec));
  }

  /** Alias- and case-tolerant check against the surface's container list. */
  public boolean supportsContainer(String container)
  {
    return container != null
        && containers.contains(PlaybackSurfaceSet.canonicalContainer(container));
  }

  /**
   * The hard TRACK-ACCESS gate (Protocol 2.1.0006). Returns true when this
   * surface can actually REACH the given audio track inside the given
   * container -- independent of whether it can decode the codec.
   *
   * <p>The server must call this BEFORE granting DIRECT_PLAY / REMUX for a
   * chosen (e.g. 5.1) audio track. If it returns false, the track the server
   * wants is not reachable by the client's demuxer, so DIRECT_PLAY/REMUX is
   * off the table for this surface -- the server must either fall to a
   * reachable lower-quality track it can also decode, pick another surface,
   * or AUDIO_TRANSCODE (preselect + recode the wanted track server-side).
   *
   * <p>Semantics (conservative by design -- absent access defaults to
   * {@code default_only}, NEVER {@code all}):
   * <ul>
   *   <li>Container rule {@code first_substream_only} (e.g. MPEG2-PS): only
   *       the first audio track is reachable, regardless of access level.</li>
   *   <li>{@code all}: any track reachable (unless a container rule restricts).</li>
   *   <li>{@code primary_only}: only the first/primary track.</li>
   *   <li>{@code default_only}: only the container's default track (treated as
   *       the first track for gating -- conservative).</li>
   *   <li>{@code none}: no explicit track selection; only the first/default
   *       track the demuxer lands on is safe.</li>
   * </ul>
   *
   * @param container the source container (canonicalized internally).
   * @param isFirstAudioTrack true when the chosen track is the first/lowest
   *   orderIndex audio stream (also treated as the default for gating).
   */
  public boolean canAccessAudioTrack(String container, boolean isFirstAudioTrack)
  {
    List<String> rules = getContainerRules(container);
    if (rules.contains("first_substream_only") && !isFirstAudioTrack)
      return false;
    String access = audioTrackAccess; // canonical lowercase, defaulted in ctor
    if ("all".equals(access))
    {
      // "all_tracks" container rule affirms all reachable; otherwise still all
      // unless a first_substream_only rule already returned false above.
      return true;
    }
    if ("primary_only".equals(access)) return isFirstAudioTrack;
    if ("default_only".equals(access)) return isFirstAudioTrack;
    if ("none".equals(access)) return isFirstAudioTrack;
    // Unknown/legacy value: conservative.
    return isFirstAudioTrack;
  }

  @Override
  public String toString()
  {
    return "PlaybackSurface[id=" + id
        + " route=" + route
        + " priority=" + priority
        + " delivery=" + deliveryModes
        + " video=" + videoCodecs
        + " audio=" + audioCodecs
        + " containers=" + containers
        + " audioTrackAccess=" + audioTrackAccess
        + " audioTrackSelectionMode=" + audioTrackSelectionMode
        + " audioContainerRules=" + audioContainerRules + "]";
  }
}
