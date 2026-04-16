/*
 * Copyright 2015 The SageTV Authors. All Rights Reserved.
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
import java.util.List;

/**
 * Writes and reads EDL (Edit Decision List) files for commercial skip markers.
 * EDL format: each line is "startSeconds endSeconds action" where action=0 means cut/skip.
 */
public class EdlWriter
{
  public static class Segment
  {
    public final double startSeconds;
    public final double endSeconds;
    public final int action; // 0 = cut (skip), 1 = mute

    public Segment(double startSeconds, double endSeconds, int action)
    {
      this.startSeconds = startSeconds;
      this.endSeconds = endSeconds;
      this.action = action;
    }

    public Segment(double startSeconds, double endSeconds)
    {
      this(startSeconds, endSeconds, 0);
    }
  }

  /**
   * Writes an EDL file atomically (write to temp, then rename).
   * @param recordingFile the recording file; EDL will be written next to it with .edl extension
   * @param segments list of commercial segments to write
   */
  public static void writeEdl(File recordingFile, List<Segment> segments) throws IOException
  {
    File edlFile = getEdlFile(recordingFile);
    File tempFile = new File(edlFile.getParentFile(), edlFile.getName() + ".tmp");
    try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(tempFile))))
    {
      for (Segment seg : segments)
      {
        pw.printf("%.3f\t%.3f\t%d%n", seg.startSeconds, seg.endSeconds, seg.action);
      }
    }
    if (edlFile.exists())
      edlFile.delete();
    if (!tempFile.renameTo(edlFile))
    {
      // Fallback: copy if rename fails (cross-filesystem)
      try (InputStream in = new FileInputStream(tempFile);
           OutputStream out = new FileOutputStream(edlFile))
      {
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0)
          out.write(buf, 0, n);
      }
      tempFile.delete();
    }
  }

  /**
   * Reads an EDL file and returns the list of segments.
   */
  public static java.util.ArrayList<Segment> readEdl(File recordingFile)
  {
    java.util.ArrayList<Segment> segments = new java.util.ArrayList<>();
    File edlFile = getEdlFile(recordingFile);
    if (!edlFile.exists()) return segments;
    try (BufferedReader br = new BufferedReader(new FileReader(edlFile)))
    {
      String line;
      while ((line = br.readLine()) != null)
      {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("#")) continue;
        String[] parts = line.split("\\s+");
        if (parts.length >= 3)
        {
          double start = Double.parseDouble(parts[0]);
          double end = Double.parseDouble(parts[1]);
          int action = Integer.parseInt(parts[2]);
          segments.add(new Segment(start, end, action));
        }
      }
    }
    catch (Exception e)
    {
      if (sage.Sage.DBG) System.out.println("Error reading EDL file " + edlFile + ": " + e);
    }
    return segments;
  }

  /**
   * Deletes the EDL file for the given recording.
   */
  public static boolean deleteEdl(File recordingFile)
  {
    File edlFile = getEdlFile(recordingFile);
    return edlFile.exists() && edlFile.delete();
  }

  /**
   * Returns the EDL file path for a given recording file.
   */
  public static File getEdlFile(File recordingFile)
  {
    String path = recordingFile.getAbsolutePath();
    int dot = path.lastIndexOf('.');
    if (dot > 0)
      return new File(path.substring(0, dot) + ".edl");
    else
      return new File(path + ".edl");
  }
}
