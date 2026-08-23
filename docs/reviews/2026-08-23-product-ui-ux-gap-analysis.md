# Product, code, and UI/UX gap analysis — 2026-08-23

## Scope and evidence rules

This is a review of the current working tree against `PRODUCT_SPEC.md`. It combines the specification-gap
request, a static code review, and a signed-in physical-device UI/UX review. It also carries forward findings from
`docs/reviews/2026-08-22-server-android-auto.md` where the cited implementation is still unchanged.

Status words have deliberately narrow meanings:

- **VERIFIED** — directly supported by source, a captured contract, or a named test artifact. It does not
  mean the whole epic is release-complete.
- **PARTIAL** — an implementation exists, but one or more specified behaviors, contracts, or appropriate
  test tiers are absent.
- **MISSING** — the required boundary or behavior is not present, or the present implementation contradicts
  the requirement.
- **NOT-EXERCISED** — the required state was not safely or legitimately available during this review. This
  is different from a failure.

The signed-in app was reviewed on a physical Samsung SM-S928B running Android 16. Thirty-six private local
captures cover every naturally reachable route-level page and the non-destructive sheets available to the
reviewer. They contain server/account identity, media titles, authors, and listening state, so they remain in
an ignored local artifact directory and are deliberately not embedded in this public document or proposed
for the pull request. The supplied product image and home-screen mockup remain design references, not test
evidence. Sign-in was performed interactively and intentionally not captured; a player was not manufactured
by starting an arbitrary book, and Android Auto requires a DHU or head-unit host rather than a phone
screenshot. Large-text, landscape/wide-window, TalkBack, player, Auto, destructive, and unavailable-role
states remain separate evidence gaps. `PRODUCT_SPEC.md:1795-1811` also requires a green final quality gate;
this document does not claim it.

## Executive result

The requested visual direction is present and was seen in the signed-in build: home shelves have cover-led
cards and clear play/resume buttons; series rows use cover stacks; author rows use cover fans and a real
server portrait when available; and genre rows use cover mosaics. The responsive home header preserved its
actions at this device width by dropping the decorative logo where necessary. The implementation behind
genre consolidation is permission-gated and sequential per book.

The first live pass found three browse defects that static screenshots or happy-path unit tests had not
settled: Series, Authors, and Genres each reported **0 books** above visibly populated cards; tapping the
visible Genre **Edit** action opened the genre's books because the clickable parent card intercepted the
nested action; and switching axes could retain the previous list's scroll position, initially presenting a
new axis part-way down. All three were corrected in the branch, regression-tested, mutation-proved, reinstalled
without clearing the signed-in session, and physically recaptured. Series then reported 468 represented books,
Authors 496, Genres 372, and the first Genre Edit target opened its confirmation without navigating or writing.

The build is not release-ready. Three P0 findings remain: an exported Media3 controller can select the
origin to which the active bearer may be sent; profile switching is not the ordered playback transaction in
section 6.5; and privileged management writes are not backed by captured adapter-level contracts. A further
network-policy defect lets automatic downloads inherit the manual-download cellular rule. These issues are
more important than additional visual polish because they affect credentials, cross-profile isolation, and
unexpected mobile-data use.

The connected physical-device result closes only one narrow test gap: the datastore Keystore tier recorded
27 tests, 0 failures, 0 errors, and 0 skipped on `SM-S928B - 16` in
`core/datastore/build/outputs/androidTest-results/connected/debug/TEST-SM-S928B - 16-_core_datastore-.xml:2-4`.
It verifies only the datastore/Keystore tier. The signed-in captures verify route reachability and rendered
phone UI by observation, not Compose automation, playback service/Binder behavior, Android Auto, or the
API/device matrix in `PRODUCT_SPEC.md:1558-1573`.

## VERIFIED in source or an explicit artifact

### Product identity and the static launcher contract — SET-003

- The visible name is exactly `BookWave` in
  `app/src/main/res/values/strings.xml:13`; the application label consumes that resource in
  `app/src/main/AndroidManifest.xml:55-67`.
- The release `applicationId` is `org.homebord.bookwave` in
  `build-logic/convention/src/main/kotlin/com/example/shelfplayer/buildlogic/AndroidApplicationConventionPlugin.kt:65-84`.
  The debug variant intentionally adds `.debug` at lines 90-94. The Kotlin/Gradle namespace remains
  `com.example.shelfplayer` by design (`app/build.gradle.kts:10` and the convention-plugin explanation at
  lines 65-74); namespace and install identity are different properties.
- The enabled launcher alias uses the BookWave asset on a fresh install
  (`app/src/main/AndroidManifest.xml:118-136`), while the picker remains available under Settings
  (`app/src/main/kotlin/com/example/shelfplayer/feature/settings/SettingsScreen.kt:296-304`).
