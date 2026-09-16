# Latest-stable upgrade plan

This document is the execution plan for issue #135 — `[BW-DEP-01] Execute staged latest-stable toolchain and dependency migration`.

The goal is not to "update everything" in one pass. The goal is to move BookWave toward the latest mutually compatible stable stack while preserving its Android/Audiobookshelf contracts and keeping every migration independently reviewable.

## Operating rules

- Resolve latest stable versions again immediately before every migration PR.
- No alpha, beta, RC, EAP, preview or snapshot dependencies unless separately approved.
- Do not silently override accepted ADRs.
- One compatibility/risk axis per PR where practical.
- Start every migration from current `main`.
- Keep strict dependency verification and warnings-as-errors enabled.
- Classpath-changing upgrades must use rerun verification.
- Do not manufacture work merely to move a roadmap checkbox.

## Completed staged slices

| PR | Component | From | To | Merged |
| --- | --- | ---: | ---: | --- |
| #154 | AndroidX DataStore | 1.1.7 | 1.2.1 | 2026-09-14 |
| #155 | KSP | 2.3.11 | 2.3.12 | 2026-09-14 |
| #156 | ktlint Gradle plugin | 12.3.0 | 14.2.0 | 2026-09-15 |
| #157 | ktlint engine | 1.5.0 | 1.8.0 | 2026-09-15 |
| #158 | Protobuf Gradle plugin | 0.9.5 | 0.10.0 | 2026-09-15 |
| #161 | AndroidX Activity | 1.12.4 | 1.13.0 | 2026-09-15 |
| #162 | Gradle wrapper | 8.14.3 | 8.14.5 | 2026-09-15 |
| #163 | AndroidX Room | 2.7.2 | 2.8.5 | 2026-09-15 |
| #164 | kotlinx.serialization | 1.8.1 | 1.9.0 | 2026-09-15 |
| #165 | kotlinx.coroutines | 1.10.2 | 1.11.0 | 2026-09-15 |
| #166 | Protobuf runtime/protoc | 4.31.1 / 31.1 | 4.36.1 / 36.1 | 2026-09-16 |
| #167 | Retrofit kotlinx.serialization converter | Jake Wharton 1.0.0 | Square 2.11.0 | 2026-09-16 |

The Codex compatibility probe from 2026-09-08 remains relevant evidence: the environment bootstrap succeeds
on JDK 21, 22, 23 and 24, but the complete `verifyDebug` gate succeeds only on JDK 21. Therefore JDK 21
remains the newest **fully verified** Codex runtime until the build foundation is migrated and the matrix is
re-run.

## Live latest-stable ledger

[`/version-control.md`](../version-control.md) is the canonical repository-wide ledger for:

- every version key in `gradle/libs.versions.toml`;
- the Gradle wrapper, Android SDK levels/tools and JDK lanes;
- every GitHub Action family used by BookWave;
- auxiliary security/CI tooling; and
- the latest compatible stable frontier.

This plan deliberately does **not** duplicate that full matrix. It owns sequencing, compatibility/risk policy,
validation expectations and phase completion criteria.

## Phase 0 — inventory and compatibility frontier

**Status: complete.** PR #137 established the first compatibility inventory and current frontier without changing
versions.

## Phase 1 — build foundation: Gradle / AGP / Android platform

**Status: gated by ADR-0011.**

ADR-0011 says BookWave stays on API 36 / AGP 8 while detekt 2.x remains prerelease. Re-resolve the following only
when a stable detekt 2.x with the required AGP 9 support exists, or when ADR-0011 is explicitly superseded:

- Gradle wrapper;
- Android Gradle Plugin;
- compileSdk / targetSdk;
- Kotlin compiler if required by the supported AGP tuple;
- dependency locking retry required by ADR-0010.

Do not select the individually newest Gradle, AGP and Kotlin versions if their supported ranges do not overlap.

When the gate opens, this phase requires:

- official Gradle/AGP/Kotlin compatibility evidence;
- JDK runtime/bytecode compatibility re-probe;
- dependency locking re-test without weakening strict verification;
- clean build-logic configuration;
- `verifyDebug --rerun-tasks` with warnings-as-errors;
- connected/device validation for the new target SDK behavior changes.

## Phase 2 — Kotlin / KSP / quality tooling

**Status: partially complete.** KSP, ktlint Gradle plugin, ktlint engine and Protobuf Gradle plugin have reached the
current stable frontier. Kotlin itself remains coupled to the gated build-foundation re-resolution, and detekt remains
behind ADR-0011 until stable 2.x exists.

Do not move Kotlin independently merely because a newer compiler exists. Re-resolve KSP and compiler-plugin
compatibility when the foundation gate opens.

## Phase 3 — Android SDK / Compose / general AndroidX

**Status: active with platform-gated rows intentionally deferred.**

AndroidX Activity 1.13.0 reached its stable frontier in PR #161. Other rows whose current stable releases compile
against API 37 or require AGP 9.1+/9.2+ remain deferred under ADR-0011. Do not use this phase to sneak in the platform
move before that gate clears.

When a non-gated AndroidX/Compose slice moves:

