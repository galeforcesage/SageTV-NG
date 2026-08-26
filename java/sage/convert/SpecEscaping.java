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
package sage.convert;

/**
 * Escaping for the raw-cmdline format-spec wire form
 * ({@code f=...;MRawCmdlineGlobal=...;MRawCmdline=...;}). Character-for-character
 * identical to {@link sage.media.format.MediaFormat#escapeString} /
 * {@link sage.media.format.MediaFormat#unescapeString}, but reimplemented here so
 * the conversion engine has no static-initialization dependency on the media
 * format classes (which pull in {@code sage.Show}). A round-trip through this
 * helper is therefore interchangeable with a round-trip through
 * {@code ContainerFormat.buildFormatFromString} in the running server.
 */
final class SpecEscaping
{
  private SpecEscaping() { }

  /** Escapes {@code \}, {@code =}, {@code ;}, {@code [}, {@code ]}. */
  static String escape(String s)
  {
    if (s == null) return s;
    if (s.indexOf('\\') != -1) s = s.replace("\\", "\\\\");
    if (s.indexOf('=') != -1)  s = s.replace("=", "\\=");
    if (s.indexOf(';') != -1)  s = s.replace(";", "\\;");
    if (s.indexOf('[') != -1)  s = s.replace("[", "\\[");
    if (s.indexOf(']') != -1)  s = s.replace("]", "\\]");
    return s;
  }

  /** Inverse of {@link #escape(String)}. */
  static String unescape(String s)
  {
    if (s == null) return null;
    StringBuilder sb = null;
    for (int i = 0; i < s.length(); i++)
    {
      char c = s.charAt(i);
      if (c == '\\')
      {
        if (sb == null) sb = new StringBuilder(s.substring(0, i));
        if (i < s.length() - 1) sb.append(s.charAt(++i));
      }
      else if (sb != null)
      {
        sb.append(c);
      }
    }
    return sb != null ? sb.toString() : s;
  }
}
