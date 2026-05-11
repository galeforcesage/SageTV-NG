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

import sage.hdhr.HDHomeRunLineup;
import sage.hdhr.HttpPullCaptureJob;

/**
 * @abstract HDHomeRun capture device class
 * @author  David DeHaven
 */
public class HDHomeRunCaptureDevice extends CaptureDevice implements Runnable
{
  /** Creates a new instance of HDHomeRunCaptureDevice */
  public HDHomeRunCaptureDevice()
  {
    super();
    captureFeatureBits = MMC.MPEG_AV_CAPTURE_MASK;
  }

  public HDHomeRunCaptureDevice(int inID)
  {
    super(inID);
    // these only capture MPEG-2 TS
    captureFeatureBits = MMC.MPEG_AV_CAPTURE_MASK;
  }

  public int getCaptureFeatureBits() {
    return captureFeatureBits;
  }

  public void freeDevice()
  {
    if (Sage.DBG) System.out.println("Freeing HDHomeRun capture device ("+ getName() + ")");
    try { activateInput(null); } catch (EncodingException e){}
    writePrefs();
    stopEncoding();
    destroyEncoder0(pHandle);
    pHandle = 0;
  }

  public boolean supportsFastMuxSwitch()
  {
    return true;
  }

  /* uncomment for debugging channel scanning... */
  /*	public int getMaxChannel(CaptureDeviceInput myInput)
	{
		return 19;
	}

	public int getMinChannel(CaptureDeviceInput myInput)
	{
		return 12;
	} */

  public long getRecordedBytes()
  {
    HttpPullCaptureJob hp = httpPullJob;
    if (hp != null) return hp.getBytesWritten();
    synchronized (caplock)
    {
      return currRecordedBytes;
    }
  }

  public boolean isLoaded()
  {
    return pHandle != 0;
  }

  public void loadDevice() throws EncodingException
  {
    if (Sage.DBG) System.out.println("Loading HDHomeRun capture device ("+ getName() + ")");
    pHandle = createEncoder0(captureDeviceName, captureDeviceNum);

    // this is to clear the current crossbar so activate actually sets it
    activateInput(null);
    activateInput(getDefaultInput());

    Sage.put(MMC.MMC_KEY + '/' + MMC.LAST_ENCODER_NAME, getName());
  }

  public void addInputs()
  {
    // TODO: should we be even more future-proof and poll the device for inputs?
    // as of now, only one input per "device", the digital tuner
    ensureInputExists(CaptureDeviceInput.DIGITAL_TUNER_CROSSBAR_INDEX, 0);
  }

  public void startEncoding(CaptureDeviceInput cdi, String encodeFile, String channel) throws EncodingException
  {
    if (Sage.DBG) System.out.println("startEncoding for "+ getName() +", file=" + encodeFile + ", chan=" + channel);
    currRecordedBytes = 0;
    if (cdi != null)
      activateInput(cdi);
    if (channel != null)
    {
      // If the channel is nothing, then use the last one. This happens with default live.
      if (channel.length() == 0)
        channel = activeSource.getChannel();
      tuneToChannel(channel);
    }

    // ---- ATSC 3.0 HTTP-pull path (HEVC non-DRM only) ----
    // Routes around libhdhomerun (which refuses ATSC 3.0 vchannels on FLEX 4K
    // firmware). We pull the device's /auto/v<channel> HTTP endpoint via
    // ffmpeg -c copy. Disk file is identical-shape MPEG-TS; demuxer Phase 1
    // covers HEVC stream_type 0x24 + AC-4 0xAC.
    if (encodeFile != null && channel != null && channel.length() > 0
        && Sage.getBoolean("hdhr/atsc3_http_pull_enabled", true))
    {
      String host = resolveLineupHost();
      if (host != null)
      {
        HDHomeRunLineup lu = HDHomeRunLineup.forHost(host);
        if (lu.isHevcNonDrm(channel))
        {
          String url = lu.getHttpUrl(channel);
          if (url != null)
          {
            if (Sage.DBG) System.out.println("HDHR ATSC3 HTTP-pull: chan=" + channel
                + " url=" + url + " file=" + encodeFile);
            HttpPullCaptureJob job = new HttpPullCaptureJob(url, encodeFile);
            httpPullJob = job;
            recFilename = encodeFile;
            recStart = Sage.time();
            freshCapture = true;
            httpPullThread = new Thread(job, getName() + "-HttpPull");
            httpPullThread.setDaemon(true);
            httpPullThread.start();
            return;
          }
        }
      }
    }

    currentlyRecordingBufferSize = recordBufferSize;
    // new file
    setupEncoding0(pHandle, encodeFile, currentlyRecordingBufferSize);
    recFilename = encodeFile;
    recStart = Sage.time();
    freshCapture = true;

    // Set the broadcast standard correctly
    activeSource.setBroadcastStd(getBroadcastStandard0(pHandle));

    // Start the encoding thread
    stopCapture = false;
    capThread = new Thread(this, getName() + "-Encoder");
    capThread.setPriority(Thread.MAX_PRIORITY);
    capThread.start();

    // Initially this may not be set so do so now.
    if ((activeSource.getChannel() != null && activeSource.getChannel().length() == 0) ||
        activeSource.getChannel().equals("0"))
    {
      activeSource.setLastChannel(getChannel());
      activeSource.writePrefs();
    }
  }

