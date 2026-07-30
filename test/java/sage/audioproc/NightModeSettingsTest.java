package sage.audioproc;

import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class NightModeSettingsTest
{
  @Test
  public void testDynamicRangeCompressionIsServerExecutable()
  {
    assertTrue(NightModeMode.DYNAMIC_RANGE_COMPRESSION.isServerExecutable());
  }

  @Test
  public void testLoudnessLevelingIsServerExecutable()
  {
    assertTrue(NightModeMode.LOUDNESS_LEVELING.isServerExecutable());
  }

  @Test
  public void testPlatformNightModeIsNotServerExecutable()
  {
    assertFalse(NightModeMode.PLATFORM_NIGHT_MODE.isServerExecutable());
  }

  @Test
  public void testOffIsNotServerExecutable()
  {
    assertFalse(NightModeMode.OFF.isServerExecutable());
  }

  @Test
  public void testFromWireUnknownValueDefaultsToOff()
  {
    assertEquals(NightModeMode.fromWire("bogus_value"), NightModeMode.OFF);
    assertEquals(NightModeMode.fromWire(null), NightModeMode.OFF);
    assertEquals(NightModeMode.fromWire(""), NightModeMode.OFF);
  }

  @Test
  public void testFromWireKnownValueRoundTrips()
  {
    assertEquals(NightModeMode.fromWire("loudness_leveling"), NightModeMode.LOUDNESS_LEVELING);
    assertEquals(NightModeMode.fromWire("PLATFORM_NIGHT_MODE"), NightModeMode.PLATFORM_NIGHT_MODE);
  }

  @Test
  public void testIntensityFromWireUnknownDefaultsToLow()
  {
    assertEquals(NightModeIntensity.fromWire("bogus"), NightModeIntensity.LOW);
    assertEquals(NightModeIntensity.fromWire(null), NightModeIntensity.LOW);
  }

  @Test
  public void testIntensityFromWireKnownValueRoundTrips()
  {
    assertEquals(NightModeIntensity.fromWire("high"), NightModeIntensity.HIGH);
  }

  @Test
  public void testNullConstructorArgumentsDefaultSafely()
  {
    NightModeSettings settings = new NightModeSettings(null, null);
    assertEquals(settings.getMode(), NightModeMode.OFF);
    assertEquals(settings.getIntensity(), NightModeIntensity.LOW);
    assertTrue(settings.isOff());
  }

  @Test
  public void testLocationFromWireUnknownDefaultsToNone()
  {
    assertEquals(AudioProcessingLocation.fromWire("bogus"), AudioProcessingLocation.NONE);
    assertEquals(AudioProcessingLocation.fromWire(null), AudioProcessingLocation.NONE);
    assertEquals(AudioProcessingLocation.fromWire("server"), AudioProcessingLocation.SERVER);
    assertEquals(AudioProcessingLocation.fromWire("CLIENT"), AudioProcessingLocation.CLIENT);
  }
}
