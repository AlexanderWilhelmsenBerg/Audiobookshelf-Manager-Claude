# Handover

Status rechecked against `main` and a signed-in physical device on 2026-08-23, not from recollection. Every
"done" below is backed by a file that exists, an explicit artifact, or a named observation; those evidence
types are not treated as interchangeable.

Phases 0 to 5 are complete and Phase 6 remains open. AUTH-005, the profile passcode lock described by the
older phase narrative below, closed on 2026-08-21 with ADR-0023; its remaining device-only evidence and
accepted limitations are recorded under "What has never been verified" rather than as unfinished code.

Three documents carry what this one deliberately does not: `docs/gaps.md` is every requirement this build
does not meet, `docs/risks.md` is everything it does that could go wrong, and `docs/release.md` is what
still blocks a public build. The focused 2026-08-22 server/Android Auto audit is
`docs/reviews/2026-08-22-server-android-auto.md`; the implementation/spec/UI gap analysis and signed-in
phone review are `docs/reviews/2026-08-23-product-ui-ux-gap-analysis.md`.

## Current work in flight — presentation, genre repair, identity, and review

The working tree adds the user-requested cover-led browse treatment, guided genre repair, and BookWave
launcher mark. It is not a text-only mockup:

- Home shelf cards use covers and a separate labelled play/resume action. Series uses cover stacks; Authors
  uses cover fans or a synchronized server portrait; Genres uses cover mosaics.
- A metadata-update-capable profile can consolidate one source genre into one or more replacement labels
  across every accessible matching book. Each item is reloaded and patched sequentially through the
  existing metadata repository, only the genre field is sent, existing drafts are preserved, systemic
  failures stop the remaining sequence, and partial completion is reported. One approved server mutation
  and its exact adapter contract are still missing, so this is not yet release-proven management I/O.
- The supplied wave/open-book mark is the fresh-install default and remains one of seven Settings choices.
  The release identity is `org.homebord.bookwave`; debug installs as `org.homebord.bookwave.debug`; Kotlin
  packages and Gradle namespaces intentionally remain `com.example.shelfplayer` (ADR-0024). Android App
  info on the reviewed Samsung showed exactly **BookWave** and rendered the adaptive icon under its
  circular-ish mask without visible clipping. The decoded density assets are valid at their expected sizes,
  and a separate 512×512, sub-1-MB Play listing PNG is checked in. Themed mode and live alias switching
  remain unexercised.
- Author-directory synchronization records only whether a portrait exists and its server revision. It
  never persists the server's private filesystem path. A real portrait rendered on the signed-in device,
  but there is still no scrubbed successful author-image response fixture and restricted-tag accounts do
  not request the directory until that privacy boundary is captured.
- Session-token and profile-passcode staged writes now commit with explicit replace-existing semantics.
  The former `File.renameTo` behavior worked on Android/Linux but refused to overwrite an existing record
  on the Windows JVM, which made repeated sign-in/renewal and passcode replacement fail locally. The new
  shared helper attempts an atomic replace first and falls back only when the filesystem provider explicitly
  reports that atomic moves are unsupported. JVM overwrite regressions cover both stores; reverting the
  helper to `renameTo` made both tests fail before the fix was restored.

### First physical execution of the datastore instrumented tier — passed

`./gradlew :core:datastore:connectedDebugAndroidTest` ran on a Samsung SM-S928B running Android 16 and
reported **27 tests, 0 failures, 0 errors, 0 skipped**. That is the first hardware execution of this tier.
It verifies `KeystoreLockCipherTest` and `ProfilePasscodeStoreTest`: the AndroidKeyStore wrap, staged record
write, encrypted rate limit, and store behavior. It does **not** verify the lock curtain/biometric prompt,
Compose routes, playback service/Binder behavior, Android Auto, installed migrations, release/R8, or the
API/device matrix. Connected tests still do not run in CI.

### Signed-in route review — complete within the safe phone scope

After a clean uninstall the user signed in interactively. Thirty-six private local captures cover every
naturally reachable route-level page and non-destructive sheet available in that session:

- Home Books shelves/list, Series, Authors, Genres, focused author/genre books, and search with the keyboard;
- Profiles; Downloads empty state; Book detail, overflow, History, More Info, and metadata editor; Series
  detail;
- Settings Server, Playback, Sleep, and About across their scroll ranges, launcher picker, Event Log, Debug
  Console, server-account list, and the blank create-account form.

The raw images remain in an ignored local artifact directory. They disclose real server/account identity,
media titles/authors, and listening state, so they must not be committed or attached to a public pull
request without explicit approval and redaction. Sign-in was deliberately not captured while credentials
were entered. Locked/reauthentication, offline/error, active/failed/completed download, destructive user or
metadata operations, cover upload/match, and other unavailable role/state combinations were not
manufactured merely to make screenshots.

Mini/Full Player and its sheets are still outside this capture set: starting an arbitrary title writes real
server progress, so the review waits for an explicitly approved safe book. Android Auto is not a phone
screen and still needs a DHU/head-unit host. TalkBack, 2.0x font, landscape/wide window, themed icons, and
launcher switching remain separate checks.

### What the signed-in pass found

The cover/play direction works visually. The play targets are clear, series/author/genre artwork makes the
axes immediately distinguishable, one author portrait arrived through the authenticated client, and the
responsive home header retained its actions by dropping the decorative logo where space required it.

It also found three browse defects in the first reviewed APK:

1. Series, Authors, and Genres each said **0 books** while populated cards were directly visible below.
2. Tapping Genre **Edit** opened that genre's focused Books view; the clickable parent card intercepted the
   nested edit action, so the guided repair UI was not reachable.
3. Switching axes could reuse the previous list's scroll offset, initially presenting the new destination
   part-way down.

All three are fixed in the branch. Focused tests cover unique counts for every content shape and independent
physical-pointer Browse/Edit dispatch; deliberately reintroducing both defects made those tests fail. The
corrected APK was installed over the signed-in session and recaptured: Series reported 468 books, Authors 496,
Genres 372, and Genre Edit opened its confirmation without navigation or a server write. List composition is
also keyed by axis/view/focus so destinations no longer inherit another axis's offset. The Settings tabs are
long enough that their navigation
disappears deep in a page; Book detail duplicates its title and gives a destructive database-removal action
the same hierarchy as benign overflow items; Metadata is a long flat form without a current-cover preview
and its trailing blank series pair looks duplicated; and Series detail is a sparse list without the useful
cover/progress/play header the Home treatment establishes. Downloads has a clear but naturally sparse empty
state. Event Log is privacy-conscious but a raw 500-row sheet needs search/filtering. Debug Console reported
notifications **Blocked** on the fresh install with no onboarding path, a discoverability gap for foreground
playback controls.

The code/security findings remain more important than visual polish: the exported Media3 surface can accept
a caller-chosen URI that the ambient bearer interceptor may authenticate; profile switching is not the
ordered pause/flush/clear/activate/restore transaction PRODUCT_SPEC 6.5 requires; privileged management
adapters lack captured contracts; and the smart-download scheduler evaluates the manual-download traffic
category. The car browse root also lacks Downloads, Series, Authors, and Genres destinations. See the two
dated review documents for evidence and required tests. None was silently fixed as part of visual review.

## Phase 0 — complete

Merged in PR #1. `./gradlew verifyDebug` green: ktlintCheck, detekt with type resolution, Android
Lint, unit tests (including Robolectric), Room schema export and equality check, `assembleDebug`.

## Phase 1 — complete

> **`docs/gaps.md` is the live list.** The table below tracks `PRODUCT_SPEC 20`'s *deliverables*, and
> marking one done here says a screen or a repository exists, nothing more — `PRODUCT_SPEC 21` makes a
> requirement complete only when its **acceptance criteria** are met. The audit that found 30 open tasks
> against those criteria, `docs/archive/phase-1-remaining.md` (P1-01 to P1-30), has since been worked through:
> per-profile item visibility is a table of its own (`profile_visible_books`, joined on every read), the
> permission refresh `PRODUCT_SPEC 5.2` requires runs over the already-captured `POST /api/authorize`, and
> LIB-001's websocket criterion — the one the original plan lost entirely — is `AbsRealtimeConnection` over
> engine.io frames, with `RealtimeContractTest` and `EngineIoFramesTest` behind it. AUTH-005 subsequently
> closed with the passcode/biometric curtain, recovery path and process relock wiring described near the end
> of this document.

`PRODUCT_SPEC 20` lists seven deliverables.

| Deliverable | Status | Evidence |
| --- | --- | --- |
| Server profile | **done** | `:data:auth`, `DefaultAuthRepository.signIn` writes the server and profile rows, stores the token, selects the profile. Reached from the sign-in screen since step 9. |
| Login | **done** | `AbsAuthApi` + `AbsAuthContractTest` against the committed fixtures, and a real sign-in against a real server on hardware. |
| Secure token storage | **done** | `KeystoreTokenCipher`, `SessionTokenStore`, `SessionTokenProvider` in `:data:auth`. |
| Capability handshake | **done** | `AbsCapabilityResolver`, `DefaultCapabilityRepository`. Runs against the bound real gateway. It confirmed *no* capability, correctly, because `/status` speaks to none of them; the set fills in from observation and from Phase 5's probe. |
| Libraries/items sync | **done** | `AbsLibraryApi`, `LibraryMapper`, `AbsLibraryContractTest`. The real gateway is bound; the demo bootstrapper is gone. |
| Room-backed home/library/search/details | **done** | Reads server data, rendered on a device. Home is the shelf of every accessible book with LIB-002 search and sort, and the book, series and download screens read the same Room rows. Covers were the deferral recorded below and are built: `ImageModule` gives Coil the profile's credential, so a cover path fetches as an authenticated request rather than as a public URL. |
| Profile switch | **done** | `ProfileSwitcherScreen` + `ProfileSwitcherViewModel`, driving `SwitchProfileUseCase`. |
| Sign-in UI | **done** | `SignInScreen` + `SignInViewModel`, two stages, address confirmed before the password. |

### First run on a real device — 2026-08-05

A debug APK was installed on hardware and pointed at a real Audiobookshelf server. **This is the first
time any of this code has run outside a test.** What was observed:

| Behaviour | Result |
| --- | --- |
| Install and sign in against a real server | **Works.** First end-to-end sign-in this project has had. |
| Detected server version on the sign-in screen | **Correct.** |
| Library after sign-in | **Empty** — a manual refresh then populated it. A defect; see below. |
| Library sync from a real server | **Works** once refreshed: real libraries and items in Room. |
| Book details: history and progress | **Correct.** The `userMediaProgress` mapping is right on real data. |
| Sign-out | Library stays browsable, profile stays saved — AUTH-004's intent, confirmed on hardware. |

Two defects came out of it and are fixed:

1. **The empty library after sign-in had no explanation.** `SignInUseCase` runs an initial sync and
   `DefaultLibraryRepository` records its outcome in `sync_state`; home never read that table, deriving its
   status from an in-memory flag only a user-started refresh sets. A failed initial sync was therefore
   indistinguishable from an empty library. `ObserveSyncStateUseCase` now surfaces it, and home runs the
   initial sync itself once when a profile has never synced — so the case self-corrects whatever caused it.
   **The underlying cause of that first sync failing is still unknown**, because nothing recorded it where
   anyone could see. The next device run will show it.
2. **The reauthentication banner said "Downloaded books still play."** There are no downloads (Phase 3) and
   no player (Phase 2). Reworded.

### Second device run — the same day

The auto-sync fix worked: the library populated on opening. Two further points, one fixed and one
deferred by the owner.

3. **Home blocked on the sync.** Fixed. `LIB-001` says the home screen "can render partial cached content
   while sync continues" and that sync status is "visible but non-blocking", and it was doing neither: a
   refresh in flight replaced the whole screen with a spinner. Since a sync of a real library is an N+1
   over every item, that is a long wait in front of content the app already had. The cached library now
   renders immediately under a progress bar. The one remaining blocking state waits on *Room*, not the
   network — without it a cold start flashes "No server connected" before the profile row arrives.

