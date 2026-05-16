/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */
package sage.epg.atsc3;

import sage.Channel;
import sage.EPG;
import sage.Sage;
import sage.Wizard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * ATSC 3.0 ↔ ATSC 1.0 channel mirror manager.
 *
 * <p>Many NextGen TV deployments simulcast each ATSC 1.0 service as a "1xx.y"
 * ATSC 3.0 sibling (e.g. {@code 109.1} = {@code 9-1}). Schedules Direct rarely
 * carries program data for the {@code 1xx.y} side, so it shows up in the guide
 * as "No Data" even though the same programming is being broadcast.
 *
 * <p>This manager produces an in-Wizard guide mirror by tweaking the EPG lineup
 * map: for each pair, the ATSC 3.0 vchannel is re-pointed at the ATSC 1.0
 * sibling's {@code stationID} in the lineup that contains it. After that:
 * <ul>
 *   <li>The guide for {@code 109.1} shows the same Airings/Shows as {@code 9-1}.</li>
 *   <li>Favourites/manual rules keyed on the ATSC 1.0 station automatically
 *       become tunable on whichever input has the 1xx.y vchannel — Scheduler
 *       gets a second physical option for free.</li>
 *   <li>The Channel object for the 1xx.y stub stays in Wiz.bin but loses its
 *       lineup entry, so it's effectively orphaned (reversible).</li>
 * </ul>
 *
 * <p>DRM-protected ATSC 3.0 services are intentionally <b>NOT</b> mirrored —
 * doing so would let SageTV pick the encrypted physical leg and silently fail.
 * Operators flag DRM stations via the {@code atsc3/mirror_drm_stations} prop
 * (populated manually or by the {@code tools/probe-atsc3-drm.sh} helper).
 *
 * <p>Three user-facing modes drive a per-airing Scheduler hint (consumed by
 * {@code Scheduler} in a follow-on patch — this manager just exposes the sets):
 * <table>
 *   <tr><th>mode</th>             <th>clear pairs</th>                            <th>DRM pairs</th></tr>
 *   <tr><td>{@code atsc1_only}</td>     <td>mirror EPG, but ATSC3 hidden</td>     <td>no action</td></tr>
 *   <tr><td>{@code prefer_atsc1}</td>   <td>mirror EPG + ATSC3 as fallback</td>   <td>no action</td></tr>
 *   <tr><td>{@code prefer_atsc3}</td>   <td>mirror EPG + ATSC3 preferred</td>     <td>no action</td></tr>
 * </table>
 *
 * <p>Properties (all under namespace {@code atsc3/}):
 * <ul>
 *   <li>{@code mirror_enabled}        — master kill switch, default {@code false}.</li>
 *   <li>{@code mirror_mode}           — {@code atsc1_only | prefer_atsc1 | prefer_atsc3},
 *                                       default {@code prefer_atsc1}.</li>
 *   <li>{@code mirror_dry_run}        — log decisions but don't write, default {@code false}.</li>
 *   <li>{@code mirror_auto}           — auto-pair on callsign root, default {@code true}.</li>
 *   <li>{@code mirror_pairs}          — manual CSV {@code "109.1>9-1,102.1>2-1"} overrides auto.</li>
 *   <li>{@code mirror_pair_overrides} — per-pair mode CSV {@code "109.1>9-1:prefer_atsc3"}.</li>
 *   <li>{@code mirror_drm_stations}   — CSV of ATSC3 stationIDs known to be DRM (skip mirror).</li>
 *   <li>{@code mirror_refresh_interval_ms} — periodic re-apply, default 1h.</li>
 *   <li>{@code mirror_applied}        — audit trail of applied actions (manager-written).</li>
 * </ul>
 *
 * <p>All operations are idempotent and reversible. The manager only modifies
 * entries it created (tracked in {@code mirror_applied}); manual overrides are
 * preserved.
 */
public class Atsc3MirrorManager
{
  // ------------------------------------------------------------------
  //  Property keys
  // ------------------------------------------------------------------
  public  static final String PROP_ENABLED          = "atsc3/mirror_enabled";
  public  static final String PROP_MODE             = "atsc3/mirror_mode";
  public  static final String PROP_DRY_RUN          = "atsc3/mirror_dry_run";
  public  static final String PROP_AUTO             = "atsc3/mirror_auto";
  public  static final String PROP_PAIRS            = "atsc3/mirror_pairs";
  public  static final String PROP_PAIR_OVERRIDES   = "atsc3/mirror_pair_overrides";
  public  static final String PROP_DRM_STATIONS     = "atsc3/mirror_drm_stations";
  public  static final String PROP_REFRESH_INTERVAL = "atsc3/mirror_refresh_interval_ms";
  public  static final String PROP_APPLIED          = "atsc3/mirror_applied";

