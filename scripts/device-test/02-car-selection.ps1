# docs/device-test-0.9.14.md section 2.8 - tapping a book in the car.
#
# The 2026-08-28 run found a populated browse tree whose books would not open: the head unit stayed on
# "Henter valget ditt" forever. It could not be diagnosed, because a car tap that resolved logged nothing at
# all - the tree said children=6 and the next line was minutes later. Two candidate explanations were
# refuted by evidence that already existed, which left none for what did happen (R-71).
#
# This script captures the one window that was missing. Clear, tap, dump.
#Requires -Version 7.0
. (Join-Path $PSScriptRoot '_common.ps1')
Require-Device

Write-Step 'Section 2.8 step 1 - Which host is this?'
Write-Note 'Record whether you are on the Desktop Head Unit or a real car, and over USB or wireless.'
Write-Note 'They are different hosts and a defect can be in one and not the other (section 2.9).'
$connections = @(Show-LogcatMatches -Pattern 'A car connected to the media session' -Last 5)
if ($connections.Count -eq 0) {
    Write-Warn 'No car connection recorded yet. Connect first, or read the in-app event log.'
} else {
    $connections | ForEach-Object { Write-Output $_ }
    Write-Ok 'controller= names the host. gearhead is projected Android Auto, DHU or real car alike.'
}

Write-Step 'Section 2.8 step 2 - Clear the log, so what follows is only the tap'
Clear-Logcat

Wait-ForTester 'In the car, open a shelf, tap a book, and wait up to 30 seconds. Record whether it ever loads.'

Write-Step 'Section 2.8 step 3 - What the service was asked, and what it answered'
$asked = @(Show-LogcatMatches -Pattern 'A controller asked to set what plays' -Last 10)
if ($asked.Count -eq 0) {
    Write-Bad 'No selection reached this service. The defect is upstream of it - discovery, or the item flags.'
    Write-Note 'Confirm against the in-app event log before concluding: search for "asked to set".'
} else {
    $asked | ForEach-Object { Write-Output $_ }
    Write-Note 'branch=   which route answered: browse (a car tap), spoken (voice), passthrough (the app).'
    Write-Note 'kind=     the SHAPE of the id (book / at / tab / root), never the id: an id names a book.'
    Write-Note 'resolved= whether the request turned into a book. THIS is the field to read.'
    Write-Warn 'resolved=false means resolution failed, whatever handedBack says: when nothing resolves and'
    Write-Warn 'a book is already playing the service hands that book back to keep it alive, so handedBack=1'
    Write-Warn 'does NOT mean the tap worked. resolved=true with no player state change is the player half.'
}

Write-Step 'Section 2.8 step 4 - What the player then did with it'
$states = @(Show-LogcatMatches -Pattern 'The player changed state' -Last 15)
if ($states.Count -eq 0) {
    Write-Bad 'The player never changed state. A queue was answered and never reached it, or never prepared.'
} else {
    $states | ForEach-Object { Write-Output $_ }
    Write-Ok 'Expect buffering then ready. Only idle means the player took it and refused to prepare.'
}

Write-Step 'Section 2.8 step 5 - Anything that threw'
$failures = @(Show-LogcatMatches -Pattern 'A browse request failed' -Last 10)
if ($failures.Count -eq 0) {
    Write-Ok 'Nothing threw. This line covers every session callback, so that rules out an exception.'
} else {
    $failures | ForEach-Object { Write-Output $_ }
    Write-Bad 'A callback threw. Preserve the complete line, including thrown=.'
}

Write-Step 'Section 2.8 step 6 - Could the book be opened at all'
$openFailures = @(Show-LogcatMatches -Pattern 'Could not open a session for a browse or resume request' -Last 5)
if ($openFailures.Count -eq 0) {
    Write-Ok 'No session-open failure. The server did open the book.'
} else {
    $openFailures | ForEach-Object { Write-Output $_ }
    Write-Bad 'The server refused to open the session, so resolution failed for that reason.'
}

Write-Step 'Section 2.8 step 7 - And the same lines from the in-app log'
Write-Note 'If logcat carried nothing above, this is not optional - it is the only record (R-70):'
Write-Note '  Settings > About > Diagnostics > Open the event log > search "asked to set"'
Write-Note 'Then search "player changed state". Copy both into the report.'
