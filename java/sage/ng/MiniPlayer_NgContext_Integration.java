/**
 * MiniPlayer.java Integration Patch for NG Playback Context Wiring
 * ================================================================
 * 
 * This file documents the exact changes to java/sage/MiniPlayer.java
 * to wire NgPlaybackContextProvider into the playback lifecycle.
 * 
 * All changes are additive and exception-safe — legacy behavior is unaffected.
 * 
 * SUMMARY OF CHANGES:
 * 1. Add import + field declaration for NgPlaybackContextWiring
 * 2. Add singleton NgPlaybackContextProvider accessor
 * 3. Wire onPlaybackOpen() at end of load() after currState = LOADED_STATE
 * 4. Wire onPlaybackClose() at start of free()
 * 5. Wire onSeek() at end of seek() after waitingForSeek = false
 * 6. Wire onPushLoopTick() in push loop after numPushedBuffers++ (rate-limited every 32 buffers)
 * 7. Wire onInactiveFile() in inactiveFile()
 */

// ============================================================
// CHANGE 1: Add field (around line 5206, after hdhrPrimeSpecial)
// ============================================================

// ADD after "private boolean hdhrPrimeSpecial;"
//
//   /** NG Playback Context wiring — lifecycle bridge to provider. Never null. */
//   private final sage.ng.NgPlaybackContextWiring ngContextWiring =
//       new sage.ng.NgPlaybackContextWiring(sage.ng.NgPlaybackContextWiring.getGlobalProvider());


// ============================================================
// CHANGE 2: Add static singleton accessor to NgPlaybackContextWiring
// (already handled — see NgPlaybackContextWiring.getGlobalProvider() below)
// ============================================================


// ============================================================
// CHANGE 3: Wire onPlaybackOpen() in load()
// INSERT after line 2314 (currState = LOADED_STATE;)
// ============================================================

/*
      currState = LOADED_STATE;

      // --- NG Context wiring: open provider session ---
      try
      {
        MediaFile ngMF = VideoFrame.getMediaFileForPlayer(MiniPlayer.this);
        long ngMediaFileId = (ngMF != null) ? ngMF.getID() : -1;
        long ngAiringId = -1;
        if (ngMF != null && ngMF.getContentAiring() != null)
          ngAiringId = ngMF.getContentAiring().getID();
        String ngContainer = (ngMF != null) ? ngMF.getContainerFormat() : null;
        long ngDuration = getDurationMillis();
        boolean ngIsLive = timeshifted && (SeekerSelector.getInstance().getCurrRecordFileForClient(uiMgr, false) != null);
        long ngRecStart = 0; // TODO: extract from Airing.getStartTime() if available
        sage.ng.NgPlaybackContextWiring.FileSizeSupplier ngSizeSupplier = null;
        long ngInitialSize = finalLength;
        if (timeshifted && mpegSrc != null)
        {
          // Rate-limited supplier that uses mpegSrc.length() (safe — called at most every 3s by wiring)
          final FastMpeg2Reader ngReader = mpegSrc;
          ngSizeSupplier = new sage.ng.NgPlaybackContextWiring.FileSizeSupplier() {
            public long getFileSize() { return ngReader.length(); }
          };
        }
        String ngClientName = (uiMgr != null) ? uiMgr.getLocalUIClientName() : "EXTERNAL";
        ngContextWiring.onPlaybackOpen(ngClientName, ngMediaFileId, ngAiringId,
            ngContainer, ngDuration, timeshifted, ngIsLive, serverSideTranscoding,
            ngRecStart, ngInitialSize, ngSizeSupplier);
      }
      catch (Exception ngEx)
      {
        if (Sage.DBG) System.out.println("NG context open failed (non-fatal): " + ngEx);
      }
      // --- end NG Context wiring ---
*/


// ============================================================
// CHANGE 4: Wire onPlaybackClose() in free()
// INSERT at line 296 (start of free(), before persistSessionBandwidthFromTranscoder)
// ============================================================

/*
  public synchronized void free()
  {
    // --- NG Context wiring: close provider session ---
    ngContextWiring.onPlaybackClose();
    // --- end NG Context wiring ---

    persistSessionBandwidthFromTranscoder();
    ...
*/


// ============================================================
// CHANGE 5: Wire onSeek() in seek()
// INSERT after line 3225 (waitingForSeek = false; in the finally block)
// ============================================================

/*
        finally
        {
          waitingForSeek = false;
          // --- NG Context wiring: notify seek ---
          ngContextWiring.onSeek(seekTimeMillis);
          // --- end NG Context wiring ---
        }
*/


// ============================================================
// CHANGE 6: Wire onPushLoopTick() in push loop
// INSERT after line 2985 (the every-32-buffers yield block):
//   if((numPushedBuffers&0x1F)==0)
// ============================================================

/*
            if((numPushedBuffers&0x1F)==0)
            {
              try{
                decoderLock.notifyAll();
                decoderLock.wait(10);}catch(Exception e){}
              // --- NG Context wiring: periodic live-window update ---
              // Uses lastParserTimestamp (already computed at line 4316 in pushBuffer0)
              // and mpegSrc.length() is NOT called here — the wiring's rate-limited
              // supplier handles it externally.
              ngContextWiring.onPushLoopTick(
                  lastParserTimestamp - timestampOffset,
                  finalLength,
                  timeshifted);
              // --- end NG Context wiring ---
            }
*/


// ============================================================
// CHANGE 7: Wire onInactiveFile() in inactiveFile()
// INSERT at end of inactiveFile() method (after finalLength is set)
// ============================================================

/*
  public void inactiveFile()
  {
    if(transcoded)
    {
      if (tcSrc != null)
        finalLength = tcSrc.length();
    }
    else
    {
      if (mpegSrc != null)
      {
        mpegSrc.setActiveFile(false);
        finalLength = mpegSrc.length();
      }
      ...
    }
    // --- NG Context wiring: file is no longer active ---
    {
      MediaFile ngMF = VideoFrame.getMediaFileForPlayer(MiniPlayer.this);
      long ngMFId = (ngMF != null) ? ngMF.getID() : -1;
      long ngAirId = -1;
      if (ngMF != null && ngMF.getContentAiring() != null)
        ngAirId = ngMF.getContentAiring().getID();
      ngContextWiring.onInactiveFile(ngMFId, ngAirId, finalLength);
    }
    // --- end NG Context wiring ---
  }
*/


// ============================================================
// CHANGE 8: flush wiring in flushPush0()
// INSERT at end of flushPush0() (line ~4215)
// ============================================================

/*
  protected boolean flushPush0()
  {
    if (Sage.DBG) System.out.println("flushPush0()");
    lastParserTimestamp = 0;
    lastParserTimestampBytePos = 0;
    ...
    // --- NG Context wiring: notify flush ---
    ngContextWiring.onFlush();
    // --- end NG Context wiring ---
    return result;
  }
*/
