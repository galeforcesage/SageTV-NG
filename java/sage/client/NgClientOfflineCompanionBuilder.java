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

import sage.Airing;
import sage.Carny;
import sage.Channel;
import sage.ManualRecord;
import sage.MediaFile;
import sage.Person;
import sage.Sage;
import sage.SeriesInfo;
import sage.Show;
import sage.Watched;
import sage.Wizard;
import sage.media.format.AudioFormat;
import sage.media.format.ContainerFormat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Builds additive offline companion metadata for transfer ACK payloads and resolves
 * sidecar/artwork assets for token-authenticated HTTP fetches.
 */
public final class NgClientOfflineCompanionBuilder
{
  public static final class OfflineAsset
  {
    public String kind;
    public String language;
    public String format;
    public String personId;
    public String sourceUrl;
    public File localFile;
    public String contentType;
  }

  private NgClientOfflineCompanionBuilder() {}

  public static String[] getServerCapabilities()
  {
    ArrayList<String> caps = new ArrayList<String>();
    caps.add("DOWNLOAD");
    caps.add("DOWNLOAD_REFRESH");
    if (isEnabled("miniclient/transfer/offline_metadata_enabled", true))
      caps.add("OFFLINE_METADATA");
    if (isEnabled("miniclient/transfer/offline_artwork_enabled", true))
      caps.add("OFFLINE_ARTWORK");
    if (isEnabled("miniclient/transfer/offline_captions_enabled", true))
      caps.add("OFFLINE_CAPTIONS");
    if (isEnabled("miniclient/transfer/offline_comskip_enabled", true))
      caps.add("OFFLINE_COMSKIP");
    if (isEnabled("miniclient/transfer/offline_transcript_enabled", true))
      caps.add("OFFLINE_TRANSCRIPT");
    return (String[]) caps.toArray(new String[0]);
  }

  public static String buildOfflineBlockJson(NgClientRecordingCopyTransferManager.TransferSession session)
  {
    if (session == null || !isEnabled("miniclient/transfer/offline_metadata_enabled", true))
      return "";

    MediaFile mf = Wizard.getInstance().getFileForID(session.recordingId);
    if (mf == null)
      return "";

    Airing airing = mf.getContentAiring();
    Show show = mf.getShow();

    StringBuilder sb = new StringBuilder(3072);
    sb.append('{');

    appendObjectStart(sb, "metadata");
    appendOfflineMetadata(sb, session, mf, airing, show);
    closeObject(sb);

    if (isEnabled("miniclient/transfer/offline_artwork_enabled", true))
    {
      ArrayList<OfflineAsset> artwork = buildArtworkAssets(session, mf, show);
      if (!artwork.isEmpty())
      {
        appendArtworkArray(sb, "artwork", session, artwork);
      }
    }

    if (isEnabled("miniclient/transfer/offline_captions_enabled", true))
    {
      ArrayList<OfflineAsset> captions = buildCaptionAssets(mf);
      if (!captions.isEmpty())
      {
        appendCaptionArray(sb, "captions", session, captions);
      }
    }

    if (isEnabled("miniclient/transfer/offline_comskip_enabled", true))
    {
      OfflineAsset comskip = buildComskipAsset(mf);
      if (comskip != null)
      {
        appendComskipObject(sb, "comskip", session, comskip);
      }
    }

    if (isEnabled("miniclient/transfer/offline_transcript_enabled", true))
    {
      OfflineAsset transcript = buildTranscriptAsset(mf);
      if (transcript != null)
      {
        appendTranscriptObject(sb, "transcript", session, transcript);
      }
    }

    sb.append('}');
    return sb.toString();
  }

