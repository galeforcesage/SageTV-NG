/*
 * Copyright 2026 SageTV-mine contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package sage.epg.ota;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import sage.Airing;
import sage.Channel;
import sage.DBObject;
import sage.Person;
import sage.Pooler;
import sage.Sage;
import sage.Show;
import sage.Wizard;

/**
 * Translates parsed ATSC1 PSIP EIT/ETT events into SageTV
 * {@link Wizard} Show + Airing records.
 *
 * <p>Merge priority:
 * <ul>
 *   <li>Existing airing has no extID, or extID starts with {@code NODATA::}
 *       — overwrite freely. This fills our placeholder rows.</li>
 *   <li>Existing airing comes from Schedules Direct (any extID not starting
 *       with {@code NODATA::} or {@code OTA::}) AND the airing starts more
 *       than {@code epg/ota_live_override_window_ms} (default 90 min) from
 *       now — leave SD alone. SD has richer metadata for future shows.</li>
 *   <li>Same as above but the airing starts within the live override
 *       window AND the OTA title differs — write the OTA version with
 *       extID prefix {@code OTA-OVERRIDE::}. The next SD refresh (daily)
 *       will reclaim the slot via {@link Wizard#addAiring} overlap
 *       resolution.</li>
 *   <li>Existing airing was already written by this scanner ({@code OTA::}
 *       or {@code OTA-OVERRIDE::}) — refresh in place.</li>
 * </ul>
 */
public final class OtaEpgIngestor
{
  private static final String PROP_LIVE_WINDOW_MS = "epg/ota_live_override_window_ms";
  private static final long   DEFAULT_LIVE_WINDOW_MS = 90L * 60L * 1000L;

  private static OtaEpgIngestor instance;
  public static synchronized OtaEpgIngestor getInstance()
  {
    if (instance == null) instance = new OtaEpgIngestor();
    return instance;
  }

  private OtaEpgIngestor() {}

  /**
   * Ingest one RF-mux capture's worth of guide data.
   *
   * @param vct    TVCT entries (one per subchannel on this mux)
   * @param events sourceId → list of EitEvent
   * @param etts   etmId → decoded long description text
   */
  public void ingest(List<PsipTables.VctEntry> vct,
                     Map<Integer, List<PsipTables.EitEvent>> events,
                     Map<Long, String> etts)
  {
    if (vct == null || vct.isEmpty() || events == null || events.isEmpty()) return;
    Wizard wiz = Wizard.getInstance();
    long now = Sage.time();
    long liveWindow = Sage.getLong(PROP_LIVE_WINDOW_MS, DEFAULT_LIVE_WINDOW_MS);

    int writes = 0, skips = 0, overrides = 0;
    for (PsipTables.VctEntry chan : vct)
    {
      Integer stationID = resolveStationID(wiz, chan);
      if (stationID == null)
      {
        skips++;
        continue;
      }
      List<PsipTables.EitEvent> list = events.get(chan.sourceId);
      if (list == null || list.isEmpty())
      {
        if (Sage.DBG) System.out.println("OtaEpgIngestor: " + chan.majorChannelNumber + "." +
            chan.minorChannelNumber + " (" + chan.shortName + ") sourceId=" + chan.sourceId +
            " — 0 EIT events from broadcaster");
        continue;
      }
      if (Sage.DBG) System.out.println("OtaEpgIngestor: " + chan.majorChannelNumber + "." +
          chan.minorChannelNumber + " (" + chan.shortName + ") sourceId=" + chan.sourceId +
          " — " + list.size() + " EIT events");
      for (PsipTables.EitEvent ev : list)
      {
        long startMs = PsipTables.gpsToUnixMillis(ev.startGps);
        long durMs   = ev.lengthSec * 1000L;
        if (durMs <= 0) continue;
        long endMs   = startMs + durMs;

        Outcome o = decide(wiz, stationID, startMs, endMs, ev, now, liveWindow);
        switch (o.action)
        {
          case SKIP:     skips++; break;
          case WRITE:    if (writeEvent(wiz, stationID, ev, startMs, durMs, chan, etts, "OTA")) writes++; break;
          case OVERRIDE: if (writeEvent(wiz, stationID, ev, startMs, durMs, chan, etts, "OTA-OVERRIDE")) overrides++; break;
        }
      }
    }
    if (Sage.DBG) System.out.println("OtaEpgIngestor: ingest done writes=" + writes + " overrides=" + overrides + " skips=" + skips);

    // If anything changed, nudge Carny + Scheduler so favorites/manual rules
    // pick up the new airings (or recompute around an OTA-OVERRIDE end-time
    // change for live sports) without waiting for the next periodic pass.
    if (writes > 0 || overrides > 0)
    {
      try { sage.Carny.getInstance().kick(); }
      catch (Throwable t) { if (Sage.DBG) System.out.println("OtaEpgIngestor: Carny.kick failed: " + t); }
      try { sage.SchedulerSelector.getInstance().kick(true); }
      catch (Throwable t) { if (Sage.DBG) System.out.println("OtaEpgIngestor: Scheduler.kick failed: " + t); }
    }
  }

  // ------------------------------------------------------------------

