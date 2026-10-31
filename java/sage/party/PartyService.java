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
    /** Validates eligible accessible media and returns its currently available duration. */
    long duration(int mediaFileId);
    /** Returns the current server-owned media window; completed media is fixed. */
    default MediaWindow mediaWindow(int mediaFileId)
    { return new MediaWindow(duration(mediaFileId), false); }
    /** Samples the initiating renderer's loaded media position, validating its exact playback generation. */
    default long currentPosition(int mediaFileId) { throw new Fault("CURRENT_POSITION_UNAVAILABLE"); }
    /** Evaluates this viewer's normal NG surface plan before preparation consumes resources. */
    Delivery plan(int mediaFileId);
    /** Returns actual server-selected playback metadata, or throws until prepared. */
    Delivery delivery(int mediaFileId);
    /** Describes the actual paused loaded path, without claiming producer admission. */
    default Delivery preparedDelivery(int mediaFileId) { return delivery(mediaFileId); }
    /** Issues a media-path binding credential for the current loaded playback generation. */
    default JsonObject mediaAdmission(int mediaFileId) { throw new Fault("MEDIA_ADMISSION_UNCONFIRMED"); }
    /** Checks existing media producer admission; client readiness flags are not evidence. */
    default boolean mediaAdmitted(int mediaFileId) { return false; }
    /** Revokes pending or confirmed media correlation without touching another viewer. */
    default void releaseMediaAdmission() {}
    /** Stages only the joined canonical media through this renderer's ordinary Watch checks. */
    default void prepare(int mediaFileId, String preparationId) { throw new Fault("PREPARE_UNSUPPORTED"); }
    /** Invalidates asynchronous preparation on membership or local lifecycle changes. */
    default void cancelPreparation() {}
    /** Queries and plans a renderer-only surface asynchronously without accepting JSON codec claims. */
    default void selectSurface(int mediaFileId, String surfaceId, String selectionId) { throw new Fault("SURFACE_UNSUPPORTED"); }
    /** Commits a queried candidate only if its original renderer/player generation is still idle. */
    default long applySurface(String selectionId) { throw new Fault("SURFACE_UNSUPPORTED"); }
    /** Cancels pending selection and restores pre-Party discovery for subsequent ordinary Watch. */
    default long restoreSurface() { return 0; }
    /** Checks whether a committed renderer-only path remains selected on its original path generation. */
    default boolean surfaceSelected(String selectionId, long pathGeneration) { return false; }
    /** Registers server reply metadata for same-JVM opaque bridge proof; no client metadata is trusted. */
    default void describeMediaAdmission(JsonObject reply) {}
    /** Performs bounded nonblocking transport/admission cleanup during maintenance. */
    default void maintenance() {}
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

  /** Current playable source extent for fixed or actively growing media. */
  public record MediaWindow(long availableEndMs, boolean growing) {}

  /** Effective per-viewer delivery, supplied by the playback core, never by JSON input. */
  public record Delivery(String surface, String mode, String videoCodec, String audioCodec,
      int bitrateKbps, boolean transcode, String route, String xcodeMode, String container)
  {
    /** Creates delivery metadata without optional conditioned-path details. */
    public Delivery(String surface, String mode, String videoCodec, String audioCodec,
        int bitrateKbps, boolean transcode)
    { this(surface, mode, videoCodec, audioCodec, bitrateKbps, transcode, null, null, null); }
  }

  private static final long GRACE_MS = 15000;
  private final LongSupplier time;
  private final boolean enabled;
  private final int maxKbps, maxTranscodes;
  private final long liveEdgeDelayMs;
  private final SecureRandom random = new SecureRandom();
  private final Map<String, Party> parties = new LinkedHashMap<>();
  private final Map<Peer, Link> links = new HashMap<>();
  private final PartyJoinCodes joinCodes = new PartyJoinCodes();
  private final Deque<Long> joinAttempts = new ArrayDeque<>();
  private final Deque<Long> codeIssues = new ArrayDeque<>();
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
    final Deque<Long> joinAttempts = new ArrayDeque<>();
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
    String preparationId;
    boolean preparationPending;
    String selectionId;
    boolean selectionPending;
    long pathGeneration;
    boolean surfacePrepared;
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
    final long created;
    final LinkedHashMap<String, Member> members = new LinkedHashMap<>();
    final LinkedHashMap<Long, CommandOutcome> outcomes = new LinkedHashMap<>();
    String host, state = "PAUSED";
    long version, seq, anchor, anchorAt, duration;
    boolean growing;
    boolean hostOnly, pauseOnBuffer;
    boolean majorityNotified;
    long lastBufferNotice = -1;
    JsonObject pending;
    Party(String id, int media, MediaWindow window, long now)
    {
      this.id = id; this.media = media; duration = window.availableEndMs();
      growing = window.growing(); created = anchorAt = now;
    }
  }
  private static final class Execution
  {
    long receivedAt = -1, executedAt = -1, position, skew;
    double uncertainty;
    boolean success;
    String state;
  }
  private static final class CommandOutcome
  {
    final JsonObject command;
    final LinkedHashMap<String, Execution> participants = new LinkedHashMap<>();
    boolean complete, timedOut, superseded;
    CommandOutcome(JsonObject command) { this.command = copy(command); }
  }

  /** Creates an isolated service; capacity limits are staged-rollout policy. */
  public PartyService(LongSupplier monotonicMs, boolean enabled, int maxKbps, int maxTranscodes)
  { this(monotonicMs, enabled, maxKbps, maxTranscodes, 3000); }

  /** Creates an isolated service with an explicit growing-media safety delay. */
  public PartyService(LongSupplier monotonicMs, boolean enabled, int maxKbps, int maxTranscodes,
      long liveEdgeDelayMs)
  {
    time = monotonicMs; this.enabled = enabled; this.maxKbps = maxKbps;
    this.maxTranscodes = maxTranscodes; this.liveEdgeDelayMs = Math.max(1500, liveEdgeDelayMs);
  }

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
    JsonArray starts = new JsonArray();
    starts.add("BEGINNING");
    if (peer.capabilities().contains("PARTY_START_POSITION_V1")) starts.add("CURRENT_POSITION");
    out.add("supportedStartModes", starts);
    out.addProperty("prepareSupported", peer.capabilities().contains("PARTY_PREPARE_V1"));
    out.addProperty("surfaceSelectionSupported", peer.capabilities().contains("PARTY_SURFACE_V1")
        && peer.capabilities().contains("PARTY_PREPARE_V1"));
    out.addProperty("growingMediaSupported", peer.capabilities().contains(GROWING_MEDIA));
    out.addProperty("joinCodeDigits", 3);
    out.addProperty("joinCodeLifetimeMs", PartyJoinCodes.LIFETIME_MS);
    out.addProperty("joinAttemptWindowMs", 60000);
    out.addProperty("joinAttemptsPerSession", 3);
    out.addProperty("joinAttemptsGlobal", 6);
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
      if ("MEDIA_ADMISSION_HANDLE".equals(text(response, "type")))
      {
        response.add("requestSeq", request.get("requestSeq"));
        peer.describeMediaAdmission(response);
      }
      diagnostic(null, null, "REQUEST_" + text(request, "type") + "_" + response.get("type").getAsString(), now);
    }
    catch (Fault f)
    {
      response = envelope("ERROR");
      response.addProperty("code", f.getMessage());
      if ("JOIN_RATE_LIMITED".equals(f.getMessage()))
        response.addProperty("retryAfterMs", joinRetryAfter(link, time.getAsLong()));
      if ("CODE_ROTATION_COOLDOWN".equals(f.getMessage()) && request != null && request.has("partyId"))
        response.addProperty("retryAfterMs", joinCodes.rotationRetryAfter(text(request, "partyId"), time.getAsLong()));
      if ("CODE_ISSUE_RATE_LIMITED".equals(f.getMessage()))
        response.addProperty("retryAfterMs", codeIssueRetryAfter(time.getAsLong()));
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
      String startMode = r.has("startMode") ? text(r, "startMode") : "BEGINNING";
      if (!Set.of("BEGINNING", "CURRENT_POSITION").contains(startMode)
          || r.has("startPositionMs") || r.has("positionMs"))
        throw new Fault("INVALID_REQUEST");
      if ("CURRENT_POSITION".equals(startMode) && !peer.capabilities().contains("PARTY_START_POSITION_V1"))
        throw new Fault("START_MODE_UNSUPPORTED");
      MediaWindow window = mediaWindow(peer, media);
      requireGrowingCapability(peer, window);
      Party p = new Party(opaque(), media, window, now);
      Member m = member(peer);
      m.reservation = peer.plan(media);
      admit(m, m.reservation);
      if ("CURRENT_POSITION".equals(startMode))
        p.anchor = clamp(p, peer.currentPosition(media));
      m.preparingSince = now;
      p.members.put(m.id, m);
      p.host = m.id;
      issueCode(p.id, now);
      parties.put(p.id, p);
      peer.progressOwner(true);
      JsonObject created = joined(p, m, "CREATED", now, true);
      created.addProperty("startMode", startMode);
      created.addProperty("startPositionMs", p.anchor);
      return created;
    }
    String partyId;
    if ("JOIN".equals(type))
    {
      if (find(peer) != null) throw new Fault("ALREADY_JOINED");
      attemptJoin(links.get(peer), now);
      if (r.has("joinToken") || !r.has("joinCode")) throw new Fault("JOIN_DENIED");
      partyId = joinCodes.resolve(text(r, "joinCode"), now);
      if (r.has("partyId") && !partyId.equals(text(r, "partyId"))) throw new Fault("JOIN_DENIED");
    }
    else partyId = text(r, "partyId");
    Party p = parties.get(partyId);
    if (p == null) throw new Fault("PARTY_GONE");
    if ("JOIN".equals(type))
    {
      if (find(peer) != null) throw new Fault("ALREADY_JOINED");
      if (r.has("mediaFileId") && number(r, "mediaFileId", 1, Integer.MAX_VALUE) != p.media)
        throw new Fault("MEDIA_MISMATCH");
      refreshWindow(p, peer);
      requireGrowingCapability(peer, new MediaWindow(p.duration, p.growing));
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
      refreshWindow(p, peer);
      requireGrowingCapability(peer, new MediaWindow(p.duration, p.growing));
      admit(m, plan);
      m.peer = peer;
      m.selectionId = null; m.selectionPending = false;
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
      case "SNAPSHOT":
        refreshWindow(p, peer);
        return snapshot(p, now);
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
            invite.addProperty("joinCodeRequired", true);
            entry.getKey().send(invite);
            return envelope("ACK");
          }
        throw new Fault("CLIENT_UNAVAILABLE");
      }
      case "ROTATE_CODE":
      case "JOIN_CODE":
        host(p, m);
        PartyJoinCodes.Grant code = "ROTATE_CODE".equals(type) ? issueCode(p.id, now) : joinCodes.current(p.id, now);
        JsonObject codeReply = envelope("JOIN_CODE");
        codeReply.addProperty("partyId", p.id);
        codeReply.addProperty("joinCode", code.code());
        codeReply.addProperty("expiresAtServerMonoMs", code.expiresAt());
        return codeReply;
      case "POLICY":
        host(p, m);
        boolean hostOnly = flag(r, "hostOnly");
        boolean pauseOnBuffer = flag(r, "pauseOnBuffer");
        p.hostOnly = hostOnly;
        p.pauseOnBuffer = pauseOnBuffer;
        p.majorityNotified = false;
        bufferPolicy(p, now);
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
      case "MEDIA_ADMISSION":
        if (m.selectionPending) throw new Fault("SURFACE_SELECTION_PENDING");
        selectedPrepared(m);
        if (m.preparationPending) throw new Fault("PREPARATION_PENDING");
        if (!Set.of("PREPARING", "RECOVERING", "BUFFERING").contains(m.status))
          throw new Fault("REJOIN_REQUIRED");
        JsonObject admission = peer.mediaAdmission(p.media);
        admission.addProperty("serverEpoch", epoch);
        admission.addProperty("sessionHandle", links.get(peer).handle);
        admission.addProperty("partyId", p.id);
        return admission;
      case "SELECT_SURFACE":
      {
        if (!peer.capabilities().contains("PARTY_SURFACE_V1") || !peer.capabilities().contains("PARTY_PREPARE_V1"))
          throw new Fault("SURFACE_UNSUPPORTED");
        if (!Set.of("PREPARING", "RECOVERING", "BUFFERING").contains(m.status)) throw new Fault("REJOIN_REQUIRED");
        if (m.selectionPending || m.preparationPending) throw new Fault("SURFACE_SELECTION_PENDING");
        if (m.selectionId != null) throw new Fault("SURFACE_ALREADY_SELECTED");
        String surface = text(r, "surfaceId");
        if (!surface.matches("[A-Za-z0-9_]{1,64}")) throw new Fault("SURFACE_UNAVAILABLE");
        reset(m);
        m.selectionId = opaque(); m.selectionPending = true; m.preparingSince = now;
        try { peer.selectSurface(p.media, surface, m.selectionId); }
        catch (Fault f) { m.selectionId = null; m.selectionPending = false; throw f; }
        JsonObject accepted = envelope("SURFACE_SELECTION_ACCEPTED");
        accepted.addProperty("partyId", p.id); accepted.addProperty("selectionId", m.selectionId);
        return accepted;
      }
      case "RESTORE_SURFACE":
      {
        if (!peer.capabilities().contains("PARTY_SURFACE_V1")) throw new Fault("SURFACE_UNSUPPORTED");
        reset(m);
        m.selectionPending = false; m.selectionId = null; m.reservation = null; m.preparingSince = now;
        m.pathGeneration = peer.restoreSurface();
        JsonObject restored = envelope("SURFACE_RESTORED");
        restored.addProperty("partyId", p.id); restored.addProperty("pathGeneration", m.pathGeneration);
        return restored;
      }
      case "PREPARE":
      {
        refreshWindow(p, peer);
        if (!peer.capabilities().contains("PARTY_PREPARE_V1")) throw new Fault("PREPARE_UNSUPPORTED");
        if (!"JOIN_AND_PREPARE".equals(text(r, "intent")) || !flag(r, "confirmReplace")
            || r.has("mediaFileId") || r.has("positionMs"))
          throw new Fault("INVALID_REQUEST");
        if (!Set.of("PREPARING", "RECOVERING", "BUFFERING").contains(m.status))
          throw new Fault("REJOIN_REQUIRED");
        if (m.preparationPending) throw new Fault("PREPARATION_PENDING");
        if (m.selectionPending) throw new Fault("SURFACE_SELECTION_PENDING");
        if (m.selectionId != null && (!r.has("selectionId") || !constantEquals(m.selectionId, text(r, "selectionId")))
            || m.selectionId == null && r.has("selectionId"))
          throw new Fault("SURFACE_CHANGED");
        if (m.selectionId != null && !peer.surfaceSelected(m.selectionId, m.pathGeneration))
          throw new Fault("SURFACE_CHANGED");
        Delivery plan = peer.plan(p.media);
        admit(m, plan);
        reset(m);
        m.reservation = plan;
        m.preparingSince = now;
        m.preparationId = opaque(); m.preparationPending = true;
        m.surfacePrepared = false;
        try { peer.prepare(p.media, m.preparationId); }
        catch (Fault f) { m.preparationPending = false; m.preparationId = null; throw f; }
        JsonObject accepted = envelope("PREPARE_ACCEPTED");
        accepted.addProperty("partyId", p.id); accepted.addProperty("mediaFileId", p.media);
        accepted.addProperty("preparationId", m.preparationId);
        accepted.addProperty("holdPlayback", true);
        if (m.selectionId != null)
        { accepted.addProperty("selectionId", m.selectionId); accepted.addProperty("pathGeneration", m.pathGeneration); }
        return accepted;
      }
      case "READY":
      {
        refreshWindow(p, peer);
        if (m.selectionPending) throw new Fault("SURFACE_SELECTION_PENDING");
        selectedPrepared(m);
        if (m.preparationPending) throw new Fault("PREPARATION_PENDING");
        current(p, r);
        if (!"PREPARING".equals(m.status) && !"RECOVERING".equals(m.status)
            && !"BUFFERING".equals(m.status)) throw new Fault("REJOIN_REQUIRED");
        if (!flag(r, "prepared") || !flag(r, "activated")
            || number(r, "bufferAheadMs", 0, 3600000)
                < (p.growing ? 1500 : Math.min(1500, p.duration - position(p, now)))
            || !m.clock.confident(now))
          throw new Fault("NOT_READY");
        long precision = number(r, "precisionMs", 0, 1000);
        boolean rate = flag(r, "rateAdjust");
        if (!peer.mediaAdmitted(p.media)) throw new Fault("MEDIA_ADMISSION_UNCONFIRMED");
        Delivery delivery = peer.delivery(p.media);
        if (m.reservation != null)
          delivery = new Delivery(delivery.surface, delivery.mode, delivery.videoCodec, delivery.audioCodec,
              Math.max(delivery.bitrateKbps, m.reservation.bitrateKbps),
              delivery.transcode || m.reservation.transcode, delivery.route, delivery.xcodeMode, delivery.container);
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
        refreshWindow(p, peer);
        current(p, r);
        return action(p, m, text(r, "action"), now);
      case "ACK":
        refreshWindow(p, peer);
        return commandAck(p, m, r, now);
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
        if (!"BUFFERING".equals(status)) m.peer.releaseMediaAdmission();
        if (!"BUFFERING".equals(status))
        { m.peer.cancelPreparation(); m.preparationId = null; m.preparationPending = false; }
        if ("LOCALLY_STOPPED".equals(status)) m.reservation = null;
        if ("LOCALLY_STOPPED".equals(status)) m.correctionId = null;
        if ("LOCALLY_STOPPED".equals(status))
        { peer.restoreSurface(); m.selectionId = null; m.selectionPending = false; }
        roster(p, m);
        bufferPolicy(p, now);
        return envelope("ACK");
      }
      case "CORRECTION_RESULT":
      {
        refreshWindow(p, peer);
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
        refreshWindow(p, peer);
        requireGrowingCapability(peer, new MediaWindow(p.duration, p.growing));
        peer.restoreSurface(); m.selectionId = null; m.selectionPending = false;
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
  private long joinRetryAfter(Link link, long now)
  {
    while (!joinAttempts.isEmpty() && joinAttempts.peekFirst() <= now - 60000) joinAttempts.removeFirst();
    if (link != null)
      while (!link.joinAttempts.isEmpty() && link.joinAttempts.peekFirst() <= now - 60000)
        link.joinAttempts.removeFirst();
    long global = joinAttempts.size() >= 6 ? joinAttempts.peekFirst() + 60000 - now : 0;
    long local = link != null && link.joinAttempts.size() >= 3 ? link.joinAttempts.peekFirst() + 60000 - now : 0;
    return Math.max(global, local);
  }
  private void attemptJoin(Link link, long now)
  {
    if (joinRetryAfter(link, now) > 0) throw new Fault("JOIN_RATE_LIMITED");
    joinAttempts.addLast(now); link.joinAttempts.addLast(now);
  }
  private void selectedPrepared(Member member)
  {
    if (member.selectionId == null) return;
    if (!member.peer.surfaceSelected(member.selectionId, member.pathGeneration)) throw new Fault("SURFACE_CHANGED");
    if (!member.surfacePrepared) throw new Fault("SURFACE_PREPARATION_REQUIRED");
  }
  private long codeIssueRetryAfter(long now)
  {
    while (!codeIssues.isEmpty() && codeIssues.peekFirst() <= now - 60000) codeIssues.removeFirst();
    return codeIssues.size() >= 12 ? codeIssues.peekFirst() + 60000 - now : 0;
  }
  private PartyJoinCodes.Grant issueCode(String party, long now)
  {
    if (codeIssueRetryAfter(now) > 0) throw new Fault("CODE_ISSUE_RATE_LIMITED");
    PartyJoinCodes.Grant grant = joinCodes.issue(party, now);
    codeIssues.addLast(now);
    return grant;
  }
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
    if (m.peer != null) { m.peer.cancelScheduled(); m.peer.releaseMediaAdmission(); m.peer.cancelPreparation(); }
    m.preparationId = null; m.preparationPending = false;
    m.status = "PREPARING"; m.clock.reset(); m.probes.clear(); m.delivery = null;
    m.lastReport = m.lastSample = m.correctionAt = -1; m.correctionId = null; m.failures.clear();
  }
  private MediaWindow mediaWindow(Peer peer, int media)
  {
    MediaWindow window = peer.mediaWindow(media);
    if (window == null || window.availableEndMs() <= 0) throw new Fault("MEDIA_UNAVAILABLE");
    return window;
  }
  private void requireGrowingCapability(Peer peer, MediaWindow window)
  {
    if (window.growing() && !peer.capabilities().contains(GROWING_MEDIA))
      throw new Fault("GROWING_MEDIA_UNSUPPORTED");
  }
  private void refreshWindow(Party p, Peer peer)
  {
    MediaWindow window = mediaWindow(peer, p.media);
    if (!p.growing && window.growing()) throw new Fault("MEDIA_MISMATCH");
    if (window.availableEndMs() + 10000 < p.duration) throw new Fault("MEDIA_MISMATCH");
    requireGrowingCapability(peer, window);
    p.duration = window.growing() ? Math.max(p.duration, window.availableEndMs()) : window.availableEndMs();
    p.growing = window.growing();
  }
  private long playableEnd(Party p)
  { return p.growing ? Math.max(0, p.duration - liveEdgeDelayMs) : p.duration; }
  private long clamp(Party p, long position)
  { return Math.max(0, Math.min(playableEnd(p), position)); }
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
    return clamp(p, anchor + ("PLAYING".equals(state) ? Math.max(0, at - anchorAt) : 0));
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
      target = delta >= 0 ? target + Math.min(delta, playableEnd(p) - target)
          : target - Math.min(delta == Long.MIN_VALUE ? Long.MAX_VALUE : -delta, target);
    }
    return transition(p, m, action, now, target, state);
  }
  private JsonObject transition(Party p, Member m, String cause, long now, long target, String state)
  {
    if (p.pending != null)
    {
      CommandOutcome previous = p.outcomes.get(p.pending.get("commandSeq").getAsLong());
      if (previous != null && !previous.complete)
      {
        previous.complete = previous.superseded = true;
        broadcast(p, outcomeJson(p, previous, now, "SUPERSEDED"));
      }
    }
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
    CommandOutcome outcome = new CommandOutcome(out);
    for (Member member : p.members.values())
      if (commandRecipient(member, now)) outcome.participants.put(member.id, new Execution());
    if (p.outcomes.size() >= 16)
    {
      CommandOutcome oldest = p.outcomes.remove(p.outcomes.keySet().iterator().next());
      if (!oldest.complete)
      {
        oldest.complete = oldest.timedOut = true;
        broadcast(p, outcomeJson(p, oldest, now, "EVICTED"));
      }
    }
    p.outcomes.put(p.seq, outcome);
    for (Member member : p.members.values())
    {
      member.correctionId = null;
      if (member.peer != null) member.peer.cancelScheduled();
    }
    diagnostic(p, m, "COMMAND_" + cause + "_DEADLINE_" + out.get("executeAtServerMonoMs").getAsLong(), now);
    for (Member member : p.members.values())
      if (commandRecipient(member, now))
      {
        member.peer.schedule(copy(out));
        member.peer.send(copy(out));
      }
    return out;
  }
  private boolean commandRecipient(Member m, long now)
  { return healthy(m, now) || m.peer != null && m.admitted && "BUFFERING".equals(m.status) && m.clock.confident(now); }
  private JsonObject commandAck(Party p, Member m, JsonObject r, long now)
  {
    long seq = number(r, "commandSeq", 1, p.seq);
    CommandOutcome outcome = p.outcomes.get(seq);
    if (outcome == null) throw new Fault("COMMAND_GONE");
    if (number(r, "stateVersion", 0, MAX_TIME) != outcome.command.get("stateVersion").getAsLong())
      throw new Fault("STALE_STATE");
    Execution result = outcome.participants.get(m.id);
    if (result == null) throw new Fault("NOT_COMMAND_RECIPIENT");
    if (outcome.complete) throw new Fault("OUTCOME_CLOSED");
    String stage = text(r, "stage");
    if (!Set.of("RECEIVED", "EXECUTED").contains(stage)) throw new Fault("INVALID_REQUEST");
    if ("EXECUTED".equals(stage))
    {
      if (result.executedAt >= 0) throw new Fault("OUTCOME_ALREADY_REPORTED");
      boolean success = flag(r, "success");
      long landed = number(r, "positionMs", 0, p.duration);
      String state = text(r, "state");
      if (!Set.of("PLAYING", "PAUSED", "BUFFERING", "SEEKING").contains(state))
        throw new Fault("INVALID_REQUEST");
      long sample = sampleTime(m, r, now);
      long deadline = outcome.command.get("executeAtServerMonoMs").getAsLong();
      if (now < deadline || sample + m.clock.uncertainty(now) < deadline)
        throw new Fault("EXECUTION_PENDING");
      long target = outcome.command.get("targetPositionMs").getAsLong();
      String targetState = outcome.command.get("targetState").getAsString();
      if ("PLAYING".equals(targetState)) target += Math.max(0, sample - deadline);
      result.position = landed;
      result.skew = landed - Math.min(p.duration, target);
      result.state = state;
      result.success = success && targetState.equals(state) && Math.abs(result.skew) <= deadband(m, now);
      result.uncertainty = m.clock.uncertainty(now);
      result.executedAt = sample;
    }
    if (result.receivedAt < 0) result.receivedAt = now;
    m.ackSeq = Math.max(m.ackSeq, seq);
    outcome.complete = outcome.participants.values().stream().allMatch(e -> e.executedAt >= 0);
    JsonObject report = outcomeJson(p, outcome, now, outcome.complete ? "COMPLETE" : "PENDING");
    broadcast(p, report);
    JsonObject ack = envelope("ACK");
    ack.addProperty("commandSeq", seq);
    ack.addProperty("stage", stage);
    return ack;
  }
  private JsonObject outcomeJson(Party p, CommandOutcome outcome, long now, String status)
  {
    JsonObject out = envelope("COMMAND_OUTCOME");
    out.addProperty("partyId", p.id);
    out.add("commandSeq", outcome.command.get("commandSeq"));
    out.add("stateVersion", outcome.command.get("stateVersion"));
    out.add("executeAtServerMonoMs", outcome.command.get("executeAtServerMonoMs"));
    out.addProperty("reportDeadlineServerMonoMs", outcome.command.get("executeAtServerMonoMs").getAsLong() + 10000);
    out.addProperty("outcome", status);
    int received = 0, executed = 0, succeeded = 0;
    long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
    double uncertainty = 0;
    JsonArray participants = new JsonArray();
    for (Map.Entry<String, Execution> entry : outcome.participants.entrySet())
    {
      Execution e = entry.getValue();
      JsonObject item = new JsonObject();
      item.addProperty("participantId", entry.getKey());
      if (e.receivedAt >= 0) { received++; item.addProperty("receivedAtServerMonoMs", e.receivedAt); }
      if (e.executedAt >= 0)
      {
        executed++; if (e.success) succeeded++;
        min = Math.min(min, e.executedAt); max = Math.max(max, e.executedAt);
        uncertainty = Math.max(uncertainty, e.uncertainty);
        item.addProperty("executedAtServerMonoMs", e.executedAt);
        item.addProperty("positionMs", e.position); item.addProperty("state", e.state);
        item.addProperty("success", e.success); item.addProperty("skewMs", e.skew);
        item.addProperty("uncertaintyMs", e.uncertainty);
      }
      participants.add(item);
    }
    out.addProperty("expectedCount", outcome.participants.size());
    out.addProperty("receivedCount", received); out.addProperty("executedCount", executed);
    out.addProperty("successCount", succeeded); out.addProperty("failedCount", executed - succeeded);
    out.addProperty("missingCount", outcome.participants.size() - executed);
    if (executed >= 2)
    {
      out.addProperty("executionSpreadMs", max - min);
      out.addProperty("spreadUncertaintyMs", 2 * uncertainty);
    }
    out.add("participants", participants);
    out.addProperty("serverMonoMs", now);
    return out;
  }
  private void bufferPolicy(Party p, long now)
  {
    int active = 0, buffering = 0;
    Member origin = null;
    for (Member member : p.members.values())
      if (member.peer != null && member.admitted && Set.of("READY", "PLAYING", "PAUSED", "BUFFERING").contains(member.status))
      {
        active++;
        if ("BUFFERING".equals(member.status)) { buffering++; origin = member; }
      }
    boolean majority = active > 0 && buffering * 2 > active;
    if (!majority) p.majorityNotified = false;
    if (buffering == 0) return;
    boolean pendingPlay = p.pending != null && "PLAYING".equals(p.pending.get("targetState").getAsString());
    if (p.pauseOnBuffer && ("PLAYING".equals(p.state) || pendingPlay))
    {
      if (p.pending != null && "PAUSED".equals(p.pending.get("targetState").getAsString())) return;
      transition(p, origin, "PAUSE", now, position(p, now + lead(p, now)), "PAUSED");
    }
    else if (!p.pauseOnBuffer && majority && !p.majorityNotified
        && (p.lastBufferNotice < 0 || now - p.lastBufferNotice >= 2000))
    {
      p.majorityNotified = true; p.lastBufferNotice = now;
      JsonObject notice = envelope("BUFFER_RECOMMENDATION");
      notice.addProperty("partyId", p.id);
      notice.addProperty("stateVersion", p.version); notice.addProperty("commandSeq", p.seq);
      notice.addProperty("policy", "KEEP_MOVING"); notice.addProperty("recommendation", "PAUSE_OR_REDUCE_QUALITY");
      notice.addProperty("bufferingCount", buffering); notice.addProperty("activeCount", active);
      notice.addProperty("hostParticipantId", p.host); notice.addProperty("serverMonoMs", now);
      broadcast(p, notice);
    }
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
    refreshWindow(p, m.peer);
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
    addMediaWindow(ack, p);
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
    o.addProperty("budgetKbps", d.bitrateKbps); o.addProperty("transcode", d.transcode);
    if (d.route != null) o.addProperty("route", d.route);
    if (d.xcodeMode != null) o.addProperty("xcodeMode", d.xcodeMode);
    if (d.container != null) o.addProperty("container", d.container);
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
    out.addProperty("prepareVia", m.peer.capabilities().contains("PARTY_PREPARE_V1") ? "PARTY_PREPARE" : "NORMAL_NG_WATCH");
    if (m.reservation != null) out.add("preparation", deliveryJson(m.reservation));
    out.add("snapshot", snapshot(p, now));
    if (token)
    {
      PartyJoinCodes.Grant code = joinCodes.current(p.id, now);
      out.addProperty("joinCode", code.code());
      out.addProperty("expiresAtServerMonoMs", code.expiresAt());
    }
    return out;
  }
  private JsonObject snapshot(Party p, long now)
  {
    JsonObject o = envelope("SNAPSHOT");
    o.addProperty("partyId", p.id); o.addProperty("serverEpoch", epoch);
    o.addProperty("mediaFileId", p.media); o.addProperty("durationMs", p.duration);
    addMediaWindow(o, p);
    o.addProperty("stateVersion", p.version); o.addProperty("commandSeq", p.seq);
    o.addProperty("anchorPositionMs", p.anchor); o.addProperty("anchorServerMonoMs", p.anchorAt);
    o.addProperty("state", p.state); o.addProperty("rate", 1);
    o.addProperty("serverMonoMs", now); o.addProperty("hostParticipantId", p.host);
    o.addProperty("hostOnly", p.hostOnly); o.addProperty("pauseOnBuffer", p.pauseOnBuffer);
    if (p.pending != null) o.add("pendingCommand", copy(p.pending));
    if (!p.outcomes.isEmpty())
    {
      CommandOutcome latest = null;
      for (CommandOutcome outcome : p.outcomes.values()) latest = outcome;
      o.add("latestCommandOutcome", outcomeJson(p, latest, now,
          latest.superseded ? "SUPERSEDED" : latest.timedOut ? "TIMED_OUT" : latest.complete ? "COMPLETE" : "PENDING"));
    }
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
  private void addMediaWindow(JsonObject out, Party p)
  {
    out.addProperty("mediaGrowing", p.growing);
    out.addProperty("availableEndMs", p.duration);
    out.addProperty("playableEndMs", playableEnd(p));
    out.addProperty("liveEdgeDelayMs", p.growing ? liveEdgeDelayMs : 0);
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
  {
    if ("COMMAND_OUTCOME".equals(out.get("type").getAsString()))
      System.out.println("PartyMode party=" + p.id + " seq=" + out.get("commandSeq").getAsLong()
          + " outcome=" + out.get("outcome").getAsString()
          + " expectedCount=" + out.get("expectedCount").getAsInt()
          + " receivedCount=" + out.get("receivedCount").getAsInt()
          + " executedCount=" + out.get("executedCount").getAsInt()
          + " failedCount=" + out.get("failedCount").getAsInt()
          + " executionSpreadMs=" + (out.has("executionSpreadMs") ? out.get("executionSpreadMs").getAsLong() : -1));
    for (Member m : p.members.values()) if (m.peer != null) m.peer.send(copy(out));
  }

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
    peer.restoreSurface();
    peer.cancelPreparation();
    links.remove(peer);
    Party p = find(peer);
    if (p == null) return;
    Member m = requireMember(p, peer);
    peer.cancelScheduled();
    m.peer = null; m.disconnectedAt = time.getAsLong(); m.clock.reset();
    m.preparationPending = false; m.preparationId = null;
    m.selectionPending = false; m.selectionId = null;
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
      m.peer.cancelPreparation();
      m.peer.restoreSurface();
      if (commit && m.admitted) m.peer.commit(p.media, position(p, now));
      m.peer.progressOwner(false);
    }
    p.members.remove(m.id);
    m.status = "LEFT"; roster(p, m);
    if (p.members.isEmpty()) { parties.remove(p.id); joinCodes.remove(p.id, now); }
    else if (m.id.equals(p.host)) transfer(p);
  }
  private void end(Party p, boolean commit, String reason, long now)
  {
    JsonObject o = envelope("ENDED");
    o.addProperty("partyId", p.id); o.addProperty("reason", reason);
    broadcast(p, o);
    for (Member m : new ArrayList<>(p.members.values())) remove(p, m, commit, now);
    parties.remove(p.id); joinCodes.remove(p.id, now);
  }

  /** Advances deadlines, invalidates stale clocks, expires grace slots and abandoned parties. */
  public synchronized void tick()
  {
    long now = time.getAsLong();
    for (Peer peer : new ArrayList<>(links.keySet())) peer.maintenance();
    for (Party p : new ArrayList<>(parties.values()))
    {
      boolean wasGrowing = p.growing;
      for (Member member : p.members.values())
        if (member.peer != null)
        {
          try { refreshWindow(p, member.peer); }
          catch (Fault failure) { diagnostic(p, null, "MEDIA_WINDOW_REFRESH_" + failure.getMessage(), now); }
          break;
        }
      if (wasGrowing && !p.growing) broadcast(p, snapshot(p, now));
      if (p.pending != null && p.pending.get("executeAtServerMonoMs").getAsLong() <= now)
      {
        p.anchor = p.pending.get("targetPositionMs").getAsLong();
        p.anchorAt = p.pending.get("executeAtServerMonoMs").getAsLong();
        p.state = p.pending.get("targetState").getAsString();
        p.pending = null;
      }
      bufferPolicy(p, now);
      for (CommandOutcome outcome : p.outcomes.values())
        if (!outcome.complete && now >= outcome.command.get("executeAtServerMonoMs").getAsLong() + 10000)
        {
          outcome.complete = outcome.timedOut = true;
          broadcast(p, outcomeJson(p, outcome, now, "TIMED_OUT"));
        }
      for (Member m : new ArrayList<>(p.members.values()))
      {
        if (m.peer != null && m.admitted && Set.of("READY", "PLAYING", "PAUSED", "BUFFERING").contains(m.status)
            && !m.peer.mediaAdmitted(p.media))
        {
          m.peer.cancelScheduled();
          m.status = "RECOVERING"; m.delivery = null; m.correctionId = null;
          JsonObject error = envelope("ERROR");
          error.addProperty("code", "MEDIA_ADMISSION_UNCONFIRMED");
          m.peer.send(error);
          roster(p, m);
        }
        if (m.peer == null && now - m.disconnectedAt > GRACE_MS) { remove(p, m, false, now); continue; }
        if (("PREPARING".equals(m.status) || m.preparationPending || m.selectionPending) && now - m.preparingSince > 60000)
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
    parties.clear(); links.clear(); joinCodes.clear(); joinAttempts.clear(); codeIssues.clear(); closed = true;
  }

  /** Reports one bounded asynchronous ordinary-Watch outcome to its exact active operation only. */
  public synchronized void preparationResult(Peer peer, String preparationId, int watchCode, long generation)
  {
    Party p = find(peer);
    if (p == null || !eligible(peer)) return;
    Member m = requireMember(p, peer);
    if (!m.preparationPending || !preparationId.equals(m.preparationId)) return;
    if (watchCode == 0 && generation < 0) watchCode = -13;
    if (watchCode == 0 && m.selectionId != null && !peer.surfaceSelected(m.selectionId, m.pathGeneration)) watchCode = -13;
    Delivery loaded = null;
    if (watchCode == 0 && m.selectionId != null)
    {
      try { loaded = peer.preparedDelivery(p.media); }
      catch (Fault f) { watchCode = -13; }
    }
    m.surfacePrepared = watchCode == 0;
    m.preparationPending = false;
    JsonObject result = envelope("PREPARATION_RESULT");
    result.addProperty("partyId", p.id); result.addProperty("mediaFileId", p.media);
    result.addProperty("preparationId", preparationId);
    result.addProperty("status", watchCode == 0 ? "LOADED" : "FAILED");
    result.addProperty("watchCode", watchCode);
    if (m.selectionId != null)
    { result.addProperty("selectionId", m.selectionId); result.addProperty("pathGeneration", m.pathGeneration); }
    if (watchCode == 0 && loaded != null) result.add("delivery", deliveryJson(loaded));
    if (watchCode == 0) result.addProperty("playbackGeneration", generation);
    else { result.addProperty("code", "PREPARATION_FAILED"); peer.cancelPreparation(); }
    peer.send(result);
  }

  /** Commits one queried surface candidate under membership, policy budget and exact lifecycle fences. */
  public synchronized void surfaceSelectionResult(Peer peer, String selectionId, Delivery plan, String failure)
  {
    Party p = find(peer);
    if (p == null || !eligible(peer)) return;
    Member m = requireMember(p, peer);
    if (!m.selectionPending || !selectionId.equals(m.selectionId)) return;
    JsonObject result = envelope("SURFACE_SELECTION_RESULT");
    result.addProperty("partyId", p.id); result.addProperty("selectionId", selectionId);
    try
    {
      if (failure != null) throw new Fault(failure);
      admit(m, plan);
      m.pathGeneration = peer.applySurface(selectionId);
      m.surfacePrepared = false;
      m.reservation = plan;
      result.addProperty("status", "SELECTED");
      result.addProperty("pathGeneration", m.pathGeneration);
      result.addProperty("requiresPrepare", true);
      result.add("preparation", deliveryJson(plan));
    }
    catch (Fault f)
    {
      peer.restoreSurface(); m.selectionId = null;
      result.addProperty("status", "FAILED"); result.addProperty("code", f.getMessage());
    }
    m.selectionPending = false;
    peer.send(result);
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
