package sage;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class FastMpeg2ReaderTest
{
  private File testFile;
  private FastMpeg2Reader reader;

  @BeforeMethod
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    testFile = File.createTempFile("fast-mpeg2-reader-seek-", ".ts");
    byte[] packets = new byte[376];
    packets[0] = 0x47;
    packets[188] = 0x47;
    Files.write(testFile.toPath(), packets);
    reader = new FastMpeg2Reader(testFile);
    reader.init(false, false, false);
  }

  @AfterMethod
  public void tearDown()
  {
    if (reader != null)
      reader.close();
    if (testFile != null)
      testFile.delete();
  }

  @Test
  public void testByteSeekInvalidatesPreSeekParserTimestamp() throws Exception
  {
    setParsedTimestamp(1_247_000L, 188);
    assertTrue(reader.getLastParsedTimeMillis() > 0);

    reader.seekToPosition(188);

    assertEquals(reader.getLastParsedTimeMillis(), 0L,
        "A byte seek must not expose the pre-seek PTS as the new delivery watermark");
    assertEquals(reader.getLastParsedDTSMillis(), 0L);
    assertEquals(reader.getLastParsedTimePackBytePos(), 0L);
  }

  @Test
  public void testTimedSeekInvalidatesPreSeekParserTimestamp() throws Exception
  {
    setParsedTimestamp(1_247_000L, 188);
    assertTrue(reader.getLastParsedTimeMillis() > 0);

    reader.seek(143_000L);

    assertEquals(reader.getLastParsedTimeMillis(), 0L,
        "A timed seek must wait for a post-seek PTS before advancing delivery");
    assertEquals(reader.getLastParsedDTSMillis(), 0L);
    assertEquals(reader.getLastParsedTimePackBytePos(), 0L);
  }

  @Test
  public void testStartCodeCheckUsesParseBufferBoundary()
  {
    ByteBuffer packets = ByteBuffer.allocate(376);
    packets.put(184, (byte)0);
    packets.put(185, (byte)0);
    packets.put(186, (byte)1);
    packets.put(187, (byte)0xB3);
    packets.put(188, (byte)0);
    packets.put(189, (byte)0);
    packets.put(190, (byte)1);
    packets.put(191, (byte)0xB3);

    assertTrue(FastMpeg2Reader.hasStartCode(packets, 184, 376, 0xB3));
    assertTrue(FastMpeg2Reader.hasStartCode(packets, 188, 376, 0xB3),
        "I-frame alignment must inspect the complete parse buffer across TS packet boundaries");
    assertEquals(FastMpeg2Reader.hasStartCode(packets, 188, 191, 0xB3), false,
        "A partial start code at the parse-buffer boundary must not be read out of bounds");
  }

  private void setParsedTimestamp(long timeMillis, long bytePosition)
  {
    reader.parsedPES.pts = timeMillis * 90;
    reader.parsedPES.dts = timeMillis * 90;
    reader.parsedPES.ptsPackBytePos = bytePosition;
    reader.parsedPES.dtsPackBytePos = bytePosition;
  }
}
