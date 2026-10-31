/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sage;

/**
 * Tracks observable transport progress independently of client type or address
 * topology. A caller can combine an idle budget with an absolute cap: useful
 * progress extends the idle window, but cannot keep an attempt alive forever.
 */
final class ConnectionProgress
{
  private final long startedAtMs;
  private long lastProgressAtMs;
  private long bytes;
  private long messages;
  private long smoothedRoundTripMs = -1L;
  private long roundTripVariationMs;

  ConnectionProgress(long nowMs)
  {
    startedAtMs = nowMs;
    lastProgressAtMs = nowMs;
  }

  synchronized void markBytes(long nowMs, long count)
  {
    if (count <= 0L)
      return;
    bytes = saturatedAdd(bytes, count);
    markProgress(nowMs);
  }

  synchronized void markMessage(long nowMs)
  {
    messages = saturatedAdd(messages, 1L);
    markProgress(nowMs);
  }

  synchronized void recordRoundTrip(long nowMs, long sampleMs)
  {
    if (sampleMs < 0L)
      return;
    if (smoothedRoundTripMs < 0L)
    {
      smoothedRoundTripMs = sampleMs;
      roundTripVariationMs = sampleMs / 2L;
    }
    else
    {
      long error = absDifference(smoothedRoundTripMs, sampleMs);
      roundTripVariationMs = (3L * roundTripVariationMs + error) / 4L;
      smoothedRoundTripMs = (7L * smoothedRoundTripMs + sampleMs) / 8L;
    }
    markProgress(nowMs);
  }

  synchronized long remainingWait(long waitStartedAtMs, long nowMs,
      long idleBudgetMs, long absoluteBudgetMs)
  {
    long effectiveLastProgress = Math.max(waitStartedAtMs, lastProgressAtMs);
    long idleDeadline = idleBudgetMs > 0L
        ? saturatedAdd(effectiveLastProgress, idleBudgetMs) : Long.MAX_VALUE;
    long absoluteDeadline = absoluteBudgetMs > 0L
        ? saturatedAdd(waitStartedAtMs, absoluteBudgetMs) : Long.MAX_VALUE;
    long deadline = Math.min(idleDeadline, absoluteDeadline);
    return deadline <= nowMs ? 0L : deadline - nowMs;
  }

  synchronized boolean expired(long waitStartedAtMs, long nowMs,
      long idleBudgetMs, long absoluteBudgetMs)
  {
    return remainingWait(waitStartedAtMs, nowMs, idleBudgetMs, absoluteBudgetMs) == 0L;
  }

  synchronized long getStartedAtMs()
  {
    return startedAtMs;
  }

  synchronized long getLastProgressAtMs()
  {
    return lastProgressAtMs;
  }

  synchronized long getBytes()
  {
    return bytes;
  }

  synchronized long getMessages()
  {
    return messages;
  }

  synchronized long getSmoothedRoundTripMs()
  {
    return smoothedRoundTripMs;
  }

  synchronized long getRoundTripVariationMs()
  {
    return roundTripVariationMs;
  }

  private void markProgress(long nowMs)
  {
    if (nowMs > lastProgressAtMs)
      lastProgressAtMs = nowMs;
  }

  private static long saturatedAdd(long left, long right)
  {
    if (right > 0L && left > Long.MAX_VALUE - right)
      return Long.MAX_VALUE;
    return left + right;
  }

  private static long absDifference(long left, long right)
  {
    if (left >= right)
      return left - right;
    return right - left;
  }
}
