#!/usr/bin/env bash
# PRODUCT_SPEC 16.1 — regenerate the dependency lock state.
#
# Run this after any change to gradle/libs.versions.toml or to a module's dependencies, then commit
# the resulting `gradle.lockfile` files. Locking is enabled for every project by the
# `shelfplayer.quality` convention plugin; without lock state present, Gradle resolves normally, so a
# missing lockfile weakens the guarantee silently rather than failing loudly. Reviewing the diff is
# the point of the exercise.
set -euo pipefail

cd "$(dirname "$0")/.."

./gradlew resolveAndLockAll --write-locks "$@"

echo
echo "Lock state written. Review the diff before committing:"
echo "  git status --short -- '**/gradle.lockfile'"
