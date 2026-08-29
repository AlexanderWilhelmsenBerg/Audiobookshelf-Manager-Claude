# docs/device-test-0.9.14.md section 0 - build, verify, install, and identify the phone build.
#Requires -Version 7.0
. (Join-Path $PSScriptRoot '_common.ps1')

Write-Step 'Section 0.1 - Environment'
Show-LocalEnvironment

Write-Step 'Section 0.1 - Verification gate'
Write-Note '--rerun-tasks is required after a classpath change (R-31).'
try { Invoke-Gradle '--stop' } catch { Write-Warn $_.Exception.Message }
Invoke-Gradle ktlintFormat
Invoke-Gradle ktlintCheck
Invoke-Gradle verifyDebug '-Pshelfplayer.warningsAsErrors=true' --no-build-cache --rerun-tasks

Write-Step 'Section 0.1 - Install'
Require-Device
Invoke-Gradle ':app:installDebug'

$apk = Join-Path $RepoRoot 'app\build\outputs\apk\debug\app-debug.apk'
$aapt = Get-NewestBuildTool 'aapt2'
Write-Step 'Section 0.1 - Build installed on the phone'
if ($aapt -and (Test-Path -LiteralPath $apk)) {
    $badging = @(& $aapt dump badging $apk)
    $packageLine = $badging | Where-Object { $_ -match '^package:' } | Select-Object -First 1
    if ($packageLine -match "name='([^']+)'.*versionCode='([^']+)'.*versionName='([^']+)'") {
        Write-Host "  package=$($Matches[1])  code=$($Matches[2])  name=$($Matches[3])"
    }
    Write-Note 'Settings -> About -> Version must match this output (R-04).'
} else {
    Write-Warn 'AAPT2 or the debug APK is missing; read the version in Settings -> About.'
}

Write-Step 'Section 0.2 - APK signature'
$apksigner = Get-NewestBuildTool 'apksigner'
if ($apksigner -and (Test-Path -LiteralPath $apk)) {
    $certificate = @(& $apksigner verify --print-certs $apk 2>$null) |
        Where-Object { $_ -match 'certificate SHA-256 digest:' } |
        Select-Object -First 1
    if ($certificate) { Write-Host "  $certificate" }
    Write-Note 'Record this digest. A changed signer requires uninstalling and loses local app state (R-68).'
    Write-Note 'See docs/release.md, Signing, for stable signing configuration.'
}
