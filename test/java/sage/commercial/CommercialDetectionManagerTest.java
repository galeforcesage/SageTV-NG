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
package sage.commercial;

import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class CommercialDetectionManagerTest
{
  @Test
  public void testLegacyDefaultMigratesToBundledBinary() throws Exception
  {
    Path tempDir = Files.createTempDirectory("comskip-path");
    File bundled = Files.createFile(tempDir.resolve("bundled-comskip")).toFile();
    File system = tempDir.resolve("system-comskip").toFile();

    assertEquals(CommercialDetectionManager.resolveComskipPath(
        CommercialDetectionManager.LEGACY_COMSKIP_PATH, bundled, system),
        bundled.getAbsolutePath());
  }

  @Test
  public void testLegacyDefaultFallsBackToSystemBinary() throws Exception
  {
    Path tempDir = Files.createTempDirectory("comskip-path");
    File bundled = tempDir.resolve("bundled-comskip").toFile();
    File system = Files.createFile(tempDir.resolve("system-comskip")).toFile();

    assertEquals(CommercialDetectionManager.resolveComskipPath(
        CommercialDetectionManager.LEGACY_COMSKIP_PATH, bundled, system),
        system.getAbsolutePath());
  }

  @Test
  public void testMissingCustomPathIsNotSilentlyReplaced() throws Exception
  {
    Path tempDir = Files.createTempDirectory("comskip-path");
    File bundled = Files.createFile(tempDir.resolve("bundled-comskip")).toFile();
    String custom = tempDir.resolve("custom-comskip").toString();

    assertEquals(CommercialDetectionManager.resolveComskipPath(
        custom, bundled, null), custom);
  }

  @Test
  public void testEmptyPathUsesBundledDefaultWhenNoBinaryExists()
  {
    assertEquals(CommercialDetectionManager.resolveComskipPath(
        "", new File("missing-bundled"), new File("missing-system")),
        CommercialDetectionManager.BUNDLED_COMSKIP_PATH);
  }

  @Test
  public void testIniLayerKeepsFirstDuplicateValue() throws Exception
  {
    Path ini = writeIni("duplicate",
        "; detect_method=999 in a comment must be ignored",
        "detect_method=127",
        "detect_method=43");

    assertEquals(ComskipIni.readLayer(ini).get("detect_method"), "127");
  }

  @Test
  public void testHigherPriorityLayerOverridesLowerLayer() throws Exception
  {
    Path base = writeIni("base", "detect_method=255", "output_edl=1");
    Path delta = writeIni("delta", "detect_method=127");

    Map<String, String> effective = ComskipIni.mergeLayers(
        Arrays.asList(base, delta), null);

    assertEquals(effective.get("detect_method"), "127");
    assertEquals(effective.get("output_edl"), "1");
  }

  @Test
  public void testEmptyContentDeltaUsesBaseUnchanged() throws Exception
  {
    Path base = writeIni("base", "detect_method=255", "output_edl=1", "lowres=0");
    Path emptyDelta = writeIni("empty-delta",
        "; Administrators may blank this file for base-only behavior.");

    Map<String, String> effective = ComskipIni.mergeLayers(
        Arrays.asList(base, emptyDelta), null);

    assertEquals(effective.get("detect_method"), "255");
    assertEquals(effective.get("output_edl"), "1");
    assertEquals(effective.get("lowres"), "0");
    assertEquals(effective.size(), 3);
  }

  @Test
  public void testFlattenedOutputHasUniqueKeys() throws Exception
  {
    Path base = writeIni("base", "detect_method=255", "output_edl=1");
    Path delta = writeIni("delta", "detect_method=127", "lowres=0");
    Map<String, String> effective = ComskipIni.mergeLayers(
        Arrays.asList(base, delta), null);
    Path output = ComskipIni.writeEffectiveIni(effective, "test", null);

    List<String> lines = Files.readAllLines(output, StandardCharsets.UTF_8);
    Set<String> keys = new HashSet<>();
    int activeDefinitions = 0;
    for (String line : lines)
    {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith(";"))
        continue;
      int equals = trimmed.indexOf('=');
      assertTrue(equals > 0, "Every active line must be a definition");
      activeDefinitions++;
      assertTrue(keys.add(trimmed.substring(0, equals)), "Duplicate active key: " + line);
    }
    assertEquals(activeDefinitions, effective.size());
  }

  @Test
  public void testDramaCategorySelectsDrama()
  {
    assertEquals(CommercialDetectionManager.detectContentProfile(
        new String[] { "Drama" }),
        CommercialDetectionManager.ContentProfile.DRAMA_SCRIPTED);
  }

  @Test
  public void testAllExplicitCategoryMappings()
  {
    assertProfile(CommercialDetectionManager.ContentProfile.DRAMA_SCRIPTED,
        "Drama", "Crime drama", "Comedy drama", "Courtroom Drama",
        "Historical drama", "Docudrama", "Dramedy", "Independent Drama",
        "Inspirational Drama", "Crime", "Mystery", "Law", "Horror",
        "Romance", "Soap");
    assertProfile(CommercialDetectionManager.ContentProfile.SITCOM,
        "Comedy", "Sitcom", "Dark comedy", "Romantic comedy",
        "Musical comedy", "Political Comedies");
    assertProfile(CommercialDetectionManager.ContentProfile.SPORTS,
        "Sports", "Sports event", "Playoff sports", "Football", "Baseball",
        "Basketball", "Hockey", "Soccer", "Golf", "Auto racing",
        "Horse racing", "Pro wrestling");
    assertProfile(CommercialDetectionManager.ContentProfile.NEWS,
        "News", "News & Information", "News & Opinion", "Newsmagazine",
        "Public affairs", "Politics", "Debate", "Interview", "Talk",
        "Business", "Bus./financial", "Weather");
    assertProfile(CommercialDetectionManager.ContentProfile.GAME_REALITY,
        "Game show", "Game Shows", "Dating Show", "Reality", "Reality TV",
        "Reality Competition", "Competition reality", "Medical Reality", "Variety");
    assertProfile(CommercialDetectionManager.ContentProfile.MOVIE, "Movie");
    assertProfile(CommercialDetectionManager.ContentProfile.BASE,
        "True Crime", "Documentary", "History", "Science", "Nature",
        "Biography", "Animals", "Children", "Family", "Cooking",
        "Lifestyle", "Travel", "Special");
  }

  @Test
  public void testCategoryPrecedenceIsOrderIndependent()
  {
    assertSameProfile(new String[] { "Movie", "Comedy" },
        new String[] { "Comedy", "Movie" }, CommercialDetectionManager.ContentProfile.MOVIE);
    assertSameProfile(new String[] { "Sports event", "News" },
        new String[] { "News", "Sports event" }, CommercialDetectionManager.ContentProfile.SPORTS);
    assertSameProfile(new String[] { "News", "Baseball" },
        new String[] { "Baseball", "News" }, CommercialDetectionManager.ContentProfile.NEWS);
    assertSameProfile(new String[] { "Reality", "Auto racing" },
        new String[] { "Auto racing", "Reality" },
        CommercialDetectionManager.ContentProfile.GAME_REALITY);
    assertSameProfile(new String[] { "Family", "Sitcom" },
        new String[] { "Sitcom", "Family" }, CommercialDetectionManager.ContentProfile.SITCOM);
    assertSameProfile(new String[] { "Documentary", "Football" },
        new String[] { "Football", "Documentary" }, CommercialDetectionManager.ContentProfile.BASE);
  }

  @Test
  public void testContentProfilesResolveExpectedDeltaFiles() throws Exception
  {
    Path dir = Files.createTempDirectory("comskip-profiles");
    assertEquals(CommercialDetectionManager.getContentDelta(dir.toFile(),
        CommercialDetectionManager.ContentProfile.DRAMA_SCRIPTED).getName(),
        "comskip_drama.delta.ini");
    assertEquals(CommercialDetectionManager.getContentDelta(dir.toFile(),
        CommercialDetectionManager.ContentProfile.SITCOM).getName(),
        "comskip_sitcom.delta.ini");
    assertEquals(CommercialDetectionManager.getContentDelta(dir.toFile(),
        CommercialDetectionManager.ContentProfile.SPORTS).getName(),
        "comskip_sports.delta.ini");
    assertEquals(CommercialDetectionManager.getContentDelta(dir.toFile(),
        CommercialDetectionManager.ContentProfile.NEWS).getName(),
        "comskip_news.delta.ini");
    assertEquals(CommercialDetectionManager.getContentDelta(dir.toFile(),
        CommercialDetectionManager.ContentProfile.GAME_REALITY).getName(),
        "comskip_game_reality.delta.ini");
    assertEquals(CommercialDetectionManager.getContentDelta(dir.toFile(),
        CommercialDetectionManager.ContentProfile.MOVIE).getName(),
        "comskip_movie.delta.ini");
    assertNull(CommercialDetectionManager.getContentDelta(dir.toFile(),
        CommercialDetectionManager.ContentProfile.BASE));
  }

  @Test
  public void testLiveOverridesReplaceExistingValues() throws Exception
  {
    Path base = writeIni("base",
        "live_tv=0", "live_tv_retries=5", "output_edl=1");
    Map<String, String> overrides = new LinkedHashMap<>();
    overrides.put("live_tv", "1");
    overrides.put("live_tv_retries", "120");

    Map<String, String> effective = ComskipIni.mergeLayers(
        Collections.singletonList(base), overrides);

    assertEquals(effective.get("live_tv"), "1");
    assertEquals(effective.get("live_tv_retries"), "120");
    assertEquals(effective.get("output_edl"), "1");
  }

  @Test
  public void testMissingOptionalLayerKeepsRemainingLayers() throws Exception
  {
    Path base = writeIni("base", "detect_method=255", "output_edl=1");
    Path missing = base.resolveSibling("missing.delta.ini");
    Path drama = writeIni("drama", "detect_method=127");
    List<Path> layers = new java.util.ArrayList<>();
    layers.add(base);

    ByteArrayOutputStream warningBytes = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    try
    {
      System.setOut(new PrintStream(warningBytes, true, "UTF-8"));
      assertFalse(CommercialDetectionManager.addOptionalLayer(
          layers, missing.toFile(), "DRAMA"));
    }
    finally
    {
      System.setOut(originalOut);
    }
    layers.add(drama);
    Map<String, String> effective = ComskipIni.mergeLayers(layers, null);

    assertFalse(Files.exists(missing));
    assertTrue(warningBytes.toString("UTF-8").contains("WARNING: Optional DRAMA delta not found"));
    assertEquals(effective.get("detect_method"), "127");
    assertEquals(effective.get("output_edl"), "1");
  }

  @Test
  public void testShowSpecificIniWinsOverChannelSpecificIni() throws Exception
  {
    Path dir = Files.createTempDirectory("comskip-specific");
    File show = Files.createFile(dir.resolve("Show.ini")).toFile();
    File channel = Files.createFile(dir.resolve("Channel.ini")).toFile();

    assertEquals(CommercialDetectionManager.selectSpecificIni(show, channel),
        show.getAbsolutePath());
  }

  @Test
  public void testChannelSpecificIniWinsWhenShowIniIsMissing() throws Exception
  {
    Path dir = Files.createTempDirectory("comskip-specific");
    File show = dir.resolve("MissingShow.ini").toFile();
    File channel = Files.createFile(dir.resolve("Channel.ini")).toFile();

    assertEquals(CommercialDetectionManager.selectSpecificIni(show, channel),
        channel.getAbsolutePath());
    assertNull(CommercialDetectionManager.selectSpecificIni(show,
        dir.resolve("MissingChannel.ini").toFile()));
  }

  @Test
  public void testPackagedDramaDeltaContainsRequiredSettings() throws Exception
  {
    Path drama = findProjectFile("comskip", "comskip_drama.delta.ini");
    assertNotNull(drama, "Drama delta must be present for release packaging");
    Map<String, String> values = ComskipIni.readLayer(drama);

    assertEquals(values.get("detect_method"), "123");
    assertEquals(values.get("min_silence"), "20");
    assertEquals(values.get("min_commercialbreak"), "20");
    assertEquals(values.get("max_commercial_size"), "300");
    assertEquals(values.get("min_show_segment_length"), "120");
    assertEquals(values.get("punish_no_logo"), "1");
    assertEquals(values.get("logo_filter"), "4");
    assertNull(values.get("lowres"), "Safe lowres=0 is inherited from the base");
  }

  @Test
  public void testBasePinsSafeFullResolutionMode() throws Exception
  {
    Path base = findProjectFile("comskip", "comskip_base.ini");
    assertNotNull(base);
    assertEquals(ComskipIni.readLayer(base).get("lowres"), "0");
  }

  @Test
  public void testChannelOverrideSkeletonHasNoActiveSettings() throws Exception
  {
    Path example = findProjectFile("comskip", "channel_override.example.delta.ini");
    assertNotNull(example);
    assertTrue(ComskipIni.readLayer(example).isEmpty());
  }

  private static Path writeIni(String name, String... lines) throws Exception
  {
    Path ini = Files.createTempFile("comskip-" + name + "-", ".ini");
    Files.write(ini, Arrays.asList(lines), StandardCharsets.UTF_8);
    ini.toFile().deleteOnExit();
    return ini;
  }

  private static Path findProjectFile(String directory, String filename)
  {
    Path current = new File(System.getProperty("user.dir")).toPath().toAbsolutePath();
    for (int i = 0; i < 5 && current != null; i++, current = current.getParent())
    {
      Path candidate = current.resolve(directory).resolve(filename);
      if (Files.isRegularFile(candidate))
        return candidate;
    }
    return null;
  }

  private static void assertProfile(CommercialDetectionManager.ContentProfile expected,
      String... categories)
  {
    for (String category : categories)
      assertEquals(CommercialDetectionManager.detectContentProfile(
          new String[] { category }), expected, category);
  }

  private static void assertSameProfile(String[] first, String[] second,
      CommercialDetectionManager.ContentProfile expected)
  {
    assertEquals(CommercialDetectionManager.detectContentProfile(first), expected);
    assertEquals(CommercialDetectionManager.detectContentProfile(second), expected);
  }
}