- The BookWave normal and round adaptive-icon XML both supply background, foreground, and monochrome layers
  (`app/src/main/res/mipmap-anydpi/ic_launcher_bookwave.xml:1-7` and
  `app/src/main/res/mipmap-anydpi/ic_launcher_bookwave_round.xml:1-7`). Density-specific foreground and
  themed WebP resources exist from mdpi through xxxhdpi. The asset audit decoded all 45 launcher WebPs as
  valid VP8L images with alpha at the expected 108/162/216/324/432 px density sizes. A separate 512×512,
  32-bit RGBA, 111,043-byte Play listing asset is checked in as
  `docs/assets/bookwave-play-store-icon.png`; it is visually opaque and is not reused as an adaptive
  foreground. `LauncherIconsTest` covers every seven normal/round adaptive XML monochrome layer, alias
  mapping, and the packaged debug application ID/label. The resource/listing separation follows Android's
  [adaptive icon guidance](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive) and
  the [Google Play icon specification](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en).

APK badging on the installed debug artifact also reported `org.homebord.bookwave.debug` and the `BookWave`
label, while the Settings picker rendered BookWave as selected. Android Settings → App info showed exactly
`BookWave` and Samsung's circular-ish launcher mask rendered the adaptive wave/book icon cleanly without
visible clipping. This verifies packaged debug identity, one real launcher mask, and picker reachability.
Themed-icon mode, alias switching on the connected launcher, and the no-restart/no-playback-interruption
acceptance criteria were not exercised because doing so while playback was deliberately absent would not
prove the latter condition.

### Requested home and browse visual slice — LIB-002

- Book shelf cards show cached covers and expose a separately labelled `Play`/`Resume` action without
  replacing the card's details action
  (`app/src/main/kotlin/com/example/shelfplayer/feature/home/HomeShelvesUi.kt:105-139`). The overlay button is
  explicitly 48 dp and has a content description (`HomeShelvesUi.kt:197-217,262-266`). This matches
  `PRODUCT_SPEC.md:312-315`.
- Series rows have representative artwork and progress
  (`app/src/main/kotlin/com/example/shelfplayer/feature/browse/SeriesListUi.kt:32-94`). Author and genre cards
  use `CollectionArtwork` (`SeriesListUi.kt:96-151`); portrait, series-stack, author-fan, and genre-mosaic
  fallback strategies are separated in
  `app/src/main/kotlin/com/example/shelfplayer/feature/browse/CollectionArtwork.kt:32-167`.
- Author image requests are made only when synchronized metadata says a portrait exists. The URL builder
  otherwise returns no URL, leaving the cached-cover fallback in place
  (`app/src/main/kotlin/com/example/shelfplayer/feature/browse/AuthorUrls.kt:9-42`). The mapper reduces the
  server's private image path to the boolean needed by the UI
  (`core/network/src/main/kotlin/com/example/shelfplayer/core/network/api/LibraryMapper.kt:63-84`).
- Search is cached and covers title, subtitle, author, narrator, series, tags, genres, ISBN, and ASIN
  (`domain/src/main/kotlin/com/example/shelfplayer/domain/library/BookSearch.kt:15-48`), matching the fields in
  `PRODUCT_SPEC.md:303-310`.
- **Live result:** cover-led shelf cards, their overlay controls, series stacks, author fans, a server author
  portrait, and genre mosaics all rendered successfully on the signed-in device. This is observational UI
  evidence, not a replacement for the missing authenticated-image fixture or screenshot tests.

### Bulk genre-edit methodology — MGR-001 / MGR-008

The implementation uses the existing, permission-gated single-item metadata contract rather than inventing
a bulk endpoint:

- It selects all accessible cached matches for the explicit profile and validates update permission and
  online state before writing
  (`domain/src/main/kotlin/com/example/shelfplayer/domain/usecase/BulkEditGenresUseCase.kt:23-101`).
- It processes one book at a time, reloads each item, re-checks the active profile and any existing draft,
  and patches only `BookMetadataField.Genres`
  (`BulkEditGenresUseCase.kt:103-218`). Network/auth/server failures stop the sequence; a 403 also refreshes
  permissions (`BulkEditGenresUseCase.kt:235-255`).
- Replacement is case-insensitive, preserves unrelated genres, inserts one or more requested replacements
  in stable order, and de-duplicates labels (`BulkEditGenresUseCase.kt:308-370`).
- The outcome distinguishes updated, unchanged, draft-conflicted, locally stale, failed, and unprocessed
  items (`BulkEditGenresUseCase.kt:274-300`). This is materially safer than an unattended retry of ambiguous
  writes and follows the non-idempotent-write rule in `PRODUCT_SPEC.md:663-692,763-785`.

