# Latest-stable toolchain and dependency upgrade plan

Status: active staged migration under issue #135.

Roadmap last reconciled: 2026-09-15.

Live current/latest version state: [`/version-control.md`](../version-control.md).

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

## Current migration progress

PR #131 established the original upgrade foundation. The numerical current/latest state now lives only in
[`/version-control.md`](../version-control.md) so this roadmap cannot silently drift from `main`.

Completed staged slices under #135:

| PR | Component | From | To | Merged |
| --- | --- | ---: | ---: | --- |
| #154 | AndroidX DataStore | 1.1.7 | 1.2.1 | 2026-09-14 |
| #155 | KSP | 2.3.11 | 2.3.12 | 2026-09-14 |
| #156 | ktlint Gradle plugin | 12.3.0 | 14.2.0 | 2026-09-15 |
| #157 | ktlint engine | 1.5.0 | 1.8.0 | 2026-09-15 |
| #158 | Protobuf Gradle plugin | 0.9.5 | 0.10.0 | 2026-09-15 |

The Codex compatibility probe from 2026-09-08 remains relevant evidence: the environment bootstrap succeeds
on JDK 21, 22, 23 and 24, but the complete `verifyDebug` gate succeeds only on JDK 21. Therefore JDK 21
remains the newest **fully verified** Codex runtime until the build foundation is migrated and the matrix is
re-run.

## Live latest-stable ledger

[`/version-control.md`](../version-control.md) is the canonical repository-wide ledger for:

- every version key in `gradle/libs.versions.toml`;
- the Gradle wrapper, Android SDK levels/tools and JDK lanes;
- every GitHub Action family used by BookWave;
- Gitleaks and other pinned CI/security tooling;
- the Node/npm runtime used by the APK/Loopbound workflow; and
- the Python runtime plus NumPy/Pillow used by launcher-asset generation.

The ledger records current version, latest stable version, last-checked date, phase, compatibility status and
authoritative source. It must be updated in the same PR as every tracked version change. This document owns
**sequencing, gates and validation requirements**, not a second copy of live version numbers.

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
13. Every tracked version-changing PR must update `/version-control.md` in the same PR, including its
    current version, latest-stable re-check date/status and the merged-history row where applicable.
14. Keep numerical live state out of this roadmap except where a historical version is needed to explain a
    migration. `/version-control.md` is the canonical live version ledger.

## Phase 0 — inventory and reproducible version discovery

**Status: complete; maintained continuously through `/version-control.md`.**

Purpose: prove what is actually out of date before changing anything and keep that evidence current.

- Inventory every version key in `gradle/libs.versions.toml` and keep it represented in `/version-control.md`.
- Inventory `gradle-wrapper.properties`.
- Inventory all `uses:` entries under `.github/workflows/`.
- Inventory versions pinned in `scripts/codex/`, release scripts and other shell/PowerShell tooling, plus repo-owned Node and Python tooling.
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

1. Upgrade the Gradle wrapper to the latest stable version recorded in `/version-control.md`.
2. Run wrapper validation and inspect the Gradle 9 upgrade warnings.
3. Fix deprecated Gradle APIs in `build-logic` rather than enabling compatibility flags indefinitely.
4. Upgrade Android Gradle Plugin to the latest mutually compatible stable AGP recorded in `/version-control.md`.
5. Apply AGP migration changes separately from Kotlin changes where possible.
6. Re-run Android Lint and inspect changes in severity/default rule sets.
7. Validate signing configuration, packaging, generated BuildConfig/resources, Room/KSP task wiring and APK
   identity.
8. Retry dependency locking under ADR-0010 on the new Gradle/AGP foundation; do not weaken the strict
   dependency-verification policy from ADR-0006 to make locking work.
9. Do not raise compileSdk/targetSdk yet unless the selected AGP requires it; platform behavior belongs in
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

