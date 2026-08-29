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
note "Plug the phone into the car, or start wireless Android Auto, then read this line."
logcat_grep "A car connected to the media session" 5
note "Expect controller=com.google.android.projection.gearhead — projected Android Auto."
note "The DHU reports the SAME package, so this cannot tell them apart: record which you used."
warn "Anything else is Automotive OS or a vendor host, and this app has never seen one. Report it."

step "§2.9 step 2  The car's own launcher"
note "Find BookWave in the car's app list. The DHU has its own launcher and proves nothing about this."
note "Missing here but present in the DHU is a DISCOVERY defect — read Settings → About → This device."

step "§2.9 step 3  Browse and select, in the car"
note "Repeat §2's counts and §2.8's tap here. A difference between car and DHU IS the finding, so"
note "record both numbers. ./scripts/device-test/02-car-selection.sh captures the tap."

step "§2.9 step 4  Steering-wheel and hard buttons"
note "Next, previous, play/pause from the wheel, and the volume knob. These arrive as media-button"
note "events and never touch the DHU, so nothing recorded so far says whether they work."
read -r -p "  Press Enter once you have tried the wheel controls… " _
logcat_grep "The player changed state" 10

step "§2.9 step 5  Driving restrictions"
warn "Only with somebody else driving, or on a rolling road. Skip it otherwise and say so."
note "The car truncates long lists and hides text while moving — that is the host, not a defect. A list"
note "that becomes unusable, or a row whose label is meaningless once truncated, is."

step "§2.9 step 6  Ignition off, ignition on"
note "Stop the engine, let the head unit power down, restart it. Expect BookWave back, and the resume"
note "tile offering your book at the position you left. Closing a DHU window is not a power cycle."

step "§2.9 step 7  Unplug while playing"
note "Pull the cable mid-book. Playback must continue on the phone and progress must not be lost —"
note "product priorities 1 and 2 in one step. Then plug back in and confirm the car picks it up."
read -r -p "  Press Enter once you have unplugged and replugged… " _
logcat_grep "A controller asked to set what plays" 10
logcat_grep "The server accepted a position" 6

step "§2.9 step 8  Voice, if the car has it"
note "'Hey Google, play <a book you own>'. That is onSetMediaItems with a search query rather than a"
note "media id — a different branch from a tap, and one only a real microphone reaches."
logcat_grep "A controller asked to set what plays" 5
note "Expect kind=none for a spoken request: it arrives with a query and no media id."
