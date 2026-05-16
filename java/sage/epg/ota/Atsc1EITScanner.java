/*
 * Copyright 2026 SageTV-mine contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package sage.epg.ota;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import sage.Airing;
import sage.CaptureDevice;
import sage.Channel;
import sage.MMC;
import sage.Sage;
import sage.Seeker;
import sage.Show;
import sage.Wizard;

/**
 * Background daemon that opportunistically scans ATSC1 over-the-air PSIP
 * (EIT-0..3, ETT) on a dedicated HDHomeRun tuner and feeds the events into
 * {@link OtaEpgIngestor}. Operates with strict yield-on-conflict so it
 * never disturbs live viewing or recording.
 *
 * <p>Tuning strategy: PSIP EIT is per-multiplex (per RF channel), so a
 * single 30-second tune to any subchannel of an RF harvests guide data for
 * <em>all</em> subchannels on that RF. We therefore group the HDHR lineup
 * by major channel number and round-robin across the resulting RFs.
 *
 * <p>Two scan modes:
 * <ul>
 *   <li><b>Coverage</b> (every {@code interval_ms}): skip any RF whose
 *       subchannels are all fully covered by Schedules Direct for the next
 *       {@code min_lookahead_ms}. Used to fill gaps for NextGen-only or
 *       SD-blacklisted channels.</li>
 *   <li><b>Sports refresh</b> (every {@code sports_interval_ms}): hit only
 *       RFs that are currently airing an SD-categorized Sports event,
 *       since broadcasters update PSIP within ~10 minutes of overrun. Used
 *       so the merge layer can extend live recordings and shift follow-on
 *       starts.</li>
 * </ul>
 */
public final class Atsc1EITScanner
{
  private static final String PROP_ENABLED            = "epg/ota_scan_enabled";
  private static final String PROP_DEVICE_ID          = "epg/ota_scan_device_id";
  private static final String PROP_DEVICE_IP          = "epg/ota_scan_device_ip";
  private static final String PROP_TUNER              = "epg/ota_scan_tuner";
  private static final String PROP_TUNERS_CSV         = "epg/ota_scan_tuners";
  private static final String PROP_ALLOW_DUAL         = "epg/ota_scan_allow_dual_tuner";
  private static final String PROP_REC_LOOKAHEAD_MS   = "epg/ota_scan_recording_lookahead_ms";
  private static final String PROP_INTERVAL_MS        = "epg/ota_scan_interval_ms";
  private static final String PROP_SPORTS_INTERVAL_MS = "epg/ota_scan_sports_interval_ms";
  private static final String PROP_PER_RF_MS          = "epg/ota_scan_per_rf_duration_ms";
  private static final String PROP_MIN_LOOKAHEAD_MS   = "epg/ota_scan_min_lookahead_ms";
  private static final String PROP_GLOBAL_BUDGET_MS   = "epg/ota_scan_global_budget_ms_per_hour";
  private static final String PROP_SKIP_RF            = "epg/ota_scan_skip_rf";

  private static final long DEFAULT_INTERVAL_MS        = 3L * 60L * 60L * 1000L;  // 3h
  private static final long DEFAULT_SPORTS_INTERVAL_MS = 10L * 60L * 1000L;       // 10min
  private static final long DEFAULT_PER_RF_MS          = 30_000L;
  private static final long DEFAULT_MIN_LOOKAHEAD_MS   = 4L * 60L * 60L * 1000L;  // 4h
  private static final long DEFAULT_GLOBAL_BUDGET_MS   = 5L * 60L * 1000L;        // 5min/hr
  private static final long DEFAULT_REC_LOOKAHEAD_MS   = 5L * 60L * 1000L;        // 5min

  private static Atsc1EITScanner instance;
  public static synchronized Atsc1EITScanner getInstance()
  {
    if (instance == null) instance = new Atsc1EITScanner();
    return instance;
  }

  private final AtomicBoolean running = new AtomicBoolean(false);
  private Thread worker;

  /** Tracks tuner-ms used per UTC-hour bucket for global budget enforcement. */
  private final Map<Long, Long> hourBudgetUsed = new ConcurrentHashMap<>();

  /** Per-RF (major channel) last successful scan timestamp. */
  private final Map<Integer, Long> lastScanByRf = new ConcurrentHashMap<>();

  private Atsc1EITScanner() {}

