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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ordered collection of {@link PlaybackSurface} entries advertised by a
 * client via {@code PLAYBACK_SURFACES} + {@code PLAYBACK_SURFACE_<id>_*}
 * properties. Insertion order preserves the client's declared surface list;
 * ranking across surfaces is done at decision time in
 * {@link PlaybackDecisionEngine} (Phase 2+).
 *
 * <p>Phase 1 = discovery + logging only. This class parses raw property
 * strings, validates tokens against the canonical SageTV name sets
 * (protocol v2.1), warns on non-canonical tokens, and returns an immutable
 * set. No decision-engine consumer yet.
 *
 * <p>See ROADMAP.md "Playback Surface capability model (Protocol 2.1)"
 * for the full contract and phasing plan.
 */
public final class PlaybackSurfaceSet
{
  /** Canonical SageTV video codec names accepted in {@code PLAYBACK_SURFACE_<id>_VIDEO_CODECS}. */
  public static final Set<String> CANONICAL_VIDEO_CODECS = Collections.unmodifiableSet(
      new HashSet<String>(Arrays.asList(
          "MPEG1-VIDEO", "MPEG2-VIDEO", "MPEG4-VIDEO",
          "H264", "HEVC", "VP9", "AV1")));

  /** Canonical SageTV audio codec names accepted in {@code PLAYBACK_SURFACE_<id>_AUDIO_CODECS}. */
  public static final Set<String> CANONICAL_AUDIO_CODECS = Collections.unmodifiableSet(
      new HashSet<String>(Arrays.asList(
          "MP2", "MP3", "AAC", "HE-AAC", "AC3", "EAC3", "AC4",
          "DTS", "TRUEHD", "OPUS", "FLAC", "PCM")));

  /** Canonical SageTV container names accepted in {@code PLAYBACK_SURFACE_<id>_CONTAINERS}. */
  public static final Set<String> CANONICAL_CONTAINERS = Collections.unmodifiableSet(
      new HashSet<String>(Arrays.asList(
          "MPEG2-PS", "MPEG2-TS", "MP4", "MATROSKA", "AVI", "MOV", "FLV", "WEBM")));

  /** Canonical delivery modes accepted in {@code PLAYBACK_SURFACE_<id>_DELIVERY_MODES}. NOT containers. */
  public static final Set<String> CANONICAL_DELIVERY_MODES = Collections.unmodifiableSet(
      new HashSet<String>(Arrays.asList(
          // pull-xcode (Protocol 2.1): pull transport, but of a SERVER-TRANSCODED
          // stream (via an XCODE_SETUP mode) rather than the raw file. The client
          // bridge maps CAP_EFFECTIVE_DELIVERY=pull-xcode:<mode> 1:1 to its
          // /msproxy?mode=<mode> endpoint, so it never sniffs the stream on NG.
          "pull", "pull-xcode", "push", "hls", "dash", "webrtc")));

  /** Canonical values for {@code PLAYBACK_SURFACE_<id>_AUDIO_TRACK_ACCESS} (2.1.0006). */
  public static final Set<String> CANONICAL_AUDIO_TRACK_ACCESS = Collections.unmodifiableSet(
      new HashSet<String>(Arrays.asList(
          "all", "primary_only", "default_only", "none")));

  /** Canonical values for {@code PLAYBACK_SURFACE_<id>_AUDIO_TRACK_SELECTION_MODE} (2.1.0006). */
  public static final Set<String> CANONICAL_AUDIO_TRACK_SELECTION_MODE = Collections.unmodifiableSet(
      new HashSet<String>(Arrays.asList(
          "client", "server")));

  /** Canonical rule tokens allowed inside {@code PLAYBACK_SURFACE_<id>_AUDIO_CONTAINER_RULES} (2.1.0006). */
  public static final Set<String> CANONICAL_AUDIO_CONTAINER_RULE_TOKENS = Collections.unmodifiableSet(
      new HashSet<String>(Arrays.asList(
          "all_tracks", "first_substream_only", "order_sensitive", "default_track_only")));

  private static final PlaybackSurfaceSet EMPTY =
      new PlaybackSurfaceSet(Collections.<String, PlaybackSurface>emptyMap());

