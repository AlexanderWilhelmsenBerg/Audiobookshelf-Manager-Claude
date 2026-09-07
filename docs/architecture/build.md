# Build and quality gates

**Classification:** Current contract.  
**Current as reviewed:** 2026-09-07.

This document describes the build as it exists on `main`. Historical bootstrap decisions remain in the
ADRs; they are not current setup instructions.

## Toolchain

`gradle/libs.versions.toml` is the source of truth for library/plugin versions. The important current
build values are:

| Piece | Current value |
| --- | --- |
| Gradle | wrapper-controlled (`gradle/wrapper/gradle-wrapper.properties`) |
| Android Gradle Plugin | 8.12.0 |
| Kotlin | 2.2.0 |
| KSP | 2.3.11 |
| compileSdk / targetSdk | 36 |
| minSdk | 26 |
| Java bytecode | 17 |
| ktlint | 1.5.0 (Gradle plugin 12.3.0) |
| detekt | 1.23.8 |
| Kover | 0.9.9 |
| Media3 | 1.11.0 |
| WorkManager | 2.11.2 |

Every dependency version is fixed; dynamic `+` versions and Git dependencies are not part of the build.
Repositories are centrally controlled so individual modules cannot quietly introduce a new repository.

## Convention plugins

`build-logic/` is an included build. Its convention plugins own SDK levels, Java/Kotlin build behavior,
quality configuration and build identity/signing rules. Module build files should express what a module
needs, not duplicate global policy.

The important families are:

| Plugin family | Purpose |
| --- | --- |
| `shelfplayer.android.application` / Compose variants | application Android configuration |
| `shelfplayer.android.library` / Compose variants | Android library modules |
| `shelfplayer.jvm.library` | pure JVM modules such as model/domain/common/testing |
| `shelfplayer.android.room` | Room schema/export behavior |
| `shelfplayer.hilt` | dependency injection where needed |
| `shelfplayer.quality` | repository quality tasks and common checks |

## `verifyDebug`

`PRODUCT_SPEC 16.5` asks for one verification command. The repository-level `verifyDebug` fans out to
the checks each module actually supports rather than pretending every module is Android.

Typical coverage includes:

- ktlint;
- detekt with type resolution;
- Android Lint for Android modules;
- JVM/Robolectric unit tests;
- Kover coverage gates;
- Room schema verification;
- debug assembly.

CI enables warnings-as-errors with `-Pshelfplayer.warningsAsErrors=true`; local work-in-progress builds
remain warning-tolerant unless the flag is supplied.

### Do not trust stale Gradle task outputs after a classpath change

Use `--rerun-tasks` when a branch changes a classpath before treating a green local result as evidence.
`docs/risks.md` R-31 records the incident where Gradle considered stale test compilation up to date and
local tests disagreed with CI.

## Android Lint and static analysis

Warnings/errors that describe this code are fixed or deliberately suppressed at the declaration with a
reason. Project-wide disabling is reserved for checks that measure external freshness/environment rather
than the correctness of this repository.

Detekt must run with type resolution where a rule depends on resolved calls. A bare detekt invocation is
not equivalent to the repository gate.

## Dependency verification is strict; dependency locking is deliberately disabled

This is the section that had drifted furthest from `main`.

### Verification — current and enforced

`gradle.properties` sets:

```properties
org.gradle.dependency.verification=strict
```

`gradle/verification-metadata.xml` contains the recorded SHA-256 metadata. A dependency whose artifact is
not represented correctly fails resolution rather than merely producing a report. For a small dependency
addition/upgrade, use Gradle's `--write-verification-metadata sha256` flow and review the metadata diff.

The component/checksum counts written in comments are snapshots. Trust the generated metadata/SBOM over a
number copied into prose.

### Locking — intentionally not active

ADR-0006 originally planned verification and Gradle dependency locking together. ADR-0010 records the
later measured result: locking was disabled after it interacted badly with the repository's Android/variant
resolution, while dependency verification could be made strict successfully.

Therefore:

- absence of `dependencyLocking { lockAllConfigurations() }` is **intentional**;
- there is no bootstrap lockfile step a fresh contributor is supposed to run;
- do not reintroduce locking simply to make ADR-0006 look complete;
- any future retry must first reproduce/solve ADR-0010's variant-resolution problem and should be its own
  deliberate architecture change.

ADR-0010 partially supersedes ADR-0006 on the locking half. Verification remains the active supply-chain
integrity mechanism.

## Room schemas

KSP exports Room schemas under `core/database/schemas`, and schema changes are committed with the migration
that explains them. The quality/CI checks make an uncommitted current schema visible rather than allowing a
schema change to merge without its evidence.

This is one of the mechanisms behind the project's no-destructive-migration posture.

## Configuration cache

`org.gradle.configuration-cache=false` remains the current repository setting. The historical comment still
calls this a Phase 0 choice; it should be revisited only as a measured build/tooling task, not enabled while
unrelated feature work is in flight.

## Build identity and signing

Build identity/version-code and debug/release signing rules have evolved beyond the original phase docs.
[`../release.md`](../release.md) is the current operational authority. In particular, stable debug signing
and release/upload signing are separate concerns; old device-test instructions that used release signing
inputs for debug builds are historical evidence, not current setup.
