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
 * A single "What should SageTV-NG create?" intent from the guided front door
 * (Menu 1). Goals are combinable; the {@link GuidedRecommender} folds the whole
 * selected set into one recommendation, surfacing a {@link Conflict} when two
 * goals genuinely cannot be satisfied by a single output (e.g. an exact backup
 * plus an AI upscale).
 *
 * <p>Goals describe <em>intent</em>, never engine behaviour: they bias the
 * resolved {@link sage.convert.ConversionRequest} but are always overridable in
 * the Customize step.
 */
public enum CreationGoal
{
  // --- Group A: portable / offline copies ---------------------------------
  /** A file to sideload onto a USB stick and play on a TV. */
  USB_TV_PLAYBACK,
  /** An offline copy sized for a phone. */
  PHONE_OFFLINE,
  /** An offline copy sized for a tablet. */
  TABLET_OFFLINE,
  /** A smaller copy for WAN / cellular download. */
  WAN_SMALLER,

  // --- Group B: picture enhancement ---------------------------------------
  /** Improve or upscale the picture (requests picture processing). */
  IMPROVE_UPSCALE,
  /** Retain and manage the result as a persistent enhanced derivative. */
  REUSABLE_FAVORITE,

  // --- Group C: preservation / storage ------------------------------------
  /** Reduce long-term storage use (space-saving re-encode). */
  REDUCE_STORAGE,
  /** Preserve the original resolution and frame rate. */
  PRESERVE_RES_FPS,
  /** Preserve surround audio and additional tracks. */
  PRESERVE_SURROUND,
  /** Create a byte-preserving exact backup of the original. */
  EXACT_BACKUP,

  // --- Group D: general requirements --------------------------------------
  /** Prefer maximum device compatibility. */
  PREFER_COMPAT,
  /** Prefer the smallest practical file. */
  PREFER_SMALLEST,
  /** Preserve HDR. */
  PRESERVE_HDR,
  /** Include subtitles. */
  INCLUDE_SUBTITLES
}
