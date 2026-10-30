package sage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.Test;
import sage.epg.sd.gson.JsonObject;
import sage.epg.sd.gson.JsonParser;
import sage.party.PartyProtocol;
import sage.party.PartyService;
import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

/** Exercises the actual MiniClient inbound dispatcher, NG adapter, service and outbound property. */
public class NgPartyTransportIntegrationTest
{
  @org.testng.annotations.BeforeClass
  public void initializeServerStatics() throws Throwable { TestUtils.initializeSageTVForTesting(); }

  private static final class Transport implements AutoCloseable
  {
    final MiniClientSageRenderer renderer = mock(MiniClientSageRenderer.class, CALLS_REAL_METHODS);
    final UIManager ui = mock(UIManager.class);
    final VideoFrame video = mock(VideoFrame.class);
    final BlockingQueue<JsonObject> output = new LinkedBlockingQueue<>();
    final java.util.List<JsonObject> observed = new java.util.concurrent.CopyOnWriteArrayList<>();
    final NgPartyConnection connection;
    final Method inbound;
    JsonObject available;
    long sequence;

    Transport(PartyService service, String mode) throws Exception
    { this(service, mode, () -> 100000); }

    Transport(PartyService service, String mode, java.util.function.LongSupplier time) throws Exception
    {
      doReturn(video).when(ui).getVideoFrame();
      doReturn("server-admitted-lan").when(renderer).getPartyPrincipal();
      doReturn(PartyProtocol.REQUIRED).when(renderer).getClientCapabilities();
      doReturn(mode).when(renderer).getCurrentSurfaceDeliveryMode();
      doAnswer(call -> {
        JsonObject message = new JsonParser().parse((String)call.getArgument(1)).getAsJsonObject();
        observed.add(message); output.add(message);
        return null;
      }).when(renderer).sendSetPropertyAsync(anyString(), anyString());
      connection = spy(new NgPartyConnection(renderer, ui, service, time));
      doReturn(3600000L).when(connection).duration(42);
      PartyService.Delivery path = new PartyService.Delivery(mode, mode, "H264", "AAC", 8000, false);
      doReturn(path).when(connection).plan(42);
      Field field = MiniClientSageRenderer.class.getDeclaredField("partyConnection");
      field.setAccessible(true); field.set(renderer, connection);
      inbound = MiniClientSageRenderer.class.getDeclaredMethod("handleInboundClientSetProperty", byte[].class);
      inbound.setAccessible(true);
      available = exchange("HELLO", new JsonObject());
    }

    JsonObject exchange(String type, JsonObject fields) throws Exception
    {
      fields.addProperty("schemaVersion", 1); fields.addProperty("type", type);
      String id = "r" + ++sequence;
      fields.addProperty("requestId", id); fields.addProperty("requestSeq", sequence);
      if (available != null)
      {
        fields.add("serverEpoch", available.get("serverEpoch"));
        fields.add("sessionToken", available.get("sessionToken"));
      }
      byte[] name = PartyProtocol.PROPERTY.getBytes(StandardCharsets.US_ASCII);
      byte[] json = fields.toString().getBytes(StandardCharsets.UTF_8);
      byte[] payload = ByteBuffer.allocate(4 + name.length + json.length).putShort((short)name.length)
          .putShort((short)json.length).put(name).put(json).array();
      assertEquals(inbound.invoke(renderer, (Object)payload), true);
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (System.nanoTime() < until)
      {
        JsonObject next = output.poll(1, TimeUnit.SECONDS);
        if (next != null && next.has("requestId") && id.equals(next.get("requestId").getAsString()))
          return next;
      }
      throw new AssertionError("Missing Party reply " + type);
    }

    @Override public void close() { connection.disconnect(); }
  }