  public void stopEncoding()
  {
    if (Sage.DBG) System.out.println("stopEncoding for "+ getName());
    recFilename = null;
    recStart = 0;

    HttpPullCaptureJob hp = httpPullJob;
    if (hp != null)
    {
      hp.requestStop();
      Thread t = httpPullThread;
      if (t != null)
      {
        try { t.join(5000); } catch (Exception e) {}
      }
      httpPullJob = null;
      httpPullThread = null;
      return;
    }

    stopCapture = true;
    if (capThread != null)
    {
      if (Sage.DBG) System.out.println("Waiting for capture thread to terminate");
      try
      {
        capThread.join(5000);
      }catch (Exception e){}
    }
    capThread = null;
  }

  public void switchEncoding(String switchFile, String channel) throws EncodingException
  {
    if (Sage.DBG) System.out.println("switchEncoding for "+ getName() + ", file=" + switchFile + ", chan=" + channel);
    //		if (channel != null)
    //			tuneToChannel(channel);
    freshCapture = false;

    HttpPullCaptureJob hp = httpPullJob;
    if (hp != null)
    {
      hp.switchFile(switchFile);
      synchronized (caplock)
      {
        recFilename = switchFile;
        recStart = Sage.time();
        currRecordedBytes = 0;
        caplock.notifyAll();
      }
      return;
    }

    synchronized (caplock)
    {
      nextRecFilename = switchFile;
      while (nextRecFilename != null)
      {
        // Wait for the new filename to be switched to and then return after that
        try{caplock.wait(10);}catch (Exception e){}
      }
    }
  }

  public void run()
  {
    boolean logCapture = Sage.getBoolean("debug_capture_progress", false);
    if (Sage.DBG) System.out.println("Starting capture thread for " + getName());
    long addtlBytes;
    while (!stopCapture)
    {
      if (nextRecFilename != null)
      {
        try
        {
          switchEncoding0(pHandle, nextRecFilename);
        }
        catch (EncodingException e)
        {
          System.out.println("ERROR Switching encoder file:" + e.getMessage());
        }
        synchronized (caplock)
        {
          recFilename = nextRecFilename;
          nextRecFilename = null;
          recStart = Sage.time();
          currRecordedBytes = 0;
          // There may be a thread waiting on this state change to occur
          caplock.notifyAll();
        }
      }
      try
      {
        addtlBytes = getOutputData0(pHandle);
      }
      catch (EncodingException e)
      {
        System.out.println("ERROR Eating encoder data:" + e.getMessage());
        addtlBytes = 0;
      }
      synchronized (caplock)
      {
        currRecordedBytes = addtlBytes;
      }

      if (logCapture)
        System.out.println(getName() + ": " + recFilename + " " + currRecordedBytes);

      // sleep for a bit to allow some data to accumulate
      try {
        Thread.sleep(125);	// might throw InterruptedException (?)
      } catch (Throwable t) {}
    }
    closeEncoding0(pHandle);
    if (Sage.DBG) System.out.println(getName() + " capture thread terminating");
  }

