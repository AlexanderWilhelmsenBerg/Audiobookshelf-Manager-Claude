#!/usr/bin/env bash
# PRODUCT_SPEC 16.1 — regenerate the dependency lock state.
#
# Run this after any change to gradle/libs.versions.toml or to a module's dependencies, then commit the
# resulting `gradle.lockfile` files. Reviewing the diff is the point of the exercise: it is the list of
# artifacts this build will accept, and a surprise in it is the thing locking exists to surface.
#
# ## Why this runs a real build rather than `resolveAndLockAll`
#
# `resolveAndLockAll` walks every configuration it can resolve standing alone, which is most of them and
# not all of them. Around seventy — AGP's per-variant classpaths, KSP's processor classpaths, the unit
# test runtime classpaths — only exist inside a task's execution context. They resolve happily during a
# build and not at all outside one, so a lock state generated from `resolveAndLockAll` is silently
# missing exactly the configurations a build then goes on to resolve, and the next ordinary `verifyDebug`
# fails with "resolved X which is not part of the dependency lock state".
#
# Running `verifyDebug` itself with `--write-locks` resolves what the build actually resolves, which is
# the only definition of "everything" that matters. `--rerun-tasks` is what makes that true on a machine
# with a warm build directory: an up-to-date task does not resolve its inputs, and a configuration that
# is not resolved is not written.
#
# It is slow — ten minutes or so from cold — and it is meant to be run rarely.
set -euo pipefail

cd "$(dirname "$0")/.."

./gradlew verifyDebug --write-locks --rerun-tasks "$@"

cat <<'EOF'

Lock state written. Two things before committing:

  1. Review the diff. Every line is an artifact this build will accept.
       git status --short -- '**/gradle.lockfile'
       git diff -- '**/gradle.lockfile'

  2. Confirm it holds on an ordinary build, with no write flags:
       ./gradlew --stop && ./gradlew verifyDebug

Step 2 is not optional. `--write-locks` cannot fail a lock check — it overwrites it — so a build that
passes with the flag says nothing about whether the state it wrote is complete.
EOF
