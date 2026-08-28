package sage;

import org.testng.annotations.Test;
import sage.media.format.AudioFormat;
import sage.media.format.BitstreamFormat;
import sage.media.format.ContainerFormat;
import sage.media.format.VideoFormat;

import java.io.File;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.testng.Assert.*;

public class FFMPEGTranscoderTest
{

  @Test
  public void testParseFrameSize() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    FFMPEGTranscoder transcoder = new FFMPEGTranscoder();

    // should parse and return values
    expectSize(transcoder.parseFrameSize("640x480", 1280, 720), 640, 480);
    expectSize(transcoder.parseFrameSize("640X480", 1280, 720), 640, 480);
    expectSize(transcoder.parseFrameSize("640 x 480", 1280, 720), 640, 480);

    // should fail and use defaults
    expectSize(transcoder.parseFrameSize("", 1280, 720), 1280, 720);
    expectSize(transcoder.parseFrameSize(null, 1280, 720), 1280, 720);
    expectSize(transcoder.parseFrameSize("640w x 480h", 1280, 720), 1280, 720);

    // setup to use original format
    transcoder.sourceFormat = new ContainerFormat();
    transcoder.sourceFormat.setStreamFormats(new BitstreamFormat[] {new VideoFormat()});
    transcoder.sourceFormat.getVideoFormat().setWidth(640);
    transcoder.sourceFormat.getVideoFormat().setHeight(480);

