# AGENTS.md — Codex working agreement

Read `PRODUCT_SPEC.md` before making changes.

## Mission

Build BookWave as a native, offline-first Android client for Audiobookshelf. (The Kotlin packages and
Gradle namespaces are still `com.example.shelfplayer`, deliberately — ADR-0024 moved only the
`applicationId`, which is the part Play sees.) Protect playback continuity, progress accuracy, user privacy, and permission boundaries above implementation speed.

## Required stack

- Kotlin
- Jetpack Compose Material 3
- Media3 ExoPlayer and MediaLibraryService
- Coroutines and Flow
- Room
- Proto DataStore
- WorkManager
- Hilt
- Retrofit, OkHttp, Kotlinx Serialization
- Gradle Wrapper and Kotlin DSL
- ktlint, detekt, Android Lint

## Commands

```bash
./gradlew ktlintFormat
./gradlew verifyDebug -Pshelfplayer.warningsAsErrors=true
```

Never mark work complete when `verifyDebug` fails.

Add `--rerun-tasks` before believing a green result on a branch that changed a classpath (`docs/risks.md`
R-31).

On a machine with a device attached:

```bash
./scripts/check-local-environment.sh                  # what is missing, and what to do about each
./gradlew :core:datastore:connectedDebugAndroidTest   # the instrumented tier; never runs in CI
```

## Codex Cloud environment

The repository owns the Codex bootstrap in `scripts/codex/`; do not spend an agent turn rebuilding the
environment by hand.

Configure the Codex Cloud environment with:

```text
CODEX_ENV_JAVA_VERSION=21
Setup:       bash scripts/codex/setup.sh
Maintenance: bash scripts/codex/maintenance.sh
```

JDK 21 is deliberate. A compatibility matrix run on 2026-09-08 proved that the complete BookWave gate
passes on JDK 21. JDK 22, 23 and 24 all prepared the same Codex environment successfully but failed
`verifyDebug` with the current Gradle 8.14.3 / AGP 8.12.0 / Kotlin 2.2.0 stack. BookWave still targets Java
17 bytecode, while normal GitHub CI stays on JDK 17 to exercise the minimum supported runtime. Re-probe
newer JDKs after the staged build-tool migration in `docs/latest-stable-upgrade-plan.md`; do not infer
compatibility merely because Gradle itself starts.

The bootstrap pins the current stable Android command-line tools, installs only the Android SDK packages
this repository actually needs (`platforms;android-36`, `build-tools;36.0.0`, and `platform-tools`), and
pre-warms the Gradle verification graph. It also installs the pinned gitleaks version used for agent-side
supply-chain work. Use the repository Gradle wrapper; never install or invoke a separate Gradle version.

`BOOKWAVE_DEBUG_KEYSTORE_BASE64` must be configured as a **Codex secret**, not as an ordinary environment
variable. The secret is exposed to the setup process under that environment-variable name, and the
bootstrap restores it to `~/.bookwave/debug.keystore`. It is optional for compilation and tests, but
without it an APK produced in a fresh Codex environment may not upgrade the developer's existing install.
The GitHub Actions repository secret with the same name is separate; Codex does not inherit GitHub Actions
secrets automatically. Never request or place release or Play upload signing credentials in the ordinary
Codex environment.

Cloud verification does not replace hardware testing. `connectedDebugAndroidTest` and macrobenchmarks
still require a device or emulator; do not claim those tiers ran merely because `verifyDebug` passed.

If the Codex bootstrap or its compatibility-canary workflow fails after a toolchain change, fix or update
the bootstrap deliberately. Do not work around it by silently downgrading the application toolchain or
weakening `verifyDebug`.

## Dependency and toolchain upgrades

Follow `docs/latest-stable-upgrade-plan.md` rather than bulk-bumping the version catalog. Build-system,
Kotlin/compiler, AndroidX/platform, networking, persistence/playback, UI and test-tool upgrades are kept in
separate reviewable phases. Resolve "latest stable" again immediately before each phase because the plan is
a sequence and policy, not permission to use stale version numbers or previews.

## Coding rules

- Work from requirement IDs in `PRODUCT_SPEC.md`.
- Keep UI, domain, data, and playback responsibilities separate.
- UI reads Room-backed repository state.
- Never call server APIs directly from Compose or a ViewModel.
- Use typed `AppResult` and `AppError`.
- Rethrow coroutine cancellation.
- No `GlobalScope`.
- Inject dispatchers and clock.
- Do not use destructive Room migrations.
- Unknown JSON fields are tolerated; missing required fields become compatibility errors.
- Do not invent Audiobookshelf endpoints or response fields.
- Add contract fixtures/tests for every endpoint.
- Never log tokens, passwords, cookies, server hosts, usernames, filenames, media titles, or descriptions by default.
- Never disable TLS verification.
- Never use a database-only item removal endpoint as if it deleted source files.
- Do not copy code from the official Audiobookshelf app without license review.
- Preserve playback during UI refreshes and management actions.
- After building a component, grep for its callers. Five of the eight defects in the last feature were
  correct code that nothing reached, and no unit test detects an absent caller (`docs/risks.md` R-43).
- When a test guards a fix, revert the fix and watch it fail before trusting it.
- Treat prose as a deliverable of the change that invalidates it. Documentation drift has been this
  project's most frequent defect (R-32).

## Change workflow

1. Identify requirement IDs.
2. Inspect current architecture and tests.
3. Write or update tests/fixtures first for policy and API changes.
4. Implement the smallest vertical slice.
5. Run formatter.
6. Run `verifyDebug`.
7. Update docs and compatibility matrix.
8. Summarize assumptions, tests, and unresolved risks.

## Definition of Done

Use section 21 of `PRODUCT_SPEC.md`. A feature is not done with only a happy-path screen.
