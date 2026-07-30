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
 * The canonical wire payload of an {@code AUDIO_PROCESSING_DSP_ACTIVE}
 * message (client&rarr;server): {@code { schemaVersion:1, playbackSessionId,
 * dspActive, activeLocation, appliedSettingsVersion?, appliedSettingsHash?,
 * engineName?, planId?, errorCode? }}.
 *
 * <p>This is distinct from {@link AudioProcessingClientState} (this
 * server's own per-connection aggregate of capabilities+settings+dsp-active,
 * which is not itself a wire message). This class is the literal payload
 * shape reported by the client for a single playback session and is not
 * persisted -- callers fold {@link #isDspActive()} into an {@link
 * AudioProcessingClientState} via {@link
 * AudioProcessingClientState#withClientDspActive(boolean, long)} if needed.
 */
public final class AudioProcessingState
{
  public static final int SCHEMA_VERSION = 1;

  private final String playbackSessionId;
  private final boolean dspActive;
  private final AudioProcessingLocation activeLocation;
  private final Long appliedSettingsVersion;
  private final String appliedSettingsHash;
  private final AudioProcessingEngineName engineName;
  private final String planId;
  private final String errorCode;

  public AudioProcessingState(String playbackSessionId, boolean dspActive, AudioProcessingLocation activeLocation,
      Long appliedSettingsVersion, String appliedSettingsHash, AudioProcessingEngineName engineName,
      String planId, String errorCode)
  {
    this.playbackSessionId = playbackSessionId;
    this.dspActive = dspActive;
    this.activeLocation = activeLocation == null ? AudioProcessingLocation.NONE : activeLocation;
    this.appliedSettingsVersion = appliedSettingsVersion;
    this.appliedSettingsHash = appliedSettingsHash;
    this.engineName = engineName == null ? AudioProcessingEngineName.None : engineName;
    this.planId = planId;
    this.errorCode = errorCode;
  }

  public int getSchemaVersion()
  {
    return SCHEMA_VERSION;
  }

  public String getPlaybackSessionId()
  {
    return playbackSessionId;
  }

  public boolean isDspActive()
  {
    return dspActive;
  }

  public AudioProcessingLocation getActiveLocation()
  {
    return activeLocation;
  }

  /** The client-local settings version applied by its active DSP engine, if reported. */
  public Long getAppliedSettingsVersion()
  {
    return appliedSettingsVersion;
  }

  /** The client-local settings hash applied by its active DSP engine, if reported. */
  public String getAppliedSettingsHash()
  {
    return appliedSettingsHash;
  }

  public AudioProcessingEngineName getEngineName()
  {
    return engineName;
  }

  /** The server plan id this state corresponds to, if the client is echoing back a prior {@code AUDIO_PROCESSING_PLAN}. */
  public String getPlanId()
  {
    return planId;
  }

  public String getErrorCode()
  {
    return errorCode;
  }

  @Override
  public String toString()
  {
    return "AudioProcessingState[playbackSessionId=" + playbackSessionId + ", dspActive=" + dspActive
        + ", activeLocation=" + activeLocation + ", engineName=" + engineName
        + ", planId=" + planId + ", errorCode=" + errorCode + "]";
  }
}
