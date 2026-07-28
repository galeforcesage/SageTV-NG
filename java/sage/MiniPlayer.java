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

/**
 *
 * @author  Narflex modified by JFT for STB chip
 */
public class MiniPlayer implements DVDMediaPlayer
{
  protected static final long GUESS_VALIDITY_DURATION = 1000;
  protected static final boolean ENABLE_DSM520_HACKS = Sage.getBoolean("enable_miniplayer_hacks", false);

  private static final int NUM_SAMPLES_BANDWIDTH_ESTIMATE = 5;
  private static final int NUM_SAMPLES_AVERAGE_BANDWIDTH_ESTIMATE = 20;

  private static final int MIN_DYNAMIC_VIDEO_BITRATE_KBPS = 50;
  private static final int BANDWIDTH_BUFFER_KBPS = 50; // increased from 30 because our new algorithm is more aggressive

  private java.nio.channels.SocketChannel clientSocket;
  private FastPusherReply clientInStream;
  private java.nio.ByteBuffer sockBuf = java.nio.ByteBuffer.allocateDirect(65536);

  public static final int MEDIACMD_INIT = 0;
  public static final int MEDIACMD_DEINIT = 1;
  public static final int MEDIACMD_OPENURL = 16;
  public static final int MEDIACMD_GETMEDIATIME = 17;
  public static final int MEDIACMD_SETMUTE = 18;
  public static final int MEDIACMD_STOP = 19;
  public static final int MEDIACMD_PAUSE = 20;
  public static final int MEDIACMD_PLAY = 21;
  public static final int MEDIACMD_FLUSH = 22;
  public static final int MEDIACMD_PUSHBUFFER = 23;
  public static final int MEDIACMD_GETVIDEORECT = 24;
  public static final int MEDIACMD_SETVIDEORECT = 25;
  public static final int MEDIACMD_GETVOLUME = 26;
  public static final int MEDIACMD_SETVOLUME = 27;
  public static final int MEDIACMD_FRAMESTEP = 28;
  public static final int MEDIACMD_SEEK = 29;

  public static final int MEDIACMD_DVD_STREAM = 36;
  public static final int MEDIACMD_DVD_NEWCELL = 32;
  public static final int MEDIACMD_DVD_CLUT = 33;

  public static final int PUSHBUFFER_SUBPIC_FLAG = 0x40;
  public static final int PUSHBUFFER_SUBPIC_PAL_FLAG = 0x200;
  public static final int SUBPIC_DISABLE_STREAM = 0x2000;
  public static final int PS_SUBPIC_DISABLE_STREAM = 0xBD3F;

  public MiniPlayer()
  {
    currState = NO_STATE;

  }

  protected boolean shouldPush(byte majorTypeHint, byte minorTypeHint)
  {
    //        if (minorTypeHint == MediaFile.MEDIASUBTYPE_MPEG2_PS) // || minorTypeHint == MediaFile.MEDIASUBTYPE_MPEG2_TS
    return true;
    //        else
    //            return false;
  }

  public boolean canFastLoad(byte majorTypeHint, byte minorTypeHint, String encodingHint, java.io.File file)
  {
    // They'll be the same type because that's already checked in VideoFrame
    // 8/28/08 - Don't do fast loading at all; we don't properly setup the new MpegReader in the
    // fastLoad method below since we've customized it so much in the main load method.
    return pushMode && !lowBandwidth && !serverSideTranscoding && downer == null;
  }

  public synchronized void fastLoad(byte majorTypeHint, byte minorTypeHint, String encodingHint, java.io.File file, String hostname, boolean timeshifted, long bufferSize, boolean waitUntilDone) throws PlaybackException
  {
    if (Sage.DBG) System.out.println("Mini Fast Load");
    int lastState = currState;
    addYieldDecoderLock();
    synchronized (decoderLock)
    {
      //			pushThreadCreated = false;

      if (tcSrc != null)
        tcSrc.close();
      if (mpegSrc != null)
        mpegSrc.close();
      tcSrc = null;
      mpegSrc = null;
      // rebuild the source
      //        currState = NO_STATE;
      //      timeGuessMillis = 0;
      //        guessTimestamp = Sage.eventTime();
      //        myRate = 1;
      eos = false;
      firstSeek = true;
      sendSeekPullNext = false;
      wasFastSwitch = true;
      boolean useMP3StreamWrapper = false;

      if (transcoded && minorTypeHint == MediaFile.MEDIASUBTYPE_MP3)
        useMP3StreamWrapper = true;

      this.timeshifted = timeshifted;
      if (bufferSize > 0 && hostname == null)
      {
        // Circular files don't work correctly with the MPEG2 pushers because they don't understand that concept. This is fixed by
        // having them go through the MediaServer which DOES understand circular files.
        if (Sage.DBG) System.out.println("MiniPlayer is going through the MediaServer to handle the circular file.");
        hostname = "localhost";
      }
      // For MP3 files we use JF's transcode wrapper; and for video & non-MP3 audio we use the media server's transcoder
      if (useMP3StreamWrapper)
      {
        if (Sage.DBG) System.out.println("MiniPlayer is using the MP3 stream wrapper");
        tcSrc = new Mpeg2Transcoder(file, hostname);
      }
      else if (rpSrc == null)
      {
        if (Sage.DBG) System.out.println("MiniPlayer is using the MPEG2 pusher");
        mpegSrc = new FastMpeg2Reader(file, hostname);
        mpegSrc.setActiveFile(timeshifted);
        MediaFile currMF = VideoFrame.getMediaFileForPlayer(MiniPlayer.this);
        sage.media.format.ContainerFormat currFileFormat = currMF.getFileFormat();
        if (currFileFormat != null && "true".equals(currFileFormat.getMetadataProperty("VARIED_FORMAT")))
          currFileFormat = sage.media.format.FormatParser.getFileFormat(file);
        mpegSrc.setStreamTranscodeMode(null, currFileFormat);
        if (currFileFormat != null && Sage.getBoolean("miniplayer/align_iframes_on_seek", true))
          mpegSrc.setIFrameAlign(true);
      }
      try
      {
        if(transcoded)
        {
          tcSrc.init(true, !timeshifted);
        }
        else if (rpSrc != null)
        {
          if (Sage.DBG) System.out.println("MiniPlayer is using the RemotePusher");
          MediaFile currMF = VideoFrame.getMediaFileForPlayer(MiniPlayer.this);
          sage.media.format.ContainerFormat currFileFormat = currMF.getFileFormat();
          if (currFileFormat != null && "true".equals(currFileFormat.getMetadataProperty("VARIED_FORMAT")))
            currFileFormat = sage.media.format.FormatParser.getFileFormat(file);
          rpSrc.openFile(file.getAbsolutePath(), (currFileFormat == null) ? "" : currFileFormat.getFullPropertyString(false), timeshifted, true);
        }
        else
        {
          mpegSrc.init(true, !timeshifted, usingRemuxer);
          checkForByteBasedSeeking(file);
        }
      }
      catch (java.io.IOException e)
      {
        System.out.println("Error initing MPEG2 stream:" + e);
        e.printStackTrace();
        throw new PlaybackException(PlaybackException.FILESYSTEM, 0);
      }
      if (!timeshifted && !transcoded && rpSrc == null)
        finalLength = mpegSrc.length();
      currFile = file;
      //			flushPush0();
      removeYieldDecoderLock();
      decoderLock.notifyAll();
    }
    //       currState = LOADED_STATE;

    //       currHintMajorType = majorTypeHint;
    //        currHintMinorType = minorTypeHint;
    //        currHintEncoding = encodingHint;

    //		languageIndex = 0;
    // For extenders, set the correct audio stream we're using for playback
    /*		if ((mediaExtender && pushMode) || hdMediaExtender)
		{
			sage.media.format.ContainerFormat cf = VideoFrame.getMediaFileForPlayer(this).getFileFormat();
			if (cf != null && cf.getNumAudioStreams() > 0)
			{
				sage.media.format.AudioFormat af = cf.getAudioFormat();
				int audioStreamType = 0xc000;
				// If we're transcoding then the original audio stream doesn't matter, just use 0xc0
				// unless we're using the remuxer....
     *///				if (af != null && ((!serverSideTranscoding && !transcoded)/* || (mpegSrc != null && mpegSrc.getTranscoder() instanceof RemuxTranscodeEngine)*/))
    /*				{
					String streamID = af.getId();
					if (streamID != null && streamID.length() > 0)
					{
						// See if it's just a stream ID or if it's 2 parts
						int dashIdx = streamID.indexOf('-');
						if (dashIdx == -1)
						{
							try
							{
								audioStreamType = (Integer.parseInt(streamID, 16) << 8);
							}
							catch (NumberFormatException nfe)
							{
								if (Sage.DBG) System.out.println("ERROR parsing audio stream ID " + streamID + " of: " + nfe);
							}
						}
						else
						{
							try
							{
								audioStreamType = (Integer.parseInt(streamID.substring(0, dashIdx), 16) << 8) |
									Integer.parseInt(streamID.substring(dashIdx + 1, dashIdx + 3), 16);
							}
							catch (NumberFormatException nfe)
							{
								if (Sage.DBG) System.out.println("ERROR parsing audio stream ID " + streamID + " of: " + nfe);
							}
						}
					}
				}
				audioTracks = cf.getAudioFormats();
				if (af != null)
				{
					for (int i = 0; i < audioTracks.length; i++)
					{
						if (audioTracks[i] == af)
						{
							languageIndex = i;
							break;
						}
					}
				}
				subpicTracks = cf.getSubpictureFormats();
				subpicIndex = 0;
				subpicOn = false;
				if (pushMode)
				{
					if (Sage.DBG) System.out.println("Setting audio stream for playback to be ID=0x" + Integer.toString(audioStreamType, 16));
					DVDStream(0, audioStreamType);
					if (bdp != null)
					{
						matchBDSubpictureToAudio();
					}
				}
			}
		}
		if (lastState == PLAY_STATE)
			play();
		else
			pause();
     */    }

  public boolean frameStep(int amount)
  {
    if (mediaExtender && currState == PAUSE_STATE && !eos)
    {
      boolean retval = true;
      if (mcsr != null && mcsr.supportsFrameStep())
      {
        addYieldDecoderLock();
        synchronized (decoderLock)
        {
          frameStep0(amount);
          removeYieldDecoderLock();
          decoderLock.notifyAll();
        }
      }
      else
      {
        addYieldDecoderLock();
        synchronized (decoderLock)
        {
          playPush0();
          removeYieldDecoderLock();
          decoderLock.notifyAll();
        }
        try{Thread.sleep(10);}catch(Exception e){}
        addYieldDecoderLock();
        synchronized (decoderLock)
        {
          pausePush0();
          removeYieldDecoderLock();
          decoderLock.notifyAll();
        }
      }
      return retval;
    }
    return false;
  }

  public synchronized void free()
  {
    // --- NG Context wiring: close provider session ---
    ngContextWiring.onPlaybackClose();
    // --- end NG Context wiring ---

    persistSessionBandwidthFromTranscoder();
    if (uiMgr != null)
      uiMgr.putFloat("miniplayer/last_volume", curVolume);
    synchronized (decoderLock)
    {
      if (currState != STOPPED_STATE)
        stop();
      currState = NO_STATE;
      // Close the remote pusher source before we do closeDriver0 so that it won't try to push anymore
      // data after the connection is dead. This will have the side effect of the connection
      // being torn down..which is fine as we ignore any errors that occur in closeDriver0 and the
      // miniclient has internal mechanisms to clean itself up when it's shutdown that way.
      if (rpSrc != null)
        rpSrc.close();
      if (Sage.DBG) System.out.println("Closing down MiniPlayer");
      closeDriver0();
    }
    if (clientInStream != null)
    {
      try { clientInStream.close(); } catch(Exception e){}
      clientInStream = null;
    }
    if (clientSocket != null)
    {
      try { clientSocket.close(); } catch(Exception e){}
      clientSocket = null;
    }
    if (tcSrc != null)
      tcSrc.close();
    if (mpegSrc != null)
      mpegSrc.close();
    mpegSrc = null;
    tcSrc = null;
    rpSrc = null;
    currFile = null;
    currHintMajorType = currHintMinorType = (byte)0;
    currHintEncoding = null;
    videoDimensions = null;
    currCCState = 0;
    eos = false;
    timeGuessMillis = 0;
    guessTimestamp = 0;
    timestampOffset = 0;
    serverSideTranscoding = false;
    pushMode = false;
    currMute = false;
    uiMgr = null;
    colorKey = null;
    pushThread = null;
    downer = null;
    timeshifted = false;
    finalLength = 0;
    transcoded = false;
    lastVideoSrcRect = lastVideoDestRect = null;
    myRate = 0;
    freeSpace = 0;
    curVolume = 1.0f;
    maxAvailBufferSize = 0;
    lastRateAdjustTime = 0;
    lastEstimatedPushBitrate = lastAverageEstimatedPushBitrate = 0;
    lastEstimatedStreamBitrate = lastAverageEstimatedStreamBitrate = 0;
    clientReportedPlayState = 0;
    clientReportedMediaTime = 0;
    lastParserTimestamp = 0;
    lastParserTimestampBytePos = 0;

    pushThreadCreated = false;
    needToPlay = false;
    dynamicRateAdjust = false;
    numPushedBuffers = 0;
    sentDiscardPtsFlag = false;
    sentTrickmodeFlag = false;
    lastMediaTime = lastMediaTimeBase = lastMediaTimeCacheTime = 0;
    if (unmountRequired != null)
    {
      java.io.File removeMe = unmountRequired;
      unmountRequired = null;
      FSManager.getInstance().releaseISOMount(removeMe);
    }
  }

  public int getClosedCaptioningState()
  {
    return CC_DISABLED;
  }

  public java.awt.Color getColorKey()
  {
    return colorKey;
  }

  public long getDurationMillis()
  {
    FastMpeg2Reader mySrc = mpegSrc;
    Mpeg2Transcoder mytcSrc = tcSrc;
    long duration;
    if(transcoded == true)
    {
      duration = (mytcSrc == null || timeshifted) ? 0 : mytcSrc.getDurationMillis();
    }
    else if (rpSrc != null)
    {
      duration = timeshifted ? 0 : rpSrc.getDurationMillis();
    }
    else
    {
      duration = (mySrc == null || timeshifted || byteBasedSeeking) ? 0 : mySrc.getDurationMillis();
    }
    if (Sage.DBG) System.out.println("getDuration : "+ duration);
    return duration;
  }

  public java.io.File getFile()
  {
    return currFile;
  }

  public synchronized long getMediaTimeMillis()
  {
    long rv;
    if (Sage.eventTime() - guessTimestamp <  GUESS_VALIDITY_DURATION || waitingForSeek) // after seeks it doesn't know the right time at first
    {
      return timeGuessMillis;
    }
    // If the file's being downloaded and the player is waiting for a reseek on the server
    // this call might end up taking a while; so don't do it in that case.
    if (downer != null && downer.isClientWaitingForRead() && guessTimestamp > lastMediaTimeCacheTime && timeGuessMillis > 0)
    {
      return timeGuessMillis;
    }
    if ((transcoded ? tcSrc !=null : mpegSrc != null) && (myRate != 1.0f) && (!hdMediaExtender || bdp != null))
    {
      // We can't trust the Sigma driver in this case so we need to guess from the MPEG parser
      rv = transcoded ? tcSrc.getLastParsedTimeMillis() : mpegSrc.getLastParsedTimeMillis();
      // --- NG Context wiring: pull-mode metadata update ---
      if (!pushMode) ngContextWiring.onPullModeTick(rv, finalLength, timeshifted);
      // --- end NG Context wiring ---
      return rv;
    }
    if (detailedPushBufferStats && pushMode && rpSrc == null)
    {
      long currMediaTime = clientReportedMediaTime + timestampOffset;
      if (lastMediaTimeBase == currMediaTime && currState == PLAY_STATE)
      {
        lastMediaTime = (Sage.eventTime() - lastMediaTimeCacheTime) + lastMediaTime;
      }
      else
        lastMediaTime = lastMediaTimeBase = currMediaTime;
      lastMediaTimeCacheTime = Sage.eventTime();
      return lastMediaTime;
    }
    rv = getNativeMediaTimeNoSync();
    // --- NG Context wiring: pull-mode metadata update ---
    if (!pushMode) ngContextWiring.onPullModeTick(rv, finalLength, timeshifted);
    // --- end NG Context wiring ---
    return rv;
  }

  private long getNativeMediaTimeNoSync()
  {
    long nativeTime;
    addYieldDecoderLock();
    synchronized (decoderLock)
    {
      nativeTime = getMediaTimeMillis0();
      removeYieldDecoderLock();
      decoderLock.notifyAll();
    }
    if (nativeTime <= 500 && !hdMediaExtender) // after seeks it doesn't know the right time at first
    {
      return timeGuessMillis;
    }
    else
    {
      long otherTime = 0;
      if(transcoded)
      {
        otherTime = (tcSrc != null ? tcSrc.getFirstTimestampMillis() : 0);
      }
      else if (rpSrc != null)
      {
        otherTime = rpSrc.getFirstPTS() / 90;
        // We added another special case here to autodetect PTS rollover if we
        // see a PTS that is more than 2^33/4 less than the initial PTS. So this should catch cases
        // with durations up to 20 hours in length.
        if (otherTime - nativeTime > 100000 && rpSrc.didPTSRollover() ||
            nativeTime < otherTime - FastMpeg2Reader.MAX_PTS/360)
        {
          otherTime = otherTime - FastMpeg2Reader.MAX_PTS/90;
        }
      }
      else if (!byteBasedSeeking)
      {
        otherTime = (mpegSrc != null ? mpegSrc.getFirstTimestampMillis() : 0);
        if (otherTime - nativeTime > 100000 && mpegSrc != null && mpegSrc.didPTSRollover())
        {
          otherTime = otherTime - FastMpeg2Reader.MAX_PTS/90;
        }
        if (mpegSrc != null && mpegSrc.usesCustomTimestamps())
        {
          otherTime -= mpegSrc.getCustomTimestampDiff()/90;
        }
      }
      otherTime -= timestampOffset;
      // max against 0 because there's no reason for these times to ever be negative
      long rv;
      // Also use the first path here for when we do a fast switch and the timestamps will be less then zero while the buffer empties
      if (nativeTime - otherTime > -2000 || wasFastSwitch) // for when the start timestamps aren't as well aligned
        rv = Math.max(0, nativeTime - otherTime);
      else // There's cases where the driver resets the timestamps for no reason
        rv = Math.max(0, nativeTime);
      // There's a case with the custom timestamps where the PTS will rollover in the file, but due to the demux buffering
      // the timestamp offset we're using is looking beyond this. We can however correct for this. :)
      if (mpegSrc != null && mpegSrc.usesCustomTimestamps() && rv > FastMpeg2Reader.MAX_PTS/90)
      {
        rv -= FastMpeg2Reader.MAX_PTS/90;
      }
      //System.out.println("nativeTime=" + nativeTime + " otherTime=" + otherTime + " timestampOffset=" + timestampOffset +
      //	" parserTime=" + (mpegSrc != null ? mpegSrc.getLastParsedTimeMillis() : 0) + " rv=" + rv);
      return rv;
    }
  }
  
  public boolean getMute()
  {
    return currMute;
  }

  public int getPlaybackCaps()
  {
    return PAUSE_CAP | SEEK_CAP; /* FRAME_STEP_FORWARD_CAP | */
  }

  public float getPlaybackRate()
  {
    return myRate;
  }

  public int getState()
  {
    return eos ? EOS_STATE : currState;
  }

  public boolean hitTrickPlayEOS()
  {
    if (rpSrc != null)
      return rpSrc.isTrickPlayEOS();
    return false;
  }

  public boolean supportsTrickPlayEOS()
  {
    return rpSrc != null;
  }

  public int getTransparency()
  {
    return (colorKey != null) ? BITMASK : TRANSLUCENT;
  }

  public java.awt.Dimension getVideoDimensions()
  {
    if (videoDimensions == null && currState >= LOADED_STATE && currState != STOPPED_STATE)
    {
      long now = Sage.eventTime();
      if (now - lastVideoDimRetry > 500)
      {
        lastVideoDimRetry = now;
        videoDimensions = getVideoDimensions0();
        if (videoDimensions != null)
        {
          if (Sage.DBG) System.out.println("Late video dimension detection: " + videoDimensions);
          if (uiMgr != null)
          {
            ZRoot rooty = uiMgr.getRootPanel();
            if (rooty != null)
              rooty.appendToDirty(new java.awt.Rectangle(0, 0, rooty.getWidth(), rooty.getHeight()));
          }
        }
      }
    }
    return videoDimensions;
  }

