# PWA Server-Side Renderer Notes

Server-side performance notes for the PWA / browser MiniClient. This
document separates **safe instrumentation** from **risky behavior
changes** and warns where a naive server change can break native and
extender clients.

## 1. How the PWA client renders

The PWA / browser client renders SageTV GFX commands through **Canvas 2D
over a WebSocket bridge**. The server emits the same binary GFX command
stream (`GFXCMD_*` opcodes in `MiniClientSageRenderer`) that native
MiniClients consume; the PWA bridge decodes those commands and replays
them onto an HTML5 canvas. There is no native GPU surface layer — every
draw, image load, and buffer flip is JavaScript on the main thread.

## 2. Fix client/bridge FIRST

Menu-navigation lag on iPad Safari and Samsung Tizen is usually a
**client/bridge** problem, not a server problem. Attempt these before
touching the server:

- **ImageBitmap retention** — keep decoded `ImageBitmap`s alive so the
  canvas doesn't re-decode on every repaint.
- **Platform-specific cache limits** — Tizen and iOS Safari have much
  smaller memory ceilings than desktop Chrome.
- **rAF-gated FLIPBUFFER presentation** — coalesce `GFXCMD_FLIPBUFFER`
  to `requestAnimationFrame` so the canvas presents at most once per
  display refresh.
- **Tizen low-memory profile** — smaller texture cache, earlier
  eviction, fewer retained surfaces.
- **Bridge coalescing / backpressure** — batch byte writes and apply
  backpressure at the WebSocket layer, preserving command order.

## 3. Why server renderer changes are high-risk

The server's `MiniClientSageRenderer` GFX dispatch path is **shared by
every MiniClient-protocol client**:

- Windows native SageTV Client
- Mac native SageTV Client
- Android native MiniClient (ExoPlayer / IJKPlayer)
- HD200 / HD300 hardware extenders
- Placeshifter clients
- PWA / browser client

A change to command batching, buffer timing, image generation, scaling,
or GFX dispatch order affects **all** of them. The extender protocol in
particular has timing expectations baked into hardware firmware.

## 4. Rules for any server behavior change

Any server behavior change intended to help the PWA MUST be:

1. **PWA-only** — gated on `isPwaBrowserClient()` returning true.
2. **Capability-gated** — driven by an explicit client-reported
   capability, never inferred from resolution, IP, hostname, or port.
3. **Disabled by default** — opt-in via a `pwa/*` property until proven
   safe with before/after metrics.
4. **Measured** — instrument before and after; ship only on evidence.

## 5. Global properties are not a first fix

Do **not** reach for global server properties (animation delay, render
delay, frame delay, image generation, scaling, GFX dispatch behavior) to
reduce PWA lag. Those affect native and extender clients and are not
PWA-scoped. Global timing changes must never be the first tool.

## 6. Future PWA-only server ideas (not yet implemented)

These are candidate future hooks. Each must satisfy Section 4 before it
ships:

- **Lower UI render target for PWA sessions only** — report a smaller UI
  resolution to the PWA so the server generates fewer/smaller GFX
  commands. Requires per-client render-size negotiation. See SERVER4.
- **Smaller artwork / thumbnail generation for PWA sessions only** —
  generate smaller textures for PWA. Requires client-profile-keyed
  artwork cache to avoid polluting the global cache. See SERVER5.
- **PWA-only GFX frame metrics** — per-frame byte and timing counters.
  Implemented as instrumentation (see `PWA_PERF` logging, SERVER2).
- **PWA-only render-hint negotiation** — read advisory client hints
  (preferred UI size, texture ceilings, platform flags) without acting
  on them yet. See SERVER3.
- **PWA-only throttling / coalescing** — last-resort server-side batch
  coalescing. Prefer the bridge for this. See SERVER6.

## 7. Explicit warning

**Do NOT make global `MiniClientSageRenderer` changes to reduce PWA lag
unless those changes are isolated from all native and extender clients.**
The renderer is a shared code path. An "improvement" for the PWA that is
not gated on `isPwaBrowserClient()` is a regression risk for every native
and hardware client. When in doubt, fix the bridge.

## Appendix — current PWA identification

The server distinguishes the PWA client via the `CLIENT_PLATFORM`
capability property (`"browser"` or `"tizen"`), which the PWA bridge
already reports during capability negotiation. See
`MiniClientSageRenderer.isPwaBrowserClient()`. Unknown clients return
`false` — the check is conservative and never infers PWA from
resolution, IP, host, or port.

## Appendix — GFX perf logging

`PWA_PERF` logs are emitted only when BOTH:

- `pwa/perf_logging=true` (default false), AND
- the session is a PWA browser client (`isPwaBrowserClient()`).

They report per-frame byte counts and timing without changing any
command payload, send order, buffering, or render timing. Native,
extender, and Android sessions never emit `PWA_PERF` logs.
