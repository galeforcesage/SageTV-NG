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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin convenience facade for new SageTV code that wants to log via SLF4J
 * but does not want to declare a {@code private static final Logger}
 * field in every class.
 *
 * <p>Existing legacy {@code if (Sage.DBG) System.out.println(...)} call
 * sites should NOT be migrated en masse — they are auto-bridged through
 * {@link SageLogBridge}. Use this facade only for new modernization
 * work (e.g.&nbsp;{@code sage.HwEncoder}, {@code AC4TranscodeJob}).
 *
 * <p>Logger name is derived from the calling class to give per-package
 * level control via Logback.
 */
public final class SageLog
{
  private SageLog() {}

  private static Logger loggerFor(Class<?> c)
  {
    return LoggerFactory.getLogger(c);
  }

  public static void debug(Class<?> c, String fmt, Object... args)
  { loggerFor(c).debug(fmt, args); }

  public static void info(Class<?> c, String fmt, Object... args)
  { loggerFor(c).info(fmt, args); }

  public static void warn(Class<?> c, String fmt, Object... args)
  { loggerFor(c).warn(fmt, args); }

  public static void error(Class<?> c, String fmt, Object... args)
  { loggerFor(c).error(fmt, args); }

  public static void error(Class<?> c, String msg, Throwable t)
  { loggerFor(c).error(msg, t); }
}
