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

/**
 * Builds a compact JSON download contract for NG miniclients.
 */
public final class NgClientDownloadContractBuilder
{
  private NgClientDownloadContractBuilder() {}

  public static String buildContractJson(sage.MediaFile mf,
      NgClientDownloadTokenManager.TokenIssue tokenIssue,
      String ngClientId,
      String ngVersion,
      java.util.Set<String> clientCapabilities,
      String sessionClientIp,
      String downloadMode,
      long estimatedBandwidthBps)
  {
    if (mf == null)
      return "{}";

    java.io.File file = mf.getFile(0);
    String filePath = file != null ? file.getAbsolutePath() : "";
    String fileName = file != null ? file.getName() : "";
    long fileSize = file != null ? file.length() : 0L;
    int generalType = mf.getGeneralType();
    sage.Airing airing = mf.getContentAiring();
    sage.Show show = airing != null ? airing.getShow() : null;
    String title = show != null ? show.getTitle() : "";
    String episode = show != null ? show.getEpisodeName() : "";
    String desc = show != null ? show.getDesc() : "";
    String normMode = normalizeDownloadMode(downloadMode);
    boolean backgroundRequested = "background".equals(normMode);
    long bytesComplete = 0L;
    long bytesRemaining = Math.max(0L, fileSize - bytesComplete);
    StringBuilder sb = new StringBuilder(2048);
    sb.append('{');
    append(sb, "command_type", "CMD_DOWNLOAD_REQUEST");
    append(sb, "download_mode", normMode);
    append(sb, "download_id", tokenIssue != null ? tokenIssue.tokenHash : "");
    append(sb, "media_id", mf.getID());
    append(sb, "file_name", fileName);
    append(sb, "file_path", filePath);
    append(sb, "file_size", fileSize);
    append(sb, "bytes_complete", bytesComplete);
    append(sb, "bytes_remaining", bytesRemaining);
    append(sb, "estimated_transfer_bps", estimatedBandwidthBps);
    append(sb, "status", "queued");
    append(sb, "general_type", generalType);
    append(sb, "title", title);
    append(sb, "episode", episode);
    append(sb, "description", desc);
    append(sb, "record_time", mf.getRecordTime());
    append(sb, "record_duration", mf.getRecordDuration());
    append(sb, "ng_client_id", ngClientId == null ? "" : ngClientId);
    append(sb, "ng_version", ngVersion == null ? "" : ngVersion);
    append(sb, "session_ip", sessionClientIp == null ? "" : sessionClientIp);
    append(sb, "token", tokenIssue != null ? tokenIssue.token : "");
    append(sb, "token_hash", tokenIssue != null ? tokenIssue.tokenHash : "");
    append(sb, "token_expires_at", tokenIssue != null ? tokenIssue.expiresAt : 0L);
    append(sb, "background_requested", backgroundRequested);
    append(sb, "resume_supported", true);
    append(sb, "retry_allowed", true);
    append(sb, "retry_until", tokenIssue != null ? tokenIssue.expiresAt : 0L);
    appendArray(sb, "capabilities", clientCapabilities);
    sb.append('}');
    return sb.toString();
  }

  private static void append(StringBuilder sb, String key, String value)
  {
    if (sb.charAt(sb.length() - 1) != '{') sb.append(',');
    sb.append('"').append(escape(key)).append('"').append(':');
    sb.append('"').append(escape(value == null ? "" : value)).append('"');
  }

  private static void append(StringBuilder sb, String key, long value)
  {
    if (sb.charAt(sb.length() - 1) != '{') sb.append(',');
    sb.append('"').append(escape(key)).append('"').append(':').append(value);
  }

  private static void append(StringBuilder sb, String key, boolean value)
  {
    if (sb.charAt(sb.length() - 1) != '{') sb.append(',');
    sb.append('"').append(escape(key)).append('"').append(':').append(value ? "true" : "false");
  }

  private static void appendArray(StringBuilder sb, String key, java.util.Set<String> values)
  {
    if (sb.charAt(sb.length() - 1) != '{') sb.append(',');
    sb.append('"').append(escape(key)).append('"').append(':').append('[');
    if (values != null)
    {
      boolean first = true;
      for (String val : values)
      {
        if (!first) sb.append(',');
        first = false;
        sb.append('"').append(escape(val)).append('"');
      }
    }
    sb.append(']');
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

  private static String normalizeDownloadMode(String mode)
  {
    if (mode == null)
      return "foreground";
    String rv = mode.trim().toLowerCase();
    return "background".equals(rv) ? rv : "foreground";
  }
}