  @Test public void actualRendererDispatchCreatesJoinsAndIsolatesSessionTokens() throws Exception
  {
    PartyService service = new PartyService(() -> 100000, true, 100000, 2);
    try (Transport host = new Transport(service, "push"); Transport guest = new Transport(service, "pull-xcode"))
    {
      JsonObject create = new JsonObject(); create.addProperty("mediaFileId", 42);
      JsonObject created = host.exchange("CREATE", create);
      assertEquals(created.get("type").getAsString(), "CREATED");
      verify(host.video).setPartyProgressOwned(true);
      JsonObject join = new JsonObject();
      join.add("partyId", created.get("partyId")); join.addProperty("mediaFileId", 42);
      join.add("joinCode", created.get("joinCode"));
      JsonObject joined = guest.exchange("JOIN", join);
      assertEquals(joined.get("type").getAsString(), "JOINED");
      assertEquals(joined.getAsJsonObject("snapshot").getAsJsonArray("roster").size(), 2);
      verify(guest.video).setPartyProgressOwned(true);
      assertNotEquals(created.get("participantId"), joined.get("participantId"));
      JsonObject otherToken = guest.available;
      guest.available = host.available;
      JsonObject snapshot = new JsonObject(); snapshot.add("partyId", created.get("partyId"));
      assertEquals(guest.exchange("SNAPSHOT", snapshot).get("code").getAsString(), "SESSION_DENIED");
      guest.available = otherToken;
      JsonObject leave = new JsonObject(); leave.add("partyId", created.get("partyId"));
      assertEquals(guest.exchange("LEAVE", leave).get("type").getAsString(), "LEFT");
      verify(guest.video).setPartyProgressOwned(false);
    }
  }

  @Test public void authenticatedNegotiatedRendererRoundTripAndLegacyRegression() throws Exception
  {
    MiniClientSageRenderer renderer = mock(MiniClientSageRenderer.class, CALLS_REAL_METHODS);
    UIManager ui = mock(UIManager.class);
    doReturn("verified-principal").when(renderer).getPartyPrincipal();
    doReturn(PartyProtocol.REQUIRED).when(renderer).getClientCapabilities();
    BlockingQueue<JsonObject> output = new LinkedBlockingQueue<>();
    doAnswer(call -> {
      assertEquals(call.getArgument(0), PartyProtocol.PROPERTY);
      output.add(new JsonParser().parse((String)call.getArgument(1)).getAsJsonObject());
      return null;
    }).when(renderer).sendSetPropertyAsync(anyString(), anyString());
    PartyService service = new PartyService(() -> 100000, true, 100000, 2);
    NgPartyConnection connection = new NgPartyConnection(renderer, ui, service);
    Field field = MiniClientSageRenderer.class.getDeclaredField("partyConnection");
    field.setAccessible(true); field.set(renderer, connection);
    Method inbound = MiniClientSageRenderer.class.getDeclaredMethod("handleInboundClientSetProperty", byte[].class);
    inbound.setAccessible(true);
    try
    {
      byte[] json = "{\"schemaVersion\":1,\"type\":\"HELLO\",\"requestId\":\"h1\",\"requestSeq\":1}"
          .getBytes(StandardCharsets.US_ASCII);
      byte[] name = PartyProtocol.PROPERTY.getBytes(StandardCharsets.US_ASCII);
      byte[] frame = ByteBuffer.allocate(4 + name.length + json.length).putShort((short)name.length)
          .putShort((short)json.length).put(name).put(json).array();
      assertEquals(inbound.invoke(renderer, (Object)frame), true);
      JsonObject available = output.poll(5, TimeUnit.SECONDS);
      assertNotNull(available);
      assertEquals(available.get("type").getAsString(), "AVAILABLE");
      assertTrue(available.has("serverEpoch"));
      doReturn(Set.of("PARTY_V1")).when(renderer).getClientCapabilities();
      inbound.invoke(renderer, (Object)frame);
      assertEquals(output.poll(5, TimeUnit.SECONDS).get("code").getAsString(), "NOT_AVAILABLE");
      doReturn(PartyProtocol.REQUIRED).when(renderer).getClientCapabilities();
      doReturn(null).when(renderer).getPartyPrincipal();
      inbound.invoke(renderer, (Object)frame);
      assertEquals(output.poll(5, TimeUnit.SECONDS).get("code").getAsString(), "NOT_AVAILABLE");
      assertEquals(inbound.invoke(renderer, (Object)new byte[] {0, 1, 0, 0, 'X'}), false);
    }
    finally { connection.disconnect(); }
  }

