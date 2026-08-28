#!/usr/bin/env bash
# docs/device-test-0.9.14.md §12 — the About tab, the event log's filters, and the language crash (R-67).
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"
require_device

step "§12.1  The About tab"
note "Settings → About. Expect NO 'Checks after wave 3' section — it was Phase 2 scaffolding and is gone."
note "Expect the old 'Testing' section to read 'This device'."
note "Then switch to Norsk bokmål and look again: both changes are in that locale too."

step "§12.2  The event log"
note "Play something first so there are a few hundred lines. Settings → About → Diagnostics → Open the"
note "event log. Search the message and the area; filter by Level and by Area; combine all three (AND)."
note "Expect 'Showing 12 of 400' while narrowed, and 'No events match…' rather than 'Nothing recorded yet'."
note "Watch the Area chips while playback logs: they must NOT re-sort as new lines arrive."

step "§12.3  The language crash (R-67) — force-stop and reopen, twice"
warn "Until 2026-08-27 failing this meant reinstalling. It should not any more."
note "Set Settings → Appearance → Language → Norsk bokmål, then run this. Each cycle must come back up."
for i in 1 2; do
  printf '  cycle %s\n' "$i"
  "$ADB" shell am force-stop "$PKG"
  sleep 1
  "$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 4
  if "$ADB" shell pidof "$PKG" >/dev/null 2>&1; then
    ok "still running after cycle $i"
  else
    bad "the app is not running after cycle $i — it crashed on launch"
  fi
done

step "§12.3  Anything that threw"
logcat_grep "HiltViewModelFactory|FATAL EXCEPTION|AndroidRuntime"
note "Expect nothing. 'Expected an activity context … but instead found: android.app.ContextImpl' is R-67."
note "Then switch back to English and to 'Follow the system', running this again after each."
