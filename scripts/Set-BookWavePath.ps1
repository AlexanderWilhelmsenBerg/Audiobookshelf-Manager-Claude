<#
.SYNOPSIS
    Finds the Android SDK and JDK on this machine and puts them on the PATH of the current PowerShell
    session.

.DESCRIPTION
    Windows is where `adb`, `sdkmanager` and `apksigner` are least likely to already be on the PATH:
    Android Studio installs them under %LOCALAPPDATA% and does not touch the shell. The result is a
    session where `./gradlew` works and every command in the device-test document does not, which reads
    as the tools being missing when they are merely unreachable.

    This resolves them the same way the build does — local.properties first, then the environment, then
    the places Android Studio actually installs to — and exports the result.

.PARAMETER Persist
    Also write ANDROID_HOME and the PATH entries to the *user* environment, so new terminals inherit
    them. Off by default: changing a machine's environment is not something a script should do because
    somebody ran it to fix one shell.

.PARAMETER WriteLocalProperties
    Write sdk.dir into local.properties when it is missing. Off by default, for the same reason
    `check-local-environment.sh` needs --install to do it: a script that reports should not also edit.

.EXAMPLE
    . .\scripts\Set-BookWavePath.ps1

    Note the leading dot. **This script must be dot-sourced**, because a child process cannot change its
    parent's environment. Running it as `.\scripts\Set-BookWavePath.ps1` prints what it found and then
    throws the changes away with the process — so it says so rather than appearing to work.

.EXAMPLE
    . .\scripts\Set-BookWavePath.ps1 -Persist -WriteLocalProperties
#>
[CmdletBinding()]
param(
    [switch]$Persist,
    [switch]$WriteLocalProperties
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

function Write-Ok    { param($m) Write-Host "  [ok]   $m" -ForegroundColor Green }
function Write-Warn2 { param($m) Write-Host "  [warn] $m" -ForegroundColor Yellow }
function Write-Bad   { param($m) Write-Host "  [miss] $m" -ForegroundColor Red }
function Write-Note  { param($m) Write-Host "         $m" -ForegroundColor DarkGray }

# Dot-sourcing check. $MyInvocation.InvocationName is '.' only when dot-sourced, so this is the one
# reliable way to tell — and getting it wrong is the single most likely way to use this script.
$dotSourced = $MyInvocation.InvocationName -eq '.'

Write-Host "`nBookWave — tool paths for this session" -ForegroundColor Cyan

# ----------------------------------------------------------------- the Android SDK
# Resolution order matches the build's own and check-local-environment.sh's.
$sdk = $null
$staleLocalProps = $null
$localProps = Join-Path $repo 'local.properties'
if (Test-Path $localProps) {
    $line = Select-String -Path $localProps -Pattern '^sdk\.dir=(.+)$' | Select-Object -Last 1
    if ($line) {
        $fromProps = $line.Matches[0].Groups[1].Value -replace '\\\\', '\' -replace '\\:', ':'
        # A path that was right once and is not any more is the commonest way this goes wrong — a
        # reinstalled Studio, a moved SDK, a local.properties copied between machines. Saying "no SDK
        # found" there sends somebody to reinstall something they already have, so name it instead and
        # keep looking.
        if (Test-Path $fromProps) { $sdk = $fromProps } else { $staleLocalProps = $fromProps }
    }
}
if (-not $sdk -and $env:ANDROID_HOME)     { $sdk = $env:ANDROID_HOME }
if (-not $sdk -and $env:ANDROID_SDK_ROOT) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) {
    # Built from whichever variables are actually set. `Join-Path` throws on a null first argument, and
    # %LOCALAPPDATA% is null anywhere that is not Windows — which made this script impossible to run at
    # all off Windows, including for a syntax check. Found by running it, not by reading it.
    $candidates = @('C:\Android\Sdk')
    if ($env:LOCALAPPDATA) { $candidates += (Join-Path $env:LOCALAPPDATA 'Android\Sdk') }
    if ($env:USERPROFILE)  { $candidates += (Join-Path $env:USERPROFILE  'AppData\Local\Android\Sdk') }
    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) { $sdk = $candidate; break }
    }
}

$pathsToAdd = New-Object System.Collections.Generic.List[string]

if ($staleLocalProps) {
    Write-Bad "local.properties points at $staleLocalProps, which does not exist."
    Write-Note 'Fix or delete that sdk.dir line; the build reads it before anything else.'
}

if (-not $sdk -or -not (Test-Path $sdk)) {
    Write-Bad 'No Android SDK found.'
    Write-Note 'Install Android Studio, or set ANDROID_HOME, then re-run.'
    Write-Note 'Looked at: local.properties sdk.dir, $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT,'
    Write-Note '           %LOCALAPPDATA%\Android\Sdk, C:\Android\Sdk'
} else {
    Write-Ok "SDK at $sdk"
    $env:ANDROID_HOME = $sdk
    $env:ANDROID_SDK_ROOT = $sdk

    # platform-tools carries adb; build-tools carries apksigner and aapt2, which the device-test
    # document uses to read a version and a signature back out of an APK.
    $platformTools = Join-Path $sdk 'platform-tools'
    if (Test-Path (Join-Path $platformTools 'adb.exe')) {
        $pathsToAdd.Add($platformTools); Write-Ok 'platform-tools (adb)'
    } else {
        Write-Bad 'platform-tools is missing, so there is no adb.'
        Write-Note 'bash scripts/check-local-environment.sh --install    (Git Bash / WSL)'
    }

    # Newest build-tools rather than the pinned one: apksigner and aapt2 are compatible across
    # versions, and pinning here would break the moment the catalog moves.
    $buildTools = Join-Path $sdk 'build-tools'
    if (Test-Path $buildTools) {
        $newest = Get-ChildItem $buildTools -Directory |
            Sort-Object { try { [version]($_.Name -replace '[^0-9.]', '') } catch { [version]'0.0.0' } } |
            Select-Object -Last 1
        if ($newest) {
            $pathsToAdd.Add($newest.FullName); Write-Ok "build-tools $($newest.Name) (apksigner, aapt2)"
        }
    } else {
        Write-Bad 'build-tools is missing, so there is no apksigner.'
    }

    $cmdlineTools = Join-Path $sdk 'cmdline-tools\latest\bin'
    if (Test-Path $cmdlineTools) { $pathsToAdd.Add($cmdlineTools); Write-Ok 'cmdline-tools (sdkmanager)' }
    else { Write-Warn2 'No cmdline-tools, so sdkmanager cannot install anything.' }
}

