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
  /**
   * CSV of {@code DEVICE_FORM_FACTOR} values eligible for upscaling, e.g.
   * {@code "tv"}. Empty (the default) means every form factor is eligible.
   *
   * <p>A GPU session spent on a 14.6" tablet held at arm's length buys far less
   * than the same session spent on a 65" panel across a room, and the two
   * compete for the same encoder. But "how much less" depends on the room, and
   * the honest way to settle it is the dry-run log -- which now records the form
   * factor -- rather than a guess baked into the default. So this ships open,
   * and an admin who has looked at their own traffic can close it.
   *
   * <p>A client that never reported a form factor is never excluded by this
   * gate: it is a benefit heuristic, not a safety gate, and silently refusing
   * every legacy client would be a regression rather than a policy.
   */
  public static final String PROP_FORM_FACTORS = "playback/gpu_enhance/upscale_form_factors";
  /**
   * Largest sink, as {@code WxH}, still assumed to be a device's own built-in
   * panel. Anything bigger is taken to be an external display.
   *
   * <p>Only consulted when {@link #PROP_FORM_FACTORS} has been narrowed, and
   * only as a fallback for clients that don't report
   * {@code DISPLAY_SINK_IS_EXTERNAL}. The default sits above every shipping
   * handheld panel (the largest tablets are ~2960x1848, phones ~3200x1440) and
   * below 4K, so a 3840x2160 sink attached to a phone reads as what it is: a
   * television.
   */
  public static final String PROP_BUILTIN_PANEL_MAX = "playback/gpu_enhance/builtin_panel_max";
  /**
   * What an absent {@code DISPLAY_SINK_RESOLUTION} means: {@code "infer"} (the
   * default) or {@code "refuse"}.
   *
   * <p>A value the client never sent is an ABSTENTION, not a refusal. It says
   * "I have no opinion -- serve me as well as you can", and answering it with a
   * flat no hands the decision to the least informed party in the exchange. So
   * the default is to decide from what the client did affirmatively state.
   *
   * <p>This is much safer than it sounds, because it does not fabricate a sink.
   * The sink clamp is simply skipped, and the tier is then settled by rules that
   * are all independently fail-closed:
   *
   * <ul>
   * <li>the decode gate in {@code finish()}, which requires the client to have
   *     PROVED it can decode the output. A client that declared no ceiling gets
   *     nothing, so every legacy client lands exactly where it does today.</li>
   * <li>the 720-line source floor, and the rule that the target must exceed the
   *     source.</li>
   * <li>the admin ceiling {@link #PROP_MAX_HEIGHT}, and {@link #PROP_FORM_FACTORS}
   *     if it has been narrowed.</li>
   * <li>network headroom and GPU admission downstream, which override any of
   *     this unconditionally -- a stream that cannot cross the link is not a
   *     better picture.</li>
   * </ul>
   *
   * <p>What is lost without a sink is only the panel clamp, so the failure mode
   * is sending 2160p to a 4K-capable decoder attached to a smaller panel: wasted
   * bandwidth, not a broken stream. Set {@code refuse} to restore the older
   * behaviour of treating silence as no.
   */
  public static final String PROP_UNKNOWN_SINK = "playback/gpu_enhance/unknown_sink";

  private static final int DEFAULT_BUILTIN_PANEL_MAX_W = 3200;
  private static final int DEFAULT_BUILTIN_PANEL_MAX_H = 1920;

  private static final int DEFAULT_MIN_GAIN_TENTHS = 15;

  /** Why enhancement was or wasn't offered. Surfaced in logs and telemetry. */
  public enum Verdict
  {
    OFFERED("offered"),
    DISABLED("feature disabled"),
    NO_GPU_SUPPORT("ffmpeg/GPU cannot run the pipeline"),
    UNKNOWN_SOURCE("source geometry unknown"),
    UNKNOWN_SINK("client reported no sink and policy is to refuse rather than infer"),
    SOURCE_BELOW_FLOOR("source below the 720-line floor and not interlaced"),
    NO_VISIBLE_GAIN("sink is not meaningfully larger than the source"),
    FORM_FACTOR_EXCLUDED("device form factor is not in the upscale-eligible set"),
    SURFACE_CANNOT_DECODE("client cannot decode the enhanced output (no surface or codec proved it)"),
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
    return advise(sourceWidth, sourceHeight, sourceInterlaced, sourceFps,
        sinkWidth, sinkHeight, surface, null, localPref, localStatus, gpuSupported);
  }

  /**
   * As above, with the active player's per-codec decode ceilings.
   *
   * @param constraints the {@code EXO_/IJK_VIDEO_*} rows for the player that
   *                    will actually decode, or null when unknown. Supplies the
   *                    per-codec form of the output gate.
   */
  public static Advice advise(int sourceWidth, int sourceHeight, boolean sourceInterlaced,
      int sourceFps, int sinkWidth, int sinkHeight, PlaybackSurface surface,
      ClientConstraints constraints,
      String localPref, String localStatus, boolean gpuSupported)
  {
    return advise(sourceWidth, sourceHeight, sourceInterlaced, sourceFps, sinkWidth,
        sinkHeight, surface, constraints, null, localPref, localStatus, gpuSupported);
  }

  /**
   * As above, with the client's declared {@code DEVICE_FORM_FACTOR}.
   *
   * @param formFactor {@code TV} / {@code TABLET} / {@code PHONE} / etc., or
   *                   null when the client didn't say. Only consulted when an
   *                   admin has narrowed {@link #PROP_FORM_FACTORS}.
   */
  public static Advice advise(int sourceWidth, int sourceHeight, boolean sourceInterlaced,
      int sourceFps, int sinkWidth, int sinkHeight, PlaybackSurface surface,
      ClientConstraints constraints, String formFactor,
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

    // An absent sink is an abstention, not a refusal: the client has expressed
    // no opinion, so the server decides from what it did state. Refusing here
    // would let silence -- the least informative thing a client can do -- veto a
    // decision the server is better placed to make. See PROP_UNKNOWN_SINK: the
    // sink clamp is skipped, nothing is fabricated, and the decode gate still
    // requires the client to have proved it can play the result.
    boolean sinkKnown = sinkWidth > 0 && sinkHeight > 0;
    if (!sinkKnown && !inferOnUnknownSink())
      return finish(deintFloor, Verdict.UNKNOWN_SINK, surface, constraints, deintFloor,
          sourceFps);

    if (sourceHeight < EnhancementTier.SOURCE_HEIGHT_FLOOR)
      return finish(deintFloor, Verdict.SOURCE_BELOW_FLOOR, surface, constraints, deintFloor,
          sourceFps);

    // Upscaling only. A handheld panel still benefits from deinterlacing, and
    // that costs a fraction of an upscale, so an excluded form factor drops to
    // the deinterlace question rather than being refused outright.
    if (!isFormFactorEligible(formFactor, sinkWidth, sinkHeight))
      return finish(deintFloor, Verdict.FORM_FACTOR_EXCLUDED, surface, constraints, deintFloor,
          sourceFps);

    // Require a real size gain before re-encoding anything. A 1080i source on a
    // 1080p panel gets deinterlaced, not "upscaled" to the size it already is.
    // Only meaningful when a panel was actually reported; without one the
    // equivalent test is the target-vs-source check below.
    if (sinkKnown)
    {
      int minGainTenths = Sage.getInt(PROP_MIN_GAIN_TENTHS, DEFAULT_MIN_GAIN_TENTHS);
      if (minGainTenths < 10) minGainTenths = 10;
      if ((long) sinkHeight * 10L < (long) sourceHeight * (long) minGainTenths)
        return finish(deintFloor, Verdict.NO_VISIBLE_GAIN, surface, constraints, deintFloor,
            sourceFps);
    }

    // Never build a picture larger than the panel can show -- in EITHER
    // dimension -- then apply the admin ceiling on top. With no panel reported
    // there is nothing to clamp against, so the admin ceiling and the decode
    // gate carry the whole load.
    EnhancementTier tier = EnhancementTier.ENHANCE_2160P;
    if (sinkKnown) tier = EnhancementTier.clampToSink(tier, sinkWidth, sinkHeight);
    int adminMax = Sage.getInt(PROP_MAX_HEIGHT, 2160);
    if (adminMax > 0) tier = EnhancementTier.clampToHeight(tier, adminMax);

    // Clamping can land below the source; at that point there is nothing to
    // gain by scaling, so fall back to the deinterlace question.
    if (tier.isUpscaling() && tier.getTargetHeight() <= sourceHeight)
      return finish(deintFloor, Verdict.NO_VISIBLE_GAIN, surface, constraints, deintFloor,
          sourceFps);

    if (!tier.isLegalForSourceHeight(sourceHeight))
      return finish(deintFloor, Verdict.SOURCE_BELOW_FLOOR, surface, constraints, deintFloor,
          sourceFps);

    return finish(tier, Verdict.OFFERED, surface, constraints, deintFloor, sourceFps);
  }

  /**
   * Apply the decode gate and settle the final verdict. Kept in one place so
   * every exit path is forced through the same "can the client actually play
   * this?" question -- the check most likely to be forgotten on one branch and
   * produce an unplayable stream.
   *
   * <p>Two independent sources can answer it, and either one suffices:
   * the winning playback surface's declared output limits, or the active
   * player's per-codec decoder ceilings. Clients report decoder limits per codec
   * on both Android and the web, so requiring the surface form would exclude
   * clients that have already told us everything we need.
   *
   * <p>If NEITHER source declared a limit, the answer is no. Silence is not
   * consent: the whole point of this gate is that listing a codec says nothing
   * about the resolution its decoder was built for.
   *
   * <p>Note there is deliberately no user override here. The Android client
   * separates the two questions: the per-codec rows report what MediaCodec can
   * actually decode and are sent unconditionally, while the user's Auto /
   * Always / Never setting only ever moves the SINK. So a decode refusal is
   * always a hardware fact, never a preference, and nothing the user can toggle
   * should be able to talk us past it.
   */
  private static Advice finish(EnhancementTier tier, Verdict verdict, PlaybackSurface surface,
      ClientConstraints constraints, EnhancementTier fallback, int sourceFps)
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
      if (canDecode(surface, constraints, t.getTargetWidth(), t.getTargetHeight(), sourceFps))
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
   * True when this device's form factor may receive an upscale.
   *
   * <p>Open by default. When an admin narrows {@link #PROP_FORM_FACTORS}, a
   * client that declared a form factor outside the list is excluded -- but a
   * client that declared nothing is still allowed through, because this is a
   * judgement about where a GPU session is best spent, not a safety gate, and
   * excluding every client that predates the field would be a regression.
   *
   * <p>Form factor describes the DEVICE; the enhancement question is about the
   * DISPLAY. Those are the same thing right up until someone puts a phone in
   * desktop mode and plugs it into a television, at which point
   * {@code DEVICE_FORM_FACTOR=PHONE} is driving a 65" 4K panel -- the case that
   * benefits most, refused by the crudest possible reading of the field. The
   * Android client reports the panel it is actually attached to, so a docked
   * phone sends the TV's geometry; a sink too large to be anything the device
   * could have shipped with is therefore taken as an external display and
   * exempted from the list.
   */
  /**
   * Whether an absent sink is treated as an abstention (decide from what the
   * client did state) or as a refusal. See {@link #PROP_UNKNOWN_SINK}.
   */
  static boolean inferOnUnknownSink()
  {
    String mode = Sage.get(PROP_UNKNOWN_SINK, "infer");
    return mode == null || !"refuse".equalsIgnoreCase(mode.trim());
  }

  static boolean isFormFactorEligible(String formFactor, int sinkWidth, int sinkHeight)
  {
    String allowed = Sage.get(PROP_FORM_FACTORS, "");
    if (allowed == null) return true;
    allowed = allowed.trim();
    if (allowed.length() == 0) return true;
    if (formFactor == null || formFactor.trim().length() == 0) return true;

    String mine = formFactor.trim().toLowerCase();
    String[] parts = allowed.toLowerCase().split(",");
    for (int i = 0; i < parts.length; i++)
      if (mine.equals(parts[i].trim())) return true;

    // Not in the list -- but the device may be driving something that is.
    return isSinkExternal(sinkWidth, sinkHeight);
  }

  /**
   * Whether the reported sink is a screen worth upscaling for, rather than the
   * device's own built-in panel.
   *
   * <p>Inferred from size alone, because the client sends no "this is external"
   * flag: the Android override moves the sink itself rather than adding a
   * field, so a docked phone simply reports the television's geometry. The
   * default ceiling sits above every shipping handheld panel and below 4K.
   */
  static boolean isSinkExternal(int sinkWidth, int sinkHeight)
  {
    int maxW = DEFAULT_BUILTIN_PANEL_MAX_W;
    int maxH = DEFAULT_BUILTIN_PANEL_MAX_H;
    String cfg = Sage.get(PROP_BUILTIN_PANEL_MAX, "");
    if (cfg != null)
    {
      String s = cfg.trim();
      int x = s.indexOf('x');
      if (x < 0) x = s.indexOf('X');
      if (x > 0 && x < s.length() - 1)
      {
        try
        {
          int w = Integer.parseInt(s.substring(0, x).trim());
          int h = Integer.parseInt(s.substring(x + 1).trim());
          if (w > 0 && h > 0) { maxW = w; maxH = h; }
        }
        catch (NumberFormatException e) { /* keep the defaults */ }
      }
    }
    // Either dimension is enough: a sink wider or taller than any built-in
    // panel ships is attached, not integrated.
    return sinkWidth > maxW || sinkHeight > maxH;
  }

  /**
   * True when either declared capability source proves this geometry is
   * decodable. Fail-closed when neither declared anything.
   */
  static boolean canDecode(PlaybackSurface surface, ClientConstraints constraints,
      int width, int height, int fps)
  {
    if (surface != null && surface.canOutput(width, height, fps)) return true;
    if (constraints != null && constraints.canDecodeAny(width, height, fps)) return true;
    return false;
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
