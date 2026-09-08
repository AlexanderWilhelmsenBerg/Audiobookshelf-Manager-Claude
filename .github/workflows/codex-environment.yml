name: Codex environment compatibility

# Proves that the Codex Cloud bootstrap still works on BookWave's newest fully verified JDK runtime.
# Gradle's Java compatibility table only proves that Gradle itself starts; AGP, lint, KSP, Robolectric
# and the rest of verifyDebug must also work before a JDK is a safe Codex baseline.
#
# A diagnostic matrix on 2026-09-08 proved JDK 21 passes the complete gate while JDK 22, 23 and 24 all
# prepare successfully but fail verifyDebug. Re-probe newer JDKs after the Gradle/AGP/Kotlin migration
# described in docs/latest-stable-upgrade-plan.md.
#
# Keep this targeted to toolchain/bootstrap changes so ordinary feature PRs do not pay for a second full
# verification run.

on:
  pull_request:
    branches: [main]
    paths:
      - 'scripts/codex/**'
      - 'gradle/wrapper/**'
      - 'gradle/libs.versions.toml'
      - 'gradle.properties'
      - 'build.gradle.kts'
      - 'settings.gradle.kts'
      - 'build-logic/**'
      - 'docs/latest-stable-upgrade-plan.md'
      - '.github/workflows/codex-environment.yml'
  workflow_dispatch:

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

permissions:
  contents: read

env:
  GRADLE_OPTS: -Dorg.gradle.jvmargs=-Xmx5g -Dorg.gradle.daemon=false

jobs:
  codex-bootstrap:
    name: JDK 21 Codex bootstrap + verifyDebug
    runs-on: ubuntu-latest
    timeout-minutes: 60

    steps:
      - uses: actions/checkout@v7

      - name: Set up verified Codex JDK
        uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: '21'

      - name: Set up Gradle cache
        uses: gradle/actions/setup-gradle@v6
        with:
          cache-provider: basic
          cache-read-only: true

      # The workflow asks the bootstrap for its cheap maintenance path, then runs the real gate itself.
      # This distinguishes "environment could not be prepared" from "BookWave failed verification" and
      # avoids running verifyDebug twice.
      - name: Prepare Codex environment
        env:
          BOOKWAVE_CODEX_PREWARM: light
        run: bash scripts/codex/setup.sh

      - name: Show prepared tool versions
        run: |
          java -version
          "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --version
          "$HOME/.local/bin/gitleaks" version
          ./gradlew --version

      - name: Verify BookWave on JDK 21
        run: ./gradlew verifyDebug --continue -Pshelfplayer.warningsAsErrors=true

      - name: Room schema is committed and current
        if: always()
        run: |
          changes="$(git status --porcelain -- core/database/schemas)"
          if [ -n "$changes" ]; then
            echo "::error::Codex verification changed committed Room schemas."
            echo "$changes"
            exit 1
          fi
