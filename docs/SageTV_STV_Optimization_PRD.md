
SageTV STV Optimization
Product Requirements Document
Runtime Performance & Modularization — Phases 1 through 4

Field	Value
Target File	SageTV7.xml
File Size	13.7 MB  •  215,701 lines
Total Widgets	66,870
Mod Layers	18 Sym prefixes (BASE, OPUS4A, OPUS4, NFLX1, COMSKIP …)
Author	galeforcesage
Version	0.2 — Draft + Implementation Status
Date	June 26, 2026
Destination	VSCode + GitHub Copilot

Implementation Status — June 26, 2026

This document still describes the original four-phase roadmap, but the current
repository and production-safe outcome are narrower than the original Phase 3
plan.

Current approved state:

- Canonical repo STV: `stvs/SageTV7/SageTV7.xml`
- Current production-safe result: targeted AC-2.5 build with no OSD/Main Menu deduplication
- Rejected Phase 3 experiments: Main Menu refresh deduplication and OSD-only refresh deduplication
- Canonical analyzer: `docs/STV_Cleanup/stv_expression_analyzer_fast.py`
- Compatibility analyzer: `docs/STV_Cleanup/stv_expression_analyzer.py`

Validated findings from June 26, 2026:

- Main Menu refresh deduplication regressed intended submenu expansion behavior and was rolled back.
- MediaPlayer OSD refresh deduplication later regressed closed-caption menu interaction and was also rolled back.
- Expression-overhead analysis on the approved Phase 3 STV reported LOW high-cost concentration.
- Current analyzer result on the rollback-safe STV: 28,926 total function calls, 527 high-cost calls, 1.8% high-cost ratio.

 Table of Contents
1.  Executive Summary	3
2.  Problem Statement	3
2.1  Background	3
2.2  Measured Baseline	3
2.3  Top Duplicate Widget Patterns	4
2.4  Uncached Expensive Expressions	4
3.  Phase 1 — Widget Deduplication	5
3.1  Problem	5
3.2  Mechanism	5
3.3  Requirements	5
3.4  Acceptance Criteria	6
3.5  Tool: stv_deduplicator.py	6
4.  Phase 2 — Expression Caching	7
4.1  Problem	7
4.2  Mechanism	7
4.3  Cache Invalidation	7
4.4  Requirements	8
4.5  Acceptance Criteria	8
4.6  Tool: stv_cache_patcher.py	8
5.  Phase 3 — Theme Chain Flattening	9
5.1  Problem	9
5.2  Mechanism	9
5.3  Requirements	10
5.4  Acceptance Criteria	10
5.5  Tool: stv_theme_flattener.py	10
6.  Phase 4 — Screen Isolation & Modularization	11
6.1  Problem	11
6.2  Architecture Overview	11
6.3  Screen Guards	11
6.4  Module Split by Sym Prefix	12
6.5  Hook Registry (hooks.json)	12
6.6  Requirements	13
6.7  Acceptance Criteria	13
6.8  Tool: stv_modularizer.py	13
7.  Testing Strategy	14
7.1  Baseline Capture	14
7.2  Phase-by-Phase Verification	14
7.3  Regression Safety	15
8.  Appendix — Raw Analysis Data	16
8.1  Widget Type Distribution	16
8.2  Sym Prefix Distribution	16
8.3  Python Tool Summary	17
 1. Executive Summary
SageTV7.xml has grown to 13.7 MB across 66,870 widgets and 18 embedded modification layers. The file now exhibits two user-visible symptoms: slow screen painting and a noticeable delay when selecting items. Analysis of the widget graph reveals four compounding root causes, each addressable in a discrete optimization phase.

The most impactful finding is that zero expression caching exists anywhere in the 215,701-line file. Every repaint forces Catbert to re-evaluate 2,265 GetProperty calls, 1,015 GetElement calls, and hundreds of other expensive API calls from scratch. Combined with 1,446 copy-pasted definitions of the same CloseOptionsMenu() widget, 87% theme inheritance, and no screen-isolation guards on any of the 619 Menu screens, the cumulative evaluation cost grows with every mod added.

These four phases address the problem in order of ROI:

