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
package sage.api;

import sage.*;
import sage.commercial.*;

/**
 * API methods for commercial detection / skip functionality, callable from STV.
 */
public class CommercialSkipAPI {
  private CommercialSkipAPI() {}
  public static void init(Catbert.ReflectionFunctionTable rft)
  {
    rft.put(new PredefinedJEPFunction("CommercialSkip", "IsCommercialDetectionEnabled", true)
    {
      /**
       * Returns whether commercial detection is enabled globally.
       * @return true if commercial detection is enabled
       * @since 9.3
       *
       * @declaration public boolean IsCommercialDetectionEnabled();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return CommercialDetectionManager.getInstance().isEnabled();
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "SetCommercialDetectionEnabled", new String[] { "Enabled" }, true)
    {
      /**
       * Enables or disables commercial detection globally.
       * @param Enabled true to enable, false to disable
       * @since 9.3
       *
       * @declaration public void SetCommercialDetectionEnabled(boolean Enabled);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        boolean enabled = evalBool(stack.pop());
        Sage.putBoolean("commercial_detection/enabled", enabled);
        return null;
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "RunCommercialDetect", new String[] { "MediaFile" })
    {
      /**
       * Manually runs commercial detection on the specified MediaFile.
       * @param MediaFile the MediaFile to run commercial detection on
       * @since 9.3
       *
       * @declaration public void RunCommercialDetect(MediaFile MediaFile);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        MediaFile mf = getMediaFile(stack);
        CommercialDetectionManager.getInstance().runNow(mf);
        return null;
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "ClearCommercialMarkers", new String[] { "MediaFile" })
    {
      /**
       * Clears all commercial markers (EDL and secondary formats) for the specified MediaFile.
       * @param MediaFile the MediaFile to clear markers for
       * @since 9.3
       *
       * @declaration public void ClearCommercialMarkers(MediaFile MediaFile);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        MediaFile mf = getMediaFile(stack);
        CommercialDetectionManager.getInstance().clearMarkers(mf);
        return null;
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "HasCommercialMarkers", new String[] { "MediaFile" })
    {
      /**
       * Returns whether commercial markers exist for the specified MediaFile.
       * @param MediaFile the MediaFile to check
       * @return true if commercial markers (EDL) exist for this file
       * @since 9.3
       *
       * @declaration public boolean HasCommercialMarkers(MediaFile MediaFile);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        MediaFile mf = getMediaFile(stack);
        return CommercialDetectionManager.getInstance().hasMarkers(mf);
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "GetCommercialSegments", new String[] { "MediaFile" })
    {
      /**
       * Returns the list of commercial segments for the specified MediaFile.
       * Each segment is a map with keys: StartSeconds, EndSeconds, Action.
       * @param MediaFile the MediaFile to get segments for
       * @return an array of segment maps, or empty array if none
       * @since 9.3
       *
       * @declaration public Object[] GetCommercialSegments(MediaFile MediaFile);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        MediaFile mf = getMediaFile(stack);
        java.util.ArrayList<EdlWriter.Segment> segs = CommercialDetectionManager.getInstance().getSegments(mf);
        Object[] result = new Object[segs.size()];
        for (int i = 0; i < segs.size(); i++)
        {
          EdlWriter.Segment seg = segs.get(i);
          java.util.HashMap<String, Object> map = new java.util.HashMap<>();
          map.put("StartSeconds", seg.startSeconds);
          map.put("EndSeconds", seg.endSeconds);
          map.put("Action", seg.action);
          result[i] = map;
        }
        return result;
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "GetCommercialSegmentCount", new String[] { "MediaFile" })
    {
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        MediaFile mf = getMediaFile(stack);
        return CommercialDetectionManager.getInstance().getSegments(mf).size();
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "GetCommercialSegmentStart", new String[] { "MediaFile", "Index" })
    {
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        int idx = getInt(stack);
        MediaFile mf = getMediaFile(stack);
        java.util.ArrayList<EdlWriter.Segment> segs = CommercialDetectionManager.getInstance().getSegments(mf);
        if (idx < 0 || idx >= segs.size()) return 0.0;
        return segs.get(idx).startSeconds;
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "GetCommercialSegmentEnd", new String[] { "MediaFile", "Index" })
    {
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        int idx = getInt(stack);
        MediaFile mf = getMediaFile(stack);
        java.util.ArrayList<EdlWriter.Segment> segs = CommercialDetectionManager.getInstance().getSegments(mf);
        if (idx < 0 || idx >= segs.size()) return 0.0;
        return segs.get(idx).endSeconds;
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "IsCommercialDetectRunning", new String[] { "MediaFile" })
    {
      /**
       * Returns whether commercial detection is currently running or scheduled for the specified MediaFile.
       * @param MediaFile the MediaFile to check
       * @return true if a detection job is running or queued
       * @since 9.3
       *
       * @declaration public boolean IsCommercialDetectRunning(MediaFile MediaFile);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        MediaFile mf = getMediaFile(stack);
        return CommercialDetectionManager.getInstance().isJobRunningOrPending(mf);
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "GetCommercialDetectEngine", true)
    {
      /**
       * Returns the current commercial detection engine name.
       * @return the engine name (e.g. "comskip", "external")
       * @since 9.3
       *
       * @declaration public String GetCommercialDetectEngine();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return CommercialDetectionManager.getInstance().getEngine();
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "SetCommercialDetectEngine", new String[] { "Engine" }, true)
    {
      /**
       * Sets the commercial detection engine.
       * @param Engine the engine name ("comskip" or "external")
       * @since 9.3
       *
       * @declaration public void SetCommercialDetectEngine(String Engine);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String engine = getString(stack);
        CommercialDetectionManager.getInstance().setEngine(engine);
        return null;
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "GetComskipPath", true)
    {
      /**
       * Returns the path to the comskip binary.
       * @return the comskip binary path
       * @since 9.3
       *
       * @declaration public String GetComskipPath();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return CommercialDetectionManager.getInstance().getComskipPath();
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "SetComskipPath", new String[] { "Path" }, true)
    {
      /**
       * Sets the path to the comskip binary.
       * @param Path the path to the comskip binary
       * @since 9.3
       *
       * @declaration public void SetComskipPath(String Path);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String path = getString(stack);
        CommercialDetectionManager.getInstance().setComskipPath(path);
        return null;
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "GetComskipIniPath", true)
    {
      /**
       * Returns the path to the comskip.ini configuration file.
       * @return the comskip.ini path (empty string if not set)
       * @since 9.3
       *
       * @declaration public String GetComskipIniPath();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return CommercialDetectionManager.getInstance().getComskipIni();
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "SetComskipIniPath", new String[] { "Path" }, true)
    {
      /**
       * Sets the path to the comskip.ini configuration file.
       * @param Path the path to comskip.ini
       * @since 9.3
       *
       * @declaration public void SetComskipIniPath(String Path);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String path = getString(stack);
        CommercialDetectionManager.getInstance().setComskipIni(path);
        return null;
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "GetCommercialDetectOutputFormats", true)
    {
      /**
       * Returns the configured output formats as a comma-separated string (e.g. "edl,vprj,csv").
       * EDL is always generated; this controls secondary formats.
       * @return the output formats string
       * @since 9.3
       *
       * @declaration public String GetCommercialDetectOutputFormats();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return CommercialDetectionManager.getInstance().getOutputFormats();
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "SetCommercialDetectOutputFormats", new String[] { "Formats" }, true)
    {
      /**
       * Sets the output formats. EDL is always generated; this controls secondary formats.
       * @param Formats comma-separated format list (e.g. "edl", "edl,vprj", "edl,csv")
       * @since 9.3
       *
       * @declaration public void SetCommercialDetectOutputFormats(String Formats);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String formats = getString(stack);
        CommercialDetectionManager.getInstance().setOutputFormats(formats);
        return null;
      }});

    // ── Channel / Category Skip (from tmiranda CommercialDetector) ──

    rft.put(new PredefinedJEPFunction("CommercialSkip", "GetCommercialSkipChannels", true)
    {
      /**
       * Returns the comma-separated list of channels to skip for commercial detection.
       * Supports channel names, numbers, and ranges (e.g. "CNN,5-10,HBO").
       * @return the skip channels string
       * @since 9.3
       *
       * @declaration public String GetCommercialSkipChannels();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return CommercialDetectionManager.getInstance().getSkipChannels();
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "SetCommercialSkipChannels", new String[] { "Channels" }, true)
    {
      /**
       * Sets the channels to skip. Comma-separated channel names, numbers, or ranges.
       * @param Channels the skip channels string (e.g. "CNN,5-10,HBO")
       * @since 9.3
       *
       * @declaration public void SetCommercialSkipChannels(String Channels);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String channels = getString(stack);
        CommercialDetectionManager.getInstance().setSkipChannels(channels);
        return null;
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "GetCommercialSkipCategories", true)
    {
      /**
       * Returns the comma-separated list of categories to skip for commercial detection.
       * @return the skip categories string
       * @since 9.3
       *
       * @declaration public String GetCommercialSkipCategories();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return CommercialDetectionManager.getInstance().getSkipCategories();
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "SetCommercialSkipCategories", new String[] { "Categories" }, true)
    {
      /**
       * Sets the categories to skip. Comma-separated category names.
       * @param Categories the skip categories string (e.g. "Sports,News")
       * @since 9.3
       *
       * @declaration public void SetCommercialSkipCategories(String Categories);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String categories = getString(stack);
        CommercialDetectionManager.getInstance().setSkipCategories(categories);
        return null;
      }});

    // ── Restricted Times (from tmiranda CommercialDetector) ──

    rft.put(new PredefinedJEPFunction("CommercialSkip", "GetCommercialDetectRestrictedTimes", true)
    {
      /**
       * Returns the restricted hours as a comma-separated string (e.g. "18,19,20,21").
       * Commercial detection will not start during these hours.
       * @return the restricted times string
       * @since 9.3
       *
       * @declaration public String GetCommercialDetectRestrictedTimes();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return CommercialDetectionManager.getInstance().getRestrictedTimes();
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "SetCommercialDetectRestrictedTimes", new String[] { "Times" }, true)
    {
      /**
       * Sets the restricted hours. Comma-separated hour values (0-23).
       * @param Times the restricted times string (e.g. "18,19,20,21")
       * @since 9.3
       *
       * @declaration public void SetCommercialDetectRestrictedTimes(String Times);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String times = getString(stack);
        CommercialDetectionManager.getInstance().setRestrictedTimes(times);
        return null;
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "IsInRestrictedTime", true)
    {
      /**
       * Returns whether the current time is in a restricted period.
       * @return true if currently in restricted time
       * @since 9.3
       *
       * @declaration public boolean IsInRestrictedTime();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return CommercialDetectionManager.getInstance().inRestrictedTime();
      }});

    // ── Intelligent Scheduling (from tmiranda CommercialDetector) ──

    rft.put(new PredefinedJEPFunction("CommercialSkip", "IsIntelligentSchedulingEnabled", true)
    {
      /**
       * Returns whether intelligent scheduling is enabled.
       * When enabled, jobs won't start when recordings are in progress or about to start.
       * @return true if intelligent scheduling is enabled
       * @since 9.3
       *
       * @declaration public boolean IsIntelligentSchedulingEnabled();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return CommercialDetectionManager.getInstance().isIntelligentSchedulingEnabled();
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "SetIntelligentSchedulingEnabled", new String[] { "Enabled" }, true)
    {
      /**
       * Enables or disables intelligent scheduling.
       * @param Enabled true to enable, false to disable
       * @since 9.3
       *
       * @declaration public void SetIntelligentSchedulingEnabled(boolean Enabled);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        boolean enabled = evalBool(stack.pop());
        CommercialDetectionManager.getInstance().setIntelligentSchedulingEnabled(enabled);
        return null;
      }});

    // ── Run Slow / Playnice (from tmiranda CommercialDetector) ──

    rft.put(new PredefinedJEPFunction("CommercialSkip", "IsRunSlowEnabled", true)
    {
      /**
       * Returns whether comskip runs with --playnice (reduced CPU priority).
       * @return true if run slow is enabled
       * @since 9.3
       *
       * @declaration public boolean IsRunSlowEnabled();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return CommercialDetectionManager.getInstance().isRunSlowEnabled();
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "SetRunSlowEnabled", new String[] { "Enabled" }, true)
    {
      /**
       * Sets whether comskip runs with --playnice (reduced CPU priority).
       * @param Enabled true to enable, false to disable
       * @since 9.3
       *
       * @declaration public void SetRunSlowEnabled(boolean Enabled);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        boolean enabled = evalBool(stack.pop());
        CommercialDetectionManager.getInstance().setRunSlowEnabled(enabled);
        return null;
      }});

    // ── Live Detection (from tmiranda CommercialDetector start_imm) ──

    rft.put(new PredefinedJEPFunction("CommercialSkip", "IsLiveDetectionEnabled", true)
    {
      /**
       * Returns whether live detection is enabled (run comskip immediately when recording starts).
       * When enabled, comskip runs on the growing file for immediate results, then
       * re-runs on the complete file when recording finishes for full accuracy.
       * @return true if live detection is enabled
       * @since 9.3
       *
       * @declaration public boolean IsLiveDetectionEnabled();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return CommercialDetectionManager.getInstance().isLiveDetectionEnabled();
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "SetLiveDetectionEnabled", new String[] { "Enabled" }, true)
    {
      /**
       * Enables or disables live detection (run comskip immediately when recording starts).
       * @param Enabled true to enable, false to disable
       * @since 9.3
       *
       * @declaration public void SetLiveDetectionEnabled(boolean Enabled);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        boolean enabled = evalBool(stack.pop());
        CommercialDetectionManager.getInstance().setLiveDetectionEnabled(enabled);
        return null;
      }});

    // ── Per-Channel INI Directory (from tmiranda QueuedJob) ──

    rft.put(new PredefinedJEPFunction("CommercialSkip", "GetComskipIniDirectory", true)
    {
      /**
       * Returns the directory for per-channel/show comskip.ini files.
       * Place ShowName.ini or ChannelName.ini in this directory for custom profiles.
       * @return the ini directory path
       * @since 9.3
       *
       * @declaration public String GetComskipIniDirectory();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return CommercialDetectionManager.getInstance().getComskipIniDirectory();
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "SetComskipIniDirectory", new String[] { "Path" }, true)
    {
      /**
       * Sets the directory for per-channel/show comskip.ini files.
       * @param Path the directory path
       * @since 9.3
       *
       * @declaration public void SetComskipIniDirectory(String Path);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String path = getString(stack);
        CommercialDetectionManager.getInstance().setComskipIniDirectory(path);
        return null;
      }});

    // ── Auto Skip (from JREkiwi ComskipPlayback) ──

    rft.put(new PredefinedJEPFunction("CommercialSkip", "IsAutoSkipEnabled", true)
    {
      /**
       * Returns whether automatic commercial skipping during playback is enabled.
       * @return true if auto-skip is enabled
       * @since 9.3
       *
       * @declaration public boolean IsAutoSkipEnabled();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return CommercialDetectionManager.getInstance().isAutoSkipEnabled();
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "SetAutoSkipEnabled", new String[] { "Enabled" }, true)
    {
      /**
       * Enables or disables automatic commercial skipping during playback.
       * @param Enabled true to enable, false to disable
       * @since 9.3
       *
       * @declaration public void SetAutoSkipEnabled(boolean Enabled);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        boolean enabled = evalBool(stack.pop());
        CommercialDetectionManager.getInstance().setAutoSkipEnabled(enabled);
        return null;
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "GetAutoSkipDelayMs", true)
    {
      /**
       * Returns the delay in milliseconds before auto-skipping a commercial.
       * @return the delay in ms (0 = instant skip)
       * @since 9.3
       *
       * @declaration public int GetAutoSkipDelayMs();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return CommercialDetectionManager.getInstance().getAutoSkipDelayMs();
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "SetAutoSkipDelayMs", new String[] { "DelayMs" }, true)
    {
      /**
       * Sets the delay before auto-skipping a commercial.
       * @param DelayMs the delay in milliseconds (0 = instant)
       * @since 9.3
       *
       * @declaration public void SetAutoSkipDelayMs(int DelayMs);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        int ms = getInt(stack);
        CommercialDetectionManager.getInstance().setAutoSkipDelayMs(ms);
        return null;
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "IsInCommercial", new String[] { "MediaFile", "PositionSeconds" })
    {
      /**
       * Returns whether the given playback position is inside a commercial segment.
       * @param MediaFile the MediaFile being played
       * @param PositionSeconds the current playback position in seconds
       * @return true if the position is inside a commercial
       * @since 9.3
       *
       * @declaration public boolean IsInCommercial(MediaFile MediaFile, double PositionSeconds);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        double pos = (double) getFloat(stack);
        MediaFile mf = getMediaFile(stack);
        return CommercialDetectionManager.getInstance().isInCommercial(mf, pos);
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "GetCommercialEndPosition", new String[] { "MediaFile", "PositionSeconds" })
    {
      /**
       * Returns the end position (seconds) of the commercial segment at the given position.
       * Returns -1 if not in a commercial.
       * @param MediaFile the MediaFile being played
       * @param PositionSeconds the current playback position in seconds
       * @return the end of the commercial in seconds, or -1
       * @since 9.3
       *
       * @declaration public double GetCommercialEndPosition(MediaFile MediaFile, double PositionSeconds);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        double pos = (double) getFloat(stack);
        MediaFile mf = getMediaFile(stack);
        return CommercialDetectionManager.getInstance().getCommercialEndPosition(mf, pos);
      }});

    // ── Queue Management (from tmiranda ComskipManager) ──

    rft.put(new PredefinedJEPFunction("CommercialSkip", "GetCommercialDetectQueueSize", true)
    {
      /**
       * Returns the number of recordings waiting in the detection queue.
       * @return the queue size
       * @since 9.3
       *
       * @declaration public int GetCommercialDetectQueueSize();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return CommercialDetectionManager.getInstance().getQueueSize();
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "GetCommercialDetectRunningCount", true)
    {
      /**
       * Returns the number of detection jobs currently running.
       * @return the number of running jobs
       * @since 9.3
       *
       * @declaration public int GetCommercialDetectRunningCount();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return CommercialDetectionManager.getInstance().getRunningCount();
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "ClearCommercialDetectQueue", true)
    {
      /**
       * Clears all pending jobs from the detection queue.
       * @since 9.3
       *
       * @declaration public void ClearCommercialDetectQueue();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        CommercialDetectionManager.getInstance().clearQueue();
        return null;
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "StopAllCommercialDetectJobs", true)
    {
      /**
       * Stops all currently running detection jobs immediately.
       * @since 9.3
       *
       * @declaration public void StopAllCommercialDetectJobs();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        CommercialDetectionManager.getInstance().stopAllJobs();
        return null;
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "StopCommercialDetect", new String[] { "MediaFile" })
    {
      /**
       * Stops a running commercial detection job for the specified MediaFile.
       * @param MediaFile the MediaFile to stop detection for
       * @since 9.3
       *
       * @declaration public void StopCommercialDetect(MediaFile MediaFile);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        MediaFile mf = getMediaFile(stack);
        CommercialDetectionManager.getInstance().stopJob(mf);
        return null;
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "ScanAllRecordings", true)
    {
      /**
       * Scans all TV recordings and queues those without commercial markers for detection.
       * Returns the number of recordings queued.
       * @return the number of recordings queued for processing
       * @since 9.3
       *
       * @declaration public int ScanAllRecordings();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return CommercialDetectionManager.getInstance().scanAllRecordings();
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "GetExternalEnginePath", true)
    {
      /**
       * Returns the path to the external commercial detection engine binary.
       * @return the external engine path (empty string if not set)
       * @since 9.3
       *
       * @declaration public String GetExternalEnginePath();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return CommercialDetectionManager.getInstance().getExternalEnginePath();
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "SetExternalEnginePath", new String[] { "Path" }, true)
    {
      /**
       * Sets the path to the external commercial detection engine binary.
       * @param Path the path to the external engine
       * @since 9.3
       *
       * @declaration public void SetExternalEnginePath(String Path);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String path = getString(stack);
        CommercialDetectionManager.getInstance().setExternalEnginePath(path);
        return null;
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "GetExternalRecordedArgs", true)
    {
      /**
       * Returns the command-line arguments template for the external engine when processing completed recordings.
       * Supports variables: {input}, {output}, {outputdir}, {ini}
       * @return the recorded args template (default: "{input} {output}")
       * @since 9.3
       *
       * @declaration public String GetExternalRecordedArgs();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return CommercialDetectionManager.getInstance().getExternalRecordedArgs();
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "SetExternalRecordedArgs", new String[] { "Args" }, true)
    {
      /**
       * Sets the command-line arguments template for the external engine when processing completed recordings.
       * Supports variables: {input}, {output}, {outputdir}, {ini}
       * @param Args the args template string
       * @since 9.3
       *
       * @declaration public void SetExternalRecordedArgs(String Args);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String args = getString(stack);
        CommercialDetectionManager.getInstance().setExternalRecordedArgs(args);
        return null;
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "GetExternalLiveArgs", true)
    {
      /**
       * Returns the command-line arguments template for the external engine when processing a recording in progress.
       * Supports variables: {input}, {output}, {outputdir}, {ini}
       * @return the live args template (default: "{input} {output}")
       * @since 9.3
       *
       * @declaration public String GetExternalLiveArgs();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return CommercialDetectionManager.getInstance().getExternalLiveArgs();
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "SetExternalLiveArgs", new String[] { "Args" }, true)
    {
      /**
       * Sets the command-line arguments template for the external engine when processing a recording in progress.
       * Supports variables: {input}, {output}, {outputdir}, {ini}
       * @param Args the args template string
       * @since 9.3
       *
       * @declaration public void SetExternalLiveArgs(String Args);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String args = getString(stack);
        CommercialDetectionManager.getInstance().setExternalLiveArgs(args);
        return null;
      }});

    // ── Profile System ──

    rft.put(new PredefinedJEPFunction("CommercialSkip", "IsCommercialDetectProfilesEnabled", true)
    {
      /**
       * Returns whether the comskip profile system is enabled.
       * When enabled, the system auto-selects base + delta INI based on content type.
       * @return true if profiles are enabled
       * @since 9.3
       *
       * @declaration public boolean IsCommercialDetectProfilesEnabled();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return CommercialDetectionManager.getInstance().isProfilesEnabled();
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "SetCommercialDetectProfilesEnabled", new String[] { "Enabled" }, true)
    {
      /**
       * Enables or disables the comskip profile system.
       * @param Enabled true to enable content-based profile selection
       * @since 9.3
       *
       * @declaration public void SetCommercialDetectProfilesEnabled(boolean Enabled);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        boolean enabled = evalBool(stack.pop());
        CommercialDetectionManager.getInstance().setProfilesEnabled(enabled);
        return null;
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "GetCommercialDetectProfileDirectory", true)
    {
      /**
       * Returns the directory containing comskip profile INI files.
       * @return the profile directory path
       * @since 9.3
       *
       * @declaration public String GetCommercialDetectProfileDirectory();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return CommercialDetectionManager.getInstance().getProfileDirectory();
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "SetCommercialDetectProfileDirectory", new String[] { "Path" }, true)
    {
      /**
       * Sets the directory containing comskip profile INI files (base + deltas).
       * @param Path the directory path
       * @since 9.3
       *
       * @declaration public void SetCommercialDetectProfileDirectory(String Path);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String path = getString(stack);
        CommercialDetectionManager.getInstance().setProfileDirectory(path);
        return null;
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "GetCommercialDetectContentProfile", new String[] { "MediaFile" })
    {
      /**
       * Returns the detected content profile for a MediaFile based on its category metadata.
       * Returns "SPORTS", "NEWS", or "DEFAULT".
       * @param MediaFile the MediaFile to check
       * @return the detected profile name
       * @since 9.3
       *
       * @declaration public String GetCommercialDetectContentProfile(MediaFile MediaFile);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        MediaFile mf = getMediaFile(stack);
        return CommercialDetectionManager.getInstance().detectContentProfile(mf).name();
      }});
  }

  private static MediaFile getMediaFile(Catbert.FastStack stack)
  {
    Object o = stack.pop();
    if (o instanceof MediaFile) return (MediaFile)o;
    return null;
  }
}
