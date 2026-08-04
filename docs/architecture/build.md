# Build and quality gates

## Toolchain

| Piece | Version | Where it is pinned |
| --- | --- | --- |
| Gradle | 8.14.3 | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 8.12.0 | `gradle/libs.versions.toml` |
| Kotlin | 2.2.0 | `gradle/libs.versions.toml` |
| KSP | 2.2.0-2.0.2 | `gradle/libs.versions.toml` |
| compileSdk / targetSdk | 36 | `gradle/libs.versions.toml` |
| minSdk | 26 | `gradle/libs.versions.toml` |
| Java bytecode | 17 | convention plugins |
| ktlint | 1.5.0 (plugin 12.3.0) | `gradle/libs.versions.toml` |
| detekt | 1.23.8 | `gradle/libs.versions.toml` |

Every version is a fixed string. There is no dynamic version, no `+` and no Git dependency
(`PRODUCT_SPEC 16.1`). Repositories are restricted: Google's Maven is content-filtered to
`com.android.*`, `com.google.*`, `androidx.*` and `android.*`, so a typo cannot silently pull an
unrelated group from it, and `RepositoriesMode.FAIL_ON_PROJECT_REPOS` stops a module from adding its
own.

## Convention plugins

`build-logic/` is an included build. Its plugins are the only place SDK levels, Java version, lint
configuration and the quality gate are expressed:

| Plugin | Applies to |
| --- | --- |
| `shelfplayer.android.application` / `.compose` | `:app` |
| `shelfplayer.android.library` / `.compose` | `:core:*`, `:data:*` Android modules |
| `shelfplayer.jvm.library` | `:core:model`, `:core:common`, `:core:testing`, `:domain` |
| `shelfplayer.android.room` | `:core:database` |
| `shelfplayer.hilt` | every module that injects |
| `shelfplayer.quality` | every project, including the root |

## `verifyDebug`

`PRODUCT_SPEC 16.5` requires one command. The root task fans out to a per-module `verifyDebug`
registered by `shelfplayer.quality`, which depends on whichever of these the module actually has:

| Gate | Android module | JVM module |
| --- | --- | --- |
| Formatter | `ktlintCheck` | `ktlintCheck` |
| Static analysis with type resolution | `detektDebug`, `detektDebugUnitTest` | `detektMain`, `detektTest` |
| Android Lint | `lintDebug` | — |
| Unit tests | `testDebugUnitTest` | `test` |
| Assembly | `assembleDebug` | — |
| Room schema | `:core:database:verifyRoomSchemas` | — |

The wiring resolves task names lazily and **fails loudly** if a module has Kotlin sources but no
detekt task carrying a classpath. A gate that silently stops running is worse than one that fails,
because the check still reports green.

### Type resolution

detekt's variant tasks (`detektDebug`) and compilation tasks (`detektMain`) carry the compile
classpath; the bare `detekt` task does not. Only the former can evaluate `ForbiddenMethodCall`, which
is what enforces "no direct `System.currentTimeMillis()`" and "no `println`" from
`PRODUCT_SPEC 16.3`. `verifyDebug` therefore depends on the type-resolving tasks, never on `detekt`.

### Warnings as errors

`allWarningsAsErrors` is bound to `-Pshelfplayer.warningsAsErrors`, which CI passes and local builds
do not. A work-in-progress slice stays runnable; nothing merges with a warning.

## Dependency locking and verification

Both are configured; both need one bootstrap run in an environment with full repository access.

- **Locking** is activated for every configuration by `shelfplayer.quality`. Gradle's default lock
  mode resolves normally when no lock state exists, so this is inert until
  `scripts/update-dependency-locks.sh` writes the `gradle.lockfile` files.
- **Verification** ships `gradle/verification-metadata.xml` with the policy
  (`verify-metadata=true`, `verify-signatures=false`) and no checksums yet.
  `gradle.properties` sets `org.gradle.dependency.verification=lenient`, so unverified artifacts are
  reported rather than fatal. `scripts/bootstrap-dependency-verification.sh` generates the checksums;
  flip to `strict` in the same commit. See [ADR-0006](../adr/0006-dependency-locking-and-verification.md).

## Room schemas

KSP exports each schema version to `core/database/schemas`, which is committed.
`:core:database:verifyRoomSchemas` fails if the file for the current `@Database(version = ...)` is
missing, and CI additionally runs `git diff --exit-code` over that directory so an uncommitted schema
change cannot merge. This is what makes `PRODUCT_SPEC 13.1`'s ban on destructive migration
enforceable: without the exported schema, a column change is invisible in a diff.

## Configuration cache

Off for Phase 0. The protobuf, KSP and AGP plugin combination has not been validated against it here,
and a build that fails only on a clean CI checkout is worse than one that is slightly slower. Turning
it on is tracked as a follow-up.
