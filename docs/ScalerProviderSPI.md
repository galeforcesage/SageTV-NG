# Scaler Provider SPI

SageTV-NG performs live, in-VRAM GPU enhancement of a transcode by rewriting the
ffmpeg argv to add `-hwaccel cuda`, a `-vf` chain (deinterlace + **scale**), and
`hevc_nvenc`. The **scale stage** — and only the scale stage — is selected
through a small, vendor-neutral provider seam so an alternative scaler backend
can be supplied by a separately installed plugin **without any of that backend's
code living in this repository**.

This document is the contract for implementing a scaler provider. The seam lives
in package `sage.enhance.spi` and is integrated at
`GpuEnhancePipeline.buildPlan()` / `buildFilterChain()` with lease lifecycle in
`FFMPEGTranscoder`. `BuiltinScaleProvider` is the always-present default.

> **The built-in does not upscale; the CUDA-Lanczos provider does.** The built-in
> provider is a **passthrough**: it renders the mandatory deinterlace/re-encode
> but emits **no scale filter**. In this fork live upscaling is provided by the
> **automatic preference chain**: a specialized AI provider (e.g. VSR, installed
> by a plugin) is tried first, then the always-registered
> `CudaLanczosScaleProvider` (a deterministic CUDA/Lanczos stage, available
> whenever this ffmpeg exposes `scale_cuda`/`scale_npp`), then the passthrough
> (source). Server-side upscaling therefore happens whenever **any** chain member
> can render; with none available (no GPU/filters and no plugin), the
> `EnhancementAdvisor` does not offer an upscale tier — clients receive the plain
> source stream and scale it themselves. There is **no** operator property that
> must name a provider. (Offline/batch conversion is a separate subsystem and keeps its own
> upscaler — see §10.)

> The SPI is deliberately backend-neutral. A provider that wraps a proprietary or
> EULA'd runtime ships and installs as a separate artifact; only the neutral seam
> is part of this repo.

---

## 1. Scope: what a provider owns

A provider owns the **scale step only**. It does **not** own — and cannot reach —
deinterlacing, the bitrate ladder, recording protection, NVENC session admission,
or client seek/timeline behavior. Those stay in the core. By the time a provider
is consulted, the core has already applied recording protection, general GPU
admission, and any tier degradation; the provider's job is purely "render this
frame size."

---

## 2. Types

All in `sage.enhance.spi`:

| Type | Kind | Role |
|---|---|---|
| `ScaleProvider` | interface | You implement it. |
| `ScaleRequest` | immutable input | The job to scale. |
| `ScaleProviderCapabilities` | immutable | Static self-description. |
| `ScaleProviderAvailability` | immutable | `available()` / `unavailable(detail)`. |
| `ScaleExecutionPlan` | immutable | How you want the scale stage realized. |
| `ExecutionForm` | enum | `BUILTIN`, `FFMPEG_FILTER`, `EXTERNAL_PROCESS`, `SIDECAR`. |
| `ScaleProviderRegistry` | singleton | `register()` / `select()`; runs the upscale preference chain. |
| `ScaleProviderRegistration` | `AutoCloseable` | Handle; `close()` unregisters. |
| `ScaleGovernor`, `ScaleGovernor.Lease` | admission budget | Core-managed. |
| `ScaleSelection` | immutable | Internal selection result. |
| `BuiltinScaleProvider` | class | Default passthrough (deinterlace-only, no upscale) + guaranteed fallback. |
| `CudaLanczosScaleProvider` | class | Always-registered deterministic CUDA/Lanczos live upscaler; the chain's second choice after a specialized AI provider. |

### 2.1 The interface

```java
public interface ScaleProvider {
  String id();                                       // stable, unique
  ScaleProviderCapabilities capabilities();          // static self-description
  ScaleProviderAvailability probe(ScaleRequest r);   // cheap, side-effect free
  ScaleExecutionPlan plan(ScaleRequest r);           // the scale stage for ONE request
}
```

