package sage.ng;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

import org.testng.annotations.Test;

public class EnhancedDeliveryMetricsTest
{
  @Test
  public void reportsMediaRateAtWindowBoundary()
  {
    EnhancedDeliveryMetrics metrics = new EnhancedDeliveryMetrics(5000L);
    assertNull(metrics.sample(1000L, 2000L, false));
    assertNull(metrics.sample(5999L, 6974L, false));
    EnhancedDeliveryMetrics.RateSample sample = metrics.sample(6000L, 6975L, false);
    assertEquals(sample.wallMillis, 5000L);
    assertEquals(sample.mediaMillis, 4975L);
    assertEquals(sample.mediaRate, 0.995, 0.000001);
  }

  @Test
  public void backpressureAndClockRegressionResetRateWindow()
  {
    EnhancedDeliveryMetrics metrics = new EnhancedDeliveryMetrics(5000L);
    assertNull(metrics.sample(1000L, 1000L, false));
    assertNull(metrics.sample(7000L, 7000L, true));
    assertNull(metrics.sample(8000L, 8000L, false));
    assertNull(metrics.sample(9000L, 10L, false));
    assertNull(metrics.sample(13000L, 4010L, false));
    assertEquals(metrics.sample(14000L, 5010L, false).mediaRate, 1.0, 0.000001);
  }

  @Test
  public void backpressureLogsTransitionsAndBoundedReminders()
  {
    EnhancedDeliveryMetrics metrics = new EnhancedDeliveryMetrics(5000L);
    assertEquals(metrics.updateBackpressure(1000L, true),
        EnhancedDeliveryMetrics.BackpressureEvent.ENTERED);
    assertEquals(metrics.updateBackpressure(5999L, true),
        EnhancedDeliveryMetrics.BackpressureEvent.NONE);
    assertEquals(metrics.updateBackpressure(6000L, true),
        EnhancedDeliveryMetrics.BackpressureEvent.REMINDER);
    assertEquals(metrics.updateBackpressure(9000L, false),
        EnhancedDeliveryMetrics.BackpressureEvent.RECOVERED);
    assertEquals(metrics.getBackpressureDuration(9000L), 8000L);
    assertEquals(metrics.updateBackpressure(9001L, false),
        EnhancedDeliveryMetrics.BackpressureEvent.NONE);
  }
}
