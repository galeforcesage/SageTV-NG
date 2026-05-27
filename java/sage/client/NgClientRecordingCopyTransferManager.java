/*
 * Copyright 2015 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sage.client;

import sage.MediaFile;
import sage.Sage;
import sage.SeekerSelector;

/**
 * Server-side transfer session manager for NG recording copy downloads.
 */
public final class NgClientRecordingCopyTransferManager
{
  public static final String STATE_REQUESTED = "requested";
  public static final String STATE_QUEUED = "queued";
  public static final String STATE_TRANSFERRING = "transferring";
  public static final String STATE_PAUSED_BY_CLIENT = "paused_by_client";
  public static final String STATE_PAUSED_BY_SERVER = "paused_by_server";
  public static final String STATE_COMPLETED = "completed";
  public static final String STATE_CANCELED = "canceled";
  public static final String STATE_EXPIRED = "expired";
  public static final String STATE_ERROR = "error";

  public static final String CODE_CLAMPED_BY_PER_CLIENT_MAX = "CLAMPED_BY_PER_CLIENT_MAX";
  public static final String CODE_CLAMPED_BY_GLOBAL_MAX = "CLAMPED_BY_GLOBAL_MAX";
  public static final String CODE_BACKGROUND_DEFAULT_CAP = "BACKGROUND_DEFAULT_CAP";
  public static final String CODE_ACTIVE_RECORDING_BACKOFF = "ACTIVE_RECORDING_BACKOFF";
  public static final String CODE_STORAGE_IO_PRESSURE = "STORAGE_IO_PRESSURE";
  public static final String CODE_TOO_MANY_ACTIVE_CLIENTS = "TOO_MANY_ACTIVE_CLIENTS";
  public static final String CODE_SESSION_EXPIRED = "SESSION_EXPIRED";
  public static final String CODE_TOKEN_INVALID = "TOKEN_INVALID";
  public static final String CODE_RECORDING_STILL_GROWING = "RECORDING_STILL_GROWING";
  public static final String CODE_DISPATCH_FAILED = "DISPATCH_FAILED";
  public static final String CODE_URL_REFRESHED = "URL_REFRESHED";

  public static final class RequestedPolicy
  {
    public final String downloadMode;
    public final String rateProfile;
    public final long maxRateKbps;
    public final int concurrency;
    public final boolean wifiOnly;
    public final boolean allowMetered;

    public RequestedPolicy(String downloadMode, String rateProfile, long maxRateKbps,
        int concurrency, boolean wifiOnly, boolean allowMetered)
    {
      this.downloadMode = normalizeDownloadMode(downloadMode);
      this.rateProfile = normalizeRateProfile(rateProfile);
      this.maxRateKbps = Math.max(0L, maxRateKbps);
      this.concurrency = Math.max(1, concurrency);
      this.wifiOnly = wifiOnly;
      this.allowMetered = allowMetered;
    }

    public static RequestedPolicy createDefault(String mode)
    {
      return new RequestedPolicy(mode, "balanced", 0L, 1, false, true);
    }
  }

  public static final class AcceptedPolicy
  {
    public final String downloadMode;
    public final String rateProfile;
    public final long maxRateKbps;
    public final int concurrency;
    public final boolean wifiOnly;
    public final boolean allowMetered;

    public AcceptedPolicy(String downloadMode, String rateProfile, long maxRateKbps,
        int concurrency, boolean wifiOnly, boolean allowMetered)
    {
      this.downloadMode = downloadMode;
      this.rateProfile = rateProfile;
      this.maxRateKbps = maxRateKbps;
      this.concurrency = concurrency;
      this.wifiOnly = wifiOnly;
      this.allowMetered = allowMetered;
    }
  }

  public static final class PolicyAdjustment
  {
    public final String code;
    public final String message;

    public PolicyAdjustment(String code, String message)
    {
      this.code = code;
      this.message = message;
    }
  }

  public static final class TransferSession
  {
    public final long queueItemId;
    public final String sessionToken;
    public final String tokenHash;
    public final String clientName;
    public final String clientIp;
    public final String ngClientId;
    public final String ngVersion;
    public final int recordingId;
    public final String fileName;
    public final String filePath;
    public final long totalBytes;
    public final long reconnectGraceSeconds;
    public final long createdAt;
    public volatile long updatedAt;
    public volatile long expiresAt;
    public volatile String sessionState;
    public volatile long bytesTransferred;
    public volatile long effectiveRateKbps;
    public volatile long urlRevision;
    public volatile AcceptedPolicy acceptedPolicy;
    public final java.util.List<PolicyAdjustment> adjustments;
    public final java.util.List<String> recentReasonCodes;
    public final boolean recordingComplete;

