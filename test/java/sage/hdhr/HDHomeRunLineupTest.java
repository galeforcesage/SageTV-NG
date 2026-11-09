package sage.hdhr;

import org.testng.annotations.Test;

import sage.ChannelQuality;

import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class HDHomeRunLineupTest
{
  @Test
  public void parseLegacyLineupDefaultsMissingMetadata()
  {
    Map<String, HDHomeRunLineup.Entry> entries = HDHomeRunLineup.parseLineupJson(
        "[{\"GuideNumber\":\"5.1\",\"GuideName\":\"Legacy\","
            + "\"URL\":\"http://tuner/auto/v5.1\"}]");

    HDHomeRunLineup.Entry entry = entries.get("5.1");
    assertEquals(entry.quality, ChannelQuality.UNKNOWN);
    assertEquals(entry.enabled, HDHomeRunLineup.Flag.UNKNOWN);
    assertEquals(entry.subscribed, HDHomeRunLineup.Flag.UNKNOWN);
    assertTrue(entry.isAvailable(), "missing availability fields must remain compatible");
  }

  @Test
  public void parseHdAndUhdQuality()
  {
    Map<String, HDHomeRunLineup.Entry> entries = HDHomeRunLineup.parseLineupJson(
        "[{\"GuideNumber\":\"5.1\",\"HD\":1},"
            + "{\"GuideNumber\":\"105.1\",\"HD\":1,\"UHD\":1}]");

    assertEquals(entries.get("5.1").quality, ChannelQuality.HD);
    assertEquals(entries.get("105.1").quality, ChannelQuality.UHD,
        "UHD must take precedence when firmware emits both flags");
  }

  @Test
  public void parseAtsc3CodecsDoesNotInventUhd()
  {
    HDHomeRunLineup.Entry entry = HDHomeRunLineup.parseLineupJson(
        "[{\"GuideNumber\":\"105.1\",\"VideoCodec\":\"HEVC\","
            + "\"AudioCodec\":\"AC4\"}]").get("105.1");

    assertEquals(entry.videoCodec, "HEVC");
    assertEquals(entry.audioCodec, "AC4");
    assertEquals(entry.quality, ChannelQuality.UNKNOWN,
        "ATSC 3.0 codecs do not imply UHD");
  }

  @Test
  public void parseDrmDisabledAndUnsubscribedChannel()
  {
    HDHomeRunLineup.Entry entry = HDHomeRunLineup.parseLineupJson(
        "[{\"GuideNumber\":\"105.2\",\"DRM\":1,\"Enabled\":false,"
            + "\"Subscribed\":0}]").get("105.2");

    assertTrue(entry.drm);
    assertEquals(entry.enabled, HDHomeRunLineup.Flag.FALSE);
    assertEquals(entry.subscribed, HDHomeRunLineup.Flag.FALSE);
    assertFalse(entry.isAvailable());
  }

  @Test
  public void parseUnknownFutureFieldsWithoutFailure()
  {
    Map<String, HDHomeRunLineup.Entry> entries = HDHomeRunLineup.parseLineupJson(
        "[{\"GuideNumber\":\"7.1\",\"SD\":1,\"FutureQuality\":\"8K\","
            + "\"FutureObject\":{\"mode\":\"new\"}}]");

    assertEquals(entries.size(), 1);
    assertEquals(entries.get("7.1").quality, ChannelQuality.SD);
  }
}
