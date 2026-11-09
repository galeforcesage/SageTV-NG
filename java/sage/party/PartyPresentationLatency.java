package sage.party;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Robust rolling presentation-latency model for one Party participant.
 */
final class PartyPresentationLatency
{
  private static final int WINDOW_SIZE = 9;
  private static final int MIN_SAMPLES = 3;
  private static final long MAX_SAMPLE_MS = 10_000;
  private static final long MAX_PREDICTION_MS = 5_000;

  private final Window preparation = new Window();
  private final Window reveal = new Window();

  void preparation(long latencyMs)
  {
    preparation.add(latencyMs);
  }

  void reveal(long latencyMs)
  {
    reveal.add(latencyMs);
  }

  boolean confident()
  {
    return preparation.confident() && reveal.confident();
  }

  long predictedTotalMs()
  {
    if (!confident()) return 0;
    return Math.min(MAX_PREDICTION_MS, preparation.median() + reveal.median());
  }

  long uncertaintyMs()
  {
    if (!confident()) return MAX_PREDICTION_MS;
    return Math.min(MAX_PREDICTION_MS, preparation.uncertainty() + reveal.uncertainty());
  }

  int preparationSamples()
  {
    return preparation.size();
  }

  int revealSamples()
  {
    return reveal.size();
  }

  private static final class Window
  {
    private final long[] values = new long[WINDOW_SIZE];
    private int size;
    private int next;

    void add(long value)
    {
      if (value < 0 || value > MAX_SAMPLE_MS) return;
      values[next] = value;
      next = (next + 1) % values.length;
      if (size < values.length) size++;
    }

    boolean confident()
    {
      return size >= MIN_SAMPLES;
    }

    int size()
    {
      return size;
    }

    long median()
    {
      ArrayList<Long> sorted = sortedValues();
      return median(sorted);
    }

    long uncertainty()
    {
      ArrayList<Long> sorted = sortedValues();
      long center = median(sorted);
      ArrayList<Long> deviations = new ArrayList<>(sorted.size());
      for (long value : sorted) deviations.add(Math.abs(value - center));
      Collections.sort(deviations);
      return Math.max(1, Math.round(1.4826 * median(deviations)));
    }

    private ArrayList<Long> sortedValues()
    {
      ArrayList<Long> sorted = new ArrayList<>(size);
      for (int i = 0; i < size; i++) sorted.add(values[i]);
      Collections.sort(sorted);
      return sorted;
    }

    private static long median(ArrayList<Long> sorted)
    {
      int middle = sorted.size() / 2;
      if ((sorted.size() & 1) != 0) return sorted.get(middle);
      return (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }
  }
}
