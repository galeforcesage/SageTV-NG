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
 * Lightweight server-authoritative Party coordinator.
 *
 * <p>Party Mode owns room membership, a canonical media timeline, monotonic
 * command deadlines and per-member clock models. Existing SageTV Watch,
 * VideoFrame and MiniPlayer paths continue to own source lookup, playback
 * preparation and media delivery.</p>
 */
public final class PartyService
{
  /** Maximum time allowed for the existing Watch path to prepare Party media. */
  public static final long PREPARATION_TIMEOUT_MS = 15000;
  static final long PARTY_LIFETIME_MS = 6 * 3600000L;
  static final long RECONNECT_GRACE_MS = 90000;
  static final long HEARTBEAT_STALE_MS = 15000;
  private static final long MIN_DRIFT_MS = 100;
  private static final long SEVERE_DRIFT_MS = 750;
  private static final long CORRECTION_COOLDOWN_MS = 10000;
  private static final long MAX_TIME = 9007199254740991L;

  /** Authenticated Party transport plus one renderer's ordinary playback adapter. */
  public interface Peer extends PartyPlayerAdapter
  {
    /** Returns the server-verified account or trusted LAN identity. */
    String principal();

    /** Returns explicitly negotiated Party guarantees for this renderer. */
    Set<String> capabilities();

    /** Returns the server-owned MiniClient locality decision. */
    default boolean localConnection() { return true; }

    /** Queues one bounded message to this viewer only. */
    void send(JsonObject message);

    /** Performs bounded nonblocking transport maintenance. */
    default void maintenance() {}
  }

  /** Current playable source extent for fixed or actively growing media. */
  public record MediaWindow(long availableEndMs, boolean growing) {}

  /** Server-selected playback metadata reported for diagnostics and adapters. */
  public record Delivery(String surface, String mode, String videoCodec, String audioCodec,
      int bitrateKbps, int selectedBitrateKbps, boolean transcode,
      String route, String xcodeMode, String container)
  {
    /** Creates delivery metadata without optional conditioned-path details. */
    public Delivery(String surface, String mode, String videoCodec, String audioCodec,
        int bitrateKbps, boolean transcode)
    {
      this(surface, mode, videoCodec, audioCodec, bitrateKbps,
          Math.max(1, bitrateKbps / 2), transcode, null, null, null);
    }

    /** Creates delivery metadata with conditioned-path details. */
    public Delivery(String surface, String mode, String videoCodec, String audioCodec,
        int bitrateKbps, boolean transcode, String route, String xcodeMode, String container)
    {
      this(surface, mode, videoCodec, audioCodec, bitrateKbps,
          Math.max(1, bitrateKbps / 2), transcode, route, xcodeMode, container);
    }
  }

  private static final class Link
  {
    final String handle;
    String token;
    long tokenExpires;
    final LinkedHashMap<String, Cached> replies = new LinkedHashMap<>();
    final Deque<Long> joinAttempts = new ArrayDeque<>();
    long requestWindow;
    long highWater;
    int requests;

    Link(String handle) { this.handle = handle; }
  }

  private record Cached(String input, JsonObject result) {}

  private static final class Member
  {
    final String id;
    final String principal;
    final PartyClock clock = new PartyClock();
    final Map<String, Long> probes = new LinkedHashMap<>();
    String recoveryToken;
    long recoveryExpires;
    Peer peer;
    String status = "PREPARING";
    boolean prepared;
    String preparationId;
    long preparingSince = -1;
    long disconnectedAt = -1;
    long lastHeartbeat = -1;
    long lastSample = -1;
    long lastPosition;
    long bufferAhead;
    long ackCommandSeq;
    long lastCorrectionAt = -1;
    int driftViolations;

    Member(String id, String recoveryToken, Peer peer)
    {
      this.id = id;
      this.recoveryToken = recoveryToken;
      this.peer = peer;
      principal = peer.principal();
    }
  }

  private static final class Party extends PartySession
  {
    final LinkedHashMap<String, Member> members = new LinkedHashMap<>();

    Party(String id, int media, MediaWindow window, long liveEdgeDelayMs, long now)
    {
      super(id, media, window.availableEndMs(), window.growing(), liveEdgeDelayMs, now);
    }
  }

  private final LongSupplier time;
  private final boolean enabled;
  private final long liveEdgeDelayMs;
  private final SecureRandom random = new SecureRandom();
  private final PartyJoinCodes joinCodes = new PartyJoinCodes();
  private final PartySessionCoordinator coordinator = new PartySessionCoordinator(300, 2500);
  private final Map<String, Party> parties = new LinkedHashMap<>();
  private final Map<Peer, Link> links = new HashMap<>();
  private final Deque<Long> joinAttempts = new ArrayDeque<>();
  private final Deque<Long> codeIssues = new ArrayDeque<>();
  private final String epoch = opaque();
  private boolean closed;

  /**
   * Creates an isolated Party service.
   *
   * @param monotonicMs server-monotonic clock
   * @param enabled whether Party requests are enabled
   */
  public PartyService(LongSupplier monotonicMs, boolean enabled)
  {
    this(monotonicMs, enabled, 3000);
  }

  /**
   * Creates an isolated Party service with a growing-media live-edge delay.
   *
   * @param monotonicMs server-monotonic clock
   * @param enabled whether Party requests are enabled
   * @param liveEdgeDelayMs safety distance from a growing media edge
   */
  public PartyService(LongSupplier monotonicMs, boolean enabled, long liveEdgeDelayMs)
  {
    time = monotonicMs;
    this.enabled = enabled;
    this.liveEdgeDelayMs = Math.max(1500, liveEdgeDelayMs);
  }

