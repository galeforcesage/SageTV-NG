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
 * <p>This is an in-memory-only snapshot held per client connection (e.g. as
 * instance fields on {@code MiniClientSageRenderer}; never persisted -- see
 * {@link AudioProcessingSettings}'s class javadoc). The {@code
 * clientDspActive} flag is what the resolver uses for double-processing
 * prevention: it must never choose {@link AudioProcessingLocation#SERVER}
 * while the client also reports its own DSP active, and vice versa.
 *
 * <p><b>Naming note:</b> this class is the server's internal per-client
 * aggregate and is deliberately named {@code AudioProcessingClientState} to
 * avoid colliding with the canonical PRD/wire model {@link
 * AudioProcessingState}, which represents only the {@code
 * AUDIO_PROCESSING_DSP_ACTIVE} message payload (a much smaller, session-
 * scoped shape). This class *contains* the wire concepts of capabilities +
 * settings + dsp-active as a convenience aggregate for the resolver, but is
 * not itself serialized over the wire.
 */
public final class AudioProcessingClientState
{
  private final String clientId;
  private final AudioProcessingCapabilities capabilities;
  private final AudioProcessingSettings settings;
  private final boolean clientDspActive;
  private final long lastUpdatedMillis;

  public AudioProcessingClientState(String clientId, AudioProcessingCapabilities capabilities,
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
  public AudioProcessingClientState withCapabilities(AudioProcessingCapabilities newCapabilities, long updatedMillis)
  {
    return new AudioProcessingClientState(clientId, newCapabilities, settings, clientDspActive, updatedMillis);
  }

  /** Returns a copy of this state with only the settings replaced. */
  public AudioProcessingClientState withSettings(AudioProcessingSettings newSettings, long updatedMillis)
  {
    return new AudioProcessingClientState(clientId, capabilities, newSettings, clientDspActive, updatedMillis);
  }

  /** Returns a copy of this state with only the client-DSP-active flag replaced. */
  public AudioProcessingClientState withClientDspActive(boolean newClientDspActive, long updatedMillis)
  {
    return new AudioProcessingClientState(clientId, capabilities, settings, newClientDspActive, updatedMillis);
  }

  @Override
  public String toString()
  {
    return "AudioProcessingClientState[clientId=" + clientId + ", capabilities=" + capabilities
        + ", settings=" + settings + ", clientDspActive=" + clientDspActive
        + ", lastUpdatedMillis=" + lastUpdatedMillis + "]";
  }
}
