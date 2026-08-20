# Open gaps

**As of:** 2026-08-20, entering Phase 6.

Every requirement this app has *not* fully met, and why. Kept as one document rather than a note per phase,
because the question anybody actually asks is "what is missing" and not "what was missing in April".

A gap is listed here only if it is real: a criterion the specification states and this build does not
satisfy. Risks — things this build *does* that could go wrong — are in `docs/risks.md`. Work that was
never in scope for a phase is not a gap, and neither is anything PRODUCT_SPEC itself defers to a later
version.

Each entry says what is missing, what it costs today, and what it depends on. **Blocked** means something
else has to exist first. **Deferred** means the specification itself put it later. **Open** means it could
be built now and has not been.

---

## Phase 1 — Authentication and cached browsing

| Requirement | Gap | State |
| --- | --- | --- |
| AUTH-005 | **Profile PIN / biometric lock.** No lock exists, so `Optional profile PIN or biometric gate` (§3.2) is unbuilt. | Open |

**What it costs today:** anyone holding the unlocked phone can switch profiles and see another household
member's library and progress. On a personal device that is the same exposure as the home screen; on a
shared one it is not.

**What it blocks:** ROUTE-002's *"Auto-play never starts when the active profile is biometric/PIN locked"*
cannot be satisfied, because there is no locked state to check. `OutputDeviceWatcher.onConnected` is where
that check goes.

---

## Phase 2 — Streaming player

| Requirement | Gap | State |
| --- | --- | --- |
| PLAY-003 | **Excluded tracks and the timeline's coordinate space.** | **Closed 2026-08-20 — not a defect** |

This entry stood for four phases and described a bug that cannot occur. It said a book whose track list
excludes a file resolves positions against the wrong offsets, because the player concatenates the playable
tracks while the book timeline still counts the excluded one.

The server's own source disposes of it. `Book.getTracklist` maps over `includedAudioFiles` — `audioFiles`
already filtered — and accumulates `startOffset` as it goes, so **an excluded file is removed before any
offset exists**. `media.tracks` is therefore always contiguous and never contains an exclusion; the
player's concatenation and the book's timeline are the same coordinate space by construction, which is
what makes ADR-0016 correct rather than merely convenient.

`docs/api-compatibility.md` records the reading verbatim, and `CapturedShapesTest` now fails if a captured
fixture ever shows a hole or a flagged track — so the day this stops being true, a test says so instead of
a listener's bookmark moving.

**What was really wrong here was the gap entry**, and it is worth saying how: it was written from the
shape of the app's own model (`AudioTrack.isExcluded` exists, so the server must send it) rather than from
the server's behaviour. Four phases of "known defect" followed from one unchecked premise.

---

## Phase 3 — Downloads and offline playback

| Requirement | Gap | State |
| --- | --- | --- |
| DL-003 / §3.3 | **Downloads into a user-chosen folder (SAF).** A volume can be chosen — internal or an SD card — but not an arbitrary directory. | Deferred |
| §12 | **The twelve named job states.** The manifest models four. | Open, by design |
| DL-001 | **Pause and resume a running download from the UI.** | **Closed 2026-08-20** |

**SAF** is deferred by PRODUCT_SPEC 3.3 itself, and ADR-0020 records why the volume half shipped without
it: `getExternalFilesDirs` gives real `File` paths that the whole pipeline — `.part`, verify, atomic rename,
sweep — works on unchanged, while a `DocumentFile` tree has no atomic rename, which is the property DL-001's
"atomic commit prevents a false complete state" rests on.

**The job states** are deliberate rather than missed. §12 names twelve states for the *coordinator*; the
manifest stores the four a *file on disk* can be in. Modelling all twelve in the manifest would create two
places that can disagree about whether a file exists, and WorkManager already owns the other eight.

**Pause** is built. The mechanism was always there — cancelling leaves the `.part` files and enqueueing
again resumes from them — and what was missing turned out not to be plumbing but a *state*. A cancelled job
leaves the manifest reading `Failed`, so a listener who stopped a download deliberately came back to
"Download failed" and an offer to retry: the app apologising for having obeyed. `DownloadState.Paused` is
the difference, it is stored so it survives the night, and nothing automatic lifts it — smart download
passes `isAutomatic = true` and steps over a paused book, so a download stopped on a metered train does not
restart itself when Wi-Fi comes back.

---

## Phase 4 — Smart downloader and device automation

