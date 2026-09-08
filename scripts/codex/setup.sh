#!/usr/bin/env bash
set -euo pipefail

# BookWave Codex Cloud bootstrap.
#
# The Codex environment should select JDK 21 with CODEX_ENV_JAVA_VERSION=21. A compatibility matrix on
# 2026-09-08 proved that the complete BookWave gate passes on JDK 21 while JDK 22, 23 and 24 prepare
# successfully but fail verifyDebug with the current Gradle/AGP/Kotlin toolchain. BookWave still targets
# Java 17 bytecode and normal GitHub CI intentionally remains on JDK 17 to exercise the minimum runtime.
#
# This script installs the newest pinned Android command-line tools, the exact Android SDK packages
# BookWave requires, gitleaks, and then pre-warms the real verification graph. The pre-warm is
# deliberately non-fatal: a Codex task may start from a branch whose broken build is the thing being
# repaired.

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

CODEX_JAVA_BASELINE=21
ANDROID_CMDLINE_TOOLS_BUILD=15859902
ANDROID_CMDLINE_TOOLS_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
GITLEAKS_VERSION=8.30.1

log() {
    printf '\n==> %s\n' "$1"
}

fail() {
    printf 'ERROR: %s\n' "$1" >&2
    exit 1
}

# ---------------------------------------------------------------------------
# Java
# ---------------------------------------------------------------------------

log "Java"

command -v java >/dev/null 2>&1 || fail \
    "Java is missing. Configure CODEX_ENV_JAVA_VERSION=${CODEX_JAVA_BASELINE} in the Codex environment."

JAVA_MAJOR="$(
    java -version 2>&1 |
        sed -n 's/.*version "\([0-9]*\).*/\1/p' |
        head -1
)"

[[ -n "$JAVA_MAJOR" ]] || fail "Could not determine the Java major version."
(( JAVA_MAJOR >= 17 )) || fail "BookWave requires JDK 17 or newer; found JDK ${JAVA_MAJOR}."
(( JAVA_MAJOR <= CODEX_JAVA_BASELINE )) || fail \
    "BookWave verifyDebug is currently proven only through JDK ${CODEX_JAVA_BASELINE}; found JDK ${JAVA_MAJOR}. Use JDK ${CODEX_JAVA_BASELINE} until the toolchain upgrade plan re-probes newer runtimes."

java -version

if (( JAVA_MAJOR != CODEX_JAVA_BASELINE )); then
    printf 'NOTE: JDK %s can prepare the environment, but the Codex baseline is JDK %s (newest fully verified).\n' \
        "$JAVA_MAJOR" "$CODEX_JAVA_BASELINE"
fi

# ---------------------------------------------------------------------------
# Android SDK
# ---------------------------------------------------------------------------

log "Android SDK"

# Reuse an existing writable SDK when the base image already has one. Otherwise keep the Codex SDK in
# the user's home directory so setup never needs sudo and the environment cache can retain it.
if [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}" && -w "${ANDROID_HOME}" ]]; then
    SDK_ROOT="$ANDROID_HOME"
else
    SDK_ROOT="${BOOKWAVE_ANDROID_SDK_ROOT:-$HOME/.bookwave/android-sdk}"
fi

export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export PATH="$SDK_ROOT/cmdline-tools/latest/bin:$SDK_ROOT/platform-tools:$HOME/.local/bin:$PATH"

mkdir -p "$SDK_ROOT/cmdline-tools"

CMDLINE_DIR="$SDK_ROOT/cmdline-tools/latest"
CMDLINE_MARKER="$CMDLINE_DIR/.bookwave-commandline-tools-build"
CMDLINE_INSTALLED=""
[[ -f "$CMDLINE_MARKER" ]] && CMDLINE_INSTALLED="$(cat "$CMDLINE_MARKER")"

if [[ "$CMDLINE_INSTALLED" != "$ANDROID_CMDLINE_TOOLS_BUILD" ]]; then
    printf 'Installing Android command-line tools build %s...\n' "$ANDROID_CMDLINE_TOOLS_BUILD"

    TMP="$(mktemp -d)"
    trap 'rm -rf "$TMP"' EXIT
    ZIP="$TMP/commandlinetools.zip"

    curl --fail --location --silent --show-error \
        "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_BUILD}_latest.zip" \
        --output "$ZIP"

    printf '%s  %s\n' "$ANDROID_CMDLINE_TOOLS_SHA256" "$ZIP" | sha256sum --check -

    unzip -q "$ZIP" -d "$TMP"
    rm -rf "$CMDLINE_DIR"
    mv "$TMP/cmdline-tools" "$CMDLINE_DIR"
    printf '%s\n' "$ANDROID_CMDLINE_TOOLS_BUILD" > "$CMDLINE_MARKER"

    rm -rf "$TMP"
    trap - EXIT
fi

SDKMANAGER="$CMDLINE_DIR/bin/sdkmanager"
[[ -x "$SDKMANAGER" ]] || fail "sdkmanager was not installed correctly."

"$SDKMANAGER" --version

# sdkmanager may terminate `yes` with SIGPIPE after the final licence prompt; that is harmless.
yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses >/dev/null 2>&1 || true

