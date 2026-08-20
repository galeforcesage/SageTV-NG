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
 * The unit of "how much enhancement" a live session is granted.
 *
 * <p>This is deliberately <b>not</b> a new {@code PlaybackDecisionEngine.Decision}
 * tier. The engine keeps deciding what is <i>playable</i> (DIRECT_PLAY / REMUX /
 * AUDIO_TRANSCODE / TRANSCODE) with its existing four-tier ranking untouched; a
 * tier from this enum is a <i>treatment</i> applied to the already-chosen winner,
 * following the {@code promoteForServerEqIfRequested} precedent. When enhancement
 * is disabled or denied the tier is {@link #NONE} and behavior is byte-identical
 * to today.
 *
 * <p>{@link #DEINTERLACE_ONLY} carries real weight: for a 1080i source viewed on a
 * 1080p panel, a proper GPU deinterlace is most of the visible win at roughly a
 * quarter of the GPU cost of a 4K upscale.
 */
public enum EnhancementTier
{
  /** No enhancement. Existing behavior, no GPU allocation of any kind. */
  NONE(0, 0, 0),
  /**
   * GPU deinterlace at the source's native resolution, no scaling.
   * Exempt from {@link #SOURCE_HEIGHT_FLOOR} because it is not upscaling.
   */
  DEINTERLACE_ONLY(0, 0, 1),
  ENHANCE_1080P(1920, 1080, 2),
  ENHANCE_1440P(2560, 1440, 3),
  ENHANCE_2160P(3840, 2160, 4);

  /**
   * Minimum <b>source height</b> for any upscaling tier.
   *
   * <p>The test is height, never width. This distinction is not pedantic: DVD and
   * SD material at 720x480 must be <i>excluded</i>, and its 720 is the width. So
   * 720x480, 704x480, 640x480 and 720x576 are all below the floor, while 1280x720
   * and 1920x1080 are at or above it. For interlaced sources the full frame height
   * is used, so 1080i counts as 1080 and qualifies.
   *
   * <p>Sub-floor material is out of scope for live enhancement and belongs to an
   * offline AI upscale pass ({@code Ministry.shouldAutoAiUpscale()}), where there
   * is time to do it properly. Live upscaling of SD would combine the largest
   * scaling factor with the least source information and the most visible
   * artifacts, while consuming budget that HD viewers actually benefit from.
   */
  public static final int SOURCE_HEIGHT_FLOOR = 720;

  private final int targetWidth;
  private final int targetHeight;
  private final int rank;

  private EnhancementTier(int w, int h, int rank)
  {
    this.targetWidth = w;
    this.targetHeight = h;
    this.rank = rank;
  }

  /** Target width, or 0 when the tier does not rescale. */
  public int getTargetWidth() { return targetWidth; }

  /** Target height, or 0 when the tier does not rescale. */
  public int getTargetHeight() { return targetHeight; }

  /** Ordering weight; higher costs more GPU. */
  public int getRank() { return rank; }

  /** True if this tier changes the frame size. */
  public boolean isUpscaling() { return targetHeight > 0; }

  /** True if this tier does any GPU work at all. */
  public boolean isActive() { return this != NONE; }

  /**
   * The next tier down the degradation ladder
   * ({@code 2160p -> 1440p -> 1080p -> deinterlace-only -> none}).
   */
  public EnhancementTier downgrade()
  {
    switch (this)
    {
      case ENHANCE_2160P: return ENHANCE_1440P;
      case ENHANCE_1440P: return ENHANCE_1080P;
      case ENHANCE_1080P: return DEINTERLACE_ONLY;
      case DEINTERLACE_ONLY: return NONE;
      default: return NONE;
    }
  }

  /**
   * True if {@code sourceHeight} is tall enough for this tier to be legal.
   * Non-upscaling tiers are always legal; upscaling tiers require
   * {@link #SOURCE_HEIGHT_FLOOR}.
   */
  public boolean isLegalForSourceHeight(int sourceHeight)
  {
    if (!isUpscaling()) return true;
    return sourceHeight >= SOURCE_HEIGHT_FLOOR;
  }

  /** Lowercase token for properties and log lines. */
  public String token() { return name().toLowerCase(Locale.ROOT); }

  /**
   * Short form used in the {@code CAP_EFFECTIVE_DELIVERY} token sent to clients,
   * e.g. {@code 2160p}. Returns null for {@link #NONE}.
   */
  public String wireToken()
  {
    switch (this)
    {
      case DEINTERLACE_ONLY: return "deint";
      case ENHANCE_1080P: return "1080p";
      case ENHANCE_1440P: return "1440p";
      case ENHANCE_2160P: return "2160p";
      default: return null;
    }
  }

  public static EnhancementTier fromToken(String t)
  {
    if (t == null) return NONE;
    String s = t.trim().toLowerCase(Locale.ROOT);
    if (s.length() == 0) return NONE;
    if (s.equals("deint") || s.equals("deinterlace") || s.equals("deinterlace_only"))
      return DEINTERLACE_ONLY;
    if (s.equals("1080p") || s.equals("1080")) return ENHANCE_1080P;
    if (s.equals("1440p") || s.equals("1440")) return ENHANCE_1440P;
    if (s.equals("2160p") || s.equals("2160") || s.equals("4k")) return ENHANCE_2160P;
    try { return EnhancementTier.valueOf(s.toUpperCase(Locale.ROOT)); }
    catch (IllegalArgumentException ex) { return NONE; }
  }

  /**
   * Highest tier whose target height does not exceed {@code maxHeight}.
   * Used to clamp against the admin "Maximum Enhancement Resolution" setting
   * and against a client's declared maximum decodable output.
   */
  public static EnhancementTier clampToHeight(EnhancementTier tier, int maxHeight)
  {
    if (tier == null) return NONE;
    EnhancementTier t = tier;
    while (t.isUpscaling() && t.getTargetHeight() > maxHeight)
      t = t.downgrade();
    return t;
  }

  /**
   * Highest tier that fits inside {@code maxWidth} x {@code maxHeight} in BOTH
   * dimensions.
   *
   * <p>Height alone is not sufficient. A panel is not guaranteed to be 16:9, and
   * clamping only by height can select a tier wider than the display: a
   * 2960x1848 tablet passes a 2160-height clamp all the way down to 1440p
   * (2560x1440) correctly, but a narrow or rotated panel such as 1920x2160 would
   * accept 2160p and hand it a 3840-wide picture it can only downscale again.
   * Building pixels the sink cannot show is pure cost -- GPU, bitrate and
   * decoder headroom spent to be thrown away.
   *
   * <p>A non-positive limit in either dimension means "unknown", and an unknown
   * ceiling clamps nothing; callers gate on the sink being known before they get
   * here.
   */
  public static EnhancementTier clampToSink(EnhancementTier tier, int maxWidth, int maxHeight)
  {
    if (tier == null) return NONE;
    EnhancementTier t = tier;
    if (maxHeight > 0) t = clampToHeight(t, maxHeight);
    if (maxWidth > 0)
      while (t.isUpscaling() && t.getTargetWidth() > maxWidth)
        t = t.downgrade();
    return t;
  }
}
