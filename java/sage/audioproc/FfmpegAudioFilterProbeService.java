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
package sage.audioproc;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Probes an ffmpeg binary's {@code -filters} listing once (on startup / on
 * ffmpeg-path change) and caches the result, keyed by binary path + file
 * modification time so a swapped/upgraded binary is re-probed automatically
 * without needing an explicit cache-invalidation call.
 *
 * <p>Only the specific filters the v1 audio-EQ feature needs are recorded:
 * {@code equalizer}, {@code anequalizer}, {@code volume}, {@code aresample},
 * {@code acompressor}, {@code loudnorm}, {@code dynaudnorm}, {@code alimiter}.
 */
public final class FfmpegAudioFilterProbeService
{
  private static final Set<String> FILTERS_OF_INTEREST = new HashSet<String>(java.util.Arrays.asList(
      "equalizer", "anequalizer", "volume", "aresample", "acompressor", "loudnorm", "dynaudnorm", "alimiter"));

  // e.g. " T.C acompressor        A->A       Audio compressor."
  //      " ..  aformat             A->A       Convert the input audio ..."
  private static final Pattern FILTER_LINE_PATTERN =
      Pattern.compile("^\\s*[T.][S.][C.]\\s+(\\S+)\\s+\\S*->\\S*\\s");

  private static final Object LOCK = new Object();
  private static final Map<String, AudioFilterCapabilities> CACHE = new HashMap<String, AudioFilterCapabilities>();

  private FfmpegAudioFilterProbeService()
  {
  }

  /**
   * Returns the (possibly cached) probe result for {@code ffmpegPath}. Never
   * throws -- a missing binary or exec failure yields {@link
   * AudioFilterCapabilities#unavailable(String)}.
   */
  public static AudioFilterCapabilities probe(String ffmpegPath)
  {
    if (ffmpegPath == null || ffmpegPath.trim().length() == 0)
      return AudioFilterCapabilities.unavailable(ffmpegPath);

    File f = new File(ffmpegPath);
    long mtime = f.exists() ? f.lastModified() : -1L;
    String cacheKey = ffmpegPath + "|" + mtime;

    synchronized (LOCK)
    {
      AudioFilterCapabilities cached = CACHE.get(cacheKey);
      if (cached != null)
        return cached;
    }

    AudioFilterCapabilities result = probeUncached(ffmpegPath);

    synchronized (LOCK)
    {
      CACHE.put(cacheKey, result);
    }
    return result;
  }

  /** Clears the probe cache; test-only (also useful if an operator forces a re-probe live). */
  public static void clearCache()
  {
    synchronized (LOCK)
    {
      CACHE.clear();
    }
  }

  private static AudioFilterCapabilities probeUncached(String ffmpegPath)
  {
    List<String> lines = runFfmpegFiltersCommand(ffmpegPath);
    if (lines == null)
      return AudioFilterCapabilities.unavailable(ffmpegPath);
    return buildFromFilterLines(ffmpegPath, lines);
  }

  /**
   * Parses raw {@code ffmpeg -filters} output lines into a capabilities
   * snapshot. Package-visible (not private) so tests can feed canned
   * ffmpeg output without spawning a real process.
   */
  static AudioFilterCapabilities buildFromFilterLines(String ffmpegPath, List<String> lines)
  {
    Set<String> found = parseFilterNames(lines);
    return AudioFilterCapabilities.builder()
        .ffmpegPath(ffmpegPath)
        .probeSucceeded(true)
        .equalizerAvailable(found.contains("equalizer"))
        .anequalizerAvailable(found.contains("anequalizer"))
        .volumeAvailable(found.contains("volume"))
        .aresampleAvailable(found.contains("aresample"))
        .acompressorAvailable(found.contains("acompressor"))
        .loudnormAvailable(found.contains("loudnorm"))
        .dynaudnormAvailable(found.contains("dynaudnorm"))
        .alimiterAvailable(found.contains("alimiter"))
        .probedAtMillis(System.currentTimeMillis())
        .build();
  }

  static Set<String> parseFilterNames(List<String> lines)
  {
    Set<String> found = new HashSet<String>();
    if (lines == null)
      return found;
    for (String line : lines)
    {
      if (line == null)
        continue;
      Matcher m = FILTER_LINE_PATTERN.matcher(line);
      if (m.find())
      {
        String name = m.group(1);
        if (FILTERS_OF_INTEREST.contains(name))
          found.add(name);
      }
    }
    return found;
  }

  private static List<String> runFfmpegFiltersCommand(String ffmpegPath)
  {
    Process p = null;
    try
    {
      ProcessBuilder pb = new ProcessBuilder(ffmpegPath, "-hide_banner", "-filters");
      pb.redirectErrorStream(true);
      p = pb.start();
      java.util.ArrayList<String> lines = new java.util.ArrayList<String>();
      BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
      String line;
      while ((line = br.readLine()) != null)
        lines.add(line);
      p.waitFor();
      return lines;
    }
    catch (IOException e)
    {
      return null;
    }
    catch (InterruptedException e)
    {
      Thread.currentThread().interrupt();
      return null;
    }
    catch (Throwable t)
    {
      return null;
    }
    finally
    {
      if (p != null)
      {
        try { p.getInputStream().close(); } catch (Throwable t) { /* best-effort cleanup */ }
      }
    }
  }
}
