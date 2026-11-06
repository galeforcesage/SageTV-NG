package sage.party;

/**
 * Authoritative Party media timeline, independent of transport and players.
 */
public final class PartyTimeline
{
  private final long liveEdgeDelayMs;
  private long stateVersion;
  private long commandSeq;
  private long anchorPositionMs;
  private long anchorServerMonoMs;
  private long availableEndMs;
  private boolean growing;
  private String state = "PAUSED";
  private PartyCommand pending;

  /**
   * Creates a paused timeline at media position zero.
   *
   * @param availableEndMs currently available media extent
   * @param growing whether the media extent can increase
   * @param liveEdgeDelayMs safety distance from a growing edge
   * @param serverMonoMs initial server-monotonic anchor
   */
  public PartyTimeline(long availableEndMs, boolean growing, long liveEdgeDelayMs,
      long serverMonoMs)
  {
    if (availableEndMs <= 0) throw new IllegalArgumentException("availableEndMs");
    this.availableEndMs = availableEndMs;
    this.growing = growing;
    this.liveEdgeDelayMs = Math.max(0, liveEdgeDelayMs);
    anchorServerMonoMs = serverMonoMs;
  }

  /** Returns the current canonical state version. */
  public long stateVersion() { return stateVersion; }

  /** Returns the current command sequence. */
  public long commandSeq() { return commandSeq; }

  /** Returns the committed anchor media position. */
  public long anchorPositionMs() { return anchorPositionMs; }

  /** Returns the committed server-monotonic anchor. */
  public long anchorServerMonoMs() { return anchorServerMonoMs; }

  /** Returns the committed playback state. */
  public String state() { return state; }

  /** Returns the currently available media extent. */
  public long availableEndMs() { return availableEndMs; }

  /** Returns whether the media extent is actively growing. */
  public boolean growing() { return growing; }

  /** Returns the maximum currently playable media position. */
  public long playableEndMs()
  {
    return growing ? Math.max(0, availableEndMs - liveEdgeDelayMs) : availableEndMs;
  }

  /** Returns the pending transition, or {@code null} when none is scheduled. */
  public PartyCommand pending() { return pending; }

  /**
   * Sets the initial media position before any transition is scheduled.
   *
   * @param positionMs initial media position
   */
  public void setInitialPosition(long positionMs)
  {
    if (stateVersion != 0 || commandSeq != 0 || pending != null)
      throw new IllegalStateException("timeline already active");
    anchorPositionMs = clamp(positionMs);
  }

  /**
   * Updates the server-observed media window.
   *
   * @param availableEndMs currently available media extent
   * @param growing whether the media extent is actively growing
   */
  public void refreshWindow(long availableEndMs, boolean growing)
  {
    if (availableEndMs <= 0) throw new IllegalArgumentException("availableEndMs");
    this.availableEndMs = growing ? Math.max(this.availableEndMs, availableEndMs) : availableEndMs;
    this.growing = growing;
    anchorPositionMs = clamp(anchorPositionMs);
  }

  /**
   * Projects the canonical media position at a server-monotonic instant.
   *
   * @param serverMonoMs projection instant
   * @return clamped canonical media position
   */
  public long positionAt(long serverMonoMs)
  {
    long position = anchorPositionMs;
    long anchorAt = anchorServerMonoMs;
    String projectedState = state;
    if (pending != null && serverMonoMs >= pending.executeAtServerMonoMs())
    {
      position = pending.targetPositionMs();
      anchorAt = pending.executeAtServerMonoMs();
      projectedState = pending.targetState();
    }
    if ("PLAYING".equals(projectedState))
      position += Math.max(0, serverMonoMs - anchorAt);
    return clamp(position);
  }

  /**
   * Schedules a new authoritative transition.
   *
   * @param cause server-defined transition cause
   * @param targetPositionMs media position at the execution deadline
   * @param targetState target playback state
   * @param targetRate target playback rate
   * @param executeAtServerMonoMs server-monotonic execution deadline
   * @param originParticipantId initiating participant
   * @return immutable scheduled command
   */
  public PartyCommand schedule(String cause, long targetPositionMs, String targetState,
      double targetRate, long executeAtServerMonoMs, String originParticipantId)
  {
    pending = new PartyCommand(++stateVersion, ++commandSeq, cause,
        clamp(targetPositionMs), targetState, targetRate, executeAtServerMonoMs,
        originParticipantId);
    return pending;
  }

  /**
   * Commits a due transition.
   *
   * @param serverMonoMs current server-monotonic time
   * @return committed command, or {@code null} when no command is due
   */
  public PartyCommand advance(long serverMonoMs)
  {
    if (pending == null || pending.executeAtServerMonoMs() > serverMonoMs) return null;
    PartyCommand committed = pending;
    anchorPositionMs = committed.targetPositionMs();
    anchorServerMonoMs = committed.executeAtServerMonoMs();
    state = committed.targetState();
    pending = null;
    return committed;
  }

  private long clamp(long positionMs)
  {
    return Math.max(0, Math.min(playableEndMs(), positionMs));
  }
}
