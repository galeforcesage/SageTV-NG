package sage.hdhr;

import org.testng.annotations.Test;

import java.util.Arrays;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;

public class HDHomeRunVariantPairingTest
{
  @Test
  public void findMajorOffsetTwinPairsMatchingVirtualChannel()
  {
    HDHomeRunLineup.Entry atsc1 = entry("2.1", "MPEG2");
    HDHomeRunLineup.Entry atsc3 = entry("102.1", "HEVC");

    assertSame(ChannelVariantAttacher.findMajorOffsetTwin(
        Arrays.asList(atsc1, atsc3), atsc3), atsc1);
  }

  @Test
  public void findMajorOffsetTwinDoesNotPairDifferentMinor()
  {
    HDHomeRunLineup.Entry atsc1 = entry("2.2", "MPEG2");
    HDHomeRunLineup.Entry atsc3 = entry("102.1", "HEVC");

    assertNull(ChannelVariantAttacher.findMajorOffsetTwin(
        Arrays.asList(atsc1, atsc3), atsc3));
  }

  @Test
  public void toGuideNumberRemovesPhysicalRfPrefix()
  {
    assertEquals(HDHomeRunLineup.toGuideNumber("19-2-1"), "2.1");
  }

  @Test
  public void toGuideNumberConvertsMajorMinorTune()
  {
    assertEquals(HDHomeRunLineup.toGuideNumber("102-1"), "102.1");
  }

  private static HDHomeRunLineup.Entry entry(String guideNumber, String videoCodec)
  {
    HDHomeRunLineup.Entry entry = new HDHomeRunLineup.Entry();
    entry.guideNumber = guideNumber;
    entry.videoCodec = videoCodec;
    return entry;
  }
}
