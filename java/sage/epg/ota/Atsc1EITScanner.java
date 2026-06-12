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
 *   <li><b>Sports refresh</b> (every {@code sports_interval_ms}): only fires
 *       when a sports overrun could affect a scheduled recording on this RF.
 *       Two cases (see {@code findSportsTriggerForRf}):
 *       <b>1a</b> sports airing is itself the scheduled recording and we're
 *       within {@code pre_end_lead} of its scheduled end;
 *       <b>1b</b> a scheduled recording is preceded on the same station by a
 *       sports airing within {@code followon_window} (default 3h), and we're
 *       within {@code pre_end_lead} of that sports airing's scheduled end.
 *       Anchored on scheduled end \u2014 broadcasters update PSIP near that point.</li>
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
  /** True when {@link #PROP_DEVICE_ID} points at an HDHomeRun that is NOT
   *  configured as a SageTV capture source (MMC video source). When set,
   *  the safety gate trusts that no recording/live-TV ever touches this
   *  device, so it skips the dual-tuner opt-in, the both-idle requirement,
   *  and the upcoming-recording lineup-overlap check. Still requires at
   *  least one tuner reporting idle (sanity).
   *  Default false (production: scan device is also an MMC source). */
  private static final String PROP_DEDICATED          = "epg/ota_scan_device_dedicated";
  private static final String PROP_REC_LOOKAHEAD_MS   = "epg/ota_scan_recording_lookahead_ms";
  private static final String PROP_INTERVAL_MS        = "epg/ota_scan_interval_ms";
  private static final String PROP_SPORTS_INTERVAL_MS = "epg/ota_scan_sports_interval_ms";
  /** How far before a sports event's scheduled end the sports refresh trigger
   *  becomes active. Overrun decisions show up in PSIP near scheduled end, so
   *  scanning earlier is wasted tuner time. Default 5 min. */
  private static final String PROP_SPORTS_PRE_END_LEAD_MS = "epg/ota_scan_sports_pre_end_lead_ms";
  /** Maximum scheduled gap between a sports event's end and a downstream
   *  scheduled recording's start for the cascade trigger (case 1b) to fire.
   *  Default 3 h — broadcasters rarely cascade overruns further. */
  private static final String PROP_SPORTS_FOLLOWON_WINDOW_MS = "epg/ota_scan_sports_followon_window_ms";
  private static final String PROP_PER_RF_MS          = "epg/ota_scan_per_rf_duration_ms";
  private static final String PROP_MIN_LOOKAHEAD_MS   = "epg/ota_scan_min_lookahead_ms";
  private static final String PROP_GLOBAL_BUDGET_MS   = "epg/ota_scan_global_budget_ms_per_hour";
  private static final String PROP_SKIP_RF            = "epg/ota_scan_skip_rf";
  /** CSV of RF major-channel numbers to dump per-subchannel mapping/coverage details for. */
  private static final String PROP_DEBUG_RFS          = "epg/ota_scan_debug_rfs";

  private static final long DEFAULT_INTERVAL_MS              = 4L * 60L * 60L * 1000L;  // 4h
  private static final long DEFAULT_SPORTS_INTERVAL_MS       = 10L * 60L * 1000L;       // 10min
  private static final long DEFAULT_SPORTS_PRE_END_LEAD_MS   = 5L * 60L * 1000L;        // 5min before sports.scheduledEnd
  private static final long DEFAULT_SPORTS_FOLLOWON_WINDOW_MS = 3L * 60L * 60L * 1000L; // 3h cascade window
  private static final long DEFAULT_PER_RF_MS                = 60_000L;                 // 60s: catches more of the EIT-1/2/3 carousel for sparse senders
  private static final long DEFAULT_MIN_LOOKAHEAD_MS         = 6L * 60L * 60L * 1000L;  // 6h (must be >= INTERVAL_MS so SD-covered RFs stay skipped between coverage cycles)
  private static final long DEFAULT_GLOBAL_BUDGET_MS   = 10L * 60L * 1000L;       // 10min/hr (scales with 60s per_rf)
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
      String trigReason = findSportsTriggerForRf(e.getValue(), now);
      if (trigReason == null) continue;
      if (!safetyGateOpen(deviceIp, "sports")) return;
      scanRf(e.getKey(), e.getValue(), "sports:" + trigReason);
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
    boolean debug = isDebugRf(subs);
    int mapped = 0, unmapped = 0;
    boolean anyUncovered = false;
    for (HdhrControl.LineupEntry le : subs)
    {
      Channel ch = findChannelByGuideNumber(wiz, le.guideNumber);
      if (ch == null)
      {
        unmapped++;
        if (debug) System.out.println("Atsc1EITScanner[dbg]: " + le.guideNumber
          + " (" + le.guideName + ") - UNMAPPED in SageTV");
        continue;
      }
      // Stations the user has marked unavailable (in every EPG source) are not
      // our business — skip them entirely instead of treating empty airings as a
      // gap that needs scanning.
      if (isStationUnavailableInAllSources(ch.getStationID()))
      {
        if (debug) System.out.println("Atsc1EITScanner[dbg]: " + le.guideNumber
          + " (" + le.guideName + ") stationID=" + ch.getStationID() + " UNAVAILABLE in all sources - skip");
        continue;
      }
      mapped++;
      boolean covered = hasSdCoverage(wiz, ch.getStationID(), now, horizon);
      if (debug)
      {
        Airing[] a = wiz.getAirings(ch.getStationID(), now, horizon, false);
        int total = (a == null) ? 0 : a.length;
        int sd = 0, nodata = 0, ota = 0, noshow = 0;
        StringBuilder sample = new StringBuilder();
        if (a != null) for (int i = 0; i < a.length; i++)
        {
          Airing x = a[i];
          Show sh = x.getShow(); String ext = sh != null ? sh.getExternalID() : null;
          if (ext == null) continue;
          if (ext.startsWith("NODATA::")) nodata++;
          else if (ext.startsWith("OTA::") || ext.startsWith("OTA-OVERRIDE::")) ota++;
          else if (ext.equals("NoShow")) noshow++;
          else sd++;
          if (i < 2)
          {
            String t = sh != null ? sh.getTitle() : "?";
            long durMin = (x.getEndTime() - x.getStartTime()) / 60000L;
            sample.append(" {ext=").append(ext).append(" title='").append(t)
              .append("' dur=").append(durMin).append("m}");
          }
        }
        System.out.println("Atsc1EITScanner[dbg]: " + le.guideNumber + " (" + le.guideName
          + ") stationID=" + ch.getStationID() + " airings total=" + total
          + " sd=" + sd + " noshow=" + noshow + " nodata=" + nodata + " ota=" + ota
          + " covered=" + covered + sample.toString());
      }
      if (!covered) anyUncovered = true;
    }
    if (anyUncovered) return false;
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
      // Exclude:
      //   NODATA::   — our placeholder
      //   OTA::      — our scanner output (would self-confirm)
      //   OTA-OVERRIDE:: — our override marker (likewise)
      //   NoShow     — SD's "no data available" placeholder (title="No Data")
      if (ext.startsWith("NODATA::") || ext.startsWith("OTA::")
          || ext.startsWith("OTA-OVERRIDE::") || ext.equals("NoShow")) continue;
      long s = Math.max(a.getStartTime(), start);
      long e = Math.min(a.getEndTime(),   end);
      if (e > s) covered += (e - s);
    }
    // Require >= 95% coverage to consider "covered"
    return covered >= (end - start) * 95L / 100L;
  }

  /**
   * Decide whether the sports refresh trigger should fire for this RF right now.
   * Returns a short reason string (for logging) or null to skip.
   *
   * <p>Two cases (both anchored on a sports airing's <i>scheduled end</i> —
   * overrun decisions only become visible in PSIP near that point):
   * <ul>
   *   <li><b>1a</b> — a sports airing on a subchannel of this RF is itself
   *       a scheduled recording and we are within
   *       <code>[sports.scheduledEnd - pre_end_lead, sports.scheduledEnd + followon_window]</code>.
   *       Scanning surfaces the new end-time so the in-flight recording extends.</li>
   *   <li><b>1b</b> — a scheduled recording R on a subchannel of this RF is
   *       preceded on the same station by a sports airing S with
   *       <code>S.scheduledEnd ≤ R.scheduledStart</code> and
   *       <code>R.scheduledStart - S.scheduledEnd ≤ followon_window</code>,
   *       and we are within <code>[S.scheduledEnd - pre_end_lead, R.scheduledStart]</code>.
   *       Scanning surfaces the overrun cascade so R's start shifts.</li>
   * </ul>
   * Cadence is provided by the outer 10-min sports loop; this method only
   * answers "is the window currently open for at least one (S,R) pair?".
   */
  private String findSportsTriggerForRf(List<HdhrControl.LineupEntry> subs, long now)
  {
    Wizard wiz = Wizard.getInstance();
    Seeker seeker = Seeker.getInstance();
    if (seeker == null) return null;
    long leadMs   = Sage.getLong(PROP_SPORTS_PRE_END_LEAD_MS, DEFAULT_SPORTS_PRE_END_LEAD_MS);
    long cascadeMs = Sage.getLong(PROP_SPORTS_FOLLOWON_WINDOW_MS, DEFAULT_SPORTS_FOLLOWON_WINDOW_MS);

    // Pull all scheduled recordings whose schedule window overlaps
    // [now - leadMs, now + cascadeMs]. The lower bound catches 1a where
    // the sports recording itself is about to end / running over.
    Airing[] scheduled = seeker.getInterleavedScheduledAirings(now - leadMs, now + cascadeMs);
    if (scheduled == null || scheduled.length == 0) return null;

    // Build set of station IDs on this RF (for fast membership test).
    java.util.Set<Integer> rfStations = new java.util.HashSet<>();
    for (HdhrControl.LineupEntry le : subs)
    {
      Channel ch = findChannelByGuideNumber(wiz, le.guideNumber);
      if (ch != null) rfStations.add(ch.getStationID());
    }
    if (rfStations.isEmpty()) return null;

    for (Airing r : scheduled)
    {
      if (r == null) continue;
      if (!rfStations.contains(r.getStationID())) continue;

      long rStart = r.getStartTime();
      long rEnd   = r.getEndTime();

      // Case 1a: the scheduled recording itself is sports, and we are within
      // [rEnd - leadMs, ...] (still recording or just past scheduled end).
      if (isSportsAiring(r) && now >= rEnd - leadMs)
      {
        return "1a rf-rec sportsEndsAt=" + Sage.df(rEnd);
      }

      // Case 1b: look at airings on R's station that scheduled-end at or before
      // R's scheduled start and within the cascade window. If any such airing
      // is sports and we are within [S.scheduledEnd - leadMs, R.scheduledStart],
      // trigger.
      long windowStart = rStart - cascadeMs;
      Airing[] preds = wiz.getAirings(r.getStationID(), windowStart, rStart, false);
      if (preds == null) continue;
      for (Airing s : preds)
      {
        if (s == null || s == r) continue;
        long sEnd = s.getEndTime();
        if (sEnd > rStart) continue;                      // overlaps R, not a predecessor
        if (rStart - sEnd > cascadeMs) continue;          // beyond cascade window
        if (now < sEnd - leadMs) continue;                // too early to scan
        if (now > rStart) continue;                       // R has already started (cascade settled)
        if (!isSportsAiring(s)) continue;
        return "1b precedingSportsEndsAt=" + Sage.df(sEnd) + " recStartsAt=" + Sage.df(rStart);
      }
    }
    return null;
  }

  private static boolean isSportsAiring(Airing a)
  {
    if (a == null) return false;
    Show sh = a.getShow();
    if (sh == null) return false;
    String cat = sh.getCategory();
    if (cat == null) return false;
    return cat.toLowerCase().contains("sport");
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

  /**
   * True if the station is marked unavailable in every EPG provider that has
   * an opinion. (canViewStation defaults to true for sources that have never
   * heard of the station, so we only consider sources where the station is
   * either explicitly available or explicitly unavailable to be informative —
   * but in practice the simpler "every source says unavailable" check is what
   * we want: if no provider can serve real data, the airings are stubs.)
   */
  private boolean isStationUnavailableInAllSources(int stationID)
  {
    sage.EPG epg = sage.EPG.getInstance();
    if (epg == null) return false;
    long[] provs = epg.getAllProviderIDs();
    if (provs == null || provs.length == 0) return false;
    boolean anyAvailable = false;
    for (long p : provs)
    {
      sage.EPGDataSource ds = epg.getSourceForProviderID(p);
      if (ds != null && ds.canViewStation(stationID)) { anyAvailable = true; break; }
    }
    return !anyAvailable;
  }

  /** True if any subchannel's major number matches the comma-separated debug RF list. */
  private boolean isDebugRf(List<HdhrControl.LineupEntry> subs)
  {
    String csv = Sage.get(PROP_DEBUG_RFS, "");
    if (csv == null || csv.trim().isEmpty() || subs == null || subs.isEmpty()) return false;
    String first = subs.get(0).guideNumber;
    int dot = first.indexOf('.');
    if (dot < 0) dot = first.indexOf('-');
    String major = (dot > 0) ? first.substring(0, dot) : first;
    for (String t : csv.split(","))
      if (major.equals(t.trim())) return true;
    return false;
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
    // Re-poll the tuner/device state on every callback; if anything no
    // longer looks safely idle, abort immediately so live TV/recording
    // can preempt the scan dwell.
    try
    {
      HdhrControl ctrl = new HdhrControl(deviceId);
      int tunerCount = inferTunerCount(deviceId);
      boolean dedicated = Sage.getBoolean(PROP_DEDICATED, false);
      int idleNeeded = dedicated ? 1 : Math.max(1, tunerCount - 1);

      HdhrControl.Status st = ctrl.queryStatus(tunerIdx);
      if (st == null || !st.isLocked() || !st.isStreaming())
      {
        if (Sage.DBG) System.out.println("Atsc1EITScanner: yielding, tuner " + tunerIdx + " no longer held by scan");
        return true;
      }

      if (countIdleTuners(ctrl, tunerCount) < idleNeeded)
      {
        if (Sage.DBG) System.out.println("Atsc1EITScanner: yielding, idle headroom dropped below " + idleNeeded + " tuners");
        return true;
      }

      if (!dedicated)
      {
        long lookahead = Sage.getLong(PROP_REC_LOOKAHEAD_MS, DEFAULT_REC_LOOKAHEAD_MS);
        if (hasUpcomingRecordingOnDevice(deviceId, lookahead))
        {
          if (Sage.DBG) System.out.println("Atsc1EITScanner: yielding, upcoming recording detected on device");
          return true;
        }
      }

      return false;
    }
    catch (IOException e)
    {
      return true;
    }
  }

  private int countIdleTuners(HdhrControl ctrl, int tunerCount)
  {
    int idle = 0;
    for (int t = 0; t < tunerCount; t++)
    {
      try
      {
        HdhrControl.Status st = ctrl.queryStatus(t);
        if (st != null && !st.isLocked() && !st.isStreaming()) idle++;
      }
      catch (IOException e) { /* count as busy */ }
    }
    return idle;
  }

  // ------------------------------------------------------------------
  //  Safety gating: tuner-count policy + upcoming-recording check
  // ------------------------------------------------------------------

  /**
   * Master safety check before any scan attempt. Enforces:
   * <ul>
   *   <li>1-tuner device → never scan (would block recording), unless
   *       {@code epg/ota_scan_device_dedicated=true}.</li>
   *   <li>2-tuner device → requires {@code epg/ota_scan_allow_dual_tuner=true}
   *       (default false) opt-in. Then identical to 3+ tuner rule:
   *       ≥ N-1 tuners currently idle, i.e. at most one busy.</li>
   *   <li>3+ tuner device → scan if at least N-1 tuners remain idle after
   *       we grab one (i.e. at most one currently busy).</li>
   *   <li>Defer if SageTV has a recording scheduled within the next
   *       {@code epg/ota_scan_recording_lookahead_ms} (default 5 min) on
   *       any channel this device's lineup contains — so the per-RF
   *       30 s dwell never collides with an upcoming scheduled tune.</li>
   *   <li>If {@code epg/ota_scan_device_dedicated=true}, ALL MMC-overlap
   *       rules are bypassed (dual-tuner opt-in, upcoming-recording
   *       lineup check, 1-tuner refusal). Only remaining gate is
   *       "≥1 tuner reports idle" as a sanity check. Use this when the
   *       scan device is an HDHomeRun that is NOT a SageTV capture
   *       source.</li>
   * </ul>
   *
   * <p>Note: live TV started on the spare tuner DURING an in-flight scan
   * is the one edge case not covered by the schedule check. The scanner
   * holds the tuner for ≤ {@code epg/ota_scan_per_rf_duration_ms}
   * (default 30 s), so the live client may briefly fail/retry; this is
   * accepted in exchange for the much larger benefit of getting EIT
   * updates during normal recording activity.
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
    final boolean dedicated = Sage.getBoolean(PROP_DEDICATED, false);

    if (tunerCount == 1 && !dedicated)
    {
      if (Sage.DBG) System.out.println("Atsc1EITScanner: device " + disc.deviceId + " has 1 tuner; refusing to scan");
      return false;
    }
    if (!dedicated && tunerCount == 2 && !Sage.getBoolean(PROP_ALLOW_DUAL, false))
    {
      if (Sage.DBG) System.out.println("Atsc1EITScanner: device " + disc.deviceId + " has 2 tuners; set " + PROP_ALLOW_DUAL + "=true to opt in");
      return false;
    }

    // Count idle tuners (lock=none AND bps=0). Need at least N-1 idle so
    // we leave behind the same headroom Sage expects for recording.
    // When dedicated, we only require ≥1 idle (this device is fully ours).
    String deviceId = Sage.get(PROP_DEVICE_ID, "");
    if (deviceId.isEmpty()) return false;
    HdhrControl ctrl = new HdhrControl(deviceId);
    int idle = countIdleTuners(ctrl, tunerCount);
    int idleNeeded = dedicated ? 1 : Math.max(1, tunerCount - 1);
    if (idle < idleNeeded)
    {
      if (Sage.DBG) System.out.println("Atsc1EITScanner: only " + idle + "/" + tunerCount + " tuners idle on " + disc.deviceId + " (need " + idleNeeded + "), defer (" + reason + ")");
      return false;
    }

    // Upcoming-recording check: don't scan if Sage plans to record soon
    // on a channel this device's lineup contains. Skipped for dedicated
    // devices since the scan device does not record anything itself.
    if (!dedicated)
    {
      long lookahead = Sage.getLong(PROP_REC_LOOKAHEAD_MS, DEFAULT_REC_LOOKAHEAD_MS);
      if (hasUpcomingRecordingOnDevice(deviceIp, lookahead))
      {
        if (Sage.DBG) System.out.println("Atsc1EITScanner: recording scheduled within " + lookahead + "ms on this device; defer (" + reason + ")");
        return false;
      }
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
