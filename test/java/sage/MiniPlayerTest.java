package sage;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

/**
 * Tests for {@link MiniPlayer#legacyH264PushProfileApplies(boolean, boolean)},
 * the legacy-client H.264-push capability profile that lets the classic
 * (non-NG) desktop Placeshifter -- which advertises H.264 video decode but
 * never advertises MPEG2-TS push (a protocol limitation, not a real
 * capability gap) -- route onto the modern {@code dynamich264} transcode
 * path instead of the legacy mpeg4 ladder.
 */
public class MiniPlayerTest
{
  @Test
  public void playerReplyWaitUsesProgressButHonorsPlaybackAbsoluteCap()
  {
    ConnectionProgress reply = new ConnectionProgress(0L);
    PlayerTimeoutPolicy.PlaybackDeadline playback =
        PlayerTimeoutPolicy.PlaybackDeadline.startingAt(0L, 15000L, 120000L);

    assertEquals(MiniPlayer.remainingPlayerReplyWait(
        reply, 0L, 5000L, 30000L, 120000L, playback), 10000L);

    reply.markBytes(12000L, 2L);
    playback.markProgress(12000L);
    assertEquals(MiniPlayer.remainingPlayerReplyWait(
        reply, 0L, 13000L, 30000L, 120000L, playback), 14000L,
        "Partial reply bytes extend both idle windows");

    reply.markBytes(119999L, 1L);
    playback.markProgress(119999L);
    assertEquals(MiniPlayer.remainingPlayerReplyWait(
        reply, 0L, 120000L, 30000L, 120000L, playback), 0L,
        "Trickle progress cannot outlive the absolute playback cap");
  }

  private static final String OVERRIDE_PROP = "miniplayer/legacy_h264_push_override";

