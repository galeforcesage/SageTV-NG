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
 * @author Narflex
 */
public class FFMPEGTranscodeJob extends TranscodeJob
{

  /** Creates a new instance of FFMPEGTranscodeJob */
  public FFMPEGTranscodeJob(MediaFile mf, String inFormatName, sage.media.format.ContainerFormat targetFormat,
      boolean replaceOriginal, java.io.File inDestFile, long inClipStartTime, long inClipDuration)
  {
    super(mf, inFormatName, targetFormat, replaceOriginal, inDestFile, inClipStartTime, inClipDuration);
  }
  public FFMPEGTranscodeJob(int inJobID)
  {
    super(inJobID);
  }

  public void saveToProps()
  {
    super.saveToProps();
    Sage.put(Ministry.TRANSCODE_JOB_PROPS + '/' + jobID + '/' + TRANSCODE_PROCESSOR, "sagetv");
  }

  protected void transcodeNow()
  {
    // ── Automatic AI upscale gate (Real-ESRGAN ncnn-vulkan, chained-job) ──
    // On the FIRST invocation for a brand-new job, decide whether to wrap
    // the encode in a 2-phase chain: phase 1 upscales source → lossless
    // intermediate; phase 2 runs the user's preset against that intermediate
    // with -vf scale=... stripped. Once aiUpscalePhase has been set (1 or
    // 2), subsequent invocations are inside the chain itself and skip the
    // detection.
    if (aiUpscalePhase == 0)
    {
      int srcH = 0;
      try
      {
        sage.media.format.ContainerFormat sf = mf.getFileFormat();
        if (sf != null && sf.getVideoFormat() != null)
          srcH = sf.getVideoFormat().getHeight();
      }
      catch (Throwable t) { /* leave srcH=0 -> rule won't fire */ }
      String rawArgs = (targetFormat == null) ? null
          : targetFormat.getMetadataProperty(sage.media.format.MediaFormat.META_RAW_FFMPEG_CMDLINE);
      int tgtH = Ministry.parseTargetHeightFromPresetArgs(rawArgs);
      int tgtW = Ministry.parseTargetWidthFromPresetArgs(rawArgs);
      if (Ministry.shouldAutoAiUpscale(srcH, tgtH))
      {
        aiUpscalePhase = 1;
        aiTargetWidth = tgtW;
        aiTargetHeight = tgtH;
        intermediateFile = Ministry.makeAiUpscaleIntermediate(jobID, transcodeSegment);
        if (Sage.DBG) System.out.println("FFMPEGTranscodeJob: auto AI upscale ENGAGED jobID=" + jobID
            + " srcH=" + srcH + " tgtH=" + tgtH + " intermediate=" + intermediateFile);
        saveToProps();
      }
    }

    // Phase 1 — spawn the wrapper script directly (not ffmpeg). On
    // success the AiUpscaleMonitor advances aiUpscalePhase=2 and re-enters
    // transcodeNow() to run the normal ffmpeg encode against the
    // intermediate.
    if (aiUpscalePhase == 1)
    {
      try
      {
        // Recover target W/H from preset if we restarted into a persisted phase-1 job.
        if (aiTargetWidth <= 0 || aiTargetHeight <= 0)
        {
          String rawArgs = (targetFormat == null) ? null
              : targetFormat.getMetadataProperty(sage.media.format.MediaFormat.META_RAW_FFMPEG_CMDLINE);
          aiTargetWidth = Ministry.parseTargetWidthFromPresetArgs(rawArgs);
          aiTargetHeight = Ministry.parseTargetHeightFromPresetArgs(rawArgs);
        }
        aiUpscaleProcess = Ministry.spawnAiUpscaleProcess(
            mf.getFile(transcodeSegment), intermediateFile, aiTargetWidth, aiTargetHeight);
      }
      catch (java.io.IOException ex)
      {
        System.out.println("AI UPSCALE PHASE 1 FAILED TO SPAWN: " + ex);
        jobState = TRANSCODE_FAILED;
        saveToProps();
        return;
      }
      monitorThread = new AiUpscaleMonitor();
      monitorThread.setDaemon(true);
      monitorThread.setPriority(Thread.MIN_PRIORITY);
      monitorThread.start();
      return;
    }

    tranny = new FFMPEGTranscoder();
    // Phase 2: source is the lossless intermediate produced by phase 1, and
    // the target preset has its -vf scale=... stripped (upscale already done).
    java.io.File phaseInputFile = (aiUpscalePhase == 2 && intermediateFile != null && intermediateFile.isFile())
        ? intermediateFile : mf.getFile(transcodeSegment);
    sage.media.format.ContainerFormat phaseTarget = (aiUpscalePhase == 2)
        ? Ministry.stripScaleFilterForPhase2(targetFormat) : targetFormat;
    tranny.setSourceFile(null, phaseInputFile);
    tranny.setCaptionSourceFile(mf.getFile(transcodeSegment));
    tranny.setOutputFile(getTempFile(transcodeSegment));
    tranny.setTranscodeFormat(mf.getFileFormat(), phaseTarget);
    if (segmentForLastPass != transcodeSegment)
    {
      currPass = 0;
      segmentForLastPass = transcodeSegment;
    }
    // Don't do multipass for 3GP, PSP or iPod because of the bitrate restrictions
    enableMultipass = Sage.getBoolean("transcoder/enable_multipass_encoding", false) &&
        ("mpeg4".equals(targetFormat.getPrimaryVideoFormat()) || "xvid".equals(targetFormat.getPrimaryVideoFormat())) &&
        !"3gp".equals(targetFormat.getFormatName()) && !"mp4".equals(targetFormat.getFormatName()) && !"psp".equals(targetFormat.getFormatName());
    if (enableMultipass)
    {
      if (currPass == 0)
      {
        // hasn't started yet, on the first pass
        currPass = 1;
      }
      else
      {
        // We're on our subsequent pass now
        currPass++;
      }
      tranny.setPass(currPass);
    }
    if (clipStartTime > 0 || clipDuration > 0)
    {
      if (transcodeSegment == getStartingSegment())
      {
        if (transcodeSegment == getEndingSegment())
        {
          tranny.setEditParameters(clipStartTime, clipDuration);
        }
        else if (clipStartTime > 0)
        {
          tranny.setEditParameters(clipStartTime, 0);
        }
      }
      else if (transcodeSegment == getEndingSegment() && clipDuration > 0)
      {
        tranny.setEditParameters(0, clipDuration - (mf.getStart(transcodeSegment) - mf.getRecordTime() - clipStartTime));
      }
    }
    try
    {
      tranny.startTranscode();
    }
    catch (java.io.IOException ex)
    {
      System.out.println("TRANSCODING ENGINE FAILED TO CREATE");
      jobState = TRANSCODE_FAILED;
      saveToProps();
      tranny = null;
      return;
    }
    monitorThread = new TranscodeMonitor();
    monitorThread.setDaemon(true);
    monitorThread.setPriority(Thread.MIN_PRIORITY);
    monitorThread.start();
  }

