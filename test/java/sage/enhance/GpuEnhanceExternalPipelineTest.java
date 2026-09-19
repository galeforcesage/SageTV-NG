/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package sage.enhance;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.testng.annotations.Test;

import sage.enhance.spi.ExecutionForm;
import sage.enhance.spi.ScaleExecutionPlan;

/**
 * The pure {@link GpuEnhancePipeline#buildExternalPipeline} list transform: a
 * copy-family base command plus an {@code EXTERNAL_PROCESS} plan yields the
 * decode and encode ffmpeg stages that bracket the worker, and every other shape
 * yields null so the caller falls back to the single-process (Lanczos) command.
 */
public class GpuEnhanceExternalPipelineTest
{
  private static List<String> baseCopyArgv()
  {
    return new ArrayList<String>(Arrays.asList(
        "/usr/bin/ffmpeg", "-ss", "10.5", "-i", "/rec/file.ts",
        "-map", "0:v", "-map", "0:a", "-c:v", "copy", "-c:a", "copy",
        "-f", "mpegts", "-"));
  }

  private static EnhancementPlan externalPlan()
  {
    ScaleExecutionPlan exec = new ScaleExecutionPlan(
        ExecutionForm.EXTERNAL_PROCESS,
        Arrays.asList("vsr_worker", "--mode", "stream"),
        1920, 1080, "rgb24", "VSR");
    return new EnhancementPlan(EnhancementTier.ENHANCE_1080P, true, "yadif_cuda",
        "scale_cuda", 1920, 1080, 14000L, 0L, "built", exec, null);
  }

  private static int idxPair(List<String> argv, String flag, String value)
  {
    for (int i = 0; i + 1 < argv.size(); i++)
      if (flag.equals(argv.get(i)) && value.equals(argv.get(i + 1))) return i;
    return -1;
  }

  @Test
  public void decodeStageEmitsRawFramesFromSource()
  {
    GpuEnhancePipeline.ExternalPipeline p =
        GpuEnhancePipeline.buildExternalPipeline(baseCopyArgv(), externalPlan(), 30);
    assertNotNull(p, "pipeline built");
    List<String> d = p.getDecodeArgv();
    assertEquals(d.get(0), "/usr/bin/ffmpeg", "decode uses the base ffmpeg binary");
    assertTrue(idxPair(d, "-hwaccel", "cuda") >= 0, "decode accelerated with cuda");
    assertTrue(idxPair(d, "-ss", "10.5") >= 0, "input seek carried into decode");
    assertTrue(idxPair(d, "-i", "/rec/file.ts") >= 0, "decode reads the source");
    assertTrue(d.contains("-an"), "decode drops audio");
    assertTrue(idxPair(d, "-vf", "yadif_cuda=1:-1:0,hwdownload,format=nv12") >= 0,
        "GPU bob deinterlace + hwdownload before the worker: " + d);
    assertTrue(idxPair(d, "-hwaccel_output_format", "cuda") >= 0,
        "CUDA-resident decoder output for the GPU deinterlacer: " + d);
    assertTrue(idxPair(d, "-f", "rawvideo") >= 0, "raw video muxer");
    assertTrue(idxPair(d, "-pix_fmt", "rgb24") >= 0, "rgb24 on the pipe");
    assertEquals(d.get(d.size() - 1), "pipe:1", "decode writes to stdout");
  }

  @Test
  public void encodeStageReadsRawFramesAndMuxesSourceAudio()
  {
    GpuEnhancePipeline.ExternalPipeline p =
        GpuEnhancePipeline.buildExternalPipeline(baseCopyArgv(), externalPlan(), 30);
    assertNotNull(p, "pipeline built");
    List<String> e = p.getEncodeArgv();
    assertEquals(e.get(0), "/usr/bin/ffmpeg", "encode uses the base ffmpeg binary");
    assertTrue(idxPair(e, "-f", "rawvideo") >= 0, "raw video input");
    assertTrue(idxPair(e, "-video_size", "1920x1080") >= 0, "reads worker output geometry");
    assertTrue(idxPair(e, "-framerate", "60") >= 0, "input frame rate set to the bob field rate");
    assertTrue(idxPair(e, "-i", "pipe:0") >= 0, "encode reads frames from stdin");
    assertTrue(idxPair(e, "-i", "/rec/file.ts") >= 0, "encode re-reads source for audio");
    assertTrue(idxPair(e, "-ss", "10.5") >= 0, "audio input aligned to the same seek");
    assertTrue(idxPair(e, "-map", "0:v:0") >= 0, "video mapped from the pipe");
    assertTrue(idxPair(e, "-map", "1:a?") >= 0, "audio mapped optionally from the source");
    assertTrue(idxPair(e, "-c:v", "hevc_nvenc") >= 0, "nvenc hevc encoder");
    assertTrue(idxPair(e, "-c:a", "copy") >= 0, "audio copied");
    assertTrue(idxPair(e, "-f", "mpegts") >= 0, "container carried from the base command");
    assertEquals(e.get(e.size() - 1), "-", "encode writes to the base output target (stdout)");
  }

  @Test
  public void filterPlanIsNotAnExternalPipeline()
  {
    ScaleExecutionPlan filter = new ScaleExecutionPlan(
        ExecutionForm.FFMPEG_FILTER, "scale_cuda=1920:1080", "CUDA");
    EnhancementPlan plan = new EnhancementPlan(EnhancementTier.ENHANCE_1080P, true,
        "yadif_cuda", "scale_cuda", 1920, 1080, 14000L, 0L, "built", filter, null);
    assertNull(GpuEnhancePipeline.buildExternalPipeline(baseCopyArgv(), plan, 30),
        "a filter plan is rendered single-process, not as an external pipeline");
  }

