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
package sage;

import sage.client.NgClientDownloadTokenManager;
import sage.client.NgClientOfflineCompanionBuilder;
import sage.client.NgClientRecordingCopyTransferManager;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class handles the server side portion of HTTP LiveStreaming to iOS clients. It takes a socket from the MCSR that has already had the first 8 bytes consumed
 * as part of the MCSR protocol. That will be the start of the GET request for the HTTP Live Session. The MCSR also passes in the UIManager so we can communicate with the
 * VideoFrame to get other data. As part of the URL, there will be a MediaFile ID, a segment #, client ID (MAC). We will then verify that MAC is already connected and that the
 * VideoFrame has the requested MediaFile open currently. That should be plenty for authentication.
 *
 * We then need to generate an m3u8 file for the initial request which has all of the bandwidth variants in it. Each of those items will also be an m3u8 file. When the individual
 * m3u8 file is requested, it will also then have a bandwidth tag in the URL so we know what rate to stream at. At that point we create an FFMPEGTranscoder with the desired target
 * rate in dynamic iOS mode (with special encoding parameters for higher-speed H264 encoding). One thing we still need to determine is if we have to specify the Content-Length ahead of
 * time or not. If we do, then we need to completely transcode a segment into a temp file on disk before we can fulfill the HTTP request. We will use 10 second segments by default, but
 * this should be configurable for testing of course. The individual m3u8 files will only have segments defined for the time span that is recorded for the active segment of the file.
 * The media player is destroyed and rebuilt when shifting between our recording segments, so we don't need to worry about that case. We also need to find out what happens for the HTTP request
 * if seeking is performed. We will analyze the timestamps that are produced by querying the transcoder directly....we should not need to utilize the Mpeg2FastReader class at all here, but
 * we may need to if the timestamps need to be more accurate.
 * @author Narflex
 */
public class HTTPLSServer implements Runnable
{
  private static final long TRANSFER_CACHE_MAX_BYTES =
      Sage.getLong("miniclient/transfer/cache_max_bytes", 500L * 1024L * 1024L);
  private static final String TRANSFER_CACHE_ROOT_DIR_NAME =
      Sage.get("miniclient/transfer/cache_dir_name", "ng_transfer_cache");
  private static final String TRANSFER_CACHE_ARTWORK_DIR_NAME = "artwork";
  private static final String TRANSFER_CACHE_METADATA_DIR_NAME = "metadata";
  private static final Object transferCacheLock = new Object();

  /** Creates a new instance of HTTPLSServer */
  public HTTPLSServer(java.nio.ByteBuffer bb, java.nio.channels.SocketChannel sake)
  {
    this(bb, sake, "GET");
  }

  public HTTPLSServer(java.nio.ByteBuffer bb, java.nio.channels.SocketChannel sake, String initialHttpMethod)
  {
    this.readBuf = bb;
    this.sake = sake;
    this.initialHttpMethod = (initialHttpMethod == null || initialHttpMethod.length() == 0) ? "GET" : initialHttpMethod.toUpperCase();
    timeout = Sage.getLong("http_timeout", 30000);
    Pooler.execute(this, "HTTPRequest", Thread.NORM_PRIORITY);
    writeBuf = java.nio.ByteBuffer.allocate(65536);
    String bwOptions = Sage.get("httpls_bandwidth_options", "160,320,864,64");
    java.util.StringTokenizer toker = new java.util.StringTokenizer(bwOptions, ",");
    bandwidths = new int[toker.countTokens()];
    int i = 0;
    while (toker.hasMoreTokens())
    {
      try
      {
        bandwidths[i++] = Integer.parseInt(toker.nextToken()) * 1000;
      }
      catch (NumberFormatException nfe)
      {
        if (Sage.DBG) System.out.println("Invalid HTTPLS bandwidth specified: " + nfe);
        bandwidths[i++] = 320;
      }
    }
    partDur = Sage.getInt("httpls_part_duration_sec", 5);
    synchronized (cleanerLock)
    {
      if (!builtCleaner)
      {
        builtCleaner = true;
        Thread t = new Thread(new HTTPLSCleaner(), "HTTPLSCleaner");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
      }
    }
  }

  // The main thing we do in this thread is process the HTTP requests and send their responses...we do it in a loop to handle HTTP Keep Alive properly
  public void run()
  {
    // The first request method bytes are already consumed by MiniClientSageRenderer.
    boolean skipInitialMethod = true;
    String requestMethod = initialHttpMethod;
    try
    {
      boolean keepAlive = false;
      do
      {
        StringBuffer sb = new StringBuffer();
        String getRequest = IOUtils.readLineBytes(sake, readBuf, timeout, sb).trim();
        if (!skipInitialMethod)
        {
          int firstSpace = getRequest.indexOf(' ');
          if (firstSpace <= 0)
          {
            if (Sage.DBG) System.out.println("Invalid HTTP request received of: " + getRequest);
            break;
          }
          requestMethod = getRequest.substring(0, firstSpace).trim().toUpperCase();
          if (!"GET".equals(requestMethod) && !"POST".equals(requestMethod))
          {
            if (Sage.DBG) System.out.println("Unsupported HTTP method received of: " + requestMethod);
            break;
          }
          getRequest = getRequest.substring(firstSpace + 1);
        }
        skipInitialMethod = false;
        int spaceIdx = getRequest.lastIndexOf(' ');
        String pageRequest = getRequest.substring(0, spaceIdx).trim();
        String httpVer = getRequest.substring(spaceIdx + 1).trim();
        String requestParam = IOUtils.readLineBytes(sake, readBuf, timeout, sb);
        boolean blankFound = false;
        java.util.HashMap paramMap = new java.util.HashMap();
        while (requestParam.length() > 0)
        {
          int colonIdx = requestParam.indexOf(':');
          if (colonIdx != -1)
            paramMap.put(requestParam.substring(0, colonIdx).trim().toLowerCase(),
                requestParam.substring(colonIdx + 1).trim());
          requestParam = IOUtils.readLineBytes(sake, readBuf, timeout, sb);
        }
        if (Sage.DBG) System.out.println("Complete HTTP request received! method=" + requestMethod +
          " page=" + pageRequest + " httpVer=" + httpVer + " params=" + sanitizeHeaderMapForLog(paramMap));

        keepAlive = "keep-alive".equalsIgnoreCase((String) paramMap.get("connection"));
        if (paramMap.containsKey("host"))
          myHost = (String) paramMap.get("host");

        byte[] requestBody = null;
        int contentLength = parseContentLength(paramMap);
        if (contentLength > 0)
          requestBody = readHttpRequestBody(contentLength);

        if (pageRequest.startsWith("/api/offline/"))
        {
          handleOfflineSnapshotRequest(pageRequest, requestMethod, paramMap, requestBody);
          continue;
        }

        if (pageRequest.startsWith("/api/transfers/"))
        {
          String refreshBody = requestBody == null ? "" : new String(requestBody, StandardCharsets.UTF_8);
          if (handleTransferRefreshRequest(pageRequest, paramMap, refreshBody))
            continue;
          handleTransferContentRequest(pageRequest, paramMap, requestMethod, requestBody);
          continue;
        }

        // Now determine which type of the 3 requests it is
        if (!pageRequest.startsWith("/iosstream_"))
        {
          if (Sage.DBG) System.out.println("Invalid page request-1 made for iOS HTTP server of: \"" + pageRequest + "\" abort connection!");
          break;
        }
        boolean isPlaylistRequest = pageRequest.endsWith(".m3u8");
        if (!isPlaylistRequest && !pageRequest.endsWith(".ts"))
        {
          if (Sage.DBG) System.out.println("Invalid page request-2 made for iOS HTTP server of: \"" + pageRequest + "\" abort connection!");
          break;
        }

        String subRequest = pageRequest.substring(10, pageRequest.lastIndexOf('.'));
        java.util.StringTokenizer toker = new java.util.StringTokenizer(subRequest, "_");
        if (toker.countTokens() != 4 && toker.countTokens() != 5)
        {
          if (Sage.DBG) System.out.println("Invalid page request-3 made for iOS HTTP server of: \"" + pageRequest + "\" abort connection!");
          break;
        }

        String clientMac = toker.nextToken();
        int mfId;
        try
        {
          mfId = Integer.parseInt(toker.nextToken());
        }
        catch (NumberFormatException nfe)
        {
          if (Sage.DBG) System.out.println("Invalid page request-4 made for iOS HTTP server of: \"" + pageRequest + "\" abort connection!");
          break;
        }
        int segmentNum;
        try
        {
          segmentNum = Integer.parseInt(toker.nextToken());
        }
        catch (NumberFormatException nfe)
        {
          if (Sage.DBG) System.out.println("Invalid page request-5 made for iOS HTTP server of: \"" + pageRequest + "\" abort connection!");
          break;
        }

        String bwStr = toker.nextToken();
        int bwkbps = 0;
        if (!"list".equals(bwStr))
        {
          try
          {
            bwkbps = Integer.parseInt(bwStr);
          }
          catch (NumberFormatException nfe)
          {
            if (Sage.DBG) System.out.println("Invalid page request-6 made for iOS HTTP server of: \"" + pageRequest + "\" abort connection!");
            break;
          }
        }

        String sessionID = (String)paramMap.get("x-playback-session-id");
        if (sessionID == null)
          sessionID = clientMac + "-" + mfId + "-" + segmentNum;

        MediaFile mf = Wizard.getInstance().getFileForID(mfId);
        if (mf == null)
        {
          if (Sage.DBG) System.out.println("Invalid MediaFileID in iOS HTTP Request of: " + mfId);
          break;
        }
        UIManager uiMgr = UIManager.getLocalUIByName(clientMac);
        if (Sage.getBoolean("httpls_require_client_connection", true) && uiMgr == null)
        {
          if (Sage.DBG) System.out.println("Invalid ClientMAC in iOS HTTP Request of: " + clientMac);
          break;
        }
        if (segmentNum >= mf.getNumSegments())
        {
          if (Sage.DBG) System.out.println("Invalid segment num for " + mf + " in iOS HTTP Request of: " + segmentNum);
          break;
        }
        if ("list".equals(bwStr) && isPlaylistRequest)
        {
          if (Sage.DBG) System.out.println("iOS HTTP Request for overall playlist for mf=" + mfId + " segment=" + segmentNum + " clientMac=" + clientMac);
          sb.setLength(0);
          // Build the string for the playlist response
          sb.append("#EXTM3U\r\n");
          for (int i = 0; i < bandwidths.length; i++)
          {
            sb.append("#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=" + bandwidths[i] + "\r\n");
            sb.append("http://" + myHost + "/iosstream_" + clientMac + "_" + mfId + "_" + segmentNum + "_" + bandwidths[i]/1000 + ".m3u8\r\n");
          }
          //sb.append("#EXT-X-ENDLIST\r\n");
          sendHTTPM3U8Response(sb.toString());
        }
        else if (isPlaylistRequest)
        {
          // Build the string for the playlist response
          sb.append("#EXTM3U\r\n");
          sb.append("#EXT-X-MEDIA-SEQUENCE:0\r\n");
          sb.append("#EXT-X-TARGETDURATION:" + partDur + "\r\n");
          long remTime = mf.getDuration(segmentNum) / 1000;
          int numParts = (int)Math.ceil(remTime / partDur);
          if (Sage.DBG) System.out.println("iOS HTTP Request for playlist at " + bwkbps + "kbps for mf=" + mfId + " segment=" + segmentNum + " clientMac=" + clientMac + " totalParts=" + numParts);
          int i = 0;
          while (remTime > 0)
          {
            long currDur = Math.min(remTime, partDur);
            remTime -= partDur;
            sb.append("#EXTINF:" + currDur + ",\r\n");
            sb.append("http://" + myHost + "/iosstream_" + clientMac + "_" + mfId + "_" + segmentNum + "_" + bwkbps + "_" + i++ + ".ts\r\n");
          }
          if (!mf.isRecording(segmentNum))
          {
            sb.append("#EXT-X-ENDLIST\r\n");
          }

          // Setup the transcoder now and prep the first 2 parts, this'll prevent it from thinking we have low bandwidth when it does the requests
          if (setupTranscoder(sessionID, mf, segmentNum, bwkbps, 0, uiMgr == null ? null : uiMgr.getVideoFrame()))
          {
            int prebufferPartNum = Math.max(0, xcode.lastRequestedPart - 1);
            if (Sage.DBG) System.out.println("Doing request for stream part " + prebufferPartNum + " to ensure it's buffered before we return to avoid bandwidth calculation issues...");
            xcode.transcoder.getSegmentFile(prebufferPartNum);
            xcode.transcoder.markSegmentConsumed(prebufferPartNum);
            xcode.lastActivityTime = Sage.time();
            if (xcode.transcoder.isTranscoding())
            {
              if (Sage.DBG) System.out.println("Doing request for stream part " + (prebufferPartNum+1) + " to ensure it's buffered before we return to avoid bandwidth calculation issues...");
              xcode.transcoder.getSegmentFile(prebufferPartNum+1);
              xcode.transcoder.markSegmentConsumed(prebufferPartNum+1);
              // Do yet another one if we're at the beginning to really ensure we have enough pre-buffered
              if (prebufferPartNum == 0 && xcode.transcoder.isTranscoding())
              {
                if (Sage.DBG) System.out.println("Doing request for stream part " + (prebufferPartNum+1) + " to ensure it's buffered before we return to avoid bandwidth calculation issues...");
                xcode.transcoder.getSegmentFile(prebufferPartNum+2);
                xcode.transcoder.markSegmentConsumed(prebufferPartNum+2);
              }
            }
          }

          if (Sage.DBG) System.out.println("Now sending back the individual bandwidth m3u8 file since we have the first 2 parts prepped...");
          sendHTTPM3U8Response(sb.toString());
        }
        else
        {
          int streamPart;
          try
          {
            streamPart = Integer.parseInt(toker.nextToken());
          }
          catch (NumberFormatException nfe)
          {
            if (Sage.DBG) System.out.println("Invalid page request-7 made for iOS HTTP server of: \"" + pageRequest + "\" abort connection!");
            break;
          }
          if (Sage.DBG) System.out.println("iOS HTTP Request for media stream part " + streamPart + " for mf=" + mfId + " segment=" + segmentNum + " clientMac=" + clientMac);

          setupTranscoder(sessionID, mf, segmentNum, bwkbps, streamPart, uiMgr == null ? null : uiMgr.getVideoFrame());
          xcode.lastRequestedPart = streamPart;

          // Now get the segment file that's requested and send it out!
          if (Sage.DBG) System.out.println("iOS HTTP server is requesting part # " + streamPart + " from the transcoder...");
          java.io.File targetFile = xcode.transcoder.getSegmentFile(streamPart);
          if (Sage.DBG) System.out.println("iOS HTTP server got part # " + streamPart + " from the transcoder, send it out:" + targetFile);
          if (targetFile == null)
            break;
          sendBackTSFile(targetFile);
          xcode.transcoder.markSegmentConsumed(streamPart);
          if (Sage.DBG) System.out.println("iOS HTTP server finished sending part # " + streamPart + " from the transcoder for: " + targetFile + " length=" + targetFile.length() +
              " rate=" + (targetFile.length()*8/(partDur*1000)) + " kbps");
        }
        if (xcode != null)
          xcode.lastActivityTime = Sage.time();
      } while (keepAlive);
    }
    catch (java.io.InterruptedIOException iioe)
    {
      if (Sage.DBG) System.out.println("TIMEOUT with HTTP socket...close it now");
    }
    catch (java.io.IOException ioe)
    {
      if (Sage.DBG) System.out.println("Error with HTTP socket of:" + ioe);
    }
    finally
    {
      try{sake.close();}catch(Exception e){}
    }
  }

