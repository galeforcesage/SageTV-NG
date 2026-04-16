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
package sage.commercial;

import sage.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.text.SimpleDateFormat;

/**
 * Singleton service that manages commercial detection jobs.
 * Launches comskip (or other external engines) against recordings,
 * manages concurrency, and converts output to EDL format.
 *
 * Config keys (all under Sage.properties):
 *   commercial_detection/enabled              - master switch (default false)
 *   commercial_detection/run_on_recording_start - auto-run when recording starts (default true)
 *   commercial_detection/max_concurrent_jobs   - thread pool size (default 1)
 *   commercial_detection/engine               - "comskip" or "external" (default "comskip")
 *   commercial_detection/comskip_path          - path to comskip binary (default "/opt/sagetv/server/comskip")
 *   commercial_detection/comskip_ini           - path to comskip.ini (default "")
 *   commercial_detection/external_engine_path  - path to custom engine binary (default "")
 *   commercial_detection/output_formats        - comma-separated: edl,vprj,csv (default "edl")
 *   commercial_detection/post_recording_delay_ms - delay after recording stops before running (default 5000)
 */
public class CommercialDetectionManager
{
  private static CommercialDetectionManager instance;

  private ExecutorService threadPool;
  private final Map<Integer, CommercialDetectionJob> activeJobs = new ConcurrentHashMap<>();
  private final ConcurrentLinkedDeque<Integer> pendingQueue = new ConcurrentLinkedDeque<>();
  private volatile boolean restrictedTimeRetryScheduled;

  private CommercialDetectionManager()
  {
    int maxJobs = Sage.getInt("commercial_detection/max_concurrent_jobs", 1);
    threadPool = Executors.newFixedThreadPool(maxJobs, r -> {
      Thread t = new Thread(r, "CommercialDetect-Worker");
      t.setDaemon(true);
      t.setPriority(Thread.MIN_PRIORITY);
      return t;
    });
    if (Sage.DBG) System.out.println("CommercialDetectionManager initialized, maxConcurrent=" + maxJobs);
  }

  public static synchronized CommercialDetectionManager getInstance()
  {
    if (instance == null)
      instance = new CommercialDetectionManager();
    return instance;
  }

  public boolean isEnabled()
  {
    return Sage.getBoolean("commercial_detection/enabled", false);
  }

  // ── Recording lifecycle hooks (called from Seeker) ──

  /**
   * Called when a recording starts. If enabled and run_on_recording_start is true,
   * launches a detection job that follows the growing file.
   */
  public void onRecordingStarted(MediaFile mf)
  {
    if (!isEnabled()) return;
    if (!Sage.getBoolean("commercial_detection/run_on_recording_start", true)) return;
    if (mf == null) return;
    if (mf.isAnyLiveStream()) return;

    // Channel/category skip filtering (from tmiranda CommercialDetector pattern)
    if (shouldSkipChannel(mf) || shouldSkipCategory(mf))
    {
      if (Sage.DBG) System.out.println("CommercialDetectionManager: Skipping — channel or category in skip list");
      return;
    }

    File recFile = mf.getRecordingFile();
    if (recFile == null) return;

    if (Sage.DBG) System.out.println("CommercialDetectionManager: Recording started, queueing job for " + recFile);
    submitJob(mf, recFile, true);
  }

  /**
   * Called when a recording stops. Signals the active job that the file is no longer growing.
   * If no job was running (e.g. started before enabled), optionally starts one now.
   */
  public void onRecordingStopped(MediaFile mf)
  {
    if (mf == null) return;
    int id = mf.getID();
    CommercialDetectionJob job = activeJobs.get(id);
    if (job != null)
    {
      if (Sage.DBG) System.out.println("CommercialDetectionManager: Recording stopped, signaling job for MF " + id);
      job.setRecordingActive(false);
      // For live_tv mode: comskip is processing the growing file natively via
      // BuildCommListAsYouGo(). When the file stops growing, comskip exhausts its
      // live_tv_retries and exits with a complete EDL — no cancel/delete/re-run needed.
    }
    else if (isEnabled())
    {
      // Channel/category skip filtering (from tmiranda CommercialDetector pattern)
      if (shouldSkipChannel(mf) || shouldSkipCategory(mf))
      {
        if (Sage.DBG) System.out.println("CommercialDetectionManager: Skipping — channel or category in skip list");
        // Still try to start pending jobs since a recording just freed resources
        processPendingQueue();
        return;
      }

      File recFile = mf.getRecordingFile();
      if (recFile == null || !recFile.exists()) return;

      // Check scheduling constraints (restricted time, intelligent scheduling)
      if (inRestrictedTime() || (isIntelligentSchedulingEnabled() && !isEnoughTimeAvailable()))
      {
        if (Sage.DBG) System.out.println("CommercialDetectionManager: Adding to pending queue due to scheduling constraints, MF " + id);
        if (!pendingQueue.contains(id))
          pendingQueue.add(id);
        // If deferred due to restricted time, schedule a one-shot retry at the next hour boundary
        if (inRestrictedTime())
          scheduleRestrictedTimeRetry();
        return;
      }

      int delayMs = Sage.getInt("commercial_detection/post_recording_delay_ms", 5000);
      if (Sage.DBG) System.out.println("CommercialDetectionManager: No active job, scheduling post-recording detect for MF " + id + " in " + delayMs + "ms");
      Pooler.execute(new Runnable() {
        public void run() {
          try { Thread.sleep(delayMs); } catch (InterruptedException e) { return; }
          if (!activeJobs.containsKey(id))
            submitJob(mf, recFile, false);
        }
      });
    }
  }

