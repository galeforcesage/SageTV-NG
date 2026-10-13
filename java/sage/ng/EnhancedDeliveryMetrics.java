package sage.ng;

/**
 * Rolling media-time delivery measurement for an enhanced transcode.
 *
 * <p>Callers supply the monotonic wall clock and FFmpeg media clock. Samples
 * taken while downstream backpressure is active reset the window because they
 * describe intentional flow control, not producer capacity.
 */
public final class EnhancedDeliveryMetrics
{
  public enum BackpressureEvent { NONE, ENTERED, REMINDER, RECOVERED }

  public static final class RateSample
  {
    public final long wallMillis;
    public final long mediaMillis;
    public final double mediaRate;

    RateSample(long wallMillis, long mediaMillis)
    {
      this.wallMillis = wallMillis;
      this.mediaMillis = mediaMillis;
      this.mediaRate = wallMillis > 0L ? (double) mediaMillis / (double) wallMillis : 0.0;
    }
  }

  private final long reportIntervalMillis;
  private long windowWallStart;
  private long windowMediaStart;
  private long backpressureStart;
  private long backpressureLastLog;
  private long recoveredDuration;

  public EnhancedDeliveryMetrics(long reportIntervalMillis)
  {
    this.reportIntervalMillis = Math.max(1000L, reportIntervalMillis);
  }

  public synchronized RateSample sample(long nowMillis, long mediaMillis, boolean excluded)
  {
    if (excluded || nowMillis <= 0L || mediaMillis < 0L)
    {
      resetRate();
      return null;
    }
    if (windowWallStart == 0L || nowMillis <= windowWallStart || mediaMillis < windowMediaStart)
    {
      windowWallStart = nowMillis;
      windowMediaStart = mediaMillis;
      return null;
    }
    long wall = nowMillis - windowWallStart;
    if (wall < reportIntervalMillis) return null;
    RateSample result = new RateSample(wall, mediaMillis - windowMediaStart);
    windowWallStart = nowMillis;
    windowMediaStart = mediaMillis;
    return result;
  }

  public synchronized BackpressureEvent updateBackpressure(long nowMillis, boolean full)
  {
    if (full)
    {
      if (backpressureStart == 0L)
      {
        backpressureStart = backpressureLastLog = nowMillis;
        return BackpressureEvent.ENTERED;
      }
      if (nowMillis - backpressureLastLog >= reportIntervalMillis)
      {
        backpressureLastLog = nowMillis;
        return BackpressureEvent.REMINDER;
      }
      return BackpressureEvent.NONE;
    }
    if (backpressureStart != 0L)
    {
      recoveredDuration = Math.max(0L, nowMillis - backpressureStart);
      backpressureStart = backpressureLastLog = 0L;
      return BackpressureEvent.RECOVERED;
    }
    return BackpressureEvent.NONE;
  }

  public synchronized long getBackpressureDuration(long nowMillis)
  {
    return backpressureStart == 0L ? recoveredDuration : Math.max(0L, nowMillis - backpressureStart);
  }

  public synchronized void reset()
  {
    resetRate();
    backpressureStart = backpressureLastLog = recoveredDuration = 0L;
  }

  private void resetRate()
  {
    windowWallStart = windowMediaStart = 0L;
  }
}
