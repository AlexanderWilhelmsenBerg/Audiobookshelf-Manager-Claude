# Shared helpers for the native PowerShell device-test scripts.
# Dot-source this file; do not run it directly.

#Requires -Version 7.0

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $RepoRoot

$PackageDebug = 'org.homebord.bookwave.debug'
$PackageRelease = 'org.homebord.bookwave'
$Package = if ($env:BOOKWAVE_PKG) { $env:BOOKWAVE_PKG } else { $PackageDebug }
$Gradle = Join-Path $RepoRoot 'gradlew.bat'

function Write-Step {
    param([Parameter(Mandatory)][string]$Message)
    Write-Host "`n$Message" -ForegroundColor Cyan
}

function Write-Ok {
    param([Parameter(Mandatory)][string]$Message)
    Write-Host "  [ok]   $Message" -ForegroundColor Green
}

function Write-Warn {
    param([Parameter(Mandatory)][string]$Message)
    Write-Host "  [warn] $Message" -ForegroundColor Yellow
}

function Write-Bad {
    param([Parameter(Mandatory)][string]$Message)
    Write-Host "  [fail] $Message" -ForegroundColor Red
}

function Write-Note {
    param([Parameter(Mandatory)][string]$Message)
    Write-Host "         $Message" -ForegroundColor DarkGray
}

function Show-Command {
    param([Parameter(Mandatory)][string]$Command)
    Write-Host "  PS> $Command" -ForegroundColor White
}

function Convert-LocalPropertiesPath {
    param([Parameter(Mandatory)][string]$Value)

    $slash = [string][char]92
    return $Value.Replace($slash + $slash, $slash).Replace($slash + ':', ':')
}

function Resolve-BookWaveSdk {
    $localProperties = Join-Path $RepoRoot 'local.properties'
    if (Test-Path -LiteralPath $localProperties) {
        $line = Get-Content -LiteralPath $localProperties |
            Where-Object { $_ -match '^sdk\.dir=(.+)$' } |
            Select-Object -Last 1
        if ($line -and $line -match '^sdk\.dir=(.+)$') {
            $candidate = Convert-LocalPropertiesPath $Matches[1]
            if (Test-Path -LiteralPath $candidate) {
                return (Resolve-Path -LiteralPath $candidate).Path
            }
        }
    }

    foreach ($candidate in @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
    )) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    return $null
}

$Sdk = Resolve-BookWaveSdk
$Adb = if ($Sdk -and (Test-Path -LiteralPath (Join-Path $Sdk 'platform-tools\adb.exe'))) {
    Join-Path $Sdk 'platform-tools\adb.exe'
} else {
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { $command.Source } else { $null }
}

function Get-NewestBuildTool {
    param([Parameter(Mandatory)][string]$Name)

    if ($Sdk) {
        $buildToolsRoot = Join-Path $Sdk 'build-tools'
        $versions = Get-ChildItem -LiteralPath $buildToolsRoot -Directory -ErrorAction SilentlyContinue |
            Sort-Object { try { [version]($_.Name -replace '[^0-9.]', '') } catch { [version]'0.0.0' } } -Descending
        foreach ($version in $versions) {
            foreach ($extension in @('.exe', '.bat', '.cmd')) {
                $candidate = Join-Path $version.FullName ($Name + $extension)
                if (Test-Path -LiteralPath $candidate) {
                    return $candidate
                }
            }
        }
    }

    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    return $null
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter()][string[]]$ArgumentList = @(),
        [Parameter()][string]$DisplayCommand
    )

    if (-not $DisplayCommand) {
        $DisplayCommand = (@($FilePath) + $ArgumentList) -join ' '
    }
    Show-Command $DisplayCommand
    & $FilePath @ArgumentList
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code $LASTEXITCODE`: $DisplayCommand"
    }
}

function Get-BookWaveSigningPropertyNames {
    param([Parameter(Mandatory)][string]$PropertiesFile)

    if (-not (Test-Path -LiteralPath $PropertiesFile)) { return @() }
    return @(
        Get-Content -LiteralPath $PropertiesFile |
            ForEach-Object {
                if ($_ -match '^\s*bookwave\.signing\.(storeFile|storePassword|keyAlias|keyPassword)\s*=\s*(.+?)\s*$') {
                    $Matches[1]
                }
            } |
            Sort-Object -Unique
    )
}

function Use-BookWaveGradleHome {
    param([switch]$RequireCompleteSigning)

    $required = @('storeFile', 'storePassword', 'keyAlias', 'keyPassword')
    $userDirectory = [Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)
    $defaultGradleHome = Join-Path $userDirectory '.gradle'
    $currentGradleHome = if ($env:GRADLE_USER_HOME) {
        [System.IO.Path]::GetFullPath($env:GRADLE_USER_HOME)
    } else {
        $defaultGradleHome
    }

    $candidateHomes = @($currentGradleHome, $defaultGradleHome) | Select-Object -Unique
    $currentProperties = Join-Path $currentGradleHome 'gradle.properties'
    $currentNames = @(Get-BookWaveSigningPropertyNames -PropertiesFile $currentProperties)
    $currentMissing = @($required | Where-Object { $_ -notin $currentNames })

    if ($currentNames.Count -gt 0 -and $currentMissing.Count -gt 0) {
        Write-Warn "$currentProperties has an incomplete signing configuration; missing: $($currentMissing -join ', ')."
    }

    foreach ($candidateHome in $candidateHomes) {
        $propertiesFile = Join-Path $candidateHome 'gradle.properties'
        $present = @(Get-BookWaveSigningPropertyNames -PropertiesFile $propertiesFile)
        $missing = @($required | Where-Object { $_ -notin $present })
        if ($missing.Count -eq 0) {
            if (-not $currentGradleHome.Equals($candidateHome, [System.StringComparison]::OrdinalIgnoreCase)) {
                $env:GRADLE_USER_HOME = $candidateHome
                Write-Warn "Using $candidateHome because it contains the complete BookWave signing configuration."
            }
            return [pscustomobject]@{
                Home = $candidateHome
                PropertiesFile = $propertiesFile
            }
        }
    }

    if ($RequireCompleteSigning) {
        throw 'No Gradle home contains all four BookWave signing values. Run .\scripts\device-test\06-create-signing-key.ps1.'
    }
    return $null
}

function Invoke-Gradle {
    $commandArguments = @($args)
    [void](Use-BookWaveGradleHome)
    Invoke-Checked `
        -FilePath $Gradle `
        -ArgumentList $commandArguments `
        -DisplayCommand ('.\gradlew.bat ' + ($commandArguments -join ' '))
}