  // ── Public API for STV / CommercialSkipAPI ──

  /**
   * Manually run commercial detection on a completed file.
   */
  public void runNow(MediaFile mf)
  {
    if (mf == null) return;
    File recFile = mf.getRecordingFile();
    if (recFile == null || !recFile.exists()) return;

    // Cancel any existing job first
    cancelJob(mf);

    boolean isRecording = mf.isRecording();
    if (Sage.DBG) System.out.println("CommercialDetectionManager: runNow for MF " + mf.getID() + " (recording=" + isRecording + ")");
    submitJob(mf, recFile, isRecording);
  }

  /**
   * Clear commercial markers for the given media file (deletes EDL + secondary formats).
   */
  public void clearMarkers(MediaFile mf)
  {
    if (mf == null) return;
    cancelJob(mf);
    File recFile = mf.getFile(0);
    if (recFile == null) return;

    EdlWriter.deleteEdl(recFile);
    deleteSecondaryFormats(recFile);
    if (Sage.DBG) System.out.println("CommercialDetectionManager: Cleared markers for MF " + mf.getID());
  }

  /**
   * Check if commercial markers exist for the given media file.
   */
  public boolean hasMarkers(MediaFile mf)
  {
    if (mf == null) return false;
    File recFile = mf.getFile(0);
    if (recFile == null) return false;
    File edlFile = EdlWriter.getEdlFile(recFile);
    return edlFile.exists() && edlFile.length() > 0;
  }

  /**
   * Get commercial segments for the given media file.
   */
  public java.util.ArrayList<EdlWriter.Segment> getSegments(MediaFile mf)
  {
    if (mf == null) return new java.util.ArrayList<>();
    File recFile = mf.getFile(0);
    if (recFile == null) return new java.util.ArrayList<>();
    return EdlWriter.readEdl(recFile);
  }

  /**
   * Check if a job is currently running for the given media file.
   */
  public boolean isJobRunning(MediaFile mf)
  {
    return mf != null && activeJobs.containsKey(mf.getID());
  }

  /**
   * Check if a job is running or queued (pending) for the given media file.
   */
  public boolean isJobRunningOrPending(MediaFile mf)
  {
    if (mf == null) return false;
    int id = mf.getID();
    return activeJobs.containsKey(id) || pendingQueue.contains(id);
  }

  // ── Config getters/setters for STV ──

  public String getComskipPath()
  {
    return Sage.get("commercial_detection/comskip_path", "/opt/sagetv/server/comskip");
  }

  public void setComskipPath(String path)
  {
    Sage.put("commercial_detection/comskip_path", path);
  }

  public String getComskipIni()
  {
    return Sage.get("commercial_detection/comskip_ini", "");
  }

  public void setComskipIni(String path)
  {
    Sage.put("commercial_detection/comskip_ini", path);
  }

  public String getOutputFormats()
  {
    return Sage.get("commercial_detection/output_formats", "edl");
  }

  public void setOutputFormats(String formats)
  {
    Sage.put("commercial_detection/output_formats", formats);
  }

  public String getEngine()
  {
    return Sage.get("commercial_detection/engine", "comskip");
  }

  public void setEngine(String engine)
  {
    Sage.put("commercial_detection/engine", engine);
  }

  public String getExternalRecordedArgs()
  {
    return Sage.get("commercial_detection/external_recorded_args", "{input} {output}");
  }

