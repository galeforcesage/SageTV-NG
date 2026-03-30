/*
 * Copyright 2024 The SageTV Authors. All Rights Reserved.
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
/*
 * Open-Meteo Weather Provider for SageTV
 *
 * Uses the free Open-Meteo API (https://open-meteo.com/) which requires
 * no API key for non-commercial use under 10,000 daily calls.
 *
 * Usage from STV:
 *
 * Get an instance:
 *   Meteo = tv_sage_weather_OpenMeteo_getInstance()
 *
 * Configure location (latitude,longitude):
 *   tv_sage_weather_OpenMeteo_setLocation(Meteo, "40.7128", "-74.0060")
 *
 * Configure settings:
 *   tv_sage_weather_OpenMeteo_setTemperatureUnit(Meteo, "fahrenheit")  // or "celsius"
 *   tv_sage_weather_OpenMeteo_setShowWindSpeed(Meteo, true)
 *   tv_sage_weather_OpenMeteo_setShowSunriseSunset(Meteo, true)
 *   tv_sage_weather_OpenMeteo_setShowUVIndex(Meteo, true)
 *   tv_sage_weather_OpenMeteo_setShowApparentTemp(Meteo, true)
 *
 * Update data:
 *   tv_sage_weather_OpenMeteo_updateNow(Meteo)
 *
 * Get data:
 *   tv_sage_weather_OpenMeteo_getCurrentCondition(Meteo, "curr_temp")
 *   tv_sage_weather_OpenMeteo_getForecastCondition(Meteo, "hi0")
 *   tv_sage_weather_OpenMeteo_getLocationInfo(Meteo, "curr_location")
 *   tv_sage_weather_OpenMeteo_getSettingsMap(Meteo)
 */
package tv.sage.weather;

import java.io.*;
import java.net.*;
import java.util.*;

public class OpenMeteo
{
  private static final String API_BASE = "https://api.open-meteo.com/v1/forecast";
  private static final String GEOCODE_BASE = "https://geocoding-api.open-meteo.com/v1/search";
  private static final long CACHE_DURATION_CC = 30 * 60 * 1000; // 30 minutes
  private static final long CACHE_DURATION_FC = 2 * 60 * 60 * 1000; // 2 hours

  private static OpenMeteo myInstance;
  private static final Object chosenOneLock = new Object();

  // WMO weather code to icon number mapping (reuses existing WeatherIcons/Images/*.png)
  private static final Map<Integer, String> WMO_ICON_MAP = new HashMap<>();
  private static final Map<Integer, String> WMO_DESCRIPTION_MAP = new HashMap<>();
  private static final Map<Integer, String> WMO_NIGHT_ICON_MAP = new HashMap<>();

