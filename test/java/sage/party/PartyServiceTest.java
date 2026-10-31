package sage.party;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.testng.annotations.Test;
import sage.epg.sd.gson.JsonObject;
import static org.testng.Assert.*;
import static sage.party.PartyProtocol.*;

/** Executable synthetic NG clients using the same property framing and service as production. */
public class PartyServiceTest
{
  static final class Client implements PartyService.Peer
  {
    final PartyService server;
    final AtomicLong now;
    final List<JsonObject> messages = new ArrayList<>();
    String principal = "verified-user";
    Set<String> caps = REQUIRED;
    String epoch, party, participant, token, recovery, sessionToken;
    long sequence;
    boolean owned;
    boolean mediaAdmitted = true;
    boolean growing;
    long availableEnd = 3600000;
    long currentPosition;
    int currentPositionReads, prepares;
    String preparationId;
    String selectionId;
    String selectedSelectionId;
    long pathGeneration;
    int restores, selections;
    int commits, skips;
    long committedPosition;
    PartyService.Delivery path = new PartyService.Delivery("android", "push", "H264", "AAC", 8000, false);

    Client(PartyService server, AtomicLong now)
    { this.server = server; this.now = now; }

    JsonObject request(String type)
    {
      JsonObject o = envelope(type);
      o.addProperty("requestId", "r" + ++sequence);
      o.addProperty("requestSeq", sequence);
      if (epoch != null) o.addProperty("serverEpoch", epoch);
      if (sessionToken != null) o.addProperty("sessionToken", sessionToken);
      if (party != null) o.addProperty("partyId", party);
      return o;
    }

    JsonObject sendRequest(JsonObject request)
    {
      byte[] name = PROPERTY.getBytes(StandardCharsets.US_ASCII);
      byte[] json = request.toString().getBytes(StandardCharsets.UTF_8);
      ByteBuffer b = ByteBuffer.allocate(4 + name.length + json.length);
      b.putShort((short)name.length).putShort((short)json.length).put(name).put(json);
      assertTrue(PartyWire.accept(b.array(), text -> server.receive(this, text)));
      return messages.get(messages.size() - 1);
    }

    void hello()
    {
      JsonObject r = sendRequest(request("HELLO"));
      assertEquals(r.get("type").getAsString(), "AVAILABLE");
      epoch = r.get("serverEpoch").getAsString();
      sessionToken = r.get("sessionToken").getAsString();
    }

    JsonObject create()
    {
      hello();
      JsonObject r = request("CREATE"); r.addProperty("mediaFileId", 42);
      JsonObject out = sendRequest(r);
      remember(out);
      token = out.get("joinCode").getAsString();
      return out;
    }

    JsonObject join(Client host)
    {
      hello(); party = host.party;
      JsonObject r = request("JOIN");
      r.addProperty("mediaFileId", 42); r.addProperty("joinCode", host.token);
      JsonObject out = sendRequest(r);
      if ("JOINED".equals(out.get("type").getAsString())) remember(out);
      return out;
    }

    void remember(JsonObject out)
    {
      party = out.get("partyId").getAsString();
      participant = out.get("participantId").getAsString();
      recovery = out.get("recoveryToken").getAsString();
    }

    JsonObject snapshot() { return sendRequest(request("SNAPSHOT")); }

    void calibrate()
    {
      calibrate(5, 5);
    }

    void calibrate(long forwardMs, long returnMs)
    {
      now.addAndGet(1000);
      for (int i = 0; i < 5; i++)
      {
        JsonObject probe = sendRequest(request("CLOCK"));
        long start = probe.get("s0").getAsLong();
        now.addAndGet(forwardMs + returnMs);
        JsonObject reply = request("CLOCK_REPLY");
        reply.addProperty("probeId", probe.get("probeId").getAsString());
        reply.addProperty("c1", start + forwardMs - 100);
        reply.addProperty("c2", start + forwardMs - 100);
        JsonObject model = sendRequest(reply);
        assertNotNull(model.get("confident"), model.toString());
        if (i == 4) assertTrue(model.get("confident").getAsBoolean(), model.toString());
      }
    }

    JsonObject ready()
    {
      calibrate();
      JsonObject r = request("READY");
      r.addProperty("stateVersion", snapshot().get("stateVersion").getAsLong());
      r.addProperty("prepared", true); r.addProperty("activated", true);
      r.addProperty("bufferAheadMs", 2000); r.addProperty("precisionMs", 50);
      r.addProperty("rateAdjust", false);
      // snapshot above consumes a higher requestSeq, so allocate the request after that read.
      r.addProperty("requestId", "r" + ++sequence); r.addProperty("requestSeq", sequence);
      JsonObject out = sendRequest(r);
      if ("CORRECTION".equals(out.get("type").getAsString())) landed(out, true);
      return out;
    }

    JsonObject landed(JsonObject correction, boolean success)
    {
      now.set(Math.max(now.get(), correction.get("executeAtServerMonoMs").getAsLong()));
      JsonObject r = request("CORRECTION_RESULT");
      r.addProperty("stateVersion", correction.get("stateVersion").getAsLong());
      r.addProperty("correctionId", correction.get("correctionId").getAsString());
      r.addProperty("success", success);
      r.addProperty("sampleMonoMs", now.get() - 100);
      r.addProperty("positionMs", correction.get("targetPositionMs").getAsLong());
      return sendRequest(r);
    }

    JsonObject action(String action)
    {
      long version = snapshot().get("stateVersion").getAsLong();
      JsonObject r = request("ACTION");
      r.addProperty("stateVersion", version); r.addProperty("action", action);
      return sendRequest(r);
    }

    JsonObject status(String status)
    {
      JsonObject r = request("STATUS"); r.addProperty("status", status); return sendRequest(r);
    }

    @Override public String principal() { return principal; }
    @Override public Set<String> capabilities() { return caps; }
    @Override public void send(JsonObject message) { messages.add(copy(message)); }
    @Override public long duration(int id)
    { if (id != 42) throw new Fault("MEDIA_UNAVAILABLE"); return availableEnd; }
    @Override public PartyService.MediaWindow mediaWindow(int id)
    { return new PartyService.MediaWindow(duration(id), growing); }
    @Override public long currentPosition(int media) { currentPositionReads++; return currentPosition; }
    @Override public void prepare(int media, String id) { assertEquals(media, 42); prepares++; preparationId = id; }
    @Override public void selectSurface(int media, String surface, String id)
    { assertEquals(media, 42); selectionId = id; selections++; }
    @Override public long applySurface(String id)
    { assertEquals(id, selectionId); selectedSelectionId = id; return ++pathGeneration; }
    @Override public long restoreSurface()
    { restores++; selectedSelectionId = null; return ++pathGeneration; }
    @Override public boolean surfaceSelected(String id, long generation)
    { return id.equals(selectedSelectionId) && generation == pathGeneration; }
    @Override public PartyService.Delivery delivery(int media) { return path; }
    @Override public boolean mediaAdmitted(int media) { return mediaAdmitted; }
    @Override public PartyService.Delivery plan(int media) { return path; }
    @Override public long skip(String action)
    { skips++; return action.startsWith("FF") ? 10000 : -10000; }
    @Override public void progressOwner(boolean own) { owned = own; }
    @Override public void commit(int media, long position) { commits++; committedPosition = position; }
    @Override public void schedule(JsonObject transition) {}
    @Override public void cancelScheduled() {}
  }

  static final class Rig
  {
    final AtomicLong now = new AtomicLong(100000);
    final PartyService service = new PartyService(now::get, true, 100000, 2);
    Client client() { return new Client(service, now); }
  }

  static void error(JsonObject out, String code)
  { assertEquals(out.get("type").getAsString(), "ERROR"); assertEquals(out.get("code").getAsString(), code); }

  @Test public void actualWireRejectsLegacyUnauthenticatedAndMissingGuarantees()
  {
    Rig r = new Rig(); Client c = r.client();
    c.principal = null; error(c.sendRequest(c.request("HELLO")), "NOT_AVAILABLE");
    c.principal = "verified"; c.caps = Set.of("PARTY_V1");
    error(c.sendRequest(c.request("HELLO")), "NOT_AVAILABLE");
    c.caps = REQUIRED; c.hello();
    assertFalse(PartyWire.accept(new byte[] {0, 0, 0, 0}, x -> fail("Legacy reply was consumed")));
    PartyService off = new PartyService(r.now::get, false, 100000, 2);
    Client disabled = new Client(off, r.now);
    error(disabled.sendRequest(disabled.request("HELLO")), "NOT_AVAILABLE");
  }

  @Test public void fiveParticipantsAndSixthDeniedWithMediaAndAuthorizationChecks()
  {
    Rig r = new Rig(); Client host = r.client(); host.create();
    for (int i = 0; i < 4; i++) assertEquals(r.client().join(host).get("type").getAsString(), "JOINED");
    error(r.client().join(host), "PARTY_FULL");
    assertEquals(host.snapshot().getAsJsonArray("roster").size(), 5);
    Client stranger = r.client(); stranger.hello(); stranger.party = host.party;
    error(stranger.snapshot(), "NOT_MEMBER");
    JsonObject bad = stranger.request("JOIN");
    bad.addProperty("joinCode", host.token); bad.addProperty("mediaFileId", 99);
    error(stranger.sendRequest(bad), "MEDIA_MISMATCH");
    r.now.addAndGet(1000);
    JsonObject retry = stranger.request("JOIN"); retry.addProperty("joinCode", "000");
    error(stranger.sendRequest(retry), "JOIN_RATE_LIMITED");
    r.now.addAndGet(60000);
    JsonObject invalid = stranger.request("JOIN");
    invalid.addProperty("joinCode", "wrong"); invalid.addProperty("mediaFileId", 42);
    error(stranger.sendRequest(invalid), "JOIN_DENIED");
  }

