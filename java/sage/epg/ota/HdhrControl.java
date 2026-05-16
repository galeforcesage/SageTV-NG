/*
 * Copyright 2026 SageTV-mine contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package sage.epg.ota;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import sage.Sage;

/**
 * Thin wrapper around the {@code hdhomerun_config} CLI tool to control a
 * SiliconDust HDHomeRun for OTA PSIP scanning. We intentionally do NOT use
 * the device's HTTP {@code /auto/vX.Y} endpoint because it strips PSIP
 * (PID 0x1FFB) — only the program ES PIDs are forwarded.
 *
 * <p>Capabilities:
 * <ul>
 *   <li>{@link #queryStatus(int)} — read /tunerN/status (used to detect
 *       an in-flight recording before we grab the tuner).</li>
 *   <li>{@link #tuneVchannel(int, String)} — set the virtual channel
 *       (e.g. "9.2"); the device resolves frequency.</li>
 *   <li>{@link #captureFullMux(int, int, ChunkConsumer)} — set program=0
 *       (full mux including PSIP) and stream raw TS to a callback for the
 *       requested duration. Releases the tuner on return.</li>
 * </ul>
 *
 * <p>Designed to be used by the EIT scanner with strict yield semantics: the
 * capture method polls {@link YieldGate} every chunk so the scanner can
 * release the tuner within ≤200 ms of a recording request.
 */
public final class HdhrControl
{
  /** Default path to hdhomerun_config inside the container. */
  private static final String HDHR_BIN_DEFAULT = "/usr/bin/hdhomerun_config";

  /** Hex device ID like "104D0AA7". */
  private final String deviceId;

  public HdhrControl(String deviceId)
  {
    this.deviceId = deviceId;
  }

  /** Where to look for the hdhomerun_config binary. */
  private static String binary()
  {
    return Sage.get("epg/ota_hdhomerun_config_path", HDHR_BIN_DEFAULT);
  }

  // ----------------------------------------------------------------------

  public static final class Status
  {
    public final String channel;     // e.g. "8vsb:503000000" or "none"
    public final String lock;        // e.g. "8vsb" or "none"
    public final int    bps;
    Status(String c, String l, int b) { channel = c; lock = l; bps = b; }
    public boolean isLocked()  { return !"none".equalsIgnoreCase(lock); }
    public boolean isStreaming(){ return bps > 0; }
  }

  public Status queryStatus(int tunerIndex) throws IOException
  {
    String out = runConfig(new String[] {
        deviceId, "get", "/tuner" + tunerIndex + "/status"
    }, 3000);
    if (out == null) return new Status("none", "none", 0);
    String ch = "none", lock = "none";
    int bps = 0;
    for (String tok : out.trim().split("\\s+"))
    {
      int eq = tok.indexOf('=');
      if (eq < 0) continue;
      String k = tok.substring(0, eq);
      String v = tok.substring(eq + 1);
      if (k.equals("ch"))   ch = v;
      else if (k.equals("lock")) lock = v;
      else if (k.equals("bps"))  try { bps = Integer.parseInt(v); } catch (NumberFormatException e) {}
    }
    return new Status(ch, lock, bps);
  }

  /** Set the virtual channel; device resolves frequency. */
  public void tuneVchannel(int tunerIndex, String vchannel) throws IOException
  {
    runConfig(new String[] {
        deviceId, "set", "/tuner" + tunerIndex + "/vchannel", vchannel
    }, 5000);
  }

  /** Set the program filter (0 = full multiplex, including PSIP). */
  public void setProgram(int tunerIndex, int program) throws IOException
  {
    runConfig(new String[] {
        deviceId, "set", "/tuner" + tunerIndex + "/program", String.valueOf(program)
    }, 3000);
  }

  /** Point the tuner's stream target at the given UDP destination. */
  public void setTargetUdp(int tunerIndex, String hostIp, int port) throws IOException
  {
    runConfig(new String[] {
        deviceId, "set", "/tuner" + tunerIndex + "/target",
        "udp://" + hostIp + ":" + port
    }, 3000);
  }

  /** Release the tuner. */
  public void release(int tunerIndex)
  {
    try
    {
      runConfig(new String[] {
          deviceId, "set", "/tuner" + tunerIndex + "/channel", "none"
      }, 3000);
    }
    catch (IOException ignore) {}
  }

  // ----------------------------------------------------------------------

  /** Receives chunks of raw TS bytes; returns true to continue, false to stop. */
  public interface ChunkConsumer
  {
    boolean accept(byte[] buf, int len);
  }

  /** Signal from scanner: should we yield the tuner right now? */
  public interface YieldGate
  {
    boolean shouldYield();
  }

