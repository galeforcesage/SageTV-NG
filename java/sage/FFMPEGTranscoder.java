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
  static final String BITRATE_OPTIONS_SIZE_KEY = "httpls_bandwidth/%s/video_size";
  private static final String[] EMBED_CC_SIDECAR_SUFFIXES = {
      ".srt", ".eng.srt", ".cc.srt", ".vtt"
  };

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
    return xcodeDone;
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
      if (XCODE_DEBUG) System.out.println("Xcode readTranscodedData(" + inLength + ") buffNum=" + buffNum +
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
      if (XCODE_DEBUG) System.out.println("Xcode transferData complete overage=" + overage);
      xcodeBufferVirtualReadPos += inLength;
      synchronized (xcodeSyncLock)
      {
        while (xcodeBufferVirtualReadPos - xcodeBufferVirtualOffset >= xcodeBuffer[0].length)
        {
          // We're reading more than one buffer beyond our start so we can kill that first buffer now
          xcodeBufferBaseNum = (xcodeBufferBaseNum + 1) % xcodeBuffer.length;
          xcodeBufferVirtualOffset += xcodeBuffer[0].length;
          numFilledXcodeBuffers--;
          if (XCODE_DEBUG) System.out.println("Adjusted buffer nums xcodeBufferBaseNum=" + xcodeBufferBaseNum +
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
        if (XCODE_DEBUG) System.out.println("Xcoder Sending overage=" + overage);
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
        if (XCODE_DEBUG) System.out.println("Xcoder readFully " + numRead + " bytes directly from transcoder and is pushing it out");
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
    if (!isTranscoding())
    {
      if (offset == 0)
        startTranscode();
      else
        throw new java.io.IOException("Cannot do seekToPosition in transcoder because it hasn't been started yet!");
      return;
    }
    if ((!bufferOutput && offset != xcodeBufferVirtualOffset) || (bufferOutput && (offset < xcodeBufferVirtualOffset ||
        offset >= xcodeBufferVirtualOffset + xcodeBuffer.length*xcodeBuffer[0].length)))
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
      int numTries = 50;
      if (XCODE_DEBUG && overage > 0) System.out.println("Xcoder waiting for more data to appear in transcode buffer over=" + overage +
          " xcodeDone=" + xcodeDone);

      while (overage > 0 && !xcodeDone && (numTries-- > 0))
      {
        try { Thread.sleep(200); } catch (Exception e){}
        overage = offset + length - xcodeBufferVirtualSize;
      }
      if (overage > 0)
      {
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
      if (XCODE_DEBUG) System.out.println("Xcode transferData(" + offset + ", " + leftToRead + ") buffNum=" + buffNum +
          " buffOffset=" + buffOffset);
      while (leftToRead > 0)
      {
        int currRead = Math.min((int)leftToRead, xcodeBuffer[buffNum].length - buffOffset);
        chan.write(java.nio.ByteBuffer.wrap(xcodeBuffer[buffNum], buffOffset, currRead));
        leftToRead -= currRead;
        buffNum = (buffNum + 1) % xcodeBuffer.length;
        buffOffset = 0;
      }
      if (XCODE_DEBUG) System.out.println("Xcode transferData complete overage=" + overage);
      synchronized (xcodeSyncLock)
      {
        while (offset - xcodeBufferVirtualOffset >= xcodeBuffer[0].length)
        {
          // Kill the buffers we've consumed
          xcodeBufferBaseNum = (xcodeBufferBaseNum + 1) % xcodeBuffer.length;
          xcodeBufferVirtualOffset += xcodeBuffer[0].length;
          numFilledXcodeBuffers--;
          if (XCODE_DEBUG) System.out.println("Adjusted buffer nums xcodeBufferBaseNum=" + xcodeBufferBaseNum +
              " xcodeBufferVirtualOffset=" + xcodeBufferVirtualOffset + " numFilledBuffers=" + numFilledXcodeBuffers);
          xcodeSyncLock.notifyAll();
        }
      }
      while (overage > 0)
      {
        initOverageBuffer();
        overageBuf.limit((int)Math.min(overage, overageBuf.capacity()));
        if (XCODE_DEBUG) System.out.println("Xcoder sending overage=" + overageBuf.limit());
        int numWritten = chan.write(overageBuf); // just write out FF's
        overage -= numWritten;
        if (XCODE_DEBUG) System.out.println("Xcoder overage sent capacity=" + overageBuf.capacity() + " overage=" + overage + " numWritten=" + numWritten);
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
        if (XCODE_DEBUG) System.out.println("Xcoder read " + numRead + " bytes directly from transcoder and is pushing it out");
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
    if ("dynamic".equalsIgnoreCase(str))
      dynamicRateAdjust = true;
    else if ("dynamicts".equalsIgnoreCase(str))
    {
      iOSMode = true;
      dynamicRateAdjust = true;
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
   * @return true if {@code src} reports HEVC video or AC-4 audio, meaning the
   *         stock ffmpeg cannot decode it and we must route through the AC-4
   *         capable build.
   */
  private static boolean needsAc4Ffmpeg(sage.media.format.ContainerFormat src)
  {
    if (src == null) return false;
    String v = src.getPrimaryVideoFormat();
    if (sage.media.format.MediaFormat.HEVC.equals(v)) return true;
    String a = src.getPrimaryAudioFormat();
    if (sage.media.format.MediaFormat.AC4.equals(a)) return true;
    return false;
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
      // Profile had no explicit audio codec — append one so AC-4 source actually decodes.
      xcodeParamsVec.add("-c:a");
      xcodeParamsVec.add(ac4SourceAudioCodec);
    }
    // E-AC-3 default bitrate bump for 5.1 — ac3 stays at whatever the profile chose.
    if ("eac3".equalsIgnoreCase(ac4SourceAudioCodec))
    {
      String eac3Bps = Sage.get("miniplayer/eac3_bitrate", "640k");
      if (abIndex >= 0)
        xcodeParamsVec.set(abIndex, eac3Bps);
      else
      {
        xcodeParamsVec.add("-b:a");
        xcodeParamsVec.add(eac3Bps);
      }
    }
    if (Sage.DBG) System.out.println("FFMPEGTranscoder: AC-4 source — audio codec overridden to "
        + ac4SourceAudioCodec + (abIndex >= 0 ? " (bitrate slot=" + abIndex + ")" : ""));
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
   * ({@link #xcodeParams}) since the streaming path writes to stdout (so
   * {@link #isMp4FamilyOutput()}, which keys off the output filename, is false).
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
   * The shared {@code browserhd_copyv} pull-xcode preset hardcodes
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

  public void startTranscode() throws java.io.IOException
  {
    xcodeBufferBaseNum = 0;
    lastExitCode = -1;
    clearPreparedEmbeddedCcSubtitleFile();

    java.util.ArrayList xcodeParamsVec = new java.util.ArrayList();
    // Reduce process priority this way on non-windows platforms.
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
      xcodeParamsVec.add(Long.toString(transcodeStartSeekTime/1000));

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

    // GPU-accelerated DECODE (input option, must precede -i). Set by the
    // pull-xcode/browserhd path via setHwaccelDecode(). Offloads HEVC/H.264
    // decode to the GPU (e.g. NVDEC via "cuda"), the main start/seek latency
    // for high-res HEVC. No -hwaccel_output_format => frames land in system
    // memory, so the downstream CPU filter graph + encoder are untouched and
    // ffmpeg falls back to software decode for unsupported codecs.
    if (hwaccelDecode != null && hwaccelDecode.length() > 0)
    {
      xcodeParamsVec.add("-hwaccel");
      xcodeParamsVec.add(hwaccelDecode);
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
      xcodeParamsVec.add("-activefile");

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
      // fallback, so use a larger probe window to guarantee the parameter sets
      // are found first. This costs NO extra startup latency: +frag_keyframe
      // won't flush the first fragment until the first keyframe anyway.
      boolean videoCopyFmp4 = isVideoCopyToFmp4();
      long probeSize = videoCopyFmp4
          ? Sage.getLong("ffmpeg/live_probesize_videocopy", 8000000)
          : Sage.getLong("ffmpeg/live_probesize", 1000000);
      long analyzeDur = videoCopyFmp4
          ? Sage.getLong("ffmpeg/live_analyzeduration_videocopy", 5000000)
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
      xcodeParamsVec.add("-f");
      xcodeParamsVec.add("mpegts");
      // Live/HLS video encoder selection. Route through HwEncoder so NVENC is
      // used when the host has an NVIDIA GPU + an nvenc-capable ffmpeg; else
      // fall back to software libx264 (no-GPU hosts keep working unchanged).
      // NOTE: VAAPI/QSV/AMF are intentionally NOT engaged on this path yet --
      // the httpls -vf deinterlace/scale chain needs a hwupload filter-graph
      // rework and the bundled ffmpeg is NVENC-only. See ROADMAP "AMD / Intel
      // live transcode (VAAPI / QSV / AMF)". HwEncoder.pick() only returns
      // those kinds when the ffmpeg binary actually advertises them, so this
      // stays software until that work lands.
      HwEncoder.Kind liveKind = HwEncoder.pick("h264");
      boolean liveNvenc = (liveKind == HwEncoder.Kind.NVENC);
      if (liveKind != HwEncoder.Kind.NONE && !liveNvenc && Sage.DBG)
        System.out.println("FFMpegTranscoder: httpls: HW encoder " + liveKind +
            " is not yet wired for the live/HLS path (needs hwupload filter-graph rework);" +
            " using software libx264. See ROADMAP: AMD/Intel live transcode.");
      if (Sage.DBG)
        System.out.println("FFMpegTranscoder: httpls: video encoder tier -> " +
            (liveNvenc ? "h264_nvenc (NVENC)" : "libx264 (software)"));
      xcodeParamsVec.add("-vcodec");
      xcodeParamsVec.add(videoCodec = liveNvenc ? "h264_nvenc" : "libx264");
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
      currAudioBitrateKbps = 32;
      currVideoBitrateKbps = (int)Math.max(64000, (estimatedBandwidth - 32000))/1000;
      // FFmpeg 7.x: bare -b is ambiguous; must use -b:v
      xcodeParamsVec.add("-b:v");
      xcodeParamsVec.add(currVideoBitrateKbps*1000 + "");
      xcodeParamsVec.add("-s");
      xcodeParamsVec.add(targetWidth + "x" + targetHeight);
      xcodeParamsVec.add("-r");
      // Trying to lower the frame rate here caused problems...
      xcodeParamsVec.add("29.97");
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
      if (liveNvenc)
      {
      // NVENC accepts software frames directly (no -hwaccel/hwupload needed on
      // this path). Emit encoder-appropriate rate control instead of the
      // libx264-only option soup, which nvenc rejects or ignores.
      xcodeParamsVec.add("-preset");
      xcodeParamsVec.add(Sage.get("multimedia/hwaccel/nvenc/live_preset", "p4"));
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
      xcodeParamsVec.add("-maxrate");
      xcodeParamsVec.add(currVideoBitrateKbps*6000/5 + "");
      xcodeParamsVec.add("-bufsize");
      xcodeParamsVec.add(currVideoBitrateKbps*5000 + "");

      // FFmpeg 7.x: -deinterlace is removed; users now express deinterlace via -vf yadif.
      // Skip auto-add if user already asked for either legacy or modern form.
      if (xcodeParams.indexOf("-deinterlace") == -1 && xcodeParams.indexOf("yadif") == -1
          && srcVideo != null && srcVideo.isInterlaced() && targetHeight > srcVideo.getHeight()/2 &&
          Sage.getBoolean("xcode_auto_deinterlace", true))
      {
        if (Sage.DBG) System.out.println("Automatically adding yadif deinterlace filter to transcoding process");
        xcodeParamsVec.add("-vf");
        xcodeParamsVec.add("yadif");
      }

      // Preserve aspect ratio properly
      if (sourceFormat != null)
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
      xcodeParamsVec.add(Integer.toString(currVideoBitrateKbps * 6000 / 5));
      xcodeParamsVec.add("-bufsize");
      xcodeParamsVec.add(Integer.toString(currVideoBitrateKbps * 5000));
      // Preserve display aspect ratio (same as the legacy dynamic path).
      if (sourceFormat != null)
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

      // Preserve aspect ratio properly
      if (sourceFormat != null)
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
      if (xcodeParams.indexOf("-aspect") == -1 && sourceFormat != null)
      {
        // Preserve aspect ratio properly
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
      // Skip auto-add if user already asked for either legacy or modern form.
      if (xcodeParams.indexOf("-deinterlace") == -1 && xcodeParams.indexOf("yadif") == -1
          && srcVideo != null && srcVideo.isInterlaced() && targetHeight > srcVideo.getHeight()/2 &&
          Sage.getBoolean("xcode_auto_deinterlace", true))
      {
        if (Sage.DBG) System.out.println("Automatically adding yadif deinterlace filter to transcoding process");
        xcodeParamsVec.add("-vf");
        xcodeParamsVec.add("yadif");
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
      xcodeParamsVec.add("2000000"); // really high to prevent underflow errors TESTING
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
    if (currAudioBitrateKbps > 0 && sourceFormat != null && sourceFormat.getNumAudioStreams() > 1 && currVideoBitrateKbps > 0)
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
      // text subtitle codec, so use mov_text there.
      xcodeParamsVec.add("-c:s");
      xcodeParamsVec.add(isMp4FamilyOutput() ? "mov_text" : "copy");
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
    // MP4-family + AAC stream-copy: AAC from an ADTS-framed source (MPEG-TS)
    // must be converted to ASC via the aac_adtstoasc bitstream filter or the
    // mp4 muxer rejects the header ("Malformed AAC bitstream detected"). Verified
    // safe to always apply for AAC copy -- it passes already-ASC AAC (MKV/MP4)
    // through unchanged. Gated on AAC only (the filter errors on non-AAC) and on
    // copy only (re-encode emits ASC directly). Fixes browserhd_remux on
    // H.264+AAC MPEG-TS sources; harmless for library MKV/MP4 remux.
    if (audioIsCopy && isMp4FamilyOutput() && sourceFormat != null
        && sourceFormat.getAudioFormat() != null
        && sage.media.format.MediaFormat.AAC.equals(sourceFormat.getAudioFormat().getFormatName())
        && !xcodeParamsVec.contains("aac_adtstoasc"))
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
        String asyncVal = resolveAudioResampleAsync(isAc4Source);
        int acIdx = xcodeParamsVec.indexOf("-ac");
        boolean isDownmixToStereo = acIdx >= 0 && acIdx + 1 < xcodeParamsVec.size() &&
            "2".equals(xcodeParamsVec.get(acIdx + 1));
        xcodeParamsVec.add("-af");
        if (isAc4Source && isDownmixToStereo)
        {
          xcodeParamsVec.add("aformat=channel_layouts=stereo,aresample=async=" + asyncVal);
          if (Sage.DBG) System.out.println("FFMPEGTranscoder: AC-4 downmix filter: aformat=channel_layouts=stereo,aresample=async=" + asyncVal);
        }
        else
        {
          xcodeParamsVec.add("aresample=async=" + asyncVal);
        }
      }
      else if (Sage.DBG)
      {
        System.out.println("FFMPEGTranscoder: skipping -af aresample=async (audio is -acodec copy)");
      }
    }
    else //if (xcodeParams.indexOf("-f mp4") != -1 || xcodeParams.indexOf("-f 3gp") != -1 || xcodeParams.indexOf("-f psp") != -1)
    {
      xcodeParamsVec.add("-fps_mode");
      xcodeParamsVec.add("cfr");
      if (!audioIsCopy)
      {
        boolean isAc4Source = sourceFormat != null &&
            sage.media.format.MediaFormat.AC4.equals(sourceFormat.getPrimaryAudioFormat());
        String asyncVal = resolveAudioResampleAsync(isAc4Source);
        int acIdx = xcodeParamsVec.indexOf("-ac");
        boolean isDownmixToStereo = acIdx >= 0 && acIdx + 1 < xcodeParamsVec.size() &&
            "2".equals(xcodeParamsVec.get(acIdx + 1));
        xcodeParamsVec.add("-af");
        if (isAc4Source && isDownmixToStereo)
        {
          xcodeParamsVec.add("aformat=channel_layouts=stereo,aresample=async=" + asyncVal);
          if (Sage.DBG) System.out.println("FFMPEGTranscoder: AC-4 downmix filter: aformat=channel_layouts=stereo,aresample=async=" + asyncVal);
        }
        else
        {
          xcodeParamsVec.add("aresample=async=" + asyncVal);
        }
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
    maybeStripInapplicableHvc1Tag(xcodeParamsVec);
    String[] xcodeParamArray = (String[]) xcodeParamsVec.toArray(Pooler.EMPTY_STRING_ARRAY);
    // Always log the FFmpeg command line for diagnosability (disable with xcode_cmdline_debug=FALSE)
    if (Sage.DBG && !"FALSE".equals(Sage.get("xcode_cmdline_debug", "TRUE"))) System.out.println("Executing xcoding process with args: " + java.util.Arrays.asList(xcodeParamArray));
    ProcessBuilder xcodePb = new ProcessBuilder(xcodeParamArray);
    Sage.applyTimeZoneToProcessBuilder(xcodePb);
    xcodeProcess = xcodePb.start();
    // We open up the error stream and consume that for status info. The transcoded data is consumed by reading
    // from stdout.
    xcodeDone = false;
    if (xcodeBuffer == null)
    {
      // Don't use properties for these because it leads to major inconsistencies between systems that are quite difficult to diagnose
      if (currVideoBitrateKbps >= 1000)
        xcodeBuffer = new byte[16][32768];
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
        }
      }
    };
    xcodeStderrThread.setDaemon(true);
    xcodeStderrThread.start();
    numFilledXcodeBuffers = 0;
    xcodeStdout = xcodeProcess.getInputStream();
    forciblyStopped = false;
    if (bufferOutput)
    {
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
                if (numFilledXcodeBuffers == xcodeBuffer.length && !xcodeDone)
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
                if (XCODE_DEBUG) System.out.println("Read " + numRead + " bytes from transcoder");
                leftToRead -= numRead;
              } while (numRead != -1 && leftToRead > 0);
              if (numRead == -1)
              {
                xcodeDone = true;
                break;
              }
              else
              {
                synchronized (xcodeSyncLock)
                {
                  numFilledXcodeBuffers++;
                  xcodeBufferVirtualSize += xcodeBuffer[currBuffNum].length;
                  if (XCODE_DEBUG) System.out.println("Number of transcode buffers filled=" + numFilledXcodeBuffers
                      + " virtXcodedBytes=" + xcodeBufferVirtualSize);
                }
              }
            }while (true);
          }
          catch (Exception e){}
          finally
          {
            xcodeDone = true;
          }
        }
      };
      xcodeStdoutThread.setDaemon(true);
      xcodeStdoutThread.start();
    }
    else if (httplsMode)
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
              if (XCODE_DEBUG) System.out.println("Read " + numRead + " bytes from transcoder");
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

    xcodeStdin = xcodeProcess.getOutputStream();
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

    String ffmpeg = Sage.get("caption_extraction/ffmpeg_path", getTranscoderPath(sourceFormat));
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

  private boolean isMp4FamilyOutput()
  {
    if (outputFile == null) return false;
    String name = outputFile.getName().toLowerCase();
    return name.endsWith(".mp4") || name.endsWith(".m4v") || name.endsWith(".3gp") || name.endsWith(".psp");
  }

  public void stopTranscode()
  {
    forciblyStopped = true;
    xcodeDone = true;
    if (XCODE_DEBUG) System.out.println("Destroying old transcode process...");
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
      xcodeProcess.destroy();
    }
    xcodeProcess = null;
    if (XCODE_DEBUG) System.out.println("Destroyed!");
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

  public int getCurrentStreamBitrateKbps()
  {
    return Math.round(((currAudioBitrateKbps + currVideoBitrateKbps) * (1 + currStreamOverheadPerct)));
  }

  public boolean isTranscoding()
  {
    return !xcodeDone && xcodeProcess != null;
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

  public java.io.File getSegmentFile(int segNum) throws java.io.IOException
  {
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

  protected int currVideoBitrateKbps = -1;
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
