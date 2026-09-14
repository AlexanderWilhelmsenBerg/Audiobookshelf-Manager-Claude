# BW-DEP-01 Phase 0 compatibility inventory

**Classification:** Current investigation snapshot with staged execution updates — re-resolve before every implementation phase.
**Issue:** #135 (`BW-DEP-01`).
**Measured:** 2026-09-10; execution refresh 2026-09-14.
**Repository base for the current execution slice:** `main` at `4be0daf0f988fd93a09b134df2c24333facbf69c`.
**Scope:** compatibility evidence plus staged execution status; each actual upgrade remains a narrow, independently verified slice.

`gradle/libs.versions.toml` remains the pinned source of truth for what BookWave actually builds with. This
page records what was current upstream on the measurement date and why many individually newer stable
versions are **not** yet an approved BookWave combination. It is not permission to bulk-update the catalog.

## Phase 0 decision and execution refresh

ADR-0011 is superseded by ADR-0030. The safety requirement did not disappear: BookWave still requires a
stable, mandatory, type-aware static-analysis gate and will not adopt a detekt alpha merely to unlock a newer
build foundation. What changed is the architecture boundary. The build foundation, compiler/tooling stack,
`compileSdk` and `targetSdk` are now separate migration axes.

The final Phase 1 foundation switch therefore remains gated until a mutually supported stable stack can
preserve the complete verification contract, but **Phase 1A readiness work may proceed now** on the current
production foundation. Platform targeting no longer blocks build-readiness work, and a future build-foundation
migration will not automatically raise `targetSdk`.

The newest Compose, AndroidX Hilt and several Compose-facing AndroidX lines have moved to API 37 / AGP 9-era
compilation, while Dagger/Hilt `2.59+` makes AGP 9 a requirement for users of the Hilt Gradle plugin. Those
stable releases are real, but they remain separate later-phase upgrades rather than reasons to weaken the
quality gate.

### 2026-09-14 Phase 2A execution

KSP `2.3.12` was released as a stable patch and raises its minimum supported AGP to `8.12.0`, exactly
BookWave's current AGP baseline. That makes it an independently safe compiler-tooling slice while the broader
foundation remains gated. The upgrade changes no Room schema and adds only the generated strict dependency
verification entries for the new KSP artifacts.

The individually newest Gradle, AGP and Kotlin releases also do not form one fully supported tuple:

- Gradle is `9.7.1` stable.
- AGP is `9.4.0` stable and requires at least Gradle `9.6.0`, JDK 17, and supports API 37.
- Kotlin/KGP is `2.4.20` stable, but JetBrains lists its fully supported ceiling as Gradle `9.7.0` and
  AGP `9.3.1`.

Therefore the newest tuple inside all three published compatibility bounds on 2026-09-10 is
**Gradle `9.7.0` + AGP `9.3.1` + Kotlin `2.4.20`**, not the three individually newest versions. BookWave
must still re-resolve this frontier when Phase 1 actually opens; these numbers are evidence, not a future pin.

## Terms used below

- **Current** — BookWave already pins the newest stable release for the relevant line.
- **BookWave baseline** — current repository-proven combination, even where an upstream compatibility table
  is narrower than BookWave's tested combination.
- **Blocked** — a stable release exists, but an accepted ADR or an upstream build requirement prevents its
  adoption in the present foundation.
- **Deferred** — a stable update exists but belongs to a later staged phase and its compatibility must be
  re-resolved there.
- **Major / isolated** — source, behavior, storage or platform risk is large enough that it must not be folded
  into a general dependency PR.

## Build, compiler and SDK frontier

