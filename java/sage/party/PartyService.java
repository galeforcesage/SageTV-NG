package sage.party;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import sage.epg.sd.gson.JsonArray;
import sage.epg.sd.gson.JsonObject;
import static sage.party.PartyProtocol.*;

/**
 * Ephemeral, server-authoritative Party Mode service.
 * All entry points are serialized; transport adapters must queue output per viewer.
 */
public final class PartyService
{
  /** A server-authorized renderer, not a physical socket or advertised device identity. */
  public interface Peer
  {
    /** Returns the server-verified account or explicitly trusted LAN admission realm, else null. */
    String principal();
    /** Returns explicitly negotiated NG guarantees for this renderer. */
    Set<String> capabilities();
    /** Queues one bounded message to this viewer only, never blocking other viewers. */
    void send(JsonObject message);
    /** Validates eligible accessible media and returns its stable duration. */
    long duration(int mediaFileId);
    /** Evaluates this viewer's normal NG surface plan before preparation consumes resources. */
    Delivery plan(int mediaFileId);
    /** Returns actual server-selected playback metadata, or throws until prepared. */
    Delivery delivery(int mediaFileId);
    /** Resolves initiating-client skip settings, using existing Sage semantics. */
    long skip(String action);
    /** Suppresses ordinary per-viewer progress logging while party playback owns it. */
    void progressOwner(boolean partyOwnsProgress);
    /** Commits through existing watched/resume policy at leave/end only. */
    void commit(int mediaFileId, long positionMs);
    /** Schedules the existing server-owned push playback path; pull adapters execute locally. */
    void schedule(JsonObject transition);
    /** Cancels superseded/local-stop server-side work without stopping other viewers. */
    void cancelScheduled();
  }

  /** Effective per-viewer delivery, supplied by the playback core, never by JSON input. */
  public record Delivery(String surface, String mode, String videoCodec, String audioCodec,
      int bitrateKbps, boolean transcode) {}

  private static final long GRACE_MS = 15000, TOKEN_MS = 300000;
  private final LongSupplier time;
  private final boolean enabled;
  private final int maxKbps, maxTranscodes;
  private final SecureRandom random = new SecureRandom();
  private final Map<String, Party> parties = new LinkedHashMap<>();
  private final Map<Peer, Link> links = new HashMap<>();
  private boolean closed;

  private static final class Link
  {
    final String handle;
    String token;
    long tokenExpires;
    final LinkedHashMap<String, Cached> replies = new LinkedHashMap<>();
    long window;
    long highWater;
    int requests;
    Link(String handle) { this.handle = handle; }
  }
  private record Cached(String input, JsonObject result) {}
  private static final class Member
  {
    final String id, principal;
    String recovery;
    long recoveryExpires;
    Peer peer;
    String status = "PREPARING";
    final PartyClock clock = new PartyClock();
    final Map<String, Long> probes = new LinkedHashMap<>();
    Delivery delivery;
    Delivery reservation;
    boolean admitted;
    long preparingSince;
    long disconnectedAt = -1, lastReport = -1, lastSample = -1, correctionAt = -1;
    long precision = 0, ackSeq;
    String correctionId;
    String correctionKind;
    long correctionSkew, correctionDue;
    boolean rateAdjust;
    final Deque<Long> failures = new ArrayDeque<>();
    Member(String id, String recovery, Peer peer)
    { this.id = id; this.recovery = recovery; this.peer = peer; principal = peer.principal(); }
  }
  private static final class Party
  {
    final String id;
    final int media;
    final long duration, created;
    final LinkedHashMap<String, Member> members = new LinkedHashMap<>();
    String host, token, state = "PAUSED";
    long tokenExpires, version, seq, anchor, anchorAt;
    boolean hostOnly, pauseOnBuffer;
    JsonObject pending;
    Party(String id, int media, long duration, long now)
    { this.id = id; this.media = media; this.duration = duration; created = anchorAt = now; }
  }

  /** Creates an isolated service; capacity limits are staged-rollout policy. */
  public PartyService(LongSupplier monotonicMs, boolean enabled, int maxKbps, int maxTranscodes)
  { time = monotonicMs; this.enabled = enabled; this.maxKbps = maxKbps; this.maxTranscodes = maxTranscodes; }

