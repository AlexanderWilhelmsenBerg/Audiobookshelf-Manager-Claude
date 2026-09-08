# Latest-stable toolchain and dependency upgrade plan

Status: planned; not an implementation checklist for PR #131 itself.

Snapshot date: 2026-09-08.

## Goal

Move BookWave from its current pinned stack to the latest stable release of every build tool, Android
platform dependency, application library, test library, CI action and Codex-side tool without turning the
migration into one unreviewable dependency bomb.

The destination is **latest stable at the time each phase starts**, not merely the version written in this
document. Before every phase, resolve versions again from the authoritative release source and exclude
alpha, beta, RC, milestone, preview, EAP, dev and snapshot builds unless a separate PR explicitly opts into
a preview.

`gradle/libs.versions.toml` remains the application dependency source of truth. Dynamic versions and `+`
remain forbidden.

## Baseline established by PR #131

- Gradle wrapper: 8.14.3.
- Android Gradle Plugin: 8.12.0.
- Kotlin: 2.2.0.
- KSP: 2.3.11.
- compileSdk / targetSdk: 36 / 36.
- Codex runtime: JDK 21.
- Android command-line tools: build 15859902.
- Android Build Tools: 36.0.0.
- gitleaks: 8.30.1.

The Codex compatibility probe on 2026-09-08 is important evidence: the environment bootstrap succeeds on
JDK 21, 22, 23 and 24, but the complete `verifyDebug` gate succeeds only on JDK 21. Therefore JDK 21 is the
newest **fully verified** Codex runtime for the current stack. A newer JDK must be re-probed after the build
toolchain migration rather than selected from Gradle's Java-compatibility table alone.

## Latest-stable snapshot already verified while writing this plan

These are evidence for planning, not permanent pins:

| Component | Current | Latest stable verified 2026-09-08 | Action |
| --- | ---: | ---: | --- |
| Gradle | 8.14.3 | 9.7.1 | Phase 1 |
| Kotlin | 2.2.0 | 2.4.20 | Phase 2 |
| KSP | 2.3.11 | 2.3.11 | Already current; re-check with Kotlin upgrade |
| detekt | 1.23.8 | 1.23.8 | Already current |
| Kover | 0.9.9 | 0.9.9 | Already current |
| ktlint Gradle plugin | 12.3.0 | 14.2.0 | Phase 2 |
| ktlint | 1.5.0 | 1.8.0 | Phase 2 |
| gitleaks | 8.30.1 | 8.30.1 | Already current |

The exact latest stable AGP, AndroidX, Compose, Hilt, Media3 and Maven-library versions must be resolved
again from Google's Android release pages / Google Maven and Maven Central at the start of their phase.
Those ecosystems move independently and should not be frozen here merely to make the roadmap look more
precise.

## Migration rules

1. One compatibility axis per PR where practical.
2. Every phase starts from freshly updated `main` after the previous phase merges.
3. No preview dependencies in a latest-stable migration.
4. Never suppress a warning, lint rule, compiler error or test merely to make an upgrade green without
   understanding the behavior change.
5. A dependency upgrade that changes runtime semantics gets regression tests before or with the upgrade.
6. Room schema files are immutable once committed; migrations are explicit.
7. Networking upgrades must keep captured Audiobookshelf contracts green.
8. Playback/Media3 upgrades require device and Android Auto testing, not only JVM tests.
9. UI/Compose upgrades require device smoke testing for navigation, insets, theming and accessibility.
10. After any classpath-changing PR, run the verification gate with `--rerun-tasks` before merge.
11. Do not combine application feature work with these upgrade PRs unless the upgrade itself requires the
    compatibility change.
12. After the migration reaches latest stable, add automation that keeps it there with small grouped PRs.

## Phase 0 — inventory and reproducible version discovery

Purpose: prove what is actually out of date before changing anything.

- Inventory every version key in `gradle/libs.versions.toml`.
- Inventory `gradle-wrapper.properties`.
- Inventory all `uses:` entries under `.github/workflows/`.
- Inventory versions pinned in `scripts/codex/`, release scripts and other shell/PowerShell tooling.
- Resolve stable releases from authoritative sources and record the date/source in the implementation PR.
- Reject prereleases automatically when generating the report.
- Run the existing dependency/licence report and SBOM before the first change so later diffs are explainable.
- Record known migration notes for every major-version jump before editing the catalog.

