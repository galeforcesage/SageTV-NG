package sage.convert;

import org.testng.annotations.Test;

import static org.testng.Assert.*;

/**
 * Unit tests for the derivative lifecycle: state machine, validation, and record
 * behavior (Layer B1). Pure logic — no ffprobe, no Wiz.bin, no server.
 */
public class DerivativeLifecycleTest
{
  // ---- State machine ------------------------------------------------------

  @Test
  public void testLegalHappyPath()
  {
    assertTrue(DerivativeStateMachine.isLegalTransition(DerivativeState.PREPARING, DerivativeState.VALIDATING));
    assertTrue(DerivativeStateMachine.isLegalTransition(DerivativeState.VALIDATING, DerivativeState.READY));
  }

  @Test
  public void testFailureTransitions()
  {
    assertTrue(DerivativeStateMachine.isLegalTransition(DerivativeState.PREPARING, DerivativeState.FAILED));
    assertTrue(DerivativeStateMachine.isLegalTransition(DerivativeState.VALIDATING, DerivativeState.FAILED));
  }

  @Test
  public void testIllegalSkipAndTerminal()
  {
    // cannot skip validation
    assertFalse(DerivativeStateMachine.isLegalTransition(DerivativeState.PREPARING, DerivativeState.READY));
    // cannot leave a terminal state
    assertFalse(DerivativeStateMachine.isLegalTransition(DerivativeState.READY, DerivativeState.VALIDATING));
    assertFalse(DerivativeStateMachine.isLegalTransition(DerivativeState.FAILED, DerivativeState.PREPARING));
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void testRequireTransitionThrowsOnIllegal()
  {
    DerivativeStateMachine.requireTransition(DerivativeState.PREPARING, DerivativeState.READY);
  }

  // ---- Record lifecycle ---------------------------------------------------

  private static DerivativeRecord rec()
  {
    return DerivativeRecord.builder()
        .sourceMediaFileId(42).outputPath("out.mp4").purpose(ConversionPurpose.OFFLINE_DEVICE)
        .containerMuxer("mp4").videoCodec("H264").width(1280).height(720).fps(29.97)
        .audioSummary("AAC stereo").byteSize(0).build();
  }

  @Test
  public void testRecordStartsPreparingNotReady()
  {
    DerivativeRecord r = rec();
    assertEquals(r.getState(), DerivativeState.PREPARING);
    assertFalse(r.isOfflineReady());
  }

  @Test
  public void testRecordAdvancesToReady()
  {
    DerivativeRecord r = rec();
    r.advanceTo(DerivativeState.VALIDATING);
    r.advanceTo(DerivativeState.READY);
    assertTrue(r.isOfflineReady());
    assertEquals(r.getFailureReason(), "");
  }

  @Test
  public void testRecordApplyValidationPass()
  {
    DerivativeRecord r = rec();
    r.advanceTo(DerivativeState.VALIDATING);
    r.applyValidation(ValidationResult.pass());
    assertTrue(r.isOfflineReady());
  }

  @Test
  public void testRecordApplyValidationFailCarriesReason()
  {
    DerivativeRecord r = rec();
    r.advanceTo(DerivativeState.VALIDATING);
    r.applyValidation(ValidationResult.fail("truncated"));
    assertEquals(r.getState(), DerivativeState.FAILED);
    assertFalse(r.isOfflineReady());
    assertEquals(r.getFailureReason(), "truncated");
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void testRecordCannotSkipValidation()
  {
    rec().advanceTo(DerivativeState.READY);
  }

  @Test
  public void testRetentionDefaultsByPurpose()
  {
    assertEquals(RetentionPolicy.defaultFor(ConversionPurpose.ARCHIVE), RetentionPolicy.KEEP_FOREVER);
    assertEquals(RetentionPolicy.defaultFor(ConversionPurpose.TRAVEL), RetentionPolicy.TEMPORARY);
    assertEquals(RetentionPolicy.defaultFor(ConversionPurpose.ENHANCED_FAVORITE), RetentionPolicy.UNTIL_SOURCE_DELETED);
  }

  // ---- Validation ---------------------------------------------------------

  @Test
  public void testValidatePassesWithinTolerance()
  {
    ProbeResult p = new ProbeResult(true, 3_600_000L, 1, 1, 1_000_000L);
    ValidationResult r = DerivativeValidator.validate(p, 3_600_000L, true, true);
    assertTrue(r.isValid(), r.getReason());
  }

  @Test
  public void testValidateFailsOnUnopenableContainer()
  {
    ProbeResult p = new ProbeResult(false, 0L, 0, 0, 500L);
    ValidationResult r = DerivativeValidator.validate(p, 3_600_000L, true, true);
    assertFalse(r.isValid());
  }

  @Test
  public void testValidateFailsOnMissingVideoStream()
  {
    ProbeResult p = new ProbeResult(true, 3_600_000L, 0, 1, 1_000_000L);
    ValidationResult r = DerivativeValidator.validate(p, 3_600_000L, true, true);
    assertFalse(r.isValid());
  }

  @Test
  public void testValidateFailsOnTruncation()
  {
    // 10 minutes short of a one-hour expectation
    ProbeResult p = new ProbeResult(true, 3_000_000L, 1, 1, 1_000_000L);
    ValidationResult r = DerivativeValidator.validate(p, 3_600_000L, true, true);
    assertFalse(r.isValid());
    assertTrue(r.getReason().contains("truncation"), r.getReason());
  }

  @Test
  public void testValidateFailsOnEmptyFile()
  {
    ProbeResult p = new ProbeResult(true, 3_600_000L, 1, 1, 0L);
    ValidationResult r = DerivativeValidator.validate(p, 3_600_000L, true, true);
    assertFalse(r.isValid());
  }

  @Test
  public void testToleranceIsAtLeastFloor()
  {
    // short clip: fractional tolerance would be tiny, floor applies
    assertEquals(DerivativeValidator.tolerance(10_000L), DerivativeValidator.MIN_TOLERANCE_MS);
    // long content: fraction dominates
    assertTrue(DerivativeValidator.tolerance(3_600_000L) > DerivativeValidator.MIN_TOLERANCE_MS);
  }
}