| Component | BookWave current | Newest stable observed | Official compatibility / finding | Phase 0 disposition |
| --- | --- | --- | --- | --- |
| Gradle wrapper | `8.14.3` | `9.7.1` | Gradle 9.7.1 can run on JVM 17–26. Kotlin 2.4.20's fully supported ceiling is Gradle 9.7.0. | **Phase 1B gated by ADR-0030.** Do not jump to 9.7.1 merely because it is individually latest. |
| Android Gradle Plugin | `8.12.0` | `9.4.0` | AGP 9.4 supports up to API 37; minimum/default Gradle is 9.6.0; JDK minimum/default is 17; default Build Tools is 36.0.0. | **Phase 1B gated by ADR-0030 verification invariant; Phase 1A readiness may proceed.** |
| Kotlin / KGP | `2.2.0` | `2.4.20` | KGP 2.4.20 fully supports Gradle 7.6.3–9.7.0 and AGP 8.5.2–9.3.1. KGP 2.2.0–2.2.10 lists AGP only through 8.10.0, so BookWave's current AGP 8.12/Kotlin 2.2 combination is repository-proven but outside JetBrains' fully-supported matrix. | **Deferred with Phase 1/2.** Re-resolve with AGP rather than treating Kotlin independently. |
| KSP | `2.3.12` | `2.3.12` | Stable 2026-09-09; minimum supported AGP is 8.12.0, matching BookWave. | **Current after Phase 2A.** |
| detekt | `1.23.8` | `1.23.8` stable | detekt 2 is still alpha; `2.0.0-alpha.6` demonstrates modern-toolchain compatibility, but prereleases are excluded from the production quality gate. | **Current stable line; Phase 1B remains gated by ADR-0030.** |
| Kover | `0.9.9` | `0.9.9` | Current Gradle Plugin Portal stable. | **Current.** |
| ktlint Gradle plugin | `12.3.0` | `14.2.0` | Plugin 13.1+ added Gradle 9 support; 14.2.0 is current stable. | **Deferred to Phase 2.** |
| ktlint engine | `1.5.0` | `1.8.0` | 1.8.0 is current stable. | **Deferred to Phase 2** with plugin/detekt rule compatibility. |
| protobuf Gradle plugin | `0.9.5` | `0.10.0` | 0.10.0 is current stable on the Gradle Plugin Portal. | **Deferred to Phase 2 / paired protobuf work.** |
| `compileSdk` / `targetSdk` | `36` / `36` | API 37 released | AGP 9.4 supports API 37; Compose 1.12 requires compileSdk 37 and AGP 9. | **Independent Phase 3 axes under ADR-0030; targetSdk remains a dedicated behavioral migration.** |
| SDK Build Tools | `36.0.0` | `36.0.0` for current AGP 9.4 default | AGP 9.4 still lists 36.0.0 as its default Build Tools version. | **Current for the measured frontier.** |
| Android command-line tools | build `15859902` in Codex | build `15859902` | Android Developers' current download page serves build 15859902 and publishes the same Linux SHA-256 that BookWave pins. | **Current.** Re-probe in Phase 8. |
| Platform Tools | installed via `sdkmanager` without a repository version pin | `37.0.1` listed | Android recommends obtaining the latest package through SDK Manager. The 37.0.1 revision is listed in current release notes; channel presentation has varied across localized docs, so Phase 8 must re-probe rather than invent a repository pin here. | **Phase 8 finding only.** |
| CI JDK | Temurin 17 | Temurin 26 feature line exists; 17/21 remain supported lines | Gradle 9.7.1 can run on JVM 17–26, but BookWave deliberately exercises its minimum runtime in CI. | **Keep 17 now. Re-probe in Phase 8.** |
| Codex JDK | 21 baseline | newer Temurin lines exist | `AGENTS.md` records that JDK 22–24 prepare but fail `verifyDebug` under the current Gradle/AGP/Kotlin foundation. Gradle JVM support alone is not BookWave verification. | **Keep 21 now. Re-probe after foundation migration.** |

### Supported combinations versus BookWave-approved combinations

