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
  private final AudioProcessingLocation resolvedLocation;
  private final String reason;
  private final String filterGraph;
  private final String filterGraphHash;
  private final String settingsHash;
  private final boolean clientMustDisableDsp;
  private final String sourceAudioCodec;
  private final String targetAudioCodec;
  private final int sampleRate;
  private final String channelLayout;

  private AudioProcessingPlan(Builder b)
  {
    this.resolvedLocation = b.resolvedLocation;
    this.reason = b.reason;
    this.filterGraph = b.filterGraph;
    this.filterGraphHash = b.filterGraph == null ? null : AudioProcessingHashing.sha256Hex16(b.filterGraph);
    this.settingsHash = b.settingsHash;
    this.clientMustDisableDsp = b.clientMustDisableDsp;
    this.sourceAudioCodec = b.sourceAudioCodec;
    this.targetAudioCodec = b.targetAudioCodec;
    this.sampleRate = b.sampleRate;
    this.channelLayout = b.channelLayout;
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

  public AudioProcessingLocation getResolvedLocation()
  {
    return resolvedLocation;
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

  @Override
  public String toString()
  {
    return "AudioProcessingPlan[location=" + resolvedLocation + ", reason=" + reason
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
    private AudioProcessingLocation resolvedLocation = AudioProcessingLocation.NONE;
    private String reason = "";
    private String filterGraph = null;
    private String settingsHash = null;
    private boolean clientMustDisableDsp = false;
    private String sourceAudioCodec = null;
    private String targetAudioCodec = null;
    private int sampleRate = 0;
    private String channelLayout = null;

    public Builder resolvedLocation(AudioProcessingLocation v)
    {
      this.resolvedLocation = v == null ? AudioProcessingLocation.NONE : v;
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

    public AudioProcessingPlan build()
    {
      return new AudioProcessingPlan(this);
    }
  }
}
