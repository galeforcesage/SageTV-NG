package sage;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

/**
 * Tests for {@link ProcessPriority}, which closes a long-standing gap: the
 * {@code nice}/{@code ionice} block in {@code FFMPEGTranscoder.startTranscode()}
 * is guarded by {@code !Sage.WINDOWS_OS}, so Windows hosts had no transcoder
 * priority reduction at all.
 */
public class ProcessPriorityTest
{
  @BeforeMethod
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.remove("xcode_windows_priority_class");
    Sage.remove("xcode_reduce_process_priority");
  }

  @Test
  public void testDefaultIsBelowNormal()
  {
    assertEquals(ProcessPriority.normalizeClass(null), "BelowNormal");
    assertEquals(ProcessPriority.normalizeClass(""), "BelowNormal");
    assertEquals(ProcessPriority.normalizeClass("   "), "BelowNormal");
  }

  @Test
  public void testAcceptedAliases()
  {
    assertEquals(ProcessPriority.normalizeClass("idle"), "Idle");
    assertEquals(ProcessPriority.normalizeClass("low"), "Idle");
    assertEquals(ProcessPriority.normalizeClass("belownormal"), "BelowNormal");
    assertEquals(ProcessPriority.normalizeClass("below_normal"), "BelowNormal");
    assertEquals(ProcessPriority.normalizeClass("BELOW"), "BelowNormal");
  }

  /** "Normal"/"off" means leave the process alone, signalled by null. */
  @Test
  public void testNormalMeansDoNothing()
  {
    assertNull(ProcessPriority.normalizeClass("normal"));
    assertNull(ProcessPriority.normalizeClass("none"));
    assertNull(ProcessPriority.normalizeClass("off"));
  }

  @Test
  public void testUnknownValueFallsBackToTheSafeDefault()
  {
    assertEquals(ProcessPriority.normalizeClass("turbo"), "BelowNormal");
  }

  /** Must never throw, whatever it's handed — it runs on the stream start path. */
  @Test
  public void testReduceIsSafeWithNullProcess()
  {
    ProcessPriority.reduce(null);
  }

  @Test
  public void testReduceIsSafeWithADeadProcess() throws Exception
  {
    Process p = new ProcessBuilder(Sage.WINDOWS_OS
        ? new String[] { "cmd", "/c", "exit" }
        : new String[] { "true" }).start();
    p.waitFor();
    ProcessPriority.reduce(p);
  }

  /**
   * End-to-end on Windows: start a real child, lower it, and confirm the OS
   * agrees. Skipped on POSIX, where the nice/ionice prefix already handles this
   * at exec time and {@code reduce()} is intentionally a no-op.
   */
  @Test
  public void testWindowsChildActuallyGetsLoweredPriority() throws Exception
  {
    if (!Sage.WINDOWS_OS)
    {
      // On POSIX reduce() must do nothing at all.
      Process p = new ProcessBuilder("sleep", "5").start();
      try { ProcessPriority.reduce(p); }
      finally { p.destroyForcibly(); }
      return;
    }

    Sage.put("xcode_reduce_process_priority", "true");
    Sage.put("xcode_windows_priority_class", "BelowNormal");
    Process p = new ProcessBuilder("cmd", "/c", "ping -n 20 127.0.0.1 > NUL").start();
    try
    {
      ProcessPriority.reduce(p);
      assertEquals(readPriorityClass(p.pid()), "BelowNormal",
          "the child's priority class should have been lowered");
    }
    finally
    {
      p.destroyForcibly();
      p.waitFor();
    }
  }

  /** Ask Windows what a PID's priority class actually is. */
  private static String readPriorityClass(long pid) throws Exception
  {
    Process q = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command",
        "(Get-Process -Id " + pid + ").PriorityClass").redirectErrorStream(true).start();
    java.io.BufferedReader r = new java.io.BufferedReader(
        new java.io.InputStreamReader(q.getInputStream()));
    StringBuilder sb = new StringBuilder();
    String line;
    while ((line = r.readLine()) != null) sb.append(line.trim());
    q.waitFor();
    return sb.toString();
  }
}