  @Test public void duplicateReorderedStaleAndChangedRequestsCannotExecuteTwice()
  {
    Rig r = new Rig(); Client h = r.client(); h.create(); h.ready();
    JsonObject action = h.request("ACTION"); action.addProperty("stateVersion", 0); action.addProperty("action", "PLAY");
    JsonObject first = h.sendRequest(action), duplicate = h.sendRequest(action);
    assertEquals(first, duplicate);
    assertEquals(h.snapshot().get("commandSeq").getAsLong(), 1);
    JsonObject mutated = copy(action); mutated.addProperty("action", "PAUSE");
    error(h.sendRequest(mutated), "REQUEST_ID_REUSED");
    JsonObject reordered = h.request("SNAPSHOT"); reordered.addProperty("requestSeq", 1);
    error(h.sendRequest(reordered), "STALE_REQUEST");
    JsonObject stale = h.request("ACTION"); stale.addProperty("stateVersion", 0); stale.addProperty("action", "PAUSE");
    error(h.sendRequest(stale), "STALE_STATE");
    error(h.action("PAUSE"), "TRANSITION_PENDING");
    r.now.addAndGet(1000);
    assertEquals(h.action("PAUSE").get("commandSeq").getAsLong(), 2);
  }

  @Test public void stopIsLocalWithNoTimelineMutationOrRemoteCommand()
  {
    Rig r = new Rig(); Client h = r.client(); h.create(); h.ready();
    Client c = r.client(); c.join(h); c.ready();
    JsonObject before = h.snapshot(); int prior = h.messages.size();
    error(c.action("STOP"), "INVALID_ACTION");
    c.status("LOCALLY_STOPPED");
    JsonObject after = h.snapshot();
    for (String field : List.of("stateVersion", "commandSeq", "anchorPositionMs", "anchorServerMonoMs", "state"))
      assertEquals(after.get(field), before.get(field));
    for (JsonObject message : h.messages.subList(prior, h.messages.size()))
      assertNotEquals(message.get("type").getAsString(), "COMMAND");
    assertEquals(c.commits, 0); assertTrue(c.owned);
    error(c.action("PLAY"), "NOT_READY");
    JsonObject rejoin = c.sendRequest(c.request("REJOIN"));
    assertEquals(rejoin.get("type").getAsString(), "JOINED");
    assertEquals(c.ready().get("type").getAsString(), "CORRECTION");
  }

  @Test public void ffRewResolveOnceAndKeepPausedWithScheduledResult()
  {
    Rig r = new Rig(); Client h = r.client(); h.create(); h.ready();
    Client c = r.client(); c.join(h); c.ready();
    long now = r.now.get();
    JsonObject ff = h.action("FF");
    assertEquals(ff.get("targetPositionMs").getAsLong(), 10000);
    assertEquals(ff.get("targetState").getAsString(), "PAUSED");
    assertEquals(ff.get("executeAtServerMonoMs").getAsLong(), now + 750);
    assertEquals(h.skips, 1); assertEquals(c.skips, 0);
    r.now.addAndGet(1000);
    JsonObject rew = c.action("REW");
    assertEquals(rew.get("targetPositionMs").getAsLong(), 0);
    assertEquals(c.skips, 1);
    assertEquals(h.snapshot().get("stateVersion").getAsLong(), 2);
  }

  @Test public void readinessClockActivationAndResourceDenialAreExplicit()
  {
    Rig r = new Rig(); Client h = r.client(); h.create();
    JsonObject premature = h.request("READY"); premature.addProperty("stateVersion", 0);
    premature.addProperty("prepared", true); premature.addProperty("activated", false);
    premature.addProperty("bufferAheadMs", 2000); premature.addProperty("precisionMs", 50);
    premature.addProperty("rateAdjust", false);
    error(h.sendRequest(premature), "NOT_READY");
    h.path = new PartyService.Delivery("pwa", "pull-xcode", "H264", "AAC", 200000, true);
    error(h.ready(), "RESOURCE_DENIED");
    assertEquals(h.snapshot().get("commandSeq").getAsLong(), 0);
    h.path = new PartyService.Delivery("android", "push", "H264", "AAC", 8000, false);
    assertEquals(h.ready().get("type").getAsString(), "CORRECTION");
  }

  @Test public void perViewerDeliveryAndTargetedCorrectionRemainIndependent()
  {
    Rig r = new Rig(); Client h = r.client(); h.create(); h.ready();
    Client c = r.client(); c.path = new PartyService.Delivery("pwa", "pull-xcode", "H264", "AAC", 5000, true);
    c.join(h); c.ready();
    JsonObject snapshot = h.snapshot();
    assertEquals(snapshot.getAsJsonArray("roster").get(0).getAsJsonObject()
        .getAsJsonObject("delivery").get("mode").getAsString(), "push");
    assertEquals(snapshot.getAsJsonArray("roster").get(1).getAsJsonObject()
        .getAsJsonObject("delivery").get("mode").getAsString(), "pull-xcode");
    int hostBefore = h.messages.size();
    JsonObject report = c.request("POSITION");
    report.addProperty("stateVersion", 0); report.addProperty("positionMs", 3000);
    report.addProperty("bufferAheadMs", 2000); report.addProperty("sampleMonoMs", r.now.get() - 100);
    report.addProperty("state", "PAUSED");
    c.sendRequest(report);
    assertEquals(h.messages.size(), hostBefore);
    JsonObject correction = c.messages.get(c.messages.size() - 2);
    assertEquals(correction.get("type").getAsString(), "CORRECTION");
    assertEquals(correction.get("kind").getAsString(), "HARD");
    assertEquals(c.landed(correction, false).get("type").getAsString(), "ACK");
  }

  @Test public void bufferingDefaultMovesPartyAndHostPolicyUsesPauseNeverStop()
  {
    Rig r = new Rig(); Client h = r.client(); h.create(); h.ready();
    Client c = r.client(); c.join(h); c.ready();
    h.action("PLAY"); r.now.addAndGet(1000); r.service.tick();
    long version = h.snapshot().get("stateVersion").getAsLong();
    c.status("BUFFERING");
    assertEquals(h.snapshot().get("stateVersion").getAsLong(), version);
    JsonObject policy = h.request("POLICY"); policy.addProperty("hostOnly", true); policy.addProperty("pauseOnBuffer", true);
    h.sendRequest(policy);
    c.ready();
    c.status("BUFFERING");
    JsonObject state = h.snapshot();
    assertEquals(state.get("stateVersion").getAsLong(), version + 1);
    assertEquals(state.get("state").getAsString(), "PAUSED");
    error(c.sendRequest(c.request("END")), "HOST_REQUIRED");
  }

  @Test public void reconnectAfterTenSecondOutageRetainsSlotAndRequiresVerifiedPrincipal()
  {
    Rig r = new Rig(); Client h = r.client(); h.create(); h.ready();
    Client c = r.client(); c.join(h); c.ready();
    r.service.disconnected(c);
    r.now.addAndGet(10000); r.service.tick();
    assertEquals(h.snapshot().getAsJsonArray("roster").size(), 2);
    Client replacement = r.client(); replacement.hello(); replacement.party = c.party;
    replacement.principal = "wrong-user";
    JsonObject resume = replacement.request("RESUME");
    resume.addProperty("participantId", c.participant); resume.addProperty("recoveryToken", c.recovery);
    error(replacement.sendRequest(resume), "REJOIN_DENIED");
    replacement.principal = c.principal;
    resume = replacement.request("RESUME");
    resume.addProperty("participantId", c.participant); resume.addProperty("recoveryToken", c.recovery);
    JsonObject result = replacement.sendRequest(resume);
    assertEquals(result.get("participantId").getAsString(), c.participant);
    replacement.remember(result);
    error(replacement.action("PLAY"), "NOT_READY");
    replacement.ready();
    assertEquals(h.snapshot().getAsJsonArray("roster").size(), 2);
  }

  @Test public void hostLossTransfersByJoinOrderAndHostOnlyLocksDuringGrace()
  {
    Rig r = new Rig(); Client h = r.client(); h.create(); h.ready();
    Client c = r.client(); c.join(h); c.ready();
    JsonObject policy = h.request("POLICY"); policy.addProperty("hostOnly", true); policy.addProperty("pauseOnBuffer", false);
    h.sendRequest(policy);
    r.service.disconnected(h);
    error(c.action("PLAY"), "HOST_REQUIRED");
    r.now.addAndGet(15001); r.service.tick();
    assertEquals(c.snapshot().get("hostParticipantId").getAsString(), c.participant);
    c.calibrate();
    c.sendRequest(c.request("REJOIN")); c.ready();
    assertEquals(c.action("PLAY").get("type").getAsString(), "COMMAND");
  }

