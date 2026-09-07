# BookWave for Android

**Unofficial** native Android client for [Audiobookshelf](https://www.audiobookshelf.org/). Not
affiliated with, endorsed by, or supported by the Audiobookshelf project, and it uses none of its
branding.

The baseline product requirements are in [`PRODUCT_SPEC.md`](PRODUCT_SPEC.md). Accepted ADRs may
explicitly refine or supersede parts of that baseline. The documentation entry point is
[`docs/README.md`](docs/README.md), and [`docs/roadmap.md`](docs/roadmap.md) is the sole canonical
answer to **what should be worked on next**.

## What it does

Streaming and offline listening against your own Audiobookshelf server, with chapters, bookmarks, a
sleep timer, per-profile progress and session sync, Android Auto, and server-side management tools —
metadata editing, covers, provider matching, embedding, scans and user accounts.

Multiple accounts across multiple servers, each with its own library grants, progress and optional
passcode lock. Downloads are per device, not per profile, so two people sharing a phone can share the
physical files without sharing progress or authorization.

**Source-file deletion is deliberately not offered** (ADR-0021): both server endpoints exist and neither
can prove the deletion happened, so the app does not claim it did.

**Not yet released.** BookWave is a feature-rich pre-release application with active correctness,
platform-integration and UI work. The current committed PR chain and work after it are tracked in
[`docs/roadmap.md`](docs/roadmap.md). Historical closeout and phase documents remain in the repository
for their engineering evidence, but they are not current work lists.

## Requirements

| | Version | Needed for | Notes |
| --- | --- | --- | --- |
| **JDK** | **17 or newer** | everything | A floor, not a pin. The build targets Java 17 bytecode; CI uses a newer JDK where configured. Android Studio's bundled JBR is sufficient. |
| **Android SDK** | `platforms;android-36`, `build-tools;36.0.0`, `platform-tools` | everything | `compileSdk`/`targetSdk` 36, `minSdk` 26. `sdk.dir` must be in `local.properties`. |
| **Gradle** | — | — | **Do not install one.** The repository uses the wrapper; `gradle/wrapper` is validated in CI. |
| **A device or emulator** | Android 8.0+ | connected/device testing | The instrumented and real-device tiers are not substitutes for JVM/Robolectric tests, and vice versa. |
| **`jq`** | any | `scripts/vulnerability-scan.sh` only | `brew install jq` · `apt install jq` · `dnf install jq` |
| **Docker** | any | re-capturing API fixtures only | Runs a throwaway Audiobookshelf container. Never point it at a real library — some capture work can rewrite server content. |
| **Python 3** | 3.9+ | regenerating launcher icons only | `scripts/requirements-bookwave-launcher-assets.txt` |
| **An Audiobookshelf server** | **2.26.0** or newer | using the app | Below 2.26.0 the server issues no refresh token and silent renewal cannot meet BookWave's authentication contract. Contracts were captured against later server versions; see the API compatibility docs. |

```bash
./scripts/check-local-environment.sh            # report only — changes nothing
./scripts/check-local-environment.sh --install  # install missing SDK packages, write local.properties
```

A bare run is **read only**. `--install` is the mode that changes the local environment, and it only
installs Android SDK packages through `sdkmanager`; it does not install a JDK, `jq` or Docker.

**On Windows**, initialize the repository tools in PowerShell with:

```powershell
. .\scripts\Set-BookWavePath.ps1
. .\scripts\Set-BookWavePath.ps1 -Persist # optional: remember it for new terminals
```

## What it is built from

[`gradle/libs.versions.toml`](gradle/libs.versions.toml) is the dependency-version source of truth.
Dynamic versions are forbidden and dependency verification runs in strict mode.

**The product version is `0.9.6.1`.** CI build identity/version-code details are deliberately kept out
of this README because they vary per build; Settings → About and [`docs/release.md`](docs/release.md)
explain the current scheme. The debug application id is `org.homebord.bookwave.debug`.

| | Current version |
| --- | --- |
| Kotlin | 2.2.0 |
| Android Gradle Plugin | 8.12.0 |
| KSP | 2.3.11 |
| Compose BOM | 2025.06.01 |
| **Media3** | **1.11.0** |
| **Benchmark** | **1.3.4** |
| UI Automator | 2.4.0 |
| Hilt | 2.58 · androidx.hilt 1.3.0 |
| Room | 2.7.2 |
| DataStore / protobuf | 1.1.7 · 4.31.1 |
| WorkManager | 2.11.2 |
| Navigation Compose | 2.9.8 |
| Lifecycle | 2.10.0 |
| Activity Compose | 1.12.4 |
| androidx.core KTX | 1.17.0 |
| Retrofit / OkHttp | 2.11.0 · 4.12.0 |
| kotlinx.serialization / coroutines | 1.8.1 · 1.10.2 |
| Coil | 2.7.0 |
| detekt / ktlint / Kover | 1.23.8 · 1.5.0 · 0.9.9 |
| Robolectric / JUnit / Turbine | 4.15.1 · 4.13.2 · 1.2.1 |

## Build and run

```bash
./gradlew assembleDebug
./gradlew installDebug
```

Debug signing is intentionally stable on a development machine and is managed separately from the
release/upload key. Release signing material never belongs in the repository. See
[`docs/release.md`](docs/release.md) for the current debug-key, upload-key and CI artifact rules rather
than following old version-specific device-test instructions.

## Verification

```bash
./gradlew ktlintFormat
./gradlew verifyDebug -Pshelfplayer.warningsAsErrors=true
```

`verifyDebug` fans out through the repository's formatting, detekt, Android Lint, unit/Robolectric,
coverage, schema and assembly gates as appropriate to each module.

**Add `--rerun-tasks` on a branch that changed a classpath.** Gradle has previously considered stale
test compilation up to date after a classpath-only change (`docs/risks.md` R-31).

### Device-only evidence

```bash
./gradlew :core:datastore:connectedDebugAndroidTest
./gradlew :benchmark:connectedBenchmarkAndroidTest
```

See [`docs/testing.md`](docs/testing.md) for the current testing authority. Version-specific device-test
documents are retained as historical evidence and should not be assumed to describe the current build.

### Supply chain

```bash
./gradlew :app:sbom
./scripts/vulnerability-scan.sh
```

## Repository layout

```text
app/                     Compose shell, navigation and feature UI
playback/                MediaLibraryService, Media3 session, Android Auto, sleep timer
benchmark/               macrobenchmark and baseline-profile tooling
core/model/              domain/value models                                      (JVM, zero deps)
core/common/             clock, dispatchers, redacted logging                     (JVM)
core/designsystem/       Material 3 theme and shared UI primitives
core/database/           Room entities, DAOs, migrations and committed schemas
core/datastore/          Proto DataStore settings and secure local storage
core/network/            Audiobookshelf gateways, Retrofit/OkHttp, DTOs and fixtures
core/testing/            shared test doubles                                      (JVM)
data/auth/               authentication, tokens and profile switching
data/library/            library, progress, bookmarks and playback history
data/downloads/          download manifest, files, verification and storage volumes
data/settings/           playback, appearance and device settings
domain/                  repository interfaces, use cases and policy              (JVM)
build-logic/             Gradle conventions, quality gates, build identity/signing
config/detekt/           detekt configuration
scripts/                 environment, contract-capture and device-test commands
docs/                    current docs, ADRs, risks, plans, research and history
```

See [`docs/architecture/overview.md`](docs/architecture/overview.md) and
[`docs/architecture/module-boundaries.md`](docs/architecture/module-boundaries.md) for the current
architectural rules.

## Documentation

Start with [`docs/README.md`](docs/README.md). In particular:

| Need | Authority |
| --- | --- |
| Current and future work | [`docs/roadmap.md`](docs/roadmap.md) |
| Current architecture | [`docs/architecture/`](docs/architecture/) |
| Playback correctness | [`docs/architecture/playback.md`](docs/architecture/playback.md) |
| Accepted decisions | [`docs/adr/`](docs/adr/) |
| Open/closed risks | [`docs/risks.md`](docs/risks.md) |
| Current test guidance | [`docs/testing.md`](docs/testing.md) |
| Server contract evidence | [`docs/api-compatibility.md`](docs/api-compatibility.md) |
| Dependency-upgrade child plan | [`docs/dependency-upgrade-plan.md`](docs/dependency-upgrade-plan.md) |
| Historical phase material | [`docs/archive/`](docs/archive/) |
| Dated investigations | [`docs/bugs/`](docs/bugs/) and [`docs/reviews/`](docs/reviews/) |

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). Work from an explicit requirement/roadmap/issue slice, keep the
change bounded, add tests at the level that can prove the claim, and do not report work complete while
the required gate fails.

## Security and privacy

- [`SECURITY.md`](SECURITY.md) — vulnerability reporting and invariants that must not regress.
- [`PRIVACY.md`](PRIVACY.md) — what stays on the device and what diagnostics redact.

## Licence

**GPL-3.0-or-later** — see [`LICENSE`](LICENSE). ADR-0012 records why reading Audiobookshelf source for
API facts does not copy its implementation, and ADR-0024 records the distribution/licensing decision.
