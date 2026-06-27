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
import sage.Agent;
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
import java.util.Arrays;
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
    if (isEnabled("miniclient/transfer/offline_guide_enabled", true))
    {
      caps.add("OFFLINE_GUIDE");
      caps.add("GUIDE_SNAPSHOT");
    }
    if (isEnabled("miniclient/transfer/offline_scheduled_enabled", true))
    {
      caps.add("OFFLINE_SCHEDULED");
      caps.add("SCHEDULED_SNAPSHOT");
    }
    if (isEnabled("miniclient/transfer/offline_favorites_enabled", true))
    {
      caps.add("OFFLINE_FAVORITES");
      caps.add("FAVORITES_SNAPSHOT");
    }
    return (String[]) caps.toArray(new String[0]);
  }

  public static String buildFavoritesSnapshotJson()
  {
    long now = Sage.time();
    Agent[] favorites = Wizard.getInstance().getFavorites();
    if (favorites == null)
      favorites = new Agent[0];
    Arrays.sort(favorites, new java.util.Comparator<Agent>()
    {
      public int compare(Agent a1, Agent a2)
      {
        if (a1 == a2)
          return 0;
        if (a1 == null)
          return 1;
        if (a2 == null)
          return -1;
        return a1.getUID() - a2.getUID();
      }
    });

    StringBuilder sb = new StringBuilder(favorites.length * 320 + 256);
    sb.append('{');
    append(sb, "snapshot_id", "fav-" + now);
    appendFavoritesArray(sb, favorites);
    closeObject(sb);
    return sb.toString();
  }

  /**
   * Builds the full guide snapshot for the active server. Clients should treat the
   * returned data as a complete per-server snapshot and key it by the server identity.
   */
  public static String buildGuideSnapshotJson()
  {
    long now = Sage.time();
    long horizonStart = startOfUtcDay(now);
    long horizonEnd = horizonStart + (getGuideSnapshotWindowDays() * Sage.MILLIS_PER_DAY);
    Channel[] channels = Wizard.getInstance().getChannels();
    if (channels == null)
      channels = new Channel[0];
    Arrays.sort(channels, Channel.STATION_ID_COMPARATOR);

    ArrayList<Airing> airings = new ArrayList<Airing>();
    Wizard wiz = Wizard.getInstance();
    for (int i = 0; i < channels.length; i++)
    {
      Channel channel = channels[i];
      if (channel == null)
        continue;
      Airing[] channelAirings = wiz.getAirings(channel.getStationID(), horizonStart, horizonEnd, false);
      if (channelAirings == null)
        continue;
      airings.addAll(Arrays.asList(channelAirings));
    }
    java.util.Collections.sort(airings, new java.util.Comparator<Airing>()
    {
      public int compare(Airing a1, Airing a2)
      {
        if (a1 == a2)
          return 0;
        if (a1 == null)
          return 1;
        if (a2 == null)
          return -1;
        long diff = a1.getStartTime() - a2.getStartTime();
        if (diff < 0L)
          return -1;
        if (diff > 0L)
          return 1;
        diff = a1.getStationID() - a2.getStationID();
        if (diff < 0L)
          return -1;
        if (diff > 0L)
          return 1;
        return a1.getID() - a2.getID();
      }
    });

    StringBuilder sb = new StringBuilder(airings.size() * 256 + channels.length * 128 + 256);
    sb.append('{');
    append(sb, "snapshot_id", "guide-" + now);
    append(sb, "horizon_start", formatIsoUtc(horizonStart));
    append(sb, "horizon_end", formatIsoUtc(horizonEnd));
    appendChannelArray(sb, channels);
    appendGuideAiringsArray(sb, airings);
    closeObject(sb);
    return sb.toString();
  }

  public static String buildSchedSnapshotJson()
  {
    long now = Sage.time();
    Airing[] scheduled = getScheduledAirings();
    if (scheduled == null)
      scheduled = new Airing[0];
    Arrays.sort(scheduled, new java.util.Comparator<Airing>()
    {
      public int compare(Airing a1, Airing a2)
      {
        if (a1 == a2)
          return 0;
        if (a1 == null)
          return 1;
        if (a2 == null)
          return -1;
        long diff = a1.getStartTime() - a2.getStartTime();
        if (diff < 0L)
          return -1;
        if (diff > 0L)
          return 1;
        diff = a1.getStationID() - a2.getStationID();
        if (diff < 0L)
          return -1;
        if (diff > 0L)
          return 1;
        return a1.getID() - a2.getID();
      }
    });

    long horizonStart = 0L;
    long horizonEnd = 0L;
    for (int i = 0; i < scheduled.length; i++)
    {
      Airing airing = scheduled[i];
      if (airing == null)
        continue;
      long start = airing.getStartTime();
      long end = airing.getEndTime();
      if (horizonStart == 0L || start < horizonStart)
        horizonStart = start;
      if (end > horizonEnd)
        horizonEnd = end;
    }

    StringBuilder sb = new StringBuilder(scheduled.length * 192 + 256);
    sb.append('{');
    append(sb, "snapshot_id", "sched-" + now);
    if (horizonStart > 0L)
      append(sb, "horizon_start", formatIsoUtc(horizonStart));
    if (horizonEnd > 0L)
      append(sb, "horizon_end", formatIsoUtc(horizonEnd));
    appendScheduledArray(sb, scheduled);
    closeObject(sb);
    return sb.toString();
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

    StringBuilder sb = new StringBuilder(4096);
    sb.append('{');

    append(sb, "manifest_version", 1);
    append(sb, "recording_id", String.valueOf(mf.getID()));
    append(sb, "generated_at", formatIsoUtc(Sage.time()));

    appendCoreObject(sb, mf, airing, show);
    appendMetadataObject(sb, mf, airing, show);
    appendCreditsArray(sb, show);

    ArrayList<OfflineAsset> artwork = isEnabled("miniclient/transfer/offline_artwork_enabled", true)
        ? buildArtworkAssets(session, mf, show)
        : new ArrayList<OfflineAsset>();
    appendArtworkArray(sb, "artwork", session, artwork);

    ArrayList<OfflineAsset> captions = isEnabled("miniclient/transfer/offline_captions_enabled", true)
        ? buildCaptionAssets(mf)
        : new ArrayList<OfflineAsset>();
    OfflineAsset comskip = isEnabled("miniclient/transfer/offline_comskip_enabled", true)
        ? buildComskipAsset(mf)
        : null;
    OfflineAsset transcript = isEnabled("miniclient/transfer/offline_transcript_enabled", true)
        ? buildTranscriptAsset(mf)
        : null;
    appendAssetsObject(sb, session, mf, captions, comskip, transcript);

    sb.append('}');
    return sb.toString();
  }

  public static String buildOfflineCoreBlockJson(NgClientRecordingCopyTransferManager.TransferSession session)
  {
    if (session == null || !isEnabled("miniclient/transfer/offline_metadata_enabled", true))
      return "";

    MediaFile mf = Wizard.getInstance().getFileForID(session.recordingId);
    if (mf == null)
      return "";

    Airing airing = mf.getContentAiring();
    Show show = mf.getShow();

    StringBuilder sb = new StringBuilder(2048);
    sb.append('{');
    append(sb, "manifest_version", 1);
    append(sb, "recording_id", String.valueOf(mf.getID()));
    append(sb, "generated_at", formatIsoUtc(Sage.time()));
    appendCoreObject(sb, mf, airing, show);
    appendMetadataObject(sb, mf, airing, show);
    appendArrayStart(sb, "credits");
    sb.append(']');
    appendArrayStart(sb, "artwork");
    sb.append(']');
    appendObjectStart(sb, "assets");
    appendObjectStart(sb, "video");
    appendFileArray(sb, "recording_files", mf.getFile(0));
    append(sb, "recording_file_size", Math.max(0L, mf.getSize()));
    append(sb, "url", "/api/transfers/" + escape(session.sessionToken) + "/content");
    closeObject(sb);
    closeObject(sb);
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

  private static void appendCoreObject(StringBuilder sb,
      MediaFile mf,
      Airing airing,
      Show show)
  {
    appendObjectStart(sb, "core");
    String title = safe(airing == null ? "" : airing.getTitle());
    if (title.length() == 0)
      title = safe(show == null ? "" : show.getTitle());
    append(sb, "title", title);
    String description = show == null ? "" : safe(show.getDesc());
    append(sb, "description", description);
    append(sb, "runtime_ms", show == null ? 0L : Math.max(0L, show.getDuration()));

    appendIfNotEmpty(sb, "subtitle", show == null ? "" : show.getEpisodeName());
    appendIfNotEmpty(sb, "show_title", show == null ? "" : show.getTitle());
    appendIfNotEmpty(sb, "show_id", show == null ? "" : show.getExternalID());
    appendIfNotEmpty(sb, "original_air_date", formatDate(show == null ? 0L : show.getOriginalAirDate()));
    if (airing != null)
    {
      long recordingStart = airing.getStartTime();
      long recordingEnd = airing.getEndTime();
      append(sb, "recording_start_ms", Math.max(0L, recordingStart));
      append(sb, "recording_end_ms", Math.max(0L, recordingEnd));
      appendIfNotEmpty(sb, "recording_start_utc", formatIsoUtc(recordingStart));
      appendIfNotEmpty(sb, "recording_end_utc", formatIsoUtc(recordingEnd));
    }
    appendIfPositiveInt(sb, "season_number", show == null ? 0 : show.getSeasonNumber());
    appendIfPositiveInt(sb, "episode_number", show == null ? 0 : show.getEpisodeNumber());
    if (airing != null)
      append(sb, "first_run", airing.isFirstRun());

    if (airing != null)
    {
      Channel chan = airing.getChannel();
      appendIfNotEmpty(sb, "channel", airing.getChannelNum(0));
      appendIfNotEmpty(sb, "station", airing.getChannelName());
      appendIfNotEmpty(sb, "network", chan == null ? "" : chan.getNetwork());
      append(sb, "station_id", airing.getStationID());
    }
    closeObject(sb);
  }

  private static void appendMetadataObject(StringBuilder sb,
      MediaFile mf,
      Airing airing,
      Show show)
  {
    appendObjectStart(sb, "metadata");

    if (show != null)
    {
      appendIfNotEmpty(sb, "rated", show.getRated());
      appendIfNotEmpty(sb, "parental_rating", show.getParentalRating());
      appendIfNotEmpty(sb, "year", show.getYear());
      appendIfNotEmpty(sb, "language", show.getLanguage());
      appendIfNotEmpty(sb, "studio", show.getStudio());
      appendIfNotEmpty(sb, "star_rating", show.getRating());
      appendStringArrayIfAny(sb, "categories", show.getCategories());
      appendStringArrayIfAny(sb, "expanded_ratings", show.getExpandedRatings());
    }

    ContainerFormat cf = mf == null ? null : mf.getFileFormat();
    appendIfNotEmpty(sb, "audio_format_summary", buildAudioSummary(cf));

    if (airing != null)
    {
      append(sb, "hdtv", airing.isHDTV());
      append(sb, "surround", airing.isSurround());
      append(sb, "cc", airing.isCC());
      append(sb, "watched", airing.isWatched());
      append(sb, "first_run", airing.isFirstRun());
    }
    append(sb, "subtitles_available", hasSubtitles(mf, airing));

    long resumeMs = 0L;
    if (airing != null)
    {
      Watched w = Wizard.getInstance().getWatch(airing);
      if (w != null)
        resumeMs = Math.max(0L, w.getWatchEnd() - airing.getStartTime());
    }
    append(sb, "resume_position_ms", resumeMs);

    boolean isFavorite = airing != null && Carny.getInstance().isLoveAir(airing);
    ManualRecord mr = airing == null ? null : Wizard.getInstance().getManualRecord(airing);
    append(sb, "favorite", isFavorite);
    append(sb, "manual", mr != null);
    append(sb, "epg_scheduled", mr == null);

    closeObject(sb);
  }

  private static void appendCreditsArray(StringBuilder sb, Show show)
  {
    appendArrayStart(sb, "credits");
    boolean first = true;
    LinkedHashSet<String> seen = new LinkedHashSet<String>();
    if (show != null)
    {
      byte[] roles = show.getRoles();
      for (int i = 0; roles != null && i < roles.length; i++)
      {
        Person p = show.getPersonObj(i);
        if (p == null)
          continue;
        String personId = "P" + Math.abs(p.getID());
        int roleCode = roles[i] & 0xFF;
        String dedupeKey = personId + "|" + roleCode;
        if (!seen.add(dedupeKey))
          continue;
        if (!first)
          sb.append(',');
        first = false;
        sb.append('{');
        append(sb, "person_id", personId);
        append(sb, "person_name", p.getName());
        append(sb, "role_code", roleCode);
        append(sb, "role_name", Show.getRoleString(roleCode));
        closeObject(sb);
      }
    }
    sb.append(']');
  }

  private static void appendAssetsObject(StringBuilder sb,
      NgClientRecordingCopyTransferManager.TransferSession session,
      MediaFile mf,
      ArrayList<OfflineAsset> captions,
      OfflineAsset comskip,
      OfflineAsset transcript)
  {
    appendObjectStart(sb, "assets");
    appendObjectStart(sb, "video");
    appendFileArray(sb, "recording_files", mf == null ? null : mf.getFile(0));
    append(sb, "recording_file_size", mf == null ? 0L : Math.max(0L, mf.getSize()));
    if (session != null)
      append(sb, "url", "/api/transfers/" + escape(session.sessionToken) + "/content");
    closeObject(sb);

    if (captions != null && !captions.isEmpty())
      appendCaptionArray(sb, "captions", session, captions);
    if (comskip != null)
      appendComskipObject(sb, "comskip", session, comskip);
    if (transcript != null)
      appendTranscriptObject(sb, "transcript", session, transcript);

    closeObject(sb);
  }

  private static ArrayList<OfflineAsset> buildArtworkAssets(
      NgClientRecordingCopyTransferManager.TransferSession session, MediaFile mf, Show show)
  {
    ArrayList<OfflineAsset> rv = new ArrayList<OfflineAsset>();
    if (show == null)
      return rv;

    // Prefer a local recording thumbnail first so the transfer sidecar can serve at least
    // one stable image even when remote artwork providers are unavailable.
    File localThumb = null;
    if (mf != null)
    {
      localThumb = mf.getSpecificThumbnailFile();
      if ((localThumb == null || !localThumb.isFile()) && mf.getGeneratedThumbnailFileLocation() != null)
      {
        File generatedThumb = mf.getGeneratedThumbnailFileLocation();
        if (generatedThumb.isFile())
          localThumb = generatedThumb;
      }
    }
    addLocalArtwork(rv, "thumbnail", localThumb, null, "image/jpeg");

    LinkedHashSet<String> seenShowUrls = new LinkedHashSet<String>();

    String thumbUrl = firstNonEmpty(
        show.getAnyImageUrl(0, true),
        show.getAnyImageUrl(0, false));
    addRemoteArtworkUnique(rv, seenShowUrls, "thumbnail", thumbUrl, null, "image/jpeg");

    String posterUrl = firstNonEmpty(
        show.getImageUrl(0, Show.IMAGE_POSTER_TALL),
        show.getImageUrl(0, Show.IMAGE_POSTER_WIDE),
        show.getAnyImageUrl(0, false));
    addRemoteArtworkUnique(rv, seenShowUrls, "poster", posterUrl, null, "image/jpeg");

    String fanartUrl = firstNonEmpty(
        show.getImageUrl(0, Show.IMAGE_PHOTO_WIDE),
        show.getImageUrl(0, Show.IMAGE_PHOTO_TALL),
        show.getAnyImageUrl(1, false),
        show.getAnyImageUrl(0, false));
    addRemoteArtworkUnique(rv, seenShowUrls, "fanart", fanartUrl, null, "image/jpeg");

    for (int i = 0; i < 8; i++)
    {
      String extra = show.getImageUrlForIndex(i, false);
      addRemoteArtworkUnique(rv, seenShowUrls, "other", extra, null, "image/jpeg");
    }

    SeriesInfo si = show.getSeriesInfo();
    if (si != null)
      addRemoteArtworkUnique(rv, seenShowUrls, "banner", si.getImageURL(false), null, "image/jpeg");

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
        headshot = p.getImageURL(true);
      if (headshot == null || headshot.length() == 0)
        continue;
      addRemoteArtwork(rv, "person", headshot, personId, "image/jpeg");
    }

    return rv;
  }

  private static void addRemoteArtworkUnique(ArrayList<OfflineAsset> out, LinkedHashSet<String> seenUrls,
      String kind, String sourceUrl, String personId, String contentType)
  {
    if (sourceUrl == null || sourceUrl.length() == 0)
      return;
    if (seenUrls != null && !seenUrls.add(sourceUrl))
      return;
    addRemoteArtwork(out, kind, sourceUrl, personId, contentType);
  }

  private static String firstNonEmpty(String v1, String v2)
  {
    if (v1 != null && v1.length() > 0)
      return v1;
    return (v2 != null && v2.length() > 0) ? v2 : "";
  }

  private static String firstNonEmpty(String v1, String v2, String v3)
  {
    String rv = firstNonEmpty(v1, v2);
    if (rv.length() > 0)
      return rv;
    return (v3 != null && v3.length() > 0) ? v3 : "";
  }

  private static String firstNonEmpty(String v1, String v2, String v3, String v4)
  {
    String rv = firstNonEmpty(v1, v2, v3);
    if (rv.length() > 0)
      return rv;
    return (v4 != null && v4.length() > 0) ? v4 : "";
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

  private static void addLocalArtwork(ArrayList<OfflineAsset> out, String kind, File localFile,
      String personId, String contentType)
  {
    if (localFile == null || !localFile.isFile())
      return;
    OfflineAsset a = new OfflineAsset();
    a.kind = kind;
    a.localFile = localFile;
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
      append(sb, "url", "/api/transfers/" + escape(session.sessionToken) + "/offline/artwork/" + i);
      if (a.personId != null && a.personId.length() > 0)
        append(sb, "subject_id", a.personId);
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
      append(sb, "format", safe(a.format));
      append(sb, "url", "/api/transfers/" + escape(session.sessionToken) + "/offline/captions/" + i);
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
    append(sb, "url", "/api/transfers/" + escape(session.sessionToken) + "/offline/comskip");
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
    append(sb, "url", "/api/transfers/" + escape(session.sessionToken) + "/offline/transcript");
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

  private static void appendChannelArray(StringBuilder sb, Channel[] channels)
  {
    appendArrayStart(sb, "channels");
    boolean first = true;
    for (int i = 0; channels != null && i < channels.length; i++)
    {
      Channel channel = channels[i];
      if (channel == null)
        continue;
      if (!first)
        sb.append(',');
      first = false;
      sb.append('{');
      append(sb, "id", channelId(channel));
      append(sb, "number", safe(channel.getNumber()));
      String channelName = safe(channel.getLongName());
      if (channelName.length() == 0)
        channelName = safe(channel.getName());
      append(sb, "name", channelName);
      String logoUrl = safe(channel.getLogoUrl(0, Channel.LOGO_MED));
      if (logoUrl.length() > 0)
        append(sb, "logo_url", logoUrl);
      sb.append('}');
    }
    sb.append(']');
  }

  private static void appendGuideAiringsArray(StringBuilder sb, ArrayList<Airing> airings)
  {
    appendArrayStart(sb, "airings");
    boolean first = true;
    for (int i = 0; airings != null && i < airings.size(); i++)
    {
      Airing airing = airings.get(i);
      if (airing == null)
        continue;
      if (!first)
        sb.append(',');
      first = false;
      sb.append('{');
      append(sb, "id", airingId(airing));
      append(sb, "channel_id", channelId(airing.getStationID()));
      append(sb, "start_ms", airing.getStartTime());
      append(sb, "start", formatIsoUtc(airing.getStartTime()));
      append(sb, "duration_ms", Math.max(0L, airing.getDuration()));
      append(sb, "title", safe(airing.getTitle()));
      Show show = airing.getShow();
      if (show != null)
      {
        if (show.getEpisodeName().length() > 0)
          append(sb, "episode_title", show.getEpisodeName());
        append(sb, "season", Math.max(0, show.getSeasonNumber()));
        append(sb, "episode", Math.max(0, show.getEpisodeNumber()));
      }
      sb.append('}');
    }
    sb.append(']');
  }

  private static void appendScheduledArray(StringBuilder sb, Airing[] scheduled)
  {
    appendArrayStart(sb, "scheduled");
    boolean first = true;
    for (int i = 0; scheduled != null && i < scheduled.length; i++)
    {
      Airing airing = scheduled[i];
      if (airing == null)
        continue;
      if (!first)
        sb.append(',');
      first = false;
      sb.append('{');
      append(sb, "airing_id", airingId(airing));
      append(sb, "channel_id", channelId(airing.getStationID()));
      append(sb, "start_ms", airing.getStartTime());
      append(sb, "start", formatIsoUtc(airing.getStartTime()));
      append(sb, "title", safe(airing.getTitle()));
      sb.append('}');
    }
    sb.append(']');
  }

  private static void appendFavoritesArray(StringBuilder sb, Agent[] favorites)
  {
    appendArrayStart(sb, "favorites");
    boolean first = true;
    for (int i = 0; favorites != null && i < favorites.length; i++)
    {
      Agent favorite = favorites[i];
      if (favorite == null)
        continue;
      if (!first)
        sb.append(',');
      first = false;

      Airing representative = findRepresentativeAiring(favorite);
      String channelId = favoriteChannelId(favorite, representative);
      String airingId = representative == null ? "" : airingId(representative);

      sb.append('{');
      append(sb, "favorite_id", favoriteId(favorite));
      append(sb, "type", "recording");
      append(sb, "title", safe(favorite.getTitle()));
      append(sb, "channel_id", channelId);
      append(sb, "airing_id", airingId);
      append(sb, "enabled", !favorite.testAgentFlag(Agent.DISABLED_FLAG));
      appendRawJson(sb, "data_json", buildFavoriteDataJson(favorite, channelId, airingId));
      sb.append('}');
    }
    sb.append(']');
  }

  private static Airing findRepresentativeAiring(Agent favorite)
  {
    if (favorite == null)
      return null;
    long horizonStart = startOfUtcDay(Sage.time());
    long horizonEnd = horizonStart + (getGuideSnapshotWindowDays() * Sage.MILLIS_PER_DAY);
    Channel[] channels = Wizard.getInstance().getChannels();
    if (channels == null)
      return null;

    StringBuilder sbCache = new StringBuilder();
    for (int i = 0; i < channels.length; i++)
    {
      Channel channel = channels[i];
      if (channel == null)
        continue;
      Airing[] airings = Wizard.getInstance().getAirings(channel.getStationID(), horizonStart, horizonEnd, false);
      for (int j = 0; airings != null && j < airings.length; j++)
      {
        Airing airing = airings[j];
        if (airing != null && favorite.followsTrend(airing, false, sbCache))
          return airing;
      }
    }
    return null;
  }

  private static String favoriteChannelId(Agent favorite, Airing representative)
  {
    if (representative != null)
      return channelId(representative.getStationID());
    if (favorite == null)
      return "";
    String channelName = safe(favorite.getChannelName());
    if (channelName.length() == 0)
      return "";
    Channel[] channels = Wizard.getInstance().getChannels();
    for (int i = 0; channels != null && i < channels.length; i++)
    {
      Channel channel = channels[i];
      if (channel == null)
        continue;
      if (channelName.equalsIgnoreCase(channel.getName()) || channelName.equalsIgnoreCase(channel.getLongName()))
        return channelId(channel);
    }
    return "";
  }

  private static String buildFavoriteDataJson(Agent favorite, String channelId, String airingId)
  {
    StringBuilder sb = new StringBuilder(512);
    sb.append('{');
    append(sb, "agent_mask", favorite.getAgentMask());
    append(sb, "trend_name", favoriteTypeLabel(favorite));
    append(sb, "title", safe(favorite.getTitle()));
    append(sb, "category", safe(favorite.getCategory()));
    append(sb, "sub_category", safe(favorite.getSubCategory()));
    append(sb, "person", safe(favorite.getPerson()));
    append(sb, "rated", safe(favorite.getRated()));
    append(sb, "year", safe(favorite.getYear()));
    append(sb, "pr", safe(favorite.getPR()));
    append(sb, "channel_name", safe(favorite.getChannelName()));
    append(sb, "network", safe(favorite.getNetwork()));
    append(sb, "keyword", safe(favorite.getKeyword()));
    append(sb, "slot_type", favorite.getSlotType());
    appendIntArray(sb, "timeslots", favorite.getTimeslots());
    append(sb, "first_runs_only", favorite.isFirstRunsOnly());
    append(sb, "reruns_only", favorite.isReRunsOnly());
    append(sb, "keep_at_most", favorite.getAgentFlag(Agent.KEEP_AT_MOST_MASK));
    append(sb, "dont_autodelete", favorite.testAgentFlag(Agent.DONT_AUTODELETE_FLAG));
    append(sb, "delete_after_convert", favorite.testAgentFlag(Agent.DELETE_AFTER_CONVERT_FLAG));
    append(sb, "disabled", favorite.testAgentFlag(Agent.DISABLED_FLAG));
    append(sb, "favorite_id", favoriteId(favorite));
    append(sb, "channel_id", channelId);
    append(sb, "airing_id", airingId);
    closeObject(sb);
    return sb.toString();
  }

  private static String favoriteTypeLabel(Agent favorite)
  {
    if (favorite == null)
      return "";
    StringBuilder sb = new StringBuilder();
    if ((favorite.getAgentMask() & Agent.TITLE_MASK) != 0) sb.append("title");
    if ((favorite.getAgentMask() & Agent.CATEGORY_MASK) != 0) sb.append("category");
    if ((favorite.getAgentMask() & Agent.ACTOR_MASK) != 0) sb.append("person");
    if ((favorite.getAgentMask() & Agent.RATED_MASK) != 0) sb.append("rated");
    if ((favorite.getAgentMask() & Agent.YEAR_MASK) != 0) sb.append("year");
    if ((favorite.getAgentMask() & Agent.PR_MASK) != 0) sb.append("pr");
    if ((favorite.getAgentMask() & Agent.CHANNEL_MASK) != 0) sb.append("channel");
    if ((favorite.getAgentMask() & Agent.NETWORK_MASK) != 0) sb.append("network");
    if ((favorite.getAgentMask() & Agent.KEYWORD_MASK) != 0) sb.append("keyword");
    if (sb.length() == 0)
      return "recording";
    return sb.toString();
  }

  private static String favoriteId(Agent favorite)
  {
    return favorite == null ? "" : "FAV-" + favorite.getUID();
  }

  private static void appendIntArray(StringBuilder sb, String key, int[] values)
  {
    if (sb.charAt(sb.length() - 1) != '{') sb.append(',');
    sb.append('"').append(escape(key)).append('"').append(':').append('[');
    boolean first = true;
    for (int i = 0; values != null && i < values.length; i++)
    {
      if (!first) sb.append(',');
      first = false;
      sb.append(values[i]);
    }
    sb.append(']');
  }

  private static void appendArrayStart(StringBuilder sb, String key)
  {
    if (sb.charAt(sb.length() - 1) != '{') sb.append(',');
    sb.append('"').append(escape(key)).append('"').append(':').append('[');
  }

  private static void appendRawJson(StringBuilder sb, String key, String rawJson)
  {
    if (rawJson == null || rawJson.length() == 0)
      return;
    if (sb.charAt(sb.length() - 1) != '{') sb.append(',');
    sb.append('"').append(escape(key)).append('"').append(':').append(rawJson);
  }

  private static Airing[] getScheduledAirings()
  {
    sage.Hunter hunter = sage.SeekerSelector.getInstance();
    if (hunter == null)
      return new Airing[0];
    Airing[] scheduled = hunter.getInterleavedScheduledAirings();
    return scheduled == null ? new Airing[0] : scheduled;
  }

  private static String channelId(Channel channel)
  {
    return channel == null ? "" : channelId(channel.getStationID());
  }

  private static String channelId(int stationId)
  {
    return "CH-" + stationId;
  }

  private static String airingId(Airing airing)
  {
    return airing == null ? "" : "AIR-" + airing.getID();
  }

  private static String formatIsoUtc(long millis)
  {
    if (millis <= 0L)
      return "";
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
    sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
    return sdf.format(new Date(millis));
  }

  private static long startOfUtcDay(long millis)
  {
    java.util.Calendar cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
    cal.setTimeInMillis(millis);
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
    cal.set(java.util.Calendar.MINUTE, 0);
    cal.set(java.util.Calendar.SECOND, 0);
    cal.set(java.util.Calendar.MILLISECOND, 0);
    return cal.getTimeInMillis();
  }

  private static long getGuideSnapshotWindowDays()
  {
    long days = Sage.getLong("miniclient/transfer/offline_guide_horizon_days", 7L);
    return Math.max(1L, Math.min(16L, days));
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

  private static void appendIfNotEmpty(StringBuilder sb, String key, String value)
  {
    String s = safe(value);
    if (s.length() == 0)
      return;
    append(sb, key, s);
  }

  private static void appendIfPositiveInt(StringBuilder sb, String key, int value)
  {
    if (value > 0)
      append(sb, key, value);
  }

  private static void appendStringArrayIfAny(StringBuilder sb, String key, String[] values)
  {
    boolean hasAny = false;
    for (int i = 0; values != null && i < values.length; i++)
    {
      String v = values[i];
      if (v != null && v.length() > 0)
      {
        hasAny = true;
        break;
      }
    }
    if (hasAny)
      appendStringArray(sb, key, values);
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
