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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-client capability constraint set (schema v2).
 *
 * Parses three strings the miniclient publishes via GetProperty:
 *   EXO_VIDEO_CONSTRAINTS / IJK_VIDEO_CONSTRAINTS
 *   EXO_AUDIO_CONSTRAINTS / IJK_AUDIO_CONSTRAINTS
 *   EXO_CONTAINER_CONSTRAINTS / IJK_CONTAINER_CONSTRAINTS
 *
 * Wire format (per row, comma-separated rows):
 *   {@code TOKEN;key=value;key=value}
 * where TOKEN is the codec or container name (e.g. {@code MPEG2-VIDEO},
 * {@code H.264}, {@code AC3}, {@code MATROSKA}).
 *
 * Attribute dictionary (only known keys are stored; unknown keys are ignored,
 * missing keys are {@link Tri#UNKNOWN}):
 *   video:     scan = progressive | interlaced+progressive | any | unknown
 *              interlaced = true | false | unknown
 *              decoder = hw | sw_or_hw | unknown
 *   audio:     decode = true | false | unknown
 *              passthrough = true | false | unknown
 *   container: push = true | false | unknown
 *              pull = true | false | unknown
 *
 * Only enabled when the client sends {@code CAP_SCHEMA_VERSION >= 2}; otherwise
 * {@link PlaybackDecisionEngine} ignores constraints entirely and the legacy
 * codec/container set policy applies unchanged.
 */
public final class ClientConstraints
{
  /** Tri-state boolean used for every advertised attribute. */
  public enum Tri
  {
    TRUE, FALSE, UNKNOWN;

    public static Tri parse(String v)
    {
      if (v == null) return UNKNOWN;
      String s = v.trim().toLowerCase();
      if (s.length() == 0 || "unknown".equals(s)) return UNKNOWN;
      if ("true".equals(s) || "yes".equals(s) || "1".equals(s)) return TRUE;
      if ("false".equals(s) || "no".equals(s) || "0".equals(s)) return FALSE;
      return UNKNOWN;
    }
  }

  /** Scan-type attribute. */
  public enum Scan
  {
    PROGRESSIVE,
    INTERLACED_AND_PROGRESSIVE,
    ANY,
    UNKNOWN;

    public static Scan parse(String v)
    {
      if (v == null) return UNKNOWN;
      String s = v.trim().toLowerCase();
      if (s.length() == 0 || "unknown".equals(s)) return UNKNOWN;
      if ("progressive".equals(s)) return PROGRESSIVE;
      if ("interlaced+progressive".equals(s) || "progressive+interlaced".equals(s)
          || "interlaced_and_progressive".equals(s)) return INTERLACED_AND_PROGRESSIVE;
      if ("any".equals(s) || "interlaced".equals(s)) return ANY;
      return UNKNOWN;
    }
  }

  /** Decoder-implementation attribute (informational / tie-break only). */
  public enum Decoder
  {
    HW, SW_OR_HW, UNKNOWN;

    public static Decoder parse(String v)
    {
      if (v == null) return UNKNOWN;
      String s = v.trim().toLowerCase();
      if ("hw".equals(s)) return HW;
      if ("sw_or_hw".equals(s) || "sw".equals(s)) return SW_OR_HW;
      return UNKNOWN;
    }
  }

  public static final class VideoConstraint
  {
    public final String codec;
    public final Scan scan;
    public final Tri interlaced;
    public final Decoder decoder;
    VideoConstraint(String codec, Scan scan, Tri interlaced, Decoder decoder)
    {
      this.codec = codec; this.scan = scan;
      this.interlaced = interlaced; this.decoder = decoder;
    }
    @Override public String toString()
    {
      return codec + "[scan=" + scan + ",interlaced=" + interlaced + ",decoder=" + decoder + "]";
    }
  }

  public static final class AudioConstraint
  {
    public final String codec;
    public final Tri decode;
    public final Tri passthrough;
    AudioConstraint(String codec, Tri decode, Tri passthrough)
    {
      this.codec = codec; this.decode = decode; this.passthrough = passthrough;
    }
    @Override public String toString()
    {
      return codec + "[decode=" + decode + ",passthrough=" + passthrough + "]";
    }
  }

  public static final class ContainerConstraint
  {
    public final String container;
    public final Tri push;
    public final Tri pull;
    ContainerConstraint(String container, Tri push, Tri pull)
    {
      this.container = container; this.push = push; this.pull = pull;
    }
    @Override public String toString()
    {
      return container + "[push=" + push + ",pull=" + pull + "]";
    }
  }

  /** Empty constraints instance — every lookup returns null (== UNKNOWN). */
  public static final ClientConstraints EMPTY = new ClientConstraints(
      Collections.<String, VideoConstraint>emptyMap(),
      Collections.<String, AudioConstraint>emptyMap(),
      Collections.<String, ContainerConstraint>emptyMap(),
      "");

  private final Map<String, VideoConstraint> video;
  private final Map<String, AudioConstraint> audio;
  private final Map<String, ContainerConstraint> container;
  private final String player;

  private ClientConstraints(Map<String, VideoConstraint> v,
                            Map<String, AudioConstraint> a,
                            Map<String, ContainerConstraint> c,
                            String player)
  {
    this.video = v;
    this.audio = a;
    this.container = c;
    this.player = player == null ? "" : player;
  }

  /** Active player tag ("exoplayer" / "ijkplayer" / "") — for log breadcrumbs. */
  public String getPlayer() { return player; }

  /** Lookup video constraint for a codec name. Case- and dialect-insensitive
   *  ("H.264" == "H264", "MPEG2-Video" == "MPEG2-VIDEO"). */
  public VideoConstraint getVideo(String codec)
  {
    return video.get(normalize(codec));
  }

  public AudioConstraint getAudio(String codec)
  {
    return audio.get(normalize(codec));
  }

  public ContainerConstraint getContainer(String container)
  {
    return this.container.get(normalize(container));
  }

  public boolean isEmpty()
  {
    return video.isEmpty() && audio.isEmpty() && container.isEmpty();
  }

  /** True when the client supplied at least one video constraint row. When
   *  true, the engine treats a missing row for a given codec as "not
   *  supported by this player" (the player declared a complete set). When
   *  false, the engine falls back to the legacy profile codec list. */
  public boolean hasAnyVideo() { return !video.isEmpty(); }
  public boolean hasAnyAudio() { return !audio.isEmpty(); }
  public boolean hasAnyContainer() { return !container.isEmpty(); }

  @Override public String toString()
  {
    return "ClientConstraints{player=" + player
        + ",video=" + video.values()
        + ",audio=" + audio.values()
        + ",container=" + container.values() + "}";
  }

  // -------------------------------------------------------------------------
  // Parsing
  // -------------------------------------------------------------------------

  /**
   * Parse three constraint strings for the active player. Any of the three may
   * be null/empty and the resulting category will simply contain no rows
   * (treated as UNKNOWN at lookup time).
   *
   * @param player "exoplayer" / "ijkplayer" / "" — informational, recorded for logs.
   */
  public static ClientConstraints parse(String player, String videoStr, String audioStr, String containerStr)
  {
    Map<String, VideoConstraint> v = parseVideoRows(videoStr);
    Map<String, AudioConstraint> a = parseAudioRows(audioStr);
    Map<String, ContainerConstraint> c = parseContainerRows(containerStr);
    if (v.isEmpty() && a.isEmpty() && c.isEmpty())
      return new ClientConstraints(
          Collections.<String, VideoConstraint>emptyMap(),
          Collections.<String, AudioConstraint>emptyMap(),
          Collections.<String, ContainerConstraint>emptyMap(),
          player);
    return new ClientConstraints(v, a, c, player);
  }

  private static Map<String, VideoConstraint> parseVideoRows(String src)
  {
    Map<String, VideoConstraint> out = new LinkedHashMap<String, VideoConstraint>();
    for (Map<String, String> row : splitRows(src))
    {
      String codec = row.remove("__token");
      if (codec == null) continue;
      out.put(normalize(codec), new VideoConstraint(
          codec,
          Scan.parse(row.get("scan")),
          Tri.parse(row.get("interlaced")),
          Decoder.parse(row.get("decoder"))));
    }
    return out;
  }

  private static Map<String, AudioConstraint> parseAudioRows(String src)
  {
    Map<String, AudioConstraint> out = new LinkedHashMap<String, AudioConstraint>();
    for (Map<String, String> row : splitRows(src))
    {
      String codec = row.remove("__token");
      if (codec == null) continue;
      out.put(normalize(codec), new AudioConstraint(
          codec,
          Tri.parse(row.get("decode")),
          Tri.parse(row.get("passthrough"))));
    }
    return out;
  }

  private static Map<String, ContainerConstraint> parseContainerRows(String src)
  {
    Map<String, ContainerConstraint> out = new LinkedHashMap<String, ContainerConstraint>();
    for (Map<String, String> row : splitRows(src))
    {
      String container = row.remove("__token");
      if (container == null) continue;
      out.put(normalize(container), new ContainerConstraint(
          container,
          Tri.parse(row.get("push")),
          Tri.parse(row.get("pull"))));
    }
    return out;
  }

  /** Split "ROW1,ROW2,ROW3" -> list of {key=value,...} maps with "__token" = first field. */
  private static java.util.List<Map<String, String>> splitRows(String src)
  {
    java.util.List<Map<String, String>> rows = new java.util.ArrayList<Map<String, String>>();
    if (src == null) return rows;
    String s = src.trim();
    if (s.length() == 0) return rows;
    String[] rowParts = s.split(",");
    for (String row : rowParts)
    {
      String r = row.trim();
      if (r.length() == 0) continue;
      String[] fields = r.split(";");
      if (fields.length == 0 || fields[0].trim().length() == 0) continue;
      Map<String, String> map = new LinkedHashMap<String, String>();
      map.put("__token", fields[0].trim());
      for (int i = 1; i < fields.length; i++)
      {
        String field = fields[i].trim();
        if (field.length() == 0) continue;
        int eq = field.indexOf('=');
        if (eq < 1) continue; // ignore tokens with no key or with leading '='
        String key = field.substring(0, eq).trim().toLowerCase();
        String val = field.substring(eq + 1).trim();
        if (key.length() > 0) map.put(key, val);
      }
      rows.add(map);
    }
    return rows;
  }

  /** Codec / container name normalizer. Strips dots and dashes, uppercases,
   *  and folds H265 ↔ HEVC for lookup convenience. */
  static String normalize(String name)
  {
    if (name == null) return "";
    String s = name.trim().toUpperCase().replace(".", "").replace("-", "").replace("_", "");
    // H265 -> HEVC fold (matches PlaybackDecisionEngine's existing alias behavior)
    if ("H265".equals(s)) return "HEVC";
    return s;
  }
}
