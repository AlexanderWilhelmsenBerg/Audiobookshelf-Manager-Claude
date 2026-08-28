#!/usr/bin/env bash
# docs/device-test-0.9.14.md §7 — the four 17.3 numbers and the baseline profile.
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"
require_device

step "§7  Before you start"
warn "Unlock the phone, plug it in, and leave it alone. An animation or a notification shade during a"
warn "frame-timing pass corrupts the result."
note "It seeds a 2,000-book fixture library, so give it room and time."

step "§7  Run"
run ./gradlew :benchmark:connectedBenchmarkAndroidTest

step "§7  Where the numbers land"
note "Console output, plus JSON under benchmark/build/outputs/connected_android_test_additional_output/"
note "Fill in docs/benchmark.md's Results table with the device, Android version and date."
note "Commit the recorded baseline-prof.txt."
warn "Read the scroll figure honestly: the fixture seeds no covers, so it is a floor, not an answer."
