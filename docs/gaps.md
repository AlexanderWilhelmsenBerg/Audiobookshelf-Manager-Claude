# Open gaps

**As of:** 2026-08-23, during Phase 6.

The focused server-contact and Android Auto audit performed after this phase-entry inventory is recorded in
[`reviews/2026-08-22-server-android-auto.md`](reviews/2026-08-22-server-android-auto.md). That review is the
current authority for those two surfaces while its new findings are triaged into this longer inventory.
The implementation/specification and signed-in phone review is
[`reviews/2026-08-23-product-ui-ux-gap-analysis.md`](reviews/2026-08-23-product-ui-ux-gap-analysis.md).

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
| AUTH-005 | **Profile PIN / biometric lock.** A per-profile passcode with an optional biometric prompt, so `Optional profile PIN or biometric gate` (§3.2) is built and PRODUCT_SPEC 24.14 is answered as version 1. | **Closed 2026-08-21 — ADR-0023** |
| AUTH-005 | **The recovery the curtain promises is now code.** `SignInUseCase` calls `clearIfLocked`, and the curtain carries the password field that reaches it — for an exhausted record and an unreadable one alike, neither of which offers a passcode field. | **Closed 2026-08-21 — R-42.** The residual hazard, that the same call is silent from the ordinary sign-in screen, is R-44 |
| AUTH-005 | **The relock delay fires.** `ProcessLockWatcher` calls `onBackgrounded` and `onForegrounded` from `Application.ActivityLifecycleCallbacks`, ignoring `isChangingConfigurations` so a rotation is not treated as leaving the app. | **Closed 2026-08-21 — R-41.** Whether `Immediately` is the right default still needs a device |
| AUTH-005 | **A locked profile that is not the active one is reachable.** The curtain draws for the active profile alone and the switch is refused before a locked profile becomes active, which left the refusal naming a passcode field that existed nowhere. The switcher now prompts. | **Closed 2026-08-21 — R-45** |
| §6.5 | **Profile switching is an ordered playback transaction.** The player is paused, flushed and cleared — awaited — before the active profile changes, and every playback write now carries the `ProfileId` of the account whose session opened the loaded book rather than resolving whoever is active when the row reaches Room. | **Closed 2026-08-23 — R-49.** Steps 2–5 of 6.5. Step 6 is the row below |
| §6.5.6 | **The new profile's last book is restored paused.** `RestoreProfilePlaybackUseCase` reads the *incoming* account's library, picks its most recently played unfinished book with the rule `AutoLibrary` used to own, and arms it. Called from both switch paths in `ProfileSwitcherViewModel` after a live session, launched rather than awaited so AUTH-002's 500 ms budget is not spent on a network call. | **Closed 2026-08-23.** 6.5 is now complete end to end |
| AUTH-005 / §3.2 | **API 26 and 27 get no biometrics at all.** The passcode is the floor there, and on 28 and 29 no sensor-strength claim is made. | Open, by the platform |
| AUTH-005 / §15 | **Closed 2026-08-24 — ADR-0026 decision 3.** The Recents thumbnail is suppressed from API 33 with `setRecentsScreenshotEnabled(false)`. `FLAG_SECURE` was declined rather than forgotten: it would block every screenshot and screen recording for every user. API 26–32 keep the thumbnail, which is the recorded residual. | **Closed**, with a stated gap below API 33 |
| §8.12 | **Nothing obliges an admin account to take a passcode.** The lock is offered per profile with no policy behind it, and 8.12 asks for it *"especially for admin accounts"*. | Open, needs a decision |
| AUTH-005 | **The biometric prompt is exercised by nothing.** It is a window the system draws, so nothing short of a person with a fingerprint reaches it. | Open, needs a person. **The Keystore half is closed:** `KeystoreLockCipherTest` and `ProfilePasscodeStoreTest` run the wrap, the staged write and the encrypted rate limit on a device via `connectedDebugAndroidTest` (R-39) |

**The lock is built, and what it is worth is stated rather than implied.** Six to twelve digits behind
PBKDF2, or the platform's own biometric prompt from API 28 where the user asks for it. Unlock tickets live
in memory and nowhere else, so a cold start is locked and no reboot or background kill can leave one open,
and the delay before a relock is evaluated against the clock when the ticket is read rather than expired by
an event — otherwise the media service and the UI could answer the same question differently. The curtain
replaces the app's content instead of covering it, because an overlay leaves what it hides in the semantics
tree and `MiniPlayer` marks its title as a polite live region, so TalkBack would read the locked account's
book aloud over the passcode field. There is no schema change: the record's existence *is* the fact, so
there is no flag anywhere that can disagree with it. ADR-0023 carries the threat model this all follows
from — somebody holding this phone, already unlocked, who is not the account's owner — and the arithmetic
that says the verifier does not resist an attacker holding the file. Everything below is what that leaves
open.

