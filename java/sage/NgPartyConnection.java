package sage;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import sage.epg.sd.gson.JsonObject;
import sage.party.PartyProtocol;
import sage.party.PartyService;

/**
 * Party Mode's authenticated NG renderer adapter. No bridge authority or shared media path.
 * Input and bounded per-viewer output are off the receiver/render threads.
 */
final class NgPartyConnection implements PartyService.Peer
{
  private static final long ORIGIN = System.nanoTime();
  private static final PartyService SERVICE = new PartyService(
      () -> (System.nanoTime() - ORIGIN) / 1000000,
      Sage.getBoolean("party/enabled", false), Sage.getInt("party/max_budget_kbps", 100000),
      Sage.getInt("party/max_transcodes", 1));
  private static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(1, r -> {
    Thread t = new Thread(r, "PartyMaintenance"); t.setDaemon(true); return t;
  });
  static { TIMER.scheduleAtFixedRate(SERVICE::tick, 1, 1, TimeUnit.SECONDS); }

  private final MiniClientSageRenderer renderer;
  private final UIManager ui;
  private final PartyService service;
  private final java.util.function.LongSupplier monotonicMs;
  private final ThreadPoolExecutor input = executor("PartyInput");
  private final ThreadPoolExecutor output = executor("PartyOutput");
  private volatile boolean closed;
  private volatile MiniPlayer admittedPushPlayer;
  private volatile long admittedPushGeneration = -1;
  private java.util.concurrent.ScheduledFuture<?> scheduled;
  private long scheduleGeneration;

  NgPartyConnection(MiniClientSageRenderer renderer, UIManager ui)
  { this(renderer, ui, SERVICE); }

  NgPartyConnection(MiniClientSageRenderer renderer, UIManager ui, PartyService service)
  { this(renderer, ui, service, () -> (System.nanoTime() - ORIGIN) / 1000000); }

  NgPartyConnection(MiniClientSageRenderer renderer, UIManager ui, PartyService service,
      java.util.function.LongSupplier monotonicMs)
  { this.renderer = renderer; this.ui = ui; this.service = service; this.monotonicMs = monotonicMs; }

