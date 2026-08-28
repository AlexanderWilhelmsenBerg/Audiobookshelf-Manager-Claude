#!/usr/bin/env bash
# docs/device-test-0.9.14.md §2 — the car browse tree. The pass condition is a `children=` line per root.
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"
require_device

step "§2 step 1  Force-stop, so the next launch is cold"
run "$ADB" shell am force-stop "$PKG"
ok "stopped — now connect the DHU and open BookWave"

step "§2 step 2  Start the Desktop Head Unit"
note "In another terminal, on the machine with the DHU installed:"
note "  \$ANDROID_HOME/extras/google/auto/desktop-head-unit"
note "The phone needs Android Auto in developer mode with 'Start head unit server' enabled."
note "Forward the port first if the DHU does not connect on its own:"
note "  adb forward tcp:5277 tcp:5277"

step "§2 step 4  The measurement — every browse the app answered"
note "Run this AFTER the car has shown its browse root. Each line is one node the car asked for."
logcat_grep "asked for a node's children"
warn "No output above is a FAILURE now. Before the R-66 fix there were no children= lines at all,"
warn "because the browse threw before it answered. One line per node the car asked for is the pass."

step "§2 step 5  Any browse that failed — this must now find nothing"
logcat_grep "A browse request failed" 10
note "Nothing here is the pass condition. If a line does appear, thrown= names the exception class;"
note "before 2026-08-27 it said only error=unknown, and that missing word cost three sessions (R-66)."

step "§2 step 7  Transport, which must still work"
note "In the car: +30s and -30s. Then check the position the server was told:"
logcat_grep "The server accepted a position" 6
