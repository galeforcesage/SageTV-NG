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

import sage.convert.ConversionPlan;
import sage.convert.ConversionRequest;

/**
 * The resolved output of {@link GuidedRecommender#recommend}: the concrete
 * {@link ConversionRequest} the guided answers (plus any overrides) resolved to,
 * the fully-built {@link ConversionPlan} (null only when a blocking conflict
 * prevented a build), a plain-language rationale ("why this was selected"), the
 * list of {@link Conflict}s, and a size estimate.
 */
public final class Recommendation
{
  private final ConversionRequest request;
  private final ConversionPlan plan;
  private final List<String> rationale;
  private final List<Conflict> conflicts;
  private final long estimatedBytes;

  public Recommendation(ConversionRequest request, ConversionPlan plan,
      List<String> rationale, List<Conflict> conflicts, long estimatedBytes)
  {
    this.request = request;
    this.plan = plan;
    this.rationale = rationale == null ? Collections.<String>emptyList()
        : Collections.unmodifiableList(rationale);
    this.conflicts = conflicts == null ? Collections.<Conflict>emptyList()
        : Collections.unmodifiableList(conflicts);
    this.estimatedBytes = estimatedBytes;
  }

  /** The resolved request (always present, even if a blocking conflict stops the build). */
  public ConversionRequest getRequest() { return request; }

  /** The fully-built plan, or {@code null} when {@link #hasBlockingConflict()} is true. */
  public ConversionPlan getPlan() { return plan; }

  public List<String> getRationale() { return rationale; }
  public List<Conflict> getConflicts() { return conflicts; }

  /** Estimated output size in bytes for the source duration, or 0 when unknown. */
  public long getEstimatedBytes() { return estimatedBytes; }

  public boolean isBuildable() { return plan != null; }

  public boolean hasBlockingConflict()
  {
    for (Conflict c : conflicts)
      if (c.isBlocking()) return true;
    return false;
  }

  /** The worst compatibility state across all conflicts. */
  public Conflict.Severity worstSeverity()
  {
    Conflict.Severity worst = Conflict.Severity.COMPATIBLE;
    for (Conflict c : conflicts)
      if (c.getSeverity().ordinal() > worst.ordinal()) worst = c.getSeverity();
    return worst;
  }
}
