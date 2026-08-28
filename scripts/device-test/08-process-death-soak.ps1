# docs/device-test-0.9.14.md section 8 - process death and two-hour playback soak.
#Requires -Version 7.0
. (Join-Path $PSScriptRoot '_common.ps1')
Require-Device

Write-Step 'Section 8.1 - Kill the background process without force-stop'
Write-Note 'Play a multi-file book, record its position to the second, then press Home.'
Write-Note 'am kill is a no-op for a foreground process; pressing Home is required.'
Wait-ForTester 'Confirm BookWave is in the background.'
Invoke-Adb shell am kill $Package
$pidOutput = @(& $Adb shell pidof $Package 2>$null)
if ($pidOutput.Count -gt 0 -and ($pidOutput -join '').Trim()) {
    Write-Bad 'The process is still running. It was probably foregrounded; press Home and retry.'
} else {
    Write-Ok 'The process is gone. Reopen BookWave and compare progress with the recorded position.'
    Write-Note 'The restored position must be within five seconds and agree with the web client.'
}

Write-Step 'Section 8.2 - Two-hour soak'
Write-Note 'Start a long book, plug in the phone, and check at 30, 60, 90, and 120 minutes:'
Write-Note '- Playback and notification clock are still advancing.'
Write-Note '- Phone and web client positions agree.'
Write-Note '- About -> This device shows no abnormal rebuffer growth.'
Write-Note '- The event log contains no error repeated hundreds of times.'
