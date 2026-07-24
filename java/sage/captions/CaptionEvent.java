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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A single normalized caption cue, the universal intermediate representation
 * that every caption source (CEA-608/708, ATSC3 STPP/IMSC1, external
 * WebVTT/SRT) is converted into before being written out as an SRT
 * sidecar (the only sidecar format SageTV-NG persists: captions are
 * rendered server-side and pushed to every client as drawing ops, so a
 * client-side format like VTT has no consumer). This decouples caption
 * <em>rendering</em> from caption
 * <em>source</em>: nothing downstream of a {@code List<CaptionEvent>} needs
 * to know whether the cue originated from line-21 user data, a TTML document,
 * or an internet subtitle file.
 *
 * <p>Instances are immutable; use {@link Builder} to construct them.
 */
public final class CaptionEvent implements Comparable<CaptionEvent>
{
  /**
   * Well-known {@link #getService()} values for CEA-608/CEA-708 sources
   * (see {@code CaptionExtractionJob}'s ccextractor passes). ATSC3 STPP
   * sources have no discrete channel/slot concept — captions are simply
   * multiplexed per language — so that path uses the uppercased normalized
   * language tag as the service instead (e.g. {@code "ENG"}, {@code "SPA"}).
   * Either convention lets {@link SrtCaptionWriter} decide which sidecar a
   * track belongs in without callers needing source-specific knowledge.
   */
  public static final String SERVICE_CC1 = "CC1";
  public static final String SERVICE_CC2 = "CC2";
  public static final String SERVICE_708_SVC1 = "708-1";

  private final String language;
  private final double beginSeconds;
  private final double endSeconds;
  private final String text;
  private final String region;
  private final String service;

  private CaptionEvent(Builder b)
  {
    this.language = b.language;
    this.beginSeconds = b.beginSeconds;
    this.endSeconds = b.endSeconds;
    this.text = b.text;
    this.region = b.region;
    this.service = b.service;
  }

  /** BCP-47/ISO-639-ish language tag, e.g. "eng", "spa". May be {@code null}/"und" if unknown. */
  public String getLanguage()
  {
    return language;
  }

  /** Cue start time in seconds, relative to the start of the media. */
  public double getBeginSeconds()
  {
    return beginSeconds;
  }

  /** Cue end time in seconds, relative to the start of the media. */
  public double getEndSeconds()
  {
    return endSeconds;
  }

  /** Plain text of the cue (formatting/markup stripped), may contain '\n' for multi-line cues. */
  public String getText()
  {
    return text;
  }

  /** Screen region hint ("top", "bottom"), or {@code null} if not specified by the source. */
  public String getRegion()
  {
    return region;
  }

  /**
   * Source-driven channel/track tag (e.g. {@link #SERVICE_CC1},
   * {@link #SERVICE_CC2}, {@link #SERVICE_708_SVC1}, or — for STPP —
   * an uppercased language tag like {@code "ENG"}/{@code "SPA"}). Drives
   * which sidecar file {@link SrtCaptionWriter#writeGrouped} routes a cue
   * to; may be {@code null} if the source doesn't distinguish tracks.
   */
  public String getService()
  {
    return service;
  }

  public double getDurationSeconds()
  {
    return endSeconds - beginSeconds;
  }

  @Override
  public int compareTo(CaptionEvent o)
  {
    return Double.compare(this.beginSeconds, o.beginSeconds);
  }

  @Override
  public String toString()
  {
    return "CaptionEvent{[" + formatSrtTime(beginSeconds) + " --> " + formatSrtTime(endSeconds) +
        "] lang=" + language + " service=" + service + " region=" + region +
        " text=" + text.replace("\n", "\\n") + "}";
  }

  /**
   * Renders this cue as one SRT block (index line, timecode line, text
   * line(s), trailing blank line) using the standard {@code HH:MM:SS,mmm}
   * SRT timecode format.
   */
  public String toSrtBlock(int index)
  {
    StringBuilder sb = new StringBuilder();
    sb.append(index).append('\n');
    sb.append(formatSrtTime(beginSeconds)).append(" --> ").append(formatSrtTime(endSeconds)).append('\n');
    sb.append(text).append('\n');
    sb.append('\n');
    return sb.toString();
  }

