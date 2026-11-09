package sage.hdhr;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class HttpPullCaptureJobTest
{
  @Test
  public void calculateBytesPerSecondUsesElapsedWindow()
  {
    assertEquals(HttpPullCaptureJob.calculateBytesPerSecond(327680L, 10000L), 32768L);
    assertEquals(HttpPullCaptureJob.calculateBytesPerSecond(0L, 10000L), 0L);
    assertEquals(HttpPullCaptureJob.calculateBytesPerSecond(327680L, 0L), 0L);
  }

  @Test
  public void calculateBytesPerSecondSaturatesOnOverflow()
  {
    assertEquals(HttpPullCaptureJob.calculateBytesPerSecond(Long.MAX_VALUE, 1L),
        Long.MAX_VALUE);
  }
}
