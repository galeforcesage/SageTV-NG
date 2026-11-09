package sage;

import org.testng.annotations.Test;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class ChannelVariantTest
{
  private static final int TEST_STATION_ID = 987654321;

  @BeforeMethod
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    ChannelVariants.clear(TEST_STATION_ID);
  }

  @AfterMethod
  public void tearDown()
  {
    ChannelVariants.clear(TEST_STATION_ID);
  }

  @Test
  public void persistenceRoundTripPreservesQualityAndDrm()
  {
    ChannelVariant original = new ChannelVariant(ChannelVariant.TYPE_ATSC3,
        "HEVC", "AC4", "105.1", "10a141b7", true, ChannelQuality.UHD);

    ChannelVariant restored = ChannelVariant.fromPersistedString(
        original.toPersistedString());

    assertEquals(restored.getSourceQuality(), ChannelQuality.UHD);
    assertTrue(restored.isDrm());
    assertEquals(restored.getVideoCodecHint(), "HEVC");
  }

  @Test
  public void legacyPersistenceDefaultsNewFields()
  {
    ChannelVariant restored = ChannelVariant.fromPersistedString(
        "ATSC1|MPEG2|AC3|5.1|10a141b7");

    assertEquals(restored.getSourceQuality(), ChannelQuality.UNKNOWN);
    assertFalse(restored.isDrm());
  }

  @Test
  public void addRefreshesMetadataForMatchingVariant()
  {
    ChannelVariants.add(TEST_STATION_ID, new ChannelVariant(
        ChannelVariant.TYPE_ATSC3, "HEVC", "AC4", "105.1", "10a141b7",
        false, ChannelQuality.HD));
    ChannelVariants.add(TEST_STATION_ID, new ChannelVariant(
        ChannelVariant.TYPE_ATSC3, "HEVC", "AC4", "105.1", "10a141b7",
        true, ChannelQuality.UHD));

    ChannelVariant refreshed = ChannelVariants.firstAtsc3(TEST_STATION_ID);
    assertEquals(ChannelVariants.forStation(TEST_STATION_ID).size(), 1);
    assertEquals(refreshed.getSourceQuality(), ChannelQuality.UHD);
    assertTrue(refreshed.isDrm());
  }
}