**Recovery is wired and remains intentionally online.** The curtain's account-password path reaches
`SignInUseCase.clearIfLocked`, including exhausted and unreadable records, and the switcher can reach another
profile. Signing in again needs the server and clears the curtain rather than pretending a forgotten
passcode can be recovered offline. The remaining hazard is R-44: the ordinary reauthentication route can
also clear a lock without explaining that side effect before it navigates away.

**The relock delay has a production caller.** `ProcessLockWatcher` forwards activity lifecycle transitions to
`ProfileLockGate`, ignores configuration changes, and the tests cover its wiring as well as the gate's clock
arithmetic. Choosing whether `Immediately` is the right default still needs human device UX evidence; the
feature is no longer dead code.

**API 26 and 27** have no `android.hardware.biometrics.BiometricPrompt` — it starts at 28 — and the older
`FingerprintManager` would need this app to draw its own dialogue, could not report whether the sensor is
strong, and could not be exercised by any test here either. Those two levels get a row disabled with the
reason written on it rather than a hidden one, because a hidden row was reported from a Phase 5 device run
as a feature that had never been built. ADR-0023 records why `androidx.biometric` was refused instead of
adopted, including the measured detail that decided it: its own API 26/27 compatibility path constructs an
`androidx.appcompat.app.AlertDialog`, which throws under this app's platform-parented theme. That is a
crash on the two oldest supported levels, in a path nothing in this repository can reach. The passcode is
the floor on every release, so no level is left without a lock; what §3.2 offers as *"PIN or biometric"* is
one option rather than two on the oldest two.

**The app-switcher thumbnail is not suppressed anywhere, and this was checked rather than assumed.** There
is no `setRecentsScreenshotEnabled`, no `FLAG_SECURE` and no `excludeFromRecents` in the tree at any level,
so the preview a locked profile leaves in the app switcher can still show the shelf it was last on, and the
curtain is drawn only once the app is resumed. §15 makes screenshot protection *optional* for these screens,
so this is a gap against ADR-0023's own threat model rather than against a stated criterion — and it is the
threat model that makes it matter, because the person holding the unlocked phone is exactly the person who
can open the app switcher. The shape of a fix is known and the trade is not settled:
`setRecentsScreenshotEnabled(false)` exists from API 33 and covers 33 and above only, while below that the
one available tool is `FLAG_SECURE`, which blocks screenshots for the whole window rather than only its
thumbnail — and §15's other half says not to do that globally without a reason.

**8.12's other half is unmet rather than satisfied by proximity.** The requirement asks for the lock
*"especially for admin accounts"*, and what ships is offered per profile with nothing behind it: an
administrator can decline it and nothing asks twice. Closing it needs a decision before it needs work —
whether this app may nag, or refuse to proceed for, an admin account with no passcode — and nobody has made
that decision.

**The automated evidence now spans JVM policy/UI tests and one connected store tier.** `PasscodeKdfTest`,
`AutoStartDecisionTest`, `ProfileLockGateTest`, process-watcher tests, and Robolectric curtain/switcher tests
cover policy and disclosed copy. On 2026-08-23 the 27-case `:core:datastore` connected tier passed against a
real AndroidKeyStore on API 36. The biometric system prompt, key invalidation/enrollment behavior, TalkBack
reading order, and API-26/27 disabled row still require device evidence; no other module has an instrumented
tier.

**The four bypasses the curtain names on itself are not in this table**, because a disclosed limit of a
feature is not an unmet requirement. They are in this document's final section with the reason each one
stays.

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
| ROUTE-002 | **Auto-play never starts when the profile is locked.** | **Closed 2026-08-21** |
| DL-004 / DL-005 | **Automatic downloads obey their own cellular setting.** The scheduler asked `allowsCellular(ManualDownload)` whoever had called it, so `smartDownloadsOnCellular` was stored, shown in Settings and read by nothing. The category now travels with the enqueue, and a manual tap can relax a constraint a sweep set. | **Closed 2026-08-23 — R-54** |
| ROUTE-002 | **`Ask` is `Ready` plus the media notification**, not a separate dismissible prompt. | Open |
| ROUTE-002 | **One control decides what a car does.** The global "auto-play when a car connects" switch is gone; `PlaybackService.onPostConnect` resolves the car's own policy through `CarConnection` and the same `AutoStartDecision` the headset path uses, so the per-device warning and the `Arm only` default now apply to a car too. A stored `true` on the old switch seeds the car's policy as `AutoPlay`, so nobody's choice changes meaning. | **Closed 2026-08-23 — R-55** |
| PLAY-006 | **Advanced buffer values** — explicit minimum, maximum, playback-start, rebuffer-start, target bytes. Only the five presets are offered. | Open |

