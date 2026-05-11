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

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Pure-Java HDHomeRun UDP discovery (port 65001). Used by the ATSC 3.0
 * HTTP-pull path to locate the unit's IP from its 8-hex-digit device ID
 * (e.g. "abcd1234") so we can fetch /lineup.json without requiring the
 * user to hand-configure a host.
 *
 * Protocol reference: SiliconDust libhdhomerun source (hdhomerun_discover.c).
 *
 * Packet layout (big-endian):
 *   2B type        (0x0002 = discover request, 0x0003 = discover reply)
 *   2B body length
 *   N  body (TLVs)
 *   4B CRC-32 of (type+length+body), reflected IEEE polynomial == java.util.zip.CRC32
 *
 * TLVs in request:
 *   tag 0x01 len 0x04 + 4B device_type (0xFFFFFFFF = wildcard / TUNER)
 *   tag 0x02 len 0x04 + 4B device_id   (0xFFFFFFFF = wildcard)
 *
 * TLVs in reply (we only need):
 *   tag 0x01 0x04 device_type
 *   tag 0x02 0x04 device_id
 *
 * The reply's source-IP is the device's address — we ignore the body's
 * optional base_url TLV and just use the UDP source address.
 */
public final class HDHomeRunDiscover
{
  private static final int  PORT        = 65001;
  private static final int  DEVICE_TUNER = 0x00000001;
  private static final int  WILDCARD     = (int) 0xFFFFFFFF;
  private static final int  TIMEOUT_MS_DEFAULT = 1500;
  private static final String PROP_TIMEOUT_MS  = "hdhr/discovery_timeout_ms";

  private HDHomeRunDiscover() {}

  /**
   * Broadcast a discover request; return a map of deviceId (8-char hex,
   * lowercase) -> source IP (dotted-quad). Empty map on any error.
   */
  public static Map<String, String> discover()
  {
    Map<String, String> out = new HashMap<String, String>();
    int timeoutMs = Sage.getInt(PROP_TIMEOUT_MS, TIMEOUT_MS_DEFAULT);

    byte[] req = buildRequest(DEVICE_TUNER, WILDCARD);

    DatagramSocket sock = null;
    try
    {
      sock = new DatagramSocket();
      sock.setBroadcast(true);
      sock.setSoTimeout(timeoutMs);

      // Send to 255.255.255.255 AND every interface's specific broadcast addr,
      // because some Linux stacks (e.g. multi-NIC, host-networking docker) drop
      // global 255.255.255.255 silently. Belt + suspenders.
      List<InetAddress> targets = collectBroadcastAddrs();
      targets.add(InetAddress.getByName("255.255.255.255"));
      if (Sage.DBG) System.out.println("HDHomeRunDiscover: bcast targets=" + targets);
      for (InetAddress bcast : targets)
      {
        try
        {
          sock.send(new DatagramPacket(req, req.length, bcast, PORT));
        }
        catch (Throwable t)
        {
          if (Sage.DBG) System.out.println("HDHomeRunDiscover: send to " + bcast + " failed: " + t);
        }
      }

      long deadline = System.currentTimeMillis() + timeoutMs;
      byte[] buf = new byte[1500];
      while (System.currentTimeMillis() < deadline)
      {
        try
        {
          DatagramPacket reply = new DatagramPacket(buf, buf.length);
          sock.receive(reply);
          String id = parseDeviceIdFromReply(reply.getData(), reply.getLength());
          if (id != null)
          {
            out.put(id, reply.getAddress().getHostAddress());
          }
        }
        catch (java.net.SocketTimeoutException ste) { break; }
        catch (Throwable t) { /* skip malformed */ }
      }
    }
    catch (Throwable t)
    {
      if (Sage.DBG) System.out.println("HDHomeRunDiscover: " + t);
    }
    finally
    {
      if (sock != null) try { sock.close(); } catch (Throwable t) {}
    }

    if (Sage.DBG && !out.isEmpty())
      System.out.println("HDHomeRunDiscover: found " + out);
    return out;
  }

  /**
   * Convenience: discover the IP for a single device ID (8-char hex,
   * case-insensitive). Returns null if not found.
   */
  public static String findIp(String hexDeviceId)
  {
    if (hexDeviceId == null) return null;
    String norm = hexDeviceId.trim().toLowerCase();
    if (norm.length() == 0) return null;
    Map<String, String> all = discover();
    return all.get(norm);
  }

  // ---------------------------------------------------------------------

