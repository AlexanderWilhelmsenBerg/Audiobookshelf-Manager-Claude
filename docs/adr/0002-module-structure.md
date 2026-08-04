# ADR-0002: Phase 0 module structure

- **Status:** Accepted
- **Date:** 2026-08-04
- **Requirements:** PRODUCT_SPEC 9.2, 9.3, 16.4, 20 (Phase 0)

## Context

`PRODUCT_SPEC 9.2` suggests 26 Gradle modules and then says, in the same section, that a project may
start with fewer if build complexity becomes counterproductive, and that a reasonable first milestone
combines all `feature:*` code in `:app` while keeping core, data and playback modules separate.

Creating all 26 now would mean roughly a dozen modules containing nothing, each adding a
configuration and a compile task to every build, and each needing a `build.gradle.kts` that would be
rewritten when its real content arrives.

## Decision

Phase 0 creates ten modules — the ones with Phase 0 content and the ones that carry a boundary the
dependency rules constrain:

```text
:app  :core:model  :core:common  :core:designsystem  :core:database
:core:datastore  :core:network  :core:testing  :data:library  :domain
```

`feature.home`, `feature.library` and `feature.book` are **packages inside `:app`**, named exactly as
`PRODUCT_SPEC 16.4` prescribes.

`:core:model`, `:core:common`, `:core:testing` and `:domain` use the Kotlin/JVM plugin rather than the
Android library plugin, so an Android import in domain policy fails to compile.

Modules for later phases (`:core:security`, `:data:auth`, `:data:playback`, `:data:downloads`,
`:data:management`, `:playback:service`, `:auto`) are listed in
`docs/architecture/module-boundaries.md` with the phase and requirement IDs that introduce them.

## Consequences

- The boundaries that matter are enforced by the compiler today: `:domain` cannot see Android,
  `:app` cannot see a Room entity or a gateway internal.
- Feature code has no compile-time guard against depending on another feature. That is the accepted
  cost, and it is what promotion to real modules will buy.
- Promoting a feature is a directory move plus a `build.gradle.kts`; no import changes, because the
  package names are already correct.
- Splitting `:app` is expected before Phase 5, when management and user-administration screens make
  it large enough that per-feature build times matter.
