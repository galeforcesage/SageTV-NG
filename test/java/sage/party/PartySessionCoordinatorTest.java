package sage.party;

import java.util.List;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

/** Tests transport-independent Party command coordination. */
public class PartySessionCoordinatorTest
{
  @Test
  public void lateParticipantDoesNotIncreaseReadyCohortLead()
  {
    PartySessionCoordinator coordinator = new PartySessionCoordinator(750, 3000);

    long lead = coordinator.commandLead(List.of(
        new PartySessionCoordinator.ParticipantTiming(true, 50),
        new PartySessionCoordinator.ParticipantTiming(false, 900)));

    assertEquals(lead, 750L);
  }

  @Test
  public void scheduleAdvancesTimelineAtCalculatedDeadline()
  {
    PartySessionCoordinator coordinator = new PartySessionCoordinator(750, 3000);
    PartyTimeline timeline = new PartyTimeline(60000, false, 0, 1000);

    PartyCommand command = coordinator.schedule(timeline, "PLAY", 5000, "PLAYING",
        1000, "host", List.of(new PartySessionCoordinator.ParticipantTiming(true, 200)));

    assertEquals(command.executeAtServerMonoMs(), 2050L);
    assertSame(timeline.pending(), command);
  }
}
