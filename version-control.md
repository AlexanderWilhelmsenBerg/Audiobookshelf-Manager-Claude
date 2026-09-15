# BookWave version control

> Canonical live ledger for repository-owned toolchains, direct dependencies, frameworks, test libraries,
> CI actions and auxiliary build tooling.

**Last full stable-version check:** 2026-09-15  
**Repository baseline checked:** `main` at `0c3f7ab0bae27f267236ac4f8adad0d33ece88d1`  
**Upgrade roadmap:** [`docs/latest-stable-upgrade-plan.md`](docs/latest-stable-upgrade-plan.md)  
**Primary migration issue:** #135 — `[BW-DEP-01] Execute staged latest-stable toolchain and dependency migration`

## How to maintain this file

This file is the quick answer to **“what version are we on, what is the newest stable version, and what is left to do?”**

- Update this file in the **same PR** whenever a tracked version, runtime, SDK level, GitHub Action or pinned tool changes.
- Re-check the affected row against its authoritative upstream source on every version-changing PR and update **Last checked**.
- A full dependency-health review should refresh every row and the **Last full stable-version check** date.
- **Latest stable** excludes alpha, beta, RC, milestone, preview, EAP, dev and snapshot builds.
- **Latest stable is not automatically the approved next version.** Compatibility gates and migration notes still apply.
- `gradle/libs.versions.toml` remains the source of truth for Gradle/Maven pins. This file mirrors those pins for planning/status visibility.
- Direct repository-owned versions are tracked here. Transitive Maven artifacts are not individually listed; Gradle dependency verification, the dependency report/SBOM and vulnerability scanning cover that surface.
- Mutable aliases such as `actions/checkout@v7`, `ubuntu-latest`, `platform-tools`, and container `:latest` tags are called out explicitly instead of pretending the repository pins an exact version.

### Status legend

| Status | Meaning |
| --- | --- |
| ✅ Current | Repository is already on the latest stable release. |
| ⬆️ Update | A newer stable release exists and belongs to the stated migration phase. |
| ⛔ Gated | A newer stable release exists but an accepted compatibility/product gate blocks it. |
| 🔁 Migration | The newer stable path crosses a major/API ownership boundary and needs a dedicated migration PR. |
| 🎯 Intentional | The repository deliberately uses a non-latest baseline for compatibility/evidence. |
| ↔️ Dynamic | The repository intentionally uses a moving alias or does not pin an exact version. |

## Build, compiler and quality toolchain

