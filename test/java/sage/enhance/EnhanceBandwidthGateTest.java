/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package sage.enhance;

import org.testng.annotations.Test;

import sage.enhance.EnhancementAdvisor.BandwidthChoice;

import static org.testng.Assert.assertEquals;

/**
 * Tests for {@link EnhancementAdvisor#selectEnhanceBandwidthKbps} -- the policy
 * that picks which bandwidth figure the GPU-enhance gate measures a client
 * against (LAN-unmetered / fresh active probe / passive fallback).
 */
public class EnhanceBandwidthGateTest
{
  private static final long DAY_MS = 24L * 3600L * 1000L;

  @Test
  public void localConnectionIsUnmeteredRegardlessOfProbeOrPassive()
  {
    // A LAN client must return 0 (no cap) even when a low passive estimate or a
    // stale probe exists -- 0 is exactly what mislabels the 6 Mbps render-socket
    // read as irrelevant on the local network.
    BandwidthChoice c = EnhancementAdvisor.selectEnhanceBandwidthKbps(
        true, 6903, 5 * DAY_MS, DAY_MS, 6903);
    assertEquals(c.kbps, 0, "LAN client must be unmetered");
    assertEquals(c.source, "lan-unmetered");
  }

  @Test
  public void freshProbeWinsForRemoteClient()
  {
    // Remote client with a recent active probe uses the probed number, not the
    // passive under-read.
    BandwidthChoice c = EnhancementAdvisor.selectEnhanceBandwidthKbps(
        false, 243000, 4L * 60L * 1000L, DAY_MS, 6903);
    assertEquals(c.kbps, 243000, "fresh probe should be used");
    assertEquals(c.source, "probe");
  }

  @Test
  public void staleProbeFallsBackToPassive()
  {
    // A probe older than the freshness window is ignored; fall back to passive.
    BandwidthChoice c = EnhancementAdvisor.selectEnhanceBandwidthKbps(
        false, 243000, DAY_MS + 1, DAY_MS, 6903);
    assertEquals(c.kbps, 6903, "stale probe must be ignored");
    assertEquals(c.source, "passive");
  }

  @Test
  public void probeAtExactMaxAgeIsStillFresh()
  {
    BandwidthChoice c = EnhancementAdvisor.selectEnhanceBandwidthKbps(
        false, 120000, DAY_MS, DAY_MS, 6903);
    assertEquals(c.kbps, 120000, "probe exactly at max age is still fresh");
    assertEquals(c.source, "probe");
  }

  @Test
  public void noProbeRemoteFallsBackToPassive()
  {
    BandwidthChoice c = EnhancementAdvisor.selectEnhanceBandwidthKbps(
        false, 0, Long.MAX_VALUE, DAY_MS, 6903);
    assertEquals(c.kbps, 6903, "no probe -> passive fallback");
    assertEquals(c.source, "passive");
  }

  @Test
  public void remoteWithNothingReportsPassiveZeroUnchanged()
  {
    // Passive itself 0 (unknown) stays 0 and stays labelled passive -- the gate
    // then resolves 0 by its assume_lan policy exactly as before this change.
    BandwidthChoice c = EnhancementAdvisor.selectEnhanceBandwidthKbps(
        false, 0, Long.MAX_VALUE, DAY_MS, 0);
    assertEquals(c.kbps, 0);
    assertEquals(c.source, "passive");
  }
}