  @Test
  public void nonCopyShapeYieldsNull()
  {
    List<String> noInput = new ArrayList<String>(Arrays.asList(
        "/usr/bin/ffmpeg", "-f", "mpegts", "-"));
    assertNull(GpuEnhancePipeline.buildExternalPipeline(noInput, externalPlan(), 30),
        "no -i => not the expected shape");
  }

  @Test
  public void progressiveSourceSkipsDeinterlace()
  {
    ScaleExecutionPlan exec = new ScaleExecutionPlan(
        ExecutionForm.EXTERNAL_PROCESS,
        Arrays.asList("vsr_worker", "--mode", "stream"),
        1920, 1080, "rgb24", "VSR");
    EnhancementPlan plan = new EnhancementPlan(EnhancementTier.ENHANCE_1080P, false,
        null, "scale_cuda", 1920, 1080, 14000L, 0L, "built", exec, null);
    GpuEnhancePipeline.ExternalPipeline p =
        GpuEnhancePipeline.buildExternalPipeline(baseCopyArgv(), plan, 30);
    assertNotNull(p, "pipeline built");
    List<String> d = p.getDecodeArgv();
    for (String s : d) assertTrue(s == null || s.indexOf("yadif") < 0,
        "no deinterlace filter for a progressive source: " + d);
    assertTrue(idxPair(d, "-hwaccel_output_format", "cuda") < 0,
        "progressive decode leaves output in system memory (no -hwaccel_output_format): " + d);
  }

  // ---- Browser (re-encode / browserhd) external pipeline -------------------

  /** The exact browserhd (PWA/MSE) re-encode base command: H.264 nvenc, CPU
   *  format filter, fragmented-MP4 container, AAC stereo audio, stdout. */
  private static List<String> baseBrowserhdArgv()
  {
    return new ArrayList<String>(Arrays.asList(
        "/usr/bin/ffmpeg", "-v", "info", "-y", "-hwaccel", "cuda", "-threads", "2",
        "-i", "/rec/file.mpg",
        "-f", "mp4", "-movflags", "+frag_keyframe+empty_moov+default_base_moof",
        "-vf", "format=yuv420p",
        "-c:v", "h264_nvenc", "-preset", "p4", "-profile:v", "high",
        "-g", "60", "-forced-idr", "1",
        "-acodec", "aac", "-ac", "2", "-ar", "48000", "-b:a", "128k", "-"));
  }

  private static EnhancementPlan externalPlan2160()
  {
    ScaleExecutionPlan exec = new ScaleExecutionPlan(
        ExecutionForm.EXTERNAL_PROCESS,
        Arrays.asList("vsr_worker", "--mode", "stream"),
        3840, 2160, "rgb24", "VSR");
    return new EnhancementPlan(EnhancementTier.ENHANCE_2160P, false, null,
        "scale_cuda", 3840, 2160, 25000L, 0L, "built", exec, null);
  }

  @Test
  public void browserDecodeStageEmitsRawFramesFromSource()
  {
    GpuEnhancePipeline.ExternalPipeline p =
        GpuEnhancePipeline.buildExternalReencodePipeline(baseBrowserhdArgv(), externalPlan2160(), 60);
    assertNotNull(p, "browser pipeline built");
    List<String> d = p.getDecodeArgv();
    assertTrue(idxPair(d, "-hwaccel", "cuda") >= 0, "decode accelerated with cuda");
    assertTrue(idxPair(d, "-i", "/rec/file.mpg") >= 0, "decode reads the source");
    assertTrue(d.contains("-an"), "decode drops audio");
    assertTrue(idxPair(d, "-f", "rawvideo") >= 0, "raw video muxer");
    assertTrue(idxPair(d, "-pix_fmt", "rgb24") >= 0, "rgb24 on the pipe");
    assertEquals(d.get(d.size() - 1), "pipe:1", "decode writes to stdout");
  }

  @Test
  public void browserEncodeKeepsH264Fmp4AndAac()
  {
    GpuEnhancePipeline.ExternalPipeline p =
        GpuEnhancePipeline.buildExternalReencodePipeline(baseBrowserhdArgv(), externalPlan2160(), 60);
    assertNotNull(p, "browser pipeline built");
    List<String> e = p.getEncodeArgv();
    // Video comes from the worker pipe at the plan's output geometry.
    assertTrue(idxPair(e, "-f", "rawvideo") >= 0, "raw video input");
    assertTrue(idxPair(e, "-video_size", "3840x2160") >= 0, "reads worker output geometry");
    assertTrue(idxPair(e, "-i", "pipe:0") >= 0, "encode reads frames from stdin");
    assertTrue(idxPair(e, "-i", "/rec/file.mpg") >= 0, "encode re-reads source for audio");
    assertTrue(idxPair(e, "-map", "0:v:0") >= 0, "video mapped from the pipe");
    assertTrue(idxPair(e, "-map", "1:a?") >= 0, "audio mapped optionally from the source");
    // Browser codec/container preserved from the base command -- NOT HEVC.
    assertTrue(idxPair(e, "-c:v", "h264_nvenc") >= 0, "keeps the browser H.264 encoder");
    assertTrue(!e.contains("hevc_nvenc") && !e.contains("hvc1"), "no HEVC on the browser path: " + e);
    assertTrue(idxPair(e, "-preset", "p4") >= 0, "keeps preset");
    assertTrue(idxPair(e, "-profile:v", "high") >= 0, "keeps profile");
    assertTrue(idxPair(e, "-forced-idr", "1") >= 0, "keeps forced-idr");
    assertTrue(idxPair(e, "-f", "mp4") >= 0, "keeps the fMP4 container");
    assertTrue(idxPair(e, "-movflags", "+frag_keyframe+empty_moov+default_base_moof") >= 0,
        "keeps the fragmented-MP4 movflags");
    assertTrue(idxPair(e, "-acodec", "aac") >= 0, "keeps AAC audio");
    assertTrue(idxPair(e, "-b:a", "128k") >= 0, "keeps the audio bitrate");
    // The worker already scaled; no video filter may survive on the encode side.
    assertTrue(!e.contains("-vf"), "no -vf on the encode stage: " + e);
    assertTrue(!e.contains("format=yuv420p"), "base CPU format filter dropped: " + e);
    // Enhancement re-imposes its own VBR envelope.
    assertTrue(idxPair(e, "-rc", "vbr") >= 0 && idxPair(e, "-b:v", "25000k") >= 0,
        "re-imposes the plan VBR bitrate: " + e);
    assertTrue(e.contains("-maxrate") && e.contains("-bufsize"), "rate ceiling set: " + e);
    assertEquals(e.get(e.size() - 1), "-", "encode writes to the base output target (stdout)");
  }

