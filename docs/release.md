# Release

`PRODUCT_SPEC 18` defines the pipeline and `PRODUCT_SPEC 25` the acceptance checklist. This records the
process, what it already does, and what still blocks a public build.

**As of:** 2026-08-20, entering Phase 6. Phases 1–5 are complete.

## Blocking open decisions

From `PRODUCT_SPEC 24`, each of these must be resolved by an ADR before a public build. Three of the
original five are now settled.

| Decision | State |
| --- | --- |
| **Application ID** — the placeholder `com.example.shelfplayer` must be replaced (`PRODUCT_SPEC 16.4`) | **Open.** Play rejects the `com.example.` prefix. ADR-0019 records why it did not move with the rename to BookWave: Android identifies an install by its `applicationId`, so changing it produces a second, empty app rather than a renamed one. The right moment is the first release, before anybody has an install to lose. |
| **Licence** — see `LICENSE` | **Open**, and it gates distribution rather than development. It interacts with ADR-0012's posture towards the official project: read it for API facts, do not copy code. |
| **Distribution channel** (Play, F-Droid, GitHub Releases, private) | **Open.** It decides the signing story, the version-code rule, and whether a bundle or an APK ships. F-Droid would additionally require a reproducible build. |
| **Minimum supported Audiobookshelf server version** | **Open.** `docs/api-compatibility.md` pins 2.36.0 as the version everything was verified against, which is not the same as a floor. The capability probe confirms features rather than versions, so a floor still has to be chosen and enforced at sign-in. |
| **Whether true source-file deletion can be exposed** (`MGR-006`) | **Settled: no.** ADR-0021. Both endpoints exist and neither can prove the deletion happened, so the app ships no such feature. |

## Versioning

`versionCode` and `versionName` live in the application convention plugin. The name states the build's
phase, so a device-test result recorded against an APK can be traced to one; the code increments by one
per build handed to a tester.

`PRODUCT_SPEC 18` requires a *reproducible* version code, and that rule is chosen when a distribution
channel is — Play needs a monotonic integer, F-Droid needs one derivable from a tag. Until then, the
current scheme is a hand-maintained counter, and the failure mode it has already had is worth recording:
it sat at `0.9.6-auto-shelves` for nine builds while the code advanced, so every field report in that
window identified the wrong build. **The name moves with the code, or it is worse than absent.**

## Signing

There is no signing configuration in this repository and there must not be one. Release builds produced
by CI are unsigned. Signing happens only in a protected environment holding the key material, never in a
workflow triggered by a push (`PRODUCT_SPEC 18`).

## The pipeline today

`.github/workflows/pull-request.yml` — Gradle wrapper validation, secret scan, `verifyDebug` with
warnings-as-errors, Room schema diff, debug APK, dependency report.

`.github/workflows/main.yml` — the above plus release lint and an unsigned release assembly.

`.github/workflows/contract-capture.yml` — captures response shapes from a real server on demand
(`PRODUCT_SPEC 22.5`).

`verifyDebug` itself fans out to every module: ktlint, detekt with type resolution, Android Lint with
warnings as errors, the unit suite, and Kover's coverage gate over domain and core. Dependency
verification is `strict` over 887 pinned components.

**Verify with `--rerun-tasks`.** Gradle can consider a test-compile task up to date when only its
classpath changed, which once let two stale test doubles pass locally and fail in CI. See `docs/risks.md`
R-31.

## What must be added before a release

| Step | Requirement | Blocked on |
| --- | --- | --- |
| Managed-device tests | 18, 17.2 | CI has no emulator. This is the largest single hole: no instrumented test exists at all. |
| Two-hour playback soak; process-death progress budget | 25, 17.3 | A device and patience. No new infrastructure. |
| Android Auto verification in the Desktop Head Unit | 17.2 | Nothing. The browse tree is built and has never been run in a car. |
| Launch the release APK once | 15 | Nothing. R8 runs in CI and its output is never executed. |
| Baseline profile and a benchmark module | 17.3 | A device; lands with managed-device tests. |
| Software Bill of Materials | 18 | The licence decision. |
| Dependency vulnerability scan | 18 | Nothing — can be added at any time. |
| Mapping/native-symbol archive | 18 | A signed release build. |
| Changelog generated from labelled changes | 18 | A label convention. |
| A supported-version statement in `SECURITY.md` | 15 | The distribution channel. |

## Pre-release checklist

Use `PRODUCT_SPEC 25` verbatim. Do not mark an item complete on the strength of a happy-path screen;
`PRODUCT_SPEC 21` requires error, loading, empty, offline and permission states, accessibility semantics,
and evidence that nothing private is logged.

Three separate device runs have each found defects that the entire unit suite passed through — eight in
the audit of 2026-08-16, four more on 2026-08-20, and three of those four were the same shape: a feature
that worked perfectly and could not be reached from any screen. **A green build is not a tested build.**