# ----------------------------------------------------------------- the JDK
# The build configures no Gradle toolchain, so the JDK running Gradle is the one that compiles.
# 17 is the floor; CI uses 21.
$jdk = $env:JAVA_HOME
if (-not $jdk) {
    # Built from whichever variables are actually set. `Join-Path` throws on a null first argument, and
    # %LOCALAPPDATA% is null anywhere that is not Windows — which made this script impossible to run at
    # all off Windows, including for a syntax check. Found by running it, not by reading it.
    $jbrCandidates = @('C:\Program Files\Android\Android Studio\jbr')
    if ($env:LOCALAPPDATA) { $jbrCandidates += (Join-Path $env:LOCALAPPDATA 'Programs\Android Studio\jbr') }
    $studioJbr = $jbrCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($studioJbr) { $jdk = $studioJbr }
}
$javaExe = if ($jdk) { Join-Path $jdk 'bin\java.exe' } else { $null }
if ($javaExe -and (Test-Path $javaExe)) {
    $env:JAVA_HOME = $jdk
    $pathsToAdd.Add((Join-Path $jdk 'bin'))
    # try/catch because a JAVA_HOME that exists but cannot be executed — a partial install, a wrong
    # architecture, a stub left by an uninstaller — would otherwise throw here under
    # $ErrorActionPreference = 'Stop' and take the whole script with it. A broken JDK is exactly the
    # case somebody runs this to diagnose.
    $version = try { & $javaExe -version 2>&1 | Select-Object -First 1 } catch { $null }
    if ($version) {
        Write-Ok "JAVA_HOME at $jdk"
        Write-Note $version
    } else {
        Write-Bad "JAVA_HOME at $jdk exists but java could not be run."
        Write-Note 'The install looks incomplete. Reinstall the JDK, or point JAVA_HOME elsewhere.'
    }
    # A floor, not a pin. Anything below 17 cannot compile this project's bytecode target.
    if ($version -and $version -match '"(\d+)') {
        $major = [int]$Matches[1]
        if ($major -lt 17) { Write-Bad "Java $major is below the floor of 17." }
    }
} else {
    Write-Bad 'No JDK found. Set JAVA_HOME, or install Android Studio (its bundled JBR is enough).'
}

# ----------------------------------------------------------------- apply
$added = @()
foreach ($p in $pathsToAdd) {
    if (($env:PATH -split ';') -notcontains $p) { $env:PATH = "$p;$env:PATH"; $added += $p }
}

Write-Host ''
if ($added.Count -gt 0) {
    Write-Host "Added to PATH for this session:" -ForegroundColor Cyan
    $added | ForEach-Object { Write-Note $_ }
} else {
    Write-Host 'Everything found was already on the PATH.' -ForegroundColor Cyan
}

if ($WriteLocalProperties -and $sdk) {
    $needs = -not (Test-Path $localProps) -or -not (Select-String -Path $localProps -Pattern '^sdk\.dir=' -Quiet)
    if ($needs) {
        # local.properties wants forward slashes or escaped backslashes; forward slashes are simpler and
        # the Android Gradle plugin accepts them on Windows.
        Add-Content -Path $localProps -Value ("sdk.dir=" + ($sdk -replace '\\', '/'))
        Write-Ok 'wrote sdk.dir to local.properties (it is gitignored, as it should be)'
    } else {
        Write-Ok 'local.properties already points at the SDK'
    }
}

if ($Persist -and $sdk) {
    [Environment]::SetEnvironmentVariable('ANDROID_HOME', $sdk, 'User')
    $userPath = [Environment]::GetEnvironmentVariable('PATH', 'User')
    $userParts = if ($userPath) { $userPath -split ';' } else { @() }
    $new = $added | Where-Object { $userParts -notcontains $_ }
    if ($new) {
        [Environment]::SetEnvironmentVariable('PATH', (($new + $userParts) -join ';'), 'User')
        Write-Ok "persisted ANDROID_HOME and $($new.Count) PATH entr$(if ($new.Count -eq 1) {'y'} else {'ies'}) for your user"
    } else {
        Write-Ok 'persisted ANDROID_HOME; the PATH entries were already there'
    }
    Write-Note 'New terminals will inherit these. This one already has them.'
}

if (-not $dotSourced) {
    Write-Host ''
    Write-Warning @'
This script was run, not dot-sourced, so none of the above survives.

A child process cannot change its parent's environment. Run it again with a leading dot:

    . .\scripts\Set-BookWavePath.ps1
'@
}

Write-Host ''
