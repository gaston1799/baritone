param(
    [switch]$NoWait
)

$ErrorActionPreference = 'Stop'

$InstanceId = '8628a017-2274-42ae-8def-bf5a10164ed6'
$GameId = 432
$InstancePath = 'C:\Users\Naquan\curseforge\minecraft\Instances\1.21.5'
$LaunchUri = "curseforge://launch-game?instanceId=$InstanceId&gameId=$GameId"

if (-not (Test-Path -LiteralPath $InstancePath)) {
    throw "CurseForge instance was not found: $InstancePath"
}

Write-Host 'Closing Minecraft for CurseForge instance 1.21.5, if it is running...'

$instancePathPattern = [regex]::Escape($InstancePath.TrimEnd('\'))
$gameProcesses = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object {
        $_.Name -in @('java.exe', 'javaw.exe', 'Minecraft.exe', 'Minecraft.Windows.exe') -and
        $_.CommandLine -and
        ($_.CommandLine -match $instancePathPattern -or $_.CommandLine -match [regex]::Escape("CFInstanceId=$InstanceId"))
    }

foreach ($processInfo in $gameProcesses) {
    $process = Get-Process -Id $processInfo.ProcessId -ErrorAction SilentlyContinue
    if ($null -eq $process) {
        continue
    }

    try {
        if ($process.MainWindowHandle -ne 0) {
            [void]$process.CloseMainWindow()
            if (-not $process.WaitForExit(10000)) {
                Stop-Process -Id $process.Id -Force
            }
        } else {
            Stop-Process -Id $process.Id -Force
        }
        Write-Host "Closed Minecraft process $($process.Id)."
    } catch {
        Write-Warning "Could not close Minecraft process $($process.Id): $($_.Exception.Message)"
    }
}

Write-Host 'Launching 1.21.5 through CurseForge...'
Start-Process -FilePath $LaunchUri

if (-not $NoWait) {
    $deadline = (Get-Date).AddSeconds(60)
    do {
        Start-Sleep -Seconds 2
        $started = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
            Where-Object {
                $_.Name -in @('java.exe', 'javaw.exe', 'Minecraft.exe', 'Minecraft.Windows.exe') -and
                $_.CommandLine -and
                ($_.CommandLine -match $instancePathPattern -or $_.CommandLine -match [regex]::Escape("CFInstanceId=$InstanceId"))
            }
    } while (-not $started -and (Get-Date) -lt $deadline)

    if ($started) {
        Write-Host 'Minecraft launch detected.'
    } else {
        Write-Warning 'CurseForge accepted the launch request, but Minecraft was not detected within 60 seconds.'
    }
}
