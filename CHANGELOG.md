# Changelog

Notable changes to ShelfPlayer. Requirement identifiers refer to `PRODUCT_SPEC.md`.

## Unreleased

### Phase 2 — Playback (closing)

Written retrospectively. Five device runs shaped this phase and each one changed the plan, so
`docs/phase-2-gaps.md` is the authoritative checklist — an audit against acceptance criteria rather than
against deliverables — and `docs/phase-2-closeout-plan.md` is what remains.

- **Bookmarks** (11.1): four routes, three of them writes; the read rides on `GET /api/me`, because that is
  where Audiobookshelf keeps them. Three facts no client should guess and this one does not, because a
  capture settled each: a bookmark has **no id** and is keyed by its position in whole **seconds**, they
  live on the **user** rather than the item, and `DELETE` answers `200 text/plain OK`. Writes are
  local-first and never rolled back on a network failure; `hasUnsyncedChanges` stops a refresh discarding
  one made offline and `isPendingDelete` stops it resurrecting one deleted offline. The custom session
  command is included, so a car or a headset can keep a spot. Database version 13.
  The sheet opens with a **New bookmark at …** button, which the first build of this did not have — it
  offered only a long press on the player's icon, and a device run found the feature unusable as a result.
  The button follows Audiobookshelf's own client, and disables itself when the current second is already
  bookmarked rather than disappearing.
- **The finished threshold comes from the server, and the app keeps none** (PLAY-004, ADR-0013): a book is
  finished when little enough of it remains, and **the library on the server decides how little**. Until now
  the threshold was a hard-coded thirty seconds inside `PlaybackService` and `LibraryDto` parsed the library's
  own `markAsFinishedTimeRemaining` away entirely. Now the library's value *is* the rule for its books, and
  thirty seconds applies only to a library whose settings have not been read yet. There is **no setting in the
  app**: nothing on the server would match it — the user object has no settings field — so a per-device number
  could only ever disagree with the web interface. The app also does not write the library's settings back:
  that object has twelve fields, this app models one, and nothing captured says whether a partial PATCH merges
  or replaces. Settings → Playback keeps a **Finished** section as a *reading* — every library, the number in
  force for its books, and where to change it. This widens the deviation from PLAY-004, which asks for a
  configurable value; ADR-0013 owns it.
  `markAsFinishedPercentComplete` is deliberately **not** read: a percentage of a long book is a long time,
  and 95% of a hundred-hour book leaves five hours to go. The decision moved from the media service into
  `DefaultPlaybackRepository`, which already resolved the profile and the book and is therefore the one place
  that can resolve the rule; `recordPosition` no longer takes an `isFinished` flag. Database version 14 adds
  the column and **version 15** removes the percentage column 14 had briefly carried — 14 shipped in build
  0.9.2 and editing it in place crashed that build's device at startup, so it is left exactly as it shipped.
  Nullable, because a library that has set no rule is not a library asking for zero seconds. No new capture was needed: `settings` has been nested in `GET /api/libraries` since
  the wave A capture.
- **A three-dot menu on the book screen** (LIB-004, PLAY-003, PLAY-004): download, play, and then the
  overflow, in the order a hand reaches them. Inside: **History** for this book, with the chapter each entry
  falls in; **Mark as finished** — which replaces the checkbox that used to sit in the middle of the reading
  surface, and whose label names the state it would put the book *into*; **Discard progress**, which asks
  first and whose confirmation says what it does *not* do, because "discard progress" could as easily mean
  deleting the download; **Go to web client**, which opens the item in the server's own web interface in a
  browser rather than a WebView, because a WebView would ask for a sign-in inside an app that already holds a
  token it must not hand over; and **More info**, the identifiers and file facts the screen has no room for.
  *Manage local files* and *Delete local item* are shown disabled with **(Phase 3)** in the label — a control
  that looks live and does nothing is worse than one that admits it. *Add to playlist* is absent rather than
  disabled: nothing in any planned phase builds playlists, so a greyed row would promise something that is
  not coming.
