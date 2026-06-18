# snapshot_safety.ps1 - Capture tracked + untracked working tree into a permanent
# git ref WITHOUT modifying the working tree. Idempotent and safe to call before
# any destructive operation (revert, reset --hard, branch switch, rm -rf, etc).
#
# The snapshot is stored as refs/wip-safety/<timestamp>. To recover a file later:
#   git show <ref>:<path>           # dump a single file from the snapshot
#   git checkout <ref> -- <path>    # restore a single file into the working tree
#   git log --all --oneline -- <path>  # see snapshot refs touching a file
#
# List all safety snapshots:
#   git for-each-ref refs/wip-safety/
#
# Prune snapshots older than N days (default keep all):
#   .\tmp\snapshot_safety.ps1 -PruneOlderThanDays 30
#
# Always run from C:\Users\ted\SageTV-mine.
param(
    [string]$Message = "",
    [int]$PruneOlderThanDays = 0,
    [switch]$Quiet
)
$ErrorActionPreference = 'Stop'

# Optional prune pass first
if ($PruneOlderThanDays -gt 0) {
    $cutoff = (Get-Date).AddDays(-$PruneOlderThanDays)
    git for-each-ref --format='%(refname) %(*creatordate:iso8601)%(creatordate:iso8601)' refs/wip-safety/ | ForEach-Object {
        $parts = $_ -split ' ',2
        $ref = $parts[0]
        try {
            $d = [datetime]::Parse($parts[1])
            if ($d -lt $cutoff) {
                git update-ref -d $ref
                if (-not $Quiet) { Write-Host "[prune] removed $ref ($($parts[1]))" -ForegroundColor Yellow }
            }
        } catch {}
    }
}

$ts = Get-Date -Format 'yyyyMMdd-HHmmss'
$msg = "safety-$ts"
if ($Message) { $msg = "$msg`: $Message" }

# stash create captures index + working tree + (-u) untracked, without modifying anything
$snap = git stash create -u $msg
if (-not $snap) {
    if (-not $Quiet) { Write-Host "[clean] working tree is clean - no snapshot needed" -ForegroundColor Green }
    exit 0
}

$ref = "refs/wip-safety/$ts"
git update-ref $ref $snap

if (-not $Quiet) {
    Write-Host "[ok] snapshot ref = $ref" -ForegroundColor Green
    Write-Host "[ok] snapshot sha = $snap" -ForegroundColor Green
    Write-Host "--- tracked diff vs HEAD ---"
    git diff --name-status HEAD $snap
    # Untracked files live in stash parent #3
    $untracked = git rev-parse "$snap^3" 2>$null
    if ($LASTEXITCODE -eq 0 -and $untracked) {
        Write-Host "--- untracked snapshot tree ---"
        git ls-tree -r --name-only $untracked
    }
}