Hard rules (enforced by the core; breaking them gets you skipped, never a crash):

- `probe()` must be **cheap and side-effect free**. No model load, no GPU
  allocation, no sidecar spin-up, no network. It answers "could I handle this
  *right now*?" only.
- `plan()` returns the scale stage for exactly one request and must not mutate
  shared state.
- **Any exception** from `probe()` or `plan()` is treated as *unavailable* and
  the core falls back to the built-in passthrough. A provider cannot break playback.

### 2.2 `ScaleRequest` (what the core hands you)

```java
EnhancementTier getTier();      // ENHANCE_1080P/1440P/2160P, DEINTERLACE_ONLY, NONE
int  getTargetWidth();          // e.g. 3840
int  getTargetHeight();         // e.g. 2160
int  getSourceHeight();         // e.g. 720, 1080
boolean isSourceInterlaced();   // true for 1080i etc.
String getBuiltinScalerHint();  // "scale_npp" | "scale_cuda" | null
Purpose getPurpose();           // LIVE or PROBE
boolean isProbe();
boolean isUpscaling();          // tier actually changes frame size
```

It carries only what a scaler needs — no bitrate, client identity, or admission
state — so a provider cannot influence those decisions.

- `getBuiltinScalerHint()` is the CUDA scaler *this ffmpeg build* offers; a
  specialized provider may ignore it.
- A `DEINTERLACE_ONLY` / non-upscaling request still goes through selection but
  has `isUpscaling() == false`. Return a plan with a **null filter** — the core
  still renders the deinterlacer.

### 2.3 `ScaleExecutionPlan` (what you return)

**Filter form** (in-process `-vf` fragment):

```java
new ScaleExecutionPlan(ExecutionForm form, String ffmpegFilter, String implementationLabel);
```

- `ffmpegFilter` — the **scale-stage `-vf` fragment only** (e.g.
  `myscaler=w=3840:h=2160`). Never include the deinterlacer; the core prepends
  it. `null` for non-filter forms and for non-upscaling plans.
- `implementationLabel` — honest, human-readable mechanism for telemetry/logs.

**External-process form** (a provider-owned worker the core spawns):

```java
new ScaleExecutionPlan(ExecutionForm.EXTERNAL_PROCESS, List<String> workerArgv,
                       int outputWidth, int outputHeight, String pipePixelFormat,
                       String implementationLabel);
```

- `workerArgv` — the worker command line, spawned **verbatim, with no shell**.
- `outputWidth` / `outputHeight` — the exact size of each frame the worker writes
  to its stdout; the core sizes the encode stage from these.
- `pipePixelFormat` — the pixel format on the stdio pipes (`rgb24` today).

The core uses the plan only if `isRenderable()` is true — either a non-empty
filter fragment (`BUILTIN`/`FFMPEG_FILTER`), or an `EXTERNAL_PROCESS` plan with a
non-empty `workerArgv` and a positive output geometry. Otherwise no scale fragment
is rendered and the stream is delivered at source resolution — the built-in
passthrough (see §5). (`isRenderablePhase0()` is a deprecated alias for
`isRenderable()`.) `SIDECAR` is declared but not yet rendered.

#### 2.3.1 External-process live frame transport

When an `EXTERNAL_PROCESS` plan is selected on the live path, the core renders a
three-process pipeline and streams raw frames between the stages over OS pipes:

```
ffmpeg (decode) --rgb24--> worker (yours) --rgb24--> ffmpeg (encode, NVENC)
```

- **Decode stage** decodes the source, deinterlaces on the CPU, and writes
  headerless `rawvideo` at the **source** resolution
  (`source_w × source_h × 3` bytes per frame).
- **Worker** reads one input-frame-sized blob, upscales, and writes one
  output-frame-sized blob (`output_w × output_h × 3` bytes). No per-frame header,
  length prefix, or metadata — dimensions are fixed for the whole session from the
  argv. Row-major, top-to-bottom, R-G-B per pixel (identical to ffmpeg
  `-pix_fmt rgb24`).
