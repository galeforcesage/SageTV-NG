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
 * The immutable outcome of {@link ScaleProviderRegistry#select}: the captured
 * scale execution plan, the specialized permit it holds (if any), and which
 * provider produced it.
 *
 * <p>A session captures this once at planning time. The permit — when present —
 * must be released exactly once by closing {@link #getLease()} (or, equivalently,
 * by closing the plan that captured it). A built-in selection holds no permit,
 * so its lease is {@code null}.
 */
public final class ScaleSelection
{
  private final String providerId;
  private final ScaleExecutionPlan executionPlan;
  private final ScaleGovernor.Lease lease;
  private final boolean fellBackToBuiltin;

  public ScaleSelection(String providerId, ScaleExecutionPlan executionPlan,
                        ScaleGovernor.Lease lease, boolean fellBackToBuiltin)
  {
    this.providerId = providerId;
    this.executionPlan = executionPlan;
    this.lease = lease;
    this.fellBackToBuiltin = fellBackToBuiltin;
  }

  public String getProviderId() { return providerId; }

  /** The captured scale stage, or null when no provider could plan (the core
   *  then renders its own legacy scale fragment). */
  public ScaleExecutionPlan getExecutionPlan() { return executionPlan; }

  /** The specialized permit held by this selection, or null for the built-in
   *  path. Must be closed exactly once by the capturing session. */
  public ScaleGovernor.Lease getLease() { return lease; }

  public boolean fellBackToBuiltin() { return fellBackToBuiltin; }

  @Override
  public String toString()
  {
    return "ScaleSelection[" + providerId
        + (fellBackToBuiltin ? " (fell back)" : "")
        + " " + executionPlan
        + (lease != null && !lease.isNoop() ? " +permit" : "") + "]";
  }
}
