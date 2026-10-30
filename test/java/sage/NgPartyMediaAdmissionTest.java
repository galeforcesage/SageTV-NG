package sage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.testng.annotations.Test;
import sage.party.PartyProtocol;
import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

/** Covers credentials, actual producer callbacks and per-playback admission invalidation. */
public class NgPartyMediaAdmissionTest
{
  @org.testng.annotations.BeforeClass
  public void initializeServerStatics() throws Throwable { TestUtils.initializeSageTVForTesting(); }

  @Test public void credentialsAreBoundToOneOwnerMediaModeContextAndProducer()
  {
    AtomicLong now = new AtomicLong();
    AtomicBoolean current = new AtomicBoolean(true), ready = new AtomicBoolean(false);
    NgPartyMediaAdmission admission = new NgPartyMediaAdmission(now::get);
    Object owner = new Object(), other = new Object(), context = new Object(), producer = new Object();
    String handle = admission.issue(owner, 42, "pull-xcode", context, current::get);
    assertEquals(handle.length(), 32);
    assertFalse(admission.confirmed(owner));
    assertFalse(admission.bind(handle, 43, "pull-xcode", null, producer, ready::get));
    assertFalse(admission.bind(handle, 42, "pull", null, producer, ready::get));
    assertFalse(admission.bind(handle, 42, "pull-xcode", other, producer, ready::get));
    assertTrue(admission.bind(handle, 42, "pull-xcode", null, producer, ready::get));
    String otherHandle = admission.issue(other, 42, "pull-xcode", context, current::get);
    assertFalse(admission.bind(otherHandle, 42, "pull-xcode", null, producer, ready::get),
        "One producer cannot confirm two viewers even with two valid credentials");
    assertFalse(admission.bind(handle, 42, "pull-xcode", null, other, ready::get));
    assertFalse(admission.confirmed(owner));
    ready.set(true);
    assertTrue(admission.confirmed(owner));
    assertFalse(admission.confirmed(other));
    ready.set(false);
    assertFalse(admission.confirmed(owner), "Downstream failure immediately withdraws readiness");
    ready.set(true);
    current.set(false);
    assertFalse(admission.confirmed(owner));
    assertFalse(admission.bind(handle, 42, "pull-xcode", null, producer, ready::get));
  }

  @Test public void expiryRotationCloseAndDisconnectPreventCredentialReplay()
  {
    AtomicLong now = new AtomicLong();
    NgPartyMediaAdmission admission = new NgPartyMediaAdmission(now::get);
    Object owner = new Object(), producer = new Object();
    String expired = admission.issue(owner, 42, "pull", null, () -> true);
    now.set(60000);
    assertFalse(admission.bind(expired, 42, "pull", null, producer, () -> true));
    String old = admission.issue(owner, 42, "pull", null, () -> true);
    String next = admission.issue(owner, 42, "pull", null, () -> true);
    assertFalse(admission.bind(old, 42, "pull", null, producer, () -> true));
    assertTrue(admission.bind(next, 42, "pull", null, producer, () -> true));
    now.addAndGet(60001);
    assertTrue(admission.confirmed(owner), "A live bound producer outlives the unused-ticket TTL");
    admission.closed(producer);
    assertFalse(admission.confirmed(owner));
    assertFalse(admission.bind(next, 42, "pull", null, producer, () -> true));
    next = admission.issue(owner, 42, "pull", null, () -> true);
    admission.revoke(owner);
    assertFalse(admission.bind(next, 42, "pull", null, producer, () -> true));
    for (int i = 0; i < 80; i++) admission.issue(new Object(), 42, "pull", null, () -> true);
    assertThrows(PartyProtocol.Fault.class,
        () -> admission.issue(new Object(), 42, "pull", null, () -> true));
  }

