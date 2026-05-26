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

/**
 * Issues and tracks short-lived download tokens for NG miniclient download
 * contracts. This is server-side only and does not expose plaintext token
 * material in logs.
 */
public final class NgClientDownloadTokenManager
{
  public static final class TokenIssue
  {
    public final String token;
    public final String tokenHash;
    public final long expiresAt;

    private TokenIssue(String token, String tokenHash, long expiresAt)
    {
      this.token = token;
      this.tokenHash = tokenHash;
      this.expiresAt = expiresAt;
    }
  }

  private static final class TokenRecord
  {
    public final String clientName;
    public final int mediaFileId;
    public final java.io.File mediaFile;
    public final String tokenHash;
    public final long expiresAt;

    private TokenRecord(String clientName, int mediaFileId, java.io.File mediaFile,
        String tokenHash, long expiresAt)
    {
      this.clientName = clientName;
      this.mediaFileId = mediaFileId;
      this.mediaFile = mediaFile;
      this.tokenHash = tokenHash;
      this.expiresAt = expiresAt;
    }
  }

  private static final NgClientDownloadTokenManager INSTANCE = new NgClientDownloadTokenManager();
  private final java.security.SecureRandom random = new java.security.SecureRandom();
  private final java.util.concurrent.ConcurrentHashMap<String, TokenRecord> issuedTokens =
      new java.util.concurrent.ConcurrentHashMap<String, TokenRecord>();

  private NgClientDownloadTokenManager()
  {
    java.util.Timer cleaner = new java.util.Timer("NgDownloadTokenCleanup", true);
    cleaner.scheduleAtFixedRate(new java.util.TimerTask()
    {
      @Override
      public void run()
      {
        cleanupExpired();
      }
    }, 5L * 60L * 1000L, 5L * 60L * 1000L);
  }

  public static NgClientDownloadTokenManager getInstance()
  {
    return INSTANCE;
  }

  public TokenIssue issueToken(String clientName, int mediaFileId, java.io.File mediaFile)
  {
    final long now = sage.Sage.time();
    final long ttlMs = sage.Sage.getLong("miniclient/download_token_ttl_ms", 10L * 60L * 1000L);
    final long expiresAt = now + Math.max(30L * 1000L, ttlMs);

    byte[] raw = new byte[16]; // 128 bits of entropy minimum
    random.nextBytes(raw);
    String token = toHex(raw);
    String tokenHash = sha256Hex(token);

    issuedTokens.put(token, new TokenRecord(
        clientName == null ? "" : clientName,
        mediaFileId,
        mediaFile,
        tokenHash,
        expiresAt));

    return new TokenIssue(token, tokenHash, expiresAt);
  }

  public boolean validateToken(String token, String clientName, int mediaFileId)
  {
    if (token == null || token.length() == 0)
      return false;
    TokenRecord rec = issuedTokens.get(token);
    if (rec == null)
      return false;
    if (rec.expiresAt < sage.Sage.time())
    {
      issuedTokens.remove(token);
      return false;
    }
    if (mediaFileId > 0 && rec.mediaFileId != mediaFileId)
      return false;
    if (clientName != null && clientName.length() > 0 && !clientName.equals(rec.clientName))
      return false;
    return true;
  }

  public void cleanupExpired()
  {
    final long now = sage.Sage.time();
    java.util.Iterator<java.util.Map.Entry<String, TokenRecord>> it = issuedTokens.entrySet().iterator();
    while (it.hasNext())
    {
      java.util.Map.Entry<String, TokenRecord> entry = it.next();
      if (entry.getValue().expiresAt < now)
        it.remove();
    }
  }

  public void logDispatch(String clientName, String clientIp, sage.MediaFile mf,
      TokenIssue issued, boolean ok, String detail)
  {
    String fileName = "";
    int mediaId = 0;
    if (mf != null)
    {
      mediaId = mf.getID();
      java.io.File f = mf.getFile(0);
      if (f != null)
        fileName = f.getName();
    }
    String tokenHash = issued == null ? "" : issued.tokenHash;
    System.out.println("NG_DOWNLOAD_AUDIT"
        + " ts=" + sage.Sage.df(sage.Sage.time())
        + " session=" + safe(clientName)
        + " ip=" + safe(clientIp)
        + " media_id=" + mediaId
        + " file=" + safe(fileName)
        + " token_hash=" + safe(tokenHash)
        + " status=" + (ok ? "ok" : "fail")
        + " detail=" + safe(detail));
  }

  private static String safe(String s)
  {
    return s == null ? "" : s.replace(' ', '_');
  }

  private static String toHex(byte[] bytes)
  {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (int i = 0; i < bytes.length; i++)
    {
      int b = bytes[i] & 0xFF;
      if (b < 16)
        sb.append('0');
      sb.append(Integer.toHexString(b));
    }
    return sb.toString();
  }

  private static String sha256Hex(String s)
  {
    try
    {
      java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(s.getBytes(sage.Sage.BYTE_CHARSET));
      return toHex(digest);
    }
    catch (Exception e)
    {
      return "";
    }
  }
}