  static
  {
    // WMO Code -> Weather.com icon number mapping
    WMO_ICON_MAP.put(0, "32");   // Clear sky -> Sunny
    WMO_ICON_MAP.put(1, "34");   // Mainly clear -> Mostly Sunny
    WMO_ICON_MAP.put(2, "30");   // Partly cloudy -> Partly Cloudy
    WMO_ICON_MAP.put(3, "26");   // Overcast -> Cloudy
    WMO_ICON_MAP.put(45, "20");  // Fog
    WMO_ICON_MAP.put(48, "20");  // Depositing rime fog
    WMO_ICON_MAP.put(51, "9");   // Drizzle light -> Light rain
    WMO_ICON_MAP.put(53, "11");  // Drizzle moderate
    WMO_ICON_MAP.put(55, "12");  // Drizzle dense
    WMO_ICON_MAP.put(56, "8");   // Freezing drizzle light
    WMO_ICON_MAP.put(57, "10");  // Freezing drizzle dense
    WMO_ICON_MAP.put(61, "11");  // Rain slight
    WMO_ICON_MAP.put(63, "12");  // Rain moderate
    WMO_ICON_MAP.put(65, "40");  // Rain heavy
    WMO_ICON_MAP.put(66, "8");   // Freezing rain light
    WMO_ICON_MAP.put(67, "10");  // Freezing rain heavy
    WMO_ICON_MAP.put(71, "13");  // Snowfall slight
    WMO_ICON_MAP.put(73, "14");  // Snowfall moderate
    WMO_ICON_MAP.put(75, "16");  // Snowfall heavy
    WMO_ICON_MAP.put(77, "42");  // Snow grains
    WMO_ICON_MAP.put(80, "39");  // Rain showers slight
    WMO_ICON_MAP.put(81, "39");  // Rain showers moderate
    WMO_ICON_MAP.put(82, "40");  // Rain showers violent
    WMO_ICON_MAP.put(85, "41");  // Snow showers slight
    WMO_ICON_MAP.put(86, "43");  // Snow showers heavy
    WMO_ICON_MAP.put(95, "37");  // Thunderstorm
    WMO_ICON_MAP.put(96, "47");  // Thunderstorm with slight hail
    WMO_ICON_MAP.put(99, "47");  // Thunderstorm with heavy hail

    // Night variants
    WMO_NIGHT_ICON_MAP.put(0, "31");   // Clear sky night
    WMO_NIGHT_ICON_MAP.put(1, "33");   // Mainly clear night
    WMO_NIGHT_ICON_MAP.put(2, "29");   // Partly cloudy night
    WMO_NIGHT_ICON_MAP.put(3, "26");   // Overcast (same day/night)

    // WMO Code -> Description
    WMO_DESCRIPTION_MAP.put(0, "Clear Sky");
    WMO_DESCRIPTION_MAP.put(1, "Mainly Clear");
    WMO_DESCRIPTION_MAP.put(2, "Partly Cloudy");
    WMO_DESCRIPTION_MAP.put(3, "Overcast");
    WMO_DESCRIPTION_MAP.put(45, "Fog");
    WMO_DESCRIPTION_MAP.put(48, "Freezing Fog");
    WMO_DESCRIPTION_MAP.put(51, "Light Drizzle");
    WMO_DESCRIPTION_MAP.put(53, "Moderate Drizzle");
    WMO_DESCRIPTION_MAP.put(55, "Dense Drizzle");
    WMO_DESCRIPTION_MAP.put(56, "Light Freezing Drizzle");
    WMO_DESCRIPTION_MAP.put(57, "Dense Freezing Drizzle");
    WMO_DESCRIPTION_MAP.put(61, "Slight Rain");
    WMO_DESCRIPTION_MAP.put(63, "Moderate Rain");
    WMO_DESCRIPTION_MAP.put(65, "Heavy Rain");
    WMO_DESCRIPTION_MAP.put(66, "Light Freezing Rain");
    WMO_DESCRIPTION_MAP.put(67, "Heavy Freezing Rain");
    WMO_DESCRIPTION_MAP.put(71, "Slight Snowfall");
    WMO_DESCRIPTION_MAP.put(73, "Moderate Snowfall");
    WMO_DESCRIPTION_MAP.put(75, "Heavy Snowfall");
    WMO_DESCRIPTION_MAP.put(77, "Snow Grains");
    WMO_DESCRIPTION_MAP.put(80, "Slight Rain Showers");
    WMO_DESCRIPTION_MAP.put(81, "Moderate Rain Showers");
    WMO_DESCRIPTION_MAP.put(82, "Violent Rain Showers");
    WMO_DESCRIPTION_MAP.put(85, "Slight Snow Showers");
    WMO_DESCRIPTION_MAP.put(86, "Heavy Snow Showers");
    WMO_DESCRIPTION_MAP.put(95, "Thunderstorm");
    WMO_DESCRIPTION_MAP.put(96, "Thunderstorm with Slight Hail");
    WMO_DESCRIPTION_MAP.put(99, "Thunderstorm with Heavy Hail");
  }

  // Instance state
  private String latitude;
  private String longitude;
  private String locationName = "";
  private String temperatureUnit = "fahrenheit"; // "celsius" or "fahrenheit"
  private boolean showWindSpeed = true;
  private boolean showSunriseSunset = true;
  private boolean showUVIndex = true;
  private boolean showApparentTemp = true;
  private Properties props = new Properties();
  private long lastCCUpdateTime;
  private long lastFCUpdateTime;
  private boolean updating;
  private String lastError = "";

  public static OpenMeteo getInstance()
  {
    if (myInstance == null)
    {
      synchronized (chosenOneLock)
      {
        if (myInstance == null)
        {
          myInstance = new OpenMeteo();
        }
      }
    }
    return myInstance;
  }

  protected OpenMeteo()
  {
    loadSettingsFromCache();
  }

  // ---- Configuration Methods ----

  public void setLocation(String lat, String lon)
  {
    if (lat != null && lon != null &&
        (!lat.equals(this.latitude) || !lon.equals(this.longitude)))
    {
      lastCCUpdateTime = 0;
      lastFCUpdateTime = 0;
    }
    this.latitude = lat;
    this.longitude = lon;
    saveSettingsToCache();
  }

