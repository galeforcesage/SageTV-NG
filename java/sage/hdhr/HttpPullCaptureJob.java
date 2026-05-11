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
  private static final int    DEFAULT_CONNECT_TO   = 5000;
  private static final int    DEFAULT_READ_TO      = 15000;
  private static final int    DEFAULT_BUFSZ        = 64 * 1024; // 64 KiB

  private final String url;
  private volatile String currentFile;
  private final Object lock = new Object();

  private volatile HttpURLConnection conn;
  private volatile InputStream       in;
  private volatile boolean stop = false;
  private volatile String  pendingSwitchFile;
  private volatile long    bytesWritten = 0L;
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

  @Override
  public void run()
  {
    if (Sage.DBG) System.out.println("HttpPullCaptureJob start url=" + url
        + " file=" + currentFile);
    try
    {
      while (!stop)
      {
        runOne(currentFile);
        synchronized (lock)
        {
          if (stop) break;
          if (pendingSwitchFile != null)
          {
            currentFile = pendingSwitchFile;
            pendingSwitchFile = null;
            bytesWritten = 0L;
            continue;
          }
          // Stream ended unexpectedly: brief backoff then reconnect to same file.
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

  private void runOne(String outFile)
  {
    int connectTo = Sage.getInt(CONNECT_TIMEOUT_PROP, DEFAULT_CONNECT_TO);
    int readTo    = Sage.getInt(READ_TIMEOUT_PROP,    DEFAULT_READ_TO);
    int bufSz     = Sage.getInt(BUFFER_SIZE_PROP,     DEFAULT_BUFSZ);

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
      // append=false: each runOne() truncates and starts fresh.
      os = new BufferedOutputStream(new FileOutputStream(outFile, false), bufSz);

      byte[] buf = new byte[bufSz];
      long total = 0L;
      int n;
      while (!stop && (n = is.read(buf)) > 0)
      {
        os.write(buf, 0, n);
        total += n;
        bytesWritten = total;
        if (pendingSwitchFile != null) break;
      }
      os.flush();
      if (Sage.DBG) System.out.println("HttpPullCaptureJob: file=" + outFile
          + " bytes=" + total);
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

  private void closeStreamQuiet()
  {
    InputStream       is = in;
    HttpURLConnection c  = conn;
    if (is != null) { try { is.close(); } catch (IOException ie) {} }
    if (c  != null) { try { c.disconnect(); } catch (Throwable t) {} }
  }
}