- **Encode stage** reads the upscaled frames, re-reads the source for its audio
  (same input flags, so any `-ss` seek stays aligned), applies NVENC HEVC, and
  writes the delivery container to stdout.

**Lifecycle.** The worker prints `READY\n` to **stderr** once its model is warm.
The core's provider-neutral warmup budget is 20 seconds end to end: up to
8 seconds for external resource acquisition and up to 12 seconds for runtime/model
readiness. Providers receive this split through `WarmupBudget`. A cold external
worker must reach READY within the same 12-second readiness phase; the core caps
`playback/gpu_enhance/scale/external_worker_ready_timeout_ms` at 12000 (the
legacy `external_worker_startup_timeout_seconds` remains a capped fallback).
If `READY` never arrives (or the worker exits first), the core
abandons the external pipeline and falls back to the built-in passthrough command for
this session — the client still gets a stream, but it **deinterlaces/re-encodes only
and does NOT upscale** (source resolution). On clean shutdown the core closes the
worker's stdin (EOF); the worker flushes and exits. If no frames flow for
`playback/gpu_enhance/scale/external_worker_stall_timeout_seconds` (default 5) the core
kills the worker and the session tears down (a mid-session worker cannot be hot-swapped
back to the built-in).

**No vendor code lives in the core.** The core only knows how to spawn the argv
the plan hands it and pipe raw frames; the worker, its model, and any EULA'd
runtime ship and install with your plugin.

A provider may keep a stream-independent resident worker warm and return a
short-lived, already-READY per-playback proxy in its `WarmContext` plan. The core
never starts dummy decode/encode processes during warmup: NVDEC/NVENC remain
stream-bound and are created only after real playback admission. If an
`ExternalAdmissionAuthority` is installed, a specialized provider must override
`managesWarmupAdmission()` to return true before core will call its proactive
warmup hook. That opt-in asserts that the provider independently owns the
authority/lease lifecycle for warm work; existing providers default to false so
they cannot accidentally start unmanaged specialized work.

### 2.4 Capabilities & availability

```java
new ScaleProviderCapabilities(String id, boolean specialized,
                              boolean supportsUpscale, int nominalMaxConcurrent);

ScaleProviderAvailability.available();
ScaleProviderAvailability.unavailable("reason");   // reason is logged
```

`specialized` is the load-bearing flag: a specialized provider consumes a scarce,
inference-like resource and is admitted through the separate `ScaleGovernor`
budget (§4). The built-in is **not** specialized and takes no permit, which is
what keeps the default path byte-for-byte unchanged. `nominalMaxConcurrent` is
advisory in this phase; the live cap is the governor property.

---

## 3. Selection flow

Per enhanced session, once, at plan time (`buildPlan()` → `ScaleProviderRegistry.select()`):

For an **upscaling** request the registry builds the **preference chain** —
specialized upscale-capable providers first (e.g. VSR), then non-specialized
ones (`CudaLanczosScaleProvider`) — and walks it, best first:

1. It calls the candidate's `probe(request)`. `null`/unavailable → **try the next
   candidate**.
2. If the candidate declared `specialized`, it acquires a `ScaleGovernor` permit.
   Budget exhausted → **try the next candidate**.
3. It calls `plan(request)`. `null`, empty, or a non-renderable form → permit
   released, **try the next candidate**.
4. Success → the session **captures** that plan (and permit) for its lifetime.
5. If no candidate can render, the built-in passthrough is used (source
   resolution). A non-upscaling / `DEINTERLACE_ONLY` request skips the chain
   entirely and uses the built-in directly.

An optional soft-preference head, `playback/gpu_enhance/scale_provider`, is
**empty by default**; when set to a registered id it only moves that provider to
the front of the chain — it never pins or restricts, so an unavailable head still
falls through. There is deliberately no property that *selects* the upscaler.

