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
package sage;

// Transcoding Manager
public class Ministry implements Runnable
{
  private static final String NEXT_JOB_ID = "transcoder/next_job_id";
  protected static final String TRANSCODE_JOB_PROPS = "transcoder/jobs";

  // Legacy preset names — swept out of Sage.properties at startup so the
  // modernized NVENC preset catalogue (loaded from disk via loadPresets())
  // can replace them cleanly. Everything in this list pre-dates the offline
  // transcode preset rewrite (see ROADMAP "Offline transcode preset
  // modernization (Ministry)") and should NOT be re-seeded.
  private static final String[] DEAD_FORMAT_NAMES = {
    // ── Original 2008-era "Compatible" series ──
    "Razr Compatible-Fair Quality",
    "Razr Compatible-Good Quality",
    "Razr Compatible-High Quality",
    "MPEG4 HDTV Deinterlaced in AVI-High Quality",
    "MPEG4 HDTV in AVI-High Quality",
    "MPEG4 HDTV Deinterlaced in AVI-Good Quality",
    "MPEG4 HDTV in AVI-Good Quality",
    "MPEG4 Deinterlaced in AVI-High Quality",
    "MPEG4 in AVI-High Quality",
    "MPEG4 Deinterlaced in AVI-Good Quality",
    "MPEG4 in AVI-Good Quality",
    "PSP Compatible-Good Quality",
    "PSP Compatible-High Quality",
    "PSP Compatible Widescreen-Good Quality",
    "PSP Compatible Widescreen-High Quality",
    "iPod Compatible-Fair Quality",
    "iPod Compatible-Good Quality",
    "iPod Compatible-High Quality",
    "DVD Compatible-Standard Play",
    "DVD Compatible-Standard Play with AC3",
    "DVD Compatible-Standard Play w/ AC3",
    "DVD Compatible-Long Play",
    "DVD Compatible-Long Play with AC3",
    "DVD Compatible-Long Play w/ AC3",
    "DVD Compatible-Extra Long Play",
    "DVD Compatible-Extra Long Play with AC3",
    "DVD Compatible-Extra Long Play w/ AC3",
    "Razr-Fair Quality",
    "Razr-Good Quality",
    "Razr-High Quality",
    "MPEG4 HDTV-High Quality Deinterlaced AVI",
    "MPEG4 HDTV-Good Quality Deinterlaced AVI",
    "MPEG4-High Quality Deinterlaced AVI",
    "MPEG4-Good Quality Deinterlaced AVI",
    // ── Previously-shipped PREDEFINED_TRANSCODER_FORMATS (now obsolete) ──
    "MPEG4 HDTV-High Quality AVI",
    "MPEG4 HDTV-Good Quality AVI",
    "MPEG4 HDTV-High Quality H.264 MKV",
    "MPEG4 HDTV-Good Quality H.264 MKV",
    "PSP-Good Quality",
    "PSP-High Quality",
    "PSP-Widescreen Good Quality",
    "PSP-Widescreen High Quality",
    "iPod-Fair Quality",
    "iPod-Good Quality",
    "iPod-High Quality",
    "iPhone-Standard",
    "iPhone-Widescreen",
    "AppleTV-High Quality",
    "AppleTV-High Quality Widescreen",
    // ── Previously-shipped PREDEFINED_TRANSCODER_FORMATS_{NTSC,PAL} ──
    "MPEG4-High Quality AVI",
    "MPEG4-Good Quality AVI",
    "MPEG4-High Quality H.264 MKV",
    "MPEG4-Good Quality H.264 MKV",
    "DVD-Standard Play",
    "DVD-Standard Play with AC3",
    "DVD-Long Play",
    "DVD-Long Play with AC3",
    "DVD-Extra Long Play",
    "DVD-Extra Long Play with AC3",
    // ── Renamed in 2026-05 preset rewrite (now UPSCALE_1440 / UPSCALE_2160) ──
    "UPSCALE_1440_FROM_1080",
    "UPSCALE_2160_FROM_1080",
  };

  // Retained for legacy callers of getPredefinedTargetFormat() that may still
  // reference the deinterlaced-AVI names; remapping is harmless (the target
  // may simply be absent now, in which case the lookup returns null and the
  // UI re-prompts).
  private static final String[][] SUBSTITUTE_FORMAT_NAMES = {
    { "MPEG4 HDTV-High Quality Deinterlaced AVI", "MPEG4 HDTV-High Quality AVI" },
    { "MPEG4 HDTV-Good Quality Deinterlaced AVI", "MPEG4 HDTV-Good Quality AVI" },
    { "MPEG4-High Quality Deinterlaced AVI", "MPEG4-High Quality AVI" },
    { "MPEG4-Good Quality Deinterlaced AVI", "MPEG4-Good Quality AVI" },
  };

  // ──────────────────────────────────────────────────────────────────────
  // Modernized preset catalogue — loaded from disk at startup. Replaces the
  // 2008-era PREDEFINED_TRANSCODER_FORMATS{,_NTSC,_PAL} arrays. Search path
  // (highest precedence first):
  //
  //   1. ${STATE_DIR}/transcoder/presets/*.properties   (deploy/user overrides)
  //   2. <Sage.installPath>/presets/transcoder/*.properties  (shipped baseline)
  //
  // STATE_DIR is the per-install state directory used by the state-managed
  // container layout (see sagetv-deploy entrypoint-state.sh, e.g.
  // "/opt/sagetv/state/mine"). If unset, only the baseline path is consulted.
  //
  // Each .properties file describes one preset:
  //   name=PHONE_STD                            ← display name (becomes the
  //                                                Sage.properties key under
  //                                                transcoder/formats/)
  //   container=mp4                             ← muxer name (mp4|matroska|...)
  //   global=-hwaccel cuda -hwaccel_output_format cuda
  //   args=-vf scale_npp=1280:720 -c:v %V264% -preset p5 -cq:v 23 ...
  //
  // Tokens substituted at load time:
  //   %V264%  →  HwEncoder.encoderName(HwEncoder.pick("h264"), "h264")
  //              (e.g. "h264_nvenc" on Turing, falls back to "libx264")
  //   %V265%  →  HwEncoder.encoderName(HwEncoder.pick("hevc"), "hevc")
  //
  // The generated Sage.properties value is the raw-cmdline metadata form
  // consumed by FFMPEGTranscoder.setTranscodeFormat() / startTranscode():
  //   f=<container>;MRawCmdlineGlobal=<global>;MRawCmdline=<args>;
  // (values are escaped via MediaFormat.escapeString so embedded '=' and ';'
  // round-trip through ContainerFormat.buildFormatFromString cleanly.)
  // ──────────────────────────────────────────────────────────────────────
  private static final String BASELINE_PRESETS_SUBDIR = "presets/transcoder";
  private static final String STATE_PRESETS_SUBDIR = "transcoder/presets";

