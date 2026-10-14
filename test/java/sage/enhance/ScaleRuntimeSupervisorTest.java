package sage.enhance;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.Test;

import sage.enhance.spi.IndexedFrameTransport;
import sage.enhance.spi.ScaleRuntimeSignal;

public class ScaleRuntimeSupervisorTest
{
  private static final IndexedFrameTransport TRANSPORT =
      new IndexedFrameTransport(IndexedFrameTransport.VERSION_1, 90000L, 16, 64);

  private static final class Sink implements ScaleFrameRouter.Sink
  {
    final List<Long> indices = new ArrayList<Long>();
    public void write(long frameIndex, long pts, byte[] frame)
    {
      indices.add(frameIndex);
    }
  }

  private static class Fallback implements ScaleRuntimeSupervisor.Fallback
  {
    boolean started;
    public int getOutputWidth() { return 4; }
    public int getOutputHeight() { return 4; }
    public String getPixelFormat() { return "rgba"; }
    public IndexedFrameTransport getTransport() { return TRANSPORT; }
    public void startAfter(long frameIndex, long pts) { started = true; }
  }

  @Test
  public void degradeStartsCompatibleFallbackWithoutRestartingSink() throws Exception
  {
    Sink sink = new Sink();
    ScaleFrameRouter router = new ScaleFrameRouter(0, 2, sink);
    Fallback fallback = new Fallback();
    ScaleRuntimeSupervisor supervisor = new ScaleRuntimeSupervisor(
        router, 4, 4, "rgba", TRANSPORT, fallback,
        new ScaleRuntimeSupervisor.FatalHandler()
        {
          public void failClosed(String reason) throws IOException
          {
            throw new IOException(reason);
          }
        });

    router.submit(ScaleFrameRouter.Source.PRIMARY, 0, 0, new byte[64]);
    assertEquals(supervisor.handle(ScaleRuntimeSignal.degrade("capacity", 0, 0)),
        ScaleRuntimeSupervisor.Result.DEGRADED);
    router.submit(ScaleFrameRouter.Source.PRIMARY, 1, 1001, new byte[64]);
    router.submit(ScaleFrameRouter.Source.FALLBACK, 0, 0, new byte[64]);
    router.submit(ScaleFrameRouter.Source.FALLBACK, 1, 1001, new byte[64]);

    assertTrue(fallback.started);
    assertTrue(router.isDegraded());
    assertEquals(sink.indices, java.util.Arrays.asList(0L, 1L));
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void mismatchedFallbackGeometryFailsClosed()
  {
    new ScaleRuntimeSupervisor(new ScaleFrameRouter(0, 2, new Sink()),
        8, 4, "rgba", TRANSPORT, new Fallback(),
        new ScaleRuntimeSupervisor.FatalHandler()
        {
          public void failClosed(String reason) {}
        });
  }

  @Test
  public void fatalIsSeparateFromDegradation() throws Exception
  {
    final boolean[] fatal = { false };
    ScaleFrameRouter router = new ScaleFrameRouter(0, 2, new Sink());
    Fallback fallback = new Fallback();
    ScaleRuntimeSupervisor supervisor = new ScaleRuntimeSupervisor(
        router, 4, 4, "rgba", TRANSPORT, fallback,
        new ScaleRuntimeSupervisor.FatalHandler()
        {
          public void failClosed(String reason) { fatal[0] = true; }
        });
    assertEquals(supervisor.handle(ScaleRuntimeSignal.fatal("encoder")),
        ScaleRuntimeSupervisor.Result.FATAL);
    assertTrue(fatal[0]);
    assertFalse(fallback.started);
    assertFalse(router.isDegraded());
  }
}
