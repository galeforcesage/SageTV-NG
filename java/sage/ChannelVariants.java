/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */
package sage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory registry of {@link ChannelVariant}s keyed by stationID, persisted
 * to Sage.properties (key namespace {@code channel/variants/&lt;stationID&gt;}).
 *
 * <p>Persisting via Sage.properties (instead of Wiz.bin schema changes) keeps
 * the variant model fully reversible: deleting the property restores stock
 * SageTV behavior, and there is no DB migration. Variants are rebuilt from
 * scratch on every HDHomeRun channel scan, so cold-start without persistence
 * still works -- the file-backed copy just avoids a re-scan after restart.</p>
 *
 * <p>All methods are thread-safe via the registry's internal monitor.</p>
 */
public final class ChannelVariants
{
  private static final String PROP_PREFIX = "channel/variants/";
  /** Joins multiple variants for one stationID in the persisted property. */
  private static final String RECORD_SEP  = ";;";

  private static final Map<Integer, List<ChannelVariant>> REG = new HashMap<>();
  private static volatile boolean loaded = false;

  private ChannelVariants() {}

  /** Returns an unmodifiable snapshot of variants for {@code stationID}. */
  public static List<ChannelVariant> forStation(int stationID)
  {
    ensureLoaded();
    synchronized (REG)
    {
      List<ChannelVariant> list = REG.get(stationID);
      if (list == null || list.isEmpty()) return Collections.emptyList();
      return Collections.unmodifiableList(new ArrayList<>(list));
    }
  }

  /** True when the station has at least one ATSC 3.0 variant. */
  public static boolean hasAtsc3(int stationID)
  {
    for (ChannelVariant v : forStation(stationID))
      if (v.isAtsc3()) return true;
    return false;
  }

  /** First ATSC 3.0 variant for the station, or null. */
  public static ChannelVariant firstAtsc3(int stationID)
  {
    for (ChannelVariant v : forStation(stationID))
      if (v.isAtsc3()) return v;
    return null;
  }

  /** First ATSC 1.0 variant for the station, or null. */
  public static ChannelVariant firstAtsc1(int stationID)
  {
    for (ChannelVariant v : forStation(stationID))
      if (!v.isAtsc3()) return v;
    return null;
  }

  /** Append a variant; persists immediately. Duplicates (by equals) ignored. */
  public static void add(int stationID, ChannelVariant v)
  {
    if (v == null) return;
    ensureLoaded();
    synchronized (REG)
    {
      List<ChannelVariant> list = REG.get(stationID);
      if (list == null)
      {
        list = new ArrayList<>(2);
        REG.put(stationID, list);
      }
      if (!list.contains(v)) list.add(v);
      persistOne(stationID, list);
    }
  }

  /** Replace all variants for a station; persists immediately. */
  public static void setAll(int stationID, List<ChannelVariant> variants)
  {
    ensureLoaded();
    synchronized (REG)
    {
      if (variants == null || variants.isEmpty())
      {
        REG.remove(stationID);
        Sage.put(PROP_PREFIX + stationID, null);
      }
      else
      {
        List<ChannelVariant> copy = new ArrayList<>(variants);
        REG.put(stationID, copy);
        persistOne(stationID, copy);
      }
    }
  }

  /** Wipe a station's variants from registry and Sage.properties. */
  public static void clear(int stationID)
  {
    ensureLoaded();
    synchronized (REG)
    {
      REG.remove(stationID);
      Sage.put(PROP_PREFIX + stationID, null);
    }
  }

  /** Wipe everything (used at the start of a channel rescan). */
  public static void clearAll()
  {
    ensureLoaded();
    synchronized (REG)
    {
      for (Integer sid : new ArrayList<>(REG.keySet()))
        Sage.put(PROP_PREFIX + sid, null);
      REG.clear();
    }
  }

  /** Force a reload from Sage.properties (used by tests). */
  public static void reload()
  {
    synchronized (REG)
    {
      REG.clear();
      loaded = false;
      ensureLoaded();
    }
  }

  // -- internal --

  private static void ensureLoaded()
  {
    if (loaded) return;
    synchronized (REG)
    {
      if (loaded) return;
      String[] kids = null;
      try { kids = Sage.keys("channel/variants"); }
      catch (Throwable t) { /* node may not exist yet */ }
      if (kids != null)
      {
        for (String k : kids)
        {
          int sid;
          try { sid = Integer.parseInt(k); }
          catch (NumberFormatException nfe) { continue; }
          String packed = Sage.get(PROP_PREFIX + k, null);
          List<ChannelVariant> list = parse(packed);
          if (!list.isEmpty()) REG.put(sid, list);
        }
      }
      loaded = true;
    }
  }

  private static void persistOne(int stationID, List<ChannelVariant> list)
  {
    if (list == null || list.isEmpty())
    {
      Sage.put(PROP_PREFIX + stationID, null);
      return;
    }
    StringBuilder sb = new StringBuilder(list.size() * 32);
    for (int i = 0; i < list.size(); i++)
    {
      if (i > 0) sb.append(RECORD_SEP);
      sb.append(list.get(i).toPersistedString());
    }
    Sage.put(PROP_PREFIX + stationID, sb.toString());
  }

  private static List<ChannelVariant> parse(String packed)
  {
    if (packed == null || packed.length() == 0) return Collections.emptyList();
    List<ChannelVariant> out = new ArrayList<>(2);
    int from = 0;
    while (from <= packed.length())
    {
      int hit = packed.indexOf(RECORD_SEP, from);
      String rec = (hit < 0) ? packed.substring(from) : packed.substring(from, hit);
      ChannelVariant v = ChannelVariant.fromPersistedString(rec);
      if (v != null) out.add(v);
      if (hit < 0) break;
      from = hit + RECORD_SEP.length();
    }
    return out;
  }
}
