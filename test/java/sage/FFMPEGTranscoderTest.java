package sage;

import org.testng.annotations.Test;
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
      assertEquals(transcoder.getDynamicMaxVideoKbps(), 8000);

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
}