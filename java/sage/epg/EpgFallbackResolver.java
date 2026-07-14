/*
 * Copyright 2026 The SageTV-NG Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package sage.epg;

import sage.Channel;
import sage.EPG;
import sage.Sage;
import sage.Wizard;

/**
 * EPG read-time fallback resolver.
 *
 * <p>For a given source {@code stationID} that has no {@code Airing} rows in
 * the requested time window, this class attempts to find a "sibling"
 * {@code stationID} whose airings should be aliased into the source's
 * grid. Two strategies, applied in order:
 *
 * <ol>
 *   <li><b>ATSC 3.0 numeric alias</b> (Phase 2 of the EPG roadmap):
 *       channels whose major number is {@code &gt;= alias_offset} are
 *       assumed to be ATSC 3.0 mirrors of an ATSC 1.0 channel at
 *       {@code (major - offset).minor}. Default offset is 100, matching
 *       HDHomeRun's convention (109.1 mirrors 9.1).</li>
 *   <li><b>Callsign fallback</b> (Phase 3): strip configurable suffixes
 *       (e.g. {@code -NG}, {@code -DT}, {@code -HD}) from the source
 *       callsign and look for any other Channel with the same stripped
 *       callsign. Off by default; opt-in via
 *       {@code epg/callsign_fallback_enabled}.</li>
 * </ol>
 *
 * <p>The resolver only returns aliasing candidates &mdash; it does not
 * itself fetch airings or modify any database row. Resolution is purely
 * read-only and idempotent. Callers are responsible for re-querying
 * {@link Wizard#getAirings(int, long, long, boolean)} with the resolved
 * fallback {@code stationID}.
 *
 * <p>Results are cached per source {@code stationID} (including misses)
 * to keep EPG-matrix queries cheap. The cache is invalidated whenever
 * the lineup changes via {@link #invalidate()}.
 */
public final class EpgFallbackResolver
{
  private static final int CACHE_MISS = -1;

  private static final EpgFallbackResolver INSTANCE = new EpgFallbackResolver();

  public static EpgFallbackResolver getInstance() { return INSTANCE; }

  private EpgFallbackResolver() {}

  /** {@code stationID -> resolved fallback stationID} (or {@code 0} for "no fallback"). */
  private final java.util.concurrent.ConcurrentHashMap<Integer, Integer> cache =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** Drop all cached resolutions. Call when the lineup or providers change. */
  public void invalidate()
  {
    cache.clear();
  }

  /**
   * @param sourceStationID the station whose airings came up empty
   * @return a different stationID to query for fallback airings, or {@code 0}
   *         if no fallback is available or this feature is disabled
   */
  public int resolveFallback(int sourceStationID)
  {
    if (sourceStationID == 0) return 0;

    Integer cached = cache.get(sourceStationID);
    if (cached != null) return cached.intValue();

    int resolved = computeFallback(sourceStationID);
    cache.put(sourceStationID, resolved);
    return resolved;
  }

  // -------- internal --------

  private int computeFallback(int sourceStationID)
  {
    Wizard wiz = Wizard.getInstance();
    if (wiz == null) return 0;
    Channel src = wiz.getChannelForStationID(sourceStationID);
    if (src == null) return 0;

    EPG epg = EPG.getInstance();
    if (epg == null) return 0;
    long[] provIDs = epg.getAllProviderIDs();
    if (provIDs == null || provIDs.length == 0) return 0;

    int aliasOffset = (int) Sage.getLong("epg/atsc3_alias_offset", 100);
    boolean aliasEnabled = Sage.getBoolean("epg/atsc3_alias_enabled", true);
    if (aliasEnabled && aliasOffset > 0)
    {
      int candidate = resolveByOffset(src, sourceStationID, provIDs, aliasOffset, epg);
      if (candidate != 0) return candidate;
    }

    if (Sage.getBoolean("epg/callsign_fallback_enabled", false))
    {
      int candidate = resolveByCallsign(src, sourceStationID, wiz);
      if (candidate != 0) return candidate;
    }

    return 0;
  }

  /**
   * Look for an ATSC 1.0 sibling at {@code (major - offset).minor}.
   */
  private int resolveByOffset(Channel src, int sourceStationID, long[] provIDs,
      int offset, EPG epg)
  {
    for (int p = 0; p < provIDs.length; p++)
    {
      String[] nums = epg.getChannels(provIDs[p], sourceStationID);
      if (nums == null) continue;
      for (int i = 0; i < nums.length; i++)
      {
        String siblingNum = shiftMajorBy(nums[i], -offset);
        if (siblingNum == null) continue;
        int siblingID = epg.guessStationID(provIDs[p], siblingNum);
        if (siblingID != 0 && siblingID != sourceStationID)
        {
          if (Sage.DBG) System.out.println("EPG fallback: " + nums[i]
              + " (st=" + sourceStationID + ") -> " + siblingNum
              + " (st=" + siblingID + ") via offset " + offset);
          return siblingID;
        }
      }
    }
    return 0;
  }

  /**
   * Parse a guide number {@code M.S} (or just {@code M}), shift the major
   * by {@code delta}, and return the new string &mdash; or {@code null}
   * if the input is not a positive offset target (i.e. negative shift would
   * leave a non-positive major).
   */
  static String shiftMajorBy(String num, int delta)
  {
    if (num == null || num.length() == 0) return null;
    int dot = num.indexOf('.');
    String majorStr = (dot < 0) ? num : num.substring(0, dot);
    String minorStr = (dot < 0) ? "" : num.substring(dot);
    int major;
    try { major = Integer.parseInt(majorStr); }
    catch (NumberFormatException nfe) { return null; }
    int shifted = major + delta;
    if (shifted <= 0) return null;
    return shifted + minorStr;
  }

  private int resolveByCallsign(Channel src, int sourceStationID, Wizard wiz)
  {
    String myCall = stripSuffixes(src.getName());
    int minLen = (int) Sage.getLong("epg/callsign_min_length", 3);
    if (myCall == null || myCall.length() < minLen) return 0;

    Channel[] all = wiz.getChannels();
    if (all == null) return 0;
    for (int i = 0; i < all.length; i++)
    {
      Channel c = all[i];
      if (c == null || c.getStationID() == sourceStationID) continue;
      String otherCall = stripSuffixes(c.getName());
      if (otherCall == null) continue;
      if (otherCall.equalsIgnoreCase(myCall))
      {
        if (Sage.DBG) System.out.println("EPG fallback: " + src.getName()
            + " (st=" + sourceStationID + ") -> " + c.getName()
            + " (st=" + c.getStationID() + ") via callsign");
        return c.getStationID();
      }
    }
    return 0;
  }

  static String stripSuffixes(String name)
  {
    if (name == null) return null;
    String s = name.trim();
    if (s.length() == 0) return null;
    String suffixCsv = Sage.get("epg/callsign_strip_suffixes", "-NG,-DT,-HD,-LD,-CD,-TV");
    String[] suffixes = suffixCsv.split(",");
    boolean changed;
    do
    {
      changed = false;
      for (int i = 0; i < suffixes.length; i++)
      {
        String suf = suffixes[i].trim();
        if (suf.length() > 0 && s.length() > suf.length()
            && s.regionMatches(true, s.length() - suf.length(), suf, 0, suf.length()))
        {
          s = s.substring(0, s.length() - suf.length());
          changed = true;
        }
      }
    } while (changed);
    return s;
  }
}
