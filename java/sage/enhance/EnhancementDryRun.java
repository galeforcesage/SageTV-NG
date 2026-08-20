/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package sage.enhance;

import sage.Sage;
import sage.client.PlaybackSurface;

/**
 * Runs the full enhancement decision and <b>logs what it would have done</b>
 * without doing any of it.
 *
 * <p>This exists because the decision depends on things no unit test can
 * supply honestly: what real clients actually report for
 * {@code DISPLAY_SINK_RESOLUTION}, what real broadcast sources actually look
 * like, and how often the recording guard would veto in practice. Shipping the
 * decision logic in observe-only mode first means the rollout can be validated
 * against a week of real tunes before a single stream is re-encoded.
 *
 * <p>Dry-run is the default even when {@code playback/gpu_enhance/enabled} is
 * true. Enhancement only actually runs once an admin also clears
 * {@code playback/gpu_enhance/dry_run}. Two switches, because the failure mode
 * of accidentally enabling this on a recording server is worse than the cost of
 * an extra opt-in.
 */
public final class EnhancementDryRun
{
  /** When true (the default), decisions are logged but never acted upon. */
  public static final String PROP_DRY_RUN = "playback/gpu_enhance/dry_run";

  private EnhancementDryRun() {}

  /** True when the decision must be observed only, never applied. */
  public static boolean isDryRun()
  {
    return Sage.getBoolean(PROP_DRY_RUN, true);
  }

  /**
   * True when enhancement is permitted to actually modify a stream: the feature
   * is on AND dry-run has been explicitly cleared.
   */
  public static boolean isLive()
  {
    return EnhancementAdvisor.isEnabled() && !isDryRun();
  }

  /**
   * Evaluate and log one candidate stream.
   *
   * @return the advised tier when enhancement is live, or
   *         {@link EnhancementTier#NONE} in dry-run mode. Callers can use the
   *         return value unconditionally; dry-run enforcement happens here so
   *         no caller can forget it.
   */
  public static EnhancementTier evaluateAndLog(String clientId, String mediaDesc,
      int sourceWidth, int sourceHeight, boolean interlaced, int sourceFps,
      int sinkWidth, int sinkHeight, PlaybackSurface surface,
      String localPref, String localStatus, boolean gpuSupported)
  {
    if (!EnhancementAdvisor.isEnabled()) return EnhancementTier.NONE;

    EnhancementAdvisor.Advice advice = EnhancementAdvisor.advise(sourceWidth, sourceHeight,
        interlaced, sourceFps, sinkWidth, sinkHeight, surface, localPref, localStatus,
        gpuSupported);

    boolean dry = isDryRun();
    if (Sage.DBG)
    {
      System.out.println("GPU_ENHANCE " + (dry ? "DRYRUN" : "LIVE")
          + " client=" + safe(clientId)
          + " media=" + safe(mediaDesc)
          + " src=" + sourceWidth + "x" + sourceHeight + (interlaced ? "i" : "p")
          + "@" + sourceFps
          + " sink=" + sinkWidth + "x" + sinkHeight
          + " surface=" + (surface == null ? "none" : surface.getId())
          + " surfaceMax=" + (surface == null ? "n/a"
              : (surface.getMaxOutputWidth() + "x" + surface.getMaxOutputHeight()
                 + "@" + surface.getMaxFps()))
          + " local=" + safe(localPref) + "/" + safe(localStatus)
          + " -> tier=" + advice.getTier().token()
          + " verdict=" + advice.getVerdict()
          + " (" + advice.getVerdict().getDescription() + ")");
    }

    return dry ? EnhancementTier.NONE : advice.getTier();
  }

  private static String safe(String s)
  {
    if (s == null || s.length() == 0) return "-";
    return s.replace(' ', '_');
  }
}