  @Test
  public void browserEncodeRetargetsExplicitAudioMap()
  {
    // Interlaced/multi-audio browserhd shape: explicit -map 0:2 (audio) -map 0:0 (video).
    List<String> base = new ArrayList<String>(Arrays.asList(
        "/usr/bin/ffmpeg", "-hwaccel", "cuda", "-i", "/rec/file.mpg",
        "-f", "mp4", "-movflags", "+frag_keyframe+empty_moov+default_base_moof",
        "-vf", "format=yuv420p", "-c:v", "h264_nvenc",
        "-acodec", "aac", "-ac", "6", "-b:a", "384k",
        "-map", "0:2", "-map", "0:0", "-"));
    GpuEnhancePipeline.ExternalPipeline p =
        GpuEnhancePipeline.buildExternalReencodePipeline(base, externalPlan2160(), 60);
    assertNotNull(p, "browser pipeline built");
    List<String> e = p.getEncodeArgv();
    assertTrue(idxPair(e, "-map", "0:v:0") >= 0, "video mapped from the worker pipe");
    assertTrue(idxPair(e, "-map", "1:2") >= 0, "chosen source audio track retargeted to input 1");
    assertTrue(idxPair(e, "-map", "0:2") < 0, "source-input audio map not left dangling: " + e);
    assertTrue(idxPair(e, "-map", "0:0") < 0, "base video map dropped (pipe supersedes it): " + e);
  }

  @Test
  public void browserNonNvencEncoderYieldsNull()
  {
    List<String> base = new ArrayList<String>(Arrays.asList(
        "/usr/bin/ffmpeg", "-i", "/rec/file.mpg", "-vf", "format=yuv420p",
        "-c:v", "libx264", "-acodec", "aac", "-f", "mp4", "-"));
    assertNull(GpuEnhancePipeline.buildExternalReencodePipeline(base, externalPlan2160(), 60),
        "a non-nvenc browser encoder is refused (fail-closed)");
  }

  @Test
  public void browserFilterPlanIsNotAnExternalPipeline()
  {
    ScaleExecutionPlan filter = new ScaleExecutionPlan(
        ExecutionForm.FFMPEG_FILTER, "scale_cuda=3840:2160", "CUDA");
    EnhancementPlan plan = new EnhancementPlan(EnhancementTier.ENHANCE_2160P, false,
        null, "scale_cuda", 3840, 2160, 25000L, 0L, "built", filter, null);
    assertNull(GpuEnhancePipeline.buildExternalReencodePipeline(baseBrowserhdArgv(), plan, 60),
        "a filter plan is rendered single-process, not as an external pipeline");
  }

  // ---- Launcher-prefix ("nice") regression ---------------------------------
  // The live base command is wrapped in a "nice" launcher, so argv[0] is "nice"
  // and the ffmpeg binary is argv[1]. Treating argv[0] as the binary produced
  // "nice -hwaccel ..." (decode) and "nice -f rawvideo ..." (encode): nice
  // rejected the flag, both sub-stages died instantly, and every streamer
  // (PWA/iPad/Tizen MSE re-encode + Android copy-family) fell back to un-enhanced.

  @Test
  public void copyFamilyPreservesNiceLauncherPrefix()
  {
    List<String> base = new ArrayList<String>(baseCopyArgv());
    base.add(0, "nice");
    GpuEnhancePipeline.ExternalPipeline p =
        GpuEnhancePipeline.buildExternalPipeline(base, externalPlan(), 30);
    assertNotNull(p, "pipeline built from a nice-prefixed base");
    List<String> d = p.getDecodeArgv();
    List<String> e = p.getEncodeArgv();
    assertEquals(d.get(0), "nice", "launcher prefix preserved on decode");
    assertEquals(d.get(1), "/usr/bin/ffmpeg", "ffmpeg binary follows the prefix on decode");
    assertEquals(e.get(0), "nice", "launcher prefix preserved on encode");
    assertEquals(e.get(1), "/usr/bin/ffmpeg", "ffmpeg binary follows the prefix on encode");
    assertTrue(idxPair(d, "-i", "/rec/file.ts") >= 0, "decode still reads the source");
    assertTrue(idxPair(e, "-i", "pipe:0") >= 0, "encode still reads worker frames");
  }

  @Test
  public void browserPreservesNiceLauncherPrefix()
  {
    List<String> base = new ArrayList<String>(baseBrowserhdArgv());
    base.add(0, "nice");
    GpuEnhancePipeline.ExternalPipeline p =
        GpuEnhancePipeline.buildExternalReencodePipeline(base, externalPlan2160(), 60);
    assertNotNull(p, "browser pipeline built from a nice-prefixed base");
    List<String> d = p.getDecodeArgv();
    List<String> e = p.getEncodeArgv();
    assertEquals(d.get(0), "nice", "launcher prefix preserved on decode");
    assertEquals(d.get(1), "/usr/bin/ffmpeg", "ffmpeg binary follows the prefix on decode");
    assertEquals(e.get(0), "nice", "launcher prefix preserved on encode");
    assertEquals(e.get(1), "/usr/bin/ffmpeg", "ffmpeg binary follows the prefix on encode");
    assertTrue(idxPair(e, "-c:v", "h264_nvenc") >= 0, "browser H.264 encoder still kept");
    assertTrue(idxPair(e, "-i", "pipe:0") >= 0, "encode still reads worker frames");
  }

