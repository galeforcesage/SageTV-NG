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

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import sage.Sage;

/**
 * The runtime registry of {@link OfflineUpscaleProvider}s and the single place an
 * offline upscaler backend is selected, mirroring
 * {@code sage.enhance.spi.ScaleProviderRegistry} for the batch path.
 *
 * <p>The built-in Real-ESRGAN provider is always present and is the guaranteed
 * fallback. Additional providers (registered at runtime by a separately
 * installed plugin) may be added and removed, but:
 * <ul>
 *   <li>Registering a duplicate id is <b>rejected</b>, never a silent replace.</li>
 *   <li>The selected provider is chosen per call from the property
 *       {@code transcoder/ai_upscale_provider} (default = built-in id).</li>
 *   <li>Command building <b>never throws</b> for provider-side problems: an
 *       unknown id, a null/empty upscale command, or any exception falls back to
 *       the built-in provider's command directly, so a misbehaving plugin can
 *       never fail an offline job outright — it degrades to Real-ESRGAN.</li>
 * </ul>
 *
 * <p>Unlike the live seam there is no per-session lease here: offline batch
 * concurrency is already bounded elsewhere (dropped to 1 while a tuner records),
 * so this registry only selects and builds commands.
 */
public final class OfflineUpscaleRegistry
{
  /** Which offline provider id to prefer. Defaults to the built-in. */
  private static final String PROP_PROVIDER = "transcoder/ai_upscale_provider";

  private static final OfflineUpscaleRegistry INSTANCE = new OfflineUpscaleRegistry();

  private final RealEsrganOfflineProvider builtin = new RealEsrganOfflineProvider();
  private final ConcurrentHashMap<String, OfflineUpscaleProvider> providers =
      new ConcurrentHashMap<String, OfflineUpscaleProvider>();

  private OfflineUpscaleRegistry()
  {
    providers.put(builtin.id(), builtin);
  }

  public static OfflineUpscaleRegistry getInstance() { return INSTANCE; }

  /** The always-present built-in Real-ESRGAN provider. */
  public OfflineUpscaleProvider getBuiltin() { return builtin; }

  /**
   * Register a provider. Rejects a duplicate id (including the built-in id)
   * rather than replacing an active provider.
   *
   * @return a handle whose {@link OfflineUpscaleRegistration#close()} removes
   *         exactly this instance.
   * @throws IllegalArgumentException if the provider or its id is null/blank
   * @throws IllegalStateException if the id is already registered
   */
  public synchronized OfflineUpscaleRegistration register(OfflineUpscaleProvider provider)
  {
    if (provider == null) throw new IllegalArgumentException("null provider");
    String id = provider.id();
    if (id == null || id.trim().isEmpty())
      throw new IllegalArgumentException("provider id is null/blank");
    if (providers.containsKey(id))
      throw new IllegalStateException("offline upscale provider id already registered: " + id);
    providers.put(id, provider);
    return new OfflineUpscaleRegistration(this, provider, id);
  }

  /** Remove a provider by its registration handle, only if it is still the
   *  instance registered under that id. The built-in is never removed. */
  synchronized void unregister(OfflineUpscaleRegistration reg)
  {
    if (reg == null) return;
    String id = reg.getId();
    if (builtin.id().equals(id)) return;
    providers.remove(id, reg.getProvider());
  }

  /** True when a provider is currently registered under this id. */
  public boolean isRegistered(String id) { return providers.containsKey(id); }

  /** The id of the provider that would be selected right now. */
  public String selectedId()
  {
    OfflineUpscaleProvider p = selected();
    return p.id();
  }

  /** The currently selected provider (property-driven), or the built-in when the
   *  configured id is unknown/unset. Never null. */
  public OfflineUpscaleProvider selected()
  {
    String requestedId = Sage.get(PROP_PROVIDER, builtin.id());
    OfflineUpscaleProvider p = providers.get(requestedId);
    return (p == null) ? builtin : p;
  }

  /**
   * Build the upscale command for one request from the selected provider, with a
   * hard fallback to the built-in on any failure or an empty result.
   */
  public List<String> buildUpscaleCommand(OfflineUpscaleRequest request)
  {
    OfflineUpscaleProvider p = selected();
    if (p != builtin)
    {
      try
      {
        List<String> cmd = p.buildUpscaleCommand(request);
        if (cmd != null && !cmd.isEmpty()) return cmd;
        if (Sage.DBG) System.out.println("OFFLINE_UPSCALE provider " + p.id()
            + " produced an empty upscale command; falling back to built-in");
      }
      catch (Throwable t)
      {
        if (Sage.DBG) System.out.println("OFFLINE_UPSCALE provider " + p.id()
            + " failed to build upscale command, falling back to built-in: " + t);
      }
    }
    return builtin.buildUpscaleCommand(request);
  }

  /**
   * Build the device-probe command from the selected provider, with a fallback
   * to the built-in on any failure. A {@code null}/empty result means the
   * provider needs no device probe (the caller treats that as available).
   */
  public List<String> buildProbeCommand()
  {
    OfflineUpscaleProvider p = selected();
    if (p != builtin)
    {
      try
      {
        return p.buildProbeCommand();
      }
      catch (Throwable t)
      {
        if (Sage.DBG) System.out.println("OFFLINE_UPSCALE provider " + p.id()
            + " failed to build probe command, falling back to built-in: " + t);
      }
    }
    return builtin.buildProbeCommand();
  }

  // ---- Test support -------------------------------------------------------

  /** Remove every non-built-in provider. Test-only. */
  public synchronized void resetForTest()
  {
    providers.clear();
    providers.put(builtin.id(), builtin);
  }
}
