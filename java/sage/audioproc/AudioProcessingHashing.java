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
package sage.audioproc;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Deterministic, stable content-fingerprint hashing for {@code audioproc}
 * models ({@code settingsHash}/{@code filterGraphHash}). Not used for any
 * security/cryptographic purpose -- purely so client and server (and logs)
 * can cheaply compare "did the effective settings/filtergraph change"
 * without shipping the full payload back and forth.
 */
final class AudioProcessingHashing
{
  private AudioProcessingHashing()
  {
  }

  /**
   * Returns the first 16 hex characters of the SHA-256 digest of {@code
   * canonicalInput} (UTF-8). The same input always produces the same
   * output; callers are responsible for building a canonical (fixed field
   * order, fixed number formatting) input string so equivalent settings
   * always hash identically regardless of arrival order.
   */
  static String sha256Hex16(String canonicalInput)
  {
    try
    {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(canonicalInput.getBytes("UTF-8"));
      StringBuilder sb = new StringBuilder(16);
      for (int i = 0; i < 8 && i < bytes.length; i++)
      {
        String hex = Integer.toHexString(bytes[i] & 0xFF);
        if (hex.length() == 1)
          sb.append('0');
        sb.append(hex);
      }
      return sb.toString();
    }
    catch (NoSuchAlgorithmException | UnsupportedEncodingException e)
    {
      // SHA-256 and UTF-8 are both guaranteed available on every JVM; this
      // is unreachable in practice, but fail to a deterministic non-empty
      // value rather than throwing out of a hashing helper.
      return Integer.toHexString(canonicalInput.hashCode());
    }
  }
}
