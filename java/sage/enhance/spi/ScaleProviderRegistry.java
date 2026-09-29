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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
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
  /**
   * Optional operator override naming a preferred provider id to try <b>first</b>.
   *
   * <p>Empty by default: with no value set the registry runs its automatic
   * preference chain (specialized AI upscaler &rarr; CUDA-Lanczos &rarr; none) and
   * needs no configuration. When set to a registered, non-built-in id it only
   * moves that provider to the head of the chain &mdash; it is a soft preference,
   * never a hard pin, so an unavailable head still falls through to the rest of
   * the chain. There is deliberately no property that <i>selects</i> the upscaler
   * outright; the client's intent plus the automatic chain drive that.
   */
  private static final String PROP_PROVIDER = "playback/gpu_enhance/scale_provider";

  private static final ScaleProviderRegistry INSTANCE = new ScaleProviderRegistry();

  private final BuiltinScaleProvider builtin = new BuiltinScaleProvider();
  private final CudaLanczosScaleProvider cudaLanczos = new CudaLanczosScaleProvider();
  private final ConcurrentHashMap<String, ScaleProvider> providers =
      new ConcurrentHashMap<String, ScaleProvider>();

  /** Deterministic ordering within a preference class, by provider id. */
  private static final Comparator<ScaleProvider> BY_ID =
      new Comparator<ScaleProvider>() {
        public int compare(ScaleProvider a, ScaleProvider b) { return a.id().compareTo(b.id()); }
      };

  private ScaleProviderRegistry()
  {
    providers.put(builtin.id(), builtin);
    providers.put(cudaLanczos.id(), cudaLanczos);
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
   * True when this deployment can perform a server-side upscale for <i>some</i>
   * request &mdash; the advisor's cheap first gate before it advertises any
   * enhance tier.
   *
   * <p>Chain-aware: it is satisfied when either a specialized upscaling provider
   * is registered (an AI upscaler such as VSR, whose runtime availability the
   * per-request probe gate confirms later) <b>or</b> the always-present
   * CUDA-Lanczos provider is actually usable on this box (a CUDA scaler is
   * present). With only the passthrough built-in and no CUDA scaler &mdash; a
   * no-GPU or filter-less build &mdash; it stays false, so such a deployment
   * delivers the source and the client scales, exactly as before.
   */
  public boolean selectedProviderCanUpscale()
  {
    for (ScaleProvider p : providers.values())
    {
      if (p == builtin || p == cudaLanczos) continue;
      ScaleProviderCapabilities caps = p.capabilities();
      if (caps != null && caps.supportsUpscale()) return true;
    }
    // The deterministic CUDA-Lanczos fallback: available only when enabled and
    // this ffmpeg actually offers a CUDA scaler.
    return cudaLanczos.isUsableHere();
  }

  /**
   * The upscale preference chain for {@code request}: the ordered list of
   * upscale-capable providers selection will try, best first. Specialized AI
   * upscalers (e.g. VSR) come first, then non-specialized deterministic GPU
   * scalers (CUDA-Lanczos); the passthrough built-in is never in the chain (it
   * does not upscale). An optional {@link #PROP_PROVIDER} soft-preference head is
   * moved to the front when it names a registered upscaler. Empty for a
   * non-upscaling request. The built-in is the terminal fallback and is applied
   * by the caller, not listed here.
   */
  private List<ScaleProvider> upscaleChain()
  {
    List<ScaleProvider> specialized = new ArrayList<ScaleProvider>();
    List<ScaleProvider> deterministic = new ArrayList<ScaleProvider>();
    for (ScaleProvider p : providers.values())
    {
      if (p == builtin) continue;
      ScaleProviderCapabilities caps = p.capabilities();
      if (caps == null || !caps.supportsUpscale()) continue;
      if (caps.isSpecialized()) specialized.add(p); else deterministic.add(p);
    }
    Collections.sort(specialized, BY_ID);
    Collections.sort(deterministic, BY_ID);
    List<ScaleProvider> chain = new ArrayList<ScaleProvider>(specialized);
    chain.addAll(deterministic);

    // Optional soft-preference head: promote, never restrict.
    String head = Sage.get(PROP_PROVIDER, "").trim();
    if (head.length() > 0 && !builtin.id().equals(head))
    {
      ScaleProvider p = providers.get(head);
      if (p != null && p != builtin && chain.remove(p)) chain.add(0, p);
    }
    return chain;
  }

  /**
   * Like {@link #selectedProviderCanUpscale()} but also verifies the selected
   * provider is actually available <b>right now</b> and would render a real
   * upscale for {@code probe} -- it runs the provider's cheap, side-effect-free
   * {@code probe()} and {@code plan()} without acquiring a {@link ScaleGovernor}
   * permit.
   *
   * <p>This exists so the advisor's <i>offer</i> agrees with what {@link #select}
   * will actually <i>build</i>. The static {@link #selectedProviderCanUpscale()}
   * only reflects a provider's declared capability, so a provider that is
   * registered and upscale-capable but momentarily unavailable -- a dev build, a
   * model still warming up, a busy GPU -- would pass the advisor yet fail the
   * plan-time backstop, leaving the client promised an enhance tier it never
   * receives (it would silently get a plain remux instead). Gating the offer on
   * the same probe the plan builder uses makes an unavailable provider behave
   * exactly like no provider: the tier is not offered and the client scales the
   * source itself.
   *
   * @param probe a {@link ScaleRequest.Purpose#PROBE} request describing the
   *              tier the advisor is about to offer
   */
  public boolean selectedProviderCanUpscale(ScaleRequest probe)
  {
    List<ScaleProvider> chain = upscaleChain();
    if (chain.isEmpty())
    {
      System.out.println("SCALE_PROVIDER offer-gate: no upscale-capable provider registered"
          + " -> not offering upscale (delivering source)");
      return false;
    }
    for (ScaleProvider p : chain)
    {
      String id = p.id();
      try
      {
        ScaleProviderAvailability avail = p.probe(probe);
        if (avail == null || !avail.isAvailable())
        {
          System.out.println("SCALE_PROVIDER offer-gate: provider '" + id + "' probe unavailable: "
              + (avail == null ? "null" : avail.getDetail()) + " -> trying next in chain");
          continue;
        }
        ScaleExecutionPlan exec = p.plan(probe);
        if (exec == null || !exec.isRenderable())
        {
          System.out.println("SCALE_PROVIDER offer-gate: provider '" + id + "' plan "
              + describePlan(exec) + " -> trying next in chain");
          continue;
        }
        System.out.println("SCALE_PROVIDER offer-gate: provider '" + id
            + "' will render -> offering upscale");
        return true;
      }
      catch (Throwable t)
      {
        System.out.println("SCALE_PROVIDER offer-gate: provider '" + id
            + "' probe/plan threw " + t + " -> trying next in chain");
        if (Sage.DBG) t.printStackTrace(System.out);
      }
    }
    System.out.println("SCALE_PROVIDER offer-gate: no provider in the chain can render now"
        + " -> not offering upscale (delivering source)");
    return false;
  }

  /**
   * Select and plan a scale backend for one request by walking the upscale
   * preference chain (specialized AI upscaler &rarr; CUDA-Lanczos), falling
   * through to the next candidate on any per-provider problem and ending at the
   * built-in passthrough.
   *
   * <p>Never throws for provider-side problems: an unavailable provider, a denied
   * specialized budget, a malformed plan, or any exception advances to the next
   * candidate; if none can render, the built-in scaler (source resolution) is
   * returned. A non-upscaling (deinterlace-only) request goes straight to the
   * built-in, exactly as before &mdash; the chain governs the scale stage only.
   * The returned selection is immutable and is meant to be captured once by the
   * calling session.
   */
  public ScaleSelection select(ScaleRequest request)
  {
    return select(request, true);
  }

  /**
   * Select a scale backend, optionally excluding every specialized provider.
   * This fail-closed admission bridge is used when an installed external
   * authority did not explicitly grant neural scaling.
   */
  public ScaleSelection select(ScaleRequest request, boolean allowSpecialized)
  {
    // Deinterlace-only / non-upscaling: the built-in passthrough (the core still
    // deinterlaces). Choosing it here is not a fallback.
    if (request == null || !request.isUpscaling())
      return builtinSelection(request, false);

    for (ScaleProvider provider : upscaleChain())
    {
      String id = provider.id();
      ScaleGovernor.Lease lease = null;
      try
      {
        boolean specialized = provider.capabilities() != null
            && provider.capabilities().isSpecialized();
        if (specialized && !allowSpecialized)
        {
          System.out.println("SCALE_PROVIDER select: provider '" + id
              + "' skipped -- specialized scaling lacks an explicit external grant");
          continue;
        }
        ScaleProviderAvailability avail = provider.probe(request);
        if (avail == null || !avail.isAvailable())
        {
          System.out.println("SCALE_PROVIDER select: provider '" + id
              + "' probe unavailable: " + (avail == null ? "null" : avail.getDetail())
              + " -> trying next in chain");
          continue;
        }

        if (specialized)
        {
          lease = ScaleGovernor.getInstance().acquire(id, request);
          if (lease == null)   // budget exhausted
          {
            System.out.println("SCALE_PROVIDER select: provider '" + id
                + "' specialized budget exhausted -> trying next in chain");
            continue;
          }
        }

        ScaleExecutionPlan exec;
        WarmContext warm = ScaleWarmupCache.getInstance().consume(id, request);
        if (warm != null)
        {
          // A pre-warmed worker is ready: let the provider fold it into its plan
          // (it may still cold-fall-back internally on a dimensions mismatch, in
          // which case it closes the context and returns a fresh cold plan).
          if (Sage.DBG) System.out.println("SCALE_PROVIDER select: provider '" + id
              + "' consuming pre-warmed context");
          exec = provider.plan(request, warm);
        }
        else
        {
          exec = provider.plan(request);
        }
        if (exec == null || !exec.isRenderable())
        {
          System.out.println("SCALE_PROVIDER select: provider '" + id
              + "' plan " + describePlan(exec) + " -> trying next in chain");
          closeQuietly(lease);
          continue;
        }

        return new ScaleSelection(id, exec, lease, false);
      }
      catch (Throwable t)
      {
        closeQuietly(lease);
        System.out.println("SCALE_PROVIDER select: provider '" + id
            + "' threw " + t + " -> trying next in chain");
        if (Sage.DBG) t.printStackTrace(System.out);
      }
    }

    // Nothing in the chain could render: deliver the source (built-in).
    return builtinSelection(request, true);
  }

  /**
   * Pre-warm the currently selected provider for an anticipated {@code req},
   * called by the advisor when it decides to offer an upscale tier. A no-op when
   * warmup is disabled, or when the selection resolves to the built-in (which
   * never needs warming). Never blocks: the actual {@code warmup()} runs on the
   * {@link ScaleWarmupCache} executor. Never throws.
   */
  public void warmupSelectedProvider(ScaleRequest req)
  {
    try
    {
      // Warmup starts specialized worker resources before normal admission. With
      // an external authority installed there is no explicit grant yet, so doing
      // that would be unmanaged neural work. Brokered sessions cold-start only
      // after their grant; deterministic CUDA-Lanczos needs no warmup.
      if (sage.enhance.GpuGovernor.getInstance().getExternalAuthority() != null)
      {
        if (Sage.DBG) System.out.println("SCALE_WARMUP skipped -- external admission"
            + " authority installed and no per-session grant exists yet");
        return;
      }
      ScaleWarmupCache cache = ScaleWarmupCache.getInstance();
      if (!cache.isEnabled() || req == null || !req.isUpscaling()) return;
      // Only a specialized provider (expensive cold start: model load, worker
      // spawn) benefits from warmup; the CUDA-Lanczos filter form starts
      // instantly. Warm the first specialized candidate in the chain -- the one
      // select() would pick first.
      for (ScaleProvider p : upscaleChain())
      {
        ScaleProviderCapabilities caps = p.capabilities();
        if (caps != null && caps.isSpecialized())
        {
          cache.requestWarmup(p.id(), p, req);
          return;
        }
      }
    }
    catch (Throwable t)
    {
      if (Sage.DBG) System.out.println("SCALE_WARMUP warmupSelectedProvider threw " + t);
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

  /** Concise, plugin-diagnostic description of why a provider plan was rejected
   *  (or accepted), naming the execution form and the specific empty/missing
   *  field that made {@link ScaleExecutionPlan#isRenderable()} false. Logged on
   *  the fail-closed fallback so a plugin author can see what their {@code
   *  plan()} returned without attaching a debugger. */
  private static String describePlan(ScaleExecutionPlan exec)
  {
    if (exec == null) return "is null";
    if (exec.isRenderable()) return "is renderable (form=" + exec.getForm() + ")";
    StringBuilder sb = new StringBuilder("not renderable (form=").append(exec.getForm());
    if (exec.getForm() == ExecutionForm.BUILTIN || exec.getForm() == ExecutionForm.FFMPEG_FILTER)
    {
      String f = exec.getFfmpegFilter();
      sb.append(", filter=").append(f == null ? "null" : (f.isEmpty() ? "empty" : "'" + f + "'"));
    }
    else if (exec.getForm() == ExecutionForm.EXTERNAL_PROCESS)
    {
      java.util.List<String> a = exec.getExternalArgv();
      sb.append(", externalArgv=")
        .append(a == null ? "null" : (a.isEmpty() ? "empty" : a.size() + " args"))
        .append(", out=").append(exec.getOutputWidth()).append('x').append(exec.getOutputHeight());
    }
    return sb.append(')').toString();
  }

  // ---- Test support -------------------------------------------------------

  /** Remove every non-built-in provider. Test-only. The always-present built-in
   *  passthrough and CUDA-Lanczos providers are retained. */
  public synchronized void resetForTest()
  {
    providers.clear();
    providers.put(builtin.id(), builtin);
    providers.put(cudaLanczos.id(), cudaLanczos);
  }
}
