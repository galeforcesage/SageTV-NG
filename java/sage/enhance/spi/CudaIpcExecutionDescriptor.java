/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package sage.enhance.spi;

import java.util.Arrays;

/**
 * Immutable negotiation contract for a CUDA-IPC surface transport.
 *
 * <p>This describes allocation lineage and synchronization; it deliberately
 * carries no live CUDA handle. Handles are per-session/per-frame capabilities
 * exchanged by the negotiated transport and revoked by its release protocol.
 */
public final class CudaIpcExecutionDescriptor
{
  private final String deviceUuid;
  private final String pixelFormat;
  private final int width, height;
  private final long[] planePitches, planeOffsets;
  private final String handleLifetime;
  private final String synchronizationPrimitive;
  private final String contextOwnership;
  private final String releaseProtocol;

  public CudaIpcExecutionDescriptor(String deviceUuid, String pixelFormat,
      int width, int height, long[] planePitches, long[] planeOffsets,
      String handleLifetime, String synchronizationPrimitive,
      String contextOwnership, String releaseProtocol)
  {
    this.deviceUuid = clean(deviceUuid);
    this.pixelFormat = clean(pixelFormat);
    this.width = width;
    this.height = height;
    this.planePitches = copy(planePitches);
    this.planeOffsets = copy(planeOffsets);
    this.handleLifetime = clean(handleLifetime);
    this.synchronizationPrimitive = clean(synchronizationPrimitive);
    this.contextOwnership = clean(contextOwnership);
    this.releaseProtocol = clean(releaseProtocol);
  }

  public String getDeviceUuid() { return deviceUuid; }
  public String getPixelFormat() { return pixelFormat; }
  public int getWidth() { return width; }
  public int getHeight() { return height; }
  public long[] getPlanePitches() { return copy(planePitches); }
  public long[] getPlaneOffsets() { return copy(planeOffsets); }
  public String getHandleLifetime() { return handleLifetime; }
  public String getSynchronizationPrimitive() { return synchronizationPrimitive; }
  public String getContextOwnership() { return contextOwnership; }
  public String getReleaseProtocol() { return releaseProtocol; }

  public boolean isComplete()
  {
    return !deviceUuid.isEmpty() && !pixelFormat.isEmpty() && width > 0 && height > 0
        && planePitches.length > 0 && planePitches.length == planeOffsets.length
        && !handleLifetime.isEmpty() && !synchronizationPrimitive.isEmpty()
        && !contextOwnership.isEmpty() && !releaseProtocol.isEmpty();
  }

  @Override
  public String toString()
  {
    return "CudaIpcExecutionDescriptor[device=" + deviceUuid + " format=" + pixelFormat
        + " " + width + "x" + height + " pitches=" + Arrays.toString(planePitches)
        + " offsets=" + Arrays.toString(planeOffsets) + " lifetime=" + handleLifetime
        + " sync=" + synchronizationPrimitive + " context=" + contextOwnership
        + " release=" + releaseProtocol + "]";
  }

  private static long[] copy(long[] value)
  {
    return value == null ? new long[0] : value.clone();
  }

  private static String clean(String value)
  {
    return value == null ? "" : value.trim();
  }
}
