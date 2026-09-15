# BookWave latest-stable upgrade roadmap

**Status:** Active migration roadmap  
**Last full stable-version check:** 2026-09-15  
**Current source of truth for actual pins:** [`/version-control.md`](../version-control.md) and
[`/gradle/libs.versions.toml`](../gradle/libs.versions.toml)

This document is the staged execution plan for keeping BookWave on the latest **mutually compatible stable**
Android/Kotlin stack without turning dependency maintenance into one unreviewable bulk bump.

## Authority and update discipline

This roadmap defines **order, gates and evidence**, not a frozen list of version numbers.

- `/version-control.md` owns the live current/latest numerical state and must be refreshed before each migration slice.
- `/gradle/libs.versions.toml` owns the actual direct Maven/Gradle pins used by the build.
- This file changes when sequencing, compatibility gates, migration steps or verification requirements change.
- Latest stable excludes alpha, beta, RC, milestone, preview, EAP, dev and snapshot builds unless a separate
  accepted decision explicitly approves one.
- No phase may disable lint, detekt/type resolution, compiler warnings, tests, coverage, dependency verification,
  schema checks or security checks merely to make an upgrade green.
- Every classpath-changing migration uses `--rerun-tasks`; see `AGENTS.md` and `docs/testing.md`.
- Each migration starts from current `main`, not from another upgrade branch.

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
| #161 | AndroidX Activity | 1.12.4 | 1.13.0 | 2026-09-15 |
| #162 | Gradle wrapper | 8.14.3 | 8.14.5 | 2026-09-15 |

The Codex compatibility probe from 2026-09-08 remains relevant evidence: the environment bootstrap succeeds
on JDK 21, 22, 23 and 24, but the complete `verifyDebug` gate succeeds only on JDK 21. Therefore JDK 21
remains the newest **fully verified** Codex runtime until the build foundation is migrated and the matrix is
re-run.

## Live latest-stable ledger

The live current-versus-latest table is intentionally not duplicated here. Read and update
[`/version-control.md`](../version-control.md) in the same PR as each staged migration. Every migration PR must:

1. re-resolve latest stable immediately before implementation;
2. update the affected ledger row;
3. append itself to the ledger's migration history once the PR number is known; and
4. record any compatibility block or phase-order change here.

## Non-negotiable invariants

Every phase preserves these repository guarantees:

1. `./gradlew verifyDebug` remains the authoritative debug gate.
2. Classpath-changing phases also run `./gradlew ktlintCheck --rerun-tasks` and
   `./gradlew verifyDebug --continue --rerun-tasks -Pshelfplayer.warningsAsErrors=true`.
3. Strict dependency verification remains enabled and trusted metadata is explicit.
4. Dependency locking is not silently weakened. Retry ADR-0010's lock-state generation at the build-foundation
   migration and record the result.
5. Java bytecode stays at the product's required target unless a separate accepted decision changes it.
6. Room schema files are immutable once committed; migrations are explicit.
7. No dependency update changes the app's Audiobookshelf contract without captured fixture evidence.
8. Release signing credentials remain outside ordinary repositories, logs and Codex environments.
9. No migration PR mixes unrelated feature work into its diff.

## Phase 0 — inventory and compatibility frontier

**Status: complete.**

The inventory phase was executed by PR #137. Its dated findings have since been replaced by the live ledger in
`/version-control.md`; do not copy the old version table back into this roadmap.

Phase 0's continuing rule is procedural: before every new phase, refresh the exact current pins, upstream stable
releases, compatibility matrices, accepted ADRs, open migration PRs and CI state.

## Phase 1 — Gradle and Android build foundation

**Status: major foundation gated; Gradle 8 maintenance remains executable.** ADR-0011 still blocks the Gradle 9 / AGP 9
foundation, but it does not justify leaving the accepted Gradle 8.14 line on an obsolete patch. BookWave therefore
tracks the latest stable 8.14.x maintenance release independently while the major foundation gate remains in force.

Upgrade the build foundation before libraries. Compiler and Android plugin upgrades should not be debugged
on top of an old wrapper.

1. Keep the current accepted Gradle major/minor line on its latest stable maintenance release while ADR-0011 blocks the major foundation; when the gate clears, re-resolve the latest mutually compatible Gradle/AGP tuple from `/version-control.md`.
2. Run wrapper validation and inspect Gradle upgrade warnings.
3. Fix deprecated Gradle APIs in `build-logic` rather than enabling compatibility flags indefinitely.
4. Upgrade Android Gradle Plugin to the latest mutually compatible stable AGP recorded in `/version-control.md`.
5. Apply AGP migration changes separately from Kotlin changes where possible.
6. Re-test strict dependency verification before any dependency metadata is regenerated.
7. Validate signing configuration, packaging, generated BuildConfig/resources, Room/KSP task wiring and APK
   outputs.
