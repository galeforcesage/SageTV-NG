/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */
package sage;

/**
 * One alternate tuning locator attached to an existing Channel/Station identity.
 *
 * <p>SageTV's Channel/Station rows are the single source of truth for EPG
 * binding. ATSC 3.0 (HEVC + AC-4) services for the same physical TV station
 * are represented as additional {@code ChannelVariant} rows attached to the
 * same {@code stationID} -- not as duplicate guide stations. This keeps EPG
 * listings collapsed onto one row while letting the LiveTV / recording paths
 * pick whichever variant suits the active client / policy.</p>
 *
 * <p>Variants live in {@link ChannelVariants} (an in-memory registry persisted
 * to Sage.properties). They are intentionally NOT serialized into Wiz.bin --
 * they can be safely rebuilt by re-running an HDHomeRun channel scan, and we
 * don't want to perturb the existing DB schema.</p>
 */
public final class ChannelVariant
{
  /** ATSC standards we currently distinguish. */
  public static final String TYPE_ATSC1 = "ATSC1";
  public static final String TYPE_ATSC3 = "ATSC3";

  /** Video codec hints (for serve-time decisions; matches MediaFormat names). */
  public static final String VCODEC_MPEG2 = "MPEG2VIDEO";
  public static final String VCODEC_H264  = "H.264";
  public static final String VCODEC_HEVC  = "HEVC";

  /** Audio codec hints. */
  public static final String ACODEC_AC3   = "AC3";
  public static final String ACODEC_EAC3  = "EAC3";
  public static final String ACODEC_AC4   = "AC4";
  public static final String ACODEC_AAC   = "AAC";

  private final String variantType;
  private final String videoCodecHint;
  private final String audioCodecHint;
  private final String tuningLocator;
  private final String sourceDeviceId;
  private final boolean drm;

  /** Backward-compatible ctor (no DRM flag => clear). */
  public ChannelVariant(String variantType, String videoCodecHint,
      String audioCodecHint, String tuningLocator, String sourceDeviceId)
  {
    this(variantType, videoCodecHint, audioCodecHint, tuningLocator, sourceDeviceId, false);
  }

  public ChannelVariant(String variantType, String videoCodecHint,
      String audioCodecHint, String tuningLocator, String sourceDeviceId,
      boolean drm)
  {
    this.variantType    = variantType    == null ? TYPE_ATSC1 : variantType;
    this.videoCodecHint = videoCodecHint == null ? ""         : videoCodecHint;
    this.audioCodecHint = audioCodecHint == null ? ""         : audioCodecHint;
    this.tuningLocator  = tuningLocator  == null ? ""         : tuningLocator;
    this.sourceDeviceId = sourceDeviceId == null ? ""         : sourceDeviceId;
    this.drm            = drm;
  }

  public String getVariantType()    { return variantType; }
  public String getVideoCodecHint() { return videoCodecHint; }
  public String getAudioCodecHint() { return audioCodecHint; }
  public String getTuningLocator()  { return tuningLocator; }
  public String getSourceDeviceId() { return sourceDeviceId; }
  public boolean isDrm()            { return drm; }
  public boolean isAtsc3()          { return TYPE_ATSC3.equals(variantType); }
  public boolean isHevc()           { return VCODEC_HEVC.equalsIgnoreCase(videoCodecHint); }
  public boolean isAc4()            { return ACODEC_AC4.equalsIgnoreCase(audioCodecHint); }

  /** Pipe-delimited persistence form. Keep stable -- it round-trips through
   *  Sage.properties via {@link ChannelVariants}. Field 6 (drm) is appended
   *  optionally so older 5-field strings deserialize cleanly with drm=false. */
  public String toPersistedString()
  {
    return variantType + '|' + videoCodecHint + '|' + audioCodecHint + '|'
         + escape(tuningLocator) + '|' + escape(sourceDeviceId)
         + '|' + (drm ? "1" : "0");
  }

  public static ChannelVariant fromPersistedString(String s)
  {
    if (s == null || s.length() == 0) return null;
    String[] parts = s.split("\\|", -1);
    if (parts.length < 4) return null;
    boolean drm = parts.length > 5 && "1".equals(parts[5]);
    return new ChannelVariant(
        parts[0],
        parts[1],
        parts[2],
        unescape(parts[3]),
        parts.length > 4 ? unescape(parts[4]) : "",
        drm);
  }

  private static String escape(String s)
  {
    if (s == null) return "";
    StringBuilder sb = new StringBuilder(s.length() + 4);
    for (int i = 0; i < s.length(); i++)
    {
      char c = s.charAt(i);
      switch (c)
      {
        case '\\': sb.append("\\\\"); break;
        case ';' : sb.append("\\s");  break;
        case '|' : sb.append("\\p");  break;
        default  : sb.append(c);
      }
    }
    return sb.toString();
  }

  private static String unescape(String s)
  {
    if (s == null) return "";
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++)
    {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length())
      {
        char n = s.charAt(++i);
        switch (n)
        {
          case '\\': sb.append('\\'); break;
          case 's' : sb.append(';');  break;
          case 'p' : sb.append('|');  break;
          default  : sb.append(c).append(n);
        }
      }
      else sb.append(c);
    }
    return sb.toString();
  }

  @Override public String toString()
  {
    return "ChannelVariant{" + variantType + " v=" + videoCodecHint
        + " a=" + audioCodecHint + " loc=" + tuningLocator
        + (sourceDeviceId.length() > 0 ? " dev=" + sourceDeviceId : "") + "}";
  }

  @Override public boolean equals(Object o)
  {
    if (!(o instanceof ChannelVariant)) return false;
    ChannelVariant other = (ChannelVariant) o;
    return variantType.equals(other.variantType)
        && tuningLocator.equals(other.tuningLocator)
        && sourceDeviceId.equals(other.sourceDeviceId);
  }

  @Override public int hashCode()
  {
    int h = variantType.hashCode();
    h = 31*h + tuningLocator.hashCode();
    h = 31*h + sourceDeviceId.hashCode();
    return h;
  }
}
