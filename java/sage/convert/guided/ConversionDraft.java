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

import java.util.EnumSet;
import java.util.Locale;

import sage.convert.AudioCodecChoice;
import sage.convert.AudioLayoutChoice;
import sage.convert.ContainerChoice;
import sage.convert.DynamicRangeChoice;
import sage.convert.FrameRateChoice;
import sage.convert.ScalingChoice;
import sage.convert.SourceMedia;
import sage.convert.SubtitleChoice;
import sage.convert.VideoCodecChoice;

/**
 * Server-side, mutable state for one run of the guided conversion wizard. The STV
 * client drives it one answer at a time through the Catbert draft API, and each
 * {@link #resolve()} re-runs the pure {@link GuidedRecommender} over the current
 * answers to produce a fresh {@link Recommendation}. Nothing here is persisted;
 * a draft lives only for the duration of a wizard session and is addressed by a
 * small integer id handed to the client.
 *
 * <p>All mutators accept the loose string tokens the STV can supply and fail
 * soft: an unrecognised token is ignored rather than throwing, so a client typo
 * can never break the wizard. The source media and its duration are captured once
 * at creation from the recording the user picked.
 */
public final class ConversionDraft
{
  private final int id;
  private final SourceMedia source;
  private final long durationMillis;

  private EnumSet<CreationGoal> goals = EnumSet.noneOf(CreationGoal.class);
  private TransferClass transfer = TransferClass.UNRESTRICTED;
  private long customBudgetBytes = 0L;
  private DeviceProfile device = DeviceProfile.unknownDevice();
  private QualityPriority priority = QualityPriority.BALANCED;
  private boolean preserveSmoothMotion;
  private boolean preserveSurround;
  private boolean preserveHdr;
  private boolean avoidReencode;
  private boolean useHardware = true;
  private boolean keepSubtitles;
  private final GuidedInputs.Overrides overrides = new GuidedInputs.Overrides();

  public ConversionDraft(int id, SourceMedia source, long durationMillis)
  {
    if (source == null) throw new IllegalArgumentException("source is null");
    this.id = id;
    this.source = source;
    this.durationMillis = durationMillis > 0 ? durationMillis : source.getDurationMillis();
  }

  public int getId() { return id; }
  public SourceMedia getSource() { return source; }
  public long getDurationMillis() { return durationMillis; }

  // --- Menu 1: creation goals -------------------------------------------
  public void setGoal(String token, boolean on)
  {
    CreationGoal g = parse(CreationGoal.class, token);
    if (g == null) return;
    if (on) goals.add(g); else goals.remove(g);
  }

  /**
   * The mutually-exclusive "what is this for?" intent goals. Section 1 of the
   * wizard is single-select: choosing one clears the others so a profile can't be
   * both "Phone offline" and "USB TV playback" at once.
   */
  private static final CreationGoal[] INTENT_GROUP = {
    CreationGoal.USB_TV_PLAYBACK, CreationGoal.PHONE_OFFLINE, CreationGoal.TABLET_OFFLINE,
    CreationGoal.WAN_SMALLER, CreationGoal.REUSABLE_FAVORITE };

  /** Single-select intent: clear the whole intent group, then set one (or none for "custom"). */
  public void setIntent(String token)
  {
    for (CreationGoal g : INTENT_GROUP) goals.remove(g);
    if (token == null) return;
    String t = token.trim();
    if (t.length() == 0 || t.equalsIgnoreCase("custom") || t.equalsIgnoreCase("none")) return;
    CreationGoal g = parse(CreationGoal.class, t);
    if (g == null) return;
    for (CreationGoal x : INTENT_GROUP) if (x == g) { goals.add(g); return; }
  }

  /** The current intent token, or "CUSTOM" when none of the intent goals is set. */
  public String getIntent()
  {
    for (CreationGoal g : INTENT_GROUP) if (goals.contains(g)) return g.name();
    return "CUSTOM";
  }

  /** Friendly label for the current intent, for the Section 1 header. */
  public String getIntentLabel()
  {
    for (CreationGoal g : INTENT_GROUP)
    {
      if (!goals.contains(g)) continue;
      switch (g)
      {
        case USB_TV_PLAYBACK:   return "USB TV playback";
        case PHONE_OFFLINE:     return "Phone (offline)";
        case TABLET_OFFLINE:    return "Tablet (offline)";
        case WAN_SMALLER:       return "Travel download";
        case REUSABLE_FAVORITE: return "Reusable favorite";
        default: return friendlyGoal(g);
      }
    }
    return "Custom profile";
  }

  // --- Menu 2: transfer class -------------------------------------------
  public void setTransfer(String token)
  {
    TransferClass t = parse(TransferClass.class, token);
    if (t != null) transfer = t;
  }

  public void setCustomBudgetBytes(long bytes) { this.customBudgetBytes = Math.max(0L, bytes); }

