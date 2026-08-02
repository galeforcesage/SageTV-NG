package sage;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Tests for {@link FFMPEGTranscoder}'s phantom-transcode reaper: the live
 * child registry plus the shutdown-time reap that force-kills any ffmpeg
 * children still running when the JVM exits (so a {@code stopsage} during a
 * deploy can't orphan a {@code -stdinctrl} ffmpeg to PID 1 forever).
 */
public class FFMPEGTranscoderReaperTest
{
  private static final String REAP_ENABLED = "media_server/reap_transcodes_on_shutdown";

  @BeforeMethod
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.remove(REAP_ENABLED);
    // Ensure a clean registry between tests.
    FFMPEGTranscoder.reapLiveTranscodeProcesses();
  }

  @AfterMethod
  public void tearDown() throws Throwable
  {
    Sage.remove(REAP_ENABLED);
    FFMPEGTranscoder.reapLiveTranscodeProcesses();
  }

  /** Spawn a long-lived, cross-platform child process (~60s) we can reap. */
  private Process spawnSleeper() throws java.io.IOException
  {
    java.util.List<String> cmd = new java.util.ArrayList<String>();
    if (Sage.WINDOWS_OS)
    {
      // ping loopback 60 times ~= 60s; robust, always present on Windows.
      cmd.add("ping");
      cmd.add("-n");
      cmd.add("60");
      cmd.add("127.0.0.1");
    }
    else
    {
      cmd.add("sleep");
      cmd.add("60");
    }
    return new ProcessBuilder(cmd).start();
  }

  @Test
  public void testRegisteredChildIsReapedOnShutdown() throws Throwable
  {
    Process p = spawnSleeper();
    try
    {
      assertTrue(p.isAlive(), "sleeper should be alive right after spawn");
      FFMPEGTranscoder.registerLiveChild(p);
      int killed = FFMPEGTranscoder.reapLiveTranscodeProcesses();
      assertTrue(killed >= 1, "reaper should report at least the one registered child killed");
      assertTrue(p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS),
          "registered child must be dead shortly after reap");
      assertFalse(p.isAlive(), "registered child must not survive the reaper");
    }
    finally
    {
      p.destroyForcibly();
    }
  }

  @Test
  public void testUnregisteredChildIsNotReaped() throws Throwable
  {
    Process p = spawnSleeper();
    try
    {
      FFMPEGTranscoder.registerLiveChild(p);
      FFMPEGTranscoder.unregisterLiveChild(p);
      int killed = FFMPEGTranscoder.reapLiveTranscodeProcesses();
      assertEquals(killed, 0, "an unregistered child must not be reaped");
      assertTrue(p.isAlive(), "child that was unregistered before reap must still be alive");
    }
    finally
    {
      p.destroyForcibly();
    }
  }

  @Test
  public void testReapDisabledByKillSwitch() throws Throwable
  {
    Sage.put(REAP_ENABLED, "false");
    Process p = spawnSleeper();
    try
    {
      FFMPEGTranscoder.registerLiveChild(p);
      int killed = FFMPEGTranscoder.reapLiveTranscodeProcesses();
      assertEquals(killed, 0, "kill-switch off must skip reaping entirely");
      assertTrue(p.isAlive(), "child must survive when the reaper is disabled");
    }
    finally
    {
      p.destroyForcibly();
      Sage.remove(REAP_ENABLED);
    }
  }

  @Test
  public void testReapWithNoRegisteredChildrenIsNoOp() throws Throwable
  {
    assertEquals(FFMPEGTranscoder.reapLiveTranscodeProcesses(), 0,
        "reaping an empty registry should kill nothing and not throw");
  }

  @Test
  public void testAlreadyDeadChildIsNotCounted() throws Throwable
  {
    Process p = spawnSleeper();
    p.destroyForcibly();
    assertTrue(p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS), "sleeper should die when force-destroyed");
    FFMPEGTranscoder.registerLiveChild(p);
    assertEquals(FFMPEGTranscoder.reapLiveTranscodeProcesses(), 0,
        "a registered-but-already-dead child should not be counted as killed");
  }
}
