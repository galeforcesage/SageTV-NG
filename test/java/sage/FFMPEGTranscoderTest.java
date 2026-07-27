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

    private void expectSize(int[] sizes, int w, int h)
  {
    assertEquals(sizes[0], w);
    assertEquals(sizes[1], h);
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