  public  static final String MODE_ATSC1_ONLY    = "atsc1_only";
  public  static final String MODE_PREFER_ATSC1  = "prefer_atsc1";
  public  static final String MODE_PREFER_ATSC3  = "prefer_atsc3";

  private static final long DEFAULT_REFRESH_INTERVAL_MS = 60L * 60L * 1000L;  // 1h

  private static final String ATSC3_RX = "^1[0-9][0-9]([-.][0-9]+)?$";
  private static final String ATSC1_RX = "^[1-9][0-9]?([-.][0-9]+)?$";

  // ------------------------------------------------------------------
  //  Singleton
  // ------------------------------------------------------------------
  private static volatile Atsc3MirrorManager instance;

  public static Atsc3MirrorManager getInstance()
  {
    if (instance == null)
    {
      synchronized (Atsc3MirrorManager.class)
      {
        if (instance == null) instance = new Atsc3MirrorManager();
      }
    }
    return instance;
  }

  // ------------------------------------------------------------------
  //  Runtime state (rebuilt on each apply())
  // ------------------------------------------------------------------
  private final Set<Integer> atsc1OnlyStations    = new HashSet<>();  // DRM pairs + atsc1_only-mode pairs
  private final Set<Integer> atsc3PreferredStations = new HashSet<>();  // prefer_atsc3 mode pairs
  private final Set<Integer> atsc1PreferredStations = new HashSet<>();  // prefer_atsc1 mode pairs

  private Thread refreshThread;
  private volatile boolean stopFlag = false;

  // ------------------------------------------------------------------
  //  Public API consumed by Scheduler hook
  // ------------------------------------------------------------------
  /** Stations the Scheduler should avoid placing on an ATSC 3.0 input. */
  public synchronized Set<Integer> getAtsc1OnlyStations()
  {
    return new HashSet<>(atsc1OnlyStations);
  }
  /** Stations the Scheduler should prefer an ATSC 3.0-capable input for. */
  public synchronized Set<Integer> getAtsc3PreferredStations()
  {
    return new HashSet<>(atsc3PreferredStations);
  }
  /** Stations the Scheduler should prefer an ATSC 1.0 input for. */
  public synchronized Set<Integer> getAtsc1PreferredStations()
  {
    return new HashSet<>(atsc1PreferredStations);
  }
  public String getMode()
  {
    return Sage.get(PROP_MODE, MODE_PREFER_ATSC1);
  }
  public boolean isEnabled()
  {
    return Sage.getBoolean(PROP_ENABLED, false);
  }
  public boolean isDryRun()
  {
    return Sage.getBoolean(PROP_DRY_RUN, false);
  }

  // ------------------------------------------------------------------
  //  Lifecycle
  // ------------------------------------------------------------------
  public void start()
  {
    if (refreshThread != null) return;
    if (!isEnabled())
    {
      if (Sage.DBG) System.out.println("Atsc3MirrorManager: disabled (" + PROP_ENABLED + "=false), not starting");
      return;
    }
    stopFlag = false;
    refreshThread = new Thread("Atsc3MirrorManager-Refresh")
    {
      @Override public void run()
      {
        // Wait a few seconds at startup so EPG + Wizard are fully initialised
        try { Thread.sleep(15_000L); } catch (InterruptedException ie) { return; }
        while (!stopFlag)
        {
          try { apply(); }
          catch (Throwable t)
          {
            if (Sage.DBG) System.out.println("Atsc3MirrorManager: apply failed: " + t);
          }
          long interval = Sage.getLong(PROP_REFRESH_INTERVAL, DEFAULT_REFRESH_INTERVAL_MS);
          try { Thread.sleep(Math.max(60_000L, interval)); } catch (InterruptedException ie) { return; }
        }
      }
    };
    refreshThread.setDaemon(true);
    refreshThread.setPriority(Thread.MIN_PRIORITY);
    refreshThread.start();
    if (Sage.DBG) System.out.println("Atsc3MirrorManager: started (mode=" + getMode() +
        ", dryRun=" + isDryRun() + ")");
  }

  public void stop()
  {
    stopFlag = true;
    if (refreshThread != null) refreshThread.interrupt();
    refreshThread = null;
  }