  // STV "Transcode To..." menu sort-order property. The STV reads this via
  // GetServerProperty("transcoder/sorting_order2", <stv-default>) and uses it
  // as the authoritative display order for the format menu (any preset not in
  // this list is appended at the end, sorted lexically). The historical
  // default ("iPod;iPhone;DVD;MPEG4;MPEG4 HDTV;AppleTV;PSP") references only
  // names that no longer exist; sweep it to the modernized ordering at boot
  // so the menu reflects the new screen-tier preset catalogue.
  private static final String XCODE_SORT_ORDER_PROP = "transcoder/sorting_order2";
  private static final String LEGACY_XCODE_SORT_ORDER = "iPod;iPhone;DVD;MPEG4;MPEG4 HDTV;AppleTV;PSP";
  private static final String[] PRESET_SORT_ORDER = {
    "PHONE_LOW", "PHONE_STD", "PHONE_HIGH_1080",
    "TABLET_10_1080", "TABLET_12_1440",
    "TV_1080_COMPAT", "TV_4K_HEVC",
    "ARCHIVE_HEVC_MKV", "DVD_LEGACY_MPEG2",
    "UPSCALE_1440", "UPSCALE_2160",
  };

  // Bump when PRESET_SORT_ORDER changes shape (rename / reorder) so the new
  // default replaces a stale auto-migrated value still sitting in Sage.properties.
  private static final String PRESET_SORT_ORDER_VERSION = "2026-05-rename-upscale";
  private static final String PRESET_SORT_ORDER_VERSION_PROP = "transcoder/sorting_order2_version";