  @Test public void actualAdmittedLanAuthorizationIsInvalidatedWithTheRenderer() throws Exception
  {
    MiniClientSageRenderer renderer = mock(MiniClientSageRenderer.class, CALLS_REAL_METHODS);
    Field initialized = MiniClientSageRenderer.class.getDeclaredField("partySessionInitialized");
    Field admitted = MiniClientSageRenderer.class.getDeclaredField("partyLanAuthorized");
    initialized.setAccessible(true); admitted.setAccessible(true);
    assertNull(renderer.getPartyPrincipal());
    admitted.set(renderer, true);
    assertNull(renderer.getPartyPrincipal(), "LAN policy branch alone is not completed admission");
    initialized.set(renderer, true);
    assertEquals(renderer.getPartyPrincipal(), "server-admitted-lan");
    Method invalidate = MiniClientSageRenderer.class.getDeclaredMethod("invalidatePartyAuthorization");
    invalidate.setAccessible(true); invalidate.invoke(renderer);
    assertNull(renderer.getPartyPrincipal());
    assertFalse(admitted.getBoolean(renderer));
    assertFalse(initialized.getBoolean(renderer));
  }

  @Test public void serverOwnedPushSchedulesOnlyItsViewerAndPullRemainsClientExecuted() throws Exception
  {
    MiniClientSageRenderer renderer = mock(MiniClientSageRenderer.class);
    UIManager ui = mock(UIManager.class);
    VideoFrame video = mock(VideoFrame.class);
    MediaFile media = mock(MediaFile.class);
    doReturn(video).when(ui).getVideoFrame();
    doReturn(media).when(video).getCurrFile();
    doReturn(42).when(media).getID();
    doReturn("push").when(renderer).getCurrentSurfaceDeliveryMode();
    java.util.concurrent.CountDownLatch applied = new java.util.concurrent.CountDownLatch(1);
    doAnswer(call -> { applied.countDown(); return null; })
        .when(video).applyPartyTimeline(42, 10000, true);
    PartyService service = new PartyService(() -> 100000, true, 100000, 2);
    NgPartyConnection connection = new NgPartyConnection(renderer, ui, service, () -> 100000);
    try
    {
      JsonObject command = PartyProtocol.envelope("COMMAND");
      command.addProperty("executeAtServerMonoMs", 100000);
      command.addProperty("targetPositionMs", 10000);
      command.addProperty("targetState", "PLAYING");
      connection.schedule(command);
      assertTrue(applied.await(5, TimeUnit.SECONDS));
      verify(video).applyPartyTimeline(42, 10000, true);
      clearInvocations(video);
      doReturn("pull-xcode").when(renderer).getCurrentSurfaceDeliveryMode();
      connection.schedule(command);
      connection.cancelScheduled();
      verify(video, never()).applyPartyTimeline(anyInt(), anyLong(), anyBoolean());
      doReturn("push").when(renderer).getCurrentSurfaceDeliveryMode();
      command.addProperty("executeAtServerMonoMs", 200000);
      connection.schedule(command);
      connection.cancelScheduled();
      verify(video, never()).applyPartyTimeline(anyInt(), anyLong(), anyBoolean());
    }
    finally { connection.disconnect(); }
  }

