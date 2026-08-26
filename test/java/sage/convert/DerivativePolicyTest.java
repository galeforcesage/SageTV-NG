package sage.convert;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.testng.annotations.Test;

import static org.testng.Assert.*;

/**
 * Unit tests for the derivative reuse / original-safety / reclamation policy
 * (Layer B2). Pure logic — no store, no filesystem.
 */
public class DerivativePolicyTest
{
  private static DerivativeRecord ready(String muxer, String codec, int w, int h, double fps,
      boolean hdr, ConversionPurpose purpose, RetentionPolicy ret, long created, boolean preferred)
  {
    DerivativeRecord r = DerivativeRecord.builder()
        .sourceMediaFileId(1).outputPath("d").purpose(purpose)
        .containerMuxer(muxer).videoCodec(codec).width(w).height(h).fps(fps).hdr(hdr)
        .retention(ret).createdTimeMillis(created).preferred(preferred).byteSize(100).build();
    r.advanceTo(DerivativeState.VALIDATING);
    r.advanceTo(DerivativeState.READY);
    return r;
  }

  // ---- Reuse --------------------------------------------------------------

  @Test
  public void testFindReusableMatchesGeometryAndCodec()
  {
    DerivativeRecord d = ready("mp4", "h264", 1280, 720, 30, false,
        ConversionPurpose.OFFLINE_DEVICE, RetentionPolicy.TEMPORARY, 1000L, false);
    DerivativeSpec want = new DerivativeSpec("mp4", "h264", 1280, 720, 30, false);
    assertSame(DerivativePolicy.findReusable(want, Arrays.asList(d)), d);
  }

  @Test
  public void testFindReusableIgnoresNonReady()
  {
    DerivativeRecord d = DerivativeRecord.builder()
        .sourceMediaFileId(1).outputPath("d").purpose(ConversionPurpose.OFFLINE_DEVICE)
        .containerMuxer("mp4").videoCodec("h264").width(1280).height(720).fps(30).build();
    // still PREPARING
    DerivativeSpec want = new DerivativeSpec("mp4", "h264", 1280, 720, 30, false);
    assertNull(DerivativePolicy.findReusable(want, Arrays.asList(d)));
  }

  @Test
  public void testFindReusableRejectsDifferentGeometry()
  {
    DerivativeRecord d = ready("mp4", "h264", 1920, 1080, 30, false,
        ConversionPurpose.OFFLINE_DEVICE, RetentionPolicy.TEMPORARY, 1000L, false);
    DerivativeSpec want = new DerivativeSpec("mp4", "h264", 1280, 720, 30, false);
    assertNull(DerivativePolicy.findReusable(want, Arrays.asList(d)));
  }

  @Test
  public void testFindReusablePrefersPreferredThenNewest()
  {
    DerivativeRecord older = ready("mkv", "hevc", 3840, 2160, 24, true,
        ConversionPurpose.ENHANCED_FAVORITE, RetentionPolicy.KEEP_FOREVER, 1000L, false);
    DerivativeRecord newer = ready("mkv", "hevc", 3840, 2160, 24, true,
        ConversionPurpose.ENHANCED_FAVORITE, RetentionPolicy.KEEP_FOREVER, 5000L, false);
    DerivativeRecord pref = ready("mkv", "hevc", 3840, 2160, 24, true,
        ConversionPurpose.ENHANCED_FAVORITE, RetentionPolicy.KEEP_FOREVER, 2000L, true);
    DerivativeSpec want = new DerivativeSpec("mkv", "hevc", 3840, 2160, 24, true);
    assertSame(DerivativePolicy.findReusable(want, Arrays.asList(older, newer, pref)), pref);
  }

  @Test
  public void testCodecEquivalenceAcrossNaming()
  {
    DerivativeRecord d = ready("matroska", "HEVC", 3840, 2160, 24, false,
        ConversionPurpose.ARCHIVE, RetentionPolicy.KEEP_FOREVER, 1000L, false);
    // "h265" should match "hevc"
    DerivativeSpec want = new DerivativeSpec("matroska", "h265", 3840, 2160, 24, false);
    assertSame(DerivativePolicy.findReusable(want, Arrays.asList(d)), d);
  }