| Combination | Status | Meaning |
| --- | --- | --- |
| Gradle `9.7.1` + AGP `9.4.0` + Kotlin `2.4.20` | **Not fully supported as one tuple** | Each is individually stable, but KGP 2.4.20's published ceiling is Gradle 9.7.0 and AGP 9.3.1. |
| Gradle `9.7.0` + AGP `9.3.1` + Kotlin `2.4.20` | **Inside the current published compatibility intersection** | Useful dated frontier evidence only. ADR-0030 still requires a stable type-aware analysis gate before the final foundation switch. |
| Gradle `8.14.3` + AGP `8.12.0` + Kotlin `2.2.0` + detekt `1.23.8` | **BookWave current/proven baseline** | This is what `main` verifies today. JetBrains' KGP table does not call the AGP 8.12 + Kotlin 2.2.0 pairing fully supported (its ceiling is AGP 8.10.0), so do not mislabel repository evidence as upstream support. |
| Any next-generation AGP BookWave foundation | **Not approved yet** | Requires ADR-0030's stable verification invariant, fresh compatibility resolution and the ADR-0010 locking retry. |
| API 37 compile/target migration | **Separate from the foundation** | `compileSdk` may move when useful and supported; `targetSdk` requires its own behavioral/device acceptance under ADR-0030. |

## Android and platform libraries

The AndroidX stable-version table changed as recently as 2026-09-09. These values must therefore be
re-resolved at the start of Phase 3/4/7 rather than copied forward from this document.

| Area | BookWave current | Newest stable observed | Disposition |
| --- | --- | --- | --- |
| Compose BOM / core Compose | `2025.06.01` | BOM `2026.08.00`; core Compose `1.12.1` | **Blocked.** Compose 1.12 requires compileSdk 37 + AGP 9; Phase 3 after the foundation gate. |
| Activity | `1.12.4` | `1.13.0` | **Deferred Phase 3.** Re-check AAR metadata against the selected compile SDK. |
| Annotation | `1.10.0` | `1.10.0` | **Current.** |
| Core KTX | `1.17.0` | `1.19.0` | **Deferred Phase 3.** Do not assume latest AndroidX artifacts remain consumable by compileSdk 36. |
| Lifecycle | `2.10.0` | `2.11.0` | **Deferred/blocked Phase 3** because BookWave consumes Compose-facing lifecycle artifacts; re-resolve after API/AGP foundation. |
| Navigation Compose | `2.9.8` | `2.10.1` | **Deferred/blocked Phase 3** with Compose/API 37 foundation. |
| AndroidX Hilt | `1.3.0` | `1.4.0` | **Blocked.** 1.4's Compose artifacts use compileSdk 37 and require at least AGP 9.2.0; Phase 3. |
| Dagger/Hilt | `2.58` | `2.60.1` | **Blocked.** Dagger 2.59 made AGP 9 a requirement for Hilt Gradle plugin users. `2.58` is therefore the newest BookWave-allowed Hilt plugin line on the current production foundation. |
| Room 2.x | `2.7.2` | `2.8.5` | **High-risk Phase 4A.** Persistence/schema/migration/process-death evidence required. |
| Room 3 | not used | `3.0.3` | **Separate architecture migration if ever chosen.** It is a distinct generation, not a routine Room 2 version bump. |
| DataStore | `1.1.7` | `1.2.1` | **High-risk Phase 4A** with persistence/process-death evidence. |
| WorkManager | `2.11.2` | `2.11.2` | **Current.** Future WorkManager upgrades still belong in isolated Phase 4B. |
| Media3 | `1.11.0` | `1.11.0` | **Current.** Future Media3 upgrades still require isolated Phase 4C phone/notification/Bluetooth/Auto evidence. |
| Benchmark | `1.3.4` | `1.5.0` | **Deferred Phase 7** after production foundations settle. |
| UI Automator | `2.4.0` | `2.4.0` | **Current.** |
| AndroidX Test core | `1.7.0` | `1.7.0` | **Current.** |
| AndroidX Test ext JUnit | `1.3.0` | `1.3.0` | **Current.** |
| AndroidX Test runner | `1.7.0` | `1.7.0` | **Current.** |

