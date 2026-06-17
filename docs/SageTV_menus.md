# SageTV Menus & V7.xml Structure (Developer Reference)

## Overview

SageTV V7 UI is **not defined as a traditional XML layout**. Instead, it is a **serialized graph of Widgets** (from the Studio/STV system).

- `V7.xml` = serialized representation of the UI graph  
- Runtime UI = **Widget graph + dynamic evaluation (Catbert)**  
- Studio = **graph editor + runtime interpreter**

---

## Core Concepts

### 1. Widget (Fundamental Unit)

Every UI element is a **Widget**.

Each Widget contains:

- `id` → unique identifier
- `type` → encoded as byte constant (Menu, Panel, Action, etc.)
- `properties` → key/value or expressions
- `contents` → child widget references
- `containers` → parent widget references

### Runtime Model

```
STV (XML or binary)
   ↓ load
Widget objects (graph)
   ↓ runtime
UI + logic execution
```

---

## 2. Graph-Based Structure (NOT Tree)

Widgets form a **directed graph**, not strictly a hierarchy.

- A widget can have multiple parents
- Relationships are explicit (ID-based)
- Navigation is graph traversal

---

## 3. Widget Types (Common)

- `Menu`
- `Panel`
- `Text`
- `Image`
- `Action`
- `Conditional`
- `Theme`
- `Hook`

---

## 4. Properties & Expressions (Catbert)

Widget properties are **not static values**.

Example:

```
<Property Name="Text">GetCurrentMediaTitle()</Property>
```

---

## 5. Process Chains (Execution Order)

```
Graph structure + Process chains = execution flow
```

- Ordering matters inside chains
- Ordering does NOT globally define execution

---

## 6. What a Menu Actually Is

A Menu is a root widget with children forming UI + logic chain.

---

## 7. V7.xml Structure (Conceptual)

XML represents references, not nesting.

---

## 8. Critical Integrity Rules

- Preserve unique IDs
- Maintain references
- Ensure property validity

---

## 9. Why Direct Editing Is Hard

- ID dependency chains
- No schema validation
- Runtime evaluation

---

## 10. Practical Modification Strategies

- Studio diffing
- Graph parser approach
- NG DSL conversion

---

## 11. Key Takeaways for SagaTV-NG

- Keep graph model
- Add validation and DSL layer

---

## 12. Recommended NG Architecture

- Graph engine
- Expression engine
- Parser + validator + exporter

---

## 13. Future Tooling Opportunity

Build an STV Analyzer to inspect and explain UI graphs.

---

## Summary

| Aspect | Reality |
|-------|--------|
| File format | Serialized widget graph |
| Structure | Directed graph |
| Logic | Catbert expressions |
| Execution | Process chains |
| Studio | Graph editor |

## Safe Editing Workflow

For practical large-file edits, use the guarded workflow in:

- [docs/SageTV_menus_safe_edit_workflow.md](docs/SageTV_menus_safe_edit_workflow.md)
