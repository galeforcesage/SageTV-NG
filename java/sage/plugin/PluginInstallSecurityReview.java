/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */
package sage.plugin;

import sage.Sage;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Security review engine for plugin preflight checks.
 */
public final class PluginInstallSecurityReview
{
  public enum Category
  {
    MALWARE,
    INTEGRITY,
    POLICY,
    HYGIENE
  }

  public enum Severity
  {
    BLOCK,
    WARN,
    INFO
  }

  public static final class Finding
  {
    public final String check;
    public final Category category;
    public final Severity severity;
    public final String title;
    public final String detail;
    public final String remediation;
    public final boolean overridable;
    public final Map evidence;

    public Finding(String check, Category category, Severity severity, String title, String detail,
                   String remediation, boolean overridable, Map evidence)
    {
      this.check = check;
      this.category = category;
      this.severity = severity;
      this.title = title;
      this.detail = detail;
      this.remediation = remediation;
      this.overridable = overridable;
      this.evidence = evidence == null ? new HashMap() : evidence;
    }

    public Map toMap()
    {
      Map rv = new HashMap();
      rv.put("check", check);
      rv.put("category", category.name());
      rv.put("severity", severity.name());
      rv.put("title", title);
      rv.put("detail", detail);
      rv.put("remediation", remediation);
      rv.put("overridable", Boolean.valueOf(overridable));
      rv.put("evidence", evidence);
      return rv;
    }
  }

  public static final class Result
  {
    public final List findings = new ArrayList();
    public int tier = 1;
    public int riskScore = 0;
    public String capabilitySummary = "";
    public String sourceRepo = "";
    public String bundleSha256 = "";

    public boolean hasHardBlock()
    {
      for (int i = 0; i < findings.size(); i++)
      {
        Finding f = (Finding) findings.get(i);
        if (f.severity == Severity.BLOCK && !f.overridable)
          return true;
      }
      return false;
    }

    public List requiredAcceptanceChecks()
    {
      List rv = new ArrayList();
      boolean allowOverrideHardBlocks = Sage.getBoolean("plugin/security/allow_override_hard_blocks", true);
      for (int i = 0; i < findings.size(); i++)
      {
        Finding f = (Finding) findings.get(i);
        if (f.severity == Severity.WARN ||
            (f.severity == Severity.BLOCK && (f.overridable || allowOverrideHardBlocks)))
          rv.add(f.check);
      }
      return rv;
    }

    public List toSerializableFindings()
    {
      List rv = new ArrayList(findings.size());
      for (int i = 0; i < findings.size(); i++)
        rv.add(((Finding) findings.get(i)).toMap());
      return rv;
    }
  }

  private static final long ZIP_MAX_BYTES = 50L * 1024L * 1024L;
  private static final long EXTRACTED_MAX_BYTES = 200L * 1024L * 1024L;
  private static final int INSPECT_READ_LIMIT = 512 * 1024;
  private static final long SANDBOX_ENTRY_MAX_BYTES = 64L * 1024L * 1024L;
  private static final String[] DANGEROUS_BYTECODE_MARKERS = new String[] {
      "java/lang/Runtime",
      "java/lang/ProcessBuilder",
      "java/lang/System",
      "java/net/Socket",
      "exec",
      "start",
      "loadLibrary"
  };

  private PluginInstallSecurityReview()
  {
  }