  // ---- CUVID / NVDEC hardware decode + -hwaccel de-duplication --------------
  // A bare "-hwaccel cuda" silently fell back to SOFTWARE mpeg2/h264 decode
  // (~0.65x realtime), starving the worker; and the base input section already
  // carried its own "-hwaccel cuda", so the decode stage emitted it twice. The
  // fix: strip inherited -hwaccel and add an explicit *_cuvid decoder chosen from
  // the source codec -- but only when the caller passes a codec (i.e. the server
  // has a GPU); a null codec keeps the generic hint.

  private static int count(List<String> argv, String tok)
  {
    int n = 0;
    for (String s : argv) if (tok.equals(s)) n++;
    return n;
  }

  @Test
  public void cuvidDecoderForMapsKnownCodecs()
  {
    assertEquals(GpuEnhancePipeline.cuvidDecoderFor(sage.media.format.MediaFormat.MPEG2_VIDEO), "mpeg2_cuvid");
    assertEquals(GpuEnhancePipeline.cuvidDecoderFor(sage.media.format.MediaFormat.H264), "h264_cuvid");
    assertEquals(GpuEnhancePipeline.cuvidDecoderFor(sage.media.format.MediaFormat.HEVC), "hevc_cuvid");
    assertNull(GpuEnhancePipeline.cuvidDecoderFor(null), "unknown/absent codec -> no cuvid");
    assertNull(GpuEnhancePipeline.cuvidDecoderFor("Theora"), "unsupported codec -> no cuvid");
  }

  @Test
  public void copyFamilyDecodeSelectsCuvidBeforeInput()
  {
    GpuEnhancePipeline.ExternalPipeline p = GpuEnhancePipeline.buildExternalPipeline(
        baseCopyArgv(), externalPlan(), 30, sage.media.format.MediaFormat.MPEG2_VIDEO);
    assertNotNull(p, "pipeline built");
    List<String> d = p.getDecodeArgv();
    int cIdx = idxPair(d, "-c:v", "mpeg2_cuvid");
    assertTrue(cIdx >= 0, "explicit mpeg2_cuvid NVDEC decoder: " + d);
    assertTrue(cIdx < d.indexOf("-i"), "cuvid decoder placed before -i: " + d);
    assertEquals(count(d, "-hwaccel"), 1, "exactly one -hwaccel on decode: " + d);
  }

  @Test
  public void browserDecodeStripsInheritedHwaccelAndAddsCuvid()
  {
    // baseBrowserhdArgv() carries its own "-hwaccel cuda" in the input section.
    GpuEnhancePipeline.ExternalPipeline p = GpuEnhancePipeline.buildExternalReencodePipeline(
        baseBrowserhdArgv(), externalPlan2160(), 60, sage.media.format.MediaFormat.MPEG2_VIDEO);
    assertNotNull(p, "browser pipeline built");
    List<String> d = p.getDecodeArgv();
    assertEquals(count(d, "-hwaccel"), 1, "inherited -hwaccel de-duplicated on decode: " + d);
    assertTrue(idxPair(d, "-c:v", "mpeg2_cuvid") >= 0, "explicit mpeg2_cuvid decoder: " + d);
    assertTrue(idxPair(d, "-i", "/rec/file.mpg") >= 0, "decode still reads the source");
  }

  @Test
  public void nullCodecKeepsGenericHwaccelWithNoCuvid()
  {
    GpuEnhancePipeline.ExternalPipeline p = GpuEnhancePipeline.buildExternalReencodePipeline(
        baseBrowserhdArgv(), externalPlan2160(), 60, null);
    assertNotNull(p, "browser pipeline built");
    List<String> d = p.getDecodeArgv();
    assertEquals(count(d, "-hwaccel"), 1, "single generic -hwaccel retained: " + d);
    assertTrue(idxPair(d, "-hwaccel", "cuda") >= 0, "generic cuda accel kept when codec unknown");
    for (String s : d) assertTrue(s == null || s.indexOf("_cuvid") < 0,
        "no explicit cuvid decoder without a known codec: " + d);
  }

  // ---- cuvid colorspace stamp (fixes decodeAlive=false on the RGB pipe) ------
  // mpeg2_cuvid emits NV12 with unspecified color metadata; strict swscale then
  // refuses NV12->RGB24 and the decode dies before the first frame. A setparams
  // stamp before the RGB conversion fixes it. Only on the cuvid + RGB-pipe path.

  @Test
  public void cuvidRgbPipeGetsColorspaceStampFirst()
  {
    // externalPlan() is interlaced + rgb24 -> GPU bob deinterlace, hwdownload,
    // THEN the colorspace stamp (it must tag the downloaded system-memory frames).
    GpuEnhancePipeline.ExternalPipeline p = GpuEnhancePipeline.buildExternalPipeline(
        baseCopyArgv(), externalPlan(), 30, sage.media.format.MediaFormat.MPEG2_VIDEO);
    assertNotNull(p, "pipeline built");
    List<String> d = p.getDecodeArgv();
    assertTrue(idxPair(d,
        "-vf", "yadif_cuda=1:-1:0,hwdownload,format=nv12,setparams=colorspace=bt709:color_primaries=bt709:color_trc=bt709") >= 0,
        "colorspace stamp follows hwdownload on the cuvid RGB pipe: " + d);
  }

  @Test
  public void reencodeCuvidRgbPipeGetsColorspaceStamp()
  {
    // externalPlan2160() is progressive + rgb24 -> chain is just the stamp.
    GpuEnhancePipeline.ExternalPipeline p = GpuEnhancePipeline.buildExternalReencodePipeline(
        baseBrowserhdArgv(), externalPlan2160(), 60, sage.media.format.MediaFormat.MPEG2_VIDEO);
    assertNotNull(p, "browser pipeline built");
    List<String> d = p.getDecodeArgv();
    assertTrue(idxPair(d,
        "-vf", "setparams=colorspace=bt709:color_primaries=bt709:color_trc=bt709") >= 0,
        "colorspace stamp present on the cuvid RGB pipe: " + d);
  }

