package sage.party;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import sage.epg.sd.gson.JsonElement;
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
    final List<Long> commits = new ArrayList<>();
    Set<String> capabilities = CAPABILITIES;
    String principal = "verified-user";
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
    String currentMediaFailure;
    boolean currentMediaWindowUnavailable;
    boolean carrierReady = true;
    boolean preparationOwner;
    boolean progressOwner;
    long preparedGeneration = -1;
    String preparationId;
    String preparationBinding;
    String installedPlaybackSessionId;
    long preparationPosition;
    PartyPlaybackDirective scheduled;
    int schedules;
    long currentPosition = 120000;
    long availableEnd = 3600000;
    boolean growing;
    boolean preparedPaused = true;
    long lastPresentedSampleMono;
    long lastHeartbeatSampleMono;
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
      if ("HELLO".equals(type) || "CREATE".equals(type)
          || "JOIN".equals(type) || "RESUME".equals(type))
        out.addProperty("contractRevision", CONTRACT_REVISION);
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

    String status()
    {
      for (JsonElement element : snapshot().getAsJsonArray("roster"))
      {
        JsonObject participant = element.getAsJsonObject();
        if (participantId.equals(participant.get("participantId").getAsString()))
          return participant.get("status").getAsString();
      }
      fail("participant missing from roster");
      return null;
    }

    void calibrate()
    {
      calibrate(95);
    }

    void calibrate(long clientLagMs)
    {
      for (int i = 0; i < 5; i++)
      {
        now.addAndGet(1000);
        JsonObject probe = exchange(request("CLOCK"));
        long s0 = probe.get("s0").getAsLong();
        now.addAndGet(10);
        JsonObject reply = request("CLOCK_REPLY");
        reply.addProperty("probeId", probe.get("probeId").getAsString());
        reply.addProperty("c1", s0 - clientLagMs);
        reply.addProperty("c2", s0 - clientLagMs);
        JsonObject model = exchange(reply);
        if (i == 4) assertTrue(model.get("confident").getAsBoolean());
      }
    }

    void prepareAndReady()
    {
      JsonObject accepted = prepare();
      preparationBinding = accepted.get("preparationBinding").getAsString();
      service.preparationResult(this, accepted.get("preparationId").getAsString(),
          PartyPlayerAdapter.PreparationResult.loaded(0, 1));
      calibrate();
      JsonObject ready = request("READY");
      ready.addProperty("stateVersion", snapshot().get("stateVersion").getAsLong());
      ready.addProperty("bufferAheadMs", 0);
      ready.addProperty("requestId", "r" + ++requestSeq);
      ready.addProperty("requestSeq", requestSeq);
      JsonObject response = exchange(ready);
      assertEquals(response.get("type").getAsString(), "CORRECTION");
      presented(response, "PAUSED", response.get("targetPositionMs").getAsLong());
    }

    JsonObject prepare()
    {
      JsonObject accepted = exchange(request("PREPARE"));
      preparationBinding = accepted.get("preparationBinding").getAsString();
      JsonObject bindingAck = request("MEDIA_OPEN_BINDING_ACK");
      bindingAck.addProperty("preparationId",
          accepted.get("preparationId").getAsString());
      bindingAck.addProperty("preparationBinding", preparationBinding);
      assertEquals(exchange(bindingAck).get("type").getAsString(), "ACK");
      opened(accepted);
      return accepted;
    }

    void opened(JsonObject accepted)
    {
      JsonObject opened = request("MEDIA_OPENED");
      opened.addProperty("preparationId",
          accepted.get("preparationId").getAsString());
      opened.addProperty("preparationBinding", preparationBinding);
      opened.addProperty("localOpenGeneration", 1);
      opened.addProperty("surfaceGeneration", 1);
      opened.addProperty("reused", false);
      assertEquals(exchange(opened).get("type").getAsString(), "ACK");
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

    JsonObject jump(long position)
    {
      JsonObject request = request("ACTION");
      request.addProperty("stateVersion", snapshot().get("stateVersion").getAsLong());
      request.addProperty("action", "JUMP");
      request.addProperty("targetPositionMs", position);
      request.addProperty("requestId", "r" + ++requestSeq);
      request.addProperty("requestSeq", requestSeq);
      return exchange(request);
    }

    JsonObject heartbeat(String state, long position)
    {
      now.addAndGet(1000);
      return heartbeatAt(state, position, now.get() - 100);
    }

    JsonObject heartbeatAt(String state, long position, long sampleMonoMs)
    {
      lastHeartbeatSampleMono = sampleMonoMs;
      long stateVersion = snapshot().get("stateVersion").getAsLong();
      JsonObject request = request("HEARTBEAT");
      request.addProperty("stateVersion", stateVersion);
      request.addProperty("sampleMonoMs", sampleMonoMs);
      request.addProperty("positionMs", position);
      request.addProperty("bufferAheadMs", "BUFFERING".equals(state) ? 0 : 2000);
      request.addProperty("state", state);
      request.addProperty("requestId", "r" + ++requestSeq);
      request.addProperty("requestSeq", requestSeq);
      return exchange(request);
    }

    JsonObject presented(JsonObject directive, String state, long position)
    {
      JsonObject response = presentedResponse(directive, state, position, true);
      assertEquals(response.get("type").getAsString(), "ACK", response.toString());
      return response;
    }

    JsonObject presentedWithoutCover(JsonObject directive, String state, long position)
    {
      JsonObject response = presentedResponse(directive, state, position, false);
      assertEquals(response.get("type").getAsString(), "ACK", response.toString());
      return response;
    }

    JsonObject presentedResponse(
        JsonObject directive, String state, long position, boolean includeCover)
    {
      if (directive.has("barrier") && directive.get("barrier").getAsBoolean())
      {
        arm(directive, position);
        held(directive, position);
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
      lastPresentedSampleMono = now.get() - 100;
      if ("PLAYING".equals(state)
          && position == directive.get("targetPositionMs").getAsLong())
        position += Math.max(0, lastPresentedSampleMono
            - directive.get("executeAtServerMonoMs").getAsLong());
      request.addProperty("sampleMonoMs", lastPresentedSampleMono);
      request.addProperty("sampleUncertaintyMs", 32);
      request.addProperty("positionMs", position);
      request.addProperty("state", state);
      if ("PLAYING".equals(state) && includeCover)
      {
        request.addProperty("coverRemovedMonoMs", lastPresentedSampleMono - 1);
        request.addProperty("frameMonoMs", lastPresentedSampleMono);
      }
      request.addProperty("requestId", "r" + ++requestSeq);
      request.addProperty("requestSeq", requestSeq);
      return exchange(request);
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
      armed.addProperty("sampleUncertaintyMs", 32);
      armed.addProperty("positionMs", position);
      armed.addProperty("state", "PAUSED");
      armed.addProperty("requestId", "r" + ++requestSeq);
      armed.addProperty("requestSeq", requestSeq);
      JsonObject response = exchange(armed);
      assertEquals(response.get("type").getAsString(), "ACK", response.toString());
      return response;
    }

    JsonObject held(JsonObject directive, long position)
    {
      JsonObject confirm = event("HOLD_CONFIRM");
      assertNotNull(confirm);
      assertEquals(confirm.get("partyId").getAsString(), partyId);
      assertEquals(confirm.get("stateVersion"), directive.get("stateVersion"));
      assertEquals(confirm.get("commandSeq"), directive.get("commandSeq"));
      assertEquals(confirm.get("directiveId"), directive.get("directiveId"));
      now.incrementAndGet();
      JsonObject held = request("ACK");
      held.addProperty("stateVersion", directive.get("stateVersion").getAsLong());
      held.addProperty("commandSeq", directive.get("commandSeq").getAsLong());
      held.addProperty("directiveId", directive.get("directiveId").getAsString());
      held.addProperty("stage", "HELD");
      held.addProperty("success", true);
      held.addProperty("sampleMonoMs", now.get() - 100);
      held.addProperty("sampleUncertaintyMs", 32);
      held.addProperty("positionMs", position);
      held.addProperty("state", "PAUSED");
      JsonObject response = exchange(held);
      assertEquals(response.get("type").getAsString(), "ACK", response.toString());
      assertTrue(response.get("accepted").getAsBoolean(), response.toString());
      return response;
    }

    JsonObject executed(JsonObject directive)
    {
      now.set(Math.max(now.get() + 1,
          directive.get("executeAtServerMonoMs").getAsLong() + 100));
      service.tick();
      JsonObject executed = request("ACK");
      executed.addProperty("stateVersion", directive.get("stateVersion").getAsLong());
      executed.addProperty("commandSeq", directive.get("commandSeq").getAsLong());
      executed.addProperty("directiveId", directive.get("directiveId").getAsString());
      executed.addProperty("stage", "EXECUTED");
      executed.addProperty("success", true);
      executed.addProperty("requestId", "r" + ++requestSeq);
      executed.addProperty("requestSeq", requestSeq);
      JsonObject response = exchange(executed);
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

    @Override public String principal() { return principal; }
    @Override public Set<String> capabilities() { return capabilities; }
    @Override public void send(JsonObject message) { messages.add(copy(message)); }
    @Override public int currentMediaFileId()
    {
      if (currentMediaFailure != null) throw new PartyProtocol.Fault(currentMediaFailure);
      return 42;
    }
    @Override public long duration(int mediaFileId) { return availableEnd; }
    @Override public PartyService.MediaWindow mediaWindow(int mediaFileId)
    {
      if (currentMediaWindowUnavailable)
        throw new PartyProtocol.Fault("MEDIA_UNAVAILABLE");
      return new PartyService.MediaWindow(availableEnd, growing);
    }
    @Override public long currentPosition(int mediaFileId) { return currentPosition; }
    @Override public long preparedPausedPosition(int mediaFileId)
    {
      return preparedPaused ? currentPosition : -1;
    }
    public PartyService.Delivery plan(int mediaFileId) { return delivery; }
    @Override public PartyService.Delivery delivery(int mediaFileId) { return delivery; }
    @Override public PartyService.Delivery preparedDelivery(int mediaFileId)
    {
      if (preparedDeliveryFails) throw new PartyProtocol.Fault("MEDIA_PATH_NOT_READY");
      return delivery;
    }
    @Override public long preparedGeneration(int mediaFileId) { return preparedGeneration; }
    @Override public void installPreparationBinding(
        PartyPlayerAdapter.PreparationBinding binding)
    {
      preparationBinding = binding.preparationBinding();
      installedPlaybackSessionId = binding.playbackSessionId();
    }
    @Override public boolean preparationBindingReady(String binding)
    {
      return carrierReady && preparationBinding != null
          && preparationBinding.equals(binding);
    }
    @Override public void cancelPreparationBinding(String binding)
    {
      if (binding == null || binding.equals(preparationBinding))
        preparationBinding = null;
    }
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
    @Override public void commit(int mediaFileId, long positionMs)
    {
      assertEquals(mediaFileId, 42);
      commits.add(positionMs);
    }
    @Override public void schedule(PartyPlaybackDirective directive)
    {
      scheduled = directive;
      schedules++;
    }
    @Override public void cancelScheduled() { scheduled = null; }
  }

  private static String deniedCode(String admittedCode, int index)
  {
    int admitted = Integer.parseInt(admittedCode);
    int candidate = index >= admitted ? index + 1 : index;
    return String.format("%03d", candidate);
  }

  @Test
  public void helloRejectsEveryOtherContractRevision()
  {
    Rig rig = new Rig();
    Client client = rig.client();
    JsonObject hello = client.request("HELLO");
    hello.addProperty("contractRevision", CONTRACT_REVISION - 1);

    JsonObject response = client.exchange(hello);

    assertEquals(response.get("type").getAsString(), "ERROR");
    assertEquals(response.get("code").getAsString(),
        "CONTRACT_REVISION_MISMATCH");
  }

  @Test
  public void readyRequiresBindingAckCarrierConsumptionAndMediaOpened()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    JsonObject accepted = host.exchange(host.request("PREPARE"));
    host.carrierReady = false;
    JsonObject bindingAck = host.request("MEDIA_OPEN_BINDING_ACK");
    bindingAck.add("preparationId", accepted.get("preparationId"));
    bindingAck.add("preparationBinding", accepted.get("preparationBinding"));
    assertEquals(host.exchange(bindingAck).get("type").getAsString(), "ACK");
    rig.service.preparationResult(host,
        accepted.get("preparationId").getAsString(),
        PartyPlayerAdapter.PreparationResult.loaded(0, 1));
    JsonObject opened = host.request("MEDIA_OPENED");
    opened.add("preparationId", accepted.get("preparationId"));
    opened.add("preparationBinding", accepted.get("preparationBinding"));
    opened.addProperty("localOpenGeneration", 1);
    opened.addProperty("surfaceGeneration", 1);
    opened.addProperty("reused", false);
    assertEquals(host.exchange(opened).get("code").getAsString(),
        "OPEN_BINDING_MISMATCH");

    host.carrierReady = true;
    opened = host.request("MEDIA_OPENED");
    opened.add("preparationId", accepted.get("preparationId"));
    opened.add("preparationBinding", accepted.get("preparationBinding"));
    opened.addProperty("localOpenGeneration", 1);
    opened.addProperty("surfaceGeneration", 1);
    opened.addProperty("reused", false);
    assertEquals(host.exchange(opened).get("type").getAsString(), "ACK");
    host.calibrate();
    long stateVersion = host.snapshot().get("stateVersion").getAsLong();
    JsonObject ready = host.request("READY");
    ready.addProperty("stateVersion", stateVersion);
    ready.addProperty("bufferAheadMs", 0);
    JsonObject correction = host.exchange(ready);
    assertEquals(correction.get("type").getAsString(), "CORRECTION",
        correction.toString());
  }

  @Test
  public void pwaBindingAckPinsThePlaybackSessionBeforeWatchStarts()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    JsonObject accepted = host.exchange(host.request("PREPARE"));
    JsonObject bindingAck = host.request("MEDIA_OPEN_BINDING_ACK");
    bindingAck.add("preparationId", accepted.get("preparationId"));
    bindingAck.add("preparationBinding", accepted.get("preparationBinding"));
    bindingAck.addProperty("playbackSessionId", "pwa-session-a");

    JsonObject response = host.exchange(bindingAck);
    assertEquals(response.get("type").getAsString(), "ACK");
    assertEquals(response.get("preparationBinding").getAsString(),
        accepted.get("preparationBinding").getAsString());
    assertEquals(response.get("playbackSessionId").getAsString(),
        "pwa-session-a");
    assertEquals(host.installedPlaybackSessionId, "pwa-session-a");
  }

  @Test
  public void mediaOpenedAckEchoesFreshBindingAndCachedReply()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    JsonObject accepted = host.exchange(host.request("PREPARE"));
    JsonObject bindingAck = host.request("MEDIA_OPEN_BINDING_ACK");
    bindingAck.add("preparationId", accepted.get("preparationId"));
    bindingAck.add("preparationBinding", accepted.get("preparationBinding"));
    assertEquals(host.exchange(bindingAck).get("type").getAsString(), "ACK");
    JsonObject opened = host.request("MEDIA_OPENED");
    opened.add("preparationId", accepted.get("preparationId"));
    opened.add("preparationBinding", accepted.get("preparationBinding"));
    opened.addProperty("localOpenGeneration", 1);
    opened.addProperty("surfaceGeneration", 1);
    opened.addProperty("reused", false);

    JsonObject response = host.exchange(opened);
    JsonObject cached = host.exchange(opened);

    assertEquals(response.get("type").getAsString(), "ACK");
    assertEquals(response.get("preparationId"), accepted.get("preparationId"));
    assertEquals(response.get("preparationBinding"), accepted.get("preparationBinding"));
    assertEquals(cached, response, "A duplicate request must replay the correlated ACK");
  }

  @Test
  public void mediaOpenedRejectsMissingMismatchedAndConsumedBindings()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    JsonObject accepted = host.exchange(host.request("PREPARE"));
    JsonObject bindingAck = host.request("MEDIA_OPEN_BINDING_ACK");
    bindingAck.add("preparationId", accepted.get("preparationId"));
    bindingAck.add("preparationBinding", accepted.get("preparationBinding"));
    assertEquals(host.exchange(bindingAck).get("type").getAsString(), "ACK");

    JsonObject missing = host.request("MEDIA_OPENED");
    missing.add("preparationId", accepted.get("preparationId"));
    missing.addProperty("localOpenGeneration", 1);
    missing.addProperty("surfaceGeneration", 1);
    missing.addProperty("reused", false);
    assertEquals(host.exchange(missing).get("code").getAsString(), "INVALID_REQUEST");

    JsonObject mismatched = host.request("MEDIA_OPENED");
    mismatched.add("preparationId", accepted.get("preparationId"));
    mismatched.addProperty("preparationBinding", "mismatched-binding");
    mismatched.addProperty("localOpenGeneration", 1);
    mismatched.addProperty("surfaceGeneration", 1);
    mismatched.addProperty("reused", false);
    assertEquals(host.exchange(mismatched).get("code").getAsString(),
        "OPEN_BINDING_MISMATCH");

    JsonObject opened = host.request("MEDIA_OPENED");
    opened.add("preparationId", accepted.get("preparationId"));
    opened.add("preparationBinding", accepted.get("preparationBinding"));
    opened.addProperty("localOpenGeneration", 1);
    opened.addProperty("surfaceGeneration", 1);
    opened.addProperty("reused", false);
    assertEquals(host.exchange(opened).get("type").getAsString(), "ACK");

    JsonObject stale = host.request("MEDIA_OPENED");
    stale.add("preparationId", accepted.get("preparationId"));
    stale.add("preparationBinding", accepted.get("preparationBinding"));
    stale.addProperty("localOpenGeneration", 1);
    stale.addProperty("surfaceGeneration", 1);
    stale.addProperty("reused", false);
    assertEquals(host.exchange(stale).get("code").getAsString(),
        "OPEN_BINDING_MISMATCH");
  }

  private static final class Rig
  {
    final AtomicLong now = new AtomicLong(100000);
    final PartyService service = new PartyService(now::get, true);

    Client client() { return new Client(service, now); }
  }

  @Test
  public void helloRenewsExpiringSessionTokenWithoutChangingPartyIdentity()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    String oldToken = host.sessionToken;
    String oldHandle = host.sessionHandle;
    String oldRecoveryToken = host.recoveryToken;
    JsonObject current = host.exchange(host.request("HELLO"));
    long oldExpiry = current.get("sessionTokenExpiresAtServerMonoMs").getAsLong();

    rig.now.set(oldExpiry - 59999);
    JsonObject renewed = host.exchange(host.request("HELLO"));
    String newToken = renewed.get("sessionToken").getAsString();
    host.sessionToken = newToken;

    assertNotEquals(newToken, oldToken);
    assertEquals(renewed.get("sessionHandle").getAsString(), oldHandle);
    assertEquals(renewed.get("recoveryToken").getAsString(), oldRecoveryToken);
    assertTrue(renewed.get("sessionTokenExpiresAtServerMonoMs").getAsLong() > oldExpiry);
    assertEquals(host.snapshot().get("partyId").getAsString(), host.partyId);

    host.sessionToken = oldToken;
    JsonObject denied = host.snapshot();
    assertEquals(denied.get("type").getAsString(), "ERROR");
    assertEquals(denied.get("code").getAsString(), "SESSION_DENIED");
    host.sessionToken = newToken;
  }

  @Test
  public void helloRecoversExpiredSessionTokenOnSameTransport()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    JsonObject current = host.exchange(host.request("HELLO"));
    long expiry = current.get("sessionTokenExpiresAtServerMonoMs").getAsLong();

    rig.now.set(expiry + 1);
    JsonObject expired = host.snapshot();
    assertEquals(expired.get("type").getAsString(), "ERROR");
    assertEquals(expired.get("code").getAsString(), "SESSION_EXPIRED");

    JsonObject renewed = host.exchange(host.request("HELLO"));
    host.sessionToken = renewed.get("sessionToken").getAsString();

    assertEquals(host.snapshot().get("partyId").getAsString(), host.partyId);
    assertFalse(renewed.has("participantId"));
    assertEquals(renewed.get("recoveryToken").getAsString(), host.recoveryToken);
  }

  @Test
  public void joinAttemptsAreLimitedPerSession()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    Client attacker = rig.client();
    attacker.hello();
    String deniedCode = host.joinCode.equals("000") ? "001" : "000";

    for (int attempt = 0; attempt < 3; attempt++)
    {
      JsonObject request = attacker.request("JOIN");
      request.addProperty("joinCode", deniedCode);
      assertEquals(attacker.exchange(request).get("code").getAsString(), "JOIN_DENIED");
    }
    JsonObject limitedRequest = attacker.request("JOIN");
    limitedRequest.addProperty("joinCode", deniedCode);
    JsonObject limited = attacker.exchange(limitedRequest);

    assertEquals(limited.get("code").getAsString(), "JOIN_RATE_LIMITED");
    assertEquals(limited.get("retryAfterMs").getAsLong(), 60000L);
  }

  @Test
  public void joinAttemptsAreLimitedAcrossTransportsForPrincipal()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    for (int attempt = 0; attempt < 6; attempt++)
    {
      Client attacker = rig.client();
      attacker.hello();
      JsonObject request = attacker.request("JOIN");
      request.addProperty("joinCode", deniedCode(host.joinCode, attempt));
      assertEquals(attacker.exchange(request).get("code").getAsString(), "JOIN_DENIED");
    }
    Client blocked = rig.client();
    blocked.hello();
    JsonObject request = blocked.request("JOIN");
    request.addProperty("joinCode", deniedCode(host.joinCode, 6));
    JsonObject limited = blocked.exchange(request);

    assertEquals(limited.get("code").getAsString(), "JOIN_RATE_LIMITED");
    assertEquals(limited.get("retryAfterMs").getAsLong(), 60000L);
  }

  @Test
  public void joinAttemptsAreLimitedPerCodeAcrossPrincipals()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    String deniedCode = deniedCode(host.joinCode, 0);
    for (int attempt = 0; attempt < 6; attempt++)
    {
      Client attacker = rig.client();
      attacker.principal = "user:attacker-" + attempt;
      attacker.hello();
      JsonObject request = attacker.request("JOIN");
      request.addProperty("joinCode", deniedCode);
      assertEquals(attacker.exchange(request).get("code").getAsString(), "JOIN_DENIED");
    }
    Client blocked = rig.client();
    blocked.principal = "user:attacker-blocked";
    blocked.hello();
    JsonObject request = blocked.request("JOIN");
    request.addProperty("joinCode", deniedCode);
    JsonObject limited = blocked.exchange(request);

    assertEquals(limited.get("code").getAsString(), "JOIN_RATE_LIMITED");
    assertEquals(limited.get("retryAfterMs").getAsLong(), 60000L);
  }

  @Test
  public void layeredJoinLimitsExpireAfterRollingWindow()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    String deniedCode = deniedCode(host.joinCode, 0);
    for (int attempt = 0; attempt < 6; attempt++)
    {
      Client attacker = rig.client();
      attacker.principal = "user:expiring-" + attempt;
      attacker.hello();
      JsonObject request = attacker.request("JOIN");
      request.addProperty("joinCode", deniedCode);
      assertEquals(attacker.exchange(request).get("code").getAsString(), "JOIN_DENIED");
    }
    Client blocked = rig.client();
    blocked.principal = "user:expiring-blocked";
    blocked.hello();
    JsonObject limitedRequest = blocked.request("JOIN");
    limitedRequest.addProperty("joinCode", deniedCode);
    assertEquals(blocked.exchange(limitedRequest).get("code").getAsString(),
        "JOIN_RATE_LIMITED");

    rig.now.addAndGet(60001);
    JsonObject retry = blocked.request("JOIN");
    retry.addProperty("joinCode", deniedCode);

    assertEquals(blocked.exchange(retry).get("code").getAsString(), "JOIN_DENIED");
  }

  @Test
  public void sharedLanPrincipalDoesNotThrottleIndependentCodes()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    for (int attempt = 0; attempt < 7; attempt++)
    {
      Client attacker = rig.client();
      attacker.principal = "server-admitted-lan";
      attacker.hello();
      JsonObject request = attacker.request("JOIN");
      request.addProperty("joinCode", deniedCode(host.joinCode, attempt));
      assertEquals(attacker.exchange(request).get("code").getAsString(), "JOIN_DENIED");
    }
  }

  @Test
  public void joinAttemptsRetainServerWideEmergencyCeiling()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    for (int attempt = 0; attempt < 30; attempt++)
    {
      Client attacker = rig.client();
      attacker.principal = "user:global-" + attempt;
      attacker.hello();
      JsonObject request = attacker.request("JOIN");
      request.addProperty("joinCode", deniedCode(host.joinCode, attempt));
      assertEquals(attacker.exchange(request).get("code").getAsString(), "JOIN_DENIED");
    }
    Client blocked = rig.client();
    blocked.principal = "user:global-blocked";
    blocked.hello();
    JsonObject request = blocked.request("JOIN");
    request.addProperty("joinCode", deniedCode(host.joinCode, 30));
    JsonObject limited = blocked.exchange(request);

    assertEquals(limited.get("code").getAsString(), "JOIN_RATE_LIMITED");
    assertEquals(limited.get("retryAfterMs").getAsLong(), 60000L);
  }

  @Test
  public void joinCodeRotationReportsCooldown()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    JsonObject rotate = host.exchange(host.request("ROTATE_CODE"));

    assertEquals(rotate.get("code").getAsString(), "CODE_ROTATION_COOLDOWN");
    assertEquals(rotate.get("retryAfterMs").getAsLong(), 10000L);
  }

  @Test
  public void preparationUsesExistingPlaybackAdapterAtCanonicalPosition()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    JsonObject created = host.create();

    JsonObject accepted = host.prepare();

    assertEquals(host.prepares, 1);
    assertEquals(host.preparationPosition, created.get("startPositionMs").getAsLong());
    assertEquals(accepted.get("targetPositionMs").getAsLong(), host.preparationPosition);
  }

  @Test
  public void snapshotRefreshesCompleteGrowingMediaWindow()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.capabilities = new HashSet<>(CAPABILITIES);
    host.capabilities.add(GROWING_MEDIA);
    host.availableEnd = 120000;
    host.growing = true;
    host.create();

    host.availableEnd = 180000;
    JsonObject snapshot = host.snapshot();

    assertEquals(snapshot.get("durationMs").getAsLong(), 180000L);
    assertTrue(snapshot.get("mediaGrowing").getAsBoolean());
    assertEquals(snapshot.get("availableEndMs").getAsLong(), 180000L);
    assertEquals(snapshot.get("playableEndMs").getAsLong(), 177000L);
    assertEquals(snapshot.get("liveEdgeDelayMs").getAsLong(), 3000L);
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
  public void omittedCreateMediaReportsCurrentMediaUnavailable()
  {
    Rig rig = new Rig();
    Client pathNotReady = rig.client();
    pathNotReady.hello();
    pathNotReady.currentMediaFailure = "MEDIA_PATH_NOT_READY";

    JsonObject pathError = pathNotReady.exchange(pathNotReady.request("CREATE"));

    assertEquals(pathError.get("code").getAsString(), "CURRENT_MEDIA_UNAVAILABLE");

    Client unavailable = rig.client();
    unavailable.hello();
    unavailable.currentMediaWindowUnavailable = true;

    JsonObject mediaError = unavailable.exchange(unavailable.request("CREATE"));

    assertEquals(mediaError.get("code").getAsString(), "CURRENT_MEDIA_UNAVAILABLE");
  }

  @Test
  public void explicitCreateMediaRetainsSpecificMediaUnavailableError()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.hello();
    host.currentMediaWindowUnavailable = true;
    JsonObject request = host.request("CREATE");
    request.addProperty("mediaFileId", 42);

    JsonObject response = host.exchange(request);

    assertEquals(response.get("code").getAsString(), "MEDIA_UNAVAILABLE");
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
    assertEquals(host.scheduled.kind(), "PLAY");
    assertEquals(guest.scheduled.kind(), "PLAY");
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
  public void correctionNoneUsesPausedCoverRemovalWithoutSeekOrFrameCallback()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    JsonObject accepted = host.prepare();
    rig.service.preparationResult(
        host, accepted.get("preparationId").getAsString(),
        PartyPlayerAdapter.PreparationResult.loaded(0, 1));
    host.currentPosition = host.preparationPosition;
    host.calibrate();
    long stateVersion = host.snapshot().get("stateVersion").getAsLong();
    JsonObject ready = host.request("READY");
    ready.addProperty("stateVersion", stateVersion);
    ready.addProperty("bufferAheadMs", 0);

    JsonObject correction = host.exchange(ready);

    assertEquals(correction.get("type").getAsString(), "CORRECTION");
    assertEquals(correction.get("kind").getAsString(), "NONE");
    assertEquals(correction.get("targetState").getAsString(), "PAUSED");
    assertEquals(correction.get("targetPositionMs").getAsLong(), host.preparationPosition);
    assertEquals(host.schedules, 0,
        "PREPARE already positioned the paused renderer at the initial target");
    host.presented(correction, "PAUSED", host.preparationPosition);
    assertTrue(host.progressOwner);
    assertEquals(host.action("PLAY").get("type").getAsString(), "COMMAND");
  }

  @Test
  public void samePositionWithoutPausedPreparationStillRequiresCorrection()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    JsonObject accepted = host.prepare();
    rig.service.preparationResult(
        host, accepted.get("preparationId").getAsString(),
        PartyPlayerAdapter.PreparationResult.loaded(0, 1));
    host.currentPosition = host.preparationPosition;
    host.preparedPaused = false;
    host.calibrate();
    long stateVersion = host.snapshot().get("stateVersion").getAsLong();
    JsonObject ready = host.request("READY");
    ready.addProperty("stateVersion", stateVersion);
    ready.addProperty("bufferAheadMs", 0);

    JsonObject response = host.exchange(ready);

    assertEquals(response.get("type").getAsString(), "CORRECTION");
    assertEquals(response.get("kind").getAsString(), "HARD");
    assertEquals(host.schedules, 1);
  }

  @Test
  public void repeatedPausedHeartbeatMayReuseAdmittedVisibleFrameSample()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    JsonObject accepted = host.prepare();
    rig.service.preparationResult(
        host, accepted.get("preparationId").getAsString(),
        PartyPlayerAdapter.PreparationResult.loaded(0, 1));
    host.currentPosition = host.preparationPosition;
    host.calibrate();
    long stateVersion = host.snapshot().get("stateVersion").getAsLong();
    JsonObject ready = host.request("READY");
    ready.addProperty("stateVersion", stateVersion);
    ready.addProperty("bufferAheadMs", 0);
    JsonObject correction = host.exchange(ready);
    host.presented(correction, "PAUSED", host.preparationPosition);
    long sampleMonoMs = host.lastPresentedSampleMono;

    rig.now.addAndGet(10000);
    JsonObject first = host.heartbeatAt("PAUSED", host.preparationPosition, sampleMonoMs);
    rig.now.addAndGet(10000);
    JsonObject second = host.heartbeatAt("PAUSED", host.preparationPosition, sampleMonoMs);
    rig.now.addAndGet(PartyClock.FRESHNESS_MS + 1);
    host.calibrate(250);
    JsonObject recalibrated =
        host.heartbeatAt("PAUSED", host.preparationPosition, sampleMonoMs);
    JsonObject changed =
        host.heartbeatAt("PAUSED", host.preparationPosition + 1, sampleMonoMs);
    JsonObject playing = host.heartbeatAt("PLAYING", host.preparationPosition, sampleMonoMs);

    assertEquals(first.get("type").getAsString(), "HEARTBEAT_ACK");
    assertEquals(second.get("type").getAsString(), "HEARTBEAT_ACK");
    assertEquals(recalibrated.get("type").getAsString(), "HEARTBEAT_ACK");
    assertEquals(changed.get("code").getAsString(), "STALE_SAMPLE");
    assertEquals(playing.get("code").getAsString(), "STALE_SAMPLE");
  }

  @Test
  public void uncoveredPlayingPresentedDoesNotRequireCoverEvidence()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    host.prepareAndReady();
    JsonObject play = host.action("PLAY");

    host.presentedWithoutCover(
        play, "PLAYING", play.get("targetPositionMs").getAsLong());

    assertEquals(host.status(), "ACTIVE");
  }

  @Test
  public void coveredPlayingPresentedStillRequiresCoverEvidence()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    host.prepareAndReady();
    JsonObject play = host.action("PLAY");
    host.presentedWithoutCover(
        play, "PLAYING", play.get("targetPositionMs").getAsLong());
    JsonObject jump = host.jump(120000);

    JsonObject response =
        host.presentedResponse(jump, "PLAYING", 120000, false);

    assertEquals(response.get("type").getAsString(), "ERROR");
    assertEquals(response.get("code").getAsString(), "INVALID_PRESENTATION");
    assertEquals(host.status(), "LATE");
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
    assertEquals(host.status(), "ACTIVE");
    assertEquals(guest.status(), "LATE");
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
    long lastVisiblePosition = play.get("targetPositionMs").getAsLong();
    host.presented(play, "PLAYING", lastVisiblePosition);

    rig.now.addAndGet(10000);
    JsonObject stopped = host.heartbeat("STOPPED", lastVisiblePosition);

    assertEquals(stopped.get("type").getAsString(), "HEARTBEAT_ACK");
    assertNull(host.event("JOIN_CODE"));
    assertNotNull(host.event("HOST_CHANGED"));
    assertEquals(guest.event("COMMAND").get("targetState").getAsString(), "PAUSED");
    assertEquals(guest.snapshot().get("hostParticipantId").getAsString(), guest.participantId);
  }

  @DataProvider(name = "removedPartyMessages")
  public Object[][] removedPartyMessages()
  {
    return new Object[][] {
        { "STATUS" },
        { "POSITION" },
        { "SYNC" },
        { "SELECT_SURFACE" },
        { "RESTORE_SURFACE" },
        { "MEDIA_ADMISSION" },
        { "POLICY" },
        { "CORRECTION_RESULT" },
        { "REJOIN" }
    };
  }

  @Test(dataProvider = "removedPartyMessages")
  public void removedPrototypeMessagesAreRejected(String type)
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();

    JsonObject response = host.exchange(host.request(type));

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
  public void repeatedSkewedPlayingHeartbeatsNeverTriggerReconvergence()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    host.prepareAndReady();
    JsonObject play = host.action("PLAY");
    long target = play.get("targetPositionMs").getAsLong();
    host.presented(play, "PLAYING", target);
    int schedules = host.schedules;
    host.messages.clear();

    host.heartbeat("PLAYING", target + 10000);
    host.heartbeat("PLAYING", target - 10000);
    host.heartbeat("PLAYING", target + 20000);
    host.heartbeat("PLAYING", target);

    assertNull(host.event("CORRECTION"));
    assertEquals(host.schedules, schedules);
    assertEquals(host.status(), "ACTIVE");
  }

  @Test
  public void alignedPlayingRecoveryAfterBufferingReturnsDirectlyToActive()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    host.prepareAndReady();
    JsonObject play = host.action("PLAY");
    long target = play.get("targetPositionMs").getAsLong();
    host.presented(play, "PLAYING", target);
    int schedules = host.schedules;
    JsonObject buffering = host.heartbeat("BUFFERING", target);
    long desired = buffering.get("desiredPositionMs").getAsLong();
    assertEquals(host.status(), "LATE");
    host.messages.clear();

    host.heartbeat("PLAYING", desired + 1000);

    assertNull(host.event("CORRECTION"));
    assertEquals(host.schedules, schedules);
    assertEquals(host.status(), "ACTIVE");
  }

  @Test
  public void misalignedPlayingRecoveryAfterBufferingUsesOneHardCorrection()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    host.prepareAndReady();
    JsonObject play = host.action("PLAY");
    long target = play.get("targetPositionMs").getAsLong();
    host.presented(play, "PLAYING", target);
    JsonObject buffering = host.heartbeat("BUFFERING", target);
    long desired = buffering.get("desiredPositionMs").getAsLong();
    int schedules = host.schedules;
    host.messages.clear();

    host.heartbeat("PLAYING", desired + 6000);

    JsonObject correction = host.event("CORRECTION");
    assertNotNull(correction);
    assertEquals(correction.get("kind").getAsString(), "HARD");
    assertEquals(correction.get("reason").getAsString(), "BUFFERING_RECOVERY");
    assertEquals(host.schedules, schedules + 1);
    assertEquals(host.status(), "ACTIVATING");
    host.heartbeat("PLAYING", desired + 7000);
    assertEquals(host.schedules, schedules + 1);
  }

  @Test
  public void staleBufferingAndRecoverySamplesDoNotChangeRecoveryState()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    host.prepareAndReady();
    JsonObject play = host.action("PLAY");
    long target = play.get("targetPositionMs").getAsLong();
    host.presented(play, "PLAYING", target);
    long currentVersion = host.snapshot().get("stateVersion").getAsLong();
    JsonObject staleBuffering = host.request("HEARTBEAT");
    staleBuffering.addProperty("stateVersion", currentVersion - 1);
    staleBuffering.addProperty("sampleMonoMs", rig.now.get() - 100);
    staleBuffering.addProperty("positionMs", target);
    staleBuffering.addProperty("bufferAheadMs", 0);
    staleBuffering.addProperty("state", "BUFFERING");

    JsonObject staleBufferingError = host.exchange(staleBuffering);

    assertEquals(staleBufferingError.get("code").getAsString(), "STALE_STATE");
    assertEquals(host.status(), "ACTIVE");
    host.heartbeat("BUFFERING", target);
    int schedules = host.schedules;
    JsonObject staleRecovery = host.heartbeatAt(
        "PLAYING", target + 10000, host.lastHeartbeatSampleMono);

    assertEquals(staleRecovery.get("code").getAsString(), "STALE_SAMPLE");
    assertEquals(host.status(), "LATE");
    assertEquals(host.schedules, schedules);
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
    JsonObject accepted = host.prepare();
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

    rig.service.preparationResult(host, preparationId,
        PartyPlayerAdapter.PreparationResult.loaded(0, 7));
    assertEquals(host.eventCount("PREPARATION_RESULT"), 1,
        "stale completion cannot alter a timed-out preparation");
  }

  @Test
  public void repeatedPrepareReusesPendingIdWhenMediaIsAlreadyLoaded()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    JsonObject first = host.prepare();
    host.preparedGeneration = 7;
    rig.service.preparationResult(
        host, first.get("preparationId").getAsString(),
        PartyPlayerAdapter.PreparationResult.loaded(0, host.preparedGeneration));
    assertEquals(host.event("PREPARATION_RESULT").get("status").getAsString(), "LOADED");
    int bindingEvents = host.eventCount("MEDIA_OPEN_BINDING");

    JsonObject repeated = host.exchange(host.request("PREPARE"));

    assertEquals(repeated.get("type").getAsString(), "PREPARE_ACCEPTED");
    assertEquals(repeated.get("preparationId"), first.get("preparationId"));
    assertEquals(repeated.get("preparationBinding"), first.get("preparationBinding"));
    assertTrue(repeated.get("openReused").getAsBoolean());
    assertEquals(host.prepares, 1);
    assertEquals(host.eventCount("MEDIA_OPEN_BINDING"), bindingEvents);
    JsonObject opened = host.request("MEDIA_OPENED");
    opened.add("preparationId", repeated.get("preparationId"));
    opened.add("preparationBinding", repeated.get("preparationBinding"));
    opened.addProperty("localOpenGeneration", host.preparedGeneration);
    opened.addProperty("surfaceGeneration", 1);
    opened.addProperty("reused", true);
    JsonObject openedAck = host.exchange(opened);
    assertEquals(openedAck.get("type").getAsString(), "ACK");
    assertEquals(openedAck.get("preparationId"), repeated.get("preparationId"));
    assertEquals(openedAck.get("preparationBinding"),
        repeated.get("preparationBinding"));
    assertEquals(host.event("PREPARATION_RESULT").get("status").getAsString(), "LOADED");

    host.preparedPaused = false;
    host.calibrate();
    long readyVersion = host.snapshot().get("stateVersion").getAsLong();
    JsonObject ready = host.request("READY");
    ready.addProperty("stateVersion", readyVersion);
    ready.addProperty("bufferAheadMs", 0);
    JsonObject recovery = host.exchange(ready);

    assertEquals(recovery.get("type").getAsString(), "CORRECTION", recovery.toString());
    assertEquals(recovery.get("reason").getAsString(), "REUSED_OPEN_RECOVERY");
    assertEquals(recovery.get("kind").getAsString(), "HARD");
    assertTrue(recovery.get("barrier").getAsBoolean());
    assertEquals(recovery.get("phase").getAsString(), "PREPARE");
    assertTrue(recovery.has("visibleAtServerMonoMs"));
    assertTrue(recovery.has("predictedPresentationLatencyMs"));
    assertTrue(recovery.has("presentationUncertaintyMs"));
  }

  @Test
  public void loadedPreparationAtomicallyCancelsItsTimeout()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    JsonObject accepted = host.prepare();
    int cancelsAfterAccept = host.preparationCancels;
    rig.service.preparationResult(
        host, accepted.get("preparationId").getAsString(),
        PartyPlayerAdapter.PreparationResult.loaded(0, 7));

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
    JsonObject accepted = host.prepare();
    int cancelsAfterAccept = host.preparationCancels;
    host.preparedDeliveryFails = true;

    rig.service.preparationResult(
        host, accepted.get("preparationId").getAsString(),
        PartyPlayerAdapter.PreparationResult.loaded(0, 7));

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
    JsonObject accepted = host.prepare();
    rig.service.preparationResult(
        host, accepted.get("preparationId").getAsString(),
        PartyPlayerAdapter.PreparationResult.loaded(0, 1));
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
    presented.addProperty("sampleUncertaintyMs", 32);
    presented.addProperty("positionMs", correction.get("targetPositionMs").getAsLong());
    presented.addProperty("state", "PLAYING");

    JsonObject error = host.exchange(presented);

    assertEquals(error.get("type").getAsString(), "ERROR");
    assertEquals(error.get("code").getAsString(), "INVALID_PRESENTATION");
    assertEquals(host.snapshot().get("roster").getAsJsonArray().get(0)
        .getAsJsonObject().get("status").getAsString(), "LATE");
  }

  @Test
  public void earlyArmedPositionRequiresFreshCorrelatedClientReadiness()
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
    jumpRequest.addProperty("targetPositionMs", 180000);
    JsonObject jump = host.exchange(jumpRequest);
    host.currentPosition = 120000;

    JsonObject armed = host.arm(jump, 120000);

    assertEquals(armed.get("type").getAsString(), "ACK");
    assertNull(host.event("REVEAL"));
    assertEquals(host.snapshot().get("roster").getAsJsonArray().get(0)
        .getAsJsonObject().get("status").getAsString(), "ACTIVATING");

    host.currentPosition = 180000;
    rig.now.incrementAndGet();
    rig.service.tick();

    assertNull(host.event("REVEAL"));
    JsonObject freshArmed = host.arm(jump, 180000);
    host.held(jump, 180000);
    JsonObject reveal = host.event("REVEAL");
    assertEquals(freshArmed.get("type").getAsString(), "ACK");
    assertNotNull(reveal);
    assertEquals(reveal.get("targetPositionMs").getAsLong(), 180000L);
    JsonObject presented = copy(jump);
    presented.remove("barrier");
    presented.addProperty("executeAtServerMonoMs",
        reveal.get("executeAtServerMonoMs").getAsLong());
    host.presented(presented, "PLAYING", 180000);
    assertEquals(host.snapshot().get("roster").getAsJsonArray().get(0)
        .getAsJsonObject().get("status").getAsString(), "ACTIVE");
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
  public void stateOnlyPauseSettlesOnlyAfterPresentedHeldFrame()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    host.prepareAndReady();
    JsonObject play = host.action("PLAY");
    host.presented(play, "PLAYING", play.get("targetPositionMs").getAsLong());

    JsonObject pause = host.action("PAUSE");
    JsonObject executed = host.executed(pause);
    JsonObject blocked = host.action("PLAY");

    assertEquals(executed.get("stage").getAsString(), "EXECUTED");
    assertFalse(pause.has("barrier"));
    assertEquals(blocked.get("code").getAsString(), "TRANSITION_PENDING");

    host.presented(pause, "PAUSED", pause.get("targetPositionMs").getAsLong());
    JsonObject snapshot = host.snapshot();

    assertFalse(snapshot.has("pendingCommand"));
    assertFalse(snapshot.has("barrierPending"));
    assertEquals(snapshot.get("state").getAsString(), "PAUSED");
    assertEquals(host.action("PLAY").get("type").getAsString(), "COMMAND");
  }

  @Test
  public void failedBarrierCohortCannotAdvanceCanonicalTimeline()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    host.prepareAndReady();
    JsonObject play = host.action("PLAY");
    host.presented(play, "PLAYING", play.get("targetPositionMs").getAsLong());
    JsonObject before = host.snapshot();
    JsonObject jump = host.jump(180000);

    rig.now.addAndGet(21000);
    rig.service.tick();
    JsonObject after = host.snapshot();

    assertEquals(host.event("ERROR").get("code").getAsString(), "ACTIVATION_EXPIRED");
    assertFalse(after.has("pendingCommand"));
    assertFalse(after.has("barrierPending"));
    assertEquals(after.get("stateVersion").getAsLong(), jump.get("stateVersion").getAsLong());
    assertEquals(after.get("state").getAsString(), "PLAYING");
    assertNotEquals(after.get("anchorPositionMs").getAsLong(), 180000L);
    assertTrue(after.get("anchorPositionMs").getAsLong()
        >= before.get("anchorPositionMs").getAsLong());
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
    assertNull(guests[2].event("REVEAL"));
    assertNotNull(host.event("HOLD_CONFIRM"));
    assertNotNull(guests[0].event("HOLD_CONFIRM"));
    assertNotNull(guests[1].event("HOLD_CONFIRM"));
    assertNotNull(guests[2].event("HOLD_CONFIRM"));
    host.held(hostJump, 180000);
    guests[0].held(guestJump[0], 180000);
    guests[1].held(guestJump[1], 180000);
    guests[2].held(guestJump[2], 180000);
    int armedAckIndex = -1;
    int heldAckIndex = -1;
    int revealIndex = -1;
    for (int i = lastGuestMessageStart; i < guests[2].messages.size(); i++)
    {
      JsonObject message = guests[2].messages.get(i);
      if ("ACK".equals(message.get("type").getAsString()) && message.has("requestId"))
      {
        if ("ARMED".equals(message.get("stage").getAsString())) armedAckIndex = i;
        if ("HELD".equals(message.get("stage").getAsString())) heldAckIndex = i;
      }
      if ("REVEAL".equals(message.get("type").getAsString()) && !message.has("requestId"))
        revealIndex = i;
    }
    assertTrue(armedAckIndex >= lastGuestMessageStart);
    assertTrue(heldAckIndex > armedAckIndex);
    assertTrue(revealIndex > heldAckIndex,
        "correlated HELD response must be queued before REVEAL");

    JsonObject hostReveal = host.event("REVEAL");
    JsonObject[] guestReveal = new JsonObject[guests.length];
    for (int i = 0; i < guests.length; i++)
      guestReveal[i] = guests[i].event("REVEAL");
    assertNotNull(hostReveal);
    assertEquals(hostReveal.get("partyId").getAsString(), host.partyId);
    assertEquals(hostReveal.get("directiveId").getAsString(),
        hostJump.get("directiveId").getAsString());
    assertEquals(hostReveal.get("stateVersion").getAsLong(), 2L);
    assertEquals(hostReveal.get("commandSeq").getAsLong(), 2L);
    for (int i = 0; i < guestReveal.length; i++)
    {
      JsonObject reveal = guestReveal[i];
      assertNotNull(reveal);
      assertEquals(reveal.get("partyId").getAsString(), guests[i].partyId);
      assertEquals(reveal.get("directiveId").getAsString(),
          guestJump[i].get("directiveId").getAsString());
      assertEquals(reveal.get("stateVersion").getAsLong(), 2L);
      assertEquals(reveal.get("commandSeq").getAsLong(), 2L);
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
  public void canonicalProgressPersistsAtResumeCadence()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    host.prepareAndReady();
    host.presented(host.jump(120000), "PAUSED", 120000);
    host.commits.clear();

    rig.now.addAndGet(PartyService.RESUME_COMMIT_INTERVAL_MS);
    rig.service.tick();
    host.presented(host.jump(180000), "PAUSED", 180000);
    rig.service.tick();

    assertEquals(host.commits, List.of(120000L));
    rig.now.addAndGet(PartyService.RESUME_COMMIT_INTERVAL_MS);
    rig.service.tick();
    assertEquals(host.commits, List.of(120000L, 180000L));
  }

  @Test
  public void disconnectImmediatelyPersistsAndHoldsLaterProgress()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    Client guest = rig.client();
    host.create();
    guest.join(host);
    host.prepareAndReady();
    host.presented(host.jump(120000), "PAUSED", 120000);
    host.commits.clear();

    rig.service.disconnected(host);
    guest.prepareAndReady();
    guest.presented(guest.jump(240000), "PAUSED", 240000);
    rig.now.addAndGet(1000);
    rig.service.tick();

    assertEquals(host.commits, List.of(120000L));
    assertTrue(guest.commits.isEmpty());
  }

  @Test
  public void endingDuringDisconnectHoldRetainsDisconnectPosition()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    Client guest = rig.client();
    host.create();
    guest.join(host);
    host.prepareAndReady();
    host.presented(host.jump(120000), "PAUSED", 120000);
    host.commits.clear();
    rig.service.disconnected(host);
    guest.prepareAndReady();
    guest.presented(guest.jump(240000), "PAUSED", 240000);

    JsonObject ended = guest.exchange(guest.request("END"));

    assertEquals(ended.get("type").getAsString(), "ENDED");
    assertEquals(host.commits, List.of(120000L));
    assertTrue(guest.commits.isEmpty());
  }

  @Test
  public void progressPersistenceResumesAfterDisconnectHold()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    Client guest = rig.client();
    host.create();
    guest.join(host);
    host.prepareAndReady();
    host.presented(host.jump(120000), "PAUSED", 120000);
    host.commits.clear();
    rig.service.disconnected(host);
    guest.prepareAndReady();
    guest.presented(guest.jump(240000), "PAUSED", 240000);

    rig.now.addAndGet(PartyService.RESUME_DISCONNECT_HOLD_MS + 1);
    rig.service.tick();

    assertEquals(host.commits, List.of(120000L));
    assertEquals(guest.commits, List.of(240000L));
  }

  @Test
  public void repeatedDisconnectRestartsResumeHold()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    Client guest = rig.client();
    Client remaining = rig.client();
    host.create();
    guest.join(host);
    remaining.join(host);
    host.prepareAndReady();
    host.presented(host.jump(100000), "PAUSED", 100000);
    host.commits.clear();
    rig.service.disconnected(host);
    guest.prepareAndReady();
    guest.presented(guest.jump(200000), "PAUSED", 200000);
    rig.now.addAndGet(30000);
    rig.service.disconnected(guest);
    remaining.prepareAndReady();
    remaining.presented(remaining.jump(300000), "PAUSED", 300000);

    rig.now.addAndGet(PartyService.RESUME_DISCONNECT_HOLD_MS - 30001);
    rig.service.tick();
    assertTrue(remaining.commits.isEmpty());
    rig.now.addAndGet(30002);
    rig.service.tick();

    assertEquals(host.commits, List.of(100000L));
    assertEquals(guest.commits, List.of(200000L));
    assertEquals(remaining.commits, List.of(300000L));
  }

  @Test
  public void newerGenerationRewindReplacesHigherPosition()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    host.prepareAndReady();
    host.presented(host.jump(200000), "PAUSED", 200000);
    host.presented(host.jump(100000), "PAUSED", 100000);
    host.commits.clear();

    rig.now.addAndGet(PartyService.RESUME_COMMIT_INTERVAL_MS);
    rig.service.tick();

    assertEquals(host.commits, List.of(100000L));
  }

  @Test
  public void staleSampleCannotReplaceFreshCanonicalPosition()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    host.prepareAndReady();
    host.presented(host.jump(100000), "PAUSED", 100000);
    JsonObject heartbeat = host.heartbeat("PAUSED", 100000);
    assertEquals(heartbeat.get("type").getAsString(), "HEARTBEAT_ACK");
    long staleSample = rig.now.get() - 100;
    long stateVersion = host.snapshot().get("stateVersion").getAsLong();
    JsonObject stale = host.request("HEARTBEAT");
    stale.addProperty("stateVersion", stateVersion);
    stale.addProperty("sampleMonoMs", staleSample);
    stale.addProperty("positionMs", 200000);
    stale.addProperty("bufferAheadMs", 0);
    stale.addProperty("state", "PAUSED");

    JsonObject error = host.exchange(stale);
    host.commits.clear();
    rig.service.disconnected(host);

    assertEquals(error.get("code").getAsString(), "STALE_SAMPLE");
    assertEquals(host.commits, List.of(100000L));
  }

  @Test
  public void endingPartyCommitsLatestCanonicalPosition()
  {
    Rig rig = new Rig();
    Client host = rig.client();
    host.create();
    host.prepareAndReady();
    host.presented(host.jump(150000), "PAUSED", 150000);
    host.commits.clear();

    JsonObject ended = host.exchange(host.request("END"));

    assertEquals(ended.get("type").getAsString(), "ENDED");
    assertEquals(host.commits, List.of(150000L));
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
    JsonObject accepted = host.prepare();
    rig.service.preparationResult(
        host, accepted.get("preparationId").getAsString(),
        PartyPlayerAdapter.PreparationResult.loaded(0, 1));
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
    presented.addProperty("sampleUncertaintyMs", 32);
    presented.addProperty("positionMs", correction.get("targetPositionMs").getAsLong());
    presented.addProperty("state", "PAUSED");

    JsonObject response = host.exchange(presented);

    assertEquals(response.get("type").getAsString(), "ACK", response.toString());
  }
}