### Architecture and privacy checks

- Library UI reads Room-backed repository flows; `DefaultLibraryRepository` explicitly describes Room as
  the read source and a transactional snapshot write at
  `data/library/src/main/kotlin/com/example/shelfplayer/data/library/DefaultLibraryRepository.kt:54-55,202-305`.
  No Retrofit/API dependency was found in a Compose screen or ViewModel.
- Explicit per-profile library calls carry the selected profile's bearer and validate accessible library
  IDs in `core/network/src/main/kotlin/com/example/shelfplayer/core/network/api/AbsLibraryApi.kt:114-168`.
- No trust-all TLS or hostname-verifier bypass was found, tokens are not appended to URLs, and default HTTP
  logging is redacted (`core/network/src/main/kotlin/com/example/shelfplayer/core/network/http/Interceptors.kt:93-117`;
  see also `docs/reviews/2026-08-22-server-android-auto.md:93-94`).
- No `GlobalScope` use was found; typed `AppResult`/`AppError` and cancellation propagation are centralized in
  `core/model/src/main/kotlin/com/example/shelfplayer/core/model/AppResult.kt:14-99`.

## PARTIAL implementations and specification gaps

### Library synchronization and browsing — LIB-001 / LIB-002 / LIB-003 / LIB-004

The Room-first architecture, immediate cached search, distinct top-level states, axes, shelves, book details,
and representative art exist. Home's axis dispatch and genre permission gating are visible at
`app/src/main/kotlin/com/example/shelfplayer/feature/home/HomeScreen.kt:688-756`; empty/loading/error/offline
handling is at `HomeScreen.kt:553-685,761-829`.

Release acceptance is still partial:

- Successful author-portrait delivery has no captured fixture or end-to-end authenticated image assertion;
  the currently safe cover fallback must remain
  (`docs/reviews/2026-08-22-server-android-auto.md:127-131`).
- Some required response envelopes are permissive. A missing libraries body becomes an empty successful
  `LibrariesResponseDto`, and a missing search body becomes an empty successful search result
  (`core/network/src/main/kotlin/com/example/shelfplayer/core/network/api/LibraryDtos.kt:28,129` and
  `AbsLibraryApi.kt:56-62,177-204`). A missing required envelope should be
  `AppError.ApiCompatibility`, not a credible empty library.
- Collections/playlists remain conditional v1 scope and are intentionally absent from the home axes until a
  real endpoint is captured (`app/src/main/kotlin/com/example/shelfplayer/feature/home/HomeControls.kt:6-18`;
  `PRODUCT_SPEC.md:57-99`). This is a recorded conditional deferment, not permission to guess an endpoint.

### Genre editing — MGR-008

The vertical slice is present but not acceptance-complete:

- **Resolved after the first live pass:** Genre Edit is now a sibling of the card's primary browse target.
  The regression test injects a physical pointer tap and proves Edit never invokes Browse while the card still
  does. Deliberately routing both repaired paths back to the defect made the test fail; the corrected APK then
  opened the confirmation on the physical device without executing a genre write.

- The Genres axis can be narrowed to a default library, while confirmation deliberately counts and edits
  all accessible cached matches by querying with `libraryId = null`
  (`app/src/main/kotlin/com/example/shelfplayer/feature/home/HomeViewModel.kt:446-490`). That matches the
  product request. The confirmation now states explicitly that the operation spans every matching cached book
  across **all accessible libraries for the active profile**, proceeds one book at a time, and may complete
  partially (`app/src/main/res/values/strings.xml:64-94`).
- The safe per-item PATCH shape is source-derived but the exact genre mutation has not been captured against
  an approved server. Add one captured request/response and an adapter-level HTTP test before calling this
  release-ready (`docs/reviews/2026-08-22-server-android-auto.md:139-156`).
- No live editor-role run has yet confirmed server results, partial failure wording, profile-switch
  cancellation, or preservation of a pre-existing draft. These are live/server acceptance cases, not
  screenshot-only checks.

### Authentication, permission freshness, and synchronization — AUTH-004 / SYNC-001 / SYNC-002 / SYNC-003

Token storage, profile rows, permission checks, Room snapshots, realtime code, and background scheduling all
exist, but their failure policy is fragmented:

- `restoreSession` clears a persisted reauthentication requirement merely because an old stored token can be
  activated (`data/auth/src/main/kotlin/com/example/shelfplayer/data/auth/DefaultAuthRepository.kt:187-205`).
  Decryption is not proof the server accepts the credential.
