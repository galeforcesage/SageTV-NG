# AI Local Context - SageTV-mine (PRIVATE - DO NOT COMMIT)

## Purpose
This file contains LOCAL environment, deployment rules, and operational constraints.
Use alongside AI_CONTEXT.md (which contains system architecture and features).

---

## Environment

### Local Workspace
- Repo: C:\Users\ted\SageTV-mine\
- Active branch: appmod/java-upgrade-20260328165139

### Remote Host
- SSH: sagetv@192.168.0.75
- Container: sagetv-mine

### Key Paths (remote, inside container)
- Sage.jar: /opt/sagetv/server/Sage.jar
- STV XML: /opt/sagetv/server/STVs/SageTV7/SageTV7.xml
- Logs: /opt/sagetv/server/sagetv_0.txt
- Deployed commit: /opt/sagetv/server/DEPLOYED_COMMIT

---

## Build Process

### Build Command
cd C:\Users\ted\SageTV-mine
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot
.\gradlew.bat --offline -x test sageJar

### Output
- build/release/Sage.jar

---

## Deploy Process (CRITICAL)

### NEVER DO
- NEVER docker stop/start/restart container
- NEVER copy partial files into container
- NEVER edit Sage.properties while running
- NEVER inline complex SSH or PowerShell commands
- NEVER use:
  pgrep -f "java.*sage.Sage"

---

### ALWAYS DO

#### Safe Deploy Flow
1. Snapshot working tree:
   tmp/snapshot_safety.ps1 -Message "<reason>"

2. Deploy via scripts:
   tmp/deploy_jar.ps1
   tmp/deploy_stv.ps1

3. Restart Sage inside container:
   stopsage
   startsage

---

## Snapshot System

### Script
- tmp/snapshot_safety.ps1

### Behavior
- Saves working tree to:
  refs/wip-safety/<timestamp>

### Recovery
git for-each-ref refs/wip-safety/
git show refs/wip-safety/<ts>:path/to/file
git checkout refs/wip-safety/<ts> -- file

---

## Versioning Rules

### BUILD_VERSION must match:
git rev-list HEAD --count

### File:
java/sage/SageConstants.java

### Workflow:
1. Get count:
   git rev-list HEAD --count

2. Update BUILD_VERSION

3. Amend commit:
   git add java/sage/SageConstants.java
   git commit --amend --no-edit

### Rule:
- DO NOT bypass version gate

---

## Active Development Status (2026‑06‑18)

- HEAD: c9f96c05
- Ahead of origin (not pushed)

### Working Feature
SMB Authentication UI:
- Username / Password / Domain inputs
- Caps-only soft keyboard
- Show password toggle
- Verified working

### In Progress
SMB Full Keyboard:
- Script: tmp/regen-smb-keyboard.ps1
- Goal:
  - Full QWERTY keyboard
  - Caps / lower / symbols modes
  - Field routing
  - Control keys

### Issue
- XML anchors do not match current STV
- Needs re-anchoring around:
  BASE-47324-AUTH-SHOW-INIT
  BASE-47324-AUTH-KEYBOARD

---

## Remote Access Cheatsheet

### Tail logs
ssh sagetv@192.168.0.75 "docker exec sagetv-mine tail -100 /opt/sagetv/server/sagetv_0.txt"

### Check deployed version
ssh sagetv@192.168.0.75 "docker exec sagetv-mine cat /opt/sagetv/server/DEPLOYED_COMMIT"

### Verify running JVM
ssh sagetv@192.168.0.75 "docker exec sagetv-mine ps -ef | grep '^sagetv.*java '"

### Restart Sage safely
ssh sagetv@192.168.0.75 "docker exec sagetv-mine /opt/sagetv/server/stopsage; docker exec sagetv-mine /opt/sagetv/server/startsage"

---

## Known Pitfalls

### CRLF issues
Fix with:
tr -d '\015'

### PowerShell encoding
- VSCode writes no BOM UTF-8
- Avoid non-ASCII chars in scripts

### tmp/ folder
- Gitignored by default
- Must use git add -f if needed

### Inline commands
- Break if too long
- Use script files instead

### Git pickaxe
- -S fails on binary files
- Always limit pathspecs

---

## Recovery After Crash

1. Check tmp/ for last script run
2. git status --short
3. git for-each-ref refs/wip-safety/
4. Compare:
   - Deployed commit vs HEAD
5. Verify JVM timestamp in container

### Rule
- DO NOT panic-revert
- Snapshot first, then investigate

---

## AI Usage Rules

- Always use scripts, not inline commands
- Always snapshot before destructive actions
- Always verify deploy state before assuming feature is live
- Never re-run deployment blindly
- Prefer modifying existing scripts over creating new ones
