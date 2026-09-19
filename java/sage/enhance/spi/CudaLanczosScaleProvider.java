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

import sage.HwEncoder;
import sage.Sage;

/**
 * An always-registered, deterministic GPU upscaler: a single CUDA {@code -vf}
 * scale stage ({@code scale_cuda} / {@code scale_npp} with Lanczos when the build
 * exposes it).
 *
 * <p>This is the live playback path's <b>second-choice</b> upscaler, sitting
 * between a specialized AI provider (e.g. NVIDIA VSR, registered by a plugin) and
 * delivering the source. The registry tries any specialized upscaler first; when
 * none is registered, its runtime is unavailable, or its specialized budget is
 * exhausted, selection falls through to this provider so a CUDA-capable
 * deployment still upscales in real time rather than dropping straight to source.
 * When no CUDA scaler is present (no GPU, or a build without the filters) this
 * provider simply reports unavailable and the chain ends at the built-in
 * passthrough.
 *
 * <p>The fragment it emits is byte-for-byte the legacy in-process scale stage the
 * core rendered before the provider seam existed, so routing it through the SPI
 * changes the selection plumbing, not the encoded output.
 *
 * <p>It is <b>not</b> specialized: it consumes ordinary NVENC session capacity
 * (already governed by the transcoder), not the scarce AI-inference budget, so it
 * never takes a {@link ScaleGovernor} permit.
 */
public final class CudaLanczosScaleProvider implements ScaleProvider
{
  public static final String ID = "cuda-lanczos";

  /** Optional master switch. {@code auto} (default) / truthy = enabled; a falsey
   *  value disables the provider so a deployment can force VSR-or-nothing. */
  private static final String PROP_ENABLED = "playback/gpu_enhance/scale_cuda_lanczos_provider";

  // specialized=false (no AI budget), supportsUpscale=true.
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
    // Scale stage only: a deinterlace-only request is not ours to service.
    if (!request.isUpscaling())
      return ScaleProviderAvailability.unavailable("not an upscale request");
    if (!enabled())
      return ScaleProviderAvailability.unavailable("cuda-lanczos provider disabled by config");
    String scaler = resolveScaler(request);
    if (scaler == null)
      return ScaleProviderAvailability.unavailable("no CUDA scaler (scale_cuda/scale_npp) available");
    return ScaleProviderAvailability.available();
  }

  @Override
  public ScaleExecutionPlan plan(ScaleRequest request)
  {
    if (request == null || !request.isUpscaling())
      return new ScaleExecutionPlan(ExecutionForm.FFMPEG_FILTER, null, "cuda-lanczos (no upscale)");
    String scaler = resolveScaler(request);
    if (scaler == null)
      return new ScaleExecutionPlan(ExecutionForm.FFMPEG_FILTER, null, "cuda-lanczos (no scaler)");

    // Byte-identical to the core's legacy in-process scale fragment.
    StringBuilder s = new StringBuilder();
    s.append(scaler).append('=')
     .append(request.getTargetWidth()).append(':').append(request.getTargetHeight());
    if (HwEncoder.scalerSupportsLanczos(scaler)) s.append(":interp_algo=lanczos");

    String label = "scale_npp".equals(scaler) ? "NPP/Lanczos" : "CUDA/Lanczos";
    return new ScaleExecutionPlan(ExecutionForm.FFMPEG_FILTER, s.toString(), label);
  }

  /** The concrete CUDA scaler to use: the request's hint (which on the live plan
   *  path is exactly {@link HwEncoder#cudaScaler()}) when present, else a direct
   *  probe. Null when this ffmpeg has neither {@code scale_cuda} nor
   *  {@code scale_npp}. */
  private static String resolveScaler(ScaleRequest request)
  {
    String hint = request.getBuiltinScalerHint();
    if (hint != null && hint.length() > 0) return hint;
    return HwEncoder.cudaScaler();
  }

  private static boolean enabled()
  {
    String v = Sage.get(PROP_ENABLED, "auto").trim().toLowerCase(java.util.Locale.ROOT);
    return !(v.equals("false") || v.equals("0") || v.equals("off")
        || v.equals("no") || v.equals("disabled") || v.equals("none"));
  }

  /** Cheap, request-free "could this provider ever upscale on this box right
   *  now?": enabled by config and a CUDA scaler is present. Used by the registry
   *  as its no-request first gate for the chain's deterministic tail. */
  boolean isUsableHere()
  {
    return enabled() && HwEncoder.cudaScaler() != null;
  }
}
