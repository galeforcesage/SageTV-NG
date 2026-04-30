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
import java.nio.file.Files;
import java.util.ArrayList;

/**
 * A single commercial detection job that runs comskip (or a custom external engine)
 * against a recording file. Supports both completed and in-progress (growing) files.
 *
 * Engine modes:
 *   "comskip"  - Erik Kaashoek's Comskip (https://github.com/erikkaashoek/Comskip)
 *                Comskip natively outputs .edl when given --output=edl.
 *                For growing files with live_tv=1, comskip handles the growing file
 *                natively via BuildCommListAsYouGo(), retrying at EOF until the file
 *                stops growing, then exits with a complete EDL.
 *   "external" - Arbitrary external binary: engine inputFile outputEdlFile [--follow]
 */
public class CommercialDetectionJob implements Runnable
{
  private final sage.MediaFile mediaFile;
  private final File recordingFile;
  private volatile boolean cancelled;
  private volatile boolean recordingActive;
  private Process runningProcess;
  private final String overrideIniPath;  // per-channel/show ini, resolved by manager
  private final boolean runSlow;         // --playnice flag
  private final boolean liveDetection;   // use comskip's native live_tv mode for growing files

  public CommercialDetectionJob(sage.MediaFile mediaFile, File recordingFile, boolean isRecording)
  {
    this(mediaFile, recordingFile, isRecording, null, false, false);
  }

  public CommercialDetectionJob(sage.MediaFile mediaFile, File recordingFile, boolean isRecording,
                                String overrideIniPath, boolean runSlow, boolean liveDetection)
  {
    this.mediaFile = mediaFile;
    this.recordingFile = recordingFile;
    this.recordingActive = isRecording;
    this.cancelled = false;
    this.overrideIniPath = overrideIniPath;
    this.runSlow = runSlow;
    this.liveDetection = liveDetection;
  }

  public void cancel()
  {
    cancelled = true;
    Process p = runningProcess;
    if (p != null && p.isAlive())
      p.destroyForcibly();
  }

  public void setRecordingActive(boolean active)
  {
    this.recordingActive = active;
  }

  public sage.MediaFile getMediaFile()
  {
    return mediaFile;
  }

  @Override
  public void run()
  {
    if (sage.Sage.DBG) System.out.println("CommercialDetectionJob starting for: " + recordingFile);
    try
    {
      // If file is still being recorded:
      // - liveDetection=true: use comskip's native live_tv mode. Comskip processes the growing
      //   file via BuildCommListAsYouGo(), retries at EOF (live_tv_retries), and exits naturally
      //   when the file stops growing — producing a complete EDL in a single run.
      // - liveDetection=false: wait until recording finishes, then run once on the complete file.
      if (recordingActive && !liveDetection)
      {
        if (sage.Sage.DBG) System.out.println("CommercialDetectionJob: Waiting for recording to finish for " + recordingFile.getName());
        while (recordingActive && !cancelled)
        {
          try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
        }
        if (cancelled) return;
        // Small delay after recording stops to let file finalize
        try { Thread.sleep(sage.Sage.getInt("commercial_detection/post_recording_delay_ms", 5000)); }
        catch (InterruptedException e) { return; }
      }
      else if (recordingActive && liveDetection)
      {
        if (sage.Sage.DBG) System.out.println("CommercialDetectionJob: Using comskip native live_tv mode for growing file " + recordingFile.getName());
        // Wait for the recording file to exist and have some data before launching comskip.
        // The recording thread may not have created/written the file yet when this job starts.
        int waitMs = sage.Sage.getInt("commercial_detection/live_tv_file_wait_ms", 30000);
        long deadline = System.currentTimeMillis() + waitMs;
        while (!cancelled && System.currentTimeMillis() < deadline)
        {
          if (recordingFile.exists() && recordingFile.length() > 0)
          {
            if (sage.Sage.DBG) System.out.println("CommercialDetectionJob: Recording file ready (" + recordingFile.length() + " bytes)");
            break;
          }
          try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
        }
        if (cancelled) return;
        if (!recordingFile.exists() || recordingFile.length() == 0)
        {
          if (sage.Sage.DBG) System.out.println("CommercialDetectionJob: Recording file not ready after " + waitMs + "ms, aborting");
          return;
        }
      }

      if (cancelled) return;

      String engine = sage.Sage.get("commercial_detection/engine", "comskip");
      if ("comskip".equals(engine))
      {
        runComskip();
      }
      else
      {
        runExternalEngine();
      }
    }
    catch (Exception e)
    {
      if (sage.Sage.DBG) System.out.println("CommercialDetectionJob error for " + recordingFile + ": " + e);
      e.printStackTrace();
    }
    finally
    {
      CommercialDetectionManager.getInstance().jobCompleted(mediaFile);
      if (sage.Sage.DBG) System.out.println("CommercialDetectionJob finished for: " + recordingFile);
    }
  }

