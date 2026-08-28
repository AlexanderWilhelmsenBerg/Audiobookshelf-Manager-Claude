#!/usr/bin/env bash
# docs/device-test-0.9.14.md §8 — progress survives a kill, and two hours of playback.
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"
require_device

step "§8.1  Kill the process (not a force-stop)"
note "Play a multi-file book, write the position down to the second, then press Home."
note "am kill is a no-op against a FOREGROUND process, so backgrounding first is not optional —"
note "without it the test passes without testing anything."
read -r -p "  Press Enter once the app is in the background… " _
run "$ADB" shell am kill "$PKG"
if "$ADB" shell pidof "$PKG" >/dev/null 2>&1; then
  bad "still running — it was probably still in the foreground. Press Home and run this again."
else
  ok "process gone. Reopen the app: the position must be within five seconds of where it was."
fi

step "§8.2  The two-hour soak"
note "Start a long book, plug in, leave it. Check at 30 / 60 / 90 / 120 minutes:"
note "  · still playing, and the notification clock is moving"
note "  · the phone and the web client still agree on the position"
note "  · Settings → About → 'Times playback ran out of buffer' — a handful is ordinary, dozens is a finding"
note "At the end, look for anything repeated hundreds of times in the log."
