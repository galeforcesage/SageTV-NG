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

import java.io.*;
import java.util.*;

/**
 * Manages server-side client capability profiles.
 * Loads profiles from profiles.json, resolves client→profile mappings,
 * and provides the EffectiveCaps for each client connection.
 *
 * Thread-safe: profiles are loaded once and stored in a concurrent map.
 * Reload can be triggered at runtime via {@link #reload()}.
 */
public class ClientProfileManager
{
  private static ClientProfileManager instance;

  private final Map<String, ClientProfile> profiles = new LinkedHashMap<>();
  private final String profilesPath;

  private ClientProfileManager(String basePath)
  {
    this.profilesPath = basePath + File.separator + "profiles.json";
    load();
  }

  public static synchronized ClientProfileManager getInstance()
  {
    if (instance == null)
    {
      String basePath = System.getProperty("user.dir");
      instance = new ClientProfileManager(basePath);
    }
    return instance;
  }

  // For testing
  static synchronized ClientProfileManager createForTesting(String basePath)
  {
    instance = new ClientProfileManager(basePath);
    return instance;
  }

  static synchronized void resetInstance()
  {
    instance = null;
  }

  public void reload()
  {
    synchronized (profiles)
    {
      profiles.clear();
      load();
    }
  }

  private void load()
  {
    File f = new File(profilesPath);
    if (!f.exists())
    {
      if (sage.Sage.DBG) System.out.println("ClientProfileManager: No profiles.json found at " + profilesPath + ", loading defaults");
      loadDefaults();
      return;
    }

    try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8")))
    {
      String json = readFully(reader);
      parseProfiles(json);
      if (sage.Sage.DBG) System.out.println("ClientProfileManager: Loaded " + profiles.size() + " profiles from " + profilesPath);
    }
    catch (Exception e)
    {
      System.out.println("ClientProfileManager: Error loading profiles.json: " + e);
      e.printStackTrace();
      loadDefaults();
    }
  }

  private String readFully(BufferedReader reader) throws IOException
  {
    StringBuilder sb = new StringBuilder();
    char[] buf = new char[4096];
    int n;
    while ((n = reader.read(buf)) != -1)
      sb.append(buf, 0, n);
    return sb.toString();
  }

  /**
   * Minimal JSON parser for profiles.json.
   * Handles our specific schema without requiring external JSON libraries.
   */
  private void parseProfiles(String json)
  {
    // Find "profiles" object
    int profilesIdx = json.indexOf("\"profiles\"");
    if (profilesIdx < 0) return;

    int braceStart = json.indexOf('{', profilesIdx + 10);
    if (braceStart < 0) return;

    // Find the matching close brace for the profiles object
    int depth = 1;
    int pos = braceStart + 1;
    while (pos < json.length() && depth > 0)
    {
      char c = json.charAt(pos);
      if (c == '{') depth++;
      else if (c == '}') depth--;
      if (depth > 0) pos++;
    }
    String profilesBlock = json.substring(braceStart + 1, pos);

    // Parse individual profile entries
    int cursor = 0;
    while (cursor < profilesBlock.length())
    {
      // Find next profile key
      int keyStart = profilesBlock.indexOf('"', cursor);
      if (keyStart < 0) break;
      int keyEnd = profilesBlock.indexOf('"', keyStart + 1);
      if (keyEnd < 0) break;
      String profileId = profilesBlock.substring(keyStart + 1, keyEnd);

      // Find the profile object
      int objStart = profilesBlock.indexOf('{', keyEnd);
      if (objStart < 0) break;
      int objDepth = 1;
      int objPos = objStart + 1;
      while (objPos < profilesBlock.length() && objDepth > 0)
      {
        char c = profilesBlock.charAt(objPos);
        if (c == '{') objDepth++;
        else if (c == '}') objDepth--;
        if (objDepth > 0) objPos++;
      }
      String objBlock = profilesBlock.substring(objStart, objPos + 1);

      ClientProfile profile = parseOneProfile(profileId, objBlock);
      if (profile != null)
        profiles.put(profileId, profile);

      cursor = objPos + 1;
    }
  }

