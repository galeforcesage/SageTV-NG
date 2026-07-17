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
 * Callback interface for components that want to receive NG playback context
 * delta updates. Listeners are invoked by {@link NgPlaybackContextProvider}
 * whenever a non-null delta is computed for any active session.
 * <p>
 * <b>Contract:</b>
 * <ul>
 *   <li>Implementations must be fast and non-blocking. Heavy work should be
 *       queued to a separate thread.</li>
 *   <li>Implementations must not throw. If they do, the provider catches,
 *       logs, and continues — playback is never affected.</li>
 *   <li>Implementations must not call back into the provider (re-entrancy
 *       is not supported and may deadlock).</li>
 *   <li>The provider holds no locks when calling listeners.</li>
 * </ul>
 */
public interface NgPlaybackContextListener
{
  /**
   * Called when a new delta has been computed for an active session.
   *
   * @param sessionKey the opaque session key (same value passed to
   *                   {@link NgPlaybackContextProvider#openSession})
   * @param delta      the computed delta (never null)
   */
  void onDelta(String sessionKey, NgPlaybackContextDelta delta);

  /**
   * Called when a session is closed. Listeners may use this to clean up
   * any per-session state they maintain.
   *
   * @param sessionKey the session that was closed
   * @param closeDelta the final session_close delta (may be null if the
   *                   session was already closed or never opened)
   */
  void onSessionClosed(String sessionKey, NgPlaybackContextDelta closeDelta);
}
