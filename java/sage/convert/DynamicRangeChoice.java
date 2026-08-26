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
 * How the output's dynamic range is handled relative to the source. The engine
 * never changes dynamic range silently: {@link #PRESERVE_HDR10} and
 * {@link #TONEMAP_SDR} are explicit user choices and are surfaced in the
 * resolved operations list.
 */
public enum DynamicRangeChoice
{
  /** Preserve HDR when the source is HDR, otherwise leave SDR as SDR. */
  AUTO,
  /** Do nothing to the dynamic range (copy colour metadata as-is). */
  KEEP,
  /** Explicitly preserve/pass through HDR10 (10-bit, PQ, colour metadata). */
  PRESERVE_HDR10,
  /** Explicitly tone-map HDR down to SDR (BT.709). */
  TONEMAP_SDR
}
