# docs/device-test-0.9.14.md section 6 - signed release APK.
#Requires -Version 7.0
. (Join-Path $PSScriptRoot '_common.ps1')

Write-Step 'Section 6.2 - Build the release APK'
Write-Note 'The four bookwave.signing.* properties must be present.'
Write-Note 'If you do not have a key yet, first run .\scripts\device-test\06-create-signing-key.ps1.'
$signingConfiguration = Use-BookWaveGradleHome -RequireCompleteSigning
Write-Ok "Complete signing configuration found in $($signingConfiguration.PropertiesFile)"
Write-Note 'Stopping Gradle first makes it reload any signing properties created in this session.'
try { Invoke-Gradle '--stop' } catch { Write-Warn $_.Exception.Message }
Invoke-Gradle ':app:assembleRelease'

$apk = Join-Path $RepoRoot 'app\build\outputs\apk\release\app-release.apk'
$apksigner = Get-NewestBuildTool 'apksigner'

Write-Step 'Section 6.2 - Verify its signature'
if (-not $apksigner -or -not (Test-Path -LiteralPath $apk)) {
    Write-Bad 'APKSIGNER or the release APK is missing.'
    throw 'The release APK cannot be verified.'
}

$signatureOutput = @(& $apksigner verify --print-certs $apk 2>$null)
if ($LASTEXITCODE -ne 0) {
    Write-Bad 'The release APK is unsigned or failed signature verification.'
    throw 'Set the four signing properties and rebuild.'
}
$signer = $signatureOutput | Where-Object { $_ -match '^Signer #1 certificate SHA-256 digest:' } | Select-Object -First 1
if (-not $signer) {
    Write-Bad 'No signer digest was found.'
    throw 'The release APK cannot be treated as signed.'
}
Write-Host "  $signer"
Write-Ok 'The release APK is signed and installable.'
Write-Note 'A v1 result of false is correct; v2 covers this app from API 24 and minSdk is 26.'

Write-Step 'Section 6.3 - Install and launch the release package'
Require-Device
Write-Note "Release uses $PackageRelease, separate from debug package $PackageDebug."
Invoke-Adb install -r $apk
Invoke-Adb shell monkey -p $PackageRelease -c android.intent.category.LAUNCHER 1
Write-Ok 'Release launched.'
Write-Note 'Sign in and test playback, downloads, About, diagnostics, a bookmark, history, and language.'
Write-Note 'Keep app\build\outputs\mapping\release\mapping.txt with any release crash report.'
