/*
 * Copyright 2015 The SageTV Authors. All Rights Reserved.
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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import sage.PlayerTimeoutPolicy.PlaybackDeadline;
import sage.PlayerTimeoutPolicy.ProfileContext;

/**
 * Tests for {@link PlayerTimeoutPolicy}: the profile-aware resolution cascade
 * for the player reconnect / reply timeout knobs, and the soonest-wins
 * {@link PlaybackDeadline} logic.
 *
 * <p>Hard contract under test: legacy sessions resolve exactly as before
 * (base property, then the historical constant) and <b>never</b> read the
 * per-profile tier; NG sessions cascade profile-specific &rarr; ng_default
 * &rarr; base &rarr; NG constant.
 */
public class PlayerTimeoutPolicyTest
{
  private static final String PROF = "desktop_hevc_optin";

  private static final String[] SUFFIXES = new String[] {
      PlayerTimeoutPolicy.SUF_INITIAL_WAIT,
      PlayerTimeoutPolicy.SUF_EXPIRE_WAIT,
      PlayerTimeoutPolicy.SUF_ATTEMPTS,
      PlayerTimeoutPolicy.SUF_BACKOFF,
      PlayerTimeoutPolicy.SUF_CONN_TIMEOUT,
      PlayerTimeoutPolicy.SUF_PLAYBACK_DEADLINE,
  };
  private static final String[] BASE_KEYS = new String[] {
      PlayerTimeoutPolicy.BASE_INITIAL_WAIT,
      PlayerTimeoutPolicy.BASE_EXPIRE_WAIT,
      PlayerTimeoutPolicy.BASE_ATTEMPTS,
      PlayerTimeoutPolicy.BASE_BACKOFF,
      PlayerTimeoutPolicy.BASE_CONN_TIMEOUT,
      PlayerTimeoutPolicy.BASE_PLAYBACK_DEADLINE,
  };

  private void clearAll()
  {
    for (String b : BASE_KEYS)
      Sage.remove(b);
    for (String s : SUFFIXES)
    {
      Sage.remove(PlayerTimeoutPolicy.PROFILE_PREFIX + PlayerTimeoutPolicy.NG_DEFAULT_ID + "/" + s);
      Sage.remove(PlayerTimeoutPolicy.PROFILE_PREFIX + PROF + "/" + s);
    }
  }

  private void putNgDefault(String suffix, String value)
  {
    Sage.put(PlayerTimeoutPolicy.PROFILE_PREFIX + PlayerTimeoutPolicy.NG_DEFAULT_ID
        + "/" + suffix, value);
  }

  private void putBase(String baseKey, String value)
  {
    Sage.put(baseKey, value);
  }

