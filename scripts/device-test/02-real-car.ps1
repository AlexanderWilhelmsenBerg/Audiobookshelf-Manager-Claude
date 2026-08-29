# docs/device-test-0.9.14.md section 2.9 - a real car, not the Desktop Head Unit.
#
# Every car result this project holds came from the DHU. The DHU is the same projection stack driving a
# window on a computer, so it covers the browse tree and covers none of the car's own contribution: the
# launcher, the steering wheel, driving restrictions, ignition cycles, an unplugged cable, the microphone.
# Each of those is a way this app can fail for a listener while every recorded test stays green.
#
# Do all of it parked. Step 5 needs the car moving and needs somebody else driving; skipping it is a result.
#Requires -Version 7.0
. (Join-Path $PSScriptRoot '_common.ps1')
Require-Device

Write-Step 'Section 2.9 step 1 - Which host actually answered'
Write-Note 'Plug the phone into the car, or start wireless Android Auto, then read this line.'
$connections = @(Show-LogcatMatches -Pattern 'A car connected to the media session' -Last 5)
if ($connections.Count -eq 0) {
    Write-Warn 'No car connection recorded. Connect first, or read the in-app event log.'
} else {
    $connections | ForEach-Object { Write-Output $_ }
    Write-Ok 'Expect controller=com.google.android.projection.gearhead - projected Android Auto.'
    Write-Note 'The DHU reports the SAME package, so this cannot tell them apart: record which you used.'
    Write-Warn 'Anything else is Automotive OS or a vendor host. This app has never seen one. Report it.'
}

Write-Step 'Section 2.9 step 2 - The car''s own launcher'
Write-Note 'Find BookWave in the car''s app list. The DHU has its own launcher and proves nothing here.'
Write-Note 'Missing here but present in the DHU is a DISCOVERY defect - read Settings > About > This device.'

Write-Step 'Section 2.9 step 3 - Browse and select, in the car'
Write-Note "Repeat section 2's counts and section 2.8's tap here. A difference between car and DHU IS the"
Write-Note 'finding, so record both. & .\scripts\device-test\02-car-selection.ps1 captures the tap.'

Write-Step 'Section 2.9 step 4 - Steering-wheel and hard buttons'
Write-Note 'Next, previous, play/pause from the wheel, and the volume knob. These arrive as media-button'
Write-Note 'events and never touch the DHU, so nothing recorded so far says whether they work.'
# Clear first, or step 3's own buffering/ready lines are still in the buffer and a wheel that does
# nothing reads as a wheel that worked - a false pass on the exact hardware path this step exists for.
Clear-Logcat
Wait-ForTester 'Try the wheel controls now.'
$states = @(Show-LogcatMatches -Pattern 'The player changed state' -Last 10)
if ($states.Count -eq 0) {
    Write-Bad 'No player state change after the wheel. Either the buttons are unsupported here, or they'
    Write-Bad 'are not reaching the session - PR #48 narrowed that command surface, so say which.'
} else {
    $states | ForEach-Object { Write-Output $_ }
    Write-Ok "The wheel reached the session ($($states.Count) state changes)."
}

Write-Step 'Section 2.9 step 5 - Driving restrictions'
Write-Warn 'Only with somebody else driving, or on a rolling road. Skip it otherwise and say so.'
Write-Note 'The car truncates long lists and hides text while moving - that is the host, not a defect. A'
Write-Note 'list that becomes unusable, or a row whose label is meaningless once truncated, is.'

Write-Step 'Section 2.9 step 6 - Ignition off, ignition on'
Write-Note 'Stop the engine, let the head unit power down, restart it. Expect BookWave back, and the resume'
Write-Note 'tile offering your book at the position you left. Closing a DHU window is not a power cycle.'

Write-Step 'Section 2.9 step 7 - Unplug while playing'
Write-Note 'Pull the cable mid-book. Playback must continue on the phone and progress must not be lost -'
Write-Note 'product priorities 1 and 2 in one step. Then plug back in and confirm the car picks it up.'
Clear-Logcat
Wait-ForTester 'Unplug, then replug.'
$asked = @(Show-LogcatMatches -Pattern 'A controller asked to set what plays' -Last 10)
$asked | ForEach-Object { Write-Output $_ }
$positions = @(Show-LogcatMatches -Pattern 'The server accepted a position' -Last 6)
if ($positions.Count -eq 0) {
    Write-Warn 'No accepted server position after the reconnect. Check the in-app event log.'
} else {
    $positions | ForEach-Object { Write-Output $_ }
    Write-Ok 'Progress reached the server across the disconnect.'
}

Write-Step 'Section 2.9 step 8 - Voice, if the car has it'
Write-Note 'Say: Hey Google, play <a book you own>. That is onSetMediaItems with a search query rather than'
Write-Note 'a media id - a different branch from a tap, and one only a real microphone reaches.'
Clear-Logcat
Wait-ForTester 'Try the voice request now.'
$spoken = @(Show-LogcatMatches -Pattern 'A controller asked to set what plays' -Last 5)
if ($spoken.Count -eq 0) {
    Write-Bad 'The spoken request never reached this service.'
} else {
    $spoken | ForEach-Object { Write-Output $_ }
    Write-Note 'Expect branch=spoken and kind=none: a spoken request arrives with a query and no media id.'
    Write-Note 'branch=browse would mean the voice host resolved the title itself and sent an id instead.'
}
