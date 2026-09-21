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

import java.text.DecimalFormat;

public class FFMPEGTranscoder implements TranscodeEngine
{
  private static final boolean XCODE_DEBUG = Sage.DBG && Sage.getBoolean("media_server/transcode_debug", false);
  // Per-buffer / per-read byte-pushing telemetry: the ring-copy and raw-stdout
  // read lines in readTranscodedData/readFullyTranscodedData and the fill/output
  // consumer loops. Each fires once per ~32KB moved to the client, i.e. thousands
  // of lines a minute during a single play, which buries the decision/setup and
  // ffmpeg-stderr detail and forces frequent log rotation. Gate them separately
  // so media_server/transcode_debug can stay on for the useful lines without the
  // byte flood; set media_server/transcode_debug_io=true to get them back.
  private static final boolean XCODE_DEBUG_IO = Sage.DBG && Sage.getBoolean("media_server/transcode_debug_io", false);
  static final String BITRATE_OPTIONS_SIZE_KEY = "httpls_bandwidth/%s/video_size";
  private static final String[] EMBED_CC_SIDECAR_SUFFIXES = {
      ".srt", ".eng.srt", ".cc.srt", ".vtt"
  };

  // ──────────────────────────────────────────────────────────────────────
  // Phantom-transcode reaping.
  //
  // Every streaming/placeshifter transcode spawns an ffmpeg child
  // (xcodeProcess). Normally stopTranscode() destroys it when the playback
  // session ends. But when the SageTV JVM is shut down (e.g. a `stopsage`
  // during a deploy) while transcodes are still in flight, those children
  // are NOT reliably torn down before the JVM exits -- they get reparented
  // to init (PPID=1) and, because SageTV's custom `-stdinctrl` ffmpeg blocks
  // waiting for stdin commands instead of exiting on stdin-EOF / stdout
  // EPIPE, they linger indefinitely as "phantom" processes, holding CPU,
  // file handles, and (for hwaccel modes) GPU/VRAM contexts until the
  // container itself is restarted.
  //
  // To prevent that, every live ffmpeg child is tracked in this registry and
  // a single JVM shutdown hook force-reaps any survivors on exit. This runs
  // on the same SIGTERM path the existing "SageTV Shutdown" hook uses, so it
  // reliably cleans up regardless of streaming-thread teardown ordering.
  // Gated by media_server/reap_transcodes_on_shutdown (default true).
  // ──────────────────────────────────────────────────────────────────────
  private static final java.util.Set<Process> LIVE_TRANSCODE_PROCESSES =
      java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Process, Boolean>());
  private static final java.util.concurrent.atomic.AtomicBoolean REAP_HOOK_INSTALLED =
      new java.util.concurrent.atomic.AtomicBoolean(false);

  /** Track a freshly-spawned ffmpeg child so it can be reaped on JVM
   * shutdown if its session never gets a clean stopTranscode(). Installs the
   * shutdown hook lazily on first use. */
  static void registerLiveChild(Process p)
  {
    if (p == null) return;
    LIVE_TRANSCODE_PROCESSES.add(p);
    ensureReapHookInstalled();
  }

  /** Stop tracking a child that has been (or is being) cleanly torn down. */
  static void unregisterLiveChild(Process p)
  {
    if (p == null) return;
    LIVE_TRANSCODE_PROCESSES.remove(p);
  }

  private static void ensureReapHookInstalled()
  {
    if (!REAP_HOOK_INSTALLED.compareAndSet(false, true)) return;
    try
    {
      Runtime.getRuntime().addShutdownHook(new Thread("FFMPEGTranscode-Reaper")
      {
        public void run() { reapLiveTranscodeProcesses(); }
      });
    }
    catch (IllegalStateException alreadyShuttingDown)
    {
      // JVM is already in shutdown -- reap synchronously right now instead.
      reapLiveTranscodeProcesses();
    }
    catch (Throwable t)
    {
      if (XCODE_DEBUG) System.out.println("Could not install transcode-reaper shutdown hook: " + t);
    }
  }

  /** Force-kill every still-live tracked ffmpeg child (and its descendants,
   * in case it was wrapped by nice/ionice). Best-effort and time-bounded so
   * it can never stall JVM shutdown. Returns the number of processes it had
   * to kill. */
  static int reapLiveTranscodeProcesses()
  {
    if (!Sage.getBoolean("media_server/reap_transcodes_on_shutdown", true)) return 0;
    java.util.List<Process> snapshot = new java.util.ArrayList<Process>(LIVE_TRANSCODE_PROCESSES);
    int killed = 0;
    // First pass: polite SIGTERM to each live child + descendants.
    for (int i = 0; i < snapshot.size(); i++)
    {
      Process p = snapshot.get(i);
      if (p == null || !p.isAlive()) continue;
      killed++;
      try { p.descendants().forEach(ProcessHandle::destroy); } catch (Throwable t) {}
      try { p.destroy(); } catch (Throwable t) {}
    }
    if (killed > 0)
    {
      // Brief grace for clean exit, then SIGKILL any survivors.
      try { Thread.sleep(800); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
      for (int i = 0; i < snapshot.size(); i++)
      {
        Process p = snapshot.get(i);
        if (p == null || !p.isAlive()) continue;
        try { p.descendants().forEach(ProcessHandle::destroyForcibly); } catch (Throwable t) {}
        try { p.destroyForcibly(); } catch (Throwable t) {}
      }
      if (Sage.DBG) System.out.println("FFMPEGTranscoder: reaped " + killed
          + " in-flight transcode process(es) on shutdown to avoid phantom ffmpeg leaks");
    }
    LIVE_TRANSCODE_PROCESSES.clear();
    return killed;
  }

  // Grace period between SIGTERM and SIGKILL when ending a single session.
  private static final String PROP_KILL_GRACE_MS = "media_server/transcode_kill_grace_ms";

  /**
   * Terminate one ffmpeg child with escalation: SIGTERM (plus descendants),
   * a bounded grace period, then SIGKILL for anything still alive.
   *
   * A plain destroy() is NOT sufficient. SageTV's custom -stdinctrl ffmpeg
   * blocks waiting for stdin commands rather than exiting on stdin-EOF or
   * stdout EPIPE (see the phantom-reaping note at the top of this file), so a
   * polite SIGTERM can be ignored indefinitely. Because the caller then drops
   * its Process reference, an un-escalated survivor becomes unkillable from
   * the JVM and lingers until the container restarts -- holding CPU, VRAM/CUDA
   * contexts, and file handles (including handles to already-deleted
   * recordings, which prevents the disk space from ever being reclaimed).
   *
   * @return true if the process is confirmed dead when this returns.
   */
  static boolean terminateChildWithEscalation(Process p)
  {
    if (p == null) return true;
    if (!p.isAlive()) return true;
    try { p.descendants().forEach(ProcessHandle::destroy); } catch (Throwable t) {}
    try { p.destroy(); } catch (Throwable t) {}

    long graceMs = Sage.getLong(PROP_KILL_GRACE_MS, 1500);
    if (graceMs > 0)
    {
      try
      {
        if (p.waitFor(graceMs, java.util.concurrent.TimeUnit.MILLISECONDS)) return true;
      }
      catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
    if (!p.isAlive()) return true;

    if (Sage.DBG) System.out.println("FFMPEGTranscoder: transcode child ignored SIGTERM after "
        + graceMs + "ms; escalating to SIGKILL to avoid a phantom ffmpeg leak");
    try { p.descendants().forEach(ProcessHandle::destroyForcibly); } catch (Throwable t) {}
    try { p.destroyForcibly(); } catch (Throwable t) {}
    try
    {
      p.waitFor(2000, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    boolean dead = !p.isAlive();
    if (!dead && Sage.DBG)
      System.out.println("FFMPEGTranscoder: WARNING transcode child survived SIGKILL; leaving it registered for shutdown reaping");
    return dead;
  }

  public FFMPEGTranscoder()
  {
  }

  /**
   * Optional audio-codec override for AC-4 source media. When non-null and the
   * source's primary audio is AC-4, the audio codec in the assembled ffmpeg
   * command line is rewritten to this value (and the bitrate adjusted to match)
   * just before exec. Callers set this from MiniPlayer based on the connected
   * client's reported audio capabilities — e.g. "eac3" when the client advertises
   * E-AC-3 (higher quality), otherwise "ac3" as the safe universal fallback.
   */
  public void setAc4SourceAudioCodec(String codec)
  {
    this.ac4SourceAudioCodec = codec;
  }
  private String ac4SourceAudioCodec;

  /**
   * Surface-aware target audio codec (ffmpeg encoder name: {@code eac3}/{@code
   * ac3}/{@code aac}/...) chosen by the decision engine as the best codec the
   * client declares at/below the source quality. Set from the XCODE_SETUP
   * ";acodec=<v>" param (Protocol 2.1 option B). When present it rewrites the
   * static {@code -acodec} in the browserhd* fMP4 transcode modes, so an
   * EAC3-capable fMP4 surface (Safari/TV) gets E-AC-3 5.1 instead of the AAC
   * stereo floor -- while a plain browser (declares only AAC) still gets AAC.
   * No-op for stream-copy (remux) modes.
   */
  private String surfaceTargetAudioCodec;
  /** Source audio channel count to preserve (from ";ac=<n>"); 0 = mode default. */
  private int surfaceTargetAudioChannels;
  public void setSurfaceTargetAudioCodec(String codec) { this.surfaceTargetAudioCodec = codec; }
  public void setSurfaceTargetAudioChannels(int ch) { this.surfaceTargetAudioChannels = ch; }
  // Winning surface's declared AUDIO_MAX_CHANNELS for the enhance AC-4 sidecar
  // (0 = legacy/undeclared). Drives the per-client 5.1-vs-stereo decision that
  // replaced the old playback/gpu_enhance/scale/ac4_sidecar_af global override.
  public void setSidecarMaxAudioChannels(int ch) { this.sidecarMaxAudioChannels = ch; }
  private int sidecarMaxAudioChannels;

  /**
   * Server-side audio EQ/processing plan for this transcode session (Audio
   * Equalizer v1, gated solely by an explicit client request -- see
   * {@code sage.audioproc.AudioProcessingResolver}). This is a PURE AUDIO-STAGE
   * OVERLAY -- it never touches video params and never chooses the audio
   * codec/bitrate itself. When {@link sage.audioproc.AudioProcessingPlan#getResolvedLocation()}
   * is {@code SERVER} and a filtergraph was built, this instance does exactly
   * two things to whatever audio stage the EXISTING selection logic already
   * assembled: (a) disqualifies a plain {@code -acodec copy} by rewriting it
   * to {@link sage.audioproc.AudioProcessingPlan#getTargetAudioCodec()} --
   * itself just an echo of whatever the existing audio-selection logic already
   * chose, never invented here -- because ffmpeg refuses to combine {@code -af}
   * with stream-copy, and (b) appends the plan's {@code -af} filtergraph to
   * whatever audio filter chain (if any) the existing logic already built.
   * Set by the caller (client-state/session wiring) before {@link #startTranscode}
   * runs; null/no-op when the caller never sets it, which keeps every existing
   * playback path byte-for-byte unchanged when this feature isn't in play.
   */
  private sage.audioproc.AudioProcessingPlan serverAudioEqPlan;
  public void setServerAudioProcessingPlan(sage.audioproc.AudioProcessingPlan plan) { this.serverAudioEqPlan = plan; }

  /**
   * True only when a real, buildable server-EQ plan targeting
   * {@link sage.audioproc.AudioProcessingLocation#SERVER} with a non-empty
   * filtergraph is present. Gated solely by the plan the caller sets via
   * {@link #setServerAudioProcessingPlan}, which itself is only ever
   * populated on an explicit client request (see
   * {@code sage.audioproc.AudioProcessingResolver}) -- guaranteeing every
   * other session stays byte-for-byte unchanged.
   */
  boolean isServerAudioEqActive()
  {
    if (serverAudioEqPlan == null) return false;
    if (serverAudioEqPlan.getResolvedLocation() != sage.audioproc.AudioProcessingLocation.SERVER) return false;
    String graph = serverAudioEqPlan.getFilterGraph();
    return graph != null && graph.length() > 0;
  }

  /**
   * Disqualifies a plain {@code -acodec}/{@code -c:a}/{@code -codec:a copy}
   * from {@code xcodeParamsVec} when a server-EQ plan is active, since a
   * filtergraph cannot be applied over stream-copy. Rewrites the value to
   * {@link sage.audioproc.AudioProcessingPlan#getTargetAudioCodec()} -- the
   * plan's echo of whatever the existing audio-selection logic already
   * picked -- so the audio codec choice itself is never made here. Must be
   * called BEFORE {@link #isAudioCopySelected} is evaluated for this build so
   * the downstream {@code -af} construction naturally takes its normal
   * (non-copy) branch. No-op if the plan lacks a usable target codec (avoids
   * corrupting the command line with a blank codec value).
   */
  @SuppressWarnings({"rawtypes","unchecked"})
  void maybeDisqualifyAudioCopyForServerEq(java.util.ArrayList xcodeParamsVec)
  {
    if (!isServerAudioEqActive()) return;
    String targetCodec = serverAudioEqPlan.getTargetAudioCodec();
    if (targetCodec == null || targetCodec.length() == 0)
    {
      if (Sage.DBG) System.out.println("FFMPEGTranscoder: server audio EQ active but plan has no "
          + "targetAudioCodec -- skipping copy disqualification (leaving audio path unchanged)");
      return;
    }
    for (int i = 0; i < xcodeParamsVec.size() - 1; i++)
    {
      Object o = xcodeParamsVec.get(i);
      if (!(o instanceof String)) continue;
      String tok = (String) o;
      if (tok.equals("-acodec") || tok.equals("-c:a") || tok.equals("-codec:a"))
      {
        Object v = xcodeParamsVec.get(i + 1);
        if (v instanceof String && "copy".equalsIgnoreCase((String) v))
        {
          xcodeParamsVec.set(i + 1, targetCodec);
          if (Sage.DBG) System.out.println("FFMPEGTranscoder: server audio EQ disqualifying -acodec copy -> "
              + targetCodec + " (filtergraph requires an active audio encode)");
        }
      }
    }
  }

  /**
   * Appends the server-EQ plan's {@code -af} filtergraph to whatever audio
   * filter chain (if any) the existing logic already built for this session,
   * or adds a fresh {@code -af} pair if none exists. Never replaces an
   * existing filter value -- always comma-joins onto the end, so an in-flight
   * transcode (e.g. AC-4 -> E-AC-3, or the aresample/async drift-correction
   * filter) keeps working exactly as before with the EQ graph layered on top.
   * Call this AFTER the existing {@code -af} construction for this build path
   * has already run.
   */
  @SuppressWarnings({"rawtypes","unchecked"})
  void maybeAppendServerAudioEqFilter(java.util.ArrayList xcodeParamsVec)
  {
    if (!isServerAudioEqActive()) return;
    String graph = serverAudioEqPlan.getFilterGraph();
    int afIdx = -1;
    for (int i = 0; i < xcodeParamsVec.size() - 1; i++)
    {
      Object o = xcodeParamsVec.get(i);
      if (o instanceof String && "-af".equals(o))
      {
        afIdx = i + 1;
        break;
      }
    }
    if (afIdx >= 0)
    {
      Object existing = xcodeParamsVec.get(afIdx);
      String existingVal = (existing == null) ? "" : existing.toString();
      String combined = existingVal.length() == 0 ? graph : existingVal + "," + graph;
      xcodeParamsVec.set(afIdx, combined);
      if (Sage.DBG) System.out.println("FFMPEGTranscoder: appended server audio EQ filtergraph "
          + "(hash=" + serverAudioEqPlan.getFilterGraphHash() + ") onto existing -af -> " + combined);
    }
    else
    {
      xcodeParamsVec.add("-af");
      xcodeParamsVec.add(graph);
      if (Sage.DBG) System.out.println("FFMPEGTranscoder: added server audio EQ -af "
          + "(hash=" + serverAudioEqPlan.getFilterGraphHash() + ") -> " + graph);
    }
  }

  /**
   * Scans {@code xcodeParamsVec} (same token-scan style as
   * {@link #maybeDisqualifyAudioCopyForServerEq}) for a video-stream-copy
   * directive: {@code -vcodec}/{@code -c:v}/{@code -codec:v} followed by
   * {@code copy}. Used to guard the auto-deinterlace injection below --
   * modern ffmpeg hard-errors when a {@code -vf} filtergraph is combined with
   * stream copy ("Filtergraph 'yadif' was specified, but codec copy was
   * selected"), killing the process before it emits a single byte. This
   * affects every copy-video xcodeMode template (mpeg2psremux, mpeg2tsremux,
   * browserhd_remux, browserhd_copyv, audioonly, DVDAudioOnly) whenever the
   * source is flagged interlaced -- both legacy media-extender push modes
   * and the modern NG surface-decision-engine modes reach these templates.
   * Real-encode paths (dynamic/dynamicts/dynamich264/browserhd full
   * transcode/DVD/SVCD/music ladders) never set a copy video codec, so this
   * check is always false there and their argv is completely unaffected.
   */
  @SuppressWarnings({"rawtypes","unchecked"})
  static boolean isVideoCopySelected(java.util.ArrayList xcodeParamsVec)
  {
    for (int i = 0; i < xcodeParamsVec.size() - 1; i++)
    {
      Object o = xcodeParamsVec.get(i);
      if (!(o instanceof String)) continue;
      String tok = (String) o;
      if (tok.equals("-vcodec") || tok.equals("-c:v") || tok.equals("-codec:v"))
      {
        Object v = xcodeParamsVec.get(i + 1);
        if (v instanceof String && "copy".equalsIgnoreCase((String) v))
          return true;
      }
    }
    return false;
  }

  /**
   * Pure decision helper (extracted for direct unit testing) for whether the
   * automatic yadif deinterlace filter should be injected. True exactly when:
   * the caller hasn't already asked for deinterlacing in either the legacy
   * ({@code -deinterlace}) or modern ({@code yadif}) form, the source is
   * flagged interlaced, the target height implies no half-height field
   * output, the {@code xcode_auto_deinterlace} property is enabled, AND the
   * video stage is NOT a stream copy (see {@link #isVideoCopySelected}) --
   * modern ffmpeg hard-errors combining a {@code -vf} filtergraph with
   * {@code -c:v}/{@code -vcodec copy} ("Filtergraph 'yadif' was specified,
   * but codec copy was selected" -> "Error opening output file", zero bytes
   * ever emitted). Copy means copy: no substitute filter is added when this
   * returns false due to the copy check.
   */
  @SuppressWarnings({"rawtypes","unchecked"})
  static boolean shouldAutoAddYadif(java.util.ArrayList xcodeParamsVec, String xcodeParams,
      sage.media.format.VideoFormat srcVideo, int targetHeight, boolean autoDeinterlaceEnabled)
  {
    return xcodeParams.indexOf("-deinterlace") == -1 && xcodeParams.indexOf("yadif") == -1
        && srcVideo != null && srcVideo.isInterlaced() && targetHeight > srcVideo.getHeight()/2
        && autoDeinterlaceEnabled && !isVideoCopySelected(xcodeParamsVec);
  }

  /**
   * Add a {@code yadif} deinterlacer to the output filtergraph WITHOUT creating a
   * second {@code -vf}. ffmpeg accepts only one {@code -vf} per output stream and
   * silently honours the LAST one, so a template that already carries a filter
   * (e.g. the {@code browserhd} full-encode command's {@code -vf format=yuv420p},
   * or a QSV {@code -vf format=nv12,hwupload}) would lose that filter if a bare
   * {@code -vf yadif} were appended -- dropping the encoder's required pixel-format
   * upload and, for GPU encoders, breaking the encode entirely. Instead, when an
   * existing {@code -vf} is present its value is rewritten to {@code yadif,<existing>}
   * so deinterlace runs FIRST (on CPU frames) and then the original chain
   * (format/hwupload) runs -- a single valid filtergraph. When no {@code -vf}
   * exists a fresh {@code -vf yadif} is appended.
   */
  @SuppressWarnings({"rawtypes","unchecked"})
  static void addOrComposeYadif(java.util.ArrayList xcodeParamsVec)
  {
    int vfIdx = -1;
    for (int i = 0; i < xcodeParamsVec.size() - 1; i++)
    {
      Object o = xcodeParamsVec.get(i);
      if (o instanceof String && "-vf".equals(o)) vfIdx = i; // last -vf wins
    }
    if (vfIdx >= 0 && (vfIdx + 1) < xcodeParamsVec.size())
    {
      Object v = xcodeParamsVec.get(vfIdx + 1);
      String existing = (v instanceof String) ? (String) v : "";
      if (existing.indexOf("yadif") >= 0) return; // already deinterlacing
      xcodeParamsVec.set(vfIdx + 1, existing.length() > 0 ? "yadif," + existing : "yadif");
    }
    else
    {
      xcodeParamsVec.add("-vf");
      xcodeParamsVec.add("yadif");
    }
  }

  public long getAvailableTranscodeBytes()
  {
    if (bufferOutput)
      return Math.max(0, xcodeBufferVirtualSize - xcodeBufferVirtualReadPos);
    else
    {
      if (xcodeDone)
        return 0;
      else
        return 65536;
    }
  }

  public long getVirtualReadPosition()
  {
    return xcodeBufferVirtualReadPos;
  }

  public long getVirtualTranscodeSize()
  {
    return xcodeBufferVirtualSize;
  }

  public boolean isTranscodeDone()
  {
    // A transcode is only "done" once the ffmpeg child has actually exited.
    // xcodeDone is set by the stdout/stderr consumer threads' finally-blocks the
    // instant either pipe hits EOF or a transient read exception fires -- which can
    // happen while the process is still very much alive (e.g. under the flood of
    // non-fatal "[dvd] buffer underflow" stderr the 4K->1080p MPEG-4 camera path
    // emits). Reporting "done" then makes didTranscodeCompleteOK() return false
    // (exitValue() throws on a live process -> -1), and MiniPlayer's server-side
    // playback watchdog reacts by tearing the transcoder down and restarting it --
    // the ~2s camera restart / green-frame loop, on a perfectly healthy stream.
    // Gate on real liveness so a running child is never declared finished; genuine
    // exits (clean or crash) still report done because isAlive() is then false.
    return xcodeDone && (xcodeProcess == null || !xcodeProcess.isAlive());
  }

  public boolean didTranscodeCompleteOK()
  {
    if (!xcodeDone) return false;
    if (xcodeProcess != null)
    {
      try
      {
        lastExitCode = xcodeProcess.exitValue();
      }
      catch (IllegalThreadStateException ise)
      {
        lastExitCode = -1;
      }
    }
    return xcodeDone && lastExitCode == 0;
  }

  /** TranscodeEngine interface stub — not used; see pauseForRecording(). */
  public void pauseTranscode() { }

  public void readFullyTranscodedData(byte[] buf, int inOffset, int inLength) throws java.io.IOException
  {
    readFullyTranscodedData(null, buf, inOffset, inLength);
  }
  public void readFullyTranscodedData(java.nio.ByteBuffer buf) throws java.io.IOException
  {
    readFullyTranscodedData(buf, null, buf.position(), buf.remaining());
  }
  private void readFullyTranscodedData(java.nio.ByteBuffer bb, byte[] buf, int inOffset, int inLength) throws java.io.IOException
  {
    int leftToRead = inLength;
    if (bufferOutput)
    {
      long overage = inLength - getAvailableTranscodeBytes();
      int numTries = 50;
      if (XCODE_DEBUG && overage > 0) System.out.println("Waiting for more data to appear in transcode buffer over=" + overage +
          " xcodeDone=" + xcodeDone);

      while (overage > 0 && !xcodeDone && (numTries-- > 0))
      {
        try { Thread.sleep(200); } catch (Exception e){}
        overage = inLength - getAvailableTranscodeBytes();
      }
      if (overage > 0)
      {
        if (overage > leftToRead)
        {
          leftToRead = 0;
          overage = inLength;
        }
        else
        {
          leftToRead -= overage;
        }
      }
      int buffNum = (int) (((xcodeBufferVirtualReadPos - xcodeBufferVirtualOffset) / xcodeBuffer[0].length) + xcodeBufferBaseNum) % xcodeBuffer.length;
      int buffOffset = (int) (xcodeBufferVirtualReadPos - xcodeBufferVirtualOffset) % xcodeBuffer[0].length;
      if (XCODE_DEBUG_IO) System.out.println("Xcode readTranscodedData(" + inLength + ") buffNum=" + buffNum +
          " buffOffset=" + buffOffset);
      int tempOffset = inOffset;
      while (leftToRead > 0)
      {
        int currRead = Math.min((int)leftToRead, xcodeBuffer[buffNum].length - buffOffset);
        if (bb != null)
          bb.put(xcodeBuffer[buffNum], buffOffset, currRead);
        else
          System.arraycopy(xcodeBuffer[buffNum], buffOffset, buf, tempOffset, currRead);
        tempOffset += currRead;
        leftToRead -= currRead;
        buffNum = (buffNum + 1) % xcodeBuffer.length;
        buffOffset = 0;
      }
      if (XCODE_DEBUG_IO) System.out.println("Xcode transferData complete overage=" + overage);
      xcodeBufferVirtualReadPos += inLength;
      synchronized (xcodeSyncLock)
      {
        while (xcodeBufferVirtualReadPos - xcodeBufferVirtualOffset >= xcodeBuffer[0].length)
        {
          // We're reading more than one buffer beyond our start so we can kill that first buffer now
          xcodeBufferBaseNum = (xcodeBufferBaseNum + 1) % xcodeBuffer.length;
          xcodeBufferVirtualOffset += xcodeBuffer[0].length;
          numFilledXcodeBuffers--;
          if (XCODE_DEBUG_IO) System.out.println("Adjusted buffer nums xcodeBufferBaseNum=" + xcodeBufferBaseNum +
              " xcodeBufferVirtualOffset=" + xcodeBufferVirtualOffset + " numFilledBuffers=" + numFilledXcodeBuffers);
          xcodeSyncLock.notifyAll();
        }
      }
      if (overage > 0)
      {
        if (bb != null)
        {
          while (bb.remaining() > 0)
            bb.put((byte) 0xFF);
        }
        else
          java.util.Arrays.fill(buf, (int)(inOffset + inLength - overage), inOffset + inLength, (byte)0xFF);
        if (XCODE_DEBUG_IO) System.out.println("Xcoder Sending overage=" + overage);
      }
    }
    else
    {
      while (leftToRead > 0)
      {
        int numRead;
        if (bb != null)
        {
          if (nioTmpBuf == null)
            nioTmpBuf = new byte[4096];
          numRead = xcodeStdout.read(nioTmpBuf, 0, Math.min(leftToRead, nioTmpBuf.length));
          bb.put(nioTmpBuf, 0, numRead);
        }
        else
          numRead = xcodeStdout.read(buf, inOffset, leftToRead);
        if (XCODE_DEBUG_IO) System.out.println("Xcoder readFully " + numRead + " bytes directly from transcoder and is pushing it out");
        if (numRead == -1)
        {
          // EOF, use the overage buffer for the rest but also push what we have in ours
          if (XCODE_DEBUG) System.out.println("XCoder sending overage for incomplete buffer read");
          if (bb != null)
          {
            while (bb.remaining() > 0)
              bb.put((byte) 0xFF);
          }
          else
            java.util.Arrays.fill(buf, inOffset, inOffset + leftToRead, (byte)0xFF);
          leftToRead = 0;
          xcodeDone = true;
        }
        else
        {
          inOffset += numRead;
          leftToRead -= numRead;
        }
      }
      xcodeBufferVirtualReadPos = xcodeBufferVirtualOffset = xcodeBufferVirtualSize = xcodeBufferVirtualReadPos + inLength;
    }
  }

  protected long estimateTranscodeSeekTimeFromOffset(long offset)
  {
    // This should return the time for the corresponding offset in the transcoded file. We estimate this
    // by analyzing the output of the transcoder and tracking what time it thinks certain byte positions correspond to.
    double streamRate = (lastXcodeStreamPosition / ((double)lastXcodeStreamTime));
    long rv = Math.round(offset / streamRate);
    if (XCODE_DEBUG) System.out.println("Xcode seeking estimRate=" + streamRate + " offset=" + offset + " time=" + rv);
    return rv;
  }

  public long getCurrentTranscodeStreamTime()
  {
    return lastXcodeStreamTime;
  }

  public void seekToPosition(long offset) throws java.io.IOException
  {
    // A live ffmpeg child is the ground truth for "are we transcoding" here --
    // NOT isTranscoding(). isTranscoding() also consults xcodeDone, which the
    // stderr consumer thread's finally-block can flip to true (see startTranscode)
    // while the process is very much alive and still producing on stdout. Using
    // isTranscoding() to gate a (re)start therefore mistakes a healthy stream for
    // a dead one and relaunches ffmpeg on every read -- the orphaned-NVENC / thrash
    // bug. Gate on the process itself instead.
    boolean liveChild = (xcodeProcess != null && xcodeProcess.isAlive());

    if (!bufferOutput)
    {
      // ===== Live, non-seekable fragmented-MP4 streaming (pull-xcode / browserhd /
      // MSE). The output is a forward-only pipe. The client's HTTP Range byte
      // offsets are NOT source seeks and cannot be honoured by repositioning a live
      // pipe. The inline streaming read (sendTranscodeOutputToChannel) always drains
      // the next available bytes from the pipe regardless of the requested offset,
      // so the ONLY job here is: make sure a transcode is running, then let reads
      // flow forward. We must NEVER tear down and relaunch ffmpeg for an in-session
      // byte-offset change -- doing so spawns a fresh NVENC transcode per Range
      // request (leaking orphaned GPU sessions) and re-serves the stream from the
      // top, so resume/seek plays ~1s then thrashes and freezes. A genuine time seek
      // (FF/REW/resume) rebuilds the transcode via a fresh XCODE_SETUP ss=, not
      // through this byte path. =====
      if (!liveChild)
      {
        if (offset != 0)
        {
          // Cold start (first read, or the child genuinely exited). Adopt this
          // offset as the virtual origin of the fresh stream and start from the
          // already-configured seek time (transcodeStartSeekTime, e.g. the resume
          // position). The fMP4 pipe emits a complete ftyp+moov + fragments from the
          // top, so the reader still gets a valid init segment regardless of the
          // byte label it asked for. Previously this threw, which tore down the
          // MediaServerConnection and popped the STV "delete recording?" prompt.
          if (XCODE_DEBUG) System.out.println("seekToPosition cold non-zero offset=" + offset +
              " seekTime=" + transcodeStartSeekTime + "; starting transcode instead of failing");
          xcodeBufferVirtualReadPos = xcodeBufferVirtualOffset = xcodeBufferVirtualSize = offset;
        }
        startTranscode();
      }
      else
      {
        // Already streaming: just realign the read cursor and serve forward.
        xcodeBufferVirtualReadPos = offset;
      }
      return;
    }

    // ===== Buffered ring-buffer mode: byte offsets are real ring positions. =====
    if (!liveChild)
    {
      if (offset != 0)
        xcodeBufferVirtualReadPos = xcodeBufferVirtualOffset = xcodeBufferVirtualSize = offset;
      startTranscode();
      return;
    }
    if (offset < xcodeBufferVirtualOffset ||
        offset >= xcodeBufferVirtualOffset + xcodeBuffer.length*xcodeBuffer[0].length)
    {
      long seekTime = estimateTranscodeSeekTimeFromOffset(offset);
      stopTranscode();
      if (XCODE_DEBUG) System.out.println("Restarting transcode to perform seek so read can continue time=" + seekTime);
      xcodeBufferVirtualReadPos = xcodeBufferVirtualOffset = xcodeBufferVirtualSize = offset;
      transcodeStartSeekTime = seekTime;
      startTranscode();
    }
    else
    {
      xcodeBufferVirtualReadPos = offset;
      synchronized (xcodeSyncLock)
      {
        while (offset - xcodeBufferVirtualOffset >= xcodeBuffer[0].length)
        {
          // We're reading more than one buffer beyond our start so we can kill that first buffer now
          xcodeBufferBaseNum = (xcodeBufferBaseNum + 1) % xcodeBuffer.length;
          xcodeBufferVirtualOffset += xcodeBuffer[0].length;
          numFilledXcodeBuffers--;
          if (XCODE_DEBUG) System.out.println("Adjusted buffer nums from seekToPosition xcodeBufferBaseNum=" + xcodeBufferBaseNum +
              " xcodeBufferVirtualOffset=" + xcodeBufferVirtualOffset + " numFilledBuffers=" + numFilledXcodeBuffers);
          xcodeSyncLock.notifyAll();
        }
      }
    }
  }

  // NOTE: There's two different kinds of seek techniques used here. For time-based we reset all of our position info. For
  // position based we have to track that stuff so we know where the client thinks we are.

  // This will ALWAYS rebuild the transcoder so only use it when necessary
  public void seekToTime(long milliSeekTime) throws java.io.IOException
  {
    stopTranscode();
    transcodeStartSeekTime = milliSeekTime;
    xcodeBufferVirtualReadPos = xcodeBufferVirtualOffset = xcodeBufferVirtualSize = 0;
    startTranscode();
  }

  public void sendTranscodeOutputToChannel(long offset, long length, java.nio.channels.WritableByteChannel chan) throws java.io.IOException
  {
    long leftToRead = length;
    // Option B (media_server/transcode_seekable_buffer): a read whose offset has
    // already fallen behind the small in-memory ring window is served straight
    // from the bounded circular spill history on disk, so an in-generation
    // seek/reconnect (PWA proxy re-fetch, MSE Range behind the frontier, legacy
    // pull re-read) never tears down and restarts ffmpeg at a new -ss. Falls
    // through to the legacy restart path only when the target is older than the
    // retained spill window.
    if (seekableSpill && bufferOutput && offset < xcodeBufferVirtualOffset)
    {
      if (serveFromSpill(offset, length, chan))
        return;
    }
    // Check to see if we're going to need to do a seek to fulfill this read request.
    if ((!bufferOutput && offset != xcodeBufferVirtualOffset) || (bufferOutput && (offset < xcodeBufferVirtualOffset ||
        offset + length > xcodeBufferVirtualOffset + xcodeBuffer.length*xcodeBuffer[0].length)))
    {
      // Seek in the file to the 'offset'
      seekToPosition(offset);
    }

    if (bufferOutput)
    {
      long overage = offset + length - xcodeBufferVirtualSize;
      // Live-stream integrity (browserhd / PWA MSE pull path): NEVER fabricate
      // 0xFF filler INTO a still-producing stream. Padding the unproduced tail of
      // a READ that straddles the live frontier injects undecodable bytes mid
      // fMP4, and the browser MSE demuxer rejects the append
      // (CHUNK_DEMUXER_ERROR_APPEND_FAILED / "failed to prepare video sample for
      // decode"). A well-behaved client only READs within the SIZE-advertised
      // avail (getVirtualTranscodeSize), but an over-read at the live edge must
      // still resolve to REAL bytes, not filler. So while the encoder is alive,
      // wait for the frontier to actually reach the requested span -- in healthy
      // operation the encoder runs ahead of realtime and this clears in well
      // under a second. Only once the transcode has genuinely ended
      // (xcodeDone == true) is a read past the frontier a legitimate past-EOF
      // read that may be padded (the READ protocol still requires a fixed byte
      // count, so the native pull path continues to get its length back).
      long stallCapMs = Sage.getInt("media_server/transcode_read_stall_ms", 60000);
      long waitedMs = 0;
      if (XCODE_DEBUG && overage > 0) System.out.println("Xcoder waiting for more data to appear in transcode buffer over=" + overage +
          " xcodeDone=" + xcodeDone);

      while (overage > 0 && !xcodeDone && waitedMs < stallCapMs)
      {
        try { Thread.sleep(50); } catch (Exception e){}
        waitedMs += 50;
        overage = offset + length - xcodeBufferVirtualSize;
      }
      if (overage > 0)
      {
        // The frontier still hasn't reached the requested span. Either the
        // transcode ended (true EOF -> padding the tail past the final byte is
        // correct) or the encoder is alive but stalled beyond the cap (a genuine
        // stall or a client seek past the live edge). The alive case is the ONLY
        // path that can still place filler on the wire; log it loudly so it can
        // be told apart from real EOF. It preserves the fixed-length READ
        // contract; a real stall trips the client watchdog, which re-opens.
        if (!xcodeDone && Sage.DBG)
          System.out.println("XCODE_LIVE_STALL read past frontier: offset=" + offset
              + " length=" + length + " avail=" + xcodeBufferVirtualSize
              + " overage=" + overage + " waitedMs=" + waitedMs);
        if (overage > leftToRead)
        {
          leftToRead = 0;
          overage = length;
        }
        else
        {
          leftToRead -= overage;
        }
      }
      int buffNum = (int) (((offset - xcodeBufferVirtualOffset) / xcodeBuffer[0].length) + xcodeBufferBaseNum) % xcodeBuffer.length;
      int buffOffset = (int) (offset - xcodeBufferVirtualOffset) % xcodeBuffer[0].length;
      if (XCODE_DEBUG_IO) System.out.println("Xcode transferData(" + offset + ", " + leftToRead + ") buffNum=" + buffNum +
          " buffOffset=" + buffOffset);
      // Bytes actually served out of the ring for this read (excludes any overage
      // filler that was not yet produced). Freeing must be based on the END of this
      // span, not the start offset — otherwise a read that begins at a buffer
      // boundary (e.g. offset 0) frees nothing, the ring stays full, and the encoder
      // deadlocks. This mirrors readFullyTranscodedData(), which frees by the
      // advanced read position rather than the read's start.
      long ringServed = leftToRead;
      // Snapshot-under-lock hardening. Previously the ring slots were written
      // straight to the socket outside xcodeSyncLock, and only afterwards were
      // the consumed slots freed. That left a window: once a slot fell behind
      // the read frontier the XcodeDataConsumer writer could recycle it (fill a
      // new virtual offset into the same physical slot) while a slow/blocking
      // socket write was still copying the OLD contents out of it — producing a
      // torn fMP4 fragment (a lost/overwritten moof header) that the browser MSE
      // demuxer rejects. Enlarging the ring only made this rarer, not impossible.
      //
      // Fix: copy the served span into a private local buffer AND free the
      // consumed slots inside a single xcodeSyncLock block. The writer picks its
      // fill slot under the same lock and cannot advance onto a freed slot until
      // we release it, so the snapshot is a consistent, immutable view. The
      // (potentially blocking) socket write then runs on the local copy outside
      // the lock, so a physical slot can never be read here and refilled by the
      // writer at the same time. arraycopy of <= one client read (a few hundred
      // KB) under the lock is microseconds and never blocks.
      byte[] ringSnapshot = new byte[(int) ringServed];
      long ringReadEnd = offset + ringServed;
      synchronized (xcodeSyncLock)
      {
        int snapPos = 0;
        long copyLeft = ringServed;
        while (copyLeft > 0)
        {
          int currRead = Math.min((int)copyLeft, xcodeBuffer[buffNum].length - buffOffset);
          System.arraycopy(xcodeBuffer[buffNum], buffOffset, ringSnapshot, snapPos, currRead);
          snapPos += currRead;
          copyLeft -= currRead;
          buffNum = (buffNum + 1) % xcodeBuffer.length;
          buffOffset = 0;
        }
        while (ringReadEnd - xcodeBufferVirtualOffset >= xcodeBuffer[0].length)
        {
          // Kill the buffers we've consumed
          xcodeBufferBaseNum = (xcodeBufferBaseNum + 1) % xcodeBuffer.length;
          xcodeBufferVirtualOffset += xcodeBuffer[0].length;
          numFilledXcodeBuffers--;
          if (XCODE_DEBUG_IO) System.out.println("Adjusted buffer nums xcodeBufferBaseNum=" + xcodeBufferBaseNum +
              " xcodeBufferVirtualOffset=" + xcodeBufferVirtualOffset + " numFilledBuffers=" + numFilledXcodeBuffers);
          xcodeSyncLock.notifyAll();
        }
      }
      leftToRead = 0;
      if (ringServed > 0)
        chan.write(java.nio.ByteBuffer.wrap(ringSnapshot, 0, (int) ringServed));
      if (XCODE_DEBUG_IO) System.out.println("Xcode transferData complete overage=" + overage);
      while (overage > 0)
      {
        initOverageBuffer();
        overageBuf.limit((int)Math.min(overage, overageBuf.capacity()));
        if (XCODE_DEBUG_IO) System.out.println("Xcoder sending overage=" + overageBuf.limit());
        int numWritten = chan.write(overageBuf); // just write out FF's
        overage -= numWritten;
        if (XCODE_DEBUG_IO) System.out.println("Xcoder overage sent capacity=" + overageBuf.capacity() + " overage=" + overage + " numWritten=" + numWritten);
      }
      xcodeBufferVirtualReadPos = offset + length;
    }
    else
    {
      if (hackBuf == null)
      {
        hackBuf = java.nio.ByteBuffer.allocate(65536);
      }
      hackBuf.clear();
      byte[] dataBuf = hackBuf.array();
      int myOffset = 0;
      while (leftToRead > 0)
      {
        int currRead = Math.min((int)leftToRead, hackBuf.remaining());
        int numRead = xcodeStdout.read(dataBuf, myOffset, currRead);
        if (XCODE_DEBUG_IO) System.out.println("Xcoder read " + numRead + " bytes directly from transcoder and is pushing it out");
        if (numRead == -1)
        {
          // EOF, use the overage buffer for the rest but also push what we have in ours
          initOverageBuffer();
          if (XCODE_DEBUG) System.out.println("XCoder sending overage for incomplete buffer read");
          overageBuf.limit((int)Math.min(leftToRead, overageBuf.capacity()));
          leftToRead -= chan.write(overageBuf);
          xcodeDone = true;
        }
        else
        {
          hackBuf.position(numRead);
          chan.write(hackBuf);
          hackBuf.clear();
          leftToRead -= numRead;
        }
      }
      xcodeBufferVirtualReadPos = xcodeBufferVirtualOffset = xcodeBufferVirtualSize = offset + length;
    }
  }

  protected void initOverageBuffer()
  {
    if (overageBuf == null)
    {
      overageBuf = java.nio.ByteBuffer.allocate(8192);
      byte[] overageFF = new byte[256];
      java.util.Arrays.fill(overageFF, 0, overageFF.length, (byte)0xFF);
      for (int i = 0; i < 8192; i += 256)
        overageBuf.put(overageFF);
    }
    overageBuf.clear();
  }

  // ===== Option B: bounded seekable spill history =========================
  // A fixed-capacity circular file mirroring the transcode output. Only reads
  // that have fallen behind the live in-memory ring consult it (see
  // sendTranscodeOutputToChannel). Gated OFF by default; when off none of this
  // runs and the transcoder behaves exactly as before. Opened per generation in
  // startTranscode and deleted in stopTranscode, so a genuine restart resets the
  // history and disk is reclaimed the moment the stream ends.
  protected void openSpillIfEnabled()
  {
    closeSpillQuietly();
    seekableSpill = bufferOutput && Sage.getBoolean("media_server/transcode_seekable_buffer", false);
    if (!seekableSpill)
      return;
    spillCapBytes = Math.max(16L << 20, Sage.getLong("media_server/transcode_spill_max_bytes", 512L << 20));
    spillSafetyMargin = Math.max(1L << 20, Sage.getLong("media_server/transcode_spill_safety_margin_bytes", 8L << 20));
    if (spillSafetyMargin >= spillCapBytes)
      spillSafetyMargin = spillCapBytes / 8;
    try
    {
      String dir = Sage.get("media_server/transcode_spill_dir", "");
      xcodeSpillFile = (dir != null && dir.length() > 0)
          ? java.io.File.createTempFile("sagetv_xcode_spill_", ".bin", new java.io.File(dir))
          : java.io.File.createTempFile("sagetv_xcode_spill_", ".bin");
      xcodeSpillChannel = new java.io.RandomAccessFile(xcodeSpillFile, "rw").getChannel();
      if (Sage.DBG) System.out.println("Xcode seekable spill enabled: " + xcodeSpillFile
          + " cap=" + spillCapBytes + " margin=" + spillSafetyMargin);
    }
    catch (java.io.IOException e)
    {
      if (Sage.DBG) System.out.println("Xcode spill open failed; falling back to ring-only: " + e);
      closeSpillQuietly();
      seekableSpill = false;
    }
  }

  protected void closeSpillQuietly()
  {
    java.nio.channels.FileChannel ch = xcodeSpillChannel;
    xcodeSpillChannel = null;
    if (ch != null)
      try { ch.close(); } catch (Exception e){}
    java.io.File f = xcodeSpillFile;
    xcodeSpillFile = null;
    if (f != null)
      try { f.delete(); } catch (Exception e){}
  }

  // Serve [offset, offset+length) from the circular spill, padding any tail that
  // has not been produced yet with 0xFF (matching the ring path). Returns false
  // (serving nothing) when the target is older than the safely-retained window,
  // so the caller falls back to the legacy restart path. The safety margin keeps
  // the read region from colliding with the writer's circular overwrite point.
  protected boolean serveFromSpill(long offset, long length, java.nio.channels.WritableByteChannel chan)
      throws java.io.IOException
  {
    java.nio.channels.FileChannel ch = xcodeSpillChannel;
    if (ch == null)
      return false;
    long size = xcodeBufferVirtualSize;
    long spillBase = Math.max(0, size - spillCapBytes) + spillSafetyMargin;
    if (offset < spillBase)
      return false;
    long canRead = Math.min(length, Math.max(0, size - offset));
    long served = spillReadCircular(ch, spillCapBytes, offset, canRead, chan);
    long overage = length - served;
    while (overage > 0)
    {
      initOverageBuffer();
      overageBuf.limit((int) Math.min(overage, overageBuf.capacity()));
      overage -= chan.write(overageBuf);
    }
    if (XCODE_DEBUG) System.out.println("Xcode served behind-window read from spill offset=" + offset
        + " length=" + length + " served=" + served);
    return true;
  }

  // Circular-file primitives, kept static + package-private so the wrap/segment
  // math is unit tested against a real FileChannel without a live transcoder.
  static void spillWriteCircular(java.nio.channels.FileChannel ch, long cap, byte[] data, int off, int len,
      long virtualPos) throws java.io.IOException
  {
    int done = 0;
    while (done < len)
    {
      long fpos = (virtualPos + done) % cap;
      int seg = (int) Math.min(len - done, cap - fpos);
      java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(data, off + done, seg);
      while (bb.hasRemaining())
        ch.write(bb, fpos + (seg - bb.remaining()));
      done += seg;
    }
  }

  static long spillReadCircular(java.nio.channels.FileChannel ch, long cap, long offset, long canRead,
      java.nio.channels.WritableByteChannel out) throws java.io.IOException
  {
    if (canRead <= 0)
      return 0;
    long served = 0;
    java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate((int) Math.min(canRead, 1 << 16));
    while (served < canRead)
    {
      long fpos = (offset + served) % cap;
      int want = (int) Math.min(Math.min(canRead - served, cap - fpos), bb.capacity());
      bb.clear();
      bb.limit(want);
      int n = ch.read(bb, fpos);
      if (n <= 0)
        break;
      bb.flip();
      while (bb.hasRemaining())
        out.write(bb);
      served += n;
    }
    return served;
  }

  public void setOutputFile(java.io.File theFile)
  {
    outputFile = theFile;
  }

  public void setSourceFile(String server, java.io.File theFile)
  {
    currFile = theFile;
    currServer = server;
  }

  public void setSourceFormat(sage.media.format.ContainerFormat fmt)
  {
    sourceFormat = fmt;
  }

  /**
   * Optional source file used for caption extraction when the transcode input
   * is an intermediate (for example AI-upscale phase 2).
   */
  public void setCaptionSourceFile(java.io.File theFile)
  {
    captionSourceFile = theFile;
  }

  public void setTranscodeFormat(sage.media.format.ContainerFormat inSourceFormat, sage.media.format.ContainerFormat newFormat)
  {
    sourceFormat = inSourceFormat;
    if (Sage.DBG) System.out.println("Set Transcode format source=" + sourceFormat + " dest=" + newFormat);
    xcodeParams = "";
    rawCmdlineMode = false;
    rawCmdlineGlobal = null;
    rawCmdlineContainer = null;

    // Raw-cmdline preset short-circuit: if the destination format carries
    // MRawCmdline=, skip the legacy stream-walk / token translation entirely
    // and stash the verbatim ffmpeg argv for startTranscode() to splice in.
    // The legacy bf=/f=/br= translator below would otherwise mangle modern
    // NVENC presets (cq, hwaccel, scale_npp filter graphs, movflags, ...).
    String rawCl = newFormat.getMetadataProperty(sage.media.format.MediaFormat.META_RAW_FFMPEG_CMDLINE);
    if (rawCl != null && rawCl.length() > 0)
    {
      rawCmdlineMode = true;
      xcodeParams = rawCl.trim();
      String g = newFormat.getMetadataProperty(sage.media.format.MediaFormat.META_RAW_FFMPEG_GLOBAL);
      rawCmdlineGlobal = (g != null && g.length() > 0) ? g.trim() : null;
      String fmt = newFormat.getFormatName();
      rawCmdlineContainer = (fmt != null && fmt.length() > 0) ? fmt.trim() : null;
      if (Sage.DBG) System.out.println("Set Transcode raw-cmdline mode; container=[" + rawCmdlineContainer
          + "] global=[" + rawCmdlineGlobal
          + "] args=[" + xcodeParams + "]");
      return;
    }

    // Set the file format
    String newFormatName = substituteName(newFormat.getFormatName());
    // NOTE: Special case for Zune. It wants a .wmv file extension; but it's an ASF file type so
    // this is how we trick it (by allowing the 'wmv' format; but replacing it with asf here)
    if ("wmv".equals(newFormatName))
      newFormatName = "asf";
    xcodeParams += "-f " + newFormatName;

    String extraProps = newFormat.getMetadataProperty(sage.media.format.MediaFormat.META_COMPRESSION_DETAILS);

    boolean redoAudio = false;
    // Check for stream information
    if (newFormat.getNumberOfStreams() > 0)
    {
      sage.media.format.BitstreamFormat[] bfs = newFormat.getStreamFormats();
      boolean foundVideo = false;
      boolean foundAudio = false;
      boolean needAudioChannels = true;
      for (int i = 0; i < bfs.length; i++)
      {
        if (bfs[i] instanceof sage.media.format.AudioFormat)
        {
          sage.media.format.AudioFormat audformat = (sage.media.format.AudioFormat) bfs[i];
          foundAudio = true;
          String fname = bfs[i].getFormatName();
          if (fname == null || fname.length() == 0 || fname.equalsIgnoreCase("copy"))
          {
            //xcodeParams += " -acodec copy";
            redoAudio = true;
          }
          else
          {
            xcodeParams += " -acodec " + substituteName(fname);
          }
          if (audformat.getBitrate() > 0)
          {
            xcodeParams += " -ab " + (audformat.getBitrate() / 1000);
            preservedAudioBitrate = audformat.getBitrate();
          }
          if (audformat.getChannels() > 0)
          {
            needAudioChannels = false;
            xcodeParams += " -ac " + audformat.getChannels();
          }
          if (audformat.getSamplingRate() > 0)
            xcodeParams += " -ar " + audformat.getSamplingRate();
        }
        else if (bfs[i] instanceof sage.media.format.VideoFormat)
        {
          sage.media.format.VideoFormat vidformat = (sage.media.format.VideoFormat) bfs[i];
          foundVideo = true;
          String fname = bfs[i].getFormatName();
          if (fname == null || fname.length() == 0 || fname.equalsIgnoreCase("copy"))
          {
            xcodeParams += " -vcodec copy";
          }
          else
          {
            xcodeParams += " -vcodec " + substituteName(fname);
          }
          if (vidformat.getBitrate() > 0)
          {
            xcodeParams += " -b " + vidformat.getBitrate()/1000;
            preservedVideoBitrate = vidformat.getBitrate();
          }
          if (vidformat.getWidth() != 0 && vidformat.getHeight() != 0)
            xcodeParams += " -s " + vidformat.getWidth() + "x" + vidformat.getHeight();
          if (vidformat.getFps() > 0)
            xcodeParams += " -r " + vidformat.getFps();
          if (vidformat.getArNum() > 0 && vidformat.getArDen() > 0)
            xcodeParams += " -aspect " + vidformat.getArNum() + ":" + vidformat.getArDen();
        }
      }

      if (!foundVideo)
        xcodeParams += " -vn";
      if (!foundAudio)
        xcodeParams += " -an";
      if (redoAudio && (extraProps == null || extraProps.indexOf(" -acodec ") == -1) && xcodeParams.indexOf(" -acodec ") == -1)
      {
        // Add the audio codec parameters to re-encode the audio in the same format it's already in
        sage.media.format.AudioFormat af = sourceFormat.getAudioFormat();
        if (af != null)
        {
          String aformat = af.getFormatName();
          if (sage.media.format.MediaFormat.AC3.equalsIgnoreCase(aformat) ||
              sage.media.format.MediaFormat.MP2.equalsIgnoreCase(aformat) ||
              sage.media.format.MediaFormat.MP3.equalsIgnoreCase(aformat) ||
              sage.media.format.MediaFormat.AAC.equalsIgnoreCase(aformat))
          {
            xcodeParams += " -acodec " + substituteName(aformat);
          }
          else if (sage.media.format.MediaFormat.AC4.equalsIgnoreCase(aformat) ||
                   sage.media.format.MediaFormat.EAC3.equalsIgnoreCase(aformat))
          {
            // Dolby AC-4 / E-AC-3: route to AC-3 for universal client compatibility.
            // (ffmpeg has no AC-4 encoder; legacy SageTV clients understand AC-3.)
            xcodeParams += " -acodec ac3";
          }
          else if (af.getChannels() <= 2)
            xcodeParams += " -acodec mp2";
          else
            xcodeParams += " -acodec ac3";
          if (af.getChannels() > 0)
            xcodeParams += " -ac " + Integer.toString(af.getChannels());
          if (af.getSamplingRate() > 0)
            xcodeParams += " -ar " + Integer.toString(af.getSamplingRate());
          // Don't blow the bitrate if the source is something like PCM
          if ((af.getBitrate()/1000) > 0 && (af.getBitrate()/1000) < 400)
          {
            xcodeParams += " -ab " + Integer.toString(af.getBitrate() / 1000);
            preservedAudioBitrate = af.getBitrate();
          }
          else if (sage.media.format.MediaFormat.AC3.equalsIgnoreCase(aformat) || xcodeParams.indexOf("-acodec ac3") != -1)
            xcodeParams += " -ab 384"; // for legacy bug where we didn't detect AC3 bitrate
          else //if (sage.media.format.MediaFormat.MP2.equalsIgnoreCase(aformat)) // we should always specify an audio bitrate
            xcodeParams += " -ab 192"; // for legacy bug where we didn't detect MP2 bitrate
        }
      }
      else if (needAudioChannels)
      {
        // Add the audio codec parameters to re-encode the audio in the same format it's already in
        sage.media.format.AudioFormat af = sourceFormat.getAudioFormat();
        if (af != null)
        {
          if (af.getChannels() > 0)
            xcodeParams += " -ac " + Integer.toString(af.getChannels());
        }
      }
      if (foundVideo && (extraProps == null || extraProps.indexOf(" -aspect ") == -1) && xcodeParams.indexOf(" -aspect ") == -1)
      {
        sage.media.format.VideoFormat vf = sourceFormat.getVideoFormat();
        if (vf != null)
        {
          if (vf.getArNum() > 0 && vf.getArDen() > 0)
            xcodeParams += " -aspect " + vf.getArNum() + ":" + vf.getArDen();
          else
            xcodeParams += " -aspect " + vf.getWidth() + ":" + vf.getHeight();
        }
      }
    }

    if (extraProps != null && extraProps.length() > 0)
      xcodeParams += " " + extraProps;

  }

  /**
   * Pick a target WxH for the modern H.264 push path by video-bitrate tier,
   * never upscaling beyond the source. When the source dimensions are unknown
   * (e.g. an as-yet-unparsed HEVC recording) fall back to a 16:9 tier size so we
   * never emit the legacy "-s 0x0". Returns even dimensions.
   */
  private static int[] pickH264PushSize(int videoKbps, sage.media.format.VideoFormat src)
  {
    int th; // target height tier
    if (videoKbps < 1200) th = 360;
    else if (videoKbps < 2500) th = 480;
    else if (videoKbps < 5000) th = 720;
    else th = 1080;
    int sw = (src != null) ? src.getWidth() : 0;
    int sh = (src != null) ? src.getHeight() : 0;
    if (sw <= 0 || sh <= 0)
    {
      int w16 = th * 16 / 9;
      return new int[] { (w16 + 1) / 2 * 2, (th + 1) / 2 * 2 };
    }
    if (th > sh) th = sh; // never upscale beyond source
    int tw = (int) Math.round((double) sw / (double) sh * th);
    return new int[] { (tw + 1) / 2 * 2, (th + 1) / 2 * 2 };
  }

  public void setTranscodeFormat(String str, sage.media.format.ContainerFormat inSourceFormat)
  {
    sourceFormat = inSourceFormat;
    xcodeModeName = str;
    if ("dynamic".equalsIgnoreCase(str))
      dynamicRateAdjust = true;
    else if ("dynamicts".equalsIgnoreCase(str))
    {
      iOSMode = true;
      dynamicRateAdjust = true;
    }
    else if ("dynamicfmp4".equalsIgnoreCase(str))
    {
      // CMAF/fMP4 HLS for browser (hls.js) + Tizen AVPlay. Same encode
      // decisions as dynamicts (iOSMode + dynamicRateAdjust), but ffmpeg's hls
      // muxer writes finalized init.mp4 + seg%d.m4s files directly (Option A) --
      // no stdout ring, no Java-side box cutting. See Phase1-CMAF-VOD plan.
      iOSMode = true;
      dynamicRateAdjust = true;
      fmp4Mode = true;
    }
    else if ("dynamich264".equalsIgnoreCase(str))
    {
      // Modern H.264 MPEG-TS push (bandwidth-aware, GPU-accelerated when
      // available). dynamicRateAdjust keeps the push-buffer bitrate adapter
      // (videorateadapt) active; pushH264 selects the H.264/TS command shape.
      dynamicRateAdjust = true;
      pushH264 = true;
    }
    else if ("audioonly".equalsIgnoreCase(str))
    {
      // Audio-only transcode: pass video through (-vcodec copy), re-encode
      // audio only. Used when the client supports the source video codec
      // (e.g. HEVC) but not the audio codec (e.g. Dolby AC-4).
      // The specific audio codec is picked by MiniPlayer's fallback ladder
      // (eac3 -> ac3 -> aac -> mp2) via setAc4SourceAudioCodec(); default ac3.
      String acodec = (ac4SourceAudioCodec != null && ac4SourceAudioCodec.length() > 0)
          ? ac4SourceAudioCodec : "ac3";
      String defaultBps;
      if ("eac3".equalsIgnoreCase(acodec))      defaultBps = "640k"; // 5.1 surround
      else if ("ac3".equalsIgnoreCase(acodec))  defaultBps = "384k"; // 5.1 surround
      else if ("aac".equalsIgnoreCase(acodec))  defaultBps = "256k"; // 2.0/5.1 (no passthrough)
      else                                      defaultBps = "192k"; // mp2 stereo floor
      String abps = Sage.get("miniplayer/audioonly_audio_bitrate", defaultBps);

      // Video pass-through by default. If client property
      // miniplayer/audioonly_video_codec is set to e.g. "h264_nvenc",
      // "libx264", or "auto", we re-encode video too. Useful for clients whose
      // HW decoder can't handle HEVC Main 10 reliably (e.g. Shield Tube /
      // Tegra X1) — symptom is black screen even though the TS is well-formed.
      // Default kept as "h264_nvenc" so existing NVENC deployments are
      // unaffected. Set to "auto" to let HwEncoder pick the best available
      // backend (nvenc / vaapi / qsv / amf / videotoolbox / libx264 fallback).
      // Set to "copy" to disable re-encode.
      String vcodec = Sage.get("miniplayer/audioonly_video_codec", "h264_nvenc");
      String vparams;
      if (vcodec == null || vcodec.length() == 0 || "copy".equalsIgnoreCase(vcodec))
      {
        vparams = "-vcodec copy";
      }
      else if ("auto".equalsIgnoreCase(vcodec))
      {
        // Generic HW-encoder selection. Defaults to H.264 (broadest client
        // compatibility); operators wanting HEVC out should set vcodec
        // explicitly to hevc_nvenc / hevc_vaapi / etc.
        sage.HwEncoder.Kind k = sage.HwEncoder.pick("h264");
        String enc = sage.HwEncoder.encoderName(k, "h264");
        if (enc == null) enc = "libx264";
        String presetHint = Sage.get("miniplayer/audioonly_hwenc_preset", "p4");
        String preset = sage.HwEncoder.preset(k, presetHint);
        String presetFlag = sage.HwEncoder.presetFlag(k);
        StringBuilder sb = new StringBuilder();
        for (String g : sage.HwEncoder.globalArgs(k)) { sb.append(g).append(' '); }
        sb.append("-vf ").append(sage.HwEncoder.videoFilter(k, "yuv420p", null));
        sb.append(" -c:v ").append(enc);
        if (preset != null && preset.length() > 0)
          sb.append(' ').append(presetFlag).append(' ').append(preset);
        String extra = Sage.get("miniplayer/audioonly_hwenc_params",
            "-b:v 8M -maxrate 12M -bufsize 16M");
        if (extra != null && extra.length() > 0) sb.append(' ').append(extra);
        vparams = sb.toString();
        if (Sage.DBG) System.out.println("FFMPEGTranscoder.audioonly: hwAuto picked "
            + k + " -> " + enc);
      }
      else if ("h264_nvenc".equalsIgnoreCase(vcodec))
      {
        // NVENC H.264 8-bit, broadly compatible. Tunable via
        // miniplayer/audioonly_h264_nvenc_params.
        String nvParams = Sage.get("miniplayer/audioonly_h264_nvenc_params",
            "-preset p4 -tune hq -profile:v high -b:v 8M -maxrate 12M -bufsize 16M");
        vparams = "-vf format=yuv420p -c:v h264_nvenc " + nvParams;
      }
      else if ("libx264".equalsIgnoreCase(vcodec))
      {
        String swParams = Sage.get("miniplayer/audioonly_libx264_params",
            "-preset veryfast -profile:v high -b:v 6M -maxrate 9M -bufsize 12M");
        vparams = "-vf format=yuv420p -c:v libx264 " + swParams;
      }
      else
      {
        // Raw codec name + optional extra params property
        String extra = Sage.get("miniplayer/audioonly_video_codec_extra", "");
        vparams = "-c:v " + vcodec + (extra.length() > 0 ? " " + extra : "");
      }
      xcodeParams = "-f mpegts " + vparams + " -acodec " + acodec + " -b:a " + abps;
      if (Sage.DBG) System.out.println("FFMPEGTranscoder.audioonly: xcodeParams=" + xcodeParams);
    }
    else
    {
      xcodeParams = Sage.get(MediaServer.XCODE_QUALITIES_PROPERTY_ROOT + str, null);
      if (xcodeParams == null)
      {
        // The format itself probably contains the information we need
        String f = "dvd";
        String vcodec = "mpeg4";
        String s = MMC.getInstance().isNTSCVideoFormat() ? "352x240" : "352x288";
        // Workaround issue where AAC audio doesn't transcode properly to mono mp2
        String ac = (Sage.getBoolean("xcode_disable_mono_audio", true) ? "2" : "1");
        String g = "300";
        String bf = "2";
        String acodec = "mp2";
        String r = MMC.getInstance().isNTSCVideoFormat() ? "30" : "25";
        String b = "300";
        String ar = "48000";
        String ab = "64";
        String packetsize = "1024";
        boolean deinterlace = false;//true;
        java.util.StringTokenizer toker = new java.util.StringTokenizer(str, ";");
        while (toker.hasMoreTokens())
        {
          String currToke = toker.nextToken();
          int eqIdx = currToke.indexOf('=');
          if (eqIdx == -1)
            continue;
          String propName = currToke.substring(0, eqIdx);
          String propVal = currToke.substring(eqIdx + 1);
          try
          {
            if ("videocodec".equals(propName))
              vcodec = propVal;
            else if ("audiochannels".equals(propName))
            {
              //Only set property if the source audio has atleast as many channels as the setting
              if(Integer.parseInt(propVal) <= sourceFormat.getAudioFormat().getChannels())
                ac = propVal;
            }
            else if ("audiocodec".equals(propName))
              acodec = propVal;
            else if ("videobitrate".equals(propName))
            {
              preservedVideoBitrate = Integer.parseInt(propVal);
              b = Integer.toString(preservedVideoBitrate/1000);
            }
            else if ("audiobitrate".equals(propName))
            {
              preservedAudioBitrate = Integer.parseInt(propVal);
              ab = Integer.toString(preservedAudioBitrate/1000);
            }
            else if ("gop".equals(propName))
              g = propVal;
            else if ("bframes".equals(propName))
              bf = propVal;
            else if ("fps".equals(propName))
            {
              if("SOURCE".equals(propVal))
              {
                DecimalFormat twoDForm = new DecimalFormat("#.##");
                r = twoDForm.format(sourceFormat.getVideoFormat().getFps());
                g = (Math.round(sourceFormat.getVideoFormat().getFps()) * 10) + "";
              }
              else    
                r = propVal;
            }
            else if ("audiosampling".equals(propName))
              ar = propVal;
            else if ("resolution".equals(propName))
            {
              if ("D1".equals(propVal))
              {
                // Hybrid rule: enforce a 720p floor for HD sources, but do
                // not upscale true SD sources.
                sage.media.format.VideoFormat svf = (inSourceFormat == null) ? null : inSourceFormat.getVideoFormat();
                if (svf != null && svf.getHeight() > 0 && svf.getHeight() < 720 && svf.getWidth() > 0)
                  s = svf.getWidth() + "x" + svf.getHeight();
                else
                  s = "1280x720";
                deinterlace = false;
              }
              else if("720".equals(propVal))
                s = "1280x720";
              else if("1080".equals(propVal))
                s = "1920x1080";
              else if("SOURCE".equals(propVal))
                s = inSourceFormat.getVideoFormat().getWidth() + "x" + inSourceFormat.getVideoFormat().getHeight();
              else
              {
                // Unknown/legacy resolution token. Keep SD at source size;
                // otherwise use the HD floor.
                sage.media.format.VideoFormat svf = (inSourceFormat == null) ? null : inSourceFormat.getVideoFormat();
                if (svf != null && svf.getHeight() > 0 && svf.getHeight() < 720 && svf.getWidth() > 0)
                  s = svf.getWidth() + "x" + svf.getHeight();
                else
                  s = "1280x720";
              }
            }
            else if ("container".equals(propName))
              f = propVal;
          }
          catch (NumberFormatException e)
          {}
        }
        
        xcodeParams = "-f " + f;
        
        if(vcodec.equals("COPY"))
        {
          xcodeParams += " -vcodec copy";
        }
        else
        {
          xcodeParams += " -vcodec " + vcodec  + " -b " + b + " -r " + r + " -s " + s  + " -g " + g + " -bf " + bf + (deinterlace ? " -vf yadif " : "");
        }
        
        if(acodec.equals("COPY"))
        {
          xcodeParams += " -acodec copy";
        }
        else
        {
          xcodeParams += " -acodec " + acodec + " -ab " + ab + " -ar " + ar  + " -ac " + ac;
        }
        
        xcodeParams += " -packetsize " + packetsize;

        // ExoPlayer/android_media3 mis-clocks a raw MPEG2-in-MPEG2-TS copy-remux
        // (observed: client media clock plays clean ~10-25s then races to EOF /
        // loops on 59.94p H.262). This is a client TS-clocking weakness, not a
        // source problem (source PTS verified clean+monotonic). Upstream SageTV
        // never fed MPEG2-TS to such a player -- its native clients decode
        // MPEG2-PS directly. For the copy-remux-to-TS fallback we add MPEG-TS
        // muxer timing hints (frequent PCR + PAT/PMT resent at each keyframe,
        // zeroed mux preload/delay) to give the client stable, frequent clock
        // references. Scoped to the mpegts + video-copy case so nothing else is
        // affected; the exact flag string is live-tunable (revert = empty).
        if ("mpegts".equalsIgnoreCase(f) && "COPY".equals(vcodec))
        {
          String tsFix = Sage.get("miniplayer/mpegts_copy_remux_extra_ffmpeg",
              "-muxpreload 0 -muxdelay 0 -mpegts_flags +resend_headers+pat_pmt_at_frames -pcr_period 20");
          if (tsFix != null && tsFix.trim().length() > 0)
            xcodeParams += " " + tsFix.trim();
        }

        // android_media3's Matroska push path periodically rebuffers (~3s
        // stalls) on long-GOP ATSC 3.0 HEVC. The Matroska muxer defaults to
        // large, keyframe-aligned clusters (cluster_time_limit ~5s and a new
        // cluster only at each video keyframe): with a broadcast HEVC keyframe
        // cadence of ~1-2s the client can't advance its media clock until a
        // whole cluster arrives, so any hiccup in keyframe delivery starves the
        // decoder and shows as a periodic freeze. Force short, frequent clusters
        // (independent of keyframe spacing) plus prompt packet flushing so the
        // client receives timestamps -- and playable bytes -- many times per
        // second. This keeps the proven Matroska+E-AC-3 audio path (no container
        // change) while smoothing the clock. Scoped to the matroska + video-copy
        // live push; live-tunable (revert = empty string).
        if (("matroska".equalsIgnoreCase(f) || "mkv".equalsIgnoreCase(f)) && "COPY".equals(vcodec))
        {
          // The short-cluster/flush flags above make each cluster small, but the
          // Matroska muxer still defaults to max_interleave_delta=1s: it will
          // HOLD already-ready video packets for up to a second waiting on the
          // other stream so the file stays tightly interleaved. On the ATSC 3.0
          // path video is a raw copy while audio is decoded from AC-4 and
          // re-encoded to E-AC-3, and the AC-4 elementary stream starts ~667ms
          // after video (no AC-4 TS parser, so timestamps are coarse). The
          // transcoded audio therefore drifts behind the copied video and
          // periodically lags past the 1s interleave window; when it does, the
          // muxer stops emitting video, bursts a batch once audio catches up,
          // and the client sees a ~1-3s hole in the pushed byte stream with no
          // ffmpeg error. A deeper client buffer only delays when that hole is
          // hit, it cannot fill it. The non-transcoded (copy-audio) live path
          // never lags and never stalls, which is why only ATSC 3.0 freezes.
          //
          // -max_interleave_delta 0 disables that hold: ffmpeg flushes each
          // stream's packets as soon as they are ready instead of waiting to
          // interleave, so the push trickles out steadily. This LOWERS latency
          // (no up-to-1s mux hold) rather than adding a lead buffer. Folded into
          // the same live-tunable property (revert = empty string).
          //
          // A/V-SYNC SAFE: this is a muxer write-ordering flag only. It does not
          // touch any packet PTS/DTS, and it does not touch the AC-4 decode or
          // the -af aformat/aresample=async=0 chain that actually governs audio
          // timing. Every audio and video packet keeps its exact timestamp, so
          // the client (Media3/exoplayer) still lip-syncs by PTS exactly as
          // before. Do NOT "fix" the prior AC-4 audio-desync work by flipping
          // aresample async here -- that is the flag that would reintroduce
          // drift; this one does not.
          String mkvFix = Sage.get("miniplayer/matroska_copy_remux_extra_ffmpeg",
              "-cluster_time_limit 500 -cluster_size_limit 262144 -flush_packets 1 -max_interleave_delta 0");
          if (mkvFix != null && mkvFix.trim().length() > 0)
            xcodeParams += " " + mkvFix.trim();
        }

      }
      dynamicRateAdjust = false;
    }
  }

  public static String getTranscoderPath()
  {
    return getTranscoderPath(null);
  }

  /**
   * Path to the FFmpeg binary. As of the FFmpeg unification work (see
   * docs/FFMPEG_UNIFICATION_PLAN.md) there is a single unified binary at
   * /opt/sagetv/server/ffmpeg with all four SageTV custom flags AND the
   * AC-4 decoder AND NVENC, so there is no longer any need to swap binaries
   * based on source codec.
   *
   * The {@code src} parameter is preserved for API compatibility with
   * pre-unification callers but is no longer consulted for binary
   * selection.
   */
  public static String getTranscoderPath(sage.media.format.ContainerFormat src)
  {
    if (new java.io.File(Sage.getToolPath("SageTVTranscoder")).isFile())
      return Sage.getToolPath("SageTVTranscoder");
    else if (new java.io.File(Sage.getToolPath("ffmpeg")).isFile())
      return Sage.getToolPath("ffmpeg");
    else
      throw new RuntimeException("Transcoder executable is missing!!! checked at: " + Sage.getToolPath("SageTVTranscoder") + " and " + Sage.getToolPath("ffmpeg"));
  }

  /**
   * Hardware-decode input flags for the native placeshifter ATSC 3.0 HEVC path.
   *
   * <p>Live ATSC 3.0 broadcasts (1080p HEVC Main10 in MPEG2-TS) make the software
   * HEVC decoder emit continuous {@code Error constructing the frame RPS} /
   * {@code Could not find ref with POC} errors on missing references, which shows
   * up as video freezes/jitter. NVIDIA's cuvid decoder tolerates those missing
   * refs. Routing decode through {@code -hwaccel cuda -c:v hevc_cuvid} (placed
   * before {@code -i}) fixes the artifacts. No {@code -hwaccel_output_format} is
   * set, so cuvid auto-downloads frames to system memory and the existing
   * software {@code mpeg4} encoder / {@code -f dvd} output are unchanged — the
   * native client keeps its exact current wire format.
   *
   * <p>Guards (all must hold, else an empty list is returned and the caller keeps
   * the current software-decode path):
   * <ul>
   *   <li>source container is MPEG2-TS and primary video is HEVC (the ATSC3 case);</li>
   *   <li>{@link #hwaccelDecode} is not already set — the browserhd/pull-xcode path
   *       manages its own {@code -hwaccel}, so we leave it untouched;</li>
   *   <li>not the HLS ({@link #httplsMode}) or H.264-push ({@link #pushH264}) path,
   *       so PWA and h264 profiles are not affected;</li>
   *   <li>property {@code multimedia/hwaccel/atsc3_hevc_decode} enables it:
   *       {@code off}/{@code none}/{@code false} disables; {@code cuda} forces it;
   *       {@code auto} (default) engages only when an NVENC-capable NVIDIA GPU is
   *       detected (implies cuvid in the unified ffmpeg build).</li>
   * </ul>
   *
   * AC-4 audio is intentionally out of scope (no HW AC-4 decode exists); the
   * {@code ac4 -> ac3} software audio handling and {@code -copytb 0} block are
   * untouched.
   *
   * @return the decoder input args (e.g. {@code [-hwaccel, cuda, -c:v, hevc_cuvid]}),
   *         or an empty list when HW decode should not be engaged.
   */
  java.util.List<String> nativeHevcHwDecodeArgs()
  {
    java.util.List<String> out = new java.util.ArrayList<String>();
    if (sourceFormat == null) return out;
    if (!sage.media.format.MediaFormat.MPEG2_TS.equals(sourceFormat.getFormatName())) return out;
    if (!sage.media.format.MediaFormat.HEVC.equals(sourceFormat.getPrimaryVideoFormat())) return out;
    // Leave the browserhd/pull-xcode path (it sets its own -hwaccel) and the
    // PWA/HLS + H.264-push paths untouched — scope to the native placeshifter.
    if (hwaccelDecode != null && hwaccelDecode.length() > 0) return out;
    if (httplsMode || pushH264) return out;

    String mode = Sage.get("multimedia/hwaccel/atsc3_hevc_decode", "auto");
    if (mode == null) mode = "auto";
    mode = mode.trim();
    if ("off".equalsIgnoreCase(mode) || "none".equalsIgnoreCase(mode) || "false".equalsIgnoreCase(mode))
      return out;

    boolean engage;
    if ("cuda".equalsIgnoreCase(mode))
      engage = true; // explicit force (also used by unit tests)
    else // "auto" (or any other value) -> engage only when NVENC/NVIDIA present
      engage = (sage.HwEncoder.pick("h264") == sage.HwEncoder.Kind.NVENC);

    if (!engage) return out;

    out.add("-hwaccel");
    out.add("cuda");
    out.add("-c:v");
    out.add("hevc_cuvid");
    if (Sage.DBG) System.out.println("FFMPEGTranscoder: native ATSC3 HEVC -> hardware decode "
        + "(-hwaccel cuda -c:v hevc_cuvid); mode=" + mode);
    return out;
  }

  /**
   * If the source's primary audio is AC-4 and a client-preferred codec was set
   * via {@link #setAc4SourceAudioCodec(String)}, rewrite the audio codec in the
   * assembled ffmpeg parameter list. Accepts both legacy ({@code -acodec}) and
   * modern ({@code -c:a}) forms. Also bumps the audio bitrate to a sensible
   * default for E-AC-3 (640k) when not already overridden in the profile.
   */
  @SuppressWarnings({"rawtypes","unchecked"})
  private void maybeOverrideAc4AudioCodec(java.util.ArrayList xcodeParamsVec)
  {
    if (ac4SourceAudioCodec == null || ac4SourceAudioCodec.length() == 0) return;
    if (sourceFormat == null) return;
    if (!sage.media.format.MediaFormat.AC4.equals(sourceFormat.getPrimaryAudioFormat())) return;
    boolean replaced = false;
    int abIndex = -1;
    for (int i = 0; i < xcodeParamsVec.size() - 1; i++)
    {
      Object o = xcodeParamsVec.get(i);
      if (!(o instanceof String)) continue;
      String tok = (String) o;
      if (tok.equals("-acodec") || tok.equals("-c:a") || tok.equals("-codec:a"))
      {
        xcodeParamsVec.set(i + 1, ac4SourceAudioCodec);
        replaced = true;
      }
      else if (tok.equals("-ab") || tok.equals("-b:a"))
      {
        abIndex = i + 1;
      }
    }
    if (!replaced)
    {
      // Profile had no explicit audio codec — append one so AC-4 source actually
      // decodes. Insert before the "-" stdout sentinel, not after it.
      addBeforeOutputSentinel(xcodeParamsVec, "-c:a", ac4SourceAudioCodec);
    }
    // E-AC-3 default bitrate bump for 5.1 — ac3 stays at whatever the profile chose.
    if ("eac3".equalsIgnoreCase(ac4SourceAudioCodec))
    {
      String eac3Bps = Sage.get("miniplayer/eac3_bitrate", "640k");
      if (abIndex >= 0)
        xcodeParamsVec.set(abIndex, eac3Bps);
      else
        // NOTE: a bare add() here lands AFTER the "-" output sentinel (added
        // earlier), so ffmpeg silently ignores it and E-AC-3 encodes at its
        // default ~448k instead of 640k. Insert before the sentinel instead.
        addBeforeOutputSentinel(xcodeParamsVec, "-b:a", eac3Bps);
    }
    if (Sage.DBG) System.out.println("FFMPEGTranscoder: AC-4 source — audio codec overridden to "
        + ac4SourceAudioCodec + (abIndex >= 0 ? " (bitrate slot=" + abIndex + ")" : ""));
  }

  /**
   * Insert an output option immediately before the {@code "-"} stdout sentinel
   * so it is applied to the streamed output, never appended after it (where
   * ffmpeg silently ignores it). Falls back to a plain append when there is no
   * sentinel (offline file output).
   */
  @SuppressWarnings({"rawtypes","unchecked"})
  private static void addBeforeOutputSentinel(java.util.ArrayList xcodeParamsVec, String opt, String val)
  {
    int outIdx = xcodeParamsVec.lastIndexOf("-");
    if (outIdx < 0) { xcodeParamsVec.add(opt); xcodeParamsVec.add(val); }
    else { xcodeParamsVec.add(outIdx, opt); xcodeParamsVec.add(outIdx + 1, val); }
  }

  /**
   * Final hygiene pass for the native placeshifter remux/copy push (live ATSC
   * 3.0 HEVC/AC-4). Two independent fixes, both scoped to a stream-COPY output:
   *
   * <ol>
   *   <li><b>Drop the spurious NVDEC input decoder.</b> {@link #nativeHevcHwDecodeArgs()}
   *   prepends {@code -hwaccel cuda -c:v hevc_cuvid} to tolerate the missing-ref
   *   (RPS/POC) errors the SOFTWARE HEVC decoder throws — but that only matters
   *   when ffmpeg actually decodes. On a {@code -vcodec copy} output no video is
   *   decoded, so the cuvid decoder is never consumed; it only spins up a live
   *   NVDEC session whose surface-pool re-inits on ATSC 3.0 discontinuities can
   *   stall the shared demux&rarr;copy pipeline. Remove it on the copy path.</li>
   *
   *   <li><b>Re-base timestamps.</b> ATSC 3.0 source PTS/DTS can start non-zero
   *   or step at recording/signal boundaries. Copied verbatim into the strict
   *   Matroska (or MPEG-TS) push muxer, that makes the client's media clock jump
   *   backward &mdash; a multi-second freeze while the recording keeps growing
   *   (observed: base media time snapping 462s&rarr;299s). Add
   *   {@code -avoid_negative_ts make_zero}, exactly like the proven live
   *   {@code hevc_nvenc} MPEG-TS path and the {@code MediaServer} TS remux.</li>
   * </ol>
   *
   * Runs after every codec-swapping override (so it observes the final video
   * codec — e.g. GPU-enhance may have swapped {@code copy}&rarr;{@code nvenc}).
   */
  @SuppressWarnings({"rawtypes","unchecked"})
  private void maybeFixNativeCopyPushCommand(java.util.ArrayList xcodeParamsVec)
  {
    int iIdx = xcodeParamsVec.indexOf("-i");
    if (iIdx < 0) return;

    boolean videoCopy = false, audioCopy = false;
    String outMux = null;
    for (int i = iIdx + 2; i + 1 < xcodeParamsVec.size(); i++)
    {
      Object o = xcodeParamsVec.get(i);
      if (!(o instanceof String)) continue;
      String tok = (String) o;
      Object nvo = xcodeParamsVec.get(i + 1);
      String val = (nvo instanceof String) ? (String) nvo : null;
      if (("-vcodec".equals(tok) || "-c:v".equals(tok) || "-codec:v".equals(tok)) && "copy".equalsIgnoreCase(val))
        videoCopy = true;
      else if (("-acodec".equals(tok) || "-c:a".equals(tok) || "-codec:a".equals(tok)) && "copy".equalsIgnoreCase(val))
        audioCopy = true;
      else if ("-f".equals(tok) && val != null && outMux == null)
        outMux = val;
    }

    // FIX 1 — a video stream-copy never decodes, so the NVDEC cuvid input
    // decoder is pure overhead and a live-stall source. Strip it.
    if (videoCopy)
    {
      for (int i = 0; i + 1 < iIdx; i++)
      {
        Object o = xcodeParamsVec.get(i);
        if (("-c:v".equals(o) || "-vcodec".equals(o)) && "hevc_cuvid".equals(xcodeParamsVec.get(i + 1)))
        {
          int from = i, to = i + 1;
          if (i >= 2 && "-hwaccel".equals(xcodeParamsVec.get(i - 2)) && "cuda".equals(xcodeParamsVec.get(i - 1)))
            from = i - 2; // also drop the paired "-hwaccel cuda"
          for (int r = to; r >= from; r--) xcodeParamsVec.remove(r);
          if (Sage.DBG) System.out.println("FFMPEGTranscoder: video is -vcodec copy — dropped spurious "
              + "-hwaccel cuda -c:v hevc_cuvid input decoder (no decode occurs on a copy)");
          break;
        }
      }
    }

    // FIX 2 — re-base timestamps on the streaming copy push (matroska/mpegts)
    // so a non-zero / discontinuous source PTS cannot rewind the client clock.
    boolean pushRemux = (videoCopy || audioCopy) && outputFile == null && !fmp4Mode
        && ("matroska".equalsIgnoreCase(outMux) || "mpegts".equalsIgnoreCase(outMux));
    if (pushRemux && !xcodeParamsVec.contains("-avoid_negative_ts") && !xcodeParamsVec.contains("-copyts"))
    {
      addBeforeOutputSentinel(xcodeParamsVec, "-avoid_negative_ts", "make_zero");
      if (Sage.DBG) System.out.println("FFMPEGTranscoder: copy push (" + outMux + ") — added "
          + "-avoid_negative_ts make_zero (re-base timestamps; prevent client media-clock rewind/freeze)");
    }
  }

  /**
   * Protocol 2.1 (option B): when a surface-aware target audio codec/channels
   * were supplied via the XCODE_SETUP ";acodec=;ac=" params, rewrite the audio
   * codec + channel count + bitrate in the assembled command. Realizes the
   * decision engine's "best codec the client supports at/below source" pick
   * instead of the static "-acodec aac -ac 2" floor in the browserhd* modes.
   * No-op when unset or when audio is stream-copied (remux modes keep source).
   */
  @SuppressWarnings({"rawtypes","unchecked"})
  private void maybeOverrideSurfaceAudio(java.util.ArrayList xcodeParamsVec)
  {
    if (surfaceTargetAudioCodec == null || surfaceTargetAudioCodec.length() == 0) return;
    if (isAudioCopySelected(xcodeParamsVec)) return; // remux: preserve source codec
    int acodecIdx = -1, acIdx = -1, abIdx = -1;
    for (int i = 0; i < xcodeParamsVec.size() - 1; i++)
    {
      Object o = xcodeParamsVec.get(i);
      if (!(o instanceof String)) continue;
      String tok = (String) o;
      if (tok.equals("-acodec") || tok.equals("-c:a") || tok.equals("-codec:a")) acodecIdx = i + 1;
      else if (tok.equals("-ac")) acIdx = i + 1;
      else if (tok.equals("-ab") || tok.equals("-b:a")) abIdx = i + 1;
    }
    if (acodecIdx >= 0) xcodeParamsVec.set(acodecIdx, surfaceTargetAudioCodec);
    else { xcodeParamsVec.add("-c:a"); xcodeParamsVec.add(surfaceTargetAudioCodec); }
    int ch = surfaceTargetAudioChannels;
    if (ch > 0)
    {
      if (acIdx >= 0) xcodeParamsVec.set(acIdx, Integer.toString(ch));
      else { xcodeParamsVec.add("-ac"); xcodeParamsVec.add(Integer.toString(ch)); }
    }
    else ch = 2; // for bitrate scaling only; leave any existing -ac untouched
    String abps = surfaceAudioBitrate(surfaceTargetAudioCodec, ch);
    if (abIdx >= 0) xcodeParamsVec.set(abIdx, abps);
    else { xcodeParamsVec.add("-b:a"); xcodeParamsVec.add(abps); }
    if (Sage.DBG) System.out.println("FFMPEGTranscoder: surface audio override -> codec="
        + surfaceTargetAudioCodec + " channels="
        + (surfaceTargetAudioChannels > 0 ? Integer.toString(surfaceTargetAudioChannels) : "src")
        + " bitrate=" + abps);
  }

  /** Per-codec/per-channel audio bitrate for the surface audio override. */
  private static String surfaceAudioBitrate(String codec, int channels)
  {
    boolean surround = channels >= 5;
    if ("eac3".equalsIgnoreCase(codec)) return Sage.get("miniplayer/eac3_bitrate", surround ? "640k" : "192k");
    if ("ac3".equalsIgnoreCase(codec))  return surround ? "448k" : "192k";
    // aac/opus/etc: ~64 kbps per channel, clamped to [96k, 512k].
    int kbps = Math.min(512, Math.max(96, channels * 64));
    return kbps + "k";
  }

  /**
   * Max output channels the lossy audio ENCODER named {@code codec} can emit.
   * The AC-3 family is the trap: E-AC-3 the *format* carries 7.1, but the
   * libavcodec {@code eac3}/{@code ac3} encoders both top out at 5.1 (6ch)
   * (verified: {@code ffmpeg -h encoder=eac3} lists no 7.1 layout). Feeding a
   * 7.1 (8ch) source to them unclamped makes ffmpeg abort with "Specified
   * channel layout is not supported" -- no audio stream is produced and the
   * client shows "no signal". Returns 0 for codecs we must NOT clamp
   * (aac/opus/flac/pcm handle >= 8) and for copy/unknown.
   */
  static int maxChannelsForAudioCodec(String codec)
  {
    if (codec == null) return 0;
    String c = codec.trim().toLowerCase(java.util.Locale.ROOT);
    if (c.equals("ac3") || c.equals("a_ac3") || c.equals("eac3")
        || c.equals("e-ac-3") || c.equals("ec-3") || c.equals("ec3")) return 6;
    if (c.equals("mp2") || c.equals("mpg1l2") || c.equals("mp3") || c.equals("mpg1l3")) return 2;
    return 0; // aac/libfdk_aac/opus/flac/pcm/copy/unknown -> no clamp
  }

  /**
   * Final safety pass: clamp the re-encode channel count to what the chosen
   * audio ENCODER can actually emit. Without this a 7.1 source transcoded to
   * ac3/eac3 asks for a layout the encoder does not support, ffmpeg exits before
   * opening the output, and the client renders nothing ("no signal"). ffmpeg
   * auto-downmixes to the requested {@code -ac}, so 7.1 -> 5.1 is clean. No-op
   * for stream-copy and for codecs that already handle the source width.
   */
  @SuppressWarnings({"rawtypes","unchecked"})
  void clampAudioChannelsToEncoder(java.util.ArrayList xcodeParamsVec)
  {
    if (isAudioCopySelected(xcodeParamsVec)) return;
    int acodecIdx = -1, acIdx = -1;
    for (int i = 0; i < xcodeParamsVec.size() - 1; i++)
    {
      Object o = xcodeParamsVec.get(i);
      if (!(o instanceof String)) continue;
      String tok = (String) o;
      if (tok.equals("-acodec") || tok.equals("-c:a") || tok.equals("-codec:a")) acodecIdx = i + 1;
      else if (tok.equals("-ac")) acIdx = i + 1;
    }
    if (acodecIdx < 0) return; // no explicit encoder named -> nothing to clamp against
    Object codecObj = xcodeParamsVec.get(acodecIdx);
    if (!(codecObj instanceof String)) return;
    String codec = (String) codecObj;
    if ("copy".equalsIgnoreCase(codec)) return;
    int max = maxChannelsForAudioCodec(codec);
    if (max <= 0) return; // encoder handles wide layouts; leave alone
    int requested = -1;
    if (acIdx >= 0)
    {
      try { requested = Integer.parseInt(((String) xcodeParamsVec.get(acIdx)).trim()); }
      catch (Exception e) { requested = -1; }
    }
    if (requested < 0 && sourceFormat != null && sourceFormat.getAudioFormat() != null)
      requested = sourceFormat.getAudioFormat().getChannels();
    if (requested <= max) return; // 5.1 or narrower -> nothing to do
    if (acIdx >= 0) xcodeParamsVec.set(acIdx, Integer.toString(max));
    else addBeforeOutputSentinel(xcodeParamsVec, "-ac", Integer.toString(max));
    if (Sage.DBG) System.out.println("FFMPEGTranscoder: clamped " + codec + " channels "
        + requested + " -> " + max + " (AC-3-family encoder tops out at 5.1; an unclamped 7.1"
        + " layout aborts the encode -> client 'no signal')");
  }

  /**
   * Returns true when {@code -acodec copy} (or the equivalent {@code -c:a} /
   * {@code -codec:a} spelling) has already been added to {@code xcodeParamsVec}.
   * Modern ffmpeg (6.1+ / the elliotclee fork) refuses {@code -af} together
   * with a stream-copied audio output — it errors out with "Filtering and
   * streamcopy cannot be used together" / "Error opening output files:
   * Invalid argument" and exits immediately. Older ffmpeg silently dropped
   * the filter in copy mode, so any audio filter (e.g. {@code aresample=async=N})
   * was already a no-op there. Callers must gate audio-filter emits on this
   * check to avoid triggering the tight ffmpeg respawn loop HTTPLSServer would
   * otherwise fall into (observed on iOS/PWA HLS playback, 2026-07).
   */
  @SuppressWarnings({"rawtypes"})
  private static boolean isAudioCopySelected(java.util.ArrayList xcodeParamsVec)
  {
    if (xcodeParamsVec == null) return false;
    for (int i = 0; i < xcodeParamsVec.size() - 1; i++)
    {
      Object o = xcodeParamsVec.get(i);
      if (!(o instanceof String)) continue;
      String tok = (String) o;
      if (tok.equals("-acodec") || tok.equals("-c:a") || tok.equals("-codec:a"))
      {
        Object v = xcodeParamsVec.get(i + 1);
        if (v instanceof String && "copy".equalsIgnoreCase((String) v)) return true;
      }
    }
    return false;
  }

  /**
   * True when this transcode COPIES the video track into an MP4-family
   * (fragmented) output — i.e. the {@code browserhd_copyv} / {@code browserhd_remux}
   * pull-xcode modes. On these paths the output track's width/height and the
   * {@code hvcC}/{@code avcC} decoder config come ENTIRELY from the source
   * stream's in-band parameter sets (VPS/SPS/PPS for HEVC), because there is no
   * encoder to supply them. Detected from the verbatim preset string
   * ({@link #xcodeParams}) since the streaming path writes to stdout (so the
   * filename-based fallback in {@link #outputMuxFormat()} is unavailable).
   */
  boolean isVideoCopyToFmp4()
  {
    if (xcodeParams == null) return false;
    boolean videoCopy = xcodeParams.indexOf("-c:v copy") != -1
        || xcodeParams.indexOf("-vcodec copy") != -1;
    boolean mp4Out = xcodeParams.indexOf("-f mp4") != -1;
    return videoCopy && mp4Out;
  }

  /**
   * True only for the confirmed modern NG surface-decision-engine copy-family
   * xcodeModes: {@code mpeg2tsremux} (Tizen/AVPlay TV family), {@code
   * browserhd_remux} and {@code browserhd_copyv} (pwa_mse/browser family).
   * Deliberately name-scoped (via {@link #xcodeModeName}, captured verbatim
   * from {@link #setTranscodeFormat(String, sage.media.format.ContainerFormat)})
   * rather than inferred from output-format flags: {@code audioonly} ALSO
   * emits {@code -f mpegts} and {@code mpeg2psremux} ALSO copies video, and
   * both are reachable from the legacy Placeshifter/older-MiniClient push
   * path (see {@code MiniPlayer.java}'s {@code !ngSession} block) — a
   * format-flag heuristic would silently pull those legacy paths into the
   * on-demand (VOD) probesize/analyzeduration tuning below, which is
   * explicitly out of scope for that fix. Used only to gate Fix B (VOD probe
   * tuning); never used to gate Fix A's copy-safety guard, which applies
   * unconditionally to every xcodeMode.
   */
  boolean isModernCopyFamilyXcodeMode()
  {
    return "mpeg2tsremux".equalsIgnoreCase(xcodeModeName)
        || "browserhd_remux".equalsIgnoreCase(xcodeModeName)
        || "browserhd_copyv".equalsIgnoreCase(xcodeModeName);
  }

  /**
   * True for an <b>inline custom</b> copy-family push mode of the shape
   * {@code container=<c>;videocodec=COPY;audiocodec=...} — the "generic push
   * container" transcode an Android-class NG client (ExoPlayer/IJK) receives.
   * This is the PUSH analogue of the named copy-family modes above: the video is
   * stream-copied (so {@link sage.enhance.GpuEnhancePipeline#rewriteArgv} can
   * swap the {@code -vcodec copy} for the NVENC HEVC upscale), and enhancement
   * is delivered over the client's native MATROSKA push rather than pull-xcode
   * (which these clients do not advertise). Deliberately kept SEPARATE from
   * {@link #isModernCopyFamilyXcodeMode} so it gates <b>only</b> the enhancement
   * rewrite (below), never the VOD probesize/analyzeduration tuning, whose
   * name-scoped set must stay unchanged. Matches only an explicit
   * {@code videocodec=copy} in the inline {@code key=value;...} form, so the
   * named legacy modes {@code audioonly} / {@code mpeg2psremux} (no {@code
   * container=} token) never match.
   */
  boolean isEnhanceableCopyContainerMode()
  {
    if (xcodeModeName == null) return false;
    String m = xcodeModeName.toLowerCase(java.util.Locale.ROOT);
    return m.indexOf("container=") >= 0 && m.indexOf("videocodec=copy") >= 0;
  }

  /**
   * True for a non-copy re-encode PLAYBACK mode that can still be enhanced in
   * place because it already decodes and re-encodes on the GPU. Currently only
   * {@code browserhd} — the H.264 fMP4 re-encode the browser/PWA negotiates for
   * sources that cannot be stream-copied (e.g. MPEG-2 DVR content). Unlike the
   * copy-family modes, enhancement here keeps the H.264 codec (browser MSE
   * generally cannot decode the HEVC the copy path emits) and only adds the CUDA
   * scale/deinterlace chain plus a higher bitrate. Still gated on {@code
   * !activeFile} exactly like the copy-family modes, so a recording is untouched.
   */
  boolean isEnhanceableReencodeMode()
  {
    return "browserhd".equalsIgnoreCase(xcodeModeName);
  }

  /**
   * Fix B gate: true when this instance's on-demand (VOD, {@code !activeFile})
   * probesize/analyzeduration tuning should apply -- i.e. a source format is
   * known to derive sane values from, AND the xcodeMode is one of the
   * confirmed modern NG copy-family templates (see
   * {@link #isModernCopyFamilyXcodeMode}). Extracted as a pure, directly
   * testable decision so the VOD-tuning scope can be verified without
   * invoking the full argv-assembly method.
   */
  boolean shouldApplyVodProbeTuning()
  {
    return !activeFile && isModernCopyFamilyXcodeMode() && sourceFormat != null;
  }

  /**
   * Honor a pending {@link #enhanceRequest} by rewriting the assembled argv to
   * add the GPU upscale/deinterlace pipeline. Every guard that could forbid it is
   * checked HERE, at the last possible moment, so no earlier caller can leak an
   * enhanced command out by accident:
   *
   * <ol>
   *   <li><b>Interlock/dry-run</b> — {@link sage.enhance.EnhancementDryRun#isLive()}
   *       is false unless the pipeline is wired AND the admin cleared dry-run.</li>
   *   <li><b>Recording safety</b> — only copy-family PLAYBACK modes
   *       ({@link #isModernCopyFamilyXcodeMode()}) and only when {@code !activeFile}.
   *       An active-file (in-progress recording) source is never rewritten.</li>
   *   <li><b>Capacity + recording veto</b> — {@link sage.enhance.GpuGovernor}
   *       admission, which runs the {@link sage.enhance.RecordingGuard} veto first
   *       and steps the tier down the ladder until it fits.</li>
   * </ol>
   *
   * <p>On admission the granted session is registered with the governor and its id
   * stored in {@link #enhanceSessionId} so {@link #stopTranscode()} releases the
   * held capacity. Any failure degrades to "no enhancement" — never a broken tune.
   */
  // ---- External-process (upscale worker) enhancement launch ---------------

  /**
   * Launch the three-process enhancement pipeline
   * ({@code ffmpeg decode → worker → ffmpeg encode}) staged by
   * {@link #maybeApplyGpuEnhancement}, wiring the stages with OS pipes and daemon
   * pump threads. Waits for the worker's {@code READY} handshake on stderr within
   * {@code playback/gpu_enhance/scale/external_worker_startup_timeout_seconds}.
   * Returns the encode process (whose stdout is the enhanced stream) on success,
   * or {@code null} on any spawn/handshake failure so the caller runs the
   * single-process fallback. No vendor code lives here: the worker argv comes
   * entirely from the provider's plan.
   */
  private Process launchExternalEnhancePipeline(
      sage.enhance.GpuEnhancePipeline.ExternalPipeline ep, java.util.List<String> workerArgv,
      Process warmWorker)
      throws java.io.IOException
  {
    if (ep == null || workerArgv == null || workerArgv.isEmpty()) return null;

    // Audio side-channel: create the named pipe the decode stage writes and the
    // encode stage reads BEFORE spawning either, so both processes can rendezvous
    // on it during ffmpeg input/output open (encode blocks opening the FIFO for
    // read until decode opens it for write). Unlinked in teardownExternalEnhance.
    final String audioSidecar = ep.getAudioSidecarPath();
    if (audioSidecar != null)
    {
      try { new java.io.File(audioSidecar).delete(); } catch (Throwable ignore) {}
      int rc = -1;
      try { rc = new ProcessBuilder("mkfifo", audioSidecar).inheritIO().start().waitFor(); }
      catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
      if (rc != 0)
        throw new java.io.IOException("mkfifo failed (rc=" + rc + ") for audio sidecar " + audioSidecar);
      enhanceAudioSidecarPath = audioSidecar;
      if (Sage.DBG) System.out.println("GPU_ENHANCE audio sidecar FIFO created: " + audioSidecar);
    }

    // A pre-warmed worker (from ScaleWarmupCache) is already spawned and already
    // signaled READY (the provider consumed that during warmup), so the core
    // neither spawns it nor waits on a READY latch it would never see.
    final boolean warm = (warmWorker != null && warmWorker.isAlive());

    long startupMs = Math.max(1000L,
        Sage.getLong("playback/gpu_enhance/scale/external_worker_startup_timeout_seconds", 30L) * 1000L);
    final long stallMs = Math.max(1000L,
        Sage.getLong("playback/gpu_enhance/scale/external_worker_stall_timeout_seconds", 10L) * 1000L);

    ProcessBuilder dPb = new ProcessBuilder(ep.getDecodeArgv());
    Sage.applyTimeZoneToProcessBuilder(dPb);
    ProcessBuilder wPb = new ProcessBuilder(workerArgv);
    Sage.applyTimeZoneToProcessBuilder(wPb);
    ProcessBuilder ePb = new ProcessBuilder(ep.getEncodeArgv());
    Sage.applyTimeZoneToProcessBuilder(ePb);

    Process decode = null, worker = null, encode = null, audio = null;
    try
    {
      if (Sage.DBG) System.out.println("GPU_ENHANCE external decode argv: " + ep.getDecodeArgv());
      decode = dPb.start();
      if (warm)
      {
        worker = warmWorker;
        if (Sage.DBG) System.out.println("GPU_ENHANCE using pre-warmed worker "
            + "(skipping spawn + READY wait); argv(diag): " + workerArgv);
      }
      else
      {
        if (Sage.DBG) System.out.println("GPU_ENHANCE external worker argv: " + workerArgv);
        worker = wPb.start();
      }
      if (Sage.DBG) System.out.println("GPU_ENHANCE external encode argv: " + ep.getEncodeArgv());
      encode = ePb.start();

      // Audio side-channel process: a standalone ffmpeg that accurately seeks the
      // source and copies its audio to the FIFO the encode reads (input 1). It is
      // decoupled from the raw video pipe, so it fills the FIFO freely without the
      // interleave deadlock that a second decode output caused. Its stderr is
      // drained to the log so the pipe never back-pressures it.
      final java.util.List<String> audioArgv = ep.getAudioArgv();
      if (audioArgv != null && !audioArgv.isEmpty())
      {
        if (Sage.DBG) System.out.println("GPU_ENHANCE external audio argv: " + audioArgv);
        ProcessBuilder aPb = new ProcessBuilder(audioArgv);
        Sage.applyTimeZoneToProcessBuilder(aPb);
        audio = aPb.start();
        registerLiveChild(audio);
        ProcessPriority.reduce(audio);
        startStderrDrain("enhance-audio-stderr", audio.getErrorStream());
      }

      registerLiveChild(decode);
      registerLiveChild(worker);
      registerLiveChild(encode);
      ProcessPriority.reduce(decode);
      ProcessPriority.reduce(worker);
      ProcessPriority.reduce(encode);

      // Worker stderr: scan for READY, then keep draining to the log.
      if (warm)
      {
        // Already READY; just drain worker stderr to the log and confirm the
        // three processes are alive before wiring the frame pumps.
        startStderrDrain("enhance-worker-stderr", worker.getErrorStream());
        if (!worker.isAlive() || !decode.isAlive() || !encode.isAlive())
        {
          if (Sage.DBG) System.out.println("GPU_ENHANCE pre-warmed pipeline not all alive"
              + " (workerAlive=" + worker.isAlive() + " decodeAlive=" + decode.isAlive()
              + " encodeAlive=" + encode.isAlive() + "); abandoning external pipeline");
          destroyQuietly(encode); destroyQuietly(worker); destroyQuietly(decode); destroyQuietly(audio);
          unregisterLiveChild(decode); unregisterLiveChild(worker); unregisterLiveChild(encode); unregisterLiveChild(audio);
          return null;
        }
      }
      else
      {
        final java.util.concurrent.CountDownLatch ready =
            new java.util.concurrent.CountDownLatch(1);
        final Process wf = worker;
        Thread werr = new Thread("enhance-worker-stderr")
        {
          public void run()
          {
            java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(wf.getErrorStream()));
            try
            {
              String line;
              while ((line = r.readLine()) != null)
              {
                if (line.contains("READY")) ready.countDown();
                if (XCODE_DEBUG) System.out.println("enhance worker: " + line);
              }
            }
            catch (java.io.IOException ignore) {}
          }
        };
        werr.setDaemon(true);
        werr.start();

        // Wait up to startupMs for READY, but bail out immediately if any of the
        // three pipeline processes dies first. A crashing worker (e.g. a provider
        // runtime error) otherwise wedges the client for the FULL timeout before we
        // fall back to the plain command -- the multi-second black screen the user
        // sees. Polling in short slices lets us abandon within ~200ms of a crash.
        boolean got = false;
        long readyDeadline = System.currentTimeMillis() + startupMs;
        while (true)
        {
          if (ready.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)) { got = true; break; }
          if (!worker.isAlive() || !decode.isAlive() || !encode.isAlive()) break;
          if (System.currentTimeMillis() >= readyDeadline) break;
        }
        if (!got || !worker.isAlive() || !decode.isAlive() || !encode.isAlive())
        {
          if (Sage.DBG) System.out.println("GPU_ENHANCE external worker not READY within "
              + startupMs + "ms (got=" + got + " workerAlive=" + worker.isAlive()
              + " decodeAlive=" + decode.isAlive() + " encodeAlive=" + encode.isAlive()
              + "); abandoning external pipeline");
          destroyQuietly(encode); destroyQuietly(worker); destroyQuietly(decode); destroyQuietly(audio);
          unregisterLiveChild(decode); unregisterLiveChild(worker); unregisterLiveChild(encode); unregisterLiveChild(audio);
          return null;
        }
      }

      // Frame pumps: decode.stdout -> worker.stdin ; worker.stdout -> encode.stdin.
      final long[] lastProgress = { System.currentTimeMillis() };
      startFramePump("enhance-decode->worker", decode.getInputStream(),
          worker.getOutputStream(), lastProgress);
      startFramePump("enhance-worker->encode", worker.getInputStream(),
          encode.getOutputStream(), null);
      startStderrDrain("enhance-decode-stderr", decode.getErrorStream());

      // Stall watchdog: if frames stop flowing decode->worker for stallMs while
      // the decode is still producing, OR the worker dies outright, tear the
      // upscale stages down so the encode's inputs both EOF and the session ends
      // (a mid-session external worker cannot be hot-swapped; the client re-plans
      // and gets a plain remux on re-open). Crucially this also destroys the audio
      // sidecar: it -follows the growing file and never EOFs on its own, so once
      // the worker is gone the encode's video pipe EOFs but its audio input would
      // not -- killing the sidecar here EOFs that input too, letting the encode
      // exit and unblocking teardown. Without it, a client that already left would
      // leave the sidecar (and encode) running forever: the "exit but the server
      // keeps transcoding" leak.
      final Process wd = worker, dd = decode, ed = encode;
      Thread watch = new Thread("enhance-stall-watchdog")
      {
        public void run()
        {
          while (ed.isAlive())
          {
            try { Thread.sleep(1000L); } catch (InterruptedException ie) { return; }
            if (!dd.isAlive()) return; // decode finished: clean EOF path, not a stall
            boolean workerGone = !wd.isAlive();
            boolean stalled = System.currentTimeMillis() - lastProgress[0] > stallMs;
            if (workerGone || stalled)
            {
              if (Sage.DBG) System.out.println("GPU_ENHANCE external worker "
                  + (workerGone ? "died" : "stalled > " + stallMs + "ms")
                  + "; tearing down worker + audio sidecar so the encode can EOF"
                  + " (plain remux on re-open)");
              if (stalled) markEnhanceStallCooldown(FFMPEGTranscoder.this.currFile);
              destroyQuietly(wd);
              Process a = FFMPEGTranscoder.this.enhanceAudioProcess;
              if (a != null) destroyQuietly(a);
              return;
            }
          }
        }
      };
      watch.setDaemon(true);
      watch.start();

      this.enhanceDecodeProcess = decode;
      this.enhanceWorkerProcess = worker;
      this.enhanceAudioProcess = audio;
      this.externalSourceCtrl = ep.hasSourceControl();
      if (Sage.DBG) System.out.println("GPU_ENHANCE external pipeline live (decode+worker+encode)");
      return encode;
    }
    catch (java.io.IOException ioe)
    {
      destroyQuietly(encode); destroyQuietly(worker); destroyQuietly(decode); destroyQuietly(audio);
      unregisterLiveChild(decode); unregisterLiveChild(worker); unregisterLiveChild(encode); unregisterLiveChild(audio);
      throw ioe;
    }
    catch (Throwable t)
    {
      destroyQuietly(encode); destroyQuietly(worker); destroyQuietly(decode); destroyQuietly(audio);
      unregisterLiveChild(decode); unregisterLiveChild(worker); unregisterLiveChild(encode); unregisterLiveChild(audio);
      if (Sage.DBG) System.out.println("GPU_ENHANCE external pipeline setup failed: " + t);
      return null;
    }
  }

  /** Copy bytes from {@code in} to {@code out} on a daemon thread, closing both on
   *  EOF/error so the downstream stage sees the pipe close (flush + exit). When
   *  {@code progress} is non-null, its element 0 is stamped with the time of each
   *  successful write for the stall watchdog. */
  private void startFramePump(final String name, final java.io.InputStream in,
      final java.io.OutputStream out, final long[] progress)
  {
    Thread t = new Thread(name)
    {
      public void run()
      {
        byte[] buf = new byte[64 * 1024];
        try
        {
          int n;
          while ((n = in.read(buf)) >= 0)
          {
            if (n > 0)
            {
              out.write(buf, 0, n);
              out.flush();
              if (progress != null) progress[0] = System.currentTimeMillis();
            }
          }
        }
        catch (java.io.IOException ignore) {}
        finally
        {
          try { out.close(); } catch (java.io.IOException ignore) {}
          try { in.close(); } catch (java.io.IOException ignore) {}
        }
      }
    };
    t.setDaemon(true);
    t.start();
  }

  /** Drain a child stderr to the log on a daemon thread so it can never block. */
  private void startStderrDrain(final String name, final java.io.InputStream in)
  {
    Thread t = new Thread(name)
    {
      public void run()
      {
        java.io.BufferedReader r = new java.io.BufferedReader(
            new java.io.InputStreamReader(in));
        try
        {
          String line;
          while ((line = r.readLine()) != null)
            if (XCODE_DEBUG) System.out.println(name + ": " + line);
        }
        catch (java.io.IOException ignore) {}
      }
    };
    t.setDaemon(true);
    t.start();
  }

  /** Tear down the external-process enhancement sub-stages (decode + worker), if
   *  any. The encode process is the tracked xcodeProcess and is handled by the
   *  normal stopTranscode() path. Idempotent. */
  private void teardownExternalEnhance()
  {
    externalEnhanceActive = false;
    externalSourceCtrl = false;
    Process w = enhanceWorkerProcess, d = enhanceDecodeProcess, a = enhanceAudioProcess;
    enhanceWorkerProcess = null;
    enhanceDecodeProcess = null;
    enhanceAudioProcess = null;
    if (w != null) { destroyQuietly(w); unregisterLiveChild(w); }
    if (d != null) { destroyQuietly(d); unregisterLiveChild(d); }
    if (a != null) { destroyQuietly(a); unregisterLiveChild(a); }
    String fifo = enhanceAudioSidecarPath;
    enhanceAudioSidecarPath = null;
    if (fifo != null) { try { new java.io.File(fifo).delete(); } catch (Throwable ignore) {} }
  }

  /** Force-kill a process and any descendants, swallowing all errors. */
  private static void destroyQuietly(Process p)
  {
    if (p == null) return;
    try { p.descendants().forEach(ProcessHandle::destroyForcibly); } catch (Throwable ignore) {}
    try { p.destroyForcibly(); } catch (Throwable ignore) {}
  }

  /**
   * Per-source enhancement stall cooldown. When the external upscale pipeline
   * stalls mid-session (worker can't sustain realtime -- e.g. bob-deinterlaced
   * 1080i-&gt;4K runs at only ~1.08x on this GPU, so any dip falls behind the live
   * edge), killing the worker tears the session down and the client re-OPENs.
   * Without this, that re-open re-attempts the same enhancement, stalls again,
   * and loops -- a black-screen flicker that also churns live tuner watches
   * ("all tuners busy" with no real clients). Keyed by source file path (stable
   * across a client re-open of the same airing), this makes the FIRST re-open
   * after a stall fall back to a plain, stable remux for a cooldown window, then
   * retry enhancement later in case conditions improved. Static so it survives
   * the per-open transcoder instance churn.
   */
  private static final java.util.concurrent.ConcurrentHashMap<String,Long>
      ENHANCE_STALL_COOLDOWN = new java.util.concurrent.ConcurrentHashMap<String,Long>();

  /** Arm the stall cooldown for a source so its next open serves plain remux. */
  static void markEnhanceStallCooldown(java.io.File src)
  {
    if (src == null) return;
    long secs = Sage.getLong("playback/gpu_enhance/stall_cooldown_seconds", 120L);
    if (secs <= 0L) return;
    ENHANCE_STALL_COOLDOWN.put(src.toString(), System.currentTimeMillis() + secs * 1000L);
    if (Sage.DBG) System.out.println("GPU_ENHANCE stall cooldown armed for " + secs
        + "s: next open of " + src + " serves plain remux (unenhanced)");
  }

  /** True while the given source is inside its post-stall cooldown window. */
  static boolean enhanceStallCoolingDown(java.io.File src)
  {
    if (src == null) return false;
    Long until = ENHANCE_STALL_COOLDOWN.get(src.toString());
    if (until == null) return false;
    if (System.currentTimeMillis() >= until.longValue())
    {
      ENHANCE_STALL_COOLDOWN.remove(src.toString());
      return false;
    }
    return true;
  }

  void maybeApplyGpuEnhancement(java.util.ArrayList xcodeParamsVec)
  {
    try
    {
      if (enhanceRequest == null || !enhanceRequest.isActive()) return;
      if (!sage.enhance.EnhancementDryRun.isLive()) return;
      // Enhancement applies to the confirmed modern copy-family playback modes
      // (remux/copy) and to the browserhd re-encode path (H.264 for browser MSE,
      // chosen when the source can't be stream-copied to fMP4, e.g. MPEG-2).
      boolean copyFamily = isModernCopyFamilyXcodeMode() || isEnhanceableCopyContainerMode();
      boolean reencodeEnhanceable = isEnhanceableReencodeMode();

      // Recording-integrity gate. The enhancement pipeline only ever READS the
      // source: the decode stage opens it read-only and writes raw frames to a
      // pipe (see GpuEnhancePipeline.buildExternalPipeline), and a growing live
      // recording is tailed with the SAME inherited "-follow 1" the main
      // transcoder uses -- a second read-only reader never touches the recording
      // mux. Historically this refused every active file outright, which was
      // stricter than the integrity concern requires and silently disabled the
      // live-TV / in-progress-recording 4K upscale this whole feature exists for
      // (the advisor still advertised verdict=OFFERED tier=2160p to the client,
      // then this gate returned with no log line -- the two layers disagreed and
      // the viewer got a plain copy). Active-file enhancement is now allowed by
      // default; the kill-switch restores the old fail-closed behavior. Recording
      // capacity is still protected downstream by GpuGovernor admission, which
      // runs the RecordingGuard veto (posture PROTECT/BALANCED/COEXIST) and the
      // measured VRAM/engine/disk ladder -- so a box that must not spend GPU
      // beside a recording denies there, honestly, rather than here, silently.
      if (activeFile && !Sage.getBoolean("playback/gpu_enhance/allow_active_file", true))
      {
        if (Sage.DBG) System.out.println("GPU_ENHANCE apply: skipped -- active-file"
            + " (live/in-progress recording) enhancement disabled via"
            + " playback/gpu_enhance/allow_active_file=false");
        return;
      }
      if (!copyFamily && !reencodeEnhanceable)
      {
        if (Sage.DBG) System.out.println("GPU_ENHANCE apply: skipped -- xcodeMode '"
            + xcodeModeName + "' is neither copy-family nor re-encode-enhanceable");
        return;
      }

      // Post-stall cooldown: this source stalled the upscale pipeline recently
      // (see the stall watchdog in launchExternalEnhancePipeline). Serve a plain,
      // stable remux now instead of re-attempting an enhancement that cannot
      // sustain realtime on this GPU -- which would stall again and loop, churning
      // live tuner watches. The window expires on its own, so a later open retries.
      if (currFile != null && enhanceStallCoolingDown(currFile))
      {
        if (Sage.DBG) System.out.println("GPU_ENHANCE apply: skipped -- source in"
            + " post-stall cooldown (playback/gpu_enhance/stall_cooldown_seconds);"
            + " serving plain remux for " + currFile);
        return;
      }

      // Optional operator override: force a specific tier regardless of the tier
      // the client negotiated from its display sink (a 1080p browser sink caps the
      // request at enhance_1080p). Lets an operator demonstrate the full 2160p
      // upscale on demand. Unset => byte-identical to the negotiated request.
      sage.enhance.EnhancementTier reqTier = enhanceRequest;
      boolean forcedTier = false;
      String forceTier = Sage.get("playback/gpu_enhance/force_tier", "");
      if (forceTier != null && forceTier.trim().length() > 0)
      {
        sage.enhance.EnhancementTier forced =
            sage.enhance.EnhancementTier.fromToken(forceTier.trim());
        if (forced != null && forced.isActive())
        {
          reqTier = forced;
          forcedTier = true;
          if (Sage.DBG) System.out.println("GPU_ENHANCE apply: force_tier override -> "
              + forced.token());
        }
      }

      int srcW = 0, srcH = 0, srcFps = 0;
      double srcFpsExact = 0.0;
      boolean interlaced = false;
      long srcKbps = 0L;
      if (sourceFormat != null)
      {
        sage.media.format.VideoFormat vf = sourceFormat.getVideoFormat();
        if (vf != null)
        {
          srcW = vf.getWidth();
          srcH = vf.getHeight();
          srcFps = Math.round(vf.getFps());
          srcFpsExact = vf.getFps();
          interlaced = vf.isInterlaced();
        }
        long br = sourceFormat.getBitrate();
        if (br > 0) srcKbps = br / 1000L;
      }

      // Live/active-file geometry recovery. For an in-progress source (live TV,
      // growing recording) sourceFormat is frequently not yet populated, so the
      // block above leaves srcW/srcH at 0. A zero srcH sinks the governor's source
      // floor: isLegalForSourceHeight() fails for every upscaling tier, so
      // requestAdmission silently steps 2160p -> 1080p -> deinterlace_only, and
      // buildPlan then returns null for the progressive source -- the client,
      // already promised tier=2160p by the advisor, is handed a plain copy. The
      // advisor recovered the true geometry at offer time with the same bounded
      // probe (MiniPlayer, "live-source probe recovered geometry"); the apply path
      // must do the same or the two layers disagree exactly as before. Bounded
      // daemon probe, hard timeout, property-gated -- a slow/growing file can
      // never stall the transcode; on timeout/error srcH stays 0 and we admit
      // honestly against what we know.
      if ((srcW <= 0 || srcH <= 0) && currFile != null
          && Sage.getBoolean("playback/gpu_enhance/live_source_probe", true))
      {
        sage.media.format.VideoFormat pvf = probeSourceVideoFormatBounded(currFile,
            Sage.getInt("playback/gpu_enhance/live_source_probe_ms", 750));
        if (pvf != null && pvf.getWidth() > 0 && pvf.getHeight() > 0)
        {
          srcW = pvf.getWidth();
          srcH = pvf.getHeight();
          interlaced = pvf.isInterlaced();
          int pfps = Math.round(pvf.getFps());
          if (pfps > 0) { srcFps = pfps; srcFpsExact = pvf.getFps(); }
          if (Sage.DBG) System.out.println("GPU_ENHANCE apply: live-source probe recovered geometry "
              + srcW + "x" + srcH + (interlaced ? "i" : "p") + "@" + srcFps + " from " + currFile);
        }
        else if (Sage.DBG)
        {
          System.out.println("GPU_ENHANCE apply: live-source probe did not recover geometry"
              + " (source still 0x0) for " + currFile + "; admission uses srcH=" + srcH);
        }
      }

      // Live sink re-negotiation (Protocol 2.1 ";sink=WxH" on the msproxy
      // re-open): a PWA dragged to a different-resolution monitor re-opens this
      // stream with its new physical sink. Re-clamp the ALREADY-granted tier to
      // that sink using the advisor's own resolution logic, so a larger panel
      // upscales further and a smaller one steps down -- without re-running the
      // full advise (this only adjusts an enhancement already offered). A pinned
      // force_tier wins over the sink, matching its "demonstrate on demand" role.
      if (!forcedTier && liveSinkWidth > 0 && liveSinkHeight > 0 && srcH > 0)
      {
        sage.enhance.EnhancementTier sinkTier =
            sage.enhance.EnhancementAdvisor.tierForLiveSink(
                liveSinkWidth, liveSinkHeight, srcH, interlaced);
        if (sinkTier != null && sinkTier != reqTier)
        {
          if (Sage.DBG) System.out.println("GPU_ENHANCE apply: live sink "
              + liveSinkWidth + "x" + liveSinkHeight + " re-clamps tier "
              + reqTier.token() + " -> " + sinkTier.token());
          reqTier = sinkTier;
        }
        if (!reqTier.isActive())
        {
          // The move (e.g. to a small window) leaves no worthwhile enhancement:
          // fall back to the byte-identical negotiated command, unenhanced.
          if (Sage.DBG) System.out.println("GPU_ENHANCE apply: live sink "
              + liveSinkWidth + "x" + liveSinkHeight
              + " yields no enhancement, leaving stream untouched");
          return;
        }
      }

      // Per-transcode session id: stable for this instance, unique across
      // concurrent transcoders, so the governor's concurrency count is honest.
      String sessionId = "xcode-" + System.identityHashCode(this);
      // Genre-aware bitrate: read the recording's EPG categories (Wiz.bin) to pick
      // a motion class, so sports/nature claim bits while news/talk save them. Kept
      // bandwidth-safe: never exceed the frame-rate figure the advisor's envelope
      // check already approved, so a genre bump can't overrun the measured link.
      sage.enhance.EnhancementProfile enhanceProfile =
          sage.enhance.MotionHint.profileForFile(currFile);
      sage.enhance.EnhancementProfile.MotionClass motion =
          sage.enhance.MotionHint.motionFor(enhanceProfile, srcFps);
      long estKbps = sage.enhance.GpuEnhancePipeline.suggestBitrateKbps(
          reqTier, motion, srcKbps);
      long fpsEst = sage.enhance.GpuEnhancePipeline.suggestBitrateKbps(
          reqTier, srcFps, srcKbps);
      if (fpsEst > 0 && estKbps > fpsEst) estKbps = fpsEst;

      sage.enhance.GpuGovernor gov = sage.enhance.GpuGovernor.getInstance();
      sage.enhance.GpuGovernor.Admission adm =
          gov.requestAdmission(sessionId, reqTier, srcH, estKbps);
      if (adm == null || !adm.isGranted())
      {
        if (Sage.DBG) System.out.println("GPU_ENHANCE apply: not admitted ("
            + (adm == null ? "null" : adm.getReason()) + ")");
        return;
      }

      sage.enhance.EnhancementTier granted = adm.getTier();
      sage.enhance.EnhancementPlan plan = sage.enhance.GpuEnhancePipeline.buildPlan(
          granted, interlaced, srcW, srcH, estKbps);
      if (plan == null || !plan.isActive())
      {
        gov.release(sessionId);
        // buildPlan guarantees an inactive plan holds no specialized permit, but
        // release defensively in case a provider planned then failed validation.
        if (plan != null) plan.releaseScaleLease();
        if (Sage.DBG) System.out.println("GPU_ENHANCE apply: no buildable plan for "
            + granted.token() + " (" + (plan == null ? "null" : plan.getReason()) + ")");
        return;
      }

      // External-process (upscale worker) plan: build the decode/encode stages
      // that bracket the worker from the copy-family base command BEFORE the
      // in-place rewrite below turns xcodeParamsVec into the single-process
      // fallback. The three-process pipeline is attempted first at launch; if the
      // worker never signals READY (or fails to spawn), the launcher discards it
      // and runs the fallback command (which deinterlaces/re-encodes but does not
      // upscale), so the client always gets a stream.
      pendingExternalPipeline = null;
      pendingExternalWorkerArgv = null;
      pendingExternalWarmProcess = null;
      // A pre-warmed worker the plan may carry. If we end up NOT staging the
      // external pipeline (wrong argv shape, or an ep build failure), this warm
      // worker was consumed from the cache and would otherwise leak, so we tear
      // it down explicitly below.
      Process planWarmProcess =
          (plan.getScaleExec() != null) ? plan.getScaleExec().getWarmProcess() : null;
      if (plan.getScaleExec() != null && plan.getScaleExec().rendersExternalProcess()
          && (copyFamily || reencodeEnhanceable))
      {
        java.util.List<String> baseArgv =
            new java.util.ArrayList<String>(xcodeParamsVec);
        // Source codec for explicit *_cuvid NVDEC decode in the decode stage, but
        // only where the server actually has an NVIDIA GPU: the vast majority of
        // SageTV servers are CPU-only, so gate on the cached GPU-enhance capability
        // probe (NVDEC ships with the same build as the NVENC/CUDA this checks).
        // A null codec keeps the generic accel hint. Kill-switch: property
        // playback/gpu_enhance/decode_cuvid=false.
        String srcVideoFmt = null;
        if (sourceFormat != null
            && Sage.getBoolean("playback/gpu_enhance/decode_cuvid", true)
            && sage.HwEncoder.gpuEnhanceSupported())
          srcVideoFmt = sourceFormat.getPrimaryVideoFormat();
        // Raw-frame-rate cap: RGB24 at 60fps is ~530 MB/s through the OS pipe and
        // the pipeline sustains ~43fps, so faster sources can optionally be
        // decimated to this cap before the pipe. Default 0 = disabled (no
        // decimation): the pipeline runs at true source fps and the encode
        // -framerate still tracks the source. Set >0 to re-enable the cap.
        int fpsCap = Sage.getInt("playback/gpu_enhance/pipe_fps_cap", 0);
        // Audio side-channel: copy the source audio out of the SAME accurately-
        // seeked decode process (a FIFO the decode writes and the encode reads)
        // instead of the encode re-opening the file on a second, independent seek.
        // On a resumed play the two independent seeks (cuvid fast-seek for video,
        // demux seek for audio) land at different times and the rawvideo worker
        // pipe strips the video PTS, so audio and video drift by a seek-dependent
        // 0.5-1.2s. One decode + accurate seek keeps them aligned by construction.
        // No-op at ss=0 (accurateSeekSplit collapses), so no startup penalty at
        // the head. Kill-switch: playback/gpu_enhance/scale/audio_sidecar=false.
        String audioSidecar = null;
        double seekPrerollSec = 0.0;
        if (Sage.getBoolean("playback/gpu_enhance/scale/audio_sidecar", true))
        {
          seekPrerollSec = Sage.getInt("playback/gpu_enhance/scale/seek_preroll_ms", 2000) / 1000.0;
          audioSidecar = new java.io.File(System.getProperty("java.io.tmpdir", "/tmp"),
              "sage-enh-audio-" + sessionId + "-" + System.nanoTime() + ".ts").getPath();
        }
        // Audio sidecar handling. The mpegts muxer in the pinned fork cannot
        // stream-copy AC-4, so a blind "-c:a copy" in the sidecar exits before it
        // opens the FIFO and the encode deadlocks reading it. For an AC-4 source we
        // reproduce the EXACT audio handling of the non-enhanced path: transcode to
        // the client codec (ac4SourceAudioCodec, default ac3), add -copytb 0 (the
        // demuxer has no AC-4 TS parser) and the aresample=async drift-correction
        // filter (the fork's AC-4 decoder drops frames -- "overread" -- which would
        // otherwise slip audio ahead of video). null => plain -c:a copy.
        sage.enhance.GpuEnhancePipeline.SidecarAudio sidecarAudio = null;
        if (sourceFormat != null
            && sage.media.format.MediaFormat.AC4.equals(sourceFormat.getPrimaryAudioFormat()))
        {
          String acodec = (ac4SourceAudioCodec != null && ac4SourceAudioCodec.length() > 0)
              ? ac4SourceAudioCodec : "ac3";
          String abps;
          if ("eac3".equalsIgnoreCase(acodec))     abps = Sage.get("miniplayer/eac3_bitrate", "640k");
          else if ("ac3".equalsIgnoreCase(acodec)) abps = "384k";
          else if ("aac".equalsIgnoreCase(acodec)) abps = "256k";
          else                                     abps = null;
          java.util.List<String> inOpts = new java.util.ArrayList<String>();
          if (sage.media.format.MediaFormat.MPEG2_TS.equals(sourceFormat.getFormatName()))
          { inOpts.add("-copytb"); inOpts.add("0"); }
          java.util.List<String> outOpts = new java.util.ArrayList<String>();
          outOpts.add("-c:a"); outOpts.add(acodec);
          if (abps != null) { outOpts.add("-b:a"); outOpts.add(abps); }
          // Audio filter for the enhanced-matroska sidecar. The 5.1-vs-stereo
          // choice is now PER CLIENT, driven by the winning surface's declared
          // AUDIO_MAX_CHANNELS (setSidecarMaxAudioChannels), NOT a SageTV-global
          // setting:
          //   * explicit capability wins -- AUDIO_MAX_CHANNELS >= 6 preserves
          //     surround; an explicit value < 6 forces the stereo downmix;
          //   * legacy/undeclared (0) mirrors the non-enhance ladder: a
          //     passthrough EAC3/AC3 codec pick keeps 5.1, AAC/MP2 downmixes.
          // Stereo => buildAudioResampleFilter(true, true) =
          // "aformat=channel_layouts=stereo,aresample=async=N"; surround =>
          // buildAudioResampleFilter(true, false) = "aresample=async=N" (source
          // layout preserved). The aresample re-clock is ALWAYS present -- the
          // fork's AC-4 decoder drops frames ("overread"), and dropping the
          // re-clock (not the downmix) was the render-breaker at frame 162.
          boolean downmixToStereo;
          if (sidecarMaxAudioChannels > 0)
            downmixToStereo = sidecarMaxAudioChannels < 6;
          else
            downmixToStereo = !("eac3".equalsIgnoreCase(acodec) || "ac3".equalsIgnoreCase(acodec));
          String sidecarAf;
          if (downmixToStereo)
            sidecarAf = buildAudioResampleFilter(true, true);
          else
          {
            // Preserve surround -- but the AC-3-family encoder tops out at 5.1,
            // so a 7.1 AC-4 source must be downmixed to 5.1 or the eac3/ac3
            // encoder aborts ("channel layout not supported") and the client
            // gets no audio ("no signal"). Only cap when the source is actually
            // wider than 5.1; never upmix a narrower source.
            int srcCh = (sourceFormat.getAudioFormat() != null)
                ? sourceFormat.getAudioFormat().getChannels() : 0;
            int cap = maxChannelsForAudioCodec(acodec);
            if (cap > 0 && srcCh > cap)
              sidecarAf = "aformat=channel_layouts=5.1," + buildAudioResampleFilter(true, false);
            else
              sidecarAf = buildAudioResampleFilter(true, false);
          }
          if (Sage.DBG) System.out.println("FFMPEGTranscoder: enhance AC-4 sidecar audio -> codec="
              + acodec + " maxChannels="
              + (sidecarMaxAudioChannels > 0 ? Integer.toString(sidecarMaxAudioChannels) : "undeclared")
              + " downmixToStereo=" + downmixToStereo + " af=" + sidecarAf);
          outOpts.add("-af"); outOpts.add(sidecarAf);
          sidecarAudio = new sage.enhance.GpuEnhancePipeline.SidecarAudio(inOpts, outOpts);
        }
        sage.enhance.GpuEnhancePipeline.ExternalPipeline ep = copyFamily
            ? sage.enhance.GpuEnhancePipeline.buildExternalPipeline(baseArgv, plan, srcFps, srcVideoFmt, fpsCap, srcFpsExact, audioSidecar, seekPrerollSec, sidecarAudio)
            : sage.enhance.GpuEnhancePipeline.buildExternalReencodePipeline(baseArgv, plan, srcFps, srcVideoFmt, fpsCap, srcFpsExact, audioSidecar, seekPrerollSec, sidecarAudio);
        if (ep != null)
        {
          pendingExternalPipeline = ep;
          pendingExternalWorkerArgv = plan.getScaleExec().getExternalArgv();
          pendingExternalWarmProcess = plan.getScaleExec().getWarmProcess();
          if (Sage.DBG) System.out.println("GPU_ENHANCE external-process pipeline staged: "
              + plan.getScaleExec().getImplementationLabel()
              + " path=" + (copyFamily ? "copy-family" : "re-encode")
              + " worker=" + pendingExternalWorkerArgv
              + (pendingExternalWarmProcess != null ? " (pre-warmed)" : ""));
        }
      }
      // Consumed a warm worker but did not stage it into a pipeline: don't leak it.
      if (pendingExternalWarmProcess == null && planWarmProcess != null)
      {
        if (Sage.DBG) System.out.println("GPU_ENHANCE pre-warmed worker not staged "
            + "(no external pipeline); destroying it");
        destroyQuietly(planWarmProcess);
      }

      boolean rewritten = copyFamily
          ? sage.enhance.GpuEnhancePipeline.rewriteArgv(xcodeParamsVec, plan, srcFps)
          : sage.enhance.GpuEnhancePipeline.rewriteReencodeArgv(xcodeParamsVec, plan, srcFps);
      if (!rewritten)
      {
        // For an EXTERNAL_PROCESS plan the upscale is delivered by the staged
        // three-process worker pipeline (decode -> worker -> encode), NOT by the
        // single-process argv. A failed single-process rewrite is therefore NOT
        // fatal when a worker pipeline was successfully staged above: the
        // un-rewritten base command is a valid un-enhanced fallback, used only if
        // the worker never comes up (launchExternalEnhancePipeline returns null).
        // Keep the governor session, the scale lease and the staged pipeline;
        // tearing them down here is what silently reduced every external-process
        // enhancement to a plain stream.
        if (pendingExternalPipeline == null)
        {
          gov.release(sessionId);
          pendingExternalWorkerArgv = null;
          // Active plan we are discarding unused: return its specialized permit.
          plan.releaseScaleLease();
          if (Sage.DBG) System.out.println("GPU_ENHANCE apply: argv not "
              + (copyFamily ? "copy-family" : "re-encode") + " shape, left untouched");
          return;
        }
        if (Sage.DBG) System.out.println("GPU_ENHANCE apply: single-process rewrite not "
            + (copyFamily ? "copy-family" : "re-encode") + " shape; delivering via external"
            + " worker pipeline (base command retained as un-enhanced fallback)");
      }
      enhanceSessionId = sessionId;
      // Capture the specialized permit (null for the built-in scaler) so it is
      // released with the governor session no matter how this session unwinds.
      enhanceScaleLease = plan.getScaleLease();
      // Size the output ring for the REAL encode bitrate. The copy-family base mode
      // this enhancement was layered onto left currVideoBitrateKbps at the tiny copy
      // default (~200 kbps), which makes startTranscode() allocate a 128 KB ring
      // (32 x 4096). That is orders of magnitude too small for a multi-megabit
      // enhanced HEVC stream drained over the MediaServer HTTP pull path, so the
      // encoder wedges on a full ring after ~128 KB. Record the true target here so
      // the enhanceSessionId branch in startTranscode() sizes the ring generously.
      if (estKbps > 0) currVideoBitrateKbps = (int) Math.min(Integer.MAX_VALUE, estKbps);
      // Live-adjust ceiling for this enhanced session: its own -maxrate envelope
      // (GpuEnhancePipeline sets -maxrate = 1.5x the tier/genre estimate), so the
      // PWA bridge's XCODE_ADJUST can trim within, and drive back up to, the full
      // upscale bitrate instead of being clamped to the generic 8 Mbps default.
      if (estKbps > 0)
        policyCeilingKbps = (int) Math.min(Integer.MAX_VALUE, estKbps * 3L / 2L);
      if (Sage.DBG) System.out.println("GPU_ENHANCE LIVE applied " + plan
          + " session=" + sessionId + " mode=" + xcodeModeName
          + " profile=" + (enhanceProfile == null ? "unknown" : enhanceProfile.name())
          + " motion=" + motion
          + " ringBitrateKbps=" + currVideoBitrateKbps);
    }
    catch (Throwable t)
    {
      // Enhancement is an optimization and must never break a tune.
      if (enhanceSessionId != null)
      {
        try { sage.enhance.GpuGovernor.getInstance().release(enhanceSessionId); }
        catch (Throwable ignore) {}
        enhanceSessionId = null;
      }
      releaseEnhanceScaleLease();
      if (Sage.DBG) System.out.println("GPU_ENHANCE apply failed (ignored): " + t);
    }
  }

  /**
   * Impose the shared {@link sage.media.BitratePolicy} launch-time rate cap on
   * the plain (un-enhanced) browserhd re-encode. browserhd re-encodes to H.264
   * for browser/PWA MSE at the <i>source</i> resolution (it does not scale), so a
   * high-detail 50/60&nbsp;fps source can otherwise let NVENC spike far above the
   * link budget (~78&nbsp;Mbps was observed on 720p content), overrunning the
   * MSE/PWA buffer and producing the tearing/stall the client reported. This
   * replaces the former static {@code media_server/browserhd_*} flat cap with a
   * per-session, resolution- and (where metered) bandwidth-aware ceiling drawn
   * from the same curve every other re-encode path now uses.
   *
   * <p>Skipped when a GPU enhancement plan already owns the bitrate
   * ({@link #enhanceSessionId} set) or for any non-browserhd mode. The live
   * {@code videorateadapt} channel (driven by {@code XCODE_ADJUST} from the PWA
   * bridge) still trims within this envelope on the patched binary, so WAN
   * adaptation is unaffected. Any failure leaves the encoder default untouched.
   */
  void applyBrowserHdRateCap(java.util.ArrayList xcodeParamsVec)
  {
    try
    {
      if (!isEnhanceableReencodeMode()) return;   // browserhd only
      if (enhanceSessionId != null) return;        // enhancement owns the bitrate
      if (!Sage.getBoolean("media_server/browserhd_policy_cap", true)) return;
      if (sourceFormat == null) return;
      sage.media.format.VideoFormat vf = sourceFormat.getVideoFormat();
      if (vf == null) return;
      int outW = vf.getWidth();
      int outH = vf.getHeight();
      double fps = vf.getFps();
      if (outH <= 0) return; // unknown geometry -> leave encoder default

      // browserhd always re-encodes H.264 (h264_nvenc, or libx264 fallback) for
      // MSE. Read the actual codec token so codecFactor and the nvenc -rc are
      // correct even if a deployment forces libx264.
      String vcodec = "h264";
      boolean nvenc = false;
      for (int i = 0; i + 1 < xcodeParamsVec.size(); i++)
      {
        Object o = xcodeParamsVec.get(i);
        if ("-c:v".equals(o) || "-vcodec".equals(o))
        {
          vcodec = String.valueOf(xcodeParamsVec.get(i + 1));
          break;
        }
      }
      if (vcodec != null && vcodec.toLowerCase(java.util.Locale.ROOT).contains("nvenc"))
        nvenc = true;

      // Per-client link budget: estimatedBandwidth (bits/s) only when metered.
      // 0 and the LAN sentinel (>=49 Mbps) both mean "unmetered" -> the
      // resolution anchor alone. On the browserhd pull path the PWA bridge drives
      // WAN adaptation live via XCODE_ADJUST, so an unmetered launch cap is right;
      // the anchor's job here is only to stop the 78 Mbps LAN spike.
      int linkKbps = 0;
      long bw = estimatedBandwidth;
      if (bw > 0 && bw < 49000000L) linkKbps = (int) (bw / 1000L);

      // Genre + frame-rate motion, from the same EPG source the enhance path uses.
      sage.enhance.EnhancementProfile prof = sage.enhance.MotionHint.profileForFile(currFile);
      sage.enhance.EnhancementProfile.MotionClass mc =
          sage.enhance.MotionHint.motionFor(prof, (int) Math.round(fps));
      sage.media.BitratePolicy.Motion motion = mapPolicyMotion(mc);

      sage.media.BitratePolicy.Plan plan =
          sage.media.BitratePolicy.compute(outW, outH, fps, vcodec, motion, linkKbps, 0);

      int applied = sage.enhance.GpuEnhancePipeline.applyBitratePlan(
          xcodeParamsVec, plan, nvenc);
      if (applied > 0)
      {
        currVideoBitrateKbps = applied;
        policyCeilingKbps = plan.maxrateKbps;
        if (Sage.DBG) System.out.println("browserhd BitratePolicy cap: out=" + outW + "x"
            + outH + "@" + Math.round(fps) + " codec=" + vcodec + " motion=" + motion
            + " linkKbps=" + linkKbps + " -> " + plan);
      }
    }
    catch (Throwable t)
    {
      if (Sage.DBG) System.out.println("browserhd BitratePolicy cap skipped: " + t);
    }
  }

  /** Map the genre motion class onto the shared {@link sage.media.BitratePolicy} enum. */
  private static sage.media.BitratePolicy.Motion mapPolicyMotion(
      sage.enhance.EnhancementProfile.MotionClass m)
  {
    if (m == null) return sage.media.BitratePolicy.Motion.MEDIUM;
    switch (m)
    {
      case HIGH: return sage.media.BitratePolicy.Motion.HIGH;
      case LOW:  return sage.media.BitratePolicy.Motion.LOW;
      default:   return sage.media.BitratePolicy.Motion.MEDIUM;
    }
  }

  /** Release the specialized scale permit exactly once (idempotent, null-safe). */
  private void releaseEnhanceScaleLease()
  {
    sage.enhance.spi.ScaleGovernor.Lease lease = enhanceScaleLease;
    if (lease != null)
    {
      enhanceScaleLease = null;
      try { lease.close(); } catch (Throwable ignore) {}
    }
  }


  /**
   * Some copy-family presets unconditionally add
   * {@code -tag:v hvc1} because its primary user is HEVC/ATSC3 video-copy (Chromium
   * MSE requires the {@code hvc1} sample-entry tag — parameter sets out-of-band in
   * {@code hvcC} — to decode HEVC in fragmented MP4). But the same preset is
   * selected for ANY {@code AUDIO_TRANSCODE} decision, including an H.264 source
   * (e.g. cable/QAM H.264 + AC-3). Forcing the {@code hvc1} tag onto a copied
   * H.264 track makes ffmpeg abort before writing a single byte:
   * "Tag hvc1 incompatible with output codec id '27' (avc1) ... Could not write
   * header". Strip the tag whenever the source video codec is not HEVC so the
   * copy still muxes with its native (avc1) sample entry. Only removes the tag
   * for a non-HEVC source; the HEVC path is left untouched.
   */
  void maybeStripInapplicableHvc1Tag(java.util.ArrayList xcodeParamsVec)
  {
    if (xcodeParamsVec == null || sourceFormat == null) return;
    String srcVideo = sourceFormat.getPrimaryVideoFormat();
    if (sage.media.format.MediaFormat.HEVC.equals(srcVideo)) return; // tag is correct for HEVC
    for (int i = 0; i < xcodeParamsVec.size() - 1; i++)
    {
      Object o = xcodeParamsVec.get(i);
      if (!(o instanceof String)) continue;
      String tok = (String) o;
      if ((tok.equals("-tag:v") || tok.equals("-vtag") || tok.equals("-codec_tag:v"))
          && "hvc1".equalsIgnoreCase(String.valueOf(xcodeParamsVec.get(i + 1))))
      {
        if (Sage.DBG) System.out.println("FFMPEGTranscoder: stripping inapplicable '-tag:v hvc1'"
            + " (source video codec is " + srcVideo + ", not HEVC) to avoid ffmpeg header failure");
        xcodeParamsVec.remove(i + 1);
        xcodeParamsVec.remove(i);
        return;
      }
    }
  }

  /**
   * The keyframe-align input seek value (seconds, as a string) for the video-copy
   * fMP4 path, or {@code null} when no such seek should be added. On a live mid-GOP
   * tune-in the audio decodes from the stream-join point while the COPIED video can
   * only begin at the first keyframe (2-4s later on ATSC3 HEVC); ffmpeg emits the
   * leading pre-keyframe audio and movenc ({@code +empty_moov}) zeroes each track's
   * first-fragment {@code tfdt} independently, so that ~1-GOP offset is dropped and
   * audio plays ~2s ahead of video. A small keyframe-snapped input seek
   * ({@code -ss <v> -noaccurate_seek}) discards the orphan pre-keyframe audio so both
   * tracks share the first video keyframe as their common origin;
   * {@code -noaccurate_seek} snaps to the keyframe (not the exact time) so an
   * already-aligned stream loses nothing. Only applied for {@link #isVideoCopyToFmp4()}
   * and when we are not already seeking ({@link #transcodeStartSeekTime} == 0).
   * Configurable via {@code ffmpeg/videocopy_kf_align_seek}; set to {@code 0} (or a
   * non-positive/invalid value) to disable.
   */
  String videoCopyKeyframeAlignSeek()
  {
    if (!isVideoCopyToFmp4()) return null;
    if (transcodeStartSeekTime != 0) return null;
    String kfAlign = Sage.get("ffmpeg/videocopy_kf_align_seek", "0.1");
    double kfAlignVal;
    try { kfAlignVal = Double.parseDouble(kfAlign); } catch (NumberFormatException e) { return null; }
    return kfAlignVal > 0 ? kfAlign : null;
  }

  /**
   * Resolves the {@code -af aresample=async=N} drift-correction value to use for
   * the current transcode's audio re-encode. Reads {@code ffmpeg/aresample_async_ac4}
   * (falling back to {@code ffmpeg/aresample_async}, then {@code 1000}) for an AC-4
   * source being downmixed, or {@code ffmpeg/aresample_async} (default {@code 1}) for
   * everything else -- these are legacy properties tuned for the fixed-rate mpeg4
   * re-encode placeshifting ladder.
   * <p>
   * On {@link #isVideoCopyToFmp4()} sessions the copied video track's PTS comes
   * straight from the source -- there is no encoder to re-time it -- so audio
   * resample-based drift correction is the ONLY mechanism keeping the two tracks
   * aligned over a long live session; unlike a full re-encode there is no fallback.
   * A configured value of {@code <= 0} (no correction at all) silently defeats
   * that, and in production has been observed getting through as an inherited
   * {@code ffmpeg/aresample_async}-family property value that predates this
   * copy-through path and was tuned for a different (fixed-rate re-encode) use
   * case -- not a deliberate "disable A/V sync" choice. Since there's no
   * self-correcting alternative here, floor it back up to a working value
   * ({@code ffmpeg/videocopy_aresample_async_floor}, default {@code 1000}) UNLESS
   * the operator explicitly opts out via the dedicated
   * {@code ffmpeg/videocopy_allow_async_zero=true} kill-switch (for troubleshooting
   * only -- expect growing A/V drift on a long live session if set).
   */
  String resolveAudioResampleAsync(boolean isAc4Source)
  {
    String asyncVal = isAc4Source
        ? Sage.get("ffmpeg/aresample_async_ac4", Sage.get("ffmpeg/aresample_async", "1000"))
        : Sage.get("ffmpeg/aresample_async", "1");
    if (isVideoCopyToFmp4() && !Sage.getBoolean("ffmpeg/videocopy_allow_async_zero", false))
    {
      double v;
      try { v = Double.parseDouble(asyncVal); } catch (NumberFormatException e) { v = -1; }
      if (v <= 0)
      {
        String floorVal = Sage.get("ffmpeg/videocopy_aresample_async_floor", "1000");
        if (Sage.DBG) System.out.println("FFMPEGTranscoder: configured aresample_async"
            + (isAc4Source ? "_ac4" : "") + "=" + asyncVal + " disables audio drift"
            + " correction, which is unsafe on the video-copy fMP4 path (video PTS is"
            + " fixed by the source -- only audio resampling can track it); flooring"
            + " to " + floorVal + ". Set ffmpeg/videocopy_allow_async_zero=true to"
            + " explicitly force it off for troubleshooting.");
        asyncVal = floorVal;
      }
    }
    return asyncVal;
  }

  /**
   * Builds the complete {@code -af} argument for an audio re-encode. Single source
   * of truth for both the {@code dynamicRateAdjust}/mpeg4 branch and the mp4-family
   * branch of {@code startTranscode()}, which previously carried byte-identical
   * copies of this logic -- a duplication that is exactly how the two paths drift
   * apart when only one of them gets a fix.
   * <p>
   * Note for future A/V-sync work: {@code aresample}'s {@code first_pts=0} option
   * is the usual textbook remedy for "audio starts offset from video", and was
   * measured here against a real ATSC 3.0 HEVC/AC-4 capture on the
   * {@link #isVideoCopyToFmp4()} path. It changed the output by {@code 0}: with or
   * without it, the decoded audio content landed at the same offset (158.7ms vs
   * 157.9ms across paths) and the only residual difference was the copied video
   * track's own 83ms {@code start_time}. Don't re-add it speculatively without
   * measuring the decoded PCM onset -- container {@code start_time} alone is
   * misleading on fragmented MP4.
   */
  String buildAudioResampleFilter(boolean isAc4Source, boolean isDownmixToStereo)
  {
    String asyncVal = resolveAudioResampleAsync(isAc4Source);
    String resample = "aresample=async=" + asyncVal;
    if (isAc4Source && isDownmixToStereo)
    {
      String filter = "aformat=channel_layouts=stereo," + resample;
      if (Sage.DBG) System.out.println("FFMPEGTranscoder: AC-4 downmix filter: " + filter);
      return filter;
    }
    return resample;
  }

  public void startTranscode() throws java.io.IOException
  {
    // Never orphan a still-running child by overwriting xcodeProcess below. The
    // normal restart path calls stopTranscode() first (which nulls xcodeProcess),
    // so this is a no-op there. But any path that reaches here with a live child
    // still attached (e.g. a repeated cold-start that mis-read xcodeDone) would
    // otherwise leak an ffmpeg/NVENC session to the process table -- exactly the
    // orphaned-transcode pileup that starves the GPU. Reap it first.
    if (xcodeProcess != null)
      stopTranscode();

    xcodeBufferBaseNum = 0;
    lastExitCode = -1;
    clearPreparedEmbeddedCcSubtitleFile();

    java.util.ArrayList xcodeParamsVec = new java.util.ArrayList();
    // Reduce process priority this way on non-windows platforms.
    // Windows has no command-prefix equivalent, so it is handled after the child
    // starts via ProcessPriority.reduce() (see below, near xcodePb.start()).
    // Optional ionice wrap (transcoder I/O priority class):
    //   xcode_ionice_class= (empty = skip) | 1 (realtime) | 2 (besteffort) | 3 (idle)
    // Optional explicit nice level:
    //   xcode_nice_level=   (empty = system default +10) | 0..19
    if (!Sage.WINDOWS_OS && Sage.getBoolean("xcode_reduce_process_priority", true))
    {
      String ioniceClass = Sage.get("xcode_ionice_class", "");
      if (ioniceClass.length() > 0)
      {
        xcodeParamsVec.add("ionice");
        xcodeParamsVec.add("-c");
        xcodeParamsVec.add(ioniceClass);
      }
      xcodeParamsVec.add("nice");
      String niceLevel = Sage.get("xcode_nice_level", "");
      if (niceLevel.length() > 0)
      {
        xcodeParamsVec.add("-n");
        xcodeParamsVec.add(niceLevel);
      }
    }
    // Find the transcoder engine — pass the source format so we can swap to the
    // AC-4 capable ffmpeg build when the source is HEVC/AC-4 (ATSC 3.0).
    xcodeParamsVec.add(getTranscoderPath(sourceFormat));

    currStreamOverheadPerct = 0.10f; // about 10% for MPEG 2 program stream

    // ORDER OF PARAMETERS MATTERS A LOT FOR FFMPEG.
    // 1. We have to put the input filename before the codec information or it won't obey it
    // 2. We have to put itsoffset before the input filename or it won't obey it

    // To specify stream mapping, we list the streams we want in the output. Each stream needs a -map parameter.
    // The video should be first, and then the audio.

    if (transcodeStartSeekTime != 0)
    {
      xcodeParamsVec.add("-ss");
      // Fractional seconds. The old integer truncation (transcodeStartSeekTime/1000)
      // silently dropped up to 999 ms on EVERY seek -- always rounding DOWN, i.e.
      // toward earlier content, which is the safe direction but still visibly
      // off on a resume. Emit ms precision so the seek lands where asked.
      xcodeParamsVec.add(String.format(java.util.Locale.US, "%d.%03d",
          transcodeStartSeekTime / 1000, transcodeStartSeekTime % 1000));

      // Narflex: further testing on 3/27/07 shows this isn't needed anymore, so we're disabling it.
      // We're also changing the dts_delta_threshold so the timestamps get reset appropriately if we're seeking close to the front
      /*			if (transcodeStartSeekTime < 15000)
			{
				xcodeParamsVec.add("-dts_delta_threshold");
				xcodeParamsVec.add("2");
			}
       */
      // NOTE: Ugly hack!
      // From testing the itsoffset parameter is needed for anything but an MPEG source or WMA
      // BUT we can't use it if we're in copyts mode
      /*String fileLC = currFile.toString().toLowerCase();
			if (!fileLC.endsWith(".mpg") && !fileLC.endsWith(".ts") && !fileLC.endsWith(".mpeg") && !fileLC.endsWith(".vob") &&
				!fileLC.endsWith(".wma") && (xcodeParams == null || xcodeParams.indexOf("-copyts") == -1))
			{
				xcodeParamsVec.add("-itsoffset");
				xcodeParamsVec.add(Long.toString(transcodeStartSeekTime/1000));
			}*/
    }
    if (httplsMode)
      segmentTargetCounter = (int)(transcodeStartSeekTime / segmentDur);

    // ffmpeg log verbosity. Historically hard-coded to "3" (below AV_LOG_FATAL=8),
    // which silenced ALL stderr — including "Permission denied" on the output file
    // and the periodic "frame=... time=... speed=..." progress lines the progress
    // parser relies on (UI gauge stayed at 0%). Default to "info" so the UI tracks
    // progress and operational errors are visible. Override via Sage.properties:
    //   xcode_ffmpeg_loglevel=quiet|panic|fatal|error|warning|info|verbose|debug
    // or a numeric level (0-56). Set to "error" for quieter logs once stable.
    xcodeParamsVec.add("-v");
    xcodeParamsVec.add(Sage.get("xcode_ffmpeg_loglevel", "info"));

    xcodeParamsVec.add("-y");

    // Optional diagnostic: emit machine-readable progress to stderr every N seconds
    // so the XcodeStderrConsumer logs periodic "out_time_us=/speed=" lines. This is
    // the clean discriminator between a SERVER-side transcode stall (out_time_us
    // freezes for the duration of the on-screen freeze) and a CLIENT-side render/
    // decode stall (out_time_us keeps advancing while the client is frozen). The
    // default -v info stats line is \r-terminated and gets swallowed by the line-
    // oriented stderr consumer, so it is unreliable for this. Off by default (adds
    // ~1 stderr block/sec); enable for a diagnostic run with:
    //   xcode_progress_probe=1        (optional xcode_progress_period_secs, default 1)
    // -stats_period / -progress are global options and must precede -i, which is
    // where we are here.
    if (Sage.getBoolean("xcode_progress_probe", false))
    {
      xcodeParamsVec.add("-stats_period");
      xcodeParamsVec.add(Sage.get("xcode_progress_period_secs", "1"));
      xcodeParamsVec.add("-progress");
      xcodeParamsVec.add("pipe:2");
    }

    // GPU-accelerated DECODE (input option, must precede -i). Set by the
    // pull-xcode/browserhd path via setHwaccelDecode(). Offloads HEVC/H.264
    // decode to the GPU (e.g. NVDEC via "cuda"), the main start/seek latency
    // for high-res HEVC. No -hwaccel_output_format => frames land in system
    // memory, so the downstream CPU filter graph + encoder are untouched and
    // ffmpeg falls back to software decode for unsupported codecs.
    // Decide the httpls/CMAF video pipeline (remux-first / full-GPU / default)
    // BEFORE any decode or encode args are emitted, so both blocks agree.
    planHttplsVideoPipeline();
    if (hwaccelDecode != null && hwaccelDecode.length() > 0)
    {
      xcodeParamsVec.add("-hwaccel");
      xcodeParamsVec.add(hwaccelDecode);
    }
    else if (httplsHwFullGpu)
    {
      // T3: keep decoded frames in CUDA memory (NVDEC) so the -vf chain
      // (yadif_cuda + scale_cuda/scale_npp) and h264_nvenc all run in-VRAM with
      // no CPU round-trip -- the fix for the ~0.55x-realtime software-decode
      // CMAF path. A generic -hwaccel (not a per-codec cuvid decoder) is used so
      // ffmpeg still falls back cleanly if a specific stream can't be NVDEC'd.
      xcodeParamsVec.add("-hwaccel");
      xcodeParamsVec.add("cuda");
      xcodeParamsVec.add("-hwaccel_output_format");
      xcodeParamsVec.add("cuda");
      if (Sage.DBG) System.out.println("FFMPEGTranscoder: httpls: T3 full-GPU decode "
          + "(-hwaccel cuda -hwaccel_output_format cuda) scaler=" + httplsHwScaler);
    }
    else
    {
      // Native placeshifter ATSC 3.0 HEVC path: route decode through NVDEC
      // cuvid so the missing-reference (RPS/POC) errors the software HEVC
      // decoder throws on live ATSC 3.0 broadcasts no longer cause freezes.
      // Empty (no-op) unless the source is MPEG2-TS/HEVC and HW is available.
      xcodeParamsVec.addAll(nativeHevcHwDecodeArgs());
    }

    if(multiThread) {
      // decode gets one thread, emphasis on encoding...let's try two, should help with H264 decode
      xcodeParamsVec.add("-threads");
      xcodeParamsVec.add("2");
    }

    // For offline conversion outputs, allow subtitle/CC streams to be carried
    // in the destination container instead of force-dropping them.
    boolean embedSubtitleStreams = shouldEmbedSubtitleStreams();
    if (!embedSubtitleStreams)
      xcodeParamsVec.add("-sn");

    // Set the flag to disable DTS parsing (which is broken in some HDPVR files) if its an MPEG2-TS w/ H264 video
    if (sourceFormat != null && sage.media.format.MediaFormat.MPEG2_TS.equals(sourceFormat.getFormatName()) &&
        sage.media.format.MediaFormat.H264.equals(sourceFormat.getPrimaryVideoFormat()) &&
        sage.media.format.MediaFormat.AC3.equals(sourceFormat.getPrimaryAudioFormat()) &&
        Sage.getBoolean("xcode_fix_broken_hdpvr_streams", false))
      xcodeParamsVec.add("-brokendts");

    // FFmpeg 7+: -vsync/-async are removed and replaced by -fps_mode and
    // -af aresample=async=N. Both are OUTPUT options, so we no longer reserve
    // slots before -i; instead the sync block below appends them to the output
    // side of the command (right before the output filename).

    // We need a very high bitrate tolerance in order to prevent FFMPEG from trying to compensate for our adaptive bitrate changes.
    // This is limited by 32-bits
    // UPDATE: I'm not really sure what's best here. If we go high, then there'll be more changes in bitrate which won't
    // deal as well with our optimization to minimize delay while maximizing bandwidth usage. But if we go low then there's very
    // perceivable changes in quality that are very distracting (when I tried 10, it was pretty bad)
    if (dynamicRateAdjust)
    {
      //			xcodeParamsVec.add("-bt");
      //			xcodeParamsVec.add("10000000");
    }

    if (transcodeEditDuration > 0)
    {
      xcodeParamsVec.add("-t");
      xcodeParamsVec.add(Long.toString(transcodeEditDuration/1000));
    }

    if (activeFile)
    {
      // Live-DVR follow mode: keep reading the input while the recorder is still
      // appending to it. Historically this was the SageTV-fork-only flag
      // "-activefile" (docs/FFMPEG_UNIFICATION_PLAN.md 0002-add-activefile-flag).
      // The consolidated CUDA ffmpeg now deployed does NOT carry that patch, so
      // "-activefile" is rejected with "Option not found" and the whole input
      // open fails -> 0 bytes -> the pull client wedges at "Loading...". It DOES
      // support the upstream-native "-follow 1" (empirically verified on this
      // binary), which is the same "keep following a growing file" semantics and
      // is cleanly ended by the "inactivefile" stdin control below via
      // -stdinctrl. Prefer -follow 1; the legacy flag stays available behind a
      // property for any environment still running the old fork binary.
      if (Sage.getBoolean("ffmpeg/use_follow_flag", true))
      {
        xcodeParamsVec.add("-follow");
        xcodeParamsVec.add("1");
      }
      else
        xcodeParamsVec.add("-activefile");
    }

    // -stdinctrl is a SageTV custom flag re-implemented in the unified
    // FFmpeg build (see docs/FFMPEG_UNIFICATION_PLAN.md). Always pass it;
    // it lets us send 'inactivefile' / 'videorateadapt' over stdin during
    // an in-flight transcode for slow-link bandwidth adaptation.
    xcodeParamsVec.add("-stdinctrl");

    // Having this on puts us in too much danger of underflow since it doesn't give us enough control
    //if (Sage.getBoolean("media_server/dont_transcode_faster_than_realtime", true))
    //	xcodeParamsVec.add("-re");

    int targetWidth=720,targetHeight=480;
    sage.media.format.VideoFormat srcVideo = sourceFormat == null ? null : sourceFormat.getVideoFormat();
    if (srcVideo != null)
    {
      targetWidth = srcVideo.getWidth();
      targetHeight = srcVideo.getHeight();
    }

    String videoCodec = "";

    // AC-4 in MPEG-TS: the demuxer has no AC-4 parser, so stream-level
    // timestamps may drift. Use demuxer timebase (-copytb 0) for more
    // reliable timing, and disable the initial audio/video start-time gap
    // that causes A/V desync in fragmented MP4 output.
    if (sourceFormat != null
        && sage.media.format.MediaFormat.MPEG2_TS.equals(sourceFormat.getFormatName())
        && sage.media.format.MediaFormat.AC4.equals(sourceFormat.getPrimaryAudioFormat()))
    {
      xcodeParamsVec.add("-copytb");
      xcodeParamsVec.add("0");
      if (Sage.DBG) System.out.println("FFMPEGTranscoder: AC-4 in MPEG-TS — adding -copytb 0 (no AC-4 TS parser)");
    }

    // Live OTA/cable source resilience. Off-air MPEG-2/AC-3 recordings routinely
    // carry the occasional corrupt packet or a timestamp discontinuity from a
    // brief signal dropout mid-programme. ffmpeg's default reaction is to error
    // on the packet ("Error submitting packet to decoder: Invalid data" /
    // "timestamp discontinuity") but keep it in play, which on the segmented
    // muxer can wedge output: the audio stream stops advancing across the bad
    // region, the muxer holds the ready video packets waiting to interleave, and
    // a segment never finalizes while the ffmpeg process sits alive but idle
    // (observed live: 5.1 AC-3 corruption near a segment boundary -> that .m4s
    // never closes -> the pull client stalls "Loading" for the 30s wait, aborts
    // and retries into the SAME bad spot). Tell the demuxer to drop corrupt
    // packets and the decoders to skip past errors so the stream steps over the
    // damage instead of stalling on it. Input options (apply to the -i below);
    // live-tunable (property=false reverts without a rebuild).
    if (httplsMode && Sage.getBoolean("httpls/input_error_resilience", true))
    {
      xcodeParamsVec.add("-err_detect");
      xcodeParamsVec.add("ignore_err");
      xcodeParamsVec.add("-fflags");
      xcodeParamsVec.add("+discardcorrupt");
    }

    // For live/active files, reduce probe time to minimize channel-change
    // latency. Default probesize (5MB) can take 5-11s on ATSC3 streams.
    // 1MB + 1.5s analyzeduration is enough for HEVC+AC-4 detection.
    if (activeFile)
    {
      // Video-COPY into fMP4 is the exception: the copied track's width/height
      // and hvcC/avcC decoder config come ENTIRELY from the source stream's
      // in-band parameter sets (VPS/SPS/PPS). With +empty_moov the fMP4 init
      // segment (moov) is written UP FRONT, so if find_stream_info hasn't yet
      // parsed a keyframe carrying those parameter sets (e.g. a live HEVC/ATSC3
      // tune-in that lands mid-GOP), ffmpeg writes a malformed init segment
      // ("dimensions not set", empty hvcC) — or fails the header — and the MSE
      // client reports videoWidth=0 (audio still works). Unlike the transcode
      // path there is no encoder to supply dimensions and no keyframe-resync
      // fallback, so the probe must span far enough to see the parameter sets.
      //
      // STARTUP LATENCY (measured, not assumed): on a live tune-in the source
      // .mpg is being WRITTEN at realtime with little/no backlog, so ffmpeg's
      // find_stream_info blocks reading up to `probesize` bytes as the file
      // grows. At a typical ~4 Mbps that made the old 8 MB probe wait ~15 s
      // before ffmpeg emitted its first byte (banner→"Input #0" gap in the
      // log). probesize — NOT analyzeduration — was the binding limit here
      // (AC-4 / bin_data streams keep find_stream_info hungry to the byte
      // ceiling), so the enlarged probe was the direct cause of slow starts,
      // contrary to the old "+frag_keyframe makes it free" reasoning (only true
      // for VOD, where the whole file is already on disk).
      //
      // The param-set guarantee does NOT need a huge probe on live: the
      // keyframe-align input seek below (-ss <kf> -noaccurate_seek, default on)
      // lands ffmpeg ON a keyframe, so VPS/SPS/PPS sit at the read position and
      // a couple MB is ample. Cap the live videocopy probe so first-frame
      // latency tracks the keyframe interval (~2-4s), not an 8 MB realtime fill.
      // VOD keeps its own (large) keys — see the !activeFile branch below.
      boolean videoCopyFmp4 = isVideoCopyToFmp4();
      long probeSize = videoCopyFmp4
          ? Sage.getLong("ffmpeg/live_probesize_videocopy", 2000000)
          : Sage.getLong("ffmpeg/live_probesize", 1000000);
      long analyzeDur = videoCopyFmp4
          ? Sage.getLong("ffmpeg/live_analyzeduration_videocopy", 2500000)
          : Sage.getLong("ffmpeg/live_analyzeduration", 1500000);
      xcodeParamsVec.add("-probesize");
      xcodeParamsVec.add(Long.toString(probeSize));
      xcodeParamsVec.add("-analyzeduration");
      xcodeParamsVec.add(Long.toString(analyzeDur));
      if (Sage.DBG) System.out.println("FFMPEGTranscoder: active file — probesize=" + probeSize
          + " analyzeduration=" + analyzeDur + (videoCopyFmp4 ? " (video-copy fMP4: enlarged so"
          + " source parameter sets/dimensions are parsed before the empty_moov init segment)" : ""));

      // A/V-sync on the video-copy fMP4 path: on a live mid-GOP tune-in the audio
      // decodes from the stream-join point, but the COPIED video can only begin at
      // the first keyframe — 2-4s later on ATSC3 HEVC. ffmpeg emits that leading
      // pre-keyframe audio, and movenc (+empty_moov+default_base_moof) zeroes EACH
      // track's first-fragment baseMediaDecodeTime (tfdt) independently, so the
      // ~1-GOP gap between audio-start and the first video keyframe is dropped: the
      // keyframe is stamped at media-time 0 alongside audio that is really ~2s
      // older, and the leading audio-only fragments let the client start audio
      // before any video — the reported "audio ~2s ahead of video". A small
      // keyframe-snapped input seek makes ffmpeg discard the orphan pre-keyframe
      // audio so BOTH tracks share the first video keyframe as their common origin.
      // -noaccurate_seek snaps to the keyframe (not the exact -ss time), so an
      // already-aligned stream (keyframe at/near the start) loses nothing and no
      // NEW mid-GOP desync is introduced — verified: aligned input keeps all
      // samples, tune-in input drops only the orphan audio. Costs no extra startup
      // latency (+frag_keyframe won't flush the first fragment until the keyframe
      // anyway). Only applied when we aren't already seeking (transcodeStartSeekTime
      // == 0). Set ffmpeg/videocopy_kf_align_seek=0 to disable.
      if (videoCopyFmp4 && transcodeStartSeekTime == 0)
      {
        String kfAlign = videoCopyKeyframeAlignSeek();
        if (kfAlign != null)
        {
          xcodeParamsVec.add("-ss");
          xcodeParamsVec.add(kfAlign);
          xcodeParamsVec.add("-noaccurate_seek");
          if (Sage.DBG) System.out.println("FFMPEGTranscoder: video-copy fMP4 — adding -ss " + kfAlign
              + " -noaccurate_seek to anchor audio to the first video keyframe (drops pre-keyframe"
              + " orphan audio that would otherwise play ~1 GOP ahead of video on a mid-GOP tune-in)");
        }
      }
    }
    else if (shouldApplyVodProbeTuning())
    {
      // Fix B: on-demand (VOD) playback of a completed recording previously
      // fell all the way through to ffmpeg's blind probesize/analyzeduration
      // defaults here (this branch is the activeFile==false counterpart of
      // the live-tune-in tuning above), which can take 5-11s+ on MPEG2-PS/TS
      // sources before ffmpeg emits its first byte -- long enough that
      // Tizen/AVPlay-style clients give up and disconnect before playback
      // ever starts. Apply the SAME proven probesize/analyzeduration values
      // already used for the live path (no new heuristic), scoped explicitly
      // to the confirmed modern NG copy-family xcodeModes by name (see
      // isModernCopyFamilyXcodeMode) so legacy on-demand playback (Windows/
      // Mac Placeshifter, older MiniClients via dynamic/dynamicts/dynamich264/
      // audioonly/mpeg2psremux) is completely unaffected -- byte-for-byte
      // unchanged. If sourceFormat is unavailable (null), neither this nor
      // the activeFile branch fires and ffmpeg's current default behavior is
      // preserved automatically.
      // NOTE: VOD reads a COMPLETE file from disk, so a large probe is read
      // instantly (no realtime-fill wait like the live branch above) — the big
      // window here is genuinely near-free and is what keeps AVPlay/Tizen from
      // disconnecting before the first byte. Kept on its OWN keys so tightening
      // the live videocopy probe for channel-change latency does not touch this
      // path (byte-for-byte unchanged: 8 MB / 5 s defaults).
      boolean videoCopyFmp4 = isVideoCopyToFmp4();
      long probeSize = videoCopyFmp4
          ? Sage.getLong("ffmpeg/vod_probesize_videocopy", 8000000)
          : Sage.getLong("ffmpeg/live_probesize", 1000000);
      long analyzeDur = videoCopyFmp4
          ? Sage.getLong("ffmpeg/vod_analyzeduration_videocopy", 5000000)
          : Sage.getLong("ffmpeg/live_analyzeduration", 1500000);
      xcodeParamsVec.add("-probesize");
      xcodeParamsVec.add(Long.toString(probeSize));
      xcodeParamsVec.add("-analyzeduration");
      xcodeParamsVec.add(Long.toString(analyzeDur));
      if (Sage.DBG) System.out.println("FFMPEGTranscoder: VOD modern-copy-family xcodeMode=["
          + xcodeModeName + "] — probesize=" + probeSize + " analyzeduration=" + analyzeDur);
    }

    xcodeParamsVec.add("-i");
    if (currServer == null || currServer.length() == 0)
      xcodeParamsVec.add(IOUtils.getLibAVFilenameString(currFile.toString()));
    else
      xcodeParamsVec.add(IOUtils.getLibAVFilenameString("stv://" + currServer + "/" + currFile.toString()));

    boolean sourceHasSubtitleStreams = sourceFormat != null && sourceFormat.getNumSubpictureStreams() > 0;
    java.io.File extractedCcSubtitleFile = maybePrepareEmbeddedCcSubtitleFile(embedSubtitleStreams, sourceHasSubtitleStreams);
    if (extractedCcSubtitleFile != null)
    {
      xcodeParamsVec.add("-i");
      xcodeParamsVec.add(IOUtils.getLibAVFilenameString(extractedCcSubtitleFile.toString()));
    }

    // output file threading (encode)
    int numThreads = Sage.getInt("xcode_process_num_threads", 0);
    if (numThreads == 0)
    {
      try
      {
        numThreads = Runtime.getRuntime().availableProcessors() + 1;
      }
      catch (Throwable t)
      {
        System.out.println("ERROR calling " + Runtime.getRuntime().availableProcessors() + " of " + t);
        numThreads = 3;
      }
    }
    if (numThreads > 1 && multiThread)
    {
      // FFMPEG cannot handle more than 8 threads; now that we use 2 for decode...change this to 7
      numThreads = Math.min(7, numThreads);
      if (Sage.DBG) System.out.println("Using " + numThreads + " threads for the transcoder");
      xcodeParamsVec.add("-threads");
      xcodeParamsVec.add(Integer.toString(numThreads));
    }

    int currFps = 30;
    int qmin = 1;
    boolean isMpeg4Codec = false;
    if (httplsMode)
    {
      isMpeg4Codec = true;
      // Add the parameters for dynamic bitrate control
      if (!fmp4Mode)
      {
        xcodeParamsVec.add("-f");
        xcodeParamsVec.add("mpegts");
      }
      else if (!httplsVideoCopy)
      {
        // CMAF: force an IDR at every segment boundary so each .m4s decodes
        // standalone given init.mp4 (validated on-server 2026-09-07). The hls
        // muxer + container are added at the output-target stage below. Skipped
        // for T1 stream-copy: -force_key_frames is ignored under -vcodec copy
        // (segments split at the source's existing keyframes instead).
        xcodeParamsVec.add("-force_key_frames");
        xcodeParamsVec.add("expr:gte(t,n_forced*" + (segmentDur / 1000) + ")");
      }
      // Live/HLS video encoder selection. Route through HwEncoder so NVENC is
      // used when the host has an NVIDIA GPU + an nvenc-capable ffmpeg; else
      // fall back to software libx264 (no-GPU hosts keep working unchanged).
      // NOTE: VAAPI/QSV/AMF are intentionally NOT engaged on this path yet --
      // the httpls -vf deinterlace/scale chain needs a hwupload filter-graph
      // rework and the bundled ffmpeg is NVENC-only. See ROADMAP "AMD / Intel
      // live transcode (VAAPI / QSV / AMF)". HwEncoder.pick() only returns
      // those kinds when the ffmpeg binary actually advertises them, so this
      // stays software until that work lands.
      boolean liveNvenc = false;
      if (httplsVideoCopy)
      {
        // T1 remux-first: stream-copy the video (planHttplsVideoPipeline already
        // proved it fits the client), skipping the whole decode/scale/encode.
        if (Sage.DBG)
          System.out.println("FFMpegTranscoder: httpls: video encoder tier -> copy (T1 remux-first passthrough)");
        xcodeParamsVec.add("-vcodec");
        xcodeParamsVec.add(videoCodec = "copy");
      }
      else
      {
      HwEncoder.Kind liveKind = HwEncoder.pick("h264");
      liveNvenc = (liveKind == HwEncoder.Kind.NVENC);
      if (liveKind != HwEncoder.Kind.NONE && !liveNvenc && Sage.DBG)
        System.out.println("FFMpegTranscoder: httpls: HW encoder " + liveKind +
            " is not yet wired for the live/HLS path (needs hwupload filter-graph rework);" +
            " using software libx264. See ROADMAP: AMD/Intel live transcode.");
      if (Sage.DBG)
        System.out.println("FFMpegTranscoder: httpls: video encoder tier -> " +
            (httplsHwFullGpu ? "h264_nvenc (T3 full-GPU: NVDEC+CUDA scale)"
             : liveNvenc ? "h264_nvenc (NVENC, software decode)" : "libx264 (software)"));
      xcodeParamsVec.add("-vcodec");
      xcodeParamsVec.add(videoCodec = liveNvenc ? "h264_nvenc" : "libx264");
      }
      // Resolution selection. Legacy (.ts iOS) path keeps the historical
      // httpls_bandwidth/<kbps>/video_size ladder byte-for-byte. The NG CMAF
      // (fmp4Mode) path -- and the legacy path only when explicitly opted in via
      // httpls_rightsize_resolution -- instead RIGHT-SIZES to the source-native
      // resolution, clamped down to the LAN/WAN ceiling and the client's real
      // display (sink). This never upscales; upscaling is the GPU-enhance path.
      boolean rightSize = fmp4Mode || Sage.getBoolean("httpls_rightsize_resolution", false);
      if (rightSize)
      {
        int[] rs = computeRightSizedTarget(srcVideo, targetWidth, targetHeight);
        targetWidth = rs[0];
        targetHeight = rs[1];
        // T1 CPU guardrail: on a GPU-less server the software encoder (libx264)
        // must sustain realtime, so cap the software-encode height (default 720)
        // -- never upscale, and preserve aspect (even width). NVENC (liveNvenc)
        // and the T3 full-GPU path have the throughput headroom and are exempt.
        if (!liveNvenc && !httplsHwFullGpu)
        {
          int cpuMaxH = Sage.getInt("httpls_cpu_max_height", 720);
          if (cpuMaxH > 0 && targetHeight > cpuMaxH)
          {
            targetWidth = (int) Math.round((double) targetWidth * cpuMaxH / targetHeight);
            if ((targetWidth & 1) == 1) targetWidth++;
            targetHeight = cpuMaxH;
            if (Sage.DBG) System.out.println("FFMPEGTranscoder: httpls: T1 CPU res cap -> "
                + targetWidth + "x" + targetHeight + " (libx264 realtime headroom)");
          }
        }
        if (Sage.DBG)
          System.out.println("FFMpegTranscoder: httpls: right-sized framesize " + targetWidth + "x" + targetHeight
              + " (source=" + (srcVideo != null ? srcVideo.getWidth() + "x" + srcVideo.getHeight() : "?")
              + " sink=" + httplsSinkWidth + "x" + httplsSinkHeight
              + " localClient=" + localClient + " fmp4=" + fmp4Mode + ")");
      }
      else
      {
        String sizeKey = String.format(BITRATE_OPTIONS_SIZE_KEY, estimatedBandwidth/1000);
        String xcodeSize = Sage.get(sizeKey, Sage.get(String.format(BITRATE_OPTIONS_SIZE_KEY, "default"), "480x272"));
        if (Sage.DBG)
          System.out.println("FFMpegTranscoder: httpls: Using framesize "+xcodeSize+" for bandwidth: "+(estimatedBandwidth/1000)+" base on key: " + sizeKey);
        // this will always return a valid 2 element array of w and h
        int size[] = parseFrameSize(xcodeSize, 480, 272);
        if (Sage.DBG)
          System.out.println("FFMpegTranscoder: httpls: Calculated framesize " + size[0] + "x" + size[1]);
        targetWidth = size[0];
        targetHeight = size[1];
      }
      currAudioBitrateKbps = 32;
      // Per-variant bitrate from the shared sage.media.BitratePolicy for THIS
      // variant's resolution, clamped by the client's link. The old formula
      // (link - 32k) tied the encode rate to the raw link, so a fast LAN client
      // got e.g. ~50 Mbps into a 480x272 variant -- bits the small frame can't
      // use and the segmenter/buffer chokes on. HLS still switches quality by
      // re-opening at a different frame size (ABR); this only right-sizes the
      // bitrate each variant launches with, consistent with every other path.
      int hlsLinkKbps = (estimatedBandwidth > 0 && estimatedBandwidth < 49000000L)
          ? (int) (estimatedBandwidth / 1000L) : 0;
      // Frame rate selection. Legacy httpls forced a flat 29.97 fps, which
      // HALVED 59.94 fps sources (e.g. a 720p60 sports/action feed) -- a real
      // quality regression against "best video for the source". The right-sized
      // paths (CMAF always; legacy TS only when httpls_rightsize_resolution is
      // on) preserve the SOURCE cadence, capped at the LAN/WAN ceiling
      // (getDynamicMaxFps: LAN up to ffmpeg/dynamic_max_fps_lan=60, WAN NTSC/PAL
      // default). min() with source fps means we never invent motion the source
      // doesn't have (a 24p film stays 24p; a 59.94 feed stays 59.94 on LAN).
      double targetFps;
      if (rightSize)
      {
        double srcFps = (srcVideo != null) ? srcVideo.getFps() : 0;
        int fpsCeil = getDynamicMaxFps(srcVideo);
        targetFps = (srcFps > 0) ? Math.min(srcFps, (double) fpsCeil) : fpsCeil;
      }
      else
        targetFps = 29.97;
      sage.enhance.EnhancementProfile hlsProf = sage.enhance.MotionHint.profileForFile(currFile);
      sage.media.BitratePolicy.Motion hlsMotion = mapPolicyMotion(
          sage.enhance.MotionHint.motionFor(hlsProf, 30));
      sage.media.BitratePolicy.Plan hlsPlan = sage.media.BitratePolicy.compute(
          targetWidth, targetHeight, targetFps, videoCodec, hlsMotion, hlsLinkKbps, 0);
      currVideoBitrateKbps = hlsPlan.targetKbps;
      policyCeilingKbps = hlsPlan.maxrateKbps;
      // FFmpeg 7.x: bare -b is ambiguous; must use -b:v. Skipped entirely for T1
      // stream-copy (no encode). For T3 full-GPU the frame size moves into the
      // CUDA -vf filtergraph (scale_cuda/scale_npp), so -s is omitted there too.
      if (!httplsVideoCopy)
      {
        xcodeParamsVec.add("-b:v");
        xcodeParamsVec.add(currVideoBitrateKbps*1000 + "");
        if (!httplsHwFullGpu)
        {
          xcodeParamsVec.add("-s");
          xcodeParamsVec.add(targetWidth + "x" + targetHeight);
        }
        xcodeParamsVec.add("-r");
        // rightSize: source cadence capped at the LAN/WAN ceiling (see above).
        // legacy: unchanged flat 29.97.
        xcodeParamsVec.add(rightSize
            ? String.format(java.util.Locale.US, "%.3f", targetFps) : "29.97");
      }
      // --- Audio codec negotiation: source -> down to player capability ---
      // HLS/MPEG-TS segments may only carry AAC, AC-3 or E-AC-3. If the client
      // (its effective ClientProfile audio set) can decode the SOURCE audio
      // codec AND that codec is HLS-safe, pass it through untouched
      // (-acodec copy) for best quality and zero transcode cost -- "unless the
      // player is equal". Otherwise transcode down to AAC-LC (aac_low), the
      // broadest-compatibility target for hls.js and native iOS HLS. HE-AAC v2
      // was dropped: its parametric stereo decodes unreliably in hls.js/iOS.
      //
      // Protocol v2.1 Phase 2.5 override: when the winning PlaybackSurface
      // published a TARGET audio codec via setHttplsSurfaceTargetAudioCodec(),
      // that is the honest per-decode-path signal and OVERRIDES the coarse
      // V1 clientSupportsHttplsAudioCodec() lookup. Copy only when the
      // target matches source AND source is HLS-safe; else transcode to
      // the surface's target (currently AAC via the existing libfdk_aac
      // path). Legacy sessions leave httplsSurfaceTargetAudioCodec empty
      // and fall through to the pre-Phase-2.5 client-caps decision.
      String srcAudCodec = (sourceFormat != null && sourceFormat.getAudioFormat() != null)
          ? sourceFormat.getAudioFormat().getFormatName() : null;
      boolean audioPassthrough;
      if (httplsSurfaceTargetAudioCodec != null && httplsSurfaceTargetAudioCodec.length() > 0)
      {
        audioPassthrough = isHlsSafeAudioCodec(srcAudCodec)
            && canonicalAudioCodec(srcAudCodec).equals(canonicalAudioCodec(httplsSurfaceTargetAudioCodec));
        if (Sage.DBG) System.out.println("FFMpegTranscoder: httpls: surface v2.1 target audio codec="
            + httplsSurfaceTargetAudioCodec + " sourceCodec=" + srcAudCodec
            + " -> " + (audioPassthrough ? "-acodec copy (match)" : "transcode to target"));
      }
      else
      {
        audioPassthrough =
            isHlsSafeAudioCodec(srcAudCodec) && clientSupportsHttplsAudioCodec(srcAudCodec);
      }
      if (audioPassthrough)
      {
        if (Sage.DBG) System.out.println("FFMpegTranscoder: httpls: audio passthrough (-acodec copy) for source codec "
            + srcAudCodec + " (client-supported and HLS-safe)");
        xcodeParamsVec.add("-acodec");
        xcodeParamsVec.add("copy");
      }
      else
      {
        if (Sage.DBG) System.out.println("FFMpegTranscoder: httpls: audio transcode to AAC-LC (aac_low); sourceCodec="
            + srcAudCodec + " clientAudio=" + httplsClientAudioCodecs);
        xcodeParamsVec.add("-acodec");
        xcodeParamsVec.add("libfdk_aac");
        xcodeParamsVec.add("-profile:a");
        xcodeParamsVec.add("aac_low"); // AAC-LC: broad hls.js / iOS HLS compatibility
        // FFmpeg 7.x: -ab is deprecated; use -b:a
        xcodeParamsVec.add("-b:a");
        xcodeParamsVec.add(Integer.toString(currAudioBitrateKbps * 1000)); // FFMPEG takes audio in bits/sec now
        xcodeParamsVec.add("-ac");
        xcodeParamsVec.add("2");
        xcodeParamsVec.add("-ar");
        xcodeParamsVec.add("44100");
      }
      if (httplsVideoCopy)
      {
        // T1 stream-copy: no encoder rate-control params (nothing is encoded).
      }
      else if (liveNvenc)
      {
      xcodeParamsVec.add("-preset");
      xcodeParamsVec.add(Sage.get("multimedia/hwaccel/nvenc/live_preset", "p4"));
      xcodeParamsVec.add("-rc:v");
      xcodeParamsVec.add("vbr");
      // NVENC honors -force_key_frames ONLY when -forced-idr is enabled; without
      // it the encoder silently drops the forced-IDR request and closes GOPs on
      // its own -g cadence instead. On the CMAF path we add -force_key_frames at
      // every segment boundary (segmentDur) above, so a missing -forced-idr made
      // nvenc emit IDRs on the -g 250 GOP (~8.3s @30fps) rather than at 5s. The
      // hls muxer then split on those GOP keyframes, producing ~8.3s .m4s files
      // while the client (and our -output_ts_offset / part->time math) assume
      // segmentDur. That mismatch drifts the live-edge estimate and provokes
      // needless far-forward reseeks. Enabling forced-idr makes segment length
      // equal segmentDur, so part N maps cleanly to N*segmentDur. Only meaningful
      // when -force_key_frames is present (CMAF re-encode); a harmless no-op on
      // the legacy TS path where no forced keyframes are requested.
      xcodeParamsVec.add("-forced-idr");
      xcodeParamsVec.add("1");
      xcodeParamsVec.add("-g");
      xcodeParamsVec.add("250");
      xcodeParamsVec.add("-keyint_min");
      xcodeParamsVec.add("25");
      xcodeParamsVec.add("-bf");
      xcodeParamsVec.add("0");
      xcodeParamsVec.add("-profile:v");
      xcodeParamsVec.add("high");
      xcodeParamsVec.add("-level:v");
      xcodeParamsVec.add("auto");
      }
      else
      {
      // T1 CPU guardrail: pick a realtime-safe x264 preset by core count/height
      // FIRST, so the fine-tuning option soup below still overrides individual
      // knobs. This is what lets a GPU-less server keep up with the live edge.
      int cpuCores = Runtime.getRuntime().availableProcessors();
      String x264Preset = libx264LivePreset(cpuCores, targetHeight);
      xcodeParamsVec.add("-preset");
      xcodeParamsVec.add(x264Preset);
      if (Sage.DBG) System.out.println("FFMPEGTranscoder: httpls: libx264 realtime preset="
          + x264Preset + " cores=" + cpuCores + " targetH=" + targetHeight);
      xcodeParamsVec.add("-coder");
      xcodeParamsVec.add("0");
      xcodeParamsVec.add("-flags");
      xcodeParamsVec.add("+loop");
      xcodeParamsVec.add("-cmp");
      xcodeParamsVec.add("+chroma");
      // FFmpeg 6.x uses comma-separated partition names instead of +/- prefixed format
      xcodeParamsVec.add("-partitions");
      xcodeParamsVec.add("i8x8,i4x4,p8x8");
      xcodeParamsVec.add("-me_method");
      xcodeParamsVec.add("dia");
      xcodeParamsVec.add("-subq");
      xcodeParamsVec.add("1");
      xcodeParamsVec.add("-me_range");
      xcodeParamsVec.add("16");
      xcodeParamsVec.add("-g");
      xcodeParamsVec.add("250");
      xcodeParamsVec.add("-keyint_min");
      xcodeParamsVec.add("25");
      xcodeParamsVec.add("-sc_threshold");
      xcodeParamsVec.add("40");
      xcodeParamsVec.add("-i_qfactor");
      xcodeParamsVec.add("0.71");
      xcodeParamsVec.add("-b_strategy");
      xcodeParamsVec.add("1");
      xcodeParamsVec.add("-qcomp");
      xcodeParamsVec.add("0.6");
      xcodeParamsVec.add("-qmin");
      xcodeParamsVec.add("10");
      xcodeParamsVec.add("-qmax");
      xcodeParamsVec.add("51");
      xcodeParamsVec.add("-qdiff");
      xcodeParamsVec.add("4");
      xcodeParamsVec.add("-bf");
      xcodeParamsVec.add("0");
      xcodeParamsVec.add("-refs");
      xcodeParamsVec.add("1");
      // FFmpeg 6.x renamed directpred→direct-pred, rc_lookahead→rc-lookahead
      // Removed -flags2 -wpred-dct8x8 (applied globally, breaks non-x264 encoders)
      xcodeParamsVec.add("-direct-pred");
      xcodeParamsVec.add("1");
      xcodeParamsVec.add("-trellis");
      xcodeParamsVec.add("0");
      xcodeParamsVec.add("-wpredp");
      xcodeParamsVec.add("0");
      xcodeParamsVec.add("-rc-lookahead");
      xcodeParamsVec.add("50");
      xcodeParamsVec.add("-level:v");
      xcodeParamsVec.add("30");
      }
      if (!httplsVideoCopy)
      {
      xcodeParamsVec.add("-maxrate");
      xcodeParamsVec.add(currVideoBitrateKbps*6000/5 + "");
      xcodeParamsVec.add("-bufsize");
      xcodeParamsVec.add(currVideoBitrateKbps*5000 + "");
      }

      // FFmpeg 7.x: -deinterlace is removed; users now express deinterlace via -vf yadif.
      // Skip auto-add if user already asked for either legacy or modern form, OR if the
      // video stage is a stream copy (filter+copy is a hard ffmpeg error -- see
      // shouldAutoAddYadif javadoc). Always false in this httpls/live branch since it
      // always sets a real video encoder, kept for defense-in-depth/consistency with the
      // other occurrence below.
      if (httplsVideoCopy)
      {
        // T1 stream-copy: no -vf at all (filter + copy is a hard ffmpeg error).
      }
      else if (httplsHwFullGpu)
      {
        // T3 CUDA filtergraph: [<cuda-deint>,]<scaler>=W:H[:format=nv12], all
        // operating on the NVDEC cuda frames, so nothing leaves VRAM until NVENC.
        // The deinterlacer is only inserted for a genuinely interlaced source
        // whose target keeps full field height (matches the CPU-yadif policy).
        StringBuilder vf = new StringBuilder();
        boolean deint = srcVideo != null && srcVideo.isInterlaced()
            && targetHeight > srcVideo.getHeight() / 2
            && Sage.getBoolean("xcode_auto_deinterlace", true);
        if (deint)
        {
          String cd = HwEncoder.cudaDeinterlacer(
              Sage.getBoolean("multimedia/hwaccel/httpls_bwdif", false));
          if (cd != null) vf.append(cd).append("=0:-1:1").append(',');
        }
        vf.append(httplsHwScaler).append('=').append(targetWidth).append(':').append(targetHeight);
        // Pin 8-bit output for h264_nvenc "high" (handles a 10-bit source);
        // gated on the modern-build capability probe so older builds that reject
        // the format option are never handed it.
        if (HwEncoder.scalerSupportsLanczos(httplsHwScaler)) vf.append(":format=nv12");
        xcodeParamsVec.add("-vf");
        xcodeParamsVec.add(vf.toString());
        if (Sage.DBG) System.out.println("FFMPEGTranscoder: httpls: T3 GPU -vf " + vf
            + " (deint=" + deint + ")");
      }
      else if (shouldAutoAddYadif(xcodeParamsVec, xcodeParams, srcVideo, targetHeight,
          Sage.getBoolean("xcode_auto_deinterlace", true)))
      {
        if (Sage.DBG) System.out.println("Automatically adding yadif deinterlace filter to transcoding process");
        addOrComposeYadif(xcodeParamsVec);
      }

      // Preserve aspect ratio properly -- but NEVER on a stream copy. ffmpeg
      // warns "Overriding aspect ratio with stream copy may produce invalid
      // files", and for an HEVC copy into Matroska it rewrites inconsistent
      // track DisplayWidth/Height that ExoPlayer/media3 rejects as a malformed
      // container (playback dies within seconds). The display aspect is already
      // carried in the HEVC SPS/VUI SAR, so -aspect is redundant here anyway.
      if (sourceFormat != null && !isVideoCopySelected(xcodeParamsVec))
      {
        sage.media.format.VideoFormat vidForm = sourceFormat.getVideoFormat();
        if (vidForm != null && ((vidForm.getArNum() > 0 && vidForm.getArDen() > 0) || (vidForm.getWidth() > 0 && vidForm.getHeight() > 0)))
        {
          xcodeParamsVec.add("-aspect");
          if (vidForm.getArNum() > 0 && vidForm.getArDen() > 0)
            xcodeParamsVec.add(vidForm.getArNum() + ":" + vidForm.getArDen());
          else
            xcodeParamsVec.add(vidForm.getWidth() + ":" + vidForm.getHeight());
        }
      }
    }
    else if (dynamicRateAdjust && pushH264)
    {
      // ---- Modern H.264 MPEG-TS push (replaces legacy mpeg4/DVD ~1 Mbps) ----
      // GPU-accelerated via HwEncoder when the host has NVENC; else software
      // libx264 (No-GPU hosts keep working). Resolution + bitrate track the
      // CLIENT'S REPORTED bandwidth (estimatedBandwidth, bits/sec) so NG/modern
      // clients get best-quality playback for their link instead of the old
      // fixed ~1 Mbps clamp. Output is H.264-in-MPEG-TS, decodable by every push
      // client that advertised H.264 + MPEG2-TS (the gate that picked this mode
      // in MiniPlayer). NVENC videorateadapt keeps working via dynamicRateAdjust.
      isMpeg4Codec = false;
      xcodeParamsVec.add("-f");
      xcodeParamsVec.add("mpegts");
      HwEncoder.Kind pushKind = HwEncoder.pick("h264");
      boolean pushNvenc = (pushKind == HwEncoder.Kind.NVENC);
      if (pushKind != HwEncoder.Kind.NONE && !pushNvenc && Sage.DBG)
        System.out.println("FFMPEGTranscoder: push-h264: HW encoder " + pushKind +
            " not yet wired for the push path (needs hwupload); using libx264.");
      videoCodec = pushNvenc ? "h264_nvenc" : "libx264";
      if (Sage.DBG)
        System.out.println("FFMPEGTranscoder: push-h264 encoder -> " + videoCodec
            + " (reportedBW=" + (estimatedBandwidth / 1000) + " kbps)");
      xcodeParamsVec.add("-vcodec");
      xcodeParamsVec.add(videoCodec);
      // Force 8-bit 4:2:0 output frames regardless of source bit depth. ATSC3
      // HEVC Main10 sources decode to yuv420p10le via the software HEVC decoder
      // on this path (nativeHevcHwDecodeArgs() intentionally excludes pushH264 --
      // see its javadoc), and both h264_nvenc's 8-bit "high" profile and libx264
      // without high-bit-depth support reject/mishandle 10-bit input directly
      // (observed as h264_nvenc "CreateInputBuffer failed: invalid param"). This
      // mirrors the existing audioonly h264_nvenc precedent (see audioonly video
      // codec handling above) and is a cheap no-op when the source is already
      // 8-bit yuv420p.
      xcodeParamsVec.add("-vf");
      xcodeParamsVec.add("format=yuv420p");

      // Bandwidth-aware target. estimatedBandwidth is the client's reported link
      // (bits/sec) from MiniPlayer.setEstimatedBandwidth; 0 => unknown, assume a
      // comfortable 8 Mbps. Reserve ~10% headroom plus audio.
      long bwKbps = (estimatedBandwidth > 0 ? estimatedBandwidth : 8000000L) / 1000L;
      int audioKbps = (bwKbps < 1500) ? 96 : 128;
      int videoKbps = (int) Math.max(200, bwKbps * 90 / 100 - audioKbps);
      videoKbps = Math.min(videoKbps, Sage.getInt("miniplayer/h264_push_max_video_kbps", 12000));
      int[] wh = pickH264PushSize(videoKbps, srcVideo);
      targetWidth = wh[0];
      targetHeight = wh[1];
      // Resolution follows the link (pickH264PushSize above); the ENCODE bitrate
      // now comes from the shared sage.media.BitratePolicy for that resolution,
      // so the push path agrees with browserhd/enhance on "resolution -> bitrate"
      // instead of using a bespoke 90%-of-link figure. The link is still passed
      // as the clamp, so a constrained client is throttled below the resolution
      // anchor exactly as before; NVENC videorateadapt continues to trim live.
      double pushFps = (srcVideo != null && srcVideo.getFps() > 0) ? srcVideo.getFps() : 0;
      int pushLinkKbps = (estimatedBandwidth > 0 && estimatedBandwidth < 49000000L)
          ? (int) (estimatedBandwidth / 1000L) : 0;
      sage.enhance.EnhancementProfile pushProf = sage.enhance.MotionHint.profileForFile(currFile);
      sage.media.BitratePolicy.Motion pushMotion = mapPolicyMotion(
          sage.enhance.MotionHint.motionFor(pushProf, (int) Math.round(pushFps)));
      sage.media.BitratePolicy.Plan pushPlan = sage.media.BitratePolicy.compute(
          targetWidth, targetHeight, pushFps, videoCodec, pushMotion, pushLinkKbps, 0);
      videoKbps = pushPlan.targetKbps;
      int pushMaxrateKbps = pushPlan.maxrateKbps;
      int pushBufsizeKbps = pushPlan.bufsizeKbps;
      policyCeilingKbps = pushPlan.maxrateKbps;
      currVideoBitrateKbps = videoKbps;
      currAudioBitrateKbps = audioKbps;
      currFps = MMC.getInstance().isNTSCVideoFormat() ? 30 : 25;
      xcodeParamsVec.add("-s");
      xcodeParamsVec.add(targetWidth + "x" + targetHeight);
      xcodeParamsVec.add("-r");
      xcodeParamsVec.add(MMC.getInstance().isNTSCVideoFormat() ? "29.97" : "25");
      xcodeParamsVec.add("-b:v");
      xcodeParamsVec.add(Integer.toString(currVideoBitrateKbps * 1000));
      // Audio: AAC-LC stereo -- universally decodable by H.264-capable push
      // clients. Override via miniplayer/h264_push_audio_codec.
      xcodeParamsVec.add("-acodec");
      xcodeParamsVec.add(Sage.get("miniplayer/h264_push_audio_codec", "aac"));
      xcodeParamsVec.add("-b:a");
      xcodeParamsVec.add(Integer.toString(currAudioBitrateKbps * 1000));
      xcodeParamsVec.add("-ac");
      xcodeParamsVec.add("2");
      xcodeParamsVec.add("-ar");
      xcodeParamsVec.add("48000");
      if (pushNvenc)
      {
        xcodeParamsVec.add("-preset");
        xcodeParamsVec.add(Sage.get("multimedia/hwaccel/nvenc/push_preset", "p4"));
        xcodeParamsVec.add("-rc:v");
        xcodeParamsVec.add("vbr");
        xcodeParamsVec.add("-g");
        xcodeParamsVec.add("250");
        xcodeParamsVec.add("-keyint_min");
        xcodeParamsVec.add("25");
        xcodeParamsVec.add("-bf");
        xcodeParamsVec.add("0");
        xcodeParamsVec.add("-profile:v");
        xcodeParamsVec.add("high");
        xcodeParamsVec.add("-level:v");
        xcodeParamsVec.add("auto");
      }
      else
      {
        xcodeParamsVec.add("-preset");
        xcodeParamsVec.add(Sage.get("multimedia/hwaccel/libx264/push_preset", "veryfast"));
        xcodeParamsVec.add("-g");
        xcodeParamsVec.add("250");
        xcodeParamsVec.add("-keyint_min");
        xcodeParamsVec.add("25");
        xcodeParamsVec.add("-bf");
        xcodeParamsVec.add("2");
        xcodeParamsVec.add("-profile:v");
        xcodeParamsVec.add("high");
      }
      xcodeParamsVec.add("-maxrate");
      xcodeParamsVec.add(Integer.toString(pushMaxrateKbps * 1000));
      xcodeParamsVec.add("-bufsize");
      xcodeParamsVec.add(Integer.toString(pushBufsizeKbps * 1000));
      // Preserve display aspect ratio (same as the legacy dynamic path) --
      // but never on a stream copy (ffmpeg warns it produces invalid files;
      // corrupts the Matroska track header -> client "malformed container").
      if (sourceFormat != null && !isVideoCopySelected(xcodeParamsVec))
      {
        sage.media.format.VideoFormat vidForm = sourceFormat.getVideoFormat();
        if (vidForm != null && ((vidForm.getArNum() > 0 && vidForm.getArDen() > 0) || (vidForm.getWidth() > 0 && vidForm.getHeight() > 0)))
        {
          xcodeParamsVec.add("-aspect");
          if (vidForm.getArNum() > 0 && vidForm.getArDen() > 0)
            xcodeParamsVec.add(vidForm.getArNum() + ":" + vidForm.getArDen());
          else
            xcodeParamsVec.add(vidForm.getWidth() + ":" + vidForm.getHeight());
        }
      }
    }
    else if (dynamicRateAdjust)
    {
      isMpeg4Codec = true;
      // Add the parameters for dynamic bitrate control
      xcodeParamsVec.add("-f");
      xcodeParamsVec.add(iOSMode ? "mpegts" : "dvd");
      xcodeParamsVec.add("-vcodec");
      xcodeParamsVec.add(videoCodec = "mpeg4");
      int[] dynamicRes = getDynamicMaxResolution(srcVideo);
      int dynamicWidth = dynamicRes[0];
      int dynamicHeight = dynamicRes[1];
      xcodeParamsVec.add("-s");
      xcodeParamsVec.add(dynamicWidth + "x" + dynamicHeight);
      targetWidth = dynamicWidth;
      targetHeight = dynamicHeight;
      xcodeParamsVec.add("-ac");
      // Workaround issue where AAC audio doesn't transcode properly to mono mp2
      xcodeParamsVec.add(Sage.getBoolean("xcode_disable_mono_audio", true) ? "2" : "1");
      xcodeParamsVec.add("-g");
      xcodeParamsVec.add("300");
      xcodeParamsVec.add("-bf");
      xcodeParamsVec.add("2");
      //xcodeParamsVec.add("-deinterlace");
      xcodeParamsVec.add("-acodec");
      xcodeParamsVec.add(iOSMode ? "libfdk_aac" : Sage.get("xcode_dynamic_audio_codec", "mp2"));
      int currAudioSampling, currPacketSize;
      String fdkAacProfile = null; // selected after bandwidth tier is determined
      // Fast start is very important so always start at the bottom for video bitrate
      if (estimatedBandwidth < 90000)
      {
        if (currVideoBitrateKbps == -1)
          currVideoBitrateKbps = 50;
        if (currAudioBitrateKbps == -1)
          currAudioBitrateKbps = 24;
        fdkAacProfile = "aac_he_v2"; // HE-AAC v2 optimal at <=48kbps
        // 10fps at 352x240
        currFps = 10;
        currAudioSampling = 24000;
        currPacketSize = 1024;
        qmin = 10;
      }
      else if (estimatedBandwidth < 150000)
      {
        if (currVideoBitrateKbps == -1)
          currVideoBitrateKbps = 64;//192;
        if (currAudioBitrateKbps == -1)
          currAudioBitrateKbps = 48;
        fdkAacProfile = "aac_he_v2"; // HE-AAC v2 optimal at <=48kbps
        // 15fps at 352x240
        currFps = 15;
        currAudioSampling = 24000;
        currPacketSize = 1024;
        qmin = 5;
      }
      else if (estimatedBandwidth < 900000)
      {
        if (currVideoBitrateKbps == -1)
          currVideoBitrateKbps = (int)estimatedBandwidth/2000;//128;//256;
        if (currAudioBitrateKbps == -1)
          currAudioBitrateKbps = 64;
        fdkAacProfile = "aac_he"; // HE-AAC v1 good at 64kbps
        // 15fps at 352x240
        currFps = 15;
        currAudioSampling = 48000;
        currPacketSize = 2048;
      }
      else
      {
        if (currVideoBitrateKbps == -1)
          currVideoBitrateKbps = selectDynamicVideoBitrateKbps(estimatedBandwidth);
        if (currAudioBitrateKbps == -1)
          currAudioBitrateKbps = 128; // There's issues with using 96Kbps audio encoding I discovered
        fdkAacProfile = "aac_low"; // LC-AAC is best at >=128kbps
        // 30fps at 352x240 and 48kHz audio at 96Kbps (or up to getDynamicMaxFps()
        // for a LAN client with headroom -- see that method's javadoc)
        currFps = getDynamicMaxFps(srcVideo);
        currAudioSampling = 48000;
        currPacketSize = 2048;
      }

      // Add HE-AAC profile for iOS mode (libfdk_aac supports aac_he_v2, aac_he, aac_low)
      if (iOSMode && fdkAacProfile != null)
      {
        xcodeParamsVec.add("-profile:a");
        xcodeParamsVec.add(fdkAacProfile);
      }

      xcodeParamsVec.add("-r");
      xcodeParamsVec.add(Integer.toString(currFps));
      // FFmpeg 7.x: -b is ambiguous, must use -b:v ; -ab is replaced by -b:a
      xcodeParamsVec.add("-b:v");
      xcodeParamsVec.add(Integer.toString(currVideoBitrateKbps * 1000)); // FFMPEG takes video in bits/sec now
      xcodeParamsVec.add("-ar");
      xcodeParamsVec.add(Integer.toString(currAudioSampling));
      xcodeParamsVec.add("-b:a");
      xcodeParamsVec.add(Integer.toString(currAudioBitrateKbps * 1000)); // FFMPEG takes audio in bits/sec now
      xcodeParamsVec.add("-packetsize");
      xcodeParamsVec.add(Integer.toString(currPacketSize));

      // Preserve aspect ratio properly -- but never on a stream copy. ffmpeg
      // warns "Overriding aspect ratio with stream copy may produce invalid
      // files"; on an HEVC copy into Matroska this corrupts the track header
      // and the client (media3/ExoPlayer) rejects it as a malformed container.
      // This is the exact site that fired for the 10-bit HEVC live-push that
      // died in ~5s with a parsing error. SAR already lives in the HEVC VUI.
      if (sourceFormat != null && !isVideoCopySelected(xcodeParamsVec))
      {
        sage.media.format.VideoFormat vidForm = sourceFormat.getVideoFormat();
        if (vidForm != null && ((vidForm.getArNum() > 0 && vidForm.getArDen() > 0) || (vidForm.getWidth() > 0 && vidForm.getHeight() > 0)))
        {
          xcodeParamsVec.add("-aspect");
          if (vidForm.getArNum() > 0 && vidForm.getArDen() > 0)
            xcodeParamsVec.add(vidForm.getArNum() + ":" + vidForm.getArDen());
          else
            xcodeParamsVec.add(vidForm.getWidth() + ":" + vidForm.getHeight());
        }
      }
    }
    else if (rawCmdlineMode)
    {
      // Raw-cmdline preset mode (item 6): defer all post-"-i" arg emission
      // to the splice step at the end of this method. We deliberately skip
      // the legacy tokenizer below (it would try to parse our verbatim
      // "-b:v 10000k" / "-vf scale_npp=..." tokens and either log warnings
      // or no-op rewrite them). The subsequent -fps_mode/-af/-vstats/
      // -priority blocks that the legacy path appends are also discarded by
      // the splice — the raw cmdline owns those decisions.
    }
    else
    {
      int flagsIndex = -1;
      java.util.StringTokenizer toker = new java.util.StringTokenizer(xcodeParams);
      while (toker.hasMoreTokens())
      {
        String currToke = toker.nextToken();
        // FFmpeg 7.x: rewrite legacy -b/-ab to unambiguous -b:v/-b:a as we copy through
        if (currToke.equals("-b")) { currToke = "-b:v"; }
        else if (currToke.equals("-ab")) { currToke = "-b:a"; }
        xcodeParamsVec.add(currToke);
        if ((currToke.equals("-b:v")) && toker.hasMoreTokens())
        {
          currToke = toker.nextToken();
          long bps = parseBitrateToBps(currToke);  // FFMPEG takes video in bits/sec now
          if (bps > 0)
          {
            currVideoBitrateKbps = (int)(bps / 1000);
            if (preservedVideoBitrate > 0)
              xcodeParamsVec.add(Integer.toString(preservedVideoBitrate));
            else
              xcodeParamsVec.add(Long.toString(bps));
          }
          else
          {
            System.out.println("Bad video bitrate parsed of " + currToke);
            xcodeParamsVec.add(currToke);
          }
        }
        else if (currToke.equals("-b:a") && toker.hasMoreTokens())
        {
          currToke = toker.nextToken();
          long bps = parseBitrateToBps(currToke);  // FFMPEG takes audio in bits/sec now
          if (bps > 0)
          {
            currAudioBitrateKbps = (int)(bps / 1000);
            if (preservedAudioBitrate > 0)
              xcodeParamsVec.add(Integer.toString(preservedAudioBitrate));
            else
              xcodeParamsVec.add(Long.toString(bps));
          }
          else
          {
            System.out.println("Bad audio bitrate parsed of " + currToke);
            xcodeParamsVec.add(currToke);  // keep the value so the ffmpeg command stays valid
          }
        }
        else if (currToke.equals("-r") && toker.hasMoreTokens())
        {
          currToke = toker.nextToken();
          xcodeParamsVec.add(currToke);
          try
          {
            currFps = Math.round(Float.parseFloat(currToke));
          }catch (NumberFormatException e)
          {
            System.out.println("Bad fps parsed of " + currToke + " err:" + e);
          }
        }
        else if (currToke.equals("-vcodec") && toker.hasMoreTokens())
        {
          currToke = videoCodec = toker.nextToken();
          xcodeParamsVec.add(currToke);
          if (currToke.equals("mpeg4"))
            isMpeg4Codec = true;
        }
        else if (currToke.equals("-s") && toker.hasMoreTokens())
        {
          currToke = toker.nextToken();
          xcodeParamsVec.add(currToke);
          try
          {
            targetWidth = Integer.parseInt(currToke.substring(0, currToke.indexOf('x')));
            targetHeight = Integer.parseInt(currToke.substring(currToke.indexOf('x') + 1));
          }catch (NumberFormatException e)
          {
            System.out.println("Bad target size parsed of " + currToke + " err:" + e);
          }
        }
        else if (currToke.equals("-vn"))
        {
          currVideoBitrateKbps = 0;
        }
        else if (currToke.equals("-an"))
        {
          currAudioBitrateKbps = 0;
        }
        else if (currToke.equals("-flags"))
        {
          flagsIndex = xcodeParamsVec.size();
        }
      }
      if (xcodeParams.indexOf("-aspect") == -1 && sourceFormat != null && !isVideoCopySelected(xcodeParamsVec))
      {
        // Preserve aspect ratio properly -- but never on a stream copy (ffmpeg
        // warns it produces invalid files; corrupts the container -> client
        // "malformed container"). SAR already lives in the video bitstream.
        sage.media.format.VideoFormat vidForm = sourceFormat.getVideoFormat();
        if (vidForm != null && ((vidForm.getArNum() > 0 && vidForm.getArDen() > 0) || (vidForm.getWidth() > 0 && vidForm.getHeight() > 0)))
        {
          xcodeParamsVec.add("-aspect");
          if (vidForm.getArNum() > 0 && vidForm.getArDen() > 0)
            xcodeParamsVec.add(vidForm.getArNum() + ":" + vidForm.getArDen());
          else
            xcodeParamsVec.add(vidForm.getWidth() + ":" + vidForm.getHeight());
        }
      }
      // FFmpeg 7.x: -deinterlace is removed; users now express deinterlace via -vf yadif.
      // Skip auto-add if user already asked for either legacy or modern form, OR if the
      // video stage is a stream copy (filter+copy is a hard ffmpeg error: "Filtergraph
      // 'yadif' was specified, but codec copy was selected" -> "Error opening output
      // file", killing the process before it emits a single byte). This is the real
      // fix site: this generic named-quality-template tokenizer branch is what parses
      // mpeg2psremux/mpeg2tsremux/browserhd_remux/browserhd_copyv/audioonly/DVDAudioOnly
      // -- every copy-video xcodeMode, legacy media-extender and modern NG surface alike
      // -- and today unconditionally injects yadif whenever the source is flagged
      // interlaced, regardless of whether the video is being copied. See
      // shouldAutoAddYadif javadoc. Copy means copy -- no substitute filter is added.
      if (shouldAutoAddYadif(xcodeParamsVec, xcodeParams, srcVideo, targetHeight,
          Sage.getBoolean("xcode_auto_deinterlace", true)))
      {
        if (Sage.DBG) System.out.println("Automatically adding yadif deinterlace filter to transcoding process");
        addOrComposeYadif(xcodeParamsVec);
      }
      else if (Sage.DBG && srcVideo != null && srcVideo.isInterlaced() && targetHeight > srcVideo.getHeight()/2
          && isVideoCopySelected(xcodeParamsVec))
      {
        System.out.println("FFMPEGTranscoder: skipping auto yadif deinterlace -- video codec is copy "
            + "(filter+copy is a hard ffmpeg error; source will play back interlaced/as-copied)");
      }
      // Creating interlaced video doesn't work properly yet...
      /*if (xcodeParams.indexOf("-deinterlace") == -1 && srcVideo != null && srcVideo.isInterlaced() && targetHeight == srcVideo.getHeight())
			{
				if (Sage.DBG) System.out.println("Automatically adding interlacing option to transcoding process");
				xcodeParamsVec.add("-interlace");
				xcodeParamsVec.add("1");
				// Setup the proper flags
				if (flagsIndex == -1)
				{
					xcodeParamsVec.add("-flags");
					xcodeParamsVec.add("+ilme+ildct");
				}
				else
				{
					String currFlags = xcodeParamsVec.get(flagsIndex).toString();
					if (currFlags.indexOf("+ilme") == -1)
						currFlags += "+ilme";
					if (currFlags.indexOf("+ildct") == -1)
						currFlags += "+ildct";
					xcodeParamsVec.set(flagsIndex, currFlags);
				}
				// We may also need to specify something regarding top field first or not....
			}*/
    }

    if (currVideoBitrateKbps == -1)
      currVideoBitrateKbps = 200; // the default for FFMPEG
    if (currAudioBitrateKbps == -1)
      currAudioBitrateKbps = 64; // the default for FFMPEG

    // This sets the initial complexity for the rate control algorithms. Without it, there'll be big spikes whenever we reset
    // it or at the beginning.
    if (isMpeg4Codec && outputFile == null && !httplsMode) // don't do rate control opts if we're not streaming
    {
      xcodeParamsVec.add("-muxrate");
      // The DVD/MPEG-PS muxrate is the multiplex ceiling (bits/sec) the pack layer
      // can carry. It MUST exceed the peak payload = the video -maxrate (== the
      // target video bitrate set below) + the audio bitrate + pack/PES overhead, or
      // the muxer underflows continuously: it emits an endless "[dvd] buffer
      // underflow" stderr flood and stalls its SCR pacing. The old value was a flat
      // 2 Mbit/s, which is BELOW the video bitrate for any HD/4K-sourced 1080p
      // MPEG-4 stream (e.g. a 2.93 Mbit/s IP-camera transcode), so it underflowed
      // on every pack -- flooding the stderr the status thread parses and feeding
      // the server-side playback restart loop. Derive it from the real payload with
      // ~15% headroom, floored so low-bitrate SD stays comfortably above its peak.
      long muxrateBits = Math.round((currVideoBitrateKbps + currAudioBitrateKbps) * 1000L * 1.15);
      muxrateBits = Math.max(muxrateBits, 2000000L);
      xcodeParamsVec.add(Long.toString(muxrateBits));
      xcodeParamsVec.add("-rc_init_cplx");
      // Guard against bad/missing source format (currFps==0 or targetW/H==0 from
      // unparsed dimensions) — fall back to sane defaults so we don't crash with
      // ArithmeticException: / by zero. Seen on imported MP4 files whose stored
      // fileFormat lacks dimensions (e.g. older legacy parser output).
      int cplxFps = currFps > 0 ? currFps : 30;
      int cplxW = targetWidth > 0 ? targetWidth : 720;
      int cplxH = targetHeight > 0 ? targetHeight : 480;
      int cplxMacroX = Math.max(1, (cplxW + 15) / 16);
      int cplxMacroY = Math.max(1, (cplxH + 15) / 16);
      int complexity = (currVideoBitrateKbps * 8000 / cplxFps) / (cplxMacroX * cplxMacroY);
      xcodeParamsVec.add(Integer.toString(complexity));
      xcodeParamsVec.add("-maxrate"); // FFMPEG takes video in bits/sec now
      xcodeParamsVec.add(Integer.toString(currVideoBitrateKbps * 1000));
      xcodeParamsVec.add("-minrate");
      xcodeParamsVec.add("0"); // For CBR this should be the same as max rate, but it's OK to go lower and if we don't make this 0, then qmin causes an A/V gap in the muxing
      xcodeParamsVec.add("-bufsize");
      // Headroom above -maxrate/the target bitrate, not an exact match: bufsize==maxrate==
      // bitrate gives the mpeg4 rate controller a 1-second window with ZERO slack to absorb a
      // short complexity spike, which is what produced the observed "[mpeg4] impossible bitrate
      // constraints, this will fail" warning and forced hard quality/frame drops instead of a
      // brief smoothed dip. ffmpeg/dynamic_vbv_bufsize_ratio (default 1.5x) restores real VBV
      // headroom; applies to every dynamicRateAdjust session (LAN or WAN), not just the raised
      // LAN ceiling above -- this bug existed at every bitrate tier.
      float bufsizeRatio = Sage.getFloat("ffmpeg/dynamic_vbv_bufsize_ratio", 1.5f);
      long bufsizeBits = Math.round(currVideoBitrateKbps * 1000f * bufsizeRatio);
      xcodeParamsVec.add(Long.toString(bufsizeBits));
      xcodeParamsVec.add("-mbd");
      xcodeParamsVec.add("2"); // rate distortion macroblock decisions
      if (dynamicRateAdjust)
      {
        // adding isB*75 helps with pulsing at the P-frame rate a lot compared to isB*25, it's noticable in detailed areas when there's temporarily not action
        // during an action scene
        xcodeParamsVec.add("-rc_eq");
        xcodeParamsVec.add("isI*200+isP*75+isB*75"); // rate control equation for CBR that balances I & P frame bits well
        if (qmin > 1)
        {
          xcodeParamsVec.add("-qmin");
          xcodeParamsVec.add(Integer.toString(qmin));
        }
      }
    }

    // See if we've got an unsupported audio stream
    if (sourceFormat != null)
    {
      String aud = sourceFormat.getPrimaryAudioFormat();
      if (aud != null && aud.startsWith("0X"))
      {
        if (Sage.DBG) System.out.println("Disabling audio in transcoder since it's an unsupported audio format");
        xcodeParamsVec.add("-an");
        currAudioBitrateKbps = 0;
      }
    }

    // See if there's multiple audio streams which means we need to setup stream mappings. But
    // we can only setup stream mappings if we have index information in the format.
    boolean usedExplicitStreamMapping = false;
    // Item 2 (audioTrackSelectionMode="server"): highest-precedence audio map.
    // When the winning surface asked the SERVER to preselect the audio track,
    // emit exactly one video + one audio stream so downstream receives ONLY
    // the chosen track (client-mode surfaces + legacy leave the rel index at
    // -1 and fall through to the all-audio / language-select logic below).
    if (!usedExplicitStreamMapping && currVideoBitrateKbps > 0 && currAudioBitrateKbps > 0)
    {
      String serverAudioMap = serverSelectAudioMapToken(
          httplsSurfaceServerAudioRelIndex >= 0, httplsSurfaceServerAudioRelIndex);
      if (serverAudioMap != null)
      {
        xcodeParamsVec.add("-map");
        xcodeParamsVec.add("0:v:0");
        xcodeParamsVec.add("-map");
        xcodeParamsVec.add(serverAudioMap);
        usedExplicitStreamMapping = true;
        if (sage.Sage.DBG)
          System.out.println("FFMPEGTranscoder: Item 2 server audio preselect -map 0:v:0 "
              + serverAudioMap + " (audioTrackSelectionMode=server)");
      }
    }
    if (!usedExplicitStreamMapping && currAudioBitrateKbps > 0 && sourceFormat != null && sourceFormat.getNumAudioStreams() > 1 && currVideoBitrateKbps > 0)
    {
      // Get the FFMPEG only format so we can go off the stream indexes that it wants for transcoding
      sage.media.format.ContainerFormat ffFormat = sage.media.format.FormatParser.getFFMPEGFileFormat(currFile.toString());
      if (ffFormat != null)
      {
        sage.media.format.VideoFormat vf = ffFormat.getVideoFormat();
        if (vf != null && vf.getOrderIndex() >= 0)
        {
          // Don't select HD audio streams as the source
          sage.media.format.AudioFormat[] srcAudioFormats = sourceFormat.getAudioFormats();
          sage.media.format.AudioFormat srcAudioFormat = null;
          for (int i = 0; i < srcAudioFormats.length; i++)
          {
            if (!srcAudioFormats[i].getFormatName().equals(sage.media.format.MediaFormat.DOLBY_HD) &&
                !srcAudioFormats[i].getFormatName().equals(sage.media.format.MediaFormat.DTS_HD) &&
                !srcAudioFormats[i].getFormatName().equals(sage.media.format.MediaFormat.DTS_MA))
            {
              srcAudioFormat = srcAudioFormats[i];
              break;
            }
          }

          // Find the FFMPEG audio format that has the same stream ID as our main audio format
          if (srcAudioFormat == null)
            srcAudioFormat = sourceFormat.getAudioFormat();
          String mainsrcid = srcAudioFormat.getId();
          boolean isAC3 = sage.media.format.MediaFormat.AC3.equals(srcAudioFormat.getFormatName());
          sage.media.format.AudioFormat af = null;
          if (mainsrcid != null)
          {
            sage.media.format.AudioFormat[] afs = ffFormat.getAudioFormats();
            for (int i = 0; i < afs.length; i++)
            {
              if (mainsrcid.equals(afs[i].getId()) ||
                  (isAC3 && mainsrcid.startsWith("bd-" + afs[i].getId())))
              {
                af = afs[i];
                break;
              }
            }
          }
          if (af == null)
            af = ffFormat.getAudioFormat();
          if (af != null && af.getOrderIndex() >= 0)
          {
            // 2.1.0003: when the surface-aware ranker picked a specific audio
            // stream (multi-audio selection by language + quality + native-decode
            // preference), use THAT stream's orderIndex instead of the legacy
            // getAudioFormat() result (which just picks the lowest orderIndex,
            // ignoring language/channels). Legacy sessions leave
            // httplsSurfaceAudioStreamIndex == -1, so the old code path runs.
            int audioMapIndex = (httplsSurfaceAudioStreamIndex >= 0)
                ? httplsSurfaceAudioStreamIndex : af.getOrderIndex();
            if (sage.Sage.DBG && httplsSurfaceAudioStreamIndex >= 0)
              System.out.println("FFMPEGTranscoder: 2.1.0003 surface audio -map override: "
                  + "legacy=" + af.getOrderIndex() + " surface=" + httplsSurfaceAudioStreamIndex);
            xcodeParamsVec.add("-map");
            xcodeParamsVec.add("0:" + vf.getOrderIndex());
            xcodeParamsVec.add("-map");
            xcodeParamsVec.add("0:" + audioMapIndex);
            if (embedSubtitleStreams && sourceHasSubtitleStreams)
            {
              xcodeParamsVec.add("-map");
              xcodeParamsVec.add("0:s?");
            }
            else if (embedSubtitleStreams && extractedCcSubtitleFile != null)
            {
              xcodeParamsVec.add("-map");
              xcodeParamsVec.add("1:0");
            }
            usedExplicitStreamMapping = true;
          }
        }
      }
    }

    if (embedSubtitleStreams && extractedCcSubtitleFile != null && !usedExplicitStreamMapping)
    {
      // A secondary subtitle input requires explicit mapping, otherwise ffmpeg
      // may not include the encoded A/V streams in the output.
      xcodeParamsVec.add("-map");
      xcodeParamsVec.add("0:v:0");
      xcodeParamsVec.add("-map");
      xcodeParamsVec.add("0:a:0?");
      xcodeParamsVec.add("-map");
      xcodeParamsVec.add("1:0");
      usedExplicitStreamMapping = true;
    }

    if (embedSubtitleStreams)
    {
      // Preserve subtitle streams when possible. MP4-family outputs require a
      // text subtitle codec, so use mov_text there. Uses the mux-target test
      // (not the filename-only isMp4FamilyOutput) so stdout-streamed MP4 remux
      // modes pick mov_text too instead of an invalid "copy".
      xcodeParamsVec.add("-c:s");
      xcodeParamsVec.add(isMp4FamilyMuxTarget() ? "mov_text" : "copy");
    }

    // NOTE: Don't use interlaced ME/DCT on MPEG4 content
    // Quicktime/iPod doesn't playback files with interlaced ME/DCT so we can't just go enabling it all the time
    if (sourceFormat != null && sourceFormat.getVideoFormat() != null && sourceFormat.getVideoFormat().isInterlaced() &&
        "mpeg2video".equals(videoCodec))
    {
      xcodeParamsVec.add("-flags");
      xcodeParamsVec.add("ildct");
      xcodeParamsVec.add("-flags");
      xcodeParamsVec.add("ilme");
    }

    // Check for multi-pass encoding
    if (pass != 0)
    {
      xcodeParamsVec.add("-pass");
      xcodeParamsVec.add(Integer.toString(pass));
      xcodeParamsVec.add("-passlogfile");
      xcodeParamsVec.add("multipassxcode");
    }

    // We only want to use these sync parameters if we're doing dynamic adjustment placeshifting
    // Although, I'm pretty sure we want to switch to the other set of params, but we need more testing before we do that
    // NOTE: 10/16/06 - the other set of params totally screw up our A/V sync for fixed rate placeshifting @ 15fps !!!!
    // FFMPEG 5+: -vsync N has been removed in favor of -fps_mode <mode>, and -async N
    // has been removed in favor of -af aresample=async=N. Map the legacy values:
    //   -vsync 0 -> -fps_mode passthrough
    //   -vsync 1 -> -fps_mode cfr
    //
    // Modern ffmpeg (6.1+) additionally refuses `-af aresample=async=N` when the
    // audio output is `-acodec copy` — it exits with "Filtering and streamcopy
    // cannot be used together" / "Error opening output files: Invalid argument"
    // before serving a single byte, which HTTPLSServer respawns in a tight loop.
    // Older ffmpeg silently ignored the filter in copy mode, so gating on the
    // copy check restores the old effective behavior. If a source genuinely
    // needs aresample drift correction, force it onto the audio re-encode
    // branch above rather than trying to filter through a copy.
    // Audio EQ v1 (gated solely by an explicit client request): if a server-side
    // EQ plan is active for this session, disqualify a plain -acodec copy BEFORE
    // the copy check below so the -af construction that follows naturally takes
    // its normal (non-copy) branch. No-op unless a buildable plan was set on
    // this instance -- see isServerAudioEqActive().
    maybeDisqualifyAudioCopyForServerEq(xcodeParamsVec);
    boolean audioIsCopy = isAudioCopySelected(xcodeParamsVec);
    boolean videoIsCopy = isVideoCopySelected(xcodeParamsVec);
    // MP4-family + AAC stream-copy: AAC from an ADTS-framed source (MPEG-TS)
    // must be reframed to ASC via aac_adtstoasc or the mp4 muxer rejects the
    // header ("Malformed AAC bitstream detected") and aborts the whole remux,
    // killing the copied video too. See needsAacAdtstoAscBsf() -- the mux-target
    // test covers both file and stdout-streamed MP4 (browserhd_remux/copyv).
    if (needsAacAdtstoAscBsf(xcodeParamsVec))
    {
      if (Sage.DBG) System.out.println("FFMPEGTranscoder: adding -bsf:a aac_adtstoasc "
          + "(AAC stream-copy into MP4-family container)");
      xcodeParamsVec.add("-bsf:a");
      xcodeParamsVec.add("aac_adtstoasc");
    }
    if (dynamicRateAdjust || (isMpeg4Codec && outputFile == null))
    {
      xcodeParamsVec.add("-fps_mode");
      // For AVI source files we need to allow video frame dropping for it to get proper initial sync if there was
      // also a seek
      // NARFLEX: 4/2/09 - using 'vsync 1' fixes a new bug where we have an error if we try to start transcoding in the middle
      // of an MKV file; so we're adding that to this case
      // NARFLEX: 10/29/10 - For frame decimation, we need to do -vsync 1 or we won't be able to drop frames for the h264 encoder properly
      // FFmpeg 7.x: -fps_mode passthrough is incompatible with an explicit -r, so when a frame
      // rate was specified (always true for the mpeg4 placeshifter path) we must use cfr.
      boolean hasExplicitFps = xcodeParamsVec.contains("-r");
      if (hasExplicitFps || httplsMode || (transcodeStartSeekTime != 0 && sourceFormat != null && (sage.media.format.MediaFormat.AVI.equals(sourceFormat.getFormatName()) ||
          sage.media.format.MediaFormat.MATROSKA.equals(sourceFormat.getFormatName()))))
        xcodeParamsVec.add("cfr");
      else
        xcodeParamsVec.add("passthrough");
      if (!audioIsCopy)
      {
        boolean isAc4Source = sourceFormat != null &&
            sage.media.format.MediaFormat.AC4.equals(sourceFormat.getPrimaryAudioFormat());
        int acIdx = xcodeParamsVec.indexOf("-ac");
        boolean isDownmixToStereo = acIdx >= 0 && acIdx + 1 < xcodeParamsVec.size() &&
            "2".equals(xcodeParamsVec.get(acIdx + 1));
        xcodeParamsVec.add("-af");
        xcodeParamsVec.add(buildAudioResampleFilter(isAc4Source, isDownmixToStereo));
      }
      else if (Sage.DBG)
      {
        System.out.println("FFMPEGTranscoder: skipping -af aresample=async (audio is -acodec copy)");
      }
    }
    else //if (xcodeParams.indexOf("-f mp4") != -1 || xcodeParams.indexOf("-f 3gp") != -1 || xcodeParams.indexOf("-f psp") != -1)
    {
      xcodeParamsVec.add("-fps_mode");
      // A stream-copied video is never decoded, so CFR normalization is
      // impossible and (on ffmpeg 6/7) rewrites the copied PTS/DTS instead of
      // dropping/duplicating frames -> visible tearing and timeslice hopping on
      // the client (seen on the ATSC-3 HEVC live-push remux). Preserve the
      // source cadence with passthrough for the copy path. An explicit -r is
      // incompatible with passthrough on ffmpeg 7, so only switch when none is
      // present (the copy remux never sets one).
      boolean copyKeepsCadence = videoIsCopy && !xcodeParamsVec.contains("-r");
      xcodeParamsVec.add(copyKeepsCadence ? "passthrough" : "cfr");
      if (!audioIsCopy)
      {
        boolean isAc4Source = sourceFormat != null &&
            sage.media.format.MediaFormat.AC4.equals(sourceFormat.getPrimaryAudioFormat());
        int acIdx = xcodeParamsVec.indexOf("-ac");
        boolean isDownmixToStereo = acIdx >= 0 && acIdx + 1 < xcodeParamsVec.size() &&
            "2".equals(xcodeParamsVec.get(acIdx + 1));
        xcodeParamsVec.add("-af");
        xcodeParamsVec.add(buildAudioResampleFilter(isAc4Source, isDownmixToStereo));
      }
      else if (Sage.DBG)
      {
        System.out.println("FFMPEGTranscoder: skipping -af aresample=async (audio is -acodec copy)");
      }
    }

    // Audio EQ v1: append (never replace) the server-EQ filtergraph onto
    // whatever -af the branches above already built (or add a fresh -af if
    // none exists). No-op unless the feature is active for this session.
    maybeAppendServerAudioEqFilter(xcodeParamsVec);

    if (Sage.DBG && "TRUE".equals(Sage.get("xcode_video_bitrate_stats", null)))
      xcodeParamsVec.add("-vstats");

    if (Sage.WINDOWS_OS && Sage.getBoolean("xcode_reduce_process_priority", true))
    {
      xcodeParamsVec.add("-priority");
      if (outputFile != null) // offline transcode
        xcodeParamsVec.add(Sage.get("xcode_process_priority_offline", "idle"));
      else
        xcodeParamsVec.add(Sage.get("xcode_process_priority_streaming", "belownormal"));
    }

    if (outputFile != null)
    {
      xcodeParamsVec.add(IOUtils.getLibAVFilenameString(outputFile.toString()));
      bufferOutput = false;
    }
    else if (fmp4Mode)
    {
      // Option A: ffmpeg's hls muxer writes finalized fMP4 files directly into
      // the session dir (init.mp4 + seg%d.m4s + index.m3u8). Java never reads
      // stdout on this path; HTTPLSServer serves the files. -start_number keeps
      // segment filenames aligned to absolute part numbers across seek-restarts,
      // and temp_file makes each .m4s appear atomically (rename) only when
      // complete, so a reader that sees seg<N>.m4s never observes a torn box.
      bufferOutput = false;
      fmp4StartSegment = segmentTargetCounter;
      java.io.File dir = fmp4OutputDir;
      // Timeline continuity across a seek-relaunch. A far-forward (or backward)
      // reseek restarts ffmpeg with an INPUT "-ss <t>", which by default rebases
      // the output timestamps to ~0. The hls fMP4 muxer then writes the new
      // segment's baseMediaDecodeTime (tfdt) starting from 0 -- so e.g. part #30
      // arrives on the wire carrying media time 0 instead of its true position.
      // The browser MSE SourceBuffer already holds earlier content on the real
      // timeline, so the reset segment overlaps it: the player rewinds/replays
      // and, once the overlap confuses the demuxer badly enough, fails the append
      // with DEMUXER_ERROR_COULD_NOT_PARSE. -start_number only renames the file;
      // it does NOT move the media clock. Re-anchor the muxer clock to the seek
      // position with -output_ts_offset so segment N's baseMediaDecodeTime lands
      // at N*segmentDur -- exactly where the client maps part N -- keeping the
      // MSE timeline monotonic and gap/overlap-free across the relaunch. Only on
      // a seek relaunch; the initial launch already starts at 0.
      if (transcodeStartSeekTime != 0)
      {
        xcodeParamsVec.add("-output_ts_offset");
        xcodeParamsVec.add(String.format(java.util.Locale.US, "%d.%03d",
            transcodeStartSeekTime / 1000, transcodeStartSeekTime % 1000));
      }
      // Anti-wedge experiment for a stalled/corrupt audio stream (borrowed from
      // the Matroska live-push precedent above). DISABLED BY DEFAULT on CMAF: a
      // 2-track fMP4 fragment must carry both video and audio every moof, but
      // max_interleave_delta 0 + flush_packets 1 let ffmpeg emit VIDEO-ONLY
      // fragments while the AC-3 decode is stalled, which the browser MSE rejects
      // (audio SourceBuffer underflow -> MEDIA_ERR / "video element error"). It is
      // the right cure for Matroska (single tolerant file) but wrong here. Left
      // behind a live-tunable flag so it can be re-enabled for diagnosis without a
      // rebuild; the real WAN fix is the server-goodput ramp-down in HTTPLSServer.
      if (Sage.getBoolean("httpls/fmp4_flush_uninterleaved", false))
      {
        xcodeParamsVec.add("-max_interleave_delta"); xcodeParamsVec.add("0");
        xcodeParamsVec.add("-flush_packets"); xcodeParamsVec.add("1");
      }
      xcodeParamsVec.add("-f"); xcodeParamsVec.add("hls");
      xcodeParamsVec.add("-hls_time"); xcodeParamsVec.add(Long.toString(segmentDur / 1000));
      xcodeParamsVec.add("-hls_segment_type"); xcodeParamsVec.add("fmp4");
      xcodeParamsVec.add("-hls_flags"); xcodeParamsVec.add("independent_segments+temp_file");
      xcodeParamsVec.add("-hls_list_size"); xcodeParamsVec.add("0");
      xcodeParamsVec.add("-start_number"); xcodeParamsVec.add(Integer.toString(segmentTargetCounter));
      xcodeParamsVec.add("-hls_segment_filename");
      xcodeParamsVec.add(IOUtils.getLibAVFilenameString(new java.io.File(dir, "seg%d.m4s").toString()));
      xcodeParamsVec.add("-hls_fmp4_init_filename"); xcodeParamsVec.add("init.mp4");
      xcodeParamsVec.add(IOUtils.getLibAVFilenameString(new java.io.File(dir, "index.m3u8").toString()));
    }
    else
      xcodeParamsVec.add("-");
    // Raw-cmdline mode: rebuild the argv to splice in the verbatim preset
    // ffmpeg arguments. We keep everything UP TO AND INCLUDING the "-i INPUT"
    // pair (so -y / -threads / -ss / activefile / stdinctrl / brokendts still
    // apply), then drop the legacy stream-walk output args entirely, then
    // append rawCmdlineGlobal (before -i) + the raw output args (after -i) +
    // the output filename. This is the "Item 6" raw cmdline plumbing —
    // see setTranscodeFormat()/MediaFormat.META_RAW_FFMPEG_CMDLINE.
    if (rawCmdlineMode)
    {
      java.util.ArrayList<String> rebuilt = new java.util.ArrayList<String>();
      int iIdx = -1;
      for (int k = 0; k < xcodeParamsVec.size(); k++)
      {
        Object o = xcodeParamsVec.get(k);
        String s = (o == null) ? "" : o.toString();
        rebuilt.add(s);
        if (iIdx == -1 && "-i".equals(s))
        {
          if (k + 1 < xcodeParamsVec.size())
            rebuilt.add(xcodeParamsVec.get(k + 1).toString());
          iIdx = rebuilt.size() - 2; // index of the "-i" token within rebuilt
          break;
        }
      }
      // Splice global pre-"-i" args (e.g. -hwaccel cuda -hwaccel_output_format cuda).
      if (rawCmdlineGlobal != null && rawCmdlineGlobal.length() > 0 && iIdx >= 0)
      {
        java.util.ArrayList<String> globals = new java.util.ArrayList<String>();
        java.util.StringTokenizer gt = new java.util.StringTokenizer(rawCmdlineGlobal);
        while (gt.hasMoreTokens()) globals.add(gt.nextToken());
        rebuilt.addAll(iIdx, globals);
      }
      // Append the raw post-"-i" args verbatim, no -b/-ab rewriting.
      java.util.StringTokenizer rt = new java.util.StringTokenizer(xcodeParams);
      while (rt.hasMoreTokens()) rebuilt.add(rt.nextToken());
      // Force the output muxer. SageTV writes the transcode to a .tmp file and
      // renames it on completion; ffmpeg cannot autodetect a muxer from .tmp,
      // so we always pass -f <container> from the preset's f= field. Skipped
      // only when no container was set (defensive — buildPresetSpec always
      // emits one).
      if (rawCmdlineContainer != null && rawCmdlineContainer.length() > 0)
      {
        rebuilt.add("-f");
        rebuilt.add(rawCmdlineContainer);
      }
      // Output filename (or stdout sentinel for streaming — raw mode is
      // intended for offline though, where outputFile is always set).
      rebuilt.add(outputFile != null
          ? IOUtils.getLibAVFilenameString(outputFile.toString()) : "-");
      xcodeParamsVec = rebuilt;
    }
    // AC-4 source audio override: when the source is AC-4 and the consuming
    // client has declared its preference (eac3 vs ac3 etc.), rewrite the audio
    // codec the profile picked. Keeps the rest of the profile (mux, video,
    // sync, channels) intact and avoids forking every transcode profile.
    maybeOverrideAc4AudioCodec(xcodeParamsVec);
    maybeOverrideSurfaceAudio(xcodeParamsVec);
    // Downmix any layout wider than the chosen AC-3-family encoder can emit
    // (7.1 -> 5.1); otherwise ffmpeg aborts and the client sees "no signal".
    clampAudioChannelsToEncoder(xcodeParamsVec);
    maybeStripInapplicableHvc1Tag(xcodeParamsVec);
    // GPU enhancement (upscale/deinterlace) — the LAST argv edit, so it operates
    // on the final copy-family command and can never be undone by an audio
    // override above. No-op unless enhancement is live AND admitted; audio is
    // never touched here.
    maybeApplyGpuEnhancement(xcodeParamsVec);
    // Launch-time bitrate policy for the plain (un-enhanced) browserhd re-encode:
    // replace the static profile cap with a per-session, resolution/bandwidth
    // aware ceiling. No-op when enhancement claimed the bitrate just above, or
    // for any non-browserhd mode.
    applyBrowserHdRateCap(xcodeParamsVec);
    // Native placeshifter copy-push hygiene: drop the spurious NVDEC decoder on
    // a video-copy output and re-base timestamps so the client media clock can't
    // rewind mid-stream (freeze). Last argv edit, so it sees the final codecs.
    maybeFixNativeCopyPushCommand(xcodeParamsVec);
    String[] xcodeParamArray = (String[]) xcodeParamsVec.toArray(Pooler.EMPTY_STRING_ARRAY);
    // Always log the FFmpeg command line for diagnosability (disable with xcode_cmdline_debug=FALSE)
    if (Sage.DBG && !"FALSE".equals(Sage.get("xcode_cmdline_debug", "TRUE"))) System.out.println("Executing xcoding process with args: " + java.util.Arrays.asList(xcodeParamArray));
    ProcessBuilder xcodePb = new ProcessBuilder(xcodeParamArray);
    Sage.applyTimeZoneToProcessBuilder(xcodePb);
    // External-process enhancement (upscale worker): try the three-process
    // pipeline first. On any spawn/handshake failure it returns null and we fall
    // through to the single-process command rewriteArgv already built. That
    // fallback deinterlaces/re-encodes but does NOT upscale (source resolution) --
    // the core ships no built-in playback upscaler -- so the client always gets a
    // stream even if the worker never comes up.
    externalEnhanceActive = false;
    Process externalEncode = null;
    if (pendingExternalPipeline != null)
    {
      try { externalEncode = launchExternalEnhancePipeline(pendingExternalPipeline, pendingExternalWorkerArgv, pendingExternalWarmProcess); }
      catch (Throwable t)
      {
        if (Sage.DBG) System.out.println("GPU_ENHANCE external pipeline launch failed, "
            + "falling back to single-process command: " + t);
      }
      pendingExternalPipeline = null;
      pendingExternalWorkerArgv = null;
      pendingExternalWarmProcess = null;
      if (externalEncode == null)
      {
        teardownExternalEnhance();
        // The upscale worker never came up, so this session is now a plain
        // (un-enhanced) transcode. It must NOT keep holding the GPU enhance
        // concurrency slot: otherwise a subsequent re-negotiation (e.g. the client
        // moves to a higher-resolution display and re-OPENs) is wrongly denied with
        // "concurrency ceiling 1 (active 1)" until this dead session finally ends.
        // The fallback command needs neither the governor permit nor the scale
        // lease, so return both now. Both releases are idempotent, so stopTranscode()
        // unwinding later is harmless.
        if (enhanceSessionId != null)
        {
          try { sage.enhance.GpuGovernor.getInstance().release(enhanceSessionId); }
          catch (Throwable ignore) {}
          if (Sage.DBG) System.out.println("GPU_ENHANCE external pipeline abandoned; "
              + "released governor permit for " + enhanceSessionId
              + " (falling back to plain transcode)");
          enhanceSessionId = null;
        }
        releaseEnhanceScaleLease();
      }
    }
    if (externalEncode != null)
    {
      xcodeProcess = externalEncode;
      externalEnhanceActive = true;
    }
    else
    {
      xcodeProcess = xcodePb.start();
    }
    // Windows can't express priority reduction as a command prefix, so the
    // nice/ionice block above is POSIX-only. Apply the equivalent by PID here so
    // Windows hosts aren't left running transcodes at normal priority against
    // in-progress recordings.
    ProcessPriority.reduce(xcodeProcess);
    // Track this child so a JVM shutdown (e.g. stopsage during a deploy)
    // while it's still streaming reaps it instead of orphaning a phantom
    // ffmpeg to PID 1. Cleared again in stopTranscode() on the normal path.
    registerLiveChild(xcodeProcess);
    // We open up the error stream and consume that for status info. The transcoded data is consumed by reading
    // from stdout.
    xcodeDone = false;
    if (xcodeBuffer == null)
    {
      // Don't use properties for these because it leads to major inconsistencies between systems that are quite difficult to diagnose
      if (enhanceSessionId != null)
      {
        // Enhanced sessions emit a high-bitrate (up to ~40 Mbps) HEVC stream that is
        // drained by the MediaServer HTTP pull path (SIZE/READ round-trips), which
        // has much higher per-chunk latency than the in-process native reader. The
        // 128 KB copy-family default ring fills in tens of milliseconds and wedges
        // the encoder between HTTP reads, so size the ring to hold ~1 second of
        // output (clamped to a sane [1 MB, 8 MB] window) using 64 KB slots.
        final int chunk = 65536;
        long kbps = currVideoBitrateKbps > 0 ? currVideoBitrateKbps : 20000L;
        long targetBytes = Math.max(1L << 20, Math.min(8L << 20, kbps * 1000L / 8L));
        int count = Math.max(16, (int) ((targetBytes + chunk - 1) / chunk));
        xcodeBuffer = new byte[count][chunk];
        if (Sage.DBG) System.out.println("GPU_ENHANCE ring sized for " + kbps + " kbps: "
            + count + " x " + chunk + " = " + (count * (long) chunk) + " bytes");
      }
      else if (bufferOutput)
      {
        // MediaServer HTTP pull path (e.g. browserhd MSE delivery). The client
        // issues large random-access READs against this ring (msproxy reads up to
        // 524288 bytes per request) while the XcodeDataConsumer thread concurrently
        // fills it. currVideoBitrateKbps is left at the ffmpeg default (~200) on the
        // browserhd path, so it previously fell through to the 32 x 4096 = 128 KB
        // ring below -- far SMALLER than a single client READ. A 512 KB read then
        // laps the 128 KB ring ~4x, reading slots the consumer is actively
        // recycling, so the bytes placed on the wire are shifted/torn fMP4 (a lost
        // moof header) and the browser MSE stack rejects them
        // (CHUNK_DEMUXER_ERROR_APPEND_FAILED / decode freeze). Proven on the wire by
        // a server-side tcpdump: corruption began exactly at a fragment boundary
        // inside the oversized read. The ring MUST be several times larger than the
        // largest single client read so the reader can never lap it and there is
        // always free margin between reader and writer. Use a fixed, generous 4 MB
        // ring (64 x 64 KB); the enhance path above is sized separately for its much
        // higher bitrate.
        final int chunk = 65536;
        final int count = 64; // 64 * 64 KB = 4 MB, >> max client READ (512 KB)
        xcodeBuffer = new byte[count][chunk];
        if (Sage.DBG) System.out.println("browserhd/pull ring sized: "
            + count + " x " + chunk + " = " + (count * chunk) + " bytes");
      }
      else
        xcodeBuffer = new byte[32][currVideoBitrateKbps >= 300 ? 16384 : 4096];
    }
    xcodeStderrThread = new Thread("XcodeStderrConsumer")
    {
      public void run()
      {
        try
        {
          java.io.InputStream buf = xcodeProcess.getErrorStream();
          StringBuffer sb = new StringBuffer();
          long nextSegmentTime = segmentDur;
          if (httplsMode)
            lastXcodeStreamTime = 0;
          do
          {
            int c = buf.read();
            if (c == -1)
              break;
            else
              sb.append((char) c);
            if (c == '\n')
            {
              if (XCODE_DEBUG) System.out.println(sb.toString().trim());
              sb.setLength(0);
            }
            else if (c == '\r')
            {
              // Live ffmpeg progress line: the child is alive and producing, so
              // refresh the governor heartbeat that keeps this session out of the
              // idle reaper. No-op when this is not an enhanced session.
              sage.enhance.GpuGovernor.getInstance().heartbeat(FFMPEGTranscoder.this.enhanceSessionId);
              // Parse to get the byte position for the specified time
              if (XCODE_DEBUG) System.out.println(sb.toString().trim());
              int frameIdx = sb.indexOf("frame=");
              int fpsIdx = sb.indexOf("fps=");
              int sizeIdx = sb.indexOf("size=");
              int timeIdx = sb.indexOf("time=");
              int bitrateIdx = sb.indexOf("bitrate=");

              // Locate the end of the "size=" numeric field by its unit token.
              // Modern FFmpeg (6.1+, e.g. N-124561 / Lavc62) reports the byte
              // count with binary IEC units "KiB"/"MiB"/"GiB"; older builds used
              // "kB". The legacy code only searched for "kB", so on modern ffmpeg
              // indexOf returned -1, this whole block was skipped, lastXcodeStreamTime
              // never advanced, HLS segments never closed, and PWA/iOS playback
              // hung until the client disconnected. Detect whichever unit is
              // present and scale to bytes accordingly ("kB" was always really KiB).
              int unitIdx = -1;
              long sizeUnitMult = 1024L;
              if (sizeIdx != -1)
              {
                int kibIdx = sb.indexOf("KiB", sizeIdx);
                int mibIdx = sb.indexOf("MiB", sizeIdx);
                int gibIdx = sb.indexOf("GiB", sizeIdx);
                int legacyKbIdx = sb.indexOf("kB", sizeIdx);
                if (kibIdx != -1) { unitIdx = kibIdx; sizeUnitMult = 1024L; }
                else if (mibIdx != -1) { unitIdx = mibIdx; sizeUnitMult = 1024L * 1024L; }
                else if (gibIdx != -1) { unitIdx = gibIdx; sizeUnitMult = 1024L * 1024L * 1024L; }
                else if (legacyKbIdx != -1) { unitIdx = legacyKbIdx; sizeUnitMult = 1024L; }
              }

              if (sizeIdx != -1 && timeIdx != -1 && unitIdx != -1 && bitrateIdx != -1)
              {
                String frameStr = "";
                String sizeStr = sb.substring(sizeIdx + 5, unitIdx).trim();
                String timeStr = sb.substring(timeIdx + 5, bitrateIdx).trim();
                
                if (sizeStr.indexOf('.') == -1)
                {
                  try
                  {
                    // FFmpeg reports "time=" as an HH:MM:SS.ms timecode (e.g.
                    // "00:00:04.26"); support that plus a bare decimal-seconds
                    // value and "N/A" for robustness. Double.parseDouble alone
                    // throws on the colon-delimited timecode.
                    double time;
                    if (timeStr.startsWith("N/A"))
                    {
                      time = 0;
                    }
                    else if (timeStr.indexOf(':') == -1)
                    {
                      time = Double.parseDouble(timeStr);
                    }
                    else
                    {
                      String[] timeParts = timeStr.split(":");
                      time = 0;
                      for (int ti = 0; ti < timeParts.length; ti++)
                        time = time * 60 + Double.parseDouble(timeParts[ti]);
                    }
                    
                    //Fallback to using frame count to determin time if the time is < 1
                    if(time > 1)
                    {
                      lastXcodeStreamTime = Math.round(1000 * time);  
                    }
                    else
                    {
                      if (XCODE_DEBUG) System.out.println("Using framecount to calculate transcoder progress");
                      
                      if(frameIdx != -1)
                      {
                        frameStr = sb.substring(frameIdx + 6, fpsIdx).trim();
                      }
                      
                      int frame = Integer.parseInt(frameStr);
                      float fps = FFMPEGTranscoder.this.sourceFormat.getVideoFormat().getFps();
                      
                      //Determine time from frames
                      lastXcodeStreamTime = Math.round(1000 * (frame / fps));
                    }
                    
                    lastXcodeStreamPosition = Long.parseLong(sizeStr) * sizeUnitMult;
                    
                  }
                  catch (NumberFormatException e)
                  {
                    System.out.println("ERROR parsing transcoder status of:" + e);
                  }
                }
              }
              if (httplsMode)
              {
                if (lastXcodeStreamTime >= nextSegmentTime)
                {
                  synchronized (segFileSyncLock)
                  {
                    segmentTargetCounter++;
                    if (XCODE_DEBUG) System.out.println("Stderr reader has read a timecode that indicates end of segment, increment counter, target=" +
                        nextSegmentTime + " read=" + lastXcodeStreamTime + " newCounterValue=" + segmentTargetCounter);
                    segFileSyncLock.notifyAll();
                  }
                  nextSegmentTime += segmentDur;
                }
              }
              sb.setLength(0);
            }
          }while (true);
          buf.close();
        }
        catch (Exception e){}
        finally
        {
          xcodeDone = true;
          // If the ffmpeg child has exited on its own (clean EOF or a crash) and
          // this was an enhanced session, return its GPU reservation immediately
          // instead of waiting for the client to notice and disconnect. This is
          // the self-exit backstop to stopTranscode() (which the connection-close
          // path calls); both are idempotent, and the !isAlive() guard means a
          // still-running child is never touched.
          try
          {
            String esid = FFMPEGTranscoder.this.enhanceSessionId;
            if (esid != null && (xcodeProcess == null || !xcodeProcess.isAlive()))
            {
              sage.enhance.GpuGovernor.getInstance().release(esid);
              FFMPEGTranscoder.this.enhanceSessionId = null;
            }
          }
          catch (Throwable ignore) {}
        }
      }
    };
    xcodeStderrThread.setDaemon(true);
    xcodeStderrThread.start();
    // Reset the fill counter and advance the writer generation atomically so any
    // still-draining writer from a prior generation (whose join() may have timed
    // out) can observe the change and retire instead of corrupting the reused ring.
    synchronized (xcodeSyncLock)
    {
      numFilledXcodeBuffers = 0;
      xcodeStdoutGeneration++;
      // Wake any prior-generation writer parked in wait() so it re-checks the
      // generation and retires immediately instead of after its wait timeout.
      xcodeSyncLock.notifyAll();
    }
    final int myXcodeGen = xcodeStdoutGeneration;
    xcodeStdout = xcodeProcess.getInputStream();
    forciblyStopped = false;
    if (bufferOutput)
    {
      openSpillIfEnabled();
      xcodeStdoutThread = new Thread("XcodeDataConsumer")
      {
        public void run()
        {
          try
          {
            do
            {
              int currBuffNum;
              int currBufReadPos = 0;
              synchronized (xcodeSyncLock)
              {
                // A newer startTranscode() (seek/re-open restart) superseded this
                // writer; retire it so only one writer ever fills the reused ring.
                if (xcodeStdoutGeneration != myXcodeGen)
                  return;
                // Backpressure: block while the ring is at (or, defensively, over)
                // capacity. MUST be >= not ==: if the count is ever nudged past
                // length by a restart race, an == check never matches again and the
                // writer laps the reader unbounded (numFilled was seen climbing to
                // ~100 in a 64-slot ring -> torn fMP4 -> CHUNK_DEMUXER_ERROR). >=
                // re-blocks and self-corrects.
                if (numFilledXcodeBuffers >= xcodeBuffer.length && !xcodeDone)
                {
                  if (XCODE_DEBUG) System.out.println("Waiting for transcode buffer to become available...");
                  try
                  {
                    xcodeSyncLock.wait(100);
                  }
                  catch (InterruptedException e){}
                  continue;
                }
                currBuffNum = (xcodeBufferBaseNum + numFilledXcodeBuffers) % xcodeBuffer.length;
              }
              int leftToRead = xcodeBuffer[currBuffNum].length;
              int numRead;
              do
              {
                numRead = xcodeStdout.read(xcodeBuffer[currBuffNum], xcodeBuffer[currBuffNum].length - leftToRead, leftToRead);
                if (XCODE_DEBUG_IO) System.out.println("Read " + numRead + " bytes from transcoder");
                leftToRead -= numRead;
              } while (numRead != -1 && leftToRead > 0);
              if (numRead == -1)
              {
                // Only the current generation's real EOF marks the stream done; a
                // retired writer's old-pipe EOF must not touch the new generation.
                if (xcodeStdoutGeneration == myXcodeGen)
                  xcodeDone = true;
                break;
              }
              else
              {
                // If a restart superseded us while we were blocked in read() above,
                // the bytes we just read belong to the retired generation's pipe.
                // Discard them and retire -- never advertise or spill them onto the
                // new generation's ring.
                if (xcodeStdoutGeneration != myXcodeGen)
                  return;
                if (seekableSpill)
                {
                  // Mirror the just-filled chunk into the circular spill BEFORE
                  // advertising it via xcodeBufferVirtualSize, so a behind-window
                  // reader never sees a byte count that isn't yet on disk. On any
                  // spill IO failure, disable the spill and keep streaming from the
                  // ring (never break the live transcode for a history-buffer error).
                  try
                  {
                    spillWriteCircular(xcodeSpillChannel, spillCapBytes,
                        xcodeBuffer[currBuffNum], 0, xcodeBuffer[currBuffNum].length, xcodeBufferVirtualSize);
                  }
                  catch (Exception se)
                  {
                    if (Sage.DBG) System.out.println("Xcode spill write failed; disabling spill: " + se);
                    closeSpillQuietly();
                    seekableSpill = false;
                  }
                }
                synchronized (xcodeSyncLock)
                {
                  numFilledXcodeBuffers++;
                  xcodeBufferVirtualSize += xcodeBuffer[currBuffNum].length;
                  if (XCODE_DEBUG_IO) System.out.println("Number of transcode buffers filled=" + numFilledXcodeBuffers
                      + " virtXcodedBytes=" + xcodeBufferVirtualSize);
                }
              }
            }while (true);
          }
          catch (Exception e){}
          finally
          {
            // Only the current generation's writer may signal end-of-stream. A
            // retired writer (superseded by a seek/re-open restart) must leave the
            // new generation's xcodeDone untouched, or it would prematurely mark a
            // freshly started stream as finished.
            if (xcodeStdoutGeneration == myXcodeGen)
              xcodeDone = true;
          }
        }
      };
      xcodeStdoutThread.setDaemon(true);
      xcodeStdoutThread.start();
    }
    else if (httplsMode && !fmp4Mode)
    {
      for (int i = 0; i < segmentData.length; i++)
      {
        segmentData[i].state = SEGMENT_FREE;
        segmentData[i].num = -1;
      }
      segmentData[0].state = SEGMENT_FILLING;
      segmentData[0].num = segmentTargetCounter;
      xcodeStdoutThread = new Thread("XcodeDataConsumer")
      {
        public void run()
        {
          byte[] readBuf;
          if (currVideoBitrateKbps >= 1000)
            readBuf = new byte[32768];
          else
            readBuf = new byte[currVideoBitrateKbps >= 300 ? 16384 : 4096];
          int lastDataIdx = -1;
          java.io.OutputStream fos = null;
          SegmentFileData currSegData = null;
          try
          {
            // First we need to have a segment file we can write to
            lastDataIdx = 0;
            currSegData = segmentData[0];
            if (XCODE_DEBUG) System.out.println("Output consumer selected initial segment buffer #" + lastDataIdx + " for writing of part #" + segmentTargetCounter);
            fos = new java.io.BufferedOutputStream(new java.io.FileOutputStream(currSegData.file));
            int numRead;
            do
            {
              numRead = xcodeStdout.read(readBuf);
              if (XCODE_DEBUG_IO) System.out.println("Read " + numRead + " bytes from transcoder");
              fos.write(readBuf, 0, numRead);
              synchronized (segFileSyncLock)
              {
                if (currSegData.num != segmentTargetCounter)
                {
                  if (XCODE_DEBUG) System.out.println("Finished writing to current segment file buffer #" + currSegData.num + " for part #" +
                      currSegData.num + ", closing file and moving on");
                  fos.close();
                  fos = null;
                  currSegData.state = SEGMENT_FILLED;
                  // Move to the next segment now
                  lastDataIdx = (lastDataIdx + 1) % segmentData.length;
                  // See if our target is free
                  while (segmentData[lastDataIdx].state != SEGMENT_FREE && segmentData[lastDataIdx].state != SEGMENT_CONSUMED && !xcodeDone)
                  {
                    // Wait until it's free or the xcoder is stopped'
                    if (XCODE_DEBUG) System.out.println("Waiting for segment file buffer to become available...");
                    try
                    {
                      segFileSyncLock.wait(500);
                    }
                    catch (InterruptedException e){}
                  }
                  if (xcodeDone)
                    return;
                  if (XCODE_DEBUG) System.out.println("Output consumer selected segment buffer #" + lastDataIdx + " for writing of part #" + segmentTargetCounter);
                  currSegData = segmentData[lastDataIdx];
                  currSegData.state = SEGMENT_FILLING;
                  currSegData.num = segmentTargetCounter;
                  fos = new java.io.BufferedOutputStream(new java.io.FileOutputStream(currSegData.file));
                  segFileSyncLock.notifyAll();
                }
              }
            } while (numRead != -1 && !xcodeDone);
            if (numRead == -1 || xcodeDone)
            {
              xcodeDone = true;
            }
          }
          catch (Exception e){}
          finally
          {
            xcodeDone = true;
            if (fos != null)
            {
              try{fos.close();}catch(Exception e){}
              fos = null;
            }
            if (!forciblyStopped && currSegData != null && currSegData.state == SEGMENT_FILLING)
            {
              synchronized (segFileSyncLock)
              {
                // Mark our last buffer as filled because we stopped due to natural causes, not a reseek or kill
                currSegData.state = SEGMENT_FILLED;
                segFileSyncLock.notifyAll();
              }
            }
          }
        }
      };
      xcodeStdoutThread.setDaemon(true);
      xcodeStdoutThread.start();
    }

    // The external-process encode stage's stdin is the raw-frame pipe fed by the
    // worker pump, NOT the SageTV -stdinctrl channel. Leave xcodeStdin null so the
    // 'inactivefile'/'videorateadapt' control writes are skipped (all guarded on
    // xcodeStdin != null) rather than corrupting the frame stream.
    xcodeStdin = externalEnhanceActive ? null : xcodeProcess.getOutputStream();
    //try{Thread.sleep(Sage.getInt("media_server/xcode_start_delay", 1000));}catch (Exception e){}
  }

  /**
   * Given a string like, '1280x720' it will return a 2 element array where element 0 is width and element 1 is height.
   * If the string is unparseable, then it will return the defaults.
   * If the string is 'original' it will attempt to get the size from the original video stream.
   *
   * @param xcodeWxH
   * @param defWidth
   * @param defHeight
   * @return
   */
  int[] parseFrameSize(String xcodeWxH, int defWidth, int defHeight)
  {
    int size[] = new int[] {defWidth, defHeight};
    if (xcodeWxH == null)
    {
      return size;
    }

    xcodeWxH = xcodeWxH.toLowerCase();

    // if we pass 'original' then try to use the original size of the video
    if ("original".equals(xcodeWxH))
    {
      if (sourceFormat!=null && sourceFormat.getVideoFormat()!=null)
      {
        size[0] = sourceFormat.getVideoFormat().getWidth();
        size[1] = sourceFormat.getVideoFormat().getHeight();
        size[0] = (size[0]<=0) ? defWidth : size[0];
        size[1] = (size[1]<=0) ? defHeight : size[1];
        return size;
      }
      else
      {
        if (Sage.DBG)
          System.out.println("FFMpegTranscoder: parseFrameSize(): 'original' was passed but there isn't any video information.  Using defaults.");
        return size;
      }
    }

    // need to parse widthxheight, ie, 1280x720
    String parts[] = xcodeWxH.split("x");
    if (parts.length != 2)
    {
      if (Sage.DBG)
        System.out.println("FFMpegTranscoder: parseFrameSize(): Invalid xcode size option "+xcodeWxH+" (should be widthxheight, eg, 1280x720)");
      return size;
    }

    int w,h;
    try
    {
      w = Integer.parseInt(parts[0].trim());
    }
    catch (Throwable t)
    {
      if (Sage.DBG)
        System.out.println("FFMpegTranscoder: parseFrameSize(): Invalid xcode size option "+xcodeWxH+" (should be widthxheight, eg, 1280x720)");
      return size;
    }

    try
    {
      h = Integer.parseInt(parts[1].trim());
    }
    catch (Throwable t)
    {
      if (Sage.DBG)
        System.out.println("FFMpegTranscoder: parseFrameSize(): Invalid xcode size option "+xcodeWxH+" (should be widthxheight, eg, 1280x720)");
      return size;
    }

    // great, we have a valid height and width
    if (h>0 && w>0)
    {
      size[0]=w;
      size[1]=h;
    }

    return size;
  }

  private boolean shouldEmbedSubtitleStreams()
  {
    return outputFile != null && Sage.getBoolean("transcoder/embed_subtitles_in_output", true);
  }

  private java.io.File maybePrepareEmbeddedCcSubtitleFile(boolean embedSubtitleStreams, boolean sourceHasSubtitleStreams)
  {
    if (!embedSubtitleStreams || sourceHasSubtitleStreams || rawCmdlineMode) return null;
    java.io.File ccSrc = (captionSourceFile != null) ? captionSourceFile : currFile;
    if (ccSrc == null || !ccSrc.isFile()) return null;

    java.io.File sidecar = findExistingCaptionSidecar(ccSrc);
    if (sidecar != null)
    {
      if (Sage.DBG) System.out.println("FFMPEGTranscoder: using existing CC sidecar for embedding " + sidecar);
      return sidecar;
    }

    if (!Sage.getBoolean("transcoder/embed_cc_extract_fallback", true)) return null;

    java.io.File tmpSrt;
    try
    {
      tmpSrt = java.io.File.createTempFile("sagetv_embedcc_", ".srt");
    }
    catch (java.io.IOException e)
    {
      if (Sage.DBG) System.out.println("FFMPEGTranscoder: unable to create temp CC sidecar " + e);
      return null;
    }

    if (!extractEmbeddedCcToSrt(ccSrc, tmpSrt))
    {
      tmpSrt.delete();
      return null;
    }

    if (!tmpSrt.isFile() || tmpSrt.length() < 8)
    {
      tmpSrt.delete();
      return null;
    }

    preparedEmbeddedCcSubtitleFile = tmpSrt;
    if (Sage.DBG) System.out.println("FFMPEGTranscoder: extracted CC sidecar for embedding " + tmpSrt);
    return tmpSrt;
  }

  private java.io.File findExistingCaptionSidecar(java.io.File mediaFile)
  {
    String base = stripFileExtension(mediaFile.getAbsolutePath());
    if (base == null || base.length() == 0) return null;
    for (int i = 0; i < EMBED_CC_SIDECAR_SUFFIXES.length; i++)
    {
      java.io.File f = new java.io.File(base + EMBED_CC_SIDECAR_SUFFIXES[i]);
      if (f.isFile() && f.length() > 0) return f;
    }
    return null;
  }

  private boolean extractEmbeddedCcToSrt(java.io.File srcFile, java.io.File outSrt)
  {
    String ccextractor = Sage.get("caption_extraction/ccextractor_path", "ccextractor");
    if (isCommandAvailable(ccextractor))
    {
      java.util.ArrayList cmd = new java.util.ArrayList();
      cmd.add(ccextractor);
      String inFmt = ccextractorInputFlag(srcFile.getName());
      if (inFmt != null) cmd.add(inFmt);
      cmd.add("-out=srt");
      int extractSec = Sage.getInt("transcoder/embed_cc_extract_seconds", 0);
      if (extractSec > 0)
      {
        cmd.add("-endat");
        cmd.add(secondsToHms(extractSec));
      }
      cmd.add(srcFile.getAbsolutePath());
      cmd.add("-o");
      cmd.add(outSrt.getAbsolutePath());
      if (runCommandForCc(cmd, "ccextractor")) return true;
    }

    // This fallback's entire input is a lavfi filter graph, and the core ffmpeg is
    // built --disable-devices, so resolve a binary that actually has lavfi rather
    // than defaulting to the core one and failing with "Unknown input format".
    String ffmpeg = sage.captions.CaptionFfmpeg.resolveLavfi();
    if (ffmpeg == null)
    {
      if (Sage.DBG) System.out.println("FFMPEGTranscoder: no ffmpeg with a lavfi input device is" +
          " available, so embedded 608/708 caption extraction is skipped. Install ccextractor" +
          " (caption_extraction/ccextractor_path) to handle this properly.");
      return false;
    }
    java.util.ArrayList cmd = new java.util.ArrayList();
    cmd.add(ffmpeg);
    cmd.add("-hide_banner");
    cmd.add("-loglevel");
    cmd.add("error");
    cmd.add("-y");
    cmd.add("-f");
    cmd.add("lavfi");
    cmd.add("-i");
    cmd.add("movie=" + escapeForLavfi(srcFile.getAbsolutePath()) + "[out0+subcc]");
    cmd.add("-map");
    cmd.add("0:1");
    cmd.add("-c:s");
    cmd.add("srt");
    cmd.add("-f");
    cmd.add("srt");
    int extractSec = Sage.getInt("transcoder/embed_cc_extract_seconds", 0);
    if (extractSec > 0)
    {
      cmd.add("-t");
      cmd.add(Integer.toString(extractSec));
    }
    cmd.add(outSrt.getAbsolutePath());
    return runCommandForCc(cmd, "ffmpeg-subcc");
  }

  private boolean runCommandForCc(java.util.ArrayList cmd, String label)
  {
    if (Sage.DBG) System.out.println("FFMPEGTranscoder: running " + label + " command " + cmd);
    Process p = null;
    try
    {
      ProcessBuilder pb = new ProcessBuilder((java.util.List<String>) (java.util.List) cmd);
      Sage.applyTimeZoneToProcessBuilder(pb);
      pb.redirectErrorStream(true);
      p = pb.start();
      java.io.BufferedReader br = new java.io.BufferedReader(
          new java.io.InputStreamReader(p.getInputStream(), Sage.I18N_CHARSET));
      String line;
      StringBuffer out = new StringBuffer();
      while ((line = br.readLine()) != null)
      {
        if (out.length() < 4096) out.append(line).append('\n');
      }
      int rc = p.waitFor();
      if (rc != 0 && rc != 2)
      {
        if (Sage.DBG) System.out.println("FFMPEGTranscoder: " + label + " failed rc=" + rc + " output=" + out);
        return false;
      }
      return true;
    }
    catch (Throwable t)
    {
      if (Sage.DBG) System.out.println("FFMPEGTranscoder: " + label + " error=" + t);
      return false;
    }
    finally
    {
      if (p != null)
      {
        try { p.getInputStream().close(); } catch (Throwable t) {}
      }
    }
  }

  private static String stripFileExtension(String path)
  {
    if (path == null || path.length() == 0) return path;
    int dot = path.lastIndexOf('.');
    if (dot <= 0) return path;
    return path.substring(0, dot);
  }

  private static String ccextractorInputFlag(String fname)
  {
    String n = (fname == null) ? "" : fname.toLowerCase();
    if (n.endsWith(".ts") || n.endsWith(".m2ts")) return "-ts";
    if (n.endsWith(".mpg") || n.endsWith(".mpeg") || n.endsWith(".vob")) return "-ps";
    if (n.endsWith(".mp4") || n.endsWith(".m4v") || n.endsWith(".mov")) return "-mp4";
    if (n.endsWith(".mkv") || n.endsWith(".webm")) return "-mkv";
    if (n.endsWith(".wtv")) return "-wtv";
    return null;
  }

  private static String secondsToHms(int s)
  {
    int h = s / 3600;
    int m = (s % 3600) / 60;
    int sec = s % 60;
    return String.format("%02d:%02d:%02d", h, m, sec);
  }

  private static String escapeForLavfi(String path)
  {
    StringBuilder sb = new StringBuilder(path.length() + 16);
    for (int i = 0; i < path.length(); i++)
    {
      char c = path.charAt(i);
      if (c == '\\' || c == '\'' || c == ':' || c == ',' || c == '[' || c == ']' || c == ';')
        sb.append('\\');
      sb.append(c);
    }
    return sb.toString();
  }

  private static boolean isCommandAvailable(String binary)
  {
    if (binary == null || binary.length() == 0) return false;
    java.io.File f = new java.io.File(binary);
    if (f.isAbsolute()) return f.canExecute();
    String path = System.getenv("PATH");
    if (path == null) return false;
    String[] dirs = path.split(java.io.File.pathSeparator);
    for (int i = 0; i < dirs.length; i++)
    {
      java.io.File c = new java.io.File(dirs[i], binary);
      if (c.canExecute()) return true;
    }
    return false;
  }

  private void clearPreparedEmbeddedCcSubtitleFile()
  {
    java.io.File f = preparedEmbeddedCcSubtitleFile;
    preparedEmbeddedCcSubtitleFile = null;
    if (f != null)
    {
      try { f.delete(); } catch (Throwable t) {}
    }
  }

  // Parses an ffmpeg bitrate token that may carry a k/M/G suffix (e.g. "384k",
  // "8M") or be a bare integer. A bare integer is interpreted as kbps for
  // backward compatibility with SageTV's legacy transcode-quality strings
  // ("384" and "384k" both mean 384000 bits/sec). Returns bits/sec, or -1 if it
  // cannot be parsed.
  private static long parseBitrateToBps(String tok)
  {
    if (tok == null || tok.length() == 0) return -1;
    long mult = 1000L; // bare integer => kbps (legacy Sage convention)
    String num = tok;
    char last = tok.charAt(tok.length() - 1);
    if (last == 'k' || last == 'K') { mult = 1000L; num = tok.substring(0, tok.length() - 1); }
    else if (last == 'm' || last == 'M') { mult = 1000000L; num = tok.substring(0, tok.length() - 1); }
    else if (last == 'g' || last == 'G') { mult = 1000000000L; num = tok.substring(0, tok.length() - 1); }
    try
    {
      double v = Double.parseDouble(num);
      if (v <= 0) return -1;
      return (long)(v * mult);
    }
    catch (NumberFormatException e)
    {
      return -1;
    }
  }

  /**
   * The ffmpeg output muxer this transcode targets, lower-cased, or {@code null}
   * when it cannot be determined. This is the single source of truth for "what
   * container are we writing." It prefers an explicit {@code -f <fmt>} in the
   * verbatim preset ({@link #xcodeParams}) — which is how the streaming
   * pull-xcode modes ({@code browserhd_remux} / {@code browserhd_copyv}) select
   * fragmented MP4 while writing to stdout with {@code outputFile == null} — and
   * otherwise infers from the output filename extension for file transcodes that
   * let ffmpeg pick the muxer from the name.
   */
  String outputMuxFormat()
  {
    if (xcodeParams != null)
    {
      java.util.StringTokenizer st = new java.util.StringTokenizer(xcodeParams, " ");
      while (st.hasMoreTokens())
      {
        if ("-f".equals(st.nextToken()) && st.hasMoreTokens())
          return st.nextToken().toLowerCase();
      }
    }
    if (outputFile != null)
    {
      String name = outputFile.getName().toLowerCase();
      int dot = name.lastIndexOf('.');
      if (dot >= 0) return name.substring(dot + 1);
    }
    return null;
  }

  /**
   * True when the output muxer is an MP4-family container, regardless of whether
   * it is written to a file or streamed to stdout. Derived from the single
   * {@link #outputMuxFormat()} source of truth, so it correctly recognizes the
   * stdout-streamed browser remux modes that a filename-only check (the former
   * {@code isMp4FamilyOutput}) missed. This is the check to use for any
   * MP4-muxer-specific command requirement (e.g. the AAC {@code aac_adtstoasc}
   * bitstream filter, or {@code mov_text} subtitle codec).
   */
  boolean isMp4FamilyMuxTarget()
  {
    String f = outputMuxFormat();
    if (f == null) return false;
    return f.equals("mp4") || f.equals("m4v") || f.equals("mov")
        || f.equals("3gp") || f.equals("psp") || f.equals("ipod");
  }

  /**
   * True when an explicit {@code -bsf:a aac_adtstoasc} must be injected before
   * ffmpeg runs: the audio track is being stream-copied, the mux target is
   * MP4-family (file or stdout — see {@link #isMp4FamilyMuxTarget()}), the
   * source audio is AAC (ADTS-framed inside MPEG-TS), and no {@code
   * aac_adtstoasc} filter is already present. MP4 requires raw AAC with an
   * in-sample-entry {@code AudioSpecificConfig}, so a TS&rarr;MP4 remux of ADTS
   * AAC that omits the filter makes the mp4 muxer reject the header
   * ("Malformed AAC bitstream detected") and abort the entire remux — which
   * kills the copied video too, producing a black, silent player. The filter is
   * safe to always apply for AAC copy: already-ASC AAC (from MKV/MP4) passes
   * through unchanged. Gated on AAC only (the filter errors on non-AAC) and on
   * copy only (a re-encode emits ASC directly). Fixes {@code browserhd_remux}
   * of H.264/AAC MPEG-TS to fragmented MP4 for PWA/browser clients.
   */
  boolean needsAacAdtstoAscBsf(java.util.ArrayList xcodeParamsVec)
  {
    if (xcodeParamsVec == null) return false;
    return isAudioCopySelected(xcodeParamsVec)
        && isMp4FamilyMuxTarget()
        && sourceFormat != null
        && sourceFormat.getAudioFormat() != null
        && sage.media.format.MediaFormat.AAC.equals(sourceFormat.getAudioFormat().getFormatName())
        && !xcodeParamsVec.contains("aac_adtstoasc");
  }

  public void stopTranscode()
  {
    forciblyStopped = true;
    xcodeDone = true;
    if (XCODE_DEBUG) System.out.println("Destroying old transcode process...");
    if (xcodeProcess != null)
    {
      Process doomed = xcodeProcess;
      try
      {
        lastExitCode = doomed.exitValue();
      }
      catch (IllegalThreadStateException ise)
      {
        lastExitCode = -1;
      }
      // Escalate to SIGKILL if needed, and only drop it from the shutdown
      // reaper's registry once it is confirmed dead. Unregistering first (the
      // previous behavior) meant a child that ignored SIGTERM was orphaned
      // beyond the reach of both this method and the shutdown hook.
      boolean dead = terminateChildWithEscalation(doomed);
      if (dead)
        unregisterLiveChild(doomed);
    }
    xcodeProcess = null;
    // Tear down the external-process enhancement sub-stages (decode + worker), if
    // any, alongside the encode process handled above.
    teardownExternalEnhance();
    if (XCODE_DEBUG) System.out.println("Destroyed!");
    // Return any GPU-enhance capacity this session held, so VRAM/engine budget
    // is freed for the next admission the moment the stream ends.
    if (enhanceSessionId != null)
    {
      try { sage.enhance.GpuGovernor.getInstance().release(enhanceSessionId); }
      catch (Throwable ignore) {}
      enhanceSessionId = null;
    }
    // Return any specialized scale permit this session held (null for the
    // built-in scaler path).
    releaseEnhanceScaleLease();
    try
    {
      if (xcodeStderrThread != null)
      {
        xcodeStderrThread.join(2000);
        xcodeStderrThread = null;
        if (XCODE_DEBUG) System.out.println("Stderr consumer thread has terminated for xcoder");
      }
    }catch(InterruptedException e){}
    try
    {
      if (xcodeStdoutThread != null)
      {
        xcodeStdoutThread.join(2000);
        xcodeStdoutThread = null;
        if (XCODE_DEBUG) System.out.println("Stdout consumer thread has terminated for xcoder");
      }
    }catch(InterruptedException e){}
    try
    {
      if (xcodeStdout != null)
        xcodeStdout.close();
    }
    catch (java.io.IOException e){}
    xcodeStdout = null;
    try
    {
      if (xcodeStdin != null)
      {
        xcodeStdin.close();
      }
    }catch(java.io.IOException e){}
    xcodeStdin = null;

    // Delete any temporary segment files
    if (httplsMode && segmentData != null)
    {
      for (int i = 0; i < segmentData.length; i++)
        segmentData[i].file.delete();
    }

    // Option B: drop the seekable spill history for this generation.
    closeSpillQuietly();

    clearPreparedEmbeddedCcSubtitleFile();
  }

  /**
   * Signal the running ffmpeg child process (and any descendants) with SIGSTOP
   * so the OS suspends it without losing state. Used by Ministry when a tuner
   * starts recording and {@code transcoder/pause_during_recording} is enabled.
   * No-op on Windows or when no process is running. Returns true on success.
   */
  public boolean pauseForRecording()
  {
    if (Sage.WINDOWS_OS) return false;
    Process p = xcodeProcess;
    if (p == null || !p.isAlive()) return false;
    boolean ok = sendSignalToProcessTree(p, "STOP");
    if (ok) pausedForRecording = true;
    return ok;
  }

  /** Resume a previously paused transcode (SIGCONT). */
  public boolean resumeForRecording()
  {
    if (Sage.WINDOWS_OS) return false;
    Process p = xcodeProcess;
    if (p == null || !p.isAlive())
    {
      pausedForRecording = false;
      return false;
    }
    boolean ok = sendSignalToProcessTree(p, "CONT");
    if (ok) pausedForRecording = false;
    return ok;
  }

  public boolean isPausedForRecording() { return pausedForRecording; }

  private boolean sendSignalToProcessTree(Process p, String sig)
  {
    boolean any = false;
    try
    {
      long pid = p.pid();
      Runtime.getRuntime().exec(new String[]{"kill", "-" + sig, Long.toString(pid)});
      any = true;
    }
    catch (Throwable t) { if (XCODE_DEBUG) System.out.println("kill -" + sig + " on parent failed: " + t); }
    // The parent may be the `nice` wrapper or even `ionice` — also signal descendants
    // so the real ffmpeg process is reliably paused/resumed.
    try
    {
      p.descendants().forEach(ph -> {
        try { Runtime.getRuntime().exec(new String[]{"kill", "-" + sig, Long.toString(ph.pid())}); }
        catch (Throwable t) { if (XCODE_DEBUG) System.out.println("kill -" + sig + " on desc failed: " + t); }
      });
    }
    catch (Throwable t) { if (XCODE_DEBUG) System.out.println("descendants() failed: " + t); }
    return any;
  }

  private volatile boolean pausedForRecording;

  public void setEnableOutputBuffering(boolean x)
  {
    bufferOutput = x;
  }

  /**
   * GPU-accelerated DECODE hint emitted as {@code -hwaccel <api>} before
   * {@code -i} (e.g. "cuda" for NVDEC). Offloads HEVC/H.264 decode to the GPU,
   * which dominates start/seek latency for high-resolution sources. No
   * {@code -hwaccel_output_format} is set, so decoded frames download to system
   * memory and the existing CPU filter graph + encoder are unchanged; ffmpeg
   * falls back to software decode for any codec the GPU cannot handle. Set by
   * the pull-xcode path (MediaServer XCODE_SETUP). Empty/null = software decode.
   */
  public void setHwaccelDecode(String api)
  {
    hwaccelDecode = (api != null && api.trim().length() > 0) ? api.trim() : null;
  }
  protected String hwaccelDecode;

  /**
   * The enhancement tier requested for this transcode session, carried from the
   * MiniPlayer decision through the {@code XCODE_SETUP ;enhance=<tier>} param.
   * {@link sage.enhance.EnhancementTier#NONE} (the default) means no request and
   * the command is left byte-identical to today.
   */
  protected sage.enhance.EnhancementTier enhanceRequest = sage.enhance.EnhancementTier.NONE;
  /** Governor session id held while an enhanced session is admitted; null when none. */
  protected String enhanceSessionId;
  /** Specialized scale-provider permit held for this enhanced session; null for
   *  the built-in scaler path. Released exactly once alongside the governor
   *  session, so it is safe to release from multiple lifecycle unwinds. */
  protected sage.enhance.spi.ScaleGovernor.Lease enhanceScaleLease;

  /**
   * Record the enhancement tier the client's {@code XCODE_SETUP} asked for. The
   * request is only <i>honored</i> later in {@link #startTranscode()} via
   * {@link #maybeApplyGpuEnhancement}, which re-checks the live/dry-run interlock,
   * the copy-family mode gate, and the {@link sage.enhance.GpuGovernor} admission
   * (recording veto + capacity). Setting it here commits to nothing.
   */
  public void setEnhancementRequest(sage.enhance.EnhancementTier tier)
  {
    enhanceRequest = (tier == null) ? sage.enhance.EnhancementTier.NONE : tier;
  }

  /** New physical display sink width for a mid-session monitor move; 0 = none. */
  protected int liveSinkWidth = 0;
  /** New physical display sink height for a mid-session monitor move; 0 = none. */
  protected int liveSinkHeight = 0;

  /**
   * Record an updated physical display sink for THIS msproxy re-open, sent by a
   * PWA whose browser window was dragged to a different-resolution monitor
   * (Protocol 2.1 {@code ";sink=WxH"} on {@code XCODE_SETUP}). It only takes
   * effect when this session already carries an active {@code enhanceRequest};
   * {@link #maybeApplyGpuEnhancement} then re-clamps the granted tier to this
   * sink via {@link sage.enhance.EnhancementAdvisor#tierForLiveSink} -- upscaling
   * further on a larger panel, stepping down on a smaller one -- so the same
   * seek-reopen path rebuilds the plan at the new resolution. A non-positive or
   * out-of-range value is ignored by the caller and leaves the negotiated tier
   * untouched.
   */
  public void setLiveSinkResolution(int w, int h)
  {
    liveSinkWidth = (w > 0) ? w : 0;
    liveSinkHeight = (h > 0) ? h : 0;
  }

  public void setActiveFile(boolean x)
  {
    if (activeFile != x)
    {
      activeFile = x;
      if (xcodeStdin != null && !activeFile)
      {
        try
        {
          xcodeStdin.write("inactivefile\n".getBytes(Sage.BYTE_CHARSET));
          xcodeStdin.flush();
        }
        catch (Exception e)
        {
          System.out.println("Error writing to xcoder stdin of:" + e);
        }
      }
      else if (!activeFile && externalEnhanceActive && externalSourceCtrl)
      {
        // External GPU-enhance pipeline: xcodeStdin is null because the encode
        // stage's stdin is the raw-frame pipe. The -stdinctrl control channel
        // lives on the source-reading stages (decode + audio sidecar) instead, so
        // route the boundary 'inactivefile' there. That stops their -follow ->
        // source EOF -> pipeline drains -> EOS, so VideoFrame's live rollover fires
        // for enhanced sessions just as it does for a single-process transcode.
        writeExternalInactiveFile();
      }
    }
  }

  /** Deliver 'inactivefile' to the external enhance source stages (decode + audio
   *  sidecar) at a program boundary. Best-effort and fully guarded: a null or
   *  already-exited stage is skipped, so this never affects the roll (VideoFrame
   *  seams on the INACTIVE_FILE notice regardless). */
  private void writeExternalInactiveFile()
  {
    writeInactiveTo(enhanceDecodeProcess, "decode");
    writeInactiveTo(enhanceAudioProcess, "audio");
  }

  private void writeInactiveTo(Process p, String tag)
  {
    if (p == null) return;
    try
    {
      java.io.OutputStream os = p.getOutputStream();
      if (os != null)
      {
        os.write("inactivefile\n".getBytes(Sage.BYTE_CHARSET));
        os.flush();
        if (Sage.DBG) System.out.println("GPU_ENHANCE sent inactivefile to external " + tag + " stage");
      }
    }
    catch (Exception e)
    {
      if (Sage.DBG) System.out.println("GPU_ENHANCE inactivefile write to external " + tag + " stage failed: " + e);
    }
  }

  public void dynamicVideoRateAdjust(int kbpsAdjust)
  {
    if (xcodeStdin != null && !xcodeDone)
    {
      try
      {
        xcodeStdin.write(("videorateadapt " + kbpsAdjust + "\n").getBytes(Sage.BYTE_CHARSET));
        xcodeStdin.flush();
        currVideoBitrateKbps += kbpsAdjust;
        estimatedBandwidth += kbpsAdjust; // in case we seek, we want to use the newly selected bandwidth and not the old one
      }
      catch (Exception e)
      {
        System.out.println("Error writing to xcoder stdin of:" + e);
      }
    }
  }

  public int getCurrentVideoBitrateKbps()
  {
    return currVideoBitrateKbps;
  }

  /**
   * The launch-time video-bitrate ceiling (kbps) that the shared
   * {@link sage.media.BitratePolicy} set for THIS session -- the {@code -maxrate}
   * it computed from the output resolution/link/codec. Used by the live
   * {@code XCODE_ADJUST} handler so a pull proxy can drive the bitrate up to this
   * session's real envelope (e.g. 40&nbsp;Mbps for a 2160p enhance) instead of a
   * single flat cap that would throttle every high-resolution session to 8&nbsp;Mbps.
   * 0 when no policy plan was applied (the handler then keeps its configured default).
   */
  public int getPolicyCeilingKbps()
  {
    return policyCeilingKbps;
  }

  public int getCurrentStreamBitrateKbps()
  {
    return Math.round(((currAudioBitrateKbps + currVideoBitrateKbps) * (1 + currStreamOverheadPerct)));
  }

  public boolean isTranscoding()
  {
    return !xcodeDone && xcodeProcess != null;
  }

  /**
   * True when a real ffmpeg child is currently alive, regardless of the {@code
   * xcodeDone} flag. {@link #isTranscoding()} reads {@code !xcodeDone}, which a
   * consumer thread can spuriously flip to true on a transient pipe read while
   * the process is still running -- making isTranscoding() briefly report false
   * on a perfectly healthy transcode. Callers that must NOT relaunch a live
   * transcode (e.g. FastMpeg2Reader's lazy-start guard) check this instead, so a
   * spurious done-flag can no longer trigger a stopTranscode()/startTranscode()
   * churn -- the 4K->1080p MPEG-4 camera "Destroying old transcode process" /
   * black-screen restart loop.
   */
  public boolean hasLiveProcess()
  {
    Process p = xcodeProcess;
    return p != null && p.isAlive();
  }

  public void setEstimatedBandwidth(long bps)
  {
    estimatedBandwidth = bps;
  }

  public void setLocalClient(boolean b)
  {
    localClient = b;
  }

  public boolean isLocalClient()
  {
    return localClient;
  }

  /**
   * @param b true if the connecting client is a hardware media extender
   *          (HD100/HD200-class); see {@link #mediaExtender}.
   */
  public void setMediaExtender(boolean b)
  {
    mediaExtender = b;
  }

  public boolean isMediaExtender()
  {
    return mediaExtender;
  }

  /**
   * Absolute video-bitrate ceiling (Kbps) for the legacy mpeg4 "dynamic" placeshifter
   * ladder and its continuous runtime up-ramp adjuster (the {@code videorateadapt}
   * loop in {@code MiniPlayer}). Historically a single hardcoded value (1000 in the
   * startup tier ladder, 1500 in the runtime adjuster) applied to every classic
   * placeshifter client regardless of how much bandwidth is actually available --
   * so a LAN client whose measured/probed throughput is 50+ Mbps was held to the
   * same ~1 Mbps cap as a genuinely bandwidth-constrained WAN client, discarding
   * real headroom the bandwidth-feedback loop had already discovered.
   * <p>
   * Returns {@code ffmpeg/dynamic_max_video_kbps_lan} (default 5000) when
   * {@link #localClient} is true (set via {@link #setLocalClient}) AND the
   * client is NOT a hardware media extender, {@code ffmpeg/dynamic_max_video_kbps_lan_extender}
   * (default 1500, a deliberately conservative value matching extenders'
   * historical baseline) when it IS a LAN extender (see {@link #mediaExtender}/
   * {@link #setMediaExtender}) -- extender hardware's real mpeg4 decode
   * headroom hasn't changed just because the desktop-Placeshifter LAN
   * fallback ceiling was raised, so it keeps its own, separately
   * configurable ceiling -- otherwise {@code ffmpeg/dynamic_max_video_kbps_wan}
   * (default 1500, preserving the pre-existing WAN ceiling exactly). Either
   * is still bounded below by whatever {@link #estimatedBandwidth}/the live
   * bandwidth-hint feedback loop actually measures -- this is a ceiling, not
   * a target -- so a slow LAN link still gets scaled down appropriately.
   * <p>
   * Never-upscale-the-source correctness rule: the returned ceiling is also
   * hard-capped at the source's own bitrate ({@link #getSourceBitrateKbps()})
   * when that is known and lower than the configured ceiling. Recompressing
   * at a higher bitrate than the source itself carried doesn't add any real
   * detail -- it just spends bits the source never had -- so a LAN client
   * only gets pushed up toward genuine source quality, never invited to
   * synthesize beyond it.
   */
  public int getDynamicMaxVideoKbps()
  {
    int ceiling;
    if (!localClient)
      ceiling = Sage.getInt("ffmpeg/dynamic_max_video_kbps_wan", 1500);
    else if (mediaExtender)
      ceiling = Sage.getInt("ffmpeg/dynamic_max_video_kbps_lan_extender", 1500);
    else
      ceiling = Sage.getInt("ffmpeg/dynamic_max_video_kbps_lan", 5000);
    int sourceKbps = getSourceBitrateKbps();
    if (sourceKbps > 0 && sourceKbps < ceiling)
      return sourceKbps;
    return ceiling;
  }

  /**
   * Top-tier video bitrate (Kbps) for the legacy mpeg4 "dynamic" placeshifter ladder,
   * once the client's measured link is comfortably above the 900Kbps tier breakpoint.
   * This is where the "best A/V for the actual network" adaptive model is enforced for
   * the legacy path: the result is bandwidth-BOUNDED (never invents bits the measured
   * link doesn't have -- {@code estimatedBandwidthBps/2000} approximates half the
   * measured link in Kbps, reserving headroom for audio/overhead/burst) AND ceiling-
   * BOUNDED by {@link #getDynamicMaxVideoKbps()} (LAN-aware, itself hard-capped at
   * source bitrate -- never upscale/never exceed source). Whichever is lower wins, so
   * neither a generous LAN ceiling nor a fast-but-not-infinite measured link alone can
   * push the output past what's actually appropriate.
   */
  int selectDynamicVideoBitrateKbps(long estimatedBandwidthBps)
  {
    return Math.min(getDynamicMaxVideoKbps(), (int) (estimatedBandwidthBps / 2000));
  }

  /**
   * Best-known source bitrate (Kbps) for the current {@link #sourceFormat}, or
   * 0 if unknown. This is the OVERALL container bitrate (video + audio, as
   * reported by ffprobe/the format parser) -- {@link sage.media.format.VideoFormat}
   * carries no separate video-only bitrate field, so this is a deliberately
   * conservative approximation: for a video-dominant broadcast stream (e.g. a
   * multi-Mbps HEVC ATSC3 program with a few-hundred-Kbps AC-4/AAC audio
   * track) it is very close to the true video bitrate, and erring toward
   * including the audio share only makes the resulting ceiling slightly
   * MORE conservative (never accidentally permits exceeding source video
   * quality).
   */
  private int getSourceBitrateKbps()
  {
    if (sourceFormat == null) return 0;
    int br = sourceFormat.getBitrate();
    return br > 0 ? br / 1000 : 0;
  }

  /**
   * Target output resolution {@code {width, height}} for the legacy mpeg4
   * "dynamic" placeshifter ladder. Historically hardcoded to 1280x720 (with
   * an SD-source exception to avoid upscaling) regardless of the client's
   * decode capability or how much better the source actually is -- e.g. a
   * genuine 1080p HEVC ATSC3 source was downscaled to 720p even for a LAN
   * client with bandwidth and decode headroom for full 1080p mpeg4.
   * <p>
   * Returns a ceiling raised toward {@code ffmpeg/dynamic_max_width_lan} x
   * {@code ffmpeg/dynamic_max_height_lan} (default 1920x1080) for a
   * {@link #localClient}, otherwise the original 1280x720 WAN ceiling
   * (unchanged default behavior). In both cases the result is HARD-CAPPED at
   * the source's own resolution -- this is a ceiling toward true source-native
   * detail, it NEVER upscales or invents resolution the source doesn't have.
   * A source narrower/shorter than the applicable ceiling (e.g. genuine SD)
   * is passed through at its native size, exactly like the historical
   * SD-passthrough behavior this generalizes.
   */
  public int[] getDynamicMaxResolution(sage.media.format.VideoFormat srcVideo)
  {
    int ceilW = localClient ? Sage.getInt("ffmpeg/dynamic_max_width_lan", 1920) : 1280;
    int ceilH = localClient ? Sage.getInt("ffmpeg/dynamic_max_height_lan", 1080) : 720;
    if (srcVideo != null && srcVideo.getWidth() > 0 && srcVideo.getHeight() > 0)
    {
      // Never upscale, and never exceed source-native -- independent per-axis
      // min() is behavior-identical to the historical hardcoded-720p path for
      // any source at or above 1280x720 (both axes clamp to the same 1280x720
      // ceiling as before), and identical to the historical SD-passthrough
      // exception for a source below the ceiling on both axes.
      return new int[] { Math.min(ceilW, srcVideo.getWidth()), Math.min(ceilH, srcVideo.getHeight()) };
    }
    return new int[] { ceilW, ceilH };
  }

  /**
   * Top-tier frame rate for the legacy mpeg4 "dynamic" placeshifter ladder. The
   * ladder's top tier historically hardcoded 30fps (NTSC) / 25fps (PAL) regardless
   * of source cadence or available bandwidth, forcing a 59.94->30fps cadence
   * conversion even for a LAN client with ample bandwidth AND decode headroom.
   * Returns {@code ffmpeg/dynamic_max_fps_lan} (default 60) for a local client
   * capped at the source's own fps (never invents motion the source doesn't have),
   * otherwise the original NTSC/PAL default -- unchanged WAN behavior.
   */
  public int getDynamicMaxFps(sage.media.format.VideoFormat srcVideo)
  {
    int ntscPalDefault = MMC.getInstance().isNTSCVideoFormat() ? 30 : 25;
    if (!localClient)
      return ntscPalDefault;
    int lanCeiling = Sage.getInt("ffmpeg/dynamic_max_fps_lan", 60);
    float srcFps = (srcVideo != null) ? srcVideo.getFps() : 0;
    // Cap to the source's own cadence when it's lower than the configured LAN ceiling --
    // e.g. a 24fps film source stays at 24fps even for a LAN client; we only raise the
    // ceiling itself, never invent motion/interpolate frames the source doesn't have.
    if (srcFps > 0 && srcFps < lanCeiling)
      return (int) Math.ceil(srcFps);
    return lanCeiling;
  }

  /**
   * Feed periodic link-capacity hints (Kbps) from the player loop.
   *
   * Uses EWMA smoothing and simple hysteresis counters so callers can avoid
   * one-sample mode/bitrate oscillation:
   * - downshift hint only after repeated deficit windows
   * - upshift hint only after sustained headroom windows
   *
   * Returns the smoothed hint currently applied.
   */
  public synchronized int ingestLiveBandwidthHintKbps(int measuredKbps)
  {
    if (measuredKbps <= 0)
      return liveSmoothedBandwidthHintKbps;

    liveLastBandwidthHintKbps = measuredKbps;
    if (liveSmoothedBandwidthHintKbps <= 0)
      liveSmoothedBandwidthHintKbps = measuredKbps;
    else
      liveSmoothedBandwidthHintKbps = (int) Math.round((liveSmoothedBandwidthHintKbps * 0.7) + (measuredKbps * 0.3));

    int streamKbps = Math.max(1, getCurrentStreamBitrateKbps());
    int deficitThreshold = Math.max(1, streamKbps - 150);
    int headroomThreshold = streamKbps + 300;

    if (liveSmoothedBandwidthHintKbps < deficitThreshold)
    {
      liveDeficitWindows++;
      liveHeadroomWindows = 0;
    }
    else if (liveSmoothedBandwidthHintKbps > headroomThreshold)
    {
      liveHeadroomWindows++;
      liveDeficitWindows = 0;
    }
    else
    {
      liveDeficitWindows = 0;
      liveHeadroomWindows = 0;
    }

    // Only mutate estimatedBandwidth after hysteresis windows are satisfied.
    if (liveDeficitWindows >= 2 || liveHeadroomWindows >= 3)
      estimatedBandwidth = Math.max(1L, liveSmoothedBandwidthHintKbps * 1000L);

    return liveSmoothedBandwidthHintKbps;
  }

  public synchronized int getSmoothedLiveBandwidthHintKbps()
  {
    return liveSmoothedBandwidthHintKbps;
  }

  public long getEstimatedBandwidth()
  {
    return estimatedBandwidth;
  }

  // This'll convert from our internal format name back into what libav wants
  private static String substituteName(String s)
  {
    if (s == null) return null;
    // AAC encoder: use Fraunhofer libfdk_aac (supports HE-AAC v1/v2 for low bitrates)
    if ("aac".equalsIgnoreCase(s)) return "libfdk_aac";
    // 5/20/08 - The XVID encoder in FFMPEG is now called 'libxvid'
    if ("xvid".equalsIgnoreCase(s)) return "libxvid";
    // 6/5/08 - The h264 encoder in FFMPEG is now called 'libx264'
    if ("h264".equalsIgnoreCase(s)) return "libx264";
    // Remove the MP3 encoder if it's being used because that's what the input file is
    if ("mp3".equalsIgnoreCase(s) && Sage.getBoolean("xcode_disable_mp3_encoder", true)) return "mp2";
    for (int i = 0; i < sage.media.format.FormatParser.FORMAT_SUBSTITUTIONS.length; i++)
      if (sage.media.format.FormatParser.FORMAT_SUBSTITUTIONS[i][1].equalsIgnoreCase(s) &&
          sage.media.format.FormatParser.FORMAT_SUBSTITUTIONS[i][0].indexOf('/') == -1)
        return sage.media.format.FormatParser.FORMAT_SUBSTITUTIONS[i][0];
    return s.toLowerCase();
  }

  public void setEditParameters(long startTime, long duration)
  {
    transcodeStartSeekTime = startTime;
    transcodeEditDuration = duration;
  }

  /**
   * Seek the transcode to this source position (milliseconds) before it
   * launches, emitting {@code -ss} ahead of {@code -i}. Used by the pull-xcode
   * path (MediaServer XCODE_SETUP {@code ss=<ms>}) so a PWA/MSE seek/FF/REW/skip
   * that re-opens the /msproxy stream starts at the requested point instead of
   * restarting the transcode from 0.
   */
  public void setTranscodeStartSeekTime(long ms)
  {
    transcodeStartSeekTime = (ms > 0) ? ms : 0;
  }

  /**
   * The pending pre-launch seek position in milliseconds (0 = from start). Read
   * by {@link MediaServer} so it can clamp a pull-xcode {@code ss=} against the
   * opened file's demuxable end (via {@link SeekWindow}) once the physical file
   * is known -- XCODE_SETUP sets the seek before OPENFILE names the file.
   */
  public long getTranscodeStartSeekTime()
  {
    return transcodeStartSeekTime;
  }

  public void setPass(int x)
  {
    pass = x;
  }

  public void setThreadingEnabled(boolean x)
  {
    multiThread = x;
  }

  public void enableSegmentedOutput(int segmentDurMsec, java.io.File[] segFiles)
  {
    httplsMode = true;
    segmentData = new SegmentFileData[segFiles.length];
    for (int i = 0; i < segmentData.length; i++)
    {
      segmentData[i] = new SegmentFileData();
      segmentData[i].file = segFiles[i];
      segmentData[i].num = -1;
    }
    segmentDur = segmentDurMsec;
  }

  // Phase 1 CMAF/fMP4 (Option A). Reuses the httpls encode-decision branch
  // (httplsMode) but ffmpeg's hls muxer writes finalized init.mp4 + seg%d.m4s
  // files into outputDir itself -- there is no segmentData stdout ring on this
  // path. HTTPLSServer serves the files via getFmp4InitFile / getSegmentFile.
  public void enableFmp4SegmentedOutput(int segmentDurMsec, java.io.File outputDir)
  {
    httplsMode = true;
    fmp4Mode = true;
    segmentDur = segmentDurMsec;
    fmp4OutputDir = outputDir;
    if (outputDir != null) outputDir.mkdirs();
  }

  // The finalized CMAF init segment (moov-only). Blocks briefly until ffmpeg has
  // written it. Note ffmpeg (fast NVENC) may transcode the whole file and EXIT
  // before the client asks, so we key off the FILE existing, not process/xcodeDone
  // state -- once init.mp4 is on disk it is valid whether or not ffmpeg is alive.
  public java.io.File getFmp4InitFile() throws java.io.IOException
  {
    if (fmp4OutputDir == null) return null;
    java.io.File init = new java.io.File(fmp4OutputDir, "init.mp4");
    long deadline = Sage.time() + Sage.getInt("httpls_fmp4_init_wait_ms", 15000);
    while (Sage.time() < deadline)
    {
      if (init.isFile()) return init;
      // Only give up early if the encoder is truly gone AND produced nothing.
      if ((xcodeProcess == null || !xcodeProcess.isAlive()) && !init.isFile())
      {
        // one more grace check for a last-moment flush
        try { Thread.sleep(50); } catch (InterruptedException e){}
        if (init.isFile()) return init;
        break;
      }
      try { Thread.sleep(50); } catch (InterruptedException e){}
    }
    return init.isFile() ? init : null;
  }

  // Return the finalized seg<segNum>.m4s. temp_file makes each .m4s appear
  // atomically (rename on segment close), so the file merely EXISTING means it is
  // complete and safe to serve -- this is the source of truth, checked FIRST and
  // regardless of process state. A fast NVENC transcode routinely produces every
  // segment and exits before the client requests part 1, so we must NOT treat
  // "process ended" (xcodeDone / !isTranscoding) as "segment unavailable" the way
  // the earlier version did (that made seg1 abort and seg2 force a needless
  // rebuild). We only (re)launch the encoder when the file is absent AND either
  // nothing is running or the running encoder began AFTER segNum (a backward seek
  // it will never reach).
  public java.io.File getFmp4SegmentFile(int segNum) throws java.io.IOException
  {
    if (fmp4OutputDir == null) return null;
    java.io.File seg = new java.io.File(fmp4OutputDir, "seg" + segNum + ".m4s");
    if (seg.isFile()) return seg; // already produced (encoder may have since exited)

    boolean procAlive = (xcodeProcess != null && xcodeProcess.isAlive());
    // A far-FORWARD seek (e.g. a resume 7 minutes into a show) asks for a segment
    // the running encoder -- launched earlier and grinding forward from its own
    // start_number -- will only reach after transcoding everything in between.
    // Blocking on that is the resume-to-first-frame stall: minutes of content at
    // a few x realtime is many seconds of wait (measured ~17s on a 435s resume).
    // If the requested part is well beyond the highest segment produced so far,
    // relaunch the encoder AT this part with -ss instead of waiting, exactly like
    // a backward seek. A small look-ahead gap is tolerated so ordinary buffering
    // (a fast client requesting the next part or two before the encoder has
    // flushed them) does NOT thrash the pipeline with needless restarts.
    int forwardGap = Sage.getInt("httpls_fmp4_forward_reseek_gap_parts", 8);
    boolean farForwardSeek = false;
    if (procAlive && segNum >= fmp4StartSegment)
    {
      // The running encoder is producing forward from fmp4StartSegment, so its
      // effective position is at least there even before the first new .m4s
      // lands on disk. Measuring the gap against max(frontier, start) keeps a
      // just-issued reseek from re-triggering itself while the target part is
      // still being encoded (which would livelock, never producing it).
      int effectiveFrontier = Math.max(highestProducedFmp4Segment(), fmp4StartSegment);
      farForwardSeek = (segNum > effectiveFrontier + forwardGap);
    }
    if (!procAlive || segNum < fmp4StartSegment || farForwardSeek)
    {
      if (XCODE_DEBUG) System.out.println("fMP4 part #" + segNum + " not present (start=" + fmp4StartSegment
          + " alive=" + procAlive + " farForward=" + farForwardSeek + "); seeking transcoder to " + ((long) segNum * segmentDur));
      seekToTime((long) segNum * segmentDur);
      seg = new java.io.File(fmp4OutputDir, "seg" + segNum + ".m4s");
      if (seg.isFile()) return seg;
    }
    long deadline = Sage.time() + Sage.getInt("httpls_fmp4_segment_wait_ms", 30000);
    while (Sage.time() < deadline)
    {
      if (seg.isFile()) return seg;
      // The encoder exiting is NOT a failure by itself -- a fast transcode
      // finishes the whole file. Only give up if it's gone AND this segment
      // still isn't on disk after a final grace check (i.e. it's past EOF).
      if (xcodeProcess == null || !xcodeProcess.isAlive())
      {
        try { Thread.sleep(100); } catch (InterruptedException e){}
        break;
      }
      try { Thread.sleep(50); } catch (InterruptedException e){}
    }
    return seg.isFile() ? seg : null;
  }

  /**
   * Highest finalized fMP4 part index currently on disk for this session (a
   * completed {@code seg<N>.m4s}, never the in-progress {@code .m4s.tmp}), or
   * {@code -1} if none yet. Lets {@link #getFmp4SegmentFile} tell a far-forward
   * resume/seek -- where the running encoder is far behind the requested part and
   * relaunching at {@code -ss} is far faster than waiting -- from ordinary
   * look-ahead a fast encoder is about to satisfy on its own.
   */
  private int highestProducedFmp4Segment()
  {
    java.io.File[] fs = (fmp4OutputDir == null) ? null : fmp4OutputDir.listFiles();
    int max = -1;
    if (fs != null)
    {
      for (java.io.File f : fs)
      {
        String n = f.getName();
        if (n.length() > 7 && n.startsWith("seg") && n.endsWith(".m4s"))
        {
          try
          {
            int idx = Integer.parseInt(n.substring(3, n.length() - 4));
            if (idx > max) max = idx;
          }
          catch (NumberFormatException ignore) {}
        }
      }
    }
    return max;
  }

  public java.io.File getSegmentFile(int segNum) throws java.io.IOException
  {
    if (fmp4Mode) return getFmp4SegmentFile(segNum);
    // There's 3 cases here.
    // 1. The file is already filled and ready to return, the caller should call markSegmentConsumed when done with the file
    // 2. The file is being filled right now, so we block until it's done and then it's like #1
    // 3. We need to do a seek w/ the transcoder to get to the right part, then after we do that it's like #2
    synchronized (segFileSyncLock)
    {
      for (int i = 0; i < segmentData.length; i++)
        if (segmentData[i].num == segNum)
        {
          if (segmentData[i].state == SEGMENT_FILLED || segmentData[i].state == SEGMENT_CONSUMED || segmentData[i].state == SEGMENT_CONSUMING)
          {
            // Case 1
            if (XCODE_DEBUG) System.out.println("Part #" + segNum + " was requested from transcode, it's already filled, so returning the buffer file #" + i);
            segmentData[i].state = SEGMENT_CONSUMING;
            return segmentData[i].file;
          }
          else if (segmentData[i].state == SEGMENT_FILLING)
          {
            // Case 2
            while (!xcodeDone && segmentData[i].state == SEGMENT_FILLING && segmentData[i].num == segNum)
            {
              try
              {
                if (XCODE_DEBUG) System.out.println("Part #" + segNum + " was requested from transcode, it's currently filling, so wait before returning the buffer file #" + i);
                segFileSyncLock.wait(500);
              }
              catch (InterruptedException ioe){}
            }
            if (xcodeDone || segmentData[i].num != segNum ||
                (segmentData[i].state != SEGMENT_FILLED && segmentData[i].state != SEGMENT_CONSUMED && segmentData[i].state != SEGMENT_CONSUMING))
              return null;
            if (XCODE_DEBUG) System.out.println("Part #" + segNum + " was requested from transcode, it's filled now, so returning the buffer file #" + i);
            segmentData[i].state = SEGMENT_CONSUMING;
            return segmentData[i].file;
          }
        }
    }

    // The requested segment file is not being filled currently, this means we should seek the transcoder so it starts filling it immediately, then we wait
    // a bit before we request the segment again
    if (XCODE_DEBUG) System.out.println("Part #" + segNum + " was requested from transcode but it's buffer is not filling/filled, seek the transcoder now to " + (segNum * segmentDur));
    seekToTime(segNum * segmentDur);
    return getSegmentFile(segNum); // it should work this time
  }

  public void markSegmentConsumed(int segNum)
  {
    // fMP4 (Option A): ffmpeg owns the files and we keep them for the session so
    // seeks can re-serve earlier parts without re-encoding -- nothing to free.
    if (fmp4Mode) return;
    // This means this file is no longer in use, so we can do what we want with it
    synchronized (segFileSyncLock)
    {
      for (int i = 0; i < segmentData.length; i++)
        if (segmentData[i].num == segNum)
        {
          segmentData[i].state = SEGMENT_CONSUMED;
          segFileSyncLock.notifyAll();
          break;
        }
    }
  }

  protected String xcodeParams = "";
  protected boolean xcodeDone;
  protected Process xcodeProcess;
  // External-process (upscale worker) enhancement state. The encode ffmpeg is
  // tracked as xcodeProcess above; these are the decode ffmpeg and the
  // provider-owned worker that feed it. Staged by maybeApplyGpuEnhancement,
  // launched in startTranscode, and torn down in stopTranscode (and by the
  // shutdown reaper).
  protected Process enhanceDecodeProcess;
  protected Process enhanceWorkerProcess;
  protected volatile boolean externalEnhanceActive = false;
  /** True when the active external enhance pipeline's source-reading stages
   *  (decode + audio sidecar) carry -stdinctrl and follow the live file, so a
   *  boundary 'inactivefile' can be delivered to them (see setActiveFile). */
  private volatile boolean externalSourceCtrl = false;
  /** Path of the audio side-channel FIFO (decode -> encode) for a resumed
   *  enhanced play, or null when the encode re-opens the source for audio.
   *  Created at launch, unlinked in teardownExternalEnhance. */
  private volatile String enhanceAudioSidecarPath;
  /** The standalone audio-only ffmpeg process that accurately seeks the source and
   *  copies audio into the sidecar FIFO, or null when there is no sidecar. Spawned
   *  at launch, destroyed in teardownExternalEnhance. */
  private volatile Process enhanceAudioProcess;
  private sage.enhance.GpuEnhancePipeline.ExternalPipeline pendingExternalPipeline;
  private java.util.List<String> pendingExternalWorkerArgv;
  // A pre-warmed worker (from ScaleWarmupCache via the provider's plan(req,warm)),
  // or null for the normal cold-spawn path.
  private Process pendingExternalWarmProcess;
  // Raw-cmdline mode: bypass the legacy bf=/f=/br= token grammar + stream-walk
  // codec/bitrate translation in setTranscodeFormat() and startTranscode().
  // When true, xcodeParams holds the verbatim post-"-i" ffmpeg argv (space
  // separated) and rawCmdlineGlobal holds the verbatim pre-"-i" argv (typically
  // "-hwaccel cuda -hwaccel_output_format cuda"). Both come from the preset's
  // MRawCmdline= / MRawCmdlineGlobal= metadata keys. Used by the modern NVENC
  // offline preset catalogue ("Offline transcode preset modernization
  // (Ministry)" — see ROADMAP.md and java/sage/Ministry.java).
  protected boolean rawCmdlineMode = false;
  protected String rawCmdlineGlobal = null;
  // Container muxer name (the f= value from the preset spec). Used in raw-
  // cmdline mode to emit "-f <container>" before the output filename, since
  // SageTV writes the in-progress file with a .tmp extension that ffmpeg
  // cannot autodetect a muxer from.
  protected String rawCmdlineContainer = null;
  protected boolean activeFile;
  // Raw xcodeMode name passed to setTranscodeFormat(String, ContainerFormat) --
  // e.g. "mpeg2tsremux", "browserhd_remux", "dynamicts". Captured so the
  // on-demand (non-activeFile) probesize/analyzeduration tuning below can be
  // scoped to the confirmed modern NG copy-family xcodeModes ONLY, leaving
  // legacy dynamic/dynamicts/dynamich264/audioonly/mpeg2psremux VOD playback
  // completely untouched (current defaults preserved) unless/until separately
  // validated for those. null when this instance was configured via the
  // ContainerFormat-based setTranscodeFormat(ContainerFormat, ContainerFormat)
  // overload instead (no named mode involved there).
  protected String xcodeModeName;
  protected java.io.OutputStream xcodeStdin;
  // This is a set of buffers used to read from the transcode stream and to also send out the data. We keep
  // one extra buffer behind us in case the client needs to re-read something.
  protected byte[][] xcodeBuffer;
  // This is the sync object for the counters used in the xocde buffering
  protected Object xcodeSyncLock = new Object();
  // This is the buffer index whose 0 position corresponds to the xcodeBufferVirtualOffset
  protected int xcodeBufferBaseNum;
  // This is the total number of bytes we are from the start of the virtual transcoded file
  protected long xcodeBufferVirtualOffset;
  // This is the number of xcode buffers that are currently filled with data
  protected int numFilledXcodeBuffers;
  // Identifies the current transcode "generation" for the bufferOutput ring. A
  // seek/re-open restart (stopTranscode()+startTranscode() on the SAME instance)
  // reuses this instance's ring array and resets the fill counter. If the prior
  // XcodeDataConsumer writer thread outlived its join(2000) in stopTranscode(),
  // it would keep filling the reused ring alongside the new writer, driving
  // numFilledXcodeBuffers past xcodeBuffer.length -- the reader gets lapped and
  // the browser MSE stack rejects the torn fMP4 (CHUNK_DEMUXER_ERROR_APPEND_FAILED).
  // Each writer captures the generation at start and exits the moment a newer
  // startTranscode() bumps it, so only one writer ever mutates the ring.
  protected volatile int xcodeStdoutGeneration;
  // This is the total number of bytes that are available from the transcoder; it's
  // the virtualOffset + the number of bytes in the buffer
  protected long xcodeBufferVirtualSize;

  protected long xcodeBufferVirtualReadPos;

  protected long lastXcodeStreamTime;
  protected long lastXcodeStreamPosition;

  protected Thread xcodeStderrThread;
  protected Thread xcodeStdoutThread;

  protected java.nio.ByteBuffer hackBuf;
  protected java.nio.ByteBuffer overageBuf;

  protected java.io.File currFile;
  protected java.io.File captionSourceFile;
  protected String currServer;
  protected java.io.File outputFile;
  protected java.io.File preparedEmbeddedCcSubtitleFile;

  protected long transcodeStartSeekTime;
  protected java.io.FileInputStream fileStream;
  protected java.nio.channels.FileChannel fileChannel;

  protected boolean bufferOutput;

  // ---- Option B: bounded seekable spill history (see openSpillIfEnabled) ----
  // When enabled, the XcodeDataConsumer mirrors every committed chunk into a
  // fixed-capacity circular file on disk. A SIZE/READ pull consumer (PWA proxy
  // OR legacy miniclient) whose requested byte offset has already fallen behind
  // the small in-memory ring window is then served from that file instead of
  // restarting ffmpeg at a new -ss -- the in-generation seek/reconnect thrash.
  // The ring is left untouched as the live forward window; the spill is only
  // consulted for behind-window reads. Gated OFF by default so it is a no-op
  // until media_server/transcode_seekable_buffer is set.
  protected boolean seekableSpill;
  protected long spillCapBytes;
  protected long spillSafetyMargin;
  protected java.io.File xcodeSpillFile;
  protected java.nio.channels.FileChannel xcodeSpillChannel;

  protected java.io.InputStream xcodeStdout;

  protected static final int SEGMENT_FREE = 0;
  protected static final int SEGMENT_FILLING = 1;
  protected static final int SEGMENT_FILLED = 2;
  protected static final int SEGMENT_CONSUMING = 3;
  protected static final int SEGMENT_CONSUMED = 4;

  protected SegmentFileData[] segmentData;
  protected int segmentDur;
  protected Object segFileSyncLock = new Object();
  protected int segmentTargetCounter; // this is the segment number we should be actively writing, it accounts for any seek offsets as well (those offsets will affect this number)

  // Phase 1 CMAF/fMP4 ("dynamicfmp4") delivery. Unlike the httpls MPEG-TS path
  // (Java cuts ffmpeg's stdout into .ts files by time), the hls muxer writes
  // finalized init.mp4 + seg%d.m4s files itself (Option A), so there is no
  // stdout ring and no Java-side box cutting on this path. fmp4OutputDir is the
  // per-session directory those files land in; fmp4StartSegment is the absolute
  // part number ffmpeg's current run began writing (aligned via -start_number).
  protected boolean fmp4Mode = false;
  protected java.io.File fmp4OutputDir;
  protected int fmp4StartSegment;

  protected int currVideoBitrateKbps = -1;
  /** Launch-time BitratePolicy {@code -maxrate} ceiling (kbps) for this session;
   *  0 until a policy plan is applied. See {@link #getPolicyCeilingKbps()}. */
  protected int policyCeilingKbps = 0;
  protected int currAudioBitrateKbps = -1;
  protected float currStreamOverheadPerct;

  protected boolean dynamicRateAdjust = false;
  protected boolean iOSMode = false;
  /** Modern H.264 MPEG-TS push (set by the "dynamich264" transcode mode). When
   *  true the dynamic push path emits H.264 (NVENC or software libx264) in an
   *  MPEG-TS container at a bandwidth-appropriate resolution/bitrate instead of
   *  the legacy 2008-era mpeg4/DVD clamped near 1 Mbps. Only engaged for clients
   *  that positively advertise H.264 video + MPEG2-TS push (gated in MiniPlayer),
   *  so legacy 9.2.16 extenders/placeshifters keep the mpeg4 path unchanged. */
  protected boolean pushH264 = false;
  protected long estimatedBandwidth;
  /**
   * True when the connecting client is on the same subnet as the server
   * (see {@code MiniClientSageRenderer.isLocalConnection()}, a real IP/subnet-mask
   * comparison -- set here by {@code MiniPlayer} alongside {@link #setEstimatedBandwidth}).
   * Used by the legacy mpeg4 "dynamic" placeshifter ladder ({@link #getDynamicMaxVideoKbps()})
   * to lift its bitrate/fps ceiling for LAN clients instead of clamping everyone to the
   * same conservative WAN-safe cap regardless of actually-available bandwidth. Defaults to
   * false (WAN-conservative) so any caller that never sets it keeps today's behavior.
   */
  protected boolean localClient = false;
  /**
   * True when the connecting client is a hardware media extender (HD100/
   * HD200-class device -- no MOUSE in {@code INPUT_DEVICES}, see
   * {@code MiniClientSageRenderer.isMediaExtender()}, the SAME signal
   * {@code MiniPlayer.legacyH264PushProfileApplies()} uses to EXCLUDE
   * extenders from the H.264-push profile). Used by
   * {@link #getDynamicMaxVideoKbps()} to apply a separate, more
   * conservative LAN ceiling for extenders than for non-extender classic
   * desktop Placeshifter clients -- so raising the desktop-Placeshifter LAN
   * fallback ceiling never also raises old extender hardware's ceiling.
   * Defaults to false (non-extender) so any caller that never sets it keeps
   * today's non-extender behavior.
   */
  protected boolean mediaExtender = false;
  protected int liveLastBandwidthHintKbps;
  protected int liveSmoothedBandwidthHintKbps;
  protected int liveDeficitWindows;
  protected int liveHeadroomWindows;
  protected boolean httplsMode = false;

  /**
   * T3 full-GPU CMAF/httpls pipeline: NVDEC decode -&gt; [yadif_cuda] -&gt;
   * scale_cuda/scale_npp -&gt; h264_nvenc, all in VRAM. Set by
   * {@link #planHttplsVideoPipeline()} from HW availability + source codec and
   * read by the encode block to emit a CUDA {@code -vf} and skip the CPU
   * {@code -s} scale. Keeps frames in {@code -hwaccel_output_format cuda} so a
   * future ScaleProvider (VSR) FFMPEG_FILTER fragment can replace the scale
   * stage with no PCIe round-trip -- the fix for the ~0.55x-realtime
   * software-decode CMAF path.
   */
  protected boolean httplsHwFullGpu = false;
  /**
   * The CUDA scaler ({@code scale_cuda}|{@code scale_npp}) chosen for
   * {@link #httplsHwFullGpu}; the same scaler the enhancement path reports, for
   * one consistent scaler story across the transcode and enhancement pipelines.
   */
  protected String httplsHwScaler = null;
  /**
   * T1 remux-first: the source video already fits the client (H.264, progressive,
   * within the resolution ceiling) so the video stage is {@code -vcodec copy} --
   * no decode/scale/encode. Set by {@link #planHttplsVideoPipeline()}.
   */
  protected boolean httplsVideoCopy = false;

  /**
   * Effective audio codecs the connecting HLS client can decode (canonical
   * SageTV codec names, uppercased). Populated by
   * {@code HTTPLSServer.setupTranscoder} from the client's resolved
   * ClientProfile / reported AUDIO_CODECS. {@code null} or empty means the
   * client capability is unknown, in which case the HLS audio path
   * conservatively transcodes to AAC-LC rather than passing audio through.
   */
  protected java.util.Set httplsClientAudioCodecs;

  public void setHttplsClientAudioCodecs(java.util.Set codecs)
  {
    this.httplsClientAudioCodecs = codecs;
  }

  /**
   * Surface-declared TARGET audio codec for this stream (Protocol v2.1
   * Phase 2.5). When non-empty this is the honest per-decode-path signal
   * from the winning {@link sage.client.PlaybackSurface} and OVERRIDES the
   * coarse {@link #httplsClientAudioCodecs} lookup for the audio-copy vs
   * transcode decision. Empty for legacy V1/V2 sessions (in which case
   * the pre-Phase-2.5 client-caps lookup runs unchanged).
   *
   * <p>Why the override matters: a Chromium-based Tizen PWA advertises
   * {@code AUDIO_CODECS} = AAC,AC3,EAC3 (matching the native tizen
   * player's decoder set), but its Media Source Extensions decoder path
   * only handles AAC. Surface {@code pwa_mse} advertises just AAC in its
   * per-surface audio list; that becomes the target here, forcing
   * AC3 -> AAC transcode instead of the AC3 passthrough that MSE rejects.
   */
  protected String httplsSurfaceTargetAudioCodec = "";

  public void setHttplsSurfaceTargetAudioCodec(String codec)
  {
    this.httplsSurfaceTargetAudioCodec = (codec == null) ? "" : codec;
  }

  /**
   * Surface-declared TARGET video codec for this stream (Protocol v2.1
   * Phase 2.5). Populated for surface-aware sessions; empty for legacy.
   * Reserved for future use -- the HLS branch currently always encodes
   * to h264_nvenc; a follow-up will honor {@code -c:v copy} when the
   * source video codec matches this target.
   */
  protected String httplsSurfaceTargetVideoCodec = "";

  public void setHttplsSurfaceTargetVideoCodec(String codec)
  {
    this.httplsSurfaceTargetVideoCodec = (codec == null) ? "" : codec;
  }

  /**
   * The connecting client's physical display (sink) resolution in pixels, as
   * reported to {@code MiniClientSageRenderer} (getSinkWidth/getSinkHeight) and
   * pushed here by {@code HTTPLSServer.setupTranscoder}. 0 means the client did
   * not report it (e.g. a browser that hasn't advertised its viewport/display),
   * in which case the right-sizing logic falls back to the source-native /
   * LAN-WAN ceiling only. This is used ONLY to right-size DOWN (never upscale):
   * we never encode more pixels than the client can actually display.
   */
  protected int httplsSinkWidth = 0;
  protected int httplsSinkHeight = 0;

  public void setHttplsSinkResolution(int w, int h)
  {
    this.httplsSinkWidth = (w > 0) ? w : 0;
    this.httplsSinkHeight = (h > 0) ? h : 0;
  }

  /**
   * Source video codecs the unified ffmpeg's NVDEC can decode into CUDA frames
   * ({@code -hwaccel cuda -hwaccel_output_format cuda}); anything else keeps the
   * software-decode path. Deliberately a conservative allowlist of the common
   * SageTV recording codecs, so an unknown/exotic codec never forces a hard
   * cuda-only decode that would fail instead of falling back to software.
   */
  static boolean nvdecCanDecode(String codec)
  {
    if (codec == null) return false;
    return sage.media.format.MediaFormat.MPEG2_VIDEO.equals(codec)
        || sage.media.format.MediaFormat.MPEG1_VIDEO.equals(codec)
        || sage.media.format.MediaFormat.H264.equals(codec)
        || sage.media.format.MediaFormat.HEVC.equals(codec)
        || sage.media.format.MediaFormat.VC1.equals(codec)
        || sage.media.format.MediaFormat.MPEG4_VIDEO.equals(codec);
  }

  /**
   * Realtime-safe libx264 preset for the software (no-GPU) live/HLS encode,
   * derived from CPU core count and target height so a GPU-less server sustains
   * &gt;=1x realtime rather than falling behind the live edge (the CPU analogue of
   * T3). Operator override: {@code multimedia/hwaccel/libx264/live_preset}
   * ({@code auto} = derive; any x264 preset name pins it).
   */
  static String libx264LivePreset(int cores, int targetHeight)
  {
    String pin = Sage.get("multimedia/hwaccel/libx264/live_preset", "auto");
    if (pin != null && pin.length() > 0 && !"auto".equalsIgnoreCase(pin)) return pin;
    boolean hd = targetHeight > 576;
    if (cores >= 12) return hd ? "faster" : "fast";
    if (cores >= 8)  return hd ? "veryfast" : "faster";
    if (cores >= 4)  return "veryfast";
    return "ultrafast";
  }

  /**
   * Decide the httpls/CMAF video pipeline ONCE, before the ffmpeg argv is
   * assembled, so the decode and encode blocks agree on a single plan:
   * <ol>
   *   <li>T1 remux-first ({@link #httplsVideoCopy}) -- stream-copy H.264 that
   *       already fits the client ceiling (no decode/scale/encode).</li>
   *   <li>T3 full-GPU ({@link #httplsHwFullGpu}) -- NVDEC decode + CUDA
   *       deinterlace/scale + NVENC, all in VRAM, for CMAF sessions.</li>
   *   <li>Default -- software decode + NVENC/libx264 (unchanged).</li>
   * </ol>
   * Sets {@link #httplsVideoCopy}, {@link #httplsHwFullGpu} and
   * {@link #httplsHwScaler}. Safe to call once per session; no-op off the httpls
   * path.
   */
  protected void planHttplsVideoPipeline()
  {
    httplsVideoCopy = false;
    httplsHwFullGpu = false;
    httplsHwScaler = null;
    if (!httplsMode) return;
    sage.media.format.VideoFormat sv = (sourceFormat != null) ? sourceFormat.getVideoFormat() : null;
    String svc = (sourceFormat != null) ? sourceFormat.getPrimaryVideoFormat() : null;

    // --- T1 remux-first: copy H.264 that already fits the client ceiling ---
    // Progressive H.264 at/under the client's resolution ceiling needs no scale,
    // no deinterlace and no re-encode -- stream-copy the video, a large CPU win
    // on GPU-less servers (browser/CMAF clients already require H.264). MPEG-2
    // recordings (the common live case) are not H.264 and fall through.
    if (Sage.getBoolean("httpls_remux_passthrough", true) && sv != null
        && sage.media.format.MediaFormat.H264.equals(svc) && !sv.isInterlaced()
        && sv.getWidth() > 0 && sv.getHeight() > 0)
    {
      int[] ceil = getDynamicMaxResolution(sv);
      if (sv.getWidth() <= ceil[0] && sv.getHeight() <= ceil[1])
      {
        httplsVideoCopy = true;
        if (Sage.DBG) System.out.println("FFMPEGTranscoder: httpls: T1 remux-first video passthrough "
            + "(-vcodec copy) src=H.264 " + sv.getWidth() + "x" + sv.getHeight()
            + " within ceiling " + ceil[0] + "x" + ceil[1]);
        return;
      }
    }

    // --- T3 full-GPU decode/scale/deinterlace (CMAF/fmp4 only for now) ---
    if (!fmp4Mode) return;
    if (!Sage.getBoolean("miniplayer/httpls_hwdecode", true)) return;
    if (HwEncoder.pick("h264") != HwEncoder.Kind.NVENC) return;
    if (!nvdecCanDecode(svc)) return;
    String scaler = HwEncoder.cudaScaler();
    if (scaler == null) return;
    // Interlaced source needs a CUDA deinterlacer to stay on the GPU; if none is
    // available, fall back to the software-decode path (which uses CPU yadif).
    if (sv != null && sv.isInterlaced() && HwEncoder.cudaDeinterlacer(false) == null) return;
    // A 10-bit-capable codec (HEVC) without a scaler that can pin 8-bit output
    // (format=nv12) risks feeding p010 to h264_nvenc "high"; skip full-GPU there.
    if (sage.media.format.MediaFormat.HEVC.equals(svc) && !HwEncoder.scalerSupportsLanczos(scaler)) return;
    httplsHwScaler = scaler;
    httplsHwFullGpu = true;
    if (Sage.DBG) System.out.println("FFMPEGTranscoder: httpls: T3 full-GPU pipeline eligible; scaler=" + scaler
        + " src=" + svc + " interlaced=" + (sv != null && sv.isInterlaced()));
  }

  /**
   * Compute the right-sized (no-upscale) encode resolution for the NG delivery
   * paths. Fits the SOURCE frame, preserving its exact aspect ratio, into the
   * smaller of (a) the LAN/WAN ceiling from {@link #getDynamicMaxResolution}
   * (which is itself already clamped to source-native, so it never upscales)
   * and (b) the client's reported physical display (sink) when known. The scale
   * factor is hard-capped at 1.0, so a source smaller than the cap box is passed
   * through at its native size and is NEVER upscaled here -- upscaling is the
   * separate GPU-enhance path's job, not this right-sizing step. Returns even
   * dimensions (encoder-friendly). Falls back to the caller's current
   * targetWidth/targetHeight when the source dimensions are unknown.
   */
  /**
   * Bounded, best-effort source-geometry probe used by the enhance apply path
   * when {@link #sourceFormat} is not yet populated for an in-progress/live
   * source. Runs {@link sage.media.format.FormatParser#getFileFormat} on a daemon
   * worker with a hard timeout so a slow or actively-growing file can never stall
   * the transcode; returns null on timeout/error. Mirrors the advisor-side probe
   * in {@code MiniPlayer.probeSourceVideoFormatBounded} so the two layers recover
   * the same geometry.
   */
  private static sage.media.format.VideoFormat probeSourceVideoFormatBounded(
      final java.io.File f, int timeoutMs)
  {
    if (f == null || timeoutMs <= 0) return null;
    final sage.media.format.ContainerFormat[] out = new sage.media.format.ContainerFormat[1];
    Thread worker = new Thread("GpuEnhanceApplySourceProbe")
    {
      public void run()
      {
        try { out[0] = sage.media.format.FormatParser.getFileFormat(f); }
        catch (Throwable t) { /* best-effort probe; ignore and yield null */ }
      }
    };
    worker.setDaemon(true);
    worker.start();
    try { worker.join(timeoutMs); }
    catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
    sage.media.format.ContainerFormat cf = out[0];
    return (cf != null) ? cf.getVideoFormat() : null;
  }

  protected int[] computeRightSizedTarget(sage.media.format.VideoFormat srcVideo,
      int fallbackW, int fallbackH)
  {
    int srcW = (srcVideo != null) ? srcVideo.getWidth() : 0;
    int srcH = (srcVideo != null) ? srcVideo.getHeight() : 0;
    if (srcW <= 0 || srcH <= 0)
      return new int[] { fallbackW, fallbackH };
    // Source-clamped LAN/WAN ceiling (never upscales, per-axis min to source).
    int[] ceil = getDynamicMaxResolution(srcVideo);
    int capW = ceil[0];
    int capH = ceil[1];
    // Further clamp to the client's real display when it told us -- no point
    // encoding 1080p for a device that can only show 1366x768.
    if (httplsSinkWidth > 0 && httplsSinkHeight > 0)
    {
      capW = Math.min(capW, httplsSinkWidth);
      capH = Math.min(capH, httplsSinkHeight);
    }
    if (capW <= 0 || capH <= 0)
      return new int[] { fallbackW, fallbackH };
    // Aspect-preserving fit of the source into the cap box; never upscale.
    double scale = Math.min(1.0, Math.min(capW / (double) srcW, capH / (double) srcH));
    int outW = ((int) Math.round(srcW * scale)) & ~1;
    int outH = ((int) Math.round(srcH * scale)) & ~1;
    if (outW < 2) outW = 2;
    if (outH < 2) outH = 2;
    return new int[] { outW, outH };
  }

  /**
   * Surface-selected audio stream orderIndex for the current stream
   * (Protocol 2.1.0003). When >= 0, the explicit {@code -map 0:<index>}
   * block uses this value instead of the legacy getAudioFormat() lookup
   * (which just picks the lowest orderIndex, ignoring language/channels).
   * Set to -1 for legacy sessions (the old code path runs unchanged).
   *
   * <p>Populated by {@code HTTPLSServer.setupTranscoder} from
   * {@code MiniClientSageRenderer.getCurrentSurfaceAudioStreamIndex()},
   * which is set by {@code MiniPlayer} from the winning
   * {@link sage.client.PlaybackDecisionEngine.AudioStreamChoice}.
   */
  protected int httplsSurfaceAudioStreamIndex = -1;

  public void setHttplsSurfaceAudioStreamIndex(int index)
  {
    this.httplsSurfaceAudioStreamIndex = index;
  }

  /**
   * Item 2 (audioTrackSelectionMode="server"): audio-RELATIVE index (0-based
   * across audio streams only) of the single track the SERVER must preselect
   * and emit when the winning {@link sage.client.PlaybackSurface} declares
   * {@code AUDIO_TRACK_SELECTION_MODE=server}. {@code >= 0} means "server
   * preselect: map ONLY this audio track"; {@code -1} (the default) means
   * client-mode / legacy — pass all audio and let the client demux.
   *
   * <p>Populated by {@code HTTPLSServer.setupTranscoder} from
   * {@code MiniClientSageRenderer.getCurrentSurfaceServerAudioRelIndex()},
   * which {@code MiniPlayer} sets from the winning surface's selection mode
   * and the chosen audio stream's audio-relative position.
   */
  protected int httplsSurfaceServerAudioRelIndex = -1;

  public void setHttplsSurfaceServerAudioRelIndex(int audioRelIndex)
  {
    this.httplsSurfaceServerAudioRelIndex = audioRelIndex;
  }

  /**
   * Item 2 pure helper: build the audio {@code -map} target that selects ONLY
   * the server-preselected audio track. Returns {@code "0:a:<n>"} when server
   * audio selection is active and the audio-relative index is valid; {@code
   * null} means "no server preselect — map all audio (client demuxes) / use
   * the existing selection logic". Kept static + side-effect-free for unit
   * testing.
   *
   * @param serverAudioSelect true when the winning surface asked the server to
   *                          preselect the audio track
   * @param audioRelativeIndex 0-based index across the source's audio streams
   * @return the ffmpeg map token, or null for no override
   */
  static String serverSelectAudioMapToken(boolean serverAudioSelect, int audioRelativeIndex)
  {
    if (!serverAudioSelect || audioRelativeIndex < 0) return null;
    return "0:a:" + audioRelativeIndex;
  }

  /** HLS / MPEG-TS segments may only carry AAC, AC-3 or E-AC-3 audio. */
  private static boolean isHlsSafeAudioCodec(String codec)
  {
    return "AAC".equals(canonicalAudioCodec(codec))
        || "AC3".equals(canonicalAudioCodec(codec))
        || "EAC3".equals(canonicalAudioCodec(codec));
  }

  /**
   * True when the connecting HLS client's effective audio set contains the
   * given codec (alias-tolerant). Returns {@code false} when the client audio
   * set is unknown, so callers conservatively transcode instead of copying.
   */
  private boolean clientSupportsHttplsAudioCodec(String codec)
  {
    if (codec == null || httplsClientAudioCodecs == null || httplsClientAudioCodecs.isEmpty())
      return false;
    String want = canonicalAudioCodec(codec);
    for (Object o : httplsClientAudioCodecs)
    {
      if (o != null && want.equals(canonicalAudioCodec(o.toString())))
        return true;
    }
    return false;
  }

  /** Normalize audio codec spelling variants to a canonical uppercased key. */
  private static String canonicalAudioCodec(String codec)
  {
    if (codec == null) return "";
    String c = codec.toUpperCase();
    if (c.equals("AC-3")) return "AC3";
    if (c.equals("E-AC-3") || c.equals("EC-3") || c.equals("EAC-3")) return "EAC3";
    return c;
  }

  protected int lastExitCode = -1;
  protected long transcodeEditDuration;
  protected boolean forciblyStopped;

  protected sage.media.format.ContainerFormat sourceFormat;

  protected int pass;

  protected int preservedAudioBitrate;
  protected int preservedVideoBitrate;

  protected boolean multiThread = true;

  protected byte[] nioTmpBuf;

  private static class SegmentFileData
  {
    public java.io.File file;
    public int state;
    public int num;
  }
}