  public CaptureDeviceInput activateInput(CaptureDeviceInput activateMe) throws EncodingException
  {
    // NOTE: This was removed so we always set the input before we start capture. There was a bug where the audio was
    // getting cut out of some recordings due to the audio standard not being set correctly. This will hopefully
    // resolve that.
    if (activeSource == activateMe && !stopCapture) return activeSource;

    super.activateInput(activateMe);
    if (activeSource != null && isLoaded())
    {
      synchronized (devlock)
      {
        // TODO: remove type/index since we only have one input
        setInput0(pHandle, activeSource.getType(), activeSource.getIndex(), activeSource.getTuningMode(),
            MMC.getInstance().getCountryCode(),
            sage.media.format.MediaFormat.MPEG2_PS.equals(activeSource.getEncoderMediaFormat().getFormatName()) ? 1 : 0);
      }
    }
    return activeSource;
  }

  protected boolean doTuneChannel(String tuneString, boolean autotune)
  {
    if (Sage.DBG) System.out.println("HDHRCaptureDevice->doTuneChannel("+tuneString+","+(autotune?"true":"false")+")");

    // Clean any bad chars from the tuneString
    if (Sage.getBoolean("mmc/remove_non_numeric_characters_from_channel_change_strings", false))
    {
      StringBuffer sb = new StringBuffer();
      for (int i = 0; i < tuneString.length(); i++)
      {
        char c = tuneString.charAt(i);
        if ((c >= '0' && c <= '9') || c == '.' || c == '-')
          sb.append(c);
      }
      tuneString = sb.toString();
    }
    boolean rv = false;
    try
    {
      // analog, digital or FM tuner type
      // FIXME: do we really need external tuning for this device?
      if ((activeSource.getType() == 1) || (activeSource.getType() == 100) || (activeSource.getType() == 99))
      {
        synchronized (devlock)
        {
          try
          {
            // FIXME: use scan channel interface to scan for a valid frequency
            if (autotune)
              rv = setChannel0(pHandle, tuneString, true, getTunerStreamType());
            else
              setChannel0(pHandle, tuneString, false, getTunerStreamType());
          }
          catch (EncodingException e)
          {
            System.out.println("ERROR setting channel for "+ getName() + ":" + e.getMessage());
          }
        }
        if (activeSource.getTuningPlugin().length() > 0 &&
            (Sage.getBoolean(MMC.MMC_KEY + '/' + MMC.ALWAYS_TUNE_CHANNEL, true) ||
                !tuneString.equals(getChannel())))
        {
          doPluginTune(tuneString);
        }
      }
      else // must be external tuner type
      {
        if (activeSource.getTuningPlugin().length() > 0 &&
            (Sage.getBoolean(MMC.MMC_KEY + '/' + MMC.ALWAYS_TUNE_CHANNEL, true) ||
                !tuneString.equals(getChannel())))
        {
          doPluginTune(tuneString);
        }
        rv = true;
      }
    }
    catch (NumberFormatException e){}
    return rv;
  }

  protected boolean doScanChannel(String tuneString)
  {
    return doTuneChannel( tuneString, true );
  }

