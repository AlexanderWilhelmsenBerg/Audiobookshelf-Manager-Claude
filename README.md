# BookWave for Android

**Unofficial** native Android client for [Audiobookshelf](https://www.audiobookshelf.org/). Not
affiliated with, endorsed by, or supported by the Audiobookshelf project, and it uses none of its
branding.

The authoritative requirements are in [`PRODUCT_SPEC.md`](PRODUCT_SPEC.md). Identifiers such as
`LIB-003` or `PLAY-004` are stable and are referenced from code, tests, commit messages and pull
requests.

## What it does

Streaming and offline listening against your own Audiobookshelf server, with chapters, bookmarks, a
sleep timer, per-profile progress and session sync, Android Auto, and the server-side management tools
— metadata editing, covers, provider matching, embedding, scans and user accounts.

Multiple accounts across multiple servers, each with its own library grants, progress and optional
passcode lock. Downloads are per device, not per profile, so two people sharing a phone share the files
and not their place in them.

**Source-file deletion is deliberately not offered** (ADR-0021): both server endpoints exist and neither
can prove the deletion happened, so the app does not claim it did.

**Not yet released.** The build is feature-complete against the specification; what remains is hardware
verification and a signing key. [`docs/closeout.md`](docs/closeout.md) is the live list.

## Requirements

| | Version | Needed for | Notes |
| --- | --- | --- | --- |
| **JDK** | **17 or newer** | everything | A floor, not a pin. The build sets no Gradle toolchain, so the JDK running Gradle is the one that compiles, targeting Java 17 bytecode. CI uses 21. Android Studio's bundled JBR is enough. |
| **Android SDK** | `platforms;android-36`, `build-tools;36.0.0`, `platform-tools` | everything | `compileSdk`/`targetSdk` 36, `minSdk` 26. `sdk.dir` must be in `local.properties`. |
| **Gradle** | — | — | **Do not install one.** The repository uses the wrapper; `gradle/wrapper` is validated in CI. |
| **A device or emulator** | Android 8.0+ | `connectedDebugAndroidTest`, and all real testing | The instrumented tier never runs in CI, which has no emulator. |
| **`jq`** | any | `scripts/vulnerability-scan.sh` only | `brew install jq` · `apt install jq` · `dnf install jq` |
| **Docker** | any | re-capturing API fixtures only | Runs a throwaway Audiobookshelf container. Never point it at a real library — the embed capture rewrites audio files. |
| **Python 3** | 3.9+ | regenerating launcher icons only | `scripts/requirements-bookwave-launcher-assets.txt` |
| **An Audiobookshelf server** | **2.26.0** or newer | using the app | Below 2.26.0 the server issues no refresh token and silent renewal fails hours later as a random sign-out. Contracts are captured against **2.36.0**. |

```bash
./scripts/check-local-environment.sh            # report only — changes nothing
./scripts/check-local-environment.sh --install  # install missing SDK packages, write local.properties
```

A bare run is **read only**: it reports and exits non-zero for anything that will stop the build.
`--install` is the only mode that changes anything, and the only thing it installs is Android SDK
packages through `sdkmanager`. It will never install a JDK, `jq` or Docker — those want `sudo`, differ
per platform, and a script that installs a JDK behind your back is one nobody should run. Those are
reported with the command to run.

**On Windows**, the tools are installed but usually not on the PATH:

```powershell
. .\scripts\Set-BookWavePath.ps1          # note the leading dot — it must be dot-sourced
. .\scripts\Set-BookWavePath.ps1 -Persist # and remember it for new terminals
```

## What it is built from

Every version below is the one in [`gradle/libs.versions.toml`](gradle/libs.versions.toml), which is the
single source of truth — dynamic versions are forbidden and 890 components are pinned by SHA-256 under
`org.gradle.dependency.verification=strict`.

**The app**: `org.homebord.bookwave`, version **0.9.14-browse-and-genres** (code 40). The debug build
installs alongside it as `org.homebord.bookwave.debug`.

| | Version |
| --- | --- |
| Kotlin | 2.2.0 |
| Android Gradle plugin | 8.12.0 |
| KSP | 2.2.0-2.0.2 |
| Compose BOM | 2025.06.01 |
| **Media3** (ExoPlayer, session, Android Auto) | **1.7.1** |
| **Benchmark** (macrobenchmark) | **1.3.4** |
| UI Automator (drives the benchmarks) | 2.3.0 |
| Hilt | 2.57 · androidx.hilt 1.2.0 |
| Room | 2.7.2 |
| DataStore (proto) | 1.1.7 · protobuf 4.31.1 |
| WorkManager | 2.10.1 |
| Navigation Compose | 2.9.0 |
| Lifecycle | 2.9.1 |
| Activity Compose | 1.10.1 |
| androidx.core KTX | 1.16.0 · annotation 1.9.1 |
| Retrofit | 2.11.0 · OkHttp 4.12.0 |
| kotlinx.serialization | 1.8.1 · coroutines 1.10.2 |
| Coil | 2.7.0 |
| detekt | 1.23.8 · ktlint 1.5.0 · Kover 0.9.1 |
| Robolectric | 4.15.1 · JUnit 4.13.2 · Turbine 1.2.1 |

## Build and run

```bash
./gradlew assembleDebug            # build
./gradlew installDebug             # install on an attached device
```

**Supply a signing key if you value your test data.** Without one, the debug build is signed with a key
AGP generates per machine — and CI runners are ephemeral, so every APK from the Build APK workflow gets
a different one. Installing a second such APK fails and forces an uninstall, which wipes the sign-in,
the passcode, the progress and the downloads. [`docs/release.md` § Signing](docs/release.md) has the
four properties and the `keytool` command; the key never enters this repository.

## Verification

```bash
./gradlew ktlintFormat                                     # apply the formatter first
./gradlew verifyDebug -Pshelfplayer.warningsAsErrors=true  # the gate CI runs
```

`verifyDebug` runs, for every module: ktlint, detekt with type resolution, Android Lint, the unit
tests, Room schema verification, Kover's coverage gate and the debug assembly.

**Add `--rerun-tasks` on a branch that changed a classpath.** Gradle has treated test-compile tasks as
up to date when only the classpath moved, which let two stale test doubles pass locally and fail in CI
(`docs/risks.md` R-31).

### Only on a device

```bash
./gradlew :core:datastore:connectedDebugAndroidTest   # the profile lock's real Keystore storage
./gradlew :benchmark:connectedBenchmarkAndroidTest    # the 17.3 numbers — see docs/benchmark.md
```

Neither is part of `verifyDebug` and neither runs in CI. `scripts/device-test/` carries the commands for
each section of [`docs/device-test-0.9.14.md`](docs/device-test-0.9.14.md), one script per section.

### Supply chain

```bash
./gradlew :app:sbom              # CycloneDX 1.5; fails if anything shipped is not pinned
./scripts/vulnerability-scan.sh  # asks OSV about every component in it; needs jq
```

## Repository layout

```text
app/                     Compose shell, navigation, feature.* screens, settings, the lock curtain
playback/                MediaLibraryService, media session, Android Auto tree, sleep timer
benchmark/               macrobenchmark: startup, scroll, memory, baseline profile   (device only)
core/model/              AppResult, AppError, domain models                          (JVM)
core/common/             dispatchers, clock, redacted logging, the event log         (JVM)
core/designsystem/       Material 3 theme and shared state views
core/database/           Room entities, DAOs, migrations, committed schemas
core/datastore/          proto DataStore settings and the profile lock's storage
core/network/            gateway interfaces, Retrofit, DTOs, fake gateway + fixtures
core/testing/            shared test doubles                                         (JVM)
data/auth/               sign-in, tokens, profile switching
data/library/            library, progress, bookmarks, playback history
data/downloads/          the downloader, smart downloads, storage volumes
data/settings/           playback, appearance and device settings
domain/                  repository interfaces, use cases, sorting and shelf policy  (JVM)
build-logic/             Gradle convention plugins, the SBOM task, release signing
config/detekt/           detekt configuration
scripts/                 environment check, contract capture, device-test commands
docs/                    architecture, ADRs, API compatibility, risks, testing, release
```

Why the boundaries are where they are:
[`docs/architecture/overview.md`](docs/architecture/overview.md).

## Documentation

| | |
| --- | --- |
| What is left to do | [`docs/closeout.md`](docs/closeout.md) |
| What is known to be wrong, and what was done | [`docs/risks.md`](docs/risks.md) |
| Built versus specified | [`docs/gaps.md`](docs/gaps.md) |
| What the server actually returns | [`docs/api-compatibility.md`](docs/api-compatibility.md) |
| Testing a build on hardware | [`docs/device-test-0.9.14.md`](docs/device-test-0.9.14.md) |
| Performance targets and results | [`docs/benchmark.md`](docs/benchmark.md) |
| Releasing, and signing | [`docs/release.md`](docs/release.md) |
| Decisions, with their reasoning | [`docs/adr/`](docs/adr/) |
| Finished work, kept for its reasoning | [`docs/archive/`](docs/archive/) |

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). In short: work from a requirement ID, keep the vertical slice
small, add tests at the right level, and never report work complete while `verifyDebug` fails.

## Security and privacy

- [`SECURITY.md`](SECURITY.md) — reporting a vulnerability, and the invariants that must not regress.
- [`PRIVACY.md`](PRIVACY.md) — what stays on the device, and what is redacted from logs.

## Licence

**GPL-3.0-or-later** — see [`LICENSE`](LICENSE). ADR-0012's posture is what made this a free choice:
this project reads Audiobookshelf's source for API facts and copied none of its code, so nothing
obliged copyleft. ADR-0024 records the interaction with Google Play.
