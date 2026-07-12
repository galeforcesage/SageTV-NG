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

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 1 logging-modernization bridge.
 *
 * <p>Routes legacy {@code System.out} / {@code System.err} writes through
 * SLF4J (Logback). Existing call sites (~5,300 {@code if (Sage.DBG)
 * System.out.println(...)} statements) work unchanged; their output now
 * benefits from Logback's rolling appender, level filtering, structured
 * timestamps, thread names, and the ability to enable/disable per-package
 * verbosity without recompiling.
 *
 * <p>Installation is OPT-IN. Activate by setting Sage property
 * {@code logging/use_slf4j=true} (default {@code false}). When disabled
 * the legacy file rotation in {@link Sage#setupRedirections(String)}
 * remains in effect and this class is never touched.
 *
 * <p>Logback configuration is loaded from
 * {@code -Dlogback.configurationFile=/opt/sagetv/server/logback.xml}
 * (or any classpath {@code logback.xml}). A reference template ships in
 * the source tree.
 */
public final class SageLogBridge
{
  private SageLogBridge() {}

  private static volatile boolean installed = false;
  private static volatile PrintStream originalOut;
  private static volatile PrintStream originalErr;

  /**
   * When true, drop sagex-api's unconditional
   * {@code System.out.printf("Calling: Api: %s; Command: %s;\n")} trace. Because
   * the bridge's PrintStream is autoFlush, that single printf is split into up
   * to five separate {@code sage.stdout} lines per API call, which floods the
   * log (e.g. an in-JVM poller hitting MediaPlayerAPI.GetCurrentMediaFile every
   * ~2s). Read once at {@link #install(String)} from
   * {@code logging/suppress_sagex_api_trace} (default true). Set the property
   * false and restart to restore the tracing.
   */
  static volatile boolean suppressSagexApiTrace = true;

  /** Logger name for everything written through {@code System.out}. */
  public static final String STDOUT_LOGGER = "sage.stdout";
  /** Logger name for everything written through {@code System.err}. */
  public static final String STDERR_LOGGER = "sage.stderr";

  /**
   * Install the bridge unconditionally. Idempotent. Caller (typically
   * {@link Sage#setupRedirections(String)}) is responsible for the
   * property gate.
   *
   * @param prefix log file prefix string passed by Sage; recorded as a
   *               Logback MDC entry so users can include it in patterns.
   */
  public static synchronized void install(String prefix)
  {
    if (installed) return;
    originalOut = System.out;
    originalErr = System.err;

    suppressSagexApiTrace = Sage.getBoolean("logging/suppress_sagex_api_trace", true);

    Logger outLog = LoggerFactory.getLogger(STDOUT_LOGGER);
    Logger errLog = LoggerFactory.getLogger(STDERR_LOGGER);

    System.setOut(new PrintStream(new LineBufferingOutputStream(outLog, false),
                                  /*autoFlush*/ true, StandardCharsets.UTF_8));
    System.setErr(new PrintStream(new LineBufferingOutputStream(errLog, true),
                                  /*autoFlush*/ true, StandardCharsets.UTF_8));

    if (prefix != null && !prefix.isEmpty()) {
      try {
        org.slf4j.MDC.put("prefix", prefix);
      } catch (Throwable ignored) { /* MDC is best-effort */ }
    }

    installed = true;
    outLog.info("SageLogBridge installed; legacy System.out/err routed to SLF4J (prefix={})", prefix);
  }

  /** True once {@link #install(String)} has run. */
  public static boolean isInstalled() { return installed; }

  /**
   * Emergency restore — reinstate the original streams captured at
   * {@link #install(String)} time. Provided for shutdown hooks and unit
   * tests; production code should not call this.
   */
  public static synchronized void uninstall()
  {
    if (!installed) return;
    if (originalOut != null) System.setOut(originalOut);
    if (originalErr != null) System.setErr(originalErr);
    installed = false;
  }

  /**
   * OutputStream that accumulates bytes until a newline (LF), then emits
   * the accumulated line to a SLF4J logger at INFO (or ERROR for stderr).
   * Suppresses empty lines so the typical {@code println()} after a
   * {@code print()} doesn't double up.
   */
  private static final class LineBufferingOutputStream extends OutputStream
  {
    private static final int MAX_LINE_BYTES = 64 * 1024; // safety cap

    private final Logger log;
    private final boolean asError;
    private final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(256);

    /**
     * Per-thread countdown used to swallow the multi-line sagex "Calling: Api:"
     * trace cluster. The opening "Calling: Api:" fragment arms the countdown;
     * the following fragments (api name, "; Command: ", command, ";") on the
     * SAME thread are then dropped. Per-thread state guarantees interleaved
     * logging from other threads is never affected.
     */
    private final ThreadLocal<int[]> apiTrace = ThreadLocal.withInitial(() -> new int[1]);

    LineBufferingOutputStream(Logger log, boolean asError)
    {
      this.log = log;
      this.asError = asError;
    }

    @Override
    public synchronized void write(int b)
    {
      if (b == '\n') {
        flushLine();
      } else if (b != '\r') {
        if (buf.size() < MAX_LINE_BYTES) buf.write(b);
      }
    }

    @Override
    public synchronized void write(byte[] b, int off, int len)
    {
      int end = off + len;
      int start = off;
      for (int i = off; i < end; i++) {
        byte c = b[i];
        if (c == '\n') {
          if (i > start && buf.size() < MAX_LINE_BYTES) {
            int chunk = Math.min(i - start, MAX_LINE_BYTES - buf.size());
            buf.write(b, start, chunk);
          }
          flushLine();
          start = i + 1;
        } else if (c == '\r') {
          if (i > start && buf.size() < MAX_LINE_BYTES) {
            int chunk = Math.min(i - start, MAX_LINE_BYTES - buf.size());
            buf.write(b, start, chunk);
          }
          start = i + 1;
        }
      }
      if (start < end && buf.size() < MAX_LINE_BYTES) {
        int chunk = Math.min(end - start, MAX_LINE_BYTES - buf.size());
        buf.write(b, start, chunk);
      }
    }

    @Override
    public synchronized void flush()
    {
      if (buf.size() > 0) flushLine();
    }

    @Override
    public synchronized void close()
    {
      flush();
    }

    private void flushLine()
    {
      if (buf.size() == 0) return;
      String line = buf.toString(StandardCharsets.UTF_8);
      buf.reset();
      if (line.isEmpty()) return;
      if (!asError && suppressSagexApiTrace && isSagexApiTraceLine(line)) return;
      if (asError) log.error("{}", line);
      else log.info("{}", line);
    }

    /**
     * True if {@code line} is part of sagex ApiHandler's "Calling: Api:" trace
     * cluster and should be dropped. Stateful per-thread: the opening
     * "Calling: Api:" fragment arms a short countdown that swallows the
     * following fragments up to and including the trailing ";" terminator. The
     * countdown is bounded so a malformed/partial cluster can never swallow
     * more than a handful of this thread's own lines.
     */
    private boolean isSagexApiTraceLine(String line)
    {
      int[] c = apiTrace.get();
      if (c[0] > 0) {
        c[0]--;
        if (";".equals(line)) c[0] = 0; // trailing fragment ends the cluster early
        return true;
      }
      if (line.startsWith("Calling: Api:")) {
        c[0] = 5; // safety bound; the ";" terminator normally clears it sooner
        return true;
      }
      return false;
    }
  }
}
