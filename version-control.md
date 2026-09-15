# BookWave live version ledger

> Canonical current-versus-latest tracker for direct BookWave dependency/toolchain pins, Android SDK tooling,
> CI actions and auxiliary build tooling.

**Last full stable-version check:** 2026-09-15  
**Repository baseline checked:** `main` at `50027ac5eb6b727477132ef291fb8adc8b22c1d2`  
**Upgrade roadmap:** [`docs/latest-stable-upgrade-plan.md`](docs/latest-stable-upgrade-plan.md)  
**Primary migration issue:** #135 — `[BW-DEP-01] Execute staged latest-stable toolchain and dependency migration`

## How to read this file

This file is the quick answer to **“what version are we on, what is the newest stable release, and what should happen next?”**

- Update the affected row in the same PR that changes a tracked version.
- Append the merged PR to the migration history after each staged slice lands.
- A full dependency-health review should refresh every row and the **Last full stable-version check** date.
- **Latest stable** excludes alpha, beta, RC, milestone, preview, EAP, dev and snapshot builds.
- **Latest stable is not automatically the approved next version.** Compatibility gates and migration notes still apply.
- `gradle/libs.versions.toml` remains the source of truth for direct Gradle/Maven pins. This file mirrors those pins for planning/status visibility; when Gradle conflict resolution ships a newer transitive version than the direct pin, record both explicitly.
- Direct repository-owned versions are tracked here. Transitive Maven artifacts are not individually listed; Gradle dependency verification, the dependency report/SBOM and vulnerability scanning cover that surface.
- Mutable aliases such as `actions/checkout@v7`, `ubuntu-latest`, `platform-tools`, and container `:latest` tags are called out explicitly instead of pretending the repository pins an exact version.

## Build, language and quality toolchain

