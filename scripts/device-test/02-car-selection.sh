#!/usr/bin/env bash
# docs/device-test-0.9.14.md §2.8 — tapping a book in the car. The half that browse population does not cover.
#
# The 2026-08-28 run found a populated browse tree whose books would not open: the head unit stayed on
# "Henter valget ditt" forever. It could not be diagnosed, because a car tap that *resolved* logged nothing
# at all — the browse tree said children=6 and the next line was minutes later. Two candidate explanations
# were refuted by evidence that already existed, which left none for what did happen (R-71).
#
# This script exists to capture the one window that was missing. Clear, tap, dump.
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"
require_device

step "§2.8  Clear the log, so what follows is only the tap"
logcat_clear

step "§2.8  Now tap a book in the car"
note "In the DHU or the head unit: open a shelf, tap a book, and WAIT — up to 30 seconds. What is being"
note "measured is whether it ever loads, so leaving too early records the wrong answer."
note "Watch the car: does the loading message clear, and does audio start?"
read -r -p "  Press Enter once the book has either started or clearly hung… " _

step "§2.8  What the service was asked, and what it answered"
logcat_grep "A controller asked to set what plays" 10
note "kind=  the SHAPE of the id (book / at / tab / root), never the id — a book id names a book (14.5)."
note "asked= how many items the car sent.  handedBack= how many this service resolved."
warn "handedBack=0 means this service could not resolve the tap: the defect is in resolution."
warn "handedBack=1 means the service answered correctly and the defect is downstream, in the player."

step "§2.8  What the player then did with it"
logcat_grep "The player changed state" 15
note "Expect buffering then ready. Only 'idle', or no line at all, means the queue never reached the"
note "player or was never prepared — which is a different defect from a queue that failed to load."

step "§2.8  Anything that threw"
logcat_grep "A browse request failed" 10
note "Expect nothing. This line covers EVERY session callback, so its absence rules out a throw in the"
note "selection path — that is how the first hypothesis for this defect was refuted."

step "§2.8  Could the book be opened at all"
logcat_grep "Could not open a session for a browse or resume request" 5
note "If this appears, the server refused to open the session and resolution failed for that reason."

step "§2.8  And the same four lines from the in-app log"
note "If logcat carried nothing above, this is not optional — it is the only record (R-70):"
note "  Settings → About → Diagnostics → Open the event log → search 'asked to set'"
note "Then search 'player changed state'. Copy both into the report."
