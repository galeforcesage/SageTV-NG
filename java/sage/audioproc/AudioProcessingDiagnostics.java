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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the structured diagnostics event for a single resolved {@link
 * AudioProcessingPlan}, per the canonical Phase 7 field list. Pure/
 * deterministic (no I/O, no logging) so it is trivially unit-testable;
 * callers (e.g. {@code MiniPlayer}) decide how/whether to log the result.
 *
 * <p>Every plan/diagnostic event carries: {@code playbackSessionId, planId,
 * clientKind, settingsVersion, settingsHash, location, clientDspActive,
 * serverDspActive, ffmpegFiltersAvailable, filterGraphHash,
 * sourceAudioCodec, targetAudioCodec, sampleRate, channelLayout,
 * doubleProcessingPrevented, errorCode}. Night-mode adds: {@code
 * nightModeEnabled, nightModeMode, nightModeControllability,
 * nightModeActive, nightModeEngine, nightModeRestoreRequired,
 * nightModeRestoreSucceeded, nightModeEffectiveNow,
 * nightModeIntensitySemantics="SUPPRESSION_STRENGTH",
 * nightModeScheduleClientLocal=true}.
 */
public final class AudioProcessingDiagnostics
{
  private AudioProcessingDiagnostics()
  {
  }

  /**
   * Builds the diagnostics field map for one resolved plan. {@code
   * clientState} and {@code ffmpegCaps} may be {@code null} (e.g. a plan
   * built without full context); every field degrades to a safe default
   * rather than throwing.
   */
  public static Map<String, Object> buildEvent(AudioProcessingClientState clientState,
      AudioFilterCapabilities ffmpegCaps, AudioProcessingPlan plan)
  {
    if (plan == null)
      plan = AudioProcessingPlan.none("no plan available", null);

    AudioProcessingSettings settings = clientState == null ? AudioProcessingSettings.DISABLED : clientState.getSettings();
    NightModeSettings nightMode = settings.getNightMode();
    boolean clientDspActive = clientState != null && clientState.isClientDspActive();
    boolean serverDspActive = plan.isServerWillApplyDsp();
    boolean doubleProcessingPrevented = clientDspActive && !serverDspActive
        && plan.getResolvedLocation() == AudioProcessingLocation.NONE;

    Map<String, Object> m = new LinkedHashMap<String, Object>();
    m.put("playbackSessionId", plan.getPlaybackSessionId());
    m.put("planId", plan.getPlanId());
    m.put("clientKind", clientState == null ? null : clientState.getCapabilities().getClientKind().name());
    m.put("settingsVersion", settings.getSettingsVersion());
    m.put("settingsHash", plan.getSettingsHash());
    m.put("location", plan.getResolvedLocation().name());
    m.put("clientDspActive", clientDspActive);
    m.put("serverDspActive", serverDspActive);
    m.put("ffmpegFiltersAvailable", ffmpegCaps != null && ffmpegCaps.supportsEqChain());
    m.put("filterGraphHash", plan.getFilterGraphHash());
    m.put("sourceAudioCodec", plan.getSourceAudioCodec());
    m.put("targetAudioCodec", plan.getTargetAudioCodec());
    m.put("sampleRate", plan.getSampleRate());
    m.put("channelLayout", plan.getChannelLayout());
    m.put("doubleProcessingPrevented", doubleProcessingPrevented);
    // No dedicated error taxonomy in v1: NONE always carries a human-readable
    // reason (plan.getReason()); errorCode surfaces it only when NONE, so a
    // successful SERVER plan reports errorCode=null (nothing to report).
    m.put("errorCode", plan.getResolvedLocation() == AudioProcessingLocation.NONE ? plan.getReason() : null);

    // Night-mode block. NightModeMode.PLATFORM_NIGHT_MODE is never
    // server-executable (see NightModeMode#isServerExecutable) -- the engine
    // is reported as PlatformNightMode/None and nightModeActive is always
    // false for it, regardless of what the client itself is doing locally.
    m.put("nightModeEnabled", nightMode.isEnabled());
    m.put("nightModeMode", nightMode.getMode().name());
    m.put("nightModeControllability", nightMode.getControllability().name());
    boolean nightModeServerExecutable = serverDspActive && nightMode.isEnabled() && !nightMode.isOff()
        && nightMode.getMode().isServerExecutable();
    m.put("nightModeActive", nightModeServerExecutable && nightMode.isEffectiveNow());
    m.put("nightModeEngine", nightModeActiveEngineName(nightMode, serverDspActive));
    // v1 scope: the protocol has no dedicated restore-ack round trip yet
    // (clientMustDisableDsp is fire-and-forget), so these are always
    // reported false/not-yet-tracked rather than guessed.
    m.put("nightModeRestoreRequired", plan.isClientMustDisableDsp());
    m.put("nightModeRestoreSucceeded", false);
    m.put("nightModeEffectiveNow", nightMode.isEffectiveNow());
    m.put("nightModeIntensitySemantics", "SUPPRESSION_STRENGTH");
    m.put("nightModeScheduleClientLocal", Boolean.TRUE);
    return m;
  }

  private static String nightModeActiveEngineName(NightModeSettings nightMode, boolean serverDspActive)
  {
    if (nightMode.getMode() == NightModeMode.PLATFORM_NIGHT_MODE)
      return AudioProcessingEngineName.PlatformNightMode.name();
    if (!nightMode.isEnabled() || nightMode.getMode() == NightModeMode.OFF)
      return AudioProcessingEngineName.None.name();
    if (serverDspActive && nightMode.getMode().isServerExecutable())
      return AudioProcessingEngineName.FFmpegNightMode.name();
    return AudioProcessingEngineName.None.name();
  }

  /** Renders a diagnostics event map as a single deterministic-ordered log line. */
  public static String formatForLog(Map<String, Object> event)
  {
    StringBuilder sb = new StringBuilder("AudioProcessing diagnostic: {");
    boolean first = true;
    for (Map.Entry<String, Object> e : event.entrySet())
    {
      if (!first) sb.append(", ");
      first = false;
      sb.append(e.getKey()).append('=').append(e.getValue());
    }
    sb.append('}');
    return sb.toString();
  }
}
