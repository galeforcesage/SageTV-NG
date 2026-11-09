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

/** User policy for deciding where video upscaling occurs. */
public enum UpscalingPolicy
{
  AUTO,
  ALWAYS,
  AI_ONLY,
  NEVER,
  LOCAL;

  public static UpscalingPolicy fromToken(String token)
  {
    if (token == null) return AUTO;
    String normalized = token.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    if ("always".equals(normalized) || "server".equals(normalized)) return ALWAYS;
    if ("ai_only".equals(normalized) || "ai".equals(normalized)) return AI_ONLY;
    if ("never".equals(normalized) || "off".equals(normalized)
        || "none".equals(normalized) || "disabled".equals(normalized))
      return NEVER;
    if ("local".equals(normalized)) return LOCAL;
    return AUTO;
  }

  public String token()
  {
    if (this == AI_ONLY) return "ai_only";
    return name().toLowerCase(Locale.ROOT);
  }

  public boolean requiresSpecializedServerUpscaler()
  {
    return this == AI_ONLY;
  }

  public boolean requiresSpecializedServerUpscaler(UpscalingCapability capability)
  {
    return this == AI_ONLY
        || (this == AUTO && capability == UpscalingCapability.HIGH_QUALITY);
  }
}
