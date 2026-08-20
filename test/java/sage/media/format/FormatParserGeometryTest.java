package sage.media.format;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import sage.TestUtils;

import static org.testng.Assert.*;

/**
 * Covers the ffmpeg geometry backfill that repairs HEVC streams the internal
 * MPEG parser can describe only as {@code Video[HEVC progressive id=0100]}.
 *
 * The native parser reads the PMT for codec and PID but only derives
 * width/height from sequence headers it knows how to walk, and it does not walk
 * the HEVC SPS. Because {@code getFileFormat} returns the internal result the
 * moment it contains any stream, ffmpeg was never consulted and a 3840x2160
 * ATSC 3.0 recording was stored with no resolution at all.
 */
public class FormatParserGeometryTest
{
  /**
   * MediaFormat's static init reaches sage.Show.ROLE_NAMES, which needs the
   * core resource bundle, so the format classes cannot be touched at all
   * without booting the minimal SageTV statics first.
   */
  @BeforeClass
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
  }

  private static VideoFormat hevcNoGeometry()
  {
    VideoFormat vf = new VideoFormat();
    vf.setFormatName(MediaFormat.HEVC);
    vf.setId("0100");
    return vf;
  }

  private static VideoFormat uhdFromFfmpeg()
  {
    VideoFormat vf = new VideoFormat();
    vf.setFormatName(MediaFormat.HEVC);
    vf.setWidth(3840);
    vf.setHeight(2160);
    vf.setFps(59.94f);
    vf.setArNum(16);
    vf.setArDen(9);
    vf.setBitrate(33400000);
    vf.setColorspace("yuvj420p");
    return vf;
  }

  /** The reported bug: HEVC arrives with codec and PID but no geometry. */
  @Test
  public void testFillsMissingHevcResolution()
  {
    VideoFormat dst = hevcNoGeometry();
    assertEquals(dst.getWidth(), 0);
    assertEquals(dst.getHeight(), 0);

    boolean changed = FormatParser.mergeVideoGeometry(
        new VideoFormat[] { dst }, new BitstreamFormat[] { uhdFromFfmpeg() });

    assertTrue(changed, "merge should report that it filled something");
    assertEquals(dst.getWidth(), 3840);
    assertEquals(dst.getHeight(), 2160);
    assertEquals(dst.getFps(), 59.94f, 0.01f);
    assertEquals(dst.getBitrate(), 33400000);
  }

  /** The PID the native parser resolved must survive the backfill. */
  @Test
  public void testPreservesNativelyParsedIdentity()
  {
    VideoFormat dst = hevcNoGeometry();
    FormatParser.mergeVideoGeometry(
        new VideoFormat[] { dst }, new BitstreamFormat[] { uhdFromFfmpeg() });

    assertEquals(dst.getId(), "0100");
    assertEquals(dst.getFormatName(), MediaFormat.HEVC);
  }

  /**
   * A value the native parser did determine must win over ffmpeg's. The native
   * parse is authoritative where it speaks at all; this is a backfill, not an
   * override.
   */
  @Test
  public void testDoesNotOverwriteValuesTheNativeParserFound()
  {
    VideoFormat dst = hevcNoGeometry();
    dst.setWidth(1920);
    dst.setHeight(1080);

    boolean changed = FormatParser.mergeVideoGeometry(
        new VideoFormat[] { dst }, new BitstreamFormat[] { uhdFromFfmpeg() });

    assertEquals(dst.getWidth(), 1920);
    assertEquals(dst.getHeight(), 1080);
    // fps/AR were still unset, so those are filled and the merge did do work.
    assertTrue(changed);
    assertEquals(dst.getFps(), 59.94f, 0.01f);
  }

  /** Nothing missing means nothing to do, so callers can skip the ffmpeg run. */
  @Test
  public void testReportsNoChangeWhenAlreadyComplete()
  {
    VideoFormat dst = uhdFromFfmpeg();
    assertFalse(FormatParser.mergeVideoGeometry(
        new VideoFormat[] { dst }, new BitstreamFormat[] { uhdFromFfmpeg() }));
  }

  /** Audio streams in the ffmpeg list must not be paired against video slots. */
  @Test
  public void testIgnoresNonVideoStreamsWhenPairing()
  {
    VideoFormat dst = hevcNoGeometry();
    AudioFormat audio = new AudioFormat();
    audio.setFormatName(MediaFormat.AC3);

    FormatParser.mergeVideoGeometry(new VideoFormat[] { dst },
        new BitstreamFormat[] { audio, uhdFromFfmpeg() });

    assertEquals(dst.getWidth(), 3840);
    assertEquals(dst.getHeight(), 2160);
  }

  /** Fewer ffmpeg streams than internal ones must not throw. */
  @Test
  public void testToleratesStreamCountMismatch()
  {
    VideoFormat a = hevcNoGeometry();
    VideoFormat b = hevcNoGeometry();

    FormatParser.mergeVideoGeometry(new VideoFormat[] { a, b },
        new BitstreamFormat[] { uhdFromFfmpeg() });

    assertEquals(a.getWidth(), 3840);
    assertEquals(b.getWidth(), 0);
  }

  @Test
  public void testNullInputsAreSafe()
  {
    assertFalse(FormatParser.mergeVideoGeometry(null, new BitstreamFormat[0]));
    assertFalse(FormatParser.mergeVideoGeometry(new VideoFormat[0], null));
    assertFalse(FormatParser.mergeVideoGeometry(
        new VideoFormat[] { null }, new BitstreamFormat[] { uhdFromFfmpeg() }));
  }

  /**
   * Resolution is what makes the rest of the system work: this is the
   * difference between a 4K recording being treated as unknown-size (and so
   * handled conservatively by transcode and client-capability logic) and being
   * correctly identified as 2160p.
   */
  @Test
  public void testToStringGainsResolutionAfterMerge()
  {
    VideoFormat dst = hevcNoGeometry();
    assertFalse(dst.toString().contains("3840x2160"));

    FormatParser.mergeVideoGeometry(
        new VideoFormat[] { dst }, new BitstreamFormat[] { uhdFromFfmpeg() });

    String s = dst.toString();
    assertTrue(s.contains("3840x2160"), "expected resolution in " + s);
    assertTrue(s.contains("id=0100"), "expected PID retained in " + s);
  }

  /**
   * End-to-end against real ffmpeg output captured from an ATSC 3.0 recording
   * on the dev server. This is the half the merge test cannot cover: that the
   * ffmpeg text actually yields geometry for an HEVC stream, including the
   * decorated codec token ("hevc (Main 10) ([36][0][0][0] / 0x0024)") and the
   * "1920x1080 [SAR 1:1 DAR 16:9]" form that carries the resolution.
   */
  @Test
  public void testParsesGeometryFromRealAtsc3FfmpegOutput()
  {
    String info =
        "Input #0, mpegts, from '/media/sagetv/SageTV9/109_109.1_0510_2207-0.mpg':\n" +
        "  Duration: 00:52:17.68, start: 86390.498300, bitrate: 4936 kb/s\n" +
        "  Program 3 \n" +
        "  Stream #0:0[0x31]: Video: hevc (Main 10) ([36][0][0][0] / 0x0024), " +
        "yuv420p10le(tv, bt709), 1920x1080 [SAR 1:1 DAR 16:9], 59.94 fps, 59.94 tbr, 90k tbn, start 86390.498300\n" +
        "  Stream #0:1[0x32](eng): Audio: ac4 (AC-4 / 0x342D4341), 48000 Hz, 5.1(side), fltp, start 86391.282422\n";

    BitstreamFormat[] streams = FormatParser.extractStreamFormatsFromFFMPEGInfo(info);
    assertNotNull(streams);

    VideoFormat vid = null;
    for (int i = 0; i < streams.length; i++)
      if (streams[i] instanceof VideoFormat) { vid = (VideoFormat) streams[i]; break; }

    assertNotNull(vid, "expected a video stream in the parsed ffmpeg output");
    assertEquals(vid.getFormatName(), MediaFormat.HEVC);
    assertEquals(vid.getWidth(), 1920);
    assertEquals(vid.getHeight(), 1080);
    assertEquals(vid.getFps(), 59.94f, 0.01f);

    // And that geometry is exactly what the backfill hands to a bare HEVC
    // stream from the native parser.
    VideoFormat bare = hevcNoGeometry();
    assertTrue(FormatParser.mergeVideoGeometry(new VideoFormat[] { bare }, streams));
    assertEquals(bare.getWidth(), 1920);
    assertEquals(bare.getHeight(), 1080);
    assertEquals(bare.getId(), "0100");
  }

  /** The DAR is in the same bracket, and was being lost with the resolution. */
  @Test
  public void testRecoversAspectRatioFromInlineBracket()
  {
    String info =
        "Input #0, mpegts, from 'x.mpg':\n" +
        "  Duration: 00:52:17.68, start: 0.0, bitrate: 4936 kb/s\n" +
        "  Stream #0:0[0x31]: Video: hevc (Main 10), yuv420p10le(tv, bt709), " +
        "1920x1080 [SAR 1:1 DAR 16:9], 59.94 fps, 59.94 tbr, 90k tbn\n";

    BitstreamFormat[] streams = FormatParser.extractStreamFormatsFromFFMPEGInfo(info);
    VideoFormat vid = (VideoFormat) streams[0];
    assertEquals(vid.getArNum(), 16);
    assertEquals(vid.getArDen(), 9);
  }

  /** The user-reported case: 4K HEVC reported as having no resolution at all. */
  @Test
  public void testParses4kHevc()
  {
    String info =
        "Input #0, mpegts, from 'x.mpg':\n" +
        "  Duration: 01:00:00.00, start: 0.0, bitrate: 33400 kb/s\n" +
        "  Stream #0:0[0x100]: Video: hevc (Main) (HEVC / 0x43564548), yuvj420p(pc), " +
        "3840x2160 [SAR 1:1 DAR 16:9], 59.94 fps, 59.94 tbr, 90k tbn\n";

    BitstreamFormat[] streams = FormatParser.extractStreamFormatsFromFFMPEGInfo(info);
    VideoFormat vid = (VideoFormat) streams[0];
    assertEquals(vid.getFormatName(), MediaFormat.HEVC);
    assertEquals(vid.getWidth(), 3840);
    assertEquals(vid.getHeight(), 2160);
    assertTrue(vid.toString().contains("3840x2160"), vid.toString());
  }

  /** The older un-bracketed layout must keep working. */
  @Test
  public void testStillParsesLegacyUnbracketedResolution()
  {
    String info =
        "Input #0, mpegts, from 'x.mpg':\n" +
        "  Duration: 01:00:00.00, start: 0.0, bitrate: 5000 kb/s\n" +
        "  Stream #0:0[0x11]: Video: mpeg2video (Main), yuv420p, 1280x720, " +
        "59.94 fps, 59.94 tbr, 90k tbn\n";

    BitstreamFormat[] streams = FormatParser.extractStreamFormatsFromFFMPEGInfo(info);
    VideoFormat vid = (VideoFormat) streams[0];
    assertEquals(vid.getWidth(), 1280);
    assertEquals(vid.getHeight(), 720);
  }

  @Test
  public void testSplitInlineAspect()
  {
    String[] r = FormatParser.splitInlineAspect("1920x1080 [SAR 1:1 DAR 16:9]");
    assertEquals(r[0], "1920x1080");
    assertEquals(r[1], "16:9");

    // No bracket: passes through untouched, no DAR.
    r = FormatParser.splitInlineAspect("1280x720");
    assertEquals(r[0], "1280x720");
    assertNull(r[1]);

    // Bracket without a DAR clause.
    r = FormatParser.splitInlineAspect("640x480 [SAR 1:1]");
    assertEquals(r[0], "640x480");
    assertNull(r[1]);

    r = FormatParser.splitInlineAspect(null);
    assertNull(r[0]);
  }
}
