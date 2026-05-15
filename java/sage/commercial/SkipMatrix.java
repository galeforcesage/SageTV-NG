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
package sage.commercial;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import sage.io.SidecarFile;

/**
 * Binary sidecar matrix for commercial skip ranges (.skip files).
 * <p>
 * Replaces repeated text parsing of .edl files with a fixed-width binary structure.
 * The matrix is indexed by start time (sorted) for O(log n) "is this position in a commercial?" lookups.
 * <p>
 * Row layout (16 bytes per row, fixed-width):
 * <pre>
 *   startMs      (8 bytes, long)  - commercial start time in milliseconds
 *   durationMs   (4 bytes, int)   - commercial duration in milliseconds
 *   confidence   (1 byte)         - detection confidence 0-100
 *   kind         (1 byte)         - 0=commercial, 1=promo, 2=chapter
 *   reserved     (2 bytes)        - must be 0
 * </pre>
 * <p>
 * The matrix is a rebuildable sidecar — if missing, stale, or corrupt, it is rebuilt
 * from the authoritative .edl file. Deletion and rebuild are safe operations.
 */
public class SkipMatrix
{
  public static final String SUFFIX = ".skip";
  public static final byte[] MAGIC = { 'S', 'K', 'I', 'P' };
  public static final int VERSION = 1;
  public static final int ROW_SIZE = 16;

  public static final byte KIND_COMMERCIAL = 0;
  public static final byte KIND_PROMO = 1;
  public static final byte KIND_CHAPTER = 2;

  // Struct-of-arrays for cache-friendly access
  private final long[] startMs;
  private final int[] durationMs;
  private final byte[] confidence;
  private final byte[] kind;
  private final int length;

  private SkipMatrix(long[] startMs, int[] durationMs, byte[] confidence, byte[] kind, int length)
  {
    this.startMs = startMs;
    this.durationMs = durationMs;
    this.confidence = confidence;
    this.kind = kind;
    this.length = length;
  }

  /**
   * Loads or builds the skip matrix for a recording.
   * <p>
   * Priority: .skip sidecar (fast) -> .edl file (parse + persist as .skip) -> empty matrix.
   *
   * @param recordingFile the recording file
   * @return the skip matrix (never null; may be empty)
   */
  public static SkipMatrix load(File recordingFile)
  {
    File skipFile = SidecarFile.getSidecarFile(recordingFile, SUFFIX);

    // Try loading existing sidecar
    int rowCount = SidecarFile.validateAndGetRowCount(skipFile, MAGIC, VERSION, ROW_SIZE);
    if (rowCount >= 0)
    {
      try
      {
        return readFromSidecar(skipFile, rowCount);
      }
      catch (IOException e)
      {
        if (sage.Sage.DBG) System.out.println("Error reading skip sidecar, rebuilding: " + e);
        skipFile.delete();
      }
    }

    // Rebuild from EDL
    ArrayList<EdlWriter.Segment> edlSegments = EdlWriter.readEdl(recordingFile);
    if (edlSegments.isEmpty())
      return empty();

    SkipMatrix matrix = fromEdlSegments(edlSegments);
    // Persist as sidecar for next time
    try
    {
      matrix.writeSidecar(recordingFile);
    }
    catch (IOException e)
    {
      if (sage.Sage.DBG) System.out.println("Failed to write skip sidecar: " + e);
    }
    return matrix;
  }

  /**
   * Returns true if the given playback position (in milliseconds) falls within a commercial segment.
   * Uses binary search on the sorted startMs array — O(log n).
   */
  public boolean isInCommercial(long positionMs)
  {
    if (length == 0) return false;
    int idx = Arrays.binarySearch(startMs, 0, length, positionMs);
    if (idx >= 0)
      return true; // exact match on a start time
    // insertionPoint = -(idx + 1) => the segment just before this position
    int insertionPoint = -(idx + 1);
    if (insertionPoint == 0)
      return false; // before the first segment
    int prev = insertionPoint - 1;
    return positionMs < startMs[prev] + (durationMs[prev] & 0xFFFFFFFFL);
  }

