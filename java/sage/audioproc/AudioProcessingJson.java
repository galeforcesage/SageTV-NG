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

import sage.epg.sd.gson.Gson;
import sage.epg.sd.gson.GsonBuilder;
import sage.epg.sd.gson.JsonArray;
import sage.epg.sd.gson.JsonElement;
import sage.epg.sd.gson.JsonObject;
import sage.epg.sd.gson.JsonParser;
import sage.epg.sd.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JSON (de)serialization for the {@code audioproc} wire messages, using the
 * project's existing vendored Gson fork ({@code sage.epg.sd.gson}) rather
 * than a new external dependency.
 *
 * <p>Parsing is hand-rolled field-by-field (mirroring the existing {@code
 * sage.epg.sd.json.locale.SDLanguageDeserializer} style) rather than
 * reflective binding, so that:
 * <ul>
 *   <li>unknown JSON fields are silently ignored (we simply never read
 *       them) -- this is how "ignore client-local night-schedule fields"
 *       is satisfied, with no special-case code required;</li>
 *   <li>unknown/malformed enum wire values fail safe via each enum's {@code
 *       fromWire(String)} rather than throwing;</li>
 *   <li>any malformed/unexpected JSON shape fails safe to a disabled/empty
 *       result instead of throwing out of the protocol-handling path.</li>
 * </ul>
 *
 * <p><b>Naming reconciliation (canonical PRD vs. the currently-shipped live
 * PWA client):</b> intake tolerates both vocabularies simultaneously:
 * <ul>
 *   <li>canonical {@code location} field, OR the legacy PWA {@code
 *       clientProcessing} boolean ({@code clientProcessing===false} is
 *       treated as an explicit SERVER request; {@code true} as CLIENT);</li>
 *   <li>canonical {@code presetId}, OR the legacy PWA {@code presetName};</li>
 *   <li>canonical {@code enabled}, OR this project's earlier internal {@code
 *       eqEnabled} name;</li>
 *   <li>canonical {@code settingsVersion}, OR the earlier internal {@code
 *       clientSettingsVersion} name.</li>
 * </ul>
 * Server-to-client output always emits canonical field/message names only.
 */
public final class AudioProcessingJson
{
  private static final Gson GSON = new GsonBuilder().create();

  private AudioProcessingJson()
  {
  }

  /**
   * Parses an {@code AUDIO_PROCESSING_SETTINGS_STATE} (canonical) or {@code
   * AUDIO_PROCESSING_SETTINGS} (live PWA legacy alias) JSON payload; never
   * throws, fails safe to {@link AudioProcessingSettings#DISABLED}.
   */
  public static AudioProcessingSettings parseSettings(String json)
  {
    JsonObject obj = parseObjectOrNull(json);
    if (obj == null)
      return AudioProcessingSettings.DISABLED;
    try
    {
      AudioProcessingSettings.Builder b = AudioProcessingSettings.builder();
      b.location(resolveLocation(obj));
      b.eqEnabled(getBoolean(obj, "enabled", getBoolean(obj, "eqEnabled", false)));
      b.preampDb(getDouble(obj, "preampDb", 0.0));
      b.settingsVersion((long) getDouble(obj, "settingsVersion", getDouble(obj, "clientSettingsVersion", 0.0)));
      b.updatedAtEpochMs((long) getDouble(obj, "updatedAtEpochMs", 0.0));
      String presetId = getString(obj, "presetId", getString(obj, "presetName", null));
      b.presetId(presetId);

      if (obj.has("bands") && obj.get("bands").isJsonArray())
      {
        JsonArray arr = obj.getAsJsonArray("bands");
        List<EqualizerBand> bands = new ArrayList<EqualizerBand>();
        for (JsonElement el : arr)
        {
          if (el == null || !el.isJsonObject())
            continue;
          JsonObject bandObj = el.getAsJsonObject();
          double freq = getDouble(bandObj, "frequencyHz", 0.0);
          bands.add(new EqualizerBand(
              getString(bandObj, "id", null),
              freq,
              getDouble(bandObj, "gainDb", 0.0),
              getDouble(bandObj, "q", EqualizerBand.DEFAULT_Q),
              getBoolean(bandObj, "enabled", true)));
        }
        b.bands(bands);
      }

      if (obj.has("nightMode") && obj.get("nightMode").isJsonObject())
      {
        JsonObject nm = obj.getAsJsonObject("nightMode");
        NightModeMode mode = NightModeMode.fromWire(getString(nm, "mode", null));
        b.nightMode(new NightModeSettings(
            mode,
            NightModeIntensity.fromWire(getString(nm, "intensity", null)),
            getBoolean(nm, "enabled", mode != NightModeMode.OFF),
            getBoolean(nm, "effectiveNow", false),
            NightModeControllability.fromWire(getString(nm, "controllability", null))));
      }

      return b.build();
    }
    catch (RuntimeException e)
    {
      return AudioProcessingSettings.DISABLED;
    }
  }

