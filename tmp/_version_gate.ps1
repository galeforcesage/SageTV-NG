# _version_gate.ps1 - shared helpers for BUILD_VERSION drift detection.
# Dot-source from deploy_*.ps1: . "$PSScriptRoot\_version_gate.ps1"
#
# BUILD_VERSION IS NOW DERIVED FROM GIT (`git rev-list --count HEAD`).
# Every commit auto-increments the number. The old .buildnumber pin file is
# gone; SageConstants.java is generated into buildoutput/generated at build
# time and is NOT checked in. Semantics of each gate:
#   [stale-build]  local jar BV != current git-count(HEAD)
#                  -> you built at an older HEAD and made new commits;
#                     rebuild before deploying.
#   [no-op]        local jar md5 == deployed jar md5  -> nothing to do.
#   [downgrade]    local jar BV < deployed BV  -> older commit; refuse.
#   [unbumped]     local jar BV == deployed BV but bytes differ  ->
#                  same-commit rebuild deploying different bytes; this can
#                  ONLY happen if you built with uncommitted changes.
#   [dirty-tree]   `git status --porcelain` shows uncommitted Java/preset
#                  edits at build/deploy time. Refuse; commit first.

$script:HostAddr = if ($env:SAGE_DEPLOY_HOST) { $env:SAGE_DEPLOY_HOST } else { 'sagetv@<HOST>' }
$script:RepoRoot = if ($env:SAGE_REPO_ROOT)   { $env:SAGE_REPO_ROOT }   else { (Split-Path -Parent $PSScriptRoot) }
$script:Container = if ($env:SAGE_DEPLOY_CONTAINER) { $env:SAGE_DEPLOY_CONTAINER } else { 'sagetv' }

function Get-RepoBuildVersion {
    # BUILD_VERSION is derived from git commit count at build time; the source
    # file is generated (not checked in). The "repo version" for the gate is
    # therefore what the *next* build would produce = git rev-list --count HEAD.
    Push-Location $script:RepoRoot
    try {
        $out = git rev-list --count HEAD 2>$null
        if (-not $out) { throw "git rev-list failed in $script:RepoRoot" }
        return [int]($out.Trim())
    } finally { Pop-Location }
}

function Test-DirtyTree {
    # Returns $true when there are uncommitted changes to source files that
    # would ship in Sage.jar (Java sources, presets, resource paths). Docs,
    # scripts, and gitignored files are excluded. Used by deploy_jar.ps1 to
    # refuse dirty-tree deploys (see "[dirty-tree]" in header comment).
    Push-Location $script:RepoRoot
    try {
        $lines = git status --porcelain -- 'java/**' 'presets/**' 'stvs/**' 'i18n/**' 2>$null
        return [bool]($lines -and $lines.Trim().Length -gt 0)
    } finally { Pop-Location }
}

function Get-DirtyTreeSummary {
    Push-Location $script:RepoRoot
    try {
        return (git status --porcelain -- 'java/**' 'presets/**' 'stvs/**' 'i18n/**' 2>$null)
    } finally { Pop-Location }
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
    $out = ssh -n -o ConnectTimeout=15 -o BatchMode=yes $script:HostAddr ('docker exec {0} javap -p -c -constants -cp /opt/sagetv/server/Sage.jar sage.SageConstants 2>/dev/null' -f $script:Container)
    $line = $out | Select-String 'BUILD_VERSION\s*=\s*(\d+)' | Select-Object -First 1
    if (-not $line) { throw "could not read deployed BUILD_VERSION (ssh/docker output empty)" }
    return [int]$line.Matches[0].Groups[1].Value
}

function Get-DeployedJarMd5 {
    $out = ssh -n -o ConnectTimeout=15 -o BatchMode=yes $script:HostAddr ('docker exec {0} md5sum /opt/sagetv/server/Sage.jar' -f $script:Container)
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
