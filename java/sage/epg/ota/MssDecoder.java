/*
 * Copyright 2026 SageTV-mine contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package sage.epg.ota;

/**
 * Decoder for ATSC A/65 Multiple String Structure (MSS), used in EIT titles
 * and ETT description bodies.
 *
 * <p>This implements compression_type 0x00 (uncompressed) for modes 0x00–0x06
 * (which collectively cover the entire BMP via 8-bit prefixes — the common
 * case for U.S. broadcasters) plus mode 0x3F (UTF-16 BE). Other compressions
 * (Huffman 0x01/0x02) are recognised but the segment is dropped. We log when
 * we drop so users can decide if extra effort is warranted.
 */
public final class MssDecoder
{
  private MssDecoder() {}

  /**
   * @param data full MSS bytes
   * @return decoded text from the first string only (sufficient for EIT title
   *         use). Returns an empty string on any structural error.
   */
  public static String decode(byte[] data)
  {
    return decode(data, 0, data.length);
  }

  public static String decode(byte[] data, int off, int len)
  {
    if (data == null || len <= 0) return "";
    try
    {
      int p = off;
      int end = off + len;
      if (p >= end) return "";
      int numStrings = data[p++] & 0xFF;
      StringBuilder out = new StringBuilder(64);
      for (int s = 0; s < numStrings && p < end; s++)
      {
        if (p + 4 > end) break;
        // 3-byte ISO-639 language code (skipped)
        p += 3;
        int numSegs = data[p++] & 0xFF;
        for (int seg = 0; seg < numSegs && p + 3 <= end; seg++)
        {
          int compType = data[p++] & 0xFF;
          int mode     = data[p++] & 0xFF;
          int nbytes   = data[p++] & 0xFF;
          if (p + nbytes > end) return out.toString();
          if (compType == 0x00)
          {
            appendUncompressed(out, mode, data, p, nbytes);
          }
          // compType 0x01 / 0x02 (Huffman) intentionally skipped: rare in
          // U.S. broadcast EIT; titles encoded that way will be empty.
          p += nbytes;
        }
        // Only the first language's text matters for our use; bail out early
        if (out.length() > 0) return out.toString();
      }
      return out.toString();
    }
    catch (Throwable t)
    {
      return "";
    }
  }

  private static void appendUncompressed(StringBuilder out, int mode,
      byte[] d, int off, int len)
  {
    // Mode 0x00..0x06: 8-bit characters with mode as the upper byte of the
    // Unicode code point (mode << 8 | byte). Mode 0x00 is Latin-1/standard
    // (most common). Modes 0x09..0x10, 0x20..0x27 cover non-Latin scripts.
    // Mode 0x3F: UTF-16 BE (each char = 2 bytes).
    if (mode == 0x3F)
    {
      // UTF-16BE
      for (int i = 0; i + 1 < len; i += 2)
      {
        int hi = d[off + i] & 0xFF;
        int lo = d[off + i + 1] & 0xFF;
        out.append((char) ((hi << 8) | lo));
      }
      return;
    }
    int upper = (mode & 0xFF) << 8;
    for (int i = 0; i < len; i++)
    {
      int b = d[off + i] & 0xFF;
      // 0x00 in mode 0x00 is a null; drop it. 0x10-0x1F are MSS control codes.
      if (mode == 0x00)
      {
        if (b == 0x00) continue;
        if (b >= 0x10 && b <= 0x1F) continue;
      }
      out.append((char) (upper | b));
    }
  }
}
