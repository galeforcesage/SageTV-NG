/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package sage.hdhr;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Immutable representation of HDHomeRun {@code /sys/features} values.
 *
 * <p>Both category names and values are retained as strings so new firmware
 * capabilities remain visible without requiring parser changes.
 */
public final class HDHomeRunDeviceCapabilities
{
  public static final HDHomeRunDeviceCapabilities EMPTY =
      new HDHomeRunDeviceCapabilities(Collections.<String, Set<String>>emptyMap());

  private final Map<String, Set<String>> categories;

  private HDHomeRunDeviceCapabilities(Map<String, Set<String>> categories)
  {
    this.categories = categories;
  }

  /**
   * Parses the line-oriented feature response.
   *
   * @param response raw {@code /sys/features} response
   * @return immutable capabilities, never {@code null}
   */
  static HDHomeRunDeviceCapabilities parse(String response)
  {
    if (response == null || response.trim().length() == 0) return EMPTY;
    Map<String, Set<String>> parsed = new LinkedHashMap<String, Set<String>>();
    String[] lines = response.split("\\r?\\n");
    for (String line : lines)
    {
      if (line == null) continue;
      int colon = line.indexOf(':');
      if (colon <= 0) continue;
      String category = line.substring(0, colon).trim();
      if (category.length() == 0) continue;
      Set<String> values = parsed.get(category);
      if (values == null)
      {
        values = new LinkedHashSet<String>();
        parsed.put(category, values);
      }
      String rawValues = line.substring(colon + 1).trim();
      if (rawValues.length() > 0)
      {
        for (String value : rawValues.split("\\s+"))
          if (value.length() > 0) values.add(value);
      }
    }
    if (parsed.isEmpty()) return EMPTY;
    Map<String, Set<String>> immutable = new LinkedHashMap<String, Set<String>>();
    for (Map.Entry<String, Set<String>> entry : parsed.entrySet())
      immutable.put(entry.getKey(), Collections.unmodifiableSet(
          new LinkedHashSet<String>(entry.getValue())));
    return new HDHomeRunDeviceCapabilities(
        Collections.unmodifiableMap(immutable));
  }

  /**
   * Returns all reported values for a category.
   *
   * @param category category such as {@code modulation}
   * @return immutable values, or an empty set
   */
  public Set<String> getValues(String category)
  {
    Set<String> values = categories.get(category);
    return values == null ? Collections.<String>emptySet() : values;
  }

  /**
   * Tests whether any category reports a capability value.
   *
   * @param value capability value such as {@code atsc3}
   * @return {@code true} when reported
   */
  public boolean supports(String value)
  {
    if (value == null) return false;
    for (Set<String> values : categories.values())
      if (values.contains(value)) return true;
    return false;
  }

  /**
   * Tests whether one category reports a capability value.
   *
   * @param category category such as {@code modulation}
   * @param value capability value such as {@code atsc3}
   * @return {@code true} when reported in that category
   */
  public boolean supports(String category, String value)
  {
    return value != null && getValues(category).contains(value);
  }

  /** @return {@code true} when the device reported no feature categories */
  public boolean isEmpty()
  {
    return categories.isEmpty();
  }

  /**
   * Returns every reported category and value.
   *
   * @return immutable feature map
   */
  public Map<String, Set<String>> asMap()
  {
    return categories;
  }

  @Override
  public String toString()
  {
    return categories.toString();
  }
}
