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
 * Guards the legal state transitions of a {@link DerivativeRecord}. Centralizing
 * the transition table means the job/validation phase, the API, and the UI all
 * agree on what "ready offline" means and can never advertise a half-produced or
 * unvalidated output.
 *
 * <p>Legal transitions:
 * <ul>
 *   <li>{@code PREPARING -> VALIDATING} (output produced)</li>
 *   <li>{@code VALIDATING -> READY}    (validation passed)</li>
 *   <li>{@code PREPARING -> FAILED}    (production failed)</li>
 *   <li>{@code VALIDATING -> FAILED}   (validation failed)</li>
 * </ul>
 * Every other transition is rejected, including any transition out of a terminal
 * state ({@code READY}/{@code FAILED}).
 */
public final class DerivativeStateMachine
{
  private DerivativeStateMachine() { }

  public static boolean isLegalTransition(DerivativeState from, DerivativeState to)
  {
    if (from == null || to == null) return false;
    if (from.isTerminal()) return false;
    switch (from)
    {
      case PREPARING:
        return to == DerivativeState.VALIDATING || to == DerivativeState.FAILED;
      case VALIDATING:
        return to == DerivativeState.READY || to == DerivativeState.FAILED;
      default:
        return false;
    }
  }

  /**
   * Returns the next state or throws {@link IllegalStateException} if the
   * transition is not permitted.
   */
  public static DerivativeState requireTransition(DerivativeState from, DerivativeState to)
  {
    if (!isLegalTransition(from, to))
      throw new IllegalStateException("illegal derivative state transition: " + from + " -> " + to);
    return to;
  }
}
