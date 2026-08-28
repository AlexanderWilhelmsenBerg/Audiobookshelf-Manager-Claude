#!/usr/bin/env bash
# docs/device-test-0.9.14.md §9 and §10 — the Recents thumbnail, and the passcode through reauthentication.
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"
require_device

step "§9  Which Android is this?"
run "$ADB" shell getprop ro.build.version.release
run "$ADB" shell getprop ro.build.version.sdk
note "API 33+ must show NO readable library in the app switcher. API 26–32 still shows it: that is the"
note "recorded, accepted residual (R-62), not a defect."
note "Then take an ordinary screenshot — it must still work. If it is blocked, FLAG_SECURE crept in."

step "§10  The passcode through reauthentication"
note "1. Settings → Passcode lock → set a 6–12 digit code. Force-stop, reopen: the curtain appears."
run "$ADB" shell am force-stop "$PKG"
note "2. In the web UI, change that user's password."
note "   Whether 2.36.0 invalidates an outstanding refresh token this way is NOT captured — if the"
note "   profile never flips to 'Needs to sign in again', that is a result worth reporting."
note "3. Sign in again from the shelf banner, force-stop, reopen."
note "   EXPECT: the curtain is STILL there. That is the fix (R-44)."
note "4. Then at the curtain: 'Forgotten your passcode?' → 'Sign in and clear the passcode'."
note "   EXPECT: after that, reopening goes straight in."
