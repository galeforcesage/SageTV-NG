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

/**
 * Reasons why a live-window delta was emitted.
 * Clients can use this to decide urgency and UI behavior.
 */
public final class NgDeltaReason
{
  /** First delta after playback open / context creation */
  public static final String INITIAL = "initial";
  /** Periodic wall-clock refresh (no significant event triggered it) */
  public static final String WALL_CLOCK = "wall_clock";
  /** Active file grew enough to warrant an update */
  public static final String FILE_GROWTH = "file_growth";
  /** Server processed a seek command */
  public static final String SEEK = "seek";
  /** Server flushed the push buffer */
  public static final String FLUSH = "flush";
  /** Stream epoch changed (e.g., channel change, new file loaded) */
  public static final String EPOCH_CHANGE = "epoch_change";
  /** Playback session is closing */
  public static final String SESSION_CLOSE = "session_close";

  private NgDeltaReason() {}
}
