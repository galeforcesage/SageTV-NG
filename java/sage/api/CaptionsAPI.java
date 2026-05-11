/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */
package sage.api;

import sage.Catbert;
import sage.MediaFile;
import sage.PredefinedJEPFunction;
import sage.Sage;
import sage.captions.CaptionExtractionManager;

/**
 * STV-callable API for closed-caption extraction (sidecar `.srt` generation).
 */
public class CaptionsAPI
{
  private CaptionsAPI() {}

  public static void init(Catbert.ReflectionFunctionTable rft)
  {
    rft.put(new PredefinedJEPFunction("Captions", "IsCaptionExtractionEnabled", true)
    {
      /**
       * Returns true if automatic closed-caption sidecar extraction is enabled.
       *
       * @declaration public boolean IsCaptionExtractionEnabled();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception
      {
        return CaptionExtractionManager.getInstance().isEnabled();
      }
    });

    rft.put(new PredefinedJEPFunction("Captions", "SetCaptionExtractionEnabled", new String[] { "Enabled" })
    {
      /**
       * Enables or disables automatic closed-caption extraction at recording end.
       *
       * @declaration public void SetCaptionExtractionEnabled(boolean Enabled);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception
      {
        boolean enabled = evalBool(stack.pop());
        Sage.putBoolean("caption_extraction/enabled", enabled);
        return null;
      }
    });

    rft.put(new PredefinedJEPFunction("Captions", "ExtractCaptions", new String[] { "MediaFile" })
    {
      /**
       * Manually extract captions from the given media file. Overwrites any
       * existing sidecar.
       *
       * @declaration public void ExtractCaptions(MediaFile MediaFile);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception
      {
        MediaFile mf = getMediaFile(stack);
        CaptionExtractionManager.getInstance().runNow(mf);
        return null;
      }
    });

    rft.put(new PredefinedJEPFunction("Captions", "HasCaptionSidecar", new String[] { "MediaFile" })
    {
      /**
       * Returns true if a non-empty `.srt` caption sidecar exists for this file.
       *
       * @declaration public boolean HasCaptionSidecar(MediaFile MediaFile);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception
      {
        MediaFile mf = getMediaFile(stack);
        return CaptionExtractionManager.getInstance().hasCaptions(mf);
      }
    });

    rft.put(new PredefinedJEPFunction("Captions", "ClearCaptionSidecar", new String[] { "MediaFile" })
    {
      /**
       * Delete the caption sidecar for this file.
       *
       * @declaration public boolean ClearCaptionSidecar(MediaFile MediaFile);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception
      {
        MediaFile mf = getMediaFile(stack);
        return CaptionExtractionManager.getInstance().clearCaptions(mf);
      }
    });

    rft.put(new PredefinedJEPFunction("Captions", "BackfillCaptions", new String[] { "Force" })
    {
      /**
       * Scan all TV recordings and queue caption extraction for those without
       * a sidecar (or all of them if Force is true). Returns the number of
       * jobs queued.
       *
       * @declaration public int BackfillCaptions(boolean Force);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception
      {
        boolean force = evalBool(stack.pop());
        return CaptionExtractionManager.getInstance().backfillAll(force);
      }
    });
  }
}
