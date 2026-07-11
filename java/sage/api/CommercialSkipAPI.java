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

    rft.put(new PredefinedJEPFunction("CommercialSkip", "GetCommercialSegmentTimes", new String[] { "MediaFile" })
    {
      /**
       * Returns a flat array of commercial segment times in seconds: [start1, end1, start2, end2, ...].
       * One file read, efficient for STV iteration. Times are in seconds from file start.
       * @param MediaFile the MediaFile to get segments for
       * @return a double array of alternating start/end times, or empty array if none
       * @since 9.3
       *
       * @declaration public double[] GetCommercialSegmentTimes(MediaFile MediaFile);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        MediaFile mf = getMediaFile(stack);
        java.util.ArrayList<EdlWriter.Segment> segs = CommercialDetectionManager.getInstance().getSegments(mf);
        double[] result = new double[segs.size() * 2];
        for (int i = 0; i < segs.size(); i++)
        {
          result[i * 2] = segs.get(i).startSeconds;
          result[i * 2 + 1] = segs.get(i).endSeconds;
        }
        return result;
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

    // ── Status / Queue Methods ──

    rft.put(new PredefinedJEPFunction("CommercialSkip", "IsComskipActive")
    {
      /**
       * Returns true if any commercial detection job is currently running.
       * @return true if at least one job is active
       * @since 9.3
       *
       * @declaration public boolean IsComskipActive();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return CommercialDetectionManager.getInstance().getRunningCount() > 0;
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "GetQueuedJobCount")
    {
      /**
       * Returns the number of jobs waiting in the queue.
       * @return the queue size
       * @since 9.3
       *
       * @declaration public int GetQueuedJobCount();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return CommercialDetectionManager.getInstance().getQueueSize();
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "GetRunningJobCount")
    {
      /**
       * Returns the number of currently running detection jobs.
       * @return the active job count
       * @since 9.3
       *
       * @declaration public int GetRunningJobCount();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return CommercialDetectionManager.getInstance().getRunningCount();
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "GetQueuedJobTitles")
    {
      /**
       * Returns a newline-separated list of show titles for queued jobs.
       * @return job titles string
       * @since 9.3
       *
       * @declaration public String GetQueuedJobTitles();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return getJobTitles(CommercialDetectionManager.getInstance().getQueuedMediaFileIDs());
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "GetRunningJobTitles")
    {
      /**
       * Returns a newline-separated list of show titles for running jobs.
       * @return job titles string
       * @since 9.3
       *
       * @declaration public String GetRunningJobTitles();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return getJobTitles(CommercialDetectionManager.getInstance().getRunningMediaFileIDs());
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "ClearCommercialDetectQueue", true)
    {
      /**
       * Clears all pending jobs from the commercial detection queue.
       * Running jobs are not affected.
       * @since 9.3
       *
       * @declaration public void ClearCommercialDetectQueue();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        CommercialDetectionManager.getInstance().clearQueue();
        return null;
      }});

    // ── Playback: Commercial Segment Queries ──

    rft.put(new PredefinedJEPFunction("CommercialSkip", "IsInCommercial", new String[] { "MediaFile", "TimeMs" })
    {
      /**
       * Returns whether the given playback time falls within a commercial segment.
       * @param MediaFile the MediaFile being played
       * @param TimeMs the current playback time in milliseconds
       * @return true if the time is within a commercial break
       * @since 9.3
       *
       * @declaration public boolean IsInCommercial(MediaFile MediaFile, long TimeMs);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        long timeMs = getLong(stack);
        MediaFile mf = getMediaFile(stack);
        return isInCommercialSegment(mf, timeMs);
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "GetCommercialEndTime", new String[] { "MediaFile", "TimeMs" })
    {
      /**
       * If the given time is within a commercial, returns the end time of that commercial
       * segment in milliseconds. Returns -1 if not in a commercial.
       * @param MediaFile the MediaFile being played
       * @param TimeMs the current playback time in milliseconds
       * @return end time in ms, or -1
       * @since 9.3
       *
       * @declaration public long GetCommercialEndTime(MediaFile MediaFile, long TimeMs);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        long timeMs = getLong(stack);
        MediaFile mf = getMediaFile(stack);
        return getCommercialEndTimeMs(mf, timeMs);
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "GetNextCommercialBoundaryTime", new String[] { "MediaFile", "TimeMs" })
    {
      /**
       * Returns the next commercial segment boundary (start or end) after the given time,
       * in epoch milliseconds. Returns -1 if no boundary exists.
       * @param MediaFile the MediaFile being played
       * @param TimeMs the current playback time in epoch milliseconds
       * @return next boundary time in epoch ms, or -1
       * @since 9.3
       *
       * @declaration public long GetNextCommercialBoundaryTime(MediaFile MediaFile, long TimeMs);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        long timeMs = getLong(stack);
        MediaFile mf = getMediaFile(stack);
        if (mf == null) return -1L;
        SkipMatrix matrix = null;
        VideoFrame vf = stack.getUIMgrSafe().getVideoFrame();
        if (vf != null && mf.equals(vf.getCurrFile()))
          matrix = vf.getCommercialSkipMatrix();
        if (matrix == null) return -1L;
        long fileStartMs = mf.getStart(0);
        long boundary = matrix.getNextBoundary(timeMs - fileStartMs);
        return boundary >= 0 ? fileStartMs + boundary : -1L;
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "GetPreviousCommercialBoundaryTime", new String[] { "MediaFile", "TimeMs" })
    {
      /**
       * Returns the previous commercial segment boundary (start or end) before the given time,
       * in epoch milliseconds. Returns -1 if no boundary exists.
       * @param MediaFile the MediaFile being played
       * @param TimeMs the current playback time in epoch milliseconds
       * @return previous boundary time in epoch ms, or -1
       * @since 9.3
       *
       * @declaration public long GetPreviousCommercialBoundaryTime(MediaFile MediaFile, long TimeMs);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        long timeMs = getLong(stack);
        MediaFile mf = getMediaFile(stack);
        if (mf == null) return -1L;
        SkipMatrix matrix = null;
        VideoFrame vf = stack.getUIMgrSafe().getVideoFrame();
        if (vf != null && mf.equals(vf.getCurrFile()))
          matrix = vf.getCommercialSkipMatrix();
        if (matrix == null) return -1L;
        long fileStartMs = mf.getStart(0);
        long boundary = matrix.getPreviousBoundary(timeMs - fileStartMs);
        return boundary >= 0 ? fileStartMs + boundary : -1L;
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "GetCommercialSegmentCount", new String[] { "MediaFile" })
    {
      /**
       * Returns the number of commercial segments detected for this MediaFile.
       * @param MediaFile the MediaFile to check
       * @return segment count, 0 if no EDL
       * @since 9.3
       *
       * @declaration public int GetCommercialSegmentCount(MediaFile MediaFile);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        MediaFile mf = getMediaFile(stack);
        if (mf == null) return 0;
        java.io.File recFile = mf.getRecordingFile();
        if (recFile == null) return 0;
        return EdlWriter.readEdl(recFile).size();
      }});

    // ── Auto-Skip Settings ──

    rft.put(new PredefinedJEPFunction("CommercialSkip", "IsAutoSkipEnabled")
    {
      /**
       * Returns whether automatic commercial skipping during playback is enabled.
       * When enabled, playback will automatically seek past commercial segments.
       * When disabled, a "Skip Commercial" popup will appear instead.
       * @return true if auto-skip is enabled
       * @since 9.3
       *
       * @declaration public boolean IsAutoSkipEnabled();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return Sage.getBoolean("commercial_detection/auto_skip", false);
      }});

    rft.put(new PredefinedJEPFunction("CommercialSkip", "SetAutoSkipEnabled", new String[] { "Enabled" }, true)
    {
      /**
       * Enables or disables automatic commercial skipping during playback.
       * @param Enabled true to enable auto-skip, false for popup mode
       * @since 9.3
       *
       * @declaration public void SetAutoSkipEnabled(boolean Enabled);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        boolean enabled = evalBool(stack.pop());
        Sage.putBoolean("commercial_detection/auto_skip", enabled);
        return null;
      }});
  }

  // ── Helpers ──

  private static MediaFile getMediaFile(Catbert.FastStack stack)
  {
    Object o = stack.pop();
    if (o instanceof MediaFile) return (MediaFile)o;
    return null;
  }

  private static long getLong(Catbert.FastStack stack)
  {
    Object o = stack.pop();
    if (o instanceof Number) return ((Number)o).longValue();
    return 0;
  }

  private static String getJobTitles(int[] ids)
  {
    if (ids == null || ids.length == 0) return "";
    StringBuilder sb = new StringBuilder();
    for (int id : ids)
    {
      MediaFile mf = Wizard.getInstance().getFileForID(id);
      if (mf != null)
      {
        Show show = mf.getShow();
        String title = (show != null) ? show.getTitle() : mf.getName();
        if (title != null && !title.isEmpty())
        {
          if (sb.length() > 0) sb.append("\n");
          String ep = (show != null) ? show.getEpisodeName() : null;
          if (ep != null && !ep.isEmpty())
            sb.append(title).append(" - ").append(ep);
          else
            sb.append(title);
        }
      }
    }
    return sb.toString();
  }

  private static boolean isInCommercialSegment(MediaFile mf, long timeMs)
  {
    if (mf == null) return false;
    java.io.File recFile = mf.getRecordingFile();
    if (recFile == null) return false;
    // Convert epoch ms to file-relative seconds
    long fileStartMs = mf.getStart(0);
    double timeSec = (timeMs - fileStartMs) / 1000.0;
    for (EdlWriter.Segment seg : EdlWriter.readEdl(recFile))
    {
      if (seg.action == 0 && timeSec >= seg.startSeconds && timeSec < seg.endSeconds)
        return true;
    }
    return false;
  }

  private static long getCommercialEndTimeMs(MediaFile mf, long timeMs)
  {
    if (mf == null) return -1;
    java.io.File recFile = mf.getRecordingFile();
    if (recFile == null) return -1;
    // Convert epoch ms to file-relative seconds
    long fileStartMs = mf.getStart(0);
    double timeSec = (timeMs - fileStartMs) / 1000.0;
    for (EdlWriter.Segment seg : EdlWriter.readEdl(recFile))
    {
      if (seg.action == 0 && timeSec >= seg.startSeconds && timeSec < seg.endSeconds)
        return fileStartMs + (long)(seg.endSeconds * 1000);
    }
    return -1;
  }
}