Guarantees:

- **Selection is captured once.** Registering/unregistering a provider or
  changing the property affects only *future* sessions. There is **no mid-stream
  hot-swap** of an active playback.
- **The chain degrades in order**, ending at the built-in directly (never a
  second registry lookup), so a failing provider can't recurse.
- **`buildFilterChain()` only renders the captured plan** — no registry lookup,
  no governor acquire — which is what makes capture-once safe.

---

## 4. Concurrency — `ScaleGovernor`

A **separate** budget for specialized providers, distinct from ordinary
NVENC/general GPU admission (`sage.enhance.GpuGovernor`) and recording protection.

- Property: `playback/gpu_enhance/scale/max_specialized_sessions` (int, default
  `1`).
- A `LIVE` specialized selection takes one permit; the core releases it exactly
  once (idempotent) on transcoder stop, client disconnect, or startup failure. A
  provider never touches the lease.
- A `PROBE` request never retains a permit.
- Built-in selections take no permit.

To allow more concurrent specialized sessions, raise the property. A provider's
declared `nominalMaxConcurrent` does not auto-raise the governor in this phase.

---

## 5. Execution forms

A backend that is a genuine ffmpeg filter is fundamentally different from one that
runs out of process. The core is explicit about what it renders today:

| Form | Meaning | Rendered today? |
|---|---|---|
| `BUILTIN` | Passthrough — no scale fragment (deinterlace/re-encode only). A directly-constructed calibration/offline-parity plan may still carry a CUDA scale fragment here. | Yes. |
| `FFMPEG_FILTER` | A provider-supplied `-vf` scale fragment the deployed ffmpeg can execute. | **Yes.** |
| `EXTERNAL_PROCESS` | A native worker process the provider owns, spawned into a `decode → worker → encode` pipeline. | **Yes** (see §2.3.1). |
| `SIDECAR` | A long-lived sidecar service. | Not yet rendered. |