  /** Returns whether the renderer is authorized and implements the replacement contract. */
  public synchronized boolean eligible(Peer peer)
  {
    return enabled && !closed && peer.principal() != null && !peer.principal().isEmpty()
        && peer.capabilities().containsAll(REQUIRED);
  }

  /** Registers an authorized renderer for invitation discovery. */
  public synchronized void connected(Peer peer)
  {
    if (eligible(peer)) links.computeIfAbsent(peer, ignored -> new Link(opaque()));
  }

  /** Returns replacement-contract availability for one admitted renderer. */
  public synchronized JsonObject availability(Peer peer)
  {
    if (!eligible(peer)) throw new Fault("NOT_AVAILABLE");
    connected(peer);
    Link link = links.get(peer);
    long now = time.getAsLong();
    if (link.token == null || link.tokenExpires - now < 60000)
    {
      link.token = opaque();
      link.tokenExpires = now + 1800000;
    }
    JsonObject out = envelope("AVAILABLE");
    out.addProperty("contract", "PARTY_V1_COORDINATOR");
    out.addProperty("serverEpoch", epoch);
    out.addProperty("sessionHandle", link.handle);
    out.addProperty("sessionToken", link.token);
    out.addProperty("sessionTokenExpiresAtServerMonoMs", link.tokenExpires);
    out.addProperty("maxParticipants", 5);
    out.addProperty("joinCodeDigits", 3);
    out.addProperty("reconnectGraceMs", RECONNECT_GRACE_MS);
    out.addProperty("playbackPath", "SAGETV_WATCH");
    out.addProperty("sourceResolution", "WIZARD_MEDIAFILE");
    Party party = find(peer);
    if (party != null)
    {
      Member member = requireMember(party, peer);
      member.recoveryExpires = now + 1800000;
      out.addProperty("recoveryToken", member.recoveryToken);
      out.addProperty("recoveryTokenExpiresAtServerMonoMs", member.recoveryExpires);
    }
    return out;
  }

  /** Handles one bounded authenticated Party envelope. */
  public synchronized void receive(Peer peer, String json)
  {
    JsonObject request = null;
    JsonObject response;
    Link link = null;
    String requestId = null;
    try
    {
      if (!eligible(peer)) throw new Fault("NOT_AVAILABLE");
      connected(peer);
      link = links.get(peer);
      long now = time.getAsLong();
      if (now - link.requestWindow >= 1000)
      {
        link.requestWindow = now;
        link.requests = 0;
      }
      if (++link.requests > 30) throw new Fault("RATE_LIMITED");
      request = parse(json);
      requestId = text(request, "requestId");
      if (!"HELLO".equals(text(request, "type")))
      {
        if (!epoch.equals(text(request, "serverEpoch"))) throw new Fault("SERVER_EPOCH_CHANGED");
        if (link.token == null || link.tokenExpires <= now) throw new Fault("SESSION_EXPIRED");
        if (!constantEquals(link.token, text(request, "sessionToken")))
          throw new Fault("SESSION_DENIED");
      }
      Cached cached = link.replies.get(requestId);
      if (cached != null)
      {
        if (!cached.input().equals(request.toString())) throw new Fault("REQUEST_ID_REUSED");
        peer.send(copy(cached.result()));
        return;
      }
      long requestSeq = number(request, "requestSeq", 1, MAX_TIME);
      if (requestSeq <= link.highWater) throw new Fault("STALE_REQUEST");
      link.highWater = requestSeq;
      tick();
      response = dispatch(peer, request, now);
      response.addProperty("requestId", requestId);
      response.addProperty("serverMonoMs", now);
      diagnostic(peer, request,
          "REQUEST_" + text(request, "type") + "_" + response.get("type").getAsString(), now);
    }
    catch (Fault failure)
    {
      response = envelope("ERROR");
      response.addProperty("code", failure.getMessage());
      if ("JOIN_RATE_LIMITED".equals(failure.getMessage()))
        response.addProperty("retryAfterMs", joinRetryAfter(link, time.getAsLong()));
      if ("CODE_ROTATION_COOLDOWN".equals(failure.getMessage())
          && request != null && request.has("partyId"))
        response.addProperty("retryAfterMs",
            joinCodes.rotationRetryAfter(text(request, "partyId"), time.getAsLong()));
      if ("CODE_ISSUE_RATE_LIMITED".equals(failure.getMessage()))
        response.addProperty("retryAfterMs", codeIssueRetryAfter(time.getAsLong()));
      if (request != null) response.addProperty("requestId", text(request, "requestId"));
      diagnostic(peer, request, "REJECT_" + failure.getMessage(), time.getAsLong());
    }
    if (requestId != null && link != null && !link.replies.containsKey(requestId))
    {
      link.replies.put(requestId, new Cached(request.toString(), copy(response)));
      if (link.replies.size() > 128)
        link.replies.remove(link.replies.keySet().iterator().next());
    }
    peer.send(response);
  }

