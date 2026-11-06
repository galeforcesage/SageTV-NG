package sage.party;

/**
 * Player-facing execution directive independent of the Party wire format.
 *
 * @param stateVersion canonical timeline generation
 * @param commandSeq command sequence associated with the directive
 * @param kind execution kind such as {@code COMMAND}, {@code HARD}, or {@code SOFT}
 * @param targetPositionMs media position at the execution deadline
 * @param targetState target playback state
 * @param targetRate target playback rate
 * @param executeAtServerMonoMs server-monotonic execution deadline
 */
public record PartyPlaybackDirective(long stateVersion, long commandSeq, String kind,
    long targetPositionMs, String targetState, double targetRate,
    long executeAtServerMonoMs)
{
}