  /**
   * Returns the end time (ms) of the commercial segment containing the given position,
   * or -1 if the position is not in a commercial.
   */
  public long getCommercialEnd(long positionMs)
  {
    if (length == 0) return -1;
    int idx = Arrays.binarySearch(startMs, 0, length, positionMs);
    if (idx >= 0)
      return startMs[idx] + (durationMs[idx] & 0xFFFFFFFFL);
    int insertionPoint = -(idx + 1);
    if (insertionPoint == 0) return -1;
    int prev = insertionPoint - 1;
    long endMs = startMs[prev] + (durationMs[prev] & 0xFFFFFFFFL);
    return (positionMs < endMs) ? endMs : -1;
  }

  /**
   * Returns the start time (ms) of the commercial segment containing the given position,
   * or -1 if the position is not in a commercial.
   */
  public long getCommercialStart(long positionMs)
  {
    if (length == 0) return -1;
    int idx = Arrays.binarySearch(startMs, 0, length, positionMs);
    if (idx >= 0)
      return startMs[idx];
    int insertionPoint = -(idx + 1);
    if (insertionPoint == 0) return -1;
    int prev = insertionPoint - 1;
    long endMs = startMs[prev] + (durationMs[prev] & 0xFFFFFFFFL);
    return (positionMs < endMs) ? startMs[prev] : -1;
  }

  /**
   * Returns the number of commercial segments.
   */
  public int getSegmentCount()
  {
    return length;
  }

  /**
   * Returns the start time in milliseconds of the given segment index.
   */
  public long getSegmentStartMs(int index)
  {
    return startMs[index];
  }

  /**
   * Returns the end time in milliseconds of the given segment index.
   */
  public long getSegmentEndMs(int index)
  {
    return startMs[index] + (durationMs[index] & 0xFFFFFFFFL);
  }

  /**
   * Returns the duration in milliseconds of the given segment index.
   */
  public long getSegmentDurationMs(int index)
  {
    return durationMs[index] & 0xFFFFFFFFL;
  }

  /**
   * Returns the confidence (0-100) of the given segment index.
   */
  public int getSegmentConfidence(int index)
  {
    return confidence[index] & 0xFF;
  }

  /**
   * Returns the next commercial boundary (start or end of any segment) after the given position,
   * or -1 if no boundary exists after this position. Uses binary search for efficiency.
   *
   * @param positionMs file-relative position in ms
   * @return next boundary in file-relative ms, or -1
   */
  public long getNextBoundary(long positionMs)
  {
    if (length == 0) return -1;
    long nearest = Long.MAX_VALUE;
    // Find the first segment whose start is after positionMs
    int idx = Arrays.binarySearch(startMs, 0, length, positionMs);
    int searchFrom = (idx >= 0) ? idx : -(idx + 1);
    // Check segment starts from searchFrom onward
    if (searchFrom < length && startMs[searchFrom] > positionMs)
      nearest = startMs[searchFrom];
    // Check segment ends — the segment just before searchFrom might end after positionMs
    int endCheck = (searchFrom > 0) ? searchFrom - 1 : 0;
    for (int i = endCheck; i <= searchFrom && i < length; i++)
    {
      long endMs = startMs[i] + (durationMs[i] & 0xFFFFFFFFL);
      if (endMs > positionMs && endMs < nearest)
        nearest = endMs;
    }
    return nearest == Long.MAX_VALUE ? -1 : nearest;
  }

  /**
   * Returns the previous commercial boundary (start or end of any segment) before the given position,
   * or -1 if no boundary exists before this position.
   *
   * @param positionMs file-relative position in ms
   * @return previous boundary in file-relative ms, or -1
   */
  public long getPreviousBoundary(long positionMs)
  {
    if (length == 0) return -1;
    long nearest = -1;
    // Find insertion point
    int idx = Arrays.binarySearch(startMs, 0, length, positionMs);
    int searchUpTo = (idx >= 0) ? idx : -(idx + 1);
    // Check segment starts before positionMs
    for (int i = searchUpTo - 1; i >= 0; i--)
    {
      if (startMs[i] < positionMs)
      {
        nearest = startMs[i];
        break;
      }
    }
    // Check segment ends before positionMs
    for (int i = searchUpTo; i >= 0 && i < length; i--)
    {
      long endMs = startMs[i] + (durationMs[i] & 0xFFFFFFFFL);
      if (endMs < positionMs && endMs > nearest)
        nearest = endMs;
      if (i == 0) break;
    }
    return nearest;
  }