  protected String doScanChannelInfo(String tuneString)
  {
    String country = Sage.get("mmc/country", Sage.rez("default_country") );
    int country_code = TVTuningFrequencies.getCountryCode(country);
    String country_region = country;
    if ("DVB-T".equals(activeSource.getBroadcastStandard()))
      country_region += TVTuningFrequencies.doesCountryHaveDVBTRegions(country_code) ? "-" +
          Sage.get("mmc/dvbt_region", "" ) : "";
          else if ("DVB-S".equals(activeSource.getBroadcastStandard()))
            country_region += TVTuningFrequencies.doesCountryHaveDVBSRegions(country_code) ? "-" +
                Sage.get("mmc/dvbs_region", "" ) : "";
                else if ("DVB-C".equals(activeSource.getBroadcastStandard()))
                  country_region += TVTuningFrequencies.doesCountryHaveDVBCRegions(country_code) ? "-" +
                      Sage.get("mmc/dvbc_region", "" ) : "";

                      return scanChannel0( pHandle, tuneString, country_region, getTunerStreamType() /* this is ultimately ignored, not sure why it's here */ );
                      //		return (doTuneChannel( tuneString, true ) ? "1" : "");
  }

  private int getTunerStreamType()
  {
    if (activeSource != null && activeSource.getType() == CaptureDeviceInput.DIGITAL_TUNER_CROSSBAR_INDEX)
    {
      if (Sage.getBoolean("mmc/encode_digital_tv_as_program_stream", true))
        return MediaFile.MEDIASUBTYPE_MPEG2_PS;
      else
        return MediaFile.MEDIASUBTYPE_MPEG2_TS;
    }
    else
      return 0;
  }

  public int getSignalStrength()
  {
    return getSignalStrength0(pHandle);
  }

  public void setEncodingQuality(String encodingName)
  {
  }

  // Returns true for CaptureDevice implementations that can do data scanning on the specified input
  public boolean doesDataScanning()
  {
    return true;
  }
  // (for EPG data at this time), the input will be from this capture device AND if that input actually wants
  // a data scan to be performed
  public boolean wantsDataScanning(CaptureDeviceInput cdi)
  {
    return cdi != null && requestedDataScanCDIs.contains(cdi);
  }
  // When we're in data scanning mode we're writing to the null file
  public void enableDataScanning(CaptureDeviceInput cdi) throws EncodingException
  {
    if (wantsDataScanning(cdi))
    {
      setFilterEnable0(pHandle, false);
      startEncoding(cdi, null, null);
      inDataScanMode = true;
    }
  }
  public void disableDataScanning()
  {
    if (inDataScanMode)
    {
      inDataScanMode = false;
      stopEncoding();
    }
    setFilterEnable0(pHandle, true);
  }
  public boolean isDataScanning()
  {
    return inDataScanMode;
  }
  public boolean requestDataScan(CaptureDeviceInput cdi)
  {
    return requestedDataScanCDIs.add(cdi);
  }
  public void cancelDataScanRequest(CaptureDeviceInput cdi)
  {
    requestedDataScanCDIs.remove(cdi);
  }

  // non-zero if device was detected
  protected native int isCaptureDeviceValid0(String devName, int devNum);
  protected native long createEncoder0(String deviceName, int deviceNum) throws EncodingException;
  protected native void destroyEncoder0(long encoderPtr);

  protected native boolean setupEncoding0(long encoderPtr, String filename, long bufferSize) throws EncodingException;
  protected native boolean switchEncoding0(long encoderPtr, String filename) throws EncodingException;
  protected native boolean closeEncoding0(long encoderPtr);

  // THIS SHOULD BE CONTINUOSLY CALLED FROM ANOTHER THREAD AFTER WE START ENCODING
  // This returns the amount of data just eaten, not the running total
  protected native long eatEncoderData0(long encoderPtr) throws EncodingException; //it's processed data bytes
  protected native long getOutputData0(long encoderPtr) throws EncodingException;  //it's fwrite data bytes

  // turn PID filter on/off
  protected native void setFilterEnable0(long encoderPtr, boolean enable);

  protected native boolean setChannel0(long encoderPtr, String chan, boolean autotune, int streamFormat) throws EncodingException;
  protected native String scanChannel0(long encoderPtr, String channelName, String region, int streamFormat);

  // Add another property to set the index we use in Linux for this
  protected native boolean setInput0(long encoderPtr, int inputType, int inputIndex, String sigFormat, int countryCode,
      int videoFormat) throws EncodingException;