  @Test
  public void softwareDecodeOmitsColorspaceStamp()
  {
    // No known codec -> no explicit cuvid -> software decode preserves the tags,
    // so no setparams stamp should be injected.
    GpuEnhancePipeline.ExternalPipeline p = GpuEnhancePipeline.buildExternalPipeline(
        baseCopyArgv(), externalPlan(), 30, null);
    assertNotNull(p, "pipeline built");
    for (String s : p.getDecodeArgv())
      assertTrue(s == null || s.indexOf("setparams") < 0,
          "no colorspace stamp on the software-decode path: " + p.getDecodeArgv());
  }

  // ---- Output pixel format (fixes 4:4:4 RGB HEVC/H.264 that HW decoders reject) ----
  // The worker feeds rgb24; NVENC left alone emits a 4:4:4 (Rext gbrp / High-4:4:4)
  // stream that HW decoders instantiate but render nothing from. The encode stage
  // must force yuv420p 8-bit 4:2:0 (Main profile for the HEVC copy-family path).

  @Test
  public void copyFamilyEncodeForcesYuv420pMainProfile()
  {
    GpuEnhancePipeline.ExternalPipeline p =
        GpuEnhancePipeline.buildExternalPipeline(baseCopyArgv(), externalPlan(), 30);
    assertNotNull(p, "pipeline built");
    List<String> e = p.getEncodeArgv();
    assertTrue(idxPair(e, "-pix_fmt", "yuv420p") >= 0,
        "encode output forced to 8-bit 4:2:0: " + e);
    assertTrue(idxPair(e, "-profile:v", "main") >= 0,
        "HEVC Main profile stamped for the RGB pipe: " + e);
    // The yuv420p pix_fmt belongs to the OUTPUT (after the pipe input), not the
    // rawvideo stdin, which stays rgb24 to match the worker.
    assertTrue(idxPair(e, "-pix_fmt", "rgb24") < idxPair(e, "-i", "pipe:0"),
        "input pipe pix_fmt stays rgb24: " + e);
  }

  @Test
  public void reencodeEncodeForcesYuv420p()
  {
    GpuEnhancePipeline.ExternalPipeline p = GpuEnhancePipeline.buildExternalReencodePipeline(
        baseBrowserhdArgv(), externalPlan2160(), 60, sage.media.format.MediaFormat.MPEG2_VIDEO);
    assertNotNull(p, "browser pipeline built");
    List<String> e = p.getEncodeArgv();
    assertTrue(idxPair(e, "-pix_fmt", "yuv420p") >= 0,
        "browser encode output forced to 8-bit 4:2:0: " + e);
  }

  // ---- Frame-rate cap / decimation (pipe-bandwidth + fps-consistency) -------
  // RGB24 at 60fps is ~530 MB/s through the OS pipe; a faster source is decimated
  // to the cap with an fps= filter BEFORE the pipe, and decode + encode share the
  // one effective rate so they never disagree. A native source below the cap is
  // passed through untouched (fixing "-framerate 60 on 24fps content").

  @Test
  public void copyFamilyDecimatesFasterSourceToCap()
  {
    // externalPlan() is interlaced -> GPU bob deinterlace (doubling 60->120), then
    // the cap decimates to 30 with an fps= filter at the tail of the chain.
    GpuEnhancePipeline.ExternalPipeline p = GpuEnhancePipeline.buildExternalPipeline(
        baseCopyArgv(), externalPlan(), 60, null, 30);
    assertNotNull(p, "pipeline built");
    List<String> d = p.getDecodeArgv();
    assertTrue(idxPair(d, "-vf", "yadif_cuda=1:-1:0,hwdownload,format=nv12,fps=30") >= 0,
        "deinterlace + decimation chain in pipe order: " + d);
    assertTrue(idxPair(p.getEncodeArgv(), "-framerate", "30") >= 0,
        "encode input tagged at the capped rate: " + p.getEncodeArgv());
  }

  @Test
  public void reencodeDecimatesProgressiveFasterSourceToCap()
  {
    // externalPlan2160() is progressive -> chain is just fps.
    GpuEnhancePipeline.ExternalPipeline p = GpuEnhancePipeline.buildExternalReencodePipeline(
        baseBrowserhdArgv(), externalPlan2160(), 60, null, 30);
    assertNotNull(p, "browser pipeline built");
    List<String> d = p.getDecodeArgv();
    assertTrue(idxPair(d, "-vf", "fps=30") >= 0, "decimation-only filter chain: " + d);
    assertTrue(idxPair(p.getEncodeArgv(), "-framerate", "30") >= 0,
        "encode input tagged at the capped rate: " + p.getEncodeArgv());
  }

  @Test
  public void nativeSourceBelowCapIsNotDecimated()
  {
    GpuEnhancePipeline.ExternalPipeline p = GpuEnhancePipeline.buildExternalReencodePipeline(
        baseBrowserhdArgv(), externalPlan2160(), 24, null, 30);
    assertNotNull(p, "browser pipeline built");
    List<String> d = p.getDecodeArgv();
    assertTrue(!d.contains("-vf"), "no filter chain when nothing to deinterlace/decimate: " + d);
    assertTrue(idxPair(p.getEncodeArgv(), "-framerate", "24") >= 0,
        "native 24fps passed through, not forced to 60: " + p.getEncodeArgv());
  }