- `renewSession` maps every refresh failure, including transient network/timeout/429/5xx failures, to
  permanent reauthentication (`DefaultAuthRepository.kt:220-244`). Only a definitive credential rejection
  should do that.
- A shared authenticated-call boundary does not consistently perform one renewal/replay on 401, permission
  data has no common five-minute freshness guard, and 403 invalidation is not centralized. The affected
  adapters are enumerated in `docs/reviews/2026-08-22-server-android-auto.md:60-91`.
- `LibrarySnapshotWriter` writes incoming progress during batch and item refreshes without preserving every
  unsynced local row (`data/library/src/main/kotlin/com/example/shelfplayer/data/library/LibrarySnapshotWriter.kt:149-177,249-282`).
  A separate account-refresh path documents “an unsynced local write always wins”
  (`DefaultLibraryRepository.kt:312-354`), so two refresh paths currently disagree.
- Cold-start/reconnect replay is not wired for the durable playback outbox, bookmark mutations lack an
  equivalent replay coordinator, and successful upload does not consistently clear the corresponding
  pending flag (`docs/reviews/2026-08-22-server-android-auto.md:72-80`).

### Android Auto and device routing — PLAY-001 / ROUTE-001 / ROUTE-002 / ROUTE-003

The app has a `MediaLibraryService`, browse/search callbacks, a recent/resumption path, and per-device policy
models. This is **PARTIAL**, not hardware-verified. Static gaps remain:

- Resumption can choose stale server progress over newer unsynced local progress.
- A legacy global car auto-play switch coexists with per-device policy and can race it.
- Cold media-button playback can race asynchronous token restoration.
- The output watcher exists only after the playback service exists.
- The browse tree is not invalidated with `notifyChildrenChanged` after library/profile/progress changes.
- Artwork and expected custom actions are incomplete, and now-playing metadata does not identify the active
  chapter.
- The current car browse root does not expose the phone's Downloads, Series, Authors, or Genres destinations.
  Continue/Chapters/History are useful playback-oriented views, but they do not yet provide the broader
  browsable-library parity implied by LIB-002's axes and the Android Auto deliverable.

The source-level evidence and required DHU cases are recorded at
`docs/reviews/2026-08-22-server-android-auto.md:96-118`. There is still no `playback/src/androidTest` or
`app/src/androidTest` service/UI tier; the only connected source set is under `core/datastore/src/androidTest`.

### Downloads and smart downloads — DL-004 / DL-005 / SET-002

Manual downloads, a persistent queue, pause/resume/cancel, pinning, storage selection, basic smart-next-book
selection, and cleanup exist. The automatic-download policy is nevertheless unsafe and several v1 settings
are incomplete:

- `SmartDownloadUseCase` marks its call as automatic
  (`domain/src/main/kotlin/com/example/shelfplayer/domain/usecase/SmartDownloadUseCase.kt:88-92`), but
  `DownloadBookUseCase` passes no traffic category to the scheduler
  (`domain/src/main/kotlin/com/example/shelfplayer/domain/usecase/DownloadBookUseCase.kt:43-50,79-94`).
  `WorkManagerDownloadScheduler` therefore always evaluates
  `TrafficCategory.ManualDownload`
  (`app/src/main/kotlin/com/example/shelfplayer/download/WorkManagerDownloadScheduler.kt:54-84`). If manual
  cellular downloads are enabled, an automatic job can be scheduled on a metered connection even when the
  **smart-download cellular** toggle is disabled.
- The UI and Proto both expose a smart-download cellular toggle
  (`app/src/main/kotlin/com/example/shelfplayer/feature/settings/PlaybackSettingsTab.kt:304-345` and
  `core/datastore/src/main/proto/com/example/shelfplayer/core/datastore/app_settings.proto:148-175`), while
  the authoritative spec says smart downloads are unmetered only with no cellular option in v1
  (`PRODUCT_SPEC.md:541-546,556-587`). The implementation, comments, and spec must be reconciled explicitly;
  until then, enforce the safer unmetered rule and remove the dead toggle from v1.
- DL-005's future-book count, battery-not-low/storage-not-low/optional charging constraints, minimum
  free-space reserve, ambiguous-series choice, seven-day cancellation suppression, and visible waiting
  reason are not represented by the current basic use case or settings schema
  (`SmartDownloadUseCase.kt:19-97`; compare `PRODUCT_SPEC.md:556-592`).

### Settings — SET-001 / SET-002

Proto DataStore is versioned and stores a meaningful subset: theme/dynamic color, privacy flags, per-profile
library/sort state, sleep settings, speed/skips/rewind, buffer preset, network toggles, smart-download and
retention flags, storage volume, devices, focus/startup behavior, and language
(`core/datastore/src/main/proto/com/example/shelfplayer/core/datastore/app_settings.proto:15-225`).

