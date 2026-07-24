/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */
package sage.captions;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes a {@link CaptionEvent} list out as a standard SubRip (.srt) file:
 * sequential 1-based index, {@code HH:MM:SS,mmm --> HH:MM:SS,mmm} timecode
 * line, cue text, blank separator line.
 *
 * <p>Writes to a {@code .tmp} file next to the destination and atomically
 * renames it into place, matching the write convention already used
 * elsewhere in {@code sage.captions} (see {@code CaptionExtractionJob}).
 */
public class SrtCaptionWriter implements CaptionWriter
{
  @Override
  public void write(List<CaptionEvent> events, File output) throws IOException
  {
    File tmp = new File(output.getAbsolutePath() + ".tmp");
    StringBuilder sb = new StringBuilder();
    int index = 1;
    if (events != null)
    {
      for (CaptionEvent e : events)
      {
        sb.append(e.toSrtBlock(index++));
      }
    }
    Files.write(tmp.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
    Files.move(tmp.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  /**
   * Groups {@code events} by {@link CaptionEvent#getService()} and writes
   * one sidecar per group, following the same file-naming convention the
   * legacy 608/708 ccextractor path already uses (see
   * {@code FormatParser.updateExternalSubs}): the primary/default track is
   * written to {@code primarySidecar} itself (e.g. {@code <base>.srt}), and
   * every other track is written alongside it with the language code
   * inserted before the extension (e.g. {@code <base>.spa.srt}).
   *
   * <p>A track is treated as "primary" when its service is {@code null},
   * a CEA-608/708 primary tag ({@link CaptionEvent#SERVICE_CC1}/
   * {@link CaptionEvent#SERVICE_708_SVC1}), or a primary-language tag
   * ({@code "ENG"}/{@code "EN"}/{@code "UND"} — case-insensitive). Every
   * other service value is lower-cased and used verbatim as the sidecar's
   * language suffix, except the legacy {@link CaptionEvent#SERVICE_CC2}
   * tag, which maps to {@code "spa"} to match the existing convention that
   * CC2 carries the Spanish SAP track on US broadcasts.
   *
   * <p>Within each group, events are sorted by begin time before writing.
   *
   * @param events         caption events, potentially spanning multiple
   *                       services/languages
   * @param primarySidecar destination for the primary/default track; other
   *                       tracks are derived from this file's name
   */
  public void writeGrouped(List<CaptionEvent> events, File primarySidecar) throws IOException
  {
    Map<String, List<CaptionEvent>> groups = new LinkedHashMap<>();
    if (events != null)
    {
      for (CaptionEvent e : events)
      {
        String suffix = suffixFor(e.getService());
        groups.computeIfAbsent(suffix == null ? "" : suffix, k -> new ArrayList<>()).add(e);
      }
    }
    if (groups.isEmpty())
    {
      // No events at all; still write an empty primary sidecar so any
      // stale prior sidecar doesn't linger with outdated content.
      write(new ArrayList<>(), primarySidecar);
      return;
    }
    for (Map.Entry<String, List<CaptionEvent>> entry : groups.entrySet())
    {
      String suffix = entry.getKey().isEmpty() ? null : entry.getKey();
      List<CaptionEvent> group = new ArrayList<>(entry.getValue());
      Collections.sort(group);
      write(group, sidecarFileFor(suffix, primarySidecar));
    }
  }

  /**
   * Maps a {@link CaptionEvent#getService()} value to a sidecar filename
   * suffix, or {@code null} if it belongs in the primary sidecar. See
   * {@link #writeGrouped} for the convention.
   */
  private static String suffixFor(String service)
  {
    if (service == null || service.trim().isEmpty()) return null;
    String s = service.trim();
    String upper = s.toUpperCase(Locale.ROOT);
    if (upper.equals(CaptionEvent.SERVICE_CC1) || upper.startsWith("708")) return null;
    if (upper.equals(CaptionEvent.SERVICE_CC2)) return "spa";
    if (upper.equals("ENG") || upper.equals("EN") || upper.equals("UND")) return null;
    return s.toLowerCase(Locale.ROOT);
  }

  private static File sidecarFileFor(String suffix, File primarySidecar)
  {
    if (suffix == null) return primarySidecar;
    String path = primarySidecar.getAbsolutePath();
    int dot = path.lastIndexOf('.');
    String base = (dot > 0) ? path.substring(0, dot) : path;
    String ext = (dot > 0) ? path.substring(dot) : ".srt";
    return new File(base + "." + suffix + ext);
  }
}
