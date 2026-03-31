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
package sage.miniclient;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Input validation and sanitization for security-sensitive operations
 * in the Placeshifter client.
 */
public class InputValidator
{
  // Hostname: alphanumeric, dots, hyphens, colons (IPv6), brackets
  private static final Pattern SAFE_HOSTNAME =
      Pattern.compile("^[a-zA-Z0-9.\\-:\\[\\]]{1,253}$");

  // Characters that should never appear in file paths passed to external processes
  private static final Pattern DANGEROUS_PATH_CHARS =
      Pattern.compile("[\\x00`$><;&|]");

  // Allowed MPlayer command-line options (whitelist)
  private static final Set<String> ALLOWED_MPLAYER_OPTIONS = new HashSet<>(Arrays.asList(
      "-vf", "-af", "-lavdopts", "-cache", "-cache-min", "-framedrop",
      "-hardframedrop", "-autosync", "-mc", "-nocorrect-pts",
      "-correct-pts", "-fps", "-ofps", "-demuxer", "-aspect",
      "-monitoraspect", "-noautosub", "-nosub", "-sub", "-subcp",
      "-subfont-text-scale", "-ass", "-noass", "-channels",
      "-volume", "-delay", "-ao", "-vo", "-vc",
      "-pp", "-sws", "-zoom", "-nosound", "-novideo",
      "-dr", "-nodr", "-double", "-nodouble", "-priority",
      "-v", "-quiet", "-msglevel", "-nolirc",
      "-idx", "-noidx", "-forceidx"
  ));

  // Blocked executable names (shell interpreters, dangerous system tools)
  private static final Set<String> BLOCKED_EXECUTABLES = new HashSet<>(Arrays.asList(
      "cmd", "cmd.exe", "powershell", "powershell.exe", "pwsh", "pwsh.exe",
      "bash", "sh", "zsh", "csh", "ksh", "dash",
      "wscript", "wscript.exe", "cscript", "cscript.exe",
      "mshta", "mshta.exe", "regsvr32", "regsvr32.exe",
      "rundll32", "rundll32.exe", "certutil", "certutil.exe",
      "bitsadmin", "bitsadmin.exe", "msiexec", "msiexec.exe"
  ));

  // Sensitive property names whose values should be masked in logs
  private static final Set<String> SENSITIVE_PROPERTIES = new HashSet<>(Arrays.asList(
      "GET_CACHED_AUTH", "SET_CACHED_AUTH", "CRYPTO_SYMMETRIC_KEY",
      "CRYPTO_PUBLIC_KEY", "CRYPTO_EVENTS_ENABLE"
  ));

  /**
   * Validates a hostname for use in network connections and stv:// URLs.
   */
  public static boolean isValidHostname(String hostname)
  {
    if (hostname == null || hostname.isEmpty()) return false;
    return SAFE_HOSTNAME.matcher(hostname).matches();
  }

  /**
   * Sanitizes a file path by removing dangerous characters.
   */
  public static String sanitizeFilePath(String path)
  {
    if (path == null) return null;
    path = path.replace("\0", "");
    if (DANGEROUS_PATH_CHARS.matcher(path).find())
    {
      System.out.println("WARNING: Dangerous characters detected in file path, sanitizing");
      path = DANGEROUS_PATH_CHARS.matcher(path).replaceAll("");
    }
    return path;
  }

  /**
   * Sanitizes MPlayer extra arguments by whitelisting known-safe options.
   * Blocks potentially dangerous options that could execute commands or access files.
   */
  public static String sanitizeMPlayerArgs(String args)
  {
    if (args == null || args.trim().isEmpty()) return "";
    StringBuilder safe = new StringBuilder();
    java.util.StringTokenizer toker = new java.util.StringTokenizer(args.trim(), " ");
    boolean expectValue = false;
    while (toker.hasMoreTokens())
    {
      String token = toker.nextToken();
      if (expectValue)
      {
        if (!token.startsWith("-") && !DANGEROUS_PATH_CHARS.matcher(token).find())
        {
          safe.append(" ").append(token);
        }
        else
        {
          System.out.println("WARNING: Blocked suspicious MPlayer arg value: " + token);
        }
        expectValue = false;
        continue;
      }
      if (token.startsWith("-"))
      {
        String optName = token.contains("=") ? token.substring(0, token.indexOf("=")) : token;
        if (ALLOWED_MPLAYER_OPTIONS.contains(optName))
        {
          safe.append(" ").append(token);
          if (!token.contains("=") && toker.hasMoreTokens())
            expectValue = true;
        }
        else
        {
          System.out.println("WARNING: Blocked disallowed MPlayer option: " + optName);
        }
      }
    }
    return safe.toString();
  }