  // ------------------------------------------------------------------
  //  Core apply loop
  // ------------------------------------------------------------------
  public synchronized void apply()
  {
    if (!isEnabled())
    {
      if (Sage.DBG) System.out.println("Atsc3MirrorManager: not enabled — skip apply");
      return;
    }

    final boolean dryRun = isDryRun();
    final String  mode   = getMode();
    final Wizard  wiz    = Wizard.getInstance();
    final EPG     epg    = EPG.getInstance();
    if (wiz == null || epg == null) return;

    final Set<Integer> drm = parseDrmStations();
    final Map<String, String> manualPairs = parsePairs(Sage.get(PROP_PAIRS, ""));
    final Map<String, String> pairOverrideModes = parsePairOverrides(Sage.get(PROP_PAIR_OVERRIDES, ""));

    // Build the working pair list: explicit > auto-detect
    final Map<String, String> pairs;
    if (!manualPairs.isEmpty())
    {
      pairs = manualPairs;
      if (Sage.DBG) System.out.println("Atsc3MirrorManager: using " + pairs.size() + " manual pairs");
    }
    else if (Sage.getBoolean(PROP_AUTO, true))
    {
      pairs = detectPairs(wiz);
      if (Sage.DBG) System.out.println("Atsc3MirrorManager: auto-detected " + pairs.size() + " pairs");
    }
    else
    {
      if (Sage.DBG) System.out.println("Atsc3MirrorManager: auto=false and no manual pairs — nothing to do");
      return;
    }

    // Reset hint sets — they're recomputed each pass
    atsc1OnlyStations.clear();
    atsc3PreferredStations.clear();
    atsc1PreferredStations.clear();

    long[] providerIDs = epg.getAllProviderIDs();
    final List<String> auditOps = new ArrayList<>();
    int applied = 0, skippedDrm = 0, skippedMissing = 0, alreadyApplied = 0;

    for (Map.Entry<String, String> e : pairs.entrySet())
    {
      String mirrorVchan  = normalise(e.getKey());     // "109.1"
      String primaryVchan = normalise(e.getValue());   // "9-1" (preserve original separator)

      // Resolve both vchannels to Channel objects via Wizard lookup across lineups
      Channel mirrorCh  = findChannelByNumber(wiz, epg, providerIDs, e.getKey());
      Channel primaryCh = findChannelByNumber(wiz, epg, providerIDs, e.getValue());
      if (primaryCh == null)
      {
        if (Sage.DBG) System.out.println("Atsc3MirrorManager: pair " + mirrorVchan + ">" +
            primaryVchan + " — primary not in any lineup, skip");
        skippedMissing++;
        continue;
      }

      int primarySid = primaryCh.getStationID();
      int mirrorSid  = (mirrorCh != null) ? mirrorCh.getStationID() : 0;

      // Per-pair effective mode
      String effMode = pairOverrideModes.getOrDefault(mirrorVchan + ">" + primaryVchan, mode);

      // DRM pairs are forced to atsc1_only regardless of requested mode
      if (mirrorSid != 0 && drm.contains(mirrorSid))
      {
        effMode = MODE_ATSC1_ONLY;
        atsc1OnlyStations.add(primarySid);
        skippedDrm++;
        if (Sage.DBG) System.out.println("Atsc3MirrorManager: pair " + mirrorVchan + ">" + primaryVchan +
            " — mirror sid=" + mirrorSid + " is DRM, atsc1-only enforced");
        continue;
      }

      // Record scheduler-hint sets for non-DRM clear pairs
      if (MODE_ATSC1_ONLY.equals(effMode))    atsc1OnlyStations.add(primarySid);
      if (MODE_PREFER_ATSC3.equals(effMode))  atsc3PreferredStations.add(primarySid);
      if (MODE_PREFER_ATSC1.equals(effMode))  atsc1PreferredStations.add(primarySid);

      // For atsc1_only mode, EPG merge but DON'T expose vchannel on ATSC3 lineup
      // — practically this means leave the lineups alone (mirror not applied) so
      // the bogus stub channel keeps its own (empty) data and is unrecordable
      // via scheduling. We can still optionally show merged guide via Strategy B
      // (airing copy) in a future pass — out of scope for this iteration.
      if (MODE_ATSC1_ONLY.equals(effMode))
      {
        if (Sage.DBG) System.out.println("Atsc3MirrorManager: pair " + mirrorVchan + ">" + primaryVchan +
            " — effMode=atsc1_only, leaving ATSC3 lineup untouched");
        continue;
      }

      // For prefer_atsc1 / prefer_atsc3: find the lineup containing mirrorVchan
      // and re-point that mapping to primarySid. Idempotent — only writes if the
      // override would actually change.
      for (long provID : providerIDs)
      {
        Map<Integer, String[]> chanMap = epg.getLineup(provID);
        if (chanMap == null) continue;
        Integer currentSidForMirror = findStationForVchan(chanMap, e.getKey());
        if (currentSidForMirror == null) continue;  // mirror vchan not in this lineup

        // Skip if already pointing at primary
        if (currentSidForMirror.intValue() == primarySid)
        {
          alreadyApplied++;
          if (Sage.DBG) System.out.println("Atsc3MirrorManager: " + mirrorVchan + " already mirrored to " +
              primaryVchan + " (sid=" + primarySid + ") in provider " + provID);
          continue;
        }

        String op = "providerID=" + provID + ": setOverride(sid=" + primarySid + ", vchans=[" +
                    e.getKey() + "]) + clearOverride(sid=" + currentSidForMirror + ")";
        auditOps.add(op);
        if (dryRun)
        {
          System.out.println("Atsc3MirrorManager: [DRY-RUN] " + op);
        }
        else
        {
          // Merge: add the mirror vchannel under the primary's stationID. We use
          // setOverride which composes onto the existing lineup, preserving any
          // other vchannels primary already has on this provider (typically none
          // since we're in the ATSC3 lineup).
          String[] existing = chanMap.get(Integer.valueOf(primarySid));
          List<String> merged = new ArrayList<>();
          if (existing != null)
          {
            for (String v : existing) if (!merged.contains(v)) merged.add(v);
          }
          if (!merged.contains(e.getKey())) merged.add(e.getKey());
          epg.setOverride(provID, primarySid, merged.toArray(new String[0]));
          epg.clearOverride(provID, currentSidForMirror.intValue());
        }
        applied++;
      }
    }

    // Persist audit trail (best-effort, non-blocking)
    if (!auditOps.isEmpty() && !dryRun)
    {
      try { Sage.put(PROP_APPLIED, String.join("|", auditOps) + "@" + Sage.time()); }
      catch (Throwable t) { /* non-critical */ }
    }

    if (Sage.DBG) System.out.println("Atsc3MirrorManager: apply complete — applied=" + applied +
        " alreadyApplied=" + alreadyApplied + " skippedDrm=" + skippedDrm +
        " skippedMissing=" + skippedMissing + " dryRun=" + dryRun);
  }

