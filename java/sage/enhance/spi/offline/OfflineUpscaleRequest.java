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

import java.io.File;

/**
 * The immutable, provider-facing description of one <b>offline / batch</b>
 * upscale job (the chained-job path in {@code Ministry}, distinct from the live
 * transcode scale seam in {@code sage.enhance.spi}).
 *
 * <p>Offline upscaling runs as a separate process that reads a source file and
 * writes an upscaled lossless intermediate, which a second transcode pass then
 * encodes. This request carries only what such a worker needs: the input and
 * output files and the target frame size (plus the source height as a hint for
 * providers that pick a model by scale factor). It carries no genre routing,
 * recording state, or scheduling — the core owns those.
 */
public final class OfflineUpscaleRequest
{
  private final File input;
  private final File output;
  private final int targetWidth;
  private final int targetHeight;
  private final int sourceHeight;

  public OfflineUpscaleRequest(File input, File output,
                               int targetWidth, int targetHeight, int sourceHeight)
  {
    this.input = input;
    this.output = output;
    this.targetWidth = targetWidth;
    this.targetHeight = targetHeight;
    this.sourceHeight = sourceHeight;
  }

  /** The source file to upscale. */
  public File getInput() { return input; }

  /** The intermediate file the worker must write. */
  public File getOutput() { return output; }

  public int getTargetWidth() { return targetWidth; }
  public int getTargetHeight() { return targetHeight; }

  /** Source frame height, or a non-positive value when unknown. Advisory only. */
  public int getSourceHeight() { return sourceHeight; }

  @Override
  public String toString()
  {
    return "OfflineUpscaleRequest[" + (input == null ? "?" : input.getName())
        + " -> " + (output == null ? "?" : output.getName())
        + " " + targetWidth + "x" + targetHeight + " src=" + sourceHeight + "]";
  }
}