  public static OfflineAsset resolveArtworkAsset(NgClientRecordingCopyTransferManager.TransferSession session, int index)
  {
    if (session == null || index < 0 || !isEnabled("miniclient/transfer/offline_artwork_enabled", true))
      return null;
    MediaFile mf = Wizard.getInstance().getFileForID(session.recordingId);
    if (mf == null)
      return null;
    ArrayList<OfflineAsset> artwork = buildArtworkAssets(session, mf, mf.getShow());
    if (index >= artwork.size())
      return null;
    return artwork.get(index);
  }

  public static OfflineAsset resolveCaptionAsset(NgClientRecordingCopyTransferManager.TransferSession session, int index)
  {
    if (session == null || index < 0 || !isEnabled("miniclient/transfer/offline_captions_enabled", true))
      return null;
    MediaFile mf = Wizard.getInstance().getFileForID(session.recordingId);
    if (mf == null)
      return null;
    ArrayList<OfflineAsset> captions = buildCaptionAssets(mf);
    if (index >= captions.size())
      return null;
    return captions.get(index);
  }

  public static OfflineAsset resolveComskipAsset(NgClientRecordingCopyTransferManager.TransferSession session)
  {
    if (session == null || !isEnabled("miniclient/transfer/offline_comskip_enabled", true))
      return null;
    MediaFile mf = Wizard.getInstance().getFileForID(session.recordingId);
    return (mf == null) ? null : buildComskipAsset(mf);
  }

  public static OfflineAsset resolveTranscriptAsset(NgClientRecordingCopyTransferManager.TransferSession session)
  {
    if (session == null || !isEnabled("miniclient/transfer/offline_transcript_enabled", true))
      return null;
    MediaFile mf = Wizard.getInstance().getFileForID(session.recordingId);
    return (mf == null) ? null : buildTranscriptAsset(mf);
  }

