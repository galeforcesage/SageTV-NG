package sage;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;
import org.testng.annotations.BeforeClass;

import sage.enhance.EnhancementTier;
import sage.media.format.AudioFormat;
import sage.media.format.BitstreamFormat;
import sage.media.format.ContainerFormat;
import sage.media.format.MediaFormat;
import sage.media.format.VideoFormat;

/**
 * Tests for {@link MiniPlayer#describeEnhancedWireFormat0}, which rewrites the
 * STREAMINFO / ng_fmt video description to match the bytes an ENHANCED session
 * actually delivers.
 *
 * <p>This exists because of the "one frame then freeze" defect: server video
 * enhancement re-encodes the wire video stream to HEVC (and rescales it for
 * upscaling tiers), but the metadata sent to the client before openURL still
 * described the SOURCE (e.g. MPEG2-Video 1280x720). A client that pre-configures
 * its decoder from that metadata sets up an MPEG2 decoder, receives HEVC 2160p,
 * paints one frame and stalls. The description must follow the wire.
 */
public class MiniPlayerEnhancedStreamInfoTest
{
  /**
   * MediaFormat's static init reaches sage.Show.ROLE_NAMES, which needs the
   * core resource bundle, so the format classes cannot be touched at all
   * without booting the minimal SageTV statics first.
   */
  @BeforeClass
  public void setUp() throws Throwable
  {
    sage.TestUtils.initializeSageTVForTesting();
  }

  private static ContainerFormat source1280x720Interlaced()
  {
    ContainerFormat cf = new ContainerFormat();
    cf.setFormatName(MediaFormat.MPEG2_TS);
    VideoFormat vf = new VideoFormat();
    vf.setFormatName(MediaFormat.MPEG2_VIDEO);
    vf.setWidth(1280);
    vf.setHeight(720);
    vf.setInterlaced(true);
    vf.setFps(59.94f);
    AudioFormat af = new AudioFormat();
    af.setFormatName(MediaFormat.AC3);
    af.setChannels(6);
    cf.setStreamFormats(new BitstreamFormat[] { vf, af });
    return cf;
  }

  @Test
  public void nullTier_returnsSourceUnchanged()
  {
    ContainerFormat src = source1280x720Interlaced();
    assertSame(MiniPlayer.describeEnhancedWireFormat0(src, null), src,
        "A null tier must not alter or copy the source format");
  }

  @Test
  public void noneTier_returnsSourceUnchanged()
  {
    ContainerFormat src = source1280x720Interlaced();
    assertSame(MiniPlayer.describeEnhancedWireFormat0(src, EnhancementTier.NONE), src,
        "NONE means no enhancement; the description must be byte-identical to today");
  }

  @Test
  public void enhance2160p_rewritesVideoToHevc4kProgressive()
  {
    ContainerFormat src = source1280x720Interlaced();
    ContainerFormat out = MiniPlayer.describeEnhancedWireFormat0(src, EnhancementTier.ENHANCE_2160P);

    assertNotSame(out, src, "An active tier must return a copy, never mutate the source");

    VideoFormat vf = out.getVideoFormat();
    assertEquals(vf.getFormatName(), MediaFormat.HEVC, "video codec must reflect the HEVC encode");
    assertEquals(vf.getWidth(), 3840, "2160p tier upscales width to 3840");
    assertEquals(vf.getHeight(), 2160, "2160p tier upscales height to 2160");
    assertFalse(vf.isInterlaced(), "deinterlaced output is progressive");

    // Audio + container are copied through untouched.
    assertEquals(out.getFormatName(), MediaFormat.MPEG2_TS, "container stays MPEG2-TS");
    AudioFormat af = out.getAudioFormat();
    assertEquals(af.getFormatName(), MediaFormat.AC3, "audio is copied through, codec unchanged");
    assertEquals(af.getChannels(), 6, "audio channel count is preserved");
  }

  @Test
  public void deinterlaceOnly_rewritesCodecButKeepsGeometry()
  {
    ContainerFormat src = source1280x720Interlaced();
    ContainerFormat out = MiniPlayer.describeEnhancedWireFormat0(src, EnhancementTier.DEINTERLACE_ONLY);

    VideoFormat vf = out.getVideoFormat();
    assertEquals(vf.getFormatName(), MediaFormat.HEVC,
        "DEINTERLACE_ONLY still re-encodes to HEVC via hevc_nvenc");
    assertEquals(vf.getWidth(), 1280, "DEINTERLACE_ONLY keeps the source width");
    assertEquals(vf.getHeight(), 720, "DEINTERLACE_ONLY keeps the source height");
    assertFalse(vf.isInterlaced(), "deinterlaced output is progressive");
  }

  @Test
  public void sourceFormatIsNeverMutated()
  {
    ContainerFormat src = source1280x720Interlaced();
    MiniPlayer.describeEnhancedWireFormat0(src, EnhancementTier.ENHANCE_2160P);

    VideoFormat vf = src.getVideoFormat();
    assertEquals(vf.getFormatName(), MediaFormat.MPEG2_VIDEO, "source codec must be untouched");
    assertEquals(vf.getWidth(), 1280, "source width must be untouched");
    assertEquals(vf.getHeight(), 720, "source height must be untouched");
    assertTrue(vf.isInterlaced(), "source interlaced flag must be untouched");
  }
}