  private ClientProfile parseOneProfile(String profileId, String json)
  {
    String description = extractString(json, "description");
    boolean managed = extractBoolean(json, "managed", true);
    List<String> containers = extractStringArray(json, "containers");
    List<String> videoCodecs = extractStringArray(json, "video_codecs");
    List<String> audioCodecs = extractStringArray(json, "audio_codecs");
    boolean allowHevc = extractBoolean(json, "allow_hevc", false);
    String autoRemux = extractString(json, "auto_remux");
    int maxW = extractInt(json, "max_video_width", 0);
    int maxH = extractInt(json, "max_video_height", 0);
    boolean allowOverrides = extractBoolean(json, "allow_client_overrides", true);

    if (containers.isEmpty())
      return null;

    return new ClientProfile(profileId, description, managed,
        containers, videoCodecs, audioCodecs, allowHevc, autoRemux,
        maxW, maxH, allowOverrides);
  }

  private String extractString(String json, String key)
  {
    String search = "\"" + key + "\"";
    int idx = json.indexOf(search);
    if (idx < 0) return null;
    int colonIdx = json.indexOf(':', idx + search.length());
    if (colonIdx < 0) return null;
    int qStart = json.indexOf('"', colonIdx + 1);
    if (qStart < 0) return null;
    int qEnd = json.indexOf('"', qStart + 1);
    if (qEnd < 0) return null;
    return json.substring(qStart + 1, qEnd);
  }

  private boolean extractBoolean(String json, String key, boolean defaultVal)
  {
    String search = "\"" + key + "\"";
    int idx = json.indexOf(search);
    if (idx < 0) return defaultVal;
    int colonIdx = json.indexOf(':', idx + search.length());
    if (colonIdx < 0) return defaultVal;
    String rest = json.substring(colonIdx + 1).trim();
    if (rest.startsWith("true")) return true;
    if (rest.startsWith("false")) return false;
    return defaultVal;
  }

