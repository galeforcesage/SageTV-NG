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
 * The latest known audio-DSP state for a single client connection: its
 * reported {@link AudioProcessingCapabilities}, its most recent {@link
 * AudioProcessingSettings} request, and whether it currently has its own
 * local DSP actively running ({@code AUDIO_PROCESSING_DSP_ACTIVE}).
 *
 * <p>This is an in-memory-only snapshot held by the per-client state
 * registry (never persisted -- see {@link AudioProcessingSettings}'s class
 * javadoc). The {@code clientDspActive} flag is what the resolver uses for
 * double-processing prevention: it must never choose {@link
 * AudioProcessingLocation#SERVER} while the client also reports its own DSP
 * active, and vice versa.
 */
public final class AudioProcessingState
{
  private final String clientId;
  private final AudioProcessingCapabilities capabilities;
  private final AudioProcessingSettings settings;
  private final boolean clientDspActive;
  private final long lastUpdatedMillis;

  public AudioProcessingState(String clientId, AudioProcessingCapabilities capabilities,
      AudioProcessingSettings settings, boolean clientDspActive, long lastUpdatedMillis)
  {
    this.clientId = clientId;
    this.capabilities = capabilities == null ? AudioProcessingCapabilities.NONE : capabilities;
    this.settings = settings == null ? AudioProcessingSettings.DISABLED : settings;
    this.clientDspActive = clientDspActive;
    this.lastUpdatedMillis = lastUpdatedMillis;
  }

  public String getClientId()
  {
    return clientId;
  }

  public AudioProcessingCapabilities getCapabilities()
  {
    return capabilities;
  }

  public AudioProcessingSettings getSettings()
  {
    return settings;
  }

  public boolean isClientDspActive()
  {
    return clientDspActive;
  }

  public long getLastUpdatedMillis()
  {
    return lastUpdatedMillis;
  }

  /** Returns a copy of this state with only the capabilities replaced. */
  public AudioProcessingState withCapabilities(AudioProcessingCapabilities newCapabilities, long updatedMillis)
  {
    return new AudioProcessingState(clientId, newCapabilities, settings, clientDspActive, updatedMillis);
  }

  /** Returns a copy of this state with only the settings replaced. */
  public AudioProcessingState withSettings(AudioProcessingSettings newSettings, long updatedMillis)
  {
    return new AudioProcessingState(clientId, capabilities, newSettings, clientDspActive, updatedMillis);
  }

  /** Returns a copy of this state with only the client-DSP-active flag replaced. */
  public AudioProcessingState withClientDspActive(boolean newClientDspActive, long updatedMillis)
  {
    return new AudioProcessingState(clientId, capabilities, settings, newClientDspActive, updatedMillis);
  }

  @Override
  public String toString()
  {
    return "AudioProcessingState[clientId=" + clientId + ", capabilities=" + capabilities
        + ", settings=" + settings + ", clientDspActive=" + clientDspActive
        + ", lastUpdatedMillis=" + lastUpdatedMillis + "]";
  }
}
