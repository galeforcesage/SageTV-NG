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
  -DeployHost 192.168.0.75 \
  -Container sagetv-mine
```

## Notes

- Use narrow markers around the smallest possible block.
- Avoid regex mode unless required (`-UseRegex`).
- Default behavior requires exactly one match for FindText in the bounded block.
- Use `-AllowMultiple` only when intentionally replacing multiple occurrences.