| Requirement | Gap | State |
| --- | --- | --- |
| ROUTE-002 | **Auto-play never starts when the profile is locked.** | Blocked on AUTH-005 |
| ROUTE-002 | **`Ask` is `Ready` plus the media notification**, not a separate dismissible prompt. | Open |
| ROUTE-002 | **The global "auto-play when a car connects" switch still exists** and overlaps the Car device's own policy. | Open |
| PLAY-006 | **Advanced buffer values** — explicit minimum, maximum, playback-start, rebuffer-start, target bytes. Only the five presets are offered. | Open |

**`Ask`** currently arms the book, and the paused media session puts a resume control in the notification
shade — which is literally *"show a notification action to resume"*, using the notification the app already
has. What it is not is a prompt that leaves the player untouched until you answer. Both readings are
defensible; the second is more work and nobody has asked for it.

**The car switch** should probably become the Car row's policy and disappear. It was left alone rather than
migrated silently, because a switch that vanishes and reappears somewhere else with a different meaning is
worse than one that overlaps for a while.

**Advanced buffer values** are in PLAY-006's user-facing model. The five presets cover the acceptance
criteria that matter — invalid combinations rejected, applied on next preparation, position survives
recreation — and the advanced form is the part nobody can use without the diagnostics that only just landed.

---

## Phase 5 — Management tools

**Complete.** All eight slices are done, one of them — source-file deletion — correctly with no feature at
all.

An adversarial audit against every acceptance criterion in EPIC MGR and EPIC USER ran on 2026-08-16 and
found **eight defects that this document did not list**, all of them now fixed. Worth recording what they
were, because they are the shapes this kind of work fails in:

- **Disabling your own account was one mis-tap and unrecoverable** — the account that would undo it is the
  one just disabled. Now refused outright rather than confirmed.
- **The cover cache never invalidated.** A new cover was invisible forever: the loader ignores cache headers
  and nothing evicted, so the URL *was* the key. `?ts=` was documented and not implemented.
- **A save whose follow-up read failed reported as a failed save**, handing back an "unsaved draft" of
  changes already on the server.
- **Permissions were a snapshot** taken when the editor opened, so going offline left every button live.
- **The destructive removal was gated on the grant alone**, not on connectivity.
- **A successful removal said nothing.**
- **A duplicate username was a page-level card**, not a field error.
- **A `REMOVED` scan left a phantom book** on the shelf.

Two were found by the compiler and the linter rather than by the audit, during the fix: an
`?.let { } ?: …` that folded "permitted" into "blocked", and three strings written but never rendered.

A **device run on 2026-08-20** found four more, all fixed, and three of them are the same shape: a feature
that worked perfectly and could not be reached.

- **The account-management row was hidden for a non-admin**, which on a device is indistinguishable from a
  feature that was never built — and was reported as exactly that. The row is now drawn for everybody and
  names the reason when it cannot be used, alongside the account's role and its four server-side grants.
  The gap this closes is diagnostic: until now the only way to find out whether an account held the
  `update` grant was to read the server's own web interface.
- **`theme_mode` and `dynamic_color` had no control.** Both have been in the settings proto since the first
  build and applied by `MainActivity` ever since; nothing ever wrote them, so the only device that could
  have a value was one restored from a build that never existed.
- **There was no language setting** despite a complete `values-nb` translation, so Norwegian was reachable
  only by changing the whole phone's language. See ADR-0022 for why it is carried by the composition rather
  than by `LocaleManager` alone.
- **The About tab described a build from three phases ago**, down to "the management tools are not [built]".