  /**
   * Validates an executable path from Programs.properties.
   * Blocks shell interpreters and dangerous system tools.
   */
  public static boolean isAllowedExecutable(String execPath)
  {
    if (execPath == null || execPath.isEmpty()) return false;
    if (execPath.contains("\0") || execPath.contains("..")) return false;
    String fileName = new java.io.File(execPath).getName().toLowerCase();
    if (BLOCKED_EXECUTABLES.contains(fileName)) return false;
    return new java.io.File(execPath).isFile();
  }

  /**
   * Returns true if a property name is sensitive and should be masked in logs.
   */
  public static boolean isSensitiveProperty(String propName)
  {
    return propName != null && SENSITIVE_PROPERTIES.contains(propName);
  }

  /**
   * Masks a sensitive string for safe logging. Shows first 2 and last 2 chars.
   */
  public static String maskForLog(String sensitive)
  {
    if (sensitive == null) return "null";
    if (sensitive.length() <= 4) return "****";
    return sensitive.substring(0, 2) + "****" + sensitive.substring(sensitive.length() - 2);
  }

  /**
   * Restricts file permissions to owner-only access.
   * Works on both Windows and POSIX systems.
   */
  public static void restrictFilePermissions(java.io.File file)
  {
    if (file == null || !file.exists()) return;
    try
    {
      file.setReadable(false, false);
      file.setWritable(false, false);
      file.setExecutable(false, false);
      file.setReadable(true, true);
      file.setWritable(true, true);
    }
    catch (SecurityException e)
    {
      System.out.println("WARNING: Could not restrict permissions on " + file.getName());
    }
  }

  /**
   * Encrypts a sensitive property value for storage using PBKDF2 + AES-128-CBC.
   * Returns a Base64-encoded string prefixed with "ENC:" marker.
   */
  public static String encryptPropertyValue(String plaintext)
  {
    if (plaintext == null || plaintext.isEmpty()) return plaintext;
    try
    {
      byte[] salt = getMachineIdentitySalt();
      javax.crypto.SecretKeyFactory factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
      javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
          getMachineIdentityChars(), salt, 10000, 128);
      javax.crypto.SecretKey tmp = factory.generateSecret(spec);
      javax.crypto.spec.SecretKeySpec secretKey = new javax.crypto.spec.SecretKeySpec(tmp.getEncoded(), "AES");

      javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
      cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey);
      byte[] iv = cipher.getIV();
      byte[] encrypted = cipher.doFinal(plaintext.getBytes("UTF-8"));

      byte[] combined = new byte[iv.length + encrypted.length];
      System.arraycopy(iv, 0, combined, 0, iv.length);
      System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
      return "ENC:" + java.util.Base64.getEncoder().encodeToString(combined);
    }
    catch (Exception e)
    {
      System.out.println("WARNING: Property encryption failed, storing as plaintext");
      return plaintext;
    }
  }

  /**
   * Decrypts a property value that was encrypted with encryptPropertyValue().
   * Returns the original plaintext, or the input unchanged if not encrypted.
   */
  public static String decryptPropertyValue(String encrypted)
  {
    if (encrypted == null || !encrypted.startsWith("ENC:")) return encrypted;
    try
    {
      byte[] combined = java.util.Base64.getDecoder().decode(encrypted.substring(4));
      byte[] salt = getMachineIdentitySalt();
      javax.crypto.SecretKeyFactory factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
      javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
          getMachineIdentityChars(), salt, 10000, 128);
      javax.crypto.SecretKey tmp = factory.generateSecret(spec);
      javax.crypto.spec.SecretKeySpec secretKey = new javax.crypto.spec.SecretKeySpec(tmp.getEncoded(), "AES");

      byte[] iv = new byte[16];
      byte[] ciphertext = new byte[combined.length - 16];
      System.arraycopy(combined, 0, iv, 0, 16);
      System.arraycopy(combined, 16, ciphertext, 0, ciphertext.length);

      javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
      cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, new javax.crypto.spec.IvParameterSpec(iv));
      byte[] decrypted = cipher.doFinal(ciphertext);
      return new String(decrypted, "UTF-8");
    }
    catch (Exception e)
    {
      System.out.println("WARNING: Property decryption failed");
      return "";
    }
  }

  private static char[] getMachineIdentityChars()
  {
    String identity = System.getProperty("user.name", "default") +
        System.getProperty("os.name", "unknown") +
        System.getProperty("user.home", "/tmp");
    return identity.toCharArray();
  }

  private static byte[] getMachineIdentitySalt()
  {
    String saltSource = "SageTVPS-" + System.getProperty("user.name", "default");
    try
    {
      java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(saltSource.getBytes("UTF-8"));
      byte[] salt = new byte[16];
      System.arraycopy(hash, 0, salt, 0, 16);
      return salt;
    }
    catch (Exception e)
    {
      return new byte[]{0x53, 0x61, 0x67, 0x65, 0x54, 0x56, 0x50, 0x53,
                         0x2D, 0x53, 0x61, 0x6C, 0x74, 0x56, 0x61, 0x6C};
    }
  }
}
