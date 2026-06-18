# deploy_stv.ps1 - in-place STV deploy: stopsage -> copy -> startsage. Never docker stop/restart.
# Pre-check: deployed Sage.jar BUILD_VERSION should match repo HEAD (STV+jar must stay in lockstep).
# Pass -Force to skip the consistency gate.
param([switch]$Force)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\_version_gate.ps1"
$host_addr = '<DEPLOY_HOST>'
$local = 'C:\Users\ted\SageTV-mine\stvs\SageTV7\SageTV7.xml'

# Auto-snapshot working tree (recoverable via refs/wip-safety/*)
& "$PSScriptRoot\snapshot_safety.ps1" -Message 'pre-deploy_stv' -Quiet

Write-Host '=== [0/5] BUILD_VERSION consistency check ===' -ForegroundColor Cyan
if ($Force) {
    Write-Host '[skip] -Force passed; not contacting host for version check' -ForegroundColor Yellow
} else {
    try {
        $ver = Test-DeployedMatchesRepo
    } catch {
        Write-Host "[error] gate check failed: $_" -ForegroundColor Red
        Write-Host '         rerun with -Force to bypass (host may be unreachable).' -ForegroundColor Yellow
        exit 7
    }
    Write-Host ("repo BUILD_VERSION     = {0}" -f $ver.Repo)
    Write-Host ("deployed BUILD_VERSION = {0}" -f $ver.Deployed)
    Write-Host ("deployed Sage.jar md5  = {0}" -f $ver.Md5)
    if (-not $ver.Match) {
        Write-Host ("[drift] repo and deployed BUILD_VERSION differ ({0} vs {1})" -f $ver.Repo, $ver.Deployed) -ForegroundColor Yellow
        Write-Host '         deploy a fresh jar first OR rerun with -Force to push STV anyway.' -ForegroundColor Yellow
        exit 2
    }
    Write-Host '[ok] deployed jar matches repo' -ForegroundColor Green
}
Write-Host ''

Write-Host '=== [1/5] scp STV to host /tmp ===' -ForegroundColor Cyan
scp -o ConnectTimeout=15 $local "${host_addr}:/tmp/SageTV7.xml"
if ($LASTEXITCODE -ne 0) { Write-Host 'scp failed' -ForegroundColor Red; exit 1 }

Write-Host ''
Write-Host '=== [2/5] stopsage (graceful, in-container) ===' -ForegroundColor Cyan
ssh -n -o ConnectTimeout=15 -o BatchMode=yes $host_addr 'docker exec sagetv-mine /opt/sagetv/server/stopsage; sleep 4; (docker exec sagetv-mine pgrep -x java >/dev/null && echo "[warn] java still running") || echo "[ok] java is down"'

Write-Host ''
Write-Host '=== [3/5] docker cp + chown ===' -ForegroundColor Cyan
ssh -n -o ConnectTimeout=15 -o BatchMode=yes $host_addr 'docker cp /tmp/SageTV7.xml sagetv-mine:/opt/sagetv/server/STVs/SageTV7/SageTV7.xml; docker exec sagetv-mine chown sagetv:sagetv /opt/sagetv/server/STVs/SageTV7/SageTV7.xml 2>/dev/null; docker exec sagetv-mine ls -l /opt/sagetv/server/STVs/SageTV7/SageTV7.xml'

Write-Host ''
Write-Host '=== [4/5] verify contents (NGDLQ + CAP sym counts) ===' -ForegroundColor Cyan
ssh -n -o ConnectTimeout=15 -o BatchMode=yes $host_addr 'docker exec sagetv-mine grep -c NGDLQ- /opt/sagetv/server/STVs/SageTV7/SageTV7.xml'
ssh -n -o ConnectTimeout=15 -o BatchMode=yes $host_addr 'docker exec sagetv-mine grep -c CAP- /opt/sagetv/server/STVs/SageTV7/SageTV7.xml'

Write-Host ''
Write-Host '=== [5/5] startsage ===' -ForegroundColor Cyan
ssh -n -o ConnectTimeout=15 -o BatchMode=yes $host_addr 'docker exec sagetv-mine /opt/sagetv/server/startsage; sleep 4; (docker exec sagetv-mine pgrep -x java >/dev/null && echo "[ok] java is up") || echo "[warn] java did not come up"'

Write-Host ''
Write-Host '=== Deploy complete ===' -ForegroundColor Green