    private TransferSession(long queueItemId, String sessionToken, String tokenHash, String clientName,
        String clientIp, String ngClientId, String ngVersion, int recordingId,
        String fileName, String filePath, long totalBytes, long reconnectGraceSeconds,
        long createdAt, long expiresAt, AcceptedPolicy acceptedPolicy,
        java.util.List<PolicyAdjustment> adjustments, String sessionState,
        boolean recordingComplete)
    {
      this.queueItemId = queueItemId;
      this.sessionToken = sessionToken;
      this.tokenHash = tokenHash;
      this.clientName = clientName;
      this.clientIp = clientIp;
      this.ngClientId = ngClientId;
      this.ngVersion = ngVersion;
      this.recordingId = recordingId;
      this.fileName = fileName;
      this.filePath = filePath;
      this.totalBytes = totalBytes;
      this.reconnectGraceSeconds = reconnectGraceSeconds;
      this.createdAt = createdAt;
      this.updatedAt = createdAt;
      this.expiresAt = expiresAt;
      this.sessionState = sessionState;
      this.bytesTransferred = 0L;
      this.effectiveRateKbps = 0L;
      this.urlRevision = 1L;
      this.acceptedPolicy = acceptedPolicy;
      this.adjustments = adjustments;
      this.recentReasonCodes = new java.util.ArrayList<String>();
      this.recordingComplete = recordingComplete;
    }

    public boolean isTerminal()
    {
      return STATE_COMPLETED.equals(sessionState) || STATE_CANCELED.equals(sessionState) ||
          STATE_EXPIRED.equals(sessionState) || STATE_ERROR.equals(sessionState);
    }
  }

  private static final NgClientRecordingCopyTransferManager INSTANCE =
      new NgClientRecordingCopyTransferManager();

  private final java.util.concurrent.ConcurrentHashMap<String, TransferSession> sessionsByToken =
      new java.util.concurrent.ConcurrentHashMap<String, TransferSession>();
    private final java.util.concurrent.atomic.AtomicLong queueItemSequence =
      new java.util.concurrent.atomic.AtomicLong(0L);

  private NgClientRecordingCopyTransferManager()
  {
    java.util.Timer cleaner = new java.util.Timer("NgTransferSessionCleanup", true);
    cleaner.scheduleAtFixedRate(new java.util.TimerTask()
    {
      public void run()
      {
        cleanupExpired();
      }
    }, 60L * 1000L, 60L * 1000L);
  }

  public static NgClientRecordingCopyTransferManager getInstance()
  {
    return INSTANCE;
  }

  public synchronized TransferSession createSession(String clientName, String clientIp,
      String ngClientId, String ngVersion, java.util.Set<String> clientCapabilities,
      MediaFile mf, RequestedPolicy requestedPolicy, long estimatedBandwidthBps)
  {
    cleanupExpired();
    if (mf == null)
      return null;

    java.io.File sourceFile = mf.getFile(0);
    if (sourceFile == null || !sourceFile.isFile())
      return null;

    RequestedPolicy requested = requestedPolicy == null ? RequestedPolicy.createDefault("background") : requestedPolicy;
    long now = Sage.time();
    long reconnectGraceSeconds = Math.max(30L,
        Sage.getLong("miniclient/transfer/reconnect_grace_seconds", 900L));
    long sessionTtlSeconds = Math.max(300L,
        Sage.getLong("miniclient/transfer/session_ttl_seconds", 86400L));

    NgClientDownloadTokenManager.TokenIssue tokenIssue =
        NgClientDownloadTokenManager.getInstance().issueToken(clientName, mf.getID(), sourceFile);

    if (tokenIssue == null || tokenIssue.token == null || tokenIssue.token.length() == 0)
      return null;

    java.util.List<PolicyAdjustment> adjustments = new java.util.ArrayList<PolicyAdjustment>();
    AcceptedPolicy accepted = clampPolicy(requested, estimatedBandwidthBps,
        hasRecordingPressure(sourceFile), hasStoragePressure(), adjustments);

    String state = shouldQueue(clientName) ? STATE_QUEUED : STATE_TRANSFERRING;
    if (STATE_QUEUED.equals(state))
      adjustments.add(new PolicyAdjustment(CODE_TOO_MANY_ACTIVE_CLIENTS,
          "Session queued due to active transfer limits."));

    boolean recordingComplete = mf.isCompleteRecording();
    if (!recordingComplete && !Sage.getBoolean("miniclient/transfer/allow_growing_recording_copy", false))
    {
      state = STATE_ERROR;
      adjustments.add(new PolicyAdjustment(CODE_RECORDING_STILL_GROWING,
          "Recording copy is blocked until recording is complete."));
    }

    TransferSession session = new TransferSession(
      queueItemSequence.incrementAndGet(),
        tokenIssue.token,
        tokenIssue.tokenHash,
        safe(clientName),
        safe(clientIp),
        safe(ngClientId),
        safe(ngVersion),
        mf.getID(),
        sourceFile.getName(),
        sourceFile.getAbsolutePath(),
        Math.max(0L, sourceFile.length()),
        reconnectGraceSeconds,
        now,
        Math.min(tokenIssue.expiresAt, now + sessionTtlSeconds * 1000L),
        accepted,
        adjustments,
        state,
        recordingComplete);

    addAdjustmentReasonCodes(session, adjustments);
    sessionsByToken.put(session.sessionToken, session);
    return session;
  }

  public synchronized TransferSession getSession(String token, String clientName)
  {
    cleanupExpired();
    if (token == null || token.length() == 0)
      return null;
    TransferSession session = sessionsByToken.get(token);
    if (session == null)
      return null;
    if (clientName != null && clientName.length() > 0 && !clientName.equals(session.clientName))
      return null;
    return session;
  }