  private static ThreadPoolExecutor executor(String name)
  {
    ThreadPoolExecutor pool = new ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(64), r -> {
          Thread t = new Thread(r, name); t.setDaemon(true); return t;
        });
    pool.allowCoreThreadTimeOut(true);
    return pool;
  }

  void authenticated()
  {
    service.connected(this);
    if (service.eligible(this))
      send(service.availability(this));
  }

  void receive(String json)
  {
    if (closed) return;
    try { input.execute(() -> service.receive(this, json)); }
    catch (java.util.concurrent.RejectedExecutionException e)
    { failTransport("INPUT_BACKPRESSURE"); }
  }

  boolean route(String action)
  {
    if (!ui.getVideoFrame().isPartyProgressOwned()) return false;
    try { input.execute(() -> service.nativeAction(this, action)); }
    catch (java.util.concurrent.RejectedExecutionException e)
    { failTransport("INPUT_BACKPRESSURE"); }
    return true;
  }

  void disconnect()
  {
    if (closed) return;
    closed = true;
    releaseMediaAdmission();
    cancelScheduled();
    service.disconnected(this);
    if (ui.getVideoFrame() != null) ui.getVideoFrame().setPartyProgressOwned(false);
    input.shutdownNow(); output.shutdownNow();
  }

  private void failTransport(String reason)
  {
    System.out.println("PartyMode transport outcome=" + reason);
    disconnect();
  }

  static void shutdown()
  {
    SERVICE.shutdown();
    TIMER.shutdownNow();
  }

  @Override public String principal() { return renderer.getPartyPrincipal(); }
  @Override public Set<String> capabilities() { return renderer.getClientCapabilities(); }

  @Override public void send(JsonObject message)
  {
    if (closed) return;
    String json = message.toString();
    if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > PartyProtocol.MAX_BYTES)
    { failTransport("OUTPUT_TOO_LARGE"); return; }
    try
    {
      output.execute(() -> {
        try { renderer.sendSetPropertyAsync(PartyProtocol.PROPERTY, json); }
        catch (java.io.IOException e) { failTransport("OUTPUT_IO"); }
      });
    }
    catch (java.util.concurrent.RejectedExecutionException e)
    { failTransport("OUTPUT_BACKPRESSURE"); }
  }

  @Override public long duration(int id)
  {
    MediaFile mf = Wizard.getInstance().getFileForID(id);
    if (mf == null || !mf.isVideo() || mf.isDVD() || mf.isBluRay() || mf.isAnyLiveStream()
        || mf.isRecording() || (mf.isTV() && !mf.isCompleteRecording())
        || mf.getNumSegments() != 1 || !mf.verifyFiles(true)
        || mf.getContentAiring() == null
        || (ui.getVideoFrame().doesAiringRequirePC(mf.getContentAiring())
            && ui.getVideoFrame().getCurrFile() != mf))
      throw new PartyProtocol.Fault("MEDIA_UNAVAILABLE");
    return mf.getRecordDuration();
  }

  @Override public PartyService.Delivery delivery(int media)
  {
    VideoFrame vf = ui.getVideoFrame();
    MediaFile mf = vf.getCurrFile();
    if (mf == null || mf.getID() != media || duration(media) <= 0
        || (vf.getPlayerState() != MediaPlayer.PAUSE_STATE && vf.getPlayerState() != MediaPlayer.PLAY_STATE))
      throw new PartyProtocol.Fault("MEDIA_PATH_NOT_READY");
    if (!mediaAdmitted(media)) throw new PartyProtocol.Fault("MEDIA_ADMISSION_UNCONFIRMED");
    MiniPlayer push = "push".equals(renderer.getCurrentSurfaceDeliveryMode()) ? vf.getPartyPlayer() : null;
    long generation = push == null ? -1 : push.getPartyPlaybackGeneration();
    PartyService.Delivery result = vf.getPartyDelivery(renderer);
    if (push != null)
    {
      if (vf.getPartyPlayer() != push || push.getPartyPlaybackGeneration() != generation)
        throw new PartyProtocol.Fault("MEDIA_ADMISSION_UNCONFIRMED");
      admittedPushPlayer = push; admittedPushGeneration = generation;
    }
    return result;
  }

  @Override public JsonObject mediaAdmission(int media)
  {
    MediaFile mf = ui.getVideoFrame().getCurrFile();
    if (mf == null || mf.getID() != media) throw new PartyProtocol.Fault("MEDIA_PATH_NOT_READY");
    JsonObject result = PartyProtocol.envelope("MEDIA_ADMISSION_HANDLE");
    result.addProperty("handle", ui.getVideoFrame().issuePartyMediaAdmission(renderer, this));
    result.addProperty("expiresInMs", 60000);
    result.addProperty("mediaFileId", media);
    result.addProperty("mode", renderer.getCurrentSurfaceDeliveryMode());
    return result;
  }

  @Override public boolean mediaAdmitted(int media)
  {
    VideoFrame vf = ui.getVideoFrame();
    MediaFile mf = vf.getCurrFile();
    MiniPlayer admitted = admittedPushPlayer;
    long generation = admittedPushGeneration;
    return !closed && principal() != null && mf != null && mf.getID() == media
        && (vf.getPlayerState() == MediaPlayer.PAUSE_STATE || vf.getPlayerState() == MediaPlayer.PLAY_STATE)
        && ("push".equals(renderer.getCurrentSurfaceDeliveryMode())
            ? vf.hasPartyPushAdmission() && (admitted == null
                || vf.getPartyPlayer() == admitted && admitted.getPartyPlaybackGeneration() == generation)
            : NgPartyMediaAdmission.INSTANCE.confirmed(this));
  }

  @Override public void releaseMediaAdmission()
  {
    admittedPushPlayer = null; admittedPushGeneration = -1;
    NgPartyMediaAdmission.INSTANCE.revoke(this);
  }

  @Override public PartyService.Delivery plan(int media)
  {
    duration(media);
    MediaFile mf = Wizard.getInstance().getFileForID(media);
    sage.media.format.ContainerFormat format = mf.getFileFormat();
    if (format == null || format.getVideoFormat() == null || format.getBitrate() <= 0
        || !Sage.getBoolean("miniplayer/use_playback_surfaces", true))
      throw new PartyProtocol.Fault("MEDIA_PATH_NOT_READY");
    sage.media.format.VideoFormat video = format.getVideoFormat();
    sage.media.format.AudioFormat audio = format.getAudioFormat();
    int bitrate = format.getBitrate() / 1000;
    int bw = (int)Math.min(Integer.MAX_VALUE, renderer.getEstimatedBandwidth() / 1000);
    java.util.List<sage.client.PlaybackDecisionEngine.SurfaceDecision> ranked =
        sage.client.PlaybackDecisionEngine.evaluateSurfaces(renderer.getPlaybackSurfaces(),
            format.getFormatName(), video.getFormatName(), audio == null ? "" : audio.getFormatName(),
            video.getWidth(), video.getHeight(), bitrate, bw, video.isInterlaced(), format,
            renderer.getCurrentClientAudioLanguage(), renderer.getClientConstraints());
    if (ranked.isEmpty()) throw new PartyProtocol.Fault("MEDIA_PATH_NOT_READY");
    sage.client.PlaybackDecisionEngine.SurfaceDecision winner = ranked.get(0);
    return new PartyService.Delivery(winner.surface.getId(), winner.chosenDeliveryMode,
        winner.decision.targetVideoCodec, winner.decision.targetAudioCodec,
        (int)Math.min(Integer.MAX_VALUE, 2L * Math.max(bitrate, winner.decision.targetBitrateKbps)),
        winner.decision.decision != sage.client.PlaybackDecisionEngine.Decision.DIRECT_PLAY);
  }

  @Override public long skip(String action) { return ui.getVideoFrame().partySkipDelta(action); }
  @Override public void progressOwner(boolean owned)
  {
    if (!owned) releaseMediaAdmission();
    ui.getVideoFrame().setPartyProgressOwned(owned);
  }
  @Override public void commit(int media, long position)
  { ui.getVideoFrame().commitPartyProgress(media, position); }

  @Override public synchronized void cancelScheduled()
  {
    scheduleGeneration++;
    if (scheduled != null) scheduled.cancel(false);
    scheduled = null;
  }

  @Override public synchronized void schedule(JsonObject transition)
  {
    cancelScheduled();
    if (closed || !"push".equals(renderer.getCurrentSurfaceDeliveryMode())
        || "SOFT".equals(transition.has("kind") ? transition.get("kind").getAsString() : ""))
      return;
    long generation = scheduleGeneration;
    long deadline = transition.get("executeAtServerMonoMs").getAsLong();
    long delay = Math.max(0, deadline - monotonicMs.getAsLong());
    long position = transition.get("targetPositionMs").getAsLong();
    boolean playing = "PLAYING".equals(transition.get("targetState").getAsString());
    MediaFile media = ui.getVideoFrame().getCurrFile();
    if (media == null) return;
    int mediaId = media.getID();
    scheduled = TIMER.schedule(() -> {
      synchronized (NgPartyConnection.this)
      {
        if (!closed && generation == scheduleGeneration)
          ui.getVideoFrame().applyPartyTimeline(mediaId, position, playing);
      }
    }, delay, TimeUnit.MILLISECONDS);
  }
}