    expectSize(transcoder.parseFrameSize("original", 1280, 720), 640, 480);
  }

  @Test
  public void testTranscoderUsesOptionsFromProperties() throws Throwable
  {
    FFMPEGTranscoder transcoder = spy(new FFMPEGTranscoder());
    transcoder.httplsMode=true;
    transcoder.segmentDur = 10;
    transcoder.currFile = new File("dummyfile.ts");


    int bw = 34;
    transcoder.estimatedBandwidth = 34*1000;

    // ensure that this is not set in the Sage.properties for testing
    // so that we can ensure we get the defaults
    Sage.remove(String.format(FFMPEGTranscoder.BITRATE_OPTIONS_SIZE_KEY, bw));
    try
    {
      try
      {
        transcoder.startTranscode();
        transcoder.stopTranscode();
      } catch (Throwable t)
      {
        // this will fail, but, we just want to make sure our method was called.
      }

      // this is the default when NO properties are set.
      verify(transcoder).parseFrameSize(eq("480x272"), anyInt(), anyInt());


      // ensure that we set this in the Sage.properties, so that we can verify that actually use the Sage.properties
      Sage.put(String.format(FFMPEGTranscoder.BITRATE_OPTIONS_SIZE_KEY, bw), "1280x720");
      try
      {
        transcoder.startTranscode();
        transcoder.stopTranscode();
      } catch (Throwable t)
      {
        // this will fail, but, we just want to make sure our method was called.
      }

      // this should have come from xcode bitrate specific options
      verify(transcoder).parseFrameSize(eq("1280x720"), anyInt(), anyInt());

      // ensure that this is not set in the Sage.properties for testing
      // so that we can ensure we get the defaults
      Sage.remove(String.format(FFMPEGTranscoder.BITRATE_OPTIONS_SIZE_KEY, bw));


      // test if default will be used
      // ensure that we set this in the Sage.properties, so that we can verify that actually use the Sage.properties
      Sage.put(String.format(FFMPEGTranscoder.BITRATE_OPTIONS_SIZE_KEY, "default"), "200x100");
      try
      {
        transcoder.startTranscode();
        transcoder.stopTranscode();
      } catch (Throwable t)
      {
        // this will fail, but, we just want to make sure our method was called.
      }

      // this should have come from xcode bitrate specific options
      verify(transcoder).parseFrameSize(eq("200x100"), anyInt(), anyInt());

      // ensure that this is not set in the Sage.properties for testing
      // so that we can ensure we get the defaults
      Sage.remove(String.format(FFMPEGTranscoder.BITRATE_OPTIONS_SIZE_KEY, "default"));
    }
    finally
    {
      // ensure that this is not set in the Sage.properties for testing
      // so that we can ensure we get the defaults
      Sage.remove(String.format(FFMPEGTranscoder.BITRATE_OPTIONS_SIZE_KEY, bw));
    }
  }

  @Test
  public void testIsVideoCopyToFmp4() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    FFMPEGTranscoder transcoder = new FFMPEGTranscoder();

    // browserhd_copyv (HEVC/H.264 video-copy + audio transcode into fMP4)
    transcoder.xcodeParams = "-f mp4 -movflags +frag_keyframe+empty_moov+default_base_moof"
        + " -frag_duration 500000 -c:v copy -tag:v hvc1 -acodec aac -ac 2 -ar 48000 -b:a 128k";
    assertTrue(transcoder.isVideoCopyToFmp4());

    // browserhd_remux (video + audio copy into fMP4)
    transcoder.xcodeParams = "-f mp4 -movflags +frag_keyframe+empty_moov+default_base_moof"
        + " -frag_duration 500000 -c:v copy -c:a copy";
    assertTrue(transcoder.isVideoCopyToFmp4());

    // legacy spelling
    transcoder.xcodeParams = "-f mp4 -vcodec copy -acodec aac";
    assertTrue(transcoder.isVideoCopyToFmp4());

    // TS copy (not fMP4) -> false
    transcoder.xcodeParams = "-f mpegts -c:v copy -c:a copy -copyts";
    assertFalse(transcoder.isVideoCopyToFmp4());

    // fMP4 but video re-encode -> false (encoder supplies dimensions)
    transcoder.xcodeParams = "-f mp4 -c:v libx264 -acodec aac";
    assertFalse(transcoder.isVideoCopyToFmp4());

    transcoder.xcodeParams = null;
    assertFalse(transcoder.isVideoCopyToFmp4());
  }

  // --- Resume/seek robustness: a non-zero seek before the transcoder is running
  // must NOT throw and tear down the session. Historically seekToPosition() threw
  // "Cannot do seekToPosition ... hasn't been started yet!" for any non-zero
  // offset while not transcoding, which killed the MediaServerConnection -> the
  // client saw an instant end-of-stream (STV popped the "delete this recording?"
  // prompt) and resume-from-position never worked (observed on the PWA browserhd
  // pull-xcode path). It must instead start the transcode aligned to the offset.
  @Test
  public void testSeekToPositionColdNonZeroStartsInsteadOfThrowing() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    FFMPEGTranscoder transcoder = spy(new FFMPEGTranscoder());
    org.mockito.Mockito.doNothing().when(transcoder).startTranscode();

    assertFalse(transcoder.isTranscoding(), "a fresh transcoder must not report transcoding");

    // Must not throw the legacy "hasn't been started yet" IOException.
    transcoder.seekToPosition(5_000_000L);

    verify(transcoder).startTranscode();
    assertEquals(transcoder.xcodeBufferVirtualOffset, 5_000_000L,
        "virtual offset must align to the requested resume offset");
    assertEquals(transcoder.xcodeBufferVirtualReadPos, 5_000_000L);
    assertEquals(transcoder.xcodeBufferVirtualSize, 5_000_000L);
  }

  @Test
  public void testSeekToPositionColdZeroStartsFromTop() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    FFMPEGTranscoder transcoder = spy(new FFMPEGTranscoder());
    org.mockito.Mockito.doNothing().when(transcoder).startTranscode();

    transcoder.seekToPosition(0L);

    verify(transcoder).startTranscode();
    assertEquals(transcoder.xcodeBufferVirtualOffset, 0L,
        "a zero-offset cold start must remain at the top of the stream");
  }

  // --- Live streaming (non-seekable fMP4 pipe) must NOT relaunch ffmpeg on an
  // in-session byte-offset change. The PWA/MSE client issues HTTP Range requests
  // against a forward-only transcode pipe; those byte offsets are not source seeks.
  // The old code gated on isTranscoding() (which xcodeDone can spuriously flip to
  // false while the child is alive) and, for streaming, restarted the transcode on
  // any offset != xcodeBufferVirtualOffset -- spawning a fresh NVENC session per
  // Range request (orphaned-GPU leak) and re-serving from the top, so resume/seek
  // played ~1s then thrashed and froze. With a live child we must just realign the
  // read cursor and serve forward: no stopTranscode(), no startTranscode().
  @Test
  public void testSeekToPositionStreamingLiveChildDoesNotRelaunch() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    FFMPEGTranscoder transcoder = spy(new FFMPEGTranscoder());
    org.mockito.Mockito.doNothing().when(transcoder).startTranscode();

    // Streaming (non-buffered) mode with a live ffmpeg child already running.
    transcoder.setEnableOutputBuffering(false);
    Process liveChild = org.mockito.Mockito.mock(Process.class);
    org.mockito.Mockito.when(liveChild.isAlive()).thenReturn(true);
    transcoder.xcodeProcess = liveChild;
    transcoder.xcodeBufferVirtualOffset = 0L;

    transcoder.seekToPosition(1_048_576L);

    // Must serve forward, not relaunch.
    verify(transcoder, org.mockito.Mockito.never()).startTranscode();
    verify(transcoder, org.mockito.Mockito.never()).stopTranscode();
    assertEquals(transcoder.xcodeBufferVirtualReadPos, 1_048_576L,
        "streaming seek must realign the read cursor to serve forward");
  }

  // --- Camera crash-loop guard: a LIVE ffmpeg child must never be reported as a
  // finished-but-failed transcode. The consumer threads set xcodeDone=true the
  // instant a pipe hiccups (EOF/read exception) even while the process is still
  // running; the old isTranscodeDone() returned xcodeDone directly, so the
  // MiniPlayer watchdog saw done + !completeOK (exitValue() throws on a live
  // process -> -1) and tore down/restarted a healthy transcode -- the ~2s
  // 4K->1080p MPEG-4 IP-camera restart / green-frame loop. isTranscodeDone() must stay
  // false while the child is alive, so the watchdog cannot fire.
  @Test
  public void testAliveTranscodeWithDoneFlagIsNotReportedDone() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    FFMPEGTranscoder transcoder = spy(new FFMPEGTranscoder());

    Process liveChild = org.mockito.Mockito.mock(Process.class);
    org.mockito.Mockito.when(liveChild.isAlive()).thenReturn(true);
    org.mockito.Mockito.when(liveChild.exitValue())
        .thenThrow(new IllegalThreadStateException());
    transcoder.xcodeProcess = liveChild;

    // A transient pipe hiccup flips the done flag while the child is still alive.
    transcoder.xcodeDone = true;

    assertFalse(transcoder.isTranscodeDone(),
        "a live ffmpeg child must never be reported as a finished transcode");

    // Once the child actually exits, isTranscodeDone() must report true again so
    // genuine failures still restart.
    org.mockito.Mockito.when(liveChild.isAlive()).thenReturn(false);
    assertTrue(transcoder.isTranscodeDone(),
        "an exited child with the done flag set must report done");
  }

  // --- Companion to the above for the FastMpeg2Reader lazy-start guard. That
  // guard (start the transcode when !isTranscoding() && !isTranscodeDone()) must
  // never fire on a live child. isTranscoding() reads !xcodeDone, which a
  // consumer thread can spuriously flip false on a live process, so the guard
  // now also checks hasLiveProcess(). A live child -- even with xcodeDone set --
  // must report hasLiveProcess()==true so the reader leaves it alone instead of
  // stopTranscode()/startTranscode() churning it (the camera black-screen loop).
  @Test
  public void testHasLiveProcessTrueForAliveChildEvenWhenDoneFlagSet() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    FFMPEGTranscoder transcoder = spy(new FFMPEGTranscoder());

    assertFalse(transcoder.hasLiveProcess(), "no child yet -> not live");

    Process liveChild = org.mockito.Mockito.mock(Process.class);
    org.mockito.Mockito.when(liveChild.isAlive()).thenReturn(true);
    transcoder.xcodeProcess = liveChild;
    transcoder.xcodeDone = true; // spurious done-flag on a still-running child

    assertTrue(transcoder.hasLiveProcess(),
        "a live child must report hasLiveProcess() even with xcodeDone set");
    assertFalse(transcoder.isTranscoding(),
        "isTranscoding() reads !xcodeDone, so the spurious flag makes it false -- "
        + "which is exactly why the reader must consult hasLiveProcess() instead");

    org.mockito.Mockito.when(liveChild.isAlive()).thenReturn(false);
    assertFalse(transcoder.hasLiveProcess(), "an exited child is not live");
  }

  // --- Fix A: yadif auto-add must never collide with a copy-video stage. ---
  // Modern ffmpeg hard-errors "Filtergraph 'yadif' was specified, but codec
  // copy was selected" -> "Error opening output file", killing the process
  // in ~65ms with zero bytes ever emitted -- this is the actual root cause of
  // the reported "~20s slow start" symptom on MPEG2-PS remux (mpeg2tsremux /
  // browserhd_remux / browserhd_copyv / mpeg2psremux / audioonly / DVDAudioOnly
  // all copy video, and are reachable from both legacy media-extender push
  // and the modern NG surface-decision-engine). shouldAutoAddYadif is the
  // single decision helper both call sites in startTranscode() now route
  // through.

  private static sage.media.format.VideoFormat interlacedSrcVideo(int height)
  {
    sage.media.format.VideoFormat v = new sage.media.format.VideoFormat();
    v.setHeight(height);
    v.setInterlaced(true);
    return v;
  }

  @Test
  public void testShouldAutoAddYadif_FalseWhenVideoIsCopy() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    // mpeg2tsremux-shaped copy-video vector, source flagged interlaced.
    java.util.ArrayList<String> vec = new java.util.ArrayList<>(
        java.util.Arrays.asList("-f", "mpegts", "-c:v", "copy", "-c:a", "copy", "-copyts"));
    assertFalse(FFMPEGTranscoder.shouldAutoAddYadif(vec, "-f mpegts -c:v copy -c:a copy -copyts",
        interlacedSrcVideo(480), 480, true));

    // legacy -vcodec copy spelling (mpeg2psremux / audioonly video pass-through).
    java.util.ArrayList<String> legacyVec = new java.util.ArrayList<>(
        java.util.Arrays.asList("-f", "dvd", "-vcodec", "copy", "-acodec", "copy", "-copyts"));
    assertFalse(FFMPEGTranscoder.shouldAutoAddYadif(legacyVec, "-f dvd -vcodec copy -acodec copy -copyts",
        interlacedSrcVideo(480), 480, true));
  }

  @Test
  public void testShouldAutoAddYadif_TrueWhenRealVideoEncode() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    // Real encode paths (dynamic/dynamicts/dynamich264/browserhd full
    // transcode) never set a copy video codec -- yadif must still be added
    // exactly as before this fix when interlaced + no explicit downscale.
    java.util.ArrayList<String> vec = new java.util.ArrayList<>(
        java.util.Arrays.asList("-f", "mpegts", "-c:v", "h264_nvenc", "-b:v", "4M"));
    assertTrue(FFMPEGTranscoder.shouldAutoAddYadif(vec, "-f mpegts -c:v h264_nvenc -b:v 4M",
        interlacedSrcVideo(480), 480, true));
  }

  @Test
  public void testShouldAutoAddYadif_FalseCasesUnrelatedToCopy() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    java.util.ArrayList<String> encVec = new java.util.ArrayList<>(
        java.util.Arrays.asList("-c:v", "h264_nvenc"));

    // Not interlaced.
    sage.media.format.VideoFormat progressive = new sage.media.format.VideoFormat();
    progressive.setHeight(480);
    progressive.setInterlaced(false);
    assertFalse(FFMPEGTranscoder.shouldAutoAddYadif(encVec, "-c:v h264_nvenc", progressive, 480, true));

    // Downscaled below half source height.
    assertFalse(FFMPEGTranscoder.shouldAutoAddYadif(encVec, "-c:v h264_nvenc", interlacedSrcVideo(480), 200, true));

    // User already asked for legacy/modern deinterlace.
    assertFalse(FFMPEGTranscoder.shouldAutoAddYadif(encVec, "-c:v h264_nvenc -deinterlace",
        interlacedSrcVideo(480), 480, true));
    assertFalse(FFMPEGTranscoder.shouldAutoAddYadif(encVec, "-c:v h264_nvenc -vf yadif",
        interlacedSrcVideo(480), 480, true));

    // Property disabled.
    assertFalse(FFMPEGTranscoder.shouldAutoAddYadif(encVec, "-c:v h264_nvenc", interlacedSrcVideo(480), 480, false));

    // No source video format known.
    assertFalse(FFMPEGTranscoder.shouldAutoAddYadif(encVec, "-c:v h264_nvenc", null, 480, true));
  }

  @Test
  public void testIsVideoCopySelected() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    assertTrue(FFMPEGTranscoder.isVideoCopySelected(new java.util.ArrayList<>(
        java.util.Arrays.asList("-c:v", "copy"))));
    assertTrue(FFMPEGTranscoder.isVideoCopySelected(new java.util.ArrayList<>(
        java.util.Arrays.asList("-vcodec", "copy"))));
    assertTrue(FFMPEGTranscoder.isVideoCopySelected(new java.util.ArrayList<>(
        java.util.Arrays.asList("-codec:v", "copy"))));
    assertFalse(FFMPEGTranscoder.isVideoCopySelected(new java.util.ArrayList<>(
        java.util.Arrays.asList("-c:v", "h264_nvenc"))));
    assertFalse(FFMPEGTranscoder.isVideoCopySelected(new java.util.ArrayList<>()));
  }

  // --- browserhd deinterlace: yadif must COMPOSE into an existing -vf, not ---
  // create a second -vf. ffmpeg honours only the last -vf, so a bare append
  // would silently drop the browserhd template's "-vf format=yuv420p" (or a
  // QSV "format=nv12,hwupload"), breaking the encoder's pixel-format upload.
  @Test
  public void testAddOrComposeYadif_mergesIntoExistingVf() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    // Mirrors the browserhd full-encode template: an existing -vf format=yuv420p.
    java.util.ArrayList<String> vec = new java.util.ArrayList<>(
        java.util.Arrays.asList("-c:v", "h264_nvenc", "-vf", "format=yuv420p", "-b:v", "4M"));
    FFMPEGTranscoder.addOrComposeYadif(vec);
    // Exactly ONE -vf, deinterlace first then the original chain.
    assertEquals(java.util.Collections.frequency(vec, "-vf"), 1,
        "There must be exactly one -vf after composing yadif");
    int vf = vec.indexOf("-vf");
    assertEquals(vec.get(vf + 1), "yadif,format=yuv420p",
        "yadif must be prepended into the existing filtergraph so upload/format survives");
  }

  @Test
  public void testAddOrComposeYadif_appendsWhenNoExistingVf() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    java.util.ArrayList<String> vec = new java.util.ArrayList<>(
        java.util.Arrays.asList("-c:v", "h264_nvenc", "-b:v", "4M"));
    FFMPEGTranscoder.addOrComposeYadif(vec);
    assertEquals(java.util.Collections.frequency(vec, "-vf"), 1,
        "A lone -vf yadif must be appended when the command has no existing filtergraph");
    int vf = vec.indexOf("-vf");
    assertEquals(vec.get(vf + 1), "yadif");
  }

  @Test
  public void testAddOrComposeYadif_idempotentWhenAlreadyDeinterlacing() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    java.util.ArrayList<String> vec = new java.util.ArrayList<>(
        java.util.Arrays.asList("-c:v", "h264_nvenc", "-vf", "yadif,format=yuv420p"));
    FFMPEGTranscoder.addOrComposeYadif(vec);
    assertEquals(java.util.Collections.frequency(vec, "-vf"), 1,
        "Composing yadif twice must not add a second -vf");
    int vf = vec.indexOf("-vf");
    assertEquals(vec.get(vf + 1), "yadif,format=yuv420p",
        "An existing yadif filtergraph must be left untouched");
  }

  // --- Fix B: extend probesize/analyzeduration tuning to VOD playback, ---
  // scoped explicitly to the confirmed modern NG copy-family xcodeModes by
  // name (mpeg2tsremux / browserhd_remux / browserhd_copyv) so legacy
  // Placeshifter/older-MiniClient on-demand playback (dynamic/dynamicts/
  // dynamich264/audioonly/mpeg2psremux) is completely unaffected.

  private static sage.media.format.ContainerFormat dummySourceFormat()
  {
    sage.media.format.ContainerFormat cf = new sage.media.format.ContainerFormat();
    cf.setStreamFormats(new BitstreamFormat[] { new VideoFormat() });
    return cf;
  }

  @Test
  public void testShouldApplyVodProbeTuning_TrueForModernCopyFamilyWithKnownSourceFormat() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    FFMPEGTranscoder t = new FFMPEGTranscoder();
    t.activeFile = false;
    t.sourceFormat = dummySourceFormat();

    t.xcodeModeName = "mpeg2tsremux";
    assertTrue(t.shouldApplyVodProbeTuning());
    t.xcodeModeName = "browserhd_remux";
    assertTrue(t.shouldApplyVodProbeTuning());
    t.xcodeModeName = "browserhd_copyv";
    assertTrue(t.shouldApplyVodProbeTuning());
    // Case-insensitive match.
    t.xcodeModeName = "MPEG2TSREMUX";
    assertTrue(t.shouldApplyVodProbeTuning());
  }

  @Test
  public void testShouldApplyVodProbeTuning_FalseForLegacyXcodeModes() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    FFMPEGTranscoder t = new FFMPEGTranscoder();
    t.activeFile = false;
    t.sourceFormat = dummySourceFormat();

    // Legacy Placeshifter/older-MiniClient on-demand playback modes must be
    // completely unaffected by this fix.
    for (String legacy : new String[] { "dynamic", "dynamicts", "dynamich264", "audioonly", "mpeg2psremux" })
    {
      t.xcodeModeName = legacy;
      assertFalse(t.shouldApplyVodProbeTuning(), "legacy mode should not get VOD probe tuning: " + legacy);
    }
  }

  @Test
  public void testShouldApplyVodProbeTuning_FalseWhenActiveFile() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    FFMPEGTranscoder t = new FFMPEGTranscoder();
    t.activeFile = true; // live/channel-change path already handled separately above
    t.sourceFormat = dummySourceFormat();
    t.xcodeModeName = "mpeg2tsremux";
    assertFalse(t.shouldApplyVodProbeTuning());
  }

  @Test
  public void testShouldApplyVodProbeTuning_FalseWhenSourceFormatUnknown() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    FFMPEGTranscoder t = new FFMPEGTranscoder();
    t.activeFile = false;
    t.sourceFormat = null; // fallback to current default (no probe tuning) behavior
    t.xcodeModeName = "mpeg2tsremux";
    assertFalse(t.shouldApplyVodProbeTuning());
  }

  // --- Android-class push MKV video-copy enhancement mode recognition. ---
  @Test
  public void testIsEnhanceableCopyContainerMode_TrueForInlineVideoCopyPushMode() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    FFMPEGTranscoder t = new FFMPEGTranscoder();
    t.xcodeModeName = "container=matroska;videocodec=COPY;audiocodec=COPY";
    assertTrue(t.isEnhanceableCopyContainerMode());
    // Case-insensitive.
    t.xcodeModeName = "CONTAINER=MATROSKA;VIDEOCODEC=COPY;AUDIOCODEC=COPY";
    assertTrue(t.isEnhanceableCopyContainerMode());
  }

  @Test
  public void testIsEnhanceableCopyContainerMode_FalseForNamedAndReencodeModes() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    FFMPEGTranscoder t = new FFMPEGTranscoder();
    // Named legacy modes carry no "container=" token -> must not match.
    for (String legacy : new String[] { "mpeg2tsremux", "browserhd_remux", "audioonly",
        "mpeg2psremux", "dynamic", null })
    {
      t.xcodeModeName = legacy;
      assertFalse(t.isEnhanceableCopyContainerMode(), "should not match: " + legacy);
    }
    // Inline mode that re-encodes video (not a copy family) must not match.
    t.xcodeModeName = "container=matroska;videocodec=H264;audiocodec=COPY";
    assertFalse(t.isEnhanceableCopyContainerMode());
  }

  @Test
  public void testIsEnhanceableCopyContainerMode_NotCoupledToVodProbeTuning() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    FFMPEGTranscoder t = new FFMPEGTranscoder();
    t.activeFile = false;
    t.sourceFormat = dummySourceFormat();
    // The push MKV copy mode is an enhancement rewrite base but must NOT pull
    // itself into the name-scoped VOD probesize/analyzeduration tuning set.
    t.xcodeModeName = "container=matroska;videocodec=COPY;audiocodec=COPY";
    assertTrue(t.isEnhanceableCopyContainerMode());
    assertFalse(t.isModernCopyFamilyXcodeMode());
    assertFalse(t.shouldApplyVodProbeTuning());
  }

  // --- EQ copy-flip must still fire correctly after yadif suppression. ---
  // The audio-side EQ stage (isServerAudioEqActive / maybeDisqualifyAudioCopyForServerEq)
  // is entirely independent of the video-side yadif guard -- confirms Fix A
  // does not regress the serverEQ REMUX-promotion feature (406d1603) on
  // interlaced MPEG2 content.
  @Test
  public void testYadifSuppressionDoesNotAffectServerEqAudioCopyFlip() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    FFMPEGTranscoder t = new FFMPEGTranscoder();
    t.setServerAudioProcessingPlan(activeServerPlan("ac3"));
    java.util.ArrayList<String> vec = new java.util.ArrayList<>(
        java.util.Arrays.asList("-c:v", "copy", "-c:a", "copy", "-f", "mpegts"));

    // Video is copy -> yadif must be suppressed even though the source is interlaced.
    assertFalse(FFMPEGTranscoder.shouldAutoAddYadif(vec, "-c:v copy -c:a copy -f mpegts",
        interlacedSrcVideo(480), 480, true));

    // The independent audio EQ copy-flip must still fire on the very same vector.
    assertTrue(t.isServerAudioEqActive());
    t.maybeDisqualifyAudioCopyForServerEq(vec);
    assertEquals(vec.get(0), "-c:v");
    assertEquals(vec.get(1), "copy"); // video untouched by the audio EQ feature
    assertEquals(vec.get(2), "-c:a");
    assertEquals(vec.get(3), "ac3"); // audio re-encode now engaged for -af to attach to
  }

  @Test
  public void testMaybeStripInapplicableHvc1Tag() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    FFMPEGTranscoder transcoder = new FFMPEGTranscoder();

    // Non-HEVC (H.264) source: the hvc1 tag must be stripped (ffmpeg would abort).
    transcoder.sourceFormat = new ContainerFormat();
    VideoFormat h264 = new VideoFormat();
    h264.setFormatName(sage.media.format.MediaFormat.H264);
    transcoder.sourceFormat.setStreamFormats(new BitstreamFormat[] {h264});

    java.util.ArrayList params = new java.util.ArrayList(java.util.Arrays.asList(
        "-c:v", "copy", "-tag:v", "hvc1", "-acodec", "aac"));
    transcoder.maybeStripInapplicableHvc1Tag(params);
    assertFalse(params.contains("-tag:v"), "-tag:v flag should be removed for H.264 source");
    assertFalse(params.contains("hvc1"), "hvc1 value should be removed for H.264 source");
    // The rest of the command is preserved.
    assertTrue(params.contains("-c:v"));
    assertTrue(params.contains("copy"));
    assertTrue(params.contains("-acodec"));
    assertTrue(params.contains("aac"));

    // HEVC source: the tag is correct and must be left untouched.
    transcoder.sourceFormat = new ContainerFormat();
    VideoFormat hevc = new VideoFormat();
    hevc.setFormatName(sage.media.format.MediaFormat.HEVC);
    transcoder.sourceFormat.setStreamFormats(new BitstreamFormat[] {hevc});

    java.util.ArrayList hevcParams = new java.util.ArrayList(java.util.Arrays.asList(
        "-c:v", "copy", "-tag:v", "hvc1", "-acodec", "aac"));
    transcoder.maybeStripInapplicableHvc1Tag(hevcParams);
    assertTrue(hevcParams.contains("-tag:v"), "-tag:v must be kept for HEVC source");
    assertTrue(hevcParams.contains("hvc1"), "hvc1 must be kept for HEVC source");
  }

  // Builds a ContainerFormat whose primary audio track is the given codec, with
  // an H.264 primary video track (the browserhd_remux source shape).
  private static ContainerFormat h264PlusAudio(String audioCodec)
  {
    ContainerFormat cf = new ContainerFormat();
    VideoFormat vf = new VideoFormat();
    vf.setFormatName(sage.media.format.MediaFormat.H264);
    AudioFormat af = new AudioFormat();
    af.setFormatName(audioCodec);
    cf.setStreamFormats(new BitstreamFormat[] {vf, af});
    return cf;
  }

  private static java.util.ArrayList remuxCopyVec()
  {
    return new java.util.ArrayList(java.util.Arrays.asList("-c:v", "copy", "-c:a", "copy"));
  }

  @Test
  public void testOutputMuxFormatAndMp4FamilyTarget() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    FFMPEGTranscoder t = new FFMPEGTranscoder();

    // Explicit -f in the preset is the source of truth (streamed-to-stdout case:
    // outputFile is null, exactly the browserhd_remux/copyv pull path).
    t.xcodeParams = "-f mp4 -movflags +frag_keyframe+empty_moov+default_base_moof -c:v copy -c:a copy";
    assertEquals(t.outputMuxFormat(), "mp4");
    assertTrue(t.isMp4FamilyMuxTarget(), "streamed -f mp4 must count as an MP4-family mux target");

    // -movflags must NOT be misread as an -f value.
    t.xcodeParams = "-movflags +faststart -f mpegts -c:v copy -c:a copy";
    assertEquals(t.outputMuxFormat(), "mpegts");
    assertFalse(t.isMp4FamilyMuxTarget(), "mpegts is not MP4-family");

    // Whole MP4 family recognized.
    for (String fam : new String[] {"mp4", "m4v", "mov", "3gp", "psp", "ipod"})
    {
      t.xcodeParams = "-f " + fam + " -c:v copy -c:a copy";
      assertTrue(t.isMp4FamilyMuxTarget(), fam + " should be MP4-family");
    }

    // No -f and no output file -> unknown, not MP4-family.
    t.xcodeParams = "-c:v copy -c:a copy";
    assertNull(t.outputMuxFormat());
    assertFalse(t.isMp4FamilyMuxTarget());

    // Falls back to the output filename extension when no explicit -f is present.
    t.xcodeParams = "-c:v copy -c:a copy";
    t.outputFile = new File("/tmp/out.mp4");
    assertEquals(t.outputMuxFormat(), "mp4");
    assertTrue(t.isMp4FamilyMuxTarget());
    t.outputFile = new File("/tmp/out.mkv");
    assertEquals(t.outputMuxFormat(), "mkv");
    assertFalse(t.isMp4FamilyMuxTarget());
  }

  @Test
  public void testNeedsAacAdtstoAscBsf() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    FFMPEGTranscoder t = new FFMPEGTranscoder();

    // THE FIX: AAC stream-copy into stdout-streamed fragmented MP4 (browserhd_remux)
    // must inject aac_adtstoasc, even though outputFile is null.
    t.xcodeParams = "-f mp4 -movflags +frag_keyframe+empty_moov+default_base_moof -c:v copy -c:a copy";
    t.sourceFormat = h264PlusAudio(sage.media.format.MediaFormat.AAC);
    assertTrue(t.needsAacAdtstoAscBsf(remuxCopyVec()),
        "AAC copy into streamed MP4 must require the aac_adtstoasc bitstream filter");

    // AC3 audio: filter would error on non-AAC, so it must NOT be added (this is
    // why US-broadcast AC3 remux has always worked and hid the bug).
    t.sourceFormat = h264PlusAudio(sage.media.format.MediaFormat.AC3);
    assertFalse(t.needsAacAdtstoAscBsf(remuxCopyVec()),
        "AC3 copy must not get aac_adtstoasc");

    // AAC but audio is being re-encoded (not copied): the encoder emits ASC
    // directly, so no bitstream filter.
    t.sourceFormat = h264PlusAudio(sage.media.format.MediaFormat.AAC);
    java.util.ArrayList reencode = new java.util.ArrayList(
        java.util.Arrays.asList("-c:v", "copy", "-c:a", "aac"));
    assertFalse(t.needsAacAdtstoAscBsf(reencode),
        "AAC re-encode must not get aac_adtstoasc");

    // AAC copy but the mux target is MPEG-TS (not MP4): ADTS is correct framing
    // there, so no filter.
    t.xcodeParams = "-f mpegts -c:v copy -c:a copy -copyts";
    assertFalse(t.needsAacAdtstoAscBsf(remuxCopyVec()),
        "AAC copy into MPEG-TS must not get aac_adtstoasc");

    // AAC copy into MP4 but the filter is already present: don't double-add.
    t.xcodeParams = "-f mp4 -c:v copy -c:a copy";
    java.util.ArrayList already = new java.util.ArrayList(
        java.util.Arrays.asList("-c:v", "copy", "-c:a", "copy", "-bsf:a", "aac_adtstoasc"));
    assertFalse(t.needsAacAdtstoAscBsf(already),
        "must not re-add aac_adtstoasc when already present");

    // AAC copy into a file-based MP4 (library remux) still works via the filename
    // fallback -- the original in-scope case is preserved.
    t.xcodeParams = "-c:v copy -c:a copy";
    t.outputFile = new File("/tmp/out.mp4");
    assertTrue(t.needsAacAdtstoAscBsf(remuxCopyVec()),
        "AAC copy into a file .mp4 must still require aac_adtstoasc");
  }

  private void expectSize(int[] sizes, int w, int h)
  {
    assertEquals(sizes[0], w);
    assertEquals(sizes[1], h);
  }

  @Test
  public void testVideoCopyKeyframeAlignSeek() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.remove("ffmpeg/videocopy_kf_align_seek");
    FFMPEGTranscoder transcoder = new FFMPEGTranscoder();

    // Video-copy into fMP4 with no pre-existing seek: a small keyframe-align seek
    // is emitted so the orphan pre-keyframe audio is dropped (fixes the ~2s A/V
    // desync on a mid-GOP tune-in).
    transcoder.xcodeParams = "-f mp4 -movflags +frag_keyframe+empty_moov+default_base_moof"
        + " -frag_duration 500000 -c:v copy -tag:v hvc1 -acodec aac -ac 2 -ar 48000 -b:a 128k";
    transcoder.transcodeStartSeekTime = 0;
    assertEquals(transcoder.videoCopyKeyframeAlignSeek(), "0.1");

    // Already seeking elsewhere: don't add a second seek.
    transcoder.transcodeStartSeekTime = 30000;
    assertNull(transcoder.videoCopyKeyframeAlignSeek());
    transcoder.transcodeStartSeekTime = 0;

    // Not a video-copy fMP4 path (full transcode / TS remux): no keyframe-align seek.
    transcoder.xcodeParams = "-f mp4 -c:v libx264 -acodec aac";
    assertNull(transcoder.videoCopyKeyframeAlignSeek());
    transcoder.xcodeParams = "-f mpegts -c:v copy -c:a copy -copyts";
    assertNull(transcoder.videoCopyKeyframeAlignSeek());

    // Disabled via property (0 / non-positive) -> no seek.
    transcoder.xcodeParams = "-f mp4 -c:v copy -acodec aac";
    Sage.put("ffmpeg/videocopy_kf_align_seek", "0");
    assertNull(transcoder.videoCopyKeyframeAlignSeek());

    // Custom positive value is honored verbatim.
    Sage.put("ffmpeg/videocopy_kf_align_seek", "0.25");
    assertEquals(transcoder.videoCopyKeyframeAlignSeek(), "0.25");
    Sage.remove("ffmpeg/videocopy_kf_align_seek");
  }

  @Test
  public void testResolveAudioResampleAsyncFloorsZeroOnVideoCopy() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.remove("ffmpeg/aresample_async");
    Sage.remove("ffmpeg/aresample_async_ac4");
    Sage.remove("ffmpeg/videocopy_aresample_async_floor");
    Sage.remove("ffmpeg/videocopy_allow_async_zero");
    try
    {
      FFMPEGTranscoder transcoder = new FFMPEGTranscoder();
      transcoder.xcodeParams = "-f mp4 -movflags +frag_keyframe+empty_moov+default_base_moof"
          + " -frag_duration 500000 -c:v copy -tag:v hvc1 -acodec aac -ac 2 -ar 48000 -b:a 128k";

      // Not a video-copy path: an explicit 0 override is honored verbatim (no floor).
      transcoder.xcodeParams = "-f mp4 -c:v libx264 -acodec aac";
      Sage.put("ffmpeg/aresample_async", "0");
      assertEquals(transcoder.resolveAudioResampleAsync(false), "0");
      Sage.remove("ffmpeg/aresample_async");

      // Video-copy fMP4 path, no override configured: normal defaults (1 / 1000) pass
      // through untouched -- the floor only engages when the resolved value is <= 0.
      // NOTE: Sage.get(name, default) persists the default on first access
      // (SageProperties.STORE_DEFAULTS), so each independent "no override" probe below
      // removes both keys immediately beforehand -- otherwise the first probe's
      // Sage.get("ffmpeg/aresample_async", "1") call would permanently write "1" and
      // poison the AC-4 probe's inner Sage.get("ffmpeg/aresample_async", "1000") fallback.
      transcoder.xcodeParams = "-f mp4 -movflags +frag_keyframe+empty_moov+default_base_moof"
          + " -frag_duration 500000 -c:v copy -tag:v hvc1 -acodec aac -ac 2 -ar 48000 -b:a 128k";
      Sage.remove("ffmpeg/aresample_async");
      Sage.remove("ffmpeg/aresample_async_ac4");
      assertEquals(transcoder.resolveAudioResampleAsync(false), "1");
      Sage.remove("ffmpeg/aresample_async");
      Sage.remove("ffmpeg/aresample_async_ac4");
      assertEquals(transcoder.resolveAudioResampleAsync(true), "1000");

      // Video-copy fMP4 path with an inherited/legacy 0 override (the exact live-server
      // condition that silently disabled all audio drift correction on an AC-4 downmix
      // session): the value must be floored back up to a working default.
      Sage.remove("ffmpeg/aresample_async");
      Sage.put("ffmpeg/aresample_async_ac4", "0");
      assertEquals(transcoder.resolveAudioResampleAsync(true), "1000");
      Sage.remove("ffmpeg/aresample_async_ac4");
      Sage.put("ffmpeg/aresample_async", "0");
      assertEquals(transcoder.resolveAudioResampleAsync(false), "1000");

      // Custom floor value is honored.
      Sage.put("ffmpeg/videocopy_aresample_async_floor", "500");
      assertEquals(transcoder.resolveAudioResampleAsync(false), "500");
      assertEquals(transcoder.resolveAudioResampleAsync(true), "500");
      Sage.remove("ffmpeg/videocopy_aresample_async_floor");

      // Explicit kill-switch: operator can still force 0 through on the video-copy path.
      Sage.put("ffmpeg/videocopy_allow_async_zero", "true");
      assertEquals(transcoder.resolveAudioResampleAsync(false), "0");
      assertEquals(transcoder.resolveAudioResampleAsync(true), "0");
    }
    finally
    {
      Sage.remove("ffmpeg/aresample_async");
      Sage.remove("ffmpeg/aresample_async_ac4");
      Sage.remove("ffmpeg/videocopy_aresample_async_floor");
      Sage.remove("ffmpeg/videocopy_allow_async_zero");
    }
  }

  @Test
  public void testGetDynamicMaxVideoKbpsIsLanAware() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.remove("ffmpeg/dynamic_max_video_kbps_lan");
    Sage.remove("ffmpeg/dynamic_max_video_kbps_wan");
    try
    {
      FFMPEGTranscoder transcoder = new FFMPEGTranscoder();

      // Default: WAN-conservative, matching the pre-fix hardcoded ceiling exactly so a
      // client we've never classified as local doesn't silently get a behavior change.
      assertFalse(transcoder.isLocalClient());
      assertEquals(transcoder.getDynamicMaxVideoKbps(), 1500);

      // A LAN client (mcsr.isLocalConnection()==true, wired via MiniPlayer.setLocalClient())
      // gets a substantially higher ceiling by default.
      transcoder.setLocalClient(true);
      assertEquals(transcoder.getDynamicMaxVideoKbps(), 5000);

      // Both ceilings are independently operator-configurable without a code change.
      Sage.put("ffmpeg/dynamic_max_video_kbps_lan", "12000");
      assertEquals(transcoder.getDynamicMaxVideoKbps(), 12000);
      transcoder.setLocalClient(false);
      Sage.put("ffmpeg/dynamic_max_video_kbps_wan", "800");
      assertEquals(transcoder.getDynamicMaxVideoKbps(), 800);
    }
    finally
    {
      Sage.remove("ffmpeg/dynamic_max_video_kbps_lan");
      Sage.remove("ffmpeg/dynamic_max_video_kbps_wan");
    }
  }

  /**
   * Hardware media extenders (HD100/HD200 -- {@code mediaExtender == true},
   * the SAME signal {@code MiniPlayer.legacyH264PushProfileApplies()} uses
   * to EXCLUDE them from the H.264-push profile) must get their OWN,
   * separately configurable LAN ceiling (conservative default 1500,
   * matching their historical baseline) rather than inheriting the
   * desktop-Placeshifter LAN fallback ceiling (5000). This keeps raising
   * the desktop-Placeshifter fallback ceiling from silently also raising
   * old extender hardware's ceiling.
   */
  @Test
  public void testGetDynamicMaxVideoKbpsExtenderCeilingIsSeparate() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.remove("ffmpeg/dynamic_max_video_kbps_lan");
    Sage.remove("ffmpeg/dynamic_max_video_kbps_lan_extender");
    Sage.remove("ffmpeg/dynamic_max_video_kbps_wan");
    try
    {
      FFMPEGTranscoder transcoder = new FFMPEGTranscoder();
      transcoder.setLocalClient(true);

      // Non-extender LAN client (classic desktop Placeshifter fallback): unchanged 5000.
      assertFalse(transcoder.isMediaExtender());
      assertEquals(transcoder.getDynamicMaxVideoKbps(), 5000);

      // Extender LAN client: separate, more conservative default ceiling (1500), NOT 5000.
      transcoder.setMediaExtender(true);
      assertTrue(transcoder.isMediaExtender());
      assertEquals(transcoder.getDynamicMaxVideoKbps(), 1500);

      // Independently operator-configurable from the non-extender LAN ceiling.
      Sage.put("ffmpeg/dynamic_max_video_kbps_lan_extender", "2200");
      assertEquals(transcoder.getDynamicMaxVideoKbps(), 2200);
      Sage.put("ffmpeg/dynamic_max_video_kbps_lan", "9000");
      // Extender still uses its own ceiling, unaffected by the non-extender LAN value.
      assertEquals(transcoder.getDynamicMaxVideoKbps(), 2200);
      transcoder.setMediaExtender(false);
      assertEquals(transcoder.getDynamicMaxVideoKbps(), 9000);

      // Still bandwidth-bound and source-capped regardless of extender status.
      transcoder.setMediaExtender(true);
      ContainerFormat cf = new ContainerFormat();
      cf.setBitrate(1_000_000); // 1000 Kbps source, below the 2200 extender ceiling
      transcoder.sourceFormat = cf;
      assertEquals(transcoder.getDynamicMaxVideoKbps(), 1000);

      // WAN path is untouched by extender status -- it's a purely LAN-side distinction.
      transcoder.sourceFormat = null;
      transcoder.setLocalClient(false);
      assertEquals(transcoder.getDynamicMaxVideoKbps(), 1500);
    }
    finally
    {
      Sage.remove("ffmpeg/dynamic_max_video_kbps_lan");
      Sage.remove("ffmpeg/dynamic_max_video_kbps_lan_extender");
      Sage.remove("ffmpeg/dynamic_max_video_kbps_wan");
    }
  }

  @Test
  public void testGetDynamicMaxFpsIsLanAwareAndSourceCapped() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.remove("ffmpeg/dynamic_max_fps_lan");
    try
    {
      FFMPEGTranscoder transcoder = new FFMPEGTranscoder();
      VideoFormat srcVideo = new VideoFormat();

      // WAN client: unchanged NTSC/PAL behavior regardless of source fps.
      srcVideo.setFps(59.94f);
      assertEquals(transcoder.getDynamicMaxFps(srcVideo), MMC.getInstance().isNTSCVideoFormat() ? 30 : 25);

      // LAN client with a high-fps source: raised to the configurable LAN ceiling (default 60),
      // never above the ceiling even if the source is higher.
      transcoder.setLocalClient(true);
      assertEquals(transcoder.getDynamicMaxFps(srcVideo), 60);

      // LAN client whose SOURCE fps is lower than the LAN ceiling: capped to the source's own
      // cadence -- we should never invent motion/interpolate frames the source doesn't have.
      srcVideo.setFps(24f);
      assertEquals(transcoder.getDynamicMaxFps(srcVideo), 24);

      // No source format available: falls back to the LAN ceiling itself.
      assertEquals(transcoder.getDynamicMaxFps(null), 60);

      // Ceiling is operator-configurable.
      Sage.put("ffmpeg/dynamic_max_fps_lan", "50");
      srcVideo.setFps(59.94f);
      assertEquals(transcoder.getDynamicMaxFps(srcVideo), 50);
    }
    finally
    {
      Sage.remove("ffmpeg/dynamic_max_fps_lan");
    }
  }

  @Test
  public void testGetDynamicMaxVideoKbpsNeverExceedsSourceBitrate() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.remove("ffmpeg/dynamic_max_video_kbps_lan");
    Sage.remove("ffmpeg/dynamic_max_video_kbps_wan");
    try
    {
      FFMPEGTranscoder transcoder = new FFMPEGTranscoder();
      transcoder.setLocalClient(true);

      // No source format known: falls back to the plain LAN ceiling (existing behavior).
      assertEquals(transcoder.getDynamicMaxVideoKbps(), 5000);

      // Source format known but a genuine multi-Mbps HEVC broadcast (e.g. ~12Mbps):
      // well above the LAN ceiling, so the ceiling itself still governs -- recompressing
      // toward a much lower mpeg4 bitrate than source is normal/expected, not an upscale.
      ContainerFormat cf = new ContainerFormat();
      cf.setBitrate(12_000_000);
      transcoder.sourceFormat = cf;
      assertEquals(transcoder.getDynamicMaxVideoKbps(), 5000);

      // Source is a genuinely LOW-bitrate program (e.g. a lightly-encoded SD feed at
      // 3Mbps): never target an OUTPUT bitrate above what the source itself carried --
      // recompressing higher than source doesn't add real detail, it just spends bits
      // the source never had.
      cf.setBitrate(3_000_000);
      assertEquals(transcoder.getDynamicMaxVideoKbps(), 3000);

      // WAN client: the much-lower WAN ceiling (1500) already governs below a normal
      // broadcast bitrate, so the source-bitrate cap is a no-op there (unchanged
      // pre-existing behavior).
      transcoder.setLocalClient(false);
      assertEquals(transcoder.getDynamicMaxVideoKbps(), 1500);
    }
    finally
    {
      Sage.remove("ffmpeg/dynamic_max_video_kbps_lan");
      Sage.remove("ffmpeg/dynamic_max_video_kbps_wan");
    }
  }

  @Test
  public void testSelectDynamicVideoBitrateKbpsIsBandwidthBoundedAndLanAware() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.remove("ffmpeg/dynamic_max_video_kbps_lan");
    Sage.remove("ffmpeg/dynamic_max_video_kbps_wan");
    try
    {
      FFMPEGTranscoder transcoder = new FFMPEGTranscoder();
      transcoder.setLocalClient(true);

      // LAN client, ~10Mbps measured link: bandwidth-derived (10_000_000/2000 = 5000)
      // and the LAN ceiling (5000 default) coincide here -- exactly the real-world
      // reading that drove this fix (a placeshifter on a healthy LAN link).
      assertEquals(transcoder.selectDynamicVideoBitrateKbps(10_000_000L), 5000);

      // LAN client, but a much SLOWER measured link (~3Mbps, e.g. a busy/degraded LAN
      // segment or Wi-Fi): must NOT get the full 5Mbps ceiling -- the measured
      // bandwidth still governs, so this client gets ~1500 Kbps, not 5000. Adaptive
      // means bounded by what's actually available, not a blind constant bump.
      assertEquals(transcoder.selectDynamicVideoBitrateKbps(3_000_000L), 1500);

      // LAN client on an extremely fast link (e.g. 100Mbps): the LAN ceiling still
      // caps it at 5000 -- bandwidth headroom alone never lets the legacy mpeg4 path
      // exceed its configured/source-capped ceiling.
      assertEquals(transcoder.selectDynamicVideoBitrateKbps(100_000_000L), 5000);

      // Never-exceed-source correctness composes here too: a modest-bitrate source
      // (e.g. 2Mbps) caps the ceiling below the LAN default, so even a fast LAN link
      // only pushes toward genuine source quality, not invented detail.
      ContainerFormat cf = new ContainerFormat();
      cf.setBitrate(2_000_000);
      transcoder.sourceFormat = cf;
      assertEquals(transcoder.selectDynamicVideoBitrateKbps(100_000_000L), 2000);
      transcoder.sourceFormat = null;

      // WAN client: unchanged, much lower ceiling governs regardless of a generous
      // (unlikely on a real WAN) measured bandwidth reading.
      transcoder.setLocalClient(false);
      assertEquals(transcoder.selectDynamicVideoBitrateKbps(10_000_000L), 1500);

      // Operator can retune the LAN ceiling without a rebuild; the bandwidth bound
      // still applies on top of it.
      transcoder.setLocalClient(true);
      Sage.put("ffmpeg/dynamic_max_video_kbps_lan", "3000");
      assertEquals(transcoder.selectDynamicVideoBitrateKbps(10_000_000L), 3000);
    }
    finally
    {
      Sage.remove("ffmpeg/dynamic_max_video_kbps_lan");
      Sage.remove("ffmpeg/dynamic_max_video_kbps_wan");
    }
  }

  @Test
  public void testGetDynamicMaxResolutionNeverUpscalesBeyondSource() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.remove("ffmpeg/dynamic_max_width_lan");
    Sage.remove("ffmpeg/dynamic_max_height_lan");
    try
    {
      FFMPEGTranscoder transcoder = new FFMPEGTranscoder();
      VideoFormat srcVideo = new VideoFormat();

      // WAN client with a genuine 1080p source: unchanged historical 1280x720 ceiling
      // (this is the pre-existing hardcoded WAN behavior, preserved exactly).
      srcVideo.setWidth(1920);
      srcVideo.setHeight(1080);
      int[] wanRes = transcoder.getDynamicMaxResolution(srcVideo);
      assertEquals(wanRes[0], 1280);
      assertEquals(wanRes[1], 720);

      // LAN client with the SAME 1080p source: raised toward true source-native detail --
      // this is the actual fix (previously downscaled to 720p even on LAN with headroom).
      transcoder.setLocalClient(true);
      int[] lanRes = transcoder.getDynamicMaxResolution(srcVideo);
      assertEquals(lanRes[0], 1920);
      assertEquals(lanRes[1], 1080);

      // LAN client but source is only 720p: hard-capped at source-native -- never upscale
      // beyond what the source actually provides, even though the LAN ceiling is higher.
      srcVideo.setWidth(1280);
      srcVideo.setHeight(720);
      int[] lanCappedRes = transcoder.getDynamicMaxResolution(srcVideo);
      assertEquals(lanCappedRes[0], 1280);
      assertEquals(lanCappedRes[1], 720);

      // Genuine SD source: passthrough at native size on both WAN and LAN (never upscale
      // SD), matching the historical SD-passthrough exception.
      srcVideo.setWidth(720);
      srcVideo.setHeight(480);
      int[] sdLan = transcoder.getDynamicMaxResolution(srcVideo);
      assertEquals(sdLan[0], 720);
      assertEquals(sdLan[1], 480);
      transcoder.setLocalClient(false);
      int[] sdWan = transcoder.getDynamicMaxResolution(srcVideo);
      assertEquals(sdWan[0], 720);
      assertEquals(sdWan[1], 480);

      // No source format available: falls back to the plain ceiling.
      assertEquals(transcoder.getDynamicMaxResolution(null)[0], 1280);
      assertEquals(transcoder.getDynamicMaxResolution(null)[1], 720);

      // Ceilings are operator-configurable.
      transcoder.setLocalClient(true);
      Sage.put("ffmpeg/dynamic_max_width_lan", "1280");
      Sage.put("ffmpeg/dynamic_max_height_lan", "720");
      srcVideo.setWidth(1920);
      srcVideo.setHeight(1080);
      int[] configuredRes = transcoder.getDynamicMaxResolution(srcVideo);
      assertEquals(configuredRes[0], 1280);
      assertEquals(configuredRes[1], 720);
    }
    finally
    {
      Sage.remove("ffmpeg/dynamic_max_width_lan");
      Sage.remove("ffmpeg/dynamic_max_height_lan");
    }
  }

    private FFMPEGTranscoder hevcTsTranscoder()
    {
      FFMPEGTranscoder t = new FFMPEGTranscoder();
      ContainerFormat cf = new ContainerFormat();
      cf.setFormatName(sage.media.format.MediaFormat.MPEG2_TS);
      VideoFormat vf = new VideoFormat();
      vf.setFormatName(sage.media.format.MediaFormat.HEVC);
      vf.setPrimary(true);
      cf.setStreamFormats(new BitstreamFormat[] { vf });
      t.sourceFormat = cf;
      return t;
    }

    @Test
    public void testNativeHevcHwDecodeArgs() throws Throwable
    {
      TestUtils.initializeSageTVForTesting();
      final String PROP = "multimedia/hwaccel/atsc3_hevc_decode";
      try
      {
        // Explicit "cuda" forces the cuvid decode args on the native ATSC3 HEVC path.
        Sage.put(PROP, "cuda");
        FFMPEGTranscoder t = hevcTsTranscoder();
        assertEquals(t.nativeHevcHwDecodeArgs(),
            java.util.Arrays.asList("-hwaccel", "cuda", "-c:v", "hevc_cuvid"));

        // "off" disables (falls back to software decode).
        Sage.put(PROP, "off");
        assertTrue(hevcTsTranscoder().nativeHevcHwDecodeArgs().isEmpty());
        Sage.put(PROP, "none");
        assertTrue(hevcTsTranscoder().nativeHevcHwDecodeArgs().isEmpty());

        // Non-HEVC source is never engaged, even with "cuda".
        Sage.put(PROP, "cuda");
        FFMPEGTranscoder h264 = new FFMPEGTranscoder();
        ContainerFormat cf = new ContainerFormat();
        cf.setFormatName(sage.media.format.MediaFormat.MPEG2_TS);
        VideoFormat vf = new VideoFormat();
        vf.setFormatName(sage.media.format.MediaFormat.H264);
        vf.setPrimary(true);
        cf.setStreamFormats(new BitstreamFormat[] { vf });
        h264.sourceFormat = cf;
        assertTrue(h264.nativeHevcHwDecodeArgs().isEmpty());

        // No source format -> empty.
        assertTrue(new FFMPEGTranscoder().nativeHevcHwDecodeArgs().isEmpty());

        // browserhd/pull-xcode path (hwaccelDecode set) is left untouched.
        FFMPEGTranscoder browserhd = hevcTsTranscoder();
        browserhd.setHwaccelDecode("cuda");
        assertTrue(browserhd.nativeHevcHwDecodeArgs().isEmpty());

        // PWA/HLS and H.264-push paths are excluded.
        FFMPEGTranscoder hls = hevcTsTranscoder();
        hls.httplsMode = true;
        assertTrue(hls.nativeHevcHwDecodeArgs().isEmpty());
        FFMPEGTranscoder push = hevcTsTranscoder();
        push.pushH264 = true;
        assertTrue(push.nativeHevcHwDecodeArgs().isEmpty());
      }
      finally
      {
        Sage.remove(PROP);
      }
    }

  // --- Audio EQ v1 (sage.audioproc) integration: pure audio-stage overlay. ---
  // These prove the two ONLY things this feature is allowed to do to an
  // xcodeParamsVec already assembled by the existing (untouched) selection
  // logic: (a) disqualify a plain -acodec copy when a server-EQ plan is
  // active, and (b) append (never replace) the plan's -af filtergraph. Every
  // "inactive" case (no plan / wrong location / no filtergraph) must leave
  // the vector byte-for-byte unchanged. There is no separate master flag --
  // activity is gated solely by whether an explicit-request plan was set.

  private static sage.audioproc.AudioProcessingPlan activeServerPlan(String targetCodec)
  {
    return sage.audioproc.AudioProcessingPlan.builder()
        .resolvedLocation(sage.audioproc.AudioProcessingLocation.SERVER)
        .reason("test")
        .filterGraph("volume=2.00dB,equalizer=f=1000:t=q:w=1.0:g=3.00,alimiter=limit=0.98")
        .settingsHash("deadbeefcafebabe")
        .sourceAudioCodec("ac3")
        .targetAudioCodec(targetCodec)
        .sampleRate(48000)
        .channelLayout("stereo")
        .build();
  }

  @Test
  public void testMaybeDisqualifyAudioCopyForServerEq_ReplacesCopyWhenActive() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    FFMPEGTranscoder t = new FFMPEGTranscoder();
    t.setServerAudioProcessingPlan(activeServerPlan("ac3"));
    java.util.ArrayList<String> vec = new java.util.ArrayList<>(
        java.util.Arrays.asList("-vcodec", "copy", "-acodec", "copy", "-f", "mpegts"));
    assertTrue(t.isServerAudioEqActive());
    t.maybeDisqualifyAudioCopyForServerEq(vec);
    // Video copy is untouched -- this feature never touches -vcodec.
    assertEquals(vec.get(0), "-vcodec");
    assertEquals(vec.get(1), "copy");
    // Audio copy is disqualified to the plan's (pass-through) target codec.
    assertEquals(vec.get(2), "-acodec");
    assertEquals(vec.get(3), "ac3");
  }

  @Test
  public void testMaybeDisqualifyAudioCopyForServerEq_NoopWhenNoPlanSet() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    FFMPEGTranscoder t = new FFMPEGTranscoder(); // no plan ever set
    java.util.ArrayList<String> vec = new java.util.ArrayList<>(
        java.util.Arrays.asList("-acodec", "copy"));
    assertFalse(t.isServerAudioEqActive());
    t.maybeDisqualifyAudioCopyForServerEq(vec);
    assertEquals(vec.get(1), "copy"); // untouched -- caller never engaged the feature
  }

  @Test
  public void testMaybeDisqualifyAudioCopyForServerEq_NoopWhenLocationNotServer() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    FFMPEGTranscoder t = new FFMPEGTranscoder();
    t.setServerAudioProcessingPlan(sage.audioproc.AudioProcessingPlan.none("client dsp active", "hash"));
    java.util.ArrayList<String> vec = new java.util.ArrayList<>(
        java.util.Arrays.asList("-acodec", "copy"));
    assertFalse(t.isServerAudioEqActive());
    t.maybeDisqualifyAudioCopyForServerEq(vec);
    assertEquals(vec.get(1), "copy");
  }

  @Test
  public void testMaybeDisqualifyAudioCopyForServerEq_NoopWhenTargetCodecMissing() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    FFMPEGTranscoder t = new FFMPEGTranscoder();
    t.setServerAudioProcessingPlan(activeServerPlan(null)); // buildable plan, but no target codec known
    java.util.ArrayList<String> vec = new java.util.ArrayList<>(
        java.util.Arrays.asList("-acodec", "copy"));
    // Active (SERVER, filtergraph present) but must not corrupt the command
    // line with a blank codec -- leaves copy in place defensively.
    assertTrue(t.isServerAudioEqActive());
    t.maybeDisqualifyAudioCopyForServerEq(vec);
    assertEquals(vec.get(1), "copy");
  }

  @Test
  public void testMaybeAppendServerAudioEqFilter_AppendsToExistingAf() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    FFMPEGTranscoder t = new FFMPEGTranscoder();
    sage.audioproc.AudioProcessingPlan plan = activeServerPlan("eac3");
    t.setServerAudioProcessingPlan(plan);
    java.util.ArrayList<String> vec = new java.util.ArrayList<>(
        java.util.Arrays.asList("-fps_mode", "cfr", "-af", "aresample=async=1000", "-b:a", "640k"));
    t.maybeAppendServerAudioEqFilter(vec);
    int afIdx = vec.indexOf("-af");
    assertEquals(afIdx, 2);
    // Comma-joined onto the END -- the existing aresample=async filter (an
    // in-flight drift-correction/AC-4 transcode) keeps working, EQ layers on top.
    assertEquals(vec.get(afIdx + 1), "aresample=async=1000," + plan.getFilterGraph());
    // Everything else in the vector is untouched.
    assertEquals(vec.get(0), "-fps_mode");
    assertEquals(vec.get(1), "cfr");
    assertEquals(vec.get(4), "-b:a");
    assertEquals(vec.get(5), "640k");
  }

  @Test
  public void testMaybeAppendServerAudioEqFilter_AddsNewAfWhenNoneExists() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    FFMPEGTranscoder t = new FFMPEGTranscoder();
    sage.audioproc.AudioProcessingPlan plan = activeServerPlan("aac");
    t.setServerAudioProcessingPlan(plan);
    java.util.ArrayList<String> vec = new java.util.ArrayList<>(
        java.util.Arrays.asList("-acodec", "aac", "-b:a", "128k"));
    t.maybeAppendServerAudioEqFilter(vec);
    assertEquals(vec.size(), 6);
    assertEquals(vec.get(4), "-af");
    assertEquals(vec.get(5), plan.getFilterGraph());
  }

  @Test
  public void testMaybeAppendServerAudioEqFilter_NoopWhenNoPlanSet() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    FFMPEGTranscoder t = new FFMPEGTranscoder(); // no plan ever set
    java.util.ArrayList<String> vec = new java.util.ArrayList<>(
        java.util.Arrays.asList("-acodec", "aac", "-b:a", "128k"));
    t.maybeAppendServerAudioEqFilter(vec);
    // Byte-for-byte unchanged: no -af added, existing flags untouched.
    assertEquals(vec, java.util.Arrays.asList("-acodec", "aac", "-b:a", "128k"));
  }

  @Test
  public void testIsServerAudioEqActive_RequiresPlanLocationAndFilterGraph() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    FFMPEGTranscoder t = new FFMPEGTranscoder();
    // No plan at all -> inactive.
    assertFalse(t.isServerAudioEqActive());

    t.setServerAudioProcessingPlan(sage.audioproc.AudioProcessingPlan.none("no eq requested", "hash"));
    assertFalse(t.isServerAudioEqActive()); // NONE location

    sage.audioproc.AudioProcessingPlan noGraph = sage.audioproc.AudioProcessingPlan.builder()
        .resolvedLocation(sage.audioproc.AudioProcessingLocation.SERVER)
        .targetAudioCodec("ac3")
        .build(); // SERVER but somehow no filterGraph -- defensive case
    t.setServerAudioProcessingPlan(noGraph);
    assertFalse(t.isServerAudioEqActive());

    t.setServerAudioProcessingPlan(activeServerPlan("ac3"));
    assertTrue(t.isServerAudioEqActive());
  }

  // =======================================================================
  // Item 2: server audio-track preselect (-map 0:a:<rel>) pure helper
  // =======================================================================
  @Test
  public void testServerSelectAudioMapToken_activeSelection()
  {
    assertEquals(FFMPEGTranscoder.serverSelectAudioMapToken(true, 0), "0:a:0",
        "Active server audio select with rel index 0 must map 0:a:0");
    assertEquals(FFMPEGTranscoder.serverSelectAudioMapToken(true, 2), "0:a:2",
        "Active server audio select with rel index 2 must map 0:a:2");
  }

  @Test
  public void testServerSelectAudioMapToken_inactiveOrInvalid_returnsNull()
  {
    assertNull(FFMPEGTranscoder.serverSelectAudioMapToken(false, 1),
        "Client-mode surfaces (serverAudioSelect=false) must map all audio (null token)");
    assertNull(FFMPEGTranscoder.serverSelectAudioMapToken(true, -1),
        "A negative rel index (legacy / unresolved) must map all audio (null token)");
    assertNull(FFMPEGTranscoder.serverSelectAudioMapToken(false, -1),
        "Both inactive and invalid must yield null");
  }
}