  // --- Menu 3: player device profile ------------------------------------
  public void setDevice(String token)
  {
    DeviceProfile d = deviceFor(token);
    if (d != null) device = d;
  }

  // --- Menu 4: priority --------------------------------------------------
  public void setPriority(String token)
  {
    QualityPriority p = parse(QualityPriority.class, token);
    if (p != null) priority = p;
  }

  // --- preference toggles (shared across menus) -------------------------
  public void setPreference(String name, boolean on)
  {
    if (name == null) return;
    switch (name.trim().toLowerCase(Locale.ROOT))
    {
      case "smoothmotion":  preserveSmoothMotion = on; break;
      case "surround":      preserveSurround = on; break;
      case "hdr":           preserveHdr = on; break;
      case "avoidreencode": avoidReencode = on; break;
      case "hardware":      useHardware = on; break;
      case "subtitles":     keepSubtitles = on; break;
      default: /* unknown preference: ignore */ break;
    }
  }

  /** Current state of a preference toggle, for checkbox rendering. */
  public boolean isPreference(String name)
  {
    if (name == null) return false;
    switch (name.trim().toLowerCase(Locale.ROOT))
    {
      case "smoothmotion":  return preserveSmoothMotion;
      case "surround":      return preserveSurround;
      case "hdr":           return preserveHdr;
      case "avoidreencode": return avoidReencode;
      case "hardware":      return useHardware;
      case "subtitles":     return keepSubtitles;
      default:              return false;
    }
  }

  // --- Menu 6: capability overrides -------------------------------------
  public void setOverride(String field, String value)
  {
    if (field == null) return;
    boolean clear = (value == null || value.trim().length() == 0
        || "auto".equalsIgnoreCase(value.trim()) || "recommended".equalsIgnoreCase(value.trim()));
    switch (field.trim().toLowerCase(Locale.ROOT))
    {
      case "container":   overrides.container   = clear ? null : parse(ContainerChoice.class, value); break;
      case "videocodec":  overrides.videoCodec  = clear ? null : parse(VideoCodecChoice.class, value); break;
      case "scaling":     overrides.scaling     = clear ? null : parse(ScalingChoice.class, value); break;
      case "width":       overrides.width       = clear ? null : parseInt(value); break;
      case "height":      overrides.height      = clear ? null : parseInt(value); break;
      case "framerate":   overrides.frameRate   = clear ? null : parse(FrameRateChoice.class, value); break;
      case "audiolayout": overrides.audioLayout = clear ? null : parse(AudioLayoutChoice.class, value); break;
      case "audiocodec":  overrides.audioCodec  = clear ? null : parse(AudioCodecChoice.class, value); break;
      case "audiobitrate":overrides.audioBitrateKbps = clear ? null : parseInt(value); break;
      case "dynamicrange":overrides.dynamicRange= clear ? null : parse(DynamicRangeChoice.class, value); break;
      case "subtitles":   overrides.subtitles   = clear ? null : parse(SubtitleChoice.class, value); break;
      case "qualitycq":   overrides.qualityCq   = clear ? null : parseInt(value); break;
      default: /* unknown field: ignore */ break;
    }
  }

  public void clearOverrides()
  {
    overrides.container = null; overrides.videoCodec = null; overrides.scaling = null;
    overrides.width = null; overrides.height = null; overrides.frameRate = null;
    overrides.audioLayout = null; overrides.audioCodec = null; overrides.audioBitrateKbps = null;
    overrides.dynamicRange = null; overrides.subtitles = null; overrides.qualityCq = null;
  }

  public boolean hasOverrides() { return !overrides.isEmpty(); }

  /** Whether one creation goal is currently selected (for checkbox rendering). */
  public boolean isGoalEnabled(String token)
  {
    CreationGoal g = parse(CreationGoal.class, token);
    return g != null && goals.contains(g);
  }

  /**
   * Current value of a single-select field or override, as an upper-case token,
   * for radio-tick and Customize labels. Returns "AUTO" when nothing is chosen.
   * Recognised fields: transfer, device, priority, and every override field name.
   */
  public String getSelection(String field)
  {
    if (field == null) return "AUTO";
    switch (field.trim().toLowerCase(Locale.ROOT))
    {
      case "transfer":    return transfer.name();
      case "device":      return device.getName();
      case "priority":    return priority.name();
      case "container":   return tok(overrides.container);
      case "videocodec":  return tok(overrides.videoCodec);
      case "scaling":     return tok(overrides.scaling);
      case "width":       return overrides.width == null ? "AUTO" : overrides.width.toString();
      case "height":      return overrides.height == null ? "AUTO" : overrides.height.toString();
      case "framerate":   return tok(overrides.frameRate);
      case "audiolayout": return tok(overrides.audioLayout);
      case "audiocodec":  return tok(overrides.audioCodec);
      case "audiobitrate":return overrides.audioBitrateKbps == null ? "AUTO" : overrides.audioBitrateKbps.toString();
      case "dynamicrange":return tok(overrides.dynamicRange);
      case "subtitles":   return tok(overrides.subtitles);
      case "qualitycq":   return overrides.qualityCq == null ? "AUTO" : overrides.qualityCq.toString();
      default:            return "AUTO";
    }
  }

