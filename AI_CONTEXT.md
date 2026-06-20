# AI Context - SageTV-mine

## Architecture

- Java-based SageTV fork
- Server runs in containerized environment
- UI defined via STV XML (SageTV7.xml)
- Core runtime packaged as Sage.jar

---

## Key Systems

### UI / STV System
- Main UI defined in SageTV7.xml
- Uses widget-based STV architecture
- UI elements identified via BASE-* IDs

### Authentication UI
- SMB authentication interface implemented
- Features:
  - username / password / domain fields
  - soft keyboard (caps-only currently)
  - show password toggle

---

## Current Work Area

### SMB Keyboard System
- Working UI exists
- Goal: expand to full QWERTY keyboard
- Current blocker:
  - XML anchor mismatch during auto-generation

---

## Build System

- Built using Gradle
- Produces Sage.jar artifact
- Version controlled via BUILD_VERSION in source

---

## Deployment Model

- Deployment uses scripted process
- Requires service stop/start sequence (not container restart)
- Version must match repo commit count

---

## Rules for AI

- Do NOT reimplement authentication UI from scratch
- Modify existing BASE-47324 components only
- Always check STV XML before adding UI elements
- Prefer modifying existing scripts instead of inline commands