  @Test public void explicitLeaveAndEndCommitButReportsAndShutdownDoNot()
  {
    Rig r = new Rig(); Client h = r.client(); h.create(); h.ready();
    Client c = r.client(); c.join(h); c.ready();
    assertEquals(h.commits, 0); assertEquals(c.commits, 0);
    c.sendRequest(c.request("LEAVE"));
    assertEquals(c.commits, 1); assertFalse(c.owned);
    h.sendRequest(h.request("END"));
    assertEquals(h.commits, 1); assertFalse(h.owned);
    Rig another = new Rig(); Client last = another.client(); last.create(); last.ready();
    another.service.shutdown();
    assertEquals(last.commits, 0); assertFalse(last.owned);
    error(last.snapshot(), "NOT_AVAILABLE");
    assertTrue(last.messages.stream().noneMatch(m -> m.toString().contains("STOP")));
  }

  @Test public void clockRejectsAsymmetricSpikesAndNeverUsesWallClockOrNetworkLabels()
  {
    PartyClock clock = new PartyClock();
    for (int i = 0; i < 5; i++) clock.add(10000 + i * 100, 9905 + i * 100, 9905 + i * 100, 10010 + i * 100);
    assertTrue(clock.confident(10500)); assertEquals(clock.offset(10500), 100.0);
    clock.add(10500, 10401, 10401, 11200); // large asymmetric spike, excluded from offset.
    assertEquals(clock.offset(11200), 100.0);
    assertEquals(clock.p95(11200), 700);
    assertFalse(clock.confident(30000));
    clock.reset(); assertFalse(clock.confident(11200));
  }

  @Test public void malformedBoundsAndRateLimitsAreExplicitAndDoNotBreakLegacyReplies()
  {
    Rig r = new Rig(); Client c = r.client(); c.hello();
    r.service.receive(c, "{\"schemaVersion\":1,\"type\":\"HELLO\",\"requestId\":\"a\",\"x\":" + "[".repeat(10));
    error(c.messages.get(c.messages.size() - 1), "INVALID_REQUEST");
    r.service.receive(c, "x".repeat(MAX_BYTES + 1));
    error(c.messages.get(c.messages.size() - 1), "INVALID_REQUEST");
    for (int i = 0; i < 32; i++) c.sendRequest(c.request("HELLO"));
    error(c.messages.get(c.messages.size() - 1), "RATE_LIMITED");
    assertFalse(PartyWire.accept(new byte[] {0, 1, 0, 0, 'X'}, x -> fail("unrelated reply")));
  }

  @Test public void authenticationPreservesLegacyDigestAndRequiresExactUniqueCachedMapping()
  {
    Map<String, String> properties = new HashMap<>();
    properties.put("miniclient/users", "alice,true;disabled,false;bob,true");
    for (String user : List.of("alice", "bob", "disabled"))
    {
      properties.put("miniclient/" + user + "/stv", "SageTV7.xml");
      properties.put("miniclient/" + user + "/pwd", "-legacy-md5-digest");
    }
    assertTrue(PartyAuthentication.verify(properties::get, "alice", "-legacy-md5-digest"));
    assertFalse(PartyAuthentication.verify(properties::get, "disabled", "-legacy-md5-digest"));
    assertFalse(PartyAuthentication.verify(properties::get, "alice", "wrong"));
    properties.put("miniclient/alice/cached_auth", "client-123");
    assertNull(PartyAuthentication.cachedPrincipal(properties::get, "client-12"));
    assertEquals(PartyAuthentication.cachedPrincipal(properties::get, "client-123"), "alice");
    properties.put("miniclient/bob/cached_auth", "client-123");
    assertNull(PartyAuthentication.cachedPrincipal(properties::get, "client-123"));
  }

  @Test public void lateJoinNeverReanchorsExistingPlaybackAndNativeControlsUseSameResolver()
  {
    Rig r = new Rig(); Client h = r.client(); h.create(); h.ready();
    h.action("PLAY"); r.now.addAndGet(4000); r.service.tick();
    JsonObject before = h.snapshot();
    Client late = r.client(); JsonObject joined = late.join(h);
    assertEquals(joined.getAsJsonObject("snapshot").get("commandSeq"), before.get("commandSeq"));
    JsonObject correction = late.ready();
    assertTrue(correction.get("targetPositionMs").getAsLong() >= 4000);
    JsonObject after = h.snapshot();
    assertEquals(before.get("anchorPositionMs"), after.get("anchorPositionMs"));
    assertEquals(before.get("anchorServerMonoMs"), after.get("anchorServerMonoMs"));
    assertTrue(r.service.nativeAction(late, "FF"));
    assertEquals(late.skips, 1); assertEquals(h.skips, 0);
    assertFalse(r.service.nativeAction(r.client(), "FF"));
  }

  @Test public void threeCorrectionFailuresDegradeOnlyTheOutlierAndRequireRejoin()
  {
    Rig r = new Rig(); Client h = r.client(); h.create(); h.ready();
    Client c = r.client(); c.join(h); c.ready();
    for (int i = 0; i < 3; i++)
    {
      JsonObject report = c.request("POSITION");
      report.addProperty("stateVersion", 0); report.addProperty("positionMs", 4000);
      report.addProperty("sampleMonoMs", r.now.get() - 100);
      report.addProperty("bufferAheadMs", 2000); report.addProperty("state", "PAUSED");
      c.sendRequest(report);
      JsonObject correction = c.messages.get(c.messages.size() - 2);
      c.landed(correction, false);
    }
    JsonObject roster = h.snapshot().getAsJsonArray("roster").get(1).getAsJsonObject();
    assertEquals(roster.get("status").getAsString(), "DEGRADED");
    assertEquals(h.snapshot().get("commandSeq").getAsLong(), 0);
    error(c.action("PLAY"), "NOT_READY");
    assertEquals(c.sendRequest(c.request("REJOIN")).get("type").getAsString(), "JOINED");
  }

  @Test public void tokenRotationExpirationInvitationsAndPreparationLeaseAreBounded()
  {
    Rig r = new Rig(); Client h = r.client(); h.create();
    Client c = r.client(); c.hello();
    JsonObject hello = c.sendRequest(c.request("HELLO"));
    JsonObject invite = h.request("INVITE");
    invite.addProperty("sessionHandle", hello.get("sessionHandle").getAsString());
    h.sendRequest(invite);
    assertEquals(c.messages.get(c.messages.size() - 1).get("type").getAsString(), "INVITED");
    String old = h.token;
    r.now.addAndGet(10000);
    JsonObject rotated = h.sendRequest(h.request("ROTATE_CODE"));
    assertNotEquals(rotated.get("joinCode").getAsString(), old);
    error(c.join(h), "JOIN_DENIED");
    h.token = rotated.get("joinCode").getAsString();
    assertEquals(c.join(h).get("type").getAsString(), "JOINED");
    r.now.addAndGet(60001); r.service.tick();
    error(h.snapshot(), "PARTY_GONE");
    assertFalse(h.owned); assertFalse(c.owned);
    assertEquals(h.commits, 0);
  }

  @Test public void preflightReservesPerViewerCapacityBeforeAnyPreparationAndReleasesOnLeave()
  {
    Rig r = new Rig(); Client h = r.client();
    h.path = new PartyService.Delivery("pwa", "pull-xcode", "H264", "AAC", 45000, true);
    h.create();
    Client c = r.client(); c.path = h.path;
    assertEquals(c.join(h).get("type").getAsString(), "JOINED");
    Client denied = r.client(); denied.path = h.path;
    error(denied.join(h), "RESOURCE_DENIED");
    assertEquals(h.snapshot().getAsJsonArray("roster").size(), 2);
    c.sendRequest(c.request("LEAVE"));
    assertEquals(denied.join(h).get("type").getAsString(), "JOINED");
  }

  @Test public void admittedLanSessionTokensExpireRefreshAndCannotCrossRendererBoundaries()
  {
    Rig r = new Rig(); Client h = r.client();
    h.principal = "server-admitted-lan"; h.hello();
    String old = h.sessionToken;
    Client another = r.client(); another.principal = "server-admitted-lan"; another.hello();
    another.sessionToken = h.sessionToken;
    error(another.sendRequest(another.request("LIST_CLIENTS")), "SESSION_DENIED");
    r.now.addAndGet(1800001);
    error(h.sendRequest(h.request("LIST_CLIENTS")), "SESSION_EXPIRED");
    h.hello();
    assertNotEquals(h.sessionToken, old);
    assertEquals(h.sendRequest(h.request("LIST_CLIENTS")).get("type").getAsString(), "CLIENTS");
    String renewed = h.sessionToken;
    h.sessionToken = old;
    error(h.sendRequest(h.request("LIST_CLIENTS")), "SESSION_DENIED");
    h.sessionToken = renewed;
    r.service.disconnected(h);
    error(h.sendRequest(h.request("LIST_CLIENTS")), "SESSION_EXPIRED");
  }

