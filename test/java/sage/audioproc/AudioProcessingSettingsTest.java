package sage.audioproc;

import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.List;

import static org.testng.Assert.*;

public class AudioProcessingSettingsTest
{
  @Test
  public void testDisabledConstantIsNoop()
  {
    assertTrue(AudioProcessingSettings.DISABLED.isEffectivelyNoop());
    assertEquals(AudioProcessingSettings.DISABLED.getLocation(), AudioProcessingLocation.NONE);
  }

  @Test
  public void testPreampClampedToBounds()
  {
    AudioProcessingSettings settings = AudioProcessingSettings.builder().preampDb(100).build();
    assertEquals(settings.getPreampDb(), AudioProcessingSettings.MAX_PREAMP_DB);

    settings = AudioProcessingSettings.builder().preampDb(-100).build();
    assertEquals(settings.getPreampDb(), AudioProcessingSettings.MIN_PREAMP_DB);
  }

  @Test
  public void testBandsAreSortedByFrequency()
  {
    List<EqualizerBand> unsorted = Arrays.asList(
        new EqualizerBand(4000, 1),
        new EqualizerBand(125, 2),
        new EqualizerBand(1000, 3));
    AudioProcessingSettings settings = AudioProcessingSettings.builder().bands(unsorted).build();
    List<EqualizerBand> sorted = settings.getBands();
    assertEquals(sorted.size(), 3);
    assertEquals(sorted.get(0).getFrequencyHz(), 125.0);
    assertEquals(sorted.get(1).getFrequencyHz(), 1000.0);
    assertEquals(sorted.get(2).getFrequencyHz(), 4000.0);
  }

  @Test
  public void testBandsListIsUnmodifiable()
  {
    AudioProcessingSettings settings = AudioProcessingSettings.builder()
        .addBand(new EqualizerBand(1000, 1)).build();
    try
    {
      settings.getBands().add(new EqualizerBand(2000, 1));
      fail("expected UnsupportedOperationException");
    }
    catch (UnsupportedOperationException expected)
    {
      // expected
    }
  }

  @Test
  public void testIsEffectivelyNoopFalseWhenEqEnabled()
  {
    AudioProcessingSettings settings = AudioProcessingSettings.builder().eqEnabled(true).build();
    assertFalse(settings.isEffectivelyNoop());
  }

  @Test
  public void testIsEffectivelyNoopFalseWhenNightModeOn()
  {
    AudioProcessingSettings settings = AudioProcessingSettings.builder()
        .nightMode(new NightModeSettings(NightModeMode.LOUDNESS_LEVELING, NightModeIntensity.MEDIUM))
        .build();
    assertFalse(settings.isEffectivelyNoop());
  }

  @Test
  public void testSettingsHashDeterministicForEqualSettings()
  {
    AudioProcessingSettings a = AudioProcessingSettings.builder()
        .location(AudioProcessingLocation.SERVER)
        .eqEnabled(true)
        .preampDb(2.0)
        .addBand(new EqualizerBand(1000, 3.0))
        .addBand(new EqualizerBand(125, -2.0))
        .build();
    AudioProcessingSettings b = AudioProcessingSettings.builder()
        .location(AudioProcessingLocation.SERVER)
        .eqEnabled(true)
        .preampDb(2.0)
        // note: added in a different order than 'a' -- hash must not depend on insertion order
        .addBand(new EqualizerBand(125, -2.0))
        .addBand(new EqualizerBand(1000, 3.0))
        .build();
    assertEquals(a.computeSettingsHash(), b.computeSettingsHash());
  }

  @Test
  public void testSettingsHashDiffersWhenGainDiffers()
  {
    AudioProcessingSettings a = AudioProcessingSettings.builder().addBand(new EqualizerBand(1000, 3.0)).build();
    AudioProcessingSettings b = AudioProcessingSettings.builder().addBand(new EqualizerBand(1000, 4.0)).build();
    assertNotEquals(a.computeSettingsHash(), b.computeSettingsHash());
  }

  @Test
  public void testNullNightModeDefaultsToOff()
  {
    AudioProcessingSettings settings = AudioProcessingSettings.builder().nightMode(null).build();
    assertEquals(settings.getNightMode(), NightModeSettings.OFF);
  }
}
