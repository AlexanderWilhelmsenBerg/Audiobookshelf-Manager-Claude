#!/usr/bin/env bash
#
# Shared by every step script in this directory. Not runnable on its own.
#
# ## Why these scripts exist
#
# `docs/device-test-0.9.14.md` is the test; this directory is the commands in it, one script per section,
# so a tester types `./scripts/device-test/02-android-auto.sh` instead of transcribing four commands with
# a package name in each. A transcribed command is a command that can be typed against the wrong package,
# and `org.homebord.bookwave` and `org.homebord.bookwave.debug` are one suffix apart.
#
# The number on a script is its **run order, not its section number**: `09-privacy-and-lock.sh` covers §9
# and §10 together, so everything after it sits one behind. Each script's first comment names its section.
#
# ## What they will and will not do
#
# They **read**, they **build**, and they **install onto a device you have attached**. None of them
# changes your server, and none installs a tool — `scripts/check-local-environment.sh --install` is the
# only script in this repository that installs anything.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

# The debug build carries the suffix; the release build does not. Every adb command below needs the
# right one, and picking the wrong one silently targets an app that may not even be installed.
PKG_DEBUG="org.homebord.bookwave.debug"
PKG_RELEASE="org.homebord.bookwave"
PKG="${BOOKWAVE_PKG:-$PKG_DEBUG}"

