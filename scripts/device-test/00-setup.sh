#!/usr/bin/env bash
# docs/device-test-0.9.14.md §0 — build, verify, install, and confirm which build is on the phone.
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

step "§0.1  Environment"
run ./scripts/check-local-environment.sh || true

step "§0.1  The gate"
note "--rerun-tasks is not optional on a branch that changed a classpath (R-31)."
run ./gradlew --stop || true
run ./gradlew ktlintFormat
run ./gradlew ktlintCheck
run ./gradlew verifyDebug -Pshelfplayer.warningsAsErrors=true --no-build-cache --rerun-tasks

step "§0.1  Install"
require_device
run ./gradlew :app:installDebug

step "§0.1  Which build is actually on the phone"
AAPT="$(newest_build_tool aapt2)"
APK=app/build/outputs/apk/debug/app-debug.apk
if [[ -n "$AAPT" && -f "$APK" ]]; then
  "$AAPT" dump badging "$APK" | sed -n "s/^package: name='\([^']*\)'.*versionCode='\([^']*\)'.*versionName='\([^']*\)'.*/  package=\1  code=\2  name=\3/p"
  note "Settings → About → Version must match. If it does not, every result is misfiled (R-04)."
else
  warn "No aapt2 or no APK; read the version from Settings → About instead."
fi

step "§0.2  The signature, which decides whether the next install wipes the phone"
APKSIGNER="$(newest_build_tool apksigner)"
if [[ -n "$APKSIGNER" && -f "$APK" ]]; then
  "$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | sed -n 's/.*certificate SHA-256 digest: /  signer=/p' | head -1
  note "Write this down. If it differs from the last build's, adb install -r will fail and you will"
  note "have to uninstall — losing the sign-in, the passcode, the progress and the downloads (R-68)."
  note "docs/release.md § Signing explains how to make it stable."
fi