  public synchronized TransferSession pauseByClient(String token, String clientName)
  {
    TransferSession session = getSession(token, clientName);
    if (session == null)
      return null;
    if (!session.isTerminal())
    {
      session.sessionState = STATE_PAUSED_BY_CLIENT;
      session.updatedAt = Sage.time();
      addReasonCode(session, "PAUSED_BY_CLIENT", "Paused by client request.");
    }
    return session;
  }

  public synchronized TransferSession pauseByServer(String token, String clientName,
      String reasonCode, String reasonMessage)
  {
    TransferSession session = getSession(token, clientName);
    if (session == null)
      return null;
    if (!session.isTerminal())
    {
      session.sessionState = STATE_PAUSED_BY_SERVER;
      session.updatedAt = Sage.time();
      addReasonCode(session, reasonCode == null ? CODE_STORAGE_IO_PRESSURE : reasonCode,
          reasonMessage == null ? reasonCode : reasonMessage);
    }
    return session;
  }

  public synchronized TransferSession resume(String token, String clientName,
      long offset, RequestedPolicy requestedPolicy, long estimatedBandwidthBps)
  {
    TransferSession session = getSession(token, clientName);
    if (session == null)
      return null;
    if (session.isTerminal())
      return session;

    if (offset >= 0L)
      session.bytesTransferred = Math.min(offset, session.totalBytes);

    if (requestedPolicy != null)
    {
      session.adjustments.clear();
      session.acceptedPolicy = clampPolicy(requestedPolicy, estimatedBandwidthBps,
          hasRecordingPressure(new java.io.File(session.filePath)), hasStoragePressure(), session.adjustments);
      addAdjustmentReasonCodes(session, session.adjustments);
    }

    session.sessionState = shouldQueue(clientName) ? STATE_QUEUED : STATE_TRANSFERRING;
    session.updatedAt = Sage.time();
    session.expiresAt = session.updatedAt + Math.max(300L,
        Sage.getLong("miniclient/transfer/session_ttl_seconds", 86400L)) * 1000L;
    session.urlRevision = Math.max(1L, session.urlRevision + 1L);
    addReasonCode(session, CODE_URL_REFRESHED,
      "Download URL refreshed for resume or policy update.");
    return session;
  }

  public synchronized TransferSession cancel(String token, String clientName)
  {
    TransferSession session = getSession(token, clientName);
    if (session == null)
      return null;
    if (!session.isTerminal())
    {
      session.sessionState = STATE_CANCELED;
      session.updatedAt = Sage.time();
      addReasonCode(session, "CANCELED_BY_CLIENT", "Canceled by client request.");
    }
    return session;
  }

  public synchronized TransferSession getLatestSessionForRecording(int recordingId,
      String clientName)
  {
    cleanupExpired();
    return findLatestSessionForRecordingLocked(recordingId, clientName);
  }

  public synchronized TransferSession pauseLatestByClientForRecording(int recordingId,
      String clientName)
  {
    TransferSession session = findLatestSessionForRecordingLocked(recordingId, clientName);
    if (session == null)
      return null;
    return pauseByClient(session.sessionToken, clientName);
  }

  public synchronized TransferSession resumeLatestForRecording(int recordingId,
      String clientName, RequestedPolicy requestedPolicy, long estimatedBandwidthBps)
  {
    TransferSession session = findLatestSessionForRecordingLocked(recordingId, clientName);
    if (session == null)
      return null;
    long resumeOffset = Math.max(0L, session.bytesTransferred);
    return resume(session.sessionToken, clientName, resumeOffset, requestedPolicy,
        estimatedBandwidthBps);
  }

  public synchronized TransferSession cancelLatestByClientForRecording(int recordingId,
      String clientName)
  {
    TransferSession session = findLatestSessionForRecordingLocked(recordingId, clientName);
    if (session == null)
      return null;
    return cancel(session.sessionToken, clientName);
  }

  public synchronized TransferSession markDispatchFailure(String token, String detail)
  {
    TransferSession session = sessionsByToken.get(token);
    if (session == null)
      return null;
    session.sessionState = STATE_ERROR;
    session.updatedAt = Sage.time();
    addReasonCode(session, CODE_DISPATCH_FAILED, detail);
    return session;
  }

  public synchronized void updateProgress(String token, long bytesTransferred,
      long effectiveRateKbps, String reasonCode)
  {
    TransferSession session = sessionsByToken.get(token);
    if (session == null)
      return;
    session.bytesTransferred = Math.max(0L, Math.min(bytesTransferred, session.totalBytes));
    session.effectiveRateKbps = Math.max(0L, effectiveRateKbps);
    session.updatedAt = Sage.time();
    if (reasonCode != null && reasonCode.length() > 0)
      addReasonCode(session, reasonCode, reasonCode);
    if (session.totalBytes > 0 && session.bytesTransferred >= session.totalBytes)
      session.sessionState = STATE_COMPLETED;
  }

