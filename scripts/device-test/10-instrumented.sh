#!/usr/bin/env bash
# docs/device-test-0.9.14.md §11 — the instrumented tier, which CI can never run.
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"
require_device

step "§11  The profile lock's real Keystore storage"
note "The only tier that exercises AndroidKeyStore; Robolectric ships no provider for it."
note "Safe alongside the installed app: the test APK has its own package and therefore its own UID."
run ./gradlew :core:datastore:connectedDebugAndroidTest
