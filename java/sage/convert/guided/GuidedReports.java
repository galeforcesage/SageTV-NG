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
package sage.convert.guided;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import sage.convert.ConversionPlan;
import sage.convert.ConversionRequest;
import sage.convert.SourceMedia;

/**
 * Formats a {@link Recommendation} into the plain-language strings the STV wizard
 * renders. Kept out of {@link GuidedRecommender} (which stays a pure decision
 * engine) and out of the Catbert layer (which stays a thin bridge) so the wording
 * can be unit-tested without a server. No engine internals (ffmpeg tokens, encoder
 * names, "NVENC", "scale_npp") leak into any string here.
 */
public final class GuidedReports
{
  private GuidedReports() { }

  /**
   * Compact header for the wizard form: just the recommended output, estimated
   * size, and a one-line compatibility status. The "why" rationale is deliberately
   * left out here (it lives in {@link #notesReport}) so the header stays short.
   */
  public static String headlineReport(Recommendation rec, long durationMillis)
  {
    if (rec == null) return "No recommendation is available.";
    StringBuilder sb = new StringBuilder();
    ConversionRequest req = rec.getRequest();
    ConversionPlan plan = rec.getPlan();

    sb.append("Recommended: ").append(plan != null ? plan.getSummary() : describeOutput(req));

    long bytes = rec.getEstimatedBytes();
    if (bytes <= 0 && plan != null && durationMillis > 0) bytes = plan.estimateBytes(durationMillis);
    if (bytes > 0) sb.append('\n').append("Estimated size: ").append(formatBytes(bytes));

    switch (rec.worstSeverity())
    {
      case INCOMPATIBLE:
        sb.append('\n').append("Cannot be produced as-is \u2014 see Compatibility notes.");
        break;
      case UNVERIFIED:
        sb.append('\n').append("Some choices are unverified \u2014 see Compatibility notes.");
        break;
      default:
        sb.append('\n').append("All choices are compatible.");
        break;
    }
    return sb.toString();
  }

  /**
   * The Compatibility-notes panel: the "why this was recommended" rationale
   * followed by any severity-tagged conflicts. Combines what used to sit in the
   * header (the rationale) with the conflict lines so both live in one place.
   */
  public static String notesReport(Recommendation rec)
  {
    if (rec == null) return "No recommendation is available.";
    StringBuilder sb = new StringBuilder();

    if (rec.getRationale() != null && !rec.getRationale().isEmpty())
    {
      sb.append("Why this recommendation:\n");
      for (String why : rec.getRationale()) sb.append("  \u2022 ").append(why).append('\n');
    }

    String[] cl = conflictLines(rec);
    if (cl.length > 0)
    {
      if (sb.length() > 0) sb.append('\n');
      sb.append("Compatibility:\n");
      for (String l : cl) sb.append(l).append('\n');
    }
    else
    {
      if (sb.length() > 0) sb.append('\n');
      sb.append("All choices are compatible.");
    }
    return sb.toString().trim();
  }

  /** Menu 5 — the recommendation the wizard proposes, ready to accept or customize. */
  public static String recommendationReport(Recommendation rec, long durationMillis)
  {
    StringBuilder sb = new StringBuilder();
    if (rec == null) return "No recommendation is available.";
    ConversionRequest req = rec.getRequest();
    ConversionPlan plan = rec.getPlan();

    sb.append("Recommended: ");
    sb.append(plan != null ? plan.getSummary() : describeOutput(req));
    sb.append('\n');

    if (!rec.getRationale().isEmpty())
    {
      sb.append('\n').append("Why:").append('\n');
      for (String why : rec.getRationale()) sb.append("  \u2022 ").append(why).append('\n');
    }

    long bytes = rec.getEstimatedBytes();
    if (bytes <= 0 && plan != null && durationMillis > 0) bytes = plan.estimateBytes(durationMillis);
    if (bytes > 0) sb.append('\n').append("Estimated size: ").append(formatBytes(bytes)).append('\n');

    switch (rec.worstSeverity())
    {
      case INCOMPATIBLE:
        sb.append('\n').append("This combination cannot be produced as-is \u2014 see the conflicts panel.");
        break;
      case UNVERIFIED:
        sb.append('\n').append("Some choices could not be verified for your device \u2014 see the conflicts panel.");
        break;
      default:
        sb.append('\n').append("All choices are compatible.");
        break;
    }
    return sb.toString();
  }

