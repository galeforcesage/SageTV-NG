# AC-2.4/AC-2.5 Testing Plan & Instrumentation

## Overview
The Phase 2 AC-2.4/AC-2.5 optimization has been deployed successfully to the running server.
This document outlines testing strategies to validate:
- **AC-2.4**: Selection lag reduction (≥50% faster focus move)
- **AC-2.5**: State-change correctness (settings reflect immediately)

## Testing Approach

### Option A: Manual UI Testing (Pragmatic)
**Pros**: Fast, easy to spot UI issues, realistic user experience
**Cons**: Hard to measure precise latency, depends on observer perception

**Procedure**:
1. Open SageTV UI (web or app)
2. Navigate to **Main Menu**
3. Repeatedly press Down arrow key, timing each selection change
4. Count: ~10 keypresses, measure total time
5. Calculate: (total_time / 10) = avg latency per keypress
6. Compare: AC-2.4 (current) vs AC-2.3 (backup version)

**Test Screens** (highest expression density):
- Main Menu
- MediaPlayer OSD
- Theme Preview Top Right with Info Below
- Video Browser

### Option B: Automated Timing with API Instrumentation (Future)
**Pros**: Precise measurements, repeatable, automated logging
**Cons**: Requires Catbert instrumentation, may need code changes

**Approach** (not yet implemented):
- Add GetTime() calls in cached expressions
- Log delta between SetLocal execution and GetLocal retrieval
- Aggregate stats by screen

---

## AC-2.5 State-Correctness Testing

### Test 1: Setting Change Visibility
**Objective**: Verify that changing a setting reflects immediately when screen is visited again

**Steps**:
1. Navigate to **Theme Preview and Info Top** (uses `_c_GetProperty_display_video_on_menus_XIf_Active`)
2. Note current state of "Display Videos on Menus" setting
3. Go to **Configuration Wizard - Ask Display Videos on Menus**
4. Change setting to opposite value (On → Off or Off → On)
5. Return to **Theme Preview and Info Top**
6. **Expected**: Screen shows new setting value immediately (not stale cached value)
7. **Result**: ✅ PASS if new value visible, ❌ FAIL if old value cached

### Test 2: Media Start/Stop Events
**Objective**: Verify MediaStarted/MediaStopped events invalidate cache correctly

**Steps**:
1. Navigate to **MediaPlayer OSD**
2. Start playing a video
3. **Expected**: GetCurrentMediaFile() updates immediately
4. Stop playback
5. **Expected**: Cache clears, next playback resets properly

### Test 3: Favorite Add/Remove
**Objective**: Verify FavoriteAdded/FavoriteRemoved invalidate cache

**Steps**:
1. Navigate to **Favorites Manager**
2. Add a new favorite
3. **Expected**: Favorite appears in list immediately
4. Remove favorite
5. **Expected**: Favorite disappears immediately

---

## Performance Metrics to Capture

### AC-2.4 Latency (Critical)
```
Metric: Focus Move Latency
Definition: Time from keypress (Down arrow) to visual screen update
Baseline (without AC-2.3): ~300-500ms (estimated)
Target (with AC-2.4): ≤250ms (50% improvement)
Acceptance: ≥50% reduction vs pre-cached version
```

### AC-2.5 Correctness (Critical)
```
Metric: State-Change Visibility
Definition: Time from setting change to cache invalidation + re-display
Target: <100ms (human-imperceptible)
Acceptance: No stale values on screen refresh
```

### Memory Impact
```
Metric: Memory usage with AC-2.3 caching
Current: 634 MB (baseline with AC-2.4)
Threshold: <1000 MB (acceptable overhead)
Concern: Cache not leaking or growing unbounded
```

---

## Test Results Template

### AC-2.4 Latency Results
| Screen | AC-2.3 (ms) | AC-2.4 (ms) | Improvement | Pass/Fail |
|--------|-------------|-------------|-------------|-----------|
| Main Menu | - | ? | ?% | ? |
| MediaPlayer OSD | - | ? | ?% | ? |
| Theme Preview | - | ? | ?% | ? |
| Video Browser | - | ? | ?% | ? |