Phase	Name	Primary Fix	Effort	Risk	Expected Gain
1	Widget Deduplication	Collapse copy-pasted widget defs to shared Refs	2–3 days	Low	10–20% file size
2	Expression Caching	Add SetLocal/GetLocal in BeforeMenuLoad	1–2 weeks	Medium	60–80% less lag
3	Theme Flattening	Pre-resolve inheritance chains	2–3 weeks	Medium	Paint O(1) vs O(depth)
4	Modularization	IsCurrentMenu guards + module split + composer	1–3 months	High	Screen-level isolation

Executive Summary Addendum — Actual Phase 3 Outcome

The original roadmap expected a broader structural Phase 3. The implemented and
validated repository outcome is narrower:

- MediaPlayer OSD refresh deduplication was attempted but later rolled back after closed-caption menu regression.
- Main Menu refresh deduplication was attempted, caused navigation regression, and was rolled back.
- The canonical `SageTV7.xml` now represents the rollback-safe targeted AC-2.5 result.
- Theme flattening remains a design-phase item in this PRD, not a completed repository state.
2. Problem Statement
2.1  Background
SageTV uses a directed widget graph (the STV) to define its entire UI. The file is loaded at startup and Catbert — the runtime expression engine — traverses widget subtrees on every repaint and input event. The STV has been extended by stacking 18 distinct modification layers (identified by Sym prefix) directly into the monolith without consolidation.
2.2  Measured Baseline
Metric	Measured Value	Expected / Target
File size	13.7 MB	< 8 MB after Phases 1–3
Widget count	66,870	< 55,000 after Phase 1
SetLocal/GetLocal calls	0	>= 500 after Phase 2
Theme widgets	3,328	Unchanged (flattened in place)
Theme inheritances	2,876 (87%)	0 after Phase 3
Max nesting depth	99 levels	< 50 after Phase 4
Menus with IsCurrentMenu guard	0 of 619	619 of 619 after Phase 4
2.3  Top Duplicate Widget Patterns
The following widget names appear as separate inline definitions rather than single shared widgets referenced by Ref=. This is the primary source of file bloat.

Widget Name	Copies Found	Redundant Definitions
CloseOptionsMenu()	1,446	1,445
DefaultFocus	831	830
ButtonText	554	553
OptionsConfirmTheme	535	534
WideLeftRowPanelTheme	389	388
PassiveListen()	210	209
BeforeMenuLoad	163	162
AfterMenuLoad	127	126
DialogTitle	127	126
MenuNeedsDefaultFocus	109	108
2.4  Uncached Expensive Expressions
Catbert Expression	Call Count	Impact
GetProperty()	2,265	Hits Sage.properties file on every repaint
GetElement()	1,015	Array traversal — expensive in large collections
GetServerProperty()	419	IPC to server process per call
GetCurrentMediaFile()	168	Database lookup per call
GetShowEpisode()	146	Database lookup per call
GetSubgroup()	140	Collection traversal
GetAiringStartTime()	88	Database lookup per call
GetFavorites()	8	Full collection scan per call
3. Phase 1 — Widget Deduplication
Phase 1: Widget Deduplication
Estimated effort: 2–3 days  •  Risk: Low  •  Expected gain: 10–20% file size reduction

3.1  Problem
When a mod is applied to the STV, new widget definitions are typically pasted inline next to the screens they enhance. When 18 mods are layered this way, common utility widgets (CloseOptionsMenu, DefaultFocus, BeforeMenuLoad, etc.) accumulate as separate full definitions at each patch site. The file pays the memory and load-time cost of 1,446 separate CloseOptionsMenu widgets when only one definition with 1,445 Ref= references would be equivalent.
3.2  Mechanism
The deduplicator computes a canonical hash of each inline widget’s subtree (stripping Sym and ID attributes which are instance-specific). Widgets sharing the same (tag, Name, content-hash) are identical. The first occurrence is promoted to a shared widget by assigning a new unique ID. All subsequent occurrences are replaced with lightweight Ref= elements.

# Before (1,446 separate definitions):
<Action Name="CloseOptionsMenu()" Sym="OPUS4A-123">
  <Conditional Name="...">...</Conditional>
</Action>
<Action Name="CloseOptionsMenu()" Sym="OPUS4A-456">   <- copy
  <Conditional Name="...">...</Conditional>
</Action>

# After (1 definition + 1,445 Refs):
<Action ID="70001" Name="CloseOptionsMenu()" Sym="OPUS4A-123">
  <Conditional Name="...">...</Conditional>
