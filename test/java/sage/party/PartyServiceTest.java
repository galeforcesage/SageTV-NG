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
    String sessionToken;
    String partyId;
    String participantId;
    String recoveryToken;
    String joinCode;
    long requestSeq;
    int prepares;
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

    JsonObject event(String type)
    {
      for (int i = messages.size() - 1; i >= 0; i--)
        if (type.equals(messages.get(i).get("type").getAsString())
            && !messages.get(i).has("requestId"))
          return messages.get(i);
      return null;
    }

    @Override public String principal() { return "verified-user"; }
    @Override public Set<String> capabilities() { return CAPABILITIES; }
    @Override public void send(JsonObject message) { messages.add(copy(message)); }
    @Override public int currentMediaFileId() { return 42; }
    @Override public long duration(int mediaFileId) { return 3600000; }
    @Override public long currentPosition(int mediaFileId) { return 120000; }
    public PartyService.Delivery plan(int mediaFileId) { return delivery; }
    @Override public PartyService.Delivery delivery(int mediaFileId) { return delivery; }
    @Override public PartyService.Delivery preparedDelivery(int mediaFileId) { return delivery; }
    @Override public void prepare(int mediaFileId, String id, long positionMs)
    {
      assertEquals(mediaFileId, 42);
      prepares++;
      preparationId = id;
      preparationPosition = positionMs;
    }
    @Override public long skip(String action)
    {
      return action.startsWith("REW") ? -10000 : 10000;
    }
    @Override public void progressOwner(boolean partyOwnsProgress) {}
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
    assertNotNull(host.event("COMMAND"));
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
  public void stalePlayingHeartbeatStillBlocksControl()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    host.prepareAndReady();
    host.action("PLAY");
    rig.now.set(host.scheduled.executeAtServerMonoMs());
    rig.service.tick();
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
    host.action("PLAY");
    rig.now.set(host.scheduled.executeAtServerMonoMs());
    rig.service.tick();
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
  public void hostStopPausesPartyPreservesCodeAndTransfersHost()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    Client guest = rig.client();
    host.create();
    guest.join(host);
    host.prepareAndReady();
    guest.prepareAndReady();
    host.action("PLAY");
    rig.now.set(host.scheduled.executeAtServerMonoMs());
    rig.service.tick();

    host.heartbeat("STOPPED", 0);

    assertNotNull(host.event("JOIN_CODE"));
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
}
