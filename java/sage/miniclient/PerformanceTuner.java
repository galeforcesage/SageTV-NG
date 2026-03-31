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
package sage.miniclient;

/**
 * Detects GPU and system capabilities at startup and auto-tunes
 * Placeshifter performance settings when no user overrides exist.
 */
public class PerformanceTuner
{
  private static boolean initialized = false;
  private static String gpuName = "Unknown";
  private static long gpuVramMB = 0;
  private static int cpuCores = 1;
  private static long systemMemMB = 0;
  private static boolean hasDiscreteGpu = false;

  public static synchronized void initialize()
  {
    if (initialized) return;
    initialized = true;
    detectSystem();
    applyAutoTuning();
  }

  private static void detectSystem()
  {
    // CPU cores
    cpuCores = Runtime.getRuntime().availableProcessors();
    systemMemMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);
    System.out.println("PerformanceTuner: CPU cores=" + cpuCores + " JVM maxMemory=" + systemMemMB + "MB");

    // GPU detection via AWT GraphicsEnvironment
    try
    {
      java.awt.GraphicsEnvironment ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment();
      java.awt.GraphicsDevice gd = ge.getDefaultScreenDevice();
      java.awt.DisplayMode dm = gd.getDisplayMode();
      System.out.println("PerformanceTuner: Display=" + dm.getWidth() + "x" + dm.getHeight() + "@" + dm.getRefreshRate() + "Hz bitDepth=" + dm.getBitDepth());
    }
    catch (Exception e)
    {
      System.out.println("PerformanceTuner: Failed to query display: " + e);
    }

    // GPU detection via Windows WMI (if on Windows)
    if (MiniClient.WINDOWS_OS)
    {
      detectWindowsGpu();
    }

    System.out.println("PerformanceTuner: GPU=\"" + gpuName + "\" VRAM=" + gpuVramMB + "MB discrete=" + hasDiscreteGpu);
  }

  private static void detectWindowsGpu()
  {
    try
    {
      // Use PowerShell to query GPU info via WMI
      ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-Command",
          "Get-CimInstance Win32_VideoController | Select-Object -First 1 -Property Name,AdapterRAM | ForEach-Object { $_.Name + '|' + $_.AdapterRAM }");
      pb.redirectErrorStream(true);
      Process p = pb.start();
      java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
      String line = reader.readLine();
      // Give the process a reasonable timeout
      boolean exited = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
      if (!exited) {
        p.destroyForcibly();
      }
      reader.close();
      if (line != null && line.contains("|"))
      {
        String[] parts = line.split("\\|", -1);
        gpuName = parts[0].trim();
        if (parts.length > 1 && !parts[1].trim().isEmpty())
        {
          try
          {
            long adapterRam = Long.parseLong(parts[1].trim());
            gpuVramMB = adapterRam / (1024 * 1024);
          }
          catch (NumberFormatException e) {}
        }

        // Detect discrete GPU by name
        String lower = gpuName.toLowerCase();
        hasDiscreteGpu = lower.contains("nvidia") || lower.contains("geforce") ||
            lower.contains("radeon") || lower.contains("rx ") || lower.contains("rtx") ||
            lower.contains("gtx") || lower.contains("arc ");
      }
    }
    catch (Exception e)
    {
      System.out.println("PerformanceTuner: WMI GPU query failed: " + e);
    }
  }

  /**
   * Apply auto-tuned defaults for UI/rendering properties only.
   * NEVER modify MPlayer stream/cache properties here — they get persisted
   * to SageTVPlaceshifter.properties and the old SageTVPlayer.exe breaks
   * with non-default buffer sizes.
   */
  private static void applyAutoTuning()
  {
    java.util.Properties props = MiniClient.myProperties;

    // --- Image cache: scale by available GPU VRAM ---
    if (props.getProperty("image_cache_size") == null)
    {
      long cacheSize;
      if (gpuVramMB >= 2048)
        cacheSize = 128000000; // 128MB for 2GB+ VRAM GPUs
      else if (gpuVramMB >= 1024)
        cacheSize = 96000000;  // 96MB for 1GB+ VRAM
      else if (gpuVramMB >= 512)
        cacheSize = 64000000;  // 64MB for 512MB+ VRAM
      else
        cacheSize = 48000000;  // 48MB min (up from default 32MB)
      props.setProperty("image_cache_size", Long.toString(cacheSize));
      System.out.println("PerformanceTuner: image_cache_size=" + cacheSize);
    }

    // --- VRAM usage for DX9 renderer ---
    if (props.getProperty("ui/max_d3d_vram_usage") == null)
    {
      long vramUsage;
      if (gpuVramMB >= 4096)
        vramUsage = 512000000; // 512MB for 4GB+ GPUs
      else if (gpuVramMB >= 2048)
        vramUsage = 384000000; // 384MB for 2GB+ GPUs
      else if (gpuVramMB >= 1024)
        vramUsage = 256000000; // 256MB for 1GB+ GPUs
      else
        vramUsage = 150000000; // keep default for low VRAM
      props.setProperty("ui/max_d3d_vram_usage", Long.toString(vramUsage));
      System.out.println("PerformanceTuner: ui/max_d3d_vram_usage=" + vramUsage);
    }

    // --- Disk image cache ---
    if (props.getProperty("disk_image_cache_size") == null)
    {
      props.setProperty("disk_image_cache_size", "256000000");
      System.out.println("PerformanceTuner: disk_image_cache_size=256000000");
    }

    // --- Post-processing: disable CPU-heavy pp7 filter if discrete GPU present ---
    if (props.getProperty("enable_video_postprocessing") == null && hasDiscreteGpu)
    {
      props.setProperty("enable_video_postprocessing", "false");
      System.out.println("PerformanceTuner: Disabled CPU video postprocessing (GPU present)");
    }

    System.out.println("PerformanceTuner: Auto-tuning complete");
  }

  /**
   * Returns MPlayer extra args for multi-threaded video decoding.
   * Should be called when building the MPlayer command line.
   */
  public static String getMPlayerPerformanceArgs()
  {
    if (!initialized) initialize();
    StringBuilder sb = new StringBuilder();

    // Multi-threaded video decoding: use up to 4 threads (more causes diminishing returns in MPlayer)
    int decodeThreads = Math.min(cpuCores, 4);
    if (decodeThreads > 1)
    {
      sb.append(" -lavdopts threads=").append(decodeThreads);
    }

    // Skip loop filter for H.264 on weaker systems (significant CPU savings)
    if (cpuCores <= 2)
    {
      sb.append(" -lavdopts skiploopfilter=nonref");
    }

    return sb.toString();
  }

  /** Returns detected GPU name */
  public static String getGpuName() { if (!initialized) initialize(); return gpuName; }
  /** Returns detected GPU VRAM in MB */
  public static long getGpuVramMB() { if (!initialized) initialize(); return gpuVramMB; }
  /** Returns available CPU cores */
  public static int getCpuCores() { if (!initialized) initialize(); return cpuCores; }
  /** Returns whether a discrete GPU was detected */
  public static boolean hasDiscreteGpu() { if (!initialized) initialize(); return hasDiscreteGpu; }
}
