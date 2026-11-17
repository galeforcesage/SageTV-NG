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
  private static final long ACTIVATION_TIMEOUT_MS = 20000;
  static final long RESUME_COMMIT_INTERVAL_MS = 30000;
  static final long RESUME_DISCONNECT_HOLD_MS = 60000;
  static final long PARTY_LIFETIME_MS = 6 * 3600000L;
  static final long RECONNECT_GRACE_MS = 90000;
  static final long HEARTBEAT_STALE_MS = 15000;
  static final long CONNECTED_ACTIVITY_STALE_MS = 30000;
  static final long TERMINAL_TOMBSTONE_MS = 1800000;
  private static final long FUTURE_SAMPLE_TOLERANCE_MS = 50;
  private static final long VISIBLE_DRIFT_DEADBAND_MS = 67;
  private static final long HARD_DRIFT_MS = 750;
  private static final long MIN_REVEAL_LEAD_MS = 300;
  private static final long MAX_REVEAL_LEAD_MS = 7500;
  private static final long HELD_FRESHNESS_MS = 5000;
  private static final long JOIN_WINDOW_MS = 60000;
  private static final int JOIN_LINK_LIMIT = 3;
  private static final int JOIN_PRINCIPAL_LIMIT = 6;
  private static final int JOIN_CODE_LIMIT = 6;
  private static final int JOIN_GLOBAL_LIMIT = 30;
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

    /** Delivers REVEAL before allowing the prepared renderer to resume. */
    default void sendReveal(JsonObject message, PartyPlaybackDirective directive)
    {
      send(message);
      schedule(directive);
    }

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
    String invitedPartyId;
    int invitedMediaFileId;
    long invitationExpires;
    int contractRevision;

    Link(String handle) { this.handle = handle; }
  }

  private record Cached(String input, JsonObject result) {}
  private record AckPending(String directiveId, long stateVersion, long commandSeq,
      long targetPosition, String targetState) {}
  private record SampleTime(long clientMonoMs, long serverMonoMs) {}
  private record ResumeSample(long stateVersion, long commandSeq, long sampleMonoMs,
      long positionMs, String participantId) {}

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
    String preparationBinding;
    boolean preparationBindingAck;
    String preparationPlaybackSessionId;
    boolean mediaOpened;
    boolean preparationOpenReused;
    boolean preparationReplayPending;
    long preparedGeneration = -1;
    long preparingSince = -1;
    long disconnectedAt = -1;
    long lastActivity;
    long lastHeartbeat = -1;
    long lastClientSample = -1;
    long lastSample = -1;
    long lastPosition;
    String lastState;
    long bufferAhead;
    long ackCommandSeq;
    String directiveId;
    long directiveStateVersion = -1;
    long directiveCommandSeq = -1;
    long directiveStartedAt = -1;
    long directiveExecuteAt = -1;
    long directiveTargetPosition = -1;
    String directiveTargetState;
    boolean directiveNeedsPresentation;
    boolean directiveBarrier;
    boolean directiveArmed;
    boolean directiveHeld;
    long directiveHeldSample = -1;
    boolean holdConfirmPending;
    long holdConfirmBy = -1;
    long directiveArmedPosition = -1;
    long directivePrepareAt = -1;
    long directiveVisibleAt = -1;
    String directiveCause;
    String directiveKind;
    final PartyPresentationLatency presentationLatency = new PartyPresentationLatency();
    boolean bufferingRecoveryPending;
    String recoveryAttemptId;
    long recoveryDeadline = -1;
    String notifiedStatus;
    boolean notifiedPrepared;
    boolean notifiedClockConfident;
    boolean notifiedReadyForPlayback;

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
    long canonicalCommandSeq = -1;
    long barrierCommandSeq = -1;
    long barrierVisibleAt = -1;
    boolean barrierRevealed;
    ResumeSample resumeSample;
    ResumeSample persistedResumeSample;
    long resumePersistedAt = -1;
    long resumeHoldUntil = -1;
    long abandonedSince = -1;

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
  private final PartySessionCoordinator coordinator = new PartySessionCoordinator(300, 7500);
  private final Map<String, Party> parties = new LinkedHashMap<>();
  private final Map<String, Long> expiredParties = new HashMap<>();
  private final Map<String, Long> expiredResumes = new HashMap<>();
  private final Map<Peer, Link> links = new HashMap<>();
  private final Deque<Long> joinAttempts = new ArrayDeque<>();
  private final Map<String, Deque<Long>> principalJoinAttempts = new HashMap<>();
  private final Map<String, Deque<Long>> codeJoinAttempts = new HashMap<>();
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
    out.addProperty("contractRevision", CONTRACT_REVISION);
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
      member.lastActivity = now;
      party.abandonedSince = -1;
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
        response.addProperty("retryAfterMs", joinRetryAfter(link, peer.principal(),
            request != null && request.has("joinCode")
                ? request.get("joinCode").getAsString() : null, time.getAsLong()));
      if ("CODE_ROTATION_COOLDOWN".equals(failure.getMessage())
          && request != null && request.has("partyId"))
        response.addProperty("retryAfterMs",
            joinCodes.rotationRetryAfter(text(request, "partyId"), time.getAsLong()));
      if ("CODE_ISSUE_RATE_LIMITED".equals(failure.getMessage()))
        response.addProperty("retryAfterMs", codeIssueRetryAfter(time.getAsLong()));
      if ("STALE_STATE".equals(failure.getMessage()) && request != null)
        addStaleStateDetails(response, request);
      if (request != null) response.addProperty("requestId", text(request, "requestId"));
      System.out.println(rejectionDiagnostic(request, failure));
      diagnostic(peer, request, "REJECT_" + failure.getMessage(), time.getAsLong());
    }
    if (requestId != null && link != null && !link.replies.containsKey(requestId))
    {
      link.replies.put(requestId, new Cached(request.toString(), copy(response)));
      if (link.replies.size() > 128)
        link.replies.remove(link.replies.keySet().iterator().next());
    }
    peer.send(response);
    afterResponse(peer, request, response);
  }

  private JsonObject dispatch(Peer peer, JsonObject request, long now)
  {
    String type = text(request, "type");
    if ("HELLO".equals(type))
    {
      if (number(request, "contractRevision", 1, 1000)
          != CONTRACT_REVISION)
        throw new Fault("CONTRACT_REVISION_MISMATCH");
      Link link = links.get(peer);
      link.contractRevision = CONTRACT_REVISION;
      return availability(peer);
    }
    requireContractRevision(peer);
    if (Set.of("CREATE", "JOIN", "RESUME").contains(type)
        && number(request, "contractRevision", 1, 1000) != CONTRACT_REVISION)
      throw new Fault("CONTRACT_REVISION_MISMATCH");
    if ("LIST_CLIENTS".equals(type)) return clients(peer);
    if ("CREATE".equals(type)) return create(peer, request, now);

    String partyId;
    if ("JOIN".equals(type))
    {
      if (find(peer) != null) throw new Fault("ALREADY_JOINED");
      String joinCode = text(request, "joinCode");
      attemptJoin(links.get(peer), peer.principal(), joinCode, now);
      partyId = joinCodes.resolve(joinCode, now);
      if (request.has("partyId") != request.has("mediaFileId"))
        throw invalid("dispatch", "partyId/mediaFileId", "must_be_supplied_together");
      if (request.has("partyId") && !partyId.equals(text(request, "partyId")))
        throw new Fault("JOIN_DENIED");
    }
    else partyId = text(request, "partyId");

    Party party = parties.get(partyId);
    if (party == null)
    {
      if (expiredParties.containsKey(partyId)) throw new Fault("PARTY_EXPIRED");
      if ("RESUME".equals(type)
          && expiredResumes.containsKey(resumeKey(partyId, text(request, "participantId"))))
        throw new Fault("RESUME_EXPIRED");
      throw new Fault("PARTY_GONE");
    }
    if ("JOIN".equals(type)) return join(party, peer, request, now);
    if ("RESUME".equals(type)) return resume(party, peer, request, now);

    Member member = requireMember(party, peer);
    member.lastActivity = now;
    party.abandonedSince = -1;
    return switch (type)
    {
      case "SNAPSHOT" ->
      {
        refreshWindow(party, member.peer);
        yield snapshot(party, now);
      }
      case "INVITE" -> invite(party, member, request);
      case "JOIN_CODE", "ROTATE_CODE" -> joinCode(party, member, type, now);
      case "CLOCK" -> clockProbe(member, now);
      case "CLOCK_REPLY" -> clockReply(party, member, request, now);
      case "PREPARE" -> prepare(party, member, now);
      case "MEDIA_OPEN_BINDING_ACK" -> mediaOpenBindingAck(party, member, request, now);
      case "MEDIA_OPENED" -> mediaOpened(party, member, request);
      case "READY" -> ready(party, member, request, now);
      case "ACTION" -> action(party, member, request, now);
      case "ACK" -> commandResult(party, member, request, now);
      case "HEARTBEAT" -> heartbeat(party, member, request, now);
      case "LEAVE" ->
      {
        remove(party, member, true, now);
        yield envelope("LEFT");
      }
      case "END" ->
      {
        requireHost(party, member);
        yield end(party, true, "HOST_ENDED", now, member.peer);
      }
      default -> throw new Fault("UNKNOWN_TYPE");
    };
  }

  private JsonObject create(Peer peer, JsonObject request, long now)
  {
    if (find(peer) != null) throw new Fault("ALREADY_JOINED");
    if (parties.size() >= 8) throw new Fault("SERVER_CAPACITY");
    boolean inferredMedia = !request.has("mediaFileId");
    int media;
    MediaWindow window;
    try
    {
      media = inferredMedia
          ? peer.currentMediaFileId()
          : (int)number(request, "mediaFileId", 1, Integer.MAX_VALUE);
      window = mediaWindow(peer, media);
    }
    catch (Fault failure)
    {
      if (inferredMedia && Set.of("MEDIA_PATH_NOT_READY", "MEDIA_UNAVAILABLE")
          .contains(failure.getMessage()))
        throw new Fault("CURRENT_MEDIA_UNAVAILABLE");
      throw failure;
    }
    String startMode = request.has("startMode") ? text(request, "startMode") : "BEGINNING";
    if (!Set.of("BEGINNING", "CURRENT_POSITION").contains(startMode))
      throw invalid("create", "startMode", "invalid_enum");
    requireGrowingCapability(peer, window);
    Party party = new Party(opaque(), media, window, liveEdgeDelayMs, now);
    if ("CURRENT_POSITION".equals(startMode))
      party.timeline.setInitialPosition(peer.currentPosition(media));
    Member host = member(peer, now);
    party.members.put(host.id, host);
    party.host = host.id;
    parties.put(party.id, party);
    issueCode(party, now);
    peer.preparationOwner(true);
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
    Link link = links.get(peer);
    if (link != null)
    {
      link.invitedPartyId = null;
      link.invitedMediaFileId = 0;
      link.invitationExpires = 0;
    }
    peer.preparationOwner(true);
    roster(party, member);
    return joined(party, member, "JOINED", now, false);
  }

  private JsonObject resume(Party party, Peer peer, JsonObject request, long now)
  {
    if (find(peer) != null) throw new Fault("ALREADY_JOINED");
    String participantId = text(request, "participantId");
    if (expiredResumes.containsKey(resumeKey(party.id, participantId)))
      throw new Fault("RESUME_EXPIRED");
    Member member = party.members.get(participantId);
    if (member == null || member.peer != null || member.disconnectedAt < 0)
      throw new Fault("RESUME_DENIED");
    if (now - member.disconnectedAt > RECONNECT_GRACE_MS || member.recoveryExpires <= now)
      throw new Fault("RESUME_EXPIRED");
    if (!member.principal.equals(peer.principal())
        || !constantEquals(member.recoveryToken, text(request, "recoveryToken")))
      throw new Fault("RESUME_DENIED");
    member.peer = peer;
    member.status = "PREPARING";
    member.prepared = false;
    member.preparationId = null;
    member.preparationBinding = null;
    member.preparationBindingAck = false;
    member.preparationPlaybackSessionId = null;
    member.mediaOpened = false;
    member.preparationOpenReused = false;
    member.preparationReplayPending = false;
    member.preparedGeneration = -1;
    member.preparingSince = now;
    member.disconnectedAt = -1;
    member.lastActivity = now;
    member.lastHeartbeat = -1;
    member.lastClientSample = -1;
    member.lastSample = -1;
    member.lastState = null;
    member.clock.reset();
    member.probes.clear();
    party.abandonedSince = -1;
    peer.preparationOwner(true);
    if (party.host == null) party.host = member.id;
    roster(party, member);
    return joined(party, member, "JOINED", now, false);
  }

  private JsonObject clients(Peer requester)
  {
    JsonObject out = envelope("CLIENTS");
    JsonArray clients = new JsonArray();
    for (Map.Entry<Peer, Link> entry : links.entrySet())
      if (entry.getKey() != requester && eligible(entry.getKey())
          && find(entry.getKey()) == null)
        clients.add(entry.getValue().handle);
    out.add("sessionHandles", clients);
    return out;
  }

  private JsonObject invite(Party party, Member member, JsonObject request)
  {
    requireHost(party, member);
    String target = text(request, "sessionHandle");
    for (Map.Entry<Peer, Link> entry : links.entrySet())
      if (entry.getKey() != member.peer
          && entry.getValue().handle.equals(target) && eligible(entry.getKey())
          && find(entry.getKey()) == null)
      {
        Link targetLink = entry.getValue();
        long now = time.getAsLong();
        if (party.id.equals(targetLink.invitedPartyId)
            && targetLink.invitedMediaFileId == party.media
            && targetLink.invitationExpires > now)
          return envelope("ACK");
        JsonObject invite = envelope("INVITED");
        invite.addProperty("partyId", party.id);
        invite.addProperty("mediaFileId", party.media);
        invite.addProperty("joinCodeRequired", true);
        targetLink.invitedPartyId = party.id;
        targetLink.invitedMediaFileId = party.media;
        targetLink.invitationExpires = Math.min(
            joinCodes.current(party.id, now).expiresAt(), now + 300000);
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
    if (member.preparationId != null)
    {
      if (!member.prepared || member.peer.preparedGeneration(party.media) < 0)
        throw new Fault("PREPARATION_PENDING");
      member.mediaOpened = false;
      member.preparationOpenReused = true;
      return prepareAccepted(party, member.preparationId,
          member.preparationBinding, now, true);
    }
    member.peer.cancelScheduled();
    member.peer.cancelPreparation();
    if (member.preparationBinding != null)
      member.peer.cancelPreparationBinding(member.preparationBinding);
    member.status = "PREPARING";
    member.prepared = false;
    member.preparationId = opaque();
    member.preparationBinding = preparationBinding();
    member.preparationBindingAck = false;
    member.preparationPlaybackSessionId = null;
    member.mediaOpened = false;
    member.preparationOpenReused = false;
    member.preparationReplayPending = false;
    member.preparedGeneration = -1;
    member.preparingSince = now;
    roster(party, member);
    return prepareAccepted(party, member.preparationId,
        member.preparationBinding, now, false);
  }

  private JsonObject prepareAccepted(Party party, String preparationId,
      String preparationBinding, long now, boolean openReused)
  {
    JsonObject out = envelope("PREPARE_ACCEPTED");
    out.addProperty("partyId", party.id);
    out.addProperty("mediaFileId", party.media);
    out.addProperty("preparationId", preparationId);
    out.addProperty("preparationBinding", preparationBinding);
    out.addProperty("openReused", openReused);
    out.addProperty("targetPositionMs", party.timeline.positionAt(now));
    out.addProperty("holdPlayback", true);
    return out;
  }

  private JsonObject mediaOpenBindingAck(Party party, Member member,
      JsonObject request, long now)
  {
    String preparationId = text(request, "preparationId");
    String binding = text(request, "preparationBinding");
    if (member.preparationId == null
        || !constantEquals(member.preparationId, preparationId)
        || member.preparationBinding == null
        || !constantEquals(member.preparationBinding, binding)
        || member.preparationBindingAck)
      throw new Fault("STALE_PREPARATION");
    String playbackSessionId = request.has("playbackSessionId")
        ? text(request, "playbackSessionId") : null;
    if (playbackSessionId != null && playbackSessionId.length() > 256)
      throw invalid("media_open_binding_ack", "playbackSessionId", "too_long");
    member.preparationBindingAck = true;
    member.preparationPlaybackSessionId = playbackSessionId;
    JsonObject out = envelope("ACK");
    out.addProperty("preparationId", preparationId);
    out.addProperty("preparationBinding", binding);
    if (playbackSessionId != null)
      out.addProperty("playbackSessionId", playbackSessionId);
    return out;
  }

  private JsonObject mediaOpened(Party party, Member member, JsonObject request)
  {
    String preparationId = text(request, "preparationId");
    String binding = text(request, "preparationBinding");
    if (member.preparationId == null
        || !constantEquals(member.preparationId, preparationId)
        || member.preparationBinding == null
        || !constantEquals(member.preparationBinding, binding)
        || !member.preparationBindingAck
        || member.mediaOpened
        || !member.peer.preparationBindingReady(binding))
      throw new Fault("OPEN_BINDING_MISMATCH");
    if (!request.has("reused")
        || flag(request, "reused") != member.preparationOpenReused)
      throw new Fault("OPEN_BINDING_MISMATCH");
    number(request, "localOpenGeneration", 0, MAX_TIME);
    number(request, "surfaceGeneration", 0, MAX_TIME);
    if (member.preparationOpenReused)
    {
      if (!member.prepared || member.preparedGeneration < 0
          || member.peer.preparedGeneration(party.media) != member.preparedGeneration)
        throw new Fault("OPEN_BINDING_MISMATCH");
      member.preparationReplayPending = true;
    }
    member.mediaOpened = true;
    JsonObject out = envelope("ACK");
    out.addProperty("preparationId", preparationId);
    out.addProperty("preparationBinding", binding);
    return out;
  }

  private JsonObject ready(Party party, Member member, JsonObject request, long now)
  {
    requireCurrent(party, request);
    if (!member.prepared || member.preparationId == null
        || !member.preparationBindingAck || !member.mediaOpened
        || !member.peer.preparationBindingReady(member.preparationBinding))
      throw new Fault("NOT_PREPARED");
    if (!member.clock.confident(now)) throw new Fault("CLOCK_UNCERTAIN");
    if (!"PREPARING".equals(member.status))
    {
      if (member.directiveId == null) member.status = "READY";
      member.lastHeartbeat = now;
      member.bufferAhead = request.has("bufferAheadMs")
          ? number(request, "bufferAheadMs", 0, 3600000) : 0;
      roster(party, member);
      return readyAck(party);
    }
    Delivery delivery = member.peer.delivery(party.media);
    long alignedPosition = -1;
    if ("PAUSED".equals(party.timeline.state()))
    {
      long position = member.peer.preparedPausedPosition(party.media);
      if (position >= 0
          && Math.abs(position - party.timeline.positionAt(now)) <= VISIBLE_DRIFT_DEADBAND_MS)
        alignedPosition = position;
    }
    member.status = "READY";
    member.lastHeartbeat = now;
    if (alignedPosition >= 0) member.lastPosition = alignedPosition;
    member.bufferAhead = request.has("bufferAheadMs")
        ? number(request, "bufferAheadMs", 0, 3600000) : 0;
    roster(party, member);
    member.peer.progressOwner(true);
    String alignmentReason = member.preparationOpenReused
        ? "REUSED_OPEN_RECOVERY" : "INITIAL_ALIGNMENT";
    JsonObject correction = correction(party, member, now, alignmentReason,
        alignedPosition >= 0 ? "NONE" : "HARD");
    if (alignedPosition >= 0)
      correction.addProperty("targetPositionMs", alignedPosition);
    correction.add("delivery", deliveryJson(delivery));
    return alignedPosition >= 0
        ? schedule(member, correction, now, true, false, false)
        : schedule(member, correction, now, true);
  }

  private JsonObject readyAck(Party party)
  {
    JsonObject out = envelope("READY_ACK");
    out.addProperty("partyId", party.id);
    out.addProperty("stateVersion", party.timeline.stateVersion());
    out.addProperty("commandSeq", party.timeline.commandSeq());
    return out;
  }

  private JsonObject action(Party party, Member member, JsonObject request, long now)
  {
    requireCurrent(party, request);
    String action = text(request, "action");
    if (!Set.of("PLAY", "PAUSE", "JUMP", "FF", "REW", "FF2", "REW2", "COMSKIP")
        .contains(action))
      throw new Fault("INVALID_ACTION");
    if (!"JUMP".equals(action) && request.has("targetPositionMs"))
      throw invalid("action", "targetPositionMs", "forbidden");
    if ("COMSKIP".equals(action)) requireHost(party, member);
    if (party.timeline.pending() != null || party.canonicalCommandSeq >= 0)
      throw new Fault("TRANSITION_PENDING");
    if (!member.clock.confident(now)) throw new Fault("CLOCK_UNCERTAIN");
    if (!canControl(party, member, now, "PAUSE".equals(action)))
      throw new Fault("NOT_READY");

    fenceParticipantRecovery(party);
    long lead = lead(party, now);
    long target = party.timeline.positionAt(now + lead);
    String targetState = party.timeline.state();
    if ("PLAY".equals(action)) targetState = "PLAYING";
    else if ("PAUSE".equals(action)) targetState = "PAUSED";
    else if ("JUMP".equals(action))
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

  private void fenceParticipantRecovery(Party party)
  {
    for (Member candidate : party.members.values())
      if (candidate.directiveId != null
          && "BUFFERING_RECOVERY".equals(candidate.directiveCause))
      {
        JsonObject cancel = directiveEvent("CANCEL", party, candidate);
        cancel.addProperty("reason", "SHARED_TRANSITION");
        candidate.peer.send(cancel);
        candidate.peer.cancelScheduled();
        clearDirective(candidate);
        candidate.recoveryAttemptId = null;
        candidate.recoveryDeadline = -1;
        candidate.status = "ACTIVE";
        roster(party, candidate);
      }
  }

  private JsonObject commandResult(Party party, Member member, JsonObject request, long now)
  {
    AckPending pending = new AckPending(
        member.directiveId, member.directiveStateVersion, member.directiveCommandSeq,
        member.directiveTargetPosition, member.directiveTargetState);
    long seq = -1;
    String stage = diagnosticText(request, "stage");
    boolean matched = false;
    String accepted = "ACCEPTED";
    try
    {
      requireCurrent(party, request);
      seq = number(request, "commandSeq", 0, party.timeline.commandSeq());
      stage = text(request, "stage");
      if (!Set.of("EXECUTED", "ARMED", "HELD", "PRESENTED").contains(stage))
        throw invalid("ack", "stage", "invalid_enum");
      if ("EXECUTED".equals(stage)
          && (request.has("sampleMonoMs") || request.has("positionMs")
              || request.has("state")))
        throw invalid("ack", "presentation_fields", "forbidden_for_executed");
      if (member.directiveId == null
          || !constantEquals(member.directiveId, text(request, "directiveId"))
          || member.directiveCommandSeq != seq)
        throw new Fault("STALE_DIRECTIVE");
      matched = true;
      member.ackCommandSeq = Math.max(member.ackCommandSeq, seq);
      if (!flag(request, "success"))
      {
        text(request, "failureCode");
        failDirective(party, member);
        accepted = "ACCEPTED_CLIENT_FAILURE";
      }
      else if ("ARMED".equals(stage))
      {
        try
        {
          accepted = armed(party, member, request, now)
              ? "ACCEPTED_ARMED_AWAITING_COHORT"
              : "ACCEPTED_ARMED_SETTLING";
        }
        catch (Fault failure)
        {
          failDirective(party, member);
          throw failure;
        }
      }
      else if ("HELD".equals(stage))
      {
        try
        {
          held(party, member, request, now);
          accepted = "ACCEPTED_HELD_AWAITING_COHORT";
        }
        catch (Fault failure)
        {
          failDirective(party, member);
          throw failure;
        }
      }
      else if ("PRESENTED".equals(stage))
      {
        try
        {
          presented(party, member, request, now);
          accepted = "ACCEPTED_PRESENTED_SETTLED";
        }
        catch (Fault failure)
        {
          failDirective(party, member);
          throw failure;
        }
      }
      else if (!member.directiveNeedsPresentation)
      {
        completeDirective(party, member);
        accepted = "ACCEPTED_EXECUTED_SETTLED";
      }
      else
      {
        accepted = member.directiveBarrier
            ? "ACCEPTED_EXECUTED_AWAITING_ARMED"
            : "ACCEPTED_EXECUTED_AWAITING_PRESENTED";
      }
    }
    catch (Fault failure)
    {
      ackDiagnostic(party, member, pending, request, now,
          "REJECT_" + failure.getMessage() + (matched ? "_DIRECTIVE_FAILED" : ""));
      throw failure;
    }
    ackDiagnostic(party, member, pending, request, now, accepted);
    JsonObject out = envelope("ACK");
    out.addProperty("commandSeq", seq);
    out.addProperty("stage", stage);
    out.addProperty("directiveId", text(request, "directiveId"));
    if ("ARMED".equals(stage))
      out.addProperty("accepted", member.directiveArmed);
    else if ("HELD".equals(stage))
      out.addProperty("accepted", member.directiveHeld);
    return out;
  }

  private JsonObject heartbeat(Party party, Member member, JsonObject request, long now)
  {
    requireCurrent(party, request);
    if (!member.prepared) throw new Fault("NOT_PREPARED");
    String state = text(request, "state");
    if (!Set.of("PLAYING", "PAUSED", "BUFFERING", "STOPPED", "ERROR").contains(state))
      throw invalid("heartbeat", "state", "invalid_enum");
    long position = number(request, "positionMs", 0, party.timeline.availableEndMs());
    SampleTime sampleTime = heartbeatSampleTime(member, request, now, state, position);
    long sample = sampleTime.serverMonoMs();
    member.lastHeartbeat = now;
    member.lastClientSample = sampleTime.clientMonoMs();
    member.lastSample = sample;
    member.lastPosition = position;
    member.lastState = state;
    member.bufferAhead = request.has("bufferAheadMs")
        ? number(request, "bufferAheadMs", 0, 3600000) : 0;

    if ("STOPPED".equals(state))
    {
      member.status = "STOPPED";
      member.prepared = false;
      member.peer.cancelScheduled();
      member.peer.cancelPreparation();
      if (member.preparationBinding != null)
        member.peer.cancelPreparationBinding(member.preparationBinding);
      clearDirective(member);
      if (member.id.equals(party.host)) pauseForHostExit(party, member, now);
    }
    else if ("ERROR".equals(state))
    {
      if (member.directiveId != null) failDirective(party, member);
      else
      {
        member.status = "LATE";
      }
    }
    else if ("BUFFERING".equals(state))
    {
      member.bufferingRecoveryPending = true;
      member.status = "LATE";
    }
    else if (member.directiveId != null || party.canonicalCommandSeq >= 0)
    {
      member.status = "ACTIVATING";
    }
    else if (member.bufferingRecoveryPending && "PLAYING".equals(state))
    {
      long skew = position - party.timeline.positionAt(sample);
      boolean wrongState = !state.equals(party.timeline.state());
      member.bufferingRecoveryPending = false;
      if (!wrongState && Math.abs(skew) <= VISIBLE_DRIFT_DEADBAND_MS)
      {
        member.status = "ACTIVE";
      }
      else
      {
        JsonObject correction =
            correction(party, member, now, "BUFFERING_RECOVERY", "HARD");
        member.peer.send(schedule(member, correction, now, true));
      }
      roster(party, member);
    }
    else
    {
      if (!member.bufferingRecoveryPending) member.status = "ACTIVE";
      roster(party, member);
    }
    if (!"ERROR".equals(state)
        && ("STOPPED".equals(state)
            || (member.directiveId == null && party.canonicalCommandSeq < 0)))
      acceptResumeSample(party, member, party.timeline.stateVersion(),
          party.timeline.commandSeq(), sample, position);

    JsonObject out = envelope("HEARTBEAT_ACK");
    out.addProperty("partyId", party.id);
    out.addProperty("stateVersion", party.timeline.stateVersion());
    out.addProperty("commandSeq", party.timeline.commandSeq());
    out.addProperty("desiredState", party.timeline.state());
    out.addProperty("desiredPositionMs", party.timeline.positionAt(now));
    out.addProperty("skewMs", position - party.timeline.positionAt(sample));
    out.addProperty("activationPending",
        member.directiveId != null || party.canonicalCommandSeq >= 0);
    return out;
  }

  private JsonObject transition(Party party, Member origin, String cause, long now,
      long targetPositionMs, String targetState)
  {
    boolean barrier = requiresPresentation(cause, targetState)
        && requiresBarrier(cause, "COMMAND");
    List<PartySessionCoordinator.ParticipantTiming> timing =
        participantTiming(party, now, barrier);
    PartyCommand command = coordinator.schedule(party.timeline, cause,
        targetPositionMs, targetState, now, origin.id, timing);
    JsonObject commandEvent = commandJson(party.id, command);
    boolean needsPresentation = requiresPresentation(cause, targetState);
    if (needsPresentation)
    {
      party.canonicalCommandSeq = command.commandSeq();
    }
    if (barrier)
    {
      party.barrierCommandSeq = command.commandSeq();
      party.barrierVisibleAt = command.executeAtServerMonoMs();
      party.barrierRevealed = false;
      party.timeline.reschedule(command.commandSeq(), MAX_TIME);
    }
    JsonObject originResponse = null;
    for (Member member : party.members.values())
    {
      if (member.peer != null)
      {
        member.peer.cancelScheduled();
        clearDirective(member);
      }
      if (commandRecipient(party, member, targetState, now))
      {
        JsonObject scheduled = schedule(member, commandEvent, now, needsPresentation, barrier);
        if (member == origin) originResponse = scheduled;
        else member.peer.send(scheduled);
      }
    }
    diagnostic(party, origin,
        "COMMAND_" + cause + "_DEADLINE_" + command.executeAtServerMonoMs(), now);
    return originResponse == null ? commandEvent : originResponse;
  }

  private JsonObject correction(Party party, Member member, long now, String reason, String kind)
  {
    long executeAt = now + (requiresBarrier(reason, kind)
        ? participantLead(member, now) : networkLead(member, now));
    JsonObject out = envelope("CORRECTION");
    out.addProperty("partyId", party.id);
    out.addProperty("participantId", member.id);
    out.addProperty("stateVersion", party.timeline.stateVersion());
    out.addProperty("commandSeq", party.timeline.commandSeq());
    out.addProperty("reason", reason);
    out.addProperty("kind", kind);
    out.addProperty("targetPositionMs", party.timeline.positionAt(executeAt));
    out.addProperty("targetState", party.timeline.state());
    out.addProperty("targetRate", 1);
    out.addProperty("executeAtServerMonoMs", executeAt);
    if ("BUFFERING_RECOVERY".equals(reason))
    {
      if (member.recoveryAttemptId == null || member.recoveryDeadline <= now)
      {
        member.recoveryAttemptId = opaque();
        member.recoveryDeadline = now + ACTIVATION_TIMEOUT_MS;
      }
      out.addProperty("recoveryAttemptId", member.recoveryAttemptId);
      out.addProperty("recoveryDeadlineServerMonoMs", member.recoveryDeadline);
    }
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
    roster(party, member);
    return out;
  }

  private SampleTime sampleTime(Member member, JsonObject request, long now)
  {
    if (!member.clock.confident(now)) throw new Fault("CLOCK_UNCERTAIN");
    long local = number(request, "sampleMonoMs", 0, MAX_TIME);
    long sample = Math.round(local + member.clock.offset(now));
    double futureTolerance =
        Math.max(FUTURE_SAMPLE_TOLERANCE_MS, member.clock.uncertainty(now));
    if (sample < now - 5000 || sample > now + futureTolerance)
      throw new Fault("STALE_SAMPLE");
    if (sample <= member.lastSample) throw new Fault("STALE_SAMPLE");
    return new SampleTime(local, sample);
  }

  private SampleTime heartbeatSampleTime(Member member, JsonObject request, long now,
      String state, long position)
  {
    if (!member.clock.confident(now)) throw new Fault("CLOCK_UNCERTAIN");
    long local = number(request, "sampleMonoMs", 0, MAX_TIME);
    if ("PAUSED".equals(state) && "PAUSED".equals(member.lastState)
        && "ACTIVE".equals(member.status) && member.directiveId == null
        && local == member.lastClientSample && position == member.lastPosition)
      return new SampleTime(local, member.lastSample);
    long sample = Math.round(local + member.clock.offset(now));
    double futureTolerance =
        Math.max(FUTURE_SAMPLE_TOLERANCE_MS, member.clock.uncertainty(now));
    if (sample > now + futureTolerance) throw new Fault("STALE_SAMPLE");
    if (sample < now - 5000 || sample <= member.lastSample)
      throw new Fault("STALE_SAMPLE");
    return new SampleTime(local, sample);
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
    return participantTiming(party, now, false);
  }

  private List<PartySessionCoordinator.ParticipantTiming> participantTiming(
      Party party, long now, boolean includePresentation)
  {
    List<PartySessionCoordinator.ParticipantTiming> timing = new ArrayList<>();
    for (Member member : party.members.values())
    {
      boolean confident = includePresentation && member.presentationLatency.confident();
      timing.add(new PartySessionCoordinator.ParticipantTiming(
          commandRecipient(party, member, "PLAYING", now), member.clock.p95(now),
          confident ? member.presentationLatency.predictedTotalMs() : 0,
          confident ? member.presentationLatency.uncertaintyMs() : 0));
    }
    return timing;
  }

  private long participantLead(Member member, long now)
  {
    long presentationLead = member.presentationLatency.confident()
        ? member.presentationLatency.predictedTotalMs()
            + member.presentationLatency.uncertaintyMs()
        : 0;
    return Math.min(7500, Math.max(300,
        Math.max(2 * member.clock.p95(now) + 100,
            presentationLead)));
  }

  private long networkLead(Member member, long now)
  {
    return Math.min(2500, Math.max(300, 2 * member.clock.p95(now) + 100));
  }

  private JsonObject schedule(Member member, JsonObject transition, long now,
      boolean needsPresentation)
  {
    String cause = transition.has("cause") ? transition.get("cause").getAsString()
        : transition.has("reason") ? transition.get("reason").getAsString() : "";
    String kind = transition.has("kind") ? transition.get("kind").getAsString() : "COMMAND";
    return schedule(member, transition, now, needsPresentation,
        needsPresentation && requiresBarrier(cause, kind));
  }

  private JsonObject schedule(Member member, JsonObject transition, long now,
      boolean needsPresentation, boolean barrier)
  {
    return schedule(member, transition, now, needsPresentation, barrier, true);
  }

  private JsonObject schedule(Member member, JsonObject transition, long now,
      boolean needsPresentation, boolean barrier, boolean schedulePlayer)
  {
    JsonObject scheduled = copy(transition);
    String kind = transition.has("kind") ? transition.get("kind").getAsString() : "COMMAND";
    String directiveId = opaque();
    scheduled.addProperty("directiveId", directiveId);
    long visibleAt = transition.get("executeAtServerMonoMs").getAsLong();
    long prepareAt = visibleAt;
    if (barrier)
    {
      long predicted = member.presentationLatency.predictedTotalMs();
      prepareAt = Math.max(now + Math.max(MIN_REVEAL_LEAD_MS, 2 * member.clock.p95(now)),
          visibleAt - predicted);
      scheduled.addProperty("phase", "PREPARE");
      scheduled.addProperty("barrier", true);
      scheduled.addProperty("visibleAtServerMonoMs", visibleAt);
      scheduled.addProperty("executeAtServerMonoMs", prepareAt);
      scheduled.addProperty("predictedPresentationLatencyMs", predicted);
      scheduled.addProperty("presentationUncertaintyMs",
          member.presentationLatency.uncertaintyMs());
    }
    member.directiveId = directiveId;
    member.directiveStateVersion = transition.get("stateVersion").getAsLong();
    member.directiveCommandSeq = transition.get("commandSeq").getAsLong();
    member.directiveStartedAt = now;
    member.directiveExecuteAt = prepareAt;
    member.directivePrepareAt = prepareAt;
    member.directiveVisibleAt = visibleAt;
    member.directiveTargetPosition = transition.get("targetPositionMs").getAsLong();
    member.directiveTargetState = transition.get("targetState").getAsString();
    member.directiveNeedsPresentation = needsPresentation;
    member.directiveBarrier = barrier;
    member.directiveArmed = false;
    member.directiveHeld = false;
    member.directiveHeldSample = -1;
    member.holdConfirmPending = false;
    member.holdConfirmBy = -1;
    member.directiveArmedPosition = -1;
    member.directiveCause = transition.has("cause")
        ? transition.get("cause").getAsString()
        : transition.has("reason") ? transition.get("reason").getAsString() : kind;
    member.directiveKind = kind;
    member.status = "ACTIVATING";
    roster(find(member.peer), member);
    String playerKind = barrier ? "ARM"
        : transition.has("kind") ? kind
        : transition.has("cause") ? transition.get("cause").getAsString()
        : "COMMAND";
    if (schedulePlayer)
      member.peer.schedule(new PartyPlaybackDirective(
          transition.get("stateVersion").getAsLong(),
          transition.get("commandSeq").getAsLong(),
          playerKind,
          transition.get("targetPositionMs").getAsLong(),
          barrier ? "PAUSED" : transition.get("targetState").getAsString(),
          transition.get("targetRate").getAsDouble(),
          prepareAt));
    return scheduled;
  }

  private boolean armed(Party party, Member member, JsonObject request, long now)
  {
    if (!member.directiveBarrier || member.directiveArmed)
      throw invalid("ack_armed", "directive", "invalid_stage_state");
    SampleTime sampleTime = sampleTime(member, request, now);
    long sample = sampleTime.serverMonoMs();
    number(request, "sampleUncertaintyMs", 0, 5000);
    long position = number(request, "positionMs", 0, party.timeline.availableEndMs());
    String state = text(request, "state");
    if (!"PAUSED".equals(state))
      throw new Fault("INVALID_PRESENTATION");
    if (sample + VISIBLE_DRIFT_DEADBAND_MS < member.directivePrepareAt)
      throw new Fault("EARLY_PRESENTATION");

    member.lastHeartbeat = now;
    member.lastClientSample = sampleTime.clientMonoMs();
    member.lastSample = sample;
    member.lastPosition = position;
    member.lastState = state;
    if (Math.abs(position - member.directiveTargetPosition) > HARD_DRIFT_MS)
    {
      member.status = "ACTIVATING";
      roster(party, member);
      return false;
    }
    completeArm(party, member, sample);
    member.directiveArmedPosition = position;
    return true;
  }

  private void completeArm(Party party, Member member, long observedAt)
  {
    member.directiveArmed = true;
    member.status = "ARMED";
    member.presentationLatency.preparation(observedAt - member.directivePrepareAt);
    roster(party, member);
  }

  private void held(Party party, Member member, JsonObject request, long now)
  {
    if (!member.directiveBarrier || !member.directiveArmed
        || member.directiveHeld || !member.holdConfirmPending)
      throw invalid("ack_held", "directive", "invalid_stage_state");
    if (now > member.holdConfirmBy) throw new Fault("ACTIVATION_TIMEOUT");
    SampleTime sampleTime = sampleTime(member, request, now);
    long sample = sampleTime.serverMonoMs();
    number(request, "sampleUncertaintyMs", 0, 5000);
    long position = number(request, "positionMs", 0, party.timeline.availableEndMs());
    if (!"PAUSED".equals(text(request, "state"))
        || Math.abs(position - member.directiveArmedPosition)
            > VISIBLE_DRIFT_DEADBAND_MS)
      throw new Fault("POSITION_MISMATCH");
    if (now - sample > HELD_FRESHNESS_MS) throw new Fault("STALE_SAMPLE");
    member.lastHeartbeat = now;
    member.lastClientSample = sampleTime.clientMonoMs();
    member.lastSample = sample;
    member.lastPosition = position;
    member.lastState = "PAUSED";
    member.directiveHeld = true;
    member.directiveHeldSample = sample;
    member.holdConfirmPending = false;
    member.status = "HELD";
    roster(party, member);
  }

  private void afterResponse(Peer peer, JsonObject request, JsonObject response)
  {
    if (request == null || response == null) return;
    Party party = find(peer);
    Member member = findMember(party, peer);
    if ("PREPARE".equals(diagnosticText(request, "type"))
        && "PREPARE_ACCEPTED".equals(diagnosticText(response, "type"))
        && party != null && member != null && member.preparationId != null
        && !response.get("openReused").getAsBoolean())
    {
      JsonObject binding = envelope("MEDIA_OPEN_BINDING");
      binding.addProperty("partyId", party.id);
      binding.addProperty("preparationId", member.preparationId);
      binding.addProperty("preparationBinding", member.preparationBinding);
      peer.send(binding);
      return;
    }
    if ("MEDIA_OPEN_BINDING_ACK".equals(diagnosticText(request, "type"))
        && "ACK".equals(diagnosticText(response, "type"))
        && party != null && member != null && member.preparationBindingAck
        && member.preparationId != null)
    {
      try
      {
        peer.installPreparationBinding(new PartyPlayerAdapter.PreparationBinding(
            party.id, member.preparationId, member.preparationBinding, party.media,
            member.preparationPlaybackSessionId));
        peer.prepare(party.media, member.preparationId,
            party.timeline.positionAt(time.getAsLong()));
      }
      catch (Fault failure)
      {
        preparationResult(peer, member.preparationId,
            PartyPlayerAdapter.PreparationResult.failed(
                -1, failure.getMessage()));
      }
      return;
    }
    if ("MEDIA_OPENED".equals(diagnosticText(request, "type"))
        && "ACK".equals(diagnosticText(response, "type"))
        && party != null && member != null && member.preparationReplayPending)
    {
      member.preparationReplayPending = false;
      preparationResult(peer, member.preparationId,
          PartyPlayerAdapter.PreparationResult.loaded(
              0, member.preparedGeneration));
      return;
    }
    if (!"ACK".equals(diagnosticText(request, "type"))
        || !"ACK".equals(diagnosticText(response, "type"))
        || party == null || member == null)
      return;
    String stage = diagnosticText(request, "stage");
    if ("ARMED".equals(stage) && member.directiveArmed)
    {
      if (member.directiveCommandSeq == party.barrierCommandSeq)
        holdSharedBarrier(party, time.getAsLong());
      else
        hold(party, member, time.getAsLong());
      return;
    }
    if (!"HELD".equals(stage) || !member.directiveHeld)
      return;
    long now = time.getAsLong();
    if (member.directiveCommandSeq == party.barrierCommandSeq)
      revealSharedBarrier(party, now);
    else
      reveal(party, member, now, Math.max(member.directiveVisibleAt,
          now + Math.max(MIN_REVEAL_LEAD_MS, 2 * member.clock.p95(now))));
  }

  private void holdSharedBarrier(Party party, long now)
  {
    if (party.barrierCommandSeq < 0 || party.barrierRevealed) return;
    List<Member> cohort = barrierCohort(party);
    if (cohort.isEmpty()) return;
    for (Member candidate : cohort)
      if (!candidate.directiveArmed) return;
    for (Member candidate : cohort) hold(party, candidate, now);
  }

  private void hold(Party party, Member member, long now)
  {
    if (member.holdConfirmPending || member.directiveHeld
        || member.directiveId == null) return;
    long reserve = presentationReserve(member, now);
    long activationEnd = member.directiveStartedAt + ACTIVATION_TIMEOUT_MS;
    member.holdConfirmBy = Math.min(now + HELD_FRESHNESS_MS,
        activationEnd - reserve - MIN_REVEAL_LEAD_MS);
    if (member.holdConfirmBy <= now)
    {
      failDirective(find(member.peer), member);
      return;
    }
    member.holdConfirmPending = true;
    JsonObject out = directiveEvent("HOLD_CONFIRM", party, member);
    out.addProperty("confirmByServerMonoMs", member.holdConfirmBy);
    member.peer.send(out);
  }

  private List<Member> barrierCohort(Party party)
  {
    List<Member> cohort = new ArrayList<>();
    for (Member candidate : party.members.values())
      if (candidate.directiveId != null
          && candidate.directiveCommandSeq == party.barrierCommandSeq
          && candidate.directiveBarrier)
        cohort.add(candidate);
    return cohort;
  }

  private void revealSharedBarrier(Party party, long now)
  {
    if (party.barrierCommandSeq < 0 || party.barrierRevealed) return;
    List<Member> cohort = barrierCohort(party);
    for (Member candidate : cohort)
      if (!candidate.directiveHeld) return;
    if (cohort.isEmpty())
    {
      if (party.timeline.pending() != null
          && party.timeline.pending().commandSeq() == party.barrierCommandSeq)
        party.timeline.cancelPending(party.barrierCommandSeq);
      if (party.canonicalCommandSeq == party.barrierCommandSeq)
        party.canonicalCommandSeq = -1;
      party.barrierCommandSeq = -1;
      party.barrierVisibleAt = -1;
      party.barrierRevealed = false;
      return;
    }
    long revealAt = Math.max(party.barrierVisibleAt, now + MIN_REVEAL_LEAD_MS);
    long latest = now + MAX_REVEAL_LEAD_MS;
    for (Member candidate : cohort)
      latest = Math.min(latest, Math.min(candidate.directiveHeldSample + HELD_FRESHNESS_MS,
          candidate.directiveStartedAt + ACTIVATION_TIMEOUT_MS
              - presentationReserve(candidate, now)));
    if (revealAt > latest)
    {
      long failedSeq = party.barrierCommandSeq;
      for (Member candidate : cohort)
      {
        if (candidate.peer != null) candidate.peer.cancelScheduled();
        clearDirective(candidate);
        candidate.status = "LATE";
        roster(party, candidate);
      }
      if (party.timeline.pending() != null
          && party.timeline.pending().commandSeq() == failedSeq)
        party.timeline.cancelPending(failedSeq);
      if (party.canonicalCommandSeq == failedSeq)
        party.canonicalCommandSeq = -1;
      party.barrierCommandSeq = -1;
      party.barrierVisibleAt = -1;
      party.barrierRevealed = false;
      return;
    }
    PartyCommand command = party.timeline.reschedule(party.barrierCommandSeq, revealAt);
    party.barrierRevealed = true;
    for (Member candidate : cohort) reveal(party, candidate, now, revealAt);
    diagnostic(party, null,
        "REVEAL_COMMAND_" + command.commandSeq() + "_DEADLINE_" + revealAt, now);
  }

  private void reveal(Party party, Member member, long now, long revealAt)
  {
    if ("BUFFERING_RECOVERY".equals(member.directiveCause)
        && Math.abs(member.directiveTargetPosition
            - party.timeline.positionAt(revealAt)) > VISIBLE_DRIFT_DEADBAND_MS)
    {
      rebaseRecovery(party, member, now);
      return;
    }
    long latest = Math.min(member.directiveHeldSample + HELD_FRESHNESS_MS,
        member.directiveStartedAt + ACTIVATION_TIMEOUT_MS
            - presentationReserve(member, now));
    if (revealAt > now + MAX_REVEAL_LEAD_MS || revealAt > latest)
    {
      failDirective(party, member);
      return;
    }
    member.directiveExecuteAt = revealAt;
    member.directiveVisibleAt = revealAt;
    member.status = "ACTIVATING";
    roster(party, member);
    JsonObject out = directiveEvent("REVEAL", party, member);
    out.addProperty("executeAtServerMonoMs", revealAt);
    out.addProperty("targetPositionMs", member.directiveTargetPosition);
    out.addProperty("targetState", member.directiveTargetState);
    out.addProperty("targetRate", 1);
    member.peer.sendReveal(out, new PartyPlaybackDirective(
        member.directiveStateVersion, member.directiveCommandSeq, "REVEAL",
        member.directiveTargetPosition, member.directiveTargetState, 1, revealAt));
  }

  private void rebaseRecovery(Party party, Member member, long now)
  {
    JsonObject cancel = directiveEvent("CANCEL", party, member);
    cancel.addProperty("reason", "REBASE");
    if (member.recoveryAttemptId != null)
      cancel.addProperty("recoveryAttemptId", member.recoveryAttemptId);
    member.peer.send(cancel);
    member.peer.cancelScheduled();
    String recoveryAttemptId = member.recoveryAttemptId;
    long recoveryDeadline = member.recoveryDeadline;
    clearDirective(member);
    member.recoveryAttemptId = recoveryAttemptId;
    member.recoveryDeadline = recoveryDeadline;
    if (recoveryDeadline <= now + MIN_REVEAL_LEAD_MS
        + presentationReserve(member, now))
    {
      member.status = "LATE";
      roster(party, member);
      return;
    }
    JsonObject replacement =
        correction(party, member, now, "BUFFERING_RECOVERY", "HARD");
    member.peer.send(schedule(member, replacement, now, true));
  }

  static JsonObject directiveEvent(String type, String partyId, String directiveId,
      long stateVersion, long commandSeq)
  {
    JsonObject out = envelope(type);
    out.addProperty("partyId", partyId);
    out.addProperty("stateVersion", stateVersion);
    out.addProperty("commandSeq", commandSeq);
    out.addProperty("directiveId", directiveId);
    return out;
  }

  private static JsonObject directiveEvent(String type, Party party, Member member)
  {
    return directiveEvent(type, party.id, member.directiveId,
        member.directiveStateVersion, member.directiveCommandSeq);
  }

  private long presentationReserve(Member member, long now)
  {
    return Math.max(1000, Math.min(5000,
        member.presentationLatency.predictedTotalMs()
            + member.clock.p95(now)
            + Math.round(member.clock.uncertainty(now))));
  }

  private void presented(Party party, Member member, JsonObject request, long now)
  {
    SampleTime sampleTime = sampleTime(member, request, now);
    long sample = sampleTime.serverMonoMs();
    long position = number(request, "positionMs", 0, party.timeline.availableEndMs());
    long sampleUncertainty = number(request, "sampleUncertaintyMs", 0, 5000);
    String state = text(request, "state");
    if (!Set.of("PLAYING", "PAUSED").contains(state)
        || !state.equals(member.directiveTargetState))
      throw new Fault("INVALID_PRESENTATION");
    if (member.directiveNeedsPresentation
        && sample + VISIBLE_DRIFT_DEADBAND_MS < member.directiveExecuteAt)
      throw new Fault("EARLY_PRESENTATION");
    if (member.directiveBarrier && !member.directiveHeld)
      throw new Fault("INVALID_PRESENTATION");
    if ("PLAYING".equals(state))
    {
      boolean hasFrame = request.has("frameMonoMs");
      boolean hasCover = request.has("coverRemovedMonoMs");
      if (member.directiveBarrier && (!hasFrame || !hasCover))
        throw new Fault("INVALID_PRESENTATION");
      if (hasFrame != hasCover) throw new Fault("INVALID_PRESENTATION");
      if (hasFrame)
      {
        long frame = number(request, "frameMonoMs", 0, MAX_TIME);
        long cover = number(request, "coverRemovedMonoMs", 0, MAX_TIME);
        if (frame != sampleTime.clientMonoMs() || frame < cover)
          throw new Fault("INVALID_PRESENTATION");
      }
      long expected = party.timeline.positionAt(sample);
      long tolerance = Math.min(HARD_DRIFT_MS, Math.max(VISIBLE_DRIFT_DEADBAND_MS,
          68 + sampleUncertainty + Math.round(member.clock.uncertainty(now))));
      if (Math.abs(position - expected) > tolerance)
        throw new Fault("POSITION_MISMATCH");
    }
    else if (Math.abs(position - member.directiveTargetPosition)
        > VISIBLE_DRIFT_DEADBAND_MS)
      throw new Fault("POSITION_MISMATCH");
    if ("PAUSED".equals(state)
        && (request.has("frameMonoMs") || request.has("coverRemovedMonoMs")))
      throw new Fault("INVALID_PRESENTATION");

    member.lastHeartbeat = now;
    member.lastClientSample = sampleTime.clientMonoMs();
    member.lastSample = sample;
    member.lastPosition = position;
    member.lastState = state;
    member.bufferingRecoveryPending = false;
    if (member.directiveBarrier)
      member.presentationLatency.reveal(sample - member.directiveExecuteAt);
    if (party.canonicalCommandSeq == member.directiveCommandSeq)
    {
      try
      {
        party.timeline.establishPresentedFrame(
            member.directiveCommandSeq, position, sample);
      }
      catch (IllegalStateException failure)
      {
        throw new Fault("STALE_DIRECTIVE");
      }
      party.canonicalCommandSeq = -1;
    }
    acceptResumeSample(party, member, member.directiveStateVersion,
        member.directiveCommandSeq, sample, position);
    completeDirective(party, member);
  }

  private void acceptResumeSample(Party party, Member member, long stateVersion,
      long commandSeq, long sampleMonoMs, long positionMs)
  {
    ResumeSample candidate = new ResumeSample(
        stateVersion, commandSeq, sampleMonoMs, positionMs, member.id);
    if (newer(candidate, party.resumeSample)) party.resumeSample = candidate;
  }

  private static boolean newer(ResumeSample candidate, ResumeSample accepted)
  {
    if (accepted == null) return true;
    if (candidate.stateVersion() != accepted.stateVersion())
      return candidate.stateVersion() > accepted.stateVersion();
    if (candidate.commandSeq() != accepted.commandSeq())
      return candidate.commandSeq() > accepted.commandSeq();
    return candidate.sampleMonoMs() > accepted.sampleMonoMs();
  }

  private void completeDirective(Party party, Member member)
  {
    clearDirective(member);
    member.recoveryAttemptId = null;
    member.recoveryDeadline = -1;
    member.status = "ACTIVE";
    roster(party, member);
  }

  private void failDirective(Party party, Member member)
  {
    long failedSeq = member.directiveCommandSeq;
    if (member.peer != null) member.peer.cancelScheduled();
    clearDirective(member);
    member.recoveryAttemptId = null;
    member.recoveryDeadline = -1;
    member.status = "LATE";
    roster(party, member);
    if (failedSeq == party.barrierCommandSeq)
      revealSharedBarrier(party, time.getAsLong());
  }

  private void clearDirective(Member member)
  {
    member.directiveId = null;
    member.directiveStateVersion = -1;
    member.directiveCommandSeq = -1;
    member.directiveStartedAt = -1;
    member.directiveExecuteAt = -1;
    member.directiveTargetPosition = -1;
    member.directiveTargetState = null;
    member.directiveNeedsPresentation = false;
    member.directiveBarrier = false;
    member.directiveArmed = false;
    member.directivePrepareAt = -1;
    member.directiveVisibleAt = -1;
    member.directiveCause = null;
    member.directiveKind = null;
    member.directiveHeld = false;
    member.directiveHeldSample = -1;
    member.holdConfirmPending = false;
    member.holdConfirmBy = -1;
    member.directiveArmedPosition = -1;
  }

  private boolean requiresBarrier(String cause, String kind)
  {
    return "HARD".equals(kind) && !"INITIAL_ALIGNMENT".equals(cause)
        || Set.of("JUMP", "FF", "REW", "FF2", "REW2", "COMSKIP").contains(cause);
  }

  private boolean requiresPresentation(String cause, String targetState)
  {
    return "PLAYING".equals(targetState)
        || Set.of("PAUSE", "JUMP", "FF", "REW", "FF2", "REW2", "COMSKIP").contains(cause);
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
    if (party.timeline.pending() != null
        && (party.barrierCommandSeq < 0 || party.barrierRevealed))
      out.add("pendingCommand", commandJson(party.id, party.timeline.pending()));
    if (party.barrierCommandSeq >= 0 && !party.barrierRevealed)
    {
      out.addProperty("barrierPending", true);
      out.addProperty("barrierCommandSeq", party.barrierCommandSeq);
    }
    JsonArray roster = new JsonArray();
    for (Member member : party.members.values())
    {
      JsonObject entry = new JsonObject();
      entry.addProperty("participantId", member.id);
      entry.addProperty("status", member.status);
      entry.addProperty("prepared", member.prepared);
      entry.addProperty("clockConfident", member.clock.confident(now));
      entry.addProperty("readyForPlayback", playEligible(party, member, now));
      entry.addProperty("p95RttMs", member.clock.p95(now));
      entry.addProperty("ackCommandSeq", member.ackCommandSeq);
      entry.addProperty("presentationLatencyConfident", member.presentationLatency.confident());
      entry.addProperty("predictedPresentationLatencyMs",
          member.presentationLatency.predictedTotalMs());
      entry.addProperty("presentationUncertaintyMs",
          member.presentationLatency.uncertaintyMs());
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
    member.lastActivity = now;
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
    if (party == null) return;
    long now = time.getAsLong();
    boolean clockConfident = member.clock.confident(now);
    boolean readyForPlayback = playEligible(party, member, now);
    if (member.status.equals(member.notifiedStatus)
        && member.prepared == member.notifiedPrepared
        && clockConfident == member.notifiedClockConfident
        && readyForPlayback == member.notifiedReadyForPlayback)
      return;
    member.notifiedStatus = member.status;
    member.notifiedPrepared = member.prepared;
    member.notifiedClockConfident = clockConfident;
    member.notifiedReadyForPlayback = readyForPlayback;
    JsonObject out = envelope("PARTICIPANT_STATE");
    out.addProperty("partyId", party.id);
    out.addProperty("participantId", member.id);
    out.addProperty("status", member.status);
    out.addProperty("prepared", member.prepared);
    out.addProperty("clockConfident", clockConfident);
    out.addProperty("readyForPlayback", readyForPlayback);
    for (Member recipient : party.members.values())
      if (recipient != member && recipient.peer != null)
        recipient.peer.send(copy(out));
  }

  private void broadcast(Party party, JsonObject message)
  {
    for (Member member : party.members.values())
      if (member.peer != null) member.peer.send(copy(message));
  }

  private void pauseForHostExit(Party party, Member host, long now)
  {
    PartyCommand pending = party.timeline.pending();
    if ("PLAYING".equals(party.timeline.state())
        || pending != null && "PLAYING".equals(pending.targetState()))
      transition(party, host, "HOST_EXIT_PAUSE", now,
          party.timeline.positionAt(now + lead(party, now)), "PAUSED");
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

  private void persistResume(Party party, long now, Peer preferred,
      boolean ignoreHold, boolean ignoreCadence)
  {
    ResumeSample sample = party.resumeSample;
    if (sample == null || !ignoreHold && now < party.resumeHoldUntil) return;
    if (!newer(sample, party.persistedResumeSample)) return;
    if (!ignoreCadence && party.resumePersistedAt >= 0
        && now - party.resumePersistedAt < RESUME_COMMIT_INTERVAL_MS)
      return;
    Peer peer = preferred;
    if (peer == null)
    {
      Member source = party.members.get(sample.participantId());
      if (source != null) peer = source.peer;
    }
    if (peer == null)
      for (Member member : party.members.values())
        if (member.peer != null)
        {
          peer = member.peer;
          break;
        }
    if (peer == null) return;
    peer.commit(party.media, sample.positionMs());
    party.persistedResumeSample = sample;
    party.resumePersistedAt = now;
  }

  private void remove(Party party, Member member, boolean commit, long now)
  {
    if (commit) persistResume(party, now, member.peer, false, true);
    if (member.peer != null)
    {
      member.peer.cancelScheduled();
      member.peer.cancelPreparation();
      if (member.preparationBinding != null)
        member.peer.cancelPreparationBinding(member.preparationBinding);
      member.peer.progressOwner(false);
      member.peer.preparationOwner(false);
    }
    boolean wasHost = member.id.equals(party.host);
    expireResume(party, member, now);
    party.members.remove(member.id);
    member.status = "LEFT";
    roster(party, member);
    if (party.members.isEmpty())
    {
      expireParty(party, now);
      parties.remove(party.id);
      joinCodes.remove(party.id, now);
    }
    else if (wasHost) transfer(party, member);
  }

  private JsonObject end(Party party, boolean commit, String reason, long now,
      Peer responsePeer)
  {
    if (commit) persistResume(party, now, responsePeer, false, true);
    JsonObject out = envelope("ENDED");
    out.addProperty("partyId", party.id);
    out.addProperty("reason", reason);
    for (Member member : party.members.values())
      if (member.peer != null && member.peer != responsePeer)
        member.peer.send(copy(out));
    for (Member member : party.members.values())
    {
      expireResume(party, member, now);
      if (member.peer != null)
      {
        member.peer.cancelScheduled();
        member.peer.cancelPreparation();
        member.peer.progressOwner(false);
        member.peer.preparationOwner(false);
      }
      member.status = "LEFT";
    }
    party.members.clear();
    expireParty(party, now);
    parties.remove(party.id);
    joinCodes.remove(party.id, now);
    return out;
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
    persistResume(party, now, peer, true, true);
    party.resumeHoldUntil = now + RESUME_DISCONNECT_HOLD_MS;
    if (member.id.equals(party.host)) pauseForHostExit(party, member, now);
    peer.cancelScheduled();
    peer.cancelPreparation();
    if (member.preparationBinding != null)
      peer.cancelPreparationBinding(member.preparationBinding);
    peer.progressOwner(false);
    peer.preparationOwner(false);
    clearDirective(member);
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
    expiredParties.entrySet().removeIf(entry -> entry.getValue() <= now);
    expiredResumes.entrySet().removeIf(entry -> entry.getValue() <= now);
    for (Peer peer : new ArrayList<>(links.keySet())) peer.maintenance();
    for (Party party : new ArrayList<>(parties.values()))
    {
      if (party.barrierCommandSeq < 0 || party.barrierRevealed)
        party.timeline.advance(now);
      persistResume(party, now, null, false, false);
      for (Member member : new ArrayList<>(party.members.values()))
      {
        if (member.peer == null && member.disconnectedAt >= 0
            && now - member.disconnectedAt > RECONNECT_GRACE_MS)
        {
          remove(party, member, false, now);
          continue;
        }
        if (member.preparationId != null && !member.prepared
            && now - member.preparingSince > PREPARATION_TIMEOUT_MS)
        {
          String preparationId = member.preparationId;
          if (member.peer != null)
            preparationResult(member.peer, preparationId,
                PartyPlayerAdapter.PreparationResult.failed(-1, "NO_MEDIA_PROGRESS"));
          else
          {
            member.preparationId = null;
            member.preparationBinding = null;
            member.preparationBindingAck = false;
            member.preparationPlaybackSessionId = null;
            member.mediaOpened = false;
            member.preparationOpenReused = false;
            member.preparationReplayPending = false;
            member.preparedGeneration = -1;
            member.status = "LATE";
            roster(party, member);
          }
        }
        if (member.directiveId != null
            && now - member.directiveStartedAt > ACTIVATION_TIMEOUT_MS)
        {
          JsonObject error = envelope("ERROR");
          error.addProperty("code", "ACTIVATION_EXPIRED");
          error.addProperty("directiveId", member.directiveId);
          if (member.peer != null) member.peer.send(error);
          failDirective(party, member);
        }
        if (member.peer != null && "PLAYING".equals(party.timeline.state())
            && "ACTIVE".equals(member.status)
            && member.lastHeartbeat >= 0
            && now - member.lastHeartbeat > HEARTBEAT_STALE_MS)
        {
          member.status = "LATE";
          roster(party, member);
        }
        roster(party, member);
      }
      if (!parties.containsKey(party.id)) continue;
      boolean genuinelyConnected = false;
      long abandonedAt = -1;
      for (Member member : party.members.values())
      {
        if (member.peer != null
            && now - member.lastActivity <= CONNECTED_ACTIVITY_STALE_MS)
        {
          genuinelyConnected = true;
          break;
        }
        long memberAbandonedAt = member.peer != null
            ? member.lastActivity + CONNECTED_ACTIVITY_STALE_MS
            : member.disconnectedAt;
        abandonedAt = Math.max(abandonedAt, memberAbandonedAt);
      }
      if (genuinelyConnected)
        party.abandonedSince = -1;
      else if (party.abandonedSince < 0
          || abandonedAt >= 0 && abandonedAt < party.abandonedSince)
        party.abandonedSince = abandonedAt >= 0 ? abandonedAt : now;
      else if (now - party.abandonedSince > RECONNECT_GRACE_MS)
      {
        end(party, true, "ABANDONED", now, null);
        continue;
      }
      if (party.canonicalCommandSeq >= 0
          && party.members.values().stream().noneMatch(member ->
              member.directiveId != null
                  && member.directiveCommandSeq == party.canonicalCommandSeq))
        party.canonicalCommandSeq = -1;
      if (party.barrierCommandSeq >= 0
          && party.members.values().stream().noneMatch(member ->
              member.directiveId != null
                  && member.directiveCommandSeq == party.barrierCommandSeq))
      {
        party.barrierCommandSeq = -1;
        party.barrierVisibleAt = -1;
        party.barrierRevealed = false;
      }
      if (now - party.created > PARTY_LIFETIME_MS)
        end(party, true, "EXPIRED", now, null);
    }
  }

  /** Clears ephemeral Party authority without stopping ordinary playback. */
  public synchronized void shutdown()
  {
    for (Party party : new ArrayList<>(parties.values()))
      end(party, false, "SERVER_SHUTDOWN", time.getAsLong(), null);
    parties.clear();
    expiredParties.clear();
    expiredResumes.clear();
    links.clear();
    joinCodes.clear();
    joinAttempts.clear();
    codeIssues.clear();
    closed = true;
  }

  /** Completes one asynchronous ordinary-Watch preparation operation. */
  /** Completes one preparation with an adapter-owned result. */
  public synchronized void preparationResult(Peer peer, String preparationId,
      PartyPlayerAdapter.PreparationResult result)
  {
    Party party = find(peer);
    if (party == null || !eligible(peer)) return;
    Member member = requireMember(party, peer);
    if (member.preparationId == null || !member.preparationId.equals(preparationId)) return;
    Delivery delivery = null;
    member.prepared = result.loaded();
    if (member.prepared)
    {
      try { delivery = peer.preparedDelivery(party.media); }
      catch (Fault failure)
      {
        member.prepared = false;
        result = PartyPlayerAdapter.PreparationResult.failed(
            -1, "TRANSCODE_START_FAILED");
      }
    }
    member.status = member.prepared ? "PREPARING" : "LATE";
    JsonObject out = envelope("PREPARATION_RESULT");
    out.addProperty("partyId", party.id);
    out.addProperty("mediaFileId", party.media);
    out.addProperty("preparationId", preparationId);
    out.addProperty("preparationBinding", member.preparationBinding);
    out.addProperty("status", member.prepared ? "LOADED" : "FAILED");
    out.addProperty("watchCode", result.watchCode());
    if (member.prepared)
    {
      member.preparedGeneration = result.generation();
      out.addProperty("playbackGeneration", result.generation());
      out.add("delivery", deliveryJson(delivery));
    }
    if (!member.prepared)
    {
      peer.cancelPreparation();
      if (member.preparationBinding != null)
        peer.cancelPreparationBinding(member.preparationBinding);
      member.preparationId = null;
      member.preparationBinding = null;
      member.preparationBindingAck = false;
      member.preparationPlaybackSessionId = null;
      member.mediaOpened = false;
      member.preparationOpenReused = false;
      member.preparationReplayPending = false;
      member.preparedGeneration = -1;
      out.addProperty("code", result.failureCode());
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

  private void attemptJoin(Link link, String principal, String code, long now)
  {
    if (joinRetryAfter(link, principal, code, now) > 0)
      throw new Fault("JOIN_RATE_LIMITED");
    joinAttempts.addLast(now);
    link.joinAttempts.addLast(now);
    if (principalJoinLimitApplies(principal))
      principalJoinAttempts.computeIfAbsent(principal, key -> new ArrayDeque<>()).addLast(now);
    String codeKey = joinCodeKey(code);
    if (codeKey != null)
      codeJoinAttempts.computeIfAbsent(codeKey, key -> new ArrayDeque<>()).addLast(now);
  }

  private long joinRetryAfter(Link link, String principal, String code, long now)
  {
    long retryAfter = retryAfter(joinAttempts, JOIN_GLOBAL_LIMIT, now);
    if (link != null)
      retryAfter = Math.max(retryAfter,
          retryAfter(link.joinAttempts, JOIN_LINK_LIMIT, now));
    pruneJoinAttempts(principalJoinAttempts, now);
    if (principalJoinLimitApplies(principal))
      retryAfter = Math.max(retryAfter,
          retryAfter(principalJoinAttempts.get(principal), JOIN_PRINCIPAL_LIMIT, now));
    pruneJoinAttempts(codeJoinAttempts, now);
    String codeKey = joinCodeKey(code);
    if (codeKey != null)
      retryAfter = Math.max(retryAfter,
          retryAfter(codeJoinAttempts.get(codeKey), JOIN_CODE_LIMIT, now));
    return retryAfter;
  }

  private static long retryAfter(Deque<Long> attempts, int limit, long now)
  {
    if (attempts == null) return 0;
    while (!attempts.isEmpty() && attempts.peekFirst() <= now - JOIN_WINDOW_MS)
      attempts.removeFirst();
    return attempts.size() >= limit
        ? attempts.peekFirst() + JOIN_WINDOW_MS - now : 0;
  }

  private static void pruneJoinAttempts(Map<String, Deque<Long>> attempts, long now)
  {
    attempts.values().removeIf(values -> {
      retryAfter(values, Integer.MAX_VALUE, now);
      return values.isEmpty();
    });
  }

  private static boolean principalJoinLimitApplies(String principal)
  {
    return principal != null && !principal.isEmpty()
        && !"server-admitted-lan".equals(principal);
  }

  private static String joinCodeKey(String code)
  {
    if (code == null || code.length() != 3) return null;
    for (int i = 0; i < code.length(); i++)
      if (code.charAt(i) < '0' || code.charAt(i) > '9') return null;
    return code;
  }

  private static boolean constantEquals(String first, String second)
  {
    return java.security.MessageDigest.isEqual(
        first.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        second.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
  }

  private void requireContractRevision(Peer peer)
  {
    Link link = links.get(peer);
    if (link == null || link.contractRevision != CONTRACT_REVISION)
      throw new Fault("CONTRACT_REVISION_MISMATCH");
  }

  private String opaque()
  {
    byte[] bytes = new byte[24];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String preparationBinding()
  {
    byte[] bytes = new byte[16];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private void ackDiagnostic(Party party, Member member, AckPending pending,
      JsonObject request, long now, String result)
  {
    long sampleMono = diagnosticLong(request, "sampleMonoMs");
    long normalizedSample = sampleMono < 0 || member == null
        ? -1 : Math.round(sampleMono + member.clock.offset(now));
    System.out.println("PartyAck"
        + " party=" + (party == null ? "-" : party.id)
        + " participant=" + (member == null ? "-" : member.id)
        + " authenticated=" + (member != null && member.peer != null)
        + " directiveReceived=" + diagnosticText(request, "directiveId")
        + " directivePending=" + (pending.directiveId() == null ? "-" : pending.directiveId())
        + " stage=" + diagnosticText(request, "stage")
        + " stateVersionReceived=" + diagnosticLong(request, "stateVersion")
        + " stateVersionExpected=" + pending.stateVersion()
        + " commandSeqReceived=" + diagnosticLong(request, "commandSeq")
        + " commandSeqExpected=" + pending.commandSeq()
        + " requestId=" + diagnosticText(request, "requestId")
        + " requestSeq=" + diagnosticLong(request, "requestSeq")
        + " sampleMonoMs=" + sampleMono
        + " normalizedServerMonoMs=" + normalizedSample
        + " serverMonoMs=" + now
        + " positionMs=" + diagnosticLong(request, "positionMs")
        + " targetPositionMs=" + pending.targetPosition()
        + " toleranceMs=" + VISIBLE_DRIFT_DEADBAND_MS
        + " stateReceived=" + diagnosticText(request, "state")
        + " stateExpected=" + (pending.targetState() == null ? "-" : pending.targetState())
        + " result=" + result);
  }

  private static String diagnosticText(JsonObject request, String name)
  {
    try
    {
      return request != null && request.has(name) ? request.get(name).getAsString() : "-";
    }
    catch (RuntimeException failure)
    {
      return "<invalid>";
    }
  }

  private static long diagnosticLong(JsonObject request, String name)
  {
    try
    {
      return request != null && request.has(name) ? request.get(name).getAsLong() : -1;
    }
    catch (RuntimeException failure)
    {
      return -1;
    }
  }

  static String rejectionDiagnostic(JsonObject request, Fault failure)
  {
    return "PartyReject"
        + " type=" + diagnosticText(request, "type")
        + " requestId=" + diagnosticText(request, "requestId")
        + " requestSeq=" + diagnosticLong(request, "requestSeq")
        + " code=" + failure.getMessage()
        + " stage=" + failure.stage()
        + " field=" + failure.field()
        + " reason=" + failure.reason();
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

  private void addStaleStateDetails(JsonObject response, JsonObject request)
  {
    String partyId = diagnosticText(request, "partyId");
    Party party = parties.get(partyId);
    long suppliedVersion = diagnosticLong(request, "stateVersion");
    long suppliedSeq = diagnosticLong(request, "commandSeq");
    long authoritativeVersion = party == null ? -1 : party.timeline.stateVersion();
    long authoritativeSeq = party == null ? -1 : party.timeline.commandSeq();
    response.addProperty("requestType", diagnosticText(request, "type"));
    response.addProperty("partyId", partyId);
    response.addProperty("suppliedStateVersion", suppliedVersion);
    response.addProperty("suppliedCommandSeq", suppliedSeq);
    response.addProperty("authoritativeStateVersion", authoritativeVersion);
    response.addProperty("authoritativeCommandSeq", authoritativeSeq);
    System.out.println("PartyMode stale-state requestType=" + diagnosticText(request, "type")
        + " requestId=" + diagnosticText(request, "requestId")
        + " party=" + partyId
        + " suppliedVersion=" + suppliedVersion
        + " suppliedSeq=" + suppliedSeq
        + " authoritativeVersion=" + authoritativeVersion
        + " authoritativeSeq=" + authoritativeSeq);
  }

  private void expireResume(Party party, Member member, long now)
  {
    expiredResumes.put(resumeKey(party.id, member.id), now + TERMINAL_TOMBSTONE_MS);
  }

  private void expireParty(Party party, long now)
  {
    expiredParties.put(party.id, now + TERMINAL_TOMBSTONE_MS);
  }

  private static String resumeKey(String partyId, String participantId)
  {
    return partyId + '\u0000' + participantId;
  }
}
