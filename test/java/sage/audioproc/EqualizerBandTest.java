package sage.audioproc;

import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class EqualizerBandTest
{
  @Test
  public void testGainClampedToUpperBound()
  {
    EqualizerBand band = new EqualizerBand(1000, 50.0);
    assertEquals(band.getGainDb(), EqualizerBand.MAX_GAIN_DB);
  }

  @Test
  public void testGainClampedToLowerBound()
  {
    EqualizerBand band = new EqualizerBand(1000, -50.0);
    assertEquals(band.getGainDb(), EqualizerBand.MIN_GAIN_DB);
  }

  @Test
  public void testGainWithinRangeIsUnchanged()
  {
    EqualizerBand band = new EqualizerBand(1000, 6.5);
    assertEquals(band.getGainDb(), 6.5);
  }

  @Test
  public void testNaNGainClampsToZero()
  {
    EqualizerBand band = new EqualizerBand(1000, Double.NaN);
    assertEquals(band.getGainDb(), 0.0);
  }

  @Test
  public void testCompareToOrdersByFrequency()
  {
    EqualizerBand low = new EqualizerBand(125, 0);
    EqualizerBand high = new EqualizerBand(4000, 0);
    assertTrue(low.compareTo(high) < 0);
    assertTrue(high.compareTo(low) > 0);
  }

  @Test
  public void testEqualsAndHashCode()
  {
    EqualizerBand a = new EqualizerBand(1000, 3.0);
    EqualizerBand b = new EqualizerBand(1000, 3.0);
    EqualizerBand c = new EqualizerBand(1000, 4.0);
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertNotEquals(a, c);
  }
}
