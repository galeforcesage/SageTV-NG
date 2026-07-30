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
package sage.audioproc;

/**
 * The connecting client's platform/runtime, as reported by {@code
 * AUDIO_PROCESSING_CAPABILITIES}. Governs the LEGACY hard rule: a legacy
 * SageTV/STV client is always {@link #LEGACY} and the resolver must always
 * return {@link AudioProcessingLocation#NONE} for it, regardless of any
 * other field.
 */
public enum ClientKind
{
  PWA_BROWSER,
  PWA_TIZEN,
  ANDROID_MINICLIENT,
  WINDOWS_NG,
  LEGACY,
  /** Safe fallback for an absent/unrecognized value -- never assume capability. */
  UNKNOWN;

  /** Fails safe to {@link #UNKNOWN} for any unrecognized/absent wire value. */
  public static ClientKind fromWire(String wire)
  {
    if (wire == null)
      return UNKNOWN;
    try
    {
      return ClientKind.valueOf(wire.trim());
    }
    catch (IllegalArgumentException e)
    {
      return UNKNOWN;
    }
  }
}
