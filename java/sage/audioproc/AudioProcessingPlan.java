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
package sage.audioproc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The resolver's decision for a single playback session, and the payload
 * sent to the client as an {@code AUDIO_PROCESSING_PLAN} message.
 *
 * <p>This is deliberately NOT a video decision: it says nothing about, and
 * must never be used to influence, {@code DIRECT_PLAY}/{@code REMUX}/
 * transcode selection, resolution, or video bitrate. It only describes what
 * (if anything) the audio stage's {@code -af} filtergraph should be, given
 * whatever audio codec/bitrate the existing playback-decision logic already
 * chose ({@link #getTargetAudioCodec()} always echoes that choice; it is
 * never set independently here).
 */
public final class AudioProcessingPlan
{
  public static final int SCHEMA_VERSION = 1;

  private final String planId;
  private final String playbackSessionId;
  private final AudioProcessingLocation resolvedLocation;
  private final Long settingsVersionAccepted;
  private final String reason;
  private final String filterGraph;
  private final String filterGraphHash;
  private final String settingsHash;
  private final boolean clientMustDisableDsp;
  private final String sourceAudioCodec;
  private final String targetAudioCodec;
  private final int sampleRate;
  private final String channelLayout;
  private final Map<String, Object> diagnostics;

  private AudioProcessingPlan(Builder b)
  {
    this.planId = b.planId;
    this.playbackSessionId = b.playbackSessionId;
    this.resolvedLocation = b.resolvedLocation;
    this.settingsVersionAccepted = b.settingsVersionAccepted;
    this.reason = b.reason;
    this.filterGraph = b.filterGraph;
    this.filterGraphHash = b.filterGraph == null ? null : AudioProcessingHashing.sha256Hex16(b.filterGraph);
    this.settingsHash = b.settingsHash;
    this.clientMustDisableDsp = b.clientMustDisableDsp;
    this.sourceAudioCodec = b.sourceAudioCodec;
    this.targetAudioCodec = b.targetAudioCodec;
    this.sampleRate = b.sampleRate;
    this.channelLayout = b.channelLayout;
    this.diagnostics = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(b.diagnostics));
  }

  /** Convenience factory for the common "nothing to do" outcome. */
  public static AudioProcessingPlan none(String reason, String settingsHash)
  {
    return new Builder()
        .resolvedLocation(AudioProcessingLocation.NONE)
        .reason(reason)
        .settingsHash(settingsHash)
        .build();
  }

  public int getSchemaVersion()
  {
    return SCHEMA_VERSION;
  }

  /** Server-generated unique identifier for this plan; may be {@code null} if not assigned by the caller. */
  public String getPlanId()
  {
    return planId;
  }

  /** The playback session this plan was resolved for; may be {@code null} if not assigned by the caller. */
  public String getPlaybackSessionId()
  {
    return playbackSessionId;
  }

  public AudioProcessingLocation getResolvedLocation()
  {
    return resolvedLocation;
  }

  /** The client {@code settingsVersion} this plan was resolved against, if known. */
  public Long getSettingsVersionAccepted()
  {
    return settingsVersionAccepted;
  }

  /** Human-readable diagnostic explaining why this location/plan was chosen. */
  public String getReason()
  {
    return reason;
  }

  /** The complete {@code -af} filtergraph string; non-null only when {@link #getResolvedLocation()} is {@code SERVER}. */
  public String getFilterGraph()
  {
    return filterGraph;
  }

  public String getFilterGraphHash()
  {
    return filterGraphHash;
  }

  public String getSettingsHash()
  {
    return settingsHash;
  }

  /** Canonical-named alias for {@link #getSettingsHash()} (wire field {@code settingsHashAccepted}). */
  public String getSettingsHashAccepted()
  {
    return settingsHash;
  }

  /**
   * {@code true} when the client must stop running its own local DSP
   * because the server has taken over (only ever {@code true} when {@link
   * #getResolvedLocation()} is {@code SERVER}) -- double-processing
   * prevention.
   */
  public boolean isClientMustDisableDsp()
  {
    return clientMustDisableDsp;
  }

  /**
   * {@code true} when the server itself will apply DSP for this session --
   * derived directly from {@link #getResolvedLocation()} (never stored
   * separately, so it can never drift out of sync with it).
   */
  public boolean isServerWillApplyDsp()
  {
    return resolvedLocation == AudioProcessingLocation.SERVER;
  }

  public String getSourceAudioCodec()
  {
    return sourceAudioCodec;
  }

  /** Echoes whatever the existing audio-selection logic already chose -- never set independently. */
  public String getTargetAudioCodec()
  {
    return targetAudioCodec;
  }

  public int getSampleRate()
  {
    return sampleRate;
  }

  public String getChannelLayout()
  {
    return channelLayout;
  }

  /** Structured diagnostics (Phase 7 field set); never {@code null}, may be empty. */
  public Map<String, Object> getDiagnostics()
  {
    return diagnostics;
  }

  @Override
  public String toString()
  {
    return "AudioProcessingPlan[planId=" + planId + ", playbackSessionId=" + playbackSessionId
        + ", location=" + resolvedLocation + ", reason=" + reason
        + ", filterGraphHash=" + filterGraphHash + ", settingsHash=" + settingsHash
        + ", clientMustDisableDsp=" + clientMustDisableDsp
        + ", sourceAudioCodec=" + sourceAudioCodec + ", targetAudioCodec=" + targetAudioCodec
        + ", sampleRate=" + sampleRate + ", channelLayout=" + channelLayout + "]";
  }

  public static Builder builder()
  {
    return new Builder();
  }

  public static final class Builder
  {
    private String planId;
    private String playbackSessionId;
    private AudioProcessingLocation resolvedLocation = AudioProcessingLocation.NONE;
    private Long settingsVersionAccepted;
    private String reason = "";
    private String filterGraph = null;
    private String settingsHash = null;
    private boolean clientMustDisableDsp = false;
    private String sourceAudioCodec = null;
    private String targetAudioCodec = null;
    private int sampleRate = 0;
    private String channelLayout = null;
    private final Map<String, Object> diagnostics = new LinkedHashMap<String, Object>();

    public Builder planId(String v)
    {
      this.planId = v;
      return this;
    }

    public Builder playbackSessionId(String v)
    {
      this.playbackSessionId = v;
      return this;
    }

    public Builder resolvedLocation(AudioProcessingLocation v)
    {
      this.resolvedLocation = v == null ? AudioProcessingLocation.NONE : v;
      return this;
    }

    public Builder settingsVersionAccepted(Long v)
    {
      this.settingsVersionAccepted = v;
      return this;
    }

    public Builder reason(String v)
    {
      this.reason = v == null ? "" : v;
      return this;
    }

    public Builder filterGraph(String v)
    {
      this.filterGraph = v;
      return this;
    }

    public Builder settingsHash(String v)
    {
      this.settingsHash = v;
      return this;
    }

    public Builder clientMustDisableDsp(boolean v)
    {
      this.clientMustDisableDsp = v;
      return this;
    }

    public Builder sourceAudioCodec(String v)
    {
      this.sourceAudioCodec = v;
      return this;
    }

    public Builder targetAudioCodec(String v)
    {
      this.targetAudioCodec = v;
      return this;
    }

    public Builder sampleRate(int v)
    {
      this.sampleRate = v;
      return this;
    }

    public Builder channelLayout(String v)
    {
      this.channelLayout = v;
      return this;
    }

    public Builder putDiagnostic(String key, Object value)
    {
      if (key != null)
        this.diagnostics.put(key, value);
      return this;
    }

    public Builder diagnostics(Map<String, Object> v)
    {
      this.diagnostics.clear();
      if (v != null)
        this.diagnostics.putAll(v);
      return this;
    }

    public AudioProcessingPlan build()
    {
      return new AudioProcessingPlan(this);
    }
  }
}
