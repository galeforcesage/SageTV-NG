/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
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
package sage.samsungtvplus;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Tests for Samsung TV Plus API parsing (offline/unit tests).
 */
public class SamsungTVPlusAPITest
{
  @Test
  public void testParseXmltvTime_utc()
  {
    long ts = SamsungTVPlusAPI.parseXmltvTime("20260405120000 +0000");
    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    cal.setTimeInMillis(ts);
    Assert.assertEquals(cal.get(Calendar.YEAR), 2026);
    Assert.assertEquals(cal.get(Calendar.MONTH), Calendar.APRIL);
    Assert.assertEquals(cal.get(Calendar.DAY_OF_MONTH), 5);
    Assert.assertEquals(cal.get(Calendar.HOUR_OF_DAY), 12);
    Assert.assertEquals(cal.get(Calendar.MINUTE), 0);
    Assert.assertEquals(cal.get(Calendar.SECOND), 0);
  }

  @Test
  public void testParseXmltvTime_withOffset()
  {
    long ts = SamsungTVPlusAPI.parseXmltvTime("20260405170000 -0500");
    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    cal.setTimeInMillis(ts);
    // 17:00 -0500 = 22:00 UTC
    Assert.assertEquals(cal.get(Calendar.HOUR_OF_DAY), 22);
  }

  @Test
  public void testParseXmltvTime_null()
  {
    Assert.assertEquals(SamsungTVPlusAPI.parseXmltvTime(null), 0);
    Assert.assertEquals(SamsungTVPlusAPI.parseXmltvTime(""), 0);
    Assert.assertEquals(SamsungTVPlusAPI.parseXmltvTime("short"), 0);
  }

  @Test
  public void testParseChannelJson_basic()
  {
    String json = "{"
        + "\"slug\":\"stvp-{id}\","
        + "\"regions\":{"
        + "  \"us\":{\"name\":\"United States\",\"channels\":{"
        + "    \"USBC1000001\":{\"name\":\"Test Channel 1\",\"chno\":1001,"
        + "      \"logo\":\"https://example.com/logo1.png\",\"group\":\"News\","
        + "      \"programs\":[[1000,\"Show A\"],[2000,\"Show B\"]]"
        + "    },"
        + "    \"USBC2000002\":{\"name\":\"DRM Channel\",\"chno\":1002,"
        + "      \"logo\":\"https://example.com/logo2.png\",\"group\":\"Movies\","
        + "      \"license_url\":\"https://example.com/drm\"}"
        + "  }},"
        + "  \"gb\":{\"name\":\"United Kingdom\",\"channels\":{"
        + "    \"GBBC3000001\":{\"name\":\"UK Channel\",\"chno\":2001,"
        + "      \"logo\":\"https://example.com/logo3.png\",\"group\":\"Entertainment\"}"
        + "  }}"
        + "}}";

    SamsungTVPlusAPI api = new SamsungTVPlusAPI("us");
    java.util.List<SamsungTVPlusChannel> channels = api.parseChannelJson(json);

    // Should only contain the US non-DRM channel
    Assert.assertEquals(channels.size(), 1);
    Assert.assertEquals(channels.get(0).getLongName(), "Test Channel 1");
    Assert.assertEquals(channels.get(0).getCategory(), "News");
    Assert.assertEquals(channels.get(0).getStreamUrl(), "https://jmp2.uk/stvp-USBC1000001");
  }

  @Test
  public void testParseChannelJson_empty()
  {
    SamsungTVPlusAPI api = new SamsungTVPlusAPI("us");
    java.util.List<SamsungTVPlusChannel> channels = api.parseChannelJson("{}");
    Assert.assertEquals(channels.size(), 0);
  }

  @Test
  public void testParseChannelJson_gbRegion()
  {
    String json = "{"
        + "\"slug\":\"stvp-{id}\","
        + "\"regions\":{"
        + "  \"gb\":{\"name\":\"United Kingdom\",\"channels\":{"
        + "    \"GBBC3000001\":{\"name\":\"UK Channel\",\"chno\":2001,"
        + "      \"logo\":\"https://example.com/logo3.png\",\"group\":\"Entertainment\"}"
        + "  }}"
        + "}}";

    SamsungTVPlusAPI api = new SamsungTVPlusAPI("gb");
    java.util.List<SamsungTVPlusChannel> channels = api.parseChannelJson(json);
    Assert.assertEquals(channels.size(), 1);
    Assert.assertEquals(channels.get(0).getLongName(), "UK Channel");
  }

  @Test
  public void testProgramExternalId_consistency()
  {
    SamsungTVPlusProgram p1 = new SamsungTVPlusProgram();
    p1.title = "Test Show";
    p1.startTime = 1000000L;
    p1.episodeId = "EP12345";

    SamsungTVPlusProgram p2 = new SamsungTVPlusProgram();
    p2.title = "Test Show";
    p2.startTime = 1000000L;
    p2.episodeId = "EP12345";

    // Same input should produce same external ID
    Assert.assertEquals(p1.getExternalId(), p2.getExternalId());
    Assert.assertTrue(p1.getExternalId().startsWith("STP"));
  }

  @Test
  public void testProgramExternalId_differentEpisodes()
  {
    SamsungTVPlusProgram p1 = new SamsungTVPlusProgram();
    p1.title = "Test Show";
    p1.startTime = 1000000L;
    p1.episodeId = "EP12345";

    SamsungTVPlusProgram p2 = new SamsungTVPlusProgram();
    p2.title = "Test Show";
    p2.startTime = 2000000L;
    p2.episodeId = "EP67890";

    // Different episodes should produce different external IDs
    Assert.assertNotEquals(p1.getExternalId(), p2.getExternalId());
  }

  @Test
  public void testEpgPlugin_getProviders()
  {
    SamsungTVPlusEPGPlugin plugin = new SamsungTVPlusEPGPlugin();
    String[][] providers = plugin.getProviders("10001");

    Assert.assertNotNull(providers);
    Assert.assertTrue(providers.length > 0);
    // First provider should be US
    Assert.assertTrue(providers[0][1].contains("United States"));
    // Provider IDs should be parseable as longs
    Long.parseLong(providers[0][0]);
  }
}
