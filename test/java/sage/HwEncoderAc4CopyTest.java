/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package sage;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.testng.annotations.Test;

/**
 * Tests for the ported ffmpeg output-format (muxer) listing parse and the
 * research-driven container stream-copy self-test that replaces the
 * StreamingSourcePlugin's {@code canStreamCopy = hasDecoder} fallacy.
 *
 * <p>The self-test's real-process pieces are exercised only through their pure,
 * package-private helpers ({@code parseMuxerNames}, {@code classifyStreamCopyProbe},
 * {@code buildStreamCopyProbeCommand}, {@code deriveFfprobePath}) so CI spawns no
 * ffmpeg. The canned failure signatures below are the exact strings captured live
 * against the deployed ffmpeg (MP4/MKV reject the AC-4 tag; TS mis-signals to
 * bin_data so the readback comes back non-AC-4).
 */
public class HwEncoderAc4CopyTest
{
  // A representative excerpt of real `ffmpeg -formats` output: a legend to be
  // ignored, a demux-only row (must NOT be recorded as a muxer), muxer rows, and
  // a comma-aliased mp4 family row.
  private static final List<String> SAMPLE_FORMATS = Arrays.asList(
      "File formats:",
      " D. = Demuxing supported",
      " .E = Muxing supported",
      " --",
      " D  aac             raw ADTS AAC (Advanced Audio Coding)",
      " DE matroska        Matroska",
      "  E mp4             MP4 (MPEG-4 Part 14)",
      " DE mov,mp4,m4a,3gp,3g2,mj2  QuickTime / MOV",
      " DE mpegts          MPEG-TS (MPEG-2 Transport Stream)",
      " D  hls             Apple HTTP Live Streaming",
      "  E ac4             raw AC-4");

  @Test
  public void testParseMuxerNamesRecordsOnlyMuxersAndSplitsAliases()
  {
    Set<String> mux = HwEncoder.parseMuxerNames(SAMPLE_FORMATS);
    assertTrue(mux.contains("matroska"), "matroska muxer present");
    assertTrue(mux.contains("mp4"), "mp4 muxer present");
    assertTrue(mux.contains("mpegts"), "mpegts muxer present");
    assertTrue(mux.contains("ac4"), "raw ac4 muxer present");
    // mov,mp4,m4a,... alias list must be split into each token
    assertTrue(mux.contains("mov"), "mov alias split out");
    assertTrue(mux.contains("m4a"), "m4a alias split out");
  }

  @Test
  public void testParseMuxerNamesExcludesDemuxOnlyRows()
  {
    Set<String> mux = HwEncoder.parseMuxerNames(SAMPLE_FORMATS);
    // aac and hls are demux-only (" D  ") — the plugin's parser wrongly kept these.
    assertFalse(mux.contains("aac"), "demux-only aac must not be a muxer");
    assertFalse(mux.contains("hls"), "demux-only hls must not be a muxer");
  }

  @Test
  public void testParseMuxerNamesHandlesNullAndEmpty()
  {
    assertTrue(HwEncoder.parseMuxerNames(null).isEmpty());
    assertTrue(HwEncoder.parseMuxerNames(Arrays.<String>asList()).isEmpty());
  }

  @Test
  public void testClassifyRejectsMp4TagFailure()
  {
    // MP4: "Could not find tag for codec ac4 ... not currently supported in container"
    String stderr = "[mp4 @ 0x0] Could not find tag for codec ac4 in stream #1, "
        + "codec not currently supported in container\n"
        + "[out#0/mp4 @ 0x0] Could not write header (incorrect codec parameters ?): Invalid argument";
    assertFalse(HwEncoder.classifyStreamCopyProbe(1, stderr, 0L, null, "ac4"));
  }

  @Test
  public void testClassifyRejectsMatroskaTagFailure()
  {
    // Matroska writes a ~293-byte EBML header stub then fails.
    String stderr = "[matroska @ 0x0] No wav codec tag found for codec ac4\n"
        + "[out#0/matroska @ 0x0] Could not write header (incorrect codec parameters ?): Invalid argument";
    assertFalse(HwEncoder.classifyStreamCopyProbe(1, stderr, 293L, null, "ac4"));
  }

  @Test
  public void testClassifyRejectsRawMuxerEmptyOutput()
  {
    // Raw ac4 muxer: header ok but "Output file is empty, nothing was encoded" (0 bytes).
    String stderr = "[out#0/ac4 @ 0x0] Output file is empty, nothing was encoded";
    assertFalse(HwEncoder.classifyStreamCopyProbe(0, stderr, 0L, null, "ac4"));
  }

