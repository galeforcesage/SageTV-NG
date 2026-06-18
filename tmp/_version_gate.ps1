# _version_gate.ps1 - shared helpers for BUILD_VERSION drift detection.
# Dot-source from deploy_*.ps1: . "$PSScriptRoot\_version_gate.ps1"

$script:HostAddr = '<DEPLOY_HOST>'
$script:RepoRoot = 'C:\Users\ted\SageTV-mine'

function Get-RepoBuildVersion {
    $path = Join-Path $script:RepoRoot 'java\sage\SageConstants.java'
    if (-not (Test-Path $path)) { throw "missing $path" }
    $m = Select-String -Path $path -Pattern 'BUILD_VERSION\s*=\s*(\d+)' | Select-Object -First 1
    if (-not $m) { throw "BUILD_VERSION not found in $path" }
    return [int]$m.Matches[0].Groups[1].Value
}

function Get-LocalJarBuildVersion {
    param([string]$JarPath)
    if (-not (Test-Path $JarPath)) { throw "jar not found: $JarPath" }
    $javap = Join-Path $env:JAVA_HOME 'bin\javap.exe'
    if (-not (Test-Path $javap)) { throw "javap not found at $javap (JAVA_HOME=$env:JAVA_HOME)" }
    $out = & $javap -p -c -constants -cp $JarPath sage.SageConstants 2>$null
    $line = $out | Select-String 'BUILD_VERSION\s*=\s*(\d+)' | Select-Object -First 1
    if (-not $line) { throw "BUILD_VERSION not in $JarPath" }
    return [int]$line.Matches[0].Groups[1].Value
}

function Get-DeployedBuildVersion {
    # -n: redirect stdin from NUL. Without it, ssh inherits the parent powershell's stdin,
    # which under `powershell -File` is non-interactive but not closed -- ssh then hangs
    # waiting on it. Dot-sourced in an interactive shell stdin is the console (a tty)
    # which ssh handles correctly, so the bug only appears under `-File`.
    $out = ssh -n -o ConnectTimeout=15 -o BatchMode=yes $script:HostAddr 'docker exec sagetv-mine javap -p -c -constants -cp /opt/sagetv/server/Sage.jar sage.SageConstants 2>/dev/null'
    $line = $out | Select-String 'BUILD_VERSION\s*=\s*(\d+)' | Select-Object -First 1
    if (-not $line) { throw "could not read deployed BUILD_VERSION (ssh/docker output empty)" }
    return [int]$line.Matches[0].Groups[1].Value
}

function Get-DeployedJarMd5 {
    $out = ssh -n -o ConnectTimeout=15 -o BatchMode=yes $script:HostAddr 'docker exec sagetv-mine md5sum /opt/sagetv/server/Sage.jar'
    if ($out -match '^([a-f0-9]{32})\s') { return $Matches[1] }
    throw "could not read deployed jar md5 (got: $out)"
}

function Get-LocalFileMd5 {
    param([string]$Path)
    return (Get-FileHash -Algorithm MD5 -Path $Path).Hash.ToLower()
}

# Assert deployed jar version matches repo (consistency check for STV-only deploys).
# Returns @{ Repo=N; Deployed=N; Match=$true/$false }.
function Test-DeployedMatchesRepo {
    $repo = Get-RepoBuildVersion
    $dep  = Get-DeployedBuildVersion
    $md5  = Get-DeployedJarMd5
    return [pscustomobject]@{
        Repo     = $repo
        Deployed = $dep
        Md5      = $md5
        Match    = ($repo -eq $dep)
    }
}
