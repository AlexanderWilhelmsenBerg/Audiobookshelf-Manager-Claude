# Changelog

Notable changes to ShelfPlayer. Requirement identifiers refer to `PRODUCT_SPEC.md`.

## Unreleased

### Phase 1 — Authentication and cached browsing (in progress)

**Not complete.** See `docs/handover.md` for a deliverable-by-deliverable status.

- Audiobookshelf contract capture: `.github/workflows/contract-capture.yml` runs the real server
  image and records what it answers, failing if committed fixtures drift (ADR-0007).
- Verified login contract against Audiobookshelf 2.36.0 and encoded it: `AuthService`, `AuthDtos`,
  `AuthMapper`, `AuthSession`. Tokens nest under `user`; `refreshToken` is returned in the body only
  for requests carrying `x-return-tokens: true`; the pre-2.26 `token` is still returned beside
  `accessToken` and is not accepted by `/auth/refresh`.
- `docs/api-compatibility.md` records the authentication endpoints, none of which appear in the
  project's published `openapi.json`.
- Retrofit client (`AudiobookshelfServiceFactory`) and the gateway `auth` sub-API (`AuthApi`,
  `AbsAuthApi`), contract-tested against the committed fixtures with MockWebServer.
- Keystore-backed token storage (AUTH-003): `TokenCipher` as an interface with `KeystoreTokenCipher`
  behind it, `SessionTokenStore` holding one encrypted file per profile and token kind, and
  `SessionTokenProvider` supplying the HTTP layer. Making the cipher an interface is what made the
  requirement about a lost key testable — Robolectric cannot invalidate a real Keystore key.
- **Server profiles from a real sign-in** (AUTH-001, AUTH-002): the `:data:auth` module,
  `DefaultAuthRepository`, and `SessionIdentity` deriving a stable `ServerId`/`ProfileId` so that
  reauthenticating returns to the same profile instead of orphaning its downloads and progress.
- **Session renewal** (AUTH-004): `POST /auth/refresh` with `x-refresh-token`; a session that cannot
  be renewed marks the profile and never signs it out. Exactly one renewal attempt, one retry.
- **Capability handshake** (SYNC-001): `AbsCapabilityResolver` and `DefaultCapabilityRepository`. It
  confirms no capability, because nothing `GET /status` reports is one — an unconfirmed capability is
  unsupported, never assumed.
- Two credential-safety changes: the authentication endpoints moved to a new `@UnauthenticatedClient`
  so a `GET /status` or `POST /login` aimed at a newly typed host cannot carry the active profile's
  token, and `AuthorizationInterceptor` now yields to an explicit `Authorization` header so a call can
  name the profile it acts for.
- Database version 2 with an additive migration and a migration test that builds a real version-1
  database from the committed exported schema.
- Contract fixtures now cover the library shapes: `scripts/seed-contract-media.sh` generates an
  audiobook with the server image's own ffmpeg so the scan produces an item. The item **list** turns
  out to be minified — counts, not contents — so only the expanded single item carries tracks,
  chapters, authors, series and `startOffset`.
- **Libraries and items sync from the server** (LIB-001): `AbsLibraryApi`, `LibraryMapper`, the real
  gateway bound in `AppModule`, and the demo-library bootstrapper removed. An unauthorized library is
  dropped at the gateway, so it is never written to Room rather than hidden by the UI; the grant is
  persisted on the profile in database version 3.
- Sign-in and profile-switch policy (`SignInUseCase`, `SwitchProfileUseCase`), so the remaining UI work is
  screens rather than screens making decisions.
- **Sign-in screen and profile switcher** (AUTH-001, AUTH-002, PRODUCT_SPEC 6.1, 6.5): a two-stage sign-in
  that confirms the server — showing its version and whether the connection is encrypted — before asking
  for a password, and a switcher that lists saved profiles with sign-out and remove. Removal states its
  actual effect: it deletes that profile's local session, progress and downloads, and nothing on the
  server.
- The navigation graph's start destination is decided from observed state, so removing the last profile
  returns to onboarding rather than leaving an unusable home.
- **The app opens on the books, not on a list of libraries** (LIB-002): the home screen is now every book
  the active profile is granted, across all of its libraries, ordered by what was played last, with the
  300 ms debounced search and the sort chips LIB-002 asks for. Reported from a device: with one library,
  the old home was a single card standing between the user and their shelf.
- Browsing by library is still available, and is now a setting (SET-001, SET-002): a first settings screen
  with **Open on libraries**, stored in Proto DataStore behind a new `:data:settings` module and a
  `SettingsRepository`, so no screen names the settings store directly.