  public void setExternalRecordedArgs(String args)
  {
    Sage.put("commercial_detection/external_recorded_args", args);
  }

  public String getExternalLiveArgs()
  {
    return Sage.get("commercial_detection/external_live_args", "{input} {output}");
  }

  public void setExternalLiveArgs(String args)
  {
    Sage.put("commercial_detection/external_live_args", args);
  }

  public String getExternalEnginePath()
  {
    return Sage.get("commercial_detection/external_engine_path", "");
  }

  public void setExternalEnginePath(String path)
  {
    Sage.put("commercial_detection/external_engine_path", path);
  }

  // ── Channel / Category Skip Filtering (from tmiranda CommercialDetector) ──

  /**
   * Check if this MediaFile's channel is in the skip list.
   * Supports channel names, numbers, and numeric ranges (e.g. "5-10").
   * Based on tmiranda's plugin.skipThisChannel() pattern.
   */
  public boolean shouldSkipChannel(MediaFile mf)
  {
    String skipList = Sage.get("commercial_detection/skip_channels", "");
    if (skipList.isEmpty()) return false;

    Airing airing = mf.getContentAiring();
    if (airing == null) return false;

    String channelName = airing.getChannelName();
    String channelNum = "";
    try
    {
      Channel ch = airing.getChannel();
      if (ch != null)
      {
        channelNum = ch.getNumber(0);
      }
    }
    catch (Exception e) { /* ignore */ }

    String[] skipArray = skipList.split(",");
    for (String skip : skipArray)
    {
      skip = skip.trim();
      if (skip.isEmpty()) continue;

      // Check for numeric range (e.g. "5-10")
      String[] rangeParts = skip.split("-");
      if (rangeParts.length == 2)
      {
        try
        {
          int first = Integer.parseInt(rangeParts[0].trim());
          int last = Integer.parseInt(rangeParts[1].trim());
          if (last < first) { int t = first; first = last; last = t; }
          try
          {
            int thisChannel = Integer.parseInt(channelNum);
            if (thisChannel >= first && thisChannel <= last)
              return true;
          }
          catch (NumberFormatException e) { /* not a numeric channel */ }
          continue;
        }
        catch (NumberFormatException e) { /* not a range, treat as literal */ }
      }

      // Check name or number match
      if (channelName.equalsIgnoreCase(skip) || channelNum.equalsIgnoreCase(skip))
        return true;
    }
    return false;
  }

  /**
   * Check if this MediaFile's category is in the skip list.
   * Based on tmiranda's plugin.skipThisCategory() pattern.
   */
  public boolean shouldSkipCategory(MediaFile mf)
  {
    String skipList = Sage.get("commercial_detection/skip_categories", "");
    if (skipList.isEmpty()) return false;

    Show show = mf.getShow();
    if (show == null) return false;

    // Build lowercase list of categories to skip
    String[] skipArray = skipList.split(",");
    java.util.ArrayList<String> skips = new java.util.ArrayList<>();
    for (String s : skipArray)
    {
      String trimmed = s.trim().toLowerCase();
      if (!trimmed.isEmpty()) skips.add(trimmed);
    }

    // Collect all categories/subcategories from the show
    String[] showCats = show.getCategories();
    if (showCats != null)
    {
      for (String cat : showCats)
      {
        if (cat == null) continue;
        // Some categories are "House / Garden" — split on "/"
        for (String part : cat.split("/"))
        {
          String trimmed = part.trim().toLowerCase();
          if (!trimmed.isEmpty() && skips.contains(trimmed))
            return true;
        }
      }
    }
    return false;
  }

  public String getSkipChannels()
  {
    return Sage.get("commercial_detection/skip_channels", "");
  }

  public void setSkipChannels(String channels)
  {
    Sage.put("commercial_detection/skip_channels", channels);
  }

  public String getSkipCategories()
  {
    return Sage.get("commercial_detection/skip_categories", "");
  }

  public void setSkipCategories(String categories)
  {
    Sage.put("commercial_detection/skip_categories", categories);
  }

  // ── Restricted Times (from tmiranda CommercialDetector) ──

  /**
   * Check if the current hour is in the restricted times list.
   * Restricted times are stored as comma-separated hour strings like "00,01,14,22".
   * Based on tmiranda's ComskipManager.inRestrictedTime() pattern.
   */
  public boolean inRestrictedTime()
  {
    String restrictedStr = Sage.get("commercial_detection/restricted_times", "");
    if (restrictedStr.isEmpty()) return false;

    String[] hours = restrictedStr.split(",");
    String currentHour = new SimpleDateFormat("H").format(new java.util.Date());

    for (String hour : hours)
    {
      String h = hour.trim();
      if (h.length() > 2) h = h.substring(0, 2);  // handle "00:00 - 00:59" format
      h = h.trim();
      if (currentHour.equals(h))
        return true;
    }
    return false;
  }

