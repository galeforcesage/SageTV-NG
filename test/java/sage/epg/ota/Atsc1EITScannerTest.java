/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package sage.epg.ota;

import org.testng.annotations.Test;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class Atsc1EITScannerTest
{
  @Test
  public void testDedicatedScannerDoesNotYieldWhileSignalAcquires()
  {
    assertFalse(Atsc1EITScanner.runtimeYieldChecksRequired(true));
    assertTrue(Atsc1EITScanner.runtimeYieldChecksRequired(false));
  }

  @Test
  public void testSharedScannerYieldsWhenItsTunerIsLost()
  {
    assertTrue(Atsc1EITScanner.ownTunerStateRequiresYield(false, false));
    assertTrue(Atsc1EITScanner.ownTunerStateRequiresYield(true, false));
    assertFalse(Atsc1EITScanner.ownTunerStateRequiresYield(true, true));
  }
}