  public String getLatitude() { return latitude; }
  public String getLongitude() { return longitude; }
  public String getLocationName() { return locationName; }

  public void setLocationName(String name)
  {
    this.locationName = name;
    props.put("loc/curr_location", name);
    saveSettingsToCache();
  }

  public void setTemperatureUnit(String unit)
  {
    if (unit != null && !unit.equals(this.temperatureUnit))
    {
      lastCCUpdateTime = 0;
      lastFCUpdateTime = 0;
    }
    this.temperatureUnit = unit;
    saveSettingsToCache();
  }

  public String getTemperatureUnit() { return temperatureUnit; }

  public void setShowWindSpeed(boolean show)
  {
    this.showWindSpeed = show;
    saveSettingsToCache();
  }

  public boolean getShowWindSpeed() { return showWindSpeed; }

  public void setShowSunriseSunset(boolean show)
  {
    this.showSunriseSunset = show;
    saveSettingsToCache();
  }

  public boolean getShowSunriseSunset() { return showSunriseSunset; }

  public void setShowUVIndex(boolean show)
  {
    this.showUVIndex = show;
    saveSettingsToCache();
  }

  public boolean getShowUVIndex() { return showUVIndex; }

  public void setShowApparentTemp(boolean show)
  {
    this.showApparentTemp = show;
    saveSettingsToCache();
  }

  public boolean getShowApparentTemp() { return showApparentTemp; }

  public String getLastError() { return lastError; }
  public long getLastUpdateTime() { return Math.max(lastCCUpdateTime, lastFCUpdateTime); }
  public boolean isCurrentlyUpdating() { return updating; }

  // ---- Search / Geocoding ----

  /**
   * Search for locations using Open-Meteo Geocoding API.
   * Returns a Map of "City, Country" -> "lat,lon" strings.
   */
  public Map<String, String> searchLocations(String query)
  {
    Map<String, String> results = new LinkedHashMap<>();
    if (query == null || query.trim().isEmpty()) return results;

    try
    {
      String urlStr = GEOCODE_BASE + "?name=" + URLEncoder.encode(query, "UTF-8") +
          "&count=10&language=en&format=json";
      String json = fetchUrl(urlStr);
      if (json == null) return results;

      // Parse the "results" array from JSON
      // Simple JSON parsing without external libs
      int resultsIdx = json.indexOf("\"results\"");
      if (resultsIdx < 0) return results;

      int arrStart = json.indexOf('[', resultsIdx);
      if (arrStart < 0) return results;

      // Parse each result object
      int pos = arrStart + 1;
      while (pos < json.length())
      {
        int objStart = json.indexOf('{', pos);
        if (objStart < 0) break;
        int objEnd = findMatchingBrace(json, objStart);
        if (objEnd < 0) break;

        String obj = json.substring(objStart, objEnd + 1);
        String name = extractJsonString(obj, "name");
        String country = extractJsonString(obj, "country");
        String admin1 = extractJsonString(obj, "admin1");
        String lat = extractJsonNumber(obj, "latitude");
        String lon = extractJsonNumber(obj, "longitude");

        if (name != null && lat != null && lon != null)
        {
          StringBuilder label = new StringBuilder(name);
          if (admin1 != null && !admin1.isEmpty())
            label.append(", ").append(admin1);
          if (country != null && !country.isEmpty())
            label.append(", ").append(country);
          results.put(label.toString(), lat + "," + lon);
        }
        pos = objEnd + 1;
      }
    }
    catch (Exception e)
    {
      System.out.println(lastError = "Geocoding error: " + e);
    }
    return results;
  }

  // ---- Data Update ----

