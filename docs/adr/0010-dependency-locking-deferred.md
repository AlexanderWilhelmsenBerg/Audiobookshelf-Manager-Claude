# ADR-0010: Dependency locking cannot be turned on with this toolchain

- **Status:** Accepted
- **Date:** 2026-08-07
- **Requirements:** PRODUCT_SPEC 16.1, 15, 18
- **Supersedes the locking half of:** ADR-0006

## Context

ADR-0006 configured both halves of `PRODUCT_SPEC 16.1` — locking and verification — and deferred their
generated data to a bootstrap step. Verification was completed on 2026-08-07: `strict`, 868 components,
1,540 SHA-256 checksums, and the build passes against it.

Locking was attempted at the same time and does not work on this toolchain. This ADR records what was
tried, what the failure is, and why the conclusion is "not yet" rather than "not configured".

## What was tried

**Attempt 1 — the documented path.** `resolveAndLockAll --write-locks`, as ADR-0006 specified. It
resolves every configuration that can be resolved standing alone, and reports that it skipped around
seventy that cannot: AGP's per-variant classpaths, KSP processor classpaths, unit-test runtime
classpaths. Those resolve during a real build, so the next ordinary `verifyDebug` fails on the first one
it reaches.

**Attempt 2 — lock state from a real build.** `./gradlew verifyDebug --write-locks --rerun-tasks`, so
that every configuration the build actually resolves is resolved and recorded. Ten minutes, succeeds,
writes twelve lockfiles. The next ordinary `verifyDebug` still fails.

**Attempt 3 — narrow the scope.** Replace `lockAllConfigurations()` with locking only the compile and
runtime classpaths of the production variants, on the theory that the test and tooling classpaths were
the problem. Regenerate. The failure moves to a *shipping* classpath and persists.

## The failure

Gradle writes a lock state for a configuration that it then rejects when the same configuration is
resolved by a task:

```
$ ./gradlew verifyDebug --write-locks --rerun-tasks
BUILD SUCCESSFUL

$ ./gradlew --stop && ./gradlew verifyDebug
> Task :core:datastore:transformDebugClassesWithAsm FAILED
   > Resolved 'org.jetbrains.kotlin:kotlin-stdlib-common:2.2.0'
     which is not part of the dependency lock state
```

The lockfile it just wrote records that artifact for two of the four locked configurations:

```
org.jetbrains.kotlin:kotlin-stdlib-common:2.2.0=debugCompileClasspath,releaseCompileClasspath
```

`transformDebugClassesWithAsm` resolves `debugRuntimeClasspath`, which is locked and which the
lock-write pass recorded *without* that artifact. `kotlin-stdlib-common` is a Kotlin metadata variant;
the resolution AGP's bytecode-transform task performs selects it, and the resolution Gradle performs
when writing lock state does not. The two disagree about what the configuration contains, and no
re-run reconciles them because both are behaving as designed.

It is not caused by anything this repository chose. It reproduces with Kover absent from the module, on
a shipping classpath, from a clean daemon.

## Decision

**Locking stays off.** `shelfplayer.quality` no longer activates it and no lockfiles are committed. A
mechanism that cannot pass its own check is not a weaker gate than none — it is a broken build, and the
pressure to make a broken build pass is what turns a gate into a `// TODO: re-enable`.

`PRODUCT_SPEC 16.1`'s other requirements are met and are what carry the guarantee meanwhile:

- **Dependency verification is `strict`** and checks every artifact the build resolves, in every
  configuration, against a committed SHA-256. An artifact that is not bit-for-bit what was reviewed
  fails the build. This is the stronger of the two mechanisms.
- **No dynamic versions, no `+` versions, no unpinned Git dependencies**, every version pinned in
  `gradle/libs.versions.toml`. Direct dependencies therefore cannot drift at all.

What is genuinely lost is narrow: a **transitive** dependency's version changing because an upstream
POM changed, without any artifact this build already knows about being altered. Verification would fail
that too the moment the new artifact was fetched, because it has no recorded checksum — so the practical
gap is that the failure reads as "unverified artifact" rather than "version drifted".

## When to revisit

At the **Gradle 9 / AGP 9 upgrade**, which is coming with the move to `compileSdk 37`. The divergence is
in how the two resolutions select variants, and a major version of both tools is the most likely place
for it to change. `scripts/update-dependency-locks.sh` is kept for that retry: it encodes the real-build
procedure that attempt 2 established, which is correct and worth not rediscovering.

The retry is a fifteen-minute experiment — activate locking, run the script, run `verifyDebug` with no
write flags — and this ADR records exactly what "it worked" would look like.

## Consequences

- `PRODUCT_SPEC 16.1` is **partially met**, and `docs/roadmap-to-phase-1-close.md` says so rather than
  listing locking as done.
- A reviewer seeing `dependencyLocking` absent from the convention plugin has this document rather than
  an unexplained gap.
- The next person to try does not repeat three attempts to reach the same wall.