  @Test
  public void testClassifyRejectsTsMisSignalledReadback()
  {
    // TS "succeeds" (exit 0, real size) but the audio reads back as data/bin_data,
    // so the first-audio-stream readback is empty -> reject.
    assertFalse(HwEncoder.classifyStreamCopyProbe(0, "", 29088864L, null, "ac4"));
    // Or, if something read back but it isn't ac4:
    assertFalse(HwEncoder.classifyStreamCopyProbe(0, "", 29088864L, "bin_data", "ac4"));
  }

  @Test
  public void testClassifyRejectsShortOutput()
  {
    // Clean exit, no error signature, but suspiciously small file (< 4096) — reject.
    assertFalse(HwEncoder.classifyStreamCopyProbe(0, "", 512L, "ac4", "ac4"));
  }

  @Test
  public void testClassifyAcceptsGenuineCopy()
  {
    // The one true-positive: clean exit, plausible size, no signature, readback == expected.
    assertTrue(HwEncoder.classifyStreamCopyProbe(0, "", 200000L, "ac4", "ac4"));
    // Case-insensitive on the codec name.
    assertTrue(HwEncoder.classifyStreamCopyProbe(0, "", 200000L, "AC4", "ac4"));
  }

  @Test
  public void testBuildStreamCopyProbeCommandShape()
  {
    List<String> cmd = HwEncoder.buildStreamCopyProbeCommand(
        "/opt/sagetv/server/ffmpeg", "/media/live.mpg", "0:a:0", "matroska", "/tmp/out.tmp");
    assertEquals(cmd.get(0), "/opt/sagetv/server/ffmpeg");
    // stream-copy, bounded, forced container, mapped audio, temp target
    assertTrue(cmd.contains("-c"));
    assertTrue(cmd.contains("copy"));
    assertTrue(cmd.contains("-map"));
    assertTrue(cmd.contains("0:a:0"));
    assertTrue(cmd.contains("-t"));
    assertTrue(cmd.contains("1"));
    assertEquals(cmd.get(cmd.indexOf("-f") + 1), "matroska");
    assertEquals(cmd.get(cmd.size() - 1), "/tmp/out.tmp");
    // input precedes the -map/-c so it copies the source, not re-reads output
    assertTrue(cmd.indexOf("-i") < cmd.indexOf("-map"));
  }

  @Test
  public void testBuildStreamCopyProbeCommandFragmentedMp4InsertsMovflags()
  {
    List<String> extra = Arrays.asList("-movflags", "+frag_keyframe+empty_moov+default_base_moof");
    List<String> cmd = HwEncoder.buildStreamCopyProbeCommand(
        "/opt/sagetv/server/ffmpeg", "/media/live.mpg", "0:a:0", "mp4", "/tmp/out.tmp", extra);
    // -movflags present and positioned before the -f/output (container shaping applies to output)
    assertTrue(cmd.contains("-movflags"));
    assertTrue(cmd.indexOf("-movflags") < cmd.indexOf("-f"),
        "-movflags must precede -f so it shapes the output muxer");
    assertEquals(cmd.get(cmd.indexOf("-movflags") + 1), "+frag_keyframe+empty_moov+default_base_moof");
    assertEquals(cmd.get(cmd.indexOf("-f") + 1), "mp4");
  }

  @Test
  public void testBuildStreamCopyProbeCommandNullExtraArgsMatchesBackCompat()
  {
    List<String> withNull = HwEncoder.buildStreamCopyProbeCommand(
        "/opt/sagetv/server/ffmpeg", "/media/live.mpg", "0:a:0", "mpegts", "/tmp/out.tmp", null);
    List<String> backCompat = HwEncoder.buildStreamCopyProbeCommand(
        "/opt/sagetv/server/ffmpeg", "/media/live.mpg", "0:a:0", "mpegts", "/tmp/out.tmp");
    assertEquals(withNull, backCompat);
  }

  @Test
  public void testDeriveFfprobePathSibling()
  {
    assertEquals(HwEncoder.deriveFfprobePath("/opt/sagetv/server/ffmpeg"),
        new java.io.File("/opt/sagetv/server/ffprobe").getPath());
    // bare name with no directory
    assertEquals(HwEncoder.deriveFfprobePath("ffmpeg"), "ffprobe");
    // null/blank -> plain ffprobe
    assertEquals(HwEncoder.deriveFfprobePath(null), "ffprobe");
    assertEquals(HwEncoder.deriveFfprobePath(""), "ffprobe");
  }
}