| Requirement | Gap | State |
| --- | --- | --- |
| MGR-006 | **Source-file deletion ships no feature.** Both endpoints exist, and neither can prove the deletion happened: a failed filesystem removal is logged on the server and discarded, and the request succeeds either way. MGR-006 requires the response to confirm it. | **Closed by decision** — ADR-0021 |
| MGR-003 | **A successful match is still uncaptured, and no longer blocking.** Quick match turned out not to be a preview at all, so MGR-003 is built on `GET /api/search/books`, which writes nothing. That endpoint reaches a third party, so its *shape* is captured and its results deliberately are not. | Unblocked |
| MGR-002 | **Cover *upload* has still never been captured** — only removal. It needs a multipart body and an image the capture script should not invent. The contract is known from the project's own source: multipart, part named `cover`, validated on the filename extension. | Open, source-derived |
| MGR-007 | **Embed metadata is built, and its contract is source-derived rather than captured.** Starting one needs an administrator on a reachable server, and the public demo account is refused, so neither the `200` nor the `task_finished` frame that carries the outcome has been recorded from a live run. The route, its query parameters, its permission gate and the task frame's shape were read from the server at 2.36.0. `TaskFramesTest`'s fixtures are hand-written from that reading and say so. | Open, source-derived |
| MGR-007 | **"Metadata only, cover only, or both" is not offered**, because `POST /api/tools/item/{id}/embed-metadata` has no such parameter — the cover is written with the metadata or not at all. MGR-007's own *"if the API supports it"* covers this; the confirmation dialog says so rather than leaving the absence unexplained. | Closed by the API |
| MGR-007 | **A dropped websocket leaves the outcome unknown, and the app says so.** Nothing replays a missed `task_finished`, so an embed whose connection died mid-task reports neither success nor failure. That is the honest answer and it is also a real limitation: the only way to find out is to look at the server. | Open, by the protocol |
| MGR-007 | **Batch embedding is not offered.** `POST /api/tools/batch/embed-metadata` exists. One item at a time is the deliberate choice for an operation that rewrites files and cannot be undone — a mis-tap that rewrites one book is recoverable from a backup, and one that rewrites a library may not be. | Open, by trade |
| USER-003 | **Deleting a user is not offered**, and disabling is. USER-003 puts deletion in later scope "unless thoroughly contract-tested", and it has not been. Library-access editing is also absent — the requirement asks for a warning about other devices' downloads first. | Deferred, correctly |
| MGR-001 | **A name containing a comma cannot be typed.** Authors, narrators, genres and tags are edited as comma-separated text, which is the right shape for two-item lists and the wrong one for `Smith, Jr.`. A chip editor would fix it and costs four gestures where a text field costs one. | Open, by trade |
| MGR-003 | **The candidate shape is captured from a demo server, not from CI.** Google Books answers `429` to GitHub Actions' addresses on every run, so the CI capture records an empty list. The committed shape comes from a run against `audiobooks.dev` on 2026-08-16 and names an Audible result's keys. The CI fixture will keep disagreeing with it. | Open, environmental |

Two findings are not gaps but standing rules:

- **`GET /api/users` returns every user's live token.** `UserDto` must never model the field, so there is
  nothing to store, log or display. Pinned by `CapturedShapesTest`.
- **Refusals on the management routes are `text/plain`, not JSON** — `Forbidden`, `Not Found` — because
  those handlers use Express's `sendStatus`. The same mechanism is why three of them answered
  `text/plain "OK"` on success. `NetworkErrorMapper` keys on the status code, so this costs nothing today;
  it would cost something the moment somebody reads an error message out of a management response.

---

## Phase 6 — Android Auto, polish, release

Phase 6 delivers seven things (PRODUCT_SPEC 1750): a browsable media library, adaptive UI, accessibility,
diagnostics, privacy/security docs, performance profiling and a release pipeline. **Two of the seven are
already built** — they arrived early because earlier phases needed them — and the table below is what an
audit on 2026-08-20 found for the rest, rather than what the phase heading implies.