  private static byte[] buildRequest(int devType, int devId)
  {
    // Body: tag(1) len(1) val(4) tag(1) len(1) val(4) = 12 bytes
    byte[] body = new byte[12];
    body[0] = 0x01; body[1] = 0x04;
    body[2] = (byte)(devType >>> 24); body[3] = (byte)(devType >>> 16);
    body[4] = (byte)(devType >>> 8);  body[5] = (byte) devType;
    body[6] = 0x02; body[7] = 0x04;
    body[8]  = (byte)(devId >>> 24); body[9]  = (byte)(devId >>> 16);
    body[10] = (byte)(devId >>> 8);  body[11] = (byte) devId;

    byte[] hdr = new byte[] { 0x00, 0x02, 0x00, (byte) body.length };

    byte[] packet = new byte[hdr.length + body.length + 4];
    System.arraycopy(hdr,  0, packet, 0,           hdr.length);
    System.arraycopy(body, 0, packet, hdr.length,  body.length);

    CRC32 crc = new CRC32();
    crc.update(packet, 0, hdr.length + body.length);
    long c = crc.getValue();
    // libhdhomerun emits CRC little-endian (it's a reflected impl).
    packet[hdr.length + body.length]     = (byte)(c);
    packet[hdr.length + body.length + 1] = (byte)(c >>> 8);
    packet[hdr.length + body.length + 2] = (byte)(c >>> 16);
    packet[hdr.length + body.length + 3] = (byte)(c >>> 24);
    return packet;
  }

  private static String parseDeviceIdFromReply(byte[] pkt, int len)
  {
    if (len < 8) return null;
    int type    = ((pkt[0] & 0xff) << 8) | (pkt[1] & 0xff);
    int bodyLen = ((pkt[2] & 0xff) << 8) | (pkt[3] & 0xff);
    if (type != 0x0003) return null;
    if (4 + bodyLen + 4 > len) return null;

    int p = 4;
    int end = 4 + bodyLen;
    while (p + 2 <= end)
    {
      int tag = pkt[p++] & 0xff;
      int tlen = pkt[p++] & 0xff;
      if (p + tlen > end) break;
      if (tag == 0x02 && tlen == 4)
      {
        long id = ((pkt[p] & 0xffL) << 24)
                | ((pkt[p+1] & 0xffL) << 16)
                | ((pkt[p+2] & 0xffL) << 8)
                |  (pkt[p+3] & 0xffL);
        return String.format("%08x", id);
      }
      p += tlen;
    }
    return null;
  }

  private static List<InetAddress> collectBroadcastAddrs()
  {
    List<InetAddress> out = new ArrayList<InetAddress>();
    try
    {
      Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
      while (en.hasMoreElements())
      {
        NetworkInterface ni = en.nextElement();
        if (!ni.isUp() || ni.isLoopback()) continue;
        for (InterfaceAddress ia : ni.getInterfaceAddresses())
        {
          InetAddress addr = ia.getAddress();
          if (addr == null || !(addr instanceof java.net.Inet4Address)) continue;
          InetAddress bcast = ia.getBroadcast();
          // Compute directed broadcast from address+prefix when:
          //  - kernel didn't set one (null), OR
          //  - returned 0.0.0.0 (happens on Linux when interface has
          //    IFA_F_NOPREFIXROUTE set — typical for static IPs assigned
          //    outside of NetworkManager).
          if (bcast == null || bcast.isAnyLocalAddress())
          {
            bcast = computeBroadcast(addr, ia.getNetworkPrefixLength());
          }
          if (bcast != null && !bcast.isAnyLocalAddress()) out.add(bcast);
        }
      }
    }
    catch (Throwable t) { /* return what we have */ }
    return out;
  }

  private static InetAddress computeBroadcast(InetAddress addr, int prefixLen)
  {
    if (prefixLen < 0 || prefixLen > 32) return null;
    byte[] ab = addr.getAddress();
    if (ab.length != 4) return null;
    int ip = ((ab[0] & 0xff) << 24) | ((ab[1] & 0xff) << 16)
           | ((ab[2] & 0xff) << 8)  |  (ab[3] & 0xff);
    int mask = prefixLen == 0 ? 0 : (0xFFFFFFFF << (32 - prefixLen));
    int bcast = (ip & mask) | (~mask);
    byte[] bb = new byte[] {
        (byte)(bcast >>> 24), (byte)(bcast >>> 16),
        (byte)(bcast >>> 8),  (byte) bcast
    };
    try { return InetAddress.getByAddress(bb); }
    catch (Throwable t) { return null; }
  }
}
