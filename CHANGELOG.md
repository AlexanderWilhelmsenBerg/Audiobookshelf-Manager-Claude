# Changelog

Notable changes to ShelfPlayer. Requirement identifiers refer to `PRODUCT_SPEC.md`.

## Unreleased

### Phase 1 — Authentication and cached browsing (in progress)

**Not complete.** `docs/phase-1-remaining.md` is the authoritative list of what is left — an audit
against acceptance criteria rather than deliverables, which is how LIB-001's websocket requirement
came to be missed. `docs/handover.md` has the deliverable-by-deliverable history.

- **Item-level permission enforcement** (5.2, exit criterion 3): `profile_visible_books` records which
  items each profile's own sync was served, and every read joins through it. Audiobookshelf restricts
  twice — by library, and by tag inside a library — and reports the second only by shortening the item
  list it serves, so there is no predicate to evaluate and nothing but a per-profile record will do.
  A device run had a restricted account reading all 490 of another account's cached books, online and
  offline alike, because both were granted the shared library. Absence now means hidden; a profile that
  has not synced sees nothing rather than everything. Database version 5.
- **Permission refresh** (5.2): `AuthApi.currentAccount` over the already-captured `POST /api/authorize`,
  called on a profile switch and after any `403`. The stored grant and role were previously written once
  at sign-in and never revisited, so a library revoked on the server stayed on the shelf until sign-out.
  Returns an `AccountState` rather than an `AuthSession` on purpose: that response carries the legacy
  `user.token` and no refresh token, and adopting it would replace a renewable credential with one that
  cannot be renewed.
- **A sync on every launch, per profile** (LIB-001, 6.5): the home screen no longer skips its automatic
  sync for a profile that succeeded on some earlier launch. Two device runs reported switching accounts
  and getting no sync at all.
- **Contract capture for the progress and websocket shapes** (22.5): `scripts/capture-contracts.sh`
  records a listening position before capturing, so `user.mediaProgress` is no longer an empty array
  whose element shape nobody has seen, and it records the socket.io handshake, namespace connect,
  authentication frame and a post-progress poll over the polling transport. Frames are stored parsed so
  a token inside one is redacted and the shape is not. Six new capture targets; the fixtures land when
  the workflow next runs against a real server.

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
- Browsing by library is still available and lives in **Settings** (SET-002) — as a list of libraries to
  open, not as a toggle that turns the home screen into something else. The toggle shipped for one build
  and was wrong in the way modal settings usually are: it cost a trip to Settings and back to find out what
  it did. Its proto field is reserved rather than removed, because a device that wrote it still has the
  bytes.
- **The `adb` checks are in the app** (SET-002, Privacy/diagnostics): **Settings → Storage on this device**
  reports servers, profiles, saved sign-ins, libraries and books *stored* against *visible to this profile*,
  soft-deleted rows and progress records. The pair is what makes "unauthorized libraries never appear"
  checkable without a cable — the requirement is that unauthorized rows were never **written**, and a screen
  that hides a row looks identical to one that never had it. Counts only, never names: listing libraries a
  profile may not see would be a strange way to prove they are hidden.
- **Known servers on the sign-in screen** (AUTH-001): the address stage lists the servers this device has
  used, with the version detected and whether the connection was encrypted. Picking one fills the field and
  **re-probes** — what is shown before a password is typed describes the server now, not what it looked like
  last time.
- **The library grant is now enforced on read as well as on write** (PRODUCT_SPEC 5.2): a grant that
  *shrinks* after a sync used to leave the revoked library's rows in the cache, where nothing enumerated
  them again. Every read path — libraries, one library, the shelf, and a single book — now filters by the
  grant stored on the profile.
- `LibraryDao` split into read and write halves, and the sync's write path extracted into
  `LibrarySnapshotWriter`. Both were prompted by the quality gate rather than by taste, and both make the
  boundary real: a screen cannot reach an `upsert` from the DAO it reads through.
- **One failed item no longer discards a whole library sync** (LIB-001): `AbsLibraryApi` fetches each item
  expanded, so a 490-book library is 490 requests, and it used to abandon everything it had collected on the
  first failure. It now keeps what it fetched, reports how much it could not, and records the sync as
  `PartiallySucceeded`. Crucially an *unreachable* item is not treated as a *removed* one — only a complete
  fetch is allowed to drive soft deletion, so a timeout can no longer make books disappear.
- **`INSERT OR REPLACE` was cascading deletes across the schema.** SQLite implements a `REPLACE` conflict as
  delete-then-insert, and the delete runs `ON DELETE CASCADE`: re-writing a library row deleted its books,
  re-writing a book row deleted its tracks, chapters, links and the profile's progress, and re-writing a
  server row deleted its profiles. Every parent table now uses `@Upsert`. Found by a test written for
  something else.
- **The initial sync no longer runs in a scope that gets cancelled.** It was awaited inside `SignInUseCase`,
  in the sign-in screen's `viewModelScope`, which a successful sign-in pops — killing the sync part-way and
  leaving `sync_state` stuck on `Syncing`. Home owns it now, and also adopts a sync recorded as running that
  nothing is running.
- A rejected sign-in says the credentials were refused, instead of reusing AUTH-004's "this profile needs to
  sign in again" — which is what a `401` mapped to for every caller.
- The profile switcher (AUTH-002): the active account is a filled badge on a highlighted card rather than a
  word between two buttons, the whole card switches profile, each card shows its **server address** — the
  open item from the previous PR — and a signed-out profile offers **Sign in** rather than *Sign out*,
  carrying its address and username to the sign-in screen so only the password is retyped.
- **A restricted account was deleting the unrestricted account's books** (PRODUCT_SPEC 5.2). Audiobookshelf
  restricts by library *and* by tag within a library, so an account with `accessAllTags = false` is served a
  filtered item list for a library it can otherwise see. Reconciliation then marked everything absent from
  that list deleted: a device run showed 302 of 490 books removed after a restricted account synced.
  `LibraryAccess` now carries `hasAllTagAccess` — captured in the contract all along, never read — and only
  an account that sees every library **and** every item may drive deletions. Database version 4, restrictive
  default for existing rows.
- **Libraries deleted on the server are removed** (PRODUCT_SPEC 13.2). Nothing enumerated libraries, so a
  deleted one survived every refresh as a stale entry. Their books go with them, under the same authority
  rule.
- **Every profile gets its initial sync**, not just the first: the "already attempted" flag was one boolean
  for the whole screen rather than a set of profile ids, so switching accounts silently skipped the sync.
- **Pull-to-refresh** (LIB-001 asks for it by name). The toolbar button stays — TalkBack cannot pull.
- Search and sort run off the main thread. Filtering 490 books per keystroke ran on `Dispatchers.Main`,
  which a device run felt as a second of lag; LIB-002's 300 ms debounce is now the only delay.
- Reauthentication lands on the password field: the probe runs on arrival instead of asking the user to tap
  *Continue* on an address the app supplied itself. It is run, not skipped — the credentials stage still
  shows the version and the encryption line before a password is typed.
- "Saved sign-ins" counts accounts, not files. It read 6 for 3 accounts, because each stores an access and a
  refresh token.
- Book rows show position, time remaining and total length rather than a bare percentage (LIB-004).
- `docs/phase-1-acceptance.md`: the manual acceptance plan that closes Phase 1 — 53 cases covering
  sign-in, the shelf, settings, two-account switching, a library-restricted account, offline browse,
  session expiry and accessibility, with the database checks the UI cannot substitute for and an explicit
  list of gaps that are expected to fail.
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
