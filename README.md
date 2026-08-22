# BookWave for Android

**Unofficial** native Android client for [Audiobookshelf](https://www.audiobookshelf.org/). This
project is not affiliated with, endorsed by, or supported by the Audiobookshelf project. It does not
use Audiobookshelf branding or logos.

The authoritative requirements are in [`PRODUCT_SPEC.md`](PRODUCT_SPEC.md). Requirement identifiers
such as `LIB-003` or `PLAY-004` are stable and are referenced from code, tests and pull requests.

## Status: Phase 0 — repository foundation

Phase 0 delivers the build, the architecture and the quality gates. It deliberately implements
neither authentication nor playback.

What works today:

- the app opens and browses a **bundled demo library**, backed by Room, with no server and no
  credentials;
- library list → book grid with search and sorting → book detail, all reading cached state;
- typed `AppResult` / `AppError`, injected clock and dispatchers, redacted structured logging;
- a fake Audiobookshelf gateway plus a fixture library, behind the same interfaces the real gateway
  will implement in Phase 1.

What is deliberately absent, with the phase that adds it:

| Absent | Arrives in |
| --- | --- |
| Sign-in, token storage, profile switching | Phase 1 (`AUTH-001`…`AUTH-004`) |
| Retrofit endpoint definitions and contract fixtures | Phase 1 (`SYNC-001`) |
| Playback, Media3 service, media session | Phase 2 (`PLAY-001`…`PLAY-008`) |
| Downloads and offline storage | Phase 3 (`DL-001`…`DL-006`) |
| Smart downloader, device policies | Phase 4 (`DL-005`, `ROUTE-002`) |
| Metadata editing, scans, user management | Phase 5 (`MGR-*`, `USER-*`) |
| Android Auto, diagnostics export | Phase 6 |

Phase 0 does **not** define Audiobookshelf endpoints. PRODUCT_SPEC 22.4 forbids inventing them and
22.5 requires a captured contract fixture before relying on a response shape; there is no server to
capture from yet, so the gateway ships with a fake implementation and no wire types. See
[`docs/api-compatibility.md`](docs/api-compatibility.md).

## Prerequisites

```bash
./scripts/check-local-environment.sh            # what is missing, and what to do about each
./scripts/check-local-environment.sh --install  # and install missing Android SDK packages
```

It exits non-zero only for something that will stop the build, and writes `local.properties` for you if
it finds an SDK that is not written down. What it looks for:

- **JDK 17 or newer.** The build targets Java 17 bytecode and configures no Gradle toolchain, so the JDK
  running Gradle is the one that compiles — 17 is a floor rather than a pin, and 21 is what CI uses.
- **Android SDK** with `platforms;android-36`, `build-tools;36.0.0` and `platform-tools`, and `sdk.dir`
  in `local.properties`.
- **A device or emulator**, for `connectedDebugAndroidTest` only — see below.
- **`jq`**, for the vulnerability scan only.
- **No global Gradle installation.** The repository uses the Gradle Wrapper.

## Build and run

```bash
./gradlew assembleDebug            # build the debug APK
./gradlew installDebug             # install on a connected device or emulator
```

On first launch the app writes the bundled fixture library into Room and opens it. No server, no
account and no network access are needed.

## Verification

Two commands, both defined by PRODUCT_SPEC 16.2 and 16.5:

```bash
./gradlew ktlintFormat             # apply the formatter
./gradlew verifyDebug              # the full gate
```

`verifyDebug` runs, for every module: ktlint, detekt with type resolution, Android Lint, unit tests,
Room schema verification and the debug assembly. CI adds `-Pshelfplayer.warningsAsErrors=true`, which
turns Kotlin compiler warnings into errors.

Individual gates:

```bash
./gradlew ktlintCheck
./gradlew detektMain detektDebug   # detekt with type resolution
./gradlew lintDebug
./gradlew test
```

### What only runs on a device

```bash
./gradlew :core:datastore:connectedDebugAndroidTest
```

Not part of `verifyDebug`, and never run in CI, which has no emulator. It is the only way to execute the
instrumented tier — the profile lock's AndroidKeyStore storage, which Robolectric cannot reach because it
ships no `AndroidKeyStore` provider. Safe to run on a phone that has the app installed: the test APK has
its own package and therefore its own UID, so it cannot touch the app's records.

### Supply chain

```bash
./gradlew :app:sbom              # CycloneDX 1.5; fails if anything shipped is not pinned
./scripts/vulnerability-scan.sh  # asks OSV about every component in it
```

## Connecting a test server

Not yet applicable. Phase 0 has no sign-in screen and makes no network requests. Phase 1 adds server
connection (`AUTH-001`) together with the contract tests that prove the request and response shapes;
the tested server versions will be recorded in
[`docs/api-compatibility.md`](docs/api-compatibility.md).

To change what the demo library contains, edit
`core/network/src/main/resources/fixtures/demo-library.json`. That file uses a BookWave-owned
format and is **not** an Audiobookshelf API response.

## Repository layout

```text
app/                     Compose shell, navigation, feature.home / feature.library / feature.book
core/model/              AppResult, AppError, domain models        (JVM, no dependencies)
core/common/             dispatchers, clock, redacted logging      (JVM)
core/designsystem/       Material 3 theme and shared state views
core/database/           Room entities, DAOs, migrations, schemas
core/datastore/          Proto DataStore settings
core/network/            gateway interfaces, HTTP foundation, fake gateway + fixtures
core/testing/            shared test doubles                       (JVM)
data/library/            LibraryRepository / ProfileRepository implementations
domain/                  repository interfaces, use cases, sorting policy  (JVM)
build-logic/             Gradle convention plugins
config/detekt/           detekt configuration
docs/                    architecture, ADRs, compatibility matrix, testing, release
```

Architecture and the reasoning behind the module boundaries:
[`docs/architecture/overview.md`](docs/architecture/overview.md).

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). In short: work from a requirement ID, keep the vertical
slice small, add tests at the right level, and never mark work complete while `verifyDebug` fails.

## Security and privacy

- [`SECURITY.md`](SECURITY.md) — reporting a vulnerability, and the invariants that must not regress.
- [`PRIVACY.md`](PRIVACY.md) — what stays on the device, and what is redacted from logs.

## Licence

Not yet chosen. See [`LICENSE`](LICENSE) — this is an open decision (PRODUCT_SPEC 24.2) and it blocks
public distribution, not development.