  /** Formats a relative-seconds offset as an SRT timecode: {@code HH:MM:SS,mmm}. */
  public static String formatSrtTime(double seconds)
  {
    if (seconds < 0) seconds = 0;
    long totalMs = Math.round(seconds * 1000.0);
    long ms = totalMs % 1000;
    long totalSec = totalMs / 1000;
    long s = totalSec % 60;
    long totalMin = totalSec / 60;
    long m = totalMin % 60;
    long h = totalMin / 60;
    return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", h, m, s, ms);
  }

  public static Builder builder()
  {
    return new Builder();
  }

  /** Builder for {@link CaptionEvent}. */
  public static final class Builder
  {
    private String language = "und";
    private double beginSeconds;
    private double endSeconds;
    private String text = "";
    private String region;
    private String service;

    public Builder language(String language)
    {
      this.language = (language == null || language.isEmpty()) ? "und" : language;
      return this;
    }

    public Builder beginSeconds(double beginSeconds)
    {
      this.beginSeconds = beginSeconds;
      return this;
    }

    public Builder endSeconds(double endSeconds)
    {
      this.endSeconds = endSeconds;
      return this;
    }

    public Builder text(String text)
    {
      this.text = (text == null) ? "" : text;
      return this;
    }

    public Builder region(String region)
    {
      this.region = region;
      return this;
    }

    public Builder service(String service)
    {
      this.service = service;
      return this;
    }

    public CaptionEvent build()
    {
      if (endSeconds < beginSeconds) endSeconds = beginSeconds;
      return new CaptionEvent(this);
    }
  }

  // ── Coalescing ─────────────────────────────────────────────────────────

  /** Minimum cue duration enforced by {@link #coalesce(List)}: short cues get their end extended. */
  public static final double MIN_CUE_DURATION_SECONDS = 1.0;

  /**
   * Coalesces a sequence of fine-grained (e.g. character-by-character
   * roll-up) caption events into sentence-level cues.
   *
   * <p>Events are assumed to already be in chronological order (as produced
   * by a single source pass) and are processed as a rolling accumulation:
   * as long as the next event's text is a prefix-extension of the text
   * accumulated so far (the common char-by-char "typewriter" pattern used
   * by broadcast caption encoders), it is merged in-place, extending the
   * cue's end time and replacing its text with the longer version. As soon
   * as an event's text is <em>not</em> an extension of the current buffer
   * (a genuinely new sentence/cue), the buffered cue is flushed and a new
   * one starts.
   *
   * <p>On flush, any cue shorter than {@link #MIN_CUE_DURATION_SECONDS} has
   * its end time extended to {@code begin + MIN_CUE_DURATION_SECONDS} so it
   * remains legible.
   *
   * @param events input events, assumed sorted by begin time
   * @return a new, coalesced list of events (input list is left untouched)
   */
  public static List<CaptionEvent> coalesce(List<CaptionEvent> events)
  {
    List<CaptionEvent> out = new ArrayList<>();
    if (events == null || events.isEmpty()) return out;

    Builder current = null;
    for (CaptionEvent e : events)
    {
      if (current == null)
      {
        current = startFrom(e);
        continue;
      }
      String bufferedText = current.text;
      String candidateText = e.text;
      boolean sameGroup = candidateText.startsWith(bufferedText) &&
          sameLanguageRegionAndService(current, e);
      if (sameGroup)
      {
        // Extend: same sentence continuing to roll up character-by-character.
        current.text(candidateText);
        current.endSeconds(e.endSeconds);
      }
      else
      {
        out.add(finish(current));
        current = startFrom(e);
      }
    }
    if (current != null) out.add(finish(current));
    return out;
  }

  private static boolean sameLanguageRegionAndService(Builder current, CaptionEvent e)
  {
    boolean langOk = (current.language == null) ? e.language == null : current.language.equals(e.language);
    boolean regionOk = (current.region == null) ? e.region == null : current.region.equals(e.region);
    boolean serviceOk = (current.service == null) ? e.service == null : current.service.equals(e.service);
    return langOk && regionOk && serviceOk;
  }

  private static Builder startFrom(CaptionEvent e)
  {
    return builder()
        .language(e.language)
        .beginSeconds(e.beginSeconds)
        .endSeconds(e.endSeconds)
        .text(e.text)
        .region(e.region)
        .service(e.service);
  }

  private static CaptionEvent finish(Builder b)
  {
    if (b.endSeconds - b.beginSeconds < MIN_CUE_DURATION_SECONDS)
      b.endSeconds(b.beginSeconds + MIN_CUE_DURATION_SECONDS);
    return b.build();
  }
}
