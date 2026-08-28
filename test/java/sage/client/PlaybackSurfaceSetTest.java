package sage.client;

import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.testng.Assert.*;

/**
 * Tests for {@link PlaybackSurfaceSet} attribute parsing on the per-surface
 * {@code VIDEO_CODECS} channel -- specifically the interlaced-decode dimension
 * a browser/MSE surface declares as {@code H264;scan=progressive;interlaced=false}.
 *
 * <p>Two things must hold: the attribute-carrying token must still be accepted
 * as its bare canonical codec (it must NOT be dropped as non-canonical, which
 * would strip H264 from the surface and could drop the whole surface), and the
 * interlaced=false / scan=progressive attribute must be retained so the
 * decision engine can escalate an interlaced source to a deinterlacing
 * transcode.
 */
public class PlaybackSurfaceSetTest
{
  @Test
  public void stripCodecAttributes_returnsBareCodec()
  {
    List<String> out = PlaybackSurfaceSet.stripCodecAttributes(
        Arrays.asList("H264;scan=progressive;interlaced=false", "HEVC"));
    assertEquals(out, Arrays.asList("H264", "HEVC"),
        "Attributes after ';' must be stripped so canonical validation sees the bare codec");
  }

  @Test
  public void parseInterlacedUnsupported_capturesInterlacedFalse()
  {
    Set<String> out = PlaybackSurfaceSet.parseInterlacedUnsupported("pwa_mse",
        Arrays.asList("H264;scan=progressive;interlaced=false", "HEVC"));
    assertTrue(out.contains("H264"), "interlaced=false must mark H264 as progressive-only");
    assertFalse(out.contains("HEVC"), "A bare codec token declares nothing about interlacing");
  }

  @Test
  public void parseInterlacedUnsupported_scanProgressiveAlone()
  {
    Set<String> out = PlaybackSurfaceSet.parseInterlacedUnsupported("s",
        Arrays.asList("H264;scan=progressive"));
    assertTrue(out.contains("H264"), "scan=progressive alone must also mark the codec progressive-only");
  }

  @Test
  public void parseInterlacedUnsupported_interlacedTrue_isNotFlagged()
  {
    Set<String> out = PlaybackSurfaceSet.parseInterlacedUnsupported("s",
        Arrays.asList("H264;interlaced=true"));
    assertFalse(out.contains("H264"), "interlaced=true declares the codec CAN decode interlaced; do not flag it");
  }

  @Test
  public void build_attributeCarryingSurface_retainsCodecAndInterlacedFlag()
  {
    // Reproduces the real pwa_mse contract: the VIDEO_CODECS reply carries
    // attributes. Positional array: [ROUTE, PRIORITY, DELIVERY_MODES,
    // VIDEO_CODECS, AUDIO_CODECS, CONTAINERS, ...].
    final String[] props = new String[] {
        "mse", "10", "pull-xcode",
        "H264;scan=progressive;interlaced=false", "AAC", "MP4",
        "", "", "", "", "", ""
    };
    PlaybackSurfaceSet set = PlaybackSurfaceSet.build("pwa_mse",
        new java.util.function.Function<String, String[]>() {
          @Override public String[] apply(String sid) { return props; }
        });
    PlaybackSurface s = set.get("pwa_mse");
    assertNotNull(s, "The surface must survive build despite the attribute-carrying codec token");
    assertTrue(s.supportsVideoCodec("H264"),
        "The bare H264 codec must remain decodable after attribute stripping");
    assertTrue(s.declaresInterlacedUnsupported("H264"),
        "The surface must retain the client's interlaced=false declaration for H264");
  }
}