8. Retry ADR-0010's dependency-lock generation. If lock state still cannot be made correct for AGP/Kover detached
   configurations, document the evidence again instead of committing partial locks.
9. Keep `compileSdk` / `targetSdk` changes out of this phase unless the selected AGP stable compatibility floor
   forces `compileSdk`; behavioral Android platform migration remains Phase 3.

Required verification:

```bash
./gradlew ktlintCheck --rerun-tasks
./gradlew verifyDebug --continue --rerun-tasks -Pshelfplayer.warningsAsErrors=true
./gradlew :app:assembleRelease
./gradlew :app:assembleDebug
```

Then run the Codex JDK probe again. Test every currently relevant stable/LTS JDK from 21 through the newest JDK
supported by the upgraded Gradle/AGP combination. Select the **highest JDK that passes the whole BookWave
gate**, not the highest one that can launch Gradle. As of 2026-09-15, Gradle 8.14.x officially runs through Java 24;
Java 25 requires Gradle 9.1+ and Java 26 requires Gradle 9.4+, so the current JDK 21 Codex baseline should not be
promoted merely because a newer JDK exists.

## Phase 2 — Kotlin, KSP and code-quality plugins

**Status: partially complete.** KSP reached stable 2.3.12 in PR #155, the ktlint Gradle plugin reached stable
14.2.0 in PR #156, the ktlint engine reached stable 1.8.0 in PR #157, and the Protobuf Gradle plugin reached
stable 0.10.0 in PR #158. Kotlin itself and detekt remain behind the ADR-0011 compatibility frontier.

Re-resolve the latest stable Kotlin/KSP/compiler-plugin family after the Gradle/AGP foundation is green. Do not
assume individually latest versions form one supported set.

Upgrade together only where compatibility requires it:

- Kotlin Gradle plugin;
- Compose compiler plugin;
- Kotlin serialization plugin;
- KSP;
- detekt;
- ktlint Gradle plugin + ktlint engine; and
- Kover / Protobuf Gradle plugin where the new Gradle/Kotlin stack requires it.

Check compiler opt-ins, Compose compiler configuration, Kotlin language/API levels, KSP generated sources,
detekt baselines, formatting changes and coverage thresholds. Formatting-rule changes should be isolated in
a mechanical commit where possible so semantic review remains readable.

Exit criteria: full verification green with `--rerun-tasks`, no unexplained generated-source changes, and no
coverage threshold silently reduced.

## Phase 3 — Android platform, Compose and general AndroidX

**Status: partially active, with the remaining known updates platform-gated.** PR #161 merged AndroidX Activity
1.13.0 after full CI and focused device smoke passed; that slice also proved the transitively resolved
Core/Core-KTX 1.18.0 graph on BookWave's compileSdk 36. Re-resolution before the next slice found that stable
Core 1.19.0 publishes consumer requirements for compileSdk 37 and AGP 9.1+, so the explicit Core pin must not
move independently on the current API-36 / AGP-8 foundation. Activity 1.14 remains prerelease and is excluded.

Lifecycle 2.11 Compose artifacts compile against API 37 and require AGP 9.2+. Core 1.19.0, Lifecycle 2.11,
Navigation 2.10, AndroidX Hilt 1.4 and the larger Compose/platform move therefore remain behind the same
ADR-0011 platform/build-foundation gate.

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
latest stable release in PR #154; WorkManager and Media3 are also current in the live ledger. With the
remaining independent Phase-3 paths gated, Room is the next executable dependency axis.

Stable Room 2.8.5 is compatible with BookWave's current foundation: the Room 2.8 line raises Android minSdk
from 21 to 23 and the Room Gradle Plugin's minimum AGP from 8.1 to 8.4, while BookWave remains at minSdk 26
and AGP 8.12. This is a library/compiler upgrade, not a database-format change by itself: do not bump the
BookWave database version or rewrite committed schemas unless the compiler proves a real format change.

Dagger/Hilt 2.59+ requires AGP 9 when the Hilt Gradle plugin is used, so the Hilt 2.60.1 row follows
ADR-0011 rather than running beside this Room slice.

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

- replay all captured API fixtures;
- re-run authentication and refresh-token tests;
- re-run websocket reconnect/subscription tests;
- verify cancellation/error mapping and retry semantics;
- verify token/URL/header redaction;
- verify TLS/cleartext policies; and
- run a real-server smoke against the supported Audiobookshelf floor and current stable server where available.

Coroutines changes need explicit review of cancellation ownership, `Flow` operators, test schedulers and
application-scope work. Serialization changes need fixture round-trips for Proto/DataStore and JSON DTOs.

## Phase 6 — visual libraries

Upgrade Coil and Haze independently or in a tightly coupled UI-only slice if their Compose compatibility makes
that necessary.