</Action>
<Action Ref="70001" Name="CloseOptionsMenu()"/>         <- ref
3.3  Requirements
•	REQ-1.1  The deduplicator MUST only merge widgets whose entire subtree content is identical (same tag, same Name, same property values, same children recursively).
•	REQ-1.2  The deduplicator MUST preserve the Sym attribute on the canonical (kept) definition and discard it from collapsed Refs.
•	REQ-1.3  The deduplicator MUST NOT merge widgets that already carry an ID (they are already shared and their ID is part of their identity).
•	REQ-1.4  The deduplicator MUST produce a valid XML document that parses identically to the original when all Refs are resolved.
•	REQ-1.5  A --dry-run mode MUST report the merge plan without writing any output.
•	REQ-1.6  The tool MUST emit a report listing each merge group with copy count and estimated definition savings.
3.4  Acceptance Criteria
ID	Criterion	Pass Condition
AC-1.1	Widget count reduced	Output file contains fewer widgets than input
AC-1.2	No new IDs conflict	All new IDs > max existing ID in input
AC-1.3	All Refs resolvable	Every Ref= value has a matching ID= in output
AC-1.4	Visual equivalence	SageTV loads output without errors; smoke test passes
AC-1.5	File size reduced	Output file is at least 5% smaller than input
3.5  Tool: stv_deduplicator.py
Located in: output/stv_deduplicator.py

# Dry run — report only
python stv_deduplicator.py SageTV7.xml SageTV7_deduped.xml --dry-run

# Apply (requires review of dry-run first)
python stv_deduplicator.py SageTV7.xml SageTV7_deduped.xml

# Only deduplicate patterns with 5+ copies
python stv_deduplicator.py SageTV7.xml SageTV7_deduped.xml --min-copies 5

GitHub Copilot prompts for extending this tool:
•	"Add a --preserve-sym flag to keep Sym attributes on Ref elements"
•	"Extend canonical_hash() to also normalise numeric attribute values"
•	"Add a --report-json flag that saves the merge plan as JSON"
4. Phase 2 — Expression Caching
Phase 2: Expression Caching
Estimated effort: 1–2 weeks  •  Risk: Medium  •  Expected gain: 60–80% selection lag reduction

4.1  Problem
Catbert evaluates every expression in a widget’s Name or property attribute on every repaint. There is currently zero use of SetLocal, GetLocal, SetGlobal, or GetGlobal anywhere in the 215,701-line file. This means GetProperty(…) fires 2,265 times per full repaint cycle, GetElement(…) fires 1,015 times, and so on. When a user moves focus, SageTV repaints the focused screen’s subtree. Each keypress triggers hundreds of file-system and IPC-bound expression evaluations.
4.2  Mechanism
SageTV provides two caching primitives in Catbert:
•	SetLocal("varName", expr)  —  stores the result of expr in a per-screen variable
•	GetLocal("varName")        —  retrieves the stored value without re-evaluating

The cache patcher identifies expressions that appear 2+ times within the same Menu screen’s subtree. For each such expression it:
1.	Adds a SetLocal("_c_...", expr) call to the screen’s BeforeMenuLoad Action (creating one if absent).
2.	Replaces each inline occurrence of expr within the screen subtree with GetLocal("_c_...").

# Before (evaluates on every repaint):
<Conditional Name='GetProperty("video_menu_style","XWindow")=="XWindow"'>
  ...
</Conditional>
<Text Name='GetProperty("video_menu_style","XWindow")'>  <- evaluated again

# After BeforeMenuLoad:
<Action Name='SetLocal("_c_GetProperty_video_menu_style", GetProperty("video_menu_style","XWindow"))'>

