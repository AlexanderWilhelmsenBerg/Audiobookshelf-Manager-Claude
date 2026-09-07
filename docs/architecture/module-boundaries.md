# Module boundaries

**Classification:** Current contract.  
**Current as reviewed:** 2026-09-07.

`PRODUCT_SPEC 9.3` defines the dependency direction. This document records how the current repository
implements it. Older phase documents may describe modules as prospective that are now real; `main` is the
implementation authority.

## Current module families

```text
:app
 ├─ UI/navigation/feature packages
 ├─ final Android wiring and WorkManager entry points
 ├─ depends on domain/repository contracts and Android-facing feature modules
 │
 ├─► :playback
 │    Media3/ExoPlayer, MediaLibraryService, Android Auto, sleep timer, audio routing
 │
 ├─► :data:auth
 ├─► :data:library
 ├─► :data:downloads
 └─► :data:settings
       repository implementations/adapters

:data:* ─► :domain ─► :core:model
   │          │            ▲
   │          └────► :core:common
   ├─► :core:network       │
   ├─► :core:database      │
   └─► :core:datastore ────┘

:core:designsystem   shared Android UI primitives/theme
:core:testing        test-only shared doubles/helpers
```

This is a dependency-direction sketch, not a promise that every data module uses every core module.
Individual `build.gradle.kts` files remain the exact graph.

## The boundaries that matter most

### `:core:model` is portable by construction

`:core:model` uses the plain Kotlin/JVM plugin and has no project dependencies. Domain/value types do not
acquire Android framework dependencies accidentally; an `import android.*` is a compile failure rather than
a review convention.

This is also the strongest existing seam for future portability work. It is not a reason to convert every
module to multiplatform today.

### `:domain` owns policy and repository contracts

`:domain` is a Kotlin/JVM module. It depends on `:core:model` and `:core:common`, not Android storage,
networking, Room, Compose or Media3.

Cross-platform or system-surface work should reuse policy from here where the policy is genuinely portable
rather than teaching each UI/controller its own rule.

### Data modules own implementations, not UI

`:data:auth`, `:data:library`, `:data:downloads` and `:data:settings` adapt persistent/network/platform data
to domain-facing contracts.

Room entities, Proto messages and network DTOs are implementation details of their owning core/data seams;
they do not become UI state merely because a screen needs one field.

### Appearance no longer bypasses the settings repository

An older version of this document recorded `AppViewModel -> AppSettingsDataSource` and generated
`ThemeMode` as a deliberate temporary exception. That exception is **closed**: merged PR #88 moved
appearance ownership behind the repository/model boundary.

Do not preserve the old direct-DataStore path in new work merely because historical documentation described
it.

### Playback is a real architecture boundary

`:playback` now owns the Media3/ExoPlayer/service surface, including Android Auto and playback-system
integration. It is not a future `:playback:service` placeholder.

The domain/repository contracts around playback/session/progress are deliberately kept separate from the
Android media engine. See [`playback.md`](playback.md).

### Downloads are a real data module

`:data:downloads` owns the offline manifest, file transfer/storage verification and storage-volume adapter
work. WorkManager scheduling/worker entry points are Android application wiring, while the durable download
state and repository semantics remain below the UI.

A future recovery UX must not collapse WorkManager execution state and durable file/manifest state into one
enum merely for display convenience.

## How important rules are enforced

| Rule | Enforcement |
| --- | --- |
| Domain policy cannot depend on Android | `:domain` is JVM-only. |
| Core model cannot depend on platform/data modules | `:core:model` is JVM-only with zero project dependencies. |
| Network DTOs do not define domain/API surface | `:core:network` maps through core model/domain-facing gateway contracts. |
| Room details stay behind database/data seams | Room lives in `:core:database`; data repositories map entities to model types. |
| Proto settings types stay behind datastore/settings seams | UI consumes settings/domain models/repositories rather than generated Proto messages. |
| Data implementations fulfill domain contracts | Hilt modules bind `Default*Repository` implementations to domain interfaces. |
| Module graph remains acyclic | Gradle dependency graph rejects cycles. |
| Android/system wiring stays at Android boundaries | Activities, services, WorkManager and platform adapters stay out of JVM policy modules. |

## Authentication/network client boundary

BookWave intentionally distinguishes authenticated and unauthenticated network work. Sign-in/status requests
must not inherit the active profile's credential simply because another account is active in the process.

`TokenProvider` is declared at the network seam and its credential-holding implementation is owned by auth,
where sign-out/credential invalidation can clear both durable and in-memory state together. UI code must not
hold decrypted tokens.

## Database transaction seam

Library synchronization needs atomic Room transactions without making `:data:library` compile against Room
internals. `DatabaseTransactionRunner` is the narrow interface that permits transactional policy without
leaking `RoomDatabase` across the boundary.

This remains a useful pattern: if a data/domain module needs one platform capability, expose the capability
it needs instead of importing the entire platform implementation type.

## Why feature packages remain inside `:app`

Feature UIs such as home/library/book/settings remain packages inside `:app` rather than one Gradle module
per screen. That is currently intentional, not unfinished Phase 0 scaffolding.

The repository already has strong boundaries where correctness and dependency direction require them:
model/domain, data, playback, storage/network and UI. Split a UI feature into another Gradle module only when
there is concrete coupling/build/ownership value, not because a historical diagram reserved a module name.

Package naming still makes a future extraction possible if evidence justifies it.

## No reserved module list

The old document reserved names such as `:playback:service`, `:data:playback`, `:data:downloads`, `:auto` and
`:data:management` for later phases. That list is retired:

- some concerns landed under different, better boundaries (`:playback`, `:data:downloads`);
- some management behavior remains in existing data/app seams rather than deserving a module solely because
  a phase document predicted one;
- Android Auto belongs to the playback/media-session boundary rather than an `:auto` island.

Do not create a module to satisfy a retired reservation. Create one when present dependencies need the
boundary.

## Naming

The repository continues to prefer meaningful role names (`*Screen`, `*Route`, `*ViewModel`, `*Repository`,
`Default*Repository`, `*Entity`, `*Dao`, `*UseCase` for real use-case policy) over generic `Manager`, `Helper`
or `Utils` buckets.
