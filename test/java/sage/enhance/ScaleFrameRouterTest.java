package sage.enhance;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.Test;

public class ScaleFrameRouterTest
{
  private static final class CollectingSink implements ScaleFrameRouter.Sink
  {
    final List<Long> indices = new ArrayList<Long>();
    final List<Long> pts = new ArrayList<Long>();
    public void write(long index, long timestamp, byte[] frame)
    {
      indices.add(index);
      pts.add(timestamp);
    }
  }

  @Test
  public void degradationSwitchesAfterCommittedCheckpointWithoutReplay() throws Exception
  {
    CollectingSink sink = new CollectingSink();
    ScaleFrameRouter router = new ScaleFrameRouter(0L, 4, sink);
    router.submit(ScaleFrameRouter.Source.PRIMARY, 0, 0, new byte[] {0});
    router.submit(ScaleFrameRouter.Source.PRIMARY, 1, 1001, new byte[] {1});
    router.degrade(1, 1001);
    router.submit(ScaleFrameRouter.Source.PRIMARY, 2, 2002, new byte[] {2});
    router.submit(ScaleFrameRouter.Source.FALLBACK, 1, 1001, new byte[] {1});
    router.submit(ScaleFrameRouter.Source.FALLBACK, 2, 2002, new byte[] {2});
    router.submit(ScaleFrameRouter.Source.FALLBACK, 3, 3003, new byte[] {3});
    assertEquals(sink.indices, java.util.Arrays.asList(0L, 1L, 2L, 3L));
    assertEquals(sink.pts, java.util.Arrays.asList(0L, 1001L, 2002L, 3003L));
    assertTrue(router.isDegraded());
  }

  @Test
  public void outOfOrderFramesCommitInOrder() throws Exception
  {
    CollectingSink sink = new CollectingSink();
    ScaleFrameRouter router = new ScaleFrameRouter(0L, 3, sink);
    router.submit(ScaleFrameRouter.Source.PRIMARY, 1, 1001, new byte[] {1});
    router.submit(ScaleFrameRouter.Source.PRIMARY, 0, 0, new byte[] {0});
    assertEquals(sink.indices, java.util.Arrays.asList(0L, 1L));
  }

  @Test(expectedExceptions = IOException.class)
  public void missingFrameBacklogIsBounded() throws Exception
  {
    ScaleFrameRouter router = new ScaleFrameRouter(0L, 2, new CollectingSink());
    router.submit(ScaleFrameRouter.Source.PRIMARY, 2, 2002, new byte[] {2});
    router.submit(ScaleFrameRouter.Source.PRIMARY, 3, 3003, new byte[] {3});
    router.submit(ScaleFrameRouter.Source.PRIMARY, 4, 4004, new byte[] {4});
  }

  @Test(expectedExceptions = IOException.class)
  public void ptsRegressionIsRejected() throws Exception
  {
    ScaleFrameRouter router = new ScaleFrameRouter(0L, 2, new CollectingSink());
    router.submit(ScaleFrameRouter.Source.PRIMARY, 0, 1001, new byte[] {0});
    router.submit(ScaleFrameRouter.Source.PRIMARY, 1, 1000, new byte[] {1});
  }
}