  private String opaque()
  {
    byte[] b = new byte[24];
    random.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  /** Requires complete NG v1 guarantees and server-side connection authorization. */
  public synchronized boolean eligible(Peer peer)
  {
    return enabled && !closed && peer.principal() != null && !peer.principal().isEmpty()
        && peer.capabilities().containsAll(REQUIRED);
  }

  /** Registers an authorized compatible renderer for selected-client invitations. */
  public synchronized void connected(Peer peer)
  {
    if (eligible(peer)) links.computeIfAbsent(peer, p -> new Link(opaque()));
  }

  /** Builds availability only for a server-authorized, fully negotiated session. */
  public synchronized JsonObject availability(Peer peer)
  {
    if (!eligible(peer)) throw new Fault("NOT_AVAILABLE");
    connected(peer);
    Link link = links.get(peer);
    long now = time.getAsLong();
    if (link.token == null || link.tokenExpires - now < 60000)
    { link.token = opaque(); link.tokenExpires = now + 1800000; }
    JsonObject out = envelope("AVAILABLE");
    out.addProperty("serverEpoch", epoch);
    out.addProperty("sessionHandle", link.handle);
    out.addProperty("sessionToken", link.token);
    out.addProperty("sessionTokenExpiresAtServerMonoMs", link.tokenExpires);
    Party party = find(peer);
    if (party != null)
    {
      Member member = requireMember(party, peer);
      member.recoveryExpires = now + 1800000;
      out.addProperty("recoveryToken", member.recovery);
      out.addProperty("recoveryTokenExpiresAtServerMonoMs", member.recoveryExpires);
    }
    out.addProperty("maxParticipants", 5);
    return out;
  }

  /** Handles one envelope from the NG transport; failures are explicit safe codes. */
  public synchronized void receive(Peer peer, String json)
  {
    JsonObject request = null;
    JsonObject response;
    Link link = null;
    String id = null;
    try
    {
      if (!eligible(peer)) throw new Fault("NOT_AVAILABLE");
      connected(peer);
      link = links.get(peer);
      long now = time.getAsLong();
      if (now - link.window >= 1000) { link.window = now; link.requests = 0; }
      if (++link.requests > 30) throw new Fault("RATE_LIMITED");
      request = parse(json);
      id = text(request, "requestId");
      if (!"HELLO".equals(text(request, "type")))
      {
        if (!epoch.equals(text(request, "serverEpoch"))) throw new Fault("SERVER_EPOCH_CHANGED");
        if (link.token == null || link.tokenExpires <= now) throw new Fault("SESSION_EXPIRED");
        if (!constantEquals(link.token, text(request, "sessionToken"))) throw new Fault("SESSION_DENIED");
      }
      Cached cached = link.replies.get(id);
      if (cached != null)
      {
        if (!cached.input.equals(request.toString())) throw new Fault("REQUEST_ID_REUSED");
        peer.send(copy(cached.result));
        return;
      }
      long requestSeq = number(request, "requestSeq", 1, MAX_TIME);
      if (requestSeq <= link.highWater) throw new Fault("STALE_REQUEST");
      link.highWater = requestSeq;
      tick();
      response = dispatch(peer, request, now);
      response.addProperty("requestId", id);
      response.addProperty("serverMonoMs", now);
      diagnostic(null, null, "REQUEST_" + text(request, "type") + "_" + response.get("type").getAsString(), now);
    }
    catch (Fault f)
    {
      response = envelope("ERROR");
      response.addProperty("code", f.getMessage());
      diagnostic(null, null, "REJECT_" + f.getMessage(), time.getAsLong());
      if (request != null) response.addProperty("requestId", text(request, "requestId"));
    }
    if (id != null && link != null && !link.replies.containsKey(id))
    {
      link.replies.put(id, new Cached(request.toString(), copy(response)));
      if (link.replies.size() > 128) link.replies.remove(link.replies.keySet().iterator().next());
    }
    peer.send(response);
  }

  private JsonObject dispatch(Peer peer, JsonObject r, long now)
  {
    String type = text(r, "type");
    if ("HELLO".equals(type))
      return availability(peer);
    if ("LIST_CLIENTS".equals(type))
    {
      JsonObject out = envelope("CLIENTS");
      JsonArray a = new JsonArray();
      for (Map.Entry<Peer, Link> e : links.entrySet())
        if (eligible(e.getKey())) a.add(e.getValue().handle);
      out.add("sessionHandles", a);
      return out;
    }
    if ("CREATE".equals(type))
    {
      if (find(peer) != null) throw new Fault("ALREADY_JOINED");
      if (parties.size() >= 16) throw new Fault("SERVER_CAPACITY");
      int media = (int)number(r, "mediaFileId", 1, Integer.MAX_VALUE);
      long duration = peer.duration(media);
      if (duration <= 0) throw new Fault("MEDIA_UNAVAILABLE");
      Party p = new Party(opaque(), media, duration, now);
      Member m = member(peer);
      m.reservation = peer.plan(media);
      admit(m, m.reservation);
      m.preparingSince = now;
      p.members.put(m.id, m);
      p.host = m.id;
      rotate(p, now);
      parties.put(p.id, p);
      peer.progressOwner(true);
      return joined(p, m, "CREATED", now, true);
    }
    Party p = parties.get(text(r, "partyId"));
    if (p == null) throw new Fault("PARTY_GONE");
    if ("JOIN".equals(type))
    {
      if (find(peer) != null) throw new Fault("ALREADY_JOINED");
      if (p.tokenExpires <= now || !constantEquals(p.token, text(r, "joinToken")))
        throw new Fault("JOIN_DENIED");
      if (number(r, "mediaFileId", 1, Integer.MAX_VALUE) != p.media)
        throw new Fault("MEDIA_MISMATCH");
      if (peer.duration(p.media) != p.duration) throw new Fault("MEDIA_MISMATCH");
      if (p.members.size() >= 5) throw new Fault("PARTY_FULL");
      Member m = member(peer);
      m.reservation = peer.plan(p.media);
      admit(m, m.reservation);
      m.preparingSince = now;
      p.members.put(m.id, m);
      peer.progressOwner(true);
      if (p.host == null) transfer(p);
      roster(p, m);
      return joined(p, m, "JOINED", now, false);
    }
    if ("RESUME".equals(type))
    {
      if (find(peer) != null) throw new Fault("ALREADY_JOINED");
      Member m = p.members.get(text(r, "participantId"));
      if (m == null || m.peer != null || m.disconnectedAt + GRACE_MS < now || m.recoveryExpires <= now
          || !m.principal.equals(peer.principal())
          || !constantEquals(m.recovery, text(r, "recoveryToken")))
        throw new Fault("REJOIN_DENIED");
      Delivery plan = peer.plan(p.media);
      admit(m, plan);
      m.peer = peer;
      m.disconnectedAt = -1;
      reset(m);
      m.reservation = plan;
      m.preparingSince = now;
      peer.progressOwner(true);
      if (p.host == null) transfer(p);
      roster(p, m);
      return joined(p, m, "JOINED", now, false);
    }
    Member m = requireMember(p, peer);
    switch (type)
    {
      case "SNAPSHOT": return snapshot(p, now);
      case "INVITE":
      {
        host(p, m);
        String target = text(r, "sessionHandle");
        for (Map.Entry<Peer, Link> entry : links.entrySet())
          if (entry.getValue().handle.equals(target) && eligible(entry.getKey()))
          {
            JsonObject invite = envelope("INVITED");
            invite.addProperty("partyId", p.id);
            invite.addProperty("mediaFileId", p.media);
            invite.addProperty("joinToken", p.token);
            invite.addProperty("expiresAtServerMonoMs", p.tokenExpires);
            entry.getKey().send(invite);
            return envelope("ACK");
          }
        throw new Fault("CLIENT_UNAVAILABLE");
      }
      case "ROTATE_TOKEN":
        host(p, m); rotate(p, now);
        JsonObject token = envelope("TOKEN");
        token.addProperty("joinToken", p.token);
        token.addProperty("expiresAtServerMonoMs", p.tokenExpires);
        return token;
      case "POLICY":
        host(p, m);
        boolean hostOnly = flag(r, "hostOnly");
        boolean pauseOnBuffer = flag(r, "pauseOnBuffer");
        p.hostOnly = hostOnly;
        p.pauseOnBuffer = pauseOnBuffer;
        broadcast(p, snapshot(p, now));
        return envelope("ACK");
      case "CLOCK":
      {
        String probe = opaque();
        if (m.probes.size() >= 8) m.probes.remove(m.probes.keySet().iterator().next());
        m.probes.put(probe, now);
        JsonObject out = envelope("CLOCK_PROBE");
        out.addProperty("probeId", probe);
        out.addProperty("s0", now);
        return out;
      }
      case "CLOCK_REPLY":
      {
        Long s0 = m.probes.remove(text(r, "probeId"));
        if (s0 == null) throw new Fault("CLOCK_SAMPLE_INVALID");
        m.clock.add(s0, number(r, "c1", 0, MAX_TIME), number(r, "c2", 0, MAX_TIME), now);
        JsonObject out = envelope("CLOCK_MODEL");
        out.addProperty("serverMinusClientMs", m.clock.offset(now));
        out.addProperty("uncertaintyMs", m.clock.uncertainty(now));
        out.addProperty("p95RttMs", m.clock.p95(now));
        out.addProperty("confident", m.clock.confident(now));
        return out;
      }
      case "READY":
      {
        current(p, r);
        if (!"PREPARING".equals(m.status) && !"RECOVERING".equals(m.status)
            && !"BUFFERING".equals(m.status)) throw new Fault("REJOIN_REQUIRED");
        if (!flag(r, "prepared") || !flag(r, "activated")
            || number(r, "bufferAheadMs", 0, 3600000) < Math.min(1500, p.duration - position(p, now))
            || !m.clock.confident(now))
          throw new Fault("NOT_READY");
        long precision = number(r, "precisionMs", 0, 1000);
        boolean rate = flag(r, "rateAdjust");
        Delivery delivery = peer.delivery(p.media);
        if (m.reservation != null)
          delivery = new Delivery(delivery.surface, delivery.mode, delivery.videoCodec, delivery.audioCodec,
              Math.max(delivery.bitrateKbps, m.reservation.bitrateKbps),
              delivery.transcode || m.reservation.transcode);
        admit(m, delivery);
        m.precision = precision;
        m.rateAdjust = rate && peer.capabilities().contains("PARTY_RATE_ADJUST_V1");
        m.delivery = delivery;
        m.reservation = delivery;
        m.admitted = true;
        m.status = "READY";
        m.lastReport = now;
        roster(p, m);
        JsonObject out = correction(p, m, now, "HARD", 1);
        out.add("delivery", deliveryJson(delivery));
        peer.schedule(copy(out));
        return out;
      }
      case "ACTION":
        current(p, r);
        return action(p, m, text(r, "action"), now);
      case "ACK":
        current(p, r);
        m.ackSeq = number(r, "commandSeq", p.seq, p.seq);
        JsonObject ack = envelope("ACK");
        ack.addProperty("commandSeq", m.ackSeq);
        return ack;
      case "POSITION": return position(p, m, r, now);
      case "STATUS":
      {
        String status = text(r, "status");
        if (!Set.of("LOCALLY_STOPPED", "BUFFERING", "RECOVERING").contains(status))
          throw new Fault("INVALID_REQUEST");
        if ("LOCALLY_STOPPED".equals(m.status) && !"LOCALLY_STOPPED".equals(status))
          throw new Fault("REJOIN_REQUIRED");
        m.status = status;
        m.peer.cancelScheduled();
        if (!"BUFFERING".equals(status)) { m.clock.reset(); m.delivery = null; }
        if ("LOCALLY_STOPPED".equals(status)) m.reservation = null;
        if ("LOCALLY_STOPPED".equals(status)) m.correctionId = null;
        roster(p, m);
        if ("BUFFERING".equals(status) && p.pauseOnBuffer && p.pending == null
            && "PLAYING".equals(p.state))
          transition(p, m, "PAUSE", now, position(p, now + lead(p, now)), "PAUSED");
        return envelope("ACK");
      }
      case "CORRECTION_RESULT":
      {
        current(p, r);
        if (!text(r, "correctionId").equals(m.correctionId)) throw new Fault("STALE_CORRECTION");
        if (now < m.correctionDue) throw new Fault("CORRECTION_PENDING");
        boolean success = flag(r, "success");
        long landed = number(r, "positionMs", 0, p.duration);
        long sample = sampleTime(m, r, now);
        long residual = Math.abs(landed - position(p, sample));
        boolean converged = success && residual <= deadband(m, now);
        boolean progress = success && "SOFT".equals(m.correctionKind)
            && residual + 20 < Math.abs(m.correctionSkew);
        m.correctionId = null;
        m.lastReport = now;
        if (!converged && !progress) failed(m, now);
        else m.status = converged ? p.state : "RECOVERING";
        diagnostic(p, m, converged ? "CORRECTION_CONVERGED" : progress ? "CORRECTION_PROGRESS" : "CORRECTION_FAILED", now);
        roster(p, m);
        return envelope("ACK");
      }
      case "REJOIN":
        Delivery plan = peer.plan(p.media);
        admit(m, plan);
        reset(m);
        m.reservation = plan;
        m.preparingSince = now;
        roster(p, m);
        return joined(p, m, "JOINED", now, false);
      case "LEAVE":
        remove(p, m, true, now);
        return envelope("LEFT");
      case "END":
        host(p, m);
        end(p, true, "HOST_ENDED", now);
        return envelope("ENDED");
      default: throw new Fault("UNKNOWN_TYPE");
    }
  }

  private static final long MAX_TIME = 9007199254740991L;
  private final String epoch = opaque();

  private Member member(Peer peer) { return new Member(opaque(), opaque(), peer); }
  private static boolean constantEquals(String a, String b)
  {
    return java.security.MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        b.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
  }
  private void rotate(Party p, long now) { p.token = opaque(); p.tokenExpires = now + TOKEN_MS; }
  private Party find(Peer peer)
  {
    for (Party p : parties.values())
      for (Member m : p.members.values()) if (m.peer == peer) return p;
    return null;
  }
  private Member requireMember(Party p, Peer peer)
  {
    for (Member m : p.members.values())
      if (m.peer == peer && m.principal.equals(peer.principal())) return m;
    throw new Fault("NOT_MEMBER");
  }
  private void host(Party p, Member m) { if (!m.id.equals(p.host)) throw new Fault("HOST_REQUIRED"); }
  private void current(Party p, JsonObject r)
  {
    if (number(r, "stateVersion", 0, MAX_TIME) != p.version) throw new Fault("STALE_STATE");
  }
  private void reset(Member m)
  {
    if (m.peer != null) m.peer.cancelScheduled();
    m.status = "PREPARING"; m.clock.reset(); m.probes.clear(); m.delivery = null;
    m.lastReport = m.lastSample = m.correctionAt = -1; m.correctionId = null; m.failures.clear();
  }
  private long position(Party p, long at)
  {
    long anchor = p.anchor, anchorAt = p.anchorAt;
    String state = p.state;
    if (p.pending != null && at >= p.pending.get("executeAtServerMonoMs").getAsLong())
    {
      anchor = p.pending.get("targetPositionMs").getAsLong();
      anchorAt = p.pending.get("executeAtServerMonoMs").getAsLong();
      state = p.pending.get("targetState").getAsString();
    }
    return Math.max(0, Math.min(p.duration, anchor + ("PLAYING".equals(state) ? Math.max(0, at - anchorAt) : 0)));
  }
  private long lead(Party p, long now)
  {
    long p95 = 0;
    for (Member m : p.members.values())
      if (healthy(m, now)) p95 = Math.max(p95, m.clock.p95(now));
    return Math.min(3000, Math.max(750, 3 * p95 + 250));
  }
  private boolean healthy(Member m, long now)
  {
    return m.peer != null && Set.of("READY", "PLAYING", "PAUSED").contains(m.status)
        && m.clock.confident(now);
  }
  private JsonObject action(Party p, Member m, String action, long now)
  {
    if (!Set.of("PLAY", "PAUSE", "FF", "REW", "FF2", "REW2").contains(action))
      throw new Fault("INVALID_ACTION");
    if (p.hostOnly && !m.id.equals(p.host)) throw new Fault("HOST_REQUIRED");
    if (!healthy(m, now)) throw new Fault("NOT_READY");
    if (p.pending != null) throw new Fault("TRANSITION_PENDING");
    String state = "PLAY".equals(action) ? "PLAYING" : "PAUSE".equals(action) ? "PAUSED" : p.state;
    long target = position(p, now + lead(p, now));
    if (action.startsWith("FF") || action.startsWith("REW"))
    {
      long delta = m.peer.skip(action);
      target = delta >= 0 ? target + Math.min(delta, p.duration - target)
          : target - Math.min(delta == Long.MIN_VALUE ? Long.MAX_VALUE : -delta, target);
    }
    return transition(p, m, action, now, target, state);
  }
  private JsonObject transition(Party p, Member m, String cause, long now, long target, String state)
  {
    JsonObject out = envelope("COMMAND");
    out.addProperty("partyId", p.id);
    out.addProperty("stateVersion", ++p.version);
    out.addProperty("commandSeq", ++p.seq);
    out.addProperty("cause", cause);
    out.addProperty("targetPositionMs", target);
    out.addProperty("targetState", state);
    out.addProperty("targetRate", 1);
    out.addProperty("executeAtServerMonoMs", now + lead(p, now));
    out.addProperty("originParticipantId", m.id);
    p.pending = copy(out);
    for (Member member : p.members.values())
    {
      member.correctionId = null;
      if (member.peer != null) member.peer.cancelScheduled();
    }
    diagnostic(p, m, "COMMAND_" + cause + "_DEADLINE_" + out.get("executeAtServerMonoMs").getAsLong(), now);
    for (Member member : p.members.values())
      if (healthy(member, now))
      {
        member.peer.schedule(copy(out));
        member.peer.send(copy(out));
      }
    return out;
  }
  private long deadband(Member m, long now)
  { return Math.max(m.precision, m.clock.p95(now) > 40 ? 250 : 125); }
  private long sampleTime(Member m, JsonObject r, long now)
  {
    if (!m.clock.confident(now)) throw new Fault("CLOCK_UNCERTAIN");
    long local = number(r, "sampleMonoMs", 0, MAX_TIME);
    long sample = Math.round(local + m.clock.offset(now));
    if (sample < now - 3000 || sample > now + m.clock.uncertainty(now))
      throw new Fault("STALE_SAMPLE");
    return sample;
  }
  private JsonObject position(Party p, Member m, JsonObject r, long now)
  {
    current(p, r);
    if ("LOCALLY_STOPPED".equals(m.status) || "PREPARING".equals(m.status) || "DEGRADED".equals(m.status))
      throw new Fault("REJOIN_REQUIRED");
    long sample = sampleTime(m, r, now);
    if (sample <= m.lastSample) throw new Fault("STALE_SAMPLE");
    long landed = number(r, "positionMs", 0, p.duration);
    number(r, "bufferAheadMs", 0, 3600000);
    String state = text(r, "state");
    if (!Set.of("PLAYING", "PAUSED", "BUFFERING", "SEEKING").contains(state)) throw new Fault("INVALID_REQUEST");
    m.lastSample = sample; m.lastReport = now;
    JsonObject ack = envelope("ACK");
    long skew = landed - position(p, sample);
    ack.addProperty("skewMs", skew);
    ack.addProperty("uncertaintyMs", m.clock.uncertainty(now));
    if (p.pending != null || "BUFFERING".equals(m.status) || m.correctionId != null) return ack;
    if (Math.abs(skew) <= deadband(m, now) && state.equals(p.state))
    { m.status = state; return ack; }
    boolean soft = Math.abs(skew) <= 750 && m.rateAdjust && "PLAYING".equals(p.state)
        && "PLAYING".equals(state);
    JsonObject correction = correction(p, m, now, soft ? "SOFT" : "HARD", skew > 0 ? .98 : 1.02);
    m.correctionSkew = skew;
    m.peer.schedule(copy(correction));
    m.peer.send(correction);
    return ack;
  }
  private JsonObject correction(Party p, Member m, long now, String kind, double rate)
  {
    long at = now + lead(p, now);
    JsonObject out = envelope("CORRECTION");
    out.addProperty("partyId", p.id);
    out.addProperty("participantId", m.id);
    out.addProperty("stateVersion", p.version);
    out.addProperty("commandSeq", p.seq);
    out.addProperty("correctionId", m.correctionId = opaque());
    out.addProperty("kind", kind);
    out.addProperty("targetPositionMs", position(p, at));
    out.addProperty("targetState", p.pending == null ? p.state : p.pending.get("targetState").getAsString());
    out.addProperty("targetRate", "SOFT".equals(kind) ? rate : 1);
    out.addProperty("durationMs", "SOFT".equals(kind) ? 5000 : 0);
    out.addProperty("executeAtServerMonoMs", at);
    m.correctionAt = now;
    m.correctionDue = at;
    m.correctionKind = kind;
    m.status = "RECOVERING";
    diagnostic(p, m, "CORRECTION_" + kind + "_DEADLINE_" + at, now);
    return out;
  }
  private void failed(Member m, long now)
  {
    while (!m.failures.isEmpty() && m.failures.peekFirst() < now - 30000) m.failures.removeFirst();
    m.failures.addLast(now);
    m.status = m.failures.size() >= 3 ? "DEGRADED" : "RECOVERING";
  }
  private void admit(Member target, Delivery d)
  {
    if (d == null || d.bitrateKbps <= 0 || d.mode == null || d.mode.isEmpty())
      throw new Fault("MEDIA_PATH_NOT_READY");
    long bitrate = d.bitrateKbps;
    int transcodes = d.transcode ? 1 : 0;
    for (Party p : parties.values())
      for (Member m : p.members.values())
        if (m != target && m.reservation != null)
        { bitrate += m.reservation.bitrateKbps; if (m.reservation.transcode) transcodes++; }
    if (bitrate > maxKbps || transcodes > maxTranscodes) throw new Fault("RESOURCE_DENIED");
  }
  private JsonObject deliveryJson(Delivery d)
  {
    JsonObject o = new JsonObject();
    o.addProperty("surface", d.surface); o.addProperty("mode", d.mode);
    o.addProperty("videoCodec", d.videoCodec); o.addProperty("audioCodec", d.audioCodec);
    o.addProperty("reservedKbps", d.bitrateKbps); o.addProperty("transcode", d.transcode);
    return o;
  }
  private JsonObject joined(Party p, Member m, String type, long now, boolean token)
  {
    JsonObject out = envelope(type);
    m.recovery = opaque();
    m.recoveryExpires = now + 1800000;
    out.addProperty("partyId", p.id); out.addProperty("participantId", m.id);
    out.addProperty("recoveryToken", m.recovery);
    out.addProperty("recoveryTokenExpiresAtServerMonoMs", m.recoveryExpires);
    out.addProperty("reconnectGraceMs", GRACE_MS);
    out.addProperty("prepareVia", "NORMAL_NG_WATCH");
    if (m.reservation != null) out.add("preparation", deliveryJson(m.reservation));
    out.add("snapshot", snapshot(p, now));
    if (token) { out.addProperty("joinToken", p.token); out.addProperty("expiresAtServerMonoMs", p.tokenExpires); }
    return out;
  }
  private JsonObject snapshot(Party p, long now)
  {
    JsonObject o = envelope("SNAPSHOT");
    o.addProperty("partyId", p.id); o.addProperty("serverEpoch", epoch);
    o.addProperty("mediaFileId", p.media); o.addProperty("durationMs", p.duration);
    o.addProperty("stateVersion", p.version); o.addProperty("commandSeq", p.seq);
    o.addProperty("anchorPositionMs", p.anchor); o.addProperty("anchorServerMonoMs", p.anchorAt);
    o.addProperty("state", p.state); o.addProperty("rate", 1);
    o.addProperty("serverMonoMs", now); o.addProperty("hostParticipantId", p.host);
    o.addProperty("hostOnly", p.hostOnly); o.addProperty("pauseOnBuffer", p.pauseOnBuffer);
    if (p.pending != null) o.add("pendingCommand", copy(p.pending));
    JsonArray roster = new JsonArray();
    for (Member m : p.members.values())
    {
      JsonObject entry = new JsonObject();
      entry.addProperty("participantId", m.id); entry.addProperty("status", m.status);
      entry.addProperty("clockConfident", m.clock.confident(now));
      entry.addProperty("p95RttMs", m.clock.p95(now));
      entry.addProperty("uncertaintyMs", m.clock.uncertainty(now));
      entry.addProperty("ackCommandSeq", m.ackSeq);
      if (m.delivery != null) entry.add("delivery", deliveryJson(m.delivery));
      roster.add(entry);
    }
    o.add("roster", roster);
    return o;
  }
  private void roster(Party p, Member m)
  {
    JsonObject o = envelope("PARTICIPANT_STATE");
    o.addProperty("partyId", p.id); o.addProperty("participantId", m.id);
    o.addProperty("status", m.status);
    diagnostic(p, m, "STATUS_" + m.status, time.getAsLong());
    // Roster changes, especially local Stop, do not mutate timeline version/sequence.
    broadcast(p, o);
  }
  private void broadcast(Party p, JsonObject out)
  { for (Member m : p.members.values()) if (m.peer != null) m.peer.send(copy(out)); }

  /** Routes existing server-side controls without rebroadcasting raw input or Stop. */
  public synchronized boolean nativeAction(Peer peer, String action)
  {
    Party p = find(peer);
    if (p == null) return false;
    try
    {
      if (!eligible(peer)) throw new Fault("NOT_AVAILABLE");
      tick();
      action(p, requireMember(p, peer),
          "TOGGLE".equals(action) ? ("PLAYING".equals(p.state) ? "PAUSE" : "PLAY") : action,
          time.getAsLong());
    }
    catch (Fault f)
    {
      JsonObject error = envelope("ERROR");
      error.addProperty("code", f.getMessage());
      peer.send(error);
    }
    return true;
  }

  /** Retains the logical slot for reconnect; physical socket IDs are never identities. */
  public synchronized void disconnected(Peer peer)
  {
    links.remove(peer);
    Party p = find(peer);
    if (p == null) return;
    Member m = requireMember(p, peer);
    peer.cancelScheduled();
    m.peer = null; m.disconnectedAt = time.getAsLong(); m.clock.reset();
    m.correctionId = null;
    m.probes.clear();
    m.status = "DISCONNECTED";
    roster(p, m);
    // Everyone-control transfers immediately; host-only locks until grace expiry.
    if (m.id.equals(p.host) && !p.hostOnly) transfer(p);
  }
  private void transfer(Party p)
  {
    p.host = null;
    for (Member m : p.members.values())
      if (m.peer != null) { p.host = m.id; break; }
    broadcast(p, snapshot(p, time.getAsLong()));
  }
  private void remove(Party p, Member m, boolean commit, long now)
  {
    if (m.peer != null)
    {
      m.peer.cancelScheduled();
      if (commit && m.admitted) m.peer.commit(p.media, position(p, now));
      m.peer.progressOwner(false);
    }
    p.members.remove(m.id);
    m.status = "LEFT"; roster(p, m);
    if (p.members.isEmpty()) parties.remove(p.id);
    else if (m.id.equals(p.host)) transfer(p);
  }
  private void end(Party p, boolean commit, String reason, long now)
  {
    JsonObject o = envelope("ENDED");
    o.addProperty("partyId", p.id); o.addProperty("reason", reason);
    broadcast(p, o);
    for (Member m : new ArrayList<>(p.members.values())) remove(p, m, commit, now);
    parties.remove(p.id);
  }

  /** Advances deadlines, invalidates stale clocks, expires grace slots and abandoned parties. */
  public synchronized void tick()
  {
    long now = time.getAsLong();
    for (Party p : new ArrayList<>(parties.values()))
    {
      if (p.pending != null && p.pending.get("executeAtServerMonoMs").getAsLong() <= now)
      {
        p.anchor = p.pending.get("targetPositionMs").getAsLong();
        p.anchorAt = p.pending.get("executeAtServerMonoMs").getAsLong();
        p.state = p.pending.get("targetState").getAsString();
        p.pending = null;
      }
      for (Member m : new ArrayList<>(p.members.values()))
      {
        if (m.peer == null && now - m.disconnectedAt > GRACE_MS) { remove(p, m, false, now); continue; }
        if ("PREPARING".equals(m.status) && now - m.preparingSince > 60000)
        {
          JsonObject error = envelope("ERROR");
          error.addProperty("code", "PREPARATION_EXPIRED");
          if (m.peer != null) m.peer.send(error);
          remove(p, m, false, now);
          continue;
        }
        if (m.correctionId != null && now - m.correctionAt > 8000)
        { m.correctionId = null; failed(m, now); roster(p, m); }
        if (Set.of("READY", "PLAYING", "PAUSED").contains(m.status)
            && (!m.clock.confident(now) || m.lastReport >= 0 && now - m.lastReport > 10000))
        { m.status = "RECOVERING"; m.clock.reset(); roster(p, m); }
      }
      if (now - p.created > 24 * 3600000L) end(p, false, "EXPIRED", now);
    }
  }

  /** Clears ephemeral authority on shutdown without sending any playback Stop. */
  public synchronized void shutdown()
  {
    for (Party p : new ArrayList<>(parties.values())) end(p, false, "SERVER_SHUTDOWN", time.getAsLong());
    parties.clear(); links.clear(); closed = true;
  }

  private void diagnostic(Party p, Member m, String outcome, long now)
  {
    System.out.println("PartyMode party=" + (p == null ? "-" : p.id)
        + " participant=" + (m == null ? "-" : m.id)
        + " mediaFileId=" + (p == null ? 0 : p.media)
        + " version=" + (p == null ? 0 : p.version) + " seq=" + (p == null ? 0 : p.seq)
        + " activeCount=" + (p == null ? 0 : p.members.size())
        + " serverMonoMs=" + now + " outcome=" + outcome);
  }
}
