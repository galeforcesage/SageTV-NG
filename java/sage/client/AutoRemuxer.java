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
package sage.client;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Auto-remux subsystem: remuxes media files on playback failure
 * using {@code ffmpeg -i input -map 0 -c copy output}.
 *
 * Caches successful remux results per (source file + target container) so each
 * variant is only remuxed once. Respects the profile's auto_remux policy field.
 */
public class AutoRemuxer
{
  private static AutoRemuxer instance;

  // Cache key: sourceFilePath + "|" + targetContainer → remuxed file path
  private final Map<String, String> remuxCache = new ConcurrentHashMap<>();
  // Track files that failed remux so we don't retry
  private final Set<String> failedRemuxes = Collections.newSetFromMap(new ConcurrentHashMap<>());

  private int remuxAttemptCount = 0;

  private AutoRemuxer() {}

  public static synchronized AutoRemuxer getInstance()
  {
    if (instance == null)
      instance = new AutoRemuxer();
    return instance;
  }

  /**
   * Check if a remuxed version of this file already exists in cache.
   *
   * @param sourceFile the original media file
   * @param targetContainer the desired output container (e.g., "MP4", "MKV", "MPEG2-TS")
   * @return path to cached remuxed file, or null if not cached
   */
  public String getCachedRemux(File sourceFile, String targetContainer)
  {
    String key = makeKey(sourceFile, targetContainer);
    String cached = remuxCache.get(key);
    if (cached != null && new File(cached).exists())
      return cached;
    // Remove stale cache entry
    if (cached != null)
      remuxCache.remove(key);
    return null;
  }

  /**
   * Attempt to remux a file. Uses {@code ffmpeg -i input -map 0 -c copy output}.
   * Returns the path to the remuxed file on success, null on failure.
   *
   * @param sourceFile the original media file
   * @param targetContainer the desired output container
   * @param ffmpegPath path to ffmpeg binary
   * @return path to remuxed file, or null if remux failed
   */
  public String remux(File sourceFile, String targetContainer, String ffmpegPath)
  {
    String key = makeKey(sourceFile, targetContainer);

    // Check cache first
    String cached = getCachedRemux(sourceFile, targetContainer);
    if (cached != null)
    {
      if (sage.Sage.DBG) System.out.println("AutoRemuxer: Using cached remux: " + cached);
      return cached;
    }

    // Don't retry known failures
    if (failedRemuxes.contains(key))
    {
      if (sage.Sage.DBG) System.out.println("AutoRemuxer: Skipping previously failed remux for " + key);
      return null;
    }

    // Determine output extension
    String ext = containerToExtension(targetContainer);
    File outputFile = new File(getRemuxCacheDir(),
        sourceFile.getName().replaceAll("\\.[^.]+$", "") + "_remux_" + (remuxAttemptCount++) + ext);

    if (sage.Sage.DBG) System.out.println("AutoRemuxer: Remuxing " + sourceFile.getAbsolutePath() +
        " → " + outputFile.getAbsolutePath() + " (container=" + targetContainer + ")");

    try
    {
      // Build the remux command: ffmpeg -i input -map 0 -c copy output
      List<String> cmd = new ArrayList<>();
      cmd.add(ffmpegPath);
      cmd.add("-y");
      cmd.add("-i");
      cmd.add(sourceFile.getAbsolutePath());
      cmd.add("-map");
      cmd.add("0");
      cmd.add("-c");
      cmd.add("copy");
      cmd.add(outputFile.getAbsolutePath());

      ProcessBuilder pb = new ProcessBuilder(cmd);
      pb.redirectErrorStream(true);
      Process proc = pb.start();

      // Read output to prevent blocking
      BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
      StringBuilder output = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null)
      {
        output.append(line).append('\n');
        if (output.length() > 8192)
          output.delete(0, 4096); // Keep last 4KB only
      }

      int exitCode = proc.waitFor();
      if (exitCode == 0 && outputFile.exists() && outputFile.length() > 0)
      {
        if (sage.Sage.DBG) System.out.println("AutoRemuxer: Remux succeeded: " + outputFile.getAbsolutePath() +
            " (" + outputFile.length() + " bytes)");
        remuxCache.put(key, outputFile.getAbsolutePath());
        return outputFile.getAbsolutePath();
      }
      else
      {
        if (sage.Sage.DBG) System.out.println("AutoRemuxer: Remux failed (exit=" + exitCode + "): " + output.toString().substring(Math.max(0, output.length() - 500)));
        failedRemuxes.add(key);
        // Clean up failed output
        if (outputFile.exists()) outputFile.delete();
        return null;
      }
    }
    catch (Exception e)
    {
      System.out.println("AutoRemuxer: Error during remux: " + e);
      failedRemuxes.add(key);
      if (outputFile.exists()) outputFile.delete();
      return null;
    }
  }

  /**
   * Handle a playback failure: if the profile allows auto-remux, attempt it.
   *
   * @param profile the client profile
   * @param sourceFile the file that failed to play
   * @param targetContainer preferred target container
   * @param ffmpegPath path to ffmpeg binary
   * @return path to remuxed file, or null if remux not applicable or failed
   */
  public String onPlaybackFailure(ClientProfile profile, File sourceFile,
      String targetContainer, String ffmpegPath)
  {
    if (profile == null || !profile.isAutoRemuxEnabled())
    {
      if (sage.Sage.DBG) System.out.println("AutoRemuxer: Auto-remux disabled for profile " +
          (profile != null ? profile.getProfileId() : "null"));
      return null;
    }

    return remux(sourceFile, targetContainer, ffmpegPath);
  }

  /**
   * Clear the remux cache. Call during server shutdown or maintenance.
   */
  public void clearCache()
  {
    remuxCache.clear();
    failedRemuxes.clear();
    remuxAttemptCount = 0;
  }

  private String makeKey(File sourceFile, String targetContainer)
  {
    return sourceFile.getAbsolutePath() + "|" + targetContainer.toUpperCase();
  }

  private File getRemuxCacheDir()
  {
    File cacheDir = new File(System.getProperty("user.dir"), "remux_cache");
    if (!cacheDir.exists())
      cacheDir.mkdirs();
    return cacheDir;
  }

  private String containerToExtension(String container)
  {
    if (container == null) return ".ts";
    switch (container.toUpperCase())
    {
      case "MP4": return ".mp4";
      case "MKV": case "MATROSKA": return ".mkv";
      case "MPEG2-TS": case "TS": return ".ts";
      case "MPEG2-PS": case "PS": return ".mpg";
      case "AVI": return ".avi";
      default: return ".ts";
    }
  }
}
