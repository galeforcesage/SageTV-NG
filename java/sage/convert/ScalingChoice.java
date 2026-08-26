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
package sage.convert;

/**
 * How the picture is resized. AI and Lanczos are mutually exclusive; AI is only
 * valid when upscaling (target larger than source) and is handled by the chained
 * offline-upscale job path, not by an ffmpeg scale filter.
 */
public enum ScalingChoice
{
  /** No resolution change. */
  NONE,
  /** Classic Lanczos resize (GPU {@code scale_npp} or software {@code scale}). */
  LANCZOS,
  /** AI super-resolution upscale (Real-ESRGAN / VSR, chained-job path). */
  AI
}
