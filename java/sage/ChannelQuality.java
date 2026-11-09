/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */
package sage;

import java.util.Locale;

/**
 * Source-provided channel quality classification.
 *
 * <p>This is advisory lineup metadata, not decoded stream geometry. Explicit
 * width, height, frame rate, HDR, and codec information always take precedence.
 */
public enum ChannelQuality
{
  UNKNOWN,
  SD,
  HD,
  UHD;

  /**
   * Parses a persisted or protocol token.
   *
   * @param token quality token, or {@code null}
   * @return the matching classification, or {@link #UNKNOWN}
   */
  public static ChannelQuality fromToken(String token)
  {
    if (token == null) return UNKNOWN;
    try
    {
      return valueOf(token.trim().toUpperCase(Locale.ROOT));
    }
    catch (IllegalArgumentException e)
    {
      return UNKNOWN;
    }
  }
}
