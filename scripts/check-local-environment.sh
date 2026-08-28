#!/usr/bin/env bash
#
# Checks that this machine can build, test and device-test BookWave, and says exactly what is missing.
#
#   ./scripts/check-local-environment.sh            # report only
#   ./scripts/check-local-environment.sh --install  # additionally install missing Android SDK packages
#
# ## What it will and will not install
#
# `--install` runs `sdkmanager` for missing Android SDK packages, which is safe, scriptable and scoped to
# a directory this project already points at. It will **never** install a JDK, `jq`, or anything else
# needed from a system package manager: those want `sudo`, differ per platform, and a script that installs
# a JDK behind someone's back is a script nobody should run. Those are reported with the command to run.
#
# ## Why a checker rather than a setup script
#
# Most of what this needs is already on a machine that has Android Studio. The failure mode worth
# preventing is not "nothing is installed", it is **one thing is wrong and the error does not say so** —
# a missing `local.properties`, build-tools at the wrong version, or a device that is attached but
# unauthorised. Each check below prints what to do, not just that it failed.
set -uo pipefail

cd "$(dirname "$0")/.."

INSTALL=0
[[ "${1:-}" == "--install" ]] && INSTALL=1

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; BOLD=$'\033[1m'; OFF=$'\033[0m'
[[ -t 1 ]] || { RED=""; GREEN=""; YELLOW=""; BOLD=""; OFF=""; }

FAILURES=0
WARNINGS=0

ok()   { printf '  %s✓%s %s\n' "$GREEN" "$OFF" "$1"; }
warn() { printf '  %s!%s %s\n' "$YELLOW" "$OFF" "$1"; WARNINGS=$((WARNINGS + 1)); }
bad()  { printf '  %s✗%s %s\n' "$RED" "$OFF" "$1"; FAILURES=$((FAILURES + 1)); }
note() { printf '      %s\n' "$1"; }
head2() { printf '\n%s%s%s\n' "$BOLD" "$1" "$OFF"; }

# ---------------------------------------------------------------- required to build

head2 "Java"

# No Gradle toolchain is configured, so the JDK running Gradle is the one that compiles. The build
# *targets* 17 bytecode; it does not require a 17 JDK, and CI and every session so far have used 21.
if ! command -v java >/dev/null; then
  bad "No java on PATH."
  note "Install a JDK 17 or newer. Android Studio bundles one (Settings → Build Tools → Gradle → Gradle JDK)."
else
  JAVA_MAJOR=$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
  if [[ -z "$JAVA_MAJOR" ]]; then
    warn "Could not parse the Java version from 'java -version'."
  elif (( JAVA_MAJOR < 17 )); then
    bad "Java $JAVA_MAJOR is too old. Android Gradle Plugin 8.x needs 17 or newer."
  else
    ok "Java $JAVA_MAJOR (the build targets 17 bytecode; a newer JDK runs it fine)"
  fi
fi

head2 "Android SDK"

