param(
    [string]$Agent = "laptop",
    [string]$SourceMods = "C:\Users\Naquan\curseforge\minecraft\Instances\FGrr (1)\mods",
    [string]$TargetMods = "C:\Users\Naqua\curseforge\minecraft\Instances\FGrr\mods",
    [int]$MaxPasses = 10
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not (Test-Path $SourceMods)) {
    throw "Source mods folder not found: $SourceMods"
}

if ([string]::IsNullOrWhiteSpace($env:LCR_TOKEN)) {
    throw "Missing LCR_TOKEN env var."
}

$lcrArgs = @()
if ($env:LCR_URL) { $lcrArgs += @("--url", $env:LCR_URL) }
$lcrArgs += @("--token", $env:LCR_TOKEN)

Write-Host "Source: $SourceMods"
Write-Host "Agent:  $Agent"
Write-Host "Target: $TargetMods"
Write-Host ""

# Ensure target exists
lcr pwsh $Agent @lcrArgs "New-Item -ItemType Directory -Force -Path '$TargetMods' | Out-Null"

for ($pass = 1; $pass -le $MaxPasses; $pass++) {
    Write-Host "=== PASS $pass ==="

    # get remote file map
    $remoteScan = @"
if (Test-Path -LiteralPath '$TargetMods') {
    Get-ChildItem -LiteralPath '$TargetMods' -File |
        Select-Object Name, Length |
        ConvertTo-Json -Compress
} else { '[]' }
"@

    $remoteJson = lcr pwsh $Agent @lcrArgs $remoteScan
    if ($LASTEXITCODE -ne 0) {
        throw "Remote scan failed"
    }

    $remoteFiles = @()
    if ($remoteJson) {
        $parsed = $remoteJson | ConvertFrom-Json
        if ($parsed -is [array]) { $remoteFiles = $parsed }
        elseif ($parsed) { $remoteFiles = @($parsed) }
    }

    $remoteMap = @{}
    foreach ($f in $remoteFiles) {
        $remoteMap[$f.Name] = [int64]$f.Length
    }

    $localFiles = Get-ChildItem -LiteralPath $SourceMods -File

    $copied = 0
    $skipped = 0

    foreach ($file in $localFiles) {
        $name = $file.Name
        $localSize = [int64]$file.Length

        $needsCopy = $false

        if (-not $remoteMap.ContainsKey($name)) {
            $needsCopy = $true
        }
        elseif ($remoteMap[$name] -ne $localSize) {
            $needsCopy = $true
        }

        if (-not $needsCopy) {
            $skipped++
            continue
        }

        $dest = "$TargetMods\$name"
        Write-Host "Copy: $name"

        lcr put $Agent $file.FullName $dest @lcrArgs
        if ($LASTEXITCODE -ne 0) {
            Write-Host "Failed copy: $name (will retry next pass)"
            continue
        }

        $copied++
    }

    Write-Host "Pass $pass -> Copied: $copied | Skipped: $skipped"

    # convergence check: nothing copied = done
    if ($copied -eq 0) {
        Write-Host "`nAll files are synced. Done."
        break
    }

    if ($pass -eq $MaxPasses) {
        Write-Host "`nHit max passes ($MaxPasses). Some files may still be out of sync."
    }

    Start-Sleep -Seconds 1
}