  protected native String getBroadcastStandard0(long encoderPtr);
  protected native int getSignalStrength0(long encoderPtr);

  public String getDeviceClass()
  {
    return "HDHomeRun";
  }
  public boolean isFreshCapture()
  {
    return freshCapture;
  }
  protected long pHandle;

  protected String nextRecFilename;

  protected Object caplock = new Object();
  protected Object devlock = new Object();
  protected boolean stopCapture = true;
  protected Thread capThread;
  protected long currRecordedBytes;
  protected boolean freshCapture;

  private boolean inDataScanMode = false;
  private java.util.Set requestedDataScanCDIs = new java.util.HashSet();

  /** Active ATSC 3.0 HTTP-pull capture job (null when on libhdhomerun path). */
  private volatile HttpPullCaptureJob httpPullJob;
  private volatile Thread             httpPullThread;

  /**
   * Resolve the HDHomeRun's HTTP host for /lineup.json.
   *
   * Precedence (user is NOT expected to configure anything here):
   *   1. Per-device-name property: hdhr/lineup_host_&lt;captureDeviceName&gt;
   *   2. Per-device-id property:   hdhr/lineup_host_&lt;hexid&gt;  (auto-cached
   *      after a successful UDP discovery — supports multi-antenna setups
   *      where each FLEX 4K points at a different market).
   *   3. Parse "@ip" from device name (legacy device naming).
   *   4. UDP broadcast discovery on port 65001 (libhdhomerun protocol),
   *      results written back to property #2 for next time.
   *   5. Global hdhr/lineup_host (last-resort manual override; not advertised).
   *
   * Returns null if no host can be determined (caller falls back to libhdhr).
   */
  private String resolveLineupHost()
  {
    // 1. Per-device-name override
    String override = Sage.get("hdhr/lineup_host_" + captureDeviceName, null);
    if (override != null && override.length() > 0) return override;

    // 2. Per-device-id (hex) cached entry
    String hexId = parseHexDeviceId(captureDeviceName);
    if (hexId != null)
    {
      String byId = Sage.get("hdhr/lineup_host_" + hexId, null);
      if (byId != null && byId.length() > 0) return byId;
    }

    // 3. Legacy "@ip" embedded in device name
    if (captureDeviceName != null)
    {
      int at = captureDeviceName.indexOf('@');
      if (at > 0 && at + 1 < captureDeviceName.length())
        return captureDeviceName.substring(at + 1);
    }

    // 4. UDP discovery — automatic, no user action required
    if (hexId != null)
    {
      String ip = sage.hdhr.HDHomeRunDiscover.findIp(hexId);
      if (ip != null && ip.length() > 0)
      {
        // Cache for future tunes so we don't broadcast every recording start
        Sage.put("hdhr/lineup_host_" + hexId, ip);
        if (Sage.DBG) System.out.println("HDHomeRunCaptureDevice: auto-discovered "
            + "lineup host " + ip + " for device " + hexId + " (cached)");
        return ip;
      }
    }

    // 5. Last-resort global override (undocumented; mainly for tests)
    override = Sage.get("hdhr/lineup_host", null);
    if (override != null && override.length() > 0) return override;

    return null;
  }

  /**
   * Extract the 8-hex-digit device id from a Sage HDHR device name. Handles
   * both the native-discovered form "HDHomeRun <hexid> Tuner N" and the
   * legacy/network-tuner form "...<HEXID>-N..." or "<HEXID>@<ip>".
   * Returns lower-case 8-char hex, or null if no id present.
   */
  private static String parseHexDeviceId(String devName)
  {
    if (devName == null) return null;
    // Match the first run of >=8 hex chars that aren't part of a longer word.
    java.util.regex.Matcher m = HEX_ID_PATTERN.matcher(devName);
    return m.find() ? m.group(1).toLowerCase() : null;
  }

  private static final java.util.regex.Pattern HEX_ID_PATTERN =
      java.util.regex.Pattern.compile("\\b([0-9A-Fa-f]{8})\\b");
}