Exit criteria:

- one current-to-target inventory exists;
- every versioned component belongs to a later phase;
- normal CI is green on the untouched baseline.

## Phase 1 — Gradle and Android build foundation

Upgrade the build foundation before libraries. Compiler and Android plugin upgrades should not be debugged
on top of an old wrapper.

1. Upgrade Gradle 8.14.3 to the latest stable Gradle (9.7.1 at this snapshot).
2. Run wrapper validation and inspect the Gradle 9 upgrade warnings.
3. Fix deprecated Gradle APIs in `build-logic` rather than enabling compatibility flags indefinitely.
4. Upgrade Android Gradle Plugin 8.12.0 to the latest stable AGP available when this phase starts.
5. Apply AGP migration changes separately from Kotlin changes where possible.
6. Re-run Android Lint and inspect changes in severity/default rule sets.
7. Validate signing configuration, packaging, generated BuildConfig/resources, Room/KSP task wiring and APK
   identity.
8. Do not raise compileSdk/targetSdk yet unless the selected AGP requires it; platform behavior belongs in
   Phase 3.

Required verification:

```bash
./gradlew ktlintCheck --rerun-tasks
./gradlew verifyDebug --continue --rerun-tasks -Pshelfplayer.warningsAsErrors=true
./gradlew :app:assembleDebug
```

Then run the Codex JDK probe again. Test every currently relevant stable JDK from 21 through the newest JDK
supported by the upgraded Gradle/AGP combination. Select the **highest JDK that passes the whole BookWave
gate**, not the highest one that can launch Gradle.

## Phase 2 — Kotlin, KSP and code-quality plugins

Upgrade together only where compiler compatibility requires it:

- Kotlin 2.2.0 -> latest stable (2.4.20 at this snapshot).
- KSP 2.3.11 -> latest stable compatible KSP; it is already current at this snapshot.
- ktlint Gradle plugin 12.3.0 -> latest stable (14.2.0 at this snapshot).
- ktlint 1.5.0 -> latest stable (1.8.0 at this snapshot).
- detekt 1.23.8 -> latest stable; currently already current.
- Kover 0.9.9 -> latest stable; currently already current.
- protobuf Gradle plugin 0.9.5 -> latest stable.

Check compiler opt-ins, Compose compiler configuration, Kotlin language/API levels, KSP generated sources,
detekt baselines, formatting changes and coverage thresholds. Formatting-rule changes should be isolated in
a mechanical commit where possible so semantic review remains readable.

Exit criteria: full verification green with `--rerun-tasks`, no unexplained generated-source changes, and no
coverage threshold silently reduced.

## Phase 3 — Android platform, Compose and general AndroidX

Resolve the latest stable Android SDK and AndroidX releases at execution time.

- compileSdk 36 -> latest stable SDK supported by stable AGP.
- targetSdk 36 -> latest stable target SDK in a dedicated behavioral review.
- minSdk stays 26 unless a product decision explicitly changes device support.
- Compose BOM 2025.06.01 -> latest stable Compose BOM.
- `androidxActivity` 1.12.4 -> latest stable.
- `androidxAnnotation` 1.10.0 -> latest stable.
- `androidxCore` 1.17.0 -> latest stable.
- `androidxLifecycle` 2.10.0 -> latest stable.
- `androidxNavigation` 2.9.8 -> latest stable.
- `androidxHiltNavigationCompose` 1.3.0 -> latest stable.
- `hiltExt` / AndroidX Hilt 1.3.0 -> latest stable.

Treat targetSdk as an Android behavior migration, not a number bump. Review foreground-service,
notifications, media playback, storage/file access, background work, edge-to-edge/insets and Android Auto
behavior changes for every crossed API level.

Device smoke tests must cover authentication, library navigation, playback, downloads, background/resume
behavior, notifications and process recreation.

## Phase 4 — persistence, background work and playback

Upgrade these in separate PRs because each owns user state or long-running behavior:

- DataStore 1.1.7 -> latest stable.
- Room 2.7.2 -> latest stable.
- WorkManager 2.11.2 -> latest stable.
- Media3 1.11.0 -> latest stable.
- Dagger/Hilt 2.58 -> latest stable.

Room requirements:

- preserve every committed schema;
- export and review the new schema;
- add migration tests for any database format change;
- test upgrade from a realistic existing BookWave database.

Media3 requirements:

- regression-test play/pause/seek/progress sync;
- test audio focus and route changes;
- test notification/media session controls;
- test Bluetooth/headset behavior;
- test Android Auto with DHU and a real car when available;
- verify resume after process death and reconnect.

WorkManager/DataStore requirements:

- background retries and constraints remain correct;
- no ownership/profile data crosses accounts;
- stored grants/settings survive upgrade unchanged unless intentionally migrated.

## Phase 5 — Kotlin runtime, serialization and network stack

Upgrade deliberately because several likely jumps cross major versions:

- kotlinx-coroutines 1.10.2 -> latest stable.
- kotlinx-serialization 1.8.1 -> latest stable.
- OkHttp 4.12.0 -> latest stable.
- Retrofit 2.11.0 -> latest stable.
- retrofit2-kotlinx-serialization-converter 1.0.0 -> latest stable or replace it if the modern Retrofit stack
  has a better maintained first-party path.
- protobuf 4.31.1 -> latest stable.
- javax.inject 1 -> latest stable / retain if still canonical for Hilt compatibility.

For each networking major upgrade:

- keep all captured Audiobookshelf contract fixtures green;
- verify unknown JSON fields remain tolerated;
- verify required-field failures remain typed compatibility errors;
- verify TLS validation remains strict;
- verify WebSocket/realtime lifecycle behavior;
- run download/resume tests and progress/session sync tests.

Do not change API behavior merely to satisfy a new converter without a contract test proving the server
shape.

## Phase 6 — images and visual effects

- Coil 2.7.0 -> latest stable; treat a move to a newer major as a migration, especially cache/request APIs.
- Haze 1.6.10 -> latest stable.

Test cover loading, offline cached covers, scrolling performance, memory behavior, placeholders/errors,
theme changes and Android Auto artwork. Do not accept a visual upgrade that regresses offline behavior.

## Phase 7 — Android test and JVM test stack

Upgrade:

- AndroidX Test Core 1.7.0 -> latest stable.
- AndroidX Test Ext JUnit 1.3.0 -> latest stable.
- AndroidX Test Runner 1.7.0 -> latest stable.
- AndroidX Benchmark 1.3.4 -> latest stable.
- UI Automator 2.4.0 -> latest stable.
- Robolectric 4.15.1 -> latest stable.
- Turbine 1.2.1 -> latest stable.
- JUnit 4.13.2 -> latest stable JUnit 4 if one exists; a JUnit 5/6 migration is a separate architecture
  decision, not a disguised dependency bump.

Run JVM, Robolectric and connected tests. Regenerate/re-validate macrobenchmark or baseline-profile assets
only if the tool upgrade requires it; never overwrite them as incidental noise.

## Phase 8 — CI, Codex, SDK and security tooling

- Android command-line tools build 15859902 -> latest stable command-line-tools package.
- Android Build Tools 36.0.0 -> latest stable required by the selected Android platform/AGP.
- platform-tools -> latest stable through sdkmanager.
- gitleaks 8.30.1 -> latest stable; it is already current at this snapshot.
- all GitHub Actions under `.github/workflows/` -> latest stable supported major/release.
- re-check `actions/checkout`, `actions/setup-java`, `actions/upload-artifact`, Gradle Actions and wrapper
  validation usage and release notes.
- update pinned checksums whenever a downloaded binary changes.
- keep release/upload signing secrets out of ordinary Codex environments.
- keep `BOOKWAVE_DEBUG_KEYSTORE_BASE64` as the only optional Codex signing secret.

After the upgraded build stack is green, re-run the modern-JDK compatibility matrix and update
`CODEX_ENV_JAVA_VERSION` to the highest fully passing stable JDK.

## Phase 9 — automate staying current

Once the repository is at latest stable, add dependency automation so this does not become a yearly
archaeological expedition.

Recommended policy:

- enable Renovate or equivalent for Gradle version catalogs, wrapper, GitHub Actions and pinned tool
  versions;
- stable releases only by default;
- patch/minor updates can be grouped by ecosystem where tests provide confidence;
- major updates get individual PRs;
- Kotlin + compiler/KSP compatibility updates may be grouped intentionally;
- AndroidX/Compose groups should remain small enough to diagnose regressions;
- never auto-merge major updates;
- run the normal gate plus the Codex compatibility canary for build-tool changes.