  public synchronized void cleanupExpired()
  {
    long now = Sage.time();
    java.util.Iterator<java.util.Map.Entry<String, TransferSession>> walker =
        sessionsByToken.entrySet().iterator();
    while (walker.hasNext())
    {
      java.util.Map.Entry<String, TransferSession> ent = walker.next();
      TransferSession session = ent.getValue();
      if (session == null)
      {
        walker.remove();
        continue;
      }
      if (session.expiresAt <= now)
      {
        session.sessionState = STATE_EXPIRED;
        addReasonCode(session, CODE_SESSION_EXPIRED, "Transfer session expired.");
        walker.remove();
      }
    }
  }

  public String buildCapsAckJson(java.util.Set<String> clientCapabilities)
  {
    StringBuilder sb = new StringBuilder(384);
    sb.append('{');
    append(sb, "type", "CAPS_ACK");
    append(sb, "recording_copy", true);
    append(sb, "resume", true);
    append(sb, "pause_resume_cancel", true);
    appendObjectStart(sb, "policy_limits");
    append(sb, "max_concurrency_per_client", getMaxConcurrentPerClient());
    append(sb, "supports_explicit_max_rate_kbps", true);
    appendArray(sb, "rate_profiles", new String[] {"auto", "low", "balanced", "max"});
    closeObject(sb);
    append(sb, "server_transfer_api_version", 1L);
    appendArray(sb, "client_capabilities", clientCapabilities);
    closeObject(sb);
    return sb.toString();
  }

  public String buildSessionAckJson(TransferSession session)
  {
    if (session == null)
      return buildErrorJson("TRANSFER_SESSION_ERROR", CODE_TOKEN_INVALID, "No transfer session was created.");

    StringBuilder sb = new StringBuilder(2048);
    sb.append('{');
    append(sb, "type", "TRANSFER_SESSION_ACK");
    append(sb, "queue_item_id", session.queueItemId);
    append(sb, "session_token", session.sessionToken);
    append(sb, "recording_id", String.valueOf(session.recordingId));
    append(sb, "file_name", session.fileName);
    append(sb, "total_bytes", session.totalBytes);
    append(sb, "session_state", session.sessionState);
    append(sb, "download_url", "/api/transfers/" + escape(session.sessionToken) + "/content?v=" + session.urlRevision);
    append(sb, "resume_from_offset", session.bytesTransferred);
    appendAcceptedPolicy(sb, session.acceptedPolicy);
    appendPolicyAdjustments(sb, session.adjustments);
    append(sb, "recording_complete", session.recordingComplete);
    append(sb, "reconnect_grace_seconds", session.reconnectGraceSeconds);
    append(sb, "expires_in_seconds", Math.max(0L, (session.expiresAt - Sage.time()) / 1000L));
    appendArray(sb, "recent_reason_codes", session.recentReasonCodes);
    closeObject(sb);
    return sb.toString();
  }

  public String buildStatusJson(TransferSession session)
  {
    if (session == null)
      return buildErrorJson("TRANSFER_STATUS_ERROR", CODE_TOKEN_INVALID, "Transfer session not found.");

    StringBuilder sb = new StringBuilder(2048);
    sb.append('{');
    append(sb, "type", "TRANSFER_STATUS");
    append(sb, "queue_item_id", session.queueItemId);
    append(sb, "session_token", session.sessionToken);
    append(sb, "session_state", session.sessionState);
    append(sb, "download_url", "/api/transfers/" + escape(session.sessionToken) + "/content?v=" + session.urlRevision);
    append(sb, "bytes_transferred", session.bytesTransferred);
    append(sb, "total_bytes", session.totalBytes);
    append(sb, "effective_rate_kbps", session.effectiveRateKbps);
    appendAcceptedPolicy(sb, session.acceptedPolicy);
    appendObjectStart(sb, "server_conditions");
    append(sb, "recording_pressure", hasRecordingPressure(new java.io.File(session.filePath)));
    append(sb, "storage_backoff_active", hasStoragePressure());
    closeObject(sb);
    appendArray(sb, "recent_reason_codes", session.recentReasonCodes);
    closeObject(sb);
    return sb.toString();
  }

  public String buildControlAckJson(String type, TransferSession session)
  {
    if (session == null)
      return buildErrorJson(type + "_ERROR", CODE_TOKEN_INVALID, "Transfer session not found.");

    StringBuilder sb = new StringBuilder(512);
    sb.append('{');
    append(sb, "type", type);
    append(sb, "queue_item_id", session.queueItemId);
    append(sb, "session_token", session.sessionToken);
    append(sb, "session_state", session.sessionState);
    append(sb, "download_url", "/api/transfers/" + escape(session.sessionToken) + "/content?v=" + session.urlRevision);
    append(sb, "bytes_transferred", session.bytesTransferred);
    append(sb, "total_bytes", session.totalBytes);
    appendArray(sb, "recent_reason_codes", session.recentReasonCodes);
    closeObject(sb);
    return sb.toString();
  }

  public String buildErrorJson(String type, String code, String message)
  {
    StringBuilder sb = new StringBuilder(384);
    sb.append('{');
    append(sb, "type", type == null ? "TRANSFER_ERROR" : type);
    append(sb, "error_code", code == null ? CODE_TOKEN_INVALID : code);
    append(sb, "message", message == null ? "" : message);
    append(sb, "retriable", isRetriableError(code));
    closeObject(sb);
    return sb.toString();
  }

