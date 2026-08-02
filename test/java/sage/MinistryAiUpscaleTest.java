package sage;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Tests for {@link Ministry}'s genre-aware AI-upscale routing matrix
 * ({@code shouldAutoAiUpscale}), the {@code isExcludedCategory} helper, and
 * the Vulkan-probe caching/backoff fix (a failed probe must NOT poison
 * {@code aiUpscaleDeviceAvailable()} for the life of the JVM -- only a
 * successful probe is cached; failures are re-probed after a short backoff).
 */
public class MinistryAiUpscaleTest
{
  private static final String ENABLED = "transcoder/ai_upscale_enabled";
  private static final String MAX_SRC_H = "transcoder/ai_upscale_max_source_height";
  private static final String MIN_TGT_H = "transcoder/ai_upscale_min_target_height";
  private static final String REQUIRE_VULKAN = "transcoder/ai_upscale_require_vulkan";
  private static final String GENRE_ROUTING_ENABLED = "transcoder/ai_upscale_genre_routing_enabled";
  private static final String CATS_720 = "transcoder/ai_upscale_lanczos_categories_720";
  private static final String CATS_1080 = "transcoder/ai_upscale_lanczos_categories_1080";
  private static final String WRAPPER = "transcoder/ai_upscale_wrapper";
  private static final String PROBE_BACKOFF = "transcoder/ai_upscale_probe_retry_backoff_secs";

