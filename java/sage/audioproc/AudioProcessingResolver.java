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
 * Decides whether the server should apply audio DSP for a playback session,
 * and if so, builds the {@link AudioProcessingPlan} describing it.
 *
 * <p><b>This is explicitly NOT a video decision.</b> {@link #resolve} takes
 * no video state and returns nothing that can affect {@code DIRECT_PLAY}/
 * {@code REMUX}/transcode selection, resolution, or video bitrate -- those
 * remain 100% governed by the existing playback-decision logic
 * ({@code sage.client.PlaybackDecisionEngine}), which this class never
 * calls into or is called from directly. It also never chooses an audio
 * codec or bitrate: {@code sourceAudioCodec}/{@code targetAudioCodec} here
 * are pass-through values supplied by the caller (which must come from
 * that existing audio-selection logic) and are only echoed into the
 * resulting plan for diagnostics/client display.
 *
 * <p>The server only ever proposes {@link AudioProcessingLocation#SERVER}
 * on an explicit, unambiguous client request: {@code location=SERVER} AND
 * EQ enabled in the client's most recent {@link AudioProcessingSettings},
 * with capabilities confirming the client understands the resulting
 * {@code AUDIO_PROCESSING_PLAN} message, with no client-side DSP currently
 * reported active (double-processing prevention), and with the ffmpeg
 * binary actually able to build the requested filtergraph. Any other case
 * -- no client state, {@code CLIENT}/{@code NONE} location,
 * unsupported client, ambiguous/stale double-active state, or an
 * unbuildable filtergraph -- resolves to {@link AudioProcessingLocation#NONE}
 * with a diagnostic reason, never a guess or a partial plan. There is no
 * separate master on/off switch: the explicit client-signal gate above IS
 * the net-neutrality guarantee -- any client that never asks for
 * {@code location=SERVER} with EQ enabled is byte-for-byte unaffected.
 */
public final class AudioProcessingResolver
{
  private AudioProcessingResolver()
  {
  }

  /**
   * @param clientState the latest known audio-DSP state for this client (capabilities + settings + dspActive), or {@code null} if unknown
   * @param ffmpegCaps the current ffmpeg audio-filter capability probe result
   * @param sourceAudioCodec the source recording's audio codec (diagnostics/echo only)
   * @param targetAudioCodec the audio codec the EXISTING audio-selection logic already chose (diagnostics/echo only -- never chosen here)
   * @param sampleRate the target sample rate the existing pipeline already chose (diagnostics/echo only)
   * @param channelLayout the target channel layout the existing pipeline already chose (diagnostics/echo only)
   */
  public static AudioProcessingPlan resolve(AudioProcessingClientState clientState,
      AudioFilterCapabilities ffmpegCaps, String sourceAudioCodec, String targetAudioCodec,
      int sampleRate, String channelLayout)
  {
    String settingsHash = clientState == null ? null : clientState.getSettings().computeSettingsHash();

    if (clientState == null)
      return AudioProcessingPlan.none("no client audio-processing state known for this session", null);

    // Hard rule: a legacy SageTV/STV client is never offered SERVER audio processing, regardless
    // of any other field it reports. (AudioProcessingCapabilities already forces serverEqPlanSupported
    // and supportedLocations={NONE} for LEGACY, so this is defense-in-depth with a clearer reason.)
    if (clientState.getCapabilities().getClientKind() == ClientKind.LEGACY)
      return AudioProcessingPlan.none("client is a legacy SageTV/STV client; always NONE", settingsHash);

    AudioProcessingSettings settings = clientState.getSettings();

    if (settings.getLocation() != AudioProcessingLocation.SERVER)
      return AudioProcessingPlan.none("client requested location=" + settings.getLocation() + ", not SERVER", settingsHash);

    if (!settings.isEqEnabled())
      return AudioProcessingPlan.none("client requested SERVER location but EQ is not enabled", settingsHash);

    if (!clientState.getCapabilities().isServerEqPlanSupported())
      return AudioProcessingPlan.none("client capabilities do not confirm AUDIO_PROCESSING_PLAN support", settingsHash);

    if (clientState.isClientDspActive())
      return AudioProcessingPlan.none(
          "client reports its own DSP already active; refusing to double-process an ambiguous/stale request",
          settingsHash);

    AudioFilterGraphBuilder.Result built = AudioFilterGraphBuilder.build(settings, ffmpegCaps);
    if (!built.isBuildable())
      return AudioProcessingPlan.none("filtergraph not buildable: " + built.getReason(), settingsHash);

    return AudioProcessingPlan.builder()
        .resolvedLocation(AudioProcessingLocation.SERVER)
        .reason("client requested SERVER location; filtergraph built successfully")
        .filterGraph(built.getFilterGraph())
        .settingsHash(settingsHash)
        .clientMustDisableDsp(true)
        .sourceAudioCodec(sourceAudioCodec)
        .targetAudioCodec(targetAudioCodec)
        .sampleRate(sampleRate)
        .channelLayout(channelLayout)
        .build();
  }
}
