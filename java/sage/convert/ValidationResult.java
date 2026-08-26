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
 * The outcome of validating a produced derivative: pass/fail plus a
 * human-readable reason for the fail (surfaced in the UI/telemetry). Pure value
 * object.
 */
public final class ValidationResult
{
  private final boolean valid;
  private final String reason;

  private ValidationResult(boolean valid, String reason)
  {
    this.valid = valid;
    this.reason = reason;
  }

  public static ValidationResult pass()
  {
    return new ValidationResult(true, "");
  }

  public static ValidationResult fail(String reason)
  {
    return new ValidationResult(false, reason == null ? "" : reason);
  }

  public boolean isValid() { return valid; }
  public String getReason() { return reason; }

  /** Maps the outcome to the terminal derivative state. */
  public DerivativeState toState()
  {
    return valid ? DerivativeState.READY : DerivativeState.FAILED;
  }
}
