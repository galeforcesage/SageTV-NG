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
 * Index availability state — tells the client whether keyframe or
 * PTS-to-byte mapping data is available for client-assisted seeking.
 */
public final class NgIndexContext
{
  public static final NgIndexContext EMPTY = new NgIndexContext(false, false, Collections.emptyList());

  private final boolean hasKeyframeIndex;
  private final boolean hasPtsByteMap;
  private final List<NgPtsSample> ptsSamples;

  public NgIndexContext(boolean hasKeyframeIndex, boolean hasPtsByteMap,
      List<NgPtsSample> ptsSamples)
  {
    this.hasKeyframeIndex = hasKeyframeIndex;
    this.hasPtsByteMap = hasPtsByteMap;
    this.ptsSamples = (ptsSamples != null)
        ? Collections.unmodifiableList(new ArrayList<>(ptsSamples))
        : Collections.emptyList();
  }

  public boolean hasKeyframeIndex() { return hasKeyframeIndex; }
  public boolean hasPtsByteMap() { return hasPtsByteMap; }
  public List<NgPtsSample> getPtsSamples() { return ptsSamples; }

  @Override
  public String toString()
  {
    return "NgIndexContext{keyframe=" + hasKeyframeIndex +
        ", ptsByteMap=" + hasPtsByteMap +
        ", samples=" + ptsSamples.size() + '}';
  }
}