  public void cleanupCurrentTranscode()
  {
    super.cleanupCurrentTranscode();
    if (tranny != null)
    {
      tranny.stopTranscode();
      tranny = null;
    }
    Process p = aiUpscaleProcess;
    if (p != null)
    {
      try { p.destroy(); } catch (Throwable t) { /* best effort */ }
      aiUpscaleProcess = null;
    }
    // If we're abandoning before phase 2 ran, the intermediate is orphaned —
    // remove it. If phase 2 ran successfully, the monitor below already
    // cleaned it up.
    if (intermediateFile != null && intermediateFile.isFile() && jobState != COMPLETED)
    {
      try { intermediateFile.delete(); } catch (Throwable t) { /* best effort */ }
    }
  }

  public boolean pauseTranscode()
  {
    FFMPEGTranscoder tempy = tranny;
    if (tempy != null) return tempy.pauseForRecording();
    // Phase 1: best-effort SIGSTOP on the wrapper PID (children may not
    // honor it without process-group propagation; documented limitation).
    return signalAiUpscale("STOP");
  }

  public boolean resumeTranscode()
  {
    FFMPEGTranscoder tempy = tranny;
    if (tempy != null) return tempy.resumeForRecording();
    return signalAiUpscale("CONT");
  }

  public boolean isPausedForRecording()
  {
    FFMPEGTranscoder tempy = tranny;
    return tempy != null && tempy.isPausedForRecording();
  }

