/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package sage.enhance;

import java.util.Locale;

/**
 * Content genre bucket derived from a recording's EPG categories, used to pick an
 * upscale tuning profile. This is a <b>pure value type</b>: the categories come
 * from the Wiz.bin {@code Show} record that playback already resolves, and the
 * mapping from those strings to a profile lives here so it can be unit-tested
 * without a database.
 *
 * <p>Each profile carries a {@link MotionClass}, which is the strongest lever on
 * quality-per-bit: high-motion sports need bits for motion, high-detail nature
 * needs bits for texture, while talking-head news/talk can be given fewer bits
 * for the same perceived quality. The class only classifies; the concrete
 * bitrate ladder (and its per-genre property overrides) lives in
 * {@link GpuEnhancePipeline#suggestBitrateKbps}.
 */
public enum EnhancementProfile
{
  /** Live/action sports — high motion, benefits most from bitrate. */
  SPORTS(MotionClass.HIGH),
  /** Nature/wildlife — high spatial detail (foliage, water), wants bits. */
  NATURE(MotionClass.HIGH),
  /** Film/movies — typically 24fps, cinematic, moderate motion. */
  FILM(MotionClass.MEDIUM),
  /** News/talk — mostly static talking heads, can save bits. */
  NEWS_TALK(MotionClass.LOW),
  /** Anything not otherwise classified. */
  GENERAL(MotionClass.MEDIUM);

  /** How much a profile stresses the encoder, which drives the bitrate ladder. */
  public enum MotionClass { LOW, MEDIUM, HIGH }

  private final MotionClass motion;

  EnhancementProfile(MotionClass motion) { this.motion = motion; }

  public MotionClass motionClass() { return motion; }

  /**
   * Map EPG categories to a profile. Categories are scanned in order (the first
   * is the primary genre), and within each the more specific/high-value buckets
   * win. Returns {@link #GENERAL} when nothing matches or the array is empty.
   *
   * <p>The keyword groups below cover the common Gracenote/Schedules Direct
   * primary categories SageTV stores on a {@code Show}; the full category-to-
   * profile map lives in {@code docs/upscale logic.md}. Matching is a
   * case-insensitive substring test so both the bare primary ("Sports event",
   * "News", "Talk") and its longer variants resolve to the same profile.
   */
  public static EnhancementProfile classify(String[] categories)
  {
    if (categories != null)
    {
      for (String c : categories)
      {
        if (c == null || c.length() == 0) continue;
        String lc = c.toLowerCase(Locale.ROOT);
        if (lc.contains("sport") || lc.contains("football") || lc.contains("basketball")
            || lc.contains("baseball") || lc.contains("hockey") || lc.contains("soccer")
            || lc.contains("olympic") || lc.contains("racing") || lc.contains("boxing")
            || lc.contains("wrestling"))
          return SPORTS;
        if (lc.contains("news") || lc.contains("talk") || lc.contains("interview")
            || lc.contains("public affairs") || lc.contains("weather"))
          return NEWS_TALK;
        if (lc.contains("nature") || lc.contains("wildlife") || lc.contains("documentary")
            || lc.contains("science") || lc.contains("history") || lc.contains("travel")
            || lc.contains("outdoor"))
          return NATURE;
        if (lc.contains("movie") || lc.contains("film"))
          return FILM;
      }
    }
    return GENERAL;
  }
}