# After replacement (reads from local cache):
<Conditional Name='GetLocal("_c_GetProperty_video_menu_style")=="XWindow"'>
<Text Name='GetLocal("_c_GetProperty_video_menu_style")'>
4.3  Cache Invalidation
Cached values must be refreshed when the underlying state changes. The patcher’s --report mode identifies which expressions are cached per screen. You must add SetLocal refresh calls to the following event Actions for each cached expression type:
Cached Expression Type	Invalidation Trigger	Action to add SetLocal refresh
GetProperty("...") / GetServerProperty("...")	User changes a setting	SettingChanged Action
GetCurrentMediaFile()	Media starts/stops playing	MediaStarted / MediaStopped Actions
GetFavorites()	Favorite added or removed	FavoriteAdded / FavoriteDeleted Actions
GetElement(list, n)	List content changes	Relevant data-load Action
4.4  Requirements
•	REQ-2.1  The patcher MUST only cache expressions that appear 2+ times within the same screen subtree.
•	REQ-2.2  The patcher MUST add SetLocal calls to the screen’s existing BeforeMenuLoad Action; it MUST create a BeforeMenuLoad Action if none exists.
•	REQ-2.3  Variable names MUST be deterministic (derived from the expression text) so re-running the patcher is idempotent.
•	REQ-2.4  The patcher MUST NOT cache expressions that already reference GetLocal() (avoid double-wrapping).
•	REQ-2.5  A --report mode MUST produce a human-readable patch plan listing every screen, every expression to cache, and every replacement location.
4.5  Acceptance Criteria
ID	Criterion	Pass Condition
AC-2.1	SetLocal calls added	Output contains >= 500 SetLocal calls (up from 0)
AC-2.2	No double-wrapping	grep -c 'GetLocal.*GetLocal' output.xml == 0
AC-2.3	Idempotent	Running patcher twice produces identical output
AC-2.4	Selection lag reduced	Measured focus-move repaint time decreases >= 50%
AC-2.5	State-change correctness	Changing a setting reflects immediately on-screen
4.6  Tool: stv_cache_patcher.py
Located in: output/stv_cache_patcher.py

# Report mode first (required before applying)
python stv_cache_patcher.py SageTV7.xml --report

# Apply patches
python stv_cache_patcher.py SageTV7.xml --apply SageTV7_cached.xml

GitHub Copilot prompts for extending this tool:
•	"Add support for SetGlobal/GetGlobal for expressions shared across multiple screens"
•	"Add a --threshold flag to require N occurrences instead of 2"
•	"Generate a JSON map of var_name -> invalidation trigger for manual review"

Phase 2 Implementation Notes — June 26, 2026

- AC-2.3 expression caching was validated as beneficial when applied without broad refresh invalidation.
- Targeted AC-2.5 invalidation remained acceptable when constrained away from hot Main Menu focus paths.
- Intermediate artifacts such as `SageTV7_cached_ac25_targeted.xml` remain useful for analysis, but the canonical file is now `stvs/SageTV7/SageTV7.xml`.
- For current analysis work, prefer the streaming analyzers over older ad hoc expression scanners.

5. Phase 3 — Theme Chain Flattening
Phase 3: Theme Chain Flattening
Estimated effort: 2–3 weeks  •  Risk: Medium  •  Expected gain: O(depth)→O(1) per property lookup

Phase 3 Status Note — June 26, 2026

The section below describes the original Phase 3 roadmap item. It is not the same as the validated Phase 3 implementation now present in the repo.

What was attempted as Phase 3:

- OSD-only refresh deduplication inside `MediaPlayer OSD`
- Main Menu refresh deduplication

Current final state:

- Canonical artifact reverted to the known-good targeted AC-2.5 build in `stvs/SageTV7/SageTV7.xml`
- No Main Menu deduplication in the approved build
- No OSD-only deduplication in the approved build

What did not ship:

- Main Menu refresh deduplication
- Theme flattening

Reason:

- Main Menu deduplication regressed intended submenu expansion behavior.
- OSD-only deduplication regressed closed-caption interaction because the guard suppressed later refreshes in the same open OSD session.
- Expression analysis showed low high-cost-call concentration, so refresh-path work remains secondary to correctness unless a safer narrower refresh strategy is developed.

5.1  Problem
The STV has 3,328 theme widgets, of which 2,876 (87%) inherit from a parent theme via a <Theme Ref="N"/> child. When Catbert resolves a widget’s visual property at paint time it must walk the full ancestor chain to find the first definition. With maximum nesting at 99 levels and 30,176 lines of code at 40+ spaces of indent, theme property resolution is a significant contributor to paint lag.
5.2  Mechanism
The flattener performs a depth-first walk of the theme inheritance tree, memoising each resolved property set. For a leaf theme it:
3.	Resolves the full property set by merging ancestor properties (child overrides parent).
4.	Removes the <Theme Ref="N"/> child and all existing property children.
5.	Writes the fully-resolved property values as direct child elements.

After flattening every theme lookup is O(1): Catbert reads a property child directly with no Ref traversal.

# Before (chain: LeafTheme -> MidTheme -> BaseTheme):
<Theme ID="200" Name="VideoItemCategoryNormalTheme">
  <Theme Ref="150" Name="BaseVideoTheme"/>
  <Font>Arial</Font>       <- only prop defined here; rest from ancestor