  private static void calibrate(Transport transport, JsonObject created, long now) throws Exception
  {
    for (int i = 0; i < 5; i++)
    {
      JsonObject fields = new JsonObject(); fields.add("partyId", created.get("partyId"));
      JsonObject probe = transport.exchange("CLOCK", fields);
      fields = new JsonObject(); fields.add("partyId", created.get("partyId"));
      fields.add("probeId", probe.get("probeId"));
      fields.addProperty("c1", now - 100); fields.addProperty("c2", now - 100);
      transport.exchange("CLOCK_REPLY", fields);
    }
  }

  @Test public void rendererReadyRequiresActualProducerAdmissionAndReportsExecutionOutcomes() throws Exception
  {
    java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(100000);
    PartyService service = new PartyService(now::get, true, 100000, 2);
    try (Transport client = new Transport(service, "pull-xcode", now::get))
    {
      MediaFile media = mock(MediaFile.class); doReturn(42).when(media).getID();
      MiniPlayer player = mock(MiniPlayer.class);
      doReturn(media).when(client.video).getCurrFile();
      doReturn(MediaPlayer.PAUSE_STATE).when(client.video).getPlayerState();
      for (String name : new String[] {"player", "currFile", "partyProgressOwned"})
      {
        Field field = VideoFrame.class.getDeclaredField(name); field.setAccessible(true);
        field.set(client.video, "player".equals(name) ? player : "currFile".equals(name) ? media : true);
      }
      doCallRealMethod().when(client.video).issuePartyMediaAdmission(client.renderer, client.connection);
      doReturn(new PartyService.Delivery("pwa", "pull-xcode", "H264", "AAC", 8000, true))
          .when(client.video).getPartyDelivery(client.renderer);
      JsonObject create = new JsonObject(); create.addProperty("mediaFileId", 42);
      JsonObject created = client.exchange("CREATE", create);
      JsonObject fields = new JsonObject(); fields.add("partyId", created.get("partyId"));
      JsonObject handle = client.exchange("MEDIA_ADMISSION", fields);
      assertEquals(handle.get("type").getAsString(), "MEDIA_ADMISSION_HANDLE");
      MediaServer.Connection producer = mock(MediaServer.class).new Connection(null);
      FFMPEGTranscoder engine = mock(FFMPEGTranscoder.class);
      producer.currMF = media; producer.xcoder = engine;
      assertTrue(producer.bindPartyAdmission(handle.get("handle").getAsString()));
      calibrate(client, created, now.get());
      JsonObject ready = new JsonObject();
      ready.add("partyId", created.get("partyId")); ready.addProperty("stateVersion", 0);
      ready.addProperty("prepared", true); ready.addProperty("activated", true);
      ready.addProperty("bufferAheadMs", 2000); ready.addProperty("precisionMs", 50);
      ready.addProperty("rateAdjust", false);
      assertEquals(client.exchange("READY", PartyProtocol.copy(ready)).get("code").getAsString(), "MEDIA_ADMISSION_UNCONFIRMED");
      doReturn(true).when(engine).isTranscoding(); doReturn(2048L).when(engine).getVirtualTranscodeSize();
      JsonObject correction = client.exchange("READY", ready);
      assertEquals(correction.get("type").getAsString(), "CORRECTION");
      now.set(correction.get("executeAtServerMonoMs").getAsLong());
      fields = new JsonObject(); fields.add("partyId", created.get("partyId"));
      fields.addProperty("stateVersion", 0); fields.add("correctionId", correction.get("correctionId"));
      fields.addProperty("success", true); fields.addProperty("sampleMonoMs", now.get() - 100);
      fields.addProperty("positionMs", 0);
      client.exchange("CORRECTION_RESULT", fields);
      fields = new JsonObject(); fields.add("partyId", created.get("partyId"));
      fields.addProperty("stateVersion", 0); fields.addProperty("action", "PLAY");
      JsonObject command = client.exchange("ACTION", fields);
      now.set(command.get("executeAtServerMonoMs").getAsLong() + 20);
      fields = new JsonObject(); fields.add("partyId", created.get("partyId"));
      fields.add("stateVersion", command.get("stateVersion")); fields.add("commandSeq", command.get("commandSeq"));
      fields.addProperty("stage", "EXECUTED"); fields.addProperty("success", true);
      fields.addProperty("sampleMonoMs", now.get() - 100); fields.addProperty("positionMs", 20);
      fields.addProperty("state", "PLAYING");
      assertEquals(client.exchange("ACK", fields).get("type").getAsString(), "ACK");
      assertTrue(client.observed.stream().anyMatch(m -> "COMMAND_OUTCOME".equals(m.get("type").getAsString())
          && "COMPLETE".equals(m.get("outcome").getAsString()) && m.get("successCount").getAsInt() == 1));
      doReturn(1L).when(player).getPartyPlaybackGeneration();
      assertFalse(client.connection.mediaAdmitted(42), "Reloading identical media still revokes old generation");
      assertFalse(producer.bindPartyAdmission(handle.get("handle").getAsString()));
    }
  }

