package sage;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import sage.epg.sd.gson.JsonObject;
import sage.party.PartyProtocol;
import sage.party.PartyService;
import sage.party.PartyPlaybackDirective;

/**
 * Party Mode's authenticated NG renderer adapter. No bridge authority or shared media path.
 * Input and bounded per-viewer output are off the receiver/render threads.
 */
final class NgPartyConnection implements PartyService.Peer
{
  private static final long ORIGIN = System.nanoTime();
  private static final PartyService SERVICE = new PartyService(
      () -> (System.nanoTime() - ORIGIN) / 1000000,
      Sage.getBoolean("party/enabled", false), Sage.getLong("party/live_edge_delay_ms", 3000));
  private static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(1, r -> {
    Thread t = new Thread(r, "PartyMaintenance"); t.setDaemon(true); return t;
  });
  static { TIMER.scheduleAtFixedRate(() -> runMaintenance(SERVICE), 1, 1, TimeUnit.SECONDS); }

  static void runMaintenance(PartyService service)
  {
    try
    {
      service.tick();
    }
    catch (RuntimeException failure)
    {
      System.out.println("PartyMode maintenance outcome=TICK_FAILED exception="
          + failure.getClass().getName() + " message=" + failure.getMessage());
      failure.printStackTrace(System.err);
    }
  }

