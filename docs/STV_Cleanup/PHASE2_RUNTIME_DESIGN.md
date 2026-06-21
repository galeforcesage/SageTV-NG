# Phase 2 Runtime Invalidation — Starter Design

## Goal

Implement the runtime cache-invalidation layer so that cached
`SetLocal`/`GetLocal` expressions stay correct across state changes.
This completes Phase 2 and satisfies PRD AC-2.4 (selection lag reduced)
and AC-2.5 (state-change correctness: changing a setting reflects
immediately on-screen).

## Acceptance Criteria (from PRD)

| AC | Criterion | Pass condition |
| --- | --- | --- |
| AC-2.4 | Selection lag reduced | Focus-move repaint time decreases >= 50% |
| AC-2.5 | State-change correctness | Changing a setting reflects immediately on-screen |

AC-2.4 depends on the build-time patches being applied (done).
AC-2.5 depends on the runtime layer refreshing cached values when the
underlying data changes.

## Open Questions

1. **What STV hooks exist for focus change / selection change?**
   Do screens already have `FocusGained`/`FocusLost` or equivalent
   listener widgets? What events fire when the user moves highlight?
2. **What STV hooks exist for setting changes?** Is there a
   `SettingChanged` Action pattern already wired into the XML, or do
   we need to create one per screen?
3. **Does Catbert have a `ClearLocal` primitive?** If not, can we
   re-`SetLocal` with a fresh evaluation, or must we force a full
   screen reload?
4. **How do Java property-change listeners propagate to the STV?**
   Does `Sage.putProperties` fire an event that the STV runtime can
   observe, or is notification purely polling-based?
5. **What existing event-listener patterns are in SageTV7.xml?**
   How do current Listener widgets subscribe to state changes? What
   naming conventions do they follow?

## Candidate Architectures

### Option A: Pure STV (XML-only)

Add `Listener` widgets that respond to focus/selection/setting events
and re-execute the relevant `SetLocal(...)` calls to refresh cached
values. No Java changes needed.

- Pro: contained within the STV; no jar rebuild.
- Con: possibly verbose (one listener per cached expression type per
  screen); may not cover all invalidation triggers cleanly.

### Option B: Java property-change callbacks

Register a Java-side observer that fires an STV event on property
change. STV hooks listen for that event and refresh their locals.

- Pro: single central hook; covers `GetProperty`/`GetServerProperty`.
- Con: requires a Sage.jar change; does not cover non-property
  expressions (`GetCurrentMediaFile`, `GetFavorites`, etc.).

### Option C: Hybrid (recommended starting point)

Use Option A for focus/selection (STV Listener widgets) and Option B
for property/setting changes (Java callback fires a synthetic event).
Non-property expressions (`GetCurrentMediaFile`, `GetFavorites`) are
handled by existing STV lifecycle hooks (MediaStarted, FavoriteAdded,
etc.) that re-SetLocal.

## Investigation Plan (next session)

1. **Grep SageTV7.xml** for `Listener`, `FocusGained`, `FocusLost`,
   `SettingChanged`, `MediaStarted`, `FavoriteAdded` — map existing
   event infrastructure.
2. **Read `java/sage/Catbert.java`** — confirm SetLocal/GetLocal/
   ClearLocal semantics and scope (per-screen? per-context?).
3. **Read `java/sage/SageProperties.java`** or equivalent — find
   property-change notification mechanism (if any).
4. **Grep for `putProperty`/`setProperty`** across Java — see how
   settings writes flow and whether an event bus exists.
5. **Prototype** a single screen (e.g. Video Browser) with one cached
   `GetProperty` and a manual `SetLocal` refresh on setting change.
   Verify AC-2.5 on that screen before scaling to all screens.

## Risk Assessment

| Risk | Impact | Mitigation |
| --- | --- | --- |
| No ClearLocal in Catbert — stale values persist | High | Re-SetLocal on every invalidation event; confirm semantics in Catbert.java |
| Per-screen listener proliferation → STV bloat | Medium | Group refresh calls into a single `RefreshCachedLocals()` Action per screen |
| Property change fires too often → perf regression | Medium | Debounce or gate refresh to visible screen only |
| Missing invalidation trigger for some expression types | Medium | Enumerate all EXPENSIVE_PATTERNS from stv_cache_patcher.py and map each to a trigger |

## Definition of Done (Phase 2 overall)

Phase 2 is complete when:

1. Build-time patch generation produces correct `SetLocal`/`GetLocal`
   patches for all eligible screens (done — `stv_cache_patcher.py`).
2. Runtime invalidation hooks refresh cached values on focus change,
   selection change, and setting change.
3. AC-2.4 passes: measured focus-move repaint time decreases >= 50%.
4. AC-2.5 passes: changing a setting reflects immediately on-screen
   without manual screen reload.
5. A verification pass confirms no stale-value regressions across a
   representative set of screens.