  public String getRestrictedTimes()
  {
    return Sage.get("commercial_detection/restricted_times", "");
  }

  public void setRestrictedTimes(String times)
  {
    Sage.put("commercial_detection/restricted_times", times);
  }

  // ── Intelligent Scheduling (from tmiranda CommercialDetector) ──

  public boolean isIntelligentSchedulingEnabled()
  {
    return Sage.getBoolean("commercial_detection/intelligent_scheduling", false);
  }

  public void setIntelligentSchedulingEnabled(boolean enabled)
  {
    Sage.putBoolean("commercial_detection/intelligent_scheduling", enabled);
  }

  /**
   * Check if it's safe to start a job now — no recordings in progress
   * and none starting soon. Based on tmiranda's isEnoughTime() pattern.
   */
  public boolean isEnoughTimeAvailable()
  {
    try
    {
      MediaFile[] recording = Seeker.getInstance().getCurrRecordFiles();
      if (recording != null && recording.length > 0)
      {
        if (Sage.DBG) System.out.println("CommercialDetectionManager: Recording in progress, not enough time");
        return false;
      }
    }
    catch (Exception e)
    {
      if (Sage.DBG) System.out.println("CommercialDetectionManager: Error checking recordings: " + e);
    }
    return true;
  }

  // ── Per-Channel/Show Comskip INI (from tmiranda QueuedJob.getComskipIni()) ──

  /**
   * Resolve the comskip.ini to use for this MediaFile.
   * Lookup order:
   * 1. ShowName.ini in the ini directory (spaces removed from show title)
   * 2. ChannelName.ini in the ini directory
   * 3. Profile-based INI (base + content-type delta), if profiles enabled
   * 4. Default configured ini path
   */
  public String resolveComskipIni(MediaFile mf)
  {
    String iniDir = Sage.get("commercial_detection/ini_directory", "");
    if (!iniDir.isEmpty())
    {
      File dir = new File(iniDir);
      if (dir.exists() && dir.isDirectory())
      {
        // Try show name
        Show show = mf.getShow();
        if (show != null)
        {
          String showName = show.getTitle();
          if (showName != null && !showName.isEmpty())
          {
            File showIni = new File(dir, showName.replaceAll("\\s+", "") + ".ini");
            if (showIni.exists())
            {
              if (Sage.DBG) System.out.println("CommercialDetectionManager: Using show-specific ini: " + showIni);
              return showIni.getAbsolutePath();
            }
          }
        }

        // Try channel name
        Airing airing = mf.getContentAiring();
        if (airing != null)
        {
          String channelName = airing.getChannelName();
          if (channelName != null && !channelName.isEmpty())
          {
            File channelIni = new File(dir, channelName + ".ini");
            if (channelIni.exists())
            {
              if (Sage.DBG) System.out.println("CommercialDetectionManager: Using channel-specific ini: " + channelIni);
              return channelIni.getAbsolutePath();
            }
          }
        }
      }
    }

    // Profile-based INI: detect content type and merge base + delta
    if (isProfilesEnabled())
    {
      String profileIni = buildProfileIni(mf);
      if (profileIni != null)
        return profileIni;
    }

    // Fall back to configured default
    return Sage.get("commercial_detection/comskip_ini", "");
  }

  // ── Comskip Profile System ──
  // Profiles use a base INI + content-type delta overlay:
  //   0 = comskip_base.ini (adaptive defaults, works alone)
  //   1 = comskip_sports.delta.ini (conservative bias for sports)
  //   2 = comskip_news.delta.ini (aggressive bias for news/talk)
  //   3 = comskip_hdhomerun.delta.ini (OTA signal tuning)
  // Effective INI = base(0) + delta(X). Later values override earlier ones.

  /**
   * Content type detected from SageTV metadata.
   */
  public enum ContentProfile
  {
    DEFAULT,   // base only
    SPORTS,    // base + sports delta
    NEWS       // base + news delta
  }

  public boolean isProfilesEnabled()
  {
    return Sage.getBoolean("commercial_detection/profiles_enabled", false);
  }

