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

## Investigation Results (2026-08-09)

### Q1: STV focus/selection hooks

- **FocusGained**: 75 Hook elements in SageTV7.xml
- **FocusLost**: 37 Hook elements
- **FocusChanged / SelectionChanged**: do NOT exist as named events
- **Pattern**: `<ns0:Hook Name="FocusGained">` fires child Actions that
  call `RefreshArea("...")` or re-evaluate state variables. This is the
  correct mechanism for cache refresh on navigation.

### Q2: Setting-change hooks

- **No `PropertyChanged` event exists.** The STV uses a manual pattern:
  ```
  SettingChanged = (OldValue != NewValue)
  → Apply(NewValue)
  → if (SettingChanged) { Refresh() }
  ```
- Only 3 instances of this pattern exist (AspectRatioMode,
  OutputResolution, and one more). Settings screens that modify
  properties generally call `Refresh()` inline.

### Q3: Does Catbert have ClearLocal?

- **No.** No `ClearLocal`, `removeLocal`, or unset primitive.
- **Workaround**: Re-`SetLocal(varName, freshEvaluation)` to refresh.
  The map accepts null values, but the semantics of re-setting with a
  fresh call result are well-supported.

### Q4: SetLocal/GetLocal scope and lifetime

- **Storage**: `ThreadSafeHashMap<String, Object>` per `Catbert.Context`
- **Scope**: lexical; child contexts walk up parent chain, then static
  context, then global context (`UIManager`).
- **Lifetime**: per-menu/screen. `context.clear()` wipes all locals
  when a screen's context is destroyed. Contexts are created via
  `createChild()` on menu entry.
- **Implication**: cached `_c_*` locals live for the duration of the
  screen/menu. They are naturally GC'd on screen exit. Staleness is
  only a problem *within* a screen session (focus moves, setting
  toggles while the screen is open).

### Q5: Property-change notification flow

- **No automatic event bus.** `Sage.put(name, value)` →
  `SageProperties.setProperty()` → marks dirty flag. That's it.
- Network distribution requires **explicit** calls to
  `NetworkClient.distributePropertyChange(propName)` by each caller.
- **Conclusion**: there is no Java-side hook we can piggyback on.
  Invalidation must be STV-driven (Option A or Option C with a
  lightweight Java hook we add ourselves).

### Q6: Listener widget patterns

- 1168 `<ns0:ListenerEvent>` elements in SageTV7.xml
- Event names: `MouseClick`, `MouseEnter`, `MouseExit`, `Right`,
  `Left`, `Up`, `Down`, `Select`, `Options`
- Structure: `<ns0:Listener> → <ns0:ListenerEvent>EventName</> →
  child Actions`
- Focus hooks are separate: `<ns0:Hook Name="FocusGained">` (not a
  Listener widget)

---

## Recommended Architecture: Pure STV (Option A, revised)

Given the investigation findings:

1. **No Java change needed for MVP.** There is no event bus to hook;
   adding one solely for cache invalidation is over-engineering.
2. **FocusGained hooks already exist** on 75 elements. We add
   re-`SetLocal` calls into existing FocusGained hooks (or add new ones
   where missing) to refresh the cached property values when focus
   moves.
3. **Setting changes are self-invalidating.** The few settings screens
   that toggle properties already call `Refresh()` which re-enters the
   menu and re-seeds all `SetLocal` calls. No additional hook needed.
4. **Screen exit = natural GC.** Locals are scoped to the context; on
   screen exit the context is destroyed. No explicit invalidation needed
   for cross-screen navigation.

### Implementation Plan

**Finding: The runtime layer is already complete for all practical purposes.**

The build-time patcher (`stv_cache_patcher.py`) already emitted both
`BeforeMenuLoad` seeds AND `FocusGained` refresh hooks where needed.
Investigation of all 14 active slots shows:

| Slot | Has FocusGained | Needs it? | Reason |
| --- | --- | --- | --- |
| `_c_GetFavorites` | ✅ Yes | Yes | List screen; selection changes |
| `_c_GetProperty_photo_lib_folder_style_xCombined` | ✅ Yes | Maybe | Picker-style |
| `_c_GetProperty_placeshifter_recent_servers` | ✅ Yes | Maybe | List-based |
| `_c_GetProperty_ui_GoogleVideo_AllowAccess_xOn` | ✅ Yes | Maybe | Toggle |
| `_c_GetProperty_WirelessInterface_ra0` | ✅ Yes | Maybe | Config |
| `_c_GetServerProperty_linux_network_netmask_255_2` | ✅ Yes | Maybe | Config |
| `_c_GetServerProperty_linux_network_ssid` | ✅ Yes | Maybe | Config |
| `_c_GetProperty_ask_delete_at_EOF_Xmanual_only` | ❌ No | **No** | OSD popup; no focus navigation changes the property |
| `_c_GetProperty_music_custom4_5_function_xTrack` | ❌ No | **No** | Music browser; property is static for the screen session |
| `_c_GetProperty_MyServer` | ❌ No | **No** | Embedded startup; single-use |
| `_c_GetServerProperty_linux_network_gateway` | ❌ No | **No** | Configuration Wizard; user edits then applies with Refresh() |
| `_c_GetServerProperty_linux_network_primary_dns` | ❌ No | **No** | Same — wizard screen |
| `_c_GetServerProperty_linux_network_secondary_dns` | ❌ No | **No** | Same — wizard screen |
| `_c_GetShowEpisode_Airing` | ❌ No | **No** | Detail popup; `Airing` is fixed for screen lifetime |

**Conclusion**: All 7 slots that need FocusGained hooks already have them
(added by the build-time patcher). The remaining 7 without FocusGained
are in single-item popups, OSD overlays, or config wizards where the
cached expression's input doesn't change during the screen session.

**AC-2.5 (settings correctness)** is satisfied because all settings
screens that modify properties call `Refresh()` to re-enter the menu,
which re-runs all `BeforeMenuLoad` seeds.

**The runtime layer requires no additional STV changes.**

### Next: AC-2.4 Measurement

The only remaining Phase 2 work is to **measure** the focus-move repaint
time reduction (AC-2.4 target: ≥ 50% reduction). This requires:
1. Capture baseline repaint timing on a screen with cached expressions
   (e.g. Favorites Manager)
2. Compare to timing without the cache patches (revert to unpatched STV)
3. Document the delta

This is a measurement/validation task, not a code change.

## Risk Assessment (updated)

| Risk | Impact | Mitigation |
| --- | --- | --- |
| ~~No ClearLocal~~ — confirmed, use re-SetLocal | Low | Re-SetLocal with fresh eval; confirmed working |
| Per-screen listener proliferation | Low | Only 2 slots actually need FocusGained hooks; rest are self-invalidating |
| Property change fires too often | N/A | Settings screens already call Refresh(); no new trigger needed |
| Missing invalidation trigger for some expression types | Low | All 14 active slots mapped to triggers above |

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
