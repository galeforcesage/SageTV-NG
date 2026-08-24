/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package sage.enhance.spi;

/**
 * The always-present, non-specialized default provider: it wraps exactly the
 * current CUDA scaler behavior.
 *
 * <p>The produced fragment is byte-for-byte what the pipeline emitted before the
 * provider seam existed: {@code scale_npp=W:H:interp_algo=lanczos} when NPP is
 * available, otherwise {@code scale_cuda=W:H}. Deliberately named for what it is
 * rather than a specific kernel — the reported {@link ScaleExecutionPlan
 * implementation label} distinguishes NPP/Lanczos from plain CUDA — so it stays
 * honest if the underlying scaler hint ever changes.
 *
 * <p>It is <b>not</b> specialized and never takes a {@link ScaleGovernor} permit.
 */
public final class BuiltinScaleProvider implements ScaleProvider
{
  public static final String ID = "builtin-lanczos";

  private static final ScaleProviderCapabilities CAPS =
      new ScaleProviderCapabilities(ID, false, true, Integer.MAX_VALUE);

  @Override
  public String id() { return ID; }

  @Override
  public ScaleProviderCapabilities capabilities() { return CAPS; }

  @Override
  public ScaleProviderAvailability probe(ScaleRequest request)
  {
    if (request == null)
      return ScaleProviderAvailability.unavailable("null request");
    // Deinterlace-only (non-upscaling) requests need no scaler; the built-in is
    // trivially "available" and contributes no scale fragment.
    if (!request.isUpscaling())
      return ScaleProviderAvailability.available();
    if (request.getBuiltinScalerHint() == null)
      return ScaleProviderAvailability.unavailable("no CUDA scaler in this ffmpeg");
    return ScaleProviderAvailability.available();
  }

  @Override
  public ScaleExecutionPlan plan(ScaleRequest request)
  {
    // Non-upscaling: no scale stage. The core still renders the deinterlacer.
    if (request == null || !request.isUpscaling())
      return new ScaleExecutionPlan(ExecutionForm.BUILTIN, null, label(null));

    String scaler = request.getBuiltinScalerHint();
    if (scaler == null)
      return new ScaleExecutionPlan(ExecutionForm.BUILTIN, null, label(null));

    StringBuilder sb = new StringBuilder();
    sb.append(scaler).append('=')
      .append(request.getTargetWidth()).append(':').append(request.getTargetHeight());
    // Lanczos when the chosen scaler supports it: scale_npp always, scale_cuda on
    // builds whose filter exposes interp_algo. Gated on capability so a
    // bilinear-only scale_cuda is never handed an option ffmpeg would reject.
    if (sage.HwEncoder.scalerSupportsLanczos(scaler)) sb.append(":interp_algo=lanczos");
    return new ScaleExecutionPlan(ExecutionForm.BUILTIN, sb.toString(), label(scaler));
  }

  private static String label(String scaler)
  {
    if ("scale_npp".equals(scaler)) return "NPP/Lanczos";
    if ("scale_cuda".equals(scaler))
      return sage.HwEncoder.scalerSupportsLanczos(scaler) ? "CUDA/Lanczos" : "CUDA";
    if (scaler == null) return "none";
    return scaler;
  }
}
