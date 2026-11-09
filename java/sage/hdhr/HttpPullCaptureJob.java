/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package sage.hdhr;

import sage.Sage;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Captures an ATSC 3.0 (HEVC + AC-4) stream from an HDHomeRun by pulling the
 * device's HTTP endpoint and writing the raw MPEG-TS bytes straight to disk.
 *
 * Pure byte-pump (no ffmpeg, no transcode):
 *   - The /auto/v&lt;channel&gt; endpoint already emits a valid MPEG-TS with a
 *     correct PMT containing HEVC video + AC-4 audio.
 *   - Phase 1 taught Sage's native demuxer about HEVC + AC-4 PIDs, so the
 *     bytes we land on disk are directly playable by sage.PlaybackHandler.
 *   - Legacy clients (Placeshifter, HD300, browser) hit AC4TranscodeJob at
 *     serve time -- not at capture time. Capture stays lossless.
 *
 * One job = one HTTP GET = one .mpg file. switchFile() closes the current
 * connection and opens a new one against a new file (mirrors the libhdhomerun
 * path's segment rotation behavior; ~50ms gap, far under the live-DVR window).
 */
public class HttpPullCaptureJob implements Runnable
{
  private static final String CONNECT_TIMEOUT_PROP = "hdhr/atsc3_connect_timeout_ms";
  private static final String READ_TIMEOUT_PROP    = "hdhr/atsc3_read_timeout_ms";
  private static final String BUFFER_SIZE_PROP     = "hdhr/atsc3_buffer_size";
  private static final String MIN_RATE_PROP        = "hdhr/http_pull_min_bytes_per_sec";
  private static final String RATE_WINDOW_PROP     = "hdhr/http_pull_rate_window_ms";
  private static final String PROGRESS_LOG_PROP    = "hdhr/http_pull_progress_log_ms";
  private static final String FLUSH_INTERVAL_PROP  = "hdhr/http_pull_flush_interval_ms";
  private static final int    DEFAULT_CONNECT_TO   = 5000;
  private static final int    DEFAULT_READ_TO      = 15000;
  private static final int    DEFAULT_BUFSZ        = 64 * 1024; // 64 KiB
  private static final long   DEFAULT_MIN_RATE     = 32 * 1024; // 256 kbps
  private static final long   DEFAULT_RATE_WINDOW  = 10000;
  private static final long   DEFAULT_PROGRESS_LOG = 30000;
  private static final long   DEFAULT_FLUSH        = 1000;

  private final String url;
  private volatile String currentFile;
  private final Object lock = new Object();

  private volatile HttpURLConnection conn;
  private volatile InputStream       in;
  private volatile boolean stop = false;
  private volatile String  pendingSwitchFile;
  private volatile long    bytesWritten = 0L;
  private volatile long    lastReadTime;
  private volatile int     lastReadSize;
  private volatile String  lastErrorLine;

  public HttpPullCaptureJob(String url, String firstFile)
  {
    if (url == null || url.length() == 0)
      throw new IllegalArgumentException("url required");
    if (firstFile == null || firstFile.length() == 0)
      throw new IllegalArgumentException("firstFile required");
    this.url = url;
    this.currentFile = firstFile;
  }

  /** Schedule a fast file switch. Picked up at the next buffer boundary. */
  public void switchFile(String newFile)
  {
    synchronized (lock)
    {
      pendingSwitchFile = newFile;
      closeStreamQuiet();
      lock.notifyAll();
    }
  }

  public void requestStop()
  {
    synchronized (lock)
    {
      stop = true;
      closeStreamQuiet();
      lock.notifyAll();
    }
  }

  /** Total output bytes for the *current* file (recordedBytes accounting). */
  public long getBytesWritten()
  {
    String f = currentFile;
    if (f == null) return 0L;
    File ff = new File(f);
    return ff.exists() ? ff.length() : 0L;
  }

  public String getLastErrorLine() { return lastErrorLine; }
  public long getLastReadTime() { return lastReadTime; }
  public int getLastReadSize() { return lastReadSize; }

  @Override
  public void run()
  {
    if (Sage.DBG) System.out.println("HttpPullCaptureJob start url=" + url
        + " file=" + currentFile);
    try
    {
      boolean append = false;
      while (!stop)
      {
        runOne(currentFile, append);
        synchronized (lock)
        {
          if (stop) break;
          if (pendingSwitchFile != null)
          {
            currentFile = pendingSwitchFile;
            pendingSwitchFile = null;
            bytesWritten = 0L;
            append = false;
            continue;
          }
          // Stream ended unexpectedly: brief backoff then reconnect to same file.
          append = true;
          try { lock.wait(500); } catch (InterruptedException ie) { break; }
          if (stop) break;
        }
      }
    }
    finally
    {
      closeStreamQuiet();
      if (Sage.DBG) System.out.println("HttpPullCaptureJob exit url=" + url);
    }
  }

  private void runOne(String outFile, boolean append)
  {
    int connectTo = Sage.getInt(CONNECT_TIMEOUT_PROP, DEFAULT_CONNECT_TO);
    int readTo    = Sage.getInt(READ_TIMEOUT_PROP,    DEFAULT_READ_TO);
    int bufSz     = Sage.getInt(BUFFER_SIZE_PROP,     DEFAULT_BUFSZ);
    long minRate  = Math.max(0L, Sage.getLong(MIN_RATE_PROP, DEFAULT_MIN_RATE));
    long rateWindow = Math.max(1000L,
        Sage.getLong(RATE_WINDOW_PROP, DEFAULT_RATE_WINDOW));
    long progressLog = Math.max(1000L,
        Sage.getLong(PROGRESS_LOG_PROP, DEFAULT_PROGRESS_LOG));
    long flushInterval = Math.max(100L,
        Sage.getLong(FLUSH_INTERVAL_PROP, DEFAULT_FLUSH));

    HttpURLConnection c = null;
    InputStream       is = null;
    OutputStream      os = null;
    try
    {
      URL u = new URL(url);
      c = (HttpURLConnection) u.openConnection();
      c.setConnectTimeout(connectTo);
      c.setReadTimeout(readTo);
      c.setRequestMethod("GET");
      c.setRequestProperty("User-Agent", "SageTV-HDHR-Capture/1.0");
      c.connect();
      int code = c.getResponseCode();
      if (code != 200)
      {
        lastErrorLine = "HTTP " + code + " " + c.getResponseMessage();
        if (Sage.DBG) System.out.println("HttpPullCaptureJob: " + lastErrorLine
            + " url=" + url);
        return;
      }
      is = c.getInputStream();
      synchronized (lock)
      {
        if (stop) return;
        conn = c;
        in   = is;
        lock.notifyAll();
      }
      File outputFile = new File(outFile);
      long total = append && outputFile.exists() ? outputFile.length() : 0L;
      os = new BufferedOutputStream(new FileOutputStream(outputFile, append), bufSz);

      byte[] buf = new byte[bufSz];
      long windowStart = Sage.eventTime();
      long windowBytes = 0L;
      long lastProgressLog = windowStart;
      long lastFlush = windowStart;
      int n;
      while (!stop && (n = is.read(buf)) > 0)
      {
        os.write(buf, 0, n);
        total += n;
        windowBytes += n;
        bytesWritten = total;
        lastReadSize = n;
        lastReadTime = Sage.eventTime();
        long now = lastReadTime;
        if (now - lastFlush >= flushInterval)
        {
          os.flush();
          lastFlush = now;
        }
        long elapsed = now - windowStart;
        if (elapsed >= rateWindow)
        {
          long bytesPerSec = calculateBytesPerSecond(windowBytes, elapsed);
          if (Sage.DBG && now - lastProgressLog >= progressLog)
          {
            System.out.println("HTTP_CAPTURE_PROGRESS file=" + outFile
                + " cumulativeBytes=" + total
                + " intervalBytes=" + windowBytes
                + " bytesPerSec=" + bytesPerSec
                + " lastReadSize=" + lastReadSize
                + " lastReadAgeMs=" + (now - lastReadTime)
                + " state=source-read");
            lastProgressLog = now;
          }
          if (minRate > 0L && bytesPerSec < minRate)
          {
            throw new IOException("capture throughput below minimum: bytesPerSec="
                + bytesPerSec + " minimum=" + minRate + " windowMs=" + elapsed);
          }
          windowStart = now;
          windowBytes = 0L;
        }
        if (pendingSwitchFile != null) break;
      }
      os.flush();
      if (Sage.DBG) System.out.println("HttpPullCaptureJob: file=" + outFile
          + " cumulativeBytes=" + total + " append=" + append
          + " lastReadSize=" + lastReadSize
          + " lastReadAgeMs=" + Math.max(0L, Sage.eventTime() - lastReadTime));
    }

    catch (IOException e)
    {
      lastErrorLine = e.getClass().getSimpleName() + ": " + e.getMessage();
      if (Sage.DBG) System.out.println("HttpPullCaptureJob: " + lastErrorLine
          + " url=" + url);
    }
    finally
    {
      if (os != null) { try { os.close(); } catch (IOException ie) {} }
      if (is != null) { try { is.close(); } catch (IOException ie) {} }
      if (c  != null) { try { c.disconnect(); } catch (Throwable t) {} }
      synchronized (lock) { conn = null; in = null; }
    }
  }

  static long calculateBytesPerSecond(long bytes, long elapsedMs)
  {
    if (bytes <= 0L || elapsedMs <= 0L) return 0L;
    return bytes > Long.MAX_VALUE / 1000L
        ? Long.MAX_VALUE : bytes * 1000L / elapsedMs;
  }

  private void closeStreamQuiet()
  {
    InputStream       is = in;
    HttpURLConnection c  = conn;
    if (is != null) { try { is.close(); } catch (IOException ie) {} }
    if (c  != null) { try { c.disconnect(); } catch (Throwable t) {} }
  }
}