| Component | Current in BookWave | Latest stable | Status / next action | Phase | Last checked | Authoritative source |
| --- | --- | --- | --- | --- | --- | --- |
| Gradle wrapper | 8.14.5 | 9.7.1 | 🎯 Current on the latest Gradle 8.14 maintenance release while ADR-0011 gates the Gradle 9 / AGP 9 foundation. The 8.14.5 binary distribution is SHA-256 pinned in the wrapper properties. | 1 | 2026-09-15 | [Gradle 8.14.5 release notes](https://docs.gradle.org/8.14.5/release-notes.html) |
| Android Gradle Plugin | 8.12.0 | 9.4.0 | ⛔ ADR-0011 requires stable detekt support for AGP 9 before this move. | 1 | 2026-09-15 | [AGP 9.4 release notes](https://developer.android.com/build/releases/agp-9-4-0-release-notes) |
| Kotlin | 2.2.0 | 2.4.20 | ⛔ Re-resolve with the compiler/build foundation; detekt type-resolution compatibility remains part of the gate. | 2 | 2026-09-15 | [Kotlin releases](https://kotlinlang.org/docs/releases.html) |
| KSP | 2.3.12 | 2.3.12 | ✅ Current; merged in PR #155. | 2 | 2026-09-15 | [KSP releases](https://github.com/google/ksp/releases) |
| ktlint Gradle plugin | 14.2.0 | 14.2.0 | ✅ Current; merged in PR #156. | 2 | 2026-09-15 | [ktlint Gradle releases](https://github.com/JLLeitschuh/ktlint-gradle/releases) |
| ktlint engine | 1.8.0 | 1.8.0 | ✅ Current; merged in PR #157. | 2 | 2026-09-15 | [ktlint releases](https://github.com/pinterest/ktlint/releases) |
| detekt | 1.23.8 | 1.23.8 stable / 2.0.0-alpha.1 prerelease | 🎯 Stay on stable 1.23.8; prerelease 2.x is excluded and ADR-0011 still depends on stable 2.x compatibility. | 2 / gate | 2026-09-15 | [detekt releases](https://github.com/detekt/detekt/releases) |
| Kover | 0.9.1 | 0.9.1 | ✅ Current. | 2 / 7 | 2026-09-15 | [Kover releases](https://github.com/Kotlin/kotlinx-kover/releases) |
| Protobuf Gradle plugin | 0.10.0 | 0.10.0 | ✅ Current; merged in PR #158. | 2 | 2026-09-15 | [protobuf-gradle-plugin releases](https://github.com/google/protobuf-gradle-plugin/releases) |

## Android platform and SDK tooling

| Component | Current in BookWave | Latest stable | Status / next action | Phase | Last checked | Authoritative source |
| --- | --- | --- | --- | --- | --- | --- |
| `compileSdk` | 36 | 37 | ⛔ ADR-0011 gates the API 37 / AGP 9 foundation. | 3 | 2026-09-15 | [Android 17 SDK](https://developer.android.com/about/versions/17/setup-sdk) |
| `targetSdk` | 36 | 37 | ⛔ Same gate as compileSdk, plus dedicated behavior/device migration when executed. | 3 | 2026-09-15 | [Android 17 behavior changes](https://developer.android.com/about/versions/17/behavior-changes-all) |
| `minSdk` | 26 | n/a product floor | ↔️ Do not raise merely because a newer library is available; requires a product/support decision. | Product | 2026-09-15 | [`PRODUCT_SPEC.md`](PRODUCT_SPEC.md) |
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
| `androidxCore` | 1.17.0 direct pin; 1.18.0 resolved via Activity 1.13.0 | 1.19.0 | ⛔ Published Core 1.19.0 consumer metadata requires compileSdk 37 and AGP 9.1+, so the explicit pin stays put until the ADR-0011 platform/build-foundation gate clears. | 3 | 2026-09-15 | [Core releases](https://developer.android.com/jetpack/androidx/releases/core) |
| `androidxDatastore` | 1.2.1 | 1.2.1 | ✅ Current; merged in PR #154. | 4 | 2026-09-15 | [DataStore releases](https://developer.android.com/jetpack/androidx/releases/datastore) |
| `androidxHiltNavigationCompose` | 1.3.0 | 1.4.0 | ⛔ Compose artifacts in 1.4.0 use compileSdk 37 and require AGP 9.2+, so this follows the API 37/AGP gate. | 3 | 2026-09-15 | [AndroidX Hilt releases](https://developer.android.com/jetpack/androidx/releases/hilt) |
| `androidxLifecycle` | 2.10.0 | 2.11.0 | ⛔ Lifecycle 2.11 Compose artifacts compile against API 37 and require AGP 9.2+, so this follows the ADR-0011 platform/build-foundation gate. | 3 | 2026-09-15 | [Lifecycle releases](https://developer.android.com/jetpack/androidx/releases/lifecycle) |
| `androidxNavigation` | 2.9.8 | 2.10.1 | ⛔ Navigation Compose 2.10 moved its Compose compileSdk to API 37/AGP 9.2+, so this follows the platform gate. | 3 | 2026-09-15 | [Navigation releases](https://developer.android.com/jetpack/androidx/releases/navigation) |
| `androidxRoom` | 2.8.5 | 2.8.5 | 🎯 Latest stable target in the active Room-only Phase 4 slice. Room 2.8 raises Android minSdk to 23 and the Room Gradle Plugin floor to AGP 8.4; BookWave minSdk 26 / AGP 8.12 remain compatible. Preserve all committed schemas and prove the existing database/migration suite unchanged. | 4 | 2026-09-15 | [Room releases](https://developer.android.com/jetpack/androidx/releases/room) |
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
| `hilt` (Dagger/Hilt) | 2.58 | 2.60.1 | ⛔ Dagger/Hilt 2.59+ makes AGP 9 a requirement when using the Hilt Gradle plugin, so 2.60.1 follows the ADR-0011 build-foundation gate. | 4 | 2026-09-15 | [Dagger releases](https://github.com/google/dagger/releases) |
| `hiltExt` (AndroidX Hilt) | 1.3.0 | 1.4.0 | ⛔ Same AndroidX Hilt API 37/AGP 9.2+ gate as navigation-compose. | 3/4 | 2026-09-15 | [AndroidX Hilt releases](https://developer.android.com/jetpack/androidx/releases/hilt) |
| `javaxInject` | 1 | 1 | ✅ Current/canonical legacy `javax.inject` artifact; reassess only as part of the Hilt/DI migration. | 5 compatibility | 2026-09-15 | [Maven Central](https://central.sonatype.com/artifact/javax.inject/javax.inject) |

## Kotlin runtime, serialization and network stack

| Version-catalog key / component | Current in BookWave | Latest stable | Status / next action | Phase | Last checked | Authoritative source |
| --- | --- | --- | --- | --- | --- | --- |
| `coroutines` | 1.10.2 | 1.11.0 | ⬆️ Runtime/async migration; test cancellation, Flow behavior and lifecycle ownership. | 5 | 2026-09-15 | [coroutines releases](https://github.com/Kotlin/kotlinx.coroutines/releases) |
| `serialization` | 1.8.1 | 1.11.0 | ⬆️ Runtime serialization migration; test persisted/contract payload compatibility. | 5 | 2026-09-15 | [serialization releases](https://github.com/Kotlin/kotlinx.serialization/releases) |
| `okhttp` | 4.12.0 | 5.5.0 | 🔁 Major network migration; verify REST, websocket, auth, TLS, cancellation and redaction. | 5 | 2026-09-15 | [OkHttp releases](https://github.com/square/okhttp/releases) |
| `protobuf` runtime / protoc | 4.31.1 | 4.36.1 | ⬆️ Runtime + code-generator move; keep runtime and protoc aligned and verify generated/source compatibility. | 5 | 2026-09-15 | [Protobuf releases](https://github.com/protocolbuffers/protobuf/releases) |
| `retrofit` | 2.11.0 | 3.0.0 | 🔁 Major network migration; coordinate with converter strategy and contract tests. | 5 | 2026-09-15 | [Retrofit releases](https://github.com/square/retrofit/releases) |
| `retrofitSerialization` | 1.0.0 | 1.0.0 | ⚠️ Archived third-party converter remains at its latest/final release; replace with Retrofit's first-party serializer when the network major migration executes. | 5 | 2026-09-15 | [Converter repository](https://github.com/JakeWharton/retrofit2-kotlinx-serialization-converter) |

## Visual libraries

| Version-catalog key / component | Current in BookWave | Latest stable | Status / next action | Phase | Last checked | Authoritative source |
| --- | --- | --- | --- | --- | --- | --- |
| `coil` | 2.7.0 | 3.6.2 | 🔁 Major migration; re-test authenticated cover fetching, caching, Compose rendering and offline behavior. | 6 | 2026-09-15 | [Coil releases](https://github.com/coil-kt/coil/releases) |
| `haze` | 1.6.10 | 1.7.2 | ⬆️ Dedicated visual-library slice; verify blur/fallback rendering and accessibility contrast on device. | 6 | 2026-09-15 | [Haze releases](https://github.com/chrisbanes/haze/releases) |

## Test and benchmark tooling

| Version-catalog key / component | Current in BookWave | Latest stable | Status / next action | Phase | Last checked | Authoritative source |
| --- | --- | --- | --- | --- | --- | --- |
| `junit` | 4.13.2 | 4.13.2 | ✅ Current. | 7 | 2026-09-15 | [JUnit 4 releases](https://github.com/junit-team/junit4/releases) |
| `mockwebserver` | 4.12.0 | 4.12.0 | 🎯 Keep aligned with the OkHttp 4 test stack until the OkHttp 5 migration. | 5/7 | 2026-09-15 | [OkHttp releases](https://github.com/square/okhttp/releases) |
| `robolectric` | 4.15.1 | 4.16.1 | ⬆️ Dedicated test-stack upgrade; rerun Compose/Robolectric screens and process-lifecycle tests. | 7 | 2026-09-15 | [Robolectric releases](https://github.com/robolectric/robolectric/releases) |
| `turbine` | 1.2.1 | 1.2.1 | ✅ Current. | 7 | 2026-09-15 | [Turbine releases](https://github.com/cashapp/turbine/releases) |

## CI, actions and auxiliary tooling

| Component | Current in BookWave | Latest stable | Status / next action | Phase | Last checked | Authoritative source |
| --- | --- | --- | --- | --- | --- | --- |
| `actions/checkout` | `@v7` | v7.0.0 | ✅ Current major alias; mutable major alias, not an immutable commit pin. | 8 | 2026-09-15 | [checkout releases](https://github.com/actions/checkout/releases) |
| `actions/setup-java` | `@v6` | v6.0.0 | ✅ Current major alias; mutable major alias. | 8 | 2026-09-15 | [setup-java releases](https://github.com/actions/setup-java/releases) |
| `gradle/actions/setup-gradle` | `@v6` | v6.0.0 | ✅ Current major alias; mutable major alias. | 8 | 2026-09-15 | [Gradle Actions releases](https://github.com/gradle/actions/releases) |
| `gradle/actions/wrapper-validation` | `@v6` | v6.0.0 | ✅ Current major alias; mutable major alias. | 8 | 2026-09-15 | [Gradle Actions releases](https://github.com/gradle/actions/releases) |
| `actions/upload-artifact` | `@v7` | v7.0.0 | ✅ Current major alias; mutable major alias. | 8 | 2026-09-15 | [upload-artifact releases](https://github.com/actions/upload-artifact/releases) |
| `actions/download-artifact` | `@v8` | v8.0.0 | ✅ Current major alias; mutable major alias. | 8 | 2026-09-15 | [download-artifact releases](https://github.com/actions/download-artifact/releases) |
| `actions/github-script` | `@v8` | v8.0.0 | ✅ Current major alias; mutable major alias. | 8 | 2026-09-15 | [github-script releases](https://github.com/actions/github-script/releases) |
| `gitleaks-action` | `@v2` running gitleaks 8.24.0 in the normal PR workflow | gitleaks 8.30.1 | ⬆️ Phase 8 security-tool refresh. | 8 | 2026-09-15 | [gitleaks releases](https://github.com/gitleaks/gitleaks/releases) |
| `github/codeql-action/upload-sarif` | `@v4` | v4.30.7 | ✅ Current major alias; exact patch drifts inside the alias. | 8 | 2026-09-15 | [CodeQL Action releases](https://github.com/github/codeql-action/releases) |
| `github/gh-aw` | `@v0.45.0` | v0.45.0 | ✅ Current immutable tag. | 8 | 2026-09-15 | [gh-aw releases](https://github.com/github/gh-aw/releases) |
| Gitleaks CLI in `scripts/codex/setup.sh` | 8.30.1 | 8.30.1 | ✅ Current exact pin + checksum. | 8 | 2026-09-15 | [gitleaks releases](https://github.com/gitleaks/gitleaks/releases) |
| Android command-line tools in Codex setup | build 15859902 | build 15859902 | ✅ Current exact download + checksum. | 8 | 2026-09-15 | [Android Studio downloads](https://developer.android.com/studio) |
| Codex environment bootstrap contract | `scripts/codex/setup.sh` | repository-owned | ✅ Current; re-run after every build-tool/JDK/SDK phase. | 8 | 2026-09-15 | [`scripts/codex/setup.sh`](scripts/codex/setup.sh) |

## Migration history

Append to this table whenever a tracked migration slice merges. The live tables above remain the source of truth for the current version.

| Date | PR | Component | From | To | Result |
| --- | --- | --- | --- | --- | --- |
| 2026-09-14 | #154 | AndroidX DataStore | 1.1.7 | 1.2.1 | ✅ Latest stable reached |
| 2026-09-14 | #155 | KSP | 2.3.11 | 2.3.12 | ✅ Latest stable reached |
| 2026-09-15 | #156 | ktlint Gradle plugin | 12.3.0 | 14.2.0 | ✅ Latest stable reached |
| 2026-09-15 | #157 | ktlint engine | 1.5.0 | 1.8.0 | ✅ Latest stable reached |
| 2026-09-15 | #158 | Protobuf Gradle plugin | 0.9.5 | 0.10.0 | ✅ Latest stable reached |
| 2026-09-15 | #161 | AndroidX Activity | 1.12.4 | 1.13.0 | ✅ Latest stable reached |
| 2026-09-15 | #162 | Gradle wrapper | 8.14.3 | 8.14.5 | 🎯 Latest stable release in the accepted 8.14 maintenance line; Gradle 9 remains gated |

## Update discipline for future PRs

Every PR that changes a tracked version should, before merge:

1. refresh the target's latest stable release from an authoritative source;
2. update the affected row above with the actual current and latest stable values;
3. record compatibility/gating decisions instead of hiding them in chat;
4. update [`docs/latest-stable-upgrade-plan.md`](docs/latest-stable-upgrade-plan.md) when phase ordering, compatibility or migration steps change;
5. append the merged PR to **Migration history**;
6. keep `gradle/libs.versions.toml` as the source of truth for actual Maven/Gradle pins.

Do not duplicate the full live table into the roadmap. The roadmap owns sequencing and evidence; this ledger owns the numerical state.