  /** Convenience for a manual dry-run from a CLI / management API. */
  public synchronized void applyDryRun()
  {
    boolean was = Sage.getBoolean(PROP_DRY_RUN, false);
    try { Sage.putBoolean(PROP_DRY_RUN, true); apply(); }
    finally { Sage.putBoolean(PROP_DRY_RUN, was); }
  }

  // ------------------------------------------------------------------
  //  Auto-pair detection
  // ------------------------------------------------------------------
  /**
   * Walk all Channels and pair every "1xx.y" with its same-callsign-root x.y
   * sibling. The shortName/callsign comparison strips common suffixes that
   * differ between the ATSC1 and ATSC3 service descriptors (e.g. "WGNDT" vs
   * "WGN-NG"). Network field is used as a fall-back.
   */
  private Map<String, String> detectPairs(Wizard wiz)
  {
    Channel[] all = wiz.getChannels();
    if (all == null || all.length == 0) return Collections.emptyMap();

    Map<String, Channel> byNumber = new HashMap<>();
    for (Channel c : all)
    {
      if (c == null) continue;
      String n = c.getNumber();
      if (n == null || n.isEmpty()) continue;
      byNumber.put(normalise(n), c);
    }

    Map<String, String> pairs = new LinkedHashMap<>();
    for (Channel c : all)
    {
      if (c == null) continue;
      String num = c.getNumber();
      if (num == null || !num.matches(ATSC3_RX)) continue;
      String norm = normalise(num);                       // "109.1"
      // Strip leading "1" to get sibling normalized number "09.1" then trim
      // leading zeros to get "9.1"
      String tail = norm.substring(1);
      String siblingNorm = tail.replaceFirst("^0+", "");
      if (siblingNorm.isEmpty() || siblingNorm.startsWith("."))
        siblingNorm = "0" + siblingNorm.replaceFirst("^\\.", ".");
      // Try dot-form first, then dash-form (lineups use either)
      Channel sibling = byNumber.get(siblingNorm);
      if (sibling == null) sibling = byNumber.get(siblingNorm.replace('.', '-'));
      if (sibling == null) continue;

      // Sanity: callsign roots should match (avoid pairing 109.1 WGN with 9.1 if
      // 9.1 is, say, an unrelated lower-frequency station with a different
      // network). Compare cleaned names.
      if (!callsignRootsMatch(c, sibling))
      {
        if (Sage.DBG) System.out.println("Atsc3MirrorManager: candidate " + num + " (" +
            c.getName() + ") ↔ " + sibling.getNumber() + " (" + sibling.getName() +
            ") — callsign roots differ, skip");
        continue;
      }
      pairs.put(num, sibling.getNumber());
    }
    return pairs;
  }

