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
`FFMPEGTranscoder`. `BuiltinScaleProvider` is the always-present default and the
exact behavior-preserving reference.

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
| `ScaleProviderRegistry` | singleton | `register()` / `select()`. |
| `ScaleProviderRegistration` | `AutoCloseable` | Handle; `close()` unregisters. |
| `ScaleGovernor`, `ScaleGovernor.Lease` | admission budget | Core-managed. |
| `ScaleSelection` | immutable | Internal selection result. |
| `BuiltinScaleProvider` | class | Default + guaranteed fallback. |

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
  the core falls back to the built-in scaler. A provider cannot break playback.

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

```java
new ScaleExecutionPlan(ExecutionForm form, String ffmpegFilter, String implementationLabel);
```

- `ffmpegFilter` — the **scale-stage `-vf` fragment only** (e.g.
  `myscaler=w=3840:h=2160`). Never include the deinterlacer; the core prepends
  it. `null` for non-filter forms and for non-upscaling plans.
- `implementationLabel` — honest, human-readable mechanism for telemetry/logs.

The core uses the plan only if `isRenderablePhase0()` is true: form is `BUILTIN`
or `FFMPEG_FILTER` **and** the filter is non-empty. Otherwise it falls back to
the built-in scaler (see §5).

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

1. The registry reads property `playback/gpu_enhance/scale_provider` (default =
   built-in id). If it isn't your id, you aren't consulted.
2. It calls your `probe(request)`. `null`/unavailable → fallback to built-in.
3. If you declared `specialized`, it acquires a `ScaleGovernor` permit. Budget
   exhausted → fallback to built-in.
4. It calls your `plan(request)`. `null`, empty, or a non-renderable form →
   permit released, fallback to built-in.
5. Success → the session **captures** your plan and the permit for its lifetime.

Guarantees:

- **Selection is captured once.** Registering/unregistering a provider or
  changing the property affects only *future* sessions. There is **no mid-stream
  hot-swap** of an active playback.
- **Fallback always targets the built-in directly**, never a second registry
  lookup, so a failing provider can't recurse.
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
| `BUILTIN` | The built-in CUDA scale fragment. | Yes (built-in only). |
| `FFMPEG_FILTER` | A provider-supplied `-vf` scale fragment the deployed ffmpeg can execute. | **Yes.** |
| `EXTERNAL_PROCESS` | A native worker process the provider owns. | Not yet rendered. |
| `SIDECAR` | A long-lived sidecar service. | Not yet rendered. |

- If a backend can be expressed as a `-vf` fragment the deployed ffmpeg
  understands (a built-in filter, or a custom libavfilter CUDA filter compiled
  into the server's ffmpeg), it can ship against the current seam with **no
  further core changes** and stay fully in-VRAM.
- An out-of-process backend requires the core to first learn to render
  `EXTERNAL_PROCESS` / `SIDECAR`: a frame-transport contract (e.g. CUDA IPC
  shared surfaces or a forwarding filter shim), extra immutable fields on
  `ScaleExecutionPlan` (transport handle, pixel format, colorspace), a bounded
  per-frame latency budget, and session-scoped teardown. That is a follow-up
  core change; returning those forms today simply falls back to the built-in.

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
  replace.
- `close()` removes only your instance (identity-keyed).
- Selection is a **two-step opt-in**: the plugin registers, *and* the property
  `playback/gpu_enhance/scale_provider` names your id. Registering alone changes
  nothing, so a provider can be installed dormant.

---

## 7. Invariants a provider must honor

1. **Recordings are never affected.** A provider never sees or touches recording
   state.
2. **Own only the scale stage.** Never emit deinterlace, bitrate, `-c:v`,
   muxing, seek, or timestamp flags; don't smuggle extra filters into
   `ffmpegFilter`.
3. **Degrade, never fail hard.** Missing runtime, no headroom, unsupported format
   → `unavailable(...)` (or a null-filter plan). The user gets built-in scaling,
   not a broken stream.
4. **Cheap probe.** No allocation, I/O, or runtime load in `probe()`.
5. **Byte-identical when disabled.** With no provider selected, output must equal
   the built-in path exactly. Don't add global side effects at class-load.

---

## 8. Acceptance checklist

- [ ] `probe()` is microsecond-cheap and side-effect free.
- [ ] `plan()` returns a scale-stage-only fragment (or an agreed non-filter form).
- [ ] Non-upscaling / `DEINTERLACE_ONLY` → null-filter plan.
- [ ] `capabilities().specialized` set correctly; unique, stable `id()`.
- [ ] Throwing from `probe()`/`plan()` falls back to built-in with no disruption.
- [ ] Registered in `start()`, `reg.close()` in `stop()`; install/uninstall
      leaves the built-in path byte-identical.
- [ ] With the property unset, generated argv equals stock for 720p→2160p,
      1080i→2160p, and 1080p→2160p plans.
- [ ] Concurrency respects `playback/gpu_enhance/scale/max_specialized_sessions`;
      the over-budget session cleanly falls back to built-in.

---

## 9. Properties

| Property | Default | Meaning |
|---|---|---|
| `playback/gpu_enhance/scale_provider` | built-in id | Which provider live requests prefer. |
| `playback/gpu_enhance/scale/max_specialized_sessions` | `1` | Concurrent specialized-provider ceiling. |

---

*Source:* `java/sage/enhance/spi/*`; integration in
`java/sage/enhance/GpuEnhancePipeline.java` and `java/sage/FFMPEGTranscoder.java`.
Tests in `test/java/sage/enhance/spi/` — `ScaleSeamRenderTest` proves the seam
renders byte-identically to the pre-seam path; `ScaleProviderRegistryTest` proves
the fallback, duplicate-rejection, and capture-immunity semantics.