Coil major upgrade checks:

- authenticated cover requests still use the intended client/interceptors;
- no bearer/token enters image URLs, cache keys or logs;
- offline cached covers still render;
- memory/disk caching behavior is appropriate; and
- Compose image state/loading/error behavior remains accessible.

Haze checks:

- player / navigation chrome remains readable;
- fallback rendering works on unsupported/older devices; and
- contrast remains acceptable under light, dark and dynamic themes.

## Phase 7 — test and benchmark stack

Upgrade test libraries separately enough that a framework behavior change does not hide a production regression.
This includes Robolectric, AndroidX Benchmark, MockWebServer alignment and any test-only Compose/AndroidX updates.

Re-run:

- all Robolectric/Compose screen tests;
- process lifecycle and Room tests;
- Android instrumented tests where available;
- macrobenchmark compile/package tasks; and
- the real-device benchmark suite when the harness or benchmark library changes materially.

Do not "fix" test-framework breakage by deleting assertions, disabling tests or reducing coverage thresholds.

## Phase 8 — CI, Codex, SDK and security tooling

Upgrade GitHub Actions and auxiliary tooling after the application/build stack is green so build-environment
changes are not mixed with application dependency changes.

Re-resolve:

- `actions/checkout`;
- `actions/setup-java`;
- `gradle/actions/setup-gradle` and wrapper validation;
- artifact upload/download actions;
- `github/codeql-action` and `github-script`;
- gh-aw;
- gitleaks;
- Android command-line tools and platform tools; and
- Codex provisioning/JDK.

Keep release/upload signing secrets out of ordinary Codex environments and keep integrity-sensitive downloads on
exact pins.

After the upgraded build stack is green, re-run the modern-JDK compatibility matrix and update
`CODEX_ENV_JAVA_VERSION` to the highest fully passing supported stable/LTS JDK. Do not move the Codex baseline
from JDK 21 to a short-lived newer feature release merely to increase the version number; as of 2026-09-15 Java 25
requires Gradle 9.1+ to run Gradle and Java 26 requires Gradle 9.4+.

## Phase 9 — automate staying current

Once the manual migration reaches the selected stable frontier, add automation that raises reviewable update PRs
without merging them automatically.

Preferred behavior:

- group only versions with the same compatibility surface;
- keep major versions separate;
- never include prereleases by default;
- run the full classpath-changing gates on every proposed update;
- keep Room, Media3, network majors and build-foundation changes in dedicated PRs; and
- update `/version-control.md` as part of accepted upgrades.

## Version-key ownership map

| Version-catalog key / component | Owned migration phase |
| --- | --- |
| agp | Phase 1 |
| kotlin | Phase 2 |
| ksp | Phase 2 |
| protobufPlugin | Phase 2 |
| ktlintGradle | Phase 2 |
| ktlint | Phase 2 |
| detekt | Phase 2 |
| kover | Phase 2 / 7 |
| compileSdk | Phase 3 |
| minSdk | Product decision |
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
| javaxInject | Phase 5 compatibility |
| coroutines | Phase 5 |
| serialization | Phase 5 |
| okhttp | Phase 5 |
| protobuf | Phase 5 |
| retrofit | Phase 5 |
| retrofitSerialization | Phase 5 |
| coil | Phase 6 |
| haze | Phase 6 |
| junit | Phase 7 |
| mockwebserver | Phase 5/7 |
| robolectric | Phase 7 |
| turbine | Phase 7 |
| CI actions | Phase 8 |
| Android SDK tooling | Phase 8 |
| JDK / Codex provisioning | Phase 8 after build foundation |

## Verification template for every classpath-changing PR

At minimum:

```bash
./gradlew ktlintCheck --rerun-tasks
./gradlew verifyDebug --continue --rerun-tasks -Pshelfplayer.warningsAsErrors=true
```

For changes affecting APK/runtime behavior also assemble and device-test. For Room, Media3, WorkManager,
networking and targetSdk changes, execute the phase-specific checks above before merge.

The PR description must record:

- exact resolved versions;
- upstream compatibility evidence;
- commands run and whether they passed;
- generated metadata/lock/schema changes;
- device/server/manual validation where applicable; and
- any accepted risk or follow-up issue.

## Exit criteria for the migration program

Issue #135 can close only when:

- every direct pin is either latest mutually compatible stable **or explicitly documented as intentionally held**;
- the selected Gradle/AGP/Kotlin/JDK/SDK combination is supported by its upstream matrices;
- strict dependency verification is intact and ADR-0010 locking has been retried at the build-foundation phase;
- Room schema/migration tests are green;
- playback, background work, network and UI device smoke appropriate to changed libraries are complete;
- Codex/bootstrap/CI use the selected supported tooling; and
- dependency-update automation exists and raises reviewable future drift.
