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
 * How a {@link ScaleProvider} actually performs its scaling work.
 *
 * <p>The selection seam is deliberately execution-form-neutral so a future
 * provider can be an in-process ffmpeg filter, an external native worker, or a
 * sidecar service without changing the public interface. Phase 0 implements only
 * {@link #BUILTIN}; the other forms are declared so the SPI does not have to be
 * redesigned when a private provider needs them, but the core renders no argv for
 * them yet.
 */
public enum ExecutionForm
{
  /** The built-in current scaler (a single ffmpeg {@code -vf} scale fragment). */
  BUILTIN,
  /** A provider-supplied ffmpeg {@code -vf} scale fragment. */
  FFMPEG_FILTER,
  /** A separate native process the provider owns. Not rendered in Phase 0. */
  EXTERNAL_PROCESS,
  /** A long-lived sidecar service the provider talks to. Not rendered in Phase 0. */
  SIDECAR
}
