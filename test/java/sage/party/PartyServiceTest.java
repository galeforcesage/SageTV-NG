package sage.party;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.testng.annotations.Test;
import sage.epg.sd.gson.JsonObject;
import static org.testng.Assert.*;
import static sage.party.PartyProtocol.*;

/** Executable replacement-contract clients using production framing and service logic. */
public class PartyServiceTest
{
  private static final Set<String> CAPABILITIES = REQUIRED;

  private static final class Client implements PartyService.Peer
  {
    final PartyService service;
    final AtomicLong now;
    final List<JsonObject> messages = new ArrayList<>();
    String epoch;
    String sessionHandle;
    String sessionToken;
    String partyId;
    String participantId;
    String recoveryToken;
    String joinCode;
    long requestSeq;
    int prepares;
    int preparationCancels;
    boolean preparedDeliveryFails;
    boolean preparationOwner;
    boolean progressOwner;
    long preparedGeneration = -1;
    String preparationId;
    long preparationPosition;
    PartyPlaybackDirective scheduled;
    int schedules;
    PartyService.Delivery delivery =
        new PartyService.Delivery("android", "push", "H264", "AAC", 8000, false);

    Client(PartyService service, AtomicLong now)
    {
      this.service = service;
      this.now = now;
    }

    JsonObject request(String type)
    {
      JsonObject out = envelope(type);
      out.addProperty("requestId", "r" + ++requestSeq);
      out.addProperty("requestSeq", requestSeq);
      if (epoch != null) out.addProperty("serverEpoch", epoch);
      if (sessionToken != null) out.addProperty("sessionToken", sessionToken);
      if (partyId != null) out.addProperty("partyId", partyId);
      return out;
    }

    JsonObject exchange(JsonObject request)
    {
      byte[] name = PROPERTY.getBytes(StandardCharsets.US_ASCII);
      byte[] json = request.toString().getBytes(StandardCharsets.UTF_8);
      ByteBuffer payload = ByteBuffer.allocate(4 + name.length + json.length);
      payload.putShort((short)name.length).putShort((short)json.length).put(name).put(json);
      assertTrue(PartyWire.accept(payload.array(), text -> service.receive(this, text)));
      String requestId = request.get("requestId").getAsString();
      for (int i = messages.size() - 1; i >= 0; i--)
        if (messages.get(i).has("requestId")
            && requestId.equals(messages.get(i).get("requestId").getAsString()))
          return messages.get(i);
      fail("missing response for " + requestId);
      return null;
    }

    void hello()
    {
      JsonObject available = exchange(request("HELLO"));
      assertEquals(available.get("type").getAsString(), "AVAILABLE");
      epoch = available.get("serverEpoch").getAsString();
      sessionHandle = available.get("sessionHandle").getAsString();
      sessionToken = available.get("sessionToken").getAsString();
    }

    JsonObject create()
    {
      hello();
      JsonObject request = request("CREATE");
      request.addProperty("mediaFileId", 42);
      JsonObject created = exchange(request);
      remember(created);
      joinCode = created.get("joinCode").getAsString();
      return created;
    }

    JsonObject join(Client host)
    {
      hello();
      JsonObject request = request("JOIN");
      request.addProperty("joinCode", host.joinCode);
      JsonObject joined = exchange(request);
      remember(joined);
      return joined;
    }

    void remember(JsonObject joined)
    {
      partyId = joined.get("partyId").getAsString();
      participantId = joined.get("participantId").getAsString();
      recoveryToken = joined.get("recoveryToken").getAsString();
    }

    JsonObject snapshot()
    {
      return exchange(request("SNAPSHOT"));
    }

    void calibrate()
    {
      for (int i = 0; i < 5; i++)
      {
        now.addAndGet(1000);
        JsonObject probe = exchange(request("CLOCK"));
        long s0 = probe.get("s0").getAsLong();
        now.addAndGet(10);
        JsonObject reply = request("CLOCK_REPLY");
        reply.addProperty("probeId", probe.get("probeId").getAsString());
        reply.addProperty("c1", s0 - 95);
        reply.addProperty("c2", s0 - 95);
        JsonObject model = exchange(reply);
        if (i == 4) assertTrue(model.get("confident").getAsBoolean());
      }
    }

    void prepareAndReady()
    {
      JsonObject accepted = exchange(request("PREPARE"));
      service.preparationResult(this, accepted.get("preparationId").getAsString(), 0, 1);
      calibrate();
      JsonObject ready = request("READY");
      ready.addProperty("stateVersion", snapshot().get("stateVersion").getAsLong());
      ready.addProperty("bufferAheadMs", 0);
      ready.addProperty("requestId", "r" + ++requestSeq);
      ready.addProperty("requestSeq", requestSeq);
      JsonObject correction = exchange(ready);
      assertEquals(correction.get("type").getAsString(), "CORRECTION");
      presented(correction, "PAUSED", correction.get("targetPositionMs").getAsLong());
    }

    JsonObject action(String action)
    {
      JsonObject request = request("ACTION");
      request.addProperty("stateVersion", snapshot().get("stateVersion").getAsLong());
      request.addProperty("action", action);
      request.addProperty("requestId", "r" + ++requestSeq);
      request.addProperty("requestSeq", requestSeq);
      return exchange(request);
    }

