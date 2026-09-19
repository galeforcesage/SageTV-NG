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
 * The always-present, non-specialized default provider: a <b>passthrough</b>.
 *
 * <p>It renders no scale stage. Deinterlace-only requests are trivially
 * "available" and contribute no fragment (the core still deinterlaces); an
 * upscaling request is accepted but returns an empty (non-renderable) plan, so
 * the core does <b>not</b> upscale on the playback path. Server-side upscaling is
 * an opt-in capability supplied by a separately installed {@link ScaleProvider}
 * (see {@link ScaleProviderRegistry#selectedProviderCanUpscale()}); with only the
 * built-in present, playback delivers the source resolution and the client
 * scales.
 *
 * <p>This is deliberate policy, not a missing feature: the built-in scaler ships
 * no GPU upscaler of its own. In this fork, live playback upscaling is provided
 * by the always-registered {@link CudaLanczosScaleProvider} (a deterministic
 * CUDA/Lanczos stage) and, when installed, a specialized AI provider such as
 * VSR; the registry prefers the AI provider, then CUDA-Lanczos, then this
 * passthrough (source). (Offline/batch conversion keeps its own Lanczos path;
 * that is a different subsystem and is unaffected.)
 *
 * <p>It is <b>not</b> specialized and never takes a {@link ScaleGovernor} permit.
 */
public final class BuiltinScaleProvider implements ScaleProvider
{
  public static final String ID = "builtin-passthrough";

  // supportsUpscale=false: the built-in never upscales the playback path.
  private static final ScaleProviderCapabilities CAPS =
      new ScaleProviderCapabilities(ID, false, false, Integer.MAX_VALUE);

  @Override
  public String id() { return ID; }

  @Override
  public ScaleProviderCapabilities capabilities() { return CAPS; }

  @Override
  public ScaleProviderAvailability probe(ScaleRequest request)
  {
    if (request == null)
      return ScaleProviderAvailability.unavailable("null request");
    // Always available: the passthrough can service any request, it simply
    // contributes no scale stage (the core still handles any deinterlace).
    return ScaleProviderAvailability.available();
  }

  @Override
  public ScaleExecutionPlan plan(ScaleRequest request)
  {
    // Passthrough: never a scale stage, whether upscaling was asked for or not.
    // A non-renderable plan makes the core skip the scale filter; an upscale that
    // reaches here (no upscaling provider installed) is dropped by the pipeline,
    // which is why the advisor gates the offer on selectedProviderCanUpscale().
    return new ScaleExecutionPlan(ExecutionForm.BUILTIN, null, "passthrough");
  }
}