  private boolean signalAiUpscale(String sig)
  {
    Process p = aiUpscaleProcess;
    if (p == null || Sage.WINDOWS_OS) return false;
    try
    {
      long pid = p.pid();
      Runtime.getRuntime().exec(new String[]{"kill", "-" + sig, Long.toString(pid)});
      return true;
    }
    catch (Throwable t) { return false; }
  }

  public float getPercentComplete()
  {
    FFMPEGTranscoder tempy = tranny;
    if (tempy != null)
    {
      long fullLength = clipDuration == 0 ? (mf.getDuration(transcodeSegment) - clipStartTime) : clipDuration;
      long currTime = tempy.getCurrentTranscodeStreamTime();
      float rv = ((float) currTime) / fullLength;
      if (enableMultipass)
        rv = (currPass == 1) ? rv/2.0f : (0.5f + rv/2.0f);
      return rv;
    }
    return 0;
  }

  private FFMPEGTranscoder tranny;
  private Thread monitorThread;
  private boolean enableMultipass;
  private int currPass;
  private int segmentForLastPass = -1;
  // AI upscale chained-job state — see Ministry.shouldAutoAiUpscale.
  private volatile Process aiUpscaleProcess;
  private int aiTargetWidth;
  private int aiTargetHeight;
  private class TranscodeMonitor extends Thread
  {
    public void run()
    {
      while (jobState == TRANSCODING && tranny != null)
      {
        if (tranny.isTranscodeDone())
        {
          if (tranny.didTranscodeCompleteOK())
          {
            if (enableMultipass && currPass == 1)
            {
              if (Sage.DBG) System.out.println("First pass for transcoding is done...starting the next pass...");
              transcodeNow();
              return;
            }
            // Successful phase-2 completion of an AI upscale chained job —
            // intermediate file is no longer needed.
            if (aiUpscalePhase == 2 && intermediateFile != null && intermediateFile.isFile())
            {
              try { intermediateFile.delete(); } catch (Throwable t) { /* best effort */ }
            }
            jobState = TRANSCODING_SEGMENT_COMPLETE;
          }
          else
            jobState = TRANSCODE_FAILED;
          Ministry.getInstance().kick();
          saveToProps();
          return;
        }
        try{Thread.sleep(1000);}catch(Exception e){}
      }
    }
  }

  /**
   * Monitor for the phase-1 AI upscale wrapper process. On clean exit
   * (code 0) the job advances to phase 2 and re-enters transcodeNow() so
   * the regular ffmpeg encode pipeline takes over against the produced
   * intermediate file. On non-zero exit the job is marked failed.
   */
  private class AiUpscaleMonitor extends Thread
  {
    AiUpscaleMonitor() { super("AiUpscaleMonitor-" + jobID); }
    public void run()
    {
      Process p = aiUpscaleProcess;
      if (p == null) return;
      // Drain stderr in a side thread so the wrapper's ffmpeg-style progress
      // shows up in sage.stderr; without this the pipe could block.
      Thread drain = new Thread("AiUpscaleStderr-" + jobID)
      {
        public void run()
        {
          try
          {
            java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(aiUpscaleProcess.getErrorStream()));
            String line;
            while ((line = br.readLine()) != null)
            {
              if (Sage.DBG) System.out.println("sage-ai-upscale: " + line);
            }
          } catch (Throwable t) { /* process ended */ }
        }
      };
      drain.setDaemon(true);
      drain.start();
      int code = -1;
      try { code = p.waitFor(); }
      catch (InterruptedException ie) { /* killed */ }
      aiUpscaleProcess = null;
      if (jobState != TRANSCODING) return;
      if (code == 0 && intermediateFile != null && intermediateFile.isFile())
      {
        if (Sage.DBG) System.out.println("AI upscale phase 1 complete jobID=" + jobID
            + "; advancing to phase 2");
        aiUpscalePhase = 2;
        saveToProps();
        transcodeNow();
      }
      else
      {
        System.out.println("AI UPSCALE PHASE 1 FAILED jobID=" + jobID + " exit=" + code);
        jobState = TRANSCODE_FAILED;
        Ministry.getInstance().kick();
        saveToProps();
      }
    }
  }
}