</Theme>

# After flattening:
<Theme ID="200" Name="VideoItemCategoryNormalTheme">
  <Font>Arial</Font>       <- own property
  <FontSize>22</FontSize>  <- resolved from ancestor
  <TextColor>FFFFFF</TextColor>
  <BackgroundColor>1F1F1F</BackgroundColor>
  ...all properties inline, no Ref chain...
</Theme>
5.3  Requirements
•	REQ-3.1  The flattener MUST preserve all property values; child properties MUST override parent properties in the resolved set.
•	REQ-3.2  Root themes (no parent Ref) MUST be left unchanged.
•	REQ-3.3  Circular inheritance (rare but possible after heavy modding) MUST be detected and skipped with a warning.
•	REQ-3.4  The --depth-report flag MUST print the chain-depth distribution before flattening.
•	REQ-3.5  A --dry-run mode MUST report how many themes would be flattened without writing output.
5.4  Acceptance Criteria
ID	Criterion	Pass Condition
AC-3.1	Zero theme Refs remain	grep -c '<Theme Ref=' output.xml == 0
AC-3.2	Property count preserved	Total property child count matches pre-flatten
AC-3.3	Visual equivalence	All screens pixel-match baseline screenshots
AC-3.4	Paint time reduced	Repaint benchmark improves >= 20%
5.5  Tool: stv_theme_flattener.py
Located in: output/stv_theme_flattener.py

# Check depth distribution first
python stv_theme_flattener.py SageTV7.xml output.xml --depth-report --dry-run

# Apply flattening
python stv_theme_flattener.py SageTV7.xml SageTV7_flat.xml

Note on file size: flattening copies ancestor property values onto every leaf theme, which slightly increases the total character count. This is the intended trade-off: smaller runtime evaluation cost at the expense of a few KB of storage.

GitHub Copilot prompts for extending this tool:
•	"Add circular inheritance detection using a visited-set in resolve_chain()"
•	"Add a --property-filter flag to only resolve specified properties (e.g. Font, FontSize)"
•	"Add screenshot comparison tooling using PIL to verify visual equivalence"
6. Phase 4 — Screen Isolation & Modularization
Phase 4: Screen Isolation & Modularization
Estimated effort: 1–3 months  •  Risk: High  •  Expected gain: Catbert skips inactive screens

6.1  Problem
There are 619 Menu widgets in the STV with zero IsCurrentMenu() Conditional guards. Catbert must evaluate all reachable widget subtrees on every navigation event, including subtrees for screens that are not currently displayed. Additionally, 18 distinct modification layers are merged into one flat namespace with no authoring-time isolation. This makes both maintenance and debugging of any specific mod extremely difficult.
6.2  Architecture Overview
Component	Role
Screen guards	IsCurrentMenu() Conditionals wrap each Menu’s children — Catbert short-circuits on false
Module files (.stv)	One file per Sym prefix: base.stv, opus4a.stv, nflx1.stv, etc.
hooks.json	Named hook point registry: maps logical hook names to concrete widget IDs
STVi plugins (.stvi)	Plugin UI extensions with ImportSTV blocks targeting hook names
stv_modularizer.py compose	Build-time composer: merges modules + applies STVi hooks → canonical SageTV7.xml

