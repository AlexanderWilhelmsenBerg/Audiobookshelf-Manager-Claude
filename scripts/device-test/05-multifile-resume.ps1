# docs/device-test-0.9.14.md section 5 - whole-book positions for a multi-file book.
#Requires -Version 7.0
. (Join-Path $PSScriptRoot '_common.ps1')
Require-Device

Write-Step 'Section 5.1 - Multi-file resume after force-stop'
Write-Note 'Play beyond the first file in a multi-file book and record the position to the second.'
Wait-ForTester 'Record the position and leave playback at that point.'
Invoke-Adb shell am force-stop $Package
Write-Ok 'BookWave stopped. Reopen it and compare the restored whole-book position.'

Write-Step 'Section 5.2 - Optional degraded path'
Write-Note 'This needs at least two tracks whose durations the server cannot determine.'
$degraded = @(Show-LogcatMatches -Pattern 'plays its first file only' -Last 5)
if ($degraded.Count -eq 0) {
    Write-Ok 'No degraded-path line was logged, which is expected without a crafted fixture.'
} else {
    $degraded | ForEach-Object { Write-Output $_ }
    Write-Warn 'The degraded path was reached; retain these lines with the fixture description.'
}