  /**
   * Converts EDL segments (seconds-based, text-parsed) into a SkipMatrix.
   */
  public static SkipMatrix fromEdlSegments(ArrayList<EdlWriter.Segment> segments)
  {
    int n = segments.size();
    long[] starts = new long[n];
    int[] durations = new int[n];
    byte[] conf = new byte[n];
    byte[] kinds = new byte[n];

    for (int i = 0; i < n; i++)
    {
      EdlWriter.Segment seg = segments.get(i);
      starts[i] = Math.round(seg.startSeconds * 1000.0);
      long endMs = Math.round(seg.endSeconds * 1000.0);
      durations[i] = (int) (endMs - starts[i]);
      conf[i] = (byte) 100; // EDL doesn't carry confidence; assume 100
      kinds[i] = (seg.action == 0) ? KIND_COMMERCIAL : KIND_PROMO;
    }

    // Sort by start time (EDL should already be sorted, but be safe)
    sortByStartMs(starts, durations, conf, kinds, n);

    return new SkipMatrix(starts, durations, conf, kinds, n);
  }

  /**
   * Creates an empty skip matrix.
   */
  public static SkipMatrix empty()
  {
    return new SkipMatrix(new long[0], new int[0], new byte[0], new byte[0], 0);
  }

  /**
   * Writes this matrix as a .skip sidecar file.
   */
  public void writeSidecar(File recordingFile) throws IOException
  {
    File skipFile = SidecarFile.getSidecarFile(recordingFile, SUFFIX);
    int dataSize = length * ROW_SIZE;
    ByteBuffer buf = ByteBuffer.allocate(SidecarFile.HEADER_SIZE + dataSize).order(ByteOrder.BIG_ENDIAN);

    SidecarFile.writeHeader(buf, MAGIC, VERSION, ROW_SIZE, length);

    for (int i = 0; i < length; i++)
    {
      buf.putLong(startMs[i]);
      buf.putInt(durationMs[i]);
      buf.put(confidence[i]);
      buf.put(kind[i]);
      buf.putShort((short) 0); // reserved
    }

    buf.flip();
    SidecarFile.atomicWrite(skipFile, buf);
  }

  private static SkipMatrix readFromSidecar(File skipFile, int rowCount) throws IOException
  {
    ByteBuffer data = SidecarFile.readData(skipFile, rowCount, ROW_SIZE);

    long[] starts = new long[rowCount];
    int[] durations = new int[rowCount];
    byte[] conf = new byte[rowCount];
    byte[] kinds = new byte[rowCount];

    for (int i = 0; i < rowCount; i++)
    {
      starts[i] = data.getLong();
      durations[i] = data.getInt();
      conf[i] = data.get();
      kinds[i] = data.get();
      data.getShort(); // skip reserved
    }

    return new SkipMatrix(starts, durations, conf, kinds, rowCount);
  }

  /**
   * Simple insertion sort by startMs — segments are nearly always already sorted from EDL.
   */
  private static void sortByStartMs(long[] starts, int[] durations, byte[] conf, byte[] kinds, int n)
  {
    for (int i = 1; i < n; i++)
    {
      long keyStart = starts[i];
      int keyDur = durations[i];
      byte keyConf = conf[i];
      byte keyKind = kinds[i];
      int j = i - 1;
      while (j >= 0 && starts[j] > keyStart)
      {
        starts[j + 1] = starts[j];
        durations[j + 1] = durations[j];
        conf[j + 1] = conf[j];
        kinds[j + 1] = kinds[j];
        j--;
      }
      starts[j + 1] = keyStart;
      durations[j + 1] = keyDur;
      conf[j + 1] = keyConf;
      kinds[j + 1] = keyKind;
    }
  }
}
