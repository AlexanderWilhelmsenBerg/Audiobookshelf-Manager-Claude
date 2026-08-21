# Release

`PRODUCT_SPEC 18` defines the pipeline and `PRODUCT_SPEC 25` the acceptance checklist. This records the
process, what it already does, and what still blocks a public build.

**As of:** 2026-08-20, entering Phase 6. Phases 1–5 are complete.

## Blocking open decisions

**All five are now settled.** ADR-0021 closed source-file deletion; ADR-0024 closed the other four, after
the owner was asked rather than guessed at.

| Decision | Settled as |
| --- | --- |
| **Application ID** | `org.homebord.bookwave` — reverse-DNS of a domain the owner controls. Only the `applicationId` moved; every module's `namespace` and every Kotlin package stay `com.example.shelfplayer`, because Play neither sees nor cares about those and renaming them would touch every file for no observable effect. Moved before the first release, which is the only moment it is free (ADR-0019). |
| **Licence** | **GPL-3.0-or-later.** `LICENSE` carries the canonical text. ADR-0012's posture is what made this a free choice: this project reads Audiobookshelf's source for API facts and copied none of its code, so nothing obliged copyleft. ADR-0024 records the real interaction with Play — source must stay available, and Play App Signing versus GPLv3 §6 rests on a widely-relied-on but not authoritatively settled reading. |
| **Distribution channel** | **Google Play.** An App Bundle, Play App Signing (so still no key material in the repository), and a data-safety declaration whose content `PRIVACY.md` already supplies. No reproducible-build requirement, which F-Droid would have imposed. |
| **Minimum server version** | **2.26.0**, enforced in `SignInViewModel` before a password is typed. Below it the server issues no refresh token, so AUTH-004's silent renewal would fail hours later and read as a random sign-out. Chosen over 2.36.0 because the refreshable token is a behavioural boundary while 2.36.0 is a testing artefact. Accepted cost: 2.26–2.36 is allowed and unverified. |
| **Whether true source-file deletion can be exposed** (`MGR-006`) | **No.** ADR-0021. Both endpoints exist and neither can prove the deletion happened. |

## Versioning

`versionCode` and `versionName` live in the application convention plugin. The channel decided the rule:
Play requires a strictly increasing integer per upload and never permits a code to be reused for a package,
so it stays a **hand-incremented integer**. A derived scheme — a timestamp, or a commit count — was
considered and rejected: both can go backwards or collide across branches, and Play's refusal of a reused
code is permanent. See ADR-0024.

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