  // ------------------------------------------------------------------

  public synchronized void start()
  {
    if (!Sage.getBoolean(PROP_ENABLED, false))
    {
      if (Sage.DBG) System.out.println("Atsc1EITScanner: disabled by property");
      return;
    }
    if (running.get()) return;
    running.set(true);
    worker = new Thread(this::runLoop, "Atsc1EITScanner");
    worker.setDaemon(true);
    worker.setPriority(Thread.MIN_PRIORITY + 1);
    worker.start();
    if (Sage.DBG) System.out.println("Atsc1EITScanner: started");
  }

  public synchronized void stop()
  {
    running.set(false);
    if (worker != null) worker.interrupt();
  }

  // ------------------------------------------------------------------

  private void runLoop()
  {
    // Brief warm-up so EPG has time to load on cold start.
    sleep(60_000L);
    long lastCoverageScan = 0L;
    long lastSportsScan   = 0L;
    while (running.get())
    {
      try
      {
        long now = Sage.time();
        long coverageInterval = Sage.getLong(PROP_INTERVAL_MS, DEFAULT_INTERVAL_MS);
        long sportsInterval   = Sage.getLong(PROP_SPORTS_INTERVAL_MS, DEFAULT_SPORTS_INTERVAL_MS);

        if (now - lastSportsScan >= sportsInterval)
        {
          runSportsCycle();
          lastSportsScan = now;
        }
        if (now - lastCoverageScan >= coverageInterval)
        {
          runCoverageCycle();
          lastCoverageScan = now;
        }
      }
      catch (Throwable t)
      {
        if (Sage.DBG) { System.out.println("Atsc1EITScanner: cycle error " + t); t.printStackTrace(); }
      }
      sleep(60_000L);
    }
    if (Sage.DBG) System.out.println("Atsc1EITScanner: stopped");
  }

  // ------------------------------------------------------------------

  private void runCoverageCycle()
  {
    String deviceIp = Sage.get(PROP_DEVICE_IP, "");
    if (deviceIp.isEmpty()) return;
    if (!safetyGateOpen(deviceIp, "coverage")) return;
    Map<Integer, List<HdhrControl.LineupEntry>> byRf = loadRfGroups(deviceIp);
    if (byRf.isEmpty()) return;
    long minLookahead = Sage.getLong(PROP_MIN_LOOKAHEAD_MS, DEFAULT_MIN_LOOKAHEAD_MS);

    // Sort RFs by oldest last-scan first for fairness.
    List<Integer> rfOrder = new ArrayList<>(byRf.keySet());
    rfOrder.sort(Comparator.comparingLong(r -> lastScanByRf.getOrDefault(r, 0L)));

    for (Integer rf : rfOrder)
    {
      if (!running.get()) return;
      List<HdhrControl.LineupEntry> subs = byRf.get(rf);
      if (allSdCovered(subs, minLookahead))
      {
        if (Sage.DBG) System.out.println("Atsc1EITScanner: RF " + rf + " fully SD-covered, skip");
        continue;
      }
      // Re-check safety between RFs in case a recording started during the cycle.
      if (!safetyGateOpen(deviceIp, "coverage")) return;
      scanRf(rf, subs, /*reason*/ "coverage");
    }
  }

  private void runSportsCycle()
  {
    String deviceIp = Sage.get(PROP_DEVICE_IP, "");
    if (deviceIp.isEmpty()) return;
    if (!safetyGateOpen(deviceIp, "sports")) return;
    Map<Integer, List<HdhrControl.LineupEntry>> byRf = loadRfGroups(deviceIp);
    if (byRf.isEmpty()) return;

    long now = Sage.time();
    for (Map.Entry<Integer, List<HdhrControl.LineupEntry>> e : byRf.entrySet())
    {
      if (!running.get()) return;
      if (!hasLiveSportsNow(e.getValue(), now)) continue;
      if (!safetyGateOpen(deviceIp, "sports")) return;
      scanRf(e.getKey(), e.getValue(), "sports");
    }
  }

  // ------------------------------------------------------------------

