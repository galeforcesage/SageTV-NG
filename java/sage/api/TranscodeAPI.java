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
/**
 *
 * @author Narflex
 */
public class TranscodeAPI
{
  private TranscodeAPI(){	}

  public static void init(Catbert.ReflectionFunctionTable rft)
  {
    rft.put(new PredefinedJEPFunction("Transcode", "GetTranscodeFormats", true)
    {
      /**
       * Gets the names of the different transcode formats
       * @return a list of the names of the different transcode formats
       * @since 5.1
       *
       * @declaration public String[] GetTranscodeFormats();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return Sage.keys("transcoder/formats");
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetTranscodeFormatDisplayName", new String[] { "FormatName" }, true)
    {
      /**
       * Gets the UI-friendly display label for the specified transcode format.
       * Falls back to the raw format name if no displayName was registered
       * (presets register theirs via the optional displayName= property file key).
       * @param FormatName the internal transcode format name
       * @return the friendly label to display in the UI, or the format name itself if no label is set
       * @since 9.3
       *
       * @declaration public String GetTranscodeFormatDisplayName(String FormatName);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String name = getString(stack);
        if (name == null) return null;
        return Sage.get("transcoder/format_labels/" + name, name);
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetTranscodeFormatDetails", new String[] { "FormatName" }, true)
    {
      /**
       * Gets the format details for the specified format name
       * @param FormatName the name of the transcode format to get the parameter details for
       * @return the full detail string that describes the specified transcode format
       * @since 5.1
       *
       * @declaration public String GetTranscodeFormatDetails(String FormatName);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return Sage.get("transcoder/formats/" + getString(stack), null);
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "AddTranscodeFormat", new String[] { "FormatName", "FormatDetails" }, true)
    {
      /**
       * Adds the specified transcode format to the list of available formats
       * @param FormatName the name of the new transcode format
       * @param FormatDetails the detailed property string for the new format
       * @since 5.1
       *
       * @declaration public void AddTranscodeFormat(String FormatName, String FormatDetails);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String details = getString(stack);
        String name = getString(stack);
        if (Permissions.hasPermission(Permissions.PERMISSION_CONVERSION, stack.getUIMgr()))
          Sage.put("transcoder/formats/" + name, details);
        return null;
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "RemoveTranscodeFormat", new String[] { "FormatName" }, true)
    {
      /**
       * Removed the specified transcode format to the list of available formats
       * @param FormatName the name of the transcode format to remove
       * @since 5.1
       *
       * @declaration public void RemoveTranscodeFormat(String FormatName);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String name = getString(stack);
        if (Permissions.hasPermission(Permissions.PERMISSION_CONVERSION, stack.getUIMgr()))
          Sage.remove("transcoder/formats/" + name);
        return null;
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "AddTranscodeJob", -1, new String[] { "SourceMediaFile", "FormatName", "DestinationFile", "DeleteSourceAfterTranscode" }, true)
    {
      /**
       * Adds the specified job to the transcoder's queue. Returns a Job ID# for future reference of it.
       * @param SourceMediaFile the source file that is to be transcoded, if it consists of multiple segments, all segments will be transcoded
       * @param FormatName the name of the transcode format to use for this conversion
       * @param DestinationFile the target file path for the conversion or null if SageTV should automatically determine the filename of the target files, if a directory is given then SageTV auto-generates the filename in that directory
       * @param DeleteSourceAfterTranscode if true then the source media files are deleted when the transcoding is done, if false the source files are kept
       * @return the job ID number to reference this transcode job
       * @since 5.1
       *
       * @declaration public int AddTranscodeJob(MediaFile SourceMediaFile, String FormatName, java.io.File DestinationFile, boolean DeleteSourceAfterTranscode);
       */

      /**
       * Adds the specified job to the transcoder's queue. Returns a Job ID# for future reference of it. This allows specification of the
       * start time and duration for the media which allows extracting a 'clip' from a file.
       * @param SourceMediaFile the source file that is to be transcoded, if it consists of multiple segments, all segments will be transcoded
       * @param FormatName the name of the transcode format to use for this conversion
       * @param DestinationFile the target file path for the conversion or null if SageTV should automatically determine the filename of the target files, if a directory is given then SageTV auto-generates the filename in that directory
       * @param DeleteSourceAfterTranscode if true then the source media files are deleted when the transcoding is done, if false the source files are kept
       * @param ClipTimeStart specifies the time in the file in seconds that the clip starts at (this number is relative to the beginning of the actual file)
       * @param ClipDuration specifies the duration of the clip in seconds to extract from the file (0 to convert until the end of the file)
       * @return the job ID number to reference this transcode job
       * @since 5.1
       *
       * @declaration public int AddTranscodeJob(MediaFile SourceMediaFile, String FormatName, java.io.File DestinationFile, boolean DeleteSourceAfterTranscode, long ClipTimeStart, long ClipDuration);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        long clipStart = 0;
        long clipDuration = 0;
        if (curNumberOfParameters == 6)
        {
          clipDuration = getLong(stack);
          clipStart = getLong(stack);
        }
        boolean deleteAfter = evalBool(stack.pop());
        java.io.File destFile = getFile(stack);
        String formatName = getString(stack);
        sage.media.format.ContainerFormat format = Ministry.getPredefinedTargetFormat(formatName);
        MediaFile mf = getMediaFile(stack);
        if (Permissions.hasPermission(Permissions.PERMISSION_CONVERSION, stack.getUIMgr()))
          return new Integer(Ministry.getInstance().addTranscodeJob(mf, formatName, format, destFile, deleteAfter, clipStart*1000, clipDuration*1000));
        else
          return null;
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetTranscodeJobStatus", new String[] { "JobID" }, true)
    {
      /**
       * Gets the status of the specified transcoding job
       * @param JobID the Job ID of the transcoding job to get the status of
       * @return the status information for the specified transcoding job, will be one of: COMPLETED, TRANSCODING, WAITING TO START, or FAILED
       * @since 5.1
       *
       * @declaration public String GetTranscodeJobStatus(int JobID);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        switch (Ministry.getInstance().getJobStatusCode(getInt(stack)))
        {
          case TranscodeJob.COMPLETED:
            return "COMPLETED";
          case TranscodeJob.TRANSCODING_SEGMENT_COMPLETE:
          case TranscodeJob.TRANSCODING:
          case TranscodeJob.LIMBO:
            return "TRANSCODING";
          case TranscodeJob.TRANSCODE_FAILED:
            return "FAILED";
          default:
            return "WAITING TO START";
        }
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "CancelTranscodeJob", new String[] { "JobID" }, true)
    {
      /**
       * Cancels the specified transcoding ob
       * @param JobID the Job ID of the transcoding job to cancel
       * @return true if the job exists and was cancelled, false otherwise
       * @since 5.1
       *
       * @declaration public boolean CancelTranscodeJob(int JobID);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        int jobID = getInt(stack);
        return (Permissions.hasPermission(Permissions.PERMISSION_CONVERSION, stack.getUIMgr()) &&
            Ministry.getInstance().cancelTranscodeJob(jobID)) ? Boolean.TRUE : Boolean.FALSE;
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetTranscodeJobSourceFile", new String[] { "JobID" }, true)
    {
      /**
       * Gets the source file of the specified transcoding job
       * @param JobID the Job ID of the transcoding job to get the source file for
       * @return the source file of the specified transcoding job
       * @since 5.1
       *
       * @declaration public MediaFile GetTranscodeJobSourceFile(int JobID);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return Ministry.getInstance().getJobSourceFile(getInt(stack));
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetTranscodeJobDestFile", new String[] { "JobID" }, true)
    {
      /**
       * Gets the destination file of the specified transcoding job
       * @param JobID the Job ID of the transcoding job to get the destination file for
       * @return the destination file of the specified transcoding job, or null if no destination file was specified
       * @since 5.1
       *
       * @declaration public java.io.File GetTranscodeJobDestFile(int JobID);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return Ministry.getInstance().getJobDestFile(getInt(stack));
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetTranscodeJobShouldKeepOriginal", new String[] { "JobID" }, true)
    {
      /**
       * Returns whether or not the specified transcoding job retains the original source file
       * @param JobID the Job ID of the transcoding job to get the destination file for
       * @return true if the specified transcoding job keeps its original file when done, false otherwise
       * @since 5.1
       *
       * @declaration public boolean GetTranscodeJobShouldKeepOriginal(int JobID);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return Ministry.getInstance().getJobShouldKeepOriginal(getInt(stack)) ? Boolean.TRUE : Boolean.FALSE;
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetTranscodeJobClipStart", new String[] { "JobID" }, true)
    {
      /**
       * Returns the clip start time for the specified transcode job
       * @param JobID the Job ID of the transcoding job to get the destination file for
       * @return the clip start time for the specified transcode job, 0 if the start time is unspecified
       * @since 5.1
       *
       * @declaration public long GetTranscodeJobClipStart(int JobID);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return new Long(Ministry.getInstance().getJobClipStartTime(getInt(stack)));
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetTranscodeJobClipDuration", new String[] { "JobID" }, true)
    {
      /**
       * Returns the clip duration for the specified transcode job
       * @param JobID the Job ID of the transcoding job to get the destination file for
       * @return the clip duration for the specified transcode job, 0 if the entire file will be trancoded
       * @since 5.1
       *
       * @declaration public long GetTranscodeJobClipDuration(int JobID);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return new Long(Ministry.getInstance().getJobClipDuration(getInt(stack)));
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetTranscodeJobFormat", new String[] { "JobID" }, true)
    {
      /**
       * Gets the target format of the specified transcoding job
       * @param JobID the Job ID of the transcoding job to get the target format file for
       * @return the target format of the specified transcoding job
       * @since 5.1
       *
       * @declaration public String GetTranscodeJobFormat(int JobID);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return Ministry.getInstance().getJobFormat(getInt(stack));
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "ClearTranscodedJobs", true)
    {
      /**
       * Removes all of the completed transcode jobs from the transcoder queue
       * @since 5.1
       *
       * @declaration public void ClearTranscodedJobs();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        if (Permissions.hasPermission(Permissions.PERMISSION_CONVERSION, stack.getUIMgr()))
          Ministry.getInstance().clearCompletedTranscodes();
        return null;
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetTranscodeJobs", true)
    {
      /**
       * Returns a list of the job IDs for all the current jobs in the transcode queue.
       * @return the list of job IDs for all the current jobs in the transcode queue
       * @since 5.1
       *
       * @declaration public Integer[] GetTranscodeJobs();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        int[] jobs = Ministry.getInstance().getTranscodeJobIDs();
        Integer[] rv = new Integer[jobs == null ? 0 : jobs.length];
        for (int i = 0; i < rv.length; i++)
          rv[i] = new Integer(jobs[i]);
        return rv;
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "CanFileBeTranscoded", new String[] { "MediaFile" }, true)
    {
      /**
       * Returns true if the specified MediaFile can be transcoded, false otherwise. Transcoding may be restricted
       * by certain formats and also by DRM.
       * @param MediaFile the MediaFile object
       * @return true if the specified MediaFile can be transcoded, false otherwise
       * @since 5.1
       *
       * @declaration public boolean CanFileBeTranscoded(MediaFile MediaFile);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        MediaFile mf = getMediaFile(stack);
        if (mf == null) return Boolean.FALSE;
        return Ministry.getInstance().isValidSourceFormat(mf.getFileFormat()) ? Boolean.TRUE : Boolean.FALSE;
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetTranscodeJobCompletePercent", new String[] { "JobID" }, true)
    {
      /**
       * Gets the percent complete (between 0 and 1 as a float) for a transcode job
       * @param JobID the Job ID of the transcoding job to get the percent complete of
       * @return the percent complete for the specified transcoding job
       * @since 5.1
       *
       * @declaration public float GetTranscodeJobCompletePercent(int JobID);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return new Float(Ministry.getInstance().getJobPercentComplete(getInt(stack)));
      }});

    // ---- Guided offline conversion (Export, Enhance & Archive) --------------
    // These expose the client-agnostic sage.convert engine to the STV UI. The
    // choice vocabularies drive the guided menus; BuildConversionPlan* resolve a
    // ConversionRequest + source snapshot into the concrete ffmpeg format spec
    // (registerable via AddTranscodeFormat and runnable via AddTranscodeJob),
    // plus a human-readable summary/operations/size-estimate for the Review step.

    rft.put(new PredefinedJEPFunction("Transcode", "GetConversionPurposes", 0, new String[] {  }, true)
    {
      /**
       * Gets the guided-conversion purpose vocabulary (USB_TV, OFFLINE_DEVICE,
       * TRAVEL, ENHANCED_FAVORITE, ARCHIVE, EXACT_BACKUP, CUSTOM).
       * @return the purpose token names for the guided conversion UI
       * @since 9.3
       *
       * @declaration public String[] GetConversionPurposes();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return enumNames(sage.convert.ConversionPurpose.values());
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetConversionVideoCodecs", 0, new String[] {  }, true)
    {
      /**
       * Gets the guided-conversion video codec vocabulary (COPY, H264, HEVC, AV1).
       * @return the video codec token names
       * @since 9.3
       *
       * @declaration public String[] GetConversionVideoCodecs();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return enumNames(sage.convert.VideoCodecChoice.values());
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetConversionContainers", 0, new String[] {  }, true)
    {
      /**
       * Gets the guided-conversion container vocabulary (KEEP, MP4, MKV).
       * @return the container token names
       * @since 9.3
       *
       * @declaration public String[] GetConversionContainers();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return enumNames(sage.convert.ContainerChoice.values());
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetConversionScalingModes", 0, new String[] {  }, true)
    {
      /**
       * Gets the guided-conversion scaling vocabulary (NONE, LANCZOS, AI).
       * @return the scaling token names
       * @since 9.3
       *
       * @declaration public String[] GetConversionScalingModes();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return enumNames(sage.convert.ScalingChoice.values());
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetConversionFrameRateModes", 0, new String[] {  }, true)
    {
      /**
       * Gets the guided-conversion frame-rate vocabulary (KEEP, CAP_30, CAP_24, ALLOW_60).
       * @return the frame-rate token names
       * @since 9.3
       *
       * @declaration public String[] GetConversionFrameRateModes();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return enumNames(sage.convert.FrameRateChoice.values());
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetConversionDynamicRangeModes", 0, new String[] {  }, true)
    {
      /**
       * Gets the guided-conversion dynamic-range vocabulary (AUTO, KEEP, PRESERVE_HDR10, TONEMAP_SDR).
       * @return the dynamic-range token names
       * @since 9.3
       *
       * @declaration public String[] GetConversionDynamicRangeModes();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return enumNames(sage.convert.DynamicRangeChoice.values());
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetConversionAudioCodecs", 0, new String[] {  }, true)
    {
      /**
       * Gets the guided-conversion audio codec vocabulary (COPY, AAC, AC3, EAC3).
       * @return the audio codec token names
       * @since 9.3
       *
       * @declaration public String[] GetConversionAudioCodecs();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return enumNames(sage.convert.AudioCodecChoice.values());
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetConversionAudioLayouts", 0, new String[] {  }, true)
    {
      /**
       * Gets the guided-conversion audio layout vocabulary (KEEP, STEREO, SURROUND_51).
       * @return the audio layout token names
       * @since 9.3
       *
       * @declaration public String[] GetConversionAudioLayouts();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return enumNames(sage.convert.AudioLayoutChoice.values());
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetConversionSubtitleModes", 0, new String[] {  }, true)
    {
      /**
       * Gets the guided-conversion subtitle vocabulary (NONE, COPY, ALL, BURN_IN).
       * @return the subtitle token names
       * @since 9.3
       *
       * @declaration public String[] GetConversionSubtitleModes();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return enumNames(sage.convert.SubtitleChoice.values());
      }});

    rft.put(new PredefinedJEPFunction("Transcode", "BuildConversionPlanSpec", CONV_PLAN_PARAMS.length,
        CONV_PLAN_PARAMS, true)
    {
      /**
       * Resolves the guided conversion choices for a source recording into a
       * concrete ffmpeg transcode format spec (raw-cmdline metadata form). The
       * returned string can be registered with AddTranscodeFormat and run with
       * AddTranscodeJob. Throws if the choices are invalid (e.g. AI downscale).
       * @return the transcode format spec string for transcoder/formats
       * @since 9.3
       *
       * @declaration public String BuildConversionPlanSpec(MediaFile SourceMediaFile, String Purpose, String Container, String VideoCodec, String Scaling, int TargetWidth, int TargetHeight, String DynamicRange, String FrameRate, String AudioLayout, String AudioCodec, int AudioBitrateKbps, String Subtitles, int QualityCq);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return buildConversionPlan(stack).getFormatSpec();
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetConversionPlanSummary", CONV_PLAN_PARAMS.length,
        CONV_PLAN_PARAMS, true)
    {
      /**
       * Resolves the guided conversion choices and returns a one-line
       * resolved-output summary (container/codec/resolution/fps/audio) for the
       * Review step.
       * @return a human-readable summary of the resolved output
       * @since 9.3
       *
       * @declaration public String GetConversionPlanSummary(MediaFile SourceMediaFile, String Purpose, String Container, String VideoCodec, String Scaling, int TargetWidth, int TargetHeight, String DynamicRange, String FrameRate, String AudioLayout, String AudioCodec, int AudioBitrateKbps, String Subtitles, int QualityCq);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return buildConversionPlan(stack).getSummary();
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetConversionPlanOperations", CONV_PLAN_PARAMS.length,
        CONV_PLAN_PARAMS, true)
    {
      /**
       * Resolves the guided conversion choices and returns the ordered list of
       * operations that will be applied (deinterlace, scale, encode, etc.) for
       * the Review step.
       * @return the ordered operation descriptions
       * @since 9.3
       *
       * @declaration public String[] GetConversionPlanOperations(MediaFile SourceMediaFile, String Purpose, String Container, String VideoCodec, String Scaling, int TargetWidth, int TargetHeight, String DynamicRange, String FrameRate, String AudioLayout, String AudioCodec, int AudioBitrateKbps, String Subtitles, int QualityCq);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        java.util.List<String> ops = buildConversionPlan(stack).getOperations();
        return ops.toArray(new String[ops.size()]);
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "EstimateConversionOutputBytes", CONV_PLAN_PARAMS.length,
        CONV_PLAN_PARAMS, true)
    {
      /**
       * Resolves the guided conversion choices and returns an ESTIMATED output
       * size in bytes for the source's duration. This is an estimate only —
       * VBR/AI variance means the actual size can differ.
       * @return the estimated output size in bytes
       * @since 9.3
       *
       * @declaration public long EstimateConversionOutputBytes(MediaFile SourceMediaFile, String Purpose, String Container, String VideoCodec, String Scaling, int TargetWidth, int TargetHeight, String DynamicRange, String FrameRate, String AudioLayout, String AudioCodec, int AudioBitrateKbps, String Subtitles, int QualityCq);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.convert.ConversionPlan plan = buildConversionPlan(stack);
        MediaFile mf = convPlanMediaFile;
        long dur = (mf != null && mf.getFileFormat() != null) ? mf.getFileFormat().getDuration() : 0L;
        return new Long(plan.estimateBytes(dur));
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetConversionPlanReport", CONV_PLAN_PARAMS.length,
        CONV_PLAN_PARAMS, true)
    {
      /**
       * Resolves the guided conversion choices and returns a single multi-line
       * human-readable report (resolved-output summary, estimated output size,
       * and the ordered operations list) for the guided Review screen. This lets
       * the STV bind a single Text widget instead of iterating a String[]. If the
       * choices are invalid for this recording (e.g. AI upscale on a source that
       * is already at/above the target), a friendly one-line reason is returned
       * instead of throwing, so the UI never dead-ends.
       * @return a multi-line conversion plan report, or a reason it is unavailable
       * @since 9.3
       *
       * @declaration public String GetConversionPlanReport(MediaFile SourceMediaFile, String Purpose, String Container, String VideoCodec, String Scaling, int TargetWidth, int TargetHeight, String DynamicRange, String FrameRate, String AudioLayout, String AudioCodec, int AudioBitrateKbps, String Subtitles, int QualityCq);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        try {
          sage.convert.ConversionPlan plan = buildConversionPlan(stack);
          MediaFile mf = convPlanMediaFile;
          long dur = (mf != null && mf.getFileFormat() != null) ? mf.getFileFormat().getDuration() : 0L;
          StringBuilder sb = new StringBuilder();
          sb.append(plan.getSummary()).append('\n');
          sb.append("Estimated size: ").append(formatBytes(plan.estimateBytes(dur))).append('\n');
          sb.append('\n').append("Operations:");
          for (String op : plan.getOperations())
            sb.append('\n').append("  - ").append(op);
          return sb.toString();
        } catch (Exception e) {
          String m = e.getMessage();
          return "This conversion isn't available for this recording:\n  " + (m == null ? e.toString() : m);
        }
      }});

    // --- Guided conversion wizard (stateful draft) ------------------------
    // The STV wizard walks the user through friendly intent questions and drives
    // one server-side ConversionDraft per session by id. Each getter re-resolves
    // the pure recommender over the current answers, so the report always matches
    // the latest choice without the client having to hold any engine state.
    rft.put(new PredefinedJEPFunction("Transcode", "NewConversionDraft", new String[] { "SourceMediaFile" }, true)
    {
      /**
       * Starts a new guided conversion draft for the given recording and returns
       * its id. Feed the id to the SetDraft and GetDraft functions.
       * @param SourceMediaFile the recording to convert
       * @return the new draft id, or -1 if the recording could not be read
       * @since 9.3
       *
       * @declaration public int NewConversionDraft(MediaFile SourceMediaFile);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        MediaFile mf = getMediaFileObj(stack.pop());
        if (mf == null || mf.getFileFormat() == null) return new Integer(-1);
        sage.convert.SourceMedia src = sage.convert.SourceMedia.from(mf.getFileFormat());
        long dur = mf.getFileFormat().getDuration();
        int id = DRAFT_IDS.incrementAndGet();
        DRAFTS.put(new Integer(id), new sage.convert.guided.ConversionDraft(id, src, dur));
        DRAFT_MEDIA.put(new Integer(id), mf);
        return new Integer(id);
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "SetDraftGoal", new String[] { "DraftID", "Goal", "Enabled" }, true)
    {
      /**
       * Adds or removes one creation goal (Menu 1 checkbox) on a draft.
       * @param DraftID the draft id
       * @param Goal the goal token (e.g. PHONE_OFFLINE, IMPROVE_UPSCALE, EXACT_BACKUP)
       * @param Enabled true to select the goal, false to clear it
       * @since 9.3
       *
       * @declaration public void SetDraftGoal(int DraftID, String Goal, boolean Enabled);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        boolean on = evalBool(stack.pop());
        String goal = getString(stack);
        sage.convert.guided.ConversionDraft d = draft(getInt(stack));
        if (d != null) d.setGoal(goal, on);
        return null;
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "SetDraftTransfer", new String[] { "DraftID", "Transfer" }, true)
    {
      /**
       * Sets how the result will be transferred (Menu 2), which bounds size/bitrate.
       * @param DraftID the draft id
       * @param Transfer LOCAL_USB, FAST_WAN, LIMITED_WAN, UNRESTRICTED or CUSTOM
       * @since 9.3
       *
       * @declaration public void SetDraftTransfer(int DraftID, String Transfer);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String t = getString(stack);
        sage.convert.guided.ConversionDraft d = draft(getInt(stack));
        if (d != null) d.setTransfer(t);
        return null;
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "SetDraftDevice", new String[] { "DraftID", "Device" }, true)
    {
      /**
       * Sets the playback device profile (Menu 3), which filters to compatible
       * containers/codecs.
       * @param DraftID the draft id
       * @param Device phone, tablet, computer, tv, unrestricted or unknown
       * @since 9.3
       *
       * @declaration public void SetDraftDevice(int DraftID, String Device);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String dev = getString(stack);
        sage.convert.guided.ConversionDraft d = draft(getInt(stack));
        if (d != null) d.setDevice(dev);
        return null;
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "SetDraftPriority", new String[] { "DraftID", "Priority" }, true)
    {
      /**
       * Sets what to prioritize (Menu 4) when trade-offs are needed.
       * @param DraftID the draft id
       * @param Priority BEST_PICTURE, BALANCED, SMALLER, FASTEST, MAX_COMPAT or PRESERVE_SOURCE
       * @since 9.3
       *
       * @declaration public void SetDraftPriority(int DraftID, String Priority);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String p = getString(stack);
        sage.convert.guided.ConversionDraft d = draft(getInt(stack));
        if (d != null) d.setPriority(p);
        return null;
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "SetDraftPreference", new String[] { "DraftID", "Preference", "Enabled" }, true)
    {
      /**
       * Toggles one preference flag on a draft.
       * @param DraftID the draft id
       * @param Preference smoothmotion, surround, hdr, avoidreencode, hardware or subtitles
       * @param Enabled true to enable the preference
       * @since 9.3
       *
       * @declaration public void SetDraftPreference(int DraftID, String Preference, boolean Enabled);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        boolean on = evalBool(stack.pop());
        String pref = getString(stack);
        sage.convert.guided.ConversionDraft d = draft(getInt(stack));
        if (d != null) d.setPreference(pref, on);
        return null;
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "SetDraftOverride", new String[] { "DraftID", "Field", "Value" }, true)
    {
      /**
       * Overrides one resolved capability (Menu 6 Customize). A null/empty/"auto"
       * value clears the override and restores the recommended value.
       * @param DraftID the draft id
       * @param Field container, videocodec, scaling, width, height, framerate,
       *        audiolayout, audiocodec, audiobitrate, dynamicrange, subtitles or qualitycq
       * @param Value the token/number to force, or null/"auto" to clear
       * @since 9.3
       *
       * @declaration public void SetDraftOverride(int DraftID, String Field, String Value);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String value = getString(stack);
        String field = getString(stack);
        sage.convert.guided.ConversionDraft d = draft(getInt(stack));
        if (d != null) d.setOverride(field, value);
        return null;
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "ClearDraftOverrides", new String[] { "DraftID" }, true)
    {
      /**
       * Clears all manual overrides, returning the draft to the pure recommendation.
       * @param DraftID the draft id
       * @since 9.3
       *
       * @declaration public void ClearDraftOverrides(int DraftID);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.convert.guided.ConversionDraft d = draft(getInt(stack));
        if (d != null) d.clearOverrides();
        return null;
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetDraftGoalEnabled", new String[] { "DraftID", "Goal" }, true)
    {
      /**
       * Whether one creation goal is currently selected, for checkbox rendering.
       * @param DraftID the draft id
       * @param Goal the goal token
       * @return true if the goal is selected
       * @since 9.3
       *
       * @declaration public boolean GetDraftGoalEnabled(int DraftID, String Goal);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String goal = getString(stack);
        sage.convert.guided.ConversionDraft d = draft(getInt(stack));
        return (d != null && d.isGoalEnabled(goal)) ? Boolean.TRUE : Boolean.FALSE;
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetDraftSelection", new String[] { "DraftID", "Field" }, true)
    {
      /**
       * Current token for a single-select field (transfer, device, priority) or an
       * override field, for radio ticks and Customize labels. Returns "AUTO" when
       * nothing is chosen.
       * @param DraftID the draft id
       * @param Field the field name
       * @return the current upper-case token, or "AUTO"
       * @since 9.3
       *
       * @declaration public String GetDraftSelection(int DraftID, String Field);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String field = getString(stack);
        sage.convert.guided.ConversionDraft d = draft(getInt(stack));
        return d == null ? "AUTO" : d.getSelection(field);
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetDraftSelectionIs", new String[] { "DraftID", "Field", "Token" }, true)
    {
      /**
      * True when a single-select field's current value equals Token. Device-aware:
      * device tokens ("phone","tablet","computer","tv","unrestricted","unknown") are
      * matched against the device profile rather than its friendly display name, so
      * picker rows can show a live checkmark on the chosen option.
      * @param DraftID the draft id
      * @param Field the field name (transfer, device, priority, or an override field)
      * @param Token the token to test for
      * @return true when Field currently equals Token
      * @since 9.3
      *
      * @declaration public boolean GetDraftSelectionIs(int DraftID, String Field, String Token);
      */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
       String token = getString(stack);
       String field = getString(stack);
       sage.convert.guided.ConversionDraft d = draft(getInt(stack));
       return (d != null && d.isSelection(field, token)) ? Boolean.TRUE : Boolean.FALSE;
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetDraftGoalsSummary", new String[] { "DraftID" }, true)
    {
      /**
       * Friendly comma list of the currently-selected creation goals, for the
       * goals submenu header (so multi-select state is visible without dynamic
       * per-row labels).
       * @param DraftID the draft id
       * @return the selected goals, or "None selected yet"
       * @since 9.3
       *
       * @declaration public String GetDraftGoalsSummary(int DraftID);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.convert.guided.ConversionDraft d = draft(getInt(stack));
        return d == null ? "None selected yet" : d.getGoalsSummary();
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "SetDraftIntent", new String[] { "DraftID", "Intent" }, true)
    {
      /**
       * Section 1 (single-select "what is this for?"): set one intent, clearing the
       * other intent goals so a profile can't be two intents at once. "custom" or
       * an empty token clears the intent group.
       * @param DraftID the draft id
       * @param Intent one of USB_TV_PLAYBACK, PHONE_OFFLINE, TABLET_OFFLINE,
       *        WAN_SMALLER, REUSABLE_FAVORITE, or "custom"
       * @since 9.3
       *
       * @declaration public void SetDraftIntent(int DraftID, String Intent);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String intent = getString(stack);
        sage.convert.guided.ConversionDraft d = draft(getInt(stack));
        if (d != null) d.setIntent(intent);
        return null;
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetDraftIntentLabel", new String[] { "DraftID" }, true)
    {
      /**
       * Friendly label for the currently-selected intent, for the Section 1 header.
       * @param DraftID the draft id
       * @return the intent label, or "Custom profile"
       * @since 9.3
       *
       * @declaration public String GetDraftIntentLabel(int DraftID);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.convert.guided.ConversionDraft d = draft(getInt(stack));
        return d == null ? "Custom profile" : d.getIntentLabel();
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "SetDraftPreference", new String[] { "DraftID", "Name", "Enabled" }, true)
    {
      /**
       * Toggle an encoding preference (Section 3). Recognised names: surround, hdr,
       * avoidreencode, hardware, subtitles, smoothmotion.
       * @param DraftID the draft id
       * @param Name the preference name
       * @param Enabled whether it is on
       * @since 9.3
       *
       * @declaration public void SetDraftPreference(int DraftID, String Name, boolean Enabled);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        boolean on = evalBool(stack.pop());
        String name = getString(stack);
        sage.convert.guided.ConversionDraft d = draft(getInt(stack));
        if (d != null) d.setPreference(name, on);
        return null;
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetDraftPreferenceEnabled", new String[] { "DraftID", "Name" }, true)
    {
      /**
       * Whether an encoding preference is currently on, for checkbox rendering.
       * @param DraftID the draft id
       * @param Name the preference name
       * @return true if the preference is enabled
       * @since 9.3
       *
       * @declaration public boolean GetDraftPreferenceEnabled(int DraftID, String Name);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String name = getString(stack);
        sage.convert.guided.ConversionDraft d = draft(getInt(stack));
        return (d != null && d.isPreference(name)) ? Boolean.TRUE : Boolean.FALSE;
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetDraftRecommendationReport", new String[] { "DraftID" }, true)
    {
      /**
       * Menu 5 report: the recommended conversion, why it was chosen, the estimated
       * size, and a compatibility line.
       * @param DraftID the draft id
       * @return a multi-line recommendation report
       * @since 9.3
       *
       * @declaration public String GetDraftRecommendationReport(int DraftID);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.convert.guided.ConversionDraft d = draft(getInt(stack));
        if (d == null) return "That conversion draft is no longer available.";
        try {
          return sage.convert.guided.GuidedReports.headlineReport(d.resolve(), d.getDurationMillis());
        } catch (Exception e) {
          return friendly(e);
        }
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetDraftGroupSummaries", new String[] { "DraftID" }, true)
    {
      /**
       * Menu 6 headers: one compact line per capability group for the Customize screen.
       * @param DraftID the draft id
       * @return a list of capability-group summary lines
       * @since 9.3
       *
       * @declaration public String[] GetDraftGroupSummaries(int DraftID);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.convert.guided.ConversionDraft d = draft(getInt(stack));
        if (d == null) return new String[0];
        try {
          return sage.convert.guided.GuidedReports.groupSummaries(d.resolve());
        } catch (Exception e) {
          return new String[] { friendly(e) };
        }
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetDraftConflicts", new String[] { "DraftID" }, true)
    {
      /**
       * Menu 7: one line per conflict, severity-tagged; empty when all compatible.
       * @param DraftID the draft id
       * @return the conflict lines
       * @since 9.3
       *
       * @declaration public String[] GetDraftConflicts(int DraftID);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.convert.guided.ConversionDraft d = draft(getInt(stack));
        if (d == null) return new String[0];
        try {
          return sage.convert.guided.GuidedReports.conflictLines(d.resolve());
        } catch (Exception e) {
          return new String[] { friendly(e) };
        }
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetDraftConflictReport", new String[] { "DraftID" }, true)
    {
      /**
       * The Compatibility-notes panel as a single string: the "why this was
       * recommended" rationale followed by any severity-tagged conflicts, for
       * rendering into one STV Text.
       * @param DraftID the draft id
       * @return the joined notes report
       * @since 9.3
       *
       * @declaration public String GetDraftConflictReport(int DraftID);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.convert.guided.ConversionDraft d = draft(getInt(stack));
        if (d == null) return "No draft.";
        try {
          return sage.convert.guided.GuidedReports.notesReport(d.resolve());
        } catch (Exception e) {
          return friendly(e);
        }
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "DraftHasBlockingConflict", new String[] { "DraftID" }, true)
    {
      /**
       * Whether the current choices cannot be produced (a blocking conflict), so
       * the UI can disable the Convert button.
       * @param DraftID the draft id
       * @return true if there is a blocking conflict or the plan cannot build
       * @since 9.3
       *
       * @declaration public boolean DraftHasBlockingConflict(int DraftID);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.convert.guided.ConversionDraft d = draft(getInt(stack));
        if (d == null) return Boolean.TRUE;
        try {
          sage.convert.guided.Recommendation rec = d.resolve();
          return (rec.hasBlockingConflict() || !rec.isBuildable()) ? Boolean.TRUE : Boolean.FALSE;
        } catch (Exception e) {
          return Boolean.TRUE;
        }
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetDraftReviewReport", new String[] { "DraftID" }, true)
    {
      /**
       * Menu 8: the exact source-vs-output review shown before converting.
       * @param DraftID the draft id
       * @return a multi-line review report
       * @since 9.3
       *
       * @declaration public String GetDraftReviewReport(int DraftID);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.convert.guided.ConversionDraft d = draft(getInt(stack));
        if (d == null) return "That conversion draft is no longer available.";
        try {
          return sage.convert.guided.GuidedReports.reviewReport(d.resolve(), d.getSource(), d.getDurationMillis());
        } catch (Exception e) {
          return friendly(e);
        }
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "StartDraftConversion", new String[] { "DraftID", "DestinationFile", "DeleteOriginal", "ClipTimeStart", "ClipDuration" }, true)
    {
      /**
       * Resolves the draft and enqueues the recommended conversion as a real
       * transcode job, reusing the existing job pipeline (progress/status/cancel
       * via the GetTranscodeJob* functions). The resolved plan is executed through
       * the transcoder's raw-cmdline format path, so the job runs exactly the
       * ffmpeg command the Review screen described. Refuses to start when there is
       * a blocking conflict or the plan cannot build.
       * @param DraftID the draft id
       * @param DestinationFile target file/dir, or null to auto-name in the default location
       * @param DeleteOriginal delete the source recording after a successful, validated conversion
       * @param ClipTimeStart optional start offset into the source in seconds (0 = beginning); only honored when both clip params are supplied
       * @param ClipDuration optional duration in seconds to convert (0 = through end of file); only honored when both clip params are supplied
       * @return the new transcode job id, or -1 if it could not be started
       * @since 9.3
       *
       * @declaration public int StartDraftConversion(int DraftID, java.io.File DestinationFile, boolean DeleteOriginal, long ClipTimeStart, long ClipDuration);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        long clipStart = 0;
        long clipDuration = 0;
        if (curNumberOfParameters == 5)
        {
          clipDuration = getLong(stack);
          clipStart = getLong(stack);
        }
        boolean deleteOriginal = evalBool(stack.pop());
        java.io.File destFile = getFile(stack);
        int id = getInt(stack);
        sage.convert.guided.ConversionDraft d = draft(id);
        MediaFile mf = DRAFT_MEDIA.get(new Integer(id));
        if (d == null || mf == null) return new Integer(-1);
        if (!Permissions.hasPermission(Permissions.PERMISSION_CONVERSION, stack.getUIMgr()))
          return new Integer(-1);
        try {
          sage.convert.guided.Recommendation rec = d.resolve();
          sage.convert.ConversionPlan plan = rec.getPlan();
          if (plan == null || rec.hasBlockingConflict()) return new Integer(-1);
          sage.media.format.ContainerFormat fmt =
              sage.media.format.ContainerFormat.buildFormatFromString(plan.getFormatSpec());
          if (fmt == null) return new Integer(-1);
          if (clipStart < 0L) clipStart = 0L;
          if (clipDuration < 0L) clipDuration = 0L;
          int jobID = Ministry.getInstance().addTranscodeJob(
              mf, "NG Guided Conversion", fmt, destFile, deleteOriginal, clipStart * 1000L, clipDuration * 1000L);
          return new Integer(jobID);
        } catch (Exception e) {
          return new Integer(-1);
        }
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "GetDraftSuggestedName", new String[] { "DraftID" }, true)
    {
      /**
       * A suggested base file name (no extension, no directory) for the draft's
       * source, to pre-fill the Create File name field. Falls back to the source
       * file's base name, then to "Converted".
       * @param DraftID the draft id
       * @return a suggested base name
       * @since 9.3
       *
       * @declaration public String GetDraftSuggestedName(int DraftID);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        MediaFile mf = DRAFT_MEDIA.get(new Integer(getInt(stack)));
        if (mf == null) return "Converted";
        java.io.File src = mf.getFile(0);
        String base = (src == null) ? null : src.getName();
        if (base != null)
        {
          int dot = base.lastIndexOf('.');
          if (dot > 0) base = base.substring(0, dot);
        }
        return (base == null || base.length() == 0) ? "Converted" : base;
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "MakeDraftDestFile", new String[] { "DraftID", "Name" }, true)
    {
      /**
       * Builds an absolute destination File for a Create File conversion: the given
       * base name (sanitized, extension stripped) placed in the source recording's
       * own directory, so a custom name never lands in the server working dir. The
       * proper container extension is appended later by the job. Returns null when
       * Name is blank, which tells StartDraftConversion to auto-name beside the source.
       * @param DraftID the draft id
       * @param Name the desired base file name, or blank/null for automatic
       * @return an absolute destination File, or null for automatic naming
       * @since 9.3
       *
       * @declaration public java.io.File MakeDraftDestFile(int DraftID, String Name);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String name = getString(stack);
        MediaFile mf = DRAFT_MEDIA.get(new Integer(getInt(stack)));
        if (name == null) return null;
        name = name.trim();
        if (name.length() == 0 || mf == null) return null;
        // Strip any directory components and a trailing extension the user typed.
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        // Remove characters that are unsafe in file names.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++)
        {
          char c = name.charAt(i);
          if (c == ':' || c == '/' || c == '\\' || c == '*' || c == '?' ||
              c == '"' || c == '<' || c == '>' || c == '|') c = '_';
          sb.append(c);
        }
        name = sb.toString().trim();
        if (name.length() == 0) return null;
        java.io.File src = mf.getFile(0);
        java.io.File dir = (src == null) ? null : src.getParentFile();
        return (dir == null) ? new java.io.File(name) : new java.io.File(dir, name);
      }});
    rft.put(new PredefinedJEPFunction("Transcode", "DiscardConversionDraft", new String[] { "DraftID" }, true)
    {
      /**
       * Frees a draft once the wizard is done or cancelled.
       * @param DraftID the draft id
       * @since 9.3
       *
       * @declaration public void DiscardConversionDraft(int DraftID);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        Integer key = new Integer(getInt(stack));
        DRAFTS.remove(key);
        DRAFT_MEDIA.remove(key);
        return null;
      }});

    /*
		rft.put(new PredefinedJEPFunction("Transcode", "", 0, new String[] {  })
		{public Object runSafely(Catbert.FastStack stack) throws Exception{
			 return null;
			}});
     */
  }

  /** Declared parameters shared by all BuildConversionPlan* funcs. */
  private static final String[] CONV_PLAN_PARAMS = new String[] {
    "SourceMediaFile", "Purpose", "Container", "VideoCodec", "Scaling",
    "TargetWidth", "TargetHeight", "DynamicRange", "FrameRate", "AudioLayout",
    "AudioCodec", "AudioBitrateKbps", "Subtitles", "QualityCq"
  };

  /** Last source MediaFile popped by {@link #buildConversionPlan}; used for duration in size estimates. */
  private static MediaFile convPlanMediaFile; // set per-call within buildConversionPlan (single-threaded UI eval)

  private static String[] enumNames(Enum<?>[] values)
  {
    String[] out = new String[values.length];
    for (int i = 0; i < values.length; i++) out[i] = values[i].name();
    return out;
  }

  private static int toInt(Object o)
  {
    if (o instanceof Number) return ((Number) o).intValue();
    if (o == null) return 0;
    try { return Integer.parseInt(o.toString().trim()); } catch (Exception e) { return 0; }
  }

  /** Human-friendly byte size for the guided-conversion Review report. */
  private static String formatBytes(long bytes)
  {
    if (bytes <= 0) return "unknown";
    double gb = bytes / (1024.0 * 1024.0 * 1024.0);
    if (gb >= 1.0) return String.format(java.util.Locale.ROOT, "%.2f GB", gb);
    double mb = bytes / (1024.0 * 1024.0);
    return String.format(java.util.Locale.ROOT, "%.0f MB", mb);
  }

  /**
   * Pops the {@link #CONV_PLAN_PARAMS} in reverse declaration order off the
   * Catbert stack, maps them onto a {@link sage.convert.ConversionRequest} plus a
   * {@link sage.convert.SourceMedia} snapshot of the source recording, resolves
   * the host encoder/filter caps, and builds the concrete
   * {@link sage.convert.ConversionPlan}.
   */
  private static sage.convert.ConversionPlan buildConversionPlan(Catbert.FastStack stack)
  {
    int qualityCq = toInt(stack.pop());
    String subtitles = stringOf(stack.pop());
    int audioBitrateKbps = toInt(stack.pop());
    String audioCodec = stringOf(stack.pop());
    String audioLayout = stringOf(stack.pop());
    String frameRate = stringOf(stack.pop());
    String dynamicRange = stringOf(stack.pop());
    int targetHeight = toInt(stack.pop());
    int targetWidth = toInt(stack.pop());
    String scaling = stringOf(stack.pop());
    String videoCodec = stringOf(stack.pop());
    String container = stringOf(stack.pop());
    String purpose = stringOf(stack.pop());
    MediaFile mf = getMediaFileObj(stack.pop());
    convPlanMediaFile = mf;

    sage.convert.VideoCodecChoice vcodec =
        enumOf(sage.convert.VideoCodecChoice.class, videoCodec, sage.convert.VideoCodecChoice.COPY);

    sage.convert.ConversionRequest.Builder rb = sage.convert.ConversionRequest.builder()
        .purpose(enumOf(sage.convert.ConversionPurpose.class, purpose, sage.convert.ConversionPurpose.CUSTOM))
        .container(enumOf(sage.convert.ContainerChoice.class, container, sage.convert.ContainerChoice.KEEP))
        .videoCodec(vcodec)
        .scaling(enumOf(sage.convert.ScalingChoice.class, scaling, sage.convert.ScalingChoice.NONE))
        .dynamicRange(enumOf(sage.convert.DynamicRangeChoice.class, dynamicRange, sage.convert.DynamicRangeChoice.AUTO))
        .frameRate(enumOf(sage.convert.FrameRateChoice.class, frameRate, sage.convert.FrameRateChoice.KEEP))
        .audioLayout(enumOf(sage.convert.AudioLayoutChoice.class, audioLayout, sage.convert.AudioLayoutChoice.KEEP))
        .audioCodec(enumOf(sage.convert.AudioCodecChoice.class, audioCodec, sage.convert.AudioCodecChoice.COPY))
        .audioBitrateKbps(audioBitrateKbps > 0 ? audioBitrateKbps : 160)
        .subtitles(enumOf(sage.convert.SubtitleChoice.class, subtitles, sage.convert.SubtitleChoice.COPY));
    if (qualityCq > 0) rb.qualityCq(qualityCq);
    if (targetWidth > 0 && targetHeight > 0) rb.targetSize(targetWidth, targetHeight);
    sage.convert.ConversionRequest req = rb.build();

    sage.convert.SourceMedia src = sage.convert.SourceMedia.from(mf == null ? null : mf.getFileFormat());
    sage.convert.ConversionEngineCaps caps = sage.convert.ConversionCapsResolver.resolve(vcodec, false);
    return sage.convert.ConversionPlanBuilder.build(req, src, caps);
  }

  private static <E extends Enum<E>> E enumOf(Class<E> type, String token, E dflt)
  {
    if (token == null || token.trim().length() == 0) return dflt;
    try { return Enum.valueOf(type, token.trim().toUpperCase(java.util.Locale.ROOT)); }
    catch (Exception e) { return dflt; }
  }

  private static String stringOf(Object o)
  {
    if (o == null) return null;
    if (o instanceof String) return (String) o;
    return o.toString();
  }

  private static MediaFile getMediaFileObj(Object o)
  {
    if (o instanceof MediaFile) return (MediaFile) o;
    if (o instanceof Airing) return Wizard.getInstance().getFileForAiring((Airing) o);
    return null;
  }

  // --- Guided conversion draft registry ---------------------------------
  private static final java.util.Map<Integer, sage.convert.guided.ConversionDraft> DRAFTS =
      new java.util.concurrent.ConcurrentHashMap<Integer, sage.convert.guided.ConversionDraft>();
  private static final java.util.Map<Integer, MediaFile> DRAFT_MEDIA =
      new java.util.concurrent.ConcurrentHashMap<Integer, MediaFile>();
  private static final java.util.concurrent.atomic.AtomicInteger DRAFT_IDS =
      new java.util.concurrent.atomic.AtomicInteger(0);

  private static sage.convert.guided.ConversionDraft draft(int id)
  {
    return DRAFTS.get(new Integer(id));
  }

  /** One-line, UI-safe rendering of an unexpected resolve failure. */
  private static String friendly(Exception e)
  {
    String m = e.getMessage();
    return "This conversion isn't available for this recording:\n  " + (m == null ? e.toString() : m);
  }
}