  // ---- Original safety ----------------------------------------------------

  @Test
  public void testCannotDeleteSourceWithoutOptIn()
  {
    DerivativeRecord d = ready("mp4", "h264", 1280, 720, 30, false,
        ConversionPurpose.OFFLINE_DEVICE, RetentionPolicy.TEMPORARY, 1000L, false);
    assertFalse(DerivativePolicy.canDeleteSource(false, true, Arrays.asList(d)));
  }

  @Test
  public void testCannotDeleteSourceWithoutReadyDerivative()
  {
    DerivativeRecord prep = DerivativeRecord.builder()
        .sourceMediaFileId(1).outputPath("d").purpose(ConversionPurpose.EXACT_BACKUP)
        .containerMuxer("mkv").videoCodec("copy").width(1920).height(1080).fps(30).build();
    assertFalse(DerivativePolicy.canDeleteSource(true, true, Arrays.asList(prep)));
  }

  @Test
  public void testLossyDeleteNeedsReview()
  {
    DerivativeRecord lossy = ready("mp4", "h264", 1280, 720, 30, false,
        ConversionPurpose.OFFLINE_DEVICE, RetentionPolicy.TEMPORARY, 1000L, false);
    assertFalse(DerivativePolicy.canDeleteSource(true, false, Arrays.asList(lossy)));
    assertTrue(DerivativePolicy.canDeleteSource(true, true, Arrays.asList(lossy)));
  }

  @Test
  public void testExactBackupDeletesWithoutReview()
  {
    DerivativeRecord backup = ready("mkv", "copy", 1920, 1080, 30, false,
        ConversionPurpose.EXACT_BACKUP, RetentionPolicy.KEEP_FOREVER, 1000L, false);
    assertTrue(DerivativePolicy.canDeleteSource(true, false, Arrays.asList(backup)));
  }

  // ---- Reclamation --------------------------------------------------------

  @Test
  public void testReclaimOrderTemporaryFirstNeverKeepForeverOrPreferred()
  {
    DerivativeRecord temp = ready("mp4", "h264", 1280, 720, 30, false,
        ConversionPurpose.TRAVEL, RetentionPolicy.TEMPORARY, 3000L, false);
    DerivativeRecord untilSrc = ready("mkv", "hevc", 3840, 2160, 24, false,
        ConversionPurpose.ENHANCED_FAVORITE, RetentionPolicy.UNTIL_SOURCE_DELETED, 1000L, false);
    DerivativeRecord keep = ready("mkv", "hevc", 3840, 2160, 24, false,
        ConversionPurpose.ARCHIVE, RetentionPolicy.KEEP_FOREVER, 500L, false);
    DerivativeRecord pref = ready("mp4", "h264", 1280, 720, 30, false,
        ConversionPurpose.TRAVEL, RetentionPolicy.TEMPORARY, 100L, true);

    // source gone -> UNTIL_SOURCE_DELETED becomes reclaimable
    List<DerivativeRecord> order = DerivativePolicy.reclaimOrder(
        new ArrayList<DerivativeRecord>(Arrays.asList(keep, untilSrc, temp, pref)), false);

    assertEquals(order.size(), 2);
    assertSame(order.get(0), temp);      // TEMPORARY reclaimed first
    assertSame(order.get(1), untilSrc);  // then UNTIL_SOURCE_DELETED (source gone)
    assertFalse(order.contains(keep));   // KEEP_FOREVER never
    assertFalse(order.contains(pref));   // preferred never
  }

  @Test
  public void testReclaimKeepsUntilSourceDeletedWhileSourceExists()
  {
    DerivativeRecord untilSrc = ready("mkv", "hevc", 3840, 2160, 24, false,
        ConversionPurpose.ENHANCED_FAVORITE, RetentionPolicy.UNTIL_SOURCE_DELETED, 1000L, false);
    List<DerivativeRecord> order = DerivativePolicy.reclaimOrder(Arrays.asList(untilSrc), true);
    assertTrue(order.isEmpty());
  }
}