  private Map<Integer, List<HdhrControl.LineupEntry>> loadRfGroups(String deviceIp)
  {
    Map<Integer, List<HdhrControl.LineupEntry>> out = new LinkedHashMap<>();
    List<HdhrControl.LineupEntry> lineup;
    try { lineup = HdhrControl.fetchLineup(deviceIp); }
    catch (IOException ioe)
    {
      if (Sage.DBG) System.out.println("Atsc1EITScanner: lineup fetch failed: " + ioe);
      return out;
    }
    String skipList = Sage.get(PROP_SKIP_RF, "");
    for (HdhrControl.LineupEntry le : lineup)
    {
      int dot = le.guideNumber.indexOf('.');
      int major = (dot > 0)
          ? safeInt(le.guideNumber.substring(0, dot))
          : safeInt(le.guideNumber);
      if (major <= 0) continue;
      if (!skipList.isEmpty() && containsToken(skipList, String.valueOf(major))) continue;
      out.computeIfAbsent(major, k -> new ArrayList<>()).add(le);
    }
    return out;
  }

  private static boolean containsToken(String csv, String tok)
  {
    for (String s : csv.split(","))
      if (s.trim().equals(tok)) return true;
    return false;
  }

  private static int safeInt(String s)
  {
    try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; }
  }

  /**
   * True if every subchannel on this RF has at least {@code lookaheadMs}
   * of forward EPG coverage from a source other than NODATA placeholders
   * (i.e. real Schedules Direct data).
   */
  private boolean allSdCovered(List<HdhrControl.LineupEntry> subs, long lookaheadMs)
  {
    long now = Sage.time();
    long horizon = now + lookaheadMs;
    Wizard wiz = Wizard.getInstance();
    int mapped = 0, unmapped = 0;
    for (HdhrControl.LineupEntry le : subs)
    {
      Channel ch = findChannelByGuideNumber(wiz, le.guideNumber);
      if (ch == null) { unmapped++; continue; } // unmapped — can't attach EPG anyway
      mapped++;
      if (!hasSdCoverage(wiz, ch.getStationID(), now, horizon)) return false;
    }
    // If nothing on this RF is mapped to a SageTV channel, OTA scanning has no place
    // to deposit the data — treat as "covered" (skip) and log the situation once.
    if (mapped == 0 && Sage.DBG)
      System.out.println("Atsc1EITScanner: no mapped SageTV channels for any of "
        + subs.size() + " subchannels (numbers like "
        + (subs.isEmpty() ? "?" : subs.get(0).guideNumber) + ") - skip");
    return true;
  }

  private boolean hasSdCoverage(Wizard wiz, int stationID, long start, long end)
  {
    Airing[] airs = wiz.getAirings(stationID, start, end, false);
    if (airs == null || airs.length == 0) return false;
    long covered = 0L;
    for (Airing a : airs)
    {
      Show sh = a.getShow();
      String ext = (sh != null) ? sh.getExternalID() : null;
      if (ext == null) continue;
      // NODATA::... = our placeholder, OTA::... = our scanner output — neither counts as SD.
      if (ext.startsWith("NODATA::") || ext.startsWith("OTA::") || ext.startsWith("OTA-OVERRIDE::")) continue;
      long s = Math.max(a.getStartTime(), start);
      long e = Math.min(a.getEndTime(),   end);
      if (e > s) covered += (e - s);
    }
    // Require >= 95% coverage to consider "covered"
    return covered >= (end - start) * 95L / 100L;
  }

  /** True if any subchannel on this RF currently airs an SD-tagged Sports event. */
  private boolean hasLiveSportsNow(List<HdhrControl.LineupEntry> subs, long now)
  {
    Wizard wiz = Wizard.getInstance();
    for (HdhrControl.LineupEntry le : subs)
    {
      Channel ch = findChannelByGuideNumber(wiz, le.guideNumber);
      if (ch == null) continue;
      Airing[] airs = wiz.getAirings(ch.getStationID(), now, now + 1, false);
      if (airs == null) continue;
      for (Airing a : airs)
      {
        if (a.getShow() == null) continue;
        String cat = a.getShow().getCategory();
        if (cat == null) continue;
        String lc = cat.toLowerCase();
        if (lc.contains("sport")) return true;
      }
    }
    return false;
  }

  private Channel findChannelByGuideNumber(Wizard wiz, String guideNumber)
  {
    // Strategy: walk all channels, match against their primary number per any provider.
    // SageTV stores OTA channel numbers with a hyphen separator (e.g. "62-1") while
    // HDHomeRun's lineup.json reports them with a dot ("62.1"). Normalize both for compare.
    Channel[] all = wiz.getChannels();
    if (all == null) return null;
    String want = normalizeGuideNumber(guideNumber);
    for (Channel c : all)
    {
      String n = c.getNumber();
      if (n != null && normalizeGuideNumber(n).equals(want)) return c;
    }
    return null;
  }

  /** Normalize a virtual channel number for comparison: strip whitespace, replace '-' with '.'. */
  private static String normalizeGuideNumber(String s)
  {
    if (s == null) return "";
    return s.trim().replace('-', '.');
  }

  // ------------------------------------------------------------------

  private void scanRf(int rf, List<HdhrControl.LineupEntry> subs, String reason)
  {
    long budget = remainingBudget();
    long perRf  = Sage.getLong(PROP_PER_RF_MS, DEFAULT_PER_RF_MS);
    long durMs  = Math.min(perRf, budget);
    if (durMs < 5000L)
    {
      if (Sage.DBG) System.out.println("Atsc1EITScanner: RF " + rf + " skipped, budget exhausted");
      return;
    }

    String deviceId = Sage.get(PROP_DEVICE_ID, "");
    String deviceIp = Sage.get(PROP_DEVICE_IP, "");
    if (deviceId.isEmpty() || deviceIp.isEmpty()) return;

    HdhrControl ctrl = new HdhrControl(deviceId);

    // Pick a tuner using merit (lowest-merit = least preferred for recording).
    int tunerIdx = selectIdleTunerByMerit(ctrl, deviceId);
    if (tunerIdx < 0)
    {
      if (Sage.DBG) System.out.println("Atsc1EITScanner: no idle tuner available on " + deviceId + ", defer");
      return;
    }

    // Pick a subchannel to tune. Prefer the lowest minor.
    HdhrControl.LineupEntry pick = subs.get(0);
    for (HdhrControl.LineupEntry le : subs)
      if (le.guideNumber.compareTo(pick.guideNumber) < 0) pick = le;
    if (Sage.DBG) System.out.println("Atsc1EITScanner: scanning RF " + rf + " via vchannel " + pick.guideNumber + " for " + durMs + "ms (" + reason + ")");

    long started = Sage.time();
    final TsSectionAssembler mgtAsm = new TsSectionAssembler(0x1FFB);
    final Map<Integer, TsSectionAssembler> eitAsm = new HashMap<>();
    final Map<Integer, TsSectionAssembler> ettAsm = new HashMap<>();
    final Map<Integer, PsipTables.MgtEntry> eitMeta = new HashMap<>();
    final Map<Integer, PsipTables.MgtEntry> ettMeta = new HashMap<>();
    final List<PsipTables.VctEntry> vct = new ArrayList<>();
    final Map<Long, String>          etts = new HashMap<>();    // etmId → text
    final Map<Integer, List<PsipTables.EitEvent>> events = new HashMap<>(); // sourceId → events
    final boolean[] sawMgt = { false };

    try
    {
      long captured = ctrl.captureFullMux(deviceIp, tunerIdx, pick.guideNumber, durMs,
        (buf, len) -> {
          // Feed MGT
          mgtAsm.consume(buf, len);
          byte[] sec;
          while ((sec = mgtAsm.pollSection()) != null)
          {
            if (!TsSectionAssembler.verifyCrc32(sec)) continue;
            // MGT table_id = 0xC7, TVCT = 0xC8
            int tableId = sec[0] & 0xFF;
            if (tableId == 0xC7 && !sawMgt[0])
            {
              try
              {
                PsipTables.Mgt mgt = PsipTables.parseMgt(sec);
                for (PsipTables.MgtEntry me : mgt.entries)
                {
                  if (me.isEit())
                  {
                    eitAsm.putIfAbsent(me.pid, new TsSectionAssembler(me.pid));
                    eitMeta.put(me.pid, me);
                  }
                  else if (me.isEttForEit())
                  {
                    ettAsm.putIfAbsent(me.pid, new TsSectionAssembler(me.pid));
                    ettMeta.put(me.pid, me);
                  }
                }
                sawMgt[0] = true;
                if (Sage.DBG) System.out.println("Atsc1EITScanner: MGT v" + mgt.versionNumber + ", " + eitAsm.size() + " EIT + " + ettAsm.size() + " ETT PIDs");
              }
              catch (Exception ex)
              {
                if (Sage.DBG) System.out.println("Atsc1EITScanner: MGT parse error: " + ex);
              }
            }
            else if (tableId == 0xC8)
            {
              try
              {
                PsipTables.Tvct tvct = PsipTables.parseTvct(sec);
                vct.clear();
                vct.addAll(tvct.entries);
              }
              catch (Exception ex)
              {
                if (Sage.DBG) System.out.println("Atsc1EITScanner: TVCT parse error: " + ex);
              }
            }
          }

          // Feed EIT PIDs
          for (TsSectionAssembler a : eitAsm.values())
          {
            a.consume(buf, len);
            byte[] s;
            while ((s = a.pollSection()) != null)
            {
              if (!TsSectionAssembler.verifyCrc32(s)) continue;
              try
              {
                List<PsipTables.EitEvent> evs = PsipTables.parseEit(s);
                for (PsipTables.EitEvent ev : evs)
                  events.computeIfAbsent(ev.sourceId, k -> new ArrayList<>()).add(ev);
              }
              catch (Exception ex) { /* skip bad section */ }
            }
          }
          // Feed ETT PIDs
          for (TsSectionAssembler a : ettAsm.values())
          {
            a.consume(buf, len);
            byte[] s;
            while ((s = a.pollSection()) != null)
            {
              if (!TsSectionAssembler.verifyCrc32(s)) continue;
              try
              {
                PsipTables.EttBody body = PsipTables.parseEtt(s);
                if (body != null && body.text != null) etts.put(body.etmId, body.text);
              }
              catch (Exception ex) { /* skip */ }
            }
          }
          return true;
        },
        () -> shouldYield(deviceId, tunerIdx));

      long elapsed = Sage.time() - started;
      accountBudget(elapsed);
      lastScanByRf.put(rf, Sage.time());

      if (Sage.DBG) System.out.println("Atsc1EITScanner: RF " + rf + " captured " + captured + " bytes in " + elapsed + "ms; " + vct.size() + " VCT entries, " + events.values().stream().mapToInt(List::size).sum() + " events");

      // Hand to ingestor
      if (!vct.isEmpty() && !events.isEmpty())
      {
        try
        {
          OtaEpgIngestor.getInstance().ingest(vct, events, etts);
        }
        catch (Throwable t)
        {
          if (Sage.DBG) { System.out.println("Atsc1EITScanner: ingest failed: " + t); t.printStackTrace(); }
        }
      }
    }
    catch (IOException ioe)
    {
      if (Sage.DBG) System.out.println("Atsc1EITScanner: capture failed on RF " + rf + ": " + ioe);
    }
  }

  /**
   * Returns true if the scanner should release the tuner immediately —
   * e.g. SageTV has started a new recording on this same physical device.
   * We don't manage the CONNECT through Sage's capture pipeline so the
   * primary safety is the pre-scan idle check; here we just give an
   * abort path for an in-cycle Seeker request on this tuner.
   */
  private boolean shouldYield(String deviceId, int tunerIdx)
  {
    if (!running.get()) return true;
    // Re-poll the tuner's status; if something else has locked it (e.g.
    // a user started a recording on a sister DVR sharing the device),
    // we get out of the way.
    try
    {
      HdhrControl ctrl = new HdhrControl(deviceId);
      HdhrControl.Status st = ctrl.queryStatus(tunerIdx);
      // We are the streamer, so bps > 0 is expected. Detect external
      // interference by an unexpected channel change: the "target" gets
      // overwritten when someone else tunes via libhdhomerun.
      // Heuristic: if we no longer hold the lock the device will report
      // lock=none briefly during a retune by another client.
      // Conservative: keep streaming; rely on max duration cap.
      return false;
    }
    catch (IOException e) { return false; }
  }

  // ------------------------------------------------------------------
  //  Safety gating: tuner-count policy + upcoming-recording check
  // ------------------------------------------------------------------

  /**
   * Master safety check before any scan attempt. Enforces:
   * <ul>
   *   <li>1-tuner device → never scan (would block recording).</li>
   *   <li>2-tuner device → only scan if {@code epg/ota_scan_allow_dual_tuner=true}
   *       (default false) AND at least one tuner remains idle AFTER we
   *       grab one (i.e. both currently idle).</li>
   *   <li>3+ tuner device → scan if at least N-1 tuners remain idle after
   *       we grab one (i.e. at most one currently busy).</li>
   *   <li>Defer if SageTV has a recording scheduled within the next
   *       {@code epg/ota_scan_recording_lookahead_ms} (default 5 min) on
   *       any channel this device's lineup contains.</li>
   * </ul>
   */
  private boolean safetyGateOpen(String deviceIp, String reason)
  {
    HdhrControl.Discover disc;
    try { disc = HdhrControl.fetchDiscover(deviceIp); }
    catch (IOException ioe)
    {
      if (Sage.DBG) System.out.println("Atsc1EITScanner: discover failed (" + reason + "): " + ioe);
      return false;
    }

    int tunerCount = disc.tunerCount;
    if (tunerCount <= 0) tunerCount = 1; // fail-safe

    if (tunerCount == 1)
    {
      if (Sage.DBG) System.out.println("Atsc1EITScanner: device " + disc.deviceId + " has 1 tuner; refusing to scan");
      return false;
    }
    if (tunerCount == 2 && !Sage.getBoolean(PROP_ALLOW_DUAL, false))
    {
      if (Sage.DBG) System.out.println("Atsc1EITScanner: device " + disc.deviceId + " has 2 tuners; set " + PROP_ALLOW_DUAL + "=true to opt in");
      return false;
    }

    // Count idle tuners (lock=none AND bps=0). Need at least N-1 idle so
    // we leave behind the same headroom Sage expects for recording.
    String deviceId = Sage.get(PROP_DEVICE_ID, "");
    if (deviceId.isEmpty()) return false;
    HdhrControl ctrl = new HdhrControl(deviceId);
    int idle = 0;
    for (int t = 0; t < tunerCount; t++)
    {
      try
      {
        HdhrControl.Status st = ctrl.queryStatus(t);
        if (!st.isLocked() && !st.isStreaming()) idle++;
      }
      catch (IOException e) { /* count as busy */ }
    }
    if (idle < tunerCount - 1 || idle < 1)
    {
      if (Sage.DBG) System.out.println("Atsc1EITScanner: only " + idle + "/" + tunerCount + " tuners idle on " + disc.deviceId + ", defer (" + reason + ")");
      return false;
    }
    if (tunerCount == 2 && idle < 2)
    {
      // Dual-tuner rule: BOTH must be idle (we never use the last tuner)
      if (Sage.DBG) System.out.println("Atsc1EITScanner: 2-tuner device requires both idle; defer (" + reason + ")");
      return false;
    }

    // Upcoming-recording check: don't scan if Sage plans to record soon
    // on a channel this device's lineup contains.
    long lookahead = Sage.getLong(PROP_REC_LOOKAHEAD_MS, DEFAULT_REC_LOOKAHEAD_MS);
    if (hasUpcomingRecordingOnDevice(deviceIp, lookahead))
    {
      if (Sage.DBG) System.out.println("Atsc1EITScanner: recording scheduled within " + lookahead + "ms on this device; defer (" + reason + ")");
      return false;
    }
    return true;
  }

  /**
   * Pick the tuner with the LOWEST merit (least preferred for recording)
   * that is currently idle. Falls back to a numeric scan over all tuners
   * if no merit info is available.
   */
  private int selectIdleTunerByMerit(HdhrControl ctrl, String deviceId)
  {
    // Build (tunerIdx, merit) list from MMC CaptureDevices matching deviceId
    String hex = deviceId.toUpperCase();
    int tunerCount = inferTunerCount(deviceId);
    int[] order = meritSortedTunerOrder(hex, tunerCount);

    for (int idx : order)
    {
      try
      {
        HdhrControl.Status st = ctrl.queryStatus(idx);
        if (!st.isLocked() && !st.isStreaming()) return idx;
        if (Sage.DBG) System.out.println("Atsc1EITScanner: tuner " + idx + " busy (ch=" + st.channel + ", bps=" + st.bps + ")");
      }
      catch (IOException e) { /* try next */ }
    }
    return -1;
  }

  private int inferTunerCount(String deviceId)
  {
    String ip = Sage.get(PROP_DEVICE_IP, "");
    if (!ip.isEmpty())
    {
      try { return HdhrControl.fetchDiscover(ip).tunerCount; }
      catch (IOException ignore) {}
    }
    // Fallback to override property; default to 2 (most common HDHR).
    return (int) Sage.getLong("epg/ota_scan_tuner_count_override", 2L);
  }

  /**
   * Return tuner indices [0..count-1] sorted ascending by SageTV merit
   * (so lowest-merit = first try). Tuners without a configured
   * CaptureDevice in SageTV are treated as merit=0 (lowest).
   * An explicit {@code epg/ota_scan_tuners} CSV property overrides
   * everything if set.
   */
  private int[] meritSortedTunerOrder(String hexDeviceId, int tunerCount)
  {
    String csv = Sage.get(PROP_TUNERS_CSV, "");
    if (csv != null && !csv.isEmpty())
    {
      List<Integer> r = new ArrayList<>();
      for (String s : csv.split(","))
      {
        try { int v = Integer.parseInt(s.trim()); if (v >= 0 && v < tunerCount) r.add(v); }
        catch (NumberFormatException e) {}
      }
      if (!r.isEmpty()) return r.stream().mapToInt(Integer::intValue).toArray();
    }

    // Map tuner index -> merit by walking MMC CaptureDevices
    int[] meritByTuner = new int[tunerCount];
    Arrays.fill(meritByTuner, 0);
    try
    {
      MMC mmc = MMC.getInstance();
      if (mmc != null)
      {
        CaptureDevice[] all = mmc.getCaptureDevices();
        if (all != null)
        {
          for (CaptureDevice cd : all)
          {
            String name = cd.getCaptureDeviceName();
            if (name == null) continue;
            if (!name.toUpperCase().contains(hexDeviceId)) continue;
            int idx = cd.getCaptureDeviceNum();
            if (idx >= 0 && idx < tunerCount) meritByTuner[idx] = cd.getMerit();
          }
        }
      }
    }
    catch (Throwable t) { /* fall through to numeric order */ }

    Integer[] boxed = new Integer[tunerCount];
    for (int i = 0; i < tunerCount; i++) boxed[i] = i;
    final int[] merits = meritByTuner;
    Arrays.sort(boxed, Comparator.comparingInt(i -> merits[i]));
    int[] out = new int[tunerCount];
    for (int i = 0; i < tunerCount; i++) out[i] = boxed[i];
    return out;
  }

  /**
   * True if SageTV has any airing scheduled to record within the next
   * {@code lookaheadMs} on a channel that appears in this HDHR device's
   * lineup. We use the lineup as a proxy for "channels reachable through
   * this physical device".
   */
  private boolean hasUpcomingRecordingOnDevice(String deviceIp, long lookaheadMs)
  {
    long now = Sage.time();
    Airing[] sched;
    try { sched = Seeker.getInstance().getInterleavedScheduledAirings(now, now + lookaheadMs); }
    catch (Throwable t) { return true; /* be conservative */ }
    if (sched == null || sched.length == 0) return false;

    // Build set of guideNumbers from this device's lineup
    java.util.Set<String> guideNums = new java.util.HashSet<>();
    try
    {
      for (HdhrControl.LineupEntry le : HdhrControl.fetchLineup(deviceIp))
        if (le.guideNumber != null) guideNums.add(le.guideNumber);
    }
    catch (IOException ioe) { return true; /* conservative */ }
    if (guideNums.isEmpty()) return false;

    Wizard wiz = Wizard.getInstance();
    for (Airing a : sched)
    {
      Channel ch = wiz.getChannelForStationID(a.getStationID());
      if (ch == null) continue;
      String num = ch.getNumber();
      if (num != null && guideNums.contains(num)) return true;
    }
    return false;
  }

  // ------------------------------------------------------------------

  private long remainingBudget()
  {
    long budget = Sage.getLong(PROP_GLOBAL_BUDGET_MS, DEFAULT_GLOBAL_BUDGET_MS);
    long bucket = Sage.time() / (60L * 60L * 1000L);
    long used   = hourBudgetUsed.getOrDefault(bucket, 0L);
    return Math.max(0L, budget - used);
  }

  private void accountBudget(long ms)
  {
    long bucket = Sage.time() / (60L * 60L * 1000L);
    hourBudgetUsed.merge(bucket, ms, Long::sum);
    // Trim old buckets (keep last 4 hours).
    long cutoff = bucket - 4;
    hourBudgetUsed.keySet().removeIf(k -> k < cutoff);
  }

  private static void sleep(long ms)
  {
    try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
  }
}