  /**
   * Tune the named virtual channel, set program=0 to receive the full multiplex
   * (so PSIP PID 0x1FFB is included), then capture raw TS bytes for up to
   * {@code maxDurationMs}, feeding each chunk to {@code sink}. Yields immediately
   * if {@code gate} returns true. Always releases the tuner on return.
   *
   * <p>Uses an HDHomeRun {@code udp://} target so the device pushes the FULL
   * multiplex (including PID 0x1FFB / PSIP) to a local UDP port. The HTTP pull
   * endpoint {@code /tunerN/v<vchannel>} only delivers the program ES PIDs
   * (no PSIP) on current FLEX/CONNECT firmwares; the bare {@code /tunerN}
   * URL returns 404. UDP target is the only reliable way to get PSIP.
   *
   * @return total bytes captured
   */
  public long captureFullMux(String deviceIp, int tunerIndex, String vchannel,
      long maxDurationMs, ChunkConsumer sink, YieldGate gate) throws IOException
  {
    // 1. Open UDP socket on an ephemeral local port.
    DatagramSocket sock = null;
    int localPort;
    String localIp;
    try
    {
      sock = new DatagramSocket();          // ephemeral
      sock.setReceiveBufferSize(2 * 1024 * 1024);
      sock.setSoTimeout(2000);
      localPort = sock.getLocalPort();
      localIp = Sage.get("epg/ota_local_ip", "");
      if (localIp.isEmpty()) localIp = pickReachableIp(deviceIp);
      if (localIp == null || localIp.isEmpty())
        throw new IOException("could not determine local IP reachable from " + deviceIp);
    }
    catch (SocketException se)
    {
      if (sock != null) sock.close();
      throw new IOException("UDP socket setup failed: " + se.getMessage(), se);
    }

    long total = 0;
    try
    {
      // 2. Tune & set program=0 BEFORE setting target so the first packets are valid.
      tuneVchannel(tunerIndex, vchannel);
      setProgram(tunerIndex, 0);
      // 3. Point the device at our UDP socket.
      setTargetUdp(tunerIndex, localIp, localPort);
      // 4. Brief settle so signal locks and target plumbing applies.
      try { Thread.sleep(300); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

      byte[] buf = new byte[1500 * 8];
      DatagramPacket pkt = new DatagramPacket(buf, buf.length);
      long started = System.currentTimeMillis();
      long lastPacketAt = started;
      while (System.currentTimeMillis() - started < maxDurationMs)
      {
        if (gate != null && gate.shouldYield())
        {
          if (Sage.DBG) System.out.println("HdhrControl: yielding tuner " + tunerIndex);
          break;
        }
        try
        {
          sock.receive(pkt);
        }
        catch (SocketTimeoutException ste)
        {
          // Nothing for 2s — abandon if the silence outlasts 5s.
          if (System.currentTimeMillis() - lastPacketAt > 5000)
          {
            if (Sage.DBG) System.out.println("HdhrControl: no UDP packets from "
              + deviceIp + " tuner" + tunerIndex + " for >5s, giving up");
            break;
          }
          continue;
        }
        int n = pkt.getLength();
        if (n <= 0) continue;
        lastPacketAt = System.currentTimeMillis();
        total += n;
        if (!sink.accept(pkt.getData(), n)) break;
      }
    }
    finally
    {
      // 5. Stop the device pushing to us, then release tuner.
      try { setTargetUdp(tunerIndex, "0.0.0.0", 0); } catch (IOException ignore) {}
      release(tunerIndex);
      try { sock.close(); } catch (Exception ignore) {}
    }
    return total;
  }

  /**
   * Find a local IPv4 address on an interface that can plausibly reach
   * {@code deviceIp}. Picks the first non-loopback, non-link-local address
   * on the same /24 as the device, falling back to any non-loopback IPv4.
   */
  private static String pickReachableIp(String deviceIp)
  {
    String prefix24 = null;
    try
    {
      String[] o = deviceIp.split("\\.");
      if (o.length == 4) prefix24 = o[0] + "." + o[1] + "." + o[2] + ".";
    }
    catch (Exception ignore) {}

    String fallback = null;
    try
    {
      Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
      while (nis != null && nis.hasMoreElements())
      {
        NetworkInterface ni = nis.nextElement();
        if (!ni.isUp() || ni.isLoopback()) continue;
        Enumeration<InetAddress> addrs = ni.getInetAddresses();
        while (addrs.hasMoreElements())
        {
          InetAddress a = addrs.nextElement();
          if (a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
          String ip = a.getHostAddress();
          if (ip.indexOf(':') >= 0) continue; // skip IPv6
          if (prefix24 != null && ip.startsWith(prefix24)) return ip;
          if (fallback == null) fallback = ip;
        }
      }
    }
    catch (SocketException ignore) {}
    return fallback;
  }

  // ----------------------------------------------------------------------

  private static String runConfig(String[] args, int timeoutMs) throws IOException
  {
    String[] cmd = new String[args.length + 1];
    cmd[0] = binary();
    System.arraycopy(args, 0, cmd, 1, args.length);
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    StringBuilder sb = new StringBuilder();
    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream())))
    {
      String line;
      while ((line = r.readLine()) != null) sb.append(line).append('\n');
    }
    try
    {
      if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS))
      {
        p.destroyForcibly();
        throw new IOException("hdhomerun_config timed out: " + String.join(" ", cmd));
      }
    }
    catch (InterruptedException ie)
    {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted");
    }
    if (p.exitValue() != 0)
      throw new IOException("hdhomerun_config exit=" + p.exitValue() + " out=" + sb.toString().trim());
    return sb.toString();
  }

  // ----------------------------------------------------------------------

  public static final class LineupEntry
  {
    public final String guideNumber;  // "9.2"
    public final String guideName;
    public final String url;
    LineupEntry(String n, String name, String u) { guideNumber = n; guideName = name; url = u; }
  }

  public static final class Discover
  {
    public final String friendlyName;
    public final String deviceId;
    public final int    tunerCount;
    Discover(String name, String id, int tc) { friendlyName = name; deviceId = id; tunerCount = tc; }
  }

  /**
   * Fetch the device's HTTP discover.json. Used to learn the tuner count
   * so the scanner can decide whether it is safe to grab one.
   */
  public static Discover fetchDiscover(String deviceIp) throws IOException
  {
    String body = httpGetString("http://" + deviceIp + "/discover.json", 3000, 5000);
    String name  = extractTop(body, "FriendlyName");
    String id    = extractTop(body, "DeviceID");
    String tcStr = extractTop(body, "TunerCount");
    int tc = 0;
    try { tc = Integer.parseInt(tcStr); } catch (Exception e) {}
    return new Discover(name, id, tc);
  }

  /**
   * Fetch the device's HTTP lineup.json. Used as the channel candidate list
   * for the scanner. Filters out ATSC3 entries automatically (those carry
   * the "/auto/vX.Y" with ATSC3 video codecs — handled separately by the
   * sibling-alias fallback).
   */
  public static List<LineupEntry> fetchLineup(String deviceIp) throws IOException
  {
    String body = httpGetString("http://" + deviceIp + "/lineup.json", 3000, 5000);
    return parseLineupJson(body);
  }

  private static String httpGetString(String url, int connectMs, int readMs) throws IOException
  {
    URL u = new URL(url);
    HttpURLConnection conn = (HttpURLConnection) u.openConnection();
    conn.setConnectTimeout(connectMs);
    conn.setReadTimeout(readMs);
    StringBuilder body = new StringBuilder();
    try (InputStream in = conn.getInputStream())
    {
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) > 0) body.append(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
    }
    finally { conn.disconnect(); }
    return body.toString();
  }

  /** Extract a top-level JSON field (string or numeric) without quotes. */
  private static String extractTop(String json, String key)
  {
    return extract(json, key); // reuse minimal extractor
  }

  /** Minimal JSON array-of-objects parser tuned for HDHR lineup.json. */
  static List<LineupEntry> parseLineupJson(String s)
  {
    List<LineupEntry> out = new ArrayList<>();
    int i = 0;
    int n = s.length();
    while (i < n)
    {
      int oStart = s.indexOf('{', i);
      if (oStart < 0) break;
      int depth = 0;
      int oEnd = oStart;
      for (; oEnd < n; oEnd++)
      {
        char c = s.charAt(oEnd);
        if (c == '{') depth++;
        else if (c == '}') { depth--; if (depth == 0) break; }
      }
      if (depth != 0) break;
      String obj = s.substring(oStart, oEnd + 1);
      String gn   = extract(obj, "GuideNumber");
      String gname= extract(obj, "GuideName");
      String url  = extract(obj, "URL");
      if (gn != null) out.add(new LineupEntry(gn, gname, url));
      i = oEnd + 1;
    }
    return out;
  }

  private static String extract(String obj, String key)
  {
    String needle = "\"" + key + "\":";
    int p = obj.indexOf(needle);
    if (p < 0) return null;
    p += needle.length();
    while (p < obj.length() && Character.isWhitespace(obj.charAt(p))) p++;
    if (p >= obj.length()) return null;
    char c = obj.charAt(p);
    if (c == '"')
    {
      int e = obj.indexOf('"', p + 1);
      if (e < 0) return null;
      return obj.substring(p + 1, e);
    }
    int e = p;
    while (e < obj.length() && obj.charAt(e) != ',' && obj.charAt(e) != '}') e++;
    return obj.substring(p, e).trim();
  }
}