  private final Map<String, PlaybackSurface> surfacesById;

  private PlaybackSurfaceSet(Map<String, PlaybackSurface> surfaces)
  {
    this.surfacesById = Collections.unmodifiableMap(surfaces);
  }

  public static PlaybackSurfaceSet empty() { return EMPTY; }

  public boolean isEmpty() { return surfacesById.isEmpty(); }
  public int size() { return surfacesById.size(); }
  public Map<String, PlaybackSurface> asMap() { return surfacesById; }
  public PlaybackSurface get(String id) { return surfacesById.get(id); }

  /**
   * Split a client's comma-separated capability list, trimming whitespace and
   * dropping empty tokens. No canonicalisation performed here -- callers use
   * {@link #validateAndFilter} to check tokens against the canonical sets.
   */
  public static List<String> split(String raw)
  {
    if (raw == null || raw.length() == 0) return Collections.<String>emptyList();
    String[] parts = raw.split(",");
    List<String> out = new ArrayList<String>(parts.length);
    for (String p : parts)
    {
      String t = p.trim();
      if (t.length() > 0) out.add(t);
    }
    return out;
  }

  // ---- canonical-name aliasing (Protocol v2.1) --------------------------
  //
  // The server holds media metadata using SageTV's INTERNAL codec / container
  // spelling (e.g. "H.264", "MPEG2-Video", "Quicktime"), while the Protocol
  // v2.1 canonical names on the wire are strict (e.g. "H264", "MPEG2-VIDEO",
  // "MP4"). Rather than force every caller to canonicalize, PlaybackSurface's
  // support* checks canonicalize the query first via these helpers. Clients
  // that mis-spell a token also get normalized (H.264 -> H264, ac-3 -> AC3),
  // so a slightly non-conforming client still gets a correct decision.
  //
  // Rules:
  //   - Case-insensitive: everything is uppercased first.
  //   - Only the aliases that SageTV's own FormatParser emits, or that clients
  //     have been observed to send in the wild, are hardcoded here. Everything
  //     else passes through unchanged (uppercased).
  //   - If a token uppercases directly to its canonical name (e.g.
  //     "mpeg2-video" -> "MPEG2-VIDEO"), no explicit alias entry is needed.

  /** Canonicalize a video codec name to its Protocol v2.1 spelling. */
  public static String canonicalVideoCodec(String raw)
  {
    if (raw == null || raw.length() == 0) return raw;
    String u = raw.trim().toUpperCase(java.util.Locale.ROOT);
    if ("H.264".equals(u)) return "H264";            // SageTV MediaFormat.H264 = "H.264"
    if ("H.265".equals(u) || "H265".equals(u)) return "HEVC";
    return u;
  }

  /** Canonicalize an audio codec name to its Protocol v2.1 spelling. */
  public static String canonicalAudioCodec(String raw)
  {
    if (raw == null || raw.length() == 0) return raw;
    String u = raw.trim().toUpperCase(java.util.Locale.ROOT);
    if ("AC-3".equals(u)) return "AC3";
    if ("E-AC-3".equals(u) || "EC-3".equals(u)) return "EAC3";
    if ("AC-4".equals(u)) return "AC4";
    if ("MPG1L2".equals(u)) return "MP2";            // SageTV FormatParser MPEG-1 Layer II
    if ("MPG1L3".equals(u)) return "MP3";            // SageTV FormatParser MPEG-1 Layer III
    return u;
  }

  /** Canonicalize a container name to its Protocol v2.1 spelling. */
  public static String canonicalContainer(String raw)
  {
    if (raw == null || raw.length() == 0) return raw;
    String u = raw.trim().toUpperCase(java.util.Locale.ROOT);
    // SageTV parses .mp4 files with container="Quicktime" (same ISOBMFF format).
    if ("QUICKTIME".equals(u)) return "MP4";
    // Legacy short forms sometimes seen.
    if ("MPG".equals(u) || "MPEG".equals(u)) return "MPEG2-PS";
    if ("TS".equals(u)) return "MPEG2-TS";
    if ("MKV".equals(u)) return "MATROSKA";
    return u;
  }