# These are the versions BookWave itself pins. Installing newer platforms/build-tools as decoration
# would consume cache and network without changing the build.
"$SDKMANAGER" \
    --sdk_root="$SDK_ROOT" \
    "platform-tools" \
    "platforms;android-36" \
    "build-tools;36.0.0"

printf 'sdk.dir=%s\n' "$SDK_ROOT" > local.properties

# ---------------------------------------------------------------------------
# Stable debug signing
# ---------------------------------------------------------------------------

log "Debug signing"

mkdir -p "$HOME/.bookwave"

if [[ -n "${BOOKWAVE_DEBUG_KEYSTORE_BASE64:-}" ]]; then
    printf '%s' "$BOOKWAVE_DEBUG_KEYSTORE_BASE64" |
        base64 --decode > "$HOME/.bookwave/debug.keystore"
    chmod 600 "$HOME/.bookwave/debug.keystore"
    printf 'Restored the stable BookWave debug keystore.\n'
elif [[ -f "$HOME/.bookwave/debug.keystore" ]]; then
    printf 'Stable BookWave debug keystore is already present.\n'
else
    printf '%s\n' \
        'BOOKWAVE_DEBUG_KEYSTORE_BASE64 is not configured; BookWave may generate a different debug key.' \
        'That is fine for verification, but a downloaded APK may not upgrade an existing device install.'
fi

# ---------------------------------------------------------------------------
# gitleaks
# ---------------------------------------------------------------------------

log "gitleaks"

GITLEAKS_ARCH=""
GITLEAKS_SHA256=""
case "$(uname -m)" in
    x86_64|amd64)
        GITLEAKS_ARCH=x64
        GITLEAKS_SHA256=551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb
        ;;
    aarch64|arm64)
        GITLEAKS_ARCH=arm64
        GITLEAKS_SHA256=e4a487ee7ccd7d3a7f7ec08657610aa3606637dab924210b3aee62570fb4b080
        ;;
    *)
        fail "Unsupported architecture for the pinned gitleaks binary: $(uname -m)"
        ;;
esac

GITLEAKS_BIN="$HOME/.local/bin/gitleaks"
GITLEAKS_CURRENT=""
if [[ -x "$GITLEAKS_BIN" ]]; then
    GITLEAKS_CURRENT="$($GITLEAKS_BIN version 2>/dev/null | sed -n 's/.*\([0-9]\+\.[0-9]\+\.[0-9]\+\).*/\1/p' | head -1)"
elif command -v gitleaks >/dev/null 2>&1; then
    GITLEAKS_CURRENT="$(gitleaks version 2>/dev/null | sed -n 's/.*\([0-9]\+\.[0-9]\+\.[0-9]\+\).*/\1/p' | head -1)"
fi

if [[ "$GITLEAKS_CURRENT" != "$GITLEAKS_VERSION" ]]; then
    mkdir -p "$HOME/.local/bin"
    TMP="$(mktemp -d)"
    trap 'rm -rf "$TMP"' EXIT
    ARCHIVE="$TMP/gitleaks.tar.gz"

    curl --fail --location --silent --show-error \
        "https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}/gitleaks_${GITLEAKS_VERSION}_linux_${GITLEAKS_ARCH}.tar.gz" \
        --output "$ARCHIVE"

    printf '%s  %s\n' "$GITLEAKS_SHA256" "$ARCHIVE" | sha256sum --check -
    tar -xzf "$ARCHIVE" -C "$TMP" gitleaks
    install -m 0755 "$TMP/gitleaks" "$GITLEAKS_BIN"

    rm -rf "$TMP"
    trap - EXIT
fi

"$GITLEAKS_BIN" version

# ---------------------------------------------------------------------------
# Repository environment check and Gradle cache warm-up
# ---------------------------------------------------------------------------

log "BookWave environment check"

chmod +x ./gradlew
find scripts -type f -name '*.sh' -exec chmod +x {} +

./scripts/check-local-environment.sh

log "Gradle wrapper"
./gradlew --version

PREWARM="${BOOKWAVE_CODEX_PREWARM:-full}"

if [[ "$PREWARM" == "full" ]]; then
    log "Pre-warming BookWave verification graph"

    set +e
    ./gradlew ktlintCheck
    KTLINT_STATUS=$?

    ./gradlew verifyDebug --continue -Pshelfplayer.warningsAsErrors=true
    VERIFY_STATUS=$?
    set -e

    # A Room/KSP task can export a schema. Environment preparation must not hand the agent source-tree
    # changes that did not exist when the task started.
    git restore --worktree -- core/database/schemas 2>/dev/null || true
    git clean -fd -- core/database/schemas >/dev/null 2>&1 || true

    if (( KTLINT_STATUS != 0 || VERIFY_STATUS != 0 )); then
        printf '%s\n' \
            'WARNING: the branch did not pass the complete pre-warm.' \
            'The environment is still usable; the failing build may be exactly what Codex was asked to repair.'
    fi
else
    log "Light Gradle pre-warm"
    ./gradlew help >/dev/null
fi

log "BookWave Codex environment ready"
printf '%s\n' \
    "Java: ${JAVA_MAJOR}" \
    "Android SDK: ${ANDROID_SDK_ROOT}" \
    "Android command-line tools build: ${ANDROID_CMDLINE_TOOLS_BUILD}" \
    "gitleaks: ${GITLEAKS_VERSION}" \
    "Pre-warm: ${PREWARM}"
