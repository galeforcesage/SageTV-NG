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
 * The immutable output of {@link ScaleProvider#plan}: how the selected provider
 * wants its <b>scale stage</b> realized, and nothing else.
 *
 * <p>Scope is intentionally narrow. A provider owns the scaling step only — it
 * cannot suppress the mandatory deinterlacer, touch the bitrate ladder, or alter
 * admission, because none of those are expressible here. Phase 0 renders the
 * {@link ExecutionForm#BUILTIN} and {@link ExecutionForm#FFMPEG_FILTER} forms
 * (both a single {@code -vf} scale fragment); the {@code EXTERNAL_PROCESS} and
 * {@code SIDECAR} forms are accepted by the type but not yet rendered by the
 * core, so a Phase 0 provider must return a filter form.
 */
public final class ScaleExecutionPlan
{
  private final ExecutionForm form;
  private final String ffmpegFilter;
  private final String implementationLabel;

  public ScaleExecutionPlan(ExecutionForm form, String ffmpegFilter,
                            String implementationLabel)
  {
    this.form = form;
    this.ffmpegFilter = ffmpegFilter;
    this.implementationLabel = (implementationLabel == null) ? "" : implementationLabel;
  }

  public ExecutionForm getForm() { return form; }

  /** The {@code -vf} scale fragment (scale stage only), or null for non-filter
   *  forms. Never includes the deinterlacer. */
  public String getFfmpegFilter() { return ffmpegFilter; }

  /** Human-readable description of the real mechanism, e.g. {@code NPP/Lanczos},
   *  {@code CUDA}, {@code Software}. For telemetry and honest labelling. */
  public String getImplementationLabel() { return implementationLabel; }

  /** True when the core can render this plan as an ffmpeg filter fragment. */
  public boolean rendersFilterFragment()
  {
    return (form == ExecutionForm.BUILTIN || form == ExecutionForm.FFMPEG_FILTER)
        && ffmpegFilter != null && !ffmpegFilter.isEmpty();
  }

  /** True when this plan is usable by the Phase 0 core. A provider that returns
   *  a not-yet-supported execution form, or an empty filter, is rejected and the
   *  registry falls back to the built-in scaler. */
  public boolean isRenderablePhase0()
  {
    return rendersFilterFragment();
  }

  @Override
  public String toString()
  {
    return "ScaleExecutionPlan[" + form + " " + ffmpegFilter
        + " (" + implementationLabel + ")]";
  }
}