  private static void appendOfflineMetadata(StringBuilder sb,
      NgClientRecordingCopyTransferManager.TransferSession session,
      MediaFile mf,
      Airing airing,
      Show show)
  {
    appendObjectStart(sb, "media_file");
    append(sb, "id", String.valueOf(mf.getID()));
    ContainerFormat cf = mf.getFileFormat();
    append(sb, "format", cf == null ? "" : cf.getPrettyDesc());
    appendFileArray(sb, "recording_files", mf.getFile(0));
    append(sb, "recording_file_size", Math.max(0L, mf.getSize()));
    append(sb, "audio_format_summary", buildAudioSummary(cf));
    appendObjectStart(sb, "file_properties");
    append(sb, "hdtv", airing != null && airing.isHDTV());
    append(sb, "surround", airing != null && airing.isSurround());
    append(sb, "cc", airing != null && airing.isCC());
    append(sb, "subtitles_available", hasSubtitles(mf, airing));
    closeObject(sb);
    closeObject(sb);

    appendObjectStart(sb, "airing");
    append(sb, "title", airing == null ? "" : airing.getTitle());
    append(sb, "show_title", show == null ? "" : show.getTitle());
    append(sb, "description", show == null ? "" : show.getDesc());
    append(sb, "original_air_date", formatDate(show == null ? 0L : show.getOriginalAirDate()));
    append(sb, "season_number", show == null ? 0 : show.getSeasonNumber());
    append(sb, "episode_number", show == null ? 0 : show.getEpisodeNumber());
    append(sb, "first_run", airing != null && airing.isFirstRun());
    append(sb, "rated", show == null ? "" : show.getRated());
    append(sb, "show_id", show == null ? "" : show.getExternalID());
    appendStringArray(sb, "categories", show == null ? null : show.getCategories());
    append(sb, "run_time_minutes", show == null ? 0L : Math.max(0L, show.getDuration() / 60000L));
    if (airing != null)
    {
      Channel chan = airing.getChannel();
      append(sb, "channel", airing.getChannelNum(0));
      append(sb, "station", airing.getChannelName());
      append(sb, "network", chan == null ? "" : safe(chan.getNetwork()));
      append(sb, "station_id", airing.getStationID());
    }
    closeObject(sb);

    appendObjectStart(sb, "show");
    appendRoleGroup(sb, "cast", show, new byte[] {
        Show.ACTOR_ROLE, Show.LEAD_ACTOR_ROLE, Show.SUPPORTING_ACTOR_ROLE,
        Show.ACTRESS_ROLE, Show.LEAD_ACTRESS_ROLE, Show.SUPPORTING_ACTRESS_ROLE,
        Show.GUEST_ROLE, Show.GUEST_STAR_ROLE, Show.HOST_ROLE,
        Show.NARRATOR_ROLE, Show.ANCHOR_ROLE, Show.VOICE_ROLE,
        Show.MUSICAL_GUEST_ROLE
    });
    appendRoleGroup(sb, "director", show, new byte[] { Show.DIRECTOR_ROLE });
    appendRoleGroup(sb, "writer", show, new byte[] { Show.WRITER_ROLE });
    appendRoleGroup(sb, "executive_producer", show,
        new byte[] { Show.EXECUTIVE_PRODUCER_ROLE, Show.CO_EXECUTIVE_PRODUCER_ROLE });
    closeObject(sb);

    appendObjectStart(sb, "viewing");
    boolean watchedFlag = airing != null && airing.isWatched();
    append(sb, "watched", watchedFlag);
    long resumeMs = 0L;
    if (airing != null)
    {
      Watched w = Wizard.getInstance().getWatch(airing);
      if (w != null)
        resumeMs = Math.max(0L, w.getWatchEnd() - airing.getStartTime());
    }
    append(sb, "resume_position_ms", resumeMs);
    closeObject(sb);

    appendObjectStart(sb, "scheduling");
    boolean isFavorite = airing != null && Carny.getInstance().isLoveAir(airing);
    ManualRecord mr = airing == null ? null : Wizard.getInstance().getManualRecord(airing);
    append(sb, "favorite", isFavorite);
    append(sb, "manual", mr != null);
    append(sb, "epg_scheduled", mr == null);
    closeObject(sb);

    if (show != null)
    {
      SeriesInfo si = show.getSeriesInfo();
      if (si != null)
      {
        appendObjectStart(sb, "series");
        append(sb, "title", safe(si.getTitle()));
        append(sb, "description", safe(si.getDescription()));
        append(sb, "network", safe(si.getNetwork()));
        append(sb, "showcard_id", show.getShowcardID());
        closeObject(sb);
      }
    }

    appendObjectStart(sb, "compat");
    append(sb, "session_token", safe(session.sessionToken));
    append(sb, "url_revision", session.urlRevision);
    closeObject(sb);
  }

  private static ArrayList<OfflineAsset> buildArtworkAssets(
      NgClientRecordingCopyTransferManager.TransferSession session, MediaFile mf, Show show)
  {
    ArrayList<OfflineAsset> rv = new ArrayList<OfflineAsset>();
    if (show == null)
      return rv;

    addRemoteArtwork(rv, "poster", show.getImageUrl(0, Show.IMAGE_POSTER_TALL), null, "image/jpeg");
    addRemoteArtwork(rv, "fanart", show.getImageUrl(0, Show.IMAGE_PHOTO_WIDE), null, "image/jpeg");

    SeriesInfo si = show.getSeriesInfo();
    if (si != null)
      addRemoteArtwork(rv, "banner", si.getImageURL(false), null, "image/jpeg");

    LinkedHashSet<String> seenCast = new LinkedHashSet<String>();
    byte[] roles = show.getRoles();
    for (int i = 0; roles != null && i < roles.length; i++)
    {
      Person p = show.getPersonObj(i);
      if (p == null)
        continue;
      String personId = "P" + Math.abs(p.getID());
      if (!seenCast.add(personId))
        continue;
      String headshot = p.getImageURL(false);
      if (headshot == null || headshot.length() == 0)
        continue;
      addRemoteArtwork(rv, "cast", headshot, personId, "image/jpeg");
    }

    return rv;
  }

