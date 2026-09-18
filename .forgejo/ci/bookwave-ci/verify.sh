#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "bookwave-ci verify: $*" >&2
  exit 1
}

node_major="$(node --version | sed -E 's/^v([0-9]+).*/\1/')"
[ "$node_major" = "22" ] || fail "expected Node 22, got $(node --version)"

java_major="$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
[ "$java_major" = "17" ] || fail "expected JDK 17, got ${java_major:-unknown}"

[ "${ANDROID_HOME:-}" = "/opt/android-sdk" ] || fail "ANDROID_HOME is not /opt/android-sdk"
[ "${ANDROID_SDK_ROOT:-}" = "/opt/android-sdk" ] || fail "ANDROID_SDK_ROOT is not /opt/android-sdk"

marker="/opt/android-sdk/cmdline-tools/latest/.bookwave-commandline-tools-build"
[ -f "$marker" ] || fail "Android command-line tools marker is missing"
[ "$(cat "$marker")" = "15859902" ] || fail "unexpected Android command-line tools build"

[ -x /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager ] || fail "sdkmanager missing"
[ -x /opt/android-sdk/platform-tools/adb ] || fail "platform-tools/adb missing"
[ -f /opt/android-sdk/platforms/android-36/android.jar ] || fail "android-36 platform missing"
[ -x /opt/android-sdk/build-tools/36.0.0/aapt2 ] || fail "build-tools 36.0.0/aapt2 missing"
[ -x /opt/android-sdk/build-tools/36.0.0/apksigner ] || fail "build-tools 36.0.0/apksigner missing"

for tool in git curl jq python3 unzip sha256sum; do
  command -v "$tool" >/dev/null 2>&1 || fail "$tool missing"
done

gitleaks_version="$(gitleaks version 2>/dev/null | sed -n 's/.*\([0-9]\+\.[0-9]\+\.[0-9]\+\).*/\1/p' | head -1)"
[ "$gitleaks_version" = "8.30.1" ] || fail "expected gitleaks 8.30.1, got ${gitleaks_version:-unknown}"

echo "bookwave-ci verify: OK"
echo "node=$(node --version)"
echo "java_major=$java_major"
echo "android_cmdline_tools=$(cat "$marker")"
echo "android_platform=36"
echo "android_build_tools=36.0.0"
echo "gitleaks=$gitleaks_version"