  @Test public void actualMediaServerBindingWaitsForOutputAndDeniesFailedOrReplacedProducer()
  {
    Object owner = new Object();
    MediaFile media = mock(MediaFile.class);
    doReturn(42).when(media).getID();
    MediaServer.Connection connection = mock(MediaServer.class).new Connection(null);
    FFMPEGTranscoder engine = mock(FFMPEGTranscoder.class);
    connection.currMF = media; connection.xcoder = engine;
    String handle = NgPartyMediaAdmission.INSTANCE.issue(owner, 42, "pull-xcode", null, () -> true);
    try
    {
      assertTrue(connection.bindPartyAdmission(handle));
      assertFalse(NgPartyMediaAdmission.INSTANCE.confirmed(owner));
      doReturn(true).when(engine).isTranscoding();
      assertFalse(NgPartyMediaAdmission.INSTANCE.confirmed(owner), "Allocation without output is not readiness");
      doReturn(2048L).when(engine).getVirtualTranscodeSize();
      assertTrue(NgPartyMediaAdmission.INSTANCE.confirmed(owner));
      doReturn(true).when(engine).isTranscodeDone();
      assertFalse(NgPartyMediaAdmission.INSTANCE.confirmed(owner));
      connection.xcoder = mock(FFMPEGTranscoder.class);
      assertFalse(NgPartyMediaAdmission.INSTANCE.confirmed(owner));
      connection.closeFile();
      assertFalse(connection.bindPartyAdmission(handle));
    }
    finally { NgPartyMediaAdmission.INSTANCE.revoke(owner); }
  }

  @Test public void actualOpenAndTranscodeStartupFailureNeverConfirmAdmission() throws Exception
  {
    Object owner = new Object();
    MediaFile media = mock(MediaFile.class); doReturn(42).when(media).getID();
    MediaServer.Connection connection = spy(mock(MediaServer.class).new Connection(null));
    doAnswer(call -> { connection.currMF = media; return true; }).when(connection).checkFileAccess();
    String missing = java.nio.file.Path.of("party-admission-missing-source-not-created").toAbsolutePath().toString();
    String direct = NgPartyMediaAdmission.INSTANCE.issue(owner, 42, "pull", null, () -> true);
    assertThrows(java.io.IOException.class, () -> connection.openFile(missing));
    assertFalse(connection.bindPartyAdmission(direct));
    assertFalse(NgPartyMediaAdmission.INSTANCE.confirmed(owner));
    FFMPEGTranscoder engine = mock(FFMPEGTranscoder.class);
    connection.xcoder = engine;
    doThrow(new java.io.IOException("resource denied")).when(engine).startTranscode();
    String xcode = NgPartyMediaAdmission.INSTANCE.issue(owner, 42, "pull-xcode", null, () -> true);
    try
    {
      assertThrows(java.io.IOException.class, () -> connection.openFile(missing));
      assertTrue(connection.bindPartyAdmission(xcode));
      assertFalse(NgPartyMediaAdmission.INSTANCE.confirmed(owner));
      connection.closeFile();
      assertFalse(connection.bindPartyAdmission(xcode));
    }
    finally { NgPartyMediaAdmission.INSTANCE.revoke(owner); }
  }

  @Test public void optionalMediaCommandAndHttpHeaderAreRedactedWithoutChangingOrdinaryCommands() throws Exception
  {
    String handle = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    assertFalse(MediaServer.safePartyCommandForLog("PARTY_BIND " + handle).contains(handle));
    assertFalse(MediaServer.safePartyCommandForLog("bad party_bind " + handle).contains(handle));
    assertEquals(MediaServer.safePartyCommandForLog("XCODE_SETUP browserhd"), "XCODE_SETUP browserhd");
    assertEquals(MediaServer.safePartyCommandForLog("SIZE"), "SIZE");
    HTTPLSServer server = mock(HTTPLSServer.class, CALLS_REAL_METHODS);
    Method sanitize = HTTPLSServer.class.getDeclaredMethod("sanitizeHeaderMapForLog", Map.class);
    sanitize.setAccessible(true);
    Map<String, String> headers = new java.util.HashMap<>();
    headers.put("x-sagetv-party-admission", handle);
    headers.put("range", "bytes=0-");
    Map<?, ?> sanitized = (Map<?, ?>)sanitize.invoke(server, headers);
    assertEquals(sanitized.get("x-sagetv-party-admission"), "<redacted>");
    assertEquals(sanitized.get("range"), "bytes=0-");
    assertEquals(headers.get("x-sagetv-party-admission"), handle);
    Method bind = HTTPLSServer.class.getDeclaredMethod("bindPartyAdmission");
    bind.setAccessible(true);
    bind.invoke(server);
  }

