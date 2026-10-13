/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package sage.enhance.spi;

/** Versioned frame identity/timeline contract for external scaler handoff. */
public final class IndexedFrameTransport
{
  public static final int VERSION_1 = 1;
  private final int version;
  private final long ptsTimescale;
  private final int inputFrameBytes;
  private final int outputFrameBytes;

  public IndexedFrameTransport(int version, long ptsTimescale,
      int inputFrameBytes, int outputFrameBytes)
  {
    this.version = version;
    this.ptsTimescale = ptsTimescale;
    this.inputFrameBytes = inputFrameBytes;
    this.outputFrameBytes = outputFrameBytes;
  }

  public int getVersion() { return version; }
  public long getPtsTimescale() { return ptsTimescale; }
  public int getInputFrameBytes() { return inputFrameBytes; }
  public int getOutputFrameBytes() { return outputFrameBytes; }
  public boolean isSupported()
  {
    return version == VERSION_1 && ptsTimescale > 0L
        && inputFrameBytes > 0 && outputFrameBytes > 0;
  }
}
