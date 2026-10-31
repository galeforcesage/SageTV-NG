package sage;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class ConnectionProgressTest
{
  @Test
  public void idleBudgetExpiresWithoutProgress()
  {
    ConnectionProgress progress = new ConnectionProgress(1000L);
    assertEquals(progress.remainingWait(1000L, 4000L, 5000L, 30000L), 2000L);
    assertTrue(progress.expired(1000L, 6000L, 5000L, 30000L));
  }

  @Test
  public void byteProgressExtendsIdleBudget()
  {
    ConnectionProgress progress = new ConnectionProgress(1000L);
    progress.markBytes(5000L, 8L);
    assertEquals(progress.remainingWait(1000L, 6000L, 5000L, 30000L), 4000L);
    assertFalse(progress.expired(1000L, 9999L, 5000L, 30000L));
    assertTrue(progress.expired(1000L, 10000L, 5000L, 30000L));
  }

  @Test
  public void absoluteCapWinsDespiteContinuingProgress()
  {
    ConnectionProgress progress = new ConnectionProgress(0L);
    progress.markMessage(9000L);
    assertEquals(progress.remainingWait(0L, 9000L, 5000L, 10000L), 1000L);
    progress.markMessage(9999L);
    assertTrue(progress.expired(0L, 10000L, 5000L, 10000L));
  }

  @Test
  public void highLatencyProgressSurvivesOldAndroidBoundary()
  {
    ConnectionProgress progress = new ConnectionProgress(0L);
    progress.markBytes(3100L, 8L);
    progress.markBytes(12000L, 16L);
    progress.markMessage(45000L);

    assertFalse(progress.expired(0L, 50000L, 60000L, 120000L));
    assertEquals(progress.remainingWait(0L, 50000L, 60000L, 120000L), 55000L);
  }

  @Test
  public void progressBeforeWaitDoesNotExtendNewWait()
  {
    ConnectionProgress progress = new ConnectionProgress(0L);
    progress.markBytes(1000L, 4L);
    assertEquals(progress.remainingWait(5000L, 6000L, 3000L, 10000L), 2000L);
  }

  @Test
  public void countersAndLatencySamplesAreTracked()
  {
    ConnectionProgress progress = new ConnectionProgress(0L);
    progress.markBytes(100L, 10L);
    progress.markBytes(200L, 5L);
    progress.markMessage(300L);
    progress.recordRoundTrip(400L, 800L);
    progress.recordRoundTrip(500L, 1200L);

    assertEquals(progress.getBytes(), 15L);
    assertEquals(progress.getMessages(), 1L);
    assertEquals(progress.getSmoothedRoundTripMs(), 850L);
    assertEquals(progress.getRoundTripVariationMs(), 400L);
    assertEquals(progress.getLastProgressAtMs(), 500L);
  }

  @Test
  public void countersSaturateInsteadOfOverflowing()
  {
    ConnectionProgress progress = new ConnectionProgress(0L);
    progress.markBytes(1L, Long.MAX_VALUE);
    progress.markBytes(2L, 1L);
    assertEquals(progress.getBytes(), Long.MAX_VALUE);
  }
}
