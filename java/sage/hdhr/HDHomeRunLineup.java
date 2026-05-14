/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package sage.hdhr;

import sage.Sage;
import sage.epg.sd.gson.JsonArray;
import sage.epg.sd.gson.JsonElement;
import sage.epg.sd.gson.JsonObject;
import sage.epg.sd.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Lightweight cache + accessor for an HDHomeRun device's /lineup.json.
 *
 * Only used by the new ATSC 3.0 HTTP-pull capture path (Phases 3-4).
 * Existing libhdhomerun-based capture for ATSC 1.0 channels is unchanged.
 *
 * Per-channel JSON entry shape (HDHomeRun firmware 20260326):
 * <pre>
 *   {
 *     "GuideNumber": "109.1",
 *     "GuideName":   "WGN-NG",
 *     "VideoCodec":  "HEVC",
 *     "AudioCodec":  "AC4",
 *     "URL":         "http://<hdhr-ip>:5004/auto/v<channel>",
 *     "DRM": 1                  // present + truthy => DRM, omit/0 => clear
 *   }
 * </pre>
 *
 * Sage.properties knobs:
 *   hdhr/lineup_cache_ttl_minutes  (default 60)
 *   hdhr/prefer_atsc3              (default false) - reserved for future
 *                                                    1.0->3.0 promotion logic
 */
public class HDHomeRunLineup
{
  private static final String PROP_TTL_MIN = "hdhr/lineup_cache_ttl_minutes";
  private static final int    DEFAULT_TTL_MINUTES = 60;
  private static final int    HTTP_CONNECT_TIMEOUT_MS = 5000;
  private static final int    HTTP_READ_TIMEOUT_MS    = 10000;

  /** host:port -> instance */
  private static final Map<String, HDHomeRunLineup> INSTANCES = new HashMap<String, HDHomeRunLineup>();

  public static synchronized HDHomeRunLineup forHost(String host)
  {
    HDHomeRunLineup l = INSTANCES.get(host);
    if (l == null)
    {
      l = new HDHomeRunLineup(host);
      INSTANCES.put(host, l);
    }
    return l;
  }

  private final String host;
  private volatile long lastFetchMs = 0L;
  private volatile Map<String, Entry> byChannel = Collections.emptyMap();

  private HDHomeRunLineup(String host)
  {
    this.host = host;
  }

  /** Force-refresh; otherwise refresh is lazy/TTL-driven on each lookup. */
  public synchronized void refresh()
  {
    try
    {
      Map<String, Entry> parsed = fetchAndParse();
      if (parsed != null)
      {
        byChannel = parsed;
        lastFetchMs = System.currentTimeMillis();
        if (Sage.DBG) System.out.println("HDHomeRunLineup: " + host
            + " cached " + parsed.size() + " channels");
        if (Sage.getBoolean("hdhr/atsc3_variant_pairing_enabled", true))
        {
          // Best-effort attach: skipped silently if Wizard isn't yet ready
          // (boot ordering) -- next refresh will retry.
          try
          {
            sage.Wizard wiz = sage.Wizard.getInstance();
            if (wiz != null)
              ChannelVariantAttacher.attach(this, hostToHexId(host));
          }
          catch (Throwable t)
          {
            if (Sage.DBG) System.out.println("ATSC3: attach error " + t);
          }
        }
      }
    }
    catch (Throwable t)
    {
      if (Sage.DBG) System.out.println("HDHomeRunLineup: refresh failed for "
          + host + ": " + t);
    }
  }

  /** Best-effort: HDHR hex device id lives in Sage.properties keyed by host. */
  private static String hostToHexId(String host)
  {
    if (host == null) return "";
    String hex = Sage.get("hdhr/host_to_devid/" + host, "");
    return hex == null ? "" : hex;
  }

  private void maybeRefresh()
  {
    long ttl = (long) Sage.getInt(PROP_TTL_MIN, DEFAULT_TTL_MINUTES) * 60_000L;
    if (System.currentTimeMillis() - lastFetchMs > ttl) refresh();
  }

