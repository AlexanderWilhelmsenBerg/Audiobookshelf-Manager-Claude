#!/usr/bin/env bash
# docs/device-test-0.9.14.md §4 — the sleep-timer history rows. All already built; this confirms them.
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"
require_device

step "§4  What to do on the phone"
note "1. Play a book, set a 5 min sleep timer     → history row 'Sleep timer set'"
note "2. Extend it from the notification or shake → 'Sleep timer extended'"
note "3. Set a 30 s custom timer and let it run   → 'Sleep timer ended playback'"
note "                                            → and 'Rewound after the sleep timer'"
note "4. Tap the ended-timer row IN THE PLAYER's pane (the book screen's is read-only by design)."

step "§4  The notification, while a timer is running"
note "The extend button appears only while a timer is active — that is deliberate, not a defect."
show "adb shell dumpsys notification --noredact | grep -iA12 $PKG"
"$ADB" shell dumpsys notification --noredact 2>/dev/null | grep -iA12 "$PKG" | head -40 || true
note "Look for an action whose title is the extend label. Absent with a timer RUNNING is a defect;"
note "absent with no timer set is correct."
