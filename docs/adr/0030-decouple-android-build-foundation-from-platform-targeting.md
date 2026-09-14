# ADR-0030: Decouple the Android build foundation from platform targeting

- **Status:** Accepted
- **Date:** 2026-09-14
- **Requirements:** PRODUCT_SPEC 16.1, 16.3, 20
- **Supersedes:** ADR-0011
- **Clarifies the revisit trigger in:** ADR-0010

## Context

ADR-0011 deliberately held back the next Android build-tool and platform migration while BookWave's required
type-resolving static-analysis gate did not have a stable path on the newer build foundation. That safety
principle remains correct: BookWave must not weaken verification merely to obtain newer tool releases.

The original decision also coupled concerns that have different compatibility and acceptance boundaries:

- Gradle and Android Gradle Plugin define the build foundation.
- Kotlin, KSP and related tools form a compiler/tooling compatibility axis.
- `compileSdk` controls which Android APIs may be compiled against.
- `targetSdk` opts the application into Android platform compatibility behavior.

Those concerns do not need to advance in one migration. In particular, modernizing the build foundation does
not require BookWave to opt into a new target-SDK behavioral contract at the same time.

Architecture decisions should express durable constraints rather than freeze the release numbers that happened
to be current when the decision was written. Exact current releases belong in the compatibility inventory and
staged upgrade plan, where they are deliberately re-resolved before execution.

## Decision

BookWave will treat the Android build foundation, compiler/tooling stack, compile SDK and target SDK as
separate migration axes. They may be coordinated where upstream compatibility requires it, but one does not
automatically force the others to move.

### Build foundation

Gradle and Android Gradle Plugin may move to a newer mutually supported stable foundation without requiring a
simultaneous `compileSdk` or `targetSdk` migration.

A foundation migration must use stable releases and preserve the repository's complete verification contract.
Before implementation, the exact compatibility frontier must be resolved again from current upstream
documentation. Releases recorded in a dated inventory are evidence, not promised targets.

### Static-analysis invariant

BookWave must retain a mandatory, type-aware static-analysis gate.

A build-tool or compiler migration must not:

- disable type-aware analysis;
- silently reduce the source sets or modules being analysed;
- turn mandatory findings into ignored warnings merely to make the migration pass;
- require a prerelease static-analysis tool solely to unlock newer build-tool releases; or
- otherwise weaken `verifyDebug` relative to the accepted baseline.

The preferred path is a stable detekt release that supports the selected build and compiler foundation. Detekt
itself is not the architectural invariant. A different stable analysis solution may replace it only through an
explicit decision that demonstrates equivalent or stronger type-aware coverage, remains pinned and
reproducible, and participates in the normal mandatory verification gate.

Prerelease detekt releases may be used on disposable investigation branches to measure compatibility. They are
not accepted as BookWave's production quality gate merely to accelerate dependency migration.

### `compileSdk`

`compileSdk` is managed independently from `targetSdk`. It may be raised when required by a stable dependency,
development requirement or supported Android API, provided the selected build foundation supports that API
level and the normal verification gates pass.

Raising `compileSdk` alone is not acceptance of the corresponding target-SDK behavioral changes.

### `targetSdk`

A `targetSdk` change is a deliberate platform-behavior migration. It receives its own compatibility review and
device acceptance appropriate to the Android release being targeted.

For BookWave this includes explicit revalidation of playback, foreground-service/background-audio behavior,
notifications, storage/file access, background work and any other target-SDK-gated behavior relevant to the
application. A build-foundation migration must not raise `targetSdk` merely because a newer build tool can
support it.

## Foundation migration exit condition

The final build-foundation switch may proceed when a mutually supported stable build stack can preserve
BookWave's mandatory verification contract, including stable type-aware static analysis.

Readiness work that does not weaken or change the accepted production foundation may proceed before that exit
condition is met. Examples include removing already-deprecated build APIs, auditing public Android Gradle
Plugin DSL/variant usage, checking package uniqueness, documenting built-in Kotlin migration seams and
measuring third-party plugin compatibility.

## Dependency locking

ADR-0010's retry trigger is the build-foundation migration itself. The dependency-locking experiment does not
wait for a `compileSdk` or `targetSdk` change.

During the foundation migration:

1. activate dependency locking using ADR-0010's retained retry procedure;
2. regenerate lock state from a real build;
3. run normal verification without lock-writing flags; and
4. retain locking only if ordinary builds consume the generated state successfully.

Strict dependency verification remains mandatory regardless of the locking result.

## Consequences

- Build-foundation upgrades are no longer blocked by a platform-targeting change that they do not inherently
  require.
- BookWave does not adopt prerelease static-analysis tooling merely to obtain newer build tools.
- Type-aware static analysis remains a hard quality invariant.
- A future replacement for detekt is possible only through an explicit equivalence decision, not by silently
  weakening CI.
- `compileSdk` and `targetSdk` can evolve on their own appropriate schedules.
- Target-SDK behavioral changes receive focused runtime/device acceptance instead of being mixed into
  build-system migrations.
- ADR-0010 dependency locking is retried at the point most relevant to its original failure: the
  build-foundation migration.
- Exact release numbers remain in dated compatibility documents rather than being frozen into this ADR.

## Rejected alternatives

### Adopt a prerelease detekt line to unblock the foundation immediately

Rejected. A prerelease may establish that the technical path is viable, but BookWave does not need the
migration urgently enough to make prerelease static-analysis software a mandatory quality dependency.

### Disable type-aware analysis during the migration

Rejected. This directly reduces an existing verification requirement and converts a compatibility problem into
technical debt.

### Keep the build foundation, compile SDK and target SDK coupled

Rejected. The changes have different compatibility boundaries and different acceptance requirements. Coupling
them enlarges migrations unnecessarily and makes failures harder to attribute.

### Permanently require detekt as the named implementation

Rejected. BookWave requires the quality property that detekt currently supplies. Encoding one implementation
as permanent architecture would prevent migration to an equivalent or stronger stable tool if the ecosystem
changes.
