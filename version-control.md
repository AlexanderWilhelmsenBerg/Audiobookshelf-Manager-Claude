# BookWave version control

> Canonical live ledger for repository-owned toolchains, direct dependencies, frameworks, test libraries,
> CI actions and auxiliary build tooling.

**Last full stable-version check:** 2026-09-15  
**Repository baseline checked:** `main` at `5ba6f7a079259fc4101cf69dc832e53286f1cf34`
**Upgrade roadmap:** [`docs/latest-stable-upgrade-plan.md`](docs/latest-stable-upgrade-plan.md)  
**Primary migration issue:** #135 — `[BW-DEP-01] Execute staged latest-stable toolchain and dependency migration`

## How to maintain this file

This file is the quick answer to **“what version are we on, what is the newest stable version, and what is left to do?”**

- Update this file in the **same PR** whenever a tracked version, runtime, SDK level, GitHub Action or pinned tool changes.
- Re-check the affected row against its authoritative upstream source on every version-changing PR and update **Last checked**.
- A full dependency-health review should refresh every row and the **Last full stable-version check** date.
- **Latest stable** excludes alpha, beta, RC, milestone, preview, EAP, dev and snapshot builds.
- **Latest stable is not automatically the approved next version.** Compatibility gates and migration notes still apply.
- `gradle/libs.versions.toml` remains the source of truth for direct Gradle/Maven pins. This file mirrors those pins for planning/status visibility; when Gradle conflict resolution ships a newer transitive version than the direct pin, record both explicitly.
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
| Gradle wrapper | 8.14.5 | 9.7.1 | 🎯 Current on the latest Gradle 8.14 maintenance release while ADR-0011 gates the Gradle 9 / AGP 9 foundation. The 8.14.5 binary distribution is SHA-256 pinned in the wrapper properties. | 1 | 2026-09-15 | [Gradle 8.14.5 release notes](https://docs.gradle.org/8.14.5/release-notes.html) |
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
| GitHub CI JDK | Temurin 17 major line | Temurin 26.0.2.1 | 🎯 Intentional minimum-runtime lane. Current 17.x security patch is 17.0.20.1; keep this lane while Java 17 remains the repository bytecode/minimum Gradle runtime baseline. | 8 | 2026-09-15 | [Adoptium CSPU](https://adoptium.net/news/2026/09/eclipse-temurin-8u504-110321-170201-210121-25041-26021-available) |
| Codex JDK | Temurin 21 major line | Temurin 26.0.2.1 | 🎯 JDK 21 is the newest fully verified BookWave baseline. Gradle 8.14.5 officially runs through Java 24; Java 25 requires Gradle 9.1+ and Java 26 requires Gradle 9.4+, so re-probe modern JDKs only after the gated build-foundation migration. | 8 | 2026-09-15 | [Gradle Java compatibility](https://docs.gradle.org/current/userguide/compatibility.html) |

## AndroidX, Jetpack and Compose

| Version-catalog key / component | Current in BookWave | Latest stable | Status / next action | Phase | Last checked | Authoritative source |
| --- | --- | --- | --- | --- | --- | --- |
| `androidxActivity` | 1.13.0 | 1.13.0 | ✅ Current; merged in PR #161 after full CI and focused device smoke passed. It resolves Core/Core-KTX 1.18.0 transitively on the current API-36 foundation. | 3 | 2026-09-15 | [Activity releases](https://developer.android.com/jetpack/androidx/releases/activity) |
| `androidxAnnotation` | 1.10.0 | 1.10.0 | ✅ Current. | 3 | 2026-09-15 | [AndroidX versions](https://developer.android.com/jetpack/androidx/versions) |
| `androidxCore` | 1.17.0 direct pin; 1.18.0 resolved via Activity 1.13.0 | 1.19.0 | ⛔ Published Core 1.19.0 consumer requirements need compileSdk 37 and AGP 9.1+, so the explicit pin stays put until the ADR-0011 platform/build-foundation gate clears. | 3 | 2026-09-15 | [Core releases](https://developer.android.com/jetpack/androidx/releases/core) |
| `androidxDatastore` | 1.2.1 | 1.2.1 | ✅ Current; merged in PR #154. | 4 | 2026-09-15 | [DataStore releases](https://developer.android.com/jetpack/androidx/releases/datastore) |
| `androidxHiltNavigationCompose` | 1.3.0 | 1.4.0 | ⛔ Compose artifacts in 1.4.0 use compileSdk 37 and require AGP 9.2+, so this follows the API 37/AGP gate. | 3 | 2026-09-15 | [AndroidX Hilt releases](https://developer.android.com/jetpack/androidx/releases/hilt) |
| `androidxLifecycle` | 2.10.0 | 2.11.0 | ⛔ Lifecycle 2.11 Compose artifacts compile against API 37 and require AGP 9.2+, so this follows the ADR-0011 platform/build-foundation gate. | 3 | 2026-09-15 | [Lifecycle releases](https://developer.android.com/jetpack/androidx/releases/lifecycle) |
| `androidxNavigation` | 2.9.8 | 2.10.1 | ⛔ Navigation Compose 2.10 moved its Compose compileSdk to API 37/AGP 9.2+, so this follows the platform gate. | 3 | 2026-09-15 | [Navigation releases](https://developer.android.com/jetpack/androidx/releases/navigation) |
| `androidxRoom` | 2.8.5 | 2.8.5 | 🎯 Latest stable target in the active Room-only Phase 4 slice. Room 2.8 raises Android minSdk to 23 and the Room Gradle Plugin floor to AGP 8.4; BookWave minSdk 26 / AGP 8.12 remain compatible. Preserve all committed schemas and prove the database/migration suite unchanged. | 4 | 2026-09-15 | [Room releases](https://developer.android.com/jetpack/androidx/releases/room) |
| `androidxTestCore` | 1.7.0 | 1.7.0 | ✅ Current. | 7 | 2026-09-15 | [AndroidX Test releases](https://developer.android.com/jetpack/androidx/releases/test) |
| `androidxTestExtJunit` | 1.3.0 | 1.3.0 | ✅ Current. | 7 | 2026-09-15 | [AndroidX Test releases](https://developer.android.com/jetpack/androidx/releases/test) |
| `androidxTestRunner` | 1.7.0 | 1.7.0 | ✅ Current. | 7 | 2026-09-15 | [AndroidX Test releases](https://developer.android.com/jetpack/androidx/releases/test) |
| `androidxTestRules` | 1.7.0 | 1.7.0 | ✅ Current. | 7 | 2026-09-15 | [AndroidX Test releases](https://developer.android.com/jetpack/androidx/releases/test) |
| `androidxTestUiAutomator` | 2.4.0 | 2.4.0 | ✅ Current. | 7 | 2026-09-15 | [UI Automator releases](https://developer.android.com/jetpack/androidx/releases/test-uiautomator) |
| `androidxBenchmark` | 1.4.1 | 1.4.1 | ✅ Current. | 7 | 2026-09-15 | [Benchmark releases](https://developer.android.com/jetpack/androidx/releases/benchmark) |
| `androidxWork` | 2.10.4 | 2.11.0 | ⛔ WorkManager 2.11 raises minSdk to 23 (compatible) but its artifacts use compileSdk 37, so this follows the API 37/AGP gate before the migration-specific behavior pass. | 4 | 2026-09-15 | [WorkManager releases](https://developer.android.com/jetpack/androidx/releases/work) |
| `androidxMedia3` | 1.9.0 | 1.9.0 | ✅ Current. | 4 | 2026-09-15 | [Media3 releases](https://developer.android.com/jetpack/androidx/releases/media3) |
| `androidxComposeBom` | 2026.09.00 | 2026.09.00 | ✅ Current. | 3 | 2026-09-15 | [Compose BOM mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping) |
| `androidxComposeMaterial3Adaptive` | 1.2.0 | 1.2.0 | ✅ Current. | 3 | 2026-09-15 | [Adaptive releases](https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive) |
| `androidxComposeMaterial3WindowSizeClass` | 1.3.2 | 1.3.2 | ✅ Current. | 3 | 2026-09-15 | [Material3 releases](https://developer.android.com/jetpack/androidx/releases/compose-material3) |
| `androidxProfileinstaller` | 1.4.1 | 1.4.1 | ✅ Current. | 8 compatibility | 2026-09-15 | [ProfileInstaller releases](https://developer.android.com/jetpack/androidx/releases/profileinstaller) |
| `androidxStartup` | 1.2.0 | 1.2.0 | ✅ Current. | 8 compatibility | 2026-09-15 | [Startup releases](https://developer.android.com/jetpack/androidx/releases/startup) |
| `androidxTestOrchestrator` | 1.6.1 | 1.6.1 | ✅ Current. | 7 | 2026-09-15 | [AndroidX Test releases](https://developer.android.com/jetpack/androidx/releases/test) |
| `androidxTracing` | 1.3.0 | 1.3.0 | ✅ Current. | 8 compatibility | 2026-09-15 | [Tracing releases](https://developer.android.com/jetpack/androidx/releases/tracing) |
| `androidxWindow` | 1.5.0 | 1.5.0 | ✅ Current. | 3 | 2026-09-15 | [Window releases](https://developer.android.com/jetpack/androidx/releases/window) |
| `androidxCoreSplashscreen` | 1.0.1 | 1.0.1 | ✅ Current. | 3 | 2026-09-15 | [SplashScreen releases](https://developer.android.com/jetpack/androidx/releases/core) |
| `coil` | 2.7.0 | 3.6.2 | 🔁 Major Coil migration; review request/cache APIs and offline cover behavior. | 6 | 2026-09-15 | [Coil changelog](https://coil-kt.github.io/coil/changelog/) |
| `detekt` | 1.23.8 | 1.23.8 | ✅ Latest stable. detekt 2.0 remains alpha; ADR-0011 waits for a stable 2.x release with AGP 9 support. | 2 / gate | 2026-09-15 | [detekt changelog](https://detekt.dev/changelog/) |
| `haze` | 1.6.10 | 1.7.2 | ⬆️ Phase 6. Haze 2.x is alpha and therefore excluded. | 6 | 2026-09-15 | [Haze releases](https://github.com/chrisbanes/haze/releases) |
| `hilt` | 2.58 | 2.59.1 | ⬆️ Re-check with the Kotlin/AGP migration because Hilt's compiler/plugin compatibility matters more than version recency. | 3/4 | 2026-09-15 | [Dagger releases](https://github.com/google/dagger/releases) |
| `javaxInject` | 1 | 1 | ✅ Current/canonical legacy `javax.inject` artifact; reassess only as part of the Hilt/DI migration. | 5 compatibility | 2026-09-15 | [Maven Central](https://central.sonatype.com/artifact/javax.inject/javax.inject) |

## Kotlin runtime, serialization and network stack

| Version-catalog key / component | Current in BookWave | Latest stable | Status / next action | Phase | Last checked | Authoritative source |
| --- | --- | --- | --- | --- | --- | --- |
| `kotlinxCoroutines` | 1.11.0 | 1.11.0 | ✅ Latest stable reached in the active Phase 5 slice after cancellation/concurrency regression coverage. Upstream 1.11.0 is built with Kotlin 2.2.20; BookWave remains on Kotlin 2.2.0 pending the gated compiler migration, so the full BookWave gate is required evidence for this compatibility slice. | 5 | 2026-09-15 | [kotlinx.coroutines releases](https://github.com/Kotlin/kotlinx.coroutines/releases) |
| `kotlinxSerialization` | 1.9.0 | 1.11.0 | 🎯 1.9.0 is the newest stable release aligned with BookWave’s current Kotlin 2.2.0 compiler line. Upstream 1.10.0 moved to Kotlin 2.3.0 and 1.11.0 is based on Kotlin 2.3.20, so those remain compiler-gated until Phase 1/2 re-resolves Kotlin. | 5 | 2026-09-15 | [kotlinx.serialization releases](https://github.com/Kotlin/kotlinx.serialization/releases) |
| `okhttp` | 4.12.0 | 5.5.0 | 🔁 Major network-stack migration deliberately left out of the converter-retirement slice; keep contract/TLS/WebSocket/download tests green when it moves. | 5 | 2026-09-16 | [OkHttp changelog](https://square.github.io/okhttp/changelogs/changelog/) |
| `protobuf` | 4.36.1 | 4.36.1 (Protobuf 36.1) | ✅ Latest stable reached in merged PR #166 with matched Java/Kotlin-lite runtime and protoc. The focused Proto DataStore suite and full classpath rerun gate are the compatibility evidence; the reported 36.1 Bazel prebuilt-tool integrity issue is outside BookWave's Gradle/Maven protoc path. | 5 | 2026-09-16 | [Protobuf releases](https://github.com/protocolbuffers/protobuf/releases) |
| `retrofit` | 3.0.0 | 3.0.0 | ✅ Latest stable target in the active Retrofit-only Phase 5 slice. Upstream states that Retrofit 3.x maintains forward binary compatibility with 2.x and 3.0.0 moves Retrofit’s OkHttp baseline to 4.12, which BookWave already uses directly; OkHttp 5 remains a separate later migration. | 5 | 2026-09-16 | [Retrofit 3.0.0 release](https://github.com/square/retrofit/releases/tag/3.0.0) |
| Retrofit kotlinx.serialization converter | 3.0.0 (first-party; tracks `retrofit`) | 3.0.0 (first-party; tracks Retrofit) | ✅ Archived Jake Wharton 1.0.0 artifact was retired in merged PR #167. The active Retrofit 3 slice keeps the first-party converter aligned with Retrofit core at 3.0.0; OkHttp remains pinned separately at 4.12.0. | 5 | 2026-09-16 | [Retrofit 3.0.0 release](https://github.com/square/retrofit/releases/tag/3.0.0) |

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

| Component | Current in BookWave | Latest stable | Status / next action | Phase | Last checked | Authoritative source |
| --- | --- | --- | --- | --- | --- | --- |
| GitHub Actions checkout | `actions/checkout@v7` | v7 | ✅ Current mutable major alias; pinning by commit SHA is tracked as a separate hardening decision. | 8 | 2026-09-15 | [checkout releases](https://github.com/actions/checkout/releases) |
| GitHub Actions setup-java | `actions/setup-java@v6` | v6 | ✅ Current mutable major alias. | 8 | 2026-09-15 | [setup-java releases](https://github.com/actions/setup-java/releases) |
| GitHub Actions setup-node | `actions/setup-node@v6` | v6 | ✅ Current mutable major alias. | 8 | 2026-09-15 | [setup-node releases](https://github.com/actions/setup-node/releases) |
| GitHub Actions upload-artifact | `actions/upload-artifact@v7` | v7 | ✅ Current mutable major alias. | 8 | 2026-09-15 | [upload-artifact releases](https://github.com/actions/upload-artifact/releases) |
| GitHub Actions download-artifact | `actions/download-artifact@v8` | v8 | ✅ Current mutable major alias. | 8 | 2026-09-15 | [download-artifact releases](https://github.com/actions/download-artifact/releases) |
| Gradle Actions setup-gradle | `gradle/actions/setup-gradle@v6` | v6 | ✅ Current mutable major alias. | 8 | 2026-09-15 | [Gradle Actions releases](https://github.com/gradle/actions/releases) |
| Gradle Actions wrapper-validation | `gradle/actions/wrapper-validation@v6` | v6 | ✅ Current mutable major alias. | 8 | 2026-09-15 | [Gradle Actions releases](https://github.com/gradle/actions/releases) |
| Gitleaks | 8.28.0 in Codex; latest URL in PR CI | 8.28.0 | 🎯 Reconcile the mutable PR install with the pinned Codex bootstrap in Phase 8. | 8 | 2026-09-15 | [Gitleaks releases](https://github.com/gitleaks/gitleaks/releases) |

## Completed BW-DEP-01 update history

| Date | PR | Component | From | To | Result |
| --- | --- | --- | --- | --- | --- |
| 2026-09-14 | #154 | AndroidX DataStore | 1.1.7 | 1.2.1 | ✅ Latest stable reached |
| 2026-09-14 | #155 | KSP | 2.3.11 | 2.3.12 | ✅ Latest stable reached |
| 2026-09-15 | #156 | ktlint Gradle plugin | 12.3.0 | 14.2.0 | ✅ Latest stable reached |
| 2026-09-15 | #157 | ktlint engine | 1.5.0 | 1.8.0 | ✅ Latest stable reached |
| 2026-09-15 | #158 | Protobuf Gradle plugin | 0.9.5 | 0.10.0 | ✅ Latest stable reached |
| 2026-09-15 | #161 | AndroidX Activity | 1.12.4 | 1.13.0 | ✅ Latest stable reached |
| 2026-09-15 | #162 | Gradle wrapper | 8.14.3 | 8.14.5 | 🎯 Latest stable release in the accepted 8.14 maintenance line; Gradle 9 remains gated |
| 2026-09-15 | #163 | AndroidX Room | 2.7.2 | 2.8.5 | 🎯 Latest stable target; schema/version unchanged |
| 2026-09-15 | #164 | kotlinx.serialization | 1.8.1 | 1.9.0 | 🎯 Latest stable compatible with the current Kotlin 2.2.0 compiler line |
| 2026-09-15 | #165 | kotlinx.coroutines | 1.10.2 | 1.11.0 | ✅ Latest stable reached after full cancellation/concurrency rerun coverage on Kotlin 2.2.0 |
| 2026-09-16 | #166 | Protobuf runtime/protoc | 4.31.1 / 31.1 | 4.36.1 / 36.1 | ✅ Latest stable reached; application source and committed schemas unchanged |
| 2026-09-16 | #167 | Retrofit kotlinx.serialization converter | Jake Wharton 1.0.0 | Square 2.11.0 | ✅ Archived converter retired; first-party converter adopted without changing Retrofit or OkHttp major versions |

## Update discipline for future PRs

Every PR that changes a tracked version should, before merge:

1. change the actual source-of-truth pin/configuration;
2. update the matching **Current in BookWave** cell here;
3. re-check the upstream stable release and refresh **Latest stable** + **Last checked** for that row;
4. update **Status / next action** and phase/gate notes when the upgrade changes what is now possible;
5. append a row to **Completed BW-DEP-01 update history** for #135 migration work; and
6. update [`docs/latest-stable-upgrade-plan.md`](docs/latest-stable-upgrade-plan.md) only when sequencing, compatibility gates, validation requirements or phase ownership changes.

That separation is deliberate: **this file owns live versions; the roadmap owns migration strategy.**
