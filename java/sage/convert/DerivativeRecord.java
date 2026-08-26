/*
 * Copyright 2015 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sage.convert;

/**
 * A derivative: a converted / enhanced / archived copy of a source recording,
 * linked back to its source. This is the in-memory record; persistence (a
 * {@code MediaFile} relation in {@code Wiz.bin}) is layered on separately so the
 * lifecycle logic here stays free of server/database static-init dependencies
 * and remains unit-testable.
 *
 * <p>The descriptive fields (source, path, purpose, container/codec/geometry,
 * size) are fixed at construction. Only the lifecycle {@link #getState() state}
 * (and its failure reason) changes, and only through
 * {@link #advanceTo(DerivativeState)} / {@link #markFailed(String)}, which are
 * guarded by {@link DerivativeStateMachine}. A derivative is offered to clients
 * only when {@link #isOfflineReady()} is true.
 */
public final class DerivativeRecord
{
  private final int sourceMediaFileId;
  private final String outputPath;
  private final ConversionPurpose purpose;
  private final String containerMuxer;
  private final String videoCodec;
  private final int width;
  private final int height;
  private final double fps;
  private final String audioSummary;
  private final boolean hdr;
  private final long byteSize;
  private final long createdTimeMillis;
  private final RetentionPolicy retention;

  private DerivativeState state;
  private String failureReason;
  private boolean preferred;

  private DerivativeRecord(Builder b)
  {
    this.sourceMediaFileId = b.sourceMediaFileId;
    this.outputPath = b.outputPath;
    this.purpose = b.purpose;
    this.containerMuxer = b.containerMuxer;
    this.videoCodec = b.videoCodec;
    this.width = b.width;
    this.height = b.height;
    this.fps = b.fps;
    this.audioSummary = b.audioSummary;
    this.hdr = b.hdr;
    this.byteSize = b.byteSize;
    this.createdTimeMillis = b.createdTimeMillis;
    this.retention = b.retention != null ? b.retention : RetentionPolicy.defaultFor(b.purpose);
    this.state = DerivativeState.PREPARING;
    this.failureReason = "";
    this.preferred = b.preferred;
  }

  public int getSourceMediaFileId() { return sourceMediaFileId; }
  public String getOutputPath() { return outputPath; }
  public ConversionPurpose getPurpose() { return purpose; }
  public String getContainerMuxer() { return containerMuxer; }
  public String getVideoCodec() { return videoCodec; }
  public int getWidth() { return width; }
  public int getHeight() { return height; }
  public double getFps() { return fps; }
  public String getAudioSummary() { return audioSummary; }
  public boolean isHdr() { return hdr; }
  public long getByteSize() { return byteSize; }
  public long getCreatedTimeMillis() { return createdTimeMillis; }
  public RetentionPolicy getRetention() { return retention; }

  public DerivativeState getState() { return state; }
  public String getFailureReason() { return failureReason; }

  /** True only in {@link DerivativeState#READY} — the derivative is validated and usable. */
  public boolean isOfflineReady() { return state == DerivativeState.READY; }

  /** Whether this is the preferred derivative to serve for its source/purpose. */
  public boolean isPreferred() { return preferred; }
  public void setPreferred(boolean v) { this.preferred = v; }

  /**
   * Advance the lifecycle to {@code next}, enforcing the legal transition table.
   * Throws {@link IllegalStateException} on an illegal transition.
   */
  public void advanceTo(DerivativeState next)
  {
    this.state = DerivativeStateMachine.requireTransition(this.state, next);
    if (this.state != DerivativeState.FAILED)
      this.failureReason = "";
  }

  /** Transition to {@link DerivativeState#FAILED} recording the reason. */
  public void markFailed(String reason)
  {
    this.state = DerivativeStateMachine.requireTransition(this.state, DerivativeState.FAILED);
    this.failureReason = reason == null ? "" : reason;
  }

  /**
   * Apply a validation outcome from {@link DerivativeState#VALIDATING}: on pass
   * transition to READY, on fail to FAILED with the reason.
   */
  public void applyValidation(ValidationResult result)
  {
    if (result == null) { markFailed("no validation result"); return; }
    if (result.isValid()) advanceTo(DerivativeState.READY);
    else markFailed(result.getReason());
  }

  public static Builder builder() { return new Builder(); }

  public static final class Builder
  {
    private int sourceMediaFileId;
    private String outputPath;
    private ConversionPurpose purpose = ConversionPurpose.CUSTOM;
    private String containerMuxer;
    private String videoCodec;
    private int width;
    private int height;
    private double fps;
    private String audioSummary = "";
    private boolean hdr;
    private long byteSize;
    private long createdTimeMillis = System.currentTimeMillis();
    private RetentionPolicy retention;
    private boolean preferred;

    public Builder sourceMediaFileId(int v) { this.sourceMediaFileId = v; return this; }
    public Builder outputPath(String v) { this.outputPath = v; return this; }
    public Builder purpose(ConversionPurpose v) { this.purpose = v; return this; }
    public Builder containerMuxer(String v) { this.containerMuxer = v; return this; }
    public Builder videoCodec(String v) { this.videoCodec = v; return this; }
    public Builder width(int v) { this.width = v; return this; }
    public Builder height(int v) { this.height = v; return this; }
    public Builder fps(double v) { this.fps = v; return this; }
    public Builder audioSummary(String v) { this.audioSummary = v; return this; }
    public Builder hdr(boolean v) { this.hdr = v; return this; }
    public Builder byteSize(long v) { this.byteSize = v; return this; }
    public Builder createdTimeMillis(long v) { this.createdTimeMillis = v; return this; }
    public Builder retention(RetentionPolicy v) { this.retention = v; return this; }
    public Builder preferred(boolean v) { this.preferred = v; return this; }

    public DerivativeRecord build() { return new DerivativeRecord(this); }
  }
}
