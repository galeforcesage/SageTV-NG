package sage.party;

/**
 * Canonical Party room state independent of connections and playback resources.
 */
public class PartySession
{
  final String id;
  final int media;
  final long created;
  final PartyTimeline timeline;
  String host;

  /**
   * Creates canonical room state.
   *
   * @param id opaque Party identifier
   * @param media canonical media identifier
   * @param availableEndMs currently available media extent
   * @param growing whether the media extent can increase
   * @param liveEdgeDelayMs safety distance from a growing edge
   * @param serverMonoMs creation time and initial timeline anchor
   */
  protected PartySession(String id, int media, long availableEndMs, boolean growing,
      long liveEdgeDelayMs, long serverMonoMs)
  {
    this.id = id;
    this.media = media;
    created = serverMonoMs;
    timeline = new PartyTimeline(availableEndMs, growing, liveEdgeDelayMs, serverMonoMs);
  }

  /** Returns the opaque Party identifier. */
  public String id() { return id; }

  /** Returns the canonical media identifier. */
  public int mediaFileId() { return media; }

  /** Returns the room creation time in server-monotonic milliseconds. */
  public long createdAtServerMonoMs() { return created; }

  /** Returns the authoritative media timeline. */
  public PartyTimeline timeline() { return timeline; }

  /** Returns the current host participant identifier. */
  public String hostParticipantId() { return host; }
}