  public synchronized String buildQueueSnapshotJson()
  {
    java.util.ArrayList<TransferSession> sessions = getOrderedSessionsLocked();

    StringBuilder sb = new StringBuilder(Math.max(256, sessions.size() * 128));
    sb.append('{');
    append(sb, "type", "TRANSFER_QUEUE_SNAPSHOT");
    append(sb, "count", sessions.size());
    if (sb.charAt(sb.length() - 1) != '{') sb.append(',');
    sb.append('"').append("items").append('"').append(':').append('[');
    for (int i = 0; i < sessions.size(); i++)
    {
      TransferSession session = sessions.get(i);
      if (session == null)
        continue;
      if (i > 0)
        sb.append(',');
      sb.append('{');
      append(sb, "queue_item_id", session.queueItemId);
      append(sb, "session_token", session.sessionToken);
      append(sb, "session_state", session.sessionState);
      append(sb, "recording_id", String.valueOf(session.recordingId));
      append(sb, "file_name", session.fileName);
      append(sb, "bytes_transferred", session.bytesTransferred);
      append(sb, "total_bytes", session.totalBytes);
      append(sb, "effective_rate_kbps", session.effectiveRateKbps);
      append(sb, "download_url", "/api/transfers/" + escape(session.sessionToken) + "/content?v=" + session.urlRevision);
      append(sb, "created_at", session.createdAt);
      append(sb, "updated_at", session.updatedAt);
      append(sb, "client_name", session.clientName);
      append(sb, "ng_client_id", session.ngClientId);
      sb.append('}');
    }
    sb.append(']');
    closeObject(sb);
    return sb.toString();
  }

  public synchronized String buildQueueSnapshotSummaryText()
  {
    java.util.ArrayList<TransferSession> sessions = getOrderedSessionsLocked();

    if (sessions.isEmpty())
      return "None";

    StringBuilder sb = new StringBuilder(Math.max(64, sessions.size() * 48));
    int shown = 0;
    for (int i = 0; i < sessions.size(); i++)
    {
      TransferSession session = sessions.get(i);
      if (session == null)
        continue;
      if (shown > 0)
        sb.append("\n");
      shown++;
      long pct = session.totalBytes > 0 ? Math.min(100L, (session.bytesTransferred * 100L) / session.totalBytes) : 0L;
      sb.append('#').append(session.queueItemId).append(' ')
          .append(session.fileName == null ? "(unknown)" : session.fileName)
          .append(" [").append(session.sessionState == null ? "unknown" : session.sessionState).append(']')
          .append(' ').append(pct).append('%');
    }
    return sb.toString();
  }

  public synchronized int getQueueItemCount()
  {
    return getOrderedSessionsLocked().size();
  }

  public synchronized String getQueueItemLabel(int queueIndex)
  {
    TransferSession session = getQueueItemByIndex(queueIndex);
    if (session == null)
      return "";
    long pct = session.totalBytes > 0 ? Math.min(100L, (session.bytesTransferred * 100L) / session.totalBytes) : 0L;
    return '#' + String.valueOf(session.queueItemId) + " " +
        (session.fileName == null ? "(unknown)" : session.fileName) +
        " [" + (session.sessionState == null ? "unknown" : session.sessionState) + "] " +
        pct + "%";
  }

  public synchronized String getQueueItemProgressText(int queueIndex)
  {
    TransferSession session = getQueueItemByIndex(queueIndex);
    if (session == null)
      return "Not found";
    long pct = session.totalBytes > 0 ? Math.min(100L, (session.bytesTransferred * 100L) / session.totalBytes) : 0L;
    return pct + "% (" + session.bytesTransferred + " / " + session.totalBytes + " bytes)";
  }

  public synchronized TransferSession pauseQueueItem(int queueIndex)
  {
    TransferSession session = getQueueItemByIndex(queueIndex);
    if (session == null)
      return null;
    return pauseByClient(session.sessionToken, null);
  }

  public synchronized TransferSession cancelQueueItem(int queueIndex)
  {
    TransferSession session = getQueueItemByIndex(queueIndex);
    if (session == null)
      return null;
    return cancel(session.sessionToken, null);
  }

  public synchronized int pauseAllActiveTransfers(String reasonCode, String reasonMessage)
  {
    cleanupExpired();
    int changed = 0;
    java.util.Iterator<TransferSession> walker = sessionsByToken.values().iterator();
    while (walker.hasNext())
    {
      TransferSession session = walker.next();
      if (session == null || session.isTerminal())
        continue;
      if (STATE_TRANSFERRING.equals(session.sessionState) || STATE_QUEUED.equals(session.sessionState))
      {
        session.sessionState = STATE_PAUSED_BY_SERVER;
        session.updatedAt = Sage.time();
        addReasonCode(session,
            reasonCode == null || reasonCode.length() == 0 ? CODE_STORAGE_IO_PRESSURE : reasonCode,
            reasonMessage == null || reasonMessage.length() == 0 ? "Paused by server-wide queue action." : reasonMessage);
        changed++;
      }
    }
    return changed;
  }

