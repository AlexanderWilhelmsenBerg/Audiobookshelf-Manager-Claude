# Architecture overview

This describes what Phase 0 actually built. It is not a plan; everything below exists in the
repository and is exercised by tests.

## The shape

```text
        ┌──────────────────────────────────────────────┐
        │  :app                                        │
        │  MainActivity → NavHost                      │
        │  feature.home / feature.library / feature.book│
        │  ViewModels expose StateFlow<*UiState>       │
        └───────────────┬──────────────────────────────┘
                        │ use cases only
        ┌───────────────▼──────────────────────────────┐
        │  :domain            (JVM, no Android)        │
        │  LibraryRepository, ProfileRepository        │  ← interfaces
        │  Observe*/Refresh* use cases, sorting policy │
        └───────────────┬──────────────────────────────┘
                        │ implemented by
        ┌───────────────▼──────────────────────────────┐
        │  :data:library                               │
        │  DefaultLibraryRepository                    │
        │  DefaultProfileRepository                    │
        │  FixtureLibraryBootstrapper                  │
        └───┬───────────────┬──────────────────┬───────┘
            │               │                  │
   ┌────────▼─────┐ ┌───────▼───────┐ ┌────────▼────────┐
   │ :core:database│ │ :core:network │ │ :core:datastore │
   │ Room = truth  │ │ gateway + fake│ │ Proto DataStore │
   └───────────────┘ └───────────────┘ └─────────────────┘

   :core:model    AppResult, AppError, domain types      (JVM, zero deps)
   :core:common   dispatchers, clock, redacted logging   (JVM)
   :core:designsystem  Material 3 theme, state views
   :core:testing  shared test doubles                    (JVM)
```

## The four rules that shape everything else

**1. Room is the read source.** Every `observe*` on a repository is a Room query. Nothing in the UI
observes the network. A refresh writes into Room and the UI updates because the query re-emits
(`PRODUCT_SPEC 9.1`, `LIB-001`). The practical consequence: a failed refresh cannot blank the screen,
because it never had the power to.

**2. Types enforce the layer boundaries, not conventions.**

- `:core:model`, `:core:common` and `:domain` use the plain Kotlin/JVM plugin. An `import android.*`
  in domain policy is a compile error, not a review comment.
- `:core:database` and `:core:network` are `implementation` dependencies of `:data:library`. Room
  entities and gateway internals cannot be named from `:domain` or `:app` because they are not on
  those modules' compile classpaths.
- The gateway returns `:core:model` types, so no wire type exists outside `:core:network`.

**3. Everything crosses a boundary as `AppResult<T>`.** Repositories and the gateway never throw
across a layer. `resultOf` is the single place allowed to catch `Throwable`, and it rethrows
`CancellationException` (`PRODUCT_SPEC 14.2`).

**4. Nothing private reaches a log.** A log field's *type* decides whether its value survives:
`LogField.Secret` has no value to render at all, and `MediaTitle`, `ServerHost`, `Username`,
`FilePath` and `Url` redact by default. A developer adding a log line cannot leak a book title,
because the only way to attach one is through a type that redacts it (`PRODUCT_SPEC 14.5`).

## The Phase 0 vertical slice, end to end

1. `ShelfPlayerApplication.onCreate` launches `FixtureLibraryBootstrapper.seedIfNeeded()` into the
   injected `@ApplicationScope` — never `GlobalScope`, and never blocking the main thread.
2. The bootstrapper asks `FakeAudiobookshelfGateway` for the server and profile, writes both to Room,
   records the active profile in Proto DataStore, and calls `LibraryRepository.refresh`.
3. `DefaultLibraryRepository.refresh` pulls libraries and books from the gateway, maps them to
   entities, and writes everything in **one transaction**.
4. It marks the seed complete in DataStore only *after* the transaction commits, so an interrupted
   first launch retries instead of leaving a half-populated library.
5. `HomeViewModel` combines the active profile, the library list and its own refresh state into
   `HomeUiState`. `HomeScreen` renders one of loading / empty / error / content.

Every step is covered by a test: `FakeAudiobookshelfGatewayTest`, `DefaultLibraryRepositoryTest`
(Robolectric, real in-memory Room), `LibraryUseCaseTest` and `HomeViewModelTest`.

## Identity

Remote ids are unique per server, never globally (`PRODUCT_SPEC 13.1`). Every remote entity stores
`serverId` and `remoteId` *and* a derived single-column key, because Room's `@Relation` can only join
on one column. `EntityKey` owns that format; no other module knows the separator, so changing it is a
migration rather than a rewrite.

Per-profile rows (`media_progress`, `sync_state`) additionally key on `profileId`, and progress is
filtered by profile **in SQL**. That makes `PRODUCT_SPEC 5.2` structural: a screen cannot render
another account's position, because those rows never leave the database.

## Where each concern lives

| Concern | Home | Requirement |
| --- | --- | --- |
| Result and error taxonomy | `:core:model` `AppResult`, `AppError` | 14.1, 14.2 |
| Injected clock | `:core:common` `AppClock` (wall clock *and* monotonic) | 16.3 |
| Injected dispatchers | `:core:common` `@Dispatcher(...)`, `@ApplicationScope` | 16.3, 22.10 |
| Redaction | `:core:common` `LogField`, `Redactor`, `RedactingLogger` | 14.5 |
| Android log sink | `:app` `AndroidLogSink` — the only `android.util.Log` call site | 14.5 |
| Series ordering | `:core:model` `SeriesSequence` + `:domain` `sortBooks` | LIB-003 |
| URL normalization | `:core:network` `ServerUrlNormalizer` | AUTH-001 |
| HTTP → `AppError` | `:core:network` `NetworkErrorMapper` | 14.3 |
| Schema and migrations | `:core:database`, schemas exported to `core/database/schemas` | 13.1 |
| Settings | `:core:datastore` Proto DataStore | SET-001 |

## Deliberate omissions

These are absences with reasons, not gaps:

- **No Retrofit service interfaces.** Defining endpoints without a server to contract-test against
  would violate `PRODUCT_SPEC 22.4`. The HTTP *foundation* (OkHttp stack, interceptors, error mapper,
  JSON configuration) exists; the endpoints arrive with their fixtures in Phase 1.
- **Only three gateway sub-APIs.** `PRODUCT_SPEC 10.4` lists nine. Phase 0 declares the three the
  fake actually implements. Empty marker interfaces for the rest would look like coverage that does
  not exist.
- **`feature:*` are packages, not modules.** `PRODUCT_SPEC 9.2` explicitly sanctions this for the
  first milestone. The package names match the ones the spec prescribes, so promotion is a move.
  See [ADR-0002](../adr/0002-module-structure.md).
- **No deep links.** `PRODUCT_SPEC 15` requires deep links to validate profile and item access.
  There are no permissions to validate yet, so no `<intent-filter>` is registered at all.
