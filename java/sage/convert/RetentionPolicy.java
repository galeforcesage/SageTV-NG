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
 * How long a derivative should be kept, and how aggressively it may be reclaimed.
 * Retention is advisory metadata consulted by the reuse/cleanup layer (B2); it
 * never overrides original-recording safety.
 */
public enum RetentionPolicy
{
  /** Keep until the user explicitly deletes it. */
  KEEP_FOREVER,
  /** Keep while the source recording exists; delete with the source. */
  UNTIL_SOURCE_DELETED,
  /** Transient: safe to reclaim automatically under space pressure. */
  TEMPORARY;

  public static RetentionPolicy defaultFor(ConversionPurpose purpose)
  {
    if (purpose == null) return UNTIL_SOURCE_DELETED;
    switch (purpose)
    {
      case ARCHIVE:
      case EXACT_BACKUP:
        return KEEP_FOREVER;
      case TRAVEL:
      case USB_TV:
        return TEMPORARY;
      default:
        return UNTIL_SOURCE_DELETED;
    }
  }
}
