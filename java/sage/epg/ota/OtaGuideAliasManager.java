/*
 * Copyright 2026 SageTV-NG contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package sage.epg.ota;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import sage.EPG;
import sage.EPGDataSource;
import sage.Sage;
import sage.epg.EpgFallbackResolver;

/**
 * Reconciles tunable OTA channel numbers with guide-bearing lineup stations.
 *
 * <p>Some Schedules Direct lineups expose the correct station under a lineup
 * number that differs from the broadcast virtual channel. Each configured
 * {@code broadcast>guide} pair keeps the guide-bearing station but exposes it
 * on the broadcast number. The original OTA stub is hidden and moved to an
 * internal non-tunable number so channel-number lookup is unambiguous.</p>
 */
public final class OtaGuideAliasManager
{
  static final String PROP_ALIASES = "epg/ota_guide_aliases";
  private static final String HIDDEN_PREFIX = "__OTA_ALIAS_STUB_";

  private static final OtaGuideAliasManager INSTANCE = new OtaGuideAliasManager();

  /** Returns the process-wide OTA guide alias manager. */
  public static OtaGuideAliasManager getInstance()
  {
    return INSTANCE;
  }

  private OtaGuideAliasManager() {}

  /** Applies all configured {@code broadcast>guide} aliases. */
  public synchronized void apply()
  {
    Map<String, String> aliases = parseAliases(Sage.get(PROP_ALIASES, ""));
    if (aliases.isEmpty()) return;

    EPG epg = EPG.getInstance();
    if (epg == null) return;
    long[] providerIDs = epg.getAllProviderIDs();
    if (providerIDs == null) return;

    for (long providerID : providerIDs)
    {
      Map<Integer, String[]> lineup = epg.getLineup(providerID);
      EPGDataSource source = epg.getSourceForProviderID(providerID);
      if (lineup == null || source == null) continue;

      for (Map.Entry<String, String> alias : aliases.entrySet())
      {
        String broadcastNumber = alias.getKey();
        String guideNumber = alias.getValue();
        Integer guideStation = findStationForNumber(lineup, guideNumber);
        Integer broadcastStation = findStationForNumber(lineup, broadcastNumber);
        if (guideStation == null || broadcastStation == null) continue;

        String mappedBroadcast = findMappedNumber(
            lineup.get(broadcastStation), broadcastNumber);
        String mappedGuide = findMappedNumber(lineup.get(guideStation), guideNumber);
        if (mappedBroadcast == null || mappedGuide == null) continue;

        if (!guideStation.equals(broadcastStation))
        {
          String[] guideNumbers = mergeNumbers(
              lineup.get(guideStation), mappedBroadcast);
          epg.setOverride(providerID, guideStation.intValue(), guideNumbers);
          epg.setOverride(providerID, broadcastStation.intValue(),
              hiddenNumber(broadcastStation.intValue()));
          source.setCanViewStation(broadcastStation.intValue(), false);
        }

        source.setCanViewStationOnChannel(
            guideStation.intValue(), mappedGuide, false);
        source.setCanViewStationOnChannel(
            guideStation.intValue(), mappedBroadcast, true);

        if (Sage.DBG)
          System.out.println("OtaGuideAliasManager: provider=" + providerID
              + " mapped " + mappedBroadcast + " to guide station "
              + guideStation + " (" + mappedGuide + ")");
      }
    }
    EpgFallbackResolver.getInstance().invalidate();
  }

  static Map<String, String> parseAliases(String csv)
  {
    Map<String, String> aliases = new LinkedHashMap<>();
    if (csv == null || csv.trim().isEmpty()) return aliases;
    for (String token : csv.split(","))
    {
      String[] pair = token.trim().split(">");
      if (pair.length != 2) continue;
      String broadcast = normalize(pair[0]);
      String guide = normalize(pair[1]);
      if (!broadcast.isEmpty() && !guide.isEmpty() && !broadcast.equals(guide))
        aliases.put(broadcast, guide);
    }
    return aliases;
  }

  static Integer findStationForNumber(
      Map<Integer, String[]> lineup, String channelNumber)
  {
    if (lineup == null) return null;
    String wanted = normalize(channelNumber);
    for (Map.Entry<Integer, String[]> entry : lineup.entrySet())
      if (findMappedNumber(entry.getValue(), wanted) != null)
        return entry.getKey();
    return null;
  }

  static String findMappedNumber(String[] numbers, String channelNumber)
  {
    if (numbers == null) return null;
    String wanted = normalize(channelNumber);
    for (String number : numbers)
      if (normalize(number).equals(wanted))
        return number;
    return null;
  }

  static String[] mergeNumbers(String[] existing, String channelNumber)
  {
    Set<String> merged = new LinkedHashSet<>();
    if (existing != null)
      for (String number : existing)
        if (number != null && !number.trim().isEmpty())
          merged.add(number);
    boolean found = false;
    for (String number : merged)
      if (normalize(number).equals(normalize(channelNumber)))
        found = true;
    if (!found) merged.add(channelNumber);
    return merged.toArray(new String[0]);
  }

  static String hiddenNumber(int stationID)
  {
    return HIDDEN_PREFIX + stationID;
  }

  static String normalize(String channelNumber)
  {
    return channelNumber == null ? ""
        : channelNumber.trim().replace('-', '.');
  }
}