  /** Canonicalize a delivery mode. Kept for symmetry; delivery modes are lowercase in v2.1. */
  public static String canonicalDeliveryMode(String raw)
  {
    if (raw == null || raw.length() == 0) return raw;
    return raw.trim().toLowerCase(java.util.Locale.ROOT);
  }

  /**
   * Canonicalize + validate the {@code AUDIO_TRACK_ACCESS} value (2.1.0006).
   * Returns {@code "default_only"} (the conservative default) for empty or
   * non-canonical input, logging a WARN for the latter. NEVER defaults to
   * {@code "all"} -- the server must not assume all tracks are reachable.
   */
  public static String canonicalTrackAccess(String surfaceId, String raw)
  {
    if (raw == null || raw.trim().length() == 0) return "default_only";
    String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
    if (CANONICAL_AUDIO_TRACK_ACCESS.contains(v)) return v;
    System.err.println("PlaybackSurfaceSet WARN: surface '" + surfaceId
        + "' AUDIO_TRACK_ACCESS '" + raw + "' not canonical (accepted: "
        + CANONICAL_AUDIO_TRACK_ACCESS + "); using conservative default_only");
    return "default_only";
  }

  /**
   * Canonicalize + validate the {@code AUDIO_TRACK_SELECTION_MODE} value
   * (2.1.0006). Returns {@code "client"} for empty/non-canonical input.
   */
  public static String canonicalSelectionMode(String surfaceId, String raw)
  {
    if (raw == null || raw.trim().length() == 0) return "client";
    String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
    if (CANONICAL_AUDIO_TRACK_SELECTION_MODE.contains(v)) return v;
    System.err.println("PlaybackSurfaceSet WARN: surface '" + surfaceId
        + "' AUDIO_TRACK_SELECTION_MODE '" + raw + "' not canonical (accepted: "
        + CANONICAL_AUDIO_TRACK_SELECTION_MODE + "); using default client");
    return "client";
  }