## Networking and runtime libraries

| Component | BookWave current | Newest stable observed | Disposition |
| --- | --- | --- | --- |
| kotlinx.coroutines | `1.10.2` | `1.11.0` | **Deferred Phase 5** after compiler foundation. 1.11.0 itself was built with Kotlin 2.2.20. |
| kotlinx.serialization | `1.8.1` | `1.11.0` | **Deferred Phase 5** after compiler foundation. 1.11.0 is based on Kotlin 2.3.20. |
| protobuf-javalite | `4.31.1` | `4.36.1` | **Deferred**, paired with codegen/DataStore compatibility evidence rather than a blind runtime bump. |
| OkHttp | `4.12.0` | `5.5.0` | **Major / isolated Phase 5.** Preserve auth, websocket, cancellation and captured Audiobookshelf contract behavior. |
| Retrofit | `2.11.0` | `3.0.0` | **Major / isolated Phase 5.** Retrofit 3 preserves forward binary compatibility for 2.x-compiled libraries, but it is still a deliberate major migration. |
| Jake Wharton kotlinx-serialization converter | `1.0.0` | project archived; implementation moved into Retrofit | **Migrate in the Retrofit Phase-5 PR** to Square's first-party `com.squareup.retrofit2:converter-kotlinx-serialization`; do not mix this seam into unrelated runtime bumps. |
| `javax.inject` | `1` | `1` | **Current / no migration identified.** |

Networking migrations must continue to use BookWave's captured Audiobookshelf fixtures and contract tests.
Upstream Audiobookshelf source can corroborate behavior but does not by itself establish the supported app
contract.

## Visual libraries

| Component | BookWave current | Newest stable observed | Disposition |
| --- | --- | --- | --- |
| Coil | `2.7.0` | Coil 3 `3.6.2` | **Major / isolated Phase 6.** BookWave is current on the Coil 2 generation; Coil 3 changes Maven coordinates/API surface and needs source migration plus real UI/device validation. |
| Haze | `1.6.10` | `1.7.2` | **Deferred Phase 6.** Haze 2 is alpha and explicitly excluded. Validate rendering/performance on device. |

## Test stack

| Component | BookWave current | Newest stable observed | Disposition |
| --- | --- | --- | --- |
| JUnit 4 | `4.13.2` | `4.13.2` | **Current.** |
| Robolectric | `4.15.1` | `4.16.1` | **Deferred Phase 7.** Robolectric 4.17 is beta and excluded. |
| Turbine | `1.2.1` | `1.2.1` | **Current.** |
| AndroidX Test / UI Automator | see Android table | current for BookWave's pinned lines | **Current except Benchmark.** Re-resolve the full test stack in Phase 7. |

## CI, Codex and security-tool inventory

| Tool | Repository use | Newest stable observed | Finding |
| --- | --- | --- | --- |
| `actions/checkout` | `@v7` | `v7.0.1` | Current stable major family. No Phase-0 change. |
| `actions/setup-java` | `@v6` | `v6.0.1` | Current stable major family. `v6.0.1` was published 2026-09-09; do not downgrade based on stale search indexes. |
| `actions/upload-artifact` | `@v7` | `v7.0.1` | Current stable major family. |
| `gradle/actions` | `@v6` | `v6.3.0` | Current stable major family. |
| Gitleaks — Codex | `8.30.1` | `8.30.1` | **Current.** |
| Gitleaks — PR workflow | `8.24.0` | `8.30.1` | **Phase 8 divergence.** Record and reconcile in an approved tooling PR; do not smuggle it into Phase 0. |
| OSV scan | repository script calls OSV `querybatch` via `curl`/`jq` | service API, no scanner binary pin | No hidden binary-version drift. Keep current fail-closed behavior. |
| GitHub CI JDK | Temurin 17 | newer stable JDKs exist | Intentional minimum-runtime evidence, not an automatic upgrade target. |
| Codex JDK | 21 | newer stable JDKs exist | Intentional proven ceiling under current foundation; re-probe in Phase 8 after build migration. |
| Android command-line tools | build 15859902 + pinned SHA-256 | build 15859902 | Current on Android Developers download page. |
| Android platform/build packages | API 36 + Build Tools 36.0.0; platform-tools via SDK Manager | API 37 exists; Platform Tools release notes list 37.0.1 | API 37 is ADR-blocked; platform/security tooling reconciliation belongs in Phase 8. |