  @Test public void goldenFixturesMatchActualEnvelopesAndRejectStopWithoutStateMutation() throws Exception
  {
    JsonObject golden = golden();
    Rig r = new Rig(); r.now.set(98200);
    Client h = r.client(); h.create(); h.ready();
    JsonObject actual = h.action("FF");
    JsonObject expected = golden.getAsJsonObject("command");
    for (String field : List.of("schemaVersion", "type", "commandSeq", "stateVersion", "cause",
        "targetPositionMs", "targetState", "targetRate", "executeAtServerMonoMs"))
      assertEquals(actual.get(field), expected.get(field), field);
    JsonObject invalid = copy(golden.getAsJsonObject("invalidStop"));
    invalid.addProperty("serverEpoch", h.epoch); invalid.addProperty("sessionToken", h.sessionToken);
    invalid.addProperty("partyId", h.party);
    invalid.addProperty("requestSeq", ++h.sequence);
    assertEquals(h.sendRequest(invalid), golden.getAsJsonObject("invalidStopReply"));
    assertEquals(h.snapshot().get("commandSeq").getAsLong(), 1);
  }

  private static JsonObject golden() throws java.io.IOException
  {
    java.nio.file.Path file = java.nio.file.Path.of("..", "..", "docs", "party", "v1", "golden.json");
    return new sage.epg.sd.gson.JsonParser().parse(java.nio.file.Files.readString(file)).getAsJsonObject();
  }

  @Test public void evictionCannotReplayAnOldActionAndRecoveryTokenRotatesOnReattachment()
  {
    Rig r = new Rig(); Client h = r.client(); h.create(); h.ready();
    JsonObject action = h.request("ACTION");
    action.addProperty("stateVersion", 0); action.addProperty("action", "PLAY");
    h.sendRequest(action);
    for (int i = 0; i < 130; i++)
    {
      r.now.addAndGet(1000);
      h.snapshot();
    }
    error(h.sendRequest(action), "STALE_REQUEST");
    assertEquals(h.snapshot().get("commandSeq").getAsLong(), 1);
    h.token = h.sendRequest(h.request("ROTATE_CODE")).get("joinCode").getAsString();
    Client c = r.client(); c.join(h); String old = c.recovery;
    r.service.disconnected(c);
    Client replacement = r.client(); replacement.hello(); replacement.party = c.party;
    JsonObject resume = replacement.request("RESUME");
    resume.addProperty("participantId", c.participant); resume.addProperty("recoveryToken", old);
    replacement.remember(replacement.sendRequest(resume));
    assertNotEquals(replacement.recovery, old);
    r.service.disconnected(replacement);
    Client thief = r.client(); thief.hello(); thief.party = c.party;
    JsonObject replay = thief.request("RESUME");
    replay.addProperty("participantId", c.participant); replay.addProperty("recoveryToken", old);
    error(thief.sendRequest(replay), "REJOIN_DENIED");
  }

  @Test public void softCorrectionNeedsBothNegotiationAndPathSupportAndUsesMeasuredLead()
  {
    Rig r = new Rig(); Client h = r.client(); h.create(); h.ready();
    h.caps = new java.util.HashSet<>(REQUIRED); h.caps.add("PARTY_RATE_ADJUST_V1");
    h.sendRequest(h.request("REJOIN")); h.calibrate();
    JsonObject ready = h.request("READY");
    ready.addProperty("stateVersion", 0); ready.addProperty("prepared", true);
    ready.addProperty("activated", true); ready.addProperty("bufferAheadMs", 2000);
    ready.addProperty("precisionMs", 50); ready.addProperty("rateAdjust", true);
    h.landed(h.sendRequest(ready), true);
    JsonObject play = h.action("PLAY");
    r.now.set(play.get("executeAtServerMonoMs").getAsLong() + 1000); r.service.tick();
    JsonObject report = h.request("POSITION");
    report.addProperty("stateVersion", 1); report.addProperty("positionMs", 1400);
    report.addProperty("sampleMonoMs", r.now.get() - 100);
    report.addProperty("bufferAheadMs", 2000); report.addProperty("state", "PLAYING");
    h.sendRequest(report);
    JsonObject correction = h.messages.get(h.messages.size() - 2);
    assertEquals(correction.get("kind").getAsString(), "SOFT");
    assertEquals(correction.get("targetRate").getAsDouble(), .98);
    assertEquals(correction.get("durationMs").getAsInt(), 5000);
    assertEquals(correction.get("executeAtServerMonoMs").getAsLong(), r.now.get() + 750);
  }

  @Test public void fiveMixedSyntheticClientsReceiveOneCanonicalDeadlineWithAsymmetricLinkDelay()
  {
    Rig r = new Rig(); Client host = r.client(); host.create(); host.ready();
    List<Client> clients = new ArrayList<>(); clients.add(host);
    for (int i = 1; i < 5; i++)
    {
      Client c = r.client();
      c.path = new PartyService.Delivery(i % 2 == 0 ? "pwa" : "android",
          i % 2 == 0 ? "pull-xcode" : "push", "H264", "AAC", 8000, false);
      c.join(host); c.ready(); clients.add(c);
    }
    Client slow = clients.get(4);
    slow.calibrate(140, 100); // bounded asymmetry: RTT240, uncertainty <=150.
    for (Client c : clients)
    {
      JsonObject report = c.request("POSITION");
      report.addProperty("stateVersion", 0); report.addProperty("positionMs", 0);
      report.addProperty("sampleMonoMs", r.now.get() - 100);
      report.addProperty("bufferAheadMs", 4000); report.addProperty("state", "PAUSED");
      assertEquals(c.sendRequest(report).get("type").getAsString(), "ACK");
    }
    JsonObject command = host.action("PLAY");
    assertEquals(command.get("executeAtServerMonoMs").getAsLong(), r.now.get() + 970);
    for (Client c : clients)
    {
      JsonObject received = c.messages.stream()
          .filter(m -> "COMMAND".equals(m.get("type").getAsString())).findFirst().orElseThrow();
      for (String field : List.of("commandSeq", "stateVersion", "targetPositionMs",
          "targetState", "executeAtServerMonoMs"))
        assertEquals(received.get(field), command.get(field));
    }
    assertEquals(host.snapshot().getAsJsonArray("roster").size(), 5);
  }

  @Test public void defaultHostTransferUsesJoinOrderWithoutStoppingOrReanchoringPlayback()
  {
    Rig r = new Rig(); Client h = r.client(); h.create(); h.ready();
    Client first = r.client(); first.join(h); first.ready();
    Client second = r.client(); second.join(h); second.ready();
    JsonObject before = first.snapshot();
    r.service.disconnected(h);
    JsonObject after = first.snapshot();
    assertEquals(after.get("hostParticipantId").getAsString(), first.participant);
    assertEquals(after.get("stateVersion"), before.get("stateVersion"));
    assertEquals(after.get("commandSeq"), before.get("commandSeq"));
    assertEquals(after.get("anchorPositionMs"), before.get("anchorPositionMs"));
    assertEquals(first.action("PLAY").get("type").getAsString(), "COMMAND");
  }

  @Test public void majorityBufferingRecommendsWithinThreeSecondsWithoutChangingTimeline() throws Exception
  {
    Rig r = new Rig(); Client h = r.client(); h.create(); h.ready();
    Client a = r.client(); a.join(h); a.ready();
    Client b = r.client(); b.join(h); b.ready();
    JsonObject play = h.action("PLAY");
    r.now.set(play.get("executeAtServerMonoMs").getAsLong()); r.service.tick();
    a.status("BUFFERING");
    assertFalse(h.messages.stream().anyMatch(m -> "BUFFER_RECOMMENDATION".equals(text(m, "type"))));
    long began = r.now.get();
    b.status("BUFFERING");
    JsonObject recommendation = h.messages.stream()
        .filter(m -> "BUFFER_RECOMMENDATION".equals(text(m, "type"))).findFirst().orElseThrow();
    assertTrue(recommendation.get("serverMonoMs").getAsLong() - began <= 3000);
    assertEquals(recommendation.get("bufferingCount").getAsInt(), 2);
    assertEquals(recommendation.get("activeCount").getAsInt(), 3);
    assertEquals(text(recommendation, "policy"), "KEEP_MOVING");
    for (String field : List.of("schemaVersion", "type", "stateVersion", "commandSeq",
        "policy", "recommendation", "bufferingCount", "activeCount"))
      assertEquals(recommendation.get(field), golden().getAsJsonObject("bufferRecommendation").get(field), field);
    for (int i = 0; i < 10; i++) { a.status("BUFFERING"); r.service.tick(); }
    assertEquals(h.messages.stream().filter(m -> "BUFFER_RECOMMENDATION".equals(text(m, "type"))).count(), 1);
    JsonObject snapshot = h.snapshot();
    assertEquals(snapshot.get("commandSeq").getAsInt(), 1);
    assertEquals(text(snapshot, "state"), "PLAYING");
  }

  @Test public void renewedMajorityDuringCooldownIsNotLostAndIsReportedWithinThreeSeconds()
  {
    Rig r = new Rig(); Client h = r.client(); h.create(); h.ready();
    Client a = r.client(); a.join(h); a.ready();
    Client b = r.client(); b.join(h); b.ready();
    a.status("BUFFERING"); b.status("BUFFERING");
    a.status("RECOVERING"); // Break the strict majority without locally stopping anyone.
    a.ready();
    long began = r.now.get();
    a.status("BUFFERING");
    r.now.addAndGet(1000); r.service.tick();
    List<JsonObject> notices = h.messages.stream()
        .filter(m -> "BUFFER_RECOMMENDATION".equals(text(m, "type"))).toList();
    assertEquals(notices.size(), 2);
    assertTrue(notices.get(1).get("serverMonoMs").getAsLong() - began <= 3000);
    assertEquals(h.snapshot().get("commandSeq").getAsInt(), 0);
  }