  public static Result evaluate(PluginWrapper plug, Map packageMap)
  {
    Result result = new Result();

    Set hosts = new HashSet();
    InspectionState state = new InspectionState();

    PluginWrapper.Package[] packages = plug.getPackages();
    for (int i = 0; i < packages.length; i++)
    {
      PluginWrapper.Package pkg = packages[i];
      File localFile = (File) packageMap.get(pkg);
      if (localFile == null)
        continue;

      String scheme = getUrlScheme(pkg.url);
      String host = getUrlHost(pkg.url);
      if (host.length() > 0)
        hosts.add(host.toLowerCase(Locale.ROOT));

      if (!"https".equalsIgnoreCase(scheme) && !"file".equalsIgnoreCase(scheme))
      {
        result.findings.add(new Finding("transport.https_only", Category.HYGIENE, Severity.BLOCK,
            "Non-HTTPS package location",
            "One or more plugin package URLs use a non-HTTPS transport.",
            "Serve plugin packages over HTTPS or use file:// for local development builds.",
            true,
            mapOf("url", pkg.url, "scheme", scheme)));
      }

      if (pkg.getSHA256() == null || pkg.getSHA256().trim().length() == 0)
      {
        result.findings.add(new Finding("sha256.verify", Category.INTEGRITY, Severity.BLOCK,
            "SHA-256 checksum missing",
            "The package metadata does not provide SHA-256 for this artifact.",
            "Publish SHA-256 values in the plugin manifest.",
            true,
            mapOf("url", pkg.url)));
      }
      else
      {
        String actualSha = calcSHA256(localFile);
        if (!pkg.getSHA256().equalsIgnoreCase(actualSha))
        {
          result.findings.add(new Finding("sha256.verify", Category.INTEGRITY, Severity.BLOCK,
              "SHA-256 mismatch",
              "Downloaded package digest does not match the declared SHA-256.",
              "Do not install. Re-download and verify package provenance.",
              false,
              mapOf("url", pkg.url, "expected", pkg.getSHA256(), "actual", actualSha)));
        }
      }

      if (pkg.getRawMD5() != null && pkg.getRawMD5().trim().length() > 0 &&
          (pkg.getSHA256() == null || pkg.getSHA256().trim().length() == 0))
      {
        result.findings.add(new Finding("md5.only", Category.HYGIENE, Severity.WARN,
            "Only MD5 checksum is provided",
            "This package only has MD5 integrity metadata, which is considered weak.",
            "Add SHA-256 metadata to plugin packages.",
            true,
            mapOf("url", pkg.url, "md5", pkg.getRawMD5())));
      }

      String lowerName = localFile.getName().toLowerCase(Locale.ROOT);
      boolean isArchive = lowerName.endsWith(".zip") || lowerName.endsWith(".jar") || "JAR".equalsIgnoreCase(pkg.type);
      if (isArchive)
      {
        if (localFile.length() > ZIP_MAX_BYTES)
        {
          result.findings.add(new Finding("zip.size_limit", Category.POLICY, Severity.BLOCK,
              "Archive exceeds size limit",
              "ZIP archive size exceeds the configured 50 MB limit.",
              "Reduce package size or split content into separate artifacts.",
              false,
              mapOf("path", localFile.getAbsolutePath(), "size", Long.valueOf(localFile.length()))));
        }

        inspectArchiveInSandbox(localFile, result, state);
      }
      else
      {
        if (isNativeLibraryName(lowerName))
          state.hasNativeLibs = true;
        if (lowerName.endsWith(".jar"))
        {
          byte[] payload = readFileBytes(localFile, INSPECT_READ_LIMIT);
          if (payload != null && containsDangerousMarkers(payload))
            state.hasDangerousCalls = true;
        }
      }
    }

    if (state.extractedBytes > EXTRACTED_MAX_BYTES)
    {
      result.findings.add(new Finding("zip.size_limit", Category.POLICY, Severity.BLOCK,
          "Extracted content exceeds size limit",
          "Extracted tree exceeds the configured 200 MB limit.",
          "Reduce extracted footprint or split package artifacts.",
          false,
          mapOf("extracted_bytes", Long.valueOf(state.extractedBytes))));
    }

    if (state.hasDangerousCalls)
    {
      result.findings.add(new Finding("bytecode.dangerous_calls", Category.POLICY, Severity.WARN,
          "Potentially dangerous bytecode calls detected",
          "Package bytecode contains APIs commonly used for command execution, native loading, or raw socket operations.",
          "Review plugin source and restrict deployment to trusted repositories.",
          true,
          mapOf("markers", Arrays.asList(DANGEROUS_BYTECODE_MARKERS))));
    }

    if (state.hasNativeLibs)
    {
      result.findings.add(new Finding("bytecode.native_libraries", Category.POLICY, Severity.WARN,
          "Native libraries included",
          "Plugin package includes native binaries (.so/.dll/.dylib).",
          "Install only if the publisher and binary provenance are trusted.",
          true,
          new HashMap()));
    }

    Set allowlist = getAllowlist();
    for (int i = 0; i < packages.length; i++)
    {
      String url = packages[i].url;
      String scheme = getUrlScheme(url);
      if ("file".equalsIgnoreCase(scheme))
        continue;
      if (!isAllowlisted(url, allowlist))
      {
        result.findings.add(new Finding("repo.allowlist", Category.POLICY, Severity.BLOCK,
            "Package source is not allowlisted",
            "Package source URL is not included in sagetv_repo_allowlist.",
            "Add trusted repository host/prefix to sagetv_repo_allowlist or use an approved mirror.",
            false,
            mapOf("url", url, "allowlist", new ArrayList(allowlist))));
      }
    }

    if (!isScannerConfigured("plugin/security/clamav_cmd"))
    {
      result.findings.add(new Finding("clamav.scan", Category.MALWARE, Severity.BLOCK,
          "ClamAV scanner unavailable",
          "ClamAV scanner command is not configured.",
          "Set plugin/security/clamav_cmd to enable malware scanning.",
          true,
          new HashMap()));
    }

    if (!isScannerConfigured("plugin/security/grype_cmd"))
    {
      result.findings.add(new Finding("grype.cve_scan", Category.POLICY, Severity.BLOCK,
          "Grype scanner unavailable",
          "Grype scanner command is not configured.",
          "Set plugin/security/grype_cmd and ensure vulnerability DB freshness.",
          true,
          new HashMap()));
    }

    result.findings.add(new Finding("gpg.manifest_signature", Category.INTEGRITY, Severity.BLOCK,
        "Manifest signature not available",
        "Plugin manifest signature metadata is not available for this installation path.",
        "Publish and verify .asc signatures for plugin manifests.",
        true,
        new HashMap()));

      result.tier = deriveTier(plug, state.hasNativeLibs, state.hasDangerousCalls);
      result.capabilitySummary = capabilitySummary(plug, state.hasNativeLibs, state.hasDangerousCalls);
      result.riskScore = calculateRiskScore(result);
    result.findings.add(new Finding("plugin.tier", Category.HYGIENE, Severity.INFO,
        "Plugin tier classification",
        "Plugin classified as tier " + result.tier + ". " + result.capabilitySummary,
        "Tier 2 plugins should only be installed from trusted sources.",
        true,
        mapOf("tier", Integer.valueOf(result.tier), "capabilities", result.capabilitySummary)));

      result.findings.add(new Finding("risk.score", Category.HYGIENE, Severity.INFO,
        "Risk score",
        "Computed install-time risk score is " + result.riskScore + " (0-100).",
        "Use this value for policy thresholds or operator review workflows.",
        true,
        mapOf("risk_score", Integer.valueOf(result.riskScore))));

    result.findings.add(new Finding("syft.sbom", Category.HYGIENE, Severity.INFO,
        "SBOM status",
        "SBOM generation is not yet integrated in this runtime path.",
        "Configure syft integration to attach SBOM in audit records.",
        true,
        new HashMap()));

    result.bundleSha256 = calcCombinedSHA256(packageMap.values());
    result.sourceRepo = hosts.isEmpty() ? "" : hosts.iterator().next().toString();
    return result;
  }

