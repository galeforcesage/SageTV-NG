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
package sage.convert.guided;

import java.util.EnumSet;

import sage.convert.AudioCodecChoice;
import sage.convert.AudioLayoutChoice;
import sage.convert.ContainerChoice;
import sage.convert.DynamicRangeChoice;
import sage.convert.FrameRateChoice;
import sage.convert.ScalingChoice;
import sage.convert.SourceMedia;
import sage.convert.SubtitleChoice;
import sage.convert.VideoCodecChoice;

/**
 * The complete input to {@link GuidedRecommender#recommend}: the source media,
 * the guided front-door answers (creation goals, transfer class, player profile,
 * priority and preference toggles), and any manual capability {@link Overrides}
 * from the Customize step. Immutable; built via {@link Builder}.
 *
 * <p>The recommender resolves goals+transfer+device+priority into a full
 * {@link sage.convert.ConversionRequest} first, then applies non-null override
 * fields on top and re-validates. Overrides therefore always win, which is how
 * the Customize step exposes every supported capability without the front door
 * having to anticipate the combination.
 */
public final class GuidedInputs
{
  private final SourceMedia source;
  private final EnumSet<CreationGoal> goals;
  private final TransferClass transfer;
  private final long customBudgetBytes;
  private final DeviceProfile device;
  private final QualityPriority priority;
  private final boolean preserveSmoothMotion;
  private final boolean preserveSurround;
  private final boolean preserveHdr;
  private final boolean avoidReencode;
  private final boolean useHardware;
  private final boolean keepSubtitles;
  private final Overrides overrides;

  private GuidedInputs(Builder b)
  {
    this.source = b.source;
    this.goals = b.goals.isEmpty() ? EnumSet.noneOf(CreationGoal.class) : EnumSet.copyOf(b.goals);
    this.transfer = b.transfer;
    this.customBudgetBytes = b.customBudgetBytes;
    this.device = b.device;
    this.priority = b.priority;
    this.preserveSmoothMotion = b.preserveSmoothMotion;
    this.preserveSurround = b.preserveSurround;
    this.preserveHdr = b.preserveHdr;
    this.avoidReencode = b.avoidReencode;
    this.useHardware = b.useHardware;
    this.keepSubtitles = b.keepSubtitles;
    this.overrides = b.overrides == null ? new Overrides() : b.overrides;
  }

  public SourceMedia getSource() { return source; }
  public EnumSet<CreationGoal> getGoals() { return goals; }
  public boolean has(CreationGoal g) { return goals.contains(g); }
  public TransferClass getTransfer() { return transfer; }
  public long getCustomBudgetBytes() { return customBudgetBytes; }
  public DeviceProfile getDevice() { return device; }
  public QualityPriority getPriority() { return priority; }
  public boolean isPreserveSmoothMotion() { return preserveSmoothMotion; }
  public boolean isPreserveSurround() { return preserveSurround; }
  public boolean isPreserveHdr() { return preserveHdr; }
  public boolean isAvoidReencode() { return avoidReencode; }
  public boolean isUseHardware() { return useHardware; }
  public boolean isKeepSubtitles() { return keepSubtitles; }
  public Overrides getOverrides() { return overrides; }

  public static Builder builder(SourceMedia source)
  {
    return new Builder(source);
  }

  /**
   * Nullable capability overrides from the Customize step. A {@code null} field
   * means "leave the recommendation's value"; a non-null field replaces it.
   */
  public static final class Overrides
  {
    public ContainerChoice container;
    public VideoCodecChoice videoCodec;
    public ScalingChoice scaling;
    public Integer width;
    public Integer height;
    public FrameRateChoice frameRate;
    public AudioLayoutChoice audioLayout;
    public AudioCodecChoice audioCodec;
    public Integer audioBitrateKbps;
    public DynamicRangeChoice dynamicRange;
    public SubtitleChoice subtitles;
    public Integer qualityCq;

    public boolean isEmpty()
    {
      return container == null && videoCodec == null && scaling == null
          && width == null && height == null && frameRate == null
          && audioLayout == null && audioCodec == null && audioBitrateKbps == null
          && dynamicRange == null && subtitles == null && qualityCq == null;
    }
  }

  public static final class Builder
  {
    private final SourceMedia source;
    private EnumSet<CreationGoal> goals = EnumSet.noneOf(CreationGoal.class);
    private TransferClass transfer = TransferClass.UNRESTRICTED;
    private long customBudgetBytes = 0L;
    private DeviceProfile device = DeviceProfile.unknownDevice();
    private QualityPriority priority = QualityPriority.BALANCED;
    private boolean preserveSmoothMotion;
    private boolean preserveSurround;
    private boolean preserveHdr;
    private boolean avoidReencode;
    private boolean useHardware = true;
    private boolean keepSubtitles;
    private Overrides overrides;

    private Builder(SourceMedia source)
    {
      if (source == null) throw new IllegalArgumentException("source is null");
      this.source = source;
    }

    public Builder goals(EnumSet<CreationGoal> g) { if (g != null) this.goals = EnumSet.copyOf(g); return this; }
    public Builder goal(CreationGoal g) { if (g != null) this.goals.add(g); return this; }
    public Builder transfer(TransferClass t) { if (t != null) this.transfer = t; return this; }
    public Builder customBudgetBytes(long v) { this.customBudgetBytes = v; return this; }
    public Builder device(DeviceProfile d) { if (d != null) this.device = d; return this; }
    public Builder priority(QualityPriority p) { if (p != null) this.priority = p; return this; }
    public Builder preserveSmoothMotion(boolean v) { this.preserveSmoothMotion = v; return this; }
    public Builder preserveSurround(boolean v) { this.preserveSurround = v; return this; }
    public Builder preserveHdr(boolean v) { this.preserveHdr = v; return this; }
    public Builder avoidReencode(boolean v) { this.avoidReencode = v; return this; }
    public Builder useHardware(boolean v) { this.useHardware = v; return this; }
    public Builder keepSubtitles(boolean v) { this.keepSubtitles = v; return this; }
    public Builder overrides(Overrides o) { this.overrides = o; return this; }

    public GuidedInputs build() { return new GuidedInputs(this); }
  }
}
