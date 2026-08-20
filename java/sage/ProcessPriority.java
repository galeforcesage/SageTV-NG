/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package sage;

import java.util.concurrent.TimeUnit;

/**
 * Reduces the scheduling priority of a spawned transcoder child.
 *
 * <p>On POSIX hosts this is done by prefixing the command with {@code nice} and
 * optionally {@code ionice}, which {@code FFMPEGTranscoder.startTranscode()} has
 * always done. That prefix approach has no Windows equivalent, and the block that
 * builds it is guarded by {@code !Sage.WINDOWS_OS} — so <b>Windows hosts have
 * historically had no transcoder priority reduction at all</b>. On a box that also
 * records, that gap matters: a transcode competing at normal priority with capture
 * is exactly the contention that Invariant 0 of the GPU enhancement work forbids.
 *
 * <p>Windows can't express this as a command prefix, so priority is applied
 * <i>after</i> the child starts, addressed by PID. That ordering is deliberate:
 * wrapping the child in {@code cmd /c start /BELOWNORMAL} would work, but it
 * replaces the direct child handle with an intermediate shell, which would break
 * both the stdout pipe the transcoder reads from and the phantom-process reaper
 * that relies on holding the real {@link Process}.
 *
 * <p>Best-effort by construction: a failure to lower priority is logged once and
 * otherwise ignored, because it must never prevent a stream from starting.
 *
 * <p>Property knobs:
 * <pre>
 *   xcode_reduce_process_priority   master switch, default true (shared with POSIX)
 *   xcode_windows_priority_class    Idle|BelowNormal|Normal, default BelowNormal
 * </pre>
 */
public final class ProcessPriority
{
  private static final String PROP_ENABLED = "xcode_reduce_process_priority";
  private static final String PROP_CLASS   = "xcode_windows_priority_class";
  private static final String DEFAULT_CLASS = "BelowNormal";

  /** Only complain once per JVM; a broken helper would otherwise spam the log. */
  private static volatile boolean loggedFailure = false;

  private ProcessPriority() { }

  /**
   * Lower {@code proc}'s priority if this platform needs post-start handling.
   *
   * <p>No-op on POSIX, where the {@code nice}/{@code ionice} prefix has already
   * done the job at exec time — applying it twice would be wrong.
   */
  public static void reduce(Process proc)
  {
    if (proc == null) return;
    if (!Sage.WINDOWS_OS) return; // handled by the nice/ionice prefix
    if (!Sage.getBoolean(PROP_ENABLED, true)) return;
    if (!proc.isAlive()) return;

    String cls = normalizeClass(Sage.get(PROP_CLASS, DEFAULT_CLASS));
    if (cls == null) return; // explicitly "Normal" => nothing to do

    long pid;
    try { pid = proc.pid(); }
    catch (Throwable t) { return; }

    applyWindowsPriority(pid, cls);
  }

  /**
   * Map a configured value onto a .NET {@code ProcessPriorityClass} name.
   * Returns null when the request is "leave it alone".
   */
  static String normalizeClass(String v)
  {
    if (v == null) return DEFAULT_CLASS;
    String s = v.trim();
    if (s.length() == 0) return DEFAULT_CLASS;
    if (s.equalsIgnoreCase("idle") || s.equalsIgnoreCase("low")) return "Idle";
    if (s.equalsIgnoreCase("belownormal") || s.equalsIgnoreCase("below_normal")
        || s.equalsIgnoreCase("below")) return "BelowNormal";
    if (s.equalsIgnoreCase("normal") || s.equalsIgnoreCase("none")
        || s.equalsIgnoreCase("off")) return null;
    return DEFAULT_CLASS;
  }

  /**
   * Set the priority class of a live PID via PowerShell.
   *
   * <p>PowerShell is used rather than {@code wmic} because {@code wmic} is
   * deprecated and absent from current Windows images. The call is fire-and-wait
   * with a short timeout: it runs once per transcode start, so the cost is
   * irrelevant, but a hung helper must not block the stream.
   */
  private static void applyWindowsPriority(long pid, String priorityClass)
  {
    Process helper = null;
    try
    {
      String script = "$p=Get-Process -Id " + pid + " -ErrorAction Stop; "
          + "$p.PriorityClass=[System.Diagnostics.ProcessPriorityClass]::" + priorityClass;
      helper = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
          "-ExecutionPolicy", "Bypass", "-Command", script)
          .redirectErrorStream(true).start();
      // Nothing useful on stdout; drain so the child can't block on a full pipe.
      try { while (helper.getInputStream().read() >= 0); } catch (Throwable ignore) {}
      if (!helper.waitFor(5000L, TimeUnit.MILLISECONDS))
      {
        helper.destroyForcibly();
        return;
      }
      if (Sage.DBG && helper.exitValue() == 0)
        System.out.println("ProcessPriority: set pid " + pid + " to " + priorityClass);
      else if (helper.exitValue() != 0) noteFailure(pid, "exit " + helper.exitValue());
    }
    catch (Throwable t)
    {
      noteFailure(pid, String.valueOf(t));
    }
    finally
    {
      if (helper != null && helper.isAlive()) helper.destroyForcibly();
    }
  }

  private static void noteFailure(long pid, String detail)
  {
    if (loggedFailure) return;
    loggedFailure = true;
    if (Sage.DBG)
      System.out.println("ProcessPriority: unable to lower priority of pid " + pid
          + " (" + detail + "); transcodes will run at normal priority on this host");
  }
}