A monthly dependency-health issue/report is enough; there is no value in notification confetti for every
transitive patch.

## Complete version-catalog inventory

Every current version key is assigned below so nothing silently falls outside the roadmap.

| Version key | Current | Phase / treatment |
| --- | ---: | --- |
| androidGradlePlugin | 8.12.0 | Phase 1 |
| kotlin | 2.2.0 | Phase 2 |
| ksp | 2.3.11 | Phase 2; already latest in snapshot |
| detekt | 1.23.8 | Phase 2; already latest in snapshot |
| kover | 0.9.9 | Phase 2; already latest in snapshot |
| ktlintGradle | 12.3.0 | Phase 2 |
| ktlint | 1.5.0 | Phase 2 |
| protobufPlugin | 0.9.5 | Phase 2 |
| compileSdk | 36 | Phase 3 |
| minSdk | 26 | Preserve unless product decision changes |
| targetSdk | 36 | Phase 3 behavioral migration |
| androidxActivity | 1.12.4 | Phase 3 |
| androidxAnnotation | 1.10.0 | Phase 3 |
| androidxCore | 1.17.0 | Phase 3 |
| androidxDatastore | 1.1.7 | Phase 4 |
| androidxHiltNavigationCompose | 1.3.0 | Phase 3 |
| androidxLifecycle | 2.10.0 | Phase 3 |
| androidxNavigation | 2.9.8 | Phase 3 |
| androidxRoom | 2.7.2 | Phase 4 |
| androidxTestCore | 1.7.0 | Phase 7 |
| androidxTestExt | 1.3.0 | Phase 7 |
| androidxTestRunner | 1.7.0 | Phase 7 |
| androidxBenchmark | 1.3.4 | Phase 7 |
| androidxUiAutomator | 2.4.0 | Phase 7 |
| androidxWork | 2.11.2 | Phase 4 |
| composeBom | 2025.06.01 | Phase 3 |
| media3 | 1.11.0 | Phase 4 |
| hilt | 2.58 | Phase 4 |
| hiltExt | 1.3.0 | Phase 3/4 |
| javaxInject | 1 | Phase 5 compatibility review |
| kotlinxCoroutines | 1.10.2 | Phase 5 |
| kotlinxSerialization | 1.8.1 | Phase 5 |
| okhttp | 4.12.0 | Phase 5 |
| protobuf | 4.31.1 | Phase 5 |
| retrofit | 2.11.0 | Phase 5 |
| retrofitKotlinxSerialization | 1.0.0 | Phase 5 |
| coil | 2.7.0 | Phase 6 |
| haze | 1.6.10 | Phase 6 |
| junit4 | 4.13.2 | Phase 7 |
| robolectric | 4.15.1 | Phase 7 |
| turbine | 1.2.1 | Phase 7 |

## Per-PR merge gate

Every upgrade PR must state:

- old version(s);
- new stable version(s) and authoritative source/date;
- migration notes reviewed;
- source changes required and why;
- tests added or changed;
- exact verification run;
- device/manual tests required before merge;
- known follow-ups deliberately excluded.

Minimum automated gate after any dependency or build-tool change:

```bash
./gradlew ktlintCheck --rerun-tasks
./gradlew verifyDebug --continue --rerun-tasks -Pshelfplayer.warningsAsErrors=true
```

For changes affecting APK/runtime behavior also assemble and device-test. For Room, Media3, WorkManager,
DataStore, targetSdk and Android Auto changes, the relevant manual/instrumented test is mandatory before
merge.

## Desired end state

The migration is complete when:

- every catalog key is on the latest stable compatible release or has a documented reason not to be;
- Gradle, AGP, Kotlin and KSP are on mutually supported current stable releases;
- compileSdk/targetSdk are current stable Android levels with behavior changes reviewed;
- GitHub Actions and Codex-side tools are current stable;
- the highest fully verified modern JDK is the Codex baseline;
- `verifyDebug --rerun-tasks` is green;
- Room schema/migration tests are green;
- connected tests are green;
- Android Auto/playback/download/offline smoke tests pass;
- dependency automation is enabled to keep the repository near-current continuously.