    JsonObject heartbeat(String state, long position)
    {
      now.addAndGet(1000);
      JsonObject request = request("HEARTBEAT");
      request.addProperty("stateVersion", snapshot().get("stateVersion").getAsLong());
      request.addProperty("sampleMonoMs", now.get() - 100);
      request.addProperty("positionMs", position);
      request.addProperty("bufferAheadMs", "BUFFERING".equals(state) ? 0 : 2000);
      request.addProperty("state", state);
      request.addProperty("requestId", "r" + ++requestSeq);
      request.addProperty("requestSeq", requestSeq);
      return exchange(request);
    }

    JsonObject presented(JsonObject directive, String state, long position)
    {
      if (directive.has("barrier") && directive.get("barrier").getAsBoolean())
      {
        arm(directive, position);
        JsonObject reveal = event("REVEAL");
        assertNotNull(reveal);
        directive = copy(directive);
        directive.addProperty("executeAtServerMonoMs",
            reveal.get("executeAtServerMonoMs").getAsLong());
      }
      now.set(Math.max(now.get() + 1,
          directive.get("executeAtServerMonoMs").getAsLong() + 100));
      service.tick();
      JsonObject request = request("ACK");
      request.addProperty("stateVersion", directive.get("stateVersion").getAsLong());
      request.addProperty("commandSeq", directive.get("commandSeq").getAsLong());
      request.addProperty("directiveId", directive.get("directiveId").getAsString());
      request.addProperty("stage", "PRESENTED");
      request.addProperty("success", true);
      request.addProperty("sampleMonoMs", now.get() - 100);
      request.addProperty("positionMs", position);
      request.addProperty("state", state);
      request.addProperty("requestId", "r" + ++requestSeq);
      request.addProperty("requestSeq", requestSeq);
      JsonObject response = exchange(request);
      assertEquals(response.get("type").getAsString(), "ACK", response.toString());
      return response;
    }

    JsonObject arm(JsonObject directive, long position)
    {
      now.set(Math.max(now.get() + 1,
          directive.get("executeAtServerMonoMs").getAsLong() + 100));
      service.tick();
      JsonObject armed = request("ACK");
      armed.addProperty("stateVersion", directive.get("stateVersion").getAsLong());
      armed.addProperty("commandSeq", directive.get("commandSeq").getAsLong());
      armed.addProperty("directiveId", directive.get("directiveId").getAsString());
      armed.addProperty("stage", "ARMED");
      armed.addProperty("success", true);
      armed.addProperty("sampleMonoMs", now.get() - 100);
      armed.addProperty("positionMs", position);
      armed.addProperty("state", "PAUSED");
      armed.addProperty("requestId", "r" + ++requestSeq);
      armed.addProperty("requestSeq", requestSeq);
      JsonObject response = exchange(armed);
      assertEquals(response.get("type").getAsString(), "ACK", response.toString());
      return response;
    }

    JsonObject event(String type)
    {
      for (int i = messages.size() - 1; i >= 0; i--)
        if (type.equals(messages.get(i).get("type").getAsString())
            && !messages.get(i).has("requestId"))
          return messages.get(i);
      return null;
    }

    int eventCount(String type)
    {
      int count = 0;
      for (JsonObject message : messages)
        if (type.equals(message.get("type").getAsString())
            && !message.has("requestId"))
          count++;
      return count;
    }

    @Override public String principal() { return "verified-user"; }
    @Override public Set<String> capabilities() { return CAPABILITIES; }
    @Override public void send(JsonObject message) { messages.add(copy(message)); }
    @Override public int currentMediaFileId() { return 42; }
    @Override public long duration(int mediaFileId) { return 3600000; }
    @Override public long currentPosition(int mediaFileId) { return 120000; }
    public PartyService.Delivery plan(int mediaFileId) { return delivery; }
    @Override public PartyService.Delivery delivery(int mediaFileId) { return delivery; }
    @Override public PartyService.Delivery preparedDelivery(int mediaFileId)
    {
      if (preparedDeliveryFails) throw new PartyProtocol.Fault("MEDIA_PATH_NOT_READY");
      return delivery;
    }
    @Override public long preparedGeneration(int mediaFileId) { return preparedGeneration; }
    @Override public void prepare(int mediaFileId, String id, long positionMs)
    {
      assertEquals(mediaFileId, 42);
      prepares++;
      preparationId = id;
      preparationPosition = positionMs;
    }
    @Override public void cancelPreparation() { preparationCancels++; }
    @Override public long skip(String action)
    {
      return action.startsWith("REW") ? -10000 : 10000;
    }
    @Override public void preparationOwner(boolean partyMayPrepare)
    {
      preparationOwner = partyMayPrepare;
    }
    @Override public void progressOwner(boolean partyOwnsProgress)
    {
      progressOwner = partyOwnsProgress;
    }
    @Override public void commit(int mediaFileId, long positionMs) {}
    @Override public void schedule(PartyPlaybackDirective directive)
    {
      scheduled = directive;
      schedules++;
    }
    @Override public void cancelScheduled() { scheduled = null; }
  }

  private static final class Rig
  {
    final AtomicLong now = new AtomicLong(100000);
    final PartyService service = new PartyService(now::get, true);

