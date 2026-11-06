package sage.party;

/**
 * Immutable authoritative Party playback transition.
 *
 * @param stateVersion canonical timeline generation
 * @param commandSeq monotonically increasing command sequence
 * @param cause server-defined transition cause
 * @param targetPositionMs media position at the execution deadline
 * @param targetState target playback state
 * @param targetRate target playback rate
 * @param executeAtServerMonoMs server-monotonic execution deadline
 * @param originParticipantId participant that initiated the transition
 */
public record PartyCommand(long stateVersion, long commandSeq, String cause,
    long targetPositionMs, String targetState, double targetRate,
    long executeAtServerMonoMs, String originParticipantId)
{
}
