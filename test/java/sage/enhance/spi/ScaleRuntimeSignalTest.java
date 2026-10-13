package sage.enhance.spi;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

import org.testng.annotations.Test;

public class ScaleRuntimeSignalTest
{
  @Test
  public void parsesTypedSignals()
  {
    assertEquals(ScaleRuntimeSignal.parse("SCALE_HEALTH|1|HEALTHY").getKind(),
        ScaleRuntimeSignal.Kind.HEALTHY);
    ScaleRuntimeSignal degrade =
        ScaleRuntimeSignal.parse("SCALE_HEALTH|1|DEGRADE|123|456789|below-capacity");
    assertEquals(degrade.getKind(), ScaleRuntimeSignal.Kind.DEGRADE);
    assertEquals(degrade.getLastCompletedFrameIndex(), 123L);
    assertEquals(degrade.getLastCompletedPts(), 456789L);
    assertEquals(degrade.getReason(), "below-capacity");
    assertEquals(ScaleRuntimeSignal.parse("SCALE_HEALTH|1|FATAL|gpu-reset").getKind(),
        ScaleRuntimeSignal.Kind.FATAL);
    assertNull(ScaleRuntimeSignal.parse("READY"));
    assertNull(ScaleRuntimeSignal.parse("SCALE_HEALTH|1|DEGRADE|bad|1|reason"));
  }
}
