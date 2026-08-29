# docs/device-test-0.9.14.md section 12 - About, event log, and language persistence.
#Requires -Version 7.0
. (Join-Path $PSScriptRoot '_common.ps1')
Require-Device

Write-Step 'Section 12.1 - About tab'
Write-Note "Settings -> About must not contain 'Checks after wave 3'."
Write-Note "The former 'Testing' heading must read 'This device'."
Write-Note 'Switch to Norsk bokmal and confirm both changes are localized.'
Wait-ForTester 'Complete the About checks.'

Write-Step 'Section 12.2 - Event log'
Write-Note 'Play something first, then open Settings -> About -> Diagnostics -> Event log.'
Write-Note 'Search message and area; filter Level and Area; combine all three with AND behavior.'
Write-Note "Expect 'Showing X of N' and the filtered empty state 'No events match...'."
Write-Note 'Area chips must retain first-seen order while new playback lines arrive.'
Write-Note 'Copy output must match the active filters and expose no private media/server data.'
Wait-ForTester 'Complete the event-log checks.'

Write-Step 'Section 12.3 - Norwegian persistence after two cold launches'
Write-Warn 'Set Settings -> Appearance -> Language -> Norsk bokmal before continuing.'
Wait-ForTester 'Confirm Norwegian is selected.'
for ($cycle = 1; $cycle -le 2; $cycle++) {
    Write-Host "  cycle $cycle"
    Invoke-Adb shell am force-stop $Package
    Start-Sleep -Seconds 1
    & $Adb shell monkey -p $Package -c android.intent.category.LAUNCHER 1 *> $null
    if ($LASTEXITCODE -ne 0) { throw "Could not launch $Package in cycle $cycle." }
    Start-Sleep -Seconds 4
    $pidOutput = @(& $Adb shell pidof $Package 2>$null)
    if ($pidOutput.Count -gt 0 -and ($pidOutput -join '').Trim()) {
        Write-Ok "BookWave is running after cycle $cycle."
    } else {
        Write-Bad "BookWave is not running after cycle $cycle; it may have crashed on launch."
    }
}

Write-Step 'Section 12.3 - Crash signatures'
$crashes = @(Show-LogcatMatches -Pattern @('HiltViewModelFactory', 'FATAL EXCEPTION', 'AndroidRuntime') -Last 20)
if ($crashes.Count -eq 0) {
    Write-Ok 'No matching crash signature was found.'
} else {
    $crashes | ForEach-Object { Write-Output $_ }
    Write-Bad 'Review these crash lines. The old activity-context exception is R-67.'
}
Write-Note "Repeat after selecting English, then repeat after selecting 'Follow the system'."
