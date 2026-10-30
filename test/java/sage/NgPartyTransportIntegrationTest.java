package sage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.Test;
import sage.epg.sd.gson.JsonObject;
import sage.epg.sd.gson.JsonParser;
import sage.party.PartyProtocol;
import sage.party.PartyService;
import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

/** Exercises the actual MiniClient inbound dispatcher, NG adapter, service and outbound property. */
public class NgPartyTransportIntegrationTest
{
  private static final class Transport implements AutoCloseable
  {
    final MiniClientSageRenderer renderer = mock(MiniClientSageRenderer.class, CALLS_REAL_METHODS);
    final UIManager ui = mock(UIManager.class);
    final VideoFrame video = mock(VideoFrame.class);
    final BlockingQueue<JsonObject> output = new LinkedBlockingQueue<>();
    final NgPartyConnection connection;
    final Method inbound;
    JsonObject available;
    long sequence;

    Transport(PartyService service, String mode) throws Exception
    {
      doReturn(video).when(ui).getVideoFrame();
      doReturn("server-admitted-lan").when(renderer).getPartyPrincipal();
      doReturn(PartyProtocol.REQUIRED).when(renderer).getClientCapabilities();
      doReturn(mode).when(renderer).getCurrentSurfaceDeliveryMode();
      doAnswer(call -> {
        output.add(new JsonParser().parse((String)call.getArgument(1)).getAsJsonObject());
        return null;
      }).when(renderer).sendSetPropertyAsync(anyString(), anyString());
      connection = spy(new NgPartyConnection(renderer, ui, service, () -> 100000));
      doReturn(3600000L).when(connection).duration(42);
      PartyService.Delivery path = new PartyService.Delivery(mode, mode, "H264", "AAC", 8000, false);
      doReturn(path).when(connection).plan(42);
      Field field = MiniClientSageRenderer.class.getDeclaredField("partyConnection");
      field.setAccessible(true); field.set(renderer, connection);
      inbound = MiniClientSageRenderer.class.getDeclaredMethod("handleInboundClientSetProperty", byte[].class);
      inbound.setAccessible(true);
      available = exchange("HELLO", new JsonObject());
    }

    JsonObject exchange(String type, JsonObject fields) throws Exception
    {
      fields.addProperty("schemaVersion", 1); fields.addProperty("type", type);
      String id = "r" + ++sequence;
      fields.addProperty("requestId", id); fields.addProperty("requestSeq", sequence);
      if (available != null)
      {
        fields.add("serverEpoch", available.get("serverEpoch"));
        fields.add("sessionToken", available.get("sessionToken"));
      }
      byte[] name = PartyProtocol.PROPERTY.getBytes(StandardCharsets.US_ASCII);
      byte[] json = fields.toString().getBytes(StandardCharsets.UTF_8);
      byte[] payload = ByteBuffer.allocate(4 + name.length + json.length).putShort((short)name.length)
          .putShort((short)json.length).put(name).put(json).array();
      assertEquals(inbound.invoke(renderer, (Object)payload), true);
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (System.nanoTime() < until)
      {
        JsonObject next = output.poll(1, TimeUnit.SECONDS);
        if (next != null && next.has("requestId") && id.equals(next.get("requestId").getAsString()))
          return next;
      }
      throw new AssertionError("Missing Party reply " + type);
    }

    @Override public void close() { connection.disconnect(); }
  }

  @Test public void actualRendererDispatchCreatesJoinsAndIsolatesSessionTokens() throws Exception
  {
    PartyService service = new PartyService(() -> 100000, true, 100000, 2);
    try (Transport host = new Transport(service, "push"); Transport guest = new Transport(service, "pull-xcode"))
    {
      JsonObject create = new JsonObject(); create.addProperty("mediaFileId", 42);
      JsonObject created = host.exchange("CREATE", create);
      assertEquals(created.get("type").getAsString(), "CREATED");
      verify(host.video).setPartyProgressOwned(true);
      JsonObject join = new JsonObject();
      join.add("partyId", created.get("partyId")); join.addProperty("mediaFileId", 42);
      join.add("joinToken", created.get("joinToken"));
      JsonObject joined = guest.exchange("JOIN", join);
      assertEquals(joined.get("type").getAsString(), "JOINED");
      assertEquals(joined.getAsJsonObject("snapshot").getAsJsonArray("roster").size(), 2);
      verify(guest.video).setPartyProgressOwned(true);
      assertNotEquals(created.get("participantId"), joined.get("participantId"));
      JsonObject otherToken = guest.available;
      guest.available = host.available;
      JsonObject snapshot = new JsonObject(); snapshot.add("partyId", created.get("partyId"));
      assertEquals(guest.exchange("SNAPSHOT", snapshot).get("code").getAsString(), "SESSION_DENIED");
      guest.available = otherToken;
      JsonObject leave = new JsonObject(); leave.add("partyId", created.get("partyId"));
      assertEquals(guest.exchange("LEAVE", leave).get("type").getAsString(), "LEFT");
      verify(guest.video).setPartyProgressOwned(false);
    }
  }