  @Test
  public void capZeroKeepsFullRate()
  {
    GpuEnhancePipeline.ExternalPipeline p = GpuEnhancePipeline.buildExternalReencodePipeline(
        baseBrowserhdArgv(), externalPlan2160(), 60, null, 0);
    assertNotNull(p, "browser pipeline built");
    List<String> d = p.getDecodeArgv();
    assertTrue(!d.contains("-vf"), "no decimation when the cap is disabled: " + d);
    assertTrue(idxPair(p.getEncodeArgv(), "-framerate", "60") >= 0,
        "full source rate retained at cap 0: " + p.getEncodeArgv());
  }

  // ---- Exact broadcast rational frame rate + bob field-rate doubling ---------
  // A literal "-framerate 30" against 29.97 drifts ~1.8s of A/V over 30 minutes.
  // The exact source rate is carried as its NTSC rational, and an interlaced
  // source in bob mode is tagged at the doubled field rate.

  @Test
  public void frameRateTokenPrefersBroadcastRationals()
  {
    assertEquals(GpuEnhancePipeline.frameRateToken(30000.0 / 1001.0), "30000/1001", "29.97 -> rational");
    assertEquals(GpuEnhancePipeline.frameRateToken(60000.0 / 1001.0), "60000/1001", "59.94 -> rational");
    assertEquals(GpuEnhancePipeline.frameRateToken(24000.0 / 1001.0), "24000/1001", "23.976 -> rational");
    assertEquals(GpuEnhancePipeline.frameRateToken(30.0), "30", "true 30 stays integer");
    assertEquals(GpuEnhancePipeline.frameRateToken(25.0), "25", "PAL 25 stays integer");
    assertEquals(GpuEnhancePipeline.frameRateToken(60.0), "60", "true 60 stays integer");
  }

  @Test
  public void interlacedExactFpsEncodesFieldRateRational()
  {
    // 1080i 29.97 interlaced -> bob doubles to the 59.94 field rate -> 60000/1001.
    GpuEnhancePipeline.ExternalPipeline p = GpuEnhancePipeline.buildExternalPipeline(
        baseCopyArgv(), externalPlan(), 30, sage.media.format.MediaFormat.MPEG2_VIDEO, 0, 30000.0 / 1001.0);
    assertNotNull(p, "pipeline built");
    assertTrue(idxPair(p.getEncodeArgv(), "-framerate", "60000/1001") >= 0,
        "encode tagged at the exact doubled field rate: " + p.getEncodeArgv());
  }

  @Test
  public void progressiveExactFpsEncodesRational()
  {
    // 720p 59.94 progressive -> no doubling, exact 60000/1001 (not "60").
    GpuEnhancePipeline.ExternalPipeline p = GpuEnhancePipeline.buildExternalReencodePipeline(
        baseBrowserhdArgv(), externalPlan2160(), 60, sage.media.format.MediaFormat.MPEG2_VIDEO, 0, 60000.0 / 1001.0);
    assertNotNull(p, "browser pipeline built");
    assertTrue(idxPair(p.getEncodeArgv(), "-framerate", "60000/1001") >= 0,
        "progressive source tagged with the exact rational: " + p.getEncodeArgv());
  }

  // ---- Audio side-channel + accurate seek (resume lip-sync) -----------------
  // On a resumed play the video (cuvid fast-seek) and the re-opened audio (demux
  // seek) land at different times, and the rawvideo worker pipe strips the video
  // PTS, so audio and video drift by a seek-dependent 0.5-1.2s. The fix copies the
  // audio out of the SAME decode process to a FIFO, and both outputs use an
  // accurate seek (coarse input pre-roll + accurate output seek) so they share one
  // origin. No-op at ss=0; correct at every seek.

  @Test
  public void accurateSeekSplitRewritesInputAndReturnsOutputSeek()
  {
    List<String> in = new ArrayList<String>(Arrays.asList("-ss", "600", "-i", "/rec/f.ts"));
    GpuEnhancePipeline.SeekSplit s = GpuEnhancePipeline.accurateSeekSplit(in, 2.0);
    assertTrue(idxPair(s.decodeInput, "-ss", "598") >= 0,
        "coarse input seek moved back by the pre-roll: " + s.decodeInput);
    assertEquals(s.outputSeek, Arrays.asList("-ss", "2"), "accurate output seek = pre-roll");
    assertEquals(s.targetSeconds, 600.0, "absolute seek target carried for the video select landing");
  }

  @Test
  public void accurateSeekSplitIsNoOpAtHeadOrWithoutSeek()
  {
    // ss=0 (head): nothing to pre-roll.
    List<String> head = new ArrayList<String>(Arrays.asList("-ss", "0", "-i", "/rec/f.ts"));
    GpuEnhancePipeline.SeekSplit s0 = GpuEnhancePipeline.accurateSeekSplit(head, 2.0);
    assertTrue(s0.outputSeek.isEmpty(), "no output seek at the head: " + s0.outputSeek);
    assertTrue(idxPair(s0.decodeInput, "-ss", "0") >= 0, "input seek unchanged at the head");
    // No -ss at all.
    List<String> none = new ArrayList<String>(Arrays.asList("-i", "/rec/f.ts"));
    GpuEnhancePipeline.SeekSplit sn = GpuEnhancePipeline.accurateSeekSplit(none, 2.0);
    assertTrue(sn.outputSeek.isEmpty(), "no output seek without an input seek");
    assertEquals(sn.decodeInput, none, "input section untouched without a seek");
    // Pre-roll clamps to the seek target (T < preroll).
    List<String> shallow = new ArrayList<String>(Arrays.asList("-ss", "1.5", "-i", "/rec/f.ts"));
    GpuEnhancePipeline.SeekSplit ss = GpuEnhancePipeline.accurateSeekSplit(shallow, 2.0);
    assertTrue(idxPair(ss.decodeInput, "-ss", "0") >= 0, "coarse seek clamped to 0: " + ss.decodeInput);
    assertEquals(ss.outputSeek, Arrays.asList("-ss", "1.5"), "output seek = full target when shallow");
  }

