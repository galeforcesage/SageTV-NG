/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package sage.enhance;

import java.io.IOException;

import sage.enhance.spi.IndexedFrameTransport;
import sage.enhance.spi.ScaleRuntimeSignal;

/**
 * Applies typed scaler health signals to one stable encoder-facing frame router.
 *
 * <p>The supervisor is transport plumbing, not a fallback implementation. It
 * activates only when both producers use the indexed transport and the fallback
 * declares the same output contract. Legacy rawvideo workers therefore remain
 * fail-closed rather than being hot-swapped at an unprovable byte boundary.
 */
public final class ScaleRuntimeSupervisor
{
  public enum Result { HEALTHY, DEGRADED, FATAL }

  public interface Fallback
  {
    int getOutputWidth();
    int getOutputHeight();
    String getPixelFormat();
    IndexedFrameTransport getTransport();
    void startAfter(long lastCommittedFrameIndex, long lastCommittedPts) throws IOException;
  }

  public interface FatalHandler
  {
    void failClosed(String reason) throws IOException;
  }

  private final ScaleFrameRouter router;
  private final Fallback fallback;
  private final FatalHandler fatalHandler;

  public ScaleRuntimeSupervisor(ScaleFrameRouter router, int outputWidth, int outputHeight,
      String pixelFormat, IndexedFrameTransport primaryTransport, Fallback fallback,
      FatalHandler fatalHandler)
  {
    if (router == null) throw new IllegalArgumentException("null frame router");
    if (outputWidth <= 0 || outputHeight <= 0)
      throw new IllegalArgumentException("invalid output geometry");
    if (pixelFormat == null || pixelFormat.length() == 0)
      throw new IllegalArgumentException("missing output pixel format");
    if (primaryTransport == null || !primaryTransport.isSupported())
      throw new IllegalArgumentException("primary transport is not indexed");
    if (!compatible(outputWidth, outputHeight, pixelFormat, primaryTransport, fallback))
      throw new IllegalArgumentException("fallback output contract does not match primary");
    if (fatalHandler == null) throw new IllegalArgumentException("null fatal handler");
    this.router = router;
    this.fallback = fallback;
    this.fatalHandler = fatalHandler;
  }

  public synchronized Result handle(ScaleRuntimeSignal signal) throws IOException
  {
    if (signal == null) throw new IllegalArgumentException("null runtime signal");
    if (signal.getKind() == ScaleRuntimeSignal.Kind.HEALTHY)
      return Result.HEALTHY;
    if (signal.getKind() == ScaleRuntimeSignal.Kind.FATAL)
    {
      fatalHandler.failClosed(signal.getReason());
      return Result.FATAL;
    }

    router.degrade(signal.getLastCompletedFrameIndex(), signal.getLastCompletedPts(),
        new ScaleFrameRouter.DegradeTransition()
        {
          public void startFallbackAfter(long frameIndex, long pts) throws IOException
          {
            fallback.startAfter(frameIndex, pts);
          }
        });
    return Result.DEGRADED;
  }

  private static boolean compatible(int width, int height, String pixelFormat,
      IndexedFrameTransport primary, Fallback candidate)
  {
    if (candidate == null || candidate.getOutputWidth() != width
        || candidate.getOutputHeight() != height
        || !pixelFormat.equalsIgnoreCase(candidate.getPixelFormat()))
      return false;
    IndexedFrameTransport other = candidate.getTransport();
    return other != null && other.isSupported()
        && other.getVersion() == primary.getVersion()
        && other.getPtsTimescale() == primary.getPtsTimescale()
        && other.getInputFrameBytes() == primary.getInputFrameBytes()
        && other.getOutputFrameBytes() == primary.getOutputFrameBytes();
  }
}