  private static final class InspectionState
  {
    boolean hasNativeLibs;
    boolean hasDangerousCalls;
    long extractedBytes;
  }

  private static void inspectArchiveInSandbox(File archiveFile, Result result, InspectionState state)
  {
    File sandboxRoot = null;
    ZipFile zf = null;
    try
    {
      sandboxRoot = createSandboxDir();
      zf = new ZipFile(archiveFile);
      Enumeration entries = zf.entries();
      while (entries.hasMoreElements())
      {
        ZipEntry ze = (ZipEntry) entries.nextElement();
        String entryName = ze.getName();
        if (isUnsafeZipEntry(entryName))
        {
          result.findings.add(new Finding("zip.entry_safety", Category.POLICY, Severity.BLOCK,
              "Unsafe archive entry",
              "The archive contains an unsafe path that could escape extraction root.",
              "Remove path traversal or absolute paths from the archive.",
              false,
              mapOf("entry", entryName, "archive", archiveFile.getName())));
          continue;
        }

        File outFile = resolveSandboxFile(sandboxRoot, entryName);
        if (outFile == null)
        {
          result.findings.add(new Finding("zip.entry_safety", Category.POLICY, Severity.BLOCK,
              "Unsafe archive entry",
              "The archive entry resolves outside of extraction sandbox.",
              "Remove path traversal or absolute paths from the archive.",
              false,
              mapOf("entry", entryName, "archive", archiveFile.getName())));
          continue;
        }

        if (ze.isDirectory())
        {
          if (!outFile.isDirectory() && !outFile.mkdirs())
          {
            result.findings.add(new Finding("zip.entry_safety", Category.POLICY, Severity.BLOCK,
                "Sandbox extraction failed",
                "Unable to create extraction directory for archive entry.",
                "Verify filesystem permissions in plugin security sandbox path.",
                true,
                mapOf("entry", entryName, "archive", archiveFile.getAbsolutePath())));
          }
          continue;
        }

        long declaredSize = ze.getSize();
        if (declaredSize > SANDBOX_ENTRY_MAX_BYTES)
        {
          result.findings.add(new Finding("zip.size_limit", Category.POLICY, Severity.BLOCK,
              "Archive entry exceeds extraction limit",
              "A ZIP entry exceeds the configured single-entry extraction limit.",
              "Split oversized payloads or reduce package footprint.",
              false,
              mapOf("entry", entryName, "size", Long.valueOf(declaredSize), "max", Long.valueOf(SANDBOX_ENTRY_MAX_BYTES))));
          continue;
        }

        long written = extractZipEntry(zf, ze, outFile, SANDBOX_ENTRY_MAX_BYTES);
        if (written < 0)
        {
          result.findings.add(new Finding("zip.entry_safety", Category.POLICY, Severity.BLOCK,
              "Sandbox extraction failed",
              "Archive entry could not be safely extracted to sandbox.",
              "Rebuild the archive and verify entry structure/size.",
              true,
              mapOf("entry", entryName, "archive", archiveFile.getAbsolutePath())));
          continue;
        }

        state.extractedBytes += written;
        String lowerEntry = entryName.toLowerCase(Locale.ROOT);
        if (isNativeLibraryName(lowerEntry))
          state.hasNativeLibs = true;

        if (lowerEntry.endsWith(".class") || lowerEntry.endsWith(".jar"))
        {
          byte[] payload = readFileBytes(outFile, INSPECT_READ_LIMIT);
          if (payload != null && containsDangerousMarkers(payload))
            state.hasDangerousCalls = true;
        }
      }
    }
    catch (IOException e)
    {
      result.findings.add(new Finding("zip.entry_safety", Category.POLICY, Severity.BLOCK,
          "Archive could not be inspected",
          "The package archive could not be inspected in sandbox for safety checks.",
          "Rebuild the package archive and retry installation.",
          true,
          mapOf("archive", archiveFile.getAbsolutePath(), "error", e.toString())));
    }
    finally
    {
      if (zf != null)
      {
        try
        {
          zf.close();
        }
        catch (IOException ignored)
        {
        }
      }
      if (sandboxRoot != null)
        deleteRecursive(sandboxRoot);
    }
  }

