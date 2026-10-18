/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package sage.epg.ota;

import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

public class OtaGuideAliasManagerTest
{
  @Test
  public void testParsesBroadcastToGuideAliases()
  {
    Map<String, String> aliases =
        OtaGuideAliasManager.parseAliases("7-2>702, 18.4 > 804");

    assertEquals(aliases.get("7.2"), "702");
    assertEquals(aliases.get("18.4"), "804");
    assertEquals(aliases.size(), 2);
  }

  @Test
  public void testFindsStationAcrossDashAndDotForms()
  {
    Map<Integer, String[]> lineup = new LinkedHashMap<>();
    lineup.put(100, new String[] { "7-2" });
    lineup.put(200, new String[] { "702" });

    assertEquals(OtaGuideAliasManager.findStationForNumber(lineup, "7.2"),
        Integer.valueOf(100));
    assertEquals(OtaGuideAliasManager.findStationForNumber(lineup, "702"),
        Integer.valueOf(200));
    assertNull(OtaGuideAliasManager.findStationForNumber(lineup, "7.3"));
  }

  @Test
  public void testMergesTunableNumberWithoutDiscardingGuideNumber()
  {
    String[] merged = OtaGuideAliasManager.mergeNumbers(
        new String[] { "702" }, "7-2");
    String[] mergedAgain = OtaGuideAliasManager.mergeNumbers(merged, "7.2");

    assertEquals(Arrays.asList(merged), Arrays.asList("702", "7-2"));
    assertEquals(Arrays.asList(mergedAgain), Arrays.asList("702", "7-2"));
  }

  @Test
  public void testHiddenStubNumberIsStableAndNonTunable()
  {
    assertEquals(OtaGuideAliasManager.hiddenNumber(100),
        "__OTA_ALIAS_STUB_100");
  }
}
