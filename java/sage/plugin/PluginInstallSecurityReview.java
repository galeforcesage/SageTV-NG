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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
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

  private static final long CLAMAV_TIMEOUT_MS = 60L * 1000L;
  private static final long GRYPE_TIMEOUT_MS = 120L * 1000L;

  private static final class ScannerResult
  {
    final int exitCode;
    final String stdout;
    final String stderr;
    final boolean timedOut;

    ScannerResult(int exitCode, String stdout, String stderr, boolean timedOut)
    {
      this.exitCode = exitCode;
      this.stdout = stdout;
      this.stderr = stderr;
      this.timedOut = timedOut;
    }
  }

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
        "SBOM generation planned",
        "Software Bill of Materials (SBOM) generation will be available in a future release via syft integration.",
        "No action required. SBOM generation will be enabled automatically when syft is configured.",
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

      // --- ClamAV malware scan on the extracted sandbox ---
      runClamAvScan(sandboxRoot, result);

      // --- Grype CVE scan on the extracted sandbox ---
      runGrypeScan(sandboxRoot, result);
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

  /**
   * Resolves a scanner command: first checks the Sage property, then falls back to
   * auto-detecting the binary on the system PATH.
   * @return the command string, or empty string if unavailable
   */
  private static String resolveScannerCommand(String property, String defaultBinary)
  {
    String configured = Sage.get(property, "");
    if (configured != null && configured.trim().length() > 0)
      return configured.trim();
    // Auto-detect: try to find the binary on PATH
    if (isBinaryAvailable(defaultBinary))
      return defaultBinary;
    return "";
  }

  /**
   * Checks whether a binary is available on the system PATH by attempting to run it
   * with --version. Returns true if the process starts successfully.
   */
  private static boolean isBinaryAvailable(String binaryName)
  {
    try
    {
      String[] cmd;
      String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
      if (os.contains("win"))
        cmd = new String[]{"where", binaryName};
      else
        cmd = new String[]{"which", binaryName};
      ProcessBuilder pb = new ProcessBuilder(cmd);
      pb.redirectErrorStream(true);
      Process p = pb.start();
      drainStream(p.getInputStream());
      int exit = p.waitFor();
      return exit == 0;
    }
    catch (Exception e)
    {
      return false;
    }
  }

  /**
   * Runs an external scanner command with timeout enforcement and proper stream draining.
   * The command is passed as a string array to avoid shell injection.
   */
  private static ScannerResult runExternalScanner(String[] cmd, File workDir, long timeoutMs)
  {
    Process process = null;
    try
    {
      ProcessBuilder pb = new ProcessBuilder(cmd);
      pb.directory(workDir);
      pb.redirectErrorStream(false);
      process = pb.start();

      // Drain stdout and stderr in separate threads to prevent deadlock
      final InputStream stdoutStream = process.getInputStream();
      final InputStream stderrStream = process.getErrorStream();
      final ByteArrayOutputStream stdoutBuf = new ByteArrayOutputStream();
      final ByteArrayOutputStream stderrBuf = new ByteArrayOutputStream();

      Thread stdoutDrainer = new Thread(new Runnable()
      {
        public void run()
        {
          drainStreamTo(stdoutStream, stdoutBuf);
        }
      }, "scanner-stdout-drainer");
      Thread stderrDrainer = new Thread(new Runnable()
      {
        public void run()
        {
          drainStreamTo(stderrStream, stderrBuf);
        }
      }, "scanner-stderr-drainer");

      stdoutDrainer.setDaemon(true);
      stderrDrainer.setDaemon(true);
      stdoutDrainer.start();
      stderrDrainer.start();

      // Wait with timeout
      long deadline = System.currentTimeMillis() + timeoutMs;
      boolean finished = false;
      while (!finished)
      {
        try
        {
          process.exitValue();
          finished = true;
        }
        catch (IllegalThreadStateException e)
        {
          if (System.currentTimeMillis() >= deadline)
          {
            process.destroy();
            stdoutDrainer.join(2000);
            stderrDrainer.join(2000);
            return new ScannerResult(-1,
                stdoutBuf.toString("UTF-8"),
                stderrBuf.toString("UTF-8"),
                true);
          }
          Thread.sleep(200);
        }
      }

      stdoutDrainer.join(5000);
      stderrDrainer.join(5000);

      return new ScannerResult(
          process.exitValue(),
          stdoutBuf.toString("UTF-8"),
          stderrBuf.toString("UTF-8"),
          false);
    }
    catch (Exception e)
    {
      if (Sage.DBG) System.out.println("Scanner execution error: " + e);
      return new ScannerResult(-1, "", e.toString(), false);
    }
    finally
    {
      if (process != null)
      {
        try
        {
          process.destroy();
        }
        catch (Exception ignored)
        {
        }
      }
    }
  }

  private static void drainStream(InputStream in)
  {
    try
    {
      byte[] buf = new byte[4096];
      while (in.read(buf) != -1)
      {
        // discard
      }
    }
    catch (IOException ignored)
    {
    }
  }

  private static void drainStreamTo(InputStream in, ByteArrayOutputStream out)
  {
    try
    {
      byte[] buf = new byte[4096];
      int read;
      while ((read = in.read(buf)) != -1)
      {
        out.write(buf, 0, read);
      }
    }
    catch (IOException ignored)
    {
    }
  }

  /**
   * Runs ClamAV scan on the sandbox directory and adds findings to the result.
   */
  private static void runClamAvScan(File sandboxRoot, Result result)
  {
    String cmd = resolveScannerCommand("plugin/security/clamav_cmd", "clamscan");
    if (cmd.length() == 0)
    {
      result.findings.add(new Finding("clamav.scan", Category.MALWARE, Severity.BLOCK,
          "ClamAV scanner unavailable",
          "ClamAV (clamscan) is not installed or not found on PATH, and plugin/security/clamav_cmd is not configured.",
          "Install ClamAV or set plugin/security/clamav_cmd to the clamscan binary path.",
          true,
          new HashMap()));
      return;
    }

    if (Sage.DBG) System.out.println("PluginSecurity: Running ClamAV scan on " + sandboxRoot.getAbsolutePath());

    String[] cmdArray = new String[]{cmd, "-r", "--no-summary", sandboxRoot.getAbsolutePath()};
    ScannerResult sr = runExternalScanner(cmdArray, sandboxRoot, CLAMAV_TIMEOUT_MS);

    if (sr.timedOut)
    {
      result.findings.add(new Finding("clamav.scan", Category.MALWARE, Severity.WARN,
          "ClamAV scan timed out",
          "ClamAV scan exceeded the " + (CLAMAV_TIMEOUT_MS / 1000) + " second time limit.",
          "Investigate plugin archive size or ClamAV configuration issues.",
          true,
          mapOf("timeout_seconds", Long.valueOf(CLAMAV_TIMEOUT_MS / 1000))));
      return;
    }

    if (sr.exitCode == 0)
    {
      if (Sage.DBG) System.out.println("PluginSecurity: ClamAV scan clean");
      return;
    }

    if (sr.exitCode == 1)
    {
      // Virus found — parse output for "filepath: virusname FOUND" lines
      String[] lines = sr.stdout.split("\n");
      int detectionCount = 0;
      for (int i = 0; i < lines.length; i++)
      {
        String line = lines[i].trim();
        if (line.endsWith("FOUND"))
        {
          detectionCount++;
          int colonIdx = line.lastIndexOf(':');
          String virusName = "unknown";
          String filePath = line;
          if (colonIdx > 0)
          {
            filePath = line.substring(0, colonIdx).trim();
            virusName = line.substring(colonIdx + 1).trim();
            if (virusName.endsWith("FOUND"))
              virusName = virusName.substring(0, virusName.length() - 5).trim();
          }
          result.findings.add(new Finding("clamav.scan", Category.MALWARE, Severity.BLOCK,
              "ClamAV: malware detected",
              "ClamAV identified malware signature: " + virusName,
              "Do not install this plugin. The archive contains known malware.",
              false,
              mapOf("virus_name", virusName, "file", filePath)));
        }
      }
      if (detectionCount == 0)
      {
        // Exit code 1 but no parseable FOUND lines
        result.findings.add(new Finding("clamav.scan", Category.MALWARE, Severity.BLOCK,
            "ClamAV: malware detected",
            "ClamAV reported a detection (exit code 1) but no details could be parsed.",
            "Do not install this plugin. Review ClamAV output manually.",
            false,
            mapOf("stdout", truncate(sr.stdout, 500), "stderr", truncate(sr.stderr, 500))));
      }
    }
    else
    {
      // Exit code 2 or other = scan error
      result.findings.add(new Finding("clamav.scan", Category.MALWARE, Severity.WARN,
          "ClamAV scan error",
          "ClamAV encountered an error during scanning (exit code " + sr.exitCode + ").",
          "Check ClamAV installation, virus database freshness, and filesystem permissions.",
          true,
          mapOf("exit_code", Integer.valueOf(sr.exitCode), "stderr", truncate(sr.stderr, 500))));
    }
  }

  /**
   * Runs Grype CVE scan on the sandbox directory and adds findings to the result.
   */
  private static void runGrypeScan(File sandboxRoot, Result result)
  {
    String cmd = resolveScannerCommand("plugin/security/grype_cmd", "grype");
    if (cmd.length() == 0)
    {
      result.findings.add(new Finding("grype.cve_scan", Category.POLICY, Severity.BLOCK,
          "Grype scanner unavailable",
          "Grype is not installed or not found on PATH, and plugin/security/grype_cmd is not configured.",
          "Install Grype or set plugin/security/grype_cmd to the grype binary path.",
          true,
          new HashMap()));
      return;
    }

    if (Sage.DBG) System.out.println("PluginSecurity: Running Grype CVE scan on " + sandboxRoot.getAbsolutePath());

    String[] cmdArray = new String[]{cmd, "dir:" + sandboxRoot.getAbsolutePath(), "-o", "json"};
    ScannerResult sr = runExternalScanner(cmdArray, sandboxRoot, GRYPE_TIMEOUT_MS);

    if (sr.timedOut)
    {
      result.findings.add(new Finding("grype.cve_scan", Category.POLICY, Severity.WARN,
          "Grype scan timed out",
          "Grype CVE scan exceeded the " + (GRYPE_TIMEOUT_MS / 1000) + " second time limit.",
          "Investigate plugin archive size or Grype database update issues.",
          true,
          mapOf("timeout_seconds", Long.valueOf(GRYPE_TIMEOUT_MS / 1000))));
      return;
    }

    if (sr.exitCode != 0 && sr.stdout.trim().length() == 0)
    {
      // Non-zero exit with no JSON output = execution error
      result.findings.add(new Finding("grype.cve_scan", Category.POLICY, Severity.WARN,
          "Grype scan error",
          "Grype encountered an error (exit code " + sr.exitCode + ").",
          "Check Grype installation, database freshness, and filesystem permissions.",
          true,
          mapOf("exit_code", Integer.valueOf(sr.exitCode), "stderr", truncate(sr.stderr, 500))));
      return;
    }

    // Parse JSON output for vulnerability matches
    parseGrypeJsonOutput(sr.stdout, result);
  }

  /**
   * Parses Grype JSON output and creates findings for each vulnerability match.
   * Uses simple string parsing to avoid a JSON library dependency.
   */
  private static void parseGrypeJsonOutput(String json, Result result)
  {
    int matchCount = 0;
    // Find "matches" array and parse individual vulnerability entries
    int matchesIdx = json.indexOf("\"matches\"");
    if (matchesIdx < 0)
    {
      if (Sage.DBG) System.out.println("PluginSecurity: Grype output has no matches key");
      return;
    }

    // Find array start
    int arrayStart = json.indexOf('[', matchesIdx);
    if (arrayStart < 0)
      return;

    // Walk through looking for vulnerability objects
    int pos = arrayStart;
    while (pos < json.length())
    {
      // Find next vulnerability block
      int vulnIdStart = json.indexOf("\"vulnerability\"", pos);
      if (vulnIdStart < 0)
        break;

      // Extract vulnerability ID
      String vulnId = extractJsonStringValue(json, vulnIdStart, "\"id\"");
      String severity = extractJsonStringValue(json, vulnIdStart, "\"severity\"");

      // Look for artifact info (comes after vulnerability in same match object)
      int artifactStart = json.indexOf("\"artifact\"", vulnIdStart);
      String artifactName = "";
      String artifactVersion = "";
      if (artifactStart > 0 && artifactStart < vulnIdStart + 2000)
      {
        artifactName = extractJsonStringValue(json, artifactStart, "\"name\"");
        artifactVersion = extractJsonStringValue(json, artifactStart, "\"version\"");
      }

      if (vulnId.length() > 0)
      {
        matchCount++;
        Severity sev = mapGrypeSeverity(severity);
        String detail = vulnId + " (" + severity + ") in " + artifactName;
        if (artifactVersion.length() > 0)
          detail += " " + artifactVersion;

        result.findings.add(new Finding("grype.cve_scan", Category.POLICY, sev,
            "CVE detected: " + vulnId,
            detail,
            "Update the affected dependency or verify the vulnerability is not exploitable in this context.",
            sev != Severity.BLOCK,
            mapOf("cve_id", vulnId, "severity", severity, "package", artifactName, "version", artifactVersion)));
      }

      // Move past this vulnerability block
      pos = vulnIdStart + 20;
      // Find next match boundary (next "vulnerability" key)
      int nextVuln = json.indexOf("\"vulnerability\"", pos);
      if (nextVuln < 0)
        break;
      pos = nextVuln;
    }

    if (Sage.DBG) System.out.println("PluginSecurity: Grype found " + matchCount + " vulnerabilities");
  }

  /**
   * Extracts a JSON string value for a given key starting from a position in the JSON string.
   * Simple parser — no library dependency required.
   */
  private static String extractJsonStringValue(String json, int searchFrom, String key)
  {
    int keyIdx = json.indexOf(key, searchFrom);
    if (keyIdx < 0 || keyIdx > searchFrom + 1500)
      return "";
    int colonIdx = json.indexOf(':', keyIdx + key.length());
    if (colonIdx < 0)
      return "";
    // Find opening quote of value
    int openQuote = json.indexOf('"', colonIdx + 1);
    if (openQuote < 0 || openQuote > colonIdx + 20)
      return "";
    int closeQuote = json.indexOf('"', openQuote + 1);
    if (closeQuote < 0)
      return "";
    return json.substring(openQuote + 1, closeQuote);
  }

  private static Severity mapGrypeSeverity(String severity)
  {
    if (severity == null)
      return Severity.INFO;
    String upper = severity.toUpperCase(Locale.ROOT);
    if ("CRITICAL".equals(upper) || "HIGH".equals(upper))
      return Severity.BLOCK;
    if ("MEDIUM".equals(upper))
      return Severity.WARN;
    return Severity.INFO;
  }

  private static String truncate(String s, int maxLen)
  {
    if (s == null)
      return "";
    if (s.length() <= maxLen)
      return s;
    return s.substring(0, maxLen) + "...[truncated]";
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