  public Entry lookup(String channelNumber)
  {
    if (channelNumber == null || channelNumber.length() == 0) return null;
    maybeRefresh();
    Map<String, Entry> snap = byChannel;
    // Direct hit (e.g. "109.1" matches GuideNumber "109.1").
    Entry e = snap.get(channelNumber);
    if (e != null) return e;
    // Major-only key (e.g. Sage tunes "109" but lineup uses "109.1"). Find any
    // GuideNumber whose major part matches; prefer HEVC (ATSC 3.0) when more
    // than one virtual subchannel shares the same major.
    if (channelNumber.indexOf('.') < 0)
    {
      String majorDot = channelNumber + ".";
      Entry best = null;
      for (Map.Entry<String, Entry> me : snap.entrySet())
      {
        String k = me.getKey();
        if (k == null) continue;
        if (k.equals(channelNumber) || k.startsWith(majorDot))
        {
          Entry cand = me.getValue();
          if (cand == null) continue;
          if (best == null) { best = cand; continue; }
          // Prefer HEVC over non-HEVC; among same-codec ties, keep first.
          boolean bestIsHevc = "HEVC".equalsIgnoreCase(best.videoCodec);
          boolean candIsHevc = "HEVC".equalsIgnoreCase(cand.videoCodec);
          if (candIsHevc && !bestIsHevc) best = cand;
        }
      }
      return best;
    }
    return null;
  }

  /**
   * True when the channel exists, is HEVC, and is NOT DRM-protected.
   * Anything else (missing channel, DRM=1, MPEG-2 video, error) returns false.
   */
  public boolean isHevcNonDrm(String channelNumber)
  {
    Entry e = lookup(channelNumber);
    return e != null && !e.drm && "HEVC".equalsIgnoreCase(e.videoCodec);
  }

  /** Direct HTTP-pull URL for the channel, or null. */
  public String getHttpUrl(String channelNumber)
  {
    Entry e = lookup(channelNumber);
    return e == null ? null : e.url;
  }

  /** Snapshot of all current cache entries (Phase B variant pairing). */
  public java.util.List<Entry> allEntries()
  {
    maybeRefresh();
    Map<String, Entry> snap = byChannel;
    return new java.util.ArrayList<Entry>(snap.values());
  }

  private Map<String, Entry> fetchAndParse() throws Exception
  {
    URL u = new URL("http://" + host + "/lineup.json");
    HttpURLConnection c = (HttpURLConnection) u.openConnection();
    c.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
    c.setReadTimeout(HTTP_READ_TIMEOUT_MS);
    c.setRequestMethod("GET");
    try
    {
      int code = c.getResponseCode();
      if (code != 200) throw new RuntimeException("HTTP " + code);
      StringBuilder sb = new StringBuilder(8192);
      BufferedReader br = new BufferedReader(
          new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
      try
      {
        char[] buf = new char[4096];
        int n;
        while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
      }
      finally { br.close(); }

      JsonElement root = new JsonParser().parse(sb.toString());
      if (!root.isJsonArray()) return Collections.emptyMap();
      JsonArray arr = root.getAsJsonArray();
      Map<String, Entry> out = new HashMap<String, Entry>(arr.size() * 2);
      for (int i = 0; i < arr.size(); i++)
      {
        JsonElement el = arr.get(i);
        if (!el.isJsonObject()) continue;
        Entry e = parseEntry(el.getAsJsonObject());
        if (e != null && e.guideNumber != null)
          out.put(e.guideNumber, e);
      }
      return out;
    }
    finally
    {
      c.disconnect();
    }
  }

  private static Entry parseEntry(JsonObject o)
  {
    Entry e = new Entry();
    e.guideNumber = optString(o, "GuideNumber");
    e.guideName   = optString(o, "GuideName");
    e.videoCodec  = optString(o, "VideoCodec");
    e.audioCodec  = optString(o, "AudioCodec");
    e.url         = optString(o, "URL");
    e.drm         = optInt(o, "DRM", 0) != 0;
    return e.guideNumber == null ? null : e;
  }

  private static String optString(JsonObject o, String k)
  {
    JsonElement el = o.get(k);
    return (el == null || el.isJsonNull()) ? null : el.getAsString();
  }

  private static int optInt(JsonObject o, String k, int def)
  {
    JsonElement el = o.get(k);
    if (el == null || el.isJsonNull()) return def;
    try { return el.getAsInt(); } catch (Throwable t) { return def; }
  }

  public static final class Entry
  {
    public String  guideNumber;
    public String  guideName;
    public String  videoCodec;   // "MPEG2", "H264", "HEVC", ...
    public String  audioCodec;   // "AC3", "AAC", "AC4", ...
    public String  url;
    public boolean drm;

    @Override public String toString()
    {
      return "HDHRLineup{" + guideNumber + " " + guideName
          + " v=" + videoCodec + " a=" + audioCodec
          + (drm ? " DRM" : "") + "}";
    }
  }
}