- update one coherent family at a time;
- verify adaptive layouts, accessibility semantics and large text;
- preserve state restoration;
- run device/emulator validation when runtime/UI behavior can change.

## Phase 4 — persistence, background work and playback libraries

**Status: active in separate risk-sized slices.**

DataStore 1.2.1 landed in PR #154. Room 2.8.5 landed in PR #163 without changing the Room database version or committed
schema history. Continue to treat Room, WorkManager and Media3 as separate migration axes.

Room requirements:

- never rewrite a committed schema;
- preserve migration coverage and destructive-migration policy;
- test upgrade/open paths and DAO/repository behavior.

WorkManager/DataStore requirements:

- preserve durable work/state across process death;
- verify retry/backoff and constraints;
- verify stored grants/settings survive upgrade unchanged unless intentionally migrated.

## Phase 5 — Kotlin runtime, serialization and network stack

**Status: active in compatibility-sized slices.** kotlinx.serialization 1.9.0 landed in PR #164 as the newest
stable release aligned with BookWave’s current Kotlin 2.2.0 compiler line, kotlinx.coroutines 1.11.0 landed in PR #165
after the full rerun gate proved it compatible with BookWave’s Kotlin 2.2.0 build despite upstream building it with
Kotlin 2.2.20, and PR #166 moved the matched Protobuf Java/Kotlin-lite runtime and protoc to 4.36.1 / 36.1.

PR #167 retired the archived Jake Wharton Retrofit kotlinx.serialization converter in favor of Retrofit’s
maintained first-party `converter-kotlinx-serialization` artifact while holding Retrofit core at 2.11.0 and OkHttp
at 4.12.0. The next independent network slice upgrades Retrofit core and its first-party converter together from
2.11.0 to 3.0.0 while deliberately keeping OkHttp at 4.12.0. Retrofit 3.0.0’s release notes state that 3.x maintains
forward binary compatibility with 2.x and that its material dependency change is moving Retrofit’s OkHttp baseline
to 4.12, which BookWave already uses directly. OkHttp 5 therefore remains a separate later risk axis. Stable
kotlinx.serialization 1.10.0+ remains behind the compiler/build-foundation re-resolution.

Upgrade deliberately from the current values to the compatible stable targets recorded in `/version-control.md`.
This phase owns kotlinx-coroutines, kotlinx-serialization, OkHttp, Retrofit, the Kotlin serialization converter,
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
- auxiliary script/tool versions owned by the repository.

After the build-foundation migration, re-probe JDK 21–26 (or the then-current relevant stable set) rather than
assuming the old Gradle 8 compatibility result still applies.

## Phase 9 — dependency update automation

After the manual migration reaches the selected stable frontier, add update automation that:

- surfaces stable-version drift;
- never auto-merges risky majors;
- groups only low-risk patch/minor changes with compatible test evidence;
- respects ADR gates and ignored prereleases;
- leaves reviewable PRs with the same strict verification policy as manual changes.

## Version-key phase mapping

This is the execution ownership map for direct catalog keys. Current and latest versions are intentionally
not duplicated here; see `/version-control.md`.**

| Version key | Phase / note |
| --- | --- |
| agp | Phase 1 / ADR-0011 gate |
| kotlin | Phase 2 / compiler foundation; currently coupled to Phase 1 |
| androidxActivity | Phase 3 |
| androidxAnnotation | Phase 3 |
| androidxCore | Phase 3 / current latest stable requires API 37 / AGP 9.1+ |
| androidxDatastore | Phase 4 |
| androidxHiltNavigationCompose | Phase 3/4 / 1.4 Compose artifact follows the API 37 / AGP 9.2+ gate |
| androidxLifecycle | Phase 3 / 2.11 Compose artifacts follow the API 37 / AGP 9.2+ gate |
| androidxNavigation | Phase 3 / 2.10 Compose artifacts follow the API 37 / AGP 9.2+ gate |
| androidxRoom | Phase 4 |
| androidxTestCore | Phase 7 |
| androidxTestExtJunit | Phase 7 |
| androidxTestRunner | Phase 7 |
| androidxTestRules | Phase 7 |
| androidxTestUiAutomator | Phase 7 |
| androidxBenchmark | Phase 7 |
| androidxWork | Phase 4 |
| androidxMedia3 | Phase 4 |
| androidxComposeBom | Phase 3 |
| androidxComposeMaterial3Adaptive | Phase 3 |
| androidxComposeMaterial3WindowSizeClass | Phase 3 |
| androidxProfileinstaller | Phase 8 compatibility review |
| androidxStartup | Phase 8 compatibility review |
| androidxTestOrchestrator | Phase 7 |
| androidxTracing | Phase 8 compatibility review |
| androidxWindow | Phase 3 |
| androidxCoreSplashscreen | Phase 3 |
| coil | Phase 6 |
| detekt | Phase 2 / ADR-0011 gate |
| haze | Phase 6 |
| hilt | Phase 3/4; review together with AGP/Kotlin constraints |
| javaxInject | Phase 5 compatibility review |
| junit4 | Phase 7 |
| kotlinxCoroutines | Phase 5 |
| kotlinxSerialization | Phase 5 |
| okhttp | Phase 5 |
| protobuf | Phase 5 |
| retrofit | Phase 5 |
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
