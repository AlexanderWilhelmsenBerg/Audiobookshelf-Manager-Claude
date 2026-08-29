# Dedicated Desktop Head Unit window for docs/device-test-0.9.14.md section 2.
#Requires -Version 7.0
. (Join-Path $PSScriptRoot '_common.ps1')
Require-Device

if (-not $Sdk) {
    throw 'The Android SDK could not be resolved. Run . .\scripts\Set-BookWavePath.ps1 first.'
}

$dhu = Join-Path $Sdk 'extras\google\auto\desktop-head-unit.exe'
if (-not (Test-Path -LiteralPath $dhu)) {
    throw "DHU is not installed at $dhu. Install Android Auto Desktop Head Unit from SDK Manager."
}

Write-Step 'Android Auto Desktop Head Unit'
Write-Note "On the phone, enable Android Auto developer mode and choose 'Start head unit server'."
Write-Note 'Keep the phone unlocked and connected over USB.'
Invoke-Adb forward tcp:5277 tcp:5277
Write-Ok 'Forwarded desktop port 5277 to the phone.'
Show-Command "& '$dhu'"
& $dhu
if ($LASTEXITCODE -ne 0) {
    throw "DHU exited with code $LASTEXITCODE."
}
