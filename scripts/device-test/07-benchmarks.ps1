# docs/device-test-0.9.14.md section 7 - benchmarks and baseline profile.
#Requires -Version 7.0
. (Join-Path $PSScriptRoot '_common.ps1')
Require-Device

Write-Step 'Section 7 - Preparation'
Write-Warn 'Unlock and plug in the phone, then leave it untouched during measurement.'
Write-Note 'The benchmark seeds about 2,000 books and may take roughly 20 minutes.'
Wait-ForTester 'Confirm the phone is ready and notifications will not interrupt it.'

Write-Step 'Section 7 - Run benchmarks'
Invoke-Gradle ':benchmark:connectedBenchmarkAndroidTest'

Write-Step 'Section 7 - Results'
Write-Note 'Console output and benchmark\build\outputs\connected_android_test_additional_output\ contain results.'
Write-Note 'Fill in docs\benchmark.md with device, Android version, date, and all four measurements.'
Write-Note 'Review baseline-prof.txt before committing it.'
Write-Warn 'The fixture has no covers, so its scroll result is an optimistic floor.'
