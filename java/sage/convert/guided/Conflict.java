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

import java.util.Collections;
import java.util.List;

/**
 * A single compatibility or feasibility finding surfaced by the
 * {@link GuidedRecommender}. The guided workflow's rule is <em>warn, don't
 * unnecessarily prohibit</em>: only {@link Severity#INCOMPATIBLE} findings block
 * a build; {@link Severity#UNVERIFIED} findings are kept and shown so the user
 * can decide.
 */
public final class Conflict
{
  /** Compatibility state of a finding. */
  public enum Severity
  {
    /** Fully compatible / informational note. */
    COMPATIBLE,
    /** Supported by SageTV-NG but not verified for the selected player/goal. */
    UNVERIFIED,
    /** Technically incompatible — the two choices cannot coexist in one output. */
    INCOMPATIBLE
  }

  private final Severity severity;
  private final String message;
  private final List<String> options;

  public Conflict(Severity severity, String message, List<String> options)
  {
    this.severity = severity;
    this.message = message;
    this.options = options == null ? Collections.<String>emptyList()
        : Collections.unmodifiableList(options);
  }

  public Severity getSeverity() { return severity; }
  public String getMessage() { return message; }

  /** Suggested resolutions the UI can present as buttons; may be empty. */
  public List<String> getOptions() { return options; }

  public boolean isBlocking() { return severity == Severity.INCOMPATIBLE; }

  @Override
  public String toString()
  {
    return severity + ": " + message;
  }
}
