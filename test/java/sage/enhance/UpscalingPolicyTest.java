package sage.enhance;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class UpscalingPolicyTest
{
  @Test
  public void policyTokensCanonicalizeClientValues()
  {
    assertEquals(UpscalingPolicy.fromToken("auto"), UpscalingPolicy.AUTO);
    assertEquals(UpscalingPolicy.fromToken("always"), UpscalingPolicy.ALWAYS);
    assertEquals(UpscalingPolicy.fromToken("server"), UpscalingPolicy.ALWAYS);
    assertEquals(UpscalingPolicy.fromToken("ai-only"), UpscalingPolicy.AI_ONLY);
    assertEquals(UpscalingPolicy.fromToken("never"), UpscalingPolicy.NEVER);
    assertEquals(UpscalingPolicy.fromToken("unexpected"), UpscalingPolicy.AUTO);
    assertTrue(UpscalingPolicy.AI_ONLY.requiresSpecializedServerUpscaler());
    assertFalse(UpscalingPolicy.ALWAYS.requiresSpecializedServerUpscaler());
    assertTrue(UpscalingPolicy.AUTO.requiresSpecializedServerUpscaler(
        UpscalingCapability.HIGH_QUALITY));
    assertFalse(UpscalingPolicy.AUTO.requiresSpecializedServerUpscaler(
        UpscalingCapability.STANDARD));
  }

  @Test
  public void capabilityTokensCanonicalizeClientValues()
  {
    assertEquals(UpscalingCapability.fromToken("none"), UpscalingCapability.NONE);
    assertEquals(UpscalingCapability.fromToken("standard"), UpscalingCapability.STANDARD);
    assertEquals(UpscalingCapability.fromToken("high-quality"),
        UpscalingCapability.HIGH_QUALITY);
    assertEquals(UpscalingCapability.fromToken("AI"), UpscalingCapability.AI);
    assertEquals(UpscalingCapability.fromToken("unexpected"), UpscalingCapability.NONE);
    assertTrue(UpscalingCapability.HIGH_QUALITY.isHighQuality());
    assertTrue(UpscalingCapability.AI.isHighQuality());
    assertFalse(UpscalingCapability.STANDARD.isHighQuality());
  }
}
