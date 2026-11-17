package sage.party;

/**
 * Bridges Party coordination to one renderer's ordinary SageTV playback path.
 */
public interface PartyPlayerAdapter
{
  /**
   * One-shot identity installed before the production media open for a Party
   * preparation.
   *
   * @param partyId owning Party
   * @param preparationId server preparation identifier
   * @param preparationBinding opaque 128-bit binding
   * @param mediaFileId media selected by the Party
   */
  record PreparationBinding(String partyId, String preparationId,
      String preparationBinding, int mediaFileId, String playbackSessionId)
  {
    /** Creates a binding for a non-HTTP route. */
    public PreparationBinding(String partyId, String preparationId,
        String preparationBinding, int mediaFileId)
    {
      this(partyId, preparationId, preparationBinding, mediaFileId, null);
    }

    /** Validates required binding identity fields. */
    public PreparationBinding
    {
      if (partyId == null || partyId.isEmpty()
          || preparationId == null || preparationId.isEmpty()
          || preparationBinding == null || preparationBinding.isEmpty()
          || (playbackSessionId != null && playbackSessionId.isEmpty()))
        throw new IllegalArgumentException("preparation binding");
    }
  }

  /**
   * Adapter-owned result of preparing the ordinary SageTV playback path.
   *
   * @param loaded whether a stable playback generation is ready
   * @param watchCode ordinary SageTV Watch result retained for diagnostics
   * @param generation stable playback generation, or {@code -1} on failure
   * @param failureCode stable Party failure code, or {@code null} on success
   */
  record PreparationResult(boolean loaded, int watchCode, long generation,
      String failureCode)
  {
    /** Creates a successful preparation result. */
    public static PreparationResult loaded(int watchCode, long generation)
    {
      if (generation < 0) throw new IllegalArgumentException("generation");
      return new PreparationResult(true, watchCode, generation, null);
    }

    /** Creates a failed preparation result. */
    public static PreparationResult failed(int watchCode, String failureCode)
    {
      if (failureCode == null || failureCode.isEmpty())
        throw new IllegalArgumentException("failureCode");
      return new PreparationResult(false, watchCode, -1, failureCode);
    }

    /** Validates coupled preparation fields. */
    public PreparationResult
    {
      if (loaded != (generation >= 0) || loaded != (failureCode == null))
        throw new IllegalArgumentException("preparation result");
    }
  }

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

  /**
   * Returns the current position only when PREPARE has left this exact media
   * generation paused and stable.
   */
  default long preparedPausedPosition(int mediaFileId)
  {
    return -1;
  }

  /** Returns actual server-selected playback metadata, or throws until prepared. */
  PartyService.Delivery delivery(int mediaFileId);

  /** Describes the actual loaded playback path before client READY. */
  default PartyService.Delivery preparedDelivery(int mediaFileId)
  {
    return delivery(mediaFileId);
  }

  /**
   * Returns the stable generation of an already-loaded delivery pipeline.
   *
   * @return generation, or {@code -1} when the requested media is not loaded
   */
  default long preparedGeneration(int mediaFileId) { return -1; }

  /**
   * Stages a one-shot binding before starting the production media open.
   * Route adapters may defer carrier installation until the immutable selected
   * surface is committed, but must install it before issuing the media open.
   *
   * @param binding exact preparation identity
   */
  default void installPreparationBinding(PreparationBinding binding)
  {
    throw new PartyProtocol.Fault("OPEN_BINDING_UNSUPPORTED");
  }

  /**
   * Returns whether the route-specific carrier was acknowledged and consumed by
   * the production media open.
   *
   * @param preparationBinding opaque binding to inspect
   * @return {@code true} only after a successful bound production open
   */
  default boolean preparationBindingReady(String preparationBinding) { return false; }

  /**
   * Invalidates one unconsumed or unsettled preparation binding.
   *
   * @param preparationBinding opaque binding to invalidate
   */
  default void cancelPreparationBinding(String preparationBinding) {}

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

  /** Authorizes staging Party media without taking ownership of ordinary playback. */
  void preparationOwner(boolean partyMayPrepare);

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