  @Test public void directFileProducerRequiresAnOpenChannelAndCloseRevokesProof()
  {
    Object owner = new Object();
    MediaFile media = mock(MediaFile.class);
    doReturn(42).when(media).getID();
    MediaServer.Connection connection = mock(MediaServer.class).new Connection(null);
    connection.currMF = media;
    java.nio.channels.FileChannel channel = mock(java.nio.channels.FileChannel.class);
    doReturn(true).when(channel).isOpen();
    connection.fileChannel = channel;
    String handle = NgPartyMediaAdmission.INSTANCE.issue(owner, 42, "pull", null, () -> true);
    try
    {
      assertTrue(connection.bindPartyAdmission(handle));
      assertTrue(NgPartyMediaAdmission.INSTANCE.confirmed(owner));
      connection.closeFile();
      assertFalse(NgPartyMediaAdmission.INSTANCE.confirmed(owner));
      assertFalse(connection.bindPartyAdmission(handle));
    }
    finally { NgPartyMediaAdmission.INSTANCE.revoke(owner); }
  }

  private static void set(Object target, String name, Object value) throws Exception
  {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true); field.set(target, value);
  }

  @Test public void actualHlsBindingRequiresMatchingRendererContextProducedMediaAndLiveCachedSession() throws Exception
  {
    Object owner = new Object();
    VideoFrame video = mock(VideoFrame.class);
    MediaFile media = mock(MediaFile.class); doReturn(42).when(media).getID();
    FFMPEGTranscoder transcoder = mock(FFMPEGTranscoder.class);
    Class<?> type = Class.forName("sage.HTTPLSServer$XCodeInfo");
    java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor();
    constructor.setAccessible(true);
    Object info = constructor.newInstance();
    set(info, "mf", media); set(info, "vf", video); set(info, "transcoder", transcoder);
    set(info, "sessionID", "party-admission-test");
    Field cacheField = HTTPLSServer.class.getDeclaredField("cachedXCodeMap");
    cacheField.setAccessible(true);
    @SuppressWarnings("unchecked") Map<String, Object> cache = (Map<String, Object>)cacheField.get(null);
    HTTPLSServer server = mock(HTTPLSServer.class, CALLS_REAL_METHODS);
    Field xcode = HTTPLSServer.class.getDeclaredField("xcode");
    xcode.setAccessible(true); xcode.set(server, info);
    Field header = HTTPLSServer.class.getDeclaredField("partyAdmissionHandle");
    header.setAccessible(true);
    Method bind = HTTPLSServer.class.getDeclaredMethod("bindPartyAdmission");
    bind.setAccessible(true);
    String handle = NgPartyMediaAdmission.INSTANCE.issue(owner, 42, "hls", video, () -> true);
    header.set(server, handle);
    cache.put("party-admission-test", info);
    try
    {
      bind.invoke(server);
      doReturn(true).when(transcoder).isTranscoding();
      assertFalse(NgPartyMediaAdmission.INSTANCE.confirmed(owner));
      set(info, "partyOutputConfirmed", true);
      assertTrue(NgPartyMediaAdmission.INSTANCE.confirmed(owner));
      doReturn(true).when(transcoder).isTranscodeDone();
      assertFalse(NgPartyMediaAdmission.INSTANCE.confirmed(owner));
      doReturn(true).when(transcoder).didTranscodeCompleteOK();
      assertFalse(NgPartyMediaAdmission.INSTANCE.confirmed(owner), "A failed grant cannot revive");
      cache.remove("party-admission-test");
      assertFalse(NgPartyMediaAdmission.INSTANCE.confirmed(owner));
    }
    finally { cache.remove("party-admission-test"); NgPartyMediaAdmission.INSTANCE.revoke(owner); }
  }
}