  private static File createSandboxDir() throws IOException
  {
    File base = new File(System.getProperty("java.io.tmpdir"));
    File dir = File.createTempFile("plugsec-", "", base);
    if (!dir.delete() || !dir.mkdirs())
      throw new IOException("Unable to create sandbox dir: " + dir.getAbsolutePath());
    return dir;
  }

  private static File resolveSandboxFile(File sandboxRoot, String entryName) throws IOException
  {
    String normalized = entryName.replace('\\', '/');
    while (normalized.startsWith("./"))
      normalized = normalized.substring(2);
    File target = new File(sandboxRoot, normalized);
    String rootPath = sandboxRoot.getCanonicalPath() + File.separator;
    String targetPath = target.getCanonicalPath();
    if (!targetPath.startsWith(rootPath))
      return null;
    return target;
  }

  private static long extractZipEntry(ZipFile zf, ZipEntry ze, File outFile, long maxBytes)
  {
    java.io.InputStream in = null;
    java.io.FileOutputStream out = null;
    try
    {
      File parent = outFile.getParentFile();
      if (parent != null && !parent.isDirectory() && !parent.mkdirs())
        return -1;
      in = zf.getInputStream(ze);
      out = new java.io.FileOutputStream(outFile);
      byte[] buf = new byte[8192];
      long total = 0;
      int read;
      while ((read = in.read(buf)) != -1)
      {
        total += read;
        if (total > maxBytes)
          return -1;
        out.write(buf, 0, read);
      }
      out.flush();
      return total;
    }
    catch (IOException e)
    {
      return -1;
    }
    finally
    {
      if (in != null)
      {
        try
        {
          in.close();
        }
        catch (IOException ignored)
        {
        }
      }
      if (out != null)
      {
        try
        {
          out.close();
        }
        catch (IOException ignored)
        {
        }
      }
    }
  }

  private static void deleteRecursive(File f)
  {
    if (f == null || !f.exists())
      return;
    if (f.isDirectory())
    {
      File[] kids = f.listFiles();
      if (kids != null)
      {
        for (int i = 0; i < kids.length; i++)
          deleteRecursive(kids[i]);
      }
    }
    if (!f.delete())
    {
      // best-effort cleanup of sandbox artifacts
    }
  }

