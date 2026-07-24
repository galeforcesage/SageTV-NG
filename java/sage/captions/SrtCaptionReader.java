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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a standard SubRip (.srt) file back into a {@link CaptionEvent} list.
 *
 * <p>This exists purely for convergence: sources that only know how to emit
 * SRT natively (the ccextractor/ffmpeg CEA-608/CEA-708 passes in
 * {@code CaptionExtractionJob}) can still funnel through {@link CaptionEvent}
 * as SageTV-NG's single canonical caption model. The extractor writes SRT
 * exactly as it always has; this class parses that SRT straight back into
 * {@code CaptionEvent}s (tagging every cue with the caller-supplied
 * {@code language}/{@code service}, since plain SRT text carries neither),
 * and the caller re-serializes via {@link SrtCaptionWriter}. The round-trip
 * is a deliberate no-op on cue content.
 */
public final class SrtCaptionReader
{
  private SrtCaptionReader() {}

  private static final Pattern TIME_LINE = Pattern.compile(
      "(\\d+):(\\d{2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(\\d+):(\\d{2}):(\\d{2})[.,](\\d{3})");

  /**
   * Parses {@code srtFile} into a list of {@link CaptionEvent}s, all tagged
   * with the given {@code language} and {@code service}. Returns an empty
   * list (never null) if the file doesn't exist or contains no parseable
   * cues; malformed individual blocks are skipped rather than aborting the
   * whole parse.
   */
  public static List<CaptionEvent> read(File srtFile, String language, String service) throws IOException
  {
    List<CaptionEvent> out = new ArrayList<>();
    if (srtFile == null || !srtFile.isFile()) return out;

    List<String> lines = Files.readAllLines(srtFile.toPath(), StandardCharsets.UTF_8);
    if (!lines.isEmpty())
    {
      // Strip a leading UTF-8 BOM if present so the first index line still
      // matches its "digits only" check.
      String first = lines.get(0);
      if (!first.isEmpty() && first.charAt(0) == '\uFEFF')
      {
        lines.set(0, first.substring(1));
      }
    }

    int i = 0;
    int n = lines.size();
    while (i < n)
    {
      while (i < n && lines.get(i).trim().isEmpty()) i++;
      if (i >= n) break;

      // Optional numeric cue-index line.
      if (lines.get(i).trim().matches("\\d+")) i++;
      if (i >= n) break;

      Matcher m = TIME_LINE.matcher(lines.get(i));
      if (!m.find())
      {
        // Not a well-formed timecode line; skip forward rather than
        // aborting the whole file on one stray/corrupt block.
        i++;
        continue;
      }
      i++;
      double begin = toSeconds(m.group(1), m.group(2), m.group(3), m.group(4));
      double end = toSeconds(m.group(5), m.group(6), m.group(7), m.group(8));

      StringBuilder text = new StringBuilder();
      while (i < n && !lines.get(i).trim().isEmpty())
      {
        if (text.length() > 0) text.append('\n');
        text.append(lines.get(i));
        i++;
      }
      if (text.length() == 0) continue;

      out.add(CaptionEvent.builder()
          .language(language)
          .service(service)
          .beginSeconds(begin)
          .endSeconds(end)
          .text(text.toString())
          .build());
    }
    return out;
  }

  private static double toSeconds(String h, String m, String s, String ms)
  {
    return Long.parseLong(h) * 3600.0 + Integer.parseInt(m) * 60.0 + Integer.parseInt(s) + Integer.parseInt(ms) / 1000.0;
  }
}