  private JsonObject dispatch(Peer peer, JsonObject request, long now)
  {
    String type = text(request, "type");
    if ("HELLO".equals(type)) return availability(peer);
    if ("LIST_CLIENTS".equals(type)) return clients();
    if ("CREATE".equals(type)) return create(peer, request, now);

    String partyId;
    if ("JOIN".equals(type))
    {
      if (find(peer) != null) throw new Fault("ALREADY_JOINED");
      attemptJoin(links.get(peer), now);
      partyId = joinCodes.resolve(text(request, "joinCode"), now);
      if (request.has("partyId") && !partyId.equals(text(request, "partyId")))
        throw new Fault("JOIN_DENIED");
    }
    else partyId = text(request, "partyId");

    Party party = parties.get(partyId);
    if (party == null) throw new Fault("PARTY_GONE");
    if ("JOIN".equals(type)) return join(party, peer, request, now);
    if ("RESUME".equals(type)) return resume(party, peer, request, now);

    Member member = requireMember(party, peer);
    return switch (type)
    {
      case "SNAPSHOT" -> snapshot(party, now);
      case "INVITE" -> invite(party, member, request);
      case "JOIN_CODE", "ROTATE_CODE" -> joinCode(party, member, type, now);
      case "CLOCK" -> clockProbe(member, now);
      case "CLOCK_REPLY" -> clockReply(party, member, request, now);
      case "PREPARE" -> prepare(party, member, now);
      case "READY" -> ready(party, member, request, now);
      case "ACTION" -> action(party, member, request, now);
      case "ACK" -> commandResult(party, member, request);
      case "HEARTBEAT" -> heartbeat(party, member, request, now);
      case "LEAVE" ->
      {
        remove(party, member, true, now);
        yield envelope("LEFT");
      }
      case "END" ->
      {
        requireHost(party, member);
        end(party, true, "HOST_ENDED", now);
        yield envelope("ENDED");
      }
      default -> throw new Fault("UNKNOWN_TYPE");
    };
  }

  private JsonObject create(Peer peer, JsonObject request, long now)
  {
    if (find(peer) != null) throw new Fault("ALREADY_JOINED");
    if (parties.size() >= 16) throw new Fault("SERVER_CAPACITY");
    int media = request.has("mediaFileId")
        ? (int)number(request, "mediaFileId", 1, Integer.MAX_VALUE)
        : peer.currentMediaFileId();
    String startMode = request.has("startMode") ? text(request, "startMode") : "BEGINNING";
    if (!Set.of("BEGINNING", "CURRENT_POSITION").contains(startMode))
      throw new Fault("INVALID_REQUEST");
    MediaWindow window = mediaWindow(peer, media);
    requireGrowingCapability(peer, window);
    Party party = new Party(opaque(), media, window, liveEdgeDelayMs, now);
    if ("CURRENT_POSITION".equals(startMode))
      party.timeline.setInitialPosition(peer.currentPosition(media));
    Member host = member(peer, now);
    party.members.put(host.id, host);
    party.host = host.id;
    parties.put(party.id, party);
    issueCode(party, now);
    peer.progressOwner(true);
    JsonObject out = joined(party, host, "CREATED", now, true);
    out.addProperty("startMode", startMode);
    out.addProperty("startPositionMs", party.timeline.anchorPositionMs());
    return out;
  }

  private JsonObject join(Party party, Peer peer, JsonObject request, long now)
  {
    if (activeMembers(party) >= 5) throw new Fault("PARTY_FULL");
    if (request.has("mediaFileId")
        && number(request, "mediaFileId", 1, Integer.MAX_VALUE) != party.media)
      throw new Fault("MEDIA_MISMATCH");
    refreshWindow(party, peer);
    Member member = member(peer, now);
    party.members.put(member.id, member);
    peer.progressOwner(true);
    roster(party, member);
    return joined(party, member, "JOINED", now, false);
  }

  private JsonObject resume(Party party, Peer peer, JsonObject request, long now)
  {
    if (find(peer) != null) throw new Fault("ALREADY_JOINED");
    Member member = party.members.get(text(request, "participantId"));
    if (member == null || member.peer != null || member.disconnectedAt < 0
        || now - member.disconnectedAt > RECONNECT_GRACE_MS
        || member.recoveryExpires <= now
        || !member.principal.equals(peer.principal())
        || !constantEquals(member.recoveryToken, text(request, "recoveryToken")))
      throw new Fault("RESUME_DENIED");
    member.peer = peer;
    member.status = "PREPARING";
    member.prepared = false;
    member.preparationId = null;
    member.preparingSince = now;
    member.disconnectedAt = -1;
    member.lastHeartbeat = -1;
    member.lastSample = -1;
    member.clock.reset();
    member.probes.clear();
    peer.progressOwner(true);
    if (party.host == null) party.host = member.id;
    roster(party, member);
    return joined(party, member, "JOINED", now, false);
  }

  private JsonObject clients()
  {
    JsonObject out = envelope("CLIENTS");
    JsonArray clients = new JsonArray();
    for (Map.Entry<Peer, Link> entry : links.entrySet())
      if (eligible(entry.getKey())) clients.add(entry.getValue().handle);
    out.add("sessionHandles", clients);
    return out;
  }

  private JsonObject invite(Party party, Member member, JsonObject request)
  {
    requireHost(party, member);
    String target = text(request, "sessionHandle");
    for (Map.Entry<Peer, Link> entry : links.entrySet())
      if (entry.getValue().handle.equals(target) && eligible(entry.getKey()))
      {
        JsonObject invite = envelope("INVITED");
        invite.addProperty("partyId", party.id);
        invite.addProperty("mediaFileId", party.media);
        invite.addProperty("joinCodeRequired", true);
        entry.getKey().send(invite);
        return envelope("ACK");
      }
    throw new Fault("CLIENT_UNAVAILABLE");
  }

  private JsonObject joinCode(Party party, Member member, String type, long now)
  {
    requireHost(party, member);
    PartyJoinCodes.Grant grant = "ROTATE_CODE".equals(type)
        ? issueCode(party, now) : joinCodes.current(party.id, now);
    JsonObject out = envelope("JOIN_CODE");
    out.addProperty("partyId", party.id);
    out.addProperty("joinCode", grant.code());
    out.addProperty("expiresAtServerMonoMs", grant.expiresAt());
    return out;
  }