  @Test
  public void copyFamilySidecarDecodeIsVideoOnlyWithAccurateSeek()
  {
    GpuEnhancePipeline.ExternalPipeline p = GpuEnhancePipeline.buildExternalPipeline(
        baseCopyArgv(), externalPlan(), 30, sage.media.format.MediaFormat.MPEG2_VIDEO,
        0, 30000.0 / 1001.0, "/tmp/aud.ts", 2.0);
    assertNotNull(p, "pipeline built");
    assertEquals(p.getAudioSidecarPath(), "/tmp/aud.ts", "sidecar path carried on the pipeline");
    List<String> d = p.getDecodeArgv();
    // baseCopyArgv() seeks to 10.5 -> coarse input 8.5; video lands accurately at
    // 10.5 via -copyts + a select filter (cuvid fast-seeks past the input discard).
    assertTrue(idxPair(d, "-ss", "8.5") >= 0, "coarse input seek on the decode: " + d);
    assertEquals(count(d, "-ss"), 1, "video decode carries only the coarse input seek: " + d);
    assertTrue(d.contains("-copyts"), "-copyts preserves source PTS for the select landing: " + d);
    String vf = d.get(d.indexOf("-vf") + 1);
    assertTrue(vf.contains("select=gte(t\\,10.5)"), "accurate select lands video at T=10.5: " + vf);
    assertTrue(idxPair(d, "-map", "0:v:0") >= 0, "explicit video map on the decode: " + d);
    assertEquals(d.get(d.indexOf("pipe:1") - 1), "rgb24", "video output pixel format on the pipe");
    assertEquals(d.get(d.size() - 1), "pipe:1", "decode's only output is the raw video pipe: " + d);
    // Audio must NOT be muxed out of the video decode (that was the deadlock).
    assertTrue(idxPair(d, "-c:a", "copy") < 0, "decode does NOT copy audio: " + d);
    assertTrue(!d.contains("/tmp/aud.ts"), "decode does NOT write the FIFO: " + d);

    // The audio is a SEPARATE process, same accurate seek, copied to the FIFO.
    List<String> a = p.getAudioArgv();
    assertNotNull(a, "audio process argv present for a sidecar pipeline");
    assertTrue(a.contains("-y"), "audio process overwrites the pre-created FIFO node: " + a);
    assertTrue(idxPair(a, "-ss", "8.5") >= 0, "audio process uses the same coarse input seek: " + a);
    assertEquals(count(a, "-ss"), 2, "audio process: same one input + one output seek: " + a);
    assertTrue(idxPair(a, "-map", "0:a?") >= 0, "audio mapped from the source: " + a);
    assertTrue(idxPair(a, "-c:a", "copy") >= 0, "audio copied to the sidecar: " + a);
    assertTrue(idxPair(a, "-f", "mpegts") >= 0, "sidecar muxed as mpegts: " + a);
    assertEquals(a.get(a.size() - 1), "/tmp/aud.ts", "audio process's last output is the FIFO: " + a);
  }

  @Test
  public void sidecarTranscodesWhenCodecSuppliedInsteadOfCopy()
  {
    // AC-4 sources cannot be stream-copied into mpegts by the pinned fork; the
    // caller passes the full transcode option lists so the sidecar transcodes
    // (with -copytb 0 + aresample=async timing) instead of a blind copy that would
    // exit before opening the FIFO and deadlock the encode.
    GpuEnhancePipeline.SidecarAudio sa = new GpuEnhancePipeline.SidecarAudio(
        Arrays.asList("-copytb", "0"),
        Arrays.asList("-c:a", "ac3", "-b:a", "384k",
            "-af", "aformat=channel_layouts=stereo,aresample=async=1000"));
    GpuEnhancePipeline.ExternalPipeline p = GpuEnhancePipeline.buildExternalPipeline(
        baseCopyArgv(), externalPlan(), 30, sage.media.format.MediaFormat.MPEG2_VIDEO,
        0, 30000.0 / 1001.0, "/tmp/aud.ts", 2.0, sa);
    assertNotNull(p, "pipeline built");
    List<String> a = p.getAudioArgv();
    assertNotNull(a, "audio process argv present");
    assertTrue(idxPair(a, "-c:a", "ac3") >= 0, "sidecar transcodes AC-4 -> ac3: " + a);
    assertTrue(idxPair(a, "-c:a", "copy") < 0, "sidecar does NOT copy when opts are given: " + a);
    assertTrue(idxPair(a, "-b:a", "384k") >= 0, "sidecar carries the caller's bitrate: " + a);
    assertTrue(a.contains("-copytb"), "-copytb input option present: " + a);
    // -copytb must precede the input -i (it is a per-input option).
    assertTrue(a.indexOf("-copytb") < a.indexOf("-i"), "-copytb sits before -i: " + a);
    int af = a.indexOf("-af");
    assertTrue(af >= 0 && a.get(af + 1).contains("aresample=async"), "async resample filter present: " + a);
    assertTrue(idxPair(a, "-f", "mpegts") >= 0, "sidecar still muxed as mpegts: " + a);
    assertEquals(a.get(a.size() - 1), "/tmp/aud.ts", "sidecar output is still the FIFO: " + a);

    // The re-encode builder honours the opts too.
    GpuEnhancePipeline.SidecarAudio sa2 = new GpuEnhancePipeline.SidecarAudio(
        Arrays.asList("-copytb", "0"),
        Arrays.asList("-c:a", "eac3", "-b:a", "640k",
            "-af", "aformat=channel_layouts=stereo,aresample=async=1000"));
    GpuEnhancePipeline.ExternalPipeline p2 = GpuEnhancePipeline.buildExternalReencodePipeline(
        baseBrowserhdArgv(), externalPlan2160(), 60, sage.media.format.MediaFormat.MPEG2_VIDEO,
        0, 60000.0 / 1001.0, "/tmp/aud2.ts", 2.0, sa2);
    List<String> a2 = p2.getAudioArgv();
    assertTrue(idxPair(a2, "-c:a", "eac3") >= 0, "re-encode sidecar transcodes to eac3: " + a2);
    assertTrue(idxPair(a2, "-b:a", "640k") >= 0, "eac3 sidecar carries the 640k floor: " + a2);
    assertTrue(a2.indexOf("-copytb") < a2.indexOf("-i"), "-copytb sits before -i (re-encode): " + a2);
  }

