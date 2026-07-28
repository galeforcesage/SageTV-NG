package sage;

import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;

import static org.testng.Assert.*;

/**
 * Created by seans on 11/09/16.
 */
public class MediaFileTest
{
  @Test
  public void testCreateValidFilename() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    String fname="A\u2019Test99: Nam\u00E9\u2019Z"; // \u00E9 = é
    boolean allowUnicode,extendedFileName;

    // test with unicode
    validateFile(fname, "ATest99Nam\u00E9Z", allowUnicode=true, extendedFileName=false);
    validateFile(fname, "ATest99 Nam\u00E9Z", allowUnicode=true, extendedFileName=true);

    // test no unicode
    validateFile(fname, "ATest99NameZ", allowUnicode=false, extendedFileName=false);
    validateFile(fname, "ATest99 NameZ", allowUnicode=false, extendedFileName=true);

    // test filename with all LEGAL Characters (will test if file creation fails - lets hope not)
    validateFile(
      "Test - " + MediaFile.LEGAL_FILE_NAME_CHARACTERS,
      "Test - " + MediaFile.LEGAL_FILE_NAME_CHARACTERS, allowUnicode=false, extendedFileName=true);
  }

  public void validateFile(String origName, String expectedName, boolean allowUnicode, boolean extendedFilename) throws IOException
  {
    Sage.put("allow_unicode_characters_in_generated_filenames", String.valueOf(allowUnicode));
    Sage.put("extended_filenames", String.valueOf(extendedFilename));
    String name = MediaFile.createValidFilename(origName);
    System.out.println("TESTING NAME: " + origName + "; allowUnicode: " + allowUnicode + "; extendedFilename: " + extendedFilename + "; RESULT: " + name);
    assertEquals(name, expectedName);
    File file = File.createTempFile(name,"-temp.file");
    assertTrue(file.exists() && file.isFile());
    file.deleteOnExit();
  }

  /**
   * Caption-session lifecycle fix (Issue C): deleting a MediaFile's backing
   * recording must also remove its caption-extraction .srt sidecar, so a
   * later, unrelated recording can never be re-attached to stale captions
   * left over from a deleted partial/ephemeral recording (the sidecar path
   * is derived purely from the recording's file path -- see
   * sage.captions.CaptionExtractionManager#sidecarFor -- so nothing else
   * ties it to the deleted recording's lifecycle unless we do it here).
   */
  @Test
  public void testDeleteRemovesCaptionSidecar() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();

    File recFile = File.createTempFile("CaptionSidecarDeleteTest", ".mpg");
    recFile.deleteOnExit();
    File sidecar = sage.captions.CaptionExtractionManager.sidecarFor(recFile);
    assertTrue(sidecar.createNewFile(), "failed to create test sidecar " + sidecar);
    sidecar.deleteOnExit();
    assertTrue(sidecar.isFile(), "sidecar should exist before delete()");

    MediaFile mf = new MediaFile(Integer.MAX_VALUE - 1);
    mf.files.add(recFile);

    assertTrue(mf.delete(false), "MediaFile.delete() should report success");
    assertFalse(sidecar.exists(), "caption sidecar should be removed when the recording is deleted");
    assertFalse(recFile.exists(), "backing recording file should be removed by delete()");
  }
}
