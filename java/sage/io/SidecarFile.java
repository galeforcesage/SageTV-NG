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
package sage.io;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/**
 * Base class for fixed-width binary sidecar files that live alongside recordings.
 * <p>
 * All sidecars share a common 16-byte header:
 * <pre>
 *   magic      (4 bytes) - unique per sidecar type (e.g. "FIDX", "SKIP", "SEGS")
 *   version    (2 bytes) - schema version, unsigned
 *   rowSize    (2 bytes) - bytes per row, unsigned
 *   rowCount   (4 bytes) - number of rows
 *   reserved   (4 bytes) - must be 0
 * </pre>
 * On open, the header is validated: magic must match, version must be understood,
 * rowSize must match expected, and (rowCount * rowSize + HEADER_SIZE) must equal file length.
 * Any mismatch causes the sidecar to be deleted so it can be rebuilt from the authoritative source.
 * <p>
 * Sidecar files are never authoritative — they are rebuildable caches/indexes.
 * Corruption, version mismatch, or deletion simply triggers a rebuild from the recording file.
 * <p>
 * Known sidecar suffixes (for cleanup during recording deletion):
 * <ul>
 *   <li>{@code .skip} — commercial skip range matrix</li>
 *   <li>{@code .fidx} — frame position index (future)</li>
 *   <li>{@code .seg}  — segment offset index (future)</li>
 * </ul>
 */
public class SidecarFile
{
  public static final int HEADER_SIZE = 16;

  /**
   * All known sidecar file suffixes. Used by {@link #deleteSidecars(File)} to clean up
   * when a recording is deleted. Add new suffixes here as new sidecar types are created.
   */
  public static final String[] SIDECAR_SUFFIXES = { ".skip", ".fidx", ".seg" };

  /**
   * Deletes all known sidecar files for a given recording file.
   * Called from recording deletion paths to prevent orphaned sidecars.
   *
   * @param recordingFile the recording file (e.g. foo.ts)
   */
  public static void deleteSidecars(File recordingFile)
  {
    String basePath = getBasePath(recordingFile);
    for (String suffix : SIDECAR_SUFFIXES)
    {
      File sidecar = new File(basePath + suffix);
      if (sidecar.exists())
      {
        if (sage.Sage.DBG) System.out.println("Deleting sidecar: " + sidecar);
        sidecar.delete();
      }
    }
  }

  /**
   * Returns the base path for sidecar naming: strips the recording's extension.
   * e.g. "/recordings/foo.ts" -> "/recordings/foo"
   */
  public static String getBasePath(File recordingFile)
  {
    String path = recordingFile.getAbsolutePath();
    int dot = path.lastIndexOf('.');
    return (dot > 0) ? path.substring(0, dot) : path;
  }

  /**
   * Returns the sidecar file for a given recording and suffix.
   * e.g. (foo.ts, ".skip") -> foo.skip
   */
  public static File getSidecarFile(File recordingFile, String suffix)
  {
    return new File(getBasePath(recordingFile) + suffix);
  }

  /**
   * Writes a sidecar header to the given ByteBuffer at its current position.
   */
  public static void writeHeader(ByteBuffer buf, byte[] magic, int version, int rowSize, int rowCount)
  {
    buf.order(ByteOrder.BIG_ENDIAN);
    buf.put(magic, 0, 4);
    buf.putShort((short) (version & 0xFFFF));
    buf.putShort((short) (rowSize & 0xFFFF));
    buf.putInt(rowCount);
    buf.putInt(0); // reserved
  }

