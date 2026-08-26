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
package sage.convert;

/**
 * The user-facing intent behind an offline conversion, as chosen in the guided
 * "What do you want to create?" workflow. Purpose selects a set of sensible
 * defaults for every other menu; it never encodes engine behaviour on its own.
 */
public enum ConversionPurpose
{
  /** A file to sideload onto a USB stick and play on a TV. */
  USB_TV,
  /** An offline copy sized for a phone or tablet. */
  OFFLINE_DEVICE,
  /** The smallest reasonable copy for travel / limited data. */
  TRAVEL,
  /** A quality-first, optionally AI-enhanced copy of a favourite. */
  ENHANCED_FAVORITE,
  /** A smaller re-encode that reclaims disk while preserving resolution. */
  ARCHIVE,
  /** A byte-preserving remux/copy of the original. */
  EXACT_BACKUP,
  /** Fully manual — no purpose defaults applied. */
  CUSTOM
}
