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
 * admission, because none of those are expressible here. The core renders two
 * families of plan:
 * <ul>
 *   <li>{@link ExecutionForm#BUILTIN} / {@link ExecutionForm#FFMPEG_FILTER} — a
 *       single in-process {@code -vf} scale fragment applied to the live ffmpeg
 *       command (the original Phase 0 path).</li>
 *   <li>{@link ExecutionForm#EXTERNAL_PROCESS} — a provider-owned native worker
 *       the core spawns and wires into a three-process pipeline
 *       ({@code ffmpeg decode → worker → ffmpeg encode}), exchanging fixed-size
 *       raw frames over stdio per the live frame-transport contract. The core
 *       ships no vendor code: it only knows the worker argv and the frame
 *       geometry the provider declares here.</li>
 * </ul>
 * The {@code SIDECAR} form is still accepted by the type but not yet rendered by
 * the core.
 */
public final class ScaleExecutionPlan
{
  private final ExecutionForm form;
  private final String ffmpegFilter;
  private final String implementationLabel;

  // EXTERNAL_PROCESS fields. Null/zero for filter forms.
  private final java.util.List<String> externalArgv;
  private final int outputWidth;
  private final int outputHeight;
  private final String pipePixelFormat;

  // Pre-warmed worker for an EXTERNAL_PROCESS plan built from a WarmContext.
  // Null for a cold-start plan; when non-null the core wires this already-running
  // process into the pipeline instead of spawning from externalArgv.
  private final Process warmProcess;

  /**
   * Filter-form plan: a single {@code -vf} scale fragment (or a
   * {@link ExecutionForm#BUILTIN} plan with a null fragment for deinterlace-only).
   */
  public ScaleExecutionPlan(ExecutionForm form, String ffmpegFilter,
                            String implementationLabel)
  {
    this(form, ffmpegFilter, null, 0, 0, null, null, implementationLabel);
  }

  /**
   * External-process plan: the worker {@code argv} the core spawns verbatim, plus
   * the exact frame geometry and pixel format on the stdio pipes. Used only with
   * {@link ExecutionForm#EXTERNAL_PROCESS}.
   *
   * @param externalArgv    the worker command line, spawned with no shell
   * @param outputWidth     width of each frame the worker writes to its stdout
   * @param outputHeight    height of each frame the worker writes to its stdout
   * @param pipePixelFormat pixel format on the pipes; {@code rgb24} today
   */
  public ScaleExecutionPlan(ExecutionForm form, java.util.List<String> externalArgv,
                            int outputWidth, int outputHeight, String pipePixelFormat,
                            String implementationLabel)
  {
    this(form, null, externalArgv, outputWidth, outputHeight, pipePixelFormat, null,
        implementationLabel);
  }

  /**
   * External-process plan backed by an <b>already-running, pre-warmed</b> worker.
   * The core wires {@code warmProcess}'s stdin/stdout into the pipeline instead of
   * spawning from {@code externalArgv}; the argv is still supplied (and returned
   * by {@link #getExternalArgv()}) for logging and diagnostics only. Returned by a
   * provider from {@link ScaleProvider#plan(ScaleRequest, WarmContext)} when it
   * consumes a {@link WarmContext}.
   *
   * @param externalArgv    the worker command line (diagnostics only when warm)
   * @param outputWidth     width of each frame the worker writes to its stdout
   * @param outputHeight    height of each frame the worker writes to its stdout
   * @param pipePixelFormat pixel format on the pipes; {@code rgb24} today
   * @param warmProcess     the already-running worker; the core does not spawn
   */
  public static ScaleExecutionPlan externalWithWarmProcess(
      java.util.List<String> externalArgv, int outputWidth, int outputHeight,
      String pipePixelFormat, String implementationLabel, Process warmProcess)
  {
    return new ScaleExecutionPlan(ExecutionForm.EXTERNAL_PROCESS, null, externalArgv,
        outputWidth, outputHeight, pipePixelFormat, warmProcess, implementationLabel);
  }

  private ScaleExecutionPlan(ExecutionForm form, String ffmpegFilter,
                             java.util.List<String> externalArgv, int outputWidth,
                             int outputHeight, String pipePixelFormat, Process warmProcess,
                             String implementationLabel)
  {
    this.form = form;
    this.ffmpegFilter = ffmpegFilter;
    this.externalArgv = (externalArgv == null) ? null
        : java.util.Collections.unmodifiableList(
              new java.util.ArrayList<String>(externalArgv));
    this.outputWidth = outputWidth;
    this.outputHeight = outputHeight;
    this.pipePixelFormat = (pipePixelFormat == null || pipePixelFormat.isEmpty())
        ? "rgb24" : pipePixelFormat;
    this.warmProcess = warmProcess;
    this.implementationLabel = (implementationLabel == null) ? "" : implementationLabel;
  }

  public ExecutionForm getForm() { return form; }

  /** The {@code -vf} scale fragment (scale stage only), or null for non-filter
   *  forms. Never includes the deinterlacer. */
  public String getFfmpegFilter() { return ffmpegFilter; }

  /** Human-readable description of the real mechanism, e.g. {@code NPP/Lanczos},
   *  {@code CUDA}, {@code Software}. For telemetry and honest labelling. */
  public String getImplementationLabel() { return implementationLabel; }

  /** The worker command line for an {@link ExecutionForm#EXTERNAL_PROCESS} plan,
   *  or null for filter forms. Immutable. Spawned verbatim, with no shell. */
  public java.util.List<String> getExternalArgv() { return externalArgv; }

  /** Width of each frame the external worker emits on its stdout. */
  public int getOutputWidth() { return outputWidth; }

  /** Height of each frame the external worker emits on its stdout. */
  public int getOutputHeight() { return outputHeight; }

  /** Pixel format exchanged on the worker stdio pipes ({@code rgb24} today). */
  public String getPipePixelFormat() { return pipePixelFormat; }

  /** For an {@link ExecutionForm#EXTERNAL_PROCESS} plan backed by a pre-warmed
   *  worker: the already-running process. The core wires its stdin/stdout into
   *  the pipeline instead of spawning from {@link #getExternalArgv()}. Null for a
   *  cold-start plan (the core spawns the argv as usual). When non-null the argv
   *  is still populated for logging/diagnostics but not used to spawn. */
  public Process getWarmProcess() { return warmProcess; }

  /** True when the core can render this plan as an ffmpeg filter fragment. */
  public boolean rendersFilterFragment()
  {
    return (form == ExecutionForm.BUILTIN || form == ExecutionForm.FFMPEG_FILTER)
        && ffmpegFilter != null && !ffmpegFilter.isEmpty();
  }

  /** True when the core can render this plan as a spawned external-worker
   *  pipeline: an {@link ExecutionForm#EXTERNAL_PROCESS} plan with a non-empty
   *  worker argv and a positive declared output geometry. */
  public boolean rendersExternalProcess()
  {
    return form == ExecutionForm.EXTERNAL_PROCESS
        && externalArgv != null && !externalArgv.isEmpty()
        && outputWidth > 0 && outputHeight > 0;
  }

  /** True when this plan is usable by the core. A provider that returns a
   *  not-yet-supported execution form, an empty filter, or an incomplete
   *  external-process plan is rejected and the registry falls back to the
   *  built-in scaler. */
  public boolean isRenderable()
  {
    return rendersFilterFragment() || rendersExternalProcess();
  }

  /**
   * @deprecated renamed to {@link #isRenderable()} now that the core renders more
   *     than the original Phase 0 filter forms. Retained as a delegating alias.
   */
  @Deprecated
  public boolean isRenderablePhase0()
  {
    return isRenderable();
  }

  @Override
  public String toString()
  {
    if (rendersExternalProcess())
      return "ScaleExecutionPlan[" + form + " " + externalArgv
          + " " + outputWidth + "x" + outputHeight + " " + pipePixelFormat
          + " (" + implementationLabel + ")]";
    return "ScaleExecutionPlan[" + form + " " + ffmpegFilter
        + " (" + implementationLabel + ")]";
  }
}