  private static void addRemoteArtwork(ArrayList<OfflineAsset> out, String kind, String sourceUrl,
      String personId, String contentType)
  {
    if (sourceUrl == null || sourceUrl.length() == 0)
      return;
    OfflineAsset a = new OfflineAsset();
    a.kind = kind;
    a.sourceUrl = sourceUrl;
    a.personId = personId;
    a.contentType = contentType;
    out.add(a);
  }

  private static ArrayList<OfflineAsset> buildCaptionAssets(MediaFile mf)
  {
    ArrayList<OfflineAsset> rv = new ArrayList<OfflineAsset>();
    File source = mf == null ? null : mf.getFile(0);
    if (source == null)
      return rv;

    String[] suffixes = new String[] { ".cc.srt", ".srt", ".eng.srt", ".cc.vtt", ".vtt", ".eng.vtt" };
    LinkedHashSet<String> seen = new LinkedHashSet<String>();
    for (int i = 0; i < suffixes.length; i++)
    {
      File f = siblingFile(source, suffixes[i]);
      if (f == null || !f.isFile())
        continue;
      String canon = f.getAbsolutePath();
      if (!seen.add(canon))
        continue;
      OfflineAsset a = new OfflineAsset();
      a.localFile = f;
      a.language = suffixes[i].indexOf("eng") != -1 ? "eng" : "und";
      a.format = suffixes[i].endsWith(".vtt") ? "vtt" : "srt";
      a.kind = suffixes[i].indexOf(".cc.") != -1 ? "cc" : a.format;
      a.contentType = suffixes[i].endsWith(".vtt") ? "text/vtt" : "application/x-subrip";
      rv.add(a);
    }
    return rv;
  }

  private static OfflineAsset buildComskipAsset(MediaFile mf)
  {
    File source = mf == null ? null : mf.getFile(0);
    if (source == null)
      return null;
    File edl = siblingFile(source, ".edl");
    if (edl != null && edl.isFile())
    {
      OfflineAsset a = new OfflineAsset();
      a.localFile = edl;
      a.format = "edl";
      a.contentType = "text/plain";
      return a;
    }
    File txt = siblingFile(source, ".txt");
    if (txt != null && txt.isFile())
    {
      OfflineAsset a = new OfflineAsset();
      a.localFile = txt;
      a.format = "txt";
      a.contentType = "text/plain";
      return a;
    }
    return null;
  }

  private static OfflineAsset buildTranscriptAsset(MediaFile mf)
  {
    File source = mf == null ? null : mf.getFile(0);
    if (source == null)
      return null;
    File vtt = siblingFile(source, ".transcript.vtt");
    if (vtt != null && vtt.isFile())
    {
      OfflineAsset a = new OfflineAsset();
      a.localFile = vtt;
      a.format = "vtt";
      a.language = "eng";
      a.contentType = "text/vtt";
      return a;
    }
    File srt = siblingFile(source, ".transcript.srt");
    if (srt != null && srt.isFile())
    {
      OfflineAsset a = new OfflineAsset();
      a.localFile = srt;
      a.format = "srt";
      a.language = "eng";
      a.contentType = "application/x-subrip";
      return a;
    }
    return null;
  }

  private static File siblingFile(File source, String suffix)
  {
    if (source == null || suffix == null)
      return null;
    String name = source.getName();
    int dot = name.lastIndexOf('.');
    String stem = (dot > 0) ? name.substring(0, dot) : name;
    return new File(source.getParentFile(), stem + suffix);
  }

  private static boolean hasSubtitles(MediaFile mf, Airing airing)
  {
    if (airing != null && airing.isSubtitled())
      return true;
    return !buildCaptionAssets(mf).isEmpty();
  }

