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

/**
 * Optional external arbiter consulted by {@link GpuGovernor} once a session has
 * already cleared every <i>local</i> admission rule.
 *
 * <p>This is the seam that lets a deployment hand the "may a second enhanced
 * session run right now, and at what tier?" decision to something with a wider
 * view than one SageTV process &mdash; for example a machine-wide GPU arbiter that
 * also sees other tenants (LLM inference, batch encoders) sharing the same card.
 * It is deliberately <b>generic</b>: there is nothing here about HTTP, tokens,
 * lease generations or any particular arbiter. An implementation supplies all of
 * that privately and installs itself at startup with
 * {@link GpuGovernor#setExternalAuthority(ExternalAdmissionAuthority)}; the stock
 * build ships no implementation and therefore behaves exactly as before.
 *
 * <h2>Where it sits in the cascade</h2>
 * The authority is only ever asked about a tier that already passed, in order:
 * the {@link RecordingGuard} veto, feature/plumbing availability, the source
 * height floor, and the local physical budgets (disk write, measured VRAM,
 * video-engine pressure). Those remain <b>local and authoritative</b> &mdash; an
 * authority can only ever <i>narrow</i> the local answer, never widen it past
 * what the box can physically sustain, and it can never overrule the recording
 * veto. What it <i>replaces</i> is the local {@link CapacityCalibrator}
 * concurrency ceiling: cross-application concurrency is exactly the decision a
 * machine-wide arbiter is better placed to make than a single calibrated number.
 *
 * <h2>Fail-open contract</h2>
 * Live TV must keep working when the arbiter is unreachable. Any
 * {@link RuntimeException} thrown from {@link #admit}, or a {@link Decision}
 * marked {@link Decision#isDeferToLocal() defer-to-local}, is treated as "no
 * answer": the governor falls back to its ordinary local concurrency-ceiling
 * decision. Neither path is an external grant, so externally managed work
 * remains forbidden while an authority is installed. An explicit
 * {@link Decision#deny(String) deny} denies the whole request. An authority
 * that declines only its scoped external resource uses
 * {@link Decision#declineExternalResource(String)}, leaving the caller free to
 * choose a separately governed local fallback.
 *
 * <h2>Lifecycle mapping</h2>
 * <ul>
 *   <li>{@link #admit} &mdash; called once per session, synchronously, on the
 *       admission path. Must return promptly (an implementation talking to a
 *       remote arbiter is expected to apply its own short timeout and then
 *       defer-to-local rather than block a tune).</li>
 *   <li>{@link #renew} &mdash; called frequently from live transcode progress
 *       (potentially many times per second). Implementations MUST throttle
 *       internally and only do real work when a keep-alive is actually due.</li>
 *   <li>{@link #release} &mdash; called when the session ends, from every
 *       teardown path. Must be idempotent.</li>
 *   <li>{@link #isReclaimed} &mdash; polled to discover that the arbiter has
 *       revoked a still-running session's grant. Must be cheap and non-blocking
 *       (answer from cached state refreshed by {@link #renew}).</li>
 * </ul>
 *
 * <p>Every method must be safe to call concurrently and must never throw from
 * {@link #renew}, {@link #release} or {@link #isReclaimed} &mdash; those run on
 * hot paths where a throw would be worse than a stale answer.
 */
public interface ExternalAdmissionAuthority
{
  /** Short human-readable name for log/telemetry lines. Never null. */
  String name();

  /**
   * Arbitrate a locally-admissible session.
   *
   * @param sessionId      the governor's session id (stable for the session)
   * @param locallyGranted the highest tier the local cascade would grant with the
   *                       concurrency ceiling removed &mdash; i.e. the ceiling the
   *                       box can physically sustain. The authority may grant this
   *                       tier, a lower one, or deny; it must never be asked to
   *                       reason above this.
   * @param sourceHeight   source frame height, for telemetry/policy
   * @param estBitrateKbps estimated output bitrate, for telemetry/policy
   * @param offline        true for batch ({@code Ministry}) sessions
   * @return a {@link Decision}; return {@link Decision#deferToLocal(String)} (or
   *         throw) to fall back to the local ceiling
   */
  Decision admit(String sessionId, EnhancementTier locallyGranted,
                 int sourceHeight, long estBitrateKbps, boolean offline);

  /** Keep-alive for a granted session. Called often; throttle internally. Never throws. */
  void renew(String sessionId);

  /** Release a granted session. Idempotent. Never throws. */
  void release(String sessionId);

  /**
   * True when the arbiter has revoked this still-running session's grant. Cheap,
   * non-blocking, never throws. The stock governor exposes this via
   * {@link GpuGovernor#isReclaimed(String)} for a transcoder to poll.
   */
  boolean isReclaimed(String sessionId);

  /**
   * The outcome of {@link #admit}. Immutable. Four shapes:
   * <ul>
   *   <li>{@link #grant(EnhancementTier)} &mdash; run at this tier (clamped down
   *       locally if it somehow exceeds the physical budget).</li>
   *   <li>{@link #deny(String)} &mdash; deny the complete requested operation.</li>
   *   <li>{@link #declineExternalResource(String)} &mdash; decline only the
   *       externally governed resource; the caller may choose an independent
   *       local fallback, but must not execute the declined resource.</li>
   *   <li>{@link #deferToLocal(String)} &mdash; "no answer"; the governor uses its
   *       ordinary local concurrency-ceiling decision (the fail-open path).</li>
   * </ul>
   */
  final class Decision
  {
    private final EnhancementTier tier;   // granted tier; null unless GRANT
    private final boolean deny;
    private final boolean declineExternalResource;
    private final boolean deferToLocal;
    private final String reason;

    private Decision(EnhancementTier tier, boolean deny,
                     boolean declineExternalResource, boolean deferToLocal, String reason)
    {
      this.tier = tier;
      this.deny = deny;
      this.declineExternalResource = declineExternalResource;
      this.deferToLocal = deferToLocal;
      this.reason = reason == null ? "" : reason;
    }

    /** Grant the session at {@code tier} (must be an active tier). */
    public static Decision grant(EnhancementTier tier)
    {
      if (tier == null || !tier.isActive())
        return deny("authority granted no active tier");
      return new Decision(tier, false, false, false, "granted " + tier.token());
    }

    /**
     * Deny the complete requested operation.
     */
    public static Decision deny(String reason)
    {
      return new Decision(null, true, false, false, reason);
    }

    /**
     * Decline only the resource governed by this authority. This is not a grant;
     * the caller may independently select a locally governed fallback.
     */
    public static Decision declineExternalResource(String reason)
    {
      return new Decision(null, false, true, false, reason);
    }

    /** No answer &mdash; the governor falls back to its local ceiling (fail-open). */
    public static Decision deferToLocal(String reason)
    {
      return new Decision(null, false, false, true, reason);
    }

    public boolean isGranted()      { return tier != null && tier.isActive(); }
    public boolean isDeny()         { return deny; }
    public boolean isExternalResourceDeclined() { return declineExternalResource; }
    public boolean isDeferToLocal() { return deferToLocal; }
    /** Granted tier, or {@link EnhancementTier#NONE} when not a grant. */
    public EnhancementTier getTier() { return tier == null ? EnhancementTier.NONE : tier; }
    public String getReason()       { return reason; }

    @Override
    public String toString()
    {
      String kind = isGranted() ? ("grant " + tier.token())
          : (deferToLocal ? "defer-to-local"
          : (declineExternalResource ? "decline-external-resource" : "deny"));
      return "Decision[" + kind + (reason.isEmpty() ? "" : ": " + reason) + "]";
    }
  }
}
