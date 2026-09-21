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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
  // --- Server video enhancement: output-limit dimension ---
  // What this surface can actually DECODE at, which is not the same question as
  // which codecs it lists. Plenty of decoders advertise HEVC and top out at
  // 1080p. Zero means "the client didn't say", which reads as unknown and
  // therefore blocks enhancement -- never as unlimited.
  private final int maxOutputWidth;
  private final int maxOutputHeight;
  private final int maxFps;
  // --- Interlaced-decode dimension (Protocol 2.1) ---
  // Canonical video codecs this surface declared it CANNOT decode interlaced
  // (via a per-codec {@code interlaced=false} or {@code scan=progressive}
  // attribute on PLAYBACK_SURFACE_<id>_VIDEO_CODECS). Empty means "the client
  // said nothing", which -- fail-open -- leaves an interlaced source copied
  // exactly as before. Only an EXPLICIT declaration escalates to a
  // server-side deinterlacing transcode. Browser/MSE decode paths populate
  // this because no browser MSE implementation decodes interlaced H.264.
  private final Set<String> interlacedUnsupportedCodecs;
  // --- Per-container transport dimension (Protocol 2.1) ---
  // Canonical container -> {pushAllowed, pullAllowed}. A container that the
  // client declared with an explicit transport attribute (e.g.
  // {@code MPEG2-PS;push=true;pull=false} on PLAYBACK_SURFACE_<id>_CONTAINERS)
  // gets an entry here; a bare container name gets NO entry and is treated as
  // "both transports allowed" (fail-open, so every pre-2.1 client is
  // unaffected). This is the surface-native equivalent of the legacy per-player
  // {@code IJK_CONTAINER_CONSTRAINTS} push/pull rows: it lets a surface say
  // "MPEG2-PS is a push-only container for me" -- a program stream the client
  // can receive over the push transport (libavformat demux) but CANNOT
  // direct-play from a raw pull (a pull OPEN of the .mpg returns NON_MEDIA). The
  // flat CONTAINERS+DELIVERY_MODES model could not express that, so the server
  // would pick the cheapest transport (pull) and strand the client. See
  // {@link #containerAllowsTransport}.
  private final Map<String, boolean[]> containerTransports;
  // --- Audio multichannel decode dimension (Protocol 2.1) ---
  // Max audio channels this surface can decode/render (e.g. 6 for 5.1, 8 for
  // 7.1) via PLAYBACK_SURFACE_<id>_AUDIO_MAX_CHANNELS. 0 == "the client didn't
  // declare it", which every consumer reads as legacy/undeclared and resolves
  // from the negotiated audio codec instead of assuming multichannel. Only an
  // EXPLICIT value >= 6 opts a surface into preserved surround on a path that
  // would otherwise downmix to stereo (e.g. the GPU-enhance AC-4 sidecar); an
  // explicit value < 6 forces stereo even for an EAC3/AC3 client.
  private final int audioMaxChannels;

  /**
   * Whether this surface can supply live delivered-goodput back to the server
   * for a managed transcode: {@code "xcode_adjust"} (a pull-xcode browserhd
   * session whose proxy sends {@code XCODE_ADJUST} on the control socket) or
   * {@code "none"} (native HLS / direct-play / undeclared). A ranking + telemetry
   * hint ONLY -- never a route selector; the decision engine never fabricates a
   * route from it. Defaults to {@code "none"} for every legacy/silent client.
   * (Protocol 2.1 additive dimension.)
   */
  private final String bandwidthFeedback;

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
    this(id, route, priority, deliveryModes, videoCodecs, audioCodecs, containers,
        audioTrackAccess, audioTrackSelectionMode, audioContainerRules, 0, 0, 0);
  }

  /**
   * Full constructor including the output-limit dimension used by server video
   * enhancement.
   *
   * @param maxOutputWidth  decoder width limit, 0 when the client didn't declare it.
   * @param maxOutputHeight decoder height limit, 0 when the client didn't declare it.
   * @param maxFps          decoder frame-rate limit, 0 when the client didn't declare it.
   */
  public PlaybackSurface(String id, String route, int priority,
      List<String> deliveryModes, List<String> videoCodecs,
      List<String> audioCodecs, List<String> containers,
      String audioTrackAccess, String audioTrackSelectionMode,
      Map<String, List<String>> audioContainerRules,
      int maxOutputWidth, int maxOutputHeight, int maxFps)
  {
    this(id, route, priority, deliveryModes, videoCodecs, audioCodecs, containers,
        audioTrackAccess, audioTrackSelectionMode, audioContainerRules,
        maxOutputWidth, maxOutputHeight, maxFps, null);
  }

  /**
   * Full constructor including the interlaced-decode dimension (Protocol 2.1).
   *
   * @param interlacedUnsupportedCodecs canonical video codecs this surface
   *   declared it cannot decode interlaced; null/empty => none declared, which
   *   fail-open leaves interlaced sources copied unchanged.
   */
  public PlaybackSurface(String id, String route, int priority,
      List<String> deliveryModes, List<String> videoCodecs,
      List<String> audioCodecs, List<String> containers,
      String audioTrackAccess, String audioTrackSelectionMode,
      Map<String, List<String>> audioContainerRules,
      int maxOutputWidth, int maxOutputHeight, int maxFps,
      Set<String> interlacedUnsupportedCodecs)
  {
    this(id, route, priority, deliveryModes, videoCodecs, audioCodecs, containers,
        audioTrackAccess, audioTrackSelectionMode, audioContainerRules,
        maxOutputWidth, maxOutputHeight, maxFps, interlacedUnsupportedCodecs, null);
  }

  /**
   * Full constructor including the per-container transport dimension (Protocol
   * 2.1).
   *
   * @param containerTransports canonical-container -&gt; {pushAllowed,
   *   pullAllowed}; null/empty =&gt; no container declared a transport
   *   restriction, so every container is reachable on either transport
   *   (fail-open). Only an EXPLICIT {@code push=false} / {@code pull=false} on a
   *   container restricts it, which is how a surface declares a push-only
   *   container such as MPEG2-PS.
   */
  public PlaybackSurface(String id, String route, int priority,
      List<String> deliveryModes, List<String> videoCodecs,
      List<String> audioCodecs, List<String> containers,
      String audioTrackAccess, String audioTrackSelectionMode,
      Map<String, List<String>> audioContainerRules,
      int maxOutputWidth, int maxOutputHeight, int maxFps,
      Set<String> interlacedUnsupportedCodecs,
      Map<String, boolean[]> containerTransports)
  {
    this(id, route, priority, deliveryModes, videoCodecs, audioCodecs, containers,
        audioTrackAccess, audioTrackSelectionMode, audioContainerRules,
        maxOutputWidth, maxOutputHeight, maxFps, interlacedUnsupportedCodecs,
        containerTransports, 0);
  }

  /**
   * Full constructor including the audio multichannel-decode dimension
   * (Protocol 2.1). Delegates bandwidth-feedback to the default {@code "none"}.
   *
   * @param audioMaxChannels max audio channels this surface can decode/render;
   *   0 =&gt; undeclared (legacy), which resolves from the negotiated audio codec
   *   rather than assuming surround. An explicit value &gt;= 6 opts into preserved
   *   5.1/7.1 on paths that would otherwise downmix to stereo; an explicit value
   *   &lt; 6 forces stereo.
   */
  public PlaybackSurface(String id, String route, int priority,
      List<String> deliveryModes, List<String> videoCodecs,
      List<String> audioCodecs, List<String> containers,
      String audioTrackAccess, String audioTrackSelectionMode,
      Map<String, List<String>> audioContainerRules,
      int maxOutputWidth, int maxOutputHeight, int maxFps,
      Set<String> interlacedUnsupportedCodecs,
      Map<String, boolean[]> containerTransports,
      int audioMaxChannels)
  {
    this(id, route, priority, deliveryModes, videoCodecs, audioCodecs, containers,
        audioTrackAccess, audioTrackSelectionMode, audioContainerRules,
        maxOutputWidth, maxOutputHeight, maxFps, interlacedUnsupportedCodecs,
        containerTransports, audioMaxChannels, "none");
  }

  /**
   * Full constructor including the bandwidth-feedback capability (Protocol 2.1
   * additive). See {@link #getBandwidthFeedback()}.
   *
   * @param bandwidthFeedback {@code "xcode_adjust"} or {@code "none"};
   *   null/empty =&gt; {@code "none"} (legacy/undeclared).
   */
  public PlaybackSurface(String id, String route, int priority,
      List<String> deliveryModes, List<String> videoCodecs,
      List<String> audioCodecs, List<String> containers,
      String audioTrackAccess, String audioTrackSelectionMode,
      Map<String, List<String>> audioContainerRules,
      int maxOutputWidth, int maxOutputHeight, int maxFps,
      Set<String> interlacedUnsupportedCodecs,
      Map<String, boolean[]> containerTransports,
      int audioMaxChannels, String bandwidthFeedback)
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
    // Negative values are nonsense on the wire; normalize them to "unknown".
    this.maxOutputWidth  = Math.max(0, maxOutputWidth);
    this.maxOutputHeight = Math.max(0, maxOutputHeight);
    this.maxFps          = Math.max(0, maxFps);
    if (interlacedUnsupportedCodecs == null || interlacedUnsupportedCodecs.isEmpty())
      this.interlacedUnsupportedCodecs = Collections.<String>emptySet();
    else
    {
      Set<String> norm = new HashSet<String>(interlacedUnsupportedCodecs.size());
      for (String c : interlacedUnsupportedCodecs)
        if (c != null && c.length() > 0)
          norm.add(PlaybackSurfaceSet.canonicalVideoCodec(c));
      this.interlacedUnsupportedCodecs = Collections.unmodifiableSet(norm);
    }
    if (containerTransports == null || containerTransports.isEmpty())
      this.containerTransports = Collections.<String, boolean[]>emptyMap();
    else
    {
      Map<String, boolean[]> norm = new java.util.LinkedHashMap<String, boolean[]>(containerTransports.size());
      for (Map.Entry<String, boolean[]> e : containerTransports.entrySet())
      {
        if (e.getKey() == null || e.getValue() == null || e.getValue().length < 2) continue;
        norm.put(PlaybackSurfaceSet.canonicalContainer(e.getKey()),
            new boolean[] { e.getValue()[0], e.getValue()[1] });
      }
      this.containerTransports = Collections.unmodifiableMap(norm);
    }
    // Negative/nonsense channel counts collapse to "undeclared".
    this.audioMaxChannels = Math.max(0, audioMaxChannels);
    this.bandwidthFeedback = (bandwidthFeedback == null || bandwidthFeedback.length() == 0)
        ? "none" : bandwidthFeedback;
  }

  public String getId() { return id; }
  public String getRoute() { return route; }
  public int getPriority() { return priority; }
  public List<String> getDeliveryModes() { return deliveryModes; }
  /** {@code "xcode_adjust"} if this surface can feed live goodput back (XCODE_ADJUST), else {@code "none"}. */
  public String getBandwidthFeedback() { return bandwidthFeedback; }
  public List<String> getVideoCodecs() { return videoCodecs; }
  public List<String> getAudioCodecs() { return audioCodecs; }
  public List<String> getContainers() { return containers; }
  public String getAudioTrackAccess() { return audioTrackAccess; }
  public String getAudioTrackSelectionMode() { return audioTrackSelectionMode; }
  public Map<String, List<String>> getAudioContainerRules() { return audioContainerRules; }

  /** Declared decoder width limit, or 0 when the client didn't say. */
  public int getMaxOutputWidth() { return maxOutputWidth; }
  /** Declared decoder height limit, or 0 when the client didn't say. */
  public int getMaxOutputHeight() { return maxOutputHeight; }
  /** Declared decoder frame-rate limit, or 0 when the client didn't say. */
  public int getMaxFps() { return maxFps; }
  /**
   * Declared max audio channels this surface can decode/render, or 0 when the
   * client didn't say. An explicit value &gt;= 6 opts into preserved surround on
   * paths that would otherwise downmix to stereo; 0 (legacy) is resolved from
   * the negotiated audio codec by the consumer, never assumed multichannel.
   */
  public int getAudioMaxChannels() { return audioMaxChannels; }

  /**
   * True when this surface EXPLICITLY declared it cannot decode interlaced
   * content for the given codec (a per-codec {@code interlaced=false} or
   * {@code scan=progressive} attribute on {@code PLAYBACK_SURFACE_<id>_VIDEO_CODECS}).
   *
   * <p>Fail-open: a surface that never declared the attribute returns false, so
   * an interlaced source keeps whatever copy/transcode decision it would have
   * had. This only ever ESCALATES to a server-side deinterlace when the client
   * itself said its decode path is progressive-only -- the honest signal a
   * browser/MSE surface sends because no browser MSE decodes interlaced H.264.
   */
  public boolean declaresInterlacedUnsupported(String codec)
  {
    return codec != null
        && interlacedUnsupportedCodecs.contains(PlaybackSurfaceSet.canonicalVideoCodec(codec));
  }

  /** True when this surface declared any output limit at all. */
  public boolean hasDeclaredOutputLimits()
  {
    return maxOutputWidth > 0 && maxOutputHeight > 0;
  }

  /**
   * The hard OUTPUT gate for server video enhancement. Returns true only when
   * this surface has PROVEN it can decode the proposed output geometry.
   *
   * <p>Deliberately fail-closed: a surface that never declared its limits
   * returns false. Enhancement is an optimization, so "the client didn't tell
   * us" must mean "don't", not "probably fine". The alternative -- assuming a
   * surface that lists HEVC can handle 4K -- is the exact bug this dimension
   * exists to prevent, because listing a codec says nothing about the level
   * and resolution ceiling the decoder was actually built for.
   *
   * @param fps proposed output frame rate; pass 0 to skip the frame-rate check.
   */
  public boolean canOutput(int width, int height, int fps)
  {
    if (width <= 0 || height <= 0) return false;
    if (!hasDeclaredOutputLimits()) return false;
    if (width > maxOutputWidth || height > maxOutputHeight) return false;
    // An undeclared frame-rate limit is tolerated -- geometry is the limit that
    // actually breaks decoders in practice, and requiring both would exclude
    // otherwise-capable clients for no gain.
    if (fps > 0 && maxFps > 0 && fps > maxFps) return false;
    return true;
  }

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
    if (codec == null) return false;
    String canon = PlaybackSurfaceSet.canonicalAudioCodec(codec);
    // AC-4 (Dolby AC-4, ATSC 3.0) is never treated as client-decodable in this
    // deployment. No shipping Android surface here (exoplayer/media3 or the ijk
    // software fallback) can actually render AC-4 -- a DIRECT_PLAY/REMUX audio
    // copy of AC-4 produces silence -- and the bundled ffmpeg cannot mux AC-4
    // into any container the clients accept. AC-4 -> E-AC-3 transcode is therefore
    // mandatory. Some clients nonetheless advertise "AC4" in their audio list;
    // honoring that led the decision engine to pick the ijk surface with
    // DIRECT_PLAY and push raw AC-4 (no audio). Force the audio dimension to
    // "not natively supported" so evaluateForSurface escalates to AUDIO_TRANSCODE.
    if ("AC4".equals(canon)) return false;
    return audioCodecs.contains(canon);
  }

  /** Alias- and case-tolerant check against the surface's container list. */
  public boolean supportsContainer(String container)
  {
    return container != null
        && containers.contains(PlaybackSurfaceSet.canonicalContainer(container));
  }

  /**
   * True when this surface can receive the given container over the requested
   * transport. Fail-open: a container the client declared WITHOUT an explicit
   * transport attribute has no entry and is reachable on either transport
   * (so every pre-2.1 client, and any container sent bare, behaves exactly as
   * before). Only an explicit {@code push=false} / {@code pull=false} on the
   * container restricts it.
   *
   * <p>This is the surface-native equivalent of the legacy per-player
   * {@code IJK_CONTAINER_CONSTRAINTS} push/pull rows. It lets a surface honestly
   * declare a push-only container (e.g. {@code MPEG2-PS;push=true;pull=false}):
   * the client can play a program stream when it is PUSHED (libavformat demux)
   * but not when it is PULLED as a raw {@code stv://} .mpg (a pull OPEN returns
   * NON_MEDIA). {@code container} is canonicalized internally.
   *
   * @param push true to ask about the push transport, false for pull.
   */
  public boolean containerAllowsTransport(String container, boolean push)
  {
    if (container == null) return false;
    boolean[] t = containerTransports.get(PlaybackSurfaceSet.canonicalContainer(container));
    if (t == null) return true; // fail-open: unspecified transport is allowed
    return push ? t[0] : t[1];
  }

  /**
   * True when this surface declared the container as PUSH-ONLY -- it can be
   * pushed but not pulled (e.g. MPEG2-PS). Used by the delivery-mode selector so
   * a push-only container is never routed over the cheaper pull transport it
   * cannot actually direct-play. False when the container is unrestricted (no
   * entry, fail-open) or explicitly pull-capable.
   */
  public boolean isContainerPushOnly(String container)
  {
    if (container == null) return false;
    boolean[] t = containerTransports.get(PlaybackSurfaceSet.canonicalContainer(container));
    return t != null && t[0] && !t[1];
  }

  /**
   * True when this surface declared the container as PULL-ONLY -- it can be
   * pulled but not pushed. Symmetric with {@link #isContainerPushOnly}; false
   * for an unrestricted (fail-open) or push-capable container.
   */
  public boolean isContainerPullOnly(String container)
  {
    if (container == null) return false;
    boolean[] t = containerTransports.get(PlaybackSurfaceSet.canonicalContainer(container));
    return t != null && t[1] && !t[0];
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
    StringBuilder ct = new StringBuilder();
    for (Map.Entry<String, boolean[]> e : containerTransports.entrySet())
    {
      if (ct.length() > 0) ct.append(",");
      ct.append(e.getKey()).append(";push=").append(e.getValue()[0])
        .append(";pull=").append(e.getValue()[1]);
    }
    return "PlaybackSurface[id=" + id
        + " route=" + route
        + " priority=" + priority
        + " delivery=" + deliveryModes
        + " video=" + videoCodecs
        + " audio=" + audioCodecs
        + " containers=" + containers
        + " containerTransports=[" + ct + "]"
        + " audioTrackAccess=" + audioTrackAccess
        + " audioTrackSelectionMode=" + audioTrackSelectionMode
        + " audioContainerRules=" + audioContainerRules
        + " maxOutput=" + maxOutputWidth + "x" + maxOutputHeight
        + " maxFps=" + maxFps
        + " bwFeedback=" + bandwidthFeedback
        + " interlacedUnsupported=" + interlacedUnsupportedCodecs + "]";
  }
}
