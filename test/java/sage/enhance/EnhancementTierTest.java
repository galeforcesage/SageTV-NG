package sage.enhance;

import org.testng.annotations.Test;

import static org.testng.Assert.*;

/**
 * Tests for the tier vocabulary, the degradation ladder, and above all the
 * source floor — whose whole point is a distinction that is easy to implement
 * backwards.
 */
public class EnhancementTierTest
{
  // ---- Source floor: the height-vs-width trap -----------------------------

  /**
   * The single most important test in this class. SD/DVD material is 720x480:
   * its 720 is the WIDTH. A floor implemented against width would wrongly admit
   * exactly the content the floor exists to exclude.
   */
  @Test
  public void testDvdSd720x480IsBelowFloorDespiteThe720()
  {
    assertFalse(EnhancementTier.ENHANCE_2160P.isLegalForSourceHeight(480),
        "720x480 DVD must be excluded: its 720 is the width, not the height");
    assertFalse(EnhancementTier.ENHANCE_1080P.isLegalForSourceHeight(480));
    assertFalse(EnhancementTier.ENHANCE_1440P.isLegalForSourceHeight(480));
  }

  @Test
  public void testOtherSdShapesAreBelowFloor()
  {
    // 704x480, 640x480, 720x576 (PAL) — all excluded on height.
    assertFalse(EnhancementTier.ENHANCE_2160P.isLegalForSourceHeight(480));
    assertFalse(EnhancementTier.ENHANCE_2160P.isLegalForSourceHeight(576));
  }

  @Test
  public void test720pIsExactlyAtTheFloorAndQualifies()
  {
    assertEquals(EnhancementTier.SOURCE_HEIGHT_FLOOR, 720);
    assertTrue(EnhancementTier.ENHANCE_2160P.isLegalForSourceHeight(720),
        "the floor is inclusive; 1280x720 must qualify");
  }

  @Test
  public void test1080QualifiesIncludingInterlaced()
  {
    // Interlaced sources use full frame height, so 1080i counts as 1080.
    assertTrue(EnhancementTier.ENHANCE_2160P.isLegalForSourceHeight(1080));
  }

  /**
   * DEINTERLACE_ONLY is deliberately exempt: it is not upscaling, so a 480i
   * source still gets a GPU deinterlace at its native resolution.
   */
  @Test
  public void testDeinterlaceOnlyIsExemptFromTheFloor()
  {
    assertTrue(EnhancementTier.DEINTERLACE_ONLY.isLegalForSourceHeight(480),
        "deinterlace-only is not upscaling and must remain available for SD");
    assertTrue(EnhancementTier.DEINTERLACE_ONLY.isLegalForSourceHeight(0));
  }

  @Test
  public void testNoneIsAlwaysLegal()
  {
    assertTrue(EnhancementTier.NONE.isLegalForSourceHeight(0));
  }

  // ---- Ladder -------------------------------------------------------------

  @Test
  public void testDowngradeLadderTerminatesAtNone()
  {
    assertEquals(EnhancementTier.ENHANCE_2160P.downgrade(), EnhancementTier.ENHANCE_1440P);
    assertEquals(EnhancementTier.ENHANCE_1440P.downgrade(), EnhancementTier.ENHANCE_1080P);
    assertEquals(EnhancementTier.ENHANCE_1080P.downgrade(), EnhancementTier.DEINTERLACE_ONLY);
    assertEquals(EnhancementTier.DEINTERLACE_ONLY.downgrade(), EnhancementTier.NONE);
    assertEquals(EnhancementTier.NONE.downgrade(), EnhancementTier.NONE);
  }

  /** A step-down loop over the ladder must always terminate. */
  @Test
  public void testWalkingTheLadderAlwaysTerminates()
  {
    EnhancementTier t = EnhancementTier.ENHANCE_2160P;
    int guard = 0;
    while (t.isActive() && guard++ < 100) t = t.downgrade();
    assertEquals(t, EnhancementTier.NONE);
    assertTrue(guard < 100, "ladder walk failed to terminate");
  }