  @Test public void authenticatedNegotiatedRendererRoundTripAndLegacyRegression() throws Exception
  {
    MiniClientSageRenderer renderer = mock(MiniClientSageRenderer.class, CALLS_REAL_METHODS);
    UIManager ui = mock(UIManager.class);
    doReturn("verified-principal").when(renderer).getPartyPrincipal();
    doReturn(PartyProtocol.REQUIRED).when(renderer).getClientCapabilities();
    BlockingQueue<JsonObject> output = new LinkedBlockingQueue<>();
    doAnswer(call -> {
      assertEquals(call.getArgument(0), PartyProtocol.PROPERTY);
      output.add(new JsonParser().parse((String)call.getArgument(1)).getAsJsonObject());
      return null;
    }).when(renderer).sendSetPropertyAsync(anyString(), anyString());
    PartyService service = new PartyService(() -> 100000, true, 100000, 2);
    NgPartyConnection connection = new NgPartyConnection(renderer, ui, service);
    Field field = MiniClientSageRenderer.class.getDeclaredField("partyConnection");
    field.setAccessible(true); field.set(renderer, connection);
    Method inbound = MiniClientSageRenderer.class.getDeclaredMethod("handleInboundClientSetProperty", byte[].class);
    inbound.setAccessible(true);
    try
    {
      byte[] json = "{\"schemaVersion\":1,\"type\":\"HELLO\",\"requestId\":\"h1\",\"requestSeq\":1}"
          .getBytes(StandardCharsets.US_ASCII);
      byte[] name = PartyProtocol.PROPERTY.getBytes(StandardCharsets.US_ASCII);
      byte[] frame = ByteBuffer.allocate(4 + name.length + json.length).putShort((short)name.length)
          .putShort((short)json.length).put(name).put(json).array();
      assertEquals(inbound.invoke(renderer, (Object)frame), true);
      JsonObject available = output.poll(5, TimeUnit.SECONDS);
      assertNotNull(available);
      assertEquals(available.get("type").getAsString(), "AVAILABLE");
      assertTrue(available.has("serverEpoch"));
      doReturn(Set.of("PARTY_V1")).when(renderer).getClientCapabilities();
      inbound.invoke(renderer, (Object)frame);
      assertEquals(output.poll(5, TimeUnit.SECONDS).get("code").getAsString(), "NOT_AVAILABLE");
      doReturn(PartyProtocol.REQUIRED).when(renderer).getClientCapabilities();
      doReturn(null).when(renderer).getPartyPrincipal();
      inbound.invoke(renderer, (Object)frame);
      assertEquals(output.poll(5, TimeUnit.SECONDS).get("code").getAsString(), "NOT_AVAILABLE");
      assertEquals(inbound.invoke(renderer, (Object)new byte[] {0, 1, 0, 0, 'X'}), false);
    }
    finally { connection.disconnect(); }
  }

  @Test public void actualAdmittedLanAuthorizationIsInvalidatedWithTheRenderer() throws Exception
  {
    MiniClientSageRenderer renderer = mock(MiniClientSageRenderer.class, CALLS_REAL_METHODS);
    Field initialized = MiniClientSageRenderer.class.getDeclaredField("partySessionInitialized");
    Field admitted = MiniClientSageRenderer.class.getDeclaredField("partyLanAuthorized");
    initialized.setAccessible(true); admitted.setAccessible(true);
    assertNull(renderer.getPartyPrincipal());
    admitted.set(renderer, true);
    assertNull(renderer.getPartyPrincipal(), "LAN policy branch alone is not completed admission");
    initialized.set(renderer, true);
    assertEquals(renderer.getPartyPrincipal(), "server-admitted-lan");
    Method invalidate = MiniClientSageRenderer.class.getDeclaredMethod("invalidatePartyAuthorization");
    invalidate.setAccessible(true); invalidate.invoke(renderer);
    assertNull(renderer.getPartyPrincipal());
    assertFalse(admitted.getBoolean(renderer));
    assertFalse(initialized.getBoolean(renderer));
  }

  @Test public void serverOwnedPushSchedulesOnlyItsViewerAndPullRemainsClientExecuted() throws Exception
  {
    MiniClientSageRenderer renderer = mock(MiniClientSageRenderer.class);
    UIManager ui = mock(UIManager.class);
    VideoFrame video = mock(VideoFrame.class);
    MediaFile media = mock(MediaFile.class);
    doReturn(video).when(ui).getVideoFrame();
    doReturn(media).when(video).getCurrFile();
    doReturn(42).when(media).getID();
    doReturn("push").when(renderer).getCurrentSurfaceDeliveryMode();
    java.util.concurrent.CountDownLatch applied = new java.util.concurrent.CountDownLatch(1);
    doAnswer(call -> { applied.countDown(); return null; })
        .when(video).applyPartyTimeline(42, 10000, true);
    PartyService service = new PartyService(() -> 100000, true, 100000, 2);
    NgPartyConnection connection = new NgPartyConnection(renderer, ui, service, () -> 100000);
    try
    {
      JsonObject command = PartyProtocol.envelope("COMMAND");
      command.addProperty("executeAtServerMonoMs", 100000);
      command.addProperty("targetPositionMs", 10000);
      command.addProperty("targetState", "PLAYING");
      connection.schedule(command);
      assertTrue(applied.await(5, TimeUnit.SECONDS));
      verify(video).applyPartyTimeline(42, 10000, true);
      clearInvocations(video);
      doReturn("pull-xcode").when(renderer).getCurrentSurfaceDeliveryMode();
      connection.schedule(command);
      connection.cancelScheduled();
      verify(video, never()).applyPartyTimeline(anyInt(), anyLong(), anyBoolean());
      doReturn("push").when(renderer).getCurrentSurfaceDeliveryMode();
      command.addProperty("executeAtServerMonoMs", 200000);
      connection.schedule(command);
      connection.cancelScheduled();
      verify(video, never()).applyPartyTimeline(anyInt(), anyLong(), anyBoolean());
    }
    finally { connection.disconnect(); }
  }
}