  public void setProfilesEnabled(boolean enabled)
  {
    Sage.putBoolean("commercial_detection/profiles_enabled", enabled);
  }

  public String getProfileDirectory()
  {
    return Sage.get("commercial_detection/profile_directory", "/opt/sagetv/server/comskip_profiles");
  }

  public void setProfileDirectory(String dir)
  {
    Sage.put("commercial_detection/profile_directory", dir);
  }

  /**
   * Detect the content profile for a MediaFile based on its category metadata.
   */
  public ContentProfile detectContentProfile(MediaFile mf)
  {
    if (mf == null) return ContentProfile.DEFAULT;
    Show show = mf.getShow();
    if (show == null) return ContentProfile.DEFAULT;

    // Check all categories for sports or news keywords
    String[] categories = show.getCategories();
    if (categories != null)
    {
      for (String cat : categories)
      {
        if (cat == null) continue;
        String lower = cat.toLowerCase();
        // Sports detection
        if (lower.contains("sport") || lower.contains("football") || lower.contains("basketball") ||
            lower.contains("baseball") || lower.contains("hockey") || lower.contains("soccer") ||
            lower.contains("tennis") || lower.contains("golf") || lower.contains("racing") ||
            lower.contains("boxing") || lower.contains("wrestling") || lower.contains("mma") ||
            lower.contains("olympics") || lower.equals("athletic event"))
        {
          return ContentProfile.SPORTS;
        }
        // News detection
        if (lower.contains("news") || lower.contains("public affairs") ||
            lower.contains("newsmagazine") || lower.contains("interview") ||
            lower.contains("talk") || lower.contains("politics") ||
            lower.contains("business") || lower.equals("debate"))
        {
          return ContentProfile.NEWS;
        }
      }
    }

    return ContentProfile.DEFAULT;
  }

  /**
   * Check if the recording source is an HDHomeRun tuner.
   */
  private boolean isHDHomeRunSource(MediaFile mf)
  {
    String encoder = mf.getEncodedBy();
    return encoder != null && encoder.toLowerCase().contains("hdhomerun");
  }

  /**
   * Build an effective INI file by merging base + content delta [+ HDHomeRun delta].
   * Returns the path to a temp INI file, or null if profiles are not configured.
   * Comskip INI works like properties: later values override earlier ones when concatenated.
   */
  private String buildProfileIni(MediaFile mf)
  {
    String profileDir = getProfileDirectory();
    File dir = new File(profileDir);
    File baseIni = new File(dir, "comskip_base.ini");
    if (!baseIni.exists())
    {
      if (Sage.DBG) System.out.println("CommercialDetectionManager: Profile base ini not found: " + baseIni);
      return null;
    }

    ContentProfile profile = detectContentProfile(mf);
    boolean hdhr = isHDHomeRunSource(mf);

    // If DEFAULT and not HDHomeRun, just use base directly (no temp file needed)
    if (profile == ContentProfile.DEFAULT && !hdhr)
    {
      if (Sage.DBG) System.out.println("CommercialDetectionManager: Using base profile ini: " + baseIni);
      return baseIni.getAbsolutePath();
    }

    // Need to merge: build a temp file
    try
    {
      // Read base
      StringBuilder sb = new StringBuilder();
      sb.append(new String(java.nio.file.Files.readAllBytes(baseIni.toPath()), "UTF-8"));

      // Append content-type delta
      File deltaFile = null;
      if (profile == ContentProfile.SPORTS)
        deltaFile = new File(dir, "comskip_sports.delta.ini");
      else if (profile == ContentProfile.NEWS)
        deltaFile = new File(dir, "comskip_news.delta.ini");

      if (deltaFile != null && deltaFile.exists())
      {
        sb.append("\n");
        sb.append(new String(java.nio.file.Files.readAllBytes(deltaFile.toPath()), "UTF-8"));
        if (Sage.DBG) System.out.println("CommercialDetectionManager: Applying " + profile + " delta: " + deltaFile.getName());
      }

      // Append HDHomeRun delta if applicable
      if (hdhr)
      {
        File hdhrDelta = new File(dir, "comskip_hdhomerun.delta.ini");
        if (hdhrDelta.exists())
        {
          sb.append("\n");
          sb.append(new String(java.nio.file.Files.readAllBytes(hdhrDelta.toPath()), "UTF-8"));
          if (Sage.DBG) System.out.println("CommercialDetectionManager: Applying HDHomeRun delta");
        }
      }

      // Write merged ini to temp file
      File tempIni = File.createTempFile("comskip_profile_", ".ini");
      tempIni.deleteOnExit();
      try (FileWriter fw = new FileWriter(tempIni))
      {
        fw.write(sb.toString());
      }
      if (Sage.DBG) System.out.println("CommercialDetectionManager: Built profile ini [" + profile +
          (hdhr ? "+HDHR" : "") + "] -> " + tempIni.getAbsolutePath());
      return tempIni.getAbsolutePath();
    }
    catch (IOException e)
    {
      if (Sage.DBG) System.out.println("CommercialDetectionManager: Failed to build profile ini: " + e);
      return null;
    }
  }

