# Phase 2 Notes - STV Expression Caching

## Summary

Phase 2 is a two-layer system.

The build-time layer scans the STV, finds repeated expensive Catbert
expressions, and generates `SetLocal`/`GetLocal` patch plans.
The runtime layer is separate: it must invalidate or refresh cached
values when screen state changes.

## What `stv_cache_patcher.py` covers

- `var_name(expr)`: turns an expression into a safe local variable name.
- `find_screens(root)`: collects inline `Menu` widgets that can be patched.
- `scan_screen(screen_elem)`: finds repeated expensive expressions inside
  a screen subtree.
- `find_before_menu_load(screen_elem)`: locates the `BeforeMenuLoad`
  insertion point for cache priming.
- `apply_patches(root, ns_prefix, dry_run)`: builds the patch plan and,
  when not in dry-run mode, writes the `SetLocal` and `GetLocal` changes.
- `print_report(patches)`: prints the planned cache substitutions.
- `run(input_path, output_path, dry_run)`: CLI wrapper for report/apply
  execution.

The script is intentionally a patch generator. It does not execute inside
SageTV runtime and it does not manage cache lifecycle after a value has
been written.

## What it does not cover

- Focus change invalidation.
- Selection change invalidation.
- Setting change invalidation.
- Any other state transition that should refresh a cached expression.
- The runtime hooks that clear or recompute locals after a state change.

## PRD Mapping

Working mapping for the Phase 2 acceptance criteria:

| AC | Layer | Responsibility |
| --- | --- | --- |
| AC-2.1 | Build-time | Find repeated expensive expressions. |
| AC-2.2 | Build-time | Produce stable local variable names. |
| AC-2.3 | Build-time | Emit `SetLocal` priming in `BeforeMenuLoad`. |
| AC-2.4 | Build-time | Replace repeated reads with `GetLocal`. |
| AC-2.5 | Runtime | Guarantee state-change correctness through invalidation. |

## Where runtime invalidation lives

Runtime invalidation belongs in the STV layer and the Java layer, not in
this generator.

- STV XML hooks: screen lifecycle, focus, selection, and settings-driven
  behaviors.
- Java callbacks: state listeners or helper methods that clear cached
  locals when the UI context changes.

## Done Definition

Phase 2 is done only when both layers exist:

1. The build-time generator can emit cache patches for eligible screens.
2. The runtime layer invalidates cached values on focus, selection, and
   setting changes.
3. A verification pass proves cached expressions stay correct after
   state changes.

If patch generation works but runtime invalidation is still missing,
Phase 2 is partial, not complete.
