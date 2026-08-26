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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure policy layer over a set of derivatives for a single source recording:
 * reuse matching, original-recording safety, and space-pressure reclamation.
 * Stateless and side-effect-free — callers apply the returned decisions against
 * the real store/filesystem. This keeps the rules unit-testable and centralized.
 */
public final class DerivativePolicy
{
  private DerivativePolicy() { }

  /**
   * Find an existing, validated derivative that satisfies {@code want}, so a new
   * job can be skipped. Only {@link DerivativeState#READY} derivatives are
   * reusable; a preferred match wins, otherwise the most recently created.
   * Returns {@code null} when nothing matches.
   */
  public static DerivativeRecord findReusable(DerivativeSpec want, List<DerivativeRecord> existing)
  {
    if (want == null || existing == null) return null;
    DerivativeRecord best = null;
    for (DerivativeRecord d : existing)
    {
      if (d == null || !d.isOfflineReady()) continue;
      if (!want.matches(d)) continue;
      if (best == null
          || (d.isPreferred() && !best.isPreferred())
          || (d.isPreferred() == best.isPreferred()
              && d.getCreatedTimeMillis() > best.getCreatedTimeMillis()))
        best = d;
    }
    return best;
  }

  /**
   * Whether the source recording may be safely deleted after conversion.
   * Original-recording safety is conservative by default:
   * <ul>
   *   <li>never when the user did not opt in to deleting the source;</li>
   *   <li>never unless at least one {@link DerivativeState#READY} derivative
   *       covers the source (validated, non-truncated output exists);</li>
   *   <li>a lossy derivative (re-encode / downscale / SDR tone-map) requires
   *       explicit review confirmation — only an {@link ConversionPurpose#EXACT_BACKUP}
   *       or lossless copy may delete the source automatically.</li>
   * </ul>
   */
  public static boolean canDeleteSource(boolean userRequestedDelete, boolean reviewConfirmed,
      List<DerivativeRecord> derivatives)
  {
    if (!userRequestedDelete) return false;
    if (derivatives == null || derivatives.isEmpty()) return false;
    boolean anyReady = false;
    boolean anyLossless = false;
    for (DerivativeRecord d : derivatives)
    {
      if (d == null || !d.isOfflineReady()) continue;
      anyReady = true;
      if (d.getPurpose() == ConversionPurpose.EXACT_BACKUP) anyLossless = true;
    }
    if (!anyReady) return false;
    // A validated exact backup is a safe delete; any other (lossy) derivative
    // requires the review step to have confirmed the trade-off.
    return anyLossless || reviewConfirmed;
  }

  /**
   * Order derivatives for reclamation under space pressure, most-reclaimable
   * first. {@code sourceExists=false} means the source recording is gone, which
   * makes {@link RetentionPolicy#UNTIL_SOURCE_DELETED} derivatives reclaimable.
   * {@link RetentionPolicy#KEEP_FOREVER} and the preferred derivative are never
   * returned. Ties break by oldest-first.
   */
  public static List<DerivativeRecord> reclaimOrder(List<DerivativeRecord> derivatives, boolean sourceExists)
  {
    List<DerivativeRecord> out = new ArrayList<DerivativeRecord>();
    if (derivatives == null) return out;
    for (DerivativeRecord d : derivatives)
    {
      if (d == null) continue;
      if (d.isPreferred()) continue;
      if (d.getRetention() == RetentionPolicy.KEEP_FOREVER) continue;
      if (d.getRetention() == RetentionPolicy.UNTIL_SOURCE_DELETED && sourceExists) continue;
      out.add(d);
    }
    out.sort(new Comparator<DerivativeRecord>()
    {
      public int compare(DerivativeRecord a, DerivativeRecord b)
      {
        int ra = rank(a.getRetention());
        int rb = rank(b.getRetention());
        if (ra != rb) return Integer.compare(ra, rb);
        return Long.compare(a.getCreatedTimeMillis(), b.getCreatedTimeMillis());
      }
      private int rank(RetentionPolicy p)
      {
        // TEMPORARY is the most reclaimable, then UNTIL_SOURCE_DELETED.
        return p == RetentionPolicy.TEMPORARY ? 0 : 1;
      }
    });
    return out;
  }
}
