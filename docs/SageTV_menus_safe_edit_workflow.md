# Safe Workflow For Editing SageTV7.xml

Use this helper for bounded, low-risk changes in the large STV XML graph:

- Script: scripts/safe-stv-edit.ps1
- Safety features:
  - marker-bounded edits only
  - marker uniqueness check (must be exactly one each)
  - replacement count guard (defaults to exactly one match)
  - automatic local backup before write
  - XML validation after write
  - auto-restore from backup on failure
  - optional deploy + restart

## 1) Validate XML only

```powershell
pwsh ./scripts/safe-stv-edit.ps1 -ValidateOnly
```

## 2) Safe bounded replacement (local only)

```powershell
pwsh ./scripts/safe-stv-edit.ps1 \
  -StartMarker 'Sym="CUSTOM-SMB-AUTH-CHECK-1"' \
  -EndMarker 'Sym="CUSTOM-SMB-AUTH-BRANCH-ELSE-1"' \
  -FindText 'ShortName = jcifs_smb_SmbFile_getName(FolderCell)' \
  -ReplaceText 'ShortName = GetFileNameFromPath(FolderCell)'
```

## 3) Safe bounded replacement + deploy to Docker host

```powershell
pwsh ./scripts/safe-stv-edit.ps1 \
  -StartMarker 'Sym="CUSTOM-SMB-AUTH-CHECK-1"' \
  -EndMarker 'Sym="CUSTOM-SMB-AUTH-BRANCH-ELSE-1"' \
  -FindText 'res = jcifs_smb_SmbFile_isDirectory(FolderCell)' \
  -ReplaceText 'res = true' \
  -Deploy \
  -DeployHost <HOST_IP> \
  -Container sagetv-mine
```

## Notes

- Use narrow markers around the smallest possible block.
- Avoid regex mode unless required (`-UseRegex`).
- Default behavior requires exactly one match for FindText in the bounded block.
- Use `-AllowMultiple` only when intentionally replacing multiple occurrences.

---

## Phase-Aware Editing Rules

The rules below apply progressively as each optimization phase is completed.
Check which phases have been applied to your STV before editing.

---

### After Phase 1 — Widget Deduplication

Common utility widgets (CloseOptionsMenu, DefaultFocus, ButtonText,
OptionsConfirmTheme, PassiveListen, etc.) are now **single shared definitions**
referenced by many `Ref=` elements throughout the file.

**Rules:**

- **Never edit a `Ref=` element directly** — it contains no editable content.
  Find the canonical definition first:
  ```powershell
  # If you see <Action Ref="70001"/>, find its definition:
  Select-String -Path SageTV7.xml -Pattern ' ID="70001"'
  ```