| Deliverable | State on entering the phase |
| --- | --- |
| **Browsable media library** | **Built.** `AutoLibrary` publishes the tree; `PlaybackService.LibraryCallback` implements `onGetLibraryRoot` (including the *recent* root behind the car's resume tile), `onGetChildren`, `onGetItem`, `onSearch`, `onGetSearchResult` and the spoken-query path through `onSetMediaItems`. The Chapters tab carries per-chapter completion against a book-progress header. Never run in a car — see R-10. |
| **Diagnostics** | **Built.** The event log, the capability rows, the sync checklist and the copyable debug console (PRODUCT_SPEC 14.4) all shipped in earlier phases. |
| **Adaptive UI** | **Built 2026-08-20.** Two panes on the book screen and the player, capped sign-in, centred lists. Unverified on hardware (R-07). |
| **Accessibility** | **Enforced by test, unverified on a device.** The net covers every screen including the player, the mini player and the shelf, several at a doubled font scale. It has found two real defects. R-29. |
| **Privacy/security docs** | **Rewritten 2026-08-20.** |
| **Performance profiling** | **Not started.** No baseline profile, no benchmark module, none of PRODUCT_SPEC 17.3's four numbers measured. R-25 to R-27. |
| **Release pipeline** | **Partly.** PR and main workflows run wrapper validation, a secret scan, `verifyDebug` with warnings-as-errors, a Room schema diff, release lint and an unsigned release assembly. Dependency verification is `strict` over 887 pinned components. Missing: SBOM, vulnerability scan, changelog, mapping archive, managed-device tests — each blocked on a decision rather than on work. R-01 to R-06. |

| Requirement | Gap | State |
| --- | --- | --- |
| §3.3 / packaging | **`applicationId` is `com.example.shelfplayer`.** Google Play rejects `com.example.`. ADR-0019 records why it did not change with the rename: Android identifies an install by its `applicationId`, so changing it produces a *second, empty* app rather than a renamed one — costing a fresh sign-in and every downloaded book. The right moment is the first release, before anybody has an install to lose, and it needs its own decision about migrating the database. | Open, with a deadline |
| §4 / §129 / §51 | **Adaptive UI is not built.** **Closed 2026-08-20.** `WindowWidth` reads the window size class; the book screen and the player go to two panes when there is room, sign-in is capped, and lists centre rather than stretch. What is unverified is how it looks on real hardware — R-07. Every screen is a single phone-width column, so a tablet, a foldable and a split-screen window all get the phone layout stretched across the available space. §51 makes adaptive layout a release requirement, not a nicety. | **Closed** |
| §51 / 2.10 | **Accessibility semantics are now enforced by a test; the device half is not.** `AccessibilityAssertions` walks everything the semantics tree reports as clickable and fails on an unlabelled control or one under Material's 40dp minimum. It covers Settings, the book screen, the downloads queue and the profile switcher — every screen with a destructive action — and one of them renders at a doubled font scale. It found a defect immediately: every genre and tag was a disabled `SuggestionChip`, which still publishes an `OnClick`, so a screen reader announced each as a dead button. The player, the mini player and the shelf are covered as of 2026-08-20, and the extension found a second defect: at a doubled font scale the player's secondary control row laid out **4dp tall** — present, announced and impossible to hit — because the square artwork claimed the column's whole width as its height regardless of what was left. The artwork is weighted now, so large text shrinks the cover instead of crushing the transport. What no JVM test can reach at all is whether a label is *useful*, whether contrast is sufficient, and what TalkBack does with the reading order. R-29, R-35. | Partly closed |
| §14.5 / packaging | **`PRIVACY.md` and `SECURITY.md` describe Phase 0.** **Closed 2026-08-20.** Both rewritten for a build that talks to a server. That was true for one phase and has been wrong for five; `PRIVACY.md` is the document a user reads to decide whether to trust a client with their server's credentials. | **Closed** |
| 18 | **`docs/release.md` lists resolved blockers.** **Closed 2026-08-20.** Rewritten; it had named MGR-006 as open after ADR-0021 settled it, and and lists integration tests as blocked on "endpoints to test", which Phase 1 delivered. | **Closed** |
| 18 / 24 | **Closed 2026-08-20.** `versionName` had stuck at `0.9.6-auto-shelves` for nine builds; it moves with each one now (`0.9.11-car-and-pause`, code 37). The debug console prints it, so a field report identifies the wrong build. | **Closed** |
| PLAY-001 / 11.1 | **The car's now-playing screen names the book, not the chapter.** The browse tree answers "how far through each chapter am I" (a completion bar per row, and a header row giving progress through the whole book), but the *playback* screen still shows title and author only. An app cannot draw in a car, so the only way to change that line is to change what the session reports as the current item's `MediaMetadata` — and the book is one `MediaItem` over a custom concatenating source (ADR-0016), so `replaceMediaItem` re-prepares it and stops the audio. The supported alternative is a `ForwardingSimpleBasePlayer` wrapping the ExoPlayer and rewriting the metadata in `getState()`, plus a ticker calling `invalidateState()` at each chapter boundary because position alone raises no event. **Deliberately not built here:** that wrapper sits between the media session and the player, so a mistake in it breaks the notification, the lock screen, the headset and the car at once — product priority 1 — and there is no instrumented test or head unit to catch one (R-07, R-10). | Open, needs a device first |
| 17.2 / 17.3 | **Nothing has been verified on hardware.** No instrumented test exists anywhere in the repository; the whole UI tier is Robolectric at `sdk = 34`. The API matrix, the two-hour soak, the process-death budget, Android Auto and the release APK are all unexercised. Three device runs have each found defects the suite passed through. | Open, and the largest |

The full accounting of the last row, with blast radius and the cheapest mitigation for each, is
`docs/risks.md`. It is a separate document because a gap and a risk are different questions: a gap asks
what this build does not do, and a risk asks what it does that could go wrong.

---

## Things that look like gaps and are not

- **`FakeAudiobookshelfGateway.signIn` returns `AppError.ApiCompatibility`.** Deliberate. The fake exists to
  back repository tests, not to let the app sign in without a server.
- **The capability set is empty on a fresh server.** `AbsCapabilityResolver` records only what a probe
  confirms, and `/status` confirms nothing except the websocket. `RangeDownload` and `ChecksumOrETag` fill in
  after the first download, which is the only honest evidence there is.
- **Changing the download volume moves nothing.** By design — the manifest holds absolute locations, which
  is what makes the setting safe to change.
- **Auto-play from a cold start is unreliable.** Android's, not this app's. The setting says so.