  private final MiniClientSageRenderer renderer;
  private final UIManager ui;
  private final PartyService service;
  private final java.util.function.LongSupplier monotonicMs;
  private final ThreadPoolExecutor input = executor("PartyInput");
  private final ThreadPoolExecutor output = executor("PartyOutput");
  private final ThreadPoolExecutor preparation = executor("PartyPrepare");
  private final Object admissionLock = new Object();
  private final java.util.concurrent.CountDownLatch admissionReady = new java.util.concurrent.CountDownLatch(1);
  private String pendingHello;
  private volatile long preparationGeneration;
  private volatile boolean closed;
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
    if (closed) return;
    service.connected(this);
    if (!service.eligible(this)) return;
    System.out.println("PartyMode transport phase=ADMISSION_READY pendingHello=" + (pendingHello != null));
    send(service.availability(this));
    admissionReady.countDown();
  }

  void receive(String json)
  {
    if (closed) return;
    if (admissionReady.getCount() != 0 && isHello(json))
    {
      synchronized (admissionLock)
      {
        if (admissionReady.getCount() != 0)
        {
          if (pendingHello == null)
          {
            pendingHello = json;
            System.out.println("PartyMode transport phase=HELLO_DEFERRED");
            try { input.execute(() -> receiveAfterAdmission(json)); }
            catch (java.util.concurrent.RejectedExecutionException e)
            { failTransport("INPUT_BACKPRESSURE"); }
          }
          else if (!pendingHello.equals(json))
          {
            try { input.execute(() -> rejectEarlyHello(json)); }
            catch (java.util.concurrent.RejectedExecutionException e)
            { failTransport("INPUT_BACKPRESSURE"); }
          }
          return;
        }
      }
    }
    submit(json);
  }

  private void receiveAfterAdmission(String json)
  {
    try
    {
      if (!admissionReady.await(15, TimeUnit.SECONDS) || closed)
      {
        if (!closed) service.receive(this, json);
        return;
      }
      service.receive(this, json);
    }
    catch (InterruptedException e)
    {
      Thread.currentThread().interrupt();
    }
    finally
    {
      synchronized (admissionLock)
      {
        if (json.equals(pendingHello)) pendingHello = null;
      }
    }
  }

  private void rejectEarlyHello(String json)
  {
    if (closed) return;
    JsonObject response = PartyProtocol.envelope("ERROR");
    response.addProperty("code", "NOT_AVAILABLE");
    try
    {
      JsonObject request = PartyProtocol.parse(json);
      response.addProperty("requestId", PartyProtocol.text(request, "requestId"));
    }
    catch (PartyProtocol.Fault ignored) {}
    send(response);
  }

  private void submit(String json)
  {
    try { input.execute(() -> service.receive(this, json)); }
    catch (java.util.concurrent.RejectedExecutionException e)
    { failTransport("INPUT_BACKPRESSURE"); }
  }

  private static boolean isHello(String json)
  {
    try
    {
      JsonObject request = PartyProtocol.parse(json);
      return "HELLO".equals(PartyProtocol.text(request, "type"))
          && PartyProtocol.number(request, "requestSeq", 1, 9007199254740991L) > 0;
    }
    catch (PartyProtocol.Fault e)
    {
      return false;
    }
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
    admissionReady.countDown();
    synchronized (admissionLock) { pendingHello = null; }
    cancelScheduled();
    service.disconnected(this);
    if (ui.getVideoFrame() != null) ui.getVideoFrame().setPartyProgressOwned(false);
    input.shutdownNow(); output.shutdownNow(); preparation.shutdownNow();
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
  @Override public boolean localConnection() { return renderer.isLocalConnection(); }

  @Override public int currentMediaFileId()
  {
    VideoFrame vf = ui.getVideoFrame();
    String principal = principal();
    if (closed || principal == null || vf == null) throw new PartyProtocol.Fault("MEDIA_PATH_NOT_READY");
    MediaFile media = vf.getCurrFile();
    MiniPlayer player = vf.getPartyPlayer();
    if (media == null || player == null || media.getID() <= 0
        || !vf.isMediaPlayerLoaded()
        || (player.getState() != MediaPlayer.PLAY_STATE && player.getState() != MediaPlayer.PAUSE_STATE))
      throw new PartyProtocol.Fault("MEDIA_PATH_NOT_READY");
    int mediaFileId = media.getID();
    long playbackGeneration = player.getPartyPlaybackGeneration();
    if (closed || !principal.equals(principal()) || vf.getCurrFile() != media || vf.getPartyPlayer() != player
        || player.getPartyPlaybackGeneration() != playbackGeneration || !vf.isMediaPlayerLoaded()
        || (player.getState() != MediaPlayer.PLAY_STATE && player.getState() != MediaPlayer.PAUSE_STATE))
      throw new PartyProtocol.Fault("MEDIA_PATH_NOT_READY");
    return mediaFileId;
  }

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
  { return mediaWindow(id).availableEndMs(); }

  @Override public PartyService.MediaWindow mediaWindow(int id)
  {
    MediaFile mf = Wizard.getInstance().getFileForID(id);
    return partyMediaWindow(mf);
  }

  static PartyService.MediaWindow partyMediaWindow(MediaFile mf)
  {
    if (mf == null || !mf.isVideo() || mf.isDVD() || mf.isBluRay() || mf.isAnyLiveStream()
        || (mf.isTV() && !mf.isRecording() && !mf.isCompleteRecording())
        || mf.getNumSegments() < 1 || !mf.verifyFiles(true)
        || mf.getContentAiring() == null)
      throw new PartyProtocol.Fault("MEDIA_UNAVAILABLE");
    long availableEnd = mf.getRecordDuration();
    if (availableEnd <= 0) throw new PartyProtocol.Fault("MEDIA_UNAVAILABLE");
    return new PartyService.MediaWindow(availableEnd, mf.isRecording());
  }

  @Override public long currentPosition(int media)
  {
    duration(media);
    String principal = principal();
    if (closed || principal == null) throw new PartyProtocol.Fault("CURRENT_POSITION_UNAVAILABLE");
    long position = ui.getVideoFrame().partyCurrentPosition(media);
    if (closed || !principal.equals(principal()))
      throw new PartyProtocol.Fault("CURRENT_POSITION_UNAVAILABLE");
    return position;
  }

  @Override public long commercialEnd(int media, long position, boolean automatic)
  { return ui.getVideoFrame().partyCommercialEnd(media, position, automatic); }

  @Override public PartyService.Delivery delivery(int media)
  {
    VideoFrame vf = ui.getVideoFrame();
    MediaFile mf = vf.getCurrFile();
    if (mf == null || mf.getID() != media || duration(media) <= 0
        || (vf.getPlayerState() != MediaPlayer.PAUSE_STATE && vf.getPlayerState() != MediaPlayer.PLAY_STATE))
      throw new PartyProtocol.Fault("MEDIA_PATH_NOT_READY");
    MiniPlayer push = "push".equals(renderer.getCurrentSurfaceDeliveryMode()) ? vf.getPartyPlayer() : null;
    long generation = push == null ? -1 : push.getPartyPlaybackGeneration();
    PartyService.Delivery result = vf.getPartyDelivery(renderer);
    if (push != null)
    {
      if (vf.getPartyPlayer() != push || push.getPartyPlaybackGeneration() != generation)
        throw new PartyProtocol.Fault("MEDIA_PATH_NOT_READY");
    }
    return result;
  }

  @Override public PartyService.Delivery preparedDelivery(int media)
  {
    VideoFrame vf = ui.getVideoFrame();
    MediaFile mf = vf.getCurrFile();
    if (closed || mf == null || mf.getID() != media || vf.getPlayerState() != MediaPlayer.PAUSE_STATE)
      throw new PartyProtocol.Fault("MEDIA_PATH_NOT_READY");
    PartyService.Delivery path = vf.getPartyDelivery(renderer);
    if (renderer.hasPartySurface())
    {
      sage.client.PlaybackSurface selected = renderer.getPlaybackSurfaces().get(path.surface());
      if (selected == null || !selected.getDeliveryModes().contains(path.mode()))
        throw new PartyProtocol.Fault("MEDIA_PATH_NOT_READY");
      if ("mse".equalsIgnoreCase(selected.getRoute()) && selected.getDeliveryModes().equals(java.util.List.of("pull-xcode"))
          && (!"MP4".equals(path.container()) || path.xcodeMode() == null || !path.xcodeMode().startsWith("browserhd")))
        throw new PartyProtocol.Fault("MEDIA_PATH_NOT_READY");
    }
    return path;
  }

  @Override public void prepare(int media, String id, long positionMs)
  {
    duration(media);
    String principal = principal();
    long generation = ++preparationGeneration;
    try
    {
      preparation.execute(() -> {
        java.util.function.BooleanSupplier authorized = () -> !closed
            && preparationGeneration == generation && principal != null && principal.equals(principal())
            && capabilities().containsAll(PartyProtocol.REQUIRED);
        if (!authorized.getAsBoolean()) return;
        MediaFile target = preparationMedia(media);
        if (target == null)
        { service.preparationResult(this, id, VideoFrame.WATCH_FAILED_FILES_NOT_ON_DISK, -1); return; }
        VideoFrame.PartyPreparation stage =
            ui.getVideoFrame().preparePartyMedia(target, id, authorized, positionMs);
        awaitPreparation(stage, monotonicMs.getAsLong() + PartyService.PREPARATION_TIMEOUT_MS, generation);
      });
    }
    catch (java.util.concurrent.RejectedExecutionException e)
    { throw new PartyProtocol.Fault("PREPARATION_BUSY"); }
  }

  MediaFile preparationMedia(int media) { return Wizard.getInstance().getFileForID(media); }

  private void awaitPreparation(VideoFrame.PartyPreparation stage, long deadline, long generation)
  {
    long remaining = Math.max(0, deadline - monotonicMs.getAsLong());
    try { stage.awaitCompletion(remaining); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    if (closed || preparationGeneration != generation) return;
    if (stage.watchCode != VideoFrame.WATCH_OK)
      service.preparationResult(this, stage.id, stage.watchCode, -1);
    else if (!stage.active())
      service.preparationResult(this, stage.id, VideoFrame.WATCH_FAILED_USER_REJECTED_CONFLICT, -1);
    else if (ui.getVideoFrame().isPartyPreparationLoaded(stage))
    {
      ui.getVideoFrame().presentPartyPreparation(stage);
      service.preparationResult(this, stage.id, VideoFrame.WATCH_OK, stage.generation);
    }
    else
      service.preparationResult(this, stage.id, VideoFrame.WATCH_FAILED_UNKNOWN, -1);
  }

  @Override public void cancelPreparation()
  {
    preparationGeneration++;
    if (ui.getVideoFrame() != null) ui.getVideoFrame().cancelPartyPreparation();
  }

  PartyService.Delivery plan(int media)
  { return plan(media, renderer.getPlaybackSurfaces(), false); }

  PartyService.Delivery plan(int media, sage.client.PlaybackSurfaceSet surfaces)
  { return plan(media, surfaces, true); }

  private PartyService.Delivery plan(int media, sage.client.PlaybackSurfaceSet surfaces, boolean conditioned)
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
        sage.client.PlaybackDecisionEngine.evaluateSurfaces(surfaces,
            format.getFormatName(), video.getFormatName(), audio == null ? "" : audio.getFormatName(),
            video.getWidth(), video.getHeight(), bitrate, bw, video.isInterlaced(), format,
            renderer.getCurrentClientAudioLanguage(), renderer.getClientConstraints(), conditioned);
    if (ranked.isEmpty()) throw new PartyProtocol.Fault("MEDIA_PATH_NOT_READY");
    sage.client.PlaybackDecisionEngine.SurfaceDecision winner = ranked.get(0);
    int selectedKbps = Math.max(bitrate, winner.decision.targetBitrateKbps);
    return new PartyService.Delivery(winner.surface.getId(), winner.chosenDeliveryMode,
        winner.decision.targetVideoCodec, winner.decision.targetAudioCodec,
        (int)Math.min(Integer.MAX_VALUE, 2L * selectedKbps), selectedKbps,
        winner.decision.decision != sage.client.PlaybackDecisionEngine.Decision.DIRECT_PLAY,
        winner.surface.getRoute(), winner.chosenXcodeMode, winner.decision.targetContainer);
  }

  @Override public long skip(String action) { return ui.getVideoFrame().partySkipDelta(action); }
  @Override public void progressOwner(boolean owned)
  {
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

  @Override public synchronized void schedule(PartyPlaybackDirective directive)
  {
    cancelScheduled();
    if (closed || !"push".equals(renderer.getCurrentSurfaceDeliveryMode())
        || "SOFT".equals(directive.kind()))
      return;
    long generation = scheduleGeneration;
    long deadline = directive.executeAtServerMonoMs();
    long delay = Math.max(0, deadline - monotonicMs.getAsLong());
    long position = directive.targetPositionMs();
    boolean playing = "PLAYING".equals(directive.targetState());
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
