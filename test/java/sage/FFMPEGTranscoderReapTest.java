package sage;

import org.testng.annotations.Test;

import java.io.File;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Regression coverage for the phantom-ffmpeg leak.
 *
 * Observed in production: an ffmpeg child that ignores SIGTERM survived
 * stopTranscode(), was already removed from the shutdown reaper's registry,
 * and had its Process reference dropped -- leaving it unkillable from the JVM.
 * It then outlived a JVM restart, reparented to init, and held a CUDA context
 * plus an open handle to an already-deleted recording for days.
 *
 * These tests exercise the escalation path directly rather than through the
 * full transcoder, so they need no ffmpeg and no GPU.
 */
public class FFMPEGTranscoderReapTest
{
  private static boolean posix()
  {
    return !System.getProperty("os.name", "").toLowerCase().contains("win");
  }

  /** A cooperative child must be reaped by the polite SIGTERM, with no SIGKILL. */
  @Test
  public void testTerminatesCooperativeChild() throws Exception
  {
    Process p = spawnSleeper(false);
    if (p == null) throw new org.testng.SkipException("no suitable shell on this host");

    assertTrue(p.isAlive(), "child should be running before termination");
    boolean dead = FFMPEGTranscoder.terminateChildWithEscalation(p);
    assertTrue(dead, "cooperative child should be confirmed dead");
    assertFalse(p.isAlive(), "cooperative child must not survive");
  }

  /**
   * The core regression: a child that explicitly ignores SIGTERM must still be
   * killed. Before the fix this returned with the process still alive.
   */
  @Test
  public void testEscalatesToKillWhenSigtermIgnored() throws Exception
  {
    if (!posix()) throw new org.testng.SkipException("SIGTERM-trapping test is POSIX-only");
    Process p = spawnSleeper(true);
    if (p == null) throw new org.testng.SkipException("no suitable shell on this host");

    // Give the trap a moment to install before we signal it.
    Thread.sleep(300);
    assertTrue(p.isAlive(), "trapping child should be running");

    long start = System.currentTimeMillis();
    boolean dead = FFMPEGTranscoder.terminateChildWithEscalation(p);
    long elapsed = System.currentTimeMillis() - start;

    assertTrue(dead, "SIGTERM-ignoring child must still be confirmed dead via SIGKILL");
    assertFalse(p.isAlive(), "SIGTERM-ignoring child must not survive escalation");
    assertTrue(elapsed >= 100, "escalation should have waited out the grace period, took " + elapsed + "ms");
  }

  /** Null and already-dead children are handled without throwing. */
  @Test
  public void testNullAndDeadChildrenAreNoOps() throws Exception
  {
    assertTrue(FFMPEGTranscoder.terminateChildWithEscalation(null), "null child is trivially dead");

    Process p = spawnSleeper(false);
    if (p == null) throw new org.testng.SkipException("no suitable shell on this host");
    p.destroyForcibly();
    p.waitFor(5, TimeUnit.SECONDS);
    assertTrue(FFMPEGTranscoder.terminateChildWithEscalation(p), "already-dead child is dead");
  }

  /**
   * Spawn a long-lived child.
   *
   * @param trapSigterm when true the child installs a SIGTERM trap that ignores
   *                    it, reproducing the -stdinctrl ffmpeg behavior.
   */
  private static Process spawnSleeper(boolean trapSigterm) throws Exception
  {
    if (posix())
    {
      String sh = new File("/bin/sh").exists() ? "/bin/sh" : null;
      if (sh == null) return null;
      String script = trapSigterm
          ? "trap '' TERM; while true; do sleep 1; done"
          : "while true; do sleep 1; done";
      return new ProcessBuilder(sh, "-c", script).start();
    }
    // Windows has no SIGTERM semantics; only the cooperative case is meaningful.
    File cmd = new File(System.getenv("ComSpec") == null ? "C:\\Windows\\System32\\cmd.exe"
        : System.getenv("ComSpec"));
    if (!cmd.exists()) return null;
    return new ProcessBuilder(cmd.getAbsolutePath(), "/c", "ping -n 600 127.0.0.1 > NUL").start();
  }
}