The settings epic is still **PARTIAL**:

- `ProfileSettings` contains default library and sort orders only
  (`app_settings.proto:228-247`). There is no general per-book/per-device/per-profile/global inheritance
  display or reset-to-default mechanism required by `PRODUCT_SPEC.md:824-838`.
- The schema/UI has no complete representation for advanced buffer values, retry behavior, prefer-local and
  fallback confirmation; future-book count, charging/battery/storage constraints, free-space reserve,
  simultaneous-download count, and max retries; reduced motion, high contrast, and cover-grid density; or
  crash-reporting opt-in, diagnostic-bundle export, and cache clearing
  (compare the complete inventory at `PRODUCT_SPEC.md:840-899`).
- Settings uses four fixed text tabs — Server, Playback, Sleep, About — and appends Devices to Playback
  (`app/src/main/kotlin/com/example/shelfplayer/feature/settings/SettingsScreen.kt:150-247`). About combines
  appearance, launcher identity, diagnostics, playback metrics, testing, storage, notification, car, and
  sync readings (`SettingsScreen.kt:283-353`). The functionality is reachable but the information
  architecture will become increasingly difficult to scan and must be checked at large font sizes.

The signed-in device confirms that cost rather than merely predicting it: Server, Playback, and About are
very long pages; the tab row and toolbar disappear from view deep in a page; Playback mixes ordinary choices
with substantial explanatory copy; and About combines launcher selection, diagnostics, storage,
notification, car, and sync information. Event Log and Debug Console remain reachable and privacy-conscious,
but a 500-entry raw event sheet needs search/filtering. The fresh install reported notifications blocked in
Debug Console without a corresponding onboarding step, so an important playback permission can remain
undiscovered until troubleshooting.

## MISSING / release-blocking code boundaries

### P0 — exported Media3 input can choose the bearer-token destination

Requirement: the exported media service must validate controller identities and commands
(`PRODUCT_SPEC.md:1346-1358`).

Evidence chain:

1. `PlaybackService.onGetSession` returns the session for every controller
   (`playback/src/main/kotlin/com/example/shelfplayer/playback/PlaybackService.kt:261`).
2. `onAddMediaItems` resolves/passes controller-provided items, and `onSetMediaItems` accepts a list unchanged
   when every item is “ready” (`PlaybackService.kt:854-900`).
3. “Ready” means only that a `MediaItem` has any `localConfiguration` or track extras; a caller-provided URI
   satisfies the first branch (`playback/src/main/kotlin/com/example/shelfplayer/playback/MediaItems.kt:125-138`).
4. The Media3 data source uses the authenticated streaming client
   (`playback/src/main/kotlin/com/example/shelfplayer/playback/di/PlaybackModule.kt:63-71`), which inherits
   `AuthorizationInterceptor` (`core/network/src/main/kotlin/com/example/shelfplayer/core/network/di/NetworkModule.kt:114-137`).
   That interceptor adds the active bearer to any request lacking one and does not bind it to the profile's
   scheme/host/port (`core/network/src/main/kotlin/com/example/shelfplayer/core/network/http/Interceptors.kt:37-57`).

Impact: another app able to connect as a Media3 controller can provide a stream URL it controls and cause
BookWave's active bearer to be attached to that origin. The service must remain exported for platform/car
integration, so removing export is not the fix.

Required fix: allow only BookWave's own package **and UID** to submit pre-resolved URI/track items; external
controllers may submit only app-issued opaque browse IDs, which BookWave resolves after checking profile and
item access. Independently bind every ambient bearer to the normalized origin that issued it. Prove both
boundaries with a second-UID instrumentation client and two `MockWebServer` origins, then rerun DHU browse and
playback.

### P0 — profile switching is not a playback-context transaction

Section 6.5 requires local/remote progress flush, pause, atomic context change, and restoration of the new
profile's player state paused (`PRODUCT_SPEC.md:225-234`). `SwitchProfileUseCase` currently checks the lock,
sets the active profile, restores credentials, refreshes permissions, and schedules sync; it has no playback
pause/flush/close/restore collaborator
(`domain/src/main/kotlin/com/example/shelfplayer/domain/usecase/SwitchProfileUseCase.kt:42-69`).

At the same time, playback writes resolve the mutable active profile at write time
(`data/library/src/main/kotlin/com/example/shelfplayer/data/library/DefaultPlaybackRepository.kt:83-101,176-191`).
The old book can therefore journal progress/bookmarks into the new profile, and an old stream can inherit the
new profile's bearer.

