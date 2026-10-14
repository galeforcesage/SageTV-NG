package sage;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

public class HTTPLSServerTest
{
  @Test
  public void testLowLatencyFeaturesAreLiveOnly() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    assertTrue(HTTPLSServer.lowLatencyPlaylistEnabled(true, true));
    assertFalse(HTTPLSServer.lowLatencyPlaylistEnabled(true, false),
        "A completed recording must not be reduced to its final live window");
    assertFalse(HTTPLSServer.lowLatencyPlaylistEnabled(false, true));
    assertFalse(HTTPLSServer.lowLatencyPlaylistEnabled(false, false));
  }

  @Test
  public void testCompletedRecordingRetainsResumeSegmentAndFullDuration() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    StringBuffer playlist = new StringBuffer();
    boolean lowLatency = HTTPLSServer.lowLatencyPlaylistEnabled(true, false);
    int windowStart = HTTPLSServer.fmp4WindowStart(lowLatency, 1920, 1, 120);
    assertEquals(windowStart, 0);
    HTTPLSServer.appendFmp4Parts(playlist, "recording", 1920000L, windowStart, 1, lowLatency);
    String result = playlist.toString();
    assertTrue(result.startsWith("#EXTINF:1.0,\r\nrecording_0.m4s\r\n"));
    assertTrue(result.contains("\r\nrecording_848.m4s\r\n"),
        "Resume at 848829ms must remain addressable, not fall outside a 1800s live window");
    assertTrue(result.endsWith("#EXTINF:1.0,\r\nrecording_1919.m4s\r\n"));
    assertEquals(result.split("#EXTINF:", -1).length - 1, 1920);
    assertFalse(result.contains("#EXT-X-PART:"));
  }

  @Test
  public void testLiveWindowStillAdvertisesLowLatencyParts() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    StringBuffer playlist = new StringBuffer();
    boolean lowLatency = HTTPLSServer.lowLatencyPlaylistEnabled(true, true);
    int windowStart = HTTPLSServer.fmp4WindowStart(lowLatency, 1920, 1, 120);
    assertEquals(windowStart, 1800);
    assertEquals(HTTPLSServer.fmp4WindowStart(lowLatency, 100, 1, 120), 0);
    assertEquals(HTTPLSServer.fmp4WindowStart(lowLatency, 1920, 1, 0), 0);
    HTTPLSServer.appendFmp4Parts(playlist, "live", 1920000L, windowStart, 1, lowLatency);
    String result = playlist.toString();
    assertEquals(result.split("#EXTINF:", -1).length - 1, 120);
    assertTrue(result.contains("#EXT-X-PART:DURATION=1.0,URI=\"live_1919.m4s\""));
    assertFalse(result.contains("\r\nlive_848.m4s\r\n"));
  }

  @Test
  public void testCmafSeamCapabilityDefaultsOffAndRequiresExactOptIn()
  {
    assertFalse(HTTPLSServer.seamCapabilityEnabled(null, false));
    assertFalse(HTTPLSServer.seamCapabilityEnabled("", false));
    assertFalse(HTTPLSServer.seamCapabilityEnabled("true", false));
    assertFalse(HTTPLSServer.seamCapabilityEnabled("0", false));
    assertTrue(HTTPLSServer.seamCapabilityEnabled("1", false));
    assertTrue(HTTPLSServer.seamCapabilityEnabled(null, true));
  }

  @Test
  public void testMultipleCmafSeamsEmitDiscontinuityAndFreshMapForEachSuccessor()
  {
    StringBuffer playlist = new StringBuffer();
    HTTPLSServer.appendFmp4SeamTransition(playlist, "next", 2000L, 1, false);
    HTTPLSServer.appendFmp4SeamTransition(playlist, "later", 1000L, 1, false);

    String result = playlist.toString();
    assertEquals(result.split("#EXT-X-DISCONTINUITY", -1).length - 1, 2);
    assertTrue(result.contains("#EXT-X-MAP:URI=\"next_init.mp4\"\r\n"));
    assertTrue(result.contains("#EXT-X-MAP:URI=\"later_init.mp4\"\r\n"));
    assertTrue(result.contains("next_1.m4s\r\n#EXT-X-DISCONTINUITY"));
    assertTrue(result.endsWith("later_0.m4s\r\n"));
  }

  @Test
  public void testCmafSeamGraceIsBoundedResettableAndIndependentPerSession()
  {
    HTTPLSServer.Fmp4SeamGrace grace = new HTTPLSServer.Fmp4SeamGrace();
    assertTrue(grace.holdOpen("a", 1000L, 8000L));
    assertTrue(grace.holdOpen("a", 8999L, 8000L));
    assertFalse(grace.holdOpen("a", 9000L, 8000L));
    assertTrue(grace.holdOpen("a", 9001L, 8000L),
        "An expired lifecycle must be removed so a later seam gets a fresh grace window");
    assertTrue(grace.holdOpen("b", 50000L, 8000L));
    grace.clear("b");
    assertTrue(grace.holdOpen("b", 70000L, 8000L));
  }

  @Test
  public void testCmafSeamGraceFailsClosedForInvalidInputs()
  {
    HTTPLSServer.Fmp4SeamGrace grace = new HTTPLSServer.Fmp4SeamGrace();
    assertFalse(grace.holdOpen(null, 1000L, 8000L));
    assertFalse(grace.holdOpen("a", 1000L, 0L));
    assertFalse(grace.holdOpen("a", 1000L, -1L));
  }
}
