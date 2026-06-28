# SagaTV-NG PRD: Future-Aware Favorites + Enhanced Input UX (Final)

## 1\. Objective

Enable users to discover and record *future (non-EPG)* content using OTA-first architecture while improving input usability for search-heavy workflows.

This version incorporates:

* Future Airings support across Favorites search types
* Numeric keypad (T9) constraints
* Introduction of a popup-style keyboard (NG-compatible)

\---

## 2\. Scope Expansion (Favorites Manager Coverage)

Future Airings must be supported consistently across:

```
New by Title
New by Actor/Team
New by Category
New by Keyword
```

### Rule

When:

```
\\\\\\\\\\\\\\\[ ] Include Future Airings = ON
```

All entry types must:

* Query metadata layer (TVMaze phase first)
* Surface Future results
* Allow DeferredFavorite creation

\---

## 3\. Capability 1 — Search Integration (Unified Behavior)

### Entry Screens

* Add New Title Favorite
* Add New Actor/Team Favorite

### UI Addition (inline — constrained layout)

```
\\\\\\\\\\\\\\\[ ] Include Future Airings
```

Placement:

* Below text input field
* Above numeric keypad

\---

### Result Screen Model

Results MUST be separate screen with tabs:

```
\\\\\\\\\\\\\\\[ Airings ]   \\\\\\\\\\\\\\\[ Future ]
```

\---

### Future Tab Example

```
Rockford Files (NBC reboot)
Jan 2027
\\\\\\\\\\\\\\\[ Add Future Favorite ]
```

\---

### Actor Search Example

Search: "David Boreanaz"

Future tab:

```
Rockford Files (2027 reboot)
"Upcoming series starring David Boreanaz"
```

\---

## 4\. Capability 2 — Upcoming Shows Menu

### Placement

```
Online Menu
   Weather
   Upcoming Shows
```

\---

### Layout

```
Upcoming Shows

Rockford Files
NBC – Jan 2027
Reboot of classic PI series

\\\\\\\\\\\\\\\[ Add ]
```

\---

### Behavior

* OK → Details
* PLAY → Add DeferredFavorite

\---

## 5\. Capability 3 — Favorites Manager Integration

### New Unified Behavior

All "New by X" flows should:

* Use shared search component
* Honor "Include Future Airings" toggle
* Support metadata-driven results

\---

### Results Model

```
--- Airings ---
Existing OTA matches

--- Future ---
Metadata-only matches
```

\---

## 6\. Input System Modernization (NEW SECTION)

### 6.1 Problem

Current input:

* T9 numeric keypad
* slow for discovery
* limits search usability

\---

### 6.2 Design Decision

Introduce **dual input modes**:

```
InputMode:
  - Classic T9 (default, legacy safe)
  - Popup Keyboard (NG-enhanced)
```

\---

### 6.3 Popup Keyboard Design (Sage-compatible)

Full-screen replacement screen (NOT overlay):

```
------------------------------------
| Enter Search                     |
------------------------------------

\\\\\\\\\\\\\\\[A B C D E F G]
\\\\\\\\\\\\\\\[H I J K L M N]
\\\\\\\\\\\\\\\[O P Q R S T U]
\\\\\\\\\\\\\\\[V W X Y Z  \\\\\\\\\\\\\\\_ ]

\\\\\\\\\\\\\\\[Space] \\\\\\\\\\\\\\\[Backspace] \\\\\\\\\\\\\\\[Done]
```

\---

### 6.4 Requirements

Popup keyboard MUST:

* Use grid navigation (remote friendly)
* Avoid animations and overlays
* Be full-screen STV view
* Maintain focus state cleanly

\---

### 6.5 Interaction Flow

```
Open Search
  → Launch Keyboard Screen
      → Enter text
      → Select "Done"
  → Return to Search Results
```

\---

### 6.6 Compatibility

|Client Type|Support|
|-|-|
|SageTV 9.2.16 UI|✅ Fully supported|
|Extenders (HD200/300)|✅ Supported (no overlays)|
|Android MiniClient|✅ Supported|
|PWA Client|✅ Supported|

\---

### 6.7 Rollout Strategy

Phase A:

* Keep T9 everywhere
* Add popup keyboard ONLY to:

  * Title search
  * Actor search

Phase B:

* Expand to keyword search

Phase C:

* Add user preference switch

\---

## 7\. Architecture Summary

```
User Input → DeferredFavorite
           → Metadata Resolver
           → Airing Match (Wizard hook)
           → FavoriteAPI
```

\---

## 8\. Safeguards

Must prevent incorrect matches:

* enforce user disambiguation
* store external IDs
* avoid title-only matching

\---

## 9\. Phases

### Phase 1

* DeferredFavorite backend
* toggle in Favorites UI

### Phase 2

* TVMaze integration
* Future tab

### Phase 3

* Upcoming Shows menu

### Phase 4

* Popup keyboard (title + actor search)

### Phase 5

* Multi-source metadata

\---

## 10\. Success Criteria

* User can search future content efficiently
* Input friction reduced vs T9
* No rerun/reboot mismatches
* Works on legacy clients without UI breakage

\---

## 11\. Key Principles

* Identity over text matching
* OTA-first compatibility
* No UI overlays (full-screen model)
* Backward-compatible input model