- **The car shows the phone's shelves** (PLAY-001, LIB-002): a device run found Android Auto saying "no books"
  about a full library, while a voice search found everything. The cause was not a filter — *Continue* was the
  car's only tab, and a library with nothing in progress has nothing to put in it, which is every account on its
  first day. The browse tree now reads the **same `ObserveHomeShelvesUseCase` the home screen reads**: Continue,
  Recently added, Listen again and Discover, empty ones omitted exactly as the phone omits them, plus Chapters
  and History which are about whatever is playing and so always offered. A library with nothing at all says so
  in one unplayable row rather than showing a blank screen, because a blank browse screen in a car is
  indistinguishable from a broken app. Media-button resume deliberately does **not** share that list: ROUTE-001
  is "resume what was playing", so a book with no stored position must never be offered to a headset press.
- **A book is one timeline window** (ADR-0016, PLAY-001/PLAY-003): Media3 reports the *current item's*
  position to every controller, so a playlist of files made the notification describe the file — "time left
  in this chapter" on a library with a file per chapter. A book is now one `MediaItem` whose extras carry
  its tracks, turned into a `ConcatenatingMediaSource2`. Mostly deletion: nothing converts positions any
  more. The 527-hour book a device run found was `add()` taking milliseconds while the first build passed
  microseconds; the test now reads the built timeline back.
- **Android Auto** (PLAY-001, 11.1): a browse tree with three tabs — Continue, Chapters, History — voice
  search over title, author and narrator, and the `automotive_app_desc` metadata Auto enumerates media apps
  by. Two device runs still failed to find the app in a car with everything in the APK verified correct, so
  Settings → About reports the five things that decide it, including what installed the build and whether a
  car has ever bound to the session — which is how the third run found the cause on the phone rather than in
  the build. **Confirmed working in a car on 2026-08-14.** What the car *shows* is not yet right: the
  Continue tab opens empty, diagnosed and scheduled as its own pull request.
- **Media-button resume** (ROUTE-001, an exit criterion): `onPlaybackResumption` returns the most recently
  played **unfinished** book at its stored position. Finished books are excluded on purpose. **Passed on
  hardware 2026-08-14**, together with the book switching it depended on.
- **The player's history pane** (PLAY-003): every event rather than only jumps — play, pause, seeks, chapter
  changes, sleep-timer set/extended/expired and the rewinds they apply — combined with the changes that
  arrived from the server, each row carrying a wall-clock time and the chapter it happened in. Play and
  pause are read from `playWhenReady`, not `isPlaying`, or a book on a slow connection writes one of each
  every few seconds.
- **Book switching** (PLAY-001): wave 5's Android Auto callbacks dropped every item they did not recognise,
  including the app's own, so no book could be started from the app at all. `MediaItems.isReadyToPlay`
  restores the pass-through Media3's own default performs.
- **Playback recovery** (PLAY-001): an errored ExoPlayer sits in `STATE_IDLE` and ignores `play()` and
  `seekTo()` alike. The service retries transient errors, the play button prepares whenever the player is
  idle, and exhausted retries surface a notice with a button.
- **Sleep timer** (PLAY-008): the seven lengths, end-of-chapter and custom, a fade that can be turned
  **off**, extension from the notification and from a shake, and a **rewind when the timer stops playback**
  — minutes-scale, off by default, applied after the pause.
- **Speed, skips, auto-rewind, buffer presets** (PLAY-006/PLAY-007/PLAY-009): 0.5×–3.0× with pitch
  preserved and a per-book override; skips configurable per direction and honoured by the notification's own
  buttons; auto-rewind with four bands, clamped to the chapter start, visible and undoable; five buffer
  presets with a memory estimate.
- **Progress persistence and the offline outbox** (PLAY-004/PLAY-005): journaled every five seconds, synced
  on a thirty-second cadence plus seven named triggers, UUIDv4 session ids with idempotent retry, seven-day
  retention, latest-trustworthy-timestamp conflict resolution and clock-skew detection in diagnostics.
- **Finished, explicitly** (PLAY-004): a checkbox in both directions. The server accepts the un-tick; what
  can overrule it is the library's own `markAsFinishedTimeRemaining`, which the app does not yet read.
- **An event log and an error log** (14.4): in memory, fed by a `LogSink` *after* redaction, so a media
  title cannot reach it. Under Settings → About, which is what turns "it stopped" into an error code.
- **Contract captures** (22.4/22.5): thirty-nine fixtures from a real Audiobookshelf 2.36.0, with a CI job
  that fails on drift. `docs/api-compatibility.md` records what each one settled — including two findings
  that came from reading the *server's* log rather than its responses.

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