4. **No cover art, and thin metadata on screen.** Deferred by the owner to a later phase, and recorded
   here because the *data* is not the problem: `LibraryMapper` maps and stores `coverPath`, `description`
   (preferring the server's `descriptionPlain`, already sanitized), narrators, genres, publisher, language
   and published year, and the device run confirmed progress and history render from that same expanded
   fetch. What is missing is the UI, plus one piece of plumbing for covers specifically — a cover URL is a
   server path that needs an authenticated image loader, so Coil has to be given the profile's credential.
   That is the `@AuthenticatedClient` OkHttp stack, which exists and is currently unused; wiring Coil to it
   is the natural next step and the reason that client was kept.

   **Correction to the deferral, for the record:** covers are *not* later-phase work in the spec. LIB-001
   lists them among what initial sync stores, LIB-004 lists them among what a book shows, and Phase 1
   delivers "Room-backed home/library/search/details". Deferring them is a scope decision the owner is
   entitled to make; calling Phase 1 complete without them is not the same thing, and this note exists so
   the two do not get confused later.

### Third device run — the shelf

The screenshot showed the home screen doing exactly what it was built to do and exactly what nobody
wanted: one card reading "Audiobooks — 188 books" above an empty screen, with the books one tap further
in.

5. **The app now opens on the books** (LIB-002), across every library the profile is granted, ordered by
   what was played last. `ObserveAccessibleBooksUseCase` + `LibraryRepository.observeAccessibleBooks`, with
   `BookSortOrder.LastPlayed` and the search and sort controls LIB-002 already required. Browsing by
   library did not go away — it moved behind **Settings → Open on libraries**, the first entry in the first
   settings screen (SET-001, SET-002), stored in Proto DataStore behind the new `:data:settings` module.

   Two things fell out of doing this that are worth more than the screen itself:

   - **The library grant is now enforced on read, not only on write.** Showing every accessible book meant
     asking "which libraries may this profile see?" at query time, and that exposed a real hole: the grant
     is applied when rows are *written*, and a grant that shrinks afterwards leaves the revoked library's
     rows in the cache with nothing to enumerate them again. All four read paths now filter by the grant
     persisted on the profile, and `DefaultLibraryRepositoryTest` proves a narrowed grant hides the library
     from every one of them — including the book detail route, which a deep link could otherwise reach.
   - `LibraryDao` is split into a read half and a write half, and the sync's write path is now
     `LibrarySnapshotWriter`. Both changes were forced by detekt rather than chosen, and both are right:
     nothing that reads can now name an `upsert`.

### Fourth device run — two accounts, and the root cause found

The run that mattered. Two real accounts on one server, one of them library-restricted, and the reports
from it identified the defect three earlier runs had only described.

| Reported | Verdict |
| --- | --- |
| Signing in as a user that does not exist said **"This profile needs to sign in again."** | **Defect.** `AppError.Authentication`'s default summary is the AUTH-004 reauthentication wording, and `NetworkErrorMapper` maps every `401` to it — including the one that means "those credentials were refused". Fixed at the call site, which is the only place that knows what was asked. |
| Signing in as root **needed a manual refresh before books appeared** | **Defect, root cause found.** See below. |
| An added empty library appeared | Correct. |
| Playing in the web interface **did not update the app** until a refresh | **Working as built, and a gap.** See *Progress freshness*. |
| After signing out, the card still offered **Sign out**, and asked to sign in again | **Defect.** Fixed: a profile that needs to sign in again now offers **Sign in**, and carries its server address and username to the sign-in screen. |
| Root saw everything; the restricted account saw only its own libraries | **Correct** — the first real-world evidence for exit criterion 2, though not the database check TC-35 asks for. |
| "I should be able to press which user I want, and it should show clearly which is active" | **Defect.** "In use" was plain text between two buttons and read as a third, disabled one. The active card now has the theme's selected colour and a filled badge, the whole card switches profile, and each card shows its server address. |
| Turning off the network and refreshing showed cached content with a failure | Correct — TC-42. TC-39 (force-stop and reopen offline) is still not done. |

#### The root cause of "empty until I pressed refresh"

Two bugs, both fixed, and neither was where the earlier runs suggested.

1. **One failed item threw away the entire library sync.** `AbsLibraryApi` fetches each item expanded —
   490 books is 490 requests — and it `return`ed on the first failure, discarding every snapshot already
   collected. One timeout in 490 attempts is close to certain over a home connection. PRODUCT_SPEC LIB-001
   says "failed optional sections do not fail the whole sync"; this did the opposite. It now keeps what it
   fetched and reports how much it could not, and the sync is recorded as `PartiallySucceeded` — a status
   the model has always had and nothing ever produced.

   The counterpart matters as much: an **unreachable** item must not be treated as a **removed** one.
   Reconciliation soft-deletes anything absent from a sync, so a partial sync that deleted what it could
   not reach would turn one timeout into books visibly disappearing. `LibrarySnapshot.isComplete` gates
   that, and only a fetch that saw everything is allowed to delete.

2. **`INSERT OR REPLACE` was cascading deletes across the schema.** Found by the test written for (1),
   which failed for the wrong reason. SQLite implements a `REPLACE` conflict as *delete the old row, then
   insert* — and the delete runs `ON DELETE CASCADE`. Every parent table was written that way: re-writing
   a library row deleted its books; re-writing a book row deleted its tracks, chapters, author and series
   links **and the reading profile's progress in it**; re-writing a server row deleted its profiles.
   Invisible while a sync always re-inserted everything it had just deleted, and data loss the moment one
   did not. All four parent tables now use `@Upsert`, which updates instead of replacing.

   This is the one worth remembering: the bug was not in the code the failing test was written for.

3. **The initial sync ran in a scope that was cancelled.** `SignInUseCase` awaited it, in the sign-in
   screen's `viewModelScope`, and a successful sign-in **pops** that screen. The sync died part-way and
   left `sync_state` saying `Syncing` — for a sync nothing was running. Home then refused to start its own,
   because its trigger was `NeverSynced`. The sync moved to home, which owns the screen the result appears
   on and outlives the navigation that reaches it; home also adopts a sync recorded as running that nothing
   is running.

#### Progress freshness — closed, and how

At the time of this run, playing in the web interface did not reach the app until a refresh. That was the
design rather than a bug — LIB-001 wants websocket events to update Room with a REST refresh as the
fallback, and only the fallback existed — and a full refresh is an N+1 over every item, so it could not be
run on every foreground.

Both halves are built now, in the order the captures allowed. The cheap half came first: `POST
/api/authorize` returns `user.mediaProgress` for the signed-in account in one request, and the capture was
re-run with an item played first so that the shape of an *element* was recorded rather than guessed at —
the earlier fixture's array was empty because the seeded contract server had never played anything, and
`PRODUCT_SPEC 22.4` forbids building on an unobserved shape. `me-after-session.json` is that element and
`socket-event-after-progress.json` is the frame that follows it. The socket came next, as
`AbsRealtimeConnection` over engine.io frames, and `ObserveRealtimeUpdatesUseCase` deliberately writes
nothing of its own: it hands the pushed payload to the same repository call the REST path uses, because two
implementations of "what does a changed account mean" would drift, and the REST one is the one with the
careful rules about not overwriting an unsynced local position. The socket's contribution is latency, not
data. `PRODUCT_SPEC SYNC-003` keeps a persistent background connection out of scope, so the connection
lives as long as something collects it.

### Fifth device run — the acceptance plan, in the app

Three requests came out of running the plan, and all three were about the plan being hard to run.

1. **The libraries toggle was the wrong shape.** "Libraries should just be displayed in the settings, not
   an *open on libraries* toggle." Correct, and it is the usual failing of a modal setting: it cost the user
   a trip to Settings, a flip, and a trip back to discover what it did. Settings now *lists* the libraries
   and opens one when tapped; the home screen is always the books. The proto field is reserved rather than
   removed, because a device that wrote it still has the bytes on disk.

2. **The `adb` cases were the hard ones.** They were also the important ones — the checks that ask what was
   *stored* rather than what is shown. **Settings → Storage on this device** now reports them: servers,
   profiles, saved sign-ins, libraries and books *stored* against *visible to this profile*, soft-deleted
   rows, and progress records.

   The stored-against-visible pair is the whole point. "Unauthorized libraries never appear" is really
   "unauthorized rows were never written", and a screen that hides a row looks identical to one that never
   had it — so a single number cannot answer it and two can. Counts only, never names: listing the libraries
   a profile may not see, in order to show that they are hidden, would be its own small breach
   (PRODUCT_SPEC 5.2).

3. **Known server addresses on the sign-in screen.** The address stage now lists the servers this device has
   used, each with the version detected and whether the connection was encrypted. Picking one fills the
   field and **re-probes**: what the user reads before typing a password describes the server now. A
   remembered "encrypted" would be a claim the app had stopped checking, and certificates expire.

**The results table did not survive the round trip.** The uploaded copy of `archive/phase-1-acceptance.md` had
sections 1–8 replaced by a single `## c`, so no result was recorded here. The plan below is the current one;
the run needs repeating against this build, which is no loss, since three of its cases changed.

### Sixth device run — the full plan, and the worst defect yet

The plan was run end to end. Most of it passed; four findings are defects and two are server behaviour.

#### Fixed

1. **A restricted account was deleting the unrestricted account's books.** The report: account A saw
   490 books, then switching to restricted account B showed 188 — *and 302 under "removed on the server"*.

   Audiobookshelf restricts twice: by library, and by tag *within* a library. B could see the shared
   library, so it synced it, and the server served it a filtered 188 items. Reconciliation then marked
   everything absent from that list deleted — 302 of A's books, on A's account too.

   Absence from a filtered account's sync says something about the filter, not about the server. The
   grant now carries `accessAllTags` (already in the captured contract, never read) and only an account
   with **all libraries and all tags** may drive deletions. Everyone else adds and updates, never removes.
   Database version 4; the new column defaults to restrictive for existing rows, unlike `hasAllLibraryAccess`
   in version 3 — getting that one wrong hides data, getting this one wrong destroys it.

2. **Libraries deleted on the server became permanent stale entries.** Nothing ever enumerated libraries:
   `refresh` reconciled books *within* a library and never the list of libraries itself. They are
   reconciled now, along with their books — the shelf reads books by server, so a deleted library's
   contents would otherwise stay on screen. Same authority rule as (1).

3. **Only the first account ever auto-synced.** `initialSyncAttempted` was one boolean for the whole
   ViewModel, so the second and third profiles were silently skipped. It is a set of profile ids now.

4. **"Saved sign-ins" read 6 for 3 accounts.** It counted files, and each profile stores an access token
   and a refresh token. It counts distinct profiles now.

5. **Search took about a second.** Filtering and sorting 490 books ran on the main thread, because a
   `Flow` collected in a ViewModel runs there. `flowOn(Default)` — the 300 ms debounce LIB-002 mandates is
   now the only delay.

6. **Pull-to-refresh** (LIB-001 asks for it in as many words). The toolbar button stays: a gesture some
   users never find, and one TalkBack cannot perform, is not a replacement for a button.

7. **Reauthentication lands on the password field.** The address and username were filled in but the user
   still had to tap *Continue* on an address the app had supplied. The probe now runs on arrival — run,
   not skipped: PRODUCT_SPEC 6.1 wants the version and encryption line seen before a password, and the
   credentials stage shows both.

8. **Book rows show position, remaining and total**, not just a percentage (LIB-004).

#### Server behaviour, not ours

- **Changing a password does not invalidate the session.** Audiobookshelf keeps existing tokens valid;
  *disabling* the account does invalidate, and the app handled that correctly. Nothing on the client can
  fix a token the server still honours.
- **Usernames are matched case-insensitively.** That is the server's login lookup. The app already stores
  and displays the username the *server* returned rather than what was typed, so it cannot show one
  account under another's name.

#### Asked for and not built — and what became of each

These were real, and each was more than a fix. All five are settled now, four of them by being built:

- **Websocket for live progress** (LIB-001's last bullet, SYNC-002) — built, as described under *Progress
  freshness* above.
- **Server-reachable indicator** — built on the shelf, as three distinct states rather than two:
  unreachable, reachable, and *unknown* when the device itself is offline, because a phone with no radio
  has learned nothing about the server. The per-server indicator on the sign-in screen was not built and
  does not need to be: picking a known server re-probes it, which is the same information at the moment it
  matters.
- **Refresh when the server comes back** — built, and deliberately narrow. Only a transition *into* online
  triggers it, and only when the last attempt actually failed: refreshing on every connectivity change
  would re-sync the whole library each time a phone hops between Wi-Fi and mobile, which on a 490-book
  library is 491 requests for nothing.
- **Books appearing as the sync runs** — built, and it is the mechanism PR #28's defect lived in. The
  catalogue list writes non-destructive previews as it arrives and the expanded fetch fills them in, with
  reconciliation still once at the end.
- **The per-library screen's missing actions** went away with the screen. The shelf is the only book list,
  and the settings list of libraries became a default-library choice rather than a way in.

### Exit criteria: all 3 demonstrated on hardware

The fourth device run put two real accounts on one server with one of them library-restricted, and the
sixth ran `docs/archive/phase-1-acceptance.md` end to end. That is what closed these, and it is worth naming the
evidence rather than the conclusion.

- Two accounts on one server can switch — **demonstrated**. Both profiles share one server row, the
  switcher lists them, and `SwitchProfileUseCase` swaps between them; the fourth run switched between a
  root account and a restricted one, and each saw its own library.
- Offline cached browse works — **demonstrated for the network-off case** (TC-42). What has still never
  been performed is TC-39, force-stop and reopen with no network, which is the case that would prove the
  cache survives the process rather than the connection. It shares its evidence with R-11.
- Unauthorized libraries never appear — **demonstrated against a real restricted account**, and it was
  the run that found the worst defect this project has had: a restricted account was deleting the
  unrestricted account's books, because absence from a filtered account's sync reads exactly like
  absence from the server. The grant is now enforced on read as well as on write, and only an account
  holding all libraries and all tags may drive a deletion.

### What closing them took

An APK, a real Audiobookshelf server, and a human — six times over, and each run found defects the whole
test suite had passed through. **`docs/archive/phase-1-acceptance.md` is the executable form of the criteria**: 53
numbered cases with the exact `adb` commands, the accounts to prepare, and the gaps that are expected to
fail so nobody raises them as defects. It is still the template for a device run, and Phase 2's waves each
left a script of their own beside it.

Nothing in this environment can perform any of them: `verifyDebug` compiles and unit-tests, and there is
still no device or emulator. That has not changed in six phases, and it is the single fact that explains
most of `docs/risks.md`.

## Phase 1's authentication layer, in detail

Written when this was the newest work in the tree, and kept because nothing since has replaced any of it.
Each commit landed with `verifyDebug` green; the suite was 248 unit tests then and is 111 test files today,
still with no instrumented tier.

### `:data:auth` (AUTH-001, AUTH-002)

The module `docs/architecture/module-boundaries.md` reserved. `DefaultAuthRepository` does, in this
order: normalize the address, probe it, authenticate, write the server and profile rows in one
transaction, store the token encrypted, select the profile. A rejected sign-in writes nothing.

`SessionIdentity` derives both ids rather than generating them: `ServerId` from the normalized base
URL, `ProfileId` from the server's own user id. Reauthenticating therefore returns to the same
profile and keeps the downloads and progress keyed to it. Hashing also keeps the host and the
username out of the token file name and out of log fields. The fallback when a server sends no user
id is the username, and the consequence — a rename then produces a second profile — is asserted in a
test rather than left implicit.

### Two boundary moves, both deliberate

- **`SessionTokenProvider` moved from `:app` to `:data:auth`.** The previous note said `:app` was the
  only module seeing both `:core:network` (which declares `TokenProvider`) and `:core:datastore`
  (which stores the token). `:data:auth` sees both too, and it also owns the sign-out that has to
  clear the in-memory copy. It is no longer nameable from the UI layer, and `AuthDataModule` binds
  `TokenProvider` instead of `AppModule`. The cached token is now tagged with its profile, so a
  failed switch cannot leave the previous account's credential attached.
- **`@UnauthenticatedClient` added.** The authentication endpoints used the shared authenticated
  client, which attaches the *active* profile's token — possibly for a different server — to a
  `GET /status` or `POST /login` aimed at a host the user just typed. That is a credential leak
  between servers. Auth calls now use a client with no `AuthorizationInterceptor` and pass their
  credential explicitly, and `AuthorizationInterceptor` no longer overwrites an explicit
  `Authorization` header, so a call can name the profile it acts for.

### `TokenCipher` is now an interface

`KeystoreTokenCipher` is the implementation. This is what made the AUTH-003 requirement testable at
all: a real Keystore key is invalidated by a device-level event Robolectric does not reproduce, so
"a lost key requires reauthentication rather than crashing" was previously unverifiable. It is now
covered against a fake in `SessionTokenStoreTest` and `DefaultAuthRepositoryTest`.

**Still unverified, and only verifiable on hardware:** the Keystore configuration itself — GCM, the
non-extractable key, `setUserAuthenticationRequired(false)` — and the real
`KeyPermanentlyInvalidatedException` path.

### Database version 2

`profiles.remoteUserId`, plus `servers.authMethodsJson`, `servers.capabilitiesJson` and
`servers.capabilitiesDetectedAt`. Every statement is additive; no table is recreated.

### Database version 3

`profiles.accessibleLibrariesJson` and `profiles.hasAllLibraryAccess` — the server's library grant, which
previously lived only in the transient `AuthSession` and so was unavailable to the sync that has to honour
it. The migration grants **existing** rows everything and defaults **new** rows to nothing: a profile
created before grants were recorded already has a library cached and browsable offline, and applying the
restrictive default retroactively would blank content the user is reading.

`MigrationTest` builds each starting version **from its committed exported schema** rather than from a
transcribed `CREATE TABLE`, so it cannot pass against a schema that drifted from the export, and it
migrates every version all the way to the current one — which is what a device two versions behind does.

`PRODUCT_SPEC 13` names a conceptual `ServerCapabilityEntity`; the handshake is stored as two JSON
columns instead, which `PRODUCT_SPEC 13` permits ("exact normalization may vary"). Nothing queries a
single capability — a handshake is written and read as one set for one server.

### Session renewal (AUTH-004)

`AuthRepository.renewSession` exchanges the stored refresh token; `RefreshLibraryUseCase` calls it on
a `401` and retries the sync once. No refresh token, a refused refresh, and an unusable renewed
session all mean "sign in again", all mark the profile, and none of them removes it or touches
downloads or local progress. Exactly one renewal per failure and at most one retry — the tests assert
that by counting calls, because "never loops login requests" is a bound, not an outcome.

The renewal replaces **both** tokens: the server issues a new refresh token each time, so keeping the
old one works once and then fails at the following renewal, hours later, looking like a random
sign-out.

### Capability handshake (SYNC-001)

`AbsCapabilityResolver` reads `/status` and confirms **no** capability. That is the finding, not a
stub: `/status` reports `app`, `serverVersion`, `isInit` and `authMethods`, and none of those is a
`ServerCapability`. A version-derived capability map is rejected because a self-hosted server sits
behind reverse proxies that break websockets and filesystems that break range requests — version is
evidence about the software, not the deployment.

That is still the rule, and it is no longer the whole picture: Phase 3 made `RangeDownload` and
`ChecksumOrETag` **observed** from a download that already happened rather than probed for, and Phase 5
added a probe for the management capabilities. A capability is set by evidence in both cases; what changed
is that a completed transfer is evidence and a version number is not.

`capabilitiesDetectedAt` stays null until a handshake runs, so "we have not asked" is distinguishable
from "the server does not support this" — SYNC-001 requires an explanation, and those are different
explanations.

## The contract fixtures now cover the library shapes

This is the most useful thing this session produced for the next one.

The previous `libraries.json` was `{"libraries": []}`: it proved the envelope key and nothing else.
A fresh container has no media, which is why. `scripts/seed-contract-media.sh` now generates one
eight-second audiobook — silence with metadata and two chapters — using the **server image's own
ffmpeg**, and `scripts/capture-contracts.sh` creates a library, waits for the scan to produce an item,
and records five library shapes. Twelve fixtures were committed then; **57 are committed now**, because
every phase since has added its own captures before it was allowed to rely on a shape, and CI re-captures
on every `:core:network` change.

**The finding that decides how LIB-001 has to be built: the item list is minified.** Each result in
`GET /api/libraries/{id}/items` carries `media.numTracks`, `media.numChapters` and
`media.numAudioFiles` as counts, and `media.metadata.authorName` / `seriesName` as *strings*. There is
no `tracks`, `chapters`, `authors` or `series` array. Only
`GET /api/items/{id}?expanded=1&include=progress` has them — plus `media.tracks[].startOffset`, which
is exactly what `PRODUCT_SPEC 11.3`'s global timeline needs, so offsets do not have to be derived by
summing durations.

So a sync that stores *playable* books cannot be one request per library. The list gives the
catalogue; each item needs its own expanded fetch before it can become a `BookSnapshot`.
`PRODUCT_SPEC 2.3` makes that non-optional — a book stored without its track offsets cannot be
resumed. Budget for it: N+1 requests per library, which is what `LIB-001`'s "failed optional sections
do not fail the whole sync" and its partial-render requirement are there to absorb.

Two capture artefacts, so they are not mistaken for server behaviour: `size` and `ino` are scrubbed to
`0` and `<volatile>`, and the fixture library has no series, so `library-series.json` records an empty
`results`.

## The Phase 1 deliverable plan, as it was built

Steps 1–9 below are the *deliverable* plan and are all complete. **They were never the whole of Phase 1**,
and the acceptance-criteria audit in `docs/archive/phase-1-remaining.md` is what caught the difference: its P1-01
to P1-30 are the criteria this list omitted, and they were built out over the phases that followed rather
than in a step 10. The list is kept because the order is the useful part — each step needed the one above
it, and a later phase that skipped that discipline is a later phase that had to capture a contract twice.

In dependency order.

1. ~~Commit the captured contract fixtures.~~ **Done.**
2. ~~Build the Retrofit client.~~ **Done.**
3. ~~Add `auth` to `AudiobookshelfGateway`.~~ **Done.**
4. ~~Secure token storage (AUTH-003).~~ **Done.**
5. ~~Server profile creation and session repository (AUTH-001, AUTH-002).~~ **Done.**
6. ~~Session expiry and refresh (AUTH-004).~~ **Done.**
7. ~~Capability handshake against `GET /status` (SYNC-001).~~ **Done.**
8. ~~**Libraries/items sync (LIB-001).**~~ **Done.** `AbsLibraryApi` filters by the persisted grant
   before anything reaches Room, fetches the catalogue and then one expanded item each, and fails a
   library's sync on any per-item error that is not a `404`. `AccountApi` was removed rather than
   reimplemented — its parameterless shape cannot serve a multi-profile client — and the note about
   `authorize.json` returning `user.token` only now lives on the gateway interface, where the next person
   to add a permission refresh will read it.

9. ~~**Sign-in UI and profile switch.**~~ **Done.** Two-stage sign-in, a profile switcher with sign-out and
   remove, a start destination decided from observed state, and AUTH-004's mark shown in both places.

   What step 9 deliberately did *not* build, and what has become of each:

   - **The switcher showed no server name.** `Profile` carried a `serverId` and not the server's own
     name, and a switcher that showed the wrong server would have been worse than one that showed none.
     The fourth device run made the omission a defect rather than a trade — a household with two servers
     cannot tell the cards apart — so each card now carries its server address, resolved through the
     join the domain layer previously did not expose.
   - **No avatar or colour.** AUTH-002 lists them as optional and they are still absent.
   - **No lock on profile selection.** AUTH-003 lists it as optional and explicitly not an authentication
     mechanism, and `PRODUCT_SPEC 24.14` left it open whether it belonged to version 1 at all. The owner
     has since decided version 1, and that is AUTH-005 — the work in flight at the end of this document.
   - **No settings screen**, so cleartext could not be opted into per server. Settings arrived with
     LIB-002's shelf and now carries the server, the libraries, playback, downloads, storage, devices,
     the launcher icon, appearance, language and diagnostics. The per-server cleartext exception is the
     one item that never appeared, and deliberately: ADR-0009 settled `PRODUCT_SPEC 24.13` by making
     cleartext a debug-build capability, so a release build keeps the platform's guarantee and there is
     no advanced screen to grant an exception in.

## Moving your settings between installs

Settings live in the app's proto DataStore, which Android deletes with the app. Until 2026-08-30 a debug
build also had to be uninstalled before every install (`DebugSigning` and `docs/release.md` fix that), so
this was a recurring loss rather than a rare one.

**Settings → About → Settings file.** *Export settings…* writes a JSON document wherever the system file
picker points; *Import settings…* reads one back. The sign-in screen has the import half too, under the
address field, so a fresh install can be set up before it has an account.

What travels:

| | |
| --- | --- |
| **Travels** | Server addresses. Playback (speed, skips, auto-rewind, buffer preset), the sleep timer, focus behaviour, startup mode. Network and download rules, the download volume. Known output devices and their ROUTE-002 policies. Theme, dynamic colour, language, the diagnostics toggles. Per-account view preferences, keyed by address and username. |
| **Never travels** | Any credential — no password, no bearer token, no passcode verifier. Those are in the Keystore-backed store precisely so they cannot be copied off the device, and an export that carried them would be a way around that (`docs/risks.md` R-20). |
| **Stays behind** | `playback_device_id`, the identifier this install sends the server as `deviceInfo.deviceId`. Copying it would make two installs one device in the server's listening history. Also the active profile, the fixture seed marker and the store's own schema version. `SettingsTransfer.EXCLUDED_FIELDS` carries the reason for each, and `SettingsTransferDriftTest` fails if a new setting is added without deciding which side of this line it is on. |

The file is JSON and pretty-printed on purpose: the promise above is one the person whose file it is can
check by opening it.

**It cannot be found automatically, and that is structural.** The owner asked for the app to detect the
file at startup. An app-private directory is deleted with the app, so a file there would not survive the
reinstall the feature exists for; anywhere else needs either a per-file SAF grant — which is the browse
button — or `READ_EXTERNAL_STORAGE`, a permission over every document on the device, asked for so the app
could read one. SAF grants do not survive an uninstall either, so remembering last time's location would
not help the case that matters. `SettingsFile`'s KDoc says the same thing next to the code.

Per-account view preferences restore only for accounts already signed in on the importing device. On a
fresh install every one of them is skipped, and the confirmation message says how many rather than
implying they arrived.

## Running this locally

Everything below assumes a checkout on your own machine with a device or emulator available. **That is a
different environment from the one most of this project was built in**, and the difference is not cosmetic:
a cloud session has no device attached, which is the single reason the instrumented tier and the
performance work stayed unbuilt for six phases. If you are running locally, you can do both.

### One-time setup

Run the checker first. It reports what is missing and what to do about each thing, and it writes
`local.properties` for you if it can find an SDK:

```bash
./scripts/check-local-environment.sh            # report
./scripts/check-local-environment.sh --install  # and install missing Android SDK packages
```

It exits non-zero only for something that will actually stop the build. A missing device or a missing
`jq` is a warning, because they block one task each rather than the build.

What it checks, and why each:

| | Needed for | Note |
| --- | --- | --- |
| **JDK 17 or newer** | everything | The build *targets* 17 bytecode and there is no Gradle toolchain, so the JDK running Gradle is the one that compiles. **17 is a floor, not a pin** — CI and every session so far have used 21. Android Studio bundles a suitable JDK. |
| **Android SDK + `local.properties`** | everything | The build reads `sdk.dir` from `local.properties` and nothing else. An SDK that exists but is not written down is the commonest way a first local build fails, so the checker writes the line rather than reporting it. |
| **`platforms;android-36`, `build-tools;36.0.0`, `platform-tools`** | everything | `compileSdk` and `targetSdk` are 36. `minSdk` is 26 and needs no platform installed. |
| **A device or emulator** | `connectedDebugAndroidTest` only | The one capability a cloud session does not have, and the reason to run locally at all. |
| **`jq`** | `scripts/vulnerability-scan.sh` only | `brew install jq` / `apt install jq`. `:app:sbom` works without it. |
| **Docker** | re-capturing contract fixtures only | Not needed for ordinary work. |

`--install` runs `sdkmanager` for missing SDK packages. It will **not** install a JDK or `jq`: those need
a system package manager and `sudo`, and a script that installs a JDK unasked is one nobody should run.

Nothing else is required — the Gradle wrapper fetches Gradle 8.14.3 itself, and the first build populates
`~/.gradle` from scratch.

### The commands, and which question each answers

```bash
./gradlew ktlintFormat                                   # always first; formatting failures are noise
./gradlew verifyDebug -Pshelfplayer.warningsAsErrors=true # the gate: ktlint, detekt, lint, unit tests, Kover
./gradlew :app:assembleDebug                             # the APK, at app/build/outputs/apk/debug/
```

`verifyDebug` is what CI runs and what a change has to pass. Cold it takes 5–8 minutes; incremental runs
are 10–90 seconds. **Add `--rerun-tasks` before believing a green result on a branch that changed a
classpath** — Gradle has considered test-compile tasks up to date when only the classpath moved, and that
once let two stale test doubles pass locally and fail in CI (R-31).

### The tiers that need a device — the reason to run locally at all

```bash
adb devices                                              # confirm one is attached and authorised
./gradlew :core:datastore:connectedDebugAndroidTest      # the profile lock's storage, on real hardware
```

`connectedDebugAndroidTest` is **not** part of `verifyDebug` and never runs in CI, because CI has no
emulator. It is the only way to execute `KeystoreLockCipherTest` and `ProfilePasscodeStoreTest`, which
between them cover the AndroidKeyStore wrap, the staged write, and the rate limit that lives inside the
encrypted record — none of which Robolectric can reach, and all of which R-39 recorded as untested from
the day AUTH-005 landed.

Results land in `core/datastore/build/reports/androidTests/connected/`. The tests clean the Keystore alias
and the `locks/` directory at both ends of every run, so a crashed run does not poison the next one.

**They are safe to run on a device you actually use BookWave on**, which is not obvious and was checked
rather than assumed. The tests delete the Keystore alias `shelfplayer.lock.v1` and wipe a `locks/`
directory — the same names the real app uses — but the test APK installs as
`com.example.shelfplayer.core.datastore.test`, a different package and therefore a different UID.
AndroidKeyStore entries and `filesDir` are both scoped per UID, so the two never meet. Verified with
`aapt2 dump badging` on the test APK rather than reasoned about.

**An emulator with no lock screen configured is fine too.** `KeystoreLockCipher` sets
`setUserAuthenticationRequired(false)` on purpose (ADR-0023), so key generation does not need a secure
lock screen to exist.

### The supply-chain checks

```bash
./gradlew :app:sbom              # CycloneDX 1.5 -> app/build/reports/sbom/bom.json
./scripts/vulnerability-scan.sh  # asks OSV about every component in it; needs network and jq
```

`:app:sbom` fails if any component reaching the release classpath has no pinned checksum. It also prints
the current component count, which is the authoritative source for the figures quoted in prose here and in
ADR-0023 — those are snapshots and go stale every time a dependency moves.

### Adding a dependency

Not a one-line change. `org.gradle.dependency.verification` is `strict`, so a new library fails the build
until its checksums are recorded. For a small addition, let Gradle merge them:

```bash
./gradlew --write-verification-metadata sha256 <the task that failed>
git diff gradle/verification-metadata.xml     # review every added component before committing
```

That merges rather than rewrites — adding the instrumented tier appended exactly three components and six
checksums, and touched nothing else. `scripts/bootstrap-dependency-verification.sh` regenerates the whole
file and is for a version-catalogue sweep, not for one library. Budget for this before deciding a library
is the cheap option; AUTH-005 weighed it as one of three reasons not to take `androidx.biometric`.

### The contract-capture harness

Only needed when a server response shape changes. It runs a real Audiobookshelf container:

```bash
docker pull ghcr.io/advplyr/audiobookshelf:2.36.0
./scripts/seed-contract-media.sh   # then the capture task; see docs/api-compatibility.md
```

**Capture twice against the same container and `diff -ru` the outputs before committing.** CI asserts
byte-identical captures, and the first version of the library fixtures failed that check because `lastScan`
and the file id inside `contentUrl` vary per capture and were not scrubbed. Both are in the scrubber now. A
*second* capture against an already-initialised server legitimately differs in `init.json`,
`status-uninitialized.json` and `userDefaultLibraryId` — those come from the fresh-server sequence, and CI
always starts a new container.

### Notes from the cloud sessions, kept because they still apply there

The container that produced most of this work came up **without** an Android SDK and with `~/.gradle`
empty, so "caches are warm" was not true. Recovering it takes two steps and both are reliable:

```bash
# Android SDK (dl.google.com is reachable)
curl -fsSL -o clt.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
mkdir -p /opt/android-sdk/cmdline-tools && unzip -q clt.zip && mv cmdline-tools /opt/android-sdk/cmdline-tools/latest
export ANDROID_HOME=/opt/android-sdk
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
echo "sdk.dir=$ANDROID_HOME" > local.properties
```

**Docker needs starting by hand there**: the daemon is installed but not running. `dockerd &` works (the
session runs as root).

**`docker exec` and attached `docker run` hang in that sandbox.** `docker run -d` works. Any container
command that needs output has to be run detached and its result read from a bind mount or `docker logs` —
which is why `seed-contract-media.sh` uses `docker run --rm`, fine when CI invokes it and awkward
interactively.

**And no device is attached**, which is the constraint that shaped six phases of this project: the
instrumented tier, the macrobenchmarks, the baseline profile, the Android Auto head unit and the two-hour
soak are all blocked there and none of them are blocked locally. The owner's own machine has since run
`:core:datastore:connectedDebugAndroidTest` green on an SM-S928B, which is what unblocked the benchmark
harness being worth building.

## Phase 2 — complete

The streaming player, built in six waves and closed over four further pull requests, with device runs
shaping it throughout — six of them on wave 5's branch alone, which is why `docs/archive/phase-2-gaps.md` rather
than the plan is that phase's authoritative checklist.
`:playback` is the only module in the build that may name ExoPlayer, a `MediaSession` or a `Service`, which
is what makes PLAY-001's "a single media session" structural rather than a convention somebody has to
remember.

Wave 0 captured 31 fixtures before anything was built, and three of its findings decided the design rather
than being worked around. `startOffset` is a **global** offset into the book — track two of a two-file book
reports the duration of track one — so the global timeline is arithmetic over values the server already
sends rather than a sum this app derives and can get wrong. The offline outbox has to drain through
`POST /api/session/local-all`, because that is the only route that reports a per-session result. And
"finished" is thirty seconds remaining rather than PLAY-004's 95%, decided by the owner in ADR-0013,
because 95% of a ten-hour book is half an hour from the end.

Then, in order: audio at all, with the play session's request body pinned field-for-field to the capture,
because `supportedMimeTypes` is what makes the server direct-play rather than transcode and a transcoded
session is a shape no fixture covers; the sleep timer, built early at the owner's request, where ADR-0014
records that a shake **restarts** the timer rather than extending it, since a shake is made in the dark by
somebody who has lost track of how long is left and "put it back to what I set" is the only answer they can
predict; the global timeline and the full-screen player; progress persistence and PLAY-005's outbox, where
every write reaches a local table before anything touches the network, because a position that was
attempted and lost is indistinguishable afterwards from one that was never recorded; speed, skips,
auto-rewind and buffer presets, with the presets applied when the player is *constructed*, since recreating
a live player mid-book to honour a setting is what product priority 1 forbids; and Android Auto with
media-button resume, which is ROUTE-001 and was the last of the phase's exit criteria.

ADR-0016 is the one to read. **A book is one timeline window.** Media3 reports the current *item's*
position to every controller, so a playlist of files made the notification describe the file — "time left
in this chapter" on a library with one file per chapter — and one arithmetic error in it produced a
527-hour book. A book is now one `MediaItem` over a concatenating source, and most of that change was
deletion: nothing converts positions any more.

Two failures from this phase are worth carrying forward. Bookmarks shipped in 0.9.0 with **no way to create
one** — every layer was tested and correct, and the tests asked whether a bookmark could be *stored* and
never whether it could be *made*. And database version 14 shipped in a build, was then edited in place, and
crashed the one device that had installed it, so version 15 exists to undo an edit that should have been a
new version. A shipped schema is immutable, and the exported schemas are what make that checkable.

Confirmed in a car on 2026-08-14: Android Auto discovery and media-button resume. Never run at all: the
two-hour soak (R-09).

## Phase 3 — complete

Downloads and offline playback, in eight slices, against the eight answers the owner gave to the plan's
open questions (ADR-0018).

The pipeline is the phase. A file is fetched into `<name>.part`, verified, and renamed; nothing is ever
written under a final name, so a name that exists has been through the check — which is what lets the
player and the start-up verifier trust a name instead of re-reading a hundred megabytes. The rename is
atomic within a filesystem, so a crash leaves either a resumable part or a finished file and there is no
third state.

Resume is guarded with `If-Range`, and that is the load-bearing detail: a server that declines a range
answers `200` with the whole file, and appending that to a partial file produces a file made of two
different recordings — one that passes every size check there is, if the lengths happen to line up. So the
destination is opened only once the status is known, with append set to what the response justifies, and a
failed request never opens it at all. Verification is DL-002's four minimums, and the fourth earns its
cost: a captive-portal login page arrives with a `200` and a truthful `Content-Length`, and only opening it
as media fails.

What this app deliberately does not claim is that the bytes match the server's. An ETag is a validator and
not a checksum, so "Check downloaded files" verifies presence, recorded length and openability, and is
labelled for exactly that. Retention (DL-006) is off by default, because deleting somebody's audiobook
unattended is the most destructive thing this app can do, and when it is on it refuses four cases
explicitly: the book that is playing, a pinned copy, a book whose progress has not reached the server, and
a book finished more recently than the cutoff.

Network policy is one switch per category rather than DL-004's three-way picker, at the owner's request:
Wi-Fi is always allowed and is not a setting at all. Metering comes from `NET_CAPABILITY_NOT_METERED`
rather than from the transport type, because the two disagree in both directions and both cases are common
— a phone hotspot reports Wi-Fi and is metered, an unmetered mobile plan reports cellular.

Two things landed after the slices, in the gaps pull request. `RangeDownload` and `ChecksumOrETag` became
**observed** from a transfer that already happened, because `/status` cannot be asked whether a server
honours a range, and the diagnostics screen was reporting a server as unable to do something it had just
done. The gate deliberately does not skip the range request when the capability reads false: `supports`
cannot tell "this server refused" from "nothing has asked yet", so gating on it would disable resuming
everywhere rather than only where it does not work. And downloads learned to live on an SD card — ADR-0020
splits the volume from the folder, because `getExternalFilesDirs` hands back ordinary `File` paths the whole
pipeline works on unchanged, while an arbitrary folder needs SAF, where there is no atomic rename — and the
atomic rename is the property DL-001's "atomic commit prevents a false complete state" rests on.

## Phase 4 — complete

`PRODUCT_SPEC 20` calls this phase the smart downloader and device automation. The smart downloader arrived
early, as Phase 3's last slice and under ADR-0017, so what this phase actually built was the automation,
the rename, and the three playback requirements its closeout swept up.

The app is **BookWave**. `app_name` changed in both locales, along with every string that named the product
and the `User-Agent` the server sees in its own logs. This phase deliberately left the `applicationId`
alone because changing it after users install produces a second, empty app; ADR-0024 later closed that
release blocker before publication by choosing `org.homebord.bookwave`. Kotlin packages and Gradle
namespaces remain `com.example.shelfplayer`. Seven launcher icons ship as `activity-alias` entries of which
exactly one is enabled, and the two writes are ordered enable-then-disable — the other order leaves a window with no
enabled launcher component, and a launcher that samples the package during it drops the app from the
drawer and does not put it back. The owner-supplied wave/book mark is the fresh-install default; an older
explicit choice wins during an upgrade so adding that default cannot create a duplicate drawer entry.
The component-name mismatch is intentional: the old manifest-default `IndigoAlias` now renders BookWave,
the old Indigo artwork lives at `IndigoClassicAlias`, and a `MY_PACKAGE_REPLACED` receiver moves an
explicitly enabled legacy Indigo choice to that replacement immediately. Renaming those aliases for
tidiness would undo the no-duplicate upgrade strategy (ADR-0019).

ROUTE-002 gives every headset, speaker, hearing aid and car this app has seen its own answer to "what
happens when this connects". **No permission is requested, and none is needed:** `AudioDeviceCallback`
reports every kind of output through one callback, and `AudioDeviceInfo` gives a product name without a
grant, so a device is identified by its kind and its advertised name and never by a hardware address. Two
identically named headsets share one policy, and the owner confirmed that trade over a permission prompt.
Arm only is the default and auto-play is never global: arming loads the last book paused, so the button on
the headphones starts it instantly with no app to open and no book to find, which is most of the value of
auto-play without the part that makes noise in a quiet room.

The closeout took PLAY-002's pause-or-duck, expressed as the audio *content type* the player is built with
so that Media3's own focus handling stays in charge of the phone call and the permanent loss; ROUTE-003's
startup mode, applied from `Application.onCreate` because opening the app means this process starting, and
a listener who paused to read a message must not find their book restarted; and PLAY-006's rebuffer count,
where the whole thing rests on `STATE_BUFFERING` meaning two different things — the first fill of an item
is startup latency, running dry mid-sentence is a rebuffer, and counting the first as the second would
report a rebuffer on every book that ever played.

`docs/gaps.md` was written here, and it is the document to read before believing any status in this one.

ROUTE-002's single unmet criterion was the profile lock, because there was no locked state to check. That
is AUTH-005.

## Phase 5 — complete

Management tools: nine slices, one of which ships no feature at all, and the first phase in which this app
**writes to somebody's library**.

Every phase before this one could be wrong and cost the user a re-sync; this one can be wrong and cost them
their metadata, their covers or an item. So slice 1 was not a metadata editor but a capture run, and the
four questions the captures could not answer were settled from the Audiobookshelf project's own source
rather than guessed at. `docs/archive/phase-5-plan.md` numbers eight slices and MGR-007's embed metadata arrived on
2026-08-20 as a ninth; PR #27 counts all nine, and the headline counts in the plan and in `docs/gaps.md`
have not caught up with the ninth.

What shipped: the four server-side grants persisted and enforced twice (database version 18), a capability
probe, the metadata editor with a three-way conflict comparison (version 19), covers validated by this app
rather than by the server, match built on a read-only search — quick match turned out not to be a preview
at all, but to apply the change and then report it — removal from the database that never sends `?hard=1`,
user management that never *parses* a token, and embed metadata whose outcome exists only on the websocket
and which reports "unknown" when the connection drops, because nothing replays a missed `task_finished` and
MGR-007's last criterion is a rule against guessing.

Slice 7 is the one to read. Source-file deletion has two endpoints and **neither can prove the deletion
happened** — a failed filesystem removal is logged on the server and discarded, and the request succeeds
either way — so ADR-0021 records the decision to ship no such feature. MGR-006 requires the response to
confirm the removal, and no wording could have made the app's claim true.

Twelve defects were found and fixed along the way: eight by an adversarial audit against every acceptance
criterion on 2026-08-16, and four by a device run on 2026-08-20, three of those the same shape — **a
feature that worked perfectly and could not be reached.** The account-management row was hidden from
non-admins, which on a device is indistinguishable from a feature that was never built and was reported as
exactly that; `theme_mode` and `dynamic_color` had been in the settings proto since the first build with
nothing anywhere that could write them; and a complete Norwegian translation was reachable only by changing
the whole phone's language. `docs/gaps.md` lists all twelve, because the shapes are more useful than the
count.

## Phase 6 — in progress

Phase 6 delivers seven things, and **two of them were already built** because earlier phases needed them:
the browsable media library and the diagnostics screens. What has landed since the phase opened in PR #27:

- **A risk register.** `docs/risks.md`, 42 entries as this is written, each carrying what goes wrong, how
  bad it is if it does, and the cheapest thing that would retire it. It is a separate document from
  `docs/gaps.md` on purpose: a gap asks what this build does not do, and a risk asks what it does that
  could go wrong. It grows as work lands rather than only as work is finished — the last four entries are
  AUTH-005's own, and one of them retired the entry that had described the absence of a lock.
- **Adaptive layouts** for tablets, foldables and split screen — two panes on the book screen and the
  player, capped sign-in, centred lists rather than stretched ones. Unverified on hardware (R-07).
- **Accessibility enforced by test.** `AccessibilityAssertions` walks everything the semantics tree reports
  as clickable and fails on an unlabelled control or one under Material's minimum target size, over every
  screen with a destructive action and several at a doubled font scale. It has found two real defects, and
  the second is one no reading of the code would have caught: at a doubled font scale the player's
  secondary control row laid out **4dp tall** — present, announced and impossible to hit — because the
  square artwork claimed the column's whole width as its height regardless of what was left. What no JVM
  test can reach at all is whether a label is *useful*, whether contrast is sufficient, and what TalkBack
  does with the reading order (R-29, R-35).
- **Documents that describe this build.** `PRIVACY.md` and `SECURITY.md` had described Phase 0 for five
  phases, which matters most for the one document a user reads before trusting a client with their server's
  credentials. `docs/release.md` had listed a blocker ADR-0021 had settled. And `versionName` had stuck at
  `0.9.6-auto-shelves` for nine builds while the code advanced, so every field report in that window
  identified the wrong build; it moves with the code now, at `0.9.11-car-and-pause` and code 37.
- **The car's resume tile and per-chapter progress**, and **download pause** — where the mechanism was
  always there, since cancelling leaves the parts and enqueueing again resumes from them, and what was
  missing was a *state*. A cancelled job left the manifest reading `Failed`, so a listener who stopped a
  download deliberately came back to "Download failed" and an offer to retry: the app apologising for
  having obeyed. Nothing automatic lifts a pause, so a download stopped on a metered train does not
  restart itself when Wi-Fi comes back.
- **PLAY-003 closed as correct rather than defective.** A gap entry had stood for four phases claiming that
  a book whose track list excludes a file resolves positions against the wrong offsets. The server's own
  source disposes of it: the track list is built from the *included* files and accumulates `startOffset` as
  it goes, so an excluded file is removed before any offset exists, and the player's concatenation and the
  book's timeline are the same coordinate space by construction. The entry had been written from the shape
  of this app's own model — `AudioTrack.isExcluded` exists, so the server must send it — rather than from
  the server's behaviour, and four phases of "known defect" followed from one unchecked premise.
  `CapturedShapesTest` now fails if a fixture ever shows a hole or a flagged track.

- **The supply-chain half of the release pipeline**, which ADR-0024's licence decision unblocked.
  `./gradlew :app:sbom` writes a CycloneDX 1.5 document — **175 components, 130 with a pinned SHA-256** —
  from two sources, each authoritative for one thing: scope from `releaseRuntimeClasspath`'s resolution
  result, integrity from `verification-metadata.xml`. The 45 components without a hash each carry a
  property saying why, and all 45 are `no-binary-published` (a Kotlin Multiplatform parent, or a BOM).
  **None is `not-pinned`**, and that value fails the task rather than appearing quietly in the document —
  verified by deleting a component's block from the metadata and watching the build name it.
  `scripts/vulnerability-scan.sh` then asks OSV about every component: **no known advisories today**,
  confirmed to be a real answer by poisoning the SBOM with `log4j-core:2.14.1` and watching it report all
  seven Log4Shell entries. The workflow also archives the R8 mapping, which `docs/release.md` had wrongly
  listed as blocked on a signing key; R8 writes one whenever minification runs.

**Performance profiling: the harness is built, the numbers are not taken (R-25, R-27, R-58).** `:benchmark`
is a `com.android.test` module that drives a release-like `benchmark` variant of the app over a seeded
2,000-book library: cold start with time to initial *and* full display, frame timing while scrolling
`BooksView.List`, Home's peak heap and RSS, and `BaselineProfileGenerator` to record the profile. Every
task it owns needs a device, so CI never runs one — but CI does *compile* the module on every pull request,
because its `debug` variant is left enabled and `verifyDebug` therefore lints, detekts and assembles these
sources. A benchmark that stops compiling is the normal fate of code no gate touches.

A baseline profile is *generated* by running a macrobenchmark on a device; hand-writing one would be
guesswork of the kind this project refuses elsewhere. The *consuming* half is already free, because
`androidx.profileinstaller` is on the release classpath transitively, so shipping a `baseline-prof.txt`
needs no new dependency — only the recorded file.

Two of 17.3's four numbers stay manual and that is a property of the requirement rather than a shortfall:
player start from a cached local book and no-ANR-under-download/playback-stress both need a real server and
a real download. A committed benchmark pointed at somebody's private instance would be unreproducible by
anyone else and would put a host name in the repository (14.5). `docs/benchmark.md` is the runbook for all
six measurements and holds the results table waiting to be filled in.

**What did get resolved is what to measure, and the answer changed the target (ADR-0025).** 17.3 asks for
a *"scrolling grid … on 2,000-item fixture library"*, and R-26 had flagged that this might be describing a
screen the app does not have. It is: `LazyVerticalGrid`, `LazyHorizontalGrid` and `GridCells` appear **zero
times** in the repository. Home is a `LazyColumn` of `LazyRow` shelves capped at 20 items each, the flat
"all books" view is a list, and the library-browse destination was deliberately removed. Building the
benchmark first would have meant inventing a grid to satisfy a measurement — the ADR-0016 mistake again,
where a "known defect" stood for four phases on one unchecked premise.

The target now measures `BooksView.List`. ADR-0025 also adds the one 17.3 could not have named, because it
describes a grid rather than an architecture: **there is no paging anywhere**, `Flow<List<Book>>`
materialises every visible book on every emission, and `HomeViewModel` reasons explicitly about that cost
at 490 books — 2,000 is four times the number the code was thought about at. Whether to adopt paging is
left to the measurement rather than assumed, for R-27's reason.

The cheap prerequisite for all of it is a **seeded 2,000-item fixture generator**; the committed demo
fixture holds 7 books, and a two-thousand-entry JSON file nobody will read a diff of is not the answer.

**The instrumented tier exists now, and holds one module (R-07).** `:core:datastore` has an `androidTest`
source set: `KeystoreLockCipherTest` and `ProfilePasscodeStoreTest`, 27 tests over the AndroidKeyStore
wrap, the staged write and the rate limit that lives inside the encrypted record. That slice was chosen
first because it needs no Hilt, no Compose, no UI and no biometric hardware, so it is deterministic on any
attached device and fails for one reason only — and because R-39 had recorded it as untested since AUTH-005
landed.

**They now compile, package, and pass on one physical device.** The 2026-08-23 run of
`./gradlew :core:datastore:connectedDebugAndroidTest` on a Samsung SM-S928B / Android 16 reported 27 tests,
0 failures, 0 errors, and 0 skipped. That retires the narrow “has never executed” gap for this source set,
not R-07's wider lack of UI/playback/Auto/device-matrix instrumentation. A cloud session still has no device,
and CI still does not execute this task.

Three things learnt building it, which the next module's tier will need:

- **`junit-ktx` is the one `androidx.test` artifact with no checksum here**, so `@RunWith(AndroidJUnit4)`
  costs a metadata regeneration. `AndroidJUnitRunner` runs a plain JUnit 4 class without it, and these
  tests do.
- **Adding the tier still needed three components pinned** — `androidx.test:runner:1.6.2`,
  `androidx.test.services:storage:1.5.0` and an old `lifecycle-common`. `--write-verification-metadata
  sha256` scoped to the failing task *merges* rather than rewrites: exactly 24 lines added, nothing else
  touched.
- **The test APK is its own package**, `com.example.shelfplayer.core.datastore.test`, so its UID differs
  from the app's. The tests delete the alias `shelfplayer.lock.v1` and wipe a `locks/` directory using the
  real names, and cannot reach the installed app's records — checked with `aapt2 dump badging`, not assumed.

`verifyDebug` does **not** include `connectedDebugAndroidTest` and CI still has no emulator, so
`app/build.gradle.kts`'s objection — "a test suite nothing runs is not a regression net" — is answered by a
person running it locally rather than by CI.

What remains of the release pipeline is three items and one decision: launching the release APK once,
managed-device tests, the two-hour soak — all needing hardware — and a pull-request **label convention**,
without which the changelog stays hand-written rather than generated.

## The two hardening merges after Phase 6 opened

PRs #28 and #29 are the most consequential merges in the tree, and #29 is the more important of the two.

**#28 fixed four fail-closed defects, two of them serious.** Catalogue reconciliation was **deleting an
entire library on any unchanged refresh**: a `LibrarySnapshot` carries only *expanded* items, an item the
server reports unchanged is deliberately skipped, so a second sync of an unchanged library produced an
empty book list, took the `rows.isEmpty()` branch and called `markAllBooksDeleted` — and every read filters
`isDeleted = 0`, so the shelf went blank. Reconciliation now follows the validated catalogue id set minus
the items an item-detail `404` actually proved gone. Separately, a **60-second OkHttp `callTimeout` bounded
the Media3 stream**: `callTimeout` covers reading the body, and the media data source shared the ordinary
API client, so any track longer than a minute was torn down mid-playback. Media3 and streaming file
transfers now use clones of the same transport with no absolute deadline, while connect, read and write
stay bounded so a dead peer still fails. The other two are the same shape as the first: an empty page
mid-listing read as end-of-library and authorised deletion of the remainder, and a `206` was appended
without checking where it started, so a cache answering another request's offset produced a file of exactly
the right size made of two different files.

**#29 answered the question #28 raised: why did nothing catch this?** A test named `refresh is idempotent
and does not duplicate rows` had existed for months, refreshed twice, and passed, while the production path
it covered emptied a library. It passed because `FakeAudiobookshelfGateway.listBooks` **ignored its
`cached` argument** and returned every book on every call, so the fake never produced the shape the real
gateway produces. Underneath that, fixture books carried no `updatedAt` stamp and `isUpToDate` requires
one, so the skip path was unreachable regardless of what the fake did — and that absence was the blind
spot. The fake now honours `cached.isUpToDate`, fixture books carry a stamp, and **reverting the production
fix now fails the previously-blind test through the full repository path**, which is the only evidence
there is that a test covers what it claims to.

R-37 records the general form: a test double that does not reproduce the shape of the real thing hides
defects behind a passing test, and this fake was not a double but a second implementation that agreed with
nothing. R-38 records the smaller trap left behind — `onCatalogueBatch` defaults to `onBatch`, so a future
persistence caller that forgets the sink silently gets the destructive behaviour the parameter exists to
avoid.

## AUTH-005 — complete; Keystore store verified on hardware, curtain/biometric unseen

The profile passcode lock originally landed from `claude/auth-005-profile-lock`. `PRODUCT_SPEC 24.14` asked whether profile
PIN or biometric protection belonged to version 1 or 1.1, and **the owner has decided version 1**, which
also unblocks ROUTE-002's last criterion. The specification asks for the lock five times — 3.2, 3.3,
AUTH-003, 8.12 and ROUTE-002 — and only ROUTE-002 says anything about behaviour. `docs/gaps.md` collects
them under the label AUTH-005, which is this project's name for the set and not a section the specification
has.

**ADR-0023 is the decision, and it is titled honestly:** *the profile passcode is a curtain, not a vault*.
Ten files across four modules cite it by number for decisions they do not restate, so it had to exist before
any of them could be reviewed. It names the threat first, because none of the design follows from the five
sentences in the specification and all of it follows from the threat: **somebody holding this phone, already
unlocked, who is not the account's owner.** Not a stolen device, not an attacker with the filesystem, not a
rooted phone, and not the server's own authorisation model.

That feature branch finished with `verifyDebug` green. **Eight defects were found in this feature after it
first looked complete, and five of them were the same shape: correct code that nothing reached.** That ratio
is the most useful thing in this section — see R-43, and the closing paragraphs below, which say what each
one was.

- **The record is its own proto, deliberately.** `AppSettingsDataSource.settings` catches `IOException` and
  emits `getDefaultInstance()`, which for a settings screen is a sensible default and for a lock would be
  **fail-open** — a corrupt file would unlock every profile. `ProfileLockRecord` is a separate schema behind
  a separate reader, one file per profile under `filesDir/locks/`, and any failure to read, unwrap or parse
  it returns null, which every caller treats as **locked**. Every write is staged and renamed under a mutex
  for the same reason read the other way round: a half-written verifier fails closed, and failing closed
  with no way back is somebody locked out of their own library permanently.
- **The rate limit lives inside the encrypted record**, not in memory and not in settings, so a force-stop
  cannot reset it: four free attempts, then thirty seconds doubling to a fifteen-minute cap, and at ten
  consecutive failures the passcode is refused permanently and only signing in again clears it.
- **The Keystore alias is separate from the session token's**, and that is not tidiness. Three mechanisms in
  `SessionTokenStore` would otherwise reach the verifier: `clear(profileId)` iterates the token kinds and
  would delete a fourth on sign-out, `clearAll()` calls `cipher.clear()` and would destroy the key the
  verifier is wrapped with, and `storedCredentialCount()` counts file stems and would report a lock as a
  saved sign-in.
- **The honest arithmetic**, which the ADR carries rather than implies: a six-digit passcode behind
  210,000 PBKDF2 iterations is roughly 2×10¹¹ hash iterations of search space, which one modern GPU
  exhausts in minutes. The verifier does not resist an attacker holding the file. What the Keystore wrap
  buys is exactly one thing — obtaining the record requires executing code on the device rather than
  copying a file off it — and the product says so rather than implying more.
- **Biometric unlock is app-enforced policy, not cryptography.** The stored verifier is a one-way
  derivation, so a fingerprint cannot produce it; the gateway trusts the platform's answer and grants a
  ticket. The device credential is **refused** as an unlock factor, because the threat this lock addresses
  is somebody holding the already-unlocked phone, and that person has the device credential by
  construction.
- **The gate is in memory only.** A cold start is therefore locked, and the relock delay is evaluated
  against the clock at *read* time, so the media service and the UI cannot disagree about whether a profile
  is currently unlocked.
- **ROUTE-002 is a truth table now.** `AutoStartDecision` is a pure function, and it suppresses arming and
  asking as well as auto-play, which is **stricter than the sentence in the specification**. Arming makes no
  sound, but it puts the locked account's title, author and cover on the lock screen, one headset press from
  audio — and that press cannot be intercepted, because there is no `onPlayerCommandRequest` and no
  `ForwardingPlayer` anywhere in this app. Product priority 4 decides it where the specification is silent.
  A device set to never do anything still reports "none" rather than "suppressed", because the lock changed
  nothing about that device and a diagnostic line claiming otherwise would be false.
- **Nothing already playing is touched.** `OutputDeviceWatcher` consults the guard only after it has asked
  whether the session is busy, so product priority 1 stays structural rather than remembered.
  `SwitchProfileUseCase` refuses a switch to a locked profile *before* writing the selection, with
  `AppError.Security`.
- **The curtain replaces the app's content rather than overlaying it.** An overlay would leave the mini
  player's polite live region in the semantics tree for TalkBack to read aloud over the lock, and its stop
  button reachable. `MainActivity` draws the curtain or the app and never both, and neither while the lock
  state is still resolving — a shelf shown for one frame before the curtain arrives is a leak with a
  screenshot.
- **The platform biometric API, not `androidx.biometric`**, and the reasons were measured rather than
  assumed. The library resolves, but pulls in the full `androidx.appcompat`, where today only
  `appcompat-resources` is on the classpath; its API 26 and 27 compatibility path constructs an AppCompat
  dialog, which throws under this app's platform-parented theme — a crash on the two oldest supported
  levels, in a path the current instrumented tier does not reach. Adding the biometric compatibility path would
  also mean regenerating the verification metadata for the pinned component set. So biometrics are
  `android.hardware.biometrics.BiometricPrompt` from API 28, and **API 26 and 27 get no biometrics at
  all** and are shown a disabled row that names the reason. The passcode is the floor on every level.
- **The product discloses what the lock does not cover**, on the curtain itself: the notification and
  lock-screen transport keep working, a connected car can still browse and play, downloaded audio is
  ordinary unencrypted files, and the lock does not protect against somebody who can read the phone's
  files.
- **A forgotten passcode is cleared by signing in to the account again**, and that is stated as a feature
  rather than a bypass — AUTH-003 says the lock is not about server authentication, and the account
  password is a strictly higher bar. Its cost is on screen *before* the passcode is set: it needs the server
  to be reachable, so it does not work offline. `SignInUseCase` calls `clearIfLocked`, which clears the
  record of a profile that was locked and deliberately leaves alone the lock of one that was not — a profile
  that was already unlocked is somebody re-authenticating after an expired session, and their passcode is
  theirs.
- **The lock needed no migration.** At that point the schema stayed at 19. The current tree is schema 20 for
  unrelated author-artwork fields. There is no `isLockEnabled` column and no settings field,
  because the record's existence *is* the fact and a second copy could only agree with it or be wrong about
  it. The profiles table was rejected for a second reason: every other column there is server-derived and is
  rewritten on each permission refresh.

**The eight defects, because the pattern in them is worth more than the list.** Numbers 1–4 and 6 are the
same shape — code with no caller, no control or no renderer, which is R-43. The rest are prose that
described something the repository did not contain.

1. **The lock was inert.** Nothing in production called `ProfileLockGate.onBackgrounded`, so `backgroundedAt`
   was never stamped, `isUnlocked` returned `true` for the life of the process, and all three relock delays
   behaved identically — the curtain guarded a cold start and nothing else. Ten passing tests covered the
   arithmetic. Fixed by `ProcessLockWatcher`, whose own test fails if the wiring is removed, and which
   ignores `isChangingConfigurations` so a rotation is not treated as leaving the app.
2. **The curtain was a dead end.** The recovery *logic* was wired — `SignInUseCase` called `clearIfLocked` —
   and the curtain offered no control that reached it. An exhausted or unreadable record left only Android's
   "clear storage". Fixed with an inline re-authentication field.
3. **A locked profile that was not the active one could not be opened at all.** The curtain reads
   `activeProfileId`, so it draws for one profile; the switch is refused before a locked profile becomes
   active. Tapping such a card produced "That account is locked. Enter its passcode to switch to it" and the
   app contained no such field anywhere. Fixed by a passcode dialogue in the switcher, driven by
   `ProfileLockRepository.isLocked` — defined as the negation of the same `mayActivate` the refusal uses, so
   the prompt and the refusal cannot disagree.
4. **`USE_BIOMETRIC` was designed and never added to the manifest**, so the biometric row would have been
   offered and then refused by the platform. Caught by lint rather than by a test.
5. `LockCurtain`'s KDoc cited a `LockCurtainScreenTest` that did not exist. Writing it required splitting
   `LockCurtainContent` out of the Hilt-bound composable — worth knowing, because the same shape is needed
   for any future screen test here.
6. **Three distinct refusal reasons were collapsed into one message**, so `111111` was refused with "a
   passcode is between 6 and 12 digits" — two of the three reasons could not be rendered at all. Fixed by
   moving `PasscodeRejection` into `:core:model` and carrying it through.
7. `ProfilePasscodeStore` documented a `[LockedByFailure]` state that does not exist; the real one is
   `PasscodeVerdict.Unreadable`.
8. The dependency-verification figures in ADR-0023 were quoted from memory as 852 components and 1,540
   checksums. Measured at the time they were 887 and 1,612; the instrumented tier has since added three
   components, so they are now **890 and 1,618**. Any figure written into prose here is a snapshot — the
   commands that produce it are in the "Running this locally" section.

**The behavioral screen/policy tests are pure JVM.** Ten for the key derivation and its passcode policy, ten for the gate's ticket
lifetime, five for ROUTE-002's truth table — the first coverage `OutputDeviceWatcher`'s policy branch has
ever had — six for the startup-mode clause, five for `ProcessLockWatcher`'s wiring, seven for the curtain
under Robolectric including the disclosure block, six for the switcher's prompt, and extensions to the
sign-in and switch tests. The separate connected tier now adds 27 passing AndroidKeyStore/store cases on
hardware. The final branch-wide `verifyDebug -Pshelfplayer.warningsAsErrors=true --rerun-tasks` result must
still be recorded after all current working-tree changes settle.

**Two guards were proved by reverting the fix and watching the test fail** — the switcher prompt (four of six
tests go red) and, earlier on this branch, PR #29's catalogue reconciliation. That step is worth keeping:
R-37 and R-43 are both cases where a test passed over a real defect, and the only way to know a new test
would have caught it is to remove the fix.

**The lock UI has not been exercised by a person.** No passcode has been typed on hardware, no biometric
prompt has ever been drawn by this app, and the disabled row has never been seen on an API 26 or 27 device.
The app-switcher thumbnail is not suppressed on any level, which was checked rather than assumed. R-39's
Keystore wrap/store half is now exercised by the 27-test device run; key invalidation, the prompt, curtain,
and API-26/27 row remain outside it.

**One residual hazard is recorded rather than fixed: R-44.** `clearIfLocked` fires from the ordinary sign-in
screen too, where the user may only have meant to refresh an expired session, and nothing there mentions the
lock — so a passcode can be removed silently. It is bounded (the profile must be locked at that moment, and
for the active profile the only reachable sign-in is the curtain's own disclosed one) and recoverable, and
reporting it properly needs a place to put the sentence on a screen that navigates away on success. That is
a design question, not an oversight.

## What has never been verified

Stated plainly because several of these look done from the code. `docs/risks.md` is the full accounting,
with a blast radius and a cheapest mitigation for each; this is the short list, and the first entry is the
one that explains most of the others.

- **The instrumented tier covers one module.** `:core:datastore` has an `androidTest` source set over the
  profile lock's Keystore storage, and no other module has one. Its first physical run passed 27/27 on
  2026-08-23, but it still never runs in CI. Everything else is on the JVM, and the UI tier is Robolectric
  at `sdk = 34` apart from two
  files that run at more than one level because the level changes the mechanism. Most of
  `PRODUCT_SPEC 17.2` is therefore still untested, and every device run so far has found defects the whole
  suite passed through — eight in the audit of 2026-08-16, four in the run of 2026-08-20 (R-07, R-08).
- **The two-hour playback soak has never run** (R-09), and **process death has never been measured**
  against `PRODUCT_SPEC 17.3`'s ten-second progress budget (R-11). Product priority 2 is "do not lose
  progress", and its acceptance number has never been checked.
- **What the car has seen is two runs of an older build.** Android Auto discovery and media-button resume
  passed in a car on 2026-08-14, and a run before that is what found the empty *Continue* tab. Everything
  added since — the resume tile behind the *recent* root, the per-chapter completion badges, the spoken-query
  path — has never been in a car or in the Desktop Head Unit, and every one of those is a rendering
  contract a head unit decides (R-10).
- **The release build is assembled and never executed** (R-12). R8 and resource shrinking run in CI and
  nothing installs the result, so a missing keep rule is a release-only crash with a minified stack trace.
- **`KeyPermanentlyInvalidatedException` handling is covered only against a fake cipher.** The connected
  tier now exercises the real AndroidKeyStore GCM/non-extractable-key configuration and staged record
  writes, but it does not reproduce permanent key invalidation or biometric enrollment changes.
- **Two contracts are read from the server's own source rather than captured from it**, and one is captured
  from somewhere CI cannot reach. Cover *upload* needs a multipart body and an image the capture script
  should not invent; MGR-007's embed metadata needs an administrator on a reachable server, so neither its
  `200` nor the `task_finished` frame carrying the outcome has been recorded from a live run. MGR-003's
  candidate shape comes from a run against a public demo server, because Google Books answers `429` to CI's
  addresses every time, so the committed shape and the CI fixture will keep disagreeing. Each is labelled
  for what it is where it is used.
- **The lock curtain and biometric UX have not been seen by a person.** See the AUTH-005 section above for
  the specifics; this no longer includes the Keystore store itself.

The migrations, by contrast, have now run on devices — that is how the version 14 crash was found — and the
grant filter has been exercised by a genuinely restricted account, which is how the deletion defect in the
sixth device run was found. Both were on this list for good reason, and both came off it the hard way.

## Security note

A live API key and a password for a real Audiobookshelf instance were pasted into the session that
produced the Phase 1 contract work. **They should be rotated.** No credential is in this repository:
the committed fixtures come from throwaway containers with the fixed fake credentials in
`scripts/capture-contracts.sh`, and the workflow fails if anything credential-shaped survives
scrubbing.
