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
          "pull", "push", "hls", "dash", "webrtc")));

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
      if (canonical.contains(t)) ok.add(t);
      else System.err.println("PlaybackSurfaceSet WARN: surface '" + surfaceId
          + "' " + propKind + " has non-canonical token '" + t
          + "' (accepted: " + canonical + "); ignored");
    }
    return ok;
  }

  /**
   * Build a set from the raw {@code PLAYBACK_SURFACES} list plus a lookup
   * function that returns the six per-surface property strings for a given
   * id in this exact order:
   * {@code [ROUTE, PRIORITY, DELIVERY_MODES, VIDEO_CODECS, AUDIO_CODECS, CONTAINERS]}.
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
      out.put(id, new PlaybackSurface(id, route, priority,
          deliveryModes, videoCodecs, audioCodecs, containers));
    }
    return out.isEmpty() ? empty() : new PlaybackSurfaceSet(out);
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