  public String getComskipIniDirectory()
  {
    return Sage.get("commercial_detection/ini_directory", "");
  }

  public void setComskipIniDirectory(String dir)
  {
    Sage.put("commercial_detection/ini_directory", dir);
  }

  // ── Run Slow (from tmiranda CommercialDetector) ──

  public boolean isRunSlowEnabled()
  {
    return Sage.getBoolean("commercial_detection/run_slow", false);
  }

  public void setRunSlowEnabled(boolean enabled)
  {
    Sage.putBoolean("commercial_detection/run_slow", enabled);
  }

  // ── Live Detection (from tmiranda CommercialDetector start_imm) ──

  /**
   * When enabled, comskip runs immediately on the growing file as soon as
   * recording starts (tmiranda's "Start comskip as Soon as Recording Starts").
   * Comskip always does a full rewrite of the EDL, so when the recording finishes
   * the partial EDL is deleted and comskip re-runs on the complete file.
   */
  public boolean isLiveDetectionEnabled()
  {
    return Sage.getBoolean("commercial_detection/live_detection", false);
  }

  public void setLiveDetectionEnabled(boolean enabled)
  {
    Sage.putBoolean("commercial_detection/live_detection", enabled);
  }

  // ── Auto Skip During Playback (from JREkiwi ComskipPlayback) ──

  public boolean isAutoSkipEnabled()
  {
    return Sage.getBoolean("commercial_detection/auto_skip", false);
  }

  public void setAutoSkipEnabled(boolean enabled)
  {
    Sage.putBoolean("commercial_detection/auto_skip", enabled);
  }

  public int getAutoSkipDelayMs()
  {
    return Sage.getInt("commercial_detection/auto_skip_delay_ms", 0);
  }

  public void setAutoSkipDelayMs(int ms)
  {
    Sage.putInt("commercial_detection/auto_skip_delay_ms", ms);
  }

  /**
   * Check if the given playback position (in seconds) is inside a commercial segment.
   * Used by autoskip and OSD marker logic (from JREkiwi ComskipPlayback).
   */
  public boolean isInCommercial(MediaFile mf, double positionSeconds)
  {
    if (mf == null) return false;
    java.util.ArrayList<EdlWriter.Segment> segs = getSegments(mf);
    for (EdlWriter.Segment seg : segs)
    {
      if (positionSeconds >= seg.startSeconds && positionSeconds < seg.endSeconds)
        return true;
    }
    return false;
  }

  /**
   * Get the end position (seconds) of the commercial segment at the given position.
   * Returns -1 if not in a commercial. Used by autoskip to know where to seek.
   */
  public double getCommercialEndPosition(MediaFile mf, double positionSeconds)
  {
    if (mf == null) return -1;
    java.util.ArrayList<EdlWriter.Segment> segs = getSegments(mf);
    for (EdlWriter.Segment seg : segs)
    {
      if (positionSeconds >= seg.startSeconds && positionSeconds < seg.endSeconds)
        return seg.endSeconds;
    }
    return -1;
  }

  // ── Queue Management (from tmiranda ComskipManager queue pattern) ──

  /**
   * Process pending queue — try to start jobs that were deferred due to
   * restricted time or scheduling constraints.
   * Based on tmiranda's startMaxJobs() / RestartRestricted pattern.
   */
  private void processPendingQueue()
  {
    if (pendingQueue.isEmpty()) return;
    if (!isEnabled()) return;
    if (inRestrictedTime())
    {
      scheduleRestrictedTimeRetry();
      return;
    }
    if (isIntelligentSchedulingEnabled() && !isEnoughTimeAvailable()) return;

    int maxJobs = Sage.getInt("commercial_detection/max_concurrent_jobs", 1);

    Iterator<Integer> it = pendingQueue.iterator();
    while (it.hasNext() && activeJobs.size() < maxJobs)
    {
      int id = it.next();
      MediaFile mf = Wizard.getInstance().getFileForID(id);
      if (mf == null)
      {
        it.remove();
        continue;
      }
      File recFile = mf.getFile(0);
      if (recFile == null || !recFile.exists())
      {
        it.remove();
        continue;
      }
      if (!activeJobs.containsKey(id))
      {
        if (Sage.DBG) System.out.println("CommercialDetectionManager: Starting pending job for MF " + id);
        it.remove();
        submitJob(mf, recFile, false);
      }
    }
  }