  public boolean updateNow()
  {
    if (updating) return true;
    if (latitude == null || longitude == null ||
        latitude.isEmpty() || longitude.isEmpty()) return false;

    try
    {
      updating = true;
      lastError = "";

      boolean needCC = (lastCCUpdateTime == 0) ||
          (System.currentTimeMillis() - lastCCUpdateTime > CACHE_DURATION_CC);
      boolean needFC = (lastFCUpdateTime == 0) ||
          (System.currentTimeMillis() - lastFCUpdateTime > CACHE_DURATION_FC);

      if (!needCC && !needFC) return true;

      String tempUnit = "fahrenheit".equals(temperatureUnit) ? "fahrenheit" : "celsius";
      String windUnit = "fahrenheit".equals(temperatureUnit) ? "mph" : "kmh";
      String precipUnit = "fahrenheit".equals(temperatureUnit) ? "inch" : "mm";
      String unitSymbol = "fahrenheit".equals(temperatureUnit) ? "\u00B0F" : "\u00B0C";
      String windSymbol = "fahrenheit".equals(temperatureUnit) ? " mph" : " km/h";
      String precipSymbol = "fahrenheit".equals(temperatureUnit) ? " in" : " mm";

      StringBuilder url = new StringBuilder(API_BASE);
      url.append("?latitude=").append(latitude);
      url.append("&longitude=").append(longitude);
      url.append("&temperature_unit=").append(tempUnit);
      url.append("&wind_speed_unit=").append(windUnit);
      url.append("&precipitation_unit=").append(precipUnit);
      url.append("&timezone=auto");

      // Current conditions
      url.append("&current=temperature_2m,relative_humidity_2m,apparent_temperature,");
      url.append("is_day,precipitation,rain,showers,snowfall,weather_code,");
      url.append("cloud_cover,wind_speed_10m,wind_direction_10m,wind_gusts_10m");

      // Daily forecast (7 days)
      url.append("&daily=weather_code,temperature_2m_max,temperature_2m_min,");
      url.append("apparent_temperature_max,apparent_temperature_min,");
      url.append("sunrise,sunset,uv_index_max,");
      url.append("precipitation_sum,precipitation_probability_max,");
      url.append("wind_speed_10m_max,wind_gusts_10m_max,wind_direction_10m_dominant");

      url.append("&forecast_days=7");

      String json = fetchUrl(url.toString());
      if (json == null)
      {
        lastError = "Failed to fetch weather data from Open-Meteo";
        return false;
      }

      // Check for API error
      if (json.contains("\"error\"") && json.contains("true"))
      {
        String reason = extractJsonString(json, "reason");
        lastError = "Open-Meteo API error: " + (reason != null ? reason : "Unknown");
        return false;
      }

      // Parse current conditions
      String currentBlock = extractJsonObject(json, "current");
      if (currentBlock != null)
      {
        parseCurrentConditions(currentBlock, unitSymbol, windSymbol, precipSymbol);
        lastCCUpdateTime = System.currentTimeMillis();
      }

      // Parse daily forecast
      String dailyBlock = extractJsonObject(json, "daily");
      if (dailyBlock != null)
      {
        parseDailyForecast(dailyBlock, unitSymbol, windSymbol, precipSymbol);
        lastFCUpdateTime = System.currentTimeMillis();
      }

      saveSettingsToCache();
      return true;
    }
    catch (Exception e)
    {
      System.out.println(lastError = "Weather update error: " + e);
      e.printStackTrace();
      return false;
    }
    finally
    {
      updating = false;
    }
  }

  private void parseCurrentConditions(String json, String unitSymbol, String windSymbol, String precipSymbol)
  {
    String temp = extractJsonNumber(json, "temperature_2m");
    String humidity = extractJsonNumber(json, "relative_humidity_2m");
    String apparentTemp = extractJsonNumber(json, "apparent_temperature");
    String isDay = extractJsonNumber(json, "is_day");
    String precip = extractJsonNumber(json, "precipitation");
    String rain = extractJsonNumber(json, "rain");
    String snowfall = extractJsonNumber(json, "snowfall");
    String weatherCode = extractJsonNumber(json, "weather_code");
    String cloudCover = extractJsonNumber(json, "cloud_cover");
    String windSpeed = extractJsonNumber(json, "wind_speed_10m");
    String windDir = extractJsonNumber(json, "wind_direction_10m");
    String windGusts = extractJsonNumber(json, "wind_gusts_10m");

    int wmoCode = 0;
    try { wmoCode = (int) Double.parseDouble(weatherCode != null ? weatherCode : "0"); }
    catch (NumberFormatException e) {}

    boolean isDayTime = !"0".equals(isDay) && !"0.0".equals(isDay);

    // Set current condition properties (matching WeatherDotCom pattern)
    if (temp != null)
      props.put("cc/curr_temp", formatNumber(temp) + unitSymbol);
    if (apparentTemp != null)
    {
      props.put("cc/curr_heatindex", formatNumber(apparentTemp) + unitSymbol);
      props.put("cc/curr_windchill", formatNumber(apparentTemp) + unitSymbol);
      props.put("cc/curr_apparent_temp", formatNumber(apparentTemp) + unitSymbol);
    }
    if (humidity != null)
      props.put("cc/curr_humidity", formatNumber(humidity) + "%");
    if (cloudCover != null)
      props.put("cc/curr_cloud_cover", formatNumber(cloudCover) + "%");

    // Weather description and icon
    String description = WMO_DESCRIPTION_MAP.get(wmoCode);
    props.put("cc/curr_conditions", description != null ? description : "Unknown");

    String icon;
    if (!isDayTime && WMO_NIGHT_ICON_MAP.containsKey(wmoCode))
      icon = WMO_NIGHT_ICON_MAP.get(wmoCode);
    else
      icon = WMO_ICON_MAP.getOrDefault(wmoCode, "na");
    props.put("cc/curr_icon", icon);
    props.put("cc/curr_weather_code", String.valueOf(wmoCode));

    // Wind
    if (windSpeed != null)
    {
      String windDirStr = windDir != null ? degreesToCompass(windDir) : "";
      props.put("cc/curr_wind", windDirStr + " " + formatNumber(windSpeed) + windSymbol);
      props.put("cc/curr_wind_speed", formatNumber(windSpeed) + windSymbol);
      props.put("cc/curr_wind_dir", windDirStr);
    }
    if (windGusts != null)
      props.put("cc/curr_wind_gusts", formatNumber(windGusts) + windSymbol);

    // Precipitation
    if (precip != null)
      props.put("cc/curr_precipitation", formatNumber(precip) + precipSymbol);

    props.put("cc/curr_updated", new java.text.SimpleDateFormat("h:mm a").format(new java.util.Date()));
  }