    Client client() { return new Client(service, now); }
  }

  @Test
  public void preparationUsesExistingPlaybackAdapterAtCanonicalPosition()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    JsonObject created = host.create();

    JsonObject accepted = host.exchange(host.request("PREPARE"));

    assertEquals(host.prepares, 1);
    assertEquals(host.preparationPosition, created.get("startPositionMs").getAsLong());
    assertEquals(accepted.get("targetPositionMs").getAsLong(), host.preparationPosition);
  }

  @Test
  public void createAuthorizesPreparationWithoutTakingPlaybackOwnership()
  {
    Rig rig = new Rig();
    Client host = rig.client();

    host.create();

    assertTrue(host.preparationOwner);
    assertFalse(host.progressOwner);
  }

  @Test
  public void readyBeforePreparationCannotTakePlaybackOwnership()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    long stateVersion = host.snapshot().get("stateVersion").getAsLong();
    JsonObject ready = host.request("READY");
    ready.addProperty("stateVersion", stateVersion);
    ready.addProperty("bufferAheadMs", 0);

    JsonObject response = host.exchange(ready);

    assertEquals(response.get("type").getAsString(), "ERROR");
    assertEquals(response.get("code").getAsString(), "NOT_PREPARED");
    assertTrue(host.preparationOwner);
    assertFalse(host.progressOwner);
  }

  @Test
  public void successfulReadyTakesPlaybackOwnership()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();

    host.prepareAndReady();

    assertTrue(host.preparationOwner);
    assertTrue(host.progressOwner);
  }

  @Test
  public void partyOwnershipIsScopedPerRendererEvenForSameMedia()
  {
    Rig rig = new Rig();
    Client partyViewer = rig.client();
    Client ordinaryViewer = rig.client();
    partyViewer.create();
    ordinaryViewer.hello();

    partyViewer.prepareAndReady();

    assertTrue(partyViewer.progressOwner);
    assertFalse(ordinaryViewer.preparationOwner);
    assertFalse(ordinaryViewer.progressOwner);
    assertEquals(ordinaryViewer.currentMediaFileId(), partyViewer.currentMediaFileId());
  }

  @Test
  public void readyClientsReceiveOneScheduledPlayCommand()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    Client guest = rig.client();
    host.create();
    guest.join(host);
    host.prepareAndReady();
    guest.prepareAndReady();

    JsonObject command = host.action("PLAY");

    assertEquals(command.get("type").getAsString(), "COMMAND");
    assertEquals(command.get("targetState").getAsString(), "PLAYING");
    assertTrue(command.has("directiveId"));
    assertNotNull(guest.event("COMMAND"));
    assertEquals(host.scheduled.targetState(), "PLAYING");
    assertEquals(guest.scheduled.targetState(), "PLAYING");
  }

  @Test
  public void duplicateReadyIsAcknowledgedWithoutAnotherCorrection()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    host.prepareAndReady();
    int schedules = host.schedules;
    PartyPlaybackDirective initial = host.scheduled;
    long stateVersion = host.snapshot().get("stateVersion").getAsLong();
    JsonObject duplicate = host.request("READY");
    duplicate.addProperty("stateVersion", stateVersion);
    duplicate.addProperty("bufferAheadMs", 0);

    JsonObject response = host.exchange(duplicate);

    assertEquals(response.get("type").getAsString(), "READY_ACK");
    assertEquals(host.schedules, schedules);
    assertSame(host.scheduled, initial);
  }

  @Test
  public void repeatedReadyRestoresParticipantAfterPostFlushBuffering()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    host.prepareAndReady();
    host.heartbeat("BUFFERING", 0);
    long stateVersion = host.snapshot().get("stateVersion").getAsLong();
    JsonObject ready = host.request("READY");
    ready.addProperty("stateVersion", stateVersion);
    ready.addProperty("bufferAheadMs", 0);

    JsonObject readyAck = host.exchange(ready);
    JsonObject play = host.action("PLAY");

    assertEquals(readyAck.get("type").getAsString(), "READY_ACK");
    assertEquals(play.get("type").getAsString(), "COMMAND");
    assertEquals(play.get("targetState").getAsString(), "PLAYING");
  }

  @Test
  public void stalePausedHeartbeatDoesNotBlockPlay()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    Client guest = rig.client();
    host.create();
    guest.join(host);
    host.prepareAndReady();
    guest.prepareAndReady();
    host.heartbeat("PAUSED", 0);
    guest.heartbeat("PAUSED", 0);
    rig.now.addAndGet(PartyService.HEARTBEAT_STALE_MS + 1);
    rig.service.tick();
    guest.messages.clear();

    JsonObject play = host.action("PLAY");

    assertEquals(play.get("type").getAsString(), "COMMAND");
    assertNotNull(guest.event("COMMAND"));
  }

  @Test
  public void expiredClockCanRecalibrateAndRetryPlayWithoutRejoining()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    host.prepareAndReady();
    rig.now.addAndGet(PartyClock.FRESHNESS_MS + 1);

    JsonObject rejected = host.action("PLAY");
    host.calibrate();
    JsonObject retried = host.action("PLAY");

    assertEquals(rejected.get("type").getAsString(), "ERROR");
    assertEquals(rejected.get("code").getAsString(), "CLOCK_UNCERTAIN");
    assertEquals(retried.get("type").getAsString(), "COMMAND");
    assertEquals(retried.get("targetState").getAsString(), "PLAYING");
  }

  @Test
  public void stalePlayingHeartbeatStillBlocksControl()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    host.prepareAndReady();
    JsonObject play = host.action("PLAY");
    host.presented(play, "PLAYING", play.get("targetPositionMs").getAsLong());
    host.heartbeat("PLAYING", host.scheduled.targetPositionMs());
    rig.now.addAndGet(PartyService.HEARTBEAT_STALE_MS + 1);
    rig.service.tick();

    JsonObject response = host.action("PLAY");

    assertEquals(response.get("type").getAsString(), "ERROR");
    assertEquals(response.get("code").getAsString(), "NOT_READY");
  }

  @Test
  public void bufferingParticipantDoesNotBlockReadyCohortAndStillReceivesPause()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    Client guest = rig.client();
    host.create();
    guest.join(host);
    host.prepareAndReady();
    guest.prepareAndReady();
    JsonObject play = host.action("PLAY");
    host.presented(play, "PLAYING", play.get("targetPositionMs").getAsLong());
    host.heartbeat("PLAYING", 0);
    guest.heartbeat("BUFFERING", 0);
    guest.messages.clear();

    JsonObject pause = host.action("PAUSE");

    assertEquals(pause.get("targetState").getAsString(), "PAUSED");
    assertNotNull(guest.event("COMMAND"));
  }

  @Test
  public void lateParticipantDoesNotReceivePlayOrIncreaseLead()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    Client guest = rig.client();
    host.create();
    guest.join(host);
    host.prepareAndReady();
    guest.prepareAndReady();
    guest.heartbeat("BUFFERING", 0);
    guest.messages.clear();
    long before = rig.now.get();

    JsonObject play = host.action("PLAY");

    assertNull(guest.event("COMMAND"));
    assertTrue(play.get("executeAtServerMonoMs").getAsLong() - before <= 750);
  }

  @Test
  public void hostStopPausesPartyAndTransfersHostWithoutJoinCodeNotification()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    Client guest = rig.client();
    host.create();
    guest.join(host);
    host.prepareAndReady();
    guest.prepareAndReady();
    JsonObject play = host.action("PLAY");
    host.presented(play, "PLAYING", play.get("targetPositionMs").getAsLong());

    host.heartbeat("STOPPED", 0);

    assertNull(host.event("JOIN_CODE"));
    assertNotNull(host.event("HOST_CHANGED"));
    assertEquals(guest.event("COMMAND").get("targetState").getAsString(), "PAUSED");
    assertEquals(guest.snapshot().get("hostParticipantId").getAsString(), guest.participantId);
  }

  @Test
  public void unknownMessagesAreRejected()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();

    JsonObject response = host.exchange(host.request("NOT_A_PARTY_MESSAGE"));

    assertEquals(response.get("type").getAsString(), "ERROR");
    assertEquals(response.get("code").getAsString(), "UNKNOWN_TYPE");
  }

  @Test
  public void serverLimitsIndependentPartiesToEight()
  {
    Rig rig = new Rig();
    for (int i = 0; i < 8; i++) rig.client().create();
    Client ninth = rig.client();
    ninth.hello();
    JsonObject create = ninth.request("CREATE");
    create.addProperty("mediaFileId", 42);

    JsonObject error = ninth.exchange(create);

    assertEquals(error.get("type").getAsString(), "ERROR");
    assertEquals(error.get("code").getAsString(), "SERVER_CAPACITY");
  }

  @Test
  public void invitationAndChangedParticipantStateMessagesRemainFunctional()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    Client guest = rig.client();
    host.create();
    guest.hello();

    JsonObject clients = host.exchange(host.request("LIST_CLIENTS"));
    JsonObject inviteRequest = host.request("INVITE");
    inviteRequest.addProperty("sessionHandle", guest.sessionHandle);
    JsonObject invite = host.exchange(inviteRequest);
    assertEquals(invite.get("type").getAsString(), "ACK");
    assertNotNull(guest.event("INVITED"));

    guest.join(host);
    host.messages.clear();
    guest.prepareAndReady();
    JsonObject participantState = host.event("PARTICIPANT_STATE");
    assertNotNull(participantState);
    assertTrue(participantState.get("prepared").getAsBoolean());
    assertTrue(participantState.get("clockConfident").getAsBoolean());
    assertTrue(participantState.get("readyForPlayback").getAsBoolean());
    host.messages.clear();
    guest.heartbeat("PAUSED", 0);

    assertTrue(clients.get("sessionHandles").getAsJsonArray().toString()
        .contains(guest.sessionHandle));
    assertNull(host.event("PARTICIPANT_STATE"));
  }

  @Test
  public void hostCanInviteFourDistinctAvailableParticipantsWithoutDuplicateEvents()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    Client[] guests = { rig.client(), rig.client(), rig.client(), rig.client() };
    for (Client guest : guests) guest.hello();

    JsonObject clients = host.exchange(host.request("LIST_CLIENTS"));

    assertEquals(clients.get("sessionHandles").getAsJsonArray().size(), 4);
    for (Client guest : guests)
    {
      JsonObject invite = host.request("INVITE");
      invite.addProperty("sessionHandle", guest.sessionHandle);
      assertEquals(host.exchange(invite).get("type").getAsString(), "ACK");
      assertEquals(guest.eventCount("INVITED"), 1);
    }
    JsonObject duplicate = host.request("INVITE");
    duplicate.addProperty("sessionHandle", guests[0].sessionHandle);
    assertEquals(host.exchange(duplicate).get("type").getAsString(), "ACK");
    assertEquals(guests[0].eventCount("INVITED"), 1);
  }

  @Test
  public void participantStateReportsPausedClockReadinessExpiry()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    Client guest = rig.client();
    host.create();
    guest.join(host);
    host.prepareAndReady();
    guest.prepareAndReady();
    host.messages.clear();

    rig.now.addAndGet(61_000);
    rig.service.tick();

    JsonObject state = host.event("PARTICIPANT_STATE");
    assertNotNull(state);
    assertEquals(state.get("participantId").getAsString(), guest.participantId);
    assertFalse(state.get("clockConfident").getAsBoolean());
    assertFalse(state.get("readyForPlayback").getAsBoolean());
  }

  @Test
  public void conditionallyRequiredJoinIdentityMustBeSuppliedAsAPair()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    Client guest = rig.client();
    host.create();
    guest.hello();
    JsonObject join = guest.request("JOIN");
    join.addProperty("joinCode", host.joinCode);
    join.addProperty("partyId", host.partyId);

    JsonObject error = guest.exchange(join);

    assertEquals(error.get("type").getAsString(), "ERROR");
    assertEquals(error.get("code").getAsString(), "INVALID_REQUEST");
  }

  @Test
  public void actionSpecificTargetIsForbiddenOutsideJump()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    host.prepareAndReady();
    long version = host.snapshot().get("stateVersion").getAsLong();
    JsonObject play = host.request("ACTION");
    play.addProperty("stateVersion", version);
    play.addProperty("action", "PLAY");
    play.addProperty("targetPositionMs", 1000);

    JsonObject error = host.exchange(play);

    assertEquals(error.get("type").getAsString(), "ERROR");
    assertEquals(error.get("code").getAsString(), "INVALID_REQUEST");
  }

  @Test
  public void executedAckRequiresSuccessAndForbidsPresentationFields()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    host.prepareAndReady();
    JsonObject play = host.action("PLAY");
    JsonObject missingSuccess = host.request("ACK");
    missingSuccess.addProperty("stateVersion", play.get("stateVersion").getAsLong());
    missingSuccess.addProperty("commandSeq", play.get("commandSeq").getAsLong());
    missingSuccess.addProperty("directiveId", play.get("directiveId").getAsString());
    missingSuccess.addProperty("stage", "EXECUTED");

    JsonObject missingSuccessError = host.exchange(missingSuccess);

    assertEquals(missingSuccessError.get("code").getAsString(), "INVALID_REQUEST");

    JsonObject withSample = host.request("ACK");
    withSample.addProperty("stateVersion", play.get("stateVersion").getAsLong());
    withSample.addProperty("commandSeq", play.get("commandSeq").getAsLong());
    withSample.addProperty("directiveId", play.get("directiveId").getAsString());
    withSample.addProperty("stage", "EXECUTED");
    withSample.addProperty("success", true);
    withSample.addProperty("sampleMonoMs", rig.now.get());

    JsonObject fieldError = host.exchange(withSample);

    assertEquals(fieldError.get("code").getAsString(), "INVALID_REQUEST");
  }

  @Test
  public void activationIgnoresHeartbeatDriftUntilFirstFrameIsPresented()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    host.prepareAndReady();
    JsonObject play = host.action("PLAY");
    rig.now.set(play.get("executeAtServerMonoMs").getAsLong());
    rig.service.tick();
    int schedules = host.schedules;

    JsonObject heartbeat = host.heartbeat("PLAYING", 5000);

    assertTrue(heartbeat.get("activationPending").getAsBoolean());
    assertEquals(host.schedules, schedules,
        "Heartbeat drift must not schedule another seek while activation is pending");
    host.presented(play, "PLAYING", play.get("targetPositionMs").getAsLong());
    assertFalse(host.heartbeat("PLAYING",
        play.get("targetPositionMs").getAsLong() + 1000)
        .get("activationPending").getAsBoolean());
  }

  @Test
  public void sustainedVisibleDriftUsesSoftCorrectionAndWaitsForPresentation()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    host.prepareAndReady();
    JsonObject play = host.action("PLAY");
    long target = play.get("targetPositionMs").getAsLong();
    host.presented(play, "PLAYING", target);
    int schedules = host.schedules;

    host.heartbeat("PLAYING", target + 250);
    host.heartbeat("PLAYING", target + 1250);
    host.heartbeat("PLAYING", target + 2250);

    JsonObject correction = host.event("CORRECTION");
    assertNotNull(correction);
    assertEquals(correction.get("kind").getAsString(), "SOFT");
    assertEquals(host.schedules, schedules + 1);
    host.heartbeat("PLAYING", target + 5000);
    assertEquals(host.schedules, schedules + 1,
        "A settling correction must suppress repeated correction seeks");
  }

  @Test
  public void substantialVisibleDriftUsesOneHardCorrection()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    host.prepareAndReady();
    JsonObject play = host.action("PLAY");
    long target = play.get("targetPositionMs").getAsLong();
    host.presented(play, "PLAYING", target);
    int schedules = host.schedules;

    host.heartbeat("PLAYING", target);

    JsonObject correction = host.event("CORRECTION");
    assertNotNull(correction);
    assertEquals(correction.get("kind").getAsString(), "HARD");
    assertEquals(correction.get("reason").getAsString(), "SEVERE_DRIFT");
    assertEquals(host.schedules, schedules + 1);
    host.heartbeat("PLAYING", target + 1000);
    assertEquals(host.schedules, schedules + 1);
  }

  @Test
  public void activationTimeoutFailsDirectiveWithoutSchedulingAnotherSeek()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    host.prepareAndReady();
    JsonObject play = host.action("PLAY");
    int schedules = host.schedules;

    rig.now.set(play.get("executeAtServerMonoMs").getAsLong() + 21000);
    rig.service.tick();

    JsonObject error = host.event("ERROR");
    assertNotNull(error);
    assertEquals(error.get("code").getAsString(), "ACTIVATION_EXPIRED");
    assertEquals(host.schedules, schedules);
    assertEquals(host.snapshot().get("roster").getAsJsonArray().get(0)
        .getAsJsonObject().get("status").getAsString(), "LATE");
  }

  @Test
  public void preparationTimeoutReportsNoMediaProgressAndRejectsStaleCallback()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    JsonObject accepted = host.exchange(host.request("PREPARE"));
    String preparationId = accepted.get("preparationId").getAsString();
    int cancelsAfterAccept = host.preparationCancels;

    rig.now.addAndGet(PartyService.PREPARATION_TIMEOUT_MS + 1);
    rig.service.tick();

    JsonObject failure = host.event("PREPARATION_RESULT");
    assertNotNull(failure);
    assertEquals(failure.get("preparationId").getAsString(), preparationId);
    assertEquals(failure.get("status").getAsString(), "FAILED");
    assertNotEquals(failure.get("watchCode").getAsInt(), 0);
    assertEquals(failure.get("code").getAsString(), "NO_MEDIA_PROGRESS");
    assertEquals(host.preparationCancels, cancelsAfterAccept + 1);
    assertNull(host.event("ERROR"), "timeout is a specific preparation result, not a generic error");

    rig.service.preparationResult(host, preparationId, 0, 7);
    assertEquals(host.eventCount("PREPARATION_RESULT"), 1,
        "stale completion cannot alter a timed-out preparation");
  }

  @Test
  public void repeatedPrepareReusesPendingIdWhenMediaIsAlreadyLoaded()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    JsonObject first = host.exchange(host.request("PREPARE"));
    host.preparedGeneration = 7;

    JsonObject repeated = host.exchange(host.request("PREPARE"));

    assertEquals(repeated.get("type").getAsString(), "PREPARE_ACCEPTED");
    assertEquals(repeated.get("preparationId"), first.get("preparationId"));
    assertEquals(host.prepares, 2);
    rig.service.preparationResult(
        host, repeated.get("preparationId").getAsString(), 0, host.preparedGeneration);
    assertEquals(host.event("PREPARATION_RESULT").get("status").getAsString(), "LOADED");
  }

  @Test
  public void loadedPreparationAtomicallyCancelsItsTimeout()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    JsonObject accepted = host.exchange(host.request("PREPARE"));
    int cancelsAfterAccept = host.preparationCancels;
    rig.service.preparationResult(
        host, accepted.get("preparationId").getAsString(), 0, 7);

    rig.now.addAndGet(PartyService.PREPARATION_TIMEOUT_MS + 1);
    rig.service.tick();

    JsonObject loaded = host.event("PREPARATION_RESULT");
    assertEquals(loaded.get("status").getAsString(), "LOADED");
    assertEquals(loaded.get("playbackGeneration").getAsLong(), 7);
    assertEquals(host.eventCount("PREPARATION_RESULT"), 1);
    assertEquals(host.preparationCancels, cancelsAfterAccept);
  }

  @Test
  public void establishedGenerationWithUnavailableDeliveryReportsTranscodeFailure()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    JsonObject accepted = host.exchange(host.request("PREPARE"));
    int cancelsAfterAccept = host.preparationCancels;
    host.preparedDeliveryFails = true;

    rig.service.preparationResult(
        host, accepted.get("preparationId").getAsString(), 0, 7);

    JsonObject failure = host.event("PREPARATION_RESULT");
    assertEquals(failure.get("status").getAsString(), "FAILED");
    assertNotEquals(failure.get("watchCode").getAsInt(), 0);
    assertEquals(failure.get("code").getAsString(), "TRANSCODE_START_FAILED");
    assertFalse(failure.has("playbackGeneration"));
    assertEquals(host.preparationCancels, cancelsAfterAccept + 1);
  }

  @Test
  public void invalidPresentedAckFailsMatchingDirectiveImmediately()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    JsonObject accepted = host.exchange(host.request("PREPARE"));
    rig.service.preparationResult(
        host, accepted.get("preparationId").getAsString(), 0, 1);
    host.calibrate();
    long stateVersion = host.snapshot().get("stateVersion").getAsLong();
    JsonObject ready = host.request("READY");
    ready.addProperty("stateVersion", stateVersion);
    ready.addProperty("bufferAheadMs", 0);
    JsonObject correction = host.exchange(ready);
    rig.now.set(correction.get("executeAtServerMonoMs").getAsLong() + 100);
    rig.service.tick();
    JsonObject presented = host.request("ACK");
    presented.addProperty("stateVersion", correction.get("stateVersion").getAsLong());
    presented.addProperty("commandSeq", correction.get("commandSeq").getAsLong());
    presented.addProperty("directiveId", correction.get("directiveId").getAsString());
    presented.addProperty("stage", "PRESENTED");
    presented.addProperty("success", true);
    presented.addProperty("sampleMonoMs", rig.now.get() - 100);
    presented.addProperty("positionMs", correction.get("targetPositionMs").getAsLong());
    presented.addProperty("state", "PLAYING");

    JsonObject error = host.exchange(presented);

    assertEquals(error.get("type").getAsString(), "ERROR");
    assertEquals(error.get("code").getAsString(), "INVALID_PRESENTATION");
    assertEquals(host.snapshot().get("roster").getAsJsonArray().get(0)
        .getAsJsonObject().get("status").getAsString(), "LATE");
  }

  @Test
  public void jumpWaitsForPresentedFrameBeforeAcceptingAnotherTransition()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    host.prepareAndReady();
    JsonObject play = host.action("PLAY");
    host.presented(play, "PLAYING", play.get("targetPositionMs").getAsLong());
    long stateVersion = host.snapshot().get("stateVersion").getAsLong();
    JsonObject jumpRequest = host.request("ACTION");
    jumpRequest.addProperty("stateVersion", stateVersion);
    jumpRequest.addProperty("action", "JUMP");
    jumpRequest.addProperty("targetPositionMs", 120000);
    JsonObject jump = host.exchange(jumpRequest);
    assertEquals(jump.get("type").getAsString(), "COMMAND", jump.toString());

    JsonObject blocked = host.action("FF");

    assertEquals(jump.get("cause").getAsString(), "JUMP");
    assertEquals(blocked.get("type").getAsString(), "ERROR");
    assertEquals(blocked.get("code").getAsString(), "TRANSITION_PENDING");
    host.presented(jump, "PLAYING", 120000);
    assertEquals(host.action("FF").get("type").getAsString(), "COMMAND");
  }

  @Test
  public void repositionBarrierWaitsForEveryReadyParticipantBeforeReveal()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    host.prepareAndReady();
    Client[] guests = { rig.client(), rig.client(), rig.client() };
    for (Client guest : guests)
    {
      guest.join(host);
      guest.prepareAndReady();
    }
    JsonObject play = host.action("PLAY");
    JsonObject[] guestPlay = new JsonObject[guests.length];
    for (int i = 0; i < guests.length; i++)
      guestPlay[i] = guests[i].event("COMMAND");
    host.presented(play, "PLAYING", play.get("targetPositionMs").getAsLong());
    for (int i = 0; i < guests.length; i++)
      guests[i].presented(guestPlay[i], "PLAYING",
          guestPlay[i].get("targetPositionMs").getAsLong());

    long jumpStateVersion = host.snapshot().get("stateVersion").getAsLong();
    JsonObject jumpRequest = host.request("ACTION");
    jumpRequest.addProperty("stateVersion", jumpStateVersion);
    jumpRequest.addProperty("action", "JUMP");
    jumpRequest.addProperty("targetPositionMs", 180000);
    JsonObject hostJump = host.exchange(jumpRequest);
    JsonObject[] guestJump = new JsonObject[guests.length];
    for (int i = 0; i < guests.length; i++)
      guestJump[i] = guests[i].event("COMMAND");
    assertEquals(hostJump.get("type").getAsString(), "COMMAND", hostJump.toString());
    for (JsonObject directive : guestJump) assertNotNull(directive);
    assertTrue(hostJump.has("executeAtServerMonoMs"), hostJump.toString());
    for (JsonObject directive : guestJump)
      assertTrue(directive.has("executeAtServerMonoMs"), directive.toString());

    host.arm(hostJump, 180000);
    guests[0].arm(guestJump[0], 180000);
    guests[1].arm(guestJump[1], 180000);

    assertNull(host.event("REVEAL"));
    rig.now.set(hostJump.get("visibleAtServerMonoMs").getAsLong() + 1000);
    rig.service.tick();
    JsonObject blockedSnapshot = host.snapshot();
    assertTrue(blockedSnapshot.get("barrierPending").getAsBoolean());
    assertFalse(blockedSnapshot.has("pendingCommand"));
    assertNotEquals(blockedSnapshot.get("anchorPositionMs").getAsLong(), 180000L);

    int lastGuestMessageStart = guests[2].messages.size();
    guests[2].arm(guestJump[2], 180000);
    int armedAckIndex = -1;
    int revealIndex = -1;
    for (int i = lastGuestMessageStart; i < guests[2].messages.size(); i++)
    {
      JsonObject message = guests[2].messages.get(i);
      if ("ACK".equals(message.get("type").getAsString()) && message.has("requestId"))
        armedAckIndex = i;
      if ("REVEAL".equals(message.get("type").getAsString()) && !message.has("requestId"))
        revealIndex = i;
    }
    assertTrue(armedAckIndex >= lastGuestMessageStart);
    assertTrue(revealIndex > armedAckIndex,
        "correlated ARMED response must be queued before REVEAL");

    JsonObject hostReveal = host.event("REVEAL");
    JsonObject[] guestReveal = new JsonObject[guests.length];
    for (int i = 0; i < guests.length; i++)
      guestReveal[i] = guests[i].event("REVEAL");
    assertNotNull(hostReveal);
    for (JsonObject reveal : guestReveal)
    {
      assertNotNull(reveal);
      assertEquals(hostReveal.get("executeAtServerMonoMs").getAsLong(),
          reveal.get("executeAtServerMonoMs").getAsLong());
    }
    JsonObject hostPresented = copy(hostJump);
    hostPresented.remove("barrier");
    hostPresented.addProperty("executeAtServerMonoMs",
        hostReveal.get("executeAtServerMonoMs").getAsLong());
    host.presented(hostPresented, "PLAYING", 180000);
    for (int i = 0; i < guests.length; i++)
    {
      JsonObject presented = copy(guestJump[i]);
      presented.remove("barrier");
      presented.addProperty("executeAtServerMonoMs",
          guestReveal[i].get("executeAtServerMonoMs").getAsLong());
      guests[i].presented(presented, "PLAYING", 180000);
    }
    assertFalse(host.snapshot().has("pendingCommand"));
  }

  @Test
  public void silentStaleTransportCannotKeepAbandonedPartyResumable()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    String partyId = host.partyId;
    String participantId = host.participantId;
    String recoveryToken = host.recoveryToken;
    rig.now.addAndGet(PartyService.CONNECTED_ACTIVITY_STALE_MS
        + PartyService.RECONNECT_GRACE_MS + 1);
    Client replacement = rig.client();
    replacement.hello();
    replacement.partyId = partyId;
    JsonObject resume = replacement.request("RESUME");
    resume.addProperty("participantId", participantId);
    resume.addProperty("recoveryToken", recoveryToken);

    JsonObject error = replacement.exchange(resume);

    assertEquals(error.get("type").getAsString(), "ERROR");
    assertEquals(error.get("code").getAsString(), "PARTY_EXPIRED");
  }

  @Test
  public void leavingFinalParticipantImmediatelyExpiresResume()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    String partyId = host.partyId;
    String participantId = host.participantId;
    String recoveryToken = host.recoveryToken;

    assertEquals(host.exchange(host.request("LEAVE")).get("type").getAsString(), "LEFT");

    Client replacement = rig.client();
    replacement.hello();
    replacement.partyId = partyId;
    JsonObject resume = replacement.request("RESUME");
    resume.addProperty("participantId", participantId);
    resume.addProperty("recoveryToken", recoveryToken);
    JsonObject error = replacement.exchange(resume);
    assertEquals(error.get("code").getAsString(), "PARTY_EXPIRED");
  }

  @Test
  public void staleStateErrorReturnsSuppliedAndAuthoritativeCoordinates()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    JsonObject ready = host.request("READY");
    ready.addProperty("stateVersion", 4);
    ready.addProperty("commandSeq", 4);
    ready.addProperty("bufferAheadMs", 0);

    JsonObject error = host.exchange(ready);

    assertEquals(error.get("code").getAsString(), "STALE_STATE");
    assertEquals(error.get("requestType").getAsString(), "READY");
    assertEquals(error.get("partyId").getAsString(), host.partyId);
    assertEquals(error.get("suppliedStateVersion").getAsLong(), 4L);
    assertEquals(error.get("suppliedCommandSeq").getAsLong(), 4L);
    assertEquals(error.get("authoritativeStateVersion").getAsLong(), 0L);
    assertEquals(error.get("authoritativeCommandSeq").getAsLong(), 0L);
  }

  @Test
  public void presentedSampleMayNormalizeSlightlyAheadOfReceipt()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    JsonObject accepted = host.exchange(host.request("PREPARE"));
    rig.service.preparationResult(
        host, accepted.get("preparationId").getAsString(), 0, 1);
    host.calibrate();
    long stateVersion = host.snapshot().get("stateVersion").getAsLong();
    JsonObject ready = host.request("READY");
    ready.addProperty("stateVersion", stateVersion);
    ready.addProperty("bufferAheadMs", 0);
    JsonObject correction = host.exchange(ready);
    rig.now.set(correction.get("executeAtServerMonoMs").getAsLong() + 100);
    rig.service.tick();
    JsonObject presented = host.request("ACK");
    presented.addProperty("stateVersion", correction.get("stateVersion").getAsLong());
    presented.addProperty("commandSeq", correction.get("commandSeq").getAsLong());
    presented.addProperty("directiveId", correction.get("directiveId").getAsString());
    presented.addProperty("stage", "PRESENTED");
    presented.addProperty("success", true);
    presented.addProperty("sampleMonoMs", rig.now.get() - 90);
    presented.addProperty("positionMs", correction.get("targetPositionMs").getAsLong());
    presented.addProperty("state", "PAUSED");

    JsonObject response = host.exchange(presented);

    assertEquals(response.get("type").getAsString(), "ACK", response.toString());
  }
}