Compose pipeline:
  base.stv  ]
 opus4a.stv ]---> stv_modularizer.py compose ---> SageTV7.xml (canonical)
  nflx1.stv ]              ^
  ...       ]              |
                    plugins/*.stvi + hooks.json
6.3  Screen Guards
Adding IsCurrentMenu() around each screen’s widget children is the immediate, low-risk sub-step. Catbert evaluates a Conditional’s expression and, when false, skips the entire subtree without traversing it. This is the single most effective way to reduce paint-time evaluation without a full modularization effort.

# Before (always evaluated):
<Menu ID="45" Name="Video Browser">
  <Panel Name="VideoBrowserContent">...</Panel>
  <Action Name="BeforeMenuLoad">...</Action>
</Menu>

# After (Catbert skips when on a different screen):
<Menu ID="45" Name="Video Browser">
  <Conditional Name='IsCurrentMenu("Video Browser")' Sym="GUARD-VIDEO_BROWSER">
    <Panel Name="VideoBrowserContent">...</Panel>
    <Action Name="BeforeMenuLoad">...</Action>
  </Conditional>
</Menu>
6.4  Module Split by Sym Prefix
Each of the 18 Sym prefixes represents a distinct modification layer. Splitting by prefix gives each layer its own source file, making diffs, reviews, and merge conflicts comprehensible.

Sym Prefix	Widget Count	Module File	Description
BASE	36,823	base.stv	Original SageTV 7 core
OPUS4A	28,411	opus4a.stv	Primary mod layer (Ted’s main mods)
OPUS4	5,336	opus4.stv	Opus4 base
NFLX1	2,457	nflx1.stv	Netflix integration
COMSKIP	548	comskip.stv	Commercial skip
XHDFU	419	xhdfu.stv	Custom module
NGDLQ	215	ngdlq.stv	Custom module
NIELM	191	nielm.stv	Custom module
JUSJOKEN	189	jusjoken.stv	Custom module
OPUS4B	154	opus4b.stv	Opus4 variant
Others	1,174	misc.stv	8 remaining prefixes
6.5  Hook Registry (hooks.json)
STVi plugins currently target hard-coded widget IDs. When you restructure a module the ID may change and the plugin breaks silently. hooks.json defines named attachment points so plugins reference stable names, not volatile IDs.

{
  "MAIN_MENU_ACTIONS": { "target": "12345", "module": "base.stv" },
  "VIDEO_PLAYER_TOOLBAR": { "target": "67890", "module": "opus4a.stv" },
  "SETTINGS_OPTIONS": { "target": "99001", "module": "base.stv" }
}
6.6  Requirements
•	REQ-4.1  split sub-command MUST add an IsCurrentMenu() Conditional guard to every inline Menu widget that does not already have one.
•	REQ-4.2  split MUST group top-level widgets by Sym prefix and write each group to a separate .stv module file.
•	REQ-4.3  split MUST write a manifest.json listing modules in descending widget count order for use by compose.
•	REQ-4.4  compose MUST merge modules in the order specified by --order or manifest.json.
•	REQ-4.5  compose MUST validate that all ID values in the merged graph are unique and report conflicts.
•	REQ-4.6  compose MUST apply STVi plugin hooks by resolving hook names via hooks.json to target widget IDs.
•	REQ-4.7  compose MUST accept --plugins and --hooks arguments so plugin integration is opt-in.
6.7  Acceptance Criteria
ID	Criterion	Pass Condition
AC-4.1	All screens guarded	grep -c 'IsCurrentMenu' output.xml >= 619
AC-4.2	Module files produced	ls modules/*.stv shows one file per Sym prefix
AC-4.3	Compose round-trip	compose(split(original)) == original (minus whitespace)
AC-4.4	No ID conflicts	compose reports 0 duplicate IDs
AC-4.5	Plugin hooks applied	Each .stvi ImportSTV block injects into correct target
AC-4.6	All 619 screens pass	Full regression test of all Menu screens passes
6.8  Tool: stv_modularizer.py
Located in: output/stv_modularizer.py

# Step 1: add guards and split
python stv_modularizer.py split SageTV7.xml ./modules/

# Step 1 dry-run (report only):
python stv_modularizer.py split SageTV7.xml ./modules/ --dry-run

# Step 2: edit modules, then recompose
python stv_modularizer.py compose ./modules/ SageTV7_composed.xml

# Step 2 with plugins and explicit load order:
python stv_modularizer.py compose ./modules/ SageTV7_composed.xml \
    --plugins ./plugins/ --hooks hooks.json \
    --order BASE OPUS4 OPUS4A NFLX1 COMSKIP XHDFU

GitHub Copilot prompts for extending this tool:
•	"Add a --compat-map flag that maps old hard-coded STVi target IDs to hook names"
•	"Add ID namespace prefixing per module to prevent ID collisions on compose"
•	"Generate a hooks.json template by scanning the current STV for common attachment points"
7. Testing Strategy
7.1  Baseline Capture
Before applying any phase, capture baseline metrics:
•	File size in bytes: wc -c SageTV7.xml
•	Widget count by type: python stv_analyzer.py SageTV7.xml --json baseline.json
•	Screen paint time: measure with SageTV debug logging (log.properties: sagex.miniclient=FINE)
•	Selection latency: time between key event and screen repaint completion
•	Screenshot set: capture all major screens for pixel comparison in Phase 3
7.2  Phase-by-Phase Verification
Phase	Verification Steps
Phase 1	1. Run stv_analyzer.py on output; confirm widget count < input 2. Verify all Ref= IDs resolve: python -c "import xml.etree.ElementTree as ET; ..."  3. Smoke test: load output STV in SageTV, navigate to 10 major screens
Phase 2	1. Confirm SetLocal count >= 500 (grep -c SetLocal output.xml) 2. Change a setting; verify change reflects immediately on next screen entry 3. Switch media; verify GetCurrentMediaFile cache refreshes correctly 4. Measure selection latency improvement vs baseline
Phase 3	1. Confirm zero Theme Ref children remain (grep -c '<Theme Ref' output.xml) 2. Run pixel-compare screenshots for all major screens vs Phase 1 baseline 3. Run repaint benchmark; confirm >= 20% improvement
Phase 4	1. Confirm IsCurrentMenu count >= 619 2. Run compose round-trip: split then compose, diff against Phase 3 output 3. Install/uninstall each STVi plugin; verify hooks apply and remove cleanly 4. Full regression: navigate all 619 screens and confirm expected layout
7.3  Regression Safety
Keep a copy of the input STV at each phase boundary. The recommended file naming convention is:

SageTV7_original.xml          <- untouched baseline
SageTV7_phase1_deduped.xml    <- after Phase 1
SageTV7_phase2_cached.xml     <- after Phase 2
SageTV7_phase3_flat.xml       <- after Phase 3
SageTV7_phase4_modular.xml    <- after Phase 4 (compose output)

Use git for all STV files. Each phase commit message should include the stv_analyzer.py widget count so the history self-documents the improvement.

Current analyzer guidance:

- Canonical analyzer: `docs/STV_Cleanup/stv_expression_analyzer_fast.py`
- Compatibility analyzer for large STV/STVi workflows: `docs/STV_Cleanup/stv_expression_analyzer.py`

The current `stv_expression_analyzer.py` has been rewritten to use a streaming linear-time parse so it is safe to use on larger plugin STVi files. Prefer the fast analyzer for routine repo analysis and use the compatibility analyzer when you need the richer JSON structure or sample locations.

8. Appendix — Raw Analysis Data
8.1  Widget Type Distribution
Widget Type	Count	Percent of Total
Action	40,202	60.1%
Conditional	9,871	14.7%
Text	4,970	7.4%
Panel	3,913	5.8%
Theme	3,328	5.0%
Listener	2,030	3.0%
Hook	707	1.1%
Image	625	0.9%
Menu	619	0.9%
Table	340	0.5%
Shape	255	0.4%
Video	13	< 0.1%
TOTAL	66,870	100%
8.2  Sym Prefix Distribution
Sym Prefix	Widget Count	Percent
BASE	36,823	49.1%
OPUS4A	28,411	37.9%
OPUS4	5,336	7.1%
NFLX1	2,457	3.3%
COMSKIP	548	0.7%
XHDFU	419	0.6%
NGDLQ	215	0.3%
NIELM	191	0.3%
JUSJOKEN	189	0.3%
OPUS4B	154	0.2%
ZCMRJ	105	0.1%
Others (7)	99	0.1%
TOTAL	74,947	100%
8.3  Python Tool Summary
File	Phase	Key Arguments
stv_analyzer.py	Pre-flight	<input.xml>  [--json report.json]
stv_deduplicator.py	1	<input.xml> <output.xml>  [--dry-run] [--min-copies N]
stv_cache_patcher.py	2	<input.xml>  --report | --apply <output.xml>
stv_theme_flattener.py	3	<input.xml> <output.xml>  [--dry-run] [--depth-report]
stv_modularizer.py	4	split <input.xml> <dir/>  [--dry-run] [--no-guards]
stv_modularizer.py	4	compose <dir/> <output.xml>  [--plugins <dir/>] [--order ...]
Appendix B — Implementation Notes
B.1  Phase Execution Order vs. Priority
The phase numbers in this document reflect recommended execution order, not priority ranking by user-visible impact. Phase 2 (Expression Caching) remains the highest-impact fix for perceived performance — the 60–80% selection lag reduction is the most immediately noticeable improvement to the end user.

Phase 1 (Widget Deduplication) is numbered first because:
•	It carries lower risk (2–3 days, no cache invalidation complexity).
•	A smaller, deduplicated graph makes the Phase 2 caching pass more accurate: fewer copy-pasted widget instances means fewer expression occurrences to analyse, and the resulting cache variable names map cleanly to one canonical widget per screen.
•	Deduplication does not depend on caching state; caching can depend on a clean graph.

If you need to ship a performance fix immediately, run Phase 2 (stv_cache_patcher.py) directly against the current SageTV7.xml — Phase 1 is not a prerequisite. Then run Phase 1 afterward to clean up the graph.
B.2  Scripts Are Data-Driven, Not Hardcoded to the Analysis Snapshot
Every Python tool scans whatever file you provide at runtime. None hardcode the specific widget IDs, widget counts, or Sym prefixes found during the June 2026 analysis. If your STV has changed since that snapshot, simply pass the updated file:

python stv_analyzer.py SageTV7_updated.xml --json current_baseline.json
python stv_deduplicator.py SageTV7_updated.xml SageTV7_deduped.xml
# All tools adapt to current file content automatically.

One exception: stv_cache_patcher.py contains an EXPENSIVE_PATTERNS list of standard Catbert API function names (GetProperty, GetElement, GetServerProperty, etc.). If your mods introduced custom SageTV API calls that also hit the database or IPC layer, add their names to that list near the top of the file. GitHub Copilot can generate these additions if you describe the function name and what it does.
B.3  Plugin Compatibility Verification
Run the following verification steps before deploying each phase output to a live SageTV instance.

Phase	Plugin Risk	Verification Steps
1 — Dedup	None. Inline widgets (no ID) cannot be externally referenced by any plugin. Newly promoted shared widgets receive IDs above the existing maximum — no existing Ref is affected.	1. Install each STVi plugin on the Phase 1 output.  2. Confirm ImportSTV hooks attach without errors.  3. Smoke-test plugin UI on representative screens.
2 — Caching	Medium. Run the patcher only after all plugins are installed. If a plugin is added later, re-run the patcher (idempotent). Confirm no double-wrapping: grep for GetLocal(GetLocal in the output.	1. Run stv_cache_patcher.py --report on the plugin-merged STV before applying.  2. Confirm no GetLocal(GetLocal(... double-wrapping.  3. Change a setting; verify it reflects on next screen entry.  4. Switch media; verify GetCurrentMediaFile cache refreshes.
3 — Theme flatten	None. Theme IDs are unchanged; only child-property structure changes. Plugin theme widgets that carry their own inheritance chains are not touched.	1. Pixel-compare all plugin-contributed screens vs Phase 2 baseline.  2. Confirm no visual regressions on themed plugin panels.
4 — Modular	High. The split step moves widgets between files. Any STVi plugin targeting a hard-coded widget ID that moved to a different module loses its attachment point.	1. Audit every .stvi file for ImportSTV target="ID" entries.  2. Add each target ID to hooks.json before composing.  3. Run compose with --plugins and --hooks flags.  4. Install and uninstall each plugin; verify hook attaches and removes cleanly.

Phase 2 ordering rule: always run stv_cache_patcher.py after all plugins are installed, not before. Plugins added after caching will evaluate their expressions uncached — functionally correct but without the performance benefit. Re-running the patcher after each plugin addition is idempotent and safe.
B.4  Documentation Updates Required After Each Phase
Phase	SageTV_menus.md	SageTV_menus_safe_edit_workflow.md
1 — Dedup	Add note: common utility widgets now appear as Ref= elements. Readers seeing <Action Ref="N"/> should grep for ID="N" to find the canonical definition.	Add rule: never edit a Ref element directly. Find the canonical widget by ID and edit that definition — all Ref copies inherit the change.
2 — Caching	Add Caching Layer section: list BeforeMenuLoad variables (_c_ prefix), the expression each maps to, and the state-change events that invalidate each.	Add rule: before adding a new expression to a screen, grep for _c_ in BeforeMenuLoad. Use GetLocal() if a cached version exists. Re-run the patcher after adding a new screen or plugin.
3 — Theme flatten	Update Theme section: remove Ref-chain guidance. Add: all theme properties are pre-resolved inline on each widget. Edit directly; re-run flattener after modifying source.	Update theme editing: edit the leaf theme widget directly. Do not add Theme Ref= children. To restructure inheritance, modify source module and re-run stv_theme_flattener.py.
4 — Modular	Add Module System section: Sym prefix to module file mapping, composer workflow, hooks.json format.	Replace monolith editing with: (1) identify module by Sym prefix, (2) edit the .stv file, (3) run compose, (4) validate. SageTV7.xml is now generated — do not edit it directly.