  private void parseDailyForecast(String json, String unitSymbol, String windSymbol, String precipSymbol)
  {
    // Parse JSON arrays for daily data
    String[] times = extractJsonArray(json, "time");
    String[] weatherCodes = extractJsonArray(json, "weather_code");
    String[] tempMax = extractJsonArray(json, "temperature_2m_max");
    String[] tempMin = extractJsonArray(json, "temperature_2m_min");
    String[] apparentMax = extractJsonArray(json, "apparent_temperature_max");
    String[] apparentMin = extractJsonArray(json, "apparent_temperature_min");
    String[] sunrises = extractJsonArray(json, "sunrise");
    String[] sunsets = extractJsonArray(json, "sunset");
    String[] uvMax = extractJsonArray(json, "uv_index_max");
    String[] precipSum = extractJsonArray(json, "precipitation_sum");
    String[] precipProbMax = extractJsonArray(json, "precipitation_probability_max");
    String[] windMax = extractJsonArray(json, "wind_speed_10m_max");
    String[] gustMax = extractJsonArray(json, "wind_gusts_10m_max");
    String[] windDirDom = extractJsonArray(json, "wind_direction_10m_dominant");

    if (times == null) return;

    String[] dayNames = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};

    for (int i = 0; i < times.length && i < 7; i++)
    {
      String prefix = String.valueOf(i);

      // Date and day name
      String dateStr = times[i].replace("\"", "");
      String dayName = getDayName(dateStr, dayNames);
      props.put("forecast/date" + prefix, dayName + " " + formatDate(dateStr));

      // Weather code, icon, conditions
      int wmoCode = 0;
      if (weatherCodes != null && i < weatherCodes.length)
      {
        try { wmoCode = (int) Double.parseDouble(weatherCodes[i].trim()); }
        catch (NumberFormatException e) {}
      }

      String condDesc = WMO_DESCRIPTION_MAP.getOrDefault(wmoCode, "Unknown");
      String dayIcon = WMO_ICON_MAP.getOrDefault(wmoCode, "na");
      String nightIcon = WMO_NIGHT_ICON_MAP.getOrDefault(wmoCode, dayIcon);

      // Day part conditions (d=day, n=night) - matching WeatherDotCom pattern
      props.put("forecast/conditionsd" + prefix, condDesc);
      props.put("forecast/conditionsn" + prefix, condDesc);
      props.put("forecast/icond" + prefix, dayIcon);
      props.put("forecast/iconn" + prefix, nightIcon);

      // Temperatures
      if (tempMax != null && i < tempMax.length)
        props.put("forecast/hi" + prefix, formatNumber(tempMax[i]) + unitSymbol);
      if (tempMin != null && i < tempMin.length)
        props.put("forecast/low" + prefix, formatNumber(tempMin[i]) + unitSymbol);

      // Apparent temperatures
      if (apparentMax != null && i < apparentMax.length)
        props.put("forecast/apparent_hi" + prefix, formatNumber(apparentMax[i]) + unitSymbol);
      if (apparentMin != null && i < apparentMin.length)
        props.put("forecast/apparent_low" + prefix, formatNumber(apparentMin[i]) + unitSymbol);

      // Sunrise/Sunset - extract time portion
      if (sunrises != null && i < sunrises.length)
        props.put("forecast/sunrise" + prefix, formatTime(sunrises[i]));
      if (sunsets != null && i < sunsets.length)
        props.put("forecast/sunset" + prefix, formatTime(sunsets[i]));

      // UV Index
      if (uvMax != null && i < uvMax.length)
        props.put("forecast/uv" + prefix, formatNumber(uvMax[i]));

      // Precipitation
      if (precipSum != null && i < precipSum.length)
        props.put("forecast/precip_amount" + prefix, formatNumber(precipSum[i]) + precipSymbol);
      if (precipProbMax != null && i < precipProbMax.length)
      {
        props.put("forecast/precipd" + prefix, formatNumber(precipProbMax[i]) + "%");
        props.put("forecast/precipn" + prefix, formatNumber(precipProbMax[i]) + "%");
      }

      // Wind
      if (windMax != null && i < windMax.length)
      {
        String dir = "";
        if (windDirDom != null && i < windDirDom.length)
          dir = degreesToCompass(windDirDom[i]);
        props.put("forecast/windd" + prefix, dir + " " + formatNumber(windMax[i]) + windSymbol);
        props.put("forecast/windn" + prefix, dir + " " + formatNumber(windMax[i]) + windSymbol);
        props.put("forecast/wind_speed" + prefix, formatNumber(windMax[i]) + windSymbol);
      }
      if (gustMax != null && i < gustMax.length)
        props.put("forecast/wind_gusts" + prefix, formatNumber(gustMax[i]) + windSymbol);
    }