  private JsonObject prepare(Party party, Member member, long now)
  {
    refreshWindow(party, member.peer);
    if (member.preparationId != null) throw new Fault("PREPARATION_PENDING");
    member.peer.cancelScheduled();
    member.peer.cancelPreparation();
    member.status = "PREPARING";
    member.prepared = false;
    member.preparationId = opaque();
    member.preparingSince = now;
    try
    {
      member.peer.prepare(party.media, member.preparationId,
          party.timeline.positionAt(now));
    }
    catch (Fault failure)
    {
      member.preparationId = null;
      throw failure;
    }
    JsonObject out = envelope("PREPARE_ACCEPTED");
    out.addProperty("partyId", party.id);
    out.addProperty("mediaFileId", party.media);
    out.addProperty("preparationId", member.preparationId);
    out.addProperty("targetPositionMs", party.timeline.positionAt(now));
    out.addProperty("holdPlayback", true);
    return out;
  }

  private JsonObject ready(Party party, Member member, JsonObject request, long now)
  {
    requireCurrent(party, request);
    if (!member.prepared || member.preparationId != null) throw new Fault("NOT_PREPARED");
    if (!member.clock.confident(now)) throw new Fault("CLOCK_UNCERTAIN");
    if (!"PREPARING".equals(member.status))
    {
      member.status = "READY";
      member.lastHeartbeat = now;
      member.bufferAhead = request.has("bufferAheadMs")
          ? number(request, "bufferAheadMs", 0, 3600000) : 0;
      member.driftViolations = 0;
      roster(party, member);
      JsonObject out = envelope("READY_ACK");
      out.addProperty("partyId", party.id);
      out.addProperty("stateVersion", party.timeline.stateVersion());
      out.addProperty("commandSeq", party.timeline.commandSeq());
      return out;
    }
    Delivery delivery = member.peer.delivery(party.media);
    member.status = "READY";
    member.lastHeartbeat = now;
    member.bufferAhead = request.has("bufferAheadMs")
        ? number(request, "bufferAheadMs", 0, 3600000) : 0;
    member.driftViolations = 0;
    roster(party, member);
    JsonObject correction = correction(party, member, now, "INITIAL_ALIGNMENT");
    correction.add("delivery", deliveryJson(delivery));
    schedule(member, correction);
    return correction;
  }

  private JsonObject action(Party party, Member member, JsonObject request, long now)
  {
    requireCurrent(party, request);
    String action = text(request, "action");
    if (!Set.of("PLAY", "PAUSE", "SEEK", "FF", "REW", "FF2", "REW2", "COMSKIP")
        .contains(action))
      throw new Fault("INVALID_ACTION");
    if ("COMSKIP".equals(action)) requireHost(party, member);
    if (!canControl(party, member, now, "PAUSE".equals(action)))
      throw new Fault("NOT_READY");
    if (party.timeline.pending() != null) throw new Fault("TRANSITION_PENDING");

    long lead = lead(party, now);
    long target = party.timeline.positionAt(now + lead);
    String targetState = party.timeline.state();
    if ("PLAY".equals(action)) targetState = "PLAYING";
    else if ("PAUSE".equals(action)) targetState = "PAUSED";
    else if ("SEEK".equals(action))
      target = number(request, "targetPositionMs", 0, party.timeline.playableEndMs());
    else if (action.startsWith("FF") || action.startsWith("REW"))
      target = clamp(party, target + member.peer.skip(action));
    else if ("COMSKIP".equals(action))
    {
      long end = member.peer.commercialEnd(party.media, party.timeline.positionAt(now), false);
      if (end <= target) throw new Fault("COMMERCIAL_SKIP_UNAVAILABLE");
      target = clamp(party, end);
    }
    return transition(party, member, action, now, target, targetState);
  }

  private JsonObject commandResult(Party party, Member member, JsonObject request)
  {
    requireCurrent(party, request);
    long seq = number(request, "commandSeq", 1, party.timeline.commandSeq());
    String stage = text(request, "stage");
    if (!Set.of("RECEIVED", "EXECUTED").contains(stage)) throw new Fault("INVALID_REQUEST");
    member.ackCommandSeq = Math.max(member.ackCommandSeq, seq);
    if ("EXECUTED".equals(stage) && request.has("success") && !flag(request, "success"))
    {
      member.status = "LATE";
      roster(party, member);
    }
    JsonObject out = envelope("ACK");
    out.addProperty("commandSeq", seq);
    out.addProperty("stage", stage);
    return out;
  }

  private JsonObject heartbeat(Party party, Member member, JsonObject request, long now)
  {
    requireCurrent(party, request);
    if (!member.prepared) throw new Fault("NOT_PREPARED");
    String state = text(request, "state");
    if (!Set.of("PLAYING", "PAUSED", "BUFFERING", "STOPPED", "ERROR").contains(state))
      throw new Fault("INVALID_REQUEST");
    long sample = sampleTime(member, request, now);
    long position = number(request, "positionMs", 0, party.timeline.availableEndMs());
    member.lastHeartbeat = now;
    member.lastSample = sample;
    member.lastPosition = position;
    member.bufferAhead = request.has("bufferAheadMs")
        ? number(request, "bufferAheadMs", 0, 3600000) : 0;

    if ("STOPPED".equals(state))
    {
      member.status = "STOPPED";
      member.prepared = false;
      member.peer.cancelScheduled();
      member.peer.cancelPreparation();
      if (member.id.equals(party.host)) pauseForHostExit(party, member, now, true);
      roster(party, member);
    }
    else if ("BUFFERING".equals(state) || "ERROR".equals(state))
    {
      member.status = "LATE";
      member.driftViolations = 0;
      roster(party, member);
    }
    else
    {
      member.status = "ACTIVE";
      long skew = position - party.timeline.positionAt(sample);
      boolean wrongState = !state.equals(party.timeline.state());
      boolean severe = wrongState || Math.abs(skew) > SEVERE_DRIFT_MS;
      if (Math.abs(skew) < MIN_DRIFT_MS && !wrongState) member.driftViolations = 0;
      else if (severe || ++member.driftViolations >= 3)
      {
        member.driftViolations = 0;
        if (party.timeline.pending() == null
            && (member.lastCorrectionAt < 0
                || now - member.lastCorrectionAt >= CORRECTION_COOLDOWN_MS))
        {
          JsonObject correction = correction(party, member, now,
              severe ? "SEVERE_DRIFT" : "SUSTAINED_DRIFT");
          schedule(member, correction);
          member.peer.send(copy(correction));
        }
      }
    }

    JsonObject out = envelope("HEARTBEAT_ACK");
    out.addProperty("partyId", party.id);
    out.addProperty("stateVersion", party.timeline.stateVersion());
    out.addProperty("commandSeq", party.timeline.commandSeq());
    out.addProperty("desiredState", party.timeline.state());
    out.addProperty("desiredPositionMs", party.timeline.positionAt(now));
    out.addProperty("skewMs", position - party.timeline.positionAt(sample));
    return out;
  }