  private static int calculateRiskScore(Result result)
  {
    int score = 0;
    for (int i = 0; i < result.findings.size(); i++)
    {
      Finding f = (Finding) result.findings.get(i);
      if (f.severity == Severity.BLOCK)
      {
        if (f.overridable)
          score += 20;
        else
          score += 50;
      }
      else if (f.severity == Severity.WARN)
      {
        score += 10;
      }
    }

    if (result.tier >= 2)
      score += 15;
    else if (result.tier == 1)
      score += 5;

    if (score > 100)
      score = 100;
    return score;
  }

  private static boolean isUnsafeZipEntry(String name)
  {
    String norm = name.replace('\\', '/');
    if (norm.startsWith("/") || norm.startsWith("../") || norm.contains("/../") || norm.contains("..\\"))
      return true;
    return norm.matches("^[a-zA-Z]:.*");
  }

  private static boolean containsDangerousMarkers(byte[] payload)
  {
    String s = new String(payload, StandardCharsets.ISO_8859_1);
    for (int i = 0; i < DANGEROUS_BYTECODE_MARKERS.length; i++)
    {
      if (s.contains(DANGEROUS_BYTECODE_MARKERS[i]))
        return true;
    }
    return false;
  }

  private static boolean isNativeLibraryName(String lower)
  {
    return lower.endsWith(".so") || lower.endsWith(".dll") || lower.endsWith(".dylib");
  }

  private static byte[] readZipEntry(ZipFile zf, ZipEntry ze, int maxBytes)
  {
    if (ze.isDirectory())
      return null;
    java.io.InputStream in = null;
    try
    {
      in = zf.getInputStream(ze);
      int alloc = Math.max(4096, Math.min(maxBytes, (int) Math.max(ze.getSize(), 4096)));
      byte[] buf = new byte[alloc];
      int read = in.read(buf);
      if (read <= 0)
        return null;
      if (read == buf.length)
        return buf;
      return Arrays.copyOf(buf, read);
    }
    catch (IOException e)
    {
      return null;
    }
    finally
    {
      if (in != null)
      {
        try
        {
          in.close();
        }
        catch (IOException ignored)
        {
        }
      }
    }
  }

  private static byte[] readFileBytes(File f, int maxBytes)
  {
    FileInputStream in = null;
    try
    {
      in = new FileInputStream(f);
      byte[] buf = new byte[Math.min(maxBytes, (int) Math.max(4096, Math.min(f.length(), maxBytes)))];
      int read = in.read(buf);
      if (read <= 0)
        return null;
      if (read == buf.length)
        return buf;
      return Arrays.copyOf(buf, read);
    }
    catch (IOException e)
    {
      return null;
    }
    finally
    {
      if (in != null)
      {
        try
        {
          in.close();
        }
        catch (IOException ignored)
        {
        }
      }
    }
  }

  private static int deriveTier(PluginWrapper plug, boolean hasNativeLibs, boolean hasDangerousCalls)
  {
    PluginWrapper.Package[] pkgs = plug.getPackages();
    boolean executable = false;
    for (int i = 0; i < pkgs.length; i++)
    {
      String t = pkgs[i].type == null ? "" : pkgs[i].type;
      if ("JAR".equalsIgnoreCase(t) || "System".equalsIgnoreCase(t))
      {
        executable = true;
        break;
      }
    }
    if (hasNativeLibs || hasDangerousCalls || executable || CorePluginManager.STANDARD_TYPE_PLUGIN.equalsIgnoreCase(plug.getType())
        || CorePluginManager.LIBRARY_TYPE_PLUGIN.equalsIgnoreCase(plug.getType()))
      return 2;

    if (CorePluginManager.STVI_TYPE_PLUGIN.equalsIgnoreCase(plug.getType()) || CorePluginManager.STV_TYPE_PLUGIN.equalsIgnoreCase(plug.getType()))
      return 1;

    return 0;
  }

  private static String capabilitySummary(PluginWrapper plug, boolean hasNativeLibs, boolean hasDangerousCalls)
  {
    StringBuilder sb = new StringBuilder();
    if (hasDangerousCalls)
      sb.append("uses runtime/process/socket APIs; ");
    if (hasNativeLibs)
      sb.append("contains native libraries; ");
    if (CorePluginManager.STANDARD_TYPE_PLUGIN.equalsIgnoreCase(plug.getType()) ||
        CorePluginManager.LIBRARY_TYPE_PLUGIN.equalsIgnoreCase(plug.getType()))
      sb.append("loads executable Java code; ");
    if (sb.length() == 0)
      sb.append("asset-focused plugin payload");
    return sb.toString().trim();
  }