  @Test
  public void testNgDirectPushFrozenReportDoesNotInventPlaybackProgress() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    MiniPlayer player = new MiniPlayer()
    {
      @Override
      protected boolean isNgTrickplaySession() { return true; }
    };
    setPlayerField(player, "currState", MediaPlayer.PLAY_STATE);
    setPlayerField(player, "myRate", 1.0f);
    setPlayerField(player, "pushMode", true);
    setPlayerField(player, "detailedPushBufferStats", true);
    setPlayerField(player, "mpegSrc", new FastMpeg2Reader(new java.io.File("unused-clock-test.mpg")));
    setPlayerField(player, "directPushSeekFloor", 1427188L);
    setPlayerField(player, "directPushSentThrough", 1500000L);
    setPlayerField(player, "clientReportedMediaTime", 1429335);
    assertEquals(player.getMediaTimeMillis(), 1429335L);
    setPlayerField(player, "lastMediaTimeCacheTime", Sage.eventTime() - 60000L);
    assertEquals(player.getMediaTimeMillis(), 1429335L,
        "A renderer stuck at one frame must not advance by a minute merely because server state is PLAY");
    setPlayerField(player, "clientReportedMediaTime", 1431000);
    assertEquals(player.getMediaTimeMillis(), 1431000L);
  }

  @Test
  public void testNgDirectPushInvalidReportFallsBackToSentBoundedClock() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    MiniPlayer player = new MiniPlayer()
    {
      @Override
      protected boolean isNgTrickplaySession() { return true; }
    };
    long seekFloor = 726589L;
    long sentThrough = 1291075L;
    setPlayerField(player, "currState", MediaPlayer.PLAY_STATE);
    setPlayerField(player, "myRate", 1.0f);
    setPlayerField(player, "pushMode", true);
    setPlayerField(player, "detailedPushBufferStats", true);
    setPlayerField(player, "mpegSrc", new FastMpeg2Reader(new java.io.File("unused-clock-test.mpg")));
    setPlayerField(player, "directPushSeekFloor", seekFloor);
    setPlayerField(player, "directPushSentThrough", sentThrough);
    setPlayerField(player, "lastMediaTime", seekFloor);
    setPlayerField(player, "lastMediaTimeBase", seekFloor);
    setPlayerField(player, "pushDisplayEstimate", seekFloor);
    setPlayerField(player, "pushDisplayEstimateUpdateTime", Sage.eventTime() - 600000L);
    setPlayerField(player, "clientReportedMediaTime", 1998180);

    assertEquals(player.getMediaTimeMillis(), sentThrough,
        "An impossible IJK position must fall back to playback progress bounded by sent PTS");
    assertEquals(player.getSeekBaseMediaTimeMillis(), sentThrough,
        "The next FF/REW must use the current bounded estimate, not the old seek floor");
  }

  @Test
  public void testNgDirectPushInvalidReportClockFreezesWhilePaused() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    MiniPlayer player = new MiniPlayer()
    {
      @Override
      protected boolean isNgTrickplaySession() { return true; }
    };
    setPlayerField(player, "currState", MediaPlayer.PAUSE_STATE);
    setPlayerField(player, "myRate", 1.0f);
    setPlayerField(player, "pushMode", true);
    setPlayerField(player, "detailedPushBufferStats", true);
    setPlayerField(player, "mpegSrc", new FastMpeg2Reader(new java.io.File("unused-clock-test.mpg")));
    setPlayerField(player, "directPushSeekFloor", 726589L);
    setPlayerField(player, "directPushSentThrough", 1291075L);
    setPlayerField(player, "lastMediaTime", 900000L);
    setPlayerField(player, "lastMediaTimeBase", 900000L);
    setPlayerField(player, "pushDisplayEstimate", 900000L);
    setPlayerField(player, "pushDisplayEstimateUpdateTime", Sage.eventTime() - 600000L);
    setPlayerField(player, "clientReportedMediaTime", 1998180);

    assertEquals(player.getMediaTimeMillis(), 900000L,
        "The server fallback must not invent progress while the renderer is paused");
  }

  @Test
  public void testNgDirectPushMediaTimePollingDoesNotResetPacingClock() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    MiniPlayer player = new MiniPlayer()
    {
      @Override
      protected boolean isNgTrickplaySession() { return true; }
    };
    long now = Sage.eventTime();
    setPlayerField(player, "currState", MediaPlayer.PLAY_STATE);
    setPlayerField(player, "myRate", 1.0f);
    setPlayerField(player, "pushMode", true);
    setPlayerField(player, "detailedPushBufferStats", true);
    setPlayerField(player, "mpegSrc", new FastMpeg2Reader(new java.io.File("unused-clock-test.mpg")));
    setPlayerField(player, "directPushSeekFloor", 1000L);
    setPlayerField(player, "directPushSentThrough", 30000L);
    setPlayerField(player, "clientReportedMediaTime", 5000);
    setPlayerField(player, "pushDisplayEstimate", 5000L);
    setPlayerField(player, "pushDisplayEstimateUpdateTime", now - 10000L);

    assertEquals(player.getMediaTimeMillis(), 5000L);
    assertEquals(invokePlayerMethod(player, "refreshPushDisplayEstimate",
        new Class<?>[] {long.class}, now), 15000L,
        "Frequent subtitle polling must not pin direct-push pacing to a stale buffer reply");
  }

  @Test
  public void testEnhancedPushClockCannotAdvanceBeyondSentPts() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    MiniPlayer player = new MiniPlayer()
    {
      @Override
      protected boolean isNgTrickplaySession() { return true; }
    };
    setPlayerField(player, "currState", MediaPlayer.PLAY_STATE);
    setPlayerField(player, "myRate", 1.0f);
    setPlayerField(player, "pushMode", true);
    setPlayerField(player, "serverSideTranscoding", true);
    setPlayerField(player, "detailedPushBufferStats", true);
    setPlayerField(player, "timestampOffset", 562253L);
    setPlayerField(player, "timestampOffsetWallTime", Sage.eventTime() - 104723L);
    setPlayerField(player, "pushDisplayEstimate", 562253L);
    setPlayerField(player, "pushDisplayEstimateUpdateTime", Sage.eventTime() - 104723L);
    setPlayerField(player, "lastParserTimestamp", 561753L);
    setPlayerField(player, "directPushSentThrough", -1L);
    assertEquals(player.getSeekBaseMediaTimeMillis(), 562253L,
        "No sent timestamp must not allow the wall clock to invent 104 seconds of playback");
    setPlayerField(player, "directPushSentThrough", 564253L);
    setPlayerField(player, "pushDisplayEstimateUpdateTime", Sage.eventTime() - 2000L);
    assertEquals(player.getSeekBaseMediaTimeMillis(), 564253L,
        "Sent PTS bounds the clock even when DTS statistics stay below the seek floor");
    setPlayerField(player, "directPushSentThrough", 700000L);
    setPlayerField(player, "pushDisplayEstimateUpdateTime", Sage.eventTime() - 104723L);
    assertTrue(player.getSeekBaseMediaTimeMillis() > 660000L);
    assertEquals(MiniPlayer.boundPushDisplayEstimate(500L, 1000L, 3000L), 1000L);
    assertEquals(MiniPlayer.boundPushDisplayEstimate(2000L, 1000L, 3000L), 2000L);
    assertEquals(FFMPEGTranscoder.estimateDeliveredTranscodeStreamTime(
        500000L, 1000000L, 552000L), 276000L);
    assertEquals(FFMPEGTranscoder.estimateDeliveredTranscodeStreamTime(
        1100000L, 1000000L, 552000L), 552000L);
  }

  @Test
  public void testValidatedClientPositionAdvancesFrozenPushFrontier() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    MiniPlayer player = new MiniPlayer()
    {
      @Override
      protected boolean isNgTrickplaySession() { return true; }
    };
    long now = Sage.eventTime();
    setPlayerField(player, "currState", MediaPlayer.PLAY_STATE);
    setPlayerField(player, "pushMode", true);
    setPlayerField(player, "serverSideTranscoding", true);
    setPlayerField(player, "detailedPushBufferStats", true);
    setPlayerField(player, "timestampOffset", 90000L);
    setPlayerField(player, "timestampOffsetWallTime", now - 403778L);
    setPlayerField(player, "pushDisplayEstimate", 90000L);
    setPlayerField(player, "pushDisplayEstimateUpdateTime", now - 403778L);
    setPlayerField(player, "directPushSentThrough", 89500L);
    setPlayerField(player, "clientReportedMediaTime", 402290);
    setPlayerField(player, "clientReportedPlayState", MediaPlayer.PLAY_STATE);

    invokePlayerMethod(player, "noteNgClientDisplayPosition", new Class<?>[] {long.class}, now);

    assertTrue((Boolean)getPlayerField(player, "clientPositionEpochTrusted"));
    assertEquals(getPlayerField(player, "directPushSentThrough"), 492290L);
    assertEquals(player.getSeekBaseMediaTimeMillis(), 492290L,
        "A current-epoch renderer position proves those bytes were delivered even when parser DTS is frozen");
  }

  @Test
  public void testStalePreSeekClientPositionCannotAdvancePushFrontier() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    MiniPlayer player = new MiniPlayer()
    {
      @Override
      protected boolean isNgTrickplaySession() { return true; }
    };
    long now = Sage.eventTime();
    setPlayerField(player, "currState", MediaPlayer.PLAY_STATE);
    setPlayerField(player, "pushMode", true);
    setPlayerField(player, "serverSideTranscoding", true);
    setPlayerField(player, "detailedPushBufferStats", true);
    setPlayerField(player, "timestampOffset", 120000L);
    setPlayerField(player, "timestampOffsetWallTime", now - 3000L);
    setPlayerField(player, "pushDisplayEstimate", 120000L);
    setPlayerField(player, "pushDisplayEstimateUpdateTime", now - 3000L);
    setPlayerField(player, "directPushSentThrough", -1L);
    setPlayerField(player, "clientReportedMediaTime", 405296);
    setPlayerField(player, "clientReportedPlayState", MediaPlayer.PLAY_STATE);

    invokePlayerMethod(player, "noteNgClientDisplayPosition", new Class<?>[] {long.class}, now);

    assertFalse((Boolean)getPlayerField(player, "clientPositionEpochTrusted"));
    assertEquals(getPlayerField(player, "directPushSentThrough"), -1L);
    assertEquals(player.getSeekBaseMediaTimeMillis(), 120000L);
  }

  @Test
  public void testMedia3EnhanceSurfaceModeOverridesEarlierGenericRemux()
  {
    String matroskaCopy = "container=matroska;videocodec=COPY;audiocodec=COPY";
    assertEquals(MiniPlayer.resolvePushEnhanceMode("mpeg2tsremux", matroskaCopy), matroskaCopy,
        "The selected surface contract must win even when a generic REMUX decision already set transcoded=true");
    assertEquals(MiniPlayer.resolvePushEnhanceMode("mpeg2tsremux", null), "mpeg2tsremux");
  }

  @Test
  public void testEnhancedPushClockFreezesWhilePaused() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    MiniPlayer player = new MiniPlayer()
    {
      @Override
      protected boolean isNgTrickplaySession() { return true; }
    };
    setPlayerField(player, "currState", MediaPlayer.PAUSE_STATE);
    setPlayerField(player, "pushMode", true);
    setPlayerField(player, "serverSideTranscoding", true);
    setPlayerField(player, "detailedPushBufferStats", true);
    setPlayerField(player, "timestampOffset", 562253L);
    setPlayerField(player, "pushDisplayEstimate", 620000L);
    setPlayerField(player, "pushDisplayEstimateUpdateTime", Sage.eventTime() - 60000L);
    setPlayerField(player, "directPushSentThrough", 700000L);
    assertEquals(player.getMediaTimeMillis(), 620000L);
    assertEquals(player.getSeekBaseMediaTimeMillis(), 620000L);
  }

  @Test
  public void testPushDisplayRefreshDoesNotAcquirePlayerMonitor() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    MiniPlayer player = new MiniPlayer();
    setPlayerField(player, "currState", MediaPlayer.PLAY_STATE);
    setPlayerField(player, "timestampOffset", 1000L);
    setPlayerField(player, "pushDisplayEstimate", 1000L);
    setPlayerField(player, "pushDisplayEstimateUpdateTime", Sage.eventTime());
    setPlayerField(player, "directPushSentThrough", 2000L);

    final Throwable[] failure = new Throwable[1];
    Thread refreshThread;
    synchronized (player)
    {
      refreshThread = new Thread(() -> {
        try
        {
          java.lang.reflect.Method method =
              MiniPlayer.class.getDeclaredMethod("refreshPushDisplayEstimate", long.class);
          method.setAccessible(true);
          method.invoke(player, Sage.eventTime());
        }
        catch (Throwable t)
        {
          failure[0] = t;
        }
      });
      refreshThread.start();
      refreshThread.join(1000);
      assertFalse(refreshThread.isAlive(),
          "The pusher must update its display estimate without acquiring the MiniPlayer monitor");
    }
    if (failure[0] != null)
      throw new AssertionError("Display estimate refresh failed", failure[0]);
  }

  @Test
  public void testPullClockUsesSourceRelativeClientPositionAfterResumeAndSeek() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    final long[] reported = {474839L};
    MiniPlayer player = new MiniPlayer()
    {
      @Override
      protected long getMediaTimeMillis0()
      {
        return reported[0];
      }
    };
    setPlayerField(player, "currState", MediaPlayer.PLAY_STATE);
    setPlayerField(player, "myRate", 1.0f);
    setPlayerField(player, "pushMode", false);
    setPlayerField(player, "timeGuessMillis", 474839L);
    setPlayerField(player, "guessTimestamp", Sage.eventTime() - 2000L);
    assertEquals(player.getMediaTimeMillis(), 474839L);
    assertEquals(player.getSeekBaseMediaTimeMillis() + 30000L, 504839L);
    reported[0] = 508835L;
    assertEquals(player.getMediaTimeMillis(), 508835L);
    assertEquals(player.getSeekBaseMediaTimeMillis() + 120000L, 628835L,
        "A resumed pull clock must not collapse to elapsed time since stream restart");
    reported[0] = 3996L;
    assertEquals(player.getMediaTimeMillis(), 3996L,
        "The server cannot reconstruct an omitted source offset from an untagged client report");
  }

  @Test
  public void testNgTrickplayPoliciesDoNotChangeLegacySettings() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    String[][] policies = {{"videoframe", "seek_target_coalesce"},
        {"videoframe", "live_skip_edge_clamp"}, {"miniplayer", "push_seek_dvr_clamp"},
        {"miniplayer", "push_inplace_seek"}};
    for (String[] policy : policies)
    {
      String legacyKey = policy[0] + "/" + policy[1];
      String ngKey = policy[0] + "/ng_" + policy[1];
      String oldLegacy = Sage.get(legacyKey, null);
      String oldNg = Sage.get(ngKey, null);
      boolean ngDefault = !policy[1].equals("push_inplace_seek");
      try
      {
        Sage.remove(legacyKey);
        Sage.remove(ngKey);
        assertFalse(MiniPlayer.trickplayPolicyEnabled(policy[0], policy[1], false, ngDefault));
        assertEquals(MiniPlayer.trickplayPolicyEnabled(policy[0], policy[1], true, ngDefault), ngDefault);
        Sage.put(ngKey, "true");
        assertFalse(MiniPlayer.trickplayPolicyEnabled(policy[0], policy[1], false, ngDefault));
        Sage.put(legacyKey, "true");
        Sage.put(ngKey, "false");
        assertTrue(MiniPlayer.trickplayPolicyEnabled(policy[0], policy[1], false, ngDefault));
        assertFalse(MiniPlayer.trickplayPolicyEnabled(policy[0], policy[1], true, ngDefault));
      }
      finally
      {
        if (oldLegacy == null) Sage.remove(legacyKey); else Sage.put(legacyKey, oldLegacy);
        if (oldNg == null) Sage.remove(ngKey); else Sage.put(ngKey, oldNg);
      }
    }
  }

  @Test
  public void testUnsupportedRatesDoNotSeek() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    MiniPlayer player = new MiniPlayer()
    {
      @Override
      public long seek(long target)
      {
        throw new AssertionError("Unsupported rate request must not reposition playback");
      }
    };
    assertEquals(player.setPlaybackRate(0.5f), 1.0f);
    assertEquals(player.setPlaybackRate(2.0f), 1.0f);
    assertEquals(player.setPlaybackRate(-2.0f), 1.0f);
    assertEquals(player.setPlaybackRate(1.0f), 1.0f);
    assertFalse(player.isNgTrickplaySession(),
        "legacy clients use the same unsupported-rate contract instead of synthetic skips");
    setPlayerField(player, "pushMode", true);
    setPlayerField(player, "serverSideTranscoding", true);
    assertEquals(player.setPlaybackRate(0.5f), 1.0f);
    assertEquals(player.setPlaybackRate(2.0f), 1.0f);
  }

  @Test
  public void testDirectPushPositionMustBelongToSentSeekWindow()
  {
    assertFalse(MiniPlayer.directPushPositionInWindow(54694L, 0L, 14000L));
    assertFalse(MiniPlayer.directPushPositionInWindow(45852L, 0L, 14000L));
    assertTrue(MiniPlayer.directPushPositionInWindow(907L, 0L, 14000L));
    assertFalse(MiniPlayer.directPushPositionInWindow(907L, 24762L, 40000L));
    assertTrue(MiniPlayer.directPushPositionInWindow(25164L, 24762L, 40000L));
    assertFalse(MiniPlayer.directPushPositionInWindow(24762L, 24762L, 0L));
    assertFalse(MiniPlayer.directPushPositionInWindow(302097L, 302097L, -1L));
    assertTrue(MiniPlayer.directPushPositionInWindow(303209L, 302097L, 310000L));
    assertFalse(MiniPlayer.directPushPositionInWindow(32071L, 302097L, 310000L));
  }

  @Test
  public void testDirectPushParserTimestampMustRemainContiguousWithinSeekEpoch()
  {
    assertTrue(MiniPlayer.directPushParserTimestampInWindow(489807L, 489807L, -1L));
    assertTrue(MiniPlayer.directPushParserTimestampInWindow(520000L, 489807L, 500000L));
    assertFalse(MiniPlayer.directPushParserTimestampInWindow(1324726L, 489807L, 500000L),
        "A stale live-edge PTS must not advance a seven-minute seek to twenty-two minutes");
    assertFalse(MiniPlayer.directPushParserTimestampInWindow(79544878L, 107658L, 109722L),
        "A false rollover timestamp must not poison the sent frontier");
    assertFalse(MiniPlayer.directPushParserTimestampInWindow(100000L, 489807L, 500000L),
        "A pre-seek timestamp must not cross into the new epoch");
  }

  @Test
  public void testPartyPushEpochMapsThePairedMediaTimestamp()
  {
    assertEquals(MiniPlayer.mapPartyPushMediaTimestamp(
        726400L, 900L, 1100L), 726600L);
    assertEquals(MiniPlayer.mapPartyPushMediaTimestamp(
        120000L, 95400L, 95400L), 120000L);
    long wrapMs = FastMpeg2Reader.MAX_PTS / 90;
    assertEquals(MiniPlayer.mapPartyPushMediaTimestamp(
        500000L, wrapMs - 100L, 50L), 500150L);
  }

  @Test
  public void testPartyPushEpochCommandUsesVersionedPreByteFraming()
  {
    java.nio.ByteBuffer command = java.nio.ByteBuffer.allocate(32);
    MiniPlayer.encodePartyPushEpochCommand(
        command, 7L, 726400L, 95400L);
    command.flip();

    int header = command.getInt();
    assertEquals(header >>> 24, MiniPlayer.MEDIACMD_PARTY_PUSH_EPOCH);
    assertEquals(header & 0xFFFFFF, 28);
    assertEquals(command.getInt(), 1);
    assertEquals(command.getLong(), 7L);
    assertEquals(command.getLong(), 726400L);
    assertEquals(command.getLong(), 95400L);
    assertFalse(command.hasRemaining());
  }

  @Test
  public void testNgSurfaceSelectionUsesBackendSpecificConstraints()
  {
    final java.util.Map<String, String[]> props =
        new java.util.HashMap<String, String[]>();
    props.put("android_ijk", new String[] {
        "native", "60", "push,pull", "HEVC", "AC3", "MATROSKA",
        "all", "server", "", "0", "0", "0", "6", "none"
    });
    props.put("android_media3", new String[] {
        "native", "100", "push,pull", "HEVC", "AC3", "MATROSKA",
        "all", "server", "", "0", "0", "0", "6", "none"
    });
    sage.client.PlaybackSurfaceSet surfaces = sage.client.PlaybackSurfaceSet.build(
        "android_ijk,android_media3", props::get);
    sage.client.ClientConstraints defaultIjk = sage.client.ClientConstraints.parse(
        "ijkplayer", "HEVC;decoder=hw;maxW=1920;maxH=1080;maxFps=60",
        "AC3", "MATROSKA");
    sage.client.ClientConstraints exo = sage.client.ClientConstraints.parse(
        "exoplayer", "HEVC;decoder=hw;maxW=7680;maxH=4320;maxFps=60",
        "AC3", "MATROSKA");
    sage.client.ClientConstraints ijk = sage.client.ClientConstraints.parse(
        "ijkplayer", "HEVC;decoder=hw;maxW=1920;maxH=1080;maxFps=60",
        "AC3", "MATROSKA");
    sage.media.format.VideoFormat video = new sage.media.format.VideoFormat();
    video.setFormatName(sage.media.format.MediaFormat.HEVC);
    video.setWidth(7680);
    video.setHeight(4320);
    video.setFps(60f);
    sage.media.format.ContainerFormat container = new sage.media.format.ContainerFormat();
    container.setFormatName("MATROSKA");
    container.setStreamFormats(new sage.media.format.BitstreamFormat[] { video });
    MiniClientSageRenderer renderer = mock(MiniClientSageRenderer.class);
    doReturn(exo).when(renderer).getClientConstraintsExo();
    doReturn(ijk).when(renderer).getClientConstraintsIjk();

    java.util.List<sage.client.PlaybackDecisionEngine.SurfaceDecision> ranked =
        MiniPlayer.evaluateNgSurfaces(renderer, surfaces,
            "MATROSKA", "HEVC", "AC3", 7680, 4320, 0, 0,
            false, container, "eng", defaultIjk, false, false);

    assertEquals(ranked.get(0).surface.getId(), "android_media3");
    assertEquals(ranked.get(0).decision.decision,
        sage.client.PlaybackDecisionEngine.Decision.DIRECT_PLAY);
    assertEquals(ranked.get(1).surface.getId(), "android_ijk");
    assertEquals(ranked.get(1).decision.decision,
        sage.client.PlaybackDecisionEngine.Decision.TRANSCODE);
  }

  @Test
  public void testNgSurfaceSelectionUsesNegotiatedPartyPushOrigin()
  {
    sage.client.PlaybackSurfaceSet surfaces = sage.client.PlaybackSurfaceSet.build(
        "android_push", id -> new String[] {
            "native", "1", "push", "MPEG2-VIDEO,H264", "AC3,AAC",
            "MPEG2-PS;push=true;pull=false,MPEG2-TS;push=true;pull=false"
        });
    MiniClientSageRenderer renderer = mock(MiniClientSageRenderer.class);

    assertTrue(MiniPlayer.evaluateNgSurfaces(renderer, surfaces,
        "MPEG2-PS", "MPEG2-VIDEO", "AC3", 1920, 1080, 8000, 20000,
        false, null, null, null, true, false).isEmpty(),
        "Party direct push must remain excluded without the origin capability");
    assertEquals(MiniPlayer.evaluateNgSurfaces(renderer, surfaces,
        "MPEG2-PS", "MPEG2-VIDEO", "AC3", 1920, 1080, 8000, 20000,
        false, null, null, null, true, true).get(0).chosenDeliveryMode, "push");
  }

  @Test
  public void testPartyOpenBindingPrecedesOpenAndRequiresAck() throws Exception
  {
    java.util.concurrent.ExecutorService worker =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    try (SocketPair pair = new SocketPair())
    {
      MiniClientSageRenderer renderer = partyRenderer();
      renderer.installPartyOpenBinding(new sage.party.PartyPlayerAdapter.PreparationBinding(
          "party", "prep", "binding", 42));
      MiniPlayer player = new MiniPlayer();
      setPlayerField(player, "mcsr", renderer);
      setPlayerField(player, "clientSocket", pair.accepted);
      pair.bridge.setSoTimeout(5000);
      java.io.DataInputStream input =
          new java.io.DataInputStream(pair.bridge.getInputStream());
      java.io.DataOutputStream output =
          new java.io.DataOutputStream(pair.bridge.getOutputStream());

      java.util.concurrent.Future<Boolean> opened =
          worker.submit(() -> player.openURL0("push:test"));
      int bindingHeader = input.readInt();
      assertEquals(bindingHeader >>> 24, MiniPlayer.MEDIACMD_PARTY_OPEN_BINDING);
      input.readFully(new byte[bindingHeader & 0xFFFFFF]);
      assertFalse(opened.isDone(), "OPEN must wait for command 41 acceptance");
      output.writeInt(1);

      int openHeader = input.readInt();
      assertEquals(openHeader >>> 24, MiniPlayer.MEDIACMD_OPENURL);
      input.readFully(new byte[openHeader & 0xFFFFFF]);
      output.writeInt(1);
      assertTrue(opened.get(5, java.util.concurrent.TimeUnit.SECONDS));
    }
    finally
    {
      worker.shutdownNow();
    }
  }

  @Test
  public void testPartyPushEpochAckPrecedesBytesForEveryGeneration() throws Exception
  {
    java.util.concurrent.ExecutorService worker =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    try (SocketPair pair = new SocketPair())
    {
      MiniClientSageRenderer renderer = mock(MiniClientSageRenderer.class);
      doReturn(true).when(renderer).partyDirectPushEpochOriginActive();
      MiniPlayer player = new MiniPlayer();
      FastMpeg2Reader reader = new FastMpeg2Reader(
          new java.io.File("unused-party-origin-test.mpg"));
      reader.parsedPES.pts = 95400L * 90L;
      setObjectField(reader, FastMpeg2Reader.class, "firstPTS", 0L);
      setPlayerField(player, "mcsr", renderer);
      setPlayerField(player, "mpegSrc", reader);
      setPlayerField(player, "clientSocket", pair.accepted);
      setPlayerField(player, "clientInStream", newFastPusherReply(player, pair.accepted));
      pair.bridge.setSoTimeout(5000);
      java.io.DataInputStream input =
          new java.io.DataInputStream(pair.bridge.getInputStream());
      java.io.DataOutputStream output =
          new java.io.DataOutputStream(pair.bridge.getOutputStream());

      assertEpochBeforeBytes(player, pair, input, output, worker, 0L, true);
      setPlayerField(player, "seekEpoch", 7);
      assertEpochBeforeBytes(player, pair, input, output, worker, 7L, false);
      assertEquals(getPlayerField(player, "partyPushEpochAnnounced"), 0L,
          "A rejected command 42 must not admit the new seek generation");
      assertEpochBeforeBytes(player, pair, input, output, worker, 7L, true);
      player.flushPush0();
      assertEpochBeforeBytes(player, pair, input, output, worker, 8L, true);

      doReturn(false).when(renderer).partyDirectPushEpochOriginActive();
      setPlayerField(player, "seekEpoch", 9);
      assertTrue((Boolean)invokePlayerMethod(
          player, "ensurePartyPushEpochOrigin0", new Class<?>[0]));
      pair.bridge.setSoTimeout(100);
      assertThrows(java.net.SocketTimeoutException.class, input::readByte);
    }
    finally
    {
      worker.shutdownNow();
    }
  }

  @Test
  public void testNgDirectPushThrottlesAtConfiguredMediaLead()
  {
    assertFalse(MiniPlayer.directPushShouldThrottle(-1L, 65246L, 15000L),
        "Pacing must wait for the first post-seek parser timestamp");
    assertFalse(MiniPlayer.directPushShouldThrottle(80245L, 65246L, 15000L));
    assertTrue(MiniPlayer.directPushShouldThrottle(80246L, 65246L, 15000L));
    assertFalse(MiniPlayer.directPushShouldThrottle(1779000L, 1770000L, 0L),
        "A non-positive lead explicitly disables pacing");
  }

  @Test
  public void testNgDirectPushSeekBootstrapStopsRunawayBeforeTimestampFrontier()
  {
    assertFalse(MiniPlayer.directPushBootstrapShouldThrottle(
        636114L, 636114L, 4L * 1024 * 1024 - 1, 4L * 1024 * 1024));
    assertTrue(MiniPlayer.directPushBootstrapShouldThrottle(
        636114L, 636114L, 4L * 1024 * 1024, 4L * 1024 * 1024),
        "A seek epoch without advancing timestamps must not race to the live edge");
    assertFalse(MiniPlayer.directPushBootstrapShouldThrottle(
        636115L, 636114L, 4L * 1024 * 1024, 4L * 1024 * 1024),
        "The normal media-time lead limiter takes over once the frontier advances");
  }

  @Test
  public void testNgDirectPushSeekEpochStartsAtRequestedPosition() throws Throwable
  {
    MiniPlayer player = new MiniPlayer()
    {
      @Override
      protected boolean isNgTrickplaySession() { return true; }
    };

    invokePlayerMethod(player, "resetDirectPushSeekEpoch",
        new Class<?>[] { long.class }, 636114L);

    assertEquals(getPlayerField(player, "directPushSeekFloor"), 636114L);
    assertEquals(getPlayerField(player, "directPushSentThrough"), 636114L);
    assertEquals(getPlayerField(player, "directPushSeekBootstrapBytes"), 0L);
    assertEquals(getPlayerField(player, "lastMediaTimeBase"), 636114L);
    assertEquals(getPlayerField(player, "clientReportedMediaTime"), 636114);
  }

  @Test
  public void testNgDirectPushPacingScopeExcludesLegacyAndTranscodedSessions() throws Throwable
  {
    MiniPlayer ngPlayer = new MiniPlayer()
    {
      @Override
      protected boolean isNgTrickplaySession() { return true; }
    };
    setPlayerField(ngPlayer, "detailedPushBufferStats", true);
    setPlayerField(ngPlayer, "pushMode", true);
    setPlayerField(ngPlayer, "mpegSrc",
        new FastMpeg2Reader(new java.io.File("unused-pacing-test.mpg")));
    assertTrue((Boolean) invokePlayerMethod(
        ngPlayer, "isNgDirectPushPacingSession", new Class<?>[0]));

    setPlayerField(ngPlayer, "serverSideTranscoding", true);
    assertFalse((Boolean) invokePlayerMethod(
        ngPlayer, "isNgDirectPushPacingSession", new Class<?>[0]));

    MiniPlayer legacyPlayer = new MiniPlayer();
    setPlayerField(legacyPlayer, "detailedPushBufferStats", true);
    setPlayerField(legacyPlayer, "pushMode", true);
    setPlayerField(legacyPlayer, "mpegSrc",
        new FastMpeg2Reader(new java.io.File("unused-pacing-test.mpg")));
    assertFalse((Boolean) invokePlayerMethod(
        legacyPlayer, "isNgDirectPushPacingSession", new Class<?>[0]));
  }

  @Test
  public void testNgDirectPushMuxTrailerUsesSourceRelativePtsAnchor()
  {
    assertEquals(MiniPlayer.pushMuxTimestampMillis(428240L, 456L, true), 428240L,
        "The first post-flush NG push must identify its absolute recording-time origin");
    assertEquals(MiniPlayer.pushMuxTimestampMillis(0L, 456L, true), 456L,
        "DTS remains the fallback until a post-flush PTS is parsed");
    assertEquals(MiniPlayer.pushMuxTimestampMillis(428240L, 456L, false), 456L,
        "Legacy push clients retain the established DTS trailer");
  }

  @Test
  public void testDirectPushMediaClockAcceptsPtsBoundaryWithoutDtsStats() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    MiniPlayer player = new MiniPlayer();
    setPlayerField(player, "detailedPushBufferStats", true);
    setPlayerField(player, "pushMode", true);
    setPlayerField(player, "mpegSrc", new FastMpeg2Reader(new java.io.File("unused-clock-test.mpg")));
    setPlayerField(player, "currState", MediaPlayer.PAUSE_STATE);
    setPlayerField(player, "myRate", 1.0f);
    setPlayerField(player, "directPushSeekFloor", 302097L);
    setPlayerField(player, "directPushSentThrough", 310000L);
    setPlayerField(player, "lastParserTimestamp", 0L);
    setPlayerField(player, "clientReportedMediaTime", 303209);
    assertEquals(player.getMediaTimeMillis(), 303209L,
        "A zero DTS statistics counter must not freeze a valid direct-push position");

    setPlayerField(player, "clientReportedMediaTime", 32071);
    assertEquals(player.getMediaTimeMillis(), 303209L,
        "A pre-seek report must not move the media clock backwards");
    setPlayerField(player, "clientReportedMediaTime", 546000);
    assertEquals(player.getMediaTimeMillis(), 303209L,
        "A report ahead of sent data must not move the media clock forwards");

    setPlayerField(player, "directPushSentThrough", 320000L);
    setPlayerField(player, "clientReportedMediaTime", 315000);
    assertEquals(player.getMediaTimeMillis(), 315000L,
        "The clock must resume as valid client reports and payload boundaries advance");
  }

  private static void setPlayerField(MiniPlayer player, String name, Object value)
      throws ReflectiveOperationException
  {
    java.lang.reflect.Field field = MiniPlayer.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(player, value);
  }

  private static Object getPlayerField(MiniPlayer player, String name)
      throws ReflectiveOperationException
  {
    java.lang.reflect.Field field = MiniPlayer.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(player);
  }

  private static Object invokePlayerMethod(MiniPlayer player, String name,
      Class<?>[] parameterTypes, Object... args) throws ReflectiveOperationException
  {
    java.lang.reflect.Method method = MiniPlayer.class.getDeclaredMethod(name, parameterTypes);
    method.setAccessible(true);
    return method.invoke(player, args);
  }

  private static final class SocketPair implements AutoCloseable
  {
    final java.net.Socket bridge;
    final java.nio.channels.SocketChannel accepted;

    SocketPair() throws Exception
    {
      try (java.nio.channels.ServerSocketChannel listener =
          java.nio.channels.ServerSocketChannel.open())
      {
        listener.bind(new java.net.InetSocketAddress(
            java.net.InetAddress.getLoopbackAddress(), 0));
        bridge = new java.net.Socket();
        bridge.connect(listener.getLocalAddress());
        accepted = listener.accept();
      }
    }

    @Override public void close() throws Exception
    {
      bridge.close();
      accepted.close();
    }
  }

  private static MiniClientSageRenderer partyRenderer() throws Exception
  {
    MiniClientSageRenderer renderer = mock(
        MiniClientSageRenderer.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    setObjectField(renderer, MiniClientSageRenderer.class,
        "partySurfaceLock", new Object());
    setObjectField(renderer, MiniClientSageRenderer.class,
        "partyOpenBindingLock", new Object());
    setObjectField(renderer, MiniClientSageRenderer.class,
        "partyHttpBindings",
        new java.util.LinkedHashMap<String, MiniClientSageRenderer.PartyOpenBinding>());
    doReturn(sage.client.PlaybackSurfaceSet.empty())
        .when(renderer).getPlaybackSurfaces();
    doReturn("").when(renderer).getCurrentSurfaceId();
    return renderer;
  }

  private static Object newFastPusherReply(
      MiniPlayer player, java.nio.channels.SocketChannel channel) throws Exception
  {
    Class<?> type = Class.forName("sage.MiniPlayer$FastPusherReply");
    java.lang.reflect.Constructor<?> constructor =
        type.getDeclaredConstructor(MiniPlayer.class, java.nio.channels.SocketChannel.class);
    constructor.setAccessible(true);
    return constructor.newInstance(player, channel);
  }

  private static void assertEpochBeforeBytes(MiniPlayer player, SocketPair pair,
      java.io.DataInputStream input, java.io.DataOutputStream output,
      java.util.concurrent.ExecutorService worker, long epoch, boolean accept)
      throws Exception
  {
    pair.bridge.setSoTimeout(5000);
    java.util.concurrent.Future<Boolean> announced = worker.submit(() -> {
      boolean ready = (Boolean)invokePlayerMethod(
          player, "ensurePartyPushEpochOrigin0", new Class<?>[0]);
      if (ready)
        pair.accepted.write(java.nio.ByteBuffer.wrap(new byte[] {(byte)0x5A}));
      return ready;
    });
    int header = input.readInt();
    assertEquals(header >>> 24, MiniPlayer.MEDIACMD_PARTY_PUSH_EPOCH);
    assertEquals(header & 0xFFFFFF, 28);
    assertEquals(input.readInt(), 1);
    assertEquals(input.readLong(), epoch);
    assertEquals(input.readLong(), 95400L);
    assertEquals(input.readLong(), 95400L);
    pair.bridge.setSoTimeout(100);
    assertThrows(java.net.SocketTimeoutException.class, input::readByte);
    output.writeInt(accept ? 1 : 0);
    assertEquals(announced.get(5, java.util.concurrent.TimeUnit.SECONDS), accept);
    if (accept)
    {
      pair.bridge.setSoTimeout(5000);
      assertEquals(input.readUnsignedByte(), 0x5A);
    }
  }

  private static void setObjectField(
      Object target, Class<?> type, String name, Object value) throws Exception
  {
    java.lang.reflect.Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  @Test
  public void testPausedPushSeekUsesStreamRelativeMilliseconds()
  {
    assertEquals(MiniPlayer.pausedPushSeekTime(199023465L, 71712L, false), 2210630L);
    assertEquals(MiniPlayer.pausedPushSeekTime(202897335L, 71712L, false), 2253673L);
    assertEquals(MiniPlayer.pausedPushSeekTime(71712L, 71712L, false), 55L);
    assertEquals(MiniPlayer.pausedPushSeekTime(0L, 9000L, false), 0L);
  }

  @Test
  public void testPausedPushSeekPreservesHardwareExtenderPts()
  {
    assertEquals(MiniPlayer.pausedPushSeekTime(199023465L, 71712L, true), 199028465L);
  }

  @Test
  public void testPausedPushSeekHandlesPtsRollover()
  {
    long first = FastMpeg2Reader.MAX_PTS - 90000L;
    assertEquals(MiniPlayer.pausedPushSeekTime(90000L, first, false), 2055L);
  }

  @Test
  public void testPushTranscodePlanRequiresCurrentCapturedSourceAndSafeLiveSeam()
  {
    assertTrue(MiniPlayer.PushTranscodePlan.seamInputsAreSafe(12, 12, true, true));
    assertFalse(MiniPlayer.PushTranscodePlan.seamInputsAreSafe(0, 0, true, true),
        "A plan without a captured source must never authorize teardown");
    assertFalse(MiniPlayer.PushTranscodePlan.seamInputsAreSafe(12, 13, true, true),
        "A stale plan from another tune must be rejected");
    assertFalse(MiniPlayer.PushTranscodePlan.seamInputsAreSafe(12, 12, false, true),
        "Scheduled-recording playback must not cross the EPG boundary");
    assertFalse(MiniPlayer.PushTranscodePlan.seamInputsAreSafe(12, 12, true, false),
        "A non-contiguous or different-tuner successor must fail closed");
  }

  @AfterMethod
  public void resetProperty() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.put(OVERRIDE_PROP, "false");
  }

  /**
   * Classic desktop Placeshifter fingerprint: H.264 advertised, no
   * MPEG2-TS push, has a MOUSE (not a hardware extender), no NG handshake.
   * The override now DEFAULTS OFF (root-caused: the classic Placeshifter's
   * push receiver is a bespoke PS-only path that cannot demux MPEG2-TS --
   * see legacyH264PushProfileApplies() javadoc), so with no property set
   * these clients must stay on the legacy mpeg4 ladder.
   */
  @Test
  public void testDefaultsOffForLegacyDesktopPlaceshifter() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.remove(OVERRIDE_PROP); // simulate a fresh install: property truly unset
    boolean ngSession = false;
    boolean mediaExtender = false; // has MOUSE -> not an extender
    assertFalse(MiniPlayer.legacyH264PushProfileApplies(ngSession, mediaExtender),
        "Legacy desktop Placeshifter must NOT get the H.264-push override by default "
            + "(client's push receiver can't demux MPEG2-TS -- stall root-caused this session)");
  }

  /**
   * Explicit opt-in: setting miniplayer/legacy_h264_push_override=true still
   * routes a qualifying legacy desktop Placeshifter onto H.264-push, for a
   * future client build verified to handle TS push, or manual testing.
   */
  @Test
  public void testExplicitOptInAppliesForLegacyDesktopPlaceshifter() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.put(OVERRIDE_PROP, "true");
    boolean ngSession = false;
    boolean mediaExtender = false; // has MOUSE -> not an extender
    assertTrue(MiniPlayer.legacyH264PushProfileApplies(ngSession, mediaExtender),
        "Explicit opt-in (override=true) should still grant the H.264-push profile "
            + "to a legacy desktop Placeshifter (non-NG, non-extender)");
  }

  /**
   * HD100/HD200 hardware extender fingerprint: no MOUSE -> isMediaExtender()
   * true. The profile must NOT apply -- these clients stay on the legacy
   * mpeg4 path (with its LAN-aware ceiling) unchanged.
   */
  @Test
  public void testExcludesMediaExtender() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.put(OVERRIDE_PROP, "true"); // even opted in, extenders must be excluded
    boolean ngSession = false;
    boolean mediaExtender = true; // HD100/HD200-style extender
    assertFalse(MiniPlayer.legacyH264PushProfileApplies(ngSession, mediaExtender),
        "Hardware media extenders (HD100/HD200) must never get the legacy H.264-push override");
  }

  /**
   * NG client (PWA/Android) fingerprint: isNgCapableSession() true. NG
   * clients already self-advertise their real capabilities correctly, so
   * the profile must NOT apply and must leave their advertised capabilities
   * completely untouched.
   */
  @Test
  public void testExcludesNgSession() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.put(OVERRIDE_PROP, "true"); // even opted in, NG sessions must be excluded
    boolean ngSession = true;
    boolean mediaExtender = false;
    assertFalse(MiniPlayer.legacyH264PushProfileApplies(ngSession, mediaExtender),
        "NG-capable sessions must never be touched by the legacy H.264-push override");
  }

  /**
   * Kill-switch: setting miniplayer/legacy_h264_push_override=false must
   * disable the override live, without a rebuild, even for an otherwise
   * qualifying legacy desktop Placeshifter.
   */
  @Test
  public void testKillSwitchDisablesOverride() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    Sage.put(OVERRIDE_PROP, "false");
    boolean ngSession = false;
    boolean mediaExtender = false;
    assertFalse(MiniPlayer.legacyH264PushProfileApplies(ngSession, mediaExtender),
        "Kill-switch=false must disable the override even for a qualifying legacy client");
  }

  /**
   * Tests for {@link MiniPlayer#buildEffDeliveryToken(String, String)}, the
   * {@code CAP_EFFECTIVE_DELIVERY} wire-string builder (Protocol 2.1).
   * DIRECT_PLAY's bare "pull" must become "pull:direct" (so the bridge
   * routes to /msproxy?mode=direct instead of /rawmedia), while
   * pull-xcode/push/hls and the null/empty no-emission case are unchanged.
   */
  @Test
  public void testDirectPlayPullEmitsPullDirect()
  {
    assertEquals(MiniPlayer.buildEffDeliveryToken("pull", null), "pull:direct",
        "DIRECT_PLAY's bare pull delivery must be labeled pull:direct so the bridge "
            + "routes to /msproxy?mode=direct (proper seeking) instead of /rawmedia");
  }

  @Test
  public void testDirectPlayPullEmitsPullDirectWithEmptyXcodeMode()
  {
    assertEquals(MiniPlayer.buildEffDeliveryToken("pull", ""), "pull:direct",
        "An empty (non-null) xcode mode must be treated the same as null for the bare-pull case");
  }

  @Test
  public void testPullXcodeModeUnchanged()
  {
    assertEquals(MiniPlayer.buildEffDeliveryToken("pull-xcode", "mpeg2tsremux"), "pull-xcode:mpeg2tsremux",
        "pull-xcode delivery must still emit pull-xcode:<mode> unchanged");
    assertEquals(MiniPlayer.buildEffDeliveryToken("pull-xcode", "browserhd_remux"), "pull-xcode:browserhd_remux");
    assertEquals(MiniPlayer.buildEffDeliveryToken("pull-xcode", "browserhd_copyv"), "pull-xcode:browserhd_copyv");
    assertEquals(MiniPlayer.buildEffDeliveryToken("pull-xcode", "audioonly"), "pull-xcode:audioonly");
    assertEquals(MiniPlayer.buildEffDeliveryToken("pull-xcode", "browserhd"), "pull-xcode:browserhd");
    assertEquals(MiniPlayer.buildEffDeliveryToken("pull-xcode", "dynamich264"), "pull-xcode:dynamich264");
  }

  @Test
  public void testPushDeliveryUnchanged()
  {
    assertEquals(MiniPlayer.buildEffDeliveryToken("push", null), "push",
        "push delivery (no xcode mode) must be emitted bare, unchanged");
  }

  @Test
  public void testHlsDeliveryUnchanged()
  {
    assertEquals(MiniPlayer.buildEffDeliveryToken("hls", null), "hls",
        "hls delivery (no xcode mode) must be emitted bare, unchanged");
  }

  @Test
  public void testPartyCmafDeliveryUnchanged()
  {
    assertEquals(MiniPlayer.buildEffDeliveryToken("party-cmaf", null), "party-cmaf",
        "Party CMAF must remain an exact dedicated bridge route token");
    assertEquals(MiniPlayer.buildEffDeliveryToken("party-cmaf", "unexpected-mode",
        sage.enhance.EnhancementTier.DEINTERLACE_ONLY, true), "party-cmaf",
        "Party CMAF must remain bare even when generic xcode/enhancement state is active");
  }

  @Test
  public void testNullOrEmptyDeliveryYieldsNull()
  {
    assertNull(MiniPlayer.buildEffDeliveryToken(null, null),
        "No chosen surface delivery (legacy/empty-surface path) must yield null -- caller skips emission");
    assertNull(MiniPlayer.buildEffDeliveryToken("", null),
        "Empty chosen surface delivery must yield null -- caller skips emission");
  }

  // -------- server video enhancement suffix (additive) --------

  @Test
  public void testNoEnhancementTierLeavesTokenByteIdentical()
  {
    // The whole backward-compatibility claim rests on this: until enhancement
    // actually runs, no client can observe any change to the token.
    assertEquals(MiniPlayer.buildEffDeliveryToken("pull-xcode", "dynamich264", null),
        MiniPlayer.buildEffDeliveryToken("pull-xcode", "dynamich264"));
    assertEquals(MiniPlayer.buildEffDeliveryToken("pull", null, sage.enhance.EnhancementTier.NONE),
        "pull:direct");
    assertEquals(MiniPlayer.buildEffDeliveryToken("push", null, sage.enhance.EnhancementTier.NONE),
        "push");
  }

  @Test
  public void testEnhancementTierAppendsSuffix()
  {
    assertEquals(MiniPlayer.buildEffDeliveryToken("pull-xcode", "dynamich264",
        sage.enhance.EnhancementTier.ENHANCE_2160P),
        "pull-xcode:dynamich264:enhance;tier=2160p");
    assertEquals(MiniPlayer.buildEffDeliveryToken("push", null,
        sage.enhance.EnhancementTier.ENHANCE_1080P),
        "push:enhance;tier=1080p");
    assertEquals(MiniPlayer.buildEffDeliveryToken("pull-xcode", "mpeg2tsremux",
        sage.enhance.EnhancementTier.DEINTERLACE_ONLY),
        "pull-xcode:mpeg2tsremux:enhance;tier=deint");
  }

  @Test
  public void testAiOnlyPolicyAppendsToEnhancementSuffix()
  {
    assertEquals(MiniPlayer.buildEffDeliveryToken("pull-xcode", "mpeg2tsremux",
        sage.enhance.EnhancementTier.ENHANCE_2160P, true),
        "pull-xcode:mpeg2tsremux:enhance;tier=2160p;policy=ai_only");
  }

  @Test
  public void testEnhancementNeverResurrectsANullToken()
  {
    assertNull(MiniPlayer.buildEffDeliveryToken(null, null,
        sage.enhance.EnhancementTier.ENHANCE_2160P),
        "An absent delivery must stay absent even with a tier set");
  }

  @Test
  public void testEnhancementNeverRidesBarePullDirect()
  {
    // Regression guard for the live-TV "direct:enhance -> 352x240 DVD default"
    // collapse. A bare "pull:direct" is a passthrough with no server transcoder,
    // so an active enhancement tier MUST NOT be appended: emitting
    // "pull:direct:enhance" made the msproxy bridge reject the unroutable
    // direct:enhance mode and fall back to the legacy DVD default profile
    // (352x240 MPEG-4 @ 300 kbps). The GPU-enhance pull upgrade in load()
    // promotes such a tune to pull-xcode:mpeg2tsremux first; this is the
    // last-line invariant that keeps a missed promotion from corrupting the
    // stream. Worst case is a clean DIRECT_PLAY at source resolution.
    assertEquals(MiniPlayer.buildEffDeliveryToken("pull", null,
        sage.enhance.EnhancementTier.ENHANCE_2160P),
        "pull:direct",
        "an active tier must never ride a bare pull:direct (no transcoder)");
    assertEquals(MiniPlayer.buildEffDeliveryToken("pull", "",
        sage.enhance.EnhancementTier.ENHANCE_1080P),
        "pull:direct",
        "empty xcode mode is still a bare pull:direct -- no enhance suffix");
    // The promoted form (what the pull upgrade produces) DOES carry the suffix:
    // once there is a copy-family transcoder the enhancement pass can rewrite.
    assertEquals(MiniPlayer.buildEffDeliveryToken("pull-xcode", "mpeg2tsremux",
        sage.enhance.EnhancementTier.ENHANCE_2160P),
        "pull-xcode:mpeg2tsremux:enhance;tier=2160p",
        "the promoted pull-xcode:mpeg2tsremux carries enhancement normally");
  }

  // -------- NG output-codec resolution (resolveNgOutputCodecNames) --------
  //
  // The server owns the browserhd ffmpeg profiles, so it states the codecs the
  // client will ACTUALLY receive on the wire. The source-based hint names the
  // pre-transcode codec (wrong for a browserhd TRANSCODE), which is what made
  // the PWA probe/stall on startup. This one resolver is the single decision
  // behind the native STREAMINFO channel (it rewrites the wire ContainerFormat).
  // The browser/CMAF path reads codecs from the fMP4 init.mp4 (#EXT-X-MAP), so
  // it needs no server side-channel; the old ng_fmt/ng_out hints are retired.
  // Pure String in/out -> no MediaFormat instantiation (which needs full Sage
  // init and is unavailable in this unit harness).

  private static final String V = "video/avc";       // H.264
  private static final String AAC_M = "audio/mp4a-latm";
  private static final String AC3_M = "audio/ac3";
  private static final String HEVC_M = "video/hevc";

  @Test
  public void testResolveOutputBrowserhdTranscodeIsH264Aac()
  {
    // The exact failing case: MPEG2/AC3 source, browserhd transcodes to H.264/AAC.
    String[] n = MiniPlayer.resolveNgOutputCodecNames("browserhd", null,
        sage.media.format.MediaFormat.MPEG2_VIDEO, sage.media.format.MediaFormat.AC3, "AAC");
    assertEquals(n[0], sage.media.format.MediaFormat.H264);
    assertEquals(n[1], "AAC");
  }

  @Test
  public void testResolveOutputDefaultsToAacFloor()
  {
    // No surface-resolved target audio codec -> browserhd's AAC floor.
    String[] n = MiniPlayer.resolveNgOutputCodecNames("browserhd", null,
        sage.media.format.MediaFormat.MPEG2_VIDEO, sage.media.format.MediaFormat.AC3, null);
    assertEquals(n[0], sage.media.format.MediaFormat.H264);
    assertEquals(n[1], sage.media.format.MediaFormat.AAC);
  }

  @Test
  public void testResolveOutputCopyvKeepsSourceVideoTranscodesAudio()
  {
    // copyv stream-copies the (already H.264) source video, transcodes audio to AAC.
    String[] n = MiniPlayer.resolveNgOutputCodecNames("browserhd_copyv", null,
        sage.media.format.MediaFormat.H264, sage.media.format.MediaFormat.AC3, "AAC");
    assertEquals(n[0], sage.media.format.MediaFormat.H264);
    assertEquals(n[1], "AAC");
  }

  @Test
  public void testResolveOutputRemuxCopiesBothStreams()
  {
    // remux stream-copies both -> report the source codecs (H.264 + AC3 here).
    String[] n = MiniPlayer.resolveNgOutputCodecNames("browserhd_remux", null,
        sage.media.format.MediaFormat.H264, sage.media.format.MediaFormat.AC3, null);
    assertEquals(n[0], sage.media.format.MediaFormat.H264);
    assertEquals(n[1], sage.media.format.MediaFormat.AC3);
  }

  @Test
  public void testResolveOutputEnhanceIsHevc()
  {
    String[] n = MiniPlayer.resolveNgOutputCodecNames("browserhd", sage.enhance.EnhancementTier.ENHANCE_2160P,
        sage.media.format.MediaFormat.MPEG2_VIDEO, sage.media.format.MediaFormat.AC3, "AAC");
    assertEquals(n[0], sage.media.format.MediaFormat.HEVC);
    assertEquals(n[1], "AAC");
  }

  @Test
  public void testResolveOutputNonBrowserhdModeYieldsNull()
  {
    assertNull(MiniPlayer.resolveNgOutputCodecNames("mpeg2tsremux", null,
        sage.media.format.MediaFormat.H264, sage.media.format.MediaFormat.AAC, "AAC"),
        "Only the browserhd/fMP4 family carries an output-codec decision");
    assertNull(MiniPlayer.resolveNgOutputCodecNames(null, null, null, null, null));
  }

  @Test
  public void testResolveOutputUnmappableNameIsAuthoritative()
  {
    // A copied source video whose name toMimeType cannot map (e.g. VP9) is still
    // authoritative for STREAMINFO -- the raw codec NAME is returned as-is (the
    // old MIME projection returned null here; STREAMINFO carries the name).
    String[] n = MiniPlayer.resolveNgOutputCodecNames("browserhd_copyv", null,
        "VP9", sage.media.format.MediaFormat.AAC, null);
    assertEquals(n[0], "VP9");
    assertEquals(n[1], sage.media.format.MediaFormat.AAC);
  }

  @Test
  public void testResolveNgOutputCodecNamesFeedsStreamInfo()
  {
    // STREAMINFO consumes the raw codec NAMES (not MIMEs) to rewrite the wire
    // ContainerFormat -- the sole serializer of this decision now.
    String[] n = MiniPlayer.resolveNgOutputCodecNames("browserhd", null,
        sage.media.format.MediaFormat.MPEG2_VIDEO, sage.media.format.MediaFormat.AC3, "AAC");
    assertEquals(n[0], sage.media.format.MediaFormat.H264);
    assertEquals(n[1], "AAC");
    // Non-browserhd -> no override anywhere.
    assertNull(MiniPlayer.resolveNgOutputCodecNames("mpeg2tsremux", null,
        sage.media.format.MediaFormat.H264, sage.media.format.MediaFormat.AAC, "AAC"));
  }

  @Test
  public void testDescribeBrowserhdWireFormatNullSourceIsNull()
  {
    // Null source / null output codecs are pure no-ops (no ContainerFormat
    // instantiation -- which needs full Sage init, unavailable in this harness).
    assertNull(MiniPlayer.describeBrowserhdWireFormat0(null, "H.264", "AAC"));
  }

  // -------- NG output-container resolution (resolveNgOutputContainerName) --------

  @Test
  public void testResolveContainerTsRemuxIsTs()
  {
    // A PS recording remuxed to MPEG2-TS delivers TS on the wire; STREAMINFO must
    // report the delivery container, not the source's MPEG2-PS.
    assertEquals(MiniPlayer.resolveNgOutputContainerName("mpeg2tsremux"),
        sage.media.format.MediaFormat.MPEG2_TS);
  }

  @Test
  public void testResolveContainerPsRemuxIsPs()
  {
    assertEquals(MiniPlayer.resolveNgOutputContainerName("mpeg2psremux"),
        sage.media.format.MediaFormat.MPEG2_PS);
  }

  @Test
  public void testResolveContainerBrowserhdFamilyIsFmp4()
  {
    // Every browserhd-family mode delivers an fMP4 (QuickTime/MP4) container.
    assertEquals(MiniPlayer.resolveNgOutputContainerName("browserhd"),
        sage.media.format.MediaFormat.QUICKTIME);
    assertEquals(MiniPlayer.resolveNgOutputContainerName("browserhd_copyv"),
        sage.media.format.MediaFormat.QUICKTIME);
    assertEquals(MiniPlayer.resolveNgOutputContainerName("browserhd_remux"),
        sage.media.format.MediaFormat.QUICKTIME);
  }

  @Test
  public void testResolveContainerDirectPlayKeepsSource()
  {
    // Direct play / bare pull: the wire IS the source container, so no override.
    assertNull(MiniPlayer.resolveNgOutputContainerName(null));
    assertNull(MiniPlayer.resolveNgOutputContainerName(""));
  }

  @Test
  public void testResolveContainerInlineManagedPushModes()
  {
    assertEquals(MiniPlayer.resolveNgOutputContainerName(
        "container=mpegts;videocodec=h264_nvenc;audiocodec=COPY"),
        sage.media.format.MediaFormat.MPEG2_TS);
    assertEquals(MiniPlayer.resolveNgOutputContainerName(
        "container=matroska;videocodec=COPY;audiocodec=COPY"),
        sage.media.format.MediaFormat.MATROSKA);
  }

  // =======================================================================
  // Item 2: audioRelativeIndexOf -- 0-based position of the chosen audio
  // stream among the source's audio streams (for -map 0:a:<rel>).
  // =======================================================================
  private static sage.media.format.AudioFormat audio(int orderIndex)
  {
    try
    {
      TestUtils.initializeSageTVForTesting();
    }
    catch (Throwable t)
    {
      throw new AssertionError("Unable to initialize Sage test state", t);
    }
    sage.media.format.AudioFormat af = new sage.media.format.AudioFormat();
    af.setOrderIndex(orderIndex);
    return af;
  }

  @Test
  public void testAudioRelativeIndexOf_multiAudioByIdentity()
  {
    sage.media.format.AudioFormat a0 = audio(1); // stream 1 (0 is video)
    sage.media.format.AudioFormat a1 = audio(2);
    sage.media.format.AudioFormat a2 = audio(3);
    sage.media.format.VideoFormat v = new sage.media.format.VideoFormat();
    v.setOrderIndex(0);
    sage.media.format.ContainerFormat cf = new sage.media.format.ContainerFormat();
    cf.setStreamFormats(new sage.media.format.BitstreamFormat[] { v, a0, a1, a2 });

    assertEquals(MiniPlayer.audioRelativeIndexOf(cf, a0), 0,
        "First audio stream must be audio-relative index 0 (not its absolute orderIndex)");
    assertEquals(MiniPlayer.audioRelativeIndexOf(cf, a1), 1);
    assertEquals(MiniPlayer.audioRelativeIndexOf(cf, a2), 2);
  }

  @Test
  public void testAudioRelativeIndexOf_matchesByOrderIndexWhenNotSameInstance()
  {
    sage.media.format.AudioFormat a0 = audio(1);
    sage.media.format.AudioFormat a1 = audio(2);
    sage.media.format.ContainerFormat cf = new sage.media.format.ContainerFormat();
    cf.setStreamFormats(new sage.media.format.BitstreamFormat[] { a0, a1 });

    // A different object with the same orderIndex must still resolve.
    assertEquals(MiniPlayer.audioRelativeIndexOf(cf, audio(2)), 1,
        "Should fall back to matching by absolute orderIndex when not the same instance");
  }

  @Test
  public void testAudioRelativeIndexOf_nullsAndMissingReturnMinusOne()
  {
    assertEquals(MiniPlayer.audioRelativeIndexOf(null, audio(1)), -1);
    assertEquals(MiniPlayer.audioRelativeIndexOf(new sage.media.format.ContainerFormat(), null), -1);

    sage.media.format.ContainerFormat cf = new sage.media.format.ContainerFormat();
    cf.setStreamFormats(new sage.media.format.BitstreamFormat[] { audio(1) });
    assertEquals(MiniPlayer.audioRelativeIndexOf(cf, audio(9)), -1,
        "An audio stream not present in the container must return -1 (caller keeps all audio)");
  }

  @Test
  public void testAudioFormatByOrderIndexUsesServerSelectedLegacyTrack()
  {
    sage.media.format.AudioFormat main = audio(1);
    sage.media.format.AudioFormat descriptions = audio(2);
    sage.media.format.ContainerFormat cf = new sage.media.format.ContainerFormat();
    cf.setStreamFormats(new sage.media.format.BitstreamFormat[] { main, descriptions });

    assertEquals(MiniPlayer.audioFormatByOrderIndex(cf, 1), main,
        "The legacy player setup must use the stream selected by the server");
    assertEquals(MiniPlayer.audioFormatByOrderIndex(cf, 99), cf.getAudioFormat(),
        "An unavailable selected stream must retain the historical primary-audio fallback");
  }

  @Test
  public void testInitialAudioSelectionTargetsOnlyLegacyPullPlayback()
  {
    sage.media.format.AudioFormat[] tracks = { audio(1), audio(2) };

    assertTrue(MiniPlayer.shouldSetInitialLegacyPullAudioStream(
        false, true, false, false, tracks));
    assertFalse(MiniPlayer.shouldSetInitialLegacyPullAudioStream(
        true, true, false, false, tracks), "NG clients have their own audio-selection contract");
    assertFalse(MiniPlayer.shouldSetInitialLegacyPullAudioStream(
        false, true, true, false, tracks), "Push playback already selects by stream ID");
    assertFalse(MiniPlayer.shouldSetInitialLegacyPullAudioStream(
        false, true, false, false, new sage.media.format.AudioFormat[] { audio(1) }),
        "Single-track files do not need a selection command");
  }

  @Test
  public void testAndroidPullXcodeFallbackTargetsOnlyKnownNativeSurfaces()
  {
    sage.client.PlaybackSurface media3 = new sage.client.PlaybackSurface(
        "android_media3", "native", 100,
        java.util.Arrays.asList("push", "pull", "pull-xcode"),
        java.util.Collections.singletonList("H264"),
        java.util.Collections.singletonList("AC3"),
        java.util.Collections.singletonList("MATROSKA"));
    sage.client.PlaybackSurface ijk = new sage.client.PlaybackSurface(
        "android_ijk", "native", 90,
        java.util.Arrays.asList("push", "pull-xcode"),
        java.util.Collections.singletonList("H264"),
        java.util.Collections.singletonList("AC3"),
        java.util.Collections.singletonList("MATROSKA"));
    sage.client.PlaybackSurface pwa = new sage.client.PlaybackSurface(
        "pwa_mse", "bridge", 100,
        java.util.Arrays.asList("pull-xcode", "push"),
        java.util.Collections.singletonList("H264"),
        java.util.Collections.singletonList("AAC"),
        java.util.Collections.singletonList("MP4"));
    sage.client.PlaybackSurface pullOnlyAndroid = new sage.client.PlaybackSurface(
        "android_media3", "native", 100,
        java.util.Collections.singletonList("pull-xcode"),
        java.util.Collections.singletonList("H264"),
        java.util.Collections.singletonList("AC3"),
        java.util.Collections.singletonList("MATROSKA"));

    assertTrue(MiniPlayer.shouldFallbackAndroidPullXcode(media3, "pull-xcode"));
    assertTrue(MiniPlayer.shouldFallbackAndroidPullXcode(ijk, "pull-xcode"));
    assertFalse(MiniPlayer.shouldFallbackAndroidPullXcode(media3, "push"),
        "An already-correct Android push decision must remain unchanged");
    assertFalse(MiniPlayer.shouldFallbackAndroidPullXcode(pwa, "pull-xcode"),
        "Browser bridges implement XCODE_SETUP and must retain pull-xcode");
    assertFalse(MiniPlayer.shouldFallbackAndroidPullXcode(pullOnlyAndroid, "pull-xcode"),
        "The server must not fabricate push when the surface did not advertise it");
  }

  @Test
  public void testAndroidEnhancePushHandlesPullAndAlreadyPushWinners()
  {
    sage.client.PlaybackSurface ijk = new sage.client.PlaybackSurface(
        "android_ijk", "software", 60,
        java.util.Arrays.asList("push", "pull"),
        java.util.Arrays.asList("MPEG2-VIDEO", "HEVC"),
        java.util.Collections.singletonList("AC3"),
        java.util.Arrays.asList("MPEG2-PS", "MATROSKA", "MPEG2-TS"));

    assertTrue(MiniPlayer.shouldUseAndroidEnhancePush(
        ijk, "pull", null, true, true),
        "An Android direct-pull winner must be promoted to a supported managed push transcode");
    assertTrue(MiniPlayer.shouldUseAndroidEnhancePush(
        ijk, "push", null, true, true),
        "An MPEG2-PS push-only winner must also use the managed enhancement transcode");
  }

  @Test
  public void testAndroidEnhancePushRejectsUnadvertisedOrNonAndroidRoutes()
  {
    sage.client.PlaybackSurface pwa = new sage.client.PlaybackSurface(
        "pwa_mse", "bridge", 100,
        java.util.Arrays.asList("push", "pull-xcode"),
        java.util.Collections.singletonList("HEVC"),
        java.util.Collections.singletonList("AAC"),
        java.util.Collections.singletonList("MATROSKA"));
    sage.client.PlaybackSurface androidWithoutMkv = new sage.client.PlaybackSurface(
        "android_media3", "native", 100,
        java.util.Arrays.asList("push", "pull"),
        java.util.Collections.singletonList("HEVC"),
        java.util.Collections.singletonList("AC3"),
        java.util.Collections.singletonList("MP4"));

    assertFalse(MiniPlayer.shouldUseAndroidEnhancePush(
        pwa, "push", null, true, true),
        "Browser surfaces must retain their own negotiated delivery path");
    assertFalse(MiniPlayer.shouldUseAndroidEnhancePush(
        androidWithoutMkv, "push", null, true, true),
        "The server must not fabricate a supported push container");
    assertFalse(MiniPlayer.shouldUseAndroidEnhancePush(
        androidWithoutMkv, "push", null, true, false),
        "Plain playback must remain byte-identical when enhancement is inactive");
  }

  private static sage.media.format.ContainerFormat videoFormat(String codec)
  {
    sage.media.format.VideoFormat video = new sage.media.format.VideoFormat();
    video.setFormatName(codec);
    video.setWidth(1920);
    video.setHeight(1080);
    video.setFps(29.97f);
    sage.media.format.ContainerFormat format = new sage.media.format.ContainerFormat();
    format.setStreamFormats(new sage.media.format.BitstreamFormat[] { video });
    return format;
  }

  @Test
  public void testMpeg2EnhanceUsesStableH264BaseModes()
  {
    sage.media.format.ContainerFormat mpeg2 =
        videoFormat(sage.media.format.MediaFormat.MPEG2_VIDEO);

    assertEquals(MiniPlayer.getEnhanceOutputVideoCodec(mpeg2),
        sage.media.format.MediaFormat.H264);
    assertEquals(MiniPlayer.getEnhanceTranscodeBaseMode("mpeg2tsremux", mpeg2),
        "dynamich264");
    assertEquals(MiniPlayer.getEnhanceTranscodeBaseMode("browserhd_remux", mpeg2),
        "browserhd");
    assertEquals(MiniPlayer.getAndroidEnhancePushMode(
        mpeg2, sage.media.format.MediaFormat.MPEG2_TS),
        "container=mpegts;videocodec=h264_nvenc;audiocodec=COPY;"
            + "videobitrate=8000000;resolution=SOURCE;fps=SOURCE");
    assertEquals(MiniPlayer.getAndroidEnhancePushMode(
        mpeg2, sage.media.format.MediaFormat.MATROSKA),
        "container=matroska;videocodec=h264_nvenc;audiocodec=COPY;"
            + "videobitrate=8000000;resolution=SOURCE;fps=SOURCE");
  }

  @Test
  public void testNonMpeg2EnhanceRetainsCopyToHevcPath()
  {
    sage.media.format.ContainerFormat h264 =
        videoFormat(sage.media.format.MediaFormat.H264);

    assertEquals(MiniPlayer.getEnhanceOutputVideoCodec(h264),
        sage.media.format.MediaFormat.HEVC);
    assertEquals(MiniPlayer.getEnhanceTranscodeBaseMode("mpeg2tsremux", h264),
        "mpeg2tsremux");
    assertEquals(MiniPlayer.getAndroidEnhancePushMode(
        h264, sage.media.format.MediaFormat.MPEG2_TS),
        "container=mpegts;videocodec=COPY;audiocodec=COPY");
  }

  @Test
  public void testAndroidMpeg2EnhanceRequiresAdvertisedH264MpegTsPush()
  {
    sage.client.PlaybackSurface h264 = new sage.client.PlaybackSurface(
        "android_media3", "native", 100,
        java.util.Arrays.asList("push", "pull"),
        java.util.Collections.singletonList("H264"),
        java.util.Collections.singletonList("AC3"),
        java.util.Collections.singletonList("MPEG2-TS"));

    assertTrue(MiniPlayer.shouldUseAndroidEnhancePush(
        h264, "pull", null, true, true, sage.media.format.MediaFormat.H264));
    assertFalse(MiniPlayer.shouldUseAndroidEnhancePush(
        h264, "pull", null, true, true, sage.media.format.MediaFormat.HEVC));
  }

  @Test
  public void testAndroidEnhancePrefersSeekSafeMpegTsPush()
  {
    sage.client.PlaybackSurface both = new sage.client.PlaybackSurface(
        "android_media3", "native", 100,
        java.util.Arrays.asList("push", "pull"),
        java.util.Arrays.asList("H264", "HEVC"),
        java.util.Collections.singletonList("AC3"),
        java.util.Arrays.asList("MATROSKA", "MPEG2-TS"));
    sage.client.PlaybackSurface mkvOnly = new sage.client.PlaybackSurface(
        "android_ijk", "native", 60,
        java.util.Collections.singletonList("push"),
        java.util.Collections.singletonList("H264"),
        java.util.Collections.singletonList("AC3"),
        java.util.Collections.singletonList("MATROSKA"));
    java.util.Map<String, boolean[]> pullOnlyTransport =
        new java.util.HashMap<String, boolean[]>();
    pullOnlyTransport.put("MPEG2-TS", new boolean[] { false, true });
    sage.client.PlaybackSurface pullOnlyTs = new sage.client.PlaybackSurface(
        "android_media3", "native", 100,
        java.util.Arrays.asList("push", "pull"),
        java.util.Collections.singletonList("H264"),
        java.util.Collections.singletonList("AC3"),
        java.util.Collections.singletonList("MPEG2-TS"),
        null, null, null, 0, 0, 0, null, pullOnlyTransport);

    assertEquals(MiniPlayer.preferredAndroidEnhancePushContainer(both),
        sage.media.format.MediaFormat.MPEG2_TS,
        "MPEG-TS avoids appending a new EBML generation after an FF/autoskip reprime");
    assertNull(MiniPlayer.preferredAndroidEnhancePushContainer(mkvOnly),
        "Matroska-only surfaces must fail closed instead of accepting concatenated EBML generations");
    assertNull(MiniPlayer.preferredAndroidEnhancePushContainer(pullOnlyTs),
        "An explicit MPEG-TS push=false declaration must be honored");
  }

  @Test
  public void testAndroidEnhanceReranksForEffectiveHevcMpegTsStream()
  {
    java.util.Map<String, sage.client.PlaybackSurface> advertised =
        new java.util.LinkedHashMap<String, sage.client.PlaybackSurface>();
    sage.client.PlaybackSurface ijk = new sage.client.PlaybackSurface(
        "android_ijk", "native", 60,
        java.util.Arrays.asList("push", "pull"),
        java.util.Arrays.asList("MPEG2-VIDEO", "HEVC"),
        java.util.Collections.singletonList("AC3"),
        java.util.Arrays.asList("MPEG2-PS", "MATROSKA", "MPEG2-TS"));
    sage.client.PlaybackSurface media3 = new sage.client.PlaybackSurface(
        "android_media3", "native", 100,
        java.util.Arrays.asList("push", "pull"),
        java.util.Arrays.asList("H264", "HEVC"),
        java.util.Collections.singletonList("AC3"),
        java.util.Arrays.asList("MP4", "MATROSKA", "MPEG2-TS"));
    advertised.put(ijk.getId(), ijk);
    advertised.put(media3.getId(), media3);
    sage.client.ClientConstraints exo = sage.client.ClientConstraints.parse(
        "exoplayer",
        "HEVC;decoder=hw;maxW=4096;maxH=4096;maxFps=60",
        "AC3", "MPEG2-TS");
    sage.client.ClientConstraints ijkConstraints = sage.client.ClientConstraints.parse(
        "ijkplayer",
        "MPEG2-VIDEO;decoder=sw_or_hw;maxW=1920;maxH=1088,"
            + "HEVC;decoder=sw_or_hw;maxW=4096;maxH=4096;maxFps=60",
        "AC3", "MPEG2-PS,MPEG2-TS");

    assertEquals(MiniPlayer.chooseAndroidEnhanceSurface(
        advertised.values(), ijk, "AC3", 3840, 2160, 60, exo, ijkConstraints), media3,
        "Enhanced HEVC/MPEG-TS must use the highest-priority proven hardware surface");
  }

  @Test
  public void testAndroidEnhanceSurfaceSwitchRequiresProvenHardwareDecode()
  {
    java.util.Map<String, sage.client.PlaybackSurface> advertised =
        new java.util.LinkedHashMap<String, sage.client.PlaybackSurface>();
    sage.client.PlaybackSurface ijk = new sage.client.PlaybackSurface(
        "android_ijk", "native", 60,
        java.util.Arrays.asList("push", "pull"),
        java.util.Arrays.asList("MPEG2-VIDEO", "HEVC"),
        java.util.Collections.singletonList("AC3"),
        java.util.Arrays.asList("MPEG2-PS", "MATROSKA", "MPEG2-TS"));
    sage.client.PlaybackSurface media3 = new sage.client.PlaybackSurface(
        "android_media3", "native", 100,
        java.util.Arrays.asList("push", "pull"),
        java.util.Collections.singletonList("HEVC"),
        java.util.Collections.singletonList("AC3"),
        java.util.Collections.singletonList("MPEG2-TS"));
    advertised.put(ijk.getId(), ijk);
    advertised.put(media3.getId(), media3);
    sage.client.ClientConstraints ambiguousExo = sage.client.ClientConstraints.parse(
        "exoplayer",
        "HEVC;decoder=sw_or_hw;maxW=4096;maxH=4096;maxFps=60",
        "AC3", "MPEG2-TS");

    assertEquals(MiniPlayer.chooseAndroidEnhanceSurface(
        advertised.values(), ijk, "AC3", 3840, 2160, 60, ambiguousExo, null), ijk,
        "The server must not switch players from an ambiguous or software decoder claim");
  }

  @Test
  public void testAndroidSurfaceSetIsRecognizedForBufferedPushEos()
  {
    assertTrue(MiniPlayer.hasAndroidNativePlaybackSurfaceIds(
        java.util.Arrays.asList("android_media3", "android_ijk")));
    assertTrue(MiniPlayer.hasAndroidNativePlaybackSurfaceIds(
        java.util.Collections.singletonList("ANDROID_MEDIA3")));
    assertFalse(MiniPlayer.hasAndroidNativePlaybackSurfaceIds(
        java.util.Collections.singletonList("pwa_mse")));
    assertFalse(MiniPlayer.hasAndroidNativePlaybackSurfaceIds(null));
  }

  @Test
  public void testTerminalTranscodeFailureBypassesBufferedPushEosDeferral()
  {
    assertTrue(MiniPlayer.shouldDeferPushEosUntilPlaybackClock(
        true, true, true, false));
    assertFalse(MiniPlayer.shouldDeferPushEosUntilPlaybackClock(
        true, true, true, true));
    assertFalse(MiniPlayer.shouldDeferPushEosUntilPlaybackClock(
        false, true, true, false));
  }

  @Test
  public void testCleanReconnectFailureIsNeverRestartedOnSameStream()
  {
    assertTrue(MiniPlayer.shouldRestartFailedServerTranscode(false, false));
    assertFalse(MiniPlayer.shouldRestartFailedServerTranscode(false, true));
    assertFalse(MiniPlayer.shouldRestartFailedServerTranscode(true, false));
  }

  // ---- chooseRemuxPushMode: REMUX target must not be hardcoded to PS ----------

  /**
   * A TS-only client (dropped PS, as the Android NG client now does) that hits a
   * codec-clean REMUX verdict must be rewrapped into MPEG2-TS, never PS. The old
   * hardcoded {@code mpeg2psremux} both ignored this and, worse, forced HEVC into
   * MPEG2-PS which freezes the client.
   */
  @Test
  public void testChooseRemux_tsOnlyClientGetsTs()
  {
    assertEquals(MiniPlayer.chooseRemuxPushMode(true, false, null, false), "mpeg2tsremux",
        "TS-capable / PS-incapable client must get an MPEG2-TS remux, not PS");
  }

  /** A legacy PS-capable client (no TS, no enhance, no fixed format) is unchanged. */
  @Test
  public void testChooseRemux_legacyPsClientUnchanged()
  {
    assertEquals(MiniPlayer.chooseRemuxPushMode(false, true, null, false), "mpeg2psremux",
        "Legacy PS-only client keeps the classic PS remux (no regression)");
  }

  /**
   * The client's explicit FIXED_PUSH_REMUX_FORMAT is honored verbatim when set —
   * the client named the wire container it wants.
   */
  @Test
  public void testChooseRemux_honorsClientFixedRemuxFormat()
  {
    String fixed = "container=mpegts;videocodec=COPY;audiocodec=COPY";
    assertEquals(MiniPlayer.chooseRemuxPushMode(true, true, fixed, false), fixed,
        "A client-declared FIXED_PUSH_REMUX_FORMAT must be honored over the PS default");
  }

  /**
   * An active GPU-enhance tier can only ride a copy-family MPEG2-TS remux; even
   * when the client would accept PS (or named a fixed format), enhancement forces
   * mpeg2tsremux so the enhancement pass has a copy-family transcoder to rewrite.
   */
  @Test
  public void testChooseRemux_enhanceForcesTsRemux()
  {
    assertEquals(MiniPlayer.chooseRemuxPushMode(true, true,
        "container=mpegts;videocodec=COPY;audiocodec=COPY", true), "mpeg2tsremux",
        "Active enhance tier + TS push must resolve to mpeg2tsremux (enhance-capable)");
    assertEquals(MiniPlayer.chooseRemuxPushMode(true, false, null, true), "mpeg2tsremux",
        "Active enhance tier on a TS-only client -> mpeg2tsremux");
  }

  /**
   * Enhance requested but the client has NO MPEG2-TS push: enhancement cannot be
   * delivered as a TS remux, so fall through to the normal container choice rather
   * than fabricating a TS push the client can't receive.
   */
  @Test
  public void testChooseRemux_enhanceWithoutTsFallsThrough()
  {
    assertEquals(MiniPlayer.chooseRemuxPushMode(false, true, null, true), "mpeg2psremux",
        "Enhance active but no TS push -> normal PS choice (no unreceivable TS push)");
  }

  /** Null mcsr degrades to the safe PS default. */
  @Test
  public void testChooseRemux_nullClientDefaultsToPs()
  {
    assertEquals(MiniPlayer.chooseRemuxPushMode(null, null, false), "mpeg2psremux",
        "Null renderer -> safe MPEG2-PS default");
  }

  // ---- high-fps MPEG-2 copy-remux -> H.264 re-encode ---------------------------

  /**
   * A 720p59.94 MPEG-2 source that would otherwise be copied through as a
   * copy-family remux must be re-encoded to H.264-in-TS when the client can
   * decode H.264 push — the copy path tears once the client buffer drains.
   */
  @Test
  public void testPreferH264_highFpsMpeg2CopyReencodes()
  {
    assertTrue(MiniPlayer.preferH264ReencodeOverMpeg2Copy("MPEG2-Video", 59.94, true, false),
        "720p59.94 MPEG-2 with H.264-capable client must re-encode to H.264");
    assertEquals(MiniPlayer.maybeReencodeHighFpsMpeg2Remux(
        "mpeg2tsremux", "MPEG2-Video", 59.94, true, false), "dynamich264",
        "High-fps MPEG-2 copy-family TS remux must become dynamich264");
    assertEquals(MiniPlayer.maybeReencodeHighFpsMpeg2Remux(
        "mpeg2psremux", "MPEG2-Video", 60.0, true, false), "dynamich264",
        "High-fps MPEG-2 copy-family PS remux must become dynamich264");
  }

  /** Ordinary 29.97 MPEG-2 stays a plain copy remux (no needless re-encode). */
  @Test
  public void testPreferH264_normalFpsMpeg2StaysCopy()
  {
    assertFalse(MiniPlayer.preferH264ReencodeOverMpeg2Copy("MPEG2-Video", 29.97, true, false),
        "29.97 MPEG-2 is within the client's MPEG-2 decode budget — keep the copy remux");
    assertEquals(MiniPlayer.maybeReencodeHighFpsMpeg2Remux(
        "mpeg2tsremux", "MPEG2-Video", 29.97, true, false), "mpeg2tsremux",
        "Standard-fps MPEG-2 remux is left untouched");
  }

  /** Enhancement active must keep the copy-family remux (enhance rides it). */
  @Test
  public void testPreferH264_enhanceKeepsCopyRemux()
  {
    assertFalse(MiniPlayer.preferH264ReencodeOverMpeg2Copy("MPEG2-Video", 59.94, true, true),
        "Active enhance tier must never be diverted off the copy-family remux");
    assertEquals(MiniPlayer.maybeReencodeHighFpsMpeg2Remux(
        "mpeg2tsremux", "MPEG2-Video", 59.94, true, true), "mpeg2tsremux",
        "Enhance-active high-fps remux stays mpeg2tsremux");
  }

  /** No H.264 push decoder -> nothing to gain, keep the copy remux. */
  @Test
  public void testPreferH264_noH264ClientStaysCopy()
  {
    assertFalse(MiniPlayer.preferH264ReencodeOverMpeg2Copy("MPEG2-Video", 59.94, false, false),
        "Without an H.264 push decoder the copy remux is the only option");
  }

  /** Non-MPEG-2 high-fps sources (e.g. H.264) are not diverted. */
  @Test
  public void testPreferH264_nonMpeg2Untouched()
  {
    assertFalse(MiniPlayer.preferH264ReencodeOverMpeg2Copy("H.264", 59.94, true, false),
        "An H.264 source is already the strong codec — no re-encode");
    assertEquals(MiniPlayer.maybeReencodeHighFpsMpeg2Remux(
        "mpeg2tsremux", "H.264", 59.94, true, false), "mpeg2tsremux",
        "Non-MPEG-2 remux mode is passed through unchanged");
  }

  /** Unknown (0) fps must not trigger a re-encode (fail-safe to copy). */
  @Test
  public void testPreferH264_unknownFpsStaysCopy()
  {
    assertFalse(MiniPlayer.preferH264ReencodeOverMpeg2Copy("MPEG2-Video", 0, true, false),
        "Unknown fps must not be treated as high-fps");
  }

  /** A custom container= remux mode is never a copy-family mode -> untouched. */
  @Test
  public void testMaybeReencode_customContainerModeUntouched()
  {
    String custom = "container=mpegts;videocodec=COPY;audiocodec=ac3";
    assertEquals(MiniPlayer.maybeReencodeHighFpsMpeg2Remux(
        custom, "MPEG2-Video", 59.94, true, false), custom,
        "A custom container= mode is not a copy-family remux and must pass through");
  }
}