  private JsonObject transition(Party party, Member origin, String cause, long now,
      long targetPositionMs, String targetState)
  {
    List<PartySessionCoordinator.ParticipantTiming> timing = participantTiming(party, now);
    PartyCommand command = coordinator.schedule(party.timeline, cause,
        targetPositionMs, targetState, now, origin.id, timing);
    JsonObject out = commandJson(party.id, command);
    for (Member member : party.members.values())
    {
      if (member.peer != null) member.peer.cancelScheduled();
      if (commandRecipient(party, member, targetState, now))
      {
        schedule(member, out);
        member.peer.send(copy(out));
      }
    }
    diagnostic(party, origin,
        "COMMAND_" + cause + "_DEADLINE_" + command.executeAtServerMonoMs(), now);
    return out;
  }

  private JsonObject correction(Party party, Member member, long now, String reason)
  {
    long executeAt = now + participantLead(member, now);
    JsonObject out = envelope("CORRECTION");
    out.addProperty("partyId", party.id);
    out.addProperty("participantId", member.id);
    out.addProperty("stateVersion", party.timeline.stateVersion());
    out.addProperty("commandSeq", party.timeline.commandSeq());
    out.addProperty("reason", reason);
    out.addProperty("kind", "HARD");
    out.addProperty("targetPositionMs", party.timeline.positionAt(executeAt));
    out.addProperty("targetState", party.timeline.state());
    out.addProperty("targetRate", 1);
    out.addProperty("executeAtServerMonoMs", executeAt);
    member.lastCorrectionAt = now;
    return out;
  }

  private JsonObject clockProbe(Member member, long now)
  {
    if (member.probes.size() >= 8)
      member.probes.remove(member.probes.keySet().iterator().next());
    String id = opaque();
    member.probes.put(id, now);
    JsonObject out = envelope("CLOCK_PROBE");
    out.addProperty("probeId", id);
    out.addProperty("s0", now);
    return out;
  }

  private JsonObject clockReply(Party party, Member member, JsonObject request, long now)
  {
    String id = text(request, "probeId");
    Long s0 = member.probes.remove(id);
    if (s0 == null) throw new Fault("CLOCK_PROBE_GONE");
    member.clock.add(s0, number(request, "c1", 0, MAX_TIME),
        number(request, "c2", 0, MAX_TIME), now);
    JsonObject out = envelope("CLOCK_MODEL");
    out.addProperty("partyId", party.id);
    out.addProperty("confident", member.clock.confident(now));
    out.addProperty("serverMinusClientMs", member.clock.offset(now));
    out.addProperty("uncertaintyMs", member.clock.uncertainty(now));
    out.addProperty("p95RttMs", member.clock.p95(now));
    return out;
  }

  private long sampleTime(Member member, JsonObject request, long now)
  {
    if (!member.clock.confident(now)) throw new Fault("CLOCK_UNCERTAIN");
    long local = number(request, "sampleMonoMs", 0, MAX_TIME);
    long sample = Math.round(local + member.clock.offset(now));
    if (sample < now - 5000 || sample > now + member.clock.uncertainty(now))
      throw new Fault("STALE_SAMPLE");
    if (sample <= member.lastSample) throw new Fault("STALE_SAMPLE");
    return sample;
  }

  private boolean canControl(Party party, Member member, long now, boolean pause)
  {
    if (member.peer == null || !member.prepared) return false;
    if (pause) return !"STOPPED".equals(member.status);
    return playEligible(party, member, now);
  }

  private boolean commandRecipient(Party party, Member member, String targetState, long now)
  {
    if (member.peer == null || !member.prepared || "STOPPED".equals(member.status)) return false;
    if ("PAUSED".equals(targetState)) return true;
    return playEligible(party, member, now);
  }

  private boolean playEligible(Party party, Member member, long now)
  {
    if (!Set.of("READY", "ACTIVE").contains(member.status)
        || !member.clock.confident(now))
      return false;
    if (party != null && "PAUSED".equals(party.timeline.state())) return true;
    return member.lastHeartbeat >= 0
        && now - member.lastHeartbeat <= HEARTBEAT_STALE_MS;
  }

  private long lead(Party party, long now)
  {
    return coordinator.commandLead(participantTiming(party, now));
  }

  private List<PartySessionCoordinator.ParticipantTiming> participantTiming(Party party, long now)
  {
    List<PartySessionCoordinator.ParticipantTiming> timing = new ArrayList<>();
    for (Member member : party.members.values())
      timing.add(new PartySessionCoordinator.ParticipantTiming(
          commandRecipient(party, member, "PLAYING", now), member.clock.p95(now)));
    return timing;
  }