  private String readHttpRequestBody(java.util.Map paramMap) throws java.io.IOException
  {
    String lenHeader = paramMap == null ? null : (String) paramMap.get("content-length");
    if (lenHeader == null || lenHeader.length() == 0)
      return "";

    int bodyLen;
    try
    {
      bodyLen = Integer.parseInt(lenHeader.trim());
    }
    catch (NumberFormatException nfe)
    {
      return "";
    }
    if (bodyLen <= 0)
      return "";

    byte[] body = new byte[bodyLen];
    int off = 0;

    if (readBuf != null && readBuf.hasRemaining())
    {
      int fromBuf = Math.min(readBuf.remaining(), bodyLen);
      readBuf.get(body, 0, fromBuf);
      off += fromBuf;
    }

    while (off < bodyLen)
    {
      java.nio.ByteBuffer target = java.nio.ByteBuffer.wrap(body, off, bodyLen - off);
      TimeoutHandler.registerTimeout(timeout, sake);
      int x = sake.read(target);
      TimeoutHandler.clearTimeout(sake);
      if (x < 0)
        throw new java.io.EOFException();
      off += x;
    }

    return new String(body, StandardCharsets.UTF_8);
  }

  private boolean handleTransferRefreshRequest(String pageRequest, java.util.Map paramMap,
      String requestBody) throws java.io.IOException
  {
    String cleanPath = pageRequest;
    int queryIdx = cleanPath.indexOf('?');
    if (queryIdx >= 0)
      cleanPath = cleanPath.substring(0, queryIdx);

    String tokenFromPath = null;
    if ("/api/transfers/refresh".equals(cleanPath))
    {
      // no token in path
    }
    else if (cleanPath.startsWith("/api/transfers/") && cleanPath.endsWith("/refresh"))
    {
      String middle = cleanPath.substring("/api/transfers/".length(), cleanPath.length() - "/refresh".length());
      if (middle.length() == 0 || middle.indexOf('/') != -1)
        return false;
      tokenFromPath = middle;
    }
    else
    {
      return false;
    }

    java.util.Map<String, String> bodyMap = parseSimpleJsonBody(requestBody);
    String bodyToken = firstNonEmptyTrimmed(
      bodyMap.get("session_token"),
      bodyMap.get("sessionToken"),
      bodyMap.get("transfer_token"),
      bodyMap.get("transferToken"),
      bodyMap.get("token"));
    String sessionToken = bodyToken.length() > 0 ? bodyToken : trimToEmpty(tokenFromPath);
    if (tokenFromPath != null && tokenFromPath.length() > 0 && bodyToken.length() > 0 && !tokenFromPath.equals(bodyToken))
    {
      sendTransferErrorResponse(401, "Unauthorized", "TRANSFER_TOKEN_INVALID",
          "Session token mismatch between path and body.", false);
      return true;
    }

    int recordingId = parseIntSafe(firstNonEmptyTrimmed(
        bodyMap.get("recording_id"),
        bodyMap.get("recordingId"),
        bodyMap.get("media_file_id"),
        bodyMap.get("mediaFileID"),
        bodyMap.get("mediaId")), -1);
    long bytesTransferred = parseLongSafe(firstNonEmptyTrimmed(
        bodyMap.get("bytes_transferred"),
        bodyMap.get("bytesTransferred"),
        bodyMap.get("offset"),
        "",
        ""), -1L);

    String ngClientId = firstNonEmptyTrimmed(
        bodyMap.get("ng_client_id"),
        bodyMap.get("ngClientId"),
        bodyMap.get("client_id"),
        bodyMap.get("clientId"),
        "");
    if (ngClientId.length() == 0)
      ngClientId = trimToEmpty((String) paramMap.get("x-ng-client-id"));
    if (ngClientId.length() == 0)
      ngClientId = trimToEmpty((String) paramMap.get("x-client-id"));
    if (ngClientId.length() > 0)
      paramMap.put("x-ng-client-id", ngClientId);

    String hintedClientName = firstNonEmptyTrimmed(
        bodyMap.get("client_name"),
        bodyMap.get("clientName"),
        "",
        "",
        "");
    String correlationId = firstNonEmptyTrimmed(
      bodyMap.get("correlation_id"),
      bodyMap.get("correlationId"),
      trimToEmpty((String) paramMap.get("x-correlation-id")),
      "",
      "");
    String ngVersion = firstNonEmptyTrimmed(
        bodyMap.get("ng_version"),
        bodyMap.get("ngVersion"),
        "",
        "",
        "");

    NgClientRecordingCopyTransferManager transferMgr = NgClientRecordingCopyTransferManager.getInstance();
    NgClientRecordingCopyTransferManager.TransferSession session = null;
    if (sessionToken.length() > 0)
      session = transferMgr.getSession(sessionToken, null);

    int effectiveRecordingId = recordingId > 0 ? recordingId : (session == null ? 0 : session.recordingId);
    if (correlationId.length() == 0)
      correlationId = java.util.UUID.randomUUID().toString();
    int coalescedWaitMs = Math.max(0,
        Sage.getInt("miniclient/transfer/refresh_coalesced_wait_ms", 5000));
    long coalescedWaitDeadline = Sage.time() + coalescedWaitMs;
    NgClientRecordingCopyTransferManager.RefreshAttemptPermit refreshPermit;
    while (true)
    {
      refreshPermit = transferMgr.beginRefreshAttempt(Math.max(0, effectiveRecordingId), correlationId, "http");
      if (refreshPermit.allowed)
        break;

      if (!"COALESCED_ACTIVE".equals(refreshPermit.blockCode) || coalescedWaitMs <= 0)
      {
        sendTransferErrorResponse(429, "Too Many Requests", "TRANSFER_REFRESH_THROTTLED",
            "Refresh request deferred by guardrail: " + refreshPermit.blockCode + ".", true);
        return true;
      }

      long remainingMs = coalescedWaitDeadline - Sage.time();
      if (remainingMs <= 0)
      {
        sendTransferErrorResponse(429, "Too Many Requests", "TRANSFER_REFRESH_THROTTLED",
            "Refresh request coalesced while another refresh was active.", true);
        return true;
      }

      try
      {
        Thread.sleep(Math.min(150L, remainingMs));
      }
      catch (InterruptedException ie)
      {
        Thread.currentThread().interrupt();
        sendTransferErrorResponse(429, "Too Many Requests", "TRANSFER_REFRESH_THROTTLED",
            "Refresh request wait was interrupted.", true);
        return true;
      }
    }
    correlationId = refreshPermit.correlationId;

    String finalResultType = "ERROR";
    String finalErrorCode = "UNKNOWN";
    boolean finalRetriable = false;
    int finalAttemptCount = 1;
    int laneFallbackCount = 0;
    try
    {
    if (session != null)
    {
      if (!isTransferRequesterBoundToSession(session, paramMap))
      {
        sendTransferErrorResponse(401, "Unauthorized", "TRANSFER_CLIENT_MISMATCH",
            "Transfer token is not valid for this client session.", false);
        finalResultType = "ERROR";
        finalErrorCode = "TRANSFER_CLIENT_MISMATCH";
        finalRetriable = false;
        return true;
      }

      long resumeOffset = bytesTransferred >= 0 ? bytesTransferred : Math.max(0L, session.bytesTransferred);
      NgClientRecordingCopyTransferManager.RequestedPolicy reqPolicy =
          session.acceptedPolicy == null ? null :
              new NgClientRecordingCopyTransferManager.RequestedPolicy(
                  session.acceptedPolicy.downloadMode,
                  session.acceptedPolicy.rateProfile,
                  session.acceptedPolicy.maxRateKbps,
                  session.acceptedPolicy.concurrency,
                  session.acceptedPolicy.wifiOnly,
                  session.acceptedPolicy.allowMetered);

      NgClientRecordingCopyTransferManager.TransferSession resumed = null;
      int maxAttempts = NgClientRecordingCopyTransferManager.getRefreshMaxAttempts();
      for (int attempt = 1; attempt <= maxAttempts; attempt++)
      {
        finalAttemptCount = attempt;
        resumed = transferMgr.resume(
            session.sessionToken,
            session.clientName,
            resumeOffset,
            reqPolicy,
            0L);
        if (resumed != null)
          break;
        if (attempt < maxAttempts && !NgClientRecordingCopyTransferManager.sleepRefreshRetryBackoff(attempt))
          break;
      }
      if (resumed == null)
      {
        sendTransferErrorResponse(401, "Unauthorized", "TRANSFER_TOKEN_INVALID",
            "Transfer token is invalid or expired.", true);
        finalResultType = "ERROR";
        finalErrorCode = "TRANSFER_TOKEN_INVALID";
        finalRetriable = true;
        return true;
      }

      if (NgClientRecordingCopyTransferManager.STATE_PAUSED_BY_CLIENT.equals(resumed.sessionState) ||
          NgClientRecordingCopyTransferManager.STATE_PAUSED_BY_SERVER.equals(resumed.sessionState) ||
          NgClientRecordingCopyTransferManager.STATE_QUEUED.equals(resumed.sessionState))
      {
        sendTransferErrorResponse(409, "Conflict", "TRANSFER_BUSY",
            "Transfer refresh request could not be processed right now.", true);
        finalResultType = "STATUS";
        finalErrorCode = "TRANSFER_BUSY";
        finalRetriable = true;
        return true;
      }

      // HTTP refresh responses are not command-channel size constrained, so include the
      // full inline manifest and avoid forcing a follow-up metadata fetch.
      sendHTTPJsonResponse(200, "OK", transferMgr.buildSessionAckJson(resumed), "application/json");
      finalResultType = "ACK";
      finalErrorCode = "";
      finalRetriable = false;
      return true;
    }

    if (recordingId <= 0)
    {
      sendTransferErrorResponse(401, "Unauthorized", "TRANSFER_TOKEN_INVALID",
          "Session token is invalid and recording_id was not provided.", true);
      finalResultType = "ERROR";
      finalErrorCode = "TRANSFER_TOKEN_INVALID";
      finalRetriable = true;
      return true;
    }

    MediaFile mf = Wizard.getInstance().getFileForID(recordingId);
    if (mf == null)
    {
      sendTransferErrorResponse(404, "Not Found", "TRANSFER_MEDIA_NOT_FOUND",
          "Media file is no longer available.", false);
      finalResultType = "ERROR";
      finalErrorCode = "TRANSFER_MEDIA_NOT_FOUND";
      finalRetriable = false;
      return true;
    }

    String clientName = hintedClientName.length() > 0 ? hintedClientName : "ng-http-refresh";
    String clientIp = "";
    try
    {
      if (sake != null && sake.socket() != null && sake.socket().getInetAddress() != null)
        clientIp = sake.socket().getInetAddress().getHostAddress();
    }
    catch (Throwable t)
    {
      if (Sage.DBG) System.out.println("Failed resolving refresh requester IP: " + t);
    }

    String transferBaseUrl = buildTransferBaseUrlFromRequest(paramMap);
    NgClientRecordingCopyTransferManager.RequestedPolicy defaultPolicy =
        new NgClientRecordingCopyTransferManager.RequestedPolicy(
            "foreground", "balanced", 0L, 1, false, true);
    NgClientRecordingCopyTransferManager.TransferSession created = null;
    int maxAttempts = NgClientRecordingCopyTransferManager.getRefreshMaxAttempts();
    for (int attempt = 1; attempt <= maxAttempts; attempt++)
    {
      finalAttemptCount = attempt;
      created = transferMgr.createSession(
          clientName,
          clientIp,
          ngClientId,
          ngVersion,
          transferBaseUrl,
          null,
          mf,
          defaultPolicy,
          0L);
      if (created != null)
        break;
      if (attempt < maxAttempts && !NgClientRecordingCopyTransferManager.sleepRefreshRetryBackoff(attempt))
        break;
    }

    if (created == null)
    {
      sendTransferErrorResponse(404, "Not Found", "TRANSFER_MEDIA_MISSING",
          "Media file is missing.", false);
      finalResultType = "ERROR";
      finalErrorCode = "TRANSFER_MEDIA_MISSING";
      finalRetriable = false;
      return true;
    }

    if (bytesTransferred >= 0)
      created.bytesTransferred = Math.min(Math.max(0L, bytesTransferred), Math.max(0L, created.totalBytes));

    if (NgClientRecordingCopyTransferManager.STATE_PAUSED_BY_CLIENT.equals(created.sessionState) ||
        NgClientRecordingCopyTransferManager.STATE_PAUSED_BY_SERVER.equals(created.sessionState) ||
        NgClientRecordingCopyTransferManager.STATE_QUEUED.equals(created.sessionState))
    {
      sendTransferErrorResponse(409, "Conflict", "TRANSFER_BUSY",
          "Transfer refresh request could not be processed right now.", true);
      finalResultType = "STATUS";
      finalErrorCode = "TRANSFER_BUSY";
      finalRetriable = true;
      return true;
    }

    // HTTP refresh responses are not command-channel size constrained, so include the
    // full inline manifest and avoid forcing a follow-up metadata fetch.
    sendHTTPJsonResponse(200, "OK", transferMgr.buildSessionAckJson(created), "application/json");
    finalResultType = "ACK";
    finalErrorCode = "";
    finalRetriable = false;
    return true;
    }
    finally
    {
      transferMgr.finishRefreshAttempt(refreshPermit, finalResultType, finalErrorCode,
          finalRetriable, laneFallbackCount, finalAttemptCount, Math.max(0, effectiveRecordingId));
    }
  }