  /**
   * Validates a sidecar file header. Returns the row count if valid, or -1 if invalid.
   * On invalid header, the sidecar file is deleted so it can be rebuilt.
   *
   * @param sidecarFile the sidecar file to validate
   * @param expectedMagic 4-byte magic identifier
   * @param expectedVersion expected schema version
   * @param expectedRowSize expected bytes per row
   * @return row count if valid, -1 if invalid/missing/corrupt (file is deleted on failure)
   */
  public static int validateAndGetRowCount(File sidecarFile, byte[] expectedMagic,
                                           int expectedVersion, int expectedRowSize)
  {
    if (!sidecarFile.exists())
      return -1;

    long fileLen = sidecarFile.length();
    if (fileLen < HEADER_SIZE)
    {
      if (sage.Sage.DBG) System.out.println("Sidecar too small, deleting: " + sidecarFile);
      sidecarFile.delete();
      return -1;
    }

    try (RandomAccessFile raf = new RandomAccessFile(sidecarFile, "r"))
    {
      byte[] header = new byte[HEADER_SIZE];
      raf.readFully(header);
      ByteBuffer hdr = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);

      // Validate magic
      for (int i = 0; i < 4; i++)
      {
        if (hdr.get() != expectedMagic[i])
        {
          if (sage.Sage.DBG) System.out.println("Sidecar magic mismatch, deleting: " + sidecarFile);
          raf.close();
          sidecarFile.delete();
          return -1;
        }
      }

      int version = hdr.getShort() & 0xFFFF;
      int rowSize = hdr.getShort() & 0xFFFF;
      int rowCount = hdr.getInt();
      int reserved = hdr.getInt();

      if (version != expectedVersion)
      {
        if (sage.Sage.DBG) System.out.println("Sidecar version mismatch (got " + version +
            ", expected " + expectedVersion + "), deleting: " + sidecarFile);
        raf.close();
        sidecarFile.delete();
        return -1;
      }

      if (rowSize != expectedRowSize)
      {
        if (sage.Sage.DBG) System.out.println("Sidecar rowSize mismatch (got " + rowSize +
            ", expected " + expectedRowSize + "), deleting: " + sidecarFile);
        raf.close();
        sidecarFile.delete();
        return -1;
      }

      long expectedLen = (long) rowCount * rowSize + HEADER_SIZE;
      if (expectedLen != fileLen)
      {
        if (sage.Sage.DBG) System.out.println("Sidecar length mismatch (expected " + expectedLen +
            ", actual " + fileLen + "), deleting: " + sidecarFile);
        raf.close();
        sidecarFile.delete();
        return -1;
      }

      return rowCount;
    }
    catch (IOException e)
    {
      if (sage.Sage.DBG) System.out.println("Error reading sidecar " + sidecarFile + ": " + e);
      sidecarFile.delete();
      return -1;
    }
  }

  /**
   * Atomically writes a sidecar file: write to .tmp, then rename.
   * The data ByteBuffer should be positioned at 0 with limit set to total bytes (header + rows).
   *
   * @param sidecarFile the target sidecar file
   * @param data ByteBuffer containing header + all row data, position=0, limit=total size
   */
  public static void atomicWrite(File sidecarFile, ByteBuffer data) throws IOException
  {
    File tmpFile = new File(sidecarFile.getParentFile(), sidecarFile.getName() + ".tmp");
    try (FileOutputStream fos = new FileOutputStream(tmpFile);
         FileChannel fc = fos.getChannel())
    {
      data.rewind();
      while (data.hasRemaining())
        fc.write(data);
      fc.force(true);
    }

    if (sidecarFile.exists())
      sidecarFile.delete();
    if (!tmpFile.renameTo(sidecarFile))
    {
      // Fallback: copy if rename fails (cross-filesystem edge case)
      try (InputStream in = new FileInputStream(tmpFile);
           FileOutputStream out = new FileOutputStream(sidecarFile))
      {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0)
          out.write(buf, 0, n);
        out.getFD().sync();
      }
      tmpFile.delete();
    }
  }

  /**
   * Reads the raw data portion (after header) of a validated sidecar file into a ByteBuffer.
   *
   * @param sidecarFile the sidecar file (must have been validated already)
   * @param rowCount the validated row count
   * @param rowSize bytes per row
   * @return ByteBuffer containing all row data, positioned at 0
   */
  public static ByteBuffer readData(File sidecarFile, int rowCount, int rowSize) throws IOException
  {
    int dataSize = rowCount * rowSize;
    ByteBuffer buf = ByteBuffer.allocate(dataSize).order(ByteOrder.BIG_ENDIAN);
    try (RandomAccessFile raf = new RandomAccessFile(sidecarFile, "r");
         FileChannel fc = raf.getChannel())
    {
      fc.position(HEADER_SIZE);
      while (buf.hasRemaining())
        fc.read(buf);
    }
    buf.flip();
    return buf;
  }
}