  @Test public void pushAdmissionDoesNotSurviveReloadingIdenticalMedia() throws Exception
  {
    PartyService service = new PartyService(() -> 100000, true, 100000, 2);
    try (Transport client = new Transport(service, "push"))
    {
      MediaFile media = mock(MediaFile.class); doReturn(42).when(media).getID();
      MiniPlayer player = mock(MiniPlayer.class);
      doReturn(media).when(client.video).getCurrFile();
      doReturn(MediaPlayer.PLAY_STATE).when(client.video).getPlayerState();
      doReturn(player).when(client.video).getPartyPlayer();
      doReturn(true).when(client.video).hasPartyPushAdmission();
      doReturn(new PartyService.Delivery("android", "push", "H264", "AAC", 8000, false))
          .when(client.video).getPartyDelivery(client.renderer);
      assertTrue(client.connection.mediaAdmitted(42));
      client.connection.delivery(42);
      doReturn(1L).when(player).getPartyPlaybackGeneration();
      assertFalse(client.connection.mediaAdmitted(42));
      client.connection.releaseMediaAdmission();
      assertTrue(client.connection.mediaAdmitted(42), "A fresh readiness barrier may admit the new generation");
    }
  }

  private static void videoField(VideoFrame video, String name, Object value) throws Exception
  {
    Field field = (java.util.Set.of("queueLock", "uiMgr").contains(name)
        ? BasicVideoFrame.class : VideoFrame.class).getDeclaredField(name);
    field.setAccessible(true); field.set(video, value);
  }

  @Test public void currentPositionUsesOnlySameRendererSameMediaGenerationAndClampsServerTime() throws Exception
  {
    PartyService service = new PartyService(() -> 100000, true, 100000, 2);
    try (Transport client = new Transport(service, "pull"))
    {
      MediaFile media = mock(MediaFile.class);
      MiniPlayer player = mock(MiniPlayer.class);
      doReturn(42).when(media).getID(); doReturn(1).when(media).getNumSegments();
      doReturn(900000L).when(media).getRecordDuration(); doReturn(500000L).when(media).getStart(0);
      doReturn(MediaPlayer.PLAY_STATE).when(player).getState();
      videoField(client.video, "player", player); videoField(client.video, "currFile", media);
      doCallRealMethod().when(client.video).getPartyPlayer();
      doCallRealMethod().when(client.video).partyCurrentPosition(anyInt());
      doReturn(true).when(client.video).isMediaPlayerLoaded();
      doReturn(623456L).when(client.video).getMediaTimeMillis(false);
      assertEquals(client.connection.currentPosition(42), 123456);
      assertThrows(PartyProtocol.Fault.class, () -> client.video.partyCurrentPosition(99));
      doReturn(499999L).when(client.video).getMediaTimeMillis(false);
      assertEquals(client.connection.currentPosition(42), 0);
      doReturn(Long.MAX_VALUE).when(client.video).getMediaTimeMillis(false);
      assertEquals(client.connection.currentPosition(42), 900000);
      doAnswer(call -> { doReturn(1L).when(player).getPartyPlaybackGeneration(); return 623456L; })
          .when(client.video).getMediaTimeMillis(false);
      assertThrows(PartyProtocol.Fault.class, () -> client.connection.currentPosition(42));
      doReturn(MediaPlayer.STOPPED_STATE).when(player).getState();
      assertThrows(PartyProtocol.Fault.class, () -> client.connection.currentPosition(42));
    }
  }

