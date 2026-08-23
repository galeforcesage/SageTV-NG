/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package sage.enhance.spi.offline;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import sage.Sage;
import sage.TestUtils;

import static org.testng.Assert.*;

/**
 * The offline provider seam must leave the built-in Real-ESRGAN command
 * byte-identical to the pre-seam invocation, must select a registered provider
 * only when the property names it, and must fall back to the built-in on any
 * provider failure.
 */
public class OfflineUpscaleRegistryTest
{
  private static final String PROP_PROVIDER = "transcoder/ai_upscale_provider";
  private static final String REQUIRE_VULKAN = "transcoder/ai_upscale_require_vulkan";

  @BeforeMethod
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.remove(PROP_PROVIDER);
    Sage.remove(REQUIRE_VULKAN);
    Sage.remove("transcoder/ai_upscale_wrapper");
    Sage.remove("transcoder/ai_upscale_binary");
    Sage.remove("transcoder/ai_upscale_model");
    Sage.remove("transcoder/ai_upscale_chunk_frames");
    OfflineUpscaleRegistry.getInstance().resetForTest();
  }

  @AfterMethod
  public void tearDown()
  {
    OfflineUpscaleRegistry.getInstance().resetForTest();
    Sage.remove(PROP_PROVIDER);
    Sage.remove(REQUIRE_VULKAN);
  }

  private static OfflineUpscaleRequest req()
  {
    return new OfflineUpscaleRequest(new File("/in/src.ts"), new File("/out/ai.mkv"),
        3840, 2160, 1080);
  }

  @Test
  public void builtinIsSelectedByDefault()
  {
    assertEquals(OfflineUpscaleRegistry.getInstance().selectedId(), RealEsrganOfflineProvider.ID);
  }

  @Test
  public void builtinUpscaleCommandIsByteIdentical()
  {
    List<String> cmd = OfflineUpscaleRegistry.getInstance().buildUpscaleCommand(req());
    assertEquals(cmd, Arrays.asList(
        "/bin/bash", "bin/sage-ai-upscale.sh",
        "--input", new File("/in/src.ts").getAbsolutePath(),
        "--output", new File("/out/ai.mkv").getAbsolutePath(),
        "--width", "3840",
        "--height", "2160",
        "--model", "realesr-general-x4v3",
        "--chunk-frames", "500",
        "--realesrgan", "/usr/local/bin/realesrgan-ncnn-vulkan"));
  }

  @Test
  public void builtinProbeCommandIsByteIdentical()
  {
    Sage.put(REQUIRE_VULKAN, "true");
    List<String> cmd = OfflineUpscaleRegistry.getInstance().buildProbeCommand();
    assertEquals(cmd, Arrays.asList(
        "/bin/bash", "bin/sage-ai-upscale.sh",
        "--probe",
        "--realesrgan", "/usr/local/bin/realesrgan-ncnn-vulkan",
        "--model", "realesr-general-x4v3"));
  }

  @Test
  public void builtinProbeIsNullWhenVulkanNotRequired()
  {
    Sage.put(REQUIRE_VULKAN, "false");
    assertNull(OfflineUpscaleRegistry.getInstance().buildProbeCommand());
  }

  @Test
  public void propertiesFlowIntoBuiltinCommand()
  {
    Sage.put("transcoder/ai_upscale_wrapper", "/opt/x.sh");
    Sage.put("transcoder/ai_upscale_binary", "/opt/realesr");
    Sage.put("transcoder/ai_upscale_model", "realesr-animevideov3");
    Sage.put("transcoder/ai_upscale_chunk_frames", "250");
    List<String> cmd = OfflineUpscaleRegistry.getInstance().buildUpscaleCommand(req());
    assertEquals(cmd.get(1), "/opt/x.sh");
    assertTrue(cmd.contains("realesr-animevideov3"));
    assertTrue(cmd.contains("250"));
    assertTrue(cmd.contains("/opt/realesr"));
  }

  @Test
  public void registeredProviderSelectedByProperty()
  {
    OfflineUpscaleRegistration r = OfflineUpscaleRegistry.getInstance().register(new FakeProvider());
    Sage.put(PROP_PROVIDER, "fake-vsr");
    assertEquals(OfflineUpscaleRegistry.getInstance().selectedId(), "fake-vsr");
    List<String> cmd = OfflineUpscaleRegistry.getInstance().buildUpscaleCommand(req());
    assertEquals(cmd, Arrays.asList("/opt/vsr", "up",
        new File("/in/src.ts").getPath(), new File("/out/ai.mkv").getPath(), "3840", "2160"));
    assertNull(OfflineUpscaleRegistry.getInstance().buildProbeCommand());
    r.close();
  }

  @Test
  public void unknownProviderIdFallsBackToBuiltin()
  {
    Sage.put(PROP_PROVIDER, "nonesuch");
    assertEquals(OfflineUpscaleRegistry.getInstance().selectedId(), RealEsrganOfflineProvider.ID);
    assertEquals(OfflineUpscaleRegistry.getInstance().buildUpscaleCommand(req()).get(0), "/bin/bash");
  }

  @Test
  public void throwingProviderFallsBackToBuiltinCommand()
  {
    OfflineUpscaleRegistry.getInstance().register(new OfflineUpscaleProvider() {
      public String id() { return "boom"; }
      public boolean isSpecialized() { return true; }
      public List<String> buildProbeCommand() { throw new RuntimeException("nope"); }
      public List<String> buildUpscaleCommand(OfflineUpscaleRequest r) { throw new RuntimeException("nope"); }
    });
    Sage.put(PROP_PROVIDER, "boom");
    // Falls back to the built-in command rather than throwing.
    assertEquals(OfflineUpscaleRegistry.getInstance().buildUpscaleCommand(req()).get(0), "/bin/bash");
    Sage.put(REQUIRE_VULKAN, "true");
    assertEquals(OfflineUpscaleRegistry.getInstance().buildProbeCommand().get(2), "--probe");
  }

  @Test
  public void emptyProviderCommandFallsBackToBuiltin()
  {
    OfflineUpscaleRegistry.getInstance().register(new OfflineUpscaleProvider() {
      public String id() { return "empty"; }
      public boolean isSpecialized() { return true; }
      public List<String> buildProbeCommand() { return new ArrayList<String>(); }
      public List<String> buildUpscaleCommand(OfflineUpscaleRequest r) { return new ArrayList<String>(); }
    });
    Sage.put(PROP_PROVIDER, "empty");
    assertEquals(OfflineUpscaleRegistry.getInstance().buildUpscaleCommand(req()).get(0), "/bin/bash");
  }

  @Test
  public void duplicateIdIsRejected()
  {
    OfflineUpscaleRegistry.getInstance().register(new FakeProvider());
    try
    {
      OfflineUpscaleRegistry.getInstance().register(new FakeProvider());
      fail("expected IllegalStateException on duplicate id");
    }
    catch (IllegalStateException expected) { }
  }

  @Test
  public void builtinIdCannotBeShadowed()
  {
    try
    {
      OfflineUpscaleRegistry.getInstance().register(new OfflineUpscaleProvider() {
        public String id() { return RealEsrganOfflineProvider.ID; }
        public boolean isSpecialized() { return true; }
        public List<String> buildProbeCommand() { return null; }
        public List<String> buildUpscaleCommand(OfflineUpscaleRequest r) { return null; }
      });
      fail("expected IllegalStateException shadowing built-in id");
    }
    catch (IllegalStateException expected) { }
  }

  @Test
  public void unregisterRestoresBuiltinSelection()
  {
    OfflineUpscaleRegistration r = OfflineUpscaleRegistry.getInstance().register(new FakeProvider());
    Sage.put(PROP_PROVIDER, "fake-vsr");
    assertEquals(OfflineUpscaleRegistry.getInstance().selectedId(), "fake-vsr");
    r.close();
    // The provider is gone; the configured id is now unknown -> built-in.
    assertEquals(OfflineUpscaleRegistry.getInstance().selectedId(), RealEsrganOfflineProvider.ID);
  }

  private static final class FakeProvider implements OfflineUpscaleProvider
  {
    public String id() { return "fake-vsr"; }
    public boolean isSpecialized() { return true; }
    public List<String> buildProbeCommand() { return null; }
    public List<String> buildUpscaleCommand(OfflineUpscaleRequest r)
    {
      return Arrays.asList("/opt/vsr", "up",
          r.getInput().getPath(), r.getOutput().getPath(),
          Integer.toString(r.getTargetWidth()), Integer.toString(r.getTargetHeight()));
    }
  }
}