  public synchronized int resumeAllPausedByServer()
  {
    cleanupExpired();
    int changed = 0;
    java.util.Iterator<TransferSession> walker = sessionsByToken.values().iterator();
    while (walker.hasNext())
    {
      TransferSession session = walker.next();
      if (session == null || session.isTerminal())
        continue;
      if (!STATE_PAUSED_BY_SERVER.equals(session.sessionState))
        continue;

      RequestedPolicy requested = requestedPolicyFromAccepted(session.acceptedPolicy);
      session.acceptedPolicy = clampPolicy(requested, 0L,
          hasRecordingPressure(new java.io.File(session.filePath)), hasStoragePressure(), session.adjustments);
      session.sessionState = shouldQueue(session.clientName) ? STATE_QUEUED : STATE_TRANSFERRING;
      session.updatedAt = Sage.time();
      session.urlRevision = Math.max(1L, session.urlRevision + 1L);
      addReasonCode(session, CODE_URL_REFRESHED,
          "Download URL refreshed when resuming from server pause.");
      changed++;
    }
    return changed;
  }

  public synchronized int clearCompletedTransfers()
  {
    cleanupExpired();
    int cleared = 0;
    java.util.Iterator<java.util.Map.Entry<String, TransferSession>> walker =
        sessionsByToken.entrySet().iterator();
    while (walker.hasNext())
    {
      java.util.Map.Entry<String, TransferSession> ent = walker.next();
      TransferSession session = ent.getValue();
      if (session == null)
      {
        walker.remove();
        cleared++;
        continue;
      }
      if (STATE_COMPLETED.equals(session.sessionState))
      {
        walker.remove();
        cleared++;
      }
    }
    return cleared;
  }

  private static RequestedPolicy requestedPolicyFromAccepted(AcceptedPolicy accepted)
  {
    if (accepted == null)
      return RequestedPolicy.createDefault("background");
    return new RequestedPolicy(accepted.downloadMode, accepted.rateProfile,
        accepted.maxRateKbps, accepted.concurrency, accepted.wifiOnly,
        accepted.allowMetered);
  }

  private java.util.ArrayList<TransferSession> getOrderedSessionsLocked()
  {
    cleanupExpired();
    java.util.ArrayList<TransferSession> sessions = new java.util.ArrayList<TransferSession>(sessionsByToken.values());
    java.util.Collections.sort(sessions, new java.util.Comparator<TransferSession>()
    {
      public int compare(TransferSession a, TransferSession b)
      {
        if (a == b)
          return 0;
        if (a == null)
          return -1;
        if (b == null)
          return 1;
        if (a.createdAt == b.createdAt)
          return a.queueItemId < b.queueItemId ? -1 : (a.queueItemId == b.queueItemId ? 0 : 1);
        return a.createdAt < b.createdAt ? -1 : 1;
      }
    });
    return sessions;
  }

  private TransferSession getQueueItemByIndex(int queueIndex)
  {
    if (queueIndex <= 0)
      return null;
    java.util.ArrayList<TransferSession> sessions = getOrderedSessionsLocked();
    if (queueIndex > sessions.size())
      return null;
    return sessions.get(queueIndex - 1);
  }

  private TransferSession findLatestSessionForRecordingLocked(int recordingId,
      String clientName)
  {
    if (recordingId <= 0)
      return null;
    TransferSession best = null;
    java.util.Iterator<TransferSession> walker = sessionsByToken.values().iterator();
    while (walker.hasNext())
    {
      TransferSession session = walker.next();
      if (session == null)
        continue;
      if (session.recordingId != recordingId)
        continue;
      if (clientName != null && clientName.length() > 0 &&
          !clientName.equals(session.clientName))
        continue;
      if (best == null || session.updatedAt > best.updatedAt ||
          (session.updatedAt == best.updatedAt && session.createdAt > best.createdAt))
        best = session;
    }
    return best;
  }

