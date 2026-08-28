# docs/device-test-0.9.14.md sections 9 and 10 - Recents privacy and profile passcode.
#Requires -Version 7.0
. (Join-Path $PSScriptRoot '_common.ps1')
Require-Device

Write-Step 'Section 9 - Android version and Recents privacy'
$release = (Get-AdbOutput shell getprop ro.build.version.release | Select-Object -First 1)
$sdkLevel = (Get-AdbOutput shell getprop ro.build.version.sdk | Select-Object -First 1)
Write-Host "  Android $release; API $sdkLevel"
Write-Note 'API 33+ must show no readable library information in the app switcher.'
Write-Note 'API 26-32 retains the documented R-62 residual.'
Write-Note 'Take a normal screenshot too; it must remain possible.'
Wait-ForTester 'Complete the Recents-card and ordinary-screenshot checks.'

Write-Step 'Section 10 - Passcode through reauthentication'
Write-Note '1. Set a 6-12 digit code in Settings -> Passcode lock.'
Wait-ForTester 'Set the passcode.'
Invoke-Adb shell am force-stop $Package
Write-Note 'Reopen BookWave and confirm the lock curtain appears.'
Write-Note "2. In the web client, change this user's password."
Write-Note "3. If the app requests sign-in, reauthenticate, then force-stop and reopen."
Write-Note 'Expected: the passcode curtain remains after reauthentication (R-44).'
Write-Note "4. Use 'Forgotten your passcode?' -> 'Sign in and clear the passcode'."
Write-Note 'Expected: the next reopen goes directly into the profile.'
