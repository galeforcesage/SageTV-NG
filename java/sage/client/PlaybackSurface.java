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
package sage.client;

import java.util.Collections;
import java.util.List;

/**
 * A single concrete playback path exposed by a client -- one honest set of
 * capabilities that corresponds to ONE actual decode pipeline (e.g.
 * {@code android_media3}, {@code android_ijk}, {@code pwa_native},
 * {@code pwa_mse}, {@code windows_mediafoundation}). NOT a device, browser,
 * or OS -- those are properties of the client, not of a decode path.
 *
 * <p>The server evaluates each surface INDEPENDENTLY and ranks them at
 * decision time; capabilities from separate surfaces MUST NEVER be merged.
 * This is what fixes the class of bug where a device has two decoders with
 * different capability sets (Tizen native vs Tizen MSE; ExoPlayer vs
 * IJKPlayer on Android) and a single flat codec list picks the wrong path.
 *
 * <p>Introduced by the Playback Surface Capability Model, Protocol v2.1.
 * See ROADMAP.md "Playback Surface capability model (Protocol 2.1)" for the
 * full contract, canonical name reference, and phasing plan. Phase 1 uses
 * this type for parsing + logging only; Phase 2 wires it into
 * {@link PlaybackDecisionEngine}.
 */
public final class PlaybackSurface
{
  private final String id;
  private final String route;
  private final int priority;
  private final List<String> deliveryModes;
  private final List<String> videoCodecs;
  private final List<String> audioCodecs;
  private final List<String> containers;

  public PlaybackSurface(String id, String route, int priority,
      List<String> deliveryModes, List<String> videoCodecs,
      List<String> audioCodecs, List<String> containers)
  {
    if (id == null || id.length() == 0)
      throw new IllegalArgumentException("PlaybackSurface id must be non-empty");
    this.id = id;
    this.route = route == null ? "" : route;
    this.priority = priority;
    this.deliveryModes = deliveryModes == null
        ? Collections.<String>emptyList()
        : Collections.unmodifiableList(deliveryModes);
    this.videoCodecs = videoCodecs == null
        ? Collections.<String>emptyList()
        : Collections.unmodifiableList(videoCodecs);
    this.audioCodecs = audioCodecs == null
        ? Collections.<String>emptyList()
        : Collections.unmodifiableList(audioCodecs);
    this.containers = containers == null
        ? Collections.<String>emptyList()
        : Collections.unmodifiableList(containers);
  }

  public String getId() { return id; }
  public String getRoute() { return route; }
  public int getPriority() { return priority; }
  public List<String> getDeliveryModes() { return deliveryModes; }
  public List<String> getVideoCodecs() { return videoCodecs; }
  public List<String> getAudioCodecs() { return audioCodecs; }
  public List<String> getContainers() { return containers; }

  public boolean supportsDeliveryMode(String mode)
  {
    return mode != null
        && deliveryModes.contains(PlaybackSurfaceSet.canonicalDeliveryMode(mode));
  }

  /**
   * Alias- and case-tolerant check against the surface's declared video
   * codec list. SageTV's FormatParser emits internal spellings (e.g.
   * {@code "H.264"}, {@code "MPEG2-Video"}) that don't literally match the
   * canonical v2.1 tokens ({@code "H264"}, {@code "MPEG2-VIDEO"}) even
   * though they refer to the same codec. Canonicalizing the query via
   * {@link PlaybackSurfaceSet#canonicalVideoCodec} before comparison fixes
   * the class of bug that made an MPG2 source appear un-decodable to the
   * ijk software surface (which does list MPEG2-VIDEO) purely because of
   * a case mismatch.
   */
  public boolean supportsVideoCodec(String codec)
  {
    return codec != null
        && videoCodecs.contains(PlaybackSurfaceSet.canonicalVideoCodec(codec));
  }

  /** Alias- and case-tolerant check against the surface's audio codec list. */
  public boolean supportsAudioCodec(String codec)
  {
    return codec != null
        && audioCodecs.contains(PlaybackSurfaceSet.canonicalAudioCodec(codec));
  }

  /** Alias- and case-tolerant check against the surface's container list. */
  public boolean supportsContainer(String container)
  {
    return container != null
        && containers.contains(PlaybackSurfaceSet.canonicalContainer(container));
  }

  @Override
  public String toString()
  {
    return "PlaybackSurface[id=" + id
        + " route=" + route
        + " priority=" + priority
        + " delivery=" + deliveryModes
        + " video=" + videoCodecs
        + " audio=" + audioCodecs
        + " containers=" + containers + "]";
  }
}