  /** Menu 6 — one compact line per capability group, for the Customize screen headers. */
  public static String[] groupSummaries(Recommendation rec)
  {
    List<String> lines = new ArrayList<String>();
    if (rec == null) return new String[0];
    ConversionRequest req = rec.getRequest();

    lines.add("Video: " + videoLine(req));
    lines.add("Audio: " + audioLine(req));
    lines.add("Container: " + name(req.getContainer()));
    lines.add("Dynamic range: " + name(req.getDynamicRange()));
    lines.add("Frame rate: " + name(req.getFrameRate()));
    lines.add("Subtitles: " + name(req.getSubtitles()));
    lines.add("Quality (CQ): " + (req.getQualityCq() > 0 ? String.valueOf(req.getQualityCq()) : "auto"));
    return lines.toArray(new String[lines.size()]);
  }

  /** Menu 7 — one line per conflict, severity-prefixed; empty when all compatible. */
  public static String[] conflictLines(Recommendation rec)
  {
    List<String> lines = new ArrayList<String>();
    if (rec == null) return new String[0];
    for (Conflict c : rec.getConflicts())
    {
      String tag;
      switch (c.getSeverity())
      {
        case INCOMPATIBLE: tag = "[Blocking] "; break;
        case UNVERIFIED:   tag = "[Unverified] "; break;
        default:           tag = "[Note] "; break;
      }
      lines.add(tag + c.getMessage());
    }
    return lines.toArray(new String[lines.size()]);
  }

  /** Menu 8 — the exact source-vs-output review the user confirms before converting. */
  public static String reviewReport(Recommendation rec, SourceMedia src, long durationMillis)
  {
    StringBuilder sb = new StringBuilder();
    if (rec == null) return "No recommendation is available.";
    ConversionRequest req = rec.getRequest();
    ConversionPlan plan = rec.getPlan();

    sb.append("Source:\n");
    if (src != null)
    {
      sb.append("  ").append(src.getWidth()).append('x').append(src.getHeight());
      if (src.isInterlaced()) sb.append(" interlaced");
      sb.append(", ").append(trimFps(src.getFps())).append(" fps");
      if (src.getVideoCodec() != null) sb.append(", ").append(src.getVideoCodec().toUpperCase(Locale.ROOT));
      if (src.isHdr()) sb.append(", HDR");
      sb.append('\n');
      sb.append("  Audio: ").append(src.getAudioChannels()).append("ch");
      if (src.getAudioCodec() != null) sb.append(' ').append(src.getAudioCodec().toUpperCase(Locale.ROOT));
      sb.append('\n');
    }

    sb.append("\nOutput:\n  ").append(plan != null ? plan.getSummary() : describeOutput(req)).append('\n');

    if (plan != null && !plan.getOperations().isEmpty())
    {
      sb.append("\nWhat will happen:\n");
      for (String op : plan.getOperations()) sb.append("  \u2022 ").append(op).append('\n');
    }

    long bytes = rec.getEstimatedBytes();
    if (bytes <= 0 && plan != null && durationMillis > 0) bytes = plan.estimateBytes(durationMillis);
    if (bytes > 0) sb.append("\nEstimated size: ").append(formatBytes(bytes));

    if (!rec.isBuildable())
      sb.append("\n\nThis conversion cannot run as configured. Resolve the blocking conflict first.");
    return sb.toString();
  }

  private static String videoLine(ConversionRequest req)
  {
    StringBuilder sb = new StringBuilder();
    sb.append(name(req.getVideoCodec()));
    if (req.hasExplicitTargetSize())
      sb.append(' ').append(req.getTargetWidth()).append('x').append(req.getTargetHeight());
    switch (req.getScaling())
    {
      case AI:      sb.append(" (AI upscale)"); break;
      case LANCZOS: sb.append(" (Lanczos scale)"); break;
      default: break;
    }
    return sb.toString();
  }

  private static String audioLine(ConversionRequest req)
  {
    return name(req.getAudioCodec()) + " " + name(req.getAudioLayout())
        + (req.getAudioBitrateKbps() > 0 ? " @ " + req.getAudioBitrateKbps() + " kbps" : "");
  }

  private static String describeOutput(ConversionRequest req)
  {
    return videoLine(req) + " / " + audioLine(req) + " in " + name(req.getContainer());
  }

  private static String name(Enum<?> e) { return e == null ? "auto" : e.name(); }

  private static String trimFps(double fps)
  {
    if (fps == Math.floor(fps)) return String.valueOf((int) fps);
    return String.format(Locale.ROOT, "%.2f", fps);
  }

  /** Human-friendly byte size (mirrors TranscodeAPI.formatBytes for consistent UI wording). */
  public static String formatBytes(long bytes)
  {
    if (bytes <= 0) return "unknown";
    double gb = bytes / (1024.0 * 1024.0 * 1024.0);
    if (gb >= 1.0) return String.format(Locale.ROOT, "%.2f GB", gb);
    double mb = bytes / (1024.0 * 1024.0);
    return String.format(Locale.ROOT, "%.0f MB", mb);
  }
}
