# Module boundaries

`PRODUCT_SPEC 9.3` states the dependency rules. This records how each one is *enforced*, because a
rule a build cannot check is a rule that erodes.

## The dependency graph

```text
:app ──────────► :data:library ──► :domain ──► :core:model
  │                   │  │  │                      ▲
  │                   │  │  └──► :core:network ────┤
  │                   │  └─────► :core:database    │
  │                   └────────► :core:datastore   │
  ├──► :core:designsystem                          │
  └──► :core:common ────────────────────────────────┘
```

`:core:testing` is a `testImplementation` dependency only.

## How each rule is enforced

| Rule (PRODUCT_SPEC 9.3) | Enforcement |
| --- | --- |
| Domain depends only on core model/common | `:domain` uses the Kotlin/JVM plugin. An Android import does not compile. |
| Network DTOs stay inside data/network | The gateway signature uses `:core:model` types. `:core:network` exposes no wire type, so nothing else can name one. |
| Room entities stay inside database/data | `:core:database` is an `implementation` dependency of `:data:library` only. `*Entity` is off the classpath of `:domain` and `:app`. |
| Data modules implement domain interfaces | `LibraryDataModule` binds `Default*Repository` to the `:domain` interface. `:app` injects the interface. |
| No cyclic module dependencies | The graph above is acyclic; Gradle rejects a cycle. |
| `:app` performs final wiring | `AppModule` in `:app` binds the gateway and the log sink — the two seams a later phase replaces. |

## Why `feature:*` are packages, not modules

`PRODUCT_SPEC 9.2` sanctions combining feature code in `:app` for the first milestone while keeping
core, data and playback separate. Phase 0 takes that option, because those are the boundaries the
dependency rules actually constrain: a feature module cannot reach a Room entity today either, since
`:app` cannot.

Package names match `PRODUCT_SPEC 16.4` exactly (`com.example.shelfplayer.feature.home`,
`.feature.library`, `.feature.book`), so promoting one to a Gradle module is a directory move plus a
`build.gradle.kts`. See [ADR-0002](../adr/0002-module-structure.md).

## Modules reserved for later phases

Named here so nobody invents a parallel abstraction for them:

| Module | Phase | Requirements |
| --- | --- | --- |
| `:core:security` | 1 | AUTH-003 — Keystore-backed token storage |
| `:data:auth` | 1 | AUTH-001…AUTH-004 |
| `:playback:service` | 2 | PLAY-001…PLAY-008, ROUTE-001 |
| `:data:playback` | 2 | PLAY-004, PLAY-005 |
| `:data:downloads` | 3 | DL-001…DL-006 |
| `:data:management` | 5 | MGR-001…MGR-007, USER-001…USER-003 |
| `:auto` | 6 | Android Auto |

## Naming

`PRODUCT_SPEC 16.4`, applied throughout: `*Screen` for route-level composables, `*Route` for the
navigation/wiring composable, `*ViewModel`, `*UiState`, `*Repository` interface with
`Default*Repository` implementation, `*Entity`, `*Dao`, `*UseCase` only where the logic is
non-trivial. `Manager`, `Helper` and `Utils` do not appear.