  @Test public void configuredBufferPauseSupersedesPendingPlayAndIncludesBufferingViewer()
  {
    Rig r = new Rig(); Client h = r.client(); h.create(); h.ready();
    Client a = r.client(); a.join(h); a.ready();
    JsonObject policy = h.request("POLICY");
    policy.addProperty("hostOnly", true); policy.addProperty("pauseOnBuffer", true); h.sendRequest(policy);
    JsonObject play = h.action("PLAY");
    long began = r.now.get();
    a.status("BUFFERING");
    JsonObject snapshot = h.snapshot();
    JsonObject pause = snapshot.getAsJsonObject("pendingCommand");
    assertEquals(text(pause, "cause"), "PAUSE");
    assertEquals(text(pause, "targetState"), "PAUSED");
    assertEquals(pause.get("commandSeq").getAsInt(), 2);
    assertTrue(pause.get("executeAtServerMonoMs").getAsLong() - began <= 3000);
    assertTrue(a.messages.stream().anyMatch(m -> "COMMAND".equals(text(m, "type"))
        && "PAUSE".equals(text(m, "cause"))));
    assertTrue(h.messages.stream().anyMatch(m -> "COMMAND_OUTCOME".equals(text(m, "type"))
        && "SUPERSEDED".equals(text(m, "outcome")) && m.get("commandSeq").equals(play.get("commandSeq"))));
    r.now.set(pause.get("executeAtServerMonoMs").getAsLong()); r.service.tick();
    assertEquals(text(h.snapshot(), "state"), "PAUSED");
    a.status("BUFFERING");
    assertEquals(h.snapshot().get("commandSeq").getAsInt(), 2);
  }

  private static JsonObject ack(Client client, JsonObject command, String stage, long at,
      long position, String state, boolean success)
  {
    JsonObject ack = client.request("ACK");
    ack.add("stateVersion", command.get("stateVersion"));
    ack.add("commandSeq", command.get("commandSeq")); ack.addProperty("stage", stage);
    if ("EXECUTED".equals(stage))
    {
      ack.addProperty("sampleMonoMs", at - 100); ack.addProperty("positionMs", position);
      ack.addProperty("state", state); ack.addProperty("success", success);
    }
    return client.sendRequest(ack);
  }

  @Test public void commandOutcomesAggregateReceiptsLandingsAndSpreadAcrossReorderedVersions()
  {
    Rig r = new Rig(); Client h = r.client(); h.create(); h.ready();
    Client a = r.client(); a.join(h); a.ready();
    JsonObject play = h.action("PLAY");
    assertEquals(text(ack(h, play, "RECEIVED", 0, 0, "", false), "stage"), "RECEIVED");
    ack(h, play, "RECEIVED", 0, 0, "", false);
    error(ack(h, play, "EXECUTED", r.now.get(), 0, "PLAYING", true), "EXECUTION_PENDING");
    long deadline = play.get("executeAtServerMonoMs").getAsLong();
    r.now.set(deadline + 30);
    ack(h, play, "EXECUTED", r.now.get(), 30, "PLAYING", true);
    h.action("PAUSE"); // Execution ACK for version 1 must survive a version 2 command.
    r.now.set(deadline + 90);
    ack(a, play, "EXECUTED", r.now.get(), 800, "PAUSED", true);
    JsonObject outcome = h.messages.stream().filter(m -> "COMMAND_OUTCOME".equals(text(m, "type"))
        && "COMPLETE".equals(text(m, "outcome"))).findFirst().orElseThrow();
    assertEquals(outcome.get("expectedCount").getAsInt(), 2);
    assertEquals(outcome.get("receivedCount").getAsInt(), 2);
    assertEquals(outcome.get("executedCount").getAsInt(), 2);
    assertEquals(outcome.get("successCount").getAsInt(), 1);
    assertEquals(outcome.get("failedCount").getAsInt(), 1);
    assertEquals(outcome.get("missingCount").getAsInt(), 0);
    assertEquals(outcome.get("executionSpreadMs").getAsLong(), 60);
    assertEquals(outcome.get("spreadUncertaintyMs").getAsDouble(), 10);
    error(ack(a, play, "EXECUTED", r.now.get(), 800, "PAUSED", true), "OUTCOME_CLOSED");
  }

  @Test public void commandOutcomesTimeoutAndLateJoinersCannotAcknowledgeOldCommands()
  {
    Rig r = new Rig(); Client h = r.client(); h.create(); h.ready();
    JsonObject command = h.action("FF");
    Client late = r.client(); late.join(h); late.ready();
    error(ack(late, command, "RECEIVED", 0, 0, "", false), "NOT_COMMAND_RECIPIENT");
    r.now.set(command.get("executeAtServerMonoMs").getAsLong() + 10000); r.service.tick();
    JsonObject outcome = h.snapshot().getAsJsonObject("latestCommandOutcome");
    assertEquals(text(outcome, "outcome"), "TIMED_OUT");
    assertEquals(outcome.get("missingCount").getAsInt(), 1);
    assertFalse(outcome.has("executionSpreadMs"));
    error(ack(h, command, "RECEIVED", 0, 0, "", false), "OUTCOME_CLOSED");
    for (int i = 0; i < 17; i++)
    {
      h.status("BUFFERING"); h.ready();
      JsonObject next = h.action("FF");
      r.now.set(next.get("executeAtServerMonoMs").getAsLong()); r.service.tick();
    }
    error(ack(h, command, "RECEIVED", 0, 0, "", false), "COMMAND_GONE");
  }

  @Test public void downstreamAdmissionFailureGatesOnlyAffectedViewerDespitePreparationClaims()
  {
    Rig r = new Rig(); Client h = r.client(); h.create(); h.ready();
    Client denied = r.client(); denied.join(h); denied.mediaAdmitted = false;
    error(denied.ready(), "MEDIA_ADMISSION_UNCONFIRMED");
    assertEquals(text(h.snapshot().getAsJsonArray("roster").get(1).getAsJsonObject(), "status"), "PREPARING");
    assertEquals(text(h.action("PLAY"), "type"), "COMMAND");
    denied.mediaAdmitted = true; denied.ready();
    denied.mediaAdmitted = false; r.service.tick();
    assertEquals(text(h.snapshot().getAsJsonArray("roster").get(1).getAsJsonObject(), "status"), "RECOVERING");
    assertEquals(h.snapshot().get("commandSeq").getAsInt(), 1);
    assertEquals(h.snapshot().get("stateVersion").getAsInt(), 1);
  }

  @Test public void actualAggregatedExecutionOutcomeMatchesGoldenSchemaAndValues() throws Exception
  {
    Rig r = new Rig(); r.now.set(96400);
    Client h = r.client(); h.create(); h.ready();
    Client a = r.client(); a.join(h); a.ready();
    JsonObject command = h.action("FF");
    ack(h, command, "RECEIVED", 0, 0, "", false);
    r.now.set(100780); ack(h, command, "EXECUTED", r.now.get(), 10000, "PAUSED", true);
    r.now.set(100840); ack(a, command, "EXECUTED", r.now.get(), 10800, "PAUSED", true);
    JsonObject actual = h.messages.stream().filter(m -> "COMMAND_OUTCOME".equals(text(m, "type"))
        && "COMPLETE".equals(text(m, "outcome"))).findFirst().orElseThrow();
    actual = copy(actual);
    actual.addProperty("partyId", "example-party");
    actual.getAsJsonArray("participants").get(0).getAsJsonObject().addProperty("participantId", "example-host");
    actual.getAsJsonArray("participants").get(1).getAsJsonObject().addProperty("participantId", "example-guest");
    assertEquals(actual, golden().getAsJsonObject("commandOutcome"));
    assertTrue(h.snapshot().toString().getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES);
  }

  private static void capabilities(Client client, String... optional)
  {
    client.caps = new java.util.HashSet<>(REQUIRED);
    client.caps.addAll(List.of(optional));
  }

  @Test public void currentStartIsServerSampledClampedPausedAndNeverAnArbitraryClientSeek()
  {
    Rig r = new Rig(); Client h = r.client(); capabilities(h, "PARTY_START_POSITION_V1");
    h.currentPosition = 123456; h.hello();
    JsonObject create = h.request("CREATE"); create.addProperty("mediaFileId", 42);
    create.addProperty("startMode", "CURRENT_POSITION");
    JsonObject created = h.sendRequest(create); h.remember(created);
    h.token = created.get("joinCode").getAsString();
    assertEquals(created.get("startPositionMs").getAsLong(), 123456);
    assertEquals(text(created, "startMode"), "CURRENT_POSITION");
    JsonObject snapshot = created.getAsJsonObject("snapshot");
    assertEquals(text(snapshot, "state"), "PAUSED");
    assertEquals(snapshot.get("anchorPositionMs").getAsLong(), 123456);
    assertEquals(snapshot.get("stateVersion").getAsInt(), 0);
    assertEquals(snapshot.get("commandSeq").getAsInt(), 0);
    assertEquals(h.sendRequest(copy(create)), created);
    assertEquals(h.currentPositionReads, 1, "Exact retry cannot resample a later playback position");
    r.now.addAndGet(1000);
    assertEquals(h.ready().get("targetPositionMs").getAsLong(), 123456);
    Client guest = r.client(); guest.join(h);
    assertEquals(guest.ready().get("targetPositionMs").getAsLong(), 123456);
    assertEquals(guest.currentPositionReads, 0);
    for (long position : new long[] {-1, Long.MIN_VALUE, Long.MAX_VALUE})
    {
      Rig clamped = new Rig(); Client c = clamped.client(); capabilities(c, "PARTY_START_POSITION_V1");
      c.currentPosition = position; c.hello();
      JsonObject request = c.request("CREATE"); request.addProperty("mediaFileId", 42);
      request.addProperty("startMode", "CURRENT_POSITION");
      assertEquals(c.sendRequest(request).get("startPositionMs").getAsLong(), position < 0 ? 0 : 3600000);
    }
  }

