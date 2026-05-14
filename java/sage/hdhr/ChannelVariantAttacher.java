/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */
package sage.hdhr;

import sage.Channel;
import sage.ChannelVariant;
import sage.ChannelVariants;
import sage.Sage;
import sage.Wizard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase B of the ATSC 3.0 coexistence plan: groups
 * {@link HDHomeRunLineup.Entry} rows by physical TV station and attaches them
 * as {@link ChannelVariant}s on the existing SageTV {@link Channel}/Station
 * row -- without creating duplicate Channels for each ATSC standard.
 *
 * <p>Pairing order (first match wins):
 *   <ol>
 *     <li>Callsign: lineup {@code GuideName} (e.g. {@code "WGN-NG"}) matches
 *         an existing {@code Channel.name} (case-insensitive, with the trailing
 *         {@code -NG} or {@code -DT} suffix stripped on either side).</li>
 *     <li>GuideNumber: lineup major.minor matches {@code Channel.getNumber()}.</li>
 *     <li>Major+100 fallback: HDHR convention where an ATSC 3.0 virtual is
 *         numbered {@code (atsc1_major + 100)}.</li>
 *   </ol>
 * Unmatched ATSC 3.0 entries are logged once as {@code ATSC3: orphan ...} and
 * skipped (no Channel row is created for them; that's a future enhancement).
 *
 * <p>Logs every decision with the {@code ATSC3:} prefix so server log greps
 * surface the entire pairing run cleanly.
 */
public final class ChannelVariantAttacher
{
  private ChannelVariantAttacher() {}

  /**
   * Walk the lineup currently cached for the given device and attach variants
   * to matching SageTV Channels. Idempotent: re-running with the same device
   * is a no-op once dedupe takes effect.
   *
   * @param lineup current lineup snapshot (must be already-refreshed)
   * @param hdhrDeviceHexId 8-hex-char HDHR device id (or "" when unknown)
   */
  public static void attach(HDHomeRunLineup lineup, String hdhrDeviceHexId)
  {
    if (lineup == null) return;
    if (hdhrDeviceHexId == null) hdhrDeviceHexId = "";

    List<HDHomeRunLineup.Entry> all = snapshotEntries(lineup);
    if (all.isEmpty())
    {
      if (Sage.DBG) System.out.println("ATSC3: attach skipped, lineup is empty");
      return;
    }

    // Group by major. "109.1" -> "109", "9.1" -> "9".
    Map<String, List<HDHomeRunLineup.Entry>> byMajor = new HashMap<>();
    for (HDHomeRunLineup.Entry e : all)
    {
      String major = majorOf(e.guideNumber);
      if (major == null) continue;
      byMajor.computeIfAbsent(major, k -> new ArrayList<>(2)).add(e);
    }

    Channel[] allChannels = safeGetChannels();
    int paired = 0, attached = 0, orphans = 0;

    for (Map.Entry<String, List<HDHomeRunLineup.Entry>> me : byMajor.entrySet())
    {
      String major = me.getKey();
      List<HDHomeRunLineup.Entry> group = me.getValue();

      HDHomeRunLineup.Entry atsc3 = pickByCodec(group, true);
      HDHomeRunLineup.Entry atsc1 = pickByCodec(group, false);

      // Find the SageTV Channel: prefer pairing against the ATSC1 twin;
      // fall back to the ATSC3 entry's metadata if ATSC1 is absent.
      HDHomeRunLineup.Entry probe = (atsc1 != null) ? atsc1 : atsc3;
      Channel chan = findChannel(allChannels, probe, major);

      if (chan == null)
      {
        if (atsc3 != null)
        {
          orphans++;
          if (Sage.DBG) System.out.println("ATSC3: orphan service GuideNumber="
              + atsc3.guideNumber + " callsign=" + atsc3.guideName
              + " (no matching SageTV Channel)");
        }
        continue;
      }

      paired++;
      int sid = chan.getStationID();

      if (atsc1 != null)
      {
        ChannelVariant v = new ChannelVariant(
            ChannelVariant.TYPE_ATSC1,
            normCodec(atsc1.videoCodec, ChannelVariant.VCODEC_MPEG2),
            normCodec(atsc1.audioCodec, ChannelVariant.ACODEC_AC3),
            atsc1.guideNumber,
            hdhrDeviceHexId);
        ChannelVariants.add(sid, v);
        attached++;
      }
      if (atsc3 != null)
      {
        ChannelVariant v = new ChannelVariant(
            ChannelVariant.TYPE_ATSC3,
            normCodec(atsc3.videoCodec, ChannelVariant.VCODEC_HEVC),
            normCodec(atsc3.audioCodec, ChannelVariant.ACODEC_AC4),
            atsc3.guideNumber,
            hdhrDeviceHexId);
        ChannelVariants.add(sid, v);
        attached++;
        if (Sage.DBG) System.out.println("ATSC3: paired GuideNumber="
            + atsc3.guideNumber + " callsign=" + atsc3.guideName
            + " -> stationID=" + sid + " name=" + chan.getName()
            + " (twin=" + (atsc1 != null ? atsc1.guideNumber : "<none>")
            + " method=" + matchMethodHint(chan, probe, major) + ")");
      }
    }

    if (Sage.DBG) System.out.println("ATSC3: attach summary device="
        + (hdhrDeviceHexId.length() == 0 ? "<unknown>" : hdhrDeviceHexId)
        + " majors=" + byMajor.size() + " paired=" + paired
        + " variants_added=" + attached + " orphans=" + orphans);
  }

  // ---- helpers ----

  private static List<HDHomeRunLineup.Entry> snapshotEntries(HDHomeRunLineup lineup)
  {
    // No public iterator; probe via lookup() over keys would require a public
    // accessor we don't want to add. Instead reuse the package-private map
    // through reflection-free trick: a dedicated accessor.
    return lineup.allEntries();
  }

  private static Channel[] safeGetChannels()
  {
    try
    {
      Wizard wiz = Wizard.getInstance();
      if (wiz == null) return new Channel[0];
      return wiz.getChannels();
    }
    catch (Throwable t)
    {
      if (Sage.DBG) System.out.println("ATSC3: getChannels failed: " + t);
      return new Channel[0];
    }
  }

  private static String majorOf(String guideNumber)
  {
    if (guideNumber == null || guideNumber.length() == 0) return null;
    int dot = guideNumber.indexOf('.');
    return (dot < 0) ? guideNumber : guideNumber.substring(0, dot);
  }

  /** Return the entry matching the requested codec class, or null. */
  private static HDHomeRunLineup.Entry pickByCodec(
      List<HDHomeRunLineup.Entry> group, boolean wantHevc)
  {
    for (HDHomeRunLineup.Entry e : group)
    {
      boolean isHevc = "HEVC".equalsIgnoreCase(e.videoCodec);
      if (isHevc == wantHevc) return e;
    }
    return null;
  }

  private static String normCodec(String raw, String fallback)
  {
    if (raw == null || raw.length() == 0) return fallback;
    if ("MPEG2".equalsIgnoreCase(raw))     return ChannelVariant.VCODEC_MPEG2;
    if ("MPEG2VIDEO".equalsIgnoreCase(raw))return ChannelVariant.VCODEC_MPEG2;
    if ("H264".equalsIgnoreCase(raw))      return ChannelVariant.VCODEC_H264;
    if ("H.264".equalsIgnoreCase(raw))     return ChannelVariant.VCODEC_H264;
    if ("HEVC".equalsIgnoreCase(raw))      return ChannelVariant.VCODEC_HEVC;
    if ("AC3".equalsIgnoreCase(raw))       return ChannelVariant.ACODEC_AC3;
    if ("EAC3".equalsIgnoreCase(raw))      return ChannelVariant.ACODEC_EAC3;
    if ("AC4".equalsIgnoreCase(raw))       return ChannelVariant.ACODEC_AC4;
    if ("AAC".equalsIgnoreCase(raw))       return ChannelVariant.ACODEC_AAC;
    return raw;
  }

  /** Pairing order: callsign -> guidenumber -> major+100 -> null. */
  private static Channel findChannel(Channel[] all, HDHomeRunLineup.Entry probe, String major)
  {
    if (probe == null) return null;

    // 1) Callsign match
    if (probe.guideName != null && probe.guideName.length() > 0)
    {
      String wanted = stripBroadcastSuffix(probe.guideName).toUpperCase();
      for (Channel c : all)
      {
        String cn = c.getName();
        if (cn == null) continue;
        if (stripBroadcastSuffix(cn).toUpperCase().equals(wanted)) return c;
      }
    }

    // 2) GuideNumber == Channel.getNumber()
    if (probe.guideNumber != null && probe.guideNumber.length() > 0)
    {
      for (Channel c : all)
      {
        try
        {
          String n = c.getNumber();
          if (n != null && n.equals(probe.guideNumber)) return c;
        }
        catch (Throwable t) { /* getNumber may NPE before EPG ready */ }
      }
    }

    // 3) Major+100 fallback (ATSC3 = ATSC1 + 100 on some markets)
    int majorInt = parseIntOr(major, -1);
    if (majorInt >= 100)
    {
      String twinMajor = String.valueOf(majorInt - 100);
      for (Channel c : all)
      {
        try
        {
          String n = c.getNumber();
          if (n != null && majorOf(n).equals(twinMajor)) return c;
        }
        catch (Throwable t) {}
      }
    }
    return null;
  }

  /** Strip "-DT", "-NG", "-HD" suffixes that HDHR appends but EPG often omits. */
  private static String stripBroadcastSuffix(String s)
  {
    if (s == null) return "";
    String u = s.toUpperCase();
    if (u.endsWith("-DT") || u.endsWith("-NG") || u.endsWith("-HD"))
      return s.substring(0, s.length() - 3);
    return s;
  }

  private static String matchMethodHint(Channel chan, HDHomeRunLineup.Entry probe, String major)
  {
    if (probe == null || chan == null) return "?";
    String wanted = probe.guideName == null ? ""
        : stripBroadcastSuffix(probe.guideName).toUpperCase();
    String have = chan.getName() == null ? ""
        : stripBroadcastSuffix(chan.getName()).toUpperCase();
    if (wanted.length() > 0 && wanted.equals(have)) return "callsign";
    try
    {
      String n = chan.getNumber();
      if (n != null && probe.guideNumber != null && n.equals(probe.guideNumber))
        return "guidenumber";
    }
    catch (Throwable t) {}
    return "major+100";
  }

  private static int parseIntOr(String s, int def)
  {
    try { return Integer.parseInt(s); }
    catch (Throwable t) { return def; }
  }
}
