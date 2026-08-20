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
 * Decides whether server-side enhancement would actually <b>help</b> this
 * stream, and if so how much.
 *
 * <p>This is the benefit gate, and it is deliberately separate from
 * {@link GpuGovernor}, which is the capacity gate. The two answer genuinely
 * different questions and conflating them is how this kind of feature goes
 * wrong: "we have a spare NVENC session" is not a reason to re-encode a stream
 * that was already going to look fine. The advisor runs first and usually says
 * no; only when it finds a real, visible win does the governor get asked
 * whether the machine can afford it.
 *
 * <p>Every gate here fails closed. Unknown client capability, unknown source
 * geometry, or a missing GPU filter all produce {@link EnhancementTier#NONE},
 * which is byte-identical to today's behavior.
 */
public final class EnhancementAdvisor
{
  /** Master switch. Off by default -- nothing enhances until an admin opts in. */
  public static final String PROP_ENABLED = "playback/gpu_enhance/enabled";
  /** Admin ceiling on output height, e.g. 1440 to forbid 4K entirely. */
  public static final String PROP_MAX_HEIGHT = "playback/gpu_enhance/max_height";
  /**
   * How much bigger the sink must be than the source before upscaling is
   * considered worthwhile, in tenths. Default 15 = 1.5x.
   */
  public static final String PROP_MIN_GAIN_TENTHS = "playback/gpu_enhance/min_gain_tenths";
  /** Whether to enhance for clients whose own upscaler is already running. */
  public static final String PROP_OVERRIDE_LOCAL = "playback/gpu_enhance/override_local_upscaler";

  private static final int DEFAULT_MIN_GAIN_TENTHS = 15;

  /** Why enhancement was or wasn't offered. Surfaced in logs and telemetry. */
  public enum Verdict
  {
    OFFERED("offered"),
    DISABLED("feature disabled"),
    NO_GPU_SUPPORT("ffmpeg/GPU cannot run the pipeline"),
    UNKNOWN_SOURCE("source geometry unknown"),
    UNKNOWN_SINK("client did not report a sink resolution"),
    SOURCE_BELOW_FLOOR("source below the 720-line floor and not interlaced"),
    NO_VISIBLE_GAIN("sink is not meaningfully larger than the source"),
    SURFACE_CANNOT_DECODE("client surface cannot decode the enhanced output"),
    CLIENT_UPSCALES_LOCALLY("client's own upscaler is active and preferred"),
    CLIENT_PREFERS_LOCAL("client explicitly prefers local enhancement");

    private final String description;
    private Verdict(String d) { this.description = d; }
    public String getDescription() { return description; }
    public boolean isOffered() { return this == OFFERED; }
  }

  /** The advisor's answer: a tier plus the reason it landed there. */
  public static final class Advice
  {
    private final EnhancementTier tier;
    private final Verdict verdict;

    Advice(EnhancementTier tier, Verdict verdict)
    {
      this.tier = tier == null ? EnhancementTier.NONE : tier;
      this.verdict = verdict;
    }

    public EnhancementTier getTier() { return tier; }
    public Verdict getVerdict() { return verdict; }
    public boolean isOffered() { return tier.isActive() && verdict.isOffered(); }

    @Override
    public String toString()
    {
      return "Advice[tier=" + tier.token() + " verdict=" + verdict
          + " (" + verdict.getDescription() + ")]";
    }
  }

  private static final Advice NONE_DISABLED = new Advice(EnhancementTier.NONE, Verdict.DISABLED);

  private EnhancementAdvisor() {}

  public static boolean isEnabled()
  {
    return Sage.getBoolean(PROP_ENABLED, false);
  }

  /**
   * Evaluate a candidate stream.
   *
   * @param sourceWidth      source frame width in pixels; 0 if unknown.
   * @param sourceHeight     source frame HEIGHT in pixels (full frame height for
   *                         interlaced content, so 1080i is 1080); 0 if unknown.
   * @param sourceInterlaced true when the source is interlaced.
   * @param sourceFps        source frame rate, rounded; 0 if unknown.
   * @param sinkWidth        physical display width; 0 when the client didn't say.
   * @param sinkHeight       physical display height; 0 when the client didn't say.
   * @param surface          the winning playback surface, or null if unknown.
   * @param localPref        {@code auto|local|server} from LOCAL_ENHANCEMENT.
   * @param localStatus      {@code active|available|none} from LOCAL_ENHANCEMENT.
   * @param gpuSupported     result of {@code HwEncoder.gpuEnhanceSupported()}.
   */
  public static Advice advise(int sourceWidth, int sourceHeight, boolean sourceInterlaced,
      int sourceFps, int sinkWidth, int sinkHeight, PlaybackSurface surface,
      String localPref, String localStatus, boolean gpuSupported)
  {
    if (!isEnabled()) return NONE_DISABLED;
    if (!gpuSupported) return new Advice(EnhancementTier.NONE, Verdict.NO_GPU_SUPPORT);
    if (sourceHeight <= 0) return new Advice(EnhancementTier.NONE, Verdict.UNKNOWN_SOURCE);

    // The client's own opinion comes first: never spend a GPU session fighting
    // an upscaler the device is already running well. A Shield doing its own AI
    // upscale of a pristine remux beats a server upscale of a re-encode.
    if ("local".equals(localPref))
      return new Advice(EnhancementTier.NONE, Verdict.CLIENT_PREFERS_LOCAL);
    boolean overrideLocal = Sage.getBoolean(PROP_OVERRIDE_LOCAL, false);
    if (!overrideLocal && "active".equals(localStatus) && !"server".equals(localPref))
      return new Advice(EnhancementTier.NONE, Verdict.CLIENT_UPSCALES_LOCALLY);

    // Deinterlacing is judged on its own terms. It is not upscaling, it is
    // exempt from the source floor, and for interlaced content it is most of
    // the visible win at a fraction of the cost.
    EnhancementTier deintFloor = sourceInterlaced
        ? EnhancementTier.DEINTERLACE_ONLY : EnhancementTier.NONE;

    if (sinkHeight <= 0 || sinkWidth <= 0)
      return finish(deintFloor, Verdict.UNKNOWN_SINK, surface, deintFloor, sourceFps);

    if (sourceHeight < EnhancementTier.SOURCE_HEIGHT_FLOOR)
      return finish(deintFloor, Verdict.SOURCE_BELOW_FLOOR, surface, deintFloor, sourceFps);

    // Require a real size gain before re-encoding anything. A 1080i source on a
    // 1080p panel gets deinterlaced, not "upscaled" to the size it already is.
    int minGainTenths = Sage.getInt(PROP_MIN_GAIN_TENTHS, DEFAULT_MIN_GAIN_TENTHS);
    if (minGainTenths < 10) minGainTenths = 10;
    if ((long) sinkHeight * 10L < (long) sourceHeight * (long) minGainTenths)
      return finish(deintFloor, Verdict.NO_VISIBLE_GAIN, surface, deintFloor, sourceFps);

    // Never build a picture larger than the panel can show, then apply the
    // admin ceiling on top.
    EnhancementTier tier = EnhancementTier.clampToHeight(EnhancementTier.ENHANCE_2160P, sinkHeight);
    int adminMax = Sage.getInt(PROP_MAX_HEIGHT, 2160);
    if (adminMax > 0) tier = EnhancementTier.clampToHeight(tier, adminMax);

    // Clamping can land below the source; at that point there is nothing to
    // gain by scaling, so fall back to the deinterlace question.
    if (tier.isUpscaling() && tier.getTargetHeight() <= sourceHeight)
      return finish(deintFloor, Verdict.NO_VISIBLE_GAIN, surface, deintFloor, sourceFps);

    if (!tier.isLegalForSourceHeight(sourceHeight))
      return finish(deintFloor, Verdict.SOURCE_BELOW_FLOOR, surface, deintFloor, sourceFps);

    return finish(tier, Verdict.OFFERED, surface, deintFloor, sourceFps);
  }

  /**
   * Apply the surface decode gate and settle the final verdict. Kept in one
   * place so every exit path is forced through the same "can the client
   * actually play this?" question -- the check most likely to be forgotten on
   * one branch and produce an unplayable stream.
   */
  private static Advice finish(EnhancementTier tier, Verdict verdict, PlaybackSurface surface,
      EnhancementTier fallback, int sourceFps)
  {
    if (!tier.isActive()) return new Advice(EnhancementTier.NONE, verdict);

    // A non-upscaling tier is never subject to the output gate. Deinterlacing
    // emits exactly the geometry the client was already going to decode, so
    // testing it against a decode ceiling is meaningless -- and testing it
    // against an UNDECLARED ceiling would wrongly refuse the cheapest and most
    // broadly applicable win the feature has.
    if (!tier.isUpscaling()) return new Advice(tier, verdict);

    EnhancementTier t = tier;
    while (t.isUpscaling())
    {
      if (surface == null || surface.canOutput(t.getTargetWidth(), t.getTargetHeight(), sourceFps))
        return new Advice(t, verdict);
      t = t.downgrade();
    }
    // Every upscale tier was refused. Fall back only to what the caller said is
    // legal for this source -- walking the ladder down to DEINTERLACE_ONLY would
    // otherwise deinterlace progressive content, which is both wrong and costly.
    if (fallback.isActive()) return new Advice(fallback, verdict);
    return new Advice(EnhancementTier.NONE, Verdict.SURFACE_CANNOT_DECODE);
  }

  /**
   * True when the source is a legal candidate for any live enhancement at all.
   * Cheap pre-check for callers that want to skip the full evaluation.
   */
  public static boolean isCandidateSource(int sourceHeight, boolean interlaced)
  {
    if (sourceHeight <= 0) return false;
    return interlaced || sourceHeight >= EnhancementTier.SOURCE_HEIGHT_FLOOR;
  }
}