  @BeforeMethod
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    // Isolate every test from real property state and from the real probe
    // subprocess: default to "Vulkan available" so matrix/category tests
    // exercise only the height/genre logic. Tests that specifically need
    // Vulkan-unavailable or probe-caching behavior override these.
    Sage.put(ENABLED, "true");
    Sage.remove(MAX_SRC_H);
    Sage.remove(MIN_TGT_H);
    Sage.put(REQUIRE_VULKAN, "false");
    Sage.remove(GENRE_ROUTING_ENABLED);
    Sage.remove(CATS_720);
    Sage.remove(CATS_1080);
    Sage.remove(PROBE_BACKOFF);
    Ministry.resetAiUpscaleProbe();
  }

  @AfterMethod
  public void tearDown() throws Throwable
  {
    Sage.remove(ENABLED);
    Sage.remove(MAX_SRC_H);
    Sage.remove(MIN_TGT_H);
    Sage.remove(REQUIRE_VULKAN);
    Sage.remove(GENRE_ROUTING_ENABLED);
    Sage.remove(CATS_720);
    Sage.remove(CATS_1080);
    Sage.remove(WRAPPER);
    Sage.remove(PROBE_BACKOFF);
    Ministry.resetAiUpscaleProbe();
  }

  // ── isExcludedCategory ──────────────────────────────────────────────

  @Test
  public void testIsExcludedCategoryNullCategoriesNotExcluded()
  {
    assertFalse(Ministry.isExcludedCategory(null, "News,Talk"));
  }

  @Test
  public void testIsExcludedCategoryEmptyCategoriesNotExcluded()
  {
    assertFalse(Ministry.isExcludedCategory(new String[0], "News,Talk"));
  }

  @Test
  public void testIsExcludedCategoryNullCsvListNotExcluded()
  {
    assertFalse(Ministry.isExcludedCategory(new String[] { "News" }, null));
  }

  @Test
  public void testIsExcludedCategoryEmptyCsvListNotExcluded()
  {
    assertFalse(Ministry.isExcludedCategory(new String[] { "News" }, ""));
  }

  @Test
  public void testIsExcludedCategoryExactMatch()
  {
    assertTrue(Ministry.isExcludedCategory(new String[] { "News" }, "News,Talk"));
  }

  @Test
  public void testIsExcludedCategoryCaseInsensitive()
  {
    assertTrue(Ministry.isExcludedCategory(new String[] { "news" }, "News,Talk"));
    assertTrue(Ministry.isExcludedCategory(new String[] { "NEWS" }, "news,talk"));
  }

  @Test
  public void testIsExcludedCategoryStartsWithNotExactSubstring()
  {
    // "News Weather" starts with "News" -> excluded.
    assertTrue(Ministry.isExcludedCategory(new String[] { "News Weather" }, "News,Talk"));
  }

  @Test
  public void testIsExcludedCategoryMultiCategoryOnlyOneMatches()
  {
    assertTrue(Ministry.isExcludedCategory(new String[] { "Drama", "Sports event" }, "Sports,News,Talk,Nature"));
  }

  @Test
  public void testIsExcludedCategoryNoMatch()
  {
    assertFalse(Ministry.isExcludedCategory(new String[] { "Drama", "Comedy" }, "News,Talk"));
  }

  // ── 720p bucket ─────────────────────────────────────────────────────

  @Test
  public void test720pDramaGetsAi()
  {
    assertTrue(Ministry.shouldAutoAiUpscale(720, 1080, new String[] { "Drama" }));
  }

  @Test
  public void test720pMovieGetsAi()
  {
    assertTrue(Ministry.shouldAutoAiUpscale(720, 1080, new String[] { "Movie" }));
  }

  @Test
  public void test720pNoCategoryGetsAi()
  {
    assertTrue(Ministry.shouldAutoAiUpscale(720, 1080, (String[]) null));
  }

  @Test
  public void test720pNewsGetsLanczos()
  {
    assertFalse(Ministry.shouldAutoAiUpscale(720, 1080, new String[] { "News" }));
  }

  @Test
  public void test720pTalkGetsLanczos()
  {
    assertFalse(Ministry.shouldAutoAiUpscale(720, 1080, new String[] { "Talk" }));
  }

  @Test
  public void test720pSportsGetsAi()
  {
    // Sports is only excluded in the 1080 list, not the 720 list.
    assertTrue(Ministry.shouldAutoAiUpscale(720, 1080, new String[] { "Sports" }));
  }

  @Test
  public void test720pNatureGetsAi()
  {
    // Nature is only excluded in the 1080 list, not the 720 list.
    assertTrue(Ministry.shouldAutoAiUpscale(720, 1080, new String[] { "Nature" }));
  }

  // ── ~1080p bucket ───────────────────────────────────────────────────

  @Test
  public void test1080pDramaGetsAi()
  {
    assertTrue(Ministry.shouldAutoAiUpscale(1080, 2160, new String[] { "Drama" }));
  }

  @Test
  public void test1080pMovieGetsAi()
  {
    assertTrue(Ministry.shouldAutoAiUpscale(1080, 2160, new String[] { "Movie" }));
  }

  @Test
  public void test1080pSportsGetsLanczos()
  {
    assertFalse(Ministry.shouldAutoAiUpscale(1080, 2160, new String[] { "Sports" }));
  }

  @Test
  public void test1080pNewsGetsLanczos()
  {
    assertFalse(Ministry.shouldAutoAiUpscale(1080, 2160, new String[] { "News" }));
  }

  @Test
  public void test1080pTalkGetsLanczos()
  {
    assertFalse(Ministry.shouldAutoAiUpscale(1080, 2160, new String[] { "Talk" }));
  }

  @Test
  public void test1080pNatureGetsLanczos()
  {
    assertFalse(Ministry.shouldAutoAiUpscale(1080, 2160, new String[] { "Nature" }));
  }

  // ── boundary cases ──────────────────────────────────────────────────

  @Test
  public void test1081TreatedAs1080pBucket()
  {
    // 1081 is above the 720 cutoff, so the 1080 exclusion list applies.
    assertFalse(Ministry.shouldAutoAiUpscale(1081, 2160, new String[] { "Sports" }));
    assertTrue(Ministry.shouldAutoAiUpscale(1081, 2160, new String[] { "Drama" }));
  }

  @Test
  public void test1120StillEligible()
  {
    assertTrue(Ministry.shouldAutoAiUpscale(1120, 2160, new String[] { "Drama" }));
  }

  @Test
  public void test1121NeverGetsAi()
  {
    assertFalse(Ministry.shouldAutoAiUpscale(1121, 2160, new String[] { "Drama" }));
    assertFalse(Ministry.shouldAutoAiUpscale(1121, 2160, (String[]) null));
  }

  @Test
  public void testHighSourceHeightNeverGetsAi()
  {
    assertFalse(Ministry.shouldAutoAiUpscale(2160, 2160, (String[]) null));
  }

  @Test
  public void testSubHdTargetNeverGetsAi()
  {
    // Target < 1080 -> never engage, regardless of source height/category.
    assertFalse(Ministry.shouldAutoAiUpscale(480, 720, (String[]) null));
    assertFalse(Ministry.shouldAutoAiUpscale(720, 1000, (String[]) null));
  }

  // ── Vulkan-unavailable override ─────────────────────────────────────

  @Test
  public void testVulkanUnavailableForcesLanczosRegardlessOfCategory()
  {
    Sage.put(REQUIRE_VULKAN, "true");
    // Point at a wrapper path that cannot possibly exist so the probe
    // deterministically fails in any test environment.
    Sage.put(WRAPPER, "/nonexistent/path/definitely-not-here/sage-ai-upscale.sh");
    Ministry.resetAiUpscaleProbe();
    assertFalse(Ministry.shouldAutoAiUpscale(720, 1080, new String[] { "Drama" }),
        "A drama at 720p would otherwise be AI-eligible, but an unusable Vulkan device must force Lanczos");
  }

  // ── genre_routing_enabled kill-switch ───────────────────────────────

  @Test
  public void testGenreRoutingDisabledFallsBackToOld720CapExactly()
  {
    Sage.put(GENRE_ROUTING_ENABLED, "false");
    // With genre routing off, News/Talk/etc. no longer matter at all --
    // 720p always gets AI (old behavior), and 1080p sources are blocked
    // entirely by the old <=720p cap (never reach the AI rule).
    assertTrue(Ministry.shouldAutoAiUpscale(720, 1080, new String[] { "News" }));
    assertFalse(Ministry.shouldAutoAiUpscale(1080, 2160, new String[] { "Drama" }));
  }

  @Test
  public void testExplicitMaxSourceHeightOverrideRespectedWithGenreRoutingOn()
  {
    // Admin explicitly capped at 720 even though genre routing is on --
    // must still block 1080p entirely (backward-compat override wins).
    Sage.put(MAX_SRC_H, "720");
    assertFalse(Ministry.shouldAutoAiUpscale(1080, 2160, new String[] { "Drama" }));
    assertTrue(Ministry.shouldAutoAiUpscale(720, 1080, new String[] { "Drama" }));
  }

  // ── legacy 2-arg overload (categories=null) ─────────────────────────

  @Test
  public void testLegacyTwoArgOverloadNeverExcludesByCategory()
  {
    assertTrue(Ministry.shouldAutoAiUpscale(720, 1080));
    assertFalse(Ministry.shouldAutoAiUpscale(2160, 2160));
  }

  // ── Show overload plumbing ───────────────────────────────────────────

  @Test
  public void testShowOverloadExtractsCategoriesForExclusion()
  {
    Show show = new Show(-919001);
    Stringer newsCat = new Stringer(-919002);
    newsCat.name = "News";
    show.categories = new Stringer[] { newsCat };
    assertFalse(Ministry.shouldAutoAiUpscale(720, 1080, show));
  }

  @Test
  public void testShowOverloadNullShowNotExcluded()
  {
    assertTrue(Ministry.shouldAutoAiUpscale(720, 1080, (Show) null));
  }

  // ── Vulkan probe caching/backoff ─────────────────────────────────────

  @Test
  public void testProbeSuccessIsCachedAndNotReprobed()
  {
    Sage.put(REQUIRE_VULKAN, "true");
    java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger(0);
    java.util.function.BooleanSupplier alwaysTrue = () -> { calls.incrementAndGet(); return true; };
    assertTrue(Ministry.aiUpscaleDeviceAvailable(alwaysTrue));
    assertTrue(Ministry.aiUpscaleDeviceAvailable(alwaysTrue));
    assertTrue(Ministry.aiUpscaleDeviceAvailable(alwaysTrue));
    assertEquals(calls.get(), 1, "A successful probe must be cached -- only probed once");
  }

  @Test
  public void testProbeFailureIsReprobedAfterBackoffElapses()
  {
    Sage.put(REQUIRE_VULKAN, "true");
    Sage.put(PROBE_BACKOFF, "0"); // no backoff -- re-probe immediately
    java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger(0);
    java.util.function.BooleanSupplier alwaysFalse = () -> { calls.incrementAndGet(); return false; };
    assertFalse(Ministry.aiUpscaleDeviceAvailable(alwaysFalse));
    assertFalse(Ministry.aiUpscaleDeviceAvailable(alwaysFalse));
    assertEquals(calls.get(), 2, "A failed probe must NOT be cached -- each call should re-probe (backoff=0)");
  }

  @Test
  public void testProbeFailureRespectsBackoffWindow()
  {
    Sage.put(REQUIRE_VULKAN, "true");
    Sage.put(PROBE_BACKOFF, "9999"); // effectively "never" within this test's runtime
    java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger(0);
    java.util.function.BooleanSupplier alwaysFalse = () -> { calls.incrementAndGet(); return false; };
    assertFalse(Ministry.aiUpscaleDeviceAvailable(alwaysFalse));
    assertFalse(Ministry.aiUpscaleDeviceAvailable(alwaysFalse));
    assertFalse(Ministry.aiUpscaleDeviceAvailable(alwaysFalse));
    assertEquals(calls.get(), 1, "Within the backoff window, a failed probe must NOT be re-invoked");
  }

  @Test
  public void testProbeRecoversAfterFailureOnceBackoffElapsed() throws InterruptedException
  {
    Sage.put(REQUIRE_VULKAN, "true");
    Sage.put(PROBE_BACKOFF, "0");
    java.util.function.BooleanSupplier fails = () -> false;
    assertFalse(Ministry.aiUpscaleDeviceAvailable(fails));
    java.util.function.BooleanSupplier succeeds = () -> true;
    assertTrue(Ministry.aiUpscaleDeviceAvailable(succeeds),
        "After a failure (with backoff elapsed), a subsequent successful probe must be honored");
    // And now it should stick (cached) even if a later call would fail.
    java.util.function.BooleanSupplier wouldFail = () -> false;
    assertTrue(Ministry.aiUpscaleDeviceAvailable(wouldFail));
  }
}
