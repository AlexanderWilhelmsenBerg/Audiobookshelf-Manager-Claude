#!/usr/bin/env bash
# docs/device-test-0.9.14.md §2.9 — a real car, not the Desktop Head Unit.
#
# Every car result this project holds came from the DHU. The DHU is the same projection stack driving a
# window on a computer, so it covers the browse tree and covers none of the car's own contribution: the
# launcher, the steering wheel, driving restrictions, ignition cycles, an unplugged cable, the microphone.
# Each of those is a way this app can fail for a listener while every recorded test stays green.
#
# Do all of it parked. Step 5 needs the car moving and needs somebody else driving; skipping it is a result.
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"
require_device

step "§2.9 step 1  Which host actually answered"
note "Plug the phone into the car, or start wireless Android Auto."
# Clear BEFORE the connection, or an older DHU session left in the buffer answers for the car in front
# of you: the verdict passes on any `gearhead` line, so a newly connected Automotive OS or vendor host
# would be reported as projected Android Auto on the strength of a stale one. This step exists to name
# the CURRENT host, so the window has to start empty.
logcat_clear
read -r -p "  Press Enter once the car has connected and BookWave is on its screen… " _
logcat_grep "A car connected to the media session" 5
step_verdict "A car connected to the media session" "gearhead" \
  "a projected Android Auto host connected" \
  "No projected Android Auto connection recorded. Connect first, or read the in-app event log."
note "Expect controller=com.google.android.projection.gearhead — projected Android Auto."
note "The DHU reports the SAME package, so this cannot tell them apart: record which you used."
warn "Anything else is Automotive OS or a vendor host, and this app has never seen one. Report it."

step "§2.9 step 2  The car's own launcher"
note "Find BookWave in the car's app list. The DHU has its own launcher and proves nothing about this."
note "Missing here but present in the DHU is a DISCOVERY defect — read Settings → About → This device."
read -r -p "  Press Enter once you have looked for BookWave in the car's app list… " _

step "§2.9 step 3  Browse and select, in the car"
note "Repeat §2's counts and §2.8's tap here. A difference between car and DHU IS the finding, so"
note "record both numbers. ./scripts/device-test/02-car-selection.sh captures the tap."
read -r -p "  Press Enter once you have browsed and tried to open a book in the car… " _

step "§2.9 step 4  Steering-wheel and hard buttons"
note "Next, previous, play/pause from the wheel, and the volume knob. These arrive as media-button"
note "events and never touch the DHU, so nothing recorded so far says whether they work."
warn "The log can witness PLAY/PAUSE only. Volume changes no playback state, and next/previous are"
warn "no-ops on the one-item queue a car selection builds — judge those two by ear, not from here."
# Clear first, or step 3's own lines are still in the buffer and a wheel that does nothing reads as a
# wheel that worked. And look for the play/pause line rather than a state change: play/pause leaves the
# player in STATE_READY, so a working wheel produces no state change at all.
logcat_clear
read -r -p "  Press Enter once you have used the wheel's PLAY/PAUSE button… " _
logcat_grep "Playback was asked to change" 10
# reason=remote is the pass, not the mere presence of a line: userRequest is the phone's own UI and
# audioFocusLoss is nobody at all, and either would otherwise be counted as the wheel working.
step_verdict "Playback was asked to change" "reason=remote" \
  "play/pause reached the session from a remote controller — the wheel" \
  "The wheel's play/pause did not reach the session as a remote request. Unsupported here, or refused
      — and PR #48 narrowed exactly that command surface, so say which if you can tell."
note "reason=userRequest would mean the phone's own UI did it; reason=audioFocusLoss, nobody did."

step "§2.9 step 5  Driving restrictions"
warn "Only with somebody else driving, or on a rolling road. Skip it otherwise and say so."
note "The car truncates long lists and hides text while moving — that is the host, not a defect. A list"
note "that becomes unusable, or a row whose label is meaningless once truncated, is."
read -r -p "  Press Enter once you have done this, or decided to skip it… " _

step "§2.9 step 6  Ignition off, ignition on"
note "Stop the engine, let the head unit power down, restart it. Expect BookWave back, and the resume"
note "tile offering your book at the position you left. Closing a DHU window is not a power cycle."
logcat_clear
read -r -p "  Press Enter once the head unit has powered down and come back up… " _
logcat_grep "A car connected to the media session" 5
step_verdict "A car connected to the media session" "controller=" \
  "the car reconnected after the power cycle — now judge the resume tile by eye" \
  "No fresh connection line: the car did not reconnect after the power cycle. That is a different
      finding from a resume tile that is missing or wrong."

step "§2.9 step 7  Unplug while playing"
note "Pull the cable mid-book. Playback must continue on the phone and progress must not be lost —"
note "product priorities 1 and 2 in one step. Then plug back in and confirm the car picks it up."
logcat_clear
read -r -p "  Press Enter once you have unplugged and replugged… " _
logcat_grep "A controller asked to set what plays" 10
logcat_grep "The server accepted a position" 6
step_verdict "The server accepted a position" "accepted a position" \
  "progress reached the server across the disconnect" \
  "No accepted server position after the reconnect — progress may not have survived the unplug.
      Check the in-app event log before concluding."

step "§2.9 step 8  Voice, if the car has it"
note "'Hey Google, play <a book you own>'. That is onSetMediaItems with a search query rather than a"
note "media id — a different branch from a tap, and one only a real microphone reaches."
# Clear and then WAIT. Dumping straight after printing the instruction would record step 7's lines and
# nothing of the request, because the tester has not spoken yet.
logcat_clear
read -r -p "  Press Enter once you have made the voice request… " _
logcat_grep "A controller asked to set what plays" 5
note "Expect branch=spoken and kind=empty: a spoken request carries a search query and an EMPTY media"
note "id, and 'empty' is what kindOf calls that. kind=none means no item arrived at all."
note "branch=browse here would mean the voice host resolved the title itself and sent an id instead."