**The lock clause is enforced**, and by a pure function rather than by a branch buried in the watcher.
`AutoStartDecision.decide` takes a device's policy and one boolean and returns what should happen, which
turns ROUTE-002's sentence into a truth table that can be pointed at — `OutputDeviceWatcher` previously had
no test of its policy `when` at all, so this is also the first coverage that branch has ever had. It
suppresses `ArmOnly` and `Ask` as well as `AutoPlay`, which is stricter than the sentence and is argued in
ADR-0023 rather than assumed: arming makes no sound, and it puts the locked account's title, author and
cover on the lock screen one headset press from audio, and this app has no way to intercept that press —
there is no `onPlayerCommandRequest` and no `ForwardingPlayer` anywhere in it. `DevicePolicy.Never` still
reports `None` rather than `Suppressed`, because the lock changed nothing about that device and a log line
claiming otherwise would be false. The car-connect path in `PlaybackService.onPostConnect` and ROUTE-003's
startup restore are gated by the same guard; ROUTE-003 has no lock clause of its own, so extending it was a
decision and is recorded as one. Product priority 1 is untouched, structurally rather than by comment: the
watcher consults the guard *after* `actions.isBusy()`, so a profile already playing is never interfered
with.

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
| MGR-002 | **Closed 2026-08-23. Cover upload is captured.** `capture-contracts.sh` reads the item's own cover back and re-posts it as multipart, which is what let the shape be recorded without the script inventing an image. `item-cover-upload.json` confirms the part name is `cover` and that the filename's extension is what the server validates; `item-cover-upload-forbidden.json` records the refusal. | **Closed** |
| MGR-007 | **Closed 2026-08-23. The embed's contract is captured, both halves.** `item-embed-metadata.json` records the `200` that means *queued*, `item-embed-metadata-repeated.json` the documented duplicate `400` (which is `text/html`, not `text/plain` as the KDoc had claimed), and `socket-embed-task.json` the four lifecycle frames from a real embed on 2.36.0. All six fields `TaskFrames.parse` reads are confirmed present, `libraryItemId` is nested in `data`, and the title-bearing private half is proven not to survive a parse. What is still source-derived is the **failure** path: `isFailed` and `error` are read and no capture provokes them. | **Closed**, except the failure path |
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

| MGR-007 | **The embed's outcome is observed, not assumed.** `socket-embed-task.json` captures `task_started`, `track_started`, `track_finished` and `task_finished` from a real embed, and `TaskFramesTest` runs the parser over it. All six fields the app reads are confirmed, `libraryItemId` is confirmed nested inside `data`, and the private half the KDoc predicted — the title in two fields, filesystem paths in three more — is confirmed present and confirmed unread. | **Closed 2026-08-23.** The failure path (`isFailed`, `error`) is still source-derived |
| MGR-002 / MGR-007 / USER-003 | **The three privileged writes are contract-proven.** Cover upload, metadata embed and user activation now have captured fixtures for the permitted *and* refused shapes, plus the duplicate-embed `400`, all against a real 2.36.0. `AbsManagementContractTest` replays them through the real Retrofit adapter and asserts path, method, bearer, multipart field name, query string, exact PATCH body and error mapping. | **Closed 2026-08-23 — R-51.** The review's third P0 |

## Phase 6 — Android Auto, polish, release

Phase 6 delivers seven things (PRODUCT_SPEC 1750): a browsable media library, adaptive UI, accessibility,
diagnostics, privacy/security docs, performance profiling and a release pipeline. **Two of the seven are
already built** — they arrived early because earlier phases needed them — and the table below is what an
audit on 2026-08-20 found for the rest, rather than what the phase heading implies.

