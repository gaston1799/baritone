param(
    [string]$ModsDir = 'C:\Users\Naquan\curseforge\minecraft\Instances\Baritone\mods',
    [string]$JavaHome = '',
    [string]$GradleUserHome = '',
    [string]$ExpectedMinecraftVersion = '1.21.4'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Test-FileLocked {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    try {
        $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
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

$minecraftVersion = Get-GradleProperty -Path $gradleProperties -Name 'minecraft_version'
$requiredJavaVersion = Get-GradleProperty -Path $gradleProperties -Name 'java_version'

if ($minecraftVersion -ne $ExpectedMinecraftVersion) {
    throw "This checkout targets Minecraft $minecraftVersion, not $ExpectedMinecraftVersion. Switch to a $ExpectedMinecraftVersion-compatible branch or tag (for example v1.13.1) and rerun."
}

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $JavaHome = Join-Path $env:USERPROFILE "scoop\apps\temurin$requiredJavaVersion-jdk\current"
}

if ([string]::IsNullOrWhiteSpace($GradleUserHome)) {
    $GradleUserHome = Join-Path $workspaceRoot '.gradle-home'
}

if (-not (Test-Path $ModsDir)) {
    throw "Mods directory not found: $ModsDir"
}

if (-not (Test-Path $JavaHome)) {
    throw "Java home not found: $JavaHome"
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
Write-Host "MC:      $minecraftVersion"
Write-Host "Java:    $JavaHome"
Write-Host "Mods:    $ModsDir"

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

    $existingJars = Get-ChildItem $ModsDir -Filter 'baritone-fabric-*.jar' -ErrorAction SilentlyContinue
    $lockedJar = $existingJars | Where-Object { Test-FileLocked $_.FullName } | Select-Object -First 1
    if ($null -ne $lockedJar) {
        throw "Cannot replace '$($lockedJar.FullName)' because it is in use by a running process. Close the Baritone Minecraft instance and rerun this script."
    }

    $existingJars | Remove-Item -Force

    $destination = Join-Path $ModsDir $jar.Name
    Copy-Item $jar.FullName $destination -Force

    Write-Host ''
    Write-Host "Deployed: $destination"
}
finally {
    Pop-Location
}
