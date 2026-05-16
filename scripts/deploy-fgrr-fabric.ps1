param(
    [string[]]$ModsDir = @(
        #1.21.4 test failed 'C:\Users\Naquan\curseforge\minecraft\Instances\botonly1.21.4'
        'C:\Users\Naquan\curseforge\minecraft\Instances\FGrr\mods',
        'C:\Users\Naquan\curseforge\minecraft\Instances\FGrr (1)\mods',
        'C:\Users\Naquan\curseforge\minecraft\Instances\botOnly\mods'
    ),
    [bool]$DeployRemote = $true,
    [hashtable[]]$RemoteTargets = @(
        #1.21.4 test failed @{ Agent = 'StreamPC'; ModsDir = 'C:\Users\tobeu\curseforge\minecraft\Instances\botonly1.21.4' }
        @{ Agent = 'StreamPC'; ModsDir = 'C:\Users\tobeu\curseforge\minecraft\Instances\FGrr (2)\mods' },
        @{ Agent = 'laptop';   ModsDir = 'C:\Users\Naqua\curseforge\minecraft\Instances\FGrr\mods' },
       @{ Agent = 'laptop';   ModsDir = 'C:\Users\Naqua\curseforge\minecraft\Instances\bot\mods' }
    ),
    [string]$RemoteToken = $env:LCR_TOKEN,
    [string]$RemoteUrl = $env:LCR_URL,
    [string]$JavaHome = '',
    [string]$GradleUserHome = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Test-FileLocked {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    try {
        $stream = [System.IO.File]::Open(
            $Path,
            [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::ReadWrite,
            [System.IO.FileShare]::None
        )
        $stream.Close()
        return $false
    } catch [System.IO.IOException] {
        return $true
    }
}

function Get-GradleProperty {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    $prefix = "$Name="
    $line = Get-Content $Path | Where-Object { $_.StartsWith($prefix) } | Select-Object -First 1
    if ($null -eq $line) {
        throw "Required Gradle property '$Name' not found in $Path"
    }
    return $line.Substring($prefix.Length).Trim()
}

$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$workspaceRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot '..'))
$gradleProperties = Join-Path $projectRoot 'gradle.properties'

if (-not (Test-Path $gradleProperties)) {
    throw "gradle.properties not found: $gradleProperties"
}

$requiredJavaVersion = Get-GradleProperty -Path $gradleProperties -Name 'java_version'

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $JavaHome = Join-Path $env:USERPROFILE "scoop\apps\temurin$requiredJavaVersion-jdk\current"
}

if ([string]::IsNullOrWhiteSpace($GradleUserHome)) {
    $GradleUserHome = Join-Path $workspaceRoot '.gradle-home'
}

foreach ($dir in $ModsDir) {
    if (-not (Test-Path $dir)) {
        throw "Mods directory not found: $dir"
    }
}

if (-not (Test-Path $JavaHome)) {
    throw "Java 17 home not found: $JavaHome"
}

if ($DeployRemote) {
    if ($null -eq (Get-Command lcr -ErrorAction SilentlyContinue)) {
        throw 'DeployRemote is enabled but lcr was not found on PATH.'
    }

    if ([string]::IsNullOrWhiteSpace($RemoteToken)) {
        throw 'DeployRemote is enabled but no LCR token was provided. Set LCR_TOKEN or pass -RemoteToken <token>.'
    }
}

$javaExe = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path $javaExe)) {
    throw "java.exe not found under: $JavaHome"
}

$gradlew = Join-Path $projectRoot 'gradlew.bat'
if (-not (Test-Path $gradlew)) {
    throw "Gradle wrapper not found: $gradlew"
}

New-Item -ItemType Directory -Force -Path $GradleUserHome | Out-Null

Write-Host "Project: $projectRoot"
Write-Host "Java:    $JavaHome"
Write-Host "Mods:"
foreach ($dir in $ModsDir) {
    Write-Host "  $dir"
}

