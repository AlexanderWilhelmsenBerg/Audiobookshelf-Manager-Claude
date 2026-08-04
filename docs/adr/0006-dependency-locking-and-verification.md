# ADR-0006: Dependency locking and verification, bootstrapped separately

- **Status:** Accepted
- **Date:** 2026-08-04
- **Requirements:** PRODUCT_SPEC 16.1, 15, 18

## Context

`PRODUCT_SPEC 16.1` requires dependency locking and dependency verification metadata; `15` requires
build dependency verification and locking to be enabled.

Gradle can only record a checksum for an artifact it has actually resolved. Generating
`gradle/verification-metadata.xml` therefore requires a build that can reach **every** configured
repository — including Google's Maven, which hosts the Android Gradle Plugin, AndroidX, Room, Media3
and the Compose libraries.

Committing a `verification-metadata.xml` that enables verification with no checksums makes *every*
dependency fail. Committing empty or hand-written lockfiles is worse: they would be wrong, and being
wrong quietly is the failure mode locking exists to prevent.

## Decision

Configure both mechanisms fully; bootstrap their generated data in one explicit step.

- **Locking.** `shelfplayer.quality` calls `dependencyLocking { lockAllConfigurations() }` for every
  project. Gradle's default lock mode resolves normally when no lock state is present, so this is
  inert until lockfiles exist — it cannot break a build, and it cannot silently claim a guarantee
  either. `scripts/update-dependency-locks.sh` writes them via the root `resolveAndLockAll` task.
- **Verification.** `gradle/verification-metadata.xml` ships with the policy
  (`verify-metadata=true`, `verify-signatures=false`) and an empty `<components>`.
  `gradle.properties` sets `org.gradle.dependency.verification=lenient`, so unverified artifacts are
  **reported** rather than fatal. `scripts/bootstrap-dependency-verification.sh` generates the
  checksums; the same commit flips the property to `strict`.

`verify-signatures` stays `false`. Several AndroidX and Google artifacts are unsigned, so enabling it
today would produce a wall of failures that says nothing about supply-chain risk. SHA-256 checksums
pinned against the resolved version set are the guarantee that actually holds here.

## Consequences

- Between now and the bootstrap run, verification reports but does not enforce. `gradle.properties`
  and this ADR both say so; the gap is visible rather than assumed away.
- Every version-catalog change needs a lockfile refresh, and the lockfile diff becomes part of code
  review — which is the point.
- The bootstrap must run somewhere with unrestricted access to Maven Central and Google's Maven. A
  network-restricted environment can build the project but cannot generate this metadata.
- The Gradle distribution itself is verified by `gradle/actions/wrapper-validation` in CI. Adding
  `distributionSha256Sum` to `gradle-wrapper.properties` is a further hardening step and is listed as
  a follow-up in `CHANGELOG.md`.
