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
import sage.client.ClientConstraints;
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

  /**
   * Phase interlock. The enhancement pipeline is not yet attached to the push
   * and pull-xcode transcode branches, so a tier can be decided but cannot
   * actually be applied to a stream.
   *
   * <p>Until that lands, clearing {@link #PROP_DRY_RUN} must not be enough to
   * leave dry-run, because the tier travels to the client in the
   * {@code CAP_EFFECTIVE_DELIVERY} token: the server would advertise
   * {@code enhance;tier=2160p} and then send an untouched stream. A protocol
   * that lies is worse than one that declines, and it would send anyone
   * debugging a client implementation chasing a difference that was never
   * produced.
   *
   * <p>Flip this to true in the same change that wires the pipeline.
   */
  static final boolean PIPELINE_WIRED = false;

  private static volatile boolean interlockLogged = false;

  private EnhancementDryRun() {}

  /** True when the decision must be observed only, never applied. */
  public static boolean isDryRun()
  {
    boolean propDry = Sage.getBoolean(PROP_DRY_RUN, true);
    if (!PIPELINE_WIRED)
    {
      // Say so rather than silently ignoring the admin's setting -- an override
      // that explains itself once is the difference between "this feature is
      // staged" and an afternoon lost to "I turned it on and nothing happened".
      if (!propDry && !interlockLogged)
      {
        interlockLogged = true;
        System.out.println("GPU_ENHANCE INTERLOCK " + PROP_DRY_RUN
            + " is false, but the enhancement pipeline is not wired to the"
            + " transcode branches yet, so dry-run stays on. Decisions are"
            + " logged and no stream is modified or advertised as enhanced.");
      }
      return true;
    }
    return propDry;
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
    return evaluateAndLog(clientId, mediaDesc, sourceWidth, sourceHeight, interlaced,
        sourceFps, sinkWidth, sinkHeight, surface, null, null, localPref, localStatus,
        gpuSupported);
  }

  public static EnhancementTier evaluateAndLog(String clientId, String mediaDesc,
      int sourceWidth, int sourceHeight, boolean interlaced, int sourceFps,
      int sinkWidth, int sinkHeight, PlaybackSurface surface,
      ClientConstraints constraints, String formFactor,
      String localPref, String localStatus, boolean gpuSupported)
  {
    if (!EnhancementAdvisor.isEnabled()) return EnhancementTier.NONE;

    EnhancementAdvisor.Advice advice = EnhancementAdvisor.advise(sourceWidth, sourceHeight,
        interlaced, sourceFps, sinkWidth, sinkHeight, surface, constraints, formFactor,
        localPref, localStatus, gpuSupported);

    boolean dry = isDryRun();
    if (Sage.DBG)
    {
      // formFactor is logged even when PROP_FORM_FACTORS is open (the default):
      // whether a tablet-class panel is WORTH a GPU session is a judgement call,
      // and this is the evidence needed to settle it from real traffic instead
      // of by assertion.
      System.out.println("GPU_ENHANCE " + (dry ? "DRYRUN" : "LIVE")
          + " client=" + safe(clientId)
          + " media=" + safe(mediaDesc)
          + " src=" + sourceWidth + "x" + sourceHeight + (interlaced ? "i" : "p")
          + "@" + sourceFps
          + " sink=" + sinkWidth + "x" + sinkHeight
          + " form=" + safe(formFactor)
          + " surface=" + (surface == null ? "none" : surface.getId())
          + " surfaceMax=" + (surface == null ? "n/a"
              : (surface.getMaxOutputWidth() + "x" + surface.getMaxOutputHeight()
                 + "@" + surface.getMaxFps()))
          + " codecMax=" + describeCodecCeiling(constraints)
          + " local=" + safe(localPref) + "/" + safe(localStatus)
          + " -> tier=" + advice.getTier().token()
          + " verdict=" + advice.getVerdict()
          + " (" + advice.getVerdict().getDescription() + ")");
    }

    return dry ? EnhancementTier.NONE : advice.getTier();
  }

  /**
   * Summarise the per-codec decode ceilings for the log line. "none" here is
   * the difference between "the client refused" and "the client never told us",
   * which are very different bugs to chase.
   */
  private static String describeCodecCeiling(ClientConstraints constraints)
  {
    if (constraints == null) return "n/a";
    if (!constraints.hasAnyDeclaredOutputLimits()) return "undeclared";
    StringBuilder sb = new StringBuilder();
    for (ClientConstraints.VideoConstraint vc : constraints.getVideoConstraints())
    {
      if (!vc.hasDeclaredOutputLimits()) continue;
      if (sb.length() > 0) sb.append(',');
      sb.append(vc.codec).append('=').append(vc.maxWidth).append('x')
        .append(vc.maxHeight);
      if (vc.decoder != ClientConstraints.Decoder.HW) sb.append("(sw)");
    }
    return sb.length() == 0 ? "undeclared" : sb.toString();
  }

  private static String safe(String s)
  {
    if (s == null || s.length() == 0) return "-";
    return s.replace(' ', '_');
  }
}
