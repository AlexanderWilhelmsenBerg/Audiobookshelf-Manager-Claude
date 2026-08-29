# docs/device-test-0.9.14.md section 11 - connected AndroidKeyStore/profile-lock tests.
#Requires -Version 7.0
. (Join-Path $PSScriptRoot '_common.ps1')
Require-Device

Write-Step 'Section 11 - AndroidKeyStore instrumented tests'
Write-Note 'This test APK has its own package and UID, so it is safe beside the installed app.'
Invoke-Gradle ':core:datastore:connectedDebugAndroidTest'
Write-Ok 'Instrumented task completed. Confirm the report shows 27 of 27 tests passed.'