# Resolution order matches the build's own: local.properties wins, then the environment, then the
# platform defaults Android Studio uses.
SDK=""
if [[ -f local.properties ]]; then
  SDK=$(sed -n 's/^sdk\.dir=//p' local.properties | tail -1)
  SDK=${SDK//\\:/:}   # local.properties escapes colons on Windows-style paths
fi
[[ -z "$SDK" && -n "${ANDROID_HOME:-}" ]] && SDK="$ANDROID_HOME"
[[ -z "$SDK" && -n "${ANDROID_SDK_ROOT:-}" ]] && SDK="$ANDROID_SDK_ROOT"
for candidate in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "/usr/lib/android-sdk" "/opt/android-sdk"; do
  [[ -z "$SDK" && -d "$candidate" ]] && SDK="$candidate"
done

if [[ -z "$SDK" || ! -d "$SDK" ]]; then
  bad "No Android SDK found."
  note "Install Android Studio, then re-run this script — it will find the SDK and write local.properties."
  note "Looked at: local.properties sdk.dir, \$ANDROID_HOME, \$ANDROID_SDK_ROOT, and the usual defaults."
else
  ok "SDK at $SDK"

  # The build reads local.properties and nothing else. An SDK that exists but is not written down is the
  # single most common way a first local build fails.
  #
  # **Writing it needs `--install`.** This used to write it on a bare run, on the reasoning that fixing is
  # kinder than reporting. That reasoning is fine and the rule it broke matters more: a script advertised
  # as "report only" that edits a file in the working tree is a script whose promise cannot be trusted,
  # and the next person to add a convenience here would have had a precedent for it. Report, then act
  # when asked.
  if [[ ! -f local.properties ]] || ! grep -q '^sdk\.dir=' local.properties; then
    if (( INSTALL )); then
      echo "sdk.dir=$SDK" >> local.properties
      ok "wrote sdk.dir to local.properties (it is gitignored, as it should be)"
    else
      bad "local.properties has no sdk.dir, so the build cannot find the SDK"
      note "Re-run with --install to write it, or add this line to local.properties yourself:"
      note "  sdk.dir=$SDK"
    fi
  else
    ok "local.properties points at it"
  fi

  SDKMANAGER=""
  for m in "$SDK/cmdline-tools/latest/bin/sdkmanager" "$SDK/cmdline-tools/bin/sdkmanager" "$SDK/tools/bin/sdkmanager"; do
    [[ -x "$m" ]] && { SDKMANAGER="$m"; break; }
  done

  # Versions the build pins. compileSdk/targetSdk are 36; minSdk is 26 and needs no platform installed.
  NEEDED=("platforms;android-36" "build-tools;36.0.0" "platform-tools")
  MISSING=()
  [[ -d "$SDK/platforms/android-36" ]] && ok "platforms;android-36" || MISSING+=("platforms;android-36")
  [[ -d "$SDK/build-tools/36.0.0" ]] && ok "build-tools;36.0.0" || MISSING+=("build-tools;36.0.0")
  [[ -x "$SDK/platform-tools/adb" ]] && ok "platform-tools (adb)" || MISSING+=("platform-tools")

  if (( ${#MISSING[@]} > 0 )); then
    for m in "${MISSING[@]}"; do bad "missing $m"; done
    if [[ -z "$SDKMANAGER" ]]; then
      note "No sdkmanager found. Install them from Android Studio → Settings → Languages & Frameworks → Android SDK,"
      note "or install the command-line tools and re-run with --install."
    elif (( INSTALL )); then
      printf '\n  Installing %s…\n' "${MISSING[*]}"
      # Licences first: sdkmanager refuses to install without them and the prompt is not scriptable.
      yes | "$SDKMANAGER" --licenses >/dev/null 2>&1
      if "$SDKMANAGER" "${MISSING[@]}"; then
        ok "installed — re-run this script to confirm"
        FAILURES=$((FAILURES - ${#MISSING[@]}))
      else
        note "sdkmanager failed. Install them from Android Studio instead."
      fi
    else
      note "Re-run with --install to install them, or use Android Studio's SDK Manager."
      note "  $SDKMANAGER ${MISSING[*]}"
    fi
  fi
fi

# ---------------------------------------------------------------- required for specific tasks

head2 "The instrumented tier (needed only for connectedDebugAndroidTest)"

ADB=""
[[ -n "$SDK" && -x "$SDK/platform-tools/adb" ]] && ADB="$SDK/platform-tools/adb"
[[ -z "$ADB" ]] && command -v adb >/dev/null && ADB=$(command -v adb)

if [[ -z "$ADB" ]]; then
  warn "No adb, so no device tests. Everything else still works."
else
  # 2>/dev/null because adb announces "daemon not running; starting now" on first contact, which reads
  # as a failure in the middle of a health check and is not one.
  ADB_OUT=$("$ADB" devices 2>/dev/null)
  DEVICES=$(printf '%s\n' "$ADB_OUT" | tail -n +2 | grep -c 'device$' || true)
  UNAUTHORISED=$(printf '%s\n' "$ADB_OUT" | grep -c 'unauthorized$' || true)
  if (( DEVICES > 0 )); then
    ok "$DEVICES device(s) attached and authorised"
    note "./gradlew :core:datastore:connectedDebugAndroidTest"
  elif (( UNAUTHORISED > 0 )); then
    warn "A device is attached but unauthorised."
    note "Unlock it and accept the 'Allow USB debugging' prompt, then re-run."
  else
    warn "No device or emulator attached."
    note "The instrumented tests are the only reason a local session beats a cloud one — attach a device"
    note "or start an emulator before running them. Everything else works without one."
  fi
fi

head2 "The supply-chain checks"

if command -v jq >/dev/null; then
  ok "jq"
else
  warn "No jq, so ./scripts/vulnerability-scan.sh cannot run. ./gradlew :app:sbom still works."
  note "macOS: brew install jq   Debian/Ubuntu: sudo apt install jq   Fedora: sudo dnf install jq"
fi

head2 "The contract-capture harness (needed only when a server response shape changes)"

if command -v docker >/dev/null && docker info >/dev/null 2>&1; then
  ok "docker is running"
elif command -v docker >/dev/null; then
  warn "docker is installed but the daemon is not running."
else
  warn "No docker. Only needed to re-capture fixtures against a real Audiobookshelf container."
fi

# ---------------------------------------------------------------- verdict

head2 "Verdict"

if (( FAILURES > 0 )); then
  printf '  %s%d problem(s) will stop the build.%s Fix those above, then:\n\n' "$RED" "$FAILURES" "$OFF"
  printf '    ./gradlew verifyDebug -Pshelfplayer.warningsAsErrors=true\n\n'
  exit 1
fi

if (( WARNINGS > 0 )); then
  printf '  %sThe build will work.%s %d optional thing(s) above are unavailable.\n' \
    "$GREEN" "$OFF" "$WARNINGS"
else
  printf '  %sEverything is present.%s\n' "$GREEN" "$OFF"
fi

cat <<'NEXT'

  The commands, and which question each answers:

    ./gradlew ktlintFormat                                    always first; formatting noise otherwise
    ./gradlew verifyDebug -Pshelfplayer.warningsAsErrors=true  the gate CI runs — 5-8 min cold
    ./gradlew :app:assembleDebug                              the APK
    ./gradlew :core:datastore:connectedDebugAndroidTest        needs a device; the Keystore tests
    ./gradlew :app:sbom && ./scripts/vulnerability-scan.sh     supply chain

  docs/handover.md, "Running this locally", has the rest.
NEXT
exit 0