**Status: partially complete.** Safe independent slices have already landed for KSP (#155), the ktlint Gradle
plugin (#156), the ktlint engine (#157) and the Protobuf Gradle plugin (#158). Kover and stable detekt are
already current in the live ledger. Kotlin/compiler migration remains pending and must respect ADR-0011.

Re-resolve and upgrade only the still-outdated components shown in `/version-control.md`, grouping them only
where compiler compatibility requires it. In particular:

- Kotlin remains a dedicated compiler migration.
- KSP must be re-checked whenever Kotlin moves even when KSP is already current.
- detekt must remain on a stable release; the AGP 9/API 37 gate is not satisfied by a detekt alpha.
- Kover, ktlint tooling and Protobuf Gradle plugin should not be churned when the ledger already shows them current.

Check compiler opt-ins, Compose compiler configuration, Kotlin language/API levels, KSP generated sources,
detekt baselines, formatting changes and coverage thresholds. Formatting-rule changes should be isolated in
a mechanical commit where possible so semantic review remains readable.

Exit criteria: full verification green with `--rerun-tasks`, no unexplained generated-source changes, and no
coverage threshold silently reduced.

## Phase 3 — Android platform, Compose and general AndroidX

Resolve the latest stable Android SDK, Compose BOM and AndroidX releases from `/version-control.md` at
execution time. This phase owns:

- compileSdk and targetSdk (while minSdk remains a product-support decision);
- Compose BOM;
- Activity, Annotation, Core, Lifecycle and Navigation; and
- AndroidX Hilt / Hilt Navigation Compose.

ADR-0011 currently gates the API 37/AGP 9 move. AndroidX/Compose releases that themselves require API 37 or
AGP 9.2+ stay behind that same gate rather than being forced through independently.

Treat targetSdk as an Android behavior migration, not a number bump. Review foreground-service,
notifications, media playback, storage/file access, background work, edge-to-edge/insets and Android Auto
behavior changes for every crossed API level.

Device smoke tests must cover authentication, library navigation, playback, downloads, background/resume
behavior, notifications and process recreation.

## Phase 4 — persistence, background work and playback

Upgrade these in separate PRs because each owns user state or long-running behavior. DataStore reached the
latest stable release in PR #154; do not manufacture another DataStore change while the ledger shows it
current. Remaining ownership includes Room, WorkManager, Media3 and Dagger/Hilt whenever their rows are
outdated.

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

Upgrade deliberately from the current values to the stable targets recorded in `/version-control.md`. This
phase owns kotlinx-coroutines, kotlinx-serialization, OkHttp, Retrofit, the Kotlin serialization converter,
Protobuf runtime/protoc and `javax.inject` compatibility. Several jumps cross major versions. The archived
Jake Wharton Retrofit serialization converter should be treated as a migration to Retrofit's maintained
first-party path, not as a version bump that does not exist.

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

Upgrade Coil and Haze to the stable targets recorded in `/version-control.md`. Treat the Coil major-version
move as an explicit migration, especially around cache/request APIs; exclude Haze prereleases from the
latest-stable lane.

Test cover loading, offline cached covers, scrolling performance, memory behavior, placeholders/errors,
theme changes and Android Auto artwork. Do not accept a visual upgrade that regresses offline behavior.

## Phase 7 — Android test and JVM test stack

Upgrade only the outdated stable rows in `/version-control.md`. This phase owns AndroidX Test Core, Ext
JUnit, Runner, Benchmark, UI Automator, Robolectric, Turbine and JUnit 4. A JUnit 5/6 migration remains a
separate architecture decision rather than a disguised dependency bump.

Run JVM, Robolectric and connected tests. Regenerate/re-validate macrobenchmark or baseline-profile assets
only if the tool upgrade requires it; never overwrite them as incidental noise.

## Phase 8 — CI, Codex, SDK and security tooling

Use the live ledger to reconcile the complete non-application tooling surface:

- Android command-line tools, Build Tools and Platform Tools;
- GitHub Actions (`checkout`, `setup-java`, `setup-node`, upload/download artifact, Gradle setup and wrapper validation);
- the known Gitleaks divergence between Codex and the PR workflow;
- the Node/npm runtime used by the APK/Loopbound workflow;
- the previously untracked Python runtime plus NumPy/Pillow launcher-asset requirements; and
- downloaded-binary checksums whenever a pinned binary changes.

Keep release/upload signing secrets out of ordinary Codex environments and keep
`BOOKWAVE_DEBUG_KEYSTORE_BASE64` as the only optional Codex signing secret. Review mutable aliases
(`ubuntu-latest`, major action tags and SDK-manager moving packages) explicitly rather than treating them as
exact pins.

After the upgraded build stack is green, re-run the modern-JDK compatibility matrix and update
`CODEX_ENV_JAVA_VERSION` to the highest fully passing stable JDK.

## Phase 9 — automate staying current

Once the repository is at latest stable, add dependency automation so this does not become a yearly
archaeological expedition.

Recommended policy:

- enable Renovate or equivalent for Gradle version catalogs, wrapper, GitHub Actions, pinned tool
  versions and the Python requirements file;
- stable releases only by default;
- patch/minor updates can be grouped by ecosystem where tests provide confidence;
- major updates get individual PRs;
- Kotlin + compiler/KSP compatibility updates may be grouped intentionally;
- AndroidX/Compose groups should remain small enough to diagnose regressions;
- never auto-merge major updates;
- run the normal gate plus the Codex compatibility canary for build-tool changes;
- require dependency/tooling PRs to update `/version-control.md`; add a lightweight CI guard if practical so
  a changed tracked pin cannot silently leave the ledger stale.

A monthly dependency-health issue/report is enough; there is no value in notification confetti for every
transitive patch.

## Complete version-catalog phase ownership

Every version key remains assigned so nothing silently falls outside the roadmap. **Current and latest stable
versions are intentionally not duplicated here; see `/version-control.md`.**

| Version key | Phase / treatment |
| --- | --- |
| androidGradlePlugin | Phase 1 |
| kotlin | Phase 2 |
| ksp | Phase 2; re-check with every Kotlin move |
| detekt | Phase 2 and ADR-0011 compatibility gate |
| kover | Phase 2 |
| ktlintGradle | Phase 2 |
| ktlint | Phase 2 |
| protobufPlugin | Phase 2 |
| compileSdk | Phase 3 |
| minSdk | Preserve unless product decision changes |
| targetSdk | Phase 3 behavioral migration |
| androidxActivity | Phase 3 |
| androidxAnnotation | Phase 3 |
| androidxCore | Phase 3 |
| androidxDatastore | Phase 4 |
| androidxHiltNavigationCompose | Phase 3 |
| androidxLifecycle | Phase 3 |
| androidxNavigation | Phase 3 |
| androidxRoom | Phase 4 |
| androidxTestCore | Phase 7 |
| androidxTestExt | Phase 7 |
| androidxTestRunner | Phase 7 |
| androidxBenchmark | Phase 7 |
| androidxUiAutomator | Phase 7 |
| androidxWork | Phase 4 |
| composeBom | Phase 3 |
| media3 | Phase 4 |
| hilt | Phase 4 |
| hiltExt | Phase 3/4 |
| javaxInject | Phase 5 compatibility review |
| kotlinxCoroutines | Phase 5 |
| kotlinxSerialization | Phase 5 |
| okhttp | Phase 5 |
| protobuf | Phase 5 |
| retrofit | Phase 5 |
| retrofitKotlinxSerialization | Phase 5; migrate away from archived converter when appropriate |
| coil | Phase 6 |
| haze | Phase 6 |
| junit4 | Phase 7 |
| robolectric | Phase 7 |
| turbine | Phase 7 |

## Per-PR merge gate

Every upgrade PR must state:

- old version(s);
- new stable version(s) and authoritative source/date;
- migration notes reviewed;
- source changes required and why;
- tests added or changed;
- exact verification run;
- device/manual tests required before merge;
- known follow-ups deliberately excluded;
- `/version-control.md` updated with the new current version, a fresh stable-version check/date and status;
- completed-history row appended when the PR is part of #135.

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

- every catalog key and repository-owned tool/runtime row in `/version-control.md` is on the latest stable
  compatible release or has a documented reason not to be;
- Gradle, AGP, Kotlin and KSP are on mutually supported current stable releases;
- compileSdk/targetSdk are current stable Android levels with behavior changes reviewed;
- GitHub Actions and Codex-side tools are current stable;
- the highest fully verified modern JDK is the Codex baseline;
- `verifyDebug --rerun-tasks` is green;
- Room schema/migration tests are green;
- connected tests are green;
- Android Auto/playback/download/offline smoke tests pass;
- dependency automation is enabled to keep the repository near-current continuously;
- `/version-control.md` remains the maintained quick-status ledger rather than becoming another historical snapshot.
