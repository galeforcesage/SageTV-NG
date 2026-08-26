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
 * Lifecycle state of a derivative (a converted/enhanced/archived copy of a
 * source recording).
 *
 * <p>The only legal progressions are {@code PREPARING -> VALIDATING -> READY}
 * and, from either non-terminal state, {@code -> FAILED}. A derivative is
 * considered "ready offline" — usable and advertisable to clients — only once it
 * reaches {@link #READY}, which happens after the output is both complete and
 * validated. See {@link DerivativeStateMachine} for the transition guard.
 */
public enum DerivativeState
{
  /** The transcode/enhancement job is producing the output file. */
  PREPARING,
  /** Output produced; being validated (container opens, duration, streams). */
  VALIDATING,
  /** Validated and usable. The only state in which the derivative is offered. */
  READY,
  /** Production or validation failed; the output must not be advertised. */
  FAILED;

  public boolean isTerminal()
  {
    return this == READY || this == FAILED;
  }
}