  private AcceptedPolicy clampPolicy(RequestedPolicy requested, long estimatedBandwidthBps,
      boolean recordingPressure, boolean storagePressure, java.util.List<PolicyAdjustment> adjustments)
  {
    long hardPerClient = Math.max(256L, Sage.getLong("miniclient/transfer/hard_max_per_client_kbps", 20000L));
    long hardGlobal = Math.max(256L, Sage.getLong("miniclient/transfer/hard_max_global_kbps", 50000L));
    long backgroundCap = Math.max(256L, Sage.getLong("miniclient/transfer/background_default_cap_kbps", 6000L));
    long activeRecordingCap = Math.max(256L, Sage.getLong("miniclient/transfer/active_recording_cap_kbps", 3000L));
    long storagePressureCap = Math.max(256L, Sage.getLong("miniclient/transfer/storage_pressure_cap_kbps", 2500L));

    long profileCap = deriveProfileCapKbps(requested.rateProfile, estimatedBandwidthBps, hardPerClient);
    long effectiveCap = requested.maxRateKbps > 0 ? Math.min(requested.maxRateKbps, profileCap) : profileCap;

    if (effectiveCap > hardPerClient)
    {
      effectiveCap = hardPerClient;
      adjustments.add(new PolicyAdjustment(CODE_CLAMPED_BY_PER_CLIENT_MAX,
          "Rate was clamped by per-client maximum."));
    }
    if (effectiveCap > hardGlobal)
    {
      effectiveCap = hardGlobal;
      adjustments.add(new PolicyAdjustment(CODE_CLAMPED_BY_GLOBAL_MAX,
          "Rate was clamped by global maximum."));
    }
    if ("background".equals(requested.downloadMode) && effectiveCap > backgroundCap)
    {
      effectiveCap = backgroundCap;
      adjustments.add(new PolicyAdjustment(CODE_BACKGROUND_DEFAULT_CAP,
          "Background transfers are capped below requested rate."));
    }
    if (recordingPressure && effectiveCap > activeRecordingCap)
    {
      effectiveCap = activeRecordingCap;
      adjustments.add(new PolicyAdjustment(CODE_ACTIVE_RECORDING_BACKOFF,
          "Active recording pressure reduced transfer ceiling."));
    }
    if (storagePressure && effectiveCap > storagePressureCap)
    {
      effectiveCap = storagePressureCap;
      adjustments.add(new PolicyAdjustment(CODE_STORAGE_IO_PRESSURE,
          "Storage pressure reduced transfer ceiling."));
    }

    return new AcceptedPolicy(requested.downloadMode, requested.rateProfile,
        Math.max(256L, effectiveCap),
        Math.min(requested.concurrency, getMaxConcurrentPerClient()),
        requested.wifiOnly, requested.allowMetered);
  }

  private long deriveProfileCapKbps(String rateProfile, long estimatedBandwidthBps, long hardPerClient)
  {
    String rp = normalizeRateProfile(rateProfile);
    if ("low".equals(rp))
      return Math.max(256L, Sage.getLong("miniclient/transfer/low_profile_kbps", 4000L));
    if ("balanced".equals(rp))
      return Math.max(256L, Sage.getLong("miniclient/transfer/balanced_profile_kbps", 10000L));
    if ("max".equals(rp))
      return hardPerClient;

    long estimateKbps = Math.max(256L, estimatedBandwidthBps / 1024L);
    long autoPct = Math.max(10L, Math.min(100L, Sage.getLong("miniclient/transfer/auto_profile_percent", 80L)));
    long autoCap = (estimateKbps * autoPct) / 100L;
    return Math.max(256L, Math.min(autoCap, hardPerClient));
  }

  private boolean shouldQueue(String clientName)
  {
    int activeForClient = 0;
    int activeGlobal = 0;
    java.util.Iterator<TransferSession> walker = sessionsByToken.values().iterator();
    while (walker.hasNext())
    {
      TransferSession session = walker.next();
      if (session == null || !STATE_TRANSFERRING.equals(session.sessionState))
        continue;
      activeGlobal++;
      if (clientName != null && clientName.equals(session.clientName))
        activeForClient++;
    }
    return activeForClient >= getMaxConcurrentPerClient() || activeGlobal >= getMaxConcurrentGlobal();
  }

  private int getMaxConcurrentPerClient()
  {
    return Math.max(1, Sage.getInt("miniclient/transfer/max_active_per_client", 1));
  }

  private int getMaxConcurrentGlobal()
  {
    return Math.max(1, Sage.getInt("miniclient/transfer/max_active_global", 2));
  }

  private boolean hasRecordingPressure(java.io.File targetFile)
  {
    try
    {
      MediaFile[] curr = SeekerSelector.getInstance().getCurrRecordFiles();
      if (curr == null || curr.length == 0)
        return false;
      if (targetFile == null)
        return true;
      String targetRoot = rootPath(targetFile);
      for (int i = 0; i < curr.length; i++)
      {
        MediaFile mf = curr[i];
        if (mf == null)
          continue;
        java.io.File f = mf.getRecordingFile();
        if (f == null)
          continue;
        if (targetRoot.equals(rootPath(f)))
          return true;
      }
    }
    catch (Throwable t)
    {
      if (Sage.DBG) System.out.println("Transfer manager recording pressure probe failed: " + t);
    }
    return false;
  }

  private boolean hasStoragePressure()
  {
    return Sage.getBoolean("miniclient/transfer/storage_pressure_active", false);
  }

  private static String rootPath(java.io.File f)
  {
    if (f == null)
      return "";
    java.io.File root = f;
    java.io.File parent = root.getParentFile();
    while (parent != null)
    {
      root = parent;
      parent = root.getParentFile();
    }
    String rv = root.getAbsolutePath();
    return rv == null ? "" : rv.toLowerCase();
  }

  private static boolean isRetriableError(String code)
  {
    if (code == null)
      return false;
    return CODE_TOO_MANY_ACTIVE_CLIENTS.equals(code) ||
        CODE_ACTIVE_RECORDING_BACKOFF.equals(code) ||
        CODE_STORAGE_IO_PRESSURE.equals(code);
  }

  private static String normalizeDownloadMode(String mode)
  {
    if (mode == null)
      return "background";
    String rv = mode.trim().toLowerCase();
    return "foreground".equals(rv) ? "foreground" : "background";
  }

