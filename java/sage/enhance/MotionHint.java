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

import java.io.File;

/**
 * Resolves the {@link EnhancementProfile} for a media file by walking the same
 * Wiz.bin records playback already uses: file &rarr; {@code MediaFile} &rarr;
 * content {@code Airing} &rarr; {@code Show} &rarr; categories.
 *
 * <p>Everything here is fail-safe: any missing link, or any exception, yields a
 * {@code null} profile so callers fall back to the frame-rate proxy. Enhancement
 * is an optimization and must never break a tune, so this never throws.
 */
public final class MotionHint
{
  private MotionHint() { }

  /**
   * Best-effort profile for a source file, or {@code null} if it can't be
   * resolved (unknown file, no EPG data, or any error).
   */
  public static EnhancementProfile profileForFile(File src)
  {
    try
    {
      if (src == null) return null;
      sage.MediaFile mf = sage.Wizard.getInstance().getFileForFilePath(src);
      if (mf == null) return null;
      sage.Airing air = mf.getContentAiring();
      sage.Show show = (air != null) ? air.getShow() : null;
      if (show == null) return null;
      return EnhancementProfile.classify(show.getCategories());
    }
    catch (Throwable t)
    {
      return null;
    }
  }

  /**
   * Resolve the motion class to size bitrate with. A known, non-general genre
   * wins; otherwise fall back to the frame-rate proxy (60fps content is
   * overwhelmingly sports/action).
   */
  public static EnhancementProfile.MotionClass motionFor(EnhancementProfile profile, int fps)
  {
    if (profile != null && profile != EnhancementProfile.GENERAL)
      return profile.motionClass();
    return (fps >= 50)
        ? EnhancementProfile.MotionClass.HIGH
        : EnhancementProfile.MotionClass.MEDIUM;
  }
}