  private long participantLead(Member member, long now)
  {
    return Math.min(2500, Math.max(300, 2 * member.clock.p95(now) + 100));
  }

  private void schedule(Member member, JsonObject transition)
  {
    member.peer.schedule(new PartyPlaybackDirective(
        transition.get("stateVersion").getAsLong(),
        transition.get("commandSeq").getAsLong(),
        transition.has("kind") ? transition.get("kind").getAsString() : "COMMAND",
        transition.get("targetPositionMs").getAsLong(),
        transition.get("targetState").getAsString(),
        transition.get("targetRate").getAsDouble(),
        transition.get("executeAtServerMonoMs").getAsLong()));
  }

  private JsonObject commandJson(String partyId, PartyCommand command)
  {
    JsonObject out = envelope("COMMAND");
    out.addProperty("partyId", partyId);
    out.addProperty("stateVersion", command.stateVersion());
    out.addProperty("commandSeq", command.commandSeq());
    out.addProperty("cause", command.cause());
    out.addProperty("targetPositionMs", command.targetPositionMs());
    out.addProperty("targetState", command.targetState());
    out.addProperty("targetRate", command.targetRate());
    out.addProperty("executeAtServerMonoMs", command.executeAtServerMonoMs());
    out.addProperty("originParticipantId", command.originParticipantId());
    return out;
  }

  private JsonObject joined(Party party, Member member, String type, long now, boolean includeCode)
  {
    member.recoveryToken = opaque();
    member.recoveryExpires = now + 1800000;
    JsonObject out = envelope(type);
    out.addProperty("partyId", party.id);
    out.addProperty("participantId", member.id);
    out.addProperty("recoveryToken", member.recoveryToken);
    out.addProperty("recoveryTokenExpiresAtServerMonoMs", member.recoveryExpires);
    out.addProperty("reconnectGraceMs", RECONNECT_GRACE_MS);
    out.addProperty("prepareVia", "SAGETV_WATCH");
    out.add("snapshot", snapshot(party, now));
    if (includeCode)
    {
      PartyJoinCodes.Grant code = joinCodes.current(party.id, now);
      out.addProperty("joinCode", code.code());
      out.addProperty("expiresAtServerMonoMs", code.expiresAt());
    }
    return out;
  }

  private JsonObject snapshot(Party party, long now)
  {
    JsonObject out = envelope("SNAPSHOT");
    out.addProperty("partyId", party.id);
    out.addProperty("serverEpoch", epoch);
    out.addProperty("mediaFileId", party.media);
    out.addProperty("durationMs", party.timeline.availableEndMs());
    out.addProperty("mediaGrowing", party.timeline.growing());
    out.addProperty("availableEndMs", party.timeline.availableEndMs());
    out.addProperty("playableEndMs", party.timeline.playableEndMs());
    out.addProperty("liveEdgeDelayMs", party.timeline.growing() ? liveEdgeDelayMs : 0);
    out.addProperty("stateVersion", party.timeline.stateVersion());
    out.addProperty("commandSeq", party.timeline.commandSeq());
    out.addProperty("anchorPositionMs", party.timeline.anchorPositionMs());
    out.addProperty("anchorServerMonoMs", party.timeline.anchorServerMonoMs());
    out.addProperty("state", party.timeline.state());
    out.addProperty("rate", 1);
    out.addProperty("serverMonoMs", now);
    if (party.host != null) out.addProperty("hostParticipantId", party.host);
    if (party.timeline.pending() != null)
      out.add("pendingCommand", commandJson(party.id, party.timeline.pending()));
    JsonArray roster = new JsonArray();
    for (Member member : party.members.values())
    {
      JsonObject entry = new JsonObject();
      entry.addProperty("participantId", member.id);
      entry.addProperty("status", member.status);
      entry.addProperty("prepared", member.prepared);
      entry.addProperty("clockConfident", member.clock.confident(now));
      entry.addProperty("p95RttMs", member.clock.p95(now));
      entry.addProperty("ackCommandSeq", member.ackCommandSeq);
      roster.add(entry);
    }
    out.add("roster", roster);
    return out;
  }

  private JsonObject deliveryJson(Delivery delivery)
  {
    JsonObject out = new JsonObject();
    out.addProperty("surface", delivery.surface());
    out.addProperty("mode", delivery.mode());
    out.addProperty("videoCodec", delivery.videoCodec());
    out.addProperty("audioCodec", delivery.audioCodec());
    out.addProperty("bitrateKbps", delivery.bitrateKbps());
    out.addProperty("selectedBitrateKbps", delivery.selectedBitrateKbps());
    out.addProperty("transcode", delivery.transcode());
    if (delivery.route() != null) out.addProperty("route", delivery.route());
    if (delivery.xcodeMode() != null) out.addProperty("xcodeMode", delivery.xcodeMode());
    if (delivery.container() != null) out.addProperty("container", delivery.container());
    return out;
  }