### AC-2.5 State-Correctness Results
| Test | Expected | Actual | Pass/Fail |
|------|----------|--------|-----------|
| Setting Change | Immediate update | ? | ? |
| Media Start/Stop | Cache invalidates | ? | ? |
| Favorite Add | List updates | ? | ? |
| Favorite Remove | List updates | ? | ? |

---

## Known Limitations & Future Improvements

### AC-2.5 Current Design
- **Limitation**: FocusGained hook only refreshes when screen regains focus
- **Not Covered**: Real-time changes while viewing same screen (e.g., favorite added while Favorites Manager open)
- **Future**: Event-driven invalidation (AC-2.5.1) for continuous correctness

### Invalidation Strategy Trade-Offs
| Strategy | Coverage | Complexity | Latency |
|----------|----------|-----------|---------|
| Focus-based (AC-2.5) | 90% | Low | 1-5ms |
| Event-driven (AC-2.5.1) | 98% | High | 5-10ms |
| Periodic refresh | 100% | Medium | 100-500ms |

### Cache Size Overhead
- **Current**: 1.1 MB (8.5% increase) — acceptable
- **Risk**: Cache variables not leaking or accumulating
- **Monitoring**: Watch for unbounded memory growth if cache never clears

---

## Acceptance Criteria

### GO/NO-GO for Production Deployment
**MUST HAVE** (blocking):
- [x] AC-2.3 patches loaded correctly (78 SetLocal calls verified)
- [x] AC-2.4/AC-2.5 hooks embedded (124 FocusGained hooks verified)
- [x] SageTV starts cleanly (no crashes)
- [ ] AC-2.4 latency improvement ≥50% OR acceptable UI responsiveness
- [ ] AC-2.5 state-correctness passes all tests (no stale values)
- [ ] No new ERROR logs related to caching

**NICE TO HAVE**:
- [ ] Memory usage stable (no unbounded growth)
- [ ] Performance improvement measurable on all 28 screens
- [ ] Benchmark data for future optimization rounds

---

## Next Steps

1. **Execute Manual Testing** (pragmatic, can start immediately)
   - Time 10 keypresses on each test screen
   - Document results in table above

2. **Execute AC-2.5 Correctness Tests** (critical)
   - Test setting visibility
   - Test media events
   - Test favorite changes

3. **Analyze Results**
   - If AC-2.4 ≥50% latency improvement: ✅ PASS
   - If AC-2.5 all tests pass: ✅ PASS
   - If both pass: Ready for production

4. **Production Decision**
   - If GO: Apply patches to SageTV7.xml and promote
   - If NO-GO: Investigate issues, adjust strategy, re-test

---

## Troubleshooting Guide

### Issue: AC-2.5 Stale Cache (Setting Change Not Visible)
**Symptom**: Change setting, navigate away/back, old value still showing
**Root Cause**: FocusGained hook not firing OR SetLocal call failed
**Debug**:
```bash
docker exec sagetv-mine grep -i "FocusGained" /opt/sagetv/server/STVs/SageTV7/SageTV7.xml | head -5
docker exec sagetv-mine tail -100 /opt/sagetv/server/sagetv_0.txt | grep -i "SetLocal"
```
**Fix**: May need to re-embed hooks or adjust event trigger

### Issue: AC-2.4 Latency Not Improved
**Symptom**: Focus move still feels slow (no perceived improvement)
**Root Cause**: SetLocal/GetLocal overhead not negligible vs full function call
**Debug**: Compare actual GetLocal performance vs GetProperty via API timing
**Fix**: May need to profile which expressions are most expensive and prioritize

### Issue: Memory Leak (Growing Memory Usage)
**Symptom**: Memory increases over time (>1000 MB after hours of use)
**Root Cause**: SetLocal variables not cleared, or cache accumulating
**Debug**:
```bash
docker exec sagetv-mine ps -o rss= -p $(pgrep -x java)
# Monitor over 1 hour
```
**Fix**: May need explicit cache eviction policy or size limits

---

**Testing Status**: READY TO BEGIN
**Next Action**: Execute manual latency measurement on Main Menu
**Expected Completion**: 2-3 hours (manual testing)
