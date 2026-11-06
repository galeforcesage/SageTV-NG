package sage.party;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

/** Tests the transport-independent authoritative Party timeline. */
public class PartyTimelineTest
{
  @Test
  public void scheduledPlayBecomesCanonicalAtDeadline()
  {
    PartyTimeline timeline = new PartyTimeline(60000, false, 0, 1000);
    timeline.setInitialPosition(5000);

    PartyCommand command = timeline.schedule("PLAY", 5000, "PLAYING", 1, 2000, "host");

    assertEquals(timeline.positionAt(1999), 5000L);
    assertEquals(timeline.positionAt(2500), 5500L);
    assertSame(timeline.advance(2000), command);
    assertEquals(timeline.state(), "PLAYING");
    assertEquals(timeline.positionAt(2500), 5500L);
  }

  @Test
  public void growingMediaIsClampedBehindLiveEdge()
  {
    PartyTimeline timeline = new PartyTimeline(10000, true, 3000, 1000);

    timeline.setInitialPosition(9500);
    PartyCommand command = timeline.schedule("SEEK", 12000, "PAUSED", 1, 2000, "host");

    assertEquals(timeline.anchorPositionMs(), 7000L);
    assertEquals(command.targetPositionMs(), 7000L);
    timeline.refreshWindow(15000, true);
    assertEquals(timeline.playableEndMs(), 12000L);
  }

  @Test
  public void replacementCommandAdvancesGenerationAndSequence()
  {
    PartyTimeline timeline = new PartyTimeline(60000, false, 0, 1000);

    PartyCommand first = timeline.schedule("PLAY", 0, "PLAYING", 1, 2000, "host");
    PartyCommand second = timeline.schedule("PAUSE", 1000, "PAUSED", 1, 3000, "host");

    assertEquals(first.stateVersion(), 1L);
    assertEquals(first.commandSeq(), 1L);
    assertEquals(second.stateVersion(), 2L);
    assertEquals(second.commandSeq(), 2L);
    assertSame(timeline.pending(), second);
  }
}