| Deliverable | State on entering the phase |
| --- | --- |
| **Browsable media library** | **Built.** `AutoLibrary` publishes the tree; `PlaybackService.LibraryCallback` implements `onGetLibraryRoot` (including the *recent* root behind the car's resume tile), `onGetChildren`, `onGetItem`, `onSearch`, `onGetSearchResult` and the spoken-query path through `onSetMediaItems`. The Chapters tab carries per-chapter completion against a book-progress header. An older build passed discovery/media-button resume in a car on 2026-08-14; the current tree, newer browse additions, and rendered DHU surface remain unverified — see R-10. |
| **Diagnostics** | **Built.** The event log, the capability rows, the sync checklist and the copyable debug console (PRODUCT_SPEC 14.4) all shipped in earlier phases. |
| **Adaptive UI** | **Built 2026-08-20.** Two panes on the book screen and the player, capped sign-in, centred lists. A signed-in phone-width pass exists; wide/tablet/foldable and landscape behavior remain unverified on hardware (R-07). |
| **Accessibility** | **Enforced by test; ordinary phone rendering reviewed, TalkBack unverified.** The net covers every screen including the player, the mini player and the shelf, several at a doubled font scale. It has found two real defects. R-29. |
| **Privacy/security docs** | **Rewritten 2026-08-20.** |
| **Performance profiling** | **Harness built, numbers not taken.** `:benchmark` measures cold start, scroll frame timing and Home's peak memory over a seeded 2,000-book library, and generates the baseline profile; every task needs a device, which CI has not. Two of PRODUCT_SPEC 17.3's four numbers stay manual — player start from a cached book and no-ANR-under-stress both need a real server. `docs/benchmark.md` is the runbook. R-25, R-27, R-58. |
| **Release pipeline** | **Partly.** PR and main workflows run wrapper validation, a secret scan, `verifyDebug` with warnings-as-errors, a Room schema diff, release lint and an unsigned release assembly. Dependency verification is `strict` over 890 pinned components. **The SBOM, the vulnerability scan and the mapping archive have landed**, the first two unblocked by ADR-0024's licence decision and the third never actually blocked. Missing: a changelog generated from labelled changes (needs a label convention) and managed-device tests (needs a runner). R-05, R-07. |

### Signed-in phone findings — 2026-08-23

Thirty-six private captures cover every naturally reachable route/page and non-destructive sheet in one
signed-in Android 16 session. They remain ignored because real server/account identity and library state are
private. The pass verified the cover/play direction, one real authenticated author portrait, the BookWave
label, and one Samsung adaptive-icon mask. It also made these gaps observable:

| Requirement | Gap | State |
| --- | --- | --- |
| LIB-002 | The first APK reported **0 books** above populated grouped cards. Counts now derive unique books from the active shape (and the uncapped shelf source total); mutation-proved tests and corrected-device captures show 468 Series, 496 Author, and 372 Genre books. | Closed 2026-08-23 |
| MGR-008 / accessibility | The first APK's parent card consumed Genre **Edit**. Browse and Edit are now sibling targets; a physical-pointer regression was mutation-proved and the corrected APK opened the confirmation without navigating or writing. | Closed 2026-08-23; TalkBack pass remains |
| LIB-002 | Switching axes reused the previous list offset. List composition is now keyed by axis, Books view, and focus. | Closed in source/test; dedicated device automation remains desirable |
| SET-001 / SET-002 | Server, Playback, and About are long fixed-tab pages whose navigation disappears deep in the content. Missing Downloads/Devices/Appearance/Privacy categories will make this worse. | Open information-architecture work |
| PLAY-001 / notifications | Debug Console reported notifications **Blocked** on the fresh install/session, with no proactive onboarding path. | Open; permission discovery |
| LIB-004 / MGR-001 / MGR-005 | Book detail duplicates its title; a destructive database-removal action has the same hierarchy as benign overflow actions; Metadata is a long flat form without current-cover preview and makes its blank add-series row look duplicated. | Open UI/UX work |
| LIB-003 | Series detail is a sparse list without a cover/summary/progress header or direct per-book play/resume actions. | Open UI/UX work |

Player screenshots were not manufactured because starting an arbitrary real title writes server progress.
Android Auto needs a DHU/head-unit host; phone screenshots cannot close it. Offline/error/locked/destructive,
active-download, large-text, TalkBack, landscape, and wide-window states remain unexercised rather than failed.

| Requirement | Gap | State |
| --- | --- | --- |
| §3.3 / packaging | **The release `applicationId` is now `org.homebord.bookwave`.** Kotlin packages and namespaces intentionally remain `com.example.shelfplayer`; Play sees only the application id. | **Closed — ADR-0024** |
| §4 / §129 / §51 | **Adaptive UI is not built.** **Closed 2026-08-20.** `WindowWidth` reads the window size class; the book screen and the player go to two panes when there is room, sign-in is capped, and lists centre rather than stretch. What is unverified is how it looks on real hardware — R-07. Every screen is a single phone-width column, so a tablet, a foldable and a split-screen window all get the phone layout stretched across the available space. §51 makes adaptive layout a release requirement, not a nicety. | **Closed** |
| §51 / 2.10 | **Accessibility semantics are now enforced by a test; the device half is not.** `AccessibilityAssertions` walks everything the semantics tree reports as clickable and fails on an unlabelled control or one under Material's 40dp minimum. It covers Settings, the book screen, the downloads queue and the profile switcher — every screen with a destructive action — and one of them renders at a doubled font scale. It found a defect immediately: every genre and tag was a disabled `SuggestionChip`, which still publishes an `OnClick`, so a screen reader announced each as a dead button. The player, the mini player and the shelf are covered as of 2026-08-20, and the extension found a second defect: at a doubled font scale the player's secondary control row laid out **4dp tall** — present, announced and impossible to hit — because the square artwork claimed the column's whole width as its height regardless of what was left. The artwork is weighted now, so large text shrinks the cover instead of crushing the transport. What no JVM test can reach at all is whether a label is *useful*, whether contrast is sufficient, and what TalkBack does with the reading order. R-29, R-35. | Partly closed |
| §14.5 / packaging | **`PRIVACY.md` and `SECURITY.md` describe Phase 0.** **Closed 2026-08-20.** Both rewritten for a build that talks to a server. That was true for one phase and has been wrong for five; `PRIVACY.md` is the document a user reads to decide whether to trust a client with their server's credentials. | **Closed** |
| 18 | **`docs/release.md` lists resolved blockers.** **Closed 2026-08-20.** Rewritten; it had named MGR-006 as open after ADR-0021 settled it, and and lists integration tests as blocked on "endpoints to test", which Phase 1 delivered. | **Closed** |
| 18 / 24 | **Closed 2026-08-20.** `versionName` had stuck at `0.9.6-auto-shelves` for nine builds; it moves with each one now (`0.9.11-car-and-pause`, code 37). The debug console prints it, so a field report identifies the wrong build. | **Closed** |
| PLAY-001 / 11.1 | **The car's now-playing screen names the book, not the chapter.** The browse tree answers "how far through each chapter am I" (a completion bar per row, and a header row giving progress through the whole book), but the *playback* screen still shows title and author only. An app cannot draw in a car, so the only way to change that line is to change what the session reports as the current item's `MediaMetadata` — and the book is one `MediaItem` over a custom concatenating source (ADR-0016), so `replaceMediaItem` re-prepares it and stops the audio. The supported alternative is a `ForwardingSimpleBasePlayer` wrapping the ExoPlayer and rewriting the metadata in `getState()`, plus a ticker calling `invalidateState()` at each chapter boundary because position alone raises no event. **Deliberately not built here:** that wrapper sits between the media session and the player, so a mistake in it breaks the notification, the lock screen, the headset and the car at once — product priority 1 — and there is no instrumented test or head unit to catch one (R-07, R-10). | Open, needs a device first |
| PLAY-001 / LIB-002 | **The car browse root has no Downloads, Series, Authors, or Genres destinations.** Continue/Chapters/History are useful playback views but do not provide the phone library's principal browse axes. | Open; verify desired depth/distraction cost in DHU before adding |
| 17.2 / 17.3 | **Only one narrow tier has been verified on hardware by a test.** On 2026-08-23 `:core:datastore:connectedDebugAndroidTest` passed 27/27 against AndroidKeyStore on an API-36 Samsung. No other module has an instrumented tier and this task never runs in CI. The whole automated UI tier is still Robolectric at `sdk = 34`; route screenshots are observational, not UI automation. The API matrix, two-hour soak, process-death budget, Android Auto, and release APK remain unexercised. | Open, and the largest |

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
- **The four bypasses the profile lock names on its own curtain** (AUTH-005). The media notification and the
  lock-screen transport keep working; a connected car can still browse and play; downloaded audio is
  ordinary unencrypted files; and the lock does nothing against somebody who can read this phone's files.
  Each of those follows from the boundary AUTH-003 drew — *"protects profile selection, not server
  authentication semantics"* — rather than from a criterion this build failed to meet, and each has a reason
  it stays: there is no interception point for a media button in this app and ROUTE-001 treats one as
  explicit intent; blanking a head unit mid-drive to hide the active profile's own content from a physically
  present person is the worse trade; and encrypting downloaded audio is a different feature answering a
  different threat. They are accepted exposures rather than gaps **because they are disclosed in the
  product**, on the curtain, where the person relying on the lock reads them before they rely on it. A
  bypass the user has been told about is a limit of the feature; one they have not been told about is a
  defect.
- **The device credential is refused as an unlock factor.** The obvious fallback is
  `KeyguardManager.createConfirmDeviceCredentialIntent`, and it is deliberately not offered, because
  ADR-0023's threat is somebody holding the already-unlocked phone and that person holds the device
  credential by construction. A factor the attacker already has is not a factor.
