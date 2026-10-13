/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package sage.enhance.spi;

/** Exact immutable identity of one measured scaling profile. */
public final class ScaleProfileKey
{
  private final int sourceWidth, sourceHeight, targetWidth, targetHeight;
  private final boolean sourceInterlaced;
  private final FrameCadence sourceCadence, targetCadence;
  private final String sourcePixelFormat, transportPixelFormat;
  private final int sourceBitDepth, transportBitDepth;
  private final String modelIdentity, backendIdentity, deviceIdentity, driverIdentity;

  public ScaleProfileKey(int sourceWidth, int sourceHeight, int targetWidth, int targetHeight,
      boolean sourceInterlaced, FrameCadence sourceCadence, FrameCadence targetCadence,
      String sourcePixelFormat, int sourceBitDepth, String transportPixelFormat,
      int transportBitDepth, String modelIdentity, String backendIdentity,
      String deviceIdentity, String driverIdentity)
  {
    this.sourceWidth = sourceWidth;
    this.sourceHeight = sourceHeight;
    this.targetWidth = targetWidth;
    this.targetHeight = targetHeight;
    this.sourceInterlaced = sourceInterlaced;
    this.sourceCadence = sourceCadence == null ? FrameCadence.UNKNOWN : sourceCadence;
    this.targetCadence = targetCadence == null ? FrameCadence.UNKNOWN : targetCadence;
    this.sourcePixelFormat = clean(sourcePixelFormat);
    this.sourceBitDepth = sourceBitDepth;
    this.transportPixelFormat = clean(transportPixelFormat);
    this.transportBitDepth = transportBitDepth;
    this.modelIdentity = clean(modelIdentity);
    this.backendIdentity = clean(backendIdentity);
    this.deviceIdentity = clean(deviceIdentity);
    this.driverIdentity = clean(driverIdentity);
  }

  public static ScaleProfileKey fromRequest(ScaleRequest request)
  {
    if (request == null) return null;
    return new ScaleProfileKey(request.getSourceWidth(), request.getSourceHeight(),
        request.getTargetWidth(), request.getTargetHeight(), request.isSourceInterlaced(),
        request.getSourceCadence(), request.getTargetCadence(), request.getSourcePixelFormat(),
        request.getSourceBitDepth(), request.getTransportPixelFormat(),
        request.getTransportBitDepth(), request.getModelIdentity(),
        request.getBackendIdentity(), request.getDeviceIdentity(), request.getDriverIdentity());
  }

  public int getSourceWidth() { return sourceWidth; }
  public int getSourceHeight() { return sourceHeight; }
  public int getTargetWidth() { return targetWidth; }
  public int getTargetHeight() { return targetHeight; }
  public boolean isSourceInterlaced() { return sourceInterlaced; }
  public FrameCadence getSourceCadence() { return sourceCadence; }
  public FrameCadence getTargetCadence() { return targetCadence; }
  public String getSourcePixelFormat() { return sourcePixelFormat; }
  public int getSourceBitDepth() { return sourceBitDepth; }
  public String getTransportPixelFormat() { return transportPixelFormat; }
  public int getTransportBitDepth() { return transportBitDepth; }
  public String getModelIdentity() { return modelIdentity; }
  public String getBackendIdentity() { return backendIdentity; }
  public String getDeviceIdentity() { return deviceIdentity; }
  public String getDriverIdentity() { return driverIdentity; }

  public boolean isComplete()
  {
    return sourceWidth > 0 && sourceHeight > 0 && targetWidth > 0 && targetHeight > 0
        && sourceCadence.isKnown() && targetCadence.isKnown()
        && !sourcePixelFormat.isEmpty() && sourceBitDepth > 0
        && !transportPixelFormat.isEmpty() && transportBitDepth > 0
        && !modelIdentity.isEmpty() && !backendIdentity.isEmpty()
        && !deviceIdentity.isEmpty() && !driverIdentity.isEmpty();
  }

  public boolean matchesRequest(ScaleRequest request)
  {
    if (request == null) return false;
    ScaleProfileKey requested = fromRequest(request);
    return sourceWidth == requested.sourceWidth && sourceHeight == requested.sourceHeight
        && targetWidth == requested.targetWidth && targetHeight == requested.targetHeight
        && sourceInterlaced == requested.sourceInterlaced
        && sourceCadence.equals(requested.sourceCadence)
        && targetCadence.equals(requested.targetCadence)
        && sourcePixelFormat.equals(requested.sourcePixelFormat)
        && sourceBitDepth == requested.sourceBitDepth
        && transportPixelFormat.equals(requested.transportPixelFormat)
        && transportBitDepth == requested.transportBitDepth
        && optionalMatch(requested.modelIdentity, modelIdentity)
        && optionalMatch(requested.backendIdentity, backendIdentity)
        && optionalMatch(requested.deviceIdentity, deviceIdentity)
        && optionalMatch(requested.driverIdentity, driverIdentity);
  }

  @Override
  public boolean equals(Object other)
  {
    if (this == other) return true;
    if (!(other instanceof ScaleProfileKey)) return false;
    ScaleProfileKey that = (ScaleProfileKey) other;
    return sourceWidth == that.sourceWidth && sourceHeight == that.sourceHeight
        && targetWidth == that.targetWidth && targetHeight == that.targetHeight
        && sourceInterlaced == that.sourceInterlaced
        && sourceBitDepth == that.sourceBitDepth && transportBitDepth == that.transportBitDepth
        && sourceCadence.equals(that.sourceCadence) && targetCadence.equals(that.targetCadence)
        && sourcePixelFormat.equals(that.sourcePixelFormat)
        && transportPixelFormat.equals(that.transportPixelFormat)
        && modelIdentity.equals(that.modelIdentity) && backendIdentity.equals(that.backendIdentity)
        && deviceIdentity.equals(that.deviceIdentity) && driverIdentity.equals(that.driverIdentity);
  }

  @Override
  public int hashCode()
  {
    int result = sourceWidth;
    result = 31 * result + sourceHeight;
    result = 31 * result + targetWidth;
    result = 31 * result + targetHeight;
    result = 31 * result + (sourceInterlaced ? 1 : 0);
    result = 31 * result + sourceCadence.hashCode();
    result = 31 * result + targetCadence.hashCode();
    result = 31 * result + sourcePixelFormat.hashCode();
    result = 31 * result + sourceBitDepth;
    result = 31 * result + transportPixelFormat.hashCode();
    result = 31 * result + transportBitDepth;
    result = 31 * result + modelIdentity.hashCode();
    result = 31 * result + backendIdentity.hashCode();
    result = 31 * result + deviceIdentity.hashCode();
    return 31 * result + driverIdentity.hashCode();
  }

  @Override
  public String toString()
  {
    return sourceWidth + "x" + sourceHeight + (sourceInterlaced ? "i@" : "p@")
        + sourceCadence + "->" + targetWidth + "x" + targetHeight + "@" + targetCadence
        + " src=" + sourcePixelFormat + "/" + sourceBitDepth
        + " transport=" + transportPixelFormat + "/" + transportBitDepth
        + " model=" + modelIdentity + " backend=" + backendIdentity
        + " device=" + deviceIdentity + " driver=" + driverIdentity;
  }

  private static String clean(String value)
  {
    return value == null ? "" : value.trim();
  }

  private static boolean optionalMatch(String requested, String measured)
  {
    return requested.isEmpty() || requested.equals(measured);
  }
}
