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
package sage;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class MMCCaptureDeviceRemovalTest
{
  private static final int TEST_DEVICE_ID = 0x5354414C;
  private static final String TEST_DEVICE_NAME = "Stale Test Device";

  private MMC mmc;
  private Map globalEncoderMap;
  private TestCaptureDevice captureDevice;

  @BeforeMethod
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    mmc = MMC.getInstance();
    Field encoderMapField = MMC.class.getDeclaredField("globalEncoderMap");
    encoderMapField.setAccessible(true);
    globalEncoderMap = (Map) encoderMapField.get(mmc);

    String prefs = MMC.MMC_KEY + "/" + CaptureDevice.ENCODERS + "/" + TEST_DEVICE_ID + "/";
    Sage.removeNode(prefs);
    Sage.put(prefs + CaptureDevice.VIDEO_CAPTURE_DEVICE_NAME, TEST_DEVICE_NAME);
    Sage.putInt(prefs + CaptureDevice.VIDEO_CAPTURE_DEVICE_NUM, 0);
    captureDevice = new TestCaptureDevice(TEST_DEVICE_ID);
    globalEncoderMap.put(captureDevice.getName(), captureDevice);
  }

  @AfterMethod
  public void tearDown()
  {
    globalEncoderMap.remove(TEST_DEVICE_NAME);
    Sage.removeNode(MMC.MMC_KEY + "/" + CaptureDevice.ENCODERS + "/" + TEST_DEVICE_ID);
  }

  @Test
  public void testRemovingStaleDeviceClearsRuntimeAndPersistedState()
  {
    captureDevice.reachability = CaptureDevice.Reachability.STALE;

    mmc.removeStaleCaptureDevice(TEST_DEVICE_NAME);

    assertNull(mmc.getCaptureDeviceNamed(TEST_DEVICE_NAME));
    assertEquals(Sage.childrenNames(captureDevice.prefs).length, 0);
    assertTrue(captureDevice.isDisabled());
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void testReachableDeviceCannotBeRemoved()
  {
    captureDevice.reachability = CaptureDevice.Reachability.REACHABLE;

    try
    {
      mmc.removeStaleCaptureDevice(TEST_DEVICE_NAME);
    }
    finally
    {
      assertTrue(globalEncoderMap.containsKey(TEST_DEVICE_NAME));
      assertFalse(captureDevice.isDisabled());
    }
  }

  @Test
  public void testRemovingUnverifiedDeviceRequiresDeleteConfirmation()
  {
    captureDevice.reachability = CaptureDevice.Reachability.UNKNOWN;

    mmc.removeUnverifiedCaptureDevice(TEST_DEVICE_NAME, "DELETE");

    assertNull(mmc.getCaptureDeviceNamed(TEST_DEVICE_NAME));
    assertEquals(Sage.childrenNames(captureDevice.prefs).length, 0);
    assertTrue(captureDevice.isDisabled());
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void testRemovingUnverifiedDeviceRejectsInvalidConfirmation()
  {
    captureDevice.reachability = CaptureDevice.Reachability.UNKNOWN;

    try
    {
      mmc.removeUnverifiedCaptureDevice(TEST_DEVICE_NAME, "delete");
    }
    finally
    {
      assertTrue(globalEncoderMap.containsKey(TEST_DEVICE_NAME));
      assertFalse(captureDevice.isDisabled());
    }
  }

  private static final class TestCaptureDevice extends CaptureDevice
  {
    private CaptureDevice.Reachability reachability = CaptureDevice.Reachability.REACHABLE;

    TestCaptureDevice(int id)
    {
      super(id);
    }

    public String getDeviceClass()
    {
      return "Test";
    }

    public CaptureDevice.Reachability getReachability()
    {
      return reachability;
    }

    public void loadDevice() {}

    public boolean isLoaded()
    {
      return true;
    }

    public void startEncoding(CaptureDeviceInput cdi, String encodeFile, String channel) {}

    public void switchEncoding(String switchFile, String channel) {}

    public void stopEncoding() {}

    public void freeDevice() {}

    public long getRecordedBytes()
    {
      return 0;
    }

    protected boolean doTuneChannel(String tuneString, boolean autotune)
    {
      return false;
    }

    protected boolean doScanChannel(String tuneString)
    {
      return false;
    }

    protected String doScanChannelInfo(String tuneString)
    {
      return null;
    }
  }
}
