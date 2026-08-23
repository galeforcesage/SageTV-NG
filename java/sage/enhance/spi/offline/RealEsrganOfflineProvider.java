/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package sage.enhance.spi.offline;

import java.util.ArrayList;
import java.util.List;

import sage.Sage;

/**
 * The always-present, built-in offline provider: it wraps exactly the current
 * Real-ESRGAN (ncnn-vulkan) invocation.
 *
 * <p>The produced argv is byte-for-byte what {@code Ministry} emitted before the
 * offline provider seam existed, so with no external provider selected the
 * offline path is unchanged. Both the upscale command and the probe command are
 * driven entirely by the existing {@code transcoder/ai_upscale_*} properties:
 *
 * <ul>
 *   <li>{@code transcoder/ai_upscale_wrapper} (default {@code bin/sage-ai-upscale.sh})</li>
 *   <li>{@code transcoder/ai_upscale_binary}  (default {@code /usr/local/bin/realesrgan-ncnn-vulkan})</li>
 *   <li>{@code transcoder/ai_upscale_model}   (default {@code realesr-general-x4v3})</li>
 *   <li>{@code transcoder/ai_upscale_chunk_frames} (default {@code 500})</li>
 *   <li>{@code transcoder/ai_upscale_require_vulkan} (default {@code true}) —
 *       when {@code false}, no probe is required and {@link #buildProbeCommand}
 *       returns {@code null}.</li>
 * </ul>
 *
 * <p>It is <b>not</b> specialized in the SPI sense.
 */
public final class RealEsrganOfflineProvider implements OfflineUpscaleProvider
{
  public static final String ID = "realesrgan-ncnn";

  @Override
  public String id() { return ID; }

  @Override
  public boolean isSpecialized() { return false; }

  @Override
  public List<String> buildProbeCommand()
  {
    // Honour the existing "require vulkan" gate: when the operator has turned it
    // off, the built-in declares no probe is needed (available). This preserves
    // the pre-seam short-circuit exactly.
    if (!Sage.getBoolean("transcoder/ai_upscale_require_vulkan", true))
      return null;

    String wrapper = Sage.get("transcoder/ai_upscale_wrapper", "bin/sage-ai-upscale.sh");
    String binary  = Sage.get("transcoder/ai_upscale_binary", "/usr/local/bin/realesrgan-ncnn-vulkan");
    String model   = Sage.get("transcoder/ai_upscale_model", "realesr-general-x4v3");
    List<String> argv = new ArrayList<String>();
    argv.add("/bin/bash");
    argv.add(wrapper);
    argv.add("--probe");
    argv.add("--realesrgan"); argv.add(binary);
    argv.add("--model"); argv.add(model);
    return argv;
  }

  @Override
  public List<String> buildUpscaleCommand(OfflineUpscaleRequest req)
  {
    String wrapper = Sage.get("transcoder/ai_upscale_wrapper", "bin/sage-ai-upscale.sh");
    String model   = Sage.get("transcoder/ai_upscale_model", "realesr-general-x4v3");
    String binary  = Sage.get("transcoder/ai_upscale_binary", "/usr/local/bin/realesrgan-ncnn-vulkan");
    int chunk      = Sage.getInt("transcoder/ai_upscale_chunk_frames", 500);
    List<String> argv = new ArrayList<String>();
    argv.add("/bin/bash");
    argv.add(wrapper);
    argv.add("--input");  argv.add(req.getInput().getAbsolutePath());
    argv.add("--output"); argv.add(req.getOutput().getAbsolutePath());
    argv.add("--width");  argv.add(Integer.toString(req.getTargetWidth()));
    argv.add("--height"); argv.add(Integer.toString(req.getTargetHeight()));
    argv.add("--model");  argv.add(model);
    argv.add("--chunk-frames"); argv.add(Integer.toString(chunk));
    argv.add("--realesrgan"); argv.add(binary);
    return argv;
  }
}