  private int extractInt(String json, String key, int defaultVal)
  {
    String search = "\"" + key + "\"";
    int idx = json.indexOf(search);
    if (idx < 0) return defaultVal;
    int colonIdx = json.indexOf(':', idx + search.length());
    if (colonIdx < 0) return defaultVal;
    String rest = json.substring(colonIdx + 1).trim();
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < rest.length(); i++)
    {
      char c = rest.charAt(i);
      if (Character.isDigit(c) || c == '-')
        sb.append(c);
      else if (sb.length() > 0)
        break;
    }
    if (sb.length() == 0) return defaultVal;
    try { return Integer.parseInt(sb.toString()); }
    catch (NumberFormatException e) { return defaultVal; }
  }

  private List<String> extractStringArray(String json, String key)
  {
    List<String> result = new ArrayList<>();
    String search = "\"" + key + "\"";
    int idx = json.indexOf(search);
    if (idx < 0) return result;
    int bracketStart = json.indexOf('[', idx + search.length());
    if (bracketStart < 0) return result;
    int bracketEnd = json.indexOf(']', bracketStart);
    if (bracketEnd < 0) return result;
    String arrayContent = json.substring(bracketStart + 1, bracketEnd);
    int cursor = 0;
    while (cursor < arrayContent.length())
    {
      int qStart = arrayContent.indexOf('"', cursor);
      if (qStart < 0) break;
      int qEnd = arrayContent.indexOf('"', qStart + 1);
      if (qEnd < 0) break;
      result.add(arrayContent.substring(qStart + 1, qEnd));
      cursor = qEnd + 1;
    }
    return result;
  }

  private void loadDefaults()
  {
    profiles.put("hd_legacy_strict", new ClientProfile(
        "hd_legacy_strict", "Unmanaged HDx00 extenders", false,
        Arrays.asList("MPEG2-TS"), Arrays.asList("H.264"), Arrays.asList("AC3", "AAC"),
        false, ClientProfile.AUTO_REMUX_AGGRESSIVE, 1920, 1080, false));

    profiles.put("desktop_default", new ClientProfile(
        "desktop_default", "Windows/macOS default", true,
        Arrays.asList("MP4", "MKV", "MATROSKA", "MPEG2-TS", "MPEG2-PS"), Arrays.asList("H.264"), Arrays.asList("AAC", "AC3"),
        false, ClientProfile.AUTO_REMUX_ON_FAILURE, 0, 0, true));

    profiles.put("desktop_hevc_optin", new ClientProfile(
        "desktop_hevc_optin", "Windows/macOS HEVC opt-in", true,
        Arrays.asList("MP4", "MKV", "MATROSKA", "MPEG2-TS", "MPEG2-PS"), Arrays.asList("H.264", "HEVC"), Arrays.asList("AAC", "AC3"),
        true, ClientProfile.AUTO_REMUX_ON_FAILURE, 0, 0, true));

    profiles.put("android_modern", new ClientProfile(
        "android_modern", "Android MiniClient", true,
        Arrays.asList("MP4", "MKV", "MATROSKA", "MPEG2-TS", "MPEG2-PS"), Arrays.asList("H.264", "HEVC"), Arrays.asList("AAC", "AC3", "EAC3", "DTS", "DCA"),
        true, ClientProfile.AUTO_REMUX_ON_FAILURE, 0, 0, true));

    profiles.put("pwa_safe", new ClientProfile(
        "pwa_safe", "PWA client", true,
        Arrays.asList("MP4"), Arrays.asList("H.264"), Arrays.asList("AAC"),
        false, ClientProfile.AUTO_REMUX_ON_FAILURE, 0, 0, true));

    if (sage.Sage.DBG) System.out.println("ClientProfileManager: Loaded " + profiles.size() + " default profiles");
  }

  public ClientProfile getProfile(String profileId)
  {
    synchronized (profiles)
    {
      return profiles.get(profileId);
    }
  }

  public Collection<String> getAvailableProfileIds()
  {
    synchronized (profiles)
    {
      return new ArrayList<>(profiles.keySet());
    }
  }

  /**
   * Resolve a client to its effective profile based on schema version and client properties.
   *
   * @param schemaVersion client's reported schema version (0 if missing = legacy)
   * @param requestedProfileId profile_id requested by client (may be null)
   * @param isExtender true if client is an HDx00 media extender
   * @param firmwareVersion firmware version string (may be null)
   * @param clientOverrides JSON override map from client (may be null)
   * @return effective ClientProfile, or null only if auto-detection is disabled
   */
  public ClientProfile resolveProfile(int schemaVersion, String requestedProfileId,
      boolean isExtender, String firmwareVersion, Map<String, String> clientOverrides)
  {
    synchronized (profiles)
    {
      // HDx00 always gets hd_legacy_strict regardless of what they request
      if (isExtender && isHDx00(firmwareVersion))
      {
        ClientProfile hdProfile = profiles.get("hd_legacy_strict");
        if (hdProfile != null)
        {
          if (sage.Sage.DBG) System.out.println("ClientProfileManager: HDx00 extender forced to hd_legacy_strict");
          return hdProfile;
        }
      }

      // Legacy clients (schema_version missing or < 2) — fall through to auto-detection
      if (schemaVersion < 2)
      {
        if (sage.Sage.DBG) System.out.println("ClientProfileManager: Legacy client (schema_version=" + schemaVersion + "), will auto-detect profile");
        // Auto-detection happens in autoDetectProfile() called from MiniClientSageRenderer
        // after all client properties are known. Return null here to signal that.
        return null;
      }

      // Schema v2: resolve requested profile
      ClientProfile profile = null;
      if (requestedProfileId != null)
        profile = profiles.get(requestedProfileId);

      if (profile == null)
      {
        if (sage.Sage.DBG) System.out.println("ClientProfileManager: Requested profile '" + requestedProfileId + "' not found, falling back to desktop_default");
        profile = profiles.get("desktop_default");
      }

      if (profile == null)
      {
        // Last resort: use first available profile
        if (!profiles.isEmpty())
          profile = profiles.values().iterator().next();
        else
          profile = new ClientProfile("desktop_default", "Fallback", true,
              Arrays.asList("MP4", "MKV", "MATROSKA", "MPEG2-TS", "MPEG2-PS"), Arrays.asList("H.264"), Arrays.asList("AAC", "AC3"),
              false, ClientProfile.AUTO_REMUX_ON_FAILURE, 0, 0, true);
      }

      // Apply client overrides if allowed
      if (clientOverrides != null && !clientOverrides.isEmpty())
      {
        profile = profile.applyOverrides(clientOverrides);
      }

      if (sage.Sage.DBG) System.out.println("ClientProfileManager: Resolved profile for client: " + profile);
      return profile;
    }
  }

  /**
   * Auto-detect the best profile for a legacy client based on its reported capabilities.
   * Called from MiniClientSageRenderer after all client properties have been read.
   *
   * Detection rules (evaluated in order):
   * 1. Admin override via Sage.properties: miniclient/profile/{clientName}
   * 2. HDx00 extender (firmware match) → hd_legacy_strict
   * 3. iOS/PWA client (iPhoneMode=true) → pwa_safe
   * 4. Non-extender with HEVC in video codecs → desktop_hevc_optin
   * 5. Extender (no mouse) with HEVC → android_modern
   * 6. Extender (no mouse) without HEVC → hd_legacy_strict
   * 7. Non-extender (has mouse) → desktop_default
   *
   * @param clientName MAC-based client identifier for admin override lookup
   * @param isExtender true if INPUT_DEVICES has no MOUSE
   * @param isIOS true if GFX_FIXED_PAR was set (iPhoneMode)
   * @param firmwareVersion FIRMWARE_VERSION string
   * @param clientVideoCodecs the VIDEO_CODECS set reported by client
   * @param clientStreamingProtocols the STREAMING_PROTOCOLS set reported by client
   * @return resolved profile, never null
   */
  public ClientProfile autoDetectProfile(String clientName, boolean isExtender, boolean isIOS,
      String firmwareVersion, java.util.Set clientVideoCodecs,
      java.util.Set clientStreamingProtocols)
  {
    synchronized (profiles)
    {
      // 1. Check for admin override in Sage.properties
      if (clientName != null)
      {
        String adminOverride = sage.Sage.get("miniclient/profile/" + clientName, "");
        if (adminOverride.length() > 0)
        {
          ClientProfile overrideProfile = profiles.get(adminOverride);
          if (overrideProfile != null)
          {
            if (sage.Sage.DBG) System.out.println("ClientProfileManager: Admin override for " + clientName + " → " + adminOverride);
            return overrideProfile;
          }
          else
          {
            if (sage.Sage.DBG) System.out.println("ClientProfileManager: Admin override profile '" + adminOverride + "' not found, ignoring");
          }
        }
      }

      // 2. HDx00 extender
      if (isExtender && isHDx00(firmwareVersion))
      {
        if (sage.Sage.DBG) System.out.println("ClientProfileManager: Auto-detected HDx00 extender → hd_legacy_strict");
        return getOrFallback("hd_legacy_strict");
      }

      // 3. iOS / PWA client
      if (isIOS)
      {
        if (sage.Sage.DBG) System.out.println("ClientProfileManager: Auto-detected iOS/PWA client → pwa_safe");
        return getOrFallback("pwa_safe");
      }

      // 4-5. Check for HEVC support
      boolean hasHevc = clientVideoCodecs != null &&
          (clientVideoCodecs.contains("HEVC") || clientVideoCodecs.contains("H265") || clientVideoCodecs.contains("H.265"));

      if (!isExtender)
      {
        // Desktop client (Windows/macOS placeshifter)
        if (hasHevc)
        {
          if (sage.Sage.DBG) System.out.println("ClientProfileManager: Auto-detected desktop client with HEVC → desktop_hevc_optin");
          return getOrFallback("desktop_hevc_optin");
        }
        // 7. Default desktop
        if (sage.Sage.DBG) System.out.println("ClientProfileManager: Auto-detected desktop client → desktop_default");
        return getOrFallback("desktop_default");
      }
      else
      {
        // Extender (no mouse) but not HDx00
        if (hasHevc)
        {
          if (sage.Sage.DBG) System.out.println("ClientProfileManager: Auto-detected modern extender with HEVC → android_modern");
          return getOrFallback("android_modern");
        }
        // 6. Non-HD extender without HEVC — use legacy strict as safe default
        if (sage.Sage.DBG) System.out.println("ClientProfileManager: Auto-detected extender without HEVC → hd_legacy_strict");
        return getOrFallback("hd_legacy_strict");
      }
    }
  }

  private ClientProfile getOrFallback(String profileId)
  {
    ClientProfile p = profiles.get(profileId);
    if (p != null) return p;
    // Fallback to desktop_default
    p = profiles.get("desktop_default");
    if (p != null) return p;
    // Last resort
    return new ClientProfile("desktop_default", "Fallback", true,
        Arrays.asList("MP4", "MKV", "MATROSKA", "MPEG2-TS", "MPEG2-PS"), Arrays.asList("H.264"), Arrays.asList("AAC", "AC3"),
        false, ClientProfile.AUTO_REMUX_ON_FAILURE, 0, 0, true);
  }

  /**
   * Check if a firmware version string indicates an HDx00 extender.
   */
  private boolean isHDx00(String firmwareVersion)
  {
    if (firmwareVersion == null || firmwareVersion.isEmpty())
      return false; // Can't determine — don't force the strict profile
    // HDx00 firmware versions typically start with specific prefixes
    String fwLower = firmwareVersion.toLowerCase();
    return fwLower.startsWith("hd100") || fwLower.startsWith("hd200") ||
        fwLower.startsWith("hd300") || fwLower.contains("hd100") ||
        fwLower.contains("hd200") || fwLower.contains("hd300");
  }
}