  /**
   * Schedule a one-shot retry of the pending queue at the next hour boundary.
   * Replaces tmiranda's periodic RestartRestricted TimerTask — since we're inside
   * SageTV we only need to wake up when the restricted hour actually ends.
   */
  private void scheduleRestrictedTimeRetry()
  {
    if (restrictedTimeRetryScheduled || pendingQueue.isEmpty()) return;
    restrictedTimeRetryScheduled = true;
    // Calculate ms until the top of the next hour + 5s padding
    Calendar now = Calendar.getInstance();
    int minutesLeft = 60 - now.get(Calendar.MINUTE);
    long delayMs = (minutesLeft * 60L * 1000L) + 5000L;
    if (Sage.DBG) System.out.println("CommercialDetectionManager: Restricted time retry in " + minutesLeft + " min");
    Pooler.execute(new Runnable() {
      public void run() {
        try { Thread.sleep(delayMs); } catch (InterruptedException e) { return; }
        restrictedTimeRetryScheduled = false;
        processPendingQueue();
      }
    });
  }

  /**
   * Scan all TV recordings and queue those that don't have EDL files.
   * Based on tmiranda's ComskipManager.getMediaFilesWithout() / ScanAll pattern.
   * @return the number of files queued
   */
  public int scanAllRecordings()
  {
    int count = 0;
    MediaFile[] allFiles = Wizard.getInstance().getFiles();
    if (allFiles == null) return 0;

    for (MediaFile mf : allFiles)
    {
      if (mf == null || !mf.isTV()) continue;
      if (shouldSkipChannel(mf) || shouldSkipCategory(mf)) continue;

      File recFile = mf.getFile(0);
      if (recFile == null || !recFile.exists()) continue;

      // Skip if already has EDL
      File edlFile = EdlWriter.getEdlFile(recFile);
      if (edlFile.exists()) continue;

      // Skip if already running or queued
      int id = mf.getID();
      if (activeJobs.containsKey(id) || pendingQueue.contains(id)) continue;

      pendingQueue.add(id);
      count++;
    }
    if (Sage.DBG) System.out.println("CommercialDetectionManager: Scan complete, queued " + count + " files");
    // Kick off processing
    processPendingQueue();
    return count;
  }

  /**
   * Get count of jobs in the pending queue.
   */
  public int getQueueSize()
  {
    return pendingQueue.size();
  }

  /**
   * Get MediaFile IDs of all queued (pending) jobs.
   */
  public int[] getQueuedMediaFileIDs()
  {
    return pendingQueue.stream().mapToInt(Integer::intValue).toArray();
  }

  /**
   * Get count of currently running jobs.
   */
  public int getRunningCount()
  {
    return activeJobs.size();
  }

  /**
   * Get MediaFile IDs of all currently running jobs.
   */
  public int[] getRunningMediaFileIDs()
  {
    return activeJobs.keySet().stream().mapToInt(Integer::intValue).toArray();
  }

  /**
   * Clear the pending queue.
   */
  public void clearQueue()
  {
    pendingQueue.clear();
    if (Sage.DBG) System.out.println("CommercialDetectionManager: Queue cleared");
  }

  /**
   * Stop all currently running jobs.
   */
  public void stopAllJobs()
  {
    for (CommercialDetectionJob job : activeJobs.values())
    {
      job.cancel();
    }
    activeJobs.clear();
    if (Sage.DBG) System.out.println("CommercialDetectionManager: All jobs stopped");
  }

  // ── Internal ──

  private void submitJob(MediaFile mf, File recFile, boolean isRecording)
  {
    int id = mf.getID();
    if (activeJobs.containsKey(id))
    {
      if (Sage.DBG) System.out.println("CommercialDetectionManager: Job already running for MF " + id);
      return;
    }
    // Resolve per-channel/show ini (adapting tmiranda QueuedJob.getComskipIni() pattern)
    String iniPath = resolveComskipIni(mf);
    boolean runSlow = isRunSlowEnabled();
    // Live detection: run comskip immediately on growing file (tmiranda's start_imm)
    boolean live = isRecording && isLiveDetectionEnabled();
    CommercialDetectionJob job = new CommercialDetectionJob(mf, recFile, isRecording, iniPath, runSlow, live);
    activeJobs.put(id, job);
    pendingQueue.remove(id);
    threadPool.submit(job);
  }