  private String buildTransferBaseUrlFromRequest(java.util.Map paramMap)
  {
    String host = trimToEmpty((String) paramMap.get("host"));
    if (host.length() == 0)
      return "";
    String proto = trimToEmpty((String) paramMap.get("x-forwarded-proto"));
    if (proto.length() == 0)
      proto = "http";
    return proto + "://" + host;
  }

  private java.util.Map<String, String> parseSimpleJsonBody(String body)
  {
    java.util.HashMap<String, String> rv = new java.util.HashMap<String, String>();
    if (body == null || body.length() == 0)
      return rv;

    String normalized = normalizeRefreshLikePayload(body);
    if (normalized == null || normalized.length() == 0)
      return rv;

    extractJsonField(normalized, "recording_id", rv);
    extractJsonField(normalized, "recordingId", rv);
    extractJsonField(normalized, "media_file_id", rv);
    extractJsonField(normalized, "mediaFileID", rv);
    extractJsonField(normalized, "mediaFileId", rv);
    extractJsonField(normalized, "mediaId", rv);

    extractJsonField(normalized, "session_token", rv);
    extractJsonField(normalized, "sessionToken", rv);
    extractJsonField(normalized, "transfer_token", rv);
    extractJsonField(normalized, "transferToken", rv);
    extractJsonField(normalized, "token", rv);

    extractJsonField(normalized, "bytes_transferred", rv);
    extractJsonField(normalized, "bytesTransferred", rv);
    extractJsonField(normalized, "offset", rv);

    extractJsonField(normalized, "correlation_id", rv);
    extractJsonField(normalized, "correlationId", rv);
    extractJsonField(normalized, "ng_client_id", rv);
    extractJsonField(normalized, "ngClientId", rv);
    extractJsonField(normalized, "client_id", rv);
    extractJsonField(normalized, "clientId", rv);
    extractJsonField(normalized, "client_name", rv);
    extractJsonField(normalized, "clientName", rv);
    extractJsonField(normalized, "ng_version", rv);
    extractJsonField(normalized, "ngVersion", rv);

    if (rv.isEmpty())
      parseSimpleFormBody(normalized, rv);

    return rv;
  }

  private void parseSimpleFormBody(String body, java.util.Map<String, String> out)
  {
    if (body == null || body.length() == 0 || out == null)
      return;
    int amp = body.indexOf('&');
    int eq = body.indexOf('=');
    if (amp < 0 || eq < 1)
      return;
    java.util.StringTokenizer toker = new java.util.StringTokenizer(body, "&");
    while (toker.hasMoreTokens())
    {
      String pair = toker.nextToken();
      int idx = pair.indexOf('=');
      if (idx <= 0)
        continue;
      String k = pair.substring(0, idx).trim();
      String v = pair.substring(idx + 1).trim();
      if (k.length() == 0)
        continue;
      try
      {
        v = java.net.URLDecoder.decode(v, "UTF-8");
      }
      catch (Throwable t)
      {
      }
      out.put(k, v);
    }
  }

  private String normalizeRefreshLikePayload(String payload)
  {
    if (payload == null)
      return "";

    String rv = payload.trim();
    if (rv.length() >= 2 && rv.charAt(0) == '"' && rv.charAt(rv.length() - 1) == '"')
      rv = rv.substring(1, rv.length() - 1);
    if (rv.indexOf("\\\"") != -1)
      rv = rv.replace("\\\"", "\"");
    if (rv.indexOf("\\\\") != -1)
      rv = rv.replace("\\\\", "\\");
    if (rv.indexOf('%') != -1)
    {
      try
      {
        rv = java.net.URLDecoder.decode(rv, "UTF-8");
      }
      catch (Throwable t)
      {
      }
    }
    return rv;
  }

  private String firstNonEmptyTrimmed(String a, String b, String c, String d, String e)
  {
    String ta = trimToEmpty(a);
    if (ta.length() > 0) return ta;
    String tb = trimToEmpty(b);
    if (tb.length() > 0) return tb;
    String tc = trimToEmpty(c);
    if (tc.length() > 0) return tc;
    String td = trimToEmpty(d);
    if (td.length() > 0) return td;
    return trimToEmpty(e);
  }

  private void extractJsonField(String json, String key, java.util.Map<String, String> out)
  {
    if (json == null || key == null || key.length() == 0)
      return;
    String needle = "\"" + key + "\"";
    int idx = json.indexOf(needle);
    if (idx < 0)
      return;
    int colon = json.indexOf(':', idx + needle.length());
    if (colon < 0)
      return;
    int i = colon + 1;
    while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
    if (i >= json.length())
      return;

    char c = json.charAt(i);
    String value = "";
    if (c == '"')
    {
      i++;
      StringBuilder sb = new StringBuilder();
      boolean escaped = false;
      while (i < json.length())
      {
        char ch = json.charAt(i++);
        if (escaped)
        {
          switch (ch)
          {
            case 'n': sb.append('\n'); break;
            case 'r': sb.append('\r'); break;
            case 't': sb.append('\t'); break;
            case '"': sb.append('"'); break;
            case '\\': sb.append('\\'); break;
            default: sb.append(ch); break;
          }
          escaped = false;
        }
        else if (ch == '\\')
        {
          escaped = true;
        }
        else if (ch == '"')
        {
          break;
        }
        else
        {
          sb.append(ch);
        }
      }
      value = sb.toString();
    }
    else
    {
      int end = i;
      while (end < json.length())
      {
        char ch = json.charAt(end);
        if (ch == ',' || ch == '}' || Character.isWhitespace(ch))
          break;
        end++;
      }
      value = json.substring(i, end).trim();
      if ("null".equalsIgnoreCase(value))
        value = "";
    }
    out.put(key, value);
  }

  private String trimToEmpty(String s)
  {
    return s == null ? "" : s.trim();
  }

  private String extractRemoteAddress()
  {
    try
    {
      if (sake != null && sake.socket() != null && sake.socket().getInetAddress() != null)
        return trimToEmpty(sake.socket().getInetAddress().getHostAddress());
    }
    catch (Throwable t)
    {
    }
    return "";
  }

  private int parseIntSafe(String s, int fallback)
  {
    if (s == null || s.length() == 0)
      return fallback;
    try
    {
      return Integer.parseInt(s.trim());
    }
    catch (NumberFormatException nfe)
    {
      return fallback;
    }
  }

  private long parseLongSafe(String s, long fallback)
  {
    if (s == null || s.length() == 0)
      return fallback;
    try
    {
      return Long.parseLong(s.trim());
    }
    catch (NumberFormatException nfe)
    {
      return fallback;
    }
  }