Required fix: make switching one ordered application transaction: pause A; snapshot and flush A with an
explicit `ProfileId`; close/clear A's Media3 queue and stream context; activate B and its credential; restore
B's last state paused. Add an ordering/rollback unit test and a device test switching between two server
origins while A has buffered audio.

### P0 — privileged management HTTP behavior is not contract-proven

Cover upload, metadata embedding, and user activation are production writes without captured request and
response contracts, and there is no adapter-level `AbsManagementContractTest`
(`core/network/src/main/kotlin/com/example/shelfplayer/core/network/api/AbsManagementApi.kt:136-174,272-320`;
`docs/reviews/2026-08-22-server-android-auto.md:45-58`). Shape-only and payload-only tests do not prove the
Retrofit path, method, headers, multipart/query names, response decoding, or error mapping. This conflicts
with the contract-test release blocker in `PRODUCT_SPEC.md:1508-1517,1544-1556`.

Required fix: capture the three operations against an approved Audiobookshelf version and add load-bearing
`MockWebServer` adapter tests before any of those actions is called release-ready. Do not infer success for an
uncaptured source-file mutation or add an automatic retry to a non-idempotent write.

### P1 — current-user disable guard is dead

The server-user screen tries to prevent disabling the signed-in account, but `signedInAs` defaults to an
empty string and is never populated; the ViewModel receives only `ServerUserRepository`
(`app/src/main/kotlin/com/example/shelfplayer/feature/users/ServerUsersViewModel.kt:37-45,128-174`). The UI
guard therefore never identifies the current user. USER-003 needs a domain/repository policy using an
explicit current profile identity, not a UI-only string comparison (`PRODUCT_SPEC.md:811-818`).

### P1 — performance and appropriate test tiers are missing

There is no benchmark/baseline-profile module and none of the startup, cached-library, playback-start,
2,000-item scroll, process-death, two-hour playback, or ANR targets in
`PRODUCT_SPEC.md:1575-1585` has measured evidence. Compose UI, playback service/Binder callbacks, Android
Auto, installed migrations, release R8 behavior, and most API levels have no instrumented tier
(`docs/reviews/2026-08-22-server-android-auto.md:120-126`). The 27/27 connected datastore result is valid but
does not reduce these broader gaps.

## Signed-in phone UI/UX review

This section combines Compose inspection with the 2026-08-23 physical-device pass. Observations explicitly
labelled live were visible on the device; recommendations still need the additional form-factor and
accessibility evidence named below.

### What is structurally strong

- The primary home action is now where users expect it: on the cover, with a 48 dp target and a distinct
  details-card action (`HomeShelvesUi.kt:197-217,262-266`). Its accessibility label changes between play and
  resume (`HomeShelvesUi.kt:120-135`).
- Card dimensions reserve title, subtitle, and progress lanes, which should reduce shelf jitter
  (`HomeShelvesUi.kt:179-269`). Series, author, and genre art now communicate content before text alone.
- The home axes are stable bottom navigation and disappear when no profile exists
  (`app/src/main/kotlin/com/example/shelfplayer/feature/home/HomeScreen.kt:258-265`). Offline and
  reauthentication reasons disable genre editing rather than hiding it (`HomeScreen.kt:734-753`).
- Bulk edit exposes a confirmation, running state, and a detailed partial-result summary rather than claiming
  all-or-nothing success (`HomeViewModel.kt:503-527` and
  `app/src/main/res/values/strings.xml:68-94`).
- **Live:** the cover/play treatment is the strongest part of the current phone UI. Covers dominate without
  obscuring titles, the play buttons are visually distinct, series/author/genre artwork gives each axis a
  recognizable texture, and an authenticated author portrait replaced its fallback without leaving an empty
  card. The home header remained usable at the reviewed width by hiding its decorative logo on Books.

### Recommendations, in priority order

1. **Reduce home-app-bar competition.** The reviewed large phone fits the actions, but a compact phone can
   expose search, Books-view toggle, refresh,
   profiles, and settings beside the title — five 48 dp actions
   (`HomeScreen.kt:169-255`). Keep Search/Profile as stable high-frequency actions; move the shelves/list
   toggle into the Books content header; keep an accessible explicit refresh through pull-to-refresh plus an
   overflow or status action; and validate the result at 1.0x and 2.0x font scale. The current conditional
   hiding of only the decorative brand mark is good but cannot solve action crowding.

2. **Make shelf headings actionable.** “Continue listening”, “Recently added”, and similar headings are
   plain text with no “See all”/focus callback (`HomeShelvesUi.kt:105-175,253-259`). Add a labelled trailing
   action that opens the corresponding full filtered list. The bottom Books list is not an equivalent
   one-step expansion because it loses shelf context.

