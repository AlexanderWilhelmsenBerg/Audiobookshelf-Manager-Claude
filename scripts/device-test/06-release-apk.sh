#!/usr/bin/env bash
# docs/device-test-0.9.14.md §6 — build, verify and install a signed release APK.
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

step "§6.2  Build the release"
note "Unsigned unless the four bookwave.signing.* properties are set — the build now says so while it"
note "runs. docs/release.md § Signing has the keytool command."
run ./gradlew :app:assembleRelease

APK=app/build/outputs/apk/release/app-release.apk
APKSIGNER="$(newest_build_tool apksigner)"

step "§6.2  Is it signed?"
if [[ -n "$APKSIGNER" && -f "$APK" ]]; then
  if "$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | sed -n 's/^Signer #1 certificate SHA-256 digest: /  signer=/p'; then
    ok "signed and installable"
  else
    bad "UNSIGNED — it cannot be installed. Set the four signing properties and build again."
    exit 1
  fi
  note "v1 reading false is correct: v2 is verified from API 24 and this app's minSdk is 26."
else
  bad "No apksigner or no APK."
  exit 1
fi

step "§6.3  Install and launch it"
require_device
note "A different application id from debug ($PKG_RELEASE, no .debug), so it is a fresh install."
run "$ADB" install -r "$APK"
run "$ADB" shell monkey -p "$PKG_RELEASE" -c android.intent.category.LAUNCHER 1
ok "launched — now sign in and exercise playback, downloads, About, a bookmark and the history pane"
note "You are looking for anything that works in debug and not here: R8 removing a class that only"
note "reflection or serialization reaches is the classic failure."

step "§6.3  Keep the mapping if it crashes"
note "app/build/outputs/mapping/release/mapping.txt — a release stack trace is unreadable without it."