function Require-Adb {
    if (-not $Adb -or -not (Test-Path -LiteralPath $Adb)) {
        Write-Bad 'ADB was not found. Run . .\scripts\Set-BookWavePath.ps1 first.'
        throw 'ADB is required for this section.'
    }
}

function Require-Device {
    Require-Adb
    $deviceLines = @(& $Adb devices 2>$null | Where-Object { $_ -match "`tdevice$" })
    if ($deviceLines.Count -eq 0) {
        Write-Bad 'No attached and authorised device was found.'
        Write-Note "Connect and unlock the phone, then accept Android's USB-debugging prompt."
        throw 'An authorised Android device is required.'
    }
    Write-Ok "$($deviceLines.Count) device(s) attached"
}

function Invoke-Adb {
    $commandArguments = @($args)
    Require-Adb
    Invoke-Checked `
        -FilePath $Adb `
        -ArgumentList $commandArguments `
        -DisplayCommand ('adb ' + ($commandArguments -join ' '))
}

function Get-AdbOutput {
    $commandArguments = @($args)
    Require-Adb
    $output = @(& $Adb @commandArguments 2>$null)
    if ($LASTEXITCODE -ne 0) {
        throw "ADB failed with exit code $LASTEXITCODE`: adb $($commandArguments -join ' ')"
    }
    return $output
}

function Show-LogcatMatches {
    param(
        [Parameter(Mandatory)][string[]]$Pattern,
        [int]$Last = 20
    )

    $shownPattern = $Pattern -join "' OR '"
    Show-Command "adb logcat -d -t 5000 | Select-String -SimpleMatch '$shownPattern' | Select-Object -Last $Last"
    $lines = Get-AdbOutput logcat -d -t 5000
    @($lines | Select-String -SimpleMatch -Pattern $Pattern | Select-Object -Last $Last) |
        ForEach-Object { $_.Line }
}

function Show-AppLog {
    param([int]$Last = 200)

    Require-Adb
    Write-Step "BookWave's log lines from logcat"
    Write-Note 'The in-app event log is redacted. Logcat is not; inspect it locally and do not paste it publicly.'
    $lines = Get-AdbOutput logcat -d -v time
    @($lines | Select-String -Pattern 'BookWave|shelfplayer|homebord' | Select-Object -Last $Last) |
        ForEach-Object { $_.Line }
}

function Wait-ForTester {
    param([Parameter(Mandatory)][string]$Message)
    [void](Read-Host "  $Message Press Enter to continue")
}

function Show-LocalEnvironment {
    Write-Step 'PowerShell and Java'
    Write-Ok "PowerShell $($PSVersionTable.PSVersion) ($($PSVersionTable.PSEdition))"
    $java = Get-Command java -ErrorAction SilentlyContinue
    if ($java) {
        & $java.Source -version
    } else {
        Write-Bad 'Java is not on PATH. Set JAVA_HOME before continuing.'
    }

    Write-Step 'Android SDK'
    if ($Sdk) {
        Write-Ok "SDK at $Sdk"
    } else {
        Write-Bad 'No Android SDK was found.'
    }
    foreach ($relative in @('platforms\android-36', 'build-tools\36.0.0', 'platform-tools\adb.exe')) {
        $candidate = if ($Sdk) { Join-Path $Sdk $relative } else { $null }
        if ($candidate -and (Test-Path -LiteralPath $candidate)) {
            Write-Ok $relative
        } else {
            Write-Bad "Missing $relative"
        }
    }

    Write-Step 'Optional tools'
    foreach ($tool in @('jq', 'docker')) {
        if (Get-Command $tool -ErrorAction SilentlyContinue) {
            Write-Ok $tool
        } else {
            Write-Warn "$tool is unavailable; only the tests that explicitly use it are affected."
        }
    }
}