- **The library grant is now enforced on read as well as on write** (PRODUCT_SPEC 5.2): a grant that
  *shrinks* after a sync used to leave the revoked library's rows in the cache, where nothing enumerated
  them again. Every read path — libraries, one library, the shelf, and a single book — now filters by the
  grant stored on the profile.
- `LibraryDao` split into read and write halves, and the sync's write path extracted into
  `LibrarySnapshotWriter`. Both were prompted by the quality gate rather than by taste, and both make the
  boundary real: a screen cannot reach an `upsert` from the DAO it reads through.
- **Not demonstrated**: the app has now been installed and signed in on hardware against a real server,
  but none of Phase 1's three exit criteria has been performed there — two accounts switching, a
  library-restricted account, and offline browse are all still only unit-tested. Cover art (LIB-001,
  LIB-004) is deferred by the owner and remains unbuilt.

### Phase 0 — Repository foundation

**Build and quality gates** (PRODUCT_SPEC 16)

- Gradle Wrapper 8.14.3, Kotlin DSL, `gradle/libs.versions.toml` version catalog with every version
  pinned; repositories restricted and content-filtered.
- Convention plugins in `build-logic/` for the Android application, Android library, JVM library,
  Compose, Hilt, Room and the shared quality gate.
- ktlint 1.5.0 with `.editorconfig` (Kotlin official style, 120 columns, trailing commas, no wildcard
  imports, LF, final newline).
- detekt 1.23.8 with type resolution, no baseline, and rules encoding the `PRODUCT_SPEC 16.3` list:
  no `GlobalScope`, no raw dispatchers, no swallowed exceptions, no empty catch, exhaustive `when` on
  sealed types, no `System.currentTimeMillis()` in domain logic, no `println`.
- Android Lint as a blocking gate with warnings as errors and no baseline.
- Kotlin compiler warnings as errors in CI via `-Pshelfplayer.warningsAsErrors=true`.
- `./gradlew verifyDebug` — ktlint, detekt with type resolution, Android Lint, unit tests, Room schema
  verification and the debug assembly, for every module.
- Dependency locking and dependency verification configured, with bootstrap scripts
  (see ADR-0006).
- GitHub Actions: pull-request workflow (wrapper validation, secret scan, `verifyDebug`, Room schema
  diff, debug APK, dependency report) and main workflow (release lint, unsigned release build).

**Architecture** (PRODUCT_SPEC 9, 13, 14)

- Ten modules: `:app`, `:core:model`, `:core:common`, `:core:designsystem`, `:core:database`,
  `:core:datastore`, `:core:network`, `:core:testing`, `:data:library`, `:domain`. `feature:*` are
  packages inside `:app` (ADR-0002). Phase 1 added `:data:auth` and `:data:settings`.
- Typed `AppResult` / `AppError` with the full `PRODUCT_SPEC 14.1` taxonomy, `isRetryable` encoding
  the `14.3` retry policy, and `resultOf` as the single exception boundary that rethrows cancellation
  (ADR-0003).
- Injected `AppClock` exposing wall-clock **and** monotonic readings, and injected dispatchers with
  an `@ApplicationScope` replacing `GlobalScope`.
- Redacted structured logging where the field *type* decides what survives; `:app` owns the only
  `android.util.Log` call site (ADR-0004).
- Room as the UI source of truth: compound `(serverId, remoteId)` identity, soft delete, freshness
  columns, profile-scoped progress filtered in SQL, exported schemas, no destructive migration.
- Proto DataStore for settings, including the active profile selection.
- OkHttp/Retrofit foundation: timeouts per endpoint class, header-only authorization, a redacting
  HTTP logger, `ServerUrlNormalizer` and `NetworkErrorMapper`. No endpoints are defined.
- Fake Audiobookshelf gateway with a ShelfPlayer-owned fixture library (ADR-0005).

**Application**

- Compose Material 3 shell: home (library list) → library (search, sort) → book detail, all reading
  Room-backed state, with distinct loading, empty, error and content states.
- First launch seeds the demo library through the same repository the real gateway will use.

**Documentation**

- `README`, `CONTRIBUTING`, `SECURITY`, `PRIVACY`, `CHANGELOG`, `LICENSE` (undecided, ADR pending).
- `docs/architecture/` (overview, module boundaries, build), `docs/adr/0001`–`0006`,
  `docs/api-compatibility.md`, `docs/testing.md`, `docs/release.md`.

### Known follow-ups

- Bootstrap dependency verification checksums and lockfiles, then flip
  `org.gradle.dependency.verification` to `strict` (ADR-0006).
- Add `distributionSha256Sum` to `gradle-wrapper.properties`.
- Enable the Gradle configuration cache once the protobuf/KSP/AGP combination is validated against it.
- Replace the placeholder application ID `com.example.shelfplayer` before any release.
- Choose a licence (PRODUCT_SPEC 24.2).
