/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sage;

import static org.testng.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;

import org.testng.annotations.Test;

/**
 * Tests for the Option B circular spill primitives
 * {@link FFMPEGTranscoder#spillWriteCircular} and
 * {@link FFMPEGTranscoder#spillReadCircular} -- the wrap/segment arithmetic that
 * lets a pull-xcode read which has fallen behind the in-memory ring be served
 * from the bounded on-disk history instead of restarting ffmpeg.
 *
 * <p>These exercise the primitives against a real temp {@link FileChannel} so
 * the positional read/write and the modulo wrap are validated end to end,
 * without standing up a live transcoder.
 */
public class FFMPEGTranscoderSpillTest
{
  private static byte[] pattern(int len, int seed)
  {
    byte[] b = new byte[len];
    for (int i = 0; i < len; i++)
      b[i] = (byte) ((i * 31 + seed) & 0xFF);
    return b;
  }

  private static FileChannel tmpChannel() throws Exception
  {
    File f = File.createTempFile("sagetv_spill_test_", ".bin");
    f.deleteOnExit();
    return new RandomAccessFile(f, "rw").getChannel();
  }

  private static byte[] readBack(FileChannel ch, long cap, long offset, long canRead) throws Exception
  {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    long served = FFMPEGTranscoder.spillReadCircular(ch, cap, offset, canRead, Channels.newChannel(bos));
    assertEquals(served, canRead, "served byte count");
    return bos.toByteArray();
  }

  @Test
  public void testWriteReadNoWrap() throws Exception
  {
    FileChannel ch = tmpChannel();
    long cap = 4096;
    byte[] data = pattern(1000, 7);
    FFMPEGTranscoder.spillWriteCircular(ch, cap, data, 0, data.length, 0);
    assertEquals(readBack(ch, cap, 0, data.length), data, "no-wrap round trip");
    ch.close();
  }

  @Test
  public void testWriteReadWithOffsetNoWrap() throws Exception
  {
    FileChannel ch = tmpChannel();
    long cap = 8192;
    byte[] data = pattern(500, 3);
    // Written at a virtual position well inside the file (no wrap).
    FFMPEGTranscoder.spillWriteCircular(ch, cap, data, 0, data.length, 1234);
    assertEquals(readBack(ch, cap, 1234, data.length), data, "offset no-wrap round trip");
    ch.close();
  }

  @Test
  public void testWriteWrapsAcrossEnd() throws Exception
  {
    FileChannel ch = tmpChannel();
    long cap = 1000;
    // Start 100 bytes before the cap so a 300-byte write straddles the wrap:
    // 100 bytes at file pos [900,1000), then 200 bytes at [0,200).
    long vpos = 900;
    byte[] data = pattern(300, 11);
    FFMPEGTranscoder.spillWriteCircular(ch, cap, data, 0, data.length, vpos);
    assertEquals(readBack(ch, cap, vpos, data.length), data, "wrapped write round trip");
    ch.close();
  }

  @Test
  public void testReadSpanningWrap() throws Exception
  {
    FileChannel ch = tmpChannel();
    long cap = 512;
    // Fill two full laps worth so every physical slot holds known data, then
    // read a span that crosses the modulo boundary.
    byte[] data = pattern((int) cap * 2, 5);
    FFMPEGTranscoder.spillWriteCircular(ch, cap, data, 0, data.length, 0);
    long start = cap - 50; // read [462, 562) which wraps at 512
    byte[] expect = new byte[100];
    System.arraycopy(data, (int) start, expect, 0, 100);
    assertEquals(readBack(ch, cap, start, 100), expect, "read across wrap");
    ch.close();
  }

  @Test
  public void testCircularOverwriteReturnsNewest() throws Exception
  {
    FileChannel ch = tmpChannel();
    long cap = 1000;
    // First lap.
    FFMPEGTranscoder.spillWriteCircular(ch, cap, pattern(1000, 1), 0, 1000, 0);
    // Second lap overwrites physical [0,200) with data written at virtual 1000..1200.
    byte[] fresh = pattern(200, 99);
    FFMPEGTranscoder.spillWriteCircular(ch, cap, fresh, 0, fresh.length, 1000);
    // Reading the newest virtual range returns the fresh bytes (physical [0,200)).
    assertEquals(readBack(ch, cap, 1000, fresh.length), fresh, "newest data after overwrite");
    ch.close();
  }

  @Test
  public void testReadClampedToAvailableReturnsZero() throws Exception
  {
    FileChannel ch = tmpChannel();
    long cap = 4096;
    // canRead <= 0 must serve nothing and not throw.
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    long served = FFMPEGTranscoder.spillReadCircular(ch, cap, 100, 0, Channels.newChannel(bos));
    assertEquals(served, 0L, "zero canRead serves nothing");
    assertEquals(bos.size(), 0, "no bytes written");
    ch.close();
  }
}
