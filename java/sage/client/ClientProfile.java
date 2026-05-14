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

import java.util.*;

/**
 * Represents a server-managed client capability profile.
 * Profiles define what containers, codecs, and behaviors are allowed
 * for a given class of client (managed or unmanaged).
 */
public class ClientProfile
{
  public static final String AUTO_REMUX_DISABLED = "disabled";
  public static final String AUTO_REMUX_ON_FAILURE = "on_failure";
  public static final String AUTO_REMUX_AGGRESSIVE = "aggressive";

  private final String profileId;
  private final String description;
  private final boolean managed;
  private final Set<String> containers;
  private final Set<String> videoCodecs;
  private final Set<String> audioCodecs;
  private final boolean allowHevc;
  private final String autoRemux;
  private final int maxVideoWidth;
  private final int maxVideoHeight;
  private final boolean allowClientOverrides;
  private final LiveTranscodeProfile liveTranscode;

  public ClientProfile(String profileId, String description, boolean managed,
      Collection<String> containers, Collection<String> videoCodecs,
      Collection<String> audioCodecs, boolean allowHevc, String autoRemux,
      int maxVideoWidth, int maxVideoHeight, boolean allowClientOverrides)
  {
    this(profileId, description, managed, containers, videoCodecs, audioCodecs,
        allowHevc, autoRemux, maxVideoWidth, maxVideoHeight, allowClientOverrides, null);
  }

  public ClientProfile(String profileId, String description, boolean managed,
      Collection<String> containers, Collection<String> videoCodecs,
      Collection<String> audioCodecs, boolean allowHevc, String autoRemux,
      int maxVideoWidth, int maxVideoHeight, boolean allowClientOverrides,
      LiveTranscodeProfile liveTranscode)
  {
    this.profileId = profileId;
    this.description = description;
    this.managed = managed;
    this.containers = new LinkedHashSet<>();
    for (String c : containers) this.containers.add(c.toUpperCase());
    this.videoCodecs = new LinkedHashSet<>();
    for (String v : videoCodecs) this.videoCodecs.add(v.toUpperCase());
    this.audioCodecs = new LinkedHashSet<>();
    for (String a : audioCodecs) this.audioCodecs.add(a.toUpperCase());
    this.allowHevc = allowHevc;
    this.autoRemux = autoRemux != null ? autoRemux : AUTO_REMUX_ON_FAILURE;
    this.maxVideoWidth = maxVideoWidth;
    this.maxVideoHeight = maxVideoHeight;
    this.allowClientOverrides = allowClientOverrides;
    this.liveTranscode = liveTranscode;
  }

  public String getProfileId() { return profileId; }
  public String getDescription() { return description; }
  public boolean isManaged() { return managed; }
  public Set<String> getContainers() { return Collections.unmodifiableSet(containers); }
  public Set<String> getVideoCodecs() { return Collections.unmodifiableSet(videoCodecs); }
  public Set<String> getAudioCodecs() { return Collections.unmodifiableSet(audioCodecs); }
  public boolean isAllowHevc() { return allowHevc; }
  public String getAutoRemux() { return autoRemux; }
  public int getMaxVideoWidth() { return maxVideoWidth; }
  public int getMaxVideoHeight() { return maxVideoHeight; }
  public boolean isAllowClientOverrides() { return allowClientOverrides; }
  /** Live-transcode shaping for this profile. Never null -- safe defaults
   *  are returned if the profile JSON omits the {@code liveTranscode} block. */
  public LiveTranscodeProfile getLiveTranscode()
  {
    return liveTranscode != null ? liveTranscode : LiveTranscodeProfile.safeDefault();
  }

  public boolean isContainerAllowed(String container)
  {
    if (container == null) return false;
    String upper = container.toUpperCase();
    if ("MKV".equals(upper) || "MATROSKA".equals(upper))
      return containers.contains("MKV") || containers.contains("MATROSKA");
    return containers.contains(upper);
  }

  public boolean isVideoCodecAllowed(String codec)
  {
    if (codec == null) return false;
    String upper = codec.toUpperCase();
    if ("HEVC".equals(upper) || "H265".equals(upper) || "H.265".equals(upper))
      return allowHevc && videoCodecs.contains("HEVC");
    if ("H264".equals(upper) || "H.264".equals(upper))
      return videoCodecs.contains("H.264") || videoCodecs.contains("H264");
    return videoCodecs.contains(upper);
  }

  public boolean isAudioCodecAllowed(String codec)
  {
    if (codec == null) return false;
    return audioCodecs.contains(codec.toUpperCase());
  }

  public boolean isAutoRemuxEnabled()
  {
    return !AUTO_REMUX_DISABLED.equals(autoRemux);
  }

  public boolean isAutoRemuxAggressive()
  {
    return AUTO_REMUX_AGGRESSIVE.equals(autoRemux);
  }

  /**
   * Apply client overrides to this profile, returning a new effective profile.
   * Only applies overrides if the base profile allows it.
   * Unknown override keys are ignored with a warning.
   */
  public ClientProfile applyOverrides(Map<String, String> overrides)
  {
    if (overrides == null || overrides.isEmpty() || !allowClientOverrides)
      return this;

    Set<String> effectiveContainers = new LinkedHashSet<>(this.containers);
    Set<String> effectiveVideoCodecs = new LinkedHashSet<>(this.videoCodecs);
    Set<String> effectiveAudioCodecs = new LinkedHashSet<>(this.audioCodecs);
    boolean effectiveAllowHevc = this.allowHevc;
    String effectiveAutoRemux = this.autoRemux;

    for (Map.Entry<String, String> entry : overrides.entrySet())
    {
      String key = entry.getKey();
      String value = entry.getValue();
      switch (key)
      {
        case "allow_hevc":
          // Client can request HEVC only if profile already allows it
          if (this.allowHevc)
            effectiveAllowHevc = "true".equalsIgnoreCase(value);
          break;
        case "auto_remux":
          if (AUTO_REMUX_DISABLED.equals(value) || AUTO_REMUX_ON_FAILURE.equals(value) || AUTO_REMUX_AGGRESSIVE.equals(value))
            effectiveAutoRemux = value;
          break;
        default:
          if (sage.Sage.DBG) System.out.println("ClientProfile: ignoring unknown override key '" + key + "' for profile " + profileId);
          break;
      }
    }

    return new ClientProfile(profileId, description, managed,
        effectiveContainers, effectiveVideoCodecs, effectiveAudioCodecs,
        effectiveAllowHevc, effectiveAutoRemux,
        maxVideoWidth, maxVideoHeight, allowClientOverrides, liveTranscode);
  }

  @Override
  public String toString()
  {
    return "ClientProfile[" + profileId + " containers=" + containers +
        " video=" + videoCodecs + " audio=" + audioCodecs +
        " hevc=" + allowHevc + " remux=" + autoRemux + " managed=" + managed + "]";
  }
}
