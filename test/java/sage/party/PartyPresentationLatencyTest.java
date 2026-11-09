package sage.party;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class PartyPresentationLatencyTest
{
  @Test
  public void requiresRobustPreparationAndRevealHistory()
  {
    PartyPresentationLatency model = new PartyPresentationLatency();
    model.preparation(900);
    model.preparation(1_000);
    model.reveal(75);
    model.reveal(80);

    assertFalse(model.confident());
    assertEquals(model.predictedTotalMs(), 0L);

    model.preparation(1_100);
    model.reveal(85);

    assertTrue(model.confident());
    assertEquals(model.predictedTotalMs(), 1_080L);
  }

  @Test
  public void medianRejectsSingleLatencyOutlier()
  {
    PartyPresentationLatency model = new PartyPresentationLatency();
    model.preparation(950);
    model.preparation(1_000);
    model.preparation(1_050);
    model.preparation(8_000);
    model.reveal(70);
    model.reveal(80);
    model.reveal(90);
    model.reveal(5_000);

    assertEquals(model.predictedTotalMs(), 1_110L);
    assertTrue(model.uncertaintyMs() < 500);
  }

  @Test
  public void ignoresImpossibleSamplesAndClampsPrediction()
  {
    PartyPresentationLatency model = new PartyPresentationLatency();
    model.preparation(-1);
    model.preparation(10_001);
    model.preparation(7_000);
    model.preparation(7_100);
    model.preparation(7_200);
    model.reveal(500);
    model.reveal(600);
    model.reveal(700);

    assertEquals(model.preparationSamples(), 3);
    assertEquals(model.revealSamples(), 3);
    assertEquals(model.predictedTotalMs(), 5_000L);
  }
}