  /**
   * Parse the {@code AUDIO_CONTAINER_RULES} value (2.1.0006) into a map keyed
   * by canonical container name. Wire format is a semicolon-separated list of
   * {@code CONTAINER:rule1,rule2} entries, e.g.
   * {@code MPEG2-PS:first_substream_only,order_sensitive;MPEG2-TS:all_tracks}.
   * Non-canonical containers or rule tokens are dropped with a WARN.
   */
  public static Map<String, List<String>> parseContainerRules(String surfaceId, String raw)
  {
    if (raw == null || raw.trim().length() == 0)
      return Collections.<String, List<String>>emptyMap();
    Map<String, List<String>> out = new LinkedHashMap<String, List<String>>();
    for (String entry : raw.split(";"))
    {
      String e = entry.trim();
      if (e.length() == 0) continue;
      int colon = e.indexOf(':');
      if (colon <= 0 || colon >= e.length() - 1)
      {
        System.err.println("PlaybackSurfaceSet WARN: surface '" + surfaceId
            + "' AUDIO_CONTAINER_RULES entry '" + e + "' missing CONTAINER:rules form; ignored");
        continue;
      }
      String rawContainer = e.substring(0, colon).trim();
      String container = canonicalContainer(rawContainer);
      if (!CANONICAL_CONTAINERS.contains(container))
      {
        System.err.println("PlaybackSurfaceSet WARN: surface '" + surfaceId
            + "' AUDIO_CONTAINER_RULES container '" + rawContainer + "' not canonical; ignored");
        continue;
      }
      List<String> rules = new ArrayList<String>();
      for (String tok : e.substring(colon + 1).split(","))
      {
        String t = tok.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.length() == 0) continue;
        if (CANONICAL_AUDIO_CONTAINER_RULE_TOKENS.contains(t)) rules.add(t);
        else System.err.println("PlaybackSurfaceSet WARN: surface '" + surfaceId
            + "' AUDIO_CONTAINER_RULES rule '" + t + "' for container " + container
            + " not canonical (accepted: " + CANONICAL_AUDIO_CONTAINER_RULE_TOKENS + "); ignored");
      }
      if (!rules.isEmpty())
        out.put(container, Collections.unmodifiableList(rules));
    }
    return out.isEmpty() ? Collections.<String, List<String>>emptyMap() : out;
  }

  /**
   * Case-sensitive check against a canonical set. Returns the input list
   * filtered to canonical tokens only; each non-canonical token is logged
   * as a WARN with the surface id, property kind, and offending token so
   * client teams get actionable feedback while iterating on Protocol v2.1
   * compliance. Case sensitivity is intentional -- canonical names have
   * one spelling ({@code AC3} not {@code AC-3}; {@code EAC3} not {@code EC-3}).
   */
  static List<String> validateAndFilter(String surfaceId, String propKind,
      List<String> tokens, Set<String> canonical)
  {
    if (tokens.isEmpty()) return tokens;
    List<String> ok = new ArrayList<String>(tokens.size());
    for (String t : tokens)
    {
      // Normalize to canonical spelling first so a client that sends "H.264"
      // or "ac-3" or "Quicktime" still ends up stored as the canonical token.
      String normalized;
      if (canonical == CANONICAL_VIDEO_CODECS)         normalized = canonicalVideoCodec(t);
      else if (canonical == CANONICAL_AUDIO_CODECS)    normalized = canonicalAudioCodec(t);
      else if (canonical == CANONICAL_CONTAINERS)      normalized = canonicalContainer(t);
      else if (canonical == CANONICAL_DELIVERY_MODES)  normalized = canonicalDeliveryMode(t);
      else                                             normalized = t;
      if (canonical.contains(normalized)) ok.add(normalized);
      else System.err.println("PlaybackSurfaceSet WARN: surface '" + surfaceId
          + "' " + propKind + " has non-canonical token '" + t
          + "' (normalized='" + normalized + "', accepted: " + canonical + "); ignored");
    }
    return ok;
  }

  /**
   * Build a set from the raw {@code PLAYBACK_SURFACES} list plus a lookup
   * function that returns the per-surface property strings for a given id.
   * The array is read positionally:
   * {@code [ROUTE, PRIORITY, DELIVERY_MODES, VIDEO_CODECS, AUDIO_CODECS,
   * CONTAINERS, AUDIO_TRACK_ACCESS, AUDIO_TRACK_SELECTION_MODE,
   * AUDIO_CONTAINER_RULES, MAX_OUTPUT_WIDTH, MAX_OUTPUT_HEIGHT, MAX_FPS]}.
   *
   * <p>Indices 0-5 are required (pre-2.1.0006). Indices 6-8 (the track-access
   * dimension) are OPTIONAL -- when the array is shorter than 9, or those
   * entries are empty, the conservative defaults apply
   * ({@code audioTrackAccess="default_only"}, NEVER {@code "all"}). This keeps
   * the additive contract: a client that advertises surfaces but hasn't added
   * the track-access fields still parses, it just gets safe defaults.
   *
   * <p>Indices 9-11 (the output-limit dimension used by server video
   * enhancement) are likewise OPTIONAL; absent or unparseable entries become 0,
   * meaning "undeclared", which disables enhancement for that surface rather
   * than assuming it can decode whatever the server would like to send.
   *
   * <p>Callers own the transport (the miniclient uses
   * {@code sendGetPropertyAsync} + {@code recvr.getStringReply()}). Any
   * surface whose video, audio, AND container sets are all empty after
   * canonical filtering is dropped with a WARN -- it can never win the
   * ranking anyway.
   */
  public static PlaybackSurfaceSet build(String surfacesRaw,
      java.util.function.Function<String, String[]> propReader)
  {
    List<String> ids = split(surfacesRaw);
    if (ids.isEmpty()) return empty();
    Map<String, PlaybackSurface> out = new LinkedHashMap<String, PlaybackSurface>();
    for (String id : ids)
    {
      String[] props = propReader.apply(id);
      if (props == null || props.length < 6)
      {
        System.err.println("PlaybackSurfaceSet WARN: surface '" + id
            + "' missing per-surface property replies; dropped");
        continue;
      }
      String route = props[0] == null ? "" : props[0].trim();
      int priority = 0;
      String rawPriority = props[1] == null ? "" : props[1].trim();
      if (rawPriority.length() > 0)
      {
        try { priority = Integer.parseInt(rawPriority); }
        catch (NumberFormatException e)
        {
          System.err.println("PlaybackSurfaceSet WARN: surface '" + id
              + "' PRIORITY '" + rawPriority + "' not an integer; using 0");
        }
      }
      List<String> deliveryModes = validateAndFilter(id, "DELIVERY_MODES",
          split(props[2]), CANONICAL_DELIVERY_MODES);
      List<String> videoCodecs = validateAndFilter(id, "VIDEO_CODECS",
          split(props[3]), CANONICAL_VIDEO_CODECS);
      List<String> audioCodecs = validateAndFilter(id, "AUDIO_CODECS",
          split(props[4]), CANONICAL_AUDIO_CODECS);
      List<String> containers = validateAndFilter(id, "CONTAINERS",
          split(props[5]), CANONICAL_CONTAINERS);
      if (videoCodecs.isEmpty() && audioCodecs.isEmpty() && containers.isEmpty())
      {
        System.err.println("PlaybackSurfaceSet WARN: surface '" + id
            + "' has empty video+audio+container sets after canonical filtering; dropped");
        continue;
      }
      // --- 2.1.0006 track-access dimension (optional; conservative defaults) ---
      String rawAccess = (props.length > 6 && props[6] != null) ? props[6].trim() : "";
      String rawSelMode = (props.length > 7 && props[7] != null) ? props[7].trim() : "";
      String rawRules = (props.length > 8 && props[8] != null) ? props[8].trim() : "";
      String audioTrackAccess = canonicalTrackAccess(id, rawAccess);
      String audioTrackSelectionMode = canonicalSelectionMode(id, rawSelMode);
      Map<String, List<String>> audioContainerRules = parseContainerRules(id, rawRules);
      // --- output-limit dimension (optional; unknown => 0 => enhancement off) ---
      int maxOutW = parseOptionalDimension(id, "MAX_OUTPUT_WIDTH",  props, 9);
      int maxOutH = parseOptionalDimension(id, "MAX_OUTPUT_HEIGHT", props, 10);
      int maxFps  = parseOptionalDimension(id, "MAX_FPS",           props, 11);
      out.put(id, new PlaybackSurface(id, route, priority,
          deliveryModes, videoCodecs, audioCodecs, containers,
          audioTrackAccess, audioTrackSelectionMode, audioContainerRules,
          maxOutW, maxOutH, maxFps));
    }
    return out.isEmpty() ? empty() : new PlaybackSurfaceSet(out);
  }

  /**
   * Parse an optional non-negative integer capability at {@code idx}. Anything
   * missing, empty, unparseable, or negative yields 0, which every consumer
   * reads as "the client didn't declare this" and therefore declines to
   * enhance. Garbage must never be allowed to look like a capability.
   */
  private static int parseOptionalDimension(String id, String name, String[] props, int idx)
  {
    if (props == null || props.length <= idx || props[idx] == null) return 0;
    String raw = props[idx].trim();
    if (raw.length() == 0) return 0;
    try
    {
      int v = Integer.parseInt(raw);
      if (v < 0)
      {
        System.err.println("PlaybackSurfaceSet WARN: surface '" + id + "' " + name
            + " '" + raw + "' is negative; treating as undeclared");
        return 0;
      }
      return v;
    }
    catch (NumberFormatException e)
    {
      System.err.println("PlaybackSurfaceSet WARN: surface '" + id + "' " + name
          + " '" + raw + "' not an integer; treating as undeclared");
      return 0;
    }
  }

  @Override
  public String toString()
  {
    if (surfacesById.isEmpty()) return "PlaybackSurfaceSet[empty]";
    StringBuilder sb = new StringBuilder("PlaybackSurfaceSet[");
    boolean first = true;
    for (PlaybackSurface s : surfacesById.values())
    {
      if (!first) sb.append(", ");
      sb.append(s);
      first = false;
    }
    sb.append(']');
    return sb.toString();
  }
}
