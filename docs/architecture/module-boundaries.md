# Module boundaries

`PRODUCT_SPEC 9.3` states the dependency rules. This records how each one is *enforced*, because a
rule a build cannot check is a rule that erodes.

## The dependency graph

```text
:app ─┬────────► :data:library ──► :domain ──► :core:model
      │              │  │  │                       ▲
      ├────────► :data:auth ──┐                    │
      │              │  │  │  └──► :core:network ──┤
      │              │  │  └─────► :core:database  │
      │              │  └────────► :core:datastore │
      ├────────► :data:settings ──► :core:datastore
      ├──► :core:designsystem                      │
      └──► :core:common ────────────────────────────┘
```

`:data:auth` and `:data:library` have the same shape and the same dependencies. They are separate
modules because their contents are separate concerns, not because their graphs differ.

`:data:settings` is narrower: `:domain` and `:core:datastore`, nothing else. It exists so a screen does
not have to name `AppSettingsDataSource`. Two reasons. A screen that reaches the store directly cannot be
tested without a DataStore on disk; and the *meaning* of a setting — its default, its place in
`SET-001`'s five-level precedence chain — then belongs to whichever screen happened to need it first
instead of to one owner.

**One call site has not moved yet.** `AppViewModel` still injects `AppSettingsDataSource` for the
appearance settings, and its `AppUiState` exposes the generated `ThemeMode` enum. Routing it through
`SettingsRepository` needs a `:core:model` theme type first, which is `SET-002` (Appearance) work rather
than part of the setting that prompted this module.

`:core:testing` is a `testImplementation` dependency only.

## How each rule is enforced

| Rule (PRODUCT_SPEC 9.3) | Enforcement |
| --- | --- |
| Domain depends only on core model/common | `:domain` uses the Kotlin/JVM plugin. An Android import does not compile. |
| Network DTOs stay inside data/network | The gateway signature uses `:core:model` types. `:core:network` exposes no wire type, so nothing else can name one. |
| Room entities stay inside database/data | `:core:database` is an `implementation` dependency of `:data:library` and `:data:auth` only. `*Entity` is off the classpath of `:domain` and `:app`. |
| Proto settings types stay inside datastore/data | `:core:datastore` is an `implementation` dependency of `:data:settings`, so the generated `AppSettings` message stops there. Not yet total: `:app` still has `:core:datastore` on its classpath for `AppViewModel` (see above). |
| Room itself stays inside `:core:database` | `DatabaseTransactionRunner` names no Room type, so a data module can be transactional with `androidx.room` off its compile classpath. See below. |
| Data modules implement domain interfaces | `LibraryDataModule`, `AuthDataModule` and `SettingsDataModule` bind `Default*Repository` to the `:domain` interface. `:app` injects the interface. |
| No cyclic module dependencies | The graph above is acyclic; Gradle rejects a cycle. |
| `:app` performs final wiring | `AppModule` in `:app` binds the gateway and the log sink — the two seams a later phase replaces. |

## Why the token provider is *not* final wiring

`TokenProvider` is declared in `:core:network` and implemented by `SessionTokenProvider` in
`:data:auth`, which binds it in its own `AuthDataModule`. It lived in `:app` first, on the reasoning
that `:app` was the only module seeing both `:core:network` and `:core:datastore`. That reasoning was
incomplete: `:data:auth` sees both as well, and it owns the sign-out that has to invalidate the
credential.

The distinction is not bookkeeping. `SessionTokenProvider` caches a **decrypted token** in memory,
because `TokenProvider.currentToken()` is synchronous (an OkHttp interceptor is) while the store
suspends and does a Keystore decryption. That cache has to be cleared at the same moment the stored
copy is, or the process keeps authenticating after the user believes it stopped. Keeping the class in
`:app` put the cache and the code responsible for clearing it in different modules, and made the
credential holder nameable from the UI layer. It is now `:data:auth`-local, and no module outside it
can reach the object holding a token.

`NoTokenProvider` in `:core:network` remains for a graph with no credential store at all.

## Two clients, not one

`PRODUCT_SPEC 9.4` asks for "qualifiers for authenticated vs unauthenticated clients", and the reason
is concrete. `GET /status` and `POST /login` are addressed at a server the user is **not** signed in to
— often one they have just typed the address of. The ambient token belongs to whichever profile is
active, possibly on a different host, so a single client would hand one server's credential to
another. `@UnauthenticatedClient` has no `AuthorizationInterceptor`; the auth endpoints use it and pass
their credential explicitly.

`AuthorizationInterceptor` also leaves an existing `Authorization` header alone. That is what lets a
call name the profile it is acting for instead of inheriting the active one — a library sync for
profile B must not be signed with profile A's token because A is on screen.

## The transaction seam

`PRODUCT_SPEC LIB-001` needs a sync to apply completely or not at all, so `:data:library` needs
transactions — but `PRODUCT_SPEC 9.3` keeps Room inside `:core:database`.

The first attempt was an extension function on `ShelfPlayerDatabase`. That does not hold the
boundary: calling any member of `ShelfPlayerDatabase` makes the caller resolve its supertype, and
`:data:library` failed to compile with *"Cannot access 'RoomDatabase' which is a supertype of
'ShelfPlayerDatabase'"*. The boundary was right; the seam was in the wrong place.

`DatabaseTransactionRunner` fixes that by naming no Room type in its signature:

```kotlin
interface DatabaseTransactionRunner {
    suspend operator fun <R> invoke(block: suspend () -> R): R
}
```

`:data:library` gets Room on its **test** classpath only, where a real in-memory database backs the
repository tests. It is a plain `interface`, not a `fun interface`: SAM conversion cannot carry a
generic method.

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
| `:core:security` | — | **Not created.** AUTH-003 landed in `:core:datastore` instead: `KeystoreTokenCipher` and `SessionTokenStore` are twenty lines of platform API next to the store they encrypt for, and a module whose only content is one cipher buys a boundary nothing was crossing. |
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