  /**
   * Derives the effective {@link AudioProcessingLocation} for a settings
   * payload: prefers the canonical {@code location} field; falls back to
   * the legacy PWA {@code clientProcessing} boolean ({@code false} means
   * "server, please process" -&gt; {@link AudioProcessingLocation#SERVER},
   * {@code true} means "client is processing" -&gt; {@link
   * AudioProcessingLocation#CLIENT}); defaults to {@link
   * AudioProcessingLocation#NONE} if neither is present.
   */
  private static AudioProcessingLocation resolveLocation(JsonObject obj)
  {
    if (obj.has("location") && !obj.get("location").isJsonNull())
      return AudioProcessingLocation.fromWire(getString(obj, "location", null));
    if (obj.has("clientProcessing") && !obj.get("clientProcessing").isJsonNull())
    {
      boolean clientProcessing = getBoolean(obj, "clientProcessing", true);
      return clientProcessing ? AudioProcessingLocation.CLIENT : AudioProcessingLocation.SERVER;
    }
    return AudioProcessingLocation.NONE;
  }

  /**
   * Parses an {@code AUDIO_PROCESSING_CAPABILITIES} JSON payload; never
   * throws, fails safe to {@link AudioProcessingCapabilities#NONE}.
   */
  public static AudioProcessingCapabilities parseCapabilities(String json)
  {
    JsonObject obj = parseObjectOrNull(json);
    if (obj == null)
      return AudioProcessingCapabilities.NONE;
    try
    {
      ClientKind clientKind = ClientKind.fromWire(getString(obj, "clientKind", null));

      List<AudioProcessingLocation> supportedLocations = new ArrayList<AudioProcessingLocation>();
      if (obj.has("supportedLocations") && obj.get("supportedLocations").isJsonArray())
      {
        for (JsonElement el : obj.getAsJsonArray("supportedLocations"))
        {
          if (el != null && !el.isJsonNull())
            supportedLocations.add(AudioProcessingLocation.fromWire(el.getAsString()));
        }
      }

      boolean serverEqPlanSupported = getBoolean(obj, "serverEqPlanSupported", false) || supportedLocations.contains(AudioProcessingLocation.SERVER);

      AudioProcessingCapabilities.GainRangeDb gainRangeDb = null;
      if (obj.has("gainRangeDb") && obj.get("gainRangeDb").isJsonObject())
      {
        JsonObject gr = obj.getAsJsonObject("gainRangeDb");
        gainRangeDb = new AudioProcessingCapabilities.GainRangeDb(
            getDouble(gr, "min", EqualizerBand.MIN_GAIN_DB), getDouble(gr, "max", EqualizerBand.MAX_GAIN_DB));
      }

      return AudioProcessingCapabilities.builder()
          .clientKind(clientKind)
          .clientSideEqSupported(getBoolean(obj, "supportsClientDsp", getBoolean(obj, "clientSideEqSupported", false)))
          .serverEqPlanSupported(serverEqPlanSupported)
          .platformNightModeAvailable(getBoolean(obj, "platformNightModeAvailable", false))
          .maxEqBands((int) getDouble(obj, "supportedBandCount", getDouble(obj, "maxEqBands", 0.0)))
          .supportsEqualizerUi(getBoolean(obj, "supportsEqualizerUi", false))
          .supportsDspActiveReporting(getBoolean(obj, "supportsDspActiveReporting", false))
          .supportsSettingsVersionSync(getBoolean(obj, "supportsSettingsVersionSync", false))
          .supportedLocations(supportedLocations)
          .gainRangeDb(gainRangeDb)
          .supportsBiquad(getBoolean(obj, "supportsBiquad", false))
          .supportsAndroidEqualizer(getBoolean(obj, "supportsAndroidEqualizer", false))
          .supportsNightMode(getBoolean(obj, "supportsNightMode", false))
          .supportsRemoteFocusNav(getBoolean(obj, "supportsRemoteFocusNav", false))
          .localPersistence(LocalPersistence.fromWire(getString(obj, "localPersistence", null)))
          .build();
    }
    catch (RuntimeException e)
    {
      return AudioProcessingCapabilities.NONE;
    }
  }

  /** Parses an {@code AUDIO_PROCESSING_DSP_ACTIVE} scalar wire value ("true"/"false"/"1"/"0"). */
  public static boolean parseDspActive(String value)
  {
    if (value == null)
      return false;
    String trimmed = value.trim();
    return trimmed.equalsIgnoreCase("true") || trimmed.equals("1");
  }