`contract-capture.yml` deliberately uses the moving `ghcr.io/advplyr/audiobookshelf:latest` image when
recapturing fixtures. That is test-fixture infrastructure, not a BookWave app dependency pin, and must not
be confused with the catalog's no-dynamic-version rule.

## Already-current stable dependencies

After the 2026-09-14 Phase 2A slice, no upgrade work is required for: KSP `2.3.12`, detekt's stable line `1.23.8`,
Kover `0.9.9`, AndroidX Annotation `1.10.0`, WorkManager `2.11.2`, Media3 `1.11.0`, UI Automator `2.4.0`,
AndroidX Test Core `1.7.0`, AndroidX Test ext JUnit `1.3.0`, AndroidX Test Runner `1.7.0`, JUnit `4.13.2`,
Turbine `1.2.1`, Android command-line-tools build `15859902`, and Codex Gitleaks `8.30.1`.

"Already current" does not remove the requirement to re-resolve these at the start of the phase that owns
them. It only prevents needless churn in the present migration plan.

## Required isolated migrations

These are not candidates for a catch-all dependency PR:

1. **Room/DataStore — Phase 4A:** migrations, schema export, process death, persistence behavior.
2. **WorkManager — Phase 4B:** when a future stable update exists, prove execution/retry/constraint semantics.
3. **Media3 — Phase 4C:** when a future stable update exists, prove phone playback, media notification/controls,
   Bluetooth and Android Auto/DHU/real-car behavior.
4. **OkHttp/Retrofit/converter — Phase 5:** major networking migration with captured Audiobookshelf contracts,
   auth, websocket, cancellation and error-semantics evidence.
5. **Coil 3 — Phase 6:** separate source/coordinate migration with real UI/device evidence.
6. **Haze — Phase 6:** separate from Coil if source/rendering changes are non-trivial.
7. **CI/Codex/JDK/SDK/Gitleaks — Phase 8:** re-probe rather than copying today's provisioning.

## ADR and documentation findings

- ADR-0006's strict checksum verification is active and remains required.
- ADR-0010 intentionally defers dependency locking after a real variant-resolution failure. Locking is not a
  forgotten setup step; Phase 1 retries it on the later Gradle/AGP foundation.
- ADR-0011 is superseded by ADR-0030. ADR-0030 keeps the stable type-aware analysis invariant, excludes
  prerelease analysis tooling as a shortcut, and decouples the build foundation from `compileSdk` /
  `targetSdk`.
- `AGENTS.md`, issue #135 and `docs/latest-stable-upgrade-plan.md` identify the staged latest-stable plan as
  current dependency-migration guidance. `README.md`, `docs/README.md` and `docs/roadmap.md` still contain
  links describing `docs/dependency-upgrade-plan.md` as the active child plan. That older plan includes
  alternatives that ADR-0011 now rejects. Treat this as documentation-authority drift; do not use the older
  plan to bypass the accepted ADR.

## Authoritative upstream sources

These were re-checked on 2026-09-10. Release pages are evidence for this dated snapshot only.

### Build, compiler and Android platform