  private enum Action { WRITE, OVERRIDE, SKIP }
  private static final class Outcome { final Action action; Outcome(Action a){action=a;} }
  private static final Outcome WRITE    = new Outcome(Action.WRITE);
  private static final Outcome OVERRIDE = new Outcome(Action.OVERRIDE);
  private static final Outcome SKIP     = new Outcome(Action.SKIP);

  private Outcome decide(Wizard wiz, int stationID, long startMs, long endMs,
                         PsipTables.EitEvent ev, long now, long liveWindow)
  {
    Airing[] overlaps = wiz.getAirings(stationID, startMs, endMs - 1, false);
    if (overlaps == null || overlaps.length == 0) return WRITE;

    String otaExt = "OTA::" + ev.sourceId + "::" + ev.eventId;
    for (Airing a : overlaps)
    {
      Show sh0 = a.getShow();
      String ext = (sh0 != null) ? sh0.getExternalID() : null;
      if (ext == null) continue;
      if (ext.equals(otaExt) || ext.equals("OTA-OVERRIDE::" + ev.sourceId + "::" + ev.eventId))
        return WRITE; // refresh in place
      if (ext.startsWith("NODATA::") || ext.startsWith("OTA::") || ext.startsWith("OTA-OVERRIDE::"))
        continue;     // our own rows or placeholders — fine to replace
      // From here on, this is foreign data (presumably SD).
      if (startMs - now > liveWindow)
        return SKIP;  // future — let SD own it
      // Inside live window. Override only if the OTA title actually differs.
      Show sdShow = a.getShow();
      String sdTitle = (sdShow != null) ? sdShow.getTitle() : "";
      String otaTitle = ev.title == null ? "" : ev.title;
      if (sdTitle != null && sdTitle.equalsIgnoreCase(otaTitle))
        return SKIP;  // same title — no benefit
      return OVERRIDE;
    }
    return WRITE;
  }

  private boolean writeEvent(Wizard wiz, int stationID, PsipTables.EitEvent ev,
                             long startMs, long durMs,
                             PsipTables.VctEntry chan, Map<Long, String> etts,
                             String prefix)
  {
    String extID = prefix + "::" + ev.sourceId + "::" + ev.eventId;
    String title = (ev.title == null || ev.title.isEmpty()) ? ("Program " + ev.eventId) : ev.title;
    String desc  = (etts != null) ? etts.get(ev.etmId) : null;
    if (desc == null) desc = "";

    try
    {
      Show s = wiz.addShow(
          /* title           */ title,
          /* episodeName     */ "",
          /* desc            */ desc,
          /* duration        */ durMs,
          /* categories      */ Pooler.EMPTY_STRING_ARRAY,
          /* people          */ new Person[0],
          /* roles           */ Pooler.EMPTY_BYTE_ARRAY,
          /* rated           */ "",
          /* expandedRatings */ Pooler.EMPTY_STRING_ARRAY,
          /* year            */ "",
          /* parentalRating  */ "",
          /* bonus           */ Pooler.EMPTY_STRING_ARRAY,
          /* extID           */ extID,
          /* language        */ "",
          /* originalAirDate */ 0L,
          /* mediaMask       */ DBObject.MEDIA_MASK_TV,
          /* seasonNum       */ (short)0,
          /* episodeNum      */ (short)0,
          /* altEpisodeNum   */ (short)0,
          /* forcedUnique    */ false,
          /* showcardID      */ 0,
          /* seriesID        */ 0,
          /* imageIDs        */ Pooler.EMPTY_SHORT_ARRAY);
      if (s == null) return false;
      Airing a = wiz.addAiring(s, stationID, startMs, durMs,
          (byte)0, 0, (byte)0, DBObject.MEDIA_MASK_TV);
      return a != null;
    }
    catch (Throwable t)
    {
      if (Sage.DBG) { System.out.println("OtaEpgIngestor: addShow/Airing failed: " + t); }
      return false;
    }
  }

  // ------------------------------------------------------------------

  /**
   * Map an ATSC TVCT entry (major.minor + short name) to a SageTV stationID.
   * Strategy:
   * <ol>
   *   <li>Match channel number "major.minor" against {@link Channel#getNumber()}.</li>
   *   <li>Fallback: match short_name (case-insensitive) against
   *       {@link Channel#getName()}.</li>
   * </ol>
   */
  private Integer resolveStationID(Wizard wiz, PsipTables.VctEntry chan)
  {
    Map<String, Integer> idx = numberIndex(wiz);
    String key = chan.majorChannelNumber + "." + chan.minorChannelNumber;
    Integer hit = idx.get(key);
    if (hit != null) return hit;
    if (chan.shortName != null && !chan.shortName.isEmpty())
    {
      String lc = chan.shortName.trim().toLowerCase();
      for (Channel c : wiz.getChannels())
      {
        String n = c.getName();
        if (n != null && n.trim().toLowerCase().equals(lc))
          return c.getStationID();
      }
    }
    return null;
  }

  /** Cached per-call number → stationID index (rebuilt each ingest call). */
  private Map<String, Integer> numberIndex(Wizard wiz)
  {
    Map<String, Integer> m = new HashMap<>();
    Channel[] all = wiz.getChannels();
    if (all == null) return m;
    for (Channel c : all)
    {
      String num = c.getNumber();
      if (num != null && !num.isEmpty()) m.put(num, c.getStationID());
    }
    return m;
  }
}
