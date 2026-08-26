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

/**
 * How the converted file will reach the player. This expresses a <em>size
 * pressure</em> only — it never chooses a codec or container by itself, it just
 * biases the quality/bitrate target the {@link GuidedRecommender} resolves.
 *
 * <p>{@link #CUSTOM} carries an explicit byte budget on {@link GuidedInputs}
 * instead of a fixed pressure.
 */
public enum TransferClass
{
  /** LAN, local Wi-Fi, direct storage or physical USB handoff — file size barely matters. */
  LOCAL_USB(1),
  /** Good remote broadband / fast hotel Wi-Fi — moderate size pressure. */
  FAST_WAN(2),
  /** Cellular or restricted data allowance — strong size pressure. */
  LIMITED_WAN(3),
  /** File stays on the server / local storage — no size pressure at all. */
  UNRESTRICTED(0),
  /** A user-supplied maximum size or data budget (see {@link GuidedInputs#getCustomBudgetBytes()}). */
  CUSTOM(2);

  private final int pressure;

  TransferClass(int pressure) { this.pressure = pressure; }

  /** Size pressure 0 (none) .. 3 (high). {@link #CUSTOM} returns its nominal 2. */
  public int pressure() { return pressure; }
}