  @BeforeMethod
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    clearAll();
    // Pin the base properties to their historical (legacy) values so the suite
    // is deterministic regardless of the ambient Sage.properties on the build
    // host. This mirrors a real server where "base defaults == current values".
    putBase(PlayerTimeoutPolicy.BASE_INITIAL_WAIT, "5000");
    putBase(PlayerTimeoutPolicy.BASE_EXPIRE_WAIT, "30000");
    putBase(PlayerTimeoutPolicy.BASE_ATTEMPTS, "2");
    putBase(PlayerTimeoutPolicy.BASE_BACKOFF, "250");
    putBase(PlayerTimeoutPolicy.BASE_CONN_TIMEOUT, "30000");
  }

  @AfterMethod
  public void tearDown() throws Throwable
  {
    clearAll();
  }

  // --- Legacy resolution -------------------------------------------------

  @Test
  public void testLegacyDefaultsMatchHistoricalConstants()
  {
    ProfileContext legacy = PlayerTimeoutPolicy.LEGACY;
    assertEquals(PlayerTimeoutPolicy.initialWaitMs(legacy), 5000L);
    assertEquals(PlayerTimeoutPolicy.expireWaitMs(legacy), 30000L);
    assertEquals(PlayerTimeoutPolicy.attempts(legacy), 2);
    assertEquals(PlayerTimeoutPolicy.backoffMs(legacy), 250L);
    assertEquals(PlayerTimeoutPolicy.connectionTimeoutMs(legacy), 30000L);
    // No unified deadline for legacy -- keep independent (stacking) behavior.
    assertEquals(PlayerTimeoutPolicy.playbackDeadlineMs(legacy), 0L);
  }

  @Test
  public void testNullContextResolvesAsLegacy()
  {
    assertEquals(PlayerTimeoutPolicy.attempts(null), 2);
    assertEquals(PlayerTimeoutPolicy.expireWaitMs(null), 30000L);
    assertEquals(PlayerTimeoutPolicy.connectionTimeoutMs(null), 30000L);
    assertEquals(PlayerTimeoutPolicy.playbackDeadlineMs(null), 0L);
  }

  @Test
  public void testLegacyHonorsBasePropertyOverride()
  {
    Sage.put(PlayerTimeoutPolicy.BASE_ATTEMPTS, "5");
    Sage.put(PlayerTimeoutPolicy.BASE_CONN_TIMEOUT, "45000");
    ProfileContext legacy = PlayerTimeoutPolicy.LEGACY;
    assertEquals(PlayerTimeoutPolicy.attempts(legacy), 5);
    assertEquals(PlayerTimeoutPolicy.connectionTimeoutMs(legacy), 45000L);
  }

  /**
   * The core compatibility guarantee: a legacy session must NEVER read the
   * per-profile tier, even if ng_default / profile-specific overrides exist.
   */
  @Test
  public void testLegacyNeverReadsProfileTier()
  {
    Sage.put(PlayerTimeoutPolicy.PROFILE_PREFIX + PlayerTimeoutPolicy.NG_DEFAULT_ID
        + "/" + PlayerTimeoutPolicy.SUF_ATTEMPTS, "9");
    Sage.put(PlayerTimeoutPolicy.PROFILE_PREFIX + PROF
        + "/" + PlayerTimeoutPolicy.SUF_ATTEMPTS, "7");
    assertEquals(PlayerTimeoutPolicy.attempts(PlayerTimeoutPolicy.LEGACY), 2,
        "Legacy must ignore both the ng_default and profile-specific tiers");
  }

  // --- NG resolution -----------------------------------------------------

  @Test
  public void testNgWithNothingConfiguredResolvesToBaseLikeLegacy()
  {
    // Corrected contract: NG tightening is opt-in via the ng_default tier.
    // With no profile/ng_default overrides, an NG session must resolve through
    // the base property exactly like legacy (byte-for-byte), NOT to the
    // recommended NG constants.
    ProfileContext ng = PlayerTimeoutPolicy.of(true, null);
    assertEquals(PlayerTimeoutPolicy.initialWaitMs(ng), 5000L);
    assertEquals(PlayerTimeoutPolicy.expireWaitMs(ng), 30000L);
    assertEquals(PlayerTimeoutPolicy.attempts(ng), 2);
    assertEquals(PlayerTimeoutPolicy.backoffMs(ng), 250L);
    assertEquals(PlayerTimeoutPolicy.connectionTimeoutMs(ng), 30000L);
    // Soonest-wins stays disabled (0) until explicitly opted in.
    assertEquals(PlayerTimeoutPolicy.playbackDeadlineMs(ng), 0L);
  }

  @Test
  public void testNgDefaultTierDeliversTightenedValues()
  {
    // Populating the ng_default tier (the documented delivery mechanism) makes
    // an NG session pick up the tightened, faster-failing budgets.
    putNgDefault(PlayerTimeoutPolicy.SUF_INITIAL_WAIT, "3000");
    putNgDefault(PlayerTimeoutPolicy.SUF_EXPIRE_WAIT, "15000");
    putNgDefault(PlayerTimeoutPolicy.SUF_ATTEMPTS, "3");
    putNgDefault(PlayerTimeoutPolicy.SUF_BACKOFF, "250");
    putNgDefault(PlayerTimeoutPolicy.SUF_CONN_TIMEOUT, "15000");
    putNgDefault(PlayerTimeoutPolicy.SUF_PLAYBACK_DEADLINE, "15000");
    ProfileContext ng = PlayerTimeoutPolicy.of(true, null);
    assertEquals(PlayerTimeoutPolicy.initialWaitMs(ng), 3000L);
    assertEquals(PlayerTimeoutPolicy.expireWaitMs(ng), 15000L);
    assertEquals(PlayerTimeoutPolicy.attempts(ng), 3);
    assertEquals(PlayerTimeoutPolicy.backoffMs(ng), 250L);
    assertEquals(PlayerTimeoutPolicy.connectionTimeoutMs(ng), 15000L);
    assertEquals(PlayerTimeoutPolicy.playbackDeadlineMs(ng), 15000L);
    // ... while a legacy session in the same server is unaffected.
    assertEquals(PlayerTimeoutPolicy.expireWaitMs(PlayerTimeoutPolicy.LEGACY), 30000L);
    assertEquals(PlayerTimeoutPolicy.attempts(PlayerTimeoutPolicy.LEGACY), 2);
  }

  @Test
  public void testSeedRecommendedNgDefaultsIsIdempotentAndNonClobbering()
  {
    // Fresh seed writes all five knobs into the ng_default tier.
    assertEquals(PlayerTimeoutPolicy.seedRecommendedNgDefaults(), 5);
    ProfileContext ng = PlayerTimeoutPolicy.of(true, null);
    assertEquals(PlayerTimeoutPolicy.expireWaitMs(ng), 15000L);
    assertEquals(PlayerTimeoutPolicy.attempts(ng), 3);
    // Re-seeding writes nothing (idempotent).
    assertEquals(PlayerTimeoutPolicy.seedRecommendedNgDefaults(), 0);
    // An explicit operator override is never clobbered by a (re-)seed.
    putNgDefault(PlayerTimeoutPolicy.SUF_ATTEMPTS, "7");
    assertEquals(PlayerTimeoutPolicy.seedRecommendedNgDefaults(), 0);
    assertEquals(PlayerTimeoutPolicy.attempts(ng), 7);
  }

  @Test
  public void testNgReadsNgDefaultTier()
  {
    Sage.put(PlayerTimeoutPolicy.PROFILE_PREFIX + PlayerTimeoutPolicy.NG_DEFAULT_ID
        + "/" + PlayerTimeoutPolicy.SUF_EXPIRE_WAIT, "12000");
    ProfileContext ng = PlayerTimeoutPolicy.of(true, PROF);
    assertEquals(PlayerTimeoutPolicy.expireWaitMs(ng), 12000L);
  }

  @Test
  public void testProfileSpecificTierWinsOverNgDefault()
  {
    Sage.put(PlayerTimeoutPolicy.PROFILE_PREFIX + PlayerTimeoutPolicy.NG_DEFAULT_ID
        + "/" + PlayerTimeoutPolicy.SUF_ATTEMPTS, "4");
    Sage.put(PlayerTimeoutPolicy.PROFILE_PREFIX + PROF
        + "/" + PlayerTimeoutPolicy.SUF_ATTEMPTS, "6");
    assertEquals(PlayerTimeoutPolicy.attempts(PlayerTimeoutPolicy.of(true, PROF)), 6);
    // A different profile with no specific override falls through to ng_default.
    assertEquals(PlayerTimeoutPolicy.attempts(PlayerTimeoutPolicy.of(true, "other")), 4);
  }

  @Test
  public void testNgFallsBackToBasePropertyBeforeNgConstant()
  {
    // No profile tier set, but an explicit base override exists -> honored.
    Sage.put(PlayerTimeoutPolicy.BASE_EXPIRE_WAIT, "20000");
    assertEquals(PlayerTimeoutPolicy.expireWaitMs(PlayerTimeoutPolicy.of(true, PROF)), 20000L);
  }

  @Test
  public void testNgFullPrecedenceChain()
  {
    // base < ng_default < profile-specific
    Sage.put(PlayerTimeoutPolicy.BASE_CONN_TIMEOUT, "31000");
    assertEquals(PlayerTimeoutPolicy.connectionTimeoutMs(PlayerTimeoutPolicy.of(true, PROF)), 31000L);
    Sage.put(PlayerTimeoutPolicy.PROFILE_PREFIX + PlayerTimeoutPolicy.NG_DEFAULT_ID
        + "/" + PlayerTimeoutPolicy.SUF_CONN_TIMEOUT, "18000");
    assertEquals(PlayerTimeoutPolicy.connectionTimeoutMs(PlayerTimeoutPolicy.of(true, PROF)), 18000L);
    Sage.put(PlayerTimeoutPolicy.PROFILE_PREFIX + PROF
        + "/" + PlayerTimeoutPolicy.SUF_CONN_TIMEOUT, "9000");
    assertEquals(PlayerTimeoutPolicy.connectionTimeoutMs(PlayerTimeoutPolicy.of(true, PROF)), 9000L);
  }

  @Test
  public void testAttemptsFlooredAtOne()
  {
    Sage.put(PlayerTimeoutPolicy.PROFILE_PREFIX + PlayerTimeoutPolicy.NG_DEFAULT_ID
        + "/" + PlayerTimeoutPolicy.SUF_ATTEMPTS, "0");
    assertEquals(PlayerTimeoutPolicy.attempts(PlayerTimeoutPolicy.of(true, PROF)), 1);
  }

  // --- Worst-case arithmetic (verified against the real loops) -----------

  @Test
  public void testWorstCaseAcquisitionLegacyVsNg()
  {
    // Legacy: one getSocketChannelInfo call ~= expireWait (shared startWait),
    // 2 attempts + 1 backoff = 2*30000 + 250 = 60250ms.
    assertEquals(PlayerTimeoutPolicy.worstCaseAcquisitionMs(PlayerTimeoutPolicy.LEGACY), 60250L);
    // NG tightening is delivered by populating the ng_default tier (the opt-in
    // knobs), NOT by a hardcoded constant fallback. Once seeded:
    // 3 attempts + 2 backoff = 3*15000 + 2*250 = 45500ms.
    putNgDefault(PlayerTimeoutPolicy.SUF_EXPIRE_WAIT, "15000");
    putNgDefault(PlayerTimeoutPolicy.SUF_ATTEMPTS, "3");
    putNgDefault(PlayerTimeoutPolicy.SUF_BACKOFF, "250");
    assertEquals(PlayerTimeoutPolicy.worstCaseAcquisitionMs(PlayerTimeoutPolicy.of(true, null)), 45500L);
  }

  // --- Soonest-wins PlaybackDeadline logic -------------------------------

  @Test
  public void testInactiveDeadlineNeverCaps()
  {
    PlaybackDeadline d = PlaybackDeadline.none();
    assertFalse(d.isActive());
    assertEquals(d.effectiveWait(30000L, 0L), 30000L);
    assertEquals(d.remaining(123L), Long.MAX_VALUE);
    assertFalse(d.expired(Long.MAX_VALUE));
  }

  @Test
  public void testNonPositiveBudgetYieldsInactiveDeadline()
  {
    assertFalse(PlaybackDeadline.startingAt(1000L, 0L).isActive());
    assertFalse(PlaybackDeadline.startingAt(1000L, -5L).isActive());
  }

  @Test
  public void testActiveDeadlineIsSoonestWins()
  {
    // Budget 15s starting at t=1000 -> deadline at 16000.
    PlaybackDeadline d = PlaybackDeadline.startingAt(1000L, 15000L);
    assertTrue(d.isActive());
    // Own timeout 30s but only 15s of budget remains at start -> capped to 15s.
    assertEquals(d.effectiveWait(30000L, 1000L), 15000L);
    // 5s later (t=6000) 10s of the 15s budget remains -> a 30s read is capped
    // to 10s (min, not sum).
    assertEquals(d.effectiveWait(30000L, 6000L), 10000L);
    // If the individual timeout is already smaller than remaining, it wins.
    assertEquals(d.effectiveWait(2000L, 6000L), 2000L);
  }

  @Test
  public void testExpiredDeadlineFailsFast()
  {
    PlaybackDeadline d = PlaybackDeadline.startingAt(0L, 10000L); // deadline at 10000
    assertTrue(d.expired(10000L));
    assertTrue(d.expired(12000L));
    // Past the deadline, any wait is clamped to 0 (fail fast, don't block).
    assertEquals(d.effectiveWait(30000L, 12000L), 0L);
  }

  @Test
  public void testForContextBuildsActiveOnlyForNg()
  {
    assertFalse(PlaybackDeadline.forContext(PlayerTimeoutPolicy.LEGACY, 0L).isActive());
    // An NG session opts into the unified deadline via the ng_default tier.
    putNgDefault(PlayerTimeoutPolicy.SUF_PLAYBACK_DEADLINE, "15000");
    PlaybackDeadline ngD = PlaybackDeadline.forContext(PlayerTimeoutPolicy.of(true, null), 1000L);
    assertTrue(ngD.isActive());
    assertEquals(ngD.deadlineAtMs(), 1000L + 15000L);
    // Without the opt-in, even an NG session has no unified cap.
    Sage.remove(PlayerTimeoutPolicy.PROFILE_PREFIX + PlayerTimeoutPolicy.NG_DEFAULT_ID
        + "/" + PlayerTimeoutPolicy.SUF_PLAYBACK_DEADLINE);
    assertFalse(PlaybackDeadline.forContext(PlayerTimeoutPolicy.of(true, null), 1000L).isActive());
  }
}