3. **Expose progress as text as well as a bar.** The shelf card has only a visual linear indicator, with no
   percentage or time remaining (`HomeShelvesUi.kt:219-248`). Add a concise value such as “2 h 14 m left” or
   “76%” where progress exists, and expose the same value in semantics. Also distinguish the currently
   playing card without relying on color alone.

4. **Show the normalized genre preview before committing.** Scope and cost are now explicit: all accessible
   libraries, sequential writes, and possible partial completion. The remaining improvement is a normalized
   preview such as `Science Fiction & Fantasy → Science Fiction + Fantasy` before confirmation. Keep the
   existing count and detailed summary; do not add blind retry.

5. **Run the remaining TalkBack check on the repaired genre card.** The nested gesture defect is fixed with
   separate browse/edit targets, a 48 dp trailing action, an explicit “Edit genre X” label, regression tests,
   mutation proof, and physical recapture. TalkBack focus order still needs a live pass.

6. **Reorganize Settings before adding the missing inventory.** Four fixed text tabs cannot absorb the
   remaining Download, Device, Appearance/accessibility, and Privacy/diagnostics settings cleanly
   (`SettingsScreen.kt:195-240,283-353`). Prefer a grouped settings landing page or scrollable category model:
   Server & profile; Playback; Downloads; Devices; Appearance & accessibility; Privacy & diagnostics; About.
   Keep advanced buffers and testing readings behind progressive disclosure. Do not make “About” the default
   destination for operational troubleshooting.

7. **Move playback feedback to the application shell.** `MainActivity` owns the playback message, but passes
   it into the nav graph where only `HomeRoute` renders it
   (`app/src/main/kotlin/com/example/shelfplayer/MainActivity.kt:131-160` and
   `app/src/main/kotlin/com/example/shelfplayer/navigation/ShelfPlayerNavHost.kt:66-80`). A failure triggered
   in Full Player, Settings, Book Details, or Downloads can be delayed or covered until Home is visible.
   Host the snackbar at the shell/player layer or render the current failure directly in Full Player.

8. **Validate portrait behavior with real data without weakening fallback.** The portrait-gating approach is
   correct, but review one real author portrait, a 404, offline mode, and a changed image timestamp. Covers
   should remain the placeholder/failure path, never a flash of empty space.