  private static boolean callsignRootsMatch(Channel a, Channel b)
  {
    String ra = callsignRoot(a);
    String rb = callsignRoot(b);
    return ra.length() >= 3 && ra.equals(rb);
  }

  /** Strip common ATSC3/HDTV suffixes from a callsign for sibling matching. */
  private static String callsignRoot(Channel c)
  {
    String s = c.getName();
    if (s == null) s = "";
    s = s.toUpperCase().trim();
    // Drop trailing variants: -NG, -DT, DT, HD, -HD, NEXTGEN, NEXT, ATSC3
    for (String suf : new String[] {"-NEXTGEN","-NEXT","-ATSC3","-NG","-DT","-HD","NEXTGEN","ATSC3","NG","DT","HD"})
    {
      if (s.endsWith(suf)) s = s.substring(0, s.length() - suf.length());
    }
    return s.trim();
  }

  // ------------------------------------------------------------------
  //  Helpers
  // ------------------------------------------------------------------
  /** Normalise a vchannel string for comparison — replace '-' with '.', trim. */
  private static String normalise(String s)
  {
    return (s == null) ? "" : s.trim().replace('-', '.');
  }

  private static Channel findChannelByNumber(Wizard wiz, EPG epg, long[] providerIDs, String vchan)
  {
    if (vchan == null) return null;
    String want = normalise(vchan);
    Channel[] all = wiz.getChannels();
    if (all == null) return null;
    for (Channel c : all)
    {
      if (c == null) continue;
      String n = c.getNumber();
      if (n != null && normalise(n).equals(want)) return c;
    }
    return null;
  }

  /** Find which stationID currently owns the given vchannel string in a lineup map. */
  private static Integer findStationForVchan(Map<Integer, String[]> chanMap, String vchan)
  {
    if (chanMap == null || vchan == null) return null;
    String want = normalise(vchan);
    for (Map.Entry<Integer, String[]> e : chanMap.entrySet())
    {
      String[] vs = e.getValue();
      if (vs == null) continue;
      for (String v : vs)
      {
        if (v != null && normalise(v).equals(want)) return e.getKey();
      }
    }
    return null;
  }

  private static Map<String, String> parsePairs(String csv)
  {
    Map<String, String> rv = new LinkedHashMap<>();
    if (csv == null || csv.trim().isEmpty()) return rv;
    for (String p : csv.split(","))
    {
      String[] kv = p.trim().split(">");
      if (kv.length == 2 && !kv[0].isEmpty() && !kv[1].isEmpty())
        rv.put(kv[0].trim(), kv[1].trim());
    }
    return rv;
  }

  /** Parses "109.1>9-1:prefer_atsc3,102.1>2-1:atsc1_only". */
  private static Map<String, String> parsePairOverrides(String csv)
  {
    Map<String, String> rv = new HashMap<>();
    if (csv == null || csv.trim().isEmpty()) return rv;
    for (String p : csv.split(","))
    {
      int colon = p.lastIndexOf(':');
      if (colon < 0) continue;
      String pair = p.substring(0, colon).trim();
      String mode = p.substring(colon + 1).trim();
      if (!pair.isEmpty() && !mode.isEmpty())
        rv.put(normalisePairKey(pair), mode);
    }
    return rv;
  }

  private static String normalisePairKey(String p)
  {
    String[] kv = p.split(">");
    return kv.length == 2 ? normalise(kv[0]) + ">" + normalise(kv[1]) : p;
  }

  private static Set<Integer> parseDrmStations()
  {
    Set<Integer> rv = new HashSet<>();
    String csv = Sage.get(PROP_DRM_STATIONS, "");
    if (csv == null || csv.trim().isEmpty()) return rv;
    for (String s : csv.split(","))
    {
      try { rv.add(Integer.parseInt(s.trim())); }
      catch (NumberFormatException nfe) { /* ignore */ }
    }
    return rv;
  }
}
