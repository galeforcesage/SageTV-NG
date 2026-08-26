/*
 * Copyright 2015 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sage.convert.guided;

/**
 * "What should this conversion prioritize?" (Menu 4). A single primary priority
 * that tilts the quality-vs-size / speed / compatibility trade-off. Combined
 * with {@link TransferClass} size pressure to resolve the final quality target.
 */
public enum QualityPriority
{
  /** Best practical picture — lowest CQ, hardware quality preset. */
  BEST_PICTURE,
  /** Balanced quality and file size (the sensible default). */
  BALANCED,
  /** Smaller file — higher CQ, stronger compression. */
  SMALLER,
  /** Fastest conversion — favour stream copy / fast presets. */
  FASTEST,
  /** Maximum compatibility — H.264 / MP4 / AAC regardless of efficiency. */
  MAX_COMPAT,
  /** Preserve source characteristics — keep resolution, fps, HDR, audio. */
  PRESERVE_SOURCE,
  /** Fully manual — no priority defaults; rely on Customize overrides. */
  CUSTOM
}