| Component | Current in BookWave | Latest stable | Status / next action | Phase | Last checked | Authoritative source |
| --- | --- | --- | --- | --- | --- | --- |
| Gradle wrapper | 8.14.3 | 9.7.1 | ⛔ Gated with the AGP 9 foundation by ADR-0011. | 1 | 2026-09-15 | [Gradle releases](https://gradle.org/releases/) |
| Android Gradle Plugin | 8.12.0 | 9.4.0 | ⛔ ADR-0011 requires stable detekt support for AGP 9 before this move. | 1 | 2026-09-15 | [AGP 9.4 release notes](https://developer.android.com/build/releases/agp-9-4-0-release-notes) |
| Kotlin | 2.2.0 | 2.4.20 | ⛔ Re-resolve with the compiler/build foundation; detekt type-resolution compatibility remains part of the gate. | 2 | 2026-09-15 | [Kotlin releases](https://kotlinlang.org/docs/releases.html) |
| KSP | 2.3.12 | 2.3.12 | ✅ Current; merged in PR #155. | 2 | 2026-09-15 | [KSP releases](https://github.com/google/ksp/releases) |
| detekt | 1.23.8 | 1.23.8 | ✅ Latest stable. detekt 2.0 remains alpha; ADR-0011 waits for a stable 2.x release with AGP 9 support. | 2 / gate | 2026-09-15 | [detekt changelog](https://detekt.dev/changelog/) |
| Kover | 0.9.9 | 0.9.9 | ✅ Current. | 2 | 2026-09-15 | [Kover Gradle plugin](https://plugins.gradle.org/plugin/org.jetbrains.kotlinx.kover) |
| ktlint Gradle plugin | 14.2.0 | 14.2.0 | ✅ Current; merged in PR #156. | 2 | 2026-09-15 | [ktlint Gradle releases](https://github.com/JLLeitschuh/ktlint-gradle/releases) |
| ktlint engine | 1.8.0 | 1.8.0 | ✅ Current; merged in PR #157. | 2 | 2026-09-15 | [ktlint releases](https://github.com/ktlint/ktlint/releases) |
| Protobuf Gradle plugin | 0.10.0 | 0.10.0 | ✅ Current; merged in PR #158. | 2 | 2026-09-15 | [protobuf-gradle-plugin releases](https://github.com/google/protobuf-gradle-plugin/releases) |

> **Compatibility frontier:** `Latest stable` is recorded per component and does not imply that those versions form a supported stack together. As checked on 2026-09-15, Kotlin Gradle Plugin 2.4.20's fully supported range tops out at Gradle 9.7.0 and AGP 9.3.1, while the independently latest releases are Gradle 9.7.1 and AGP 9.4.0. Phase 1 must re-resolve the latest **mutually compatible** stable tuple before implementation. ADR-0011 currently blocks the AGP 9/API 37 move in any case.

## Android platform, SDK and JDK toolchain

| Component | Current in BookWave | Latest stable | Status / next action | Phase | Last checked | Authoritative source |
| --- | --- | --- | --- | --- | --- | --- |
| compileSdk | API 36 | API 37 (Android 17) | ⛔ ADR-0011 keeps BookWave on API 36 until stable detekt/AGP 9 compatibility is available. | 3 | 2026-09-15 | [Android 17](https://developer.android.com/about/versions/17) |
| targetSdk | API 36 | API 37 (Android 17) | ⛔ Same foundation gate; target 37 also requires a dedicated Android behavior review/device pass. | 3 | 2026-09-15 | [Android 17 behavior changes](https://developer.android.com/about/versions/17/behavior-changes-17) |
| minSdk | API 26 | N/A — product support policy | 🎯 Preserve API 26 unless a product decision changes supported devices. | Product policy | 2026-09-15 | [`PRODUCT_SPEC.md`](PRODUCT_SPEC.md) |
| Android SDK Build Tools | 36.0.0 | 36.0.0 | ✅ Current/default stable toolset for the current/latest AGP documentation. | 8 | 2026-09-15 | [Build Tools release notes](https://developer.android.com/tools/releases/build-tools) |
| Android command-line tools | build 15859902 | build 15859902 | ✅ Current pinned download/checksum. | 8 | 2026-09-15 | [Android Studio / command-line tools](https://developer.android.com/studio) |
| Android Platform Tools | `sdkmanager "platform-tools"` (not exact-pinned) | 37.0.1 | ↔️ Dynamic. Phase 8 should decide whether to keep the SDK-manager moving package or record/pin a resolved revision. | 8 | 2026-09-15 | [Platform Tools release notes](https://developer.android.com/tools/releases/platform-tools) |
| GitHub CI JDK | Temurin 17 major line | Temurin 26.0.2.1 | 🎯 Intentional minimum-runtime lane. Current 17.x security patch is 17.0.20.1; do not replace this lane merely because a newer feature JDK exists. | 8 | 2026-09-15 | [Adoptium CSPU](https://adoptium.net/news/2026/09/eclipse-temurin-8u504-110321-170201-210121-25041-26021-available) |
| Codex JDK | Temurin 21 major line | Temurin 26.0.2.1 | 🎯 JDK 21 is the newest fully verified BookWave baseline from the existing compatibility probe. Current 21.x security patch is 21.0.12.1; re-probe modern JDKs after the foundation migration. | 8 | 2026-09-15 | [Adoptium CSPU](https://adoptium.net/news/2026/09/eclipse-temurin-8u504-110321-170201-210121-25041-26021-available) |

## AndroidX, Jetpack and Compose

| Version-catalog key / component | Current in BookWave | Latest stable | Status / next action | Phase | Last checked | Authoritative source |
| --- | --- | --- | --- | --- | --- | --- |
| `androidxActivity` | 1.12.4 | 1.13.0 | ⬆️ Update in the Phase 3 AndroidX/Compose slice. | 3 | 2026-09-15 | [Activity releases](https://developer.android.com/jetpack/androidx/releases/activity) |
| `androidxAnnotation` | 1.10.0 | 1.10.0 | ✅ Current. | 3 | 2026-09-15 | [AndroidX versions](https://developer.android.com/jetpack/androidx/versions) |
| `androidxCore` | 1.17.0 | 1.19.0 | ⬆️ Update in Phase 3. | 3 | 2026-09-15 | [Core releases](https://developer.android.com/jetpack/androidx/releases/core) |
| `androidxDatastore` | 1.2.1 | 1.2.1 | ✅ Current; merged in PR #154. | 4 | 2026-09-15 | [DataStore releases](https://developer.android.com/jetpack/androidx/releases/datastore) |
| `androidxHiltNavigationCompose` | 1.3.0 | 1.4.0 | ⛔ Compose artifacts in 1.4.0 use compileSdk 37 and require AGP 9.2+, so this follows the API 37/AGP gate. | 3 | 2026-09-15 | [AndroidX Hilt releases](https://developer.android.com/jetpack/androidx/releases/hilt) |
| `androidxLifecycle` | 2.10.0 | 2.11.0 | ⬆️ Phase 3 AndroidX/Compose compatibility slice. | 3 | 2026-09-15 | [Lifecycle releases](https://developer.android.com/jetpack/androidx/releases/lifecycle) |
| `androidxNavigation` | 2.9.8 | 2.10.1 | ⛔ Navigation Compose 2.10 moved its Compose compileSdk to API 37/AGP 9.2+, so this follows the platform gate. | 3 | 2026-09-15 | [Navigation releases](https://developer.android.com/jetpack/androidx/releases/navigation) |
| `androidxRoom` | 2.7.2 | 2.8.5 | 🔁 Persistence migration with schema/migration verification; keep separate from unrelated library bumps. | 4 | 2026-09-15 | [Room releases](https://developer.android.com/jetpack/androidx/releases/room) |
| `androidxTestCore` | 1.7.0 | 1.7.0 | ✅ Current. | 7 | 2026-09-15 | [AndroidX Test releases](https://developer.android.com/jetpack/androidx/releases/test) |
| `androidxTestExt` | 1.3.0 | 1.3.0 | ✅ Current. | 7 | 2026-09-15 | [AndroidX Test releases](https://developer.android.com/jetpack/androidx/releases/test) |
| `androidxTestRunner` | 1.7.0 | 1.7.0 | ✅ Current. | 7 | 2026-09-15 | [AndroidX Test releases](https://developer.android.com/jetpack/androidx/releases/test) |
| `androidxBenchmark` | 1.3.4 | 1.5.0 | ⬆️ Update in the dedicated test/benchmark phase. | 7 | 2026-09-15 | [Benchmark releases](https://developer.android.com/jetpack/androidx/releases/benchmark) |
| `androidxUiAutomator` | 2.4.0 | 2.4.0 | ✅ Current. | 7 | 2026-09-15 | [UI Automator releases](https://developer.android.com/jetpack/androidx/releases/test-uiautomator) |
| `androidxWork` | 2.11.2 | 2.11.2 | ✅ Current. | 4 | 2026-09-15 | [WorkManager releases](https://developer.android.com/jetpack/androidx/releases/work) |
| `composeBom` | 2025.06.01 | 2026.08.00 | ⛔ Large Compose jump; execute with Phase 3 platform/AGP compatibility and device UI regression coverage. | 3 | 2026-09-15 | [Compose BOM](https://developer.android.com/develop/ui/compose/bom) |
| `media3` | 1.11.0 | 1.11.0 | ✅ Current. | 4 | 2026-09-15 | [Media3 releases](https://developer.android.com/jetpack/androidx/releases/media3) |

## Dependency injection

| Version-catalog key / component | Current in BookWave | Latest stable | Status / next action | Phase | Last checked | Authoritative source |
| --- | --- | --- | --- | --- | --- | --- |
| `hilt` (Dagger/Hilt) | 2.58 | 2.60.1 | ⬆️ Dedicated Phase 4 DI upgrade after compiler/platform compatibility is established. | 4 | 2026-09-15 | [Dagger releases](https://github.com/google/dagger/releases) |
| `hiltExt` (AndroidX Hilt) | 1.3.0 | 1.4.0 | ⛔ Same AndroidX Hilt API 37/AGP 9.2+ gate as navigation-compose. | 3/4 | 2026-09-15 | [AndroidX Hilt releases](https://developer.android.com/jetpack/androidx/releases/hilt) |
| `javaxInject` | 1 | 1 | ✅ Current/canonical legacy `javax.inject` artifact; reassess only as part of the Hilt/DI migration. | 5 compatibility | 2026-09-15 | [Maven Central](https://central.sonatype.com/artifact/javax.inject/javax.inject) |

## Kotlin runtime, serialization and network stack

| Version-catalog key / component | Current in BookWave | Latest stable | Status / next action | Phase | Last checked | Authoritative source |
| --- | --- | --- | --- | --- | --- | --- |
| `kotlinxCoroutines` | 1.10.2 | 1.11.0 | ⬆️ Phase 5 runtime update with cancellation/concurrency regression coverage. | 5 | 2026-09-15 | [kotlinx.coroutines releases](https://github.com/Kotlin/kotlinx.coroutines/releases) |
| `kotlinxSerialization` | 1.8.1 | 1.11.0 | ⬆️ Phase 5; 1.12.0-RC is excluded as prerelease. | 5 | 2026-09-15 | [kotlinx.serialization releases](https://github.com/Kotlin/kotlinx.serialization/releases) |
| `okhttp` | 4.12.0 | 5.5.0 | 🔁 Major network-stack migration; keep contract/TLS/WebSocket/download tests green. | 5 | 2026-09-15 | [OkHttp changelog](https://square.github.io/okhttp/changelogs/changelog/) |
| `protobuf` | 4.31.1 | 4.36.1 (Protobuf 36.1) | ⬆️ Phase 5 runtime/protoc update with serialization compatibility checks. | 5 | 2026-09-15 | [Protobuf releases](https://github.com/protocolbuffers/protobuf/releases) |
| `retrofit` | 2.11.0 | 3.0.0 | 🔁 Major network-stack migration, preferably coordinated with OkHttp/converter evidence but not hidden in an unrelated PR. | 5 | 2026-09-15 | [Retrofit changelog](https://github.com/square/retrofit/blob/trunk/CHANGELOG.md) |
| `retrofitKotlinxSerialization` | 1.0.0 | 1.0.0 (final; project archived) | 🔁 Do not chase a nonexistent newer release; migrate from the archived Jake Wharton converter to Retrofit's maintained first-party Kotlin serialization converter during the Retrofit migration. | 5 | 2026-09-15 | [Archived converter repository](https://github.com/JakeWharton/retrofit2-kotlinx-serialization-converter) |

## Images and visual effects

| Version-catalog key / component | Current in BookWave | Latest stable | Status / next action | Phase | Last checked | Authoritative source |
| --- | --- | --- | --- | --- | --- | --- |
| `coil` | 2.7.0 | 3.6.2 | 🔁 Major Coil migration; review request/cache APIs and offline cover behavior. | 6 | 2026-09-15 | [Coil changelog](https://coil-kt.github.io/coil/changelog/) |
| `haze` | 1.6.10 | 1.7.2 | ⬆️ Phase 6. Haze 2.x is alpha and therefore excluded. | 6 | 2026-09-15 | [Haze releases](https://github.com/chrisbanes/haze/releases) |

## Test libraries

| Version-catalog key / component | Current in BookWave | Latest stable | Status / next action | Phase | Last checked | Authoritative source |
| --- | --- | --- | --- | --- | --- | --- |
| `junit4` | 4.13.2 | 4.13.2 | ✅ Latest JUnit 4. A JUnit 5/6 migration is a separate architecture decision. | 7 | 2026-09-15 | [JUnit 4 releases](https://github.com/junit-team/junit4/releases) |
| `robolectric` | 4.15.1 | 4.16.1 | ⬆️ Update in Phase 7; prerelease 4.17 builds are excluded. | 7 | 2026-09-15 | [Robolectric releases](https://github.com/robolectric/robolectric/releases) |
| `turbine` | 1.2.1 | 1.2.1 | ✅ Current. | 7 | 2026-09-15 | [Turbine releases](https://github.com/cashapp/turbine/releases) |

## CI, security and auxiliary tooling

GitHub workflows intentionally use supported **major aliases** today. The exact stable release is still recorded here so drift is visible. A future security-hardening decision may choose immutable commit SHAs instead; that is separate from merely staying on the latest stable major.

| Component | Current in BookWave | Latest stable | Status / next action | Phase | Last checked | Authoritative source |
| --- | --- | --- | --- | --- | --- | --- |
| `actions/checkout` | `@v7` | 7.0.1 | ✅ Current major alias; Phase 8 should decide whether to SHA-pin. | 8 | 2026-09-15 | [checkout releases](https://github.com/actions/checkout/releases) |
| `actions/setup-java` | `@v6` | 6.0.1 | ✅ Current major alias; Phase 8 should decide whether to SHA-pin. | 8 | 2026-09-15 | [setup-java releases](https://github.com/actions/setup-java/releases) |
| `actions/setup-node` | `@v7` | 7.0.0 | ✅ Current major alias; Phase 8 should decide whether to SHA-pin. | 8 | 2026-09-15 | [setup-node releases](https://github.com/actions/setup-node/releases) |
| `actions/upload-artifact` | `@v7` | 7.0.1 | ✅ Current major alias; Phase 8 should decide whether to SHA-pin. | 8 | 2026-09-15 | [upload-artifact releases](https://github.com/actions/upload-artifact/releases) |
| `actions/download-artifact` | `@v8` | 8.0.1 | ✅ Current major alias; Phase 8 should decide whether to SHA-pin. | 8 | 2026-09-15 | [download-artifact releases](https://github.com/actions/download-artifact/releases) |
| `gradle/actions/setup-gradle` | `@v6` | 6.3.0 | ✅ Current major alias. | 8 | 2026-09-15 | [Gradle Actions releases](https://github.com/gradle/actions/releases) |
| `gradle/actions/wrapper-validation` | `@v6` | 6.3.0 | ✅ Current major alias. | 8 | 2026-09-15 | [Gradle Actions releases](https://github.com/gradle/actions/releases) |
| Gitleaks — Codex bootstrap | 8.30.1 | 8.30.1 | ✅ Current and checksum-pinned. | 8 | 2026-09-15 | [Gitleaks releases](https://github.com/gitleaks/gitleaks/releases) |
| Gitleaks — PR workflow | 8.24.0 | 8.30.1 | ⬆️ Known tooling divergence; reconcile in a dedicated Phase 8 PR and pin the downloaded checksum/version consistently. | 8 | 2026-09-15 | [Gitleaks releases](https://github.com/gitleaks/gitleaks/releases) |
| Node.js — APK/Loopbound workflow | 22 major line (latest 22.x: 22.23.2 LTS) | 26.8.2 Current; 24.21.0 LTS | ⬆️ Phase 8 should deliberately select a supported LTS target (normally 24.x) rather than blindly moving CI to the non-LTS Current line. `actions/setup-node` currently resolves the moving 22.x line. | 8 | 2026-09-15 | [Node.js downloads](https://nodejs.org/en/download) |
| npm — APK/Loopbound workflow | Not pinned separately; follows Node 22 distribution | 11.19.1 with Node 26.8.2; 11.19.0 with Node 24.21.0 LTS | ↔️ Keep coupled to the selected Node runtime unless a repository need requires an explicit npm pin. | 8 | 2026-09-15 | [Node.js downloads](https://nodejs.org/en/download) |
| Python runtime — launcher asset generator | Not pinned/documented | 3.14.7 | ⬆️ Policy gap found during the 2026-09-15 audit. Define a supported/pinned Python baseline for the asset utility in Phase 8. | 8 | 2026-09-15 | [Python source releases](https://www.python.org/getit/source/) |
| NumPy — launcher asset generator | 2.3.5 | 2.5.3 | ⬆️ Auxiliary tooling update; verify generated launcher assets remain byte/visual-equivalent where expected. | 8 | 2026-09-15 | [NumPy releases](https://numpy.org/news/) |
| Pillow — launcher asset generator | 12.3.0 | 12.3.0 | ✅ Current. | 8 | 2026-09-15 | [Pillow on PyPI](https://pypi.org/project/Pillow/) |
| OSV vulnerability scan | Service `querybatch` API; no scanner binary pin | N/A — service API | ↔️ No binary version to bump; retain fail-closed behavior and periodically review the integration. | 8 | 2026-09-15 | [OSV API](https://google.github.io/osv.dev/api/) |

## Dynamic / intentionally non-versioned external inputs

These are part of the reproducibility surface but do not have a meaningful `current -> latest stable` comparison in the repository today.

| Input | Repository selection | Treatment | Last checked |
| --- | --- | --- | --- |
| GitHub-hosted runner image | `ubuntu-latest` | ↔️ Moving GitHub runner alias. Review runner-image migrations in Phase 8; do not pretend it is an exact repository pin. | 2026-09-15 |
| Audiobookshelf contract-capture fixture | `ghcr.io/advplyr/audiobookshelf:latest` | ↔️ Intentionally dynamic contract fixture. If reproducible historical captures become necessary, pin a digest/version in a dedicated contract-fixture change. | 2026-09-15 |
| Android Platform Tools install | `sdkmanager "platform-tools"` | ↔️ Moving SDK package; latest resolved upstream revision is recorded in the Android toolchain table above. | 2026-09-15 |

## Completed BW-DEP-01 update history

Append to this table whenever a tracked migration slice merges. The live tables above remain the source of truth for the current version.

| Date | PR | Component | From | To | Result |
| --- | --- | --- | --- | --- | --- |
| 2026-09-14 | #154 | AndroidX DataStore | 1.1.7 | 1.2.1 | ✅ Latest stable reached |
| 2026-09-14 | #155 | KSP | 2.3.11 | 2.3.12 | ✅ Latest stable reached |
| 2026-09-15 | #156 | ktlint Gradle plugin | 12.3.0 | 14.2.0 | ✅ Latest stable reached |
| 2026-09-15 | #157 | ktlint engine | 1.5.0 | 1.8.0 | ✅ Latest stable reached |
| 2026-09-15 | #158 | Protobuf Gradle plugin | 0.9.5 | 0.10.0 | ✅ Latest stable reached |

## Update discipline for future PRs

Every PR that changes a tracked version should, before merge:

1. change the actual source-of-truth pin/configuration;
2. update the matching **Current in BookWave** cell here;
3. re-check the upstream stable release and refresh **Latest stable** + **Last checked** for that row;
4. update **Status / next action** and phase/gate notes when the upgrade changes what is now possible;
5. append a row to **Completed BW-DEP-01 update history** for #135 migration work; and
6. update [`docs/latest-stable-upgrade-plan.md`](docs/latest-stable-upgrade-plan.md) only when sequencing, compatibility gates, validation requirements or phase ownership changes.

That separation is deliberate: **this file owns live versions; the roadmap owns migration strategy.**