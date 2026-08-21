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
verification is `strict` over 890 pinned components.

**Verify with `--rerun-tasks`.** Gradle can consider a test-compile task up to date when only its
classpath changed, which once let two stale test doubles pass locally and fail in CI. See `docs/risks.md`
R-31.

## What must be added before a release

| Step | Requirement | Blocked on |
| --- | --- | --- |
| Managed-device tests | 18, 17.2 | CI has no emulator. Still the largest single hole, though no longer a total one: `:core:datastore` has an instrumented suite over the profile lock's storage, runnable with `connectedDebugAndroidTest` against an attached device. No other module has one, and none of it runs in CI. |
| Two-hour playback soak; process-death progress budget | 25, 17.3 | A device and patience. No new infrastructure. |
| Android Auto verification in the Desktop Head Unit | 17.2 | Nothing. The browse tree is built and has never been run in a car. |
| Launch the release APK once | 15 | Nothing. R8 runs in CI and its output is never executed. |
| Baseline profile and a benchmark module | 17.3 | A device; lands with managed-device tests. |
| Changelog generated from labelled changes | 18 | A label convention. `CHANGELOG.md` exists and is written by hand; what is missing is the mapping from a pull-request label to a section, so that the note is generated rather than remembered. |

## The Software Bill of Materials

`./gradlew :app:sbom` writes CycloneDX 1.5 JSON to `app/build/reports/sbom/bom.json`, and the main-branch
workflow uploads it beside the R8 mapping as `release-supply-chain`. **175 components, 130 of them with a
pinned SHA-256.**

It is a task in `build-logic` rather than the `org.cyclonedx.bom` plugin, because this build already holds
every input the document needs and a plugin would add its own transitive tree inside `strict` dependency
verification. It reaches no network.

**Two sources, each for the one thing it is authoritative about.** Which components ship comes from
`releaseRuntimeClasspath`'s resolution result — the graph *after* conflict resolution. It does not come
from `verification-metadata.xml`, which lists every version Gradle ever resolved metadata for and would
name four versions of `androidx.activity` as shipped when only 1.10.1 is. Integrity comes from
`verification-metadata.xml`, because that is where this project's pinned checksums live; re-hashing the
Gradle cache would only prove the cache agrees with itself.

**Reading the fields honestly:**

- **`hashes`** is the SHA-256 of the component's shipped binary, selected by extension from what the
  metadata actually lists rather than by constructing a file name. That distinction is not pedantic:
  AndroidX publishes its AAR as `animation-release.aar`, not `animation-1.8.3.aar`, and a first version of
  this task that built the expected name found hashes for 96 of 175 components — which read as "this
  project does not pin most of its dependencies" when in fact it pins all of them.
- **45 components carry no hash**, every one of them with a
  `shelfplayer:hash-absent` property saying why. All 45 are `no-binary-published`: a Kotlin Multiplatform
  parent such as `androidx.annotation:annotation`, whose binary is published as `annotation-jvm`, or a BOM
  that publishes only a POM. **None is `not-pinned`** — the value that would mean a binary reaches the
  application without a pinned checksum, which `strict` verification should make impossible and which
  **fails the task** rather than appearing quietly in the document.
- **`licenses`** is copied verbatim from each component's own POM `<licenses>` and is **omitted** when the
  POM declares none — which is the case for five components today, among them Guava and protobuf-javalite.
  An omitted licence means *the publisher did not state one in its POM*. It does not mean the component is
  unlicensed, and nothing here is an audit. SPDX identifiers are not inferred from the free text, because
  mapping "The Apache Software License, Version 2.0" to `Apache-2.0` is a judgement this build is not
  entitled to make on a publisher's behalf; CycloneDX's `license.name` exists for the quotation.

The document's own metadata component carries `GPL-3.0-or-later` (ADR-0024), written as a literal in the
convention plugin so that changing the project's licence has to touch that line — an SBOM naming the wrong
licence for the work itself is the one field in it nobody would think to check.

## The dependency vulnerability scan

`./scripts/vulnerability-scan.sh` reads the SBOM and asks OSV whether any component has a known advisory.
It runs in the main-branch workflow immediately after `:app:sbom`, and it exits non-zero on a finding.

**As of 2026-08-21 there are no known advisories against any of the 175 components.** That is a result
rather than a default: the script was checked against a poisoned SBOM carrying
`org.apache.logging.log4j:log4j-core:2.14.1`, and it reported all seven Log4Shell advisories and failed.

`curl` against OSV's documented batch endpoint rather than a third-party scanning action. Every other
action in this repository is first-party — `actions/*` and `gradle/*` — and the SBOM already carries every
purl the query needs, so the scan introduces nothing new to trust.

**It says nothing about reachability.** An advisory in a transitive library the app never calls fails the
build exactly like one in a library it calls on every screen. That is the right default for a release
gate: deciding a vulnerability is unreachable is a judgement that belongs in a written exemption, not in a
script's silence. There is no exemption mechanism yet, and the first time one is genuinely needed is the
right time to design it.

## Pre-release checklist

Use `PRODUCT_SPEC 25` verbatim. Do not mark an item complete on the strength of a happy-path screen;
`PRODUCT_SPEC 21` requires error, loading, empty, offline and permission states, accessibility semantics,
and evidence that nothing private is logged.

Three separate device runs have each found defects that the entire unit suite passed through — eight in
the audit of 2026-08-16, four more on 2026-08-20, and three of those four were the same shape: a feature
that worked perfectly and could not be reached from any screen. **A green build is not a tested build.**
