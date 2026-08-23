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

import java.util.concurrent.ConcurrentHashMap;

import sage.Sage;

/**
 * The runtime registry of {@link ScaleProvider}s and the single place where a
 * scale backend is <b>selected</b> for a request.
 *
 * <p>The built-in provider is always present and is the guaranteed fallback.
 * Additional providers (registered at runtime by a separately installed plugin)
 * may be added and removed, but:
 * <ul>
 *   <li>Registering a duplicate id is <b>rejected</b>, never a silent replace.</li>
 *   <li>Registration/unregistration only affects <b>future</b> selections. A
 *       session that already captured a selection is unaffected — there is no
 *       mid-stream hot-swap — because {@link #select} produces an immutable
 *       result the caller captures once.</li>
 *   <li>Fallback always targets the built-in provider <b>directly</b>, never a
 *       second registry lookup, so a failing provider cannot recurse into
 *       itself.</li>
 * </ul>
 */
public final class ScaleProviderRegistry
{
  /** Which provider id live requests should prefer. Defaults to the built-in. */
  private static final String PROP_PROVIDER = "playback/gpu_enhance/scale_provider";

  private static final ScaleProviderRegistry INSTANCE = new ScaleProviderRegistry();

  private final BuiltinScaleProvider builtin = new BuiltinScaleProvider();
  private final ConcurrentHashMap<String, ScaleProvider> providers =
      new ConcurrentHashMap<String, ScaleProvider>();

  private ScaleProviderRegistry()
  {
    providers.put(builtin.id(), builtin);
  }

  public static ScaleProviderRegistry getInstance() { return INSTANCE; }

  /** The always-present built-in provider. */
  public ScaleProvider getBuiltin() { return builtin; }

  /**
   * Register a provider. Rejects a duplicate id (including the built-in id)
   * rather than replacing an active provider.
   *
   * @return a handle whose {@link ScaleProviderRegistration#close()} removes
   *         exactly this instance.
   * @throws IllegalArgumentException if the provider or its id is null/blank
   * @throws IllegalStateException if the id is already registered
   */
  public synchronized ScaleProviderRegistration register(ScaleProvider provider)
  {
    if (provider == null) throw new IllegalArgumentException("null provider");
    String id = provider.id();
    if (id == null || id.trim().isEmpty())
      throw new IllegalArgumentException("provider id is null/blank");
    if (providers.containsKey(id))
      throw new IllegalStateException("scale provider id already registered: " + id);
    providers.put(id, provider);
    return new ScaleProviderRegistration(this, provider, id);
  }

  /** Remove a provider by its registration handle, only if it is still the
   *  instance registered under that id. The built-in is never removed. */
  synchronized void unregister(ScaleProviderRegistration reg)
  {
    if (reg == null) return;
    String id = reg.getId();
    if (builtin.id().equals(id)) return;
    providers.remove(id, reg.getProvider());
  }

  /** True when a provider is currently registered under this id. */
  public boolean isRegistered(String id) { return providers.containsKey(id); }

  /**
   * Select and plan a scale backend for one request.
   *
   * <p>Never throws for provider-side problems: an unknown id, an unavailable
   * provider, a denied specialized budget, a malformed plan, or any exception
   * all resolve to the built-in scaler. The returned selection is immutable and
   * is meant to be captured once by the calling session.
   */
  public ScaleSelection select(ScaleRequest request)
  {
    String requestedId = Sage.get(PROP_PROVIDER, builtin.id());
    ScaleProvider provider = providers.get(requestedId);

    // Unknown / unset id, or the built-in itself: take the built-in path with no
    // permit and no fallback flag when it was actually requested.
    if (provider == null || provider == builtin)
      return builtinSelection(request, provider == null && !builtin.id().equals(requestedId));

    ScaleGovernor.Lease lease = null;
    try
    {
      ScaleProviderAvailability avail = provider.probe(request);
      if (avail == null || !avail.isAvailable())
        return builtinSelection(request, true);

      boolean specialized = provider.capabilities() != null
          && provider.capabilities().isSpecialized();
      if (specialized)
      {
        lease = ScaleGovernor.getInstance().acquire(requestedId, request);
        if (lease == null)   // budget exhausted
          return builtinSelection(request, true);
      }

      ScaleExecutionPlan exec = provider.plan(request);
      if (exec == null || !exec.isRenderablePhase0())
      {
        closeQuietly(lease);
        return builtinSelection(request, true);
      }

      return new ScaleSelection(requestedId, exec, lease, false);
    }
    catch (Throwable t)
    {
      closeQuietly(lease);
      if (Sage.DBG) System.out.println("SCALE_PROVIDER select failed for "
          + requestedId + ", falling back to built-in: " + t);
      return builtinSelection(request, true);
    }
  }

  /** Build a selection from the built-in provider directly (no registry lookup,
   *  no permit). */
  private ScaleSelection builtinSelection(ScaleRequest request, boolean fellBack)
  {
    ScaleExecutionPlan exec;
    try
    {
      exec = builtin.plan(request);
    }
    catch (Throwable t)
    {
      // The built-in is trusted; if it somehow fails, hand back a null plan so
      // the core renders its own legacy scale fragment.
      exec = null;
    }
    return new ScaleSelection(builtin.id(), exec, null, fellBack);
  }

  private static void closeQuietly(ScaleGovernor.Lease lease)
  {
    if (lease != null) { try { lease.close(); } catch (Throwable ignore) {} }
  }

  // ---- Test support -------------------------------------------------------

  /** Remove every non-built-in provider. Test-only. */
  public synchronized void resetForTest()
  {
    providers.clear();
    providers.put(builtin.id(), builtin);
  }
}