  private static String buildAudioSummary(ContainerFormat cf)
  {
    if (cf == null)
      return "";
    AudioFormat[] afs = cf.getAudioFormats(true);
    if (afs == null || afs.length == 0)
      return safe(cf.getPrimaryAudioFormat());
    AudioFormat af = afs[0];
    if (af == null)
      return safe(cf.getPrimaryAudioFormat());
    StringBuilder sb = new StringBuilder();
    sb.append(safe(af.getFormatName()));
    int ch = af.getChannels();
    if (ch == 6)
      sb.append(" 5.1");
    else if (ch == 7)
      sb.append(" 6.1");
    else if (ch > 0)
      sb.append(' ').append(ch).append("ch");
    return sb.toString();
  }

  private static String formatDate(long millis)
  {
    if (millis <= 0)
      return "";
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    sdf.setTimeZone(TimeZone.getDefault());
    return sdf.format(new Date(millis));
  }

  private static boolean isEnabled(String prop, boolean defVal)
  {
    return Sage.getBoolean(prop, defVal);
  }

  private static String safe(String s)
  {
    return s == null ? "" : s;
  }

  private static void appendRoleGroup(StringBuilder sb, String key, Show show, byte[] acceptedRoles)
  {
    if (sb.charAt(sb.length() - 1) != '{') sb.append(',');
    sb.append('"').append(escape(key)).append('"').append(':').append('[');
    if (show != null)
    {
      byte[] roles = show.getRoles();
      boolean first = true;
      LinkedHashSet<String> seen = new LinkedHashSet<String>();
      for (int i = 0; roles != null && i < roles.length; i++)
      {
        if (!containsRole(acceptedRoles, roles[i]))
          continue;
        Person p = show.getPersonObj(i);
        if (p == null)
          continue;
        String personId = "P" + Math.abs(p.getID());
        if (!seen.add(personId))
          continue;
        if (!first) sb.append(',');
        first = false;
        sb.append('{');
        append(sb, "person_id", personId);
        append(sb, "name", p.getName());
        append(sb, "role", Show.getRoleString(roles[i]));
        append(sb, "billing", normalizeBilling(Show.getRoleString(roles[i])));
        sb.append('}');
      }
    }
    sb.append(']');
  }

  private static boolean containsRole(byte[] accepted, byte role)
  {
    for (int i = 0; accepted != null && i < accepted.length; i++)
      if (accepted[i] == role)
        return true;
    return false;
  }

  private static String normalizeBilling(String role)
  {
    if (role == null || role.length() == 0)
      return "";
    return role.toLowerCase().replace(' ', '_');
  }

  private static void appendArtworkArray(StringBuilder sb, String key,
      NgClientRecordingCopyTransferManager.TransferSession session,
      ArrayList<OfflineAsset> assets)
  {
    if (sb.charAt(sb.length() - 1) != '{') sb.append(',');
    sb.append('"').append(escape(key)).append('"').append(':').append('[');
    for (int i = 0; i < assets.size(); i++)
    {
      OfflineAsset a = assets.get(i);
      if (i > 0) sb.append(',');
      sb.append('{');
      append(sb, "kind", safe(a.kind));
      append(sb, "url", "/api/transfers/" + escape(session.sessionToken) + "/offline/artwork/" + i + "?v=" + session.urlRevision);
      if (a.personId != null && a.personId.length() > 0)
        append(sb, "person_id", a.personId);
      sb.append('}');
    }
    sb.append(']');
  }

  private static void appendCaptionArray(StringBuilder sb, String key,
      NgClientRecordingCopyTransferManager.TransferSession session,
      ArrayList<OfflineAsset> assets)
  {
    if (sb.charAt(sb.length() - 1) != '{') sb.append(',');
    sb.append('"').append(escape(key)).append('"').append(':').append('[');
    for (int i = 0; i < assets.size(); i++)
    {
      OfflineAsset a = assets.get(i);
      if (i > 0) sb.append(',');
      sb.append('{');
      append(sb, "language", safe(a.language));
      append(sb, "kind", safe(a.kind));
      append(sb, "url", "/api/transfers/" + escape(session.sessionToken) + "/offline/captions/" + i + "?v=" + session.urlRevision);
      sb.append('}');
    }
    sb.append(']');
  }

