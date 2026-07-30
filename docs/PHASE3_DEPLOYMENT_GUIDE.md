## Phase 3 Main Menu Optimization: Deployment & Validation Plan

### What Changed (Plugin-Safe Design)

**Problem:** Main Menu ref 75 had 13 potential Refresh() calls per interaction sequence, with 9 separate NeedRefresh=true writes. Multiple state changes in a single focus cycle would trigger multiple redundant Refresh() calls.

**Solution:** Per-frame refresh deduplication guard.
- Added SetLocal variable tracking per focus cycle
- Wrapped ref 75's NeedRefresh conditional with a guard
- First Refresh() in cycle executes normally and sets guard flag
- Subsequent Refresh() requests skip execution but still clear NeedRefresh (preserves state)

**Why It's Plugin-Safe:**
- ✓ Action ID 75 unchanged (external references still work)
- ✓ NeedRefresh variable behavior preserved (plugins reading it see same state)
- ✓ Refresh() still called every cycle (just deduplicated within cycle)
- ✓ No structural changes to Main Menu (plugin patches still apply)
- ✓ Event ordering unchanged (timing preserved)
- ✓ SetLocal variables are internal only (not exposed to plugins)

### Optimization Details

**New Conditional Added to ref 75:**
```
Conditional Name="GetLocal('_MainMenuRefreshCycle') != true"
  Branch Name="true"   // First refresh in this cycle
    [Original: NeedRefresh = false, Refresh()]
    [New: SetLocal('_LastRefreshWasThisCycle', true)]
  Branch Name="else"   // Subsequent refresh request
    NeedRefresh = false  // Clear flag but skip Refresh()
```

**Guard Initialization (in BeforeMenuLoad):**
```
SetLocal('_MainMenuRefreshCycle', GetSystemTime())
```

This resets the guard on each focus cycle, allowing one Refresh() per cycle while preventing redundant executions.

### Expected Performance Impact

**Before Phase 3:**
- Main Menu focus → multiple state changes → multiple NeedRefresh=true writes → 13 ref 75 calls
- Potential for 3-4 Refresh() calls per interaction

**After Phase 3:**
- Main Menu focus → multiple state changes → multiple NeedRefresh=true writes → 13 ref 75 calls
- Only 1 Refresh() per cycle (subsequent calls skip execution)
- Expected reduction: ~60-75% fewer Refresh() executions

### Deployment Procedure

**Step 1: Create Server Snapshot (Rollback Point)**
```bash
# SSH to server
ssh sagetv@<host>

# Inside container, snapshot current STV
docker exec <container> bash -c \
  'cp /opt/sagetv/server/STVs/SageTV7/SageTV7.xml \
      /opt/sagetv/server/STVs/SageTV7_backups/SageTV7.xml.pre-phase3-'$(date +%s)''
```

**Step 2: Deploy Phase 3 STV**
```bash
# From local workstation
cd %USERPROFILE%\SageTV-NG

# Copy to server (via scp to /tmp, then docker cp)
scp stvs/SageTV7/SageTV7_cached_ac25_phase3.xml sagetv@<host>:/tmp/

# SSH and deploy into container
ssh sagetv@<host> 'docker cp /tmp/SageTV7_cached_ac25_phase3.xml \
  <container>:/opt/sagetv/server/STVs/SageTV7/SageTV7.xml'

# Stop Sage and reload
ssh sagetv@<host> 'docker exec <container> /opt/sagetv/server/stopsage'
# Wait ~10s for clean shutdown
ssh sagetv@<host> 'docker exec <container> /opt/sagetv/server/startsage'
```

**Step 3: Validation Testing**

**Feel Test (Main Menu Navigation):**
- Launch SageTV7 UI on client
- Navigate Main Menu rapidly (up/down/left/right)
- Check if navigation feels responsive (compare to AC-2.5 baseline)
- Test MediaPlayer OSD focus (should feel unchanged)

**Plugin Verification:**
- If you have any installed plugins, verify they still appear in menus
- Test plugin-added menu items (if any)
- Verify Main Menu structure hasn't changed from plugin perspective

**Server Log Check:**
```bash
ssh sagetv@<host> 'docker exec <container> tail -100 /opt/sagetv/server/sagetv_0.txt'
```
Look for:
- Any ERROR or exception related to SetLocal or GetLocal
- Startup messages confirming STV loaded
- No "undefined variable" warnings

**Optional: Enable Diagnostic Logging**
(If you want to measure actual Refresh() reduction)
```bash
ssh sagetv@<host> 'docker exec <container> bash -c \
  "sed -i \"s/^/ui\\/debug_refresh_churn=true/\" /opt/sagetv/server/Sage.properties"'
```
Then restart and watch logs for "Main Menu ref 75 Refresh() called" lines.

### Rollback Procedure (If Issues Occur)

**Immediate Rollback:**
```bash
# SSH to server
ssh sagetv@<host>

# Stop Sage
docker exec <container> /opt/sagetv/server/stopsage

# Restore from backup
docker exec <container> bash -c \
  'cp /opt/sagetv/server/STVs/SageTV7_backups/SageTV7.xml.pre-phase3-* \
      /opt/sagetv/server/STVs/SageTV7/SageTV7.xml'

# Restart
docker exec <container> /opt/sagetv/server/startsage
```

### Regression Testing Checklist

- [ ] Main Menu navigation feels responsive
- [ ] No crashes or exceptions in server logs
- [ ] MediaPlayer OSD works normally
- [ ] Focus movement is smooth (no "stutter" from multiple Refresh calls)
- [ ] Any installed plugins appear correctly
- [ ] Plugin menu items (if any) function normally
- [ ] No SetLocal/GetLocal undefined warnings in logs

### Phase 3 Architecture Notes

**Per-Frame Guard Design:**
- Guard variable `_LastRefreshWasThisCycle` lives only in the execution scope
- Resets on each BeforeMenuLoad cycle (every focus/interaction)
- Allows exactly one Refresh() per cycle
- Safe for multi-threaded UI (SageTV serializes UI thread)

**Backward Compatibility:**
- If an external tool/plugin injects a custom Refresh() call, it will still work (guard only affects ref 75)
- NeedRefresh state is readable (unchanged behavior)
- Plugins can inject their own refresh guards if needed

### Next Steps (Optional Phase 3 Extensions)

Once deployed and validated:
1. Run stv_hotpath_analyzer on Phase 3 STV to measure new baseline
2. Compare Refresh() call counts before/after
3. Consider extending deduplication to MediaPlayer OSD (52 refresh calls currently)
4. Profile expression evaluation overhead (AC-2.3 gains may not be realized until this is optimized)

### Testing Timeline

- Deploy and feel-test: 5-10 minutes
- Monitor logs: 30 minutes (watch for anomalies)
- Full regression check: 15-20 minutes
- Total: ~1 hour for safe validation

---

**File Versions:**
- Phase 3 optimizer: `docs/STV_Cleanup/stv_phase3_main_menu_optimizer.py`
- Phase 3 STV: `stvs/SageTV7/SageTV7_cached_ac25_phase3.xml` (MD5: 57526de33366be68d4657186269962c1)
- Deployed to: `/opt/sagetv/server/STVs/SageTV7/SageTV7.xml` on the `<container>` container

**Commit:** 7259138c
