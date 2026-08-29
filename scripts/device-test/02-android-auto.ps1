# docs/device-test-0.9.14.md section 2 - Android Auto cold browse and transport.
#Requires -Version 7.0
. (Join-Path $PSScriptRoot '_common.ps1')
Require-Device

Write-Step 'Section 2 step 1 - Cold start'
Invoke-Adb shell am force-stop $Package
Write-Ok 'BookWave stopped.'

Write-Step 'Section 2 step 2 - Dedicated DHU window'
$pwsh = Join-Path $PSHOME 'pwsh.exe'
if (-not (Test-Path -LiteralPath $pwsh)) {
    $pwshCommand = Get-Command pwsh -ErrorAction SilentlyContinue
    if (-not $pwshCommand) { throw 'PowerShell 7 executable could not be resolved.' }
    $pwsh = $pwshCommand.Source
}
$dhuScript = Join-Path $PSScriptRoot '02-android-auto-dhu.ps1'
$arguments = @('-NoLogo', '-NoExit', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $dhuScript + '"'))
Show-Command "Start-Process -FilePath '$pwsh' -ArgumentList '-NoLogo -NoExit -ExecutionPolicy Bypass -File `"$dhuScript`"'"
Start-Process -FilePath $pwsh -ArgumentList $arguments
Write-Ok 'Opened a fully initialized DHU PowerShell window.'
Write-Note "If the new window cannot connect, confirm 'Start head unit server' on the phone."

Wait-ForTester 'In DHU, open BookWave, browse into a library, and select a book. Record whether the book opens or remains on the loading message.'

Write-Step 'Section 2 step 4 - Browse responses'
$children = @(Show-LogcatMatches -Pattern "asked for a node's children" -Last 20)
if ($children.Count -eq 0) {
    Write-Warn 'No children= lines reached logcat. Check the in-app event log before deciding the result.'
    Write-Note "Settings -> About -> Diagnostics -> Event log; search for: node's children"
} else {
    $children | ForEach-Object { Write-Output $_ }
    Write-Ok "Found $($children.Count) answered browse request(s). Confirm children is nonzero."
}

Write-Step 'Section 2 step 5 - Browse failures'
$failures = @(Show-LogcatMatches -Pattern 'A browse request failed' -Last 10)
if ($failures.Count -eq 0) {
    Write-Ok 'No failed browse requests reached logcat. Confirm the in-app event log is also clear.'
} else {
    $failures | ForEach-Object { Write-Output $_ }
    Write-Bad 'A browse request failed. Preserve each complete line, including thrown=.'
}

Wait-ForTester 'In DHU, start playback, press +30 seconds, then press -30 seconds.'

Write-Step 'Section 2 step 7 - Server position updates'
$positions = @(Show-LogcatMatches -Pattern 'The server accepted a position' -Last 6)
if ($positions.Count -eq 0) {
    Write-Warn 'No accepted server-position lines reached logcat. Search for them in the in-app event log.'
} else {
    $positions | ForEach-Object { Write-Output $_ }
    Write-Note 'Confirm the forward delta is +30005 ms and the backward delta is -30000 ms.'
}