- Gradle releases: https://gradle.org/releases/
- Gradle compatibility matrix: https://docs.gradle.org/current/userguide/compatibility.html
- AGP 9.4 release notes and compatibility: https://developer.android.com/build/releases/agp-9-4-0-release-notes
- Kotlin Gradle plugin compatibility table: https://kotlinlang.org/docs/gradle-configure-project.html
- Kotlin 2.4.20 release notes: https://kotlinlang.org/docs/whatsnew2420.html
- KSP releases: https://github.com/google/ksp/releases
- detekt stable changelog: https://detekt.dev/changelog/
- detekt 2 alpha changelog: https://detekt.dev/changelog-2.0.0/
- Kover Gradle Plugin Portal: https://plugins.gradle.org/plugin/org.jetbrains.kotlinx.kover
- ktlint Gradle plugin: https://plugins.gradle.org/plugin/org.jlleitschuh.gradle.ktlint
- ktlint releases: https://github.com/ktlint/ktlint/releases
- protobuf Gradle plugin: https://plugins.gradle.org/plugin/com.google.protobuf
- Android Studio / command-line tools downloads: https://developer.android.com/studio
- SDK Platform Tools release notes: https://developer.android.com/tools/releases/platform-tools
- Eclipse Temurin release notes: https://adoptium.net/temurin/release-notes

### AndroidX / Compose / DI

- Current AndroidX versions: https://developer.android.com/jetpack/androidx/versions
- AndroidX stable channel: https://developer.android.com/jetpack/androidx/versions/stable-channel
- Compose BOM: https://developer.android.com/develop/ui/compose/bom
- Compose compileSdk/AGP compatibility: https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler
- AndroidX Hilt: https://developer.android.com/jetpack/androidx/releases/hilt
- Room: https://developer.android.com/jetpack/androidx/releases/room
- DataStore: https://developer.android.com/jetpack/androidx/releases/datastore
- WorkManager: https://developer.android.com/jetpack/androidx/releases/work
- Media3: https://developer.android.com/jetpack/androidx/releases/media3
- Benchmark: https://developer.android.com/jetpack/androidx/releases/benchmark
- AndroidX Test: https://developer.android.com/jetpack/androidx/releases/test
- Dagger/Hilt releases: https://github.com/google/dagger/releases

### Runtime, networking, UI and tests

- kotlinx.coroutines releases: https://github.com/Kotlin/kotlinx.coroutines/releases
- kotlinx.serialization releases/changelog: https://github.com/Kotlin/kotlinx.serialization/releases
- protobuf-javalite Maven Central: https://central.sonatype.com/artifact/com.google.protobuf/protobuf-javalite
- OkHttp Maven Central: https://central.sonatype.com/artifact/com.squareup.okhttp3/okhttp
- Retrofit releases: https://github.com/square/retrofit/releases
- Retrofit changelog: https://github.com/square/retrofit/blob/trunk/CHANGELOG.md
- archived Jake Wharton converter: https://github.com/JakeWharton/retrofit2-kotlinx-serialization-converter
- Coil changelog: https://coil-kt.github.io/coil/changelog/
- Haze releases: https://github.com/chrisbanes/haze/releases
- Robolectric releases: https://github.com/robolectric/robolectric/releases
- Turbine releases: https://github.com/cashapp/turbine/releases

### CI/security tooling

- checkout releases: https://github.com/actions/checkout/releases
- setup-java releases: https://github.com/actions/setup-java/releases
- upload-artifact releases: https://github.com/actions/upload-artifact/releases
- Gradle Actions releases: https://github.com/gradle/actions/releases
- Gitleaks releases: https://github.com/gitleaks/gitleaks/releases

## Phase handoff rules

At the start of every later phase, repeat the relevant upstream resolution. In particular, do **not** carry
forward today's Gradle `9.7.0` / AGP `9.3.1` / Kotlin `2.4.20` intersection as a promised target. Phase 1A
readiness may proceed on the current production foundation. Phase 1B starts only when ADR-0030's stable
type-aware verification invariant can be satisfied; its first engineering act is to recompute the frontier and
retry ADR-0010 dependency locking with the repository's full gate intact.
