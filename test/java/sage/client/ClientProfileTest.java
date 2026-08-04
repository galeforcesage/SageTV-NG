package sage.client;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import sage.TestUtils;

import java.util.*;

import static org.testng.Assert.*;

/**
 * Unit tests for the Managed Client Configuration system.
 *
 * Required test cases per PRD:
 * 1. schema_version missing → legacy behavior
 * 2. Managed profile resolution and codec/container clamping
 * 3. HD300 → forced hd_legacy_strict + aggressive remux
 */
public class ClientProfileTest
{
  @BeforeClass
  public void setup() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
  }

  @BeforeMethod
  public void resetProfileManager()
  {
    ClientProfileManager.resetInstance();
  }

  // -----------------------------------------------------------------------
  // Test Case 1: schema_version missing → legacy behavior
  // -----------------------------------------------------------------------
  @Test
  public void testLegacyClient_SchemaVersionMissing()
  {
    ClientProfileManager mgr = ClientProfileManager.getInstance();

    // schema_version = 0 (missing), no profile requested, not an extender
    ClientProfile profile = mgr.resolveProfile(0, null, false, null, null);

    // Legacy clients get null → system uses v1 negotiation path
    assertNull(profile, "Legacy client (schema_version missing) should get null profile to trigger v1 fallback");
  }

  @Test
  public void testLegacyClient_SchemaVersion1()
  {
    ClientProfileManager mgr = ClientProfileManager.getInstance();

    // schema_version = 1 (below v2 threshold)
    ClientProfile profile = mgr.resolveProfile(1, "desktop_default", false, null, null);

    // Still legacy — schema_version < 2
    assertNull(profile, "Client with schema_version=1 should get null profile (legacy v1 path)");
  }

  // -----------------------------------------------------------------------
  // Test Case 2: managed profile codec/container clamping
  // (the old static browser ceiling was removed — the only browser client,
  //  PWA, is a full NG client routed by real capability; no static ceiling remains)
  // -----------------------------------------------------------------------

  // -----------------------------------------------------------------------
  // Test Case 3: HD300 → forced hd_legacy_strict + aggressive remux
  // -----------------------------------------------------------------------
  @Test
  public void testHD300_ForcedLegacyStrict()
  {
    ClientProfileManager mgr = ClientProfileManager.getInstance();

    // HD300 extender with firmware "HD300-2.0.1", isExtender=true
    // Even if it requests a different profile, it should be forced to hd_legacy_strict
    ClientProfile profile = mgr.resolveProfile(2, "desktop_default", true, "HD300-2.0.1", null);

    assertNotNull(profile, "HD300 should always get a profile (hd_legacy_strict)");
    assertEquals(profile.getProfileId(), "hd_legacy_strict",
        "HD300 must be forced to hd_legacy_strict regardless of requested profile");

    // Must not allow HEVC
    assertFalse(profile.isAllowHevc(), "HD300 must never receive HEVC");
    assertFalse(profile.isVideoCodecAllowed("HEVC"), "HD300 must not support HEVC codec");

    // Must have aggressive auto-remux
    assertTrue(profile.isAutoRemuxEnabled(), "HD300 must have auto-remux enabled");
    assertTrue(profile.isAutoRemuxAggressive(), "HD300 must use aggressive remux");

    // Must not allow client overrides
    assertFalse(profile.isAllowClientOverrides(), "HD300 must not allow client overrides");

    // Not managed
    assertFalse(profile.isManaged(), "HD300 must be unmanaged");
  }

  @Test
  public void testHD300_RiskyFormatTriggersRemux()
  {
    ClientProfileManager mgr = ClientProfileManager.getInstance();
    ClientProfile profile = mgr.resolveProfile(2, "desktop_default", true, "HD300-2.0.1", null);

    // H264 in TS is risky for HDx00 — should prefer remux even if format is "supported"
    PlaybackDecisionEngine.PlaybackDecision decision =
        PlaybackDecisionEngine.evaluate(profile, "MPEG2-TS", "H264", "AC3", 1920, 1080, true);

    assertEquals(decision.decision, PlaybackDecisionEngine.Decision.REMUX,
        "HD300 should prefer remux for risky TS+H264 combination");
    assertTrue(decision.reason.contains("risk"), "Reason should mention risk");
  }

  @Test
  public void testHD200_AlsoForcedLegacyStrict()
  {
    ClientProfileManager mgr = ClientProfileManager.getInstance();

    ClientProfile profile = mgr.resolveProfile(2, "android_modern", true, "HD200-1.5.3", null);

    assertEquals(profile.getProfileId(), "hd_legacy_strict",
        "HD200 must also be forced to hd_legacy_strict");
  }

  // -----------------------------------------------------------------------
  // Additional validation tests
  // -----------------------------------------------------------------------
  @Test
  public void testDesktopDefault_SupportsMultipleContainers()
  {
    ClientProfileManager mgr = ClientProfileManager.getInstance();
    ClientProfile profile = mgr.resolveProfile(2, "desktop_default", false, null, null);

    assertNotNull(profile);
    assertTrue(profile.isContainerAllowed("MP4"));
    assertTrue(profile.isContainerAllowed("MKV"));
    assertTrue(profile.isContainerAllowed("MPEG2-TS"));
    assertTrue(profile.isVideoCodecAllowed("H264"));
    assertFalse(profile.isAllowHevc());
  }

  @Test
  public void testAndroidModern_SupportsHEVC()
  {
    ClientProfileManager mgr = ClientProfileManager.getInstance();
    ClientProfile profile = mgr.resolveProfile(2, "android_modern", false, null, null);

    assertNotNull(profile);
    assertTrue(profile.isAllowHevc(), "android_modern should allow HEVC");
    assertTrue(profile.isVideoCodecAllowed("HEVC"), "android_modern should support HEVC codec");
    assertTrue(profile.isVideoCodecAllowed("H264"), "android_modern should support H264 codec");
  }

  @Test
  public void testClientOverrides_Respected()
  {
    ClientProfileManager mgr = ClientProfileManager.getInstance();

    Map<String, String> overrides = new HashMap<>();
    overrides.put("auto_remux", "aggressive");
    ClientProfile profile = mgr.resolveProfile(2, "desktop_default", false, null, overrides);

    assertNotNull(profile);
    assertTrue(profile.isAutoRemuxAggressive(), "Client override to aggressive remux should be respected");
  }

  @Test
  public void testClientOverrides_UnknownKeysIgnored()
  {
    ClientProfileManager mgr = ClientProfileManager.getInstance();

    Map<String, String> overrides = new HashMap<>();
    overrides.put("unknown_future_key", "some_value");
    overrides.put("another_bad_key", "whatever");
    ClientProfile profile = mgr.resolveProfile(2, "desktop_default", false, null, overrides);

    // Should not throw, should resolve normally
    assertNotNull(profile);
    assertEquals(profile.getProfileId(), "desktop_default");
  }

  @Test
  public void testHEVCOverride_OnlyIfProfileAllows()
  {
    ClientProfileManager mgr = ClientProfileManager.getInstance();

    // Try to enable HEVC on hd_legacy_strict (which doesn't allow it). A client
    // override must never exceed the profile's HEVC ceiling.
    Map<String, String> overrides = new HashMap<>();
    overrides.put("allow_hevc", "true");
    ClientProfile profile = mgr.resolveProfile(2, "hd_legacy_strict", false, null, overrides);

    assertFalse(profile.isAllowHevc(), "hd_legacy_strict should not allow HEVC even if client requests it");
  }

  @Test
  public void testUnknownProfile_FallsBackToDesktopDefault()
  {
    ClientProfileManager mgr = ClientProfileManager.getInstance();

    ClientProfile profile = mgr.resolveProfile(2, "nonexistent_profile_xyz", false, null, null);

    assertNotNull(profile, "Unknown profile should fall back to desktop_default");
    assertEquals(profile.getProfileId(), "desktop_default");
  }

  @Test
  public void testDirectPlay_WhenAllCompatible()
  {
    ClientProfileManager mgr = ClientProfileManager.getInstance();
    ClientProfile profile = mgr.resolveProfile(2, "desktop_default", false, null, null);

    PlaybackDecisionEngine.PlaybackDecision decision =
        PlaybackDecisionEngine.evaluate(profile, "MP4", "H264", "AAC", 1920, 1080, false);

    assertEquals(decision.decision, PlaybackDecisionEngine.Decision.DIRECT_PLAY);
  }

  @Test
  public void testAvailableProfiles_ContainsAllRequired()
  {
    ClientProfileManager mgr = ClientProfileManager.getInstance();
    Collection<String> ids = mgr.getAvailableProfileIds();

    assertTrue(ids.contains("hd_legacy_strict"), "Must have hd_legacy_strict");
    assertTrue(ids.contains("desktop_default"), "Must have desktop_default");
    assertTrue(ids.contains("desktop_hevc_optin"), "Must have desktop_hevc_optin");
    assertTrue(ids.contains("android_modern"), "Must have android_modern");
  }

  // -----------------------------------------------------------------------
  // Integration tests: profile clamping of existing codec/container sets
  // These simulate what MiniClientSageRenderer.initMini() does after
  // resolving a profile — intersecting client-reported capabilities with
  // the profile's allowed codecs/containers.
  // -----------------------------------------------------------------------

  @Test
  public void testProfileClamping_HD300_ClampsHEVC()
  {
    ClientProfileManager mgr = ClientProfileManager.getInstance();
    // HD300 forces hd_legacy_strict
    ClientProfile profile = mgr.resolveProfile(2, "android_modern", true, "HD300-2.0.1", null);

    // Even if the client hardware reports HEVC support
    Set<String> clientVideoCodecs = new HashSet<>(Arrays.asList("H.264", "HEVC"));

    // Clamp
    clientVideoCodecs.retainAll(profile.getVideoCodecs());
    if (!profile.isAllowHevc())
    {
      clientVideoCodecs.remove("HEVC");
      clientVideoCodecs.remove("H265");
    }

    // HEVC must be gone
    assertFalse(clientVideoCodecs.contains("HEVC"), "HD300 must not have HEVC after clamping");
    assertTrue(clientVideoCodecs.contains("H.264"), "HD300 must keep H.264");
  }

  @Test
  public void testProfileClamping_DesktopDefault_RetainsMultipleContainers()
  {
    ClientProfileManager mgr = ClientProfileManager.getInstance();
    ClientProfile profile = mgr.resolveProfile(2, "desktop_default", false, null, null);

    Set<String> clientPushContainers = new HashSet<>(Arrays.asList("MPEG2-PS", "MPEG2-TS", "MP4", "MKV", "AVI"));

    clientPushContainers.retainAll(profile.getContainers());

    // desktop_default allows MP4, MKV, MPEG2-TS, MPEG2-PS but not AVI
    assertTrue(clientPushContainers.contains("MP4"));
    assertTrue(clientPushContainers.contains("MKV"));
    assertTrue(clientPushContainers.contains("MPEG2-TS"));
    assertTrue(clientPushContainers.contains("MPEG2-PS"), "desktop_default must include MPEG2-PS for push mode");
    assertFalse(clientPushContainers.contains("AVI"), "desktop_default should not include AVI");
  }

  @Test
  public void testProfileClamping_LegacyClient_NoChange()
  {
    ClientProfileManager mgr = ClientProfileManager.getInstance();
    // Legacy client — resolveProfile returns null
    ClientProfile profile = mgr.resolveProfile(0, null, false, null, null);

    // Simulate existing client-reported codecs
    Set<String> clientVideoCodecs = new HashSet<>(Arrays.asList("H.264", "MPEG2-VIDEO", "MPEG4"));

    // No profile → no clamping should occur
    if (profile != null)
    {
      clientVideoCodecs.retainAll(profile.getVideoCodecs());
    }

    // Original set should be unchanged
    assertEquals(clientVideoCodecs.size(), 3, "Legacy client codecs must not be modified");
    assertTrue(clientVideoCodecs.contains("H.264"));
    assertTrue(clientVideoCodecs.contains("MPEG2-VIDEO"));
    assertTrue(clientVideoCodecs.contains("MPEG4"));
  }

  @Test
  public void testProfileClamping_AndroidModern_KeepsHEVC()
  {
    ClientProfileManager mgr = ClientProfileManager.getInstance();
    ClientProfile profile = mgr.resolveProfile(2, "android_modern", false, null, null);

    Set<String> clientVideoCodecs = new HashSet<>(Arrays.asList("H.264", "HEVC"));

    clientVideoCodecs.retainAll(profile.getVideoCodecs());
    // android_modern allows HEVC
    assertTrue(clientVideoCodecs.contains("HEVC"), "android_modern should keep HEVC");
    assertTrue(clientVideoCodecs.contains("H.264"), "android_modern should keep H.264");
  }

  // -----------------------------------------------------------------------
  // Auto-detection tests: server-side profile assignment without client changes
  // -----------------------------------------------------------------------

  @Test
  public void testAutoDetect_DesktopPlaceshifter()
  {
    ClientProfileManager mgr = ClientProfileManager.getInstance();

    // Desktop placeshifter: has MOUSE (isExtender=false), no HEVC, no iOS
    Set<String> videoCodecs = new HashSet<>(Arrays.asList("H264", "MPEG2-VIDEO"));
    ClientProfile profile = mgr.autoDetectProfile("aabbccddee01", false, false, "", videoCodecs, null);

    assertNotNull(profile);
    assertEquals(profile.getProfileId(), "desktop_default",
        "Desktop placeshifter without HEVC should auto-detect to desktop_default");
  }

  @Test
  public void testAutoDetect_DesktopWithHEVC()
  {
    ClientProfileManager mgr = ClientProfileManager.getInstance();

    // Desktop with HEVC support
    Set<String> videoCodecs = new HashSet<>(Arrays.asList("H264", "HEVC", "MPEG2-VIDEO"));
    ClientProfile profile = mgr.autoDetectProfile("aabbccddee02", false, false, "", videoCodecs, null);

    assertNotNull(profile);
    assertEquals(profile.getProfileId(), "desktop_hevc_optin",
        "Desktop placeshifter with HEVC should auto-detect to desktop_hevc_optin");
  }

  @Test
  public void testAutoDetect_NgPwaClient_Hevc_RoutesToDesktopHevcOptin()
  {
    ClientProfileManager mgr = ClientProfileManager.getInstance();

    // NG client, isIOS=true (GFX_FIXED_PAR rendering hint), HEVC advertised,
    // non-extender, ngVersion 1.0. GFX_FIXED_PAR is NOT a capability signal, so
    // the client must be routed by its real caps → desktop_hevc_optin,
    // NOT clamped to a static browser ceiling.
    Set<String> videoCodecs = new HashSet<>(Arrays.asList("H264", "HEVC"));
    Set<String> audioCodecs = new HashSet<>(Arrays.asList("AAC", "EAC3", "AC4"));
    ClientProfile profile = mgr.autoDetectProfile("aabbccddee0a", false, true, "", "1.0",
        videoCodecs, audioCodecs, null);

    assertNotNull(profile);
    assertEquals(profile.getProfileId(), "desktop_hevc_optin",
        "NG isIOS client with HEVC must route by capability to desktop_hevc_optin");
  }

  @Test
  public void testAutoDetect_NgPwaClient_NoHevc_RoutesToDesktopDefault()
  {
    ClientProfileManager mgr = ClientProfileManager.getInstance();

    // NG client, isIOS=true, NO HEVC, non-extender, ngVersion 1.0 → routed by
    // capability to desktop_default.
    Set<String> videoCodecs = new HashSet<>(Arrays.asList("H264"));
    Set<String> audioCodecs = new HashSet<>(Arrays.asList("AAC", "EAC3"));
    ClientProfile profile = mgr.autoDetectProfile("aabbccddee0c", false, true, "", "1.0",
        videoCodecs, audioCodecs, null);

    assertNotNull(profile);
    assertEquals(profile.getProfileId(), "desktop_default",
        "NG isIOS client without HEVC must route by capability to desktop_default");
  }

  @Test
  public void testAutoDetect_NonNgIsIos_RoutesByCapability()
  {
    ClientProfileManager mgr = ClientProfileManager.getInstance();

    // GFX_FIXED_PAR (isIOS) is only a rendering hint and no longer selects a
    // profile. There is no non-NG browser client, but a non-NG isIOS client
    // (ngVersion=null) now also routes by real capability: H264-only + non-extender
    // → desktop_default. The old static browser ceiling has been removed entirely.
    Set<String> videoCodecs = new HashSet<>(Arrays.asList("H264"));
    Set<String> audioCodecs = new HashSet<>(Arrays.asList("AAC"));
    ClientProfile profile = mgr.autoDetectProfile("aabbccddee0b", false, true, "", null,
        videoCodecs, audioCodecs, null);

    assertNotNull(profile);
    assertEquals(profile.getProfileId(), "desktop_default",
        "isIOS is a rendering hint only; client must route by capability to desktop_default");
  }

  @Test
  public void testAutoDetect_HD300Extender()
  {
    ClientProfileManager mgr = ClientProfileManager.getInstance();

    // HD300: isExtender=true, firmware contains HD300
    Set<String> videoCodecs = new HashSet<>(Arrays.asList("H264", "MPEG2-VIDEO"));
    ClientProfile profile = mgr.autoDetectProfile("aabbccddee04", true, false, "HD300-2.0.1", videoCodecs, null);

    assertNotNull(profile);
    assertEquals(profile.getProfileId(), "hd_legacy_strict",
        "HD300 should auto-detect to hd_legacy_strict");
    assertFalse(profile.isAllowHevc(), "HD300 must not allow HEVC");
    assertTrue(profile.isAutoRemuxAggressive(), "HD300 must use aggressive remux");
  }

  @Test
  public void testAutoDetect_AndroidMiniClient()
  {
    ClientProfileManager mgr = ClientProfileManager.getInstance();

    // Android MiniClient: isExtender=true (no MOUSE), has HEVC, no HD firmware
    Set<String> videoCodecs = new HashSet<>(Arrays.asList("H264", "HEVC"));
    ClientProfile profile = mgr.autoDetectProfile("aabbccddee05", true, false, "", videoCodecs, null);

    assertNotNull(profile);
    assertEquals(profile.getProfileId(), "android_modern",
        "Android MiniClient with HEVC and no MOUSE should auto-detect to android_modern");
    assertTrue(profile.isAllowHevc(), "android_modern must allow HEVC");
  }

  @Test
  public void testAutoDetect_UnknownExtenderNoHEVC()
  {
    ClientProfileManager mgr = ClientProfileManager.getInstance();

    // Unknown extender: no MOUSE, no HEVC, no HD firmware
    Set<String> videoCodecs = new HashSet<>(Arrays.asList("H264", "MPEG2-VIDEO"));
    ClientProfile profile = mgr.autoDetectProfile("aabbccddee06", true, false, "", videoCodecs, null);

    assertNotNull(profile);
    assertEquals(profile.getProfileId(), "hd_legacy_strict",
        "Unknown extender without HEVC should fall back to hd_legacy_strict for safety");
  }

  @Test
  public void testAutoDetect_AdminOverride()
  {
    ClientProfileManager mgr = ClientProfileManager.getInstance();

    // Set admin override in properties
    sage.Sage.put("miniclient/profile/aabbccddee07", "hd_legacy_strict");
    try
    {
      // This would normally be desktop_hevc_optin (non-extender + HEVC), but admin overrides it
      Set<String> videoCodecs = new HashSet<>(Arrays.asList("H264", "HEVC", "MPEG2-VIDEO"));
      ClientProfile profile = mgr.autoDetectProfile("aabbccddee07", false, false, "", videoCodecs, null);

      assertNotNull(profile);
      assertEquals(profile.getProfileId(), "hd_legacy_strict",
          "Admin override should take precedence over auto-detection");
    }
    finally
    {
      // Clean up
      sage.Sage.put("miniclient/profile/aabbccddee07", "");
    }
  }

  @Test
  public void testAutoDetect_AdminOverride_InvalidProfile()
  {
    ClientProfileManager mgr = ClientProfileManager.getInstance();

    // Set admin override to a non-existent profile
    sage.Sage.put("miniclient/profile/aabbccddee08", "nonexistent_profile");
    try
    {
      Set<String> videoCodecs = new HashSet<>(Arrays.asList("H264"));
      ClientProfile profile = mgr.autoDetectProfile("aabbccddee08", false, false, "", videoCodecs, null);

      assertNotNull(profile);
      // Should fall through to auto-detection since override profile doesn't exist
      assertEquals(profile.getProfileId(), "desktop_default",
          "Invalid admin override should fall through to auto-detection");
    }
    finally
    {
      sage.Sage.put("miniclient/profile/aabbccddee08", "");
    }
  }
}