  @Test public void actualRendererCreateCurrentPositionRemainsPausedAndDefaultBeginningDoesNotSample() throws Exception
  {
    PartyService service = new PartyService(() -> 100000, true, 100000, 2);
    try (Transport client = new Transport(service, "pull"))
    {
      java.util.Set<String> caps = new java.util.HashSet<>(PartyProtocol.REQUIRED);
      caps.add("PARTY_START_POSITION_V1");
      doReturn(caps).when(client.renderer).getClientCapabilities();
      doReturn(456789L).when(client.video).partyCurrentPosition(42);
      JsonObject create = new JsonObject(); create.addProperty("mediaFileId", 42);
      create.addProperty("startMode", "CURRENT_POSITION");
      JsonObject created = client.exchange("CREATE", create);
      assertEquals(created.get("startPositionMs").getAsLong(), 456789);
      assertEquals(created.getAsJsonObject("snapshot").get("state").getAsString(), "PAUSED");
      verify(client.video).partyCurrentPosition(42);
      JsonObject leave = new JsonObject(); leave.add("partyId", created.get("partyId"));
      client.exchange("LEAVE", leave);
      clearInvocations(client.video);
      create = new JsonObject(); create.addProperty("mediaFileId", 42);
      assertEquals(client.exchange("CREATE", create).get("startPositionMs").getAsLong(), 0);
      verify(client.video, never()).partyCurrentPosition(anyInt());
    }
  }

  @Test public void explicitRendererPrepareUsesOnlyCanonicalMediaAndAsyncWatchResult() throws Exception
  {
    PartyService service = new PartyService(() -> 100000, true, 100000, 2);
    try (Transport client = new Transport(service, "pull"))
    {
      java.util.Set<String> caps = new java.util.HashSet<>(PartyProtocol.REQUIRED);
      caps.add("PARTY_PREPARE_V1"); doReturn(caps).when(client.renderer).getClientCapabilities();
      JsonObject create = new JsonObject(); create.addProperty("mediaFileId", 42);
      JsonObject created = client.exchange("CREATE", create);
      MediaFile media = mock(MediaFile.class); doReturn(42).when(media).getID();
      doReturn(media).when(client.connection).preparationMedia(42);
      videoField(client.video, "partyProgressOwned", true);
      doCallRealMethod().when(client.video).cancelPartyPreparation();
      doCallRealMethod().when(client.video).preparePartyMedia(any(), anyString(), any());
      doReturn(VideoFrame.WATCH_FAILED_PARENTAL_CHECK_FAILED).when(client.video).watchPartyMedia(any());
      verify(client.video, never()).watchPartyMedia(any());
      JsonObject fields = new JsonObject(); fields.add("partyId", created.get("partyId"));
      fields.addProperty("intent", "JOIN_AND_PREPARE"); fields.addProperty("confirmReplace", true);
      JsonObject accepted = client.exchange("PREPARE", fields);
      assertEquals(accepted.get("type").getAsString(), "PREPARE_ACCEPTED");
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (client.observed.stream().noneMatch(m -> "PREPARATION_RESULT".equals(m.get("type").getAsString()))
          && System.nanoTime() < until) Thread.sleep(10);
      JsonObject failure = client.observed.stream().filter(m -> "PREPARATION_RESULT".equals(m.get("type").getAsString()))
          .findFirst().orElseThrow();
      assertEquals(failure.get("watchCode").getAsInt(), VideoFrame.WATCH_FAILED_PARENTAL_CHECK_FAILED);
      assertEquals(failure.get("preparationId"), accepted.get("preparationId"));
      verify(client.video).watchPartyMedia(argThat(stage -> stage.media == media && stage.held));
      verify(client.video, never()).play();
    }
  }

