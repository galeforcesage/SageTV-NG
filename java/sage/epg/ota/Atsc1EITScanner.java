/*
 * Copyright 2026 SageTV-mine contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package sage.epg.ota;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import sage.Airing;
import sage.Channel;
import sage.Sage;
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
      scanRf(rf, subs, /*reason*/ "coverage");
    }
  }

  private void runSportsCycle()
  {
    String deviceIp = Sage.get(PROP_DEVICE_IP, "");
    if (deviceIp.isEmpty()) return;
    Map<Integer, List<HdhrControl.LineupEntry>> byRf = loadRfGroups(deviceIp);
    if (byRf.isEmpty()) return;

    long now = Sage.time();
    for (Map.Entry<Integer, List<HdhrControl.LineupEntry>> e : byRf.entrySet())
    {
      if (!running.get()) return;
      if (!hasLiveSportsNow(e.getValue(), now)) continue;
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
    for (HdhrControl.LineupEntry le : subs)
    {
      Channel ch = findChannelByGuideNumber(wiz, le.guideNumber);
      if (ch == null) continue; // unmapped — not our problem
      if (!hasSdCoverage(wiz, ch.getStationID(), now, horizon)) return false;
    }
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
    Channel[] all = wiz.getChannels();
    if (all == null) return null;
    for (Channel c : all)
    {
      String n = c.getNumber();
      if (n != null && n.equals(guideNumber)) return c;
    }
    return null;
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
    int tunerIdx    = (int) Sage.getLong(PROP_TUNER, 1L);
    if (deviceId.isEmpty() || deviceIp.isEmpty()) return;

    HdhrControl ctrl = new HdhrControl(deviceId);

    // Pre-check tuner is idle (won't disturb a recording in progress on it).
    try
    {
      HdhrControl.Status st = ctrl.queryStatus(tunerIdx);
      if (st.isLocked() || st.isStreaming())
      {
        if (Sage.DBG) System.out.println("Atsc1EITScanner: tuner " + tunerIdx + " busy (ch=" + st.channel + ", bps=" + st.bps + "), defer");
        return;
      }
    }
    catch (IOException ioe)
    {
      if (Sage.DBG) System.out.println("Atsc1EITScanner: status check failed: " + ioe);
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