  @Test public void growingMediaRequiresExplicitCapabilityForCreateAndJoin()
    {
      Rig r = new Rig(); Client unsupported = r.client(); unsupported.growing = true;
      unsupported.availableEnd = 60000; unsupported.hello();
      JsonObject create = unsupported.request("CREATE"); create.addProperty("mediaFileId", 42);
      error(unsupported.sendRequest(create), "GROWING_MEDIA_UNSUPPORTED");

      Client host = r.client(); host.growing = true; host.availableEnd = 60000;
      capabilities(host, GROWING_MEDIA); host.create();
      Client guest = r.client(); guest.growing = true; guest.availableEnd = 60500;
      error(guest.join(host), "GROWING_MEDIA_UNSUPPORTED");
      capabilities(guest, GROWING_MEDIA);
      assertEquals(text(guest.join(host), "type"), "JOINED");
    }

  @Test public void growingMediaPublishesDelayedPlayableEdgeAndRetainsReadinessBufferFloor()
    {
      Rig r = new Rig(); Client host = r.client(); host.growing = true; host.availableEnd = 60000;
      host.currentPosition = 59500; capabilities(host, GROWING_MEDIA, "PARTY_START_POSITION_V1");
      host.hello();
      JsonObject create = host.request("CREATE"); create.addProperty("mediaFileId", 42);
      create.addProperty("startMode", "CURRENT_POSITION");
      JsonObject created = host.sendRequest(create); host.remember(created);
      host.token = created.get("joinCode").getAsString();
      JsonObject snapshot = created.getAsJsonObject("snapshot");
      assertTrue(snapshot.get("mediaGrowing").getAsBoolean());
      assertEquals(snapshot.get("availableEndMs").getAsLong(), 60000);
      assertEquals(snapshot.get("playableEndMs").getAsLong(), 57000);
      assertEquals(snapshot.get("liveEdgeDelayMs").getAsLong(), 3000);
      assertEquals(created.get("startPositionMs").getAsLong(), 57000);

      host.calibrate();
      JsonObject ready = host.request("READY");
      ready.addProperty("stateVersion", host.snapshot().get("stateVersion").getAsLong());
      ready.addProperty("prepared", true); ready.addProperty("activated", true);
      ready.addProperty("bufferAheadMs", 1499); ready.addProperty("precisionMs", 50);
      ready.addProperty("rateAdjust", false);
      ready.addProperty("requestId", "r" + ++host.sequence); ready.addProperty("requestSeq", host.sequence);
      error(host.sendRequest(ready), "NOT_READY");
    }

  @Test public void growingMediaWindowAdvancesAndRecordingCompletionBecomesFixed()
    {
      Rig r = new Rig(); Client host = r.client(); host.growing = true; host.availableEnd = 60000;
      capabilities(host, GROWING_MEDIA); host.create();
      host.availableEnd = 65000;
      JsonObject growing = host.snapshot();
      assertEquals(growing.get("durationMs").getAsLong(), 65000);
      assertEquals(growing.get("playableEndMs").getAsLong(), 62000);
      host.growing = false; host.availableEnd = 65250;
      r.service.tick();
      JsonObject completed = host.snapshot();
      assertFalse(completed.get("mediaGrowing").getAsBoolean());
      assertEquals(completed.get("durationMs").getAsLong(), 65250);
      assertEquals(completed.get("playableEndMs").getAsLong(), 65250);
      assertEquals(completed.get("liveEdgeDelayMs").getAsLong(), 0);
    }

  @Test public void omittedBeginningRemainsCompatibleAndCurrentModeRequiresExplicitGuarantee()
  {
    Rig r = new Rig(); Client h = r.client(); JsonObject created = h.create();
    assertEquals(created.get("startPositionMs").getAsLong(), 0);
    assertEquals(text(created, "startMode"), "BEGINNING");
    assertEquals(h.currentPositionReads, 0);
    Client c = r.client(); c.hello();
    JsonObject create = c.request("CREATE"); create.addProperty("mediaFileId", 42);
    create.addProperty("startMode", "CURRENT_POSITION");
    error(c.sendRequest(create), "START_MODE_UNSUPPORTED");
    create = c.request("CREATE"); create.addProperty("mediaFileId", 42);
    create.addProperty("startMode", "BEGINNING"); create.addProperty("startPositionMs", 9000);
    error(c.sendRequest(create), "INVALID_REQUEST");
    assertFalse(c.owned);
    create = c.request("CREATE"); create.addProperty("mediaFileId", 42);
    create.addProperty("startMode", "SEEK");
    error(c.sendRequest(create), "INVALID_REQUEST");
    assertEquals(c.currentPositionReads, 0);
    capabilities(c, "PARTY_START_POSITION_V1", "PARTY_PREPARE_V1");
    JsonObject hello = c.sendRequest(c.request("HELLO"));
    assertTrue(hello.get("prepareSupported").getAsBoolean());
    assertEquals(hello.getAsJsonArray("supportedStartModes").get(1).getAsString(), "CURRENT_POSITION");
  }

  @Test public void explicitPreparationIsCanonicalOnlyIdempotentAndWaitsForAsyncResult()
  {
    Rig r = new Rig(); Client h = r.client(); h.create();
    Client guest = r.client(); capabilities(guest, "PARTY_PREPARE_V1"); guest.join(h);
    assertEquals(guest.prepares, 0, "Invitation/JOIN must never force an open");
    JsonObject prepare = guest.request("PREPARE");
    prepare.addProperty("intent", "JOIN_AND_PREPARE"); prepare.addProperty("confirmReplace", true);
    JsonObject accepted = guest.sendRequest(prepare);
    assertEquals(text(accepted, "type"), "PREPARE_ACCEPTED");
    assertTrue(accepted.get("holdPlayback").getAsBoolean());
    assertEquals(accepted.get("mediaFileId").getAsInt(), 42);
    assertEquals(guest.sendRequest(copy(prepare)), accepted);
    assertEquals(guest.prepares, 1);
    error(guest.sendRequest(guest.request("READY")), "PREPARATION_PENDING");
    error(guest.sendRequest(guest.request("MEDIA_ADMISSION")), "PREPARATION_PENDING");
    int before = guest.messages.size();
    r.service.preparationResult(guest, "wrong-operation", 0, 7);
    assertEquals(guest.messages.size(), before);
    r.service.preparationResult(guest, guest.preparationId, 0, 7);
    JsonObject loaded = guest.messages.get(guest.messages.size() - 1);
    assertEquals(text(loaded, "status"), "LOADED");
    assertEquals(loaded.get("playbackGeneration").getAsLong(), 7);
    r.service.preparationResult(guest, guest.preparationId, 0, 8);
    assertEquals(guest.messages.get(guest.messages.size() - 1), loaded);
    error(guest.action("PLAY"), "NOT_READY");
    guest.ready();
    assertEquals(guest.prepares, 1);
    assertEquals(h.snapshot().get("commandSeq").getAsInt(), 0);
  }

  @Test public void prepareRequiresConfirmationAndNegotiationAndFailureNeverMutatesTimeline()
  {
    Rig r = new Rig(); Client h = r.client(); h.create();
    JsonObject p = h.request("PREPARE"); p.addProperty("intent", "JOIN_AND_PREPARE"); p.addProperty("confirmReplace", true);
    error(h.sendRequest(p), "PREPARE_UNSUPPORTED");
    capabilities(h, "PARTY_PREPARE_V1");
    p = h.request("PREPARE"); p.addProperty("intent", "JOIN_AND_PREPARE"); p.addProperty("confirmReplace", false);
    error(h.sendRequest(p), "INVALID_REQUEST");
    p = h.request("PREPARE"); p.addProperty("intent", "JOIN_AND_PREPARE"); p.addProperty("confirmReplace", true);
    p.addProperty("mediaFileId", 99); error(h.sendRequest(p), "INVALID_REQUEST");
    p = h.request("PREPARE"); p.addProperty("intent", "JOIN_AND_PREPARE"); p.addProperty("confirmReplace", true);
    h.sendRequest(p); String old = h.preparationId;
    r.service.preparationResult(h, old, -3, -1);
    JsonObject failure = h.messages.get(h.messages.size() - 1);
    assertEquals(text(failure, "status"), "FAILED");
    assertEquals(failure.get("watchCode").getAsInt(), -3);
    assertFalse(failure.has("playbackGeneration"));
    p = h.request("PREPARE"); p.addProperty("intent", "JOIN_AND_PREPARE"); p.addProperty("confirmReplace", true);
    h.sendRequest(p); String next = h.preparationId;
    assertNotEquals(next, old);
    h.status("LOCALLY_STOPPED"); int before = h.messages.size();
    r.service.preparationResult(h, next, 0, 1);
    assertEquals(h.messages.size(), before);
    assertEquals(h.snapshot().get("stateVersion").getAsInt(), 0);
    assertEquals(h.snapshot().get("commandSeq").getAsInt(), 0);
  }

