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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.testng.Assert.assertEquals;

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
}