  public synchronized float getVolume()
  {
    if (clientSocket == null) return 0;
    return curVolume;
  }

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
      else if (rpSrc != null && timeshifted)
      {
        synchronized (decoderLock)
        {
          try
          {
            rpSrc.sendInactiveFile();
          }
          catch (java.io.IOException e)
          {
            if (Sage.DBG) System.out.println("ERROR with RemotePusher communication, kill the UI!");
            e.printStackTrace();
            connectionError();
          }
        }
      }
    }
    timeshifted = serverSideTranscoding;

    // --- NG Context wiring: file is no longer active ---
    try
    {
      MediaFile ngMF = VideoFrame.getMediaFileForPlayer(MiniPlayer.this);
      long ngMFId = (ngMF != null) ? ngMF.getID() : -1;
      long ngAirId = -1;
      if (ngMF != null && ngMF.getContentAiring() != null)
        ngAirId = ngMF.getContentAiring().getID();
      ngContextWiring.onInactiveFile(ngMFId, ngAirId, finalLength);
    }
    catch (Exception ngEx)
    {
      if (Sage.DBG) System.out.println("NG context inactiveFile failed (non-fatal): " + ngEx);
    }
    // --- end NG Context wiring ---
  }
  
  void checkForByteBasedSeeking(java.io.File file) {
    if (Sage.getBoolean("disable_byte_based_seek_check", true)) return;
    if (mpegSrc != null) {
      if (uiMgr.getBoolean("force_byte_based_seeking", false)) {
        System.out.println("Forcing byte based seeking due to property setting");
        byteBasedSeeking = true;
      } else if (!timeshifted) {
        // We can't check duration for files that are currently recording because
        // the parser doesn't check the duration in that case and it's problematic
        // if we change that.
        long parserDuration = mpegSrc.getDurationMillis();
        if (parserDuration < 0) {
          System.out.println("Using byte based seeking due to invalid duration from parser");
          byteBasedSeeking = true;
        } else {
          long fileDur = VideoFrame.getMediaFileForPlayer(this).getDuration(file);
          long diff = Math.abs(parserDuration - fileDur);
          if (fileDur > Sage.MILLIS_PER_MIN && diff > fileDur/4) {
            byteBasedSeeking = true;
            System.out.println("Using byte based seeking due to duration mismatch between " +
                "parser (" + parserDuration + ") and recording (" + fileDur + ")");
          }
        }
      }
    }
  }

  /***
   * Determines if this instance of the miniplayer is transcoding
   * @return Returns true if the miniplayer is transcoding 
   */
  public boolean isTranscoding()
  {
    if(mpegSrc != null && mpegSrc.getTranscoder() != null && serverSideTranscoding)
    {
      return true;
    }      
    
    return false;
  }
  
  public void load(byte majorTypeHint, byte minorTypeHint, String encodingHint, java.io.File file, String hostname, boolean timeshifted, long bufferSize) throws PlaybackException
  {
    VideoFrame vf = VideoFrame.getVideoFrameForPlayer(MiniPlayer.this);
    MediaFile currMF;
    uiMgr = vf.getUIMgr();
    mcsr = (MiniClientSageRenderer) uiMgr.getRootPanel().getRenderEngine();
    MetaImage.clearHiResNativeCache(mcsr);
    disableVideoPositioning = mcsr.gfxPositionedVideo();
    currMF = VideoFrame.getMediaFileForPlayer(MiniPlayer.this);
    if (FileDownloader.isDownloading(file))
      downer = FileDownloader.getFileDownloader(file);
    else
      downer = null;
    CaptureDevice capDev = currMF.guessCaptureDeviceFromEncoding();
    if (capDev != null && mcsr != null)
    {
      // Enable the special mode for when we are using Qian's HDHRPrime support along w/ a Bruno client.
      hdhrPrimeSpecial = (capDev.getName().startsWith("HDHR")) &&
          capDev.isNetworkEncoder() &&
          Sage.getBoolean("enable_detection_of_hdhrprime_custom_network_encoder", false) &&
          mcsr.isMediaExtender() && mcsr.supports3DTransforms();
      if (hdhrPrimeSpecial && Sage.DBG)
        System.out.println("Detected use of BRUNO device and HDHRPrime special network encoder....use alternate streaming mode w/ timestamp fix");
    }

    synchronized (this)
    {
      // Do this before we load the player so we don't screw up the driver if the mount fails due to network issues
      if (file.isFile() && currMF.isBluRay())
      {
        // This is an ISO image instead of a DVD directory; so mount it and then change the file path to be the image
        java.io.File mountDir = FSManager.getInstance().requestISOMount(file, uiMgr);
        if (mountDir == null)
        {
          if (Sage.DBG) System.out.println("FAILED mounting ISO image for BluRay playback");
          throw new PlaybackException(PlaybackException.FILESYSTEM, 0);
        }
        unmountRequired = mountDir;
        if (new java.io.File(mountDir, "bdmv").isDirectory())
          file = new java.io.File(mountDir, "bdmv");
        else if (new java.io.File(mountDir, "BDMV").isDirectory())
          file = new java.io.File(mountDir, "BDMV");
        else
          file = mountDir;
      }

      // Set the curVolume field now so that if the open fails for any reason; the call to free() doesn't set the property
      // to 1.0f; it instead retains its value
      curVolume = uiMgr.getFloat("miniplayer/last_volume", 1.0f);

      if (initDriver0((vf == null || vf.getDisplayAspectRatio() > 1.40) ? 1 :0 ) == 0)
        throw new PlaybackException();

      // See if we need to transcode the video or not. This is dependent upon two things. One is whether or not
      // the client supports the format that the media is in, the other is whether or not it has the bandwidth
      // to handle the transfer and needs to be transrated(coded)
      // NOTE: If they want to use optimized VOB transcoding they can set miniclient/vob_transcode_mode to DVDAudioOnly. But that causes
      // A/V sync problems in some cases.
      if (Sage.WINDOWS_OS && Sage.get("media_server/conservative_transcode", null) == null)
      {
        // Base the default for this on CPU speed which we can get from the registry
        int cpuSpeedMHz = Sage.readDwordValue(Sage.HKEY_LOCAL_MACHINE, "HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0", "~MHz");
        if (Sage.DBG && cpuSpeedMHz != 0)
          System.out.println("Detected CPU speed to be ~" + cpuSpeedMHz + "MHz");
        if (cpuSpeedMHz >= 1900)
        {
          Sage.putBoolean("media_server/conservative_transcode", false);
        }
      }
      String prefTranscodeMode = "DVD";
      sage.media.format.ContainerFormat inputFormat = currMF.getFileFormat();
      sage.media.format.AudioFormat inAudio = null;
      boolean using6ChAudioTranscode = false;
      if (inputFormat != null)
        inAudio = inputFormat.getAudioFormat();
      if (inAudio != null && inAudio.getChannels() >= 5)
      {
        prefTranscodeMode = Sage.getBoolean("media_server/conservative_transcode", false) ? uiMgr.get("miniclient/conservative_transcode_mode_6ch", "SVCD6Ch") :
          uiMgr.get("miniclient/transcode_mode_6ch", "DVD6Ch");
        using6ChAudioTranscode = true;
      }
      else
        prefTranscodeMode = Sage.getBoolean("media_server/conservative_transcode", false) ? uiMgr.get("miniclient/conservative_transcode_mode", "SVCD") :
          uiMgr.get("miniclient/transcode_mode", "DVD");

      /*
       * We need to decide whether to use push or pull mode and also whether or not to use the transocder.
       * The media format comparisons for this are NOT done yet, but will be implemented soon by adding
       * a MediaContainerFormat and MediaStreamFormat set of classes that allow detailed description of a
       * file's stream information.
       *
       * For now, we use pull mode if the client supports it and the bitrate is over 2 Mbps from the UI estimates.
       * We consider the client to support pull mode if they're pull mode format list is not empty.
       * We will also force pull mode if the client doesn't have any format support for push mode but does for pull mode.
       * We will also use push mode if the client has a fixed push format property setting.
       * We transcode if there's a fixed push format, or if we're pushing and the BW detected is under 2Mbps
       */
      detailedPushBufferStats = false;
      currState = NO_STATE;
      timeGuessMillis = 0;
      guessTimestamp = Sage.eventTime();
      myRate = 1;
      eos = false;
      firstSeek = true;
      sendSeekPullNext = false;
      wasFastSwitch = false;
      boolean useMP3StreamWrapper = false;
      long uiBandwidthEstimate = 0;
      boolean clientDoesMPEG2Push = true;
      boolean clientDoesPull = false;
      boolean clientCanDoMpeg4 = false;
      boolean clientCanDoMPEGHD = false;
      // Modern H.264 MPEG-TS push eligibility (set below once mcsr is known).
      boolean h264PushOK = false;
      hdMediaPlayer = false;
      String fixedPushFormat = null;
      String fixedPushRemuxFormat = null;
      boolean containerSupported = false;
      boolean audioCodecSupported = false;
      boolean videoCodecSupported = false;
      mediaExtender = true;
      lowBandwidth = false;
      enableBufferFillPause = Sage.getBoolean("miniclient/enable_buffer_fill_on_seek", false);
      boolean pureLocal = false;
      boolean httpls = false;
      // NG-first ruling (ROADMAP: "NG-first decision ordering in MiniPlayer.load()").
      // Computed once, as early as mcsr is known, so every legacy determination
      // below can be read as `if (ngSession) { honor surface plan } else { legacy }`
      // instead of bolting on scattered !isNgCapableSession() patches after the fact.
      boolean ngSession = false;
      isMpeg2PS = sage.media.format.MediaFormat.MPEG2_PS.equals(currMF.getContainerFormat());
      if (mcsr != null)
      {
        ngSession = mcsr.isNgCapableSession();
        mediaExtender = mcsr.isMediaExtender();
        hdMediaPlayer = mcsr.isStandaloneMediaPlayer();
        if (mcsr.isMiniClientColorKeyed())
          colorKey = mcsr.getMiniClientColorKey();
        clientDoesMPEG2Push = mcsr.isSupportedPushContainerFormat(sage.media.format.MediaFormat.MPEG2_PS);
        // Modern H.264 MPEG-TS push: engage ONLY when the client positively
        // advertises H.264 video AND MPEG2-TS push. Legacy 9.2.16 clients
        // (HD100/HD200 hardware extenders, classic placeshifter) that don't keep
        // the existing mpeg4/DVD dynamic path unchanged. Kill-switch:
        // miniplayer/enable_h264_push_transcode (default true).
        h264PushOK = Sage.getBoolean("miniplayer/enable_h264_push_transcode", true)
            && mcsr.isSupportedVideoCodec(sage.media.format.MediaFormat.H264)
            && mcsr.isSupportedPushContainerFormat(sage.media.format.MediaFormat.MPEG2_TS);
        detailedPushBufferStats = mcsr.isDetailedPushBufferStats();
        if (mcsr.isSupportedVideoCodec("MPEG2-VIDEO@HL"))
        {
          clientCanDoMPEGHD = true;
          if (mediaExtender)
            hdMediaExtender = true;
        }
        maxPushBufferSize = mcsr.getPushBufferSizeLimit();
        if (maxPushBufferSize == 0)
          maxPushBufferSize = 32768;
        maxPushBufferSize = Math.min(131072, maxPushBufferSize);
        int forceMaxPush = Sage.getInt("miniplayer/forced_max_push_size", 0);
        if (forceMaxPush > 0)
          maxPushBufferSize = forceMaxPush;
        // Check for transcoding mode support
        if (mcsr.isSupportedVideoCodec(sage.media.format.MediaFormat.MPEG4_VIDEO))
          clientCanDoMpeg4 = true;
        if (hostname != null && hostname.startsWith("file://"))
        {
          clientDoesPull = true;
          pureLocal = true;
        }
        else
        {
          containerSupported = clientDoesPull = mcsr.isSupportedPullContainerFormat(currMF.getContainerFormat());
          
          // Check the audio & video formats
          String vidForm = currMF.getPrimaryVideoFormat();
          String audForm = currMF.getPrimaryAudioFormat();
          
          videoCodecSupported = mcsr.isSupportedVideoCodec(vidForm);
          audioCodecSupported = mcsr.isSupportedAudioCodec(audForm);
          
          if (clientDoesPull)
          {
            if (vidForm.length() > 0 && !mcsr.isSupportedVideoCodec(vidForm))
              clientDoesPull = false;
            if (audForm.length() > 0 && !mcsr.isSupportedAudioCodec(audForm))
              clientDoesPull = false;
          }
          fixedPushFormat = mcsr.getFixedPushMediaFormat();
          fixedPushRemuxFormat = mcsr.getFixedPushRemuxFormat();

          // NG-first: compute the surface capability verdict BEFORE the legacy
          // iPhoneMode HLS latch so transport follows the honest surface plan.
          // Bandwidth-independent (availableBwKbps=0) -- the authoritative
          // decision with real bandwidth is still computed later (~L1094); this
          // early pass only decides pull(DIRECT_PLAY) vs hls(TRANSCODE) for the
          // httpls gate. evaluateSurfaces is pure, so the double call is safe.
          String ngEarlyDelivery = null;
          sage.client.PlaybackDecisionEngine.PlaybackDecision ngEarlyDecision = null;
          if (ngSession && Sage.getBoolean("miniplayer/use_playback_surfaces", true))
          {
            sage.client.PlaybackSurfaceSet ngEarlySurfaces = mcsr.getPlaybackSurfaces();
            sage.media.format.ContainerFormat ngEcf = currMF.getFileFormat();
            if (ngEarlySurfaces != null && !ngEarlySurfaces.isEmpty())
            {
              int ngEw = 0, ngEh = 0, ngEkbps = 0;
              boolean ngEint = false;
              if (ngEcf != null && ngEcf.getVideoFormat() != null)
              {
                ngEw = ngEcf.getVideoFormat().getWidth();
                ngEh = ngEcf.getVideoFormat().getHeight();
                ngEint = ngEcf.getVideoFormat().isInterlaced();
              }
              if (ngEcf != null && ngEcf.getBitrate() > 0) ngEkbps = ngEcf.getBitrate() / 1000;
              java.util.List<sage.client.PlaybackDecisionEngine.SurfaceDecision> ngEranked =
                  sage.client.PlaybackDecisionEngine.evaluateSurfaces(ngEarlySurfaces,
                      currMF.getContainerFormat(), currMF.getPrimaryVideoFormat(),
                      currMF.getPrimaryAudioFormat(), ngEw, ngEh, ngEkbps, 0, ngEint, ngEcf,
                      mcsr.getCurrentClientAudioLanguage());
              if (!ngEranked.isEmpty())
              {
                ngEarlyDelivery = ngEranked.get(0).chosenDeliveryMode;
                ngEarlyDecision = ngEranked.get(0).decision;
              }
            }
          }

          // Legacy iPhoneMode HLS latch, now gated by the NG surface verdict
          // (ROADMAP: "NG-first decision ordering"). When NG's surface plan
          // wants a non-hls delivery (pull for DIRECT_PLAY), SUPPRESS httpls and
          // use pull: the browser's bridge rewrites the stv:// / bare-path URL
          // to <bridge>/rawmedia (byte-range disk read) for native decode. When
          // the plan wants hls (TRANSCODE), KEEP httpls -> server-side NVENC HLS.
          // Non-NG / no-surface sessions are unchanged (legacy latch).
          if (mcsr.isIOSClient() && (currMF.isVideo() || currMF.isTV()))
          {
            boolean ngPullOverride = ngSession && ngEarlyDelivery != null
                && !"hls".equals(ngEarlyDelivery)
                && Sage.getBoolean("miniplayer/ng_suppress_httpls_for_pull", true);
            if (ngPullOverride)
            {
              clientDoesPull = true; // pull mode; httpls stays false
              if (Sage.DBG) System.out.println("MiniPlayer: NG-first: iPhoneMode HLS latch SUPPRESSED"
                  + " (surface delivery=" + ngEarlyDelivery + " decision=" + ngEarlyDecision
                  + "); using pull -> browser bridge /rawmedia instead of iosstream");
            }
            else
            {
              clientDoesPull = httpls = true;
              if (Sage.DBG && ngSession) System.out.println("MiniPlayer: NG-first: iPhoneMode HLS latch"
                  + " active (surface delivery=" + ngEarlyDelivery + ") -> server-side HLS/transcode");
            }
          }

          uiBandwidthEstimate = mcsr.getEstimatedBandwidth();
          // Disable transcoding on the fly
          if (uiBandwidthEstimate < 500000 && (clientCanDoMpeg4 || httpls))
          {
            // No estimated BW from the UI. Do a push to the MiniClient before it's setup and it'll
            // just dump that buffer, but we'll get to see how much time it took
            int oldPriority = Thread.currentThread().getPriority();
            byte[] buf = new byte[16384];
            for (int i = 0; i < buf.length; i++)
              buf[i] = (byte)(i & 0xFF);
            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
            // Be sure other threads don't interfere with our bandwidth calculation by being the highest priority
            if (Sage.DBG) System.out.println("MiniPlayer was not able to get a bandwidth estimate from the UI system, sending data to get its own estimate...");
            // This has always been a problem. So here's the new idea. We'll do this with 16k first and see how long it takes round trip.
            // Then we will do it with 32k. The difference in time between those two is a good basis for how fast we can send 16k and we use
            // that for our bandwidth calculation.
            try
            {
              // Do a first one that doesn't actually count just to prep it
              long t0 = Sage.eventTime();
              sockBuf.clear();
              sockBuf.putInt(MEDIACMD_PUSHBUFFER<<24 | (buf.length+(detailedPushBufferStats ? 18 : 8)));
              sockBuf.putInt(buf.length);
              sockBuf.putInt(0);
              if (detailedPushBufferStats)
              {
                sockBuf.putShort((short)0);
                sockBuf.putShort((short)0);
                sockBuf.putShort((short)0);
                sockBuf.putInt(0);
              }
              sockBuf.put(buf, 0, buf.length);
              sockBuf.flip();
              while (sockBuf.hasRemaining())
                clientSocket.write(sockBuf);
              clientInStream.readInt();
              if (detailedPushBufferStats)
              {
                clientReportedMediaTime = clientInStream.readInt();
                clientReportedPlayState = clientInStream.readByte();
              }
              long t1 = Sage.eventTime();
              sockBuf.clear();
              sockBuf.putInt(MEDIACMD_PUSHBUFFER<<24 | (buf.length+(detailedPushBufferStats ? 18 : 8)));
              sockBuf.putInt(buf.length);
              sockBuf.putInt(0);
              if (detailedPushBufferStats)
              {
                sockBuf.putShort((short)0);
                sockBuf.putShort((short)0);
                sockBuf.putShort((short)0);
                sockBuf.putInt(0);
              }
              sockBuf.put(buf, 0, buf.length);
              sockBuf.flip();
              while (sockBuf.hasRemaining())
                clientSocket.write(sockBuf);
              clientInStream.readInt();
              if (detailedPushBufferStats)
              {
                clientReportedMediaTime = clientInStream.readInt();
                clientReportedPlayState = clientInStream.readByte();
              }
              long t2 = Sage.eventTime();
              for (int i = 0; i < 2; i++)
              {
                sockBuf.clear();
                sockBuf.putInt(MEDIACMD_PUSHBUFFER<<24 | (buf.length+(detailedPushBufferStats ? 18 : 8)));
                sockBuf.putInt(buf.length);
                sockBuf.putInt(0);
                if (detailedPushBufferStats)
                {
                  sockBuf.putShort((short)0);
                  sockBuf.putShort((short)0);
                  sockBuf.putShort((short)0);
                  sockBuf.putInt(0);
                }
                sockBuf.put(buf, 0, buf.length);
                sockBuf.flip();
                while (sockBuf.hasRemaining())
                  clientSocket.write(sockBuf);
              }
              for (int i = 0; i < 2; i++)
              {
                clientInStream.readInt();
                if (detailedPushBufferStats)
                {
                  clientReportedMediaTime = clientInStream.readInt();
                  clientReportedPlayState = clientInStream.readByte();
                }
              }
              long t3 = Sage.eventTime();

              long doubleTime = t3 - t2;
              long singleTime = Math.min(t2 - t1, t1 - t0);
              if (singleTime >= doubleTime)
              {
                if (Sage.DBG) System.out.println("Not using optimized bandwidth detection because the numbers didn't align");
                singleTime = doubleTime/2;
              }
              uiBandwidthEstimate = Math.max((buf.length + 12)*8000/Math.max(1, Math.max(Math.min(150, singleTime), doubleTime - singleTime)),
                  (buf.length + 12)*8000/Math.max(1, singleTime));
              if (Sage.DBG) System.out.println("Bandwidth test base=" + singleTime + " base*2=" + doubleTime + " BW=" + uiBandwidthEstimate);
            }
            catch (Exception e)
            {
              System.out.println("ERROR estimating MiniPlayer bandwidth of:" + e);
            }
            Thread.currentThread().setPriority(oldPriority);
            if (!mcsr.isLocalConnection() && uiBandwidthEstimate < 10000000 &&
                uiBandwidthEstimate >= Sage.getInt("miniplayer/min_bandwidth_for_no_transcode", 2000000) && Sage.getBoolean("miniplayer/wan_prevent_push", true))
            {
              if (Sage.DBG) System.out.println("Detected non-LAN connection under 10Mbps but above set limit (" + uiBandwidthEstimate +
                  "), force it to transcode mode");
              uiBandwidthEstimate = Sage.getInt("miniplayer/min_bandwidth_for_no_transcode", 2000000) - 1000;
            }
            mcsr.addDataToBandwidthCalc(uiBandwidthEstimate/8, 1000);//buf.length + 12, Math.max(1, doubleTime - singleTime));
          }
          else if (uiBandwidthEstimate == 0)
            uiBandwidthEstimate = 50000000; // not an extender that can support low bandwidth transcode
          if (Sage.DBG) System.out.println("MiniPlayer got an estimate from the UI on bandwidth of " + uiBandwidthEstimate/1000 + "Kbps");
          // Set the average to be our initial estimate so we can use it to filter out bad estimated bandwidth values initially
          lastAverageEstimatedPushBitrate = (int)uiBandwidthEstimate;
        }
      }
      else
      {
        colorKey = null;
      }

      // NOTE: We should really check the media's rate against our bandwidth and not use 2Mbps as the bounds
      if (!pureLocal && mcsr != null && (mcsr.isSupportedPushContainerFormat(sage.media.format.MediaFormat.MPEG2_PS) ||
          mcsr.isSupportedPushContainerFormat(sage.media.format.MediaFormat.MPEG2_TS)) && uiBandwidthEstimate < Sage.getInt("miniplayer/min_bandwidth_for_no_transcode", 2000000) && clientCanDoMpeg4)
      {
        lowBandwidth = true;
      }

      boolean useOriginalAudioTrack = true;

      // --- Profile-driven playback decision (schema v2) ---
      // If a managed client has a resolved profile, consult the PlaybackDecisionEngine
      // to influence the transcoding/remux/direct play decision.
      // For legacy clients (no profile), this block is skipped and the existing logic runs unchanged.
      sage.client.ClientProfile effectiveProfile = (mcsr != null) ? mcsr.getResolvedProfile() : null;
      sage.client.PlaybackDecisionEngine.PlaybackDecision profileDecision = null;
      if (effectiveProfile != null && currMF != null)
      {
        String mediaContainer = currMF.getContainerFormat();
        String mediaVideo = currMF.getPrimaryVideoFormat();
        sage.media.format.ContainerFormat cf = currMF.getFileFormat();
        // 2.1.0004: multi-audio-stream selection for ALL clients (Legacy + 2.1).
        // Instead of getPrimaryAudioFormat() (which picks lowest orderIndex,
        // ignoring language/channels), use the smart selector that filters by
        // server language, sorts by quality, and prefers native decode. For
        // Legacy V1 clients the coarse AUDIO_CODECS set is used for the
        // native-decode check; for Protocol 2.1 the surface-based overload
        // runs in the evaluateSurfaces path below.
        String mediaAudio;
        int legacyChosenAudioStreamIndex = -1;
        if (cf != null && cf.getAudioFormats(false) != null && cf.getAudioFormats(false).length > 1
            && mcsr != null)
        {
          @SuppressWarnings("rawtypes")
          java.util.Set v1Audio = mcsr.getEffectiveAudioCodecs();
          sage.client.PlaybackDecisionEngine.AudioStreamChoice legacyAsc =
              sage.client.PlaybackDecisionEngine.selectBestAudioStreamLegacy(v1Audio, cf);
          if (legacyAsc != null && legacyAsc.audioFormat != null)
          {
            mediaAudio = legacyAsc.audioFormat.getFormatName();
            legacyChosenAudioStreamIndex = legacyAsc.audioFormat.getOrderIndex();
            if (Sage.DBG) System.out.println("MiniPlayer: legacy multi-audio selection: "
                + legacyAsc + " (overrides getPrimaryAudioFormat)");
          }
          else
          {
            mediaAudio = currMF.getPrimaryAudioFormat();
          }
        }
        else
        {
          mediaAudio = currMF.getPrimaryAudioFormat();
        }
        int mediaW = 0, mediaH = 0;
        if (cf != null && cf.getVideoFormat() != null)
        {
          mediaW = cf.getVideoFormat().getWidth();
          mediaH = cf.getVideoFormat().getHeight();
        }
        // ContainerFormat.bitrate is stored in bits/sec (see toString which
        // divides by 1000 for its "kbps" display). Convert to Kbps here for
        // the decision engine. Without this, a 2 Mbps source was reported as
        // "2129000 kbps" and always lost the bandwidth check, forcing transcode
        // on every otherwise-direct-playable file.
        int sourceBitrateKbps = (cf != null && cf.getBitrate() > 0) ? (cf.getBitrate() / 1000) : 0;
        // uiBandwidthEstimate is bits/sec; engine expects Kbps. The 50 Mbps
        // sentinel value (line ~931) means "not a low-bandwidth extender";
        // treat that as unmetered (0 = skip the BW check).
        int availableBwKbps = 0;
        if (uiBandwidthEstimate > 0 && uiBandwidthEstimate < 49000000L)
          availableBwKbps = (int) (uiBandwidthEstimate / 1000L);
        boolean isHDx00 = effectiveProfile.getProfileId().equals("hd_legacy_strict");
        // Schema v2: pass the client's capability constraints + the source's
        // interlaced flag + the transport mode so the engine can apply
        // per-client gates (e.g. ExoPlayer + MPEG-2 + interlaced -> reject
        // DIRECT_PLAY because the decoder can't deinterlace). The constraints
        // object is null for legacy clients; the engine falls back to legacy
        // codec/container set policy in that case.
        sage.client.ClientConstraints constraints =
            (mcsr != null) ? mcsr.getClientConstraints() : null;
        // Per-player constraints for the player-switch path: if the default
        // player can't direct-play but the alternate player can, the engine
        // will recommend a switch via PlaybackDecision.preferredPlayer.
        sage.client.ClientConstraints exoConstraints =
            (mcsr != null) ? mcsr.getClientConstraintsExo() : null;
        sage.client.ClientConstraints ijkConstraints =
            (mcsr != null) ? mcsr.getClientConstraintsIjk() : null;
        String defaultPlayerTag = (mcsr != null) ? mcsr.getClientDefaultPlayer() : "";
        boolean srcInterlaced = (cf != null && cf.getVideoFormat() != null
            && cf.getVideoFormat().isInterlaced());
        // clientDoesPull is computed below; here we use its inverse via the
        // existing renderer state. For the decision the relevant question is
        // "will we be in push mode?" — for miniclients that's the default
        // unless the explicit pull path below is taken. The container row's
        // push/pull check is only consulted when the chosen transport differs
        // from what the row allows; using push as the default matches the
        // legacy push-first behavior of the miniclient pipeline.
        boolean isPushTransport = !clientDoesPull;
        // Resolve which set is "primary" (default player) and which is "alt".
        sage.client.ClientConstraints primaryC;
        sage.client.ClientConstraints altC;
        String altPlayerTag;
        if ("ijkplayer".equals(defaultPlayerTag))
        {
          primaryC = ijkConstraints; altC = exoConstraints; altPlayerTag = "exoplayer";
        }
        else
        {
          primaryC = exoConstraints; altC = ijkConstraints; altPlayerTag = "ijkplayer";
        }
        // Legacy client-report intersection: compute the client's ACTUAL
        // reported support for the source container/video/audio from its coarse
        // capability lists (upstream google/SageTV honor model). The engine
        // uses this so the static profile can only RESTRICT, never GRANT, a
        // capability the client did not report. The container check follows the
        // chosen transport (push vs pull); TS is also acceptable for push when
        // the internal TS->PS remuxer is enabled and the client supports PS push.
        boolean crContainer;
        if (isPushTransport)
        {
          crContainer = mcsr.isSupportedPushContainerFormat(mediaContainer)
              || (Sage.getBoolean("enable_internal_push_remuxer", true)
                  && sage.media.format.MediaFormat.MPEG2_TS.equals(mediaContainer)
                  && mcsr.isSupportedPushContainerFormat(sage.media.format.MediaFormat.MPEG2_PS));
        }
        else
        {
          crContainer = mcsr.isSupportedPullContainerFormat(mediaContainer);
        }
        boolean crVideo = (mediaVideo == null || mediaVideo.length() == 0)
            || mcsr.isSupportedVideoCodec(mediaVideo);
        boolean crAudio = (mediaAudio == null || mediaAudio.length() == 0)
            || mcsr.isSupportedAudioCodec(mediaAudio);
        sage.client.PlaybackDecisionEngine.ClientReportedCaps clientCaps =
            new sage.client.PlaybackDecisionEngine.ClientReportedCaps(crContainer, crVideo, crAudio);

        // --- Playback Surface capability model (Protocol v2.1) — Phase 2 ---
        // When the client advertised PLAYBACK_SURFACES during the capability
        // handshake AND the gate is on, run the surface-aware evaluator FIRST.
        // The winning surface's decision becomes profileDecision and the
        // legacy V1/V2 evaluateWithPlayerSwitch() below is skipped. If no
        // surfaces were advertised OR the gate is off OR no surface produced
        // a servable decision, we fall through to the legacy path unchanged.
        // See ROADMAP.md "Playback Surface capability model (Protocol 2.1)".
        String chosenSurfaceId = null;
        String chosenSurfaceDelivery = null;
        String chosenSurfaceXcodeMode = null;
        int chosenSurfaceAudioStreamIndex = -1;
        int chosenSurfaceAudioChannels = 0;
        sage.client.PlaybackSurfaceSet surfaces = (mcsr != null)
            ? mcsr.getPlaybackSurfaces() : sage.client.PlaybackSurfaceSet.empty();
        if (!surfaces.isEmpty() && Sage.getBoolean("miniplayer/use_playback_surfaces", true))
        {
          // 2.1.0002: pass ContainerFormat so the evaluator can inspect
          // multiple audio streams, filter by server language, and prefer
          // native-decode over transcode (see selectBestAudioStream).
          // 2.1.0007: thread the client-reported preferred audio language
          // (CLIENT_AUDIO_LANGUAGE) so multi-audio sources match the client's
          // language first, then the server locale (null-safe; empty falls
          // back to server locale as before).
          String clientAudioLang = (mcsr != null) ? mcsr.getCurrentClientAudioLanguage() : null;
          java.util.List<sage.client.PlaybackDecisionEngine.SurfaceDecision> ranked =
              sage.client.PlaybackDecisionEngine.evaluateSurfaces(surfaces,
                  mediaContainer, mediaVideo, mediaAudio,
                  mediaW, mediaH, sourceBitrateKbps, availableBwKbps, srcInterlaced,
                  cf, clientAudioLang);
          if (!ranked.isEmpty())
          {
            sage.client.PlaybackDecisionEngine.SurfaceDecision winner = ranked.get(0);
            profileDecision = winner.decision;
            chosenSurfaceId = winner.surface.getId();
            chosenSurfaceDelivery = winner.chosenDeliveryMode;
            chosenSurfaceXcodeMode = winner.chosenXcodeMode;
            // 2.1.0003: extract the chosen audio stream's orderIndex so the
            // transcoder's -map picks the right track (language + quality aware).
            if (winner.audioStreamChoice != null && winner.audioStreamChoice.audioFormat != null)
            {
              chosenSurfaceAudioStreamIndex = winner.audioStreamChoice.audioFormat.getOrderIndex();
              chosenSurfaceAudioChannels = winner.audioStreamChoice.audioFormat.getChannels();
            }
            if (Sage.DBG) System.out.println("MiniPlayer surface decision (v2.1): winner=" + winner
                + " audioStreamIdx=" + chosenSurfaceAudioStreamIndex
                + " runnersUp=" + (ranked.size() - 1)
                + " (surfaces=" + surfaces.size() + " advertised)");
          }
          else if (Sage.DBG)
          {
            System.out.println("MiniPlayer surface decision (v2.1): no servable surface "
                + "(client advertised " + surfaces.size() + " but none met delivery filter); "
                + "falling back to legacy V1/V2 path");
          }
        }

        if (profileDecision == null)
        {
          if (Sage.DBG) System.out.println("MiniPlayer: calling evaluateWithPlayerSwitch ngSession=" + ngSession 
              + " clientCaps=[c=" + crContainer + " v=" + crVideo + " a=" + crAudio + "]"
              + " mediaAudio=" + mediaAudio);
          profileDecision = sage.client.PlaybackDecisionEngine.evaluateWithPlayerSwitch(
              effectiveProfile, mediaContainer, mediaVideo, mediaAudio,
              mediaW, mediaH, isHDx00, sourceBitrateKbps, availableBwKbps,
              defaultPlayerTag, altPlayerTag,
              primaryC, altC, srcInterlaced, isPushTransport, clientCaps, ngSession);
          if (Sage.DBG) System.out.println("MiniPlayer profile decision: " + profileDecision
              + " clientReports[container=" + crContainer + " video=" + crVideo + " audio=" + crAudio + "]");
        }

        // --- Session stickiness contract (per OPENURL) ---
        // The stream plan and target player are selected HERE and HERE ONLY.
        // Trickplay (pause/ff/rew/seek/jump) intentionally never reaches this
        // block, so the player selection stays frozen for the active stream.
        //
        // We emit CAP_EFFECTIVE_PLAYER exactly once at OPENURL with the
        // chosen player tag — whether that's the client's default or the
        // alternate selected by the engine. The client treats it as an
        // advisory selection for THIS stream only.
        String chosenPlayer = (profileDecision != null && profileDecision.preferredPlayer != null)
            ? profileDecision.preferredPlayer
            : defaultPlayerTag;
        if (mcsr != null && chosenPlayer != null && chosenPlayer.length() > 0)
        {
          if (Sage.DBG)
          {
            String switchTag = (profileDecision != null && profileDecision.preferredPlayer != null
                && !profileDecision.preferredPlayer.equalsIgnoreCase(defaultPlayerTag))
                ? " (SWITCHED from default=" + defaultPlayerTag + ")"
                : " (kept default)";
            System.out.println("MiniPlayer OPENURL stream-plan locked: player=" + chosenPlayer
                + switchTag
                + " decision=" + (profileDecision != null ? profileDecision.decision : "n/a")
                + " reason=" + (profileDecision != null ? profileDecision.reason : "n/a"));
          }
          try
          {
            mcsr.sendSetProperty("CAP_EFFECTIVE_PLAYER", chosenPlayer);
          }
          catch (java.io.IOException ioe)
          {
            if (Sage.DBG) System.out.println("MiniPlayer failed to send CAP_EFFECTIVE_PLAYER=" + chosenPlayer + ": " + ioe);
          }
        }
        // --- Playback Surface capability model (Protocol v2.1) — Phase 2 ---
        // If a surface won the ranking above, emit CAP_EFFECTIVE_SURFACE
        // exactly once per OPENURL. Same session-stickiness contract as
        // CAP_EFFECTIVE_PLAYER: trickplay/seek never re-run the decision,
        // the chosen surface stays locked for the active stream. Only
        // emitted when the surface-aware path picked a winner; legacy
        // sessions never see this property.
        if (mcsr != null && chosenSurfaceId != null && chosenSurfaceId.length() > 0)
        {
          if (Sage.DBG) System.out.println("MiniPlayer OPENURL surface-plan locked: surface="
              + chosenSurfaceId + " delivery=" + chosenSurfaceDelivery
              + " decision=" + (profileDecision != null ? profileDecision.decision : "n/a"));
          try
          {
            mcsr.sendSetProperty("CAP_EFFECTIVE_SURFACE", chosenSurfaceId);
          }
          catch (java.io.IOException ioe)
          {
            if (Sage.DBG) System.out.println("MiniPlayer failed to send CAP_EFFECTIVE_SURFACE="
                + chosenSurfaceId + ": " + ioe);
          }
          // Protocol 2.1: publish the effective delivery mode (and, for
          // pull-xcode, the concrete server-native XCODE_SETUP mode) so the
          // bridge maps CAP_EFFECTIVE_DELIVERY=pull-xcode:<mode> 1:1 to its
          // /msproxy?mode=<mode> and the PWA stops sniffing on NG. For plain
          // pull/push/hls just the mode name is sent (no xcode mode).
          if (chosenSurfaceDelivery != null && chosenSurfaceDelivery.length() > 0)
          {
            String effDelivery = (chosenSurfaceXcodeMode != null && chosenSurfaceXcodeMode.length() > 0)
                ? chosenSurfaceDelivery + ":" + chosenSurfaceXcodeMode
                : chosenSurfaceDelivery;
            // Protocol 2.1 option B: for the fMP4 modes that TRANSCODE audio
            // (browserhd, browserhd_copyv) carry the decision's best target
            // audio codec + source channel count as ";acodec=<v>;ac=<n>" so the
            // server honors "best codec the client supports at/below source"
            // instead of the static AAC-stereo floor. Copy/remux modes omit it.
            if (("browserhd".equals(chosenSurfaceXcodeMode) || "browserhd_copyv".equals(chosenSurfaceXcodeMode))
                && profileDecision != null && profileDecision.targetAudioCodec != null
                && profileDecision.targetAudioCodec.length() > 0)
            {
              String tac = profileDecision.targetAudioCodec.trim().toUpperCase(java.util.Locale.ROOT);
              String ffAcodec = null;
              if (tac.equals("EAC3") || tac.equals("E-AC-3") || tac.equals("EC-3")) ffAcodec = "eac3";
              else if (tac.equals("AC3") || tac.equals("AC-3")) ffAcodec = "ac3";
              else if (tac.equals("AAC") || tac.equals("HE-AAC")) ffAcodec = "aac";
              else if (tac.equals("MP2")) ffAcodec = "mp2";
              else if (tac.equals("OPUS")) ffAcodec = "libopus";
              else if (tac.equals("FLAC")) ffAcodec = "flac";
              // else (DTS/TRUEHD/unknown): no override -> the mode's default stands.
              if (ffAcodec != null)
              {
                effDelivery += ";acodec=" + ffAcodec;
                int ch = chosenSurfaceAudioChannels;
                if (ch <= 0 && currMF != null && currMF.getFileFormat() != null
                    && currMF.getFileFormat().getAudioFormat() != null)
                  ch = currMF.getFileFormat().getAudioFormat().getChannels();
                if (ch > 0) effDelivery += ";ac=" + ch;
              }
            }
            if (Sage.DBG) System.out.println("MiniPlayer OPENURL emit CAP_EFFECTIVE_DELIVERY=" + effDelivery);
            try
            {
              mcsr.sendSetProperty("CAP_EFFECTIVE_DELIVERY", effDelivery);
            }
            catch (java.io.IOException ioe)
            {
              if (Sage.DBG) System.out.println("MiniPlayer failed to send CAP_EFFECTIVE_DELIVERY="
                  + effDelivery + ": " + ioe);
            }
          }
          // Phase 2.5 wiring: publish the surface's target codecs + delivery
          // mode to the MiniClient session state so the transcoder subsystem
          // (HTTPLSServer.setupTranscoder + FFMPEGTranscoder) can honor the
          // surface's HONEST target list instead of falling back to the
          // coarse V1 AUDIO_CODECS lookup that mis-negotiated AC3 for
          // Chromium MSE clients (Tizen PWA 2026-07 incident).
          mcsr.setCurrentSurfaceSelection(chosenSurfaceId,
              profileDecision != null ? profileDecision.targetAudioCodec : "",
              profileDecision != null ? profileDecision.targetVideoCodec : "",
              chosenSurfaceDelivery,
              chosenSurfaceAudioStreamIndex);
          // Phase 2.5 transport override: honor the surface's declared
          // delivery mode as an authoritative signal for THIS stream. This
          // is orthogonal to the legacy transport-force block below (which
          // only fires for non-NG legacy clients). Surface-aware clients
          // are trusted -- their surface list is the honest report of what
          // pipeline they want to receive bytes through.
          //
          // 2.1.0008 single-port / remote-client transport safety:
          // stv:// PULL uses a SEPARATE media-server port, so it only works
          // on a LAN connection. A remote client reachable on just the
          // control/HTTP port (default 31099, NAT-forwarded) cannot fetch a
          // pull URL. HLS and native PUSH both ride the single 31099 port and
          // survive NAT. Therefore:
          //   - An httpls session is left ENTIRELY alone -- HLS is its
          //     delivery and clientDoesPull was already configured upstream
          //     (line ~830) for the HLS path.
          //   - pull is forced ONLY on a local (LAN) connection.
          //   - push is always safe to force (rides 31099).
          boolean localConn = (mcsr != null && mcsr.isLocalConnection());
          if (httpls)
          {
            if (Sage.DBG) System.out.println("MiniPlayer: httpls session — surface '"
                + chosenSurfaceId + "' delivery=" + chosenSurfaceDelivery
                + " not applied to transport; HLS over the single HTTP port is the delivery");
          }
          else if ("pull".equals(chosenSurfaceDelivery) && !clientDoesPull && localConn)
          {
            if (Sage.DBG) System.out.println("MiniPlayer: surface '" + chosenSurfaceId
                + "' declared pull delivery (local connection) — forcing clientDoesPull=true");
            clientDoesPull = true;
          }
          else if ("pull-xcode".equals(chosenSurfaceDelivery) && !clientDoesPull)
          {
            // pull-xcode rides the single control/HTTP port via the bridge's
            // /msproxy?mode=<xcodeMode>, so it is safe on both LAN and remote
            // (unlike raw stv:// pull which needs the separate media-server
            // port). Force pull transport; the client requested XCODE mode is
            // carried in CAP_EFFECTIVE_DELIVERY above.
            if (Sage.DBG) System.out.println("MiniPlayer: surface '" + chosenSurfaceId
                + "' declared pull-xcode delivery (mode=" + chosenSurfaceXcodeMode
                + ") — forcing clientDoesPull=true");
            clientDoesPull = true;
          }
          else if ("pull".equals(chosenSurfaceDelivery) && !clientDoesPull && !localConn)
          {
            if (Sage.DBG) System.out.println("MiniPlayer: surface '" + chosenSurfaceId
                + "' declared pull delivery but connection is REMOTE — stv:// pull needs a "
                + "separate media-server port unreachable over a single NAT port; keeping push/HLS");
          }
          else if ("push".equals(chosenSurfaceDelivery) && clientDoesPull)
          {
            if (Sage.DBG) System.out.println("MiniPlayer: surface '" + chosenSurfaceId
                + "' declared push delivery — forcing clientDoesPull=false");
            clientDoesPull = false;
          }
          // "hls" delivery is honored via the existing HTTPLS routing path
          // (iPhoneMode / iosstream detection). No override needed here.
        }
      }
      // --- End profile decision ---

      // Transport enforcement: when a managed profile's decision engine says
      // the source needs REMUX or TRANSCODE (i.e. the container/codec is NOT
      // directly playable by this client), a LEGACY client must NOT pull the
      // raw file — it must receive the push-mode remux/transcode. The legacy
      // clientDoesPull flag is seeded from the client's PULL_AV_CONTAINERS
      // property, which for legacy placeshifters is a STATIC, optimistic list
      // (e.g. MPlayer advertises Quicktime/MP4) that the client's demuxer
      // cannot actually handle in pull mode. Clearing clientDoesPull here
      // forces pushMode below so the REMUX/TRANSCODE verdict (mpeg2psremux
      // etc.) is honored.
      //
      // IMPORTANT — NG clients are EXCLUDED from this override. NG clients
      // (PWA browser, Android MiniClient) do accurate capability negotiation
      // (browser canPlayType probing, ExoPlayer/IJK decoder matrix), so their
      // reported PULL_AV_CONTAINERS / codec sets are RELIABLE and must win over
      // a static server-side profile. Example: Safari genuinely decodes HEVC
      // even though the conservative pwa_safe profile lists only H.264 — forcing
      // a transcode there would ignore the client's real capability. So this
      // correction applies ONLY to legacy (non-NG) clients whose self-report is
      // a fixed list, not to NG clients whose self-report reflects real decoders.
      if (profileDecision != null && clientDoesPull
          && mcsr != null && !ngSession
          && profileDecision.decision != sage.client.PlaybackDecisionEngine.Decision.DIRECT_PLAY)
      {
        if (Sage.DBG) System.out.println("MiniPlayer: forcing push mode (legacy client) — profile decision "
            + profileDecision.decision + " requires server-side remux/transcode, "
            + "clearing clientDoesPull (was pull-capable per client PULL_AV_CONTAINERS)");
        clientDoesPull = false;
      }

      if (clientDoesPull && (httpls || pureLocal || !clientDoesMPEG2Push || !clientCanDoMpeg4 || uiBandwidthEstimate >= Sage.getInt("miniplayer/min_bandwidth_for_no_transcode", 2000000)))
      {
        if (Sage.DBG) System.out.println("MiniPlayer is using Pull mode playback");
        // Pull mode is being used
        pushMode = false;
      }
      else
      {
        /*
         * There's a few things we can do here since we're in push mode.
         * 1. If we're not a media extender; we know we're transcoding because that's the only way we push to desktop placeshifters
         * 2. For extenders, if the formats are compatible then we just push directly
         * 3. For extenders, if it doesn't support mpeg4 or if it's not lowBandwidth; then we transcode into DVD/SVCD, but if the video and audio
         * codecs are supported we just remux it instead
         * 4. For extenders that can do mpeg4 and its low bandwidth mode; then we transcode into the same format as the placeshifter uses
         */
        if (Sage.DBG) System.out.println("MiniPlayer is using Push mode playback");
        pushMode = true; // shouldPush(majorTypeHint, minorTypeHint);
        useNioTransfers = Sage.getBoolean("use_nio_transfers", false);
        // Check for transcoding
        // NOTE: Always transcode when we're doing push mode with the placeshifter. Non-transcoded push mode
        // doesn't work all that well and people usually connect that way when they want to experiment with transcoding.

        // Profile-aware fast path: if a managed client (e.g. modern Android) supports the
        // source container + video codec but NOT the audio codec, run an audio-only
        // transcode (video passthrough). This avoids the legacy MPEG2-DVD path which a
        // modern Android tablet cannot decode (black screen w/ audio playing fine).
        boolean audioOnlyEarlyPath = false;
        boolean enableAO = Sage.getBoolean("miniplayer/enable_audioonly_transcode", true);
        if (Sage.DBG) System.out.println("MiniPlayer.audioOnlyEval: enable=" + enableAO
            + " mcsr=" + (mcsr != null) + " currMF=" + (currMF != null)
            + " ff=" + (currMF != null ? currMF.getFileFormat() : null));
        if (enableAO && mcsr != null && currMF != null && currMF.getFileFormat() != null)
        {
          sage.media.format.ContainerFormat cfEarly = currMF.getFileFormat();
          sage.media.format.VideoFormat vfEarly = cfEarly.getVideoFormat();
          sage.media.format.AudioFormat afEarly = cfEarly.getAudioFormat();
          boolean conOK = (cfEarly.getFormatName() != null) && mcsr.isSupportedPushContainerFormat(cfEarly.getFormatName());
          boolean vidOK = (vfEarly != null) && mcsr.isSupportedVideoCodec(vfEarly.getFormatName());
          boolean audOK = (afEarly != null) && mcsr.isSupportedAudioCodec(afEarly.getFormatName());
          if (Sage.DBG) System.out.println("MiniPlayer.audioOnlyEval: container=" + cfEarly.getFormatName() + "(push=" + conOK + ")"
              + " video=" + (vfEarly != null ? vfEarly.getFormatName() : "null") + "(ok=" + vidOK + ")"
              + " audio=" + (afEarly != null ? afEarly.getFormatName() : "null") + "(ok=" + audOK + ")");

          // NOTE: We previously had a "COPY-directive override" that honored
          // FIXED_PUSH_REMUX_FORMAT=container=mpegts;videocodec=COPY;audiocodec=COPY
          // as if it were proof the client could decode anything. That assumption is
          // wrong — the Android miniclient sends that string as a hardcoded default,
          // not as a real capability claim. Trusting it caused Shield (no AC-4 HW) to
          // get raw AC-4 pushed and play silent video. The right behavior when audio
          // is unsupported is the audio-only transcode path below.

          // Drop strict container-push check: if video codec is supported and audio
          // codec is NOT, audio-only transcode is the right call regardless of whether
          // the source container appears in the client's push-container list. Output is
          // MPEG2-TS, which every modern push-mode client (including Android miniclient)
          // can demux.
          if (vfEarly != null && afEarly != null && vidOK && !audOK)
          {
            // Take the audio-only transcode path: video copy + audio re-encode.
            // Works for both Shield (HEVC video copies cleanly) and Galaxy Tab
            // (re-test pending — if HEVC video copy fails on Tab, narrow with a
            // client-aware override later, NOT a blanket HEVC->full-transcode).
            if (Sage.DBG) System.out.println("MiniPlayer: profile-aware audio-only transcode "
                + "(container=" + cfEarly.getFormatName()
                + " video=" + vfEarly.getFormatName()
                + " audio=" + afEarly.getFormatName() + " not supported)");
            transcoded = true;
            useOriginalAudioTrack = false;
            dynamicRateAdjust = false;
            prefTranscodeMode = "audioonly";
            audioOnlyEarlyPath = true;
          }
        }

        if (audioOnlyEarlyPath)
        {
          // skip both the mediaExtender/lowBandwidth and clientCanDoMPEGHD analysis.
        }
        else if (!mediaExtender || lowBandwidth/* && ((fixedPushFormat != null && fixedPushFormat.length() > 0) || uiBandwidthEstimate < 2000000)*/)
        {
          if (Sage.DBG) System.out.println("MiniPlayer is using the MPEG4 transcoder");
          transcoded = true;
          useOriginalAudioTrack = false;
          // HEVC source: legacy push clients can't actually decode it (their caps lie),
          // and their fixedPushFormat uses fps=SOURCE/resolution=SOURCE which becomes
          // "-r 0 -s 0x0" because the HEVC source format has no parsed dims. Force
          // the dynamic MPEG4/MP2 path which has sane defaults.
          sage.media.format.ContainerFormat _cfHe = (currMF != null) ? currMF.getFileFormat() : null;
          sage.media.format.VideoFormat _vfHe = (_cfHe != null) ? _cfHe.getVideoFormat() : null;
          boolean hevcSrcLegacy = (_vfHe != null
              && (sage.media.format.MediaFormat.HEVC.equalsIgnoreCase(_vfHe.getFormatName())
                  || "H.265".equalsIgnoreCase(_vfHe.getFormatName())));
          if (hevcSrcLegacy)
          {
            dynamicRateAdjust = false;
            prefTranscodeMode = h264PushOK ? "dynamich264"
                : ((mcsr != null && mcsr.isSupportedPushContainerFormat(sage.media.format.MediaFormat.MPEG2_PS))
                    ? "dynamic" : "dynamicts");
            if (Sage.DBG) System.out.println("MiniPlayer: HEVC source — forcing prefTranscodeMode=" + prefTranscodeMode
                + " (legacy fixedPushFormat would produce -r 0 -s 0x0)");
          }
          else
          {
            dynamicRateAdjust = (fixedPushFormat == null || fixedPushFormat.length() == 0);
            if (dynamicRateAdjust)
              prefTranscodeMode = majorTypeHint == MediaFile.MEDIATYPE_AUDIO ?
                  ((uiBandwidthEstimate > 256000) ? "music128" : "music") :
                    (h264PushOK ? "dynamich264"
                     : ((mcsr != null && mcsr.isSupportedPushContainerFormat(sage.media.format.MediaFormat.MPEG2_PS)) ? "dynamic" : "dynamicts"));
                  else
                    prefTranscodeMode = fixedPushFormat;
            // Even when a FIXED_PUSH_MEDIA_FORMAT is locked in by the
            // client (legacy clients configured per docs/ClientSettings.md),
            // we still want the bitrate adjuster to clamp the transcode
            // DOWN when the measured link can't carry the profile's target
            // bitrate. The UP branch in the adjuster gates on
            // currentVideoBitrate < fftc.getDynamicMaxVideoKbps() (LAN-aware
            // ceiling; see FFMPEGTranscoder), so a fixed profile at or above
            // that ceiling cannot be ratcheted upward — we only ever clamp
            // downward toward the link's capacity.
            //
            // Toggle: transcoder/adapt_fixed_to_bw (default true).
            if (!dynamicRateAdjust && Sage.getBoolean("transcoder/adapt_fixed_to_bw", true))
            {
              dynamicRateAdjust = true;
              if (Sage.DBG) System.out.println("MiniPlayer: enabling BW-adaptive bitrate clamp for FIXED_PUSH_MEDIA_FORMAT="
                  + fixedPushFormat + " (transcoder/adapt_fixed_to_bw=true)");
            }
          }
        }
        else
        {
          sage.media.format.ContainerFormat cf = currMF.getFileFormat();
          if (cf != null && mcsr != null)
          {
            boolean containerOK = mcsr.isSupportedPushContainerFormat(cf.getFormatName()) ||
                (Sage.getBoolean("enable_internal_push_remuxer", true) &&
                    sage.media.format.MediaFormat.MPEG2_TS.equals(cf.getFormatName()) &&
                    mcsr.isSupportedPushContainerFormat(sage.media.format.MediaFormat.MPEG2_PS));
            sage.media.format.VideoFormat vidFormat = cf.getVideoFormat();
            boolean videoOK = false;
            boolean hasVideo = false;
            if (vidFormat != null)
            {
              hasVideo = true;
              if (sage.media.format.MediaFormat.MPEG2_VIDEO.equals(vidFormat.getFormatName()))
              {
                // Video format might be OK if it's an appropriate resolution
                if (!mcsr.isSupportedVideoCodec(vidFormat.getFormatName()))
                  videoOK = false;
                else if (clientCanDoMPEGHD)
                  videoOK = true;
                else if (vidFormat.getWidth() <= 720)
                {
                  if (MMC.getInstance().isNTSCVideoFormat())
                  {
                    if (vidFormat.getHeight() <= 480 && vidFormat.getFps() <= 30.1) // 30fps or less, and within NTSC resolution
                    {
                      // Format is OK for video!
                      videoOK = true;
                    }
                  }
                  else
                  {
                    if (vidFormat.getHeight() <= 576 && vidFormat.getFps() <= 25.1) // 25fps or less & within PAL resolution
                    {
                      // Format is OK for video!
                      videoOK = true;
                    }
                  }
                }
              }
              else if (mcsr.isSupportedVideoCodec(vidFormat.getFormatName()))
              {
                videoOK = true;
              }
            }
            sage.media.format.AudioFormat audFormat = cf.getAudioFormat();
            boolean audioOK = false;
            boolean hasAudio = false;
            boolean lowRateAudio = false;
            if (audFormat != null)
            {
              hasAudio = true;
              if (mcsr.isSupportedAudioCodec(audFormat.getFormatName()))
              {
                audioOK = true;
              }

              if (audFormat.getChannels() == 1 || audFormat.getSamplingRate() < 30000)
                lowRateAudio = true;
            }
            if (!Sage.getBoolean("miniplayer/allow_transcoding", true))
            {
              // do not allow transcoding w/ FFMPEG
              containerOK = videoOK = audioOK = true;
            }
            if (!clientCanDoMPEGHD)
            {
              if (!containerOK || (hasVideo && !videoOK) || (hasAudio && !audioOK))
              {
                transcoded = true;
                if (minorTypeHint == MediaFile.MEDIASUBTYPE_MP3)
                  useMP3StreamWrapper = true;
                else if (majorTypeHint == MediaFile.MEDIATYPE_AUDIO)
                  prefTranscodeMode = lowRateAudio ? "music128" : "music256";
                useOriginalAudioTrack = false;
              }
            }
            else
            {
              if (containerOK && hasVideo && videoOK && hasAudio && !audioOK
                  && Sage.getBoolean("miniplayer/enable_audioonly_transcode", true))
              {
                // Client supports the source video codec (e.g. HEVC) and the
                // container, but not the source audio codec (e.g. Dolby AC-4).
                // Pass video through, re-encode audio only.
                if (Sage.DBG) System.out.println("MiniPlayer: video OK, audio NOT OK — using audio-only transcode "
                    + "(video=" + (vidFormat != null ? vidFormat.getFormatName() : "?")
                    + ", audio=" + (audFormat != null ? audFormat.getFormatName() : "?") + ")");
                transcoded = true;
                prefTranscodeMode = "audioonly";
                useOriginalAudioTrack = false;
              }
              else if ((hasVideo && !videoOK) || (hasAudio && !audioOK)/* ||
								(!containerOK && !hasVideo && minorTypeHint == MediaFile.MEDIASUBTYPE_MP3)*/)
              {
                transcoded = true;
                if (minorTypeHint == MediaFile.MEDIASUBTYPE_MP3)
                  useMP3StreamWrapper = true;
                else if (majorTypeHint == MediaFile.MEDIATYPE_AUDIO)
                  prefTranscodeMode = lowRateAudio ? "music128" : "music256";
                useOriginalAudioTrack = false;
              }
              else if (!containerOK)
              {
                transcoded = true;
                prefTranscodeMode = "mpeg2psremux";
              }
            }
          }
        }

        // (Reverted: previous FINAL OVERRIDE forced HEVC->dynamic transcode for ALL
        // legacy push clients. This blanket-forced transcode also on NVIDIA Shield,
        // which has hardware HEVC and may actually be able to play push-mode HEVC.
        // Removed so Shield (legacy client) can be tested raw. Galaxy Tab still
        // fails — re-add a client-aware version (MAC/name match) once we know
        // which clients truly cannot decode HEVC.)
      }

      // --- Profile decision diagnostic + authoritative override (schema v2) ---
      // The profile clamps videoCodecs/audioCodecs/pushContainers/pullContainers in
      // MiniClientSageRenderer.initMini() and the legacy format-checking logic above
      // SHOULD operate on those clamped sets. In practice the legacy path can still
      // miss the rejection (e.g. MPEG2-Video special-case branch around line 1100,
      // codec-name normalization mismatches between profile clamp and
      // mcsr.isSupportedVideoCodec(), or the clientCanDoMPEGHD bypass). When that
      // happens the server would direct-play a codec the client never advertised
      // (observed: SD MPEG2-PS/MPEG2-Video/AC3 Dick Van Dyke recording sent raw
      // to a Shield/android_modern client). Treat the profile decision as
      // authoritative and honor the priority order: raw (DIRECT_PLAY) > remux
      // (container-only fix) > transcode (last resort).
      if (profileDecision != null)
      {
        if (Sage.DBG) System.out.println("MiniPlayer: Profile decision=" + profileDecision.decision +
            " reason=" + profileDecision.reason +
            " (enforced via clamped codec/container sets, existing logic result: transcoded=" + transcoded + ")");

        // ----- Case A: profile says DIRECT_PLAY but legacy decided to transcode.
        // The legacy decision was driven by xcode_qualities / push-mode defaults
        // (e.g. mode='DVD6Ch' or a container=matroska;videobitrate=4000000;...
        // template) — NOT by a real client capability gap. Forcing a remux into
        // Matroska when the client already decodes the source container/codecs
        // natively is harmful: it breaks ExoPlayer (e.g. MPEG2-Video inside MKV
        // on Galaxy Tab → ExoPlaybackException retry loop) and wastes CPU.
        // Clear the transcode flag so the source pushes raw end-to-end.
        if (profileDecision.decision == sage.client.PlaybackDecisionEngine.Decision.DIRECT_PLAY
            && transcoded
            && pushMode && mcsr != null
          && ngSession
            && majorTypeHint == MediaFile.MEDIATYPE_VIDEO)
        {
          if (Sage.DBG) System.out.println("MiniPlayer: profile-authoritative override forces DIRECT_PLAY"
              + " (legacy had transcoded=true mode=" + prefTranscodeMode + ") — clearing transcode,"
              + " pushing source as-is. reason=" + profileDecision.reason);
          transcoded = false;
          prefTranscodeMode = null;
          dynamicRateAdjust = false;
          useOriginalAudioTrack = true;
        }

        if (!transcoded
            && pushMode && mcsr != null
            && majorTypeHint == MediaFile.MEDIATYPE_VIDEO)
        {
          if (profileDecision.decision == sage.client.PlaybackDecisionEngine.Decision.REMUX)
          {
            // Container-only fix: codecs are fine, just rewrap. mpeg2psremux
            // does an in-process TS->PS rewrap (RemuxTranscodeEngine) without
            // re-encoding video or audio.
            transcoded = true;
            useOriginalAudioTrack = true;
            prefTranscodeMode = "mpeg2psremux";
            dynamicRateAdjust = false;
            if (Sage.DBG) System.out.println("MiniPlayer: profile-authoritative override forces REMUX (legacy missed it) mode="
                + prefTranscodeMode + " reason=" + profileDecision.reason);
          }
          else if (profileDecision.decision == sage.client.PlaybackDecisionEngine.Decision.TRANSCODE)
          {
            // Last-resort full transcode. When the legacy client supplied a
            // FIXED_PUSH_MEDIA_FORMAT (per docs/ClientSettings.md), honor it
            // as the transcode target — it already names a valid FFmpeg-side
            // output spec the client knows how to decode. Otherwise pick
            // MPEG2-PS dynamic when the client supports PS push, else
            // MPEG2-TS dynamic. NOTE: we no longer gate this override on
            // fixedPushFormat being empty — TRANSCODE means "client cannot
            // decode this source", and shipping it raw just kills MCSR.
            //
            // Preferred path: when PlaybackDecisionEngine says the only thing
            // wrong is the audio (targetVideoCodec equals source video codec,
            // i.e. "remux video, transcode audio") build a custom mode string
            // with videocodec=COPY + the target audio codec. FFMPEGTranscoder
            // parses this format string and emits `-vcodec copy -acodec <x>`.
            // This is the right thing for the WGN-NG HEVC+AC-4 case on a LAN
            // client that already decodes HEVC — full re-encode (mpeg4 at
            // 1080p59.94) is far too slow to fill the push pipe inside the
            // client's start-up window and the socket times out.
            //
            // Fall-backs:
            //   (a) target video codec != source -> respect client's
            //       fixedPushFormat (legacy templates) provided source isn't
            //       HEVC (the SOURCE-resolved template breaks on HEVC dims).
            //   (b) otherwise pick dynamic/dynamicts (full re-encode).
            sage.media.format.ContainerFormat _cfOv = (currMF != null) ? currMF.getFileFormat() : null;
            sage.media.format.VideoFormat _vfOv = (_cfOv != null) ? _cfOv.getVideoFormat() : null;
            String _srcVCodec = (_vfOv != null) ? _vfOv.getFormatName() : null;
            boolean hevcSrcOv = (_srcVCodec != null
                && (sage.media.format.MediaFormat.HEVC.equalsIgnoreCase(_srcVCodec)
                    || "H.265".equalsIgnoreCase(_srcVCodec)));
            boolean videoCopyOk = (_srcVCodec != null
                && profileDecision.targetVideoCodec != null
                && (_srcVCodec.equalsIgnoreCase(profileDecision.targetVideoCodec)
                    || "COPY".equalsIgnoreCase(profileDecision.targetVideoCodec)
                    || (hevcSrcOv && "HEVC".equalsIgnoreCase(profileDecision.targetVideoCodec))));
            String pathTaken;
            transcoded = true;
            useOriginalAudioTrack = false;
            if (videoCopyOk)
            {
              // ffmpeg-side container name: prefer MKV (matroska) — supports
              // HEVC video + EAC3 audio + arbitrary stream maps, and the
              // android client treats it as a generic push container.
              String _tgtAudio = (profileDecision.targetAudioCodec != null)
                  ? profileDecision.targetAudioCodec.toLowerCase() : "eac3";
              // Map Sage audio codec names to ffmpeg codec names.
              if ("eac3".equals(_tgtAudio) || "ec-3".equals(_tgtAudio) || "ec3".equals(_tgtAudio))
                _tgtAudio = "eac3";
              else if ("ac3".equals(_tgtAudio) || "a_ac3".equals(_tgtAudio))
                _tgtAudio = "ac3";
              else if (_tgtAudio.startsWith("aac"))
                _tgtAudio = "aac";
              else if ("mp2".equals(_tgtAudio) || "mpg1l2".equals(_tgtAudio))
                _tgtAudio = "mp2";
              else if ("mp3".equals(_tgtAudio) || "mpg1l3".equals(_tgtAudio))
                _tgtAudio = "mp3";
              // 640 kbps fits 5.1 EAC3 comfortably; FFMPEGTranscoder divides by 1000.
              int _abps = (profileDecision.targetAudioCodec != null
                  && profileDecision.targetAudioCodec.toLowerCase().startsWith("aac")) ? 192000 : 640000;
              prefTranscodeMode = "container=matroska;videocodec=COPY;audiocodec=" + _tgtAudio
                  + ";audiobitrate=" + _abps + ";audiochannels=2";
              // Video is passthrough → bitrate clamping does nothing useful and
              // dynamicRateAdjust on a copy track confuses the ladder logic.
              dynamicRateAdjust = false;
              pathTaken = "video-copy+audio-transcode";
            }
            else if (!hevcSrcOv && fixedPushFormat != null && fixedPushFormat.length() > 0)
            {
              prefTranscodeMode = fixedPushFormat;
              dynamicRateAdjust = Sage.getBoolean("transcoder/adapt_fixed_to_bw", true);
              pathTaken = "fixedPushFormat";
            }
            else
            {
              prefTranscodeMode = h264PushOK ? "dynamich264"
                  : (mcsr.isSupportedPushContainerFormat(sage.media.format.MediaFormat.MPEG2_PS) ? "dynamic" : "dynamicts");
              dynamicRateAdjust = true;
              pathTaken = h264PushOK ? "dynamic-h264-fallback" : "dynamic-fallback";
            }
            if (Sage.DBG) System.out.println("MiniPlayer: profile-authoritative override forces TRANSCODE (legacy missed it) path="
                + pathTaken + " mode=" + prefTranscodeMode + " srcVCodec=" + _srcVCodec
                + " hevcSrc=" + hevcSrcOv + " videoCopyOk=" + videoCopyOk
                + " targetV=" + profileDecision.targetVideoCodec + " targetA=" + profileDecision.targetAudioCodec
                + " dynamicRateAdjust=" + dynamicRateAdjust + " reason=" + profileDecision.reason);
          }
        }
      }
      // --- End profile decision diagnostic ---


      this.timeshifted = timeshifted;
      currMute = !mediaExtender;
      serverSideTranscoding = false;
      usingRemuxer = false;
      if (pushMode)
      {
        if (bufferSize > 0 && hostname == null)
        {
          // Circular files don't work correctly with the MPEG2 pushers because they don't understand that concept. This is fixed by
          // having them go through the MediaServer which DOES understand circular files.
          if (Sage.DBG) System.out.println("MiniPlayer is going through the MediaServer to handle the circular file.");
          hostname = "localhost";
        }
        if(transcoded)
        {
          // For MP3 files we use JF's transcode wrapper; and for video & non-MP3 audio we use the media server's transcoder
          if (useMP3StreamWrapper)
          {
            if (Sage.DBG) System.out.println("MiniPlayer is using the MP3 stream wrapper");
            tcSrc = new Mpeg2Transcoder(file, hostname);
            /*					if (Sage.DBG) System.out.println("MiniPlayer is using the transcoder");
						mpegSrc = new FastMpeg2Reader(file, hostname);
						mpegSrc.setActiveFile(timeshifted);
						mpegSrc.setStreamTranscodeMode("mp3");
						transcoded = false;
						serverSideTranscoding = true;
						this.timeshifted = timeshifted = true;*/
          }
          else
          {
            if (Sage.DBG) System.out.println("MiniPlayer is using the transcoder");
            mpegSrc = new FastMpeg2Reader(file, hostname);
            mpegSrc.setActiveFile(timeshifted);
            sage.media.format.ContainerFormat currFileFormat = currMF.getFileFormat();
            if (currFileFormat != null && "true".equals(currFileFormat.getMetadataProperty("VARIED_FORMAT")))
              currFileFormat = sage.media.format.FormatParser.getFileFormat(file);
            
            //Check to see if there was a fixed format defined for transcoding and that the file has video
            // BUT: do NOT clobber a profile-aware "audioonly" decision. Audio-only transcode keeps
            // the source video codec via -vcodec copy; the legacy fixedPushFormat parser would
            // re-encode video (defaulting to mpeg4) and uses fps=SOURCE/resolution=SOURCE which
            // explodes when the source format lacks fps/dimensions (e.g. HEVC ATSC 3.0 streams
            // probed with native parser only — no width/height/fps detected).
            // ALSO skip the override for HEVC sources: the legacy MiniClient v1.x cannot
            // actually decode HEVC (its caps advertise it but the push decoder is mpeg4-only)
            // AND the fixedPushFormat template fps=SOURCE/res=SOURCE produces invalid
            // -r 0 -s 0x0 ffmpeg args from a 0x0/0fps HEVC source format.
            sage.media.format.VideoFormat _vfFP = (currFileFormat != null) ? currFileFormat.getVideoFormat() : null;
            boolean _hevcSrcFP = (_vfFP != null
                && (sage.media.format.MediaFormat.HEVC.equalsIgnoreCase(_vfFP.getFormatName())
                    || "H.265".equalsIgnoreCase(_vfFP.getFormatName())));
            // Profile-authoritative guard: if PlaybackDecisionEngine concluded
            // REMUX or TRANSCODE (because a source codec is unsupported by the
            // active profile / effective player), the profile-aware override
            // block above ALREADY set the right prefTranscodeMode (mpeg2psremux,
            // a video-copy+audio-transcode custom string, or dynamic[ts]).
            // The legacy fixedPushFormat / fixedPushRemuxFormat override below
            // is based on `videoCodecSupported && audioCodecSupported` flags
            // that come from the WARN-ONLY unclamped codec sets — they can lie
            // when the profile rejects a codec the client still advertises in
            // its union. Letting the legacy override run in that case
            // re-emits `-vcodec copy` / `-acodec copy` for a codec the player
            // actually cannot decode, producing silent or black playback
            // (observed: MPEG2-Video copy pushed to android_modern Shield/Fold,
            // openURL bf=vid=MPEG2-Video contradicting the TRANSCODE decision).
            // Skip the legacy override whenever the profile is authoritative.
            boolean _profileAuthOverride = (profileDecision != null
                && (profileDecision.decision == sage.client.PlaybackDecisionEngine.Decision.REMUX
                    || profileDecision.decision == sage.client.PlaybackDecisionEngine.Decision.TRANSCODE));
            // Defense in depth: a custom mode string ("container=...;videocodec=...;...")
            // is always produced by the profile-aware path and must not be replaced
            // by a legacy template either.
            boolean _customModeAlready = (prefTranscodeMode != null
                && prefTranscodeMode.indexOf('=') >= 0
                && prefTranscodeMode.indexOf(';') >= 0);
            if(fixedPushFormat != null && fixedPushFormat.length() > 0
                    && currFileFormat != null && currFileFormat.getVideoFormats().length > 0
                    && !"audioonly".equalsIgnoreCase(prefTranscodeMode)
                    && !_hevcSrcFP
                    && !_profileAuthOverride
                    && !_customModeAlready)
            {
                if(fixedPushRemuxFormat != null && fixedPushRemuxFormat.length() > 0 
                        && videoCodecSupported && audioCodecSupported && !containerSupported)
                {
                  if (Sage.DBG) System.out.println("Overriding transcode mode because a fixed remux format was set by client and only the container is not supported");
                  prefTranscodeMode = fixedPushRemuxFormat;
                }
                else
                {
                  if (Sage.DBG) System.out.println("Overriding transcode mode because a fixed format was set by client");
                  prefTranscodeMode = fixedPushFormat;
                }
                
            }
            else if (Sage.DBG && _profileAuthOverride
                     && fixedPushFormat != null && fixedPushFormat.length() > 0)
            {
              System.out.println("MiniPlayer: keeping profile-authoritative transcode mode=" + prefTranscodeMode
                  + " decision=" + profileDecision.decision
                  + " (skipping legacy fixedPushFormat/fixedPushRemuxFormat override) reason=" + profileDecision.reason);
            }
            else if (Sage.DBG && _customModeAlready
                     && fixedPushFormat != null && fixedPushFormat.length() > 0)
            {
              System.out.println("MiniPlayer: keeping custom transcode mode=" + prefTranscodeMode
                  + " (skipping legacy fixedPushFormat override)");
            }
            else if (Sage.DBG && "audioonly".equalsIgnoreCase(prefTranscodeMode)
                     && fixedPushFormat != null && fixedPushFormat.length() > 0)
            {
              System.out.println("MiniPlayer: keeping audio-only transcode (skipping client fixedPushFormat override)");
            }
            mpegSrc.setStreamTranscodeMode(prefTranscodeMode, currFileFormat);
            // If the source has Dolby AC-4 audio (ATSC 3.0), prefer E-AC-3 for
            // any client that advertises EAC3 (higher quality / 5.1 preserved).
            // Otherwise fall back to AC-3 (universal among legacy SageTV clients).
            if (currFileFormat != null
                && sage.media.format.MediaFormat.AC4.equals(currFileFormat.getPrimaryAudioFormat()))
            {
              // Audio fallback ladder (best -> worst preserving surround where possible):
              //   1. EAC3  — 5.1, 640k, HDMI passthrough capable, ExoPlayer >= 1.x
              //   2. AC3   — 5.1, 384k, HDMI passthrough capable, universal
              //   3. AAC   — 2.0/5.1, 256k, decoder always present, no passthrough
              //   4. MP2   — stereo only, 192k, last-resort universal floor
              String pick;
              if (mcsr != null && mcsr.isSupportedAudioCodec(sage.media.format.MediaFormat.EAC3))
                pick = "eac3";
              else if (mcsr != null && mcsr.isSupportedAudioCodec(sage.media.format.MediaFormat.AC3))
                pick = "ac3";
              else if (mcsr != null && mcsr.isSupportedAudioCodec(sage.media.format.MediaFormat.AAC))
                pick = "aac";
              else
                pick = "mp2";
              if (Sage.DBG) System.out.println("MiniPlayer: AC-4 source detected — selecting "
                  + pick + " (fallback ladder: eac3 -> ac3 -> aac -> mp2)");
              mpegSrc.setAc4SourceAudioCodec(pick);
            }
            transcoded = false;
            serverSideTranscoding = true;
            this.timeshifted = timeshifted = true;
          }
        }
        else if (hdhrPrimeSpecial || (hostname != null && (hostname.equals(Sage.get("alternate_media_server", "")) ||
            Sage.getBoolean("use_alternate_streaming_ports", false))))
        {
          if (hostname == null)
            hostname = "127.0.0.1";
          if (Sage.DBG) System.out.println("MiniPlayer is using the RemotePusher connected to: " + hostname);
          rpSrc = new RemotePusherClient(this);
          try
          {
            rpSrc.connect(hostname);
            sage.media.format.ContainerFormat currFileFormat = currMF.getFileFormat();
            if (currFileFormat != null && "true".equals(currFileFormat.getMetadataProperty("VARIED_FORMAT")))
              currFileFormat = sage.media.format.FormatParser.getFileFormat(file);
            rpSrc.openFile(file.getAbsolutePath(), currFileFormat == null ? "" : currFileFormat.getFullPropertyString(false),
                timeshifted, false);
          }
          catch (java.io.IOException e)
          {
            System.out.println("Error initing RemotePusher stream:" + e);
            e.printStackTrace();
            rpSrc.close();
            rpSrc = null;
            throw new PlaybackException(PlaybackException.FILESYSTEM, 0);
          }
        }
        else
        {
          if (Sage.DBG) System.out.println("MiniPlayer is using the MPEG2 pusher");
          mpegSrc = new FastMpeg2Reader(file, hostname);
          mpegSrc.setActiveFile(timeshifted);
          sage.media.format.ContainerFormat currFileFormat = currMF.getFileFormat();
          if (currFileFormat != null && "true".equals(currFileFormat.getMetadataProperty("VARIED_FORMAT")))
            currFileFormat = sage.media.format.FormatParser.getFileFormat(file);
          mpegSrc.setStreamTranscodeMode(null, currFileFormat);
          if (currMF.isBluRay())
            mpegSrc.setTargetBDTitle(uiMgr.getVideoFrame().getBluRayTargetTitle());
          if (!hdMediaExtender && currFileFormat != null && sage.media.format.MediaFormat.MPEG2_TS.equals(currFileFormat.getFormatName())
              && !(mcsr != null && mcsr.isSupportedPushContainerFormat(sage.media.format.MediaFormat.MPEG2_TS)))
          {
            // Client doesn't support TS push — remux TS→PS for legacy clients
            usingRemuxer = true;
            transcoded = false;
            serverSideTranscoding = true;
            this.timeshifted = timeshifted = true;
            // NOTE: WE DO WANT TO USE IT; WE JUST DON'T KNOW WHERE IT'LL BE!!!!
            // NOTE: WE DO WANT TO USE IT; WE JUST DON'T KNOW WHERE IT'LL BE!!!!
            useOriginalAudioTrack = true;
          }
          else if (!hdMediaExtender && currFileFormat != null && sage.media.format.MediaFormat.MPEG2_TS.equals(currFileFormat.getFormatName()))
          {
            // Client supports TS push — skip remuxer entirely, push raw TS bytes
            if (Sage.DBG) System.out.println("MiniPlayer skipping remuxer — pushing raw TS (client supports MPEG2-TS push)");
          }
          else if (currFileFormat != null && Sage.getBoolean("miniplayer/align_iframes_on_seek", true))
            mpegSrc.setIFrameAlign(true);
        }
        if (rpSrc == null)
        {
          try
          {
            if(transcoded)
            {
              tcSrc.init(true, !timeshifted);
            }
            else
            {
              mpegSrc.init(true, !timeshifted, usingRemuxer);
              checkForByteBasedSeeking(file);
            }
          }
          catch (java.io.IOException e)
          {
            System.out.println("Error initing MPEG2 stream:" + e);
            e.printStackTrace();
            throw new PlaybackException(PlaybackException.FILESYSTEM, 0);
          }
        }
        if (mpegSrc != null)
          bdp = mpegSrc.getBluRaySource();
        if (!timeshifted && !transcoded && rpSrc == null)
          finalLength = mpegSrc.length();
        if (serverSideTranscoding && mpegSrc != null && mpegSrc.getTranscoder() != null && mpegSrc.getTranscoder() instanceof FFMPEGTranscoder)
        {
          FFMPEGTranscoder xcodeFtc = (FFMPEGTranscoder)mpegSrc.getTranscoder();
          xcodeFtc.setEstimatedBandwidth(uiBandwidthEstimate);
          // mcsr.isLocalConnection() is the same real subnet-mask IP comparison already used
          // above to clamp DOWN a marginal WAN estimate -- reuse it here to let the dynamic
          // mpeg4 ladder/ramp raise ITS bitrate/fps ceiling for a LAN client instead of holding
          // every classic placeshifter client to the same WAN-conservative cap. See
          // FFMPEGTranscoder.getDynamicMaxVideoKbps()/getDynamicMaxFps().
          xcodeFtc.setLocalClient(mcsr != null && mcsr.isLocalConnection());
          xcodeFtc.setThreadingEnabled(Sage.getBoolean("xcode/allow_multithreading_for_hdextender_placeshifting", false) || !hdMediaExtender || !lowBandwidth);
        }
      }
      //mpegSrc.setTimeshifted(timeshifted);
      //mpegSrc.setCircularSize(bufferSize);

      if (rpSrc != null)
      {
        // Tell the miniclient to redirect to our alternate server instead
        sage.media.format.ContainerFormat currFileFormat = currMF.getFileFormat();
        if (currFileFormat != null && "true".equals(currFileFormat.getMetadataProperty("VARIED_FORMAT")))
          currFileFormat = sage.media.format.FormatParser.getFileFormat(file);
        if (!openURL0("push://" + hostname + (Sage.getBoolean("use_alternate_streaming_ports", false) ?
            ":31098" : "") + "/session/" + rpSrc.getSessionID() + "?" +
            (currFileFormat == null ? "" : currFileFormat.getFullPropertyString(false, timeshifted ? "live=1;" : null))))
          throw new PlaybackException();
      }
      else if (pushMode)
      {
        // Get the full format string for specifying in push mode
        String formatString = "";
        if (currMF != null)
        {
          sage.media.format.ContainerFormat cf = (bdp != null) ? bdp.getFileFormat() : currMF.getFileFormat();
          if (cf != null && "true".equals(cf.getMetadataProperty("VARIED_FORMAT")))
            cf = sage.media.format.FormatParser.getFileFormat(file);
          if (usingRemuxer)
            cf = ((RemuxTranscodeEngine)mpegSrc.getTranscoder()).getTargetFormat();
          else if (serverSideTranscoding && "mpeg2psremux".equals(prefTranscodeMode))
          {
            // REMUX path: FFMPEGTranscoder runs `-f dvd -vcodec copy -acodec copy`
            // (see MediaServer.XCODE_QUALITIES_PROPERTY_ROOT + "mpeg2psremux").
            // Wire bytes = MPEG2-PS with the SOURCE video+audio codecs preserved.
            // NG miniclients (ExoPlayer) need the format hint to match the wire
            // container or the Extractor mis-sniffs. The legacy fallthrough used
            // cf.getFullPropertyString() which advertised the source container
            // (MPEG2-TS) — wrong for the remuxed PS bytes. Build a corrected
            // hint here that keeps source codec metadata but switches container
            // to MPEG2-PS and drops PID/stream-id fields that don't apply to PS.
            sage.media.format.ContainerFormat srcCf = cf;
            cf = null;
            StringBuilder fb = new StringBuilder();
            fb.append("f=").append(sage.media.format.MediaFormat.MPEG2_PS).append(";");
            sage.media.format.VideoFormat srcVf = (srcCf != null) ? srcCf.getVideoFormat() : null;
            if (srcVf != null && srcVf.getFormatName() != null)
            {
              fb.append("[bf=vid;f=").append(srcVf.getFormatName());
              if (srcVf.getWidth() > 0)  fb.append(";w=").append(srcVf.getWidth());
              if (srcVf.getHeight() > 0) fb.append(";h=").append(srcVf.getHeight());
              if (srcVf.getFps() > 0)    fb.append(";fps=").append(srcVf.getFps());
              if (srcVf.getAspectRatio() > 0) fb.append(";ar=").append(srcVf.getAspectRatio());
              fb.append(";]");
            }
            sage.media.format.AudioFormat srcAf = (srcCf != null) ? srcCf.getAudioFormat() : null;
            if (srcAf != null && srcAf.getFormatName() != null)
            {
              fb.append("[bf=aud;f=").append(srcAf.getFormatName());
              if (srcAf.getChannels() > 0)      fb.append(";ch=").append(srcAf.getChannels());
              if (srcAf.getSamplingRate() > 0)  fb.append(";sr=").append(srcAf.getSamplingRate());
              if (srcAf.getBitsPerSample() > 0) fb.append(";bps=").append(srcAf.getBitsPerSample());
              fb.append(";]");
            }
            formatString = fb.toString();
            if (Sage.DBG) System.out.println("MiniPlayer: mpeg2psremux push format hint -> " + formatString);
          }
          else if (serverSideTranscoding && mediaExtender)
          {
            cf = null; // don't set the format since it'll be a base MPEG2 format
            // But if we're doing placeshifting then we need the format string
            if ("dynamic".equals(prefTranscodeMode))
            {
              formatString = "f=MPEG2-PS;[bf=vid;f=MPEG4;][bf=aud;f=MP2]";
            }
            else if ("dynamicts".equals(prefTranscodeMode))
            {
              formatString = "f=MPEG2-TS;[bf=vid;f=MPEG4;][bf=aud;f=AAC]";
            }
            else if ("music".equals(prefTranscodeMode) || "music128".equals(prefTranscodeMode))
            {
              formatString = "f=MPEG2-PS;[bf=aud;f=MP2]";
            }
            else if ("audioonly".equals(prefTranscodeMode))
            {
              // Audio-only transcode keeps source video via -vcodec copy and remuxes
              // into MPEG2-TS with EAC3 (or AC3) audio. Tell the client EXACTLY that
              // so it sets up its TS demuxer + correct decoders. Without this the
              // client falls back to its FIXED_PUSH_MEDIA_FORMAT default (often
              // matroska/mp2) and renders nothing because the wire format doesn't
              // match the demuxer it initialized.
              sage.media.format.ContainerFormat srcCf = currMF.getFileFormat();
              sage.media.format.VideoFormat srcVf = (srcCf != null) ? srcCf.getVideoFormat() : null;
              sage.media.format.AudioFormat srcAf = (srcCf != null) ? srcCf.getAudioFormat() : null;
              // Map the active audioonly video codec setting to the wire codec name we
              // advertise to the client. If we're not doing -vcodec copy then the bytes
              // on the wire are NOT the source codec — telling the client they are will
              // make it spin up the wrong decoder (e.g. HEVC for an H.264 stream → black).
              String aoVc = Sage.get("miniplayer/audioonly_video_codec", "h264_nvenc");
              String vcName;
              if (aoVc == null || aoVc.length() == 0 || "copy".equalsIgnoreCase(aoVc))
                vcName = (srcVf != null && srcVf.getFormatName() != null) ? srcVf.getFormatName() : sage.media.format.MediaFormat.H264;
              else if ("auto".equalsIgnoreCase(aoVc))
              {
                // HwEncoder auto-pick targets H.264 by default (see FFMPEGTranscoder
                // audioonly path); advertise H.264 to the client to match.
                vcName = sage.media.format.MediaFormat.H264;
              }
              else
              {
                String norm = sage.HwEncoder.normalizeCodec(aoVc);
                if ("hevc".equals(norm))
                  vcName = sage.media.format.MediaFormat.HEVC;
                else
                  vcName = sage.media.format.MediaFormat.H264;
              }
              boolean clientEac3 = (mcsr != null && mcsr.isSupportedAudioCodec(sage.media.format.MediaFormat.EAC3));
              String acName = (srcAf != null && sage.media.format.MediaFormat.AC4.equals(srcAf.getFormatName()))
                  ? (clientEac3 ? sage.media.format.MediaFormat.EAC3 : sage.media.format.MediaFormat.AC3)
                  : ((srcAf != null && srcAf.getFormatName() != null) ? srcAf.getFormatName() : sage.media.format.MediaFormat.AC3);
              StringBuilder fb = new StringBuilder();
              fb.append("f=").append(sage.media.format.MediaFormat.MPEG2_TS).append(";");
              fb.append("[bf=vid;f=").append(vcName);
              if (srcVf != null)
              {
                if (srcVf.getWidth() > 0)  fb.append(";w=").append(srcVf.getWidth());
                if (srcVf.getHeight() > 0) fb.append(";h=").append(srcVf.getHeight());
                if (srcVf.getFps() > 0)    fb.append(";fps=").append(srcVf.getFps());
              }
              fb.append(";]");
              fb.append("[bf=aud;f=").append(acName).append(";]");
              formatString = fb.toString();
              if (Sage.DBG) System.out.println("MiniPlayer: audio-only push format hint -> " + formatString);
            }
          }
          if (cf != null)
          {
            formatString = cf.getFullPropertyString(false);
            if (serverSideTranscoding && !useOriginalAudioTrack)
            {
              // Change it to be the transcode format properties...for now just fix audio sampling rate for the hd extender
              // NOTE: FIX ME FIX ME!!!!
              formatString = formatString.replaceAll("\\;sr\\=[0-9]*\\;", ";sr=48000;");
            }
          }
          else if (formatString.length() == 0 && serverSideTranscoding && prefTranscodeMode != null
              && prefTranscodeMode.length() > 0)
          {
            // The mediaExtender branch above nulled cf because the legacy code
            // pre-baked hints only for a fixed set of modes (dynamic/dynamicts/
            // music*/audioonly/mpeg2psremux). User-configurable xcode_qualities
            // entries (DVD, DVD6Ch, SVCD, custom) fell through with formatString
            // empty → NG miniclients (ExoPlayer) get `push:` with no hint and
            // must sniff. Sniffing of MPEG2-PS streams often produces audio-only
            // (first AC3 track) with no video. Parse the active xcode_qualities
            // property and synthesize a matching hint.
            String xcodeArgs = Sage.get(MediaServer.XCODE_QUALITIES_PROPERTY_ROOT + prefTranscodeMode, null);
            // When prefTranscodeMode is itself a property-string spec
            // (e.g. "container=matroska;videocodec=COPY;audiocodec=eac3;...")
            // — as produced by the profile-authoritative override for
            // HEVC video-copy + audio-transcode — there is no xcode_qualities
            // entry. Parse the tokens FFMPEGTranscoder also parses and
            // synthesize the wire format hint directly. Without this the
            // openURL0(push:) goes out with an EMPTY descriptor and the
            // client sits ~30s waiting for a format declaration that never
            // arrives, then drops the socket.
            if ((xcodeArgs == null || xcodeArgs.length() == 0)
                && prefTranscodeMode != null && prefTranscodeMode.indexOf('=') >= 0)
            {
              String _modeContainer = null;
              String _modeVCodec = null;
              String _modeACodec = null;
              java.util.StringTokenizer mt = new java.util.StringTokenizer(prefTranscodeMode, ";");
              while (mt.hasMoreTokens())
              {
                String t = mt.nextToken();
                int eq = t.indexOf('=');
                if (eq < 0) continue;
                String k = t.substring(0, eq).trim();
                String v = t.substring(eq + 1).trim();
                if ("container".equalsIgnoreCase(k)) _modeContainer = v;
                else if ("videocodec".equalsIgnoreCase(k)) _modeVCodec = v;
                else if ("audiocodec".equalsIgnoreCase(k)) _modeACodec = v;
              }
              String wireContainer = sage.media.format.MediaFormat.MPEG2_PS;
              if (_modeContainer != null)
              {
                String mc = _modeContainer.toLowerCase();
                if ("matroska".equals(mc) || "mkv".equals(mc) || "webm".equals(mc))
                  wireContainer = sage.media.format.MediaFormat.MATROSKA;
                else if ("mp4".equals(mc) || "ismv".equals(mc))
                  wireContainer = sage.media.format.MediaFormat.QUICKTIME;
                else if ("mpegts".equals(mc) || "ts".equals(mc))
                  wireContainer = sage.media.format.MediaFormat.MPEG2_TS;
                else if ("dvd".equals(mc) || "vob".equals(mc) || "mpeg".equals(mc))
                  wireContainer = sage.media.format.MediaFormat.MPEG2_PS;
              }
              String wireVCodec = sage.media.format.MediaFormat.MPEG2_VIDEO;
              if ("COPY".equalsIgnoreCase(_modeVCodec))
              {
                sage.media.format.ContainerFormat _sc = currMF.getFileFormat();
                sage.media.format.VideoFormat _sv = (_sc != null) ? _sc.getVideoFormat() : null;
                if (_sv != null && _sv.getFormatName() != null)
                  wireVCodec = _sv.getFormatName();
              }
              else if (_modeVCodec != null)
              {
                String vc = _modeVCodec.toLowerCase();
                if (vc.startsWith("libx265") || "hevc".equals(vc) || "h265".equals(vc))
                  wireVCodec = sage.media.format.MediaFormat.HEVC;
                else if (vc.startsWith("libx264") || "h264".equals(vc) || vc.startsWith("h264_"))
                  wireVCodec = sage.media.format.MediaFormat.H264;
                else if ("mpeg4".equals(vc) || "libxvid".equals(vc))
                  wireVCodec = sage.media.format.MediaFormat.MPEG4_VIDEO;
                else if ("mpeg2video".equals(vc) || "mpeg2".equals(vc))
                  wireVCodec = sage.media.format.MediaFormat.MPEG2_VIDEO;
              }
              String wireACodec = sage.media.format.MediaFormat.MP2;
              if ("COPY".equalsIgnoreCase(_modeACodec))
              {
                sage.media.format.ContainerFormat _sc = currMF.getFileFormat();
                sage.media.format.AudioFormat _sa = (_sc != null) ? _sc.getAudioFormat() : null;
                if (_sa != null && _sa.getFormatName() != null)
                  wireACodec = _sa.getFormatName();
              }
              else if (_modeACodec != null)
              {
                String ac = _modeACodec.toLowerCase();
                if ("eac3".equals(ac) || "ec-3".equals(ac) || "ec3".equals(ac))
                  wireACodec = sage.media.format.MediaFormat.EAC3;
                else if ("ac3".equals(ac))
                  wireACodec = sage.media.format.MediaFormat.AC3;
                else if (ac.startsWith("aac") || "libfdk_aac".equals(ac))
                  wireACodec = sage.media.format.MediaFormat.AAC;
                else if ("mp2".equals(ac))
                  wireACodec = sage.media.format.MediaFormat.MP2;
                else if ("mp3".equals(ac) || "libmp3lame".equals(ac))
                  wireACodec = sage.media.format.MediaFormat.MP3;
              }
              StringBuilder fb = new StringBuilder();
              fb.append("f=").append(wireContainer).append(";");
              fb.append("[bf=vid;f=").append(wireVCodec).append(";]");
              fb.append("[bf=aud;f=").append(wireACodec).append(";]");
              formatString = fb.toString();
              if (Sage.DBG) System.out.println("MiniPlayer: property-string transcode mode — synthesized push hint -> "
                  + formatString + " from mode=" + prefTranscodeMode);
            }
            else if (xcodeArgs != null && xcodeArgs.length() > 0)
            {
              String wireContainer = sage.media.format.MediaFormat.MPEG2_PS;
              String wireVCodec = sage.media.format.MediaFormat.MPEG2_VIDEO;
              String wireACodec = sage.media.format.MediaFormat.MP2;
              boolean videoOnlyDropped = xcodeArgs.contains(" -vn ");
              java.util.StringTokenizer toker = new java.util.StringTokenizer(xcodeArgs, " ");
              while (toker.hasMoreTokens())
              {
                String tok = toker.nextToken();
                if ("-f".equals(tok) && toker.hasMoreTokens())
                {
                  String f = toker.nextToken();
                  if ("mpegts".equalsIgnoreCase(f) || "ts".equalsIgnoreCase(f))
                    wireContainer = sage.media.format.MediaFormat.MPEG2_TS;
                  else if ("dvd".equalsIgnoreCase(f) || "vob".equalsIgnoreCase(f) || "mpeg".equalsIgnoreCase(f) || "vcd".equalsIgnoreCase(f) || "svcd".equalsIgnoreCase(f))
                    wireContainer = sage.media.format.MediaFormat.MPEG2_PS;
                  else if ("mp4".equalsIgnoreCase(f) || "ismv".equalsIgnoreCase(f))
                    wireContainer = sage.media.format.MediaFormat.QUICKTIME;
                  else if ("matroska".equalsIgnoreCase(f) || "mkv".equalsIgnoreCase(f) || "webm".equalsIgnoreCase(f))
                    wireContainer = sage.media.format.MediaFormat.MATROSKA;
                }
                else if (("-acodec".equals(tok) || "-c:a".equals(tok)) && toker.hasMoreTokens())
                {
                  String a = toker.nextToken();
                  if ("ac3".equalsIgnoreCase(a)) wireACodec = sage.media.format.MediaFormat.AC3;
                  else if ("eac3".equalsIgnoreCase(a) || "ac3_fixed".equalsIgnoreCase(a)) wireACodec = sage.media.format.MediaFormat.EAC3;
                  else if ("aac".equalsIgnoreCase(a) || "libfdk_aac".equalsIgnoreCase(a)) wireACodec = sage.media.format.MediaFormat.AAC;
                  else if ("mp2".equalsIgnoreCase(a)) wireACodec = sage.media.format.MediaFormat.MP2;
                  else if ("libmp3lame".equalsIgnoreCase(a) || "mp3".equalsIgnoreCase(a)) wireACodec = sage.media.format.MediaFormat.MP3;
                  else if ("copy".equalsIgnoreCase(a))
                  {
                    sage.media.format.ContainerFormat srcCf2 = currMF.getFileFormat();
                    sage.media.format.AudioFormat srcAf2 = (srcCf2 != null) ? srcCf2.getAudioFormat() : null;
                    if (srcAf2 != null && srcAf2.getFormatName() != null) wireACodec = srcAf2.getFormatName();
                  }
                }
                else if (("-vcodec".equals(tok) || "-c:v".equals(tok)) && toker.hasMoreTokens())
                {
                  String v = toker.nextToken();
                  if ("mpeg2video".equalsIgnoreCase(v) || "mpeg2".equalsIgnoreCase(v)) wireVCodec = sage.media.format.MediaFormat.MPEG2_VIDEO;
                  else if ("mpeg4".equalsIgnoreCase(v) || "libxvid".equalsIgnoreCase(v)) wireVCodec = sage.media.format.MediaFormat.MPEG4_VIDEO;
                  else if ("libx264".equalsIgnoreCase(v) || "h264".equalsIgnoreCase(v) || "h264_nvenc".equalsIgnoreCase(v) || "h264_qsv".equalsIgnoreCase(v) || "h264_vaapi".equalsIgnoreCase(v)) wireVCodec = sage.media.format.MediaFormat.H264;
                  else if ("libx265".equalsIgnoreCase(v) || "hevc".equalsIgnoreCase(v) || "hevc_nvenc".equalsIgnoreCase(v) || "hevc_qsv".equalsIgnoreCase(v) || "hevc_vaapi".equalsIgnoreCase(v)) wireVCodec = sage.media.format.MediaFormat.HEVC;
                  else if ("copy".equalsIgnoreCase(v))
                  {
                    sage.media.format.ContainerFormat srcCf2 = currMF.getFileFormat();
                    sage.media.format.VideoFormat srcVf2 = (srcCf2 != null) ? srcCf2.getVideoFormat() : null;
                    if (srcVf2 != null && srcVf2.getFormatName() != null) wireVCodec = srcVf2.getFormatName();
                  }
                }
              }
              StringBuilder fb = new StringBuilder();
              fb.append("f=").append(wireContainer).append(";");
              if (!videoOnlyDropped)
                fb.append("[bf=vid;f=").append(wireVCodec).append(";]");
              fb.append("[bf=aud;f=").append(wireACodec).append(";]");
              formatString = fb.toString();
              if (Sage.DBG) System.out.println("MiniPlayer: fallback push format hint for prefTranscodeMode='"
                  + prefTranscodeMode + "' (parsed from xcode_qualities) -> " + formatString);
            }
          }
          else if (formatString.length() == 0 && !serverSideTranscoding && !usingRemuxer
              && mcsr != null && mcsr.getResolvedProfile() != null
              && majorTypeHint == MediaFile.MEDIATYPE_VIDEO)
          {
            // DIRECT_PLAY with no parsed source format (newly-imported file
            // whose async format parse hasn't completed yet). Without a hint
            // NG miniclients (ExoPlayer) must sniff; for HLS-of-TS or raw TS
            // that often fails. Synthesize a minimal profile-derived hint so
            // the client at least picks the right Extractor. Default to
            // MPEG2-TS since that's what every SageTV recording is on the wire.
            String containerHint = sage.media.format.MediaFormat.MPEG2_TS;
            sage.client.ClientProfile prof = mcsr.getResolvedProfile();
            java.util.Set<String> profConts = prof.getContainers();
            if (profConts != null && !profConts.isEmpty()
                && !profConts.contains(sage.media.format.MediaFormat.MPEG2_TS))
            {
              // Pick the first profile-allowed container as the floor.
              containerHint = profConts.iterator().next();
            }
            formatString = "f=" + containerHint + ";";
            if (Sage.DBG) System.out.println("MiniPlayer: DIRECT_PLAY with null source format — "
                + "synthesized profile-derived push hint -> " + formatString);
          }
        }
        // INVARIANT GUARD: if PlaybackDecisionEngine said TRANSCODE because a source
        // codec is unsupported, the emitted descriptor MUST NOT advertise that source
        // codec back as-is (which would mean we're about to `-vcodec copy`/`-acodec copy`
        // a stream the client cannot decode). Log loudly so regressions are obvious in
        // the server log; do not silently emit a broken openURL.
        if (profileDecision != null
            && profileDecision.decision == sage.client.PlaybackDecisionEngine.Decision.TRANSCODE
            && currMF != null && currMF.getFileFormat() != null
            && formatString != null && formatString.length() > 0)
        {
          sage.media.format.ContainerFormat _srcCf = currMF.getFileFormat();
          sage.media.format.VideoFormat _srcVf = _srcCf.getVideoFormat();
          sage.media.format.AudioFormat _srcAf = _srcCf.getAudioFormat();
          sage.client.ClientProfile _prof = (mcsr != null) ? mcsr.getResolvedProfile() : null;
          if (_prof != null)
          {
            if (_srcVf != null && _srcVf.getFormatName() != null
                && !_prof.isVideoCodecAllowed(_srcVf.getFormatName())
                && formatString.indexOf("bf=vid;f=" + _srcVf.getFormatName() + ";") >= 0)
            {
              System.out.println("MiniPlayer: WARNING INVARIANT VIOLATION — TRANSCODE decision but openURL"
                  + " descriptor still advertises unsupported source video codec '" + _srcVf.getFormatName()
                  + "' (profile=" + _prof.getProfileId() + " reason=" + profileDecision.reason
                  + " formatString=" + formatString + " prefTranscodeMode=" + prefTranscodeMode + ")");
            }
            if (_srcAf != null && _srcAf.getFormatName() != null
                && !_prof.isAudioCodecAllowed(_srcAf.getFormatName())
                && formatString.indexOf("bf=aud;f=" + _srcAf.getFormatName() + ";") >= 0)
            {
              System.out.println("MiniPlayer: WARNING INVARIANT VIOLATION — TRANSCODE decision but openURL"
                  + " descriptor still advertises unsupported source audio codec '" + _srcAf.getFormatName()
                  + "' (profile=" + _prof.getProfileId() + " reason=" + profileDecision.reason
                  + " formatString=" + formatString + " prefTranscodeMode=" + prefTranscodeMode + ")");
            }
          }
        }
        // NG push-mode format hint: append MIME triplet parsed from the wire-format descriptor
        if (ngSession && formatString != null && formatString.length() > 0)
        {
          String cMime = null;
          String vMime = null;
          String aMime = null;
          // Container: first "f=XXX;" not inside a bf= block
          int fIdx = formatString.indexOf("f=");
          if (fIdx >= 0)
          {
            int fEnd = formatString.indexOf(';', fIdx);
            if (fEnd > fIdx) cMime = toMimeType(formatString.substring(fIdx + 2, fEnd));
          }
          // Video: "bf=vid;f=XXX;"
          int vIdx = formatString.indexOf("bf=vid;f=");
          if (vIdx >= 0)
          {
            int vStart = vIdx + 9; // length of "bf=vid;f="
            int vEnd = formatString.indexOf(';', vStart);
            if (vEnd > vStart) vMime = toMimeType(formatString.substring(vStart, vEnd));
          }
          // Audio: "bf=aud;f=XXX;"
          int aIdx = formatString.indexOf("bf=aud;f=");
          if (aIdx >= 0)
          {
            int aStart = aIdx + 9; // length of "bf=aud;f="
            int aEnd = formatString.indexOf(';', aStart);
            if (aEnd > aStart) aMime = toMimeType(formatString.substring(aStart, aEnd));
          }
          if (cMime != null || vMime != null || aMime != null)
          {
            formatString += "|ng_fmt=" + (cMime != null ? cMime : "") + ","
                + (vMime != null ? vMime : "") + ","
                + (aMime != null ? aMime : "");
            if (Sage.DBG) System.out.println("MiniPlayer: NG push-mode format hint -> " + formatString);
          }
        }
        if (!openURL0("push:" + formatString))
          throw new PlaybackException();
      }
      else
      {
        // Do this now since we may use it below for determining if we're localhost or not & setting up the stv:// URL hostname
        String theURL = null;
        if (majorTypeHint == MediaFile.MEDIATYPE_DVD && file == null)
          theURL = "dvd://";
        else if (httpls)
        {
          // NOTE: We should put some kind of HOSTNAME marker in here that the client replaces with the address they connected to since
          // we won't necessarily know our external IP address if they didn't use the locator ID to connect
          // Temp hack to get our external IP for now
          String ipPort = null;
          /*					try
					{
						ipPort = sage.locator.LocatorLookupClient.lookupIPForGuid(sage.locator.LocatorRegistrationClient.getPrettyGuid(
							sage.locator.LocatorRegistrationClient.getSystemGuid()));
					}
					catch (java.io.IOException ioe){}
					if (ipPort != null && ipPort.indexOf(":") == -1)
						ipPort += ":31099";
					if (ipPort == null)
						ipPort = "192.168.1.22:31099";*/
          ipPort = "HOSTNAME";
          String forced = Sage.get("forced_external_httpls_addr_port", "");
          if (forced != null && forced.length() > 0)
            ipPort = forced;

          theURL = "http://" + ipPort + "/iosstream_" + uiMgr.getLocalUIClientName() + "_" + currMF.id + "_" + VideoFrame.getVideoFrameForPlayer(this).getCurrSegment() + "_list.m3u8";
        }
        else if (pureLocal)
        {
          theURL = hostname;
        }
        else if (hostname != null && hostname.equals(Sage.get("alternate_media_server", "")))
          theURL = "stv://" + hostname + (Sage.getBoolean("use_alternate_streaming_ports", false) ?
              ":7817" : "") + "/" + file.getAbsolutePath();
        else if (mcsr.isStreamingProtocolSupported("stv") && (!IOUtils.isLocalhostSocket(clientSocket.socket()) || timeshifted))
          theURL = "stv://" + clientSocket.socket().getLocalAddress().getHostAddress() + "/" + file.getAbsolutePath();
        else
          theURL = file.getAbsolutePath();
        // NG pull-mode format hint: append MIME triplet so client skips probing
        if (ngSession && theURL != null && !theURL.startsWith("dvd") && currMF != null)
        {
          sage.media.format.ContainerFormat ngCf = currMF.getFileFormat();
          if (ngCf != null)
          {
            String cMime = toMimeType(ngCf.getFormatName());
            String vMime = null;
            String aMime = null;
            sage.media.format.VideoFormat ngVf = ngCf.getVideoFormat();
            if (ngVf != null) vMime = toMimeType(ngVf.getFormatName());
            sage.media.format.AudioFormat ngAf = ngCf.getAudioFormat();
            if (ngAf != null) aMime = toMimeType(ngAf.getFormatName());
            if (cMime != null || vMime != null || aMime != null)
            {
              String sep = theURL.contains("?") ? "&" : "?";
              theURL += sep + "ng_fmt=" + (cMime != null ? cMime : "") + ","
                  + (vMime != null ? vMime : "") + ","
                  + (aMime != null ? aMime : "");
              if (Sage.DBG) System.out.println("MiniPlayer: NG pull-mode format hint -> " + theURL);
            }
          }
        }
        if (!openURL0(theURL))
          throw new PlaybackException();
      }
      // For extenders, set the correct audio stream we're using for playback
      if (((mediaExtender && pushMode) || hdMediaExtender) && !lowBandwidth)
      {
        sage.media.format.ContainerFormat cf = (bdp != null) ? bdp.getFileFormat() : currMF.getFileFormat();
        if (cf != null && "true".equals(cf.getMetadataProperty("VARIED_FORMAT")))
          cf = sage.media.format.FormatParser.getFileFormat(file);
        if (usingRemuxer)
          cf = ((RemuxTranscodeEngine)mpegSrc.getTranscoder()).getTargetFormat();
        if (cf != null && cf.getNumAudioStreams() > 0)
        {
          sage.media.format.AudioFormat af = cf.getAudioFormat();
          int audioStreamType = (using6ChAudioTranscode && serverSideTranscoding && !usingRemuxer) ? 0xbd80 : 0xc000;
          int ac3indexOffset = (af != null && usingRemuxer) ? af.getOrderIndex() : 0;
          // If we're transcoding then the original audio stream doesn't matter, just use 0xc0
          // unless we're using the remuxer....
          if (af != null && (useOriginalAudioTrack || ((!serverSideTranscoding && !transcoded))/* || (mpegSrc != null && mpegSrc.getTranscoder() instanceof RemuxTranscodeEngine)*/))
          {
            String streamID = af.getId();
            if (streamID != null && streamID.length() > 0)
            {
              // See if it's just a stream ID or if it's 2 parts
              int dashIdx = streamID.indexOf('-');
              if (dashIdx == -1)
              {
                try
                {
                  if (streamID.length() == 4) // the full ID
                    audioStreamType = Integer.parseInt(streamID, 16);
                  else
                    audioStreamType = (Integer.parseInt(streamID, 16) << 8);
                }
                catch (NumberFormatException nfe)
                {
                  if (Sage.DBG) System.out.println("ERROR parsing audio stream ID " + streamID + " of: " + nfe);
                }
              }
              else
              {
                try
                {
                  audioStreamType = (Integer.parseInt(streamID.substring(0, dashIdx), 16) << 8) |
                      Integer.parseInt(streamID.substring(dashIdx + 1, dashIdx + 3), 16);
                }
                catch (NumberFormatException nfe)
                {
                  if (Sage.DBG) System.out.println("ERROR parsing audio stream ID " + streamID + " of: " + nfe);
                }
              }
            }
          }
          if (af != null && serverSideTranscoding && useOriginalAudioTrack && sage.media.format.MediaFormat.AC3.equals(af.getFormatName()))
          {
            if (Sage.DBG) System.out.println("Switching audio stream to be 0xbd-80 for default AC3");
            audioStreamType = 0xbd80 + ac3indexOffset;
          }
          if (hdMediaExtender && !serverSideTranscoding)
          {
            audioTracks = cf.getAudioFormats();
            if (af != null)
            {
              for (int i = 0; i < audioTracks.length; i++)
              {
                if (audioTracks[i] == af)
                {
                  languageIndex = i;
                  break;
                }
              }
            }
            subpicTracks = cf.getSubpictureFormats();
            subpicIndex = 0;
            subpicOn = false;
            // Disable subpictures for DVB if its an HD100
            if (!hdMediaPlayer && subpicTracks != null && subpicTracks.length > 0)
            {
              for (int i = 0; i < subpicTracks.length; i++)
              {
                if ("dvbsub".equalsIgnoreCase(subpicTracks[i].getFormatName()))
                {
                  if (Sage.DBG) System.out.println("Disabling subpicture track selection for HD100 since it doesn't support DVB subpictures");
                  subpicTracks = null;
                  break;
                }
              }
            }
          }
          if (pushMode)
          {
            // DVDStream(0, audioStreamType) sends MEDIACMD_DVD_STREAM with an
            // MPEG-2 PS substream ID (0xbd80 for AC-3, 0xc000 for MPEG audio).
            // This is only meaningful when the wire container is MPEG2-PS — for
            // Matroska / MP4 / MPEG2-TS outputs the legacy Android miniclient
            // never replies, blocking the load() thread on clientInStream.readInt()
            // for ~30s until the push socket idle-times-out. Skip the call when
            // we know the wire container is not PS. Detected from the
            // property-string transcode mode produced by the profile-authoritative
            // override (e.g. "container=matroska;videocodec=COPY;...").
            boolean _skipDVDStreamAudio = false;
            if (prefTranscodeMode != null && prefTranscodeMode.indexOf("container=") >= 0)
            {
              String _pmLow = prefTranscodeMode.toLowerCase();
              if (_pmLow.contains("container=matroska") || _pmLow.contains("container=mkv")
                  || _pmLow.contains("container=webm") || _pmLow.contains("container=mp4")
                  || _pmLow.contains("container=ismv") || _pmLow.contains("container=mpegts")
                  || _pmLow.contains("container=ts") || _pmLow.contains("container=mpeg2-ts"))
              {
                _skipDVDStreamAudio = true;
              }
            }
            if (_skipDVDStreamAudio)
            {
              if (Sage.DBG) System.out.println("MiniPlayer: skipping DVDStream(0,0x"
                  + Integer.toString(audioStreamType, 16)
                  + ") — wire container is non-PS (mode=" + prefTranscodeMode
                  + "); legacy command would hang the client 30s on Matroska/MP4/TS push");
            }
            else
            {
              if (Sage.DBG) System.out.println("Setting audio stream for playback to be ID=0x" + Integer.toString(audioStreamType, 16));
              DVDStream(0, audioStreamType);
              matchBDSubpictureToAudio();
            }
          }
        }
        if (isMpeg2PS)
        {
          if (Sage.DBG) System.out.println("Setting default subpicture track to be disabled for MPEG2-PS");
          DVDStream(1, PS_SUBPIC_DISABLE_STREAM);
        }
      }
      else if (hdMediaExtender && pushMode && lowBandwidth && serverSideTranscoding)
      {
        // We still need to set the default audio stream
        DVDStream(0, 0xc000);
      }

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
        long ngRecStart = (ngMF != null) ? ngMF.getRecordTime() : 0;
        long ngInitialSize = finalLength;
        sage.ng.NgPlaybackContextWiring.FileSizeSupplier ngSizeSupplier = null;
        if (timeshifted && mpegSrc != null)
        {
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

      currHintMajorType = majorTypeHint;
      currHintMinorType = minorTypeHint;
      currHintEncoding = encodingHint;

      currCCState = -1;
      //videoDimensions = new java.awt.Dimension(mpegSrc.parsedVideo.horizontal_size_value,
      //    mpegSrc.parsedVideo.vertical_size_value);
      // Use the native size returned even though it's not right because it's what we want to use
      // for video rectangles. That's the purpose of this information.
      videoDimensions = getVideoDimensions0();
      if (Sage.DBG) System.out.println("Sigma video dim=" + videoDimensions);
      currFile = file;

      // If we don't pause it first then the push thread may not see the correct state and terminate immediately
      if (pushMode)
        pause();

      if (mediaExtender)
        setMute0(false);

      // Preserve the volume setting across UI sessions
      if (uiMgr != null)
        setVolume(uiMgr.getFloat("miniplayer/last_volume", 1.0f));

      //		flushPush0();

      /*
       * There is ALWAYS a seek after load is completed to set the initial time for file playback. Do not start
       * pushing until after we've done that seek.
       */
      if (pushMode)
      {
        pushThreadCreated = false;
      }
      //createPushThread();
    }
  }



  // For pushing we create a thread that takes data from the Mpeg source and shoves it into the decoder.
  // Before it does that, it first checks to be sure there's enough data to be read in the Mpeg source. Then
  // it gets the next available buffer from the decoder once it's available. At that point, it'll get the decoder buffer,
  // then read from the source into a Java buffer, and then do a native copy from the Java buffer to the decoder buffer.
  // Then it sends the decoder the buffer.  This should cause the smallest delay for any kind of stream interruption we want
  // to perform for seeking.
  protected void createPushThread()
  {
    if (Sage.DBG) System.out.println("Creating new push thread");
    pushThread = new Thread(new Runnable()
    {
      public void run()
      {
        if (Sage.DBG) System.out.println("Pusher thread is starting");
        if (rpSrc != null)
        {
          synchronized (decoderLock)
          {
            try
            {
              rpSrc.sendStart();
            }
            catch (java.io.IOException e)
            {
              if (Sage.DBG) System.out.println("Error sending START command to remote pusher of:" + e);
              e.printStackTrace();
              connectionError();
              return;
            }
          }
        }
        pushBufferSize = (lowBandwidth || currHintMajorType == MediaFile.MEDIATYPE_AUDIO) ? 16384 : Math.min(maxPushBufferSize, 131072);
        if (Sage.DBG) System.out.println("Miniplayer pusher using buffer size of " + pushBufferSize);
        if (hdMediaPlayer && !lowBandwidth && currHintMajorType != MediaFile.MEDIATYPE_AUDIO && !transcoded && !serverSideTranscoding)
        {
          // Check if this is a transport stream, and if so modulus the buffer size with the TS packet size. This
          // helps with aligment on smooth FF/REW
          MediaFile currMF = VideoFrame.getMediaFileForPlayer(MiniPlayer.this);
          if (currMF != null)
          {
            sage.media.format.ContainerFormat cf = currMF.getFileFormat();
            if (cf != null && sage.media.format.MediaFormat.MPEG2_TS.equals(cf.getFormatName()))
            {
              int packetSize = cf.getPacketSize();
              if (packetSize == 0)
                packetSize = 188;
              pushBufferSize = pushBufferSize - (pushBufferSize % packetSize);
              if (Sage.DBG) System.out.println("Adjusted push buffer size for TS packet alignment of " + packetSize + " bytes to be: " + pushBufferSize);
            }
          }
        }
        pushDumpStream = null;
        String pushDumpFileName = (uiMgr == null) ? "" : uiMgr.get("miniclient/push_dump_debug_file", "");
        if (pushDumpFileName.length() > 0)
        {
          int dumpFileIdx = 0;
          while (new java.io.File(pushDumpFileName + "-" + dumpFileIdx + ".mpg").isFile())
            dumpFileIdx++;
          try
          {
            pushDumpStream = new java.io.FileOutputStream(pushDumpFileName + "-" + dumpFileIdx + ".mpg").getChannel();
          }
          catch (java.io.IOException e)
          {
            System.out.println("ERROR creating push dump debug file:" +e );
          }
        }
        if (bdp != null)
        {
          currBDAngle = 1;
          currBDTitle = bdp.getTitle();
        }
        java.nio.ByteBuffer javaBuff = (tcSrc != null) ? java.nio.ByteBuffer.allocate(pushBufferSize) : java.nio.ByteBuffer.allocateDirect(pushBufferSize);
        boolean kickVF = false;
        float lastPlayRate = 1;
        while ((currState == PAUSE_STATE || currState == PLAY_STATE))
        {
          if (kickVF)
          {
            VideoFrame vf = VideoFrame.getVideoFrameForPlayer(MiniPlayer.this);
            vf.kick();
            kickVF = false;
            continue;
          }
          synchronized (decoderLock)
          {
            if (currState != PAUSE_STATE && currState != PLAY_STATE)
              break;
            if (debugPush) System.out.println("SDPushLoop");
            if (detailedPushBufferStats && clientReportedPlayState == EOS_STATE && !eos)
            {
              if (Sage.DBG) System.out.println("Client reported play state indicates EOS, set the flag-1");
              // Reset the UI timeouts on an EOS so we don't trigger the SS since it considers EOS to be a non-playing state
              if (uiMgr != null)
                uiMgr.getRouter().resetInactivityTimers();
              eos = true;
              kickVF = true;
            }
            // In low bandwidth mode we push even when paused so we can get ahead further in buffering
            // We should always do this in order to keep as much data in the client's buffer as possible...BUT there's
            // a legacy bug in the HD media extender where it starts playing after a flush, so if we re-enable this
            // then seeking while paused will cause playback to resume!
            if (currState == PAUSE_STATE && !lowBandwidth && (mcsr == null || !mcsr.supportsFrameStep()))
            {
              if (debugPush) System.out.println("Waiting in paused state");
              try{decoderLock.wait(100);}catch(Exception e){}
              continue;
            }
            if (shouldYieldDecoderLock())
            {
              try{
                decoderLock.notifyAll();
                decoderLock.wait(20);}catch(Exception e){}
              continue;
            }
            if (rpSrc == null && (transcoded || timeshifted) &&
                (transcoded ? tcSrc.availableToRead() :
                  mpegSrc.availableToRead2(pushBufferSize))< (transcoded ? 0 : pushBufferSize))
            {
              if (debugPush) System.out.println("SigmaPlayer waiting for data to appear in file...");
              boolean alreadyCalledPushBuffer = false;
              // Be sure we keep our stats updated if we've pushed all the data and are just waiting around for the client
              // to get to the EOS
              if (detailedPushBufferStats && Sage.eventTime() - lastDetailedBufferUpdate > 500)
              {
                boolean sendServerEOS = false;
                if (serverSideTranscoding && mpegSrc != null)
                {
                  if (mpegSrc.getTranscoder().isTranscodeDone())
                  {
                    if (!((FFMPEGTranscoder) (mpegSrc.getTranscoder())).didTranscodeCompleteOK())
                    {
                      if (Sage.DBG) System.out.println("Detected failure in the transcoder attempt to restart it...");
                      try
                      {
                        mpegSrc.seek(mpegSrc.getLastParsedTimeMillis());
                      }
                      catch (java.io.IOException ioe)
                      {
                        if (Sage.DBG) System.out.println("ERROR restarting the transcoder of:" + ioe);
                        sendServerEOS = true;
                      }
                    }
                    else
                    {
                      if (debugPush) System.out.println("Server is pushing an EOS message to the client");
                      sendServerEOS = true;
                    }
                  }
                }
                if (numPushedBuffers > 1 || sendServerEOS)
                {
                  if (!pushBuffer0(javaBuff, 0, (sendServerEOS ? 0x80 : 0) | getFlags()))
                  {
                    if (Sage.DBG) System.out.println("pushBuffer call failed; terminating push loop");
                    break;
                  }
                  alreadyCalledPushBuffer = true;
                }
              }
              if((transcoded && tcSrc.availableToRead() < 0) || (serverSideTranscoding && mpegSrc != null &&
                  numPushedBuffers > 0 && mpegSrc.getTranscoder().isTranscodeDone()))
              { // Reached EOS
                if (serverSideTranscoding && mpegSrc != null && mpegSrc.getTranscoder().isTranscodeDone() &&
                    !((FFMPEGTranscoder) (mpegSrc.getTranscoder())).didTranscodeCompleteOK())
                {
                  if (Sage.DBG) System.out.println("Detected failure in the transcoder attempt to restart it...");
                  try
                  {
                    mpegSrc.seek(mpegSrc.getLastParsedTimeMillis());
                    continue;
                  }
                  catch (java.io.IOException ioe)
                  {
                    if (Sage.DBG) System.out.println("ERROR restarting the transcoder of:" + ioe);
                  }
                }
                if (Sage.DBG) System.out.println("Pushing EOS to decoder-1");
                if(!pushBuffer0(javaBuff, 0, 0x80 | getFlags()))
                {
                  if (Sage.DBG) System.out.println("pushBuffer call failed; terminating push loop");
                  break;
                }
                alreadyCalledPushBuffer = true;
                if(freeSpace<0)
                {
                  if (Sage.DBG) System.out.println("Received eos from client");
                  if (!eos)
                  {
                    // Reset the UI timeouts on an EOS so we don't trigger the SS since it considers EOS to be a non-playing state
                    if (uiMgr != null)
                      uiMgr.getRouter().resetInactivityTimers();
                    kickVF = true; // I think we need that only once, verify with Jeff...
                    eos = true;
                  }
                }
                try{decoderLock.wait(100);}catch(Exception e){}

                continue;
              }
              if (!alreadyCalledPushBuffer && !lowBandwidth)
              {
                // We call pushBuffer again here in case there are DVB subtitles which need more frequent updating...we'd alredy be doing this
                // if the file wasn't 'active'.
                if (!pushBuffer0(null, 0, getFlags()))
                {
                  if (Sage.DBG) System.out.println("pushBuffer call failed; terminating push loop");
                  break;
                }
              }
              try{decoderLock.wait((lowBandwidth && dynamicRateAdjust) ? 500 : 50);}catch(Exception e){}
              continue;
            }
            if (!(transcoded || timeshifted) && eos && ((rpSrc != null && rpSrc.isServerEOS() && myRate >= 1.0) ||
                (rpSrc == null && (finalLength - mpegSrc.getReadPos()) <= 0)))
            {
              if (debugPush) System.out.println("Waiting at end of stream");
              try{decoderLock.wait(100);}catch(Exception e){}
              continue;
            }

            // if we were rewinding, and we're not now, then flush the decoder to get back our buffers
            // NOTE: 7/1/05 - Always flush the decoder on rate changes or we may have
            // issues where we run out of buffers
            if ((myRate > 0 && wasReversePlay) || (lastPlayRate != myRate /*&&
                            /*(lastPlayRate == 1.0 || ((lastPlayRate > 0) != (myRate > 0)))*/))
            {
              if (debugPush) System.out.println("Flushing decoder after rate change");
              wasReversePlay = false;
              lastPlayRate = myRate;
              if (rpSrc != null)
              {
                try
                {
                  rpSrc.sendRateChange(myRate);
                  if (myRate < 1.0 && eos) {
                    // we are rewinding from EOS, set state to PLAY
                    eos = false;
                    play();
                  }
                }
                catch (java.io.IOException e)
                {
                  System.out.println("ERROR sending rate change command to remote pusher of:" + e);
                  break;
                }
                lastMediaTimeCacheTime = 0;
              }
              else if (hdMediaExtender)
              {
                // Seek us after variable speed play so that we're at the proper time in the stream
                // since there may be a bunch of stuff buffered in the decoder
                long seekTimeMillis;
                // If we're already at the beginning of the stream then don't bother seeking because
                // sometimes the timestamps from the client are messed up when we rewind to the beginning
                if ((transcoded ? tcSrc.getReadPos() : mpegSrc.getReadPos()) <= 512*1024)
                  seekTimeMillis = 0;
                else
                {
                  // For BluRay the time isn't accurate due to cell boundary issues; so use the time
                  // from the demux instead. It'll be pretty close anyways due to the high bitrate of the content
                  seekTimeMillis = bdp != null ? mpegSrc.getLastParsedTimeMillis() : getNativeMediaTimeNoSync();
                  try
                  {
                    if(transcoded)
                      tcSrc.seek(seekTimeMillis);
                    else
                      mpegSrc.seek(seekTimeMillis);
                  }
                  catch (java.io.IOException e)
                  {
                    System.out.println("ERROR seeking MPEG pusher after finishing variable speed play:" + e);
                  }
                }

                flushPush0();
                // do this to clear the flags for reverse play issues w/ BluRay
                if (bdp != null && !pushBuffer0(null, 0, getFlags()))
                {
                  if (Sage.DBG) System.out.println("pushBuffer call failed; terminating push loop");
                  break;
                }
                if (bdp != null)
                {
                  lastBluRayIndex = -1;//bdp.getCurrClipIndex();
                  //									long ptsOffset = bdp.getClipPtsOffset(lastBluRayIndex);
                  if (Sage.DBG) System.out.println("Resuming normal play for BluRay; reset the index");
                  //									NewCell0(ptsOffset);
                }
                if (serverSideTranscoding)
                {
                  timestampOffset = seekTimeMillis;
                  lastMediaTime = 0;
                  clientReportedMediaTime = 0;
                }
                else
                  lastMediaTime = seekTimeMillis;
                lastMediaTimeCacheTime = Sage.eventTime();
                // We must continue around the loop because we seeked and now may not have anything left in the buffer
                continue;
              }
              else
                flushPush0();
            }

            if (rpSrc != null)
            {
              // Check on our state
              if (!timeshifted)
              {
                if (rpSrc.isServerEOS())
                {
                  MediaFile currRecFile = null;
                  if (uiMgr != null && (currRecFile = SeekerSelector.getInstance().getCurrRecordFileForClient(uiMgr, false)) != null)
                  {
                    // Also make sure this device supports fast mux switching
                    CaptureDevice recInput = SeekerSelector.getInstance().getCaptureDeviceControlledByClient(uiMgr);
                    if (!eos && (recInput == null || recInput.supportsFastMuxSwitch()))
                    {
                      if (Sage.DBG) System.out.println("SERVER Buffer size is now ZERO! Trigger local EOS to start the seamless file switch");
                      // Reset the UI timeouts on an EOS so we don't trigger the SS since it considers EOS to be a non-playing state
                      if (uiMgr != null)
                        uiMgr.getRouter().resetInactivityTimers();
                      // We trigger this now so that we cause our transitions when watching live TV to happen early enough
                      eos = true;
                    }
                  }
                  // Check for an EOS on the client
                  if (!eos && rpSrc.isClientEOS())
                  {
                    if (Sage.DBG) System.out.println("Received eos from client");
                    // Reset the UI timeouts on an EOS so we don't trigger the SS since it considers EOS to be a non-playing state
                    if (uiMgr != null)
                      uiMgr.getRouter().resetInactivityTimers();
                    eos = true;
                    needToPlay = false;
                    pausePush0();
                    currState = PAUSE_STATE;
                  }
                  else
                    try{decoderLock.wait(100);}catch(Exception e){}
                  kickVF = true;
                }
                else
                  try{decoderLock.wait(50);}catch(Exception e){}
              }
              else
                try{decoderLock.wait(50);}catch(Exception e){}
              continue;
            }

            // This used to be prepNextDecoderBuffer0, but we inlined it because of the getFlags() call
            if (freeSpace < pushBufferSize)
            {
              if (!pushBuffer0(null, 0, getFlags()))
              {
                if (Sage.DBG) System.out.println("pushBuffer call failed; terminating push loop");
                break;
              }
            }
            int availBufferSize = freeSpace;
            // If we're doing transcoding and the client's buffer is full then wait to send more until
            // they have a complete buffer to receive from us.
            if (availBufferSize < pushBufferSize)//availBufferSize == 0 || (serverSideTranscoding && availBufferSize < pushBufferSize))
            {
              if (debugPush) System.out.println("SigmaPlayer waiting for buffer to become available from decoder...");
              try{decoderLock.wait(lowBandwidth ? 250 : 50);}catch(Exception e){}
              continue;
            }
            if (debugPush) System.out.println("buffer size avail=" + availBufferSize + " using=" + Math.min(javaBuff.capacity(), availBufferSize));

            // Wait for a bit till we start adjusting since we always start low
            if (dynamicRateAdjust && serverSideTranscoding && numPushedBuffers > 10)
            {
              // Go entirely off the estimated bitrate that we see when we push the data; that is by far the
              // most accurate measurement we can use.
              // NARFLEX - Update 10/17/08 - I did a major update to the placeshifter system. We realized on the 8635 that
              // the major performance slowdown was in waiting for the replies to the pushbuffer call. So we applied that
              // same optimization here. This however interferes majorly with bandwidth detection when the buffers are
              // almost full. So now we're using averages more since we dont rely on our instantaneous calculations as much
              // since they rarely have symmetrical roundtrip statistics in them (meaning back and forth and not just lots of sends).
              // So now we've adjusted the way the timing works so it seems like it does a pretty good job of estimating what
              // the bandwidth available is.
              FFMPEGTranscoder fftc = ((FFMPEGTranscoder)mpegSrc.getTranscoder());

              // Adjust the bandwidth buffer based on how much free space the client has. This is our secondary
              // measure for protecting against underflow.
              if (freeSpace < maxAvailBufferSize/2)
                currBandwidthBufferKbps = BANDWIDTH_BUFFER_KBPS;
              else if (freeSpace < 3*maxAvailBufferSize/4)
                currBandwidthBufferKbps = BANDWIDTH_BUFFER_KBPS*2;
              else
                currBandwidthBufferKbps = BANDWIDTH_BUFFER_KBPS*3;

              if (debugPush) System.out.println("Client Buffer size=" + (maxAvailBufferSize - freeSpace) +
                  " estimRateKbps=" + lastEstimatedPushBitrate/1000 + " avgRateKbps=" + lastAverageEstimatedPushBitrate/1000 +
                  " rateKbps=" + fftc.getCurrentStreamBitrateKbps() +
                  " estimStreamRateKbps=" + lastEstimatedStreamBitrate/1000 + " avgStreamRateKbps=" + lastAverageEstimatedStreamBitrate/1000 +
                  " bwBufferKbps=" + currBandwidthBufferKbps);

              if (fftc.getCurrentVideoBitrateKbps() > MIN_DYNAMIC_VIDEO_BITRATE_KBPS && Sage.eventTime() - lastRateAdjustTime > 500 &&
                  fftc.getCurrentStreamBitrateKbps() > lastAverageEstimatedPushBitrate/1000 - currBandwidthBufferKbps)
              {
                // If it's a larger change then use compare it against the shorter term, for smaller changes to the longer term
                // This avoids making unnecessary bitrate adjustments.
                int currAdjust = Math.max(lastEstimatedPushBitrate/1000 - currBandwidthBufferKbps - fftc.getCurrentStreamBitrateKbps(),
                    -(fftc.getCurrentVideoBitrateKbps() - MIN_DYNAMIC_VIDEO_BITRATE_KBPS));
                //								if (lastEstimatedPushBitrate > lastAverageEstimatedPushBitrate &&
                //									lastAverageEstimatedPushBitrate > fftc.getCurrentStreamBitrateKbps())//(Math.abs(currAdjust) < 20)
                {
                  int newAdjust = Math.max(lastAverageEstimatedPushBitrate/1000 - currBandwidthBufferKbps - fftc.getCurrentStreamBitrateKbps(),
                      -(fftc.getCurrentVideoBitrateKbps() - MIN_DYNAMIC_VIDEO_BITRATE_KBPS));
                  //if (Math.abs(newAdjust) < Math.abs(currAdjust))
                  {
                    currAdjust = newAdjust;
                  }
                }
                if (Math.abs(currAdjust) > 3)
                {
                  if (Sage.DBG && !debugPush) System.out.println("Client Buffer size=" + (maxAvailBufferSize - freeSpace) +
                      " estimRateKbps=" + lastEstimatedPushBitrate/1000 + " avgRateKbps=" + lastAverageEstimatedPushBitrate/1000 +
                      " rateKbps=" + fftc.getCurrentStreamBitrateKbps() +
                      " estimStreamRateKbps=" + lastEstimatedStreamBitrate/1000 + " avgStreamRateKbps=" + lastAverageEstimatedStreamBitrate/1000);
                  fftc.dynamicVideoRateAdjust(currAdjust);
                  lastRateAdjustTime = Sage.eventTime();
                  if (Sage.DBG || debugPush) System.out.println("Adjusted bitrate DOWN to : " + fftc.getCurrentStreamBitrateKbps());
                }
              }
              else if (fftc.getCurrentVideoBitrateKbps() < fftc.getDynamicMaxVideoKbps() &&
                  (lastAverageEstimatedPushBitrate/1000 > fftc.getCurrentStreamBitrateKbps() + currBandwidthBufferKbps) && Sage.eventTime() - lastRateAdjustTime > 1000)
              {
                // Trying to push the bitrate higher than the bandwidth we've detected doesn't seem wise at this point...
                // Ceiling is fftc.getDynamicMaxVideoKbps() -- LAN-aware (raised for a client on the
                // server's subnet, see FFMPEGTranscoder.setLocalClient()/getDynamicMaxVideoKbps()) instead
                // of a single hardcoded 1500 applied to every classic placeshifter client regardless of
                // actually-measured/available bandwidth (lastAverageEstimatedPushBitrate above still
                // bounds it, so a slow LAN link isn't force-fed bitrate it can't sustain).
                int currAdjust = Math.min(fftc.getDynamicMaxVideoKbps() - fftc.getCurrentVideoBitrateKbps(),
                    lastEstimatedPushBitrate/1000 - fftc.getCurrentStreamBitrateKbps() - currBandwidthBufferKbps);
                //								if (lastEstimatedPushBitrate < lastAverageEstimatedPushBitrate &&
                //									lastAverageEstimatedPushBitrate < fftc.getCurrentStreamBitrateKbps())//(Math.abs(currAdjust) < 20)
                {
                  int newAdjust = Math.min(fftc.getDynamicMaxVideoKbps() - fftc.getCurrentVideoBitrateKbps(),
                      lastAverageEstimatedPushBitrate/1000 - fftc.getCurrentStreamBitrateKbps() - currBandwidthBufferKbps);
                  //									if (Math.abs(newAdjust) < Math.abs(currAdjust))
                  {
                    currAdjust = newAdjust;
                  }
                }
                if (currAdjust > 200)
                  currAdjust = 200;
                if (Math.abs(currAdjust) > 10)
                {
                  if (Sage.DBG && !debugPush) System.out.println("Client Buffer size=" + (maxAvailBufferSize - freeSpace) +
                      " estimRateKbps=" + lastEstimatedPushBitrate/1000 + " avgRateKbps=" + lastAverageEstimatedPushBitrate/1000 +
                      " rateKbps=" + fftc.getCurrentStreamBitrateKbps() +
                      " estimStreamRateKbps=" + lastEstimatedStreamBitrate/1000 + " avgStreamRateKbps=" + lastAverageEstimatedStreamBitrate/1000);
                  fftc.dynamicVideoRateAdjust(currAdjust);
                  lastRateAdjustTime = Sage.eventTime();
                  if (Sage.DBG || debugPush) System.out.println("Adjusted bitrate UP to : " + fftc.getCurrentStreamBitrateKbps());
                }
              }
            }

            availBufferSize = Math.min(javaBuff.capacity(), availBufferSize);

            if (bdp != null)
            {
              if (!mpegSrc.canSkipOnNextRead() && bdp.getBytesLeftInClip() > 0 && bdp.getBytesLeftInClip() < availBufferSize)
              {
                availBufferSize = (int)bdp.getBytesLeftInClip();
                if (Sage.DBG) System.out.println("At the end of a BluRay clip; adjust the read buffer size for the next push to be " + availBufferSize);
              }
            }

            int readBufferSize = availBufferSize;
            if (!(transcoded || timeshifted))
            {
              readBufferSize = (int)Math.min(readBufferSize, mpegSrc.availableToRead2(readBufferSize));
              //finalLength - mpegSrc.getReadPos());
              if (readBufferSize <= 0)
              {
                MediaFile currRecFile = null;
                if (uiMgr != null && (currRecFile = SeekerSelector.getInstance().getCurrRecordFileForClient(uiMgr, false)) != null)
                {
                  // Also make sure this device supports fast mux switching
                  CaptureDevice recInput = SeekerSelector.getInstance().getCaptureDeviceControlledByClient(uiMgr);
                  if (!eos && (recInput == null || recInput.supportsFastMuxSwitch()))
                  {
                    if (Sage.DBG) System.out.println("SERVER Buffer size is now ZERO! Trigger local EOS to start the seamless file switch");
                    // Reset the UI timeouts on an EOS so we don't trigger the SS since it considers EOS to be a non-playing state
                    if (uiMgr != null)
                      uiMgr.getRouter().resetInactivityTimers();
                    // We trigger this now so that we cause our transitions when watching live TV to happen early enough
                    eos = true;
                  }
                }
                if (!eos)
                {
                  boolean sendServerEOS = false;
                  if (serverSideTranscoding && mpegSrc != null)
                  {
                    if (mpegSrc.getTranscoder().isTranscodeDone())
                    {
                      if (!((FFMPEGTranscoder) (mpegSrc.getTranscoder())).didTranscodeCompleteOK())
                      {
                        if (Sage.DBG) System.out.println("Detected failure in the transcoder attempt to restart it...");
                        try
                        {
                          mpegSrc.seek(mpegSrc.getLastParsedTimeMillis());
                        }
                        catch (java.io.IOException ioe)
                        {
                          if (Sage.DBG) System.out.println("ERROR restarting the transcoder of:" + ioe);
                          sendServerEOS = true;
                        }
                      }
                      else
                      {
                        if (debugPush) System.out.println("Server is pushing an EOS message to the client");
                        sendServerEOS = true;
                      }
                    }
                  }
                  // Check for an EOS on the client
                  if(!serverSideTranscoding || sendServerEOS)
                  {
                    if (!pushBuffer0(javaBuff, 0, 0x80 | getFlags()))
                    {
                      if (Sage.DBG) System.out.println("pushBuffer call failed; terminating push loop");
                      break;
                    }
                    if (freeSpace<0)
                    {
                      if (Sage.DBG) System.out.println("Received eos from client");
                      if(!eos)
                      {
                        // Reset the UI timeouts on an EOS so we don't trigger the SS since it considers EOS to be a non-playing state
                        if (uiMgr != null)
                          uiMgr.getRouter().resetInactivityTimers();
                        kickVF = true; // I think we need that only once, verify with Jeff...
                        eos = true;
                      }
                      needToPlay = false;
                      pausePush0();
                      currState = PAUSE_STATE;
                    }
                    else
                      try{decoderLock.wait(100);}catch(Exception e){}
                  }
                  else
                    try{decoderLock.wait(100);}catch(Exception e){}
                  //                                    if (Sage.DBG) System.out.println("Pushing EOS to decoder-2");
                  //                                  pushBuffer0(javaBuff, 0, 0x80);
                  //                                eos = true;
                }
                else
                  try{decoderLock.wait(100);}catch(Exception e){}
                kickVF = true;
                continue;
              }
            }
            if(transcoded)
              tcSrc.setPlaybackRate((myRate > 1) ? (int)Math.floor(myRate) :
                ((myRate < 0) ? (int)Math.floor(myRate) : 1));
            else
              mpegSrc.setPlaybackRate((myRate > 1) ? (int)Math.floor(myRate) :
                ((myRate < 0) ? (int)Math.floor(myRate) : 1));
            if(transcoded)
            {
              readBufferSize = (int)Math.min(readBufferSize,
                  tcSrc.availableToRead());
            }
            if (debugPush) System.out.println("About to read buffer of size: " + readBufferSize);
            javaBuff.clear();
            if(transcoded)
            {
              try
              {
                tcSrc.read(javaBuff.array(), 0, readBufferSize);
                javaBuff.position(0).limit(readBufferSize);
              }
              catch (java.io.IOException e)
              {
                System.out.println("I/O error reading in push thread:" + e);
                e.printStackTrace();
              }
            }
            // Since we're doing an NIO transfer we want the NEXT read's clip index, not what we just read
            // But this only applies if we won't be doing a re-seek which could potentially change that
            if (bdp != null && !mpegSrc.canSkipOnNextRead() && lastBluRayIndex != bdp.getClipIndexForNextRead())
            {
              lastBluRayIndex = bdp.getClipIndexForNextRead();
              long ptsOffset = bdp.getClipPtsOffset(lastBluRayIndex);
              if (Sage.DBG) System.out.println("Detected cell boundary for BluRay; send the NewCell command with PTSOffset=" + ptsOffset);
              NewCell0(ptsOffset);
            }
            if (debugPush) System.out.println("about to push buffer");
            int flags = getFlags();
            if (!pushBuffer0(javaBuff, readBufferSize, flags))
            {
              if (Sage.DBG) System.out.println("pushBuffer call failed; terminating push loop");
              break;
            }
            /*						if (pushDumpStream != null)
						{
							try
							{
								pushDumpStream.write(javaBuff, 0, readBufferSize);
							}catch (Exception e)
							{
								System.out.println("ERROR writing push buffer dump stream: " + e);
							}
						}*/
            numPushedBuffers++;
            if (debugPush) System.out.println("buffer was pushed x=" + numPushedBuffers +
                " flag=" + flags + " len="+readBufferSize);
            //if (numPushedBuffers >= 1 && needToPlay)/*DSM520TEMP*/
            if (numPushedBuffers >= 1 && needToPlay)
            {
              playPush0();
              needToPlay = false;
            }
            if (bufferFillPause && videoPTSForPlay != -1)
            {
              //System.out.println("Checking PTS for resuming playback after buffer fill with pts=" + mpegSrc.getLastRawVideoPTS());
              if (mpegSrc.getLastRawVideoPTS() > videoPTSForPlay || freeSpace <= pushBufferSize)
              {
                //System.out.println("Resuming playback after buffer fill with pts=" + mpegSrc.getLastRawVideoPTS());
                //seekPull0(videoPTSForPlay - 300000);
                playPush0();
                bufferFillPause = false;
              }
            }
            if((numPushedBuffers&0x1F)==0/* && (((int)Math.floor(myRate))!=1 || serverSideTranscoding)*/)
            {
              try{
                decoderLock.notifyAll();
                decoderLock.wait(10);}catch(Exception e){}
              // --- NG Context wiring: periodic live-window update ---
              ngContextWiring.onPushLoopTick(
                  lastParserTimestamp - timestampOffset,
                  finalLength,
                  timeshifted);
              // --- end NG Context wiring ---
            }
          }
        }
        if (pushDumpStream != null)
        {
          try
          {
            pushDumpStream.close();
          }
          catch (Exception e){}
          pushDumpStream = null;
        }
      }

      private int getFlags()
      {
        int flags = 0;
        if (firstPush)
        {
          // Stop discarding PTS and stop trick mode
          flags = 0x12;
          firstPush = false;
        }
        else
        {
          if (myRate > 1.0f || myRate < 0)
          {
            // Discard PB Frames & PTS's
            int irate=(int)(myRate*32.0f);
            irate&=0x7FFF; // we support 10.5 format
            flags = 0x09 | (irate<<16);
            sentDiscardPtsFlag = true;
            sentTrickmodeFlag = true;
            if (myRate < 0)
              wasReversePlay = true;
          }
          else
          {
            if (sentDiscardPtsFlag)
            {
              sentDiscardPtsFlag = false;
              flags |= 0x10;
            }
            if (sentTrickmodeFlag)
            {
              sentTrickmodeFlag = false;
              flags |= 0x02;
            }
          }
        }
        return flags;
      }
      private boolean firstPush = true;
      private boolean wasReversePlay = false;
    }, "Pusher");
    pushThread.setDaemon(true);
    pushThread.setPriority(Thread.MAX_PRIORITY - Sage.getInt("push_thread_priority_offset", 2));
    pushThread.start();
  }

  private void addYieldDecoderLock()
  {
    synchronized (yieldDecoderLockCountLock)
    {
      yieldDecoderLockCount++;
    }
  }

  private void removeYieldDecoderLock()
  {
    synchronized (yieldDecoderLockCountLock)
    {
      yieldDecoderLockCount--;
    }
  }

  private boolean shouldYieldDecoderLock()
  {
    synchronized (yieldDecoderLockCountLock)
    {
      return yieldDecoderLockCount > 0;
    }
  }

  public void kickPusherThread()
  {
    Pooler.execute(new Runnable() {
      public void run() {
        synchronized (decoderLock) {
          decoderLock.notifyAll();
        }
      }
    });
  }

  public boolean pause()
  {
    if (currState == LOADED_STATE || currState == PLAY_STATE)
    {
      synchronized (this)
      {
        addYieldDecoderLock();
        synchronized (decoderLock)
        {
          needToPlay = false;
          bufferFillPause = false;
          pausePush0();
          currState = PAUSE_STATE;
          removeYieldDecoderLock();
          decoderLock.notifyAll();
        }
      }
    }
    return currState == PAUSE_STATE;
  }

  public boolean play()
  {
    if ((currState == LOADED_STATE || currState == PAUSE_STATE) && !eos)
    {
      synchronized (this)
      {
        addYieldDecoderLock();
        synchronized (decoderLock)
        {
          // Delay the play if we're pushing in a high bandwidth environment
          // so the decoder can get some data in it and avoid any init issues
          if (pushMode && numPushedBuffers < 8 && !serverSideTranscoding && rpSrc == null)
            needToPlay = true;
          else
            playPush0();
          bufferFillPause = false;
          currState = PLAY_STATE;
          removeYieldDecoderLock();
          decoderLock.notifyAll();
        }
      }
    }
    return currState == PLAY_STATE;
  }

  public long seek(long seekTimeMillis) throws PlaybackException
  {
    if (currState == PLAY_STATE || currState == PAUSE_STATE || currState == LOADED_STATE)
    {
      maybeCheckpointSessionBandwidthOnSeek(seekTimeMillis);
      synchronized (this)
      {
        timeGuessMillis = seekTimeMillis;
        guessTimestamp = Sage.eventTime();
        eos = false;
        try
        {
          waitingForSeek = true;
          addYieldDecoderLock();
          synchronized (decoderLock)
          {
            if (pushMode)
            {
              if (Sage.DBG) System.out.println("seeking numpushbuffers=" + numPushedBuffers + " seekTime=" + seekTimeMillis);

              if (serverSideTranscoding && detailedPushBufferStats && seekTimeMillis > clientReportedMediaTime + timestampOffset &&
                  seekTimeMillis < lastParserTimestamp)
              {
                if (Sage.DBG) System.out.println("Seeking within the push buffer limit crmt=" + clientReportedMediaTime + " to=" + timestampOffset + " lpt=" + lastParserTimestamp + " seek=" + seekTimeMillis);
                seekPull0(seekTimeMillis - timestampOffset);
                lastMediaTime = seekTimeMillis - timestampOffset;
              }
              else if (rpSrc != null)
              {
                rpSrc.sendSeek(seekTimeMillis);
                lastMediaTime = seekTimeMillis;
                lastMediaTimeCacheTime = Sage.eventTime();
                decoderLock.notifyAll();
              }
              else
              {
                if (byteBasedSeeking) {
                  long target = Math.round((((double) seekTimeMillis) / 
                      VideoFrame.getMediaFileForPlayer(this).getDuration(currFile)) * mpegSrc.length());
                  mpegSrc.seekToPosition(target);
                } else if(transcoded) {
                  tcSrc.seek(seekTimeMillis);
                } else {
                  mpegSrc.seek(seekTimeMillis);
                }

                if (currState == PAUSE_STATE && (mcsr != null && mcsr.supportsFrameStep()))
                {
                  sendSeekPullNext = true;
                }
                else if (enableBufferFillPause && currState == PLAY_STATE && !transcoded && mpegSrc.isIFrameAlignEnabled()) // disable for now
                {
                  pausePush0();
                  bufferFillPause = true;
                  videoPTSForPlay = -1;
                  //System.out.println("Paused stream and setting flag for playing after PTS increase");
                }
                flushPush0();
                justSeeked = true;
                // NOTE: This is to workaround the 'not enough space in demux' issue on the 8654 that can happen if we seek too fast. When we added this
                // to try to debug it more, the problem went away...so we'll just leave it in there for now.
                if (hdMediaExtender)
                  freeSpace = 0;

                decoderLock.notifyAll();
                lastBluRayIndex = -1; // to force a newcell for BluRay
                if (serverSideTranscoding)
                {
                  timestampOffset = seekTimeMillis;
                  lastMediaTime = 0;
                  clientReportedMediaTime = 0;
                }
                else
                  lastMediaTime = seekTimeMillis;
                lastMediaTimeCacheTime = Sage.eventTime();
              }
            }
            else
            {
              // Skip the initial seek to zero since we started out there already
              if (!firstSeek || seekTimeMillis > 0)
                seekPull0(seekTimeMillis);
            }
            removeYieldDecoderLock();
            decoderLock.notifyAll();
          }
        }
        catch (java.io.IOException e)
        {
          System.out.println("I/O error seeking:" + e);
          throw new PlaybackException(PlaybackException.FILESYSTEM, 0);
        }
        finally
        {
          waitingForSeek = false;
          // --- NG Context wiring: notify seek ---
          ngContextWiring.onSeek(seekTimeMillis);
          // --- end NG Context wiring ---

        }
      }
      if (pushMode && !pushThreadCreated)
      {
        /*			if (serverSideTranscoding && mpegSrc != null && mpegSrc.getTranscoder() != null && !mpegSrc.getTranscoder().isTranscoding())
				{
					try
					{
						mpegSrc.getTranscoder().startTranscode();
					}
					catch (java.io.IOException e)
					{
						System.out.println("ERROR starting transcode engine!:" + e);
						throw new PlaybackException();
					}
				}*/
        pushThreadCreated = true;
        createPushThread();
      }
      firstSeek = false;
      return seekTimeMillis;
    }
    return 0;
  }

  private void maybeCheckpointSessionBandwidthOnSeek(long seekTimeMillis)
  {
    // Treat large seeks as natural session boundaries and persist the latest
    // learned transcode bandwidth so the next play starts from fresher data.
    long majorSeekDeltaMs = Sage.getLong("miniplayer/session_bw_checkpoint_seek_ms", 30000L);
    if (majorSeekDeltaMs <= 0)
      return;
    long currentMs = getMediaTimeMillis();
    if (Math.abs(seekTimeMillis - currentMs) < majorSeekDeltaMs)
      return;
    persistSessionBandwidthFromTranscoder();
  }

  public boolean setClosedCaptioningState(int ccState)
  {
    return false;
  }

  public void setMute(boolean x)
  {
    if (currMute != x)
    {
      synchronized (this)
      {
        addYieldDecoderLock();
        synchronized (decoderLock)
        {
          setMute0(currMute = x);
          removeYieldDecoderLock();
          decoderLock.notifyAll();
        }
      }
    }
  }

  public float setPlaybackRate(float newRate)
  {
    if (Sage.DBG) System.out.println("MiniPlayer.setPlaybackRate(" + newRate + ")");
    // Don't allow modified playback rates if we're using the transcoder!
    // NOTE: Disable smooth FF/REW with the remuxer for now it needs more work!!!
    if (pushMode && (!serverSideTranscoding /*|| usingRemuxer*/))
    {
      // The time may be wrong for a little bit after this so establish our guess
      timeGuessMillis = getMediaTimeMillis();
      guessTimestamp = Sage.eventTime();
      // The push thread should pick this up.
      addYieldDecoderLock();
      synchronized (decoderLock)
      {
        myRate = newRate;
        removeYieldDecoderLock();
        decoderLock.notifyAll();
      }
      return myRate;
    }
    else
    {
      MediaFile currMF = VideoFrame.getMediaFileForPlayer(MiniPlayer.this);
      // Do a skip instead so we actually do something with this command instead of not responding to it
      VideoFrame vf = VideoFrame.getVideoFrameForPlayer(MiniPlayer.this);
      long maxTime = (vf != null && currMF != null) ? currMF.getDuration(vf.getCurrSegment()) : Long.MAX_VALUE;
      try
      {
        if (newRate > 1.0f)
        {
          seek(Math.min(maxTime, Math.max(0, getMediaTimeMillis() + (uiMgr == null ? 15000L : uiMgr.getLong("videoframe/ff_time", 10000L)))));
        }
        else if (newRate < 1.0f)
        {
          seek(Math.min(maxTime, Math.max(0, getMediaTimeMillis() + (uiMgr == null ? -15000L : uiMgr.getLong("videoframe/rew_time", -10000L)))));
        }
      }
      catch (PlaybackException e)
      {
        System.out.println("ERROR doing seek instead of rate change of: " + e);
      }
      return 1.0f;
    }
  }

  public synchronized void setVideoRectangles(java.awt.Rectangle videoSrcRect,
      java.awt.Rectangle videoDestRect, boolean hideCursor)
  {
    if(!disableVideoPositioning && clientInStream != null && currHintMajorType != MediaFile.MEDIATYPE_AUDIO)
    {
      if (lastVideoSrcRect == null || lastVideoDestRect == null || !videoSrcRect.equals(lastVideoSrcRect) ||
          !videoDestRect.equals(lastVideoDestRect))
      {
        boolean tookIt;
        addYieldDecoderLock();
        synchronized (decoderLock)
        {
          tookIt = setVideoRectangles0(videoSrcRect, videoDestRect);
          removeYieldDecoderLock();
          decoderLock.notifyAll();
        }
        if (tookIt)
        {
          lastVideoSrcRect = (java.awt.Rectangle) videoSrcRect.clone();
          lastVideoDestRect = (java.awt.Rectangle) videoDestRect.clone();
        }
        else
        {
          lastVideoSrcRect = null;
          lastVideoDestRect = null;
        }
      }
    }
  }

  public synchronized float setVolume(float f)
  {
    addYieldDecoderLock();
    synchronized (decoderLock)
    {
      if(f>1.0f) f=1.0f;
      if(f<0.0f) f=0.0f;
      setVolume0(f);
      curVolume=f;
      removeYieldDecoderLock();
      decoderLock.notifyAll();
    }
    return f;
  }

  public void stop()
  {
    persistSessionBandwidthFromTranscoder();
    if (currState == PLAY_STATE || currState == PAUSE_STATE)
    {
      synchronized (this)
      {
        currState = STOPPED_STATE;
        addYieldDecoderLock();
        synchronized (decoderLock)
        {
          stopPush0();
          removeYieldDecoderLock();
          decoderLock.notifyAll();
        }
      }
    }
    else
      currState = STOPPED_STATE;
  }

  public boolean playControlEx(int playCode, long param1, long param2) throws PlaybackException
  {
    if (hdMediaExtender && (currState == PLAY_STATE || currState == PAUSE_STATE || currState == LOADED_STATE))
    {
      if (audioTracks != null && playCode == VideoFrame.DVD_CONTROL_AUDIO_CHANGE)
      {
        int newLanguageIndex = param1 >= 0 ? (int)param1 : ((languageIndex + 1) % audioTracks.length);
        if (newLanguageIndex != languageIndex)
        {
          languageIndex = Math.max(0, Math.min(audioTracks.length - 1, newLanguageIndex));
          synchronized (this)
          {
            sage.media.format.AudioFormat af = audioTracks[languageIndex];
            int audioStreamType = 0xc000;
            int ac3indexOffset = (af != null && usingRemuxer) ? af.getOrderIndex() : 0;
            // If we're transcoding then the original audio stream doesn't matter, just use 0xc0
            // unless we're using the remuxer....
            String streamID = af.getId();
            if (streamID != null && streamID.length() > 0)
            {
              // See if it's just a stream ID or if it's 2 parts
              int dashIdx = streamID.indexOf('-');
              if (dashIdx == -1)
              {
                try
                {
                  if (streamID.length() == 4) // the full ID
                    audioStreamType = Integer.parseInt(streamID, 16);
                  else
                    audioStreamType = (Integer.parseInt(streamID, 16) << 8);
                }
                catch (NumberFormatException nfe)
                {
                  if (Sage.DBG) System.out.println("ERROR parsing audio stream ID " + streamID + " of: " + nfe);
                }
              }
              else
              {
                try
                {
                  audioStreamType = (Integer.parseInt(streamID.substring(0, dashIdx), 16) << 8) |
                      Integer.parseInt(streamID.substring(dashIdx + 1, dashIdx + 3), 16);
                }
                catch (NumberFormatException nfe)
                {
                  if (Sage.DBG) System.out.println("ERROR parsing audio stream ID " + streamID + " of: " + nfe);
                }
              }
            }
            if (Sage.DBG) System.out.println("Setting audio stream for playback to be ID=0x" + Integer.toString(audioStreamType, 16));
            long pftime = getMediaTimeMillis();
            DVDStream(0, pushMode ? audioStreamType : languageIndex);
            matchBDSubpictureToAudio();
            seek(pftime);
          }
        }
      }
      else if (subpicTracks != null && playCode == VideoFrame.DVD_CONTROL_SUBTITLE_TOGGLE)
      {
        subpicOn = !subpicOn;
        synchronized (this)
        {
          long pftime = getMediaTimeMillis();
          String tag = subpicTracks[subpicIndex].getId();
          int target = subpicIndex;
          // See if it's just a stream ID or if it's 2 parts
          if (tag != null && tag.length() > 0)
          {
            int dashIdx = tag.indexOf('-');
            if (dashIdx == -1)
            {
              try
              {
                if (tag.length() == 4) // the full ID
                  target = Integer.parseInt(tag, 16);
                else
                  target = (Integer.parseInt(tag, 16) << 8);
              }
              catch (NumberFormatException nfe)
              {
                if (Sage.DBG) System.out.println("ERROR parsing subpic stream ID " + tag + " of: " + nfe);
              }
            }
            else
            {
              try
              {
                target = (Integer.parseInt(tag.substring(0, dashIdx), 16) << 8) |
                    Integer.parseInt(tag.substring(dashIdx + 1, dashIdx + 3), 16);
              }
              catch (NumberFormatException nfe)
              {
                if (Sage.DBG) System.out.println("ERROR parsing subpic stream ID " + tag + " of: " + nfe);
              }
            }
          }
          if (Sage.DBG) System.out.println((subpicOn ? "Enabling " : "Disabling ") + "subpicture stream " + target);
          if (!subpicOn)
          {
            if (!matchBDSubpictureToAudio())
              DVDStream(1, isMpeg2PS ? (subpicOn ? target : PS_SUBPIC_DISABLE_STREAM) : ((target & 0x1FFF) | (subpicOn ? 0 : SUBPIC_DISABLE_STREAM)));
          }
          else
          {
            DVDStream(1, isMpeg2PS ? (subpicOn ? target : PS_SUBPIC_DISABLE_STREAM) : ((target & 0x1FFF) | (subpicOn ? 0 : SUBPIC_DISABLE_STREAM)));
          }
          seek(pftime);
        }
      }
      else if (subpicTracks != null && playCode == VideoFrame.DVD_CONTROL_SUBTITLE_CHANGE)
      {
        int newSubpicIndex = param1 >= 0 ? (int)param1 : ((subpicIndex + 1) % subpicTracks.length);
        if (newSubpicIndex != subpicIndex || !subpicOn)
        {
          subpicIndex = Math.max(0, Math.min(subpicTracks.length - 1, newSubpicIndex));
          if (!subpicOn)
            subpicOn = true;
          String tag = subpicTracks[subpicIndex].getId();
          int target = subpicIndex;
          // See if it's just a stream ID or if it's 2 parts
          if (tag != null && tag.length() > 0)
          {
            int dashIdx = tag.indexOf('-');
            if (dashIdx == -1)
            {
              try
              {
                if (tag.length() == 4) // the full ID
                  target = Integer.parseInt(tag, 16);
                else
                  target = (Integer.parseInt(tag, 16) << 8);
              }
              catch (NumberFormatException nfe)
              {
                if (Sage.DBG) System.out.println("ERROR parsing subpic stream ID " + tag + " of: " + nfe);
              }
            }
            else
            {
              try
              {
                target = (Integer.parseInt(tag.substring(0, dashIdx), 16) << 8) |
                    Integer.parseInt(tag.substring(dashIdx + 1, dashIdx + 3), 16);
              }
              catch (NumberFormatException nfe)
              {
                if (Sage.DBG) System.out.println("ERROR parsing subpic stream ID " + tag + " of: " + nfe);
              }
            }
          }
          synchronized (this)
          {
            long pftime = getMediaTimeMillis();
            if (Sage.DBG) System.out.println("Enabling subpicture stream " + target);
            DVDStream(1, isMpeg2PS ? target : (target & 0x1FFF));
            seek(pftime);
          }
        }
      }
      else if (bdp != null && playCode == VideoFrame.DVD_CONTROL_CHAPTER_NEXT)
      {
        if (getDVDChapter() < getDVDTotalChapters())
        {
          long newTime = bdp.getChapterStartMsec(getDVDChapter() + 1);
          if (Sage.DBG) System.out.println("Next chapter for BluRay seeking to " + newTime);
          seek(newTime);
        }
      }
      else if (bdp != null && playCode == VideoFrame.DVD_CONTROL_CHAPTER_PREV)
      {
        int currChapter = getDVDChapter();
        if (getMediaTimeMillis() - bdp.getChapterStartMsec(currChapter) > 7000 || currChapter == 1)
        {
          if (Sage.DBG) System.out.println("Prev chapter (restart curr chapter) for BluRay");
          seek(bdp.getChapterStartMsec(currChapter));
        }
        else
        {
          long newTime = bdp.getChapterStartMsec(currChapter - 1);
          if (Sage.DBG) System.out.println("Prev chapter for BluRay seeking to " + newTime);
          seek(newTime);
        }
      }
      else if (bdp != null && playCode == VideoFrame.DVD_CONTROL_CHAPTER_SET)
      {
        long newTime = bdp.getChapterStartMsec((int)param1);
        if (Sage.DBG) System.out.println("Set chapter (" + param1 + ") for BluRay seeking to " + newTime);
        seek(newTime);
      }
      else if (bdp != null && playCode == VideoFrame.DVD_CONTROL_ANGLE_CHANGE)
      {
        /*if (getDVDTotalAngles() > 1)
				{
					currBDAngle++;
					if (currBDAngle > getDVDTotalAngles())
						currBDAngle = 1;
					if (Sage.DBG) System.out.println("Setting BluRay Angle to be " + currBDAngle);
					// Lock the pusher so we can change the file source
					synchronized (this)
					{
						addYieldDecoderLock();
						synchronized (decoderLock)
						{
							bdp.setAngle(currBDAngle);
							seek(getMediaTimeMillis());
						}
					}
				}*/
      }
      else if (bdp != null && playCode == VideoFrame.DVD_CONTROL_TITLE_SET && uiMgr != null)
      {
        if (param1 > 0 && param1 <= bdp.getNumTitles())
          uiMgr.getVideoFrame().setBluRayTargetTitle((int)param1);
        else
          uiMgr.getVideoFrame().playbackControl(0);
      }
    }
    return false;
  }

  private void persistSessionBandwidthFromTranscoder()
  {
    if (mcsr == null || mpegSrc == null || !serverSideTranscoding)
      return;
    TranscodeEngine te = mpegSrc.getTranscoder();
    if (!(te instanceof FFMPEGTranscoder))
      return;
    long bps = ((FFMPEGTranscoder) te).getEstimatedBandwidth();
    if (bps <= 0)
      return;
    long now = Sage.eventTime();
    if (lastSessionBandwidthPersistBps == bps && (now - lastSessionBandwidthPersistTime) < 5000)
      return;

    // Normalize to a 1s sample and feed the renderer's existing estimator so
    // the next playback decision starts from the latest in-session adaptation.
    mcsr.addDataToBandwidthCalc(Math.max(1L, bps / 8L), 1000L);
    lastSessionBandwidthPersistBps = bps;
    lastSessionBandwidthPersistTime = now;
    if (Sage.DBG)
      System.out.println("MiniPlayer persisted session bandwidth sample=" + (bps / 1000L) + "Kbps");
  }

  private boolean matchBDSubpictureToAudio()
  {
    if (bdp != null && audioTracks != null && !subpicOn)
    {
      // Also init the corresponding subpicture stream for forced subtitles
      String currLang = audioTracks[Math.max(0, Math.min(languageIndex, audioTracks.length - 1))].getLanguage();
      if (subpicTracks != null && currLang != null)
      {
        for (int i = 0; i < subpicTracks.length; i++)
        {
          if (currLang.equals(subpicTracks[i].getLanguage()))
          {
            String tag = subpicTracks[i].getId();
            int target = i;
            // See if it's just a stream ID or if it's 2 parts
            if (tag != null && tag.length() > 0)
            {
              int dashIdx = tag.indexOf('-');
              if (dashIdx == -1)
              {
                try
                {
                  if (tag.length() == 4) // the full ID
                    target = Integer.parseInt(tag, 16);
                  else
                    target = (Integer.parseInt(tag, 16) << 8);
                }
                catch (NumberFormatException nfe)
                {
                  if (Sage.DBG) System.out.println("ERROR parsing subpic stream ID " + tag + " of: " + nfe);
                }
              }
              else
              {
                try
                {
                  target = (Integer.parseInt(tag.substring(0, dashIdx), 16) << 8) |
                      Integer.parseInt(tag.substring(dashIdx + 1, dashIdx + 3), 16);
                }
                catch (NumberFormatException nfe)
                {
                  if (Sage.DBG) System.out.println("ERROR parsing subpic stream ID " + tag + " of: " + nfe);
                }
              }
            }
            if (Sage.DBG) System.out.println("Setting BD subpicture to match audio track for forced subs subID=" + tag);
            synchronized (this)
            {
              DVDStream(1, isMpeg2PS ? (subpicOn ? target : PS_SUBPIC_DISABLE_STREAM) : ((target & 0x1FFF) | SUBPIC_DISABLE_STREAM));
            }
            return true;
          }
        }
      }
    }
    return false;
  }

  public boolean areDVDButtonsVisible()
  {
    return false;
  }

  public int getDVDAngle()
  {
    if (bdp != null)
      return Math.min(currBDAngle, getDVDTotalAngles());
    return 0;
  }

  public String[] getDVDAvailableLanguages()
  {
    if (hdMediaExtender && audioTracks != null && (currState == PLAY_STATE || currState == PAUSE_STATE))
    {
      MediaFile mf = VideoFrame.getMediaFileForPlayer(this);
      if (mf != null)
      {
        sage.media.format.ContainerFormat cf = (bdp != null) ? bdp.getFileFormat() : mf.getFileFormat();
        if (cf != null && cf.getNumAudioStreams() > 1)
        {
          if (audioSels == null)
            audioSels = cf.getAudioStreamSelectionDescriptors();
          return audioSels;
        }
      }
    }
    return Pooler.EMPTY_STRING_ARRAY;
  }

  public String[] getDVDAvailableSubpictures()
  {
    if (hdMediaExtender && subpicTracks != null && (currState == PLAY_STATE || currState == PAUSE_STATE))
    {
      MediaFile mf = VideoFrame.getMediaFileForPlayer(this);
      if (mf != null)
      {
        sage.media.format.ContainerFormat cf = (bdp != null) ? bdp.getFileFormat() : mf.getFileFormat();
        if (cf != null && cf.getNumSubpictureStreams() > 0)
        {
          if (subpicSels == null)
            subpicSels = cf.getSubpictureStreamSelectionDescriptors();
          return subpicSels;
        }
      }
    }
    return Pooler.EMPTY_STRING_ARRAY;
  }

  public int getDVDChapter()
  {
    if (bdp != null)
      return bdp.getChapter(mpegSrc.getLastParsedTimeMillis() * 45);
    return 0;
  }

  public int getDVDTotalChapters()
  {
    if (bdp != null)
      return bdp.getNumChapters();
    return 0;
  }

  public int getDVDDomain()
  {
    if (bdp != null)
      return 4; // We're always in the movie for BluRays
    return 0;
  }

  public sage.media.format.AudioFormat getCurrAudioFormat()
  {
    if (audioTracks != null && audioTracks.length > 0)
    {
      return audioTracks[Math.min(audioTracks.length - 1, Math.max(0, languageIndex))];
    }
    else
      return null;
  }

  public String getDVDLanguage()
  {
    if (hdMediaExtender && audioTracks != null && (currState == PLAY_STATE || currState == PAUSE_STATE))
    {
      MediaFile mf = VideoFrame.getMediaFileForPlayer(this);
      if (mf != null)
      {
        sage.media.format.ContainerFormat cf = (bdp != null) ? bdp.getFileFormat() : mf.getFileFormat();
        if (cf != null && cf.getNumAudioStreams() > 0)
        {
          if (audioSels == null)
            audioSels = cf.getAudioStreamSelectionDescriptors();
          return audioSels[Math.min(audioSels.length - 1, Math.max(0, languageIndex))];
        }
      }
    }
    return "";
  }

  public String getDVDSubpicture()
  {
    if (hdMediaExtender && subpicTracks != null && (currState == PLAY_STATE || currState == PAUSE_STATE))
    {
      if (!subpicOn) return null;
      MediaFile mf = VideoFrame.getMediaFileForPlayer(this);
      if (mf != null)
      {
        sage.media.format.ContainerFormat cf = (bdp != null) ? bdp.getFileFormat() : mf.getFileFormat();
        if (cf != null && cf.getNumSubpictureStreams() > 0)
        {
          if (subpicSels == null)
            subpicSels = cf.getSubpictureStreamSelectionDescriptors();
          return subpicSels[Math.min(subpicSels.length - 1, Math.max(0, subpicIndex))];
        }
      }
    }
    return "";
  }

  public int getDVDTitle()
  {
    if (bdp != null)
      return currBDTitle;
    return 0;
  }

  public String getBluRayTitleDesc(int titleNum)
  {
    if (bdp != null)
      return bdp.getTitleDesc(titleNum);
    else
      return "";
  }

  public int getDVDTotalAngles()
  {
    if (bdp != null)
      return bdp.getNumAngles();
    return 0;
  }

  public int getDVDTotalTitles()
  {
    if (bdp != null)
      return bdp.getNumTitles();
    return 0;
  }

  public float getCurrentAspectRatio()
  {
    return 0;
  }

  public void sendSubpicPalette(byte[] palette)
  {
    if (palette == null || palette.length != 64)
      throw new IllegalArgumentException("Invalid subpicture palette passed to miniplayer!");
    synchronized (this)
    {
      addYieldDecoderLock();
      synchronized (decoderLock)
      {
        CLUT0(64, palette);
        removeYieldDecoderLock();
        decoderLock.notifyAll();
      }
    }
  }

  public void sendSubpicBitmap(byte[] data, int size, int extraFlags)
  {
    if (data == null || size == 0)
      return;
    synchronized (this)
    {
      addYieldDecoderLock();
      synchronized (decoderLock)
      {
        int offset = 0;
        while (size > 0)
        {
          // Subpic buffers are limited to 32k
          int currSend = Math.min(32768, size);
          pushBuffer0(java.nio.ByteBuffer.wrap(data, offset, currSend), currSend, PUSHBUFFER_SUBPIC_FLAG | extraFlags);
          size -= currSend;
          offset += currSend;
        }
        removeYieldDecoderLock();
        decoderLock.notifyAll();
      }
    }
  }

  protected long initDriver0(int videoFormat)
  {
    if (Sage.DBG) System.out.println("initDriver0()");
    clientSocket = (mcsr == null) ? MiniClientSageRenderer.getPlayerSocketChannel(null, null) :
      mcsr.getPlayerSocketChannel();

    String clientName = (uiMgr == null ? "EXTERNAL" : uiMgr.getLocalUIClientName());
    if (clientSocket == null) return 0;
    boolean retry = true;
    while (true)
    {
      try
      {
        clientInStream = new FastPusherReply(clientSocket);
        sockBuf.clear();
        sockBuf.putInt(MEDIACMD_INIT<<24 | 4 );
        sockBuf.putInt(videoFormat);
        sockBuf.flip();
        while (sockBuf.hasRemaining())
          clientSocket.write(sockBuf);
        if (Sage.DBG) System.out.println("MiniPlayer established for " + clientName);
        return clientInStream.readInt()!=0 ? 1 : 0;
      }
      catch (java.io.IOException e)
      {
        if (Sage.DBG) System.out.println("Error w/ MiniPlayer socket of:" + e);
        if (!retry)
          return 0;
        if (Sage.DBG) System.out.println("Retrying MiniPlayer connection....");
        try{clientSocket.close();}catch(Exception e2){}
        retry = false;
        clientSocket = (mcsr == null) ? MiniClientSageRenderer.getPlayerSocketChannel(null, null) : mcsr.getPlayerSocketChannel();
      }
    }
  }

  protected void seekPull0(long seekTimeMillis)
  {
    if (Sage.DBG) System.out.println("seekPull0(" + seekTimeMillis + ")");
    try
    {
      sockBuf.clear();
      sockBuf.putInt(MEDIACMD_SEEK<<24 | 8 );
      sockBuf.putLong(seekTimeMillis);
      sockBuf.flip();
      while (sockBuf.hasRemaining())
        clientSocket.write(sockBuf);
    }catch(Exception e)
    {
      System.out.println(e);
      e.printStackTrace();
    }
  }

  protected boolean openURL0(String url)
  {
    if (Sage.DBG) System.out.println("openURL0(" + url + ")");
    int retryCount = clientSocket == null ? 2 : 1;
    if (clientSocket == null)
    {
      clientSocket = (mcsr == null) ? MiniClientSageRenderer.getPlayerSocketChannel(null, null) : mcsr.getPlayerSocketChannel();
      if (clientSocket == null)
        return false;
      if (Sage.DBG) System.out.println("MiniPlayer established for " + (uiMgr == null ? "EXTERNAL" : uiMgr.getLocalUIClientName()));
    }
    while (retryCount > 0)
    {
      retryCount--;
      try
      {
        if (clientInStream == null)
        {
          clientInStream = new FastPusherReply(clientSocket);
        }
        // 11/17/2015 Narflex - This was I18N_CHARSET for all cases in V7; then for
        // some reason we made it I18N_CHARSET for EMBEDDED only...and getBytes() for
        // the other cases. I'm changing it back to the old way of always I18N_CHARSET.
        byte []b = url.getBytes(Sage.I18N_CHARSET);
        sockBuf.clear();
        sockBuf.putInt(MEDIACMD_OPENURL<<24 | b.length+1+4);
        sockBuf.putInt(b.length+1);
        sockBuf.put(b, 0, b.length);
        sockBuf.put((byte)0);
        sockBuf.flip();
        while (sockBuf.hasRemaining())
          clientSocket.write(sockBuf);
        int res = clientInStream.readInt();
        return res!=0;
      }catch(Exception e)
      {
        if (retryCount > 0)
        {
          if (Sage.DBG) System.out.println("Error w/ MiniPlayer socket of:" + e);
          if (Sage.DBG) System.out.println("Retrying MiniPlayer connection....");
          try{clientSocket.close();}catch(Exception e2){}
          clientSocket = (mcsr == null) ? MiniClientSageRenderer.getPlayerSocketChannel(null, null) : mcsr.getPlayerSocketChannel();
          try { clientInStream.close(); } catch(Exception e1){}
          clientInStream = null;
        }
        System.out.println(e);
        e.printStackTrace();
      }
    }
    return false;
  }

  protected long getMediaTimeMillis0()
  {
    if (Sage.eventTime() - lastMediaTimeCacheTime < 100 || clientSocket == null)
      return lastMediaTime;
    //if (Sage.DBG) System.out.println("getMediaTimeMillis0() cachetime is" + Sage.df(lastMediaTimeCacheTime));
    addYieldDecoderLock();
    synchronized (decoderLock)
    {
      try
      {
        sockBuf.clear();
        sockBuf.putInt(MEDIACMD_GETMEDIATIME<<24 | 0);
        sockBuf.flip();
        while (sockBuf.hasRemaining())
          clientSocket.write(sockBuf);
        long currMediaTime = clientInStream.readInt();
        if (currMediaTime == 0xFFFFFFFF)
        {
          // Indicates EOS from the client
          if (!eos)
          {
            if (Sage.DBG) System.out.println("MiniPlayer received an EOS when getting the media time - set the EOS flag");
            // Reset the UI timeouts on an EOS so we don't trigger the SS since it considers EOS to be a non-playing state
            if (uiMgr != null)
              uiMgr.getRouter().resetInactivityTimers();
            eos = true;
            VideoFrame.getVideoFrameForPlayer(MiniPlayer.this).kick();
          }
          lastMediaTimeCacheTime = Sage.eventTime();
          return lastMediaTime;
        }
        if (byteBasedSeeking && mpegSrc != null) {
          // Generate an estimated media time based off the byte position and
          // the current known duration of the file.
          long currPos = Math.max(0, mpegSrc.getReadPos() - maxAvailBufferSize);
          double relativePos = ((double) currPos) / mpegSrc.length();
          MediaFile mf = VideoFrame.getMediaFileForPlayer(this);
          currMediaTime = Math.round(mf.getDuration(currFile) * relativePos);
        }
        // I don't see any good reason to do this interpolation on embedded since if we sent a request to the miniclient above
        // then we should have a pretty accurate time counter right now
        if (lastMediaTimeBase == currMediaTime && currState == PLAY_STATE)
        {
          lastMediaTime = (Sage.eventTime() - lastMediaTimeCacheTime) + lastMediaTime;
        }
        else if (hdMediaExtender && Sage.eventTime() - lastMediaTimeCacheTime < 2000 && currMediaTime > 80000000 &&
            currMediaTime - lastMediaTime > 70000000 && myRate < 0)
        {
          // Set them back to zero in this case. This is a bug on the HD extender where it returns timestamps that are near the PTS rollover
          // value if you're rewinding at a high speed towards the beginning of the file
          lastMediaTime = lastMediaTimeBase = 0;
        }
        else
          lastMediaTime = lastMediaTimeBase = currMediaTime;
        lastMediaTimeCacheTime = Sage.eventTime();
        if (detailedPushBufferStats)
        {
          clientReportedPlayState = clientInStream.readByte();
          if (clientReportedPlayState == EOS_STATE && !eos)
          {
            if (Sage.DBG) System.out.println("Client reported play state indicates EOS, set the flag-2");
            // Reset the UI timeouts on an EOS so we don't trigger the SS since it considers EOS to be a non-playing state
            if (uiMgr != null)
              uiMgr.getRouter().resetInactivityTimers();
            eos = true;
            VideoFrame.getVideoFrameForPlayer(MiniPlayer.this).kick();
          }
        }
        removeYieldDecoderLock();
        decoderLock.notifyAll();
        return lastMediaTime;
      }catch(Exception e)
      {
        if (Sage.DBG) System.out.println("Error with MiniPlayer, closing UI: " + e);
        e.printStackTrace();
        connectionError();
      }
      removeYieldDecoderLock();
      decoderLock.notifyAll();
    }
    return lastMediaTime; // otherwise if a connection is killed while viewing we won't be able to track the watched time in it (this used to return 0 here)
  }

  protected boolean closeDriver0()
  {
    if (Sage.DBG) System.out.println("closeDriver0()");
    try
    {
      sockBuf.clear();
      sockBuf.putInt(MEDIACMD_DEINIT<<24 | 0);
      sockBuf.flip();
      while (sockBuf.hasRemaining())
        clientSocket.write(sockBuf);
      // Why do we care?? This just makes it take longer to close.
      // 8/29/07 - Narflex - We need to wait for the return if the client is using pull mode playback so that the
      // file handle gets closed in case we're about to delete the file.
      // 2/8/10 - Narflex - We need to wait for this to finish in order to know that the video surface has been
      // properly released. Otherwise we may try to display an image for the background, and that won't work properly.
      //			if (!pushMode)
      clientInStream.readInt();
      return true;
    }catch(Exception e)
    {
      // Supress these errors because they could be from an aynchronous client shutdown if we're using the remote pusher
      //System.out.println(e);
      //e.printStackTrace();
    }
    finally
    {
      try
      {
        clientInStream.close();
      }catch (Exception e){}
      try
      {
        clientSocket.close();
      }catch (Exception e){}
      clientInStream = null;
      clientSocket = null;

    }
    return false;
  }

  protected boolean setMute0(boolean x)
  {
    if (clientSocket == null) return false;
    if (Sage.DBG) System.out.println("setMute0()");
    try
    {
      sockBuf.clear();
      sockBuf.putInt(MEDIACMD_SETMUTE<<24 | 4 );
      sockBuf.putInt(x ? 1 : 0);
      sockBuf.flip();
      while (sockBuf.hasRemaining())
        clientSocket.write(sockBuf);
      return clientInStream.readInt()!=0;
    }catch(Exception e)
    {
      if (Sage.DBG) System.out.println("Error with MiniPlayer, closing UI: " + e);
      e.printStackTrace();
      connectionError();
    }
    return false;
  }

  protected boolean stopPush0()
  {
    if (Sage.DBG) System.out.println("stopPush0()");
    try
    {
      sockBuf.clear();
      sockBuf.putInt(MEDIACMD_STOP<<24 | 0 );
      sockBuf.flip();
      while (sockBuf.hasRemaining())
        clientSocket.write(sockBuf);
      return clientInStream.readInt()!=0;
    }catch(Exception e)
    {
      if (Sage.DBG) System.out.println("Error with MiniPlayer, closing UI: " + e);
      e.printStackTrace();
      connectionError();
    }
    return false;
  }

  protected boolean pausePush0()
  {
    if (Sage.DBG) System.out.println("pausePush0()");
    try
    {
      sockBuf.clear();
      sockBuf.putInt(MEDIACMD_PAUSE<<24 | 0 );
      sockBuf.flip();
      while (sockBuf.hasRemaining())
        clientSocket.write(sockBuf);
      if (rpSrc != null)
        rpSrc.sendPause();
      return clientInStream.readInt()!=0;
    }catch(Exception e)
    {
      if (Sage.DBG) System.out.println("Error with MiniPlayer, closing UI: " + e);
      e.printStackTrace();
      connectionError();
    }
    return false;
  }

  protected boolean playPush0()
  {
    if (Sage.DBG) System.out.println("playPush0()");
    try
    {
      sockBuf.clear();
      sockBuf.putInt(MEDIACMD_PLAY<<24 | 0 );
      sockBuf.flip();
      while (sockBuf.hasRemaining())
        clientSocket.write(sockBuf);
      if (rpSrc != null)
        rpSrc.sendPlay();
      return clientInStream.readInt()!=0;
    }catch(Exception e)
    {
      if (Sage.DBG) System.out.println("Error with MiniPlayer, closing UI: " + e);
      e.printStackTrace();
      connectionError();
    }
    return false;
  }

  protected boolean flushPush0()
  {
    if (Sage.DBG) System.out.println("flushPush0()");
    lastParserTimestamp = 0;
    lastParserTimestampBytePos = 0;
    if (numPushedBuffers > 0 || mediaExtender)
    {
      try
      {
        sockBuf.clear();
        sockBuf.putInt(MEDIACMD_FLUSH<<24 | 0 );
        sockBuf.flip();
        while (sockBuf.hasRemaining())
          clientSocket.write(sockBuf);
        boolean rv = clientInStream.readInt()!=0;
        if (currMute && mediaExtender)
        {
          // Flushing the decoder resets the mute state on the MVP
          setMute0(currMute);
        }
      }catch(Exception e)
      {
        if (Sage.DBG) System.out.println("Error with MiniPlayer, closing UI: " + e);
        e.printStackTrace();
        connectionError();
      }
    }
    // --- NG Context wiring: notify flush ---
    ngContextWiring.onFlush();
    // --- end NG Context wiring ---
    return false;
  }
  private long lastTime = -1;
  private long pushedBytes = 0;
  private int numWaits = 0;
  private int numPushes = 0;
  protected boolean pushBuffer0(java.nio.ByteBuffer buf, int size, int flags)
  {
    try
    {
      long t1 = Sage.eventTime();
      if (lastTime == -1 || t1 - lastTime > 10000)
      {
        lastTime = t1;
        pushedBytes = 0;
      }
      if (size > 0 && (detailedPushBufferStats || dynamicRateAdjust))
      {
        //				if (Sage.DBG && (numPushedBuffers % (lowBandwidth ? 50 : 500) == 0))
        //					System.out.println("Pusher BWestim=" + lastAverageEstimatedPushBitrate);
        pushTimerEntry();
      }
      boolean alreadyFilledBuffer = false;
      if (buf != null && size > 0 && !transcoded && (flags & PUSHBUFFER_SUBPIC_FLAG) == 0 &&
          (bufferFillPause || sendSeekPullNext || !useNioTransfers || (bdp != null && mpegSrc.canSkipOnNextRead())))
      {
        alreadyFilledBuffer = true;
        buf.clear().limit(size);
        mpegSrc.transfer(null, size, buf);
        if (bufferFillPause && videoPTSForPlay == -1)
        {
          videoPTSForPlay = mpegSrc.getLastIFramePTS() + Sage.getInt("miniclient/video_pts_gap_for_play", 45000);
          //System.out.println("Set videoPTSForPlay=" + videoPTSForPlay);
        }
        if (justSeeked)
        {
          // This is used for debugging alignment on seek
          /*System.out.println("Post Seek Push Buffer Dump:");
					StringBuffer sb = new StringBuffer();
					for (int i = 0; i < 2048; i++)
					{
						int x = buf.get(i) & 0xFF;
						if (x < 16)
							sb.append('0');
						sb.append(Integer.toString(x, 16));
						if (sb.length() == 64)
						{
							System.out.println(sb.toString());
							sb.setLength(0);
						}
					}*/
          justSeeked = false;
        }
      }
      // Check for the special case with BluRay where we jump a cell boundary while doing the reseek to the proper PTS
      if (bdp != null && alreadyFilledBuffer && lastBluRayIndex != bdp.getCurrClipIndex())
      {
        lastBluRayIndex = bdp.getCurrClipIndex();
        long ptsOffset = bdp.getClipPtsOffset(lastBluRayIndex);
        if (Sage.DBG) System.out.println("Detected cell boundary for BluRay-2; send the NewCell command with PTSOffset=" + ptsOffset);
        NewCell0(ptsOffset);
      }
      if (sendSeekPullNext && alreadyFilledBuffer)
      {
        sendSeekPullNext = false;
        if (currState == PAUSE_STATE && mpegSrc.isIFrameAlignEnabled())
        {
          // Send the target time that we should decode to enable seeking while paused
          long targetPTS = mpegSrc.getLastIFramePTS();
          if (targetPTS > 0)
          {
            if (Sage.DBG) System.out.println("Sending seek pull command to enable seek while paused targetPTS=" + targetPTS);
            seekPull0(targetPTS + 5000);
          }
        }
      }
      sockBuf.clear();
      sockBuf.putInt(MEDIACMD_PUSHBUFFER<<24 | (size+(detailedPushBufferStats ? 18 : 8) ));
      sockBuf.putInt(size);
      sockBuf.putInt(flags);
      long currParserTimestamp = (mpegSrc != null) ? mpegSrc.getLastParsedDTSMillis() : (tcSrc != null ? tcSrc.getLastParsedTimeMillis() : 0);
      if (detailedPushBufferStats)
      {
        // Send an extra 10 bytes for statistics info. Bandwidths are short term average measurements from the last push
        // we did. The mux time is the time at the end of the data that's currently being pushed.
        // 2 bytes for the estimated channel bandwidtdh in kbps
        // 2 bytes for the estimated data rate in kbps
        // 2 bytes for the target data rate in kbps
        // 4 bytes for the server's mux time in milliseconds
        sockBuf.putShort((short) (Math.min(Short.MAX_VALUE, lastEstimatedPushBitrate/1000)));
        sockBuf.putShort((short) (Math.min(Short.MAX_VALUE, lastEstimatedStreamBitrate/1000)));
        if (mpegSrc != null && mpegSrc.getTranscoder() instanceof FFMPEGTranscoder)
        {
          FFMPEGTranscoder fftc = ((FFMPEGTranscoder)mpegSrc.getTranscoder());
          sockBuf.putShort((short) fftc.getCurrentStreamBitrateKbps());
        }
        else
          sockBuf.putShort((short)0);
        sockBuf.putInt((int)(currParserTimestamp - timestampOffset));
      }
      sockBuf.flip();
      if(buf!=null && size > 0)
      {
        if ((flags & PUSHBUFFER_SUBPIC_FLAG) != 0 || transcoded) // transcoded implies MP3StreamWrapper
        {
          dbuf[0] = sockBuf;
          dbuf[1] = buf;
          while (sockBuf.hasRemaining() || buf.hasRemaining())
            clientSocket.write(dbuf);
        }
        else
        {
          if (useNioTransfers && !alreadyFilledBuffer && pushDumpStream == null)
          {
            while (sockBuf.hasRemaining())
              clientSocket.write(sockBuf);
            mpegSrc.transfer(clientSocket, size, buf);
          }
          else
          {
            if (!alreadyFilledBuffer)
            {
              buf.clear().limit(size);
              mpegSrc.transfer(null, size, buf);
            }
            dbuf[0] = sockBuf;
            dbuf[1] = buf;
            while (sockBuf.hasRemaining() || buf.hasRemaining())
              clientSocket.write(dbuf);
            if (pushDumpStream != null)
            {
              buf.rewind();
              pushDumpStream.write(buf);
            }
          }
        }
      }
      else
      {
        while (sockBuf.hasRemaining())
          clientSocket.write(sockBuf);
      }
      long t2 = Sage.eventTime();
      pushedBytes += size;
      if (bwDebug && Sage.DBG && numPushedBuffers % 50 == 0 && t2 > lastTime && size > 0)
        System.out.println("BW=" + ((pushedBytes * 8000) / (t2 - lastTime)) + " numPushes=" + numPushes + " numWaits=" + numWaits);
      numPushes++;
      if (size == 0 || !pushMode || (!useAsyncReplies && freeSpace <= pushBufferSize))
      {
        numWaits++;
        freeSpace = clientInStream.readInt();
        if (detailedPushBufferStats)
        {
          clientReportedMediaTime = clientInStream.readInt();
          clientReportedPlayState = clientInStream.readByte();
        }
        if (debugPush) System.out.println("Read the reply from the push call size=" + size + " freeSpace=" + freeSpace);
      }
      else
      {
        clientInStream.lazyReadRegister(size);
      }
      if (size > 0 && (detailedPushBufferStats || dynamicRateAdjust))
        addBytesToPushCalc(size);
      // Calculate the bandwidth of the stream data we're trying to send
      if (currParserTimestamp > lastParserTimestamp && mpegSrc != null &&
          (mpegSrc.isLastTimestampVideo() || currHintMajorType == MediaFile.MEDIATYPE_AUDIO))
      {
        if ((detailedPushBufferStats || dynamicRateAdjust) && lastParserTimestampBytePos > 0)
        {
          int currEstim = Math.round(1000*((((float)mpegSrc.getLastParsedDTSPackBytePos() - lastParserTimestampBytePos) * 8) /
              (currParserTimestamp - lastParserTimestamp)));
          //System.out.println("currEstim=" + currEstim + " lastPos=" + lastParserTimestampBytePos + " currPos=" + mpegSrc.getLastParsedDTSPackBytePos() +
          //	" lastTime=" + lastParserTimestamp + " currTime=" + currParserTimestamp);
          if (lastEstimatedStreamBitrate == 0)
          {
            lastAverageEstimatedStreamBitrate = lastEstimatedStreamBitrate = currEstim;
            streamBitrateStats[0] = currEstim;
            streamBitrateStatsWeights[0] = currParserTimestamp - lastParserTimestamp;
            streamBitrateStatsIndex = 1;
          }
          else
          {
            streamBitrateStats[streamBitrateStatsIndex] = currEstim;
            streamBitrateStatsWeights[streamBitrateStatsIndex] = currParserTimestamp - lastParserTimestamp;
            streamBitrateStatsIndex = (streamBitrateStatsIndex + 1) % streamBitrateStats.length;
            long avgEstAgg = 0;
            long avgEstAggWeights = 0;
            for (int i = 0; i < streamBitrateStats.length; i++)
            {
              avgEstAgg += ((long)streamBitrateStats[i]) * streamBitrateStatsWeights[i];
              avgEstAggWeights += streamBitrateStatsWeights[i];
            }
            lastEstimatedStreamBitrate = currEstim;
            lastAverageEstimatedStreamBitrate = (int)(avgEstAgg / avgEstAggWeights);
          }
        }
        lastParserTimestamp = currParserTimestamp;
        lastParserTimestampBytePos = mpegSrc.getLastParsedDTSPackBytePos();
      }

      maxAvailBufferSize = Math.max(maxAvailBufferSize, freeSpace);
      if (detailedPushBufferStats)
      {
        lastDetailedBufferUpdate = Sage.eventTime();
        if (Sage.DBG && debugPush && mpegSrc != null)
        {
          System.out.println("Client Play Time=" + clientReportedMediaTime  + " svrTime=" + (mpegSrc.getLastParsedTimeMillis() - timestampOffset) +
              " diff=" + (mpegSrc.getLastParsedTimeMillis() - clientReportedMediaTime - timestampOffset) + " estimRate=" + lastEstimatedStreamBitrate +
              " estimAvgRate=" + lastAverageEstimatedStreamBitrate);
        }

        if (videoDimensions == null && (clientReportedPlayState == PAUSE_STATE || clientReportedPlayState == PLAY_STATE) &&
            currHintMajorType != MediaFile.MEDIATYPE_AUDIO)
        {
          videoDimensions = getVideoDimensions0();
          if (Sage.DBG) System.out.println("Got video dimensions from push player of:" + videoDimensions);
          // Force a UI refresh of the whole screen so we properly position the video now
          if (uiMgr != null)
          {
            ZRoot rooty = uiMgr.getRootPanel();
            if (rooty != null)
            {
              rooty.appendToDirty(new java.awt.Rectangle(0, 0, rooty.getWidth(), rooty.getHeight()));
            }
          }
        }
      }
      //if(freeSpace>32768) freeSpace=32768;
      return true;
    }catch(Exception e)
    {
      if (Sage.DBG) System.out.println("Error with MiniPlayer, closing UI: " + e);
      e.printStackTrace();
      connectionError();
    }
    return false;
  }

  protected java.awt.Dimension getVideoDimensions0()
  {
    if (!mediaExtender && detailedPushBufferStats)
    {
      if (!pushMode || (clientReportedPlayState == PLAY_STATE || clientReportedPlayState == PAUSE_STATE))
      {
        try
        {
          sockBuf.clear();
          sockBuf.putInt(MEDIACMD_GETVIDEORECT<<24 | 0);
          sockBuf.flip();
          while (sockBuf.hasRemaining())
            clientSocket.write(sockBuf);
          short rectWidth = clientInStream.readShort();
          short rectHeight = clientInStream.readShort();
          if (rectWidth > 0 && rectHeight > 0)
            return new java.awt.Dimension(rectWidth, rectHeight);
          else
          {
            if (!pushMode)
            {
              MediaFile mf = VideoFrame.getMediaFileForPlayer(this);
              if (mf != null)
              {
                sage.media.format.ContainerFormat cf = (bdp != null) ? bdp.getFileFormat() : mf.getFileFormat();
                if (cf != null)
                {
                  sage.media.format.VideoFormat vidForm = cf.getVideoFormat();
                  if (vidForm != null && vidForm.getWidth() > 0 && vidForm.getHeight() > 0)
                  {
                    return new java.awt.Dimension(vidForm.getWidth(), vidForm.getHeight());
                  }
                }
              }
            }
            return null;
          }
        }catch(Exception e)
        {
          System.out.println(e);
          e.printStackTrace();
          return null;
        }
      }
      else
        return null;
    }
    else
      return new java.awt.Dimension(720,480);
  }

  private void connectionError()
  {
    // Don't forcibly kill the UI if we had a client/server problem
    if (mcsr != null)
      mcsr.connectionError();
  }

  protected boolean setVideoRectangles0(java.awt.Rectangle videoSrcRect, java.awt.Rectangle videoDestRect)
  {
    if(clientSocket != null && clientInStream != null)
    {
      try
      {
        sockBuf.clear();
        sockBuf.putInt(MEDIACMD_SETVIDEORECT<<24 | 8*4 );
        sockBuf.putInt(videoSrcRect.x);
        sockBuf.putInt(videoSrcRect.y);
        sockBuf.putInt(videoSrcRect.width);
        sockBuf.putInt(videoSrcRect.height);
        sockBuf.putInt(videoDestRect.x);
        sockBuf.putInt(videoDestRect.y);
        sockBuf.putInt(videoDestRect.width);
        sockBuf.putInt(videoDestRect.height);
        sockBuf.flip();
        while (sockBuf.hasRemaining())
          clientSocket.write(sockBuf);
        clientInStream.readInt();
        return true;
      }catch(Exception e)
      {
        if (Sage.DBG) System.out.println("Error with MiniPlayer, closing UI: " + e);
        e.printStackTrace();
        connectionError();
      }
    }
    return false;
  }

  protected float getVolume0()
  {
    if (clientSocket == null) return 0;
    try
    {
      sockBuf.clear();
      sockBuf.putInt(MEDIACMD_GETVOLUME<<24 | 0 );
      sockBuf.flip();
      while (sockBuf.hasRemaining())
        clientSocket.write(sockBuf);
      return clientInStream.readInt()/65535.0f;
    }catch(Exception e)
    {
      if (Sage.DBG) System.out.println("Error with MiniPlayer, closing UI: " + e);
      e.printStackTrace();
      connectionError();
    }
    return 0.0f;
  }

  protected float setVolume0(float volume)
  {
    if (clientSocket == null) return 0;
    try
    {
      sockBuf.clear();
      sockBuf.putInt(MEDIACMD_SETVOLUME<<24 | 4 );
      sockBuf.putInt((int)(volume*65535));
      sockBuf.flip();
      while (sockBuf.hasRemaining())
        clientSocket.write(sockBuf);
      return clientInStream.readInt()/65535.0f;
    }catch(Exception e)
    {
      if (Sage.DBG) System.out.println("Error with MiniPlayer, closing UI: " + e);
      e.printStackTrace();
      connectionError();
    }
    return 0.0f;
  }

  protected boolean frameStep0(int amount)
  {
    try
    {
      sockBuf.clear();
      sockBuf.putInt(MEDIACMD_FRAMESTEP<<24 | 4 );
      sockBuf.putInt(amount);
      sockBuf.flip();
      while (sockBuf.hasRemaining())
        clientSocket.write(sockBuf);
      clientInStream.readInt();
    }catch(Exception e)
    {
      if (Sage.DBG) System.out.println("Error with MiniPlayer, closing UI: " + e);
      e.printStackTrace();
      connectionError();
    }
    return true;
  }

  protected boolean DVDStream(int type, int stream)
  {
    try
    {
      addYieldDecoderLock();
      synchronized (decoderLock)
      {
        int streamStatus;
        sockBuf.clear();
        sockBuf.putInt(MEDIACMD_DVD_STREAM<<24 |
            (8));
        sockBuf.putInt(type);
        sockBuf.putInt(stream);
        sockBuf.flip();
        while (sockBuf.hasRemaining())
          clientSocket.write(sockBuf);
        streamStatus = clientInStream.readInt();
        removeYieldDecoderLock();
        decoderLock.notifyAll();
        return true;
      }
    }catch(Exception e)
    {
      if (Sage.DBG) System.out.println("Error with MiniPlayer, closing UI: " + e);
      e.printStackTrace();
      connectionError();
    }
    return false;
  }

  protected boolean NewCell0(long ptsOffset)
  {
    try
    {
      int cellStatus;
      sockBuf.clear();
      sockBuf.putInt(MEDIACMD_DVD_NEWCELL<<24 |
          (8));
      sockBuf.putInt(4);
      sockBuf.putInt((int)(ptsOffset & 0xFFFFFFFF));
      sockBuf.flip();
      while (sockBuf.hasRemaining())
        clientSocket.write(sockBuf);
      cellStatus = clientInStream.readInt();
      return true;
    }catch(Exception e)
    {
      if (Sage.DBG) System.out.println("Error with MiniPlayer, closing UI: " + e);
      e.printStackTrace();
      connectionError();
    }
    return false;
  }

  protected boolean CLUT0(int size, byte[] buf)
  {
    try
    {
      int spuStatus;
      sockBuf.clear();
      sockBuf.putInt(MEDIACMD_DVD_CLUT<<24 |
          (size+(4)));
      sockBuf.putInt(size);
      if(buf!=null)
        sockBuf.put(buf, 0, size);
      sockBuf.flip();
      while (sockBuf.hasRemaining())
        clientSocket.write(sockBuf);
      spuStatus = clientInStream.readInt();
      return true;
    }catch(Exception e)
    {
      if (Sage.DBG) System.out.println("Error with MiniPlayer, closing UI: " + e);
      e.printStackTrace();
      connectionError();
    }
    return false;
  }


  private long pushTimerBase;
  private long pushTimerStopTime; // if non-zero, then its stopped
  private long pushTimerBytes;
  private final long[] pushStatHolder = new long[2];
  private int sucessivePushStatDiscards;
  private boolean dataRecvdForLastResume = true; // indicates the addBytesToPushCalc call was made for the corresponding pushTimerEntry call
  private void pushTimerEntry()
  {
    synchronized (pushStatHolder)
    {
      dataRecvdForLastResume = false;
      resumePushTimer();
      if (pushTimerBase == 0)
        pushTimerBase = Sage.eventTime();
    }
  }

  private void addBytesToPushCalc(int size)
  {
    synchronized (pushStatHolder)
    {
      dataRecvdForLastResume = true;
      pushTimerBytes += size;
    }
  }

  private boolean hasNewPushStats()
  {
    long currTime = Sage.eventTime();
    if (pushTimerBytes > 50000 && currTime - pushTimerBase > 50)
    {
      return true;
    }
    return false;
  }

  private long[] getNewPushStat()
  {
    synchronized (pushStatHolder)
    {
      long currTime = Sage.eventTime();
      pushStatHolder[0] = pushTimerBytes * 8000 / (currTime - pushTimerBase);
      pushStatHolder[1] = currTime - pushTimerBase;
      pushTimerBase = currTime;
      pushTimerBytes = 0;
      pushStatHolder[1] = 1; // REMOVE THE WEIGHTS
      // Sanity check for bandwidth values that are totally off due to forced waits removing all the delay
      if (pushStatHolder[0] > 3 * lastAverageEstimatedPushBitrate && sucessivePushStatDiscards < 5 &&
          pushStatHolder[0] > 500000 && lowBandwidth)
      {
        if (debugPush) System.out.println("DISCARDING push stat because it's too large avg=" + lastAverageEstimatedPushBitrate +
            " successiveDiscards=" + sucessivePushStatDiscards + " curr=" + pushStatHolder[0]);
        sucessivePushStatDiscards++;
        pushStatHolder[0] = 0;
      }
      else
        sucessivePushStatDiscards = 0;
    }
    return pushStatHolder;
  }

  private void suspendPushTimer()
  {
    if (pushTimerStopTime == 0)
    {
      if (debugPush) System.out.println("SUSPENDING the push bandwidth timer");
      pushTimerStopTime = Sage.eventTime();
    }
  }

  private void resumePushTimer()
  {
    if (pushTimerStopTime > 0)
    {
      long currTime = Sage.eventTime();
      pushTimerBase += (currTime - pushTimerStopTime);
      pushTimerStopTime = 0;
      if (debugPush) System.out.println("RESUMING the push bandwidth timer");
    }
  }

  private class FastPusherReply implements Runnable
  {
    public FastPusherReply(java.nio.channels.SocketChannel is)
    {
      in = is;
      alive = true;
      bb = java.nio.ByteBuffer.allocate(64);
    }

    public void run()
    {
      // Asynchronously process the replies from the pusher here. We then take control
      // of when the push BW timer should be stopped as well; and much more accurately then this can
      // be done in the pusher thread itself. We'll also get reliable media time/state updates as well
      // since they'll be processed right when they're received rather then when the pusher has time.
      try
      {
        int timeoutRetries = 1;
        while (alive)
        {
          if (extraRepliesToRead > 0)
          {
            try
            {
              int x = readIntX();
              synchronized (this)
              {
                if (x <= 0)
                  freeSpace = x;
                else
                  freeSpace = Math.max(0, x - (extraRepliesToRead - 1) * pushBufferSize);
                if (debugPush) System.out.println("Did the async read for the pusher reply freeSpace=" + freeSpace +
                    " repliesLeft=" + (extraRepliesToRead-1) + " replyFreeSpace=" + x);
              }
            }
            catch (java.net.SocketTimeoutException ste)
            {
              // Timeouts should not occur...but we'll give them a single retry just in case
              if (timeoutRetries > 0)
              {
                timeoutRetries--;
                if (Sage.DBG) System.out.println("Async pusher reply timed out waiting for a response...try again...");
                continue;
              }
              else
                throw ste;
            }
            if (detailedPushBufferStats)
            {
              clientReportedMediaTime = readIntX();
              clientReportedPlayState = readByteX();
            }
            // Calculate the bandwidth for what we're actually sending across the channel
            if ((detailedPushBufferStats || dynamicRateAdjust) && hasNewPushStats())
            {
              //int currEstim = Math.round(1000*((((float)size + 12) * 8) / (t2 - t1)));
              long[] currData = getNewPushStat();
              int currEstim = (int) currData[0];
              if (currEstim > 0)
              {
                if (lastEstimatedPushBitrate == 0)
                {
                  lastAverageEstimatedPushBitrate = lastEstimatedPushBitrate = currEstim;
                  pushBitrateStats[0] = currEstim;
                  pushBitrateStatsWeights[0] = currData[1];
                  pushBitrateStatsIndex = 1;
                }
                else
                {
                  pushBitrateStats[pushBitrateStatsIndex] = currEstim;
                  pushBitrateStatsWeights[pushBitrateStatsIndex] = currData[1];
                  pushBitrateStatsIndex = (pushBitrateStatsIndex + 1) % pushBitrateStats.length;
                  int calcIndex = pushBitrateStatsIndex - NUM_SAMPLES_BANDWIDTH_ESTIMATE;
                  if (calcIndex < 0)
                    calcIndex += pushBitrateStats.length;
                  long estAgg = 0;
                  long estAggWeights = 0;
                  long avgEstAgg = 0;
                  long avgEstWeights = 0;
                  for (int i = 0; i < pushBitrateStats.length; i++, calcIndex++)
                  {
                    calcIndex = calcIndex % pushBitrateStats.length;
                    if (i < NUM_SAMPLES_BANDWIDTH_ESTIMATE)
                    {
                      estAgg += ((long)pushBitrateStats[calcIndex]) * pushBitrateStatsWeights[calcIndex];
                      estAggWeights += pushBitrateStatsWeights[calcIndex];
                    }
                    avgEstAgg += ((long)pushBitrateStats[calcIndex]) * pushBitrateStatsWeights[calcIndex];
                    avgEstWeights += pushBitrateStatsWeights[calcIndex];
                  }
                  lastEstimatedPushBitrate = (int)(estAgg / estAggWeights);
                  lastAverageEstimatedPushBitrate = (int)(avgEstAgg / avgEstWeights);
                }
                if (debugPush) System.out.println("Adding BW ESTIMATE " + currEstim/1000 + " lastEstim=" + lastEstimatedPushBitrate + " lastAvg=" + lastAverageEstimatedPushBitrate);
              }
            }
            synchronized (this)
            {
              extraRepliesToRead--;
            }
          }
          else
          {
            synchronized (this)
            {
              if (extraRepliesToRead == 0)
              {
                // Suspend the timer because there's no data in the channel right now
                if ((detailedPushBufferStats || dynamicRateAdjust) && dataRecvdForLastResume)
                  suspendPushTimer();
                notify();
                try
                {
                  wait(10000);
                }
                catch (InterruptedException e){}
              }
            }
          }
          timeoutRetries = 1;
        }
      }
      catch (java.io.IOException e)
      {
        if (alive && Sage.DBG)
        {
          System.out.println("PusherReply thread terminated with exception:" + e);
          e.printStackTrace();
        }
      }
      finally
      {
        alive = false;
      }
    }

    public synchronized void lazyReadRegister(int bufSize)
    {
      extraRepliesToRead++;
      freeSpace = Math.max(0, freeSpace - bufSize);
      if (debugPush) System.out.println("Adjusted freeSpace from push ahead to be:" + freeSpace);
      if (!startedReplyThread && alive && useAsyncReplies)
      {
        Pooler.execute(this, "PusherReply");
        startedReplyThread = true;
      }
      else
      {
        if (useAsyncReplies)
          notify();
      }
    }

    public void close()  throws java.io.IOException
    {
      alive = false;
      in.close();
    }

    // These are the only 3 read calls we use.
    // Must be synchronized to prevent the async PusherReply thread from
    // racing on the shared bb ByteBuffer while we read a command reply.
    public synchronized byte readByte() throws java.io.IOException
    {
      checkLazies();
      return readByteX();
    }
    public synchronized int readInt() throws java.io.IOException
    {
      checkLazies();
      return readIntX();
    }
    public int getLazyReplyCount()
    {
      return extraRepliesToRead;
    }
    private byte readByteX() throws java.io.IOException
    {
      bb.clear();
      bb.limit(1);
      try
      {
        TimeoutHandler.registerTimeout(timeout, in);
        int num = in.read(bb);
        if (num < 0)
          throw new java.io.EOFException();
      }
      finally
      {
        TimeoutHandler.clearTimeout(in);
      }
      bb.flip();
      return bb.get();
    }
    private int readIntX() throws java.io.IOException
    {
      bb.clear().limit(4);
      try
      {
        TimeoutHandler.registerTimeout(timeout, in);
        do{
          int x = in.read(bb);
          if (x < 0)
            throw new java.io.EOFException();
        }while(bb.remaining() > 0);
      }
      finally
      {
        TimeoutHandler.clearTimeout(in);
      }
      bb.flip();
      return bb.getInt();
    }
    public synchronized short readShort() throws java.io.IOException
    {
      checkLazies();
      bb.clear().limit(2);
      try
      {
        TimeoutHandler.registerTimeout(timeout, in);
        do{
          int x = in.read(bb);
          if (x < 0)
            throw new java.io.EOFException();
        }while(bb.remaining() > 0);
      }
      finally
      {
        TimeoutHandler.clearTimeout(in);
      }
      bb.flip();
      return bb.getShort();
    }
    private synchronized void checkLazies() throws java.io.IOException
    {
      if (useAsyncReplies)
        notify();
      while (alive && extraRepliesToRead > 0)
      {
        if (useAsyncReplies)
        {
          try
          {
            wait(500);
          }
          catch (InterruptedException e)
          {}
        }
        else
        {
          try
          {
            TimeoutHandler.registerTimeout(timeout, in);
            int x = readIntX();
            if (x <= 0)
              freeSpace = x;
            else
              freeSpace = Math.max(0, x - (extraRepliesToRead - 1) * pushBufferSize);
            if (detailedPushBufferStats)
            {
              clientReportedMediaTime = readIntX();
              clientReportedPlayState = readByteX();
            }
          }
          finally
          {
            TimeoutHandler.clearTimeout(in);
          }
          extraRepliesToRead--;
        }
      }
    }

    private int extraRepliesToRead;
    private java.nio.channels.SocketChannel in;
    private boolean alive;
    private boolean startedReplyThread;
    private java.nio.ByteBuffer bb;
  }


  protected volatile int currState;
  protected volatile java.io.File currFile;
  protected byte currHintMajorType;
  protected byte currHintMinorType;
  protected String currHintEncoding;

  protected volatile FastMpeg2Reader mpegSrc;
  protected volatile Mpeg2Transcoder tcSrc;
  protected volatile RemotePusherClient rpSrc;

  protected final Object decoderLock = new Object();

  protected java.awt.Dimension videoDimensions;
  private long lastVideoDimRetry;

  protected int currCCState;
  protected boolean eos;

  protected long timeGuessMillis;
  protected long guessTimestamp;
  protected long timestampOffset;
  protected boolean byteBasedSeeking;
  protected boolean serverSideTranscoding;

  protected boolean pushMode;

  protected boolean currMute;

  protected UIManager uiMgr;
  protected MiniClientSageRenderer mcsr;
  protected java.awt.Color colorKey;

  protected Thread pushThread;
  protected volatile boolean timeshifted;
  protected volatile long finalLength;
  protected volatile boolean transcoded;

  protected java.awt.Rectangle lastVideoSrcRect;
  protected java.awt.Rectangle lastVideoDestRect;

  protected float myRate;
  protected int freeSpace = 0;
  protected float curVolume = 1.0f;

  private int maxAvailBufferSize;
  private long lastRateAdjustTime;

  private int lastEstimatedPushBitrate;
  private int lastAverageEstimatedPushBitrate;
  private int[] pushBitrateStats = new int[NUM_SAMPLES_AVERAGE_BANDWIDTH_ESTIMATE];
  private long[] pushBitrateStatsWeights = new long[NUM_SAMPLES_AVERAGE_BANDWIDTH_ESTIMATE];
  private int pushBitrateStatsIndex;
  private int lastEstimatedStreamBitrate;
  private int lastAverageEstimatedStreamBitrate;
  private int[] streamBitrateStats = new int[NUM_SAMPLES_AVERAGE_BANDWIDTH_ESTIMATE];
  private long[] streamBitrateStatsWeights = new long[NUM_SAMPLES_AVERAGE_BANDWIDTH_ESTIMATE];
  private int streamBitrateStatsIndex;

  private boolean pushThreadCreated;

  private boolean needToPlay;

  private boolean dynamicRateAdjust;

  int numPushedBuffers = 0;
  final boolean debugPush = Sage.getBoolean("miniclient/debug_push", false);
  boolean sentDiscardPtsFlag = false;
  boolean sentTrickmodeFlag = false;

  private long lastMediaTime;
  private long lastMediaTimeBase;
  private long lastMediaTimeCacheTime;

  private boolean detailedPushBufferStats;
  private int clientReportedMediaTime;
  private int clientReportedPlayState;
  private long lastDetailedBufferUpdate;
  private long lastSessionBandwidthPersistTime;
  private long lastSessionBandwidthPersistBps;

  private long lastParserTimestamp;
  private long lastParserTimestampBytePos;

  private int pushBufferSize;

  private boolean firstSeek;

  private boolean mediaExtender; // true if this is a connection from a media extender; false if it's a desktop app
  private boolean lowBandwidth; // true if this is a 'remote' connection that's low bandwidth
  private boolean hdMediaExtender;

  private boolean usingRemuxer;

  // I know this isn't perfect, but it's a quick and easy way to allow yielding on the decoder
  // lock only when necessary. It'll yield in a fair amount of other cases as well though.
  private int yieldDecoderLockCount;
  private final Object yieldDecoderLockCountLock = new Object();

  private boolean disableVideoPositioning = false;
  private int languageIndex;
  private sage.media.format.AudioFormat[] audioTracks;
  private int subpicIndex;
  private boolean subpicOn;
  private sage.media.format.SubpictureFormat[] subpicTracks;
  private String[] subpicSels;
  private String[] audioSels;
  private boolean isMpeg2PS;

  private boolean waitingForSeek;

  private FileDownloader downer;

  private int currBandwidthBufferKbps = BANDWIDTH_BUFFER_KBPS;

  // For BluRay handling
  private sage.media.bluray.BluRayStreamer bdp;
  private int lastBluRayIndex;
  private int currBDAngle;
  private int currBDTitle;

  private boolean useNioTransfers;

  private int maxPushBufferSize;

  private java.nio.ByteBuffer[] dbuf = new java.nio.ByteBuffer[2];

  private boolean useAsyncReplies = true;
  private long timeout = Sage.getInt("ui/remote_player_connection_timeout", 30000);
  private boolean bwDebug = Sage.getBoolean("miniplayer/bwstats", false);

  private java.nio.channels.FileChannel pushDumpStream;

  protected java.io.File unmountRequired;
  private boolean justSeeked = false;
  private boolean sendSeekPullNext;
  private boolean wasFastSwitch;
  private long videoPTSForPlay;
  private boolean bufferFillPause;
  private boolean hdMediaPlayer;
  private boolean enableBufferFillPause;

  private boolean hdhrPrimeSpecial;
  private int autoRemuxFailureCount;

  /** NG Playback Context wiring — lifecycle bridge to provider. Never null. */
  private final sage.ng.NgPlaybackContextWiring ngContextWiring =
      new sage.ng.NgPlaybackContextWiring(sage.ng.NgPlaybackContextWiring.getGlobalProvider());

  /**
   * Attempt auto-remux on playback failure if the client profile permits it.
   * Returns the path to the remuxed file, or null if remux is not applicable or failed.
   */
  protected String attemptAutoRemux(java.io.File sourceFile)
  {
    if (mcsr == null || sourceFile == null) return null;
    sage.client.ClientProfile profile = mcsr.getResolvedProfile();
    if (profile == null || !profile.isAutoRemuxEnabled()) return null;

    // Limit remux attempts per playback session
    if (autoRemuxFailureCount >= 2)
    {
      if (Sage.DBG) System.out.println("MiniPlayer: Auto-remux attempt limit reached");
      return null;
    }
    autoRemuxFailureCount++;

    // Pick the first allowed container from the profile
    String targetContainer = null;
    for (String c : profile.getContainers())
    {
      targetContainer = c;
      break;
    }
    if (targetContainer == null) return null;

    String ffmpegPath = System.getProperty("user.dir") + java.io.File.separator + "ffmpeg";
    sage.client.AutoRemuxer remuxer = sage.client.AutoRemuxer.getInstance();
    return remuxer.onPlaybackFailure(profile, sourceFile, targetContainer, ffmpegPath);
  }

  private static String toMimeType(String fmt)
  {
    if (fmt == null) return null;
    switch (fmt)
    {
      // Containers
      case "MPEG2-TS": return "video/mp2t";
      case "MPEG2-PS": return "video/mpeg";
      case "MATROSKA": return "video/x-matroska";
      case "MP4": return "video/mp4";
      case "AVI": return "video/x-msvideo";
      case "Quicktime": return "video/quicktime";
      // Video
      case "HEVC": return "video/hevc";
      case "H.264": return "video/avc";
      case "MPEG2-Video": return "video/mpeg2";
      case "MPEG4-Video": return "video/mp4v-es";
      case "VC1": return "video/x-ms-wmv";
      // Audio
      case "AC-4": return "audio/ac4";
      case "EAC3": return "audio/eac3";
      case "AC3": return "audio/ac3";
      case "AAC": return "audio/mp4a-latm";
      case "MP3": return "audio/mpeg";
      case "MP2": return "audio/mpeg-L2";
      case "FLAC": return "audio/flac";
      case "DTS": return "audio/vnd.dts";
      case "DTS-HD": return "audio/vnd.dts.hd";
      case "DTS-MA": return "audio/vnd.dts.hd";
      case "Vorbis": return "audio/vorbis";
      case "ALAC": return "audio/alac";
      default: return null;
    }
  }
}