  private static void appendComskipObject(StringBuilder sb, String key,
      NgClientRecordingCopyTransferManager.TransferSession session,
      OfflineAsset asset)
  {
    if (asset == null)
      return;
    appendObjectStart(sb, key);
    append(sb, "format", safe(asset.format));
    append(sb, "url", "/api/transfers/" + escape(session.sessionToken) + "/offline/comskip?v=" + session.urlRevision);
    closeObject(sb);
  }

  private static void appendTranscriptObject(StringBuilder sb, String key,
      NgClientRecordingCopyTransferManager.TransferSession session,
      OfflineAsset asset)
  {
    if (asset == null)
      return;
    appendObjectStart(sb, key);
    append(sb, "format", safe(asset.format));
    append(sb, "language", safe(asset.language));
    append(sb, "url", "/api/transfers/" + escape(session.sessionToken) + "/offline/transcript?v=" + session.urlRevision);
    closeObject(sb);
  }

  private static void appendFileArray(StringBuilder sb, String key, File f)
  {
    if (sb.charAt(sb.length() - 1) != '{') sb.append(',');
    sb.append('"').append(escape(key)).append('"').append(':').append('[');
    if (f != null)
      sb.append('"').append(escape(f.getAbsolutePath())).append('"');
    sb.append(']');
  }

  private static void appendStringArray(StringBuilder sb, String key, String[] values)
  {
    if (sb.charAt(sb.length() - 1) != '{') sb.append(',');
    sb.append('"').append(escape(key)).append('"').append(':').append('[');
    boolean first = true;
    for (int i = 0; values != null && i < values.length; i++)
    {
      String v = values[i];
      if (v == null || v.length() == 0)
        continue;
      if (!first) sb.append(',');
      first = false;
      sb.append('"').append(escape(v)).append('"');
    }
    sb.append(']');
  }

  private static void appendObjectStart(StringBuilder sb, String key)
  {
    if (sb.charAt(sb.length() - 1) != '{') sb.append(',');
    sb.append('"').append(escape(key)).append('"').append(':').append('{');
  }

  private static void append(StringBuilder sb, String key, String value)
  {
    if (sb.charAt(sb.length() - 1) != '{') sb.append(',');
    sb.append('"').append(escape(key)).append('"').append(':')
        .append('"').append(escape(value == null ? "" : value)).append('"');
  }

  private static void append(StringBuilder sb, String key, long value)
  {
    if (sb.charAt(sb.length() - 1) != '{') sb.append(',');
    sb.append('"').append(escape(key)).append('"').append(':').append(value);
  }

  private static void append(StringBuilder sb, String key, int value)
  {
    append(sb, key, (long) value);
  }

  private static void append(StringBuilder sb, String key, boolean value)
  {
    if (sb.charAt(sb.length() - 1) != '{') sb.append(',');
    sb.append('"').append(escape(key)).append('"').append(':').append(value ? "true" : "false");
  }

  private static void closeObject(StringBuilder sb)
  {
    sb.append('}');
  }

  private static String escape(String s)
  {
    if (s == null || s.length() == 0)
      return "";
    StringBuilder rv = new StringBuilder(s.length() + 16);
    for (int i = 0; i < s.length(); i++)
    {
      char c = s.charAt(i);
      switch (c)
      {
        case '\\': rv.append("\\\\"); break;
        case '"': rv.append("\\\""); break;
        case '\n': rv.append("\\n"); break;
        case '\r': rv.append("\\r"); break;
        case '\t': rv.append("\\t"); break;
        default: rv.append(c); break;
      }
    }
    return rv.toString();
  }
}