  private static void migrateSortOrder()
  {
    String cur = Sage.get(XCODE_SORT_ORDER_PROP, null);
    String curVer = Sage.get(PRESET_SORT_ORDER_VERSION_PROP, null);
    boolean migrated = (cur != null && cur.length() > 0 && !LEGACY_XCODE_SORT_ORDER.equals(cur));
    boolean versionMatches = PRESET_SORT_ORDER_VERSION.equals(curVer);
    // Leave a user-customized value alone (any value not matching either a previous
    // auto-migration or the legacy default). Detect auto-migrated values by checking
    // they consist entirely of names known to Ministry (current or DEAD).
    if (migrated && !versionMatches && !isAutoMigratedSortOrder(cur))
      return; // user-customized; do not clobber
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < PRESET_SORT_ORDER.length; i++)
    {
      if (i > 0) sb.append(';');
      sb.append(PRESET_SORT_ORDER[i]);
    }
    Sage.put(XCODE_SORT_ORDER_PROP, sb.toString());
    Sage.put(PRESET_SORT_ORDER_VERSION_PROP, PRESET_SORT_ORDER_VERSION);
  }

  private static boolean isAutoMigratedSortOrder(String val)
  {
    java.util.Set<String> known = new java.util.HashSet<String>();
    for (String s : PRESET_SORT_ORDER) known.add(s);
    for (String s : DEAD_FORMAT_NAMES) known.add(s);
    for (String s : val.split(";"))
    {
      s = s.trim();
      if (s.length() == 0) continue;
      if (!known.contains(s)) return false;
    }
    return true;
  }

  private static void loadPresets()
  {
    java.util.List<java.io.File> dirs = new java.util.ArrayList<java.io.File>();
    String stateDir = System.getenv("STATE_DIR");
    if (stateDir != null && stateDir.length() > 0)
    {
      java.io.File f = new java.io.File(stateDir, STATE_PRESETS_SUBDIR);
      if (f.isDirectory()) dirs.add(f);
    }
    // Baseline shipped with the install (Dockerfile / install layout puts
    // these under <installRoot>/presets/transcoder/). Resolved relative to
    // the JVM working dir, which for SageTV is the install root.
    java.io.File baseline = new java.io.File(BASELINE_PRESETS_SUBDIR);
    if (baseline.isDirectory()) dirs.add(baseline);

    java.util.Set<String> seen = new java.util.HashSet<String>();
    for (java.io.File dir : dirs)
    {
      java.io.File[] files = dir.listFiles(new java.io.FilenameFilter()
      {
        public boolean accept(java.io.File d, String n) { return n.endsWith(".properties"); }
      });
      if (files == null) continue;
      java.util.Arrays.sort(files);
      for (java.io.File f : files)
      {
        try
        {
          java.util.Properties p = new java.util.Properties();
          java.io.InputStream is = new java.io.FileInputStream(f);
          try { p.load(is); } finally { is.close(); }
          String name = p.getProperty("name", "").trim();
          if (name.length() == 0)
          {
            if (Sage.DBG) System.out.println("Ministry: preset " + f + " has no 'name' property; skipping");
            continue;
          }
          if (!seen.add(name)) continue; // higher-precedence dir already won
          String spec = buildPresetSpec(p);
          if (spec != null)
          {
            Sage.put("transcoder/formats/" + name, spec);
            applyPresetDisplayName(p, name);
          }
        }
        catch (Exception e)
        {
          if (Sage.DBG) System.out.println("Ministry: failed loading preset " + f + ": " + e);
        }
      }
    }
  }

  private static String buildPresetSpec(java.util.Properties p)
  {
    String container = p.getProperty("container", "mp4").trim();

    // Vendor-block selection: if the preset provides args_nv=/args_sw= keys,
    // pick the block matching the detected hardware encoder. This lets each
    // preset carry correct encoder-specific params (NVENC -preset p5 / -rc:v
    // vbr / -cq:v vs libx264 -preset medium / -crf) without runtime rewriting.
    // Presets that only have the legacy bare args=/global= keys still work —
    // they go through the original %V264%/%V265% token + CUDA-strip path.
    String vendorBlock = pickVendorBlock();
    String global = p.getProperty("global_" + vendorBlock, "").trim();
    String args = p.getProperty("args_" + vendorBlock, "").trim();
    if (args.length() == 0)
    {
      // Fallback: legacy bare keys (backward compat for user-authored presets
      // and presets like DVD_LEGACY_MPEG2 that have no vendor variants).
      global = p.getProperty("global", "").trim();
      args = p.getProperty("args", "").trim();
    }
    if (args.length() == 0) return null;

    // Resolve the %V264%/%V265% encoder tokens and keep the CUDA -hwaccel
    // flags consistent with the chosen encoder. These presets are authored
    // for NVENC + CUDA decode; when NVENC is unavailable the tokens resolve
    // to software encoders (libx264/libx265) and the CUDA -hwaccel flags must
    // be stripped or ffmpeg aborts with "no CUDA device" on a GPU-less host.
    boolean nvencUsed = false;
    boolean tokenPresent = false;
    if (args.indexOf("%V264%") != -1 || global.indexOf("%V264%") != -1)
    {
      tokenPresent = true;
      HwEncoder.Kind k = pickPresetEncoder("h264");
      if (k == HwEncoder.Kind.NVENC) nvencUsed = true;
      String enc = HwEncoder.encoderName(k, "h264");
      args = args.replace("%V264%", enc);
      global = global.replace("%V264%", enc);
    }
    if (args.indexOf("%V265%") != -1 || global.indexOf("%V265%") != -1)
    {
      tokenPresent = true;
      HwEncoder.Kind k = pickPresetEncoder("hevc");
      if (k == HwEncoder.Kind.NVENC) nvencUsed = true;
      String enc = HwEncoder.encoderName(k, "hevc");
      args = args.replace("%V265%", enc);
      global = global.replace("%V265%", enc);
    }
    // Software (or non-CUDA) fallback: drop the CUDA-specific -hwaccel flags.
    if (tokenPresent && !nvencUsed)
    {
      global = stripCudaHwaccel(global);
      args = stripCudaHwaccel(args);
    }

    StringBuilder sb = new StringBuilder();
    sb.append("f=").append(sage.media.format.MediaFormat.escapeString(container)).append(';');
    if (global.length() > 0)
      sb.append("MRawCmdlineGlobal=").append(sage.media.format.MediaFormat.escapeString(global)).append(';');
    sb.append("MRawCmdline=").append(sage.media.format.MediaFormat.escapeString(args)).append(';');
    return sb.toString();
  }

  // UI-friendly label for the preset. Persisted under transcoder/format_labels/<NAME>
  // (NOT under transcoder/formats/ — that subtree is enumerated by
  // TranscodeAPI.GetTranscodeFormats() and must contain only preset keys).
  // STVs / clients fetch via TranscodeAPI.GetTranscodeFormatDisplayName(name),
  // which falls back to the raw name if no label was provided.
  private static final String FORMAT_LABEL_PREFIX = "transcoder/format_labels/";

  private static void applyPresetDisplayName(java.util.Properties p, String name)
  {
    String label = p.getProperty("displayName", "").trim();
    if (label.length() == 0) label = name;
    Sage.put(FORMAT_LABEL_PREFIX + name, label);
  }

  // Encoder choice for the CUDA-shaped screen-tier presets. Only NVENC gets
  // the CUDA fast path here; every other outcome (no GPU, or a non-NVIDIA HW
  // encoder that can't be wired into a CUDA-authored filter graph) degrades to
  // the software encoder (libx264/libx265). Native VAAPI/QSV/AMF acceleration
  // belongs to dedicated vendor-specific preset files (see ROADMAP
  // "Vendor-agnostic preset coverage"), not an auto-rewrite of CUDA presets.
  private static HwEncoder.Kind pickPresetEncoder(String codec)
  {
    HwEncoder.Kind k = HwEncoder.pick(codec);
    if (k == HwEncoder.Kind.NVENC) return k;
    if (k != HwEncoder.Kind.NONE && Sage.DBG)
      System.out.println("Ministry: " + codec + " HW encoder " + k + " is available but the"
          + " screen-tier presets are CUDA-shaped; using software (libx264/libx265)."
          + " Add a vendor-specific preset for native " + k + " acceleration.");
    return HwEncoder.Kind.NONE;
  }

  // Select the vendor block suffix for preset .properties lookup.
  // Returns "nv" when NVENC is available, "sw" otherwise.
  // Future: "vaapi", "qsv", "amf" when those preset blocks are authored.
  private static String pickVendorBlock()
  {
    HwEncoder.Kind k = HwEncoder.pick("h264");
    if (k == HwEncoder.Kind.NVENC) return "nv";
    // Future vendors would be checked here:
    // if (k == HwEncoder.Kind.VAAPI) return "vaapi";
    // if (k == HwEncoder.Kind.QSV) return "qsv";
    return "sw";
  }

  // Remove CUDA-only decode flags (-hwaccel cuda, -hwaccel_output_format cuda,
  // -hwaccel_device N) from a preset arg string, leaving all other flags intact.
  // Used when a CUDA-authored preset falls back to a software encoder so the
  // command does not demand a CUDA device that may be absent.
  private static String stripCudaHwaccel(String s)
  {
    if (s == null || s.length() == 0) return "";
    String[] toks = s.trim().split("\\s+");
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < toks.length; i++)
    {
      String t = toks[i];
      if (t.equals("-hwaccel") || t.equals("-hwaccel_output_format") || t.equals("-hwaccel_device"))
      {
        i++; // also skip this flag's value token
        continue;
      }
      if (sb.length() > 0) sb.append(' ');
      sb.append(t);
    }
    return sb.toString();
  }

  private static class MinistryHolder
  {
    public static final Ministry instance = new Ministry();
  }

  public static Ministry getInstance()
  {
    return MinistryHolder.instance;
  }

  /** Creates a new instance of Ministry */
  public Ministry()
  {
    jobCounter = Sage.getInt(NEXT_JOB_ID, 1);

    // Sweep obsolete legacy preset names out of Sage.properties so the
    // modernized catalogue below replaces them cleanly on first boot.
    for (int i = 0; i < DEAD_FORMAT_NAMES.length; i++)
      Sage.remove("transcoder/formats/" + DEAD_FORMAT_NAMES[i]);

    // Load the modernized NVENC/upscale preset catalogue from disk.
    loadPresets();

    // Log the resolved transcode encoder tier once so no-GPU / wrong-GPU
    // deployments are diagnosable from the startup log.
    if (Sage.DBG)
      System.out.println("Ministry: transcode encoder tier -> h264=" + HwEncoder.pick("h264")
          + " hevc=" + HwEncoder.pick("hevc")
          + " (software fallback = libx264/libx265 when NVENC absent)");

    // Replace the stale legacy STV menu sort order (which only references
    // dead names) with the modernized preset ordering. No-op if the user has
    // already customized this property.
    migrateSortOrder();
  }

  public void notifyOfID(int x)
  {
    synchronized (idLock)
    {
      if (x >= jobCounter)
      {
        jobCounter = x + 1;
        Sage.putInt(NEXT_JOB_ID, jobCounter);
      }
    }
  }

  public int getNextJobID()
  {
    synchronized (idLock)
    {
      Sage.putInt(NEXT_JOB_ID, jobCounter + 1);
      jobCounter++;
      return jobCounter - 1;
    }
  }

  public void spawn()
  {
    alive = true;
    ministryThread = new Thread(this, "Ministry");
    ministryThread.setPriority(Thread.MIN_PRIORITY);
    ministryThread.setDaemon(true);
    ministryThread.start();
  }

  void goodbye()
  {
    alive = false;
    synchronized (lock)
    {
      lock.notifyAll();
    }
    if (ministryThread != null)
    {
      try
      {
        ministryThread.join(10000);
      }
      catch (InterruptedException e){}
    }
  }

  public void run()
  {
    // Just wait a sec at first so we don't slow down anything initializing since we're lowest priority
    try{Thread.sleep(5000);}catch(Exception e){}

    if (Sage.DBG) System.out.println("Ministry is starting");
    // Look for any MediaFiles which we should mark as transcoded immediately
    MediaFile[] mfs = Wizard.getInstance().getFiles();
    for (int i = 0; i < mfs.length; i++)
    {
      MediaFile mf = mfs[i];
      if (doesFileAlwaysRequireTranscoding(mf))
      {
        if (Sage.DBG) System.out.println("Added for transcoding:" + mf);
        sage.media.format.ContainerFormat cf = new sage.media.format.ContainerFormat();
        cf.setFormatName(sage.media.format.MediaFormat.AVI);
        waitingForConversion.add(new DShowTranscodeJob(mf, "AVI", cf, true, null));
      }
    }

    String[] jobKeys = Sage.childrenNames(TRANSCODE_JOB_PROPS);
    for (int i = 0; i < jobKeys.length; i++)
    {
      int currJobID;
      try
      {
        currJobID = Integer.parseInt(jobKeys[i]);
      }
      catch (NumberFormatException e)
      {
        System.out.println("ERROR in transcode job id format:" + e);
        continue;
      }
      String processor = Sage.get(TRANSCODE_JOB_PROPS + '/' + currJobID + '/' + TranscodeJob.TRANSCODE_PROCESSOR, null);
      TranscodeJob tj;
      try
      {
        if ("sagetv".equals(processor))
        {
          tj = new FFMPEGTranscodeJob(currJobID);
        }
        else
        {
          System.out.println("Unknown Transcode processor:" + processor);
          continue;
        }
        if (tj.getJobState() == TranscodeJob.COMPLETED || tj.getJobState() < 0)
          waitingForAbsolution.add(tj);
        else
          waitingForConversion.add(tj);
      }
      catch (IllegalArgumentException iae)
      {
        System.out.println("BAD transcode job data:" + iae);
        Sage.removeNode(TRANSCODE_JOB_PROPS + '/' + currJobID);
      }
    }

    while (alive)
    {
      long waitTime = Sage.getLong("mstry_engine_update_frequency", 3*Sage.MILLIS_PER_MIN);
      try
      {
        // Pre-compute recording state once per tick — used to gate parallel
        // transcodes and to drive transcoder/pause_during_recording.
        boolean anyDeviceRecording = false;
        try
        {
          sage.CaptureDevice[] cds = MMC.getInstance().getCaptureDevices();
          if (cds != null)
          {
            for (int ci = 0; ci < cds.length; ci++)
            {
              if (cds[ci] != null && cds[ci].isRecording())
              {
                anyDeviceRecording = true;
                break;
              }
            }
          }
        }
        catch (Throwable t) { /* defensive — never let recording probe break the tick */ }

        int maxConcurrent = anyDeviceRecording ? 1
            : Math.max(1, Sage.getInt("transcoder/max_concurrent_when_idle", 1));
        boolean pauseDuringRec = Sage.getBoolean("transcoder/pause_during_recording", false);

        // Check if anything that's waiting to be converted is now ready for the conversion queue
        if (!waitingForConversion.isEmpty())
        {
          for (int i = 0; i < waitingForConversion.size(); i++)
          {
            TranscodeJob tj = (TranscodeJob) waitingForConversion.get(i);
            if (tj.isReadyForConversion())
            {
              if (converting.size() >= maxConcurrent) break;
              waitingForConversion.removeElementAt(i);
              startConversion(tj);
            }
            else if (tj.hasLostHope())
            {
              waitingForConversion.removeElementAt(i);
            }
            else
            {
              waitTime = Math.min(waitTime, tj.getWaitTime());
            }
          }
        }

        synchronized (converting)
        {
          // Tick the state machine for every active job (was: only converting.get(0)).
          // Iterate backwards because TRANSCODE_FAILED/DESTROYED/LIMBO branches mutate the list.
          for (int ci = converting.size() - 1; ci >= 0; ci--)
          {
            TranscodeJob mainConvert = (TranscodeJob) converting.get(ci);
            // Honor pause_during_recording: SIGSTOP/SIGCONT the running ffmpeg.
            if (pauseDuringRec && mainConvert.getJobState() == TranscodeJob.TRANSCODING)
            {
              if (anyDeviceRecording && !mainConvert.isPausedForRecording())
                mainConvert.pauseTranscode();
              else if (!anyDeviceRecording && mainConvert.isPausedForRecording())
                mainConvert.resumeTranscode();
            }
            switch (mainConvert.getJobState())
            {
              case TranscodeJob.WAITING:
                mainConvert.startTranscode();
                dirty = true;
                break;
              case TranscodeJob.TRANSCODING:
                break;
              case TranscodeJob.TRANSCODE_FAILED:
                converting.remove(ci);
                waitingForAbsolution.add(mainConvert);
                mainConvert.cleanupCurrentTranscode();
                mainConvert.abandon();
                dirty = true;
                break;
              case TranscodeJob.DESTROYED:
                converting.remove(ci);
                mainConvert.cleanupCurrentTranscode();
                mainConvert.abandon();
                dirty = true;
                break;
              case TranscodeJob.TRANSCODING_SEGMENT_COMPLETE:
                mainConvert.cleanupCurrentTranscode();
                if (mainConvert.getClipDuration() == 0 || mainConvert.transcodeSegment < mainConvert.getEndingSegment())
                  mainConvert.getTempFile(mainConvert.transcodeSegment).setLastModified(mainConvert.getMediaFile().
                      getEnd(mainConvert.transcodeSegment));
                else
                  mainConvert.getTempFile(mainConvert.transcodeSegment).setLastModified(mainConvert.getMediaFile().
                      getRecordTime() + mainConvert.getClipStartTime() + mainConvert.getClipDuration());
                if (mainConvert.transcodeSegment  < mainConvert.getEndingSegment())
                {
                  mainConvert.continueTranscode();
                }
                else
                {
                  mainConvert.setJobState(TranscodeJob.LIMBO);
                  converting.remove(ci);
                  waitingForAbsolution.add(mainConvert);
                }
                dirty = true;
                break;
            }
          }
        }

        Hunter seek = SeekerSelector.getInstance();
        for (int i = 0; i < waitingForAbsolution.size(); i++)
        {
          TranscodeJob tj = (TranscodeJob) waitingForAbsolution.get(i);
          if (tj.getJobState() == TranscodeJob.COMPLETED || tj.getJobState() < 0)
            continue;
          if (Sage.DBG) System.out.println("Ministry is absolving " + tj.getMediaFile());
          if (tj.shouldReplaceOriginal() && seek.isMediaFileBeingViewed(tj.getMediaFile()))
          {
            if (Sage.DBG) System.out.println("Waiting to perform transcode DB update until file use has completed.");
            continue;
          }
          java.io.File[] newFiles = tj.getTargetFiles();
          java.io.File[] currFiles = tj.getTempFiles();
          java.util.ArrayList actualFilesVec = new java.util.ArrayList();
          for (int j = tj.getStartingSegment(); j <= tj.getEndingSegment(); j++)
          {
            actualFilesVec.add(newFiles[j]);
            seek.addIgnoreFile(newFiles[j]);
            if (!currFiles[j].equals(newFiles[j]) && (tj.shouldReplaceOriginal() || !newFiles[j].equals(tj.getMediaFile().getFile(j))))
              newFiles[j].delete(); // delete the target file so we can rename appropriately
            if (!currFiles[j].renameTo(newFiles[j]))
            {
              if (Sage.DBG) System.out.println("Renaming of transcoded file " + currFiles[j] + " failed to " + newFiles[j]);
            }
          }
          boolean ok;
          if (tj.shouldReplaceOriginal())
          {
            if (tj.getClipStartTime() != 0 || tj.getClipDuration() != 0)
            {
              long theStart = tj.getClipStartTime() + tj.getMediaFile().getRecordTime();
              long clipDur = tj.getClipDuration();
              long theEnd;
              if (clipDur != 0)
                theEnd = theStart + clipDur;
              else
                theEnd = tj.getMediaFile().getRecordEnd();
              ok = tj.getMediaFile().setFiles((java.io.File[]) actualFilesVec.toArray(new java.io.File[0]), theStart, theEnd);
              tj.getMediaFile().thisIsComplete();
            }
            else
              ok = tj.getMediaFile().setFiles((java.io.File[]) actualFilesVec.toArray(new java.io.File[0]));
            if (!ok)
            {
              for (int j = tj.getStartingSegment(); j <= tj.getEndingSegment(); j++)
              {
                if (!newFiles[j].renameTo(currFiles[j]))
                {
                  if (Sage.DBG) System.out.println("Re-renaming of transcoded file " + newFiles[j] + " failed to " + currFiles[j]);
                }
              }
            }
            else
              SeekerSelector.getInstance().processFileExport(tj.getMediaFile().getFiles(), MediaFile.ACQUISITION_MANUAL);
          }
          else
          {
            if (Sage.getBoolean("transcoder/dont_add_converted_duplicate_files_to_db", false))
            {
              ok = true;
            }
            else
            {
              MediaFile addedFile = Wizard.getInstance().addMediaFile(newFiles[tj.getStartingSegment()], "", MediaFile.ACQUISITION_MANUAL);
              if (addedFile != null)
              {
                if (addedFile.isArchiveFile() != tj.getMediaFile().isArchiveFile())
                {
                  if (tj.getMediaFile().isArchiveFile())
                    addedFile.simpleArchive();
                  else
                    addedFile.simpleUnarchive();
                }
                // Copy any auxillary metadata
                sage.media.format.ContainerFormat cf = tj.getMediaFile().getFileFormat();
                if (cf != null && cf.hasMetadata())
                {
                  // We need to do it one by one so that the external .properties file gets updated (instead of addMetadata that does it all at once)
                  java.util.Properties metaProps = cf.getMetadata();
                  java.util.Iterator walker = metaProps.entrySet().iterator();
                  while (walker.hasNext())
                  {
                    java.util.Map.Entry ent = (java.util.Map.Entry) walker.next();
                    if (ent.getKey() != null && ent.getValue() != null)
                      addedFile.addMetadata(ent.getKey().toString(), ent.getValue().toString());
                  }
                }
                if (Sage.DBG) System.out.println("New Library File " + addedFile);
                if (tj.getClipStartTime() == 0 && tj.getClipDuration() == 0)
                {
                  // Converted the whole thing, so use the complete airing.
                  addedFile.setInfoAiring(Wizard.getInstance().addAiring(tj.getMediaFile().getShow(), 0,
                      tj.getMediaFile().getContentAiring().getStartTime(),
                      tj.getMediaFile().getContentAiring().getDuration(),
                      tj.getMediaFile().getContentAiring().partsB, tj.getMediaFile().getContentAiring().miscB,
                      tj.getMediaFile().getContentAiring().prB, tj.getMediaFile().getMediaMask()));
                }
                else
                {
                  long airStart = tj.getMediaFile().getRecordTime() + tj.getClipStartTime();
                  addedFile.setInfoAiring(Wizard.getInstance().addAiring(tj.getMediaFile().getShow(), 0,
                      airStart, (tj.getClipDuration() == 0) ?
                          (tj.getMediaFile().getRecordEnd() - airStart) : tj.getClipDuration(),
                          tj.getMediaFile().getContentAiring().partsB, tj.getMediaFile().getContentAiring().miscB,
                          tj.getMediaFile().getContentAiring().prB, tj.getMediaFile().getMediaMask()));
                }
                for (int x = 1; x < newFiles.length; x++)
                {
                  addedFile.addSegmentFileDirect((java.io.File) actualFilesVec.get(x));
                }
                ok = true;
                SeekerSelector.getInstance().processFileExport(addedFile.getFiles(), MediaFile.ACQUISITION_MANUAL);
              }
              else
                ok = false;
            }
          }
          for (int j = 0; j < newFiles.length; j++)
          {
            seek.removeIgnoreFile(newFiles[j]);
          }
          // It might not be ready yet for some reason if there's a lock on a file
          if (ok)
          {
            // It's all done
            //waitingForAbsolution.remove(i--);
            tj.setJobState(TranscodeJob.COMPLETED);
            if (tj instanceof FFMPEGTranscodeJob)
              tj.saveToProps();
          }
          if (tj.hasLostHope())
            tj.abandon();
        }
      }
      catch (Throwable t)
      {
        System.out.println("ERROR Occured in core of Ministry:" + t);
        t.printStackTrace();
      }
      synchronized (lock)
      {
        if (!dirty)
        {
          try
          {
            if (Sage.DBG) System.out.println("Ministry is waiting for " + waitTime/1000 + " sec");
            lock.wait(waitTime);
          }catch(Exception e){}
        }
      }
      dirty = false;
    }

    // Abandon all current transcode jobs since we're shutting down
    if (Sage.DBG) System.out.println("Ministry is shutting down....destroying the converts in progress");
    while (waitingForAbsolution.size() > 0)
    {
      TranscodeJob tj = (TranscodeJob) waitingForAbsolution.remove(0);
      tj.cleanupCurrentTranscode();
      tj.abandon();
    }
    while (converting.size() > 0)
    {
      TranscodeJob tj = (TranscodeJob) converting.remove(0);
      tj.cleanupCurrentTranscode();
      tj.abandon();
    }
  }

  public static sage.media.format.ContainerFormat getPredefinedTargetFormat(String formatName)
  {
    for (int i = 0; i < SUBSTITUTE_FORMAT_NAMES.length; i++)
    {
      if (SUBSTITUTE_FORMAT_NAMES[i][0].equalsIgnoreCase(formatName))
      {
        formatName = SUBSTITUTE_FORMAT_NAMES[i][1];
        break;
      }
    }
    return sage.media.format.ContainerFormat.buildFormatFromString(Sage.get("transcoder/formats/" + formatName, null));
  }

  public void submitForPotentialTranscoding(MediaFile mf)
  {
    if (doesFileAlwaysRequireTranscoding(mf))
    {
      synchronized (waitingForConversion)
      {
        for (int i = 0; i < waitingForConversion.size(); i++)
        {
          TranscodeJob tj = (TranscodeJob) waitingForConversion.get(i);
          if (tj.getMediaFile() == mf)
            return;
        }
        if (Sage.DBG) System.out.println("Added for transcoding:" + mf);
        sage.media.format.ContainerFormat cf = new sage.media.format.ContainerFormat();
        cf.setFormatName(sage.media.format.MediaFormat.AVI);
        waitingForConversion.add(new DShowTranscodeJob(mf, "AVI", cf, true, null));
      }
      kick();
    }
    else
    {
      // Check for a Favorite auto conversion
      String targetFormat = Carny.getInstance().getAutoConvertFormat(mf);
      if (targetFormat != null && targetFormat.length() > 0)
      {
        if (Sage.DBG) System.out.println("Setting up automatic Favorite conversion to format " + targetFormat + " for " + mf);
        java.io.File destDir = Carny.getInstance().getAutoConvertDest(mf);
        if (destDir != null)
          destDir.mkdirs();
        addTranscodeJob(mf, targetFormat, getPredefinedTargetFormat(targetFormat),
            destDir, Carny.getInstance().isDeleteAfterConversion(mf), 0, 0);
      }
    }
  }

  public int addTranscodeJob(MediaFile srcFile, String formatName, sage.media.format.ContainerFormat theFormat, java.io.File destFile,
      boolean deleteSourceAfter, long clipStartTime, long clipDuration)
  {
    TranscodeJob tj;
    synchronized (waitingForConversion)
    {
      if (Sage.DBG) System.out.println("Added for transcoding:" + srcFile);
      tj = new FFMPEGTranscodeJob(srcFile, formatName, theFormat, deleteSourceAfter, destFile,
          clipStartTime, clipDuration);
      tj.saveToProps();
      waitingForConversion.add(tj);
    }
    kick();
    return tj.getJobID();
  }

  public void clearCompletedTranscodes()
  {
    synchronized (waitingForAbsolution)
    {
      for (int i = 0; i < waitingForAbsolution.size(); i++)
      {
        TranscodeJob tj = (TranscodeJob) waitingForAbsolution.get(i);
        if (tj.getJobState() == TranscodeJob.COMPLETED || tj.getJobState() < 0)
        {
          Sage.removeNode(TRANSCODE_JOB_PROPS + '/' + tj.getJobID());
          waitingForAbsolution.removeElementAt(i--);
        }
      }
    }
  }

  public boolean cancelTranscodeJob(int jobID)
  {
    boolean rv = false;
    synchronized (waitingForConversion)
    {
      for (int i = 0; i < waitingForConversion.size(); i++)
      {
        TranscodeJob tj = (TranscodeJob) waitingForConversion.get(i);
        if (tj.getJobID() == jobID)
        {
          if (Sage.DBG) System.out.println("KillTranscoding for:" + tj.getMediaFile());
          tj.cleanupCurrentTranscode();
          tj.abandon();
          Sage.removeNode(TRANSCODE_JOB_PROPS + '/' + tj.getJobID());
          waitingForConversion.removeElementAt(i);
          rv = true;
          break;
        }
      }
    }
    synchronized (converting)
    {
      for (int i = 0; !rv && i < converting.size(); i++)
      {
        TranscodeJob tj = (TranscodeJob) converting.get(i);
        if (tj.getJobID() == jobID)
        {
          if (Sage.DBG) System.out.println("KillTranscoding for:" + tj.getMediaFile());
          tj.cleanupCurrentTranscode();
          tj.abandon();
          Sage.removeNode(TRANSCODE_JOB_PROPS + '/' + tj.getJobID());
          converting.removeElementAt(i);
          rv = true;
          break;
        }
      }
    }
    synchronized (waitingForAbsolution)
    {
      for (int i = 0; !rv && i < waitingForAbsolution.size(); i++)
      {
        TranscodeJob tj = (TranscodeJob) waitingForAbsolution.get(i);
        if (tj.getJobID() == jobID)
        {
          if (Sage.DBG) System.out.println("KillTranscoding for:" + tj.getMediaFile());
          Sage.removeNode(TRANSCODE_JOB_PROPS + '/' + tj.getJobID());
          waitingForAbsolution.removeElementAt(i);
          rv = true;
          break;
        }
      }
    }
    if (rv)
      kick();
    return rv;
  }

  protected TranscodeJob getJobForID(int jobID)
  {
    synchronized (waitingForConversion)
    {
      for (int i = 0; i < waitingForConversion.size(); i++)
      {
        TranscodeJob tj = (TranscodeJob) waitingForConversion.get(i);
        if (tj.getJobID() == jobID)
          return tj;
      }
    }
    synchronized (converting)
    {
      for (int i = 0; i < converting.size(); i++)
      {
        TranscodeJob tj = (TranscodeJob) converting.get(i);
        if (tj.getJobID() == jobID)
          return tj;
      }
    }
    synchronized (waitingForAbsolution)
    {
      for (int i = 0; i < waitingForAbsolution.size(); i++)
      {
        TranscodeJob tj = (TranscodeJob) waitingForAbsolution.get(i);
        if (tj.getJobID() == jobID)
          return tj;
      }
    }
    return null;
  }

  public int getJobStatusCode(int jobID)
  {
    TranscodeJob tj = getJobForID(jobID);
    return (tj == null) ? -1 : tj.getJobState();
  }

  public MediaFile getJobSourceFile(int jobID)
  {
    TranscodeJob tj = getJobForID(jobID);
    return (tj == null) ? null : tj.getMediaFile();
  }

  public java.io.File getJobDestFile(int jobID)
  {
    TranscodeJob tj = getJobForID(jobID);
    return (tj == null) ? null : tj.getDestFile();
  }

  public String getJobFormat(int jobID)
  {
    TranscodeJob tj = getJobForID(jobID);
    return (tj == null) ? "" : tj.getTargetFormatName();
  }

  public float getJobPercentComplete(int jobID)
  {
    TranscodeJob tj = getJobForID(jobID);
    return (tj == null) ? 0 : tj.getPercentComplete();
  }

  public boolean getJobShouldKeepOriginal(int jobID)
  {
    TranscodeJob tj = getJobForID(jobID);
    return (tj == null) ? false : !tj.shouldReplaceOriginal();
  }

  public long getJobClipStartTime(int jobID)
  {
    TranscodeJob tj = getJobForID(jobID);
    return (tj == null) ? 0 : tj.getClipStartTime();
  }

  public long getJobClipDuration(int jobID)
  {
    TranscodeJob tj = getJobForID(jobID);
    return (tj == null) ? 0 : tj.getClipDuration();
  }

  public int[] getTranscodeJobIDs()
  {
    java.util.ArrayList rv = new java.util.ArrayList();
    synchronized (waitingForConversion)
    {
      for (int i = 0; i < waitingForConversion.size(); i++)
      {
        TranscodeJob tj = (TranscodeJob) waitingForConversion.get(i);
        rv.add(new Integer(tj.getJobID()));
      }
    }
    synchronized (converting)
    {
      for (int i = 0; i < converting.size(); i++)
      {
        TranscodeJob tj = (TranscodeJob) converting.get(i);
        rv.add(new Integer(tj.getJobID()));
      }
    }
    synchronized (waitingForAbsolution)
    {
      for (int i = 0; i < waitingForAbsolution.size(); i++)
      {
        TranscodeJob tj = (TranscodeJob) waitingForAbsolution.get(i);
        rv.add(new Integer(tj.getJobID()));
      }
    }
    int[] irv = new int[rv.size()];
    for (int i = 0; i < rv.size(); i++)
      irv[i] = ((Integer) rv.get(i)).intValue();
    return irv;
  }

  public void killTranscoding(MediaFile mf)
  {
    // IT SHOULD FIND THE MF'S JOB IF IT HAS ONE AND THEN KILL THE FILTER GRAPH, IT MUST
    // WAIT UNTIL ITS KILLED SO ITS SURE IT CAN DELETE THE FILES BECAUSE WE DON'T WANT THE SOURCE FILTER
    // TO BE HOLDING THEM OPEN. THEN IT NEEDS TO REMOVE THEM FROM OUR QUEUES ALSO
    boolean changed = false;
    synchronized (converting)
    {
      for (int i = 0; i < converting.size(); i++)
      {
        TranscodeJob tj = (TranscodeJob) converting.get(i);
        if (tj.getMediaFile() == mf)
        {
          if (Sage.DBG) System.out.println("KillTranscoding for:" + mf);
          tj.cleanupCurrentTranscode();
          tj.abandon();
          tj.setJobState(TranscodeJob.DESTROYED);
          converting.removeElementAt(i);
          Sage.removeNode(TRANSCODE_JOB_PROPS + '/' + tj.getJobID());
          changed = true;
          break;
        }
      }
    }
    if (changed)
      kick();
  }

  public boolean doesFileAlwaysRequireTranscoding(MediaFile mf)
  {
    return sage.media.format.MediaFormat.MPEG2_PS.equals(mf.getContainerFormat()) &&
        sage.media.format.MediaFormat.MPEG4X.equals(mf.getPrimaryVideoFormat()) && !mf.isAnyLiveStream();
  }

  public void kick()
  {
    dirty = true;
    synchronized (lock)
    {
      lock.notifyAll();
    }
  }

  private void startConversion(TranscodeJob tj)
  {
    tj.setJobState(TranscodeJob.WAITING);
    converting.add(tj);
  }

  public boolean isValidSourceFormat(sage.media.format.ContainerFormat cf)
  {
    // We can transcode anything without DRM that's not WMV9 or WMALossless
    if (cf == null || cf.isDRMProtected() ||
        //			sage.media.format.MediaFormat.WMV9.equals(cf.getPrimaryVideoFormat()) ||
        sage.media.format.MediaFormat.WMA9LOSSLESS.equals(cf.getPrimaryAudioFormat()))
      return false;
    else
      return true;
  }

  public boolean requiresPower()
  {
    // This is true if there's any transcode jobs are in the queue
    synchronized (this)
    {
    	return (converting.size() > 0 || waitingForConversion.size() > 0);
    }
  }

  // ──────────────────────────────────────────────────────────────────────
  // Automatic AI upscale (Real-ESRGAN ncnn-vulkan) — chained-job helpers.
  //
  // Rule: if the source video height is <= ai_upscale_max_source_height
  // (default 720) AND the target preset's video height is
  // >= ai_upscale_min_target_height (default 1080), the transcode is split
  // into two phases:
  //
  //   Phase 1: bin/sage-ai-upscale.sh source → lossless H.264 intermediate
  //            at the target WxH.
  //   Phase 2: the user's chosen preset, but run against the intermediate
  //            and with any -vf scale=... filter token stripped (the upscale
  //            already happened in phase 1).
  //
  // Config knobs (Sage.properties):
  //   transcoder/ai_upscale_enabled              default true
  //   transcoder/ai_upscale_max_source_height    default 720
  //   transcoder/ai_upscale_min_target_height    default 1080
  //   transcoder/ai_upscale_wrapper              default ${install}/bin/sage-ai-upscale.sh
  //   transcoder/ai_upscale_binary               default /usr/local/bin/realesrgan-ncnn-vulkan
  //   transcoder/ai_upscale_model                default realesr-general-x4v3
  //   transcoder/ai_upscale_chunk_frames         default 500
  //   transcoder/ai_upscale_intermediate_dir     default ${STATE_DIR}/transcoder/intermediates
  //                                              (falls back to java.io.tmpdir)
  //   transcoder/ai_upscale_require_vulkan        default true
  //   transcoder/ai_upscale_probe_timeout_secs    default 60
  //
  // No-GPU degradation: when ai_upscale_require_vulkan is true (default) the
  // chained job only engages if a Vulkan device is actually usable — probed
  // once per JVM via `sage-ai-upscale.sh --probe`. On a host with no Vulkan
  // GPU the rule declines and the transcode falls back to the preset's own
  // (Lanczos) scale instead of failing mid-job.
  // ──────────────────────────────────────────────────────────────────────

  private static final java.util.regex.Pattern SCALE_FILTER_PATTERN =
      java.util.regex.Pattern.compile("scale(?:_npp)?=(\\d+):(\\d+)(?::[^,\\s]*)?");

  /**
   * Parse the target output height from a raw ffmpeg preset args string by
   * looking for the first `-vf scale=W:H...` (or `scale_npp=W:H...`) token.
   * Returns -1 if no scale filter is present (e.g. a copy/transmux preset).
   */
  public static int parseTargetHeightFromPresetArgs(String args)
  {
    if (args == null || args.length() == 0) return -1;
    java.util.regex.Matcher m = SCALE_FILTER_PATTERN.matcher(args);
    if (!m.find()) return -1;
    try { return Integer.parseInt(m.group(2)); } catch (NumberFormatException e) { return -1; }
  }

  /** Parse target width too (for the wrapper invocation). Returns -1 if absent. */
  public static int parseTargetWidthFromPresetArgs(String args)
  {
    if (args == null || args.length() == 0) return -1;
    java.util.regex.Matcher m = SCALE_FILTER_PATTERN.matcher(args);
    if (!m.find()) return -1;
    try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { return -1; }
  }

  /**
   * Decide whether to activate the AI upscale chained-job path for a
   * particular source/target pair. Honors the master enable flag and the
   * source/target height thresholds.
   */
  public static boolean shouldAutoAiUpscale(int sourceHeight, int targetHeight)
  {
    if (!Sage.getBoolean("transcoder/ai_upscale_enabled", true)) return false;
    int maxSrc = Sage.getInt("transcoder/ai_upscale_max_source_height", 720);
    int minTgt = Sage.getInt("transcoder/ai_upscale_min_target_height", 1080);
    if (sourceHeight <= 0 || targetHeight <= 0) return false;
    if (!(sourceHeight <= maxSrc && targetHeight >= minTgt)) return false;
    // Only engage the chained AI-upscale job if a Vulkan device is actually
    // usable; otherwise decline so the caller runs a plain (Lanczos) preset
    // transcode rather than spawning an upscaler that would fail mid-job.
    if (!aiUpscaleDeviceAvailable())
    {
      if (Sage.DBG) System.out.println("Ministry: AI upscale rule matched (srcH=" + sourceHeight
          + " tgtH=" + targetHeight + ") but no usable Vulkan device — using plain transcode");
      return false;
    }
    return true;
  }

  /** Cached Vulkan-probe result: null=unprobed, TRUE/FALSE once determined. */
  private static volatile Boolean aiUpscaleVulkanOK = null;

  /**
   * One-per-JVM probe: can the AI-upscale wrapper initialize a Vulkan device
   * and upscale a trivial test frame? Result is cached. Gated by
   * {@code transcoder/ai_upscale_require_vulkan} (default true) — set it to
   * false to skip the probe and assume the upscaler is usable.
   */
  public static boolean aiUpscaleDeviceAvailable()
  {
    if (!Sage.getBoolean("transcoder/ai_upscale_require_vulkan", true))
      return true;
    Boolean cached = aiUpscaleVulkanOK;
    if (cached != null) return cached.booleanValue();
    synchronized (Ministry.class)
    {
      if (aiUpscaleVulkanOK != null) return aiUpscaleVulkanOK.booleanValue();
      boolean ok = probeAiUpscaleVulkan();
      aiUpscaleVulkanOK = Boolean.valueOf(ok);
      if (Sage.DBG)
        System.out.println("Ministry: AI-upscale Vulkan probe -> "
            + (ok ? "AVAILABLE" : "UNAVAILABLE (falling back to plain transcode)"));
      return ok;
    }
  }

  /** Reset the cached Vulkan-probe result so the next call re-probes. */
  public static void resetAiUpscaleProbe() { aiUpscaleVulkanOK = null; }

  private static boolean probeAiUpscaleVulkan()
  {
    Process p = null;
    try
    {
      String wrapper = Sage.get("transcoder/ai_upscale_wrapper", "bin/sage-ai-upscale.sh");
      String binary  = Sage.get("transcoder/ai_upscale_binary", "/usr/local/bin/realesrgan-ncnn-vulkan");
      String model   = Sage.get("transcoder/ai_upscale_model", "realesr-general-x4v3");
      java.util.ArrayList<String> argv = new java.util.ArrayList<String>();
      argv.add("/bin/bash");
      argv.add(wrapper);
      argv.add("--probe");
      argv.add("--realesrgan"); argv.add(binary);
      argv.add("--model"); argv.add(model);
      if (Sage.DBG) System.out.println("Ministry: AI-upscale probe: " + argv);
      p = new ProcessBuilder(argv).redirectErrorStream(true).start();
      // Drain output so a full pipe can't block the child.
      java.io.BufferedReader r = new java.io.BufferedReader(
          new java.io.InputStreamReader(p.getInputStream()));
      try { while (r.readLine() != null) { } }
      finally { try { r.close(); } catch (java.io.IOException ie) {} }
      int timeoutSec = Sage.getInt("transcoder/ai_upscale_probe_timeout_secs", 60);
      if (!p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS))
      {
        p.destroyForcibly();
        if (Sage.DBG) System.out.println("Ministry: AI-upscale probe timed out after " + timeoutSec + "s");
        return false;
      }
      return p.exitValue() == 0;
    }
    catch (Throwable t)
    {
      if (p != null) { try { p.destroyForcibly(); } catch (Throwable ig) {} }
      if (Sage.DBG) System.out.println("Ministry: AI-upscale probe failed: " + t);
      return false;
    }
  }

  /**
   * Build the intermediate file path for a given job id. Directory is
   * created on demand.
   */
  public static java.io.File makeAiUpscaleIntermediate(int jobID, int segment)
  {
    String dir = Sage.get("transcoder/ai_upscale_intermediate_dir", "");
    if (dir.length() == 0)
    {
      String stateDir = System.getenv("STATE_DIR");
      if (stateDir != null && stateDir.length() > 0)
        dir = stateDir + "/transcoder/intermediates";
      else
        dir = System.getProperty("java.io.tmpdir", "/tmp") + "/sage-ai-intermediates";
    }
    java.io.File d = new java.io.File(dir);
    if (!d.isDirectory()) d.mkdirs();
    return new java.io.File(d, "ai_" + jobID + "_seg" + segment + ".mkv");
  }

  /**
   * Spawn the phase-1 AI upscale wrapper. Returns the live Process; caller
   * monitors waitFor() / exitValue() and chains phase 2 on success.
   */
  public static Process spawnAiUpscaleProcess(java.io.File input,
      java.io.File intermediate, int targetWidth, int targetHeight) throws java.io.IOException
  {
    String wrapper = Sage.get("transcoder/ai_upscale_wrapper", "bin/sage-ai-upscale.sh");
    String model = Sage.get("transcoder/ai_upscale_model", "realesr-general-x4v3");
    String binary = Sage.get("transcoder/ai_upscale_binary", "/usr/local/bin/realesrgan-ncnn-vulkan");
    int chunk = Sage.getInt("transcoder/ai_upscale_chunk_frames", 500);
    java.util.ArrayList<String> argv = new java.util.ArrayList<String>();
    argv.add("/bin/bash");
    argv.add(wrapper);
    argv.add("--input"); argv.add(input.getAbsolutePath());
    argv.add("--output"); argv.add(intermediate.getAbsolutePath());
    argv.add("--width"); argv.add(Integer.toString(targetWidth));
    argv.add("--height"); argv.add(Integer.toString(targetHeight));
    argv.add("--model"); argv.add(model);
    argv.add("--chunk-frames"); argv.add(Integer.toString(chunk));
    argv.add("--realesrgan"); argv.add(binary);
    if (Sage.DBG) System.out.println("Ministry: spawning AI upscale: " + argv);
    return Runtime.getRuntime().exec(argv.toArray(new String[0]));
  }

  /**
   * Produce a copy of {@code targetFormat} suitable for phase 2 of a chained
   * AI upscale job: any `-vf scale=W:H[:flags=...]` (or scale_npp) filter
   * token is removed from the raw cmdline metadata since the upscale already
   * happened in phase 1. If nothing else remains in the -vf chain, the whole
   * `-vf <expr>` pair is dropped.
   */
  public static sage.media.format.ContainerFormat stripScaleFilterForPhase2(
      sage.media.format.ContainerFormat targetFormat)
  {
    if (targetFormat == null) return targetFormat;
    String raw = targetFormat.getMetadataProperty(sage.media.format.MediaFormat.META_RAW_FFMPEG_CMDLINE);
    if (raw == null || raw.length() == 0) return targetFormat;
    String rewritten = stripScaleFilterFromArgs(raw);
    if (rewritten.equals(raw)) return targetFormat;
    // Clone the format and overwrite the raw cmdline.
    sage.media.format.ContainerFormat copy = (sage.media.format.ContainerFormat)
        sage.media.format.ContainerFormat.buildFormatFromString(targetFormat.getFullPropertyString(true));
    copy.addMetadata(sage.media.format.MediaFormat.META_RAW_FFMPEG_CMDLINE, rewritten, true);
    if (Sage.DBG) System.out.println("Ministry: phase-2 args (scale stripped): " + rewritten);
    return copy;
  }

  /** Strip any scale=/scale_npp= token from a -vf chain, dropping -vf entirely if the chain becomes empty. */
  static String stripScaleFilterFromArgs(String args)
  {
    String[] toks = args.split("\\s+");
    java.util.ArrayList<String> out = new java.util.ArrayList<String>(toks.length);
    for (int i = 0; i < toks.length; i++)
    {
      if ("-vf".equals(toks[i]) && i + 1 < toks.length)
      {
        String chain = toks[i + 1];
        // Split chain on ',' (filter separator), drop scale=/scale_npp= entries.
        String[] parts = chain.split(",");
        java.util.ArrayList<String> keep = new java.util.ArrayList<String>(parts.length);
        for (String p : parts)
        {
          String pt = p.trim();
          if (pt.startsWith("scale=") || pt.startsWith("scale_npp=")) continue;
          if (pt.length() > 0) keep.add(pt);
        }
        if (keep.isEmpty())
        {
          // drop both -vf and its arg
          i++;
          continue;
        }
        out.add("-vf");
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < keep.size(); j++) { if (j > 0) sb.append(','); sb.append(keep.get(j)); }
        out.add(sb.toString());
        i++;
      }
      else
      {
        out.add(toks[i]);
      }
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < out.size(); i++) { if (i > 0) sb.append(' '); sb.append(out.get(i)); }
    return sb.toString();
  }

  private Object lock = new Object();
  private java.util.Vector waitingForConversion = new java.util.Vector();
  private java.util.Vector converting = new java.util.Vector();
  private java.util.Vector waitingForAbsolution = new java.util.Vector();

  private boolean dirty = false;
  private boolean alive;
  private Thread ministryThread;
  private int jobCounter;
  private Object idLock = new Object();
}
