# deploy_jar.ps1 - in-place Sage.jar deploy: stopsage -> copy -> startsage.
# HARD RULE: never docker stop/start/restart sagetv-mine.
#
# Gate: refuses to deploy unless local jar BUILD_VERSION > deployed BUILD_VERSION.
# That means every java-touching commit MUST bump BUILD_VERSION (see SageConstants.java).
#
#   -SkipBuild  : reuse existing build/libs/Sage.jar instead of running gradlew sageJar
#   -Force      : skip the BUILD_VERSION gate (use only when intentionally redeploying same version)
#
# Always run from C:\Users\ted\SageTV-mine.

param(
    [switch]$SkipBuild,
    [switch]$Force
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\_version_gate.ps1"

$host_addr = '<DEPLOY_HOST>'
$jar       = 'C:\Users\ted\SageTV-mine\build\libs\Sage.jar'

# ---- [0/7] Auto-snapshot working tree (recoverable via refs/wip-safety/*) ----
& "$PSScriptRoot\snapshot_safety.ps1" -Message 'pre-deploy_jar' -Quiet

# ---- [0/7] Build (unless -SkipBuild) -----------------------------------------
if (-not $SkipBuild) {
    Write-Host '=== [0/7] gradle sageJar ===' -ForegroundColor Cyan
    Push-Location 'C:\Users\ted\SageTV-mine'
    try {
        $env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'
        & .\gradlew.bat sageJar
        if ($LASTEXITCODE -ne 0) { Write-Host 'gradle build failed' -ForegroundColor Red; exit 1 }
    } finally { Pop-Location }
    Write-Host ''
} else {
    Write-Host '=== [0/7] -SkipBuild: reusing existing build/libs/Sage.jar ===' -ForegroundColor Yellow
    Write-Host ''
}

if (-not (Test-Path $jar)) { Write-Host "build artifact missing: $jar" -ForegroundColor Red; exit 1 }

# ---- [1/7] Version gate ------------------------------------------------------
Write-Host '=== [1/7] BUILD_VERSION gate ===' -ForegroundColor Cyan
$repoVer  = Get-RepoBuildVersion
$jarVer   = Get-LocalJarBuildVersion -JarPath $jar
$depVer   = Get-DeployedBuildVersion
$jarMd5   = Get-LocalFileMd5 -Path $jar
$depMd5   = Get-DeployedJarMd5

Write-Host ("repo BUILD_VERSION     = {0}" -f $repoVer)
Write-Host ("local-jar BUILD_VERSION= {0}" -f $jarVer)
Write-Host ("deployed BUILD_VERSION = {0}" -f $depVer)
Write-Host ("local jar md5          = {0}" -f $jarMd5)
Write-Host ("deployed jar md5       = {0}" -f $depMd5)

if ($jarVer -ne $repoVer) {
    Write-Host "[stale-build] local jar BUILD_VERSION ($jarVer) != repo source ($repoVer). Rebuild with -SkipBuild dropped." -ForegroundColor Red
    if (-not $Force) { exit 3 }
}
if ($jarMd5 -eq $depMd5) {
    Write-Host '[no-op] local jar is byte-identical to deployed. Nothing to deploy.' -ForegroundColor Yellow
    if (-not $Force) { exit 0 }
}
if ($jarVer -lt $depVer) {
    Write-Host "[downgrade] local BUILD_VERSION ($jarVer) is OLDER than deployed ($depVer). Refusing." -ForegroundColor Red
    if (-not $Force) { exit 4 }
}
if ($jarVer -eq $depVer -and $jarMd5 -ne $depMd5) {
    Write-Host "[unbumped] BUILD_VERSION matches deployed ($jarVer) but jar bytes differ. Bump SageConstants.BUILD_VERSION before deploying, OR pass -Force." -ForegroundColor Yellow
    if (-not $Force) { exit 5 }
}
Write-Host ("[ok] deploying {0} -> {1}" -f $depVer, $jarVer) -ForegroundColor Green
Write-Host ''

# ---- [2/7] scp jar to host /tmp ---------------------------------------------
Write-Host '=== [2/7] scp Sage.jar to host /tmp ===' -ForegroundColor Cyan
scp -o ConnectTimeout=20 $jar "${host_addr}:/tmp/Sage.jar"
if ($LASTEXITCODE -ne 0) { Write-Host 'scp failed' -ForegroundColor Red; exit 1 }
Write-Host ''

# ---- [3/7] stopsage ----------------------------------------------------------
Write-Host '=== [3/7] stopsage (graceful, in-container) ===' -ForegroundColor Cyan
ssh -n -o ConnectTimeout=15 -o BatchMode=yes $host_addr 'docker exec sagetv-mine /opt/sagetv/server/stopsage; sleep 4; (docker exec sagetv-mine pgrep -x java >/dev/null && echo "[warn] java still running") || echo "[ok] java is down"'
Write-Host ''

# ---- [4/7] docker cp + chown ------------------------------------------------
Write-Host '=== [4/7] docker cp + chown ===' -ForegroundColor Cyan
ssh -n -o ConnectTimeout=15 -o BatchMode=yes $host_addr 'docker cp /tmp/Sage.jar sagetv-mine:/opt/sagetv/server/Sage.jar; docker exec sagetv-mine chown sagetv:sagetv /opt/sagetv/server/Sage.jar 2>/dev/null; docker exec sagetv-mine ls -l /opt/sagetv/server/Sage.jar'
Write-Host ''

# ---- [5/7] verify md5 + BUILD_VERSION post-copy -----------------------------
Write-Host '=== [5/7] verify post-copy ===' -ForegroundColor Cyan
ssh -n -o ConnectTimeout=15 -o BatchMode=yes $host_addr 'docker exec sagetv-mine md5sum /opt/sagetv/server/Sage.jar'
$postVer = Get-DeployedBuildVersion
Write-Host ("deployed BUILD_VERSION (post-copy) = {0}" -f $postVer)
if ($postVer -ne $jarVer) {
    Write-Host "[error] post-copy BUILD_VERSION ($postVer) != local jar ($jarVer)" -ForegroundColor Red
    Write-Host '         (Did the cp land in the wrong path? root Sage.jar is authoritative, NOT JARs/Sage.jar)' -ForegroundColor Yellow
    if (-not $Force) { exit 6 }
}
Write-Host ''

# ---- [6/7] startsage ---------------------------------------------------------
Write-Host '=== [6/7] startsage ===' -ForegroundColor Cyan
ssh -n -o ConnectTimeout=15 -o BatchMode=yes $host_addr 'docker exec sagetv-mine /opt/sagetv/server/startsage; sleep 4; (docker exec sagetv-mine pgrep -x java >/dev/null && echo "[ok] java is up (pid=$(docker exec sagetv-mine pgrep -x java))") || echo "[warn] java did not come up"'
Write-Host ''

# ---- [7/7] post-start sanity -------------------------------------------------
Write-Host '=== [7/7] post-start sanity ===' -ForegroundColor Cyan
ssh -n -o ConnectTimeout=15 -o BatchMode=yes $host_addr 'docker exec sagetv-mine sh -c "ls -l /opt/sagetv/server/sagetv_0.txt; tail -3 /opt/sagetv/server/sagetv_0.txt 2>/dev/null"'
Write-Host ''

Write-Host '=== Deploy complete ===' -ForegroundColor Green
Write-Host ("local md5={0}  deployed md5={1}  BUILD_VERSION={2}" -f $jarMd5, (Get-DeployedJarMd5), $jarVer) -ForegroundColor Green