  private static Set getAllowlist()
  {
    String defaultAllow = "download.sagetv.com,raw.githubusercontent.com,github.com";
    String raw = Sage.get("sagetv_repo_allowlist", defaultAllow);
    Set rv = new HashSet();
    String[] bits = raw.split(",");
    for (int i = 0; i < bits.length; i++)
    {
      String x = bits[i].trim();
      if (x.length() > 0)
        rv.add(x.toLowerCase(Locale.ROOT));
    }
    return rv;
  }

  private static boolean isAllowlisted(String url, Set allowlist)
  {
    if (allowlist == null || allowlist.isEmpty())
      return true;
    String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
    String host = getUrlHost(url).toLowerCase(Locale.ROOT);
    for (java.util.Iterator it = allowlist.iterator(); it.hasNext();)
    {
      String token = it.next().toString();
      if (token.startsWith("http://") || token.startsWith("https://"))
      {
        if (lower.startsWith(token))
          return true;
      }
      else
      {
        if (host.equals(token) || host.endsWith("." + token))
          return true;
      }
    }
    return false;
  }

  private static String getUrlScheme(String url)
  {
    try
    {
      java.net.URL u = new java.net.URL(url);
      return u.getProtocol();
    }
    catch (Exception e)
    {
      return "";
    }
  }

  private static String getUrlHost(String url)
  {
    try
    {
      java.net.URL u = new java.net.URL(url);
      return u.getHost() == null ? "" : u.getHost();
    }
    catch (Exception e)
    {
      return "";
    }
  }

  private static boolean isScannerConfigured(String property)
  {
    String cmd = Sage.get(property, "");
    return cmd != null && cmd.trim().length() > 0;
  }

  private static String calcSHA256(File f)
  {
    FileInputStream in = null;
    try
    {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      in = new FileInputStream(f);
      byte[] buf = new byte[65536];
      int read;
      while ((read = in.read(buf)) != -1)
      {
        md.update(buf, 0, read);
      }
      return toHex(md.digest());
    }
    catch (IOException e)
    {
      return "";
    }
    catch (NoSuchAlgorithmException e)
    {
      return "";
    }
    finally
    {
      if (in != null)
      {
        try
        {
          in.close();
        }
        catch (IOException ignored)
        {
        }
      }
    }
  }

  private static String calcCombinedSHA256(java.util.Collection files)
  {
    try
    {
      List hashes = new ArrayList();
      for (java.util.Iterator it = files.iterator(); it.hasNext();)
      {
        File f = (File) it.next();
        hashes.add(calcSHA256(f));
      }
      java.util.Collections.sort(hashes);
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      for (int i = 0; i < hashes.size(); i++)
      {
        String h = hashes.get(i).toString();
        md.update(h.getBytes(StandardCharsets.UTF_8));
      }
      return toHex(md.digest());
    }
    catch (NoSuchAlgorithmException e)
    {
      return "";
    }
  }

  private static String toHex(byte[] data)
  {
    StringBuilder sb = new StringBuilder(data.length * 2);
    for (int i = 0; i < data.length; i++)
    {
      int v = data[i] & 0xFF;
      if (v < 16)
        sb.append('0');
      sb.append(Integer.toHexString(v));
    }
    return sb.toString();
  }

  private static Map mapOf(Object... kv)
  {
    Map m = new HashMap();
    for (int i = 0; i + 1 < kv.length; i += 2)
      m.put(kv[i], kv[i + 1]);
    return m;
  }

  public static String jsonEscape(String s)
  {
    if (s == null)
      return "";
    StringBuilder out = new StringBuilder(s.length() + 16);
    for (int i = 0; i < s.length(); i++)
    {
      char c = s.charAt(i);
      switch (c)
      {
        case '\\': out.append("\\\\"); break;
        case '"': out.append("\\\""); break;
        case '\b': out.append("\\b"); break;
        case '\f': out.append("\\f"); break;
        case '\n': out.append("\\n"); break;
        case '\r': out.append("\\r"); break;
        case '\t': out.append("\\t"); break;
        default:
          if (c < 0x20)
          {
            String hex = Integer.toHexString(c);
            out.append("\\u");
            for (int j = hex.length(); j < 4; j++) out.append('0');
            out.append(hex);
          }
          else
            out.append(c);
      }
    }
    return out.toString();
  }
}