  @Test
  public void testRanksAreStrictlyOrdered()
  {
    assertTrue(EnhancementTier.NONE.getRank() < EnhancementTier.DEINTERLACE_ONLY.getRank());
    assertTrue(EnhancementTier.DEINTERLACE_ONLY.getRank() < EnhancementTier.ENHANCE_1080P.getRank());
    assertTrue(EnhancementTier.ENHANCE_1080P.getRank() < EnhancementTier.ENHANCE_1440P.getRank());
    assertTrue(EnhancementTier.ENHANCE_1440P.getRank() < EnhancementTier.ENHANCE_2160P.getRank());
  }

  // ---- Geometry -----------------------------------------------------------

  @Test
  public void testUpscalingTiersCarryTargets()
  {
    assertEquals(EnhancementTier.ENHANCE_2160P.getTargetWidth(), 3840);
    assertEquals(EnhancementTier.ENHANCE_2160P.getTargetHeight(), 2160);
    assertEquals(EnhancementTier.ENHANCE_1440P.getTargetHeight(), 1440);
    assertEquals(EnhancementTier.ENHANCE_1080P.getTargetHeight(), 1080);
    assertTrue(EnhancementTier.ENHANCE_1080P.isUpscaling());
  }

  @Test
  public void testNonScalingTiersCarryNoTargets()
  {
    assertFalse(EnhancementTier.DEINTERLACE_ONLY.isUpscaling());
    assertEquals(EnhancementTier.DEINTERLACE_ONLY.getTargetHeight(), 0);
    assertFalse(EnhancementTier.NONE.isActive());
    assertTrue(EnhancementTier.DEINTERLACE_ONLY.isActive());
  }

  // ---- Clamping -----------------------------------------------------------

  @Test
  public void testClampToHeightStepsDownToFit()
  {
    assertEquals(EnhancementTier.clampToHeight(EnhancementTier.ENHANCE_2160P, 1080),
        EnhancementTier.ENHANCE_1080P);
    assertEquals(EnhancementTier.clampToHeight(EnhancementTier.ENHANCE_2160P, 1440),
        EnhancementTier.ENHANCE_1440P);
    assertEquals(EnhancementTier.clampToHeight(EnhancementTier.ENHANCE_2160P, 2160),
        EnhancementTier.ENHANCE_2160P);
  }

  /** A sink too small for even 1080p enhancement falls through to deinterlace. */
  @Test
  public void testClampBelow1080YieldsDeinterlaceOnly()
  {
    assertEquals(EnhancementTier.clampToHeight(EnhancementTier.ENHANCE_2160P, 720),
        EnhancementTier.DEINTERLACE_ONLY);
  }

  @Test
  public void testClampHandlesNullAndNonScaling()
  {
    assertEquals(EnhancementTier.clampToHeight(null, 2160), EnhancementTier.NONE);
    assertEquals(EnhancementTier.clampToHeight(EnhancementTier.DEINTERLACE_ONLY, 480),
        EnhancementTier.DEINTERLACE_ONLY);
  }

  // ---- Tokens -------------------------------------------------------------

  @Test
  public void testWireTokenRoundTrip()
  {
    for (EnhancementTier t : EnhancementTier.values())
    {
      if (!t.isActive()) continue;
      assertEquals(EnhancementTier.fromToken(t.wireToken()), t,
          "wire token round-trip failed for " + t);
    }
  }

  @Test
  public void testNoneHasNoWireToken()
  {
    assertNull(EnhancementTier.NONE.wireToken());
  }

  @Test
  public void testFromTokenAcceptsAliasesAndFailsClosed()
  {
    assertEquals(EnhancementTier.fromToken("4k"), EnhancementTier.ENHANCE_2160P);
    assertEquals(EnhancementTier.fromToken("2160"), EnhancementTier.ENHANCE_2160P);
    assertEquals(EnhancementTier.fromToken("deinterlace"), EnhancementTier.DEINTERLACE_ONLY);
    // Unknown input must fail closed to NONE, never to an enhancement tier.
    assertEquals(EnhancementTier.fromToken("nonsense"), EnhancementTier.NONE);
    assertEquals(EnhancementTier.fromToken(null), EnhancementTier.NONE);
    assertEquals(EnhancementTier.fromToken(""), EnhancementTier.NONE);
  }
}