  @Test public void invitedSessionsCannotBypassMandatoryCodeAndManualJoinCreatesNoNewSession()
  {
    Rig r = new Rig(); Client h = r.client(); JsonObject created = h.create();
    assertFalse(created.has("joinToken")); assertTrue(h.token.matches("[0-9]{3}"));
    Client guest = r.client(); guest.hello();
    JsonObject available = guest.sendRequest(guest.request("HELLO"));
    JsonObject invite = h.request("INVITE"); invite.addProperty("sessionHandle", available.get("sessionHandle").getAsString());
    h.sendRequest(invite);
    JsonObject invited = guest.messages.get(guest.messages.size() - 1);
    assertTrue(invited.get("joinCodeRequired").getAsBoolean());
    assertFalse(invited.has("joinToken")); assertFalse(invited.has("joinCode"));
    guest.party = h.party;
    JsonObject bypass = guest.request("JOIN"); bypass.addProperty("joinToken", h.token);
    error(guest.sendRequest(bypass), "JOIN_DENIED");
    JsonObject manual = guest.request("JOIN"); manual.remove("partyId"); manual.addProperty("joinCode", h.token);
    JsonObject joined = guest.sendRequest(manual);
    assertEquals(text(joined, "type"), "JOINED");
    assertFalse(joined.has("joinCode")); assertFalse(joined.has("joinToken"));
    guest.remember(joined);
    error(guest.sendRequest(guest.request("JOIN_CODE")), "HOST_REQUIRED");
    error(guest.sendRequest(guest.request("ROTATE_CODE")), "HOST_REQUIRED");
    JsonObject rotation = h.sendRequest(h.request("ROTATE_CODE"));
    error(rotation, "CODE_ROTATION_COOLDOWN");
    assertEquals(rotation.get("retryAfterMs").getAsLong(), 10000);
    assertEquals(h.sendRequest(h.request("JOIN_CODE")).get("joinCode").getAsString(), h.token);
    assertEquals(h.snapshot().getAsJsonArray("roster").size(), 2);
  }

  @Test public void guessingLimitsIncludeSuccessfulAttemptsAndCannotBeResetByReconnectOrCachedRetries()
  {
    Rig r = new Rig(); Client h = r.client(); h.create(); h.ready();
    Client c = r.client(); c.hello();
    String wrongCode = "000".equals(h.token) ? "001" : "000";
    JsonObject wrong = c.request("JOIN"); wrong.addProperty("joinCode", wrongCode);
    error(c.sendRequest(wrong), "JOIN_DENIED");
    for (int i = 0; i < 8; i++) error(c.sendRequest(copy(wrong)), "JOIN_DENIED");
    for (int i = 0; i < 2; i++)
    {
      JsonObject request = c.request("JOIN"); request.addProperty("joinCode", wrongCode);
      error(c.sendRequest(request), "JOIN_DENIED");
    }
    JsonObject blocked = c.request("JOIN"); blocked.addProperty("joinCode", h.token);
    JsonObject denied = c.sendRequest(blocked); error(denied, "JOIN_RATE_LIMITED");
    assertEquals(denied.get("retryAfterMs").getAsLong(), 60000);
    r.service.disconnected(c);
    for (int i = 0; i < 3; i++)
    {
      Client replacement = r.client(); replacement.hello();
      JsonObject request = replacement.request("JOIN"); request.addProperty("joinCode", wrongCode);
      error(replacement.sendRequest(request), "JOIN_DENIED");
      r.service.disconnected(replacement);
    }
    Client fresh = r.client(); fresh.hello();
    JsonObject request = fresh.request("JOIN"); request.addProperty("joinCode", h.token);
    denied = fresh.sendRequest(request); error(denied, "JOIN_RATE_LIMITED");
    assertEquals(denied.get("retryAfterMs").getAsLong(), 60000);
    r.now.addAndGet(60000);
    request = fresh.request("JOIN"); request.addProperty("joinCode", h.token);
    assertEquals(text(fresh.sendRequest(request), "type"), "JOINED");
  }

  @Test public void shortCodeExpiresWithoutBypassingRecoveryOrLegacyGates()
  {
    Rig r = new Rig(); Client h = r.client(); h.create(); h.ready();
    Client guest = r.client(); guest.join(h); r.service.disconnected(guest);
    Client replacement = r.client(); replacement.hello(); replacement.party = guest.party;
    JsonObject recover = replacement.request("RESUME");
    recover.addProperty("participantId", guest.participant); recover.addProperty("recoveryToken", guest.recovery);
    assertEquals(text(replacement.sendRequest(recover), "type"), "JOINED");
    r.now.addAndGet(120000);
    Client late = r.client(); error(late.join(h), "JOIN_DENIED");
    error(h.sendRequest(h.request("JOIN_CODE")), "JOIN_CODE_EXPIRED");
    Client oldProtocol = r.client(); capabilities(oldProtocol);
    oldProtocol.caps.remove("PARTY_JOIN_CODE_V1");
    error(oldProtocol.sendRequest(oldProtocol.request("HELLO")), "NOT_AVAILABLE");
    assertFalse(h.snapshot().has("joinCode"));
  }

  @Test public void startAndPrepareGoldensMatchActualServiceRepliesAndEvents() throws Exception
  {
    JsonObject fixtures = golden();
    Rig r = new Rig(); Client h = r.client();
    capabilities(h, "PARTY_START_POSITION_V1", "PARTY_PREPARE_V1"); h.currentPosition = 123456; h.hello();
    JsonObject create = copy(fixtures.getAsJsonObject("createCurrent"));
    create.addProperty("serverEpoch", h.epoch); create.addProperty("sessionToken", h.sessionToken); h.sequence = 2;
    JsonObject created = copy(h.sendRequest(create)); h.remember(created);
    created.addProperty("partyId", "example-party"); created.addProperty("participantId", "example-host");
    created.addProperty("recoveryToken", "EXAMPLE_NOT_A_REAL_RECOVERY_TOKEN"); created.addProperty("joinCode", "042");
    JsonObject snapshot = created.getAsJsonObject("snapshot");
    snapshot.addProperty("partyId", "example-party"); snapshot.addProperty("serverEpoch", "example-epoch");
    snapshot.addProperty("hostParticipantId", "example-host");
    snapshot.getAsJsonArray("roster").get(0).getAsJsonObject().addProperty("participantId", "example-host");
    assertEquals(created, fixtures.getAsJsonObject("createdCurrent"));
    JsonObject prepare = copy(fixtures.getAsJsonObject("prepare"));
    prepare.addProperty("serverEpoch", h.epoch); prepare.addProperty("sessionToken", h.sessionToken);
    prepare.addProperty("partyId", h.party); h.sequence = 3;
    JsonObject accepted = copy(h.sendRequest(prepare));
    accepted.addProperty("partyId", "example-party"); accepted.addProperty("preparationId", "example-preparation");
    assertEquals(accepted, fixtures.getAsJsonObject("prepareAccepted"));
    r.service.preparationResult(h, h.preparationId, 0, 7);
    JsonObject loaded = copy(h.messages.get(h.messages.size() - 1));
    loaded.addProperty("partyId", "example-party"); loaded.addProperty("preparationId", "example-preparation");
    assertEquals(loaded, fixtures.getAsJsonObject("preparationLoaded"));
    prepare.addProperty("requestId", "prepare2"); prepare.addProperty("requestSeq", 4); h.sequence = 4;
    h.sendRequest(prepare); r.service.preparationResult(h, h.preparationId, -3, -1);
    JsonObject failed = copy(h.messages.get(h.messages.size() - 1));
    failed.addProperty("partyId", "example-party"); failed.addProperty("preparationId", "example-preparation");
    assertEquals(failed, fixtures.getAsJsonObject("preparationFailed"));
  }

  @Test public void codeIssuanceCannotExhaustNamespaceThroughNewRendererChurn()
  {
    Rig r = new Rig();
    for (int i = 0; i < 12; i++)
    {
      Client creator = r.client(); creator.create(); creator.sendRequest(creator.request("LEAVE"));
      r.service.disconnected(creator);
    }
    Client fresh = r.client(); fresh.hello();
    JsonObject create = fresh.request("CREATE"); create.addProperty("mediaFileId", 42);
    JsonObject rejected = fresh.sendRequest(create);
    error(rejected, "CODE_ISSUE_RATE_LIMITED"); assertEquals(rejected.get("retryAfterMs").getAsLong(), 60000);
    assertFalse(fresh.owned);
    r.now.addAndGet(60000);
    create = fresh.request("CREATE"); create.addProperty("mediaFileId", 42);
    assertEquals(text(fresh.sendRequest(create), "type"), "CREATED");
  }

