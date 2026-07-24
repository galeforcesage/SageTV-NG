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

/**
 * Identifies where a {@link CaptionEvent} stream originated from. Caption
 * handling in SageTV-NG is source-driven rather than codec-driven: each
 * source has its own extraction pipeline, but all of them converge on the
 * same {@link CaptionEvent} model before being written out as an SRT
 * sidecar (SageTV-NG's sole sidecar format; captions render server-side and
 * are pushed to clients as drawing ops, so no client-facing format like
 * VTT is ever needed), so playback and rendering code never needs to care
 * which of these produced a given cue.
 */
public enum CaptionSource
{
  /** Legacy line-21 EIA-608 or CEA-708 captions embedded in MPEG-2 user data / H.264/H.265 SEI (ATSC 1.0). */
  ATSC1_608_708,

  /** STPP/IMSC1 (TTML) caption stream carried as its own PID in an ATSC 3.0 MPEG-TS. */
  ATSC3_STPP,

  /** Subtitles supplied externally (WebVTT, SRT, TTML) for OTT/internet content. */
  EXTERNAL_SUBTITLE
}