if ($DeployRemote) {
    Write-Host "Remote:"
    $RemoteTargets | Group-Object -Property Agent | ForEach-Object {
        Write-Host "  [$($_.Name)]"
        $first = $true
        foreach ($t in $_.Group) {
            $tag = if ($first) { 'upload' } else { 'copy  ' }
            Write-Host "    $tag  $($t.ModsDir)"
            $first = $false
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($RemoteUrl)) {
        Write-Host "  Url: $RemoteUrl"
    }
}

Push-Location $projectRoot

try {
    $env:GRADLE_USER_HOME = $GradleUserHome
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$(Join-Path $JavaHome 'bin');$env:Path"

    & $gradlew ':fabric:remapJar' '--console' 'plain'
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }

    $jar = Get-ChildItem (Join-Path $projectRoot 'fabric\build\libs\baritone-fabric-*.jar') |
        Where-Object { $_.Name -notmatch 'dev|shadow' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if ($null -eq $jar) {
        throw 'No final Fabric jar was produced.'
    }

    foreach ($dir in $ModsDir) {
        $existingJars = Get-ChildItem $dir -Filter 'baritone-fabric-*.jar' -ErrorAction SilentlyContinue
        $lockedJar = $existingJars | Where-Object { Test-FileLocked $_.FullName } | Select-Object -First 1

        if ($null -ne $lockedJar) {
            throw "Cannot replace '$($lockedJar.FullName)' because it is in use by a running process. Close the FGrr Minecraft instances and rerun this script."
        }
    }

    foreach ($dir in $ModsDir) {
        $existingJars = Get-ChildItem $dir -Filter 'baritone-fabric-*.jar' -ErrorAction SilentlyContinue
        $existingJars | Remove-Item -Force

        $destination = Join-Path $dir $jar.Name
        Copy-Item $jar.FullName $destination -Force

        Write-Host ''
        Write-Host "Deployed: $destination"
    }

    if ($DeployRemote) {
        $remoteArgs = @()

        if (-not [string]::IsNullOrWhiteSpace($RemoteUrl)) {
            $remoteArgs += @('--url', $RemoteUrl)
        }

        $remoteArgs += @('--token', $RemoteToken)

        # Capture jar info into plain strings so $using: can capture them inside the parallel block
        $jarFullName = $jar.FullName
        $jarName     = $jar.Name

        # Group targets by agent so we upload once then remote-copy to the rest
        $agentGroups = $RemoteTargets |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_.Agent) -and -not [string]::IsNullOrWhiteSpace($_.ModsDir) } |
            Group-Object -Property Agent

        $results = $agentGroups | ForEach-Object -Parallel {
            $agent      = $_.Name
            $dirs       = @($_.Group | ForEach-Object { $_.ModsDir })
            $primaryDir = $dirs[0]
            $extraDirs  = @($dirs | Select-Object -Skip 1)
            $remoteArgs = $using:remoteArgs
            $jarFull    = $using:jarFullName
            $jarName    = $using:jarName
            $log        = [System.Text.StringBuilder]::new()

            try {
                $null = $log.AppendLine('')
                $null = $log.AppendLine("[$agent] Uploading to: $primaryDir")

                # Clean and upload to the primary dir
                $remotePrep = @"
New-Item -ItemType Directory -Force -Path '$primaryDir' | Out-Null
Get-ChildItem -LiteralPath '$primaryDir' -Filter '*baritone*.jar' -ErrorAction SilentlyContinue | Remove-Item -Force
"@
                $out = & lcr pwsh $agent @remoteArgs $remotePrep 2>&1
                if ($LASTEXITCODE -ne 0) { throw "Cleanup failed (exit $LASTEXITCODE):`n$($out -join "`n")" }

                $primaryDest = "$primaryDir\$jarName"
                $out = & lcr put $agent $jarFull $primaryDest @remoteArgs 2>&1
                if ($LASTEXITCODE -ne 0) { throw "Upload failed (exit $LASTEXITCODE):`n$($out -join "`n")" }
                $null = $log.AppendLine("[$agent] Uploaded:  $primaryDest")

                # Remote-copy from the primary to every additional dir — no extra uploads
                foreach ($extraDir in $extraDirs) {
                    $extraDest = "$extraDir\$jarName"
                    $remoteCopy = @"
New-Item -ItemType Directory -Force -Path '$extraDir' | Out-Null
Get-ChildItem -LiteralPath '$extraDir' -Filter '*baritone*.jar' -ErrorAction SilentlyContinue | Remove-Item -Force
Copy-Item -LiteralPath '$primaryDest' -Destination '$extraDest' -Force
"@
                    $out = & lcr pwsh $agent @remoteArgs $remoteCopy 2>&1
                    if ($LASTEXITCODE -ne 0) { throw "Copy to '$extraDir' failed (exit $LASTEXITCODE):`n$($out -join "`n")" }
                    $null = $log.AppendLine("[$agent] Copied:    $extraDest")
                }

                # Verify all dirs at once
                $dirList = ($dirs | ForEach-Object { "'$_'" }) -join ','
                $remoteVerify = @"
@($dirList) | ForEach-Object { Get-ChildItem -LiteralPath `$_ -Filter '*baritone*' | Select-Object @{N='Path';E={`$_.FullName}},Length,LastWriteTime }
"@
                $out = & lcr pwsh $agent @remoteArgs $remoteVerify 2>&1
                if ($LASTEXITCODE -ne 0) { throw "Verification failed (exit $LASTEXITCODE):`n$($out -join "`n")" }
                $null = $log.AppendLine(($out -join "`n"))

                [PSCustomObject]@{ Agent = $agent; Success = $true; Log = $log.ToString(); Error = '' }
            } catch {
                [PSCustomObject]@{ Agent = $agent; Success = $false; Log = $log.ToString(); Error = $_.Exception.Message }
            }
        }

        foreach ($result in $results) {
            Write-Host $result.Log.TrimEnd()
        }

        $failures = @($results | Where-Object { -not $_.Success })
        if ($failures.Count -gt 0) {
            foreach ($f in $failures) {
                Write-Host "FAILED [$($f.Agent)]: $($f.Error)"
            }
            throw "Remote deployment failed for: $($failures.Agent -join ', ')"
        }
    }
}
finally {
    Pop-Location
}
