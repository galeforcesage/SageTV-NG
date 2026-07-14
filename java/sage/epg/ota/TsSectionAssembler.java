/*
 * Copyright 2026 SageTV-NG contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package sage.epg.ota;

import java.util.Arrays;

/**
 * Reassembles MPEG-2 transport-stream sections from a raw TS byte stream
 * for a single PID. ATSC PSIP sections (MGT, EIT, ETT, etc.) routinely span
 * multiple TS packets; this class consumes 188-byte TS packets one at a time
 * and yields complete, CRC32-verified sections.
 *
 * <p>Caller drives via {@link #consume(byte[], int)} and polls
 * {@link #pollSection()} until it returns {@code null}.
 *
 * <p><b>Not thread-safe.</b> One instance per PID per scanner thread.
 */
public final class TsSectionAssembler
{
  private static final int TS_PKT_LEN = 188;
  private static final byte TS_SYNC   = 0x47;
  /** Cap per-section length (MGT/EIT can reach ~4 KB in practice; spec max is 4096). */
  private static final int MAX_SECTION_LEN = 4096;

  private final int pid;
  private final java.util.ArrayDeque<byte[]> ready = new java.util.ArrayDeque<>();

  /** Working buffer for the section currently being assembled. */
  private byte[] secBuf = new byte[MAX_SECTION_LEN];
  /** Bytes written into secBuf so far. */
  private int    secLen = 0;
  /** Total section length we expect (from header); 0 = not yet known. */
  private int    secTarget = 0;
  /** True once we have seen the first PUSI for this PID (otherwise we drop bytes). */
  private boolean started = false;

  public TsSectionAssembler(int pid)
  {
    this.pid = pid;
  }

  public int getPid() { return pid; }

  /**
   * Feed one or more raw TS packets. Length must be a multiple of 188.
   * Packets for other PIDs are silently skipped.
   */
  public void consume(byte[] data, int len)
  {
    if (len < TS_PKT_LEN) return;
    // Align on a sync byte
    int p = 0;
    while (p + TS_PKT_LEN <= len)
    {
      if (data[p] != TS_SYNC)
      {
        p++;
        continue;
      }
      handlePacket(data, p);
      p += TS_PKT_LEN;
    }
  }

  private void handlePacket(byte[] d, int o)
  {
    int packetPid = ((d[o + 1] & 0x1F) << 8) | (d[o + 2] & 0xFF);
    if (packetPid != pid) return;

    boolean pusi = (d[o + 1] & 0x40) != 0;
    int afc = (d[o + 3] >> 4) & 0x03;
    int payloadStart = 4;
    if ((afc & 0x02) != 0)
    {
      int adaptLen = d[o + 4] & 0xFF;
      payloadStart = 5 + adaptLen;
    }
    if (payloadStart >= TS_PKT_LEN) return;
    if ((afc & 0x01) == 0) return; // no payload

    int payOff = o + payloadStart;
    int payLen = TS_PKT_LEN - payloadStart;

    if (pusi)
    {
      // First byte of payload is pointer_field: bytes before the new section
      // belong to the previous one and should complete it.
      int ptr = d[payOff] & 0xFF;
      int afterPtr = payOff + 1;
      int remain = payLen - 1;

      // First, finish any in-flight section with the bytes before ptr
      if (started && ptr > 0 && secLen > 0)
      {
        int take = Math.min(ptr, remain);
        appendToSection(d, afterPtr, take);
        finishIfComplete();
        afterPtr += take;
        remain   -= take;
      }
      // Reset and start the new section
      started = true;
      secLen = 0;
      secTarget = 0;
      // Skip any leftover pointer-field bytes (defensive)
      if (ptr > 0)
      {
        // pointer was already consumed above (or didn't fit)
        int skipped = Math.min(ptr - (payLen - 1 - remain), Math.max(0, remain));
        afterPtr += skipped;
        remain   -= skipped;
      }
      if (remain > 0)
      {
        appendToSection(d, afterPtr, remain);
        finishIfComplete();
      }
    }
    else
    {
      // Continuation of the current section
      if (!started || secLen == 0) return;
      appendToSection(d, payOff, payLen);
      finishIfComplete();
    }
  }

  private void appendToSection(byte[] src, int srcOff, int len)
  {
    if (len <= 0) return;
    if (secLen + len > secBuf.length)
    {
      len = secBuf.length - secLen;
      if (len <= 0) return;
    }
    System.arraycopy(src, srcOff, secBuf, secLen, len);
    secLen += len;
    if (secTarget == 0 && secLen >= 3)
    {
      int sectionLength = ((secBuf[1] & 0x0F) << 8) | (secBuf[2] & 0xFF);
      secTarget = sectionLength + 3; // 3 bytes header + body
      if (secTarget > MAX_SECTION_LEN)
      {
        // Malformed; reset
        secLen = 0;
        secTarget = 0;
        started = false;
      }
    }
  }

  private void finishIfComplete()
  {
    if (secTarget == 0 || secLen < secTarget) return;
    byte[] out = Arrays.copyOf(secBuf, secTarget);
    if (verifyCrc32(out))
      ready.addLast(out);
    secLen = 0;
    secTarget = 0;
  }

  /**
   * @return next complete section, or {@code null} if none ready.
   */
  public byte[] pollSection()
  {
    return ready.pollFirst();
  }

  // ---------------------------------------------------------------------
  // MPEG-2 CRC-32 (poly 0x04C11DB7, init 0xFFFFFFFF, no reflect, no xor-out)
  // ---------------------------------------------------------------------
  private static final int[] CRC_TABLE = buildCrcTable();

  private static int[] buildCrcTable()
  {
    int[] t = new int[256];
    for (int i = 0; i < 256; i++)
    {
      int c = i << 24;
      for (int j = 0; j < 8; j++)
        c = (c << 1) ^ ((c < 0) ? 0x04C11DB7 : 0);
      t[i] = c;
    }
    return t;
  }

  /**
   * Verify the trailing 4-byte MPEG-2 CRC32 of an ATSC PSIP section.
   * Returns true if the computed CRC over the entire section is zero.
   */
  public static boolean verifyCrc32(byte[] section)
  {
    if (section.length < 4) return false;
    int crc = 0xFFFFFFFF;
    for (byte b : section)
      crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ (b & 0xFF)) & 0xFF];
    return crc == 0;
  }
}