  /**
   * Stop a running detection job for the given MediaFile.
   */
  public void stopJob(MediaFile mf)
  {
    if (mf == null) return;
    cancelJob(mf);
  }

  private void cancelJob(MediaFile mf)
  {
    int id = mf.getID();
    CommercialDetectionJob job = activeJobs.remove(id);
    if (job != null)
    {
      job.cancel();
      if (Sage.DBG) System.out.println("CommercialDetectionManager: Cancelled job for MF " + id);
    }
  }

  /**
   * Called by CommercialDetectionJob when it finishes.
   * Converts comskip native output to EDL if needed, then emits secondary formats.
   */
  void jobCompleted(MediaFile mf)
  {
    int id = mf.getID();
    activeJobs.remove(id);

    // Chain to next pending job (like tmiranda's ComskipManager.jobComplete → startFirstInQueue)
    processPendingQueue();

    File recFile = mf.getRecordingFile();
    if (recFile == null) return;

    // If using comskip engine, convert its native .edl output format
    // Comskip writes EDL natively when --output=edl is used, so just verify it exists
    File edlFile = EdlWriter.getEdlFile(recFile);
    if (!edlFile.exists())
    {
      if (Sage.DBG) System.out.println("CommercialDetectionManager: No EDL produced for MF " + id);
      return;
    }

    // Emit secondary output formats
    String formats = getOutputFormats();
    if (formats.contains("vprj"))
      emitVprj(recFile, EdlWriter.readEdl(recFile));
    if (formats.contains("csv"))
      emitCsv(recFile, EdlWriter.readEdl(recFile));

    if (Sage.DBG) System.out.println("CommercialDetectionManager: Job completed for MF " + id +
        ", EDL=" + edlFile.exists() + ", formats=" + formats);
  }

  private void deleteSecondaryFormats(File recFile)
  {
    String basePath = getBasePath(recFile);
    new File(basePath + ".vprj").delete();
    new File(basePath + ".csv").delete();
  }

  /**
   * Emit VideoReDo VPrj (Video Project) file from EDL segments.
   */
  private void emitVprj(File recFile, java.util.ArrayList<EdlWriter.Segment> segments)
  {
    if (segments.isEmpty()) return;
    try
    {
      File vprjFile = new File(getBasePath(recFile) + ".vprj");
      try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(vprjFile))))
      {
        pw.println("<VideoReDoProject>");
        pw.println("  <Filename>" + recFile.getAbsolutePath() + "</Filename>");
        for (EdlWriter.Segment seg : segments)
        {
          // VPrj uses 10MHz ticks
          long startTicks = (long)(seg.startSeconds * 10000000.0);
          long endTicks = (long)(seg.endSeconds * 10000000.0);
          pw.println("  <Cut><Start>" + startTicks + "</Start><End>" + endTicks + "</End></Cut>");
        }
        pw.println("</VideoReDoProject>");
      }
      if (Sage.DBG) System.out.println("CommercialDetectionManager: Wrote VPrj " + vprjFile);
    }
    catch (IOException e)
    {
      if (Sage.DBG) System.out.println("CommercialDetectionManager: Error writing VPrj: " + e);
    }
  }

  /**
   * Emit CSV file from EDL segments.
   */
  private void emitCsv(File recFile, java.util.ArrayList<EdlWriter.Segment> segments)
  {
    if (segments.isEmpty()) return;
    try
    {
      File csvFile = new File(getBasePath(recFile) + ".csv");
      try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(csvFile))))
      {
        pw.println("start_seconds,end_seconds,action");
        for (EdlWriter.Segment seg : segments)
        {
          pw.printf("%.3f,%.3f,%d%n", seg.startSeconds, seg.endSeconds, seg.action);
        }
      }
      if (Sage.DBG) System.out.println("CommercialDetectionManager: Wrote CSV " + csvFile);
    }
    catch (IOException e)
    {
      if (Sage.DBG) System.out.println("CommercialDetectionManager: Error writing CSV: " + e);
    }
  }

  private static String getBasePath(File recFile)
  {
    String path = recFile.getAbsolutePath();
    int dot = path.lastIndexOf('.');
    return dot > 0 ? path.substring(0, dot) : path;
  }
}