  /**
   * Parses the full canonical {@code AUDIO_PROCESSING_DSP_ACTIVE} JSON
   * object payload into an {@link AudioProcessingState}; never throws,
   * fails safe to an inactive/NONE state. If {@code json} is a bare
   * scalar (not a JSON object), falls back to {@link
   * #parseDspActive(String)} for the {@code dspActive} flag only.
   */
  public static AudioProcessingState parseState(String json)
  {
    JsonObject obj = parseObjectOrNull(json);
    if (obj == null)
    {
      return new AudioProcessingState(null, parseDspActive(json), AudioProcessingLocation.NONE,
          null, null, AudioProcessingEngineName.None, null, null);
    }
    try
    {
      Long appliedVersion = obj.has("appliedSettingsVersion") && !obj.get("appliedSettingsVersion").isJsonNull()
          ? Long.valueOf((long) getDouble(obj, "appliedSettingsVersion", 0.0)) : null;
      return new AudioProcessingState(
          getString(obj, "playbackSessionId", null),
          getBoolean(obj, "dspActive", false),
          AudioProcessingLocation.fromWire(getString(obj, "activeLocation", null)),
          appliedVersion,
          getString(obj, "appliedSettingsHash", null),
          AudioProcessingEngineName.fromWire(getString(obj, "engineName", null)),
          getString(obj, "planId", null),
          getString(obj, "errorCode", null));
    }
    catch (RuntimeException e)
    {
      return new AudioProcessingState(null, false, AudioProcessingLocation.NONE,
          null, null, AudioProcessingEngineName.None, null, null);
    }
  }

  /** Serializes a resolved {@link AudioProcessingPlan} to the canonical {@code AUDIO_PROCESSING_PLAN} JSON payload. */
  public static String toJson(AudioProcessingPlan plan)
  {
    if (plan == null)
      return "{}";
    JsonObject obj = new JsonObject();
    obj.addProperty("schemaVersion", plan.getSchemaVersion());
    if (plan.getPlanId() != null)
      obj.addProperty("planId", plan.getPlanId());
    if (plan.getPlaybackSessionId() != null)
      obj.addProperty("playbackSessionId", plan.getPlaybackSessionId());
    obj.addProperty("location", plan.getResolvedLocation().name());
    if (plan.getSettingsVersionAccepted() != null)
      obj.addProperty("settingsVersionAccepted", plan.getSettingsVersionAccepted());
    obj.addProperty("reason", plan.getReason());
    if (plan.getFilterGraph() != null)
      obj.addProperty("ffmpegFilterGraph", plan.getFilterGraph());
    if (plan.getFilterGraphHash() != null)
      obj.addProperty("filterGraphHash", plan.getFilterGraphHash());
    if (plan.getSettingsHashAccepted() != null)
      obj.addProperty("settingsHashAccepted", plan.getSettingsHashAccepted());
    obj.addProperty("clientMustDisableDsp", plan.isClientMustDisableDsp());
    obj.addProperty("serverWillApplyDsp", plan.isServerWillApplyDsp());
    if (plan.getSourceAudioCodec() != null)
      obj.addProperty("sourceAudioCodec", plan.getSourceAudioCodec());
    if (plan.getTargetAudioCodec() != null)
      obj.addProperty("targetAudioCodec", plan.getTargetAudioCodec());
    obj.addProperty("sampleRate", plan.getSampleRate());
    if (plan.getChannelLayout() != null)
      obj.addProperty("channelLayout", plan.getChannelLayout());
    if (!plan.getDiagnostics().isEmpty())
    {
      JsonObject diag = new JsonObject();
      for (Map.Entry<String, Object> entry : plan.getDiagnostics().entrySet())
      {
        Object v = entry.getValue();
        if (v == null)
          continue;
        else if (v instanceof Boolean)
          diag.addProperty(entry.getKey(), (Boolean) v);
        else if (v instanceof Number)
          diag.addProperty(entry.getKey(), (Number) v);
        else
          diag.addProperty(entry.getKey(), String.valueOf(v));
      }
      obj.add("diagnostics", diag);
    }
    return GSON.toJson(obj);
  }

  private static JsonObject parseObjectOrNull(String json)
  {
    if (json == null || json.trim().length() == 0)
      return null;
    try
    {
      JsonElement root = new JsonParser().parse(json);
      if (root == null || !root.isJsonObject())
        return null;
      return root.getAsJsonObject();
    }
    catch (JsonSyntaxException e)
    {
      return null;
    }
    catch (RuntimeException e)
    {
      return null;
    }
  }

  private static String getString(JsonObject obj, String key, String def)
  {
    if (obj.has(key) && !obj.get(key).isJsonNull())
    {
      try
      {
        return obj.get(key).getAsString();
      }
      catch (RuntimeException e)
      {
        return def;
      }
    }
    return def;
  }

  private static double getDouble(JsonObject obj, String key, double def)
  {
    if (obj.has(key) && !obj.get(key).isJsonNull())
    {
      try
      {
        return obj.get(key).getAsDouble();
      }
      catch (RuntimeException e)
      {
        return def;
      }
    }
    return def;
  }

  private static boolean getBoolean(JsonObject obj, String key, boolean def)
  {
    if (obj.has(key) && !obj.get(key).isJsonNull())
    {
      try
      {
        return obj.get(key).getAsBoolean();
      }
      catch (RuntimeException e)
      {
        return def;
      }
    }
    return def;
  }
}