  /**
   * Run Erik Kaashoek's Comskip against the recording file.
   * Comskip natively writes .edl when invoked with --output=edl.
   * Command: comskip [--ini=comskip.ini] --output=edl --output-path=<dir> <inputFile>
   */
  private void runComskip() throws Exception
  {
    String comskipPath = sage.Sage.get("commercial_detection/comskip_path", "/opt/sagetv/server/comskip");
    File comskipBin = new File(comskipPath);
    if (!comskipBin.exists())
    {
      if (sage.Sage.DBG) System.out.println("CommercialDetectionJob: comskip binary not found at: " + comskipPath);
      return;
    }

    // Determine output directory (same as recording file)
    File outputDir = recordingFile.getParentFile();

    ArrayList<String> cmd = new ArrayList<>();
    cmd.add(comskipPath);

    // Run slow / playnice (from tmiranda CommercialDetector pattern)
    if (runSlow)
      cmd.add("--playnice");

    // Per-channel/show ini (resolved by CommercialDetectionManager using tmiranda's lookup pattern:
    // ShowName.ini -> ChannelName.ini -> configured default -> comskip binary sibling)
    String iniPath = overrideIniPath;
    if (iniPath == null || iniPath.isEmpty())
    {
      iniPath = sage.Sage.get("commercial_detection/comskip_ini", "");
      if (iniPath.isEmpty())
      {
        File defaultIni = new File(comskipBin.getParentFile(), "comskip.ini");
        if (defaultIni.exists())
          iniPath = defaultIni.getAbsolutePath();
      }
    }
    // For live detection, create a temp ini with live_tv settings appended.
    // Comskip's native live_tv mode (BuildCommListAsYouGo) processes the growing
    // file in real-time, retrying at EOF until the file stops growing.
    File tempLiveIni = null;
    if (!iniPath.isEmpty())
    {
      File iniFile = new File(iniPath);
      if (iniFile.exists())
      {
        if (liveDetection)
        {
          try
          {
            int retries = sage.Sage.getInt("commercial_detection/live_tv_retries", 120);
            tempLiveIni = File.createTempFile("comskip_live_", ".ini", recordingFile.getParentFile());
            String baseIni = new String(Files.readAllBytes(iniFile.toPath()), "UTF-8");
            try (FileWriter fw = new FileWriter(tempLiveIni))
            {
              fw.write(baseIni);
              if (!baseIni.endsWith("\n")) fw.write("\n");
              fw.write("\n[Live TV]\n");
              fw.write("live_tv=1\n");
              fw.write("live_tv_retries=" + retries + "\n");
            }
            cmd.add("--ini=" + tempLiveIni.getAbsolutePath());
            if (sage.Sage.DBG) System.out.println("CommercialDetectionJob: Created live_tv ini with " + retries + " retries");
          }
          catch (IOException e)
          {
            if (sage.Sage.DBG) System.out.println("CommercialDetectionJob: Failed to create live ini, falling back: " + e);
            cmd.add("--ini=" + iniPath);
            tempLiveIni = null;
          }
        }
        else
        {
          cmd.add("--ini=" + iniPath);
        }
      }
      else if (sage.Sage.DBG)
        System.out.println("CommercialDetectionJob: comskip.ini not found at " + iniPath + ", using defaults");
    }

    // Output folder (comskip writes files next to input by default; redirect to same dir)
    cmd.add("--output=" + outputDir.getAbsolutePath());

    // Input file
    cmd.add(recordingFile.getAbsolutePath());

    if (sage.Sage.DBG) System.out.println("CommercialDetectionJob launching comskip: " + cmd);

    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.redirectErrorStream(true);
    // Set working directory to output dir so any extra comskip output lands there
    pb.directory(outputDir);

    runningProcess = pb.start();
    Process proc = runningProcess;

    // Drain stdout/stderr to prevent pipe blocking
    Thread outputReader = new Thread(() -> {
      try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream())))
      {
        String line;
        while ((line = br.readLine()) != null)
        {
          if (sage.Sage.DBG) System.out.println("comskip: " + line);
        }
      }
      catch (IOException e) { /* ignore */ }
    }, "CommercialDetect-comskip-" + mediaFile.getID());
    outputReader.setDaemon(true);
    outputReader.start();

    // Wait for comskip to finish or be cancelled
    while (proc.isAlive() && !cancelled)
    {
      try { Thread.sleep(500); } catch (InterruptedException e) { break; }
    }

    if (cancelled && proc.isAlive())
    {
      proc.destroyForcibly();
      if (sage.Sage.DBG) System.out.println("CommercialDetectionJob: comskip cancelled");
      return;
    }

    int exitCode = proc.waitFor();
    runningProcess = null;

    if (sage.Sage.DBG) System.out.println("CommercialDetectionJob: comskip exited with code " + exitCode);

    // Comskip exit codes: 0 = commercials found, 1 = no commercials, >=2 = error
    if (exitCode >= 2)
    {
      if (sage.Sage.DBG) System.out.println("CommercialDetectionJob: comskip reported an error (exit " + exitCode + ")");
    }

    // Comskip writes the EDL file using the same base name as the input file.
    // Verify it exists where we expect it.
    File edlFile = EdlWriter.getEdlFile(recordingFile);
    if (edlFile.exists())
    {
      java.util.ArrayList<EdlWriter.Segment> segments = EdlWriter.readEdl(recordingFile);
      if (sage.Sage.DBG) System.out.println("CommercialDetectionJob: comskip produced " +
          segments.size() + " segment(s) in " + edlFile.getName());
    }
    else
    {
      // Comskip may have put it somewhere else or used a different naming convention
      // Look for it in the output directory
      String baseName = recordingFile.getName();
      int dot = baseName.lastIndexOf('.');
      if (dot > 0) baseName = baseName.substring(0, dot);
      File altEdl = new File(outputDir, baseName + ".edl");
      if (altEdl.exists() && !altEdl.equals(edlFile))
      {
        // Move to canonical location
        if (!altEdl.renameTo(edlFile))
        {
          // Copy fallback
          copyFile(altEdl, edlFile);
          altEdl.delete();
        }
        if (sage.Sage.DBG) System.out.println("CommercialDetectionJob: Moved comskip EDL to " + edlFile);
      }
      else if (exitCode == 0)
      {
        if (sage.Sage.DBG) System.out.println("CommercialDetectionJob: comskip said commercials found but no EDL at " + edlFile);
      }
    }

    // Clean up extra comskip output files we don't need (logo, txt, etc.)
    cleanupComskipExtras(outputDir, recordingFile);

    // Clean up temp live ini file if we created one
    if (tempLiveIni != null && tempLiveIni.exists())
      tempLiveIni.delete();
  }

  /**
   * Run an arbitrary external engine binary.
   * Command: engine inputFile outputEdlFile
   */
  private void runExternalEngine() throws Exception
  {
    String enginePath = sage.Sage.get("commercial_detection/external_engine_path", "");
    if (enginePath.isEmpty())
    {
      if (sage.Sage.DBG) System.out.println("CommercialDetectionJob: No external engine path configured");
      return;
    }
    File engineFile = new File(enginePath);
    if (!engineFile.exists())
    {
      if (sage.Sage.DBG) System.out.println("CommercialDetectionJob: External engine not found: " + enginePath);
      return;
    }

    File edlFile = EdlWriter.getEdlFile(recordingFile);

    // Use template-based args: live template if recording is active + live detection, else recorded template
    String argsTemplate;
    if (recordingActive && liveDetection)
      argsTemplate = sage.Sage.get("commercial_detection/external_live_args", "{input} {output}");
    else
      argsTemplate = sage.Sage.get("commercial_detection/external_recorded_args", "{input} {output}");

    // Resolve per-channel/show INI for {ini} substitution
    String iniForExt = (overrideIniPath != null) ? overrideIniPath : sage.Sage.get("commercial_detection/comskip_ini", "");

    String expandedArgs = argsTemplate
        .replace("{input}", recordingFile.getAbsolutePath())
        .replace("{output}", edlFile.getAbsolutePath())
        .replace("{outputdir}", recordingFile.getParentFile().getAbsolutePath())
        .replace("{ini}", iniForExt != null ? iniForExt : "");

    ArrayList<String> cmd = new ArrayList<>();
    cmd.add(enginePath);
    // Tokenize expanded args respecting quoted strings
    for (String arg : tokenizeArgs(expandedArgs))
      cmd.add(arg);

    if (sage.Sage.DBG) System.out.println("CommercialDetectionJob launching external: " + cmd);

    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.redirectErrorStream(true);

    runningProcess = pb.start();
    Process proc = runningProcess;

    Thread outputReader = new Thread(() -> {
      try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream())))
      {
        String line;
        while ((line = br.readLine()) != null)
        {
          if (sage.Sage.DBG) System.out.println("CommercialDetect[ext]: " + line);
        }
      }
      catch (IOException e) { /* ignore */ }
    }, "CommercialDetect-ext-" + mediaFile.getID());
    outputReader.setDaemon(true);
    outputReader.start();

    while (proc.isAlive() && !cancelled)
    {
      try { Thread.sleep(500); } catch (InterruptedException e) { break; }
    }

    if (cancelled && proc.isAlive())
    {
      proc.destroyForcibly();
      if (sage.Sage.DBG) System.out.println("CommercialDetectionJob: External process cancelled");
    }
    else
    {
      int exitCode = proc.waitFor();
      runningProcess = null;
      if (sage.Sage.DBG) System.out.println("CommercialDetectionJob: External process exited with code " + exitCode);
    }
  }

  /**
   * Tokenize a command-line args string, respecting double-quoted strings.
   */
  private static java.util.List<String> tokenizeArgs(String args)
  {
    java.util.List<String> tokens = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inQuotes = false;
    for (int i = 0; i < args.length(); i++)
    {
      char c = args.charAt(i);
      if (c == '"')
      {
        inQuotes = !inQuotes;
      }
      else if (c == ' ' && !inQuotes)
      {
        if (current.length() > 0)
        {
          tokens.add(current.toString());
          current.setLength(0);
        }
      }
      else
      {
        current.append(c);
      }
    }
    if (current.length() > 0)
      tokens.add(current.toString());
    return tokens;
  }

  /**
   * Remove extra comskip output files (logo, txt, log) that we don't want cluttering the recording dir.
   */
  private void cleanupComskipExtras(File dir, File recFile)
  {
    String baseName = recFile.getName();
    int dot = baseName.lastIndexOf('.');
    if (dot > 0) baseName = baseName.substring(0, dot);

    String[] extraExts = { ".logo.txt", ".txt", ".log", ".live", ".incommercial" };
    for (String ext : extraExts)
    {
      File extra = new File(dir, baseName + ext);
      if (extra.exists())
      {
        extra.delete();
        if (sage.Sage.DBG) System.out.println("CommercialDetectionJob: Cleaned up " + extra.getName());
      }
    }
  }

  private static void copyFile(File src, File dst) throws IOException
  {
    try (InputStream in = new FileInputStream(src);
         OutputStream out = new FileOutputStream(dst))
    {
      byte[] buf = new byte[4096];
      int n;
      while ((n = in.read(buf)) > 0)
        out.write(buf, 0, n);
    }
  }
}
