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
SEL=$("$ADB" logcat -d 2>/dev/null | grep -iE "A controller asked to set what plays" || true)
ASKED=0; RESOLVED=0
if [[ -n "$SEL" ]]; then
  ASKED=$(printf '%s\n' "$SEL" | grep -c . || true)
  RESOLVED=$(printf '%s\n' "$SEL" | grep -c "resolved=true" || true)
fi
logcat_grep "A controller asked to set what plays" 10
if (( ASKED == 0 )); then
  # Only a diagnosis when the log can support one. If logcat is not carrying the app, absence says
  # nothing about where the defect is, and `logcat_grep` has already said so — a layer attribution on
  # top of that would contradict it in the same breath.
  if [[ "${LOGCAT_CARRIES_APP:-unknown}" == "yes" ]]; then
    bad "No selection reached this service. The defect is upstream of it — discovery, or the item's flags."
  else
    bad "No selection line found, and logcat is not carrying this app — so this locates nothing."
    note "Read Settings → About → Diagnostics → the event log and search 'asked to set' before concluding."
  fi
fi
note "branch=   which route answered: browse (a car tap), spoken (a voice query), passthrough (the app)."
note "kind=     the SHAPE of the id (book / at / tab / root), never the id — an id names a book (14.5)."
note "resolved= whether the request turned into a book. THIS is the field to read."
warn "resolved=false means resolution failed here, whatever handedBack says: when nothing resolves and a"
warn "book is already playing, the service hands that book back to keep it alive, so handedBack=1 does"
warn "NOT mean the tap worked. resolved=true with no player state change is the player's half."

step "§2.8  What the player then did with it"
logcat_grep "The player changed state" 15
# This step can only speak for the tap if the tap resolved. A book already streaming produces
# buffering/ready from an ordinary rebuffer inside the same 30-second window, so a pass here without a
# `resolved=true` selection would claim the queue reached the player while the step above says the
# opposite. Correlating by timestamp in shell is the kind of cleverness that has generated defects on
# this branch already; requiring the earlier fact is both simpler and true.
# **This step deliberately issues no pass.** Two attempts at one both failed for the same reason: a
# `state=buffering`/`ready` in this window is produced by an ordinary rebuffer of a book that was
# already playing, as well as by your tap. Requiring a `resolved=true` line somewhere in the window does
# not fix it — the rebuffer can precede the tap, and the tap can then resolve and still never reach the
# player, which is exactly the defect §2.8 exists to find. Ordering is the only thing that separates
# them, and correlating timestamps in shell is what produced the last two defects here.
#
# So the script reports and the reader judges, which for two timestamps is a second's work and is
# reliable. R-71 records why this whole family of inference is capped.
if (( RESOLVED > 0 )); then
  note "A selection resolved. Now compare TIMESTAMPS by eye: a buffering/ready line AFTER the"
  note "'asked to set … resolved=true' line is your tap reaching the player. One before it is not."
  note "Only 'idle' after that line means the player took the queue and refused to prepare."
  note "No line after it at all means the queue never reached the player — the likeliest shape here."
else
  warn "No resolved=true selection above, so nothing here can be attributed to your tap: any"
  warn "buffering/ready you see may be an ordinary rebuffer of a book that was already playing."
  note "Read the lines above for context only. The verdict that matters is resolved= in the step before."
fi

step "§2.8  Anything that threw"
logcat_grep "A browse request failed" 10
note "Expect nothing. This line covers EVERY session callback, so its absence rules out a throw in the"
note "selection path — that is how the first hypothesis for this defect was refuted."

step "§2.8  Could the book be opened at all"
logcat_grep "Could not open a session for a browse or resume request" 5
note "If this appears, the server refused to open the session and resolution failed for that reason."
note "If it does NOT appear, that proves only its absence: the line is also missing when nothing"
note "reached the service, and when a browse id never parsed. resolved= above is what says whether the"
note "request became a book — do not read silence here as the server having opened one."

step "§2.8  And the same four lines from the in-app log"
note "If logcat carried nothing above, this is not optional — it is the only record (R-70):"
note "  Settings → About → Diagnostics → Open the event log → search 'asked to set'"
note "Then search 'player changed state'. Copy both into the report."
