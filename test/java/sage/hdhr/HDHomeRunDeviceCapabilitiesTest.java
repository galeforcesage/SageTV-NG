package sage.hdhr;

import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class HDHomeRunDeviceCapabilitiesTest
{
  @Test
  public void parseCurrentFlexFeatures()
  {
    HDHomeRunDeviceCapabilities capabilities = HDHomeRunDeviceCapabilities.parse(
        "channelmap: us-bcast us-cable\n"
            + "modulation: atsc3 8vsb qam256 qam64\n"
            + "auto-modulation: auto auto6t auto6c qam\n");

    assertTrue(capabilities.supports("modulation", "atsc3"));
    assertTrue(capabilities.supports("modulation", "qam64"));
    assertFalse(capabilities.supports("modulation", "dvbt2"));
  }

  @Test
  public void preserveUnknownCategoriesAndValues()
  {
    HDHomeRunDeviceCapabilities capabilities = HDHomeRunDeviceCapabilities.parse(
        "future-radio: wave9 wave10\n");

    assertEquals(capabilities.getValues("future-radio"),
        new LinkedHashSet<String>(Arrays.asList("wave9", "wave10")));
    assertTrue(capabilities.asMap().containsKey("future-radio"));
  }

  @Test
  public void missingFeaturesProduceEmptyCapabilities()
  {
    assertTrue(HDHomeRunDeviceCapabilities.parse(null).isEmpty());
    assertTrue(HDHomeRunDeviceCapabilities.parse("").isEmpty());
  }
}