    // Also set location sunrise/sunset from today's forecast (day 0)
    if (sunrises != null && sunrises.length > 0)
      props.put("loc/curr_sunrise", formatTime(sunrises[0]));
    if (sunsets != null && sunsets.length > 0)
      props.put("loc/curr_sunset", formatTime(sunsets[0]));
  }

  // ---- Data Access Methods (match WeatherDotCom API) ----

  public Map<String, String> getLocationProperties()
  {
    return getPropertiesWithPrefix("loc/");
  }

  public Map<String, String> getCurrentConditionProperties()
  {
    return getPropertiesWithPrefix("cc/");
  }

  public Map<String, String> getForecastProperties()
  {
    return getPropertiesWithPrefix("forecast/");
  }

  public String getLocationInfo(String propName)
  {
    return props.getProperty("loc/" + propName);
  }

  public String getCurrentCondition(String propName)
  {
    return props.getProperty("cc/" + propName);
  }

  public String getForecastCondition(String propName)
  {
    return props.getProperty("forecast/" + propName);
  }

  /**
   * Returns a settings map for the STV to read current configuration.
   */
  public Map<String, String> getSettingsMap()
  {
    Map<String, String> settings = new LinkedHashMap<>();
    settings.put("latitude", latitude != null ? latitude : "");
    settings.put("longitude", longitude != null ? longitude : "");
    settings.put("location_name", locationName != null ? locationName : "");
    settings.put("temperature_unit", temperatureUnit);
    settings.put("show_wind_speed", String.valueOf(showWindSpeed));
    settings.put("show_sunrise_sunset", String.valueOf(showSunriseSunset));
    settings.put("show_uv_index", String.valueOf(showUVIndex));
    settings.put("show_apparent_temp", String.valueOf(showApparentTemp));
    return settings;
  }

  // ---- Persistence ----

  private void saveSettingsToCache()
  {
    props.put("meteo/latitude", latitude != null ? latitude : "");
    props.put("meteo/longitude", longitude != null ? longitude : "");
    props.put("meteo/location_name", locationName != null ? locationName : "");
    props.put("meteo/temperature_unit", temperatureUnit);
    props.put("meteo/show_wind_speed", String.valueOf(showWindSpeed));
    props.put("meteo/show_sunrise_sunset", String.valueOf(showSunriseSunset));
    props.put("meteo/show_uv_index", String.valueOf(showUVIndex));
    props.put("meteo/show_apparent_temp", String.valueOf(showApparentTemp));
    props.put("meteo/lastCCUpdateTime", String.valueOf(lastCCUpdateTime));
    props.put("meteo/lastFCUpdateTime", String.valueOf(lastFCUpdateTime));

    File cacheFile = new File("openmeteo_cache.properties");
    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(cacheFile)))
    {
      props.store(out, "SageTV Open-Meteo Weather Data");
    }
    catch (Exception e)
    {
      System.out.println("Error saving Open-Meteo cache: " + e);
    }
  }

  private void loadSettingsFromCache()
  {
    props = new Properties();
    File cacheFile = new File("openmeteo_cache.properties");
    try (InputStream in = new BufferedInputStream(new FileInputStream(cacheFile)))
    {
      props.load(in);
    }
    catch (Exception e)
    {
      // No cache yet, that's fine
    }

    latitude = props.getProperty("meteo/latitude");
    longitude = props.getProperty("meteo/longitude");
    locationName = props.getProperty("meteo/location_name", "");
    temperatureUnit = props.getProperty("meteo/temperature_unit", "fahrenheit");
    showWindSpeed = "true".equals(props.getProperty("meteo/show_wind_speed", "true"));
    showSunriseSunset = "true".equals(props.getProperty("meteo/show_sunrise_sunset", "true"));
    showUVIndex = "true".equals(props.getProperty("meteo/show_uv_index", "true"));
    showApparentTemp = "true".equals(props.getProperty("meteo/show_apparent_temp", "true"));

    try
    {
      lastCCUpdateTime = Long.parseLong(props.getProperty("meteo/lastCCUpdateTime", "0"));
      lastFCUpdateTime = Long.parseLong(props.getProperty("meteo/lastFCUpdateTime", "0"));
    }
    catch (NumberFormatException e) {}
  }

  // ---- Utility Methods ----

  private Map<String, String> getPropertiesWithPrefix(String prefix)
  {
    Map<String, String> rv = new LinkedHashMap<>();
    for (String key : props.stringPropertyNames())
    {
      if (key.startsWith(prefix))
        rv.put(key.substring(prefix.length()), props.getProperty(key));
    }
    return rv;
  }

  private String fetchUrl(String urlStr)
  {
    System.out.println("Open-Meteo fetching: " + urlStr);
    HttpURLConnection conn = null;
    try
    {
      URL url = new URL(urlStr);
      conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(15000);
      conn.setReadTimeout(15000);
      conn.setRequestProperty("User-Agent", "SageTV/1.0");

      int responseCode = conn.getResponseCode();
      if (responseCode != 200)
      {
        lastError = "HTTP " + responseCode + " from Open-Meteo";
        return null;
      }

      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(conn.getInputStream(), "UTF-8")))
      {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null)
          sb.append(line);
        return sb.toString();
      }
    }
    catch (Exception e)
    {
      lastError = "Network error: " + e.getMessage();
      return null;
    }
    finally
    {
      if (conn != null) conn.disconnect();
    }
  }

  // ---- Simple JSON Parsing Helpers (no external library needed) ----

  private String extractJsonString(String json, String key)
  {
    String searchKey = "\"" + key + "\"";
    int idx = json.indexOf(searchKey);
    if (idx < 0) return null;
    int colonIdx = json.indexOf(':', idx + searchKey.length());
    if (colonIdx < 0) return null;
    int start = json.indexOf('"', colonIdx + 1);
    if (start < 0) return null;
    int end = json.indexOf('"', start + 1);
    if (end < 0) return null;
    return json.substring(start + 1, end);
  }

  private String extractJsonNumber(String json, String key)
  {
    String searchKey = "\"" + key + "\"";
    int idx = json.indexOf(searchKey);
    if (idx < 0) return null;
    int colonIdx = json.indexOf(':', idx + searchKey.length());
    if (colonIdx < 0) return null;

    int start = colonIdx + 1;
    while (start < json.length() && json.charAt(start) == ' ') start++;
    if (start >= json.length()) return null;

    // Could be a string-wrapped number or raw number
    if (json.charAt(start) == '"')
    {
      int end = json.indexOf('"', start + 1);
      return end > 0 ? json.substring(start + 1, end) : null;
    }

    // null value
    if (json.charAt(start) == 'n') return null;

    int end = start;
    while (end < json.length() && (Character.isDigit(json.charAt(end)) ||
        json.charAt(end) == '.' || json.charAt(end) == '-'))
      end++;
    return start < end ? json.substring(start, end) : null;
  }

  private String extractJsonObject(String json, String key)
  {
    String searchKey = "\"" + key + "\"";
    int idx = json.indexOf(searchKey);
    if (idx < 0) return null;
    int braceStart = json.indexOf('{', idx + searchKey.length());
    if (braceStart < 0) return null;
    int braceEnd = findMatchingBrace(json, braceStart);
    if (braceEnd < 0) return null;
    return json.substring(braceStart, braceEnd + 1);
  }

  private String[] extractJsonArray(String json, String key)
  {
    String searchKey = "\"" + key + "\"";
    int idx = json.indexOf(searchKey);
    if (idx < 0) return null;
    int bracketStart = json.indexOf('[', idx + searchKey.length());
    if (bracketStart < 0) return null;
    int bracketEnd = findMatchingBracket(json, bracketStart);
    if (bracketEnd < 0) return null;

    String arrContent = json.substring(bracketStart + 1, bracketEnd).trim();
    if (arrContent.isEmpty()) return new String[0];

    // Split by comma, respecting quoted strings
    List<String> items = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inQuotes = false;
    for (int i = 0; i < arrContent.length(); i++)
    {
      char c = arrContent.charAt(i);
      if (c == '"') inQuotes = !inQuotes;
      else if (c == ',' && !inQuotes)
      {
        items.add(current.toString().trim().replace("\"", ""));
        current = new StringBuilder();
        continue;
      }
      if (c != '"')
        current.append(c);
    }
    items.add(current.toString().trim().replace("\"", ""));
    return items.toArray(new String[0]);
  }

  private int findMatchingBrace(String json, int openPos)
  {
    int depth = 0;
    boolean inString = false;
    for (int i = openPos; i < json.length(); i++)
    {
      char c = json.charAt(i);
      if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) inString = !inString;
      if (!inString)
      {
        if (c == '{') depth++;
        else if (c == '}') { depth--; if (depth == 0) return i; }
      }
    }
    return -1;
  }

  private int findMatchingBracket(String json, int openPos)
  {
    int depth = 0;
    boolean inString = false;
    for (int i = openPos; i < json.length(); i++)
    {
      char c = json.charAt(i);
      if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) inString = !inString;
      if (!inString)
      {
        if (c == '[') depth++;
        else if (c == ']') { depth--; if (depth == 0) return i; }
      }
    }
    return -1;
  }

  private String degreesToCompass(String degreesStr)
  {
    try
    {
      double deg = Double.parseDouble(degreesStr.trim());
      String[] dirs = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
          "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
      int idx = (int) Math.round(((deg % 360) / 22.5)) % 16;
      return dirs[idx];
    }
    catch (NumberFormatException e)
    {
      return "";
    }
  }

  private String formatNumber(String numStr)
  {
    if (numStr == null) return "";
    try
    {
      double val = Double.parseDouble(numStr.trim());
      if (val == (long) val)
        return String.valueOf((long) val);
      return String.format("%.1f", val);
    }
    catch (NumberFormatException e)
    {
      return numStr.trim();
    }
  }

  private String formatDate(String isoDate)
  {
    // "2026-03-29" -> "Mar 29"
    if (isoDate == null || isoDate.length() < 10) return isoDate != null ? isoDate : "";
    String[] months = {"Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
    try
    {
      int month = Integer.parseInt(isoDate.substring(5, 7));
      int day = Integer.parseInt(isoDate.substring(8, 10));
      return months[month - 1] + " " + day;
    }
    catch (Exception e)
    {
      return isoDate;
    }
  }

  private String formatTime(String isoTime)
  {
    // "2026-03-29T06:45" -> "6:45 AM"
    if (isoTime == null) return "";
    String cleaned = isoTime.replace("\"", "").trim();
    int tIdx = cleaned.indexOf('T');
    if (tIdx < 0) return cleaned;
    String timePart = cleaned.substring(tIdx + 1);
    try
    {
      String[] parts = timePart.split(":");
      int hour = Integer.parseInt(parts[0]);
      String min = parts.length > 1 ? parts[1] : "00";
      String ampm = hour >= 12 ? "PM" : "AM";
      if (hour > 12) hour -= 12;
      if (hour == 0) hour = 12;
      return hour + ":" + min + " " + ampm;
    }
    catch (Exception e)
    {
      return timePart;
    }
  }

  private String getDayName(String isoDate, String[] dayNames)
  {
    try
    {
      java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
      java.util.Date date = sdf.parse(isoDate.replace("\"", "").trim());
      java.util.Calendar cal = java.util.Calendar.getInstance();
      cal.setTime(date);
      return dayNames[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1];
    }
    catch (Exception e)
    {
      return "";
    }
  }
}
