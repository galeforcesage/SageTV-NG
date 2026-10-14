/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package sage.enhance;

import java.io.IOException;
import java.util.Arrays;
import java.util.TreeMap;

/**
 * Bounded sequence/PTS commit router shared by primary and fallback scalers.
 *
 * <p>The router never fabricates frames. Before degradation it accepts primary
 * output; after an explicit committed checkpoint it accepts fallback output
 * strictly after that checkpoint. Duplicate, replayed, or regressing output is
 * discarded, and a missing-frame backlog is bounded.
 */
public final class ScaleFrameRouter
{
  public enum Source { PRIMARY, FALLBACK }

  public interface DegradeTransition
  {
    void startFallbackAfter(long lastCommittedFrameIndex, long lastCommittedPts)
        throws IOException;
  }

  public interface Sink
  {
    void write(long frameIndex, long pts, byte[] frame) throws IOException;
  }

  private static final class Frame
  {
    final long pts;
    final byte[] bytes;
    Frame(long pts, byte[] bytes) { this.pts = pts; this.bytes = bytes; }
  }

  private final Sink sink;
  private final int maxPendingFrames;
  private final TreeMap<Long, Frame> pending = new TreeMap<Long, Frame>();
  private long nextFrameIndex;
  private long lastCommittedPts = Long.MIN_VALUE;
  private boolean degraded;
  private long primaryCheckpoint = Long.MAX_VALUE;
  private long primaryCheckpointPts = Long.MIN_VALUE;

  public ScaleFrameRouter(long firstFrameIndex, int maxPendingFrames, Sink sink)
  {
    if (firstFrameIndex < 0L) throw new IllegalArgumentException("negative first frame");
    if (maxPendingFrames <= 0) throw new IllegalArgumentException("non-positive bound");
    if (sink == null) throw new IllegalArgumentException("null sink");
    this.nextFrameIndex = firstFrameIndex;
    this.maxPendingFrames = maxPendingFrames;
    this.sink = sink;
  }

  public synchronized void degrade(long lastCompletedFrameIndex, long lastCompletedPts)
  {
    try
    {
      degrade(lastCompletedFrameIndex, lastCompletedPts, null);
    }
    catch (IOException impossible)
    {
      throw new IllegalStateException(impossible);
    }
  }

  /**
   * Atomically starts a fallback producer and commits the source switch at the
   * encoder's last committed frame. A producer started by {@code transition}
   * may immediately submit on another thread; that submit waits on this router
   * until the switch is committed.
   */
  public synchronized void degrade(long lastCompletedFrameIndex, long lastCompletedPts,
      DegradeTransition transition) throws IOException
  {
    if (degraded)
    {
      if (lastCompletedFrameIndex == primaryCheckpoint
          && lastCompletedPts == primaryCheckpointPts)
        return;
      throw new IllegalArgumentException("degrade checkpoint changed after transition");
    }
    long committedIndex = nextFrameIndex - 1L;
    if (lastCompletedFrameIndex != committedIndex)
      throw new IllegalArgumentException("degrade checkpoint " + lastCompletedFrameIndex
          + " does not equal committed output " + committedIndex);
    if (lastCompletedPts != lastCommittedPts)
      throw new IllegalArgumentException("degrade PTS " + lastCompletedPts
          + " does not equal committed output PTS " + lastCommittedPts);
    if (transition != null)
      transition.startFallbackAfter(lastCompletedFrameIndex, lastCompletedPts);
    degraded = true;
    primaryCheckpoint = lastCompletedFrameIndex;
    primaryCheckpointPts = lastCompletedPts;
    pending.tailMap(lastCompletedFrameIndex + 1L).clear();
  }

  public synchronized void submit(Source source, long frameIndex, long pts, byte[] frame)
      throws IOException
  {
    if (source == null || frame == null) throw new IllegalArgumentException("null frame input");
    if (frameIndex < nextFrameIndex) return;
    if (!degraded && source != Source.PRIMARY) return;
    if (degraded)
    {
      if (source == Source.PRIMARY && frameIndex > primaryCheckpoint) return;
      if (source == Source.FALLBACK && frameIndex <= primaryCheckpoint) return;
    }
    if (!pending.containsKey(frameIndex))
      pending.put(frameIndex, new Frame(pts, Arrays.copyOf(frame, frame.length)));
    if (pending.size() > maxPendingFrames)
      throw new IOException("scale frame router exceeded " + maxPendingFrames
          + " pending frames waiting for index " + nextFrameIndex);
    drain();
  }

  public synchronized long getNextFrameIndex() { return nextFrameIndex; }
  public synchronized long getLastCommittedPts() { return lastCommittedPts; }
  public synchronized boolean isDegraded() { return degraded; }
  public synchronized int getPendingFrameCount() { return pending.size(); }

  private void drain() throws IOException
  {
    Frame frame;
    while ((frame = pending.remove(nextFrameIndex)) != null)
    {
      if (lastCommittedPts != Long.MIN_VALUE && frame.pts < lastCommittedPts)
        throw new IOException("scale output PTS regressed at frame " + nextFrameIndex);
      sink.write(nextFrameIndex, frame.pts, frame.bytes);
      lastCommittedPts = frame.pts;
      nextFrameIndex++;
    }
  }
}