  private void refreshWindow(Party party, Peer peer)
  {
    MediaWindow window = mediaWindow(peer, party.media);
    if (!party.timeline.growing() && window.growing()) throw new Fault("MEDIA_MISMATCH");
    if (window.availableEndMs() + 10000 < party.timeline.availableEndMs())
      throw new Fault("MEDIA_MISMATCH");
    requireGrowingCapability(peer, window);
    party.timeline.refreshWindow(window.availableEndMs(), window.growing());
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

  private long clamp(Party party, long position)
  {
    return Math.max(0, Math.min(party.timeline.playableEndMs(), position));
  }

  private Member member(Peer peer, long now)
  {
    Member member = new Member(opaque(), opaque(), peer);
    member.recoveryExpires = now + 1800000;
    member.preparingSince = now;
    return member;
  }

  private Party find(Peer peer)
  {
    for (Party party : parties.values())
      for (Member member : party.members.values())
        if (member.peer == peer) return party;
    return null;
  }

  private Member findMember(Party party, Peer peer)
  {
    if (party != null)
      for (Member member : party.members.values())
        if (member.peer == peer && member.principal.equals(peer.principal())) return member;
    return null;
  }

  private Member requireMember(Party party, Peer peer)
  {
    Member member = findMember(party, peer);
    if (member != null) return member;
    throw new Fault("NOT_MEMBER");
  }

  private void requireCurrent(Party party, JsonObject request)
  {
    if (number(request, "stateVersion", 0, MAX_TIME) != party.timeline.stateVersion())
      throw new Fault("STALE_STATE");
  }

  private void requireHost(Party party, Member member)
  {
    if (!member.id.equals(party.host)) throw new Fault("HOST_REQUIRED");
  }

  private int activeMembers(Party party)
  {
    int count = 0;
    for (Member member : party.members.values())
      if (member.peer != null || member.disconnectedAt >= 0) count++;
    return count;
  }

  private void roster(Party party, Member member)
  {
    JsonObject out = envelope("PARTICIPANT_STATE");
    out.addProperty("partyId", party.id);
    out.addProperty("participantId", member.id);
    out.addProperty("status", member.status);
    out.addProperty("prepared", member.prepared);
    broadcast(party, out);
  }

  private void broadcast(Party party, JsonObject message)
  {
    for (Member member : party.members.values())
      if (member.peer != null) member.peer.send(copy(message));
  }

  private void pauseForHostExit(Party party, Member host, long now, boolean sendCode)
  {
    PartyCommand pending = party.timeline.pending();
    if ("PLAYING".equals(party.timeline.state())
        || pending != null && "PLAYING".equals(pending.targetState()))
      transition(party, host, "HOST_EXIT_PAUSE", now,
          party.timeline.positionAt(now + lead(party, now)), "PAUSED");
    if (sendCode && host.peer != null)
    {
      PartyJoinCodes.Grant code = joinCodes.current(party.id, now);
      JsonObject notice = envelope("JOIN_CODE");
      notice.addProperty("partyId", party.id);
      notice.addProperty("joinCode", code.code());
      notice.addProperty("expiresAtServerMonoMs", code.expiresAt());
      host.peer.send(notice);
    }
    transfer(party, host);
  }

  private void transfer(Party party, Member excluded)
  {
    party.host = null;
    for (Member candidate : party.members.values())
      if (candidate != excluded && candidate.peer != null
          && !"STOPPED".equals(candidate.status))
      {
        party.host = candidate.id;
        break;
      }
    JsonObject out = envelope("HOST_CHANGED");
    out.addProperty("partyId", party.id);
    if (party.host != null) out.addProperty("hostParticipantId", party.host);
    broadcast(party, out);
  }

  private void remove(Party party, Member member, boolean commit, long now)
  {
    if (member.peer != null)
    {
      member.peer.cancelScheduled();
      member.peer.cancelPreparation();
      if (commit && member.prepared)
        member.peer.commit(party.media, party.timeline.positionAt(now));
      member.peer.progressOwner(false);
    }
    boolean wasHost = member.id.equals(party.host);
    party.members.remove(member.id);
    member.status = "LEFT";
    roster(party, member);
    if (party.members.isEmpty())
    {
      parties.remove(party.id);
      joinCodes.remove(party.id, now);
    }
    else if (wasHost) transfer(party, member);
  }

  private void end(Party party, boolean commit, String reason, long now)
  {
    JsonObject out = envelope("ENDED");
    out.addProperty("partyId", party.id);
    out.addProperty("reason", reason);
    broadcast(party, out);
    for (Member member : new ArrayList<>(party.members.values()))
      remove(party, member, commit, now);
    parties.remove(party.id);
    joinCodes.remove(party.id, now);
  }

  /** Routes existing SageTV playback controls into the authoritative timeline. */
  public synchronized boolean nativeAction(Peer peer, String action)
  {
    Party party = find(peer);
    if (party == null || "SEEK".equals(action)) return false;
    try
    {
      tick();
      JsonObject request = new JsonObject();
      request.addProperty("stateVersion", party.timeline.stateVersion());
      request.addProperty("action",
          "TOGGLE".equals(action)
              ? ("PLAYING".equals(party.timeline.state()) ? "PAUSE" : "PLAY")
              : action);
      action(party, requireMember(party, peer), request, time.getAsLong());
    }
    catch (Fault failure)
    {
      JsonObject error = envelope("ERROR");
      error.addProperty("code", failure.getMessage());
      peer.send(error);
    }
    return true;
  }

  /** Retains a logical participant briefly while releasing its physical connection. */
  public synchronized void disconnected(Peer peer)
  {
    links.remove(peer);
    Party party = find(peer);
    if (party == null) return;
    Member member = requireMember(party, peer);
    long now = time.getAsLong();
    if (member.id.equals(party.host)) pauseForHostExit(party, member, now, false);
    peer.cancelScheduled();
    peer.cancelPreparation();
    peer.progressOwner(false);
    member.peer = null;
    member.status = "DISCONNECTED";
    member.disconnectedAt = now;
    member.prepared = false;
    member.clock.reset();
    member.probes.clear();
    roster(party, member);
  }

  /** Advances pending timelines, preparation deadlines and reconnect expiry. */
  public synchronized void tick()
  {
    long now = time.getAsLong();
    for (Peer peer : new ArrayList<>(links.keySet())) peer.maintenance();
    for (Party party : new ArrayList<>(parties.values()))
    {
      party.timeline.advance(now);
      for (Member member : new ArrayList<>(party.members.values()))
      {
        if (member.peer == null && member.disconnectedAt >= 0
            && now - member.disconnectedAt > RECONNECT_GRACE_MS)
        {
          remove(party, member, false, now);
          continue;
        }
        if (member.preparationId != null
            && now - member.preparingSince > PREPARATION_TIMEOUT_MS)
        {
          JsonObject error = envelope("ERROR");
          error.addProperty("code", "PREPARATION_EXPIRED");
          if (member.peer != null) member.peer.send(error);
          member.preparationId = null;
          member.status = "LATE";
          roster(party, member);
        }
        if (member.peer != null && "PLAYING".equals(party.timeline.state())
            && "ACTIVE".equals(member.status)
            && member.lastHeartbeat >= 0
            && now - member.lastHeartbeat > HEARTBEAT_STALE_MS)
        {
          member.status = "LATE";
          roster(party, member);
        }
      }
      if (now - party.created > PARTY_LIFETIME_MS)
        end(party, false, "EXPIRED", now);
    }
  }

  /** Clears ephemeral Party authority without stopping ordinary playback. */
  public synchronized void shutdown()
  {
    for (Party party : new ArrayList<>(parties.values()))
      end(party, false, "SERVER_SHUTDOWN", time.getAsLong());
    parties.clear();
    links.clear();
    joinCodes.clear();
    joinAttempts.clear();
    codeIssues.clear();
    closed = true;
  }

  /** Completes one asynchronous ordinary-Watch preparation operation. */
  public synchronized void preparationResult(Peer peer, String preparationId,
      int watchCode, long generation)
  {
    Party party = find(peer);
    if (party == null || !eligible(peer)) return;
    Member member = requireMember(party, peer);
    if (member.preparationId == null || !member.preparationId.equals(preparationId)) return;
    member.preparationId = null;
    member.prepared = watchCode == 0 && generation >= 0;
    member.status = member.prepared ? "PREPARING" : "LATE";
    JsonObject out = envelope("PREPARATION_RESULT");
    out.addProperty("partyId", party.id);
    out.addProperty("mediaFileId", party.media);
    out.addProperty("preparationId", preparationId);
    out.addProperty("status", member.prepared ? "LOADED" : "FAILED");
    out.addProperty("watchCode", watchCode);
    if (member.prepared)
    {
      out.addProperty("playbackGeneration", generation);
      try { out.add("delivery", deliveryJson(peer.preparedDelivery(party.media))); }
      catch (Fault failure) { member.prepared = false; member.status = "LATE"; }
    }
    if (!member.prepared)
    {
      peer.cancelPreparation();
      out.addProperty("code", "PREPARATION_FAILED");
    }
    peer.send(out);
    roster(party, member);
  }

  private PartyJoinCodes.Grant issueCode(Party party, long now)
  {
    if (codeIssueRetryAfter(now) > 0) throw new Fault("CODE_ISSUE_RATE_LIMITED");
    PartyJoinCodes.Grant grant =
        joinCodes.issue(party.id, now, party.created + PARTY_LIFETIME_MS);
    codeIssues.addLast(now);
    return grant;
  }

  private long codeIssueRetryAfter(long now)
  {
    while (!codeIssues.isEmpty() && codeIssues.peekFirst() <= now - 60000)
      codeIssues.removeFirst();
    return codeIssues.size() >= 12 ? codeIssues.peekFirst() + 60000 - now : 0;
  }

  private void attemptJoin(Link link, long now)
  {
    if (joinRetryAfter(link, now) > 0) throw new Fault("JOIN_RATE_LIMITED");
    joinAttempts.addLast(now);
    link.joinAttempts.addLast(now);
  }

  private long joinRetryAfter(Link link, long now)
  {
    while (!joinAttempts.isEmpty() && joinAttempts.peekFirst() <= now - 60000)
      joinAttempts.removeFirst();
    if (link != null)
      while (!link.joinAttempts.isEmpty() && link.joinAttempts.peekFirst() <= now - 60000)
        link.joinAttempts.removeFirst();
    long global = joinAttempts.size() >= 6
        ? joinAttempts.peekFirst() + 60000 - now : 0;
    long local = link != null && link.joinAttempts.size() >= 3
        ? link.joinAttempts.peekFirst() + 60000 - now : 0;
    return Math.max(global, local);
  }

  private static boolean constantEquals(String first, String second)
  {
    return java.security.MessageDigest.isEqual(
        first.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        second.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
  }

  private String opaque()
  {
    byte[] bytes = new byte[24];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private void diagnostic(Party party, Member member, String outcome, long now)
  {
    System.out.println("PartyMode party=" + (party == null ? "-" : party.id)
        + " participant=" + (member == null ? "-" : member.id)
        + " mediaFileId=" + (party == null ? 0 : party.media)
        + " version=" + (party == null ? 0 : party.timeline.stateVersion())
        + " seq=" + (party == null ? 0 : party.timeline.commandSeq())
        + " activeCount=" + (party == null ? 0 : activeMembers(party))
        + " timelineState=" + (party == null ? "-" : party.timeline.state())
        + " memberStatus=" + (member == null ? "-" : member.status)
        + " prepared=" + (member != null && member.prepared)
        + " peerConnected=" + (member != null && member.peer != null)
        + " clockConfident=" + (member != null && member.clock.confident(now))
        + " heartbeatAgeMs=" + (member == null || member.lastHeartbeat < 0
            ? -1 : now - member.lastHeartbeat)
        + " serverMonoMs=" + now + " outcome=" + outcome);
  }

  private void diagnostic(Peer peer, JsonObject request, String outcome, long now)
  {
    Party party = find(peer);
    if (party == null && request != null && request.has("partyId"))
      party = parties.get(text(request, "partyId"));
    diagnostic(party, findMember(party, peer), outcome, now);
  }
}
