#!/usr/bin/env bash
# docs/device-test-0.9.14.md §5 — whole-book positions across a multi-file book.
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"
require_device

step "§5.1  Force-stop mid-book"
note "Play a MULTI-FILE book past the first file — say 8 minutes into a book whose first file is 5."
note "Write the position down to the second, then:"
run "$ADB" shell am force-stop "$PKG"
ok "stopped — reopen the app and compare the position"

step "§5.2  The degraded path, if you can provoke it"
note "Needs two tracks whose duration the server cannot determine. One is no longer enough: the app"
note "recovers a single unknown length from the book total (R-61)."
logcat_grep "plays its first file only" 5
warn "No output is the expected result. This path has never been observed on a real server."
