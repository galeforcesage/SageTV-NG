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
 * Who/what actually controls a {@link NightModeMode#PLATFORM_NIGHT_MODE}
 * request on the client: the app itself, the device OS, the hardware
 * vendor, or -- when the client can't act on it at all -- a purely
 * external/advisory signal. This is client-side metadata only; it never
 * changes server-side executability (that is decided purely by {@link
 * NightModeMode#isServerExecutable()}). Carried through to diagnostics
 * ({@code nightModeControllability}) but not otherwise consumed by the
 * resolver.
 */
public enum NightModeControllability
{
  APP_SCOPED,
  OS_SCOPED,
  VENDOR_SCOPED,
  EXTERNAL_ADVISORY;

  /** Fails safe to the most conservative/non-actionable value for any unrecognized/absent wire value. */
  public static NightModeControllability fromWire(String wire)
  {
    if (wire == null)
      return EXTERNAL_ADVISORY;
    try
    {
      return NightModeControllability.valueOf(wire.trim());
    }
    catch (IllegalArgumentException e)
    {
      return EXTERNAL_ADVISORY;
    }
  }
}