  private static String tok(Enum<?> e) { return e == null ? "AUTO" : e.name(); }

  /**
   * True when {@code field}'s current selection matches {@code token}. Device-aware:
   * device tokens ("phone","tablet","computer","tv","unrestricted","unknown") are mapped
   * through {@link #deviceFor} so callers compare against stable tokens instead of the
   * friendly display name. All other fields compare case-insensitively against
   * {@link #getSelection}.
   */
  public boolean isSelection(String field, String token)
  {
    if (field == null || token == null) return false;
    if (field.trim().equalsIgnoreCase("device"))
    {
      DeviceProfile want = deviceFor(token);
      return want != null && device != null && want.getName().equals(device.getName());
    }
    String cur = getSelection(field);
    return cur != null && cur.equalsIgnoreCase(token.trim());
  }

  /** Friendly comma list of currently selected goals, for a submenu header. */
  public String getGoalsSummary()
  {
    if (goals.isEmpty()) return "None selected yet";
    StringBuilder sb = new StringBuilder();
    for (CreationGoal g : goals)
    {
      if (sb.length() > 0) sb.append(", ");
      sb.append(friendlyGoal(g));
    }
    return sb.toString();
  }

  private static String friendlyGoal(CreationGoal g)
  {
    switch (g)
    {
      case USB_TV_PLAYBACK:  return "USB TV playback";
      case PHONE_OFFLINE:    return "Phone (offline)";
      case TABLET_OFFLINE:   return "Tablet (offline)";
      case WAN_SMALLER:      return "Smaller for download";
      case IMPROVE_UPSCALE:  return "Improve / upscale";
      case REUSABLE_FAVORITE:return "Reusable favorite";
      case REDUCE_STORAGE:   return "Reduce storage";
      case PRESERVE_RES_FPS: return "Preserve res/fps";
      case PRESERVE_SURROUND:return "Preserve surround";
      case EXACT_BACKUP:     return "Exact backup";
      case PREFER_COMPAT:    return "Prefer compatibility";
      case PREFER_SMALLEST:  return "Prefer smallest";
      case PRESERVE_HDR:     return "Preserve HDR";
      case INCLUDE_SUBTITLES:return "Include subtitles";
      default:               return g.name();
    }
  }

  /** Snapshot the current answers into an immutable {@link GuidedInputs}. */
  public GuidedInputs toInputs()
  {
    GuidedInputs.Overrides copy = new GuidedInputs.Overrides();
    copy.container = overrides.container; copy.videoCodec = overrides.videoCodec;
    copy.scaling = overrides.scaling; copy.width = overrides.width; copy.height = overrides.height;
    copy.frameRate = overrides.frameRate; copy.audioLayout = overrides.audioLayout;
    copy.audioCodec = overrides.audioCodec; copy.audioBitrateKbps = overrides.audioBitrateKbps;
    copy.dynamicRange = overrides.dynamicRange; copy.subtitles = overrides.subtitles;
    copy.qualityCq = overrides.qualityCq;
    return GuidedInputs.builder(source)
        .goals(goals)
        .transfer(transfer)
        .customBudgetBytes(customBudgetBytes)
        .device(device)
        .priority(priority)
        .preserveSmoothMotion(preserveSmoothMotion)
        .preserveSurround(preserveSurround)
        .preserveHdr(preserveHdr)
        .avoidReencode(avoidReencode)
        .useHardware(useHardware)
        .keepSubtitles(keepSubtitles)
        .overrides(copy)
        .build();
  }

  /** Re-run the recommender over the current answers. */
  public Recommendation resolve()
  {
    return GuidedRecommender.recommend(toInputs());
  }

  private static DeviceProfile deviceFor(String token)
  {
    if (token == null) return null;
    switch (token.trim().toLowerCase(Locale.ROOT))
    {
      case "phone":        return DeviceProfile.phone();
      case "tablet":       return DeviceProfile.tablet();
      case "computer":
      case "pc":
      case "desktop":      return DeviceProfile.computer();
      case "tv":
      case "4ktv":
      case "modern4ktv":   return DeviceProfile.modern4kTv();
      case "unrestricted": return DeviceProfile.unrestricted();
      case "unknown":      return DeviceProfile.unknownDevice();
      default:             return null;
    }
  }

  private static <E extends Enum<E>> E parse(Class<E> type, String token)
  {
    if (token == null || token.trim().length() == 0) return null;
    try { return Enum.valueOf(type, token.trim().toUpperCase(Locale.ROOT)); }
    catch (Exception e) { return null; }
  }

  private static Integer parseInt(String v)
  {
    if (v == null) return null;
    try { return Integer.valueOf(v.trim()); } catch (Exception e) { return null; }
  }
}