    private void handleTransferContentRequest(String pageRequest, java.util.Map paramMap,
      String requestMethod, byte[] requestBody)
      throws java.io.IOException
  {
    String cleanPath = pageRequest;
    int queryIdx = cleanPath.indexOf('?');
    if (queryIdx >= 0)
      cleanPath = cleanPath.substring(0, queryIdx);

    String prefix = "/api/transfers/";
    if (!cleanPath.startsWith(prefix))
    {
      sendTransferErrorResponse(404, "Not Found", "TRANSFER_ENDPOINT_NOT_FOUND",
          "Unknown transfer endpoint.", false);
      return;
    }

    String tokenAndSuffix = cleanPath.substring(prefix.length());
    int slashIdx = tokenAndSuffix.indexOf('/');
    if (slashIdx <= 0)
    {
      sendTransferErrorResponse(400, "Bad Request", "TRANSFER_TOKEN_MISSING",
          "Missing transfer token.", false);
      return;
    }

    String token = tokenAndSuffix.substring(0, slashIdx);
    String suffix = tokenAndSuffix.substring(slashIdx);

    if (!isTransferTokenHintValid(token, pageRequest, paramMap))
    {
      sendTransferErrorResponse(401, "Unauthorized", "TRANSFER_TOKEN_INVALID",
          "Transfer token hint did not match URL token.", false);
      return;
    }

    NgClientRecordingCopyTransferManager transferMgr = NgClientRecordingCopyTransferManager.getInstance();
    NgClientRecordingCopyTransferManager.TransferSession session = transferMgr.getSession(token, null);
    if (session == null)
    {
      sendTransferErrorResponse(404, "Not Found", "TRANSFER_SESSION_NOT_FOUND",
          "Transfer session not found.", true);
      return;
    }

    if (Sage.getBoolean("miniclient/transfer/enforce_client_binding", false) &&
        !isTransferRequesterBoundToSession(session, paramMap))
    {
      sendTransferErrorResponse(401, "Unauthorized", "TRANSFER_CLIENT_MISMATCH",
          "Transfer token is not valid for this client session.", false);
      return;
    }

    if (!NgClientDownloadTokenManager.getInstance().validateToken(token, session.clientName, session.recordingId))
    {
      NgClientRecordingCopyTransferManager.TransferSession refreshedSession =
          refreshTransferSessionForExpiredToken(transferMgr, session);
      if (refreshedSession != null)
      {
        String redirectPath = "/api/transfers/" + refreshedSession.sessionToken + suffix +
            "?v=" + refreshedSession.urlRevision;
        if (Sage.DBG)
          System.out.println("Transfer token expired; issuing redirect to refreshed URL: " + redirectPath);
        sendHTTPRedirectResponse(307, "Temporary Redirect", redirectPath);
        return;
      }

      sendTransferErrorResponse(401, "Unauthorized", "TRANSFER_TOKEN_INVALID",
          "Transfer token is invalid or expired.", true);
      return;
    }

    if (suffix.startsWith("/offline/"))
    {
      handleTransferOfflineRequest(token, suffix, session, paramMap);
      return;
    }

    if ("/refresh".equals(suffix))
    {
      handleTransferRefreshRequest(transferMgr, session, requestMethod, requestBody);
      return;
    }

    if (!"/content".equals(suffix))
    {
      sendTransferErrorResponse(404, "Not Found", "TRANSFER_ENDPOINT_NOT_FOUND",
          "Unknown transfer endpoint.", false);
      return;
    }

    if (!"GET".equalsIgnoreCase(requestMethod))
    {
      sendTransferErrorResponse(405, "Method Not Allowed", "TRANSFER_METHOD_NOT_ALLOWED",
          "Content endpoint only supports GET.", false);
      return;
    }

    if (NgClientRecordingCopyTransferManager.STATE_CANCELED.equals(session.sessionState) ||
        NgClientRecordingCopyTransferManager.STATE_ERROR.equals(session.sessionState) ||
        NgClientRecordingCopyTransferManager.STATE_EXPIRED.equals(session.sessionState))
    {
      sendTransferErrorResponse(410, "Gone", "TRANSFER_SESSION_INACTIVE",
          "Transfer session is no longer active.", true);
      return;
    }

    if (NgClientRecordingCopyTransferManager.STATE_PAUSED_BY_CLIENT.equals(session.sessionState) ||
        NgClientRecordingCopyTransferManager.STATE_PAUSED_BY_SERVER.equals(session.sessionState) ||
        NgClientRecordingCopyTransferManager.STATE_QUEUED.equals(session.sessionState))
    {
      sendHTTPJsonResponse(409, "Conflict", transferMgr.buildStatusJson(session), "application/json");
      return;
    }

    MediaFile mf = Wizard.getInstance().getFileForID(session.recordingId);
    if (mf == null)
    {
      sendTransferErrorResponse(404, "Not Found", "TRANSFER_MEDIA_NOT_FOUND",
          "Media file is no longer available.", false);
      return;
    }

    java.io.File sourceFile = mf.getFile(0);
    if (sourceFile == null || !sourceFile.isFile())
    {
      sendTransferErrorResponse(404, "Not Found", "TRANSFER_MEDIA_MISSING",
          "Media file is missing.", false);
      return;
    }

    String sourcePath = sourceFile.getAbsolutePath();
    if (session.filePath != null && session.filePath.length() > 0 && !sourcePath.equals(session.filePath))
    {
      sendTransferErrorResponse(409, "Conflict", "TRANSFER_SOURCE_CHANGED",
          "Transfer session source file changed.", true);
      return;
    }

    long totalSize = sourceFile.length();
    if (totalSize < 0)
      totalSize = 0;

    long start = 0;
    long end = Math.max(0, totalSize - 1);
    boolean partial = false;

    String range = (String) paramMap.get("range");
    if (range != null && range.length() > 0)
    {
      if (!range.startsWith("bytes="))
      {
        sendHTTPRangeNotSatisfiable(totalSize);
        return;
      }

      String rangeSpec = range.substring(6).trim();
      int commaIdx = rangeSpec.indexOf(',');
      if (commaIdx != -1)
        rangeSpec = rangeSpec.substring(0, commaIdx).trim();
      int dashIdx = rangeSpec.indexOf('-');
      if (dashIdx < 0)
      {
        sendHTTPRangeNotSatisfiable(totalSize);
        return;
      }

      try
      {
        String startText = rangeSpec.substring(0, dashIdx).trim();
        String endText = rangeSpec.substring(dashIdx + 1).trim();
        if (startText.length() == 0)
        {
          long suffixLen = Long.parseLong(endText);
          if (suffixLen <= 0)
          {
            sendHTTPRangeNotSatisfiable(totalSize);
            return;
          }
          if (suffixLen >= totalSize)
            start = 0;
          else
            start = totalSize - suffixLen;
          end = Math.max(0, totalSize - 1);
        }
        else
        {
          start = Long.parseLong(startText);
          if (endText.length() > 0)
            end = Long.parseLong(endText);
          else
            end = Math.max(0, totalSize - 1);
        }
      }
      catch (NumberFormatException nfe)
      {
        sendHTTPRangeNotSatisfiable(totalSize);
        return;
      }

      if (start < 0 || start >= totalSize || end < start)
      {
        sendHTTPRangeNotSatisfiable(totalSize);
        return;
      }

      if (end >= totalSize)
        end = totalSize - 1;
      partial = true;
    }

    if (totalSize == 0)
    {
      start = 0;
      end = -1;
      partial = false;
    }

    long contentLength = (end >= start) ? (end - start + 1) : 0;

    writeBuf.clear();
    appendStringToWriteBuf(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
    appendStringToWriteBuf("Server: SageTV " + UIManager.SAGE + "\r\n");
    appendStringToWriteBuf("Date: " + new java.util.Date().toString() + "\r\n");
    appendStringToWriteBuf("Accept-Ranges: bytes\r\n");
    appendStringToWriteBuf("Content-Type: application/octet-stream\r\n");
    appendStringToWriteBuf("Content-Disposition: attachment; filename=\"" + sanitizeHeaderValue(sourceFile.getName()) + "\"\r\n");
    appendStringToWriteBuf("Cache-Control: no-store\r\n");
    if (partial)
      appendStringToWriteBuf("Content-Range: bytes " + start + "-" + end + "/" + totalSize + "\r\n");
    appendStringToWriteBuf("Content-Length: " + contentLength + "\r\n\r\n");
    if (writeBuf.position() > 0)
    {
      writeBuf.flip();
      sake.write(writeBuf);
    }

    long bytesSent = 0;
    long started = Sage.time();
    java.nio.channels.FileChannel fc = new java.io.FileInputStream(sourceFile).getChannel();
    try
    {
      long offset = start;
      int transferChunkSize = Math.max(16384, Sage.getInt("ng_transfer_http_chunk_size", 32768));
      while (bytesSent < contentLength)
      {
        long toSend = Math.min(transferChunkSize, contentLength - bytesSent);
        long sent = fc.transferTo(offset, toSend, sake);
        if (sent <= 0)
          break;
        offset += sent;
        bytesSent += sent;

        long elapsedMs = Math.max(1L, Sage.time() - started);
        long kbps = Math.max(0L, ((bytesSent * 8L * 1000L) / elapsedMs) / 1024L);
        long progressedTo = Math.max(session.bytesTransferred, start + bytesSent);
        transferMgr.updateProgress(token, progressedTo, kbps, null);

        // Play-nice pacing: when playback/recording activity contends for BW or disk,
        // sleep just enough each chunk to keep our running rate at or below the cap.
        long capKbps = computePlayniceCapKbps();
        if (capKbps > 0)
        {
          long allowedBytes = (capKbps * 1024L / 8L) * elapsedMs / 1000L;
          if (bytesSent > allowedBytes)
          {
            long overBytes = bytesSent - allowedBytes;
            long sleepMs = (overBytes * 8L * 1000L) / (capKbps * 1024L);
            if (sleepMs > 200L) sleepMs = 200L;
            if (sleepMs > 0)
            {
              try { Thread.sleep(sleepMs); }
              catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
          }
        }
      }
    }
    finally
    {
      fc.close();
    }

    long elapsedMs = Math.max(1L, Sage.time() - started);
    long kbps = Math.max(0L, ((bytesSent * 8L * 1000L) / elapsedMs) / 1024L);
    long progressedTo = Math.max(session.bytesTransferred, start + bytesSent);
    transferMgr.updateProgress(token, progressedTo, kbps, null);
  }

  private void handleTransferRefreshRequest(NgClientRecordingCopyTransferManager transferMgr,
      NgClientRecordingCopyTransferManager.TransferSession session,
      String requestMethod, byte[] requestBody) throws java.io.IOException
  {
    if (!"POST".equalsIgnoreCase(requestMethod) && !"GET".equalsIgnoreCase(requestMethod))
    {
      sendTransferErrorResponse(405, "Method Not Allowed", "TRANSFER_METHOD_NOT_ALLOWED",
          "Refresh endpoint only supports GET or POST.", false);
      return;
    }

    if (session == null)
    {
      sendTransferErrorResponse(404, "Not Found", "TRANSFER_SESSION_NOT_FOUND",
          "Transfer session not found.", true);
      return;
    }

    long requestedOffset = extractRefreshOffset(requestBody, session.bytesTransferred);
    NgClientRecordingCopyTransferManager.RequestedPolicy reqPolicy =
        session.acceptedPolicy == null ? null :
        new NgClientRecordingCopyTransferManager.RequestedPolicy(
            session.acceptedPolicy.downloadMode,
            session.acceptedPolicy.rateProfile,
            session.acceptedPolicy.maxRateKbps,
            session.acceptedPolicy.concurrency,
            session.acceptedPolicy.wifiOnly,
            session.acceptedPolicy.allowMetered);

    NgClientRecordingCopyTransferManager.TransferSession refreshed = transferMgr.resume(
        session.sessionToken,
        session.clientName,
        requestedOffset,
        reqPolicy,
        0L);

    if (refreshed == null)
    {
      sendTransferErrorResponse(409, "Conflict", "TRANSFER_REFRESH_FAILED",
          "Transfer refresh request could not be processed right now.", true);
      return;
    }

    // HTTP refresh ACKs can carry the full inline manifest, which keeps the client
    // from having to reformulate a second metadata request.
    String body = transferMgr.buildSessionAckJson(refreshed);
    sendHTTPJsonResponse(200, "OK", body, "application/json");
  }

  private int parseContentLength(java.util.Map paramMap)
  {
    if (paramMap == null)
      return 0;
    String contentLength = (String) paramMap.get("content-length");
    if (contentLength == null || contentLength.length() == 0)
      return 0;
    try
    {
      return Math.max(0, Integer.parseInt(contentLength.trim()));
    }
    catch (NumberFormatException nfe)
    {
      return 0;
    }
  }

  private byte[] readHttpRequestBody(int contentLength) throws java.io.IOException
  {
    if (contentLength <= 0)
      return null;

    byte[] body = new byte[contentLength];
    int copied = 0;
    if (readBuf != null && readBuf.hasRemaining())
    {
      int fromBuffer = Math.min(contentLength, readBuf.remaining());
      readBuf.get(body, 0, fromBuffer);
      copied += fromBuffer;
    }

    while (copied < contentLength)
    {
      readBuf.clear();
      TimeoutHandler.registerTimeout(timeout, sake);
      try
      {
        int readCount = sake.read(readBuf);
        if (readCount <= 0)
          throw new java.io.EOFException();
      }
      finally
      {
        TimeoutHandler.clearTimeout(sake);
      }
      readBuf.flip();
      int chunk = Math.min(contentLength - copied, readBuf.remaining());
      readBuf.get(body, copied, chunk);
      copied += chunk;
    }
    return body;
  }

  private long extractRefreshOffset(byte[] requestBody, long defaultOffset)
  {
    if (requestBody == null || requestBody.length == 0)
      return Math.max(0L, defaultOffset);

    String payload;
    try
    {
      payload = new String(requestBody, Sage.BYTE_CHARSET);
    }
    catch (java.io.UnsupportedEncodingException e)
    {
      payload = new String(requestBody);
    }

    long offset = extractLongField(payload, "bytesTransferred", -1L);
    if (offset < 0)
      offset = extractLongField(payload, "bytes_transferred", -1L);
    if (offset < 0)
      offset = extractLongField(payload, "offset", -1L);
    if (offset < 0)
      offset = defaultOffset;
    return Math.max(0L, offset);
  }

  private long extractLongField(String payload, String key, long defValue)
  {
    if (payload == null || key == null || key.length() == 0)
      return defValue;

    String marker = '"' + key + '"';
    int idx = payload.indexOf(marker);
    if (idx == -1)
      return defValue;
    int colon = payload.indexOf(':', idx + marker.length());
    if (colon == -1)
      return defValue;
    int start = colon + 1;
    int end = start;
    while (end < payload.length())
    {
      char c = payload.charAt(end);
      if ((c >= '0' && c <= '9') || c == '-' || c == '+')
      {
        end++;
        continue;
      }
      break;
    }
    if (end <= start)
      return defValue;
    try
    {
      return Long.parseLong(payload.substring(start, end).trim());
    }
    catch (NumberFormatException nfe)
    {
      return defValue;
    }
  }

  private void handleOfflineSnapshotRequest(String pageRequest, String requestMethod,
      java.util.Map paramMap, byte[] requestBody) throws java.io.IOException
  {
    String cleanPath = pageRequest;
    int queryIdx = cleanPath.indexOf('?');
    if (queryIdx >= 0)
      cleanPath = cleanPath.substring(0, queryIdx);

    if ("/api/offline/playback-state-sync".equals(cleanPath))
    {
      handleOfflinePlaybackStateSyncRequest(requestMethod, paramMap, requestBody);
      return;
    }

    if ("/api/offline/guide-snapshot".equals(cleanPath))
    {
      String body = NgClientOfflineCompanionBuilder.buildGuideSnapshotJson();
      sendHTTPJsonResponse(200, "OK", body, "application/json");
      return;
    }

    if ("/api/offline/sched-snapshot".equals(cleanPath))
    {
      String body = NgClientOfflineCompanionBuilder.buildSchedSnapshotJson();
      sendHTTPJsonResponse(200, "OK", body, "application/json");
      return;
    }

    if ("/api/offline/favorites-snapshot".equals(cleanPath))
    {
      String body = NgClientOfflineCompanionBuilder.buildFavoritesSnapshotJson();
      sendHTTPJsonResponse(200, "OK", body, "application/json");
      return;
    }

    sendHTTPErrorResponse(404, "Not Found", "Unknown offline snapshot endpoint.");
  }

  private void handleOfflinePlaybackStateSyncRequest(String requestMethod, java.util.Map paramMap,
      byte[] requestBody) throws java.io.IOException
  {
    if (!"POST".equalsIgnoreCase(requestMethod))
    {
      sendPlaybackSyncErrorResponse(405, "Method Not Allowed", "PLAYBACK_SYNC_METHOD_NOT_ALLOWED",
          "Playback sync endpoint only supports POST.", false);
      return;
    }

    String bodyText = requestBody == null ? "" : new String(requestBody, StandardCharsets.UTF_8);
    String normalizedBody = normalizeRefreshLikePayload(bodyText);
    if (normalizedBody == null || normalizedBody.length() == 0)
    {
      sendPlaybackSyncErrorResponse(400, "Bad Request", "PLAYBACK_SYNC_INVALID_REQUEST",
          "Playback sync payload is required.", false);
      return;
    }

    java.util.HashMap<String, String> topLevel = new java.util.HashMap<String, String>();
    extractJsonField(normalizedBody, "schema_version", topLevel);
    extractJsonField(normalizedBody, "schemaVersion", topLevel);
    int schemaVersion = parseIntSafe(firstNonEmptyTrimmed(
        topLevel.get("schema_version"),
        topLevel.get("schemaVersion"),
        "",
        "",
        ""), 1);
    if (schemaVersion != 1)
    {
      sendPlaybackSyncErrorResponse(400, "Bad Request", "PLAYBACK_SYNC_SCHEMA_UNSUPPORTED",
          "Unsupported playback sync schema version.", false);
      return;
    }

    String hintedToken = firstNonEmptyTrimmed(
        trimToEmpty((String) paramMap.get("x-transfer-token")),
        trimToEmpty((String) paramMap.get("transfer-token")),
        "",
        "",
        "");
    if (hintedToken.length() > 0)
    {
      NgClientRecordingCopyTransferManager transferMgr = NgClientRecordingCopyTransferManager.getInstance();
      if (transferMgr.getSession(hintedToken, null) == null)
      {
        sendPlaybackSyncErrorResponse(401, "Unauthorized", "TRANSFER_TOKEN_INVALID",
            "Transfer token is invalid or expired.", true);
        return;
      }
    }

    java.util.ArrayList<OfflinePlaybackSyncRecord> records = parseOfflinePlaybackSyncRecords(normalizedBody);
    if (records.isEmpty())
    {
      sendPlaybackSyncErrorResponse(400, "Bad Request", "PLAYBACK_SYNC_INVALID_REQUEST",
          "Playback sync payload must include recordings entries.", false);
      return;
    }

    int appliedCount = 0;
    StringBuilder sb = new StringBuilder(256 + records.size() * 96);
    sb.append('{');
    sb.append("\"type\":\"OFFLINE_PLAYBACK_SYNC_ACK\",");
    sb.append("\"schema_version\":1,");
    sb.append("\"applied_count\":");

    StringBuilder itemBuilder = new StringBuilder(64 + records.size() * 96);
    itemBuilder.append('[');
    boolean firstItem = true;

    for (int i = 0; i < records.size(); i++)
    {
      OfflinePlaybackSyncRecord rec = records.get(i);
      String mediaIdText = trimToEmpty(rec.mediaFileId);
      int mediaFileId = parseIntSafe(mediaIdText, -1);
      if (mediaFileId <= 0)
      {
        if (!firstItem) itemBuilder.append(',');
        appendPlaybackSyncItemError(itemBuilder, mediaIdText, "PLAYBACK_SYNC_MEDIA_ID_INVALID",
            "media_file_id is missing or invalid.");
        firstItem = false;
        continue;
      }

      MediaFile mf = Wizard.getInstance().getFileForID(mediaFileId);
      if (mf == null)
      {
        if (!firstItem) itemBuilder.append(',');
        appendPlaybackSyncItemError(itemBuilder, Integer.toString(mediaFileId), "TRANSFER_MEDIA_NOT_FOUND",
            "Media file is no longer available.");
        firstItem = false;
        continue;
      }

      Airing air = mf.getContentAiring();
      if (air == null)
      {
        if (!firstItem) itemBuilder.append(',');
        appendPlaybackSyncItemError(itemBuilder, Integer.toString(mediaFileId), "TRANSFER_MEDIA_NOT_FOUND",
            "Content airing is unavailable.");
        firstItem = false;
        continue;
      }

      boolean serverWatched = BigBrother.isWatched(air);
      long serverResumeMs = extractResumePositionMs(air);
      long clientResumeMs = Math.max(0L, rec.resumePositionMs);
      boolean mergedWatched = serverWatched || rec.watched;
      long mergedResumeMs = Math.max(serverResumeMs, clientResumeMs);

      boolean changed = false;
      if (mergedWatched && !serverWatched)
      {
        BigBrother.setWatched(air);
        changed = true;
      }
      if (!mergedWatched && mergedResumeMs > serverResumeMs)
      {
        if (applyResumePositionMs(air, mergedResumeMs))
          changed = true;
      }
      if (changed)
        appliedCount++;

      boolean finalWatched = BigBrother.isWatched(air);
      long finalResumeMs = extractResumePositionMs(air);

      if (!firstItem) itemBuilder.append(',');
      itemBuilder.append('{');
      itemBuilder.append("\"media_file_id\":\"").append(escapeForJson(Integer.toString(mediaFileId))).append("\",");
      itemBuilder.append("\"watched\":").append(finalWatched ? "true" : "false").append(',');
      itemBuilder.append("\"resume_position_ms\":").append(finalResumeMs);
      itemBuilder.append('}');
      firstItem = false;
    }

    itemBuilder.append(']');
    sb.append(appliedCount);
    sb.append(",\"recordings\":");
    sb.append(itemBuilder);
    sb.append('}');

    sendHTTPJsonResponse(200, "OK", sb.toString(), "application/json");
  }

  private void appendPlaybackSyncItemError(StringBuilder sb, String mediaFileId,
      String errorCode, String message)
  {
    sb.append('{');
    sb.append("\"media_file_id\":\"").append(escapeForJson(trimToEmpty(mediaFileId))).append("\",");
    sb.append("\"error_code\":\"").append(escapeForJson(errorCode)).append("\",");
    sb.append("\"message\":\"").append(escapeForJson(message)).append("\"");
    sb.append('}');
  }

  private void sendPlaybackSyncErrorResponse(int statusCode, String statusText, String errorCode,
      String message, boolean retriable) throws java.io.IOException
  {
    String body = "{\"type\":\"TRANSFER_ERROR\",\"error_code\":\"" +
        escapeForJson(errorCode) + "\",\"message\":\"" +
        escapeForJson(message) + "\",\"retriable\":" +
        (retriable ? "true" : "false") + "}";
    sendHTTPJsonResponse(statusCode, statusText, body, "application/json");
  }

  private java.util.ArrayList<OfflinePlaybackSyncRecord> parseOfflinePlaybackSyncRecords(String jsonBody)
  {
    java.util.ArrayList<OfflinePlaybackSyncRecord> rv = new java.util.ArrayList<OfflinePlaybackSyncRecord>();
    String recordingsArray = extractJsonArrayForKey(jsonBody, "recordings");
    if (recordingsArray.length() == 0)
      return rv;

    java.util.ArrayList<String> objects = splitTopLevelJsonObjects(recordingsArray);
    for (int i = 0; i < objects.size(); i++)
    {
      String obj = objects.get(i);
      java.util.HashMap<String, String> fields = new java.util.HashMap<String, String>();
      extractJsonField(obj, "media_file_id", fields);
      extractJsonField(obj, "mediaFileID", fields);
      extractJsonField(obj, "mediaFileId", fields);
      extractJsonField(obj, "recording_id", fields);
      extractJsonField(obj, "recordingId", fields);
      extractJsonField(obj, "resume_position_ms", fields);
      extractJsonField(obj, "resumePositionMs", fields);
      extractJsonField(obj, "watched", fields);

      String mediaFileId = firstNonEmptyTrimmed(
          fields.get("media_file_id"),
          fields.get("mediaFileID"),
          fields.get("mediaFileId"),
          fields.get("recording_id"),
          fields.get("recordingId"));
      if (mediaFileId.length() == 0)
        continue;

      long resumeMs = parseLongSafe(firstNonEmptyTrimmed(
          fields.get("resume_position_ms"),
          fields.get("resumePositionMs"),
          "0",
          "",
          ""), 0L);
      boolean watched = parseBooleanSafe(fields.get("watched"));
      rv.add(new OfflinePlaybackSyncRecord(mediaFileId, Math.max(0L, resumeMs), watched));
    }
    return rv;
  }

  private String extractJsonArrayForKey(String json, String key)
  {
    if (json == null || key == null || key.length() == 0)
      return "";
    String needle = "\"" + key + "\"";
    int idx = json.indexOf(needle);
    if (idx < 0)
      return "";
    int colon = json.indexOf(':', idx + needle.length());
    if (colon < 0)
      return "";

    int i = colon + 1;
    while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
    if (i >= json.length() || json.charAt(i) != '[')
      return "";

    int depth = 0;
    boolean inString = false;
    boolean escaped = false;
    for (int p = i; p < json.length(); p++)
    {
      char ch = json.charAt(p);
      if (inString)
      {
        if (escaped)
          escaped = false;
        else if (ch == '\\')
          escaped = true;
        else if (ch == '"')
          inString = false;
        continue;
      }

      if (ch == '"')
      {
        inString = true;
        continue;
      }
      if (ch == '[')
      {
        depth++;
        continue;
      }
      if (ch == ']')
      {
        depth--;
        if (depth == 0)
          return json.substring(i, p + 1);
      }
    }
    return "";
  }

  private java.util.ArrayList<String> splitTopLevelJsonObjects(String jsonArray)
  {
    java.util.ArrayList<String> rv = new java.util.ArrayList<String>();
    if (jsonArray == null || jsonArray.length() < 2)
      return rv;

    int start = jsonArray.indexOf('[');
    int end = jsonArray.lastIndexOf(']');
    if (start < 0 || end <= start)
      return rv;

    int depth = 0;
    int objStart = -1;
    boolean inString = false;
    boolean escaped = false;
    for (int i = start + 1; i < end; i++)
    {
      char ch = jsonArray.charAt(i);
      if (inString)
      {
        if (escaped)
          escaped = false;
        else if (ch == '\\')
          escaped = true;
        else if (ch == '"')
          inString = false;
        continue;
      }

      if (ch == '"')
      {
        inString = true;
        continue;
      }
      if (ch == '{')
      {
        if (depth == 0)
          objStart = i;
        depth++;
        continue;
      }
      if (ch == '}')
      {
        depth--;
        if (depth == 0 && objStart >= 0)
        {
          rv.add(jsonArray.substring(objStart, i + 1));
          objStart = -1;
        }
      }
    }
    return rv;
  }

  private boolean parseBooleanSafe(String s)
  {
    if (s == null)
      return false;
    String v = s.trim();
    if (v.length() == 0)
      return false;
    return "true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v);
  }

  private long extractResumePositionMs(Airing air)
  {
    if (air == null)
      return 0L;
    long latestWatch = BigBrother.getLatestWatch(air);
    long start = air.getStartTime();
    long end = air.getEndTime();
    long resumeMs = Math.max(0L, latestWatch - start);
    long maxMs = Math.max(0L, end - start);
    if (resumeMs > maxMs)
      resumeMs = maxMs;
    return resumeMs;
  }

  private boolean applyResumePositionMs(Airing air, long resumePositionMs)
  {
    if (air == null)
      return false;
    long start = air.getStartTime();
    long end = air.getEndTime();
    long maxMs = Math.max(0L, end - start);
    long clampedMs = Math.max(0L, Math.min(maxMs, resumePositionMs));
    long watchEnd = start + clampedMs;
    if (watchEnd <= start)
      return false;
    return BigBrother.setWatched(air, start, watchEnd, 0L, 0L, false);
  }

  private static final class OfflinePlaybackSyncRecord
  {
    private final String mediaFileId;
    private final long resumePositionMs;
    private final boolean watched;

    private OfflinePlaybackSyncRecord(String mediaFileId, long resumePositionMs, boolean watched)
    {
      this.mediaFileId = mediaFileId;
      this.resumePositionMs = resumePositionMs;
      this.watched = watched;
    }
  }

  private boolean isTransferTokenHintValid(String token, String pageRequest, java.util.Map paramMap)
  {
    if (token == null || token.length() == 0)
      return false;

    String headerToken = paramMap == null ? null : (String) paramMap.get("x-transfer-token");
    if (headerToken != null)
    {
      headerToken = headerToken.trim();
      if (headerToken.length() > 0 && !token.equals(headerToken))
        return false;
    }

    int qIdx = pageRequest == null ? -1 : pageRequest.indexOf('?');
    if (qIdx != -1 && qIdx < pageRequest.length() - 1)
    {
      String query = pageRequest.substring(qIdx + 1);
      String queryToken = getQueryParam(query, "token");
      if (queryToken != null && queryToken.length() > 0 && !token.equals(queryToken))
        return false;
    }
    return true;
  }

  private String getQueryParam(String query, String key)
  {
    if (query == null || key == null || key.length() == 0)
      return "";
    java.util.StringTokenizer toker = new java.util.StringTokenizer(query, "&");
    while (toker.hasMoreTokens())
    {
      String pair = toker.nextToken();
      int eq = pair.indexOf('=');
      if (eq <= 0)
        continue;
      String k = pair.substring(0, eq).trim();
      if (!key.equalsIgnoreCase(k))
        continue;
      String v = pair.substring(eq + 1);
      try
      {
        return java.net.URLDecoder.decode(v, "UTF-8");
      }
      catch (Throwable t)
      {
        return v;
      }
    }
    return "";
  }

    private void handleTransferOfflineRequest(String token, String suffix,
      NgClientRecordingCopyTransferManager.TransferSession session,
      java.util.Map paramMap) throws java.io.IOException
  {
    if ("/offline/metadata".equals(suffix))
    {
        String offlineJson = readCachedTransferMetadata(token);
        if (offlineJson.length() == 0)
        {
          offlineJson = NgClientOfflineCompanionBuilder.buildOfflineBlockJson(session);
          if (offlineJson != null && offlineJson.length() > 0)
            writeCachedTransferMetadata(token, offlineJson);
        }
      if (offlineJson == null || offlineJson.length() == 0)
      {
        sendHTTPErrorResponse(404, "Not Found", "Offline metadata unavailable.");
        return;
      }
      if (Sage.getBoolean("miniclient/transfer/log_offline_metadata_manifest", true))
      {
        int artworkRefs = countOccurrences(offlineJson, "/offline/artwork/");
        int captionRefs = countOccurrences(offlineJson, "/offline/captions/");
        System.out.println("NG_TRANSFER_METADATA"
            + " token_prefix=" + shortTokenPrefix(token)
            + " artwork_refs=" + artworkRefs
            + " caption_refs=" + captionRefs
            + " bytes=" + offlineJson.length());
      }
      sendHTTPJsonResponse(200, "OK", offlineJson, "application/json");
      return;
    }

    if (suffix.startsWith("/offline/artwork/"))
    {
      int index = parsePositiveIndex(suffix.substring("/offline/artwork/".length()));
      if (index < 0)
      {
        sendTransferErrorResponse(400, "Bad Request", "TRANSFER_ARTWORK_INDEX_INVALID",
            "Artwork index is missing or invalid.", false);
        return;
      }
      NgClientOfflineCompanionBuilder.OfflineAsset asset =
          NgClientOfflineCompanionBuilder.resolveArtworkAsset(session, index);
      sendTransferArtworkAsset(token, index, asset, extractTransferCorrelationId(paramMap));
      return;
    }

    if (suffix.startsWith("/offline/captions/"))
    {
      int index = parsePositiveIndex(suffix.substring("/offline/captions/".length()));
      NgClientOfflineCompanionBuilder.OfflineAsset asset =
          NgClientOfflineCompanionBuilder.resolveCaptionAsset(session, index);
      if (asset == null)
      {
        sendHTTPErrorResponse(404, "Not Found", "Offline captions not available.");
        return;
      }
      sendOfflineAsset(asset);
      return;
    }

    if ("/offline/comskip".equals(suffix))
    {
      NgClientOfflineCompanionBuilder.OfflineAsset asset =
          NgClientOfflineCompanionBuilder.resolveComskipAsset(session);
      if (asset == null)
      {
        sendHTTPErrorResponse(404, "Not Found", "Offline comskip not available.");
        return;
      }
      sendOfflineAsset(asset);
      return;
    }

    if ("/offline/transcript".equals(suffix))
    {
      NgClientOfflineCompanionBuilder.OfflineAsset asset =
          NgClientOfflineCompanionBuilder.resolveTranscriptAsset(session);
      if (asset == null)
      {
        sendHTTPErrorResponse(404, "Not Found", "Offline transcript not available.");
        return;
      }
      sendOfflineAsset(asset);
      return;
    }

    sendHTTPErrorResponse(404, "Not Found", "Unknown offline endpoint.");
  }

  private int parsePositiveIndex(String tail)
  {
    if (tail == null || tail.length() == 0)
      return -1;
    int q = tail.indexOf('?');
    if (q != -1)
      tail = tail.substring(0, q);
    try
    {
      int rv = Integer.parseInt(tail.trim());
      return rv < 0 ? -1 : rv;
    }
    catch (NumberFormatException nfe)
    {
      return -1;
    }
  }

  private void sendOfflineAsset(NgClientOfflineCompanionBuilder.OfflineAsset asset)
      throws java.io.IOException
  {
    if (asset == null)
    {
      sendHTTPErrorResponse(404, "Not Found", "Offline asset unavailable.");
      return;
    }
    if (asset.localFile != null)
    {
      sendHTTPFile(asset.localFile,
          asset.contentType == null || asset.contentType.length() == 0 ? "application/octet-stream" : asset.contentType);
      return;
    }
    if (asset.sourceUrl != null && asset.sourceUrl.length() > 0)
    {
      sendHTTPRemote(asset.sourceUrl,
          asset.contentType == null || asset.contentType.length() == 0 ? "application/octet-stream" : asset.contentType);
      return;
    }
    sendHTTPErrorResponse(404, "Not Found", "Offline asset unavailable.");
  }

  private void sendTransferArtworkAsset(String token, int artworkIndex,
      NgClientOfflineCompanionBuilder.OfflineAsset asset, String correlationId)
      throws java.io.IOException
  {
    long startedAt = Sage.time();
    long endedAt = startedAt;
    String status = "500";
    String source = "none";
    String exceptionText = "";
    TransferStreamMetrics metrics = new TransferStreamMetrics();
    try
    {
      if (asset == null)
      {
        status = "404";
        sendTransferErrorResponse(404, "Not Found", "TRANSFER_ARTWORK_NOT_FOUND",
            "Offline artwork not available.", false);
        return;
      }

      String contentType = asset.contentType == null || asset.contentType.length() == 0 ?
          "application/octet-stream" : asset.contentType;
      if (asset.localFile != null)
      {
        source = "local";
        streamLocalTransferAsset(asset.localFile, contentType, metrics);
        status = "200";
        return;
      }

      if (asset.sourceUrl != null && asset.sourceUrl.length() > 0)
      {
        source = "remote";
        streamRemoteTransferAsset(asset.sourceUrl, contentType, metrics);
        status = "200";
        return;
      }

      status = "404";
      sendTransferErrorResponse(404, "Not Found", "TRANSFER_ARTWORK_NOT_FOUND",
          "Offline artwork source is unavailable.", false);
    }
    catch (java.io.FileNotFoundException fnfe)
    {
      exceptionText = String.valueOf(fnfe);
      status = "404";
      if (!metrics.responseStarted)
      {
        sendTransferErrorResponse(404, "Not Found", "TRANSFER_ARTWORK_NOT_FOUND",
            "Offline artwork source was not found.", false);
      }
    }
    catch (java.net.SocketTimeoutException ste)
    {
      exceptionText = String.valueOf(ste);
      status = "504";
      if (!metrics.responseStarted)
      {
        sendTransferErrorResponse(504, "Gateway Timeout", "TRANSFER_ARTWORK_TIMEOUT",
            "Artwork fetch timed out.", true);
      }
    }
    catch (Throwable t)
    {
      exceptionText = String.valueOf(t);
      status = "502";
      if (!metrics.responseStarted)
      {
        sendTransferErrorResponse(502, "Bad Gateway", "TRANSFER_ARTWORK_FETCH_FAILED",
            "Artwork fetch failed.", true);
      }
    }
    finally
    {
      endedAt = Sage.time();
      if (Sage.getBoolean("miniclient/transfer/log_artwork_requests", true))
      {
        long ttfbMs = metrics.firstByteAtMs > 0 ? (metrics.firstByteAtMs - startedAt) : -1L;
        long totalMs = Math.max(0L, endedAt - startedAt);
        System.out.println("NG_TRANSFER_ARTWORK"
            + " status=" + status
            + " token_prefix=" + shortTokenPrefix(token)
            + " idx=" + artworkIndex
            + " corr=" + (correlationId == null ? "" : correlationId)
            + " source=" + source
            + " ttfb_ms=" + ttfbMs
            + " total_ms=" + totalMs
            + " bytes=" + metrics.bytesWritten
            + " response_started=" + metrics.responseStarted
            + " ex=" + exceptionText);
      }
    }
  }

  private void streamLocalTransferAsset(java.io.File sourceFile, String contentType,
      TransferStreamMetrics metrics) throws java.io.IOException
  {
    if (sourceFile == null || !sourceFile.isFile())
      throw new java.io.FileNotFoundException("Missing local artwork file.");

    long len = Math.max(0L, sourceFile.length());
    writeBuf.clear();
    appendStringToWriteBuf("HTTP/1.1 200 OK\r\n");
    appendStringToWriteBuf("Server: SageTV " + UIManager.SAGE + "\r\n");
    appendStringToWriteBuf("Date: " + new java.util.Date().toString() + "\r\n");
    appendStringToWriteBuf("Cache-Control: no-store\r\n");
    appendStringToWriteBuf("Content-Type: " + contentType + "\r\n");
    appendStringToWriteBuf("Content-Length: " + len + "\r\n\r\n");
    writeBuf.flip();
    sake.write(writeBuf);
    metrics.responseStarted = true;
    metrics.firstByteAtMs = Sage.time();

    java.nio.channels.FileChannel fc = new java.io.FileInputStream(sourceFile).getChannel();
    try
    {
      long offset = 0;
      while (offset < len)
      {
        long sent = fc.transferTo(offset, Math.min(32768L, len - offset), sake);
        if (sent <= 0)
          break;
        offset += sent;
        metrics.bytesWritten += sent;
      }
    }
    finally
    {
      fc.close();
    }
  }

  private void streamRemoteTransferAsset(String sourceUrl, String fallbackType,
      TransferStreamMetrics metrics) throws java.io.IOException
  {
    java.io.IOException lastError = null;
    byte[] bodyBytes = null;

    java.util.ArrayList<String> candidates = buildArtworkSourceCandidates(sourceUrl);
    for (int i = 0; i < candidates.size(); i++)
    {
      String candidate = candidates.get(i);
      try
      {
        bodyBytes = readCachedTransferArtwork(candidate);
        if (bodyBytes != null)
        {
          lastError = null;
          break;
        }
        bodyBytes = loadRemoteTransferAssetBytes(candidate);
        if (bodyBytes != null && bodyBytes.length > 0)
          writeCachedTransferArtwork(candidate, bodyBytes);
        lastError = null;
        break;
      }
      catch (java.io.IOException ioe)
      {
        lastError = ioe;
      }
    }

    if (bodyBytes == null)
      throw (lastError == null ? new java.io.IOException("Artwork source unavailable") : lastError);

    writeBuf.clear();
    appendStringToWriteBuf("HTTP/1.1 200 OK\r\n");
    appendStringToWriteBuf("Server: SageTV " + UIManager.SAGE + "\r\n");
    appendStringToWriteBuf("Date: " + new java.util.Date().toString() + "\r\n");
    appendStringToWriteBuf("Cache-Control: no-store\r\n");
    appendStringToWriteBuf("Content-Type: " + fallbackType + "\r\n");
    appendStringToWriteBuf("Content-Length: " + bodyBytes.length + "\r\n");
    appendStringToWriteBuf("\r\n");
    writeBuf.flip();
    sake.write(writeBuf);
    metrics.responseStarted = true;
    metrics.firstByteAtMs = Sage.time();

    java.nio.ByteBuffer out = java.nio.ByteBuffer.wrap(bodyBytes);
    while (out.hasRemaining())
      sake.write(out);
    metrics.bytesWritten += bodyBytes.length;
  }

  private byte[] loadRemoteTransferAssetBytes(String sourceUrl) throws java.io.IOException
  {
    MetaImage metaImage = MetaImage.getMetaImage(new java.net.URL(sourceUrl));
    if (metaImage == null || metaImage.isNullOrFailed())
      throw new java.io.IOException("Artwork cache load failed for " + sourceUrl);

    byte[] bodyBytes = metaImage.getSourceAsBytes();
    if (bodyBytes == null || bodyBytes.length == 0)
      throw new java.io.IOException("Artwork cache bytes unavailable for " + sourceUrl);
    return bodyBytes;
  }

  private java.net.URLConnection openArtworkRemoteConnection(String sourceUrl) throws java.io.IOException
  {
    java.net.URLConnection con = new java.net.URL(sourceUrl).openConnection();
    con.setConnectTimeout((int) Math.min(Integer.MAX_VALUE, Math.max(1000L, timeout)));
    con.setReadTimeout((int) Math.min(Integer.MAX_VALUE, Math.max(1000L, timeout)));
    con.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; SageTV-OffCompanion/1.0)");
    con.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8");
    con.setRequestProperty("Referer", "https://json.schedulesdirect.org/");
    if (con instanceof java.net.HttpURLConnection)
      ((java.net.HttpURLConnection) con).setInstanceFollowRedirects(true);
    return con;
  }

  private java.util.ArrayList<String> buildArtworkSourceCandidates(String sourceUrl)
  {
    java.util.ArrayList<String> rv = new java.util.ArrayList<String>();
    addSourceCandidate(rv, sourceUrl);
    if (sourceUrl == null || sourceUrl.length() == 0)
      return rv;

    if (sourceUrl.indexOf("json.schedulesdirect.org/20141201/image/") != -1)
    {
      String assetsPath = sourceUrl.replace("/20141201/image/", "/20141201/image/assets/");
      addSourceCandidate(rv, assetsPath);

      String filePart = sourceUrl;
      int slash = filePart.lastIndexOf('/');
      if (slash != -1 && slash + 1 < filePart.length())
        filePart = filePart.substring(slash + 1);
      int q = filePart.indexOf('?');
      if (q != -1)
        filePart = filePart.substring(0, q);
      if (filePart.length() > 0)
      {
        addSourceCandidate(rv, "https://s3.amazonaws.com/schedulesdirect/assets/" + filePart);
      }
    }
    return rv;
  }

  private void addSourceCandidate(java.util.ArrayList<String> candidates, String url)
  {
    if (url == null || url.length() == 0)
      return;
    if (!candidates.contains(url))
      candidates.add(url);
  }

  private String extractTransferCorrelationId(java.util.Map paramMap)
  {
    if (paramMap == null)
      return "";
    return firstNonEmptyTrimmed(
        trimToEmpty((String) paramMap.get("x-correlation-id")),
        trimToEmpty((String) paramMap.get("x-request-id")),
        "",
        "",
        "");
  }

  private String shortTokenPrefix(String token)
  {
    if (token == null)
      return "";
    String t = token.trim();
    if (t.length() <= 8)
      return t;
    return t.substring(0, 8);
  }

  private int countOccurrences(String text, String needle)
  {
    if (text == null || needle == null || needle.length() == 0)
      return 0;
    int count = 0;
    int idx = 0;
    while ((idx = text.indexOf(needle, idx)) >= 0)
    {
      count++;
      idx += needle.length();
    }
    return count;
  }

  private String readCachedTransferMetadata(String token)
  {
    if (token == null || token.length() == 0)
      return "";
    synchronized (transferCacheLock)
    {
      java.io.File f = getTransferMetadataCacheFile(token);
      if (f == null || !f.isFile())
        return "";
      try
      {
        byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
        f.setLastModified(Sage.time());
        return new String(bytes, StandardCharsets.UTF_8);
      }
      catch (Throwable t)
      {
        if (Sage.DBG) System.out.println("Failed reading transfer metadata cache: " + t);
        return "";
      }
    }
  }

  private void writeCachedTransferMetadata(String token, String metadataJson)
  {
    if (token == null || token.length() == 0 || metadataJson == null || metadataJson.length() == 0)
      return;
    synchronized (transferCacheLock)
    {
      java.io.File f = getTransferMetadataCacheFile(token);
      if (f == null)
        return;
      writeBytesAtomic(f, metadataJson.getBytes(StandardCharsets.UTF_8));
      pruneTransferCacheIfNeededLocked();
    }
  }

  private byte[] readCachedTransferArtwork(String sourceUrl)
  {
    if (sourceUrl == null || sourceUrl.length() == 0)
      return null;
    synchronized (transferCacheLock)
    {
      java.io.File f = getTransferArtworkCacheFile(sourceUrl);
      if (f == null || !f.isFile())
        return null;
      try
      {
        byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
        if (bytes == null || bytes.length == 0)
          return null;
        f.setLastModified(Sage.time());
        return bytes;
      }
      catch (Throwable t)
      {
        if (Sage.DBG) System.out.println("Failed reading transfer artwork cache: " + t);
        return null;
      }
    }
  }

  private void writeCachedTransferArtwork(String sourceUrl, byte[] bodyBytes)
  {
    if (sourceUrl == null || sourceUrl.length() == 0 || bodyBytes == null || bodyBytes.length == 0)
      return;
    synchronized (transferCacheLock)
    {
      java.io.File f = getTransferArtworkCacheFile(sourceUrl);
      if (f == null)
        return;
      writeBytesAtomic(f, bodyBytes);
      pruneTransferCacheIfNeededLocked();
    }
  }

  private java.io.File getTransferCacheRootDir()
  {
    java.io.File root = new java.io.File(Sage.getPath("cache"), TRANSFER_CACHE_ROOT_DIR_NAME);
    if (!root.isDirectory())
      root.mkdirs();
    return root;
  }

  private java.io.File getTransferCacheSubDir(String name)
  {
    java.io.File dir = new java.io.File(getTransferCacheRootDir(), name);
    if (!dir.isDirectory())
      dir.mkdirs();
    return dir;
  }

  private java.io.File getTransferMetadataCacheFile(String token)
  {
    String safeToken = sanitizeCacheKey(token);
    if (safeToken.length() == 0)
      return null;
    return new java.io.File(getTransferCacheSubDir(TRANSFER_CACHE_METADATA_DIR_NAME), safeToken + ".json");
  }

  private java.io.File getTransferArtworkCacheFile(String sourceUrl)
  {
    String hash = hashForCacheKey(sourceUrl);
    if (hash.length() == 0)
      return null;
    return new java.io.File(getTransferCacheSubDir(TRANSFER_CACHE_ARTWORK_DIR_NAME), hash + ".bin");
  }

  private String sanitizeCacheKey(String input)
  {
    if (input == null)
      return "";
    StringBuilder sb = new StringBuilder(input.length());
    for (int i = 0; i < input.length(); i++)
    {
      char c = input.charAt(i);
      if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_')
        sb.append(c);
    }
    return sb.toString();
  }

  private String hashForCacheKey(String input)
  {
    if (input == null || input.length() == 0)
      return "";
    try
    {
      java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
      byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (int i = 0; i < digest.length; i++)
      {
        int v = digest[i] & 0xFF;
        if (v < 16)
          sb.append('0');
        sb.append(Integer.toHexString(v));
      }
      return sb.toString();
    }
    catch (Throwable t)
    {
      if (Sage.DBG) System.out.println("Failed hashing transfer cache key: " + t);
      return "";
    }
  }

  private void writeBytesAtomic(java.io.File target, byte[] body)
  {
    if (target == null || body == null)
      return;
    java.io.File parent = target.getParentFile();
    if (parent != null && !parent.isDirectory())
      parent.mkdirs();
    java.io.File tmp = new java.io.File(target.getAbsolutePath() + ".tmp");
    try
    {
      java.nio.file.Files.write(tmp.toPath(), body);
      try
      {
        java.nio.file.Files.move(tmp.toPath(), target.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      }
      catch (Throwable moveErr)
      {
        java.nio.file.Files.move(tmp.toPath(), target.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      target.setLastModified(Sage.time());
    }
    catch (Throwable t)
    {
      if (Sage.DBG) System.out.println("Failed writing transfer cache file: " + t);
      if (tmp.isFile())
        tmp.delete();
    }
  }

  private void pruneTransferCacheIfNeededLocked()
  {
    long maxBytes = Math.max(0L, TRANSFER_CACHE_MAX_BYTES);
    if (maxBytes <= 0L)
      return;

    java.io.File root = getTransferCacheRootDir();
    java.util.ArrayList<java.io.File> files = new java.util.ArrayList<java.io.File>();
    collectTransferCacheFiles(root, files);
    if (files.isEmpty())
      return;

    long totalBytes = 0L;
    for (int i = 0; i < files.size(); i++)
      totalBytes += Math.max(0L, files.get(i).length());
    if (totalBytes <= maxBytes)
      return;

    java.util.Collections.sort(files, new java.util.Comparator<java.io.File>()
    {
      public int compare(java.io.File f1, java.io.File f2)
      {
        long d = f1.lastModified() - f2.lastModified();
        if (d < 0L)
          return -1;
        if (d > 0L)
          return 1;
        return f1.getName().compareTo(f2.getName());
      }
    });

    for (int i = 0; i < files.size() && totalBytes > maxBytes; i++)
    {
      java.io.File f = files.get(i);
      long len = Math.max(0L, f.length());
      if (f.delete())
        totalBytes -= len;
    }
  }

  private void collectTransferCacheFiles(java.io.File dir, java.util.ArrayList<java.io.File> out)
  {
    if (dir == null || out == null || !dir.isDirectory())
      return;
    java.io.File[] kids = dir.listFiles();
    if (kids == null)
      return;
    for (int i = 0; i < kids.length; i++)
    {
      java.io.File f = kids[i];
      if (f == null)
        continue;
      if (f.isDirectory())
      {
        collectTransferCacheFiles(f, out);
      }
      else
      {
        out.add(f);
      }
    }
  }

  private static class TransferStreamMetrics
  {
    public boolean responseStarted;
    public long firstByteAtMs;
    public long bytesWritten;
  }

  private void sendHTTPFile(java.io.File sourceFile, String contentType) throws java.io.IOException
  {
    if (sourceFile == null || !sourceFile.isFile())
    {
      sendHTTPErrorResponse(404, "Not Found", "File missing.");
      return;
    }

    long len = Math.max(0L, sourceFile.length());
    writeBuf.clear();
    appendStringToWriteBuf("HTTP/1.1 200 OK\r\n");
    appendStringToWriteBuf("Server: SageTV " + UIManager.SAGE + "\r\n");
    appendStringToWriteBuf("Date: " + new java.util.Date().toString() + "\r\n");
    appendStringToWriteBuf("Cache-Control: no-store\r\n");
    appendStringToWriteBuf("Content-Type: " + contentType + "\r\n");
    appendStringToWriteBuf("Content-Length: " + len + "\r\n\r\n");
    writeBuf.flip();
    sake.write(writeBuf);

    java.nio.channels.FileChannel fc = new java.io.FileInputStream(sourceFile).getChannel();
    try
    {
      long offset = 0;
      while (offset < len)
      {
        long sent = fc.transferTo(offset, Math.min(32768L, len - offset), sake);
        if (sent <= 0)
          break;
        offset += sent;
      }
    }
    finally
    {
      fc.close();
    }
  }

  private void sendHTTPRemote(String sourceUrl, String fallbackType) throws java.io.IOException
  {
    java.net.URLConnection con = new java.net.URL(sourceUrl).openConnection();
    con.setConnectTimeout((int) Math.min(Integer.MAX_VALUE, Math.max(1000L, timeout)));
    con.setReadTimeout((int) Math.min(Integer.MAX_VALUE, Math.max(1000L, timeout)));
    con.setRequestProperty("User-Agent", "SageTV-OffCompanion/1.0");

    java.io.InputStream in = null;
    try
    {
      long len = con.getContentLengthLong();
      String ctype = con.getContentType();
      if (ctype == null || ctype.length() == 0)
        ctype = fallbackType;

      writeBuf.clear();
      appendStringToWriteBuf("HTTP/1.1 200 OK\r\n");
      appendStringToWriteBuf("Server: SageTV " + UIManager.SAGE + "\r\n");
      appendStringToWriteBuf("Date: " + new java.util.Date().toString() + "\r\n");
      appendStringToWriteBuf("Cache-Control: no-store\r\n");
      appendStringToWriteBuf("Content-Type: " + ctype + "\r\n");
      if (len >= 0)
        appendStringToWriteBuf("Content-Length: " + len + "\r\n");
      appendStringToWriteBuf("\r\n");
      writeBuf.flip();
      sake.write(writeBuf);

      in = con.getInputStream();
      byte[] buf = new byte[32768];
      int r;
      while ((r = in.read(buf)) > 0)
      {
        java.nio.ByteBuffer out = java.nio.ByteBuffer.wrap(buf, 0, r);
        while (out.hasRemaining())
          sake.write(out);
      }
    }
    catch (Throwable t)
    {
      if (Sage.DBG) System.out.println("Failed proxying offline remote asset: " + t);
      sendHTTPErrorResponse(404, "Not Found", "Remote asset unavailable.");
    }
    finally
    {
      if (in != null)
        try{in.close();}catch(Throwable t){}
    }
  }

  private boolean isTransferRequesterBoundToSession(
      NgClientRecordingCopyTransferManager.TransferSession session, java.util.Map paramMap)
  {
    if (session == null)
      return false;

    String requesterClientId = paramMap == null ? null : (String) paramMap.get("x-ng-client-id");
    if (requesterClientId != null)
      requesterClientId = requesterClientId.trim();
    String expectedClientId = session.ngClientId == null ? "" : session.ngClientId.trim();
    if (expectedClientId.length() > 0)
    {
      if (requesterClientId == null || requesterClientId.length() == 0)
        return false;
      return expectedClientId.equals(requesterClientId);
    }

    String expectedIp = session.clientIp == null ? "" : session.clientIp.trim();
    if (expectedIp.length() == 0)
      return true;

    String requesterIp = "";
    try
    {
      if (sake != null && sake.socket() != null && sake.socket().getInetAddress() != null)
        requesterIp = sake.socket().getInetAddress().getHostAddress();
    }
    catch (Throwable t)
    {
      if (Sage.DBG) System.out.println("Failed resolving transfer requester IP: " + t);
    }
    return expectedIp.equals(requesterIp);
  }

  private void sendTransferErrorResponse(int statusCode, String statusText, String errorCode,
      String message, boolean retriable) throws java.io.IOException
  {
    String body = "{\"type\":\"TRANSFER_ERROR\",\"error_code\":\"" +
        escapeForJson(errorCode) + "\",\"message\":\"" +
        escapeForJson(message) + "\",\"retriable\":" +
        (retriable ? "true" : "false") + "}";
    sendHTTPJsonResponse(statusCode, statusText, body, "application/json");
  }

  private java.util.Map sanitizeHeaderMapForLog(java.util.Map source)
  {
    if (source == null || source.isEmpty())
      return source;
    java.util.HashMap copy = new java.util.HashMap(source);
    maskHeader(copy, "authorization");
    maskHeader(copy, "proxy-authorization");
    return copy;
  }

  private void maskHeader(java.util.Map map, String key)
  {
    if (map == null || key == null)
      return;
    if (!map.containsKey(key))
      return;
    map.put(key, "<redacted>");
  }

  private void sendHTTPRangeNotSatisfiable(long totalSize) throws java.io.IOException
  {
    writeBuf.clear();
    appendStringToWriteBuf("HTTP/1.1 416 Range Not Satisfiable\r\n");
    appendStringToWriteBuf("Server: SageTV " + UIManager.SAGE + "\r\n");
    appendStringToWriteBuf("Date: " + new java.util.Date().toString() + "\r\n");
    appendStringToWriteBuf("Accept-Ranges: bytes\r\n");
    appendStringToWriteBuf("Content-Range: bytes */" + Math.max(0L, totalSize) + "\r\n");
    appendStringToWriteBuf("Content-Length: 0\r\n\r\n");
    if (writeBuf.position() > 0)
    {
      writeBuf.flip();
      sake.write(writeBuf);
    }
  }

  private NgClientRecordingCopyTransferManager.TransferSession refreshTransferSessionForExpiredToken(
      NgClientRecordingCopyTransferManager transferMgr,
      NgClientRecordingCopyTransferManager.TransferSession oldSession)
  {
    if (transferMgr == null || oldSession == null || oldSession.recordingId <= 0 || oldSession.isTerminal())
      return null;

    MediaFile mf = Wizard.getInstance().getFileForID(oldSession.recordingId);
    if (mf == null)
      return null;

    NgClientRecordingCopyTransferManager.RequestedPolicy reqPolicy =
        oldSession.acceptedPolicy == null ?
        NgClientRecordingCopyTransferManager.RequestedPolicy.createDefault("foreground") :
        new NgClientRecordingCopyTransferManager.RequestedPolicy(
            oldSession.acceptedPolicy.downloadMode,
            oldSession.acceptedPolicy.rateProfile,
            oldSession.acceptedPolicy.maxRateKbps,
            oldSession.acceptedPolicy.concurrency,
            oldSession.acceptedPolicy.wifiOnly,
            oldSession.acceptedPolicy.allowMetered);

    NgClientRecordingCopyTransferManager.TransferSession refreshed = transferMgr.createSession(
        oldSession.clientName,
        oldSession.clientIp,
        oldSession.ngClientId,
        oldSession.ngVersion,
      oldSession.transferBaseUrl,
        null,
        mf,
        reqPolicy,
        0L);

    if (refreshed != null)
      refreshed.bytesTransferred = Math.max(0L, oldSession.bytesTransferred);
    return refreshed;
  }

  private void sendHTTPRedirectResponse(int statusCode, String statusText, String location)
      throws java.io.IOException
  {
    if (location == null || location.length() == 0)
      location = "/";
    writeBuf.clear();
    appendStringToWriteBuf("HTTP/1.1 " + statusCode + " " + statusText + "\r\n");
    appendStringToWriteBuf("Server: SageTV " + UIManager.SAGE + "\r\n");
    appendStringToWriteBuf("Date: " + new java.util.Date().toString() + "\r\n");
    appendStringToWriteBuf("Location: " + location + "\r\n");
    appendStringToWriteBuf("Cache-Control: no-store\r\n");
    appendStringToWriteBuf("Content-Length: 0\r\n\r\n");
    if (writeBuf.position() > 0)
    {
      writeBuf.flip();
      sake.write(writeBuf);
    }
  }

  private void sendHTTPErrorResponse(int statusCode, String statusText, String message) throws java.io.IOException
  {
    String body = "{\"error\":\"" + escapeForJson(message) + "\"}";
    sendHTTPJsonResponse(statusCode, statusText, body, "application/json");
  }

  private void sendHTTPJsonResponse(int statusCode, String statusText, String body, String contentType)
      throws java.io.IOException
  {
    if (body == null)
      body = "";
    if (contentType == null || contentType.length() == 0)
      contentType = "application/json";
    byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
    writeBuf.clear();
    appendStringToWriteBuf("HTTP/1.1 " + statusCode + " " + statusText + "\r\n");
    appendStringToWriteBuf("Server: SageTV " + UIManager.SAGE + "\r\n");
    appendStringToWriteBuf("Date: " + new java.util.Date().toString() + "\r\n");
    if (contentType.toLowerCase().indexOf("charset=") == -1)
      appendStringToWriteBuf("Content-Type: " + contentType + "; charset=UTF-8\r\n");
    else
      appendStringToWriteBuf("Content-Type: " + contentType + "\r\n");
    appendStringToWriteBuf("Cache-Control: no-store\r\n");
    appendStringToWriteBuf("Content-Length: " + bodyBytes.length + "\r\n\r\n");
    appendBytesToWriteBuf(bodyBytes);
    if (writeBuf.position() > 0)
    {
      writeBuf.flip();
      sake.write(writeBuf);
    }
  }

  private String escapeForJson(String s)
  {
    if (s == null || s.length() == 0)
      return "";
    StringBuilder rv = new StringBuilder(s.length() + 8);
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

  private String sanitizeHeaderValue(String s)
  {
    if (s == null)
      return "recording.bin";
    return s.replace('"', '_').replace('\r', '_').replace('\n', '_');
  }

  // Returns true if a new transcoder was spawned
  private static Object xcodeSetupLock = new Object();
  private boolean setupTranscoder(String sessionID, MediaFile mf, int segmentNum, int bwkbps, int streamPart, VideoFrame vf) throws java.io.IOException
  {
    synchronized (xcodeSetupLock)
    {
      if (xcode != null)
        xcode.lastActivityTime = Sage.time();

      if (xcode == null)
      {
        // Check the cache map for a transcoder that's already running
        xcode = (XCodeInfo) cachedXCodeMap.get(sessionID);
        if (xcode != null)
        {
          xcode.lastActivityTime = Sage.time();
          if (!cachedXCodeMap.containsKey(sessionID))
            xcode = null; // sync, the other thread could have removed it during the above call
          if (xcode.transcoder.isTranscodeDone() && !xcode.transcoder.didTranscodeCompleteOK())
          {
            if (Sage.DBG) System.out.println("Transcoder failure detected! Kill it and build a new one!");
            cachedXCodeMap.remove(sessionID);
            xcode = null;
          }
        }
        if (Sage.DBG && xcode != null) System.out.println("iOS HTTP Request found a cached transcoder, re-using it!");
      }

      if (xcode == null)
      {
        if (Sage.DBG) System.out.println("iOS HTTP server is launching the transcoder for the source file " + mf);
        // We need to create and start the transcoder
        xcode = new XCodeInfo();
        xcode.lastActivityTime = Sage.time();
        xcode.mf = mf;
        xcode.segment = segmentNum;
        xcode.sessionID = sessionID;
        xcode.vf = vf;
      }
      if (xcode.transcoder == null)
      {
        xcode.transcoder = new FFMPEGTranscoder();
        xcode.transcoder.setEstimatedBandwidth(bwkbps * 1000);

        // Give the transcoder the connecting client's effective audio codec set
        // so the HLS audio path can negotiate: pass the source audio through
        // (-acodec copy) when the player supports it and it is HLS-safe, else
        // transcode down to AAC-LC. Resolved from the client's MiniClient
        // renderer via the VideoFrame; null-safe when unavailable.
        //
        // Protocol v2.1 Phase 2.5: if the MiniClient has an active surface
        // selection (MiniPlayer set it after the surface-aware ranker picked
        // a winner), ALSO push the surface's target audio/video codecs to
        // the transcoder. Those override the V1 coarse-list lookup in
        // FFMPEGTranscoder so a Chromium MSE client's honest AAC-only
        // surface preference is honored instead of being masked by its
        // legacy AUDIO_CODECS=AAC,AC3,EAC3 advertisement (which reflects
        // the native tizen player, not MSE).
        try
        {
          if (vf != null && vf.getUIMgr() != null && vf.getUIMgr().getRootPanel() != null
              && (vf.getUIMgr().getRootPanel().getRenderEngine() instanceof MiniClientSageRenderer))
          {
            MiniClientSageRenderer mcsr =
                (MiniClientSageRenderer) vf.getUIMgr().getRootPanel().getRenderEngine();
            xcode.transcoder.setHttplsClientAudioCodecs(mcsr.getEffectiveAudioCodecs());
            String surfAud = mcsr.getCurrentSurfaceTargetAudioCodec();
            String surfVid = mcsr.getCurrentSurfaceTargetVideoCodec();
            xcode.transcoder.setHttplsSurfaceTargetAudioCodec(surfAud);
            xcode.transcoder.setHttplsSurfaceTargetVideoCodec(surfVid);
            // 2.1.0003: pass the surface-selected audio stream orderIndex so
            // the -map block picks the right track (language + quality aware).
            int surfAudIdx = mcsr.getCurrentSurfaceAudioStreamIndex();
            xcode.transcoder.setHttplsSurfaceAudioStreamIndex(surfAudIdx);
            if (Sage.DBG && (surfAud.length() > 0 || surfVid.length() > 0))
              System.out.println("iOS HTTP server: surface v2.1 targets for '"
                  + mcsr.getCurrentSurfaceId() + "' audio=" + surfAud
                  + " video=" + surfVid + " audioStreamIdx=" + surfAudIdx
                  + " delivery=" + mcsr.getCurrentSurfaceDeliveryMode());
          }
        }
        catch (Throwable t)
        {
          if (Sage.DBG) System.out.println("iOS HTTP server: could not resolve client audio codecs: " + t);
        }

        int numTempFiles = Sage.getInt("xcode_num_temp_httpls_segments", 20);
        java.io.File[] tempSegFiles = new java.io.File[numTempFiles];
        for (int i = 0; i < numTempFiles; i++)
        {
          tempSegFiles[i] = java.io.File.createTempFile("stvhttpls", ".ts");
          tempSegFiles[i].deleteOnExit();
        }
        xcode.transcoder.enableSegmentedOutput(partDur * 1000, tempSegFiles);
        xcode.transcoder.setActiveFile(mf.isRecording(segmentNum));
        xcode.transcoder.setSourceFile(null, mf.getFile(segmentNum));
        xcode.transcoder.setTranscodeFormat("dynamicts", mf.getFileFormat());
        xcode.transcoder.seekToTime(streamPart * partDur * 1000);
        cachedXCodeMap.put(sessionID, xcode);
        return true;
      }

      // NOTE: We should do a bandwidth calculation on the server side as well and then use that knowledge in our rate adaptation. If there's delays due to pre-buffering then we could
      // avoid them by not adjusting the rate when it is requested. We may also be able to always create playlists like live TV would so the client won't request ahead as much (and we
      // base them on how much we've actually transcoded so far). We would then need to be smart about this and query the VideoFrame for what the target seek time is so that it's actually
      // possible for it to seek as far as it needs to in the file. It's possible that the iOS media player won't try to perform a seek to part of the stream that's not in the playlist (sounds
      // right since it won't know what URI to request), but since it's always requesting a new playlist then it will do that request again (maybe the seek will even kick it to do that) and then
      // we'll know how far to put segments from the playlist in there. But then if they seek back the client may end up caching that playlist and not request a new one which would break what we're
      // trying to do at that point.

      int targetVideoKbps = Math.max(64, bwkbps - 32);
      if (xcode.transcoder.getCurrentVideoBitrateKbps() > targetVideoKbps)
      {
        if (Sage.DBG) System.out.println("Requested bandwidth has decreased! Dump the transcoder and rebuild it!");
        xcode.transcoder.stopTranscode();
        //cachedXCodeMap.remove(sessionID);
        xcode.transcoder = null;
        return setupTranscoder(sessionID, mf, segmentNum, bwkbps, streamPart, vf);
      }
      else if (xcode.transcoder.getCurrentVideoBitrateKbps() < targetVideoKbps)
      {
        if (Sage.DBG) System.out.println("ADJUSTING HTTP streaming video bandwidth from " + xcode.transcoder.getCurrentVideoBitrateKbps() + " to " + targetVideoKbps);
        xcode.transcoder.dynamicVideoRateAdjust(targetVideoKbps - xcode.transcoder.getCurrentVideoBitrateKbps());
      }
      return false;
    }
  }

  private void sendBackTSFile(java.io.File theFile) throws java.io.IOException
  {
    writeBuf.clear();
    appendStringToWriteBuf("HTTP/1.1 200 OK\r\n");
    appendStringToWriteBuf("Server: SageTV " + UIManager.SAGE + "\r\n");
    appendStringToWriteBuf("Date: " + new java.util.Date().toString() + "\r\n");
    appendStringToWriteBuf("Content-Type: video/MP2T\r\n");
    appendStringToWriteBuf("Content-Length: " + theFile.length() + "\r\n\r\n");
    if (writeBuf.position() > 0)
    {
      writeBuf.flip();
      sake.write(writeBuf);
    }
    java.nio.channels.FileChannel fc = new java.io.FileInputStream(theFile).getChannel();
    int transferChunkSize = Sage.getInt("httpls_transfer_chunk_size", 32768);
    long totalSize = theFile.length();
    long offset = 0;
    try
    {
      if (transferChunkSize == 0)
      {
        long sent = fc.transferTo(0, totalSize, sake);
        MiniClientSageRenderer.recordServerActiveWindowWrite(sent);
      }
      else
      {
        while (totalSize > offset)
        {
          long currSize = Math.min(transferChunkSize, totalSize - offset);
          long sent = fc.transferTo(offset, currSize, sake);
          MiniClientSageRenderer.recordServerActiveWindowWrite(sent);
          if (sent <= 0)
            break;
          offset += sent;
        }
      }
    }
    finally
    {
      fc.close();
    }
  }

  private void sendHTTPM3U8Response(String data) throws java.io.IOException
  {
    writeBuf.clear();
    appendStringToWriteBuf("HTTP/1.1 200 OK\r\n");
    appendStringToWriteBuf("Server: SageTV " + UIManager.SAGE + "\r\n");
    appendStringToWriteBuf("Content-Type: application/vnd.apple.mpegurl; charset=ISO-8859-1\r\n");
    appendStringToWriteBuf("Content-Length: " + data.length() + "\r\n\r\n");
    appendStringToWriteBuf(data);
    if (writeBuf.position() > 0)
    {
      writeBuf.flip();
      sake.write(writeBuf);
    }
  }

  private void appendStringToWriteBuf(String s) throws java.io.IOException
  {
    appendBytesToWriteBuf(s.getBytes(StandardCharsets.UTF_8));
  }

  private void appendBytesToWriteBuf(byte[] b) throws java.io.IOException
  {
    if (writeBuf.remaining() > b.length)
    {
      writeBuf.put(b);
      return;
    }
    if (writeBuf.remaining() == 0)
    {
      writeBuf.flip();
      sake.write(writeBuf);
      writeBuf.clear();
    }
    int off = 0;
    int len = b.length;
    while (len > 0)
    {
      int rem = Math.min(writeBuf.remaining(), len);
      writeBuf.put(b, off, rem);
      if (writeBuf.remaining() == 0)
      {
        writeBuf.flip();
        sake.write(writeBuf);
        writeBuf.clear();
      }
      off += rem;
      len -= rem;
    }
  }

  // Computes the play-nice download cap based on current playback/recording activity.
  // Returns 0 (= unthrottled) when nothing else is going on, so a lone download keeps
  // the full pipe. Re-evaluated every chunk so playback start/stop adapts mid-transfer.
  private long computePlayniceCapKbps()
  {
    if (!Sage.getBoolean("miniclient/transfer/playnice_enabled", true))
      return 0L;

    boolean bwShared = false;
    boolean diskShared = false;

    // Any active recording = disk-write pressure regardless of NIC (HDHR on dedicated
    // NIC, PCIe capture card, network HDHR -- all funnel into the same disk writes).
    try
    {
      MediaFile[] recs = SeekerSelector.getInstance().getCurrRecordFiles();
      if (recs != null && recs.length > 0)
        diskShared = true;
    }
    catch (Throwable t) { /* ignore */ }

    // Determine this download's server-side local NIC so we can compare with playback
    // sessions' server-side local NIC. If the addresses match the bandwidth path is
    // shared; if they differ, only disk is shared.
    java.net.InetAddress downloadLocalAddr = null;
    try
    {
      if (sake != null && sake.socket() != null)
        downloadLocalAddr = sake.socket().getLocalAddress();
    }
    catch (Throwable t) { /* ignore */ }

    boolean nicAware = Sage.getBoolean("miniclient/transfer/playnice_nic_aware", true);
    try
    {
      UIClient[] watchers = Seeker.getInstance().getActiveWatchClients();
      if (watchers != null && watchers.length > 0)
      {
        if (!nicAware || downloadLocalAddr == null)
        {
          bwShared = true;
        }
        else
        {
          for (int i = 0; i < watchers.length; i++)
          {
            UIClient uic = watchers[i];
            if (uic instanceof MiniClientSageRenderer)
            {
              java.net.InetAddress wAddr = ((MiniClientSageRenderer) uic).getServerLocalAddress();
              if (wAddr == null || wAddr.equals(downloadLocalAddr))
              {
                bwShared = true;
                break;
              }
              diskShared = true;
            }
            else
            {
              // Local UI or other watcher with no socket: assume BW-shared (conservative)
              bwShared = true;
              break;
            }
          }
        }
      }
    }
    catch (Throwable t) { /* ignore */ }

    if (bwShared)
      return Math.max(256L, Sage.getLong("miniclient/transfer/playnice_bw_cap_kbps", 8000L));
    if (diskShared)
      return Math.max(256L, Sage.getLong("miniclient/transfer/playnice_disk_cap_kbps", 50000L));
    return 0L;
  }

  private java.nio.ByteBuffer readBuf;
  private java.nio.ByteBuffer writeBuf;
  private java.nio.channels.SocketChannel sake;
  private String initialHttpMethod;
  private String myHost;
  private long timeout;
  private int[] bandwidths;
  private int partDur;
  private XCodeInfo xcode;

  private static java.util.HashMap cachedXCodeMap = new java.util.HashMap();

  private static class XCodeInfo
  {
    public String sessionID;
    public MediaFile mf;
    public int segment;
    public long lastActivityTime;
    public FFMPEGTranscoder transcoder;
    public VideoFrame vf;
    public int lastRequestedPart;
  }

  // Call this when playback of a file is closed so the transcoder can be killed for that client
  public static void kickCleaner()
  {
    synchronized (cleanerLock)
    {
      cleanerNeedsWork = true;
      cleanerLock.notifyAll();
    }
  }

  // This is how we can change the active file state when needed
  public static void notifyOfInactiveFile(String s)
  {
    java.util.Iterator walker = cachedXCodeMap.values().iterator();
    while (walker.hasNext())
    {
      XCodeInfo xci = (XCodeInfo) walker.next();
      if (xci.mf.getFile(xci.segment).toString().equals(s))
      {
        xci.transcoder.setActiveFile(false);
      }
    }
  }

  private static boolean builtCleaner = false;
  private static Object cleanerLock = new Object();
  private static boolean cleanerNeedsWork = false;
  private static class HTTPLSCleaner implements Runnable
  {
    public void run()
    {
      if (Sage.DBG) System.out.println("The HTTPLSCleaner thread is now running...");
      // This is the time a connection can be inactive for if connections are allowed that aren't linked to a real player before it will be destroyed
      long timeToKill = Sage.getLong("httpls_xcode_expire_time", 30000);
      while (true)
      {
        synchronized (cleanerLock)
        {
          if (!cleanerNeedsWork)
          {
            try
            {
              cleanerLock.wait(15000);
            }
            catch (InterruptedException e){}
          }
          cleanerNeedsWork = false;
        }

        if (cachedXCodeMap.isEmpty())
          continue;

        java.util.Iterator walker = cachedXCodeMap.values().iterator();
        while (walker.hasNext())
        {
          XCodeInfo info = (XCodeInfo) walker.next();
          if (info.vf != null)
          {
            // Verify this MediaFile+segment is still being viewed
            if (info.vf.getCurrFile() != info.mf || info.vf.getCurrSegment() != info.segment)
            {
              if (Sage.DBG) System.out.println("HTTPLSCleaner found a transcoder that is not in use for " + info.mf + " segment=" + info.segment + " killing it!");
              info.transcoder.stopTranscode();
              walker.remove();
            }
          }
          else // this is from a generic HTTP connection
          {
            long diff = Sage.time() - info.lastActivityTime;
            if (diff > timeToKill)
            {
              if (Sage.DBG) System.out.println("HTTPLSCleaner found an expired transcoder that has not been active for " + diff + " msec for " + info.mf + " and segment=" + info.segment +
                  " killing it now!");
              info.transcoder.stopTranscode();
              walker.remove();
            }
          }
        }
      }
    }
  }
}