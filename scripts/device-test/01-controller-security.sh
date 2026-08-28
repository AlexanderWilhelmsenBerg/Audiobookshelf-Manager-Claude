#!/usr/bin/env bash
# docs/device-test-0.9.14.md §1 — the untrusted-controller fix, and the trusted surfaces it must not break.
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"
require_device

step "§1  What is bound to the media session right now"
note "adb shell holds MEDIA_CONTENT_CONTROL, so anything you drive from here is the TRUSTED branch."
note "§1.3 needs a third-party app; adb cannot stand in for it."
show "adb shell dumpsys media_session | sed -n '/Sessions Stack/,/\$/p'"
"$ADB" shell dumpsys media_session 2>/dev/null | sed -n '/Sessions Stack/,/^$/p' | head -40

step "§1  The app's own session"
show "adb shell dumpsys media_session | grep -iA6 $PKG"
"$ADB" shell dumpsys media_session 2>/dev/null | grep -iA6 "$PKG" | head -30 || true

step "§1.4  Transport from a trusted caller (this is adb, so it must work)"
note "Watch the phone: each of these should move playback."
for key in 85 87 88; do
  case $key in 85) what="play/pause";; 87) what="next";; 88) what="previous";; esac
  printf '  press %s (%s)\n' "$key" "$what"
  "$ADB" shell input keyevent "$key"
  sleep 2
done

dump_app_log 120
step "Now read the in-app log"
note "Settings → About → Diagnostics → Open the event log → Copy."
note "§1.3 expects:  A controller connected without library access   controller=<package>"