- If a backend can be expressed as a `-vf` fragment the deployed ffmpeg
  understands (a built-in filter, or a custom libavfilter CUDA filter compiled
  into the server's ffmpeg), it can ship as a `FFMPEG_FILTER` provider and stay
  fully in-VRAM.
- An out-of-process backend ships as an `EXTERNAL_PROCESS` provider: it returns a
  worker argv and the output frame geometry, and the core renders the three-stage
  pipeline described in §2.3.1 (headerless `rgb24` frames over stdio, `READY`
  handshake, startup/stall timeouts, and fallback to the built-in passthrough
  (deinterlace-only, no upscale) on failure). No vendor code enters the core.
- `SIDECAR` (a long-lived daemon shared across sessions) is still declared but not
  yet rendered; returning it today falls back to the built-in.

---

## 5.1 Warmup (optional pre-warm to eliminate cold-start)

> Status: SPI surface and core wiring are active by default. The path remains
> inert for providers that keep the default `warmup()` implementation, and can
> be disabled with `playback/gpu_enhance/scale/warmup_enabled=false`.

An `EXTERNAL_PROCESS` provider may need seconds to become ready (model load,
shader compile, GPU context init). Paid at play-start that cost is a black
screen. The advisory phase evaluates providers seconds *before* playback begins,
which is a natural window to pre-warm one. The seam is two `default` methods on
`ScaleProvider` plus an opaque `WarmContext` handle the core holds between the
advisory phase and pipeline build:

```java
public interface ScaleProvider {
  // ... id(), capabilities(), probe(), plan(ScaleRequest) ...

  /** Begin expensive init for an anticipated session; return a handle the core
   *  holds and passes back at build time, or null for no warmup. May block. */
  default WarmContext warmup(ScaleRequest request) { return null; }

  /** Budget-aware form; the default preserves old provider behavior. */
  default WarmContext warmup(ScaleRequest request, WarmupBudget budget) {
    return warmup(request);
  }

  /** True only when this provider independently admits its warm resource. */
  default boolean managesWarmupAdmission() { return false; }

  /** Build a plan using a still-valid pre-warmed context. If unusable, close it,
   *  fall back to plan(request), and let the core cold-start. */
  default ScaleExecutionPlan plan(ScaleRequest request, WarmContext warm) {
    if (warm != null) warm.close();
    return plan(request);
  }
}
```

`WarmContext extends AutoCloseable`:

| Member | Contract |
|---|---|
| `long ttlMillis()` | Max delay between offer and play-start; the core closes and discards the context if it is not consumed within this window. 30–60 s is typical. Non-positive is treated as already-expired. |
| `boolean isValid()` | The core checks this before `plan(req, warm)`; a crashed worker / dropped connection / reclaimed GPU returns false and the core cold-starts. |
| `void close()` | Release everything (kill process, free VRAM). **Idempotent** — may be called after `plan()` consumed it, or twice by the reaper. |

Guarantees the core gives a provider:

- **Optional.** Return `null` from `warmup()` and nothing changes (the default).
- **Single-owner.** A `WarmContext` is consumed at most once, then closed; it is
  never shared across concurrent sessions.
- **Bounded.** If the offer is never taken, a reaper calls `close()` after
  `ttlMillis()` — a warm worker cannot leak VRAM indefinitely. Acquire plus
  readiness is also hard-capped at 20 seconds (8 + 12 by default). At the
  deadline core cancels and interrupts the warmup task; providers must honor
  interruption, abort external work, and close partial acquisitions.
- **Matched by geometry.** The core only hands a warm context back to
  `  plan(req, warm)` when the live request's provider id, source height, target
  WxH, and tier match the request the context was warmed for. On any mismatch the core
  closes the stale context and cold-starts, so a provider's `plan(req, warm)` can
  assume the dimensions line up (but should still verify and cold-fall-back).

Consuming a resident worker: create a short-lived per-playback proxy and return
a plan carrying that already-READY proxy process, so the core skips process
spawn and the READY wait (the argv is retained for logging only). The resident
worker remains provider-owned; core may terminate the proxy during normal
stream teardown:

```java
@Override public ScaleExecutionPlan plan(ScaleRequest req, WarmContext warm) {
  VsrWarmContext ctx = (VsrWarmContext) warm;
  if (!ctx.isValid() || dimensionsMismatch(req, ctx)) { ctx.close(); return plan(req); }
  return ScaleExecutionPlan.externalWithWarmProcess(
      WorkerCommand.buildLiveStream(...),   // diagnostics only when warm
      req.getTargetWidth(), req.getTargetHeight(), "rgb24", "nvidia-vsr",
      ctx.openPlaybackProxy());             // core owns this proxy, not resident
}
```

Resolved design decisions (deviations from the draft proposal, noted for the
plugin team):

- **Non-blocking.** `warmup()` runs on a dedicated single-thread executor. The
  advisory never blocks the offer on it; the offer is made from `probe()`/`plan()`
  as today, and the warm context is resolved (with a short bounded wait) at build
  time. Blocking the offer for a 9 s model load would defeat the point.
- **No speculative core permit.** Core does not hold a `ScaleGovernor` permit
  during warmup; the normal permit is acquired at consume time inside `select()`.
  Under an installed `ExternalAdmissionAuthority`, core calls proactive warmup
  only when `managesWarmupAdmission()` is true. Such a provider owns its
  low-priority/reclaimable warm-resource lease and must reconcile reclaim before
  returning a context.
- **Keyed by request geometry, not media id.** The consume point
  (`ScaleProviderRegistry.select(ScaleRequest)`) carries only the request, so the
  stash key is `(providerId, srcW, srcH, targetW, targetH, tier)` — which also
  gives the dimensions-match guarantee above for free.

---

## 6. Reference: provider + registration

Filter-form provider:

```java
import sage.enhance.spi.*;

public final class MyScaleProvider implements ScaleProvider {
  public static final String ID = "my-scaler";

  private static final ScaleProviderCapabilities CAPS =
      new ScaleProviderCapabilities(ID, /*specialized*/ true, /*supportsUpscale*/ true, /*nominalMax*/ 2);

  @Override public String id() { return ID; }
  @Override public ScaleProviderCapabilities capabilities() { return CAPS; }

  @Override public ScaleProviderAvailability probe(ScaleRequest r) {
    if (r == null || !r.isUpscaling()) return ScaleProviderAvailability.available();
    if (!MyRuntime.ready())            return ScaleProviderAvailability.unavailable("runtime not ready");
    return ScaleProviderAvailability.available();
  }

  @Override public ScaleExecutionPlan plan(ScaleRequest r) {
    if (r == null || !r.isUpscaling())
      return new ScaleExecutionPlan(ExecutionForm.BUILTIN, null, "none");
    String vf = "myscaler=w=" + r.getTargetWidth() + ":h=" + r.getTargetHeight();
    return new ScaleExecutionPlan(ExecutionForm.FFMPEG_FILTER, vf, "My Scaler");
  }
}
```

Registration is a bridge from the control-plane plugin API
(`sage.SageTVPlugin` has no data-plane hook) to the SPI:

```java
public final class MyPlugin implements sage.SageTVPlugin {
  private ScaleProviderRegistration reg;

  @Override public void start() {
    reg = ScaleProviderRegistry.getInstance().register(new MyScaleProvider());
  }
  @Override public void stop() {
    if (reg != null) { reg.close(); reg = null; }
  }
}
```

Registration rules:

- A duplicate id is **rejected** (`IllegalStateException`), never a silent
  replace. The reserved ids `builtin-passthrough` and `cuda-lanczos` cannot be
  registered.
- `close()` removes only your instance (identity-keyed).
- A registered upscale-capable provider **joins the preference chain
  automatically** — no property is required. Specialized (AI) providers are tried
  ahead of the deterministic CUDA-Lanczos fallback. Registering is enough; the
  optional `playback/gpu_enhance/scale_provider` head only reorders, it does not
  gate.

---

## 7. Invariants a provider must honor

1. **Recordings are never affected.** A provider never sees or touches recording
   state.
2. **Own only the scale stage.** Never emit deinterlace, bitrate, `-c:v`,
   muxing, seek, or timestamp flags; don't smuggle extra filters into
   `ffmpegFilter`.
3. **Degrade, never fail hard.** Missing runtime, no headroom, unsupported format
   → `unavailable(...)` (or a null-filter plan). The user gets the built-in
   passthrough (source-resolution, deinterlaced) — not a broken stream.
4. **Cheap probe.** No allocation, I/O, or runtime load in `probe()`.
5. **Byte-identical when disabled.** With no provider selected, output must equal
   the built-in passthrough path exactly (no scale fragment). Don't add global
   side effects at class-load.

---

## 8. Acceptance checklist

- [ ] `probe()` is microsecond-cheap and side-effect free.
- [ ] `plan()` returns a scale-stage-only fragment (or an agreed non-filter form).
- [ ] Non-upscaling / `DEINTERLACE_ONLY` → null-filter plan.
- [ ] `capabilities().specialized` set correctly; unique, stable `id()`.
- [ ] Throwing from `probe()`/`plan()` falls back to built-in with no disruption.
- [ ] Registered in `start()`, `reg.close()` in `stop()`; install/uninstall
      leaves the built-in path byte-identical.
- [ ] With the property unset, generated argv equals the built-in passthrough
      (no scale fragment; deinterlace preserved) for 720p, 1080i, and 1080p sources.
- [ ] Concurrency respects `playback/gpu_enhance/scale/max_specialized_sessions`;
      the over-budget session cleanly falls back to built-in.

---

## 9. Properties

| Property | Default | Meaning |
|---|---|---|
| `playback/gpu_enhance/scale_provider` | *(empty)* | Optional soft-preference head: names a registered provider to try first. Empty = pure automatic chain. Never pins or restricts. |
| `playback/gpu_enhance/scale_cuda_lanczos_provider` | `auto` | Master switch for the always-registered CUDA-Lanczos live upscaler. `auto`/truthy = enabled; a falsey value forces VSR-or-nothing. |
| `playback/gpu_enhance/scale/max_specialized_sessions` | `1` | Concurrent specialized-provider ceiling. |
| `playback/gpu_enhance/scale/external_worker_ready_timeout_ms` | `12000` | Worker/model READY phase for a cold `EXTERNAL_PROCESS`; clamped to 1,000–12,000 ms. |
| `playback/gpu_enhance/scale/external_worker_startup_timeout_seconds` | `12` | Legacy fallback used only when `external_worker_ready_timeout_ms` is unset; still capped at 12 seconds. |
| `playback/gpu_enhance/scale/external_worker_stall_timeout_seconds` | `5` | Idle-frame watchdog: if no frames flow for this long, the core kills the worker and tears the session down. |
| `playback/gpu_enhance/scale/warmup_enabled` | `true` | Master switch for the §5.1 warmup path. Providers with the default no-op hook remain unaffected. |
| `playback/gpu_enhance/scale/warmup_acquire_timeout_ms` | `8000` | External warm-resource acquisition phase budget. |
| `playback/gpu_enhance/scale/warmup_ready_timeout_ms` | `12000` | Worker/model readiness phase budget; acquire + readiness is hard-capped at 20 seconds. |
| `playback/gpu_enhance/scale/warmup_max_ttl_seconds` | `60` | Upper bound the core clamps a provider's `WarmContext.ttlMillis()` to, so a misbehaving provider cannot pin a warm worker (and its VRAM) indefinitely. |

---

## 10. Offline / batch upscale provider

SageTV-NG upscales in **two** places, and they are different mechanisms:

- The **live** seam above (`sage.enhance.spi`) selects an in-graph scale stage
  for a live transcode.
- The **offline / batch** seam (`sage.enhance.spi.offline`) selects the external
  worker for the chained-job path in `Ministry`: a source is upscaled to a
  lossless **intermediate file** by a separate process, then a second transcode
  pass encodes that intermediate (with its `-vf scale` token stripped). The
  built-in worker is Real-ESRGAN (ncnn-vulkan).

This seam exists so a plugin can replace the offline worker generically — the
same "no backend code in this repo" rule applies.

### 10.1 The interface

```java
package sage.enhance.spi.offline;

public interface OfflineUpscaleProvider {
  String id();                                        // stable, unique
  boolean isSpecialized();                            // informational
  java.util.List<String> buildProbeCommand();         // argv, or null/empty = no probe
  java.util.List<String> buildUpscaleCommand(OfflineUpscaleRequest request); // argv for one job
}
```

`OfflineUpscaleRequest` (immutable) gives you `getInput()` (source `File`),
`getOutput()` (intermediate `File` you must write), `getTargetWidth()`,
`getTargetHeight()`, and `getSourceHeight()` (advisory; `<=0` when unknown).

### 10.2 The server ↔ worker command contract (this is the syntax you must honor)

A provider returns a **complete argv `List<String>`** — the server runs it
verbatim; the provider owns every token, including the interpreter (`/bin/bash`,
a binary path, etc.). There is no implicit shell, so **no shell quoting/escaping**
applies: each list element is one process argument, passed straight to
`Runtime.exec` / `ProcessBuilder`.

**Upscale command** (`buildUpscaleCommand`): the process must

- read `request.getInput()`, write `request.getOutput()` upscaled to
  `getTargetWidth() × getTargetHeight()`,
- **exit 0 on success, non-zero on failure** (the core fails the job on
  non-zero),
- write only to its declared output (the core manages the intermediate dir and
  the phase-2 re-encode).

**Probe command** (`buildProbeCommand`): a cheap device check the core runs with
output draining and a timeout (`transcoder/ai_upscale_probe_timeout_secs`,
default 60). **Exit 0 = available.** Return `null` or an empty list to declare
"no device probe needed" (treated as available). A successful probe is cached for
the JVM's life; a failure is re-probed after
`transcoder/ai_upscale_probe_retry_backoff_secs` (default 30s).

For reference, the built-in Real-ESRGAN provider emits exactly (byte-identical to
the pre-seam invocation):

```
# upscale
/bin/bash <wrapper> --input <in> --output <out> --width <W> --height <H> \
  --model <model> --chunk-frames <N> --realesrgan <binary>

# probe (only when transcoder/ai_upscale_require_vulkan=true)
/bin/bash <wrapper> --probe --realesrgan <binary> --model <model>
```

with `<wrapper>`, `<binary>`, `<model>`, `<N>` from the `transcoder/ai_upscale_*`
properties below. A plugin provider is **not** required to use this flag shape —
it returns its own argv.

### 10.3 Registration and selection

Identical lifecycle to the live seam, via `OfflineUpscaleRegistry`:

```java
OfflineUpscaleRegistration reg;
public void start() { reg = OfflineUpscaleRegistry.getInstance().register(new MyOfflineProvider()); }
public void stop()  { if (reg != null) { reg.close(); reg = null; } }
```

- Two-step opt-in: register **and** set `transcoder/ai_upscale_provider = <id>`.
- Duplicate id is rejected; the built-in id cannot be shadowed.
- **Any failure is safe:** an unknown configured id, a `null`/empty upscale
  command, or an exception from either method → the core uses the built-in
  Real-ESRGAN command. A misbehaving plugin degrades to the built-in, never fails
  the job outright.
- There is **no per-session lease** here (unlike the live seam): offline batch
  concurrency is already bounded elsewhere — it drops to 1 while a tuner records.
- Genre routing, intermediate management, the phase-2 scale-filter strip, and
  recording protection remain in `Ministry`; a provider only supplies the two
  commands.

### 10.4 Offline properties

| Property | Default | Meaning |
|---|---|---|
| `transcoder/ai_upscale_provider` | built-in id (`realesrgan-ncnn`) | Which offline provider to use. |
| `transcoder/ai_upscale_enabled` | `true` | Master switch for the offline pass. |
| `transcoder/ai_upscale_wrapper` | `bin/sage-ai-upscale.sh` | Built-in wrapper script. |
| `transcoder/ai_upscale_binary` | `/usr/local/bin/realesrgan-ncnn-vulkan` | Built-in upscaler binary. |
| `transcoder/ai_upscale_model` | `realesr-general-x4v3` | Built-in model. |
| `transcoder/ai_upscale_chunk_frames` | `500` | Built-in chunk size. |
| `transcoder/ai_upscale_require_vulkan` | `true` | When `false`, built-in needs no probe. |
| `transcoder/ai_upscale_probe_timeout_secs` | `60` | Probe subprocess timeout. |
| `transcoder/ai_upscale_probe_retry_backoff_secs` | `30` | Re-probe backoff after a failed probe. |

*The `transcoder/ai_upscale_*` wrapper/binary/model/chunk properties are consumed
only by the built-in provider; a plugin provider owns its own configuration.*

---

*Source:* live seam `java/sage/enhance/spi/*` (integration in
`GpuEnhancePipeline.java` / `FFMPEGTranscoder.java`); offline seam
`java/sage/enhance/spi/offline/*` (integration in `Ministry.java`
`spawnAiUpscaleProcess` / device probe). Tests in
`test/java/sage/enhance/spi/` and `test/java/sage/enhance/spi/offline/` —
`ScaleSeamRenderTest` proves the live seam renders byte-identically to the
pre-seam path; `OfflineUpscaleRegistryTest` proves the offline built-in command
is byte-identical and the fallback/duplicate-rejection semantics hold.