  @Test public void preparingAlreadyLoadedSameMediaUsesOrdinaryWatchShortcutPausedAndExactGeneration() throws Exception
  {
    PartyService service = new PartyService(() -> 100000, true, 100000, 2);
    try (Transport client = new Transport(service, "pull"))
    {
      java.util.Set<String> caps = new java.util.HashSet<>(PartyProtocol.REQUIRED);
      caps.add("PARTY_PREPARE_V1"); doReturn(caps).when(client.renderer).getClientCapabilities();
      JsonObject create = new JsonObject(); create.addProperty("mediaFileId", 42);
      JsonObject created = client.exchange("CREATE", create);
      MediaFile media = mock(MediaFile.class); Airing airing = mock(Airing.class);
      MiniPlayer player = mock(MiniPlayer.class);
      java.util.concurrent.atomic.AtomicInteger state = new java.util.concurrent.atomic.AtomicInteger(MediaPlayer.PLAY_STATE);
      doReturn(42).when(media).getID(); doReturn(airing).when(media).getContentAiring();
      doReturn(7L).when(player).getPartyPlaybackGeneration();
      doAnswer(call -> state.get()).when(player).getState();
      doAnswer(call -> { state.set(MediaPlayer.PAUSE_STATE); return true; }).when(player).pause();
      videoField(client.video, "partyProgressOwned", true);
      videoField(client.video, "currFile", media); videoField(client.video, "player", player);
      videoField(client.video, "queueLock", new Object());
      videoField(client.video, "watchQueue", new java.util.Vector<>());
      doReturn(media).when(client.connection).preparationMedia(42);
      doCallRealMethod().when(client.video).getPartyPlayer();
      doCallRealMethod().when(client.video).cancelPartyPreparation();
      doCallRealMethod().when(client.video).preparePartyMedia(any(), anyString(), any());
      doCallRealMethod().when(client.video).watchPartyMedia(any());
      doCallRealMethod().when(client.video).isPartyPreparationLoaded(any());
      JsonObject fields = new JsonObject(); fields.add("partyId", created.get("partyId"));
      fields.addProperty("intent", "JOIN_AND_PREPARE"); fields.addProperty("confirmReplace", true);
      JsonObject accepted = client.exchange("PREPARE", fields);
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (client.observed.stream().noneMatch(m -> "PREPARATION_RESULT".equals(m.get("type").getAsString()))
          && System.nanoTime() < until) Thread.sleep(10);
      JsonObject loaded = client.observed.stream().filter(m -> "PREPARATION_RESULT".equals(m.get("type").getAsString()))
          .findFirst().orElseThrow();
      assertEquals(loaded.get("preparationId"), accepted.get("preparationId"));
      assertEquals(loaded.get("status").getAsString(), "LOADED");
      assertEquals(loaded.get("playbackGeneration").getAsLong(), 7);
      verify(player).pause(); verify(player, never()).play();
      assertEquals(state.get(), MediaPlayer.PAUSE_STATE);
      VideoFrame.PartyPreparation stage = new VideoFrame.PartyPreparation(media, "old", () -> true);
      stage.loaded = true; stage.generation = 6;
      assertFalse(client.video.isPartyPreparationLoaded(stage), "Same file with another generation is not completion");
    }
  }

