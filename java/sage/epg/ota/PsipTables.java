/*
 * Copyright 2026 SageTV-mine contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package sage.epg.ota;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ATSC A/65 PSIP table parsers: MGT (Master Guide Table),
 * TVCT (Terrestrial Virtual Channel Table), EIT (Event Information Table)
 * and ETT (Extended Text Table).
 *
 * <p>Each {@code parseXxx} method takes a complete, CRC-verified section
 * (the bytes the {@link TsSectionAssembler} hands out) and returns a
 * lightweight POJO. No allocation beyond the result objects.
 */
public final class PsipTables
{
  private PsipTables() {}

  // ---------- MGT ----------------------------------------------------------

  public static final class MgtEntry
  {
    public final int  tableType;   // 0x0100-0x017F = EIT-N, 0x0200-0x027F = ETT-EIT-N
    public final int  pid;
    public final int  versionNumber;

    MgtEntry(int t, int p, int v) { tableType = t; pid = p; versionNumber = v; }

    public boolean isEit()      { return tableType >= 0x0100 && tableType <= 0x017F; }
    public boolean isEttForEit(){ return tableType >= 0x0200 && tableType <= 0x027F; }
    public int eitIndex()       { return isEit() ? (tableType - 0x0100) : (tableType - 0x0200); }
  }

  public static final class Mgt
  {
    public final int versionNumber;
    public final List<MgtEntry> entries;
    Mgt(int v, List<MgtEntry> e) { versionNumber = v; entries = e; }
  }

  public static Mgt parseMgt(byte[] sec)
  {
    if (sec == null || sec.length < 13) return null;
    if ((sec[0] & 0xFF) != 0xC7) return null;
    int version = (sec[5] >> 1) & 0x1F;
    int tablesDefined = ((sec[9] & 0xFF) << 8) | (sec[10] & 0xFF);
    int p = 11;
    List<MgtEntry> out = new ArrayList<>(tablesDefined);
    for (int i = 0; i < tablesDefined; i++)
    {
      if (p + 11 > sec.length) break;
      int ttype = ((sec[p] & 0xFF) << 8) | (sec[p + 1] & 0xFF);
      int tpid  = ((sec[p + 2] & 0x1F) << 8) | (sec[p + 3] & 0xFF);
      int tver  = sec[p + 4] & 0x1F;
      int descLen = ((sec[p + 9] & 0x0F) << 8) | (sec[p + 10] & 0xFF);
      out.add(new MgtEntry(ttype, tpid, tver));
      p += 11 + descLen;
    }
    return new Mgt(version, out);
  }

  // ---------- TVCT ---------------------------------------------------------

  /**
   * One row of a TVCT: maps a virtual channel number (e.g. "9.2") to its
   * source_id and short_name. We use source_id to bind EIT events to a
   * SageTV {@code Channel}.
   */
  public static final class VctEntry
  {
    public final String shortName;          // 7-char UTF-16 short name
    public final int    majorChannelNumber; // 12-bit
    public final int    minorChannelNumber; // 12-bit (0 means analog-only)
    public final int    sourceId;
    public final int    serviceType;        // 0x02 = ATSC digital TV
    VctEntry(String n, int maj, int min, int src, int svc)
    { shortName = n; majorChannelNumber = maj; minorChannelNumber = min; sourceId = src; serviceType = svc; }
  }

  public static final class Tvct
  {
    public final List<VctEntry> entries;
    Tvct(List<VctEntry> e) { entries = e; }
  }

  public static Tvct parseTvct(byte[] sec)
  {
    if (sec == null || sec.length < 11) return null;
    int tid = sec[0] & 0xFF;
    if (tid != 0xC8 && tid != 0xC9) return null;
    int numChannels = sec[9] & 0xFF;
    int p = 10;
    List<VctEntry> out = new ArrayList<>(numChannels);
    for (int i = 0; i < numChannels && p + 32 <= sec.length; i++)
    {
      // 7 UTF-16BE chars short name = 14 bytes
      StringBuilder sn = new StringBuilder(7);
      for (int c = 0; c < 7; c++)
      {
        int hi = sec[p + c * 2] & 0xFF;
        int lo = sec[p + c * 2 + 1] & 0xFF;
        char ch = (char) ((hi << 8) | lo);
        if (ch != 0) sn.append(ch);
      }
      p += 14;
      int b0 = sec[p] & 0xFF, b1 = sec[p + 1] & 0xFF, b2 = sec[p + 2] & 0xFF;
      int major = ((b0 & 0x0F) << 6) | ((b1 & 0xFC) >> 2);
      int minor = ((b1 & 0x03) << 8) | b2;
      p += 3;
      // skip modulation_mode(1) + carrier_frequency(4) + channel_TSID(2) + program_number(2)
      p += 9;
      // ETM_location(2 bits) + access_controlled(1) + hidden(1) + reserved(2)
      // + hide_guide(1) + reserved(3) + service_type(6) ...
      // total 4 bytes for those bitfields
      int svc = sec[p + 1] & 0x3F;
      p += 2;
      int sourceId = ((sec[p] & 0xFF) << 8) | (sec[p + 1] & 0xFF);
      p += 2;
      int descLen = ((sec[p] & 0x03) << 8) | (sec[p + 1] & 0xFF);
      p += 2 + descLen;
      out.add(new VctEntry(sn.toString(), major, minor, sourceId, svc));
    }
    return new Tvct(out);
  }

