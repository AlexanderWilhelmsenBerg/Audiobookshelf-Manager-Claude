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
Write-Note 'Plug the phone into the car, or start wireless Android Auto.'
# Clear BEFORE the connection, or an older DHU session left in the buffer answers for the car in front
# of you: the verdict passes on any 'gearhead' line, so a newly connected Automotive OS or vendor host
# would be reported as projected Android Auto on the strength of a stale one. This step exists to name
# the CURRENT host, so the window has to start empty.
Clear-Logcat
Wait-ForTester 'Once the car has connected and BookWave is on its screen.'
$connections = @(Show-LogcatMatches -Pattern 'A car connected to the media session' -Last 5)
$connections | ForEach-Object { Write-Output $_ }
Test-StepVerdict -Lines $connections -Expected 'gearhead' `
    -PassMessage 'A projected Android Auto host connected.' `
    -FailMessage 'No projected Android Auto connection recorded. Connect first, or read the in-app event log.'
Write-Note 'The DHU reports the SAME package, so this cannot tell them apart: record which you used.'
Write-Warn 'A connection line naming anything but gearhead is Automotive OS or a vendor host. This app'
Write-Warn 'has never seen one - report it rather than reading it as a normal pass.'

Write-Step 'Section 2.9 step 2 - The car''s own launcher'
Write-Note 'Find BookWave in the car''s app list. The DHU has its own launcher and proves nothing here.'
Write-Note 'Missing here but present in the DHU is a DISCOVERY defect - read Settings > About > This device.'
Wait-ForTester "Once you have looked for BookWave in the car's app list."

Write-Step 'Section 2.9 step 3 - Browse and select, in the car'
Write-Note "Repeat section 2's counts and section 2.8's tap here. A difference between car and DHU IS the"
Write-Note 'finding, so record both. & .\scripts\device-test\02-car-selection.ps1 captures the tap.'
Wait-ForTester 'Once you have browsed and tried to open a book in the car.'

Write-Step 'Section 2.9 step 4 - Steering-wheel and hard buttons'
Write-Note 'Next, previous, play/pause from the wheel, and the volume knob. These arrive as media-button'
Write-Note 'events and never touch the DHU, so nothing recorded so far says whether they work.'
Write-Warn 'The log can witness PLAY/PAUSE only. Volume changes no playback state, and next/previous are'
Write-Warn 'no-ops on the one-item queue a car selection builds - judge those two by ear, not from here.'
# Clear first, or step 3's own lines are still in the buffer and a wheel that does nothing reads as a
# wheel that worked. And look for the play/pause line rather than a state change: play/pause leaves the
# player in STATE_READY, so a working wheel produces no state change at all.
Clear-Logcat
Wait-ForTester "Use the wheel's PLAY/PAUSE button now."
$asked = @(Show-LogcatMatches -Pattern 'Playback was asked to change' -Last 10)
$asked | ForEach-Object { Write-Output $_ }
# reason=remote is the pass, not the mere presence of a line: userRequest is the phone's own UI and
# audioFocusLoss is nobody at all, and either would otherwise be counted as the wheel working.
Test-StepVerdict -Lines $asked -Expected 'reason=remote' `
    -PassMessage 'Play/pause reached the session from a remote controller - the wheel.' `
    -FailMessage "The wheel's play/pause did not reach the session as a remote request. Unsupported here, or refused - PR #48 narrowed that command surface, so say which if you can tell."
Write-Note 'reason=userRequest would mean the phone UI did it; reason=audioFocusLoss, nobody did.'

Write-Step 'Section 2.9 step 5 - Driving restrictions'
Write-Warn 'Only with somebody else driving, or on a rolling road. Skip it otherwise and say so.'
Write-Note 'The car truncates long lists and hides text while moving - that is the host, not a defect. A'
Write-Note 'list that becomes unusable, or a row whose label is meaningless once truncated, is.'
Wait-ForTester 'Once you have done this, or decided to skip it.'

Write-Step 'Section 2.9 step 6 - Ignition off, ignition on'
Write-Note 'Stop the engine, let the head unit power down, restart it. Expect BookWave back, and the resume'
Write-Note 'tile offering your book at the position you left. Closing a DHU window is not a power cycle.'
Clear-Logcat
Wait-ForTester 'Once the head unit has powered down and come back up.'
$back = @(Show-LogcatMatches -Pattern 'A car connected to the media session' -Last 5)
$back | ForEach-Object { Write-Output $_ }
Test-StepVerdict -Lines $back -Expected 'controller=' `
    -PassMessage 'The car reconnected after the power cycle. Now judge the resume tile by eye.' `
    -FailMessage 'No fresh connection line: the car did not reconnect after the power cycle. That is a different finding from a resume tile that is missing or wrong.'

Write-Step 'Section 2.9 step 7 - Unplug while playing'
Write-Note 'Pull the cable mid-book. Playback must continue on the phone and progress must not be lost -'
Write-Note 'product priorities 1 and 2 in one step. Then plug back in and confirm the car picks it up.'
Clear-Logcat
Wait-ForTester 'Unplug, then replug.'
$asked = @(Show-LogcatMatches -Pattern 'A controller asked to set what plays' -Last 10)
$asked | ForEach-Object { Write-Output $_ }
$positions = @(Show-LogcatMatches -Pattern 'The server accepted a position' -Last 6)
$positions | ForEach-Object { Write-Output $_ }
# No pass here either: the remote sync ticker writes this line about every thirty seconds while a book
# plays, so one landing while the tester reads the prompt would pass a test proving nothing about the
# disconnect. The sound version is a position compared either side of the unplug, by hand.
Write-Note 'This line proves a sync happened, NOT that progress survived the unplug: the ticker writes'
Write-Note 'one about every 30 s while a book plays, so one may have landed before you pulled the cable.'
Write-Note 'The real check is the position itself - note it before unplugging and compare after, on the'
Write-Note 'phone and in the web client. Product priority 2 is the reason this one is done by hand.'

Write-Step 'Section 2.9 step 8 - Voice, if the car has it'
Write-Note 'Say: Hey Google, play <a book you own>. That is onSetMediaItems with a search query rather than'
Write-Note 'a media id - a different branch from a tap, and one only a real microphone reaches.'
Clear-Logcat
Wait-ForTester 'Try the voice request now.'
$spoken = @(Show-LogcatMatches -Pattern 'A controller asked to set what plays' -Last 5)
if ($spoken.Count -eq 0) {
    # Gated like the selection branch: absence locates nothing when logcat is not carrying the app.
    if ($script:LogcatCarriesApp -eq 'yes') {
        Write-Bad 'The spoken request never reached this service.'
    }
    else {
        Write-Bad 'No spoken request found, and logcat is not carrying this app - so this locates nothing.'
        Write-Note 'Read Settings > About > Diagnostics > the event log and search "asked to set" first.'
    }
} else {
    $spoken | ForEach-Object { Write-Output $_ }
    Write-Note 'Expect branch=spoken and kind=empty: a spoken request carries a search query and an EMPTY'
    Write-Note "media id, and 'empty' is what kindOf calls that. kind=none means no item arrived at all."
    Write-Note 'branch=browse would mean the voice host resolved the title itself and sent an id instead.'
}