  private static String normalizeRateProfile(String profile)
  {
    if (profile == null)
      return "auto";
    String rv = profile.trim().toLowerCase();
    if ("auto".equals(rv) || "low".equals(rv) || "balanced".equals(rv) || "max".equals(rv))
      return rv;
    return "auto";
  }

  private static void addAdjustmentReasonCodes(TransferSession session,
      java.util.List<PolicyAdjustment> adjustments)
  {
    for (int i = 0; i < adjustments.size(); i++)
      addReasonCode(session, adjustments.get(i).code, adjustments.get(i).message);
  }

  private static void addReasonCode(TransferSession session, String code, String message)
  {
    if (session == null || code == null || code.length() == 0)
      return;
    if (session.recentReasonCodes.contains(code))
      session.recentReasonCodes.remove(code);
    session.recentReasonCodes.add(code);
    while (session.recentReasonCodes.size() > 8)
      session.recentReasonCodes.remove(0);
    if (message != null && message.length() > 0)
      session.adjustments.add(new PolicyAdjustment(code, message));
  }

  private static String safe(String val)
  {
    return val == null ? "" : val;
  }

  private static void appendAcceptedPolicy(StringBuilder sb, AcceptedPolicy policy)
  {
    if (sb.charAt(sb.length() - 1) != '{') sb.append(',');
    sb.append('"').append("accepted_policy").append('"').append(':').append('{');
    if (policy != null)
    {
      append(sb, "download_mode", policy.downloadMode);
      append(sb, "rate_profile", policy.rateProfile);
      append(sb, "max_rate_kbps", policy.maxRateKbps);
      append(sb, "concurrency", policy.concurrency);
      append(sb, "wifi_only", policy.wifiOnly);
      append(sb, "allow_metered", policy.allowMetered);
    }
    sb.append('}');
  }

  private static void appendPolicyAdjustments(StringBuilder sb,
      java.util.List<PolicyAdjustment> adjustments)
  {
    if (sb.charAt(sb.length() - 1) != '{') sb.append(',');
    sb.append('"').append("policy_adjustments").append('"').append(':').append('[');
    boolean first = true;
    for (int i = 0; adjustments != null && i < adjustments.size(); i++)
    {
      PolicyAdjustment adj = adjustments.get(i);
      if (adj == null)
        continue;
      if (!first) sb.append(',');
      first = false;
      sb.append('{');
      append(sb, "code", adj.code);
      append(sb, "message", adj.message);
      sb.append('}');
    }
    sb.append(']');
  }

  private static void appendArray(StringBuilder sb, String key, String[] values)
  {
    if (sb.charAt(sb.length() - 1) != '{') sb.append(',');
    sb.append('"').append(escape(key)).append('"').append(':').append('[');
    for (int i = 0; values != null && i < values.length; i++)
    {
      if (i > 0) sb.append(',');
      sb.append('"').append(escape(values[i])).append('"');
    }
    sb.append(']');
  }

  private static void appendArray(StringBuilder sb, String key, java.util.Collection<String> values)
  {
    if (sb.charAt(sb.length() - 1) != '{') sb.append(',');
    sb.append('"').append(escape(key)).append('"').append(':').append('[');
    if (values != null)
    {
      boolean first = true;
      for (java.util.Iterator<String> it = values.iterator(); it.hasNext(); )
      {
        String val = it.next();
        if (val == null)
          continue;
        if (!first) sb.append(',');
        first = false;
        sb.append('"').append(escape(val)).append('"');
      }
    }
    sb.append(']');
  }

  private static void append(StringBuilder sb, String key, String value)
  {
    if (sb.charAt(sb.length() - 1) != '{') sb.append(',');
    sb.append('"').append(escape(key)).append('"').append(':')
        .append('"').append(escape(value == null ? "" : value)).append('"');
  }

  private static void append(StringBuilder sb, String key, long value)
  {
    if (sb.charAt(sb.length() - 1) != '{') sb.append(',');
    sb.append('"').append(escape(key)).append('"').append(':').append(value);
  }

  private static void append(StringBuilder sb, String key, int value)
  {
    append(sb, key, (long) value);
  }

  private static void append(StringBuilder sb, String key, boolean value)
  {
    if (sb.charAt(sb.length() - 1) != '{') sb.append(',');
    sb.append('"').append(escape(key)).append('"').append(':').append(value ? "true" : "false");
  }

  private static void appendObjectStart(StringBuilder sb, String key)
  {
    if (sb.charAt(sb.length() - 1) != '{') sb.append(',');
    sb.append('"').append(escape(key)).append('"').append(':').append('{');
  }

  private static void closeObject(StringBuilder sb)
  {
    sb.append('}');
  }

  private static String escape(String s)
  {
    if (s == null || s.length() == 0)
      return "";
    StringBuilder rv = new StringBuilder(s.length() + 16);
    for (int i = 0; i < s.length(); i++)
    {
      char c = s.charAt(i);
      switch (c)
      {
        case '\\': rv.append("\\\\"); break;
        case '"': rv.append("\\\""); break;
        case '\n': rv.append("\\n"); break;
        case '\r': rv.append("\\r"); break;
        case '\t': rv.append("\\t"); break;
        default: rv.append(c); break;
      }
    }
    return rv.toString();
  }
}