  @Test public void hostSidecarCodeRetrievalAfterResumeDoesNotRotateOrMutateTimeline()
  {
    Rig r = new Rig(); Client host = r.client(); host.create(); host.ready();
    r.service.disconnected(host);
    Client replacement = r.client(); replacement.hello(); replacement.party = host.party;
    JsonObject resume = replacement.request("RESUME");
    resume.addProperty("participantId", host.participant); resume.addProperty("recoveryToken", host.recovery);
    replacement.remember(replacement.sendRequest(resume));
    JsonObject before = replacement.snapshot();
    JsonObject code = replacement.sendRequest(replacement.request("JOIN_CODE"));
    JsonObject reopened = replacement.sendRequest(replacement.request("JOIN_CODE"));
    assertEquals(code.get("joinCode").getAsString(), host.token);
    assertEquals(code.get("expiresAtServerMonoMs"), reopened.get("expiresAtServerMonoMs"));
    JsonObject after = replacement.snapshot();
    for (String field : List.of("stateVersion", "commandSeq", "anchorPositionMs", "anchorServerMonoMs", "state"))
      assertEquals(after.get(field), before.get(field));
    assertFalse(after.has("joinCode"));
  }

  @Test public void pendingPrepareLeaseCannotBeBypassedByBufferingAndDisconnectRetainsRecoveryGrace()
  {
    Rig r = new Rig(); Client h = r.client(); h.create(); h.ready();
    Client guest = r.client(); capabilities(guest, "PARTY_PREPARE_V1"); guest.join(h);
    JsonObject prepare = guest.request("PREPARE");
    prepare.addProperty("intent", "JOIN_AND_PREPARE"); prepare.addProperty("confirmReplace", true);
    guest.sendRequest(prepare); String old = guest.preparationId;
    guest.status("BUFFERING"); r.now.addAndGet(60001); r.service.tick();
    assertTrue(guest.messages.stream().anyMatch(m -> m.has("code") && "PREPARATION_EXPIRED".equals(text(m, "code"))));
    int before = guest.messages.size(); r.service.preparationResult(guest, old, 0, 7);
    assertEquals(guest.messages.size(), before);
    Client reconnecting = r.client(); capabilities(reconnecting, "PARTY_PREPARE_V1"); reconnecting.join(h);
    prepare = reconnecting.request("PREPARE");
    prepare.addProperty("intent", "JOIN_AND_PREPARE"); prepare.addProperty("confirmReplace", true);
    reconnecting.sendRequest(prepare);
    r.now.addAndGet(59000); r.service.disconnected(reconnecting);
    r.now.addAndGet(2000); r.service.tick();
    Client replacement = r.client(); replacement.hello(); replacement.party = reconnecting.party;
    JsonObject resume = replacement.request("RESUME");
    resume.addProperty("participantId", reconnecting.participant); resume.addProperty("recoveryToken", reconnecting.recovery);
    assertEquals(text(replacement.sendRequest(resume), "type"), "JOINED");
  }

  @Test public void negotiatedSurfaceSelectionRequiresPreparationEchoAndDoesNotAffectOtherViewers() throws Exception
  {
    Rig r = new Rig(); Client host = r.client(); host.create();
    Client viewer = r.client(); capabilities(viewer, "PARTY_SURFACE_V1", "PARTY_PREPARE_V1"); viewer.join(host);
    viewer.path = new PartyService.Delivery("pwa_party_mse", "pull-xcode", "H264", "AAC", 8000, true,
        "mse", "browserhd_remux", "MP4");
    JsonObject select = viewer.request("SELECT_SURFACE"); select.addProperty("surfaceId", "pwa_mse");
    JsonObject accepted = viewer.sendRequest(select);
    assertEquals(text(accepted, "type"), "SURFACE_SELECTION_ACCEPTED");
    assertEquals(viewer.sendRequest(copy(select)), accepted); assertEquals(viewer.selections, 1);
    error(viewer.sendRequest(viewer.request("MEDIA_ADMISSION")), "SURFACE_SELECTION_PENDING");
    r.service.surfaceSelectionResult(viewer, viewer.selectionId, viewer.path, null);
    JsonObject selected = viewer.messages.get(viewer.messages.size() - 1);
    JsonObject normalized = copy(selected);
    normalized.addProperty("partyId", "example-party"); normalized.addProperty("selectionId", "example-selection");
    assertEquals(normalized, golden().getAsJsonObject("surfaceSelected"));
    assertEquals(text(selected, "status"), "SELECTED"); assertTrue(selected.get("requiresPrepare").getAsBoolean());
    error(viewer.sendRequest(viewer.request("READY")), "SURFACE_PREPARATION_REQUIRED");
    JsonObject prepare = viewer.request("PREPARE");
    prepare.addProperty("intent", "JOIN_AND_PREPARE"); prepare.addProperty("confirmReplace", true);
    error(viewer.sendRequest(prepare), "SURFACE_CHANGED");
    prepare = viewer.request("PREPARE"); prepare.addProperty("intent", "JOIN_AND_PREPARE"); prepare.addProperty("confirmReplace", true);
    prepare.addProperty("selectionId", viewer.selectionId);
    JsonObject prepared = viewer.sendRequest(prepare);
    assertEquals(prepared.get("selectionId"), accepted.get("selectionId"));
    r.service.preparationResult(viewer, viewer.preparationId, 0, 7);
    normalized = copy(viewer.messages.get(viewer.messages.size() - 1));
    normalized.addProperty("partyId", "example-party"); normalized.addProperty("selectionId", "example-selection");
    normalized.addProperty("preparationId", "example-preparation");
    assertEquals(normalized, golden().getAsJsonObject("selectedPreparationLoaded"));
    assertEquals(text(viewer.ready(), "type"), "CORRECTION");
    JsonObject restore = viewer.sendRequest(viewer.request("RESTORE_SURFACE"));
    assertEquals(text(restore, "type"), "SURFACE_RESTORED");
    assertEquals(host.selections, 0); assertEquals(host.restores, 0);
    assertEquals(host.snapshot().get("commandSeq").getAsInt(), 0);
  }

  @Test public void everyPartyExitRestoresSelectedConfigurationWithoutTimelineMutation()
  {
    for (String exit : java.util.List.of("LEAVE", "END", "STOP", "REJOIN", "DISCONNECT", "SHUTDOWN"))
    {
      Rig r = new Rig(); Client host = r.client(); capabilities(host, "PARTY_SURFACE_V1", "PARTY_PREPARE_V1"); host.create();
      JsonObject select = host.request("SELECT_SURFACE"); select.addProperty("surfaceId", "pwa_party_mse");
      host.sendRequest(select); r.service.surfaceSelectionResult(host, host.selectionId, host.path, null);
      assertNotNull(host.selectedSelectionId);
      int restores = host.restores;
      if ("STOP".equals(exit)) host.status("LOCALLY_STOPPED");
      else if ("DISCONNECT".equals(exit)) r.service.disconnected(host);
      else if ("SHUTDOWN".equals(exit)) r.service.shutdown();
      else host.sendRequest(host.request(exit));
      assertNull(host.selectedSelectionId, exit);
      assertTrue(host.restores > restores, exit);
      assertFalse(host.messages.stream().anyMatch(m -> "COMMAND".equals(text(m, "type"))), exit);
    }
  }

  @Test public void surfaceQueryFailureBudgetAndCancelledCallbacksCannotInstallPolicy()
  {
    Rig r = new Rig(); Client host = r.client(); host.create();
    JsonObject select = host.request("SELECT_SURFACE"); select.addProperty("surfaceId", "pwa_mse");
    error(host.sendRequest(select), "SURFACE_UNSUPPORTED");
    capabilities(host, "PARTY_SURFACE_V1", "PARTY_PREPARE_V1");
    select = host.request("SELECT_SURFACE"); select.addProperty("surfaceId", "pwa_mse"); host.sendRequest(select);
    r.service.surfaceSelectionResult(host, host.selectionId, null, "SURFACE_QUERY_FAILED");
    assertEquals(text(host.messages.get(host.messages.size() - 1), "code"), "SURFACE_QUERY_FAILED");
    select = host.request("SELECT_SURFACE"); select.addProperty("surfaceId", "pwa_mse"); host.sendRequest(select);
    String cancelled = host.selectionId;
    host.sendRequest(host.request("RESTORE_SURFACE"));
    int before = host.messages.size();
    r.service.surfaceSelectionResult(host, cancelled, host.path, null);
    assertEquals(host.messages.size(), before);
    select = host.request("SELECT_SURFACE"); select.addProperty("surfaceId", "pwa_mse"); host.sendRequest(select);
    r.service.surfaceSelectionResult(host, host.selectionId,
        new PartyService.Delivery("pwa_mse", "pull-xcode", "H264", "AAC", 100001, true), null);
    assertEquals(text(host.messages.get(host.messages.size() - 1), "code"), "RESOURCE_DENIED");
    select = host.request("SELECT_SURFACE"); select.addProperty("surfaceId", "pwa_mse"); host.sendRequest(select);
    String pending = host.selectionId;
    host.status("LOCALLY_STOPPED"); before = host.messages.size();
    r.service.surfaceSelectionResult(host, pending, host.path, null);
    assertEquals(host.messages.size(), before);
  }
}