- Edit the canonical (ID'd) widget. All `Ref=` copies inherit the change
  automatically — no need to hunt down every occurrence.
- When adding a new widget that is similar to an existing shared one, check
  whether a canonical version already exists before creating a new inline copy:
  ```powershell
  Select-String -Path SageTV7.xml -Pattern 'Name="CloseOptionsMenu\(\)"' |
    Where-Object { $_ -match ' ID=' }
  ```

---

### After Phase 2 — Expression Caching

`BeforeMenuLoad` Actions now contain `SetLocal()` calls that cache expensive
Catbert expressions for the duration of each screen visit. Cached variables
follow the `_c_` naming prefix.

**Rules:**

- **Before adding a new expression to any screen subtree**, check whether a
  cached version already exists in that screen's `BeforeMenuLoad`:
  ```powershell
  # Example: checking if GetProperty("video_menu_style",...) is already cached
  Select-String -Path SageTV7.xml -Pattern '_c_GetProperty_video_menu_style'
  ```
  If found, use `GetLocal("_c_...")` in your new widget — not the raw call.

- **After adding a new screen** (new Menu widget), run the cache patcher to
  generate its `BeforeMenuLoad` caching block:
  ```powershell
  python stv_cache_patcher.py SageTV7.xml --apply SageTV7.xml
  ```

- **After installing or updating a plugin**, re-run the cache patcher so any
  new expression occurrences added by the plugin are picked up:
  ```powershell
  python stv_cache_patcher.py SageTV7.xml --apply SageTV7.xml
  ```
  The patcher is idempotent — running it multiple times produces identical output.

- **Cache invalidation:** if you modify what a cached property controls (e.g. a
  setting read by `GetProperty`), verify the relevant state-change Action also
  calls `SetLocal` to refresh that cache slot. Stale caches cause the UI to
  display outdated values until the next screen entry.

---

### After Phase 3 — Theme Chain Flattening

Theme inheritance chains have been pre-resolved. All theme property values are
written **directly on each theme widget** — no `<Theme Ref="N"/>` children exist.

**Rules:**

- **To change a theme property**: edit the leaf theme widget directly using
  `safe-stv-edit.ps1`, using the theme widget's Sym as the marker boundary:
  ```powershell
  pwsh ./scripts/safe-stv-edit.ps1 \
    -StartMarker 'Sym="OPUS4A-VideoItemTheme"' \
    -EndMarker 'Sym="OPUS4A-VideoItemTheme-END"' \
    -FindText '<TextColor>CCCCCC</TextColor>' \
    -ReplaceText '<TextColor>FFFFFF</TextColor>'
  ```

- **Do NOT add a `<Theme Ref="N"/>` child** to any theme widget. The flattened
  structure assumes no inheritance. Adding a Ref child creates a mixed state
  (some properties pre-resolved, some inherited) that produces unpredictable
  paint results.

- **To add a new theme**: define all properties inline with no `Ref=` child.
  Copy the values you need from an existing similar theme rather than
  referencing it.

- **To restructure theme inheritance** (e.g. change which theme a widget
  inherits from): modify the source `.stv` module file and re-run the flattener:
  ```powershell
  python stv_theme_flattener.py SageTV7.xml SageTV7_reflat.xml
  ```

---

### After Phase 4 — Modularization

`SageTV7.xml` is now a **generated artefact** produced by the composer pipeline.
Editing `SageTV7.xml` directly will be overwritten the next time
`stv_modularizer.py compose` runs.

**New editing workflow:**

**Step 1 — Identify which module contains the widget:**
```powershell
# Find the widget's Sym prefix → determines which module file to edit
Select-String -Path SageTV7.xml -Pattern 'YourWidgetName' | Select-Object -First 3
# If Sym="OPUS4A-12345" → edit modules/opus4a.stv
# If Sym="BASE-44343"   → edit modules/base.stv
# If Sym="NFLX1-99001"  → edit modules/nflx1.stv
```

**Step 2 — Edit the relevant module file:**
```powershell
pwsh ./scripts/safe-stv-edit.ps1 \
  -InputFile ./modules/opus4a.stv \
  -StartMarker 'Sym="OPUS4A-12345"' \
  -EndMarker   'Sym="OPUS4A-12346"' \
  -FindText    'OldExpression()' \
  -ReplaceText 'NewExpression()'
```

**Step 3 — Recompose the canonical STV:**
```powershell
python stv_modularizer.py compose ./modules/ SageTV7.xml `
  --plugins ./plugins/ --hooks hooks.json `
  --order BASE OPUS4 OPUS4A NFLX1 COMSKIP XHDFU
```

**Step 4 — Validate the composed output:**
```powershell
pwsh ./scripts/safe-stv-edit.ps1 -ValidateOnly -InputFile SageTV7.xml
```

**Plugin hook maintenance:**

When installing a new STVi plugin that targets specific widget IDs, add its
attachment targets to `hooks.json` **before composing**:

```json
{
  "MY_PLUGIN_MAIN_HOOK":   { "target": "12345", "module": "base.stv" },
  "MY_PLUGIN_OSD_HOOK":    { "target": "67890", "module": "opus4a.stv" }
}
```

After adding hook entries, recompose and verify each plugin hook attaches
correctly by checking that the injected widgets appear in the right location.
