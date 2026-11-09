package sage.party;

import java.util.Collection;

/**
 * Coordinates authoritative Party transitions without transport or player dependencies.
 */
public final class PartySessionCoordinator
{
  private final long minimumLeadMs;
  private final long maximumLeadMs;

  /**
   * Creates a coordinator with bounded command scheduling policy.
   *
   * @param minimumLeadMs minimum future command lead
   * @param maximumLeadMs maximum future command lead
   */
  public PartySessionCoordinator(long minimumLeadMs, long maximumLeadMs)
  {
    if (minimumLeadMs < 0 || maximumLeadMs < minimumLeadMs)
      throw new IllegalArgumentException("command lead bounds");
    this.minimumLeadMs = minimumLeadMs;
    this.maximumLeadMs = maximumLeadMs;
  }

  /**
   * Calculates one command lead from ready participant timing only.
   *
   * @param participants participant timing snapshots
   * @return bounded command lead in milliseconds
   */
  public long commandLead(Collection<ParticipantTiming> participants)
  {
    long lead = minimumLeadMs;
    for (ParticipantTiming participant : participants)
      if (participant.ready())
        lead = Math.max(lead, Math.max(
            4 * participant.p95RttMs() + 250,
            participant.presentationLatencyMs() + participant.presentationUncertaintyMs() + 300));
    return Math.min(maximumLeadMs, lead);
  }

  /**
   * Schedules an authoritative transition.
   *
   * @param timeline canonical timeline
   * @param cause server-defined transition cause
   * @param targetPositionMs media position at the execution deadline
   * @param targetState target playback state
   * @param serverMonoMs current server-monotonic time
   * @param originParticipantId initiating participant
   * @param participants participant timing snapshots
   * @return immutable scheduled command
   */
  public PartyCommand schedule(PartyTimeline timeline, String cause, long targetPositionMs,
      String targetState, long serverMonoMs, String originParticipantId,
      Collection<ParticipantTiming> participants)
  {
    return timeline.schedule(cause, targetPositionMs, targetState, 1,
        serverMonoMs + commandLead(participants), originParticipantId);
  }

  /**
   * Immutable participant timing input for command scheduling.
   *
   * @param ready whether the participant belongs to the current ready cohort
   * @param p95RttMs participant p95 round-trip delay
   * @param presentationLatencyMs robust predicted preparation plus reveal latency
   * @param presentationUncertaintyMs robust prediction uncertainty
   */
  public record ParticipantTiming(boolean ready, long p95RttMs,
      long presentationLatencyMs, long presentationUncertaintyMs)
  {
    /** Creates timing input without learned presentation history. */
    public ParticipantTiming(boolean ready, long p95RttMs)
    {
      this(ready, p95RttMs, 0, 0);
    }

    /** Validates non-negative timing input. */
    public ParticipantTiming
    {
      if (p95RttMs < 0 || presentationLatencyMs < 0 || presentationUncertaintyMs < 0)
        throw new IllegalArgumentException("participant timing");
    }
  }
}
