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

/** Client-declared quality of its local/display upscaler. */
public enum UpscalingCapability
{
  NONE,
  STANDARD,
  HIGH_QUALITY,
  AI;

  public static UpscalingCapability fromToken(String token)
  {
    if (token == null) return NONE;
    String normalized = token.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    if ("standard".equals(normalized)) return STANDARD;
    if ("high_quality".equals(normalized) || "highquality".equals(normalized))
      return HIGH_QUALITY;
    if ("ai".equals(normalized)) return AI;
    return NONE;
  }

  public String token()
  {
    return name().toLowerCase(Locale.ROOT);
  }

  public boolean isHighQuality()
  {
    return this == HIGH_QUALITY || this == AI;
  }
}
