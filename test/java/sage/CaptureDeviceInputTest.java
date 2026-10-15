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

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class CaptureDeviceInputTest
{
  private static final int TEST_DEVICE_ID = 0x53414745;

  private HDHomeRunCaptureDevice captureDevice;
  private CaptureDeviceInput captureInput;

  @BeforeMethod
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.removeNode(MMC.MMC_KEY + "/" + CaptureDevice.ENCODERS + "/" + TEST_DEVICE_ID);
    captureDevice = new HDHomeRunCaptureDevice(TEST_DEVICE_ID);
    captureInput = new CaptureDeviceInput(captureDevice, captureDevice.prefs,
        CaptureDeviceInput.TV_TUNER_CROSSBAR_INDEX, 0);
  }

  @AfterMethod
  public void tearDown()
  {
    Sage.removeNode(MMC.MMC_KEY + "/" + CaptureDevice.ENCODERS + "/" + TEST_DEVICE_ID);
  }

  @Test
  public void testConfiguringExistingLineupEnablesCaptureDevice()
  {
    captureInput.setProvider(1234);
    captureDevice.setDisabled(true);

    captureInput.setProvider(1234);

    assertFalse(captureDevice.isDisabled());
    assertTrue(captureDevice.isFunctioning());
  }

  @Test
  public void testConfiguringNewLineupEnablesCaptureDevice()
  {
    captureDevice.setDisabled(true);

    captureInput.setProvider(1234);

    assertFalse(captureDevice.isDisabled());
    assertTrue(captureDevice.isFunctioning());
  }

  @Test
  public void testReleasingInputDoesNotEnableCaptureDevice()
  {
    captureDevice.setDisabled(true);

    captureInput.setProvider(0);

    assertTrue(captureDevice.isDisabled());
    assertFalse(captureDevice.isFunctioning());
  }
}