  @Test public void stagedNewLoadSeeksButCannotAutoplayWhileNormalLoadStillPlays() throws Exception
  {
    for (boolean staged : new boolean[] {true, false})
    {
      VideoFrame video = mock(VideoFrame.class); MediaFile media = mock(MediaFile.class);
      MiniPlayer player = mock(MiniPlayer.class); UIManager ui = mock(UIManager.class);
      videoField(video, "uiMgr", ui); videoField(video, "player", player);
      videoField(video, "currFile", media); videoField(video, "segment", -1);
      doReturn(100000L).when(media).getRecordTime(); doReturn(900000L).when(media).getRecordEnd();
      doReturn(100000L).when(media).getStart(0); doReturn(900000L).when(media).getEnd(0);
      doReturn(new java.io.File("party-completed.ts")).when(media).getFile(0);
      doReturn(MediaPlayer.PLAY_STATE).when(player).getState();
      java.lang.reflect.Method select = VideoFrame.class.getDeclaredMethod("timeSelected", long.class, boolean.class, boolean.class);
      select.setAccessible(true);
      assertEquals(select.invoke(video, 112345L, true, staged), true);
      verify(player).seek(12345L);
      verify(player).load(anyByte(), anyByte(), nullable(String.class), any(java.io.File.class),
          nullable(String.class), eq(false), eq(0L));
      if (staged) { verify(player).pause(); verify(player, never()).play(); }
      else { verify(player).play(); verify(player, never()).pause(); }
    }
  }

  @Test public void preparationResourcePermissionAndAsyncLoadFailuresNeverReportLoaded() throws Exception
  {
    for (int failureCode : new int[] {VideoFrame.WATCH_FAILED_INSUFFICIENT_RESOURCES_WHILE_RECORDING,
        VideoFrame.WATCH_FAILED_INSUFFICIENT_PERMISSIONS, VideoFrame.WATCH_FAILED_UNKNOWN})
    {
      PartyService service = new PartyService(() -> 100000, true, 100000, 2);
      try (Transport client = new Transport(service, "pull"))
      {
        java.util.Set<String> caps = new java.util.HashSet<>(PartyProtocol.REQUIRED);
        caps.add("PARTY_PREPARE_V1"); doReturn(caps).when(client.renderer).getClientCapabilities();
        JsonObject create = new JsonObject(); create.addProperty("mediaFileId", 42);
        JsonObject created = client.exchange("CREATE", create);
        MediaFile media = mock(MediaFile.class); doReturn(media).when(client.connection).preparationMedia(42);
        videoField(client.video, "partyProgressOwned", true);
        doCallRealMethod().when(client.video).cancelPartyPreparation();
        doCallRealMethod().when(client.video).preparePartyMedia(any(), anyString(), any());
        java.util.concurrent.atomic.AtomicReference<VideoFrame.PartyPreparation> pending = new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(call -> {
          pending.set(call.getArgument(0));
          return failureCode == VideoFrame.WATCH_FAILED_UNKNOWN ? VideoFrame.WATCH_OK : failureCode;
        }).when(client.video).watchPartyMedia(any());
        JsonObject fields = new JsonObject(); fields.add("partyId", created.get("partyId"));
        fields.addProperty("intent", "JOIN_AND_PREPARE"); fields.addProperty("confirmReplace", true);
        JsonObject accepted = client.exchange("PREPARE", fields);
        if (failureCode == VideoFrame.WATCH_FAILED_UNKNOWN)
        {
          long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
          while (pending.get() == null && System.nanoTime() < until) Thread.sleep(10);
          assertNotNull(pending.get());
          pending.get().watchCode = VideoFrame.WATCH_FAILED_UNKNOWN;
        }
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (client.observed.stream().noneMatch(m -> "PREPARATION_RESULT".equals(m.get("type").getAsString()))
            && System.nanoTime() < until) Thread.sleep(10);
        JsonObject failure = client.observed.stream().filter(m -> "PREPARATION_RESULT".equals(m.get("type").getAsString()))
            .findFirst().orElseThrow();
        assertEquals(failure.get("preparationId"), accepted.get("preparationId"));
        assertEquals(failure.get("status").getAsString(), "FAILED");
        assertEquals(failure.get("watchCode").getAsInt(), failureCode);
        assertFalse(failure.has("playbackGeneration"));
        assertFalse(pending.get().active());
      }
    }
  }
}
