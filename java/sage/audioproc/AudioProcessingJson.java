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
 */
public final class AudioProcessingJson
{
  private static final Gson GSON = new GsonBuilder().create();

  private AudioProcessingJson()
  {
  }

  /** Parses an {@code AUDIO_PROCESSING_SETTINGS_STATE} JSON payload; never throws, fails safe to {@link AudioProcessingSettings#DISABLED}. */
  public static AudioProcessingSettings parseSettings(String json)
  {
    JsonObject obj = parseObjectOrNull(json);
    if (obj == null)
      return AudioProcessingSettings.DISABLED;
    try
    {
      AudioProcessingSettings.Builder b = AudioProcessingSettings.builder();
      b.location(AudioProcessingLocation.fromWire(getString(obj, "location", null)));
      b.eqEnabled(getBoolean(obj, "eqEnabled", false));
      b.preampDb(getDouble(obj, "preampDb", 0.0));

      if (obj.has("bands") && obj.get("bands").isJsonArray())
      {
        JsonArray arr = obj.getAsJsonArray("bands");
        List<EqualizerBand> bands = new ArrayList<EqualizerBand>();
        for (JsonElement el : arr)
        {
          if (el == null || !el.isJsonObject())
            continue;
          JsonObject bandObj = el.getAsJsonObject();
          bands.add(new EqualizerBand(getDouble(bandObj, "frequencyHz", 0.0), getDouble(bandObj, "gainDb", 0.0)));
        }
        b.bands(bands);
      }

      if (obj.has("nightMode") && obj.get("nightMode").isJsonObject())
      {
        JsonObject nm = obj.getAsJsonObject("nightMode");
        b.nightMode(new NightModeSettings(
            NightModeMode.fromWire(getString(nm, "mode", null)),
            NightModeIntensity.fromWire(getString(nm, "intensity", null))));
      }

      b.clientSettingsVersion((long) getDouble(obj, "clientSettingsVersion", 0.0));
      return b.build();
    }
    catch (RuntimeException e)
    {
      return AudioProcessingSettings.DISABLED;
    }
  }

  /** Parses an {@code AUDIO_PROCESSING_CAPABILITIES} JSON payload; never throws, fails safe to {@link AudioProcessingCapabilities#NONE}. */
  public static AudioProcessingCapabilities parseCapabilities(String json)
  {
    JsonObject obj = parseObjectOrNull(json);
    if (obj == null)
      return AudioProcessingCapabilities.NONE;
    try
    {
      return AudioProcessingCapabilities.builder()
          .clientSideEqSupported(getBoolean(obj, "clientSideEqSupported", false))
          .serverEqPlanSupported(getBoolean(obj, "serverEqPlanSupported", false))
          .platformNightModeAvailable(getBoolean(obj, "platformNightModeAvailable", false))
          .maxEqBands((int) getDouble(obj, "maxEqBands", 0.0))
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

  /** Serializes a resolved {@link AudioProcessingPlan} to the {@code AUDIO_PROCESSING_PLAN} JSON payload. */
  public static String toJson(AudioProcessingPlan plan)
  {
    if (plan == null)
      return "{}";
    JsonObject obj = new JsonObject();
    obj.addProperty("location", plan.getResolvedLocation().name());
    obj.addProperty("reason", plan.getReason());
    if (plan.getFilterGraph() != null)
      obj.addProperty("filterGraph", plan.getFilterGraph());
    if (plan.getFilterGraphHash() != null)
      obj.addProperty("filterGraphHash", plan.getFilterGraphHash());
    if (plan.getSettingsHash() != null)
      obj.addProperty("settingsHash", plan.getSettingsHash());
    obj.addProperty("clientMustDisableDsp", plan.isClientMustDisableDsp());
    if (plan.getSourceAudioCodec() != null)
      obj.addProperty("sourceAudioCodec", plan.getSourceAudioCodec());
    if (plan.getTargetAudioCodec() != null)
      obj.addProperty("targetAudioCodec", plan.getTargetAudioCodec());
    obj.addProperty("sampleRate", plan.getSampleRate());
    if (plan.getChannelLayout() != null)
      obj.addProperty("channelLayout", plan.getChannelLayout());
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
