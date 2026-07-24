/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */
package sage.captions;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Writes a {@link CaptionEvent} list out as a standard SubRip (.srt) file:
 * sequential 1-based index, {@code HH:MM:SS,mmm --> HH:MM:SS,mmm} timecode
 * line, cue text, blank separator line.
 *
 * <p>Writes to a {@code .tmp} file next to the destination and atomically
 * renames it into place, matching the write convention already used
 * elsewhere in {@code sage.captions} (see {@code CaptionExtractionJob}).
 */
public class SrtCaptionWriter implements CaptionWriter
{
  @Override
  public void write(List<CaptionEvent> events, File output) throws IOException
  {
    File tmp = new File(output.getAbsolutePath() + ".tmp");
    StringBuilder sb = new StringBuilder();
    int index = 1;
    if (events != null)
    {
      for (CaptionEvent e : events)
      {
        sb.append(e.toSrtBlock(index++));
      }
    }
    Files.write(tmp.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
    Files.move(tmp.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }
}
