package sage.party;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Per-participant four-timestamp clock model, independent of wall time and addresses. */
public final class PartyClock
{
  private record Sample(long at, double offset, long delay) {}
  private final List<Sample> samples = new ArrayList<>();

  /** Adds a server-measured round trip with client processing time removed. */
  public void add(long s0, long c1, long c2, long s3)
  {
    long delay = (s3 - s0) - (c2 - c1);
    if (s3 < s0 || c2 < c1 || delay < 0 || delay > 2000 || s3 - s0 > 5000)
      throw new PartyProtocol.Fault("CLOCK_SAMPLE_INVALID");
    samples.add(new Sample(s3, ((s0 - c1) + (s3 - c2)) / 2.0, delay));
    if (samples.size() > 20) samples.remove(0);
  }

  private List<Sample> fresh(long now)
  {
    List<Sample> out = new ArrayList<>();
    for (Sample s : samples) if (now - s.at <= 15000) out.add(s);
    out.sort(Comparator.comparingLong(Sample::delay));
    return out;
  }

  /** Requires five fresh samples and a bounded low-delay uncertainty interval. */
  public boolean confident(long now) { return fresh(now).size() >= 5 && uncertainty(now) <= 150; }

  /** Returns server-minus-client monotonic offset from the five lowest-delay samples. */
  public double offset(long now)
  {
    List<Sample> f = fresh(now);
    return f.stream().limit(5).mapToDouble(Sample::offset).average().orElse(0);
  }

  /** Bounds asymmetric-delay error by half RTT plus observed offset dispersion. */
  public double uncertainty(long now)
  {
    List<Sample> f = fresh(now);
    if (f.size() < 5) return 1000000;
    double mean = offset(now);
    return f.stream().limit(5).mapToDouble(s -> s.delay / 2.0 + Math.abs(s.offset - mean))
        .max().orElse(1000000);
  }

  /** Returns measured p95 delay across the fresh rolling window, including spikes. */
  public long p95(long now)
  {
    List<Sample> f = fresh(now);
    return f.isEmpty() ? 2000 : f.get((int)Math.ceil(f.size() * .95) - 1).delay;
  }

  /** Invalidates confidence after reconnect, backgrounding, or a link handoff. */
  public void reset() { samples.clear(); }
}