9. **Treat Android Auto as a separate UX surface.** Phone screenshots cannot validate driver-distraction
   constraints, browse depth, voice search, host-cropped artwork, progress refresh, or custom actions. Fix the
   controller boundary first, then review discovery, recent/continue, search/voice, chapters, artwork,
   completion progress, profile invalidation, and supported buttons in DHU and one real head unit. Use the
   official [media overview](https://developer.android.com/training/cars/media),
   [distraction safeguards](https://developer.android.com/training/cars/media/distraction-safeguards), and
   [car testing guide](https://developer.android.com/training/cars/testing) as the host-level acceptance
   boundary.

10. **Keep summary-count and axis-restoration regressions load-bearing.** These live defects are fixed: counts
    derive unique books from the active content shape, shelf counts retain the uncapped source total, and list
    composition is keyed by axis/view/focus. The count and edit tests were mutation-proved; the scroll reset is
    source/test reviewed but still merits a dedicated device automation assertion.

11. **Strengthen detail and management hierarchy.** Book detail repeats the title in the app bar and body,
    and its large overflow gives `Remove from Audiobookshelf database` the same visual treatment as benign
    actions. Separate destructive actions and give them warning hierarchy. The metadata editor is a very
    long flat form, offers cover buttons without a current-cover preview, and presents a trailing blank
    series name/position pair as though it were duplicated input; add grouped sections, a cover preview, and
    an explicit “Add another series” affordance. The series-detail page needs a useful cover/summary/progress
    header and per-book play/resume actions; its current list leaves most of the screen empty and wraps long
    titles awkwardly. The Downloads empty state is clear, but naturally sparse.

Every new independent action should keep Android's documented
[48 dp minimum target and meaningful label](https://developer.android.com/guide/topics/ui/accessibility/views/apps-views).
For wide layouts, prefer the platform's
[adaptive navigation suite](https://developer.android.com/develop/adaptive-apps/guides/build-adaptive-navigation)
and [canonical list-detail guidance](https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts)
instead of stretching the phone hierarchy.

## Live-device capture coverage and unexercised states

The route inventory comes from the navigation graph at
`app/src/main/kotlin/com/example/shelfplayer/navigation/ShelfPlayerNavHost.kt:39-146` and player overlays at
`app/src/main/kotlin/com/example/shelfplayer/MainActivity.kt:99-205`. The following records what was actually
reviewed rather than treating every theoretically constructible state as a screenshot requirement.

### Signed-in screenshot inventory

- **VERIFIED:** Home Books shelves and list, Series, Authors, Genres, author- and genre-focused Books, and
  search with the IME open.
- **VERIFIED:** Profiles; Settings Server, Playback, Sleep, and About at representative scroll positions;
  launcher picker; Event Log; Debug Console; server-account list; and the blank create-account form.
- **VERIFIED:** Downloads empty state; Book detail; non-destructive overflow, History, and More Info sheets;
  metadata editor at top and lower sections; and Series detail.
- **VERIFIED:** Android App info for the installed debug package: exact BookWave name, clean adaptive-icon
  mask, and notifications reported Blocked. The capture remains private with the rest.
- **NOT-EXERCISED:** Sign-in screenshot. The user signed in interactively after a clean uninstall, and no
  credential screen was captured.
- **NOT-EXERCISED:** Mini/Full Player and its Chapters, Speed, Sleep, History, and Bookmarks sheets. Starting
  an arbitrary book would create real server progress; review waits for an explicitly approved safe title.
- **VERIFIED AFTER REINSTALL:** grouped summary counts rendered as 468 Series books, 496 Author books, and
  372 Genre books; the first Genre Edit target opened its confirmation. The dialog was dismissed and no genre
  write was performed merely to create evidence.
- **NOT-EXERCISED:** offline/error/reauthentication, locked-profile, active/failed/completed download,
  destructive management, user-disable, cover upload/match, launcher switching, landscape, large text,
  TalkBack, and wide-window variants. Legitimate absent state was not manufactured, and no server or local
  data was mutated solely for screenshots.
- **SEPARATE HOST REQUIRED:** Android Auto browse and now-playing surfaces. A phone screenshot cannot render
  or validate a DHU/head-unit host.

For each surface, review TalkBack focus order and labels, 48 dp targets, truncation, contrast, keyboard/IME
behavior, loading/error/empty/offline state distinctions, back behavior, bottom-inset handling, and whether
the Mini Player obscures content. Include a compact phone width, landscape, and at least one wide/tablet or
resizable-window width as required by `PRODUCT_SPEC.md:129,1541-1542,1558-1573`.

Screenshots from a real account can disclose media titles, authors, server/account identity, and listening
progress. Keep raw captures in an ignored local artifact directory. Do not commit them to a public pull
request unless the user explicitly approves the disclosed content; prefer the demo fixture or redact private
content before attaching evidence. Never capture a password or bearer token.

### Hardware tests still pending after the datastore tier and phone route review

- Playback service discovery, controller authorization, Binder callbacks, process/service recreation, noisy
  route, transient/permanent audio focus, Bluetooth, wired output, and OEM cold media-button behavior.
- Metered-to-unmetered and unmetered-to-metered transitions during streaming/manual/automatic download.
- Profile switching between two server origins while playback is buffered and progress is unsynced.
- Android Auto DHU plus one physical head unit where practical.
- API 26/31/34/36, release APK/R8, installed upgrade/migration, low storage, process death, and the performance
  targets in `PRODUCT_SPEC.md:1558-1585`.

## Documentation consistency gaps

This change reconciles the current decision/evidence statements in `PRODUCT_SPEC.md`, ADR-0019,
`CHANGELOG.md`, `docs/gaps.md`, and `docs/handover.md`. The distinction to preserve in later edits is:

- Profile-lock recovery/relock code is closed; biometric/curtain human review is still open.
- Management UI reachability is not proof of an approved privileged HTTP contract.
- The datastore Keystore tier is green on one API-36 device; it does not close UI, playback, Auto,
  migration, release, or device-matrix testing.
- Private real-account captures prove what rendered, but are not distributable PR evidence and do not
  satisfy large-text/TalkBack/wide-layout coverage.

## Release recommendation and next evidence

1. Fix and regression-test the two P0 credential/profile boundaries before further visual polish.
2. Capture privileged server contracts and add adapter-level tests, including one exact genre mutation.
3. Make the automatic-download traffic category explicit and reconcile the v1 cellular policy with the spec.
4. Correct unsynced-progress merge/replay semantics and the auth/permission failure policy.
5. Retain the private route inventory as local review evidence; use a scrubbed/demo account for any PR
   images. With an explicitly approved safe book, review Mini/Full Player. Separately run TalkBack, 2.0x
   font, landscape/wide, and DHU checks.
6. Update `docs/handover.md`, `docs/gaps.md`, risks/compatibility notes, and UI evidence. Then run formatter and
   `./gradlew verifyDebug -Pshelfplayer.warningsAsErrors=true --rerun-tasks`; do not call the change complete
   unless that final branch state is green.
