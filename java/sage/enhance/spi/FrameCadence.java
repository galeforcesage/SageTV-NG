/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package sage.enhance.spi;

/** Exact immutable rational frame cadence. */
public final class FrameCadence
{
  public static final FrameCadence UNKNOWN = new FrameCadence(0L, 1L);

  private final long numerator;
  private final long denominator;

  public FrameCadence(long numerator, long denominator)
  {
    if (numerator <= 0L || denominator <= 0L)
    {
      this.numerator = 0L;
      this.denominator = 1L;
      return;
    }
    long gcd = gcd(numerator, denominator);
    this.numerator = numerator / gcd;
    this.denominator = denominator / gcd;
  }

  public static FrameCadence fromFps(double fps)
  {
    if (!(fps > 0.0) || Double.isInfinite(fps) || Double.isNaN(fps)) return UNKNOWN;
    long[][] broadcast = {{24000, 1001}, {30000, 1001}, {60000, 1001}, {120000, 1001}};
    for (long[] value : broadcast)
      if (Math.abs(fps - (double) value[0] / value[1]) < 0.02)
        return new FrameCadence(value[0], value[1]);
    long denominator = 1000000L;
    return new FrameCadence(Math.round(fps * denominator), denominator);
  }

  public long getNumerator() { return numerator; }
  public long getDenominator() { return denominator; }
  public boolean isKnown() { return numerator > 0L; }
  public double asDouble() { return isKnown() ? (double) numerator / denominator : 0.0; }
  public FrameCadence multiply(long value)
  {
    return isKnown() && value > 0L ? new FrameCadence(numerator * value, denominator) : UNKNOWN;
  }

  @Override
  public boolean equals(Object other)
  {
    if (this == other) return true;
    if (!(other instanceof FrameCadence)) return false;
    FrameCadence that = (FrameCadence) other;
    return numerator == that.numerator && denominator == that.denominator;
  }

  @Override
  public int hashCode()
  {
    return 31 * Long.valueOf(numerator).hashCode() + Long.valueOf(denominator).hashCode();
  }

  @Override
  public String toString()
  {
    return isKnown() ? numerator + "/" + denominator : "unknown";
  }

  private static long gcd(long a, long b)
  {
    while (b != 0L)
    {
      long next = a % b;
      a = b;
      b = next;
    }
    return Math.abs(a);
  }
}
