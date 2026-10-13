package sage.ng;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class ActivePullPacingPolicyTest
{
  @Test
  public void eligibleSessionUsesAdaptiveDefaults()
  {
    ActivePullPacingPolicy policy = resolve(true, true, true, true, false,
        "1.0", "6", "1.5");
    assertTrue(policy.isEnabled());
    assertEquals(policy.getReadRate(), 1.0);
    assertEquals(policy.getInitialBurstSeconds(), 6.0);
    assertEquals(policy.getCatchupRate(), 1.5);

    List<String> args = new ArrayList<String>();
    policy.appendInputArguments(args);
    assertEquals(args, Arrays.asList("-readrate", "1",
        "-readrate_initial_burst", "6", "-readrate_catchup", "1.5"));
  }

  @Test
  public void ineligibleTransportsRemainUnpaced()
  {
    assertFalse(resolve(false, true, true, true, false, "1", "6", "1.5").isEnabled());
    assertFalse(resolve(true, false, true, true, false, "1", "6", "1.5").isEnabled());
    assertFalse(resolve(true, true, false, true, false, "1", "6", "1.5").isEnabled());
    assertFalse(resolve(true, true, true, false, false, "1", "6", "1.5").isEnabled());
    assertFalse(resolve(true, true, true, true, true, "1", "6", "1.5").isEnabled());
  }

  @Test
  public void malformedAndOutOfRangeValuesResolveDeterministically()
  {
    ActivePullPacingPolicy policy = resolve(true, true, true, true, false,
        "not-a-number", "999", "0.25");
    assertEquals(policy.getReadRate(), ActivePullPacingPolicy.DEFAULT_READ_RATE);
    assertEquals(policy.getInitialBurstSeconds(), 30.0);
    assertEquals(policy.getCatchupRate(), policy.getReadRate(),
        "Catch-up may never be slower than the normal read rate");

    policy = resolve(true, true, true, true, false,
        "9", "-3", "99");
    assertEquals(policy.getReadRate(), 2.0);
    assertEquals(policy.getInitialBurstSeconds(), 0.0);
    assertEquals(policy.getCatchupRate(), 4.0);
  }

  @Test
  public void startupAndRecoveryLeadAreBounded()
  {
    ActivePullPacingPolicy policy = resolve(true, true, true, true, false,
        "1", "6", "1.5");
    double produced = policy.getInitialBurstSeconds();
    assertEquals(produced, 6.0, "Startup may establish only the configured reservoir");

    produced += 10.0 * policy.getReadRate();
    assertEquals(produced, 16.0, "Healthy production advances at media time");

    double producedBeforeBlock = produced;
    double blockedSeconds = 5.0;
    assertEquals(produced, producedBeforeBlock,
        "Existing downstream backpressure stops production during a network block");
    double normalBudgetAfterBlock = policy.maxInputMediaSeconds(10.0 + blockedSeconds, false);
    assertEquals(normalBudgetAfterBlock - produced, 5.0,
        "A network block creates a refill deficit instead of producer lead");

    produced += 5.0 * policy.getCatchupRate();
    assertEquals(produced, 23.5,
        "Recovery refills above 1x but only at the configured catch-up rate");
    assertTrue(produced < policy.maxInputMediaSeconds(20.0, false),
        "Bounded catch-up cannot create unlimited lead over the media clock");
  }

  private static ActivePullPacingPolicy resolve(boolean enabled, boolean active,
      boolean buffered, boolean protocol21, boolean segmented, String rate,
      String burst, String catchup)
  {
    return ActivePullPacingPolicy.resolve(enabled, active, buffered, protocol21,
        segmented, rate, burst, catchup);
  }
}
