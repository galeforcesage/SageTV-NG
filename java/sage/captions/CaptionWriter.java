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
import java.util.List;

/**
 * Writes a normalized {@link CaptionEvent} stream out to a sidecar file in
 * some concrete subtitle format (SRT, VTT, ...). Implementations are
 * expected to write atomically (temp file + rename) so a partially written
 * sidecar never confuses a subtitle loader that is concurrently polling the
 * output path.
 */
public interface CaptionWriter
{
  /**
   * Writes {@code events} (should already be sorted/coalesced as desired) to
   * {@code output}.
   *
   * @param events caption events to write, in chronological order
   * @param output destination file
   * @throws IOException if the file cannot be written
   */
  void write(List<CaptionEvent> events, File output) throws IOException;
}
