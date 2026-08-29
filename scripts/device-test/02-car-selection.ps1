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
# No verdict here, and deliberately. Nothing has been cleared yet - this script is run with the car
# already connected - so the newest connection line may be from a DHU session hours ago, and a pass
# would report a host that is not the one in front of you. The log cannot settle it even when fresh:
# the DHU and a real car both report controller=com.google.android.projection.gearhead (R-75). Only
# the tester can say which host this is, which is what the step asks them to record.
$connections = @(Show-LogcatMatches -Pattern 'A car connected to the media session' -Last 5)
$connections | ForEach-Object { Write-Output $_ }
if ($connections.Count -eq 0) {
    Write-Note 'No connection line in the buffer. It may simply have rolled - connect, then carry on.'
}
else {
    Write-Note 'Context only: the buffer was not cleared, so the newest line above may predate today.'
    Write-Note 'gearhead is projected Android Auto, DHU and real car alike - you record which (2.9).'
}

Write-Step 'Section 2.8 step 2 - Clear the log, so what follows is only the tap'
Clear-Logcat

Wait-ForTester 'In the car, open a shelf, tap a book, and wait up to 30 seconds. Record whether it ever loads.'

Write-Step 'Section 2.8 step 3 - What the service was asked, and what it answered'
$asked = @(Show-LogcatMatches -Pattern 'A controller asked to set what plays' -Last 10)
$script:SelectionResolved = @($asked | Where-Object { $_ -match 'resolved=true' }).Count
if ($asked.Count -eq 0) {
    # Only a diagnosis when the log can support one. If logcat is not carrying the app, absence says
    # nothing about where the defect is, and Show-LogcatMatches has already said so - a layer
    # attribution on top of that would contradict it in the same breath.
    if ($script:LogcatCarriesApp -eq 'yes') {
        Write-Bad 'No selection reached this service. The defect is upstream of it - discovery, or the item flags.'
    }
    else {
        Write-Bad 'No selection line found, and logcat is not carrying this app - so this locates nothing.'
        Write-Note 'Read Settings > About > Diagnostics > the event log and search "asked to set" first.'
    }
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
$states | ForEach-Object { Write-Output $_ }
# This step can only speak for the tap if the tap resolved. A book already streaming produces
# buffering/ready from an ordinary rebuffer inside the same 30-second window, so a pass here without a
# resolved=true selection would claim the queue reached the player while the step above says the
# opposite. Requiring the earlier fact is simpler than correlating timestamps, and true.
# This step deliberately issues no pass - see the shell script for the reasoning. A buffering/ready
# line in this window is produced by an ordinary rebuffer as well as by the tap, requiring a
# resolved=true line somewhere does not separate them because the rebuffer can precede the tap, and
# correlating timestamps in a shell is what produced the last two defects here. Report, and let the
# reader compare two timestamps.
if ($script:SelectionResolved -gt 0) {
    Write-Note 'A selection resolved. Now compare TIMESTAMPS by eye: a buffering/ready line AFTER the'
    Write-Note "'asked to set ... resolved=true' line is your tap reaching the player. One before is not."
    Write-Note "Only 'idle' after that line means the player took the queue and refused to prepare."
    Write-Note 'No line after it at all means the queue never reached the player - the likeliest shape.'
}
else {
    Write-Warn 'No resolved=true selection above, so nothing here can be attributed to your tap: any'
    Write-Warn 'buffering/ready you see may be an ordinary rebuffer of a book that was already playing.'
    Write-Note 'Read the lines above for context only. The verdict that matters is resolved= before this.'
}

Write-Step 'Section 2.8 step 5 - Anything that threw'
# Absence is not a green, and the shell script has said so since it was written - this one had not
# caught up. `Show-LogcatMatches` may have just reported that the dump is not evidence (logcat not
# carrying the app, or a clear the device refused), and an empty collection then rules out nothing at
# all. Report what is there; the in-app log settles it when the dump cannot.
$failures = @(Show-LogcatMatches -Pattern 'A browse request failed' -Last 10)
if ($failures.Count -eq 0) {
    if ($script:LogcatCarriesApp -eq 'yes' -and $script:LogcatIsolated -eq 'yes') {
        Write-Ok 'Nothing threw. This line covers every session callback, so that rules out an exception.'
    }
    else {
        Write-Note 'No throw was logged - but this dump is not usable evidence (see the warning above),'
        Write-Note 'so it does not rule one out. Search the in-app event log for "browse request failed".'
    }
} else {
    $failures | ForEach-Object { Write-Output $_ }
    Write-Bad 'A callback threw. Preserve the complete line, including thrown=.'
}

Write-Step 'Section 2.8 step 6 - Could the book be opened at all'
$openFailures = @(Show-LogcatMatches -Pattern 'Could not open a session for a browse or resume request' -Last 5)
if ($openFailures.Count -eq 0) {
    # Absence proves only absence. This line is also missing when no selection reached the service at
    # all, and when a browse id failed to parse before openSession was ever attempted - so reading it as
    # "the server opened the book" would contradict step 3's own verdict and send the next look to the
    # player. Only resolved=true in step 3 can say the request became a book.
    Write-Note 'No session-open failure was logged. That is not proof the server opened anything:'
    Write-Note 'this line is also absent when nothing reached the service, or when the id never parsed.'
    Write-Note "Step 3's resolved= is the field that says whether the request became a book."
} else {
    $openFailures | ForEach-Object { Write-Output $_ }
    Write-Bad 'The server refused to open the session, so resolution failed for that reason.'
}

Write-Step 'Section 2.8 step 7 - And the same lines from the in-app log'
Write-Note 'If logcat carried nothing above, this is not optional - it is the only record (R-70):'
Write-Note '  Settings > About > Diagnostics > Open the event log > search "asked to set"'
Write-Note 'Then search "player changed state". Copy both into the report.'
