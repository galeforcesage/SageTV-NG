package sage;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import sage.media.format.AudioFormat;

/**
 * Tests for {@link MiniPlayer#appendCopiedAudioDetail(StringBuilder, AudioFormat, String)}.
 *
 * <p>NG push clients (ExoPlayer / IJK) initialise their audio renderer directly
 * from the {@code [bf=aud;...]} push descriptor. A bare {@code [bf=aud;f=AC3;]}
 * with no {@code sr=}/{@code ch=} makes the client pass 0/0 to the decoder,
 * which fails to open ("Invalid sample rate or channel count") and plays the
 * stream SILENT. The GPU-enhance ({@code mpeg2tsremux}) path re-encodes video to
 * HEVC while copying audio, so its push hint is synthesized here rather than
 * copied from a target format; it must still carry the source audio geometry so
 * enhanced TS playback has sound, exactly like the {@code mpeg2psremux} path.
 */
public class MiniPlayerPushAudioDetailTest
{
  @BeforeClass
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
  }

  private static AudioFormat af(String codec, int ch, int sr, int bps)
  {
    AudioFormat a = new AudioFormat();
    a.setFormatName(codec);
    a.setChannels(ch);
    a.setSamplingRate(sr);
    a.setBitsPerSample(bps);
    return a;
  }

  /** AC3 source copied as AC3: geometry must be appended so the client has sound. */
  @Test
  public void testAppendsGeometryOnCopy()
  {
    StringBuilder fb = new StringBuilder();
    MiniPlayer.appendCopiedAudioDetail(fb, af("AC3", 6, 48000, 16), "AC3");
    assertEquals(fb.toString(), ";ch=6;sr=48000;bps=16");
  }

  /** A true transcode (codecs differ) must NOT advertise the source geometry. */
  @Test
  public void testSkipsOnTranscode()
  {
    StringBuilder fb = new StringBuilder();
    MiniPlayer.appendCopiedAudioDetail(fb, af("AC4", 6, 48000, 16), "EAC3");
    assertEquals(fb.toString(), "");
  }

  /** Null source format is tolerated (no append, no NPE). */
  @Test
  public void testNullSourceFormat()
  {
    StringBuilder fb = new StringBuilder();
    MiniPlayer.appendCopiedAudioDetail(fb, null, "AC3");
    assertEquals(fb.toString(), "");
  }

  /** Zero/unknown geometry fields are omitted individually. */
  @Test
  public void testOmitsUnknownFields()
  {
    StringBuilder fb = new StringBuilder();
    MiniPlayer.appendCopiedAudioDetail(fb, af("AC3", 2, 0, 0), "AC3");
    assertEquals(fb.toString(), ";ch=2");
  }
}
