# BookWave version control

> Canonical live ledger for repository-owned toolchains, direct dependencies, frameworks, test libraries,
> CI actions and auxiliary build tooling.

**Last full stable-version check:** 2026-09-15  
**Repository/planning state reconciled:** `main` at `a20bb5b9` on 2026-10-04; release-version rows retain their own upstream `Last checked` dates. This reconciliation is not a fresh latest-stable discovery run.
**Upgrade roadmap:** [`docs/latest-stable-upgrade-plan.md`](docs/latest-stable-upgrade-plan.md)  
**Primary migration issue:** GitHub #135 — `[BW-DEP-01] Execute staged latest-stable toolchain and dependency migration` (closed; historical Forgejo #42). Staged work remains under the active upgrade plan.

## How to maintain this file

This file is the quick answer to **“what version are we on, what is the newest stable version, and what is left to do?”**

- Update this file in the **same PR** whenever a tracked version, runtime, SDK level, GitHub Actions ref or pinned tool changes.
- Re-check the affected row against its authoritative upstream source on every version-changing PR and update **Last checked**.
- A full dependency-health review should refresh every row and the **Last full stable-version check** date.
- **Latest stable** excludes alpha, beta, RC, milestone, preview, EAP, dev and snapshot builds.
- **Latest stable is not automatically the approved next version.** Compatibility gates and migration notes still apply.
- `gradle/libs.versions.toml` remains the source of truth for direct Gradle/Maven pins. This file mirrors those pins for planning/status visibility; when Gradle conflict resolution ships a newer transitive version than the direct pin, record both explicitly.
- Direct repository-owned versions are tracked here. Transitive Maven artifacts are not individually listed; Gradle dependency verification, the dependency report/SBOM and vulnerability scanning cover that surface.
- Mutable inputs such as the active GitHub action major tags, `sdkmanager "platform-tools"`, and the CI image's rebuild base tag are called out explicitly instead of pretending the repository pins an exact version.

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
| GitHub Android CI JDK | Java 17 via the digest-pinned Android image / `setup-java` major selection | Temurin 26.0.2.1 was the latest stable family checked on 2026-09-15 | 🎯 Java-17 verification lane. GitHub PR/main-seed jobs use the image; release/APK select Temurin 17. Keep the minimum/bytecode lane; a selected major is not an exact patch pin. | 8 | 2026-09-19 | [Gradle Java compatibility](https://docs.gradle.org/current/userguide/compatibility.html) |
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
| `androidxRoom` | 2.8.5 | 2.8.5 | ✅ Phase-4 compatible frontier reached in PR #163. Preserve committed schemas and migration checks; do not queue the same upgrade again. | 4 | 2026-09-15 | [Room releases](https://developer.android.com/jetpack/androidx/releases/room) |
| `androidxTestCore` | 1.7.0 | 1.7.0 | ✅ Current. | 7 | 2026-09-15 | [AndroidX Test releases](https://developer.android.com/jetpack/androidx/releases/test) |
| `androidxTestExt` | 1.3.0 | 1.3.0 | ✅ Current. | 7 | 2026-09-15 | [AndroidX Test releases](https://developer.android.com/jetpack/androidx/releases/test) |
| `androidxTestRunner` | 1.7.0 | 1.7.0 | ✅ Current. | 7 | 2026-09-15 | [AndroidX Test releases](https://developer.android.com/jetpack/androidx/releases/test) |
| `androidxBenchmark` | 1.5.0 | 1.5.0 | ✅ API-36 harness repair verified on phone: eight benchmark executions pass. Startup meets the fixture target; scrolling/manual-player/stress acceptance remains open. The generated app profile demonstrated no gain and stays outside production. | 7 | 2026-10-04 | [Benchmark releases](https://developer.android.com/jetpack/androidx/releases/benchmark) |
| `androidxUiAutomator` | 2.4.0 | 2.4.0 | ✅ Current. | 7 | 2026-09-15 | [UI Automator releases](https://developer.android.com/jetpack/androidx/releases/test-uiautomator) |
| `androidxWork` | 2.11.2 | 2.11.2 | ✅ Current. | 4 | 2026-09-15 | [WorkManager releases](https://developer.android.com/jetpack/androidx/releases/work) |
| `composeBom` | 2025.06.01 | 2026.08.00 | ⛔ Large Compose jump; execute with Phase 3 platform/AGP compatibility and device UI regression coverage. | 3 | 2026-09-15 | [Compose BOM](https://developer.android.com/develop/ui/compose/bom) |
| `media3` | 1.11.0 | 1.11.0 | ✅ Current. | 4 | 2026-09-15 | [Media3 releases](https://developer.android.com/jetpack/androidx/releases/media3) |

## Dependency injection

| Version-catalog key / component | Current in BookWave | Latest stable | Status / next action | Phase | Last checked | Authoritative source |
| --- | --- | --- | --- | --- | --- | --- |
| `hilt` (Dagger/Hilt) | 2.58 | 2.60.1 | ⛔ Dagger/Hilt 2.59+ makes AGP 9 a requirement when the Hilt Gradle plugin is used, so 2.60.1 follows the ADR-0011 build-foundation gate. | 4 | 2026-09-15 | [Dagger releases](https://github.com/google/dagger/releases) |
| `hiltExt` (AndroidX Hilt) | 1.3.0 | 1.4.0 | ⛔ Same AndroidX Hilt API 37/AGP 9.2+ gate as navigation-compose. | 3/4 | 2026-09-15 | [AndroidX Hilt releases](https://developer.android.com/jetpack/androidx/releases/hilt) |
| `javaxInject` | 1 | 1 | ✅ Phase-5 compatibility review complete: current/canonical legacy `javax.inject` artifact with no version migration identified. Reassess only as part of the Hilt/DI migration. | 5 compatibility | 2026-09-16 | [Maven Central](https://central.sonatype.com/artifact/javax.inject/javax.inject) |

## Kotlin runtime, serialization and network stack

| Version-catalog key / component | Current in BookWave | Latest stable | Status / next action | Phase | Last checked | Authoritative source |
| --- | --- | --- | --- | --- | --- | --- |
| `kotlinxCoroutines` | 1.11.0 | 1.11.0 | ✅ Phase 5 complete at latest stable after cancellation/concurrency regression coverage. Upstream 1.11.0 is built with Kotlin 2.2.20; BookWave remains on Kotlin 2.2.0 pending the gated compiler migration. | 5 | 2026-09-16 | [kotlinx.coroutines releases](https://github.com/Kotlin/kotlinx.coroutines/releases) |
| `kotlinxSerialization` | 1.9.0 | 1.11.0 | 🎯 Phase 5 complete at the current compiler-compatible frontier. 1.9.0 is the newest stable release aligned with BookWave’s Kotlin 2.2.0 compiler line; 1.10.0+ remains gated until Phase 1/2 re-resolves Kotlin. The 1.12.0-RC prerelease is excluded. | 5 | 2026-09-16 | [kotlinx.serialization releases](https://github.com/Kotlin/kotlinx.serialization/releases) |
| `okhttp` | 5.4.0 | 5.5.0 | 🎯 Phase 5 complete at the latest stable compatible with BookWave’s accepted API-36 / AGP-8 foundation; merged PR #169 supplied contract-capture and CI evidence, with the required test/smoke pass reported before merge. OkHttp 5.5.0 raises `okhttp-android` to compileSdk 37 and remains gated by ADR-0011. Keep the MockWebServer package migration separate. | 5 | 2026-09-16 | [OkHttp changelog](https://square.github.io/okhttp/changelogs/changelog/) |
| `protobuf` | 4.36.1 | 4.36.1 (Protobuf 36.1) | ✅ Latest stable reached in merged PR #166 with matched Java/Kotlin-lite runtime and protoc. The focused Proto DataStore suite and full classpath rerun gate are the compatibility evidence; the reported 36.1 Bazel prebuilt-tool integrity issue is outside BookWave's Gradle/Maven protoc path. | 5 | 2026-09-16 | [Protobuf releases](https://github.com/protocolbuffers/protobuf/releases) |
| `retrofit` | 3.0.0 | 3.0.0 | ✅ Phase 5 complete at latest stable in merged PR #168; PR #169 then upgraded the independent OkHttp engine to its current compatible frontier. | 5 | 2026-09-16 | [Retrofit 3.0.0 release](https://github.com/square/retrofit/releases/tag/3.0.0) |
| Retrofit kotlinx.serialization converter | 3.0.0 (first-party; tracks `retrofit`) | 3.0.0 (first-party; tracks Retrofit) | ✅ Phase 5 complete: first-party converter aligned with Retrofit 3.0.0 in merged PR #168; the archived third-party converter remains retired. | 5 | 2026-09-16 | [Retrofit 3.0.0 release](https://github.com/square/retrofit/releases/tag/3.0.0) |

> **Phase 5 reconciled 2026-09-16:** no compatible stable migration remains unexecuted on the current foundation. `kotlinxSerialization` and OkHttp intentionally stop at their compiler/platform-compatible frontiers, while `javaxInject` has no version migration. Resume this phase only when those gates change or a new compatible stable release appears; Phase 6 is the next executable dependency lane.

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

The active automation surface is **GitHub Actions under `.github/workflows/`**. The 2026-10-04
source reconciliation below replaces the former Forgejo runtime/ref snapshot. Upstream latest values
retain their stated check dates; a source/ref inventory is not a fresh upstream-release check.

| Component | Current repository selection | Planning status / next action | Checked |
| --- | --- | --- | --- |
| Checkout | `actions/checkout@v7` | Moving major tag; Phase 8 reviews exact implementation pins. | Source: 2026-10-04 |
| Gradle setup / wrapper validation | `gradle/actions/setup-gradle@v6`, `gradle/actions/wrapper-validation@v6` | Moving major tags. Enhanced Gradle cache; trusted main-seed writes. | Source: 2026-10-04 |
| Java / Node setup | `actions/setup-java@v6`, `actions/setup-node@v7` | Java 17 / Node 22 major selections; resolve supported release/patch identity before migration. | Source: 2026-10-04 |
| Artifact upload / download | `actions/upload-artifact@v7`, `actions/download-artifact@v8` | Active v8 download already replaced the historical v3 path; validate interoperability before changing refs. | Source: 2026-10-04 |
| Container registry login | `docker/login-action@v4` | Moving major tag; manual CI-image publication workflow. | Source: 2026-10-04 |
| Android verification image | `ghcr.io/alexanderwilhelmsenberg/bookwave-ci@sha256:a39aa3dad91c2a9e4464c527637ccd76ec8ba777d5d60c252f7cc3dd6f09e00b` | Immutable PR/main-seed runtime; [image source](ci/bookwave-ci/Dockerfile). Manual rebuild/publish, then reviewed digest update. | Source: 2026-10-04 |
| Gitleaks — PR/image/Codex | 8.30.1 | Source pins agree; latest stable 8.30.1 was checked 2026-09-19 (CI), 2026-09-15 (Codex). Re-check upstream before changing. | Source: 2026-10-04 |
| Node/npm | CI rebuild base `node:22-bookworm`; APK bundle selects Node 22; npm follows distribution | Digest freezes existing image bytes, rebuild base and bundle major can move. Historical upstream check: Node 26.8.2 Current / 24.21.0 LTS and npm 11.19.x on 2026-09-15. Phase 8 re-resolves a supported LTS and pin policy. | Source: 2026-10-04 |
| Python runtime — launcher assets | No repository baseline pin | Phase 8 policy gap. Historical latest check: 3.14.7 on 2026-09-15; re-resolve before selecting a baseline. | Upstream: 2026-09-15 |
| NumPy — launcher assets | 2.3.5 | Historical latest 2.5.3 on 2026-09-15; re-resolve and compare generated assets. | Upstream: 2026-09-15 |
| Pillow — launcher assets | 12.3.0 | Matched latest at the 2026-09-15 check; re-resolve before changing. | Upstream: 2026-09-15 |
| OSV | Service `querybatch` API, no scanner binary | Preserve fail-closed behavior; API/tool review, not a binary bump. | Source policy: 2026-09-15 |

## Dynamic / intentionally non-versioned external inputs

These are part of the reproducibility surface but do not have a meaningful `current -> latest stable`
comparison in the repository.

| Input | Repository selection | Treatment | Last checked |
| --- | --- | --- | --- |
| GitHub-hosted runner | `runs-on: ubuntu-latest` | ↔️ Moving hosted OS image; only PR/main-seed Android jobs add the immutable container. Do not infer exact runner/image package versions from a major tag. | 2026-10-04 |
| Audiobookshelf contract-capture fixture | Default `ghcr.io/advplyr/audiobookshelf:2.36.0`; manual input may override | 🎯 Default capture is explicit rather than `:latest`, while the workflow deliberately permits a caller-selected server image for compatibility recapture. | 2026-09-19 |
| Android Platform Tools install | `sdkmanager "platform-tools"` inside CI-image construction/Codex setup | ↔️ Moving SDK package; latest resolved upstream revision is recorded in the Android toolchain table above. | 2026-09-15 |

## Completed BW-DEP-01 update history

Append to this table whenever a tracked migration slice merges. The live tables above remain the source of truth for the current version. Rows through 2026-09-16 predate the Forgejo migration, so their `#154`–`#169` PR numbers are historical GitHub provenance; post-cutover entries name GitHub PRs; preserve historical Forgejo provenance explicitly.

| Date | PR / provenance | Component | From | To | Result |
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
| 2026-09-16 | #168 | Retrofit core + first-party converter | 2.11.0 | 3.0.0 | ✅ Latest stable reached; OkHttp intentionally held at 4.12.0 for the next independent network-major slice |
| 2026-09-16 | #169 | OkHttp family | 4.12.0 | 5.4.0 | 🎯 Latest stable compatible with API 36 / AGP 8; 5.5.0 remains ADR-0011/platform-gated |
| 2026-10-04 | [GitHub PR #223](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/pull/223) focused repair | AndroidX Benchmark | 1.3.4 | 1.5.0 | Strict forced gates/assembly and eight physical benchmark executions pass. Startup target met; scrolling and manual-player/stress acceptance remain open. App-profile experiment retained outside production without demonstrated gain. |

## Update discipline for future PRs

2026-10-04 focused Phase-7 slice: AndroidX Benchmark 1.3.4→1.5.0, official stable release checked on this
date. This repairs the supplied API-36 harness's documented process-discovery incompatibility and does
not complete the remaining Phase 7 migration. See [the repair review](docs/reviews/2026-10-04-benchmark-api36.md) and [completed rerun / remaining acceptance](docs/testing/2026-10-04-phone-2179.md).

Every PR that changes a tracked version should, before merge:

1. change the actual source-of-truth pin/configuration;
2. update the matching **Current in BookWave** cell here;
3. re-check the upstream stable release and refresh **Latest stable** + **Last checked** for that row;
4. update **Status / next action** and phase/gate notes when the upgrade changes what is now possible;
5. append a row to **Completed BW-DEP-01 update history** for Forgejo #42 migration work; and
6. update [`docs/latest-stable-upgrade-plan.md`](docs/latest-stable-upgrade-plan.md) only when sequencing, compatibility gates, validation requirements or phase ownership changes.

That separation is deliberate: **this file owns live versions; the roadmap owns migration strategy.**