  // ---------- EIT ----------------------------------------------------------

  public static final class EitEvent
  {
    public final int    sourceId;
    public final int    eventId;
    /** UTC start time in seconds since the GPS epoch (1980-01-06 00:00:00 UTC). */
    public final long   startGps;
    /** Length in seconds. */
    public final int    lengthSec;
    public final String title;
    /** ETM identifier (event_id is encoded into it) for ETT lookup. */
    public final long   etmId;

    EitEvent(int src, int eid, long s, int l, String t, long etm)
    { sourceId = src; eventId = eid; startGps = s; lengthSec = l; title = t; etmId = etm; }
  }

  /** Convert PSIP GPS epoch seconds to Unix epoch millis (with leap-second offset). */
  public static long gpsToUnixMillis(long gpsSeconds)
  {
    // GPS epoch = 1980-01-06 00:00:00 UTC, which is 315964800s after Unix epoch.
    // Leap seconds: GPS time does NOT include leap seconds, but UTC does.
    // As of 2017, GPS is 18s ahead of UTC. EIT start_time is "GPS seconds"
    // per A/65 so we subtract leap_second_offset to get UTC. The STT in PID
    // 0x1FFB carries the current offset; for typical 2026 use a constant 18.
    final long GPS_TO_UNIX_OFFSET = 315964800L - 18L;
    return (gpsSeconds + GPS_TO_UNIX_OFFSET) * 1000L;
  }

  public static List<EitEvent> parseEit(byte[] sec)
  {
    if (sec == null || sec.length < 14) return java.util.Collections.emptyList();
    if ((sec[0] & 0xFF) != 0xCB) return java.util.Collections.emptyList();
    int sourceId = ((sec[3] & 0xFF) << 8) | (sec[4] & 0xFF);
    int numEvents = sec[9] & 0xFF;
    int p = 10;
    List<EitEvent> out = new ArrayList<>(numEvents);
    for (int i = 0; i < numEvents; i++)
    {
      if (p + 12 > sec.length) break;
      int eventId = ((sec[p] & 0x3F) << 8) | (sec[p + 1] & 0xFF);
      long startGps =
          ((long)(sec[p + 2] & 0xFF) << 24) |
          ((long)(sec[p + 3] & 0xFF) << 16) |
          ((long)(sec[p + 4] & 0xFF) << 8)  |
          ((long)(sec[p + 5] & 0xFF));
      // ETM_location is 2 bits in sec[p+6] upper; lengthInSeconds is 20 bits
      int lengthSec = ((sec[p + 6] & 0x0F) << 16) | ((sec[p + 7] & 0xFF) << 8) | (sec[p + 8] & 0xFF);
      int titleLen = sec[p + 9] & 0xFF;
      int titleOff = p + 10;
      if (titleOff + titleLen > sec.length) break;
      String title = MssDecoder.decode(sec, titleOff, titleLen);
      int dOff = titleOff + titleLen;
      if (dOff + 2 > sec.length) break;
      int descLen = ((sec[dOff] & 0x0F) << 8) | (sec[dOff + 1] & 0xFF);
      // ETM_id is conventionally (source_id << 16) | (event_id << 2) for EIT events
      long etmId = ((long) sourceId << 16) | ((long) eventId << 2);
      out.add(new EitEvent(sourceId, eventId, startGps, lengthSec, title, etmId));
      p = dOff + 2 + descLen;
    }
    return out;
  }

  // ---------- ETT ----------------------------------------------------------

  /** ETT carries extended-text (long descriptions) for an event_id or channel. */
  public static final class EttBody
  {
    public final long   etmId;
    public final String text;
    EttBody(long id, String t) { etmId = id; text = t; }
  }

  public static EttBody parseEtt(byte[] sec)
  {
    if (sec == null || sec.length < 14) return null;
    if ((sec[0] & 0xFF) != 0xCC) return null;
    long etmId =
        ((long)(sec[9]  & 0xFF) << 24) |
        ((long)(sec[10] & 0xFF) << 16) |
        ((long)(sec[11] & 0xFF) << 8)  |
        ((long)(sec[12] & 0xFF));
    int  textOff = 13;
    int  textLen = sec.length - textOff - 4 /*CRC*/;
    if (textLen <= 0) return null;
    String text = MssDecoder.decode(sec, textOff, textLen);
    return new EttBody(etmId, text);
  }
}
