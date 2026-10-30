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
      token = out.get("joinToken").getAsString();
      return out;
    }

    JsonObject join(Client host)
    {
      hello(); party = host.party;
      JsonObject r = request("JOIN");
      r.addProperty("mediaFileId", 42); r.addProperty("joinToken", host.token);
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
    { if (id != 42) throw new Fault("MEDIA_UNAVAILABLE"); return 3600000; }
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
    bad.addProperty("joinToken", host.token); bad.addProperty("mediaFileId", 99);
    error(stranger.sendRequest(bad), "MEDIA_MISMATCH");
    JsonObject invalid = stranger.request("JOIN");
    invalid.addProperty("joinToken", "wrong"); invalid.addProperty("mediaFileId", 42);
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
    JsonObject rotated = h.sendRequest(h.request("ROTATE_TOKEN"));
    assertNotEquals(rotated.get("joinToken").getAsString(), old);
    error(c.join(h), "JOIN_DENIED");
    h.token = rotated.get("joinToken").getAsString();
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
}
