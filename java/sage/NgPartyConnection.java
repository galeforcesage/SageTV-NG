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
      Sage.getInt("party/max_transcodes", 1), Sage.getLong("party/live_edge_delay_ms", 3000));
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
  private final ThreadPoolExecutor preparation = executor("PartyPrepare");
  private final Object admissionLock = new Object();
  private final java.util.concurrent.CountDownLatch admissionReady = new java.util.concurrent.CountDownLatch(1);
  private String pendingHello;
  private volatile long preparationGeneration;
  private volatile SurfaceCandidate surfaceCandidate;
  private volatile String selectedSurfaceId;
  private final java.security.SecureRandom contextRandom = new java.security.SecureRandom();
  private volatile java.nio.channels.SocketChannel admissionControl;
  private volatile boolean admissionEventFailure;
  private final java.util.concurrent.atomic.AtomicLong surfaceGeneration = new java.util.concurrent.atomic.AtomicLong();
  private final Object surfaceStateLock = new Object();
  private record SurfaceCandidate(String id, long operation, String principal, long pathGeneration,
      VideoFrame.PartySurfaceFence fence, sage.client.PlaybackSurfaceSet surfaces) {}
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
    restoreSurface();
    releaseMediaAdmission();
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

  @Override public JsonObject mediaAdmission(int media)
  {
    MediaFile mf = ui.getVideoFrame().getCurrFile();
    if (mf == null || mf.getID() != media) throw new PartyProtocol.Fault("MEDIA_PATH_NOT_READY");
    MiniPlayer player = ui.getVideoFrame().getPartyPlayer();
    if (player == null) throw new PartyProtocol.Fault("MEDIA_PATH_NOT_READY");
    long playbackGeneration = player.getPartyPlaybackGeneration();
    long pathGeneration = renderer.getPartyPathGeneration();
    java.nio.channels.SocketChannel control = renderer.partyControlChannel();
    byte[] bytes = new byte[24]; contextRandom.nextBytes(bytes);
    String contextId = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    JsonObject result = PartyProtocol.envelope("MEDIA_ADMISSION_HANDLE");
    result.addProperty("handle", ui.getVideoFrame().issuePartyMediaAdmission(renderer, this, reason -> {
      JsonObject event = PartyProtocol.envelope("MEDIA_ADMISSION_REVOKED");
      event.addProperty("contextId", contextId); event.addProperty("reason", reason);
      queueAdmissionRevocation(event);
    }));
    if (ui.getVideoFrame().getPartyPlayer() != player || player.getPartyPlaybackGeneration() != playbackGeneration
        || renderer.getPartyPathGeneration() != pathGeneration)
    { releaseMediaAdmission(); throw new PartyProtocol.Fault("MEDIA_PATH_NOT_READY"); }
    result.addProperty("contextId", contextId);
    result.addProperty("playbackGeneration", playbackGeneration);
    result.addProperty("pathGeneration", pathGeneration);
    result.addProperty("expiresAtServerMonoMs",
        NgPartyMediaAdmission.INSTANCE.expiresAt(this, result.get("handle").getAsString()));
    result.addProperty("expiresInMs", 60000);
    result.addProperty("mediaFileId", media);
    result.addProperty("mode", renderer.getCurrentSurfaceDeliveryMode());
    admissionControl = control;
    return result;
  }

  private void queueAdmissionRevocation(JsonObject event)
  {
    if (closed) return;
    try
    {
      output.execute(() -> {
        if (closed) return;
        try { renderer.sendSetPropertyAsync(PartyProtocol.PROPERTY, event.toString()); }
        catch (java.io.IOException e) { failTransport("ADMISSION_EVENT_IO"); }
      });
    }
    catch (java.util.concurrent.RejectedExecutionException e)
    { admissionEventFailure = true; System.out.println("PartyMode transport outcome=ADMISSION_EVENT_BACKPRESSURE"); }
  }

  @Override public void maintenance()
  {
    NgPartyMediaAdmission.INSTANCE.maintenance();
    if (admissionEventFailure) failTransport("ADMISSION_EVENT_BACKPRESSURE");
  }

  @Override public void describeMediaAdmission(JsonObject reply)
  {
    java.nio.channels.SocketChannel control = admissionControl;
    PartyMediaAdmissionBridge.Context context = new PartyMediaAdmissionBridge.Context(
        PartyProtocol.text(reply, "contextId"), PartyProtocol.text(reply, "serverEpoch"),
        PartyProtocol.text(reply, "sessionHandle"), reply.get("mediaFileId").getAsInt(),
        PartyProtocol.text(reply, "mode"), reply.get("playbackGeneration").getAsLong(),
        reply.get("pathGeneration").getAsLong(), reply.get("expiresAtServerMonoMs").getAsLong(),
        PartyProtocol.text(reply, "requestId"), reply.get("requestSeq").getAsLong());
    NgPartyMediaAdmission.INSTANCE.describe(this, PartyProtocol.text(reply, "handle"), context,
        socket -> !closed && principal() != null && renderer.ownsPartyControlSocket(control, socket));
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

  @Override public void prepare(int media, String id)
  {
    duration(media);
    String principal = principal();
    long generation = ++preparationGeneration;
    try
    {
      preparation.execute(() -> {
        java.util.function.BooleanSupplier authorized = () -> !closed
            && preparationGeneration == generation && principal != null && principal.equals(principal())
            && capabilities().containsAll(PartyProtocol.REQUIRED) && capabilities().contains("PARTY_PREPARE_V1");
        if (!authorized.getAsBoolean()) return;
        MediaFile target = preparationMedia(media);
        if (target == null)
        { service.preparationResult(this, id, VideoFrame.WATCH_FAILED_FILES_NOT_ON_DISK, -1); return; }
        long pathGeneration = renderer.getPartyPathGeneration();
        java.util.function.BooleanSupplier pathAuthorized = () -> authorized.getAsBoolean()
            && renderer.getPartyPathGeneration() == pathGeneration;
        VideoFrame.PartyPreparation stage = renderer.hasPartySurface()
            ? ui.getVideoFrame().preparePartyMedia(target, id, pathAuthorized, true)
            : ui.getVideoFrame().preparePartyMedia(target, id, pathAuthorized);
        pollPreparation(stage, monotonicMs.getAsLong() + 60000, generation);
      });
    }
    catch (java.util.concurrent.RejectedExecutionException e)
    { throw new PartyProtocol.Fault("PREPARATION_BUSY"); }
  }

  MediaFile preparationMedia(int media) { return Wizard.getInstance().getFileForID(media); }

  private void pollPreparation(VideoFrame.PartyPreparation stage, long deadline, long generation)
  {
    if (closed || preparationGeneration != generation) return;
    if (stage.watchCode != VideoFrame.WATCH_OK)
      service.preparationResult(this, stage.id, stage.watchCode, -1);
    else if (!stage.active())
      service.preparationResult(this, stage.id, VideoFrame.WATCH_FAILED_USER_REJECTED_CONFLICT, -1);
    else if (ui.getVideoFrame().isPartyPreparationLoaded(stage))
      service.preparationResult(this, stage.id, VideoFrame.WATCH_OK, stage.generation);
    else if (stage.loaded || monotonicMs.getAsLong() >= deadline)
      service.preparationResult(this, stage.id, VideoFrame.WATCH_FAILED_UNKNOWN, -1);
    else TIMER.schedule(() -> pollPreparation(stage, deadline, generation), 100, TimeUnit.MILLISECONDS);
  }

  @Override public void cancelPreparation()
  {
    preparationGeneration++;
    if (ui.getVideoFrame() != null) ui.getVideoFrame().cancelPartyPreparation();
  }

  @Override public PartyService.Delivery plan(int media)
  { return plan(media, renderer.getPlaybackSurfaces(), renderer.hasPartySurface()); }

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
    return new PartyService.Delivery(winner.surface.getId(), winner.chosenDeliveryMode,
        winner.decision.targetVideoCodec, winner.decision.targetAudioCodec,
        (int)Math.min(Integer.MAX_VALUE, 2L * Math.max(bitrate, winner.decision.targetBitrateKbps)),
        winner.decision.decision != sage.client.PlaybackDecisionEngine.Decision.DIRECT_PLAY,
        winner.surface.getRoute(), winner.chosenXcodeMode, winner.decision.targetContainer);
  }

  @Override public void selectSurface(int media, String surface, String id)
  {
    if (closed || principal() == null) throw new PartyProtocol.Fault("NOT_AVAILABLE");
    VideoFrame.PartySurfaceFence fence = ui.getVideoFrame().partySurfaceFence();
    String principal = principal();
    long operation = surfaceGeneration.incrementAndGet();
    long path = renderer.getPartyPathGeneration();
    try
    {
      preparation.execute(() -> {
        try
        {
          sage.client.PlaybackSurfaceSet surfaces = renderer.queryPartySurface(surface);
          PartyService.Delivery plan = plan(media, surfaces);
          if (closed || surfaceGeneration.get() != operation) return;
          surfaceCandidate = new SurfaceCandidate(id, operation, principal, path, fence, surfaces);
          service.surfaceSelectionResult(this, id, plan, null);
        }
        catch (java.io.IOException e)
        { service.surfaceSelectionResult(this, id, null, "SURFACE_QUERY_FAILED"); }
        catch (PartyProtocol.Fault f)
        { service.surfaceSelectionResult(this, id, null, f.getMessage()); }
      });
    }
    catch (java.util.concurrent.RejectedExecutionException e)
    { throw new PartyProtocol.Fault("PREPARATION_BUSY"); }
  }

  @Override public long applySurface(String id)
  {
    synchronized (surfaceStateLock)
    {
      SurfaceCandidate candidate = surfaceCandidate;
      if (candidate == null || !candidate.id.equals(id) || closed
          || surfaceGeneration.get() != candidate.operation || !candidate.principal.equals(principal()))
        throw new PartyProtocol.Fault("SURFACE_CHANGED");
      releaseMediaAdmission();
      long generation = ui.getVideoFrame().commitPartySurface(candidate.fence, renderer, candidate.surfaces, candidate.pathGeneration);
      selectedSurfaceId = id;
      surfaceCandidate = null;
      return generation;
    }
  }

  @Override public long restoreSurface()
  {
    synchronized (surfaceStateLock)
    {
      surfaceGeneration.incrementAndGet(); surfaceCandidate = null;
      selectedSurfaceId = null;
      cancelPreparation(); releaseMediaAdmission(); cancelScheduled();
      return renderer.restorePartySurface();
    }
  }

  @Override public boolean surfaceSelected(String id, long generation)
  { return !closed && id.equals(selectedSurfaceId) && renderer.hasPartySurface() && renderer.getPartyPathGeneration() == generation; }

  @Override public long skip(String action) { return ui.getVideoFrame().partySkipDelta(action); }
  @Override public void progressOwner(boolean owned)
  {
    if (!owned) restoreSurface();
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