  @Test
  public void sidecarEncodeOmitsShortestSoLiveMuxIsNotTruncated()
  {
    // -shortest must NOT be on the encode: with the audio sidecar -following a
    // growing live file, the pinned ffmpeg fork can finalize/truncate the live
    // matroska mux early, and the client rejects the malformed stream ("I/O code
    // unsupported for video"). Teardown is handled by the stall watchdog killing
    // the worker + sidecar so both encode inputs EOF -- no -shortest needed.
    GpuEnhancePipeline.ExternalPipeline p = GpuEnhancePipeline.buildExternalPipeline(
        baseCopyArgv(), externalPlan(), 30, sage.media.format.MediaFormat.MPEG2_VIDEO,
        0, 30000.0 / 1001.0, "/tmp/aud.ts", 2.0);
    assertNotNull(p, "copy-family pipeline built");
    assertFalse(p.getEncodeArgv().contains("-shortest"),
        "copy-family encode must not carry -shortest: " + p.getEncodeArgv());

    GpuEnhancePipeline.ExternalPipeline p2 = GpuEnhancePipeline.buildExternalReencodePipeline(
        baseBrowserhdArgv(), externalPlan2160(), 60, sage.media.format.MediaFormat.MPEG2_VIDEO,
        0, 60000.0 / 1001.0, "/tmp/aud2.ts", 2.0);
    assertNotNull(p2, "re-encode pipeline built");
    assertFalse(p2.getEncodeArgv().contains("-shortest"),
        "re-encode encode must not carry -shortest: " + p2.getEncodeArgv());
  }

  @Test
  public void copyFamilySidecarEncodeReadsFifoNotSource()
  {
    GpuEnhancePipeline.ExternalPipeline p = GpuEnhancePipeline.buildExternalPipeline(
        baseCopyArgv(), externalPlan(), 30, sage.media.format.MediaFormat.MPEG2_VIDEO,
        0, 30000.0 / 1001.0, "/tmp/aud.ts", 2.0);
    assertNotNull(p, "pipeline built");
    List<String> e = p.getEncodeArgv();
    assertTrue(idxPair(e, "-i", "/tmp/aud.ts") >= 0, "encode reads audio from the FIFO: " + e);
    assertTrue(idxPair(e, "-i", "/rec/file.ts") < 0, "encode does NOT re-open the source: " + e);
    assertTrue(!e.contains("-ss"), "encode carries no seek (audio already positioned): " + e);
    assertTrue(idxPair(e, "-map", "0:v:0") >= 0, "video from the worker pipe: " + e);
    assertTrue(idxPair(e, "-map", "1:a?") >= 0, "audio from the FIFO input: " + e);
  }

  @Test
  public void reencodeSidecarDecodeVideoOnlyAndSeparateAudioProcess()
  {
    GpuEnhancePipeline.ExternalPipeline p = GpuEnhancePipeline.buildExternalReencodePipeline(
        baseBrowserhdArgv(), externalPlan2160(), 60, sage.media.format.MediaFormat.MPEG2_VIDEO,
        0, 60000.0 / 1001.0, "/tmp/aud2.ts", 2.0);
    assertNotNull(p, "browser pipeline built");
    assertEquals(p.getAudioSidecarPath(), "/tmp/aud2.ts", "sidecar path carried");
    List<String> d = p.getDecodeArgv();
    assertTrue(idxPair(d, "-map", "0:v:0") >= 0, "explicit video map: " + d);
    assertEquals(d.get(d.size() - 1), "pipe:1", "decode's only output is the raw video pipe: " + d);
    assertTrue(idxPair(d, "-c:a", "copy") < 0, "decode does NOT copy audio: " + d);
    assertTrue(!d.contains("/tmp/aud2.ts"), "decode does NOT write the FIFO: " + d);
    List<String> a = p.getAudioArgv();
    assertNotNull(a, "audio process argv present");
    assertTrue(a.contains("-y"), "audio process overwrites the FIFO node: " + a);
    assertTrue(idxPair(a, "-map", "0:a?") >= 0, "audio mapped from source: " + a);
    assertEquals(a.get(a.size() - 1), "/tmp/aud2.ts", "audio process's last output is the FIFO: " + a);
    List<String> e = p.getEncodeArgv();
    assertTrue(idxPair(e, "-i", "/tmp/aud2.ts") >= 0, "encode reads the FIFO: " + e);
    assertTrue(idxPair(e, "-map", "1:a?") >= 0, "audio from the FIFO input: " + e);
  }

  @Test
  public void nullSidecarPreservesLegacyReopenShape()
  {
    // Explicit null sidecar (default) => legacy: -an on decode, encode re-opens source.
    GpuEnhancePipeline.ExternalPipeline p = GpuEnhancePipeline.buildExternalPipeline(
        baseCopyArgv(), externalPlan(), 30, sage.media.format.MediaFormat.MPEG2_VIDEO,
        0, 30.0, null, 2.0);
    assertNotNull(p, "pipeline built");
    assertNull(p.getAudioSidecarPath(), "no sidecar path on the legacy shape");
    assertNull(p.getAudioArgv(), "no audio process on the legacy shape");
    assertTrue(p.getDecodeArgv().contains("-an"), "legacy decode drops audio");
    assertTrue(idxPair(p.getEncodeArgv(), "-i", "/rec/file.ts") >= 0, "legacy encode re-opens source");
  }
}
