package sage.party;

/**
 * Bridges Party coordination to one renderer's ordinary SageTV playback path.
 */
public interface PartyPlayerAdapter
{
  /** Returns the exact media currently loaded on this renderer. */
  default int currentMediaFileId() { throw new PartyProtocol.Fault("MEDIA_PATH_NOT_READY"); }

  /** Validates eligible accessible media and returns its currently available duration. */
  long duration(int mediaFileId);

  /** Returns the current server-owned media window. */
  default PartyService.MediaWindow mediaWindow(int mediaFileId)
  {
    return new PartyService.MediaWindow(duration(mediaFileId), false);
  }

  /** Samples the renderer's loaded media position for its current generation. */
  default long currentPosition(int mediaFileId)
  {
    throw new PartyProtocol.Fault("CURRENT_POSITION_UNAVAILABLE");
  }

  /** Returns actual server-selected playback metadata, or throws until prepared. */
  PartyService.Delivery delivery(int mediaFileId);

  /** Describes the actual paused loaded playback path. */
  default PartyService.Delivery preparedDelivery(int mediaFileId)
  {
    return delivery(mediaFileId);
  }

  /** Stages canonical media at a server-projected Party position. */
  default void prepare(int mediaFileId, String preparationId, long positionMs)
  {
    throw new PartyProtocol.Fault("PREPARE_UNSUPPORTED");
  }

  /** Invalidates asynchronous preparation. */
  default void cancelPreparation() {}

  /** Resolves initiating-client skip settings using existing Sage semantics. */
  long skip(String action);

  /** Resolves a server-owned commercial marker end relative to joined media. */
  default long commercialEnd(int mediaFileId, long positionMs, boolean automatic) { return -1; }

  /** Suppresses ordinary per-viewer progress logging while Party owns playback. */
  void progressOwner(boolean partyOwnsProgress);

  /** Commits through existing watched/resume policy at leave or end. */
  void commit(int mediaFileId, long positionMs);

  /**
   * Schedules the existing renderer playback path.
   *
   * @param directive generation-fenced player directive
   */
  void schedule(PartyPlaybackDirective directive);

  /** Cancels superseded or locally stopped renderer work. */
  void cancelScheduled();
}
