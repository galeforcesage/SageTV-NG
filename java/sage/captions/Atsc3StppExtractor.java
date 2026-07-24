/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */
package sage.captions;

import sage.Sage;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Extracts {@link CaptionEvent}s from an ATSC 3.0 recording's embedded
 * STPP/IMSC1 (TTML) caption stream.
 *
 * <p>ATSC 3.0 broadcasts (e.g. via the HDHomeRun Flex 4K) carry a clean STPP
 * data stream as its own PID inside the MPEG-TS. Each packet on that stream
 * is a complete {@code <tt>} TTML document covering a short (~2s) rolling
 * window, with character-by-character cue timing that has to be coalesced
 * into sentence-level cues (see {@link CaptionEvent#coalesce(List)}).
 *
 * <p>Pipeline: detect the STPP stream via {@code ffprobe} -&gt; extract the
 * raw elementary stream via {@code ffmpeg -c copy -f data} -&gt; split the
 * byte stream on {@code <?xml} document boundaries -&gt; parse each TTML
 * document with the JDK's built-in {@code javax.xml.parsers}/{@code org.w3c.dom}
 * (no third-party XML libraries) -&gt; normalize the (absolute, wall-clock-like)
 * TTML timestamps to be relative to the first cue -&gt; coalesce.
 */
public final class Atsc3StppExtractor
{
  private Atsc3StppExtractor() {}

  /** Result of probing a file for an STPP data stream. */
  public static final class StppStream
  {
    public final int streamIndex;
    public final String language;

    StppStream(int streamIndex, String language)
    {
      this.streamIndex = streamIndex;
      this.language = language;
    }
  }

  /**
   * Runs the full extraction pipeline: detect, extract, parse, normalize,
   * coalesce. Returns an empty list (never null) if no STPP stream is found
   * or extraction/parsing yields no usable cues.
   *
   * @param input      the recording (MPEG-TS) to extract from
   * @param ffmpegPath  path to the ffmpeg binary (used both for probing-adjacent
   *                    lookup and for the raw stream extraction)
   */
  public static List<CaptionEvent> extract(File input, String ffmpegPath)
  {
    List<CaptionEvent> events = new ArrayList<>();
    StppStream stream = detectStppStream(input, ffmpegPath);
    if (stream == null)
    {
      return events;
    }

    File raw = null;
    try
    {
      raw = File.createTempFile("stpp_raw_", ".bin");
      if (!extractRawStream(input, ffmpegPath, stream.streamIndex, raw))
      {
        return events;
      }

      List<CaptionEvent> flat = parseTtmlPackets(raw, stream.language);
      if (flat.isEmpty())
      {
        return events;
      }

      normalizeTimestamps(flat);
      java.util.Collections.sort(flat);
      events = CaptionEvent.coalesce(flat);
    }
    catch (IOException e)
    {
      if (Sage.DBG) System.out.println("Atsc3StppExtractor: extraction failed for " + input + ": " + e);
    }
    finally
    {
      if (raw != null) raw.delete();
    }
    return events;
  }

  // ── Detection ────────────────────────────────────────────────────────

  /**
   * Probes {@code input} for an STPP (IMSC1/TTML) data stream using
   * ffprobe. Returns {@code null} if ffprobe is unavailable, the probe
   * fails, or no STPP stream is present.
   */
  public static StppStream detectStppStream(File input, String ffmpegPath)
  {
    String ffprobe = resolveFfprobePath(ffmpegPath);
    if (ffprobe == null)
    {
      if (Sage.DBG) System.out.println("Atsc3StppExtractor: no ffprobe binary available");
      return null;
    }

    String json = runAndCapture(new String[] {
        ffprobe, "-v", "error",
        "-show_streams", "-select_streams", "d",
        "-of", "json",
        input.getAbsolutePath()
    });
    if (json == null || json.length() < 16) return null;

    int searchFrom = 0;
    while (true)
    {
      int s = json.indexOf("\"index\"", searchFrom);
      if (s < 0) break;
      int next = json.indexOf("\"index\"", s + 1);
      String block = (next < 0) ? json.substring(s) : json.substring(s, next);
      searchFrom = (next < 0) ? json.length() : next;

      String tag = jsonStr(block, "codec_tag_string");
      String codecName = jsonStr(block, "codec_name");
      boolean isStpp = (tag != null && tag.toUpperCase(Locale.ROOT).contains("STPP")) ||
          (codecName != null && codecName.toUpperCase(Locale.ROOT).contains("STPP"));
      if (isStpp)
      {
        String idxStr = jsonStr(block, "index");
        String lang = jsonStr(block, "language");
        try
        {
          int idx = Integer.parseInt(idxStr.trim());
          if (Sage.DBG) System.out.println("Atsc3StppExtractor: found STPP stream index=" + idx + " lang=" + lang);
          return new StppStream(idx, lang == null ? "und" : lang);
        }
        catch (Exception ignore) {}
      }
    }
    return null;
  }

  private static String resolveFfprobePath(String ffmpegPath)
  {
    String configured = Sage.get("caption_extraction/ffprobe_path", "");
    if (configured != null && !configured.isEmpty() && new File(configured).canExecute())
      return configured;

    String sibling = siblingFfprobe(ffmpegPath);
    String[] candidates = { sibling, "/usr/bin/ffprobe", "/usr/local/bin/ffprobe" };
    for (String c : candidates)
    {
      if (c != null && new File(c).canExecute()) return c;
    }
    if (which("ffprobe")) return "ffprobe";
    return null;
  }

  private static String siblingFfprobe(String ffmpegPath)
  {
    if (ffmpegPath == null) return null;
    File f = new File(ffmpegPath);
    File dir = f.getParentFile();
    if (dir == null) return null;
    String exe = ffmpegPath.toLowerCase(Locale.ROOT).endsWith(".exe") ? "ffprobe.exe" : "ffprobe";
    return new File(dir, exe).getPath();
  }

  private static boolean which(String binary)
  {
    String path = System.getenv("PATH");
    if (path == null) return false;
    for (String dir : path.split(File.pathSeparator))
    {
      if (new File(dir, binary).canExecute()) return true;
    }
    return false;
  }

  /** Runs a process to completion and returns its combined stdout (stderr is discarded via -v error). */
  private static String runAndCapture(String[] cmd)
  {
    try
    {
      ProcessBuilder pb = new ProcessBuilder(cmd);
      Process p = pb.start();
      StringBuilder out = new StringBuilder();
      try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)))
      {
        String line;
        while ((line = r.readLine()) != null) out.append(line).append('\n');
      }
      // Drain stderr too so the process never blocks on a full pipe.
      try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8)))
      {
        while (r.readLine() != null) {}
      }
      p.waitFor();
      return out.toString();
    }
    catch (IOException | InterruptedException e)
    {
      if (Sage.DBG) System.out.println("Atsc3StppExtractor: probe exec failed: " + e);
      return null;
    }
  }

  /** Tiny JSON value extractor for {@code "key": "value"} or {@code "key": value}. First match wins. */
  private static String jsonStr(String json, String key)
  {
    String k = "\"" + key + "\"";
    int i = json.indexOf(k);
    if (i < 0) return null;
    int colon = json.indexOf(':', i + k.length());
    if (colon < 0) return null;
    int p = colon + 1;
    while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
    if (p >= json.length()) return null;
    char c = json.charAt(p);
    if (c == '"')
    {
      int end = json.indexOf('"', p + 1);
      if (end < 0) return null;
      return json.substring(p + 1, end);
    }
    int end = p;
    while (end < json.length())
    {
      char ec = json.charAt(end);
      if (ec == ',' || ec == '\n' || ec == '\r' || ec == '}' || ec == ']') break;
      end++;
    }
    return json.substring(p, end).trim();
  }

  // ── Raw stream extraction ────────────────────────────────────────────

  /**
   * Extracts the raw STPP elementary stream (concatenated TTML documents,
   * verbatim) via {@code ffmpeg -map 0:<streamIndex> -c copy -f data}.
   */
  private static boolean extractRawStream(File input, String ffmpegPath, int streamIndex, File out)
  {
    List<String> cmd = new ArrayList<>();
    cmd.add(ffmpegPath);
    cmd.add("-hide_banner");
    cmd.add("-loglevel");
    cmd.add("error");
    cmd.add("-y");
    cmd.add("-i");
    cmd.add(input.getAbsolutePath());
    cmd.add("-map");
    cmd.add("0:" + streamIndex);
    cmd.add("-c");
    cmd.add("copy");
    cmd.add("-f");
    cmd.add("data");
    cmd.add(out.getAbsolutePath());

    if (Sage.DBG) System.out.println("Atsc3StppExtractor: " + String.join(" ", cmd));
    try
    {
      ProcessBuilder pb = new ProcessBuilder(cmd);
      pb.redirectErrorStream(true);
      Process proc = pb.start();
      StringBuilder err = new StringBuilder();
      try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8)))
      {
        String line;
        while ((line = r.readLine()) != null)
          if (err.length() < 4096) err.append(line).append('\n');
      }
      int rc = proc.waitFor();
      if (rc != 0)
      {
        if (Sage.DBG) System.out.println("Atsc3StppExtractor: ffmpeg exit=" + rc + "\n" + err);
        return false;
      }
      return out.isFile() && out.length() > 0;
    }
    catch (IOException | InterruptedException e)
    {
      if (Sage.DBG) System.out.println("Atsc3StppExtractor: ffmpeg extraction error: " + e);
      return false;
    }
  }

  // ── TTML parsing ─────────────────────────────────────────────────────

  /**
   * Splits the raw byte stream on {@code <?xml} document boundaries and
   * parses each fragment as an independent TTML document, flattening every
   * {@code <p>} cue found into a chronological list. Timestamps at this
   * stage are still the raw (absolute) values found in the TTML; call
   * {@link #normalizeTimestamps(List)} afterwards.
   */
  private static List<CaptionEvent> parseTtmlPackets(File raw, String fallbackLanguage) throws IOException
  {
    List<CaptionEvent> out = new ArrayList<>();
    byte[] bytes = Files.readAllBytes(raw.toPath());
    String content = new String(bytes, StandardCharsets.UTF_8);

    List<String> docs = splitXmlDocuments(content);

    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    // These are trusted, locally-produced documents, but harden against XXE
    // regardless since we're parsing arbitrary broadcast-supplied bytes.
    try
    {
      dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    }
    catch (Exception ignore) {}
    dbf.setNamespaceAware(false);
    dbf.setExpandEntityReferences(false);

    int parsedDocs = 0;
    for (String doc : docs)
    {
      try
      {
        DocumentBuilder builder = dbf.newDocumentBuilder();
        Document dom = builder.parse(new InputSource(new java.io.StringReader(doc)));
        parseOneDocument(dom, fallbackLanguage, out);
        parsedDocs++;
      }
      catch (Exception e)
      {
        // Individual packets can be truncated (e.g. the very last one if the
        // recording/extraction was cut mid-packet); skip and keep going.
        if (Sage.DBG) System.out.println("Atsc3StppExtractor: skipping malformed TTML packet: " + e);
      }
    }
    if (Sage.DBG) System.out.println("Atsc3StppExtractor: parsed " + parsedDocs + "/" + docs.size() +
        " TTML packets, " + out.size() + " raw <p> cues");
    return out;
  }

  /** Splits on {@code <?xml} boundaries, re-attaching the delimiter to each fragment. */
  static List<String> splitXmlDocuments(String content)
  {
    List<String> out = new ArrayList<>();
    String marker = "<?xml";
    int start = content.indexOf(marker);
    while (start >= 0)
    {
      int next = content.indexOf(marker, start + marker.length());
      String frag = (next < 0) ? content.substring(start) : content.substring(start, next);
      String trimmed = frag.trim();
      if (!trimmed.isEmpty()) out.add(trimmed);
      start = next;
    }
    return out;
  }

  private static void parseOneDocument(Document dom, String fallbackLanguage, List<CaptionEvent> out)
  {
    Element root = dom.getDocumentElement();
    if (root == null) return;
    String lang = root.getAttribute("xml:lang");
    if (lang == null || lang.isEmpty()) lang = fallbackLanguage;
    lang = normalizeLanguage(lang);
    // STPP has no CC1/CC2/708-service concept — captions are simply
    // multiplexed per language — so the uppercased language tag doubles as
    // the CaptionEvent "service", giving SrtCaptionWriter.writeGrouped a
    // uniform way to route both STPP and 608/708 tracks to sidecar files.
    String service = lang.toUpperCase(Locale.ROOT);

    NodeList pNodes = dom.getElementsByTagName("p");
    for (int i = 0; i < pNodes.getLength(); i++)
    {
      Node n = pNodes.item(i);
      if (!(n instanceof Element)) continue;
      Element p = (Element) n;
      String beginAttr = p.getAttribute("begin");
      String endAttr = p.getAttribute("end");
      if (beginAttr == null || beginAttr.isEmpty()) continue;

      Double begin = parseTtmlClockTime(beginAttr);
      Double end = (endAttr != null && !endAttr.isEmpty()) ? parseTtmlClockTime(endAttr) : null;
      if (begin == null) continue;
      if (end == null) end = begin;

      String region = p.getAttribute("region");
      if (region != null && region.isEmpty()) region = null;

      String text = extractPlainText(p);
      if (text == null || text.trim().isEmpty()) continue;

      out.add(CaptionEvent.builder()
          .language(lang)
          .beginSeconds(begin)
          .endSeconds(end)
          .text(text)
          .region(region)
          .service(service)
          .build());
    }
  }

  /** Maps a TTML {@code xml:lang} value (e.g. "ENG") down to a lowercase language tag (e.g. "eng"). */
  private static String normalizeLanguage(String lang)
  {
    if (lang == null || lang.isEmpty()) return "und";
    return lang.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * Parses a TTML clock-time value in {@code H+:MM:SS.mmm} form (hours may
   * exceed two digits, as seen in real broadcast STPP streams using an
   * absolute/wall-clock-like epoch) into total seconds. Returns {@code null}
   * if the value can't be parsed.
   */
  static Double parseTtmlClockTime(String s)
  {
    if (s == null) return null;
    s = s.trim();
    String[] parts = s.split(":");
    if (parts.length != 3) return null;
    try
    {
      long hours = Long.parseLong(parts[0].trim());
      long minutes = Long.parseLong(parts[1].trim());
      double seconds = Double.parseDouble(parts[2].trim());
      return hours * 3600.0 + minutes * 60.0 + seconds;
    }
    catch (NumberFormatException e)
    {
      return null;
    }
  }

  /**
   * Recursively extracts plain text from a {@code <p>} element: text nodes
   * are appended verbatim, {@code <br/>} becomes a newline, other elements
   * (e.g. {@code <span>}) are descended into but contribute no markup of
   * their own. Each resulting line is trimmed of leading/trailing
   * whitespace and runs of internal whitespace are collapsed.
   */
  static String extractPlainText(Element p)
  {
    StringBuilder sb = new StringBuilder();
    appendText(p, sb);
    String[] lines = sb.toString().split("\n", -1);
    StringBuilder cleaned = new StringBuilder();
    for (int i = 0; i < lines.length; i++)
    {
      String line = lines[i].replaceAll("[ \\t]+", " ").trim();
      if (line.isEmpty()) continue;
      if (cleaned.length() > 0) cleaned.append('\n');
      cleaned.append(line);
    }
    return cleaned.toString();
  }

  private static void appendText(Node node, StringBuilder sb)
  {
    NodeList children = node.getChildNodes();
    for (int i = 0; i < children.getLength(); i++)
    {
      Node child = children.item(i);
      switch (child.getNodeType())
      {
        case Node.TEXT_NODE:
        case Node.CDATA_SECTION_NODE:
          sb.append(child.getNodeValue());
          break;
        case Node.ELEMENT_NODE:
          String tag = child.getNodeName();
          if ("br".equalsIgnoreCase(tag))
          {
            sb.append('\n');
          }
          else
          {
            appendText(child, sb);
          }
          break;
        default:
          break;
      }
    }
  }

  // ── Timestamp normalization ──────────────────────────────────────────

  /**
   * Normalizes absolute/wall-clock-like TTML timestamps to be relative to
   * the media start: subtracts the earliest {@code begin} value found
   * across every event from every event's begin/end, in place, so the
   * first cue starts at {@code t=0}.
   */
  static void normalizeTimestamps(List<CaptionEvent> events)
  {
    if (events.isEmpty()) return;
    applyEpochOffset(events, findMinBegin(events));
  }

  /** Returns the earliest {@code begin} value across {@code events}, or 0 if empty. */
  static double findMinBegin(List<CaptionEvent> events)
  {
    double minBegin = Double.MAX_VALUE;
    for (CaptionEvent e : events)
    {
      if (e.getBeginSeconds() < minBegin) minBegin = e.getBeginSeconds();
    }
    return (minBegin == Double.MAX_VALUE) ? 0.0 : minBegin;
  }

  /**
   * Subtracts a fixed {@code offset} (seconds) from every event's begin/end,
   * in place, rebuilding each immutable {@link CaptionEvent}. Unlike
   * {@link #normalizeTimestamps(List)} the offset is supplied by the caller
   * rather than derived from this batch, so incremental (tail-only) extraction
   * can reuse the epoch anchor frozen on the very first pass and keep
   * normalized timestamps identical to a full rescan.
   */
  static void applyEpochOffset(List<CaptionEvent> events, double offset)
  {
    if (events.isEmpty() || offset == 0.0) return;
    for (int i = 0; i < events.size(); i++)
    {
      CaptionEvent e = events.get(i);
      events.set(i, CaptionEvent.builder()
          .language(e.getLanguage())
          .beginSeconds(e.getBeginSeconds() - offset)
          .endSeconds(e.getEndSeconds() - offset)
          .text(e.getText())
          .region(e.getRegion())
          .service(e.getService())
          .build());
    }
  }

  // ── Incremental / live extraction ────────────────────────────────────

  /**
   * Mutable per-recording cursor + accumulator that lets an in-progress
   * recording be re-scanned incrementally (cost proportional to newly-arrived
   * data, not total file length) while producing exactly the same finalized
   * cues a single full {@link #extract(File, String)} would.
   *
   * <p>Not thread-safe: a single {@code LiveExtractor} owns one instance and
   * calls {@link #extractIncremental} serially.
   */
  public static final class StppIncrementalState
  {
    // Cached stream detection (done once on the first pass).
    int streamIndex = -1;
    String language = "und";
    boolean detected;
    boolean noStream;

    // The frozen normalization anchor: the minimum raw TTML begin seen on the
    // first non-empty pass. Reused verbatim for every later pass so a cue's
    // normalized time is identical regardless of which window produced it.
    double epochOffsetSeconds;
    boolean epochSet;

    // Where to resume the next ffmpeg window (normalized seconds). Set to the
    // begin of the earliest still-open (provisional) sentence so that sentence
    // is always re-derived from its start; a further overlap guard is applied
    // on top when seeking.
    double resumeSeconds;

    // All cues finalized (closed) so far, in emit order, plus their dedupe keys.
    final List<CaptionEvent> finalizedCues = new ArrayList<>();
    final java.util.Set<String> emittedKeys = new java.util.HashSet<>();

    /** True once an STPP stream has been confirmed absent for this file. */
    public boolean hasNoStream() { return noStream; }

    /** Immutable snapshot of every finalized cue emitted so far. */
    public List<CaptionEvent> finalizedSnapshot()
    {
      return new ArrayList<>(finalizedCues);
    }
  }

  /** Result of one incremental pass. */
  public static final class IncrementalResult
  {
    /** Cues newly finalized by this pass (never re-emitted on later passes). */
    public final List<CaptionEvent> newlyFinalized;
    /**
     * The currently in-progress (provisional) cue(s) — one per track — whose
     * text/end may still grow on a later pass. Safe to display but must be
     * treated as replaceable, not appended to a persistent store.
     */
    public final List<CaptionEvent> provisionalTail;

    IncrementalResult(List<CaptionEvent> newlyFinalized, List<CaptionEvent> provisionalTail)
    {
      this.newlyFinalized = newlyFinalized;
      this.provisionalTail = provisionalTail;
    }
  }

  /**
   * Runs one incremental extraction pass against the (growing) recording,
   * returning only the cues that changed since the previous pass and advancing
   * {@code state}. Detection happens once; subsequent passes extract just a
   * time window ({@code -ss resume-overlap}) of the STPP stream, so per-pass
   * cost stays roughly constant instead of growing with the recording.
   *
   * @return newly-finalized cues plus the current provisional tail; empty (never
   *         null) result when there is no STPP stream or nothing new parsed.
   */
  public static IncrementalResult extractIncremental(File input, String ffmpegPath, StppIncrementalState state)
  {
    IncrementalResult empty = new IncrementalResult(
        java.util.Collections.<CaptionEvent>emptyList(),
        java.util.Collections.<CaptionEvent>emptyList());

    if (!state.detected)
    {
      StppStream s = detectStppStream(input, ffmpegPath);
      state.detected = true;
      if (s == null)
      {
        state.noStream = true;
        return empty;
      }
      state.streamIndex = s.streamIndex;
      state.language = s.language;
    }
    if (state.noStream) return empty;

    double overlap = Math.max(0.0, Sage.getFloat("caption_extraction/stpp_window_overlap_seconds", 4.0f));
    double seekSeconds = Math.max(0.0, state.resumeSeconds - overlap);

    File raw = null;
    try
    {
      raw = File.createTempFile("stpp_inc_", ".bin");
      if (!extractRawStreamWindow(input, ffmpegPath, state.streamIndex, seekSeconds, raw))
      {
        return empty;
      }

      List<CaptionEvent> flat = parseTtmlPackets(raw, state.language);
      if (flat.isEmpty()) return empty;

      return processWindowRaw(flat, state);
    }
    catch (IOException e)
    {
      if (Sage.DBG) System.out.println("Atsc3StppExtractor: incremental extraction failed for " + input + ": " + e);
      return empty;
    }
    finally
    {
      if (raw != null) raw.delete();
    }
  }

  /**
   * Core of one incremental pass, factored out from the ffmpeg plumbing so it
   * is unit-testable without a broadcast stream: freezes the epoch anchor on
   * the first non-empty batch, applies that frozen offset (so normalized times
   * match a full rescan), coalesces, and reconciles against {@code state}.
   *
   * @param rawWindowCues raw (un-normalized) cues parsed from this window's
   *                      TTML packets, in any order
   */
  static IncrementalResult processWindowRaw(List<CaptionEvent> rawWindowCues, StppIncrementalState state)
  {
    IncrementalResult empty = new IncrementalResult(
        java.util.Collections.<CaptionEvent>emptyList(),
        java.util.Collections.<CaptionEvent>emptyList());
    if (rawWindowCues == null || rawWindowCues.isEmpty()) return empty;

    List<CaptionEvent> flat = new ArrayList<>(rawWindowCues);
    // Freeze the epoch anchor on the first non-empty pass, then always apply
    // that same offset so normalized times match a full rescan.
    if (!state.epochSet)
    {
      state.epochOffsetSeconds = findMinBegin(flat);
      state.epochSet = true;
    }
    applyEpochOffset(flat, state.epochOffsetSeconds);
    java.util.Collections.sort(flat);

    List<CaptionEvent> coalesced = CaptionEvent.coalesce(flat);
    return reconcile(coalesced, state);
  }

  /**
   * Splits a window's coalesced cues into finalized vs provisional-tail (the
   * last cue per track is provisional — it may still roll up), dedupes closed
   * cues against what's already been emitted, appends the genuinely-new ones to
   * {@code state}, and advances {@code state.resumeSeconds} to the earliest
   * provisional tail begin so the next window re-derives every open sentence
   * from its start.
   */
  private static IncrementalResult reconcile(List<CaptionEvent> coalesced, StppIncrementalState state)
  {
    // Identify the last cue per track (provisional tails).
    java.util.Map<String, CaptionEvent> tailByTrack = new java.util.LinkedHashMap<>();
    for (CaptionEvent e : coalesced)
    {
      tailByTrack.put(trackKey(e), e); // later cue overwrites earlier ⇒ last per track
    }
    java.util.Set<CaptionEvent> tails = new java.util.HashSet<>(tailByTrack.values());

    List<CaptionEvent> newlyFinalized = new ArrayList<>();
    for (CaptionEvent e : coalesced)
    {
      if (tails.contains(e)) continue; // provisional; do not finalize yet
      String key = keyOf(e);
      if (state.emittedKeys.add(key))
      {
        state.finalizedCues.add(e);
        newlyFinalized.add(e);
      }
    }

    // Safety ceiling: a long-running live buffer must not grow the retained
    // finalized state without bound. Drop oldest cues (and their dedupe keys)
    // once past the cap. Safe because the resume cursor only ever moves forward,
    // so dropped cues are never re-extracted; and a kept recording still gets a
    // complete canonical SRT from the full post-recording pass.
    enforceCeiling(state);

    // Resume from the earliest open sentence (or, if none, the latest finalized end).
    double resume = Double.MAX_VALUE;
    for (CaptionEvent t : tailByTrack.values())
    {
      if (t.getBeginSeconds() < resume) resume = t.getBeginSeconds();
    }
    if (resume == Double.MAX_VALUE)
    {
      for (CaptionEvent e : newlyFinalized)
      {
        if (e.getEndSeconds() > state.resumeSeconds) state.resumeSeconds = e.getEndSeconds();
      }
    }
    else
    {
      state.resumeSeconds = Math.max(state.resumeSeconds, resume);
    }

    List<CaptionEvent> provisional = new ArrayList<>(tailByTrack.values());
    java.util.Collections.sort(provisional);
    return new IncrementalResult(newlyFinalized, provisional);
  }

  private static String trackKey(CaptionEvent e)
  {
    return (e.getLanguage() == null ? "" : e.getLanguage()) + '\u0000' +
        (e.getService() == null ? "" : e.getService()) + '\u0000' +
        (e.getRegion() == null ? "" : e.getRegion());
  }

  /** Stable dedupe identity for a finalized cue (frozen epoch + deterministic coalesce ⇒ reproducible). */
  private static String keyOf(CaptionEvent e)
  {
    long beginMs = Math.round(e.getBeginSeconds() * 1000.0);
    return (e.getService() == null ? "" : e.getService()) + '\u0000' + beginMs + '\u0000' + e.getText();
  }

  /**
   * Safety ceiling on retained finalized cues so an indefinitely-running live
   * incremental state can't grow without bound (~a few hours of TV at typical
   * cue rates). When exceeded, the oldest cues are evicted along with their
   * dedupe keys.
   */
  static final int MAX_FINALIZED_CUES = 10000;

  private static void enforceCeiling(StppIncrementalState state)
  {
    if (MAX_FINALIZED_CUES <= 0) return;
    while (state.finalizedCues.size() > MAX_FINALIZED_CUES)
    {
      CaptionEvent old = state.finalizedCues.remove(0);
      state.emittedKeys.remove(keyOf(old));
    }
  }

  /**
   * Like {@link #extractRawStream} but seeks the input to {@code startSeconds}
   * first ({@code -ss} before {@code -i}), so only the tail of the growing
   * stream is remuxed. STPP packets are self-contained TTML documents carrying
   * their own absolute timestamps, so an imprecise container seek is harmless —
   * the caller dedupes by cue identity.
   */
  private static boolean extractRawStreamWindow(File input, String ffmpegPath, int streamIndex,
      double startSeconds, File out)
  {
    List<String> cmd = new ArrayList<>();
    cmd.add(ffmpegPath);
    cmd.add("-hide_banner");
    cmd.add("-loglevel");
    cmd.add("error");
    cmd.add("-y");
    if (startSeconds > 0.0)
    {
      cmd.add("-ss");
      cmd.add(String.format(Locale.ROOT, "%.3f", startSeconds));
    }
    cmd.add("-i");
    cmd.add(input.getAbsolutePath());
    cmd.add("-map");
    cmd.add("0:" + streamIndex);
    cmd.add("-c");
    cmd.add("copy");
    cmd.add("-f");
    cmd.add("data");
    cmd.add(out.getAbsolutePath());

    if (Sage.DBG) System.out.println("Atsc3StppExtractor: " + String.join(" ", cmd));
    try
    {
      ProcessBuilder pb = new ProcessBuilder(cmd);
      pb.redirectErrorStream(true);
      Process proc = pb.start();
      StringBuilder err = new StringBuilder();
      try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8)))
      {
        String line;
        while ((line = r.readLine()) != null)
          if (err.length() < 4096) err.append(line).append('\n');
      }
      int rc = proc.waitFor();
      if (rc != 0)
      {
        if (Sage.DBG) System.out.println("Atsc3StppExtractor: ffmpeg window exit=" + rc + "\n" + err);
        return false;
      }
      return out.isFile() && out.length() > 0;
    }
    catch (IOException | InterruptedException e)
    {
      if (Sage.DBG) System.out.println("Atsc3StppExtractor: ffmpeg window extraction error: " + e);
      return false;
    }
  }
}