BOLD=$'\033[1m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; RED=$'\033[31m'; OFF=$'\033[0m'
[[ -t 1 ]] || { BOLD=""; GREEN=""; YELLOW=""; RED=""; OFF=""; }

step()  { printf '\n%s%s%s\n' "$BOLD" "$1" "$OFF"; }
ok()    { printf '  %s✓%s %s\n' "$GREEN" "$OFF" "$1"; }
warn()  { printf '  %s!%s %s\n' "$YELLOW" "$OFF" "$1"; }
bad()   { printf '  %s✗%s %s\n' "$RED" "$OFF" "$1"; }
note()  { printf '      %s\n' "$1"; }
# Echoes what it is about to run, then runs it. The adb path is shown as `adb` so the echoed line is one
# a reader can copy — the absolute path is what executes, which is the point of resolving it at all.
run() {
  local shown="$*"
  [[ -n "${ADB:-}" ]] && shown="${shown//$ADB/adb}"
  printf '  %s$ %s%s\n' "$BOLD" "$shown" "$OFF"
  "$@"
}

# `run` must never appear on the left of a pipe: the pipe binds to `run` itself, so the line it echoes is
# swallowed by whatever filters its output and the reader is shown a result with no command above it.
# `show` prints the pipeline — as `adb`, which is what a reader would type, not the absolute path — and the
# caller runs it on the next line.
show() { printf '  %s$ %s%s\n' "$BOLD" "$*" "$OFF"; }

# The app's own logcat tag. `AndroidLogSink` writes every line as `ShelfPlayer/<Category>`, so this is what
# separates "the app logged nothing" from "logcat is not carrying the app at all" — which are opposite
# findings and, on the 2026-08-28 run, looked identical.
APP_TAG="ShelfPlayer"

# Clear the log buffer so what follows is a small, fresh window.
#
# This is the fix for the defect the 2026-08-28 run exposed. These scripts used to dump `logcat -d` long
# after the thing being measured, and on that Samsung the buffer had rolled: every grep came back empty
# while the in-app event log held all four `children=` lines. A tester following the script would have read
# an empty result as a failed browse when the browse had worked — a signal that means nothing, which is
# exactly what `docs/risks.md` R-15 is about. Clear first, act, then dump.
logcat_clear() {
  require_adb
  show "adb logcat -c"
  "$ADB" logcat -c 2>/dev/null || true
  ok "log buffer cleared — do the step now, then let this script dump it"
}

# One grep over BookWave's own log lines, with the preflight that makes an empty result mean something.
#
# Never `|| true` on the pipeline as a whole: a grep that matches nothing is the expected result in half
# these sections and the finding in the other half, so each caller says which. But neither reading is safe
# until we know logcat carries the app at all, which is what the preflight settles.
logcat_grep() {
  local pattern="$1" n="${2:-20}" carried
  show "adb logcat -d | grep -iE \"$pattern\" | tail -$n"
  carried=$("$ADB" logcat -d 2>/dev/null | grep -c "$APP_TAG" || true)
  "$ADB" logcat -d 2>/dev/null | grep -iE -- "$pattern" | tail -"$n" || true
  if (( carried == 0 )); then
    bad "logcat holds NO $APP_TAG lines at all, so nothing above is evidence either way."
    note "This device is not giving adb the app's log — a rolled buffer, or a vendor filter. Read"
    note "Settings → About → Diagnostics → Open the event log instead, and search for the phrase."
    note "Seen on an SM-S928B on 2026-08-28: logcat empty, the in-app log complete (R-70)."
  else
    note "logcat is carrying the app ($carried lines), so an empty result above is a real absence."
  fi
}

# The SDK, resolved the way the build resolves it, so these scripts work whether or not the tools are on
# the PATH. Windows users: `. .\scripts\Set-BookWavePath.ps1` does the same for a PowerShell session.
resolve_sdk() {
  local sdk=""
  [[ -f local.properties ]] && sdk=$(sed -n 's/^sdk\.dir=//p' local.properties | tail -1) && sdk=${sdk//\\:/:}
  [[ -z "$sdk" && -n "${ANDROID_HOME:-}" ]] && sdk="$ANDROID_HOME"
  [[ -z "$sdk" && -n "${ANDROID_SDK_ROOT:-}" ]] && sdk="$ANDROID_SDK_ROOT"
  for c in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "/usr/lib/android-sdk" "/opt/android-sdk"; do
    [[ -z "$sdk" && -d "$c" ]] && sdk="$c"
  done
  printf '%s' "$sdk"
}

SDK="$(resolve_sdk)"
ADB="${SDK:+$SDK/platform-tools/adb}"
[[ -x "${ADB:-}" ]] || ADB="$(command -v adb || true)"

# Newest build-tools rather than the pinned one: apksigner and aapt2 are compatible across versions.
newest_build_tool() {
  local name="$1"
  [[ -n "$SDK" && -d "$SDK/build-tools" ]] || { command -v "$name" || true; return; }
  find "$SDK/build-tools" -maxdepth 2 -name "$name" -type f 2>/dev/null | sort -V | tail -1
}

require_adb() {
  if [[ -z "${ADB:-}" || ! -x "$ADB" ]]; then
    bad "No adb. Run ./scripts/check-local-environment.sh --install, or add platform-tools to your PATH."
    exit 1
  fi
}

require_device() {
  require_adb
  local n
  n=$("$ADB" devices 2>/dev/null | tail -n +2 | grep -c 'device$' || true)
  if (( n == 0 )); then
    bad "No device attached and authorised."
    note "Plug the phone in, unlock it, and accept the 'Allow USB debugging' prompt."
    exit 1
  fi
  ok "$n device(s) attached"
}

# The log every section asks for. Copied from the app rather than adb where the document says so — the
# in-app log is redacted (14.5) and logcat is not, so a pasted logcat can carry things the app is careful
# never to show. These helpers read only BookWave's own tag namespace.
dump_app_log() {
  require_adb
  step "BookWave's log lines from logcat"
  note "The in-app event log (Settings → About → Diagnostics) is the redacted one and is what the"
  note "document asks you to paste. This is the same information plus anything logged before the app's"
  note "own buffer existed, and it is NOT redacted — read it, do not paste it into a public report."
  "$ADB" logcat -d -v time 2>/dev/null | grep -iE "BookWave|shelfplayer|homebord" | tail -"${1:-200}"
}
