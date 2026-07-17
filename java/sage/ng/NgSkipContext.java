/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
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
package sage.ng;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

/**
 * Skip/chapter/bookmark segments for the current media.
 * For content with no skip data, use {@link #EMPTY}.
 */
public final class NgSkipContext
{
  public static final NgSkipContext EMPTY = new NgSkipContext(
      Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

  private final List<NgSkipSegment> commercials;
  private final List<NgSkipSegment> chapters;
  private final List<NgSkipSegment> bookmarks;

  public NgSkipContext(List<NgSkipSegment> commercials,
      List<NgSkipSegment> chapters, List<NgSkipSegment> bookmarks)
  {
    this.commercials = (commercials != null)
        ? Collections.unmodifiableList(new ArrayList<>(commercials))
        : Collections.emptyList();
    this.chapters = (chapters != null)
        ? Collections.unmodifiableList(new ArrayList<>(chapters))
        : Collections.emptyList();
    this.bookmarks = (bookmarks != null)
        ? Collections.unmodifiableList(new ArrayList<>(bookmarks))
        : Collections.emptyList();
  }

  public List<NgSkipSegment> getCommercials() { return commercials; }
  public List<NgSkipSegment> getChapters() { return chapters; }
  public List<NgSkipSegment> getBookmarks() { return bookmarks; }

  @Override
  public String toString()
  {
    return "NgSkipContext{commercials=" + commercials.size() +
        ", chapters=" + chapters.size() +
        ", bookmarks=" + bookmarks.size() + '}';
  }
}
